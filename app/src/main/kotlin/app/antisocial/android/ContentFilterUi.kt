/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A coherent filter snapshot. Expiry and edits are offered behind Apply rather
 * than changing the card under a finger. Revealing never alters a saved rule. */
@Stable internal class ReaderFilterSession(val context: ContentFilterContext, initial: List<ContentRule>) {
    var applied by mutableStateOf(initial)
    var evaluatedAt by mutableLongStateOf(System.currentTimeMillis())
    var revealed by mutableStateOf(setOf<String>())
    var managing by mutableStateOf(false)
    var reviewing by mutableStateOf(false)
    var candidate by mutableStateOf<List<Post>?>(null)
    var revealTarget by mutableStateOf<String?>(null)
    fun apply(rules: List<ContentRule>) {applied=rules;evaluatedAt=System.currentTimeMillis();revealed=emptySet()}
}
@Composable internal fun rememberReaderFilters(repo: Repository, context: ContentFilterContext): ReaderFilterSession = remember(repo,context) {ReaderFilterSession(context,repo.contentFilters.rules.value)}

internal data class ReaderAssessment(val editions: List<Post>,val verdict: ContentVerdict)
/** Cancel obsolete batches cooperatively. Previously evaluated cards stay
 * coherent; an unclassified new card is never briefly exposed or marked read. */
internal fun filterProjection(repo: Repository,post: Post)=post.copy(title=repo.publicText(post.title,post.platform),body=repo.publicText(post.body,post.platform),links=post.links?.map {repo.publicUrl(it,post.platform)})
@Composable internal fun rememberFilterAssessments(repo: Repository,session: ReaderFilterSession,groups: List<List<Post>>): Map<String,ReaderAssessment> {
    val rules=session.applied
    val evaluatedAt=session.evaluatedAt
    if(rules.none {it.enabled&&it.expiresAt?.let {at->at<=evaluatedAt}!=true})return remember(groups){groups.filter {it.isNotEmpty()}.associate {CrossPosts.originalKey(it.first()) to ReaderAssessment(it,ContentVerdict())}}
    val matcher=remember(rules){ContentFilters.compile(rules)}
    val result by produceState<Map<String,ReaderAssessment>>(emptyMap(),matcher,groups,evaluatedAt) {
        value=withContext(Dispatchers.Default) {buildMap {
            groups.forEach {posts->
                ensureActive()
                if(posts.isNotEmpty())put(CrossPosts.originalKey(posts.first()),ReaderAssessment(posts,matcher.evaluate(posts.map {filterProjection(repo,it)},session.context,evaluatedAt)))
            }
        }}
    }
    return result
}
internal fun Map<String,ReaderAssessment>.decision(posts: List<Post>): ContentVerdict? = posts.firstOrNull()?.let {get(CrossPosts.originalKey(it))}?.takeIf {it.editions==posts}?.verdict

@Composable internal fun PendingFilterCard() {Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f),modifier=Modifier.fillMaxWidth().testTag("Evaluating feed filters")){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Text("Applying your filters…",Modifier.padding(start=12.dp),fontSize=12.sp)}}}

