package app.antisocial.android

import android.app.role.RoleManager
import android.content.*
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity: ComponentActivity() {
    private data class IncomingRequest(val url: String,val explicit: Boolean,val sequence: Long)
    private var incoming by mutableStateOf<IncomingRequest?>(null)
    private var sequence=0L
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);receive(intent)
        (application as AntiSocialApplication).repo.applyNetworkSettings()
        setContent {
            val repo=(application as AntiSocialApplication).repo
            val vm: AntiSocialViewModel=viewModel(factory=AntiSocialViewModel.Factory(repo))
            LaunchedEffect(vm) {vm.externalLinks.collect {url->
                if(runCatching {repo.routes.recognize(url)}.getOrNull()==null&&vm.articles.canOpen(url)){vm.articles.open(url);return@collect}
                val result=repo.resolveOutgoing(url,action=ShareLinkAction.OPEN)
                withContext(Dispatchers.Main.immediate) {when(result) {
                    is ShareRouteResult.Available->{openExternal(result.url,repo);result.notice?.let {android.widget.Toast.makeText(this@MainActivity,it,android.widget.Toast.LENGTH_LONG).show()}}
                    is ShareRouteResult.Unavailable->android.widget.Toast.makeText(this@MainActivity,result.message,android.widget.Toast.LENGTH_LONG).show()
                }}
            }}
            LaunchedEffect(vm){vm.browserLinks.collect {openExternal(it,repo)}}
            LaunchedEffect(incoming) {
                val request=incoming ?: return@LaunchedEffect
                vm.openLink(request.url,request.explicit)
                incoming=null
            }
            AntiSocialApp(vm)
        }
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);receive(intent)}
    private fun receive(intent: Intent) {
        intent.getStringExtra("openContext")?.takeIf {it.matches(Regex("[a-fA-F0-9-]{36}"))}?.let {id->
            (application as AntiSocialApplication).repo.contextReader.open(id)
            return
        }
        // Explicit debug-only tracing; ordinary reads do not log destinations.
        if(BuildConfig.DEBUG&&intent.getBooleanExtra("antisocialDiagnostics",false)) {
            (application as AntiSocialApplication).repo.gateway.onNetworkEvent={event->
                android.util.Log.d("AntiSocialNetwork","${event.host}: ${event.stage} at ${event.elapsedMs} ms")
            }
        }
        val shared=intent.action==Intent.ACTION_SEND
        val url=if(shared)Regex("https?://[^\\s<>]+",RegexOption.IGNORE_CASE).find(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.value?.trimEnd('.',',',';',')',']','}') else intent.dataString
        incoming=url?.let {IncomingRequest(it,shared,++sequence)}
    }
    private fun openExternal(url: String,repo: Repository) {
        val browser=repo.prefs.externalBrowser
        val cleaned=try {repo.gateway.screenNavigation(url)}catch(e:Exception) {
            val message=when { !repo.filters.value.ready->"Protection is starting. Try again in a moment."
                repo.gateway.currentPolicy().let {it.tor&&!it.ready}->"Waiting for Tor. Try again once connected."
                else->"This link was stopped by your protection." }
            android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show();return
        }
        if(!cleaned.startsWith("https://")&&!cleaned.startsWith("http://"))return
        if(browser.isNotBlank()&&runCatching {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(cleaned)).setPackage(browser))}.isSuccess)return
        val candidates=packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW,Uri.parse(cleaned)),android.content.pm.PackageManager.MATCH_ALL).filter {it.activityInfo.packageName!=packageName}.distinctBy {it.activityInfo.packageName}
        if(candidates.isEmpty()){android.widget.Toast.makeText(this,"Choose an external browser in Settings",android.widget.Toast.LENGTH_LONG).show();return}
        val intents=candidates.map {Intent(Intent.ACTION_VIEW,Uri.parse(cleaned)).setPackage(it.activityInfo.packageName)}
        startActivity(Intent.createChooser(intents.first(),"Open in browser").putExtra(Intent.EXTRA_INITIAL_INTENTS,intents.drop(1).toTypedArray()))
    }
}

