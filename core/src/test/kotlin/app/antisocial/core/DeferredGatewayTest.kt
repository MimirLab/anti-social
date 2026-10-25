/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class DeferredGatewayTest {
    private fun gateway()=NetworkGateway(UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject),object: RequestFilter {
        override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)
    },true)
    @Test fun aContentRequestCancelsDeferredWorkWithoutCancellingItsCaller(): Unit=runBlocking {
        MockWebServer().use {slow->MockWebServer().use {fast->
            slow.start();fast.start();slow.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));fast.enqueue(MockResponse().setBody("content"))
            val gateway=gateway()
            val name=async(Dispatchers.IO){gateway.deferredRead {gateway.text(slow.url("/profile").toString())}}
            assertNotNull(slow.takeRequest(5,TimeUnit.SECONDS));assertFalse(gateway.hasForegroundRequests)
            assertEquals("content",gateway.text(fast.url("/post").toString()).text)
            withTimeout(5_000){name.join()};assertTrue(name.isCancelled);assertTrue(currentCoroutineContext().isActive)
            withTimeout(5_000){while(gateway.hasForegroundRequests)delay(10)}
            fast.enqueue(MockResponse().setBody("name"))
            assertEquals("name",gateway.deferredRead {gateway.text(fast.url("/profile").toString())}.text)
            assertFalse(gateway.hasForegroundRequests)
        }}
    }
    @Test fun localSearchCanStreamDeferredDetailsUntilActualContentTakesPriority():Unit=runBlocking {
        MockWebServer().use {search->MockWebServer().use {details->MockWebServer().use {content->
            search.start();details.start();content.start()
            search.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            details.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            content.enqueue(MockResponse().setBody("article"))
            val gateway=gateway()
            val acquisition=async(Dispatchers.IO){gateway.discoveryRead {gateway.text(search.url("/places").toString())}}
            assertNotNull(search.takeRequest(5,TimeUnit.SECONDS));assertFalse(gateway.hasForegroundRequests)
            val enrichment=async(Dispatchers.IO){gateway.discoveryRead {gateway.deferredRead {gateway.text(details.url("/contact").toString())}}}
            assertNotNull(details.takeRequest(5,TimeUnit.SECONDS));assertTrue(enrichment.isActive)
            assertFalse(gateway.hasForegroundRequests)
            assertEquals("article",gateway.text(content.url("/article").toString()).text)
            withTimeout(5_000){enrichment.join()};assertTrue(enrichment.isCancelled)
            assertTrue(acquisition.isActive);acquisition.cancelAndJoin()
            withTimeout(5_000){while(gateway.hasForegroundRequests)delay(10)}
        }}}
    }
    @Test fun mediaUsingTheSharedClientAlsoPreventsDeferredLookups(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val gateway=gateway();val media=gateway.client().newCall(Request.Builder().url(server.url("/video")).build())
            val reading=launch(Dispatchers.IO){try {media.execute().use {it.body?.bytes()}}catch(_: IOException){}}
            assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));assertTrue(gateway.hasForegroundRequests)
            val name=async(Dispatchers.IO){gateway.deferredRead {gateway.text(server.url("/profile").toString())}}
            withTimeout(5_000){name.join()};assertTrue(name.isCancelled)
            assertEquals(1,server.requestCount);media.cancel();reading.join()
            withTimeout(5_000){while(gateway.hasForegroundRequests)delay(10)}
        }
    }
    @Test fun deferredReadsKeepTheSameTorAndContentBudgets(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val gateway=gateway();gateway.configure(NetworkPolicy(tor=true,ready=false))
            assertThrows(IOException::class.java){runBlocking {gateway.deferredRead {gateway.text(server.url("/profile").toString())}}}
            assertEquals(0,server.requestCount);gateway.configure(NetworkPolicy())
            server.enqueue(MockResponse().setBody("x".repeat(2000)))
            assertThrows(IOException::class.java){runBlocking {gateway.deferredRead {gateway.boundedHealthRead(1024){gateway.text(server.url("/profile").toString())}}}}
            assertEquals(1,server.requestCount)
        }
    }
}
