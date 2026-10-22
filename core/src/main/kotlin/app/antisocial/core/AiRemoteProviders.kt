package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.MessageDigest

enum class AiProtocol(val label: String) {
    COMPATIBLE("OpenAI compatible"), OPENAI("OpenAI"), ANTHROPIC("Anthropic Messages")
}

/** Presets are inert configuration, never a request or a subscription purchase.
 * Model IDs remain editable: account access and provider catalogs change. */
enum class AiProviderPreset(val label: String,val baseUrl: String,val protocol: AiProtocol,val textModel: String,val embeddingModel: String="") {
    OPENAI("OpenAI · ChatGPT", "https://api.openai.com/v1", AiProtocol.OPENAI, "gpt-4.1-mini", "text-embedding-3-small"),
    ANTHROPIC("Anthropic · Claude", "https://api.anthropic.com/v1", AiProtocol.ANTHROPIC, "claude-haiku-5-5"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", AiProtocol.COMPATIBLE, "deepseek-flash"),
    ZAI("Z.ai", "https://api.z.ai/api/paas/v4", AiProtocol.COMPATIBLE, "glm-4.7"),
    CUSTOM("Other provider", "", AiProtocol.COMPATIBLE, "");
    fun supports(kind: AiKind)=kind==AiKind.TEXT || embeddingModel.isNotEmpty() || this==CUSTOM
    fun model(kind: AiKind)=if(kind==AiKind.TEXT)textModel else embeddingModel
}

enum class AiRouting(val label: String) { LOCAL_FIRST("Local first"), DEVICE_ONLY("Device only"), PROVIDER_ONLY("Use selected provider") }
object AiModelRouting {
    /** Resolve once, before I/O. A failed local choice never becomes a cloud upload. */
    fun useDevice(routing: AiRouting,installed: Boolean,localChosen: Boolean): Boolean = when(routing) {
        AiRouting.PROVIDER_ONLY -> false
        AiRouting.DEVICE_ONLY -> {check(installed){"Download a local model to use Device only."};true}
        AiRouting.LOCAL_FIRST -> {
            check(installed||!localChosen){"Your selected local model is not ready. Retry its download or choose Use selected provider in AI settings."}
            installed
        }
    }
}

/** A user-owned inference endpoint; no hosted service is selected by
 * default. Secrets are deliberately excluded from diagnostic rendering. */
data class AiRemoteConfig(val baseUrl: String, val model: String, val apiKey: String = "", val localHost: Boolean = false, val protocol: AiProtocol = AiProtocol.COMPATIBLE, val workspaceId: String = "", val preset: AiProviderPreset = AiProviderPreset.CUSTOM) {
    init {
        val url = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a model server URL")
        require(url.isHttps || localHost && url.scheme == "http") { "Remote model servers require HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use a model server URL without credentials or query parameters" }
        require(model.length in 1..200 && model.all { !it.isISOControl() }) { "Enter a model name" }
        require(apiKey.isEmpty() || apiKey.length <= 8192 && apiKey.all { it.code in 33..126 }) { "Invalid model server credential" }
        require(workspaceId.isEmpty() || workspaceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid provider workspace ID" }
        require(workspaceId.isEmpty() || protocol==AiProtocol.ANTHROPIC) { "Workspace ID is only supported by Anthropic" }
        if (localHost) require(url.host == "localhost" || url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) { "Use the local server's numeric address" }
    }
    override fun toString() = "AiRemoteConfig(baseUrl=$baseUrl, model=$model, apiKey=<private>, localHost=$localHost)"
    internal fun endpoint(path: String): String = baseUrl.trimEnd('/') + "/" + path
}

data class AiEmbeddingBatch(val spaceId: String, val vectors: List<FloatArray>)

/** A completed request exhausted generation; unlike an ambiguous transport
 * failure it can leave this passage pending while other sources advance. */
class AiResponseLimitException : IOException("The model reached its response limit before finishing this passage.")

/** The transport is shared with the reader's privacy policy. There is no
 * automatic provider fallback, discovery request, upload, or key purchase. */
class AiRemoteProvider(private val gateway: NetworkGateway, private val config: AiRemoteConfig) {
    /** Called only by an authorized Context job. This never submits reader
     * material, generates text, loads a model, or changes provider. */
    suspend fun contextCapabilities(): ContextModelCapabilities {
        val observedAt = System.currentTimeMillis()
        var best = ContextModelCapabilities.unknown(config.model, observedAt)
        suspend fun metadata(url: String): JsonObject? = try {
            parse(gateway.aiMetadata(url, config.apiKey.takeIf { it.isNotEmpty() }, config.localHost,
                config.protocol == AiProtocol.ANTHROPIC, config.workspaceId.takeIf { it.isNotEmpty() }))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: Exception) { null }
        fun adopt(candidate: ContextModelCapabilities) {
            // Size-only metadata is useful even when it cannot establish the
            // active context allocation. Never enlarge a window from size.
            best = best.copy(parameterCount = candidate.parameterCount ?: best.parameterCount,
                modelBytes = candidate.modelBytes ?: best.modelBytes, quantization = candidate.quantization ?: best.quantization,
                vllmChatTemplate = candidate.vllmChatTemplate || best.vllmChatTemplate)
            if (candidate.confidence == ContextCapabilityConfidence.ACTIVE_WINDOW ||
                best.confidence == ContextCapabilityConfidence.UNKNOWN && candidate.confidence == ContextCapabilityConfidence.MODEL_LIMIT) {
                val advertised = candidate.advertisedContextTokens ?: best.advertisedContextTokens
                val input = candidate.maxInputTokens ?: best.maxInputTokens
                best = candidate.copy(contextTokens = minOf(candidate.contextTokens, advertised ?: candidate.contextTokens, input ?: candidate.contextTokens),
                    advertisedContextTokens = advertised, maxOutputTokens = candidate.maxOutputTokens ?: best.maxOutputTokens,
                    tokenizerIdentity = candidate.tokenizerIdentity ?: best.tokenizerIdentity,
                    maxInputTokens = input, parameterCount = candidate.parameterCount ?: best.parameterCount,
                    modelBytes = candidate.modelBytes ?: best.modelBytes, quantization = candidate.quantization ?: best.quantization)
            }
        }
        // A finite metadata-only budget covers protocol-specific alternatives
        // on the same selected private server, not retries or cloud failover.
        kotlinx.coroutines.withTimeoutOrNull(if (gateway.currentPolicy().tor) 45_000L else 15_000L) {
            val primaryUrl = if (config.protocol == AiProtocol.COMPATIBLE) config.endpoint("models")
                else config.endpoint("models").toHttpUrlOrNull()!!.newBuilder().addPathSegment(config.model).build().toString()
            // A cold connection must not consume the entire discovery budget
            // and pin a large server to the tiny unknown-window fallback.
            // Retry only this timed-out, read-only exact endpoint, within the
            // same total deadline. HTTP rejection/redirect is not retried.
            var primaryFinished=false
            val primary = kotlinx.coroutines.withTimeoutOrNull(if(gateway.currentPolicy().tor)15_000L else 5_000L) {
                metadata(primaryUrl).also {primaryFinished=true}
            } ?: if(!primaryFinished)metadata(primaryUrl)else null
            primary?.let { adopt(ContextCapabilityParser.models(it, config.model, observedAt)) }
            if (best.confidence == ContextCapabilityConfidence.ACTIVE_WINDOW || !config.localHost || config.protocol != AiProtocol.COMPATIBLE) return@withTimeoutOrNull
            // These global server routes are only queried under the separate
            // explicit private-host grant, and retain exactly its origin.
            val base = config.baseUrl.toHttpUrlOrNull()!!
            fun serverPath(path: String) = base.newBuilder().encodedPath(path).build().toString()
            metadata(serverPath("/props"))?.let { adopt(ContextCapabilityParser.llamaProps(it, config.model, observedAt,
                primary?.let { json -> ContextCapabilityParser.singleLlamaModel(json, config.model) } == true)) }
            if (best.confidence == ContextCapabilityConfidence.ACTIVE_WINDOW) return@withTimeoutOrNull
            metadata(serverPath("/api/v1/models"))?.let { adopt(ContextCapabilityParser.lmStudio(it, config.model, observedAt)) }
            if (best.confidence == ContextCapabilityConfidence.ACTIVE_WINDOW) return@withTimeoutOrNull
            metadata(serverPath("/api/v0/models"))?.let { adopt(ContextCapabilityParser.lmStudio(it, config.model, observedAt)) }
        }
        return best
    }
    private enum class DirectAnswerProfile { GENERIC, BONSAI_GGUF, BONSAI_MLX }
    /** Only published main-model IDs/filenames identify this protocol profile.
     * A renamed server remains generic; optional DFlash drafters never qualify.
     * Source: PrismML Bonsai-demo/SMALL-MODELS.md, native server API examples. */
    private fun directAnswerProfile(): DirectAnswerProfile {
        val name = config.model.substringAfterLast('/')
        return when {
            Regex("Ternary-Bonsai-2-(?:2B|4B|9B)-mlx-2bit", RegexOption.IGNORE_CASE).matches(name) -> DirectAnswerProfile.BONSAI_MLX
            Regex("Ternary-Bonsai-2-(?:2B|4B|9B)-(?:gguf|(?:PQ2_0|PTQ1_0|Q2_0)(?:-MTP-Q8_0)?\\.gguf)", RegexOption.IGNORE_CASE).matches(name) -> DirectAnswerProfile.BONSAI_GGUF
            else -> DirectAnswerProfile.GENERIC
        }
    }
    suspend fun text(system: String, user: String, maxTokens: Int = 512, vllmUtility: Boolean = false): String {
        require(user.isNotBlank() && system.length.toLong() + user.length <= 1_048_576 &&
            system.toByteArray(Charsets.UTF_8).size.toLong() + user.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Text model input is empty or too large" }
        require(maxTokens in 1..8192) { "Text model output limit is too large" }
        val request = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("stream", false)
            addProperty(if(config.protocol==AiProtocol.OPENAI)"max_completion_tokens"else "max_tokens", maxTokens)
            if(config.protocol==AiProtocol.COMPATIBLE)addProperty("temperature", 0)
            if(config.protocol==AiProtocol.OPENAI)addProperty("store", false)
            when (if(config.protocol==AiProtocol.COMPATIBLE)directAnswerProfile()else DirectAnswerProfile.GENERIC) {
                DirectAnswerProfile.GENERIC -> Unit
                DirectAnswerProfile.BONSAI_GGUF, DirectAnswerProfile.BONSAI_MLX -> {
                    // A short utility request must reach its answer rather
                    // than exhaust its bounded output inside hidden thinking.
                    addProperty("temperature", 0.7)
                    addProperty("top_p", 0.8)
                    addProperty("top_k", 20)
                    addProperty("min_p", 0)
                    addProperty("presence_penalty", 1.5)
                    if (directAnswerProfile() == DirectAnswerProfile.BONSAI_GGUF) {
                        addProperty("repeat_penalty", 1.0)
                        addProperty("repeat_last_n", 64)
                        add("chat_template_kwargs", JsonObject().apply { addProperty("enable_thinking", false) })
                    } else {
                        // Native mlx-vlm's public /v1/chat/completions example
                        // documents these top-level names, unlike llama-server.
                        addProperty("repetition_penalty", 1.0)
                        addProperty("presence_context_size", 64)
                        addProperty("enable_thinking", false)
                    }
                }
            }
            add("messages", JsonArray().apply {
                if (system.isNotBlank()&&config.protocol!=AiProtocol.ANTHROPIC) add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", user) })
            })
        }
        if(config.protocol==AiProtocol.ANTHROPIC&&system.isNotBlank())request.addProperty("system",system)
        // vLLM's documented template override is safe for templates that do
        // not declare this variable. Bind this hint to the exact selected
        // model's metadata; never infer it from a host or an alias like Mimir.
        // No reasoning text is repurposed as an answer, and no paid retry is
        // introduced. Generic providers keep their original wire contract.
        if(config.protocol==AiProtocol.COMPATIBLE&&vllmUtility) {
            request.add("chat_template_kwargs",JsonObject().apply {addProperty("enable_thinking",false)})
            request.add("response_format",JsonObject().apply {addProperty("type","json_object")})
        }
        if(config.preset==AiProviderPreset.ANTHROPIC&&config.model=="claude-haiku-5-5")
            request.add("thinking",JsonObject().apply {addProperty("type","disabled")})
        // These utility requests need a visible answer within a short output cap.
        // Only documented optional-thinking families receive this parameter.
        if(config.preset==AiProviderPreset.DEEPSEEK||config.preset==AiProviderPreset.ZAI&&Regex("glm-(?:4\\.[67](?:-.*)?|5(?:\\.[012])?)",RegexOption.IGNORE_CASE).matches(config.model))
            request.add("thinking",JsonObject().apply {addProperty("type","disabled")})
        val response = gateway.aiService(config.endpoint(if(config.protocol==AiProtocol.ANTHROPIC)"messages"else "chat/completions"), request,
            config.apiKey.takeIf { it.isNotEmpty() }, config.localHost, config.protocol==AiProtocol.ANTHROPIC, config.workspaceId.takeIf {it.isNotEmpty()})
        val root = parse(response)
        if(config.protocol==AiProtocol.ANTHROPIC) return answer(root["content"])
        val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: throw IOException("The text server returned no answer")
        if (choice["finish_reason"]?.takeIf { it.isJsonPrimitive }?.asString == "length")
            throw AiResponseLimitException()
        val message = choice.getAsJsonObject("message") ?: throw IOException("The text server returned an unsupported answer")
        return answer(message["content"])
    }

