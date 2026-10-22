package app.antisocial.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextReplyContextTest {
    private class Model:ContextModel {
        override val processingPlan=ContextProcessingPlan(ContextProcessingStyle.FOCUSED,true,1,4,4,4096,"Reply context fixture",true)
        var calls=0
        override suspend fun count(system:String,user:String)=128
        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
            calls++
            return if(system.contains("\"relevant\""))"""{"relevant":true}""" else """{"keep":[1]}"""
        }
    }
    @Test fun identicalShortAnswersKeepTheirDifferentParentQuestionsAndSpeakers():Unit=runBlocking {
        val model=Model();val engine=ContextEngine(model,"reply-context",{if(it=="a")"Repair volunteer"else "Jamie"})
        val docs=listOf(ContextDocument("a","r1","No.",metadata=mapOf("contextParent" to "charging")),ContextDocument("b","r1","No.",metadata=mapOf("contextParent" to "replacement")))
        val first="Is it safe to charge a swollen battery?";val second="Did you replace the entire battery?"
        val backgrounds=mapOf("a" to ContextBackground(reply=ContextBackgroundSource(first,"Alex")),"b" to ContextBackground(reply=ContextBackgroundSource(second,"Sam")))
        val reviewed=engine.process(docs,backgrounds=backgrounds)
        val brief=engine.summarizeResult(reviewed.findings).text
        assertTrue("A short answer needs the question it answers",brief.contains(first))
        assertTrue(brief.contains(second));assertTrue(brief.contains("Repair volunteer"));assertTrue(brief.contains("Jamie"))
        assertEquals(2,Regex("No\\.").findAll(brief).count())
    }

    @Test fun aMissingParentStaysExplicitInsteadOfGivingADeicticReplyAnInventedReferent():Unit=runBlocking {
        val model=Model();val engine=ContextEngine(model,"missing-context",{"Reader"})
        val doc=ContextDocument("child","r1","That changed this morning.",metadata=mapOf("contextParentUnavailable" to "true"))
        val result=engine.process(listOf(doc),backgrounds=mapOf(doc.id to ContextBackground(reply=ContextBackgroundSource("",unavailable=true))))
        val brief=engine.summarizeResult(result.findings).text
        assertTrue("The missing parent must remain visible to the reader",brief.contains("unavailable",true))
        assertTrue(brief.contains(doc.text))
    }
    @Test fun reviewedQuestionsKeepTheirActualClosedAnswersWithoutAnotherModelCall():Unit=runBlocking {
        val model=Model();val engine=ContextEngine(model,"closed-answer",{it})
        val question=ContextDocument("question","r1","Did the workshop replace the entire battery?",kind="comment")
        val first=engine.process(listOf(question));val before=model.calls
        val answer=ContextDocument("answer","r1","No.",metadata=mapOf("contextParent" to "platform-question-alias"))
        val background=ContextBackground(reply=ContextBackgroundSource(question.text,"Alex",sourceId=question.id))
        val second=engine.process(listOf(answer),first.findings,backgrounds=mapOf(answer.id to background))
        assertEquals(before,model.calls)
        val brief=engine.summarizeResult(second.findings)
        assertTrue(brief.text.contains(question.text));assertTrue(brief.text.contains("answer: No."))
        assertEquals(1,Regex(Regex.escape(question.text)).findAll(brief.text).count())
        assertTrue("The question is a real source receipt",question.id in brief.sources)
    }
    @Test fun contextReferencesShareOneFrameAndLongContributionsKeepOneTextBlock() {
        val parent=ContextBackground(reply=ContextBackgroundSource("Did the repair require replacing the whole battery?","Alex",sourceId="parent"))
        val frame=ContextReplyContext.from(parent)!!
        val registry=ContextReplyContexts.collect(mapOf("a" to frame,"b" to frame))
        assertEquals(1,registry.frames.size);assertSame(registry.source("a"),registry.source("b"))
        val quote="A complete independent explanation "+"documents practical workshop repair details ".repeat(15)
        val finding=ContextFinding("long",quote,"context",listOf("a","b"),mapOf("a" to quote,"b" to quote))
        assertEquals(1,ContextContributions.groups(listOf(finding),registry::source).size)
        assertNull(ContextContributions.groups(listOf(finding),registry::source).single().reply)
    }
    @Test fun partialAndUncertainParentsRemainQualifiedAndUnicodeSafe() {
        val frame=ContextReplyContext.from(ContextBackground(reply=ContextBackgroundSource("a".repeat(639)+"😀 additional words",uncertain=true)))!!
        assertTrue(frame.partial);assertFalse(frame.text.last().isHighSurrogate())
        val finding=ContextFinding("reply","No.","answer",listOf("child"),mapOf("child" to "No."))
        val rendered=ContextContributions.render(ContextContributions.groups(listOf(finding)){frame}) {"Reader"}
        assertTrue(rendered.contains("partial"));assertTrue(rendered.contains("uncertain"))
    }
    @Test fun declaredMissingParentsCannotBeMergedIntoAnUnrelatedAnswer():Unit=runBlocking {
        val model=Model();val engine=ContextEngine(model,"missing-keys",{it})
        val documents=listOf("a","b").map {ContextDocument(it,"r1","No.",metadata=mapOf("contextParent" to "unknown-$it"))}
        val result=engine.process(documents)
        val brief=engine.summarizeResult(result.findings)
        assertEquals(2,Regex("No\\.").findAll(brief.text).count())
        assertTrue(brief.text.contains("unavailable"));assertEquals(listOf("a","b"),brief.sources)
    }

    @Test fun aSingleParaphrasedFindingCannotBypassItsParentFrame():Unit=runBlocking {
        val model=Model();val frame=ContextReplyContext.from(ContextBackground(reply=ContextBackgroundSource("Did you use the rear ramp yourself?","Access reader")))!!
        val engine=ContextEngine(model,"paraphrased-reply",{"Eden"},{emptyList()},{null},sourceReplyContext={frame})
        val finding=ContextFinding("paraphrase","Eden reports using it in person.","experience",listOf("child"),mapOf("child" to "I used it myself."))
        val summary=engine.summarizeResult(listOf(finding))
        assertTrue(summary.text.contains(frame.text));assertTrue(summary.text.contains(finding.text));assertEquals(0,model.calls)
    }

    @Test fun aTranslatedQuestionUsesItsOwnEnglishQuoteForTheClosedAnswerPath():Unit=runBlocking {
        val model=Model();val engine=ContextEngine(model,"translated-question",{it})
        val original="¿Cambiaste toda la batería?";val english="Did you replace the entire battery?"
        val parent=ContextDocument("translated-parent","r1",original,kind="comment")
        val reading=ContextReadingText(ContextReadingText.digest(original),english,listOf(ContextReadingSpan(0,original.length,0,english.length,"es",true)),"fixture-es")
        val first=engine.process(listOf(parent),readings=mapOf(parent.id to ContextReadingInput(reading,"")))
        val before=model.calls
        assertEquals(original,first.findings.single().quotes[parent.id]);assertEquals(english,first.findings.single().readingQuotes!![parent.id])
        val child=ContextDocument("reply","r1","No.",metadata=mapOf("contextParent" to parent.id))
        val result=engine.process(listOf(child),first.findings,backgrounds=mapOf(child.id to ContextBackground(reply=ContextBackgroundSource(english,"Alex",sourceId=parent.id))))
        assertEquals("An already reviewed translated question must not need another decision",before,model.calls)
        assertTrue(engine.summarizeResult(result.findings).text.contains("reply: No."))
    }

}
