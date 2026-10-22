package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

/** Acquired-model evidence. Never downloads and never runs on the user's preview. */
@RunWith(AndroidJUnit4::class)
class AiNativeWorkerTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun optIn(){assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLocalAiModels")=="true"&&InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")}
    @Test fun immediateCancellationNeverLeavesModelWorkerPermanentlyBusy()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        assumeTrue(android.os.Build.SUPPORTED_ABIS.first() in setOf("arm64-v8a","x86_64"))
        LocalTextRuntime.installer(context).install()
        val client=LocalAiClient(context)
        try {
            client.request(AiIpc.CAPABILITIES)
            repeat(30) {
                withContext(Dispatchers.Main.immediate) {
                    val request=launch(start=CoroutineStart.UNDISPATCHED){client.request(AiIpc.CAPABILITIES)}
                    request.cancelAndJoin()
                }
                var ready=false
                repeat(30){if(!ready){delay(25);try {val result=client.request(AiIpc.CAPABILITIES);assertNotNull(result.getString("diagnostics"));ready=true}catch(e:IllegalStateException){if(e.message?.contains("busy")!=true)throw e}}}
                assertTrue("Cancelled request must release its queue entry",ready)
            }
        }finally {client.release()}
    }
    @Test fun privateWorkerEmbedsOnCpuInAnotherProcessWithoutStartingAReaderRepository()=runBlocking {
        optIn();val client=LocalAiClient(context)
        try {
            val result=client.request(AiIpc.EMBED,Bundle().apply {putString("kind","embedding");putString("path",File(context.filesDir,"local-ai/test/sse-q4").path);putString("user","A battery battery is not a solar panel. 中文 🙂")})
            assertNotEquals(android.os.Process.myPid(),result.getInt("workerPid"));assertEquals(512,result.getFloatArray("vector")!!.size)
            assertEquals(SseEmbedding.SPACE_ID,result.getString("spaceId"));assertTrue(result.getInt("tokenCount")>5)
        }finally {client.release()}
    }
    @Test fun verifiedBonsaiGeneratesAndCancelsThenStartsAFreshRequestWithoutReasoning()=runBlocking {
        optIn();LocalTextRuntime.installer(context).install();val client=LocalAiClient(context)
        val path=File(context.filesDir,"local-ai/test/Ternary-Bonsai-2-4B-PQ2_0.gguf").path
        fun input(user: String,max: Int=16)=Bundle().apply {putString("kind","text");putString("path",path);putString("backend","cpu");putString("system","Follow instructions directly. Do not include reasoning.");putString("user",user);putInt("maxTokens",max)}
        try {
            val started=android.os.SystemClock.elapsedRealtime()
            val first=withTimeout(180_000){client.request(AiIpc.TEXT,input("The original number was 19. Correction: the number is now 23. Reply only with the corrected number."))}
            assertNotEquals(android.os.Process.myPid(),first.getInt("workerPid"));assertEquals("23",first.getString("text")!!.trim());assertFalse(first.getString("text")!!.contains("<think>"))
            val diagnostics=JSONObject(first.getString("diagnostics")!!)
            assertEquals("cpu",diagnostics.getString("selected_backend"));assertFalse(diagnostics.getBoolean("thinking"))
            val unicode=withTimeout(120_000){client.request(AiIpc.TEXT,input("Asha wrote: 'The location is Montréal 🙂.' Reply only with the location name and emoji."))}.getString("text")!!
            File(context.filesDir,"local-ai/test/bonsai-android-unicode.json").writeText(JSONObject().put("output",unicode).put("first",first.getString("text")).toString())
            // The question asks for a location name, for which Montreal is a
            // valid English spelling. This tests the named source and real
            // supplementary Unicode across JNI, rather than exact prose.
            val city=java.text.Normalizer.normalize(unicode,java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"")
            assertTrue("Unicode answer: $unicode",city.contains("Montreal",ignoreCase=true));assertTrue("Unicode answer: $unicode",unicode.contains("🙂"))
            val cancelled=launch {client.request(AiIpc.TEXT,input("Write a long numbered list with 300 entries about different trees.",512))}
            delay(900);val cancelAt=android.os.SystemClock.elapsedRealtime();cancelled.cancelAndJoin()
            var next: Bundle?=null
            repeat(20){if(next==null){delay(150);try {next=withTimeout(120_000){client.request(AiIpc.TEXT,input("Reply only with READY."))}}catch(e: IllegalStateException){if(e.message?.contains("busy")!=true)throw e}}}
            assertNotNull(next);assertEquals("READY",next!!.getString("text")!!.trim())
            val evidence=JSONObject().apply {put("model","Bonsai 2 4B PQ2_0");put("abi",android.os.Build.SUPPORTED_ABIS.first());put("backend","cpu");put("corrected_number",first.getString("text"));put("unicode",unicode);put("reset",next!!.getString("text"));put("cancel_to_recovered_ms",android.os.SystemClock.elapsedRealtime()-cancelAt);put("total_ms",android.os.SystemClock.elapsedRealtime()-started);put("diagnostics",diagnostics)}
            File(context.filesDir,"local-ai/test/bonsai-android-worker.json").writeText(evidence.toString(2))
        }finally {client.release()}
    }
}
