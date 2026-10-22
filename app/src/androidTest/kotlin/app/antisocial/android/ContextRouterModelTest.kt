/* AntiSocial, GPL-3.0-or-later. Opt-in qualification; only synthetic public-like material. */
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
class ContextRouterModelTest {
    private val documents = listOf(
        ContextDocument("rude-correction", "1", "You missed the important bit. The service manual specifies 56 Wh, not 65 Wh.", author = "Repair technician", parentText = "The guide recommends a 65 Wh battery for a laptop repair."),
        ContextDocument("noise", "1", "That author is an idiot and their logo is ugly.", author = "Bystander", parentText = "The guide recommends a 65 Wh battery for a laptop repair."),
        ContextDocument("question", "1", "¿Sirve también para el modelo de 2021?", author = "Reader", parentText = "A replacement battery fits the 2022 laptop; compatibility with the 2021 laptop is not known.")
    )
    @Test fun acquiredMicroModelUsesFocusedTasksAndRetainsAnUnansweredQuestion(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalAiModels") == "true" && args.getString("antisocialIsolatedFixtureDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = args.getString("antisocialContextPhoneModel") ?: "Ternary-Bonsai-2-2B-PQ2_0.gguf"
        require(name in setOf("Ternary-Bonsai-2-2B-PQ2_0.gguf", "Ternary-Bonsai-2-4B-PQ2_0.gguf"))
        val file = File(context.filesDir, "local-ai/test/$name"); assumeTrue(file.isFile)
        val client = LocalAiClient(context)
        val evidence = mutableListOf<Map<String, Any>>()
        fun input(system: String = "", user: String = "") = Bundle().apply {
            putString("kind", "text"); putString("path", file.path); putString("backend", "cpu"); putString("system", system); putString("user", user)
        }
        try {
            val report = ContextCapabilityParser.nativeRuntime(JsonParser.parseString(client.request(AiIpc.PREPARE, input()).getString("diagnostics")).asJsonObject, name)
            assertNotNull(report.parameterCount); assertNotNull(report.modelBytes)
            val model = object : ContextModel {
                override val contextTokens = report.contextTokens
                override val maxOutputTokens = report.maxOutputTokens!!
                override val shortDecisionTokens = 32
                override val processingPlan = ContextProcessingRouter.select(report, true, maxOutputTokens)
                override suspend fun count(system: String, user: String) = client.request(AiIpc.COUNT, input(system, user)).getInt("tokenCount")
                override suspend fun generate(system: String, user: String, maxTokens: Int): String {
                    assertTrue(count(system, user) + maxTokens + 32 <= contextTokens)
                    val result = withTimeout(300_000) { client.request(AiIpc.TEXT, input(system, user).apply {
                        putInt("maxTokens", maxTokens); putBoolean("jsonMode", true); putBoolean("instructionCache", true)
                    }) }
                    val text = result.getString("text")!!
                    evidence += mapOf("prompt" to JsonParser.parseString(user), "output" to text, "maxTokens" to maxTokens,
                        "diagnostics" to JsonParser.parseString(result.getString("diagnostics")))
                    File(context.filesDir, "context-router-phone-attempt.json").writeText(Gson().toJson(evidence))
                    return text
                }
            }
            assertEquals(ContextProcessingStyle.FOCUSED, model.processingPlan.style)
            val engine = ContextEngine(model)
            val first = engine.process(documents)
            assertEquals(ContextUnitStatus.NO_SIGNAL, first.outcomes.single { it.documentId == "noise" }.status)
            assertTrue(first.findings.any { "rude-correction" in it.sources && it.text.contains("56") && it.quotes["rude-correction"]!!.contains("56 Wh") })
            val question = first.findings.single { "question" in it.sources }
            assertEquals("question", question.kind); assertEquals(documents.last().text, question.text)
            val calls = evidence.size
            engine.process(documents, first.findings, first.outcomes)
            assertEquals(calls, evidence.size)
            File(context.filesDir, "context-router-phone-evidence.json").writeText(Gson().toJson(mapOf(
                "model" to name, "capabilities" to report, "plan" to model.processingPlan, "outcomes" to first.outcomes,
                "calls" to evidence, "unchangedReceiptReuse" to true, "timingScope" to "x86_64 CPU emulator, not physical phone performance")))
        } finally { client.release() }
    }

    @Test fun chosenServerCanReviewBoundedBatchesWithEachSourceAccountedFor(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialContextRemoteModel") == "true" && args.getString("antisocialIsolatedFixtureDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configFile = File(context.noBackupFilesDir, "context-test-provider.json"); assumeTrue(configFile.isFile)
        val config = JsonParser.parseString(configFile.readText()).asJsonObject
        val repo = (context.applicationContext as AntiSocialApplication).repo; repo.awaitReady()
        val oldPolicy = repo.gateway.currentPolicy()
        val records = mutableListOf<Map<String, Any>>()
        try {
            repo.gateway.configure(NetworkPolicy())
            val provider = AiRemoteProvider(repo.gateway, AiRemoteConfig(config["baseUrl"].asString, config["model"].asString, config["key"].asString))
            val report = provider.contextCapabilities()
            // This server does not advertise its parameter count. Test the
            // reader's explicit profiles; do not claim it is a 60B+ model.
            for (style in listOf(ContextProcessingStyle.BALANCED, ContextProcessingStyle.EXTENDED)) {
                val calls = mutableListOf<Map<String, Any>>()
                val model = object : ContextModel {
                    override val contextTokens = report.contextTokens
                    override val maxOutputTokens = ContextProcessingRouter.outputBudget(report, false, style)
                    override val processingPlan = ContextProcessingRouter.select(report, false, maxOutputTokens, style)
                    override suspend fun count(system: String, user: String) = system.toByteArray().size + user.toByteArray().size + 128
                    override suspend fun generate(system: String, user: String, maxTokens: Int): String {
                        assertTrue(count(system, user) + maxTokens + 32 <= contextTokens)
                        val result = provider.text(system, user, maxTokens)
                        calls += mapOf("prompt" to JsonParser.parseString(user), "output" to result, "maxTokens" to maxTokens)
                        File(context.filesDir, "context-router-server-attempt.json").writeText(Gson().toJson(mapOf("style" to style, "calls" to calls)))
                        return result
                    }
                }
                val engine = ContextEngine(model)
                val result = withTimeout(180_000) { engine.process(documents) }
                records += mapOf("plan" to model.processingPlan, "calls" to calls, "outcomes" to result.outcomes)
                File(context.filesDir, "context-router-server-attempt.json").writeText(Gson().toJson(mapOf("capabilities" to report, "runs" to records)))
                assertEquals(documents.size, result.outcomes.size)
                assertEquals(1, calls.size)
                assertEquals(ContextUnitStatus.NO_SIGNAL, result.outcomes.single { it.documentId == "noise" }.status)
                assertTrue(result.findings.any { "rude-correction" in it.sources && it.text.contains("56") })
                assertEquals("question", result.findings.single { "question" in it.sources }.kind)
                engine.process(documents, result.findings, result.outcomes); assertEquals(1, calls.size)
            }
            File(context.filesDir, "context-router-server-evidence.json").writeText(Gson().toJson(mapOf("capabilities" to report, "runs" to records)))
        } finally { repo.gateway.configure(oldPolicy) }
    }
}
