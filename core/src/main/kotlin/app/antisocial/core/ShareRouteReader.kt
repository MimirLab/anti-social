/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.io.IOException
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** One body read through the app gateway. A HEAD/200, API response, generic
 * frontend homepage or login shell is never proof that a permalink is readable. */
class ShareRouteReader(private val gateway: NetworkGateway,private val routes: DarwinRoutes,private val cleaner: UrlCleaner,private val clock: ()->Long=System::currentTimeMillis) {
    private val originals=OriginalLinks(routes,cleaner)
    suspend fun check(candidate: ShareRouteCandidate): ShareRouteCheck {
        if(ShareRouteUrls.safe(candidate.url)==null)return ShareRouteCheck(ShareRouteStatus.BLOCKED)
        return try {
            if(YouTubeShareEvidence.binaryReplay(candidate.post,candidate.url,clock())) {
                val prefix=gateway.samplePayload(candidate.url,candidate.url,limit=4096)
                return YouTubeShareEvidence.binary(prefix.bytes,prefix.finalUrl,candidate,clock())
            }
            val uri=URI(candidate.url)
            val origin="${uri.scheme}://${uri.rawAuthority}"
            inspect(gateway.text(candidate.url,candidate.post.canonicalUrl,maxBytes=1024L*1024,allowedOrigins=setOf(origin)),candidate)
        }catch(e: CancellationException){throw e}
        catch(e: SourceFailure){ShareRouteCheck(when {e.httpStatus in setOf(404,410)->ShareRouteStatus.NOT_FOUND;e.status==HealthStatus.LOGIN_REQUIRED->ShareRouteStatus.LOGIN_REQUIRED;e.status==HealthStatus.EMPTY->ShareRouteStatus.EMPTY;else->ShareRouteStatus.UNVERIFIED})}
        catch(e: IOException){ShareRouteCheck(if(Regex("private destination|private address|private source|source credentials|insecure source|blocked by privacy",RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()))ShareRouteStatus.BLOCKED else ShareRouteStatus.UNVERIFIED)}
    }
    private fun matches(post: Post,url: String,source: Source?): Boolean {
        val safe=ShareRouteUrls.safe(url) ?: return false
        if(YouTubeShareEvidence.archive(post,safe,clock()))return true
        val candidate=ArchiveUrls.original(safe) ?: originals.url(safe,post.platform)
        val found=routes.recognize(candidate,post.platform)
        if(found?.platform==post.platform&&ContentIdentity.key(post.platform,found.canonicalUrl)==ContentIdentity.key(post.platform,post.canonicalUrl))return true
        if(source?.role==SourceRole.ALTERNATE&&source.frontend in ShareRouteResolver.htmlFrontends&&DarwinRoutes.matches(URI(safe),source.root)) {
            val target=routes.recognize(post.canonicalUrl,post.platform) ?: return false
            val expected=ShareRouteUrls.safe(cleaner.clean(routes.convert(target,source))) ?: return false
            return safe.substringBefore('#')==expected.substringBefore('#')
        }
        return post.platform==Platform.RSS&&candidate.trimEnd('/')==post.canonicalUrl.trimEnd('/')
    }
    fun inspect(payload: Payload,candidate: ShareRouteCandidate): ShareRouteCheck {
        val final=ShareRouteUrls.safe(payload.finalUrl)
        if(final==null) {
            val path=runCatching {URI(payload.finalUrl).path.orEmpty().lowercase()}.getOrDefault("")
            return ShareRouteCheck(if(Regex("/(?:login|signin|authwall|checkpoint|challenge)(?:/|$)").containsMatchIn(path))ShareRouteStatus.LOGIN_REQUIRED else ShareRouteStatus.BLOCKED)
        }
        if(payload.status in setOf(404,410))return ShareRouteCheck(ShareRouteStatus.NOT_FOUND)
        if(payload.status!=200||!matches(candidate.post,final,candidate.source))return ShareRouteCheck(ShareRouteStatus.UNVERIFIED)
        val html=payload.text.trimStart()
        if(!ShareRouteUrls.webDocument(final)||!html.startsWith('<')||payload.contentType.contains("json",true)||payload.contentType.contains("xml",true)&&!payload.contentType.contains("xhtml",true))return ShareRouteCheck(ShareRouteStatus.UNVERIFIED)
        YouTubeShareEvidence.inspect(payload,candidate,clock())?.let {return it}
        val archiveOriginal=ArchiveUrls.original(final)
        if(archiveOriginal!=null) {
            val uri=URI(final)
            val provider=ArchiveProvider.defaults.firstOrNull {it.sameOrigin(final)} ?: return ShareRouteCheck(ShareRouteStatus.UNVERIFIED)
            val stamp=Regex("^/(?:web|noFrame/replay|wayback)/([0-9]{14})(?:[a-z]{1,4}_)?/").find(uri.rawPath.orEmpty())?.groupValues?.get(1) ?: return ShareRouteCheck(ShareRouteStatus.UNVERIFIED)
            val captured=runCatching {LocalDateTime.parse(stamp,DateTimeFormatter.ofPattern("uuuuMMddHHmmss")).toInstant(ZoneOffset.UTC).toEpochMilli()}.getOrNull() ?: return ShareRouteCheck(ShareRouteStatus.UNVERIFIED)
            val capture=ArchiveCapture(provider,stamp,archiveOriginal,captured)
            val version=ArchiveParsing.replay(payload,capture,candidate.post,routes,clock())
            return if(version!=null)ShareRouteCheck(ShareRouteStatus.READABLE,version.archiveUrl)else ShareRouteCheck(ShareRouteStatus.EMPTY)
        }
        val document=Jsoup.parse(payload.text,final)
        val resolve: (String)->String?={raw->runCatching {URI(final).resolve(raw).toString()}.getOrNull()}
        val declared=document.select("link[rel=canonical],meta[property=og:url]").any {element->resolve(element.attr(if(element.tagName()=="link")"href"else "content"))?.let {matches(candidate.post,it,candidate.source)}==true}
        val linked=document.select("a.tweet-link[href],.tweet-date a[href],a.comment_permalink[href],a.post_link[href],a.post_title[href],a.title[href],.post-timestamp a[href],.tgme_widget_message_date[href]").any {element->resolve(element.attr("href"))?.let {matches(candidate.post,it,candidate.source)}==true}
        val token=ContentIdentity.key(candidate.post.platform,candidate.post.canonicalUrl).substringAfterLast(':')
        val explicit=document.select("[data-tweet-id],[data-post],[data-fullname],[post-id]").any {element->
            candidate.post.platform==Platform.X&&element.attr("data-tweet-id")==token||candidate.post.platform==Platform.REDDIT&&(element.attr("data-fullname")=="t3_$token"||element.attr("post-id")=="t3_$token")||candidate.post.platform==Platform.TELEGRAM&&matches(candidate.post,"https://t.me/"+element.attr("data-post"),candidate.source)
        }
        if(candidate.post.platform==Platform.RSS) {
            val article=document.select("article,main").firstOrNull {it.text().length>=40}
            if(article!=null&&document.title().isNotBlank()&&ArchiveParsing.wall(payload.text)==null)return ShareRouteCheck(ShareRouteStatus.READABLE,final)
        }
        if(declared||linked||explicit) {
            val target=routes.recognize(candidate.post.canonicalUrl,candidate.post.platform)
            if(target!=null) {
                val source=candidate.source ?: Source(candidate.post.platform,"original",candidate.post.platform.origin,role=SourceRole.ORIGINAL)
                val page=runCatching {if(source.frontend=="phice")FacebookProtocol.phice(payload,target,source)else Extraction.parse(payload,target,source,routes,clock())}.getOrNull()
                if(page?.posts?.any {it.platform==candidate.post.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(candidate.post.platform,candidate.post.canonicalUrl)&&!it.preview&&(it.body.isNotBlank()||it.title.isNotBlank()||it.media.isNotEmpty())}==true)return ShareRouteCheck(ShareRouteStatus.READABLE,final)
            }
        }
        val deletion=document.select(".error-panel,.error-detail,[data-testid=error-detail],#error-page,.not-found").text()
        if(Regex("(?:this (?:post|tweet) (?:was|has been) deleted|(?:post|tweet) (?:not found|does not exist|is unavailable)|page not found)",RegexOption.IGNORE_CASE).containsMatchIn(deletion))return ShareRouteCheck(ShareRouteStatus.NOT_FOUND)
        return ShareRouteCheck(when(ArchiveParsing.wall(payload.text)){HealthStatus.LOGIN_REQUIRED->ShareRouteStatus.LOGIN_REQUIRED;HealthStatus.CHALLENGE->ShareRouteStatus.UNVERIFIED;else->ShareRouteStatus.EMPTY})
    }
}
