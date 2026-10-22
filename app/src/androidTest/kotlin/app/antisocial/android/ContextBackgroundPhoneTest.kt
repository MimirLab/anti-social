/* AntiSocial, GPL-3.0-or-later. Real acquired models, isolated development devices only. */
package app.antisocial.android

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContextBackgroundPhoneTest {
    @Test fun actualNativePlanningKeepsBothParentsAndResumesOnlyTheMatchingBackground(): Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalAiModels")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name=args.getString("antisocialContextPhoneModel") ?: "Ternary-Bonsai-2-2B-PQ2_0.gguf"
        require(name in setOf("Ternary-Bonsai-2-2B-PQ2_0.gguf","Ternary-Bonsai-2-4B-PQ2_0.gguf"))
        val file=File(context.filesDir,"local-ai/test/$name");assumeTrue(file.isFile)
        val client=LocalAiClient(context);val evidence=mutableListOf<Map<String,Any>>()
        fun input(system: String="",user: String="")=Bundle().apply {
            putString("kind","text");putString("path",file.path);putString("backend","cpu");putString("system",system);putString("user",user)
        }
        try {
            val report=ContextCapabilityParser.nativeRuntime(JsonParser.parseString(client.request(AiIpc.PREPARE,input()).getString("diagnostics")).asJsonObject,name)
            val model=object:ContextModel {
                override val contextTokens=report.contextTokens
                override val maxOutputTokens=report.maxOutputTokens!!
                override suspend fun count(system: String,user: String)=client.request(AiIpc.COUNT,input(system,user)).getInt("tokenCount")
                override suspend fun generate(system: String,user: String,maxTokens: Int): String {
                    val tokens=count(system,user)
                    assertTrue(tokens+maxTokens+32<=contextTokens)
                    val prompt=JsonParser.parseString(user).asJsonObject
                    assertTrue(prompt["parent_context"].asString.contains("Subject post"))
                    assertTrue(prompt["parent_context"].asString.contains("Reply parent"))
                    assertTrue(prompt["parent_context"].asString.contains("Which battery does the manual specify?"))
                    assertFalse(prompt.has("parent_truncated"))
                    assertTrue(prompt["parent_context"].asString.contains("Only the revised connector is compatible."))
                    val result=withTimeout(300_000){client.request(AiIpc.TEXT,input(system,user).apply {
                        putInt("maxTokens",maxTokens);putBoolean("jsonMode",true);putBoolean("instructionCache",true)
                    })}
                    val text=result.getString("text")!!
                    evidence+=mapOf("prompt" to prompt,"tokens" to tokens,"maxTokens" to maxTokens,"response" to text,
                        "diagnostics" to JsonParser.parseString(result.getString("diagnostics")))
                    return text
                }
            }
            val doc=ContextDocument("background-correction","r1","The service manual specifies a 56 Wh replacement battery, not 65 Wh.",author="Repair technician")
            val longPost="The workshop announced a replacement laptop battery with a 65 Wh capacity.\n"+"A separate routine checklist accompanies the announcement.\n".repeat(30)+"Only the revised connector is compatible."
            val parent=ContextBackgroundSource("Which battery does the manual specify?","Workshop reader")
            val background=ContextBackground(ContextBackgroundSource(longPost,"Workshop"),parent)
            val engine=ContextEngine(model,"native-background-qualification")
            val first=engine.process(listOf(doc),backgrounds=mapOf(doc.id to background))
            File(context.filesDir,"context-background-model-attempt.json").writeText(Gson().toJson(evidence))
            assertTrue(first.findings.any {it.text.contains("56")&&it.quotes.values.any {q->q.contains("56 Wh")&&doc.text.contains(q)}})
            engine.process(listOf(doc),completed=first.outcomes,backgrounds=mapOf(doc.id to background))
            assertEquals(1,evidence.size)
            val changed=ContextBackground(ContextBackgroundSource(longPost+"A later qualification was added beyond the former excerpt.","Workshop"),parent)
            val next=engine.process(listOf(doc),completed=first.outcomes,backgrounds=mapOf(doc.id to changed))
            assertEquals(2,evidence.size);assertNotEquals(first.outcomes.single().unitId,next.outcomes.single().unitId)
            assertTrue(next.findings.any {it.text.contains("56")})
            File(context.filesDir,"context-background-model-evidence.json").writeText(Gson().toJson(mapOf("model" to name,"runs" to evidence,"matchingBackgroundReused" to true,"changedBackgroundReprocessed" to true)))
        }finally {client.release()}
    }
}
