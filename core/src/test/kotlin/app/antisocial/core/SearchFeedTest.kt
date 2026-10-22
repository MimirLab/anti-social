package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchFeedTest {
    @Test fun emptyBatchesHaveDistinctCompletionRevisionsWhenLoadingFramesCoalesce(): Unit=runBlocking {
        val session=SearchFeed(target,discover={index(1,2,3)},read={Page(emptyList())},blockSize=2)
        session.loadNext {}
        val first=session.state
        assertTrue(first.hasMore);assertTrue(first.page.posts.isEmpty());assertTrue(first.revision>0)
        session.loadNext {}
        assertFalse(session.state.hasMore);assertTrue(session.state.revision>first.revision)
    }
    private val target=Target(Platform.X,TargetKind.SEARCH,"space","https://x.com/search?q=space")
    private val source=Source(Platform.X,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
    private val provenance=Provenance(source,1000,source.root+"/html/")
    private fun destination(id: Int)=Target(Platform.X,TargetKind.POST,"writer/status/$id","https://x.com/writer/status/$id")
    private fun post(id: Int)=Post("$id",Platform.X,destination(id).canonicalUrl,"writer","Real post $id",publishedAt=id.toLong(),provenance=provenance)
    private fun index(vararg ids: Int,next: String?=null)=Page(emptyList(),next=next,provenance=provenance,collections=ids.map {Collection(destination(it),"Index title","", "Incomplete search snippet")})

    @Test fun resolvesOnlyActualRequestedPostsAndStreamsCompletionOrder(): Unit=runBlocking {
        val first=CompletableDeferred<Page>();val second=CompletableDeferred<Page>()
        val active=AtomicInteger();val peak=AtomicInteger();val seen=mutableListOf<SearchFeedState>()
        val profileTarget=Target(Platform.X,TargetKind.PROFILE,"NASA","https://x.com/NASA")
        val session=SearchFeed(target,discover={index(1,2).copy(collections=index(1,2).collections+Collection(profileTarget,"Indexed name"))},read={requested->
            val n=active.incrementAndGet();peak.updateAndGet {maxOf(it,n)}
            try {when(requested) {destination(1)->first.await();destination(2)->second.await();else->Page(emptyList(),profile=Profile(profileTarget,"Real NASA","Real biography"))}}finally {active.decrementAndGet()}
        })
        val job=launch {session.loadNext {seen+=it}}
        yield();second.complete(Page(listOf(post(2),post(999))))
        withTimeout(2000){while(session.state.page.posts.isEmpty())yield()}
        assertTrue(session.state.loading);assertEquals(listOf("2"),session.state.page.posts.map {it.id})
        first.complete(Page(listOf(post(1))));job.join()
        assertEquals(listOf("2","1"),session.state.page.posts.map {it.id})
        assertEquals("Real NASA",session.state.page.accounts.single().name)
        assertTrue(session.state.page.collections.isEmpty());assertFalse(session.state.hasMore)
        assertEquals(2,peak.get());assertTrue(seen.any {it.loading&&it.page.posts.size==1})
    }
    @Test fun prefetchesTheNextCursorAsThePendingBlockDrainsAndDeduplicatesAcrossPages(): Unit=runBlocking {
        var discoveries=0;val reads=mutableListOf<Target>()
        val session=SearchFeed(target,discover={previous->discoveries++;if(previous==null)index(1,2,3,next="next")else {assertEquals("next",previous.next);index(3,4)}},read={reads+=it;Page(listOf(post(it.identity.substringAfterLast('/').toInt())))},blockSize=2)
        session.loadNext {};assertEquals(2,discoveries);assertEquals(2,reads.size);assertTrue(session.state.hasMore)
        session.loadNext {};assertEquals(2,discoveries);assertEquals(4,reads.size);assertEquals(4,session.state.page.posts.size)
        session.loadNext {};assertEquals(2,discoveries);assertFalse(session.state.hasMore)
    }
    @Test fun cancellationAndDuplicateRequestsDoNotLosePendingResults(): Unit=runBlocking {
        var calls=0;var blocked=true;val started=CompletableDeferred<Unit>()
        val session=SearchFeed(target,discover={index(1)},read={calls++;started.complete(Unit);if(blocked)awaitCancellation()else Page(listOf(post(1)))})
        val job=launch {session.loadNext {}}
        started.await();session.loadNext {};assertEquals(1,calls)
        job.cancelAndJoin();assertFalse(session.state.loading)
        blocked=false;session.loadNext {};assertEquals(2,calls);assertEquals("1",session.state.page.posts.single().id)
    }
    @Test fun unavailableContentIsNotReplacedWithAnIndexSnippetAndPagingFailureCanRetry(): Unit=runBlocking {
        var attempts=0
        val session=SearchFeed(target,discover={previous->if(previous==null)index(1,2,next="next")else {attempts++;if(attempts==1)error("Provider unavailable")else index(3)}},read={if(it==destination(2))error("Login wall")else Page(listOf(post(it.identity.substringAfterLast('/').toInt())))})
        session.loadNext {};assertEquals(1,session.state.unavailable);assertEquals(listOf("1"),session.state.page.posts.map {it.id})
        assertEquals("Provider unavailable",session.state.error);assertEquals(1,session.state.page.posts.size);assertTrue(session.state.hasMore)
        session.loadNext {};assertNull(session.state.error);assertEquals(listOf("3","1"),session.state.page.posts.map {it.id});assertFalse(session.state.hasMore)
    }
    @Test fun repeatedCursorsAndSessionBoundsStopAutomaticFetching(): Unit=runBlocking {
        var calls=0
        val repeating=SearchFeed(target,discover={calls++;Page(listOf(post(calls)),next="same",provenance=provenance)},read={error("No hydration expected")})
        repeat(5){repeating.loadNext {}}
        assertEquals(2,calls);assertFalse(repeating.state.hasMore)
        val bounded=SearchFeed(target,discover={Page((1..10).map {post(it)},provenance=provenance)},read={error("No hydration expected")},maxPosts=3)
        bounded.loadNext {};assertEquals(3,bounded.state.page.posts.size);assertTrue(bounded.state.limited);assertFalse(bounded.state.hasMore)
        val small=SearchFeed(target,discover={Page(listOf(post(1).copy(body="x".repeat(10000))),provenance=provenance)},read={error("No hydration expected")},maxCharacters=1024)
        small.loadNext {};assertTrue(small.state.page.posts.isEmpty());assertTrue(small.state.limited)
    }
    @Test fun undatedPublicPostsRemainVisibleWithoutInventingADate(): Unit=runBlocking {
        val session=SearchFeed(target,discover={Page(listOf(post(1).copy(publishedAt=null),post(2)),provenance=provenance)},read={error("No hydration expected")})
        session.loadNext {};assertEquals(listOf("2","1"),session.state.page.posts.map {it.id});assertNull(session.state.page.posts.last().publishedAt)
    }
    @Test fun continuationAndDestinationReadsOverlapAndUsefulResultsStreamBeforeSlowReads(): Unit=runBlocking {
        val readsStarted=CompletableDeferred<Unit>();val continuationStarted=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val active=AtomicInteger();val peak=AtomicInteger()
        val session=SearchFeed(target,discover={previous->if(previous==null)index(1,2,next="real-cursor")else {
            assertEquals("real-cursor",previous.next);continuationStarted.complete(Unit)
            readsStarted.await();Page(listOf(post(3)),provenance=provenance)
        }},read={requested->val count=active.incrementAndGet();peak.updateAndGet {maxOf(it,count)};if(count==2)readsStarted.complete(Unit)
            try {release.await();Page(listOf(post(requested.identity.substringAfterLast('/').toInt())))}finally {active.decrementAndGet()}
        })
        val job=launch {session.loadNext {}}
        withTimeout(2000){continuationStarted.await();readsStarted.await();while(session.state.page.posts.isEmpty())yield()}
        assertEquals(listOf("3"),session.state.page.posts.map {it.id});assertTrue(session.state.loading);assertEquals(2,peak.get())
        release.complete(Unit);job.join();assertEquals(listOf("3","2","1"),session.state.page.posts.map {it.id})
    }
    @Test fun emptyAndRepeatedContentPagesDoNotHideLaterPostsBehindDistinctCursors(): Unit=runBlocking {
        var pages=0
        val session=SearchFeed(target,discover={pages++;when(pages){1->Page(emptyList(),next="cursor-1");2->Page(emptyList(),next="cursor-2");else->Page(listOf(post(8)))}},read={error("No destination read")})
        session.loadNext {};assertEquals(2,pages);assertTrue(session.state.hasMore);assertFalse(session.state.limited);assertNull(session.state.error)
        session.loadNext {};assertEquals(3,pages);assertEquals("8",session.state.page.posts.single().id);assertFalse(session.state.hasMore)
    }
    @Test fun unreadableCandidatesNeverHideUsefulPendingPostsAfterTwoFailedReads(): Unit=runBlocking {
        val session=SearchFeed(target,discover={index(1,2,3)},read={if(it==destination(3))Page(listOf(post(3)))else Page(emptyList())},blockSize=2)
        session.loadNext {};assertEquals(2,session.state.unavailable);assertTrue(session.state.hasMore);assertFalse(session.state.limited)
        session.loadNext {};assertEquals("3",session.state.page.posts.single().id);assertFalse(session.state.hasMore)
    }
    @Test fun aFullPeopleShelfDoesNotEndPostDiscovery(): Unit=runBlocking {
        fun account(id: Int)=Profile(Target(Platform.X,TargetKind.PROFILE,"writer$id","https://x.com/writer$id"),"Writer $id")
        val session=SearchFeed(target,discover={previous->if(previous==null)Page(emptyList(),accounts=listOf(account(1),account(2)),next="posts-next")else Page(listOf(post(3)))},read={error("No destination read")},maxAccounts=1)
        session.loadNext {};assertEquals(1,session.state.page.accounts.size);assertEquals("3",session.state.page.posts.single().id);assertFalse(session.state.limited)
    }
    @Test fun canceledLookaheadAndReadsResumeWithoutLosingTheRealCursorOrPendingCandidates(): Unit=runBlocking {
        val continuation=CompletableDeferred<Unit>();val reading=CompletableDeferred<Unit>();var blocked=true;var canceled=0;var continuations=0
        val session=SearchFeed(target,discover={previous->if(previous==null)index(1,2,next="preserved-cursor")else {
            continuations++;assertEquals("preserved-cursor",previous.next);continuation.complete(Unit)
            if(blocked)try {awaitCancellation()}finally {canceled++}else Page(listOf(post(3)))
        }},read={reading.complete(Unit);if(blocked)try {awaitCancellation()}finally {canceled++}else Page(listOf(post(it.identity.substringAfterLast('/').toInt())))})
        val job=launch {session.loadNext {}};withTimeout(2000){continuation.await();reading.await()};job.cancelAndJoin();session.pause()
        assertTrue(session.state.paused);assertFalse(session.state.loading);assertTrue(canceled>=2)
        blocked=false;session.loadNext {};assertFalse(session.state.paused);assertEquals(2,continuations);assertEquals(listOf("3","2","1"),session.state.page.posts.map {it.id})
    }
    @Test fun distinctEmptyCursorsAndCandidateFloodsRespectGlobalBudgets(): Unit=runBlocking {
        var pages=0
        val empty=SearchFeed(target,discover={pages++;Page(emptyList(),next="next-$pages")},read={error("No reads")},maxPages=3)
        repeat(10){empty.loadNext {}};assertEquals(3,pages);assertTrue(empty.state.limited);assertFalse(empty.state.hasMore)
        val reads=AtomicInteger()
        val flood=SearchFeed(target,discover={index(*(1..50).toList().toIntArray(),next="more")},read={reads.incrementAndGet();Page(listOf(post(it.identity.substringAfterLast('/').toInt())))},maxCandidates=3)
        repeat(10){flood.loadNext {}};assertEquals(3,reads.get());assertTrue(flood.state.limited);assertFalse(flood.state.hasMore)
    }
}
