package app.antisocial.core

import com.google.gson.JsonParser
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Integration boundaries: discovered links and source reachability cannot widen a scope. */
class SearchScopeIntegrationTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private fun scope(platform: Platform,kind: SearchScopeKind,value: String,query: String="space")=routes.search(platform,query,SearchScopes.normalize(platform,kind,value))
    private fun redditHtml(community: String,id: String="abc")="""<div class="post"><a class="post_title" href="https://www.reddit.com/r/$community/comments/$id/real_post/">Real $community post</a><div class="post_body">Actual space story</div><span class="post_author">Reader</span><time datetime="2026-10-16T12:00:00Z"></time></div>"""
    private fun close(gateway: NetworkGateway) {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
    private fun localRoutes(server: MockWebServer): DarwinRoutes {
        val data=asset("darwin-routes.json")
        data.add("sources",JsonParser.parseString("""[{"platform":"reddit","frontend":"redlib","root":"${server.url("/").toString().trimEnd('/')}","fallbackOnly":false}]""").asJsonArray)
        data.add("searchSources",JsonParser.parseString("[]").asJsonArray)
        return DarwinRoutes(data,cleaner)
    }
    @Test fun wrongSubredditCannotMarkASourceHealthyOrPublishARead(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(redditHtml("other")))
            val local=localRoutes(server)
            val checked=mutableListOf<String>()
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {checked+=url;return FilterDecision(false)}},true)
            try {
                val pool=SourcePool(gateway,local).apply {searchProvider=SearchProvider.DARWIN}
                var published=0;pool.onRead={_,_,_->published++}
                val target=local.search(Platform.REDDIT,"space",SearchScopes.normalize(Platform.REDDIT,SearchScopeKind.COMMUNITY,"android"))
                assertThrows(SourceFailure::class.java){runBlocking {withTimeout(5000){pool.load(target,originalFallback=false)}}}
                assertEquals(1,server.requestCount);assertEquals(0,published)
                assertEquals(HealthStatus.EMPTY,pool.results(Platform.REDDIT).single().status)
                assertTrue(checked.single().contains("/r/android/search?"));assertFalse(checked.single().contains("antisocial-scope"))
            }finally {close(gateway)}
        }
    }
    @Test fun aMixedSourceReturnsOnlyTheSelectedSubredditBeforePublishingHealth(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(redditHtml("other","xyz")+redditHtml("Android")))
            val local=localRoutes(server)
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            try {
                val pool=SourcePool(gateway,local).apply {searchProvider=SearchProvider.DARWIN}
                val target=local.search(Platform.REDDIT,"space",SearchScopes.normalize(Platform.REDDIT,SearchScopeKind.COMMUNITY,"android"))
                val page=withTimeout(5000){pool.load(target,originalFallback=false)}
                assertEquals(listOf("Real Android post"),page.posts.map {it.title})
                assertEquals(HealthStatus.HEALTHY,pool.results(Platform.REDDIT).single().status)
                val request=server.takeRequest().requestUrl!!
                assertEquals("/r/android/search",request.encodedPath)
                assertEquals("on",request.queryParameter("restrict_sr"));assertEquals("space",request.queryParameter("q"))
            }finally {close(gateway)}
        }
    }
    @Test fun originalRedditJsonAndRssCarryTheSameRestrictedSearch(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"id":"abc","permalink":"/r/android/comments/abc/real_post/","author":"reader","title":"Real post","selftext":"Space story","created_utc":1792152000}}]}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type","application/rss+xml").setBody("""<rss><channel><item><title>Real post</title><link>https://www.reddit.com/r/android/comments/abc/real_post/</link><description>Space story</description><pubDate>Fri, 16 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""))
            val originalRequests=mutableListOf<String>()
            val gateway=NetworkGateway(cleaner,object: RequestFilter {
                override fun check(url: String,sourceUrl: String,type: String): FilterDecision {
                    val u=URI(url)
                    if(u.host==server.hostName&&u.port==server.port)return FilterDecision(false)
                    originalRequests+=url
                    return FilterDecision(false,rewrittenUrl=server.url(u.rawPath+u.rawQuery?.let {"?$it"}.orEmpty()).toString())
                }
            },true)
            try {
                val target=scope(Platform.REDDIT,SearchScopeKind.COMMUNITY,"android","space & science")
                val adapter=OriginalAdapter(Source(Platform.REDDIT,"original",Platform.REDDIT.origin,role=SourceRole.ORIGINAL),gateway,routes)
                assertEquals(1,adapter.fetch(target).posts.size)
                assertEquals(1,adapter.feed(target).posts.size)
                assertEquals(listOf("/r/android/search.json","/r/android/search.rss"),originalRequests.map {URI(it).path})
                repeat(2) {
                    val request=server.takeRequest().requestUrl!!
                    assertEquals("space & science",request.queryParameter("q"));assertEquals("on",request.queryParameter("restrict_sr"));assertEquals("new",request.queryParameter("sort"))
                    assertNull(request.fragment)
                }
            }finally {close(gateway)}
        }
    }
    @Test fun frontendConversionRetainsNativeScopeAndKeepsLocalMetadataOffTheWire() {
        val reddit=scope(Platform.REDDIT,SearchScopeKind.COMMUNITY,"android","space & science")
        val source=Source(Platform.REDDIT,"redlib","https://reader.example")
        val converted=URI(routes.convert(reddit,source))
        assertEquals("reader.example",converted.host);assertEquals("/r/android/search",converted.path)
        assertEquals("on",DarwinRoutes.queryValue(converted,"restrict_sr"));assertEquals("space & science",DarwinRoutes.queryValue(converted,"q"));assertNull(converted.fragment)
        val x=scope(Platform.X,SearchScopeKind.ACCOUNT,"nasa","space & science")
        val nitter=URI(routes.convert(x,Source(Platform.X,"nitter","https://nitter.example")))
        assertEquals("from:nasa (space & science)",DarwinRoutes.queryValue(nitter,"q"))
        assertFalse(nitter.toString().contains("antisocial-scope"))
    }
    @Test fun hydratedSearchCandidatesCannotEscapeTheSelectedAccountOrTag(): Unit=runBlocking {
        val nasa=scope(Platform.X,SearchScopeKind.ACCOUNT,"nasa")
        val wrong=routes.recognize("https://x.com/other/status/123")!!
        val session=SearchFeed(nasa,discover={Page(emptyList(),collections=listOf(Collection(wrong,"A misleading indexed result")))},read={Page(listOf(Post(wrong.canonicalUrl,Platform.X,wrong.canonicalUrl,"nasa","Real but off-scope text")))})
        session.loadNext{}
        assertTrue(session.state.page.posts.isEmpty())
        val tagged=scope(Platform.X,SearchScopeKind.TAG,"space")
        val destination=routes.recognize("https://x.com/nasa/status/124")!!
        val noTag=SearchFeed(tagged,discover={Page(emptyList(),collections=listOf(Collection(destination,"#space indexed text")))},read={Page(listOf(Post(destination.canonicalUrl,Platform.X,destination.canonicalUrl,"NASA","The post itself has no hashtag")))})
        noTag.loadNext{}
        assertTrue(noTag.state.page.posts.isEmpty())
    }
    @Test fun scopedFeedSearchCanFindMatchesAfterTwoEmptyDiscoveryBlocks(): Unit=runBlocking {
        var reads=0
        val target=scope(Platform.PINTEREST,SearchScopeKind.BOARD,"nasa/space","needle")
        val feed=ScopedFeedSearch("needle",load={
            reads++
            Page(listOf(Post("$reads",Platform.PINTEREST,"https://www.pinterest.com/pin/$reads/","NASA",if(reads==7)"Found the needle"else "Unrelated public entry $reads",publishedAt=reads.toLong())),next=if(reads<7)"cursor-$reads"else null)
        })
        val search=SearchFeed(target,discover=feed::next,read={error("Posts are already native")},allowEmptyDiscoveryPages={true})
        repeat(3){search.loadNext{}}
        assertEquals(7,reads);assertEquals(listOf("Found the needle"),search.state.page.posts.map {it.body});assertFalse(search.state.hasMore)
        assertTrue(search.state.page.notice!!.contains("available public posts"))
    }
    @Test fun emptyScopedFeedsStillStopAtTheReadBudget(): Unit=runBlocking {
        var reads=0
        val target=scope(Platform.PINTEREST,SearchScopeKind.BOARD,"nasa/space","needle")
        val feed=ScopedFeedSearch("needle",load={reads++;Page(listOf(Post("$reads",Platform.PINTEREST,"https://www.pinterest.com/pin/$reads/","NASA","Unrelated entry $reads")),next="cursor-$reads")},maxPages=7)
        val search=SearchFeed(target,discover=feed::next,read={error("No hydration")},allowEmptyDiscoveryPages={true})
        repeat(5){search.loadNext{}}
        assertEquals(7,reads);assertFalse(search.state.hasMore);assertTrue(search.state.page.posts.isEmpty())
        assertTrue(search.state.page.notice!!.contains("reading limit"))
    }
    @Test fun memoryAndRoutingFailuresKeepIdenticalQueriesInDifferentScopesApart() {
        val everywhere=routes.search(Platform.REDDIT,"space")
        val android=scope(Platform.REDDIT,SearchScopeKind.COMMUNITY,"android")
        val linux=scope(Platform.REDDIT,SearchScopeKind.COMMUNITY,"linux")
        val page=Page(listOf(Post("abc",Platform.REDDIT,"https://www.reddit.com/r/android/comments/abc/title/","Reader","Android post")))
        val memory=PageMemory();memory.put(android,page)
        assertEquals(page,memory.get(android));assertNull(memory.get(linux));assertNull(memory.get(everywhere))
        val now=1792152000000L;val source=Source(Platform.REDDIT,"redlib","https://reader.example")
        val learning=AdaptiveRouting{now}
        learning.observe(ProbeResult(source,Capability.SEARCH,HealthStatus.EMPTY,10,now,false,"No scoped results"),android)
        assertFalse(learning.eligible(source,Capability.SEARCH,false,android))
        assertTrue(learning.eligible(source,Capability.SEARCH,false,linux));assertTrue(learning.eligible(source,Capability.SEARCH,false,everywhere))
        assertNull(learning.failure(source,Capability.SEARCH,false,linux));assertNull(learning.failure(source,Capability.SEARCH,false,everywhere))
    }
}
