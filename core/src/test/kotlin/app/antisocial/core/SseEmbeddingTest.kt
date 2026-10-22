package app.antisocial.core

import org.junit.Test
import org.junit.Assume.assumeTrue
import kotlin.test.*
import java.io.File

class SseEmbeddingTest {
    @Test fun spaceIdentityIncludesTheActualUnchangedPackAndRecipe() {
        assertEquals(512, SseEmbedding.DIMENSIONS)
        assertEquals("en", SseEmbedding.LANGUAGE)
        assertTrue(SseEmbedding.SPACE_DESCRIPTOR.contains("mean-token-occurrences"))
        assertTrue(SseEmbedding.SPACE_DESCRIPTOR.contains("beta*tanh(alpha*mean+bias)"))
        assertTrue(SseEmbedding.SPACE_DESCRIPTOR.contains("truncation=false"))
        assertEquals(71, SseEmbedding.SPACE_ID.length)
        assertNotNull(EmbeddingResult.Empty)
    }

    @Test fun missingPackCannotActivateNativeRuntime() {
        assertFailsWith<IllegalArgumentException> { SseEmbedding.open(File("/antisocial-no-such-model-pack")) }
    }

    @Test fun pinnedNativeEncoderKeepsOccurrencesAndReturnsTypedEmpty() {
        val directory = System.getenv("ANTISOCIAL_SSE_TEST_PACK")
        assumeTrue("Optional acquired-model parity run", directory != null)
        SseEmbedding.open(File(directory!!)).use { model ->
            assertEquals(EmbeddingResult.Empty, model.encode(" \t\n"))
            val text = "A battery battery is not a solar panel."
            val ids = model.tokenIds(text)
            val output = assertIs<EmbeddingResult.Vector>(model.encode(text))
            assertEquals(ids.size, output.tokenCount)
            assertTrue(ids.size > ids.distinct().size)
            assertEquals(512, output.values.size)
            assertEquals(SseEmbedding.SPACE_ID, output.spaceId)
            assertTrue(kotlin.math.abs(output.values.sumOf { it.toDouble()*it } - 1.0) < 0.00001)
        }
    }
}
