/* anti social, GPL-3.0-or-later. Exact text reuse, unchanged source semantics. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PresentationTextPreparationTest {
    private fun post(text:String,index:Int=0)=Post("${81000001+index}",Platform.X,"https://x.com/studio/status/${81000001+index}","Studio",text,publishedAt=1_800_000_000_000L)
    private val inputs=listOf(
        "<b>Approved</b> &amp; published.","<b>Not approved</b> &amp; published.",
        "The total is $40, not $400.","The total is $400, not $40.",
        "Read https://example.org/Part?key=A#one","Read https://example.org/part?key=a#two",
        "Read http://example.org/Part?key=A#one","Read https://example.org/Part?key=A#two",
        "Photo Cafe\u0301","Photo Café","ordinary\u200b text","ordinary text",
        "<script>Hidden instructions</script>Public caption.","<style>Hidden style</style>Public caption.",
        "A > B?","A < B!","", "A caption with an authored &amp;amp; entity.")
    @Test fun exactReusePreservesHtmlUnicodeClaimsAndCaseSensitiveDestinations() {
        val expected=inputs.associateWith {PostPresentation.comparableContent(post(it))}
        CrossPostFusion.withPrepared {
            repeat(3){pass->for((i,text)in inputs.reversed().withIndex()) {
                val source=post(text,pass*100+i)
                assertEquals(expected.getValue(text),PostPresentation.comparableContent(source))
                assertEquals(text,source.body)
            }}
            val stats=CrossPostFusion.preparationStats()!!
            assertEquals(inputs.size,stats.textComputations);assertEquals(inputs.size*2,stats.textHits)
            assertNotEquals(expected[inputs[0]],expected[inputs[1]])
            assertNotEquals(expected[inputs[2]],expected[inputs[3]])
            assertNotEquals(expected[inputs[4]],expected[inputs[5]])
            assertNotEquals(expected[inputs[4]],expected[inputs[6]])
            assertNotEquals(expected[inputs[4]],expected[inputs[7]])
        }
        assertNull(CrossPostFusion.preparationStats())
    }
    @Test fun repeatedHeadingsAcrossIndependentEditionsKeepTheirOriginalPresentation() {
        val caption="The public workshop opens its repair benches this afternoon. Bring your broken equipment and inspect the illustrated maintenance guide."
        val a=post(caption).copy(title=caption)
        val b=post(caption,1).copy(platform=Platform.FACEBOOK,title=caption)
        val c=post(caption,2).copy(title="Additional instructions",links=listOf("https://example.org/Guide?edition=A"))
        val expected=listOf(a,b,c).map(PostPresentation::text)
        CrossPostFusion.withPrepared {
            assertEquals(expected,listOf(a,b,c).map(PostPresentation::text))
            assertEquals("",PostPresentation.text(a).title)
            assertEquals("Additional instructions",PostPresentation.text(c).title)
            assertTrue(CrossPostFusion.preparationStats()!!.textHits>=3)
        }
    }
    @Test fun boundsOversizedInputsAndInterruptedWorkCannotRetainReadingHistory() {
        CrossPostFusion.withPrepared {
            repeat(3000){i->PostPresentation.comparableContent(post("Public bulletin $i",i))}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.textEntries<=2048);assertTrue(stats.bytes<=8L*1024*1024)
        }
        val huge="A long public caption. ".repeat(1000);val expected=PostPresentation.comparableContent(post(huge))
        CrossPostFusion.withPrepared {
            repeat(2){i->assertEquals(expected,PostPresentation.comparableContent(post(huge,i)))}
            assertEquals(0,CrossPostFusion.preparationStats()!!.textEntries)
        }
        assertThrows(IllegalStateException::class.java){CrossPostFusion.withPrepared {
            PostPresentation.comparableContent(post(inputs.first()));error("Interrupted preparation")
        }}
        assertNull(CrossPostFusion.preparationStats())
        CrossPostFusion.withPrepared {
            PostPresentation.comparableContent(post(inputs.first()))
            assertEquals(1,CrossPostFusion.preparationStats()!!.textComputations)
            assertEquals(0,CrossPostFusion.preparationStats()!!.textHits)
        }
    }
    @Test fun parallelReadersAndNestedWorkHaveOnlyTheirOwnOperationState() {
        val workers=Executors.newFixedThreadPool(2);val barrier=CyclicBarrier(2)
        try {
            val jobs=(0..1).map {worker->workers.submit<String> {CrossPostFusion.withPrepared {
                val text=inputs[4+worker]
                val first=PostPresentation.comparableContent(post(text))
                barrier.await(10,TimeUnit.SECONDS)
                CrossPostFusion.withPrepared {assertEquals(first,PostPresentation.comparableContent(post(text,1)))}
                val stats=CrossPostFusion.preparationStats()!!
                assertEquals(1,stats.textComputations);assertEquals(1,stats.textHits)
                first
            }}}
            assertNotEquals(jobs[0].get(15,TimeUnit.SECONDS),jobs[1].get(15,TimeUnit.SECONDS))
            assertNull(CrossPostFusion.preparationStats())
        }finally {workers.shutdownNow()}
    }
    @Test fun parserEndMarkersCannotEraseLiteralTailsOrDistinctHeadings() {
        val heading="<b>Public workshop update.</b>\uffff Admission is not approved."
        val source=post("Public workshop update.").copy(title=heading)
        assertEquals(heading,PostPresentation.text(source).title)
        assertEquals(source.body,PostPresentation.text(source).body)
        val values=listOf("Admission is approved.","Admission is not approved.","Capacity is 40.","Capacity is 400.")
            .map {"<b>Public workshop update.</b>\uffff $it"}
        CrossPostFusion.withPrepared {
            assertEquals(values,values.map {PostPresentation.comparableContent(post(it))})
        }
        assertEquals(values.size,values.map {PostPresentation.comparableContent(post(it))}.distinct().size)
    }
}
