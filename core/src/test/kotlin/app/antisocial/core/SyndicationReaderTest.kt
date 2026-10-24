/* anti social, GPL-3.0-or-later. Real bounded Gateway reads over owned fixtures. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SyndicationReaderTest {
    private class Fixture(budget:Int=4*1024*1024,ttl:Long=30*60_000L):AutoCloseable {
        val server=MockWebServer().apply {start()}
        val clock=AtomicLong(1_790_000_000_000)
        private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        var rewrite:String?=null
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false,rewrittenUrl=rewrite?.takeIf {url.contains("/feed")})},true)
        private val clients=mutableSetOf(gateway.client())
        val reader=SyndicationReader(gateway,clock::get,maxBytes=budget,ttl=ttl)
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
        val target=routes.target(Platform.X,"NASA")
        val source=Source(Platform.X,"rss",server.url("/").toString().trimEnd('/'))
        val parses=AtomicInteger()
        var beforeParse:suspend ()->Unit={}
        fun body(text:String="Useful flight notice")="""<rss><channel><title>NASA</title><link>https://x.com/NASA</link><item><title>Flight notice</title><link>https://x.com/NASA/status/123</link><description>$text</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""
        fun full(text:String="Useful flight notice")=MockResponse().setHeader("Content-Type","application/rss+xml").setHeader("ETag","\"one\"").setBody(body(text))
        fun unchanged(tag:String="\"one\"")=MockResponse().setResponseCode(304).setHeader("ETag",tag)
        suspend fun read(path:String="/feed",selected:Target=target,headers:Map<String,String> = emptyMap()):Page {
            runCatching {gateway.client()}.getOrNull()?.let {clients+=it}
            return reader.read(server.url(path).toString(),selected,source,1024L*1024,headers){payload->
                parses.incrementAndGet();beforeParse();Extraction.parse(payload,selected,source,routes,clock.get())
            }
        }
        fun request()=requireNotNull(server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS))
        fun unconditional(request:RecordedRequest){assertNull(request.getHeader("If-None-Match"));assertNull(request.getHeader("If-Modified-Since"))}
        override fun close(){clients.forEach {it.dispatcher.executorService.shutdownNow();it.connectionPool.evictAll()};server.shutdown()}
    }
    @Test fun identical200BodiesAvoidReextractionButChangedFactsDoNot():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(f.full());f.server.enqueue(f.full());f.server.enqueue(f.full("The departure is now 18:30"))
            val first=f.read();f.clock.addAndGet(100);val again=f.read()
            assertEquals(1,f.parses.get());assertEquals(first.posts.single().body,again.posts.single().body)
            assertTrue(again.provenance!!.fetchedAt>first.provenance!!.fetchedAt)
            val changed=f.read();assertEquals(2,f.parses.get());assertEquals("The departure is now 18:30",changed.posts.single().body)
            assertEquals(3,f.server.requestCount)
        }
    }
    @Test fun a304RetainsAllFieldsAndUsesAFreshObservationWithoutParsing():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(f.full());f.server.enqueue(f.unchanged("W/\"one\""))
            val first=f.read();f.clock.addAndGet(500);val checked=f.read()
            assertEquals(1,f.parses.get());assertEquals(first.posts.map {it.copy(provenance=null)},checked.posts.map {it.copy(provenance=null)})
            assertEquals(f.clock.get(),checked.posts.single().provenance!!.fetchedAt)
            assertFalse(checked.provenance!!.cached);assertNull(FeedRefresh.pendingReason(checked,Platform.X))
            f.unconditional(f.request());val request=f.request();assertEquals("\"one\"",request.getHeader("If-None-Match"));assertEquals("no-cache",request.getHeader("Cache-Control"))
        }
    }
    @Test fun lastModifiedCanValidateAFeedWithoutAnEntityTag():Unit=runBlocking {
        Fixture().use {f->
            val date="Fri, 23 Oct 2026 12:00:00 GMT"
            f.server.enqueue(f.full().removeHeader("ETag").setHeader("Last-Modified",date))
            f.server.enqueue(MockResponse().setResponseCode(304).setHeader("Last-Modified",date))
            f.read();f.read();assertEquals(1,f.parses.get());f.request()
            val request=f.request();assertNull(request.getHeader("If-None-Match"));assertEquals(date,request.getHeader("If-Modified-Since"))
        }
    }
    @Test fun anUnexpected304CannotBecomeAnEmptyOrFabricatedFeed():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(f.unchanged())
            val result=runCatching {f.read()};assertEquals(HealthStatus.EMPTY,(result.exceptionOrNull() as SourceFailure).status)
            assertEquals(0,f.parses.get());assertEquals(0,f.reader.retainedBytes())
        }
    }
    @Test fun conflicting304ValidatorsAreRejectedAndTheNextReadIsUnconditional():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(f.full());f.server.enqueue(f.unchanged("\"different\""));f.server.enqueue(f.full("A new verified body"))
            f.read();assertTrue(runCatching {f.read()}.isFailure);assertEquals(0,f.reader.retainedBytes())
            assertEquals("A new verified body",f.read().posts.single().body)
            f.request();f.request();f.unconditional(f.request());assertEquals(2,f.parses.get())
        }
    }
    @Test fun a304DoesNotKeepTheOriginalValidationBindingAliveForever():Unit=runBlocking {
        Fixture(ttl=100).use {f->
            f.server.enqueue(f.full());f.server.enqueue(f.unchanged());f.server.enqueue(f.full())
            f.read();f.clock.addAndGet(50);f.read();f.clock.addAndGet(51);f.read()
            assertEquals(2,f.parses.get());f.request();f.request();f.unconditional(f.request())
        }
    }
    @Test fun failedRefreshNeverReturnsThePriorGoodPage():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(f.full());f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","120"))
            f.read();val result=runCatching {f.read()};val failure=result.exceptionOrNull() as SourceFailure
            assertEquals(HealthStatus.UNAVAILABLE,failure.status);assertEquals(120_000L,failure.retryAfterMs)
            assertEquals(1,f.parses.get());assertEquals(2,f.server.requestCount)
        }
    }
    @Test fun replacementShellsDiscardTheOldBindingRatherThanHidingBehindIt():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(f.full());f.server.enqueue(MockResponse().setHeader("ETag","\"shell\"").setBody("<html>Sign in to see this account</html>"));f.server.enqueue(f.full())
            f.read();assertTrue(runCatching {f.read()}.isFailure);assertEquals(0,f.reader.retainedBytes())
            f.read();f.request();f.request();f.unconditional(f.request());assertEquals(3,f.parses.get())
        }
    }
    @Test fun noStoreAndUnknownVaryPreventCrossRequestRetention():Unit=runBlocking {
        for((name,value)in listOf("Cache-Control" to "max-age=60, no-store","Vary" to "*","Vary" to "Cookie"))Fixture().use {f->
            repeat(2){f.server.enqueue(f.full().setHeader(name,value));f.read()}
            assertEquals(2,f.parses.get());assertEquals(0,f.reader.retainedBytes())
            repeat(2){f.unconditional(f.request())}
        }
    }
    @Test fun noStoreOnA304DropsItsBindingAfterTheCurrentValidatedRead():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(f.full());f.server.enqueue(f.unchanged().setHeader("Cache-Control","no-store"));f.server.enqueue(f.full())
            f.read();f.read();assertEquals(0,f.reader.retainedBytes());assertEquals(1,f.parses.get())
            f.read();f.request();f.request();f.unconditional(f.request());assertEquals(2,f.parses.get())
        }
    }
    @Test fun validatorsBelongToTheExactFeedTargetAndRequestHeaders():Unit=runBlocking {
        Fixture().use {f->
            repeat(4){f.server.enqueue(f.full().setHeader("Vary","Accept-Language"))}
            f.read(headers=mapOf("Accept-Language" to "en"));f.read(headers=mapOf("Accept-Language" to "es"))
            f.read(selected=f.routes.target(Platform.X,"another"));f.read(path="/other-feed")
            repeat(4){f.unconditional(f.request())};assertEquals(4,f.parses.get())
        }
    }
    @Test fun directModeRoundTripsAndCacheClearRequireAnotherFullRead():Unit=runBlocking {
        Fixture().use {f->
            repeat(3){f.server.enqueue(f.full())}
            f.read();f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy());f.read();f.reader.clear();f.read()
            repeat(3){f.unconditional(f.request())};assertEquals(3,f.parses.get())
        }
    }
    @Test fun redirectsCannotCarryAValidatorToAnotherFeedOrManufactureA304():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(f.full());f.read();f.request()
            f.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/other"));f.server.enqueue(f.unchanged())
            assertTrue(runCatching {f.read()}.isFailure)
            assertEquals("\"one\"",f.request().getHeader("If-None-Match"));f.unconditional(f.request());assertEquals(1,f.parses.get())
        }
    }
    @Test fun aFilterRewriteCannotTransferAValidatorToItsDestination():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(f.full());f.read();f.request();f.rewrite=f.server.url("/replacement").toString();f.server.enqueue(f.unchanged())
            assertTrue(runCatching {f.read()}.isFailure);f.unconditional(f.request());assertEquals(1,f.parses.get())
        }
    }
    @Test fun cacheClearDuringExtractionCannotRepopulateTheClearedMemory():Unit=runBlocking {
        Fixture().use {f->
            val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            f.beforeParse={if(f.parses.get()==1){started.complete(Unit);release.await()}}
            f.server.enqueue(f.full());f.server.enqueue(f.full())
            val task=async {f.read()}
            try {withTimeout(2_000){started.await()};f.reader.clear();release.complete(Unit);task.await();assertEquals(0,f.reader.retainedBytes());f.read()
                f.request();f.unconditional(f.request());assertEquals(2,f.parses.get())
            }finally {release.complete(Unit);task.cancelAndJoin()}
        }
    }
    @Test fun obsoleteExtractionCannotReplaceANewerSessionsValidatedPage():Unit=runBlocking {
        Fixture().use {f->
            val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            f.beforeParse={if(f.parses.get()==1){started.complete(Unit);release.await()}}
            f.server.enqueue(f.full());f.server.enqueue(f.full("New session content"));f.server.enqueue(f.unchanged())
            val old=async {runCatching {f.read()}}
            try {
                withTimeout(2_000){started.await()};f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())
                assertEquals("New session content",f.read().posts.single().body);release.complete(Unit)
                assertTrue(old.await().exceptionOrNull() is CancellationException)
                assertEquals("New session content",f.read().posts.single().body);assertEquals(2,f.parses.get())
                f.request();f.unconditional(f.request());assertEquals("\"one\"",f.request().getHeader("If-None-Match"))
            }finally {release.complete(Unit);old.cancelAndJoin()}
        }
    }
    @Test fun evictionAndOversizeRecordsNeverLeaveOrphanValidators():Unit=runBlocking {
        Fixture(budget=8_000).use {f->
            repeat(7){f.server.enqueue(f.full())}
            repeat(6){f.read(path="/feed-$it")};assertTrue(f.reader.retainedBytes() in 1..8_000)
            f.read(path="/feed-0");repeat(6){f.request()};f.unconditional(f.request())
        }
        Fixture(budget=4_000).use {f->
            repeat(2){f.server.enqueue(f.full("x".repeat(10_000)));f.read()}
            assertEquals(0,f.reader.retainedBytes());assertEquals(2,f.parses.get());repeat(2){f.unconditional(f.request())}
        }
    }
}
