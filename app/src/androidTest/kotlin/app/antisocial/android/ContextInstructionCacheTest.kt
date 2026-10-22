/* AntiSocial, GPL-3.0-or-later. Synthetic acquired-model fixture on isolated test devices only. */
package app.antisocial.android

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContextInstructionCacheTest {
    @Test fun compiledInstructionsReuseStateWithoutRetainingSourceTokens(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalAiModels")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name=args.getString("antisocialContextPhoneModel") ?: "Ternary-Bonsai-2-2B-PQ2_0.gguf"
        require(name in setOf("Ternary-Bonsai-2-2B-PQ2_0.gguf","Ternary-Bonsai-2-4B-PQ2_0.gguf"))
        val file=File(context.filesDir,"local-ai/test/$name")
        assumeTrue("Stage the acquired local model",file.isFile)
        var system="";var user=""
        val capture=object: ContextModel {
            override val contextTokens=2048
            override val maxOutputTokens=256
            override suspend fun count(system: String,user: String)=(system.length+user.length+3)/4+16
            override suspend fun generate(instructions: String,body: String,maxTokens: Int): String {
                system=instructions;user=body
                return """{"status":"no_signal","findings":[]}"""
            }
        }
        ContextEngine(capture).process(listOf(ContextDocument("correction","1",
            "The service manual specifies a 56 Wh replacement battery, not 65 Wh.",author="Repair technician",kind="comment",
            parentText="The workshop announced a replacement laptop battery with a 65 Wh capacity.")))
        assertTrue(ContextEngine.isReusableInstruction(system))
        val other=JsonParser.parseString(user).asJsonObject.apply {
            addProperty("text","The service manual specifies a 72 Wh replacement battery, not 65 Wh.")
        }.toString()
        val client=LocalAiClient(context)
        val evidence=mutableListOf<Map<String,Any>>()
        fun input(body: String,enabled: Boolean,instructions: String=system)=Bundle().apply {
            putString("kind","text");putString("path",file.path);putString("backend","cpu")
            putString("system",instructions);putString("user",body);putInt("maxTokens",256)
            putBoolean("jsonMode",true);putBoolean("instructionCache",enabled)
        }
        suspend fun request(label: String,body: String,enabled: Boolean,instructions: String=system): Pair<String,JsonObject> {
            val result=withTimeout(240_000){client.request(AiIpc.TEXT,input(body,enabled,instructions))}
            val text=result.getString("text")!!
            val diagnostics=JsonParser.parseString(result.getString("diagnostics")).asJsonObject
            evidence+=mapOf("label" to label,"output" to text,"diagnostics" to diagnostics)
            assertTrue(diagnostics["instruction_cache_bytes"].asLong<=diagnostics["instruction_cache_budget"].asLong)
            return text to diagnostics
        }
        try {
            val cold=request("A-cold",user,false)
            val first=request("A-cache-miss",user,true)
            assertEquals(cold.first,first.first)
            assertFalse(first.second["instruction_cache_hit"].asBoolean)
            assertTrue("The test device needs sufficient RAM for this snapshot",first.second["instruction_cache_bytes"].asLong>0)
            val warm=request("B-cache-hit",other,true)
            val metrics=warm.second
            assertTrue(metrics["instruction_cache_hit"].asBoolean)
            assertTrue(metrics["instruction_prefix_tokens"].asInt>0)
            assertEquals(metrics["input_tokens"].asInt-metrics["instruction_prefix_tokens"].asInt,metrics["prefilled_tokens"].asInt)
            assertTrue("The current source must replace the earlier source",warm.first.contains("72"))
            assertFalse(warm.first.contains("56"))
            val coldOther=request("B-cold-and-clear",other,false)
            assertEquals(coldOther.first,warm.first)
            assertEquals(0L,coldOther.second["instruction_cache_bytes"].asLong)
            // Even a requesting caller cannot cache an altered system prompt.
            val denied=request("altered-instruction-denied",user,true,system+" ")
            assertEquals(0L,denied.second["instruction_cache_budget"].asLong)
            assertEquals(0L,denied.second["instruction_cache_bytes"].asLong)
            val primed=request("A-reprimed",user,true)
            assertEquals(cold.first,primed.first)
            val cancelled=launch {client.request(AiIpc.TEXT,input(other,true))}
            delay(900);cancelled.cancelAndJoin()
            var recovered: Pair<String,JsonObject>?=null
            withTimeout(240_000) {
                while(recovered==null) {
                    try {recovered=request("A-after-cancellation",user,true)}
                    catch(e: IllegalStateException) {if(!e.message.orEmpty().contains("busy"))throw e;delay(200)}
                }
            }
            assertEquals(cold.first,recovered!!.first)
            assertTrue(recovered!!.second["instruction_cache_hit"].asBoolean)
            File(context.filesDir,"context-instruction-cache-evidence.json").writeText(Gson().toJson(mapOf("model" to name,"runs" to evidence)))
        } finally {client.release()}
    }
}
