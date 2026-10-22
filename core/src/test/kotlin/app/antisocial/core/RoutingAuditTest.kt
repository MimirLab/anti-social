package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Local HTTP fixtures exercise real gateway requests, parsing and cancellation. */
class RoutingAuditTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private fun gateway()=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
    private fun routes(root: String): DarwinRoutes {
        val catalog=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject
        catalog.add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"$root","fallbackOnly":false}]"""))
        catalog.add("searchSources",JsonArray())
        return DarwinRoutes(catalog,cleaner)
    }
    private fun post(body: String)="""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/123"></a><div class="tweet-content">$body</div></div>"""

    @Test fun postCapabilityProbeChecksThePostSelectedFromItsSentinelProfile(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(post("timeline card")));server.enqueue(MockResponse().setBody(post("selected full post")))
            val routes=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes)
            val result=pool.probe(routes.sources.single(),Capability.POST,force=true)
            assertEquals(HealthStatus.HEALTHY,result.status);assertEquals(2,server.requestCount)
            assertEquals("/NASA",server.takeRequest().path);assertEquals("/NASA/status/123",server.takeRequest().path)
            // Probes confirm usable data, but their two-request timing is not feed delivery timing.
            assertEquals(0,pool.learning.get(routes.sources.single(),Capability.POST,false)!!.deliverySamples)
        }
    }
    @Test fun parentTimeoutCancelsAnInFlightReadWithoutTrainingAnOutage(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(post("too late")).setBodyDelay(2,TimeUnit.SECONDS))
            val routes=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes)
            assertNull(withTimeoutOrNull(500){pool.load(routes.target(Platform.X,"NASA"),originalFallback=false)})
            assertEquals(1,server.requestCount)
            assertTrue(pool.results(Platform.X).isEmpty());assertTrue(pool.learning.snapshot().isEmpty())
        }
    }
    @Test fun deltaRetryAfterIsCarriedByRateLimitAndServiceUnavailableFailures(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val gateway=gateway();val url=server.url("/public").toString()
            for(code in listOf(429,503)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After","3600"))
                val failure=assertThrows(SourceFailure::class.java){runBlocking {gateway.text(url)}}
                assertEquals(if(code==429)HealthStatus.RATE_LIMITED else HealthStatus.UNAVAILABLE,failure.status)
                assertEquals(3_600_000L,failure.retryAfterMs)
            }
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun httpDateRetryAfterIsBoundedAndMalformedValuesAreIgnored(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val gateway=gateway();val url=server.url("/public").toString()
            val future=DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().plusSeconds(3600).atZone(ZoneOffset.UTC))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After",future))
            val futureFailure=assertThrows(SourceFailure::class.java){runBlocking {gateway.text(url)}}
            assertTrue(futureFailure.retryAfterMs!! in 3_590_000L..3_600_000L)
            for(value in listOf("999999999999999999999999999999999999","172800")) {
                server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After",value))
                assertEquals(86_400_000L,assertThrows(SourceFailure::class.java){runBlocking {gateway.text(url)}}.retryAfterMs)
            }
            for(value in listOf("tomorrow","-1","12.5","120 garbage")) {
                server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After",value))
                assertNull(assertThrows(SourceFailure::class.java){runBlocking {gateway.text(url)}}.retryAfterMs)
            }
        }
    }
    @Test fun serverRequestedPausePropagatesThroughSourcePoolIntoPersistentRouteEvidence(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","3600"))
            val routes=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes);val source=routes.sources.single()
            assertThrows(SourceFailure::class.java){runBlocking {pool.load(routes.target(Platform.X,"NASA"),originalFallback=false)}}
            val result=pool.results(Platform.X).single();assertEquals(3_600_000L,result.retryAfterMs)
            val until=pool.learning.get(source,Capability.TIMELINE,false)!!.cooldownUntil
            assertTrue(until-System.currentTimeMillis() in 3_590_000L..3_600_000L)
            val restored=AdaptiveRouting();restored.restore(pool.learning.snapshot())
            assertEquals(until,restored.get(source,Capability.TIMELINE,false)!!.cooldownUntil)
            assertFalse(restored.eligible(source,Capability.TIMELINE,false))
        }
    }
    @Test fun feedRateLimitDoesNotRetryAnotherFormatBeforeTheServerPause(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","3600"))
            // A format retry would consume this queued response and appear to work.
            server.enqueue(MockResponse().setBody(post("must remain unfetched")))
            val routes=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes);val source=routes.sources.single()
            assertThrows(SourceFailure::class.java){runBlocking {pool.load(routes.target(Platform.X,"NASA"),feed=true,originalFallback=false)}}
            assertEquals(1,server.requestCount);assertEquals("/NASA/rss",server.takeRequest().path)
            assertEquals(3_600_000L,pool.results(Platform.X).single().retryAfterMs)
            assertTrue(pool.learning.eligibleAt(source,Capability.FEED,false)-System.currentTimeMillis() in 3_590_000L..3_600_000L)
        }
    }
    @Test fun healthRequestBudgetCountsRedirectsAndSuccessiveReadsBeforeConnecting(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val gateway=gateway();val url=server.url("/start").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/middle"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/end"))
            server.enqueue(MockResponse().setBody("healthy"))
            val failure=assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead(maxRequests=2){gateway.text(url)}}}
            assertTrue(failure.message!!.contains("health-check budget"));assertEquals(2,server.requestCount)
            // Budget ends with its coroutine context; an ordinary user read is unaffected.
            assertEquals("healthy",gateway.text(server.url("/ordinary").toString()).text)
            repeat(3){server.enqueue(MockResponse().setBody("small"))}
            val nextFailure=assertThrows(IOException::class.java){runBlocking {gateway.boundedHealthRead(maxRequests=2){repeat(3){gateway.text(url)}}}}
            assertTrue(nextFailure.message!!.contains("health-check budget"));assertEquals(5,server.requestCount)
            assertThrows(IllegalArgumentException::class.java){runBlocking {gateway.boundedHealthRead(maxRequests=0){gateway.text(url)}}}
        }
    }
}
