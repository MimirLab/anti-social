package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Actual local gateway calls and content parsing, with a virtual scheduling clock. */
class SourcePoolHeartbeatTest {
    private var now=1_800_000_000_000L
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private fun gateway()=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
    private fun routes(vararg servers: MockWebServer): DarwinRoutes {
        val catalog=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject
        catalog.add("sources",JsonParser.parseString(servers.joinToString(",","[","]") {
            """{"platform":"twitter","frontend":"nitter","root":"${it.url("/").toString().trimEnd('/')}","fallbackOnly":false}"""
        }))
        catalog.add("searchSources",JsonArray())
        return DarwinRoutes(catalog,cleaner)
    }
    private fun post(body: String)="""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/123"></a><div class="tweet-content">$body</div><span class="tweet-date"><a title="Oct 16, 2026 · 12:00 PM UTC"></a></span></div>"""

    @Test fun actualReadReplacesProbingAndOneDueSoundingLeavesDeliverySpeedAlone(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {backup->
            first.start();backup.start();first.enqueue(MockResponse().setBody(post("requested")))
            backup.enqueue(MockResponse().setBody(post("backup proof")))
            val routes=routes(first,backup);val pool=SourcePool(gateway(),routes,clock={now});val target=routes.target(Platform.X,"NASA")
            var reservedBeforeSocket=false
            pool.onHeartbeatReservation={assertEquals(0,backup.requestCount);reservedBeforeSocket=true}
            assertNull(pool.maintenanceDueAt(target,Capability.TIMELINE,false))
            assertEquals("requested",pool.load(target,originalFallback=false).posts.single().body)
            pool.maintain(target,Capability.TIMELINE,routes.sources.first(),false)
            assertEquals(0,backup.requestCount)
            now=requireNotNull(pool.maintenanceDueAt(target,Capability.TIMELINE,false))
            pool.maintain(target,Capability.TIMELINE,routes.sources.first(),false)
            assertEquals(1,backup.requestCount)
            assertTrue(reservedBeforeSocket)
            assertEquals(0,pool.learning.get(routes.sources[1],Capability.TIMELINE,false)!!.deliverySamples)
            assertTrue(requireNotNull(pool.maintenanceDueAt(target,Capability.TIMELINE,false))>now)
            repeat(4){pool.maintain(target,Capability.TIMELINE,null,false)}
            assertEquals(2,first.requestCount+backup.requestCount)
        }}
    }

    @Test fun foregroundReadPreemptsOnlyTheCanaryAndTheMaintenanceCallerCanReschedule(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {backup->
            first.start();backup.start();repeat(2){first.enqueue(MockResponse().setBody(post("foreground")))}
            backup.enqueue(MockResponse().setBody(post("too late")).setBodyDelay(3,TimeUnit.SECONDS))
            val routes=routes(first,backup);val pool=SourcePool(gateway(),routes,clock={now});val target=routes.target(Platform.X,"NASA")
            pool.load(target,originalFallback=false)
            now=requireNotNull(pool.maintenanceDueAt(target,Capability.TIMELINE,false))
            var resumed=false
            val maintenance=async {pool.maintain(target,Capability.TIMELINE,routes.sources.first(),false);resumed=true}
            withContext(Dispatchers.IO){assertNotNull(backup.takeRequest(2,TimeUnit.SECONDS))}
            assertEquals("foreground",pool.load(target,originalFallback=false).posts.single().body)
            withTimeout(2000){maintenance.await()}
            assertTrue("The worker remains alive after its child probe yields",resumed)
            assertFalse(pool.hasActiveReads)
            assertNull(pool.learning.get(routes.sources[1],Capability.TIMELINE,false))
            assertTrue(pool.results(Platform.X).all {it.status==HealthStatus.HEALTHY})
            assertEquals(0,pool.heartbeat.state(pool.heartbeatScope(target,Capability.TIMELINE))!!.failures)
            assertEquals(1,pool.heartbeat.snapshot().attempts.size)
            assertTrue(requireNotNull(pool.maintenanceDueAt(target,Capability.TIMELINE,false))>now)
        }}
    }

    @Test fun unavailableTorDoesNotSpendOrTrainAHeartbeat(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(post("direct evidence")))
            val routes=routes(server);val gateway=gateway();val pool=SourcePool(gateway,routes,clock={now});val target=routes.target(Platform.X,"NASA")
            pool.load(target,originalFallback=false)
            now=requireNotNull(pool.maintenanceDueAt(target,Capability.TIMELINE,false))
            val before=pool.heartbeat.snapshot()
            gateway.configure(NetworkPolicy(tor=true,ready=false))
            pool.maintain(target,Capability.TIMELINE,null,false)
            assertEquals(before,pool.heartbeat.snapshot());assertEquals(1,server.requestCount)
        }
    }

    @Test fun oneMissingAccountDoesNotTeachASiteWideOutage(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(post("working account")))
            server.enqueue(MockResponse().setResponseCode(404))
            val routes=routes(server);val pool=SourcePool(gateway(),routes,clock={now});val target=routes.target(Platform.X,"NASA")
            pool.load(target,originalFallback=false)
            val scope=pool.heartbeatScope(target,Capability.TIMELINE);val before=pool.heartbeat.state(scope)!!
            assertThrows(SourceFailure::class.java){runBlocking {pool.load(routes.target(Platform.X,"missing"),originalFallback=false)}}
            val after=pool.heartbeat.state(scope)!!
            assertEquals(before.failures,after.failures);assertEquals(before.volatility,after.volatility)
            assertEquals(before.nextDueAt,after.nextDueAt)
            now=requireNotNull(pool.maintenanceDueAt(target,Capability.TIMELINE,false))
            server.enqueue(MockResponse().setResponseCode(404))
            pool.maintain(routes.target(Platform.X,"missing"),Capability.TIMELINE,null,false)
            assertEquals(0,pool.heartbeat.state(scope)!!.failures)
            assertEquals(0,pool.heartbeat.state(scope)!!.volatility)
            assertTrue(requireNotNull(pool.maintenanceDueAt(target,Capability.TIMELINE,false))>=now+3_600_000)
        }
    }

    @Test fun serverPauseAndFailedCanaryRemainQuietAcrossPolicyRestore(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(post("real feed")))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","7200"))
            val routes=routes(server);val pool=SourcePool(gateway(),routes,clock={now});val target=routes.target(Platform.X,"NASA")
            pool.load(target,feed=true,originalFallback=false)
            now=requireNotNull(pool.maintenanceDueAt(target,Capability.FEED,false))
            pool.maintain(target,Capability.FEED,null,false)
            assertEquals(2,server.requestCount)
            assertTrue(requireNotNull(pool.maintenanceDueAt(target,Capability.FEED,false))>=now+7_200_000)
            val restored=SourcePool(gateway(),routes,clock={now})
            restored.learning.restore(pool.learning.snapshot());restored.heartbeat.restore(pool.heartbeat.snapshot())
            assertEquals(pool.maintenanceDueAt(target,Capability.FEED,false),restored.maintenanceDueAt(target,Capability.FEED,false))
            restored.maintain(target,Capability.FEED,null,false)
            assertEquals(2,server.requestCount)
        }
    }
}
