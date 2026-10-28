package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TikWmSearchTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val permit=object:RequestFilter{override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)}
    private val body="""{"code":0,"msg":"success","data":{"videos":[{"video_id":"7699532645008575767","title":"A public space photograph","images":["https://images.example/nebula.jpg"],"create_time":1792700000,"author":{"unique_id":"space_photos","nickname":"Space Photos"},"comment_count":12},{"video_id":"7693212611755871490","title":"A public video","play":"https://video.example/clip.mp4","duration":12.5,"create_time":1792699000,"author":{"unique_id":"space_video","nickname":"Space Video"}}],"cursor":12,"hasMore":true}}"""
    @Test fun publicPostsAndPhotosRetainTheirSourcesAndBindEveryContinuation():Unit=runBlocking {
        MockWebServer().use {server->server.start();repeat(2){server.enqueue(MockResponse().setBody(body))}
            val gateway=NetworkGateway(cleaner,permit,true);val source=Source(Platform.TIKTOK,"tikwm-photo-search",server.url("/").toString().trimEnd('/'))
            val adapter=TikWmSearchAdapter(source,gateway,routes);val target=routes.search(Platform.TIKTOK,"space photos")
            try {
                val page=adapter.fetch(target,null);assertEquals(2,page.posts.size)
                assertTrue(page.posts[0].canonicalUrl.contains("/photo/"));assertTrue(page.posts[1].canonicalUrl.contains("/video/"))
                assertEquals("Space Photos",page.posts[0].author);assertEquals(source,page.posts[0].provenance!!.source)
                assertEquals(12L,page.posts[0].replyCount);assertEquals("image",page.posts[0].media.single().type)
                assertNull(page.posts[0].media.single().durationMs);assertEquals(12500L,page.posts[1].media.single().durationMs)
                assertEquals(target.identity,server.takeRequest().requestUrl!!.queryParameter("keywords"))
                val cursor=requireNotNull(page.next)
                assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(routes.search(Platform.TIKTOK,"different"),cursor)}}
                assertThrows(IllegalArgumentException::class.java){runBlocking {TikWmSearchAdapter(source.copy(frontend="tikwm-search"),gateway,routes).fetch(target,cursor)}}
                assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(target,"https://attacker.example/cursor")}}
                assertEquals(1,server.requestCount)
                val second=adapter.fetch(target,cursor);assertNull(second.next);assertNotNull(second.notice)
                assertEquals("12",server.takeRequest().requestUrl!!.queryParameter("cursor"))
                assertFalse(adapter.accepts(routes.search(Platform.TIKTOK,"photos",SearchScope(SearchScopeKind.ACCOUNT,"space_photos"))));assertEquals(2L,gateway.protectionStats().checked)
            }finally {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
        }
    }
    @Test fun challengesQuotaErrorsAndMalformedRecordsAreNotWorkingSearches():Unit=runBlocking {
        MockWebServer().use {server->server.start()
            for(body in listOf("<title>Just a moment</title>","""{"code":-1,"msg":"Too many requests"}""","""{"code":0,"data":{"videos":[{"video_id":"123","title":"Unattributed","author":{"unique_id":"../../bad"}}]}}"""))server.enqueue(MockResponse().setBody(body))
            val gateway=NetworkGateway(cleaner,permit,true);val adapter=TikWmSearchAdapter(Source(Platform.TIKTOK,"tikwm-search",server.url("/").toString().trimEnd('/')),gateway,routes)
            try {
                val statuses=listOf(HealthStatus.CHALLENGE,HealthStatus.RATE_LIMITED,HealthStatus.EMPTY)
                for(status in statuses)assertEquals(status,assertThrows(SourceFailure::class.java){runBlocking {adapter.fetch(routes.search(Platform.TIKTOK,"nasa"),null)}}.status)
            }finally {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
        }
    }
}
