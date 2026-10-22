package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ContextModelCapabilitiesTest {
    @Test fun coldMetadataConnectionCanRetryWithinTheExistingDiscoveryDeadline():Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            server.enqueue(MockResponse().setHeadersDelay(6,java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"selected","owned_by":"vllm","max_model_len":262144}]}"""))
            val result=AiRemoteProvider(AiTestFixtures.gateway(server),AiRemoteConfig(server.url("/v1").toString(),"selected",localHost=true)).contextCapabilities()
            assertEquals(262144,result.contextTokens);assertTrue(result.vllmChatTemplate)
            assertEquals(2,server.requestCount)
            assertEquals("/v1/models",server.takeRequest().path);assertEquals("/v1/models",server.takeRequest().path)
        }
    }
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
    private fun parse(value: String, model: String = "selected") = ContextCapabilityParser.models(json(value), model, 1234)
    @Test fun selectedVllmRecordReportsItsActiveWindowIncludingMimir262k() {
        val result = parse("""{"data":[{"id":"other","max_model_len":4096},{"id":"mimir-bigbrain","object":"model","owned_by":"vllm","max_model_len":262144}]}""", "mimir-bigbrain")
        assertEquals(262144, result.contextTokens); assertEquals(262144, result.activeContextTokens)
        assertEquals(ContextCapabilityConfidence.ACTIVE_WINDOW, result.confidence); assertEquals(1234L, result.observedAt)
        assertTrue(result.vllmChatTemplate)
    }
    @Test fun utilityProtocolRequiresTheExactUniqueSelectedServerRecord() {
        assertFalse(parse("""{"data":[{"id":"other","owned_by":"vllm"},{"id":"selected","owned_by":"someone"}]}""").vllmChatTemplate)
        assertFalse(parse("""{"data":[{"id":"selected","owned_by":"vllm"},{"id":"selected","owned_by":"vllm"}]}""").vllmChatTemplate)
        assertFalse(ContextModelCapabilities.local("mimir-bigbrain").vllmChatTemplate)
        assertFalse(parse("""{"id":"mimir-bigbrain"}""","mimir-bigbrain").vllmChatTemplate)
    }
    @Test fun trainingLimitDoesNotPretendToBeLoadedContext() {
        val result = parse("""{"data":[{"id":"selected","meta":{"n_ctx_train":131072},"params":99999999999}]}""")
        assertEquals(2048, result.contextTokens); assertEquals(131072, result.advertisedContextTokens)
        assertNull(result.activeContextTokens); assertEquals(ContextCapabilityConfidence.MODEL_LIMIT, result.confidence)
    }
    @Test fun advertisedSmallerLimitAndInputLimitConstrainConservativeWindow() {
        val result = parse("""{"id":"selected","context_length":1024,"max_input_tokens":768,"max_output_tokens":128,"tokenizer":{"id":"exact-tokenizer-v1"}}""")
        assertEquals(768, result.contextTokens); assertEquals(128, result.maxOutputTokens)
        assertEquals("exact-tokenizer-v1", result.tokenizerIdentity)
    }
    @Test fun exactSelectedIdentityAndUniquenessAreRequired() {
        for (value in listOf("""{"data":[{"id":"selected-9B","max_model_len":999999}]}""",
            """{"data":[{"id":"selected","max_model_len":65536},{"id":"selected","max_model_len":4096}]}""")) {
            val result = parse(value); assertEquals(2048, result.contextTokens); assertEquals(ContextCapabilityConfidence.UNKNOWN, result.confidence)
        }
    }
    @Test fun namesAndWeightCountsNeverImplyModelCapacity() {
        val result = parse("""{"id":"large-model-9B-1M","params":9000000000,"size_bytes":3000000000}""", "large-model-9B-1M")
        assertEquals(2048, result.contextTokens); assertEquals(ContextCapabilityConfidence.UNKNOWN, result.confidence)
    }
    @Test fun invalidCountsAreIgnoredRatherThanTruncatedOrWrapped() {
        for (count in listOf("-1", "0", "2048.1", "1048577", "99999999999999999999999", "\"131072\"", "null")) {
            val result = parse("""{"id":"selected","max_model_len":$count}""")
            assertEquals(2048, result.contextTokens); assertNull(result.activeContextTokens)
        }
    }
    @Test fun localWindowIsActive2048RegardlessOfModelSize() {
        val result = ContextModelCapabilities.local("Bonsai-9B", 55)
        assertEquals(2048, result.contextTokens); assertEquals(2048, result.activeContextTokens)
        assertEquals(ContextCapabilityConfidence.ACTIVE_WINDOW, result.confidence)
    }
    @Test fun nativeDiagnosticsUseTheAllocatedWindowInsteadOfModelSizeOrRuntimeMaximum() {
        for (window in listOf(512, 2048, 4096, 8192)) {
            val report = ContextCapabilityParser.nativeRuntime(json("""{"context":$window,"model":"huge-1M","maximum_context":8192}"""), "selected", 66)
            assertEquals(window, report.contextTokens); assertEquals(window, report.activeContextTokens)
            assertEquals(minOf(512, window / 4), report.maxOutputTokens); assertEquals(66L, report.observedAt)
            assertEquals(ContextCapabilityConfidence.ACTIVE_WINDOW, report.confidence)
        }
        for (value in listOf("{}", """{"maximum_context":8192}""", """{"context":"2048"}""", """{"context":2048.5}""", """{"context":-1}""", """{"context":16384}""")) {
            assertThrows(IllegalArgumentException::class.java) { ContextCapabilityParser.nativeRuntime(json(value), "selected") }
        }
    }
    @Test fun llamaServerDefaultSlotRequiresExactModelOrVerifiedSingleAlias() {
        val props = json("""{"model_path":"/private/model.gguf","default_generation_settings":{"n_ctx":8192,"params":{"n_predict":-1}}}""")
        assertEquals(2048, ContextCapabilityParser.llamaProps(props, "alias").contextTokens)
        assertEquals(8192, ContextCapabilityParser.llamaProps(props, "/private/model.gguf").contextTokens)
        assertEquals(8192, ContextCapabilityParser.llamaProps(props, "alias", verifiedSingleModel = true).contextTokens)
        assertTrue(ContextCapabilityParser.singleLlamaModel(json("""{"data":[{"id":"alias","owned_by":"llamacpp"}]}"""), "alias"))
        assertFalse(ContextCapabilityParser.singleLlamaModel(json("""{"data":[{"id":"alias","owned_by":"llamacpp"},{"id":"another","owned_by":"llamacpp"}]}"""), "alias"))
    }
    @Test fun lmStudioUsesLoadedInstanceConfigRatherThanModelMaximum() {
        val root = json("""{"models":[{"key":"selected","max_context_length":262144,"loaded_instances":[{"id":"instance-one","config":{"context_length":8192}}]}]}""")
        val result = ContextCapabilityParser.lmStudio(root, "selected", 12)
        assertEquals(8192, result.contextTokens); assertEquals(262144, result.advertisedContextTokens)
        assertEquals(ContextCapabilityConfidence.ACTIVE_WINDOW, result.confidence)
        assertEquals(8192, ContextCapabilityParser.lmStudio(root, "instance-one").contextTokens)
    }
    @Test fun lmStudioMultipleInstancesUseSmallestAndIncompleteReportsRemainConservative() {
        val full = json("""{"models":[{"key":"selected","loaded_instances":[{"id":"a","config":{"context_length":4096}},{"id":"b","config":{"context_length":16384}}]}]}""")
        assertEquals(4096, ContextCapabilityParser.lmStudio(full, "selected").contextTokens)
        val incomplete = json("""{"models":[{"key":"selected","max_context_length":131072,"loaded_instances":[{"id":"a","config":{"context_length":8192}},{"id":"b","config":{}}]}]}""")
        assertEquals(2048, ContextCapabilityParser.lmStudio(incomplete, "selected").contextTokens)
        assertNull(ContextCapabilityParser.lmStudio(incomplete, "selected").activeContextTokens)
    }
    @Test fun unloadedLmStudioModelIsNotSilentlyLoadedOrAssignedAnActiveWindow() {
        val result = ContextCapabilityParser.lmStudio(json("""{"data":[{"id":"selected","state":"not-loaded","max_context_length":131072}]}"""), "selected")
        assertEquals(2048, result.contextTokens); assertNull(result.activeContextTokens)
    }
    @Test fun metadataGetIsPrivateScopedAndContainsNoInferenceBody(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server); val observed = AtomicInteger()
            gateway.onTextResponse = { observed.incrementAndGet() }
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"selected","max_model_len":262144}]}"""))
            val result = AiRemoteProvider(gateway, AiRemoteConfig(server.url("/v1").toString(), "selected", "private-key", true)).contextCapabilities()
            assertEquals(262144, result.contextTokens)
            val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("/v1/models", request.path)
            assertEquals(0L, request.bodySize); assertEquals("Bearer private-key", request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertEquals(0, observed.get()); assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("reader")); gateway.text(server.url("/ordinary").toString())
            assertNull(server.takeRequest().getHeader("Authorization")); assertEquals(1, observed.get())
        }
    }
    @Test fun privateCompatibleServerCanReportBoundSingleLlamaSlotWithoutInference(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"data":[{"id":"selected","owned_by":"llamacpp","meta":{"n_ctx_train":131072}}]}"""))
            server.enqueue(MockResponse().setBody("""{"model_path":"/models/model.gguf","default_generation_settings":{"n_ctx":16384}}"""))
            val result = AiRemoteProvider(AiTestFixtures.gateway(server), AiRemoteConfig(server.url("/v1").toString(), "selected", localHost = true)).contextCapabilities()
            assertEquals(16384, result.contextTokens); assertEquals(131072, result.advertisedContextTokens)
            assertEquals("/v1/models", server.takeRequest().path); assertEquals("/props", server.takeRequest().path); assertEquals(2, server.requestCount)
        }
    }
    @Test fun activePrivateSlotRetainsThePrimaryModelsSmallerInputLimit(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"selected","owned_by":"llamacpp","max_input_tokens":3072,"max_output_tokens":256}]}"""))
            server.enqueue(MockResponse().setBody("""{"default_generation_settings":{"n_ctx":16384}}"""))
            val result = AiRemoteProvider(AiTestFixtures.gateway(server), AiRemoteConfig(server.url("/v1").toString(), "selected", localHost = true)).contextCapabilities()
            assertEquals(16384, result.activeContextTokens); assertEquals(3072, result.maxInputTokens)
            assertEquals(3072, result.contextTokens); assertEquals(256, result.maxOutputTokens)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun capabilityRedirectCannotReachAnotherEndpoint(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/stolen")))
            assertThrows(IOException::class.java) { runBlocking { AiTestFixtures.gateway(server).aiMetadata(server.url("/v1/models").toString(), "secret", localHost = true) } }
            assertEquals(1, server.requestCount); assertEquals("/v1/models", server.takeRequest().path)
        }
    }
    @Test fun metadataTorPrivateGrantFailsClosedAndFilteringCannotRewriteTheEndpoint(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server)
            gateway.configure(NetworkPolicy(tor = true, ready = true, socksPort = 9050))
            assertThrows(IOException::class.java) { runBlocking { gateway.aiMetadata(server.url("/v1/models").toString(), "secret", localHost = true) } }
            assertEquals(0, server.requestCount)
            val cleaner = UrlCleaner(json("""{"providers":{}}"""))
            val rewriting = NetworkGateway(cleaner, object : RequestFilter {
                override fun check(url: String, sourceUrl: String, type: String) = FilterDecision(false, rewrittenUrl = server.url("/stolen").toString())
            }, true)
            assertThrows(IOException::class.java) { runBlocking { rewriting.aiMetadata(server.url("/v1/models").toString(), "secret", localHost = true) } }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun metadataCancellationStopsWorkAndDoesNotStartOtherProbes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBodyDelay(5, TimeUnit.SECONDS).setBody("""{"data":[]}"""))
            val provider = AiRemoteProvider(AiTestFixtures.gateway(server), AiRemoteConfig(server.url("/v1").toString(), "selected", localHost = true))
            val job = launch(Dispatchers.Default) { provider.contextCapabilities() }
            withTimeout(5000) { while (server.requestCount == 0) delay(10) }; job.cancelAndJoin()
            assertEquals(1, server.requestCount)
        }
    }
}
