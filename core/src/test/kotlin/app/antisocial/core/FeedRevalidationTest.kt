/* anti social, GPL-3.0-or-later. Owned feed responses through the real Gateway. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class FeedRevalidationTest {
    private class Fixture:AutoCloseable {
        val server=MockWebServer().apply {start()}
        private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        private val client=gateway.client()
        val root=server.url("/").toString().trimEnd('/')
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {
            add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"$root","fallbackOnly":false}]"""));add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes)
        val target=routes.target(Platform.X,"NASA")
        val follow=Follow("feed-fixture",target,"NASA",feedUrl="$root/feed")
        val body="""<rss><channel><title>Public NASA</title><link>https://x.com/NASA</link><item><title>Flight notice</title><link>https://x.com/NASA/status/123</link><description>Bring the required equipment.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""
        override fun close(){client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();server.shutdown()}
    }
    @Test fun anExternalFeedRevalidatesKnownContentWithoutLosingItsFreshness():Unit=runBlocking {
        Fixture().use {f->
            f.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=
                if(request.getHeader("If-None-Match")=="\"feed-one\"")MockResponse().setResponseCode(304).setHeader("ETag","\"feed-one\"")
                else MockResponse().setHeader("ETag","\"feed-one\"").setBody(f.body)}
            val first=f.pool.externalFeed(f.follow)
            val second=runCatching {withTimeout(1_500){f.pool.externalFeed(f.follow)}}
            assertTrue("An exact validated unchanged feed stays readable: ${second.exceptionOrNull()}",second.isSuccess)
            assertEquals(first.posts.map {it.body},second.getOrThrow().posts.map {it.body})
            assertNull(FeedRefresh.pendingReason(second.getOrThrow(),Platform.X))
            assertFalse(requireNotNull(second.getOrThrow().provenance).cached)
            assertNull(f.server.takeRequest().getHeader("If-None-Match"))
            assertEquals("\"feed-one\"",f.server.takeRequest().getHeader("If-None-Match"))
            assertEquals(2,f.server.requestCount)
        }
    }
    @Test fun aValidatedFrontendFeedDoesNotFallThroughToItsHtmlPageOn304():Unit=runBlocking {
        Fixture().use {f->
            f.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=
                if(request.path=="/NASA/rss")if(request.getHeader("If-None-Match")=="\"rss-one\"")MockResponse().setResponseCode(304).setHeader("ETag","\"rss-one\"")else MockResponse().setHeader("ETag","\"rss-one\"").setBody(f.body)
                else MockResponse().setResponseCode(503)}
            val first=f.pool.load(f.target,feed=true,originalFallback=false)
            val next=runCatching {withTimeout(1_500){f.pool.load(f.target,feed=true,originalFallback=false)}}
            assertTrue("Unchanged XML must not become a failed frontend: ${next.exceptionOrNull()}",next.isSuccess)
            assertEquals(first.posts.map {it.id},next.getOrThrow().posts.map {it.id})
            f.server.takeRequest();assertEquals("\"rss-one\"",f.server.takeRequest().getHeader("If-None-Match"))
            assertEquals(2,f.server.requestCount);assertTrue(f.pool.results(Platform.X).all {it.status==HealthStatus.HEALTHY})
        }
    }
    @Test fun anUnsolicitedPartialFeedCannotCertifyACompleteRefresh():Unit=runBlocking {
        Fixture().use {f->
            f.server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-99/1000").setBody(f.body))
            val result=runCatching {f.pool.externalFeed(f.follow)}
            assertTrue("A partial response must remain pending",result.isFailure||result.getOrNull()?.let {FeedRefresh.pendingReason(it,Platform.X)!=null}==true)
        }
    }
}
