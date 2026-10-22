package app.antisocial.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Read-only, exact-URL archive discovery. No archive submission or remote JavaScript.
 * Protocol references: Internet Archive wayback-cdx-server README and Arquivo.pt
 * CDX API documentation. Parsers are independently implemented for AntiSocial.
 */
class ArchiveReader internal constructor(
    private val gateway: NetworkGateway,
    private val routes: DarwinRoutes,
    private val providers: List<ArchiveProvider>,
    private val clock: () -> Long
) {
    constructor(gateway: NetworkGateway,routes: DarwinRoutes): this(gateway,routes,ArchiveProvider.defaults,System::currentTimeMillis)
    private val lock=Mutex()
    private data class Pause(val until: Long,val status: HealthStatus,val detail: String)
    private val pauses=mutableMapOf<String,Pause>()

    /** Earliest usable capture among this bounded lookup, never a proof of a first
     * edition or of unchanged intermediate captures. A miss is deliberately quiet. */
    suspend fun oldest(post: Post): ArchivedVersion? {
        routes.recognize(post.canonicalUrl,post.platform)?.takeIf {it.platform==post.platform&&it.kind in setOf(TargetKind.POST,TargetKind.THREAD)}
            ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"Archive history requires an exact public post")
        if(post.preview||post.parentId!=null||!ContentIdentity.key(post.platform,post.canonicalUrl).startsWith("post:"))throw SourceFailure(HealthStatus.UNSUPPORTED,"Archive history requires a complete original post")
        if(!lock.tryLock())throw SourceFailure(HealthStatus.UNAVAILABLE,"An archive lookup is already in progress")
        try {
            val candidates=mutableListOf<ArchiveCapture>()
            val failures=mutableListOf<SourceFailure>()
            var validIndexes=0
            val selected=providers.take(2)
            val indexUrl=ArchiveParsing.indexOriginal(post)
            for(provider in selected) {
                val pause=pauses[provider.id]
                if(pause!=null&&pause.until>clock()) {failures+=SourceFailure(pause.status,pause.detail);continue}
                // Per-provider spacing is independent of any UI/cache policy.
                pauses[provider.id]=Pause(clock()+60_000,HealthStatus.UNAVAILABLE,"Archive lookup is cooling down")
                try {
                    val payload=withTimeout(if(gateway.currentPolicy().tor)25_000L else 12_000L) {
                        gateway.text(provider.indexUrl(indexUrl),maxBytes=64L*1024,allowedOrigins=setOf(provider.origin))
                    }
                    if(!provider.sameOrigin(payload.finalUrl))throw SourceFailure(HealthStatus.UNAVAILABLE,"Archive index left its provider")
                    ArchiveParsing.wall(payload.text)?.let {throw SourceFailure(it,"Archive requires a browser or account")}
                    candidates+=ArchiveParsing.checkedIndex(payload,provider,post,clock())
                    validIndexes++
                }catch(e: TimeoutCancellationException){currentCoroutineContext().ensureActive();failures+=backoff(provider,e)}
                catch(e: CancellationException){throw e}
                catch(e: Exception){failures+=backoff(provider,e)}
            }
            // Two index requests and at most two small replay bodies. A broken
            // capture cannot cause an unbounded historical scan.
            val blockedReplayProviders=mutableSetOf<String>()
            for(capture in candidates.distinctBy {it.provider.id+it.timestamp+it.original}.sortedBy {it.capturedAt}.take(2)) {
                if(capture.provider.id in blockedReplayProviders)continue
                try {
                    val payload=withTimeout(if(gateway.currentPolicy().tor)40_000L else 18_000L) {
                        gateway.text(capture.replayUrl,maxBytes=1024L*1024,allowedOrigins=setOf(capture.provider.origin))
                    }
                    ArchiveParsing.wall(payload.text)?.let {throw SourceFailure(it,"Archive requires a browser or account")}
                    val version=ArchiveParsing.replay(payload,capture,post,routes,clock())
                    if(version!=null)return version
                    failures+=SourceFailure(HealthStatus.UNAVAILABLE,"An archived capture did not contain a complete matching post")
                }catch(e: TimeoutCancellationException){currentCoroutineContext().ensureActive();failures+=backoff(capture.provider,e);blockedReplayProviders+=capture.provider.id}
                catch(e: CancellationException){throw e}
                catch(e: Exception){failures+=backoff(capture.provider,e);blockedReplayProviders+=capture.provider.id}
            }
            // A negative result is confirmed only when every configured index
            // returned valid empty data. Incomplete coverage remains retryable.
            if(failures.isNotEmpty())throw failures.minBy {when(it.status){HealthStatus.RATE_LIMITED->0;HealthStatus.CHALLENGE->1;HealthStatus.LOGIN_REQUIRED->2;else->3}}
            if(selected.isEmpty()||validIndexes!=selected.size||candidates.isNotEmpty())throw SourceFailure(HealthStatus.UNAVAILABLE,"Archive coverage could not be confirmed")
            return null
        }finally {lock.unlock()}
    }

    /** Recover an unavailable exact permalink from the latest readable capture
     * found in this bounded lookup. This is not a live post, an edit comparison,
     * or a claim to have searched every archive for the globally newest capture.
     * Recent unreadable shells allow a bounded attempt at the oldest captures.
     * A provider's successful replay returns immediately; media-only CDN links
     * require a binary validation path and are deliberately unsupported here.
     */
    suspend fun recover(target: Target): ArchivedVersion? {
        val recognized=routes.recognize(target.canonicalUrl,target.platform)?.takeIf {
            target.kind in setOf(TargetKind.POST,TargetKind.THREAD,TargetKind.MEDIA)&&
                it.platform==target.platform&&it.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&
                ContentIdentity.key(it.platform,it.canonicalUrl).startsWith("post:")
        } ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"Archive recovery requires an exact public post link")
        // Identity only: this placeholder never leaves the parser or becomes a
        // native card. Returned text/media must come from a matching replay.
        val identity=Post(ContentIdentity.key(recognized.platform,recognized.canonicalUrl),recognized.platform,recognized.canonicalUrl,"","")
        if(!lock.tryLock())throw SourceFailure(HealthStatus.UNAVAILABLE,"An archive lookup is already in progress")
        try {
            val selected=providers.take(2)
            val failures=mutableListOf<SourceFailure>()
            var validEmptyIndexes=0
            var indexes=0
            var replays=0
            val historical=mutableListOf<ArchiveProvider>()
            val attempted=mutableSetOf<String>()
            val blocked=mutableSetOf<String>()
            val original=ArchiveParsing.indexOriginal(identity)
            fun captureKey(capture: ArchiveCapture)=capture.provider.id+"|"+capture.timestamp+"|"+capture.original
            suspend fun index(provider: ArchiveProvider,latest: Boolean): List<ArchiveCapture>? {
                currentCoroutineContext().ensureActive()
                if(indexes>=4)return null
                indexes++
                return try {
                    val payload=withTimeout(if(gateway.currentPolicy().tor)25_000L else 12_000L) {
                        gateway.text(provider.indexUrl(original,latest),maxBytes=64L*1024,allowedOrigins=setOf(provider.origin))
                    }
                    if(!provider.sameOrigin(payload.finalUrl))throw SourceFailure(HealthStatus.UNAVAILABLE,"Archive index left its provider")
                    ArchiveParsing.wall(payload.text)?.let {throw SourceFailure(it,"Archive requires a browser or account")}
                    ArchiveParsing.checkedIndex(payload,provider,identity,clock())
                }catch(e: TimeoutCancellationException){currentCoroutineContext().ensureActive();failures+=backoff(provider,e);blocked+=provider.id;null}
                catch(e: CancellationException){throw e}
                catch(e: Exception){failures+=backoff(provider,e);blocked+=provider.id;null}
            }
            suspend fun read(capture: ArchiveCapture): ArchivedVersion? {
                currentCoroutineContext().ensureActive()
                if(replays>=4||!attempted.add(captureKey(capture)))return null
                replays++
                return try {
                    val payload=withTimeout(if(gateway.currentPolicy().tor)40_000L else 18_000L) {
                        gateway.text(capture.replayUrl,maxBytes=1024L*1024,allowedOrigins=setOf(capture.provider.origin))
                    }
                    ArchiveParsing.wall(payload.text)?.let {throw SourceFailure(it,"Archive requires a browser or account")}
                    if(!ArchiveParsing.matchingReplay(payload,capture,identity))throw SourceFailure(HealthStatus.UNAVAILABLE,"Archive replay left its selected post")
                    ArchiveParsing.replay(payload,capture,identity,routes,clock()).also {
                        if(it==null)failures+=SourceFailure(HealthStatus.UNAVAILABLE,"An archived capture did not contain a complete matching post")
                    }
                }catch(e: TimeoutCancellationException){currentCoroutineContext().ensureActive();failures+=backoff(capture.provider,e);blocked+=capture.provider.id;null}
                catch(e: CancellationException){throw e}
                catch(e: Exception){failures+=backoff(capture.provider,e);blocked+=capture.provider.id;null}
            }
            // First give each archive its newest replay. Recent social captures
            // can be script-only shells even when a much older capture is readable.
            for(provider in selected) {
                currentCoroutineContext().ensureActive()
                val pause=pauses[provider.id]
                if(pause!=null&&pause.until>clock()) {failures+=SourceFailure(pause.status,pause.detail);continue}
                pauses[provider.id]=Pause(clock()+60_000,HealthStatus.UNAVAILABLE,"Archive lookup is cooling down")
                val captures=index(provider,latest=true) ?: continue
                if(captures.isEmpty()){validEmptyIndexes++;continue}
                read(captures.maxBy {it.capturedAt})?.let {return it}
                if(provider.id !in blocked)historical+=provider
            }
            // Only a matching but unreadable replay justifies an oldest lookup.
            // No historical retries follow transport errors, walls or rate limits.
            // Across both providers: at most four 64 KiB indexes and four 1 MiB bodies.
            for(provider in historical) {
                currentCoroutineContext().ensureActive()
                if(provider.id in blocked||replays>=4)continue
                val capture=index(provider,latest=false)?.sortedBy {it.capturedAt}?.firstOrNull {captureKey(it) !in attempted} ?: continue
                read(capture)?.let {return it}
            }
            if(failures.isNotEmpty())throw failures.minBy {when(it.status){HealthStatus.RATE_LIMITED->0;HealthStatus.CHALLENGE->1;HealthStatus.LOGIN_REQUIRED->2;else->3}}
            if(selected.isEmpty()||validEmptyIndexes!=selected.size)throw SourceFailure(HealthStatus.UNAVAILABLE,"Archive coverage could not be confirmed")
            return null
        }finally {lock.unlock()}
    }
    private fun backoff(provider: ArchiveProvider,error: Exception): SourceFailure {
        val failure=error as? SourceFailure ?: SourceFailure(HealthStatus.UNAVAILABLE,"Archive lookup is temporarily unavailable")
        val long=failure.status in setOf(HealthStatus.RATE_LIMITED,HealthStatus.CHALLENGE,HealthStatus.LOGIN_REQUIRED)
        pauses[provider.id]=Pause(clock()+if(long)24L*60*60*1000 else 60L*60*1000,failure.status,failure.message.orEmpty())
        return failure
    }
}

