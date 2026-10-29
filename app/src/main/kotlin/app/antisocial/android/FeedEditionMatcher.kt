/* anti social, GPL-3.0-or-later. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Finish one coherent projection; retain only the newest waiting state.
 * combine's own input buffer otherwise queues old snapshots behind CPU work.
 * Identical image objects must still flow when footage or gallery state changes. */
internal fun feedMatchingInputs(images:Flow<MediaIdentitySnapshot>,videos:Flow<Long>,galleries:Flow<Long>):Flow<MediaIdentitySnapshot> =
    combine(images,videos,galleries){image,_,_->image}.conflate()

/** Owned by one source/membership projection, on its grouping worker only.
 * Video observations do not invalidate already completed caption/image work.
 * Keep the base, not the joined result: expired or withdrawn footage must split
 * again, and the next independent observation must not inherit an old proof. */
internal class FeedEditionMatcher(
    private val follows:List<Follow>,
    private val memberships:Map<String,Set<String>>,
    private val group:(suspend (MediaIdentitySnapshot)->List<PostGroup>)? = null
) {
    private val source by lazy {CrossPosts.sourceProjection(follows,memberships)}
    private val gate=Mutex()
    private var snapshot:MediaIdentitySnapshot?=null
    private var base=emptyList<PostGroup>()
    suspend fun project(evidence:MediaIdentitySnapshot,captured:(Post)->Post,related:(Post,Post)->SharedVideoFootage?):List<PostGroup> = gate.withLock {
        val context=currentCoroutineContext()
        if(snapshot?.sameInput(evidence)!=true) {
            val next=group?.invoke(evidence) ?: source.group(evidence.posts,evidence::same)
            context.ensureActive()
            base=next;snapshot=evidence
        }
        val observed=base.map {g->context.ensureActive();g.copy(posts=g.posts.map(captured))}
        CrossPosts.withVideoFootage(observed,follows,memberships,related){context.ensureActive()}
    }
}
