package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** An enrichment helper must not replace the allowance of the search that called it. */
class NestedGatewayBudgetTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private val permit=object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)}
    @Test fun separateNestedLookupsShareTheParentRequestLimit()=runBlocking {
        MockWebServer().use {server->server.start();repeat(5){server.enqueue(MockResponse().setBody("{}"))}
            val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/identity").toString()
            assertThrows(IOException::class.java){runBlocking {gateway.boundedMediaRead(maxRequests=2) {
                repeat(3){gateway.boundedMediaRead(maxRequests=5){gateway.text(url)}}
            }}}
            assertEquals(2,server.requestCount)
            assertEquals("{}",gateway.text(url).text)
        }
    }
    @Test fun childLimitsStillApplyWhenTheParentHasMoreCapacity()=runBlocking {
        MockWebServer().use {server->server.start();repeat(3){server.enqueue(MockResponse().setBody("{}"))}
            val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/identity").toString()
            assertThrows(IOException::class.java){runBlocking {gateway.boundedMediaRead(maxRequests=5) {
                gateway.boundedMediaRead(maxRequests=1){gateway.text(url);gateway.text(url)}
            }}}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun nestedDocumentAndMediaReadsShareAggregateBytes()=runBlocking {
        MockWebServer().use {server->server.start();repeat(3){server.enqueue(MockResponse().setChunkedBody("a".repeat(700),100))}
            val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/resource").toString()
            assertThrows(IOException::class.java){runBlocking {gateway.boundedMediaRead(maxBytes=1024,maxRequests=5) {
                gateway.boundedHealthRead(2048){gateway.extractorDocument(url)}
                gateway.boundedMediaRead(maxBytes=4096){gateway.bytes(url)}
            }}}
            assertEquals(2,server.requestCount)
        }
    }
}
