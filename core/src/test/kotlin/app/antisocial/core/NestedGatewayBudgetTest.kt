package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.File
import java.nio.file.Files

/** An enrichment helper must not replace the allowance of the search that called it. */
class NestedGatewayBudgetTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private val permit=object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)}
    @Test fun streamedFilesShareTheirParentsAggregateByteAllowanceAndDiscardPartialOutput()=runBlocking {
        val directory=Files.createTempDirectory("download-budget-").toFile()
        try {MockWebServer().use {server->server.start();repeat(3){server.enqueue(MockResponse().setChunkedBody("a".repeat(700),100))}
            val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/recording").toString()
            val first=File(directory,"first");val second=File(directory,"second")
            assertThrows(IOException::class.java){runBlocking {gateway.boundedMediaRead(maxBytes=1024,maxRequests=4) {
                gateway.download(url,url,first,maxBytes=2048)
                gateway.boundedMediaRead(maxBytes=4096){gateway.download(url,url,second,maxBytes=2048)}
            }}}
            assertEquals(700,first.length());assertFalse(second.exists())
            assertEquals(2,server.requestCount)
            val fresh=File(directory,"fresh")
            assertEquals(700,gateway.download(url,url,fresh,maxBytes=2048));assertEquals(700,fresh.length())
        }}finally {directory.deleteRecursively()}
    }
    @Test fun streamedFileRedirectsCannotEscapeTheRequestAllowance()=runBlocking {
        val directory=Files.createTempDirectory("download-redirect-").toFile()
        try {MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/recording"))
            server.enqueue(MockResponse().setBody("complete"))
            val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/redirect").toString()
            val file=File(directory,"recording")
            assertThrows(IOException::class.java){runBlocking {gateway.boundedMediaRead(maxRequests=1){gateway.download(url,url,file)}}}
            assertEquals(1,server.requestCount);assertFalse(file.exists())
        }}finally {directory.deleteRecursively()}
    }
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
