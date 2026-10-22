package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlatformTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    @Test fun everyPlatformHasScopedDiscoveryAndPreservesSearchMeaning() {
        val pool=SourcePool(NetworkGateway(cleaner,permit),routes)
        for(platform in Platform.entries.filterNot {it==Platform.RSS}) {
            val target=routes.search(platform,"space & science")
            assertEquals(target.identity,routes.recognize(target.canonicalUrl)!!.identity)
            assertTrue(PublicSearchAdapter.query(target).startsWith("site:${platform.searchDomain} "));assertTrue(PublicSearchAdapter.query(target).endsWith("space & science"))
            for(provider in listOf(SearchProvider.DUCKDUCKGO,SearchProvider.BING)) {
                pool.searchProvider=provider
                assertEquals(listOf(provider.frontend),pool.sources(platform,Capability.SEARCH,true).filter {it.frontend in PublicSearchAdapter.frontends}.map {it.frontend})
                assertTrue(pool.sources(platform,Capability.SEARCH,false).none {it.frontend in PublicSearchAdapter.frontends})
            }
            pool.searchProvider=SearchProvider.DARWIN
            assertTrue(pool.sources(platform,Capability.SEARCH).any {it.frontend=="librey"&&it.platform==platform})
            assertTrue(pool.sources(platform,Capability.TIMELINE).none {it.frontend in PublicSearchAdapter.frontends})
        }
        assertEquals("https://www.threads.com/@nasa/post/ABC",routes.recognize("https://www.threads.net/@nasa/post/ABC")!!.canonicalUrl)
    }
    @Test fun navigationPagesDoNotBecomeNativeAccountCards() {
        for((platform,url) in listOf(Platform.TIKTOK to "https://www.tiktok.com/explore",Platform.INSTAGRAM to "https://www.instagram.com/accounts",Platform.PINTEREST to "https://www.pinterest.com/business",Platform.THREADS to "https://www.threads.com/login")) {
            val page=PublicSearchAdapter.duckDuckGo(org.jsoup.Jsoup.parse("""<div class="result"><a class="result__a" href="$url">Not a person</a></div>""","https://html.duckduckgo.com/html/"),routes.search(platform,"space"),routes)
            assertTrue(page.collections.isEmpty())
        }
    }
    @Test fun onlyAnonymousBookmarkIsCapturedAndNoCookieJarIsEnabled(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("one").addHeader("Set-Cookie","bookmark=opaque_cursor%3D; Path=/").addHeader("Set-Cookie","session=account; Path=/").addHeader("Set-Cookie","csrftoken=unwanted; Path=/"))
            server.enqueue(MockResponse().setBody("two"));server.enqueue(MockResponse().setBody("ordinary"))
            val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/search/pins/?q=space").toString()
            val first=gateway.pagedText(url,url);assertEquals("opaque_cursor%3D",first.pageBookmark)
            assertNull(server.takeRequest(1,TimeUnit.SECONDS)!!.getHeader("Cookie"))
            gateway.pagedText(url+"&next",url,first.pageBookmark)
            val next=server.takeRequest(1,TimeUnit.SECONDS)!!;assertEquals("bookmark=opaque_cursor%3D",next.getHeader("Cookie"));assertNull(next.getHeader("Authorization"))
            gateway.text(url,headers=mapOf("Cookie" to "session=must-not-leak"));assertNull(server.takeRequest(1,TimeUnit.SECONDS)!!.getHeader("Cookie"))
            assertThrows(IllegalArgumentException::class.java){runBlocking {gateway.pagedText(url,url,"cursor; session=inject")}}
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun bookmarksCannotFollowRedirectsToAnotherHostOrEndpoint(): Unit=runBlocking {
        MockWebServer().use {server->MockWebServer().use {other->
            server.start();other.start();val gateway=NetworkGateway(cleaner,permit,true);val url=server.url("/search/pins/?q=space").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/leak")))
            assertThrows(java.io.IOException::class.java){runBlocking {gateway.pagedText(url,url,"cursor")}};assertEquals(0,other.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/other"))
            assertThrows(java.io.IOException::class.java){runBlocking {gateway.pagedText(url,url,"cursor")}};assertEquals(2,server.requestCount)
        }}
    }
    @Test fun redditTumblrAndTiebaExposeTheirActualNextLinks() {
        for((platform,frontend,link) in listOf(Triple(Platform.REDDIT,"redlib","<a accesskey='N' href='/search?q=space&amp;after=next'>NEXT</a>"),Triple(Platform.TUMBLR,"priviblur","<a class='primary next-page button' href='/search/space?continuation=token'>Next</a>"),Triple(Platform.TIEBA,"ratAintTieba","<div class='paginator'><a>1</a><a href='/f?kw=space&amp;pn=2'>2</a><a href='/f?kw=space&amp;pn=99'>Last</a></div>"))) {
            val source=routes.sources.first {it.frontend==frontend};val target=routes.search(platform,"space")
            val page=Extraction.html(link,target,Provenance(source,1,source.root+"/search?q=space"),routes)
            assertNotNull(page.next);assertTrue(page.next!!.startsWith(source.root))
            if(platform==Platform.TIEBA)assertTrue(page.next!!.contains("pn=2"))
        }
    }
    @Test fun redditAfterAndNamespacedFeedContinuationsAreRetained() {
        val source=Source(Platform.REDDIT,"original",Platform.REDDIT.origin,role=SourceRole.ORIGINAL);val target=routes.search(Platform.REDDIT,"space")
        val listing=Extraction.json(JsonParser.parseString("""{"data":{"children":[],"after":"t3_next"}}"""),target,Provenance(source,1,target.canonicalUrl),routes)
        assertEquals("t3_next",listing.next)
        val rss=Extraction.rss("""<feed xmlns="http://www.w3.org/2005/Atom"><link rel="next" href="https://www.reddit.com/search.rss?q=space&amp;after=t3_next"/><entry><title>A post</title><id>one</id><link href="https://www.reddit.com/r/space/comments/abc/title"/><content>Body</content><updated>2026-10-16T12:00:00Z</updated></entry></feed>""",target,Provenance(source,1,target.canonicalUrl),routes)
        assertTrue(rss.next!!.contains("after=t3_next"));assertEquals(1,rss.posts.size)
    }
    @Test fun redditRssFallbackKeepsTheSearchQuery(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("""<rss><channel><item><title>Space</title><link>https://www.reddit.com/r/space/comments/abc/title</link><description>Body</description></item></channel></rss>"""))
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl=server.url(url.toHttpUrlOrNull()!!.encodedPath+"?"+url.toHttpUrlOrNull()!!.encodedQuery).toString())},true)
            val target=routes.search(Platform.REDDIT,"space & science");val page=OriginalAdapter(Source(Platform.REDDIT,"original",Platform.REDDIT.origin,role=SourceRole.ORIGINAL),gateway,routes).feed(target)
            assertEquals(1,page.posts.size);val request=server.takeRequest();assertEquals("/search.rss",request.requestUrl!!.encodedPath);assertEquals("space & science",request.requestUrl!!.queryParameter("q"))
        }
    }
    @Test fun embeddedTikTokProfileMetadataIsRetainedWithoutInventingATimeline() {
        val target=routes.target(Platform.TIKTOK,"nasa");val source=Source(Platform.TIKTOK,"original",Platform.TIKTOK.origin,role=SourceRole.ORIGINAL)
        val page=Extraction.parse(Payload("""<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">{"__DEFAULT_SCOPE__":{"webapp.user-detail":{"userInfo":{"user":{"uniqueId":"nasa","nickname":"NASA","signature":"Exploring space","avatarMedium":"https://example.org/avatar.png"}}}}}</script>""",target.canonicalUrl,"text/html",200),target,source,routes)
        assertEquals("NASA",page.profile!!.name);assertEquals("Exploring space",page.profile!!.description);assertTrue(page.posts.isEmpty())
    }
    @Test fun metadataPreviewsRequireTheRequestedPlatformAndIdentity() {
        val target=routes.recognize("https://x.com/NASA/status/123")!!;val source=Source(Platform.X,"original",Platform.X.origin,role=SourceRole.ORIGINAL)
        fun payload(url: String)=Payload("""<meta property="og:url" content="$url"><meta property="og:type" content="article"><meta property="og:title" content="Preview"><meta property="og:description" content="Public excerpt"><meta property="og:image" content="https://example.org/image.png">""",target.canonicalUrl,"text/html",200)
        assertTrue(Extraction.parse(payload(target.canonicalUrl),target,source,routes).posts.single().preview)
        for(url in listOf("https://x.com/NASA/status/456","https://www.instagram.com/p/123"))assertThrows(SourceFailure::class.java){Extraction.parse(payload(url),target,source,routes)}
    }
    @Test fun blueskyDidLinksKeepTheSelectedIdentityAfterHandleResolution(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("""{"did":"did:plc:example","handle":"current.example"}"""))
            server.enqueue(MockResponse().setBody("""{"thread":{"post":{"uri":"at://did:plc:example/app.bsky.feed.post/abc","author":{"handle":"current.example"},"record":{"text":"Selected post","createdAt":"2026-10-16T12:00:00Z"}}}}"""))
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl=server.url("/").toString())},true)
            val target=routes.recognize("https://bsky.app/profile/did:plc:example/post/abc")!!
            val page=OriginalAdapter(Source(Platform.BLUESKY,"original",Platform.BLUESKY.origin,role=SourceRole.ORIGINAL),gateway,routes).fetch(target)
            assertEquals(target.canonicalUrl,page.posts.single().canonicalUrl);assertEquals("Selected post",page.posts.single().body)
        }
    }
    @Test fun nativeProfilesPopulateSearchAccountCardsOnEveryPlatform(): Unit=runBlocking {
        for((platform,url) in listOf(Platform.BLUESKY to "https://bsky.app/profile/stsci.edu",Platform.INSTAGRAM to "https://www.instagram.com/s3space",Platform.TIKTOK to "https://www.tiktok.com/@nasa",Platform.THREADS to "https://www.threads.com/@nasa")) {
            val destination=routes.recognize(url)!!;val target=routes.search(platform,"space")
            val feed=SearchFeed(target,discover={Page(emptyList(),collections=listOf(Collection(destination,"Indexed title")))},read={Page(emptyList(),profile=Profile(destination,"Actual profile"))})
            feed.loadNext {};assertEquals(platform.label,1,feed.state.page.accounts.size)
        }
    }
    @Test fun profilePostDiscoveryRejectsOtherAuthorsAndUnrelatedReplies(): Unit=runBlocking {
        val profile=routes.target(Platform.THREADS,"nasa");val mine=routes.recognize("https://www.threads.com/@nasa/post/123")!!;val other=routes.recognize("https://www.threads.com/@someone/post/456")!!
        val post=Post("123",Platform.THREADS,mine.canonicalUrl,"nasa","Actual post",publishedAt=1)
        val raw=Page(emptyList(),collections=listOf(Collection(mine,"Index excerpt"),Collection(other,"Wrong author")))
        val requested=mutableListOf<Target>()
        val page=ProfilePosts.resolve(profile,Page(emptyList(),profile=Profile(profile,"NASA")),raw,routes){target->requested+=target;Page(listOf(post,post.copy(id="reply",canonicalUrl=other.canonicalUrl)))}
        assertEquals(listOf(mine),requested);assertEquals(listOf(post),page.posts);assertEquals("NASA",page.profile!!.name)
        assertTrue(ProfilePosts.search(routes,profile).identity.contains("site:threads.com/@nasa/post/"));assertNotNull(page.notice)
    }
    @Test fun failedProfilePostReadsNeverBecomeSyntheticSnippets(): Unit=runBlocking {
        val profile=routes.target(Platform.TIKTOK,"nasa");val post=routes.recognize("https://www.tiktok.com/@nasa/video/123")!!
        val initial=Page(emptyList(),profile=Profile(profile,"NASA"))
        val page=ProfilePosts.resolve(profile,initial,Page(emptyList(),collections=listOf(Collection(post,"This must not be a post"))),routes){error("Unavailable")}
        assertTrue(page.posts.isEmpty());assertEquals("NASA",page.profile!!.name);assertTrue(page.notice!!.contains("did not supply"))
    }
    @Test fun aTransientIndexErrorRetriesOnlyTheSameProviderFormOnce(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("<h1>The server encountered an error</h1>"))
            server.enqueue(MockResponse().setBody("""<div class="result"><a class="result__a" href="https://x.com/NASA/status/123">Post</a></div>"""))
            val gateway=NetworkGateway(cleaner,permit,true);val source=Source(Platform.X,"duckduckgo",server.url("/").toString().trimEnd('/'),true,SourceRole.ORIGINAL)
            assertEquals(1,PublicSearchAdapter(source,gateway,routes).fetch(routes.search(Platform.X,"NASA")).collections.size)
            assertEquals("GET",server.takeRequest().method);val retry=server.takeRequest();assertEquals("POST",retry.method);assertEquals("/html/",retry.path);assertTrue(retry.body.readUtf8().contains("q=site%3Ax.com%20NASA"));assertEquals(2,server.requestCount)
            server.enqueue(MockResponse().setBody("<form id='challenge-form'>A challenge</form>"))
            assertThrows(SourceFailure::class.java){runBlocking {PublicSearchAdapter(source,gateway,routes).fetch(routes.search(Platform.X,"NASA"))}}
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun publicBlueskyAccountSearchSurvivesDeniedPostSearch(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(403));server.enqueue(MockResponse().setBody("""{"actors":[{"did":"did:plc:example","handle":"space.example","displayName":"Space","description":"Public account"}]}"""))
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl=server.url("/").toString())},true)
            val page=OriginalAdapter(Source(Platform.BLUESKY,"original",Platform.BLUESKY.origin,role=SourceRole.ORIGINAL),gateway,routes).fetch(routes.search(Platform.BLUESKY,"space"))
            assertEquals("Space",page.accounts.single().name);assertTrue(page.posts.isEmpty());assertEquals(2,server.requestCount)
            assertEquals("https://bsky.app/profile/space.example",routes.recognize("https://web-cdn.bsky.app/profile/space.example")!!.canonicalUrl)
        }
    }
    @Test fun tiebaThreadBodiesDatesMediaAndReplyIdentitiesArePreserved() {
        val source=routes.sources.first {it.frontend=="ratAintTieba"};val target=routes.recognize("https://tieba.baidu.com/p/123")!!
        val body="""<header class="thread-nav"><div class="title">Thread title</div></header><div class="post" id="1"><div class="userinfo"><a>Author</a></div><div class="content">Actual body<img src="/proxy/pic/image.jpg"></div><small class="date">2026年10月16日 09点10分</small></div><div class="post" id="2"><div class="userinfo"><a>Reply author</a></div><div class="content">Actual reply</div><small class="date">2026年10月16日 09点11分</small></div>"""
        val page=Extraction.parse(Payload(body,source.root+"/p/123","text/html",200),target,source,routes)
        assertEquals(2,page.posts.size);val root=page.posts.first();val reply=page.posts.last()
        assertEquals("Thread title",root.title);assertEquals("Actual body",root.body);assertEquals("Author",root.author);assertEquals(1,root.media.size)
        assertEquals(java.time.Instant.parse("2026-10-16T01:10:00Z").toEpochMilli(),root.publishedAt)
        assertEquals("comment:123:2",reply.id);assertEquals(root.id,reply.parentId);assertEquals(reply.canonicalUrl,routes.recognize(reply.canonicalUrl)!!.canonicalUrl)
    }
    @Test fun anonymousFirstPageMayCanonicalizeBeforeAnyBookmarkExists(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/nasa/_created"));server.enqueue(MockResponse().setBody("pins").addHeader("Set-Cookie","bookmark=cursor; Path=/"))
            val gateway=NetworkGateway(cleaner,permit,true);val payload=gateway.pagedText(server.url("/nasa").toString(),"https://www.pinterest.com/nasa")
            assertEquals("cursor",payload.pageBookmark);repeat(2){assertNull(server.takeRequest().getHeader("Cookie"))}
        }
    }
    @Test fun repeatedContentAndUnreadableBlocksStayBoundedWithoutHidingLaterCursors(): Unit=runBlocking {
        val target=routes.search(Platform.X,"space");val post=Post("1",Platform.X,"https://x.com/test/status/1","test","Actual body",publishedAt=1)
        var calls=0;val same=SearchFeed(target,discover={calls++;Page(listOf(post),next="changing-$calls")},read={error("Not expected")},maxPages=4)
        repeat(5){same.loadNext {}};assertEquals(4,calls);assertFalse(same.state.hasMore);assertTrue(same.state.limited);assertEquals(1,same.state.page.posts.size)
        calls=0;val unavailable=SearchFeed(target,discover={calls++;Page(emptyList(),next="next-$calls",collections=(1..6).map {Collection(routes.recognize("https://x.com/test/status/${calls*10+it}")!!,"Index only")})},read={error("Unavailable")},maxPages=3)
        repeat(5){unavailable.loadNext {}};assertEquals(3,calls);assertFalse(unavailable.state.hasMore);assertTrue(unavailable.state.limited);assertEquals(18,unavailable.state.unavailable)
    }
    @Test fun anUnprovenHangingPostFrontendDoesNotDelayOriginalFallbackForThirtySeconds(): Unit=runBlocking {
        MockWebServer().use {alternate->MockWebServer().use {original->
            alternate.start();original.start();alternate.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            original.enqueue(MockResponse().setBody("""{"id_str":"123","text":"Actual original post","user":{"screen_name":"NASA"}}"""))
            val catalog=asset("darwin-routes.json");catalog.add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"${alternate.url("/").toString().trimEnd('/')}","fallbackOnly":false}]""").asJsonArray)
            val local=DarwinRoutes(catalog,cleaner)
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl=if(url.startsWith("https://cdn.syndication.twimg.com"))original.url("/").toString()else null)},true)
            val page=kotlinx.coroutines.withTimeout(6500){SourcePool(gateway,local).load(local.recognize("https://x.com/NASA/status/123")!!)}
            assertEquals("Actual original post",page.posts.single().body);assertEquals(1,alternate.requestCount);assertEquals(1,original.requestCount)
        }}
    }
    @Test fun aPublicPreviewDoesNotHideFullContentAndSurvivesFailedOrDisabledFallback(): Unit=runBlocking {
        for(mode in listOf("full","unavailable","disabled"))MockWebServer().use {alternate->MockWebServer().use {original->
            alternate.start();original.start()
            alternate.enqueue(MockResponse().setBody("""<meta property="og:url" content="https://x.com/NASA/status/123"><meta property="og:type" content="article"><meta property="og:title" content="Public excerpt"><meta property="og:image" content="https://example.org/image.jpg">"""))
            original.enqueue(if(mode=="full")MockResponse().setBody("""{"id_str":"123","text":"Actual original post","user":{"screen_name":"NASA"}}""")else MockResponse().setResponseCode(403))
            val catalog=asset("darwin-routes.json");catalog.add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"${alternate.url("/").toString().trimEnd('/')}","fallbackOnly":false}]""").asJsonArray)
            val local=DarwinRoutes(catalog,cleaner)
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl=if(url.startsWith("https://cdn.syndication.twimg.com"))original.url("/").toString()else null)},true)
            val pool=SourcePool(gateway,local);val page=pool.load(local.recognize("https://x.com/NASA/status/123")!!,originalFallback=mode!="disabled")
            assertEquals(mode!="full",page.posts.single().preview)
            if(mode=="full")assertEquals("Actual original post",page.posts.single().body)
            assertEquals(if(mode=="disabled")0 else 1,original.requestCount)
            assertEquals(HealthStatus.EMPTY,pool.results(Platform.X).first {it.source.role==SourceRole.ALTERNATE}.status)
        }}
    }
    @Test fun originalMediaRestrictionsAlsoCoverRegionalAndMetaCdnHosts() {
        val gateway=NetworkGateway(cleaner,permit).apply {originalFallback=false}
        for(url in listOf("https://p19-common-sign.tiktokcdn-us.com/avatar.jpeg","https://video.tiktokcdn-eu.com/video.mp4","https://video.tiktokv.com/video.mp4","https://scontent.example.fbcdn.net/photo.jpg","https://imgsrc.baidu.com/picture.jpg","https://img.getfn.io/images/picture.jpg")) {
            val error=assertThrows(java.io.IOException::class.java){runBlocking {gateway.text(url)}}
            assertTrue(error.message!!.contains("Original-site access is disabled"))
        }
        assertEquals(0,gateway.protectionStats().checked)
    }
    @Test fun unicodeSourceContextStaysLocalAcrossTextMediaFormsAndRedirects(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val source="https://www.tumblr.com/example/123/你好ㅤ";val contexts=mutableListOf<Pair<String,String>>()
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {contexts+=sourceUrl to type;return FilterDecision(false)}},true)
            fun response(){server.enqueue(MockResponse().setBody("ok"))}
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","/redirect"));response()
            gateway.text(server.url("/text").toString(),source)
            response();gateway.bytes(server.url("/image").toString(),source)
            response();gateway.sample(server.url("/sample").toString(),source)
            response();gateway.form(server.url("/form").toString(),mapOf("q" to "你好"),source)
            response();gateway.pagedText(server.url("/paged").toString(),source)
            val download=java.io.File(java.io.File(System.getProperty("antisocial.native.path")).parentFile,"unicode-download-test")
            try {response();gateway.download(server.url("/download").toString(),source,download);assertEquals("ok",download.readText())}finally {download.delete()}
            assertEquals(7,contexts.size);assertTrue(contexts.all {it.first==source});assertEquals(listOf("xmlhttprequest","xmlhttprequest","image","media","xmlhttprequest","xmlhttprequest","media"),contexts.map {it.second})
            repeat(7){val request=server.takeRequest();assertNull(request.getHeader("X-AntiSocial-Source"));assertNull(request.getHeader("X-AntiSocial-Type"))}
        }
    }
    @Test fun ifunnyWebpageMetadataDoesNotDisplaceTheActualNativeImage() {
        val target=routes.recognize("https://ifunny.co/picture/AbC12XYZ9")!!;val source=Source(Platform.IFUNNY,"original",Platform.IFUNNY.origin,role=SourceRole.ORIGINAL)
        val body="""<script type="application/ld+json">{"@type":"ImageObject","contentUrl":"https://ifunny.co/picture/AbC12XYZ9","description":"Generic site branding"}</script><script>window.__INITIAL_STATE__={"feed":{"items":[{"link":"https://ifunny.co/picture/AbC12XYZ9","created":1700000000,"creator":{"nick":"Actual author"},"url":"https://img.getfn.io/images/actual.jpg","type":"picture"}]}};</script>"""
        val post=Extraction.parse(Payload(body,target.canonicalUrl,"text/html",200),target,source,routes).posts.single()
        assertEquals("Actual author",post.author);assertEquals("https://img.getfn.io/images/actual.jpg",post.media.single().url);assertFalse(post.body.contains("branding"))
    }
}
