package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class ContextDetailRetentionTest {
    @Test fun limitedMeasurementsAndPublishedConditionsGetOneNarrowReviewAndDurableReuse()=runBlocking {
        for((subject,text) in listOf(
            "A market reports a power cut." to "I measured the freezer at -6 C at 10:20. I did not inspect the refrigerated cabinet.",
            "A release announces a fix for spreadsheet export." to "We exported 120 rows using version 4.1 and the final column was missing. We have not tested other versions.",
            "A hospital announces normal refrigerator operation." to "At 08:15 I read 11 C on the ward refrigerator. The thermometer was uncalibrated, so I cannot confirm the true temperature.",
            "A shop announces a new returns policy." to "The updated terms allow returns within 14 days only for unopened boxes. Opened batteries are excluded.")) {
            var details=0;var calls=0
            val model=object:ContextModel {
                override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true)
                override val contextTokens=2048
                override suspend fun count(system:String,user:String)=(system.length+user.length)/4+32
                override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                    calls++;assertTrue(count(system,user)+maxTokens+32<=contextTokens)
                    val input=JsonParser.parseString(user).asJsonObject
                    return when {
                        input["topic_review"]?.asBoolean==true->"{\"related\":true}"
                        input["detail_review"]?.asBoolean==true->{details++;assertEquals(text,input["text"].asString);"""{"has_detail":true}"""}
                        input.has("items")->"""{"reviews":[{"id":1,"on_topic":true,"adds_information":false}]}"""
                        else->com.google.gson.Gson().toJson(mapOf("text" to text))
                    }
                }
            }
            val finding=ContextFinding("f",text,"context",listOf("report"),mapOf("report" to text))
            val engine=ContextEngine(model,"detail-test",{"Reader"},{emptyList()},{ContextEvidenceScope(ContextTextObservation.COMMENT)},readerBrief=true,readerSubject={subject})
            val result=engine.summarizeResult(listOf(finding))
            assertEquals(text,result.text);assertEquals(1,details)
            val before=calls;engine.summarizeResult(listOf(finding),result.text,result.receipts);assertEquals(before,calls)
        }
    }
    @Test fun aTopicalFailureCannotBeOverriddenByNumbersOrAWeakDetailVote()=runBlocking {
        val text="I measured the mayor's stock price at 6.46 dollars."
        val model=object:ContextModel {
            override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                assertFalse(JsonParser.parseString(user).asJsonObject.has("detail_review"))
                return """{"reviews":[{"id":1,"on_topic":false,"adds_information":true}]}"""
            }
        }
        val finding=ContextFinding("f",text,"context",listOf("report"),mapOf("report" to text))
        val result=ContextEngine(model,"detail-test",{"Reader"},{emptyList()},{ContextEvidenceScope(ContextTextObservation.COMMENT)},readerBrief=true,readerSubject={"The council repairs a footbridge."}).summarizeResult(listOf(finding))
        assertEquals("",result.text)
    }
    @Test fun untestedKeepsTheSpecificNegativeQualificationButTestedDoesNot()=runBlocking {
        val source="We exported 120 rows using version 4.1. We have not tested other versions."
        for((brief,accepted) in listOf("An export of 120 rows used version 4.1; other versions remain untested." to true,
            "An export of 120 rows used version 4.1; other versions were tested." to false)) {
            val model=object:ContextModel {
                override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true)
                override suspend fun count(system:String,user:String)=128
                override suspend fun generate(system:String,user:String,maxTokens:Int):String=if(JsonParser.parseString(user).asJsonObject.has("items"))
                    """{"reviews":[{"id":1,"on_topic":true,"adds_information":true}]}"""else com.google.gson.Gson().toJson(mapOf("text" to brief))
            }
            val task:suspend ()->ContextSummary={ContextEngine(model,readerBrief=true).summarizeResult(listOf(ContextFinding("f",source,"context",listOf("r"),mapOf("r" to source))))}
            if(accepted)assertEquals(brief,task().text)else assertFailsWith<ContextProcessingException>{task()}
        }
    }
    @Test fun longPreparedCommentUsesClosedPassagesAndKeepsTheFollowingLimitation():Unit=runBlocking {
        val observation="We tested the spreadsheet export with 120 rows on version 4.1 and the final column was missing. "+"The test used the same local file and ordinary export settings. ".repeat(7)
        val limitation="We have not tested other versions."
        val text=observation+"\n\n"+limitation+"\n\n"+"General discussion about the release continued. ".repeat(20)
        assertTrue(text.length>1200)
        for(valid in listOf(true,false)) {
            val model=object:ContextModel {
                override val contextTokens=32768
                override val processingPlan=ContextProcessingPlan.SINGLE.copy(preservePreparedContributions=true)
                override suspend fun count(system:String,user:String)=128
                override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                    val data=JsonParser.parseString(user).asJsonObject
                    val first=data["passages"].asJsonArray.first().asJsonObject["text"].asString
                    assertTrue(first.contains(limitation));assertTrue(first.contains("120 rows"))
                    return if(valid)"""{"keep":[1]}"""else """{"keep":[999]}"""
                }
            }
            val execute:suspend ()->ContextBatchResult={ContextEngine(model,readerBrief=true).process(listOf(ContextDocument("report","1",text,kind="comment",metadata=mapOf("readingLanguage" to "en"))))}
            if(valid) {
                val result=execute()
                assertTrue(result.findings.single().text.contains(limitation))
                assertFalse(result.findings.single().text.contains("General discussion"))
            }else {
                assertFailsWith<ContextProcessingException>{execute()}
            }
        }
    }

    @Test fun aTechnicalFindingWithAnInconsistentEvidenceLabelGetsOneCategoryRepair():Unit=runBlocking {
        val fact="On Linux, version 2.8 writes empty PNG files when the output folder has an accented character. Renaming the folder to ASCII avoids it."
        var reviews=0
        val model=object:ContextModel {
            override val contextTokens=32768
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val data=JsonParser.parseString(user).asJsonObject
                return when {
                    data.has("items")&&!data.has("support_review")->"""{"reviews":[{"id":1,"on_topic":true,"adds_information":true}]}"""
                    data.has("support_review")->{reviews++;com.google.gson.Gson().toJson(mapOf("checks" to listOf(mapOf("id" to 1,"basis" to if(reviews==1)"firsthand"else "practical","quote" to fact))))}
                    else->com.google.gson.Gson().toJson(mapOf("text" to fact))
                }
            }
        }
        val result=ContextEngine(model,"basis-test",{ "" },{emptyList()},sourceEvidenceScope={ContextEvidenceScope(ContextTextObservation.COMMENT)},readerBrief=true).summarizeResult(listOf(ContextFinding("r",fact,"context",listOf("report"),mapOf("report" to fact))))
        assertEquals(2,reviews);assertEquals(fact,result.text)
    }
    @Test fun proseRepairUsesTheRejectedDraftAndCannotDeleteItsUsefulMeasurement():Unit=runBlocking {
        val fact="I got 310 photographs from one charge. The screen was disabled."
        for(repair in listOf(true,false)) {
            var writes=0
            val model=object:ContextModel {
                override val contextTokens=32768
                override suspend fun count(system:String,user:String)=128
                override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                    val data=JsonParser.parseString(user).asJsonObject
                    if(data.has("items"))return """{"reviews":[{"id":1,"on_topic":true,"adds_information":true}]}"""
                    writes++
                    if(writes==2)assertTrue(data.has("rejected_draft"))
                    return com.google.gson.Gson().toJson(mapOf("text" to if(repair&&writes==2)"One trial produced 310 photographs from one charge with the screen disabled."else "The camera maker announced longer battery life. A user reported 310 photographs from one charge with the screen disabled."))
                }
            }
            val run:suspend ()->ContextSummary={ContextEngine(model,readerBrief=true,readerSubject={"A camera maker announces longer battery life."}).summarizeResult(listOf(ContextFinding("r",fact,"context",listOf("report"),mapOf("report" to fact))))}
            if(repair)assertTrue(run().text.contains("310"))else assertFailsWith<ContextProcessingException>{run()}
            assertEquals(2,writes)
        }
    }

    @Test fun twoPropertyDetailReviewStillRequiresBothDecisionsAndCachesTheOutcome():Unit=runBlocking {
        val text="I got 310 photographs from one charge with the screen disabled, so this does not establish ordinary battery life."
        for(related in listOf(true,false)) {
            var checks=0
            val model=object:ContextModel {
                override val contextTokens=2048
                override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true,detailReview=ContextDetailReview.TWO_CHECKS)
                override suspend fun count(system:String,user:String)=128
                override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                    val data=JsonParser.parseString(user).asJsonObject
                    if(data.has("items")) {
                        checks++;return """{"reviews":[{"id":1,"on_topic":${if(checks==1)true else related},"adds_information":${checks>1}}]}"""
                    }
                    return com.google.gson.Gson().toJson(mapOf("text" to text))
                }
            }
            val engine=ContextEngine(model,"dual-check",{""},{emptyList()},{ContextEvidenceScope(ContextTextObservation.COMMENT)},readerBrief=true,readerSubject={"A camera maker announces longer battery life."})
            val finding=ContextFinding("r",text,"context",listOf("report"),mapOf("report" to text))
            val result=engine.summarizeResult(listOf(finding))
            assertEquals(if(related)text else "",result.text);assertEquals(2,checks)
            engine.summarizeResult(listOf(finding),result.text,result.receipts);assertEquals(2,checks)
        }
    }

}
