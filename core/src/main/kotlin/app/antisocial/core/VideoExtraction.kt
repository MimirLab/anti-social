/* AntiSocial, GPL-3.0-or-later. These readers consume public data, never page scripts.
 * Format references: Nitter src/views/tweet.nim renderVideoAttachment;
 * Redlib src/utils.rs Media::parse. No frontend implementation is embedded. */
package app.antisocial.core

import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.Base64

/** Select one exposed, playable alternative per attachment rather than duplicate cards. */
internal object VideoExtraction {
    private fun text(value: JsonObject,vararg keys: String)=keys.firstNotNullOfOrNull {key->value[key]?.takeIf {it.isJsonPrimitive}?.asString?.takeIf(String::isNotBlank)}.orEmpty()
    fun safe(value: String,base: String): String? {
        val raw=Parser.unescapeEntities(value.trim(),false)
        if(raw.isBlank())return null
        return base.toHttpUrlOrNull()?.resolve(raw)?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    }
    fun mime(value: String): String?=when(value.substringBefore(';').trim().lowercase()) {
        "video/mp4"->"video/mp4"
        "video/webm"->"video/webm"
        "application/vnd.apple.mpegurl","application/x-mpegurl","audio/mpegurl","audio/x-mpegurl"->"application/x-mpegURL"
        "application/dash+xml"->"application/dash+xml"
        else->null
    }
    private fun inferredMime(value: String): String? {
        fun extension(url: String)=url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringAfterLast('.',"")?.lowercase()?.let {when(it) {
            "mp4","m4v"->"video/mp4";"webm"->"video/webm";"m3u8"->"application/x-mpegURL";"mpd"->"application/dash+xml";else->null
        }}
        extension(value)?.let {return it}
        val url=value.toHttpUrlOrNull() ?: return null
        // Nitter proxies may hide the real suffix in a signed percent/base64 path.
        // Inspect the exposed value only to choose a decoder; keep its proxy URL intact.
        val parts=url.pathSegments
        val nested=when {
            parts.size==4&&parts[0]=="video"&&parts[1]=="enc"&&parts[3].length<=8192->runCatching {String(Base64.getUrlDecoder().decode(parts[3]),Charsets.UTF_8)}.getOrNull()
            parts.size==3&&parts[0]=="video"->parts[2]
            else->null
        }
        return nested?.let(::extension)
    }
    private fun candidate(raw: String,base: String,type: String="",preview: String?=null,description: String=""): Media? {
        val url=safe(raw,base) ?: return null
        // Browser-only blob URLs and explicitly unsupported formats are not streams.
        val declared=mime(type)
        if(type.isNotBlank()&&declared==null)return null
        val hint=declared ?: inferredMime(url)
        return Media(url,"video",preview,description,hint)
    }
    fun html(root: Element,base: String): List<Media> =root.select("video").take(16).mapNotNull {video->
        val preview=safe(video.attr("poster"),base)
        val own=listOf("src","data-url","data-src").firstNotNullOfOrNull {attribute->candidate(video.attr(attribute),base,video.attr("type"),preview)}
        own ?: video.select("source[src],source[data-src]").filter {it.parents().firstOrNull {parent->parent.tagName()=="video"}===video}.mapNotNull {source->
            candidate(source.attr("src").ifBlank {source.attr("data-src")},base,source.attr("type"),preview)
        }.let {alternatives->alternatives.firstOrNull {it.mimeType in setOf("video/mp4","video/webm")} ?: alternatives.firstOrNull()}
    }.distinctBy {it.url}

    /** A disabled HLS player still offers its public progressive download.
     * Read only this post's own gallery, never a quoted post or generic link.
     * The signed proxy stays intact and its nested provider clip is qualified
     * as data only; fetching it still belongs to NetworkGateway. */
    fun nitterDownloads(root:Element,provenance:Provenance):List<Media> {
        val excluded=setOf("quote","quote-big","quoted-tweet","quote-tweet","tweet-content","replies","related-tweets")
        fun own(node:Element):Boolean {
            var current:Element?=node
            while(current!=null&&current!==root) {
                if(current.classNames().any {it in excluded}||current!==node&&current.hasClass("timeline-item"))return false
                current=current.parent()
            }
            return current===root&&!root.classNames().any {it in excluded}
        }
        return root.select(".gallery-video").asSequence().filter(::own).take(16).mapNotNull {gallery->
            if(html(gallery,provenance.finalUrl).isNotEmpty())return@mapNotNull null
            val link=gallery.select("a.video-download[href]").filter(::own).singleOrNull() ?: return@mapNotNull null
            val url=safe(link.attr("href"),provenance.finalUrl) ?: return@mapNotNull null
            if(DeclaredVideo.proxyRecording(url,provenance)==null)return@mapNotNull null
            val poster=gallery.select("img[src]").filter(::own).singleOrNull()?.let {safe(it.attr("src"),provenance.finalUrl)}
            candidate(url,provenance.finalUrl,preview=poster)?.takeIf {it.mimeType in setOf("video/mp4","video/webm")}
        }.distinctBy {it.url}.toList()
    }

    fun reddit(post: JsonObject,base: String): Media? {
        fun nested(parent: String,child: String)=post[parent]?.takeIf {it.isJsonObject}?.asJsonObject?.get(child)?.takeIf {it.isJsonObject}?.asJsonObject
        val sources=listOfNotNull(nested("secure_media","reddit_video"),nested("media","reddit_video"),nested("preview","reddit_video_preview"))
        for(video in sources) {
            val duration=MediaDuration.seconds(video["duration"])
            // Reddit's progressive fallback commonly omits its separate audio track.
            candidate(text(video,"hls_url"),base,"application/x-mpegURL")?.let {return it.copy(durationMs=duration)}
            candidate(text(video,"dash_url"),base,"application/dash+xml")?.let {return it.copy(durationMs=duration)}
            candidate(text(video,"fallback_url"),base,"video/mp4")?.let {return it.copy(durationMs=duration)}
        }
        return null
    }

    fun xVideo(video: JsonObject,base: String,preview: String?=null,description: String=""): Media? {
        val variants=video["variants"]?.takeIf {it.isJsonArray}?.asJsonArray?.take(32).orEmpty().mapNotNull {entry->
            val value=entry.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            val media=candidate(text(value,"src","url"),base,text(value,"type","content_type"),preview,description) ?: return@mapNotNull null
            media to (text(value,"bitrate").toLongOrNull()?.coerceAtLeast(0) ?: 0L)
        }
        val progressive=variants.filter {it.first.mimeType in setOf("video/mp4","video/webm")}
        val primary=(progressive.ifEmpty {variants}).maxByOrNull {it.second}?.first ?: return null
        return primary.copy(durationMs=MediaDuration.milliseconds(video["duration_millis"]),
            samplingUrl=VideoAssetIdentity.smallRendition(primary.url,progressive.map {it.first.url to it.second}))
    }
}
