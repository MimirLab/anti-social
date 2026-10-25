package app.antisocial.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class MediaPrefix(val bytes: ByteArray,val finalUrl: String,val contentType: String,val status: Int)
data class GatewayMediaPayload(val bytes:ByteArray,val finalUrl:String,val contentType:String,val manifestUrls:List<String>)

data class GatewayDocument(val status: Int,val message: String,val headers: Map<String,List<String>>,val text: String,val finalUrl: String,val conditional:Boolean=false)
/** Ephemeral object identity, never sent. Equal policy values after a mode
 * change are still a new session; another gateway cannot recreate it. */
class NetworkSession internal constructor(val policy:NetworkPolicy) {
    /** Local bookmark epoch, never part of an HTTP request. A retained feed
     * cursor cannot silently move to a later session with equal policy values. */
    internal val feedCursorEpoch=java.util.UUID.randomUUID().toString()
}
/** User connection selection, distinct from Tor becoming ready. Never persisted. */
internal class NetworkSelection

data class Payload(val text: String, val finalUrl: String, val contentType: String, val status: Int,val pageBookmark: String?=null,val pageOffset: Int?=null)
private data class RequestContext(val source: String,val type: String)
private data class AllowedOrigins(val values: Set<String>,val description: String="archive")
private data class ExactFeedAddress(val url:HttpUrl)
private class DeferredTraffic(val owner: kotlinx.coroutines.Job): kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key: kotlin.coroutines.CoroutineContext.Key<DeferredTraffic>
}
private class PublicExtractor
private data class PublicPreviewAccess(val source:Source,val target:Target,val lease:PublicPersonaBank.Lease,
    val session:NetworkSession,val cohort:Int,val max:Int,val factory:PublicReadEngineFactory)
private class DecodedBudgeted
private data class PublicSocket(val session:NetworkSession,val route:NetworkRoute,val socket:java.net.Socket)
private class ContextTraffic: kotlin.coroutines.AbstractCoroutineContextElement(Key) {companion object Key: kotlin.coroutines.CoroutineContext.Key<ContextTraffic>}
private class ImageUpload
/** Explicit service credentials never participate in the public scraper path. */
private data class ServiceScope(val endpoint: HttpUrl,val token: String?,val provider: String?,val localHost: Boolean=false,val anthropic: Boolean=false,val workspaceId: String?=null,val inference: Boolean=false)
private const val MODEL_REQUEST_TIMEOUT_MS=120_000L
private data class PageBookmark(val scope: HttpUrl,val value: String?)
private class TextReadBudget(private val remaining: java.util.concurrent.atomic.AtomicLong,private val requests: java.util.concurrent.atomic.AtomicInteger,
    private val parent:TextReadBudget?=null): kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key: kotlin.coroutines.CoroutineContext.Key<TextReadBudget>
    fun available():Long=minOf(remaining.get(),parent?.available() ?: Long.MAX_VALUE)
    fun request() {
        parent?.request()
        if(requests.decrementAndGet()<0)throw java.net.ProtocolException("Request exceeds the health-check budget")
    }
    fun received(count:Int) {
        parent?.received(count)
        if(remaining.addAndGet(-count.toLong())<0)throw IOException("Response exceeds the health-check budget")
    }
}
private val bookmarkPattern=Regex("[A-Za-z0-9_~.%+/=:-]{1,8192}")
data class ProtectionStats(val checked: Long,val blocked: Long)
data class NetworkEvent(val host: String,val stage: String,val elapsedMs: Long)

/** Shared enforcement path for extraction, feeds, artwork, media and updates.
 * DEFAULT follows the user's app-wide "Tor for all traffic" preference; TOR
 * requires Tor even when ordinary content is direct. configureTor updates only
 * the search session unless DEFAULT already uses Tor. Neither source failover
 * nor a redirect authorizes direct fallback from a Tor-selected session.
 * Destination/credential checks, filters, purpose and session guards are shared
 * by both modes. See docs/ARCHITECTURE.md and docs/REVIEWING.md for consumers,
 * the native settings control, narrow service exceptions and dated evidence. */
