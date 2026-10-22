package app.antisocial.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextCaptionTimecodeTest {
    private val caption="[0:00] Disconnect power before opening the cover.\n"+"[0:08] Compare the connector label before fitting a part.\n".repeat(24)
    private val guide="The guide discusses a particular connector inspection. ".repeat(24)
    private suspend fun summarize(extra:String="",medium:String="Published captions"):ContextSummary {
        val docs=listOf(ContextDocument("caption","1",caption+extra,author="Captions",kind="article",metadata=mapOf("medium" to medium,"timestamps" to "true")),ContextDocument("guide","1",guide,author="Guide",kind="article"))
        val model=object:ContextModel {
            override val contextTokens=8192;override val maxOutputTokens=512
            override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int)="""{"text":"The captions instruct readers to disconnect power and compare the connector label before fitting a part. The guide discusses the connector inspection."}"""
        }
        val data=docs.map {ContextFinding(it.id,it.text,"brief",listOf(it.id),mapOf(it.id to it.text))}
        assertTrue(data.all {it.text.length<=1600});assertTrue(data.sumOf {it.text.length}>2400)
        return ContextEngine(model,"captions",{id->docs.single {it.id==id}.author},{emptyList()},{id->ContextEvidenceScope.from(docs.single {it.id==id})}).summarizeResult(data)
    }
    @Test fun mediaCuePositionsAreNotContentCorrectionValues():Unit=runBlocking {
        val result=summarize()
        assertTrue(result.text.contains("disconnect power"));assertEquals(2,result.findings.size)
    }
    @Test fun statedQuantitiesRemainProtectedInsideTimecodedCaptions():Unit=runBlocking {
        try {summarize("[0:10] The capacity is 56 Wh, not 65 Wh.\n");fail("A real correction must remain protected")}
        catch(expected:ContextProcessingException){assertTrue(expected.message!!.contains("correction"))}
    }
    @Test fun aClockTimeStatedInTheSpeechRemainsAContentValue():Unit=runBlocking {
        try {summarize("[0:15] The appointment starts at 10:00.\n");fail("A stated event time cannot be stripped as a cue")}
        catch(expected:ContextProcessingException){assertTrue(expected.message!!.contains("correction"))}
    }
    @Test fun sourceClockTextCannotOptOutOfChecksByClaimingTimecodesAlone():Unit=runBlocking {
        try {summarize(medium="Ordinary source text");fail("Only known media cue formats can shed locator numbers")}
        catch(expected:ContextProcessingException){assertTrue(expected.message!!.contains("correction"))}
    }
}
