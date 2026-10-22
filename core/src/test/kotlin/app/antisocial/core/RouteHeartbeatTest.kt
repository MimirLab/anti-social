package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class RouteHeartbeatTest {
    private var now = 100 * RouteHeartbeat.MAX_DELAY
    private val source = Source(Platform.X, "nitter", "https://reader.example")
    private val scope = HeartbeatScope(Platform.X, Capability.TIMELINE, false)
    private fun policy() = RouteHeartbeat { now }
    private fun activate(policy: RouteHeartbeat, scope: HeartbeatScope = this.scope, winner: String = source.key, plan: String = "alternate then original") = policy.observeRead(scope, winner, plan)
    private fun pulse(policy: RouteHeartbeat, outcome: HeartbeatOutcome = HeartbeatOutcome.HEALTHY, plan: String = "alternate then original", selected: Source = source): HeartbeatState {
        now = policy.nextAllowedAt(scope, listOf(selected))!!
        val reservation = policy.reserve(scope, selected, plan)!!
        assertTrue(policy.complete(reservation, outcome, plan))
        return policy.state(scope)!!
    }

    @Test fun initialDemandAndClockZeroAreSafeAndNoColdSiteIsActivated() {
        now = 0
        val policy = policy()
        assertNull(policy.dueAt(scope))
        assertNull(policy.reserve(scope, source, "one"))
        assertTrue(policy.snapshot().scopes.isEmpty())
        activate(policy)
        assertTrue(policy.dueAt(scope)!! in 27 * 60_000L..33 * 60_000L)
        assertNull(policy.reserve(scope, source, "alternate then original"))
        assertNull(policy.nextAllowedAt(scope, emptyList()))
        pulse(policy)
        assertEquals(1, policy.state(scope)?.stablePulses)
    }

    @Test fun stablePulsePlansExponentiallyQuietToOneDay() {
        val policy = policy(); activate(policy)
        val delays = mutableListOf<Long>()
        repeat(14) {
            val state = pulse(policy)
            delays += state.nextDueAt - now
        }
        assertTrue(delays[1] >= delays[0] * 19 / 10)
        assertTrue(delays[3] >= delays[1] * 19 / 10)
        assertTrue(delays.last() in 21 * 60 * 60_000L..RouteHeartbeat.MAX_DELAY)
        assertTrue(delays.all { it <= RouteHeartbeat.MAX_DELAY })
        assertEquals(14, policy.state(scope)?.stablePulses)
    }

    @Test fun densePassiveReadsPostponeChecksWithoutManufacturingIndependentEvidence() {
        val policy = policy(); activate(policy)
        val original = policy.dueAt(scope)!!
        repeat(100) { now += 1_000; activate(policy) }
        assertEquals(0, policy.state(scope)?.stablePulses)
        assertTrue(policy.dueAt(scope)!! > original)
        now += RouteHeartbeat.BASE
        activate(policy)
        assertEquals(1, policy.state(scope)?.stablePulses)
        activate(policy)
        assertEquals(1, policy.state(scope)?.stablePulses)
    }

    @Test fun passiveEvidenceCanQuietTheScopeWithoutAnyExtraPulse() {
        val policy = policy(); activate(policy)
        repeat(12) { now += RouteHeartbeat.BASE; activate(policy) }
        assertEquals(12, policy.state(scope)?.stablePulses)
        assertEquals(0L, policy.state(scope)?.lastPulseAt)
        assertTrue(policy.dueAt(scope)!! - now > 20 * 60 * 60_000L)
        assertTrue(policy.snapshot().attempts.isEmpty())
    }

    @Test fun realWinnerAndMeaningfulPlanChangesIncreaseWatchfulnessIndependentlyOfProbeLatency() {
        val policy = policy(); activate(policy)
        repeat(10) { pulse(policy) }
        val quietDelay = policy.dueAt(scope)!! - now
        now += 10_000
        activate(policy, winner = "another actual delivering path")
        val changed = policy.state(scope)!!
        assertEquals(0, changed.stablePulses)
        assertEquals(3, changed.volatility)
        assertTrue(changed.nextDueAt - now < quietDelay / 10)
        val winnerHash = changed.winnerHash
        pulse(policy, plan = "alternate then original")
        assertEquals(winnerHash, policy.state(scope)?.winnerHash)
        policy.observePlan(scope, "different top routes")
        assertTrue(policy.state(scope)!!.volatility > 2)
        assertEquals(0, policy.state(scope)?.stablePulses)
    }

    @Test fun unchangedPlansDoNotResetStableHistoryOrArtificiallyBecomeVolatile() {
        val policy = policy(); activate(policy)
        repeat(4) { pulse(policy) }
        val before = policy.state(scope)
        policy.observePlan(scope, "alternate then original")
        assertEquals(before, policy.state(scope))
        assertEquals(0, before?.volatility)
    }

    @Test fun repeatedlyDeadPathsBackOffEvenWhenOtherReadsRemainHealthy() {
        val policy = policy(); activate(policy)
        val delays = mutableListOf<Long>()
        repeat(8) {
            pulse(policy, HeartbeatOutcome.FAILED)
            val path = policy.snapshot().paths.single()
            delays += path.retryAt - now
            now += 1_000
            activate(policy, winner = "different working route") // Another winner must not forgive a failing backup's retry budget.
        }
        assertEquals(15 * 60_000L, delays.first())
        assertEquals(30 * 60_000L, delays[1])
        assertEquals(RouteHeartbeat.MAX_DELAY, delays.last())
        assertEquals(0, policy.state(scope)?.failures)
        assertEquals(8, policy.snapshot().paths.single().failures)
    }

    @Test fun successfulRequestedDeliveryRecoversThatExactPathsFailedCanaryBackoff() {
        val policy = policy(); activate(policy)
        repeat(5) { pulse(policy, HeartbeatOutcome.FAILED) }
        assertEquals(5, policy.snapshot().paths.single().failures)
        now += 1_000
        activate(policy)
        val recovered = policy.snapshot().paths.single()
        assertEquals(0, recovered.failures)
        assertEquals(now + 15 * 60_000L, recovered.retryAt)
        assertTrue(policy.nextAllowedAt(scope, listOf(source))!! - now < 35 * 60_000L)
    }

    @Test fun entireSiteFailureAlsoBacksOffAndSuccessfulDeliveryRecoversScope() {
        val policy = policy(); activate(policy)
        repeat(6) { now += 1_000; policy.observeRead(scope, null, "alternate then original", successful = false) }
        assertEquals(6, policy.state(scope)?.failures)
        assertTrue(policy.dueAt(scope)!! - now > 7 * 60 * 60_000L)
        activate(policy)
        assertEquals(0, policy.state(scope)?.failures)
        assertTrue(policy.dueAt(scope)!! - now < 35 * 60_000L)
    }

    @Test fun inconclusivePulsesAreNotOutagesAndDoNotRapidlyRepeat() {
        val policy = policy(); activate(policy)
        val before = policy.state(scope)!!.winnerHash
        pulse(policy, HeartbeatOutcome.INCONCLUSIVE)
        val state = policy.state(scope)!!
        assertEquals(0, state.failures)
        assertEquals(0, state.stablePulses)
        assertEquals(before, state.winnerHash)
        assertTrue(state.nextDueAt - now >= 60 * 60_000L)
    }

    @Test fun capabilityTorAndFederatedOriginAreIsolated() {
        val policy = policy(); activate(policy)
        val comments = scope.copy(capability = Capability.COMMENTS)
        val tor = scope.copy(tor = true)
        assertNull(policy.dueAt(comments)); assertNull(policy.dueAt(tor))
        activate(policy, comments); activate(policy, tor)
        val a = Target(Platform.MASTODON, TargetKind.PROFILE, "one", "https://ONE.example:443/@private-account?q=secret")
        val b = a.copy(canonicalUrl = "https://two.example/@other")
        val scopeA = RouteHeartbeat.scope(a, Capability.TIMELINE, false)
        val scopeB = RouteHeartbeat.scope(b, Capability.TIMELINE, false)
        assertEquals("https://one.example", scopeA.origin)
        activate(policy, scopeA)
        assertNull(policy.dueAt(scopeB))
        assertEquals(4, policy.snapshot().scopes.size)
        assertFalse(Gson().toJson(policy.snapshot()).contains("private-account"))
        assertFalse(Gson().toJson(policy.snapshot()).contains("secret"))
    }

    @Test fun oneGlobalLeaseAndForegroundPreemptionPreventConcurrentMaintenance() {
        val policy = policy(); activate(policy)
        now = policy.dueAt(scope)!!
        assertNull(policy.reserve(scope, source, "alternate then original", foreground = true))
        assertTrue(policy.snapshot().attempts.isEmpty())
        val claim = policy.reserve(scope, source, "alternate then original")!!
        val other = source.copy(root = "https://other.example")
        assertNull(policy.reserve(scope, other, "alternate then original"))
        assertTrue(policy.nextAllowedAt(scope, listOf(other))!! > now)
        assertTrue(policy.cancel(claim))
        assertFalse(policy.complete(claim, HeartbeatOutcome.FAILED, "different"))
        assertEquals(0, policy.state(scope)?.failures)
        assertTrue(policy.snapshot().paths.isEmpty())
        assertTrue(policy.nextAllowedAt(scope, listOf(source))!! >= now + 15 * 60_000L)
    }

    @Test fun abandonedLeaseExpiresAndOldCompletionCannotOverrideReplacement() {
        val policy = policy(); activate(policy)
        now = policy.dueAt(scope)!!
        val old = policy.reserve(scope, source, "alternate then original")!!
        now += 10 * 60_000L
        val other = source.copy(root = "https://other.example")
        now = policy.nextAllowedAt(scope, listOf(other))!!
        val replacement = policy.reserve(scope, other, "alternate then original")!!
        assertFalse(policy.complete(old, HeartbeatOutcome.FAILED, "bad"))
        assertTrue(policy.complete(replacement, HeartbeatOutcome.HEALTHY, "alternate then original"))
        assertEquals(0, policy.state(scope)?.failures)
    }

    @Test fun realReadWhilePulseIsInFlightSupersedesOldMaintenanceScheduleEvenAtSameClockTick() {
        val policy = policy(); activate(policy)
        now = policy.dueAt(scope)!!
        val old = policy.reserve(scope, source, "alternate then original")!!
        activate(policy, winner = "new actual source")
        val fresh = policy.state(scope)!!
        assertFalse(policy.complete(old, HeartbeatOutcome.FAILED, "obsolete probe plan"))
        assertEquals(fresh, policy.state(scope))
        assertEquals(0, policy.state(scope)?.failures)
        assertTrue(policy.snapshot().paths.isEmpty())
    }

    @Test fun sharedHostsRespectTheirBudgetAcrossPlatformsCapabilitiesAndTor() {
        val policy = policy(); activate(policy)
        val otherScope = HeartbeatScope(Platform.REDDIT, Capability.POST, true)
        activate(policy, otherScope)
        now = maxOf(policy.dueAt(scope)!!, policy.dueAt(otherScope)!!)
        val claim = policy.reserve(scope, source, "alternate then original")!!
        assertTrue(policy.cancel(claim))
        val sameHost = Source(Platform.REDDIT, "redlib", "https://READER.example:8443")
        assertNull(policy.reserve(otherScope, sameHost, "alternate then original"))
        assertTrue(policy.nextAllowedAt(otherScope, listOf(sameHost))!! >= now + 15 * 60_000L)
        assertTrue(policy.nextAllowedAt(otherScope, listOf(sameHost.copy(root = "https://different.example")))!! < now + 15 * 60_000L)
    }

    @Test fun globalShortAndHourlyBudgetsAreSharedAcrossDifferentSites() {
        val policy = policy()
        val all = Platform.entries.take(14).map { HeartbeatScope(it, Capability.TIMELINE, false) }
        all.forEach { activate(policy, it) }
        now = all.maxOf { policy.dueAt(it)!! }
        val start = now
        repeat(6) { batch ->
            repeat(2) { within ->
                val index = batch * 2 + within
                val s = all[index]
                val route = source.copy(platform = s.platform, root = "https://host$index.example")
                val claim = policy.reserve(s, route, "alternate then original")!!
                assertTrue(policy.cancel(claim))
            }
            val blocked = all[12]
            assertNull(policy.reserve(blocked, source.copy(platform = blocked.platform, root = "https://unused.example"), "alternate then original"))
            assertTrue(policy.dueAt(blocked)!! > now)
            if (batch < 5) now += 5 * 60_000L
        }
        assertEquals(12, policy.snapshot().attempts.size)
        assertTrue(policy.dueAt(all[12])!! >= start + 60 * 60_000L)
        now = start + 60 * 60_000L
        val allowed = all[12]
        assertNotNull(policy.reserve(allowed, source.copy(platform = allowed.platform, root = "https://unused.example"), "alternate then original"))
    }

    @Test fun snapshotRestoreRetainsSchedulesFailedPathAndGlobalHostBudgets() {
        val policy = policy(); activate(policy)
        pulse(policy, HeartbeatOutcome.FAILED)
        val snapshot = policy.snapshot()
        val restored = policy()
        restored.restore(Gson().fromJson(Gson().toJson(snapshot), HeartbeatSnapshot::class.java))
        assertEquals(snapshot, restored.snapshot())
        assertEquals(policy.nextAllowedAt(scope, listOf(source)), restored.nextAllowedAt(scope, listOf(source)))
        assertNull(restored.reserve(scope, source, "alternate then original"))
    }

    @Test fun restoreRejectsUnknownVersionsCorruptOriginsHashesAndUnboundedTimestamps() {
        val policy = policy(); activate(policy)
        val valid = policy.snapshot().scopes.single()
        val restored = policy()
        restored.restore(HeartbeatSnapshot(version = 55, scopes = listOf(valid)))
        assertTrue(restored.snapshot().scopes.isEmpty())
        val bad = listOf(valid.copy(planHash = "account@example/secret"), valid.copy(lastObservedAt = Long.MAX_VALUE),
            valid.copy(scope = HeartbeatScope(Platform.MASTODON, Capability.TIMELINE, false, "https://user:password@example/private?q=secret")))
        restored.restore(HeartbeatSnapshot(scopes = bad))
        assertTrue(restored.snapshot().scopes.isEmpty())
        restored.restore(HeartbeatSnapshot(scopes = listOf(valid.copy(stablePulses = Int.MAX_VALUE, volatility = -99, failures = Int.MAX_VALUE, nextDueAt = Long.MAX_VALUE))))
        val clean = restored.state(scope)!!
        assertEquals(20, clean.stablePulses); assertEquals(0, clean.volatility); assertEquals(16, clean.failures)
        assertEquals(now + RouteHeartbeat.MAX_DELAY, clean.nextDueAt)
        restored.restore(Gson().fromJson("{\"version\":1,\"scopes\":[null,{\"scope\":null}],\"paths\":[null],\"attempts\":[null]}", HeartbeatSnapshot::class.java))
        assertTrue(restored.snapshot().scopes.isEmpty())
    }

    @Test fun inactiveDemandStopsProbingAndAllStateExpires() {
        val policy = policy(); activate(policy)
        now += 7 * RouteHeartbeat.MAX_DELAY + 1
        assertNull(policy.dueAt(scope)); assertNull(policy.nextAllowedAt(scope, listOf(source)))
        assertNull(policy.reserve(scope, source, "alternate then original"))
        assertEquals(1, policy.snapshot().scopes.size)
        now += 23 * RouteHeartbeat.MAX_DELAY
        assertTrue(policy.snapshot().scopes.isEmpty())
    }

    @Test fun stateAndImportedHistoryStayBounded() {
        val policy = policy()
        repeat(520) { index -> activate(policy, HeartbeatScope(Platform.MASTODON, Capability.TIMELINE, false, "https://server$index.example")) }
        assertEquals(512, policy.snapshot().scopes.size)
        val snapshot = policy.snapshot()
        val restored = policy()
        restored.restore(snapshot.copy(paths = List(600) { index -> HeartbeatPathState("%064x".format(index), "host$index.example", lastObservedAt = now) },
            attempts = List(600) { HeartbeatAttempt("host$it.example", now) }))
        assertEquals(512, restored.snapshot().paths.size)
        assertEquals(12, restored.snapshot().attempts.size)
    }

    @Test fun deterministicJitterSpreadsScopesAndNeverSavesSensitivePlanInputs() {
        val a = policy(); val b = policy()
        activate(a, winner = "https://reader.example/private-account", plan = "search=privatewords")
        activate(b, winner = "https://reader.example/private-account", plan = "search=privatewords")
        assertEquals(a.snapshot(), b.snapshot())
        val encoded = Gson().toJson(a.snapshot())
        assertFalse(encoded.contains("private-account")); assertFalse(encoded.contains("privatewords"))
        val delays = Platform.entries.map {
            val scope = HeartbeatScope(it, Capability.TIMELINE, false)
            activate(a, scope)
            a.dueAt(scope)!! - now
        }.toSet()
        assertTrue(delays.size > 10)
    }

    @Test fun malformedOrForeignSourcesCannotClaimAndClearInvalidatesOutstandingWork() {
        val policy = policy(); activate(policy)
        now = policy.dueAt(scope)!!
        assertNull(policy.reserve(scope, source.copy(platform = Platform.REDDIT), "alternate then original"))
        assertNull(policy.reserve(scope, source.copy(root = "https://private:password@host.example"), "alternate then original"))
        assertNull(policy.nextAllowedAt(scope, listOf(source.copy(root = "javascript:secret"))))
        val old = policy.reserve(scope, source, "alternate then original")!!
        policy.clear()
        assertFalse(policy.complete(old, HeartbeatOutcome.FAILED, "changed"))
        activate(policy)
        now = policy.dueAt(scope)!!
        val next = policy.reserve(scope, source, "alternate then original")!!
        assertNotEquals(old.id, next.id)
        assertFalse(policy.complete(old, HeartbeatOutcome.FAILED, "changed"))
        assertTrue(policy.complete(next, HeartbeatOutcome.HEALTHY, "alternate then original"))
    }
}
