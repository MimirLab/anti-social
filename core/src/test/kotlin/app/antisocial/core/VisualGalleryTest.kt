/* Anti Social, GPL-3.0-or-later. Local presentation evidence, not authentication. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class VisualGalleryTest {
    private val colors=List(48){80+it*2}
    private val pixels=ByteArray(4096){(60+it%140).toByte()}
    private fun frame(detail: ByteArray?=pixels,aspect: Double=1.25,cells: List<Int> = colors)=VisualFrames.encode(aspect,50.0,0x1234,0x4567,cells,detail)!!

    @Test fun smallEncodingNoiseMatchesWithoutNeedingTheSameCoarseHash() {
        val noisy=pixels.mapIndexed {i,b->((b.toInt() and 255)+if(i%2==0)2 else -2).toByte()}.toByteArray()
        val other=VisualFrames.encode(1.25,50.0,0xffff,0xcccc,colors,noisy)!!
        assertFalse(VisualFrames.same(frame(),other))
        assertTrue(VisualFrames.sameImage(frame(),other))
    }
    @Test fun aSmallChangedRegionCannotDisappearInsideAGlobalAverage() {
        val correction=pixels.copyOf()
        for(y in 20..23)for(x in 40..43)correction[y*64+x]=((pixels[y*64+x].toInt() and 255)+60).coerceAtMost(255).toByte()
        assertTrue(VisualFrames.same(frame(),frame(correction)))
        assertFalse(VisualFrames.sameImage(frame(),frame(correction)))
    }
    @Test fun aBroadEditAndAChangedShapeRemainSeparate() {
        val changed=pixels.map {(it.toInt()+10).toByte()}.toByteArray()
        assertFalse(VisualFrames.sameImage(frame(),frame(changed)))
        assertFalse(VisualFrames.sameImage(frame(),frame(aspect=1.4)))
        assertFalse(VisualFrames.sameImage(frame(),frame(cells=List(48){240})))
    }
    @Test fun oldCapturedEditionsRemainReadableAndComparable() {
        val old=frame(detail=null)
        assertTrue(VisualFrames.valid(old));assertTrue(VisualFrames.sameImage(old,old))
        assertTrue(VisualFrames.sameImage(old,frame()));assertTrue(VisualFrames.valid(frame()))
    }
    @Test fun malformedOrOversizedRasterEvidenceFailsClosed() {
        val good=frame()
        for(bad in listOf(good.dropLast(1),good+"A",good.dropLast(3)+"!!!",good.replaceFirst("f2|","f3|"))) {
            assertFalse(VisualFrames.valid(bad));assertFalse(VisualFrames.sameImage(good,bad))
            assertFalse(VisualFrames.sameImage(frame(detail=null),bad))
        }
        assertNull(VisualFrames.encode(1.25,50.0,0,0,colors,ByteArray(4097)))
    }
}
