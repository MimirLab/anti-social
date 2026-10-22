/* AntiSocial, GPL-3.0-or-later. Private metadata cache; never part of reader exports. */
package app.antisocial.android

import android.content.Context
import app.antisocial.core.ContextCapabilityConfidence
import app.antisocial.core.ContextModelCapabilities
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Inert until resolve is called by an explicitly requested or enabled Context
 * job. Opening settings and constructing this store perform no I/O to a server.
 * Identity must include selected route/model/protocol; mode includes privacy
 * policy. Neither raw identity, API keys nor reader material is persisted. */
internal class ContextCapabilityStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 6L * 60 * 60 * 1000
) {
    private val prefs = context.applicationContext.getSharedPreferences("context-model-capabilities", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val loading = Mutex()
    private val writes = Any()
    private val generation = AtomicLong()
    init { require(ttlMs in 1_000L..7L * 24 * 60 * 60 * 1000) }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun prefix(identity: String): String { require(identity.isNotBlank() && identity.length <= 8192); return "v2:${hash(identity)}:" }
    private fun key(identity: String, mode: String): String { require(mode.isNotBlank() && mode.length <= 1024); return prefix(identity) + hash(mode) }
    private fun cachedAt(value: String): Long? = runCatching { JsonParser.parseString(value).asJsonObject["cachedAt"].asLong }.getOrNull()
    private fun read(cacheKey: String): ContextModelCapabilities? = runCatching {
        val encoded = prefs.getString(cacheKey, null) ?: return@runCatching null
        if (encoded.length > 8192) return@runCatching null
        val root = JsonParser.parseString(encoded).asJsonObject
        val storedAt = root["cachedAt"].asLong
        val now = clock()
        if (storedAt < 0 || storedAt > now + 60_000 || now - storedAt >= ttlMs) return@runCatching null
        val report = gson.fromJson(root["report"], ContextModelCapabilities::class.java)
        // Gson bypasses Kotlin init; reconstruct to validate a persisted record.
        val validated = ContextModelCapabilities(report.contextTokens, report.maxOutputTokens, report.advertisedContextTokens,
            report.tokenizerIdentity, ContextCapabilityConfidence.valueOf(report.confidence.name), report.source,
            report.model, report.observedAt, report.activeContextTokens, report.maxInputTokens,
            report.parameterCount, report.modelBytes, report.quantization,report.vllmChatTemplate)
        // A temporarily unavailable metadata route must not pin a server to
        // the fallback for six hours. Unsupported endpoints still get a quiet
        // retry floor, and this check never starts background network work.
        if (validated.confidence == ContextCapabilityConfidence.UNKNOWN && now - storedAt >= minOf(ttlMs, 5L * 60 * 1000)) null else validated
    }.getOrNull()

    fun peek(identity: String, mode: String): ContextModelCapabilities? = read(key(identity, mode))

    suspend fun resolve(identity: String, mode: String, force: Boolean = false,
        load: suspend () -> ContextModelCapabilities): ContextModelCapabilities = withContext(Dispatchers.IO) {
        val cacheKey = key(identity, mode)
        loading.withLock {
            if (!force) read(cacheKey)?.let { return@withLock it }
            val version = generation.get()
            val report = load() // Cancellation propagates; a failed load never poisons a valid record.
            synchronized(writes) { if (generation.get() == version) {
                val now = clock()
                val root = com.google.gson.JsonObject().apply {
                    addProperty("cachedAt", now); add("report", gson.toJsonTree(report))
                }
                val rawEntries = prefs.all.entries.filter { it.key.startsWith("v1:")||it.key.startsWith("v2:") }
                val entries = rawEntries.mapNotNull { (name, value) ->
                    (value as? String)?.let { cachedAt(it) }?.let { name to it }
                }
                val edit = prefs.edit()
                rawEntries.filter { it.key.startsWith("v1:")||it.key !in entries.map { entry -> entry.first } }.forEach { edit.remove(it.key) }
                entries.filter { (_, time) -> time < 0 || time > now + 60_000 || now - time >= ttlMs }.forEach { edit.remove(it.first) }
                entries.filter { (name, time) -> name != cacheKey && time >= 0 && time <= now + 60_000 && now - time < ttlMs }
                    .sortedByDescending { it.second }.drop(31).forEach { edit.remove(it.first) }
                edit.putString(cacheKey, root.toString()).apply()
            } }
            report
        }
    }

    /** Also prevents an already running metadata read repopulating the cache. */
    fun invalidate(identity: String? = null) = synchronized(writes) {
        generation.incrementAndGet()
        val edit = prefs.edit()
        if (identity == null) edit.clear() else {
            val start = prefix(identity)
            prefs.all.keys.filter { it.startsWith(start) }.forEach { edit.remove(it) }
        }
        edit.apply()
    }
}
