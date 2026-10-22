/* AntiSocial, GPL-3.0-or-later. Metadata is evidence about a selected endpoint, never permission to invoke tools. */
package app.antisocial.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

enum class ContextCapabilityConfidence { ACTIVE_WINDOW, MODEL_LIMIT, UNKNOWN }

/** [contextTokens] is the conservative working window, including input and
 * output. An advertised training/model limit alone cannot enlarge it. */
data class ContextModelCapabilities(
    val contextTokens: Int = 2048,
    val maxOutputTokens: Int? = null,
    val advertisedContextTokens: Int? = null,
    val tokenizerIdentity: String? = null,
    val confidence: ContextCapabilityConfidence = ContextCapabilityConfidence.UNKNOWN,
    val source: String = "No active context metadata; conservative default",
    val model: String = "",
    val observedAt: Long = System.currentTimeMillis(),
    val activeContextTokens: Int? = null,
    val maxInputTokens: Int? = null,
    val parameterCount: Long? = null,
    val modelBytes: Long? = null,
    val quantization: String? = null,
    /** Exact selected /models record identifies the server protocol, not the
     * model family or its ability to reason. Used only for bounded utility jobs. */
    val vllmChatTemplate: Boolean = false
) {
    init {
        require(contextTokens in 1..MAX_CONTEXT_TOKENS)
        require(listOfNotNull(maxOutputTokens, advertisedContextTokens, activeContextTokens, maxInputTokens).all { it in 1..MAX_CONTEXT_TOKENS })
        require(source.length <= 256 && model.length <= 256 && tokenizerIdentity.orEmpty().length <= 256)
        require(parameterCount == null || parameterCount in 1..10_000_000_000_000L)
        require(modelBytes == null || modelBytes in 1..100_000_000_000_000L)
        require(quantization.orEmpty().length <= 80)
    }
    companion object {
        const val MAX_CONTEXT_TOKENS = 1_048_576
        fun local(model: String, observedAt: Long = System.currentTimeMillis(), activeContextTokens: Int = 2048) = ContextModelCapabilities(
            contextTokens = activeContextTokens, maxOutputTokens = minOf(512, activeContextTokens / 4), confidence = ContextCapabilityConfidence.ACTIVE_WINDOW,
            source = "anti social native runtime active window", model = model.take(256), observedAt = observedAt,
            activeContextTokens = activeContextTokens)
        fun unknown(model: String, observedAt: Long = System.currentTimeMillis(), source: String = "Active context metadata unavailable; conservative default") =
            ContextModelCapabilities(model = model.take(256), observedAt = observedAt, source = source.take(256))
    }
}

/** Pure parsers for published server metadata, with exact model matching.
 * Size is independent of the active context window. No model-name guesses. */
