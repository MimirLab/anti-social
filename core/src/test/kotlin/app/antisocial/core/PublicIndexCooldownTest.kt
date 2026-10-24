/* anti social, GPL-3.0-or-later. Owned Gateway sources; no live provider reads. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PublicIndexCooldownTest {
    private class Fixture(val frontend:String="librey"):AutoCloseable {
        val server=MockWebServer().apply {start()}
        private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        val source=Source(Platform.INSTAGRAM,frontend,server.url("/").toString().trimEnd('/'))
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {
            add("sources",JsonParser.parseString(listOf(Platform.INSTAGRAM,Platform.FACEBOOK,Platform.X).joinToString(",","[","]") {"""{"platform":"${it.id}","frontend":"$frontend","root":"${source.root}","fallbackOnly":false}"""}))
            add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes).apply {searchProvider=SearchProvider.DARWIN}
        fun target(platform:Platform=Platform.INSTAGRAM)=routes.search(platform,"fixture")
        override fun close(){gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll();server.shutdown()}
    }
    @Test fun aModeChangeInsideTheResponseHookIsRejectedBeforeARetry():Unit=runBlocking {
        Fixture("duckduckgo").use {f->
            f.server.enqueue(MockResponse().setBody("<html><p>The server encountered an error</p></html>"))
            f.server.enqueue(MockResponse().setBody("<div class='result'><a class='result__a' href='https://x.com/nasa/status/12345'>Public result</a></div>"))
            val switched=AtomicBoolean()
            f.gateway.onTextResponse={if(switched.compareAndSet(false,true)){f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())}}
            val result=runCatching {withTimeout(2_000){PublicSearchAdapter(f.source.copy(platform=Platform.X),f.gateway,f.routes).fetch(f.target(Platform.X))}}
            assertTrue(switched.get())
            assertEquals("The old query must stop before its retry uses a new session",1,f.server.requestCount)
            assertTrue(result.exceptionOrNull() is CancellationException);assertFalse(result.exceptionOrNull() is TimeoutCancellationException)
        }
    }
    @Test fun aDirectoryRefusalPausesPostSearchOtherPlatformsAndForcedProbes():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","120"))
            withTimeout(1_000){f.pool.searchDirectory(f.target(),false)}
            assertEquals(HealthStatus.RATE_LIMITED,f.pool.results(f.source.platform).single().status)
            assertEquals(1,f.server.requestCount)
            val samples=f.pool.learning.get(f.source,Capability.SEARCH,false)!!.samples
            val peer=f.source.copy(platform=Platform.FACEBOOK);val target=f.target(peer.platform)
            assertFalse(f.pool.learning.eligible(peer,Capability.SEARCH,false))
            withTimeout(1_000){f.pool.searchDirectory(target,false)}
            val result=runCatching {withTimeout(1_000){f.pool.load(target,originalFallback=false)}}
            assertTrue(result.exceptionOrNull() is SourceFailure)
            assertTrue((result.exceptionOrNull() as SourceFailure).retryAfterMs!!>110_000)
            val probe=withTimeout(1_000){f.pool.probe(peer,Capability.SEARCH,force=true)}
            assertTrue(probe.detail.contains("no request sent"))
            assertEquals(1,f.server.requestCount)
            assertEquals("Skipping work is not another failed probe",samples,f.pool.learning.get(f.source,Capability.SEARCH,false)!!.samples)
        }
    }
    @Test fun anInitialIndexReadCanKeepItsUsefulPostAndItsLateRefusal():Unit=runBlocking {
        Fixture().use {f->
            val target=f.target();val queries=PublicSearchAdapter.postQueries(target)
            f.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.queryParameter("q")) {
                queries[0]->MockResponse().setBody("<div class='text-result-wrapper'><a href='https://www.instagram.com/p/PartialUseful/'><h2>Useful destination</h2></a></div>")
                queries[1]->MockResponse().setHeadersDelay(100,java.util.concurrent.TimeUnit.MILLISECONDS).setResponseCode(429).setHeader("Retry-After","120")
                else->MockResponse().setBody("<html>No indexed accounts</html>")
            }}
            val page=withTimeout(1_500){f.pool.load(target,originalFallback=false)}
            assertTrue(page.collections.any {it.target.canonicalUrl.contains("PartialUseful")})
            assertEquals(HealthStatus.RATE_LIMITED,f.pool.results(target.platform).single().status)
            val stat=f.pool.learning.get(f.source,Capability.SEARCH,false)!!
            assertEquals(1,stat.samples);assertEquals(1,stat.successes);assertEquals(1,stat.deliverySamples)
            assertFalse(f.pool.learning.eligible(f.source,Capability.SEARCH,false))
            val before=f.server.requestCount
            withTimeout(1_000){f.pool.searchDirectory(target,false)}
            assertEquals(before,f.server.requestCount)
        }
    }
    @Test fun anInternalIndexPolicyCancellationCannotStrandTheSourceCollector():Unit=runBlocking {
        Fixture().use {f->
            f.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody("<div class='text-result-wrapper'><a href='https://www.instagram.com/p/BeforeModeChange/'><h2>Public destination</h2></a></div>")}
            val switched=AtomicBoolean()
            f.gateway.onTextResponse={if(switched.compareAndSet(false,true)){f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())}}
            val result=runCatching {withTimeout(1_500){f.pool.load(f.target(),originalFallback=false)}}
            assertTrue(result.exceptionOrNull() is CancellationException);assertFalse(result.exceptionOrNull() is TimeoutCancellationException)
            assertTrue(f.pool.results(f.source.platform).isEmpty());assertFalse(f.pool.hasActiveReads)
        }
    }
}
