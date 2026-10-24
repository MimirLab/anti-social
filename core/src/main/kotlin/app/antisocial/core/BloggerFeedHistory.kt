/* anti social, GPL-3.0-or-later. Published public feed links, not a generic proxy. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Blogger publishes HTTP links from HTTPS custom-domain feeds. Its numeric
 * blog identity qualifies only the known public feed read at the HTTPS origin.
 * No API key, account session or guessed continuation is used. */
internal object BloggerFeedHistory {
    private val marker=Regex("tag:blogger\\.com,1999:blog-([0-9]{1,30})")
    fun blogId(value:String?)=value?.let {marker.matchEntire(it)?.groupValues?.get(1)}
    fun host(value:String)=value in setOf("blogger.com","www.blogger.com")
    fun next(raw:String,syndicationId:String?):String? {
        val id=blogId(syndicationId) ?: return null
        if(raw.length>8192||raw.any(Char::isISOControl))return null
        val url=raw.toHttpUrlOrNull() ?: return null
        if(!host(url.host)||url.username.isNotEmpty()||url.password.isNotEmpty()||url.fragment!=null)return null
        if(!(url.scheme=="http"&&url.port==80||url.scheme=="https"&&url.port==443))return null
        if(url.encodedPath!="/feeds/$id/posts/default")return null
        if(url.queryParameterNames.any {it !in setOf("start-index","max-results","alt")||url.queryParameterValues(it).size!=1})return null
        if(url.queryParameter("start-index")?.toIntOrNull() !in 1..1_000_000)return null
        if(url.queryParameterNames.contains("max-results")&&url.queryParameter("max-results")?.toIntOrNull() !in 1..200)return null
        if(url.queryParameterNames.contains("alt")&&url.queryParameter("alt")!="atom")return null
        return url.newBuilder().scheme("https").host("www.blogger.com").port(443).build().toString()
    }
}
