package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SearchPrivacyTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    @Test fun paginationFormsUseGatewayGuardsAndPreserveOnlySafeRedirects(): Unit=runBlocking {
        MockWebServer().use {server->MockWebServer().use {other->
            server.start();other.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location","/second"));server.enqueue(MockResponse().setBody("next block"))
            val gateway=NetworkGateway(cleaner,permit,true)
            val page=gateway.form(server.url("/html/").toString(),mapOf("q" to "site:x.com space","s" to "10"));assertEquals("next block",page.text);assertEquals(10,page.pageOffset)
            repeat(2){val request=server.takeRequest(1,TimeUnit.SECONDS)!!;assertEquals("POST",request.method);assertTrue(request.body.readUtf8().contains("q=site%3Ax.com%20space"));assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))}
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",other.url("/leak")))
            assertThrows(java.io.IOException::class.java){runBlocking {gateway.form(server.url("/html/").toString(),mapOf("q" to "private query"))}}
            assertEquals(0,other.requestCount)
            assertThrows(java.io.IOException::class.java){runBlocking {gateway.form("https://html.duckduckgo.com/html/",mapOf("q" to "private query"))}}
            gateway.originalFallback=false
            assertThrows(java.io.IOException::class.java){runBlocking {gateway.form("https://html.duckduckgo.com/html/",mapOf("q" to "private query"))}}
        }}
    }
    @Test fun connectionPreparationUsesHeadAndRejectsQueryText(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(204))
            val gateway=NetworkGateway(cleaner,permit,true)
            assertEquals(204,gateway.preconnect(server.url("/favicon.ico").toString()))
            val request=server.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("HEAD",request.method);assertEquals("/favicon.ico",request.path);assertEquals(0,request.bodySize)
            assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
            assertThrows(IllegalArgumentException::class.java){runBlocking {gateway.preconnect(server.url("/?q=must-stay-local").toString())}}
            assertEquals(1,server.requestCount)
        }
        val gateway=NetworkGateway(cleaner,permit)
        assertThrows(java.io.IOException::class.java){runBlocking {gateway.preconnect("https://html.duckduckgo.com/favicon.ico")}}
        assertEquals(0,gateway.protectionStats().checked)
        gateway.originalFallback=false
        val denied=assertThrows(java.io.IOException::class.java){runBlocking {gateway.preconnect("https://html.duckduckgo.com/favicon.ico")}}
        assertTrue(denied.message!!.contains("disabled"))
    }
    @Test fun protectedSearchFailsBeforeDnsWhileOrdinaryTrafficStillWorks(): Unit=runBlocking {
        MockWebServer().use {direct->
            direct.start();direct.enqueue(MockResponse().setBody("direct content"))
            val gateway=NetworkGateway(cleaner,permit,true)
            val client=gateway.client()
            for(url in listOf("https://html.duckduckgo.com/html/?q=fixture","https://www.bing.com/search?q=fixture","https://www.google.com/search?q=fixture")) {
                val error=assertThrows(java.io.IOException::class.java){runBlocking {gateway.text(url)}}
                assertTrue(error.message!!.contains("Tor is connecting"))
            }
            assertEquals(0,gateway.protectionStats().checked)
            assertFalse(gateway.currentPolicy().tor)
            assertEquals("direct content",gateway.text(direct.url("/profile").toString()).text)
            assertSame(client,gateway.client())
        }
    }
    @Test fun searchOnlyUsesSocksRemoteDnsAndRetainsIndependentDirectPool(): Unit=runBlocking {
        ServerSocket(0,1,InetAddress.getLoopbackAddress()).use {socks->
            socks.soTimeout=5000
            val received=CompletableFuture<Pair<String,String>>()
            val worker=Thread {
                try {socks.accept().use {socket->
                    socket.soTimeout=5000
                    val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                    assertEquals(5,input.readUnsignedByte());repeat(input.readUnsignedByte()){input.readByte()}
                    output.write(byteArrayOf(5,0));output.flush()
                    assertEquals(5,input.readUnsignedByte());assertEquals(1,input.readUnsignedByte());input.readByte();assertEquals(3,input.readUnsignedByte())
                    val host=ByteArray(input.readUnsignedByte()).also {input.readFully(it)}.toString(Charsets.UTF_8);input.readUnsignedShort()
                    output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80));output.flush()
                    val reader=input.bufferedReader();val headers=mutableListOf<String>();while(true){val line=reader.readLine() ?: break;if(line.isEmpty())break;headers+=line}
                    received.complete(host to headers.joinToString("\n"))
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 13\r\nConnection: close\r\n\r\nprivate query".toByteArray());output.flush()
                }}catch(e: Throwable){received.completeExceptionally(e)}
            }.apply {start()}
            val gateway=NetworkGateway(cleaner,permit,true);val direct=gateway.client()
            gateway.configureTor(NetworkPolicy(true,socks.localPort,true))
            assertEquals("private query",gateway.text("http://index.invalid/search?q=fixture",headers=mapOf("Cookie" to "secret","Authorization" to "secret"),route=NetworkRoute.TOR).text)
            val (host,headers)=received.get(5,TimeUnit.SECONDS)
            assertEquals("index.invalid",host);assertFalse(headers.contains("secret"));assertFalse(headers.contains("X-anti social"))
            assertSame(direct,gateway.client());assertFalse(gateway.currentPolicy().tor)
            gateway.configureTor(NetworkPolicy(tor=true))
            assertThrows(java.io.IOException::class.java){runBlocking {gateway.text("http://index.invalid/search?q=fixture",route=NetworkRoute.TOR)}}
            assertSame(direct,gateway.client());worker.join(1000)
        }
    }
    @Test fun directRedirectToSearchIsStoppedBeforeTheDestination(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","https://www.bing.com/search?q=fixture"))
            val gateway=NetworkGateway(cleaner,permit,true)
            val error=assertThrows(java.io.IOException::class.java){runBlocking {gateway.text(server.url("/").toString())}}
            assertTrue(error.message!!.contains("require Tor"));assertEquals(1,server.requestCount)
        }
    }
}
