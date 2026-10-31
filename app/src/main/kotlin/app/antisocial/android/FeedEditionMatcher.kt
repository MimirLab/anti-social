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
    private val preparationBudget:(()->Long)?=null,
    analysis:CrossPosts.SourceAnalysisSnapshot?=null,
    private val group:(suspend (MediaIdentitySnapshot)->List<PostGroup>)? = null
) {
    private val pendingAnalysis=java.util.concurrent.atomic.AtomicReference(analysis.takeIf {group==null})
    private val sourcePreparation=lazy {if(preparationBudget==null)CrossPosts.sourceProjection(follows,memberships)
        else CrossPosts.sourceProjection(follows,memberships,SourcePreparationBudget.EXPANDED)}
    private val source get()=sourcePreparation.value
    private val released=java.util.concurrent.atomic.AtomicLong()
    private var preparedSourcePass=false
    private val gate=Mutex()
    private var snapshot:MediaIdentitySnapshot?=null
    private var base=emptyList<PostGroup>()
    internal fun releaseSourceAnalysis() {
        released.incrementAndGet()
        pendingAnalysis.set(null)
        if(sourcePreparation.isInitialized())sourcePreparation.value.setPreparationBudget(0)
    }
    internal fun snapshotSourceAnalysis(accounts:List<Follow>):CrossPosts.SourceAnalysisSnapshot? {
        if(accounts!=follows)return null
        return (if(sourcePreparation.isInitialized())sourcePreparation.value.snapshotSourceAnalysis()else null) ?: pendingAnalysis.get()
    }
    suspend fun project(evidence:MediaIdentitySnapshot,captured:(Post)->Post,related:(Post,Post)->SharedVideoFootage?)=
        projectWithImages(evidence,captured,related,null)
    suspend fun projectCurrent(evidence:MediaIdentitySnapshot,captured:(Post)->Post,related:(Post,Post)->SharedVideoFootage?,currentImages:()->MediaIdentitySnapshot)=
        projectWithImages(evidence,captured,related,currentImages)
    private suspend fun projectWithImages(evidence:MediaIdentitySnapshot,captured:(Post)->Post,related:(Post,Post)->SharedVideoFootage?,currentImages:(()->MediaIdentitySnapshot)?):List<PostGroup> = gate.withLock {
        val context=currentCoroutineContext()
        context.ensureActive()
        var used=currentImages?.invoke() ?: evidence
        if(snapshot?.sameInput(used)!=true) {
            if(group==null&&preparationBudget!=null) {
                val epoch=released.get()
                val allowed=preparationBudget()
                source.setPreparationBudget(if(preparedSourcePass)allowed else minOf(allowed,SourcePreparationBudget.BASIC))
                // A stopped/disposed reader can release memory while the
                // resource query waits. Its old answer must not refill it.
                if(epoch!=released.get())source.setPreparationBudget(0)
            }
            if(group==null)pendingAnalysis.getAndSet(null)?.let {source.restoreSourceAnalysis(it,used.posts)}
            val next=if(group!=null)group.invoke(used)else if(currentImages==null)source.group(used.posts,used::same)
            else source.groupWithCurrentMedia(used.posts) {
                context.ensureActive();val latest=currentImages();used=latest
                CrossPostMediaSnapshot(latest.posts,latest::same)
            }
            context.ensureActive()
            base=next;snapshot=used;preparedSourcePass=true
        }
        val observed=base.map {g->context.ensureActive();g.copy(posts=g.posts.map(captured))}
        CrossPosts.withVideoFootage(observed,follows,memberships,related){context.ensureActive()}
    }
}

/** UI-owned continuity across immutable source windows. Only completed source
 * facts cross the boundary; each matcher keeps independent admission and base. */
internal class FeedMatcherHandoff {
    private var previous:FeedEditionMatcher?=null
    fun create(follows:List<Follow>?,memberships:Map<String,Set<String>>,budget:()->Long):FeedEditionMatcher? {
        val next=follows?.let {FeedEditionMatcher(it,memberships,preparationBudget=budget,analysis=previous?.snapshotSourceAnalysis(it))}
        previous=next
        return next
    }
}