    private fun answer(content: com.google.gson.JsonElement?): String {
        val text = try { when {
            content == null || content.isJsonNull -> ""
            content.isJsonPrimitive && content.asJsonPrimitive.isString -> content.asString
            content.isJsonArray -> content.asJsonArray.mapNotNull { part -> part.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it["type"]?.asString == "text" }?.get("text")?.asString }.joinToString("\n")
            else -> throw IOException("The text server returned an unsupported answer")
        }}catch(_: RuntimeException){throw IOException("The text server returned an unsupported answer")}
        if (text.isBlank() || text.length > 65536) throw IOException("The text server returned an empty or oversized answer")
        return text
    }

    suspend fun embeddings(inputs: List<String>): AiEmbeddingBatch {
        require(config.protocol!=AiProtocol.ANTHROPIC&&config.preset.supports(AiKind.EMBEDDING)){"Choose a separate embedding provider. Anthropic Messages does not supply embeddings."}
        require(inputs.size in 1..16 && inputs.all { it.isNotBlank() && it.length <= 4096 } && inputs.sumOf { it.length } <= 32768) { "Embedding input is empty or too large" }
        val request = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("encoding_format", "float")
            add("input", JsonArray().apply { inputs.forEach { add(it) } })
        }
        val response = gateway.aiService(config.endpoint("embeddings"), request, config.apiKey.takeIf { it.isNotEmpty() }, config.localHost)
        val root = parse(response)
        val data = root.getAsJsonArray("data") ?: throw IOException("The embedding server returned no vectors")
        if (data.size() != inputs.size) throw IOException("The embedding server returned an incomplete batch")
        val ordered = arrayOfNulls<FloatArray>(inputs.size)
        var dimensions = 0
        for (entry in data) {
            val value = entry.asJsonObject
            val index = value["index"]?.asInt ?: throw IOException("The embedding response has no input index")
            if (index !in ordered.indices || ordered[index] != null) throw IOException("The embedding response has duplicate or invalid input indexes")
            val vector = value.getAsJsonArray("embedding") ?: throw IOException("The embedding response has no vector")
            if (vector.size() !in 1..8192 || dimensions != 0 && dimensions != vector.size()) throw IOException("The embedding response has inconsistent dimensions")
            dimensions = vector.size()
            val floats = FloatArray(dimensions) { vector[it].asFloat }
            if (floats.any { !it.isFinite() } || floats.all { it == 0f }) throw IOException("The embedding server returned an invalid vector")
            ordered[index] = floats
        }
        val actualModel = root["model"]?.takeIf { it.isJsonPrimitive }?.asString?.take(256) ?: config.model
        val identity = "openai-compatible-float-v1\n${config.endpoint("embeddings")}\n${config.model}\n$actualModel\n$dimensions"
        val space = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return AiEmbeddingBatch("remote:$space", ordered.map { it ?: throw IOException("The embedding response is incomplete") })
    }

    /** A manual connection check contains no reader content. */
    suspend fun probe(kind: AiKind): String = when (kind) {
        AiKind.TEXT -> { text("Reply briefly to the connection check.", "Say OK.", 16); "${config.model} is responding" }
        AiKind.EMBEDDING -> { val result = embeddings(listOf("anti social model connection check.")); "${config.model} is responding · ${result.vectors.single().size} dimensions" }
    }

    private fun parse(payload: Payload): JsonObject = try {
        JsonParser.parseString(payload.text).asJsonObject
    } catch (_: Exception) { throw IOException("The model server returned an invalid response") }
}
