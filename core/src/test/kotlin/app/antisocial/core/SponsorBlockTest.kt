/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.core

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SponsorBlockTest {
    private val video="dQw4w9WgXcQ"
    private fun gateway(filter: RequestFilter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})=
        NetworkGateway(UrlCleaner(JsonObject().apply {add("providers",JsonObject())}),filter,true)
    private fun wire(segment: String)= """[{"videoID":"$video","segments":[$segment]}]"""
    private fun segment(id: String="a",start: Double=10.0,end: Double=20.0,category: String="sponsor",action: String="skip",duration: Double=100.0)=
        """{"UUID":"$id","segment":[$start,$end],"category":"$category","actionType":"$action","videoDuration":$duration}"""

    @Test fun canonicalYouTubeFormsAreRecognizedWithoutMistakingProxiesOrForeignLinks() {
        listOf("https://youtube.com/watch?v=$video","https://youtu.be/$video?t=12","https://www.youtube.com/shorts/$video","https://m.youtube.com/live/$video","https://www.youtube-nocookie.com/embed/$video").forEach {assertEquals(video,SponsorBlock.videoId(it))}
        listOf("https://youtube.com.evil.example/watch?v=$video","https://proxy.example/watch?v=$video","https://youtube.com/@$video","https://youtube.com/watch?v=short","https://x.com/$video").forEach {assertNull(SponsorBlock.videoId(it))}
    }
    @Test fun defaultsEnableEveryRangeCategoryAndHighlightWithOnlyHashPrefixInTheRequest() {
        assertEquals(10,SponsorSettings().categories.size)
        assertTrue(SponsorSettings().categories.containsAll(listOf("filler","music_offtopic","hook","poi_highlight")))
        val url=SponsorBlock.requestUrl(video).toHttpUrl()
        assertEquals(4,url.pathSegments.last().length)
        assertEquals(SponsorBlock.hash(video).take(4),url.pathSegments.last())
        assertFalse(url.toString().contains(video));assertNull(url.queryParameter("videoID"))
        assertTrue(url.queryParameter("categories")!!.contains("music_offtopic"))
        assertEquals("[\"skip\",\"mute\",\"poi\"]",url.queryParameter("actionTypes"))
    }
    @Test fun prefixBucketIsFilteredByExactVideoIdAndDoesNotTrustUnrelatedRows() {
        val source="""[{"videoID":"AAAAAAAAAAA","segments":[${segment("other")}]},{"videoID":"$video","segments":[${segment("mine")}]}]"""
        assertEquals(listOf("mine"),SponsorBlock.parse(source,video).map {it.id})
        assertTrue(SponsorBlock.parse(source,"BBBBBBBBBBB").isEmpty())
    }
    @Test fun malformedSegmentsAndFullVideoChapterLabelsNeverTurnIntoWholeVideoSkips() {
        val invalid=listOf(segment(end=5.0),segment(start=-1.0),segment(end=101.1),segment(action="full"),segment(action="chapter"),segment(category="exclusive_access",action="full",start=0.0,end=0.0),segment(category="unknown"),segment(category="poi_highlight"),segment(action="poi"),segment(duration=-1.0),segment().replace("10.0","\"10\""),segment().replace("100.0","\"100\""),segment().replace("\"a\"","\"../a\""))
        invalid.forEach {assertTrue(it,SponsorBlock.parse(wire(it),video).isEmpty())}
        assertTrue(SponsorBlock.parse(wire(segment().dropLast(1)+",\"hidden\":true}"),video).isEmpty())
    }
    @Test fun duplicateIdsAreBoundedAndMuteAndPointSemanticsAreRetained() {
        val parsed=SponsorBlock.parse(wire(listOf(segment(),segment(),segment("mute",action="mute"),segment("point",start=40.0,end=40.0,category="poi_highlight",action="poi")).joinToString(",")),video)
        assertEquals(3,parsed.size);assertEquals("mute",parsed.first {it.id=="mute"}.action)
        assertEquals(40_000L,parsed.first {it.id=="point"}.startMs)
    }
    @Test fun overlappingAndChainedRangesSeekToOneEndWithoutRepeatedJumping() {
        val segments=listOf(SponsorSegment("a","sponsor",1000,3000,10000),SponsorSegment("b","intro",2800,5000,10000),SponsorSegment("c","filler",4900,7000,10000))
        val session=SponsorSkipSession();val skip=session.next(1500,10000,segments)!!
        assertEquals(7000L,skip.toMs);assertEquals(setOf("a","b","c"),skip.segmentIds)
        session.applied(skip)
        assertNull(session.next(1500,10000,segments));assertNull(session.next(7000,10000,segments))
    }
    @Test fun disabledSettingsCategoriesAndChangedArchiveDurationDoNotSkip() {
        val segments=listOf(SponsorSegment("a","sponsor",1000,3000,10000))
        assertNull(SponsorSkipSession().next(1500,10000,segments,SponsorSettings(enabled=false)))
        assertNull(SponsorSkipSession().next(1500,10000,segments,SponsorSettings(categories=emptySet())))
        assertNull(SponsorSkipSession().next(1500,15000,segments))
        assertNotNull(SponsorSkipSession().next(1500,11000,segments))
        assertNull(SponsorSkipSession().next(1500,-1,segments))
    }
    @Test fun highlightJumpsOnlyOnceAtTheBeginningAndNeverBackwardsAfterAUserSeek() {
        val point=listOf(SponsorSegment("a","poi_highlight",40000,40000,100000,"poi"))
        val session=SponsorSkipSession();val skip=session.next(0,100000,point)!!
        assertTrue(skip.highlight);assertEquals(40000L,skip.toMs);session.applied(skip)
        assertNull(session.next(0,100000,point))
        assertNull(SponsorSkipSession().next(8000,100000,point))
        assertNull(SponsorSkipSession().next(50000,100000,point))
    }
    @Test fun muteAnnotationsSaveTimeBySkippingAndUnknownDurationCanUseThePlayableTimeline() {
        val segment=SponsorSegment("mute","sponsor",1000,3000,action="mute")
        assertEquals(3000L,SponsorSkipSession().next(1500,10000,listOf(segment))!!.toMs)
    }
    @Test fun gatewayChecksHashLookupAndNoViewingOrIdentityRequestsAreSent()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody(wire(segment())));server.start()
            val contexts=mutableListOf<String>();val network=gateway(object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {contexts+=sourceUrl;return FilterDecision(false)}})
            assertEquals(1,SponsorBlockClient(network,server.url("/").toString()).read(video).size)
            val request=server.takeRequest();assertEquals("GET",request.method)
            assertTrue(request.path!!.startsWith("/api/skipSegments/${SponsorBlock.hash(video).take(4)}?"));assertFalse(request.path!!.contains(video))
            assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
            assertEquals(listOf(SponsorBlock.API),contexts);assertEquals(1,server.requestCount)
        }
    }
    @Test fun absentSegmentsAreNormalAndTorOrNetworkFilteringFailsClosed()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(404));server.start()
            assertTrue(SponsorBlockClient(gateway(),server.url("/").toString()).read(video).isEmpty())
            val blocked=gateway(object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(true,"fixture")})
            try {SponsorBlockClient(blocked,server.url("/").toString()).read(video);fail()}catch(_: java.io.IOException){}
            val tor=gateway().apply {configure(NetworkPolicy(tor=true,ready=false))}
            try {SponsorBlockClient(tor,server.url("/").toString()).read(video);fail()}catch(_: java.io.IOException){}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun oversizedBucketsAndInvalidIdsAreRejectedBeforeTheyCanBeUsed() {
        try {SponsorBlock.parse(" ".repeat(SponsorBlock.MAX_BYTES.toInt()+1),video);fail()}catch(_: IllegalArgumentException){}
        try {SponsorBlock.requestUrl("bad");fail()}catch(_: IllegalArgumentException){}
    }
}
