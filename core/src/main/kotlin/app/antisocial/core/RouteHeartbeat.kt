package app.antisocial.core

import java.net.IDN
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** An accountless routing scope. Federated servers learn independently. */
data class HeartbeatScope(val platform: Platform, val capability: Capability, val tor: Boolean, val origin: String = "")
enum class HeartbeatOutcome { HEALTHY, FAILED, INCONCLUSIVE }
data class HeartbeatState(
    val scope: HeartbeatScope,
    val planHash: String,
    val winnerHash: String = "",
    val stablePulses: Int = 0,
    val volatility: Int = 0,
    val failures: Int = 0,
    val lastReadAt: Long = 0,
    val lastObservedAt: Long = 0,
    val lastStableAt: Long = 0,
    val lastPulseAt: Long = 0,
    val nextDueAt: Long = 0
)
data class HeartbeatPathState(val key: String, val host: String, val failures: Int = 0, val retryAt: Long = 0, val lastObservedAt: Long = 0)
data class HeartbeatAttempt(val host: String, val at: Long)
data class HeartbeatSnapshot(
    val version: Int = 1,
    val scopes: List<HeartbeatState> = emptyList(),
    val paths: List<HeartbeatPathState> = emptyList(),
    val attempts: List<HeartbeatAttempt> = emptyList()
)
data class HeartbeatReservation(val id: Long, val scope: HeartbeatScope, val pathKey: String, val host: String, val startedAt: Long)

/**
 * Local scheduling policy only: it never resolves a host or opens a connection.
 * Real requested content establishes the winner; a canary only establishes health.
 * No post URL, account name, search, response body or latency is retained here.
 *
 * Stable independent observations progressively quiet a scope from thirty minutes
 * to a day. Route changes make it watchful again, while repeatedly failed paths
 * back off independently. Fresh real reads replace maintenance, not supplement it.
 */
class RouteHeartbeat(private val clock: () -> Long = System::currentTimeMillis) {
    private val scopes = linkedMapOf<String, HeartbeatState>()
    private val paths = linkedMapOf<String, HeartbeatPathState>()
    private val attempts = ArrayDeque<HeartbeatAttempt>()
    private var reservation: HeartbeatReservation? = null
    private var nextId = 0L
    private var readRevision = 0L
    private var reservationReadRevision = 0L
    private fun now() = clock().coerceAtLeast(0)
    private fun key(scope: HeartbeatScope) = hash("${scope.platform.id}|${scope.capability}|${scope.tor}|${scope.origin}")
    private fun pathKey(scope: HeartbeatScope, source: Source) = hash("${key(scope)}|${source.key}")

    @Synchronized fun state(scope: HeartbeatScope): HeartbeatState? {
        trim()
        val clean = cleanScope(scope) ?: return null
        return scopes[key(clean)]
    }

    /** Null means this scope has no recent demand. A heartbeat never activates a site. */
    @Synchronized fun dueAt(scope: HeartbeatScope): Long? {
        val state = state(scope) ?: return null
        return if (recentDemand(state, now())) maxOf(state.nextDueAt, globalAllowedAt(now())) else null
    }

    /** Includes cross-site budgets, shared hosts, failed paths and the one in-flight lease. */
    @Synchronized fun nextAllowedAt(scope: HeartbeatScope, sources: List<Source>): Long? {
        val state = state(scope) ?: return null
        val now = now()
        if (!recentDemand(state, now)) return null
        return sources.asSequence().filter { it.platform == state.scope.platform }.mapNotNull { source ->
            val host = sourceHost(source) ?: return@mapNotNull null
            maxOf(now, state.nextDueAt, globalAllowedAt(now), hostAllowedAt(host, now), paths[pathKey(state.scope, source)]?.retryAt ?: 0)
        }.minOrNull()
    }

    /** Only meaningful route-plan changes belong here, never target-specific missing posts. */
    @Synchronized fun observePlan(scope: HeartbeatScope, planKey: String) {
        val old = state(scope) ?: return
        val plan = hash(planKey)
        if (old.planHash == plan) return
        val now = now()
        val changed = old.copy(planHash = plan, stablePulses = 0, volatility = (old.volatility + 3).coerceAtMost(8), lastObservedAt = now)
        put(changed.copy(nextDueAt = minOf(old.nextDueAt, add(now, interval(changed)))))
    }

