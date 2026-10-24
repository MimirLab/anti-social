/* anti social, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.SocketPolicy

/** A useful RSS head and a deeper HTML timeline are separate, real reads. */
class FeedHistoryRoutingTest {
    private class Fixture(count:Int=1,frontends:List<String> = List(count){"nitter"}):AutoCloseable {
        val servers=List(count){MockWebServer().apply {start()}}
        val paths=List(count){Collections.synchronizedList(mutableListOf<String>())}
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {
            override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)
        },true).apply {originalFallback=false}
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {
            add("sources",JsonParser.parseString(servers.mapIndexed {i,server->
                """{"platform":"twitter","frontend":"${frontends[i]}","root":"${server.url("/").toString().trimEnd('/')}","fallbackOnly":false}"""
            }.joinToString(",","[","]")));add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes)
        val target=routes.target(Platform.X,"NASA")
        fun respond(index:Int=0,read:(String)->MockResponse) {
            servers[index].dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val path=requireNotNull(request.path);paths[index]+=path;return read(path)
            }}
        }
        suspend fun collect(known:Set<String> = setOf("twitter:"+ContentIdentity.key(Platform.X,"https://x.com/NASA/status/100")),pages:Int=8,posts:Int=300,deadline:Long=5_000)=
            FeedCatchUp(pages,posts,deadline).collect(Platform.X,known,
                first={pool.load(target,feed=true,originalFallback=false)},
                next={pool.continuePage(target,it,originalFallback=false,requestedCapability=Capability.FEED)})
        override fun close() {
            gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()
            servers.forEach {it.close()}
        }
    }
    private fun rss()="""<rss><channel><title>NASA</title><link>https://x.com/NASA</link><item><title>Flight notice</title><link>https://x.com/NASA/status/105</link><description>The full arrival instructions and required equipment.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate><enclosure url="https://media.example/flight.jpg" type="image/jpeg"/></item></channel></rss>"""
    private fun timeline(vararg ids:Int,next:String="")=ids.joinToString("") {id->"""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/$id"></a><div class="tweet-content">${if(id==105)"Shorter copy"else "Public notice $id"}</div><span class="tweet-date"><a title="Oct 23, 2026 · 11:00 AM UTC"></a></span></div>"""}+next
    private fun body(text:String)=MockResponse().setBody(text)

    @Test fun aFiniteRssHeadCanBridgeItsPreviousWindowThroughTheSameSourcesTimeline():Unit=runBlocking {
        Fixture().use {f->
            f.respond {if(it=="/NASA/rss")body(rss())else body(timeline(105,104,100))}
            val result=f.collect()
            assertNull("A real timeline should bridge the shallow RSS head",result.pendingReason)
            assertEquals(listOf("post:105","post:100","post:104"),result.page.posts.map {it.id})
            assertEquals(listOf("/NASA/rss","/NASA"),f.paths.single())
            val retained=result.page.posts.first()
            assertEquals("The full arrival instructions and required equipment.",retained.body)
            assertEquals("https://media.example/flight.jpg",retained.media.single().url)
            assertTrue(retained.provenance!!.finalUrl.endsWith("/NASA/rss"))
            assertEquals(setOf(Capability.FEED,Capability.TIMELINE),f.pool.results(Platform.X).filter {it.status==HealthStatus.HEALTHY}.map {it.capability}.toSet())
        }
    }
    @Test fun initialAndOverlappingHeadsKeepTheSingleCheapRead():Unit=runBlocking {
        for(known in listOf(emptySet(),setOf("twitter:"+ContentIdentity.key(Platform.X,"https://x.com/NASA/status/105"))))Fixture().use {f->
            f.respond {if(it=="/NASA/rss")body(rss())else error("No history work is needed")}
            assertNull(f.collect(known).pendingReason)
            assertEquals(listOf("/NASA/rss"),f.paths.single())
        }
    }
    @Test fun aFailedHistoryFormatCanUseAnotherNativeSourceWithoutDiscardingTheHead():Unit=runBlocking {
        Fixture(2).use {f->
            f.respond(0){if(it=="/NASA/rss")body(rss())else MockResponse().setResponseCode(503)}
            f.respond(1){body(timeline(104,100))}
            val result=f.collect()
            assertNull("A different native route should complete the gap",result.pendingReason)
            assertEquals(setOf("post:105","post:104","post:100"),result.page.posts.map {it.id}.toSet())
            assertEquals(listOf("/NASA/rss","/NASA"),f.paths[0]);assertEquals(listOf("/NASA"),f.paths[1])
            assertEquals(HealthStatus.HEALTHY,f.pool.results(Platform.X).single {it.source==f.routes.sources[0]&&it.capability==Capability.FEED}.status)
            assertEquals(HealthStatus.UNAVAILABLE,f.pool.results(Platform.X).single {it.source==f.routes.sources[0]&&it.capability==Capability.TIMELINE}.status)
        }
    }
    @Test fun deeperWindowsWithoutTheOldHeadStayPendingWithinThreeAdditionalStarts():Unit=runBlocking {
        Fixture(6).use {f->
            f.servers.indices.forEach {i->f.respond(i){path->if(i==0&&path=="/NASA/rss")body(rss())else body(timeline(104-i))}}
            val result=f.collect()
            assertNotNull(result.pendingReason)
            assertEquals(4,result.pages)
            assertEquals(4,f.servers.sumOf {it.requestCount})
            assertEquals(0,f.servers.drop(3).sumOf {it.requestCount})
            assertTrue(result.page.posts.any {it.id=="post:105"&&it.media.size==1})
        }
    }
    @Test fun pageAndPostLimitsDoNotStartHistoryWork():Unit=runBlocking {
        for(limitPages in listOf(true,false))Fixture().use {f->
            f.respond {path->if(path=="/NASA/rss")body(rss())else error("The shared catch-up allowance was exhausted")}
            val result=f.collect(pages=if(limitPages)1 else 8,posts=if(limitPages)300 else 1)
            assertNotNull(result.pendingReason);assertEquals(1,result.pages)
            assertEquals(listOf("/NASA/rss"),f.paths.single())
        }
    }
    @Test fun aRetainedPageDoesNotCarryAuthorityToStartAnotherRoute():Unit=runBlocking {
        Fixture().use {f->
            f.respond {path->if(path=="/NASA/rss")body(rss())else error("A retained Page cannot authorize recovery")}
            val retained=f.pool.load(f.target,feed=true,originalFallback=false)
            val copied=com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(retained),Page::class.java)
            val result=FeedCatchUp().collect(Platform.X,setOf("twitter:post:100"),first={copied},next={f.pool.continuePage(f.target,it,false,Capability.FEED)})
            assertNotNull(result.pendingReason);assertEquals(listOf("/NASA/rss"),f.paths.single())
        }
    }
    @Test fun anOwnedDeadlineKeepsTheHeadAndCancelsUnfinishedHistory():Unit=runBlocking {
        Fixture().use {f->
            f.respond {if(it=="/NASA/rss")body(rss())else body(timeline(100)).setBodyDelay(2,TimeUnit.SECONDS)}
            val result=f.collect(deadline=250)
            assertEquals(listOf("post:105"),result.page.posts.map {it.id})
            assertTrue(requireNotNull(result.pendingReason).contains("timed out"))
            assertTrue(f.pool.results(Platform.X).none {it.capability==Capability.TIMELINE})
        }
    }
    @Test fun callerCancellationDoesNotPublishAPartialRefreshOrTrainHistoryFailure():Unit=runBlocking {
        Fixture().use {f->
            f.respond {if(it=="/NASA/rss")body(rss())else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)}
            val read=async(Dispatchers.IO){f.collect(deadline=5_000)}
            try {repeat(2){assertNotNull(f.servers.single().takeRequest(2,TimeUnit.SECONDS))}}
            finally {read.cancelAndJoin()}
            assertTrue(read.isCancelled)
            assertTrue(f.pool.results(Platform.X).none {it.capability==Capability.TIMELINE})
        }
    }
    @Test fun connectionRoundTripsAndOriginalPermissionChangesCancelTheInitialPlan():Unit=runBlocking {
        for(modeChange in listOf(true,false))Fixture().use {f->
            f.respond {if(it=="/NASA/rss")body(rss())else error("A superseded policy cannot start another route")}
            try {
                FeedCatchUp().collect(Platform.X,setOf("twitter:post:100"),first={f.pool.load(f.target,feed=true,originalFallback=false)},next={page->
                    if(modeChange){f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())}else f.gateway.originalFallback=true
                    f.pool.continuePage(f.target,page,false,Capability.FEED)
                })
                fail("A policy change must cancel the complete refresh")
            }catch(_:CancellationException){}
            assertEquals(listOf("/NASA/rss"),f.paths.single())
            assertTrue(f.pool.results(Platform.X).none {it.capability==Capability.TIMELINE})
        }
    }
    @Test fun anUndatedTimelineDoesNotWinHistoryButAnotherDatedRouteCan():Unit=runBlocking {
        Fixture(2).use {f->
            f.respond(0){if(it=="/NASA/rss")body(rss())else body(timeline(100).replace(Regex("<span class=\"tweet-date\">.*?</span>"),""))}
            f.respond(1){body(timeline(100))}
            val result=f.collect()
            assertNull(result.pendingReason)
            assertEquals(listOf("/NASA"),f.paths[1])
            assertTrue(result.page.posts.all {it.publishedAt!=null})
        }
    }
    @Test fun aHistoryCooldownDoesNotDamageTheHealthyHeadOrGetRetriedEarly():Unit=runBlocking {
        Fixture(2).use {f->
            f.respond(0){if(it=="/NASA/rss")body(rss())else error("Timeline cooldown must remain effective")}
            f.respond(1){body(timeline(100))}
            f.pool.restore(listOf(ProbeResult(f.routes.sources[0],Capability.TIMELINE,HealthStatus.RATE_LIMITED,5,System.currentTimeMillis(),false,"Wait",120_000)))
            val result=f.collect()
            assertNull(result.pendingReason);assertEquals(listOf("/NASA/rss"),f.paths[0])
            assertEquals(HealthStatus.HEALTHY,f.pool.results(Platform.X).single {it.capability==Capability.FEED}.status)
        }
    }
    @Test fun aSelectedTimelineCursorStaysWithItsSourceAndFailureCanTryAnother():Unit=runBlocking {
        for(failCursor in listOf(false,true))Fixture(2).use {f->
            val cursor=f.servers[0].url("/NASA?cursor=OwnCaseToken").toString()
            f.respond(0){path->when(path){
                "/NASA/rss"->body(rss())
                "/NASA"->body(timeline(104,next="""<div class="show-more"><a href="$cursor">Load more</a></div>"""))
                "/NASA?cursor=OwnCaseToken"->if(failCursor)MockResponse().setResponseCode(503)else body(timeline(100))
                else->error("Never invent a continuation: $path")
            }}
            f.respond(1){path->assertEquals("/NASA",path);body(timeline(100))}
            val result=FeedCatchUp(timeoutMillis=5_000).collect(Platform.X,setOf("twitter:post:100"),
                first={f.pool.load(f.target,feed=true,originalFallback=false)},next={previous->
                    val page=f.pool.continuePage(f.target,previous,false,Capability.FEED)
                    // Production HTML admits HTTPS links. Supply this owned
                    // loopback cursor explicitly to exercise actual continuation
                    // I/O without weakening that extraction rule for the fixture.
                    if(page.provenance?.source==f.routes.sources[0]&&page.posts.singleOrNull()?.id=="post:104")page.copy(next=cursor)else page
                })
            assertNull(result.pendingReason)
            assertEquals(listOf("/NASA/rss","/NASA","/NASA?cursor=OwnCaseToken"),f.paths[0])
            assertEquals(if(failCursor)listOf("/NASA")else emptyList<String>(),f.paths[1])
        }
    }
    @Test fun aPermittedOriginalRemainsAfterAlternatesWithinTheSameAllowance():Unit=runBlocking {
        Fixture(4).use {f->
            f.gateway.originalFallback=true
            val target=f.target.copy(canonicalUrl=f.servers[3].url("/NASA").toString())
            val starts=Collections.synchronizedList(mutableListOf<Int>())
            f.servers.indices.forEach {i->f.respond(i){path->
                if(i==0&&path=="/NASA/rss")body(rss())else {starts+=i;if(i==3)body(timeline(100))else MockResponse().setResponseCode(503)}
            }}
            val result=FeedCatchUp(timeoutMillis=5_000).collect(Platform.X,setOf("twitter:post:100"),
                first={f.pool.load(target,feed=true,originalFallback=true)},
                next={f.pool.continuePage(target,it,true,Capability.FEED)})
            assertNull(result.pendingReason)
            assertEquals(listOf(0,1,3),starts)
            assertEquals(0,f.servers[2].requestCount)
            assertEquals(4,f.servers.sumOf {it.requestCount})
        }
    }
    @Test fun aContinuationCannotExpandTheOriginalPermissionOfItsInitialRead():Unit=runBlocking {
        Fixture(4).use {f->
            f.gateway.originalFallback=true
            val target=f.target.copy(canonicalUrl=f.servers[3].url("/NASA").toString())
            f.servers.indices.forEach {i->f.respond(i){path->
                if(i==0&&path=="/NASA/rss")body(rss())else MockResponse().setResponseCode(503)
            }}
            val result=FeedCatchUp(timeoutMillis=5_000).collect(Platform.X,setOf("twitter:post:100"),
                first={f.pool.load(target,feed=true,originalFallback=false)},
                next={f.pool.continuePage(target,it,true,Capability.FEED)})
            assertNotNull(result.pendingReason);assertEquals(0,f.servers[3].requestCount)
            assertEquals(4,f.servers.sumOf {it.requestCount})
        }
    }
    @Test fun aFiniteApiHeadUsesAnotherMethodWithoutRepeatingItsIdenticalFetch():Unit=runBlocking {
        Fixture(2,listOf("sotwe-api","nitter")).use {f->
            f.respond(0){path->assertEquals("/api/v3/user/NASA/",path);body("""{"key":"NASA","info":{"id":"1","screenName":"NASA","name":"NASA"},"data":[{"id":"105","text":"The original full notice","createdAt":"2026-10-23T12:00:00Z"}]}""")}
            f.respond(1){path->assertEquals("/NASA",path);body(timeline(100))}
            val result=f.collect()
            assertEquals("Finite API windows need a history route too",setOf("post:105","post:100"),result.page.posts.map {it.id}.toSet())
            assertNull(result.pendingReason)
            assertEquals(1,f.servers[0].requestCount);assertEquals(1,f.servers[1].requestCount)
        }
    }
    @Test fun anHtmlHeadAfterFailedRssIsNotFetchedAgainAfterItsCursorFails():Unit=runBlocking {
        Fixture(2).use {f->
            val cursor=f.servers[0].url("/NASA?cursor=owned-history").toString()
            f.respond(0){path->if(path=="/NASA")body(timeline(105))else MockResponse().setResponseCode(503)}
            f.respond(1){body(timeline(100))}
            val result=FeedCatchUp(timeoutMillis=5_000).collect(Platform.X,setOf("twitter:post:100"),
                first={f.pool.load(f.target,feed=true,originalFallback=false).copy(next=cursor)},
                next={f.pool.continuePage(f.target,it,false,Capability.FEED)})
            assertNull(result.pendingReason)
            assertEquals("The initial feed already fell back to this HTML representation",listOf("/NASA/rss","/NASA","/NASA?cursor=owned-history"),f.paths[0])
            assertEquals(listOf("/NASA"),f.paths[1])
        }
    }
}
