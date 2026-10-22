/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler
import org.schabi.newpipe.extractor.Page as ExtractorPage
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class YouTubeNewPipeTest {
    private val video="dQw4w9WgXcQ"
    private val source=Source(Platform.YOUTUBE,"youtube-newpipe","https://www.youtube.com",role=SourceRole.ORIGINAL)
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private fun routes()=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private fun gateway(events: MutableList<String>)=NetworkGateway(cleaner,object: RequestFilter {
        override fun check(url: String,sourceUrl: String,type: String): FilterDecision {events+="$sourceUrl|$type";return FilterDecision(true,"Fixture never contacts a live source")}
    })
    @Test fun listItemsBecomeNativeCardsWithCanonicalIdentityAndDeferredPlayback() {
        val bridge=YouTubeNewPipe(gateway(mutableListOf()),source,routes())
        val item=StreamInfoItem(0,"https://www.youtube.com/shorts/$video","Public title",StreamType.VIDEO_STREAM).apply {
            uploaderName="Public creator";uploaderUrl="https://www.youtube.com/@creator";shortDescription="Public caption";duration=83
            thumbnails=listOf(Image("https://i.ytimg.com/vi/$video/hqdefault.jpg",360,480,Image.ResolutionLevel.MEDIUM))
        }
        val card=bridge.post(item)!!
        assertEquals(ContentIdentity.key(Platform.YOUTUBE,YouTubeUrls.canonical(video)),card.id);assertEquals(YouTubeUrls.canonical(video),card.canonicalUrl)
        assertEquals("Public creator",card.author);assertEquals("Public title",card.title);assertEquals("Public caption",card.body)
        assertEquals(83000L,card.media.single().durationMs);assertEquals(card.canonicalUrl,card.media.single().url)
        assertEquals(source,card.provenance!!.source);assertNull(card.publishedAt)
        item.url // No inferred thumbnail identity or invented upload timestamp.
    }
    @Test fun originalFallbackUsesTheCallingGatewayAndNeverAnUnboundGlobalClient()=runBlocking {
        val events=CopyOnWriteArrayList<String>();val network=gateway(events)
        val bridge=YouTubeNewPipe(network,source,routes());val target=YouTubeUrls.recognize(YouTubeUrls.canonical(video))!!
        try {bridge.fetch(target);fail("Blocked gateway must prevent extraction")}catch(_: Exception){}
        assertTrue("Extractor requests must enter NetworkGateway",events.isNotEmpty())
        assertTrue(events.all {it.startsWith(target.canonicalUrl+"|")})
        assertTrue(network.protectionStats().blocked>0)
    }
    @Test fun concurrentBridgesKeepTheirOwnGatewayAndSourceContext()=runBlocking {
        val first=CopyOnWriteArrayList<String>();val second=CopyOnWriteArrayList<String>()
        val a=YouTubeUrls.recognize("https://www.youtube.com/@first_creator")!!
        val b=YouTubeUrls.recognize("https://www.youtube.com/@second_creator")!!
        coroutineScope {
            listOf(async {runCatching {YouTubeNewPipe(gateway(first),source,routes()).fetch(a)}},async {runCatching {YouTubeNewPipe(gateway(second),source,routes()).fetch(b)}}).awaitAll()
        }
        assertTrue(first.isNotEmpty());assertTrue(second.isNotEmpty())
        assertTrue(first.all {it.startsWith(a.canonicalUrl+"|")});assertTrue(second.all {it.startsWith(b.canonicalUrl+"|")})
    }
    @Test fun unavailableTorAndDisabledOriginalAccessStopBeforeARequestIsSent()=runBlocking {
        val events=CopyOnWriteArrayList<String>();val tor=gateway(events).apply {configure(NetworkPolicy(tor=true,ready=false))}
        val target=YouTubeUrls.recognize("https://www.youtube.com/@creator")!!
        try {YouTubeNewPipe(tor,source,routes()).fetch(target);fail()}catch(_: Exception){}
        assertTrue(events.isEmpty())
        val disabled=gateway(events).apply {originalFallback=false;registerOriginalUrl(source.root)}
        try {YouTubeNewPipe(disabled,source,routes()).fetch(target);fail()}catch(_: Exception){}
        assertTrue(events.isEmpty())
    }
    @Test fun foreignOrOversizedContinuationIsRejectedBeforeDownloaderUse()=runBlocking {
        val events=mutableListOf<String>();val bridge=YouTubeNewPipe(gateway(events),source,routes())
        val target=YouTubeUrls.recognize("https://www.youtube.com/results?search_query=public")!!
        val foreign="""{"version":1,"sourceKey":"${source.key}","targetUrl":"${target.canonicalUrl}","mode":"search","lanes":[],"page":{"url":"https://foreign.example/collect","id":"public-token","ids":[],"cookies":{}}}"""
        val boundToOther=foreign.replace(target.canonicalUrl,"https://www.youtube.com/results?search_query=other")
        listOf("np1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(foreign.toByteArray()),"np1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(boundToOther.toByteArray()),"np1:"+"a".repeat(25000)).forEach {cursor->
            try {bridge.fetch(target,cursor);fail("Unsafe continuation must fail")}catch(_: IllegalArgumentException){}
        }
        assertTrue(events.isEmpty())
    }
    @Test fun mismatchedCanonicalTargetsCannotChangeVideoDuringExtraction()=runBlocking {
        val events=mutableListOf<String>();val bridge=YouTubeNewPipe(gateway(events),source,routes())
        val target=Target(Platform.YOUTUBE,TargetKind.POST,"AAAAAAAAAAA",YouTubeUrls.canonical(video))
        try {bridge.fetch(target);fail()}catch(_: IllegalArgumentException){}
        assertTrue(events.isEmpty())
    }
    private val channel="UC"+"A".repeat(22)
    private val otherChannel="UC"+"B".repeat(22)
    private fun channelPage(owner: String=channel,url: String="https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",cookies: Map<String,String>?=null)=
        ExtractorPage(url,null,listOf("Public creator","https://www.youtube.com/channel/$owner","VERIFIED"),cookies,"""{"context":{"client":{"clientName":"WEB"}},"continuation":"public-fixture-token"}""".toByteArray())
    private fun channelCursor(target: Target=YouTubeUrls.target("@NASA"),owner: String=channel,page: ExtractorPage=channelPage(owner))=
        YouTubeNewPipe.Cursor(sourceKey=source.key,targetUrl=target.canonicalUrl,mode="channel",lanes=listOf(YouTubeNewPipe.Lane(owner,ChannelTabs.VIDEOS,page)))

    @Test fun relativeReadyVideosTabPersistsAsStableChannelAndTabWithoutSerializingItsRenderer()=runBlocking {
        val events=mutableListOf<String>();val bridge=YouTubeNewPipe(gateway(events),source,routes())
        val target=YouTubeUrls.target("@NASA")
        val ready=ReadyChannelTabListLinkHandler("/@NASA/videos",channel,ChannelTabs.VIDEOS) {_,_->error("A cached renderer must not become part of a continuation")}
        assertEquals("/@NASA/videos",ready.url)
        val value=channelCursor(target).copy(lanes=listOf(YouTubeNewPipe.Lane(channel,ready.contentFilters.single(),channelPage())))
        val encoded=bridge.encode(value)!!
        val json=String(YouTubeCursorWire.decode(encoded))
        assertTrue(json.contains("\"channelId\":\"$channel\""));assertTrue(json.contains("\"tab\":\"videos\""))
        assertFalse(json.contains("/@NASA/videos"));assertFalse(json.contains("handler"));assertTrue(encoded.length<24*1024)
        try {bridge.fetch(target,encoded);fail("Fixture gateway blocks the real continuation request")}catch(_: Exception){}
        assertTrue("A reconstructed standard handler must reach the gateway",events.isNotEmpty())
        assertTrue(events.all {it.startsWith(target.canonicalUrl+"|")})
    }
    @Test fun channelContinuationsRetainStrictOriginCookieAndAttributionBounds() {
        val bridge=YouTubeNewPipe(gateway(mutableListOf()),source,routes())
        val invalid=listOf(
            channelCursor(page=channelPage(url="https://foreign.example/youtubei/v1/browse")),
            channelCursor(page=channelPage(url="/youtubei/v1/browse")),
            channelCursor(page=channelPage(url="https://www.youtube.com/redirect")),
            channelCursor(page=channelPage(cookies=mapOf("CONSENT" to "public-fixture"))),
            channelCursor(page=channelPage(owner=otherChannel)),
            channelCursor(target=YouTubeUrls.target("channel/$otherChannel")),
            channelCursor().copy(lanes=listOf(YouTubeNewPipe.Lane(channel,ChannelTabs.VIDEOS,channelPage()),YouTubeNewPipe.Lane(otherChannel,ChannelTabs.SHORTS,channelPage(otherChannel))))
        )
        invalid.forEach {value->try {bridge.encode(value);fail("Unsafe continuation must fail")}catch(_: IllegalArgumentException){}}
    }
    @Test fun aResolvedProfileMustMatchItsStableIdAndAnExplicitRequestedChannelId() {
        val bridge=YouTubeNewPipe(gateway(mutableListOf()),source,routes())
        val info=ChannelInfo(0,channel,"https://www.youtube.com/channel/$channel","https://www.youtube.com/@NASA","NASA")
        val profile=bridge.channelProfile(info,YouTubeUrls.target("@NASA"))
        assertEquals("channel/$channel",profile.target.identity);assertEquals("NASA",profile.name)
        assertEquals(profile,bridge.channelProfile(info,YouTubeUrls.target("channel/$channel")))
        try {bridge.channelProfile(info,YouTubeUrls.target("channel/$otherChannel"));fail("A different requested stable ID must be rejected")}catch(_: IllegalArgumentException){}
        val foreign=ChannelInfo(0,channel,"https://foreign.example/channel/$channel","https://www.youtube.com/@NASA","NASA")
        try {bridge.channelProfile(foreign,YouTubeUrls.target("@NASA"));fail("An unrelated returned profile must be rejected")}catch(_: IllegalArgumentException){}
    }
    private fun largeChannelPage(seed: Long,tokenBytes: Int): ExtractorPage {
        val token=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(tokenBytes).also {java.util.Random(seed).nextBytes(it)})
        return ExtractorPage("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",null,
            listOf("Public creator","https://www.youtube.com/channel/$channel","VERIFIED"),null,
            """{"context":{"client":{"clientName":"WEB"}},"continuation":"$token"}""".toByteArray())
    }
    @Test fun normalLargeThreeTabContinuationRoundTripsWithoutDiscardingPublicUploads()=runBlocking {
        val events=mutableListOf<String>();val bridge=YouTubeNewPipe(gateway(events),source,routes());val target=YouTubeUrls.target("@NASA")
        val tabs=listOf(ChannelTabs.VIDEOS,ChannelTabs.SHORTS,ChannelTabs.LIVESTREAMS)
        val value=channelCursor(target).copy(lanes=tabs.mapIndexed {index,tab->YouTubeNewPipe.Lane(channel,tab,largeChannelPage(index.toLong(),3000))})
        assertTrue(com.google.gson.Gson().toJson(value).toByteArray().size>18*1024)
        val encoded=bridge.encode(value)!!
        assertTrue(encoded.startsWith("np2:"));assertTrue(encoded.length<=24*1024)
        try {bridge.fetch(target,encoded);fail("Fixture gateway must stop the resumed public read")}catch(_: Exception){}
        assertTrue(events.isNotEmpty())
    }
    @Test fun trulyOversizedValidatedContinuationRetainsThePageAndMarksItPending() {
        val bridge=YouTubeNewPipe(gateway(mutableListOf()),source,routes());val target=YouTubeUrls.target("@NASA")
        val lanes=listOf(ChannelTabs.VIDEOS,ChannelTabs.SHORTS,ChannelTabs.LIVESTREAMS).mapIndexed {index,tab->YouTubeNewPipe.Lane(channel,tab,largeChannelPage(index.toLong(),10500))}
        val post=Post("post:$video",Platform.YOUTUBE,YouTubeUrls.canonical(video),"Public creator","Readable public upload",publishedAt=123,provenance=Provenance(source,123,target.canonicalUrl))
        val page=bridge.channelPage(target,listOf(post),null,lanes)
        assertEquals(listOf(post),page.posts);assertNull(page.next);assertNotNull(page.continuationIssue)
        assertEquals(page.continuationIssue,page.notice)
        assertNotNull(FeedRefresh.pendingReason(page,Platform.YOUTUBE))
        val unsafe=lanes.first().copy(page=channelPage(url="https://foreign.example/youtubei/v1/browse"))
        try {bridge.channelPage(target,listOf(post),null,listOf(unsafe));fail("Unsafe origin must still fail rather than become partial success")}catch(_: IllegalArgumentException){}
    }
}
