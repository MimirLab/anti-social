/* anti social, GPL-3.0-or-later. Actual bounded loopback routing, synthetic records. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SourcePoolVideoPostRefreshTest {
    private val oldUrl="https://v45.tiktokcdn-eu.com/"+"a".repeat(32)+"/6ae10001/video/tos/alisg/tos-alisg-ve-001c001-sg/oWorkshopFilm0123456789abcdef/?mime_type=video_mp4"
    private val newUrl=oldUrl.replace("v45.","v16m.").replace("-eu.com","-us.com").replace("oWorkshopFilm","oCurrentWorkshopFilm")
    private val old=Post("710003",Platform.TIKTOK,"https://www.tiktok.com/@publicstudio/video/710003","Public studio","A public workshop film",publishedAt=1_700_000_000_000,
        media=listOf(Media(oldUrl,"video")),provenance=Provenance(Source(Platform.TIKTOK,"rsshub","https://feed.example"),1,"https://feed.example/recent"))
    private fun response(url:String=newUrl,id:String="710003",caption:String=old.body)="""{"code":0,"data":{"id":"$id","title":"$caption","create_time":1700000000,"duration":120,"author":{"unique_id":"publicstudio","nickname":"Public studio"},"wmplay":"$url"}}"""
    private class Fixture(frontends:List<String>):AutoCloseable {
        val servers=frontends.map {MockWebServer().apply {start()}}
        private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        private val client=gateway.client()
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {
            add("sources",JsonParser.parseString(servers.mapIndexed {i,s->"""{"platform":"tiktok","frontend":"${frontends[i]}","root":"${s.url("/").toString().trimEnd('/')}","fallbackOnly":false}"""}.joinToString(",","[","]")))
            add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes)
        override fun close(){client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();servers.forEach {it.shutdown()}}
    }
    @Test fun anUnchangedDeliveryCannotWinAheadOfTheNextQualifiedCurrentRecord()=runBlocking {
        Fixture(listOf("proxiTok","tikwm","tikwm","tikwm")).use {f->
            f.servers[1].enqueue(MockResponse().setBody(response(oldUrl)));f.servers[2].enqueue(MockResponse().setBody(response()))
            val target=f.routes.recognize(old.canonicalUrl)!!
            val page=f.pool.load(target,originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true,mediaRefreshPost=old)
            assertEquals(newUrl,page.posts.single().media.single().url)
            assertEquals(listOf(0,1,1,0),f.servers.map {it.requestCount})
            assertTrue(f.servers[1].takeRequest().path!!.startsWith("/api/?url="));assertTrue(f.servers[2].takeRequest().path!!.startsWith("/api/?url="))
            val results=f.pool.results(Platform.TIKTOK);assertEquals(2,results.size)
            assertEquals(setOf(HealthStatus.EMPTY,HealthStatus.HEALTHY),results.map {it.status}.toSet());assertTrue(results.all {it.capability==Capability.MEDIA})
        }
    }
    @Test fun theTwoMetadataRouteBoundCannotSpillIntoAnotherMethodOrThirdMirror()=runBlocking {
        Fixture(listOf("proxiTok","tikwm","tikwm","tikwm")).use {f->
            f.servers[1].enqueue(MockResponse().setBody(response(oldUrl)));f.servers[2].enqueue(MockResponse().setBody(response(caption="Another announcement")))
            assertTrue(runCatching {f.pool.load(f.routes.recognize(old.canonicalUrl)!!,originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true,mediaRefreshPost=old)}.isFailure)
            assertEquals(listOf(0,1,1,0),f.servers.map {it.requestCount})
        }
    }
    @Test fun missingRefreshMetadataDoesNotDeclareTheOrdinaryPostReaderBroken()=runBlocking {
        Fixture(listOf("tikwm")).use {f->
            repeat(2){f.servers.single().enqueue(MockResponse().setBody(response(oldUrl)))}
            val target=f.routes.recognize(old.canonicalUrl)!!
            assertTrue(runCatching {f.pool.load(target,originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true,mediaRefreshPost=old)}.isFailure)
            assertEquals(old.body,f.pool.load(target,originalFallback=false).posts.single().body)
            assertEquals(2,f.servers.single().requestCount)
            val results=f.pool.results(Platform.TIKTOK);assertEquals(HealthStatus.EMPTY,results.single {it.capability==Capability.MEDIA}.status)
            assertEquals(HealthStatus.HEALTHY,results.single {it.capability==Capability.POST}.status)
        }
    }
    @Test fun aDifferentPostFromOneRouteCannotBecomeTheRequestedRecord()=runBlocking {
        Fixture(listOf("tikwm","tikwm")).use {f->
            f.servers[0].enqueue(MockResponse().setBody(response(id="710004")));f.servers[1].enqueue(MockResponse().setBody(response()))
            val page=f.pool.load(f.routes.recognize(old.canonicalUrl)!!,originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true,mediaRefreshPost=old)
            assertEquals(old.identityKey,page.posts.single().identityKey);assertEquals(listOf(1,1),f.servers.map {it.requestCount})
        }
    }
    @Test fun refreshCannotEnableOriginalFallbackChangeIdentityOrRunBeforeSelectedTor()=runBlocking {
        Fixture(listOf("tikwm")).use {f->
            val target=f.routes.recognize(old.canonicalUrl)!!
            assertTrue(runCatching {f.pool.load(target,originalFallback=true,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true,mediaRefreshPost=old)}.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching {f.pool.load(target,originalFallback=false,requestedCapability=Capability.MEDIA,mediaRefreshPost=old)}.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching {f.pool.load(target.copy(canonicalUrl=target.canonicalUrl.replace("710003","710004")),originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true,mediaRefreshPost=old)}.exceptionOrNull() is IllegalArgumentException)
            f.gateway.configure(NetworkPolicy(tor=true,ready=false))
            assertTrue(runCatching {f.pool.load(target,originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true,mediaRefreshPost=old)}.isFailure)
            assertEquals(0,f.servers.single().requestCount)
        }
    }
    @Test fun anExplicitServerPauseStillAppliesToTheOrdinaryPostRoute()=runBlocking {
        Fixture(listOf("tikwm")).use {f->
            f.servers.single().enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","120"))
            val target=f.routes.recognize(old.canonicalUrl)!!
            assertTrue(runCatching {f.pool.load(target,originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true,mediaRefreshPost=old)}.isFailure)
            assertTrue(runCatching {f.pool.load(target,originalFallback=false)}.isFailure)
            assertEquals(1,f.servers.single().requestCount);assertEquals(HealthStatus.RATE_LIMITED,f.pool.results(Platform.TIKTOK).single().status)
        }
    }
}
