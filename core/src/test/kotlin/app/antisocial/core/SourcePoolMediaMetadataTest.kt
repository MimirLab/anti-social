/* anti social, GPL-3.0-or-later. Narrow metadata reads retain the shared policy. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SourcePoolMediaMetadataTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private fun routes()=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {
        add("sources",JsonArray());add("searchSources",JsonArray())
    },cleaner)
    private fun pathways(count:Int)=PublicPathways(JsonParser.parseString("""{"sources":[
        {"enabled":true,"platform":"twitter","frontend":"nitter","root":"https://html.example"},
        {"enabled":true,"platform":"twitter","frontend":"fxembed-api","root":"https://archive.example","role":"CACHE"},
        ${(0 until count).joinToString(","){"""{"enabled":true,"platform":"twitter","frontend":"fxembed-api","root":"https://api$it.example"}"""}}
    ]}""").asJsonObject)
    private fun response(media:Boolean)= """{"code":200,"status":{"type":"status","provider":"twitter","id":"710001","url":"https://x.com/publicstudio/status/710001","text":"Public workshop film","created_timestamp":1800000000,"author":{"screen_name":"publicstudio","name":"Public studio"}${if(media)""","media":{"videos":[{"duration":120,"formats":[{"container":"mp4","codec":"h264","bitrate":2000,"url":"https://video.twimg.com/amplify_video/710001/vid/1920x1080/main.mp4"},{"container":"mp4","codec":"h264","bitrate":100,"url":"https://video.twimg.com/amplify_video/710001/vid/320x180/small.mp4"}]}]}"""else ""}}}"""
    private fun gateway(server:MockWebServer,requests:MutableList<String>)=NetworkGateway(cleaner,object:RequestFilter {
        override fun check(url:String,sourceUrl:String,type:String):FilterDecision {
            requests+=url
            val parsed=url.toHttpUrlOrNull()!!
            return FilterDecision(false,rewrittenUrl=server.url(parsed.encodedPath+(parsed.encodedQuery?.let {"?$it"}.orEmpty())).toString())
        }
    },true)

    @Test fun metadataUsesTheExistingJsonMethodAndLearnsOnlyItsMediaDelivery()=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(response(true)))
            val requests=CopyOnWriteArrayList<String>();val gateway=gateway(server,requests);val client=gateway.client();val routes=routes()
            try {
                val pool=SourcePool(gateway,routes,pathways(1));val target=routes.target(Platform.X,"https://x.com/publicstudio/status/710001")
                val page=pool.load(target,originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true)
                assertEquals(1,page.posts.size);assertNotNull(page.posts.single().media.single().samplingUrl)
                assertEquals("/2/status/710001",server.takeRequest().path);assertEquals(1,server.requestCount)
                assertTrue(requests.all {it.startsWith("https://api0.example/")||it.startsWith(server.url("/").toString())})
                assertEquals(HealthStatus.HEALTHY,pool.results(Platform.X).single().status)
                assertEquals(Capability.MEDIA,pool.results(Platform.X).single().capability)
            }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    @Test fun aReadableCaptionWithoutVideoMetadataCannotWinOrTriggerOtherMethods()=runBlocking {
        MockWebServer().use {server->
            server.start();repeat(3){server.enqueue(MockResponse().setBody(response(false)))}
            val requests=CopyOnWriteArrayList<String>();val gateway=gateway(server,requests);val client=gateway.client();val routes=routes()
            try {
                val pool=SourcePool(gateway,routes,pathways(3));val target=routes.target(Platform.X,"https://x.com/publicstudio/status/710001")
                assertTrue(runCatching {pool.load(target,originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true)}.isFailure)
                assertEquals(2,server.requestCount)
                assertEquals(2,pool.results(Platform.X).size);assertTrue(pool.results(Platform.X).all {it.status==HealthStatus.EMPTY&&it.capability==Capability.MEDIA})
                assertTrue(requests.none {it.contains("html.example")||it.contains("archive.example")||it.contains("x.com")})
            }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    @Test fun publisherThreadRequiresAConnectedChildWithoutPoisoningOrdinaryPostHealth():Unit=runBlocking {
        MockWebServer().use {server->
            server.start();repeat(2){server.enqueue(MockResponse().setBody(response(false)))}
            val requests=CopyOnWriteArrayList<String>();val gateway=gateway(server,requests);val client=gateway.client();val routes=routes()
            try {
                val pool=SourcePool(gateway,routes,pathways(3));val target=routes.target(Platform.X,"https://x.com/publicstudio/status/710001").copy(kind=TargetKind.THREAD)
                val error=runCatching {pool.load(target,originalFallback=false,publisherThreadOnly=true)}.exceptionOrNull() as SourceFailure
                assertEquals(HealthStatus.EMPTY,error.status);assertEquals(2,server.requestCount)
                assertTrue(pool.results(Platform.X).isEmpty())
                assertTrue(requests.none {it.contains("html.example")||it.contains("archive.example")||it.contains("x.com")})
                assertTrue(runCatching {pool.load(target,originalFallback=true,publisherThreadOnly=true)}.exceptionOrNull()is IllegalArgumentException)
                assertTrue(runCatching {pool.load(target.copy(kind=TargetKind.POST),originalFallback=false,publisherThreadOnly=true)}.exceptionOrNull()is IllegalArgumentException)
                val closed=gateway(server,CopyOnWriteArrayList());val closedClient=closed.client();closed.configure(NetworkPolicy(tor=true,ready=false))
                try {assertTrue(runCatching {SourcePool(closed,routes,pathways(1)).load(target,originalFallback=false,publisherThreadOnly=true)}.isFailure)}
                finally {closedClient.dispatcher.executorService.shutdownNow();closedClient.connectionPool.evictAll()}
                assertEquals(2,server.requestCount)
            }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    @Test fun metadataCannotEnableOriginalFallbackOrDefeatUnavailableSelectedTor()=runBlocking {
        MockWebServer().use {server->
            server.start();val requests=CopyOnWriteArrayList<String>();val gateway=gateway(server,requests);val client=gateway.client();val routes=routes()
            try {
                val pool=SourcePool(gateway,routes,pathways(1));val target=routes.target(Platform.X,"https://x.com/publicstudio/status/710001")
                assertTrue(runCatching {pool.load(target,originalFallback=true,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true)}.exceptionOrNull() is IllegalArgumentException)
                gateway.configure(NetworkPolicy(tor=true,ready=false))
                assertTrue(runCatching {pool.load(target,originalFallback=false,requestedCapability=Capability.MEDIA,mediaMetadataOnly=true)}.isFailure)
                assertEquals(0,server.requestCount);assertTrue(requests.isEmpty())
            }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    @Test fun unsupportedContinuationLeavesOrdinaryPostUsableButExplicitServerPauseStillApplies():Unit=runBlocking {
        val onlyFx=PublicPathways(JsonParser.parseString("""{"sources":[{"enabled":true,"platform":"twitter","frontend":"fxembed-api","root":"https://api0.example"}]}""").asJsonObject)
        MockWebServer().use {server->
            server.start();server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest)=if(request.path?.startsWith("/2/thread/")==true)MockResponse().setResponseCode(404)
                    else MockResponse().setBody(response(false))
            }
            val gateway=gateway(server,CopyOnWriteArrayList());val client=gateway.client();val routes=routes()
            try {
                val pool=SourcePool(gateway,routes,onlyFx);val target=routes.target(Platform.X,"https://x.com/publicstudio/status/710001")
                assertTrue(runCatching {pool.load(target.copy(kind=TargetKind.THREAD),originalFallback=false,publisherThreadOnly=true)}.isFailure)
                assertTrue(pool.results(Platform.X).isEmpty())
                assertEquals(1,pool.load(target,originalFallback=false).posts.size)
                assertEquals(2,server.requestCount);assertEquals(HealthStatus.HEALTHY,pool.results(Platform.X).single().status)
                server.dispatcher=object:Dispatcher() {
                    override fun dispatch(request:RecordedRequest)=MockResponse().setResponseCode(429).setHeader("Retry-After","120")
                }
                val paused=SourcePool(gateway,routes,onlyFx)
                assertTrue(runCatching {paused.load(target.copy(kind=TargetKind.THREAD),originalFallback=false,publisherThreadOnly=true)}.isFailure)
                assertEquals(HealthStatus.RATE_LIMITED,paused.results(Platform.X).single().status)
                assertTrue(runCatching {paused.load(target,originalFallback=false)}.isFailure)
                assertEquals("A server pause must also protect the ordinary route",3,server.requestCount)
            }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
}
