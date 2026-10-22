/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.EmbeddingResult
import app.antisocial.core.SseEmbedding
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Explicit acquired-model test; all reads are from private files and no requests occur. */
@RunWith(AndroidJUnit4::class)
class SseNativeParityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val testRoot get() = File(instrumentation.targetContext.filesDir, "local-ai/test")

    private fun pack(): File {
        assumeTrue("Opt in only after staging the pinned pack", arguments.getString("antisocialLocalAiModels") == "true")
        val directory = arguments.getString("antisocialSsePack")?.let(::File) ?: File(testRoot, "sse-q4")
        assertTrue("Stage all five unchanged embedding files in ${directory.absolutePath}", directory.isDirectory)
        return directory
    }
    private fun corpus(): JSONObject {
        val path = arguments.getString("antisocialSseReference")?.let(::File) ?: File(testRoot, "sse-reference.json")
        assertTrue("Stage host published-loader reference at ${path.absolutePath}", path.isFile)
        return JSONObject(path.readText())
    }

    @Test fun publishedTokenizerAndAllCoordinatesMatchOnAndroid() {
        val directory = pack()
        val reference = corpus()
        val cases = reference.getJSONArray("cases")
        var maximumError = 0.0
        var minimumCosine = 1.0
        var emptyCount = 0
        val timings = JSONArray()
        SseEmbedding.open(directory).use { model ->
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val text = case.getString("text")
                val ids = case.getJSONArray("ids")
                val expectedIds = IntArray(ids.length()) { ids.getInt(it) }
                assertArrayEquals("SSE tokenizer case $index", expectedIds, model.tokenIds(text))
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                val result = model.encode(text)
                timings.put((android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                if (ids.length() == 0) {
                    assertEquals(EmbeddingResult.Empty, result)
                    emptyCount++
                    continue
                }
                assertTrue(result is EmbeddingResult.Vector)
                result as EmbeddingResult.Vector
                assertEquals(expectedIds.size, result.tokenCount)
                assertEquals(SseEmbedding.SPACE_ID, result.spaceId)
                assertEquals(512, result.values.size)
                val vector = case.getJSONArray("vector")
                var cosine = 0.0
                var norm = 0.0
                for (dimension in 0 until 512) {
                    val actual = result.values[dimension].toDouble()
                    val expected = vector.getDouble(dimension)
                    maximumError = maxOf(maximumError, abs(actual - expected))
                    cosine += actual * expected
                    norm += actual * actual
                    assertTrue("Case $index coordinate $dimension", abs(actual - expected) < 0.000004)
                }
                minimumCosine = minOf(minimumCosine, cosine)
                assertTrue("Case $index cosine=$cosine", cosine > 0.99999)
                assertEquals(1.0, norm, 0.00001)
            }
        }
        assertEquals(3, emptyCount)
        testRoot.mkdirs()
        File(testRoot, "sse-android-parity.json").writeText(JSONObject().apply {
            put("schema", "antisocial-sse-android-parity-v1")
            put("cases", cases.length())
            put("empty_cases", emptyCount)
            put("max_coordinate_error", maximumError)
            put("minimum_cosine", minimumCosine)
            put("case_ms", timings)
            put("abi", android.os.Build.SUPPORTED_ABIS.first())
            put("android_api", android.os.Build.VERSION.SDK_INT)
            put("space_id", SseEmbedding.SPACE_ID)
        }.toString(2))
    }

    @Test fun occurrencesAreNotDeduplicatedAndBlankInputNeverBecomesAZeroVector() {
        SseEmbedding.open(pack()).use { model ->
            assertEquals(EmbeddingResult.Empty, model.encode(" \t\n"))
            assertEquals(EmbeddingResult.Empty, model.encode("\u0000\ufffd"))
            val repeated = model.encode("battery battery solar") as EmbeddingResult.Vector
            val single = model.encode("battery solar") as EmbeddingResult.Vector
            assertTrue(repeated.tokenCount > single.tokenCount)
            assertFalse(repeated.values.contentEquals(single.values))
            assertFalse(model.tokenIds("Café 中文测试").isEmpty())
        }
    }

    @Test fun corruptedOrIncompletePackCannotActivate() {
        val source = pack()
        val temporary = File(testRoot, "bad-pack").apply { deleteRecursively(); mkdirs() }
        try {
            listOf("embedding.q4_k_m.bin", "model_rest.safetensors", "tokenizer.json", "config_sentence_transformers.json", "modules.json").forEach {
                File(source, it).copyTo(File(temporary, it), overwrite = true)
            }
            java.io.RandomAccessFile(File(temporary, "embedding.q4_k_m.bin"), "rw").use { file ->
                file.seek(100)
                val value = file.readByte()
                file.seek(100)
                file.writeByte(value.toInt() xor 1)
            }
            try { SseEmbedding.open(temporary).close(); fail("Modified coefficients must not activate") } catch (_: IllegalStateException) { }
            File(source, "embedding.q4_k_m.bin").copyTo(File(temporary, "embedding.q4_k_m.bin"), overwrite = true)
            File(temporary, "modules.json").delete()
            try { SseEmbedding.open(temporary).close(); fail("Incomplete packs must not activate") } catch (_: IllegalStateException) { }
        } finally { temporary.deleteRecursively() }
    }

    @Test fun explicitCloseIsIdempotentAndFurtherInferenceIsRejected() {
        val model = SseEmbedding.open(pack())
        model.close()
        model.close()
        try { model.encode("battery"); fail("Closed model cannot encode") } catch (_: IllegalStateException) { }
    }
}
