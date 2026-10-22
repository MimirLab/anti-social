package app.antisocial.core

import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Empty input is not a searchable zero vector, and must never enter an index. */
sealed interface EmbeddingResult {
    data object Empty : EmbeddingResult
    data class Vector(val values: FloatArray, val spaceId: String, val tokenCount: Int) : EmbeddingResult
}

/** Published English static encoder. Loading this class never starts a download or network request. */
class SseEmbedding private constructor(handle: Long) : Closeable {
    private val nativeHandle = AtomicLong(handle)

    /** One bounded CPU job at a time. The AI worker schedules it independently of the generator. */
    fun encode(text: String): EmbeddingResult {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "Split embedding inputs larger than 1 MiB into retained items" }
        val encoded = SseNativeBridge.encode(openHandle(), text)
        if (encoded.isEmpty()) return EmbeddingResult.Empty
        check(encoded.size == DIMENSIONS + 1) { "Invalid embedding runtime output" }
        val count = encoded[0].toInt()
        val values = encoded.copyOfRange(1, encoded.size)
        check(count > 0 && values.all(Float::isFinite)) { "Invalid embedding runtime output" }
        return EmbeddingResult.Vector(values, SPACE_ID, count)
    }

    /** Exposed for reproducible tokenizer parity diagnostics, without changing tokenizer settings. */
    fun tokenIds(text: String): IntArray = SseNativeBridge.tokenize(openHandle(), text)
    fun cancel() { nativeHandle.get().takeIf { it != 0L }?.let(SseNativeBridge::cancel) }
    override fun close() { nativeHandle.getAndSet(0L).takeIf { it != 0L }?.let(SseNativeBridge::close) }
    private fun openHandle(): Long = nativeHandle.get().also { check(it != 0L) { "Embedding model is closed" } }

    companion object {
        const val DIMENSIONS = 512
        const val MAX_INPUT_BYTES = 1_048_576
        const val MODEL_ID = "RikkaBotan/quantized-stable-static-embedding-fast-retrieval-mrl-en"
        const val REVISION = "48de1fc899574177a7c2d0d4ae90bb902190935c"
        const val LANGUAGE = "en"
        /** Different head/pooling/tokenizer/dimensions/prefixes/index policies imply different spaces. */
        val SPACE_DESCRIPTOR = "sse-uniform-q4-rows-v1|$MODEL_ID@$REVISION|" +
            "table=af7f5fa63246dbf4b73aef4d6d28b8e3cae82f7476edaf7417f0219061158447|" +
            "head=67f1caf874ce4621b8ed58e74b154623d83b6642a3bb958fcfaa83967182034b|" +
            "tokenizer=d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66|" +
            "config=a2c4f9036851e1800f7586652019ebb3ee614c1e970f887d7b2bdc26fa60bdad|" +
            "modules=ae26f0648b6560f6b91a7445962504d88bfd090ffc3b822ce9dfaac4bbf44c4f|" +
            "mean-token-occurrences|head=beta*tanh(alpha*mean+bias)-v1|dims=512|l2|" +
            "query-prefix=|document-prefix=|index=f32-v1|special-tokens=false|padding=false|truncation=false"
        val SPACE_ID: String = "sse512:" + MessageDigest.getInstance("SHA-256").digest(SPACE_DESCRIPTOR.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Verifies all five immutable files before mmap/tokenizer/head activation. */
        fun open(packDirectory: File): SseEmbedding {
            require(packDirectory.isDirectory) { "Local embedding pack is not installed" }
            val handle = SseNativeBridge.open(packDirectory.absolutePath)
            check(handle != 0L) { "Could not activate local embedding model" }
            return SseEmbedding(handle)
        }
    }
}

/** Library is loaded only on an explicit model open; optional AI has no baseline reader startup cost. */
internal object SseNativeBridge {
    init { System.getProperty("antisocial.embedding.native.path")?.let(System::load) ?: System.loadLibrary("antisocial_embedding") }
    external fun open(directory: String): Long
    /** Native envelope: token occurrence count followed by 512 coordinates; empty array = no content. */
    external fun encode(handle: Long, text: String): FloatArray
    external fun tokenize(handle: Long, text: String): IntArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}
