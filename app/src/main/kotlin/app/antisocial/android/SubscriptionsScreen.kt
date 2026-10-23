package app.antisocial.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*

@Composable internal fun SubscriptionsScreen(vm: AntiSocialViewModel,follows: List<Follow>,onAdd: ()->Unit) {
    val subscriptions=remember(follows){Subscriptions.bundles(follows)}
    var managing by remember {mutableStateOf<String?>(null)}
    var discovering by remember {mutableStateOf<Subscription?>(null)}
    var adding by remember {mutableStateOf<Subscription?>(null)}
    var addingRss by remember {mutableStateOf(false)}
    var related by remember {mutableStateOf<Subscription?>(null)}
    val legacyVideos by vm.repo.legacyVideoFollows.collectAsState(emptyList())
    var showLegacyVideos by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().testTag("Subscriptions list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Column {Text("Subscriptions",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("People, organizations and public sources. Kept here.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))}}
        item {FilledTonalButton(onClick=onAdd,modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.Add,null);Spacer(Modifier.width(8.dp));Text("Add a subscription")}}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick={addingRss=true},modifier=Modifier.weight(1f).testTag("Add ordinary RSS feed")){Icon(Icons.Rounded.RssFeed,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("RSS / Atom")}
            OutlinedButton(onClick={vm.settings(SettingsPage.PORTABILITY)},modifier=Modifier.weight(1f).testTag("Subscription import export")){Icon(Icons.Rounded.ImportExport,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Packs & files")}
        }}
        if(legacyVideos.isNotEmpty())item {
            TextButton(onClick={showLegacyVideos=!showLegacyVideos},modifier=Modifier.testTag("Older video channel entries")){Text("Older video channel entries · ${legacyVideos.size}")}
            if(showLegacyVideos)Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Kept for export. Upload feeds are inactive; saved videos remain available.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                legacyVideos.forEach {follow->Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(follow.name,Modifier.weight(1f),fontSize=14.sp)
                    IconButton(onClick={vm.launch {vm.repo.dao.removeFollow(follow.id)}}){Icon(Icons.Rounded.Close,"Remove old video channel ${follow.name}")}
                }}
            }
        }
        if(subscriptions.isEmpty())item {Text("Add a public account. anti social can find other accounts linked from its profile or website, then bring matching posts together.",Modifier.padding(vertical=24.dp),lineHeight=23.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(subscriptions,key={it.id}) {subscription->
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable {vm.subscription(subscription.id)}) {
                            MentionText(subscription.name,vm.repo,subscription.accounts.first().target.platform,subscription.accounts.first().target.canonicalUrl,fontSize=19.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text("${subscription.accounts.size} ${if(subscription.accounts.size==1)"account"else "linked accounts"} · open feed",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                        }
                        IconButton(onClick={managing=subscription.id}){Icon(Icons.Rounded.MoreHoriz,"Manage ${subscription.name}")}
                    }
                    Row(Modifier.padding(top=10.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        subscription.accounts.distinctBy {it.target.platform}.forEach {account->IconButton(onClick={vm.open(account.target)}){PlatformMark(account.target.platform,26.dp)}}
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick={vm.subscription(subscription.id)}){Text("Read feed")}
                        if(subscription.accounts.any {AdjacentDiscovery.valid(it.target)})TextButton(onClick={related=subscription}){Text("Discover related")}
                        if(subscription.accounts.any {it.target.kind==TargetKind.PROFILE})TextButton(onClick={discovering=subscription}){Text("Find linked accounts")}
                    }
                }
            }
        }
    }
    subscriptions.firstOrNull {it.id==managing}?.let {subscription->
        AlertDialog(onDismissRequest={managing=null},containerColor=MaterialTheme.colorScheme.surface,title={Text(subscription.name)},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            subscription.accounts.forEach {account->Row(verticalAlignment=Alignment.CenterVertically) {
                PlatformMark(account.target.platform,24.dp)
                Column(Modifier.weight(1f).padding(8.dp)){Text(account.target.platform.label,fontWeight=FontWeight.Medium);Text(account.target.identity,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)}
                IconButton(onClick={managing=null;vm.open(account.target)}){Icon(Icons.AutoMirrored.Rounded.OpenInNew,"Open ${account.target.platform.label} account")}
                IconButton(onClick={vm.launch {vm.repo.dao.removeFollow(account.id)}}){Icon(Icons.Rounded.Close,"Unsubscribe ${account.target.platform.label} account")}
            }}
            TextButton(onClick={managing=null;adding=subscription}){Icon(Icons.Rounded.Add,null);Text("Add an account manually")}
        }},confirmButton={TextButton(onClick={managing=null}){Text("Done")}})
    }
    related?.let {subscription->subscription.accounts.firstOrNull {AdjacentDiscovery.valid(it.target)}?.let {account->AdjacentDialog(vm,Profile(account.target,subscription.name,displayNameSupplied=false),null,onDismiss={related=null})}}
    discovering?.let {subscription->SubscriptionDialog(vm,subscription.accounts.first().target.platform,subscription,autoDiscover=true,onDismiss={discovering=null})}
    adding?.let {subscription->SubscriptionDialog(vm,subscription.accounts.first().target.platform,subscription,onDismiss={adding=null})}
    if(addingRss)RssSourceSheet(onAdd={url,title,folder->vm.repo.addRssSource(url,title,folder)},onDismiss={addingRss=false})
}

