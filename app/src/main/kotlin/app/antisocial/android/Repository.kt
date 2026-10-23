package app.antisocial.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import androidx.room.Room
import androidx.room.withTransaction
import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class StorageUsage(val cache: Long=0,val saved: Long=0,val filters: Long=0,val videoCache: Long=0,val videoDownloads: Long=0)
data class FollowedPostSnapshot(val posts: List<Post>,val memberships: Map<String,Set<String>>,val generation: Long=0)
data class FeedRefreshState(val generation: Long=0,val running: Boolean=false,val requested: Set<String> = emptySet(),val failed: Set<String> = emptySet())
private data class PostWindow(val posts: List<Post>,val generation: Long)
/** Access-time/bookkeeping updates are not new source material. Comparing this
 * projection before decoding prevents a read dwell from reparsing the feed. */
private data class FeedPostRecord(val key:String,val payload:String,val publishedAt:Long?,val createdAt:Long)
data class FilterSource(val id: String,val url: String,val name: String,val enabled: Boolean=true)
data class FilterState(val ready: Boolean=false,val updating: Boolean=false,val rules: Int=0,val message: String="Preparing privacy filters",val lastCheckedAt:Long=0)
data class Backup(val schema: Int=2,val settings: JsonObject,val follows: List<FollowRow>,val bookmarks: List<BookmarkRow>,val history: List<HistoryRow>,val savedDetails: List<SavedDetailRow>?=null,val collections: List<LibraryCollectionRow>?=null,val collectionLinks: List<SavedCollectionRow>?=null,val retainedHistory: List<RetainedHistoryRow>?=null)
private data class PageRecord(val postKeys: List<String>,val next: String?,val profile: Profile?,val provenance: Provenance?,val collections: List<app.antisocial.core.Collection>,val accounts: List<Profile>?=null,val notice: String?=null,val continuationIssue: String?=null)

