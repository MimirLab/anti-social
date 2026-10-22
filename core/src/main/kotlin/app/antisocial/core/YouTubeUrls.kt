/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Video identity is independent of embeds, Shorts, tracking and reader hosts. */
object YouTubeUrls {
    private val id=Regex("[A-Za-z0-9_-]{11}")
    private val hosts=setOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be","www.youtu.be","youtube-nocookie.com","www.youtube-nocookie.com")
    fun canonical(videoId: String): String {require(videoId.matches(id));return "https://www.youtube.com/watch?v=$videoId"}
    fun validId(value: String)=value.matches(id)
    fun videoId(value: String): String? {
        val u=value.toHttpUrlOrNull()?.takeIf {it.host in hosts&&it.username.isEmpty()&&it.password.isEmpty()&&it.port==if(it.isHttps)443 else 80} ?: return null
        val p=u.pathSegments.filter(String::isNotBlank)
        val found=when {
            u.host in setOf("youtu.be","www.youtu.be")&&p.size==1->p[0]
            p==listOf("watch")&&u.queryParameterValues("v").size==1->u.queryParameter("v")
            p.size==2&&p[0] in setOf("shorts","embed","live","v")->p[1]
            else->null
        }
        return found?.takeIf {it.matches(id)}
    }
    fun commentId(value: String)=value.toHttpUrlOrNull()?.queryParameter("lc")?.takeIf {it.matches(Regex("[A-Za-z0-9_.-]{1,256}"))}
    fun recognize(value: String): Target? {
        val u=value.toHttpUrlOrNull()?.takeIf {it.host in hosts&&it.username.isEmpty()&&it.password.isEmpty()&&it.port==if(it.isHttps)443 else 80} ?: return null
        videoId(value)?.let {video->return Target(Platform.YOUTUBE,TargetKind.POST,video,canonical(video)+commentId(value)?.let {"&lc=$it"}.orEmpty())}
        val p=u.pathSegments.filter(String::isNotBlank)
        if(p==listOf("watch"))return null
        if(p==listOf("results")&&u.queryParameterValues("search_query").size==1)return u.queryParameter("search_query")?.takeIf {it.isNotBlank()}?.let {Target(Platform.YOUTUBE,TargetKind.SEARCH,it,"https://www.youtube.com/results?search_query=${DarwinRoutes.encode(it)}")}
        val channel=when {
            p.size in 1..2&&p[0].startsWith('@')&&p[0].matches(Regex("@[\\p{L}\\p{N}_.-]{1,100}"))&&p.getOrNull(1) in setOf(null,"videos","shorts","streams","featured")->p[0]
            p.size in 2..3&&p[0] in setOf("channel","c","user")&&p[1].matches(Regex("[A-Za-z0-9_.-]{1,100}"))&&p.getOrNull(2) in setOf(null,"videos","shorts","streams","featured")->p.take(2).joinToString("/")
            else->null
        } ?: return null
        return Target(Platform.YOUTUBE,TargetKind.PROFILE,channel,"https://www.youtube.com/$channel")
    }
    fun target(value: String,kind: TargetKind=TargetKind.PROFILE): Target {
        val v=value.trim()
        if(v.startsWith("http",true))return recognize(v) ?: throw IllegalArgumentException("Enter a YouTube video or channel link")
        if(kind==TargetKind.POST&&validId(v))return recognize(canonical(v))!!
        val path=if(v.startsWith("UC")&&v.matches(Regex("UC[A-Za-z0-9_-]{22}")))"channel/$v"else if(v.startsWith('@')||v.startsWith("channel/")||v.startsWith("user/")||v.startsWith("c/"))v else "@$v"
        return recognize("https://www.youtube.com/$path") ?: throw IllegalArgumentException("Enter a YouTube channel handle or public link")
    }
}

/** A resolved stream is short-lived transport, never a replacement public URL. */
data class YouTubePlayback(val videoId: String,val media: Media,val sourceUrl: String,val source: Source,val recovered: Boolean=false,val notice: String?=null)
