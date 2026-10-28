/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.net.URI
import java.util.Locale

/** Public streams may use extensionless or signed proxy URLs. A source's
 * declared container takes precedence; unknown endpoints need only a tiny probe.
 */
object VideoFormats {
    const val HLS="application/x-mpegURL"
    const val DASH="application/dash+xml"
    suspend fun resolve(media: Media,sourceUrl: String,gateway: NetworkGateway): String? {
        mime(media)?.let {return it}
        val prefix=try {gateway.sample(media.url,sourceUrl,limit=4096)}catch(e: java.io.IOException) {
            // Some public streams reject Range while accepting an ordinary GET.
            // Keep the read bounded and repeat all gateway checks on that GET.
            if(e.message!="Media returned 416")throw e
            gateway.sample(media.url,sourceUrl,limit=4096,range=false)
        }
        return sniff(prefix)
    }
    fun normalize(value: String?): String?=when(value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "application/vnd.apple.mpegurl","application/x-mpegurl","audio/mpegurl","audio/x-mpegurl"->HLS
        "application/dash+xml"->DASH
        "video/mp4","video/x-m4v","video/quicktime"->"video/mp4"
        "video/webm"->"video/webm"
        "video/x-matroska"->"video/x-matroska"
        "video/mp2t"->"video/mp2t"
        "video/ogg"->"video/ogg"
        "video/mpeg"->"video/mpeg"
        else->null
    }
    fun mime(media: Media): String?=normalize(media.mimeType) ?: runCatching {
        val path=URI(media.url).path.lowercase(Locale.ROOT)
        when(path.substringAfterLast('.')) {
            "m3u8","m3u"->HLS
            "mpd"->DASH
            "mp4","m4v","mov"->"video/mp4"
            "webm"->"video/webm"
            "mkv"->"video/x-matroska"
            "ts"->"video/mp2t"
            "ogv","ogg"->"video/ogg"
            "mpeg","mpg"->"video/mpeg"
            else->null
        }
    }.getOrNull()
    fun adaptive(mime: String?)=normalize(mime) in setOf(HLS,DASH)
    fun sniff(prefix: ByteArray): String? {
        VideoContainerHints.sniff(prefix)?.let {return it}
        var text=String(prefix.take(4096).toByteArray(),Charsets.UTF_8).trimStart('\uFEFF',' ','\t','\r','\n')
        if(text.startsWith("#EXTM3U"))return HLS
        text=text.replaceFirst(Regex("^<\\?xml[^>]*>\\s*",RegexOption.IGNORE_CASE),"")
        while(text.startsWith("<!--")) {
            val end=text.indexOf("-->");if(end<0)return null
            text=text.substring(end+3).trimStart()
        }
        return if(Regex("^<(?:[A-Za-z_][\\w.-]*:)?MPD(?:\\s|>)").containsMatchIn(text))DASH else null
    }
}
