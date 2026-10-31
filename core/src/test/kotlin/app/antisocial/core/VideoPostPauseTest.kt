/* anti social, GPL-3.0-or-later. Same HTTP operation, distinct local purposes. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class VideoPostPauseTest {
    private val source=Source(Platform.TIKTOK,"tikwm","https://reader.example")
    private fun pause(at:Long,capability:Capability)=ProbeResult(source,capability,HealthStatus.RATE_LIMITED,100,at,false,"Source is rate limited",120_000)
    @Test fun theSharedPostApiHonorsAWaitFromEitherCapability() {
        val at=1_800_000_000_000L
        for(first in listOf(Capability.POST,Capability.MEDIA)) {
            val bank=AdaptiveRouting {at};bank.observe(pause(at,first))
            assertFalse(bank.eligible(source,Capability.POST,false));assertFalse(bank.eligible(source,Capability.MEDIA,false))
            assertEquals(120_000L,bank.failure(source,Capability.POST,false,Target(Platform.TIKTOK,TargetKind.POST,"710003","https://www.tiktok.com/@publicstudio/video/710003"))!!.retryAfterMs)
            assertTrue(bank.eligible(source,Capability.POST,true));assertTrue(bank.eligible(source.copy(root="https://other.example"),Capability.POST,false))
            assertTrue(bank.eligible(source.copy(frontend="tikwm-comments"),Capability.COMMENTS,false))
        }
    }
    @Test fun anInflightSuccessInTheOtherPurposeCannotClearTheServerDeadline() {
        var at=1_800_000_000_000L;val bank=AdaptiveRouting {at};bank.observe(pause(at,Capability.MEDIA))
        at+=1_000;bank.observe(ProbeResult(source,Capability.POST,HealthStatus.HEALTHY,20,at,false,"Post delivered"))
        assertFalse(bank.eligible(source,Capability.POST,false));assertFalse(bank.eligible(source,Capability.MEDIA,false))
        at+=119_001;assertTrue(bank.eligible(source,Capability.POST,false));assertTrue(bank.eligible(source,Capability.MEDIA,false))
    }
    @Test fun aRestoredDeadlineStillCoversBothPurposesAndExpiresNormally() {
        var at=1_800_000_000_000L;val first=AdaptiveRouting {at};first.observe(pause(at,Capability.POST))
        val bank=AdaptiveRouting {at};bank.restore(first.snapshot())
        assertFalse(bank.eligible(source,Capability.MEDIA,false));at+=120_001
        assertTrue(bank.eligible(source,Capability.MEDIA,false))
    }
    @Test fun anEmptyMetadataResultDoesNotPauseTheReadablePost() {
        val at=1_800_000_000_000L;val bank=AdaptiveRouting {at}
        val target=Target(Platform.TIKTOK,TargetKind.POST,"710003","https://www.tiktok.com/@publicstudio/video/710003")
        bank.observe(ProbeResult(source,Capability.MEDIA,HealthStatus.EMPTY,10,at,false,"No changed delivery"),target)
        assertFalse(bank.eligible(source,Capability.MEDIA,false,target));assertTrue(bank.eligible(source,Capability.POST,false,target))
    }
}
