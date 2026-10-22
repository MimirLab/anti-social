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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AiRemoteProvidersTest {
    private fun provider(server: MockWebServer, gateway: NetworkGateway = AiTestFixtures.gateway(server), key: String = "user-secret") = AiRemoteProvider(gateway, AiRemoteConfig(server.url("/v1").toString(), "owned-model", key, localHost = true))

    @Test fun userOwnedTextUsesExactEndpointAndSecretIsScopedAwayFromReaderRequestsAndLogs(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server); val observed = AtomicInteger(); gateway.onTextResponse = { observed.incrementAndGet() }
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"A brief useful answer."}}]}"""))
            assertEquals("A brief useful answer.", provider(server, gateway).text("Summarize faithfully.", "Public post text", 100))
            val request = server.takeRequest(); assertEquals("/v1/chat/completions", request.path); assertEquals("Bearer user-secret", request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject; assertEquals(100, json["max_tokens"].asInt); assertFalse(json["stream"].asBoolean); assertEquals("Public post text", json.getAsJsonArray("messages")[1].asJsonObject["content"].asString)
            assertEquals(0, observed.get()); server.enqueue(MockResponse().setBody("ordinary")); gateway.text(server.url("/public").toString()); assertNull(server.takeRequest().getHeader("Authorization")); assertEquals(1, observed.get())
            assertFalse(AiRemoteConfig("https://models.example/v1", "owned", "secret-key").toString().contains("secret-key"))
        }
    }

    @Test fun vectorBatchesAreOrderedAndSpacesChangeWithServerModel(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); val provider = provider(server)
            server.enqueue(MockResponse().setBody("""{"model":"v1","data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}"""))
            val result = provider.embeddings(listOf("first", "second")); assertArrayEquals(floatArrayOf(1f, 0f), result.vectors[0], 0f); assertArrayEquals(floatArrayOf(0f, 1f), result.vectors[1], 0f)
            assertEquals("/v1/embeddings", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"model":"v2","data":[{"index":0,"embedding":[1.0,0.0]}]}"""))
            assertNotEquals(result.spaceId, provider.embeddings(listOf("first")).spaceId)
        }
    }

    @Test fun invalidVectorsAndMixedDimensionsAreRejected(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); val provider = provider(server)
            for (data in listOf("""[{"index":0,"embedding":[0,0]}]""", """[{"index":0,"embedding":[1,0]},{"index":0,"embedding":[1,0]}]""", """[{"index":0,"embedding":[1,0]},{"index":1,"embedding":[1,0,0]}]""", """[{"index":3,"embedding":[1,0]}]""")) {
                server.enqueue(MockResponse().setBody("""{"data":$data}""")); val count = if (data.contains("},{")) 2 else 1
                assertThrows(IOException::class.java) { runBlocking { provider.embeddings(List(count) { "text" }) } }
            }
        }
    }

    @Test fun localEndpointsAreExplicitAndTorNeverBypassesTheSelectedPolicy(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server); gateway.configure(NetworkPolicy(tor = true, ready = true, socksPort = 9050))
            assertThrows(IOException::class.java) { runBlocking { provider(server, gateway).text("", "test") } }
            assertEquals(0, server.requestCount)
            assertThrows(IllegalArgumentException::class.java) { AiRemoteConfig("http://192.168.1.7:8080/v1", "model") }
            assertThrows(IllegalArgumentException::class.java) { AiRemoteConfig("https://rebind.example/v1", "model", localHost = true) }
            assertThrows(IllegalArgumentException::class.java) { AiRemoteConfig("https://user:password@models.example/v1", "model") }
            assertThrows(IllegalArgumentException::class.java) { AiRemoteConfig("https://models.example/v1?token=secret", "model") }
            assertThrows(IllegalArgumentException::class.java) { AiRemoteConfig("https://models.example/v1", "model", "key\r\ninject") }
        }
    }

    @Test fun privateEndpointGrantDoesNotPermitLinkLocalOrPublicDestinations(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server)
            for (base in listOf("http://169.254.169.254/v1", "http://8.8.8.8/v1", "http://0.0.0.0/v1")) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { AiRemoteProvider(gateway, AiRemoteConfig(base, "model", localHost = true)).text("", "test") } }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun inferenceRedirectIsNotFollowedAndSecretNeverReachesOtherEndpoint(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/stolen")))
            assertThrows(IOException::class.java) { runBlocking { provider(server).text("", "test") } }
            assertEquals(1, server.requestCount); assertEquals("/v1/chat/completions", server.takeRequest().path)
        }
    }

    @Test fun postTransportFailureIsNeverReplayed(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertThrows(IOException::class.java) { runBlocking { provider(server).text("", "test") } }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun inputsAndOutputsAreBoundedBeforeBackgroundWorkCanGrow(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); val provider = provider(server)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { provider.text("", "a".repeat(1_048_577)) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { provider.text("", "界".repeat(349_526)) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { provider.text("", "test", 8193) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { provider.embeddings(List(17) { "test" }) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { provider.embeddings(listOf(" ")) } }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setHeader("Content-Length", 3 * 1024 * 1024).setBody("""{"choices":[]}"""))
            assertThrows(IOException::class.java) { runBlocking { provider.text("", "test") } }
        }
    }
    @Test fun largerReplyAllowancesReachTheSelectedServerWithoutChangingProtocol(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (limit in listOf(4096, 8192)) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"finished"}}]}"""))
                assertEquals("finished", provider(server).text("bounded task", "public fixture", limit))
                val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
                assertEquals(limit, body["max_tokens"].asInt)
                assertFalse(body.has("tools")); assertFalse(body.has("chat_template_kwargs"))
            }
        }
    }
    @Test fun exhaustedGenerationNeverMasqueradesAsAFinishedVisibleAnswer(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (content in listOf("null", "\"apparently complete text\"")) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":$content}}]}"""))
                val error = assertThrows(AiResponseLimitException::class.java) { runBlocking { provider(server).text("", "fixture", 4096) } }
                assertTrue(error.message!!.contains("response limit"))
            }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun verifiedVllmUtilityUsesDirectAnswersWithoutChangingOtherProtocols():Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            for(protocol in AiProtocol.entries) {
                server.enqueue(MockResponse().setBody(if(protocol==AiProtocol.ANTHROPIC)"""{"content":[{"type":"text","text":"answer"}]}"""else """{"choices":[{"finish_reason":"stop","message":{"content":"answer","reasoning_content":"never use this as evidence"}}]}"""))
                val p=AiRemoteProvider(AiTestFixtures.gateway(server),AiRemoteConfig(server.url("/v1").toString(),"alias","",true,protocol))
                assertEquals("answer",p.text("bounded utility","fixture",4096,vllmUtility=true))
                val body=JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
                if(protocol==AiProtocol.COMPATIBLE) {
                    assertFalse(body.getAsJsonObject("chat_template_kwargs")["enable_thinking"].asBoolean)
                    assertEquals("json_object",body.getAsJsonObject("response_format")["type"].asString)
                }else {assertFalse(body.has("chat_template_kwargs"));assertFalse(body.has("response_format"))}
            }
        }
    }

    @Test fun cancellationClosesInferenceWithoutFallback(): Unit = runBlocking {
        MockWebServer().use { server ->
            // A sleeping response races MockWebServer's own five-second close
            // deadline. Keep the request pending until cancellation instead.
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.Default) { provider(server).text("", "test") }
            withTimeout(5000) { while (server.requestCount == 0) delay(10) }; job.cancelAndJoin(); assertEquals(1, server.requestCount)
        }
    }

    @Test fun modelCanPrepareANonStreamingAnswerBeyondTheWebsiteReadTimeout():Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            // Real servers can finish thinking before sending any headers.
            // This exceeds the gateway's ordinary 15-second website timeout,
            // while remaining inside its explicit two-minute inference budget.
            server.enqueue(MockResponse().setHeadersDelay(16,TimeUnit.SECONDS)
                .setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"A complete delayed answer."}}]}"""))
            val gateway=AiTestFixtures.gateway(server)
            assertEquals("A complete delayed answer.",withTimeout(30_000){provider(server,gateway).text("","A bounded inference fixture",8192)})
            assertEquals(1,server.requestCount)
            assertEquals("/v1/chat/completions",server.takeRequest().path)
            assertEquals("Public requests keep their website policy",15_000,gateway.client().readTimeoutMillis)
        }
    }

    @Test fun manualProbeContainsNoFeedOrSavedMaterial(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
            assertTrue(provider(server).probe(AiKind.TEXT).contains("responding"))
            val json = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals("Say OK.", json.getAsJsonArray("messages")[1].asJsonObject["content"].asString)
        }
    }

    @Test fun publishedBonsaiSmallGgufIdsUseBoundedNonThinkingServerProfile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (model in listOf("prism-ml/Ternary-Bonsai-2-2B-gguf", "Ternary-Bonsai-2-4B-PQ2_0.gguf", "/models/Ternary-Bonsai-2-9B-PTQ1_0.gguf", "Ternary-Bonsai-2-4B-PQ2_0-MTP-Q8_0.gguf")) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
                val provider = AiRemoteProvider(AiTestFixtures.gateway(server), AiRemoteConfig(server.url("/v1").toString(), model, localHost = true))
                assertTrue(provider.probe(AiKind.TEXT).contains("responding"))
                val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
                assertEquals(16, body["max_tokens"].asInt)
                assertFalse(body.getAsJsonObject("chat_template_kwargs")["enable_thinking"].asBoolean)
                assertEquals(0.7, body["temperature"].asDouble, 0.0); assertEquals(0.8, body["top_p"].asDouble, 0.0)
                assertEquals(20, body["top_k"].asInt); assertEquals(0, body["min_p"].asInt); assertEquals(1.5, body["presence_penalty"].asDouble, 0.0)
                assertEquals(1.0, body["repeat_penalty"].asDouble, 0.0); assertEquals(64, body["repeat_last_n"].asInt)
                assertFalse(body.has("enable_thinking")); assertFalse(body.has("presence_context_size"))
            }
        }
    }

    @Test fun nativeMlxServerUsesItsPublishedTopLevelDirectAnswerFields(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"A direct answer."}}]}"""))
            val provider = AiRemoteProvider(AiTestFixtures.gateway(server), AiRemoteConfig(server.url("/v1").toString(), "prism-ml/Ternary-Bonsai-2-4B-mlx-2bit", localHost = true))
            assertEquals("A direct answer.", provider.text("Be concise.", "Public utility input", 100))
            val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertFalse(body["enable_thinking"].asBoolean); assertEquals(64, body["presence_context_size"].asInt)
            assertEquals(1.0, body["repetition_penalty"].asDouble, 0.0); assertEquals(0.7, body["temperature"].asDouble, 0.0)
            assertFalse(body.has("chat_template_kwargs")); assertFalse(body.has("repeat_penalty")); assertFalse(body.has("repeat_last_n"))
        }
    }

    @Test fun genericRenamedAndAuxiliaryModelsDoNotReceiveBonsaiVendorFields(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (model in listOf("owned-model", "renamed-bonsai", "Qwen3.5-4B", "Ternary-Bonsai-2-4B-DFlash2-BF16.gguf", "Bonsai-4B-gguf", "Ternary-Bonsai-2-27B-gguf")) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
                AiRemoteProvider(AiTestFixtures.gateway(server), AiRemoteConfig(server.url("/v1").toString(), model, localHost = true)).text("", "Test", 32)
                val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
                assertEquals(0, body["temperature"].asInt)
                assertEquals(setOf("model", "stream", "max_tokens", "temperature", "messages"), body.keySet())
            }
        }
    }
}
