/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ShareLinkAction { COPY, OPEN }
enum class ShareRouteKind { ORIGINAL, FRONTEND, ARCHIVE, CROSS_POST }
enum class ShareRouteStatus { READABLE, NOT_FOUND, LOGIN_REQUIRED, EMPTY, UNVERIFIED, BLOCKED }
data class ShareRouteEvidence(val originalUrl: String,val url: String,val tor: Boolean,val status: ShareRouteStatus,val checkedAt: Long)
data class ShareRouteCandidate(val post: Post,val url: String,val kind: ShareRouteKind,val source: Source?=null)
data class ShareRouteCheck(val status: ShareRouteStatus,val finalUrl: String?=null,val notice: String?=null)
sealed interface ShareRouteResult {
    data class Available(val url: String,val originalUrl: String,val editionUrl: String,val platform: Platform,val kind: ShareRouteKind,val verified: Boolean,val notice: String?=null): ShareRouteResult
    data class Unavailable(val message: String): ShareRouteResult
}
interface ShareRouteMemory {
    fun get(originalUrl: String,url: String,tor: Boolean): ShareRouteEvidence?
    fun put(value: ShareRouteEvidence)
}
class MemoryShareRoutes(private val limit: Int=128): ShareRouteMemory {
    private val values=linkedMapOf<String,ShareRouteEvidence>()
    @Synchronized override fun get(originalUrl: String,url: String,tor: Boolean)=values["$tor|$originalUrl|$url"]
    @Synchronized override fun put(value: ShareRouteEvidence) {values["${value.tor}|${value.originalUrl}|${value.url}"]=value;while(values.size>limit)values.remove(values.keys.first())}
}

/** An explicit action checks a small, already discovered route list. Ordinary
 * rendering does not call this resolver or turn a transport endpoint into a link. */
