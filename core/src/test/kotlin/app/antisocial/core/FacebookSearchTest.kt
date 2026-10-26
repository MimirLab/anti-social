/* AntiSocial — public Facebook search regression fixtures, GPL-3.0-or-later. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FacebookSearchTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val target=routes.search(Platform.FACEBOOK,"sampleton organic food")
    private val source=Source(Platform.FACEBOOK,"facebook-public","https://www.facebook.com",role=SourceRole.ORIGINAL)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    private fun fixture()=JsonParser.parseReader(javaClass.getResourceAsStream("/facebook-search-results.json")!!.reader()).asJsonObject
    private fun directoryFixture()=JsonParser.parseReader(javaClass.getResourceAsStream("/facebook-directory-results.json")!!.reader()).asJsonObject
    private fun results(root: JsonObject)=root.getAsJsonObject("data").getAsJsonObject("serpResponse").getAsJsonObject("results")
    private fun story(edge: com.google.gson.JsonElement)=edge.asJsonObject.getAsJsonObject("rendering_strategy").getAsJsonObject("view_model").getAsJsonObject("click_model").getAsJsonObject("story")
    private fun payload(value: String)=Payload(value,"https://www.facebook.com/api/graphql/","text/html; charset=utf-8",200)
    private fun parse(value: JsonObject)=FacebookProtocol.search(payload(value.toString()),target,source)
    private fun fields(body: String)=body.split('&').associate {part->part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='),"UTF-8")}

    @Test fun actualShapedAnonymousHitsHaveNativeContentAndChronology() {
        // The public endpoint calls its JSON text/html. Parsing is structural;
        // arbitrary HTML is never accepted simply because the request is 200.
        val page=parse(fixture())
        assertEquals(listOf("Synthetic organic food update 501","Synthetic organic food update 500"),page.posts.map {it.body})
        val first=page.posts.first()
        assertEquals("https://www.facebook.com/100/posts/501",first.canonicalUrl)
        assertEquals("Organic Market",first.author);assertEquals(1700000100000L,first.publishedAt)
        assertEquals("https://images.example/produce-501.jpg",first.media.single().url)
        assertEquals("Produce basket",first.media.single().description)
        assertEquals(7L,first.score);assertEquals(3L,first.replyCount)
        assertEquals(source,first.provenance?.source);assertFalse(first.preview)
        assertNull(page.profile);assertTrue(page.collections.isEmpty())
        assertTrue(page.accounts.isEmpty())
        assertEquals("next-search-block",page.next)
    }

    @Test fun nativeDirectoryUsesExplicitEntityRolesAndRetainsOriginalProfileMetadata() {
        val pages=FacebookProtocol.directory(payload(directoryFixture().toString()),target,source,routes,"PAGES_TAB")
        assertTrue(pages.posts.isEmpty());assertNull(pages.next);assertNull(pages.profile)
        assertEquals(listOf("Local Growers","Organic Market"),pages.accounts.map {it.name})
        assertEquals("Community produce\nSampleton",pages.accounts.first().description)
        assertEquals("https://images.example/account-100.jpg",pages.accounts.first().avatar)
        assertEquals("https://www.facebook.com/people/Local-Growers/100",pages.accounts.first().target.canonicalUrl)
        assertEquals("https://www.facebook.com/OrganicMarket",pages.accounts.last().target.canonicalUrl)
        val people=FacebookProtocol.directory(payload(directoryFixture().toString()),target,source,routes,"PEOPLE_TAB")
        assertEquals("Example Person",people.accounts.single().name)
        assertEquals("https://www.facebook.com/200",people.accounts.single().target.canonicalUrl)
        assertTrue((pages.accounts+people.accounts).all {it.target==routes.recognize(it.target.canonicalUrl)})
        assertThrows(SourceFailure::class.java){FacebookProtocol.directory(payload(fixture().toString()),target,source,routes,"PAGES_TAB")}
    }

    @Test fun nativeDirectoryIgnoresPostAuthorsNestedProfilesAndUnsafeOrNonProfileLinks() {
        val root=directoryFixture();val edges=results(root).getAsJsonArray("edges")
        val clean=edges[0].deepCopy().asJsonObject
        for(url in listOf("https://elsewhere.example/Fake","http://www.facebook.com/Unsafe","https://name:secret@www.facebook.com/Unsafe","https://www.facebook.com/login","https://www.facebook.com/100/posts/500","https://www.facebook.com/groups/Organic")) {
            edges.add(clean.deepCopy().apply {getAsJsonObject("rendering_strategy").getAsJsonObject("view_model").getAsJsonObject("profile").addProperty("url",url)})
        }
        edges.add(clean.deepCopy().apply {getAsJsonObject("node").addProperty("role","TOP_PUBLIC_POSTS")})
        edges.add(clean.deepCopy().apply {getAsJsonObject("node").addProperty("role","RECOMMENDED_PAGES")})
        edges.add(clean.deepCopy().apply {getAsJsonObject("rendering_strategy").getAsJsonObject("view_model").getAsJsonObject("profile").addProperty("name"," ")})
        edges.add(clean.deepCopy().apply {getAsJsonObject("rendering_strategy").getAsJsonObject("view_model").getAsJsonObject("profile").addProperty("id","not-an-entity-id")})
        edges.add(JsonObject().apply {add("unrelated_recommendation",clean)})
        root.getAsJsonObject("data").add("profile_bootstrap",clean)
        val accounts=FacebookProtocol.directory(payload(root.toString()),target,source,routes,"PAGES_TAB").accounts
        assertEquals(listOf("Local Growers","Organic Market"),accounts.map {it.name})
    }

    @Test fun observedOpaquePersonIdsKeepTheirSuppliedPublicProfileUrl() {
        // The live PEOPLE_TAB response used pfbid IDs for seven of eight
        // people, including both /people/slug/id and ordinary vanity links.
        val root=directoryFixture();val edges=results(root).getAsJsonArray("edges")
        val template=edges[2].deepCopy().asJsonObject
        val opaque="pfbid0CVi73AteLjmGiQ8WeQQCEDGKjBtnZz8zkF9dh79iEqKKsLyNcquegNAAfZX1y2Lhl"
        val supplied="https://www.facebook.com/people/Example-Grower/$opaque"
        val person=template.deepCopy().apply {getAsJsonObject("rendering_strategy").getAsJsonObject("view_model").getAsJsonObject("profile").apply {addProperty("id",opaque);addProperty("url",supplied);addProperty("name","Example Grower")}}
        edges.add(person)
        edges.add(template.deepCopy().apply {getAsJsonObject("rendering_strategy").getAsJsonObject("view_model").getAsJsonObject("profile").apply {addProperty("id","pfbidABC123");addProperty("url","https://www.facebook.com/example.grower");addProperty("name","Vanity Grower")}})
        val accounts=FacebookProtocol.directory(payload(root.toString()),target,source,routes,"PEOPLE_TAB").accounts
        assertEquals(3,accounts.size)
        val selected=accounts.first {it.name=="Example Grower"}.target
        assertEquals(supplied,selected.canonicalUrl);assertEquals(selected,routes.recognize(supplied))
        assertEquals(TargetKind.PROFILE,selected.kind);assertTrue(PublicSearchAdapter.profileAllowed(selected))
        assertEquals("https://www.facebook.com/example.grower",accounts.first {it.name=="Vanity Grower"}.target.canonicalUrl)
        for(token in listOf("pfbid","pfbidInvalid_id","pfbid"+"A".repeat(196),"not-an-observed-id")) {
            val candidate=selected.copy(identity="people/Example/$token",canonicalUrl="https://www.facebook.com/people/Example/$token")
            assertFalse(PublicSearchAdapter.profileAllowed(candidate))
            val invalid=root.deepCopy().apply {results(this).add("edges",com.google.gson.JsonArray().apply {add(person.deepCopy().apply {getAsJsonObject("rendering_strategy").getAsJsonObject("view_model").getAsJsonObject("profile").addProperty("id",token)})})}
            assertThrows(SourceFailure::class.java){FacebookProtocol.directory(payload(invalid.toString()),target,source,routes,"PEOPLE_TAB")}
        }
    }

    @Test fun nativeDirectoryRejectsLoginAndEmptyConnectionsButKeepsValidPartialEntities() {
        val partial=directoryFixture().apply {add("errors",JsonParser.parseString("""[{"message":"optional field unavailable"}]"""))}
        assertNotNull(FacebookProtocol.directory(payload(partial.toString()),target,source,routes,"PAGES_TAB").notice)
        assertThrows(SourceFailure::class.java){FacebookProtocol.directory(payload("""{"data":{"serpResponse":{"results":{"edges":[],"page_info":{"has_next_page":true,"end_cursor":"directory-next"}}}}}"""),target,source,routes,"PAGES_TAB")}
        val login=assertThrows(SourceFailure::class.java){FacebookProtocol.directory(payload("<h1>Log in to continue</h1>"),target,source,routes,"PEOPLE_TAB")}
        assertEquals(HealthStatus.LOGIN_REQUIRED,login.status)
        assertThrows(SourceFailure::class.java){FacebookProtocol.directory(payload("""{"data":{"other":{"serpResponse":{"results":{}}}}}"""),target,source,routes,"PAGES_TAB")}
        assertThrows(IllegalArgumentException::class.java){FacebookProtocol.directoryVariables(target.identity,"POSTS_TAB")}
    }

    @Test fun directoryRequestsTwoConcurrentAnonymousFirstWindowsWithoutChangingPostSearch(): Unit=runBlocking {
        MockWebServer().use {server->
            val arrived=CountDownLatch(2);val active=AtomicInteger();val maximum=AtomicInteger()
            server.dispatcher=object: Dispatcher() {override fun dispatch(request: RecordedRequest): MockResponse {
                val data=fields(request.body.clone().readUtf8());val variables=JsonParser.parseString(data.getValue("variables")).asJsonObject
                val tab=variables.getAsJsonObject("args").getAsJsonObject("experience")["type"].asString
                if(tab=="POSTS_TAB")return MockResponse().setBody(fixture().toString())
                val current=active.incrementAndGet();maximum.updateAndGet {maxOf(it,current)};arrived.countDown()
                val concurrent=arrived.await(2,TimeUnit.SECONDS);active.decrementAndGet()
                return if(concurrent)MockResponse().setBody(directoryFixture().toString())else MockResponse().setResponseCode(503)
            }}
            server.start();val selected=source.copy(root=server.url("/").toString().trimEnd('/'))
            val adapter=FacebookAdapter(selected,NetworkGateway(cleaner,permit,true),routes)
            val directory=adapter.directory(target,null)
            assertEquals(3,directory.accounts.size);assertTrue(directory.posts.isEmpty());assertNull(directory.next)
            assertEquals(2,maximum.get());assertEquals(2,server.requestCount)
            val tabs=mutableSetOf<String>()
            repeat(2) {
                val request=server.takeRequest();assertEquals("POST",request.method);assertEquals("/api/graphql/",request.path)
                assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
                val data=fields(request.body.readUtf8());assertEquals("0",data["__user"]);assertEquals("25707066605590247",data["doc_id"])
                val variables=JsonParser.parseString(data.getValue("variables")).asJsonObject
                tabs+=variables.getAsJsonObject("args").getAsJsonObject("experience")["type"].asString
                assertEquals(target.identity,variables.getAsJsonObject("args")["text"].asString)
                assertTrue(variables["cursor"].isJsonNull);assertEquals(5,variables["count"].asInt);assertFalse(variables["useDefaultActor"].asBoolean)
            }
            assertEquals(setOf("PAGES_TAB","PEOPLE_TAB"),tabs)
            assertEquals(2,adapter.fetch(target).posts.size)
            val postVariables=JsonParser.parseString(fields(server.takeRequest().body.readUtf8()).getValue("variables")).asJsonObject
            assertEquals("POSTS_TAB",postVariables.getAsJsonObject("args").getAsJsonObject("experience")["type"].asString)
        }
    }

    @Test fun directoryRetainsOneWorkingCategoryWhenTheOtherRefusesAccess(): Unit=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object: Dispatcher() {override fun dispatch(request: RecordedRequest): MockResponse {
                val variables=JsonParser.parseString(fields(request.body.clone().readUtf8()).getValue("variables")).asJsonObject
                return MockResponse().setBody(if(variables.getAsJsonObject("args").getAsJsonObject("experience")["type"].asString=="PAGES_TAB")directoryFixture().toString()else """{"errors":[{"message":"login_required"}]}""")
            }}
            server.start();val adapter=FacebookAdapter(source.copy(root=server.url("/").toString().trimEnd('/')),NetworkGateway(cleaner,permit,true),routes)
            val page=adapter.directory(target,null)
            assertEquals(2,page.accounts.size);assertNotNull(page.notice);assertTrue(page.posts.isEmpty());assertNull(page.next)
            assertEquals(2,server.requestCount)
        }
    }

    @Test fun directoryRefusesScopeCursorIdentityTamperingAndUnreadyTorBeforeTraffic(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val gateway=NetworkGateway(cleaner,permit,true)
            val adapter=FacebookAdapter(source.copy(root=server.url("/").toString().trimEnd('/')),gateway,routes)
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.directory(target,"some-post-cursor")}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.directory(target.copy(identity="different query"),null)}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.directory(routes.search(Platform.FACEBOOK,"organic",SearchScope(SearchScopeKind.ACCOUNT,"market")),null)}}
            gateway.configure(NetworkPolicy(tor=true,ready=false))
            assertThrows(IOException::class.java){runBlocking {adapter.directory(target,null)}}
            assertEquals(0,server.requestCount)
        }
        val denied=NetworkGateway(cleaner,permit).apply {originalFallback=false}
        assertThrows(IOException::class.java){runBlocking {FacebookAdapter(source,denied,routes).directory(target,null)}}
        assertEquals(0L,denied.protectionStats().checked)
    }

    @Test fun phiceDirectoryReadsOnlySearchCardsAndKeepsBothRequestsOnItsChosenSource(): Unit=runBlocking {
        val html="""
          <a href="/mirror/people/Local-Growers/100/"><div class="search_card card"><img class="search_card_picture" src="https://images.example/growers.jpg"><div class="search_body"><span>Local Growers</span><p class="search_card_description">Community produce</p></div></div></a>
          <a href="/mirror/OrganicMarket"><div class="search_card card"><div class="search_body"><span>Organic Market</span><p class="search_card_description">Seasonal food</p></div></div></a>
          <a href="https://elsewhere.example/Fake"><div class="search_card"><div class="search_body"><span>Foreign link</span></div></div></a>
          <a href="/mirror/login"><div class="search_card"><div class="search_body"><span>Login wall</span></div></div></a>
          <a href="/mirror/Market/posts/500"><div class="search_card"><div class="search_body"><span>A post is not an account</span></div></div></a>
          <article class="post"><div class="post_name"><b>Post author is not a directory result</b></div></article>
          <div id="navigation_buttons"><a href="/mirror/search?t=pages&amp;q=sampleton+organic+food&amp;cursor=unused">Next</a></div>
        """.trimIndent()
        MockWebServer().use {server->
            server.start();repeat(2){server.enqueue(MockResponse().setBody(html))}
            val selected=Source(Platform.FACEBOOK,"phice",server.url("/mirror").toString(),role=SourceRole.ALTERNATE)
            val adapter=FacebookAdapter(selected,NetworkGateway(cleaner,permit,true),routes)
            val page=adapter.directory(target,null)
            assertEquals(listOf("Local Growers","Organic Market"),page.accounts.map {it.name})
            assertEquals("https://www.facebook.com/people/Local-Growers/100",page.accounts.first().target.canonicalUrl)
            assertEquals("https://www.facebook.com/OrganicMarket",page.accounts.last().target.canonicalUrl)
            assertEquals("https://images.example/growers.jpg",page.accounts.first().avatar)
            assertTrue(page.posts.isEmpty());assertNull(page.next);assertEquals(selected,page.provenance?.source)
            val categories=mutableSetOf<String>()
            repeat(2){val request=server.takeRequest().requestUrl!!;assertEquals("/mirror/search",request.encodedPath);assertEquals(target.identity,request.queryParameter("q"));assertNull(request.queryParameter("cursor"));categories+=request.queryParameter("t")!!}
            assertEquals(setOf("pages","people"),categories);assertEquals(2,server.requestCount)
            assertThrows(SourceFailure::class.java){FacebookProtocol.phiceDirectory(Payload("<article class=\"post\">Only a post author</article>",selected.root+"/search?t=pages&q=x","text/html",200),target,selected,routes)}
            assertThrows(IllegalArgumentException::class.java){FacebookProtocol.alternateSearchUrl(selected.root+"/search?t=posts&q=sampleton+organic+food",target,selected,"pages")}
        }
    }

    @Test fun recommendedNestedAndSponsoredStoriesNeverBecomeExtraSearchHits() {
        val root=fixture();val edges=results(root).getAsJsonArray("edges")
        val recommended=edges[0].deepCopy().asJsonObject.apply {getAsJsonObject("node").addProperty("role","RECOMMENDED_POSTS");story(this).apply {addProperty("post_id","900");addProperty("id","story-900")}}
        val sponsored=edges[0].deepCopy().asJsonObject.apply {story(this).apply {addProperty("post_id","901");addProperty("id","story-901");add("sponsored_data",JsonObject())}}
        val nested=story(edges[0]).deepCopy().apply {addProperty("post_id","902");addProperty("id","story-902")}
        story(edges[0]).add("recommended_stories",nested)
        edges.add(recommended);edges.add(sponsored);edges.add(edges[1].deepCopy())
        root.getAsJsonObject("data").add("unrelated_bootstrap",nested)
        assertEquals(setOf("https://www.facebook.com/100/posts/500","https://www.facebook.com/100/posts/501"),parse(root).posts.map {it.canonicalUrl}.toSet())
        assertEquals(2,parse(root).posts.size)
    }

    @Test fun alternateStoryLayoutAndExactTimestampDoNotUseNestedRecommendationTime() {
        val root=fixture();val edges=results(root).getAsJsonArray("edges")
        val view=edges[0].asJsonObject.getAsJsonObject("rendering_strategy").getAsJsonObject("view_model")
        val actual=story(edges[0]);view.remove("click_model");view.add("story",actual)
        actual.getAsJsonObject("comet_sections").add("context_layout",JsonParser.parseString("""{"recommendation":{"creation_time":1900000000}}"""))
        assertEquals(1700000000000L,parse(root).posts.first {it.canonicalUrl.endsWith("500")}.publishedAt)
    }

    @Test fun originalPermalinkFormsRemainReadablePostIdentitiesIncludingGroupContext() {
        // The successful live response included Page pfbid links and this
        // permalink.php form. Native numeric identities remain recognized too.
        val originals=listOf(
            "https://www.facebook.com/eastfieldnaturalfoods/posts/pfbid0251iSzvXxhSF4SKbrRVonx7ish8YeLYTijDR5eTKEkjUSJZKCdKiDxE8iTuTeFtSl",
            "https://www.facebook.com/permalink.php?story_fbid=pfbid0NQvGGd5c4dZsr7om8zicEu8VY3fQoQZ3ECT8qft6RY2kkC94ur8zpsyCMPSkn5Hkl&id=100069553181908",
            "https://www.facebook.com/100/posts/500",
            "https://www.facebook.com/groups/OrganicGrowers/posts/500"
        )
        for(url in originals)assertEquals(TargetKind.POST,requireNotNull(routes.recognize(url)).kind)
        val root=fixture();val first=story(results(root).getAsJsonArray("edges")[0])
        first.addProperty("permalink_url",originals.last())
        val post=parse(root).posts.first {it.body.endsWith("500")}
        assertEquals(originals.last(),post.canonicalUrl)
        val selected=requireNotNull(routes.recognize(post.canonicalUrl))
        assertEquals(TargetKind.POST,selected.kind);assertEquals(post.canonicalUrl,selected.canonicalUrl)
        assertEquals(ContentIdentity.key(Platform.FACEBOOK,selected.canonicalUrl),post.id)
        assertTrue(parse(root).posts.all {routes.recognize(it.canonicalUrl)?.kind==TargetKind.POST})
    }

    @Test fun continuationComesOnlyFromSearchConnectionIncludingDeferredMetadata() {
        val root=fixture();val response=results(root);response.remove("page_info")
        story(response.getAsJsonArray("edges")[0]).add("comments",JsonParser.parseString("""{"page_info":{"has_next_page":true,"end_cursor":"wrong-comments"}}"""))
        val wrong="""{"label":"SearchCometResults_defer_page_info","path":["other","results"],"data":{"page_info":{"has_next_page":true,"end_cursor":"wrong-search"}}}"""
        val nested="""{"label":"SearchCometResults_defer_page_info","path":["serpResponse","results","edges",0,"comments"],"data":{"page_info":{"has_next_page":true,"end_cursor":"nested-comment-cursor"}}}"""
        val malformed="""{"label":"SearchCometResults_defer_page_info","path":["serpResponse",null,"results"],"data":{"page_info":{"has_next_page":true,"end_cursor":"malformed-path"}}}"""
        val deferred="""{"label":"SearchCometResultsPaginatedResultsQuery_defer_page_info","path":["serpResponse","results"],"data":{"page_info":{"has_next_page":true,"end_cursor":"deferred-search"}}}"""
        assertEquals("deferred-search",FacebookProtocol.search(payload(root.toString()+"\n"+wrong+"\n"+nested+"\n"+malformed+"\n"+deferred),target,source).next)
        assertNull(FacebookProtocol.search(payload(root.toString()+"\n"+nested+"\n"+malformed),target,source).next)
        assertNull(parse(root).next)
    }

    @Test fun errorsOrLoginHtmlNeverCountAsWorkingEmptySearch() {
        val partial=fixture().apply {add("errors",JsonParser.parseString("""[{"message":"optional attachment unavailable"}]"""))}
        assertNotNull(parse(partial).notice)
        val empty=fixture().apply {results(this).getAsJsonArray("edges").remove(1);results(this).getAsJsonArray("edges").remove(0);results(this).getAsJsonObject("page_info").addProperty("has_next_page",false)}
        assertThrows(SourceFailure::class.java){parse(empty)}
        val login=assertThrows(SourceFailure::class.java){FacebookProtocol.search(payload("<h1>Log in to continue</h1>"),target,source)}
        assertEquals(HealthStatus.LOGIN_REQUIRED,login.status)
        val rate=assertThrows(SourceFailure::class.java){FacebookProtocol.search(payload("""{"errors":[{"code":1675004,"message":"Rate limit"}]}"""),target,source)}
        assertEquals(HealthStatus.RATE_LIMITED,rate.status)
        assertThrows(SourceFailure::class.java){FacebookProtocol.search(payload("""{"data":{"unrelated":{"serpResponse":{"results":{}}}}}"""),target,source)}
    }

    @Test fun emptyFilteredBlockAdvancesOnlyWithARealBoundedSearchConnection() {
        val filtered=fixture().apply {results(this).getAsJsonArray("edges").forEach {story(it).add("sponsored_data",JsonObject())}}
        val progress=parse(filtered)
        assertTrue(progress.posts.isEmpty());assertEquals("next-search-block",progress.next);assertNotNull(progress.notice)
        val empty=filtered.deepCopy().apply {results(this).add("edges",com.google.gson.JsonArray())}
        assertEquals("next-search-block",parse(empty).next)
        for(info in listOf(
            """{"has_next_page":false,"end_cursor":"unused"}""",
            """{"has_next_page":"true","end_cursor":"not-a-boolean"}""",
            """{"has_next_page":true,"end_cursor":123}""",
            """{"has_next_page":true,"end_cursor":" "}""",
            """{"has_next_page":true,"end_cursor":"control\ncharacter"}""",
            """{"has_next_page":true,"end_cursor":null}""",
            """{"has_next_page":true,"end_cursor":"${"x".repeat(8193)}"}"""
        ))assertThrows(SourceFailure::class.java){parse(empty.deepCopy().apply {results(this).add("page_info",JsonParser.parseString(info))})}
        assertThrows(SourceFailure::class.java){parse(empty.deepCopy().apply {results(this).remove("edges")})}
        assertThrows(SourceFailure::class.java){parse(empty.deepCopy().apply {add("errors",JsonParser.parseString("""[{"message":"login_required"}]"""))})}
    }

    @Test fun nativeSearchUsesOneSmallAnonymousReadAndBoundContinuation(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(fixture().toString()))
            val end=fixture().apply {results(this).getAsJsonObject("page_info").addProperty("has_next_page",false)}
            server.enqueue(MockResponse().setBody(end.toString()))
            val selected=source.copy(root=server.url("/").toString().trimEnd('/'))
            val adapter=FacebookAdapter(selected,NetworkGateway(cleaner,permit,true),routes)
            assertTrue(Capability.SEARCH in adapter.capabilities)
            val first=adapter.fetch(target);assertNotNull(first.next)
            assertNull(adapter.fetch(target,first.next).next)
            repeat(2){index->
                val request=server.takeRequest();assertEquals("POST",request.method);assertEquals("/api/graphql/",request.path)
                assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
                assertEquals("_",request.getHeader("X-FB-LSD"))
                val data=fields(request.body.readUtf8())
                assertEquals("SearchCometResultsPaginatedResultsQuery",data["fb_api_req_friendly_name"])
                assertEquals("25707066605590247",data["doc_id"]);assertEquals("0",data["__user"])
                val variables=JsonParser.parseString(data.getValue("variables")).asJsonObject
                assertEquals("sampleton organic food",variables.getAsJsonObject("args")["text"].asString)
                assertEquals("POSTS_TAB",variables.getAsJsonObject("args").getAsJsonObject("experience")["type"].asString)
                assertEquals(5,variables["count"].asInt);assertFalse(variables["useDefaultActor"].asBoolean)
                assertFalse(variables.has("id"));assertFalse(variables.has("actorID"))
                if(index==0)assertTrue(variables["cursor"].isJsonNull)else assertEquals("next-search-block",variables["cursor"].asString)
            }
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(routes.search(Platform.FACEBOOK,"other query"),first.next)}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(target.copy(identity="other query"),first.next)}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(target,FacebookProtocol.cursor(selected,target,"comment-thread","some-token"))}}
            assertEquals(2,server.requestCount)
        }
    }

    @Test fun nativeSearchCannotSilentlyWidenPageScopeOrChangeSelectedSource(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val selected=source.copy(root=server.url("/").toString().trimEnd('/'))
            val adapter=FacebookAdapter(selected,NetworkGateway(cleaner,permit,true),routes)
            val scoped=routes.search(Platform.FACEBOOK,"organic",SearchScope(SearchScopeKind.ACCOUNT,"market"))
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(scoped)}}
            val wrong=FacebookProtocol.cursor(source,target,"search:posts","next")
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(target,wrong)}}
            assertEquals(0,server.requestCount)
        }
    }

    @Test fun selectedTorAndDisabledOriginalPolicyFailBeforeAnySearchTraffic(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val gateway=NetworkGateway(cleaner,permit,true).apply {configure(NetworkPolicy(tor=true,ready=false))}
            val adapter=FacebookAdapter(source.copy(root=server.url("/").toString().trimEnd('/')),gateway,routes)
            val tor=assertThrows(IOException::class.java){runBlocking {adapter.fetch(target)}}
            assertTrue(tor.message!!.contains("Tor"));assertEquals(0,server.requestCount)
        }
        val gateway=NetworkGateway(cleaner,permit).apply {originalFallback=false}
        val denied=assertThrows(IOException::class.java){runBlocking {FacebookAdapter(source,gateway,routes).fetch(target)}}
        assertTrue(denied.message!!.contains("Original-site access"))
        assertEquals(0L,gateway.protectionStats().checked)
    }

    private fun phiceHtml(next: String="/search?t=posts&amp;q=sampleton+organic+food&amp;cursor=more")="""
       <article class="post"><a class="post_link" href="/Market/posts/500"></a><div class="post_name"><b>Market</b></div><div class="post_text">Organic market</div><div class="post_time"><span title="2026-10-16T10:00:00Z"></span></div></article>
       <article class="post"><a class="post_link" href="/Garden/posts/501"></a><div class="post_name"><b>Garden</b></div><div class="post_text">Community garden</div><div class="post_time"><span title="2026-10-17T10:00:00Z"></span></div></article>
       <article class="comment"><a class="comment_link" href="/Market/posts/500?comment_id=9"></a><div class="comment_text">Unrelated nested reply</div></article>
       <div id="navigation_buttons"><a href="$next">Next</a></div>
    """.trimIndent()

    @Test fun phiceSearchKeepsDistinctPostIdentitiesAndSourceBoundSameQueryPagination(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(phiceHtml()))
            server.enqueue(MockResponse().setBody(phiceHtml("/search?t=posts&amp;q=unrelated&amp;cursor=wrong")))
            val selected=Source(Platform.FACEBOOK,"phice",server.url("/").toString().trimEnd('/'),role=SourceRole.ALTERNATE)
            val adapter=FacebookAdapter(selected,NetworkGateway(cleaner,permit,true),routes)
            val first=adapter.fetch(target)
            assertEquals(listOf("Community garden","Organic market"),first.posts.map {it.body})
            assertEquals(2,first.posts.map {it.id}.distinct().size);assertTrue(first.posts.none {it.parentId!=null})
            assertEquals(setOf("https://www.facebook.com/Market/posts/500","https://www.facebook.com/Garden/posts/501"),first.posts.map {it.canonicalUrl}.toSet())
            assertNotNull(first.next);assertNull(adapter.fetch(target,first.next).next)
            repeat(2){index->val request=server.takeRequest().requestUrl!!
                assertEquals("/search",request.encodedPath);assertEquals("posts",request.queryParameter("t"));assertEquals("sampleton organic food",request.queryParameter("q"))
                if(index==1)assertEquals("more",request.queryParameter("cursor"))
            }
            for(url in listOf(selected.root+"/search?t=posts&q=unrelated&cursor=bad",selected.root+"/search?t=profiles&q=sampleton+organic+food&cursor=bad",selected.root+"/search?t=posts&q=sampleton+organic+food&q=other&cursor=bad","https://elsewhere.example/search?t=posts&q=sampleton+organic+food&cursor=bad")) {
                assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(target,FacebookProtocol.cursor(selected,target,"alternate",url))}}
            }
            assertEquals(2,server.requestCount)
        }
    }
}
