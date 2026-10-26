package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SourcePoolRoutingTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private fun routes(vararg roots: String): DarwinRoutes {
        val catalog=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject
        catalog.add("sources",JsonParser.parseString(roots.joinToString(",","[","]"){"""{"platform":"twitter","frontend":"nitter","root":"$it","fallbackOnly":false}"""}))
        catalog.add("searchSources",com.google.gson.JsonArray())
        return DarwinRoutes(catalog,cleaner)
    }
    private fun gateway()=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
    private fun post(body: String)="""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/123"></a><div class="tweet-content">$body</div><span class="tweet-date"><a title="Oct 16, 2026 · 12:00 PM UTC"></a></span></div>"""
    @Test fun aModeRoundTripCancelsTheProbeWithoutTrainingAnOutage():Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(post("Public content")))
            lateinit var gateway:NetworkGateway
            var changed=false
            gateway=NetworkGateway(cleaner,object:RequestFilter {
                override fun check(url:String,sourceUrl:String,type:String):FilterDecision {
                    if(!changed){changed=true;gateway.configure(NetworkPolicy(tor=true));gateway.configure(NetworkPolicy())}
                    return FilterDecision(false)
                }
            },true)
            val local=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway,local)
            assertThrows(kotlinx.coroutines.CancellationException::class.java){runBlocking {pool.probe(local.sources.single(),Capability.TIMELINE,true)}}
            assertTrue(pool.results(Platform.X).isEmpty());assertTrue(pool.learning.snapshot().isEmpty())
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun aValidatedEmptyFacebookSearchPageCanAdvanceWithoutBeingMarkedHealthy(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":{"serpResponse":{"results":{"edges":[],"page_info":{"has_next_page":true,"end_cursor":"native-more"}}}}}"""))
            server.enqueue(MockResponse().setBody(JsonParser.parseReader(javaClass.getResourceAsStream("/facebook-search-results.json")!!.reader()).toString()))
            val catalog=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject
            catalog.add("sources",com.google.gson.JsonArray());catalog.add("searchSources",com.google.gson.JsonArray())
            val local=DarwinRoutes(catalog,cleaner)
            val gateway=NetworkGateway(cleaner,object: RequestFilter {
                override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl=if(url.startsWith("https://www.facebook.com/api/graphql/"))server.url("/graphql").toString()else null)
            },true)
            val pool=SourcePool(gateway,local).apply {searchProvider=SearchProvider.DARWIN}
            val target=local.search(Platform.FACEBOOK,"sampleton organic food")
            val empty=pool.load(target)
            assertTrue(empty.posts.isEmpty());assertTrue(requireNotNull(empty.next).startsWith("facebook:"))
            assertEquals(HealthStatus.EMPTY,pool.results(Platform.FACEBOOK).single().status)
            val next=pool.continuePage(target,empty)
            assertTrue(next.posts.isNotEmpty());assertEquals(HealthStatus.HEALTHY,pool.results(Platform.FACEBOOK).single().status)
            assertEquals(2,server.requestCount)
            repeat(2){val request=server.takeRequest();assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))}
            gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
        }
    }
    @Test fun fastFirstContentDoesNotContactBackups(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {backup->
            first.start();backup.start();first.enqueue(MockResponse().setBody(post("fast first")))
            val routes=routes(first.url("/").toString().trimEnd('/'),backup.url("/").toString().trimEnd('/'))
            val pool=SourcePool(gateway(),routes)
            assertEquals("fast first",pool.load(routes.target(Platform.X,"NASA"),originalFallback=false).posts.single().body)
            assertEquals(1,first.requestCount);assertEquals(0,backup.requestCount)
            assertEquals(1,pool.learning.snapshot().size)
        }}
    }
    @Test fun stalledFirstPathHedgesAndDoesNotRecordCancellationAsOutage(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {backup->
            first.start();backup.start();first.enqueue(MockResponse().setBody(post("slow first")).setBodyDelay(4,java.util.concurrent.TimeUnit.SECONDS));backup.enqueue(MockResponse().setBody(post("backup content")))
            val routes=routes(first.url("/").toString().trimEnd('/'),backup.url("/").toString().trimEnd('/'))
            val pool=SourcePool(gateway(),routes)
            val start=System.nanoTime();val page=pool.load(routes.target(Platform.X,"NASA"),originalFallback=false)
            assertEquals("backup content",page.posts.single().body)
            assertTrue("Backup must beat the stalled response",(System.nanoTime()-start)/1_000_000<3500)
            assertEquals(1,first.requestCount);assertEquals(1,backup.requestCount)
            assertTrue(pool.results(Platform.X).all {it.status==HealthStatus.HEALTHY})
        }}
    }
    @Test fun emptyReachableSourceLosesToUsableContent(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {backup->
            first.start();backup.start();first.enqueue(MockResponse().setBody("<title>Welcome to this frontend</title>"));backup.enqueue(MockResponse().setBody(post("real content")))
            val routes=routes(first.url("/").toString().trimEnd('/'),backup.url("/").toString().trimEnd('/'))
            val pool=SourcePool(gateway(),routes)
            assertEquals("real content",pool.load(routes.target(Platform.X,"NASA"),originalFallback=false).posts.single().body)
            assertEquals(HealthStatus.EMPTY,pool.results(Platform.X).first {it.source==routes.sources.first()}.status)
        }}
    }
    @Test fun anUndatedFirstSourceCannotWinAChronologicalFeedOverADatedBackup():Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {backup->
            first.start();backup.start()
            fun rss(date:String)="""<?xml version="1.0"?><rss><channel><item><title>Flight update</title><link>https://x.com/NASA/status/123</link><description>Complete public update</description>$date</item></channel></rss>"""
            first.enqueue(MockResponse().setBody(rss("")))
            backup.enqueue(MockResponse().setBody(rss("<pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate>")))
            val local=routes(first.url("/").toString().trimEnd('/'),backup.url("/").toString().trimEnd('/'))
            val pool=SourcePool(gateway(),local)
            val page=pool.load(local.target(Platform.X,"NASA"),feed=true,originalFallback=false)
            assertNotNull("Chronological feed routing must continue past undated content",page.posts.single().publishedAt)
            assertEquals(local.sources[1],page.provenance?.source)
            assertEquals(HealthStatus.EMPTY,pool.results(Platform.X).first {it.source==local.sources[0]}.status)
            assertEquals(1,first.requestCount);assertEquals(1,backup.requestCount)
        }}
    }
    @Test fun aDatedPreviewCannotTrainFeedHealthOrBeatACompleteDatedSource():Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {backup->
            first.start();backup.start()
            first.enqueue(MockResponse().setBody("""<html><head><title>Flight excerpt</title>
              <meta property="og:url" content="https://x.com/NASA/status/123"><meta property="og:type" content="article">
              <meta property="og:description" content="A small public excerpt"><meta property="og:image" content="https://pbs.twimg.com/preview.jpg">
              <meta property="article:published_time" content="2026-10-23T12:00:00Z"></head><body></body></html>"""))
            backup.enqueue(MockResponse().setBody("""<?xml version="1.0"?><rss><channel><item><title>Complete flight notice</title><link>https://x.com/NASA/status/123</link><description>Complete public instructions.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""))
            val local=routes(first.url("/").toString().trimEnd('/'),backup.url("/").toString().trimEnd('/'))
            val pool=SourcePool(gateway(),local)
            // Feed eligibility is a capability contract, independent of the
            // entity shape used to supply this explicitly excerpted fixture.
            val target=requireNotNull(local.recognize("https://x.com/NASA/status/123"))
            val page=pool.load(target,feed=true,originalFallback=false)
            assertFalse("A date alone cannot make an excerpt a complete feed entry",page.posts.single().preview)
            assertEquals(local.sources[1],page.provenance?.source)
            assertEquals(HealthStatus.EMPTY,pool.results(Platform.X).first {it.source==local.sources[0]}.status)
            assertEquals(1,first.requestCount);assertEquals(1,backup.requestCount)
        }}
    }
    @Test fun freshBackupNeedsNoAutomaticNetworkRead(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {backup->
            first.start();backup.start();val routes=routes(first.url("/").toString().trimEnd('/'),backup.url("/").toString().trimEnd('/'))
            val pool=SourcePool(gateway(),routes)
            pool.learning.observe(ProbeResult(routes.sources[1],Capability.TIMELINE,HealthStatus.HEALTHY,100,System.currentTimeMillis(),false))
            pool.maintain(routes.target(Platform.X,"NASA"),Capability.TIMELINE,routes.sources[0],false)
            assertEquals(0,first.requestCount+backup.requestCount)
        }}
    }
    @Test fun oversizedSoundingPreservesPreviousWorkingEvidence(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("x".repeat(600*1024)))
            val routes=routes(server.url("/").toString().trimEnd('/'));val source=routes.sources.single();val pool=SourcePool(gateway(),routes)
            val previous=ProbeResult(source,Capability.TIMELINE,HealthStatus.HEALTHY,100,System.currentTimeMillis()-3_600_001,false)
            pool.restore(listOf(previous));pool.learning.observe(previous)
            pool.probe(source,Capability.TIMELINE,force=true,automatic=true)
            assertEquals(previous,pool.results(Platform.X).single())
            assertEquals(1,pool.learning.get(source,Capability.TIMELINE,false)!!.samples)
        }
    }
    @Test fun unrelatedPostCardsCannotWinASelectedPostRead(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {backup->
            first.start();backup.start();first.enqueue(MockResponse().setBody(post("unrelated card").replace("status/123","status/456")));backup.enqueue(MockResponse().setBody(post("selected post")))
            val routes=routes(first.url("/").toString().trimEnd('/'),backup.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes)
            val target=routes.recognize("https://x.com/NASA/status/123")!!
            assertEquals("selected post",pool.load(target,originalFallback=false).posts.single().body)
            assertEquals(HealthStatus.EMPTY,pool.results(Platform.X).first {it.source==routes.sources.first()}.status)
        }}
    }
    @Test fun unrelatedOnlySourcesCannotEscapeAsPartialPostContent(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(post("unrelated card").replace("status/123","status/456")))
            val routes=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes)
            assertThrows(SourceFailure::class.java){runBlocking {pool.load(routes.recognize("https://x.com/NASA/status/123")!!,originalFallback=false)}}
        }
    }
    @Test fun emptySearchDoesNotDisableAnotherQuery(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("<title>No matching public results</title>"));server.enqueue(MockResponse().setBody(post("another query works")))
            val routes=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes)
            assertThrows(SourceFailure::class.java){runBlocking {pool.load(routes.search(Platform.X,"empty query"),originalFallback=false)}}
            assertEquals("another query works",pool.load(routes.search(Platform.X,"another query"),originalFallback=false).posts.single().body)
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun exhaustedContinuationDoesNotCoolTheWorkingFirstPage(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val routes=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes);val source=routes.sources.single()
            val healthy=ProbeResult(source,Capability.TIMELINE,HealthStatus.HEALTHY,100,System.currentTimeMillis(),false);pool.restore(listOf(healthy));pool.learning.observe(healthy)
            server.enqueue(MockResponse().setBody("<title>No more posts</title>"));server.enqueue(MockResponse().setBody(post("first page still works")))
            val target=routes.target(Platform.X,"NASA");val previous=Page(emptyList(),next=server.url("/?cursor=end").toString(),provenance=Provenance(source,System.currentTimeMillis(),source.root))
            assertThrows(SourceFailure::class.java){runBlocking {pool.continuePage(target,previous,false)}}
            assertEquals(healthy,pool.results(Platform.X).single());assertTrue(pool.learning.eligible(source,Capability.TIMELINE,false,target))
            assertEquals("first page still works",pool.load(target,originalFallback=false).posts.single().body)
        }
    }
    @Test fun originalApiAndRssFormatsAreRankedBySuccessfulDelivery(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val root=server.url("/").toString().trimEnd('/')
            val routes=routes();val target=Target(Platform.MASTODON,TargetKind.PROFILE,"reader","$root/@reader");val pool=SourcePool(gateway(),routes)
            val sources=pool.sourcesFor(target,Capability.TIMELINE,true)
            sources.forEach {source->pool.learning.observe(ProbeResult(source,Capability.TIMELINE,HealthStatus.HEALTHY,if(source.frontend=="mastodon-rss")50 else 1600,System.currentTimeMillis(),false))}
            val link="https://localhost:${server.port}/@reader/123"
            server.enqueue(MockResponse().setHeader("Content-Type","application/rss+xml").setBody("""<rss><channel><title>Reader</title><link>$root/@reader</link><item><link>$link</link><description>Readable public post</description><pubDate>Fri, 16 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""))
            assertEquals("Readable public post",pool.load(target).posts.single().body)
            assertEquals("/@reader.rss",server.takeRequest().path)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun emptyOriginalFormatDoesNotPreventTheNextFormat(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val root=server.url("/").toString().trimEnd('/');val routes=routes();val target=Target(Platform.MASTODON,TargetKind.PROFILE,"reader","$root/@reader");val pool=SourcePool(gateway(),routes)
            // Valid account metadata plus an empty status array is partial, never a timeline.
            server.enqueue(MockResponse().setBody("""{"id":"7","username":"reader","acct":"reader","display_name":"Reader"}"""));server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""<rss><channel><title>Reader</title><link>$root/@reader</link><item><link>https://localhost:${server.port}/@reader/123</link><description>RSS survived</description><pubDate>Fri, 16 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""))
            assertEquals("RSS survived",pool.load(target).posts.single().body)
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun selectedFederatedOriginRetainsPortAndDoesNotBorrowAnotherServer() {
        val routes=routes();val pool=SourcePool(gateway(),routes)
        val target=routes.recognize("https://social.example:8443/@reader",Platform.MASTODON)!!
        val source=pool.sourcesFor(target,Capability.TIMELINE,true).first {it.frontend=="original"}
        assertEquals("https://social.example:8443",source.root)
        assertEquals(SourceRole.ORIGINAL,source.role)
        assertTrue(pool.sourcesFor(target,Capability.TIMELINE,false).isEmpty())
        val other=routes.recognize("https://other.example/@reader",Platform.MASTODON)!!
        assertEquals("https://other.example",pool.sourcesFor(other,Capability.TIMELINE,true).first {it.frontend=="original"}.root)
    }
}