internal data class ArchiveProvider(val id: String,val label: String,val origin: String,val indexPath: String,val replayPath: String,val ndjson: Boolean=false) {
    fun sameOrigin(url: String): Boolean=url.toHttpUrlOrNull()?.let {it.scheme+"://"+it.host+(if(it.port==443&&it.isHttps||it.port==80&&!it.isHttps)""else ":${it.port}")}==origin
    // Do not ask the server to find distinct digests: obtaining two changes can
    // scan thousands of identical captures. Fetch two raw oldest rows instead.
    fun indexUrl(original: String,latest: Boolean=false): String=(origin+indexPath).toHttpUrlOrNull()!!.newBuilder()
        .addQueryParameter("url",original).addQueryParameter("matchType","exact").addQueryParameter("limit",if(latest&&!ndjson)"-2"else "2").addQueryParameter("output","json")
        .apply {
            if(ndjson){addQueryParameter("fields","timestamp,url,status,mime");addQueryParameter("filter","=status:200");if(latest)addQueryParameter("sort","reverse")}
            else {addQueryParameter("fl","timestamp,original,statuscode,mimetype");addQueryParameter("filter","statuscode:200");if(latest)addQueryParameter("fastLatest","true")}
        }
        .build().toString()
    fun replay(timestamp: String,original: String)=origin+replayPath+timestamp+"id_/"+original
    companion object {
        val defaults=listOf(
            ArchiveProvider("wayback","Internet Archive","https://web.archive.org","/cdx/search/cdx","/web/"),
            ArchiveProvider("arquivo","Arquivo.pt","https://arquivo.pt","/wayback/cdx","/noFrame/replay/",true)
        )
    }
}
internal data class ArchiveCapture(val provider: ArchiveProvider,val timestamp: String,val original: String,val capturedAt: Long) {val replayUrl get()=provider.replay(timestamp,original)}

