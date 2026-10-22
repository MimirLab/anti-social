package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SearchTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    @Test fun providerPaginationKeepsTheQueryAndRejectsForeignForms(): Unit=runBlocking {
        val source=Source(Platform.X,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
        val target=routes.search(Platform.X,"NASA")
        val result="""<div class="result"><a class="result__a" href="https://x.com/NASA/status/123">Post</a></div>"""
        fun page(action: String="/html/",query: String="site:x.com NASA")=Extraction.parse(Payload(result+"""<form action="$action" method="post"><input type="submit" value="Next"><input type="hidden" name="q" value="$query"><input type="hidden" name="s" value="10"><input type="hidden" name="vqd" value="token"><input type="hidden" name="tracking" value="discard"></form>""",source.root+"/html/","text/html",200),target,source,routes)
        val next=requireNotNull(page().next)
        assertTrue(next.startsWith("search-form:"));assertFalse(next.contains("tracking"));assertTrue(next.contains("token"))
        assertNull(page("https://example.org/html/").next);assertNull(page(query="different query").next)
        val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})
        val adapter=PublicSearchAdapter(source,gateway,routes)
        assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(target,next.replace("html.duckduckgo.com","evil.example"))}}
        assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(routes.search(Platform.X,"different"),next)}}
        val pool=SourcePool(gateway,routes).apply {searchProvider=SearchProvider.BING}
        assertThrows(IllegalArgumentException::class.java){runBlocking {pool.continuePage(target,page())}}
        assertEquals(0,gateway.protectionStats().checked)
    }
    @Test fun continuationUsesOnlyItsIssuingFrontend(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val root=server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/124"></a><div class="tweet-content">Next real post</div></div>"""))
            val catalog=asset("darwin-routes.json");catalog.add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"$root","fallbackOnly":false}]""").asJsonArray)
            val local=DarwinRoutes(catalog,cleaner);val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val source=local.sources.single();val previous=Page(emptyList(),next="$root/search?f=tweets&q=NASA&cursor=next",provenance=Provenance(source,1,root))
            val page=SourcePool(gateway,local).continuePage(local.search(Platform.X,"NASA"),previous,false)
            assertEquals("Next real post",page.posts.single().body);assertEquals(1,server.requestCount)
            assertTrue(server.takeRequest().path!!.contains("cursor=next"))
        }
    }
    @Test fun wordsSearchWhilePrefixesAndExplicitProfileModeOpenTargets() {
        val search=routes.openInput(Platform.X," elon ",true)
        assertEquals(TargetKind.SEARCH,search.kind);assertEquals("elon",search.identity)
        assertEquals(TargetKind.SEARCH,routes.recognize(search.canonicalUrl)!!.kind)
        assertEquals("elon",DarwinRoutes.queryValue(java.net.URI(search.canonicalUrl),"q"))
        assertEquals(TargetKind.PROFILE,routes.openInput(Platform.X,"elon",false).kind)
        assertEquals("elonmusk",routes.openInput(Platform.X,"@elonmusk",true).identity)
        assertEquals(TargetKind.TAG,routes.openInput(Platform.X,"#space",true).kind)
        assertEquals(TargetKind.POST,routes.openInput(Platform.X,"https://x.com/elonmusk/status/123?s=20",true).kind)
        assertEquals("space & science",routes.openInput(Platform.BLUESKY,"space & science",true).identity)
    }
    @Test fun disabledSearchIsUnsupportedRatherThanAnEmptyHealthyPage() {
        val source=routes.sources.first {it.frontend=="nitter"};val target=routes.search(Platform.X,"elon")
        val failure=assertThrows(SourceFailure::class.java){Extraction.parse(Payload("""<title>Search disabled</title><div class="disabled-feature"><h1>Search disabled</h1><p>This feature is disabled on this instance.</p></div>""",source.root,"text/html",200),target,source,routes)}
        assertEquals(HealthStatus.UNSUPPORTED,failure.status);assertEquals("Search disabled",failure.message)
    }
    private val indexed="""<div class="text-result-wrapper"><a href="https://x.com/ElonMusk"><h2>Elon Musk (@elonmusk) / X</h2></a><span>Indexed profile snippet</span></div>
        <div class="text-result-wrapper"><a href="https://x.com/elonmusk/status/123"><h2>A matching post</h2></a><span>An incomplete indexed snippet</span></div>
        <div class="text-result-wrapper"><a href="https://example.org/unrelated"><h2>Off-site result</h2></a></div>
        <div class="text-result-wrapper"><a href="https://x.com/elonmusk/highlights"><h2>Unsupported profile subpage</h2></a></div>
        <div class="text-result-wrapper"><a href="javascript:alert(1)"><h2>Bad destination</h2></a></div>"""
    @Test fun indexResultsAreDestinationLinksWithProvenanceNotInventedPosts() {
        val source=Source(Platform.X,"librey","https://search.example.org",true)
        val page=Extraction.parse(Payload(indexed,source.root,"text/html",200),routes.search(Platform.X,"elon"),source,routes)
        assertTrue(page.posts.isEmpty());assertEquals(2,page.collections.size)
        assertEquals(listOf(TargetKind.PROFILE,TargetKind.POST),page.collections.map {it.target.kind})
        assertEquals("Indexed profile snippet",page.collections.first().detail)
        assertEquals(source,page.provenance!!.source)
        assertEquals("https://x.com/ElonMusk",page.collections.first().target.canonicalUrl)
    }
    @Test fun originalIndexRssKeepsOnlySupportedSocialLinksAndRejectsEntities() {
        val source=Source(Platform.X,"bing-rss","https://www.bing.com",true,SourceRole.ORIGINAL)
        val body="""<?xml version="1.0"?><rss><channel><title>Search results</title><item><title>Elon Musk</title><link>https://x.com/ElonMusk</link><description><![CDATA[An <b>indexed</b> profile]]></description><pubDate>Wed, 14 Oct 2026 12:00:00 GMT</pubDate></item><item><title>Off-site article</title><link>https://example.org/elon</link></item><item><title>Navigation</title><link>https://x.com/home</link></item></channel></rss>"""
        val target=routes.search(Platform.X,"elon")
        val page=Extraction.parse(Payload(body,source.root,"text/xml",200),target,source,routes)
        assertTrue(page.posts.isEmpty());assertEquals(1,page.collections.size)
        assertEquals("An indexed profile",page.collections.single().detail)
        assertEquals(TargetKind.PROFILE,page.collections.single().target.kind)
        assertEquals(source,page.provenance!!.source)
        val bad="""<!DOCTYPE rss [<!ENTITY external SYSTEM "https://example.org/private">]><rss><channel><item><title>&external;</title></item></channel></rss>"""
        assertEquals(HealthStatus.UNSUPPORTED,assertThrows(SourceFailure::class.java){Extraction.parse(Payload(bad,source.root,"text/xml",200),target,source,routes)}.status)
    }
    @Test fun originalIndexCannotBypassTheOriginalAccessSetting(): Unit=runBlocking {
        val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})
        gateway.originalFallback=false
        val source=SourcePool(gateway,routes).apply {searchProvider=SearchProvider.BING}.originalSource(Platform.X,Capability.SEARCH)
        assertEquals("bing-rss",source.frontend);assertEquals(SourceRole.ORIGINAL,source.role)
        val failure=assertThrows(java.io.IOException::class.java){runBlocking {PublicSearchAdapter(source,gateway,routes).fetch(routes.search(Platform.X,"elon"))}}
        assertTrue(failure.message!!.contains("Original-site access is disabled"));assertEquals(0,gateway.protectionStats().checked)
        gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
    }
    @Test fun searchMetadataCannotEvadeTheRecentPageMemoryBudget() {
        val memory=PageMemory(maxCharacters=2000);val target=routes.search(Platform.X,"large search result")
        val page=Page(emptyList(),collections=listOf(Collection(routes.target(Platform.X,"NASA"),"A long search result",detail="x".repeat(4000))))
        memory.put(target,page)
        assertNull(memory.get(target));assertFalse(memory.fresh(target))
    }
    @Test fun indexFailoverUsesGatewayAndKeepsSearchHealthSeparateFromTimeline(): Unit=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object: Dispatcher(){override fun dispatch(request: RecordedRequest)=when {
                request.path!!.startsWith("/mirror/")->MockResponse().setBody("""<div class="disabled-feature"><h1>Search disabled</h1></div>""")
                request.path!!.startsWith("/index/search.php")->MockResponse().setBody(indexed)
                else->MockResponse().setResponseCode(404)
            }}
            server.start()
            val catalog=asset("darwin-routes.json")
            catalog.add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"${server.url("/mirror").toString().trimEnd('/')}","fallbackOnly":false}]""").asJsonArray)
            catalog.add("searchSources",JsonParser.parseString("""[{"platform":"twitter","frontend":"librey","root":"${server.url("/index").toString().trimEnd('/')}","fallbackOnly":true}]""").asJsonArray)
            val local=DarwinRoutes(catalog,cleaner)
            val checked=java.util.concurrent.CopyOnWriteArrayList<String>()
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {checked+=url;return FilterDecision(false)}},true)
            gateway.originalFallback=false
            val pool=SourcePool(gateway,local).apply {searchProvider=SearchProvider.DARWIN}
            val page=withTimeout(5000){pool.load(local.search(Platform.X,"elon"),originalFallback=false)}
            assertTrue(page.posts.isEmpty());assertEquals(2,page.collections.size)
            assertEquals("librey",page.provenance!!.source.frontend)
            assertTrue(checked.any {it.contains("/index/search.php?q=site%3Ax.com%20elon")||it.contains("/index/search.php?q=site%3Ax.com+elon")})
            assertTrue(checked.none {java.net.URI(it).host=="x.com"})
            assertEquals(HealthStatus.UNSUPPORTED,pool.results(Platform.X).first {it.source.frontend=="nitter"}.status)
            assertEquals(HealthStatus.HEALTHY,pool.results(Platform.X).first {it.source.frontend=="librey"}.status)
            assertTrue(pool.results(Platform.X).all {it.capability==Capability.SEARCH})
            assertEquals(1,pool.sources(Platform.X,Capability.TIMELINE).size)
            gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
        }
    }
    @Test fun selectedProviderDoesNotSendTheQueryToOtherIndexes() {
        val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})
        val pool=SourcePool(gateway,routes)
        for(provider in SearchProvider.entries) {
            pool.searchProvider=provider
            val sources=pool.sources(Platform.X,Capability.SEARCH,true)
            val indexes=sources.filter {it.frontend in PublicSearchAdapter.frontends}
            if(provider==SearchProvider.DARWIN)assertTrue(indexes.isNotEmpty()&&indexes.all {it.frontend=="librey"})
            else {assertEquals(listOf(provider.frontend),indexes.map {it.frontend});assertTrue(pool.sources(Platform.X,Capability.SEARCH,false).none {it.frontend in PublicSearchAdapter.frontends})}
        }
    }
    @Test fun duckDuckGoDecodesLinksWithoutFetchingTrackersAndReportsChallenges() {
        val source=Source(Platform.X,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
        val target=routes.search(Platform.X,"NASA")
        val body="""<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fx.com%2FNASA&amp;rut=tracking">NASA / X</a><div class="result__snippet">Public profile</div></div><div class="result result--ad"><a class="result__a" href="https://x.com/ad">Advertisement</a></div><div class="result"><a class="result__a" href="https://example.org/offsite">Other website</a></div>"""
        val page=Extraction.parse(Payload(body,source.root+"/html/","text/html",200),target,source,routes)
        assertEquals(1,page.collections.size);assertTrue(page.posts.isEmpty());assertEquals("https://x.com/NASA",page.collections.single().target.canonicalUrl);assertEquals("Public profile",page.collections.single().detail)
        val challenge="""<form id="challenge-form"><p>Unfortunately, bots use DuckDuckGo too. Select all squares containing a duck.</p></form>"""
        assertEquals(HealthStatus.CHALLENGE,assertThrows(SourceFailure::class.java){Extraction.parse(Payload(challenge,source.root,"text/html",200),target,source,routes)}.status)
        assertEquals(HealthStatus.CHALLENGE,assertThrows(SourceFailure::class.java){Extraction.parse(Payload(challenge+body,source.root,"text/html",200),target,source,routes)}.status)
        val error="""<h1>Oops...</h1><p>The server encountered an error and could not complete your request.</p>"""
        assertEquals(HealthStatus.UNAVAILABLE,assertThrows(SourceFailure::class.java){Extraction.parse(Payload(error,source.root,"text/html",200),target,source,routes)}.status)
    }
    @Test fun selectiveSearchExtractionBoundsCardsAndKeepsQuotedErrorText() {
        val source=Source(Platform.X,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
        val body=(0..40).joinToString(""){index->"""<div class="result"><div class="result__a">Not a link</div><a class="result__a" href="https://x.com/account$index">Profile $index</a><div class="result__snippet">Discussing bots use DuckDuckGo and The server encountered an error.</div></div>"""}
        val page=Extraction.parse(Payload(body,source.root,"text/html",200),routes.search(Platform.X,"privacy"),source,routes)
        assertEquals(30,page.collections.size);assertEquals("https://x.com/account29",page.collections.last().target.canonicalUrl)
        assertTrue(page.collections.first().detail.contains("The server encountered an error"));assertTrue(page.posts.isEmpty())
    }
    @Test fun indexedMirrorHostsStillRespectPathsPortsAndSchemes() {
        val catalog=asset("darwin-routes.json")
        catalog.add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"https://mirror.example/x","fallbackOnly":false},{"platform":"reddit","frontend":"redlib","root":"https://mirror.example/r","fallbackOnly":false},{"platform":"twitter","frontend":"nitter","root":"https://mirror.example:8443/x","fallbackOnly":false}]""").asJsonArray)
        val local=DarwinRoutes(catalog,cleaner)
        assertEquals("https://x.com/NASA",local.recognize("https://MIRROR.example/x/NASA")!!.canonicalUrl)
        assertEquals(Platform.REDDIT,local.recognize("https://mirror.example/r/r/Android")!!.platform)
        assertEquals("https://x.com/NASA",local.recognize("https://mirror.example:8443/x/NASA")!!.canonicalUrl)
        assertNull(local.recognize("http://mirror.example/x/NASA"))
        assertNull(local.recognize("https://mirror.example/xish/NASA"))
        assertNull(local.recognize("https://mirror.example.evil/x/NASA"))
    }
    @Test fun failedSearchFrontendsHaveACooldownAndCanRecover(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/123"></a><div class="tweet-content">Recovered search result</div></div>"""))
            val catalog=asset("darwin-routes.json")
            catalog.add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"${server.url("/").toString().trimEnd('/')}","fallbackOnly":false}]""").asJsonArray)
            catalog.add("searchSources",com.google.gson.JsonArray())
            val local=DarwinRoutes(catalog,cleaner)
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val pool=SourcePool(gateway,local)
            suspend fun search(word: String){try {pool.load(local.search(Platform.X,word),originalFallback=false);fail("The failed frontend must not appear healthy")}catch(_: SourceFailure){}}
            search("first");assertEquals(1,server.requestCount)
            search("second");assertEquals("Immediate repeat searches should reach the index without retrying a failed frontend",1,server.requestCount)
            pool.restore(pool.results(Platform.X).map {it.copy(checkedAt=System.currentTimeMillis()-61_000)})
            search("third");assertEquals("An explicit refusal should not delay every search after one minute",1,server.requestCount)
            pool.restore(pool.results(Platform.X).map {it.copy(checkedAt=System.currentTimeMillis()-601_000)})
            val recovered=pool.load(local.search(Platform.X,"fourth"),originalFallback=false)
            assertEquals(2,server.requestCount);assertEquals("Recovered search result",recovered.posts.single().body)
            assertEquals(HealthStatus.HEALTHY,pool.results(Platform.X).single().status)
        }
    }
    @Test fun explicitSourceTestingCanRecoverACachedDnsFailure(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            val content="""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/123"></a><div class="tweet-content">Recovered source</div></div>"""
            repeat(2){server.enqueue(MockResponse().setBody(content))}
            val catalog=asset("darwin-routes.json")
            catalog.add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"${server.url("/").toString().trimEnd('/')}","fallbackOnly":false}]""").asJsonArray)
            catalog.add("searchSources",com.google.gson.JsonArray())
            val local=DarwinRoutes(catalog,cleaner)
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val pool=SourcePool(gateway,local);val source=local.sources.single()
            pool.restore(listOf(ProbeResult(source,Capability.SEARCH,HealthStatus.UNAVAILABLE,0,System.currentTimeMillis()-120_000,false,"Unable to resolve host fixture")))
            assertThrows(SourceFailure::class.java){runBlocking {pool.load(local.search(Platform.X,"test"),originalFallback=false)}}
            assertEquals(0,server.requestCount)
            assertEquals(HealthStatus.HEALTHY,pool.probe(source,Capability.SEARCH,force=true).status)
            assertEquals("Recovered source",pool.load(local.search(Platform.X,"test"),originalFallback=false).posts.single().body)
            assertEquals(2,server.requestCount)
        }
    }
}
