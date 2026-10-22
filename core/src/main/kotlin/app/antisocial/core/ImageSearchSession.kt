package app.antisocial.core

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

enum class ImageSearchStage { IDLE, SEARCHING, READY, EXHAUSTED, FAILED }

data class ImageSearchEngineState(
    val engine: ImageSearchEngine,
    val stage: ImageSearchStage = ImageSearchStage.IDLE,
    val discovered: Int = 0,
    val notice: String? = null,
    val error: String? = null
)

/** A real supported destination whose content could not be read, never a synthetic card. */
data class ImageSearchMatch(val target: Target, val engines: Set<ImageSearchEngine>, val error: String)

data class ImageSearchState(
    val page: Page = Page(emptyList()),
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val engines: List<ImageSearchEngineState> = emptyList(),
    val unresolved: List<ImageSearchMatch> = emptyList(),
    val limited: Boolean = false,
    val error: String? = null
) {
    val unavailable: Int get() = unresolved.size
}

/**
 * Public image-search pages discover links; only exact, readable social destinations
 * become native cards. Different uploaders are never treated as one person merely
 * because a search engine associated their pages with the same image.
 *
 * All I/O is injected: production discovery and reads use NetworkGateway. Provider
 * requests and two content reads progress together, so one unavailable engine does
 * not hold back useful cards from another. This session owns no long-lived scope.
 */
