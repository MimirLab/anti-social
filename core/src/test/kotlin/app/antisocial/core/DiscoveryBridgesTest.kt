package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscoveryBridgesTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject))
    private val id="UC"+"a".repeat(22);private val peer="UC"+"b".repeat(22)
    private fun payload(body:String,type:String="text/html")=Payload(body,"https://www.youtube.com/@fixture/about",type,200)
    private fun html(owner:String=id)="""<script>var ytInitialData = {"metadata":{"channelMetadataRenderer":{"externalId":"$owner","title":"Public Workshop","ownerUrls":["http://www.youtube.com/@fixture"],"channelUrl":"https://www.youtube.com/channel/$owner","description":"A brace } in prose","facebookProfileId":"workshop.fixture"}},"contents":{"shelfRenderer":{"items":[{"gridChannelRenderer":{"channelId":"$peer","title":{"simpleText":"Another workshop"}}},{"videoRenderer":{"channelId":"UCcccccccccccccccccccccc"}}]}},"onResponseReceivedEndpoints":[{"aboutChannelViewModel":{"channelId":"$owner","links":[{"channelExternalLinkViewModel":{"link":{"commandRuns":[{"onTap":{"innertubeCommand":{"urlEndpoint":{"url":"https://www.youtube.com/redirect?q=https%3A%2F%2Fx.com%2Fworkshop"}}}}]}}}]}}]}; doNotExecute();</script>"""
    @Test fun creatorSelectedChannelsAndDeclaredLinksSurviveWithoutVideoSuggestionsOrJavascript() {
        val root=PublicAccounts.parse("https://www.youtube.com/@fixture",routes)!!
        val page=DiscoveryBridges.youtube(root,payload(html()),routes,123)
        assertEquals(id,page.account.id);assertEquals("https://www.youtube.com/channel/$id",page.account.url)
        assertEquals(peer,page.neighbors.single().account.id);assertEquals(AdjacentKind.FEATURED,page.neighbors.single().kind)
        assertEquals(listOf("https://www.facebook.com/workshop.fixture","https://x.com/workshop"),page.links)
        assertEquals(123L,page.checkedAt);assertTrue(page.description.contains("} in prose"))
    }
    @Test fun channelIdentityMustMatchTheRequestedStableIdOrReturnedVanityAlias() {
        val root=PublicAccount("youtube","https://www.youtube.com/channel/$peer",id=peer)
        assertTrue(runCatching {DiscoveryBridges.youtube(root,payload(html()),routes,1)}.isFailure)
        val other=PublicAccount("youtube","https://www.youtube.com/@someone_else")
        assertTrue(runCatching {DiscoveryBridges.youtube(other,payload(html()),routes,1)}.isFailure)
        assertTrue(runCatching {DiscoveryBridges.youtube(root,payload("<h1>Sign in</h1>"),routes,1)}.isFailure)
    }
    @Test fun hiddenPayloadWorkHasStructuralAndByteLimits() {
        assertTrue(runCatching {DiscoveryBridges.embedded("x="+"[".repeat(81)+"0"+"]".repeat(81),"x=")}.isFailure)
        assertTrue(runCatching {DiscoveryBridges.embedded("a".repeat(DiscoveryBridges.MAX_DOCUMENT+1),"x=")}.isFailure)
        assertNull(DiscoveryBridges.embedded("x={\"unfinished\":true","x="))
    }
    @Test fun externalDiscoveryIdentitiesNeverBecomeOrdinarySocialSubscriptions() {
        val yt="https://www.youtube.com/channel/$id"
        assertTrue(Subscriptions.candidates(listOf(yt),routes,"").isEmpty())
        assertEquals(Platform.YOUTUBE,Subscriptions.candidates(listOf(yt),routes,"",true).single().target.platform)
        assertFalse(ReaderScope.isFeedSource(Platform.YOUTUBE))
        assertEquals("mixcloud",PublicAccounts.parse("https://www.mixcloud.com/workshop/",routes)!!.provider)
        assertNull(PublicAccounts.parse("https://www.mixcloud.com/discover/",routes))
    }
    @Test fun mixerFollowingKeepsActualProfileIdentitiesAndRejectsSwappedOwners() {
        val account=PublicAccount("mixcloud","https://www.mixcloud.com/workshop")
        val data=payload("""{"url":"https://www.mixcloud.com/workshop/","name":"Workshop","biog":"Follow https://x.com/workshop"}""","application/json")
        assertEquals(listOf("https://x.com/workshop"),DiscoveryBridges.mixcloud(account,data,routes,1).links)
        assertTrue(runCatching {DiscoveryBridges.mixcloud(account,data.copy(text=data.text.replace("workshop/","another/")),routes,1)}.isFailure)
        val follows=DiscoveryBridges.mixcloud(account,payload("""{"data":[{"url":"https://www.mixcloud.com/peer/","name":"Useful peer"},{"url":"https://x.com/unrelated"}]}"""),routes,2,true)
        assertEquals("Useful peer",follows.neighbors.single().account.name)
    }
    @Test fun sharedChatGraphHasActualEdgesAndCannotInventCoverageForAnAbsentChannel() {
        val data=payload("""{"generated":"2026-10-23","nodes":[{"n":"seed"},{"n":"peer"},{"n":"unrelated"}],"links":[[0,1,17],[1,2,500],[0,999,8]]}""")
        val page=DiscoveryBridges.twitch(PublicAccount("twitch","https://www.twitch.tv/seed"),data,1)
        assertEquals("peer",page.neighbors.single().account.name);assertTrue(page.neighbors.single().detail.contains("17 shared chat participants"))
        assertTrue(DiscoveryBridges.twitch(PublicAccount("twitch","https://www.twitch.tv/missing"),data,1).neighbors.isEmpty())
    }
    @Test fun allLinkedProvidersGetPrimaryWorkBeforeOneProvidersSecondHopQueue()=runBlocking {
        val seed=Profile(routes.target(Platform.X,"fixture"),"Fixture");val calls=mutableListOf<Platform>()
        val session=AdjacentSession(seed,routes,read={e->calls+=e.source.platform;val body=when(e.source.platform){Platform.X->"""{"code":200,"results":[]}""";Platform.REDDIT->"""{"data":[]}""";else->"[]"};AdjacentDocument(payload(body,"application/json"))})
        repeat(7){session.addBridge(Profile(routes.target(Platform.X,"fixture$it"),"Fixture"))}
        session.addBridge(Profile(routes.target(Platform.REDDIT,"u/nasa"),"NASA"))
        session.loadNext()
        assertTrue("Reddit was starved by the X roots: $calls",Platform.REDDIT in calls.take(3))
    }
    @Test fun profileShellsStillLoadTheirRealLinksAndRoutingModeCanKeepYoutubeWithoutLeakingItToReaderMode()=runBlocking {
        val seed=routes.target(Platform.X,"fixture");var fetched=0
        val known=Profile(seed,"Fixture",displayNameSupplied=false)
        val engine=AccountDiscoveryEngine(routes,profile={fetched++;known.copy(links=listOf("https://www.youtube.com/channel/$id"))},web={u,_->Payload("{}",u,"application/json",200)},discoveryRouting=true)
        val result=engine.discover(seed,known)
        assertEquals(1,fetched);assertTrue(result.publicAccounts.orEmpty().any {it.provider=="youtube"});assertTrue(result.candidates.any {it.target.platform==Platform.YOUTUBE})
        val reader=AccountDiscoveryEngine(routes,profile={fetched++;known.copy(links=listOf("https://www.instagram.com/fixture"))},web={u,_->Payload("{}",u,"application/json",200)})
        val ordinary=reader.discover(seed,known)
        assertEquals(2,fetched);assertTrue(ordinary.candidates.any {it.target.platform==Platform.INSTAGRAM});assertNull(ordinary.publicAccounts)
    }
    @Test fun soundcloudUsesOnlyPublicHydrationFieldsAndPreservesActualFollowsAndWebLinks() {
        val owner=PublicAccount("soundcloud","https://soundcloud.com/fixture")
        val body="""<script>window.__sc_hydration = [{"hydratable":"anonymousId","data":"discard-me"},{"hydratable":"apiClient","data":{"id":"${"a".repeat(32)}"}},{"hydratable":"user","data":{"id":42,"urn":"soundcloud:users:42","permalink_url":"https://soundcloud.com/fixture","username":"Workshop"}}];</script>"""
        val identity=DiscoveryBridges.soundcloudIdentity(owner,payload(body),routes,123)
        assertEquals("42",identity.userId);assertEquals("soundcloud:users:42",identity.urn);assertEquals("Workshop",identity.page.account.name)
        val follows=DiscoveryBridges.soundcloudNeighbors(owner,payload("""{"collection":[{"permalink_url":"https://soundcloud.com/peer","username":"Useful peer"}]}"""),routes,false)
        assertEquals("Useful peer",follows.single().account.name);assertEquals(AdjacentKind.FOLLOWS,follows.single().kind)
        assertEquals(listOf("https://www.example.org/"),DiscoveryBridges.soundcloudLinks(payload("""[{"url":"http://www.example.org"}]"""),owner.url))
    }

}
