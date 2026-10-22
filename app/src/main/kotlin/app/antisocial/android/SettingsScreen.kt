package app.antisocial.android

import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import app.antisocial.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URI

/** Settings navigation lives in the ViewModel so activity recreation keeps the page. */
enum class SettingsPage(val title: String) {
    HOME("Settings"), CONTENT_FILTERS("Feed filters"), AI("AI models"), ACCOUNT_NAMES("Account names"), SPONSORBLOCK("SponsorBlock"), PLATFORMS("Platform tabs"), PRIVACY("Privacy & connection"),
    SEARCH("Search"), COMMENTS("Comments"), REFRESH("Feed updates"), STORAGE("Storage"),
    FILTERS("Network protection"), FILTER_LISTS("Filter lists"), BLOCKED("Recent blocks"),
    LINKS("Link opening"), APPEARANCE("Appearance"), PORTABILITY("Import & export"), BACKUP("Backup & restore")
}

private data class NumberSetting(val title: String,val description: String,val value: Int,val range: IntRange,val unit: String,val save: (Int)->Unit)
private data class ChoiceSetting(val title: String,val value: Int,val options: List<Pair<Int,String>>,val choose: (Int)->Unit,val custom: ()->Unit)

private data class SettingsValues(
    val tor: Boolean,val originalFallback: Boolean,val searchProvider: SearchProvider,val archiveChecks: Boolean,val archiveRecovery: Boolean,
    val intervalMinutes: Int,val cacheMb: Int,val unseenDays: Int,val viewedDays: Int,
    val historyDays: Int,val theme: String,val externalBrowser: String,
    val disabledLists: Set<String>,val customLists: Set<String>,
    val videoCacheMb: Int,val videoCacheDays: Int,val videoDownloadLimitMb: Int,val videoDownloadWifiOnly: Boolean,
    val historyFullText: Boolean,val historyTextMb: Int,val readableMentions: Boolean,val mentionLookups: Boolean
)
private fun readSettings(prefs: Preferences)=SettingsValues(
    prefs.tor,prefs.originalFallback,prefs.searchProvider,prefs.archiveChecks,prefs.archiveRecovery,prefs.intervalMinutes,
    prefs.cacheMb,prefs.unseenDays,prefs.viewedDays,prefs.historyDays,prefs.theme,
    prefs.externalBrowser,prefs.disabledLists(),prefs.customLists(),
    prefs.videoCacheMb,prefs.videoCacheDays,prefs.videoDownloadLimitMb,prefs.videoDownloadWifiOnly,prefs.historyFullText,prefs.historyTextMb,prefs.readableMentions,prefs.mentionLookups
)

