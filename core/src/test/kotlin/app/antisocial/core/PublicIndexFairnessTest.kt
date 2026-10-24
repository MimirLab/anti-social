/* anti social, GPL-3.0-or-later. Real Gateway reads; no public-provider requests. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
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
        val source:Source;val adapter:PublicSearchAdapter;val pool:SourcePool
        init {
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.queryParameter("q")) {
                queries[0]->MockResponse().setBody(hit("https://www.instagram.com/p/Fast${postReads.incrementAndGet()}/"))
                queries[1]->reelReads.incrementAndGet().let {if(it==1)MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)else reply(it)}
                queries[2]->MockResponse().setBody(hit("https://www.instagram.com/nasa/"))
                else->MockResponse().setResponseCode(400)
            }}
            server.start();source=Source(Platform.INSTAGRAM,"librey",server.url("/").toString().trimEnd('/'))
            val catalog=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {
                add("sources",JsonParser.parseString("""[{"platform":"instagram","frontend":"librey","root":"${source.root}","fallbackOnly":false}]"""));add("searchSources",com.google.gson.JsonArray())
            }
            val local=DarwinRoutes(catalog,cleaner);pool=SourcePool(gateway,local).apply {searchProvider=SearchProvider.DARWIN};adapter=PublicSearchAdapter(source,gateway,local)
        }
        suspend fun plan():String=withFastContinuation(requireNotNull(withTimeout(1_500){adapter.fetch(target)}.next),server,queries[0],1)
        override fun close(){dispose(gateway);server.shutdown()}
    }
    private fun reader(f:ReelFixture,read:(Target)->Unit={}):SearchFeed=SearchFeed(f.target,discover={previous->
        if(previous==null)f.adapter.fetch(f.target).let {it.copy(next=withFastContinuation(requireNotNull(it.next),f.server,f.queries[0],1))}
        else f.pool.continuePage(f.target,previous,false)
    },read={requested->
        read(requested)
        if(requested.kind==TargetKind.POST)Page(listOf(Post(requested.identity,requested.platform,requested.canonicalUrl,"Fixture",requested.canonicalUrl,publishedAt=1_792_800_000_000L)))else Page(emptyList(),profile=Profile(requested,"NASA fixture"))
    })
    private suspend fun updates(f:ReelFixture,publish:suspend (Page,()->Unit)->Unit):Page {
        val previous=Page(emptyList(),next=f.plan(),provenance=Provenance(f.source,0,f.source.root))
        return withContext(SearchDiscoveryUpdates(f.target,publish)){f.pool.continuePage(f.target,previous,false)}
    }

    @Test fun stoppingAfterAPartialRetriesTheTailWithoutRepeatingTheCompletedFastPage():Unit=runBlocking {
        val release=CountDownLatch(1)
        ReelFixture {call->if(call==2)check(release.await(5,TimeUnit.SECONDS));MockResponse().setBody(hit("https://www.instagram.com/reel/SlowReel/"))}.use {f->
            val ready=CompletableDeferred<Unit>();val reads=mutableListOf<String>();val feed=reader(f){reads+=it.canonicalUrl}
            val work=launch {feed.loadNext {if(it.page.posts.any {it.canonicalUrl.contains("/p/Fast2")})ready.complete(Unit)}}
            try {
                withTimeout(2_000){ready.await()};work.cancelAndJoin();feed.pause()
                assertTrue(feed.state.paused);assertTrue(feed.state.hasMore)
                withTimeout(2_000){feed.loadNext {}}
                assertEquals(3,feed.state.page.posts.size);assertFalse(feed.state.hasMore)
                assertEquals(2,f.postReads.get());assertEquals(3,f.reelReads.get())
                assertEquals(1,reads.count {it.contains("/p/Fast2")});assertTrue(feed.state.page.posts.any {it.canonicalUrl.contains("SlowReel")})
            }finally {release.countDown();work.cancelAndJoin()}
        }
    }
    @Test fun providerAndPrivacyChangesDuringPartialPublicationCancelWithoutOutageTraining():Unit=runBlocking {
        for(change in listOf<(ReelFixture)->Unit>({it.pool.searchProvider=SearchProvider.BING},{it.gateway.configure(NetworkPolicy(tor=true));it.gateway.configure(NetworkPolicy())},{it.gateway.originalFallback=false})) {
            val release=CountDownLatch(1)
            ReelFixture {check(release.await(5,TimeUnit.SECONDS));MockResponse().setBody(hit("https://www.instagram.com/reel/SlowReel/"))}.use {f->
                try {
                    var publications=0
                    val result=runCatching {withTimeout(2_000){updates(f){_,check->publications++;change(f);check()}}}
                    assertTrue(result.exceptionOrNull() is CancellationException)
                    assertFalse(result.exceptionOrNull() is TimeoutCancellationException)
                    assertEquals(1,publications);assertEquals(2,f.postReads.get());assertEquals(2,f.reelReads.get())
                    assertTrue(f.pool.results(Platform.INSTAGRAM).isEmpty())
                }finally {release.countDown()}
            }
        }
    }
    @Test fun changingProviderBeforeTheHedgeStartsSendsNoParkedQuery():Unit=runBlocking {
        val release=CountDownLatch(1);val tailStarted=CompletableDeferred<Unit>()
        ReelFixture {tailStarted.complete(Unit);check(release.await(5,TimeUnit.SECONDS));MockResponse().setBody(hit("https://www.instagram.com/reel/SlowReel/"))}.use {f->
            val feed=reader(f);val work=launch {feed.loadNext {}}
            try {
                withTimeout(2_000){tailStarted.await()};f.pool.searchProvider=SearchProvider.BING
                withTimeout(1_500){work.join()}
                assertFalse(feed.state.loading);assertNotNull(feed.state.error)
                assertEquals(1,feed.state.page.posts.size)
                assertEquals(1,f.postReads.get());assertEquals(2,f.reelReads.get());assertTrue(f.pool.results(Platform.INSTAGRAM).isEmpty())
            }finally {release.countDown();work.cancelAndJoin()}
        }
    }
    @Test fun aPolicyChangeWhileAwaitingSearchTransportCannotStartTheOldProvider():Unit=runBlocking {
        for(changeProvider in listOf(true,false)) {
            val gateway=gateway();val pool=SourcePool(gateway,routes)
            val target=routes.search(Platform.INSTAGRAM,"fixture")
            val source=Source(target.platform,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
            var awaited=false
            pool.awaitSearchConnection={awaited=true;if(changeProvider)pool.searchProvider=SearchProvider.BING else gateway.originalFallback=false}
            val previous=Page(emptyList(),next="Saved continuation is never opened after the policy changes",provenance=Provenance(source,0,source.root))
            try {
                val result=runCatching {pool.continuePage(target,previous,true)}
                assertTrue(awaited);assertTrue(result.exceptionOrNull() is CancellationException)
                assertEquals(ProtectionStats(0,0),gateway.protectionStats());assertTrue(pool.results(target.platform).isEmpty())
            }finally {dispose(gateway)}
        }
    }
    @Test fun anInitialSourceFetchDoesNotLeakUnselectedResultsIntoTheContinuationConsumer():Unit=runBlocking {
        ReelFixture {MockResponse().setBody(hit("https://www.instagram.com/reel/SlowReel/"))}.use {f->
            var callbacks=0
            val page=withContext(SearchDiscoveryUpdates(f.target){_,_->callbacks++}){f.pool.sourceReader(f.source).fetch(f.target)}
            assertTrue(page.collections.isNotEmpty());assertEquals(0,callbacks)
        }
    }
    @Test fun completedPriorityRepliesBeatTheHedgeAfterSlowConsumerAcknowledgment():Unit=runBlocking {
        MockWebServer().use {server->
            val target=routes.search(Platform.TIKTOK,"nasa");val queries=PublicSearchAdapter.postQueries(target);val counts=List(3){AtomicInteger()}
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val lane=queries.indexOf(request.requestUrl!!.queryParameter("q"));check(lane>=0)
                val call=counts[lane].incrementAndGet()
                if(lane>0&&call==1)return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                val url=if(lane==1)"https://www.tiktok.com/@nasa/photo/2234567890"else "https://www.tiktok.com/@nasa/video/${lane+1}234567890"
                return MockResponse().setHeadersDelay(if(lane==2)50 else 0,TimeUnit.MILLISECONDS).setBody(hit(url))
            }}
            server.start();val gateway=gateway()
            try {
                val adapter=PublicSearchAdapter(Source(target.platform,"librey",server.url("/").toString().trimEnd('/')),gateway,routes)
                val first=adapter.fetch(target);val plan=withFastContinuation(requireNotNull(first.next),server,queries[0],1)
                var partials=0
                val page=withTimeout(2_000){adapter.searchUpdates(target,plan){partials++;if(partials==1)delay(400)}}
                assertEquals("Already parsed priority work must be consumed before a new hedge",1,counts[0].get())
                assertEquals(2,partials);assertEquals(2,page.collections.size);assertNotNull(page.next)
            }finally {dispose(gateway)}
        }
    }
    @Test fun unrelatedSearchReadinessKeepsTheDirectContinuationAndLearnsFirstDeliveryTime():Unit=runBlocking {
        val release=CountDownLatch(1)
        ReelFixture {check(release.await(5,TimeUnit.SECONDS));MockResponse().setBody(hit("https://www.instagram.com/reel/SlowReel/"))}.use {f->
            try {
                val pages=mutableListOf<Page>();var firstAt=0L;var began=0L
                val previous=Page(emptyList(),next=f.plan(),provenance=Provenance(f.source,0,f.source.root))
                began=System.nanoTime()
                val page=withTimeout(3_000){withContext(SearchDiscoveryUpdates(f.target){partial,check->
                    pages+=partial
                    if(firstAt==0L) {
                        firstAt=System.nanoTime();f.gateway.configureTor(NetworkPolicy(true,12345,true));check()
                        delay(450);release.countDown()
                    }
                }){f.pool.continuePage(f.target,previous,false)}}
                assertEquals(2,pages.size);assertTrue(page.collections.any {it.target.canonicalUrl.contains("SlowReel")})
                val learned=f.pool.results(Platform.INSTAGRAM).single()
                assertEquals(HealthStatus.HEALTHY,learned.status);assertFalse(learned.tor)
                assertTrue("Learn acquisition time, excluding caller backpressure and the held tail",learned.latencyMs<=(firstAt-began)/1_000_000+100)
                assertTrue((System.nanoTime()-began)/1_000_000>=learned.latencyMs+350)
                assertEquals(2,f.postReads.get());assertEquals(2,f.reelReads.get())
            }finally {release.countDown()}
        }
    }
    @Test fun streamedContinuationsStopAtAnEarlyOrLateProviderRefusal():Unit=runBlocking {
        for(wait in listOf(0L,600L))ReelFixture {MockResponse().setHeadersDelay(wait,TimeUnit.MILLISECONDS).setBody("<div id='challenge-container'>Verify you are human</div>")}.use {f->
            val partials=mutableListOf<Page>()
            val result=runCatching {withTimeout(2_000){updates(f){p,check->check();partials+=p}}}
            if(wait==0L) {
                assertEquals(HealthStatus.CHALLENGE,(result.exceptionOrNull() as SourceFailure).status)
                assertTrue(partials.isEmpty());assertEquals(1,f.postReads.get())
            }else {
                val page=result.getOrThrow();assertNull(page.next);assertNotNull(page.notice)
                assertEquals(1,partials.size);assertTrue(partials.single().collections.any {it.target.canonicalUrl.contains("/p/Fast2")})
                assertEquals(2,f.postReads.get())
            }
            assertEquals(2,f.reelReads.get())
        }
    }
    @Test fun usefulPartialResultsPreserveTheProvidersRateLimitAndRetryAfter():Unit=runBlocking {
        ReelFixture {MockResponse().setHeadersDelay(600,TimeUnit.MILLISECONDS).setResponseCode(429).setHeader("Retry-After","120")}.use {f->
            val partials=mutableListOf<Page>()
            val page=withTimeout(2_000){updates(f){p,check->check();partials+=p}}
            assertTrue(page.collections.any {it.target.canonicalUrl.contains("/p/Fast2")})
            assertEquals(1,partials.size);assertNull(page.next);assertNotNull(page.notice)
            val probe=f.pool.results(f.target.platform).single()
            assertEquals("A delivered page must not erase the provider's refusal",HealthStatus.RATE_LIMITED,probe.status)
            assertEquals(120_000L,probe.retryAfterMs)
            assertTrue(f.pool.learning.eligibleAt(f.source,Capability.SEARCH,false,f.target)-System.currentTimeMillis()>110_000)
            assertTrue(f.pool.learning.get(f.source,Capability.SEARCH,false)!!.deliverySamples>0)
        }
    }
    @Test fun aSavedContinuationCannotSendRequestsDuringAKnownProviderCooldown():Unit=runBlocking {
        ReelFixture {MockResponse().setBody(hit("https://www.instagram.com/reel/SlowReel/"))}.use {f->
            val previous=Page(emptyList(),next=f.plan(),provenance=Provenance(f.source,0,f.source.root))
            f.pool.learning.observe(ProbeResult(f.source,Capability.SEARCH,HealthStatus.RATE_LIMITED,10,System.currentTimeMillis(),false,"Source is rate limited",120_000))
            val before=f.server.requestCount
            val result=runCatching {withTimeout(2_000){f.pool.continuePage(f.target,previous,false)}}
            assertEquals("A saved cursor does not authorize another request during the pause",before,f.server.requestCount)
            assertTrue(result.exceptionOrNull() is SourceFailure)
            assertTrue((result.exceptionOrNull() as SourceFailure).retryAfterMs!!>110_000)
        }
    }

    @Test fun availablePagesReachTheReaderWhileADeferredQueryIsStillWaiting():Unit=runBlocking {
        val release=CountDownLatch(1);val tailStarted=CompletableDeferred<Unit>()
        ReelFixture {tailStarted.complete(Unit);check(release.await(5,TimeUnit.SECONDS));MockResponse().setBody(hit("https://www.instagram.com/reel/SlowReel/"))}.use {f->
            val second=CompletableDeferred<Unit>();val snapshots=mutableListOf<List<String>>()
            val feed=SearchFeed(f.target,discover={previous->
                if(previous==null)f.adapter.fetch(f.target).let {it.copy(next=withFastContinuation(requireNotNull(it.next),f.server,f.queries[0],1))}
                else f.pool.continuePage(f.target,previous,false)
            },read={requested->if(requested.kind==TargetKind.POST)Page(listOf(Post(requested.identity,requested.platform,requested.canonicalUrl,"Fixture",requested.canonicalUrl,publishedAt=1_792_800_000_000L)))else Page(emptyList(),profile=Profile(requested,"NASA fixture"))})
            val work=launch {feed.loadNext {state->
                snapshots+=state.page.posts.map {it.canonicalUrl}
                if(state.page.posts.any {it.canonicalUrl.contains("/p/Fast2")})second.complete(Unit)
            }}
            try {
                withTimeout(2_000){tailStarted.await();second.await()}
                assertFalse("The tail stays caller-owned and active while a useful page is visible",work.isCompleted)
                assertTrue(feed.state.loading);assertTrue(feed.state.page.posts.none {it.canonicalUrl.contains("/reel/")})
                release.countDown();withTimeout(3_000){work.join()}
                assertEquals(3,feed.state.page.posts.size)
                assertTrue(feed.state.page.posts.any {it.canonicalUrl.contains("/reel/SlowReel")})
                assertEquals(2,f.postReads.get());assertEquals(2,f.reelReads.get())
            }finally {
                println("Tail streaming observed: fastReads=${f.postReads.get()}, tailReads=${f.reelReads.get()}, snapshots=$snapshots")
                release.countDown();work.cancelAndJoin()
            }
        }
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
