/* anti social, GPL-3.0-or-later. Public RSS protocol over owned Gateway fixtures. */
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
import java.util.concurrent.atomic.AtomicInteger

class NitterFeedHistoryTest {
    private class Fixture(base:String=""):AutoCloseable {
        val server=MockWebServer().apply {start()}
        val requests=Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        var rewrite:(String)->String?={null}
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false,rewrittenUrl=rewrite(url))},true).apply {originalFallback=false}
        val clients=mutableSetOf(gateway.client())
        val root=server.url("/").toString().trimEnd('/')
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {
            add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"$root$base","fallbackOnly":false}]"""));add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes);val target=routes.target(Platform.X,"NASA")
        val source get()=routes.sources.single()
        val reader=SyndicationReader(gateway)
        val parses=AtomicInteger()
        fun respond(read:(RecordedRequest)->MockResponse) {server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            requests+=request;return read(request)
        }}}
        suspend fun head():Page {clients+=gateway.client();return pool.load(target,feed=true,originalFallback=false)}
        suspend fun next(page:Page):Page {clients+=gateway.client();return pool.continuePage(target,page,false,Capability.FEED)}
        suspend fun read(url:String?=null,target:Target=this.target,source:Source=this.source):Page {
            clients+=gateway.client()
            return reader.read(url ?: source.root+"/NASA/rss",target,source,1024L*1024,emptyMap()){payload->parses.incrementAndGet();Extraction.parse(payload,target,source,routes)}
        }
        override fun close(){clients.forEach {it.dispatcher.executorService.shutdownNow();it.connectionPool.evictAll()};server.close()}
    }
    private fun feed(id:Int=105)="""<rss><channel><title>NASA</title><link>https://x.com/NASA</link><item><title>Public notice $id</title><link>https://x.com/NASA/status/$id</link><description>Required equipment and arrival instructions $id.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""
    private fun response(id:Int=105,cursor:String?="DAA+/=:KeepCase")=MockResponse().setHeader("Content-Type","application/rss+xml").setBody(feed(id)).apply {cursor?.let {setHeader("Min-Id",it)}}

    @Test fun aPublishedHeaderCursorContinuesTheSameAccountThroughRss():Unit=runBlocking {
        Fixture().use {f->
            val token="DAA+/=:KeepCase"
            f.respond {request->if(request.path=="/NASA/rss")response(cursor=token)else response(100,null)}
            val first=f.head()
            assertNotNull("Nitter's published Min-Id must survive as scoped feed navigation",first.next)
            val second=f.next(first)
            assertEquals(listOf("post:100"),second.posts.map {it.id})
            assertEquals(2,f.requests.size)
            val request=f.requests.last();assertEquals("/NASA/rss",request.requestUrl!!.encodedPath)
            assertEquals(setOf("cursor"),request.requestUrl!!.queryParameterNames)
            assertEquals(token,request.requestUrl!!.queryParameter("cursor"))
            assertFalse(second.provenance!!.cached)
            assertEquals(setOf(Capability.FEED,Capability.TIMELINE),f.pool.results(Platform.X).map {it.capability}.toSet())
        }
    }
    @Test fun aHeaderCursorJoinsTheOldHeadWithoutRequestingHtml():Unit=runBlocking {
        Fixture().use {f->
            f.respond {request->when {
                request.path=="/NASA/rss"->response()
                request.requestUrl!!.encodedPath=="/NASA/rss"->response(100,null)
                else->MockResponse().setResponseCode(503)
            }}
            val result=FeedCatchUp(timeoutMillis=3_000).collect(Platform.X,setOf("twitter:post:100"),first={f.head()},next={f.next(it)})
            assertNull("The RSS header path should bridge the prior reading window",result.pendingReason)
            assertEquals(setOf("post:105","post:100"),result.page.posts.map {it.id}.toSet())
            assertEquals(2,result.pages);assertEquals(2,f.requests.size)
            assertTrue(f.requests.all {it.requestUrl!!.encodedPath=="/NASA/rss"})
        }
    }
    @Test fun initialAndOverlappingReadsDoNotFollowTheHeader():Unit=runBlocking {
        for(known in listOf(emptySet(),setOf("twitter:post:105")))Fixture().use {f->
            f.respond {request->assertEquals("/NASA/rss",request.path);response()}
            val result=FeedCatchUp().collect(Platform.X,known,first={f.head()},next={error("No missing interval")})
            assertNull(result.pendingReason);assertEquals(1,f.requests.size)
        }
    }
    @Test fun aRepeatedRssWindowCanRecoverThroughItsNativeTimeline():Unit=runBlocking {
        Fixture().use {f->
            f.respond {request->if(request.requestUrl!!.encodedPath=="/NASA/rss")response()else MockResponse().setHeader("Content-Type","text/html").setBody("""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/100"></a><div class="tweet-content">Earlier public arrival instructions.</div><span class="tweet-date"><a title="Oct 23, 2026 · 12:00 PM UTC"></a></span></div>""")}
            val result=FeedCatchUp(timeoutMillis=3_000).collect(Platform.X,setOf("twitter:post:100"),first={f.head()},next={f.next(it)})
            assertNull(result.pendingReason)
            assertEquals(setOf("post:105","post:100"),result.page.posts.map {it.id}.toSet())
            assertEquals(3,result.pages);assertEquals(3,f.requests.size)
            assertEquals(listOf("/NASA/rss","/NASA/rss","/NASA"),f.requests.map {it.requestUrl!!.encodedPath})
            assertEquals("DAA+/=:KeepCase",f.requests[1].requestUrl!!.queryParameter("cursor"))
            assertNull(f.requests.last().requestUrl!!.query)
        }
    }
    @Test fun identicalBodiesUseTheCurrentHeaderAndDoNotReparseTheXml():Unit=runBlocking {
        Fixture().use {f->
            val cursors=listOf("first+/=","second+/=",null)
            f.respond {request->if(request.requestUrl!!.queryParameter("cursor")!=null)response(100,null)else response(cursor=cursors[f.requests.count {it.requestUrl!!.query==null}-1])}
            val first=f.read();val second=f.read()
            assertNotEquals(first.next,second.next);assertNotNull(second.next)
            f.next(second);assertEquals("second+/=",f.requests.last().requestUrl!!.queryParameter("cursor"))
            assertNull(f.read().next)
            assertEquals(1,f.parses.get());assertEquals(4,f.requests.size)
        }
    }
    @Test fun validated304CanKeepReplaceOrRemoveNavigationWithoutReparsing():Unit=runBlocking {
        Fixture().use {f->
            f.respond {request->when(f.requests.size){
                1->response(cursor="first").setHeader("ETag","\"same\"")
                2->MockResponse().setResponseCode(304).setHeader("ETag","\"same\"")
                3->MockResponse().setResponseCode(304).setHeader("ETag","\"same\"").setHeader("Min-Id","replacement")
                else->MockResponse().setResponseCode(304).setHeader("ETag","\"same\"").addHeader("Min-Id","ambiguous-one").addHeader("Min-Id","ambiguous-two")
            }.also {if(f.requests.size>1)assertEquals("\"same\"",request.getHeader("If-None-Match"))}}
            val first=f.read();assertNotNull(first.next)
            assertEquals(first.next,f.read().next)
            val replaced=f.read();assertNotEquals(first.next,replaced.next);assertNotNull(replaced.next)
            assertNull(f.read().next);assertEquals(1,f.parses.get())
        }
    }
    @Test fun noStoreDoesNotKeepAHeaderOrItsRepresentationForTheNextRead():Unit=runBlocking {
        Fixture().use {f->
            f.respond {response(cursor=if(f.requests.size==1)"one"else null).setHeader("ETag","\"same\"").setHeader("Cache-Control","no-store")}
            assertNotNull(f.read().next);assertEquals(0,f.reader.retainedBytes())
            assertNull(f.read().next);assertEquals(2,f.parses.get())
            assertTrue(f.requests.all {it.getHeader("If-None-Match")==null})
        }
    }
    @Test fun malformedAndAmbiguousHeadersLeaveTheReadableFeedUsable():Unit=runBlocking {
        for(value in listOf(""," ","a b","a,b","suspended","x".repeat(4097)))Fixture().use {f->
            f.respond {response(cursor=value)}
            val page=f.head();assertNull(page.next);assertEquals("post:105",page.posts.single().id)
        }
        Fixture().use {f->
            f.respond {response().addHeader("min-id","second")}
            assertNull(f.head().next)
        }
    }
    @Test fun punctuationInAnOpaqueTokenCannotBecomeAnotherUrlOrQueryField():Unit=runBlocking {
        Fixture().use {f->
            val token="next?x=https://unrelated.example/path#frag&accept=true+/%25="
            f.respond {request->if(request.requestUrl!!.query==null)response(cursor=token)else response(100,null)}
            f.next(f.head())
            val next=f.requests.last().requestUrl!!
            assertEquals("/NASA/rss",next.encodedPath);assertEquals(setOf("cursor"),next.queryParameterNames)
            assertEquals(token,next.queryParameter("cursor"));assertEquals(f.server.port,next.port)
        }
    }
    @Test fun theHeaderDoesNotActivateForOrdinaryFeedsOrAnotherAccountsPath():Unit=runBlocking {
        Fixture().use {f->
            f.respond {response()}
            assertNull(f.read(source=f.source.copy(frontend="rss")).next)
            assertNull(f.read(url=f.root+"/Other/rss").next)
            assertNull(f.read(target=f.target.copy(kind=TargetKind.SEARCH)).next)
        }
    }
    @Test fun anHtmlResponseWithADatedPostDoesNotAcquireAnRssCursor():Unit=runBlocking {
        Fixture().use {f->
            f.respond {MockResponse().setHeader("Min-Id","not-rss").setBody("""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/105"></a><div class="tweet-content">Useful native notice</div><span class="tweet-date"><a title="Oct 23, 2026 · 12:00 PM UTC"></a></span></div>""")}
            val page=f.head();assertEquals(1,page.posts.size);assertNull(page.next)
        }
    }
    @Test fun sourcePrefixesAndCanonicalAccountCaseRemainBound():Unit=runBlocking {
        Fixture("/front").use {f->
            f.respond {request->when {
                request.path=="/front/NASA/rss"->MockResponse().setResponseCode(302).setHeader("Location",f.root+"/front/nasa/rss")
                request.requestUrl!!.query==null->response()
                else->response(100,null)
            }}
            f.next(f.head())
            assertEquals("/front/nasa/rss",f.requests.last().requestUrl!!.encodedPath)
        }
    }
    @Test fun redirectsCannotMoveHeaderNavigationToAnotherAccountOrCursor():Unit=runBlocking {
        for(path in listOf("/Other/rss?cursor=other","/NASA/rss?cursor=replaced"))Fixture().use {f->
            f.respond {request->if(request.requestUrl!!.query==null)response()else MockResponse().setResponseCode(302).setHeader("Location",f.root+path)}
            val result=runCatching {f.next(f.head())}
            assertTrue(result.isFailure);assertEquals(2,f.requests.size)
            assertEquals(HealthStatus.HEALTHY,f.pool.results(Platform.X).single {it.capability==Capability.FEED}.status)
        }
        Fixture().use {f->MockWebServer().use {other->
            other.start();f.respond {request->if(request.requestUrl!!.query==null)response()else MockResponse().setResponseCode(302).setHeader("Location",other.url("/NASA/rss?cursor=other"))}
            assertTrue(runCatching {f.next(f.head())}.isFailure);assertEquals(0,other.requestCount)
        }}
    }
    @Test fun aFilterRewriteCannotRetargetTheSelectedFeedRequest():Unit=runBlocking {
        Fixture().use {f->
            f.respond {response()};val page=f.head()
            f.rewrite={url->if(url.contains("cursor="))f.root+"/Other/rss"else null}
            assertTrue(runCatching {f.next(page)}.isFailure);assertEquals(1,f.requests.size)
        }
    }
    @Test fun aModeRoundTripOrAnotherGatewayCannotReplayTheHeaderBookmark():Unit=runBlocking {
        Fixture().use {f->
            f.respond {response()};val first=f.head()
            Fixture().use {other->
                assertThrows(CancellationException::class.java){NitterFeedHistory.resolve(requireNotNull(first.next),f.target,f.source,other.gateway)}
            }
            f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())
            assertThrows(CancellationException::class.java){runBlocking {f.next(first)}}
            assertEquals(1,f.requests.size)
            assertTrue(f.pool.results(Platform.X).none {it.capability==Capability.TIMELINE})
            val replacement=f.head();assertNotEquals(first.next,replacement.next)
            assertNull(f.requests.last().getHeader("If-None-Match"))
        }
    }
    @Test fun aModeChangeBetweenBookmarkDecodingAndIoStillCancelsBeforeTheWire():Unit=runBlocking {
        Fixture().use {f->
            f.respond {response()};val page=f.head()
            val selected=NitterFeedHistory.resolve(requireNotNull(page.next),f.target,f.source,f.gateway)
            f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())
            assertThrows(CancellationException::class.java){runBlocking {
                f.gateway.feedContinuation(selected.url,f.target,f.source,selected.session){Extraction.parse(it,f.target,f.source,f.routes)}
            }}
            assertEquals(1,f.requests.size)
        }
    }
    @Test fun localSessionIdentityIsNotTransmittedInTheCursorRequest():Unit=runBlocking {
        Fixture().use {f->
            f.respond {request->if(request.requestUrl!!.query==null)response()else response(100,null)}
            val epoch=f.gateway.currentSession().feedCursorEpoch;val first=f.head()
            assertTrue(requireNotNull(first.next).contains(epoch));f.next(first)
            assertTrue(f.requests.none {(it.requestUrl.toString()+it.headers.toString()).contains(epoch)})
            assertTrue(f.requests.all {it.getHeader("Cookie")==null&&it.getHeader("Authorization")==null})
        }
    }
    @Test fun malformedOrWrongScopeBookmarksDoNotStartARequest():Unit=runBlocking {
        Fixture().use {f->
            f.respond {response()};val first=f.head();val original=requireNotNull(first.next)
            val data=JsonParser.parseString(original.substringAfter(':')).asJsonObject
            val bad=listOf("nitter-rss-v1:"+"[".repeat(20_000),"nitter-rss-v1:{}",original+"{}",
                "nitter-rss-v1:"+data.deepCopy().apply {addProperty("target","https://x.com/Other")},
                "nitter-rss-v1:"+data.deepCopy().apply {addProperty("url",f.root+"/Other/rss?cursor=one")},
                "nitter-rss-v1:"+data.deepCopy().apply {addProperty("url",data["url"].asString+"\n")},
                "nitter-rss-v1:"+data.deepCopy().apply {add("url",com.google.gson.JsonNull.INSTANCE)},
                "nitter-rss-v1:"+data.deepCopy().apply {addProperty("unexpected","ignored?")})
            for(cursor in bad) {
                val result=runCatching {f.next(first.copy(next=cursor))}
                assertEquals(HealthStatus.EMPTY,(result.exceptionOrNull()as? SourceFailure)?.status)
            }
            assertEquals(1,f.requests.size);assertTrue(f.pool.results(Platform.X).none {it.capability==Capability.TIMELINE})
        }
    }
    @Test fun anOldHeaderCursorCannotGoDirectWhileSelectedTorIsUnavailable():Unit=runBlocking {
        Fixture().use {f->
            f.respond {response()};val first=f.head()
            f.gateway.configure(NetworkPolicy(tor=true,ready=false))
            val result=runCatching {f.pool.continuePage(f.target,first,false,Capability.FEED)}
            assertTrue(result.exceptionOrNull()is CancellationException)
            assertEquals(1,f.requests.size)
            assertTrue(f.pool.results(Platform.X).none {it.capability==Capability.TIMELINE})
        }
    }
    @Test fun publishedAccountTabsAndNumericListsRetainTheirExactOperation():Unit=runBlocking {
        for(path in listOf("NASA/with_replies","NASA/media","NASA/articles","i/lists/123456"))Fixture().use {f->
            val target=f.routes.target(Platform.X,path)
            f.respond {request->if(request.requestUrl!!.query==null)response()else response(100,null)}
            val page=f.read(url=f.root+"/$path/rss",target=target)
            assertNotNull(page.next)
            f.pool.continuePage(target,page,false,Capability.FEED)
            assertEquals("/$path/rss",f.requests.last().requestUrl!!.encodedPath)
            assertEquals(2,f.requests.size)
        }
    }
    @Test fun anAccountTabCursorCannotBeReusedForAnotherTabOrTheAccountHead():Unit=runBlocking {
        Fixture().use {f->
            f.respond {response()};val target=f.routes.target(Platform.X,"NASA/media")
            val page=f.read(url=f.root+"/NASA/media/rss",target=target)
            for(other in listOf(f.target,f.routes.target(Platform.X,"NASA/with_replies"))) {
                val result=runCatching {f.pool.continuePage(other,page,false,Capability.FEED)}
                assertEquals(HealthStatus.EMPTY,(result.exceptionOrNull()as?SourceFailure)?.status)
            }
            assertEquals(1,f.requests.size)
        }
    }
}