@Composable fun SettingsScreen(vm: AntiSocialViewModel,selected: List<Platform>,onSelected: (List<Platform>)->Unit,onChanged: ()->Unit) {
    val repo=vm.repo;val prefs=repo.prefs;val context=LocalContext.current
    val state by vm.screen.collectAsState()
    val page=state.settingsPage
    val usage by repo.usage.collectAsState()
    val tor by repo.tor.state.collectAsState()
    val filters by repo.filters.collectAsState()
    val blocked by repo.blocked.collectAsState()
    var protection by remember {mutableStateOf(repo.gateway.protectionStats())}
    LaunchedEffect(page) {if(page==SettingsPage.FILTERS)while(true){protection=repo.gateway.protectionStats();delay(1000)}}
    var revision by remember {mutableIntStateOf(0)}
    val communityConnection by repo.commentAnywhere.connection.collectAsState()
    var communityOwner by remember {mutableStateOf<String?>(null)}
    var communityIdentityError by remember {mutableStateOf<String?>(null)}
    var resetCommunityIdentity by remember {mutableStateOf(false)}
    var communityBusy by remember {mutableStateOf(false)}
    LaunchedEffect(page,revision) {if(page==SettingsPage.COMMENTS)try {
        communityOwner=repo.commentAnywhere.owner();communityIdentityError=null
    }catch(e: CancellationException){throw e}catch(e: Exception){communityIdentityError=e.message}}
    val values=remember(revision){readSettings(prefs)}
    val provider=values.searchProvider
    val scope=rememberCoroutineScope()
    val snackbar=remember {SnackbarHostState()}
    val savedPages=rememberSaveableStateHolder()
    var number by remember {mutableStateOf<NumberSetting?>(null)}
    var choice by remember {mutableStateOf<ChoiceSetting?>(null)}
    var clearHistory by remember {mutableStateOf(false)}
    var clearVideos by remember {mutableStateOf(false)}
    var clearingCache by remember {mutableStateOf(false)}
    var addList by remember {mutableStateOf(false)}
    var customList by remember {mutableStateOf("")}
    var customListError by remember {mutableStateOf<String?>(null)}
    var backupMode by remember {mutableStateOf<String?>(null)}
    var password by remember {mutableStateOf("")}
    var pendingBackup by remember {mutableStateOf<ByteArray?>(null)}
    var pendingImport by remember {mutableStateOf<ByteArray?>(null)}
    var backupBusy by remember {mutableStateOf(false)}
    var previousInterval by rememberSaveable {mutableIntStateOf(values.intervalMinutes.takeIf {it>0} ?: 60)}

    fun work(success: String?=null,action: suspend ()->Unit) {
        scope.launch {try {action();success?.let {snackbar.showSnackbar(it)}}catch(e: CancellationException){throw e}catch(e: Exception){snackbar.showSnackbar(e.message ?: "Could not complete this action. Try again.")}}
    }
    fun networkChanged(){repo.applyNetworkSettings();revision++}
    fun refreshChanged(){scheduleRefresh(context,prefs);revision++}
    fun setInterval(value: Int){prefs.intervalMinutes=value;if(value>0)previousInterval=value;refreshChanged()}
    fun editInterval(){number=NumberSetting("Refresh interval","Android allows automatic updates at least 15 minutes apart.",values.intervalMinutes.takeIf {it>0} ?: previousInterval,15..10080,"minutes",::setInterval)}
    fun editCache(){number=NumberSetting("Cache limit","Temporary content is removed automatically to stay within this limit. Saved items are protected.",values.cacheMb,50..5000,"MB"){prefs.cacheMb=it;revision++;work {repo.cleanup()}}}

    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        if(uri==null)pendingBackup=null else work("Encrypted backup exported") {
            val data=pendingBackup ?: error("Prepare the backup again.")
            try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use {it.write(data)} ?: error("Could not open this destination.")}}finally {pendingBackup=null}
        }
    }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)work {
        pendingImport=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use {input->
            val output=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
            while(true){val count=input.read(chunk);if(count<0)break;require(output.size()+count<=16*1024*1024+128){"This backup is too large."};output.write(chunk,0,count)}
            output.toByteArray()
        } ?: error("Could not open this backup.")}
        backupMode="import"
    }}
    val linkSettings=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){}
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    val browsers=remember {context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW,Uri.parse("https://example.org")),PackageManager.MATCH_ALL).filter {it.activityInfo.packageName!=context.packageName}.distinctBy {it.activityInfo.packageName}}
    LaunchedEffect(Unit){repo.updateUsage()}

    Box(Modifier.fillMaxSize()) {
        savedPages.SaveableStateProvider(page.name) {
            if(page==SettingsPage.CONTENT_FILTERS)ContentFiltersScreen(repo)else if(page==SettingsPage.AI)AiSettingsScreen(repo)else if(page==SettingsPage.PORTABILITY)PortabilityScreen(repo,onChanged={revision++;onSelected(prefs.selected);onChanged()})else if(page==SettingsPage.SPONSORBLOCK)SponsorBlockSettings(repo.sponsorBlock)else
            LazyColumn(Modifier.fillMaxSize().testTag("Settings list"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=6.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                when(page) {
                    SettingsPage.SPONSORBLOCK,SettingsPage.AI,SettingsPage.CONTENT_FILTERS -> Unit
                    SettingsPage.HOME -> {
                        item {StatusCard(if(filters.ready)"Protection is on" else "Starting protection",if(filters.ready)"Content and media requests are filtered."else filters.message,Icons.Rounded.Shield){vm.settings(SettingsPage.FILTERS)}}
                        item {SettingsGroup("YOUR EXPERIENCE") {
                            Destination("Feed filters","Shape feeds and search · preview every rule",Icons.Rounded.Tune,SettingsPage.CONTENT_FILTERS,vm)
                            SettingsDivider()
                            Destination("Platform tabs","${selected.size} selected · choose and reorder",Icons.Rounded.Dashboard,SettingsPage.PLATFORMS,vm)
                            SettingsDivider()
                            Destination("Appearance",themeName(values.theme),Icons.Rounded.Palette,SettingsPage.APPEARANCE,vm)
                            SettingsDivider()
                            Destination("Account names","Readable mentions and remembered names",Icons.Rounded.Person,SettingsPage.ACCOUNT_NAMES,vm)
                            SettingsDivider()
                            Destination("AI models","Optional local models or your own provider",Icons.Rounded.AutoAwesome,SettingsPage.AI,vm)
                        }}
                        item {SettingsGroup("READING & PRIVACY") {
                            Destination("Privacy & connection",if(values.tor)"All traffic through Tor"else if(values.originalFallback&&provider.torOnly)"Content direct · search through Tor"else "Direct connection",Icons.Rounded.Lock,SettingsPage.PRIVACY,vm)
                            SettingsDivider()
                            Destination("Search",provider.label,Icons.Rounded.Search,SettingsPage.SEARCH,vm)
                            SettingsDivider()
                            Destination("Comments","Shared discussion and your local identity",Icons.Rounded.Forum,SettingsPage.COMMENTS,vm)
                            SettingsDivider()
                            Destination("Feed updates",intervalName(values.intervalMinutes),Icons.Rounded.Update,SettingsPage.REFRESH,vm)
                            SettingsDivider()
                            Destination("SponsorBlock","YouTube skips and highlight jump",Icons.Rounded.SkipNext,SettingsPage.SPONSORBLOCK,vm)
                        }}
                        item {SettingsGroup("ON THIS DEVICE") {
                            Destination("Storage","${size(usage.cache)} cached · saved items protected",Icons.Rounded.Storage,SettingsPage.STORAGE,vm)
                            SettingsDivider()
                            Destination("Link opening","Supported links and your browser",Icons.Rounded.Link,SettingsPage.LINKS,vm)
                            SettingsDivider()
                            Destination("Import & export","Move your reader or share chosen sources",Icons.Rounded.ImportExport,SettingsPage.PORTABILITY,vm)
                            SettingsDivider()
                            Destination("Backup & restore","An encrypted copy of your library",Icons.Rounded.Backup,SettingsPage.BACKUP,vm)
                        }}
                        item {Column(Modifier.padding(horizontal=4.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            BrandWordmark(height=22.dp)
                            Text("${BuildConfig.VERSION_NAME} · Free and open source\nBuilt with Darwin's rebuilt and expanded routing.",fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }}
                    }
                    SettingsPage.PLATFORMS -> {
                        item {SettingsIntro("Choose the sites you read. Move selected tabs into the order you want.")}
                        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            SettingLabel("YOUR TABS")
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick={onSelected(PlatformOrder.sort(selected))},enabled=selected!=PlatformOrder.sort(selected),modifier=Modifier.testTag("Sort tabs by popularity")){Text("Sort by popularity")}
                        }}
                        items(selected,key={it.id}) {platform->
                            Surface(shape=RoundedCornerShape(18.dp),color=settingsCardColor()) {
                                Column {
                                    PlatformChoice(platform,true,selected.size>1){onSelected(selected-platform)}
                                    Row(Modifier.fillMaxWidth().padding(start=60.dp,end=8.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                        Text("Tab ${selected.indexOf(platform)+1}",Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        val index=selected.indexOf(platform)
                                        IconButton(onClick={val next=selected.toMutableList();java.util.Collections.swap(next,index,index-1);onSelected(next)},enabled=index>0){Icon(Icons.Rounded.ArrowUpward,"Move ${platform.label} tab left")}
                                        IconButton(onClick={val next=selected.toMutableList();java.util.Collections.swap(next,index,index+1);onSelected(next)},enabled=index<selected.lastIndex){Icon(Icons.Rounded.ArrowDownward,"Move ${platform.label} tab right")}
                                    }
                                }
                            }
                        }
                        if(selected.size<ReaderScope.platforms.size) {
                            item {SettingLabel("ADD A TAB")}
                            items(PlatformOrder.popular.filter {it !in selected},key={it.id}) {platform->SettingsGroup {PlatformChoice(platform,false,true){onSelected(selected+platform)}}}
                        }
                        item {SettingsNote("Keep at least one tab selected. Hiding a tab keeps its subscriptions and saved items. Linked YouTube videos open in the player independently of these tabs.")}
                    }
                    SettingsPage.PRIVACY -> {
                        item {StatusCard(if(values.tor)"All traffic uses Tor"else "Content connects directly",if(values.tor)tor.message else if(values.originalFallback&&provider.torOnly)"${provider.label} searches use a separate Tor connection."else "Tor for all traffic is off.",Icons.Rounded.Shield)}
                        item {SettingsGroup("CONNECTION") {
                            SettingToggle("Tor for all traffic","Route content, photos, videos and updates through Tor. Requests pause if Tor disconnects.",values.tor){prefs.tor=it;networkChanged();if(it&&android.os.Build.VERSION.SDK_INT>=33)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)}
                            SettingsDivider()
                            SettingToggle("Use original sites as fallback","When alternate sources fail, try public original content. Also enables Bing and DuckDuckGo search.",values.originalFallback){prefs.originalFallback=it;networkChanged()}
                            SettingsDivider()
                            SettingToggle("Check older post versions","After you linger on a post, quietly send its clean link to public archives. Uses your content connection; checks are limited to protect archive services.",values.archiveChecks){prefs.archiveChecks=it;revision++}
                            SettingsDivider()
                            SettingToggle("Recover unavailable posts","When current sources fail, look for this exact post in public archives. Uses your content connection.",values.archiveRecovery){prefs.archiveRecovery=it;revision++}
                        }}
                        item {SettingsGroup("PROTECTION & SOURCES") {
                            Destination("Network protection",if(filters.ready)"On · content and media"else "Starting…",Icons.Rounded.Shield,SettingsPage.FILTERS,vm)
                            SettingsDivider()
                            SettingRow("Inspect and test source pools",subtitle="Check public content for ${state.platform.label}",icon=Icons.Rounded.Troubleshoot,onClick={vm.section("pools")})
                        }}
                        item {SettingsNote("anti social keeps no social login sessions. YouTube playback may decode its player signatures locally. On a direct connection, the destination can see your IP address.")}
                    }
                    SettingsPage.SEARCH -> {
                        item {SettingsIntro("Used across all tabs when social sources cannot supply search results.")}
                        item {SettingsGroup {
                            SearchProvider.entries.forEachIndexed {index,engine->
                                if(index>0)SettingsDivider()
                                SettingOption(engine.label,if(engine.torOnly)"Always through Tor"else "Darwin's reviewed LibreY instances",provider==engine,"Search provider ${engine.id}"){prefs.searchProvider=engine;networkChanged()}
                            }
                        }}
                        item {StatusCard(if(provider.torOnly)"Search through Tor"else "Darwin search routes",if(provider.torOnly){if(values.originalFallback)tor.message else "Enable original-site fallback in Privacy & connection to use this provider."}else if(values.tor)"These sources follow your all-traffic Tor setting."else "These sources use your direct connection.",Icons.Rounded.TravelExplore)}
                        if(provider.torOnly&&!values.originalFallback)item {TextButton(onClick={vm.settings(SettingsPage.PRIVACY)}){Text("Open privacy settings")}}
                        item {SettingsGroup("LOCAL DISCOVERY") {SettingToggle("Read event flyers","Read image announcements on this device after initial results. Works without AI; no background crawling.",prefs.localFlyerReading){prefs.localFlyerReading=it;revision++}}}
                        item {SettingsNote("Queries go only to the selected provider. anti social never silently switches engines. The provider receives your query; opening a result follows your content connection setting.")}
                    }
                    SettingsPage.COMMENTS -> {
                        item {SettingsIntro("A place to participate without a social media account. Shared replies use CommentAnywhere, attached to the original post's address.")}
                        item {SettingsGroup {
                            SettingToggle("Shared community comments","Include shared replies in All and enable participation in All or the community tab.",prefs.communityComments){prefs.communityComments=it;repo.commentAnywhere.clear();revision++}
                        }}
                        item {StatusCard(if(!prefs.communityComments)"Shared comments are off"else communityConnection.title,if(!prefs.communityComments)"Original platform comments remain available."else communityConnection.detail,Icons.Rounded.Forum)}
                        item {SettingsGroup("CONNECTION") {
                            SettingRow("Check connection",subtitle="Read the service's own public discussion. No identity is created.",icon=Icons.Rounded.Refresh,enabled=prefs.communityComments&&!communityConnection.checking&&!communityBusy,modifier=Modifier.testTag("Check community connection"),onClick={work {communityBusy=true;try {repo.commentAnywhere.check()}finally {communityBusy=false}}})
                            SettingsDivider()
                            Destination("Privacy & connection",if(values.tor)"Shared comments use Tor and wait if it disconnects."else "Shared comments use your content connection.",Icons.Rounded.Lock,SettingsPage.PRIVACY,vm)
                        }}
                        item {SettingsGroup("ON THIS DEVICE") {
                            SettingRow("Participation identity",subtitle=communityIdentityError ?: if(communityOwner==null)"Created only when you first publish or vote. Reading needs no account or key."else "An anonymous identity is stored securely on this device to manage your comments.",icon=Icons.Rounded.PersonOutline)
                            SettingsDivider()
                            SettingRow("Reset participation identity",subtitle="Forget local ownership of earlier comments.",icon=Icons.Rounded.RestartAlt,enabled=!communityBusy&&(communityOwner!=null||communityIdentityError!=null),modifier=Modifier.testTag("Reset community identity"),onClick={resetCommunityIdentity=true})
                        }}
                        item {SettingsNote("Shared replies are public and are not posted to the social platform. Your participation identity links your shared comments together. It is not included in portable backups.")}
                        item {SettingsNote("An unavailable shared service does not prevent reading original platform comments. Connection checks respect service cooldowns; there is no background polling.")}
                    }
                    SettingsPage.REFRESH -> {
                        item {SettingsIntro("Keep subscriptions up to date on your schedule.")}
                        item {SettingsGroup {
                            SettingToggle("Automatic refresh","Check your subscriptions in the background.",values.intervalMinutes>0){setInterval(if(it)previousInterval else 0)}
                            SettingsDivider()
                            SettingRow("Refresh every",value=intervalName(values.intervalMinutes).removePrefix("Every "),enabled=values.intervalMinutes>0,onClick={choice=ChoiceSetting("Refresh every",values.intervalMinutes,listOf(15 to "15 minutes",30 to "30 minutes",60 to "1 hour",180 to "3 hours",360 to "6 hours",1440 to "1 day"),::setInterval,::editInterval)})
                            SettingsDivider()
                            SettingToggle("Wi-Fi only","Use an unmetered connection for automatic feed updates.",prefs.wifiOnly){prefs.wifiOnly=it;refreshChanged()}
                        }}
                        item {SettingsNote("You can refresh a feed manually at any time. Android may delay background updates to save battery.")}
                    }
                    SettingsPage.STORAGE -> {
                        item {SettingsGroup {
                            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Text(size(usage.cache),fontSize=30.sp,fontWeight=FontWeight.SemiBold)
                                Text("Temporary cache · ${values.cacheMb} MB limit",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                LinearProgressIndicator(progress={(usage.cache.toFloat()/(values.cacheMb*1024f*1024f)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                                Text("${size(usage.saved)} saved · ${size(usage.filters)} filter data",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }}
                        item {SettingsNote("Saved posts and explicit offline downloads are protected from automatic cleanup.",Icons.Rounded.BookmarkBorder)}
                        item {SettingsGroup("AUTOMATIC CLEANUP") {
                            SettingRow("Cache limit",value="${values.cacheMb} MB",onClick={choice=ChoiceSetting("Cache limit",values.cacheMb,listOf(200 to "200 MB",500 to "500 MB",1000 to "1 GB"),{prefs.cacheMb=it;revision++;work {repo.cleanup()}},::editCache)})
                            SettingsDivider()
                            SettingRow("Unviewed items",value="${values.unseenDays} days",onClick={number=NumberSetting("Unviewed items","Remove ordinary cached items you have not opened after this many days.",values.unseenDays,1..365,"days"){prefs.unseenDays=it;revision++}})
                            SettingsDivider()
                            SettingRow("Viewed items",value="${values.viewedDays} days",onClick={number=NumberSetting("Viewed items","Remove ordinary cached items after this many days without viewing them again.",values.viewedDays,1..365,"days"){prefs.viewedDays=it;revision++}})
                        }}
                        item {SettingsGroup("VIDEO DOWNLOADS") {
                            SettingRow("Downloaded videos",value=size(usage.videoDownloads),subtitle="Selected videos kept with saved copies.",icon=Icons.Rounded.VideoLibrary)
                            SettingsDivider()
                            SettingRow("Video download allowance",value=if(values.videoDownloadLimitMb==0)"No limit"else "${values.videoDownloadLimitMb} MB",onClick={number=NumberSetting("Video download allowance","Pause additional video downloads at this amount. Existing saved videos stay. Use 0 for no limit.",values.videoDownloadLimitMb,0..100_000,"MB"){prefs.videoDownloadLimitMb=it;revision++}})
                            SettingsDivider()
                            SettingToggle("Download videos on Wi-Fi only","Pending videos can be retried from their saved copy in Library.",values.videoDownloadWifiOnly){prefs.videoDownloadWifiOnly=it;revision++}
                            SettingsDivider()
                            SettingRow("Remove downloaded videos",subtitle="Keep saved text, images, replies and collections.",icon=Icons.Rounded.DeleteOutline,onClick={clearVideos=true})
                        }}
                        item {SettingsGroup("VIDEO CACHE") {
                            SettingRow("Temporary videos",value=size(usage.videoCache),subtitle="Separate from videos you chose to download.")
                            SettingsDivider()
                            SettingRow("Video cache limit",value="${values.videoCacheMb} MB",onClick={number=NumberSetting("Video cache limit","Automatically remove temporary videos to stay within this limit. Use 0 to disable video caching.",values.videoCacheMb,0..5000,"MB"){prefs.videoCacheMb=it;revision++;work {repo.cleanup()}}})
                            SettingsDivider()
                            SettingRow("Keep temporary videos",value="${values.videoCacheDays} days",onClick={number=NumberSetting("Keep temporary videos","Remove ordinary cached videos after this many days without viewing. Saved videos stay.",values.videoCacheDays,1..365,"days"){prefs.videoCacheDays=it;revision++;work {repo.cleanup()}}})
                        }}
                        item {SettingsGroup("READING HISTORY") {
                            SettingToggle("Retain history text","Keep searchable text only for posts you actually view. Turning this off clears retained text; saved copies stay.",values.historyFullText){prefs.historyFullText=it;revision++;work {repo.library.pruneHistory()}}
                            SettingsDivider()
                            SettingRow("Reading history",value="${values.historyDays} days",onClick={number=NumberSetting("Reading history","Keep your local reading history for this many days.",values.historyDays,1..3650,"days"){prefs.historyDays=it;revision++;work {repo.library.pruneHistory()}}})
                            SettingsDivider()
                            SettingRow("History text limit",value="${values.historyTextMb} MB",onClick={number=NumberSetting("History text limit","Remove the oldest retained history text when this limit is reached. Saved copies stay.",values.historyTextMb,5..500,"MB"){prefs.historyTextMb=it;revision++;work {repo.library.pruneHistory()}}})
                        }}
                        item {SettingsGroup("FREE UP SPACE") {
                            SettingRow(if(clearingCache)"Clearing cache…"else "Clear cache",subtitle="Saved posts and offline downloads stay.",icon=Icons.Rounded.CleaningServices,enabled=!clearingCache,onClick={clearingCache=true;work("Temporary cache cleared. Saved items kept."){try{vm.clearCache()}finally{clearingCache=false}}})
                            SettingsDivider()
                            SettingRow("Clear reading history",subtitle="Remove opened links and retained history text.",icon=Icons.Rounded.History,onClick={clearHistory=true})
                        }}
                    }
                    SettingsPage.FILTERS -> {
                        item {StatusCard(if(filters.ready)"Protection is on"else "Starting protection",filters.message,Icons.Rounded.Shield)}
                        item {SettingsGroup {
                            SettingRow("Safety checks",value=grouped(protection.checked))
                            SettingsDivider()
                            SettingRow("Blocked this session",value=grouped(protection.blocked))
                            SettingsDivider()
                            SettingRow("List entries",value=if(filters.rules>=0)grouped(filters.rules.toLong())else "Saved protection")
                            SettingsDivider()
                            SettingRow("Last checked",value=if(filters.lastCheckedAt>0)java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(filters.lastCheckedAt))else "Awaiting first check")
                        }}
                        item {SettingsGroup {
                            Destination("Filter lists","${repo.filterSources.size} sources · manage or add",Icons.Rounded.FilterList,SettingsPage.FILTER_LISTS,vm)
                            SettingsDivider()
                            Destination("Recent blocks",if(blocked.isEmpty())"No blocks recorded this session"else "${blocked.size} recent requests",Icons.Rounded.Block,SettingsPage.BLOCKED,vm)
                        }}
                        item {Button(onClick={work {repo.refreshFilters();snackbar.showSnackbar(repo.filters.value.message)}},enabled=filters.ready&&!filters.updating,modifier=Modifier.fillMaxWidth()){Text(if(filters.updating)"Updating lists…"else "Update filter lists")}}
                        item {SettingsNote("Darwin's rules protect links, content and media. Lists are checked daily in the background on Wi-Fi; Android may defer checks. Working protection stays active during updates. Native article reading runs no page scripts. YouTube signature decoding stays within its extractor.")}
                    }
                    SettingsPage.FILTER_LISTS -> {
                        item {SettingsIntro("Choose the lists used for network protection. Changes take effect when you update the lists.")}
                        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick={customList="";customListError=null;addList=true},modifier=Modifier.weight(1f)){Text("Add a list")}
                            Button(onClick={work {repo.refreshFilters();snackbar.showSnackbar(repo.filters.value.message)}},enabled=filters.ready&&!filters.updating,modifier=Modifier.weight(1f)){Text(if(filters.updating)"Updating…"else "Update lists")}
                        }}
                        items(repo.filterSources,key={it.id}) {source->SettingsGroup {
                            Row(Modifier.fillMaxWidth().toggleable(value=source.id !in values.disabledLists,role=Role.Checkbox,onValueChange={prefs.setList(source.id,it);revision++}).testTag("Filter list ${source.id}").padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(checked=source.id !in values.disabledLists,onCheckedChange=null)
                                Column(Modifier.weight(1f).padding(start=12.dp)) {
                                    Text(filterTitle(source),fontSize=15.sp,fontWeight=FontWeight.Medium)
                                    Text(URI(source.url).host.orEmpty(),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                                if(source.url in values.customLists)IconButton(onClick={prefs.removeList(source.url);revision++}){Icon(Icons.Rounded.Close,"Remove ${filterTitle(source)} list")}
                            }
                        }}
                        item {SettingsNote("Only network rules are used. Native screens do not need website cosmetic rules or scriptlets.")}
                    }
                    SettingsPage.BLOCKED -> {
                        item {SettingsIntro("Recent requests stopped by your network filters. This list stays on your device and resets with the app session.")}
                        if(blocked.isEmpty())item {StatusCard("No blocks recorded","Most website telemetry never runs in anti social's native reader.",Icons.Rounded.Shield)}
                        items(blocked.take(100).withIndex().toList(),key={it.index}) {(_,entry)->SettingsGroup {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {Text(entry.first,fontWeight=FontWeight.SemiBold);Text(entry.second.take(180),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                    }
                    SettingsPage.LINKS -> {
                        item {SettingsIntro("Open links from your selected sites in anti social. Android lets you choose which domains open here.")}
                        item {SettingsGroup("SELECTED SITES") {
                            Destination("Choose sites",selected.joinToString(" · "){it.label},Icons.Rounded.Dashboard,SettingsPage.PLATFORMS,vm)
                        }}
                        item {Button(onClick={work {
                            LinkOpening.sync(context,selected)
                            linkSettings.launch(LinkOpening.settingsIntent(context))
                        }},modifier=Modifier.fillMaxWidth().testTag("Configure supported links")){Text("Choose supported links")}}
                        item {SettingsNote(if(android.os.Build.VERSION.SDK_INT>=31)"In Android, turn on Open supported links, then add the domains you want. These sites belong to their operators, so Android needs your approval."else "In Android app settings, open Open by default and enable supported links. When Android shows a link chooser, choose anti social and Always for a site you want.")}
                        item {SettingsNote("Only selected sites are offered by anti social. Android may list other supported domains; their handlers stay inactive until you select the site. For an unfamiliar federated server, choose its platform in anti social's link dialog and paste the link.")}
                        item {SettingsGroup("EXTERNAL BROWSER") {
                            SettingOption("Ask each time","Choose when opening an outside link.",values.externalBrowser.isBlank(),"External browser chooser"){prefs.externalBrowser="";revision++}
                            browsers.forEach {browser->SettingsDivider();SettingOption(browser.loadLabel(context.packageManager).toString(),"",values.externalBrowser==browser.activityInfo.packageName,"External browser ${browser.activityInfo.packageName}"){prefs.externalBrowser=browser.activityInfo.packageName;revision++}}
                        }}
                        if(browsers.isEmpty())item {SettingsNote("No external browser is installed. Install one to open links outside anti social.")}
                        item {SettingsNote("An external browser uses its own privacy and connection settings.")}
                    }
                    SettingsPage.ACCOUNT_NAMES -> {
                        item {SettingsIntro("Read public account names in place of @handles.")}
                        item {SettingsGroup {
                            SettingToggle("Readable mentions","Show public account names in posts, replies and author labels.",values.readableMentions){prefs.readableMentions=it;repo.mentionNames.changed();revision++}
                            SettingsDivider()
                            SettingToggle("Look up missing names","Fill gaps after your content loads. Turn off to use only names already on this device.",values.mentionLookups){prefs.mentionLookups=it;repo.mentionNames.changed();revision++}
                        }}
                        item {val names by repo.mentionNames.stats.collectAsState();SettingsGroup("ON THIS DEVICE") {
                            SettingRow("Remembered names",value="${names.names}",subtitle="${size(names.bytes)} of local name cache.",icon=Icons.Rounded.Person)
                            SettingsDivider()
                            SettingRow("Forget remembered names",subtitle="Posts, subscriptions and saved copies stay intact.",icon=Icons.Rounded.DeleteOutline,onClick={work("Remembered names cleared"){withContext(Dispatchers.IO){repo.mentionNames.clear()};repo.updateUsage()}})
                        }}
                        item {SettingsNote("Tap a name to see its original handle, platform and account link. Public display names do not verify who owns an account.")}
                        item {SettingsNote("While a public name is unavailable, recognized accounts use a clickable identifier without mention notation. Email addresses, URLs and code keep their original meaning.")}
                        item {SettingsNote("Names unused for 30 days expire. The bank holds at most 1,024 entries and keeps frequently viewed names when space is needed. Background lookups follow your connection and original-site settings.")}
                    }
                    SettingsPage.APPEARANCE -> {
                        item {SettingsIntro("Choose a look that feels comfortable.")}
                        item {SettingsGroup {
                            listOf("system" to "Use device theme","light" to "Light","dark" to "Dark").forEachIndexed {index,(id,name)->if(index>0)SettingsDivider();SettingOption(name,if(id=="system")"Match your Android appearance."else "",values.theme==id,"Theme $id"){prefs.theme=id;revision++;onChanged()}}
                        }}
                    }
                    SettingsPage.BACKUP -> {
                        item {StatusCard("Keep a private copy","Your backup is encrypted with a passphrase you choose.",Icons.Rounded.Lock)}
                        item {SettingsGroup {
                            SettingRow("Export encrypted backup",subtitle="Subscriptions, saved records, history and settings.",icon=Icons.Rounded.Upload,enabled=!backupBusy,onClick={password="";backupMode="export"})
                            SettingsDivider()
                            SettingRow("Restore from backup",subtitle="Choose an anti social backup file and enter its passphrase.",icon=Icons.Rounded.Download,enabled=!backupBusy,onClick={import.launch(arrayOf("application/octet-stream","*/*"))})
                        }}
                        if(backupBusy)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
                        item {SettingsNote("Offline media files stay on this device and are not included in the backup. Keep your passphrase somewhere safe; anti social cannot recover it.")}
                    }
                    SettingsPage.PORTABILITY -> Unit // Rendered as its own native screen above.
                }
            }
        }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    number?.let {setting->NumberDialog(setting,onDismiss={number=null}){setting.save(it);number=null}}
    if(resetCommunityIdentity)AlertDialog(onDismissRequest={resetCommunityIdentity=false},title={Text("Reset participation identity?")},text={Text("Your public comments stay online, but this device will lose the ability to edit or delete them. Your saved posts and subscriptions stay.")},confirmButton={TextButton(onClick={resetCommunityIdentity=false;work("Participation identity reset"){communityBusy=true;try {repo.commentAnywhere.forget();revision++}finally {communityBusy=false}}},modifier=Modifier.testTag("Confirm reset community identity")){Text("Reset identity")}},dismissButton={TextButton(onClick={resetCommunityIdentity=false}){Text("Cancel")}})
    choice?.let {setting->AlertDialog(onDismissRequest={choice=null},title={Text(setting.title)},text={LazyColumn(Modifier.heightIn(max=360.dp)) {
        items(setting.options,key={it.first}) {(value,label)->ChoiceOption(label,setting.value==value){choice=null;setting.choose(value)}}
        item {TextButton(onClick={choice=null;setting.custom()}){Text("Custom value…")}}
    }},confirmButton={},dismissButton={TextButton(onClick={choice=null}){Text("Cancel")}})}
    if(clearHistory)AlertDialog(onDismissRequest={clearHistory=false},title={Text("Clear reading history?")},text={Text("This removes opened links and retained history text from this device. Saved copies and subscriptions are kept.")},confirmButton={TextButton(onClick={clearHistory=false;work("Reading history cleared"){repo.library.clearHistory()}}){Text("Clear history")}},dismissButton={TextButton(onClick={clearHistory=false}){Text("Cancel")}})
    if(clearVideos)AlertDialog(onDismissRequest={clearVideos=false},title={Text("Remove downloaded videos?")},text={Text("Saved text, images, captured replies and collections stay. Selected videos can be downloaded again from Library.")},confirmButton={TextButton(onClick={clearVideos=false;work("Downloaded videos removed. Text and images kept."){repo.library.removeVideos()}},modifier=Modifier.testTag("Confirm remove downloaded videos")){Text("Remove videos")}},dismissButton={TextButton(onClick={clearVideos=false}){Text("Cancel")}})
    if(addList)AlertDialog(onDismissRequest={addList=false},title={Text("Add a filter list")},text={OutlinedTextField(customList,{customList=it;customListError=null},label={Text("HTTPS list URL")},isError=customListError!=null,supportingText={Text(customListError ?: "Use a public list without login credentials.")},modifier=Modifier.testTag("Custom filter URL"),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))},confirmButton={TextButton(onClick={if(prefs.addList(customList)){revision++;addList=false}else customListError="Enter a valid HTTPS URL without login credentials."}){Text("Add list")}},dismissButton={TextButton(onClick={addList=false}){Text("Cancel")}})
    if(backupMode!=null)AlertDialog(onDismissRequest={backupMode=null;password="";pendingImport=null},title={Text(if(backupMode=="export")"Encrypt your backup"else "Unlock your backup")},text={OutlinedTextField(password,{password=it},label={Text("Passphrase")},supportingText={Text("Use at least 8 characters.")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.testTag("Backup passphrase"))},confirmButton={TextButton(onClick={
        val mode=backupMode;val secret=password.toCharArray();password="";backupMode=null;backupBusy=true
        work {try {
            if(mode=="export"){val data=repo.exportBackup();pendingBackup=withContext(Dispatchers.Default){BackupCrypto.encrypt(data,secret)};export.launch("antisocial-backup.antisocial")}
            else {val data=pendingImport ?: error("Choose the backup again.");val decrypted=withContext(Dispatchers.Default){BackupCrypto.decrypt(data,secret)};repo.importBackup(decrypted);pendingImport=null;revision++;onSelected(prefs.selected);onChanged();snackbar.showSnackbar("Backup restored")}
        }catch(e: CancellationException){throw e}catch(e: Exception){throw IllegalStateException(if(mode=="import")"Could not unlock this backup. Check the passphrase and file."else "Could not prepare the backup. Try again.",e)}finally {secret.fill('\u0000');pendingImport=null;backupBusy=false}}
    },enabled=password.length>=8){Text("Continue")}},dismissButton={TextButton(onClick={backupMode=null;password="";pendingImport=null}){Text("Cancel")}})
}

@Composable private fun SettingsIntro(text: String){Text(text,fontSize=14.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=4.dp,vertical=8.dp))}
@Composable private fun SettingLabel(text: String){Text(text,Modifier.padding(start=4.dp,top=4.dp,bottom=6.dp),fontSize=11.sp,letterSpacing=1.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable private fun SettingsGroup(title: String?=null,content: @Composable ColumnScope.()->Unit) {
    Column {if(title!=null)SettingLabel(title);Surface(shape=RoundedCornerShape(22.dp),color=settingsCardColor(),modifier=Modifier.fillMaxWidth()){Column(content=content)}}
}
@Composable private fun settingsCardColor()=if(MaterialTheme.colorScheme.background.luminance()<.5f)MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
@Composable private fun SettingsDivider(){HorizontalDivider(Modifier.padding(start=18.dp,end=18.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))}
@Composable private fun SettingRow(title: String,modifier: Modifier=Modifier,subtitle: String="",icon: ImageVector?=null,value: String?=null,enabled: Boolean=true,onClick: (()->Unit)?=null) {
    Row(modifier.fillMaxWidth().heightIn(min=76.dp).then(if(onClick!=null)Modifier.clickable(enabled=enabled,onClick=onClick,role=Role.Button)else Modifier).padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
        if(icon!=null){Icon(icon,null,Modifier.size(23.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(16.dp))}
        Column(Modifier.weight(1f).padding(end=8.dp)) {
            Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium,color=if(enabled)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if(subtitle.isNotBlank())Text(subtitle,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
        }
        if(value!=null)Text(value,Modifier.widthIn(max=130.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
        if(onClick!=null){Spacer(Modifier.width(6.dp));Icon(Icons.Rounded.ChevronRight,null,Modifier.size(19.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@Composable private fun Destination(title: String,subtitle: String,icon: ImageVector,page: SettingsPage,vm: AntiSocialViewModel) {
    SettingRow(title,subtitle=subtitle,icon=icon,onClick={vm.settings(page)},modifier=Modifier.testTag("Settings destination ${page.name.lowercase()}"))
}
@Composable private fun SettingToggle(title: String,subtitle: String,value: Boolean,onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value=value,role=Role.Switch,onValueChange=onChange).testTag("Setting toggle $title").padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end=14.dp)){Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium);Text(subtitle,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))}
        Switch(checked=value,onCheckedChange=null)
    }
}
@Composable private fun SettingOption(title: String,subtitle: String,selected: Boolean,tag: String,onClick: ()->Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected=selected,role=Role.RadioButton,onClick=onClick).testTag(tag).padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end=12.dp)){Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium);if(subtitle.isNotBlank())Text(subtitle,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))}
        RadioButton(selected=selected,onClick=null)
    }
}
@Composable private fun PlatformChoice(platform: Platform,checked: Boolean,enabled: Boolean,onChange: ()->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value=checked,enabled=enabled,role=Role.Switch,onValueChange={onChange()}).testTag("Platform toggle ${platform.id}").padding(horizontal=18.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        PlatformMark(platform,25.dp)
        Column(Modifier.weight(1f).padding(start=16.dp,end=8.dp)) {
            Text(platform.label,fontWeight=FontWeight.Medium,fontSize=15.sp)
            platformScope(platform)?.let {Text(it,fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=3.dp))}
        }
        Switch(checked=checked,onCheckedChange=null,enabled=enabled)
    }
}
@Composable private fun StatusCard(title: String,subtitle: String,icon: ImageVector,onClick: (()->Unit)?=null) {
    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.secondaryContainer.copy(alpha=.65f),modifier=Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick,role=Role.Button)else Modifier)) {
        Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,Modifier.size(26.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start=16.dp)){Text(title,fontSize=16.sp,fontWeight=FontWeight.SemiBold);Text(subtitle,fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp))}
            if(onClick!=null)Icon(Icons.Rounded.ChevronRight,null,Modifier.size(19.dp))
        }
    }
}
@Composable private fun SettingsNote(text: String,icon: ImageVector=Icons.Rounded.Info) {
    Row(Modifier.padding(horizontal=4.dp,vertical=4.dp),verticalAlignment=Alignment.Top){Icon(icon,null,Modifier.padding(top=2.dp).size(17.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Text(text,Modifier.padding(start=10.dp),fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}
@Composable private fun ChoiceOption(label: String,selected: Boolean,onClick: ()->Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected=selected,role=Role.RadioButton,onClick=onClick).padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=selected,onClick=null);Text(label,Modifier.padding(start=8.dp))}
}
@Composable private fun NumberDialog(setting: NumberSetting,onDismiss: ()->Unit,onSave: (Int)->Unit) {
    var text by remember(setting.title){mutableStateOf(setting.value.toString())}
    val value=text.toIntOrNull();val valid=value!=null&&value in setting.range
    AlertDialog(onDismissRequest=onDismiss,title={Text(setting.title)},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(setting.description,fontSize=14.sp,lineHeight=21.sp)
        OutlinedTextField(text,{text=it.filter(Char::isDigit).take(5)},label={Text(setting.unit)},singleLine=true,isError=!valid,supportingText={Text(if(valid)"${setting.range.first}–${setting.range.last} ${setting.unit}"else "Enter a number from ${setting.range.first} to ${setting.range.last}.")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("Setting number input"))
    }},confirmButton={TextButton(onClick={if(valid)onSave(value!!)},enabled=valid){Text("Save")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
private fun intervalName(minutes: Int)=when {minutes==0->"Manual only";minutes%1440==0->"Every ${minutes/1440} day"+(if(minutes>1440)"s"else "");minutes%60==0->"Every ${minutes/60} hour"+(if(minutes>60)"s"else "");else->"Every $minutes minutes"}
private fun themeName(theme: String)=when(theme){"dark"->"Dark";"light"->"Light";else->"Use device theme"}
private fun grouped(value: Long)=java.text.NumberFormat.getIntegerInstance().format(value)
private fun filterTitle(source: FilterSource): String {
    if(source.name!=URI(source.url).host)return source.name
    val uri=URI(source.url);val parts=uri.path.trim('/').split('/')
    return if(uri.host=="raw.githubusercontent.com"&&parts.size>=4) {
        val name=if(parts.last()=="hosts")parts[parts.lastIndex-1]else parts.last().substringBeforeLast('.')
        if(parts[1].equals(name,true))name else parts[1]+" · "+name
    }else parts.last().substringBeforeLast('.').takeIf {it.isNotBlank()&&it!="hosts"} ?: uri.host
}
private fun size(bytes: Long): String=if(bytes>=1024*1024*1024)"%.1f GB".format(bytes.toDouble()/1024/1024/1024)else "%.1f MB".format(bytes.toDouble()/1024/1024)

@Composable fun PoolScreen(vm: AntiSocialViewModel) {
    val repo=vm.repo;val state by vm.screen.collectAsState();var rows by remember(state.platform){mutableStateOf(repo.pool.results(state.platform))};var busy by remember{mutableStateOf(false)}
    var capability by remember(state.platform) {mutableStateOf(state.sourceCapability)}
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    // Read local health only. Ordinary requests update this screen without
    // creating a repeating network probe or a background tracking signal.
    LaunchedEffect(state.platform) {
        while(isActive) {rows=repo.pool.results(state.platform);now=System.currentTimeMillis();delay(2_000)}
    }
    val sources=(state.sourceTarget?.let {repo.pool.sourcesFor(it,capability,repo.prefs.originalFallback)} ?: repo.pool.sources(state.platform,capability,repo.prefs.originalFallback)).distinctBy {it.key}
    LazyColumn(Modifier.fillMaxSize().testTag("Source pathways"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Text("${state.platform.label} sources",fontSize=26.sp,fontWeight=FontWeight.Bold);Text("Routes learn from normal reads. Tests check public content, not server pings.",Modifier.padding(top=8.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){Capability.entries.forEach {c->FilterChip(selected=capability==c,onClick={capability=c;rows=repo.pool.results(state.platform)},label={Text(c.name.lowercase().replaceFirstChar {it.uppercase()})},modifier=Modifier.testTag("Source capability ${c.name}"))}}}
        item {Button(onClick={busy=true;vm.launch {try {repo.awaitReady();val target=state.sourceTarget;if(target!=null)repo.pool.sweepTarget(target,capability,repo.prefs.originalFallback)else repo.pool.sweep(state.platform,capability,repo.prefs.originalFallback);rows=repo.pool.results(state.platform)}finally{busy=false}}},enabled=!busy&&sources.isNotEmpty()){Text(if(busy)"Testing public data…" else "Test ${capability.name.lowercase()}")}}
        if(sources.isEmpty())item {
            SettingsGroup {Column(Modifier.padding(18.dp)) {
                Text("No ${capability.name.lowercase()} pathways configured",fontWeight=FontWeight.Medium)
                Text(if(!repo.prefs.originalFallback)"Public original fallback is off. Other capabilities may have alternate routes." else "This reader does not currently provide a route for this capability.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
            }}
        }
        items(sources,key={it.key}) {source->
            SourceHealthCard(source,rows.filter {it.source.key==source.key&&it.capability==capability}.maxByOrNull {it.checkedAt},now)
        }
        item {Text("Public original data is tried only after alternate sources fail. Availability may differ between profiles, posts, comments, and media.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable internal fun SourceHealthCard(source: Source,health: ProbeResult?,now: Long) {
    val stale=health!=null&&now-health.checkedAt>=3_600_000
    val status=when(health?.status) {
        null,HealthStatus.UNKNOWN->"Not yet content-tested"
        HealthStatus.HEALTHY->if(stale)"Previously supplied public data" else "Public data verified"
        HealthStatus.EMPTY->"No usable public data returned"
        HealthStatus.CHALLENGE->"Blocked by a source challenge"
        HealthStatus.LOGIN_REQUIRED->"Source requires a login"
        HealthStatus.RATE_LIMITED->"Source is rate limited"
        HealthStatus.UNAVAILABLE->"Source unavailable"
        HealthStatus.UNSUPPORTED->"Capability not supported by this reader"
    }
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().testTag("Source route ${source.key}")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(URI(source.root).host ?: source.root,fontWeight=FontWeight.SemiBold)
            val routeLabel=if(source.role==SourceRole.ORIGINAL)"Public original"+when {
                source.frontend=="mastodon-rss"->" · RSS feed"
                source.platform==Platform.PIXELFED->" · Atom feed"
                source.platform in setOf(Platform.MASTODON,Platform.LEMMY,Platform.MISSKEY,Platform.GETTR,Platform.GAB,Platform.TRUTH_SOCIAL)->" · API"
                source.platform==Platform.TELEGRAM->" · channel preview"
                else->" · page data"
            }else source.frontend
            Text(routeLabel+(if(source.fallbackOnly)" · fallback" else ""),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status,fontSize=12.sp,color=if(health?.status==HealthStatus.HEALTHY&&!stale)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if(health!=null) {
                val age=(now-health.checkedAt).coerceAtLeast(0)/60_000
                val checked=when {age==0L->"Checked just now";age<60->"Checked $age min ago";age<1_440->"Checked ${age/60} h ago";else->"Checked ${age/1_440} d ago"}
                Text(checked+" · "+(if(health.tor)"Tor"else "Direct")+(if(health.status==HealthStatus.HEALTHY)" · ${health.latencyMs} ms"else ""),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(health.detail.isNotBlank())Text(health.detail,fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