@Composable internal fun FilterControls(repo: Repository,session: ReaderFilterSession,groups: List<List<Post>>,assessments: Map<String,ReaderAssessment>,compact:Boolean=false) {
    val rules by repo.contentFilters.rules.collectAsState()
    val error by repo.contentFilters.error.collectAsState()
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){delay(30_000);now=System.currentTimeMillis()}}
    val expired=session.applied.any {it.enabled&&it.expiresAt?.let {at->at>session.evaluatedAt&&at<=now}==true}
    val changed=rules!=session.applied||expired
    val count=remember(groups,assessments){groups.count {assessments.decision(it)?.withheld==true}}
    if(compact) {
        var options by remember {mutableStateOf(false)}
        Box(Modifier.testTag("Reader filter controls")) {
            IconButton(onClick={options=true},modifier=Modifier.testTag("Manage feed filters")) {
                BadgedBox(badge={if(count>0||changed)Badge()}) {
                    Icon(if(error!=null)Icons.Rounded.WarningAmber else if(session.context.surface==FilterSurface.SEARCH)Icons.Rounded.Search else Icons.Rounded.Tune,if(error!=null)"Filters need attention"else "Feed filters",Modifier.size(20.dp),tint=if(error!=null)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded=options,onDismissRequest={options=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                DropdownMenuItem(text={Text(if(error!=null)"Repair filters"else "Edit filters")},onClick={options=false;session.managing=true})
                if(count>0)DropdownMenuItem(text={Text("Review $count filtered ${if(count==1)"post"else "posts"}")},modifier=Modifier.testTag("Review filtered posts"),onClick={options=false;session.reviewing=true})
                if(changed)DropdownMenuItem(text={Text("Apply filter changes")},modifier=Modifier.testTag("Apply feed filters"),onClick={options=false;session.apply(rules)})
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().heightIn(min=40.dp).padding(horizontal=12.dp).testTag("Reader filter controls"),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={session.managing=true},contentPadding=PaddingValues(horizontal=6.dp),modifier=Modifier.testTag("Manage feed filters")) {
            Icon(if(session.context.surface==FilterSurface.SEARCH)Icons.Rounded.Search else Icons.Rounded.Tune,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("Filters",fontSize=12.sp)
        }
        if(error!=null)Text("Paused · repair rules",Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.error)
        else if(count>0)TextButton(onClick={session.reviewing=true},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp)) {Text("$count filtered · review",fontSize=11.sp)}
        else Text(if(session.applied.none {it.enabled})"Your feed, your rules"else "${session.applied.count {it.enabled}} rules · on device",Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(changed)TextButton(onClick={session.apply(rules)},modifier=Modifier.testTag("Apply feed filters")) {Text("Apply",fontSize=12.sp)}
    }
}

@Composable internal fun ReaderFilterDialogs(repo: Repository,session: ReaderFilterSession,groups: List<List<Post>>,assessments: Map<String,ReaderAssessment>) {
    if(session.managing||session.candidate!=null)ContentFilterSheet(repo,session.context,groups,session.candidate,onDismiss={session.managing=false;session.candidate=null})
    if(session.reviewing)FilteredItemsSheet(groups,assessments,session,onDismiss={session.reviewing=false})
}

@Composable internal fun FilteredPost(verdict: ContentVerdict,key: String,session: ReaderFilterSession,onEdit: ()->Unit,content: @Composable ()->Unit) {
    val revealed=key in session.revealed
    if(!verdict.withheld||revealed) {
        if(revealed)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Revealed · ${verdict.reason}",Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            TextButton(onClick={session.revealed=session.revealed-key}){Text("Collapse",fontSize=11.sp)}
        }
        content()
    }else if(verdict.action==ContentAction.COLLAPSE) {
        Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f),modifier=Modifier.fillMaxWidth().testTag("Filtered post $key")) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FilterAlt,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                    Text(verdict.reason,Modifier.weight(1f).padding(start=10.dp),fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Text(verdict.matches.flatMap {it.reasons}.distinct().joinToString(" · "),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
                Row {
                    TextButton(onClick={session.revealed=session.revealed+key},modifier=Modifier.testTag("Reveal filtered $key")){Text("Show post")}
                    TextButton(onClick=onEdit){Text("Adjust rules")}
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FilteredItemsSheet(groups: List<List<Post>>,assessments: Map<String,ReaderAssessment>,session: ReaderFilterSession,onDismiss: ()->Unit) {
    val matches=remember(groups,assessments){groups.mapNotNull {posts->assessments.decision(posts)?.takeIf {it.withheld}?.let {posts to it}}}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f).testTag("Filtered items"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Text("Filtered items",style=MaterialTheme.typography.headlineSmall);Text("From the fetched batch. These haven't been marked read. Saved copies remain in your library.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))}
            items(matches,key={CrossPosts.originalKey(it.first.first())}) {(posts,verdict)->
                val post=posts.first();val key=CrossPosts.originalKey(post)
                Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f)) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(post.author,fontWeight=FontWeight.SemiBold)
                        Text(post.title.ifBlank {post.body},maxLines=4,overflow=TextOverflow.Ellipsis,fontSize=13.sp)
                        Text(verdict.reason+" · "+verdict.matches.flatMap {it.reasons}.distinct().joinToString(" · "),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick={session.revealed=session.revealed+key;session.revealTarget=key;onDismiss()},modifier=Modifier.testTag("Review reveal $key")){Text("Reveal in feed")}
                    }
                }
            }
            item {TextButton(onClick=onDismiss){Text("Done")}}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ContentFilterSheet(repo: Repository,context: ContentFilterContext,groups: List<List<Post>>,candidate: List<Post>?=null,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.91f)){ContentFiltersScreen(repo,context,groups,candidate,onClose=onDismiss)}
    }
}

@Composable internal fun ContentFiltersScreen(repo: Repository,context: ContentFilterContext=ContentFilterContext(FilterSurface.FEED),groups: List<List<Post>> = emptyList(),candidate: List<Post>?=null,onClose: (()->Unit)?=null) {
    val channels by repo.channels.channels.collectAsState()
    val rules by repo.contentFilters.rules.collectAsState()
    val error by repo.contentFilters.error.collectAsState()
    val androidContext=LocalContext.current;val scope=rememberCoroutineScope()
    var feedSites by remember(repo){mutableStateOf(repo.prefs.selected)}
    DisposableEffect(repo){val stop=repo.prefs.observePlatforms {feedSites=repo.prefs.selected};onDispose(stop)}
    var editing by remember {mutableStateOf<ContentRule?>(candidate?.let {newRule(it,context)})}
    var notice by remember {mutableStateOf<String?>(null)}
    var deleted by remember {mutableStateOf<ContentRule?>(null)}
    var imported by remember {mutableStateOf<List<ContentRule>?>(null)}
    var selection by remember {mutableStateOf(setOf<String>())}
    var reset by remember {mutableStateOf(false)}
    val samples by produceState<List<List<Post>>>(emptyList(),groups,repo) {
        val fetched=if(groups.isEmpty())withContext(Dispatchers.IO) {
            // A bounded preview of ordinary cached posts, never a new crawl.
            repo.dao.filterPreviewPosts().mapNotNull {row->runCatching {repo.gson.fromJson(row.payload,Post::class.java)}.getOrNull()?.let {listOf(it)}}
        }else groups
        value=withContext(Dispatchers.Default){fetched.map {posts->posts.map {filterProjection(repo,it)}}}
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->if(uri!=null)scope.launch {
        notice=runCatching {withContext(Dispatchers.IO){androidContext.contentResolver.openOutputStream(uri)?.use {it.write(ContentRuleCodec.encode(rules).toByteArray())} ?: kotlin.error("Couldn't open the file.")};"Filter recipe exported."}.getOrElse {it.message}
    }}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null)scope.launch {
        runCatching {withContext(Dispatchers.IO){androidContext.contentResolver.openInputStream(uri)?.use {input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=1_000_000){"Filter recipe is too large."};out.write(buffer,0,n)}
            ContentRuleCodec.decode(out.toString("UTF-8"))
        } ?: kotlin.error("Couldn't open the file.")}}.onSuccess {imported=it;selection=it.map {r->r.id}.toSet()}.onFailure {notice=it.message ?: "Couldn't read this recipe."}
    }}
    Box(Modifier.fillMaxSize().testTag("Feed filter manager")) {
        val edit=editing
        if(edit!=null)ContentRuleEditor(edit,candidate,context,samples,preferredSites=feedSites,scopeDescription={readableScope(it,channels,samples)},onCancel={editing=null},onSave={rule->runCatching {repo.contentFilters.save(rule)}.onSuccess {editing=null;notice="Rule saved. Use Apply in the reader when you're ready."}.onFailure {notice=it.message}})
        else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Feed filters",style=MaterialTheme.typography.headlineSmall);Text("Less noise. Your rules.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};onClose?.let {IconButton(onClick=it){Icon(Icons.Rounded.Close,"Close feed filters")}}}}
            item {Text("Choose what reaches your feed and search. You can always reveal filtered posts. Your reading order stays chronological.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(error!=null)item {Text(error!!,color=MaterialTheme.colorScheme.error);TextButton(onClick={reset=true}){Text("Reset unreadable rules")}}
            item {FilledTonalButton(onClick={notice=null;editing=newRule(candidate.orEmpty(),context)},enabled=error==null,modifier=Modifier.testTag("Create feed filter")){Icon(Icons.Rounded.Add,null);Spacer(Modifier.width(8.dp));Text("Create a rule")}}
            if(rules.isEmpty())item {Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f)){Column(Modifier.padding(20.dp)){Icon(Icons.Rounded.Tune,null,tint=MaterialTheme.colorScheme.primary);Text("Start with what bothers you",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=10.dp));Text("Use “Filter like this…” on a post, or create a rule for a phrase, hashtag, link or kind of media. Nothing is filtered by default.",fontSize=13.sp,modifier=Modifier.padding(top=8.dp))}}}
            items(rules,key={it.id}) {rule->
                val expired=rule.expiresAt?.let {it<=System.currentTimeMillis()}==true
                Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f)) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically){Text(rule.name,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Switch(checked=rule.enabled&&!expired,onCheckedChange={enabled->runCatching {repo.contentFilters.save(rule.copy(enabled=enabled,expiresAt=if(expired&&enabled)null else rule.expiresAt))}.onFailure {notice=it.message}},modifier=Modifier.testTag("Enable filter ${rule.id}"))}
                        RuleAudience(rule,feedSites)
                        Text("${actionLabel(rule.action)} · ${readableScope(rule,channels,samples)}"+if(expired)" · Expired"else rule.expiresAt?.let {" · Until "+DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))}.orEmpty(),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(ruleSummary(rule),fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                        Row {TextButton(onClick={notice=null;editing=rule},modifier=Modifier.testTag("Edit filter ${rule.id}")){Text("Edit & preview")};TextButton(onClick={runCatching {repo.contentFilters.delete(rule.id)}.onSuccess {deleted=rule;notice="Rule deleted."}.onFailure {notice=it.message}}){Text("Delete")}}
                    }
                }
            }
            item {HorizontalDivider();Row {TextButton(onClick={import.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=error==null){Text("Import recipe")};TextButton(onClick={export.launch("antisocial-filters.json")},enabled=error==null&&rules.isNotEmpty()){Text("Export recipe")}};Text("Recipes contain explicit rules and their scopes, never reading history or credentials. Network protection has its own settings.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            notice?.let {message->item {Text(message,fontSize=13.sp,color=MaterialTheme.colorScheme.primary);deleted?.let {rule->TextButton(onClick={runCatching {repo.contentFilters.save(rule)}.onSuccess {deleted=null;notice="Deletion undone."}.onFailure {notice=it.message}}){Text("Undo deletion")}}}}
        }
        if(edit!=null&&notice!=null)Snackbar(modifier=Modifier.align(Alignment.BottomCenter),action={TextButton(onClick={notice=null}){Text("Dismiss")}}){Text(notice!!)}
    }
    if(reset)AlertDialog(onDismissRequest={reset=false},title={Text("Reset feed filters?")},text={Text("This removes the unreadable rule document. Subscriptions and saved material are kept.")},confirmButton={TextButton(onClick={repo.contentFilters.reset();reset=false}){Text("Reset rules")}},dismissButton={TextButton(onClick={reset=false}){Text("Cancel")}})
    imported?.let {list->AlertDialog(onDismissRequest={imported=null},title={Text("Preview recipe")},text={LazyColumn(Modifier.heightIn(max=380.dp)){item {Text("Choose rules to add. Their site choices, limits and end dates are kept. Your existing rules stay in place.",fontSize=13.sp)};items(list,key={it.id}) {rule->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=rule.id in selection,onCheckedChange={selection=if(it)selection+rule.id else selection-rule.id});Column {Text(rule.name);RuleAudience(rule,feedSites);Text("${actionLabel(rule.action)} · ${scopeLabel(rule.scope)} · ${ruleSummary(rule)}",fontSize=11.sp,maxLines=3)}}}}},confirmButton={TextButton(onClick={runCatching {repo.contentFilters.importRules(list.filter {it.id in selection})}.onSuccess {imported=null;notice="Rules imported. Use Apply in the reader when you're ready."}.onFailure {notice=it.message}},enabled=selection.isNotEmpty()){Text("Add ${selection.size} rules")}},dismissButton={TextButton(onClick={imported=null}){Text("Cancel")}})}
}

