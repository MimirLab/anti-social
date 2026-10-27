/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FeedCatchUpTest {
    private val source=Source(Platform.X,"nitter","https://reader.example")
    private val provenance=Provenance(source,1_800_000_000_000L,"https://reader.example/alice/rss")
    private fun post(id: String,time: Long=id.toLong())=Post(id,Platform.X,"https://x.com/alice/status/$id","alice","Public post $id",publishedAt=time,provenance=provenance)
    private fun page(vararg posts: Post,next: String?=null,provenance: Provenance=this.provenance)=Page(posts.toList(),next=next,provenance=provenance)
    private fun key(post: Post)=CrossPosts.originalKey(post)

    @Test fun validPostsSurviveAnExplicitContinuationLimitWithoutClaimingCaughtUp(): Unit=runBlocking {
        val old=post("100")
        val partial=page(post("105"),post("104")).copy(continuationIssue="More posts remain; this subscription is still pending.")
        val result=FeedCatchUp().collect(Platform.X,setOf(key(old)),first={partial},next={error("There is no usable continuation to guess")})
        assertEquals(partial.posts,result.page.posts)
        assertEquals(partial.continuationIssue,result.pendingReason)
        assertEquals(partial.continuationIssue,result.page.continuationIssue)
    }

    @Test fun aLimitedOriginalWindowCannotCertifyAnUnbridgedCachedHead():Unit=runBlocking {
        val first=page(post("105"),post("104")).copy(limitedTimeline=true)
        val result=FeedCatchUp().collect(Platform.X,setOf(key(post("100"))),first={first},next={error("Never guess a cursor for a shallow original")})
        assertEquals(first.posts,result.page.posts);assertNotNull(result.pendingReason)
        assertTrue(result.page.limitedTimeline);assertNull(result.page.next)
    }
    @Test fun aLimitedWindowCanFinishWhenItContainsTheHeadOrIsTheFirstSubscription():Unit=runBlocking {
        val head=post("100");val first=page(post("101"),head).copy(limitedTimeline=true)
        for(known in listOf(emptySet(),setOf(key(head)))) {
            val result=FeedCatchUp().collect(Platform.X,known,first={first},next={error("Do not crawl beyond an already joined window")})
            assertNull(result.pendingReason);assertEquals(first.posts,result.page.posts)
        }
    }

    @Test fun followsOnlySuppliedRealCursorsUntilThePreviousNewestHeadAppears(): Unit=runBlocking {
        val head=post("100")
        val one=page(post("106"),post("105"),next="opaque:BlockTwo/Aa?sig=KeepCase")
        val two=page(post("104"),post("103"),next="opaque:BlockThree/Xx")
        val three=page(post("102"),post("101"),head,next="opaque:historical-do-not-read")
        val followed=mutableListOf<String>()
        val result=FeedCatchUp().collect(Platform.X,setOf(key(head)),first={one},next={previous->
            followed+=previous.next!!
            when(previous.next){one.next->two;two.next->three;else->error("No guessed cursor or older history is allowed")}
        })
        assertNull(result.pendingReason)
        assertEquals(listOf(one.next,two.next),followed)
        assertEquals(3,result.pages)
        assertEquals((106 downTo 100).map(Int::toString),result.page.posts.map {it.id})
        assertNull(result.page.next)
    }

    @Test fun anInitialSubscriptionLoadsItsFirstWindowWithoutCrawlingHistoricalPages(): Unit=runBlocking {
        var continuations=0
        val first=page(post("106"),post("105"),next="real-history-cursor")
        val result=FeedCatchUp().collect(Platform.X,emptySet(),first={first},next={continuations++;error("Initial follows must not crawl all history")})
        assertEquals(0,continuations)
        assertEquals(first.posts,result.page.posts)
        assertEquals(1,result.pages)
        assertNull(result.pendingReason)
        assertNull(result.page.next)
    }

    @Test fun aShallowInitialWindowStreamsItsHeadBeforeBoundedSourceOwnedHistory():Unit=runBlocking {
        val first=page(post("106"),post("105"),next="owned-page-2")
        val events=mutableListOf<String>()
        val result=FeedCatchUp(initialPages=3,initialPosts=6).collect(Platform.X,emptySet(),first={first},next={previous->
            assertEquals("head",events.first());events+=previous.next!!
            when(previous.next){"owned-page-2"->page(post("104"),post("103"),next="owned-page-3")
                "owned-page-3"->page(post("102"),post("101"),next="must-not-crawl")
                else->error("Only source-owned cursors within the initial budget")}
        },onInitialPage={assertEquals(first,it);events+="head"})
        assertEquals(listOf("head","owned-page-2","owned-page-3"),events)
        assertEquals(6,result.page.posts.size);assertEquals(3,result.pages);assertNull(result.pendingReason)
    }

    @Test fun aPopulatedInitialWindowAndAWarmRefreshDoNotRequestExtraHistory():Unit=runBlocking {
        val first=page(post("106"),post("105"),next="unused-cursor")
        var streamed=0
        for(head in listOf(emptySet(),setOf(key(first.posts.last())))) {
            val result=FeedCatchUp(initialPages=3,initialPosts=2).collect(Platform.X,head,first={first},
                next={error("The required window is already present")},onInitialPage={streamed++})
            assertEquals(1,result.pages)
        }
        assertEquals(1,streamed)
    }

    @Test fun initialCoverageStopsAtOldHistoryButOnePinnedPostDoesNotEndIt():Unit=runBlocking {
        val now=1_800_000_000_000L
        val recent=post("106",now);val pin=post("100",now-10*86_400_000L)
        val first=page(recent,pin,next="owned-history")
        val continued=FeedCatchUp(initialPages=3).collect(Platform.X,emptySet(),first={first},next={
            page(post("105",now-60_000),post("99",now-11*86_400_000L),next="too-old")
        })
        assertEquals(2,continued.pages);assertEquals(4,continued.page.posts.size)
        val inactive=FeedCatchUp(initialPages=3).collect(Platform.X,emptySet(),first={
            page(recent,pin,post("99",now-11*86_400_000L),next="too-old")
        },next={error("Old public history already bounds this initial window")})
        assertEquals(1,inactive.pages)
    }

    @Test fun anOldPinnedCachedPostIsNotThePreviouslyNewestHeadFrontier(): Unit=runBlocking {
        val pinned=post("1")
        val previousNewest=post("100")
        val first=page(pinned,post("105"),post("104"),next="continue-below-pinned")
        var continuations=0
        val result=FeedCatchUp().collect(Platform.X,setOf(key(previousNewest)),first={first},next={
            assertEquals(first.next,it.next);continuations++
            page(post("103"),post("102"),post("101"),previousNewest)
        })
        assertEquals(1,continuations)
        assertNull(result.pendingReason)
        assertEquals(listOf("105","104","103","102","101","100","1"),result.page.posts.map {it.id})
    }

    @Test fun aRepeatedCursorStopsAndLeavesTheSubscriptionVisiblyPending(): Unit=runBlocking {
        var continuations=0
        val first=page(post("105"),next="same-real-cursor")
        val result=FeedCatchUp().collect(Platform.X,setOf(key(post("100"))),first={first},next={
            continuations++;assertEquals("same-real-cursor",it.next)
            page(post("104"),next="same-real-cursor")
        })
        assertEquals(1,continuations)
        assertEquals(2,result.pages)
        assertNotNull(result.pendingReason)
        assertEquals(listOf("105","104"),result.page.posts.map {it.id})
        assertNull(result.page.next)
    }

    @Test fun repeatedPagesWithDifferentCursorsRemainPendingAtThePageBudget(): Unit=runBlocking {
        var reads=1
        val result=FeedCatchUp(maxPages=3).collect(Platform.X,setOf(key(post("100"))),first={page(post("105"),next="real-1")},next={previous->
            assertEquals("real-${reads}",previous.next)
            reads++
            page(post("105"),next="real-$reads")
        })
        assertEquals(3,reads)
        assertEquals(3,result.pages)
        assertEquals(listOf("105"),result.page.posts.map {it.id})
        assertNotNull(result.pendingReason)
    }

    @Test fun reachingThePageBudgetRetainsFetchedPostsWithoutClaimingTheGapWasBridged(): Unit=runBlocking {
        var continuations=0
        val result=FeedCatchUp(maxPages=1).collect(Platform.X,setOf(key(post("100"))),first={page(post("105"),next="real-next")},next={continuations++;error("Page budget exceeded")})
        assertEquals(0,continuations)
        assertEquals(1,result.pages)
        assertEquals(listOf("105"),result.page.posts.map {it.id})
        assertNotNull(result.pendingReason)
    }

    @Test fun aTruncatedPostWindowRemainsPendingEvenIfTheDroppedRowWasTheOldHead(): Unit=runBlocking {
        val head=post("100")
        val result=FeedCatchUp(maxPosts=3).collect(Platform.X,setOf(key(head)),first={page(post("104"),post("103"),post("102"),post("101"),head)},next={error("No continuation was supplied")})
        assertEquals(listOf("104","103","102"),result.page.posts.map {it.id})
        assertNotNull(result.pendingReason)
        assertNull(result.page.next)
    }

    @Test fun canonicalDuplicatesDoNotConsumeTheDistinctPostBudgetOrHideTheOldHead(): Unit=runBlocking {
        val current=post("105")
        val alias=current.copy(canonicalUrl="https://mobile.twitter.com/alice/status/105?utm_source=share",body="Updated complete public post")
        val head=post("100")
        val result=FeedCatchUp(maxPosts=2).collect(Platform.X,setOf(key(head)),first={page(current,next="real-next")},next={page(alias,head)})
        assertEquals(2,result.page.posts.size)
        assertEquals(listOf(alias,head),result.page.posts)
        assertNull(result.pendingReason)
    }

    @Test fun continuationFailureRetainsAllGatheredPostsAndTheLastCompletedPageCount(): Unit=runBlocking {
        val first=page(post("105"),post("104"),next="actual-next")
        val result=FeedCatchUp().collect(Platform.X,setOf(key(post("100"))),first={first},next={
            assertEquals("actual-next",it.next)
            throw SourceFailure(HealthStatus.UNAVAILABLE,"Source unavailable")
        })
        assertEquals(first.posts,result.page.posts)
        assertEquals(1,result.pages)
        assertNotNull(result.pendingReason)
        assertNull(result.page.next)
    }

    @Test fun cachedIndexedAndEmptyResponsesNeverCertifyASuccessfulRefresh(): Unit=runBlocking {
        val cached=provenance.copy(cached=true)
        val cacheRole=provenance.copy(source=source.copy(role=SourceRole.CACHE))
        val indexed=provenance.copy(source=source.copy(frontend="librey"))
        val invalid=listOf(page(post("105"),provenance=cached),page(post("105"),provenance=cacheRole),page(post("105").copy(provenance=indexed),provenance=indexed),page())
        for(first in invalid) {
            val result=FeedCatchUp().collect(Platform.X,emptySet(),first={first},next={error("An invalid source must not be crawled")})
            assertNotNull(result.pendingReason)
            assertTrue(result.page.posts.isEmpty())
        }
    }

    @Test fun invalidContinuationCannotReplaceOrDiscardPreviouslyGatheredReadableContent(): Unit=runBlocking {
        val first=page(post("105"),next="real-next")
        val invalid=page(post("105").copy(body="Unreadable preview",preview=true),post("104"),provenance=provenance.copy(cached=true))
        val result=FeedCatchUp().collect(Platform.X,setOf(key(post("100"))),first={first},next={invalid})
        assertEquals(first.posts,result.page.posts)
        assertEquals(2,result.pages)
        assertNotNull(result.pendingReason)
    }

    @Test fun aResolvedNativePostCanBeRetainedWithoutCertifyingAnIndexedFeed(): Unit=runBlocking {
        val indexed=provenance.copy(source=source.copy(frontend="librey"))
        val real=post("105")
        val result=FeedCatchUp().collect(Platform.X,emptySet(),first={page(real,provenance=indexed)},next={error("Indexed fallback is not a fresh timeline")})
        assertEquals(listOf(real),result.page.posts)
        assertNotNull(result.pendingReason)
    }

    @Test fun cancellationFromEitherTheInitialOrContinuationReadPropagates(): Unit=runBlocking {
        for(initial in listOf(true,false)) {
            val expected=CancellationException("Reader stopped")
            try {
                FeedCatchUp().collect(Platform.X,setOf(key(post("100"))),first={if(initial)throw expected else page(post("105"),next="real-next")},next={throw expected})
                fail("Cancellation must not be turned into an ordinary pending result")
            }catch(actual: CancellationException){assertSame(expected,actual)}
        }
    }

    @Test fun anOwnedDeadlineRetainsCompletedPagesAndCancelsTheUnfinishedRead():Unit=runBlocking {
        val first=page(post("105"),next="actual-second-page")
        val second=page(post("104"),next="actual-third-page")
        var reads=0;var cancelled=false
        val result=FeedCatchUp(timeoutMillis=100).collect(Platform.X,setOf(key(post("100"))),first={first},next={previous->
            reads++
            if(reads==1){assertEquals(first.next,previous.next);second}
            else try {assertEquals(second.next,previous.next);awaitCancellation()}finally {cancelled=true}
        })
        assertTrue(cancelled);assertEquals(2,reads);assertEquals(2,result.pages)
        assertEquals(listOf("105","104"),result.page.posts.map {it.id})
        assertEquals(second.provenance,result.page.provenance);assertNull(result.page.next)
        assertTrue(requireNotNull(result.pendingReason).contains("timed out"))
    }

    @Test fun aDeadlineBeforeTheFirstPageFailsWithoutInventingAnEmptySuccess():Unit=runBlocking {
        var cancelled=false
        val failure=try {
            FeedCatchUp(timeoutMillis=50).collect(Platform.X,emptySet(),first={try {awaitCancellation()}finally {cancelled=true}},next={error("No first page")})
            fail("An unfinished first read must fail");null
        }catch(e:SourceFailure){e}
        assertTrue(cancelled);assertEquals(HealthStatus.UNAVAILABLE,failure!!.status)
        assertTrue(failure.message.orEmpty().contains("before a public page"))
    }

    @Test fun aShorterCallerDeadlineStillCancelsInsteadOfPublishingPartialWork():Unit=runBlocking {
        var cancelled=false
        try {
            withTimeout(50) {
                FeedCatchUp(timeoutMillis=5_000).collect(Platform.X,setOf(key(post("100"))),first={page(post("105"),next="actual-next")},next={
                    try {awaitCancellation()}finally {cancelled=true}
                })
            }
            fail("Caller cancellation must not become a pending result")
        }catch(_:kotlinx.coroutines.TimeoutCancellationException){assertTrue(cancelled)}
    }

    @Test fun aSuccessfulOwnedDeadlinePreservesOrdinaryCompletionAndNoExtraReads():Unit=runBlocking {
        val head=post("100");val first=page(post("105"),next="actual-next")
        val result=FeedCatchUp(timeoutMillis=5_000).collect(Platform.X,setOf(key(head)),first={first},next={page(head)})
        assertEquals(2,result.pages);assertNull(result.pendingReason)
        assertEquals(listOf("105","100"),result.page.posts.map {it.id})
    }

    @Test fun invalidBudgetsAreRejected() {
        for(deadline in listOf(0L,-1L))assertThrows(IllegalArgumentException::class.java){FeedCatchUp(timeoutMillis=deadline)}
        for((pages,posts) in listOf(0 to 10,1 to 0,-1 to 10,1 to -1)) {
            try {FeedCatchUp(pages,posts);fail("Invalid catch-up budget")}
            catch(_: IllegalArgumentException) { }
        }
    }
}
