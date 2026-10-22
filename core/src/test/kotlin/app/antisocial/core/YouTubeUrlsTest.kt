/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class YouTubeUrlsTest {
    private val id="BaW_jenozKc"
    private val canonical="https://www.youtube.com/watch?v=$id"
    private fun routes(): DarwinRoutes {
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        return DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    }
    @Test fun watchShortEmbedLiveAndPrivacyHostsShareTheSameVideoIdentity() {
        for(url in listOf("https://youtu.be/$id?si=tracking","https://www.youtube.com/watch?v=$id&feature=share","https://m.youtube.com/shorts/$id","https://music.youtube.com/watch?v=$id","https://www.youtube-nocookie.com/embed/$id","http://youtube.com/live/$id?si=tracking","https://youtube.com/v/$id")) {
            val target=YouTubeUrls.recognize(url)!!
            assertEquals(Platform.YOUTUBE,target.platform);assertEquals(TargetKind.POST,target.kind)
            assertEquals(id,target.identity);assertEquals(canonical,target.canonicalUrl)
            assertEquals("post:$id",ContentIdentity.key(Platform.YOUTUBE,url))
        }
    }
    @Test fun commentLinksKeepTheirOwnIdentityWhileDroppingPlaybackTracking() {
        val comment="Ugy-public.comment_001"
        val target=YouTubeUrls.recognize("$canonical&lc=$comment&t=42&si=tracking")!!
        assertEquals("$canonical&lc=$comment",target.canonicalUrl)
        assertEquals("comment:$id:$comment",ContentIdentity.key(Platform.YOUTUBE,target.canonicalUrl))
        assertNotEquals(ContentIdentity.key(Platform.YOUTUBE,canonical),ContentIdentity.key(Platform.YOUTUBE,target.canonicalUrl))
    }
    @Test fun videoAndChannelParsingRejectUnrelatedHostsCredentialsPortsAndMalformedIdentities() {
        for(url in listOf("https://youtube.com.evil.example/watch?v=$id","https://user:secret@youtube.com/watch?v=$id","https://youtube.com:8443/watch?v=$id","https://youtube.com/watch?v=too-short","https://youtu.be/$id/another","https://youtube.com/embed/$id/another","https://youtube.com/playlist?list=PLpublic","https://youtube.com/account","https://youtube.com/watch?v=$id&v=abcdefghijk")) {
            assertNull(url,YouTubeUrls.recognize(url))
        }
        assertThrows(IllegalArgumentException::class.java){YouTubeUrls.canonical("bad")}
        assertThrows(IllegalArgumentException::class.java){YouTubeUrls.target("https://other.example/watch?v=$id")}
    }
    @Test fun handlesChannelIdsAndLegacyChannelPathsNormalizeTheirUploadTabs() {
        val channel="UC_x5XG1OV2P6uZZ5FSM9Ttw"
        for((input,identity,url) in listOf(
            Triple("NASA","@NASA","https://www.youtube.com/@NASA"),
            Triple("@NASA","@NASA","https://www.youtube.com/@NASA"),
            Triple(channel,"channel/$channel","https://www.youtube.com/channel/$channel"),
            Triple("https://youtube.com/@NASA/shorts","@NASA","https://www.youtube.com/@NASA"),
            Triple("https://youtube.com/user/NASA/streams","user/NASA","https://www.youtube.com/user/NASA"),
            Triple("https://youtube.com/c/NASA/videos","c/NASA","https://www.youtube.com/c/NASA"),
        )) {
            val target=YouTubeUrls.target(input)
            assertEquals(TargetKind.PROFILE,target.kind);assertEquals(identity,target.identity);assertEquals(url,target.canonicalUrl)
        }
        assertEquals(canonical,YouTubeUrls.target(id,TargetKind.POST).canonicalUrl)
    }
    @Test fun nativeSearchAndIncomingLinksUseTheDarwinIdentityLayer() {
        val routes=routes();val query="battery + repair & teardown"
        val search=routes.search(Platform.YOUTUBE,query)
        assertEquals(TargetKind.SEARCH,search.kind);assertEquals(query,routes.recognize(search.canonicalUrl)!!.identity)
        assertEquals(canonical,routes.recognize("https://youtu.be/$id?si=tracking")!!.canonicalUrl)
        assertEquals(Platform.YOUTUBE,SocialLinkHosts.platform("https://youtube-nocookie.com/embed/$id"))
        assertEquals(canonical,SocialLinkHosts.canonical("https://m.youtube.com/shorts/$id?si=tracking"))
        assertEquals("@NASA",routes.target(Platform.YOUTUBE,"NASA").identity)
        assertFalse(Platform.YOUTUBE in PlatformOrder.defaultSelected)
        assertTrue(ReaderScope.handles(routes.recognize(canonical)!!,PlatformOrder.defaultSelected))
    }
    @Test fun knownReaderLinksBecomeAuthenticPublicLinksWithoutChangingMediaTransport() {
        val routes=routes()
        val source=routes.sources.first {it.platform==Platform.YOUTUBE&&it.frontend=="invidious"}
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val outgoing=OriginalLinks(routes,cleaner)
        assertEquals(canonical,outgoing.url(source.root+"/watch?v=$id"))
        assertEquals(canonical,routes.recognize(source.root+"/watch?v=$id")!!.canonicalUrl)
        assertEquals("A public clip: $canonical",outgoing.text("A public clip: ${source.root}/watch?v=$id"))
    }
}
