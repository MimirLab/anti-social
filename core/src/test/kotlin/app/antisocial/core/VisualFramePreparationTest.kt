/* anti social, GPL-3.0-or-later. Operation-local comparison compatibility. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class VisualFramePreparationTest {
    private fun frame(detail:Boolean=true,seed:Int=0)=VisualFrames.encode(1.25,50.0,seed.toLong(),seed.toLong(),
        List(48){80+it*2},if(detail)ByteArray(4096){(60+it%140).toByte()}else null)!!
    private fun answers(a:String?,b:String?)=listOf(VisualFrames.valid(a),VisualFrames.valid(b),VisualFrames.same(a,b),
        VisualFrames.same(a,b,nearbyFrame=true),VisualFrames.sameImage(a,b))

    @Test fun reuseRetainsEveryCoarseDetailedAndMalformedComparisonOutcome() {
        val good=frame();val old=frame(false)
        val inputs=listOf(null,old,good,frame(seed=3),good.dropLast(3)+"!!!",good.dropLast(1),good+"A",
            good.replaceFirst("f2|","f3|"),good.replaceFirst("|12500|","|250000|"),"x".repeat(100_000))
        val expected=inputs.flatMap {a->inputs.map {b->answers(a,b)}}
        CrossPostFusion.withPrepared {
            repeat(3) {assertEquals(expected,inputs.flatMap {a->inputs.map {b->answers(a,b)}})}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.frameHits>stats.frameComputations)
            // A successful coarse read of the same string cannot stand in for
            // validation of malformed base64 or the changed-detail guard.
            assertFalse(VisualFrames.valid(inputs[4]));assertFalse(VisualFrames.sameImage(good,inputs[4]))
            val changed=VisualFrames.encode(1.25,50.0,0,0,List(48){80+it*2},ByteArray(4096){(60+it%140).toByte()}.apply {this[512]=0})!!
            assertTrue(VisualFrames.same(good,changed));assertFalse(VisualFrames.sameImage(good,changed))
        }
        assertNull(CrossPostFusion.preparationStats())
    }

    @Test fun nestedReadersReuseEvidenceOnlyUntilTheOwningOperationEnds() {
        val value=frame()
        CrossPostFusion.withPrepared {
            assertTrue(VisualFrames.valid(value))
            val first=CrossPostFusion.preparationStats()!!
            CrossPostFusion.withPrepared {assertTrue(VisualFrames.valid(value))}
            assertEquals(first.frameComputations,CrossPostFusion.preparationStats()!!.frameComputations)
            assertEquals(first.frameHits+1,CrossPostFusion.preparationStats()!!.frameHits)
        }
        assertThrows(IllegalStateException::class.java) {CrossPostFusion.withPrepared {VisualFrames.valid(value);error("Stopped reader")}}
        assertNull(CrossPostFusion.preparationStats())
        CrossPostFusion.withPrepared {assertTrue(VisualFrames.valid(value));assertEquals(1,CrossPostFusion.preparationStats()!!.frameComputations)}
    }

    @Test fun rasterReuseCannotExtendTheExistingWorkspaceByteAllowance() {
        CrossPostFusion.withPrepared {
            repeat(900){assertTrue(VisualFrames.valid(frame(seed=it)))}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.frameEntries in 1..512);assertTrue(stats.frameEntries<900)
            assertTrue(stats.bytes<=8L*1024*1024)
        }
        CrossPostFusion.withPrepared {
            repeat(130){i->val url="https://fixture.invalid/$i/"+"a".repeat(16_384);CrossPostFusion.normalizedUrl(url){url}}
            val before=CrossPostFusion.preparationStats()!!.bytes
            repeat(50){assertTrue(VisualFrames.valid(frame(seed=it)))}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.bytes>=before);assertTrue(stats.bytes<=8L*1024*1024)
            assertTrue("Image and URL preparation share one allowance",stats.frameEntries<50)
        }
    }
}
