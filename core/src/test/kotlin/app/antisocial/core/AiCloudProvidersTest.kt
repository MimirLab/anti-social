/* AntiSocial, GPL-3.0-or-later. Protocol fixtures make no paid provider calls. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AiCloudProvidersTest {
    private fun config(server: MockWebServer,protocol: AiProtocol,preset: AiProviderPreset=AiProviderPreset.CUSTOM)=
        AiRemoteConfig(server.url("/v1").toString(),if(preset==AiProviderPreset.CUSTOM)"chosen-model"else preset.textModel,"fixture-secret",true,protocol,if(protocol==AiProtocol.ANTHROPIC)"wrkspc_fixture"else "",preset)

    @Test fun claudeMessagesUsesScopedVersionWorkspaceAndSystemWithTextBlocksOnly(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val gateway=AiTestFixtures.gateway(server);var observed=0;gateway.onTextResponse={observed++}
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"thinking","thinking":"private reasoning"},{"type":"text","text":"Readable"},{"type":"text","text":"answer"}]}"""))
            assertEquals("Readable\nanswer",AiRemoteProvider(gateway,config(server,AiProtocol.ANTHROPIC,AiProviderPreset.ANTHROPIC)).text("Be faithful.","Source text",120))
            val req=server.takeRequest();assertEquals("/v1/messages",req.path)
            assertEquals("Bearer fixture-secret",req.getHeader("Authorization"));assertEquals("2023-06-01",req.getHeader("anthropic-version"));assertEquals("wrkspc_fixture",req.getHeader("anthropic-workspace-id"));assertNull(req.getHeader("Cookie"))
            val body=JsonParser.parseString(req.body.readUtf8()).asJsonObject
            assertEquals("Be faithful.",body["system"].asString);assertEquals(120,body["max_tokens"].asInt);assertFalse(body.has("temperature"));assertEquals("disabled",body.getAsJsonObject("thinking")["type"].asString)
            assertEquals(1,body.getAsJsonArray("messages").size());assertEquals("user",body.getAsJsonArray("messages")[0].asJsonObject["role"].asString)
            assertEquals(0,observed)
            server.enqueue(MockResponse().setBody("public"))
            gateway.text(server.url("/public").toString(),headers=mapOf("Authorization" to "Bearer must-strip","x-api-key" to "must-strip","anthropic-version" to "bad","anthropic-workspace-id" to "secret-workspace"))
            val public=server.takeRequest();for(header in listOf("Authorization","x-api-key","anthropic-version","anthropic-workspace-id"))assertNull(public.getHeader(header));assertEquals(1,observed)
        }
    }
    @Test fun openAiUsesModernTokenLimitWithoutUnsupportedTemperatureAndDoesNotStore(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
            assertEquals("OK",AiRemoteProvider(AiTestFixtures.gateway(server),config(server,AiProtocol.OPENAI)).text("System","User",256))
            val req=server.takeRequest();assertEquals("/v1/chat/completions",req.path)
            val body=JsonParser.parseString(req.body.readUtf8()).asJsonObject
            assertEquals(256,body["max_completion_tokens"].asInt);assertFalse(body.has("max_tokens"));assertFalse(body.has("temperature"));assertFalse(body["store"].asBoolean)
            assertNull(req.getHeader("anthropic-version"));assertNull(req.getHeader("anthropic-workspace-id"))
        }
    }
    @Test fun deepSeekAndOptionalThinkingGlmAskForDirectAnswersWithoutLeakingReasoning(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            for(preset in listOf(AiProviderPreset.DEEPSEEK,AiProviderPreset.ZAI)) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"reasoning_content":"hidden","content":"Useful answer"}}]}"""))
                assertEquals("Useful answer",AiRemoteProvider(AiTestFixtures.gateway(server),config(server,preset.protocol,preset)).text("","Question"))
                val body=JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
                assertEquals(preset.textModel,body["model"].asString);assertEquals("disabled",body.getAsJsonObject("thinking")["type"].asString)
            }
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Answer"}}]}"""))
            AiRemoteProvider(AiTestFixtures.gateway(server),config(server,AiProtocol.COMPATIBLE,AiProviderPreset.ZAI).copy(model="glm-5.3")).text("","Question")
            assertFalse(JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject.has("thinking"))
        }
    }
    @Test fun namedProviderCapabilitiesDoNotInventEmbeddingEndpoints(): Unit=runBlocking {
        assertEquals("https://api.openai.com/v1",AiProviderPreset.OPENAI.baseUrl)
        assertEquals("https://api.anthropic.com/v1",AiProviderPreset.ANTHROPIC.baseUrl)
        assertEquals("https://api.deepseek.com",AiProviderPreset.DEEPSEEK.baseUrl)
        assertEquals("https://api.z.ai/api/paas/v4",AiProviderPreset.ZAI.baseUrl)
        MockWebServer().use {server->server.start()
            for(preset in listOf(AiProviderPreset.ANTHROPIC,AiProviderPreset.DEEPSEEK,AiProviderPreset.ZAI)) {
                assertFalse(preset.supports(AiKind.EMBEDDING))
                assertThrows(IllegalArgumentException::class.java){runBlocking {AiRemoteProvider(AiTestFixtures.gateway(server),config(server,preset.protocol,preset)).embeddings(listOf("private"))}}
            }
            assertEquals(0,server.requestCount)
        }
        assertTrue(AiProviderPreset.OPENAI.supports(AiKind.EMBEDDING));assertTrue(AiProviderPreset.CUSTOM.supports(AiKind.EMBEDDING))
    }
    @Test fun anthropicRedirectsAndPrivateTorRequestsNeverReplayThePayload(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val gateway=AiTestFixtures.gateway(server)
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",server.url("/other")))
            assertThrows(IOException::class.java){runBlocking {AiRemoteProvider(gateway,config(server,AiProtocol.ANTHROPIC)).text("","private")}}
            assertEquals(1,server.requestCount)
            gateway.configure(NetworkPolicy(tor=true,ready=false))
            assertThrows(IOException::class.java){runBlocking {AiRemoteProvider(gateway,config(server,AiProtocol.ANTHROPIC)).text("","private")}}
            assertEquals(1,server.requestCount)
            assertThrows(IllegalArgumentException::class.java){config(server,AiProtocol.ANTHROPIC).copy(workspaceId="bad\r\ninjection")}
        }
    }
    @Test fun claudeNeverReturnsThinkingOnlyOrMalformedContentAsTheAnswer(): Unit=runBlocking {
        MockWebServer().use {server->server.start()
            for(content in listOf("""[{"type":"thinking","thinking":"hidden"}]""","""[{"type":"text","text":{}}]""","null")) {
                server.enqueue(MockResponse().setBody("""{"content":$content}"""))
                assertThrows(IOException::class.java){runBlocking {AiRemoteProvider(AiTestFixtures.gateway(server),config(server,AiProtocol.ANTHROPIC)).text("","Question")}}
            }
        }
    }
    @Test fun routingIsLocalFirstByDefaultButExplicitProviderChoiceDoesNotNeedLocalDeletion() {
        assertTrue(AiModelRouting.useDevice(AiRouting.LOCAL_FIRST,true,false))
        assertTrue(AiModelRouting.useDevice(AiRouting.LOCAL_FIRST,true,true))
        assertFalse(AiModelRouting.useDevice(AiRouting.LOCAL_FIRST,false,false))
        assertThrows(IllegalStateException::class.java){AiModelRouting.useDevice(AiRouting.LOCAL_FIRST,false,true)}
        for(installed in listOf(false,true))for(chosen in listOf(false,true))assertFalse(AiModelRouting.useDevice(AiRouting.PROVIDER_ONLY,installed,chosen))
        assertTrue(AiModelRouting.useDevice(AiRouting.DEVICE_ONLY,true,false))
        assertThrows(IllegalStateException::class.java){AiModelRouting.useDevice(AiRouting.DEVICE_ONLY,false,false)}
    }
}
