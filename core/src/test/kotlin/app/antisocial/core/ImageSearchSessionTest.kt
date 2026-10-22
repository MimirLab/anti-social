package app.antisocial.core

import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ImageSearchSessionTest {
    private fun asset(name: String) = JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val routes = DarwinRoutes(asset("darwin-routes.json"), UrlCleaner(asset("clean-links.json")))
    private val bing = ImageSearchEngine.BING
    private val lens = ImageSearchEngine.GOOGLE_LENS
    private fun target(id: Int) = requireNotNull(routes.recognize("https://x.com/writer/status/$id"))
    private fun post(id: Int) = Post("$id", Platform.X, target(id).canonicalUrl, "writer", "Actual post $id", publishedAt = id.toLong())
    private fun links(vararg ids: Int, next: String? = null) = ImageSearchLinks(ids.map { target(it).canonicalUrl }, next)
    private fun session(
        engines: List<ImageSearchEngine> = listOf(bing),
        discover: suspend (ImageSearchEngine, String?) -> ImageSearchLinks,
        read: suspend (Target) -> Page,
        blockSize: Int = 12
    ) = ImageSearchSession(engines, discover, { routes.recognize(it) }, read, blockSize = blockSize)

    @Test fun fastEnginePublishesActualCardsWhileAnotherEngineIsStillWaiting(): Unit = runBlocking {
        val slow = CompletableDeferred<ImageSearchLinks>()
        val states = mutableListOf<ImageSearchState>()
        val reader = session(listOf(bing, lens, ImageSearchEngine.YANDEX), { engine, _ ->
            if (engine == ImageSearchEngine.YANDEX) links(2) else slow.await()
        }, { Page(listOf(post(2))) })
        val job = launch { reader.loadNext { states += it } }
        withTimeout(2000) { while (reader.state.page.posts.isEmpty()) yield() }
        assertTrue(reader.state.loading)
        assertEquals("2", reader.state.page.posts.single().id)
        assertEquals(ImageSearchStage.SEARCHING, reader.state.engines.first { it.engine == bing }.stage)
        slow.complete(ImageSearchLinks(emptyList(), notice = "No public matches."))
        job.join()
        assertFalse(reader.state.loading)
        assertFalse(reader.state.hasMore)
        assertTrue(states.any { it.loading && it.page.posts.isNotEmpty() })
        assertEquals("No public matches.", reader.state.engines.first { it.engine == bing }.notice)
    }

    @Test fun canonicalDuplicatesCollapseAcrossEnginesButNumericIdsOnDifferentPlatformsDoNot(): Unit = runBlocking {
        val instagram = requireNotNull(routes.recognize("https://www.instagram.com/p/123/"))
        val reads = mutableListOf<Target>()
        val reader = session(listOf(bing, lens), { engine, _ ->
            ImageSearchLinks(if (engine == bing) listOf("https://twitter.com/one/status/123", instagram.canonicalUrl)
                else listOf("https://x.com/another/status/123?utm_source=tracking"))
        }, {
            reads += it
            Page(listOf(if (it.platform == Platform.X) post(123) else Post("123", Platform.INSTAGRAM, instagram.canonicalUrl, "elsewhere", "Different uploader")))
        })
        reader.loadNext {}
        assertEquals(2, reads.size)
        assertEquals(setOf(Platform.X, Platform.INSTAGRAM), reader.state.page.posts.map { it.platform }.toSet())
        assertEquals(2, reader.state.engines.first { it.engine == bing }.discovered)
        assertEquals(1, reader.state.engines.first { it.engine == lens }.discovered)
    }

    @Test fun hydrationRunsOnlyTwoAtATimeAndPublishesCompletionOrder(): Unit = runBlocking {
        val first = CompletableDeferred<Page>()
        val second = CompletableDeferred<Page>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val reader = session(discover = { _, _ -> links(1, 2, 3) }, read = {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
                when (it) { target(1) -> first.await(); target(2) -> second.await(); else -> Page(listOf(post(3))) }
            } finally { active.decrementAndGet() }
        })
        val job = launch { reader.loadNext {} }
        second.complete(Page(listOf(post(2), post(999))))
        withTimeout(2000) { while (reader.state.page.posts.size < 2) yield() }
        assertTrue(reader.state.loading)
        assertEquals(listOf("3", "2"), reader.state.page.posts.map { it.id })
        first.complete(Page(listOf(post(1))))
        job.join()
        assertEquals(2, peak.get())
        assertEquals(listOf("3", "2", "1"), reader.state.page.posts.map { it.id })
    }

    @Test fun snippetsPreviewsWrongPostsAndSameNumericIdentityOnWrongPlatformNeverBecomeCards(): Unit = runBlocking {
        val reader = session(discover = { _, _ -> links(1, 2, 3, 4) }, read = { requested ->
            when (requested) {
                target(1) -> Page(listOf(post(99)), notice = "Search engine snippet")
                target(2) -> Page(listOf(post(2).copy(preview = true)))
                target(3) -> Page(listOf(post(3).copy(platform = Platform.INSTAGRAM, canonicalUrl = "https://www.instagram.com/p/3")))
                else -> Page(emptyList(), collections = listOf(Collection(requested, "Indexed title", detail = "Not the actual content")))
            }
        })
        reader.loadNext {}
        assertTrue(reader.state.page.posts.isEmpty())
        assertTrue(reader.state.page.collections.isEmpty())
        assertEquals(4, reader.state.unavailable)
        assertEquals((1..4).map { target(it) }.toSet(), reader.state.unresolved.map { it.target }.toSet())
    }

    @Test fun onlyExactPublicProfilesBecomeAccountsAndSameImageNeverMergesDifferentAccounts(): Unit = runBlocking {
        val x = requireNotNull(routes.recognize("https://x.com/writer"))
        val instagram = requireNotNull(routes.recognize("https://www.instagram.com/writer"))
        val missing = requireNotNull(routes.recognize("https://x.com/missing"))
        val reads = mutableListOf<Target>()
        val reader = session(discover = { _, _ -> ImageSearchLinks(listOf(x.canonicalUrl, instagram.canonicalUrl,
            missing.canonicalUrl, "https://x.com/login", "https://x.com/search?q=writer", "https://example.org/picture")) }, read = {
            reads += it
            val profile = Profile(it, "Same display name", avatar = "https://example.org/same-avatar.jpg")
            if (it == missing) Page(emptyList(), accounts = listOf(profile)) else Page(emptyList(), profile = profile)
        })
        reader.loadNext {}
        assertEquals(3, reads.size)
        assertEquals(setOf(Platform.X, Platform.INSTAGRAM), reader.state.page.accounts.map { it.target.platform }.toSet())
        assertEquals(missing, reader.state.unresolved.single().target)
    }

    @Test fun unreadableMatchesKeepAllEngineAttributionAndCanRetryWithoutResubmittingTheImage(): Unit = runBlocking {
        var discoveries = 0
        var readable = false
        val reader = session(listOf(bing, lens), { _, _ -> discoveries++; links(1) }, {
            if (!readable) throw SourceFailure(HealthStatus.LOGIN_REQUIRED, "A login wall refused this post")
            Page(listOf(post(1)))
        })
        reader.loadNext {}
        assertEquals(setOf(bing, lens), reader.state.unresolved.single().engines)
        assertTrue(reader.state.unresolved.single().error.contains("login wall"))
        assertFalse(reader.state.hasMore)
        readable = true
        reader.retryMatch(target(1)) {}
        assertEquals(2, discoveries)
        assertEquals("1", reader.state.page.posts.single().id)
        assertEquals(0, reader.state.unavailable)
    }

    @Test fun oneEngineFailureDoesNotStopAnotherAndExplicitRetryUsesItsOriginalCursor(): Unit = runBlocking {
        val calls = mutableListOf<Pair<ImageSearchEngine, String?>>()
        var fail = true
        val reader = session(listOf(bing, lens), { engine, cursor ->
            calls += engine to cursor
            if (engine == lens) links(2)
            else if (cursor == null) links(1, next = "owned-cursor")
            else if (fail) error("Human challenge") else links(3)
        }, { Page(listOf(post(it.identity.substringAfterLast('/').toInt()))) })
        reader.loadNext {}
        reader.loadNext {}
        assertEquals(2, reader.state.page.posts.size)
        assertEquals(ImageSearchStage.FAILED, reader.state.engines.first { it.engine == bing }.stage)
        assertFalse(reader.state.hasMore)
        reader.loadNext {}
        assertEquals(3, calls.size)
        fail = false
        reader.retryEngine(bing) {}
        assertEquals(bing to "owned-cursor", calls.last())
        assertEquals(listOf("3", "2", "1"), reader.state.page.posts.map { it.id })
        assertNull(reader.state.error)
    }

    @Test fun pendingMatchesDrainBeforeAnotherDiscoveryAndRepeatedCursorsStop(): Unit = runBlocking {
        var discoveries = 0
        val readIds = mutableListOf<Int>()
        val reader = session(discover = { _, cursor -> discoveries++
            if (cursor == null) links(1, 2, 3, next = "same") else links(3, 4, next = "same")
        }, read = {
            val id = it.identity.substringAfterLast('/').toInt(); readIds += id; Page(listOf(post(id)))
        }, blockSize = 2)
        reader.loadNext {}
        assertEquals(1, discoveries)
        assertEquals(2, readIds.size)
        reader.loadNext {}
        assertEquals(1, discoveries)
        assertEquals(3, readIds.size)
        reader.loadNext {}
        assertEquals(2, discoveries)
        assertEquals(4, readIds.size)
        assertFalse(reader.state.hasMore)
        reader.loadNext {}
        assertEquals(2, discoveries)
    }

    @Test fun cancellationAndConcurrentLoadRequestsPreserveUnfinishedContent(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        var blocked = true
        var calls = 0
        var discoveries = 0
        val reader = session(discover = { _, _ -> discoveries++; links(1) }, read = {
            calls++; started.complete(Unit)
            if (blocked) awaitCancellation() else Page(listOf(post(1)))
        })
        val job = launch { reader.loadNext {} }
        started.await()
        reader.loadNext {}
        assertEquals(1, calls)
        job.cancelAndJoin()
        assertFalse(reader.state.loading)
        assertTrue(reader.state.hasMore)
        blocked = false
        reader.loadNext {}
        assertEquals(2, calls)
        assertEquals(1, discoveries)
        assertEquals("1", reader.state.page.posts.single().id)
    }

    @Test fun canceledDiscoveryIsResumableAndIsNeverRecordedAsAnEngineFailure(): Unit = runBlocking {
        var blocked = true
        val started = CompletableDeferred<Unit>()
        val reader = session(discover = { _, _ -> started.complete(Unit)
            if (blocked) awaitCancellation() else links(1)
        }, read = { Page(listOf(post(1))) })
        val job = launch { reader.loadNext {} }
        started.await(); job.cancelAndJoin()
        assertEquals(ImageSearchStage.IDLE, reader.state.engines.single().stage)
        assertNull(reader.state.engines.single().error)
        blocked = false
        reader.loadNext {}
        assertEquals("1", reader.state.page.posts.single().id)
    }

    @Test fun aReaderCancellationPropagatesInsteadOfHangingTheCoordinator(): Unit = runBlocking {
        val reader = session(discover = { _, _ -> links(1) }, read = { throw CancellationException("Reader canceled") })
        val result = async { runCatching { reader.loadNext {} }.exceptionOrNull() }
        val error = withTimeout(2000) { result.await() }
        assertTrue(error is CancellationException)
        assertFalse(reader.state.loading)
        assertTrue(reader.state.hasMore)
    }

    @Test fun engineAndReadTimeoutsAreReportedWithoutBlockingOtherProviders(): Unit = runBlocking {
        val reader = ImageSearchSession(listOf(bing, lens), { engine, _ ->
            if (engine == bing) awaitCancellation() else links(1, 2)
        }, { routes.recognize(it) }, { if (it == target(1)) awaitCancellation() else Page(listOf(post(2))) }, requestTimeoutMs = 100)
        withTimeout(3000) { reader.loadNext {} }
        assertEquals("2", reader.state.page.posts.single().id)
        assertEquals(1, reader.state.unavailable)
        assertEquals(ImageSearchStage.FAILED, reader.state.engines.first { it.engine == bing }.stage)
        assertFalse(reader.state.loading)
    }

    @Test fun targetContentAndContinuationBudgetsBoundTheSession(): Unit = runBlocking {
        var discoveries = 0
        val reader = ImageSearchSession(listOf(bing), { _, _ -> discoveries++; links(1, 2, 3, 4, 5, next = "next") },
            { routes.recognize(it) }, { Page(listOf(post(it.identity.substringAfterLast('/').toInt()))) }, maxTargets = 3)
        repeat(4) { reader.loadNext {} }
        assertEquals(1, discoveries)
        assertEquals(3, reader.state.page.posts.size)
        assertTrue(reader.state.limited)
        assertFalse(reader.state.hasMore)
        val huge = ImageSearchSession(listOf(bing), { _, _ -> links(1) }, { routes.recognize(it) },
            { Page(listOf(post(1).copy(body = "x".repeat(10000)))) }, maxCharacters = 1024)
        huge.loadNext {}
        assertTrue(huge.state.page.posts.isEmpty())
        assertTrue(huge.state.limited)
        assertFalse(huge.state.hasMore)
        val pages = ImageSearchSession(listOf(bing), { _, _ -> links(1, next = "next") }, { routes.recognize(it) },
            { Page(listOf(post(1))) }, maxPagesPerEngine = 1)
        pages.loadNext {}
        assertTrue(pages.state.limited)
        assertFalse(pages.state.hasMore)
    }

    @Test fun unavailableEnginesAndUndatedPostsKeepHonestStatus(): Unit = runBlocking {
        val failed = session(discover = { _, _ -> error("Provider refused the request") }, read = { error("No reads") })
        failed.loadNext {}
        assertTrue(failed.state.page.posts.isEmpty())
        assertNotNull(failed.state.error)
        assertFalse(failed.state.hasMore)
        val reader = session(discover = { _, _ -> links(1, 2) }, read = {
            Page(listOf(if (it == target(1)) post(1).copy(publishedAt = null) else post(2)))
        })
        reader.loadNext {}
        assertEquals(listOf("2", "1"), reader.state.page.posts.map { it.id })
        assertNull(reader.state.page.posts.last().publishedAt)
    }
}
