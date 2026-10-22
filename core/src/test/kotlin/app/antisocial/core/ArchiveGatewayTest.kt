package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

class ArchiveGatewayTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    private fun scope(server: MockWebServer)=setOf(server.url("/").toString())
    private fun downloadFile(): File {
        val directory=File("/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/archive-unit-tests")
        check(directory.isDirectory||directory.mkdirs())
        return File(directory,"blocked-${UUID.randomUUID()}.bin")
    }

    @Test fun scopedIndexAndReplayStayUsableWithinTheSelectedArchive(): Unit=runBlocking {
        MockWebServer().use {archive->
            archive.start()
            archive.enqueue(MockResponse().setBody("[[\"timestamp\",\"original\"],[\"20200101000000\",\"https://x.com/person/status/42\"]]"))
            archive.enqueue(MockResponse().setResponseCode(302).addHeader("Location","/web/20200101000000id_/https://x.com/person/status/42"))
            archive.enqueue(MockResponse().setBody("<article>The older public statement</article>"))
            val gateway=NetworkGateway(cleaner,permit,true)
            val index=gateway.text(archive.url("/cdx/search/cdx?url=https%3A%2F%2Fx.com%2Fperson%2Fstatus%2F42").toString(),allowedOrigins=scope(archive))
            assertTrue(index.text.contains("20200101000000"))
            val replay=gateway.text(archive.url("/web/oldest/https://x.com/person/status/42").toString(),allowedOrigins=scope(archive))
            assertEquals("<article>The older public statement</article>",replay.text)
            assertEquals(archive.url("/web/20200101000000id_/https://x.com/person/status/42").toString(),replay.finalUrl)
            assertEquals(3,archive.requestCount)
            repeat(3) {
                val request=archive.takeRequest()
                assertNull(request.getHeader("X-AntiSocial-Source"))
                assertNull(request.getHeader("Cookie"))
                assertNull(request.getHeader("Authorization"))
                assertTrue(request.getHeader("User-Agent")!!.startsWith("AntiSocial/"))
            }
        }
    }

    @Test fun aScopedFirstRequestCannotStartOnAnotherOrigin(): Unit=runBlocking {
        MockWebServer().use {archive->MockWebServer().use {original->
            archive.start();original.start();original.enqueue(MockResponse().setBody("must not be read"))
            val gateway=NetworkGateway(cleaner,permit,true)
            val failure=assertThrows(IOException::class.java) {runBlocking {gateway.text(original.url("/live-post").toString(),allowedOrigins=scope(archive))}}
            assertTrue(failure.message!!.contains("selected archive"))
            assertEquals(0,archive.requestCount);assertEquals(0,original.requestCount)
        }}
    }

    @Test fun anArchiveRedirectCannotContactTheLiveSiteOrAnotherPort(): Unit=runBlocking {
        MockWebServer().use {archive->MockWebServer().use {original->
            archive.start();original.start()
            archive.enqueue(MockResponse().setResponseCode(302).addHeader("Location",original.url("/live-post")))
            original.enqueue(MockResponse().setBody("must not be read"))
            val gateway=NetworkGateway(cleaner,permit,true)
            val failure=assertThrows(IOException::class.java) {runBlocking {gateway.text(archive.url("/web/capture").toString(),allowedOrigins=scope(archive))}}
            assertTrue(failure.message!!.contains("selected archive"))
            assertEquals(1,archive.requestCount);assertEquals(0,original.requestCount)
        }}
    }

    @Test fun filterRewritesCannotEscapeTheArchiveBeforeAnyConnection(): Unit=runBlocking {
        MockWebServer().use {archive->MockWebServer().use {original->
            archive.start();original.start();original.enqueue(MockResponse().setBody("must not be read"))
            val checks=AtomicInteger()
            val gateway=NetworkGateway(cleaner,object: RequestFilter {
                override fun check(url: String,sourceUrl: String,type: String): FilterDecision {
                    checks.incrementAndGet();return FilterDecision(false,rewrittenUrl=original.url("/live-post").toString())
                }
            },true)
            val failure=assertThrows(IOException::class.java) {runBlocking {gateway.text(archive.url("/web/capture").toString(),allowedOrigins=scope(archive))}}
            assertTrue(failure.message!!.contains("selected archive"))
            assertEquals(1,checks.get());assertEquals(0,archive.requestCount);assertEquals(0,original.requestCount)
        }}
    }

    @Test fun knownArchiveContextPinsTextMediaDownloadsAndRawRequestsBeforeNetworking(): Unit=runBlocking {
        for(source in listOf("https://web.archive.org/web/20200101000000/https://x.com/person/status/42","https://arquivo.pt/wayback/20200101000000/https://x.com/person/status/42")) {
            MockWebServer().use {original->
                original.start();repeat(8){original.enqueue(MockResponse().setBody("must not be read"))}
                val checks=AtomicInteger()
                val gateway=NetworkGateway(cleaner,object: RequestFilter {
                    override fun check(url: String,sourceUrl: String,type: String): FilterDecision {checks.incrementAndGet();return FilterDecision(false)}
                },true)
                val live=original.url("/live-post").toString();val file=downloadFile()
                try {
                    val reads=listOf<suspend ()->Unit>(
                        {gateway.text(live,sourceUrl=source);Unit},
                        {gateway.bytes(live,sourceUrl=source);Unit},
                        {gateway.sample(live,sourceUrl=source);Unit},
                        {gateway.download(live,source,file);Unit},
                        {gateway.form(live,mapOf("query" to "public"),sourceUrl=source);Unit},
                        {gateway.json(live,JsonObject().apply {addProperty("limit",1)},sourceUrl=source);Unit},
                        {gateway.pagedText(live,source);Unit},
                        {gateway.client().newCall(Request.Builder().url(live).header("X-AntiSocial-Source",source).build()).execute().use {it.body?.string()};Unit}
                    )
                    for(read in reads) {
                        val failure=assertThrows(IOException::class.java){runBlocking {read()}}
                        assertTrue(failure.message!!.contains("selected archive"))
                    }
                    assertEquals(0,checks.get());assertEquals(0,original.requestCount)
                    assertFalse(file.exists())
                } finally {file.delete()}
            }
        }
    }

    @Test fun productionGatewayAlsoRejectsPrivateReplayDestinationsBeforeNetworking(): Unit=runBlocking {
        MockWebServer().use {original->
            original.start();original.enqueue(MockResponse().setBody("must not be read"))
            val gateway=NetworkGateway(cleaner,permit)
            val destination=original.url("/live-post").newBuilder().scheme("https").build().toString()
            val failure=assertThrows(IOException::class.java){runBlocking {gateway.text(destination,sourceUrl="https://web.archive.org/web/20200101/https://x.com/person/status/42")}}
            assertTrue(failure.message!!.contains("Private destinations"))
            assertEquals(0,original.requestCount)
        }
    }

    @Test fun selectedTorNeverFallsBackToDirectArchiveOrMediaRequests(): Unit=runBlocking {
        MockWebServer().use {archive->
            archive.start();repeat(4){archive.enqueue(MockResponse().setBody("must not be read"))}
            val gateway=NetworkGateway(cleaner,permit,true)
            gateway.configure(NetworkPolicy(tor=true,ready=false))
            val url=archive.url("/web/capture").toString()
            val file=downloadFile()
            try {
                val reads=listOf<suspend ()->Unit>(
                    {gateway.text(url,allowedOrigins=scope(archive));Unit},
                    {gateway.bytes(url);Unit},
                    {gateway.sample(url);Unit},
                    {gateway.download(url,url,file);Unit},
                    {gateway.client().newCall(Request.Builder().url(url).build()).execute().close();Unit}
                )
                for(read in reads) {
                    val failure=assertThrows(IOException::class.java){runBlocking {read()}}
                    assertTrue(failure.message!!.contains("Tor is connecting"))
                }
                assertFalse(file.exists());assertEquals(0,archive.requestCount)
            } finally {file.delete()}
        }
    }

    @Test fun explicitTorArchiveReadsAlsoFailClosedWithoutChangingDirectMode(): Unit=runBlocking {
        MockWebServer().use {archive->
            archive.start();archive.enqueue(MockResponse().setBody("must not be read"))
            val gateway=NetworkGateway(cleaner,permit,true)
            assertFalse(gateway.currentPolicy().tor)
            val failure=assertThrows(IOException::class.java) {runBlocking {gateway.text(archive.url("/cdx/search/cdx").toString(),route=NetworkRoute.TOR,allowedOrigins=scope(archive))}}
            assertTrue(failure.message!!.contains("Tor is connecting"))
            assertFalse(gateway.currentPolicy().tor);assertEquals(0,archive.requestCount)
        }
    }

    @Test fun knownArchiveReadIndexesAndReplaysReachTransportForGetAndHead() {
        val gateway=NetworkGateway(cleaner,permit)
        val received=CopyOnWriteArrayList<String>()
        val client=gateway.client().newBuilder().addInterceptor {chain->
            received+=chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("archived public data".toResponseBody()).build()
        }.build()
        val urls=listOf(
            "https://web.archive.org/cdx/search/cdx?url=https%3A%2F%2Fx.com%2Fperson%2Fstatus%2F42&matchType=exact",
            "https://web.archive.org/web/20200101120000id_/https://x.com/person/status/42",
            "https://arquivo.pt/wayback/cdx?url=https%3A%2F%2Fx.com%2Fperson%2Fstatus%2F42&matchType=exact",
            "https://arquivo.pt/noFrame/replay/20200101120000id_/https://x.com/person/status/42"
        )
        for(url in urls)for(method in listOf("GET","HEAD")) {
            client.newCall(Request.Builder().url(url).method(method,null).build()).execute().use {response->assertEquals(200,response.code)}
        }
        assertEquals(8,received.size)
        assertEquals(urls.flatMap {listOf(it,it)},received)
    }

    @Test fun sameArchiveRedirectToSubmissionNeverReachesTransport() {
        for((replay,control) in listOf(
            "https://web.archive.org/web/20200101120000id_/https://x.com/person/status/42" to "https://web.archive.org/save/https://x.com/person/status/42",
            "https://arquivo.pt/noFrame/replay/20200101120000id_/https://x.com/person/status/42" to "https://arquivo.pt/save/https://x.com/person/status/42"
        )) {
            val gateway=NetworkGateway(cleaner,permit)
            val received=CopyOnWriteArrayList<String>()
            val client=gateway.client().newBuilder().addInterceptor {chain->
                received+=chain.request().url.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found").header("Location",control).body("".toResponseBody()).build()
            }.build()
            val failure=assertThrows(IOException::class.java){client.newCall(Request.Builder().url(replay).build()).execute().close()}
            assertTrue(failure.message!!.contains("read-only"))
            assertEquals(listOf(replay),received)
        }
    }

    @Test fun sameArchiveControlFilterRewriteIsBlockedBeforeTransport() {
        val replay="https://web.archive.org/web/20200101120000id_/https://x.com/person/status/42"
        val gateway=NetworkGateway(cleaner,object: RequestFilter {
            override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl="https://web.archive.org/save/https://x.com/person/status/42")
        })
        val received=AtomicInteger()
        val client=gateway.client().newBuilder().addInterceptor {chain->
            received.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("must not be read".toResponseBody()).build()
        }.build()
        val failure=assertThrows(IOException::class.java){client.newCall(Request.Builder().url(replay).header("X-AntiSocial-Source",replay).build()).execute().close()}
        assertTrue(failure.message!!.contains("read-only"));assertEquals(0,received.get())
    }

    @Test fun rawArchiveContextCannotSubmitOrReadControlEndpoints() {
        val gateway=NetworkGateway(cleaner,permit)
        val received=AtomicInteger()
        val client=gateway.client().newBuilder().addInterceptor {chain->
            received.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("must not be read".toResponseBody()).build()
        }.build()
        for(url in listOf(
            "https://web.archive.org/save/https://x.com/person/status/42",
            "https://web.archive.org/manage/",
            "https://web.archive.org/cdx/search/cdx/save?url=https://x.com/person/status/42",
            "https://arquivo.pt/save/https://x.com/person/status/42",
            "https://arquivo.pt/wayback/cdx/../save/https://x.com/person/status/42"
        )) {
            val source=if(url.contains("arquivo.pt"))"https://arquivo.pt/noFrame/replay/20200101120000id_/https://x.com/person/status/42"else "https://web.archive.org/web/20200101120000id_/https://x.com/person/status/42"
            val failure=assertThrows(IOException::class.java){client.newCall(Request.Builder().url(url).header("X-AntiSocial-Source",source).build()).execute().close()}
            assertTrue(url,failure.message!!.contains("read-only"))
        }
        val post=Request.Builder().url("https://web.archive.org/cdx/search/cdx").post("url=https://x.com/person/status/42".toRequestBody()).build()
        val failure=assertThrows(IOException::class.java){client.newCall(post).execute().close()}
        assertTrue(failure.message!!.contains("read-only"));assertEquals(0,received.get())
    }

    @Test fun publicArchiveControlRequestIsRejectedBeforeClientNetworking(): Unit=runBlocking {
        val checks=AtomicInteger()
        val gateway=NetworkGateway(cleaner,object: RequestFilter {
            override fun check(url: String,sourceUrl: String,type: String): FilterDecision {checks.incrementAndGet();return FilterDecision(false)}
        })
        val stages=CopyOnWriteArrayList<String>();gateway.onNetworkEvent={stages+=it.stage}
        val failure=assertThrows(IOException::class.java){runBlocking {gateway.text("https://web.archive.org/save/https://x.com/person/status/42",allowedOrigins=setOf("https://web.archive.org"))}}
        assertTrue(failure.message!!.contains("read-only"));assertEquals(0,checks.get());assertTrue(stages.isEmpty())
    }
}
