package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Request

class PublicGatewayTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    @Test fun redirectedDocumentsDoNotForwardValidatorsToAnotherResource():Unit=runBlocking {
        MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/another-resource"))
            server.enqueue(MockResponse().setBody("Public replacement"))
            val gateway=NetworkGateway(cleaner,permit,true)
            gateway.extractorDocument(server.url("/first-resource").toString(),headers=mapOf("If-None-Match" to "\"private-tag\"","If-Modified-Since" to "Sun, 18 Oct 2026 12:30:00 GMT"))
            val first=server.takeRequest();val second=server.takeRequest()
            assertEquals("\"private-tag\"",first.getHeader("If-None-Match"))
            assertNull("A redirect target has not issued this ETag",second.getHeader("If-None-Match"))
            assertNull(second.getHeader("If-Modified-Since"))
        }
    }
    @Test fun aFilterRewriteDropsValidatorsBeforeTheOnlyHttpExchange():Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("Read the actual destination"))
            val gateway=NetworkGateway(cleaner,object:RequestFilter {
                override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false,rewrittenUrl=server.url("/new-resource").toString().takeIf {url.contains("/old-resource")})
            },true)
            val response=gateway.extractorDocument(server.url("/old-resource").toString(),headers=mapOf("If-None-Match" to "\"old\""))
            val request=server.takeRequest();assertEquals("/new-resource",request.path);assertNull(request.getHeader("If-None-Match"));assertFalse(response.conditional)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun aRedirectBackToTheOriginalUriStillReportsAnUnconditionalResponse():Unit=runBlocking {
        MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/middle"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/original"))
            server.enqueue(MockResponse().setResponseCode(304))
            val gateway=NetworkGateway(cleaner,permit,true)
            val response=gateway.extractorDocument(server.url("/original").toString(),headers=mapOf("If-None-Match" to "\"old\""))
            assertEquals(304,response.status);assertFalse(response.conditional)
            assertNotNull(server.takeRequest().getHeader("If-None-Match"))
            repeat(2){assertNull(server.takeRequest().getHeader("If-None-Match"))}
        }
    }
    @Test fun staleClientsAndExplicitSessionsCannotReviveAfterAModeRoundTrip():Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("Fresh session"))
            val gateway=NetworkGateway(cleaner,permit,true);val before=gateway.currentSession();val client=gateway.client()
            gateway.configure(NetworkPolicy());assertSame(before,gateway.currentSession())
            gateway.configureTor(NetworkPolicy(tor=true,socksPort=9050,ready=true));assertSame("Search Tor readiness must not invalidate direct content",before,gateway.currentSession())
            gateway.configure(NetworkPolicy(tor=true));gateway.configure(NetworkPolicy())
            assertNotSame(before,gateway.currentSession());val url=server.url("/article").toString()
            assertThrows(IOException::class.java){client.newCall(Request.Builder().url(url).build()).execute()}
            assertThrows(kotlinx.coroutines.CancellationException::class.java){runBlocking {gateway.extractorDocument(url,expectedSession=before)}}
            assertThrows(kotlinx.coroutines.CancellationException::class.java){runBlocking {gateway.formInSession(url,mapOf("q" to "fixture"),url,expectedSession=before)}}
            assertEquals(0,server.requestCount)
            assertEquals("Fresh session",gateway.text(url).text);assertEquals(1,server.requestCount)
        }
    }
    @Test fun switchingContentModeRetainsAnUnchangedWarmTorSearchClient() {
        val gateway=NetworkGateway(cleaner,permit,true);val tor=NetworkPolicy(tor=true,socksPort=9050,ready=true)
        gateway.configureTor(tor)
        val session=gateway.currentSession(NetworkRoute.TOR);val warm=gateway.client(NetworkRoute.TOR)
        gateway.configure(tor)
        assertSame(session,gateway.currentSession(NetworkRoute.TOR));assertSame(warm,gateway.client(NetworkRoute.TOR))
        gateway.configure(NetworkPolicy())
        assertSame(session,gateway.currentSession(NetworkRoute.TOR));assertSame(warm,gateway.client(NetworkRoute.TOR))
        gateway.configureTor(tor.copy(socksPort=9051))
        assertNotSame(session,gateway.currentSession(NetworkRoute.TOR));assertNotSame(warm,gateway.client(NetworkRoute.TOR))
    }
    @Test fun publicJsonAndGraphqlFormCarryOnlyExplicitReadDataAndNoAccountCredentials(): Unit=runBlocking {
        MockWebServer().use {server->server.start();repeat(2){server.enqueue(MockResponse().setBody("{}"))}
            val gateway=NetworkGateway(cleaner,permit,true);val fields=JsonObject().apply {addProperty("username","alice");addProperty("limit",20)}
            val headers=mapOf("Cookie" to "session=account","Authorization" to "Bearer account","X-FB-LSD" to "public-page-token","User-Agent" to "Untrusted")
            gateway.json(server.url("/api/users/show").toString(),fields,headers=headers)
            val json=server.takeRequest();assertEquals("POST",json.method);assertTrue(json.getHeader("Content-Type")!!.startsWith("application/json"));assertEquals("alice",JsonParser.parseString(json.body.readUtf8()).asJsonObject["username"].asString)
            assertNull(json.getHeader("Cookie"));assertNull(json.getHeader("Authorization"));assertEquals("public-page-token",json.getHeader("X-FB-LSD"));assertTrue(json.getHeader("User-Agent")!!.startsWith("AntiSocial/"))
            gateway.form(server.url("/api/graphql/").toString(),mapOf("doc_id" to "public-read","variables" to "{\"count\":3}"),headers=headers)
            val form=server.takeRequest();assertEquals("POST",form.method);assertTrue(form.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"));assertTrue(form.body.readUtf8().contains("doc_id=public-read"));assertNull(form.getHeader("Cookie"));assertNull(form.getHeader("Authorization"));assertEquals("public-page-token",form.getHeader("X-FB-LSD"))
        }
    }
    @Test fun originalPolicyAppliesToRegisteredFederatedServersAndTheirMediaBeforeAnyConnection(): Unit=runBlocking {
        val checked=AtomicInteger();val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {checked.incrementAndGet();return FilterDecision(false)}})
        gateway.registerOriginalUrl("https://social.example/@alice");gateway.registerOriginalUrl("https://media.example/files/picture.jpg");gateway.originalFallback=false
        for(url in listOf("https://social.example/api/v1/accounts/lookup?acct=alice","https://sub.social.example/public","https://media.example/files/picture.jpg","https://www.facebook.com/api/graphql/")) {
            val text=assertThrows(IOException::class.java){runBlocking {gateway.text(url)}};assertTrue(text.message!!.contains("Original-site access"))
            val bytes=assertThrows(IOException::class.java){runBlocking {gateway.bytes(url)}};assertTrue(bytes.message!!.contains("Original-site access"))
            val sample=assertThrows(IOException::class.java){runBlocking {gateway.sample(url)}};assertTrue(sample.message!!.contains("Original-site access"))
        }
        assertEquals(0,checked.get())
    }
    @Test fun healthReadBudgetIsCumulativeAcrossMetadataAndContentAndDoesNotLeakIntoOrdinaryReads(): Unit=runBlocking {
        MockWebServer().use {server->server.start();repeat(3){server.enqueue(MockResponse().setBody("a".repeat(900)))}
            val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/canary").toString()
            val error=assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead(1500) {assertEquals(900,gateway.text(url).text.length);gateway.text(url)}}}
            assertTrue(error.message!!.contains("health-check budget"));assertEquals(900,gateway.text(url).text.length);assertEquals(3,server.requestCount)
        }
    }
    @Test fun healthBudgetAlsoStopsUnknownLengthChunkedResponses(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setChunkedBody("x".repeat(3000),300))
            val gateway=NetworkGateway(cleaner,permit,true)
            val error=assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead(1024) {gateway.text(server.url("/canary").toString())}}}
            assertTrue(error.message!!.contains("health-check budget"))
        }
    }
    @Test fun selectedTorFailsClosedForJsonFormsAndCanariesBeforeDirectTraffic(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val gateway=NetworkGateway(cleaner,permit,true);gateway.configure(NetworkPolicy(tor=true,ready=false))
            val url=server.url("/public").toString();val fields=JsonObject().apply {addProperty("limit",1)}
            assertThrows(IOException::class.java){runBlocking {gateway.json(url,fields)}}
            assertThrows(IOException::class.java){runBlocking {gateway.form(url,mapOf("limit" to "1"))}}
            assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead {gateway.text(url)}}}
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun passiveDiscoveryDocumentsUseTheirSmallerAllowanceAndExplicitTorFailsClosed():Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("x".repeat(8192)))
            val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/public-calendar").toString()
            assertThrows(IOException::class.java){runBlocking {gateway.extractorDocument(url,maxBytes=1024)}}
            assertEquals(1,server.requestCount)
            assertThrows(IOException::class.java){runBlocking {gateway.extractorDocument(url,maxBytes=1024,route=NetworkRoute.TOR)}}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun oversizedPublicQueriesAndHeaderSetsAreRejectedBeforeNetworking(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/public").toString()
            assertThrows(IllegalArgumentException::class.java){runBlocking {gateway.json(url,JsonObject().apply {addProperty("query","漢".repeat(6000))})}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {gateway.form(url,mapOf("query" to "small"),headers=mapOf("X-Read" to "a".repeat(5000)))}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {gateway.boundedHealthRead(1024*1024+1) {gateway.text(url)}}}
            assertEquals(0,server.requestCount)
        }
    }
}
