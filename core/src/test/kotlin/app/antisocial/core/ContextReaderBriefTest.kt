/* AntiSocial, GPL-3.0-or-later. A reader brief must actually condense its evidence. */
package app.antisocial.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextReaderBriefTest {
    // These tests isolate composition/grounding. Admission has its own suite.
    private fun admission(user:String):String? {
        val items=com.google.gson.JsonParser.parseString(user).asJsonObject.getAsJsonArray("items") ?: return null
        return com.google.gson.Gson().toJson(mapOf("reviews" to items.map {mapOf("id" to it.asJsonObject["id"].asInt,"on_topic" to true,"adds_information" to true)}))
    }
    @Test fun aDefinedAcronymRemainsUsableWithAnOrdinaryArticleBeforeIt():Unit=runBlocking {
        val source="Multi-Office Accelerator Team (MOAT) improves scientific work."
        val finding=ContextFinding("defined",source,"brief",listOf("source"),mapOf("source" to source))
        val supported="The Multi-Office Accelerator Team (MOAT) improves scientific work."
        var calls=0
        val model=object:ContextModel {
            override val contextTokens=8192
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String { admission(user)?.let {return it};calls++;return com.google.gson.Gson().toJson(mapOf("text" to supported))}
        }
        assertEquals(supported,ContextEngine(model,"reader",readerBrief=true).summarizeResult(listOf(finding)).text)
        assertEquals(1,calls)
    }
    @Test fun anUndefinedAcronymCannotBecomeAnInventedDefinition():Unit=runBlocking {
        val source="Advances in SI autonomy accelerate hypothesis formation and testing. The Multi-Office Accelerator Team (MOAT) improves scientific work."
        val finding=ContextFinding("acronyms",source,"brief",listOf("source"),mapOf("source" to source))
        val supported="SI autonomy accelerates hypothesis formation and testing, alongside work by the Multi-Office Accelerator Team (MOAT)."
        var calls=0
        val model=object:ContextModel {
            override val contextTokens=8192
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String { admission(user)?.let {return it};
                calls++
                return com.google.gson.Gson().toJson(mapOf("text" to if(calls==1)"Scientific instrument (SI) autonomy accelerates testing."else supported))
            }
        }
        assertEquals(supported,ContextEngine(model,"reader",readerBrief=true).summarizeResult(listOf(finding)).text)
        assertEquals(2,calls)
    }
    @Test fun aNameActuallySuppliedByTheSourceDoesNotTriggerTheGuard():Unit=runBlocking {
        val source="Bill Nelson spoke about the nuclear propulsion proposal."
        val finding=ContextFinding("named",source,"brief",listOf("source"),mapOf("source" to source))
        var calls=0
        val model=object:ContextModel {
            override val contextTokens=8192
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String { admission(user)?.let {return it};calls++;return com.google.gson.Gson().toJson(mapOf("text" to source))}
        }
        assertEquals(source,ContextEngine(model,"reader",readerBrief=true).summarizeResult(listOf(finding)).text)
        assertEquals(1,calls)
    }
    @Test fun aRememberedOfficialsNameIsRejectedBeforeTheReaderSeesIt():Unit=runBlocking {
        val source="A new NASA-ENERGY agreement aims to accelerate nuclear power and propulsion for space."
        val finding=ContextFinding("space",source,"brief",listOf("source"),mapOf("source" to source))
        val supported="The NASA-ENERGY agreement aims to accelerate nuclear power and propulsion for space."
        var calls=0
        val model=object:ContextModel {
            override val contextTokens=8192
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String { admission(user)?.let {return it};
                calls++
                return com.google.gson.Gson().toJson(mapOf("text" to if(calls==1)"NASA Administrator Bill Nelson announced a nuclear propulsion agreement."else supported))
            }
        }
        val result=ContextEngine(model,"reader",readerBrief=true).summarizeResult(listOf(finding))
        assertEquals(supported,result.text);assertEquals(2,calls)
    }
    @Test fun incompleteUnconfirmedAndCommentSourcesStillReceiveTheirOwnReview():Unit=runBlocking {
        val text="The campus funding proposal includes a new repair laboratory."
        val docs=listOf(
            ContextDocument("partial","v1",text,kind="article",metadata=mapOf("readingLanguage" to "en","truncated" to "true")),
            ContextDocument("unknown","v1",text,kind="article"),
            ContextDocument("reply","v1",text,kind="comment",metadata=mapOf("readingLanguage" to "en")),
        )
        var calls=0
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String { admission(user)?.let {return it};
                calls++
                if(com.google.gson.JsonParser.parseString(user).asJsonObject.has("passages"))return """{"keep":[1]}"""
                return com.google.gson.Gson().toJson(mapOf("status" to "signal","findings" to listOf(mapOf("text" to "The proposal includes a repair laboratory.","kind" to "context","quote" to text))))
            }
        }
        for(doc in docs) {
            val before=calls
            val result=ContextEngine(model,"reader",readerBrief=true).process(listOf(doc))
            assertEquals(before+1,calls)
            assertEquals(if(doc.kind=="article")text else "The proposal includes a repair laboratory.",result.findings.single().text)
        }
    }
    @Test fun shortCompleteEnglishLinkedSourceReachesCompositionWithoutAParaphraseOrComparison():Unit=runBlocking {
        val text="The new campus is organized around national missions rather than traditional academic departments."
        val source=ContextDocument("linked-post","v1",text,kind="article",metadata=mapOf("readingLanguage" to "en"))
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String=error("The complete short source needs no separate model interpretation")
        }
        val engine=ContextEngine(model,"reader",readerBrief=true)
        val result=engine.process(listOf(source))
        assertEquals(text,result.findings.single().text)
        assertEquals(text,result.findings.single().quotes[source.id])
        val resumed=engine.process(listOf(source),result.findings,result.outcomes)
        assertEquals(result.findings,resumed.findings)
    }
    @Test fun unknownRemoteWindowCanComposeOneDatedLinkedFindingWithinItsActualByteBudget():Unit=runBlocking {
        val text="The guide specifies a T5 screwdriver and says to disconnect the battery first."
        val finding=ContextFinding("one",text,"brief",listOf("guide"),mapOf("guide" to text))
        var calls=0
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=system.toByteArray().size+user.toByteArray().size+128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String { admission(user)?.let {return it};
                assertTrue(count(system,user)+maxTokens+32<=contextTokens)
                assertFalse("Posting timestamps must not become invented event dates",user.contains("2026-10-20T12:00:00Z"))
                calls++;return com.google.gson.Gson().toJson(mapOf("text" to text))
            }
        }
        val engine=ContextEngine(model,"unknown",{"Service guide"},
            {listOf(ContextSourcePublication("Service guide",ContextPublicationTime.parse("2026-10-20T12:00:00Z")))},
            {ContextEvidenceScope(ContextTextObservation.PAGE)},readerBrief=true)
        assertEquals(text,engine.summarizeResult(listOf(finding)).text)
        assertEquals(1,calls)
    }
    @Test fun microReaderBriefCondensesSelectedPassagesInsteadOfJoiningEveryExtract():Unit=runBlocking {
        val texts=listOf("The workshop opens Friday and accepts laptops with loose battery connectors.","Volunteers supply the correct screwdriver and inspect the connector before replacing a battery.","The guide instructs visitors to disconnect the battery before touching a damaged cable.")
        val findings=texts.mapIndexed {i,t->ContextFinding("f$i",t,"brief",listOf("s$i"),mapOf("s$i" to t))}
        var calls=0
        val brief="Friday’s laptop workshop supplies tools and checks loose connectors before recommending battery replacement. Disconnect the battery before touching damaged cables."
        val model=object:ContextModel {
            override val contextTokens=8192
            override val maxOutputTokens=512
            override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String { admission(user)?.let {return it};calls++;return com.google.gson.Gson().toJson(mapOf("text" to brief))}
        }
        val engine=ContextEngine(model,"reader",{"Workshop"},readerBrief=true)
        val result=engine.summarizeResult(findings)
        assertEquals(brief,result.text);assertEquals(1,calls)
        assertEquals(findings.map {it.text to it.quotes},result.findings.map {it.text to it.quotes})
        val reused=engine.summarizeResult(findings,result.text,result.receipts)
        assertEquals(brief,reused.text);assertEquals(1,calls)
        assertTrue(result.text.length<texts.sumOf {it.length})
    }
}