private fun newRule(posts: List<Post>,context: ContentFilterContext): ContentRule {
    val account=posts.firstOrNull()?.let(CrossPosts::originalAccount)
    return ContentRule("filter:"+UUID.randomUUID(),"",scope=if(account!=null)ContentScope.ACCOUNT else if(context.surface==FilterSurface.SEARCH)ContentScope.SEARCH else ContentScope.EVERYWHERE,scopeKey=account ?: context.search.orEmpty(),surfaces=if(account==null&&context.surface==FilterSurface.SEARCH)setOf(FilterSurface.SEARCH)else FilterSurface.entries.toSet())
}
private fun actionLabel(action: ContentAction)=when(action){ContentAction.KEEP->"Always show";ContentAction.COLLECT->"Collect repeats";ContentAction.COLLAPSE->"Collapse";ContentAction.HIDE->"Hide"}
private fun scopeLabel(scope: ContentScope)=when(scope){ContentScope.EVERYWHERE->"Everywhere";ContentScope.ACCOUNT->"Account";ContentScope.CHANNEL->"Channel";ContentScope.SEARCH->"This search"}
private fun readableScope(rule: ContentRule,channels: List<PersonalChannel>,samples: List<List<Post>>): String=when(rule.scope) {
    ContentScope.EVERYWHERE->"Everywhere"
    ContentScope.ACCOUNT->samples.asSequence().flatten().firstOrNull {CrossPosts.originalAccount(it)==rule.scopeKey}?.author?.removePrefix("@") ?: (Platform.entries.firstOrNull {it.id==rule.scopeKey.substringBefore(':')}?.label?.let {"Selected $it account"} ?: "Selected account")
    ContentScope.CHANNEL->channels.firstOrNull {it.id==rule.scopeKey}?.name ?: "Unavailable channel"
    ContentScope.SEARCH->runCatching {java.net.URI(rule.scopeKey).let {DarwinRoutes.queryValue(it,"q") ?: DarwinRoutes.queryValue(it,"query")}}.getOrNull()?.let {"Search: "+it.take(100)} ?: "Selected search"
}
private fun ruleSummary(r: ContentRule)=buildList {addAll(r.phrases.map {"“$it”"});addAll(r.hashtags.map {"#"+it.removePrefix("#")});addAll(r.subreddits.map {"r/$it"});addAll(r.servers.map {"On $it"});addAll(r.domains.map {"Links to $it"});addAll(r.types.map {it.name.lowercase()});if(r.repeatsOnly)add("confirmed repeats");if(r.exceptPhrases.isNotEmpty())add("except "+r.exceptPhrases.joinToString())}.joinToString(" · ").ifBlank {if(r.platforms.isNotEmpty())"All posts from selected sites"else "All posts from this account"}

