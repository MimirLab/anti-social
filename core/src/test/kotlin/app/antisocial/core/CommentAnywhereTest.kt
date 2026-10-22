package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CommentAnywhereTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private fun routes()=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    private val url="https://x.com/NASA/status/123"
    private class Memory: AnywhereIdentityStore {
        var identity: AnywhereIdentity?=null;var saves=0
        override suspend fun load()=identity
        override suspend fun save(identity: AnywhereIdentity){this.identity=identity;saves++}
    }
    private fun client(server: MockWebServer,store: Memory=Memory(),gateway: NetworkGateway=NetworkGateway(cleaner,permit,true),at: Long=1_000_000)=CommentAnywhereClient(gateway,store,server.url("/api/").toString(),server.url("/signup").toString(),server.url("/refresh").toString()){at}
    private fun post(platform: Platform,address: String)=Post("fixture",platform,address,"Example","Post")
    private fun json(text: String)=MockResponse().setHeader("Content-Type","application/json").setBody(text)

    @Test fun publicServiceHttpFailuresKeepStatusAndCooldownWithoutCreatingIdentity(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(500).setHeader("Retry-After","30").setBody("Service unavailable"))
            val store=Memory()
            try {client(server,store).read(url,CommentOrder.NEWEST);fail("An HTTP error is not an empty discussion")}
            catch(e: SourceFailure){assertEquals(500,e.httpStatus);assertEquals(30_000L,e.retryAfterMs);assertEquals(HealthStatus.UNAVAILABLE,e.status)}
            assertEquals(1,server.requestCount);assertEquals(0,store.saves);assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun originalRootsCoverEverySupportedPlatformWithoutFrontendsOrArchiveReplays() {
        val examples=mapOf(Platform.X to url,Platform.BLUESKY to "https://bsky.app/profile/bsky.app/post/abc",
            Platform.REDDIT to "https://www.reddit.com/r/test/comments/abc/title",Platform.TUMBLR to "https://www.tumblr.com/staff/123/title",
            Platform.TIKTOK to "https://www.tiktok.com/@nasa/video/123",Platform.INSTAGRAM to "https://www.instagram.com/p/ABC",
            Platform.PINTEREST to "https://www.pinterest.com/pin/123",Platform.IFUNNY to "https://ifunny.co/picture/example-ABC",
            Platform.TIEBA to "https://tieba.baidu.com/p/123",Platform.THREADS to "https://www.threads.com/@nasa/post/ABC",
            Platform.FACEBOOK to "https://www.facebook.com/NASA/posts/123",Platform.TRUTH_SOCIAL to "https://truthsocial.com/@example/123",
            Platform.MASTODON to "https://mastodon.social/@example/123",Platform.LEMMY to "https://lemmy.world/post/123",
            Platform.PIXELFED to "https://pixelfed.social/p/example/123",Platform.MISSKEY to "https://misskey.io/notes/abc",
            Platform.GETTR to "https://gettr.com/post/abc",Platform.GAB to "https://gab.com/Example/posts/123",
            Platform.TELEGRAM to "https://t.me/example/123",Platform.LINKEDIN to "https://www.linkedin.com/feed/update/urn:li:activity:123",
            Platform.SNAPCHAT to "https://www.snapchat.com/@nasa/spotlight/abc",Platform.YOUTUBE to "https://youtu.be/BaW_jenozKc")
        assertEquals(Platform.entries.filterNot {it==Platform.RSS}.toSet(),examples.keys)
        examples.forEach {(platform,address)->assertEquals(platform.label,address,AnywhereUrls.root(post(platform,address+"/"),routes()))}
        val routes=routes();val nitter=routes.sources.first {it.platform==Platform.X&&it.frontend=="nitter"}
        assertEquals(url,AnywhereUrls.root(post(Platform.X,nitter.root+"/NASA/status/123?utm_source=test#reply"),routes))
        assertEquals(url,AnywhereUrls.root(post(Platform.X,"https://web.archive.org/web/20200101000000/$url"),routes))
        val recovered=post(Platform.X,url).copy(recoveredArchive=ArchiveRecovery("wayback",1,"https://web.archive.org/web/20200101000000/$url"))
        assertEquals(url,AnywhereUrls.root(recovered,routes))
    }
    @Test fun youtubeUsesItsOfficialQueryFreePermalinkWithoutCollapsingDifferentVideos() {
        val routes=routes();val id="BaW_jenozKc";val other="abcdefghijk"
        for(address in listOf("https://www.youtube.com/watch?v=$id&t=42","https://youtube.com/shorts/$id","https://youtu.be/$id?si=tracking"))assertEquals("https://youtu.be/$id",AnywhereUrls.root(post(Platform.YOUTUBE,address),routes))
        assertEquals("https://youtu.be/$other",AnywhereUrls.root(post(Platform.YOUTUBE,"https://www.youtube.com/watch?v=$other"),routes))
    }
    @Test fun queryIdentitiesCannotCollapseDifferentPostsIntoOneDiscussion() {
        val routes=routes()
        listOf("https://www.facebook.com/story.php?story_fbid=123&id=456","https://www.facebook.com/photo.php?fbid=123",
            "https://www.facebook.com/NASA/posts/123?comment_id=456").forEach {address->assertThrows(IllegalArgumentException::class.java){AnywhereUrls.root(post(Platform.FACEBOOK,address),routes)}}
        assertThrows(IllegalArgumentException::class.java){AnywhereUrls.root(post(Platform.TIEBA,"https://tieba.baidu.com/p/123?pid=456"),routes)}
        assertEquals(url,AnywhereUrls.root(post(Platform.X,url+"?s=20&utm_source=share#x"),routes))
        assertThrows(IllegalArgumentException::class.java){AnywhereUrls.root(post(Platform.REDDIT,"https://redd.it/abc"),routes)}
        assertThrows(IllegalArgumentException::class.java){AnywhereUrls.root(post(Platform.X,"https://unknown.example/post/123"),routes)}
        assertThrows(IllegalArgumentException::class.java){AnywhereUrls.root(post(Platform.X,"https://user:secret@x.com/NASA/status/123"),routes)}
    }
    @Test fun federatedRootsNeverShareTheLocalIdOfAnotherServer() {
        val routes=routes();routes.target(Platform.MASTODON,"@alice@social.example");routes.target(Platform.MASTODON,"@alice@other.example")
        val a=AnywhereUrls.root(post(Platform.MASTODON,"https://social.example/@alice/123"),routes)
        val b=AnywhereUrls.root(post(Platform.MASTODON,"https://other.example/@alice/123"),routes)
        assertNotEquals(a,b)
    }
    @Test fun readingUsesObservedWireSortAndTreeBoundsWithoutAuthOrRegistration(): Unit=runBlocking {
        MockWebServer().use {server->server.start();repeat(3){server.enqueue(json("""{"items":[]}"""))}
            val store=Memory();val client=client(server,store)
            client.read(url,CommentOrder.NEWEST);client.read(url,CommentOrder.SLOP,60);client.read(url,CommentOrder.NEWEST,parentId="reply42")
            val first=server.takeRequest();assertEquals("/api/list",first.requestUrl!!.encodedPath);assertEquals(url,first.requestUrl!!.queryParameter("pageUrl"));assertEquals("New",first.requestUrl!!.queryParameter("sort"));assertEquals("30",first.requestUrl!!.queryParameter("limit"));assertEquals("2",first.requestUrl!!.queryParameter("depth"))
            assertNull(first.getHeader("Authorization"));assertNull(first.getHeader("Cookie"));assertNull(first.getHeader("X-PCA-Origin"));assertNull(first.getHeader("Origin"))
            val top=server.takeRequest();assertEquals("Top",top.requestUrl!!.queryParameter("sort"));assertEquals("60",top.requestUrl!!.queryParameter("limit"))
            val subtree=server.takeRequest();assertEquals("/api/subtree",subtree.requestUrl!!.encodedPath);assertEquals("reply42",subtree.requestUrl!!.queryParameter("parentId"));assertEquals("5",subtree.requestUrl!!.queryParameter("depth"))
            assertEquals(0,store.saves);assertEquals(3,server.requestCount)
        }
    }
    @Test fun anonymousIdentityIsCreatedOnlyForAnExplicitWriteAndReusedForEveryAction(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(json("""{"localId":"owner","idToken":"token123","refreshToken":"renew123","expiresIn":"3600"}"""));repeat(5){server.enqueue(json("""{"ok":true}"""))}
            val memory=Memory();val gateway=NetworkGateway(cleaner,permit,true);val observed=AtomicInteger();gateway.onTextResponse={observed.incrementAndGet()}
            val client=client(server,memory,gateway)
            client.mutate(url,CommentOrder.NEWEST,AnywhereAction.POST,"Native comment")
            client.mutate(url,CommentOrder.SLOP,AnywhereAction.REPLY,"A reply","parent42")
            client.mutate(url,CommentOrder.NEWEST,AnywhereAction.VOTE,id="comment42",value=-1)
            client.mutate(url,CommentOrder.NEWEST,AnywhereAction.EDIT,"Edited text","comment42")
            client.mutate(url,CommentOrder.NEWEST,AnywhereAction.DELETE,id="comment42")
            val signup=server.takeRequest();assertEquals("/signup",signup.path);assertNull(signup.getHeader("Authorization"));assertEquals(true,JsonParser.parseString(signup.body.readUtf8()).asJsonObject["returnSecureToken"].asBoolean)
            val actions=listOf("postComment","replyComment","vote","editComment","deleteComment")
            actions.forEachIndexed {index,endpoint->val request=server.takeRequest();assertEquals("POST",request.method);assertEquals("/api/$endpoint",request.path);assertEquals("Bearer token123",request.getHeader("Authorization"));assertEquals("firebase",request.getHeader("X-PCA-Provider"));assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("X-PCA-Origin"));val body=JsonParser.parseString(request.body.readUtf8()).asJsonObject;assertEquals(url,body["pageUrl"].asString);if(index==1){assertEquals("parent42",body["parentId"].asString);assertFalse(body.has("id"));assertEquals("Top",body["sort"].asString)};if(index==2)assertEquals(-1,body["value"].asInt)}
            assertEquals(1,memory.saves);assertEquals(0,observed.get());assertTrue(memory.identity!!.owns(AnywhereComment("id","text",authorIds=listOf("anon:owner"))))
        }
    }
    @Test fun expiredIdentityRefreshesWithoutLosingCommentOwnership(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(json("""{"id_token":"newtoken","refresh_token":"newrenew","expires_in":"3600"}"""));server.enqueue(json("{}"))
            val memory=Memory().apply {identity=AnywhereIdentity("owner","oldtoken","oldrenew",1)};val client=client(server,memory)
            client.mutate(url,CommentOrder.NEWEST,AnywhereAction.POST,"Hello")
            val refresh=server.takeRequest();assertEquals("/refresh",refresh.path);assertTrue(refresh.body.readUtf8().contains("refresh_token=oldrenew"));assertNull(refresh.getHeader("Authorization"));assertEquals("Bearer newtoken",server.takeRequest().getHeader("Authorization"));assertEquals("owner",memory.identity!!.uid)
        }
    }
    @Test fun failedRefreshNeverSilentlyCreatesAnotherIdentityOrPublishes(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(401))
            val memory=Memory().apply {identity=AnywhereIdentity("owner","old","renew",1)}
            assertThrows(SourceFailure::class.java){runBlocking {client(server,memory).mutate(url,CommentOrder.NEWEST,AnywhereAction.POST,"Draft")}}
            assertEquals("/refresh",server.takeRequest().path);assertEquals(1,server.requestCount);assertEquals("owner",memory.identity!!.uid);assertEquals(0,memory.saves)
        }
    }
    @Test fun credentialsCannotFollowRedirectsOrFilterRewritesAndPublicReadsStillStripThem(): Unit=runBlocking {
        MockWebServer().use {server->MockWebServer().use {other->server.start();other.start();server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",other.url("/steal")));server.enqueue(MockResponse().setBody("public"))
            val gateway=NetworkGateway(cleaner,permit,true)
            assertThrows(IOException::class.java){runBlocking {gateway.service(server.url("/post").toString(),json=JsonObject(),token="private",provider="firebase")}}
            assertEquals(1,server.requestCount);assertEquals(0,other.requestCount);assertEquals("Bearer private",server.takeRequest().getHeader("Authorization"))
            gateway.replaceFilter(object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl=other.url("/steal").toString())})
            assertThrows(IOException::class.java){runBlocking {gateway.service(server.url("/post").toString(),json=JsonObject(),token="private",provider="firebase")}}
            assertEquals(0,other.requestCount);assertEquals(1,server.requestCount)
            gateway.replaceFilter(permit);gateway.text(server.url("/public").toString(),headers=mapOf("Authorization" to "Bearer private","X-PCA-Provider" to "firebase"))
            val public=server.takeRequest();assertNull(public.getHeader("Authorization"));assertNull(public.getHeader("X-PCA-Provider"))
        }}
    }
    @Test fun aDisconnectedWriteIsNeverAutomaticallyReplayed(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));server.enqueue(json("{}"))
            val gateway=NetworkGateway(cleaner,permit,true)
            assertThrows(IOException::class.java){runBlocking {gateway.service(server.url("/post").toString(),json=JsonObject().apply {addProperty("text","one time")},token="private",provider="firebase")}}
            assertEquals(1,server.requestCount);assertNotNull(server.takeRequest(1,TimeUnit.SECONDS))
        }
    }
    @Test fun selectedTorStopsReadsRegistrationAndWritesBeforeAnyDirectConnection(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val gateway=NetworkGateway(cleaner,permit,true);gateway.configure(NetworkPolicy(tor=true));val memory=Memory();val client=client(server,memory,gateway)
            assertThrows(IOException::class.java){runBlocking {client.read(url,CommentOrder.NEWEST)}}
            assertThrows(IOException::class.java){runBlocking {client.mutate(url,CommentOrder.NEWEST,AnywhereAction.POST,"Draft")}}
            assertEquals(0,server.requestCount);assertEquals(0,memory.saves)
        }
    }
    @Test fun invalidAndOversizedWritesAreRejectedBeforeRegistration(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val client=client(server)
            listOf("", "漢".repeat(3000)).forEach {text->assertThrows(IllegalArgumentException::class.java){runBlocking {client.mutate(url,CommentOrder.NEWEST,AnywhereAction.POST,text)}}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {client.mutate(url,CommentOrder.NEWEST,AnywhereAction.REPLY,"Draft","bad/id")}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {client.mutate(url,CommentOrder.NEWEST,AnywhereAction.VOTE,id="id",value=99)}}
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun onlyAnExplicitItemsArrayCanMeanNoComments() {
        listOf("{}","<html>Login required</html>","{\"error\":\"unavailable\",\"items\":[]}","{\"ok\":false,\"items\":[]}").forEach {text->assertThrows(Exception::class.java){CommentAnywhereClient.parse(text,url,CommentOrder.NEWEST,30,1)}}
        assertTrue(CommentAnywhereClient.parse("{\"items\":[]}",url,CommentOrder.NEWEST,30,1).items.isEmpty())
    }
    @Test fun nestedRepliesDeletedNodesAndOwnershipSurviveParsingWithoutFlatteningTheTree() {
        val text="""{"items":[{"id":"parent","text":"Public text","username":"Alice","timestamp":"2025-09-11T12:00:00Z","up":5,"down":1,"userId":"anon:owner","children":[{"id":"reply","text":"[deleted by user]","timestamp":1757592000000,"hasMore":true}]}]}"""
        val page=CommentAnywhereClient.parse(text,url,CommentOrder.SLOP,30,1)
        assertEquals(listOf(0,1),page.flattened().map {it.second});assertTrue(page.items.first().children.first().deleted);assertTrue(page.items.first().children.first().hasMore);assertEquals(5L,page.items.first().up);assertEquals(1757592000000L,page.items.first().timestamp);assertTrue(AnywhereIdentity("owner","t","r",1).owns(page.items.first()))
        val roundTrip=CommentAnywhereClient.parse(Gson().toJson(mapOf("items" to page.items)),url,page.order,30,1)
        assertEquals(page.items,roundTrip.items)
    }
    @Test fun responseBudgetLimitsNodesDepthTextAndDuplicateIds() {
        val nodes=(0..250).joinToString(","){"""{"id":"c$it","text":"Text"}"""}
        val page=CommentAnywhereClient.parse("{\"items\":[$nodes]}",url,CommentOrder.NEWEST,100,1);assertEquals(200,page.flattened().size);assertTrue(page.limited)
        val duplicate=CommentAnywhereClient.parse("""{"items":[{"id":"same","text":"First"},{"id":"same","text":"Again"}]}""",url,CommentOrder.NEWEST,30,1);assertEquals(1,duplicate.flattened().size)
        assertThrows(IllegalArgumentException::class.java){CommentAnywhereClient.parse("{\"items\":[{\"id\":\"id\",\"text\":\"${"x".repeat(33000)}\"}]}",url,CommentOrder.NEWEST,30,1)}
        assertThrows(IllegalArgumentException::class.java){CommentAnywhereClient.parse("x".repeat(512*1024+1),url,CommentOrder.NEWEST,30,1)}
        var deep="""{"id":"bottom","text":"Bottom"}""";repeat(20){deep="""{"id":"depth$it","text":"Nested","children":[$deep]}"""}
        val bounded=CommentAnywhereClient.parse("{\"items\":[$deep]}",url,CommentOrder.NEWEST,30,1);assertEquals(9,bounded.flattened().size);assertTrue(bounded.limited)
    }
}