class ImageSearchSession(
    engines: List<ImageSearchEngine>,
    private val discover: suspend (ImageSearchEngine, String?) -> ImageSearchLinks,
    private val recognize: (String) -> Target?,
    private val read: suspend (Target) -> Page,
    private val blockSize: Int = 12,
    private val maxPosts: Int = 120,
    private val maxAccounts: Int = 60,
    private val maxTargets: Int = 240,
    private val maxPagesPerEngine: Int = 6,
    private val maxCharacters: Int = 1024 * 1024,
    private val requestTimeoutMs: Long = 20_000
) {
    private class EngineProgress(val engine: ImageSearchEngine) {
        var cursor: String? = null
        var pages = 0
        var ended = false
        var failed = false
        val cursors = mutableSetOf<String>()
        val discovered = mutableSetOf<String>()
        var status = ImageSearchEngineState(engine)
    }

    private class Candidate(val target: Target, val engines: MutableSet<ImageSearchEngine>)
    private sealed interface Event {
        data class Discovery(val source: EngineProgress, val result: Result<ImageSearchLinks>) : Event
        data class Content(val key: String, val result: Result<Page>) : Event
    }

    private val sources = engines.distinct().take(8).map(::EngineProgress)
    private val mutex = Mutex()
    private val targets = linkedMapOf<String, Candidate>()
    private val pending = ArrayDeque<String>()
    private val unresolved = linkedMapOf<String, ImageSearchMatch>()
    private val postKeys = mutableSetOf<String>()
    private val accountKeys = mutableSetOf<String>()
    private var characters = 0L
    private var targetLimit = false
    private var contentLimit = false

    var state = ImageSearchState(engines = sources.map { it.status }, hasMore = sources.isNotEmpty())
        private set

    init {
        require(blockSize in 1..60 && maxPosts > 0 && maxAccounts > 0 && maxTargets > 0)
        require(maxPagesPerEngine > 0 && maxCharacters > 0 && requestTimeoutMs > 0)
    }

    private fun identityKey(target: Target): String {
        val canonical = runCatching { recognize(target.canonicalUrl) }.getOrNull()
            ?.takeIf { it.platform == target.platform && it.kind == target.kind } ?: target
        val identity = ContentIdentity.key(canonical.platform, canonical.canonicalUrl)
        val stable = if (canonical.kind == TargetKind.PROFILE && canonical.platform in caseInsensitiveProfiles)
            identity.lowercase(Locale.ROOT) else identity
        // ContentIdentity's numeric post IDs are deliberately local to a platform.
        return "${canonical.platform.id}:${canonical.kind}:$stable"
    }

    private fun more() = !contentLimit && (pending.isNotEmpty() ||
        (!targetLimit && sources.any { !it.ended && !it.failed }))

    private fun snapshot(loading: Boolean = state.loading) {
        val allFailed = sources.isNotEmpty() && sources.all { it.failed }
        state = state.copy(
            loading = loading,
            hasMore = more(),
            engines = sources.map { it.status },
            unresolved = unresolved.values.toList(),
            error = if (allFailed) "Public image search is unavailable on this connection. Retry an engine." else null
        )
    }

    private fun destination(url: String): Target? {
        if (url.length > 8192) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host == null || uri.userInfo != null) return null
        val target = runCatching { recognize(url) }.getOrNull() ?: return null
        if (target.canonicalUrl.length > 8192 || target.identity.length > 2048) return null
        if (target.kind != TargetKind.POST && target.kind != TargetKind.PROFILE) return null
        if (target.kind == TargetKind.PROFILE && !PublicSearchAdapter.profileAllowed(target)) return null
        return target
    }

    private fun discovered(source: EngineProgress, incoming: ImageSearchLinks) {
        source.pages++
        incoming.urls.asSequence().take(120).forEach { url ->
            val target = destination(url) ?: return@forEach
            val key = identityKey(target)
            source.discovered.add(key)
            val existing = targets[key]
            if (existing != null) {
                existing.engines.add(source.engine)
                unresolved[key]?.let { unresolved[key] = it.copy(engines = existing.engines.toSet()) }
            } else if (targets.size < maxTargets && !contentLimit) {
                targets[key] = Candidate(target, mutableSetOf(source.engine))
                pending.addLast(key)
            } else {
                targetLimit = true
                state = state.copy(limited = true)
            }
        }
        if (incoming.urls.size > 120) state = state.copy(limited = true)
        val cursor = incoming.next?.takeIf { it.isNotBlank() && it.length <= 24 * 1024 }
        val repeated = cursor != null && !source.cursors.add(cursor)
        val pageLimit = source.pages >= maxPagesPerEngine && cursor != null
        source.ended = cursor == null || repeated || pageLimit
        if (pageLimit || incoming.next != null && cursor == null) state = state.copy(limited = true)
        source.cursor = if (source.ended) null else cursor
        source.failed = false
        source.status = ImageSearchEngineState(
            source.engine,
            if (source.ended) ImageSearchStage.EXHAUSTED else ImageSearchStage.READY,
            source.discovered.size,
            incoming.notice?.take(1000)
        )
    }

    private fun unreadable(key: String, message: String) {
        val candidate = targets.getValue(key)
        unresolved[key] = ImageSearchMatch(candidate.target, candidate.engines.toSet(), message.take(500))
    }

    private fun hydrated(key: String, result: Result<Page>) {
        val target = targets.getValue(key).target
        val page = result.getOrNull()
        if (page == null) {
            unreadable(key, result.exceptionOrNull()?.message ?: "This public match could not be read.")
            return
        }
        if (target.kind == TargetKind.POST) {
            val post = page.posts.asSequence().take(512).firstOrNull {
                it.platform == target.platform && !it.preview &&
                    (it.body.isNotBlank() || it.title.isNotBlank() || it.media.isNotEmpty()) &&
                    identityKey(Target(it.platform, TargetKind.POST, "", it.canonicalUrl)) == key
            }
            if (post == null) {
                unreadable(key, "This public match did not supply its exact readable post.")
            } else if (key !in postKeys && !contentLimit) {
                val size = postCharacters(post)
                if (postKeys.size >= maxPosts || characters + size > maxCharacters) {
                    contentLimit = true
                    state = state.copy(limited = true)
                } else {
                    postKeys.add(key)
                    characters += size
                    unresolved.remove(key)
                    state = state.copy(page = state.page.copy(posts = (state.page.posts + post).sortedWith(
                        compareByDescending<Post> { it.publishedAt ?: Long.MIN_VALUE }.thenBy { it.platform.id }.thenBy { it.id }
                    )))
                }
            }
        } else {
            val profile = page.profile?.takeIf {
                it.target.platform == target.platform && it.target.kind == TargetKind.PROFILE &&
                    it.name.isNotBlank() && PublicSearchAdapter.profileAllowed(it.target) && identityKey(it.target) == key
            }
            if (profile == null) {
                unreadable(key, "This public match did not supply its exact readable account.")
            } else if (key !in accountKeys && !contentLimit) {
                val size = profile.name.length.toLong() + profile.description.length + profile.target.canonicalUrl.length +
                    profile.avatar.orEmpty().length + profile.links.orEmpty().sumOf { it.length.toLong() } + 512
                if (accountKeys.size >= maxAccounts || characters + size > maxCharacters) {
                    contentLimit = true
                    state = state.copy(limited = true)
                } else {
                    accountKeys.add(key)
                    characters += size
                    unresolved.remove(key)
                    state = state.copy(page = state.page.copy(accounts = state.page.accounts + profile))
                }
            }
        }
    }

    suspend fun loadNext(publish: suspend (ImageSearchState) -> Unit) = load(publish)

    /** Explicit retries keep a refusing engine from being hammered by feed scrolling. */
    suspend fun retryEngine(engine: ImageSearchEngine, publish: suspend (ImageSearchState) -> Unit) =
        load(publish, engineToRetry = engine)

    suspend fun retryMatch(target: Target, publish: suspend (ImageSearchState) -> Unit) =
        load(publish, matchToRetry = target)

    private suspend fun load(
        publish: suspend (ImageSearchState) -> Unit,
        engineToRetry: ImageSearchEngine? = null,
        matchToRetry: Target? = null
    ) {
        if (!mutex.tryLock()) return
        try {
            if (engineToRetry != null) sources.firstOrNull { it.engine == engineToRetry && it.failed }?.let {
                it.failed = false
                it.status = it.status.copy(stage = if (it.pages == 0) ImageSearchStage.IDLE else ImageSearchStage.READY, error = null)
            }
            if (matchToRetry != null) {
                val key = identityKey(matchToRetry)
                if (unresolved.remove(key) != null && key !in pending) pending.addFirst(key)
            }
            if (!more()) { snapshot(false); publish(state); return }
            snapshot(true)
            publish(state)
            coroutineScope {
                val events = Channel<Event>(Channel.UNLIMITED)
                // The standard three engines start independently; the separate
                // two-reader limit below bounds expensive social hydration.
                val providerPermits = Semaphore(3)
                val selected = if (matchToRetry != null) emptyList() else sources.filter {
                    !targetLimit && !it.ended && !it.failed && (engineToRetry == null || it.engine == engineToRetry) &&
                        (engineToRetry != null || pending.isEmpty() || it.pages == 0)
                }
                var providers = selected.size
                var readers = 0
                var readsStarted = 0
                val inFlight = mutableSetOf<String>()
                val jobs = mutableListOf<kotlinx.coroutines.Job>()
                selected.forEach { source ->
                    source.status = source.status.copy(stage = ImageSearchStage.SEARCHING, error = null)
                    jobs += launch {
                        val result = try {
                            Result.success(providerPermits.withPermit { withTimeout(requestTimeoutMs) { discover(source.engine, source.cursor) } })
                        } catch (e: CancellationException) {
                            if (e is kotlinx.coroutines.TimeoutCancellationException) Result.failure(e)
                            else { events.close(e); throw e }
                        } catch (e: Exception) { Result.failure(e) }
                        events.send(Event.Discovery(source, result))
                    }
                }
                suspend fun dispatchReads() {
                    while (!contentLimit && readers < 2 && readsStarted < blockSize) {
                        val key = pending.firstOrNull { it !in inFlight } ?: break
                        val target = targets.getValue(key).target
                        inFlight.add(key)
                        readers++
                        readsStarted++
                        jobs += launch {
                            val result = try { Result.success(withTimeout(requestTimeoutMs) { read(target) }) }
                            catch (e: CancellationException) {
                                if (e is kotlinx.coroutines.TimeoutCancellationException) Result.failure(e)
                                else { events.close(e); throw e }
                            } catch (e: Exception) { Result.failure(e) }
                            events.send(Event.Content(key, result))
                        }
                    }
                }
                try {
                    snapshot(true); publish(state)
                    dispatchReads()
                    while (providers > 0 || readers > 0) {
                        when (val event = events.receive()) {
                            is Event.Discovery -> {
                                providers--
                                val incoming = event.result.getOrNull()
                                if (incoming != null) discovered(event.source, incoming)
                                else {
                                    event.source.failed = true
                                    event.source.status = event.source.status.copy(
                                        stage = ImageSearchStage.FAILED,
                                        error = event.result.exceptionOrNull()?.message?.take(500) ?: "This search engine is unavailable."
                                    )
                                }
                            }
                            is Event.Content -> {
                                readers--
                                inFlight.remove(event.key)
                                pending.remove(event.key)
                                hydrated(event.key, event.result)
                            }
                        }
                        snapshot(true); publish(state)
                        dispatchReads()
                    }
                } finally {
                    jobs.forEach { it.cancel() }
                    events.cancel()
                }
            }
            snapshot(false)
            publish(state)
        } catch (e: CancellationException) {
            throw e
        } finally {
            // Unfinished reads remain pending and canceled discoveries retain their
            // previous cursor. A later load resumes rather than losing those links.
            sources.filter { it.status.stage == ImageSearchStage.SEARCHING }.forEach {
                it.status = it.status.copy(stage = if (it.pages == 0) ImageSearchStage.IDLE else ImageSearchStage.READY)
            }
            snapshot(false)
            mutex.unlock()
        }
    }

    private fun postCharacters(post: Post): Long = post.body.length.toLong() + post.title.length + post.author.length +
        post.id.length + post.canonicalUrl.length + post.avatar.orEmpty().length + 512 +
        post.media.sumOf { it.url.length.toLong() + it.preview.orEmpty().length + it.audioUrl.orEmpty().length + it.audioMimeType.orEmpty().length + it.description.length + it.mimeType.orEmpty().length + 64 } +
        post.links.orEmpty().sumOf { it.length.toLong() }

    private companion object {
        val caseInsensitiveProfiles = setOf(Platform.X, Platform.BLUESKY, Platform.INSTAGRAM, Platform.REDDIT,
            Platform.TIKTOK, Platform.THREADS, Platform.FACEBOOK, Platform.PINTEREST, Platform.TELEGRAM)
    }
}
