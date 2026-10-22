/* AntiSocial, GPL-3.0-or-later. Exact public identities, never a page reachability test. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal object YouTubeShareEvidence {
    private val stamp=Regex("^/web/([0-9]{14})(?:[a-z]{1,4}_)?/")
    private val date=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private fun id(post: Post)=post.takeIf {it.platform==Platform.YOUTUBE&&YouTubeUrls.commentId(it.canonicalUrl)==null}?.canonicalUrl?.let(YouTubeUrls::videoId)
    private fun safe(value: String)=ShareRouteUrls.safe(value)?.toHttpUrlOrNull()
    private fun capture(value: String)=safe(value)?.encodedPath?.let {stamp.find(it)?.groupValues?.get(1)}?.let {runCatching {LocalDateTime.parse(it,date).toInstant(ZoneOffset.UTC).toEpochMilli()}.getOrNull()}
    fun binaryReplay(post: Post,value: String,now: Long=System.currentTimeMillis()): Boolean {
        val id=id(post) ?: return false
        val archive=safe(value)?.takeIf {it.host=="web.archive.org"} ?: return false
        // Wayback's exact-ID legacy endpoint selects an available recording;
        // zero is a selector, never a capture date. The prefix response must
        // remain on this same-ID endpoint or a same-ID timestamped replay.
        if(archive.query==null&&Regex("^/web/0id_/https?://wayback-fakeurl\\.archive\\.org/yt/${Regex.escape(id)}$").matches(archive.encodedPath))return true
        if(capture(value)?.let {it in 1..now+CachePolicy.DAY}!=true)return false
        val original=ArchiveUrls.original(archive.toString())?.toHttpUrlOrNull() ?: return false
        return original.host=="wayback-fakeurl.archive.org"&&original.encodedPath=="/yt/$id"&&original.query==null ||
            original.host.endsWith(".googlevideo.com")&&original.encodedPath=="/videoplayback"&&original.queryParameterValues("video_id")==listOf(id)
    }
    fun archive(post: Post,value: String,now: Long=System.currentTimeMillis()): Boolean {
        val id=id(post) ?: return false
        val url=safe(value) ?: return false
        return when(url.host) {
            "web.archive.org"->binaryReplay(post,value,now)||capture(value)?.let {it in 1..now+CachePolicy.DAY}==true&&ArchiveUrls.original(value)?.let(YouTubeUrls::videoId)==id
            "ghostarchive.org"->url.encodedPath=="/varchive/$id"&&url.query==null
            "hobune.stream"->url.encodedPath in setOf("/videos/$id","/tpa-h/videos/$id")&&url.query==null
            "preservetube.com"->url.encodedPath=="/watch"&&url.queryParameterNames==setOf("v")&&url.queryParameterValues("v")==listOf(id)
            "archive.org"->url.pathSegments.let {it.size==2&&it[0]=="details"&&it[1] in setOf("youtube-$id","youtube_$id",id)}&&url.query==null
            else->false
        }
    }
    fun binary(bytes: ByteArray,finalUrl: String,candidate: ShareRouteCandidate,now: Long): ShareRouteCheck =
        if(binaryReplay(candidate.post,finalUrl,now)&&YouTubeStreamProof.recognized(bytes))ShareRouteCheck(ShareRouteStatus.READABLE,finalUrl)else ShareRouteCheck(ShareRouteStatus.UNVERIFIED)

    /** Bounded HTML data is read as JSON/markup. No player scripts execute. */
    fun inspect(payload: Payload,candidate: ShareRouteCandidate,now: Long): ShareRouteCheck? {
        val id=id(candidate.post) ?: return null
        val document=Jsoup.parse(payload.text,payload.finalUrl)
        ArchiveParsing.wall(payload.text)?.let {return ShareRouteCheck(if(it==HealthStatus.LOGIN_REQUIRED)ShareRouteStatus.LOGIN_REQUIRED else ShareRouteStatus.UNVERIFIED)}
        val final=safe(payload.finalUrl) ?: return ShareRouteCheck(ShareRouteStatus.BLOCKED)
        if(archive(candidate.post,payload.finalUrl,now)) {
            val videos=VideoExtraction.html(document,payload.finalUrl)
            val playable=when(final.host) {
                "ghostarchive.org","hobune.stream"->videos.any {media->val u=safe(media.url);u!=null&&(u.host==final.host||final.host=="ghostarchive.org"&&u.host=="ghostvideo.b-cdn.net")&&u.pathSegments.any {it==id||it.substringBeforeLast('.')==id}}
                "archive.org"->{
                    val identifier=final.pathSegments[1]
                    val declared=document.select("a[href]").any {element->YouTubeUrls.videoId(element.absUrl("href"))==id}
                    fun media(raw: String): Boolean {
                        val u=final.resolve(raw)?.takeIf {safe(it.toString())!=null} ?: return false
                        return u.host=="archive.org"&&u.pathSegments.let {it.size==3&&it[0]=="download"&&it[1]==identifier&&it[2].substringAfterLast('.').lowercase() in setOf("mp4","webm")&&(declared||it[2].substringBeforeLast('.')==id||it[2].contains("-$id.")||it[2].contains("_$id."))}
                    }
                    videos.any {media(it.url)}||document.select("#js-play8-playlist[value]").any {element->
                        runCatching {JsonParser.parseString(element.attr("value")).asJsonArray.take(16).any {item->item.asJsonObject["sources"]?.asJsonArray?.take(16)?.any {source->source.asJsonObject["file"]?.asString?.let(::media)==true}==true}}.getOrDefault(false)
                    }
                }
                "web.archive.org"->videos.any {binaryReplay(candidate.post,it.url,now)}
                "preservetube.com"->videos.isNotEmpty()&&document.select("link[rel=canonical],meta[property=og:url],[data-video-id]").any {element->element.attr("data-video-id")==id||final.resolve(element.attr(if(element.tagName()=="link")"href"else "content"))?.let {it.host=="preservetube.com"&&it.encodedPath=="/watch"&&it.queryParameterValues("v")==listOf(id)}==true}
                else->false
            }
            return ShareRouteCheck(if(playable)ShareRouteStatus.READABLE else ShareRouteStatus.EMPTY,if(playable)payload.finalUrl else null)
        }
        val assignment=Regex("(?:var\\s+)?ytInitialPlayerResponse\\s*=\\s*")
        val responses=document.select("script").take(128).mapNotNull {script->
            val raw=script.data();val match=assignment.find(raw) ?: return@mapNotNull null
            runCatching {JsonParser.parseReader(JsonReader(raw.substring(match.range.last+1).reader())).asJsonObject}.getOrNull()
        }
        for(response in responses) {
            val details=response["videoDetails"]?.takeIf {it.isJsonObject}?.asJsonObject ?: continue
            if(text(details,"videoId")!=id)continue
            val status=response["playabilityStatus"]?.takeIf {it.isJsonObject}?.asJsonObject
            if(status?.let {text(it,"status")}=="LOGIN_REQUIRED")return ShareRouteCheck(ShareRouteStatus.LOGIN_REQUIRED)
            val reason=status?.let {text(it,"reason")}.orEmpty()
            if(Regex("this video (?:has been|was) (?:removed|deleted)",RegexOption.IGNORE_CASE).containsMatchIn(reason))return ShareRouteCheck(ShareRouteStatus.NOT_FOUND)
            val streams=response["streamingData"]?.takeIf {it.isJsonObject}?.asJsonObject
            val content=streams!=null&&(listOf("formats","adaptiveFormats").any {key->streams[key]?.takeIf {it.isJsonArray}?.asJsonArray?.take(64)?.any {entry->entry.takeIf {it.isJsonObject}?.asJsonObject?.let {text(it,"mimeType").startsWith("video/")&&(safe(text(it,"url"))!=null||text(it,"signatureCipher").isNotBlank()||text(it,"cipher").isNotBlank())}==true}==true}||listOf("hlsManifestUrl","dashManifestUrl").any {safe(text(streams,it))!=null})
            if(status?.let {text(it,"status")}=="OK"&&text(details,"title").isNotBlank()&&content)return ShareRouteCheck(ShareRouteStatus.READABLE,payload.finalUrl)
        }
        if(candidate.source?.frontend=="invidious") {
            val declared=document.select("link[rel=canonical],meta[property=og:url]").any {element->
                val u=final.resolve(element.attr(if(element.tagName()=="link")"href"else "content")) ?: return@any false
                YouTubeUrls.videoId(u.toString())==id||u.host==final.host&&u.port==final.port&&u.encodedPath=="/watch"&&u.queryParameterValues("v")==listOf(id)
            }
            if(declared&&VideoExtraction.html(document,payload.finalUrl).isNotEmpty()&&document.select("h1,meta[property=og:title]").any {it.text().isNotBlank()||it.attr("content").isNotBlank()})return ShareRouteCheck(ShareRouteStatus.READABLE,payload.finalUrl)
        }
        return null
    }
    private fun text(o: JsonObject,key: String)=o[key]?.takeIf {it.isJsonPrimitive}?.asString.orEmpty()
}
