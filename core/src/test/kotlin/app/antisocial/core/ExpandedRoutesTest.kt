package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ExpandedRoutesTest {
    private fun routes()=DarwinRoutes(
        JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    )
    @Test fun oldDarwinSelectionWorksWithoutNewServiceKeys() {
        val routes=routes()
        assertEquals(Platform.X,routes.recognize("https://x.com/NASA/status/123")!!.platform)
        assertEquals(Platform.BLUESKY,routes.recognize("https://bsky.app/profile/bsky.app")!!.platform)
        val examples=mapOf(
            Platform.FACEBOOK to "https://m.facebook.com/NASA/posts/123",
            Platform.TRUTH_SOCIAL to "https://truthsocial.com/@realDonaldTrump/123",
            Platform.MASTODON to "https://mstdn.social/@stux/123",
            Platform.LEMMY to "https://lemmy.world/post/123",
            Platform.PIXELFED to "https://pixelfed.social/p/pixelfed/123",
            Platform.MISSKEY to "https://misskey.io/notes/abc123",
            Platform.GETTR to "https://gettr.com/post/abc123",
            Platform.GAB to "https://gab.com/Example/posts/123",
            Platform.TELEGRAM to "https://t.me/telegram/123",
            Platform.LINKEDIN to "https://www.linkedin.com/feed/update/urn:li:activity:123",
            Platform.SNAPCHAT to "https://www.snapchat.com/@nasa/spotlight/abc123"
        )
        for((platform,url) in examples) {
            val found=routes.recognize(url)
            assertNotNull(url,found);assertEquals(url,platform,found!!.platform);assertEquals(url,TargetKind.POST,found.kind)
            assertTrue(ContentIdentity.key(platform,found.canonicalUrl).startsWith("post:"))
        }
    }
    @Test fun explicitFederatedAddressesKeepTheirOwnServerAndRegisterOnlyThatServer() {
        val routes=routes()
        assertNull(routes.recognize("https://social.example.org/@alice"))
        val alice=routes.target(Platform.MASTODON,"@alice@social.example.org")
        assertEquals("https://social.example.org/@alice",alice.canonicalUrl)
        assertEquals("alice@social.example.org",alice.identity)
        assertEquals(alice,routes.recognize(alice.canonicalUrl))
        assertEquals(Platform.MASTODON,routes.recognize("https://social.example.org/@alice/123")!!.platform)
        assertNull(routes.recognize("https://unrelated.example.org/@alice"))
        val photos=routes.target(Platform.PIXELFED,"https://photos.example.org/alice")
        assertEquals("alice@photos.example.org",photos.identity)
        assertEquals("https://photos.example.org/alice",photos.canonicalUrl)
        val community=routes.target(Platform.LEMMY,"!technology@forum.example.org")
        assertEquals(TargetKind.COMMUNITY,community.kind)
        assertEquals("https://forum.example.org/c/technology",community.canonicalUrl)
        assertEquals("c/technology@forum.example.org",community.identity)
        val person=routes.target(Platform.LEMMY,"@alice@forum.example.org")
        assertEquals(TargetKind.PROFILE,person.kind)
        assertEquals("https://forum.example.org/u/alice",person.canonicalUrl)
        assertEquals(Platform.X,routes.target(Platform.MASTODON,"https://x.com/NASA").platform)
    }
    @Test fun restoredFederatedRecordsRegisterWithoutNetworkOrGlobalHostGuessing() {
        val routes=routes();val target=Target(Platform.MISSKEY,TargetKind.PROFILE,"alice@notes.example.org","https://notes.example.org/@alice")
        assertNull(routes.recognize(target.canonicalUrl));routes.register(target)
        assertEquals(target,routes.recognize(target.canonicalUrl))
        assertEquals(Platform.MISSKEY,routes.recognize("https://notes.example.org/notes/a123")!!.platform)
        assertNull(routes.recognize("https://another.example.org/notes/a123"))
        assertNull(routes.recognize("https://user:password@notes.example.org/@alice"))
        assertNull(routes.recognize("https://notes.example.org/login"))
    }
    @Test fun sameFederatedLocalIdNeverMergesAcrossServersOrPostAndCommentKinds() {
        for(platform in listOf(Platform.MASTODON,Platform.MISSKEY,Platform.PIXELFED,Platform.LEMMY)) {
            val path=when(platform){Platform.MASTODON->"/@alice/123";Platform.MISSKEY->"/notes/123";Platform.PIXELFED->"/p/alice/123";else->"/post/123"}
            assertNotEquals(platform.label,ContentIdentity.key(platform,"https://one.example.org$path"),ContentIdentity.key(platform,"https://two.example.org$path"))
            assertEquals(ContentIdentity.key(platform,"https://one.example.org$path"),ContentIdentity.key(platform,"https://one.example.org$path?sort=top"))
        }
        assertNotEquals(ContentIdentity.key(Platform.LEMMY,"https://lemmy.world/post/123"),ContentIdentity.key(Platform.LEMMY,"https://lemmy.world/comment/123"))
        assertEquals(ContentIdentity.key(Platform.MASTODON,"https://mstdn.social/@alice/123"),ContentIdentity.key(Platform.MASTODON,"https://mstdn.social/users/alice/statuses/123"))
        assertNotEquals(ContentIdentity.key(Platform.MASTODON,"https://one.example.org:8443/@alice/123"),ContentIdentity.key(Platform.MASTODON,"https://one.example.org/@alice/123"))
    }
    @Test fun facebookAliasesNormalizeAndThreadRepliesKeepDistinctIdentity() {
        val routes=routes();val root=routes.recognize("https://m.facebook.com/story.php?story_fbid=500&id=100")!!
        assertEquals("https://www.facebook.com/100/posts/500",root.canonicalUrl)
        assertEquals("post:500",ContentIdentity.key(Platform.FACEBOOK,root.canonicalUrl))
        val first=routes.recognize(root.canonicalUrl+"?comment_id=11")!!
        val nested=routes.recognize(root.canonicalUrl+"?comment_id=11&reply_comment_id=12")!!
        assertEquals("comment:500:11",ContentIdentity.key(Platform.FACEBOOK,first.canonicalUrl))
        assertEquals("comment:500:12",ContentIdentity.key(Platform.FACEBOOK,nested.canonicalUrl))
        val post=Post("post:500",Platform.FACEBOOK,root.canonicalUrl,"","Selected post")
        assertTrue(Discussions.target(post,routes,false)!!.canonicalUrl.contains("sort=new"))
        assertTrue(Discussions.target(post,routes,true)!!.canonicalUrl.contains("sort=top"))
        assertEquals("https://www.facebook.com/100",routes.recognize("https://www.facebook.com/profile.php?id=100")!!.canonicalUrl)
    }
    @Test fun telegramPreviewsNormalizeButPrivateInvitesAndToolsAreNotPublicProfiles() {
        val routes=routes()
        assertEquals("https://t.me/telegram/123",routes.recognize("https://t.me/s/telegram/123?before=999")!!.canonicalUrl)
        assertEquals(ContentIdentity.key(Platform.TELEGRAM,"https://t.me/telegram/123"),ContentIdentity.key(Platform.TELEGRAM,"https://t.me/s/Telegram/123"))
        assertNotEquals(ContentIdentity.key(Platform.TELEGRAM,"https://t.me/telegram/123"),ContentIdentity.key(Platform.TELEGRAM,"https://t.me/other_channel/123"))
        for(url in listOf("https://t.me/+privateInvite","https://t.me/joinchat/token","https://t.me/share/url?url=anything","https://t.me/c/123/456"))assertNull(url,routes.recognize(url))
    }
    @Test fun accountDiscoveryRejectsSiteNavigationAndAllowsRealPublicProfiles() {
        val routes=routes()
        val examples=mapOf(Platform.FACEBOOK to "NASA",Platform.TRUTH_SOCIAL to "realDonaldTrump",Platform.MASTODON to "alice@social.example.org",Platform.LEMMY to "@alice@forum.example.org",Platform.PIXELFED to "alice",Platform.MISSKEY to "alice",Platform.GETTR to "joerogan",Platform.GAB to "Example",Platform.TELEGRAM to "telegram",Platform.LINKEDIN to "company/google",Platform.SNAPCHAT to "nasa")
        for((platform,input) in examples)assertTrue(platform.label,PublicSearchAdapter.profileAllowed(routes.target(platform,input)))
        for(url in listOf("https://www.facebook.com/login","https://gab.com/settings","https://www.linkedin.com/company/","https://www.snapchat.com/accounts")) {
            val target=routes.recognize(url);assertTrue(url,target==null||!PublicSearchAdapter.profileAllowed(target))
        }
    }
    @Test fun everyNativeDiscoverySearchKeepsItsQueryWithoutClaimingPublicApiSearchSupport() {
        val routes=routes()
        for(platform in Platform.entries.filterNot {it==Platform.RSS}) {
            val target=routes.search(platform,"space & science")
            val recognized=routes.recognize(target.canonicalUrl)
            assertNotNull(platform.label,recognized)
            assertEquals(platform.label,TargetKind.SEARCH,recognized!!.kind)
            assertEquals(platform.label,target.identity,recognized.identity)
        }
    }
}