@Composable internal fun GroupedFeed(vm: AntiSocialViewModel,posts: List<Post>,follows: List<Follow>,savedKeys: Set<String>,title: String,subtitle: String,counterparts: Map<String,List<Post>> = emptyMap(),counterpartsReady: Boolean=true,onAdd: ()->Unit) {
    val state by vm.screen.collectAsState()
    val person=state.subscriptionId.takeIf {state.section=="subscription"}
    val scoped=follows.filter {it.enabled&&(person==null||Subscriptions.id(it)==person)}
    ChronologicalFeed(vm,posts,scoped,savedKeys,person?.let {"person:$it"} ?: "all",title,subtitle,grouped=true,onAdd=onAdd,counterparts=counterparts,counterpartsReady=counterpartsReady)
}

@Composable internal fun SubscriptionDialog(vm: AntiSocialViewModel,initial: Platform,existing: Subscription?=null,autoDiscover: Boolean=false,initialTarget: Target?=null,initialProfile:Profile?=null,discoverAccounts: (suspend (Target)->AccountDiscovery)?=null,onDismiss: ()->Unit) {
    val scope=rememberCoroutineScope()
    var platform by remember {mutableStateOf(initial.takeIf(ReaderScope::isFeedSource) ?: vm.repo.prefs.activePlatform)}
    var value by remember {mutableStateOf(initialTarget?.takeIf {ReaderScope.isFeedSource(it.platform)}?.let {vm.repo.publicUrl(it.canonicalUrl,it.platform)} ?: if(autoDiscover)existing?.accounts?.firstOrNull {it.target.kind==TargetKind.PROFILE}?.target?.let {vm.repo.publicUrl(it.canonicalUrl,it.platform)}.orEmpty()else "")}
    var name by remember {mutableStateOf(existing?.name.orEmpty())}
    var discovery by remember {mutableStateOf<AccountDiscovery?>(null)}
    var selected by remember {mutableStateOf(setOf<String>())}
    var loading by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var advanced by remember {mutableStateOf(false)}
    var saving by remember {mutableStateOf(false)}
    var nameTouched by remember {mutableStateOf(false)}
    var generation by remember {mutableIntStateOf(0)}
    var group by remember {mutableStateOf("")};var feed by remember {mutableStateOf("")}
    var job by remember {mutableStateOf<Job?>(null)}
    val dismiss={job?.cancel();onDismiss()}
    val find: (Boolean)->Unit={matchingNames->
        val request=++generation;job?.cancel()
        job=scope.launch {
            loading=true;error=null
            try {
                val target=withContext(Dispatchers.Default){vm.repo.routes.target(platform,value.trim())}
                if(discovery==null)selected=(existing?.accounts?.map {AccountLinkage.key(it.target,vm.repo.routes)}.orEmpty()+AccountLinkage.key(target,vm.repo.routes)).toSet()
                val retained=discovery?.candidates.orEmpty().associateBy {AccountLinkage.key(it.target,vm.repo.routes)}.toMutableMap()
                fun update(found:AccountDiscovery) {
                    if(request!=generation)return
                    found.candidates.forEach {c->val key=AccountLinkage.key(c.target,vm.repo.routes);val old=retained[key];if(old==null||c.kind.ordinal<old.kind.ordinal)retained[key]=c}
                    discovery=found.copy(candidates=retained.values.take(20))
                    if(!nameTouched&&existing==null)name=found.name
                }
                // The opened account is usable immediately. Slow or unavailable
                // discovery lanes never disable subscribing to it.
                update(AccountDiscovery(initialProfile?.name ?: existing?.name ?: target.identity,listOf(AccountCandidate(target,"Account you opened",AccountLinkKind.ENTERED)),complete=false))
                val found=if(discoverAccounts!=null)discoverAccounts(target)else vm.repo.discoverAccounts(target,initialProfile,matchingNames,::update)
                update(found)
            }catch(e: CancellationException){throw e}catch(e: Exception){if(request==generation)error="Other account suggestions are unavailable. You can still subscribe to the account you opened."}finally {if(request==generation)loading=false}
        }
    }
    LaunchedEffect(Unit){if(autoDiscover)find(false)}
    AlertDialog(onDismissRequest=dismiss,containerColor=MaterialTheme.colorScheme.surface,title={Text(if(existing==null)"Add a subscription"else "Link accounts")},text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("Subscription dialog"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(discovery==null) {
            Text("Start with one public account. anti social looks for other platforms linked from its profile and website.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){PlatformOrder.popular.forEach {p->FilterChip(selected=p==platform,onClick={platform=p},enabled=!loading,label={PlatformMark(p,24.dp)},modifier=Modifier.testTag("Subscription platform ${p.id}"))}}
            OutlinedTextField(value,{value=it},label={Text(platformInputLabel(platform))},singleLine=true,enabled=!loading,modifier=Modifier.fillMaxWidth().testTag("Subscription account"))
            platformScope(platform)?.let {Text(it,fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(existing==null)TextButton(onClick={advanced=!advanced},enabled=!loading){Text(if(advanced)"Hide source options"else "Community, tag or RSS options")}
            if(advanced){OutlinedTextField(group,{group=it},label={Text("Group (optional)")},singleLine=true,enabled=!loading);OutlinedTextField(feed,{feed=it},label={Text("Alternate RSS/Atom URL (optional)")},singleLine=true,enabled=!loading)}
        }else {
            OutlinedTextField(name,{name=it;nameTouched=true},label={Text("Person or organization")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Subscription name"))
            discovery!!.notice?.let {Text(vm.repo.publicText(it),fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            discovery!!.candidates.filter {ReaderScope.isFeedSource(it.target.platform)}.forEach {candidate->val url=candidate.target.canonicalUrl;val accountKey=AccountLinkage.key(candidate.target,vm.repo.routes)
                Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().testTag("Account candidate $url").toggleable(value=accountKey in selected,enabled=!saving,role=Role.Checkbox,onValueChange={selected=if(it)selected+accountKey else selected-accountKey}).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(accountKey in selected,null);PlatformMark(candidate.target.platform,24.dp)
                        Column(Modifier.weight(1f).padding(start=10.dp)){MentionText(candidate.target.identity.removePrefix("profile/"),vm.repo,candidate.target.platform,candidate.target.canonicalUrl,accountLabel=true,networkAllowed=false,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text(vm.repo.publicText(candidate.evidence,candidate.target.platform),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
        }
        if(loading&&!saving){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Finding linked accounts… You can subscribe now.",fontSize=12.sp)}
        if(discovery!=null&&!loading&&!saving)TextButton(onClick={find(true)},modifier=Modifier.testTag("Find more accounts")){Icon(Icons.Rounded.Search,null);Spacer(Modifier.width(6.dp));Text("Find more accounts")}
        error?.let {Text(vm.repo.publicText(it),fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={
        TextButton(enabled=!saving&&if(discovery==null)!loading&&value.isNotBlank()else name.isNotBlank()&&selected.isNotEmpty(),onClick={
            if(discovery==null&&feed.isBlank()&&!advanced)find(false)else {
                val accepted=discovery
                generation++;job?.cancel()
                job=scope.launch {
                    loading=true;saving=true;error=null
                    try {
                        if(accepted==null)vm.repo.follow(platform,value.trim(),group.trim(),feed.trim().takeIf {it.isNotBlank()})
                        else vm.repo.saveSubscription(name,accepted.candidates.filter {AccountLinkage.key(it.target,vm.repo.routes) in selected}.map {it.target},existing?.id)
                        onDismiss()
                        // Refresh belongs to the view model; closing the dialog cannot cancel it.
                        vm.launch {if(existing==null)vm.repo.refresh()else vm.repo.refreshSubscription(existing.id)}
                    }catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "Subscription could not be saved"}finally {loading=false;saving=false}
                }
            }
        },modifier=Modifier.testTag("Save subscription")){Text(if(discovery==null&&!advanced)"Find accounts"else if(existing==null)"Subscribe"else "Link selected")}
    },dismissButton={TextButton(onClick=dismiss){Text("Cancel")}})
}
