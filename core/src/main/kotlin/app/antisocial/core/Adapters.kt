package app.antisocial.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.google.gson.Gson

private fun newestReddit(target: Target): Target {
    if(target.platform!=Platform.REDDIT||target.kind!=TargetKind.COMMUNITY)return target
    val uri=URI(target.canonicalUrl);val parts=uri.path.trim('/').split('/')
    if(parts.size !in 2..3||parts.firstOrNull()!="r"||parts.size==3&&parts[2] !in setOf("hot","top","new","rising"))return target
    return target.copy(canonicalUrl="${Platform.REDDIT.origin}/r/${parts[1]}/new")
}
private fun Exception.respectRetryDelay() {
    if(this is SourceFailure&&(status==HealthStatus.RATE_LIMITED||retryAfterMs!=null))throw this
}
private suspend fun mediaPage(target: Target,url: String,source: Source,gateway: NetworkGateway): Page {
    val type=MediaFormats.type(gateway.sample(url,target.canonicalUrl)) ?: throw SourceFailure(HealthStatus.EMPTY,"This source did not return recognizable media")
    val provenance=Provenance(source,System.currentTimeMillis(),url)
    return Page(listOf(Post(ContentIdentity.key(target.platform,target.canonicalUrl),target.platform,target.canonicalUrl,"","",media=listOf(Media(url,type)),provenance=provenance)),provenance=provenance)
}

class FrontendAdapter(override val source: Source, private val gateway: NetworkGateway, private val routes: DarwinRoutes) : DataAdapter {
    private data class PinlessCursor(val url: String,val bookmark: String,val target: String)
    override val capabilities = if(source.frontend=="binternet")setOf(Capability.SEARCH,Capability.MEDIA)else setOf(Capability.PROFILE,Capability.TIMELINE,Capability.POST,Capability.FEED,Capability.MEDIA)+
        (if(source.frontend in setOf("nitter","redlib","libreddit","teddit","priviblur","kittygram","pinless","painterest"))setOf(Capability.SEARCH)else emptySet())+
        (if(source.frontend in setOf("nitter","redlib","libreddit","teddit","ratAintTieba"))setOf(Capability.COMMENTS)else emptySet())
    override suspend fun fetch(target: Target, cursor: String?): Page {
        if(target.kind==TargetKind.MEDIA)return mediaPage(target,routes.convert(target,source),source,gateway)
        val urls=mutableListOf<String>()
        var bookmark: String?=null
        val raw=routes.convert(target,source)
        if(cursor!=null) {
            val url=if(source.frontend=="pinless") {
                require(cursor.length<=24*1024&&cursor.startsWith("pinless:")){"Refresh this page to continue"}
                val saved=Gson().fromJson(cursor.removePrefix("pinless:"),PinlessCursor::class.java)
                require(saved.target==target.canonicalUrl){"Pagination changed the selected search or profile"}
                bookmark=saved.bookmark;saved.url
            }else cursor
            require(DarwinRoutes.matches(URI(url),source.root)) { "Pagination left the selected source" }
            urls+=url
        } else {
            if(source.frontend=="pinless"&&target.kind==TargetKind.PROFILE&&!target.identity.contains('/'))urls+=raw.trimEnd('/')+"/_created"
            if(source.frontend=="kittygram"&&target.kind==TargetKind.PROFILE)urls+=raw.substringBefore('#')+(if(raw.contains('?'))"&"else "?")+"view=timeline"
            if(source.frontend=="proxigram") {
                val identity=target.identity.trim('/').removePrefix("@")
                urls+=if(target.kind==TargetKind.POST) "${source.root}/api/p/${identity.substringAfterLast('/')}" else "${source.root}/api/$identity/posts"
            }
            urls+=raw
        }
        var last: Exception?=null
        for(url in urls)try {
            val payload=if(source.frontend=="pinless")gateway.pagedText(url,target.canonicalUrl,bookmark)else gateway.text(url,target.canonicalUrl)
            val page=Extraction.parse(payload,target,source,routes)
            if(source.frontend=="kittygram"&&target.kind==TargetKind.SEARCH&&cursor==null&&target.identity.matches(Regex("[\\p{L}\\p{N}_]{1,64}"))) {
                try {
                    val topic=routes.target(Platform.INSTAGRAM,"#"+target.identity)
                    val tagged=withTimeoutOrNull(if(gateway.currentPolicy().tor)8_000 else 3_000){Extraction.parse(gateway.text(routes.convert(topic,source),target.canonicalUrl),target,source,routes)}
                    if(tagged?.posts?.isNotEmpty()==true)return page.copy(posts=tagged.posts,next=tagged.next,provenance=tagged.provenance)
                }catch(e: CancellationException){throw e}catch(_: Exception){/* Account results remain valid if the topic is unavailable. */}
            }
            return if(source.frontend=="pinless")page.copy(next=page.next?.let {next->payload.pageBookmark?.let {"pinless:"+Gson().toJson(PinlessCursor(next,it,target.canonicalUrl))}})else page
        }catch(e: CancellationException) {throw e}catch(e: Exception) {e.respectRetryDelay();last=e}
        throw last ?: SourceFailure(HealthStatus.UNAVAILABLE,"Source has no supported data endpoint")
    }
    override suspend fun directory(target: Target,cursor: String?): Page {
        if(source.platform!=Platform.REDDIT||source.frontend !in setOf("redlib","libreddit","teddit"))throw SourceFailure(HealthStatus.UNSUPPORTED,"This frontend has no separate public directory reader")
        require(target.platform==source.platform&&target.kind==TargetKind.SEARCH&&SearchScopes.from(target)==null&&cursor==null){"Directory discovery requires an unscoped first window"}
        val base=source.root.toHttpUrlOrNull() ?: error("Invalid directory source")
        val url=base.newBuilder().encodedPath(base.encodedPath.trimEnd('/')+"/search").query(null).fragment(null).addQueryParameter("q",target.identity).addQueryParameter("type","sr_user").build().toString()
        val payload=gateway.text(url,target.canonicalUrl)
        require(DarwinRoutes.matches(URI(payload.finalUrl),source.root)){"The directory redirected outside its selected frontend"}
        val page=Extraction.parse(payload,target,source,routes)
        return Page(emptyList(),accounts=page.accounts,provenance=page.provenance)
    }
    override suspend fun feed(target: Target): Page {
        val newest=newestReddit(target)
        val raw=routes.convert(newest,source).substringBefore('#').substringBefore('?').trimEnd('/')
        val url=when(source.frontend) { "kittygram" -> "$raw/atom.xml";"shoelace" -> "${source.root}/rss/${target.identity.removePrefix("@").trim('/')}";"nitter","proxigram","priviblur","proxiTok" -> "$raw/rss";"redlib","libreddit","teddit" -> "$raw.rss?sort=new";else -> "$raw/rss" }
        return try { Extraction.parse(gateway.text(url,target.canonicalUrl),target,source,routes) }catch(e: CancellationException){throw e}catch(e: Exception){e.respectRetryDelay();fetch(newest)}
    }
    override fun sentinel(capability: Capability): Target = if(capability==Capability.SEARCH)routes.search(source.platform,"space")else when(source.platform) {
        Platform.REDDIT -> routes.target(Platform.REDDIT,"r/Android",TargetKind.COMMUNITY)
        Platform.X -> routes.target(Platform.X,"NASA")
        Platform.INSTAGRAM -> routes.target(Platform.INSTAGRAM,"nasa")
        Platform.BLUESKY -> routes.target(Platform.BLUESKY,"bsky.app")
        Platform.TUMBLR -> routes.target(Platform.TUMBLR,"staff")
        Platform.TIKTOK -> routes.target(Platform.TIKTOK,"nasa")
        Platform.PINTEREST -> routes.target(Platform.PINTEREST,"nasa")
        Platform.IFUNNY -> routes.target(Platform.IFUNNY,"user/ifunny")
        Platform.TIEBA -> routes.target(Platform.TIEBA,"Android",TargetKind.COMMUNITY)
        Platform.THREADS -> routes.target(Platform.THREADS,"nasa")
        Platform.FACEBOOK -> routes.target(source.platform,"NASA")
        Platform.TRUTH_SOCIAL -> routes.target(source.platform,"realDonaldTrump")
        Platform.MASTODON -> routes.target(source.platform,"stux@mstdn.social")
        Platform.LEMMY -> routes.target(source.platform,"c/technology",TargetKind.COMMUNITY)
        Platform.PIXELFED -> routes.target(source.platform,"pixelfed")
        Platform.MISSKEY -> routes.target(source.platform,"support")
        Platform.GETTR -> routes.target(source.platform,"joerogan")
        Platform.GAB -> routes.target(source.platform,"AmericanFamAssc")
        Platform.TELEGRAM -> routes.target(source.platform,"telegram")
        Platform.LINKEDIN -> routes.target(source.platform,"company/google")
        Platform.SNAPCHAT -> routes.target(source.platform,"nasa")
        Platform.YOUTUBE -> routes.target(Platform.YOUTUBE,"@NASA")
        Platform.RSS -> throw SourceFailure(HealthStatus.UNSUPPORTED,"Ordinary feeds use their selected feed URL")
    }
}

