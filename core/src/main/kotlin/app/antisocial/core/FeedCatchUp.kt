/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import kotlinx.coroutines.CancellationException

data class FeedCatchUpResult(val page: Page,val pendingReason: String?=null,val pages: Int=1)

/** Follow real cursors back to the previously fetched head before publishing a
 * refresh. A finite window is honest about a source that cannot bridge the gap. */
class FeedCatchUp(private val maxPages: Int=8,private val maxPosts: Int=300) {
    init {require(maxPages>0&&maxPosts>0)}
    suspend fun collect(platform: Platform,knownHead: Set<String>,first: suspend ()->Page,next: suspend (Page)->Page): FeedCatchUpResult {
        var page=first();var pages=1
        val retained=linkedMapOf<String,Post>();val cursors=mutableSetOf<String>()
        fun key(post: Post)="${post.platform.id}:${ContentIdentity.key(post.platform,post.canonicalUrl)}"
        var problem: String?=null
        while(true) {
            val readable=FeedRefresh.posts(page,platform)
            var truncated=false
            readable.forEach {if(retained.size<maxPosts||key(it) in retained)retained[key(it)]=it else truncated=true}
            val invalid=FeedRefresh.pendingReason(page,platform)
            if(invalid!=null){problem=invalid;break}
            if(truncated){problem="More posts remain beyond this catch-up window; this subscription is still pending.";break}
            // A first subscription has no previous head to bridge. Do not crawl
            // its entire historical archive on first use.
            if(knownHead.isEmpty()||readable.any {key(it) in knownHead})break
            val cursor=page.next?.takeIf(String::isNotBlank) ?: break
            if(!cursors.add(cursor)){problem="The source repeated its cursor before reaching the previous reading window.";break}
            if(pages>=maxPages||retained.size>=maxPosts){problem="More posts remain beyond this catch-up window; this subscription is still pending.";break}
            page=try {next(page)}catch(e: CancellationException){throw e}catch(_: Exception) {
                problem="The source could not finish loading back to the previous reading window.";break
            }
            pages++
        }
        return FeedCatchUpResult(page.copy(posts=retained.values.sortedWith(compareByDescending<Post>{it.publishedAt ?: Long.MIN_VALUE}.thenBy {it.id}),next=null),problem,pages)
    }
}
