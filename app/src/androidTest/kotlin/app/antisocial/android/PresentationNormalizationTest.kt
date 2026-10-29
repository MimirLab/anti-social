/* anti social, GPL-3.0-or-later. Literal source clauses must survive normalization. */
package app.antisocial.android

import app.antisocial.core.*
import org.junit.Assert.*
import org.junit.Test

class PresentationNormalizationTest {
    private fun post(text:String,index:Int=0)=Post("${81000001+index}",if(index==0)Platform.X else Platform.FACEBOOK,
        if(index==0)"https://x.com/studio/status/81000001"else "https://www.facebook.com/studio/posts/81000002",
        "Studio",text,publishedAt=1_800_000_000_000L+index)
    @Test fun aParserEndMarkerCannotEraseDistinctClausesFromAnAdmittedCollection() {
        for((a,b)in listOf("Admission is approved." to "Admission is not approved.",
            "Capacity is 40." to "Capacity is 400.","Read https://example.org/Part?key=A" to "Read https://example.org/part?key=a")) {
            val sources=listOf(post("<b>Public workshop update.</b>\uffff $a"),post("<b>Public workshop update.</b>\uffff $b",1))
            val plan=collectionPlan(sources)
            val display=plan.fusion?.let {fusion->fusion.weave?.flatMap {it.variants.map(FusionVariant::visibleText)} ?: fusion.segments.map {it.text}}
                ?: plan.originals.map(PostPresentation::content)
            if(!a.startsWith("Read https://")) {
                assertTrue("The first source's literal clause must be displayed",display.any {a in it})
                assertTrue("The other source's changed clause must be displayed",display.any {b in it})
            }
            // URL reference suffixes can be shown through the existing links
            // inventory. Their literal source paths must still reconstruct;
            // displayText is deliberately different from evidence text there.
            for((source,tail)in sources.zip(listOf(a,b))) {
                val key=CrossPosts.originalKey(source)
                val literal=plan.fusion?.let {fusion->
                    fusion.weave?.map {run->run.variants.single {key in it.originalKeys}.text}
                        ?: fusion.segments.filter {key in it.originalKeys}.map {it.text}
                } ?: listOf(PostPresentation.content(source))
                assertTrue("Every source keeps its exact literal clause",literal.joinToString(" ").contains(tail))
            }
            assertEquals(sources,plan.originals)
        }
    }
    @Test fun aParserEndMarkerCannotTurnARealHeadingIntoAnEchoOfItsBody() {
        val source=post("Public workshop update.").copy(title="<b>Public workshop update.</b>\uffff Admission is not approved.")
        val text=PostPresentation.text(source)
        assertEquals(source.title,text.title);assertEquals(source.body,text.body)
    }
    @Test fun plainAndHtmlTextRetainEquivalentReadingCaptionsOnAndroid() {
        val characters=listOf('\u0001','\t','\n','\r','\u00a0','\u00ad','\u0085','\u0301','\u200b','\u200d','\u2028','\u2029','\u2060','\ud800','\udfff','\ufffe')
        for(char in characters) {
            val caption="The public studio shares a repair update. A${char}B is recorded in the original notes."
            val a=post(caption);val b=post("<span>$caption</span>",1)
            val plan=collectionPlan(listOf(a,b))
            assertNotNull(plan.fusion)
            assertEquals(1,plan.fusion!!.segments.size);assertTrue(plan.fusion!!.segments.single().shared)
            assertEquals(listOf(a,b),plan.originals)
        }
    }
}
