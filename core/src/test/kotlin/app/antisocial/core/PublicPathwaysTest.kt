package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class PublicPathwaysTest {
    private fun json(text: String)=JsonParser.parseString(text).asJsonObject
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(json("""{"providers":{}}"""))
    private fun routes()=DarwinRoutes(asset("darwin-routes.json").apply {add("sources",com.google.gson.JsonArray());add("searchSources",com.google.gson.JsonArray())},cleaner)
    private fun catalog(vararg rows: String)=PublicPathways(json(rows.joinToString(",","{\"sources\":[","]}")))
    private val cache="""{"platform":"reddit","frontend":"arctic-shift","root":"https://arctic-shift.photon-reddit.com","role":"CACHE","enabled":true}"""
    private val node="""{"id":"abc12","permalink":"/r/Android/comments/abc12/topic/","subreddit":"Android","author":"alice","created_utc":1792152000,"title":"Battery repair","selftext":"Replace the battery"}"""
    private fun gateway(server: MockWebServer)=NetworkGateway(cleaner,object: RequestFilter {
        override fun check(url: String,sourceUrl: String,type: String): FilterDecision {
            val u=url.toHttpUrlOrNull()!!
            if(u.host==server.hostName&&u.port==server.port)return FilterDecision(false)
            return FilterDecision(false,rewrittenUrl=server.url("/"+u.host+u.encodedPath+(u.encodedQuery?.let {"?$it"}.orEmpty())).toString())
        }
    },true)

    @Test fun tiktokGalleryLinksReachPostReadersAndKeepOnePostIdentity() {
        val routes=routes()
        val photo=routes.recognize("https://www.tiktok.com/@alice/photo/7694797676306615583")!!
        assertEquals(TargetKind.POST,photo.kind)
        assertEquals("post:7694797676306615583",ContentIdentity.key(photo.platform,photo.canonicalUrl))
        assertEquals(ContentIdentity.key(photo.platform,photo.canonicalUrl),ContentIdentity.key(photo.platform,photo.canonicalUrl.replace("/photo/","/video/")))
        assertEquals("comment:7694797676306615583:12345",ContentIdentity.key(photo.platform,photo.canonicalUrl+"?comment_id=12345"))
    }

    @Test fun catalogRejectsUnsafeDisabledAndCrossPlatformOriginTemplates() {
        val items=catalog(
            """{"platform":"twitter","frontend":"fxembed-api","root":"http://api.example","enabled":true}""",
            """{"platform":"twitter","frontend":"fxembed-api","root":"https://user:pass@api.example","enabled":true}""",
            """{"platform":"twitter","frontend":"fxembed-api","root":"https://api.example?key=secret","enabled":true}""",
            """{"platform":"twitter","frontend":"fxembed-api","root":"https://api.example","scope":"selected-origin","role":"ORIGINAL","enabled":true}""",
            """{"platform":"telegram","frontend":"rsshub","root":"https://rss.example","enabled":false}""",
            """{"platform":"pixelfed","frontend":"pixelfed-activitypub","root":"https://pixelfed.social","scope":"selected-origin","role":"ORIGINAL","enabled":true}"""
        )
        assertEquals(1,items.entries.size)
        val target=Target(Platform.PIXELFED,TargetKind.PROFILE,"alice","https://photos.example:8443/alice")
        assertEquals("https://photos.example:8443",items.sources(target).single().root)
        assertTrue(items.sources(Platform.PIXELFED).isEmpty())
    }

    @Test fun bundledPoolHasConcreteReadersAndTheResearchCoversEveryPlatform() {
        val data=asset("public-pathways.json");val pathways=PublicPathways(data)
        assertEquals(data.getAsJsonArray("sources").size(),pathways.entries.size)
        assertTrue(pathways.entries.size>=140)
        val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=error("Catalog inspection must not open a socket")})
        val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
        val pool=SourcePool(gateway,routes,pathways)
        for(entry in pathways.entries) {
            val source=entry.source
            val reader=protocolDiscoveryAdapter(source,gateway,routes) ?: largePlatformDiscoveryAdapter(source,gateway,routes) ?: otherDiscoveryAdapter(source,gateway,routes) ?: publicFeedPathwayAdapter(source,gateway,routes)
            assertTrue("Missing specific reader: ${source.key}",reader!=null||source.frontend in setOf("nitter","kittygram","painterest","redlib"))
            if(source.role==SourceRole.CACHE)assertFalse(Capability.FEED in pool.sourceReader(source).capabilities)
        }
        val covered=asset("source-discovery.json").getAsJsonArray("platforms").map {it.asJsonObject["id"].asString}.toSet()
        assertEquals(Platform.entries.filterNot {it==Platform.RSS}.map {it.id}.toSet(),covered)
    }

    @Test fun sharedFeedRenderersCannotHijackIncomingSocialLinks() {
        val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
        routes.registerPathwayAliases(listOf(Source(Platform.X,"rsshub","https://feeds.example"),Source(Platform.REDDIT,"rsshub","https://feeds.example"),Source(Platform.X,"nitter","https://new-nitter.example")))
        assertNull(routes.recognize("https://feeds.example/twitter/user/NASA"))
        assertEquals("https://x.com/NASA/status/123",routes.recognize("https://new-nitter.example/NASA/status/123")!!.canonicalUrl)
    }

    @Test fun newlyVerifiedFormatGetsATryBeforeColdLegacyHostsWithoutPreseedingHealth(): Unit=runBlocking {
        MockWebServer().use {server->server.start()
            val data=asset("darwin-routes.json").apply {add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"https://legacy-nitter.example","fallbackOnly":false}]"""))}
            val routes=DarwinRoutes(data,cleaner)
            val pathways=catalog("""{"platform":"twitter","frontend":"fxembed-api","root":"https://api.fxtwitter.com","priority":-8,"fallbackOnly":false,"enabled":true}""")
            val pool=SourcePool(gateway(server),routes,pathways)
            assertTrue(pool.results(Platform.X).isEmpty())
            server.enqueue(MockResponse().setBody("""{"code":200,"results":[{"type":"status","provider":"twitter","id":"123","url":"https://x.com/NASA/status/123","text":"A public launch","created_timestamp":1792152000,"author":{"screen_name":"NASA","name":"NASA"}}]}"""))
            val page=pool.load(routes.target(Platform.X,"NASA"),originalFallback=false)
            assertEquals("A public launch",page.posts.single().body)
            assertEquals("fxembed-api",page.provenance!!.source.frontend)
            assertEquals(1,server.requestCount);assertTrue(server.takeRequest().path!!.startsWith("/api.fxtwitter.com/2/profile/NASA/statuses"))
        }
    }

    @Test fun liveOriginalWinsBeforeHistoricalRecovery(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""[{"data":{"children":[{"kind":"t3","data":$node}]}}]"""))
            val routes=routes();val pool=SourcePool(gateway(server),routes,catalog(cache));val target=routes.recognize("https://www.reddit.com/r/Android/comments/abc12/topic/")!!
            val page=pool.load(target)
            assertEquals(SourceRole.ORIGINAL,page.provenance!!.source.role);assertEquals(1,server.requestCount)
            assertTrue(server.takeRequest().path!!.startsWith("/www.reddit.com/"))
            assertTrue(pool.results(Platform.REDDIT).none {it.source.role==SourceRole.CACHE})
        }
    }

    @Test fun historicalRecoveryWorksAfterLiveFailureWithoutTrainingHeartbeatOrFreshness(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(403));server.enqueue(MockResponse().setBody("""{"data":[$node]}"""))
            val routes=routes();val pool=SourcePool(gateway(server),routes,catalog(cache));val target=routes.recognize("https://www.reddit.com/r/Android/comments/abc12/topic/")!!
            var observed=0;pool.onRead={_,_,_->observed++}
            val page=pool.load(target)
            assertEquals(SourceRole.CACHE,page.provenance!!.source.role);assertTrue(page.provenance!!.cached)
            assertEquals("Battery repair",page.posts.single().title);assertNull(page.posts.single().recoveredArchive)
            assertEquals(0,observed);assertTrue(pool.heartbeat.snapshot().scopes.isEmpty())
            assertEquals(2,server.requestCount);assertTrue(server.takeRequest().path!!.startsWith("/www.reddit.com/"));assertTrue(server.takeRequest().path!!.startsWith("/arctic-shift.photon-reddit.com/"))
            assertEquals(0,pool.learning.snapshot().single {it.source.role==SourceRole.CACHE}.deliverySamples)
            assertNull(pool.maintenanceDueAt(routes.target(Platform.REDDIT,"r/Android"),Capability.TIMELINE,true))
        }
    }

    @Test fun recoveryDoesNotRequireOriginalFallbackButNeverEntersTheFeed(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"data":[$node]}"""))
            val routes=routes();val pool=SourcePool(gateway(server),routes,catalog(cache));val target=routes.recognize("https://www.reddit.com/r/Android/comments/abc12/topic/")!!
            assertEquals(SourceRole.CACHE,pool.load(target,originalFallback=false).provenance!!.source.role)
            assertEquals(1,server.requestCount)
            assertThrows(SourceFailure::class.java){runBlocking {pool.load(routes.target(Platform.REDDIT,"r/Android"),feed=true,originalFallback=false)}}
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun recoveryAndEverySupplementalReadFailClosedOnTor(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val gateway=gateway(server);gateway.configure(NetworkPolicy(tor=true,ready=false))
            val routes=routes();val pool=SourcePool(gateway,routes,catalog(cache))
            assertThrows(java.io.IOException::class.java){runBlocking {pool.load(routes.recognize("https://www.reddit.com/r/Android/comments/abc12/topic/")!!,originalFallback=false)}}
            assertEquals(0,server.requestCount)
        }
    }
}