class ShareRouteResolver(
    private val routes: DarwinRoutes,private val cleaner: UrlCleaner,
    private val check: suspend (ShareRouteCandidate)->ShareRouteCheck,
    private val memory: ShareRouteMemory=MemoryShareRoutes(),
    private val originalAllowed: ()->Boolean={true},private val tor: ()->Boolean={false},
    private val clock: ()->Long=System::currentTimeMillis,
    private val alternatives: (Post)->List<Source> = {emptyList()}
) {
    private val originals=OriginalLinks(routes,cleaner)
    fun original(post: Post): String?=ShareRouteUrls.safe(originals.url(post.canonicalUrl,post.platform))
    private fun clean(value: String): String? {
        val safe=ShareRouteUrls.safe(runCatching {cleaner.clean(value)}.getOrDefault(value)) ?: return null
        val uri=URI(safe)
        return if(uri.rawFragment=="m"&&routes.outwardAlias(uri)?.first?.frontend=="nitter")safe.substringBefore('#')else safe
    }
    private fun identity(post: Post)=ContentIdentity.key(post.platform,post.canonicalUrl)
    private fun same(post: Post,url: String,source: Source?=null): Boolean {
        if(YouTubeShareEvidence.archive(post,url,clock()))return true
        val unwrapped=ArchiveUrls.original(url) ?: originals.url(url,post.platform)
        val recognized=routes.recognize(unwrapped,post.platform)
        if(recognized?.platform==post.platform&&ContentIdentity.key(post.platform,recognized.canonicalUrl)==identity(post))return true
        if(source?.role==SourceRole.ALTERNATE&&source.frontend in htmlFrontends&&DarwinRoutes.matches(URI(url),source.root)) {
            val target=routes.recognize(post.canonicalUrl,post.platform) ?: return false
            return clean(routes.convert(target,source))?.substringBefore('#')==clean(url)?.substringBefore('#')
        }
        return post.platform==Platform.RSS&&unwrapped.trimEnd('/')==post.canonicalUrl.trimEnd('/')
    }
    private fun fresh(value: ShareRouteEvidence,now: Long): Boolean {
        val ttl=when(value.status){ShareRouteStatus.READABLE->15*60_000L;ShareRouteStatus.NOT_FOUND->24*60*60_000L;ShareRouteStatus.LOGIN_REQUIRED,ShareRouteStatus.EMPTY->10*60_000L;else->60_000L}
        return now>=value.checkedAt&&now-value.checkedAt<=ttl
    }
    private fun evidence(original: String,url: String,mode: Boolean): ShareRouteEvidence? {
        val now=clock()
        val current=memory.get(original,url,mode)?.takeIf {fresh(it,now)}
        val other=memory.get(original,url,!mode)?.takeIf {fresh(it,now)}
        val both=listOfNotNull(current,other)
        val hard=both.filter {it.status in setOf(ShareRouteStatus.NOT_FOUND,ShareRouteStatus.BLOCKED)}.maxByOrNull {it.checkedAt}
        val readable=both.filter {it.status==ShareRouteStatus.READABLE}.maxByOrNull {it.checkedAt}
        // A newer exact missing/rejected result invalidates an older positive
        // proof even when it arrived through another connection mode.
        if(hard!=null&&(readable==null||hard.checkedAt>=readable.checkedAt))return hard
        if(current?.status in setOf(ShareRouteStatus.NOT_FOUND,ShareRouteStatus.BLOCKED)&&readable!=null&&readable.checkedAt>current!!.checkedAt)return null
        return current
    }
    /** Called only for freshly extracted pages, never imported or merely cached
     * records. Only known HTML readers and an exact permalink qualify as proof. */
    fun remember(page: Page) {
        val provenance=page.provenance ?: return
        if(provenance.cached||provenance.source.frontend !in htmlFrontends+"archive-recovery")return
        val now=clock()
        if(provenance.fetchedAt !in (now-15*60_000L)..now)return
        for(post in page.posts.take(64)) {
            if(post.preview||post.body.isBlank()&&post.title.isBlank()&&post.media.isEmpty())continue
            val canonical=original(post) ?: continue
            val exact=clean(provenance.finalUrl) ?: continue
            if(!ShareRouteUrls.webDocument(exact))continue
            if(!same(post.copy(canonicalUrl=canonical),exact,provenance.source))continue
            if(provenance.source.frontend=="archive-recovery"&&post.recoveredArchive?.archiveUrl!=exact)continue
            memory.put(ShareRouteEvidence(canonical,exact,tor(),ShareRouteStatus.READABLE,provenance.fetchedAt))
        }
    }
    private fun identicalEditions(primary: Post,others: List<Post>): List<Post> = others.distinctBy {it.platform to identity(it)}.filter {other->
        if(other.preview||primary.preview||other.parentId!=null||primary.parentId!=null)return@filter false
        val a=primary.publishedAt?.takeIf {it>0} ?: return@filter false
        val b=other.publishedAt?.takeIf {it>0} ?: return@filter false
        if(kotlin.math.abs(a-b)>CrossPosts.MAX_REPEAT_WINDOW)return@filter false
        if(PostPresentation.comparableContent(primary)!=PostPresentation.comparableContent(other))return@filter false
        val x=PostPresentation.text(primary);val y=PostPresentation.text(other)
        ArchiveContent.compare(primary.copy(title=x.title,body=x.body),other.copy(platform=primary.platform,canonicalUrl=primary.canonicalUrl,title=y.title,body=y.body))==ArchiveComparison.SAME
    }.take(8)
    private fun candidates(post: Post,kind: ShareRouteKind): List<ShareRouteCandidate> {
        val canonical=original(post) ?: return emptyList()
        val exact=post.copy(canonicalUrl=canonical)
        val found=mutableListOf(ShareRouteCandidate(exact,canonical,kind))
        val provenance=post.provenance
        val source=provenance?.source?.takeIf {it.role==SourceRole.ALTERNATE&&it.frontend in htmlFrontends&&ShareRouteUrls.safe(it.root)!=null}
        if(source!=null) {
            val target=routes.recognize(canonical,post.platform)
            val urls=listOfNotNull(provenance.finalUrl,target?.let {runCatching {routes.convert(it,source)}.getOrNull()})
            for(raw in urls)clean(raw)?.takeIf {ShareRouteUrls.webDocument(it)&&same(exact,it,source)}?.let {found+=ShareRouteCandidate(exact,it,if(kind==ShareRouteKind.CROSS_POST)kind else ShareRouteKind.FRONTEND,source)}
        }
        val captures=(listOfNotNull(post.recoveredArchive?.let {it.capturedAt to it.archiveUrl},post.archivedVersion?.let {it.capturedAt to it.archiveUrl})+
            listOfNotNull(provenance?.takeIf {post.platform==Platform.YOUTUBE&&it.source.frontend=="youtube-archive"&&it.source.role==SourceRole.CACHE}?.finalUrl?.let {0L to it})).sortedByDescending {it.first}
        for((_,raw) in captures)clean(raw)?.takeIf {url->YouTubeShareEvidence.archive(exact,url,clock())||ArchiveUrls.original(url)?.let {ArchiveUrls.samePost(exact,it)}==true}?.let {found+=ShareRouteCandidate(exact,it,if(kind==ShareRouteKind.CROSS_POST)kind else ShareRouteKind.ARCHIVE)}
        val target=routes.recognize(canonical,post.platform)
        if(target!=null)for(alternate in alternatives(post).distinctBy {it.key}.take(2)) {
            if(alternate.role!=SourceRole.ALTERNATE||alternate.platform!=post.platform||alternate.frontend !in htmlFrontends||ShareRouteUrls.safe(alternate.root)==null)continue
            clean(routes.convert(target,alternate))?.takeIf {ShareRouteUrls.webDocument(it)&&same(exact,it,alternate)}?.let {found+=ShareRouteCandidate(exact,it,if(kind==ShareRouteKind.CROSS_POST)kind else ShareRouteKind.FRONTEND,alternate)}
        }
        return found.distinctBy {it.url}
    }
    suspend fun resolve(post: Post,editions: List<Post> = emptyList(),action: ShareLinkAction=ShareLinkAction.COPY): ShareRouteResult {
        val canonical=original(post) ?: return ShareRouteResult.Unavailable("This link is not a safe public address")
        val primary=post.copy(canonicalUrl=canonical)
        val mode=tor();val allowed=originalAllowed()
        val list=(candidates(primary,ShareRouteKind.ORIGINAL)+identicalEditions(primary,editions).filter {it.platform!=primary.platform||identity(it)!=identity(primary)}.flatMap {candidates(it,ShareRouteKind.CROSS_POST)}).distinctBy {it.post.platform to it.url}
        val previous=evidence(canonical,canonical,mode)
        var gone=previous?.status==ShareRouteStatus.NOT_FOUND;var blocked=previous?.status==ShareRouteStatus.BLOCKED;var attempts=0
        fun available(candidate: ShareRouteCandidate,url: String,verified: Boolean,notice: String?=null)=ShareRouteResult.Available(url,canonical,candidate.post.canonicalUrl,candidate.post.platform,candidate.kind,verified,notice ?: when(candidate.kind){ShareRouteKind.FRONTEND->"Using a readable reader link; original attribution is preserved.";ShareRouteKind.ARCHIVE->"Using an archived copy of this original post.";ShareRouteKind.CROSS_POST->"Using an identical retained original on ${candidate.post.platform.label}.";else->null})
        val total=if(mode)20_000L else 10_000L
        try {withTimeout(total) {
            for(candidate in list) {
                currentCoroutineContext().ensureActive()
                if(tor()!=mode||originalAllowed()!=allowed)throw CancellationException("Link connection policy changed")
                val isOriginal=candidate.url==candidate.post.canonicalUrl
                if(isOriginal&&!allowed)continue
                val cached=evidence(candidate.post.canonicalUrl,candidate.url,mode)
                if(candidate.kind==ShareRouteKind.ORIGINAL&&isOriginal) {if(cached?.status==ShareRouteStatus.NOT_FOUND)gone=true;if(cached?.status==ShareRouteStatus.BLOCKED)blocked=true}
                if(cached?.status==ShareRouteStatus.READABLE)return@withTimeout available(candidate,candidate.url,true)
                if(cached!=null)continue
                if(attempts>=3)continue
                attempts++
                val outcome=try {withTimeout(if(mode)7_000L else 3_000L){check(candidate)}}
                    catch(e: TimeoutCancellationException){currentCoroutineContext().ensureActive();ShareRouteCheck(ShareRouteStatus.UNVERIFIED)}
                    catch(e: CancellationException){throw e}
                    catch(_: Exception){ShareRouteCheck(ShareRouteStatus.UNVERIFIED)}
                if(tor()!=mode||originalAllowed()!=allowed)throw CancellationException("Link connection policy changed")
                val chosen=outcome.finalUrl?.let(::clean)?.takeIf {same(candidate.post,it,candidate.source)} ?: candidate.url
                val status=if(outcome.status==ShareRouteStatus.READABLE&&outcome.finalUrl!=null&&clean(outcome.finalUrl)?.let {same(candidate.post,it,candidate.source)}!=true)ShareRouteStatus.UNVERIFIED else outcome.status
                memory.put(ShareRouteEvidence(candidate.post.canonicalUrl,candidate.url,mode,status,clock()))
                if(candidate.kind==ShareRouteKind.ORIGINAL&&isOriginal) {if(status==ShareRouteStatus.NOT_FOUND)gone=true;if(status==ShareRouteStatus.BLOCKED)blocked=true}
                if(status==ShareRouteStatus.READABLE)return@withTimeout available(candidate,chosen,true,outcome.notice)
            }
            null
        }?.let {return it}}catch(e: TimeoutCancellationException){currentCoroutineContext().ensureActive()}
        for(candidate in list) {
            if(candidate.url==candidate.post.canonicalUrl&&!allowed)continue
            val cached=evidence(candidate.post.canonicalUrl,candidate.url,mode)
            if(cached?.status==ShareRouteStatus.READABLE)return available(candidate,candidate.url,true)
        }
        if(blocked)return ShareRouteResult.Unavailable("This original link was rejected by the public-link privacy checks and no readable discovered copy could be verified")
        if(gone)return ShareRouteResult.Unavailable("The original was unavailable and no readable discovered copy could be verified")
        if(action==ShareLinkAction.OPEN&&!allowed)return ShareRouteResult.Unavailable("Original access is disabled and no readable discovered copy could be verified")
        return ShareRouteResult.Available(canonical,canonical,canonical,post.platform,ShareRouteKind.ORIGINAL,false,"Original link; public availability could not be verified. This does not establish that the post was deleted.")
    }
    companion object {
        val htmlFrontends=setOf("nitter","redlib","libreddit","teddit","eddrit","troddit","phice","priviblur","kittygram","proxigram","proxiTok","shoelace","ratAintTieba","pinless","painterest","binternet","invidious")
    }
}

