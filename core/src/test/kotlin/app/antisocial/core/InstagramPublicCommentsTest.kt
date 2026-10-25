package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class InstagramPublicCommentsTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val code="DeTUk4sDSp-";private val target=routes.recognize("https://www.instagram.com/p/$code/")!!
    private val root=Post("post:$code",Platform.INSTAGRAM,target.canonicalUrl,"NASA","Mission update")
    private fun comment(id:String,parent:String?=null)=JsonObject().apply {
        addProperty("__typename","XDTCommentDict");addProperty("pk",id);addProperty("text","Useful observation $id");addProperty("created_at",1792800000)
        add("parent_comment_id",parent?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
        add("user",JsonObject().apply {addProperty("username","reader$id");addProperty("full_name","Mission reader $id");addProperty("pk","99$id")})
    }
    private fun body(nodes:List<JsonObject>,after:String?="next",more:Boolean=true)=Gson().toJson(mapOf("data" to mapOf(
        "xdt_api__v1__media__media_id__comments__connection" to mapOf("edges" to nodes.map {mapOf("node" to it)},
            "page_info" to mapOf("has_next_page" to more,"end_cursor" to after)))))
    private inner class Fixture:AutoCloseable {
        val server=MockWebServer().apply {start()};val clock=AtomicLong(System.currentTimeMillis())
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        val client=gateway.client();val source=Source(Platform.INSTAGRAM,"instagram-public-graphql",server.url("/").toString().trimEnd('/'),true,SourceRole.ORIGINAL)
        val adapter=InstagramPublicGraphQlAdapter(source,gateway,routes,clock::get)
        fun enqueue(value:String,status:Int=200){server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(value))}
        fun request()=requireNotNull(server.takeRequest(1,TimeUnit.SECONDS))
        override fun close(){client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();server.shutdown()}
    }
    @Test fun commentsAndTheirIssuedContinuationStayInTheSelectedDiscussion():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(body(listOf(comment("10"),comment("11","10"))));f.enqueue(body(listOf(comment("12")),more=false))
            val first=f.adapter.comments(target);val second=f.adapter.comments(target,first.next)
            assertEquals(2,Discussions.comments(root,first).size);assertEquals(1,Discussions.comments(root,second).size)
            assertEquals("comment:$code:10",first.posts.first().id);assertEquals("comment:$code:10",first.posts[1].parentId)
            assertEquals("https://www.instagram.com/p/$code/c/10/",first.posts.first().canonicalUrl)
            assertEquals(1792800000000L,first.posts.first().publishedAt);assertNull(second.next)
            val a=f.request();val b=f.request();assertNull(a.getHeader("Cookie"));assertNull(a.getHeader("Authorization"));assertEquals("POST",a.method)
            val fields=("https://fixture.example/?"+b.body.readUtf8()).toHttpUrl()
            assertEquals("PolarisPostCommentsPaginationQuery",fields.queryParameter("fb_api_req_friendly_name"))
            val vars=JsonParser.parseString(fields.queryParameter("variables")).asJsonObject
            assertEquals("next",vars["after"].asString);assertEquals("recent",vars["sort_order"].asString)
            assertFalse(vars["__relay_internal__pv__PolarisIsLoggedInrelayprovider"].asBoolean)
        }
    }
    @Test fun commentPermalinkVariantsStayDistinctFromTheirPostAndOtherReplies() {
        for(path in listOf("p","reel","reels","tv")) {
            assertEquals("comment:$code:10",ContentIdentity.key(Platform.INSTAGRAM,"https://www.instagram.com/$path/$code/c/10/"))
            assertEquals("comment:$code:10",ContentIdentity.key(Platform.INSTAGRAM,"https://www.instagram.com/$path/$code/?comment_id=10"))
        }
        assertNotEquals(root.identityKey,root.copy(canonicalUrl=target.canonicalUrl+"?comment_id=10").identityKey)
        assertNotEquals(ContentIdentity.key(Platform.INSTAGRAM,target.canonicalUrl+"?comment_id=10"),ContentIdentity.key(Platform.INSTAGRAM,target.canonicalUrl+"?comment_id=11"))
        assertEquals("post:$code",ContentIdentity.key(Platform.INSTAGRAM,target.canonicalUrl+"?comment_id=invalid"))
        assertEquals("https://www.instagram.com/p/$code/c/10/",OriginalLinks(routes,cleaner).url("https://www.instagram.com/p/$code/c/10/"))
    }
    @Test fun numericIdentifiersAndOpaqueJsonCursorsPreserveExactSourceRelationships():Unit=runBlocking {
        Fixture().use {f->
            val one=comment("10").apply {addProperty("pk",10L);getAsJsonObject("user").addProperty("pk",9910L)}
            val two=comment("11","10").apply {addProperty("parent_comment_id",10L)}
            val cursor="{\"cursor\": \"next\"}"
            f.enqueue(body(listOf(one,two),after=cursor));f.enqueue(body(listOf(comment("12")),more=false))
            val first=f.adapter.comments(target);f.adapter.comments(target,first.next)
            assertEquals("comment:$code:10",first.posts[1].parentId);assertEquals("9910",first.accounts.first().persistentId)
            f.request();val fields=("https://fixture.example/?"+f.request().body.readUtf8()).toHttpUrl()
            assertEquals(cursor,JsonParser.parseString(fields.queryParameter("variables")).asJsonObject["after"].asString)
        }
    }
    @Test fun publicCommentBootstrapCannotAssertThatTheReaderIsLoggedIn() {
        val selected=InstagramPublicGraphQl.identity(target)!!;val expected=InstagramPublicGraphQl.commentsQuery(code)
        val html="""<script type="application/json">{"p":{"actorID":"0","queryName":"PolarisPostCommentsPaginationQuery","queryID":"12345678901234567","variables":{"media_id":"${InstagramPublicGraphQl.mediaId(code)}","__relay_internal__pv__PolarisIsLoggedInrelayprovider":true}}}</script>"""
        val refreshed=InstagramPublicGraphQl.bootstrap(html,selected,expected)!!
        assertFalse(refreshed.variables["__relay_internal__pv__PolarisIsLoggedInrelayprovider"].asBoolean)
        assertEquals(expected.variables["media_id"],refreshed.variables["media_id"])
    }
    @Test fun missingAuthorsDatesAndParentsRemainUnknownWhileUsefulTextAndExplicitNamesSurvive():Unit=runBlocking {
        Fixture().use {f->
            val missing=comment("11").apply {remove("user");remove("created_at");remove("parent_comment_id")}
            f.enqueue(body(listOf(comment("10"),missing),more=false));val page=f.adapter.comments(target)
            assertEquals(2,Discussions.comments(root,page).size);val unknown=page.posts[1]
            assertNull(unknown.publishedAt);assertNull(unknown.parentId);assertEquals(ExtractionStatus.UNKNOWN,unknown.extractionCoverage!!.replyParent)
            assertEquals("Unavailable account",unknown.author)
            val author=page.accounts.single();assertEquals("reader10",author.target.identity);assertEquals("Mission reader 10",author.name);assertTrue(author.displayNameSupplied)
        }
    }
    @Test fun repeatedCommentPagesStopAndNewCommentsSurviveAStalledCursor():Unit=runBlocking {
        for(repeated in listOf(true,false))Fixture().use {f->
            f.enqueue(body(listOf(comment("10"))));val first=f.adapter.comments(target)
            f.enqueue(body(listOf(comment(if(repeated)"10"else "11"))))
            if(repeated)assertEquals(HealthStatus.EMPTY,assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(target,first.next)}}.status)
            else {val next=f.adapter.comments(target,first.next);assertEquals(1,next.posts.size);assertNull(next.next);assertNotNull(next.continuationIssue)}
        }
    }
    @Test fun forgedExpiredCrossTargetAndConnectionRoundTripCursorsCauseNoTraffic():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue(body(listOf(comment("10"))));val first=f.adapter.comments(target)
            val other=target.copy(canonicalUrl="https://www.instagram.com/p/DeAYvsnn4la/")
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(target,"ig-public-v1:forged")}}
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(other,first.next)}}
            f.clock.addAndGet(600_001)
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(target,first.next)}}
            assertEquals(1,f.server.requestCount)
        }
        Fixture().use {f->
            f.enqueue(body(listOf(comment("10"))));val first=f.adapter.comments(target)
            f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(target,first.next)}}
            assertEquals(1,f.server.requestCount)
        }
    }
    @Test fun refusalsNeverCauseBootstrapOrAnEmptyHealthyDiscussion():Unit=runBlocking {
        for((value,status)in listOf("{}" to 429,"{}" to 401,"{}" to 403,"{\"errors\":[{\"message\":\"login_required\"}]}" to 200,
            "{\"errors\":[{\"message\":\"rate limit exceeded\"}]}" to 200,"{\"data\":{}}" to 200))Fixture().use {f->
            f.enqueue(value,status);assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(target)}};assertEquals(1,f.server.requestCount)
        }
    }
    @Test fun onlyTheSelectedConnectionAndMatchingMediaCanSupplyDiscussionMaterial():Unit=runBlocking {
        Fixture().use {f->
            f.enqueue("{\"data\":{\"recommendation\":"+body(listOf(comment("10")))+"}}")
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(target)}}
        }
        Fixture().use {f->
            f.enqueue(body(listOf(comment("10").apply {addProperty("media_id","999")})))
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(target)}}
        }
        Fixture().use {f->
            f.enqueue(body((1..65).map {comment(it.toString())}))
            assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(target)}}
        }
    }
    @Test fun changedCommentQueryBootstrapsOnlyAnExactActorZeroOperation():Unit=runBlocking {
        for(valid in listOf(true,false))Fixture().use {f->
            f.enqueue("{\"errors\":[{\"message\":\"Unknown query\"}]}")
            val id=if(valid)InstagramPublicGraphQl.mediaId(code)else "999"
            f.enqueue("""<script type="application/json">{"preloader":{"actorID":"0","queryName":"PolarisPostCommentsPaginationQuery","queryID":"12345678901234567","variables":{"media_id":"$id","__relay_internal__pv__PolarisIsLoggedInrelayprovider":false}}}</script>""")
            if(valid){f.enqueue(body(listOf(comment("10")),more=false));assertEquals(1,f.adapter.comments(target).posts.size);assertEquals(3,f.server.requestCount)}
            else {assertThrows(SourceFailure::class.java){runBlocking {f.adapter.comments(target)}};assertEquals(2,f.server.requestCount)}
        }
    }
    @Test fun originalPermissionAndSelectedTorStillGuardTheNewCommentRoute():Unit=runBlocking {
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(true)})
        val catalog=JsonParser.parseReader(javaClass.getResourceAsStream("/public-pathways.json")!!.reader()).asJsonObject
        val pool=SourcePool(gateway,routes,PublicPathways(catalog))
        assertFalse(pool.supportsComments(Platform.INSTAGRAM,false));assertTrue(pool.supportsComments(Platform.INSTAGRAM,true))
        val source=pool.sourcesFor(target,Capability.COMMENTS,true).single {it.frontend=="instagram-public-graphql"}
        val profile=routes.target(Platform.INSTAGRAM,"nasa")
        assertThrows(SourceFailure::class.java){runBlocking {pool.sourceReader(source).comments(profile)}}
        gateway.configure(NetworkPolicy(tor=true,ready=false))
        assertThrows(java.io.IOException::class.java){runBlocking {pool.sourceReader(source).comments(target)}}
        assertEquals(0L,gateway.protectionStats().checked)
    }
}