class OriginalAdapter(override val source: Source, private val gateway: NetworkGateway, private val routes: DarwinRoutes): DataAdapter {
    override val capabilities=setOf(Capability.PROFILE,Capability.TIMELINE,Capability.POST,Capability.FEED,Capability.MEDIA)+
        (if(source.platform in setOf(Platform.BLUESKY,Platform.REDDIT,Platform.TUMBLR,Platform.PINTEREST,Platform.INSTAGRAM))setOf(Capability.SEARCH)else emptySet())+
        (if(source.platform in setOf(Platform.REDDIT,Platform.BLUESKY))setOf(Capability.COMMENTS)else emptySet())
    override suspend fun fetch(target: Target, cursor: String?): Page {
        if(target.kind==TargetKind.SEARCH&&Capability.SEARCH !in capabilities)throw SourceFailure(HealthStatus.UNSUPPORTED,"The original site does not provide accountless search through this reader")
        if(target.kind==TargetKind.MEDIA)return mediaPage(target,target.canonicalUrl,source,gateway)
        if(cursor?.startsWith("https://")==true) {
            val url=cursor.toHttpUrlOrNull() ?: error("Invalid public feed continuation")
            require(url.host==target.platform.searchDomain||url.host.endsWith("."+target.platform.searchDomain)){"Pagination left the original platform"}
            return Extraction.parse(gateway.text(cursor,target.canonicalUrl),target,source,routes)
        }
        var url=SearchScopes.networkUrl(target)
        var selectedBlueskyUri: String?=null
        if(URI(url).host in setOf("t.co","redd.it")) {
            val expanded=routes.recognize(gateway.resolve(url))
            if(expanded!=null&&expanded.canonicalUrl!=target.canonicalUrl)return fetch(expanded,cursor)
            throw SourceFailure(HealthStatus.UNAVAILABLE,"Short link did not resolve to a supported public post")
        }
        when(target.platform) {
            Platform.X -> if(target.kind==TargetKind.POST) {
                val id=target.identity.substringAfter("status/").substringBefore('/').substringBefore('?')
                require(id.matches(Regex("[0-9]{1,40}"))){"Invalid public post ID"}
                val number=id.toDouble()/1e15*Math.PI
                var fraction=number-number.toLong();val digits=StringBuilder(number.toLong().toString(36))
                repeat(16){fraction*=36;val digit=fraction.toInt().coerceIn(0,35);digits.append("0123456789abcdefghijklmnopqrstuvwxyz"[digit]);fraction-=digit}
                val token=digits.toString().replace("0","")
                url="https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=$token"
            }
            Platform.REDDIT -> {
                val newest=SearchScopes.networkUrl(newestReddit(target)).toHttpUrlOrNull()!!
                val sort=if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD))newest.queryParameter("sort")?.takeIf {it in setOf("new","top")} ?: "new"else "new"
                url=newest.newBuilder().encodedPath(newest.encodedPath.trimEnd('/')+".json").setQueryParameter("raw_json","1").setQueryParameter("sort",sort).apply {cursor?.let {setQueryParameter("after",it)}}.build().toString()
            }
            Platform.BLUESKY -> {
                val id=target.identity.removePrefix("profile/")
                url=if(target.kind in setOf(TargetKind.SEARCH,TargetKind.TAG)) {
                    val query=DarwinRoutes.queryValue(URI(target.canonicalUrl),"q") ?: target.identity
                    val scope=SearchScopes.from(target)
                    "https://public.api.bsky.app/xrpc/app.bsky.feed.searchPosts?q=${DarwinRoutes.encode(query)}&sort=latest&limit=50"+(when(scope?.kind){SearchScopeKind.ACCOUNT->"&author=${DarwinRoutes.encode(scope.value)}";SearchScopeKind.TAG->"&tag=${DarwinRoutes.encode(scope.value)}";else->""})+(cursor?.let {"&cursor=${DarwinRoutes.encode(it)}"}.orEmpty())
                }else if(id.contains("/post/")) {
                    val handle=id.substringBefore("/post/")
                    val profile=gateway.text("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=${DarwinRoutes.encode(handle)}",target.canonicalUrl)
                    val did=com.google.gson.JsonParser.parseString(profile.text).asJsonObject["did"].asString
                    selectedBlueskyUri="at://$did/app.bsky.feed.post/${id.substringAfter("/post/")}"
                    "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?uri=${DarwinRoutes.encode(selectedBlueskyUri!!)}&depth=6"
                } else "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=${DarwinRoutes.encode(id)}&filter=posts_no_replies"+(cursor?.let { "&cursor=${DarwinRoutes.encode(it)}" }.orEmpty())
            }
            Platform.INSTAGRAM -> if(target.kind==TargetKind.PROFILE)url="https://www.instagram.com/api/v1/users/web_profile_info/?username=${DarwinRoutes.encode(target.identity)}"
            else -> Unit
        }
        return try {
            val payload=gateway.text(url,target.canonicalUrl,headers=if(target.platform in setOf(Platform.THREADS,Platform.TIKTOK))mapOf("Accept" to "text/html","Accept-Language" to "en-US,en;q=0.5")else emptyMap())
            val page=Extraction.parse(payload,target,source,routes)
            if(selectedBlueskyUri!=null) {
                val selected=com.google.gson.JsonParser.parseString(payload.text).asJsonObject["thread"]?.takeIf {it.isJsonObject}?.asJsonObject?.get("post")?.takeIf {it.isJsonObject}?.asJsonObject
                val handle=selected?.get("author")?.takeIf {it.isJsonObject}?.asJsonObject?.get("handle")?.asString
                val canonical="https://bsky.app/profile/$handle/post/${target.identity.substringAfter("/post/")}"
                if(selected?.get("uri")?.asString==selectedBlueskyUri)page.copy(posts=page.posts.map {post->
                    val parent=if(post.parentId==ContentIdentity.key(target.platform,canonical))ContentIdentity.key(target.platform,target.canonicalUrl)else post.parentId
                    if(post.canonicalUrl==canonical)post.copy(canonicalUrl=target.canonicalUrl,id=ContentIdentity.key(target.platform,target.canonicalUrl),parentId=parent)else post.copy(parentId=parent)
                })else page
            }else page
        }catch(e: CancellationException){throw e}catch(e: Exception) {
            if(target.platform==Platform.TIKTOK&&target.kind==TargetKind.POST)Extraction.parse(gateway.text("https://www.tiktok.com/oembed?url=${DarwinRoutes.encode(target.canonicalUrl)}",target.canonicalUrl),target,source,routes)
            else if(target.platform==Platform.BLUESKY&&target.kind==TargetKind.SEARCH&&SearchScopes.from(target)==null)Extraction.parse(gateway.text("https://public.api.bsky.app/xrpc/app.bsky.actor.searchActors?q=${DarwinRoutes.encode(target.identity)}&limit=20",target.canonicalUrl),target,source,routes)
            else throw e
        }
    }
    override suspend fun directory(target: Target,cursor: String?): Page {
        require(target.platform==source.platform&&target.kind==TargetKind.SEARCH&&SearchScopes.from(target)==null&&cursor==null){"Directory discovery requires an unscoped first window"}
        val url=when(target.platform) {
            Platform.REDDIT->"${source.root.trimEnd('/')}/subreddits/search.json?q=${DarwinRoutes.encode(target.identity)}&limit=20&raw_json=1"
            Platform.BLUESKY->"https://public.api.bsky.app/xrpc/app.bsky.actor.searchActors?q=${DarwinRoutes.encode(target.identity)}&limit=20"
            else->throw SourceFailure(HealthStatus.UNSUPPORTED,"This original has no separate public directory reader")
        }
        val page=Extraction.parse(gateway.text(url,target.canonicalUrl),target,source,routes)
        return Page(emptyList(),accounts=page.accounts,provenance=page.provenance)
    }
    override suspend fun profile(target: Target): Page {
        if(target.platform==Platform.REDDIT&&target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY)) {
            val uri=target.canonicalUrl.toHttpUrlOrNull() ?: error("Invalid Reddit directory destination")
            val parts=uri.pathSegments.filter(String::isNotBlank)
            require(parts.size==2&&parts[0] in setOf("r","u","user")&&parts[1].matches(Regex("[A-Za-z0-9_-]{1,64}"))){"Invalid Reddit directory destination"}
            require((parts[0]=="r")== (target.kind==TargetKind.COMMUNITY)){"Reddit directory destination changed its type"}
            val path="/${if(target.kind==TargetKind.COMMUNITY)"r"else "user"}/${parts[1]}/about.json"
            return Extraction.parse(gateway.text(uri.newBuilder().encodedPath(path).query(null).fragment(null).addQueryParameter("raw_json","1").build().toString(),target.canonicalUrl),target,source,routes)
        }
        if(target.platform!=Platform.BLUESKY)return fetch(target)
        val actor=target.identity.removePrefix("profile/")
        return Extraction.parse(gateway.text("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=${DarwinRoutes.encode(actor)}",target.canonicalUrl),target,source,routes)
    }
    override suspend fun feed(target: Target): Page {
        val id=target.identity.trim('/').removePrefix("@").removePrefix("profile/")
        val url=when(target.platform) {
            Platform.REDDIT -> {val url=SearchScopes.networkUrl(newestReddit(target)).toHttpUrlOrNull()!!;url.newBuilder().encodedPath(url.encodedPath.trimEnd('/')+if(target.kind==TargetKind.SEARCH)".rss"else "/.rss").build().toString()}
            Platform.BLUESKY -> "https://bsky.app/profile/$id/rss"
            Platform.TUMBLR -> "https://$id.tumblr.com/rss"
            Platform.PINTEREST -> "https://www.pinterest.com/$id/feed.rss"
            else -> null
        }
        if(url!=null)try {return Extraction.parse(gateway.text(url,target.canonicalUrl),target,source,routes)}catch(e: CancellationException){throw e}catch(e: Exception){e.respectRetryDelay()}
        return fetch(target)
    }
    override fun sentinel(capability: Capability)=FrontendAdapter(source,gateway,routes).sentinel(capability)
}