    /**
     * A delivered winner is evidence from a real read, not the fastest probe.
     * Dense repeated reads postpone probes but count as at most one independent
     * stable observation per thirty minutes.
     */
    @Synchronized fun observeRead(scope: HeartbeatScope, winnerKey: String?, planKey: String, successful: Boolean = true) {
        trim()
        val clean = cleanScope(scope) ?: return
        readRevision++
        val now = now()
        val plan = hash(planKey)
        val winner = winnerKey?.takeIf { it.isNotBlank() }?.let(::hash).orEmpty()
        val old = scopes[key(clean)]
        if (successful && !winnerKey.isNullOrBlank()) {
            val deliveredPath = hash("${key(clean)}|$winnerKey")
            paths[deliveredPath]?.let { previous ->
                // A delivered read is stronger evidence than this path's old failed canary.
                paths[deliveredPath] = previous.copy(failures = 0, retryAt = add(now, HOST_GAP), lastObservedAt = now)
            }
        }
        val changed = old != null && (old.planHash != plan || winner.isNotEmpty() && old.winnerHash.isNotEmpty() && winner != old.winnerHash)
        val independent = old != null && successful && !changed && now >= add(old.lastStableAt, BASE)
        val next = HeartbeatState(
            clean, plan, if (successful && winner.isNotEmpty()) winner else old?.winnerHash.orEmpty(),
            if (changed) 0 else ((old?.stablePulses ?: 0) + if (independent) 1 else 0).coerceAtMost(20),
            if (changed) ((old?.volatility ?: 0) + 3).coerceAtMost(8) else ((old?.volatility ?: 0) - if (independent) 1 else 0).coerceAtLeast(0),
            if (successful) 0 else ((old?.failures ?: 0) + 1).coerceAtMost(16),
            now, now, if (old == null || changed || independent) now else old.lastStableAt,
            old?.lastPulseAt ?: 0, 0
        )
        put(next.copy(nextDueAt = add(now, maxOf(PASSIVE_GRACE, interval(next)))))
    }

    /** Claims immediately count against request budgets, including cancelled work. */
    @Synchronized fun reserve(scope: HeartbeatScope, source: Source, planKey: String, foreground: Boolean = false): HeartbeatReservation? {
        trim()
        if (foreground) return null
        val clean = cleanScope(scope) ?: return null
        if (source.platform != clean.platform) return null
        val host = sourceHost(source) ?: return null
        observePlan(clean, planKey)
        val allowed = nextAllowedAt(clean, listOf(source)) ?: return null
        val now = now()
        if (allowed > now || reservation != null) return null
        val claim = HeartbeatReservation(++nextId, clean, pathKey(clean, source), host, now)
        reservation = claim
        reservationReadRevision = readRevision
        attempts.addLast(HeartbeatAttempt(host, now))
        return claim
    }

    /** Cancellation is not a failed route and must not alter its success ranking. */
    @Synchronized fun cancel(claim: HeartbeatReservation): Boolean {
        if (reservation != claim) return false
        reservation = null
        scopes[key(claim.scope)]?.let { put(it.copy(nextDueAt = maxOf(it.nextDueAt, add(now(), CANCEL_GRACE)))) }
        return true
    }

    @Synchronized fun complete(claim: HeartbeatReservation, outcome: HeartbeatOutcome, planKey: String): Boolean {
        trim()
        if (reservation != claim) return false
        if (reservationReadRevision != readRevision) {
            cancel(claim)
            return false // A real read supersedes the older maintenance schedule.
        }
        reservation = null
        val old = scopes[key(claim.scope)] ?: return false
        val now = now()
        val plan = hash(planKey)
        val changed = old.planHash != plan
        val healthy = outcome == HeartbeatOutcome.HEALTHY
        val previousPath = paths[claim.pathKey]
        val pathFailures = if (healthy) 0 else ((previousPath?.failures ?: 0) + 1).coerceAtMost(16)
        paths.remove(claim.pathKey)
        paths[claim.pathKey] = HeartbeatPathState(claim.pathKey, claim.host, pathFailures,
            add(now, if (healthy) HOST_GAP else failureDelay(pathFailures)), now)
        val next = old.copy(
            planHash = plan,
            stablePulses = if (changed) 0 else (old.stablePulses + if (healthy) 1 else 0).coerceAtMost(20),
            volatility = if (changed) (old.volatility + 3).coerceAtMost(8) else (old.volatility - if (healthy) 1 else 0).coerceAtLeast(0),
            failures = if (healthy) 0 else if (outcome == HeartbeatOutcome.FAILED) (old.failures + 1).coerceAtMost(16) else old.failures,
            lastObservedAt = now, lastStableAt = if (healthy || changed) now else old.lastStableAt, lastPulseAt = now
        )
        // Size-limited/inconclusive soundings justify no outage claim and no immediate retry.
        put(next.copy(nextDueAt = add(now, maxOf(interval(next), if (outcome == HeartbeatOutcome.INCONCLUSIVE) HOUR else 0))))
        trim()
        return true
    }

