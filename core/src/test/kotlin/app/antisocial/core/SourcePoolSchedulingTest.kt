package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual loopback Gateway reads, without live services or invented delivery evidence. */
class SourcePoolSchedulingTest {
    private class Fixture(frontends:List<String>):AutoCloseable {
        val servers=frontends.map {MockWebServer().apply {start()}}
        private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {
            override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)
        },true)
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {
            add("sources",JsonParser.parseString(servers.mapIndexed {i,s->
                """{"platform":"twitter","frontend":"${frontends[i]}","root":"${s.url("/").toString().trimEnd('/')}","fallbackOnly":${frontends[i]=="rsshub"}}"""
            }.joinToString(",","[","]")))
            add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes)
        val target=routes.target(Platform.X,"NASA")
        override fun close() {
            gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()
            servers.forEach {it.close()}
        }
    }
    private fun post(text:String)="""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/123"></a><div class="tweet-content">$text</div><span class="tweet-date"><a title="Oct 23, 2026 · 12:00 PM UTC"></a></span></div>"""
    private fun feed(dated:Boolean=true)="""<rss><channel><title>NASA</title><link>https://x.com/NASA</link><item><title>Public flight notice</title><link>https://x.com/NASA/status/123</link><description>Bring the required equipment.</description>${if(dated)"<pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate>"else ""}</item></channel></rss>"""

    @Test fun aStalledEarlierRouteDoesNotHoldAnAvailableLaterSlot():Unit=runBlocking {
        Fixture(List(4){"nitter"}).use {f->
            f.servers[0].enqueue(MockResponse().setResponseCode(503))
            f.servers[1].enqueue(MockResponse().setBody(post("stalled second route")).setBodyDelay(4,TimeUnit.SECONDS))
            f.servers[2].enqueue(MockResponse().setResponseCode(503))
            f.servers[3].enqueue(MockResponse().setBody(post("productive fourth route")))
            val page=withTimeout(3_000){f.pool.load(f.target,originalFallback=false)}
            assertEquals("productive fourth route",page.posts.single().body)
            assertEquals(1,f.servers[3].requestCount)
            assertTrue("Cancelling the losing slow read must not train an outage",f.pool.results(Platform.X).none {it.source==f.routes.sources[1]})
        }
    }
    @Test fun aDifferentUntriedFeedMethodPrecedesMoreMirrorsOfTheFailedMethod():Unit=runBlocking {
        Fixture(listOf("nitter","nitter","nitter","rsshub")).use {f->
            // The ordinary frontend tries its RSS endpoint and then HTML.
            repeat(3){i->repeat(2){f.servers[i].enqueue(MockResponse().setResponseCode(503))}}
            f.servers[3].enqueue(MockResponse().setBody(feed()))
            val page=f.pool.load(f.target,feed=true,originalFallback=false)
            assertEquals("rsshub",page.provenance!!.source.frontend)
            assertEquals(listOf(2,0,0,1),f.servers.map {it.requestCount})
        }
    }
    @Test fun atMostThreeCandidateReadsRunWhileALaterRouteWaitsForASlot():Unit=runBlocking {
        val release=CountDownLatch(1)
        try {Fixture(List(5){"nitter"}).use {f->
            f.servers[0].dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    check(release.await(8,TimeUnit.SECONDS));return MockResponse().setResponseCode(503)
                }
            }
            f.servers[1].enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            f.servers[2].enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            f.servers[3].enqueue(MockResponse().setBody(post("fourth uses the released slot")))
            val read=async(Dispatchers.IO) {f.pool.load(f.target,originalFallback=false)}
            try {
                repeat(3){assertNotNull(f.servers[it].takeRequest(3,TimeUnit.SECONDS))}
                delay(100)
                assertEquals(listOf(0,0),f.servers.drop(3).map {it.requestCount})
                release.countDown()
                assertEquals("fourth uses the released slot",withTimeout(3_000){read.await()}.posts.single().body)
                assertEquals(0,f.servers[4].requestCount)
            }finally {release.countDown();read.cancelAndJoin()}
        }}finally {release.countDown()}
    }
    @Test fun callerCancellationClosesTheActiveReadAndLeavesQueuedRoutesUnused():Unit=runBlocking {
        Fixture(List(6){"nitter"}).use {f->
            f.servers[0].enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val read=async(Dispatchers.IO) {f.pool.load(f.target,originalFallback=false)}
            try {assertNotNull(f.servers[0].takeRequest(2,TimeUnit.SECONDS))}
            finally {read.cancelAndJoin()}
            withTimeout(2_000){while(f.gateway.client().dispatcher.runningCallsCount()>0)delay(10)}
            assertEquals(listOf(1,0,0,0,0,0),f.servers.map {it.requestCount})
            assertTrue(f.pool.results(Platform.X).isEmpty());assertTrue(f.pool.learning.snapshot().isEmpty())
        }
    }
    @Test fun aKnownHealthyMirrorKeepsPriorityOverUntriedMethods():Unit=runBlocking {
        Fixture(listOf("nitter","nitter","rsshub")).use {f->
            val healthy=ProbeResult(f.routes.sources[1],Capability.FEED,HealthStatus.HEALTHY,100,System.currentTimeMillis(),false)
            f.pool.restore(listOf(healthy));f.pool.learning.observe(healthy)
            f.servers[1].enqueue(MockResponse().setBody(post("known productive mirror")))
            assertEquals("known productive mirror",f.pool.load(f.target,feed=true,originalFallback=false).posts.single().body)
            assertEquals(listOf(0,1,0),f.servers.map {it.requestCount})
        }
    }
    @Test fun aRouteThatEntersCooldownWhileWaitingIsSkippedBeforeAnyRequest():Unit=runBlocking {
        Fixture(List(3){"nitter"}).use {f->
            f.servers[0].enqueue(MockResponse().setBody(post("slow first route")).setBodyDelay(4,TimeUnit.SECONDS))
            f.servers[2].enqueue(MockResponse().setBody(post("eligible third route")))
            val read=async(Dispatchers.IO){f.pool.load(f.target,originalFallback=false)}
            try {
                assertNotNull(f.servers[0].takeRequest(2,TimeUnit.SECONDS))
                f.pool.learning.observe(ProbeResult(f.routes.sources[1],Capability.TIMELINE,HealthStatus.RATE_LIMITED,0,System.currentTimeMillis(),false,"Rate limited",120_000))
                assertEquals("eligible third route",withTimeout(3_000){read.await()}.posts.single().body)
                assertEquals(0,f.servers[1].requestCount)
            }finally {read.cancelAndJoin()}
        }
    }
    @Test fun anUndatedDifferentMethodCannotBeatACompleteChronologicalFeed():Unit=runBlocking {
        Fixture(listOf("nitter","nitter","rsshub")).use {f->
            repeat(2){f.servers[0].enqueue(MockResponse().setResponseCode(503))}
            f.servers[1].enqueue(MockResponse().setBody(post("complete dated notice")))
            f.servers[2].enqueue(MockResponse().setBody(feed(dated=false)))
            val page=f.pool.load(f.target,feed=true,originalFallback=false)
            assertEquals("complete dated notice",page.posts.single().body);assertNotNull(page.posts.single().publishedAt)
            assertEquals("The untried method was checked and rejected",1,f.servers[2].requestCount)
            assertEquals(HealthStatus.EMPTY,f.pool.results(Platform.X).single {it.source==f.routes.sources[2]}.status)
        }
    }
}
