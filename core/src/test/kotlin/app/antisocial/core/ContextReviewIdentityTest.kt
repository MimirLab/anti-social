package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextReviewIdentityTest {
    private class Model:ContextModel {
        override val contextTokens=8192
        override val processingPlan=ContextProcessingPlan.SINGLE.copy(summaryGroupLimit=2)
        var summaries=0
        val summaryInputs=mutableListOf<String>()
        override suspend fun count(system:String,user:String)=128
        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
            val input=JsonParser.parseString(user).asJsonObject
            if(input.has("source_id")) {
                val quote=input["text"].asString.substringBefore('.')+"."
                return JsonObject().apply {addProperty("status","signal");add("findings",JsonArray().apply {add(JsonObject().apply {
                    addProperty("text",quote);addProperty("quote",quote);addProperty("kind","context")
                })})}.toString()
            }
            summaries++;summaryInputs+=user
            return """{"text":"The workshops report inspections with their stated limits."}"""
        }
    }
    private fun sources(kind:String="article")=listOf("first","second","third","fourth").map {name->
        ContextDocument(name,"r1","The $name workshop reports a connector inspection. The original report describes a complete test.",author="Workshop $name",kind=kind)
    }

    @Test fun aNewSourceReviewCannotReuseOldCompactionJustBecauseItsSelectedQuoteIsUnchanged():Unit=runBlocking {
        val model=Model();val engine=ContextEngine(model,"review-source");val docs=sources()
        val first=engine.process(docs);val publication=engine.summarizeResult(first.findings);val count=model.summaries
        val next=docs.toMutableList().apply {this[0]=this[0].copy(revision="r2",text=this[0].text.substringBefore('.')+". The revised report limits this to a disconnected bench sample.")}
        val reviewed=engine.process(next,first.findings,first.outcomes)
        assertEquals("Selected words and quotes deliberately stay identical",first.findings,reviewed.findings)
        val updated=engine.summarizeResult(reviewed.findings,publication.text,publication.receipts)
        assertTrue("Composition must reconsider a changed supporting review",model.summaries>count)
        assertEquals("The unaffected branch should remain reusable",2,model.summaries-count)
        assertNotEquals(publication.receipts.last().id,updated.receipts.last().id)
        val after=model.summaries
        engine.summarizeResult(reviewed.findings,updated.text,updated.receipts);assertEquals(after,model.summaries)
    }

    @Test fun aChangedReplyParentCannotReuseAChildsOldCompactionWithTheSameWords():Unit=runBlocking {
        val model=Model();val engine=ContextEngine(model,"review-parent")
        // Force the hierarchical path: short literal replies now preserve
        // their parent deterministically and need no model compaction.
        val docs=sources("comment").map {it.copy(text=it.text.substringBefore('.')+" "+"documented inspection details ".repeat(20)+". The original report describes a complete test.")}
        val backgrounds=docs.associate {it.id to ContextBackground(reply=ContextBackgroundSource("The inspection covered production equipment."))}
        val first=engine.process(docs,backgrounds=backgrounds);val publication=engine.summarizeResult(first.findings);val count=model.summaries
        val next=backgrounds+mapOf(docs.first().id to ContextBackground(reply=ContextBackgroundSource("The inspection covered only an isolated prototype.")))
        val reviewed=engine.process(docs,first.findings,first.outcomes,backgrounds=next)
        assertEquals(first.findings,reviewed.findings)
        val updated=engine.summarizeResult(reviewed.findings,publication.text,publication.receipts)
        assertTrue("The parent context used by a review is interpretive evidence",model.summaries>count)
        assertNotEquals(publication.receipts.last().id,updated.receipts.last().id)
        assertTrue(model.summaryInputs.any {it.contains("production equipment")})
        assertTrue(model.summaryInputs.last().contains("isolated prototype"))
    }

    @Test fun emptyReviewsDoNotChangePublishedMeaningAndReviewOrderIsNotNewInformation():Unit=runBlocking {
        val model=Model();val engine=ContextEngine(model,"review-noise")
        val first=engine.process(sources());val publication=engine.summarizeResult(first.findings);val count=model.summaries
        val next=engine.process(listOf(ContextDocument("reaction","r1","Thanks!")),first.findings)
        val unchanged=engine.summarizeResult(next.findings,publication.text,publication.receipts)
        assertEquals(count,model.summaries);assertFalse(unchanged.changed)
        assertEquals(publication.receipts,unchanged.receipts)
        assertEquals(ContextReviewIdentity.of(listOf("second","first","first")),ContextReviewIdentity.of(listOf("first","second")))
        assertNotEquals(ContextReviewIdentity.of(listOf("a","bc")),ContextReviewIdentity.of(listOf("ab","c")))
        assertNull(ContextReviewIdentity.of(emptyList()))
    }
}