private fun surfaceIcon(surface: FilterSurface): ImageVector=if(surface==FilterSurface.SEARCH)Icons.Rounded.Search else Icons.Rounded.DynamicFeed
private fun surfaceLabel(surface: FilterSurface)=if(surface==FilterSurface.SEARCH)"Search"else "Feed"
private fun scopeIcon(scope: ContentScope): ImageVector=when(scope){ContentScope.EVERYWHERE->Icons.Rounded.Public;ContentScope.ACCOUNT->Icons.Rounded.Person;ContentScope.CHANNEL->Icons.Rounded.Folder;ContentScope.SEARCH->Icons.Rounded.Search}

@Composable private fun RuleAudience(rule: ContentRule,feedSites: List<Platform>) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        FilterSurface.entries.filter {it in rule.surfaces}.forEach {surface->Row(verticalAlignment=Alignment.CenterVertically){Icon(surfaceIcon(surface),null,Modifier.size(16.dp));Text(surfaceLabel(surface),fontSize=12.sp,modifier=Modifier.padding(start=4.dp))}}
        if(rule.platforms.isEmpty())Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.Public,null,Modifier.size(16.dp));Text("All sites",fontSize=12.sp,modifier=Modifier.padding(start=4.dp))}
        else (feedSites+PlatformOrder.sort(rule.platforms)).distinct().filter {it in rule.platforms}.forEach {platform->PlatformMark(platform,22.dp)}
    }
}

