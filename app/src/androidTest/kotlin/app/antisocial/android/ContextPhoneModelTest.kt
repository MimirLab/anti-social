/* AntiSocial, GPL-3.0-or-later. Acquired-model fixture, never a user download. */
package app.antisocial.android

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContextPhoneModelTest {
    @Test fun phonePlanRetainsAnUncertainWitnessWithoutPublishingTheModelsParaphrase(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalAiModels")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.filesDir,"local-ai/test/Ternary-Bonsai-2-2B-PQ2_0.gguf")
        assumeTrue(file.isFile)
        val client=LocalAiClient(context)
        try {
            fun input(system: String="",user: String="")=Bundle().apply {
                putString("kind","text");putString("path",file.path);putString("backend","cpu")
                putString("cacheProfile","pq4_protected_v2");putString("system",system);putString("user",user)
            }
            val diagnostics=client.request(AiIpc.PREPARE,input()).getString("diagnostics")!!
            val capacity=ContextCapabilityParser.nativeRuntime(JsonParser.parseString(diagnostics).asJsonObject,"2B")
            var calls=0
            val raw=mutableListOf<String>()
            val model=object: ContextModel {
                override val contextTokens=capacity.contextTokens
                override val maxOutputTokens=capacity.maxOutputTokens!!
                override val shortDecisionTokens=32
                override val processingPlan=ContextProcessingRouter.select(capacity,true,maxOutputTokens)
                override suspend fun count(system: String,user: String)=client.request(AiIpc.COUNT,input(system,user)).getInt("tokenCount")
                override suspend fun generate(system: String,user: String,maxTokens: Int): String {
                    calls++
                    return client.request(AiIpc.TEXT,input(system,user).apply {putInt("maxTokens",maxTokens);putBoolean("jsonMode",true)}).getString("text")!!.also {raw+=it}
                }
            }
            assertTrue(model.processingPlan.extractiveFindings)
            val text="I was there this morning and it was still taped off. I do not know whether staff reopened it later."
            val source=ContextDocument("witness","1",text,author="A visitor",kind="comment",parentText="The library announced that its lift reopened.")
            val labels=mapOf("witness" to "A visitor","lift-post" to "Library","lift-log" to "Maintenance log","lift-update" to "Maintenance team")
            val engine=ContextEngine(model,"phone-extractive-qualification",sourceLabel={labels[it].orEmpty()})
            val result=withTimeout(300_000){engine.process(listOf(source))}
            assertTrue("Retain the witness's explicit uncertainty",result.findings.any {it.text.contains("I do not know whether staff reopened it later.")})
            assertTrue(result.findings.all {text.contains(it.text)&&it.text==it.quotes[source.id]&&it.sources==listOf(source.id)})
            val before=calls
            engine.process(listOf(source),result.findings,result.outcomes)
            assertEquals(before,calls)
            assertEquals("Only the narrow relevance decision needs generation",1,calls)
            val documents=listOf(source,
                ContextDocument("lift-post","1","The library announced that the lift reopened this morning.",author="Library",kind="post"),
                ContextDocument("lift-log","1","An inspection was recorded at 10:00. The log does not record a reopening time.",author="Maintenance log",kind="article"),
                ContextDocument("lift-update","1","The lift reopened at 15:20 after a second inspection.",author="Maintenance team",kind="post"))
            val expanded=engine.process(documents,result.findings,result.outcomes)
            val summary=engine.summarizeResult(expanded.findings)
            assertEquals("An already compact ledger needs no model rewrite",before,calls)
            assertEquals(4,summary.findings.size);assertEquals(0,summary.levels)
            documents.forEach {assertTrue(summary.text.contains("${it.author}: ${it.text}"))}
            assertFalse(engine.summarizeResult(expanded.findings,summary.text).changed)
            File(context.filesDir,"context-phone-extractive-evidence.json").writeText(Gson().toJson(mapOf("raw" to raw,"findings" to expanded.findings,"brief" to summary.text,"generations" to calls,"plan" to model.processingPlan)))
        } finally {client.release()}
    }

    @Test fun smallNativeWindowExtractsCorrectionsAndDoesNotReprocessItsReceipts(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalAiModels")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val modelName=args.getString("antisocialContextPhoneModel") ?: "Ternary-Bonsai-2-2B-PQ2_0.gguf"
        require(modelName in setOf("Ternary-Bonsai-2-2B-PQ2_0.gguf","Ternary-Bonsai-2-4B-PQ2_0.gguf"))
        val file=File(context.filesDir,"local-ai/test/$modelName")
        assumeTrue("Stage the acquired local model",file.isFile)
        val client=LocalAiClient(context)
        try {
        var generations=0
        var largestInput=0
        val responses=mutableListOf<String>()
        fun input(system: String,user: String)=Bundle().apply {
            putString("kind","text");putString("path",file.path);putString("backend","cpu")
            putString("system",system);putString("user",user)
        }
        val diagnostics=client.request(AiIpc.PREPARE,input("","")).getString("diagnostics")!!
        val capacity=ContextCapabilityParser.nativeRuntime(JsonParser.parseString(diagnostics).asJsonObject,modelName)
        assertEquals(2048,capacity.activeContextTokens)
        val model=object: ContextModel {
            override val contextTokens=capacity.contextTokens
            override val maxOutputTokens=capacity.maxOutputTokens!!
            override suspend fun count(system: String,user: String): Int = client.request(AiIpc.COUNT,input(system,user)).getInt("tokenCount")
            override suspend fun generate(system: String,user: String,maxTokens: Int): String {
                generations++
                val count=count(system,user)
                largestInput=maxOf(count,largestInput)
                check(count+maxTokens+32<=contextTokens){"Generation exceeded the native working window"}
                return client.request(AiIpc.TEXT,input(system,user).apply {putInt("maxTokens",maxTokens);putBoolean("jsonMode",true)}).getString("text")!!.also {responses+=it}
            }
        }
        val correction="You have it backwards, genius. The service manual specifies a 56 Wh replacement battery, not 65 Wh. I fitted this exact part yesterday."
        val documents=listOf(
            ContextDocument("repair-correction","1",correction,author="Repair technician",kind="comment",parentText="The workshop announced a replacement laptop battery with a 65 Wh capacity."),
            ContextDocument("reaction","1","lol",author="Bystander",kind="comment")
        )
        val started=System.currentTimeMillis()
            val engine=ContextEngine(model,"phone-qualification")
            val result=withTimeout(300_000){engine.process(documents)}
            File(context.filesDir,"context-phone-model-attempt.json").writeText(Gson().toJson(mapOf("model" to modelName,"responses" to responses,"findings" to result.findings,"outcomes" to result.outcomes)))
            assertTrue("The tiny model must retain the correction",result.findings.any {it.text.contains("56")&&it.quotes.values.any {quote->quote.contains("56 Wh")&&correction.contains(quote)}})
            assertEquals(1,generations)
            assertTrue(result.outcomes.any {it.documentId=="reaction"&&it.status==ContextUnitStatus.NO_SIGNAL})
            val resumed=engine.process(documents,result.findings,result.outcomes)
            assertEquals(1,generations);assertEquals(result.outcomes,resumed.outcomes)
            assertTrue("Native formatted prompts must reserve output space",largestInput+model.maxOutputTokens+32<=model.contextTokens)
            File(context.filesDir,"context-phone-model-evidence.json").writeText(Gson().toJson(mapOf(
                "model" to modelName,"contextTokens" to model.contextTokens,"largestPromptTokens" to largestInput,
                "generations" to generations,"elapsedMs" to System.currentTimeMillis()-started,
                "findings" to result.findings,"receiptsReused" to true
            )))
        } finally {client.release()}
    }
}