internal object ArchiveParsing {
    private val timestampFormat=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    private fun stamp(value: String): Long?=if(!value.matches(Regex("[0-9]{14}")))null else runCatching {LocalDateTime.parse(value,timestampFormat).toInstant(ZoneOffset.UTC).toEpochMilli()}.getOrNull()
    private fun string(value: JsonObject,vararg names: String)=names.firstNotNullOfOrNull {value[it]?.takeIf(JsonElement::isJsonPrimitive)?.asString}.orEmpty()
    fun indexOriginal(post: Post): String {
        val url=post.canonicalUrl.toHttpUrlOrNull() ?: return post.canonicalUrl
        return url.newBuilder().fragment(null).apply {if(post.platform==Platform.X)host("twitter.com")}.build().toString()
    }
    private fun original(url: String): String? {
        val parsed=url.toHttpUrlOrNull() ?: return null
        if(parsed.username.isNotEmpty()||parsed.password.isNotEmpty()||parsed.port !in setOf(80,443))return null
        return parsed.newBuilder().scheme("https").port(443).fragment(null).build().toString()
    }
    private fun matches(url: String,post: Post): Boolean {
        val clean=original(url) ?: return false
        val parsed=runCatching {URI(clean)}.getOrNull() ?: return false
        val postUri=runCatching {URI(post.canonicalUrl)}.getOrNull() ?: return false
        val expectedHost=postUri.host?.removePrefix("www.") ?: return false
        val actualHost=parsed.host?.removePrefix("www.") ?: return false
        // A coincidental /status/20 on an unrelated host is not the same post.
        val aliases=if(post.platform==Platform.X)setOf("x.com","twitter.com","mobile.twitter.com","m.twitter.com")else setOf(expectedHost)
        return actualHost in aliases&&ContentIdentity.key(post.platform,clean)==ContentIdentity.key(post.platform,post.canonicalUrl)
    }
    fun index(body: String,provider: ArchiveProvider,post: Post,now: Long): List<ArchiveCapture> {
        if(body.length>64*1024)return emptyList()
        val rows=mutableListOf<JsonObject>()
        runCatching {
            if(provider.ndjson)body.lineSequence().filter(String::isNotBlank).take(3).forEach {line->JsonParser.parseString(line).takeIf(JsonElement::isJsonObject)?.asJsonObject?.let(rows::add)}
            else {
                val array=JsonParser.parseString(body).asJsonArray
                val keys=array.firstOrNull()?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.map {it.asString}.orEmpty()
                array.drop(1).take(2).forEach {row->if(row.isJsonArray&&row.asJsonArray.size()==keys.size)rows+=JsonObject().apply {keys.forEachIndexed {i,key->add(key,row.asJsonArray[i])}}}
            }
        }
        return rows.mapNotNull {row->
            val timestamp=string(row,"timestamp");val at=stamp(timestamp) ?: return@mapNotNull null
            val url=string(row,"original","url")
            val status=string(row,"statuscode","status");val mime=string(row,"mimetype","mime")
            if(status!="200"||mime.substringBefore(';').lowercase() !in setOf("text/html","application/xhtml+xml","application/json")||!matches(url,post)||at<631152000000L||at>now||post.publishedAt?.let {at<it-24*60*60*1000L}==true)return@mapNotNull null
            ArchiveCapture(provider,timestamp,url,at)
        }.distinctBy {it.timestamp+it.original}.sortedBy {it.capturedAt}.take(2)
    }
    /** Empty is meaningful only inside the documented index protocol. An HTML
     * shell or malformed/nonmatching row must not become a multi-day negative. */
    fun checkedIndex(payload: Payload,provider: ArchiveProvider,post: Post,now: Long): List<ArchiveCapture> {
        fun invalid(): Nothing=throw SourceFailure(HealthStatus.UNAVAILABLE,"Archive did not return a valid matching index")
        if(payload.status!=200||payload.text.length>64*1024||payload.contentType.contains("html",true))invalid()
        val body=payload.text.trim()
        if(provider.ndjson&&body.isEmpty())return emptyList()
        try {
            if(provider.ndjson) {
                val lines=body.lineSequence().filter(String::isNotBlank).toList()
                if(lines.size>2)invalid()
                lines.forEach {line->
                    val value=JsonParser.parseString(line)
                    if(!value.isJsonObject||setOf("timestamp","url","status","mime").any {value.asJsonObject[it]?.isJsonPrimitive!=true}||index(line,provider,post,now).isEmpty())invalid()
                }
            }else {
                val value=JsonParser.parseString(body)
                if(!value.isJsonArray)invalid()
                val array=value.asJsonArray
                if(array.size()==0)return emptyList()
                val header=array.first().takeIf {it.isJsonArray}?.asJsonArray ?: invalid()
                val keys=header.map {if(!it.isJsonPrimitive)invalid()else it.asString}
                if(!keys.containsAll(setOf("timestamp","original","statuscode","mimetype"))||array.size()>3)invalid()
                array.drop(1).forEach {row->
                    if(!row.isJsonArray||row.asJsonArray.size()!=keys.size||row.asJsonArray.any {!it.isJsonPrimitive})invalid()
                    val single=com.google.gson.JsonArray().apply {add(header);add(row)}
                    if(index(single.toString(),provider,post,now).isEmpty())invalid()
                }
            }
        }catch(e: SourceFailure){throw e}
        catch(e: Exception){invalid()}
        return index(body,provider,post,now)
    }
    private data class ReplayUrl(val timestamp: String,val original: String)
    private fun replayUrl(url: String,provider: ArchiveProvider): ReplayUrl? {
        if(!provider.sameOrigin(url))return null
        val raw=url.substringAfter(provider.origin+provider.replayPath,"")
        val timestamp=raw.substringBefore('/').take(14)
        if(stamp(timestamp)==null)return null
        val modifier=raw.substringBefore('/').drop(14)
        if(!modifier.matches(Regex("(?:[a-z]{1,4}_)?")))return null
        val original=raw.substringAfter('/',"").takeIf {it.startsWith("http://")||it.startsWith("https://")} ?: return null
        return ReplayUrl(timestamp,original)
    }
    private fun unwrap(value: String,base: String,provider: ArchiveProvider): String? {
        val resolved=runCatching {URI(base).resolve(value.trim()).toString()}.getOrNull() ?: return null
        return replayUrl(resolved,provider)?.original?.let(::original) ?: original(resolved)
    }
    /** All artwork is pinned to its capture's archive, even if the captured page
     * refers to an original CDN. It must never cause a live-original fallback. */
    private fun mediaUrl(value: String,base: String,capture: ArchiveCapture): String? {
        if(value.isBlank())return null
        val resolved=runCatching {URI(base).resolve(value.trim()).toString()}.getOrNull() ?: return null
        val old=replayUrl(resolved,capture.provider)
        val url=old?.original ?: resolved
        val valid=original(url) ?: return null
        return capture.provider.replay(old?.timestamp ?: capture.timestamp,valid)
    }
    fun matchingReplay(payload: Payload,capture: ArchiveCapture,current: Post): Boolean =
        replayUrl(payload.finalUrl,capture.provider)?.let {matches(it.original,current)}==true
    fun replay(payload: Payload,capture: ArchiveCapture,current: Post,routes: DarwinRoutes,now: Long): ArchivedVersion? {
        val replay=replayUrl(payload.finalUrl,capture.provider) ?: return null
        val capturedAt=stamp(replay.timestamp) ?: return null
        if(!matches(replay.original,current)||capturedAt>(current.provenance?.fetchedAt ?: now)||capturedAt>now||payload.status!=200||payload.text.length>1024*1024)return null
        val document=Jsoup.parse(payload.text,original(replay.original) ?: return null)
        val head=document.title()+" "+document.body().text().take(1200)
        if(document.select("#challenge-container,#captcha,#captcha-area,#turnstile-widget-container,#anubis-main").isNotEmpty()||Regex("verify you are human|checking your browser|making sure you.re not a bot|just a moment|javascript is not available|log in to continue|login required|sign in to view|account required|has not archived that url|not in archive|this url has been excluded|wayback machine has not archived",RegexOption.IGNORE_CASE).containsMatchIn(head))return null
        val normalized=capture.copy(timestamp=replay.timestamp,original=replay.original,capturedAt=capturedAt)
        val candidates=mutableListOf<PostSnapshot>()
        document.select("script[type=application/ld+json]").take(16).forEach {script->runCatching {structured(JsonParser.parseString(script.data()),current,normalized)}.getOrNull()?.let(candidates::addAll)}
        val declared=document.select("link[rel=canonical],meta[property=og:url]").mapNotNull {unwrap(it.attr(if(it.tagName()=="link")"href"else "content"),replay.original,capture.provider)}.any {matches(it,current)}
        val roots=document.select("#permalink.hentry,[data-tweet-id],article,[data-testid=tweet],.main-tweet,.timeline-item,.thing,shreddit-post,.status,.tgme_widget_message,.post")
        val postRoots=roots.filter {it.tagName() !in setOf("html","body")}
        val uniqueRoots=postRoots.filter {node->node.parents().none {it in postRoots}}
        for(node in uniqueRoots.take(64)) {
            val copy=node.clone().apply {select("script,style,iframe,form,.comments,.replies,.quote-tweet,.quoted-tweet,.retweet,.comment,.status__prepend").remove()}
            val content=copy.selectFirst(".desc-inner > p,.tweet-text,.tweet-content,[data-testid=tweetText],.usertext-body > .md,.post_body,.post-content,.post-body,.status__content,.entry-content,.tgme_widget_message_text,.caption,.post-caption-text,[slot=text-body]")
            // A quoted post or a link in the message is not this card's identity.
            val urls=copy.select("a[href]").filter {a->content==null||a!=content&&content !in a.parents()}.mapNotNull {unwrap(it.attr("href"),replay.original,capture.provider)}
            val exact=urls.any {matches(it,current)}
            val id=ContentIdentity.key(current.platform,current.canonicalUrl).substringAfterLast(':')
            val explicitId=node.attr("data-tweet-id")==id&&current.platform==Platform.X||node.attr("data-fullname")=="t3_$id"&&current.platform==Platform.REDDIT||node.attr("post-id")=="t3_$id"&&current.platform==Platform.REDDIT||node.attr("data-post").takeIf(String::isNotBlank)?.let {matches("https://t.me/$it",current)}==true
            val legacy=current.platform==Platform.X&&node.id()=="permalink"&&node.hasClass("hentry")&&uniqueRoots.size==1
            val otherPost=urls.any {url->routes.recognize(url,current.platform)?.let {it.platform==current.platform&&it.kind==TargetKind.POST&& !matches(url,current)}==true}
            if(!exact&&!explicitId&&!legacy&&!(declared&&uniqueRoots.size==1&&!otherPost))continue
            val body=content?.wholeText()?.trim().orEmpty()
            if(incomplete(body,copy))continue
            val title=copy.selectFirst("a.title,.post_title,[slot=title]")?.text().orEmpty().ifBlank {copy.attr("post-title")}
            val author=copy.selectFirst(".screen-name,.username,.post_author,.author,.status__display-name,.tgme_widget_message_owner_name,[data-testid=User-Name]")?.text()?.trim()?.removePrefix("@").orEmpty().ifBlank {copy.attr("author")}
            val avatar=copy.selectFirst(".thumb img,.tweet-avatar img,.avatar img,img.avatar")?.attr("src")?.let {mediaUrl(it,replay.original,normalized)}
            val images=copy.select(".attachments img[src],.AdaptiveMedia img[src],.js-adaptive-photo img[src],.post-content img[src],.status__attachments img[src],.media-gallery img[src],img.preview,img.post_media,img.media,.tgme_widget_message_photo_wrap,.post-image img[src],video[src],video source[src]").mapNotNull {e->
                val raw=e.attr("src").ifBlank {Regex("url\\(['\"]?([^)'\"]+)").find(e.attr("style"))?.groupValues?.get(1).orEmpty()}
                mediaUrl(raw,replay.original,normalized)?.let {Media(it,if(e.tagName() in setOf("video","source"))"video"else "image",mediaUrl(e.attr("poster"),replay.original,normalized),e.attr("alt"))}
            }.distinctBy {it.url}.take(16)
            if(body.isBlank()&&title.isBlank()&&images.isEmpty())continue
            val links=content?.select("a[href]")?.mapNotNull {unwrap(it.attr("href"),replay.original,capture.provider)}?.distinct()?.take(12).orEmpty()
            val date=copy.selectFirst("time[datetime]")?.attr("datetime")?.let(Extraction::timestamp)
            candidates+=PostSnapshot(title,body,author,date,avatar,images,links)
        }
        val snapshot=candidates.firstOrNull {(it.body.isNotBlank()||it.title.isNotBlank()||it.media.isNotEmpty())&&!incomplete(it.body,null)} ?: return null
        return ArchivedVersion(capture.provider.label,capturedAt,payload.finalUrl,snapshot)
    }
    fun wall(body: String): HealthStatus? {
        val head=Jsoup.parse(body.take(128*1024)).let {it.title()+" "+it.body().text().take(1200)}
        return when {
            Regex("verify you are human|checking your browser|making sure you.re not a bot|just a moment|too many requests",RegexOption.IGNORE_CASE).containsMatchIn(head)->HealthStatus.CHALLENGE
            Regex("log in to continue|login required|sign in to view|account required",RegexOption.IGNORE_CASE).containsMatchIn(head)->HealthStatus.LOGIN_REQUIRED
            else->null
        }
    }
    private fun incomplete(body: String,node: Element?): Boolean=body.trimEnd().let {it.endsWith("…")||it.endsWith("...")}||node?.select(".truncated,.read-more,.show-more-text,[data-testid=tweet-text-show-more-link]")?.isNotEmpty()==true
    private fun structured(value: JsonElement,current: Post,capture: ArchiveCapture,depth: Int=0): List<PostSnapshot> {
        if(depth>12)return emptyList()
        if(value.isJsonArray)return value.asJsonArray.take(50).flatMap {structured(it,current,capture,depth+1)}
        if(!value.isJsonObject)return emptyList()
        val o=value.asJsonObject
        val types=o["@type"]?.let {if(it.isJsonArray)it.asJsonArray.mapNotNull {v->v.takeIf(JsonElement::isJsonPrimitive)?.asString}else listOfNotNull(it.takeIf(JsonElement::isJsonPrimitive)?.asString)}.orEmpty()
        val main=o["mainEntityOfPage"]?.let {if(it.isJsonObject)string(it.asJsonObject,"@id","url")else it.takeIf(JsonElement::isJsonPrimitive)?.asString}.orEmpty()
        val url=string(o,"url","@id").ifBlank {main}
        val result=mutableListOf<PostSnapshot>()
        val body=string(o,"articleBody","text")
        if(types.any {it in setOf("SocialMediaPosting","DiscussionForumPosting","BlogPosting","Article")}&&unwrap(url,capture.original,capture.provider)?.let {matches(it,current)}==true&&!incomplete(body,null)) {
            val author=o["author"]?.let {if(it.isJsonObject)string(it.asJsonObject,"name")else it.takeIf(JsonElement::isJsonPrimitive)?.asString}.orEmpty()
            val media=mutableListOf<Media>()
            fun images(v: JsonElement?,video: Boolean=false) {
                if(v==null)return
                if(v.isJsonArray){v.asJsonArray.take(16).forEach {images(it,video)};return}
                val raw=if(v.isJsonObject)string(v.asJsonObject,"contentUrl","url")else v.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
                mediaUrl(raw,capture.original,capture)?.let {media+=Media(it,if(video)"video"else "image")}
            }
            images(o["image"]);images(o["video"],true)
            if(body.isNotBlank()||media.isNotEmpty())result+=PostSnapshot(string(o,"headline"),Extraction.plain(body),author,Extraction.timestamp(string(o,"datePublished")),media=media.distinctBy {it.url}.take(16),aiDisclosures=StructuredAiMetadata.declarations(o).takeIf {it.isNotEmpty()})
        }
        o.entrySet().filter {it.key in setOf("@graph","mainEntity","hasPart","itemListElement","item")}.take(8).forEach {result+=structured(it.value,current,capture,depth+1)}
        return result
    }
}
