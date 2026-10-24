/* anti social, GPL-3.0-or-later. Real Gateway reads; no public-provider requests. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PublicIndexFairnessTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private fun gateway()=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
    private fun hit(url:String)="""<div class="text-result-wrapper"><a href="$url"><h2>Public indexed destination</h2></a></div>"""
    /** Pagination admission correctly rejects HTTP HTML links. Stage the known
     * source/query-bound continuation at the scheduler boundary so the fixture
     * can exercise real Gateway HTTP reads without weakening HTTPS admission. */
    private fun withFastContinuation(plan:String,server:MockWebServer,query:String,attempts:Int):String {
        val root=JsonParser.parseString(plan.removePrefix("public-index:")).asJsonObject
        val lanes=root.getAsJsonArray("lanes")
        if(lanes.none {it.asJsonObject["index"].asInt==0})lanes.add(com.google.gson.JsonObject().apply {
            addProperty("index",0);addProperty("attempts",attempts)
            addProperty("cursor",server.url("/search.php").newBuilder().addQueryParameter("q",query).addQueryParameter("p",(attempts+1).toString()).build().toString())
            if(root["version"].asInt==2)addProperty("deferred",false)
        })
        return "public-index:$root"
    }
    private fun dispose(gateway:NetworkGateway){gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()}
    private inner class ReelFixture(private val reply:(Int)->MockResponse):java.io.Closeable {
        val server=MockWebServer();val gateway=gateway();val target=routes.search(Platform.INSTAGRAM,"nasa")
        val queries=PublicSearchAdapter.postQueries(target);val postReads=AtomicInteger();val reelReads=AtomicInteger()
        val source:Source;val adapter:PublicSearchAdapter
        init {
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.queryParameter("q")) {
                queries[0]->MockResponse().setBody(hit("https://www.instagram.com/p/Fast${postReads.incrementAndGet()}/"))
                queries[1]->reelReads.incrementAndGet().let {if(it==1)MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)else reply(it)}
                queries[2]->MockResponse().setBody(hit("https://www.instagram.com/nasa/"))
                else->MockResponse().setResponseCode(400)
            }}
            server.start();source=Source(Platform.INSTAGRAM,"librey",server.url("/").toString().trimEnd('/'));adapter=PublicSearchAdapter(source,gateway,routes)
        }
        suspend fun plan():String=withFastContinuation(requireNotNull(withTimeout(1_500){adapter.fetch(target)}.next),server,queries[0],1)
        override fun close(){dispose(gateway);server.shutdown()}
    }

    @Test fun failedDeferredQueriesStillAllowAParkedWorkingContinuation():Unit=runBlocking {
        ReelFixture {MockResponse().setResponseCode(503)}.use {f->
            val page=withTimeout(2_000){f.adapter.fetch(f.target,f.plan())}
            assertTrue(page.collections.any {it.target.canonicalUrl.contains("/p/Fast2")})
            assertEquals(2,f.postReads.get());assertEquals(2,f.reelReads.get());assertNotNull(page.notice)
        }
    }
    @Test fun aDeferredProviderRefusalStopsBeforeAnyParkedQueryIsSent():Unit=runBlocking {
        ReelFixture {MockResponse().setBody("<div id='challenge-container'>Verify you are human</div>")}.use {f->
            val plan=f.plan()
            val error=assertThrows(SourceFailure::class.java){runBlocking {withTimeout(2_000){f.adapter.fetch(f.target,plan)}}}
            assertEquals(HealthStatus.CHALLENGE,error.status);assertEquals(1,f.postReads.get());assertEquals(2,f.reelReads.get())
        }
    }
    @Test fun parkedAndDeferredTokensAreAllValidatedBeforeThePriorityRead():Unit=runBlocking {
        ReelFixture {error("A forged plan must never reach the server")}.use {f->
            val plan=JsonParser.parseString(f.plan().removePrefix("public-index:")).asJsonObject
            val invalid=listOf(
                plan.deepCopy().apply {getAsJsonArray("lanes").first {it.asJsonObject["index"].asInt==0}.asJsonObject.addProperty("cursor","https://elsewhere.example/search.php?q=stolen")},
                plan.deepCopy().apply {getAsJsonArray("lanes")[0].asJsonObject.addProperty("deferred","true")},
                plan.deepCopy().apply {getAsJsonArray("lanes")[0].asJsonObject.remove("deferred")},
                plan.deepCopy().apply {addProperty("version",3)}
            )
            val before=f.server.requestCount
            for(body in invalid)assertThrows(IllegalArgumentException::class.java){runBlocking {f.adapter.fetch(f.target,"public-index:$body")}}
            assertEquals(before,f.server.requestCount)
        }
    }
    @Test fun cancellingThePriorityReadLeavesTheParkedQueryUnused():Unit=runBlocking {
        ReelFixture {MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)}.use {f->
            val plan=f.plan();val read=async {f.adapter.fetch(f.target,plan)}
            try {withTimeout(2_000){while(f.reelReads.get()<2)delay(10)}}finally {read.cancelAndJoin()}
            withTimeout(2_000){while(f.gateway.client().dispatcher.runningCallsCount()>0)delay(10)}
            assertEquals(1,f.postReads.get());assertEquals(2,f.reelReads.get())
        }
    }
    @Test fun aLegacyPlanUpgradesAfterItsFirstDeferralWithoutLosingTheQuery():Unit=runBlocking {
        ReelFixture {call->if(call==2)MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)else MockResponse().setHeadersDelay(700,TimeUnit.MILLISECONDS).setBody(hit("https://www.instagram.com/reel/SlowReel/"))}.use {f->
            val legacy=JsonParser.parseString(f.plan().removePrefix("public-index:")).asJsonObject.apply {
                addProperty("version",1);getAsJsonArray("lanes").forEach {it.asJsonObject.remove("deferred")}
            }
            val second=f.adapter.fetch(f.target,"public-index:$legacy")
            assertTrue(second.collections.any {it.target.canonicalUrl.contains("/p/Fast2")})
            val upgraded=JsonParser.parseString(requireNotNull(second.next).removePrefix("public-index:")).asJsonObject
            assertEquals(2,upgraded["version"].asInt)
            val third=withTimeout(2_000){f.adapter.fetch(f.target,second.next)}
            assertTrue(third.collections.any {it.target.canonicalUrl.contains("/reel/SlowReel")})
            assertEquals(2,f.postReads.get());assertEquals(3,f.reelReads.get())
        }
    }

    @Test fun aDeferredReelCompletesBeforeTheFasterPostQueryConsumesAnotherPage():Unit=runBlocking {
        MockWebServer().use {server->
            val target=routes.search(Platform.INSTAGRAM,"nasa")
            val queries=PublicSearchAdapter.postQueries(target)
            val postReads=AtomicInteger();val reelReads=AtomicInteger()
            server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {
                val q=request.requestUrl!!.queryParameter("q")!!
                return when(q) {
                    queries[0]->{val page=postReads.incrementAndGet();MockResponse().setBody(hit("https://www.instagram.com/p/Fast$page/"))}
                    queries[1]->if(reelReads.incrementAndGet()==1)MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else MockResponse().setHeadersDelay(700,TimeUnit.MILLISECONDS).setBody(hit("https://www.instagram.com/reel/SlowReel/"))
                    queries[2]->MockResponse().setBody(hit("https://www.instagram.com/nasa/"))
                    else->MockResponse().setResponseCode(400)
                }
            }}
            server.start();val gateway=gateway()
            try {
                val source=Source(Platform.INSTAGRAM,"librey",server.url("/").toString().trimEnd('/'))
                val adapter=PublicSearchAdapter(source,gateway,routes)
                val first=withTimeout(1_500){adapter.fetch(target)}
                assertTrue(first.collections.any {it.target.canonicalUrl.contains("/p/Fast1")})
                val second=withTimeout(2_000){adapter.fetch(target,withFastContinuation(requireNotNull(first.next),server,queries[0],1))}
                assertEquals("A deferred query must get its result before more pages from the winning query",listOf("https://www.instagram.com/reel/SlowReel"),second.collections.map {it.target.canonicalUrl.trimEnd('/')})
                assertEquals("The parked post continuation must not issue a second request yet",1,postReads.get())
                assertEquals(2,reelReads.get())
                val third=adapter.fetch(target,requireNotNull(second.next))
                assertTrue(third.collections.any {it.target.canonicalUrl.contains("/p/Fast2")})
                assertEquals(2,postReads.get());assertEquals(2,reelReads.get())
            }finally {dispose(gateway)}
        }
    }

    @Test fun threeQueryShapesEventuallyDeliverEvenWhenOneAlwaysReturnsFirst():Unit=runBlocking {
        MockWebServer().use {server->
            val target=routes.search(Platform.TIKTOK,"nasa");val queries=PublicSearchAdapter.postQueries(target)
            val counts=List(3){AtomicInteger()}
            val urls=listOf("https://www.tiktok.com/@nasa/video/1234567890","https://www.tiktok.com/@nasa/photo/2234567890","https://www.tiktok.com/@nasa/video/3234567890")
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val q=request.requestUrl!!.queryParameter("q")!!;val lane=queries.indexOf(q)
                if(lane<0)return MockResponse().setResponseCode(400)
                counts[lane].incrementAndGet()
                return MockResponse().setHeadersDelay(listOf(0L,450L,900L)[lane],TimeUnit.MILLISECONDS)
                    .setBody(hit(urls[lane]))
            }}
            server.start();val gateway=gateway()
            try {
                val adapter=PublicSearchAdapter(Source(Platform.TIKTOK,"librey",server.url("/").toString().trimEnd('/')),gateway,routes)
                val found=linkedSetOf<String>();var cursor:String?=null
                var rounds=0
                do {
                    val page=withTimeout(2_000){adapter.fetch(target,cursor)}
                    found+=page.collections.map {it.target.canonicalUrl.trimEnd('/')}
                    cursor=page.next?.let {withFastContinuation(it,server,queries[0],counts[0].get())}
                    rounds++
                }while(found.size<3&&cursor!=null&&rounds<4)
                assertEquals("Every requested post shape needs a fair chance, not four repeated cancellations",urls.toSet(),found)
                assertEquals("Only the initial page of the fastest lane is needed during the deferred passes",1,counts[0].get())
                assertTrue(counts.sumOf {it.get()}<=6)
            }finally {dispose(gateway)}
        }
    }
}
