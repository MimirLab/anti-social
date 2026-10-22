package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CommunityMetadataTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private val target=routes.search(Platform.REDDIT,"gardening")
    private val reddit=Source(Platform.REDDIT,"original","https://www.reddit.com",role=SourceRole.ORIGINAL)
    private fun subreddit(name: String="gardening",url: String="/r/$name/")="""{"kind":"t5","data":{"id":"abc","name":"t5_abc","display_name":"$name","url":"$url","title":"Gardeners together","public_description":"An actual public community description","subscribers":1234,"community_icon":"https://styles.redditmedia.com/icon.png"}}"""
    private fun parse(body: String,selected: Target=target,source: Source=reddit)=Extraction.parse(Payload(body,source.root+"/search","application/json",200),selected,source,routes)

    @Test fun nativeRedditListingAndAboutMetadataKeepCommunityIdentityWithoutInventingPosts() {
        val listing="""{"kind":"Listing","data":{"children":[${subreddit()},${subreddit("composting")}]}}"""
        val page=parse(listing)
        assertTrue(page.posts.isEmpty());assertTrue(page.collections.isEmpty());assertEquals(2,page.accounts.size)
        val community=page.accounts.first();assertEquals(TargetKind.COMMUNITY,community.target.kind)
        assertEquals("Gardeners together",community.name);assertEquals(1234L,community.followers)
        assertEquals("An actual public community description",community.description)
        assertEquals("https://www.reddit.com/r/gardening",community.target.canonicalUrl)
        val selected=routes.target(Platform.REDDIT,"r/gardening",TargetKind.COMMUNITY)
        val about=parse(subreddit(),selected)
        assertEquals(selected,about.profile!!.target);assertTrue(about.accounts.isEmpty());assertTrue(about.posts.isEmpty())
        assertThrows(SourceFailure::class.java){parse(subreddit("composting"),selected)}
        assertThrows(SourceFailure::class.java){parse(subreddit(url="https://evil.example/r/gardening"),selected)}
        assertThrows(SourceFailure::class.java){parse(subreddit().replace("\"subscribers\":1234","\"subreddit_type\":\"private\",\"subscribers\":1234"),selected)}
    }

    @Test fun redditUserAboutUsesRealUserMetadataAndKeepsUserAndCommunitySeparate() {
        val user=routes.target(Platform.REDDIT,"user/gardener")
        val body="""{"kind":"t2","data":{"id":"def","name":"gardener","icon_img":"https://styles.redditmedia.com/user.png","subreddit":{"title":"A real gardener","public_description":"Actual biography"}}}"""
        val page=parse(body,user)
        assertEquals(user,page.profile!!.target);assertEquals("A real gardener",page.profile!!.name)
        assertEquals("Actual biography",page.profile!!.description);assertTrue(page.posts.isEmpty())
        assertNotEquals(PublicSearchAdapter.entityKey(user),PublicSearchAdapter.entityKey(routes.target(Platform.REDDIT,"r/gardener",TargetKind.COMMUNITY)))
        assertThrows(SourceFailure::class.java){parse(body.replace("\"name\":\"gardener\"","\"name\":\"somebodyelse\""),user)}
    }

    @Test fun genuineRedlibDirectoryCardsAndSubredditPanelUseSuppliedMetadataOnly() {
        val source=Source(Platform.REDDIT,"redlib","https://redlib.fixture.example")
        val body="""<div id="search_subreddits"><a class="search_subreddit" href="/r/gardening"><span class="search_subreddit_left"><img src="https://styles.redditmedia.com/icon.png"></span><span class="search_subreddit_name">r/gardening</span><span class="search_subreddit_description">Actual directory description</span></a><a class="search_subreddit" href="/r/composting"><span class="search_subreddit_name">r/wrongname</span></a><a class="search_subreddit" id="more_subreddits" href="/search?type=sr_user&amp;q=gardening">More communities</a></div><a href="/r/random_navigation">Unrelated site navigation</a>"""
        val page=Extraction.parse(Payload(body,source.root+"/search?q=gardening&type=sr_user","text/html",200),target,source,routes)
        assertTrue(page.posts.isEmpty());assertEquals(1,page.accounts.size)
        assertEquals("r/gardening",page.accounts.single().name)
        assertEquals("Actual directory description",page.accounts.single().description)
        assertEquals("https://www.reddit.com/r/gardening",page.accounts.single().target.canonicalUrl)
        val panel="""<details id="subreddit"><div id="sub_meta"><img id="sub_icon" src="https://styles.redditmedia.com/icon.png"><h1 id="sub_title">Gardeners together</h1><p id="sub_name">r/gardening</p><p id="sub_description">Actual panel description</p></div></details>"""
        val selected=routes.target(Platform.REDDIT,"r/gardening",TargetKind.COMMUNITY)
        val profile=Extraction.parse(Payload(panel,source.root+"/r/gardening/new","text/html",200),selected,source,routes).profile
        assertEquals(selected,profile!!.target);assertEquals("Actual panel description",profile.description)
        assertThrows(SourceFailure::class.java){Extraction.parse(Payload(panel.replace("r/gardening","r/unrelated"),source.root+"/r/gardening/new","text/html",200),selected,source,routes)}
    }

    private fun lemmy(name: String="gardening",actor: String="https://lemmy.world/c/$name")="""{"community_view":{"community":{"id":2,"name":"$name","title":"Gardeners together","actor_id":"$actor","description":"Actual Lemmy description","icon":"https://images.example/icon.png","deleted":false,"removed":false},"counts":{"subscribers":42}}}"""

    @Test fun selectedLemmyEntityRequiresMatchingNameAndActorOriginIncludingRemoteAliases() {
        val selected=routes.target(Platform.LEMMY,"gardening",TargetKind.COMMUNITY)
        val page=parse(lemmy(),selected,Source(Platform.LEMMY,"original","https://lemmy.world",role=SourceRole.ORIGINAL))
        assertEquals(selected,page.profile!!.target);assertEquals(42L,page.profile!!.followers);assertTrue(page.posts.isEmpty())
        assertNull(Extraction.lemmyProfile(JsonParser.parseString(lemmy(actor="https://other.example/c/gardening")).asJsonObject,selected))
        assertNull(Extraction.lemmyProfile(JsonParser.parseString(lemmy(name="unrelated")).asJsonObject,selected))
        val remote=requireNotNull(routes.recognize("https://lemmy.world/c/gardening@other.example",Platform.LEMMY))
        assertEquals(remote,Extraction.lemmyProfile(JsonParser.parseString(lemmy(actor="https://other.example/c/gardening")).asJsonObject,remote)?.target)
        assertNull(Extraction.lemmyProfile(JsonParser.parseString(lemmy().replace("\"removed\":false","\"removed\":true")).asJsonObject,selected))
    }

    @Test fun lemmyMetadataHydrationDoesNotRequireOrFetchATimeline(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(lemmy()))
            val source=Source(Platform.LEMMY,"lemmy-api",server.url("/").toString().trimEnd('/'))
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            try {
                val selected=routes.target(Platform.LEMMY,"gardening",TargetKind.COMMUNITY)
                val page=requireNotNull(publicSocialAdapter(source,gateway,routes)).profile(selected)
                assertTrue(page.posts.isEmpty());assertEquals("Gardeners together",page.profile!!.name)
                assertEquals("/api/v3/community?name=gardening",server.takeRequest().path);assertEquals(1,server.requestCount)
            }finally {gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()}
        }
    }
}
