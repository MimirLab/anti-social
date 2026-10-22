/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.net.URI
import java.util.Locale

/** Known public social entry points offered to Android's supported-link chooser.
 * Arbitrary federated servers stay explicit user input; a hostname is not a mirror.
 */
object SocialLinkHosts {
    val hosts: Map<Platform,List<String>> = linkedMapOf(
        Platform.YOUTUBE to listOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be","www.youtu.be","youtube-nocookie.com","www.youtube-nocookie.com"),
        Platform.X to listOf("x.com","www.x.com","m.x.com","twitter.com","www.twitter.com","m.twitter.com","mobile.twitter.com","t.co"),
        Platform.BLUESKY to listOf("bsky.app","www.bsky.app"),
        Platform.REDDIT to listOf("reddit.com","www.reddit.com","old.reddit.com","np.reddit.com","m.reddit.com","redd.it","reddit.app.link"),
        Platform.TUMBLR to listOf("tumblr.com","www.tumblr.com","*.tumblr.com","t.umblr.com"),
        Platform.TIKTOK to listOf("tiktok.com","www.tiktok.com","m.tiktok.com","vm.tiktok.com","vt.tiktok.com"),
        Platform.INSTAGRAM to listOf("instagram.com","www.instagram.com","m.instagram.com","instagr.am","www.instagr.am"),
        Platform.PINTEREST to listOf("pinterest.com","www.pinterest.com","pin.it","pinterest.co.uk","www.pinterest.co.uk","pinterest.ca","www.pinterest.ca","pinterest.com.au","www.pinterest.com.au","pinterest.de","www.pinterest.de","pinterest.fr","www.pinterest.fr"),
        Platform.IFUNNY to listOf("ifunny.co","www.ifunny.co"),
        Platform.TIEBA to listOf("tieba.baidu.com"),
        Platform.THREADS to listOf("threads.com","www.threads.com","threads.net","www.threads.net"),
        Platform.FACEBOOK to listOf("facebook.com","www.facebook.com","m.facebook.com","mbasic.facebook.com","web.facebook.com","touch.facebook.com","fb.com","www.fb.com","fb.me","www.fb.me","fb.watch"),
        Platform.TRUTH_SOCIAL to listOf("truthsocial.com","www.truthsocial.com"),
        Platform.MASTODON to listOf("mastodon.social","mstdn.social"),
        Platform.LEMMY to listOf("lemmy.world","lemmy.ml"),
        Platform.PIXELFED to listOf("pixelfed.social"),
        Platform.MISSKEY to listOf("misskey.io"),
        Platform.GETTR to listOf("gettr.com","www.gettr.com","m.gettr.com"),
        Platform.GAB to listOf("gab.com","www.gab.com"),
        Platform.TELEGRAM to listOf("t.me","www.t.me","telegram.me","www.telegram.me","telegram.dog","www.telegram.dog"),
        Platform.LINKEDIN to listOf("linkedin.com","www.linkedin.com","m.linkedin.com","uk.linkedin.com","us.linkedin.com","in.linkedin.com","au.linkedin.com","ca.linkedin.com","de.linkedin.com","fr.linkedin.com","br.linkedin.com","it.linkedin.com","es.linkedin.com","nl.linkedin.com","lnkd.in"),
        Platform.SNAPCHAT to listOf("snapchat.com","www.snapchat.com","m.snapchat.com","story.snapchat.com","t.snapchat.com")
    )
    private val shorteners=setOf("t.co","redd.it","reddit.app.link","t.umblr.com","vm.tiktok.com","vt.tiktok.com","pin.it","fb.me","www.fb.me","fb.watch","lnkd.in","t.snapchat.com")
    private val federated=setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY)
    private fun parsed(value: String): URI?=runCatching {URI(value.trim())}.getOrNull()?.takeIf {
        it.scheme?.lowercase(Locale.ROOT) in setOf("http","https")&&it.host!=null&&it.userInfo==null&&it.port in setOf(-1,80,443)&&
            (it.port==-1||it.scheme.equals("http",true)&&it.port==80||it.scheme.equals("https",true)&&it.port==443)
    }
    private fun matches(host: String,declared: String)=if(declared.startsWith("*."))host.endsWith(declared.removePrefix("*"))&&host.length>declared.length-1 else host==declared
    fun platform(url: String): Platform? {
        val host=parsed(url)?.host?.lowercase(Locale.ROOT) ?: return null
        return hosts.entries.firstOrNull {(_,names)->names.any {matches(host,it)}}?.key
    }
    fun needsExpansion(url: String): Boolean {
        val uri=parsed(url) ?: return false
        val path=uri.path.orEmpty()
        return uri.host.lowercase(Locale.ROOT) in shorteners||when(platform(url)) {
            Platform.TIKTOK->path.startsWith("/t/")
            Platform.FACEBOOK->Regex("^/share/(?:p|r|v)/[^/]+/?$").matches(path)
            Platform.INSTAGRAM->Regex("^/share/[^/]+(?:/.*)?$").matches(path)
            Platform.REDDIT->Regex("^/r/[^/]+/s/[^/]+/?$").matches(path)
            else->false
        }
    }
    /** Normalize public site aliases without fetching or guessing a short link's destination. */
    fun canonical(url: String): String? {
        val uri=parsed(url) ?: return null
        YouTubeUrls.recognize(url)?.let {return it.canonicalUrl}
        val platform=platform(url) ?: return null
        val host=uri.host.lowercase(Locale.ROOT)
        val keepHost=needsExpansion(url)||platform in federated||platform==Platform.TUMBLR&&host !in setOf("tumblr.com","www.tumblr.com")
        val destination=if(keepHost)host else URI(platform.origin).host
        return "https://$destination"+uri.rawPath.orEmpty()+uri.rawQuery?.let {"?$it"}.orEmpty()+uri.rawFragment?.let {"#$it"}.orEmpty()
    }
}
