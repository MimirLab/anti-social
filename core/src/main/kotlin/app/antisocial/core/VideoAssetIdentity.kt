/* anti social, GPL-3.0-or-later. Asset keys never rewrite playback URLs. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Qualifies renditions of one supplied clip; never uses a caption, account,
 * thumbnail or an arbitrary query-bearing website as a video identifier. */
object VideoAssetIdentity {
    private val twitter=Regex("^/(amplify_video|ext_tw_video)/([0-9]{1,40})/(?:vid|pl)/")
    fun key(raw:String):String? {
        val url=raw.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()} ?: return null
        YouTubeUrls.videoId(raw)?.let {return "youtube:$it"}
        if(url.host in setOf("video.twimg.com","video-s.twimg.com"))twitter.find(url.encodedPath)?.let {return "twitter:${it.groupValues[1]}:${it.groupValues[2]}"}
        if(url.host=="v.redd.it")url.pathSegments.firstOrNull()?.takeIf {it.matches(Regex("[A-Za-z0-9]{4,40}"))}?.let {return "reddit:$it"}
        // CDN shard and expiring access parameters do not identify a new file.
        // Keep the complete path; different media objects remain different.
        if((url.host.endsWith(".fbcdn.net")||url.host.endsWith(".cdninstagram.com"))&&
            url.encodedPath.endsWith(".mp4",true))return "meta:"+url.encodedPath
        return null
    }
}