class AntiSocialApplication: Application() {
    lateinit var repo: Repository;private set
    override fun onCreate() {
        super.onCreate()
        if(android.os.Build.VERSION.SDK_INT>=28&&android.os.Process.isIsolated())return
        val process=runCatching {if(android.os.Build.VERSION.SDK_INT>=28)getProcessName()else
            getSystemService(android.app.ActivityManager::class.java).runningAppProcesses?.firstOrNull {it.pid==android.os.Process.myPid()}?.processName}.getOrNull()
        if(process==packageName)repo=Repository(this)
    }
}
class Repository(val context: Context) {
    internal val documentSandbox by lazy {ContentSandbox(context.applicationContext,DocumentSandboxService::class.java)}
    internal val contentSandbox by lazy {ContentSandbox(context.applicationContext)}
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val gson=Gson()
    val prefs=Preferences(context)
    val channels=PersonalChannelStore(prefs)
    val contentFilters=ContentFilterStore(prefs)
    val db=Room.databaseBuilder(context,AntiSocialDatabase::class.java,"antisocial.db").addMigrations(AntiSocialDatabase.MIGRATION_1_2,AntiSocialDatabase.MIGRATION_2_3).build()
    val dao=db.dao()
    private fun assetJson(name: String)=context.assets.open(name).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    val cleaner=UrlCleaner(assetJson("clean-links.json"))
    val routes=DarwinRoutes(assetJson("darwin-routes.json"),cleaner)
    val evidenceReader by lazy {ContextEvidenceReader(gateway,routes,pool,extract=documentSandbox::article)}
    private val sourceRecordStore=lazy {SourceRecordStore(this)}
    internal val sourceRecords get()=sourceRecordStore.value
    internal val articleReadingCache:ArticleReadingCache by lazy {ArticleReadingCache(File(context.cacheDir,"article-reading"),
        budget={minOf(64L*1024*1024,prefs.cacheMb.toLong()*1024*1024/4)},onChange={scope.launch {updateUsage()}})}
    private val originalLinks by lazy {OriginalLinks(routes,cleaner)}
    /** Attribution labels use original addresses; transport and stored
     * source records keep their actual retrieval URLs and provenance. */
    fun publicUrl(value: String,platform: Platform?=null): String {
        // Linkless RSS records use a fragment solely as a stable local key.
        // Shared/copied links still point at the real feed, not an invented article.
        val public=if(platform==Platform.RSS)value.toHttpUrlOrNull()?.takeIf {it.fragment?.startsWith("antisocial-entry=")==true}?.newBuilder()?.fragment(null)?.build()?.toString() ?: value else value
        return originalLinks.url(public,platform)
    }
    fun publicText(value: String,platform: Platform?=null): String=originalLinks.text(value,platform)
    val filters=MutableStateFlow(FilterState())
    val blocked=MutableStateFlow<List<Pair<String,String>>>(emptyList())
    val status=MutableStateFlow("")
    val usage=MutableStateFlow(StorageUsage())
    val videoCache=VideoCache(context,prefs)
    val mentionNames by lazy {MentionNameStore(this)}
    /** Public labels are disposable enrichment; a damaged cache must never
     * prevent the original page from being delivered or stored. */
    private fun rememberAccountNames(page: Page) {
        scope.launch {
            try {mentionNames.seed(page)}
            catch(e: CancellationException){throw e}
            catch(_: Exception){}
        }
    }
    private fun rememberAccountHints(hints:List<AccountNameHint>,epoch:Long=accountDiscoveryCache.epoch()) {
        if(hints.isEmpty())return
        scope.launch {
            try {if(epoch==accountDiscoveryCache.epoch())mentionNames.seed(hints)}
            catch(e:CancellationException){throw e}
            catch(_:Exception){}
        }
    }
    val sponsorBlock by lazy {SponsorBlockStore(this)}
    val youtube by lazy {YouTubeStore(this)}
    val cardWork=CardWork()
    val images by lazy {ImageLoader(this)}
    val aiMetadata by lazy {AiMetadataStore(scope,onWork={url->cardWork.begin(listOf(mediaWorkKey(url)),"Image information")})}
    private val mediaInfoStore=lazy {MediaInfoStore(this)}
    internal val mediaInfo get()=mediaInfoStore.value
    val ai by lazy {AiModelManager(this)}
    internal val contextReader by lazy {ContextReader(this)}
    val mediaTools by lazy {ContextMediaTools(this)}
    internal val languages by lazy {LanguageTools(this)}
    val library by lazy {LocalLibrary(this)}
    private val localDiscoveryStore=lazy {LocalDiscoveryStore(context)}
    internal val localDiscovery get()=localDiscoveryStore.value
    val outgoing by lazy {ShareRouteStore(this)}
    suspend fun resolveOutgoing(post: Post,editions: List<Post> = emptyList(),action: ShareLinkAction=ShareLinkAction.COPY): ShareRouteResult=outgoing.resolve(post,editions,action)
    suspend fun resolveOutgoing(url: String,platform: Platform?=null,action: ShareLinkAction=ShareLinkAction.OPEN): ShareRouteResult=outgoing.resolve(url,platform,action)
    val portability by lazy {ReaderPortability(this)}
    val commentAnywhere by lazy {AnywhereStore(this)}
    val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(true,"Privacy filters are starting")})
    val tor=TorController(context,gateway,scope)
    val pool=SourcePool(gateway,routes,PublicPathways(assetJson("public-pathways.json"))) {r->dao.putHealth(HealthRow("${r.source.key}|${r.capability}|${r.tor}",gson.toJson(r),r.checkedAt))}
    internal var directLoader: suspend (Target,(String)->Unit)->Page = {target,progress->pool.load(target,originalFallback=prefs.originalFallback,progress=progress)}
    internal var feedMemory=FeedMemoryStore(context,scope)
    internal var followLoader: suspend (Follow)->Page = {loadFollow(it)}
    internal var followPageLoader: suspend (Follow,Page)->Page = {follow,page->pool.continuePage(follow.target,page,prefs.originalFallback)}
    val feedRefresh=MutableStateFlow(FeedRefreshState())
    private val refreshMutex=Mutex()
    private val routingFile=File(context.cacheDir,"route-observations.json")
    private val routingWrites=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    internal val routeHeartbeat by lazy {RouteHeartbeatAndroid(this)}
    internal val archiveStore by lazy {ArchiveHistoryStore(this)}
    internal val recoveryStore by lazy {ArchiveRecoveryStore(this)}
    private val incomingLinks by lazy {IncomingLinks(routes,cleaner){url->gateway.registerOriginalUrl(url);gateway.resolve(url)}}
    val archiveHistory: StateFlow<Map<String,ArchivedVersion>> get()=archiveStore.history
    fun archiveVersion(post: Post)=archiveStore.version(post)
    suspend fun checkArchive(post: Post)=withContext(Dispatchers.IO){archiveStore.check(post)}
    private val dataDir=File(context.cacheDir,"content").apply {mkdirs()}
    private val filterDir=File(context.filesDir,"filters").apply {mkdirs()}
    internal val filterUpdates=FilterUpdateState(context)
    internal var filterDownload:suspend (FilterSource)->String={source->gateway.text(source.url,source.url,maxBytes=64L*1024*1024).text}
    private val savedDir=File(context.filesDir,"saved").apply {mkdirs()}
    private val filterMutex=Mutex()
    private val cleanupMutex=Mutex()
    internal val mediaMutex=Mutex()
    private val mediaKeys=KeyedMutex<String>()
    private val mediaPermits=Semaphore(4)
    private val cleanupQueued=java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var lastAutoCleanup=0L
    private val activeKeys=java.util.concurrent.ConcurrentHashMap<String,Int>()
    private var blocker: NativeFilter?=null
    private val ready=CompletableDeferred<Unit>()
    private var searchPreparation: Job?=null
    @Volatile private var transportRequested=false
    @Volatile private var imageSearchTransport=false
    @Volatile private var localSearchTransport=false
    private data class PreparedSearch(val provider: SearchProvider,val client: okhttp3.OkHttpClient,val at: Long)
    @Volatile private var preparedSearch: PreparedSearch?=null
    private val bundledSources by lazy {assetJson("filter-sources.json")["sources"].asJsonArray.map {o->gson.fromJson(o,FilterSource::class.java)}}
    val filterSources: List<FilterSource> get()=(bundledSources+prefs.customLists().mapNotNull {value->value.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.let {FilterSource(value,it.toString(),it.host)}}).distinctBy {it.url}
    private val retainedFollows=dao.follows().map {rows->rows.map {gson.fromJson(it.payload,Follow::class.java)}}.flowOn(Dispatchers.Default)
    val follows=retainedFollows.map {list->list.filter {ReaderScope.isFeedSource(it.target.platform)}}
    val legacyVideoFollows=retainedFollows.map {list->list.filterNot {ReaderScope.isFeedSource(it.target.platform)}}
    private fun readableRecord(post: Post): Boolean {
        if(post.recoveredArchive==null)return true
        if(!RecoveredArchives.valid(post))return false
        val recognized=runCatching {routes.recognize(post.canonicalUrl,post.platform)}.getOrNull() ?: return false
        return recognized.platform==post.platform&&recognized.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&ContentIdentity.key(post.platform,recognized.canonicalUrl)==ContentIdentity.key(post.platform,post.canonicalUrl)
    }
    val bookmarks=dao.bookmarks().map {rows->rows.map {gson.fromJson(it.payload,Post::class.java)}.filter(::readableRecord).also {registerPageOrigins(Page(it))} }.flowOn(Dispatchers.Default)
    val history=dao.history()
    init {
        scope.launch {
            follows.distinctUntilChanged().collect {list->
                val memory=feedMemory;memory.awaitReady();memory.syncFollows(list.filter(ReaderScope::active).map {it.id})
                routeHeartbeat.changed()
        mentionNames.changed()
            }
        }
        pool.onLearning={routingWrites.trySend(Unit)}
        pool.onRead={_,_,_->routeHeartbeat.afterRead()}
        pool.onHeartbeatChanged={routeHeartbeat.changed()}
        pool.onHeartbeatReservation={routeHeartbeat.persistReservation()}
        scope.launch {
            for(signal in routingWrites) {
                delay(800)
                while(routingWrites.tryReceive().isSuccess){}
                runCatching {atomic(routingFile,gson.toJson(pool.learning.snapshot()).toByteArray())}
            }
        }
        gateway.onBlocked={host,why->scope.launch(Dispatchers.Main.immediate){blocked.update { (listOf(host to why)+it).take(100) }}}
        pool.onProgress={message->scope.launch(Dispatchers.Main.immediate){status.value=message}}
        scope.launch {
            try {
                cleaner.prepare();LinkOpening.sync(context,prefs.selected);applyNetworkSettings(startTransport=false);initializeFilters()
                val revision=File(context.filesDir,"health-reader-version")
                val sameReader=runCatching {revision.readText()=="5"}.getOrDefault(false)
                pool.restore(dao.allHealth().mapNotNull {runCatching {gson.fromJson(it.payload,ProbeResult::class.java)}.getOrNull()}.filter {sameReader||it.status==HealthStatus.HEALTHY})
                if(routingFile.exists()&&routingFile.length()<=1024*1024)runCatching {
                    pool.learning.restore(gson.fromJson(routingFile.readText(),Array<RouteStats>::class.java).toList().map {row->
                        if(!sameReader&&row.source.platform==Platform.FACEBOOK&&row.capability==Capability.SEARCH)row.copy(cooldownUntil=0)else row
                    })
                }
                routeHeartbeat.restore()
                dao.allFollows().forEach {row->runCatching {gson.fromJson(row.payload,Follow::class.java).target}.getOrNull()?.let {target->routes.register(target);registerTargetOrigin(target)}}
                library.initialize()
                atomic(revision,"5".toByteArray());ready.complete(Unit);scheduleRefresh(context,prefs);cleanup()
            }
            catch(e: Exception){publish(filters,FilterState(message="Privacy filters could not load: ${e.message}"));ready.completeExceptionally(e)}
        }
    }
    private fun registerTargetOrigin(target: Target) {
        if(target.platform in setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY))gateway.registerOriginalUrl(target.canonicalUrl)
    }
    internal fun registerPageOrigins(page: Page) {
        outgoing.remember(page)
        page.posts.filter {it.platform!=Platform.RSS&&it.provenance?.source?.role==SourceRole.ORIGINAL}.forEach {post->
            gateway.registerOriginalUrl(post.canonicalUrl)
            (listOfNotNull(post.avatar)+post.media.flatMap {listOfNotNull(it.url,it.preview)}).forEach {gateway.registerOriginalUrl(it)}
        }
        if(page.provenance?.source?.platform!=Platform.RSS&&page.provenance?.source?.role==SourceRole.ORIGINAL)page.profile?.avatar?.let {gateway.registerOriginalUrl(it)}
    }
    private suspend fun <T> publish(flow: MutableStateFlow<T>,value: T)=withContext(Dispatchers.Main.immediate){flow.value=value}
    suspend fun awaitReady()=ready.await()
    suspend fun resolveIncoming(value: String,explicit: Boolean=false,hint: Platform?=null): IncomingLinkResult=withContext(Dispatchers.IO) {
        awaitReady();gateway.originalFallback=prefs.originalFallback
        incomingLinks.resolve(value,prefs.selected.toSet(),explicit,hint,prefs.originalFallback)
    }
    @Synchronized fun applyNetworkSettings(startTransport: Boolean=true) {
        gateway.originalFallback=prefs.originalFallback
        pool.searchProvider=prefs.searchProvider
        pool.awaitSearchConnection={try {withTimeout(120_000){tor.state.first {it.enabled&&it.ready};Unit}}catch(e: TimeoutCancellationException){throw SourceFailure(HealthStatus.UNAVAILABLE,"Tor did not connect. Search is paused; no direct fallback was used.")}}
        if(startTransport) {
            transportRequested=true
            tor.enable(prefs.tor,localSearchTransport||imageSearchTransport||prefs.originalFallback&&prefs.searchProvider.torOnly)
            prepareSearchConnection()
        }else if(!transportRequested)gateway.configure(NetworkPolicy(tor=prefs.tor))
        routeHeartbeat.changed()
    }
    /** An image-search session can need protected search transport even when the
     * selected text-search provider is one of the alternate routes. */
    fun imageSearchConnection(active: Boolean) {imageSearchTransport=active;applyNetworkSettings()}
    @Synchronized internal fun localSearchConnection(active:Boolean){if(localSearchTransport!=active){localSearchTransport=active;applyNetworkSettings()}}
    fun imageSearchSession(image: ImageSearchInput): ImageSearchSession {
        val search=PublicImageSearch(gateway)
        val engines=listOf(ImageSearchEngine.YANDEX,ImageSearchEngine.SAUCENAO,ImageSearchEngine.BING,ImageSearchEngine.GOOGLE_LENS)
        return ImageSearchSession(engines,discover={engine,cursor->
            gateway.originalFallback=prefs.originalFallback
            search.search(engine,image,cursor)
        },recognize={url->routes.recognize(cleaner.clean(url))},read={destination->open(destination)},requestTimeoutMs=45_000)
    }
    @Synchronized fun cancelSearchPreparation(){searchPreparation?.cancel();searchPreparation=null}
    /** No entered text is used. Do preparation while browsing/typing, and let an
     * actual submitted query cancel it instead of waiting behind it. */
    @Synchronized fun prepareSearchConnection() {
        searchPreparation?.cancel()
        val provider=prefs.searchProvider
        if(!prefs.originalFallback||!provider.torOnly){searchPreparation=null;preparedSearch=null;return}
        searchPreparation=scope.launch {
            try {
                awaitReady()
                withTimeout(120_000){tor.state.first {it.ready}}
                val client=gateway.client(NetworkRoute.TOR)
                val now=android.os.SystemClock.elapsedRealtime()
                val old=preparedSearch
                if(old?.provider==provider&&old.client===client&&now-old.at<60_000)return@launch
                gateway.preconnect(provider.root!!.trimEnd('/')+"/favicon.ico")
                preparedSearch=PreparedSearch(provider,client,android.os.SystemClock.elapsedRealtime())
            }catch(e: CancellationException){throw e}catch(_: Exception){/* Optional preparation never blocks a submitted search. */}
        }
    }
    suspend fun awaitConnection() {awaitReady();applyNetworkSettings();if(prefs.tor)withTimeout(120_000){while(!tor.state.value.ready)delay(1000)}}
    fun key(post: Post)=post.identityKey
    fun postTarget(post: Post): Target?=if(post.platform==Platform.RSS)runCatching {RssSources.itemTarget(post)}.getOrNull()else routes.recognize(post.canonicalUrl)
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun postWindow(platform: Platform?,group: String="",personId: String?=null,channel: PersonalChannel?=null): Flow<PostWindow> = combine(follows,feedRefresh) {list,refresh->
        val eligible=channel?.let {PersonalChannels.accounts(it,list).map(Follow::id).toSet()}
        list.filter {ReaderScope.active(it)&&(platform==null||it.target.platform==platform)&&(group.isBlank()||it.group==group)&&(personId==null||Subscriptions.id(it)==personId)&&(eligible==null||it.id in eligible)}.map {it.id} to refresh
    }.distinctUntilChanged().flatMapLatest {(ids,refresh)->
        if(refresh.running)emptyFlow()else if(ids.isEmpty())flowOf(PostWindow(emptyList(),refresh.generation)) else combine(ids.chunked(400).map {chunk->dao.followedPosts(chunk).map {rows->rows.map {FeedPostRecord(it.key,it.payload,it.publishedAt,it.createdAt)}}.distinctUntilChanged()}) {chunks->
            chunks.flatMap {it}.distinctBy {it.key}.sortedWith(compareByDescending<FeedPostRecord> {it.publishedAt ?: Long.MIN_VALUE}.thenByDescending {it.createdAt}).take(1000)
        }.distinctUntilChanged().map {rows->
            val posts=rows.mapNotNull {runCatching {gson.fromJson(it.payload,Post::class.java)}.getOrNull()}.filter(::readableRecord).also {registerPageOrigins(Page(it))}
            PostWindow(posts,refresh.generation)
        }
    }.flowOn(Dispatchers.Default)
    fun posts(platform: Platform?,group: String="",personId: String?=null,channel: PersonalChannel?=null): Flow<List<Post>> =postWindow(platform,group,personId,channel).map {it.posts}
    /** Coherent local content/source membership for cross-post matching. Source
     * context can identify linked opaque-ID feeds without trusting display names. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun followedSnapshot(platform: Platform?=null,group: String="",personId: String?=null,channel: PersonalChannel?=null): Flow<FollowedPostSnapshot> =
        postWindow(platform,group,personId,channel).flatMapLatest {(rows,generation)->
            val keys=rows.map(::key)
            if(keys.isEmpty())flowOf(FollowedPostSnapshot(rows,emptyMap(),generation))
            else combine(keys.chunked(400).map(dao::postMemberships)) {chunks->
                FollowedPostSnapshot(rows,chunks.flatMap {it}.groupBy {it.postKey}.mapValues {(_,members)->members.map {it.followId}.toSet()},generation)
            }
        }.flowOn(Dispatchers.Default)
    private fun pruneFilterFiles()=pruneFilterFiles(filterDir,filterSources.filter {it.id !in prefs.disabledLists()}.map {it.id})
    private suspend fun initializeFilters()=filterMutex.withLock {
        pruneFilterFiles()
        val started=android.os.SystemClock.elapsedRealtime()
        var origin="saved snapshot"
        val compiled=File(filterDir,"current.bin")
        val bundledMetadata=assetJson("network-rules.compiled.json")
        val savedMetadata=runCatching {JsonParser.parseString(File(filterDir,"current.json").readText()).asJsonObject}.getOrNull()
        val seedHashes=setOf(bundledMetadata["sha256"].asString)+(bundledMetadata["previousSeedSha256"]?.asJsonArray?.map {it.asString} ?: emptyList())
        val redundantSeed=savedMetadata?.get("sha256")?.asString in seedHashes
        var next=if(compiled.exists()&&!redundantSeed)runCatching {NativeFilter.restoreFile(compiled)}.getOrNull()else null
        val count=if(next!=null)runCatching {
            val metadata=savedMetadata ?: error("Missing snapshot metadata")
            val header=snapshotHeader(compiled)
            if(metadata.has("nativeHeader"))require(metadata["nativeHeader"].asString==header&&metadata["bytes"].asLong==compiled.length())
            else {
                require(metadata["sha256"].asString==digestFile(compiled))
                metadata.addProperty("nativeHeader",header);metadata.addProperty("bytes",compiled.length())
                atomic(File(filterDir,"current.json"),metadata.toString().toByteArray())
            }
            metadata["entries"].asInt.also {require(it>=0)}
        }.getOrDefault(-1)else assetJson("filter-seed-status.json")["rules"].asInt
        if(next==null)next=runCatching {
            // The signed APK contains the build-verified seed. A direct read of
            // its uncompressed asset avoids inflate/stream-growth copies. The
            // native format verifies its version, checksum and FlatBuffer.
            val engine=context.assets.openFd("network-rules.compiled").use {asset->
                require(asset.length in 1..128L*1024*1024)
                NativeFilter.restoreFileRange("/proc/self/fd/${asset.parcelFileDescriptor.fd}",asset.startOffset,asset.length)
            }
            engine.also {origin="bundled snapshot"}
        }.getOrNull()
        val engine=next ?: run {
            publish(filters,FilterState(message="Rebuilding privacy filters"))
            origin="text recovery"
            NativeFilter(context.assets.open("network-rules.seed").use {GZIPInputStream(it).bufferedReader().readText()}).also {saveFilterSnapshot(it,count)}
        }
        if(origin=="bundled snapshot"&&redundantSeed){compiled.delete();File(filterDir,"current.json").delete()}
        blocker=engine;gateway.replaceFilter(engine)
        android.util.Log.i("AntiSocialStartup","Protection ready from $origin in ${android.os.SystemClock.elapsedRealtime()-started} ms")
        publish(filters,FilterState(true,false,count,if(count>=0)"Darwin protection ready"else "Saved protection restored; update lists to refresh the entry count",filterUpdates.lastAttempt))
    }
    private fun saveFilterSnapshot(engine: NativeFilter,entries: Int) {
        val bytes=engine.serialize();atomic(File(filterDir,"current.bin"),bytes)
        val metadata=JsonObject().apply {addProperty("sha256",digest(bytes));addProperty("entries",entries);addProperty("nativeHeader",bytes.take(13).joinToString(""){"%02x".format(it)});addProperty("bytes",bytes.size)}
        atomic(File(filterDir,"current.json"),metadata.toString().toByteArray())
    }
    internal suspend fun refreshFilters(reason:FilterUpdateReason=FilterUpdateReason.USER):Boolean=withContext(Dispatchers.IO) {
        awaitReady()
        filterMutex.withLock {
        pruneFilterFiles()
        if(reason==FilterUpdateReason.AUTOMATIC&&filterUpdates.remaining()>0)return@withLock true
        publish(filters,filters.value.copy(updating=true,message="Refreshing protection"))
        var complete=false
        var cancelled=false
        var activeRules=filters.value.rules
        try {
            val rules=LinkedHashSet<String>();val errors=mutableListOf<String>();var characters=0L
            for(source in filterSources.filter {it.id !in prefs.disabledLists()}) {
                val file=File(filterDir,hash(source.id)+".txt")
                val text=try {
                    if(reason!=FilterUpdateReason.USER&&file.isFile&&System.currentTimeMillis()-file.lastModified() in 0 until FilterUpdateState.DAY)file.readText()
                    else {val text=filterDownload(source)
                        require(text.isNotBlank()&&'\u0000' !in text&&!text.trimStart('\uFEFF',' ','\t','\n','\r').startsWith('<')&&!text.take(1024).contains("<html",true))
                        atomic(file,text.toByteArray());text}
                }
                catch(e: CancellationException){throw e}catch(e: Exception) { if(file.exists()){errors+=source.name;file.readText()}else throw IllegalStateException("${source.name} unavailable; previous protection retained",e) }
                characters+=text.length;require(characters<=128L*1024*1024){"Filter source budget exceeded"}
                text.lineSequence().map {it.trim()}.filter {it.isNotEmpty()&&!it.startsWith('!')&&!it.startsWith('#')&&!it.startsWith('[')}.forEach {rules+=it;require(rules.size<=2_000_000){"Filter entry budget exceeded"}}
            }
            val next=NativeFilter(rules.joinToString("\n"))
            try {saveFilterSnapshot(next,rules.size)}catch(e:Exception){next.close();throw e}
            // Old handles remain valid for requests already holding the old snapshot.
            val old=blocker;blocker=next;gateway.replaceFilter(next)
            activeRules=rules.size
            scope.launch {delay(60_000);old?.close()}
            publish(filters,FilterState(true,false,rules.size,if(errors.isEmpty())"Filters updated" else "Updated; cached sources: ${errors.joinToString()}"))
            complete=errors.isEmpty()
        }catch(e: CancellationException){cancelled=true;throw e}
         catch(_: Exception){publish(filters,filters.value.copy(updating=false,message="Working protection kept · unavailable lists will retry"))}
        finally {
            withContext(NonCancellable) {
                if(!cancelled&&reason!=FilterUpdateReason.RETRY)filterUpdates.attempted()
                publish(filters,filters.value.copy(updating=false,rules=activeRules,lastCheckedAt=filterUpdates.lastAttempt,
                    message=if(cancelled)"Working protection kept · update will continue later"else filters.value.message))
            }
        }
        updateUsage()
        complete
    }}
    suspend fun store(page: Page,followId: String?=null,allowPurged: Boolean=false)=withContext(Dispatchers.IO) {
        rememberAccountNames(page)
        db.withTransaction {
        registerPageOrigins(page)
        val now=System.currentTimeMillis()
        for(item in page.posts) {
            val post=archiveStore.marry(item)
            val key=key(post);val seen=dao.seen(key)
            if(seen?.purged==true&&!allowPurged)continue
            val old=dao.post(key);val json=gson.toJson(post)
            dao.putPost(PostRow(key,post.platform.id,json,post.publishedAt,old?.createdAt ?: seen?.firstSeen ?: now,old?.lastViewedAt,json.toByteArray().size.toLong(),followId ?: old?.followId))
            dao.putSeen(SeenRow(key,post.platform.id,seen?.firstSeen ?: now,false))
        }
    }}
    fun pageKey(target: Target)="page:"+hash("${target.platform.id}:${target.kind}:${target.canonicalUrl}")
    suspend fun cachedPage(target: Target): Page?=withContext(Dispatchers.IO) {mediaMutex.withLock {
        val row=dao.media(pageKey(target)) ?: return@withLock null
        val file=File(row.path)
        val record=runCatching {gson.fromJson(file.readText(),PageRecord::class.java).also {it.postKeys.size;it.collections.size}}.getOrNull() ?: return@withLock null
        val posts=record.postKeys.chunked(400).flatMap {keys->dao.bookmarksByKeys(keys).map {it.key to it.payload}+dao.postsByKeys(keys).map {it.key to it.payload}}.associate {it}
        val ordered=record.postKeys.mapNotNull {key->posts[key]?.let {runCatching {gson.fromJson(it,Post::class.java)}.getOrNull()}}.filter(::readableRecord)
        val page=Page(ordered,record.next,record.profile,record.provenance,record.collections,record.accounts.orEmpty(),record.notice,record.continuationIssue)
        rememberAccountNames(page)
        registerTargetOrigin(target);registerPageOrigins(page)
        dao.putMedia(row.copy(lastViewedAt=System.currentTimeMillis()))
        page.copy(provenance=page.provenance?.copy(cached=true),posts=page.posts.map {it.copy(provenance=it.provenance?.copy(cached=true))})
    }}
    suspend fun rememberPage(target: Target,page: Page)=withContext(Dispatchers.IO) {
        rememberAccountNames(page)
        val bytes=gson.toJson(PageRecord(page.posts.map {key(it)},page.next,page.profile,page.provenance,page.collections,page.accounts,page.notice,page.continuationIssue)).toByteArray()
        if(bytes.size<=4*1024*1024)mediaMutex.withLock {
            val key=pageKey(target);val file=File(dataDir,key);atomic(file,bytes)
            val now=System.currentTimeMillis();dao.putMedia(MediaRow(key,file.path,bytes.size.toLong(),now,lastViewedAt=now))
        }
    }
    fun searchFeed(target: Target): SearchFeed {
        require(ReaderScope.isFeedSource(target.platform)){ReaderScope.VIDEO_ONLY}
        val scope=SearchScopes.from(target)
        val contained=scope!=null&&!SearchScopes.nativeSearch(target)
        val scanner=if(contained||scope?.kind in setOf(SearchScopeKind.ACCOUNT,SearchScopeKind.COMMUNITY)) {
            val container=SearchScopes.container(routes,target)
            ScopedFeedSearch(target.identity,load={previous->
                awaitReady();gateway.originalFallback=prefs.originalFallback;cancelSearchPreparation()
                if(previous==null)pool.load(container,originalFallback=prefs.originalFallback)else pool.continuePage(container,previous,prefs.originalFallback)
            })
        }else null
        val discovery=ScopedSearchDiscovery(native={previous->
            awaitReady();gateway.originalFallback=prefs.originalFallback;cancelSearchPreparation()
            if(contained)requireNotNull(scanner).next(previous)else if(previous==null)pool.load(target,originalFallback=prefs.originalFallback)else pool.continuePage(target,previous,prefs.originalFallback)
        },fallback=if(contained)null else scanner)
        return SearchFeed(target,discover={previous->
            discovery.next(previous)
        },read={destination->
            gateway.originalFallback=prefs.originalFallback
            pool.load(destination,originalFallback=prefs.originalFallback)
        },allowEmptyDiscoveryPages={contained||discovery.usingFallback},directory=if(scope!=null)null else ({
            awaitReady();gateway.originalFallback=prefs.originalFallback;cancelSearchPreparation()
            pool.searchDirectory(target,originalFallback=prefs.originalFallback).also {page->rememberAccountNames(page)}
        }))
    }
    private suspend fun discoverProfilePosts(target: Target,initial: Page): Page {
        if(!ProfilePosts.supported(target)||!prefs.originalFallback||initial.posts.isNotEmpty())return initial
        return try {withTimeout(45_000) {
            val discovery=pool.load(ProfilePosts.search(routes,target),originalFallback=prefs.originalFallback)
            ProfilePosts.resolve(target,initial,discovery,routes){post->pool.load(post,originalFallback=prefs.originalFallback)}
        }}catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;initial.copy(notice="Public posts could not be loaded on this connection.")}
        catch(_: Exception){initial.copy(notice="Public posts could not be loaded on this connection.")}
    }
    suspend fun open(target: Target,onPage: (Page)->Unit={},progress: (String)->Unit={}): Page {
        require(ReaderScope.canOpen(target)){ReaderScope.VIDEO_ONLY}
        // Public comment permalinks retain their authentic identity for sharing,
        // but a bounded public reader cannot promise that an arbitrary old reply
        // belongs to its current comment window. Always open its useful parent.
        if(target.platform==Platform.YOUTUBE&&YouTubeUrls.commentId(target.canonicalUrl)!=null) {
            val parent=YouTubeUrls.recognize(YouTubeUrls.canonical(requireNotNull(YouTubeUrls.videoId(target.canonicalUrl))))!!
            fun explain(page: Page)=page.copy(notice=listOfNotNull("This link points to a YouTube comment. Showing its video; open comments to browse available replies.",page.notice).distinct().joinToString("\n"))
            return explain(open(parent,onPage={onPage(explain(it))},progress=progress))
        }
        awaitReady();gateway.originalFallback=prefs.originalFallback
        if(target.platform==Platform.RSS&&target.kind==TargetKind.POST) {
            // Opening a feed entry reads the exact local item. It does not turn
            // a blog URL into a new scraping or original-site fallback request.
            val identity="${Platform.RSS.id}:${ContentIdentity.key(Platform.RSS,target.canonicalUrl)}"
            val row=dao.bookmarksByKeys(listOf(identity)).firstOrNull()?.payload ?: dao.post(identity)?.payload
            val post=row?.let {runCatching {gson.fromJson(it,Post::class.java)}.getOrNull()}?.takeIf {it.platform==Platform.RSS&&key(it)==identity}
                ?: throw SourceFailure(HealthStatus.UNAVAILABLE,"This feed entry is no longer cached. Refresh its feed or open a saved copy.")
            val linkless=target.canonicalUrl.toHttpUrlOrNull()?.fragment?.startsWith("antisocial-entry=")==true
            val page=Page(listOf(post),provenance=post.provenance?.copy(cached=true),notice=if(linkless)"Text supplied by the RSS/Atom feed. This entry has no article link; its source link opens the feed."else "Text supplied by the RSS/Atom feed. Use the original link for the full article.")
            viewed(post);rememberPage(target,page);onPage(page);return page
        }
        if(target.kind==TargetKind.SEARCH)cancelSearchPreparation()
        suspend fun saved(): Page? {
            val cached=(dao.allBookmarks().map {it.payload}+dao.platformPosts(target.platform.id).map {it.payload}).mapNotNull {runCatching {gson.fromJson(it,Post::class.java)}.getOrNull()}.filter(::readableRecord).filter {it.platform==target.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}.distinctBy {key(it)}
            return cached.takeIf {it.isNotEmpty()}?.let {Page(it,notice="Showing the saved copy")}
        }
        val direct=target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&ContentIdentity.key(target.platform,target.canonicalUrl).startsWith("post:")
        val initial=if(direct)DirectPostReader().read(target,
            live={directLoader(target,progress)},cached={saved()},recover={
                if(!prefs.archiveRecovery)throw SourceFailure(HealthStatus.UNSUPPORTED,"Archive recovery is turned off in Privacy & connection.")
                if(target.platform==Platform.YOUTUBE)youtube.recoverPage(target)else recoveryStore.recover(target)
            },onPartial={onPage(it)},progress={message->if(prefs.archiveRecovery)progress(message)}
        )else try {pool.load(target,originalFallback=prefs.originalFallback,progress=progress)}catch(e: CancellationException){throw e}catch(e: Exception){
            saved()?.let {cached->cached.copy(posts=cached.posts.map {it.copy(provenance=it.provenance?.copy(cached=true))})}
                ?: if(ProfilePosts.supported(target)&&prefs.originalFallback)Page(emptyList())else throw e
        }
        if(initial.profile!=null)onPage(initial)
        val page=discoverProfilePosts(target,initial)
        registerTargetOrigin(target);registerPageOrigins(page)
        if(page.posts.isEmpty()&&page.profile==null&&page.collections.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"This profile did not supply readable public posts on this connection")
        page.posts.forEach {lease(key(it),true)}
        try {
            store(page,allowPurged=true)
            rememberPage(target,page)
            if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD,TargetKind.MEDIA))page.posts.firstOrNull {ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}?.let {viewed(it)}
            scheduleCleanup()
        }finally {page.posts.forEach {lease(key(it),false)}}
        publish(status,"");return page
    }
    suspend fun follow(platform: Platform,value: String,group: String="",feedUrl: String?=null)=withContext(Dispatchers.IO) {
        require(ReaderScope.isFeedSource(platform)){ReaderScope.VIDEO_ONLY}
        if(platform==Platform.RSS){addRssSource(feedUrl ?: value,value,group);return@withContext}
        val target=routes.target(platform,value);require(ReaderScope.isFeedSource(target.platform)){ReaderScope.VIDEO_ONLY};val id="${target.platform.id}:${target.canonicalUrl}"
        val old=dao.allFollows().firstOrNull {it.id==id}?.let {gson.fromJson(it.payload,Follow::class.java)}
        val follow=old?.copy(group=group.ifBlank {old.group},feedUrl=feedUrl?.takeIf {it.isNotBlank()} ?: old.feedUrl) ?: Follow(id,target,value,group,feedUrl=feedUrl?.takeIf {it.isNotBlank()})
        dao.putFollow(FollowRow(id,target.platform.id,gson.toJson(follow)))
    }
    suspend fun addRssSource(url: String,title: String,folder: String=""): Follow=withContext(Dispatchers.IO) {
        awaitReady()
        val target=RssSources.target(url);val id=RssSources.followId(target.canonicalUrl)
        val name=title.trim().ifBlank {java.net.URI(target.canonicalUrl).host}
        require(name.length in 1..160&&!name.any(Char::isISOControl)&&folder.length<=256&&!folder.any(Char::isISOControl)){"Use a shorter feed title or folder."}
        val existing=dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}.firstOrNull {it.target.platform==Platform.RSS&&it.target.canonicalUrl==target.canonicalUrl}
        val follow=existing ?: Follow(id,target,name,folder.trim(),feedUrl=target.canonicalUrl)
        dao.putFollow(FollowRow(follow.id,Platform.RSS.id,gson.toJson(follow)))
        if(Platform.RSS !in prefs.selected)prefs.selected=prefs.selected+Platform.RSS
        feedMemory.syncFollows(dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}.filter(ReaderScope::active).map {it.id})
        follow
    }
    private val accountCacheStore=lazy {AccountDiscoveryCache(File(context.cacheDir,"account-links"))}
    internal val accountDiscoveryCache get()=accountCacheStore.value
    private val adjacentCacheStore=lazy {AdjacentStore(File(context.cacheDir,"adjacent-discovery"))}
    internal val adjacentStore get()=adjacentCacheStore.value
    private val participantBankStore=lazy {PublicParticipantBank(File(context.cacheDir,"public-participants.json"),routes)}
    internal val participantBank get()=participantBankStore.value
    internal suspend fun adjacentSession(profile:Profile,knownPage:Page?=null,force:Boolean=false):AdjacentRuntime=withContext(Dispatchers.IO) {
        require(AdjacentDiscovery.valid(profile.target)){"Open a public account, community or feed to discover connections"}
        awaitReady();gateway.originalFallback=prefs.originalFallback
        val policy=gateway.currentPolicy();val originals=prefs.originalFallback
        val key="v2|"+AdjacentDiscovery.key(profile.target)+"|"+policy+"|"+originals
        val cached=if(force)null else adjacentStore.get(key)
        val page=knownPage?.takeIf {it.profile?.target==profile.target} ?: cachedPage(profile.target)
        val observed=page?.let {AdjacentDiscovery.observed(profile,it,routes,it.provenance?.fetchedAt ?: System.currentTimeMillis())}.orEmpty()+participantBank.related(profile)
        val session=AdjacentSession(profile,routes,read={endpoint->
            if(policy!=gateway.currentPolicy()||originals!=prefs.originalFallback)throw SourceFailure(HealthStatus.UNKNOWN,"Connection settings changed; refresh discovery")
            if(policy.tor&&!policy.ready)throw SourceFailure(HealthStatus.UNKNOWN,"Waiting for the selected Tor connection")
            if(endpoint.source.role==SourceRole.ORIGINAL&&!originals)throw SourceFailure(HealthStatus.UNKNOWN,"Original-site fallback is disabled")
            if(endpoint.source.role==SourceRole.ORIGINAL)gateway.registerOriginalUrl(endpoint.source.root)
            val publisher=endpoint.source.frontend=="discovery-publisher"
            if(publisher)accountDiscoveryCache.website(policy.toString()+"|"+endpoint.url)?.let {warm->
                val declarations=warm.publisher;val source=warm.sourceUrl
                if(declarations!=null&&warm.checkedAt>0&&source!=null)return@AdjacentSession AdjacentDocument(
                    Payload("",source,"text/html",200),cachedPublisher=declarations,checkedAt=warm.checkedAt)
            }
            val result=gateway.extractorDocument(endpoint.url,sourceUrl=profile.target.canonicalUrl,maxBytes=PublicRelations.MAX_DOCUMENT.toLong(),
                headers=mapOf("Accept" to if(publisher)"text/html,application/rss+xml,application/atom+xml,application/json;q=0.8"else "application/json"),
                purpose=if(publisher)FetchPurpose.SUPPLEMENTAL else FetchPurpose.API)
            AdjacentDocument(Payload(result.text,result.finalUrl,result.headers.entries.firstOrNull {it.key.equals("Content-Type",true)}?.value?.firstOrNull().orEmpty(),result.status),result.headers)
        },learning=pool.learning,tor=policy.tor,timeoutMs=if(policy.tor)28_000 else 18_000,initial=observed,cached=cached?.snapshot,
            onLearning={pool.onLearning(pool.learning.snapshot())},maxRequests=64)
        AdjacentRuntime(session,key,adjacentStore.epoch(),policy,cached?.fresh==true)
    }
    internal suspend fun adjacentNext(runtime:AdjacentRuntime,onUpdate:(AdjacentSnapshot)->Unit):AdjacentSnapshot=withContext(Dispatchers.IO) {
        check(runtime.policy==gateway.currentPolicy()){"Connection settings changed; refresh discovery"}
        val result=gateway.boundedMediaRead(maxBytes=3L*1024*1024,maxRequests=8) {
            runtime.session.loadNext {snapshot->
                check(runtime.policy==gateway.currentPolicy()){"Connection settings changed; refresh discovery"}
                val names=snapshot.cards.map {it.profile}.filter {it.displayNameSupplied&&runtime.named.add(AdjacentDiscovery.key(it.target)+"|"+it.name)}
                if(names.isNotEmpty())rememberAccountNames(Page(emptyList(),accounts=names))
                onUpdate(snapshot)
            }
        }
        currentCoroutineContext().ensureActive()
        if(runtime.policy==gateway.currentPolicy())adjacentStore.put(runtime.key,result,runtime.epoch)
        result
    }
    private val accountWarmLock=Any()
    private val accountWarmAttempts=linkedMapOf<String,Long>()
    /** One idle, cancellable website read after a deliberate profile open. The
     * cache retains typed account links only, never the HTML page husk. */
    suspend fun prefetchAccountLinks(profile:Profile)=withContext(Dispatchers.IO) {
        awaitReady();gateway.originalFallback=prefs.originalFallback
        if(!AccountLinkage.valid(profile.target)||pool.hasForegroundReads||gateway.hasForegroundRequests||feedRefresh.value.running)return@withContext
        val website=AccountLinkage.websites(profile.links.orEmpty()+Subscriptions.urls(profile.description.take(16_384)),routes).firstOrNull() ?: return@withContext
        val policy=gateway.currentPolicy();val key=policy.toString()+"|"+website
        if(accountDiscoveryCache.website(key)!=null)return@withContext
        val now=System.currentTimeMillis()
        val admitted=synchronized(accountWarmLock) {
            val last=accountWarmAttempts[key] ?: 0L
            if(now-last<15*60_000L||accountWarmAttempts.values.any {now-it<10_000L})false else {
                accountWarmAttempts.remove(key);accountWarmAttempts[key]=now
                while(accountWarmAttempts.size>64)accountWarmAttempts.remove(accountWarmAttempts.keys.first())
                true
            }
        }
        if(!admitted)return@withContext
        val epoch=accountDiscoveryCache.epoch()
        try {withTimeout(if(policy.tor)5_000L else 3_000L) {
            gateway.deferredRead {gateway.boundedMediaRead(maxBytes=256*1024L,maxRequests=2) {
                val document=gateway.extractorDocument(website,sourceUrl=profile.target.canonicalUrl,maxBytes=256*1024L,headers=mapOf("Accept" to "text/html,application/xhtml+xml;q=0.9"),purpose=FetchPurpose.SUPPLEMENTAL)
                val payload=Payload(document.text,document.finalUrl,"text/html",document.status)
                val result=AccountLinkage.website(payload,routes,System.currentTimeMillis(),document.headers)
                currentCoroutineContext().ensureActive()
                if(policy==gateway.currentPolicy()) {
                    accountDiscoveryCache.rememberWebsite(key,result,epoch)
                    rememberAccountHints(result.nameHints,epoch)
                }
            }}
        }}catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e}catch(_:Exception){}
    }
    suspend fun discoverAccounts(target: Target,knownProfile: Profile?=null,matchingNames:Boolean=false,onUpdate:(AccountDiscovery)->Unit={}): AccountDiscovery=
        resolveAccountDiscovery(target,knownProfile,matchingNames,false,onUpdate)
    internal suspend fun discoverRoutingAccounts(target:Target,knownProfile:Profile?=null,onUpdate:(AccountDiscovery)->Unit={}):AccountDiscovery=
        resolveAccountDiscovery(target,knownProfile,false,true,onUpdate)
    private suspend fun resolveAccountDiscovery(target:Target,knownProfile:Profile?,matchingNames:Boolean,discoveryRouting:Boolean,onUpdate:(AccountDiscovery)->Unit):AccountDiscovery=withContext(Dispatchers.IO) {
        require(ReaderScope.isFeedSource(target.platform)||discoveryRouting&&target.platform==Platform.YOUTUBE){ReaderScope.VIDEO_ONLY}
        awaitReady();gateway.originalFallback=prefs.originalFallback
        if(!(if(discoveryRouting)AccountLinkage.validIdentity(target)else AccountLinkage.valid(target)))return@withContext AccountDiscovery(target.identity,listOf(AccountCandidate(target,"Account you opened")),"Cross-platform discovery is available for people and organizations.")
        val policy=gateway.currentPolicy()
        val platforms=if(matchingNames)(prefs.selected+PlatformOrder.popular).distinct()else emptyList()
        val cacheKey="v4|routing=$discoveryRouting|"+AccountLinkage.key(target,routes)+"|"+policy+"|"+prefs.originalFallback+"|"+platforms.joinToString {it.id}+"|"+hash(gson.toJson(knownProfile))
        val epoch=accountDiscoveryCache.epoch()
        val local=dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}
        // Cached observations keep their original checkedAt. Local subscription
        // membership is always recomputed rather than persisted in this cache.
        accountDiscoveryCache.get(cacheKey)?.let {cached->
            val owners=local.filter {AccountLinkage.same(it.target,target,routes)}.map(Subscriptions::id).toSet()
            val additions=local.filter {Subscriptions.id(it) in owners}.map {AccountCandidate(it.target,"Already linked in your subscriptions",AccountLinkKind.LOCAL,it.target.canonicalUrl,System.currentTimeMillis())}
            return@withContext cached.copy(candidates=(additions+cached.candidates.filter {it.kind!=AccountLinkKind.LOCAL}).distinctBy {AccountLinkage.key(it.target,routes)}.take(20)).also {
                rememberAccountHints(it.nameHints,epoch);onUpdate(it)
            }
        }
        val known=knownProfile ?: cachedPage(target)?.profile
        known?.takeIf {AccountLinkage.same(it.target,target,routes)}?.let {rememberAccountNames(Page(emptyList(),profile=it))}
        val engine=AccountDiscoveryEngine(routes,
            profile={account->
                val p=if(discoveryRouting&&account.platform==Platform.YOUTUBE) {
                    val page=discoveryBridge(PublicAccounts.from(account),false)
                    Profile(account,page.account.name.ifBlank {account.identity},page.description,links=page.links,displayNameSupplied=page.account.name.isNotBlank(),persistentId=page.account.id)
                }else pool.load(account,originalFallback=prefs.originalFallback,requestedCapability=Capability.PROFILE,progress={},profileNameOnly=!AccountLinkage.same(account,target,routes)).profile
                    ?.takeIf {AccountLinkage.same(it.target,account,routes)}
                p?.let {rememberAccountNames(Page(emptyList(),profile=it))};p
            },
            web={url,source->gateway.text(url,source,maxBytes=AccountLinkage.MAX_DOCUMENT.toLong(),headers=mapOf("Accept" to if(url.startsWith("https://www.wikidata.org/w/api.php")||url.startsWith("https://keybase.io/_/api/"))"application/json"else "text/html,application/xhtml+xml;q=0.9"))},
            laneTimeout=if(policy.tor)10_000 else 6_000,totalTimeout=if(policy.tor)30_000 else 20_000,
            cachedWebsite={url->accountDiscoveryCache.website(policy.toString()+"|"+url)},
            rememberWebsite={url,result->if(policy==gateway.currentPolicy())accountDiscoveryCache.rememberWebsite(policy.toString()+"|"+url,result,epoch)},discoveryRouting=discoveryRouting)
        val result=gateway.boundedMediaRead(maxBytes=4L*1024*1024,maxRequests=8) {
            engine.discover(target,known,local,platforms) {update->rememberAccountHints(update.nameHints,epoch);onUpdate(update)}
        }
        currentCoroutineContext().ensureActive()
        if(policy==gateway.currentPolicy())accountDiscoveryCache.put(cacheKey,result,epoch)
        result
    }
    suspend fun saveSubscription(name: String,targets: List<Target>,personId: String?=null)=withContext(Dispatchers.IO) {
        require(name.isNotBlank()&&targets.isNotEmpty()&&targets.size<=20)
        require(targets.all {ReaderScope.isFeedSource(it.platform)}){ReaderScope.VIDEO_ONLY}
        val accepted=db.withTransaction {
            val old=dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}
            // Keep one local subscription per canonical account, including existing feed settings.
            fun accountKey(target: Target)=if(AccountLinkage.valid(target))AccountLinkage.key(target,routes)else target.platform.id+":"+(target.canonicalUrl.toHttpUrlOrNull()?.toString() ?: target.canonicalUrl)
            val chosen=targets.distinctBy(::accountKey)
            val existing=old.filter {f->chosen.any {accountKey(it)==accountKey(f.target)}}
            val id=personId ?: existing.firstNotNullOfOrNull {it.personId} ?: "person:"+java.util.UUID.randomUUID()
            // Merging existing subscriptions retains every account in those subscriptions.
            val merging=existing.map(Subscriptions::id).toSet()+listOfNotNull(personId)
            val retained=old.filter {Subscriptions.id(it) in merging}
            val accounts=(retained.map {it.target}+chosen).distinctBy(::accountKey)
            require(accounts.size<=40){"A subscription can contain up to 40 accounts"}
            accounts.forEach {target->
                val previous=old.firstOrNull {accountKey(it.target)==accountKey(target)}
                val follow=(previous ?: Follow(if(target.platform==Platform.RSS)RssSources.followId(target.canonicalUrl)else "${target.platform.id}:${target.canonicalUrl}",target,target.identity,feedUrl=target.canonicalUrl.takeIf {target.platform==Platform.RSS})).copy(personId=id,personName=name.trim())
                dao.putFollow(FollowRow(follow.id,target.platform.id,gson.toJson(follow)))
            }
            accounts
        }
        try {mentionNames.linkAccounts(accepted)}catch(e:CancellationException){throw e}catch(_:Exception){}
    }
    internal suspend fun linkedSubscriptionAccounts(target:Target):List<Target> {
        val local=follows.first()
        val owners=local.filter {AccountLinkage.same(it.target,target,routes)}.map(Subscriptions::id).toSet()
        return local.filter {Subscriptions.id(it) in owners}.map {it.target}.take(40)
    }
    /** Remove only this local bundle's memberships. Saved material and source
     * records remain available; the same accounts can be subscribed again. */
    suspend fun unsubscribeAll(subscriptionId:String)=withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}.filter {Subscriptions.id(it)==subscriptionId}.forEach {dao.removeFollow(it.id)}
        }
    }
    suspend fun refreshSubscription(id: String) {refresh(personId=id)}
    suspend fun discussion(post: Post,previous: Page?=null,order: ReadingOrder=ReadingOrder.CHRONOLOGICAL): Page {
        awaitReady();gateway.originalFallback=prefs.originalFallback
        if(!pool.supportsComments(post.platform,prefs.originalFallback))throw SourceFailure(HealthStatus.UNSUPPORTED,"Public comments are not available from the current ${post.platform.label} readers.")
        val target=Discussions.target(post,routes,order==ReadingOrder.SLOP) ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"This post has no readable discussion address")
        val page=if(previous==null)pool.load(target,originalFallback=prefs.originalFallback,requestedCapability=Capability.COMMENTS)else pool.continuePage(target,previous,prefs.originalFallback,requestedCapability=Capability.COMMENTS)
        val graphEpoch=participantBank.epoch()
        scope.launch(Dispatchers.IO){participantBank.observe(post,page,graphEpoch)}
        store(page,allowPurged=true);rememberPage(target,page);scheduleCleanup()
        return page
    }
    private suspend fun loadFollow(follow: Follow): Page =
        if(follow.feedUrl!=null)pool.externalFeed(follow)else try {pool.load(follow.target,feed=true,originalFallback=prefs.originalFallback)}catch(e: CancellationException){throw e}catch(e: Exception){
            if(!ProfilePosts.supported(follow.target)||!prefs.originalFallback)throw e
            discoverProfilePosts(follow.target,Page(emptyList())).also {if(it.posts.isEmpty())throw e}
        }
    /** The scheduled connection wait is a pending check, never an old success.
     * It holds no refresh lock, so a foreground read can still proceed. */
    suspend fun refreshWhenConnected() {
        awaitReady();feedMemory.awaitReady()
        val ids=dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}.filter(ReaderScope::active).map {it.id}
        val token=feedMemory.begin(ids)
        try {awaitConnection();refresh()}
        finally {withContext(NonCancellable) {
            ids.forEach {feedMemory.fail(it,token,"Connection check did not complete; subscription still pending")}
            feedMemory.flush()
        }}
    }
    suspend fun refresh(platform: Platform?=null,personId: String?=null,group: String="",channel: PersonalChannel?=null)=refreshMutex.withLock {
        awaitReady()
        feedMemory.awaitReady()
        gateway.originalFallback=prefs.originalFallback
        val all=dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}
        feedMemory.syncFollows(all.filter(ReaderScope::active).map {it.id})
        val channelAccounts=channel?.let {PersonalChannels.accounts(it,all).map(Follow::id).toSet()}
        val list=all.filter {ReaderScope.active(it)&&(platform==null||it.target.platform==platform)&&(personId==null||Subscriptions.id(it)==personId)&&(group.isBlank()||it.group==group)&&(channelAccounts==null||it.id in channelAccounts)}
        val token=feedMemory.begin(list.map {it.id})
        val unfinished=java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {addAll(list.map {it.id})}
        val failures=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val generation=feedRefresh.value.generation+1
        feedRefresh.value=FeedRefreshState(generation,true,list.map {it.id}.toSet())
        try {
            if(prefs.wifiOnly) {
                val cm=context.getSystemService(ConnectivityManager::class.java);val caps=cm.getNetworkCapabilities(cm.activeNetwork)
                if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)!=true){
                    unfinished.forEach {feedMemory.fail(it,token,"Waiting for Wi-Fi")};unfinished.clear()
                    publish(status,"Waiting for Wi-Fi; subscriptions remain pending");return@withLock
                }
            }
            val permits=Semaphore(3)
            coroutineScope {list.map {follow->async {permits.withPermit {
                currentCoroutineContext().ensureActive()
                publish(status,"Updating ${follow.name}")
                try {
                    val known=dao.followedPosts(listOf(follow.id)).first()
                    val newest=known.mapNotNull {it.publishedAt}.maxOrNull()
                    val cachedHead=known.filter {newest==null||it.publishedAt==newest}.take(8).map {it.key}
                    // Failed partial windows must not redefine the frontier: a
                    // retry still has to bridge the last unfinished gap.
                    feedMemory.catchUpHead(follow.id,token,cachedHead)
                    val head=feedMemory.refresh.value[follow.id]?.catchUpHead.orEmpty().toSet()
                    val fetched=withTimeout(if(prefs.tor)90_000L else 45_000L) {FeedCatchUp().collect(follow.target.platform,head,first={followLoader(follow)},next={followPageLoader(follow,it)})}
                    if(fetched.page.posts.isNotEmpty())store(fetched.page,follow.id)
                    val reason=fetched.pendingReason
                    if(reason==null)feedMemory.success(follow.id,token,System.currentTimeMillis())
                    else {feedMemory.fail(follow.id,token,reason);failures+=follow.id}
                }catch(e: TimeoutCancellationException){
                    currentCoroutineContext().ensureActive()
                    feedMemory.fail(follow.id,token,"Catch-up timed out; subscription still pending");failures+=follow.id
                }catch(e: CancellationException){throw e}catch(e: Exception){
                    feedMemory.fail(follow.id,token,e.message?.take(180) ?: "Subscription unavailable");failures+=follow.id
                }
                unfinished.remove(follow.id)
            }}}.awaitAll()}
            cleanup();publish(status,if(failures.isNotEmpty())"${failures.size} sources pending; saved feed retained" else "")
        }finally {
            withContext(NonCancellable) {
                try {
                    unfinished.forEach {feedMemory.fail(it,token,"Refresh interrupted; subscription still pending")}
                    feedMemory.flush()
                }finally {
                    // A local persistence failure must not leave all subscribed
                    // data flows permanently suspended behind a running flag.
                    feedRefresh.value=FeedRefreshState(generation,false,list.map {it.id}.toSet(),failures+unfinished)
                }
            }
        }
    }
    suspend fun viewed(post: Post) {val now=System.currentTimeMillis();dao.touchPost(key(post),now);dao.putHistory(HistoryRow(key(post),post.platform.id,post.canonicalUrl,post.title.ifBlank {post.body.take(90)},now));library.recordHistory(post,now)}
    suspend fun bookmark(post: Post,save: Boolean) {
        archiveStore.mutations.withLock {
            if(save) {
                val married=captureAiMetadata(archiveStore.marry(post))
                if(dao.bookmarksByKeys(listOf(key(post))).isEmpty())dao.putBookmark(BookmarkRow(key(post),post.platform.id,gson.toJson(married),System.currentTimeMillis()))
                library.indexBookmark(post)
            }else {
                library.cancel(key(post));db.withTransaction {dao.removeBookmark(key(post));dao.deleteDocuments(key(post),"saved")};library.notifyChanged()
                val retained=library.retainedMediaKeys()
                mediaMutex.withLock {val unused=dao.allMedia().filter {it.saved&&it.key !in retained};unused.forEach {File(it.path).delete()};if(unused.isNotEmpty())dao.removeMedia(unused.map {it.key})}
            }
        }
        updateUsage()
    }
    fun lease(key: String,active: Boolean) {if(active)activeKeys.merge(key,1){old,new->old+new}else activeKeys.computeIfPresent(key){_,count->(count-1).takeIf {it>0}}}
    private data class OfflineIntent(val owner: String,val marker: String,val token: String)
    private suspend fun offlineIntent(post: Post): OfflineIntent? {
        val marker=dao.media(archiveStore.offlineKey(post))?.takeIf {it.saved} ?: return null
        val token=runCatching {File(marker.path).takeIf {it.length()<=128}?.readText()}.getOrNull() ?: return null
        return OfflineIntent(key(post),marker.key,token)
    }
    private suspend fun validIntent(intent: OfflineIntent): Boolean {
        if(dao.bookmarksByKeys(listOf(intent.owner)).isEmpty())return false
        val row=dao.media(intent.marker)?.takeIf {it.saved} ?: return false
        return runCatching {File(row.path).length()<=128&&File(row.path).readText()==intent.token}.getOrDefault(false)
    }
    private suspend fun <T> mediaCommit(intent: OfflineIntent?,action: suspend ()->T): T {
        return if(intent==null)mediaMutex.withLock {action()}else archiveStore.mutations.withLock {
            if(!validIntent(intent))throw CancellationException("Offline save was removed")
            mediaMutex.withLock {action()}
        }
    }
    suspend fun media(url: String,sourceUrl: String,save: Boolean=false): File=cacheMedia(url,sourceUrl,save,null)
    private suspend fun cacheMedia(url: String,sourceUrl: String,save: Boolean,intent: OfflineIntent?): File = withContext(Dispatchers.IO) {
        val key=hash(url)
        mediaKeys.withKey(key) {
            val cached=mediaCommit(intent) {
                val existing=dao.media(key)
                if(existing==null||!File(existing.path).isFile)null else {
                    val target=if(save&&!existing.saved)File(savedDir,key)else File(existing.path)
                    if(target.path!=existing.path)File(existing.path).copyTo(target,true)
                    dao.putMedia(existing.copy(path=target.path,saved=existing.saved||save,lastViewedAt=System.currentTimeMillis()))
                    // A visible reader may still be decoding the former path.
                    if(target.path!=existing.path&&!activeKeys.containsKey(key))File(existing.path).delete()
                    target
                }
            }
            if(cached!=null)return@withKey cached
            awaitReady()
            val payload=mediaPermits.withPermit {gateway.mediaPayload(url,sourceUrl)}
            val bytes=payload.bytes
            mediaCommit(intent) {
                val file=File(if(save)savedDir else dataDir,key);atomic(file,bytes)
                val now=System.currentTimeMillis()
                dao.putMedia(MediaRow(key,file.path,bytes.size.toLong(),now,lastViewedAt=now,saved=save))
                mediaInfo.acquired(url,payload)
                scheduleCleanup();file
            }
        }
    }
    suspend fun savedMedia(url: String): File?=dao.media(hash(url))?.takeIf {it.saved}?.let {File(it.path).takeIf {f->f.isFile}}
    /** Already available bytes only; provenance inspection never causes a fetch. */
    internal suspend fun availableMedia(url: String): File?=dao.media(hash(url))?.let {File(it.path).takeIf {f->f.isFile}}
    suspend fun saveOffline(post: Post)=withContext(Dispatchers.IO) {
        bookmark(post,true)
        // An intent token prevents a late download reviving an unsaved pair.
        val intent=archiveStore.mutations.withLock {
            if(dao.bookmarksByKeys(listOf(key(post))).isEmpty())return@withLock null
            val markerKey=archiveStore.offlineKey(post);val marker=File(savedDir,markerKey);val token=java.util.UUID.randomUUID().toString()
            mediaMutex.withLock {atomic(marker,token.toByteArray());dao.putMedia(MediaRow(markerKey,marker.path,token.length.toLong(),System.currentTimeMillis(),saved=true))}
            OfflineIntent(key(post),markerKey,token)
        } ?: return@withContext
        val married=archiveStore.marry(post)
        var failed=savePostMedia(married,intent)
        archiveVersion(married)?.let {failed+=savePostMedia(it.displayPost(married),intent)}
        publish(status,if(failed>0)"Text saved; $failed media files unavailable"else if(married.media.any {it.type!="image"})"Text and images saved offline; choose videos in Save a copy"else "Text and media saved offline")
        updateUsage()
    }
    internal suspend fun saveArchivedMedia(current: Post,version: ArchivedVersion) {
        val intent=archiveStore.mutations.withLock {
            val saved=dao.bookmarksByKeys(listOf(key(current))).firstOrNull()?.let {gson.fromJson(it.payload,Post::class.java)}
            if(saved==null||saved.archivedVersion!=version||archiveVersion(saved)!=version)null else offlineIntent(saved)
        } ?: return
        val failed=savePostMedia(version.displayPost(current),intent)
        if(failed>0)publish(status,"Archived text saved; some historic media is unavailable")
        updateUsage()
    }
    private suspend fun savePostMedia(post: Post,intent: OfflineIntent): Int {
        var failed=0
        // Legacy quick-offline callers keep images. Video downloads require an explicit library selection.
        val entries=(post.media.filter {it.type=="image"}+post.media.mapNotNull {it.preview?.let(::Media)}+post.avatar?.let {listOf(Media(it))}.orEmpty()).distinctBy {it.url}
        for(m in entries)try {
            if(m.type=="image")cacheMedia(m.url,post.contentSourceUrl,true,intent)
            else {
                val key=hash(m.url)
                mediaKeys.withKey(key) {if(savedMedia(m.url)==null) {
                    awaitReady()
                    val file=File(savedDir,key);val temp=File(savedDir,"$key.part")
                    try {val bytes=mediaPermits.withPermit {
                            archiveStore.mutations.withLock {if(!validIntent(intent))throw CancellationException("Offline save was removed")}
                            gateway.download(m.url,post.contentSourceUrl,temp)
                        }
                        mediaCommit(intent) {check(temp.renameTo(file));val now=System.currentTimeMillis();dao.putMedia(MediaRow(key,file.path,bytes,now,now,true))}
                    }finally {temp.delete()}
                }}
            }
            m.preview?.let {cacheMedia(it,post.contentSourceUrl,true,intent)}
        }catch(e: CancellationException){throw e}catch(_: Exception){failed++}
        return failed
    }
    /** Shared adaptive upkeep; full source sweeps remain explicit source-details work. */
    suspend fun probeSelected()=routeHeartbeat.run()
    fun scheduleCleanup() {
        if(android.os.SystemClock.elapsedRealtime()-lastAutoCleanup<30_000||!cleanupQueued.compareAndSet(false,true))return
        scope.launch {try {delay(1000);cleanup();lastAutoCleanup=android.os.SystemClock.elapsedRealtime()}finally {cleanupQueued.set(false)}}
    }
    suspend fun cleanup(clearAll: Boolean=false)=withContext(Dispatchers.IO) {
        // Never sweep an in-progress writer's temporary files or delay ordinary
        // cache eviction behind a network update. The next pass can prune them.
        if(filterMutex.tryLock())try {pruneFilterFiles()}finally {filterMutex.unlock()}
        if(accountCacheStore.isInitialized())accountDiscoveryCache.cleanup(clearAll)
        adjacentStore.cleanup(clearAll)
        participantBank.cleanup(clearAll)
        articleReadingCache.cleanup(clearAll)
        LocalMenuReader.cleanup(context.cacheDir,clearAll)
        ContentSandbox.cleanup(context.cacheDir,clearAll)
        if(clearAll)evidenceReader.clearArticleMemory()
        if(sourceRecordStore.isInitialized())sourceRecords.cleanup(clearAll)
        mediaInfo.cleanup(clearAll)
        if(localDiscoveryStore.isInitialized())localDiscovery.cleanup(clearAll)
        contextReader.cleanup(clearAll)
        if(clearAll)mentionNames.clear()else mentionNames.cleanup()
        if(clearAll)outgoing.clear()
        if(clearAll)commentAnywhere.clear()
        if(clearAll)aiMetadata.clear()else aiMetadata.cleanup()
        cleanupMutex.withLock {mediaMutex.withLock {
        if(clearAll)images.clear()
        val now=System.currentTimeMillis();val posts=dao.postCacheRows();val media=dao.allMedia()
        videoCache.cleanup(now,clearAll)
        if(clearAll){sponsorBlock.clear();youtube.clear()}else sponsorBlock.cleanup(now)
        val entries=posts.map {CacheEntry(it.key,it.bytes,it.createdAt,it.lastViewedAt,false,activeKeys.containsKey(it.key))}+media.map {CacheEntry(it.key,it.bytes,it.createdAt,it.lastViewedAt,it.saved,activeKeys.containsKey(it.key))}
        val free=StatFs(context.filesDir.path).availableBytes
        val budget=if(clearAll)0 else (minOf(prefs.cacheMb.toLong()*1024*1024,(free-256L*1024*1024).coerceAtLeast(0))-mentionNames.bytes()-contextReader.store.logicalBytes(false)-contextReader.temporaryBytes()-articleReadingCache.bytes()-(if(localDiscoveryStore.isInitialized())localDiscovery.bytes()else 0)-(if(sourceRecordStore.isInitialized())sourceRecords.bytes()else 0)-mediaInfo.bytes()-(if(accountCacheStore.isInitialized())accountDiscoveryCache.bytes()else 0)-adjacentStore.bytes()-participantBank.bytes()).coerceAtLeast(0)
        val evict=CachePolicy.eviction(entries,now,budget,prefs.unseenDays,prefs.viewedDays)
        val postKeys=posts.filter {it.key in evict}.map {it.key};val mediaRows=media.filter {it.key in evict}
        if(postKeys.isNotEmpty()){db.withTransaction {dao.markPurged(postKeys);dao.removePosts(postKeys)}}
        contextReader.cleanup(clearAll)
        if(mediaRows.isNotEmpty()){dao.removeMedia(mediaRows.map {it.key});mediaRows.forEach {File(it.path).delete()}}
        dao.pruneSeen(now-prefs.historyDays*CachePolicy.DAY);dao.pruneHistory(now-prefs.historyDays*CachePolicy.DAY);dao.pruneHealth(now-30*CachePolicy.DAY);library.pruneHistory()
        val known=dao.allMedia().map {it.path}.toSet();dataDir.listFiles()?.filter {it.path !in known&&!activeKeys.containsKey(it.name)}?.forEach {it.delete()}
        updateUsage()
    }}}
    suspend fun updateUsage()=withContext(Dispatchers.IO) {
        val savedBytes=(if(localDiscoveryStore.isInitialized())localDiscovery.bytes(true)else 0)+dao.bookmarkBytes()+dao.savedDetailBytes()+dao.mediaBytes(true)+contextReader.store.logicalBytes(true)
        val videoKeys=dao.allSavedAssets().filter {it.type!="image"}.map {it.assetKey}.toSet()+dao.allBookmarks().mapNotNull {runCatching {gson.fromJson(it.payload,Post::class.java)}.getOrNull()}.flatMap {p->(p.media+p.archivedVersion?.snapshot?.media.orEmpty()).filter {it.type!="image"}.map {hash(it.url)}}
        val videoBytes=dao.allMedia().filter {it.saved&&it.key in videoKeys}.sumOf {it.bytes}
        publish(usage,StorageUsage((if(localDiscoveryStore.isInitialized())localDiscovery.bytes()else 0)+(if(sourceRecordStore.isInitialized())sourceRecords.bytes()else 0)+mediaInfo.bytes()+dao.postBytes()+dao.mediaBytes(false)+mentionNames.bytes()+contextReader.store.logicalBytes(false)+contextReader.temporaryBytes()+articleReadingCache.bytes()+(if(accountCacheStore.isInitialized())accountDiscoveryCache.bytes()else 0)+adjacentStore.bytes()+participantBank.bytes(),savedBytes,filterDir.listFiles()?.sumOf {it.length()} ?: 0,videoCache.bytes(),videoBytes))
    }
    suspend fun exportBackup(): ByteArray=withContext(Dispatchers.IO) {val backup=Backup(settings=JsonParser.parseString(prefs.export().toString()).asJsonObject,follows=dao.allFollows(),bookmarks=dao.allBookmarks(),history=dao.allHistory(),savedDetails=dao.allSavedDetails(),collections=dao.allCollections(),collectionLinks=dao.allCollectionLinks(),retainedHistory=dao.allRetainedHistory());gson.toJson(backup).toByteArray()}
    suspend fun importBackup(bytes: ByteArray) {
        require(bytes.size<=16*1024*1024)
        val backup=gson.fromJson(String(bytes),Backup::class.java);require(backup.schema in 1..2)
        val settings=org.json.JSONObject(backup.settings.toString());prefs.validate(settings)
        backup.bookmarks.forEach {row->
            val post=requireNotNull(gson.fromJson(row.payload,Post::class.java)) {"Invalid saved post"}
            val url=requireNotNull(post.canonicalUrl.toHttpUrlOrNull()) {"Invalid original URL"}
            require(url.username.isEmpty()&&url.password.isEmpty()) {"Source credentials are not supported"}
            require(readableRecord(post)&&row.key==key(post)) {"The backup contains an invalid saved copy"}
        }
        val roots=backup.bookmarks.associateBy {it.key}
        val details=backup.savedDetails.orEmpty().map {row->
            val root=roots[row.key]?.let {gson.fromJson(it.payload,Post::class.java)} ?: error("Saved thread has no post")
            val detail=requireNotNull(gson.fromJson(row.payload,SavedCopyDetails::class.java))
            require(detail.threads.size<=4096&&detail.threads.sumOf {it.count+1}<=4096)
            detail.threads.forEach {thread->thread.commentAnywhere?.let {library.normalizedCommunity(thread.root,it)}}
            detail.threads.flatMap {listOf(it.root)+it.replies}.forEach {p->val url=p.canonicalUrl.toHttpUrlOrNull();require(readableRecord(p)&&!p.preview&&url!=null&&url.username.isEmpty()&&url.password.isEmpty()&&p.body.length+p.title.length<=1024*1024){"Invalid captured public reply"}}
            val fusion=library.normalizedFusionRoots(root,detail.fusionRoots.orEmpty())
            val allowed=library.availableAssets(root,detail.threads,fusion).associateBy {it.key}
            require(detail.selectedAssets.all {allowed[it.key]==it}){"Invalid selected attachment"}
            row.copy(payload=gson.toJson(detail.copy(token=java.util.UUID.randomUUID().toString(),fusionRoots=fusion.takeIf {it.size>1})))
        }
        val collections=backup.collections.orEmpty();require(collections.map {it.id}.distinct().size==collections.size)
        require(collections.all {it.id.isNotBlank()&&it.id.length<=128&&it.name.isNotBlank()&&it.name.length<=80})
        require(collections.map {it.name.map {c->if(c in 'A'..'Z')c.lowercaseChar()else c}.joinToString("")}.distinct().size==collections.size)
        collections.forEach {c->val existing=dao.collectionNamed(c.name);require(existing==null||existing.id==c.id){"A collection with this name already exists"}}
        val links=backup.collectionLinks.orEmpty();require(links.all {it.ownerKey in roots&&it.collectionId in collections.map {c->c.id}})
        val retained=backup.retainedHistory.orEmpty();require(retained.size<=2000)
        retained.forEach {row->val post=requireNotNull(gson.fromJson(row.payload,Post::class.java));require(row.key==key(post)&&readableRecord(post)&&post.canonicalUrl.toHttpUrlOrNull()!=null&&row.payload.toByteArray().size<=1024*1024)}
        archiveStore.mutations.withLock {
            db.withTransaction {
                backup.follows.forEach {dao.putFollow(it)};backup.bookmarks.forEach {
                    library.cancel(it.key);dao.putBookmark(it);dao.deleteDocuments(it.key,"saved")
                    if(details.none {d->d.key==it.key}){dao.deleteSavedAssets(it.key);db.openHelper.writableDatabase.execSQL("DELETE FROM saved_details WHERE `key`=?",arrayOf(it.key))}
                };backup.history.forEach {dao.putHistory(it)}
                collections.forEach {c->val existing=dao.collectionNamed(c.name);require(existing==null||existing.id==c.id){"A collection with this name already exists"};dao.putCollection(c)}
                details.forEach {row->
                    dao.putSavedDetail(row);dao.deleteSavedAssets(row.key)
                    val detail=gson.fromJson(row.payload,SavedCopyDetails::class.java)
                    detail.selectedAssets.forEach {a->val file=savedMedia(a.url);val problem=file?.let {SavedAssetValidation.problem(it,a.type,contentSandbox)};dao.putSavedAsset(SavedAssetRow(row.key,a.key,detail.token,a.url,a.sourceUrl,a.type,if(file==null)"MISSING"else if(problem==null)"COMPLETE"else "UNSUPPORTED",file?.length() ?: 0,problem ?: if(file==null)"Media files are not included in text backups"else null,System.currentTimeMillis()))}
                }
                backup.bookmarks.forEach {row->dao.setCollectionLinks(row.key,links.filter {it.ownerKey==row.key}.map {it.collectionId})}
                retained.forEach {r->dao.putRetainedHistory(r.copy(bytes=r.payload.toByteArray().size.toLong()));dao.deleteDocuments(r.key,"history")}
            }
        }
        prefs.restore(settings);library.initialize();applyNetworkSettings();scheduleRefresh(context,prefs);updateUsage()
    }
    companion object {
        private fun snapshotHeader(file: File)=DataInputStream(file.inputStream()).use {input->ByteArray(13).also {input.readFully(it)}.joinToString(""){"%02x".format(it)}}
        private fun digestFile(file: File): String {
            val md=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;md.update(buffer,0,n)}}
            return md.digest().joinToString(""){"%02x".format(it)}
        }
        private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun hash(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
        fun atomic(file: File,bytes: ByteArray) {
            file.parentFile?.mkdirs();val temp=File(file.parentFile,file.name+".part")
            try {temp.outputStream().use {it.write(bytes)};check(temp.renameTo(file)){"Could not commit ${file.name}"}}
            finally {temp.delete()}
        }
    }
}