object ContextCapabilityParser {
    private fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.takeIf { it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl) }
    private fun count(value: JsonElement?): Int? = try {
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal
            ?.intValueExact()?.takeIf { it in 1..ContextModelCapabilities.MAX_CONTEXT_TOKENS }
    } catch (_: RuntimeException) { null }
    private fun JsonObject.count(vararg keys: String): Int? = keys.mapNotNull { count(get(it)) }.minOrNull()
    private fun positiveLong(value: JsonElement?, maximum: Long): Long? = try {
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal
            ?.longValueExact()?.takeIf { it in 1..maximum }
    } catch (_: RuntimeException) { null }
    private fun JsonObject.parameters(key: String) = positiveLong(get(key), 10_000_000_000_000L)
    private fun JsonObject.bytes(key: String) = positiveLong(get(key), 100_000_000_000_000L)
    // LM Studio publishes params_string separately from the display name.
    // A MoE's total count is retained; active experts are not a quality score.
    private fun parameterString(value: String?): Long? = try {
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)([BMT])(?:-A[0-9]+(?:\\.[0-9]+)?[BMT])?", RegexOption.IGNORE_CASE).matchEntire(value.orEmpty())
        match?.let { it.groupValues[1].toBigDecimal().multiply(when(it.groupValues[2].uppercase()) {
            "M" -> 1_000_000.toBigDecimal(); "T" -> 1_000_000_000_000.toBigDecimal(); else -> 1_000_000_000.toBigDecimal()
        }).longValueExact().takeIf { count -> count in 1..10_000_000_000_000L } }
    } catch (_: RuntimeException) { null }
    private fun records(root: JsonObject): List<JsonObject> = when {
        root["data"]?.isJsonArray == true -> root.getAsJsonArray("data").take(4096).mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        root["models"]?.isJsonArray == true -> root.getAsJsonArray("models").take(4096).mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        else -> listOf(root)
    }
    private fun matches(record: JsonObject, model: String): Boolean = record.string("id") == model || record.string("key") == model
    private fun selected(root: JsonObject, model: String) = records(root).filter { matches(it, model) }.singleOrNull()
    private fun tokenizer(record: JsonObject): String? = record.string("tokenizer_id") ?: record.string("tokenizer_name")
        ?: record.string("tokenizer") ?: record.obj("tokenizer")?.let { it.string("id") ?: it.string("name") }

    /** Diagnostics come from the already selected private native worker, not
     * a model's description or its training metadata. Fail if the worker cannot
     * confirm its allocation rather than planning against an assumed window. */
    fun nativeRuntime(root: JsonObject, model: String, observedAt: Long = System.currentTimeMillis()): ContextModelCapabilities {
        val active = root.count("context")?.takeIf { it in 512..8192 }
            ?: throw IllegalArgumentException("The local runtime did not report a supported active context window")
        return ContextModelCapabilities.local(model, observedAt, active).copy(
            parameterCount = root.parameters("parameter_count"), modelBytes = root.bytes("model_bytes"))
    }

    fun models(root: JsonObject, model: String, observedAt: Long = System.currentTimeMillis()): ContextModelCapabilities {
        val record = selected(root, model) ?: return ContextModelCapabilities.unknown(model, observedAt, "Selected model was not uniquely identified in model metadata")
        val meta = record.obj("meta")
        val active = record.count("max_model_len")
        val advertised = listOfNotNull(record.count("context_length", "context_window", "max_context_length", "n_ctx_train"),
            meta?.count("n_ctx_train", "max_context_length")).minOrNull()
        val input = record.count("max_input_tokens", "input_token_limit")
        val output = record.count("max_output_tokens", "max_completion_tokens", "output_token_limit")
        val budget = listOfNotNull(active ?: 2048, advertised, input).minOrNull() ?: 2048
        return ContextModelCapabilities(budget, output, advertised, tokenizer(record),
            if (active != null) ContextCapabilityConfidence.ACTIVE_WINDOW else if (advertised != null || input != null) ContextCapabilityConfidence.MODEL_LIMIT else ContextCapabilityConfidence.UNKNOWN,
            when { active != null -> "Selected model metadata: max_model_len"; advertised != null -> "Selected model advertised limit; active window unknown"; input != null -> "Selected model input limit; active window unknown"; else -> "Selected model metadata has no context window" },
            model.take(256), observedAt, active, input,
            parameterCount = meta?.parameters("n_params"), modelBytes = meta?.bytes("size"),
            vllmChatTemplate = record.string("owned_by") == "vllm")
    }

    /** Only a uniquely matched single llama.cpp model can bind an alias to
     * /props' global default slot; a multi-model router must name its model. */
    fun singleLlamaModel(root: JsonObject, model: String): Boolean = records(root).singleOrNull()?.let {
        matches(it, model) && it.string("owned_by") in setOf("llamacpp", "llama.cpp")
    } == true

    fun llamaProps(root: JsonObject, model: String, observedAt: Long = System.currentTimeMillis(), verifiedSingleModel: Boolean = false): ContextModelCapabilities {
        val named = listOfNotNull(root.string("model_path"), root.string("model_alias"), root.string("model"))
        if (model !in named && !verifiedSingleModel) return ContextModelCapabilities.unknown(model, observedAt, "Server properties do not identify the selected model")
        val active = root.obj("default_generation_settings")?.count("n_ctx")
            ?: return ContextModelCapabilities.unknown(model, observedAt, "Server properties have no active context window")
        return ContextModelCapabilities(active, tokenizerIdentity = tokenizer(root), confidence = ContextCapabilityConfidence.ACTIVE_WINDOW,
            source = "Selected llama.cpp server default slot: n_ctx", model = model.take(256), observedAt = observedAt, activeContextTokens = active)
    }

    fun lmStudio(root: JsonObject, model: String, observedAt: Long = System.currentTimeMillis()): ContextModelCapabilities {
        val all = records(root)
        val direct = all.filter { matches(it, model) }
        val byInstance = all.filter { record -> record["loaded_instances"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?.any { it.isJsonObject && it.asJsonObject.string("id") == model } == true }
        val record = (direct + byInstance).distinct().singleOrNull()
            ?: return ContextModelCapabilities.unknown(model, observedAt, "Selected model was not uniquely identified in LM Studio metadata")
        val instances = record["loaded_instances"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()
        val matchingInstances = if (direct.isEmpty()) instances.filter { it.string("id") == model } else instances
        // If a model key routes to several loaded instances, use the smallest
        // configured window, and require a context report for every instance.
        val windows = matchingInstances.map { it.obj("config")?.count("context_length") }
        val active = windows.takeIf { it.isNotEmpty() && it.all { value -> value != null } }?.filterNotNull()?.minOrNull()
        val advertised = record.count("max_context_length")
        val budget = listOfNotNull(active ?: 2048, advertised).minOrNull() ?: 2048
        return ContextModelCapabilities(budget, advertisedContextTokens = advertised, tokenizerIdentity = tokenizer(record),
            confidence = if (active != null) ContextCapabilityConfidence.ACTIVE_WINDOW else if (advertised != null) ContextCapabilityConfidence.MODEL_LIMIT else ContextCapabilityConfidence.UNKNOWN,
            source = if (active != null) "Selected LM Studio loaded instance config: context_length" else "Selected LM Studio advertised limit; active window unknown",
            model = model.take(256), observedAt = observedAt, activeContextTokens = active,
            parameterCount = parameterString(record.string("params_string")), modelBytes = record.bytes("size_bytes"),
            quantization = (record.obj("quantization")?.string("name") ?: record.string("quantization"))?.take(80))
    }
}
