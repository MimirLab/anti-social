/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

data class FeedCatchUpResult(val page: Page,val pendingReason: String?=null,val pages: Int=1)

/** Follow source-owned cursors back to the previous head. When a fresh pool read
 * cannot bridge that gap, its scoped allowance can try another native timeline.
 * All work shares the same page/post/deadline limits and retained initial head. */
class FeedCatchUp(private val maxPages: Int=8,private val maxPosts: Int=300,private val timeoutMillis:Long?=null,
    private val initialPages:Int=1,private val initialPosts:Int=24) {
    init {require(maxPages>0&&maxPosts>0&&(timeoutMillis==null||timeoutMillis>0)&&initialPages in 1..maxPages&&initialPosts in 1..300)}
    suspend fun collect(platform: Platform,knownHead: Set<String>,first: suspend ()->Page,next: suspend (Page)->Page,
        onInitialPage:suspend (Page)->Unit={}): FeedCatchUpResult {
        var lastPage:Page?=null;var pages=0
        val retained=linkedMapOf<String,Post>();val cursors=mutableSetOf<String>()
        fun key(post: Post)="${post.platform.id}:${ContentIdentity.key(post.platform,post.canonicalUrl)}"
        var problem: String?=null
        val history=FeedHistoryScope(platform)
        var recovering=false
        suspend fun recover(reason:String):Page? {
            problem=reason
            if(knownHead.isEmpty()&&(initialPages==1||pages>=initialPages)||pages>=maxPages||retained.size>=maxPosts||!history.canRecover())return null
            // This is an explicit format/source switch, never a cursor guessed
            // from the old provider. SourcePool admits it only in this context.
            val previous=lastPage ?: return null
            return try {next(previous.copy(next=null)).also {recovering=true}}
            catch(e:CancellationException){throw e}catch(_:Exception){null}
        }
        suspend fun gather() {
            var page=first()
            while(true) {
                lastPage=page;pages++
                val readable=FeedRefresh.posts(page,platform)
                var truncated=false
                readable.forEach {post->
                    val id=key(post)
                    if(id in retained){if(!recovering)retained[id]=post}
                    else if(retained.size<maxPosts)retained[id]=post else truncated=true
                }
                val invalid=FeedRefresh.pendingReason(page,platform)
                if(invalid!=null){val replacement=recover(invalid);if(replacement!=null){page=replacement;continue};break}
                if(knownHead.isEmpty()&&pages==1)onInitialPage(page.copy(posts=readable))
                if(truncated){problem="More posts remain beyond this catch-up window; this subscription is still pending.";break}
                // Publish a new subscription's first usable page before a small
                // source-owned coverage pass. Three entries from one frontend
                // must not define the entire initial multi-platform window.
                // This is bounded independently of ordinary warm catch-up.
                val initial=knownHead.isEmpty()
                val newest=if(initial)retained.values.mapNotNull {it.publishedAt?.takeIf {date->date>0}}.maxOrNull()else null
                // A pinned older post alone cannot end a recent window. Two
                // already observed older entries are enough to stop this cheap
                // pass; do not fill an inactive account with years of history.
                val oldEntries=if(newest==null)0 else retained.values.count {it.publishedAt?.let {date->date>0&&date<newest-48L*60*60*1000}==true}
                if(initial&&(pages>=initialPages||retained.size>=minOf(initialPosts,maxPosts)||oldEntries>=2)||!initial&&readable.any {key(it) in knownHead}){problem=null;break}
                val cursor=page.next?.takeIf(String::isNotBlank)
                if(cursor==null) {
                    if(page.limitedTimeline||recovering) {
                        val replacement=recover("This source's limited public window could not reach the previous reading window; this subscription is still pending.")
                        if(replacement!=null){page=replacement;continue}
                    }
                    if(initial)problem=null // a finite public window is not an unbridged old head
                    break
                }
                if(!cursors.add(page.provenance?.source?.key.orEmpty()+"\n"+cursor)) {
                    val replacement=recover("The source repeated its cursor before reaching the previous reading window.")
                    if(replacement!=null){page=replacement;continue};break
                }
                if(pages>=maxPages||retained.size>=maxPosts){problem="More posts remain beyond this catch-up window; this subscription is still pending.";break}
                val continued=try {next(page)}catch(e: CancellationException){throw e}catch(_: Exception) {
                    recover("The source could not finish loading back to the previous reading window.")
                }
                if(continued==null)break else page=continued
            }
        }
        // Own the whole source deadline here, where completed pages are still
        // available. A timeout around collect() discards that useful work. Only
        // our deadline becomes pending; caller cancellation must still escape.
        var cancelled:CancellationException?=null
        suspend fun ownedGather() {try {gather()}catch(e:CancellationException){cancelled=e;throw e}}
        val finished=try {withContext(history){if(timeoutMillis==null){ownedGather();true}else withTimeoutOrNull(timeoutMillis){ownedGather();true}==true}}
        catch(e:CancellationException){throw cancelled ?: e}
        currentCoroutineContext().ensureActive()
        history.check()
        if(!finished)problem="Catch-up timed out before reaching the previous reading window; this subscription is still pending."
        val page=lastPage ?: throw SourceFailure(HealthStatus.UNAVAILABLE,"Catch-up timed out before a public page was received; this subscription is still pending.")
        return FeedCatchUpResult(page.copy(posts=retained.values.sortedWith(compareByDescending<Post>{it.publishedAt ?: Long.MIN_VALUE}.thenBy {it.id}),next=null),problem,pages)
    }
}