private val Mint=Color(0xFF8DE5BA)
private val Ink=Color(0xFF111315)
private val Panel=Color(0xFF1A1D20)
private val Muted=Color(0xFF929D99)
private data class KnownPostGroups(val posts: List<Post>,val follows: List<Follow>,val memberships: Map<String,Set<String>>,val images: Long,val groups: Map<String,List<Post>>)
internal val LocalPostCounterparts=compositionLocalOf<Map<String,List<Post>>> {emptyMap()}
internal val LocalSavedPostKeys=compositionLocalOf<Set<String>> {emptySet()}
internal val LocalImageSearch=compositionLocalOf<((Media,String)->Unit)?> {null}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AntiSocialApp(vm: AntiSocialViewModel) {
    val repo=vm.repo
    val state by vm.screen.collectAsStateWithLifecycle()
    DisposableEffect(state.target){val key=state.target?.let {repo.pageKey(it)};if(key!=null)repo.lease(key,true);onDispose {if(key!=null)repo.lease(key,false)}}
    val filtering by repo.filters.collectAsStateWithLifecycle()
    val tor by repo.tor.state.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle()
    LaunchedEffect(tor.ready,tor.allTraffic){if(state.section!="image-search"&&tor.allTraffic&&tor.ready&&(repo.prefs.intervalMinutes>0||state.target!=null))vm.refresh()}
    val followsState=repo.follows.collectAsStateWithLifecycle<List<Follow>?>(null)
    val loadedFollows=followsState.value
    val follows=loadedFollows.orEmpty()
    val bookmarkKeysFlow=remember(repo){repo.dao.bookmarkKeys()}
    val bookmarkKeys by bookmarkKeysFlow.collectAsStateWithLifecycle(emptyList())
    val savedKeys=remember(bookmarkKeys){bookmarkKeys.toSet()}
    val discussion by vm.discussions.state.collectAsStateWithLifecycle()
    val history by repo.history.collectAsStateWithLifecycle(emptyList())
    val personalChannels by repo.channels.channels.collectAsStateWithLifecycle()
    val channelId by repo.channels.selected.collectAsStateWithLifecycle()
    val channelError by repo.channels.error.collectAsStateWithLifecycle()
    val activeChannel=personalChannels.firstOrNull {it.id==channelId}
    var group by remember(state.platform){mutableStateOf(repo.prefs.readingGroup(state.platform))}
    // An explicit source change clears the old flow before the next frozen batch.
    val feedMembership=remember(follows){follows.filter {it.enabled}.map {it.copy(name="",personName=null)}}
    val candidatesFlow=remember(repo){repo.followedSnapshot(null)}
    val globalSnapshot=key(feedMembership){candidatesFlow.collectAsStateWithLifecycle<FollowedPostSnapshot?>(null).value}
    val useGlobal=state.section=="feed"&&state.target==null&&state.combined&&activeChannel==null
    val postsFlow=remember(state.platform,group,state.section,state.target,state.combined,state.subscriptionId,feedMembership,channelId,activeChannel){if(state.section=="subscription")repo.followedSnapshot(null,personId=state.subscriptionId)else if(state.section=="feed"&&state.target==null)repo.followedSnapshot(if(state.combined)null else state.platform,if(state.combined||channelId!=null)""else group,channel=activeChannel)else flowOf(FollowedPostSnapshot(emptyList(),emptyMap()))}
    // A placeholder empty list must not become the reader's first accepted batch.
    // The unfiltered combined feed is the exact same window as the global
    // counterpart source. Reuse its coherent snapshot instead of issuing and
    // decoding a second thousand-post Room stream.
    val storedSnapshot=if(useGlobal)globalSnapshot else key(postsFlow){postsFlow.collectAsStateWithLifecycle<FollowedPostSnapshot?>(null).value}
    val allStoredPosts=storedSnapshot?.posts.orEmpty()
    val storedPosts=if(state.combined||state.section=="subscription")allStoredPosts else allStoredPosts.filter {it.platform==state.platform}
    val feedRefresh by repo.feedRefresh.collectAsStateWithLifecycle()
    // Keep the current scope represented even when a busy global feed's bounded
    // window omits its older rows, or Room delivers the two query results apart.
    val matchingCandidates=remember(globalSnapshot,storedPosts,useGlobal){globalSnapshot?.let {if(useGlobal)it.posts else (storedPosts+it.posts).distinctBy(repo::key)}}
    val matchingMemberships=remember(globalSnapshot,storedSnapshot){buildMap<String,Set<String>> {
        (if(useGlobal)listOfNotNull(globalSnapshot?.memberships)else listOfNotNull(globalSnapshot?.memberships,storedSnapshot?.memberships)).forEach {map->map.forEach {(key,ids)->put(key,get(key).orEmpty()+ids)}}
    }}
    val imageRevision by repo.images.identities.revision.collectAsStateWithLifecycle()
    LaunchedEffect(repo,matchingCandidates) {
        matchingCandidates?.let {posts->delay(300);repo.images.restoreCachedIdentities(posts)}
    }
    val knownGroups by produceState<KnownPostGroups?>(null,matchingCandidates,loadedFollows,matchingMemberships,imageRevision) {
        val candidates=matchingCandidates;val accounts=loadedFollows
        if(candidates==null||accounts==null){value=null;return@produceState}
        value=withContext(Dispatchers.Default){KnownPostGroups(candidates,accounts,matchingMemberships,imageRevision,repo.cardWork.track(candidates.map(::postWorkKey),"Matching editions"){CrossPosts.groupCancellable(candidates.map(repo.images.identities::captured),accounts,matchingMemberships,repo.images.identities::same)}.flatMap {g->g.posts.map {repo.key(it) to g.posts}}.toMap())}
    }
    val discussionGroups=knownGroups?.groups.orEmpty()
    val counterpartsReady=!feedRefresh.running&&globalSnapshot?.generation==feedRefresh.generation&&
        ((state.section!="subscription"&&(state.section!="feed"||state.target!=null))||storedSnapshot!=null&&storedSnapshot.generation==globalSnapshot?.generation)&&matchingCandidates!=null&&loadedFollows!=null&&knownGroups?.posts==matchingCandidates&&knownGroups?.follows==loadedFollows&&knownGroups?.memberships==matchingMemberships&&knownGroups?.images==imageRevision
    val readerStates=rememberSaveableStateHolder()
    var readerKeys by rememberSaveable {mutableStateOf(listOf<String>())}
    val channelScope=channelId?.takeIf {state.target==null}?.let {":channel:$it:${activeChannel?.let(PersonalChannels::fingerprint).orEmpty()}"}.orEmpty()
    val readerKey=remember(state.platform,state.target,state.combined,channelScope){(if(state.combined)"all"else state.platform.id)+":"+(state.target?.let {it.kind.name+":"+Repository.hash(it.canonicalUrl)} ?: "following")+channelScope}
    LaunchedEffect(readerKey,state.section){if(state.section=="feed"){val next=readerKeys.filter {it!=readerKey}+readerKey;next.dropLast(24).forEach {readerStates.removeState(it)};readerKeys=next.takeLast(24)}}
    var selected by remember {mutableStateOf(repo.prefs.selected)}
    DisposableEffect(repo) {val stop=repo.prefs.observePlatforms {selected=repo.prefs.selected};onDispose(stop)}
    val visibleTabs=(if(state.platform in selected||!ReaderScope.isFeedSource(state.platform))selected else selected+state.platform).filter(ReaderScope::isFeedSource)
    LaunchedEffect(state.platform){if(ReaderScope.isFeedSource(state.platform)&&state.platform !in selected){selected=selected+state.platform;repo.prefs.selected=selected;repo.routeHeartbeat.changed()}}
    var showFollow by remember {mutableStateOf(false)}
    var showSearch by remember {mutableStateOf(false)}
    var showConnection by rememberSaveable {mutableStateOf(false)}
    var showChannels by remember {mutableStateOf(false)}
    var showFeedChooser by remember {mutableStateOf(false)}
    var sharingChannel by remember {mutableStateOf<PersonalChannel?>(null)}
    var channelBefore by remember {mutableStateOf<String?>(null)}
    val manageChannels={channelBefore=repo.channels.selected.value;showChannels=true}
    val connectionNotifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(showSearch){if(showSearch)repo.prepareSearchConnection()}
    var searchInitially by remember {mutableStateOf(true)}
    var settingsRevision by remember {mutableIntStateOf(0)}
    val context=LocalContext.current
    val theme=remember(settingsRevision){repo.prefs.theme}
    LaunchedEffect(repo) {repo.ai.refresh();repo.contextReader;ContextMaintenance.recover(repo)}
    val dark=when(theme){"light"->false;"dark"->true;else->isSystemInDarkTheme()}
    SideEffect {
        if(android.os.Build.VERSION.SDK_INT>=35)(context as? android.app.Activity)?.window?.let {window->
            androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    val colors=if(dark)darkColorScheme(primary=Mint,secondary=Mint,secondaryContainer=Color(0xFF253D31),onSecondaryContainer=Mint,background=Ink,surface=Ink,surfaceVariant=Panel,onSurface=Color(0xFFECEFEA),onSurfaceVariant=Muted) else lightColorScheme(primary=Color(0xFF236849),secondary=Color(0xFF236849),secondaryContainer=Color(0xFFDDEDE3),onSecondaryContainer=Color(0xFF236849),background=Color(0xFFF7F9F6),surface=Color.White,surfaceVariant=Color(0xFFF0F3EF),onSurface=Ink,onSurfaceVariant=Color(0xFF64726B))
    MaterialTheme(colorScheme=colors,typography=Typography()) {
        CompositionLocalProvider(LocalMentionOpenAccount provides {url->vm.openLink(url)},LocalArchiveChecksPaused provides (showFollow||showSearch||showConnection||showChannels||showFeedChooser||sharingChannel!=null||discussion!=null),LocalPostCounterparts provides discussionGroups,LocalSavedPostKeys provides savedKeys,LocalImageSearch provides vm::searchImage) {
        BackHandler(enabled=state.target!=null||state.section!="feed"){vm.back()}
        ContextSheet(repo,onOpenLink={vm.openLink(it,true)})
        ArticleReaderSheet(vm.articles,onOpenLink={vm.openLink(it,true)},onBrowser=vm::openBrowser)
        Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
            if(state.section=="settings") {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start=8.dp,end=20.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={vm.back()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back")}
                    Text(state.settingsPage.title,modifier=Modifier.weight(1f).testTag("Settings title"),fontSize=24.sp,fontWeight=FontWeight.SemiBold)
                }
            }else {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(start=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(state.section !in setOf("feed","discover")||state.target!=null)IconButton(onClick={vm.back()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back")}
                    if(state.section=="feed"&&state.target==null)TextButton(onClick={showFeedChooser=true},modifier=Modifier.weight(1f).testTag("Choose feed"),contentPadding=PaddingValues(horizontal=4.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(activeChannel?.name ?: "Following",fontSize=22.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false))
                            Icon(Icons.Rounded.ExpandMore,"Choose feed",Modifier.padding(start=4.dp).size(20.dp))
                        }
                    }else if(state.section=="discover")Text("Search",fontSize=24.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp,modifier=Modifier.weight(1f))
                    else Box(Modifier.weight(1f)){BrandWordmark()}
                    IconButton(onClick={showConnection=true},modifier=Modifier.testTag("Connection status")) {
                        Icon(if(tor.enabled)Icons.Rounded.Shield else Icons.Rounded.Public,if(tor.allTraffic)"Tor for all traffic"else if(tor.enabled)"Tor for search engines; content connects directly"else "Direct connection",Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
                    }
                    if(state.section!="discover")IconButton(onClick={searchInitially=true;showSearch=true}){Icon(Icons.Rounded.Search,"Search or open a link")}
                    IconButton(onClick={vm.section("settings")}){Icon(Icons.Rounded.Settings,"Settings")}
                }
                if(state.section=="feed"&&state.target?.platform!=Platform.YOUTUBE) {
                    key(visibleTabs.map {it.id},state.platform) {
                    ScrollableTabRow(selectedTabIndex=if(state.combined)0 else visibleTabs.indexOf(state.platform).coerceAtLeast(0)+1,containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,edgePadding=8.dp,divider={HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))}) {
                        Tab(selected=state.combined,onClick={vm.combined()},modifier=Modifier.height(48.dp).testTag("Platform tab all"),icon={Icon(painterResource(R.drawable.ic_antisocial),"All platforms",Modifier.size(30.dp),tint=MaterialTheme.colorScheme.primary)})
                        visibleTabs.forEach {p->Tab(selected=!state.combined&&p==state.platform,onClick={if(channelId!=null)vm.feedPlatform(p)else vm.platform(p)},modifier=Modifier.height(48.dp).testTag("Platform tab ${p.id}"),icon={PlatformMark(p,30.dp)})}
                    }
                    }
                }
            }
            }
        },bottomBar={
            if(state.section!="settings") {
            NavigationBar(modifier=Modifier.height(68.dp+WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).testTag("Primary navigation"),containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                listOf(Triple("feed","Following",Icons.Rounded.DynamicFeed),Triple("discover","Search",Icons.Rounded.Search),Triple("follows","Subscriptions",Icons.Rounded.PeopleOutline),Triple("saved","Library",Icons.Rounded.BookmarkBorder)).forEach {(section,label,icon)->NavigationBarItem(modifier=Modifier.testTag("Primary destination $section"),selected=state.section==section||section=="follows"&&state.section=="subscription",onClick={if(section=="feed")vm.following()else vm.section(section)},icon={Icon(icon,label)},label={Text(label,fontSize=11.sp)})}
            }
            }
        }) {padding->
            Column(Modifier.fillMaxSize().padding(padding)) {
                state.notice?.let {notice->Surface(color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.fillMaxWidth().testTag("Reader scope notice")) {Row(Modifier.padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {Text(notice,Modifier.weight(1f).padding(vertical=12.dp),fontSize=13.sp);IconButton(onClick=vm::dismissNotice){Icon(Icons.Rounded.Close,"Dismiss notice")}}}}
                if(!filtering.ready) {Text(filtering.message,Modifier.fillMaxWidth().padding(16.dp),style=MaterialTheme.typography.bodySmall)}
                if(tor.allTraffic&&!tor.ready)Text(tor.message,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                if(state.section=="feed"&&state.target==null) {
                    if(channelId==null&&channelError!=null)TextButton(onClick=manageChannels){Text("Channels need attention",fontSize=12.sp)}
                }
                if(state.section=="feed"&&state.target?.kind!=TargetKind.SEARCH&&state.page!=null&&(state.loading||state.error!=null)) {
                    Surface(color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(repo.publicText(if(state.loading)state.progress.ifBlank {"Updating this page…"}else "Showing the last loaded page · ${state.error}"),Modifier.weight(1f),fontSize=12.sp,maxLines=3)
                            TextButton(onClick={if(state.loading)vm.stopLoading()else vm.refresh()}){Text(if(state.loading)"Stop"else "Retry")}
                        }
                    }
                }
                when(state.section) {
                    "settings"->SettingsScreen(vm,selected,{selected=it;repo.prefs.selected=it;repo.routeHeartbeat.changed();if(state.platform !in it&&it.isNotEmpty()){repo.prefs.activePlatform=it.first();vm.screen.value=vm.screen.value.copy(platform=it.first())};settingsRevision++},{settingsRevision++})
                    "follows"->SubscriptionsScreen(vm,follows){showFollow=true}
                    "discover"->LocalDiscoveryScreen(vm)
                    "image-search"->ImageSearchScreen(vm,savedKeys)
                    "subscription"->if(storedSnapshot==null)LinearProgressIndicator(Modifier.fillMaxWidth())else GroupedFeed(vm,storedPosts,follows,savedKeys,Subscriptions.bundles(follows).firstOrNull {it.id==state.subscriptionId}?.name ?: "Subscription","Linked accounts · latest first",counterparts=discussionGroups,counterpartsReady=counterpartsReady){showFollow=true}
                    "saved"->LibraryScreen(repo,onOpenOriginal={post->vm.open(repo.postTarget(post) ?: Target(post.platform,TargetKind.POST,post.id,post.canonicalUrl))},onStorage={vm.settings(SettingsPage.STORAGE)},onOpenLink=vm::openLink,onReadLink=vm.articles::open)
                    "pools"->PoolScreen(vm)
                    else->readerStates.SaveableStateProvider(readerKey) {
                        val displayed=state.page?.posts ?: if(state.target==null)storedPosts else emptyList()
                        if(state.target==null) {
                            if(storedSnapshot==null)LinearProgressIndicator(Modifier.fillMaxWidth())
                            else if(channelId!=null) {
                                if(activeChannel==null||channelError!=null)UnavailableChannel(channelError ?: "The selected channel is unavailable.",manageChannels)
                                else PersonalChannelFeed(vm,activeChannel,storedPosts,follows,savedKeys,matchingMemberships,discussionGroups,counterpartsReady,onAdd={showFollow=true},onEdit=manageChannels)
                            }
                            else if(state.combined)GroupedFeed(vm,storedPosts,follows,savedKeys,"All platforms","Your subscriptions, together · latest first",counterparts=discussionGroups,counterpartsReady=counterpartsReady){showFollow=true}
                            else {
                                val scopedFollows=follows.filter {it.enabled&&it.target.platform==state.platform&&(group.isBlank()||it.group==group)}
                                val groups=follows.filter {it.enabled&&it.target.platform==state.platform}.map {it.group}.filter {it.isNotBlank()}.distinct()
                                ChronologicalFeed(vm,storedPosts,scopedFollows,savedKeys,"following:${state.platform.id}:group:${Repository.hash(group)}","Following","${state.platform.label} · latest first",onAdd={showFollow=true},onRefresh={vm.refresh(group)},selector=if(groups.isEmpty()&&group.isBlank())null else {{
                                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                        (listOf("")+groups).forEach {g->FilterChip(selected=group==g,onClick={group=g;repo.prefs.readingGroup(state.platform,g)},label={Text(g.ifBlank {"All sources"})})}
                                    }
                                }},counterparts=discussionGroups,counterpartsReady=counterpartsReady)
                            }
                        }else if(state.target?.kind==TargetKind.SEARCH) {
                            SearchFeedScreen(vm,savedKeys)
                        }else if(state.platform in setOf(Platform.INSTAGRAM,Platform.PIXELFED)&&displayed.isNotEmpty()&&state.target?.kind==TargetKind.PROFILE) {
                            InstagramProfile(vm,displayed)
                        }else if(state.platform==Platform.PINTEREST&&state.target?.kind !in setOf(TargetKind.POST,TargetKind.MEDIA)&&displayed.isNotEmpty()) {
                            PinterestFeed(vm,displayed,savedKeys,state.target?.identity ?: "Following")
                        }else {
                        val listState=rememberLazyListState()
                        ArchiveListViewport(listState) {
                        LazyColumn(state=listState,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=18.dp,bottom=90.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            item {
                                val heading=when(state.target?.kind) {
                                    TargetKind.POST->if(state.target?.platform==Platform.YOUTUBE)"Linked video"else "${state.platform.label} post"
                                    TargetKind.THREAD->"${state.platform.label} thread"
                                    TargetKind.MEDIA->"${state.platform.label} media"
                                    TargetKind.TAG->"#${state.target!!.identity.substringAfterLast('/')}"
                                    else->state.target?.identity?.ifBlank {state.platform.label} ?: "Following"
                                }
                                Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)){MentionText(heading,repo,state.platform,state.target?.canonicalUrl,accountLabel=state.target?.kind==TargetKind.PROFILE,fontSize=25.sp,fontWeight=FontWeight.Bold);Text(state.target?.kind?.name?.lowercase()?.replaceFirstChar {it.uppercase()} ?: "${state.platform.label} · latest first",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)};IconButton(onClick={vm.refresh()}){Icon(Icons.Rounded.Refresh,"Refresh")}}
                            }
                            val groups=follows.filter {it.target.platform==state.platform}.map {it.group}.filter {it.isNotBlank()}.distinct()
                            if(state.target==null&&groups.isNotEmpty())item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){(listOf("")+groups).forEach {g->FilterChip(selected=group==g,onClick={group=g},label={Text(g.ifBlank {"All sources"})})}}}
                            if(state.loading&&state.page==null)item {LinearProgressIndicator(Modifier.fillMaxWidth());Row(verticalAlignment=Alignment.CenterVertically){Text(if(!filtering.ready)filtering.message else state.progress.ifBlank {if(state.target==null)status.ifBlank {"Updating your feed…"}else "Finding working public data"},Modifier.weight(1f).padding(top=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onClick={vm.stopLoading()}){Text("Stop")}}}
                            if(state.error!=null&&state.page==null)item {
                                Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(16.dp)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(if(state.error=="Loading stopped")"Loading stopped"else if(state.target?.kind==TargetKind.SEARCH)"Search is unavailable"else "Content is unavailable",fontWeight=FontWeight.SemiBold)
                                        Text(repo.publicText(state.error.orEmpty()),fontSize=13.sp,modifier=Modifier.padding(top=6.dp))
                                        Row {
                                            TextButton(onClick={vm.refresh()}){Text("Retry")}
                                            TextButton(onClick={vm.section("pools")}){Text("Inspect sources")}
                                        }
                                        if(state.target?.kind==TargetKind.SEARCH)TextButton(onClick={searchInitially=false;showSearch=true}){Text("Open a profile or link")}
                                    }
                                }
                            }
                            item {PageIntro(vm)}
                            if(displayed.isEmpty()&&state.page?.collections.isNullOrEmpty()&&!state.loading&&state.error==null)item {if(state.page?.profile!=null)EmptyState("No public posts supplied","This source returned profile details only. Another source may provide the posts.","Inspect sources"){vm.section("pools")}else EmptyState("Your ${state.platform.label}, on your terms","Subscribe to public sources to build a chronological feed. Your follows live here, not in a social account.","Choose a source"){showFollow=true}}
                            val root=state.target?.takeIf {it.kind in setOf(TargetKind.POST,TargetKind.THREAD)}?.let {target->displayed.filter {it.platform==target.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}}.orEmpty()
                            items(root.distinctBy {repo.key(it)},key={"root:${repo.key(it)}"}) {post->PostCard(post,vm,repo.key(post) in savedKeys,if(state.target==null)discussionGroups[repo.key(post)] ?: listOf(post)else listOf(post)){repo.postTarget(post)?.let {vm.open(it)}}}
                            val remaining=displayed.filterNot {it in root}
                            val dated=remaining.filter {it.publishedAt!=null}
                            val undated=remaining.filter {it.publishedAt==null}
                            items(if(state.target==null)CachePolicy.chronological(dated)else dated.distinctBy {repo.key(it)},key={"dated:${repo.key(it)}"}) {post->Box(Modifier.padding(start=(replyDepth(post,displayed)*12).dp)){PostCard(post,vm,repo.key(post) in savedKeys,if(state.target==null)discussionGroups[repo.key(post)] ?: listOf(post)else listOf(post)){repo.postTarget(post)?.let {vm.open(it)}}}}
                            if(undated.isNotEmpty())item {Text("DATE UNAVAILABLE",fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=12.dp))}
                            items(undated.distinctBy {repo.key(it)},key={"undated:${repo.key(it)}"}) {post->PostCard(post,vm,repo.key(post) in savedKeys,if(state.target==null)discussionGroups[repo.key(post)] ?: listOf(post)else listOf(post)){repo.postTarget(post)?.let {vm.open(it)}}}
                            if(!state.loading&&state.target==null&&state.error==null&&status.isNotBlank())item {Text(repo.publicText(status),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            if(state.page?.next!=null)item {OutlinedButton(onClick={vm.loadMore()},enabled=!state.loading,modifier=Modifier.fillMaxWidth()){Text("Load more")}}
                        }
                        }
                        }
                    }
                }
            }
        }
        if(showFollow)SubscriptionDialog(vm,state.platform,onDismiss={showFollow=false})
        if(showFeedChooser)ChannelSelector(personalChannels,channelId,onSelect={id->repo.channels.select(id);vm.combined();showFeedChooser=false},onManage={showFeedChooser=false;manageChannels()},onDismiss={showFeedChooser=false})
        if(showSearch)SourceDialog(state.platform.takeIf(ReaderScope::isFeedSource) ?: repo.prefs.activePlatform,true,searchInitially,state.target,onDismiss={showSearch=false}){p,value,_,_,searching,scope->vm.openInput(p,value,searching,scope);showSearch=false}
        if(showConnection)ConnectionSheet(tor,repo.prefs.tor,repo.prefs.searchProvider,repo.prefs.originalFallback,onContentTor={enabled->
            repo.prefs.tor=enabled;repo.applyNetworkSettings();settingsRevision++
            if(enabled&&android.os.Build.VERSION.SDK_INT>=33)connectionNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        },onDismiss={showConnection=false})
        if(showChannels)ChannelsSheet(repo.channels,follows,onDismiss={showChannels=false;if(repo.channels.selected.value!=channelBefore)vm.combined()},onShareSources={channel->showChannels=false;sharingChannel=channel})
        sharingChannel?.let {channel->ChannelSourcePack(repo,follows,channel,onDismiss={sharingChannel=null})}
        DiscussionSheet(vm)
        }
    }
}