    @Synchronized fun snapshot(): HeartbeatSnapshot {
        trim()
        return HeartbeatSnapshot(scopes = scopes.values.toList(), paths = paths.values.toList(), attempts = attempts.toList())
    }

    /** Bounded import. Timestamps, hashes, origins and counters are treated as untrusted. */
    @Synchronized fun restore(value: HeartbeatSnapshot) {
        clear()
        if (value.version != VERSION) return
        val now = now()
        value.scopes.orEmpty().takeLast(MAX_STATES).forEach { raw -> runCatching {
            val scope = cleanScope(raw.scope) ?: return@runCatching
            if (!digest(raw.planHash) || raw.winnerHash.isNotEmpty() && !digest(raw.winnerHash) || !recent(raw.lastObservedAt, now)) return@runCatching
            if (raw.lastReadAt !in 0..add(now, CLOCK_TOLERANCE) || raw.lastStableAt !in 0..add(now, CLOCK_TOLERANCE) || raw.lastPulseAt !in 0..add(now, CLOCK_TOLERANCE)) return@runCatching
            put(raw.copy(scope = scope, stablePulses = raw.stablePulses.coerceIn(0, 20), volatility = raw.volatility.coerceIn(0, 8),
                failures = raw.failures.coerceIn(0, 16), nextDueAt = raw.nextDueAt.coerceIn(now, add(now, MAX_DELAY))))
        } }
        value.paths.orEmpty().takeLast(MAX_STATES).forEach { raw -> runCatching {
            val host = cleanHost(raw.host) ?: return@runCatching
            if (!digest(raw.key) || host != raw.host || !recent(raw.lastObservedAt, now)) return@runCatching
            paths[raw.key] = raw.copy(failures = raw.failures.coerceIn(0, 16), retryAt = raw.retryAt.coerceIn(now, add(now, MAX_DELAY)))
        } }
        value.attempts.orEmpty().takeLast(128).mapNotNull { raw -> runCatching {
            val host = cleanHost(raw.host) ?: return@runCatching null
            if (host != raw.host || raw.at !in (now - HOUR).coerceAtLeast(0)..now) null else HeartbeatAttempt(host, raw.at)
        }.getOrNull() }.sortedBy { it.at }.takeLast(12).forEach(attempts::addLast)
        trim()
    }

    @Synchronized fun clear() {
        scopes.clear(); paths.clear(); attempts.clear(); reservation = null
        readRevision++
        // IDs must not be reused while a cancelled old coroutine can still finish.
    }

    private fun put(state: HeartbeatState) {
        val key = key(state.scope)
        scopes.remove(key); scopes[key] = state
        while (scopes.size > MAX_STATES) scopes.remove(scopes.keys.first())
    }
    private fun interval(state: HeartbeatState): Long {
        val quiet = (BASE * (1L shl (state.stablePulses / 2).coerceAtMost(6))).coerceAtMost(MAX_DELAY)
        val responsive = (quiet / (1 + state.volatility)).coerceAtLeast(MIN_DELAY)
        val backoff = if (state.failures > 0) failureDelay(state.failures) else 0
        val nominal = maxOf(responsive, backoff)
        // Stagger local site schedules without identifying device or account data.
        val jitter = (key(state.scope).take(8).toLong(16) % 201 - 100).toInt()
        return (nominal + nominal * jitter / 1000).coerceIn(MIN_DELAY, MAX_DELAY)
    }
    private fun failureDelay(failures: Int) = (HOST_GAP * (1L shl (failures - 1).coerceIn(0, 7))).coerceAtMost(MAX_DELAY)
    private fun globalAllowedAt(now: Long): Long {
        val short = attempts.filter { it.at > now - SHORT_WINDOW }
        val hourly = attempts.filter { it.at > now - HOUR }
        return maxOf(now,
            if (short.size >= 2) add(short[short.size - 2].at, SHORT_WINDOW) else now,
            if (hourly.size >= 12) add(hourly[hourly.size - 12].at, HOUR) else now,
            reservation?.let { add(it.startedAt, LEASE) } ?: now)
    }
    private fun hostAllowedAt(host: String, now: Long) = attempts.lastOrNull { it.host == host }?.let { maxOf(now, add(it.at, HOST_GAP)) } ?: now
    private fun recentDemand(state: HeartbeatState, now: Long) = state.lastReadAt <= add(now, CLOCK_TOLERANCE) && now - state.lastReadAt <= DEMAND_TTL
    private fun recent(at: Long, now: Long) = at in (now - STATE_TTL).coerceAtLeast(0)..add(now, CLOCK_TOLERANCE)
    private fun trim() {
        val now = now()
        scopes.entries.removeAll { !recent(it.value.lastObservedAt, now) }
        paths.entries.removeAll { !recent(it.value.lastObservedAt, now) }
        while (paths.size > MAX_STATES) paths.remove(paths.keys.first())
        while (attempts.firstOrNull()?.let { it.at <= now - HOUR } == true) attempts.removeFirst()
        reservation?.takeIf { now >= add(it.startedAt, LEASE) }?.let { cancel(it) }
    }

