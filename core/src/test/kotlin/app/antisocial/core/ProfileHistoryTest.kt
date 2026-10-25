package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Owned Gateway fixtures, not a claim that a public source is always available. */
class ProfileHistoryTest {
    private fun asset(name:String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private val firstCode="DeTUk4sDSp-";private val secondCode="DeJ28eTxo4s"
    private fun rss(codes:List<String>)="""<rss><channel><title>NASA</title><link>https://www.instagram.com/nasa/</link>${codes.joinToString("") {"<item><link>https://www.instagram.com/p/$it/</link><description>Public mission record $it</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item>"}}</channel></rss>"""
    private fun grid(codes:List<String>,cursor:String?="next",more:Boolean=true)=JsonObject().apply {add("data",JsonObject().apply {
        add("xig_user_by_username",JsonObject().apply {addProperty("pk","123");add("polaris_ordered_timeline_connection",JsonObject().apply {
            add("edges",JsonArray().apply {codes.forEach {code->add(JsonObject().apply {add("node",JsonObject().apply {
                addProperty("__typename","XIGPolarisImageMedia");addProperty("__isXIGPolarisMedia","XIGPolarisImageMedia")
                addProperty("code",code);addProperty("display_uri","https://images.example/$code.jpg")
                add("user",JsonObject().apply {addProperty("username","nasa")});add("caption",JsonObject().apply {addProperty("text","Public mission record $code")})
            })})}})
            add("page_info",JsonObject().apply {addProperty("has_next_page",more);cursor?.let {addProperty("end_cursor",it)}})
        })})
    })}.toString()
    private inner class Fixture:AutoCloseable {
        val server=MockWebServer().apply {start()}
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        val client=gateway.client()
        val clock=AtomicLong(System.currentTimeMillis())
        val rss=Source(Platform.INSTAGRAM,"rsshub",server.url("/feed").toString().trimEnd('/'))
        // An authored loopback catalog uses test-only HTTP. Production's
        // HTTPS/original-role catalog and permission checks are tested separately.
        val history=Source(Platform.INSTAGRAM,"instagram-public-graphql",server.url("/").toString().trimEnd('/'),true)
        val routes=DarwinRoutes(asset("darwin-routes.json").apply {
            add("sources",JsonArray().apply {listOf(rss,history).forEach {s->add(JsonObject().apply {addProperty("platform",s.platform.id);addProperty("frontend",s.frontend);addProperty("root",s.root);addProperty("fallbackOnly",s.fallbackOnly)})}})
            add("searchSources",JsonArray())
        },cleaner)
        val target=routes.recognize("https://www.instagram.com/nasa/")!!
        val pool=SourcePool(gateway,routes,clock=clock::get).apply {restore(listOf(ProbeResult(rss,Capability.TIMELINE,HealthStatus.HEALTHY,1,clock.get(),false)))}
        fun enqueue(body:String)=server.enqueue(MockResponse().setHeader("Content-Type",if(body.startsWith('<'))"application/rss+xml"else "application/json").setBody(body))
        fun request()=requireNotNull(server.takeRequest(1,TimeUnit.SECONDS))
        override fun close(){client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();server.shutdown()}
    }
    @Test fun aFastFiniteSnapshotOffersHistoryWithoutFetchingItUntilRequested():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(rss(listOf(firstCode,secondCode)))
            val first=f.pool.load(f.target,originalFallback=false)
            assertEquals(f.rss,first.provenance!!.source);assertEquals(2,first.posts.size);assertEquals(1,f.server.requestCount)
            assertNotNull("A successful finite preview must not conceal another verified history route",first.next)
        }
    }
    @Test fun overlappingHeadsAreSkippedAndTheNextPageStaysWithItsIssuer():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(rss(listOf(firstCode,secondCode)));val first=f.pool.load(f.target,originalFallback=false);f.request()
            f.enqueue(grid(listOf(firstCode,secondCode),"one"));f.enqueue(grid(listOf("DeAYvsnn4la"),"two"))
            val extra=f.pool.continuePage(f.target,first,originalFallback=false)
            assertEquals(f.history,extra.continuationSource)
            assertEquals(listOf("https://www.instagram.com/p/DeAYvsnn4la"),extra.posts.map {it.canonicalUrl})
            assertEquals(f.history,extra.provenance!!.source);assertEquals(2,listOf(f.request(),f.request()).count {it.path=="/api/graphql"})
            val merged=PageContinuation.merge(first,extra)
            assertEquals(first.provenance,merged.provenance);assertEquals(3,merged.posts.size);assertEquals(f.history,merged.continuationSource)
            assertFalse(merged.posts.first().preview);assertTrue(merged.posts.last().preview)
            f.enqueue(grid(listOf("De5P4VhGyGE"),more=false))
            val last=f.pool.continuePage(f.target,merged,originalFallback=false)
            assertEquals(1,last.posts.size);assertEquals("/api/graphql",f.request().path)
            assertNull(PageContinuation.merge(merged,last).next)
        }
    }
    @Test fun duplicateOnlyBatchesRetainNavigationWithoutInventingNewPostsOrHealthyContent():Unit=runBlocking {
        Fixture().use {f->
            val known=listOf(firstCode,secondCode,"DeAYvsnn4la","De5P4VhGyGE")
            f.enqueue(rss(known));val first=f.pool.load(f.target,originalFallback=false);f.request()
            known.take(3).forEachIndexed {i,code->f.enqueue(grid(listOf(code),"cursor-$i"))}
            val progress=f.pool.continuePage(f.target,first,originalFallback=false)
            assertTrue(progress.posts.isEmpty());assertTrue(progress.continuationProgress);assertNotNull(progress.next)
            assertTrue(f.pool.results(Platform.INSTAGRAM).none {it.source==f.history&&it.status==HealthStatus.HEALTHY})
            val merged=PageContinuation.merge(first,progress);assertEquals(known.size,merged.posts.size);assertNotNull(merged.next)
            f.enqueue(grid(listOf("De5D2LmkaYz"),more=false))
            val next=f.pool.continuePage(f.target,merged,originalFallback=false)
            assertEquals(1,next.posts.size);assertFalse(next.continuationProgress)
            assertEquals(5,PageContinuation.merge(merged,next).posts.size)
        }
    }
    @Test fun failedFollowupRetriesFromTheCompletedDuplicateCheckpoint():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(rss(listOf(firstCode)));val first=f.pool.load(f.target,originalFallback=false);f.request()
            f.enqueue(grid(listOf(firstCode),"after-known"));f.server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(SourceFailure::class.java){runBlocking {f.pool.continuePage(f.target,first,originalFallback=false)}}
            f.request();f.request()
            // A transient failure must not force another read of the head.
            f.clock.addAndGet(60_001) // Honor the learned source pause first.
            f.enqueue(grid(listOf(secondCode),more=false))
            val page=f.pool.continuePage(f.target,first,originalFallback=false)
            assertEquals(1,page.posts.size)
            val fields=("https://fixture.example/?"+f.request().body.readUtf8()).let {okhttp3.HttpUrl.Companion.run {it.toHttpUrl()}}
            assertEquals("after-known",JsonParser.parseString(fields.queryParameter("variables")).asJsonObject["after"].asString)
        }
    }
    @Test fun aRepeatedSourcePageDoesNotBecomeAnAdvancingCheckpoint():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(rss(listOf(firstCode)));val first=f.pool.load(f.target,originalFallback=false)
            f.enqueue(grid(listOf(firstCode),"one"));f.enqueue(grid(listOf(firstCode),"two"))
            assertEquals(HealthStatus.EMPTY,assertThrows(SourceFailure::class.java){runBlocking {f.pool.continuePage(f.target,first,originalFallback=false)}}.status)
        }
    }
    @Test fun aSourcePauseBeforeOrAfterOfferingDoesNotCauseHistoryTraffic():Unit=runBlocking {
        for(pauseBefore in listOf(false,true))Fixture().use {f->
            fun pause(){f.pool.learning.observe(ProbeResult(f.history,Capability.TIMELINE,HealthStatus.RATE_LIMITED,0,System.currentTimeMillis(),false,retryAfterMs=60_000),f.target)}
            if(pauseBefore)pause()
            f.enqueue(rss(listOf(firstCode)));val first=f.pool.load(f.target,originalFallback=false)
            if(pauseBefore)assertNull(first.next)else {
                assertNotNull(first.next);pause()
                assertThrows(SourceFailure::class.java){runBlocking {f.pool.continuePage(f.target,first,originalFallback=false)}}
            }
            assertEquals(1,f.server.requestCount)
        }
    }
    @Test fun modeRoundTripAndUnknownContinuationSourceAreRejectedBeforeTraffic():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(rss(listOf(firstCode)));val first=f.pool.load(f.target,originalFallback=false)
            assertThrows(IllegalArgumentException::class.java){runBlocking {f.pool.continuePage(f.target,first.copy(continuationSource=f.history.copy(root="https://unknown.example")),false)}}
            f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())
            assertThrows(SourceFailure::class.java){runBlocking {f.pool.continuePage(f.target,first,false)}}
            assertEquals(1,f.server.requestCount)
        }
    }
    @Test fun actualOriginalCatalogOffersNothingWhenOriginalAccessIsDisabled():Unit=runBlocking {
        Fixture().use {f->
            val catalog=asset("public-pathways.json").apply {add("sources",JsonArray().apply {asset("public-pathways.json").getAsJsonArray("sources").filter {it.asJsonObject["frontend"].asString=="instagram-public-graphql"}.forEach(::add)})}
            val routes=DarwinRoutes(asset("darwin-routes.json").apply {add("sources",JsonArray().apply {add(JsonObject().apply {addProperty("platform","instagram");addProperty("frontend","rsshub");addProperty("root",f.rss.root);addProperty("fallbackOnly",false)})});add("searchSources",JsonArray())},cleaner)
            val pool=SourcePool(f.gateway,routes,PublicPathways(catalog))
            f.enqueue(rss(listOf(firstCode)));assertNull(pool.load(f.target,originalFallback=false).next)
            f.enqueue(rss(listOf(firstCode)));val enabled=pool.load(f.target,originalFallback=true)
            assertNotNull(enabled.next);assertEquals(SourceRole.ORIGINAL,enabled.continuationSource!!.role)
            assertThrows(SourceFailure::class.java){runBlocking {pool.continuePage(f.target,enabled,originalFallback=false)}}
            assertEquals(2,f.server.requestCount)
        }
    }
    @Test fun ordinaryRepeatedPagesStillStopAndNavigationKeepsExistingFullCopies() {
        val source=Source(Platform.INSTAGRAM,"fixture","https://fixture.example")
        val post=Post("id",Platform.INSTAGRAM,"https://www.instagram.com/p/$firstCode/","NASA","Full text",publishedAt=1)
        val first=Page(listOf(post),next="one",provenance=Provenance(source,1,source.root))
        val duplicate=Page(listOf(post.copy(preview=true,publishedAt=null)),next="two",provenance=first.provenance)
        assertNull(PageContinuation.merge(first,duplicate).next)
        assertFalse(PageContinuation.merge(first,duplicate).posts.single().preview)
        assertNull(PageContinuation.merge(first,duplicate.copy(next="one",continuationProgress=true)).next)
        val origin=source.copy(root="https://original.example",role=SourceRole.ORIGINAL)
        val changed=duplicate.copy(posts=listOf(post.copy(id="another",canonicalUrl="https://www.instagram.com/p/$secondCode/")),provenance=Provenance(origin,2,origin.root),continuationSource=source)
        assertEquals(source,PageContinuation.merge(first,changed).continuationSource)
    }
    @Test fun retryCheckpointCannotMoveBackwardsOrExtendItsOriginalExpiry() {
        val now=AtomicLong(100);val bank=InstagramPublicCursors(now::get,ttl=10)
        val source=Source(Platform.INSTAGRAM,"fixture","https://fixture.example")
        val target=Target(Platform.INSTAGRAM,TargetKind.PROFILE,"nasa","https://www.instagram.com/nasa/")
        val session=NetworkSession(NetworkPolicy());val query=InstagramPublicGraphQl.query(InstagramPublicGraphQl.identity(target)!!)
        val key=bank.issue(source,target,session,query,"",null,emptyList(),emptyList())
        val initial=bank.resolve(key,source,target,session)
        bank.checkpoint(key,initial,initial.copy(after="two",position=2));bank.checkpoint(key,initial,initial.copy(after="one",position=1))
        assertEquals("two",bank.resolve(key,source,target,session).after)
        now.set(111);assertThrows(SourceFailure::class.java){bank.resolve(key,source,target,session)}
    }
}