@Composable private fun SectionHeading(title: String,subtitle: String) {Column(Modifier.padding(vertical=10.dp)){Text(title,fontSize=26.sp,fontWeight=FontWeight.Bold);Text(subtitle,Modifier.padding(top=6.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable private fun EmptyState(title: String,body: String,action: String?,onAction: ()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=24.dp,horizontal=18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.08f)){Icon(Icons.Rounded.Spa,null,Modifier.padding(16.dp).size(32.dp),tint=MaterialTheme.colorScheme.primary)}
        Text(title,fontSize=20.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=16.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        Text(body,fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.padding(top=10.dp))
        if(action!=null)Button(onClick=onAction,modifier=Modifier.padding(top=16.dp),shape=RoundedCornerShape(12.dp)){Text(action)}
    }
}

private data class PostSaveCapture(val post:Post,val replies:List<CapturedThread>,val editions:List<Post>)

@Composable internal fun PostCard(post: Post,vm: AntiSocialViewModel,saved: Boolean,discussions: List<Post> = listOf(post),bodyVariants: List<Post>?=null,onExplain: (()->Unit)?=null,onFilter: (()->Unit)?=null,onOpen: ()->Unit) {
    val repo=vm.repo;val context=LocalContext.current
    val outgoing=rememberOutgoingActions(repo)
    val known=LocalPostCounterparts.current[repo.key(post)].orEmpty()
    val threads=remember(post,discussions,known){(listOf(post)+discussions+known).distinctBy(CrossPosts::originalKey).take(CrossPosts.MAX_EDITIONS)}
    val contentRoots=bodyVariants ?: threads
    val repeats=hasRepeatedOriginals(threads)
    val copyTarget=remember(threads,post.platform){CrossPosts.representative(threads,post.platform) ?: post}
    var showOriginals by remember(repo.key(post)){mutableStateOf(false)}
    var showOptions by remember(repo.key(post)){mutableStateOf(false)}
    var showSourceDetails by remember(repo.key(post)){mutableStateOf(false)}
    val savedKeys=LocalSavedPostKeys.current
    val isSaved=saved||threads.any {repo.key(it) in savedKeys}
    var fullImage by remember(post.id){mutableStateOf<Pair<List<Media>,Int>?>(null)}
    var saveCopy by remember(repo.key(post)){mutableStateOf<PostSaveCapture?>(null)}
    val archive=rememberArchivePresentation(post,repo,active=fullImage==null&&!showOriginals&&!showOptions)
    val displayed=archive.post
    val attributedAccounts=remember(threads,displayed,archive.showingArchive){(if(archive.showingArchive||displayed.recoveredArchive!=null)listOf(displayed)else threads).distinctBy {CrossPosts.originalAccount(it) ?: CrossPosts.originalKey(it)}}
    val fusion=remember(contentRoots,archive.showingArchive,displayed){if(!archive.showingArchive&&displayed.recoveredArchive==null)collectedPostFusion(contentRoots)else null}
    val caption=remember(contentRoots,displayed,fusion,archive.showingArchive){if(fusion==null&&!archive.showingArchive&&displayed.recoveredArchive==null)collectedCaptionPost(contentRoots) ?: displayed else displayed}
    val postLinks=remember(displayed,caption,contentRoots,repo){(listOf(displayed,caption)+contentRoots).flatMap(PostLinks::urls).map {repo.publicUrl(it,displayed.platform)}.distinct().take(PostLinks.MAX_LINKS)}
    val linkedVideo=post.platform==Platform.YOUTUBE
    var videoDescriptionExpanded by rememberSaveable(repo.key(post)){mutableStateOf(false)}
    // A late exact copy can add comment/source controls without changing the
    // frozen reading body. Preserve those validated editions when saving too;
    // no unseen differing context or additional attachment is fetched here.
    // Saved-copy proof is needed only after a save action. It must not repeat
    // pairwise collection validation on the rendering/scrolling path.
    val savePlan=remember(contentRoots,threads,post){lazy {
        val body=runCatching {repo.library.normalizedFusionRoots(post,contentRoots)}.getOrDefault(emptyList())
        val extended=if(collectedCaptionTexts(threads)==collectedCaptionTexts(contentRoots))
            runCatching {repo.library.normalizedFusionRoots(post,threads)}.getOrDefault(emptyList())else emptyList()
        extended.ifEmpty {body}
    }}
    val shownMedia=rememberCollectedMedia(repo,if(fusion!=null)contentRoots else listOf(displayed))
    val mediaSources=remember(displayed,fusion,contentRoots){if(fusion==null)emptyMap()else contentRoots.asReversed().flatMap {root->root.media.map {it.url to root.contentSourceUrl}}.toMap()}
    val images=shownMedia.filter {it.type=="image"}
    val infoPosts=if(archive.showingArchive||displayed.recoveredArchive!=null)listOf(displayed)else threads
    LaunchedEffect(archive.showingArchive){fullImage=null}
    // Keep an open viewer on the picture the reader chose if later local
    // evidence removes another copy from the card underneath it.
    fullImage?.let {(opened,index)->MediaViewer(opened,index,displayed.contentSourceUrl,repo,sources=mediaSources){fullImage=null}}
    val reading=rememberArchiveDwellModifier("history:${repo.key(post)}",enabled=fullImage==null&&!showOriginals&&archiveViewportAllows(repo.key(post))&&!LocalArchiveChecksPaused.current,dwellMs=600){repo.viewed(post)}
    DisposableEffect(repo.key(post)){repo.lease(repo.key(post),true);onDispose {repo.lease(repo.key(post),false)}}
    val shape=when(post.platform){Platform.X,Platform.BLUESKY,Platform.THREADS,Platform.MASTODON,Platform.LINKEDIN->RoundedCornerShape(4.dp);Platform.REDDIT,Platform.LEMMY->RoundedCornerShape(14.dp);Platform.INSTAGRAM,Platform.PIXELFED,Platform.FACEBOOK->RoundedCornerShape(8.dp);Platform.PINTEREST,Platform.TIKTOK->RoundedCornerShape(22.dp);else->RoundedCornerShape(18.dp)}
    Surface(modifier=archive.dwell.then(reading),shape=shape,color=MaterialTheme.colorScheme.surface,border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f))) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                if(displayed.avatar!=null)RemoteImage(displayed.avatar!!,displayed.contentSourceUrl,repo,Modifier.size(36.dp).clip(CircleShape),maxEdge=256)else Surface(shape=CircleShape,color=Color(post.platform.accent).copy(alpha=.15f)){Text(displayed.author.removePrefix("@").take(1).uppercase().ifBlank {post.platform.mark},Modifier.padding(10.dp),fontWeight=FontWeight.Bold)}
                Column(Modifier.weight(1f).padding(start=10.dp)) {
                    if(attributedAccounts.size==1)MentionText(displayed.author.ifBlank {post.platform.label},repo,displayed.platform,displayed.canonicalUrl,accountLabel=true,explicitProfileLinks=displayed.links.orEmpty(),fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.height(20.dp))
                    else Row(Modifier.fillMaxWidth().height(20.dp).horizontalScroll(rememberScrollState()).testTag("Post authors ${repo.key(post)}"),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                        attributedAccounts.forEach {entry->Row(Modifier.clickable(role=androidx.compose.ui.semantics.Role.Button,onClick={showOriginals=true}).testTag("Post author ${CrossPosts.originalAccount(entry) ?: CrossPosts.originalKey(entry)}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            PlatformMark(entry.platform,15.dp)
                            MentionText(entry.author.ifBlank {entry.platform.label},repo,entry.platform,entry.canonicalUrl,accountLabel=true,explicitProfileLinks=entry.links.orEmpty(),fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=1)
                        }}
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(displayed.publishedAt?.let {DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))} ?: "Date not supplied",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" · ",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(fusion==null)SourcePresentation.label(post)else contentRoots.map {SourcePresentation.label(it)}.distinct().joinToString(" · "),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("Post source ${repo.key(post)}"))
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){PostActivity(repo,threads,postLinks)}
                }
                ArchiveVersionButton(archive.version,archive.showingArchive,archive.toggle)
                IconButton(onClick={vm.launch {repo.cardWork.track(threads.map(::postWorkKey),"Saving copy") {
                    if(isSaved)threads.forEach {repo.bookmark(it,false)}else {
                        val replies=captureLoadedThreads(vm,threads)
                        val editions=withContext(Dispatchers.Default){savePlan.value}
                        if(editions.isNotEmpty())repo.library.save(post,emptyList(),replies,fusionRoots=editions)else repo.bookmark(post,true)
                    }
                }}}){Icon(if(isSaved)Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,if(isSaved)"Remove saved post" else "Save post",tint=if(isSaved)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            val visualFirst=linkedVideo||post.platform in setOf(Platform.INSTAGRAM,Platform.PIXELFED,Platform.SNAPCHAT,Platform.TIKTOK,Platform.PINTEREST,Platform.IFUNNY)
            ArchiveVersionLabel(archive.version,archive.showingArchive,Modifier.padding(start=16.dp,end=16.dp,bottom=10.dp))
            RecoveredArchiveLabel(displayed,Modifier.padding(start=16.dp,end=16.dp,bottom=10.dp))
            if(post.preview)Text("Public preview · full content unavailable",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp))
            if(linkedVideo&&caption.title.isNotBlank())MentionText(caption.title,repo,caption.platform,caption.canonicalUrl,fontSize=18.sp,fontWeight=FontWeight.SemiBold,maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=16.dp,vertical=10.dp),explicitProfileLinks=caption.links.orEmpty())
            if(!visualFirst){if(fusion!=null)FusedPostText(fusion,onOpen,contentRoots,repo,showDetails=false)else {if(caption.author!=displayed.author)PostAttribution("Fuller original · ${caption.author}",Modifier.padding(horizontal=16.dp),repo,caption.platform,caption.canonicalUrl);PostText(caption,repo,onOpen,showDetails=false)}}
            shownMedia.forEach {m->
                val owner=if(fusion==null)displayed else contentRoots.firstOrNull {root->root.media.any {it.url==m.url}} ?: displayed
                if(m.type=="image")RemoteImage(m.url,owner.contentSourceUrl,repo,Modifier.fillMaxWidth().testTag("Post image ${repo.key(post)} ${m.url}").aspectRatio(when(post.platform){Platform.TIKTOK,Platform.SNAPCHAT->.75f;Platform.INSTAGRAM,Platform.PIXELFED->1f;Platform.PINTEREST->.8f;else->1.25f}).padding(top=12.dp).clickable {fullImage=images.toList() to images.indexOf(m)},m.description) else VideoCard(m,owner,repo)
            }
            if(post.platform==Platform.TIKTOK&&displayed.media.any {it.description.startsWith("Video preview;")})Text("Video preview · playback unavailable from this source",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(16.dp))
            if(linkedVideo&&(caption.body.isNotBlank()||fusion!=null)) {
                TextButton(onClick={videoDescriptionExpanded=!videoDescriptionExpanded},modifier=Modifier.padding(horizontal=8.dp).testTag("Video description ${repo.key(post)}")) {
                    Text(if(videoDescriptionExpanded)"Hide description"else "Description",fontSize=12.sp)
                    Icon(if(videoDescriptionExpanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null,Modifier.size(18.dp))
                }
                if(videoDescriptionExpanded){if(fusion!=null)FusedPostText(fusion,onOpen,contentRoots,repo,showDetails=false)else PostText(caption.copy(title=""),repo,onOpen,showDetails=false)}
            }else if(visualFirst&&!linkedVideo){if(fusion!=null)FusedPostText(fusion,onOpen,contentRoots,repo,showDetails=false)else {if(caption.author!=displayed.author)PostAttribution("Fuller original · ${caption.author}",Modifier.padding(horizontal=16.dp),repo,caption.platform,caption.canonicalUrl);PostText(caption,repo,onOpen,showDetails=false)}}
            val sourceUrls=remember(postLinks,threads,repo){(postLinks+threads.map {repo.publicUrl(it.canonicalUrl,it.platform)}).distinct()}
            val inspectableSources=remember(sourceUrls){sourceRequests(sourceUrls).isNotEmpty()}
            if(showSourceDetails)SourceDetailsSheet(repo,sourceUrls,onOpen={vm.openLink(it)},onDismiss={showSourceDetails=false})
            PaperUpdateNotice(repo,postLinks){showSourceDetails=true}
            val detailSections=remember(caption,fusion,contentRoots,repo){postDetailSections(caption,fusion,contentRoots,repo)}
            val quietLinks=postLinks.filterNot {link->detailSections.any {it.value.contains(link)}}
            PostDetailsInventory(detailSections,quietLinks,repo.key(post),onOpen={vm.openLink(it)},onCopy={outgoing.copyUrl(it,displayed.platform)},repo=repo)
            CommunityNoteDisclosure((if(archive.showingArchive)listOf(displayed)else threads).flatMap {it.contextNotes.orEmpty()}.distinct(),onOpen={vm.openLink(it)})
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
              Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={vm.discussions.open(threads)},modifier=Modifier.testTag("Read discussions ${repo.key(post)}")){Icon(Icons.Rounded.ChatBubbleOutline,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(threads.size>1)"Discussions"else "Comments")}
                ContextPostAction(repo,displayed,threads,contentRoots,archive.showingArchive)
                if(threads.size>1)threads.distinctBy {it.platform}.forEach {entry->IconButton(onClick={vm.discussions.open(threads,entry.platform.id)},modifier=Modifier.semantics {contentDescription="Read ${entry.platform.label} comments"}){PlatformMark(entry.platform,20.dp)}}
                if(repeats)TextButton(onClick={showOriginals=true},modifier=Modifier.testTag("Collected originals ${repo.key(post)}")){Text("${threads.size} originals",fontSize=12.sp)}
              }
                MediaInfoAction(repo,infoPosts)
                Box {
                    IconButton(onClick={showOptions=true},modifier=Modifier.testTag("Post options ${repo.key(post)}")){Icon(Icons.Rounded.MoreHoriz,"Post options",Modifier.size(22.dp))}
                    DropdownMenu(expanded=showOptions,onDismissRequest={showOptions=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                        DropdownMenuItem(text={Text("Copy clean link")},leadingIcon={Icon(Icons.Rounded.Link,null)},modifier=Modifier.testTag("Copy post link ${repo.key(post)}"),onClick={showOptions=false;outgoing.copy(copyTarget,threads)})
                        DropdownMenuItem(text={Text("Save a copy")},leadingIcon={Icon(Icons.Rounded.Download,null)},modifier=Modifier.testTag("Save copy ${repo.key(post)}"),onClick={
                            showOptions=false
                            val replies=captureLoadedThreads(vm,threads)
                            vm.launch {
                                val editions=repo.cardWork.track(threads.map(::postWorkKey),"Preparing saved copy"){withContext(Dispatchers.Default){savePlan.value}}
                                // A late edition must not replace this completed
                                // plan or restart its proof on the UI thread.
                                saveCopy=PostSaveCapture(post,replies,editions)
                            }
                        })
                        if(inspectableSources)DropdownMenuItem(text={Text("Source details")},leadingIcon={Icon(Icons.Rounded.TravelExplore,null)},modifier=Modifier.testTag("Inspect sources ${repo.key(post)}"),onClick={showOptions=false;showSourceDetails=true})
                        onFilter?.let {filter->DropdownMenuItem(text={Text("Filter like this…")},leadingIcon={Icon(Icons.Rounded.Tune,null)},modifier=Modifier.testTag("Filter like ${repo.key(post)}"),onClick={showOptions=false;filter()})}
                        onExplain?.let {explain->DropdownMenuItem(text={Text("Why this appeared")},leadingIcon={Icon(Icons.Rounded.Info,null)},modifier=Modifier.testTag("Why post ${repo.key(post)}"),onClick={showOptions=false;explain()})}
                        post.score?.let {Text("Platform score: $it",Modifier.padding(horizontal=16.dp,vertical=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
        }
    }
    saveCopy?.let {capture->SaveCopySheet(repo,capture.post,capture.replies,fusionRoots=capture.editions,onSaved={saveCopy=null;android.widget.Toast.makeText(context,"Saved to your library",android.widget.Toast.LENGTH_SHORT).show()},onDismiss={saveCopy=null})}
    if(showOriginals)OriginalPostsSheet(vm,threads,copyTarget){showOriginals=false}
}

internal fun captureLoadedThreads(vm: AntiSocialViewModel,roots: List<Post>,reader: DiscussionReader=vm.discussions): List<CapturedThread> {
    val keys=roots.map(vm.repo::key).toSet();val now=System.currentTimeMillis()
    val discussion=reader.state.value
    val captures=discussion?.threads?.values.orEmpty().filter {vm.repo.key(it.post) in keys&&it.page!=null}.map {thread->
        val page=requireNotNull(thread.page)
        CapturedThread(thread.post,Discussions.comments(thread.post,page),discussion!!.order.name,now,page.next!=null)
    }.toMutableList()
    val page=vm.screen.value.page
    if(page!=null)roots.filter {root->captures.none {vm.repo.key(it.root)==vm.repo.key(root)}}.forEach {root->
        val replies=Discussions.comments(root,page)
        if(replies.isNotEmpty())captures+=CapturedThread(root,replies,"SOURCE",now,page.next!=null)
    }
    return captures+reader.communityCaptures(roots)
}
@Composable private fun PostText(post: Post,repo: Repository,onOpen: ()->Unit,showDetails: Boolean=true) {
    val text=remember(post){PostPresentation.text(post)}
    val split=remember(text){PostBodyDetails.split(listOf(text.title,text.body).filter(String::isNotBlank).joinToString("\n"))}
    val titleVisible=text.title.isNotBlank()&&(split.body==text.title||split.body.startsWith(text.title+"\n"))
    val body=if(titleVisible)split.body.removePrefix(text.title).removePrefix("\n")else split.body
    text.attribution?.let {PostAttribution(it,Modifier.padding(start=16.dp,end=16.dp,bottom=8.dp),repo,post.platform,post.canonicalUrl)}
    if(titleVisible)MentionText(text.title,repo,post.platform,post.canonicalUrl,explicitProfileLinks=post.links.orEmpty(),fontSize=if(post.platform==Platform.REDDIT)18.sp else 16.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(horizontal=16.dp).clickable(onClick=onOpen))
    if(body.isNotBlank())PostBodyText(if(showDetails)text.body else body,repo,post.platform,onOpen,modifier=Modifier.padding(horizontal=16.dp,vertical=if(titleVisible||post.media.isNotEmpty())10.dp else 0.dp),tag="Post body ${repo.key(post)}",showDetails=showDetails,sourceUrl=post.canonicalUrl,explicitProfileLinks=post.links.orEmpty())
}
@Composable private fun PinterestFeed(vm: AntiSocialViewModel,posts: List<Post>,savedKeys: Set<String>,title: String) {
    val repo=vm.repo
    val grid=rememberLazyStaggeredGridState()
    ArchiveGridViewport(grid) {
    LazyVerticalStaggeredGrid(state=grid,columns=StaggeredGridCells.Adaptive(145.dp),modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalItemSpacing=14.dp) {
        item(span=StaggeredGridItemSpan.FullLine){Row(verticalAlignment=Alignment.CenterVertically){Text(title,fontSize=25.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={vm.refresh()}){Icon(Icons.Rounded.Refresh,"Refresh")}}}
        item(span=StaggeredGridItemSpan.FullLine){PageIntro(vm)}
        items(CachePolicy.chronological(posts),key={repo.key(it)}) {PinCard(it,vm,repo.key(it) in savedKeys)}
        if(posts.any {it.publishedAt==null})item(span=StaggeredGridItemSpan.FullLine){Text("DATE UNAVAILABLE",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(posts.filter {it.publishedAt==null}.distinctBy {repo.key(it)},key={repo.key(it)}) {PinCard(it,vm,repo.key(it) in savedKeys)}
        if(vm.screen.value.page?.next!=null)item(span=StaggeredGridItemSpan.FullLine){OutlinedButton(onClick={vm.loadMore()},modifier=Modifier.fillMaxWidth()){Text("Load more")}}
    }
    }
}
@Composable private fun PinCard(post: Post,vm: AntiSocialViewModel,saved: Boolean) {
    val repo=vm.repo;val archive=rememberArchivePresentation(post,repo);val displayed=archive.post;val image=displayed.media.firstOrNull {it.type=="image"}
    DisposableEffect(repo.key(post)){repo.lease(repo.key(post),true);onDispose {repo.lease(repo.key(post),false)}}
    val reading=rememberArchiveDwellModifier("history:${repo.key(post)}",enabled=archiveViewportAllows(repo.key(post))&&!LocalArchiveChecksPaused.current,dwellMs=600){repo.viewed(post)}
    Column(Modifier.fillMaxWidth().then(archive.dwell).then(reading)) {
        if(image!=null)RemoteImage(image.url,displayed.contentSourceUrl,repo,Modifier.fillMaxWidth().heightIn(min=96.dp,max=400.dp).clip(RoundedCornerShape(20.dp)).clickable {repo.postTarget(post)?.let {vm.open(it)}},image.description,maxEdge=768)
        MentionText(displayed.title.ifBlank {displayed.body},repo,displayed.platform,displayed.canonicalUrl,modifier=Modifier.padding(top=8.dp),explicitProfileLinks=displayed.links.orEmpty(),maxLines=3,overflow=TextOverflow.Ellipsis,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
        ArchiveVersionLabel(archive.version,archive.showingArchive,Modifier.padding(top=6.dp))
        RecoveredArchiveLabel(displayed,Modifier.padding(top=6.dp))
        Row(verticalAlignment=Alignment.CenterVertically){MentionText(displayed.author.ifBlank {"Public pin"},repo,displayed.platform,displayed.canonicalUrl,accountLabel=true,explicitProfileLinks=displayed.links.orEmpty(),modifier=Modifier.weight(1f),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant);ArchiveVersionButton(archive.version,archive.showingArchive,archive.toggle);IconButton(onClick={vm.launch {repo.bookmark(post,!saved)}},modifier=Modifier.size(40.dp)){Icon(if(saved)Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,if(saved)"Remove saved post"else "Save post",Modifier.size(18.dp))}}
    }
}

@Composable internal fun RemoteImage(url: String,source: String,repo: Repository,modifier: Modifier,description: String="",contentScale: ContentScale=ContentScale.Crop,maxEdge: Int=1600) {
    var bitmap by remember(url,maxEdge){mutableStateOf<android.graphics.Bitmap?>(null)}
    var failed by remember(url,maxEdge){mutableStateOf(false)}
    var attempt by remember(url,maxEdge){mutableIntStateOf(0)}
    DisposableEffect(url){repo.lease(Repository.hash(url),true);onDispose {repo.lease(Repository.hash(url),false)}}
    LaunchedEffect(url,maxEdge,attempt) {failed=false;try {bitmap=repo.images.load(url,source,maxEdge)}catch(e: CancellationException){throw e}catch(_: Exception){failed=true}}
    if(bitmap!=null)Image(bitmap!!.asImageBitmap(),repo.publicText(description),modifier,contentScale=contentScale)else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){if(failed)IconButton(onClick={attempt++}){Icon(Icons.Rounded.Refresh,"Retry image",tint=MaterialTheme.colorScheme.onSurfaceVariant)}else CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)}
}

@Composable internal fun SourceDialog(initial: Platform,opening: Boolean,searchInitially: Boolean=true,initialTarget: Target?=null,onDismiss: ()->Unit,onSubmit: (Platform,String,String,String?,Boolean,SearchScope?)->Unit) {
    var platform by remember {mutableStateOf(initial.takeIf(ReaderScope::isFeedSource) ?: PlatformOrder.defaultSelected.first())};var value by remember {mutableStateOf("")};var group by remember {mutableStateOf("")};var feed by remember {mutableStateOf("")}
    var searching by remember {mutableStateOf(searchInitially)}
    var scope by remember {mutableStateOf(initialTarget?.takeIf {it.platform==initial}?.let {if(it.kind==TargetKind.SEARCH)SearchScopes.from(it)else SearchScopes.fromContext(it)})}
    val canSearch=true
    val submit={if(value.isNotBlank())onSubmit(platform,value.trim(),group.trim(),feed.trim().takeIf{it.isNotBlank()},opening&&searching&&canSearch,scope.takeIf {opening&&searching&&canSearch})}
    AlertDialog(onDismissRequest=onDismiss,modifier=Modifier.imePadding(),properties=DialogProperties(decorFitsSystemWindows=false),title={Row(verticalAlignment=Alignment.CenterVertically){Text(if(opening&&canSearch)"Search"else if(opening)"Open public content" else "Add a subscription",Modifier.weight(1f));SearchPlatformPicker(platform){platform=it;scope=null}}},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(opening&&canSearch)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=searching,onClick={searching=true},label={Text("Search")});FilterChip(selected=!searching,onClick={searching=false},label={Text("Profile or link")})}
        OutlinedTextField(value,{value=it},label={Text(if(opening&&searching&&canSearch){if(scope==null)"Search terms, @username, or link"else "Search terms"}else platformInputLabel(platform))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Content query"),keyboardOptions=KeyboardOptions(imeAction=if(opening&&searching&&canSearch)ImeAction.Search else ImeAction.Go),keyboardActions=KeyboardActions(onSearch={submit()},onGo={submit()}))
        if(opening&&searching&&canSearch)SearchScopeControl(platform,scope,{scope=it})
        if(!searching)platformScope(platform)?.let {Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(!opening){OutlinedTextField(group,{group=it},label={Text("Group (optional)")},singleLine=true);OutlinedTextField(feed,{feed=it},label={Text("Alternate RSS/Atom URL (optional)")},singleLine=true)}
        Text(if(opening&&searching&&canSearch){if(scope==null)"Public results depend on source availability."else "Search inside this scope. Public coverage varies."}else if(opening)"Open an exact username, community, tag, or link."else "Public data only. Your subscription is stored on this device.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={TextButton(onClick={submit()},enabled=value.isNotBlank(),modifier=Modifier.testTag("Submit content query")){Text(if(opening&&searching&&canSearch)"Search"else if(opening)"Open" else "Subscribe")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
private fun replyDepth(post: Post,posts: List<Post>): Int {
    var parent=post.parentId;var depth=0;val seen=mutableSetOf(post.id)
    while(parent!=null&&depth<4&&seen.add(parent)){val next=posts.firstOrNull {it.id==parent} ?: break;depth++;parent=next.parentId}
    return depth
}