class NetworkGateway(private val cleaner: UrlCleaner, initialFilter: RequestFilter, private val allowHttpForTests: Boolean = false) {
    private val filter=AtomicReference(initialFilter)
    private val checkedRequests=java.util.concurrent.atomic.AtomicLong()
    private val blockedRequests=java.util.concurrent.atomic.AtomicLong()
    fun protectionStats()=ProtectionStats(checkedRequests.get(),blockedRequests.get())
    private val policy=AtomicReference(NetworkSession(NetworkPolicy()))
    private val torPolicy=AtomicReference(NetworkSession(NetworkPolicy(tor=true)))
    private val selection=AtomicReference(NetworkSelection())
    private val syndication=lazy {SyndicationReader(this)}
    private val clients=mutableMapOf<Pair<NetworkRoute,NetworkPolicy>,OkHttpClient>()
    private val foregroundRequests=java.util.concurrent.atomic.AtomicInteger()
    private val deferredCalls=java.util.concurrent.ConcurrentHashMap<Call,kotlinx.coroutines.Job>()
    private val contextPermits=Semaphore(2)
    @Volatile private var publicReadFactory:PublicReadEngineFactory?=null
    private val publicPersonas by lazy {PublicPersonaBank(::sessionFor)}
    private val publicSockets=java.util.concurrent.ConcurrentHashMap<Call,PublicSocket>()
    /** Installs a byte provider, never another permission or network policy. */
    @Synchronized fun installPublicReadFactory(factory:PublicReadEngineFactory) {
        check(publicReadFactory==null||publicReadFactory===factory){"Public reader already installed"}
        publicReadFactory=factory
    }
    val hasPublicReadFactory:Boolean get()=publicReadFactory!=null
    suspend fun <T> contextRead(read: suspend ()->T): T=withContext(ContextTraffic()){read()}
    val hasForegroundRequests: Boolean get()=foregroundRequests.get()>0
    /** Supplemental profile labels yield even to media using the shared client.
     * The marker is local request metadata; nothing is sent to the server. */
    suspend fun <T> deferredRead(read: suspend ()->T): T=kotlinx.coroutines.coroutineScope {
        withContext(DeferredTraffic(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)){read()}
    }
    private val cleanup=java.util.concurrent.Executors.newSingleThreadExecutor {r->Thread(r,"anti social network cleanup").apply {isDaemon=true}}
    var onBlocked: (String,String)->Unit = { _,_ -> }
    @Volatile var onNetworkEvent: ((NetworkEvent)->Unit)?=null
    /** Explicit diagnostic opt-in only; ordinary requests retain no response log. */
    @Volatile var onTextResponse: ((Payload)->Unit)?=null
    @Volatile var originalFallback: Boolean = true
    private val searchDomains=setOf("bing.com","duckduckgo.com","google.com","yandex.com","yandex.ru","tineye.com","saucenao.com")
    private val originalDomains=java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {addAll(setOf("youtube.com","youtu.be","youtube-nocookie.com","googlevideo.com","ytimg.com","youtubei.googleapis.com","yt3.googleusercontent.com","ggpht.com","x.com","twitter.com","t.co","twimg.com","reddit.com","redd.it","bsky.app","tumblr.com","tiktok.com","tiktokcdn.com","tiktokcdn-us.com","tiktokcdn-eu.com","tiktokv.com","instagram.com","cdninstagram.com","fbcdn.net","pinterest.com","pinimg.com","ifunny.co","getfn.io","tieba.baidu.com","imgsrc.baidu.com","bdimg.com","threads.net","threads.com","facebook.com","fb.watch","fbsbx.com","truthsocial.com","linkedin.com","licdn.com","snapchat.com","sc-cdn.net","snapkit.com","gettr.com","gab.com","t.me","telegram.org","telesco.pe","telegra.ph","mastodon.social","lemmy.world","pixelfed.social","misskey.io","misskeyusercontent.jp")+searchDomains)}
    /** Selected federated servers and original media origins share the original
     * fallback policy, including redirects and already-cached post artwork. */
    fun registerOriginalUrl(value: String) {value.toHttpUrlOrNull()?.host?.let {if(originalDomains.size<4096)originalDomains.add(it)}}
    suspend fun <T> boundedHealthRead(maxBytes: Long=512L*1024,maxRequests: Int=4,read: suspend ()->T): T {
        require(maxBytes in 1024..1024*1024)
        require(maxRequests in 1..8)
        return withContext(TextReadBudget(java.util.concurrent.atomic.AtomicLong(maxBytes),java.util.concurrent.atomic.AtomicInteger(maxRequests),kotlinx.coroutines.currentCoroutineContext()[TextReadBudget])){read()}
    }
    /** Explicit media inspection shares a request/byte budget across redirects and attachments. */
    suspend fun <T> boundedMediaRead(maxBytes:Long=24L*1024*1024,maxRequests:Int=4,read:suspend ()->T):T {
        require(maxBytes in 1024..64L*1024*1024&&maxRequests in 1..8)
        return withContext(TextReadBudget(java.util.concurrent.atomic.AtomicLong(maxBytes),java.util.concurrent.atomic.AtomicInteger(maxRequests),kotlinx.coroutines.currentCoroutineContext()[TextReadBudget])){read()}
    }
    private fun origin(url: HttpUrl)="${url.scheme}://${url.host}:${url.port}"
    private fun requestOrigins(sourceUrl: String): AllowedOrigins? {
        val source=sourceUrl.toHttpUrlOrNull() ?: return null
        return if(source.isHttps&&source.host in setOf("web.archive.org","arquivo.pt"))AllowedOrigins(setOf(origin(source)))else null
    }
    private fun restrict(request: Request,url: HttpUrl) {
        request.tag(ExactFeedAddress::class.java)?.let {scope->
            if(url!=scope.url)throw IOException("Feed navigation left its selected address")
        }
        request.tag(ServiceScope::class.java)?.let {scope->
            if(origin(url)!=origin(scope.endpoint)||url.encodedPath!=scope.endpoint.encodedPath||url.encodedQuery!=scope.endpoint.encodedQuery)
                throw IOException("Service request left its selected endpoint")
        }
        request.tag(AllowedOrigins::class.java)?.let {scope->
            if(origin(url) !in scope.values)throw IOException("Request left its selected ${scope.description}")
            val index=when(url.host) {"web.archive.org"->"/cdx/search/cdx";"arquivo.pt"->"/wayback/cdx";else->return@let}
            if(request.method !in setOf("GET","HEAD")||!url.isHttps||url.port!=443||
                url.encodedPath!=index&&ArchiveUrls.original(url.toString())==null&&!youtubeArchiveEndpoint(url))throw IOException("Archive requests are limited to read-only indexes and replays")
        }
    }
    private fun youtubeArchiveEndpoint(url: HttpUrl): Boolean {
        if(url.host!="web.archive.org")return false
        val id=Regex("[A-Za-z0-9_-]{11}")
        return url.encodedPath=="/__wb/videoinfo"&&url.queryParameterNames==setOf("vtype","vid")&&url.queryParameter("vtype")=="youtube"&&url.queryParameter("vid")?.matches(id)==true ||
            Regex("^/web/(?:0|[0-9]{14})(?:id_)?/https?://wayback-fakeurl\\.archive\\.org/yt/[A-Za-z0-9_-]{11}$").matches(url.encodedPath)&&url.query==null
    }
    private fun searchHost(host: String)=searchDomains.any {host==it||host.endsWith(".$it")}
    private fun routeFor(request: Request)=if(searchHost(request.url.host))NetworkRoute.TOR else request.tag(NetworkRoute::class.java) ?: NetworkRoute.DEFAULT
    private fun validate(url: HttpUrl,request: Request?=null) {
        request?.tag(ServiceScope::class.java)?.takeIf {it.localHost}?.let {scope->
            restrict(request,url)
            if(currentPolicy(routeFor(request)).tor)throw IOException("Local model servers cannot be reached while Tor is selected")
            if(url.username.isNotEmpty()||url.password.isNotEmpty()||localModelAddress(url.host)==null)throw IOException("Select an explicit local model server address")
            return
        }
        if(!url.isHttps && !allowHttpForTests)throw IOException("An insecure source was rejected")
        if(allowHttpForTests)return
        if(url.username.isNotEmpty()||url.password.isNotEmpty())throw IOException("Source credentials are not supported")
        if(url.host=="localhost"||url.host.endsWith(".local")||url.host.endsWith(".localhost"))throw IOException("Private destinations are not public sources")
        if(!originalFallback && originalDomains.any {url.host==it||url.host.endsWith(".$it")})throw IOException("Original-site access is disabled")
        if(url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) {
            val address=InetAddress.getByName(url.host)
            if(privateAddress(address))throw IOException("Private destinations are not public sources")
        }
    }
    private fun privateAddress(address: InetAddress)=address.isAnyLocalAddress||address.isLoopbackAddress||address.isLinkLocalAddress||address.isSiteLocalAddress||address.isMulticastAddress||address.hostAddress.orEmpty().startsWith("fc",true)||address.hostAddress.orEmpty().startsWith("fd",true)
    /** No arbitrary DNS names: a local-host grant belongs to exactly one
     * loopback/private numeric address, never to a rebinding public hostname. */
    private fun localModelAddress(host: String): InetAddress? {
        if(host!="localhost"&&!host.contains(':')&&!host.matches(Regex("[0-9.]+")))return null
        val address=runCatching {InetAddress.getByName(host)}.getOrNull() ?: return null
        return address.takeIf {!it.isAnyLocalAddress&&!it.isMulticastAddress&&!it.isLinkLocalAddress&&(it.isLoopbackAddress||it.isSiteLocalAddress||it.hostAddress.orEmpty().startsWith("fc",true)||it.hostAddress.orEmpty().startsWith("fd",true))}
    }
    fun replaceFilter(next: RequestFilter) { filter.set(next) }
    /** Local screening before an explicit browser handoff, including cached
     * availability results. This does not open a socket or run a website. */
    fun screenNavigation(value:String,sourceUrl:String=value):String {
        val mode=currentPolicy()
        if(mode.tor&&!mode.ready)throw IOException("Tor is connecting. Requests are paused.")
        val request=Request.Builder().url(value).build()
        return filteredDestination(request,sourceUrl,"document").toString()
    }
    private fun filteredDestination(request:Request,source:String,type:String):HttpUrl {
        if(request.tag(FetchPurpose::class.java)==FetchPurpose.SUPPLEMENTAL&&(request.method !in setOf("GET","HEAD")||!AutomaticLinks.mayRead(request.url)))throw IOException("Action links require deliberate opening")
        var value=request.url.toString()
        val engine=filter.get()
        repeat(9) {
            val url=cleaner.clean(value).toHttpUrlOrNull() ?: throw IOException("Invalid destination")
            validate(url,request);restrict(request,url)
            if(request.tag(FetchPurpose::class.java)==FetchPurpose.SUPPLEMENTAL&&!AutomaticLinks.mayRead(url))throw IOException("Action links require deliberate opening")
            val decision=engine.check(url.toString(),source,type)
            checkedRequests.incrementAndGet()
            if(decision.blocked){blockedRequests.incrementAndGet();onBlocked(url.host,decision.reason);throw IOException("Blocked by privacy filters: ${url.host}")}
            val next=decision.rewrittenUrl?.let {it.toHttpUrlOrNull() ?: throw IOException("Invalid filter destination")} ?: url
            if(next==url)return url
            value=next.toString()
        }
        throw IOException("Filter rewrite loop was stopped")
    }
    internal fun currentSession(route:NetworkRoute=NetworkRoute.DEFAULT):NetworkSession=if(route==NetworkRoute.TOR)torPolicy.get()else policy.get()
    internal fun currentSelection():NetworkSelection=selection.get()
    /** Use the session the actual source host would select, including mandatory
     * Tor search hosts. Unrelated search readiness must not cancel direct reads. */
    internal fun sessionFor(url:String):NetworkSession=currentSession(routeFor(Request.Builder().url(url).build()))
    fun currentPolicy(route: NetworkRoute=NetworkRoute.DEFAULT): NetworkPolicy = currentSession(route).policy
    suspend fun prepare(route: NetworkRoute=NetworkRoute.DEFAULT)=withContext(Dispatchers.IO){client(route);Unit}
    fun clearFeedMemory(){if(syndication.isInitialized())syndication.value.clear()}
    fun cleanupFeedMemory(){if(syndication.isInitialized())syndication.value.cleanup()}
    suspend fun feed(url:String,target:Target,source:Source,maxBytes:Long=4L*1024*1024,headers:Map<String,String> = emptyMap(),parse:suspend (Payload)->Page):Page=withContext(Dispatchers.IO) {
        syndication.value.read(url,target,source,maxBytes,headers,parse=parse)
    }
    /** A decoded feed bookmark keeps its issuing session across the handoff to
     * IO. Reading the then-current session here would allow a mode-change race. */
    internal suspend fun feedContinuation(url:String,target:Target,source:Source,session:NetworkSession,parse:suspend (Payload)->Page):Page=withContext(Dispatchers.IO) {
        syndication.value.read(url,target,source,4L*1024*1024,emptyMap(),expectedSession=session,parse=parse)
    }
    internal suspend fun feedDocument(url:String,sourceUrl:String,maxBytes:Long,headers:Map<String,String>,session:NetworkSession,pinAddress:Boolean=false):GatewayDocument=withContext(Dispatchers.IO) {
        var document:GatewayDocument?=null
        val request=Request.Builder().url(url).tag(NetworkSession::class.java,session).tag(FetchPurpose::class.java,FetchPurpose.API)
            .tag(ExactFeedAddress::class.java,if(pinAddress)ExactFeedAddress(requireNotNull(url.toHttpUrlOrNull()))else null)
            .tag(AllowedOrigins::class.java,requestOrigins(sourceUrl)).tag(RequestContext::class.java,RequestContext(sourceUrl,"xmlhttprequest"))
            .apply {headers.forEach {(name,value)->header(name,value)}}.build()
        readText(request,maxBytes,allowNotModified=true,onResponse={response,payload->
            document=GatewayDocument(response.code,response.message,response.headers.toMultimap().filterKeys {!it.equals("Set-Cookie",true)},payload.text,payload.finalUrl,
                response.request.header("If-None-Match")!=null||response.request.header("If-Modified-Since")!=null)
        })
        requireNotNull(document)
    }
    @Synchronized fun configure(next: NetworkPolicy) {
        if(policy.get().policy.tor!=next.tor)selection.set(NetworkSelection())
        val torChanged=next.tor&&torPolicy.get().policy!=next
        val contentChanged=policy.get().policy!=next
        if(torChanged)torPolicy.set(NetworkSession(next))
        if(contentChanged)policy.set(NetworkSession(next))
        closeStalePublicSockets()
        if(torChanged||contentChanged) {
            val stale=clients.keys.filter {it.first==NetworkRoute.DEFAULT&&contentChanged||it.first==NetworkRoute.TOR&&torChanged}
            val previous=stale.mapNotNull(clients::remove)
            // TLS close-notify can write to the socket. Android forbids that on
            // the UI thread; new requests already see the new policy above.
            if(previous.isNotEmpty())cleanup.execute {previous.forEach {it.dispatcher.cancelAll();it.connectionPool.evictAll()}}
        }
    }
    /** Updating the search connection never changes direct content/media traffic. */
    @Synchronized fun configureTor(next: NetworkPolicy) {
        require(next.tor)
        if(torPolicy.get().policy==next)return
        torPolicy.set(NetworkSession(next))
        if(policy.get().policy.tor)policy.set(NetworkSession(next))
        closeStalePublicSockets()
        val stale=clients.filterKeys {it.second.tor&&it.second!=next}.values.toList()
        clients.keys.removeAll {it.second.tor&&it.second!=next}
        if(stale.isNotEmpty())cleanup.execute {stale.forEach {it.dispatcher.cancelAll();it.connectionPool.evictAll()}}
    }
    private fun closeStalePublicSockets() {
        val stale=publicSockets.entries.filter {currentSession(it.value.route)!==it.value.session}
        stale.forEach {entry->if(publicSockets.remove(entry.key,entry.value))cleanup.execute {runCatching {entry.value.socket.close()}}}
    }
    @Synchronized fun client(route: NetworkRoute=NetworkRoute.DEFAULT): OkHttpClient {
        val session=currentSession(route);val snapshot=session.policy
        if(snapshot.tor && (!snapshot.ready || snapshot.socksPort !in 1..65535)) throw IOException("Tor is connecting. Requests are paused.")
        return clients.getOrPut(route to snapshot) {
            OkHttpClient.Builder().proxy(if(snapshot.tor) Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",snapshot.socksPort)) else Proxy.NO_PROXY)
                .eventListenerFactory {call->
                    object: EventListener() {
                        private val started=System.nanoTime()
                        private val owner=call.request().tag(DeferredTraffic::class.java)?.owner
                        private val finished=java.util.concurrent.atomic.AtomicBoolean()
                        private fun event(stage: String){onNetworkEvent?.let {runCatching {it(NetworkEvent(call.request().url.host,stage,(System.nanoTime()-started)/1_000_000))}}}
                        private fun finish(){if(finished.compareAndSet(false,true)){if(owner==null)foregroundRequests.decrementAndGet()else deferredCalls.remove(call)}}
                        override fun callStart(call: Call){
                            if(owner==null){foregroundRequests.incrementAndGet();deferredCalls.values.toSet().forEach {it.cancel(kotlinx.coroutines.CancellationException("Content requests take priority"))}}
                            else {deferredCalls[call]=owner;if(hasForegroundRequests||!owner.isActive)owner.cancel(kotlinx.coroutines.CancellationException("Content requests take priority"))}
                            event("start")
                        }
                        override fun callEnd(call: Call){finish()}
                        override fun dnsStart(call: Call,domainName: String){event("dns")}
                        override fun dnsEnd(call: Call,domainName: String,inetAddressList: List<InetAddress>){event("dns ready")}
                        override fun connectStart(call: Call,inetSocketAddress: InetSocketAddress,proxy: Proxy){event("connect")}
                        override fun secureConnectStart(call: Call){event("tls")}
                        override fun secureConnectEnd(call: Call,handshake: Handshake?){event("tls ready")}
                        override fun responseHeadersStart(call: Call){event("response")}
                        override fun responseBodyEnd(call: Call,byteCount: Long){event("body ready")}
                        override fun callFailed(call: Call,ioe: IOException){finish();event("failed: ${ioe.javaClass.simpleName}")}
                    }
                }
                .dns(object: Dns {override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses -> if(!allowHttpForTests && addresses.any(::privateAddress))throw IOException("DNS resolved to a private destination") } })
                .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
                .connectTimeout(if(snapshot.tor) 30 else 8,TimeUnit.SECONDS).readTimeout(if(snapshot.tor) 45 else 15,TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    if(currentSession(route)!==session||chain.request().tag(NetworkSession::class.java)?.let {it!==session}==true)throw IOException("Network mode changed; request cancelled")
                    // Count actual HTTP exchanges, including transport retries,
                    // before writing headers. ProtocolException is not retried.
                    chain.request().tag(TextReadBudget::class.java)?.request()
                    chain.proceed(chain.request())
                }
                .addInterceptor { chain ->
                    var request=chain.request()
                    val context=request.tag(RequestContext::class.java)
                    val source=context?.source ?: request.header("X-AntiSocial-Source") ?: request.url.toString()
                    val type=context?.type ?: request.header("X-AntiSocial-Type") ?: "xmlhttprequest"
                    if(request.tag(AllowedOrigins::class.java)==null)requestOrigins(source)?.let {request=request.newBuilder().tag(AllowedOrigins::class.java,it).build()}
                    repeat(9) {
                        if(currentSession(route)!==session||request.tag(NetworkSession::class.java)?.let {it!==session}==true)throw IOException("Network mode changed; request cancelled")
                        val destination=filteredDestination(request,source,type)
                        if(currentSession(route)!==session)throw IOException("Network mode changed; request cancelled")
                        if(searchHost(destination.host)&&!snapshot.tor)throw IOException("Search engines require Tor; the direct redirect was stopped")
                        val bookmark=request.tag(PageBookmark::class.java)
                        if(bookmark!=null&&(destination.host!=bookmark.scope.host||destination.port!=bookmark.scope.port||destination.scheme!=bookmark.scope.scheme||bookmark.value!=null&&destination.encodedPath.trimEnd('/')!=bookmark.scope.encodedPath.trimEnd('/')))throw IOException("Pagination bookmark left its source endpoint")
                        request=request.newBuilder().url(destination).removeHeader("X-AntiSocial-Source").removeHeader("X-AntiSocial-Type").removeHeader("Cookie").removeHeader("Authorization").removeHeader("X-PCA-Provider").removeHeader("x-api-key").removeHeader("anthropic-version").removeHeader("anthropic-workspace-id").header("User-Agent",request.header("User-Agent")?.takeIf {request.tag(PublicExtractor::class.java)!=null&&it.length<=512} ?: "AntiSocial/0.31 (public content reader)").apply {
                            if(destination!=request.url){removeHeader("If-None-Match");removeHeader("If-Modified-Since")}
                            bookmark?.value?.let {header("Cookie","bookmark=$it")}
                            request.tag(ServiceScope::class.java)?.let {service->service.token?.let {header("Authorization","Bearer $it")};service.provider?.let {header("X-PCA-Provider",it)};if(service.anthropic)header("anthropic-version","2023-06-01");service.workspaceId?.let {header("anthropic-workspace-id",it)}}
                        }.build()
                        val response=chain.proceed(request)
                        if(currentSession(route)!==session){response.close();throw IOException("Network mode changed; response discarded")}
                        if(response.code !in setOf(301,302,303,307,308)) return@addInterceptor response
                        if(request.tag(ServiceScope::class.java)!=null){response.close();throw IOException("The service redirected; the request was not repeated")}
                        val code=response.code
                        val next=response.header("Location")?.let { request.url.resolve(it) }
                        // Lens completes a same-origin upload with a 303 to a
                        // different Google result origin. Return the location
                        // to the provider adapter; never forward image bytes.
                        if(code==303&&request.tag(ImageUpload::class.java)!=null&&next!=null&&origin(next)!=origin(request.url))return@addInterceptor response
                        response.close()
                        if(next==null)throw IOException("Invalid redirect")
                        if(request.body!=null&&(next.host!=request.url.host||next.port!=request.url.port||next.scheme!=request.url.scheme))throw IOException("Form redirect left the selected source")
                        // Redirects are a new request: clean/filter them before DNS or a socket.
                        request=request.newBuilder().url(next).removeHeader("If-None-Match").removeHeader("If-Modified-Since").apply {if(code==303&&request.method!="HEAD"||code in setOf(301,302)&&request.method=="POST")get()}.build()
                    }
                    throw IOException("Redirect loop was stopped")
                }.addInterceptor {chain->
                    val access=chain.request().tag(PublicPreviewAccess::class.java)
                    if(access==null)chain.proceed(chain.request())else try {publicExchange(chain,access)}
                    catch(e:IOException){throw e}catch(e:Exception){throw IOException("Public preview exchange was rejected",e)}
                }.build()
        }
    }
    /** Only a pre-authorized request reaches here, after the ordinary Gateway
     * filter/credential/redirect interceptor. This owns all byte-engine IO. */
    private fun publicExchange(chain:Interceptor.Chain,access:PublicPreviewAccess):Response {
        val request=chain.request();val route=routeFor(request);val call=chain.call();val url=request.url
        fun current() {
            if(call.isCanceled()||currentSession(route)!==access.session)throw IOException("Network mode changed or public read cancelled")
            if(!originalFallback)throw IOException("Original-site access is disabled")
            publicPersonas.ensure(access.lease,access.source,access.target,url.toString())
        }
        current();require(request.method=="GET"&&request.tag(ServiceScope::class.java)==null&&request.tag(ImageUpload::class.java)==null)
        val budget=request.tag(TextReadBudget::class.java)
        val limit=minOf(access.max.toLong(),budget?.available() ?: Long.MAX_VALUE).toInt()
        if(limit<1)throw IOException("Public read budget exhausted")
        val base=client(route);val timeout=call.timeout().timeoutNanos().takeIf {it>0} ?: java.util.concurrent.TimeUnit.SECONDS.toNanos(if(access.session.policy.tor)45 else 12)
        val deadline=System.nanoTime()+timeout
        fun alive(){current();if(System.nanoTime()>=deadline)throw java.net.SocketTimeoutException("Public read timed out")}
        val socket=java.net.Socket();val active=PublicSocket(access.session,route,socket)
        var peerVerified=false
        publicSockets[call]=active
        try {
            alive();socket.soTimeout=200
            // The request/attempt budget applies even if TLS fails before HTTP.
            budget?.request()
            val address=if(access.session.policy.tor)InetSocketAddress("127.0.0.1",access.session.policy.socksPort)else {
                val addresses=base.dns.lookup(url.host);alive()
                InetSocketAddress(addresses.firstOrNull() ?: throw IOException("Public source has no address"),url.port)
            }
            alive();val remaining=((deadline-System.nanoTime())/1_000_000).coerceIn(1,Int.MAX_VALUE.toLong()).toInt()
            socket.connect(address,minOf(base.connectTimeoutMillis,remaining));alive()
            val input=socket.getInputStream();val output=socket.getOutputStream()
            fun exact(n:Int):ByteArray {
                val bytes=ByteArray(n);var at=0
                while(at<n){alive();val got=try {input.read(bytes,at,n-at)}catch(_:java.net.SocketTimeoutException){continue};if(got<0)throw java.io.EOFException("Public proxy ended early");at+=got}
                return bytes
            }
            if(access.session.policy.tor) {
                // Explicit SOCKS5 domain addressing; never local target DNS or
                // the Java SOCKS implementation's ambient credential fallback.
                output.write(byteArrayOf(5,1,0));output.flush()
                if(!exact(2).contentEquals(byteArrayOf(5,0)))throw IOException("Tor proxy did not accept anonymous SOCKS5")
                val host=url.host.toByteArray(Charsets.US_ASCII);require(host.size in 1..253)
                output.write(byteArrayOf(5,1,0,3,host.size.toByte()));output.write(host);output.write(byteArrayOf((url.port ushr 8).toByte(),url.port.toByte()));output.flush()
                val reply=exact(4);if(reply[0]!=5.toByte()||reply[1]!=0.toByte()||reply[2]!=0.toByte())throw IOException("Tor could not connect to the public source")
                exact(when(reply[3].toInt()and 255){1->4;4->16;3->exact(1)[0].toInt()and 255;else->throw IOException("Invalid Tor proxy address")});exact(2)
            }
            val headers=access.lease.persona.headers(true).toMutableList()
            publicPersonas.cookies(access.lease,access.source,access.target,url.toString())?.let {headers+="cookie" to it}
            val path=url.encodedPath+(url.encodedQuery?.let {"?$it"}.orEmpty())
            access.factory.open(access.cohort,url.host,url.port,path,headers,limit,verify={peer->
                alive();val session=PublicReadTrust.session(url.host,url.port,peer)
                access.factory.verifyChain(base.x509TrustManager ?: throw IOException("Platform trust unavailable"),session)
                if(!base.hostnameVerifier.verify(url.host,session))throw javax.net.ssl.SSLPeerUnverifiedException("Public source certificate does not match its hostname")
                alive();peerVerified=true;true
            },decoded={count->alive();if(!peerVerified)throw IOException("Public data arrived before peer verification");budget?.received(count)}).use {engine->
                val bytes=ByteArray(32768)
                while(true) {
                    alive();val state=engine.step()
                    if(state<0)throw IOException("Public preview exchange failed")
                    if(state==2) {
                        val result=engine.result();alive()
                        if(!peerVerified)throw javax.net.ssl.SSLPeerUnverifiedException("Public preview omitted peer verification")
                        require(result.status in 200..599&&result.body.size<=limit&&result.headers.size<=128)
                        publicPersonas.receive(access.lease,access.source,access.target,url.toString(),result.headers.filter {it.first.equals("set-cookie",true)}.map {it.second})
                        val responseHeaders=Headers.Builder().apply {result.headers.forEach {(name,value)->addUnsafeNonAscii(name,value)}}.build()
                        return Response.Builder().request(request.newBuilder().tag(DecodedBudgeted::class.java,DecodedBudgeted()).build())
                            .protocol(Protocol.HTTP_2).code(result.status).message("")
                            .headers(responseHeaders.newBuilder().removeAll("Content-Encoding").removeAll("Content-Length").build())
                            .body(result.body.toResponseBody(responseHeaders["Content-Type"]?.toMediaTypeOrNull())).build()
                    }
                    var n=engine.output(bytes)
                    while(n>0){alive();output.write(bytes,0,n);n=engine.output(bytes)}
                    output.flush()
                    if(state==1)continue
                    val got=try {input.read(bytes)}catch(_:java.net.SocketTimeoutException){continue}
                    if(got<0)throw java.io.EOFException("Public preview ended before a complete response")
                    if(got>0)engine.feed(bytes,got)
                }
            }
        }finally {publicSockets.remove(call,active);runCatching {socket.close()}}
    }
    private suspend fun <T> execute(request: Request,networkTimeoutMs: Long?=null,drained: kotlinx.coroutines.CompletableDeferred<Unit>?=null,read: (Response)->T): T {
        // Bind before waiting for admission. A queued request must not silently
        // move to a different privacy session, even direct -> Tor -> direct.
        val bound=if(request.tag(NetworkSession::class.java)!=null)request else request.newBuilder().tag(NetworkSession::class.java,currentSession(routeFor(request))).build()
        return if(kotlinx.coroutines.currentCoroutineContext()[ContextTraffic]!=null)contextPermits.withPermit {executeNow(bound,networkTimeoutMs,drained,read)}else executeNow(bound,networkTimeoutMs,drained,read)
    }
    private suspend fun <T> executeNow(request: Request,networkTimeoutMs: Long?,drained: kotlinx.coroutines.CompletableDeferred<Unit>?,read: (Response)->T): T=suspendCancellableCoroutine {continuation->
        val session=request.tag(NetworkSession::class.java)
        fun failed(error:Exception) {
            // A user changing mode is cancellation, not evidence that this
            // source is down. This also covers direct -> Tor -> direct.
            val failure=if(session!==currentSession(routeFor(request)))kotlinx.coroutines.CancellationException("Network mode changed").also {it.initCause(error)}else error
            if(continuation.isActive)continuation.resumeWithException(failure)
        }
        try {
        if(session!==currentSession(routeFor(request)))throw IOException("Network mode changed; request cancelled")
        validate(request.url,request);restrict(request,request.url)
        val deferred=continuation.context[DeferredTraffic]
        val tagged=if(deferred==null)request else request.newBuilder().tag(DeferredTraffic::class.java,deferred).build()
        val base=client(routeFor(tagged))
        val service=tagged.tag(ServiceScope::class.java)
        val transport=if(service!=null)base.newBuilder().retryOnConnectionFailure(false).apply {
            if(service.inference) {
                // A non-streaming model may prepare its answer before sending
                // headers or body bytes. The reader's 15/45-second socket
                // timeout must not undercut this authorized inference budget.
                readTimeout(MODEL_REQUEST_TIMEOUT_MS,TimeUnit.MILLISECONDS)
                callTimeout(MODEL_REQUEST_TIMEOUT_MS,TimeUnit.MILLISECONDS)
            }
            if(service.localHost) {
                val address=localModelAddress(service.endpoint.host) ?: throw IOException("Invalid local model server address")
                dns(object: Dns {override fun lookup(hostname: String): List<InetAddress> {if(hostname!=service.endpoint.host)throw IOException("Local model request left its selected host");return listOf(address)}})
            }
        }.build()else base
        val call=transport.newCall(tagged)
        if(networkTimeoutMs!=null)call.timeout().timeout(networkTimeoutMs,TimeUnit.MILLISECONDS)
        continuation.invokeOnCancellation {call.cancel();publicSockets.remove(call)?.let {entry->cleanup.execute {runCatching {entry.socket.close()}}}}
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException){try {failed(e)}finally {drained?.complete(Unit)}}
            override fun onResponse(call: Call,response: Response){
                if(!continuation.isActive){response.close();drained?.complete(Unit);return}
                try {
                    if(session!==currentSession(routeFor(request)))throw IOException("Network mode changed; response discarded")
                    val value=response.use(read)
                    if(session!==currentSession(routeFor(request)))throw IOException("Network mode changed; response discarded")
                    if(continuation.isActive)continuation.resume(value)
                }catch(e: Exception){response.close();failed(e)}finally {drained?.complete(Unit)}
            }
        })
        }catch(e: Exception){drained?.complete(Unit);failed(e)}
    }
    /** Establish a reusable connection without a query or response body. This
     * returns HTTP status only, never evidence that a source has usable content. */
    suspend fun preconnect(url: String): Int=withContext(Dispatchers.IO) {
        val request=Request.Builder().url(url).head().build()
        require(request.url.query==null){"Connection preparation must not contain a query"}
        execute(request,10_000){it.code}
    }
    suspend fun text(url: String, sourceUrl: String=url, type: String="xmlhttprequest", maxBytes: Long=4L*1024*1024, headers: Map<String,String> = emptyMap(), route: NetworkRoute=NetworkRoute.DEFAULT,allowedOrigins: Set<String>?=null,purpose:FetchPurpose=FetchPurpose.API,expectedSession:NetworkSession?=null): Payload = withContext(Dispatchers.IO) {
        val pinned=allowedOrigins?.let {values->
            require(values.isNotEmpty()&&values.size<=4){"Invalid archive origin scope"}
            AllowedOrigins(values.map {value->val parsed=value.toHttpUrlOrNull() ?: error("Invalid archive origin");require(parsed.isHttps||allowHttpForTests);origin(parsed)}.toSet(),if(values.any {searchHost(it.toHttpUrlOrNull()?.host.orEmpty())})"search source"else "archive")
        } ?: requestOrigins(sourceUrl)
        val request=Request.Builder().url(url).tag(NetworkSession::class.java,expectedSession).tag(FetchPurpose::class.java,purpose).tag(AllowedOrigins::class.java,pinned).tag(NetworkRoute::class.java,route).tag(RequestContext::class.java,RequestContext(sourceUrl,type)).apply { headers.forEach { (k,v)->header(k,v) } }.build()
        readText(request,maxBytes)
    }
    /** Optional original-preview route, never a global replacement for pooled
     * reads. SourcePool must select it after the alternate source stage. */
    suspend fun publicPreview(url:String,target:Target,source:Source,cohort:Int=1,maxBytes:Long=4L*1024*1024):Payload {
        require(cohort in 0..1&&maxBytes in 1024..4L*1024*1024&&source.role==SourceRole.ORIGINAL)
        require(target.kind in setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD,TargetKind.COMMUNITY))
        val session=sessionFor(url)
        return withContext(Dispatchers.IO) {
            if(sessionFor(url)!==session)throw kotlinx.coroutines.CancellationException("Network mode changed")
            if(!originalFallback)throw IOException("Original-site access is disabled")
            if(session.policy.tor&&(!session.policy.ready||session.policy.socksPort !in 1..65535))throw IOException("Selected Tor is not ready")
            val factory=publicReadFactory?.takeIf {it.supports(cohort)} ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"Public preview transport is unavailable")
            val lease=publicPersonas.acquire(source,target,url,if(cohort==0)PersonaCohort.ANDROID_131 else PersonaCohort.MACOS_142)
            val scope=requireNotNull(url.toHttpUrlOrNull())
            val request=Request.Builder().url(scope).tag(NetworkSession::class.java,session).tag(FetchPurpose::class.java,FetchPurpose.API)
                .tag(RequestContext::class.java,RequestContext(target.canonicalUrl,"document"))
                .tag(AllowedOrigins::class.java,AllowedOrigins(setOf(origin(scope)),"public preview"))
                .tag(PublicPreviewAccess::class.java,PublicPreviewAccess(source,target,lease,session,cohort,maxBytes.toInt(),factory)).build()
            readText(request,maxBytes)
        }
    }
    /** NewPipe's synchronous parsers receive bounded documents through this
     * cancellable gateway. Cookies/credentials remain stripped; only public
     * read methods and small JSON request bodies are accepted. */
    suspend fun contextText(url: String,sourceUrl: String,maxBytes: Long=2L*1024*1024,sameOrigin: Boolean=false): Payload=withContext(Dispatchers.IO) {
        val parsed=url.toHttpUrlOrNull() ?: error("Invalid evidence address")
        val pinned=if(sameOrigin)AllowedOrigins(setOf(origin(parsed)),"context")else requestOrigins(sourceUrl)
        val request=Request.Builder().url(parsed).tag(AllowedOrigins::class.java,pinned).tag(RequestContext::class.java,RequestContext(sourceUrl,"xmlhttprequest")).build()
        readText(request,maxBytes)
    }
    suspend fun extractorDocument(url: String,method: String="GET",data: ByteArray?=null,headers: Map<String,String> = emptyMap(),sourceUrl: String=url,sameOrigin: Boolean=false,maxBytes:Long=8L*1024*1024,route:NetworkRoute=NetworkRoute.DEFAULT,purpose:FetchPurpose=FetchPurpose.API,expectedSession:NetworkSession?=null): GatewayDocument=withContext(Dispatchers.IO) {
        require(maxBytes in 1..8L*1024*1024){"Invalid extraction allowance"}
        require(method in setOf("GET","HEAD","POST")) {"Unsupported extraction method"}
        require(data==null||data.size<=1024*1024) {"Extraction request exceeds its size limit"}
        val content=headers.entries.firstOrNull {it.key.equals("Content-Type",true)}?.value ?: "application/json"
        require(method!="POST"||content.substringBefore(';') in setOf("application/json","application/x-www-form-urlencoded")) {"Unsupported extraction request"}
        val budget=kotlinx.coroutines.currentCoroutineContext()[TextReadBudget]
        val pinned=if(sameOrigin)AllowedOrigins(setOf(origin(url.toHttpUrlOrNull() ?: error("Invalid extraction URL"))),"context")else requestOrigins(sourceUrl)
        val request=Request.Builder().url(url).tag(NetworkSession::class.java,expectedSession).tag(NetworkRoute::class.java,route).tag(AllowedOrigins::class.java,pinned).tag(FetchPurpose::class.java,purpose).tag(PublicExtractor::class.java,PublicExtractor()).tag(RequestContext::class.java,RequestContext(sourceUrl,"xmlhttprequest")).tag(TextReadBudget::class.java,budget).apply {
            headers.forEach {(key,value)->if(!key.equals("Cookie",true)&&!key.equals("Authorization",true))header(key,value)}
            method(method,if(method=="POST")(data ?: ByteArray(0)).toRequestBody(content.toMediaType())else null)
        }.build()
        execute(request,if(currentPolicy(routeFor(request)).tor)45_000L else 15_000L) {response->
            val max=maxBytes
            val body=response.body
            if(body!=null&&body.contentLength()>max)throw IOException("Extraction response exceeds its size limit")
            if(body!=null&&budget!=null&&body.contentLength()>budget.available())throw IOException("Response exceeds the health-check budget")
            val output=java.io.ByteArrayOutputStream()
            body?.byteStream()?.use {stream->val chunk=ByteArray(8192);while(true){val n=stream.read(chunk);if(n<0)break;if(output.size()+n>max)throw IOException("Extraction response exceeds its size limit");budget?.received(n);output.write(chunk,0,n)}}
            GatewayDocument(response.code,response.message,response.headers.toMultimap().filterKeys {!it.equals("Set-Cookie",true)},output.toString("UTF-8"),response.request.url.toString(),response.request.header("If-None-Match")!=null||response.request.header("If-Modified-Since")!=null)
        }
    }
    /** Pinless uses one anonymous cursor cookie instead of putting the cursor in
     * its next link. No cookie jar is enabled; only this bounded bookmark may be
     * captured and explicitly replayed against the same source endpoint. */
    suspend fun pagedText(url: String,sourceUrl: String,bookmark: String?=null): Payload=withContext(Dispatchers.IO) {
        require(bookmark==null||bookmarkPattern.matches(bookmark)){"Invalid pagination bookmark"}
        val destination=url.toHttpUrlOrNull() ?: error("Invalid pagination endpoint")
        readText(Request.Builder().url(destination).tag(RequestContext::class.java,RequestContext(sourceUrl,"xmlhttprequest")).tag(PageBookmark::class.java,PageBookmark(destination,bookmark)).build(),4L*1024*1024)
    }
    /** Public search forms use the same filtering, Tor and cancellation path. */
    suspend fun form(url: String,fields: Map<String,String>,sourceUrl: String=url,headers: Map<String,String> = emptyMap()): Payload=
        formInSession(url,fields,sourceUrl,headers,null)
    internal suspend fun formInSession(url:String,fields:Map<String,String>,sourceUrl:String,headers:Map<String,String> = emptyMap(),expectedSession:NetworkSession?):Payload=withContext(Dispatchers.IO) {
        require(fields.size<=16&&fields.entries.sumOf {it.key.length+it.value.length}<=16*1024){"Pagination form is too large"}
        val body=FormBody.Builder().apply {fields.forEach {(key,value)->add(key,value)}}.build()
        require(headers.size<=12&&headers.entries.sumOf {it.key.length+it.value.length}<=4096){"Source headers are too large"}
        readText(Request.Builder().url(url).tag(NetworkSession::class.java,expectedSession).tag(RequestContext::class.java,RequestContext(sourceUrl,"xmlhttprequest")).apply {headers.forEach {(k,v)->header(k,v)}}.post(body).build(),4L*1024*1024)
    }
    /** An explicit image search submits a bounded, metadata-stripped copy to a
     * public search form. Images never leave Tor or the selected upload origin;
     * there is no account, cookie jar, persistent upload token or direct retry. */
    suspend fun imageUpload(url: String,field: String,image: ByteArray,mimeType: String,base64: Boolean=false,headers: Map<String,String> = emptyMap()): Payload=withContext(Dispatchers.IO) {
        require(image.isNotEmpty()&&image.size<=8*1024*1024){"Search image must be between 1 byte and 8 MiB"}
        require(field.matches(Regex("[A-Za-z_]{1,40}"))){"Invalid image form field"}
        require(mimeType in setOf("image/jpeg","image/png","image/webp")){"Unsupported search image format"}
        require(headers.size<=8&&headers.entries.sumOf {it.key.length+it.value.length}<=4096){"Source headers are too large"}
        val destination=url.toHttpUrlOrNull() ?: error("Invalid image search upload")
        require(destination.isHttps||allowHttpForTests){"Image uploads require HTTPS"}
        val body=MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            if(base64)addFormDataPart(field,java.util.Base64.getEncoder().encodeToString(image))
            else addFormDataPart(field,"image."+when(mimeType){"image/png"->"png";"image/webp"->"webp";else->"jpg"},image.toRequestBody(mimeType.toMediaType()))
        }.build()
        readText(Request.Builder().url(destination).tag(AllowedOrigins::class.java,AllowedOrigins(setOf(origin(destination)),"image search source")).tag(ImageUpload::class.java,ImageUpload())
            .tag(NetworkRoute::class.java,NetworkRoute.TOR).tag(RequestContext::class.java,RequestContext(url,"xmlhttprequest"))
            .apply {headers.forEach {(k,v)->header(k,v)}}.post(body).build(),4L*1024*1024)
    }
    /** Only public read endpoints use JSON bodies. The gateway still removes
     * credentials, enforces filtering, validates every hop and fails closed on Tor. */
    suspend fun json(url: String,fields: com.google.gson.JsonObject,sourceUrl: String=url,headers: Map<String,String> = emptyMap()): Payload=withContext(Dispatchers.IO) {
        val encoded=fields.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size<=16*1024){"Public data query is too large"}
        require(headers.size<=12&&headers.entries.sumOf {it.key.length+it.value.length}<=4096){"Source headers are too large"}
        val body=encoded.toRequestBody("application/json; charset=utf-8".toMediaType())
        readText(Request.Builder().url(url).tag(RequestContext::class.java,RequestContext(sourceUrl,"xmlhttprequest")).apply {headers.forEach {(k,v)->header(k,v)}}.post(body).build(),4L*1024*1024)
    }
    /** Narrow native service transport. The exact endpoint is pinned before and
     * after cleaning/filtering. Writes are one-shot: no redirect or transport
     * replay, no cookie jar, and no credentials on ordinary content requests. */
    internal suspend fun service(url: String,json: com.google.gson.JsonObject?=null,form: Map<String,String>?=null,token: String?=null,provider: String?=null): Payload=withContext(Dispatchers.IO) {
        require(json==null||form==null)
        require(token==null||token.length in 1..8192&&token.all {it.code in 33..126}){"Invalid service credential"}
        require(provider==null||provider=="firebase"||provider=="native")
        val endpoint=url.toHttpUrlOrNull() ?: error("Invalid service endpoint")
        require(endpoint.username.isEmpty()&&endpoint.password.isEmpty())
        val body=when {json!=null->json.toString().toRequestBody("application/json; charset=utf-8".toMediaType());form!=null->FormBody.Builder().apply {form.forEach {(k,v)->add(k,v)}}.build();else->null}
        require(body==null||body.contentLength()<=16*1024){"Service request is too large"}
        val once=body?.let {original->object: RequestBody() {
            override fun contentType()=original.contentType()
            override fun contentLength()=original.contentLength()
            override fun isOneShot()=true
            override fun writeTo(sink: okio.BufferedSink)=original.writeTo(sink)
        }}
        val request=Request.Builder().url(endpoint).tag(ServiceScope::class.java,ServiceScope(endpoint,token,provider))
            .tag(RequestContext::class.java,RequestContext(url,"xmlhttprequest")).apply {if(once!=null)post(once)}.build()
        readText(request,512L*1024)
    }
    /** User-selected inference endpoint. Credentials never enter content
     * observers, redirects or retries. Local hosting is a separate explicit
     * grant and fails closed under Tor rather than silently going direct. */
    internal suspend fun aiService(url: String,json: com.google.gson.JsonObject,token: String?=null,localHost: Boolean=false,anthropic: Boolean=false,workspaceId: String?=null): Payload=withContext(Dispatchers.IO) {
        require(workspaceId==null||anthropic&&workspaceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {"Invalid provider workspace ID"}
        require(token==null||token.length in 1..8192&&token.all {it.code in 33..126}) {"Invalid model server credential"}
        val endpoint=url.toHttpUrlOrNull() ?: error("Invalid model server endpoint")
        require(endpoint.username.isEmpty()&&endpoint.password.isEmpty()&&endpoint.query==null&&endpoint.fragment==null)
        require(endpoint.isHttps||localHost||allowHttpForTests) {"Remote model servers require HTTPS"}
        if(localHost)require(localModelAddress(endpoint.host)!=null) {"Local hosting requires a loopback or private numeric address"}
        val original=json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        require(original.contentLength()<=2L*1024*1024) {"Model request is too large"}
        val once=object: RequestBody() {
            override fun contentType()=original.contentType()
            override fun contentLength()=original.contentLength()
            override fun isOneShot()=true
            override fun writeTo(sink: okio.BufferedSink)=original.writeTo(sink)
        }
        val request=Request.Builder().url(endpoint).tag(ServiceScope::class.java,ServiceScope(endpoint,token,null,localHost,anthropic,workspaceId,inference=true))
            .tag(RequestContext::class.java,RequestContext(url,"xmlhttprequest")).post(once).build()
        readText(request,2L*1024*1024,MODEL_REQUEST_TIMEOUT_MS)
    }
    /** Read-only capability metadata from the explicitly selected model server.
     * Shares the inference credential scope and exact endpoint pin, without a
     * request body, redirect, replay, cookie or reader-content observer. */
    internal suspend fun aiMetadata(url: String,token: String?=null,localHost: Boolean=false,anthropic: Boolean=false,workspaceId: String?=null): Payload=withContext(Dispatchers.IO) {
        require(workspaceId==null||anthropic&&workspaceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {"Invalid provider workspace ID"}
        require(token==null||token.length in 1..8192&&token.all {it.code in 33..126}) {"Invalid model server credential"}
        val endpoint=url.toHttpUrlOrNull() ?: error("Invalid model server endpoint")
        require(endpoint.username.isEmpty()&&endpoint.password.isEmpty()&&endpoint.query==null&&endpoint.fragment==null)
        require(endpoint.isHttps||localHost||allowHttpForTests) {"Remote model servers require HTTPS"}
        if(localHost)require(localModelAddress(endpoint.host)!=null) {"Local hosting requires a loopback or private numeric address"}
        val request=Request.Builder().url(endpoint).tag(ServiceScope::class.java,ServiceScope(endpoint,token,null,localHost,anthropic,workspaceId))
            .tag(RequestContext::class.java,RequestContext(url,"xmlhttprequest")).header("Accept","application/json").get().build()
        readText(request,1024L*1024)
    }
    private suspend fun readText(request: Request,maxBytes: Long,timeoutMs: Long?=null,allowNotModified:Boolean=false,onResponse:((Response,Payload)->Unit)?=null): Payload {
        val healthBudget=kotlinx.coroutines.currentCoroutineContext()[TextReadBudget]
        val tor=currentPolicy(routeFor(request)).tor
        val boundedRequest=if(healthBudget==null)request else request.newBuilder().tag(TextReadBudget::class.java,healthBudget).build()
        return execute(boundedRequest,timeoutMs ?: if(maxBytes>4L*1024*1024){if(tor)120_000L else 60_000L}else if(tor)45_000L else 12_000L) { response ->
            if(response.code==303&&request.tag(ImageUpload::class.java)!=null) {
                val next=response.header("Location")?.let {response.request.url.resolve(it)} ?: throw IOException("Invalid image result redirect")
                validate(next)
                return@execute Payload("",next.toString(),"",303)
            }
            when(response.code) {
                429 -> throw SourceFailure(HealthStatus.RATE_LIMITED,"Source is rate limited",retryAfter(response),response.code)
                503 -> throw SourceFailure(HealthStatus.UNAVAILABLE,"Source returned 503",retryAfter(response),response.code)
                401 -> throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"This content requires login",httpStatus=response.code)
            }
            if(!response.isSuccessful&&!(allowNotModified&&response.code==304))throw SourceFailure(HealthStatus.UNAVAILABLE,"Source returned ${response.code}",retryAfter(response),response.code)
            val body=response.body ?: throw IOException("Empty response")
            val prepaid=response.request.tag(DecodedBudgeted::class.java)!=null
            if(!prepaid&&healthBudget!=null&&body.contentLength()>healthBudget.available())throw IOException("Response exceeds the health-check budget")
            if(body.contentLength()>maxBytes)throw IOException("Response exceeds its size limit")
            val buffer=java.io.ByteArrayOutputStream()
            body.byteStream().use { stream -> val chunk=ByteArray(8192);while(true) {val count=stream.read(chunk);if(count<0)break;if(!prepaid)healthBudget?.received(count);if(buffer.size().toLong()+count>maxBytes)throw IOException("Response exceeds its size limit");buffer.write(chunk,0,count)} }
            val bookmark=if(request.tag(PageBookmark::class.java)!=null)response.headers("Set-Cookie").mapNotNull {Cookie.parse(response.request.url,it)}.firstOrNull {it.name=="bookmark"&&bookmarkPattern.matches(it.value)&&it.expiresAt>System.currentTimeMillis()}?.value else null
            val form=request.body as? FormBody
            val offset=form?.let {fields->(0 until fields.size).firstOrNull {fields.name(it)=="s"}?.let {fields.value(it).toIntOrNull()}} ?: request.url.queryParameter("s")?.toIntOrNull()
            Payload(buffer.toString("UTF-8"),response.request.url.toString(),body.contentType()?.toString().orEmpty(),response.code,bookmark,offset).also {payload->onResponse?.invoke(response,payload);if(request.tag(ServiceScope::class.java)==null)onTextResponse?.let {observer->runCatching {observer(payload)}}}
        }
    }
    /** A server-requested pause is carried to routing without retaining headers.
     * Both delta-seconds and HTTP dates are accepted; cap hostile or mistaken
     * values at one day so an endpoint cannot disable a route indefinitely. */
    private fun retryAfter(response: Response): Long? {
        val value=response.header("Retry-After")?.trim()?.takeIf {it.isNotEmpty()&&it.length<=128} ?: return null
        val day=86_400_000L
        if(value.all {it in '0'..'9'}) {
            val significant=value.trimStart('0').ifEmpty {"0"}
            return (significant.toLongOrNull() ?: return day).coerceAtMost(86_400L)*1000L
        }
        val now=System.currentTimeMillis()
        // RFC 9110 recipients also recognize the two obsolete HTTP-date forms.
        val patterns=listOf("EEE, dd MMM yyyy HH:mm:ss zzz","EEEE, dd-MMM-yy HH:mm:ss zzz","EEE MMM d HH:mm:ss yyyy")
        for(pattern in patterns) {
            val parser=java.text.SimpleDateFormat(pattern,java.util.Locale.US).apply {isLenient=false;timeZone=java.util.TimeZone.getTimeZone("GMT")}
            val position=java.text.ParsePosition(0)
            val date=parser.parse(value,position) ?: continue
            if(position.index!=value.length)continue
            return (date.time-now).coerceIn(0L,day)
        }
        return null
    }
    suspend fun bytes(url: String, sourceUrl: String=url, maxBytes: Long=16L*1024*1024): ByteArray = mediaPayload(url,sourceUrl,maxBytes).bytes
    /** Retains only credential references, never cookies or arbitrary response headers. */
    suspend fun mediaPayload(url:String,sourceUrl:String=url,maxBytes:Long=16L*1024*1024,type:String="image"):GatewayMediaPayload=withContext(Dispatchers.IO) {
        require(maxBytes in 1..32L*1024*1024)
        val budget=kotlinx.coroutines.currentCoroutineContext()[TextReadBudget]
        withTimeout(if(currentPolicy().tor)120_000L else 30_000L) {
        execute(Request.Builder().url(url).tag(TextReadBudget::class.java,budget).tag(AllowedOrigins::class.java,requestOrigins(sourceUrl)).tag(RequestContext::class.java,RequestContext(sourceUrl,type)).build()) { response ->
            if(!response.isSuccessful)throw IOException("Media returned ${response.code}")
            val body=response.body ?: throw IOException("Empty media")
            val stream=body.byteStream();val buffer=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
            while(true) { val count=stream.read(chunk);if(count<0)break;if(buffer.size().toLong()+count>maxBytes)throw IOException("Media is too large to cache");budget?.received(count);buffer.write(chunk,0,count) }
            val final=response.request.url.toString()
            GatewayMediaPayload(buffer.toByteArray(),final,body.contentType()?.toString().orEmpty(),MediaCredentialLinks.headers(response.headers.values("Link"),final))
        }
        }
    }
    suspend fun sample(url: String,sourceUrl: String=url,type: String="media",limit: Int=64*1024,range: Boolean=true): ByteArray=samplePayload(url,sourceUrl,type,limit,range).bytes
    /** A redirect-aware bounded prefix proves that a recovery URL really leads
     * to the selected recording, without downloading an entire video. */
    suspend fun samplePayload(url: String,sourceUrl: String=url,type: String="media",limit: Int=64*1024,range: Boolean=true): MediaPrefix=withContext(Dispatchers.IO) {
        require(limit in 1..256*1024)
        val budget=kotlinx.coroutines.currentCoroutineContext()[TextReadBudget]
        withTimeout(if(currentPolicy().tor)45_000L else 15_000L) {
            execute(Request.Builder().url(url).tag(TextReadBudget::class.java,budget).tag(AllowedOrigins::class.java,requestOrigins(sourceUrl)).tag(RequestContext::class.java,RequestContext(sourceUrl,type)).apply {if(range)header("Range","bytes=0-${limit-1}")}.build()) {response->
                if(!response.isSuccessful)throw IOException("Media returned ${response.code}")
                val stream=response.body?.byteStream() ?: throw IOException("Empty media")
                val output=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
                while(output.size()<limit) {val n=stream.read(chunk,0,minOf(chunk.size,limit-output.size()));if(n<0)break;budget?.received(n);output.write(chunk,0,n)}
                MediaPrefix(output.toByteArray(),response.request.url.toString(),response.body?.contentType()?.toString().orEmpty(),response.code)
            }
        }
    }
    suspend fun resolve(url: String): String=withContext(Dispatchers.IO) {
        withTimeout(if(currentPolicy().tor)45_000L else 12_000L) {
            execute(Request.Builder().url(url).tag(RequestContext::class.java,RequestContext(url,"xmlhttprequest")).header("Range","bytes=0-0").build()) {it.request.url.toString()}
        }
    }
    /** Exact-size model payload, streamed to private staging. Range validation
     * prevents appending a whole response or a different byte range. Partial
     * bytes survive cancellation; activation still requires the signed hash. */
    internal suspend fun downloadAiArtifact(url: String,file: java.io.File,expectedBytes: Long,progress: (Long)->Unit={}): Long=withContext(Dispatchers.IO) {
        require(expectedBytes in 1..4L*1024*1024*1024)
        val offset=if(file.exists())file.length()else 0L
        require(offset<=expectedBytes) {"Staged model exceeds its declared size"}
        if(offset==expectedBytes)return@withContext offset
        val request=Request.Builder().url(url).header("Accept-Encoding","identity")
            .tag(RequestContext::class.java,RequestContext(url,"other")).apply {if(offset>0)header("Range","bytes=$offset-")}.build()
        val drained=kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            execute(request,drained=drained) { response->
                if(response.code!=200&&response.code!=206)throw IOException("Model download returned ${response.code}")
                val body=response.body ?: throw IOException("Empty model response")
                if(response.header("Content-Encoding")?.let {it!="identity"}==true)throw IOException("Encoded model range was rejected")
                val append=response.code==206
                val start=if(append)offset else 0L
                if(append) {
                    val range=response.header("Content-Range")?.let {Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it)} ?: throw IOException("Missing model byte range")
                    if(range.groupValues[1].toLongOrNull()!=offset||range.groupValues[2].toLongOrNull()!=expectedBytes-1||range.groupValues[3].toLongOrNull()!=expectedBytes)throw IOException("Model byte range does not match the release manifest")
                }
                if(body.contentLength()>expectedBytes-start)throw IOException("Model exceeds its declared size")
                file.parentFile?.mkdirs()
                var total=start
                java.io.FileOutputStream(file,append).use {output->
                    body.byteStream().use {input->val buffer=ByteArray(64*1024);while(true) {
                        val count=input.read(buffer);if(count<0)break
                        if(total+count>expectedBytes)throw IOException("Model exceeds its declared size")
                        output.write(buffer,0,count);total+=count;progress(total)
                    }}
                    output.fd.sync()
                }
                if(total!=expectedBytes)throw IOException("Model download was interrupted; its partial file can be resumed")
                total
            }
        }finally {
            // A cancelled continuation must not release the install mutex
            // while OkHttp is still closing the staging file on its thread.
            withContext(kotlinx.coroutines.NonCancellable) {drained.await()}
        }
    }
    suspend fun download(url: String, sourceUrl: String, file: java.io.File, type: String="media", maxBytes: Long=256L*1024*1024,purpose:FetchPurpose=FetchPurpose.MEDIA): Long = withContext(Dispatchers.IO) {
        withTimeout(if(currentPolicy().tor)600_000L else 300_000L) {
        execute(Request.Builder().url(url).tag(FetchPurpose::class.java,purpose).tag(AllowedOrigins::class.java,requestOrigins(sourceUrl)).tag(RequestContext::class.java,RequestContext(sourceUrl,type)).build()) { response ->
            if(!response.isSuccessful)throw IOException("Download returned ${response.code}")
            val body=response.body ?: throw IOException("Empty media")
            if(body.contentLength()>maxBytes)throw IOException("Download exceeds its size limit")
            if(type=="media" && body.contentType()?.toString()?.contains("mpegurl",true)==true)throw IOException("Offline playlists are not yet supported")
            var total=0L
            try {file.outputStream().use {output->body.byteStream().use {input->val buffer=ByteArray(8192);while(true){val count=input.read(buffer);if(count<0)break;total+=count;if(total>maxBytes)throw IOException("Download exceeds its size limit");output.write(buffer,0,count)}}}}catch(e: Exception){file.delete();throw e}
            total
        }
        }
    }
}