/** Matches the reader's chosen tabs. Saved selections outside those tabs remain
 * reachable; the rest of the catalogue is an explicit expansion. */
@Composable private fun FilterSitePicker(selected: Set<Platform>,preferred: List<Platform>,onChange: (Set<Platform>)->Unit) {
    var expanded by rememberSaveable {mutableStateOf(false)}
    val familiar=remember(preferred,selected){(preferred+PlatformOrder.sort(selected)).distinct()}
    val all=remember(familiar){(familiar+PlatformOrder.popular).distinct()}
    val visible=if(expanded)all else familiar
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Posts from",fontWeight=FontWeight.SemiBold)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterSiteIcon("All sites",selected.isEmpty(),{onChange(emptySet())},Modifier.testTag("Filter all sites")){Icon(Icons.Rounded.Public,null,Modifier.size(26.dp))}
            visible.forEach {platform->key(platform.id){
                FilterSiteIcon(platform.label,platform in selected,{onChange(if(platform in selected)selected-platform else selected+platform)},Modifier.testTag("Filter site ${platform.id}")){PlatformMark(platform,32.dp,contentDescription=null)}
            }}
            if(all.size>familiar.size)FilterSiteIcon(if(expanded)"Fewer sites"else "More sites",expanded,{expanded=!expanded},Modifier.testTag("More filter sites")){Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null,Modifier.size(26.dp))}
        }
    }
}

@Composable private fun FilterSiteIcon(label: String,selected: Boolean,onClick: ()->Unit,modifier: Modifier=Modifier,content: @Composable ()->Unit) {
    val colors=MaterialTheme.colorScheme
    Surface(selected=selected,onClick=onClick,shape=RoundedCornerShape(14.dp),color=if(selected)colors.secondaryContainer else colors.surfaceVariant.copy(alpha=.35f),border=BorderStroke(1.dp,if(selected)colors.primary.copy(alpha=.6f)else colors.outlineVariant),modifier=modifier.size(48.dp).semantics {contentDescription=label}) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){content()}
    }
}

