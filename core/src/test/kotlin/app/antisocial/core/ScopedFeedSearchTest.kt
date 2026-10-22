package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScopedFeedSearchTest {
    private val source = Source(Platform.REDDIT, "redlib", "https://reader.example")
    private val provenance = Provenance(source, 1000L, "https://reader.example/r/space")
    private val container = Target(Platform.REDDIT, TargetKind.COMMUNITY, "r/space", "https://www.reddit.com/r/space")
    private fun post(id: Int, body: String, title: String = "") = Post(
        "$id", Platform.REDDIT, "https://www.reddit.com/r/space/comments/$id/post", "A display name", body, title
    )
    private fun page(posts: List<Post>, next: String? = null) = Page(posts, next, Profile(container, "Space"), provenance,
        collections = listOf(Collection(container, "Not a search post")), accounts = listOf(Profile(container, "Not an account result")))

    @Test fun matchesCaseFoldedWordsAndQuotedPhrasesWithoutTreatingOperatorsAsInstructions(): Unit = runBlocking {
        val reader = ScopedFeedSearch("SPACE \"red fox\"", { page(listOf(
            post(1, "A RED\nFOX went to space"), post(2, "space red quick fox"), post(3, "red fox", "ＳＰＡＣＥ")
        )) })
        assertEquals(listOf("1", "3"), reader.next(null).posts.map { it.id })
        val literal = ScopedFeedSearch("from:alice", { page(listOf(post(4, "from:alice is literal text"), post(5, "Alice wrote this"))) })
        assertEquals("4", literal.next(null).posts.single().id)
    }

    @Test fun retainsOriginalSourceMetadataWhileReturningOnlyPublicPostMatches(): Unit = runBlocking {
        val first = page(listOf(post(1, "First match")), "opaque-source-token")
        var calls = 0
        val reader = ScopedFeedSearch("match", { previous ->
            calls++
            if (previous == null) first else {
                assertSame(first, previous)
                assertEquals("opaque-source-token", previous.next)
                assertEquals("Space", previous.profile!!.name)
                page(listOf(post(2, "Second match"))).copy(notice = "Source availability varies.")
            }
        })
        val filtered = reader.next(null)
        assertNull(filtered.profile); assertTrue(filtered.accounts.isEmpty()); assertTrue(filtered.collections.isEmpty())
        assertEquals(first.provenance, filtered.provenance); assertEquals(first.next, filtered.next)
        val second = reader.next(filtered)
        assertEquals("2", second.posts.single().id); assertNull(second.next)
        assertTrue(second.notice!!.contains("Searches available public posts from this feed."))
        assertTrue(second.notice!!.contains("Source availability varies."))
        reader.next(second); assertEquals(2, calls)
    }

    @Test fun looksAheadThreePagesAndKeepsContinuationWhenNoMatchWasFound(): Unit = runBlocking {
        var calls = 0
        val reader = ScopedFeedSearch("needle", { calls++; page(listOf(post(calls, if (calls == 5) "needle" else "unrelated")), "next-$calls") })
        val empty = reader.next(null)
        assertEquals(3, calls); assertTrue(empty.posts.isEmpty()); assertEquals("next-3", empty.next)
        val found = reader.next(empty)
        assertEquals(5, calls); assertEquals("5", found.posts.single().id); assertEquals("next-5", found.next)
    }

    @Test fun stopsRepeatedContinuationsAndDuplicateOnlySourcePages(): Unit = runBlocking {
        var cursorCalls = 0
        val cursors = ScopedFeedSearch("absent", { cursorCalls++; page(listOf(post(cursorCalls, "unrelated")), "same") })
        assertNull(cursors.next(null).next); assertEquals(2, cursorCalls)
        cursors.next(null); assertEquals(2, cursorCalls)
        var duplicateCalls = 0
        val duplicates = ScopedFeedSearch("absent", { duplicateCalls++; page(listOf(post(1, "unrelated")), "new-$duplicateCalls") })
        assertNull(duplicates.next(null).next); assertEquals(2, duplicateCalls)
    }

    @Test fun deduplicatesCanonicalPostIdentityAcrossDifferentReaderIds(): Unit = runBlocking {
        var calls = 0
        val reader = ScopedFeedSearch("needle", { calls++; if (calls == 1) page(listOf(post(1, "needle")), "next")
            else page(listOf(post(1, "needle").copy(id = "a different database id"), post(2, "needle"))) })
        val first = reader.next(null)
        assertEquals(listOf("1"), first.posts.map { it.id })
        assertEquals(listOf("2"), reader.next(first).posts.map { it.id })
    }

    @Test fun boundsTotalSourcePagesAndInspectedPostsIncludingNonMatches(): Unit = runBlocking {
        var calls = 0
        val pages = ScopedFeedSearch("absent", { calls++; page(listOf(post(calls, "unrelated")), "next-$calls") }, maxPages = 4)
        val first = pages.next(null); val last = pages.next(first)
        assertEquals(4, calls); assertNull(last.next); assertTrue(last.notice!!.contains("reading limit"))
        var postCalls = 0
        val posts = ScopedFeedSearch("needle", { postCalls++; page((1..10).map { post(it, if (it > 3) "needle" else "unrelated") }, "next") }, maxPosts = 3)
        val bounded = posts.next(null)
        assertEquals(1, postCalls); assertTrue(bounded.posts.isEmpty()); assertNull(bounded.next)
        assertTrue(bounded.notice!!.contains("reading limit"))
    }

    @Test fun boundsTitleAndBodyCharactersBeforeMatchingOversizedPosts(): Unit = runBlocking {
        val reader = ScopedFeedSearch("needle", { page(listOf(post(1, "needle"), post(2, "needle", "x".repeat(100))), "next") }, maxCharacters = 20)
        val result = reader.next(null)
        assertEquals(listOf("1"), result.posts.map { it.id }); assertNull(result.next)
        assertTrue(result.notice!!.contains("reading limit"))
        val naturallyFinished = ScopedFeedSearch("needle", { page(listOf(post(3, "needle"))) }, maxCharacters = 6)
        assertFalse(naturallyFinished.next(null).notice!!.contains("reading limit"))
    }

    @Test fun cancelledAndFailedLoadsRemainRetryableWithoutConsumingSourcePages(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cancel = true; var calls = 0
        val reader = ScopedFeedSearch("needle", { calls++; if (cancel) { started.complete(Unit); awaitCancellation() } else page(listOf(post(1, "needle"))) })
        val job = launch { reader.next(null) }
        started.await(); job.cancelAndJoin(); cancel = false
        assertEquals("1", reader.next(null).posts.single().id); assertEquals(2, calls)
        var failLoad = true
        val retry = ScopedFeedSearch("needle", { if (failLoad) error("Source unavailable") else page(listOf(post(2, "needle"))) })
        try { retry.next(null); fail("Expected source failure") } catch (e: IllegalStateException) { assertEquals("Source unavailable", e.message) }
        failLoad = false; assertEquals("2", retry.next(null).posts.single().id)
    }
}
