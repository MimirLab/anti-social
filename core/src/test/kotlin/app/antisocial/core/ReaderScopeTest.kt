package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ReaderScopeTest {
    private val video=YouTubeUrls.recognize("https://youtu.be/BaW_jenozKc")!!
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))

    @Test fun linkedVideosOpenIndependentlyOfChosenSocialSites() {
        assertTrue(ReaderScope.canOpen(video));assertTrue(ReaderScope.handles(video,listOf(Platform.X)))
        assertTrue(ReaderScope.handles(video,emptyList()))
        val social=routes.target(Platform.X,"nasa")
        assertTrue(ReaderScope.handles(social,listOf(Platform.X)))
        assertFalse(ReaderScope.handles(social,listOf(Platform.REDDIT)))
    }
    @Test fun videoSupportDoesNotEnableChannelsSearchOrInventedVideoTargets() {
        for(target in listOf(routes.target(Platform.YOUTUBE,"@NASA"),routes.search(Platform.YOUTUBE,"workshop"),video.copy(canonicalUrl="https://www.youtube.com/@NASA"),video.copy(kind=TargetKind.FEED))) {
            assertFalse(ReaderScope.canOpen(target));assertFalse(ReaderScope.handles(target,Platform.entries))
        }
    }
    @Test fun oldChannelFollowsAreInactiveButTheirRecordsRemainIntact() {
        val follow=Follow("old-channel",routes.target(Platform.YOUTUBE,"@NASA"),"NASA")
        assertTrue(follow.enabled);assertFalse(ReaderScope.active(follow));assertEquals("NASA",follow.name)
        assertTrue(ReaderScope.active(Follow("social",routes.target(Platform.X,"nasa"),"NASA")))
    }
    @Test fun linkedAccountDiscoveryDoesNotSuggestVideoChannelSubscriptions() {
        val candidates=Subscriptions.candidates(listOf("https://www.youtube.com/@NASA","https://x.com/NASA"),routes,"Profile links")
        assertEquals(listOf(Platform.X),candidates.map {it.target.platform})
    }
    @Test fun socialVideoSourcesAndOrdinaryRssRemainFeedSources() {
        assertEquals(Platform.entries.toSet()-Platform.YOUTUBE,ReaderScope.platforms.toSet())
        assertTrue(ReaderScope.isFeedSource(Platform.TIKTOK));assertTrue(ReaderScope.isFeedSource(Platform.RSS))
    }
    @Test fun incomingVideosOpenWithoutAYouTubeTabWhileOtherUnselectedSocialLinksRemainExternal(): Unit=runBlocking {
        val incoming=IncomingLinks(routes,UrlCleaner(asset("clean-links.json"))){error("Recognized links need no network expansion")}
        for(url in listOf("https://youtu.be/BaW_jenozKc?si=tracking","https://www.youtube-nocookie.com/embed/BaW_jenozKc")) {
            val result=incoming.resolve(url,setOf(Platform.X)) as IncomingLinkResult.Open
            assertEquals(video.canonicalUrl,result.target.canonicalUrl)
        }
        assertTrue(incoming.resolve("https://www.reddit.com/r/android",setOf(Platform.X)) is IncomingLinkResult.External)
        for(url in listOf("https://www.youtube.com/@NASA","https://www.youtube.com/results?search_query=workshop")) {
            assertEquals(IncomingLinkResult.Unavailable(ReaderScope.VIDEO_ONLY),incoming.resolve(url,setOf(Platform.X)))
        }
    }
    @Test fun aSelectedSocialShortenerCanLeadToLinkedVideoWithoutEnablingAVideoFeed(): Unit=runBlocking {
        var expansions=0
        val incoming=IncomingLinks(routes,UrlCleaner(asset("clean-links.json"))){expansions++;"https://youtu.be/BaW_jenozKc"}
        assertEquals(video, (incoming.resolve("https://t.co/video",setOf(Platform.X)) as IncomingLinkResult.Open).target)
        assertEquals(1,expansions)
    }
}
