/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Authentic, shareable addresses at presentation/action boundaries only.
 * This never resolves a URL or mutates fetched posts, transport assets or route
 * provenance. Unknown hosts and opaque frontend proxy paths remain untouched.
 */
class OriginalLinks(private val routes: DarwinRoutes,private val cleaner: UrlCleaner) {
    @Suppress("UNUSED_PARAMETER")
    fun url(value: String,platform: Platform?=null): String {
        if(value.length>MAX_URL)return value
        val supplied=value.trim()
        var current=if(supplied.startsWith("//"))"https:$supplied" else supplied
        if(valid(current)==null) {
            // Captions sometimes use a reader hostname as the anchor's label.
            // Add a scheme only when it actually identifies a known frontend.
            val candidate="https://$supplied"
            val parsed=valid(candidate) ?: return value
            if(alias(parsed)==null)return value
            current=candidate
        }
        val seen=hashSetOf<String>()
        repeat(12) {
            if(!seen.add(current))return current
            val before=current
            val cleaned=runCatching {cleaner.clean(current)}.getOrDefault(current)
            if(valid(cleaned)!=null)current=cleaned
            ArchiveUrls.original(current)?.let {original->
                // Replay fragments address the original document's comments or
                // image anchor. Archive identity parsing intentionally omits
                // them, but a copied/opened URL must retain their meaning.
                val fragment=valid(current)?.rawFragment
                current=original+(if(fragment!=null&&valid(original)?.rawFragment==null)"#$fragment"else "")
            }
            val parsed=valid(current) ?: return before
            val known=alias(parsed)
            if(known!=null)reverse(parsed,known.first,known.second)?.let {candidate->if(valid(candidate)!=null)current=candidate}
            else if(parsed.host.equals("web-cdn.bsky.app",true))current=authority(Platform.BLUESKY.origin,parsed)
            SocialLinkHosts.canonical(current)?.let {current=it}
            if(current==before)return current
        }
        return current
    }

    /** Replace complete absolute or known bare reader URLs, preserving prose.
     * URL identities retain case, escaped path separators and nontracking query
     * fields. Trailing sentence punctuation is kept outside the replacement. */
    @Suppress("UNUSED_PARAMETER")
    fun text(value: String,platform: Platform?=null): String = tokens.replace(value) {match->
        val raw=match.value
        val link=trimProsePunctuation(raw)
        if(link.length>MAX_URL)raw else url(link)+raw.substring(link.length)
    }

    private fun alias(uri: URI): Pair<Source,URI>? = routes.outwardAlias(uri) ?: legacy[uri.host.lowercase(Locale.ROOT)]
        ?.takeIf {uri.port==-1||uri.scheme.equals("http",true)&&uri.port==80||uri.scheme.equals("https",true)&&uri.port==443}
        ?.let {it to URI(it.root)}

    private fun reverse(uri: URI,source: Source,base: URI): String? {
        var path=uri.rawPath.orEmpty().removePrefix(base.rawPath.orEmpty().trimEnd('/'))
        val query=uri.rawQuery?.let {"?$it"}.orEmpty()
        val fragment=uri.rawFragment?.takeUnless {source.frontend=="nitter"&&it=="m"}?.let {"#$it"}.orEmpty()
        if(source.platform==Platform.YOUTUBE)return YouTubeUrls.recognize(source.platform.origin+path+query+fragment)?.canonicalUrl
        val first=path.trim('/').substringBefore('/')
        if(first in navigation)return null
        when(source.frontend) {
            "skyview" -> {
                if(path !in setOf("","/"))return null
                val embedded=queryValue(uri,"url") ?: return null
                return embedded.takeIf {valid(it)!=null&&SocialLinkHosts.platform(it)==source.platform}
            }
            "nitter" -> {
                if(path.startsWith("/pic/"))return nitterPicture(path.removePrefix("/pic/"),query,fragment)
                if(path.startsWith("/video/"))return null // Signed opaque proxies need their transport context.
                if(path.startsWith("/t.co/"))return "https://t.co/"+path.removePrefix("/t.co/")+query+fragment
                if(Regex("^/[A-Za-z0-9_]{1,15}/rss/?$").matches(path))path=path.removeSuffix("/").removeSuffix("/rss")
                // /i/user/<id>, search, tags, profiles and status/photo paths are
                // authentic X paths. Arbitrary frontend navigation is not.
                if(path.isNotBlank()&&path!="/"&&first !in setOf("search","i","hashtag")&&!first.matches(Regex("[A-Za-z0-9_]{1,15}")))return null
            }
            "redlib","libreddit","eddrit","troddit","teddit" -> {
                if(path.startsWith("/img/"))return "https://i.redd.it/"+path.removePrefix("/img/")+query+fragment
                if(path.startsWith("/preview/pre/"))return "https://preview.redd.it/"+path.removePrefix("/preview/pre/")+query+fragment
                if(path.startsWith("/preview/external-pre/"))return "https://external-preview.redd.it/"+path.removePrefix("/preview/external-pre/")+query+fragment
            }
            "priviblur" -> {
                Regex("^/tblr/media/([0-9]+)(/.*)$").matchEntire(path)?.let {return "https://${it.groupValues[1]}.media.tumblr.com${it.groupValues[2]}$query$fragment"}
                if(path.startsWith("/tblr/assets/"))return "https://assets.tumblr.com/"+path.removePrefix("/tblr/assets/")+query+fragment
                if(path.startsWith("/tblr/static/"))return "https://static.tumblr.com/"+path.removePrefix("/tblr/static/")+query+fragment
            }
            "kittygram" -> if(path.startsWith("/popular/"))path="/explore/tags/"+path.removePrefix("/popular/")
            "binternet" -> if(path=="/search.php")path="/search/pins/"
            "pinless","painterest" -> Unit
        }
        if(source.frontend in setOf("binternet","pinless","painterest")&&path in setOf("/image_proxy.php","/image","/_/proxy")) {
            val embedded=queryValue(uri,"url") ?: return null
            return embedded.takeIf {valid(it)?.let {u->u.host.equals("i.pinimg.com",true)&&u.scheme.equals("https",true)}==true}
        }
        return source.platform.origin+path+query+fragment
    }

