/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PublicSearchDiscoveryTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private fun gateway()=NetworkGateway(cleaner,object: RequestFilter {
        override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)
    },true)
    private val target get()=routes.search(Platform.FACEBOOK,"melbourne organic food")
    private fun result(url: String,title: String="Organic food")="""<div class="text-result-wrapper"><a href="$url"><h2>$title</h2></a><span>Index description, never a fabricated post.</span></div>"""
    private fun close(gateway: NetworkGateway) {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}

    @Test fun facebookSearchUsesTwoConcurrentQueriesOnOnlyTheSelectedProviderAndPrefersPosts(): Unit=runBlocking {
        MockWebServer().use {server->
            val both=CountDownLatch(2)
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    both.countDown()
                    if(!both.await(3,TimeUnit.SECONDS))return MockResponse().setResponseCode(503)
                    val query=request.requestUrl!!.queryParameter("q").orEmpty()
                    return MockResponse().setBody(if("inurl:posts" in query)
                        result("https://en-gb.facebook.com/OrganicMelbourne/posts/123")+result("https://www.facebook.com/OrganicMelbourne/posts/124")
                    else result("https://www.facebook.com/OrganicMelbourne")+result("https://www.facebook.com/OrganicMelbourne/posts/123"))
                }
            }
            server.start();val gateway=gateway()
            try {
                val source=Source(Platform.FACEBOOK,"librey",server.url("/").toString().trimEnd('/'),true)
                val page=withTimeout(5000){PublicSearchAdapter(source,gateway,routes).fetch(target)}
                assertTrue(page.posts.isEmpty());assertEquals(3,page.collections.size)
                assertEquals(listOf(TargetKind.POST,TargetKind.POST,TargetKind.PROFILE),page.collections.map {it.target.kind})
                assertEquals(setOf("https://www.facebook.com/OrganicMelbourne/posts/123","https://www.facebook.com/OrganicMelbourne/posts/124","https://www.facebook.com/OrganicMelbourne"),page.collections.map {it.target.canonicalUrl}.toSet())
                assertNull(page.next);assertEquals(source,page.provenance!!.source)
                assertEquals(2,server.requestCount)
                val queries=List(2){server.takeRequest().requestUrl!!.queryParameter("q")}.toSet()
                assertEquals(PublicSearchAdapter.facebookQueries(target).toSet(),queries)
                assertEquals(2L,gateway.protectionStats().checked)
            }finally {close(gateway)}
        }
    }

    @Test fun aFastUsefulLaneDoesNotWaitForTheSlowCompanionAndItsContinuationRemainsBound(): Unit=runBlocking {
        MockWebServer().use {server->
            val broadCalls=AtomicInteger()
            val bothStarted=CountDownLatch(2)
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val query=request.requestUrl!!.queryParameter("q").orEmpty()
                    bothStarted.countDown()
                    return if("inurl:posts" in query) {
                        if(!bothStarted.await(3,TimeUnit.SECONDS))return MockResponse().setResponseCode(503)
                        MockResponse().setBody(result("https://www.facebook.com/OrganicMelbourne/posts/123"))
                    }
                    else if(broadCalls.incrementAndGet()==1)MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else MockResponse().setBody(result("https://www.facebook.com/OrganicMelbourne/posts/124"))
                }
            }
            server.start();val gateway=gateway()
            try {
                val source=Source(Platform.FACEBOOK,"librey",server.url("/").toString().trimEnd('/'),true)
                val adapter=PublicSearchAdapter(source,gateway,routes)
                val first=withTimeout(4000){adapter.fetch(target)}
                assertEquals("https://www.facebook.com/OrganicMelbourne/posts/123",first.collections.single().target.canonicalUrl)
                val next=requireNotNull(first.next)
                assertTrue(next.startsWith("public-index:"));assertTrue(next.length<24*1024)
                val before=gateway.protectionStats().checked
                assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(routes.search(Platform.FACEBOOK,"unrelated private words"),next)}}
                assertThrows(IllegalArgumentException::class.java){runBlocking {PublicSearchAdapter(source.copy(root="https://another-index.example"),gateway,routes).fetch(target,next)}}
                val tampered=JsonParser.parseString(next.removePrefix("public-index:")).asJsonObject
                tampered.getAsJsonArray("lanes")[0].asJsonObject.addProperty("cursor","https://attacker.example/search.php?q=stolen")
                // A valid-looking other lane must not run before every cursor is checked.
                tampered.getAsJsonArray("lanes").add(JsonObject().apply {
                    addProperty("index",0);addProperty("attempts",1);add("cursor",com.google.gson.JsonNull.INSTANCE)
                })
                assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(target,"public-index:$tampered")}}
                assertEquals(before,gateway.protectionStats().checked)
                val second=withTimeout(2500){adapter.fetch(target,next)}
                assertEquals("https://www.facebook.com/OrganicMelbourne/posts/124",second.collections.single().target.canonicalUrl)
                assertNull(second.next);assertEquals(3,server.requestCount)
            }finally {close(gateway)}
        }
    }

    @Test fun fastProfileOnlyResultsDoNotCancelTheSlowerPostQuery(): Unit=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val query=request.requestUrl!!.queryParameter("q").orEmpty()
                    return if("inurl:posts" in query)MockResponse()
                        .setHeadersDelay(800,TimeUnit.MILLISECONDS)
                        .setBody(result("https://www.facebook.com/OrganicMelbourne/posts/123"))
                    else MockResponse().setBody(result("https://www.facebook.com/OrganicMelbourne"))
                }
            }
            server.start();val gateway=gateway()
            try {
                val source=Source(Platform.FACEBOOK,"librey",server.url("/").toString().trimEnd('/'),true)
                val page=withTimeout(4000){PublicSearchAdapter(source,gateway,routes).fetch(target)}
                assertEquals(listOf(TargetKind.POST,TargetKind.PROFILE),page.collections.map {it.target.kind})
                assertEquals("https://www.facebook.com/OrganicMelbourne/posts/123",page.collections.first().target.canonicalUrl)
                assertNull("Both actual query responses were consumed without retrying the post lane",page.next)
                assertEquals(2,server.requestCount)
            }finally {close(gateway)}
        }
    }

    @Test fun aProviderChallengeStopsTheCompanionRatherThanBecomingAnEmptyHealthySearch(): Unit=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest)=if("inurl:posts" in request.requestUrl!!.queryParameter("q").orEmpty())
                    MockResponse().setBody("<div id='challenge-container'>Verify you are human</div>")
                else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            server.start();val gateway=gateway()
            try {
                val source=Source(Platform.FACEBOOK,"librey",server.url("/").toString().trimEnd('/'),true)
                val failure=assertThrows(SourceFailure::class.java){runBlocking {withTimeout(2500){PublicSearchAdapter(source,gateway,routes).fetch(target)}}}
                assertEquals(HealthStatus.CHALLENGE,failure.status)
                assertTrue(server.requestCount in 1..2)
            }finally {close(gateway)}
        }
    }

    @Test fun cancellingASearchCancelsBothGatewayLanesAndNeverBroadensAScopedQuery(): Unit=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object: Dispatcher() {override fun dispatch(request: RecordedRequest)=MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)}
            server.start();val gateway=gateway()
            try {
                val source=Source(Platform.FACEBOOK,"librey",server.url("/").toString().trimEnd('/'),true)
                val adapter=PublicSearchAdapter(source,gateway,routes)
                val scoped=routes.search(Platform.FACEBOOK,target.identity,SearchScope(SearchScopeKind.ACCOUNT,"OrganicMelbourne"))
                assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(scoped)}}
                assertEquals(0,server.requestCount)
                val search=launch {adapter.fetch(target)}
                withTimeout(4000){while(server.requestCount<2)delay(10)}
                search.cancelAndJoin()
                withTimeout(4000){while(gateway.client().dispatcher.runningCallsCount()>0)delay(10)}
                assertTrue(search.isCancelled);assertEquals(2,server.requestCount)
            }finally {close(gateway)}
        }
    }

    @Test fun filteredEmptyIndexPagesRetainOnlyAnAuthenticSameQueryContinuation() {
        val source=Source(Platform.FACEBOOK,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
        val postQuery=PublicSearchAdapter.facebookQueries(target).first()
        fun body(query: String=postQuery,action: String="/html/")="""<div class="result"><a class="result__a" href="https://example.org/article">An off-site article</a></div><form action="$action"><input type="submit" value="Next"><input type="hidden" name="q" value="$query"><input type="hidden" name="s" value="10"><input type="hidden" name="vqd" value="token"></form>"""
        fun parse(body: String)=Extraction.parse(Payload(body,source.root+"/html/","text/html",200),target,source,routes,publicSearchQuery=postQuery)
        val page=parse(body())
        assertTrue(page.posts.isEmpty());assertTrue(page.collections.isEmpty());assertNotNull(page.next)
        assertEquals(source,page.provenance!!.source)
        assertEquals(HealthStatus.EMPTY,assertThrows(SourceFailure::class.java){parse(body(query="other query"))}.status)
        assertEquals(HealthStatus.EMPTY,assertThrows(SourceFailure::class.java){parse(body(action="https://evil.example/html/"))}.status)
        assertEquals(HealthStatus.EMPTY,assertThrows(SourceFailure::class.java){parse(body(action="https://user:secret@html.duckduckgo.com/html/"))}.status)
        assertEquals(HealthStatus.CHALLENGE,assertThrows(SourceFailure::class.java){parse("<form id='challenge-form'>Bots use DuckDuckGo</form>"+body())}.status)
        val librey=source.copy(frontend="librey",root="https://index.example")
        val url=librey.root+"/search.php?q="+DarwinRoutes.encode(postQuery)
        val next=librey.root+"/search.php?q="+DarwinRoutes.encode(postQuery)+"&p=2"
        val empty=Extraction.parse(Payload("<a rel='next' href='$next'>Next</a>",url,"text/html",200),target,librey,routes,publicSearchQuery=postQuery)
        assertEquals(next,empty.next);assertTrue(empty.collections.isEmpty())
        val credentialed=next.replace("https://","https://user:secret@")
        assertThrows(SourceFailure::class.java){Extraction.parse(Payload("<a rel='next' href='$credentialed'>Next</a>",url,"text/html",200),target,librey,routes,publicSearchQuery=postQuery)}
        assertThrows(SourceFailure::class.java){Extraction.parse(Payload("<a rel='next' href='${librey.root}/account/delete?q=${DarwinRoutes.encode(postQuery)}'>Next</a>",url,"text/html",200),target,librey,routes,publicSearchQuery=postQuery)}
    }

    @Test fun officialFacebookLocaleLinksNormalizeWithoutTrustingUnrelatedSubdomains() {
        for(host in listOf("en-gb.facebook.com","de-de.facebook.com","fr.facebook.com","m.facebook.com")) {
            val post=requireNotNull(routes.recognize("https://$host/OrganicMelbourne/posts/123?comment_id=456"))
            assertEquals(Platform.FACEBOOK,post.platform);assertEquals(TargetKind.POST,post.kind)
            assertEquals("https://www.facebook.com/OrganicMelbourne/posts/123?comment_id=456",post.canonicalUrl)
            assertEquals("comment:123:456",ContentIdentity.key(post.platform,post.canonicalUrl))
            assertTrue(PublicSearchAdapter.profileAllowed(requireNotNull(routes.recognize("https://$host/OrganicMelbourne"))))
        }
        for(host in listOf("en-gb.facebook.com.evil.example","facebook.com.evil.example","developers.facebook.com","l.facebook.com","facebookcdn.example"))
            assertNull(host,routes.recognize("https://$host/OrganicMelbourne/posts/123"))
    }

    @Test fun publicIndexCommunitiesSurviveAsDestinationsWhileEntityOnlyModeExcludesPosts() {
        val selected=routes.search(Platform.REDDIT,"gardening")
        val source=Source(Platform.REDDIT,"librey","https://index.example")
        val html=result("https://www.reddit.com/r/Gardening","Indexed community name")+
            result("https://www.reddit.com/r/gardening/","Same community")+
            result("https://www.reddit.com/user/reader","Indexed person")+
            result("https://www.reddit.com/r/gardening/comments/123/post/","Indexed post")+
            result("https://www.reddit.com/r/all","Navigation")
        val broad=Extraction.parse(Payload(html,source.root+"/search.php?q=q","text/html",200),selected,source,routes)
        assertEquals(3,broad.collections.size);assertTrue(broad.collections.any {it.target.kind==TargetKind.COMMUNITY})
        val entities=Extraction.parse(Payload(html,source.root+"/search.php?q=q","text/html",200),selected,source,routes,publicSearchEntitiesOnly=true)
        assertEquals(setOf(TargetKind.PROFILE,TargetKind.COMMUNITY),entities.collections.map {it.target.kind}.toSet())
        assertTrue(entities.posts.isEmpty());assertTrue(entities.accounts.isEmpty())
        assertTrue(entities.collections.any {it.name=="Indexed community name"})
        assertFalse(entities.collections.any {it.target.identity=="r/all"})
        val lemmy=routes.search(Platform.LEMMY,"gardening")
        val lemmyIndex=source.copy(platform=Platform.LEMMY)
        val community=Extraction.parse(Payload(result("https://lemmy.world/c/gardening"),source.root+"/search.php?q=q","text/html",200),lemmy,lemmyIndex,routes,publicSearchEntitiesOnly=true)
        assertEquals(TargetKind.COMMUNITY,community.collections.single().target.kind)
    }

    @Test fun explicitEntityDirectoryUsesOnlyOneSelectedProviderQueryAndRejectsForgedOrScopedRequests(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(result("https://www.facebook.com/OrganicMelbourne")+result("https://www.facebook.com/OrganicMelbourne/posts/123")))
            val gateway=gateway()
            try {
                val source=Source(Platform.FACEBOOK,"librey",server.url("/").toString().trimEnd('/'),true)
                val adapter=PublicSearchAdapter(source,gateway,routes)
                val page=adapter.directory(target)
                assertEquals(1,page.collections.size);assertEquals(TargetKind.PROFILE,page.collections.single().target.kind)
                assertTrue(page.posts.isEmpty());assertTrue(page.accounts.isEmpty())
                assertEquals(PublicSearchAdapter.directoryQuery(target),server.takeRequest().requestUrl!!.queryParameter("q"))
                assertEquals(1,server.requestCount)
                val checked=gateway.protectionStats().checked
                val scoped=routes.search(Platform.FACEBOOK,target.identity,SearchScope(SearchScopeKind.ACCOUNT,"OrganicMelbourne"))
                assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.directory(scoped)}}
                assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.directory(target,"public-directory:{\"version\":1,\"binding\":\"forged\",\"cursor\":\"https://attacker.example/search.php?q=stolen\"}")}}
                assertEquals(checked,gateway.protectionStats().checked)
            }finally {close(gateway)}
        }
    }
}