/** Local-only URL screening; NetworkGateway also validates DNS on every request. */
object ShareRouteUrls {
    fun webDocument(value: String): Boolean {
        val url=value.toHttpUrlOrNull() ?: return false
        val parts=url.pathSegments.filter(String::isNotBlank).map(String::lowercase)
        return parts.none {it in setOf("api","xrpc","graphql")}&&parts.lastOrNull() !in setOf("rss","atom","feed")&&parts.lastOrNull()?.let {Regex("\\.(?:rss|atom|xml|json)$").containsMatchIn(it)}!=true&&url.queryParameter("format")?.lowercase() !in setOf("rss","atom","xml","json")
    }
    fun safe(value: String): String? {
        if(value.isBlank()||value.length>16*1024||value.any {it<=' '||it=='\\'||it=='\u007f'})return null
        val url=value.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()&&it.port==443} ?: return null
        val host=url.host.lowercase().trimEnd('.')
        if('.' !in host||host=="localhost"||listOf(".local",".localhost",".internal",".lan",".home").any(host::endsWith))return null
        // Public social/article links use DNS names. Reject literals locally;
        // this also excludes ambiguous shortened or mapped private addresses.
        if(host.contains(':')||host.matches(Regex("[0-9.]+")))return null
        val parts=url.pathSegments.filter(String::isNotBlank).map(String::lowercase)
        if(parts.firstOrNull() in setOf("login","login.php","signin","sign-in","signup","register","auth","authwall","oauth","oauth2","accounts","checkpoint","challenge","consent")||parts.take(3)==listOf("i","flow","login"))return null
        if(url.queryParameterNames.any {it.lowercase() in setOf("token","auth","access_token","auth_token","password","session","sessionid","session_id","authorization")})return null
        return url.toString()
    }
}