/** Capability-aware orchestration over Darwin instances AND PublicPathways.
 * An individual adapter is one method, not the full retrieval fallback plan.
 * Learned eligibility, bounded rolling hedges, accountless-original policy and
 * exact cursor ownership compose with Gateway protection; route failover never
 * changes the selected Tor policy. Empty shells are not healthy content, and
 * undated previews cannot certify chronological feed freshness. Architecture,
 * source scope and dated qualification are mapped in docs/ARCHITECTURE.md. */
class SourcePool(private val gateway: NetworkGateway, private val routes: DarwinRoutes, private val pathways: PublicPathways=PublicPathways(), private val clock: ()->Long=System::currentTimeMillis, private val onProbe: suspend (ProbeResult)->Unit = {}) {
    init {routes.registerPathwayAliases(pathways.entries.map {it.source})}
    private fun content(page: Page,capability: Capability,target: Target)=when(capability){
        Capability.COMMENTS->Discussions.comments(Post(ContentIdentity.key(target.platform,target.canonicalUrl),target.platform,target.canonicalUrl,"",""),page).isNotEmpty()
        Capability.PROFILE->page.profile!=null
        Capability.POST->page.posts.any {it.platform==target.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)&&!it.preview}
        Capability.FEED->FeedRefresh.posts(page,target.platform).isNotEmpty()
        else->page.posts.isNotEmpty()||page.collections.any {!it.navigation}||capability==Capability.SEARCH&&page.accounts.isNotEmpty()
    }
    /** A validated search cursor is navigation progress, never working content or
     * a healthy frontend. Keep its empty health result while allowing later pages. */
    private fun searchProgress(page: Page,capability: Capability,target: Target)=content(page,capability,target)||
        capability==Capability.SEARCH&&target.kind==TargetKind.SEARCH&&page.provenance?.source?.let {source->
            source.frontend in PublicSearchAdapter.frontends||source.platform==Platform.FACEBOOK&&source.frontend in setOf("original","phice")
        }==true&&
        page.next?.let {it.isNotBlank()&&it.length<=24*1024}==true
    private val health=ConcurrentHashMap<String,ProbeResult>()
    private val directoryBackoff=ConcurrentHashMap<String,Long>()
    private val readers=ConcurrentHashMap<Source,DataAdapter>()
    private val learnedSources=ConcurrentHashMap<String,Source>()
    val learning=AdaptiveRouting(clock)
    val heartbeat=RouteHeartbeat(clock)
    var onLearning: (List<RouteStats>)->Unit={}
    var onHeartbeatChanged: ()->Unit={}
    var onHeartbeatReservation: suspend ()->Unit={}
    var onRead: (Target,Capability,Source)->Unit={_,_,_->}
    private val activeReads=java.util.concurrent.atomic.AtomicInteger()
    val hasActiveReads: Boolean get()=activeReads.get()>0
    private val foregroundReads=java.util.concurrent.atomic.AtomicInteger()
    private val supplementalReads=java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    val hasForegroundReads: Boolean get()=foregroundReads.get()>0
    private val backgroundRead=java.util.concurrent.atomic.AtomicReference<Job?>()
    private val permits=Semaphore(4)
    @Volatile var searchProvider=SearchProvider.DUCKDUCKGO
    var awaitSearchConnection: suspend ()->Unit={}
    var onProgress: (String)->Unit={}
    fun restore(results: List<ProbeResult>) { results.takeLast(4096).forEach { health[key(it.source,it.capability,it.tor)]=it;rememberSource(it.source) } }
    private fun usesTor(source: Source)=source.frontend in setOf("bing-rss","duckduckgo")||gateway.currentPolicy().tor
    fun results(platform: Platform): List<ProbeResult> = health.values.filter { it.source.platform==platform&&it.tor==usesTor(it.source) }.sortedBy { it.latencyMs }
    private fun key(source: Source,capability: Capability,tor: Boolean)="${source.key}|$capability|$tor"
    private fun rememberSource(source: Source) {if(source.role==SourceRole.ORIGINAL)gateway.registerOriginalUrl(source.root);if(learnedSources.size<512)learnedSources[source.key]=source}
    private fun adapter(source: Source): DataAdapter=readers[source] ?: run {
        val delegate=when {
            source.platform==Platform.YOUTUBE&&source.frontend in setOf("original","invidious","piped") -> YouTubeAdapter(source,gateway,routes)
            source.frontend in PublicSearchAdapter.frontends -> PublicSearchAdapter(source,gateway,routes)
            else -> protocolDiscoveryAdapter(source,gateway,routes) ?: largePlatformDiscoveryAdapter(source,gateway,routes) ?: otherDiscoveryAdapter(source,gateway,routes) ?: publicFeedPathwayAdapter(source,gateway,routes) ?:
                (if(source.platform==Platform.FACEBOOK)FacebookAdapter(source,gateway,routes)else null) ?: publicSocialAdapter(source,gateway,routes) ?: additionalSocialAdapter(source,gateway,routes) ?:
                if(source.role==SourceRole.ORIGINAL)OriginalAdapter(source,gateway,routes)else FrontendAdapter(source,gateway,routes)
        }
        val value=object: DataAdapter {
            override val source=delegate.source
            override val capabilities=delegate.capabilities
            override fun accepts(target: Target)=delegate.accepts(target)
            override suspend fun fetch(target: Target,cursor: String?)=SearchScopes.scopePage(target,delegate.fetch(target,cursor))
            override suspend fun searchUpdates(target:Target,cursor:String,onPartial:suspend (Page)->Unit)=
                SearchScopes.scopePage(target,delegate.searchUpdates(target,cursor){onPartial(SearchScopes.scopePage(target,it))})
            override suspend fun feed(target: Target)=SearchScopes.scopePage(target,delegate.feed(target))
            override suspend fun profile(target: Target)=delegate.profile(target)
            override suspend fun comments(target: Target,cursor: String?)=delegate.comments(target,cursor)
            override suspend fun directory(target: Target,cursor: String?)=delegate.directory(target,cursor)
            override fun sentinel(capability: Capability)=delegate.sentinel(capability)
        }
        if(readers.size>=512)readers.keys.firstOrNull()?.let {readers.remove(it)}
        readers.putIfAbsent(source,value) ?: value
    }
    /** Explicit diagnostics and native tests use the same concrete factory as routing. */
    fun sourceReader(source: Source): DataAdapter=adapter(source)
    /** Native playback shares the foreground route health and privacy tiers.
     * A tiny media read, rather than a reachable API, certifies each winner. */
    suspend fun youtubePlayback(videoId: String,originalFallback: Boolean=true,rejectedUrls: Set<String> = emptySet(),muxedOnly: Boolean=false): YouTubePlayback=withContext(Dispatchers.IO) {
        val target=YouTubeUrls.recognize(YouTubeUrls.canonical(videoId))!!
        readStarted()
        val mode=gateway.currentPolicy()
        var last: Exception?=null
        try {
            gateway.prepare()
            val paths=sourcesFor(target,Capability.MEDIA,originalFallback).filter {it.frontend in setOf("invidious","piped","original")&&learning.eligible(it,Capability.MEDIA,mode.tor,target)&&(it.role==SourceRole.ORIGINAL||learning.eligible(it,Capability.POST,mode.tor,target))}
                .sortedWith(compareBy<Source> {it.role==SourceRole.ORIGINAL}.thenBy {routeTier(it,Capability.MEDIA)}.thenBy {learning.score(it,Capability.MEDIA,mode.tor)})
            for(tier in listOf(false,true)) {
                val selected=paths.filter {(it.role==SourceRole.ORIGINAL)==tier}.take(if(tier)1 else 5)
                for(batch in selected.chunked(2)) {
                    val winner=coroutineScope {
                        val results=kotlinx.coroutines.channels.Channel<YouTubePlayback?>(batch.size)
                        val jobs=batch.map {source->launch {
                            val started=System.nanoTime()
                            var result: YouTubePlayback?=null
                            try {
                                result=withTimeout(if(mode.tor)40_000L else 15_000L) {
                                    val stream=YouTubeAdapter(source,gateway,routes).playback(videoId,rejectedUrls,muxedOnly)
                                    require(stream.videoId==videoId&&stream.media.url !in rejectedUrls){"Video identity changed"}
                                    YouTubeStreamProof.check(stream.media,stream.sourceUrl,gateway)
                                    stream
                                }
                                require(gateway.currentPolicy()==mode){"Network mode changed"}
                                record(ProbeResult(source,Capability.MEDIA,HealthStatus.HEALTHY,(System.nanoTime()-started)/1_000_000,clock(),mode.tor,"Playable video stream sampled"),target)
                            }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();last=e;result=null;record(ProbeResult(source,Capability.MEDIA,HealthStatus.UNAVAILABLE,0,clock(),mode.tor,"Video stream timed out"),target)}
                            catch(e: Exception){last=e;result=null;record(ProbeResult(source,Capability.MEDIA,(e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,0,clock(),mode.tor,e.message.orEmpty().take(180),(e as? SourceFailure)?.retryAfterMs),target)}
                            results.send(result)
                        }}
                        try {repeat(batch.size){results.receive()?.let {return@coroutineScope it}};null}
                        finally {jobs.forEach {it.cancel()};results.cancel()}
                    }
                    if(gateway.currentPolicy()!=mode)throw CancellationException("Network mode changed")
                    if(winner!=null)return@withContext winner
                }
            }
            throw last ?: SourceFailure(HealthStatus.UNAVAILABLE,"No public source supplied a playable copy of this YouTube video")
        }finally {readFinished()}
    }
    fun originalSource(platform: Platform,capability: Capability)=if(platform==Platform.X&&capability==Capability.SEARCH&&searchProvider.torOnly)Source(platform,searchProvider.frontend!!,searchProvider.root!!,fallbackOnly=true,role=SourceRole.ORIGINAL)else Source(platform,"original",if(platform==Platform.GETTR)"https://api.gettr.com"else platform.origin,role=SourceRole.ORIGINAL)
    private fun fallbackSources(platform: Platform,capability: Capability,target: Target?=null): List<Source> {
        if(platform==Platform.X&&capability==Capability.SEARCH&&searchProvider==SearchProvider.DARWIN)return emptyList()
        var original=originalSource(platform,capability)
        if(target!=null&&platform in setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY)) {
            val url=target.canonicalUrl.toHttpUrlOrNull() ?: return emptyList()
            original=original.copy(root=url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
            gateway.registerOriginalUrl(original.root)
        }
        val extra=(if(target!=null)pathways.sources(target)else pathways.sources(platform)).filter {it.role==SourceRole.ORIGINAL&&capability in adapter(it).capabilities&&(target==null||adapter(it).accepts(target))}
        return (extra+listOf(original)+(if(platform==Platform.MASTODON)listOf(original.copy(frontend="mastodon-rss"))else emptyList())+if(platform!=Platform.X&&capability==Capability.SEARCH&&searchProvider.torOnly)listOf(Source(platform,searchProvider.frontend!!,searchProvider.root!!,fallbackOnly=true,role=SourceRole.ORIGINAL))else emptyList()).distinctBy {it.key}
    }
    fun sources(platform: Platform,capability: Capability,originalFallback: Boolean=false)=if(platform==Platform.RSS)emptyList()else (routes.sources.filter {it.platform==platform}+pathways.sources(platform).filter {it.role!=SourceRole.ORIGINAL}+(if(capability==Capability.SEARCH&&searchProvider==SearchProvider.DARWIN)routes.searchSources.map {it.copy(platform=platform)}else emptyList())+if(originalFallback)fallbackSources(platform,capability)+learnedSources.values.filter {it.platform==platform} else emptyList()).distinctBy {it.key}
    fun sourcesFor(target: Target,capability: Capability,originalFallback: Boolean=false): List<Source> {
        if(target.platform==Platform.RSS)return emptyList()
        routes.register(target)
        if(target.platform in setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY))gateway.registerOriginalUrl(target.canonicalUrl)
        return (sources(target.platform,capability,false)+if(originalFallback)fallbackSources(target.platform,capability,target)else emptyList()).distinctBy {it.key}.onEach {rememberSource(it)}
    }
    private suspend fun record(result: ProbeResult,target: Target?=null,delivery: Boolean=true,partialDeliveryMs:Long?=null) {
        if(result.detail.contains("health-check budget",true))return
        health[key(result.source,result.capability,result.tor)]=result
        // Health and routing metadata contain no search terms or post bodies.
        if(partialDeliveryMs==null)learning.observe(result,target,delivery)else learning.observePartial(result,partialDeliveryMs,target,delivery)
        onProbe(result);onLearning(learning.snapshot())
        while(health.size>4096)health.entries.minByOrNull {it.value.checkedAt}?.let {health.remove(it.key)}
    }
    private fun indexFeedback(source:Source)=if(source.frontend in PublicSearchAdapter.frontends)PublicIndexFeedback(source,gateway.sessionFor(source.root))else null
    private suspend fun <T> withIndexFeedback(feedback:PublicIndexFeedback?,read:suspend ()->T):T=
        if(feedback==null)read()else withContext(feedback){read().also {feedback.check(gateway)}}
    private suspend fun recordRead(result:ProbeResult,feedback:PublicIndexFeedback?,target:Target?=null,delivery:Boolean=true):ProbeResult {
        feedback?.check(gateway)
        val refusal=feedback?.refusal
        val observed=if(refusal==null)result else result.copy(status=refusal.status,latencyMs=feedback.elapsedMs,detail=refusal.message.orEmpty().take(180),retryAfterMs=refusal.retryAfterMs)
        record(observed,target,delivery,partialDeliveryMs=if(refusal!=null&&result.status==HealthStatus.HEALTHY&&delivery)result.latencyMs else null)
        return observed
    }
    private fun indexPause(source:Source,capability:Capability,target:Target):SourceFailure? {
        if(source.frontend !in PublicSearchAdapter.frontends||capability!=Capability.SEARCH||learning.eligible(source,capability,usesTor(source)))return null
        return learning.failure(source,capability,usesTor(source),target) ?:
            SourceFailure(HealthStatus.UNAVAILABLE,"Public search source is paused; try again later.",learning.eligibleAt(source,capability,usesTor(source))-clock())
    }
    private fun delivered(target: Target,capability: Capability,page: Page,originalFallback: Boolean): Page {
        page.provenance?.source?.let {source->
            if(source.role==SourceRole.ORIGINAL) {
                gateway.registerOriginalUrl(source.root)
                (page.posts.flatMap {post->listOfNotNull(post.avatar)+post.media.flatMap {listOfNotNull(it.url,it.preview)}}+listOfNotNull(page.profile?.avatar)).forEach {gateway.registerOriginalUrl(it)}
            }
            if(content(page,capability,target)&&source.role!=SourceRole.CACHE&&page.provenance?.cached!=true) {
                if(maintenanceTarget(target,capability)) {
                    heartbeat.observeRead(heartbeatScope(target,capability),source.key,plan(target,capability,originalFallback))
                    onHeartbeatChanged()
                }
                onRead(target,capability,source)
            }
        }
        return page
    }
    fun heartbeatScope(target: Target,capability: Capability)=RouteHeartbeat.scope(target,capability,gateway.currentPolicy().tor)
    private fun maintenanceTarget(target: Target,capability: Capability)=
        target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY)&&capability in setOf(Capability.FEED,Capability.TIMELINE,Capability.PROFILE)
    private fun supportsMaintenance(source: Source,capability: Capability)=source.role!=SourceRole.CACHE&&source.frontend !in PublicSearchAdapter.frontends&&capability in adapter(source).capabilities
    private fun redirectedOriginal(source: Source,page: Page)=source.role==SourceRole.ALTERNATE&&page.provenance?.source?.role==SourceRole.ORIGINAL
    private fun routeTier(source: Source,capability: Capability): Int {
        val now=clock();val mode=usesTor(source);val h=health[key(source,capability,mode)]
        if(h?.status==HealthStatus.HEALTHY&&now-h.checkedAt<3_600_000)return 0
        if(h==null||now-h.checkedAt>=3_600_000)return 1
        if(AdaptiveRouting.isTargetSpecific(h))return if(learning.get(source,capability,mode)?.lastSuccessAt?.let {now-it<3_600_000}==true)0 else 1
        return 2
    }
    private fun routeAllowed(source: Source,capability: Capability): Boolean {
        val h=health[key(source,capability,usesTor(source))] ?: return true
        val age=clock()-h.checkedAt
        return !(h.status==HealthStatus.UNSUPPORTED&&(h.detail==ORIGINAL_REDIRECT||h.detail.startsWith("Feed pathway disabled"))&&age<86_400_000||
            capability==Capability.SEARCH&&(h.status==HealthStatus.UNSUPPORTED&&h.detail=="Search disabled"&&age<3_600_000||
                h.status==HealthStatus.UNAVAILABLE&&(h.detail=="Source returned 403"||h.detail=="Source returned 451"||h.detail.startsWith("Unable to resolve host"))&&age<600_000))
    }
    /** Preserve observed winners. For unproven paths, try another extraction
     * method before sweeping more mirrors of the same frontend. Every path
     * remains available, and original-source privacy tiers stay separate. */
    private data class RequestedSource(val source:Source,val tier:Int,val score:Double,val priority:Int)
    private fun requestedOrder(paths:List<Source>,capability:Capability):List<Source> = paths
        // Snapshot each observation once: other reads can train shared health
        // while this list is being sorted and divided into freshness tiers.
        .map {RequestedSource(it,routeTier(it,capability),learning.score(it,capability,usesTor(it)),pathways.priority(it))}
        .sortedWith(compareBy<RequestedSource> {it.source.role==SourceRole.ORIGINAL}.thenBy {it.tier}.thenBy {it.source.fallbackOnly}.thenBy {it.score}.thenBy {it.priority})
        .groupBy {(it.source.role==SourceRole.ORIGINAL) to it.tier}.flatMap {(tier,group)->
            if(tier.first||tier.second!=1)group else {
                val methods=group.groupBy {it.source.frontend}.values.toList()
                buildList {for(i in 0 until (methods.maxOfOrNull {it.size} ?: 0))for(method in methods)method.getOrNull(i)?.let {add(it)}}
            }
        }.map {it.source}
    /** Same privacy tiers and eligible ordering as a requested read. Only the
     * two leading pipes form a plan; tiny timing noise is not an outage. */
    private fun plan(target: Target,capability: Capability,originalFallback: Boolean): String =
        requestedOrder(sourcesFor(target,capability,originalFallback).filter {it.role!=SourceRole.CACHE&&capability in adapter(it).capabilities&&adapter(it).accepts(target)&&routeAllowed(it,capability)&&learning.eligible(it,capability,usesTor(it))},capability)
            .take(2).joinToString("\n") {"${it.role}|${it.key}"}
    /** A small shortlist of promising paths, never a sweep of the imported pool.
     * A formerly fast path remains eligible for a recovery test after backoff. */
    private fun maintenancePaths(target: Target,capability: Capability,originalFallback: Boolean): List<Source> =
        if(!maintenanceTarget(target,capability))emptyList()else sourcesFor(target,capability,originalFallback)
            .filter {supportsMaintenance(it,capability)&&adapter(it).accepts(target)&&routeAllowed(it,capability)}
            .sortedWith(compareBy<Source> {it.role==SourceRole.ORIGINAL}.thenBy {it.fallbackOnly}.thenBy {learning.score(it,capability,usesTor(it))}.thenBy {pathways.priority(it)}).take(3)
    fun maintenanceDueAt(target: Target,capability: Capability,originalFallback: Boolean): Long? {
        val scope=heartbeatScope(target,capability)
        return maintenancePaths(target,capability,originalFallback).mapNotNull {source->
            heartbeat.nextAllowedAt(scope,listOf(source))?.let {maxOf(it,learning.eligibleAt(source,capability,usesTor(source),target))}
        }.minOrNull()
    }
    /** One bounded, reserved sounding. Foreground work cancels it and never
     * learns cancellation as an outage. Real traffic postpones the next pulse. */
    suspend fun maintain(target: Target,capability: Capability,winner: Source?,originalFallback: Boolean) {
        if(hasActiveReads||!maintenanceTarget(target,capability)||gateway.currentPolicy().let {it.tor&&!it.ready})return
        val scope=heartbeatScope(target,capability);val before=plan(target,capability,originalFallback)
        val candidates=maintenancePaths(target,capability,originalFallback).filter {learning.eligible(it,capability,usesTor(it),target)}
            .sortedWith(compareBy<Source> {it==winner}.thenBy {learning.get(it,capability,usesTor(it))?.lastObservedAt ?: 0L})
        for(source in candidates) {
            val reservation=heartbeat.reserve(scope,source,before,foreground=hasActiveReads) ?: continue
            onHeartbeatChanged()
            try {
                // Persist the consumed request budget before opening a socket.
                onHeartbeatReservation()
                if(hasActiveReads||gateway.currentPolicy().let {it.tor!=scope.tor||it.tor&&!it.ready}) {heartbeat.cancel(reservation);return}
                val result=automaticPulse(source,capability,target,scope)
                if(result==null){heartbeat.cancel(reservation);return}
                val outcome=when {result.detail.contains("health-check budget",true)||AdaptiveRouting.isTargetSpecific(result)->HeartbeatOutcome.INCONCLUSIVE;result.status==HealthStatus.HEALTHY->HeartbeatOutcome.HEALTHY;else->HeartbeatOutcome.FAILED}
                heartbeat.complete(reservation,outcome,plan(target,capability,originalFallback))
            }catch(e: CancellationException){heartbeat.cancel(reservation);throw e}
            catch(e: Exception){heartbeat.cancel(reservation);throw e}
            finally {onHeartbeatChanged()}
            return
        }
    }
    private suspend fun automaticPulse(source: Source,capability: Capability,target: Target,scope: HeartbeatScope): ProbeResult?=coroutineScope {
        // The cancellable child owns the network call. Preempting it leaves the
        // Android worker alive to persist its budget and arrange its next wake.
        val task=async(start=CoroutineStart.LAZY) {
            if(hasActiveReads||gateway.currentPolicy().let {it.tor!=scope.tor||it.tor&&!it.ready})null
            else probe(source,capability,force=true,automatic=true,probeTarget=target)
        }
        if(!backgroundRead.compareAndSet(null,task)){task.cancel();return@coroutineScope null}
        try {task.start();task.await()}
        catch(e: CancellationException){currentCoroutineContext().ensureActive();null}
        finally {backgroundRead.compareAndSet(task,null)}
    }
    suspend fun probe(source: Source, capability: Capability=Capability.TIMELINE, force: Boolean=false,automatic: Boolean=false,probeTarget: Target?=null): ProbeResult = withContext(Dispatchers.IO) {permits.withPermit {
        val tor=usesTor(source);val key=key(source,capability,tor);val old=health[key];val now=clock()
        val transport=if(source.frontend in setOf("bing-rss","duckduckgo"))NetworkRoute.TOR else NetworkRoute.DEFAULT
        val policy=gateway.currentPolicy(transport)
        indexPause(source,capability,probeTarget ?: Target(source.platform,TargetKind.SEARCH,"",source.root))?.let {pause->
            return@withPermit ProbeResult(source,capability,pause.status,0,now,tor,"Known provider cooldown; no request sent",pause.retryAfterMs)
        }
        if(!force && old!=null && now-old.checkedAt<3_600_000)return@withPermit old
        val start=System.nanoTime()
        var feedback:PublicIndexFeedback?=null
        val result=try {
            if(source.frontend in setOf("bing-rss","duckduckgo"))awaitSearchConnection()
            gateway.prepare(if(source.frontend in setOf("bing-rss","duckduckgo"))NetworkRoute.TOR else NetworkRoute.DEFAULT)
            feedback=indexFeedback(source)
            val a=adapter(source);val target=probeTarget ?: a.sentinel(capability)
            if(capability !in a.capabilities||!a.accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"This reader does not implement ${capability.name.lowercase()} for ${source.frontend}")
            val multiple=capability in setOf(Capability.POST,Capability.COMMENTS,Capability.MEDIA)
            val read: suspend ()->Boolean = {
            withTimeout(if(automatic){if(tor)16_000 else 8_000}else if(tor){if(multiple)100_000 else 55_000}else if(multiple)40_000 else 20_000) {
                if(capability==Capability.MEDIA&&source.platform==Platform.YOUTUBE) {
                    val id=YouTubeUrls.videoId(target.canonicalUrl) ?: YouTubeUrls.videoId(a.sentinel(Capability.MEDIA).canonicalUrl) ?: error("No public video sentinel")
                    val selected=YouTubeAdapter(source,gateway,routes).playback(id)
                    YouTubeStreamProof.check(selected.media,selected.sourceUrl,gateway)
                    return@withTimeout true
                }
                var contentTarget=target
                var page=if(capability==Capability.FEED)a.feed(target)else if(capability==Capability.PROFILE)a.profile(target)else if(capability==Capability.COMMENTS)a.comments(target)else a.fetch(target)
                if(redirectedOriginal(source,page))throw SourceFailure(HealthStatus.UNSUPPORTED,ORIGINAL_REDIRECT)
                if(capability in setOf(Capability.POST,Capability.COMMENTS)&&target.kind !in setOf(TargetKind.POST,TargetKind.THREAD)) {
                    val post=page.posts.firstOrNull()?.canonicalUrl?.let {routes.recognize(it)}
                    if(post!=null){page=if(capability==Capability.COMMENTS)a.comments(post)else a.fetch(post);contentTarget=post}
                }
                if(redirectedOriginal(source,page))throw SourceFailure(HealthStatus.UNSUPPORTED,ORIGINAL_REDIRECT)
                when(capability) {
                    Capability.PROFILE -> page.profile!=null
                    Capability.MEDIA -> page.posts.flatMap {it.media}.firstOrNull()?.let {media->MediaFormats.type(gateway.sample(media.preview ?: media.url,target.canonicalUrl))!=null} ?: false
                    Capability.COMMENTS -> content(page,capability,contentTarget)
                    Capability.FEED -> content(page,capability,contentTarget)
                    Capability.SEARCH -> page.posts.isNotEmpty()||page.collections.isNotEmpty()||page.accounts.isNotEmpty()
                    else -> content(page,capability,contentTarget)
                }
            }
            }
            val usable=withIndexFeedback(feedback){if(automatic)gateway.boundedHealthRead {read()}else read()}
            ProbeResult(source,capability,if(usable)HealthStatus.HEALTHY else HealthStatus.EMPTY,(System.nanoTime()-start)/1_000_000,now,tor,if(usable)"Recognizable public data" else "No usable data for this capability")
        }catch(e: CancellationException) { if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();ProbeResult(source,capability,HealthStatus.UNAVAILABLE,0,now,tor,"Timed out")
        }catch(e: Exception){ProbeResult(source,capability,(e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,0,now,tor,e.message.orEmpty().take(180),(e as? SourceFailure)?.retryAfterMs)}
        if(gateway.currentPolicy(transport)!=policy)throw CancellationException("Network mode changed during the check")
        val smoothed=if(result.status==HealthStatus.HEALTHY&&old?.status==HealthStatus.HEALTHY)result.copy(latencyMs=(old.latencyMs*0.7+result.latencyMs*0.3).toLong()) else result
        recordRead(smoothed,feedback,probeTarget,delivery=false)
    }
    }
    suspend fun sweep(platform: Platform, capability: Capability=Capability.TIMELINE,originalFallback: Boolean=true): List<ProbeResult> = coroutineScope { sources(platform,capability,originalFallback).take(128).map { source->async {probe(source,capability,true)} }.awaitAll() }
    suspend fun sweepTarget(target: Target,capability: Capability=Capability.TIMELINE,originalFallback: Boolean=true): List<ProbeResult> = coroutineScope {
        sourcesFor(target,capability,originalFallback).take(128).map {source->async {probe(source,capability,force=true,probeTarget=target)}}.awaitAll()
    }
    fun supportsComments(platform: Platform,originalFallback: Boolean)=sources(platform,Capability.COMMENTS,originalFallback).any {Capability.COMMENTS in adapter(it).capabilities}
    /** An independent, small directory window. Entity results neither consume a
     * post cursor nor certify that the same source can supply a readable feed. */
    suspend fun searchDirectory(target: Target,originalFallback: Boolean=true): Page=withContext(Dispatchers.IO) {
        require(target.kind==TargetKind.SEARCH){"Directory discovery requires a search"}
        if(SearchScopes.from(target)!=null||target.platform==Platform.RSS)return@withContext Page(emptyList())
        readStarted()
        try {
            val selected=searchProvider
            val requestedMode=gateway.currentPolicy().tor
            val originalPermission=gateway.originalFallback
            val retained=java.util.concurrent.atomic.AtomicReference<List<Profile>>(emptyList())
            val retainedProvenance=java.util.concurrent.atomic.AtomicReference<Provenance?>()
            fun samePolicy()=searchProvider==selected&&gateway.currentPolicy().tor==requestedMode&&gateway.originalFallback==originalPermission
            fun checkPolicy(){require(samePolicy()){"Search connection or provider changed; refresh this search"}}
            suspend fun failed(source: Source,error: Exception) {
                if(!samePolicy())return
                if((error as? SourceFailure)?.status==HealthStatus.EMPTY)return
                if(source.frontend in PublicSearchAdapter.frontends&&PublicIndexFeedback.isRefusal(error))
                    record(ProbeResult(source,Capability.SEARCH,(error as SourceFailure).status,0,clock(),usesTor(source),error.message.orEmpty().take(180),error.retryAfterMs),target,delivery=false)
                if(directoryBackoff.size>=128)directoryBackoff.entries.removeIf {it.value<=clock()}
                if(directoryBackoff.size<128)directoryBackoff["${source.key}|${usesTor(source)}"]=clock()+
                    ((error as? SourceFailure)?.retryAfterMs ?: if((error as? SourceFailure)?.status in setOf(HealthStatus.CHALLENGE,HealthStatus.RATE_LIMITED,HealthStatus.LOGIN_REQUIRED))300_000L else 60_000L).coerceIn(30_000L,600_000L)
            }
            val result=withTimeoutOrNull(if(requestedMode)50_000L else 30_000L) {
                gateway.prepare()
                val available=sourcesFor(target,Capability.SEARCH,originalFallback)
                val native=available.filter {when(it.platform) {
                    Platform.FACEBOOK->it.frontend in setOf("phice","original")
                    Platform.REDDIT->it.frontend in setOf("redlib","libreddit","teddit","original")
                    Platform.BLUESKY->it.frontend=="original"
                    Platform.YOUTUBE->it.frontend in setOf("invidious","piped","original")
                    else->false
                }}
                    .sortedWith(compareBy<Source> {it.role==SourceRole.ORIGINAL}.thenBy {learning.score(it,Capability.SEARCH,usesTor(it))})
                val indexes=available.filter {it.frontend in PublicSearchAdapter.frontends&&
                    (if(selected==SearchProvider.DARWIN)it.frontend=="librey"else it.frontend==selected.frontend&&it.root==selected.root)}
                val paths=native.filter {it.role!=SourceRole.ORIGINAL}.take(2)+native.filter {it.role==SourceRole.ORIGINAL}.take(1)+indexes.take(1)
                for(source in paths) {
                    currentCoroutineContext().ensureActive()
                    checkPolicy()
                    if(source.role==SourceRole.ORIGINAL&&!originalFallback)continue
                    if(indexPause(source,Capability.SEARCH,target)!=null)continue
                    val backoffKey="${source.key}|${usesTor(source)}"
                    if((directoryBackoff[backoffKey] ?: 0L)>clock())continue
                    var feedback:PublicIndexFeedback?=null
                    try {
                        val page=withTimeout(if(source.role==SourceRole.ALTERNATE){if(usesTor(source))16_000L else 8_000L}else if(usesTor(source))35_000L else 20_000L) {
                            if(source.frontend in setOf("bing-rss","duckduckgo"))awaitSearchConnection()
                            feedback=indexFeedback(source)
                            withIndexFeedback(feedback){adapter(source).directory(target)}
                        }
                        checkPolicy()
                        if(source.role==SourceRole.ALTERNATE&&page.provenance?.source?.role==SourceRole.ORIGINAL&&!originalFallback)continue
                        val entities=page.accounts.filter {it.target.platform==target.platform&&it.target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY)&&it.name.isNotBlank()}
                            .distinctBy {ContentIdentity.key(it.target.platform,it.target.canonicalUrl)}.take(40)
                        if(entities.isNotEmpty()){directoryBackoff.remove(backoffKey);return@withTimeoutOrNull Page(emptyList(),accounts=entities,provenance=page.provenance)}
                        val destinations=page.collections.map {it.target}.filter {it.platform==target.platform&&it.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY)}
                            .distinctBy {ContentIdentity.key(it.platform,it.canonicalUrl)}.take(8)
                        if(destinations.isEmpty())continue
                        val hydration=Semaphore(2)
                        val hydrated=coroutineScope {destinations.map {destination->async {hydration.withPermit {
                            try {
                                val record=withTimeout(12_000L){load(destination,originalFallback=originalFallback,progress={},requestedCapability=Capability.PROFILE)}.profile
                                checkPolicy()
                                record?.takeIf {it.target.platform==destination.platform&&it.target.kind==destination.kind&&it.name.isNotBlank()&&ContentIdentity.key(it.target.platform,it.target.canonicalUrl)==ContentIdentity.key(destination.platform,destination.canonicalUrl)}?.also {profile->
                                    retainedProvenance.compareAndSet(null,page.provenance)
                                    retained.updateAndGet {known->(known+profile).distinctBy {ContentIdentity.key(it.target.platform,it.target.canonicalUrl)}.take(40)}
                                }
                            }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();null}catch(_: Exception){null}
                        }}}.awaitAll().filterNotNull()}
                        checkPolicy()
                        if(hydrated.isNotEmpty()){directoryBackoff.remove(backoffKey);return@withTimeoutOrNull Page(emptyList(),accounts=hydrated,provenance=page.provenance)}
                    }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();failed(source,e)}
                    catch(e: SourceFailure){feedback?.check(gateway);failed(source,e);if(source.frontend in PublicSearchAdapter.frontends&&PublicIndexFeedback.isRefusal(e))break}
                    catch(e: Exception){feedback?.check(gateway);failed(source,e)}
                }
                Page(emptyList())
            }
            if(!samePolicy())Page(emptyList())else result ?: Page(emptyList(),accounts=retained.get(),provenance=retainedProvenance.get())
        }finally {readFinished()}
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun load(target: Target, feed: Boolean=false, cursor: String?=null, originalFallback: Boolean=true,progress: (String)->Unit=onProgress,requestedCapability: Capability?=null,background: Boolean=false,profileNameOnly: Boolean=false): Page = withContext(Dispatchers.IO) {
        val supplementalJob=if(background)currentCoroutineContext()[Job]else null
        readStarted(background,supplementalJob)
        val capability=requestedCapability ?: when {feed->Capability.FEED;target.kind==TargetKind.MEDIA->Capability.MEDIA;target.kind in setOf(TargetKind.POST,TargetKind.THREAD)->Capability.POST;target.kind==TargetKind.SEARCH->Capability.SEARCH;else->Capability.TIMELINE}
        val requestedMode=gateway.currentPolicy().tor
        val attempted=java.util.concurrent.atomic.AtomicBoolean()
        try {
        require(!profileNameOnly||capability==Capability.PROFILE){"Name lookups require an exact profile"}
        fun usableProfileName(page: Page): Boolean {
            if(!profileNameOnly)return true
            val expected=MentionNames.account(target,routes) ?: return false
            val supplied=page.profile?.takeIf {it.displayNameSupplied} ?: return false
            val actual=MentionNames.account(supplied.target,routes) ?: return false
            return expected.key==actual.key&&MentionNames.sanitizeDisplayName(supplied.name,actual.key)!=null
        }
        // An account result is useful for the people shelf, but must not cancel
        // the routes that can answer the reader's site-wide post search.
        fun directoryOnly(page: Page)=capability==Capability.SEARCH&&target.kind==TargetKind.SEARCH&&SearchScopes.from(target)==null&&
            page.posts.isEmpty()&&page.collections.none {it.target.kind==TargetKind.POST}&&
            (page.accounts.isNotEmpty()||page.collections.any {PublicSearchAdapter.entityAllowed(it.target)})
        fun usableProgress(page: Page)=searchProgress(page,capability,target)&&usableProfileName(page)&&!directoryOnly(page)
        progress("Preparing a secure connection")
        gateway.prepare()
        if(target.platform==Platform.RSS&&target.kind!=TargetKind.FEED)throw SourceFailure(HealthStatus.UNSUPPORTED,"Open the saved feed entry; article pages are not downloaded automatically")
        if(target.kind==TargetKind.FEED)return@withContext externalFeed(Follow(target.canonicalUrl,target,target.identity,feedUrl=target.canonicalUrl))
        val uri=URI(target.canonicalUrl)
        if(originalFallback&&target.platform==Platform.TIKTOK&&(uri.host in setOf("vm.tiktok.com","vt.tiktok.com")||uri.path.startsWith("/t/"))) {
            val expanded=routes.recognize(gateway.resolve(target.canonicalUrl))
            if(expanded!=null&&expanded.platform==target.platform&&expanded.kind==TargetKind.POST&&expanded.canonicalUrl!=target.canonicalUrl)return@withContext load(expanded,feed,cursor,originalFallback,progress)
            throw SourceFailure(HealthStatus.UNAVAILABLE,"This short link did not resolve to a public TikTok post")
        }
        val tor=gateway.currentPolicy().tor
        val now=clock()
        val candidates=requestedOrder(sourcesFor(target,capability).filter {
            it.role!=SourceRole.CACHE&&capability in adapter(it).capabilities&&adapter(it).accepts(target)&&routeAllowed(it,capability)&&learning.eligible(it,capability,usesTor(it),target)
        },capability)
        var last: Exception?=sourcesFor(target,capability,originalFallback).firstNotNullOfOrNull {learning.failure(it,capability,usesTor(it),target)}
        var partial: Page?=null
        var originalRedirect: Page?=null
        fun previewOnly(page: Page)=capability==Capability.POST&&page.posts.firstOrNull {ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}?.preview==true
        fun retainPartial(page: Page) {
            if(profileNameOnly)return
            val candidate=if(capability==Capability.POST)page.copy(posts=page.posts.filter {it.platform==target.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)},profile=null,collections=emptyList(),accounts=emptyList())else page
            if(candidate.posts.isEmpty()&&candidate.profile==null&&candidate.collections.isEmpty()&&candidate.accounts.isEmpty())return
            if(partial==null||previewOnly(candidate)&&partial!!.posts.isEmpty())partial=candidate
            else if(capability==Capability.SEARCH&&directoryOnly(candidate))partial=partial!!.copy(
                accounts=(partial!!.accounts+candidate.accounts).distinctBy {PublicSearchAdapter.entityKey(it.target)},
                collections=(partial!!.collections+candidate.collections).distinctBy {ContentIdentity.key(it.target.platform,it.target.canonicalUrl)})
        }
        fun includeDirectory(page:Page)=if(capability==Capability.SEARCH&&partial!=null)page.copy(
            accounts=(page.accounts+partial!!.accounts).distinctBy {PublicSearchAdapter.entityKey(it.target)},
            collections=(page.collections+partial!!.collections.filter {PublicSearchAdapter.entityAllowed(it.target)}).distinctBy {ContentIdentity.key(it.target.platform,it.target.canonicalUrl)})else page
        val indexes=candidates.filter {it.frontend=="librey"}
        val native=candidates.filter {it.frontend!="librey"}
        fun healthy(source: Source)=routeTier(source,capability)==0
        val indexFallback=capability==Capability.SEARCH&&originalFallback&&searchProvider.torOnly
        val contentFallback=originalFallback&&capability in setOf(Capability.POST,Capability.COMMENTS,Capability.PROFILE,Capability.MEDIA)&&(profileNameOnly||candidates.none(::healthy))
        val groups=if(indexes.isEmpty())listOf(candidates to if(indexFallback||contentFallback){if(tor)8_000L else 4_000L}else if(tor)90_000L else 36_000L)else {
            val nativeGroup=native to if(tor)45_000L else 12_000L
            val indexGroup=indexes to if(tor)45_000L else 24_000L
            if(indexes.any(::healthy)&&native.none(::healthy))listOf(indexGroup,nativeGroup)else listOf(nativeGroup,indexGroup)
        }
        // Keep at most three trials active. A failed latest trial advances one
        // successor immediately; each new trial gets its own hedge interval.
        // A freed slot can reach later paths without awaiting a stalled batch.
        for((group,budget) in groups) {
        val alternate=withTimeoutOrNull(budget) {
            coroutineScope {
                val completed=Channel<Triple<Int,Page?,Exception?>>(3)
                val running=linkedMapOf<Int,Job>()
                var next=0;var latest=-1;var startedAt=0L;var hedgeMs=0L;var advance=true
                fun startNext() {
                    val index=next++;val source=group[index]
                    latest=index;startedAt=System.nanoTime();hedgeMs=learning.hedgeDelay(source,capability,usesTor(source));advance=false
                    running[index]=launch {
                        if(!routeAllowed(source,capability)||!learning.eligible(source,capability,usesTor(source),target)) {
                            completed.send(Triple(index,null,null));return@launch
                        }
                        progress("Trying ${URI(source.root).host}")
                        val started=System.nanoTime()
                        var page: Page?=null;var failure: Exception?=null
                        try {
                            val a=adapter(source)
                            val feedback=indexFeedback(source)
                            attempted.set(true)
                            page=withIndexFeedback(feedback){withTimeout(if(tor)55_000 else 20_000){if(feed)a.feed(target)else if(capability==Capability.PROFILE)a.profile(target)else if(capability==Capability.COMMENTS)a.comments(target,cursor)else a.fetch(target,cursor)}}
                            if(feed&&page.posts.none {it.publishedAt!=null})throw SourceFailure(HealthStatus.EMPTY,"Source has no dated feed entries")
                            val content=content(page,capability,target)&&!previewOnly(page)
                            val redirected=redirectedOriginal(source,page)
                            val r=ProbeResult(source,capability,if(redirected)HealthStatus.UNSUPPORTED else if(content)HealthStatus.HEALTHY else HealthStatus.EMPTY,(System.nanoTime()-started)/1_000_000,clock(),tor,if(redirected)ORIGINAL_REDIRECT else if(content)"Requested public content extracted"else if(previewOnly(page))"Public preview only; full content unavailable"else "Profile information only; no timeline entries")
                            recordRead(r,feedback,target,delivery=!redirected)
                        }catch(e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            page=null;failure=e
                            if(e is TimeoutCancellationException)record(ProbeResult(source,capability,HealthStatus.UNAVAILABLE,0,clock(),tor,"Timed out"),target)
                        }catch(e: Exception) {
                            page=null;failure=e
                            val r=ProbeResult(source,capability,(e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,0,clock(),tor,e.message.orEmpty().take(180),(e as? SourceFailure)?.retryAfterMs)
                            record(r,target)
                        }
                        completed.send(Triple(index,page,failure))
                    }
                }
                try {
                    while(next<group.size||running.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        if(running.isEmpty()) {startNext();continue}
                        val canStart=next<group.size&&running.size<3
                        val remainingNanos=hedgeMs*1_000_000-(System.nanoTime()-startedAt)
                        // Select atomically between a completion and its hedge
                        // timer. A buffered completion beats even an overdue
                        // timer, avoiding a needless new request beside a winner.
                        val result=if(canStart)select<Triple<Int,Page?,Exception?>?> {
                            completed.onReceive {it}
                            onTimeout(if(advance)0 else ((remainingNanos+999_999)/1_000_000).coerceAtLeast(0)){null}
                        }else completed.receive()
                        if(result==null)startNext() else {
                            val (index,page,failure)=result;running.remove(index)
                            if(failure is CancellationException&&failure !is TimeoutCancellationException)throw failure
                            if(failure!=null)last=failure
                            if(page!=null) {
                                if(page.provenance?.source?.role==SourceRole.ORIGINAL) {
                                    if(originalFallback&&usableProgress(page)&&!previewOnly(page))originalRedirect=originalRedirect ?: page
                                    else if(originalFallback)retainPartial(page)
                                }else {
                                    if(usableProgress(page)&&!previewOnly(page))return@coroutineScope page
                                    retainPartial(page)
                                }
                            }
                            if(index==latest)advance=true
                        }
                    }
                    null
                }finally {running.values.forEach {it.cancel()};completed.cancel()}
            }
        }
        if(alternate!=null) {
            return@withContext delivered(target,capability,includeDirectory(alternate),originalFallback)
        }
        }
        if(originalFallback&&originalRedirect!=null)return@withContext delivered(target,capability,includeDirectory(originalRedirect!!),originalFallback)
        if(originalFallback)for(source in fallbackSources(target.platform,capability,target).let {paths->if(capability==Capability.SEARCH)paths else paths.sortedWith(compareBy<Source> {learning.score(it,capability,usesTor(it))}.thenBy {pathways.priority(it)})}) {
            val a=adapter(source);val started=System.nanoTime()
            var feedback:PublicIndexFeedback?=null
            val originalTor=usesTor(source)
            val old=health[key(source,capability,originalTor)]
            if(!learning.eligible(source,capability,originalTor,target))continue
            if(indexFallback&&source.frontend=="original"&&target.platform!=Platform.FACEBOOK&&!partial?.accounts.isNullOrEmpty())continue
            if(indexFallback&&source.frontend=="original"&&old?.status in setOf(HealthStatus.UNAVAILABLE,HealthStatus.UNSUPPORTED,HealthStatus.LOGIN_REQUIRED,HealthStatus.CHALLENGE)&&now-(old?.checkedAt ?: 0)<600_000)continue
            progress(if(source.frontend in PublicSearchAdapter.frontends)"Searching ${searchProvider.label} through Tor"else "Trying public original data")
            suspend fun report(status: HealthStatus,detail: String,retryAfterMs: Long?=null) {
                val result=ProbeResult(source,capability,status,(System.nanoTime()-started)/1_000_000,clock(),originalTor,detail.take(180),retryAfterMs)
                record(result,target)
            }
            try {
                if(capability !in a.capabilities||!a.accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"This original reader does not implement ${capability.name.lowercase()}")
                if(source.frontend in setOf("bing-rss","duckduckgo"))awaitSearchConnection()
                feedback=indexFeedback(source)
                attempted.set(true)
                val page=withIndexFeedback(feedback){withTimeout(if(indexFallback&&source.frontend=="original"){if(target.platform==Platform.FACEBOOK){if(originalTor)40_000 else 20_000}else if(originalTor)8_000 else 4_000}else if(originalTor)55_000 else 20_000){
                if(feed)a.feed(target)else if(capability==Capability.PROFILE)a.profile(target)else if(capability==Capability.COMMENTS)a.comments(target,cursor)else try {a.fetch(target,cursor)}catch(e: CancellationException){throw e}catch(e: Exception){
                    e.respectRetryDelay()
                    if(source.frontend !in PublicSearchAdapter.frontends&&cursor==null&&(target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY)||target.kind==TargetKind.SEARCH&&target.platform==Platform.REDDIT)&&target.platform in setOf(Platform.REDDIT,Platform.BLUESKY,Platform.TUMBLR,Platform.PINTEREST))a.feed(target)else throw e
                }}
                }
                if(feed&&page.posts.none {it.publishedAt!=null})throw SourceFailure(HealthStatus.EMPTY,"Original has no dated feed entries")
                val content=content(page,capability,target)&&!previewOnly(page)
                recordRead(ProbeResult(source,capability,if(content)HealthStatus.HEALTHY else HealthStatus.EMPTY,(System.nanoTime()-started)/1_000_000,clock(),originalTor,if(content)"Requested public content extracted"else if(usableProgress(page))"No matching content on this index page; continuation available"else "Profile information only; no timeline entries"),feedback,target)
                if(!usableProgress(page)) {
                    // Keep a real preview/profile for a possible partial result,
                    // but exhaust eligible formats/providers before returning it.
                    retainPartial(page)
                    continue
                }
                return@withContext delivered(target,capability,includeDirectory(page),originalFallback)
            }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();last=e;report(HealthStatus.UNAVAILABLE,"Timed out")}
            catch(e: Exception){last=e;report((e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,e.message.orEmpty(),(e as? SourceFailure)?.retryAfterMs)}
        }
        // Independent archives are a recovery tier. They cannot win a race with
        // fresh content or confirm that a chronological subscription refreshed.
        if(capability!=Capability.FEED&&!profileNameOnly) {
            val cached=withTimeoutOrNull(if(tor)55_000L else 24_000L) {
                for(source in sourcesFor(target,capability).filter {it.role==SourceRole.CACHE&&capability in adapter(it).capabilities&&adapter(it).accepts(target)&&routeAllowed(it,capability)&&learning.eligible(it,capability,usesTor(it),target)}.sortedBy {learning.score(it,capability,usesTor(it))}) {
                    val started=System.nanoTime()
                    progress("Checking an independent archive")
                    try {
                        attempted.set(true)
                        val page=withTimeout(if(tor)25_000 else 10_000){if(capability==Capability.COMMENTS)adapter(source).comments(target,cursor)else adapter(source).fetch(target,cursor)}
                        val usable=content(page,capability,target)&&!previewOnly(page)
                        record(ProbeResult(source,capability,if(usable)HealthStatus.HEALTHY else HealthStatus.EMPTY,(System.nanoTime()-started)/1_000_000,clock(),tor,if(usable)"Independent archive content extracted"else "Archive has no matching content"),target,delivery=false)
                        if(usable)return@withTimeoutOrNull page.copy(provenance=(page.provenance ?: Provenance(source,clock(),source.root)).copy(cached=true),notice=page.notice ?: "Independent archive copy; this may not reflect the current post.")
                    }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();last=e;record(ProbeResult(source,capability,HealthStatus.UNAVAILABLE,0,clock(),tor,"Timed out"),target,delivery=false)}
                    catch(e: Exception){last=e;record(ProbeResult(source,capability,(e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,0,clock(),tor,e.message.orEmpty().take(180),(e as? SourceFailure)?.retryAfterMs),target,delivery=false)}
                }
                null
            }
            if(cached!=null)return@withContext cached
        }
        partial?.let {return@withContext if(target.kind==TargetKind.SEARCH&&it.accounts.isNotEmpty()&&last!=null)it.copy(notice="Public accounts are available; posts could not be loaded on this connection.")else it}
        if(target.kind==TargetKind.SEARCH) {
            val failure=last as? SourceFailure
            throw SourceFailure(failure?.status ?: HealthStatus.UNAVAILABLE,"Public ${target.platform.label} search is unavailable. ${failure?.message ?: "Try an @username or a direct link; see source details for access restrictions or connection failures."}",failure?.retryAfterMs,failure?.httpStatus)
        }
        // A provider timeout is a failed read, not cancellation by the caller.
        // Let the native reader report it and leave its loading state.
        throw if(last is TimeoutCancellationException||last is java.io.InterruptedIOException)SourceFailure(HealthStatus.UNAVAILABLE,"Public sources timed out; try another source or connection")
            else last ?: SourceFailure(HealthStatus.UNAVAILABLE,"No working public data source")
        }catch(e: CancellationException){throw e}
        catch(e: Exception) {
            if(!profileNameOnly&&attempted.get()&&maintenanceTarget(target,capability)&&gateway.currentPolicy().let {it.tor==requestedMode&&(!it.tor||it.ready)}) {
                val reason=ProbeResult(Source(target.platform,"requested",target.platform.origin),capability,(e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,0,clock(),requestedMode,e.message.orEmpty())
                if(AdaptiveRouting.isTargetSpecific(reason))heartbeat.observePlan(heartbeatScope(target,capability),plan(target,capability,originalFallback))
                else heartbeat.observeRead(heartbeatScope(target,capability),null,plan(target,capability,originalFallback),successful=false)
                onHeartbeatChanged()
            }
            throw e
        }finally {readFinished(background,supplementalJob)}
    }
    /** Continuations belong to the source that issued them, never a failover engine. */
    suspend fun continuePage(target: Target,previous: Page,originalFallback: Boolean=true,requestedCapability: Capability?=null): Page=withContext(Dispatchers.IO) {
        readStarted()
        try {
        val source=requireNotNull(previous.provenance?.source){"Missing pagination source"}
        val cursor=requireNotNull(previous.next){"No further page"}
        val selected=searchProvider;val originalPermission=gateway.originalFallback
        require(source.platform==target.platform){"Pagination changed platform"}
        if(source.role==SourceRole.ORIGINAL&&!originalFallback)throw SourceFailure(HealthStatus.UNAVAILABLE,"Original-site access is disabled")
        if(source.frontend in PublicSearchAdapter.frontends) {
            require(if(selected==SearchProvider.DARWIN)source.frontend=="librey" else source.frontend==selected.frontend&&source.root==selected.root){"Search provider changed; refresh this search"}
        }
        val known=source in routes.sources||source in pathways.sources(target)||source in routes.searchSources.map {it.copy(platform=target.platform)}||source in fallbackSources(target.platform,if(target.kind==TargetKind.SEARCH)Capability.SEARCH else Capability.TIMELINE,target)
        require(known){"Unrecognized pagination source"}
        val capability=requestedCapability ?: if(target.kind==TargetKind.SEARCH)Capability.SEARCH else if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD))Capability.POST else Capability.TIMELINE
        indexPause(source,capability,target)?.let {throw it}
        if(source.frontend in setOf("bing-rss","duckduckgo"))awaitSearchConnection()
        val updates=currentCoroutineContext()[SearchDiscoveryUpdates]?.takeIf {it.target==target&&capability==Capability.SEARCH&&source.frontend in PublicSearchAdapter.frontends}
        val session=gateway.sessionFor(source.root)
        fun checkPolicy() {
            if(gateway.sessionFor(source.root)!==session||source.frontend in PublicSearchAdapter.frontends&&searchProvider!=selected||gateway.originalFallback!=originalPermission)
                throw CancellationException("Search connection or provider changed; refresh this search")
        }
        checkPolicy()
        val feedback=indexFeedback(source)
        val started=System.nanoTime()
        var firstDelivery:Long?=null
        try {
            val page=withIndexFeedback(feedback){withTimeout(if(usesTor(source))55_000 else 20_000){withContext(SearchContinuationPolicy(::checkPolicy)){
                if(updates!=null)adapter(source).searchUpdates(target,cursor){partial->
                    checkPolicy()
                    require(partial.provenance?.source==source){"Search update changed its selected source"}
                    require(partial.next==null||partial.next.isNotBlank()&&partial.next.length<=24*1024){"Invalid search update checkpoint"}
                    if(firstDelivery==null&&content(partial,capability,target))firstDelivery=(System.nanoTime()-started)/1_000_000
                    updates.publish(partial,::checkPolicy)
                    checkPolicy()
                }else if(capability==Capability.COMMENTS)adapter(source).comments(target,cursor)else adapter(source).fetch(target,cursor)
            }}}
            checkPolicy()
            val usable=content(page,capability,target)
            if(usable||feedback?.refusal!=null)recordRead(ProbeResult(source,capability,if(usable)HealthStatus.HEALTHY else HealthStatus.EMPTY,firstDelivery ?: (System.nanoTime()-started)/1_000_000,clock(),usesTor(source),"Continuation data checked"),feedback,delivery=source.role!=SourceRole.CACHE)
            delivered(target,capability,page,originalFallback)
        }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();record(ProbeResult(source,capability,HealthStatus.UNAVAILABLE,(System.nanoTime()-started)/1_000_000,clock(),usesTor(source),"Timed out"));throw SourceFailure(HealthStatus.UNAVAILABLE,"Public source timed out")}catch(e: Exception){if((e as? SourceFailure)?.status!=HealthStatus.EMPTY)record(ProbeResult(source,capability,(e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,(System.nanoTime()-started)/1_000_000,clock(),usesTor(source),e.message.orEmpty().take(180),(e as? SourceFailure)?.retryAfterMs));throw e}
        }finally {readFinished()}
    }
    suspend fun externalFeed(follow: Follow): Page = withContext(Dispatchers.IO) {
        readStarted()
        try {
        val url=follow.feedUrl ?: return@withContext load(follow.target,true)
        if(follow.target.platform==Platform.RSS) {
            val feed=RssSources.target(url)
            return@withContext RssSources.parse(gateway.text(feed.canonicalUrl,feed.canonicalUrl),feed,clock())
        }
        val root=URI(url).let { "${it.scheme}://${it.host}" }
        val source=Source(follow.target.platform,"rss",root)
        Extraction.parse(gateway.text(url,follow.target.canonicalUrl),follow.target,source,routes)
        }finally {readFinished()}
    }
    private fun readStarted(background: Boolean=false,job: Job?=null) {
        activeReads.incrementAndGet()
        if(background){
            job?.let {supplementalReads.add(it);if(hasForegroundReads)it.cancel(CancellationException("Foreground read takes priority"))}
        }else {
            foregroundReads.incrementAndGet()
            supplementalReads.forEach {it.cancel(CancellationException("Foreground read takes priority"))}
            backgroundRead.get()?.cancel(CancellationException("Foreground read takes priority"))
        }
    }
    private fun readFinished(background: Boolean=false,job: Job?=null) {
        activeReads.decrementAndGet()
        if(background)job?.let {supplementalReads.remove(it)}else foregroundReads.decrementAndGet()
    }
    companion object {private const val ORIGINAL_REDIRECT="Route redirects to original data"}
}
