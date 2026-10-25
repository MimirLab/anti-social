package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class InstagramPublicGraphQlTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val target=requireNotNull(routes.recognize("https://www.instagram.com/nasa/"))
    private val original=Source(Platform.INSTAGRAM,InstagramPublicGraphQlAdapter.FRONTEND,"https://www.instagram.com",true,SourceRole.ORIGINAL)
    private val code="DeTUk4sDSp-"
    private fun node(code:String=this.code,user:String="nasa",type:String="XIGPolarisVideoMedia")=JsonObject().apply {
        addProperty("__typename",type);addProperty("__isXIGPolarisMedia",type);addProperty("code",code)
        addProperty("pk",InstagramPublicGraphQl.mediaId(code));addProperty("display_uri","https://images.example/$code.jpg")
        add("caption",JsonObject().apply {addProperty("text","Read the mission data https://science.example/data")})
        add("user",JsonObject().apply {addProperty("username",user);addProperty("full_name","NASA");addProperty("pk","123")})
    }
    private fun timeline(nodes:List<JsonObject>,after:String?="after-one",more:Boolean=true,owner:String="123")=JsonObject().apply {
        add("data",JsonObject().apply {add("xig_user_by_username",JsonObject().apply {
            addProperty("pk",owner);add("polaris_ordered_timeline_connection",JsonObject().apply {
                add("edges",JsonArray().apply {nodes.forEach {add(JsonObject().apply {add("node",it)})}})
                add("page_info",JsonObject().apply {addProperty("has_next_page",more);after?.let {addProperty("end_cursor",it)}})
            })
        })})
    }.toString()
    private fun detail(selected:JsonObject=node())=JsonObject().apply {add("data",JsonObject().apply {
        add("xig_polaris_media",JsonObject().apply {addProperty("__typename",selected["__typename"].asString)
            addProperty("pk",InstagramPublicGraphQl.mediaId(code));addProperty("code",code);add("if_not_gated_logged_out",selected)})
    })}.toString()
    private fun fields(request:RecordedRequest)=requireNotNull(("https://fixture.example/?"+request.body.readUtf8()).toHttpUrl()).let {url->url.queryParameterNames.associateWith {url.queryParameter(it)}}
    private inner class Fixture(filter:RequestFilter=object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)}):AutoCloseable {
        val server=MockWebServer().apply {start()}
        val gateway=NetworkGateway(cleaner,filter,true)
        val client=gateway.client()
        val source=original.copy(root=server.url("/").toString().trimEnd('/'))
        val adapter=InstagramPublicGraphQlAdapter(source,gateway,routes)
        fun enqueue(body:String,status:Int=200)=server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body))
        fun request()=requireNotNull(server.takeRequest(1,TimeUnit.SECONDS))
        override fun close(){client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();server.shutdown()}
    }
    @Test fun realGatewayPostsReadonlyQueriesAndAdvancesIssuedCursorsWithoutCookies():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(timeline(listOf(node(),node("DeJ28eTxo4s","nasawebb","XIGPolarisImageMedia"))))
            f.enqueue(timeline(listOf(node("DeAYvsnn4la")),more=false))
            val first=f.adapter.fetch(target);val second=f.adapter.fetch(target,first.next)
            assertEquals(2,first.posts.size);assertEquals("nasawebb",first.posts[1].author)
            assertTrue(first.posts.all {it.preview&&it.publishedAt==null});assertEquals(1,second.posts.size);assertNull(second.next)
            assertTrue(FeedRefresh.posts(first,Platform.INSTAGRAM).isEmpty());assertNotNull(FeedRefresh.pendingReason(first,Platform.INSTAGRAM))
            val a=f.request();val b=f.request();assertEquals("POST",a.method);assertEquals("/api/graphql",a.path)
            for(request in listOf(a,b)){assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"));assertEquals("936619743392459",request.getHeader("X-IG-App-ID"))}
            val aa=fields(a);val bb=fields(b)
            assertEquals(a.getHeader("X-FB-LSD"),aa["lsd"]);assertEquals(b.getHeader("X-FB-LSD"),bb["lsd"])
            assertEquals("29483846714553007",aa["doc_id"])
            assertFalse(JsonParser.parseString(aa["variables"]).asJsonObject.has("after"))
            assertEquals("after-one",JsonParser.parseString(bb["variables"]).asJsonObject["after"].asString)
        }
    }
    @Test fun selectedPostRetainsActualTimeAndVideoWithoutHarvestingNestedRecommendations():Unit=runBlocking {
        Fixture().use {f->
            val selected=node().apply {addProperty("taken_at",1791645302);add("video_versions",JsonArray().apply {add(JsonObject().apply {addProperty("url","https://videos.example/movie.mp4")})})
                getAsJsonObject("user").add("polaris_ordered_timeline_connection",JsonParser.parseString(timeline(listOf(node("DeAYvsnn4la")))).asJsonObject)}
            f.enqueue(detail(selected))
            val post=f.adapter.fetch(requireNotNull(routes.recognize("https://www.instagram.com/p/$code/"))).posts.single()
            assertFalse(post.preview);assertEquals(1791645302000L,post.publishedAt);assertEquals("video",post.media.single().type)
            assertEquals("https://videos.example/movie.mp4",post.media.single().url);assertEquals("NASA",post.author)
            val vars=JsonParser.parseString(fields(f.request())["variables"]).asJsonObject
            assertEquals("4004634989362489982",vars["media_id"].asString)
        }
    }
    @Test fun missingVideoAndGalleryDataRemainExplicitlyPartial() {
        val provenance=Provenance(original,1,"https://www.instagram.com/api/graphql")
        for(type in listOf("XIGPolarisVideoMedia","XIGPolarisCarouselMedia")) {
            val data=JsonParser.parseString(detail(node(type=type))).asJsonObject.getAsJsonObject("data")
            val post=InstagramPublicGraphQl.post(data,code,provenance)
            assertEquals("image",post.media.single().type);assertEquals(ExtractionStatus.PARTIAL,post.extractionCoverage?.attachments)
            assertNull(post.publishedAt)
        }
    }
    @Test fun aFullGalleryRetainsAllChildrenAndMissingChildrenDoNotBecomeComplete() {
        val provenance=Provenance(original,1,original.root)
        val selected=node(type="XIGPolarisCarouselMedia").apply {addProperty("carousel_media_count",2);add("carousel_media",JsonArray().apply {
            add(node(type="XIGPolarisImageMedia"));add(node("DeAYvsnn4la",type="XIGPolarisImageMedia"))})}
        val data=JsonParser.parseString(detail(selected)).asJsonObject.getAsJsonObject("data")
        assertEquals(2,InstagramPublicGraphQl.post(data,code,provenance).media.size)
        assertEquals(ExtractionStatus.PRESENT,InstagramPublicGraphQl.post(data,code,provenance).extractionCoverage?.attachments)
        data.getAsJsonObject("xig_polaris_media").getAsJsonObject("if_not_gated_logged_out").addProperty("carousel_media_count",3)
        assertEquals(ExtractionStatus.PARTIAL,InstagramPublicGraphQl.post(data,code,provenance).extractionCoverage?.attachments)
    }
    @Test fun fullImageCandidatesAndExplicitRelationshipsSurviveTheNewRoute() {
        val selected=node(type="XIGPolarisImageMedia").apply {
            add("image_versions2",JsonObject().apply {add("candidates",JsonArray().apply {add(JsonObject().apply {addProperty("url","https://images.example/full.jpg")})})})
            add("coauthor_producers",JsonArray().apply {add(JsonObject().apply {addProperty("username","nasasolarsystem");addProperty("full_name","NASA Solar System")})})
            add("usertags",JsonObject().apply {add("in",JsonArray().apply {add(JsonObject().apply {add("user",JsonObject().apply {addProperty("username","nasaartemis")})})})})
        }
        val post=InstagramPublicGraphQl.post(JsonParser.parseString(detail(selected)).asJsonObject.getAsJsonObject("data"),code,Provenance(original,1,original.root))
        assertEquals("https://images.example/full.jpg",post.media.single().url);assertNotNull(post.media.single().preview)
        assertEquals(listOf(PublicRelationKind.COLLABORATOR,PublicRelationKind.TAGGED),post.publicRelations!!.map {it.kind})
        assertTrue(PublicRelations.valid(post))
    }
    @Test fun postIdentityAndGateAreCheckedBeforeAnyNestedMaterial() {
        val provenance=Provenance(original,1,original.root)
        val data=JsonParser.parseString(detail()).asJsonObject.getAsJsonObject("data")
        data.getAsJsonObject("xig_polaris_media").addProperty("pk","123")
        assertThrows(SourceFailure::class.java){InstagramPublicGraphQl.post(data,code,provenance)}
        data.getAsJsonObject("xig_polaris_media").addProperty("pk",InstagramPublicGraphQl.mediaId(code))
        data.getAsJsonObject("xig_polaris_media").add("if_not_gated_logged_out",JsonNull.INSTANCE)
        assertEquals(HealthStatus.LOGIN_REQUIRED,assertThrows(SourceFailure::class.java){InstagramPublicGraphQl.post(data,code,provenance)}.status)
    }
    @Test fun aRepeatedOrChangingOwnerPageCannotLookLikeProgress():Unit=runBlocking {
        for(body in listOf(timeline(listOf(node()),"after-two"),timeline(listOf(node("DeAYvsnn4la")),"after-two",owner="456")))Fixture().use {f->
            f.enqueue(timeline(listOf(node())));f.enqueue(body)
            val first=f.adapter.fetch(target)
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.fetch(target,first.next)}}
            assertEquals(2,f.server.requestCount)
        }
    }
    @Test fun aStalledCursorStillDeliversNewMaterialButStopsPagination():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(timeline(listOf(node())));f.enqueue(timeline(listOf(node("DeAYvsnn4la"))))
            val first=f.adapter.fetch(target);val second=f.adapter.fetch(target,first.next)
            assertEquals(1,second.posts.size);assertNull(second.next);assertNotNull(second.continuationIssue)
        }
    }
    @Test fun refusedOrFailedReadsDoNotBootstrapOrReturnEmptyHealthyData():Unit=runBlocking {
        for((body,status,health)in listOf(Triple("{}",429,HealthStatus.RATE_LIMITED),Triple("{}",401,HealthStatus.LOGIN_REQUIRED),
            Triple("{\"errors\":[{\"message\":\"login_required\"}]}",200,HealthStatus.LOGIN_REQUIRED),
            Triple("{\"errors\":[{\"message\":\"rate limit exceeded\"}]}",200,HealthStatus.RATE_LIMITED),
            Triple("{\"errors\":[{\"message\":\"checkpoint required\"}]}",200,HealthStatus.CHALLENGE),Triple("{}",200,HealthStatus.EMPTY)))Fixture().use {f->
            f.enqueue(body,status)
            val failure=assertThrows(SourceFailure::class.java){runBlocking {f.adapter.fetch(target)}}
            assertEquals(health,failure.status);assertEquals(1,f.server.requestCount)
        }
    }
    @Test fun aChangedReadonlyProtocolCanRefreshFromLiteralActorZeroMetadata():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue("{\"errors\":[{\"message\":\"Unknown query\"}]}")
            f.enqueue("""<script type="application/json">{"preloader":{"actorID":"0","queryName":"PolarisLoggedOutDesktopWWWProfilePostsTabContentQuery","queryID":"12345678901234567","variables":{"username":"nasa","__relay_internal__pv__ExtraFeatureRelayrelayprovider":false,"arbitrary":"ignored"}}}</script>""")
            f.enqueue(timeline(listOf(node()),more=false))
            assertEquals(1,f.adapter.fetch(target).posts.size)
            f.request();assertEquals("GET",f.request().method)
            val fields=fields(f.request());assertEquals("12345678901234567",fields["doc_id"])
            val vars=JsonParser.parseString(fields["variables"]).asJsonObject
            assertFalse(vars.has("arbitrary"));assertFalse(vars["__relay_internal__pv__ExtraFeatureRelayrelayprovider"].asBoolean)
        }
    }
    @Test fun bootstrapRejectsAuthenticatedOtherTargetOrAmbiguousQueries() {
        fun declaration(actor:String="0",username:String="nasa",id:String="12345678901234567")="""{"actorID":"$actor","queryName":"PolarisLoggedOutDesktopWWWProfilePostsTabContentQuery","queryID":"$id","variables":{"username":"$username"}}"""
        val selected=requireNotNull(InstagramPublicGraphQl.identity(target))
        for(raw in listOf(declaration("100"),declaration(username="other"),"{\"a\":${declaration()},\"b\":${declaration(id="76543210987654321")}}"))
            assertNull(InstagramPublicGraphQl.bootstrap("<script type='application/json'>$raw</script>",selected))
    }
    @Test fun expiredForgedCrossTargetAndModeRoundTripCursorsCannotMakeRequests():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(timeline(listOf(node())));val first=f.adapter.fetch(target);f.request()
            for((t,c)in listOf(target to "ig-public-v1:forged",target.copy(identity="other",canonicalUrl="https://www.instagram.com/other/") to first.next))
                assertThrows(SourceFailure::class.java){runBlocking {f.adapter.fetch(t,c)}}
            f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.fetch(target,first.next)}}
            assertEquals(1,f.server.requestCount)
        }
        val now=AtomicLong(100);val bank=InstagramPublicCursors(now::get,maxEntries=2,ttl=10)
        val session=NetworkSession(NetworkPolicy());val query=InstagramPublicGraphQl.query(requireNotNull(InstagramPublicGraphQl.identity(target)))
        fun issue()=bank.issue(original,target,session,query,"123","after",listOf("one"),listOf("after"))
        val first=issue();issue();issue();assertEquals(2,bank.size())
        assertThrows(SourceFailure::class.java){bank.resolve(first,original,target,session)}
        val recent=issue();now.set(111);assertThrows(SourceFailure::class.java){bank.resolve(recent,original,target,session)}
        now.set(200);val rollback=issue();now.set(199);assertThrows(SourceFailure::class.java){bank.resolve(rollback,original,target,session)}
    }
    @Test fun aFailedPageCanRetryTheSameIssuedCursor():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(timeline(listOf(node())));f.enqueue("{}",503);f.enqueue(timeline(listOf(node("DeAYvsnn4la")),more=false))
            val first=f.adapter.fetch(target)
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.fetch(target,first.next)}}
            assertEquals(1,f.adapter.fetch(target,first.next).posts.size)
        }
    }
    @Test fun filtersTorPauseAndModeChangesRemainGatewayBoundaries():Unit=runBlocking {
        Fixture(object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(true,"Fixture block")}).use {f->
            assertTrue(runCatching {f.adapter.fetch(target)}.isFailure);assertEquals(0,f.server.requestCount)
        }
        Fixture().use {f->
            f.gateway.configure(NetworkPolicy(tor=true));assertTrue(runCatching {f.adapter.fetch(target)}.isFailure);assertEquals(0,f.server.requestCount)
        }
        Fixture().use {f->
            f.server.enqueue(MockResponse().setBody(timeline(listOf(node()))).setBodyDelay(500,TimeUnit.MILLISECONDS))
            val read=async(Dispatchers.IO) {runCatching {f.adapter.fetch(target)}};f.request()
            f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())
            assertTrue(read.await().isFailure)
        }
    }
    @Test fun malformedOversizedOrDeepResponsesNeverBecomePublicPosts() {
        for(body in listOf("<html>Log in</html>","{\"data\":{}} trailing","{"+"\"x\":"+"[".repeat(70)+"0"+"]".repeat(70)+"}"," ".repeat(4*1024*1024+1))) {
            assertThrows(SourceFailure::class.java){InstagramPublicGraphQl.envelope(GatewayDocument(200,"OK",emptyMap(),body,original.root+"/api/graphql"),original,1)}
        }
    }
    @Test fun bundledSourceIsAvailableOnlyAsAnOriginalFallbackAndDoesNotClaimAFeed() {
        val data=JsonParser.parseReader(javaClass.getResourceAsStream("/public-pathways.json")!!.reader()).asJsonObject
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=error("No socket in catalog test")})
        val pool=SourcePool(gateway,routes,PublicPathways(data))
        assertTrue(pool.sourcesFor(target,Capability.TIMELINE,true).any {it.frontend==original.frontend})
        assertFalse(pool.sourcesFor(target,Capability.TIMELINE,false).any {it.frontend==original.frontend})
        assertFalse(pool.sourceReader(original).capabilities.contains(Capability.FEED))
        assertTrue(pool.sourceReader(original).capabilities.contains(Capability.COMMENTS))
        assertTrue(pool.sourceReader(original).accepts(target));assertFalse(pool.sourceReader(original).accepts(target.copy(canonicalUrl="https://other.example/nasa/")))
    }
}
