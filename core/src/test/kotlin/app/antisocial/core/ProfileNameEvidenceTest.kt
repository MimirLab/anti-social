/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** A readable profile card is not automatically proof that its title is an account's display name. */
class ProfileNameEvidenceTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private fun obj(body: String)=JsonParser.parseString(body).asJsonObject
    private fun provenance(platform: Platform,frontend: String="original")=Provenance(Source(platform,frontend,platform.origin),1,platform.origin)
    private fun target(platform: Platform,value: String)=routes.target(platform,value)

    @Test fun legacyAndManuallyConstructedProfilesDoNotClaimDisplayNameEvidence() {
        assertFalse(Profile(target(Platform.X,"alice"),"Alice Example").displayNameSupplied)
    }

    @Test fun nitterFullnameIsEvidenceButGenericHeadingAndTargetFallbackAreNot() {
        val t=target(Platform.X,"NASA");val p=provenance(Platform.X,"nitter").copy(finalUrl=t.canonicalUrl)
        val named=Extraction.html("<div class='profile-card'><a class='user-name'>@NASA</a><a class='profile-card-fullname'>NASA</a></div>",t,p,routes).profile!!
        assertEquals("NASA",named.name);assertTrue(named.displayNameSupplied)
        assertFalse(Extraction.html("<div class='profile-card'><h1>Nitter</h1></div>",t,p,routes).profile!!.displayNameSupplied)
        assertFalse(Extraction.html("<div class='profile-card'><div class='profile-bio'>About the account</div></div>",t,p,routes).profile!!.displayNameSupplied)
    }

    @Test fun evenAnExactOpenGraphProfileTitleDoesNotClaimAProperPublicName() {
        val t=target(Platform.INSTAGRAM,"alice")
        val html="""<meta property="og:type" content="profile"><meta property="og:url" content="${t.canonicalUrl}"><meta property="og:title" content="Instagram">"""
        assertFalse(Extraction.html(html,t,provenance(Platform.INSTAGRAM),routes).profile!!.displayNameSupplied)
    }

    @Test fun structuredBlueskyDisplayNameIsDistinguishedFromHandleFallback() {
        val t=target(Platform.BLUESKY,"alice.bsky.social");val p=provenance(Platform.BLUESKY)
        val supplied=Extraction.json(obj("""{"handle":"alice.bsky.social","displayName":"Alice Example"}"""),t,p,routes).profile!!
        assertEquals("Alice Example",supplied.name);assertTrue(supplied.displayNameSupplied)
        val fallback=Extraction.json(obj("""{"handle":"alice.bsky.social","description":"About this account"}"""),t,p,routes).profile!!
        assertEquals("alice.bsky.social",fallback.name);assertFalse(fallback.displayNameSupplied)
    }

    @Test fun emptyTikTokNicknameDoesNotPromoteItsUniqueIdToProperName() {
        val t=target(Platform.TIKTOK,"alice");val p=provenance(Platform.TIKTOK)
        assertTrue(Extraction.json(obj("""{"uniqueId":"alice","nickname":"Alice Example"}"""),t,p,routes).profile!!.displayNameSupplied)
        assertFalse(Extraction.json(obj("""{"uniqueId":"alice","nickname":""}"""),t,p,routes).profile!!.displayNameSupplied)
    }

    @Test fun mastodonDisplayNameMayEqualHandleButUsernameFallbackIsNotEvidence() {
        val t=target(Platform.MASTODON,"NASA@mastodon.social")
        val named=PublicSocialParsing.mastodonProfile(obj("""{"id":"7","username":"NASA","display_name":"NASA"}"""),t,t.canonicalUrl)!!
        assertEquals("NASA",named.name);assertTrue(named.displayNameSupplied)
        assertFalse(PublicSocialParsing.mastodonProfile(obj("""{"id":"7","username":"NASA","display_name":""}"""),t,t.canonicalUrl)!!.displayNameSupplied)
    }

    @Test fun lemmyDisplayNameNeedsItsActualFieldAfterActorOwnershipIsValidated() {
        val t=target(Platform.LEMMY,"@alice@lemmy.world")
        fun body(name: String)="""{"person_view":{"person":{"name":"alice","display_name":"$name","actor_id":"https://lemmy.world/u/alice"}}}"""
        assertTrue(Extraction.lemmyProfile(obj(body("Alice Example")),t)!!.displayNameSupplied)
        assertFalse(Extraction.lemmyProfile(obj(body("")),t)!!.displayNameSupplied)
        assertNull(Extraction.lemmyProfile(obj(body("Alice Example").replace("lemmy.world/u/alice","other.example.org/u/alice")),t))
    }

    @Test fun telegramUsesKnownChannelHeadingAndNotItsFallbackHandle() {
        val t=target(Platform.TELEGRAM,"alice");val p=provenance(Platform.TELEGRAM)
        val named=PublicSocialParsing.telegram("<div class='tgme_channel_info_header_title'>Alice Example</div>",t,p).profile!!
        assertTrue(named.displayNameSupplied);assertEquals("Alice Example",named.name)
        assertFalse(PublicSocialParsing.telegram("<h1>Telegram</h1>",t,p).profile!!.displayNameSupplied)
    }

    @Test fun invidiousAuthorMetadataIsEvidenceButMissingMetadataIsNot(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val adapter=YouTubeAdapter(Source(Platform.YOUTUBE,"invidious",server.url("/").toString().trimEnd('/')),gateway,routes)
            val t=target(Platform.YOUTUBE,"UC_x5XG1OV2P6uZZ5FSM9Ttw")
            server.enqueue(MockResponse().setBody("""{"author":"Google for Developers","authorId":"UC_x5XG1OV2P6uZZ5FSM9Ttw","latestVideos":[]}"""))
            val actual=adapter.profile(t).profile!!
            assertEquals("Google for Developers",actual.name);assertTrue(actual.displayNameSupplied)
            server.enqueue(MockResponse().setBody("""{"latestVideos":[]}"""))
            assertFalse(adapter.profile(t).profile!!.displayNameSupplied)
        }
    }

    @Test fun htmlFullnameRequiresExactFinalProfileAndCannotComeFromATweet() {
        val t=target(Platform.X,"NASA")
        val p=provenance(Platform.X,"nitter").copy(finalUrl=t.canonicalUrl)
        val html="""<div class="profile-card"><a class="profile-card-fullname">Someone Else</a></div>"""
        val redirected=Extraction.html(html,t,p.copy(finalUrl="https://x.com/POTUS"),routes).profile!!
        assertFalse(redirected.displayNameSupplied)
        val nested="""<div class="profile-card"><div class="timeline-item"><a class="fullname" href="https://x.com/POTUS">The President</a></div></div>"""
        assertFalse(Extraction.html(nested,t,p,routes).profile!!.displayNameSupplied)
        val generic="""<div class="profile-card"><span class="fullname">A name without account ownership</span></div>"""
        assertFalse(Extraction.html(generic,t,p,routes).profile!!.displayNameSupplied)
        val own="""<div class="profile-card"><a class="fullname" href="https://x.com/NASA">NASA</a></div>"""
        assertTrue(Extraction.html(own,t,p,routes).profile!!.displayNameSupplied)
    }

    @Test fun youtubePublicNameNeedsReturnedChannelIdentityOrExactHandleUrl() {
        val channel="UC_x5XG1OV2P6uZZ5FSM9Ttw"
        val t=target(Platform.YOUTUBE,channel)
        assertTrue(YouTubeAdapter.profileIdentity(obj("""{"authorId":"$channel"}"""),t))
        assertTrue(YouTubeAdapter.profileIdentity(obj("""{"id":"$channel"}"""),t))
        assertFalse(YouTubeAdapter.profileIdentity(obj("""{"author":"A convincing unrelated name"}"""),t))
        assertFalse(YouTubeAdapter.profileIdentity(obj("""{"authorId":"UCaaaaaaaaaaaaaaaaaaaaaa"}"""),t))
        assertFalse(YouTubeAdapter.profileIdentity(obj("""{"authorId":"$channel","authorUrl":"/channel/UCaaaaaaaaaaaaaaaaaaaaaa"}"""),t))
        val handle=target(Platform.YOUTUBE,"@NASA")
        assertTrue(YouTubeAdapter.profileIdentity(obj("""{"authorUrl":"https://www.youtube.com/@NASA"}"""),handle))
        assertFalse(YouTubeAdapter.profileIdentity(obj("""{"authorId":"$channel","authorUrl":"/channel/$channel"}"""),handle))
        assertFalse(YouTubeAdapter.profileIdentity(obj("""{"authorUrl":"https://www.youtube.com/@POTUS"}"""),handle))
        assertFalse(YouTubeAdapter.profileIdentity(obj("""{"authorUrl":"https://user:password@www.youtube.com/@NASA"}"""),handle))
    }

    private fun fxPost(name: String?="President of the United States",username: String="POTUS") =
        """{"type":"status","provider":"twitter","id":"123","url":"https://x.com/POTUS/status/123","text":"A public announcement.","created_timestamp":1792152000,"author":{"screen_name":"$username"${name?.let {",\"name\":\"$it\""}.orEmpty()}}}"""

    @Test fun fxProfileCanResolvePotusFromItsExplicitValidatedPostAuthorWithoutAnotherRequest(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val adapter=protocolDiscoveryAdapter(Source(Platform.X,"fxembed-api",server.url("/").toString().trimEnd('/')),gateway,routes)!!
            val t=target(Platform.X,"POTUS")
            server.enqueue(MockResponse().setBody("""{"code":200,"results":[${fxPost()}]}"""))
            val profile=adapter.profile(t).profile!!
            assertTrue(profile.displayNameSupplied)
            assertEquals("President of the United States",profile.name)
            val match=MentionNames.find("Thanks @POTUS.",Platform.X,"https://x.com/reader/status/1",routes).single()
            assertEquals("Thanks President of the United States.",MentionNames.present("Thanks @POTUS.",listOf(match),mapOf(match.key.id to profile.name)).text)
            assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody("""{"code":200,"results":[${fxPost(null)}]}"""))
            assertFalse(adapter.profile(t).profile!!.displayNameSupplied)
        }
    }

    @Test fun fxProfilePrefersExactTopLevelUserMetadataAndRejectsWrongUsers(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val adapter=protocolDiscoveryAdapter(Source(Platform.X,"fxembed-api",server.url("/").toString().trimEnd('/')),gateway,routes)!!
            val t=target(Platform.X,"POTUS")
            server.enqueue(MockResponse().setBody("""{"code":200,"user":{"screen_name":"POTUS","name":"The President"},"results":[${fxPost()}]}"""))
            assertEquals("The President",adapter.profile(t).profile!!.name)
            server.enqueue(MockResponse().setBody("""{"code":200,"user":{"username":"somebodyelse","name":"Wrong person"},"results":[${fxPost()}]}"""))
            assertThrows(SourceFailure::class.java){runBlocking {adapter.profile(t)}}
            server.enqueue(MockResponse().setBody("""{"code":200,"results":[${fxPost(username="somebodyelse") }]}"""))
            assertThrows(SourceFailure::class.java){runBlocking {adapter.profile(t)}}
        }
    }
}
