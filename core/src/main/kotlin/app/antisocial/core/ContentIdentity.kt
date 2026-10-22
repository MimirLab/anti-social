package app.antisocial.core

import java.net.URI

/** Stable platform identity survives a different frontend, author alias or slug. */
object ContentIdentity {
    private val patterns=mapOf(
        Platform.X to Regex("/(?:status|statuses)/([^/]+)"),
        Platform.REDDIT to Regex("/comments/([^/]+)(?:/[^/]+/([^/]+))?"),
        Platform.TUMBLR to Regex("/(?:post/)?([0-9]+)(?:/|$)"),
        Platform.TIKTOK to Regex("/(?:video|photo)/([^/]+)"),
        Platform.INSTAGRAM to Regex("/(?:p|reel|reels)/([^/]+)"),
        Platform.PINTEREST to Regex("/pin/([^/]+)"),
        Platform.TIEBA to Regex("/p/([^/]+)"),
        Platform.THREADS to Regex("/(?:post|t)/([^/]+)"),
        Platform.BLUESKY to Regex("/profile/([^/]+)/post/([^/]+)"),
        Platform.IFUNNY to Regex("/(?:picture|video|gif|meme)/([^/]+)")
    )
    fun key(platform: Platform,url: String): String {
        // Feed entry permalinks may intentionally differ only by a fragment.
        if(platform==Platform.YOUTUBE)YouTubeUrls.videoId(url)?.let {id->return YouTubeUrls.commentId(url)?.let {"comment:$id:$it"} ?: "post:$id"}
        if(platform==Platform.RSS)return url.trim()
        val uri=runCatching {URI(url)}.getOrNull()
        val path=uri?.path.orEmpty().ifEmpty {url.substringBefore('?').substringBefore('#')}
        expanded(platform,uri,path)?.let {return it}
        val match=patterns[platform]?.find(path)
        return match?.let {m->when(platform) {
            Platform.REDDIT -> if(m.groupValues[2].isNotBlank())"comment:${m.groupValues[1]}:${m.groupValues[2]}"else "post:${m.groupValues[1]}"
            Platform.BLUESKY -> "post:${m.groupValues[1].lowercase()}:${m.groupValues[2]}"
            Platform.IFUNNY -> "post:${m.groupValues[1].substringAfterLast('-')}"
            Platform.TIEBA -> runCatching {DarwinRoutes.queryValue(URI(url),"pid")}.getOrNull()?.takeIf {it.matches(Regex("[0-9]+"))&&it!="1"}?.let {"comment:${m.groupValues[1]}:$it"} ?: "post:${m.groupValues[1]}"
            Platform.TIKTOK -> uri?.let {DarwinRoutes.queryValue(it,"comment_id")}?.takeIf {it.matches(Regex("[0-9]{1,40}"))}?.let {"comment:${m.groupValues[1]}:$it"} ?: "post:${m.groupValues[1]}"
            else -> "post:${m.groupValues[1]}"
        }
        } ?: url.substringBefore('#').trimEnd('/')
    }
    /** Reference normalization retains native IDs alongside permalinks. Root
     * scope is supplied separately; a missing parent never becomes the root. */
    fun reference(platform: Platform,value: String): String {
        var ref=value.trim()
        if(ref.startsWith("https://")||ref.startsWith("http://"))ref=key(platform,ref)
        if(platform==Platform.X&&ref.matches(Regex("[0-9]+")))return "post:$ref"
        if(platform==Platform.REDDIT)return when {
            ref.startsWith("t1_")->"comment:"+ref.removePrefix("t1_")
            ref.startsWith("t3_")->"post:"+ref.removePrefix("t3_")
            ref.startsWith("comment:")->"comment:"+ref.substringAfterLast(':')
            else->ref
        }
        if(platform==Platform.BLUESKY)Regex("^at://([^/]+)/app\\.bsky\\.feed\\.post/([^/?#]+)$").matchEntire(ref)?.let {
            return "post:${it.groupValues[1].lowercase()}:${it.groupValues[2]}"
        }
        return ref
    }
    private fun expanded(platform: Platform,uri: URI?,path: String): String? {
        val parts=path.trim('/').split('/').filter(String::isNotBlank)
        fun match(pattern: String)=Regex(pattern).find(path)?.groupValues?.get(1)
        fun query(name: String)=uri?.let {runCatching {DarwinRoutes.queryValue(it,name)}.getOrNull()}
        val host=uri?.host?.lowercase()?.let {it+(if(uri.port !in setOf(-1,80,443))":${uri.port}"else "")}.orEmpty()
        val id=when(platform) {
            Platform.FACEBOOK -> {
                val post=match("/(?:posts|videos|reel)/([A-Za-z0-9_-]+)") ?: query("story_fbid") ?: query("fbid") ?: return null
                val comment=(query("reply_comment_id") ?: query("comment_id"))?.takeIf {it.matches(Regex("[0-9]{1,40}"))}
                return if(comment!=null)"comment:$post:$comment"else "post:$post"
            }
            Platform.TRUTH_SOCIAL -> match("/(?:@[^/]+|posts|statuses)/([A-Za-z0-9_-]+)(?:/|$)") ?: return null
            Platform.GETTR -> match("/post/([A-Za-z0-9_-]+)") ?: return null
            Platform.GAB -> match("/(?:posts|statuses)/([A-Za-z0-9_-]+)") ?: return null
            Platform.TELEGRAM -> {
                val channel=parts.filterNot {it=="s"}
                if(channel.size!=2||!channel[1].matches(Regex("[0-9]{1,40}")))return null
                return "post:${channel[0].lowercase()}:${channel[1]}"
            }
            Platform.MASTODON -> match("/(?:@[^/]+|users/[^/]+/statuses)/([A-Za-z0-9_-]+)(?:/|$)") ?: return null
            Platform.MISSKEY -> match("/notes/([A-Za-z0-9_-]+)") ?: return null
            Platform.PIXELFED -> match("/(?:p/[^/]+|i/web/post)/([A-Za-z0-9_-]+)") ?: return null
            Platform.LEMMY -> {
                val type=parts.firstOrNull()?.takeIf {it in setOf("post","comment")} ?: return null
                val token=parts.getOrNull(1)?.takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return null
                return "$type:$host:$token"
            }
            Platform.LINKEDIN -> match("(?:urn:li:activity:|activity-)([0-9]+)") ?: return null
            Platform.SNAPCHAT -> match("/(?:p|spotlight|stories|story)/([A-Za-z0-9_-]+)") ?: return null
            else -> return null
        }
        return if(platform in ExpandedRoutes.federated)"post:$host:$id"else "post:$id"
    }
}
