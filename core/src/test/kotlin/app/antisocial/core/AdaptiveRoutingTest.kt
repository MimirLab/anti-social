package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class AdaptiveRoutingTest {
    private var now=100L*86_400_000
    private val source=Source(Platform.X,"nitter","https://reader.example")
    private val first=Target(Platform.X,TargetKind.PROFILE,"one","https://x.com/one")
    private val second=Target(Platform.X,TargetKind.PROFILE,"two","https://x.com/two")
    private fun policy()=AdaptiveRouting {now}
    private fun result(status: HealthStatus,ms: Long=1000,cap: Capability=Capability.TIMELINE,tor: Boolean=false,detail: String="public content",selected: Source=source)=ProbeResult(selected,cap,status,ms,now,tor,detail)

    @Test fun timingsAndCooldownsAreIndependentAcrossCapabilitiesAndNetworkModes() {
        val routing=policy();routing.observe(result(HealthStatus.HEALTHY,200),first)
        routing.observe(result(HealthStatus.RATE_LIMITED,tor=true),first)
        routing.observe(result(HealthStatus.UNAVAILABLE,cap=Capability.COMMENTS,detail="Could not connect"),first)
        assertTrue(routing.eligible(source,Capability.TIMELINE,false,first))
        assertFalse(routing.eligible(source,Capability.TIMELINE,true,second))
        assertFalse(routing.eligible(source,Capability.COMMENTS,false,second))
        assertTrue(routing.eligible(source,Capability.PROFILE,true,first))
        assertEquals(200.0,routing.get(source,Capability.TIMELINE,false)!!.deliveryMs,0.0)
        assertEquals(3,routing.snapshot().size)
    }
    @Test fun emptyAndLoginRestrictedAccountsDoNotDisableOtherProfiles() {
        val routing=policy();routing.observe(result(HealthStatus.EMPTY),first)
        assertFalse(routing.eligible(source,Capability.TIMELINE,false,first))
        assertTrue(routing.eligible(source,Capability.TIMELINE,false,second))
        assertEquals(HealthStatus.EMPTY,routing.failure(source,Capability.TIMELINE,false,first)?.status)
        now+=20_001
        assertTrue(routing.eligible(source,Capability.TIMELINE,false,first))
        routing.observe(result(HealthStatus.LOGIN_REQUIRED),first)
        assertTrue(routing.eligible(source,Capability.TIMELINE,false,second))
        assertFalse(routing.eligible(source,Capability.TIMELINE,false,first))
        routing.observe(result(HealthStatus.HEALTHY),first)
        assertNull(routing.failure(source,Capability.TIMELINE,false,first))
    }
    @Test fun rateLimitsBackOffTheRouteAndSuccessResetsIt() {
        val routing=policy()
        val firstRate=routing.observe(result(HealthStatus.RATE_LIMITED),first)
        assertEquals(now+60_000,firstRate.cooldownUntil)
        assertFalse(routing.eligible(source,Capability.TIMELINE,false,second))
        val repeated=routing.observe(result(HealthStatus.RATE_LIMITED),second)
        assertEquals(now+120_000,repeated.cooldownUntil)
        now+=120_001;assertTrue(routing.eligible(source,Capability.TIMELINE,false,second))
        val recovered=routing.observe(result(HealthStatus.HEALTHY,250),second)
        assertEquals(0L,recovered.cooldownUntil);assertEquals(0,recovered.consecutiveFailures)
    }
    @Test fun aPublicIndexPauseAppliesAcrossPlatformsButNotOtherRoutesOrTransports() {
        val routing=policy();val index=Source(Platform.INSTAGRAM,"librey","https://index.example")
        val sibling=index.copy(platform=Platform.FACEBOOK)
        routing.observe(result(HealthStatus.RATE_LIMITED,cap=Capability.SEARCH,selected=index).copy(retryAfterMs=120_000))
        assertFalse(routing.eligible(sibling,Capability.SEARCH,false))
        assertTrue(routing.eligible(sibling,Capability.SEARCH,true))
        assertTrue(routing.eligible(sibling,Capability.PROFILE,false))
        assertTrue(routing.eligible(sibling.copy(root="https://independent.example"),Capability.SEARCH,false))
        now+=120_001;assertTrue(routing.eligible(sibling,Capability.SEARCH,false))
    }
    @Test fun inFlightSuccessDoesNotEraseAPublicIndexPauseAndSnapshotsPreserveIt() {
        val routing=policy();val index=Source(Platform.INSTAGRAM,"librey","https://index.example")
        val sibling=index.copy(platform=Platform.FACEBOOK)
        routing.observe(result(HealthStatus.RATE_LIMITED,cap=Capability.SEARCH,selected=index).copy(retryAfterMs=120_000))
        routing.observe(result(HealthStatus.HEALTHY,100,cap=Capability.SEARCH,selected=index))
        routing.observe(result(HealthStatus.HEALTHY,100,cap=Capability.SEARCH,selected=sibling))
        assertFalse("A response already in flight does not revoke Retry-After",routing.eligible(index,Capability.SEARCH,false))
        assertFalse(routing.eligible(sibling,Capability.SEARCH,false))
        val restored=policy();restored.restore(routing.snapshot())
        assertFalse(restored.eligible(index,Capability.SEARCH,false));assertFalse(restored.eligible(sibling,Capability.SEARCH,false))
        assertEquals(1,routing.get(index,Capability.SEARCH,false)!!.deliverySamples)
    }
    @Test fun partialDeliveryKeepsOneUsefulSampleAndAnIncreasingProviderBackoff() {
        val routing=policy();val index=Source(Platform.INSTAGRAM,"librey","https://index.example")
        val refusal=result(HealthStatus.RATE_LIMITED,900,cap=Capability.SEARCH,selected=index)
        val first=routing.observePartial(refusal,200)
        assertEquals(1,first.samples);assertEquals(1,first.successes);assertEquals(1,first.deliverySamples)
        assertEquals(200.0,first.deliveryMs,0.0);assertEquals(1,first.consecutiveFailures)
        now+=60_001
        val second=routing.observePartial(refusal.copy(checkedAt=now),220)
        assertEquals(now+120_000,second.cooldownUntil);assertEquals(2,second.consecutiveFailures)
        now+=120_001
        val healthy=routing.observe(result(HealthStatus.HEALTHY,200,cap=Capability.SEARCH,selected=index))
        assertEquals(0L,healthy.cooldownUntil);assertEquals(0,healthy.consecutiveFailures)
    }
    @Test fun publicIndexLoginPausesTheProviderButOrdinaryAccountLoginRemainsTargetSpecific() {
        val routing=policy();val index=Source(Platform.INSTAGRAM,"librey","https://index.example")
        routing.observe(result(HealthStatus.LOGIN_REQUIRED,cap=Capability.SEARCH,selected=index),first)
        assertFalse(routing.eligible(index.copy(platform=Platform.FACEBOOK),Capability.SEARCH,false))
        assertTrue(routing.eligible(index.copy(root="https://other.example"),Capability.SEARCH,false))
        assertTrue(routing.eligible(index,Capability.PROFILE,false))
    }
    @Test fun unavailableContentIsScopedButDnsAndServerOutagesAreGlobal() {
        val routing=policy();routing.observe(result(HealthStatus.UNAVAILABLE,detail="Source returned 404"),first)
        assertTrue(routing.eligible(source,Capability.TIMELINE,false,second))
        routing.observe(result(HealthStatus.UNAVAILABLE,detail="Unable to resolve host"),second)
        assertFalse(routing.eligible(source,Capability.TIMELINE,false))
        now+=30_001;assertTrue(routing.eligible(source,Capability.TIMELINE,false))
        routing.observe(result(HealthStatus.UNAVAILABLE,detail="Source returned 503"),second)
        assertFalse(routing.eligible(source,Capability.TIMELINE,false,first))
    }
    @Test fun deliveryEstimatesUseSuccessfulReadsAndHedgesStayBounded() {
        val routing=policy();routing.observe(result(HealthStatus.HEALTHY,400),first)
        assertEquals(500L,routing.hedgeDelay(source,Capability.TIMELINE,false))
        routing.observe(result(HealthStatus.HEALTHY,1200),first)
        val measured=routing.get(source,Capability.TIMELINE,false)!!
        assertEquals(600.0,measured.deliveryMs,0.0);assertEquals(275.0,measured.deviationMs,0.0)
        assertEquals(875L,routing.hedgeDelay(source,Capability.TIMELINE,false))
        routing.observe(result(HealthStatus.LOGIN_REQUIRED,100_000),second)
        assertEquals(600.0,routing.get(source,Capability.TIMELINE,false)!!.deliveryMs,0.0)
        assertEquals(1500L,routing.hedgeDelay(source,Capability.TIMELINE,true))
        routing.observe(result(HealthStatus.HEALTHY,170_000,tor=true),first)
        assertEquals(3500L,routing.hedgeDelay(source,Capability.TIMELINE,true))
    }
    @Test fun statsExpireAndRestoreRejectsCorruptOrUnboundedObservations() {
        val routing=policy()
        routing.restore(listOf(RouteStats(source,Capability.POST,false,1,1,Double.NaN,lastObservedAt=now),RouteStats(source,Capability.COMMENTS,false,1,2,lastObservedAt=now),RouteStats(source,Capability.MEDIA,false,1,1,lastObservedAt=now+120_000)))
        assertTrue(routing.snapshot().isEmpty())
        val valid=RouteStats(source,Capability.TIMELINE,false,1,1,lastObservedAt=now,cooldownUntil=Long.MAX_VALUE)
        routing.restore(listOf(valid));assertEquals(now+86_400_000,routing.get(source,Capability.TIMELINE,false)?.cooldownUntil)
        now+=7L*86_400_000+1;assertTrue(routing.snapshot().isEmpty())
    }
    @Test fun routeAndPerTargetHistoryRemainBounded() {
        val routing=policy()
        repeat(520){index->routing.observe(result(HealthStatus.HEALTHY,selected=source.copy(root="https://reader$index.example")),first)}
        assertEquals(512,routing.snapshot().size)
        assertNull(routing.get(source.copy(root="https://reader0.example"),Capability.TIMELINE,false))
        repeat(1030){index->routing.observe(result(HealthStatus.EMPTY),first.copy(canonicalUrl="https://x.com/target$index"))}
        assertNull(routing.failure(source,Capability.TIMELINE,false,first.copy(canonicalUrl="https://x.com/target0")))
        assertNotNull(routing.failure(source,Capability.TIMELINE,false,first.copy(canonicalUrl="https://x.com/target1029")))
    }
    @Test fun oversizedSoundingsAreInconclusiveAndPreserveWorkingEvidence() {
        val routing=policy();routing.observe(result(HealthStatus.HEALTHY,300),first)
        val before=routing.get(source,Capability.TIMELINE,false)
        routing.observe(result(HealthStatus.UNAVAILABLE,detail="Response exceeds the health-check budget"),first)
        assertEquals(before,routing.get(source,Capability.TIMELINE,false))
        assertTrue(routing.eligible(source,Capability.TIMELINE,false,first))
    }
    @Test fun probesUpdateAvailabilityWithoutTrainingDeliveryTimings() {
        val routing=policy()
        routing.observe(result(HealthStatus.HEALTHY,9000),first,delivery=false)
        val probeOnly=routing.get(source,Capability.TIMELINE,false)!!
        assertEquals(1,probeOnly.successes);assertEquals(0,probeOnly.deliverySamples)
        assertEquals(1500.0,probeOnly.deliveryMs,0.0)
        assertEquals(600L,routing.hedgeDelay(source,Capability.TIMELINE,false))
        routing.observe(result(HealthStatus.HEALTHY,400),first)
        val delivered=routing.get(source,Capability.TIMELINE,false)!!
        assertEquals(400.0,delivered.deliveryMs,0.0);assertEquals(100.0,delivered.deviationMs,0.0)
        routing.observe(result(HealthStatus.HEALTHY,120_000),second,delivery=false)
        val afterProbe=routing.get(source,Capability.TIMELINE,false)!!
        assertEquals(3,afterProbe.successes);assertEquals(1,afterProbe.deliverySamples)
        assertEquals(delivered.deliveryMs,afterProbe.deliveryMs,0.0);assertEquals(delivered.deviationMs,afterProbe.deviationMs,0.0)
        routing.observe(result(HealthStatus.HEALTHY,1200),first)
        assertEquals(600.0,routing.get(source,Capability.TIMELINE,false)!!.deliveryMs,0.0)
        assertEquals(2,routing.get(source,Capability.TIMELINE,false)!!.deliverySamples)
    }
    @Test fun targetRestrictionsDoNotDamageAnotherAccountsRouteEvidence() {
        val routing=policy();routing.observe(result(HealthStatus.HEALTHY,200),first)
        val previous=routing.get(source,Capability.TIMELINE,false);val score=routing.score(source,Capability.TIMELINE,false)
        for(failure in listOf(result(HealthStatus.EMPTY),result(HealthStatus.LOGIN_REQUIRED),result(HealthStatus.UNAVAILABLE,detail="Source returned 404"),result(HealthStatus.UNAVAILABLE,detail="Source returned 410"))) {
            assertTrue(AdaptiveRouting.isTargetSpecific(failure))
            routing.observe(failure,second)
            assertEquals(previous,routing.get(source,Capability.TIMELINE,false))
            assertEquals(score,routing.score(source,Capability.TIMELINE,false),0.0)
            assertFalse(routing.eligible(source,Capability.TIMELINE,false,second))
            assertTrue(routing.eligible(source,Capability.TIMELINE,false,first))
        }
        val fresh=policy();fresh.observe(result(HealthStatus.EMPTY),second)
        assertTrue(fresh.snapshot().isEmpty());assertNotNull(fresh.failure(source,Capability.TIMELINE,false,second))
    }
    @Test fun targetRestrictionsDoNotIncreaseGlobalBackoffStreak() {
        val routing=policy();routing.observe(result(HealthStatus.RATE_LIMITED),first)
        routing.observe(result(HealthStatus.LOGIN_REQUIRED),second)
        assertEquals(1,routing.get(source,Capability.TIMELINE,false)!!.consecutiveFailures)
        val repeated=routing.observe(result(HealthStatus.RATE_LIMITED),first)
        assertEquals(now+120_000,repeated.cooldownUntil)
        assertFalse(AdaptiveRouting.isTargetSpecific(result(HealthStatus.UNAVAILABLE,detail="Source returned 500")))
        val failed=policy();failed.observe(result(HealthStatus.UNAVAILABLE,detail="Source returned 500"),second)
        assertFalse(failed.eligible(source,Capability.TIMELINE,false,first))
    }
    @Test fun serverRequestedPausesSurviveRestoreAndCannotBeShortenedByAnotherFailure() {
        val routing=policy()
        routing.observe(result(HealthStatus.RATE_LIMITED).copy(retryAfterMs=3_600_000),first)
        val until=now+3_600_000
        assertEquals(until,routing.get(source,Capability.TIMELINE,false)!!.cooldownUntil)
        now+=1000
        routing.observe(result(HealthStatus.UNAVAILABLE,detail="Source returned 503").copy(retryAfterMs=1000),second)
        assertEquals(until,routing.get(source,Capability.TIMELINE,false)!!.cooldownUntil)
        val restored=policy();restored.restore(routing.snapshot())
        assertEquals(until,restored.get(source,Capability.TIMELINE,false)!!.cooldownUntil)
        assertFalse(restored.eligible(source,Capability.TIMELINE,false,second))
        val bounded=policy();bounded.observe(result(HealthStatus.RATE_LIMITED).copy(retryAfterMs=Long.MAX_VALUE),first)
        assertEquals(now+86_400_000,bounded.get(source,Capability.TIMELINE,false)!!.cooldownUntil)
    }
    @Test fun anInFlightSuccessKeepsAnExplicitServerWaitScopedAndRestorable() {
        val routing=policy()
        routing.observe(result(HealthStatus.RATE_LIMITED).copy(retryAfterMs=120_000),first)
        val until=now+120_000
        now+=30_000
        routing.observe(result(HealthStatus.HEALTHY,100),second)
        assertEquals(until,routing.eligibleAt(source,Capability.TIMELINE,false,first))
        assertEquals(until,routing.eligibleAt(source,Capability.TIMELINE,false,second))
        assertTrue(routing.eligible(source,Capability.TIMELINE,true,first))
        assertTrue(routing.eligible(source,Capability.POST,false,first))
        assertTrue(routing.eligible(source.copy(root="https://other.example"),Capability.TIMELINE,false,first))
        val restored=policy();restored.restore(routing.snapshot())
        assertEquals(until,restored.eligibleAt(source,Capability.TIMELINE,false,second))
        assertEquals(1,restored.get(source,Capability.TIMELINE,false)!!.deliverySamples)
        now=until+1
        assertTrue(restored.eligible(source,Capability.TIMELINE,false,second))
        restored.observe(result(HealthStatus.HEALTHY,100),second)
        assertEquals(0L,restored.get(source,Capability.TIMELINE,false)!!.retryAfterUntil)
    }
    @Test fun earliestEligibilityIncludesBothTargetAndGlobalPauses() {
        val routing=policy()
        assertEquals(now,routing.eligibleAt(source,Capability.TIMELINE,false,first))
        routing.observe(result(HealthStatus.EMPTY),first)
        assertEquals(now+20_000,routing.eligibleAt(source,Capability.TIMELINE,false,first))
        assertEquals(now,routing.eligibleAt(source,Capability.TIMELINE,false,second))
        routing.observe(result(HealthStatus.RATE_LIMITED).copy(retryAfterMs=3_600_000),second)
        assertEquals(now+3_600_000,routing.eligibleAt(source,Capability.TIMELINE,false,first))
        assertEquals(now+3_600_000,routing.eligibleAt(source,Capability.TIMELINE,false,second))
        assertEquals(now,routing.eligibleAt(source,Capability.TIMELINE,true,first))
        now+=3_600_001
        assertEquals(now,routing.eligibleAt(source,Capability.TIMELINE,false,first))
        assertTrue(routing.eligible(source,Capability.TIMELINE,false,first))
    }
}
