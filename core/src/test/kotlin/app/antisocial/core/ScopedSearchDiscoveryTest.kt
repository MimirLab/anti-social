package app.antisocial.core

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScopedSearchDiscoveryTest {
    private fun post(id: Int) = Post("$id", Platform.BLUESKY, "https://bsky.app/profile/bsky.app/post/$id", "Bluesky", "A Bluesky update")
    private fun page(id: Int, next: String? = null) = Page(listOf(post(id)), next)

    @Test fun successfulNativeResultsIncludingEmptySearchNeverReadTheFeed(): Unit = runBlocking {
        var feeds = 0
        val first = page(1, "native-cursor")
        val discovery = ScopedSearchDiscovery(native = { previous ->
            if (previous == null) first else { assertSame(first, previous); Page(emptyList()) }
        }, fallback = ScopedFeedSearch("Bluesky", { feeds++; error("No fallback expected") }))
        val found = discovery.next(null)
        assertSame(first, found)
        assertTrue(discovery.next(found).posts.isEmpty())
        assertEquals(0, feeds)
    }

    @Test fun failedNativeContinuationSelectsFeedAndKeepsItsOriginalCursors(): Unit = runBlocking {
        var nativeCalls = 0; var feedCalls = 0
        val nativePage = page(1, "native-cursor")
        val feedPage = page(2, "feed-cursor")
        val scanner = ScopedFeedSearch("Bluesky", { previous ->
            feedCalls++
            if (previous == null) feedPage else {
                assertSame(feedPage, previous); assertEquals("feed-cursor", previous.next)
                page(3)
            }
        })
        val discovery = ScopedSearchDiscovery(native = { previous ->
            nativeCalls++
            if (previous == null) nativePage else { assertSame(nativePage, previous); throw SourceFailure(HealthStatus.UNAVAILABLE, "HTTP 403") }
        }, fallback = scanner)
        val first = discovery.next(null)
        val recovered = discovery.next(first)
        assertEquals("2", recovered.posts.single().id); assertEquals("feed-cursor", recovered.next)
        assertTrue(recovered.notice!!.contains("available public posts from this feed"))
        assertEquals("3", discovery.next(recovered).posts.single().id)
        assertEquals(2, nativeCalls); assertEquals(2, feedCalls)
    }

    @Test fun cancellationRateLimitsAndMissingFallbackPreserveTheOriginalFailure(): Unit = runBlocking {
        var feeds = 0
        fun scanner() = ScopedFeedSearch("Bluesky", { feeds++; page(1) })
        val cancelled = CancellationException("Reader cancelled")
        val limited = SourceFailure(HealthStatus.RATE_LIMITED, "HTTP 429")
        val absent = IOException("No public route")
        val cases = listOf(
            ScopedSearchDiscovery({ throw cancelled }, scanner()) to cancelled,
            ScopedSearchDiscovery({ throw limited }, scanner()) to limited,
            ScopedSearchDiscovery({ throw absent }, null) to absent
        )
        for ((discovery, failure) in cases) {
            try { discovery.next(null); fail("Expected original failure") }
            catch (e: Exception) { assertSame(failure, e) }
        }
        assertEquals(0, feeds)
    }

    @Test fun ioFailureSelectsFeedAndFailedFeedRetryDoesNotReturnToNative(): Unit = runBlocking {
        var nativeCalls = 0; var feedCalls = 0
        val feedFailure = IOException("Public feed temporarily unavailable")
        val discovery = ScopedSearchDiscovery(native = { nativeCalls++; throw IOException("Native route unavailable") },
            fallback = ScopedFeedSearch("Bluesky", { previous ->
                assertNull(previous); feedCalls++
                if (feedCalls == 1) throw feedFailure else page(2)
            }))
        try { discovery.next(null); fail("Expected feed failure") }
        catch (e: IOException) { assertSame(feedFailure, e) }
        assertEquals("2", discovery.next(null).posts.single().id)
        assertEquals(1, nativeCalls); assertEquals(2, feedCalls)
    }

    @Test fun feedFallbackCanScanPastMultipleEmptyBlocksAfterNativeResults(): Unit = runBlocking {
        var nativeCalls=0;var feedCalls=0
        val target=SearchScopes.apply(Target(Platform.BLUESKY,TargetKind.SEARCH,"needle","https://bsky.app/search?q=needle"),SearchScope(SearchScopeKind.ACCOUNT,"bsky.app"))
        val discovery=ScopedSearchDiscovery(native={previous->
            nativeCalls++
            if(previous==null)page(1,"native-cursor").copy(posts=listOf(post(1).copy(body="needle native result")))
            else throw SourceFailure(HealthStatus.UNAVAILABLE,"Native search continuation unavailable")
        },fallback=ScopedFeedSearch("needle",load={
            feedCalls++
            Page(listOf(post(100+feedCalls).copy(body=if(feedCalls==7)"needle in an older public post"else "Unrelated public post $feedCalls")),next=if(feedCalls<7)"feed-cursor-$feedCalls"else null)
        }))
        val session=SearchFeed(target,discover=discovery::next,read={error("Native posts need no hydration")},allowEmptyDiscoveryPages={discovery.usingFallback})
        var streamedNativeBeforeFallback=false
        session.loadNext{if(it.page.posts.isNotEmpty()&&!discovery.usingFallback)streamedNativeBeforeFallback=true}
        assertTrue(streamedNativeBeforeFallback);assertEquals(1,session.state.page.posts.size)
        assertTrue(discovery.usingFallback);assertTrue(session.state.hasMore);assertEquals(3,feedCalls)
        session.loadNext{}
        assertEquals(2,nativeCalls);assertEquals(7,feedCalls)
        assertEquals(setOf("needle native result","needle in an older public post"),session.state.page.posts.map {it.body}.toSet())
        assertFalse(session.state.hasMore)
    }

    @Test fun anEmptyNativeContinuationStillStopsWithoutScanningThePublicFeed(): Unit = runBlocking {
        var nativeCalls=0;var feedCalls=0
        val target=SearchScopes.apply(Target(Platform.BLUESKY,TargetKind.SEARCH,"needle","https://bsky.app/search?q=needle"),SearchScope(SearchScopeKind.ACCOUNT,"bsky.app"))
        val discovery=ScopedSearchDiscovery(native={previous->
            nativeCalls++
            if(previous==null)page(1,"native-cursor").copy(posts=listOf(post(1).copy(body="needle native result")))
            else Page(emptyList(),next="native-empty-cursor")
        },fallback=ScopedFeedSearch("needle",load={feedCalls++;error("Successful native search must not select a fallback")}))
        val session=SearchFeed(target,discover=discovery::next,read={error("No hydration")},allowEmptyDiscoveryPages={discovery.usingFallback})
        repeat(3){session.loadNext{}}
        assertFalse(discovery.usingFallback);assertEquals(2,nativeCalls);assertEquals(0,feedCalls)
        assertFalse(session.state.hasMore);assertEquals(1,session.state.page.posts.size)
    }
}
