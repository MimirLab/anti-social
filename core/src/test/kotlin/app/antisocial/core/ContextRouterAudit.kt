/* AntiSocial, GPL-3.0-or-later. Explicit development audit; never included in the app. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import java.io.File

/** Runs the real engine, including counts, validation, screens and checkpoints.
 * Credentials come from a private file, never command arguments or evidence.
 * Local subprocesses are development-only native probes; app workers are tested
 * independently on Android. Status matching is not a semantic quality score. */
fun main() = runBlocking {
    val requested = System.getProperty("antisocial.routerAuditMode")
    for (mode in if (requested == "all") listOf("2B", "4B", "server") else listOf(requested)) runRouterAudit(mode)
}

private suspend fun runRouterAudit(mode: String) {
    val root = File(System.getProperty("antisocialBuildRoot"))
    val out = File(System.getProperty("antisocial.routerAuditOutput", File(root, "context-foundation/context-router").path)).canonicalFile
    require(out.path.startsWith(root.canonicalPath + File.separator)) { "Keep audit evidence under LiberBuild" }
    out.mkdirs()
    require(mode in setOf("2B", "4B", "server"))
    require(listOf("jsonl", "capabilities.json", "native.log", "identity.json").none { File(out, "audit-$mode-$it").exists() }) { "Refusing to overwrite audit evidence" }
    require(!File(out, "audit-$mode.jsonl").exists()) { "Refusing to overwrite audit evidence" }
    val fixture=System.getProperty("antisocial.routerAuditFixture","context-evaluation/router-holdout-v1.json")
    require(fixture.startsWith("context-evaluation/")&&!fixture.contains(".."))
    val cases = JsonParser.parseReader(requireNotNull(ContextEngine::class.java.getResourceAsStream("/$fixture")).reader()).asJsonArray
    val requestedCases = System.getProperty("antisocial.routerAuditCases")?.split(',')?.toSet()
    val selectedCases = cases.filter { requestedCases == null || it.asJsonObject["name"].asString in requestedCases }
    require(requestedCases == null || selectedCases.map { it.asJsonObject["name"].asString }.toSet() == requestedCases) { "Unknown audit case" }
    val gson = Gson()
    val calls = mutableListOf<Map<String, Any>>()
    var process: Process? = null
    var profileId = "router-audit-$mode"
    val model: ContextModel
    try {
    if (mode != "server") {
        val binary = File(System.getProperty("antisocial.routerAuditBinary", File(root, "context-foundation/context-router/router-native-probe").path))
        val command = mutableListOf(binary.path, File(root, "ai-foundation/models/Ternary-Bonsai-2-$mode-PQ2_0.gguf").path)
        // Older probes take only a model path. The recipe-aware runner takes
        // explicit backend/cache/window/position arguments, recorded verbatim.
        if (System.getProperty("antisocial.routerAuditBinary") != null) command += listOf(
            System.getProperty("antisocial.routerAuditBackend", "cpu"),
            System.getProperty("antisocial.routerAuditCache", "f16_reference"),
            System.getProperty("antisocial.routerAuditCapacity", "2048"),
            System.getProperty("antisocial.routerAuditPosition", "native"),System.getProperty("antisocial.routerAuditThreads","2"))
        fun digest(file: File): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        val binaryHash = digest(binary); val modelHash = digest(File(command[1]))
        profileId = (command + binaryHash + modelHash).joinToString("|")
        File(out, "audit-$mode-identity.json").writeText(gson.toJson(mapOf("command" to command,
            "binary_sha256" to binaryHash, "model_sha256" to modelHash,
            "case_names" to selectedCases.map { it.asJsonObject["name"].asString },
            "scope" to "Real ContextEngine source extraction and receipt reuse; external measurement and semantic review required")))
        process = ProcessBuilder(command)
            .redirectError(File(out, "audit-$mode-native.log")).start()
        val writer = process.outputStream.bufferedWriter(); val reader = process.inputStream.bufferedReader()
        fun exchange(op: String, system: String = "", user: String = "", maxTokens: Int = 0): JsonObject {
            writer.write(JsonObject().apply { addProperty("op", op); addProperty("system", system); addProperty("user", user); addProperty("maxTokens", maxTokens) }.toString())
            writer.newLine(); writer.flush()
            val response = JsonParser.parseString(reader.readLine() ?: error("Native probe ended")).asJsonObject
            check(!response.has("error")) { response["error"].asString }
            return response
        }
        val report = ContextCapabilityParser.nativeRuntime(exchange("metadata"), mode)
        model = object : ContextModel {
            override val contextTokens = report.contextTokens
            override val maxOutputTokens = report.maxOutputTokens!!
            override val shortDecisionTokens = 32
            override val processingPlan = ContextProcessingRouter.select(report, true, maxOutputTokens)
            override suspend fun count(system: String, user: String) = exchange("count", system, user)["count"].asInt
            override suspend fun generate(system: String, user: String, maxTokens: Int): String {
                val response = exchange("generate", system, user, maxTokens)
                calls += mapOf("prompt" to JsonParser.parseString(user), "maxTokens" to maxTokens, "response" to response)
                return response["text"].asString
            }
        }
        File(out, "audit-$mode-capabilities.json").writeText(gson.toJson(report))
    } else {
        val config = JsonParser.parseString(File(root, "context-foundation/private/provider.json").readText()).asJsonObject
        val cleaner = UrlCleaner(JsonParser.parseReader(ContextEngine::class.java.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
        val gateway = NetworkGateway(cleaner, object : RequestFilter { override fun check(url: String, sourceUrl: String, type: String) = FilterDecision(false) })
        val provider = AiRemoteProvider(gateway, AiRemoteConfig(config["baseUrl"].asString, config["model"].asString, config["key"].asString))
        val report = provider.contextCapabilities()
        model = object : ContextModel {
            override val contextTokens = report.contextTokens
            override val maxOutputTokens = ContextProcessingRouter.outputBudget(report, false, ContextProcessingStyle.BALANCED)
            override val generationProfile=if(report.vllmChatTemplate)"vllm-utility-direct-json-v2"else "default"
            override val processingPlan = ContextProcessingRouter.select(report, false, maxOutputTokens, ContextProcessingStyle.BALANCED)
            override suspend fun count(system: String, user: String) = system.toByteArray().size + user.toByteArray().size + 128
            override suspend fun generate(system: String, user: String, maxTokens: Int): String {
                val response = provider.text(system, user, maxTokens,vllmUtility=report.vllmChatTemplate)
                calls += mapOf("prompt" to JsonParser.parseString(user), "maxTokens" to maxTokens, "text" to response)
                return response
            }
        }
        File(out, "audit-server-capabilities.json").writeText(gson.toJson(report))
    }
        File(out, "audit-$mode.jsonl").bufferedWriter().use { output ->
            // Batching is exercised on Android. Independent cases here avoid
            // letting an unrelated holdout subject contaminate another case.
            for (entry in selectedCases) {
                val row = entry.asJsonObject; val input = row["input"].asJsonObject
                val document = ContextDocument(row["name"].asString, "1", input["text"].asString, author = input["author"].asString, parentText = input["parent_context"].asString)
                val background=input.getAsJsonObject("background")?.let {b->
                    fun source(key:String)=b.getAsJsonObject(key)?.let {s->ContextBackgroundSource(s["text"]?.asString.orEmpty(),s["label"]?.asString.orEmpty(),
                        s["incomplete"]?.asBoolean ?: false,s["unavailable"]?.asBoolean ?: false,s["uncertain"]?.asBoolean ?: false,
                        sourceId=s["sourceId"]?.asString.orEmpty())}
                    ContextBackground(source("post"),source("reply"))
                }
                val backgrounds=background?.let {mapOf(document.id to it)}.orEmpty()
                val readings=if(input.has("languageUnconfirmed"))mapOf(document.id to ContextReadingInput(ContextReadingText.identity(document.text,"fidelity-audit-v1",input["languageUnconfirmed"].asBoolean)))else emptyMap()
                calls.clear(); val started = System.nanoTime()
                val evidence = linkedMapOf<String, Any>("name" to row["name"].asString, "expectedStatus" to row["expected_status"].asString, "plan" to model.processingPlan)
                try {
                    val engine = ContextEngine(model, profileId)
                    val result = engine.process(listOf(document),backgrounds=backgrounds,readings=readings)
                    evidence["outcomes"] = result.outcomes; evidence["findings"] = result.findings
                    if(System.getProperty("antisocial.routerAuditSummary")=="true"&&result.findings.isNotEmpty())evidence["brief"]=engine.summarizeResult(result.findings).text
                    val before = calls.size
                    engine.process(listOf(document), result.findings, result.outcomes,backgrounds=backgrounds,readings=readings)
                    check(calls.size == before) { "Receipt reuse generated again" }
                    evidence["receiptReuse"] = true
                } catch (error: Exception) {
                    evidence["errorType"] = error.javaClass.simpleName
                    if(error is ContextProcessingException||error is AiResponseLimitException)evidence["reviewFailure"]=error.message.orEmpty().take(512)
                }
                evidence["calls"] = calls.toList(); evidence["elapsedMs"] = (System.nanoTime() - started) / 1_000_000
                output.write(gson.toJson(evidence)); output.newLine(); output.flush()
                println("$mode: ${row["name"].asString} recorded")
            }
        }
    } finally {
        process?.let {
            it.outputStream.close()
            if (!it.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                it.destroy()
                if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
            }
        }
    }
}