    companion object {
        const val VERSION = 1
        const val MIN_DELAY = 5 * 60_000L
        const val BASE = 30 * 60_000L
        const val MAX_DELAY = 24 * 60 * 60_000L
        private const val PASSIVE_GRACE = 15 * 60_000L
        private const val CANCEL_GRACE = 2 * 60_000L
        private const val HOST_GAP = 15 * 60_000L
        private const val SHORT_WINDOW = 5 * 60_000L
        private const val HOUR = 60 * 60_000L
        private const val LEASE = 2 * 60_000L
        private const val STATE_TTL = 30 * MAX_DELAY
        private const val DEMAND_TTL = 7 * MAX_DELAY
        private const val CLOCK_TOLERANCE = 60_000L
        private const val MAX_STATES = 512
        private val federated = setOf(Platform.MASTODON, Platform.LEMMY, Platform.PIXELFED, Platform.MISSKEY)
        private val digestPattern = Regex("[0-9a-f]{64}")
        private fun digest(value: String) = digestPattern.matches(value)
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun add(at: Long, delay: Long) = if (at > Long.MAX_VALUE - delay) Long.MAX_VALUE else at + delay
        private fun cleanHost(value: String): String? = runCatching {
            if (value.isBlank() || value.length > 253 || value.any { it.isWhitespace() || it in "/?#@" }) return@runCatching null
            val host = value.lowercase(Locale.ROOT).removeSuffix(".").removePrefix("[").removeSuffix("]")
            if (':' in host) {
                if (!host.all { it in "0123456789abcdef:" }) null else host
            } else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).takeIf { it.isNotBlank() && it.length <= 253 }
        }.getOrNull()
        private fun origin(value: String): String? = runCatching {
            val uri = URI(value)
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in listOf(null, "", "/") || uri.port !in -1..65535) return@runCatching null
            val host = cleanHost(uri.host ?: return@runCatching null) ?: return@runCatching null
            val scheme = uri.scheme.lowercase(Locale.ROOT)
            val port = uri.port.takeUnless { it == -1 || it == if (scheme == "https") 443 else 80 }
            "$scheme://${if (':' in host) "[$host]" else host}${port?.let { ":$it" }.orEmpty()}"
        }.getOrNull()
        private fun cleanScope(scope: HeartbeatScope): HeartbeatScope? = runCatching {
            // Force enum access so malformed deserialized nulls are rejected.
            scope.platform.id; scope.capability.name
            if (scope.origin.isEmpty()) scope else if (scope.platform !in federated) scope.copy(origin = "") else scope.copy(origin = origin(scope.origin) ?: return@runCatching null)
        }.getOrNull()
        private fun sourceHost(source: Source): String? = runCatching {
            val uri = URI(source.root)
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.rawUserInfo != null) null else cleanHost(uri.host ?: return@runCatching null)
        }.getOrNull()
        fun scope(target: Target, capability: Capability, tor: Boolean): HeartbeatScope {
            val origin = if (target.platform in federated) runCatching {
                val uri = URI(target.canonicalUrl)
                origin(URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString()).orEmpty()
            }.getOrDefault("") else ""
            return HeartbeatScope(target.platform, capability, tor, origin)
        }
    }
}