    private fun nitterPicture(tail: String,query: String,fragment: String): String? {
        // Decode the proxy's one encoding layer; '+' is a path character, not a
        // form-space. Only established original Twitter artwork is shareable.
        val decoded=runCatching {URLDecoder.decode(tail.replace("+","%2B"),"UTF-8")}.getOrNull() ?: return null
        val original=if(decoded.startsWith("https://",true)||decoded.startsWith("http://",true))decoded else {
            val path=decoded.removePrefix("orig/")
            if(!path.startsWith("media/")&&!path.startsWith("tweet_video_thumb/")&&!path.startsWith("ext_tw_video_thumb/")&&!path.startsWith("amplify_video_thumb/")&&!path.startsWith("profile_images/")&&!path.startsWith("profile_banners/"))return null
            "https://pbs.twimg.com/$path"
        }
        val parsed=valid(original) ?: return null
        if(parsed.host.lowercase(Locale.ROOT) !in setOf("pbs.twimg.com","video.twimg.com"))return null
        val extra=if(query.isEmpty())"" else if(parsed.rawQuery!=null)"&"+query.removePrefix("?")else query
        return original+extra+fragment
    }

    private fun queryValue(uri: URI,key: String): String? {
        if(uri.rawQuery?.split('&')?.count {it.substringBefore('=')==key}!=1)return null
        return runCatching {DarwinRoutes.queryValue(uri,key)}.getOrNull()
    }
    private fun authority(root: String,uri: URI)=root+uri.rawPath.orEmpty()+uri.rawQuery?.let {"?$it"}.orEmpty()+uri.rawFragment?.let {"#$it"}.orEmpty()

    companion object {
        private const val MAX_URL=16*1024
        private val navigation=setOf("about","settings","preferences","login","logout","register","signup","privacy","terms","donate","rss","robots.txt","favicon.ico")
        // Historical donor instance, still found in old saved posts and links.
        // This is an outward identity alias, never a claim of route availability.
        private val legacy=mapOf("nitter.net" to Source(Platform.X,"nitter","https://nitter.net"),
            // Observed public reader links survive in captions after an instance
            // leaves the live pool. Identity does not claim route availability.
            "invidious.tiekoetter.com" to Source(Platform.YOUTUBE,"invidious","https://invidious.tiekoetter.com"))
        private val tokens=Regex("(?i)(?<![\\p{L}\\p{N}_@.-])(?:(?:https?:)?//[^\\s<>\\\"“”]+|(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}(?::[0-9]{1,5})?(?:/[^\\s<>\\\"“”]*)?)")
        private fun valid(value: String): URI? {
            if(value.isBlank()||value.length>MAX_URL||value.any {it<=' '||it=='\\'||it=='\u007f'})return null
            return runCatching {URI(value)}.getOrNull()?.takeIf {it.scheme?.lowercase(Locale.ROOT) in setOf("http","https")&&it.host!=null&&it.rawUserInfo==null&&it.port in -1..65535}
        }
        private fun trimProsePunctuation(value: String): String {
            var end=value.length
            while(end>0) {
                val c=value[end-1]
                val closer=when(c){')'->'(';']'->'[';'}'->'{';else->null}
                val extraClosing=closer!=null&&value.take(end).count {it==c}>value.take(end).count {it==closer}
                if(c in ".,;:'’"||extraClosing)end-- else break
            }
            return value.substring(0,end)
        }
    }
}
