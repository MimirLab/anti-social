package app.antisocial.core

import com.google.gson.JsonObject
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class YouTubeRecoveryGatewayTest {
    private val id="dQw4w9WgXcQ"
    private val index="https://web.archive.org/__wb/videoinfo?vtype=youtube&vid=$id"
    private val legacy="https://web.archive.org/web/0id_/http://wayback-fakeurl.archive.org/yt/$id"
    private fun gateway(test: Boolean=false,filter: RequestFilter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})=
        NetworkGateway(UrlCleaner(JsonObject().apply {add("providers",JsonObject())}),filter,test)

    @Test fun exactReadOnlyVideoIndexesAndLegacyRecordingReachGatewayTransport() {
        val gateway=gateway();val seen=mutableListOf<String>()
        val client=gateway.client().newBuilder().addInterceptor {chain->
            seen+=chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("fixture".toResponseBody()).build()
        }.build()
        for(url in listOf(index,legacy))for(method in listOf("GET","HEAD"))client.newCall(Request.Builder().url(url).method(method,null).build()).execute().close()
        assertEquals(listOf(index,index,legacy,legacy),seen)
    }
    @Test fun archiveVideoAllowanceCannotWriteSubmitOrReadAnUnboundEndpoint() {
        val gateway=gateway();var reached=0
        val client=gateway.client().newBuilder().addInterceptor {chain->
            reached++;Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("fixture".toResponseBody()).build()
        }.build()
        val invalid=listOf(
            "https://web.archive.org/__wb/videoinfo?vtype=youtube&vid=bad",
            "$index&other=unbounded",
            "https://web.archive.org/__wb/videoinfo?vtype=other&vid=$id",
            "https://web.archive.org/web/0id_/http://wayback-fakeurl.archive.org/yt/$id/extra",
            "$legacy?save=true",
            "https://web.archive.org/save/https://youtube.com/watch?v=$id"
        )
        for(url in invalid)assertThrows(IOException::class.java){client.newCall(Request.Builder().url(url).header("X-AntiSocial-Source",index).build()).execute().close()}
        assertThrows(IOException::class.java){client.newCall(Request.Builder().url(index).post("{}".toRequestBody()).build()).execute().close()}
        assertEquals(0,reached)
    }
    @Test fun videoIndexRedirectCannotQuietlyFallThroughToYouTube() {
        val gateway=gateway();val seen=mutableListOf<String>()
        val client=gateway.client().newBuilder().addInterceptor {chain->
            seen+=chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found").header("Location","https://www.youtube.com/watch?v=$id").body("".toResponseBody()).build()
        }.build()
        assertThrows(IOException::class.java){client.newCall(Request.Builder().url(index).build()).execute().close()}
        assertEquals(listOf(index),seen)
    }
    @Test fun discoveryTextAndMediaPrefixesShareActualHttpExchangeBudget()=runBlocking {
        MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setBody("{}"));server.enqueue(MockResponse().setBody("binary prefix"));server.enqueue(MockResponse().setBody("must not be read"))
            val gateway=gateway(true);val url=server.url("/archive").toString()
            val error=assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead(maxRequests=2) {
                gateway.text(url);assertEquals(13,gateway.sample(url,limit=4096).size);gateway.text(url)
            }}}
            assertTrue(error.message!!.contains("health-check budget"));assertEquals(2,server.requestCount)
        }
    }
    @Test fun mediaRedirectsAlsoSpendTheSharedExchangeBudget()=runBlocking {
        MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/final")))
            server.enqueue(MockResponse().setBody("must not be read"))
            val gateway=gateway(true)
            val error=assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead(maxRequests=1) {gateway.sample(server.url("/archive").toString(),limit=4096)}}}
            assertTrue(error.message!!.contains("health-check budget"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun textAndPrefixBytesShareOneDiscoveryBudget()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("x".repeat(700)));server.enqueue(MockResponse().setBody("x".repeat(400)))
            val gateway=gateway(true);val url=server.url("/archive").toString()
            val error=assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead(maxBytes=1024) {gateway.text(url);gateway.sample(url,limit=4096)}}}
            assertTrue(error.message!!.contains("health-check budget"));assertEquals(2,server.requestCount)
        }
    }
    @Test fun selectedUnavailableTorRejectsRecoveryWithoutDirectArchiveRequests()=runBlocking {
        val gateway=gateway().apply {configure(NetworkPolicy(tor=true))};var events=0;gateway.onNetworkEvent={events++}
        assertNull(YouTubeRecovery(gateway).recover(id));assertEquals(0,events);assertEquals(0L,gateway.protectionStats().checked)
    }
}