@Composable private fun MediaFilterChoices(selected: Set<ChannelContentType>,site: FilterSitePresentation,onChange: (Set<ChannelContentType>)->Unit) {
    Text("Post includes",fontWeight=FontWeight.Medium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ChannelContentType.entries.forEach {type->
            val (label,icon)=when(type){ChannelContentType.TEXT->"Text" to Icons.Rounded.TextFields;ChannelContentType.IMAGES->site.photos to Icons.Rounded.Image;ChannelContentType.VIDEO->"Video" to Icons.Rounded.PlayCircle;ChannelContentType.AUDIO->"Audio" to Icons.Rounded.Headphones;ChannelContentType.LINKS->"Links" to Icons.Rounded.Link}
            FilterChip(selected=type in selected,onClick={onChange(if(type in selected)selected-type else selected+type)},label={Text(label)},leadingIcon={Icon(icon,null,Modifier.size(18.dp))})
        }
    }
}

@Composable private fun ContentRuleEditor(initial: ContentRule,candidate: List<Post>?,context: ContentFilterContext,samples: List<List<Post>>,preferredSites: List<Platform>,scopeDescription: (ContentRule)->String,onCancel: ()->Unit,onSave: (ContentRule)->Unit) {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    var rule by remember(initial.id){mutableStateOf(initial)}
    var phraseText by remember(initial.id){mutableStateOf(initial.phrases.joinToString("\n"))}
    var tagText by remember(initial.id){mutableStateOf(initial.hashtags.joinToString("\n"))}
    var domainText by remember(initial.id){mutableStateOf(initial.domains.joinToString("\n"))}
    var subredditText by remember(initial.id){mutableStateOf(initial.subreddits.joinToString("\n") {"r/$it"})}
    var serverText by remember(initial.id){mutableStateOf(initial.servers.joinToString("\n"))}
    var exceptionText by remember(initial.id){mutableStateOf(initial.exceptPhrases.joinToString("\n"))}
    var advanced by rememberSaveable(initial.id){mutableStateOf(false)}
    var preview by remember {mutableStateOf(false)}
    var errors by remember {mutableStateOf<List<String>>(emptyList())}
    val account=candidate?.firstOrNull()?.let(CrossPosts::originalAccount) ?: initial.scopeKey.takeIf {initial.scope==ContentScope.ACCOUNT}
    val singleSite=rule.platforms.singleOrNull()
    val siteStyle=filterSitePresentation(singleSite)
    var inlineTags by remember(initial.id){mutableStateOf(initial.hashtags.isNotEmpty()||(initial.platforms.singleOrNull()?.let {filterSitePresentation(it).tags!=null}==true))}
    var inlineMedia by remember(initial.id){mutableStateOf(initial.platforms.size==1||initial.types.isNotEmpty())}
    fun terms(value: String)=value.split('\n').map(String::trim).filter(String::isNotEmpty)
    val previewMatcher=remember(rule,preview){if(preview)ContentFilters.compile(listOf(rule))else null}
    val previewMatches=remember(previewMatcher,samples,context){samples.filter {previewMatcher?.evaluate(it,context,System.currentTimeMillis())?.matches?.isNotEmpty()==true}}
    val previewResult=previewMatcher?.let {Triple(it,previewMatches,rule)}
    LazyColumn(Modifier.fillMaxSize().testTag("Feed filter editor"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onCancel){Icon(Icons.Rounded.ArrowBack,"Back to rules")};Text("Shape your feed",style=MaterialTheme.typography.headlineSmall)}}
        candidate?.firstOrNull()?.let {post->item {Text(post.author,fontWeight=FontWeight.SemiBold);Text(post.title.ifBlank {post.body},fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        item {OutlinedTextField(rule.name,{rule=rule.copy(name=it);preview=false},label={Text("Rule name")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Filter rule name"))}
        item {
            Text("Use in",fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterSurface.entries.forEach {surface->FilterChip(selected=surface in rule.surfaces,onClick={focus.clearFocus();keyboard?.hide();rule=rule.copy(surfaces=if(surface in rule.surfaces)rule.surfaces-surface else rule.surfaces+surface);preview=false},label={Text(surfaceLabel(surface))},leadingIcon={Icon(surfaceIcon(surface),null,Modifier.size(20.dp))},modifier=Modifier.testTag("Filter surface ${surface.name}"))}}
        }
        item {FilterSitePicker(rule.platforms,preferredSites+candidate.orEmpty().map {it.platform}){sites->focus.clearFocus();keyboard?.hide();inlineTags=tagText.isNotBlank()||(sites.singleOrNull()?.let {filterSitePresentation(it).tags!=null}==true);inlineMedia=sites.size==1||rule.types.isNotEmpty();rule=rule.copy(platforms=sites);preview=false}}
        if(singleSite==Platform.REDDIT||subredditText.isNotBlank())item {
            OutlinedTextField(subredditText,{subredditText=it;rule=rule.copy(subreddits=terms(it).map(ContentFilters::subredditName));preview=false},label={Text("Subreddits")},isError=rule.platforms.isNotEmpty()&&Platform.REDDIT !in rule.platforms,leadingIcon={PlatformMark(Platform.REDDIT,20.dp)},supportingText={Text("Optional. One name or link per line, such as r/Android. Only posts from these subreddits match.")},maxLines=4,modifier=Modifier.fillMaxWidth().testTag("Filter subreddits"))
        }
        if(singleSite in ContentFilters.federatedSites||serverText.isNotBlank())item {
            OutlinedTextField(serverText,{serverText=it;rule=rule.copy(servers=terms(it).map(ContentFilters::serverAddress));preview=false},label={Text("Posted on servers")},isError=rule.platforms.isNotEmpty()&&rule.platforms.none {it in ContentFilters.federatedSites},leadingIcon={Icon(Icons.Rounded.Dns,null)},supportingText={Text("Optional. One server address per line. Matches the post's original server, such as mastodon.social.")},maxLines=4,modifier=Modifier.fillMaxWidth().testTag("Filter servers"))
        }
        item {OutlinedTextField(phraseText,{phraseText=it;rule=rule.copy(phrases=terms(it));preview=false},label={Text(siteStyle.wording)},supportingText={Text("Optional. One per line; match any whole phrase, ignoring case.")},minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth().testTag("Filter phrases"))}
        if(inlineTags)item {OutlinedTextField(tagText,{tagText=it;rule=rule.copy(hashtags=terms(it));preview=false},label={Text(siteStyle.tags ?: "Hashtags in text")},leadingIcon={Icon(Icons.Rounded.Tag,null)},supportingText={Text("Optional. One per line; matches visible #tags in the post.")},maxLines=4,modifier=Modifier.fillMaxWidth().testTag("Filter hashtags"))}
        if(inlineMedia)item {MediaFilterChoices(rule.types,siteStyle){rule=rule.copy(types=it);preview=false}}
        item {Text("Then",fontWeight=FontWeight.SemiBold);LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(ContentAction.entries.filter {it!=ContentAction.COLLECT||rule.repeatsOnly}) {action->FilterChip(selected=rule.action==action,onClick={rule=rule.copy(action=action);preview=false},label={Text(actionLabel(action))})}};if(rule.action==ContentAction.COLLECT)Text("Only already-confirmed originals are collected into the existing merged card. Sharing a topic or link isn't proof of duplication.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Text("Limit to",fontWeight=FontWeight.SemiBold);FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {ContentScope.entries.filter {it==ContentScope.EVERYWHERE||it==ContentScope.ACCOUNT&&account!=null||it==ContentScope.CHANNEL&&(context.channel!=null||initial.scope==it)||it==ContentScope.SEARCH&&(context.search!=null||initial.scope==it)}.forEach {type->FilterChip(selected=rule.scope==type,onClick={focus.clearFocus();keyboard?.hide();rule=rule.copy(scope=type,surfaces=if(type==ContentScope.SEARCH)setOf(FilterSurface.SEARCH)else rule.surfaces,scopeKey=when(type){ContentScope.EVERYWHERE->"";ContentScope.ACCOUNT->account.orEmpty();ContentScope.CHANNEL->context.channel ?: initial.scopeKey;ContentScope.SEARCH->context.search ?: initial.scopeKey});preview=false},label={Text(if(type==ContentScope.ACCOUNT)siteStyle.account else scopeLabel(type))},leadingIcon={Icon(scopeIcon(type),null,Modifier.size(18.dp))},modifier=Modifier.testTag("Filter scope ${type.name}"))}};if(rule.scope!=ContentScope.EVERYWHERE)Text(candidate?.firstOrNull()?.author?.takeIf {rule.scope==ContentScope.ACCOUNT} ?: scopeDescription(rule),fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {TextButton(onClick={focus.clearFocus();keyboard?.hide();advanced=!advanced},modifier=Modifier.testTag("More filter options")){Icon(if(advanced)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null);Text("More options")}}
        if(advanced) {
        item {LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            item {SuggestionChip(onClick={phraseText="sponsored\npaid partnership\nuse my code";tagText="";rule=rule.copy(name="Disclosed promotions",phrases=listOf("sponsored","paid partnership","use my code"),hashtags=emptyList(),repeatsOnly=false);preview=false},label={Text("Promotion wording")})}
            item {SuggestionChip(onClick={phraseText="";tagText="";domainText="";rule=rule.copy(name="Repeated posts",phrases=emptyList(),hashtags=emptyList(),domains=emptyList(),types=emptySet(),repeatsOnly=true,action=ContentAction.COLLECT);preview=false},label={Text("Confirmed repeats")})}
        };Text("Presets match the wording shown in the text field. They don't guess a post's intent.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(!inlineTags)item {OutlinedTextField(tagText,{tagText=it;rule=rule.copy(hashtags=terms(it));preview=false},label={Text("Hashtags in text")},leadingIcon={Icon(Icons.Rounded.Tag,null)},modifier=Modifier.fillMaxWidth().testTag("Filter hashtags"))}
            item {OutlinedTextField(domainText,{domainText=it;rule=rule.copy(domains=terms(it).map(ContentFilters::linkedWebsite));preview=false},label={Text("Links to a website")},leadingIcon={Icon(Icons.Rounded.Link,null)},supportingText={Text("Only posts linking to these websites. Paste one address or full link per line.")},modifier=Modifier.fillMaxWidth().testTag("Filter linked websites"))}
            if(!inlineMedia)item {MediaFilterChoices(rule.types,siteStyle){rule=rule.copy(types=it);preview=false}}
            item {OutlinedTextField(exceptionText,{exceptionText=it;rule=rule.copy(exceptPhrases=terms(it));preview=false},label={Text("Keep posts containing")},supportingText={Text("A matching phrase in any version keeps the combined post visible.")},modifier=Modifier.fillMaxWidth().testTag("Filter exceptions"))}
            item {Row(verticalAlignment=Alignment.CenterVertically){Checkbox(rule.repeatsOnly,{rule=rule.copy(repeatsOnly=it,action=if(!it&&rule.action==ContentAction.COLLECT)ContentAction.COLLAPSE else rule.action);preview=false});Text("Only confirmed repeat groups",fontSize=13.sp)}}
            item {Text("Duration",fontWeight=FontWeight.Medium);LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(listOf(0L to "Until removed",1L to "1 hour",24L to "1 day",168L to "1 week",720L to "30 days")){(hours,label)->SuggestionChip(onClick={rule=rule.copy(expiresAt=if(hours==0L)null else System.currentTimeMillis()+hours*3_600_000);preview=false},label={Text(label)})}};Text(rule.expiresAt?.let {"Expires "+DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))} ?: "Until you remove it",fontSize=12.sp)}
            item {Text("A post must match each filled-in section. Within a section, any entry is enough. Always show rules take priority.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        if(errors.isNotEmpty())item {Text(errors.joinToString("\n"),color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
        item {FilledTonalButton(onClick={errors=ContentFilters.validate(rule);if(errors.isEmpty()){focus.clearFocus();keyboard?.hide()};preview=errors.isEmpty()},modifier=Modifier.fillMaxWidth().testTag("Preview feed filter")){Icon(Icons.Rounded.Visibility,null);Spacer(Modifier.width(8.dp));Text("Preview rule")}}
        if(previewResult!=null) {
            val matcher=previewResult.first
            val matches=previewResult.second
            item {Text("${matches.size} of ${samples.size} cached cards match",fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("Filter preview count"));Text("This preview covers fetched material only. It makes no requests and doesn't mark anything read. Other keep rules can spare these posts.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(matches.take(4),key={"preview:"+CrossPosts.originalKey(it.first())}) {posts->val post=posts.first();Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f)){Column(Modifier.padding(14.dp)){Text(post.author,fontWeight=FontWeight.Medium);Text(post.title.ifBlank {post.body},fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis);Text(matcher.evaluate(posts,context,System.currentTimeMillis()).matches.flatMap {it.reasons}.distinct().joinToString(" · "),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            item {Button(onClick={onSave(previewResult.third)},modifier=Modifier.fillMaxWidth().testTag("Save feed filter")){Text("Save rule")}}
        }
    }
}
