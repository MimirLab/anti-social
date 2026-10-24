package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Actual gateway requests prove that failure advances one fallback, not a broadcast. */
class SourcePoolHedgeTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private fun post(body:String)="""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/123"></a><div class="tweet-content">$body</div></div>"""
    private fun routes(servers:List<MockWebServer>):DarwinRoutes {
        val catalog=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject
        catalog.add("sources",JsonParser.parseString(servers.joinToString(",","[","]") {"""{"platform":"twitter","frontend":"nitter","root":"${it.url("/").toString().trimEnd('/')}","fallbackOnly":false}"""}))
        catalog.add("searchSources",JsonArray())
        return DarwinRoutes(catalog,cleaner)
    }
    private fun verifyUnusedThird(firstResponse:MockResponse)=runBlocking {
        val servers=List(3){MockWebServer().apply {start()}}
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        try {
            servers[0].enqueue(firstResponse)
            servers[1].enqueue(MockResponse().setBody(post("useful second route")).setBodyDelay(100,TimeUnit.MILLISECONDS))
            servers[2].enqueue(MockResponse().setBody(post("unnecessary third route")).setBodyDelay(1,TimeUnit.SECONDS))
            val routes=routes(servers);val pool=SourcePool(gateway,routes)
            val page=pool.load(routes.target(Platform.X,"NASA"),originalFallback=false)
            assertEquals("useful second route",page.posts.single().body)
            assertEquals(1,servers[0].requestCount);assertEquals(1,servers[1].requestCount)
            assertEquals("A quick successful fallback must leave the third route untouched",0,servers[2].requestCount)
        }finally {
            gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
            servers.forEach {it.close()}
        }
    }
    @Test fun failedFirstRouteDoesNotReleaseEveryWaitingBackup()=verifyUnusedThird(MockResponse().setResponseCode(503))
    @Test fun emptyFirstRouteDoesNotReleaseEveryWaitingBackup()=verifyUnusedThird(MockResponse().setBody("<title>No timeline entries</title>"))
    @Test fun twoFailedRoutesReachTheThirdWithoutWaitingForTheirHedgeTimers()=runBlocking {
        val servers=List(3){MockWebServer().apply {start()}}
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        try {
            servers[0].enqueue(MockResponse().setResponseCode(503));servers[1].enqueue(MockResponse().setResponseCode(503))
            servers[2].enqueue(MockResponse().setBody(post("working third route")))
            val routes=routes(servers);val pool=SourcePool(gateway,routes)
            routes.sources.forEach {pool.learning.observe(ProbeResult(it,Capability.TIMELINE,HealthStatus.HEALTHY,3000,System.currentTimeMillis(),false))}
            val start=System.nanoTime()
            assertEquals("working third route",pool.load(routes.target(Platform.X,"NASA"),originalFallback=false).posts.single().body)
            assertTrue("Explicit failures must skip their trained 1.5-second stall intervals",(System.nanoTime()-start)/1_000_000<1000)
            assertEquals(listOf(1,1,1),servers.map {it.requestCount})
        }finally {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll();servers.forEach {it.close()}}
    }
}
