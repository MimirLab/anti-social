/* anti social, GPL-3.0-or-later. Owned HTTP fixtures; no live Tor or provider traffic. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SourceProbeAdmissionTest {
    private class Fixture:AutoCloseable {
        val server=MockWebServer().apply {start()}
        val clock=AtomicLong(1_790_000_000_000)
        private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        private val client=gateway.client()
        val source=Source(Platform.X,"duckduckgo",server.url("/").toString().trimEnd('/'))
        val direct=source.copy(frontend="nitter")
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {
            add("sources",JsonArray());add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes,clock=clock::get)
        fun ready(){gateway.configureTor(NetworkPolicy(tor=true,socksPort=9,ready=true))}
        fun indexed(){server.enqueue(MockResponse().setBody("<div class='result'><a class='result__a' href='https://x.com/NASA/status/12345'>Public result</a></div>"))}
        fun previous():ProbeResult {
            val old=ProbeResult(source,Capability.SEARCH,HealthStatus.HEALTHY,30,clock.get()-3_600_001,true,"Earlier verified content")
            pool.restore(listOf(old));pool.learning.observe(old,delivery=false);return old
        }
        override fun close(){client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();server.shutdown()}
    }
    @Test fun expectedSearchReadinessDoesNotInvalidateAUsefulProbe():Unit=runBlocking {
        Fixture().use {f->
            f.indexed();f.pool.awaitSearchConnection={f.ready()}
            val result=runCatching {withTimeout(1_500){f.pool.probe(f.source,Capability.SEARCH,force=true)}}
            assertTrue("Expected startup readiness must not cancel a successful source check: ${result.exceptionOrNull()}",result.isSuccess)
            assertEquals(HealthStatus.HEALTHY,result.getOrThrow().status);assertEquals(1,f.server.requestCount)
            assertFalse(f.gateway.currentPolicy().tor)
        }
    }
    @Test fun waitingForTorDoesNotOccupyAllSourceReadPermits():Unit=runBlocking {
        Fixture().use {f->
            val waiting=AtomicInteger();val allWaiting=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            f.pool.awaitSearchConnection={if(waiting.incrementAndGet()==4)allWaiting.complete(Unit);release.await()}
            f.server.enqueue(MockResponse().setBody("<div class='timeline-item'><a class='tweet-link' href='https://x.com/NASA/status/12345'></a><div class='tweet-content'>Useful direct route</div></div>"))
            val work=List(4){async {runCatching {f.pool.probe(f.source,Capability.SEARCH,force=true)}}}
            try {
                withTimeout(1_500){allWaiting.await()}
                val direct=withTimeoutOrNull(700){f.pool.probe(f.direct,Capability.TIMELINE,force=true)}
                assertNotNull("Waiting search transport must leave a slot for ready direct checks",direct)
                assertEquals(HealthStatus.HEALTHY,direct!!.status);assertEquals(1,f.server.requestCount)
            }finally {work.forEach {it.cancel()};work.forEach {it.join()}}
        }
    }
    @Test fun probeLatencyAndObservationTimeStartAfterTransportWait():Unit=runBlocking {
        Fixture().use {f->
            f.ready();f.indexed()
            f.pool.awaitSearchConnection={delay(350);f.clock.addAndGet(30_000)}
            val start=System.nanoTime();val result=f.pool.probe(f.source,Capability.SEARCH,force=true)
            val elapsed=(System.nanoTime()-start)/1_000_000
            assertEquals("Observation is dated when content is checked",f.clock.get(),result.checkedAt)
            assertTrue("Transport wait is not source latency: total=$elapsed source=${result.latencyMs}",elapsed-result.latencyMs>=300)
        }
    }
    @Test fun aPauseLearnedDuringTransportWaitPreventsTheProbeRequest():Unit=runBlocking {
        Fixture().use {f->
            f.ready();f.indexed()
            f.pool.awaitSearchConnection={f.pool.learning.observe(ProbeResult(f.source,Capability.SEARCH,HealthStatus.RATE_LIMITED,1,f.clock.get(),true,"Source is rate limited",120_000),delivery=false)}
            val result=f.pool.probe(f.source,Capability.SEARCH,force=true)
            assertEquals("A newly learned pause must be rechecked before I/O",0,f.server.requestCount)
            assertTrue(result.detail.contains("no request sent"));assertEquals(1,f.pool.learning.get(f.source,Capability.SEARCH,true)!!.samples)
        }
    }
    @Test fun aPauseLearnedWhileQueuedForAReadPermitPreventsAnotherRequest():Unit=runBlocking {
        Fixture().use {f->
            f.ready();repeat(4){f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))}
            val holding=List(4){async {runCatching {f.pool.probe(f.direct,Capability.TIMELINE,force=true)}}}
            var queued:Deferred<ProbeResult>?=null
            try {
                withTimeout(2_000){while(f.server.requestCount<4)delay(10)}
                val prepared=CompletableDeferred<Unit>();f.pool.awaitSearchConnection={prepared.complete(Unit)}
                queued=async {f.pool.probe(f.source,Capability.SEARCH,force=true)}
                withTimeout(1_000){prepared.await()}
                f.pool.learning.observe(ProbeResult(f.source,Capability.SEARCH,HealthStatus.RATE_LIMITED,1,f.clock.get(),true,"Provider pause",120_000),delivery=false)
                holding.first().cancelAndJoin()
                val result=withTimeout(1_000){queued.await()}
                assertTrue(result.detail.contains("no request sent"));assertEquals(4,f.server.requestCount)
                assertEquals(1,f.pool.learning.get(f.source,Capability.SEARCH,true)!!.samples)
            }finally {queued?.cancelAndJoin();holding.forEach {it.cancel()};holding.forEach {it.join()}}
        }
    }
    @Test fun localSearchStartupFailureDoesNotOverwriteWorkingSourceEvidence():Unit=runBlocking {
        Fixture().use {f->
            val old=f.previous();val stats=f.pool.learning.snapshot()
            f.pool.awaitSearchConnection={throw SourceFailure(HealthStatus.UNAVAILABLE,"Tor did not connect. Search is paused.")}
            val result=f.pool.probe(f.source,Capability.SEARCH,force=true)
            assertEquals(HealthStatus.UNAVAILABLE,result.status);assertEquals(0,f.server.requestCount)
            assertEquals("Local transport failure is not a provider observation",listOf(old),f.pool.results(Platform.X));assertEquals(stats,f.pool.learning.snapshot())
        }
    }
    @Test fun anUnreadyTransportCannotTrainAProviderOutage():Unit=runBlocking {
        Fixture().use {f->
            val old=f.previous();val stats=f.pool.learning.snapshot()
            val result=f.pool.probe(f.source,Capability.SEARCH,force=true)
            assertEquals(HealthStatus.UNAVAILABLE,result.status);assertEquals(0,f.server.requestCount)
            assertEquals(listOf(old),f.pool.results(Platform.X));assertEquals(stats,f.pool.learning.snapshot())
        }
    }
    @Test fun aModeRoundTripDuringStartupCancelsBeforeTheSourceIsRead():Unit=runBlocking {
        Fixture().use {f->
            f.ready();f.indexed()
            f.pool.awaitSearchConnection={f.gateway.configure(f.gateway.currentPolicy(NetworkRoute.TOR));f.gateway.configure(NetworkPolicy())}
            val result=runCatching {f.pool.probe(f.source,Capability.SEARCH,force=true)}
            assertEquals(0,f.server.requestCount)
            assertTrue(result.exceptionOrNull() is CancellationException);assertTrue(f.pool.learning.snapshot().isEmpty())
        }
    }
}
