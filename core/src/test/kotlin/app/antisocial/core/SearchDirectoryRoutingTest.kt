package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SearchDirectoryRoutingTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private fun routes(platform: Platform?=null,frontend: String="",root: String=""): DarwinRoutes {
        val catalog=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject
        catalog.add("sources",if(platform==null)JsonArray()else JsonParser.parseString("""[{"platform":"${platform.id}","frontend":"$frontend","root":"$root","fallbackOnly":false}]""").asJsonArray)
        catalog.add("searchSources",JsonArray())
        return DarwinRoutes(catalog,cleaner)
    }
    private fun gateway(rewrite: (String)->String?={null})=NetworkGateway(cleaner,object: RequestFilter {
        override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl=rewrite(url))
    },true)

    @Test fun genuineFacebookEntitiesDoNotConsumePostCursorsOrCertifyPostHealth(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            val fixture=JsonParser.parseReader(javaClass.getResourceAsStream("/facebook-directory-results.json")!!.reader()).toString()
            repeat(2){server.enqueue(MockResponse().setBody(fixture))}
            val routes=routes();val gateway=gateway {url->if(url.startsWith("https://www.facebook.com/api/graphql/"))server.url("/api/graphql/").toString()else null}
            val pool=SourcePool(gateway,routes);val target=routes.search(Platform.FACEBOOK,"melbourne organic food")
            val page=pool.searchDirectory(target)
            assertEquals(3,page.accounts.size);assertTrue(page.posts.isEmpty());assertNull(page.next)
            assertEquals(setOf("Local Growers","Organic Market","Example Person"),page.accounts.map {it.name}.toSet())
            assertTrue(page.accounts.all {it.target.kind==TargetKind.PROFILE&&it.target.platform==Platform.FACEBOOK})
            assertTrue("Directory success is not proof of a working post-search route",pool.results(Platform.FACEBOOK).isEmpty())
            assertFalse(pool.hasActiveReads);assertEquals(2,server.requestCount)
            repeat(2){val request=server.takeRequest();assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"));assertTrue(request.body.readUtf8().contains("melbourne"))}
            gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
        }
    }

    @Test fun aRedlibDirectoryDeliversRealSubredditMetadataBeforeOriginalFallback(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("""<div id="search_subreddits"><a class="search_subreddit" href="/mounted/r/Organic"><div class="search_subreddit_right"><p class="search_subreddit_header"><span class="search_subreddit_name">r/Organic</span></p><p class="search_subreddit_description">Public organic growing community</p></div></a><a id="more_subreddits" class="search_subreddit" href="?q=organic&type=sr_user">More subreddit results</a></div>"""))
            val routes=routes(Platform.REDDIT,"redlib",server.url("/mounted").toString().trimEnd('/'));val gateway=gateway();val pool=SourcePool(gateway,routes)
            val page=pool.searchDirectory(routes.search(Platform.REDDIT,"organic"),originalFallback=false)
            val community=page.accounts.single();assertEquals(TargetKind.COMMUNITY,community.target.kind)
            assertEquals("https://www.reddit.com/r/Organic",community.target.canonicalUrl.trimEnd('/'))
            assertEquals("Public organic growing community",community.description)
            assertEquals(1,server.requestCount);val request=server.takeRequest();assertEquals("/mounted/search",request.requestUrl!!.encodedPath);assertEquals("sr_user",request.requestUrl!!.queryParameter("type"));assertEquals("organic",request.requestUrl!!.queryParameter("q"))
            assertTrue(pool.results(Platform.REDDIT).isEmpty());assertFalse(pool.hasActiveReads)
            gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
        }
    }

    @Test fun scopedSearchAndDisabledOriginalFallbackDoNotLaunchAnUnrelatedDirectory(): Unit=runBlocking {
        val requested=CopyOnWriteArrayList<String>();val routes=routes();val gateway=gateway {requested+=it;null};val pool=SourcePool(gateway,routes)
        val target=routes.search(Platform.FACEBOOK,"organic")
        assertTrue(pool.searchDirectory(target,originalFallback=false).accounts.isEmpty())
        val scope=SearchScope(SearchScopeKind.COMMUNITY,"Android")
        val scoped=routes.search(Platform.REDDIT,"battery",scope)
        assertTrue(pool.searchDirectory(scoped).accounts.isEmpty())
        assertTrue(requested.isEmpty());assertFalse(pool.hasActiveReads)
    }

    @Test fun redditDirectoryHydrationReadsOnlyTheSelectedAboutRecord(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("""{"kind":"t5","data":{"display_name":"Organic","title":"Organic growers","public_description":"Actual community metadata","url":"/r/Organic/"}}"""))
            val routes=routes();val gateway=gateway {url->if(url.startsWith("https://www.reddit.com/r/Organic/about.json"))server.url("/r/Organic/about.json?raw_json=1").toString()else null}
            val source=Source(Platform.REDDIT,"original",Platform.REDDIT.origin,role=SourceRole.ORIGINAL)
            val target=routes.recognize("https://www.reddit.com/r/Organic/")!!
            val result=OriginalAdapter(source,gateway,routes).profile(target)
            assertEquals(target,result.profile!!.target);assertEquals("Actual community metadata",result.profile!!.description)
            assertTrue(result.posts.isEmpty());assertEquals(1,server.requestCount);assertEquals("/r/Organic/about.json",server.takeRequest().requestUrl!!.encodedPath)
            gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
        }
    }

    @Test fun anUnavailableDirectoryBacksOffWithoutDisablingPostSearch(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(503))
            val routes=routes(Platform.REDDIT,"redlib",server.url("/").toString().trimEnd('/'));val gateway=gateway();val pool=SourcePool(gateway,routes)
            assertTrue(pool.searchDirectory(routes.search(Platform.REDDIT,"organic"),originalFallback=false).accounts.isEmpty())
            assertTrue(pool.searchDirectory(routes.search(Platform.REDDIT,"gardening"),originalFallback=false).accounts.isEmpty())
            assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody("""<div class="post"><a class="post_title" href="https://www.reddit.com/r/Organic/comments/abc123/seed">A real post</a><div class="post_body">Growing food</div></div>"""))
            // Directory backoff is isolated from ordinary post-search health.
            assertTrue(pool.results(Platform.REDDIT).isEmpty())
            val post=pool.load(routes.search(Platform.REDDIT,"gardening"),originalFallback=false).posts.single()
            assertEquals("Growing food",post.body);assertEquals(2,server.requestCount)
            assertEquals(HealthStatus.HEALTHY,pool.results(Platform.REDDIT).single().status)
            assertFalse(pool.hasActiveReads)
            gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
        }
    }
}
