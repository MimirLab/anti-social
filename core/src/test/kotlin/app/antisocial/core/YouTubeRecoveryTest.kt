package app.antisocial.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YouTubeRecoveryTest {
    private val id="dQw4w9WgXcQ"
    private val index="https://web.archive.org/__wb/videoinfo?vtype=youtube&vid=$id"
    private val legacy="https://web.archive.org/web/0id_/http://wayback-fakeurl.archive.org/yt/$id"
    private val ghost="https://ghostarchive.org/varchive/$id"
    private val clip="https://ghostvideo.b-cdn.net/video/$id/$id.mp4"
    private val mp4=byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()
    private val webm=byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte(),0,0,0,0)
    private fun payload(url: String,text: String)=Payload(text,url,"application/json",200)
    private fun ghostHtml(source: String=clip)="""<html><h2>An archived video</h2><video><source src="$source" type="video/mp4"></video><p>Uploader: <a href="https://www.youtube.com/channel/UCtest">An author</a></p><p>Original upload date: Sat, 24 Oct 2009 02:00:00 GMT</p><p>Archive date: Fri, 17 Sep 2021 11:35:12 GMT</p><div id="desc">The actual description.</div></html>"""
    private class Transport: YouTubeRecoveryTransport {
        val documents=mutableMapOf<String,Payload>()
        val prefixes=mutableMapOf<String,ByteArray>()
        val requests=mutableListOf<String>()
        val contexts=mutableListOf<Pair<String,String>>()
        var available=true
        var privacy="direct"
        var cancel=false
        override suspend fun text(url: String): Payload {requests+=url;if(cancel)throw CancellationException("fixture");return documents[url] ?: throw IOException("Not found")}
        override suspend fun prefix(url: String,sourceUrl: String): ByteArray {requests+=url;contexts+=url to sourceUrl;return prefixes[url] ?: throw IOException("Not found")}
        override fun ready()=available
        override fun partition()=privacy
    }
    @Test fun exactArchivePlayerIsRecoveredIntoNativeData()=runBlocking {
        val transport=Transport().apply {documents[ghost]=payload(ghost,ghostHtml());prefixes[clip]=mp4}
        val result=YouTubeRecovery(transport){1_000L}.recover(id)!!
        assertEquals("Ghostarchive",result.provider);assertEquals(clip,result.media.url);assertEquals("video/mp4",result.media.mimeType)
        assertEquals("An archived video",result.title);assertEquals("An author",result.author)
        assertEquals(1_631_878_512_000L,result.capturedAt)
        assertEquals(1_256_349_600_000L,result.publishedAt)
        assertEquals("The actual description.",result.body)
        val post=result.asPost(now=1_000L)
        assertEquals(Platform.YOUTUBE,post.platform);assertEquals("https://www.youtube.com/watch?v=$id",post.canonicalUrl)
        assertEquals(ghost,post.contentSourceUrl);assertEquals(ghost,post.provenance!!.finalUrl)
        assertEquals(SourceRole.CACHE,post.provenance!!.source.role)
        assertEquals(listOf(clip to ghost),transport.contexts.filter {it.first==clip})
    }
    @Test fun readableWatchPageAndMetadataOnlyArchiveDoNotCountAsPlayback()=runBlocking {
        val transport=Transport().apply {documents[ghost]=payload(ghost,"<h2>A title</h2><p>Archived metadata</p>")}
        assertNull(YouTubeRecovery(transport).recover(id));assertTrue(transport.requests.size<=8)
        assertNull(YouTubeRecovery.container("<html><video src='gone.mp4'></video></html>".toByteArray()))
    }
    @Test fun htmlErrorWithAnMp4ExtensionIsRejected()=runBlocking {
        val transport=Transport().apply {documents[ghost]=payload(ghost,ghostHtml());prefixes[clip]="<html>Sign in</html>".toByteArray()}
        assertNull(YouTubeRecovery(transport).recover(id))
    }
    @Test fun redirectedArchiveLandingPageAndOtherVideoAreRejected() {
        assertNull(YouTubeRecovery.html(id,payload("https://ghostarchive.org/",ghostHtml()),"Ghostarchive"))
        assertNull(YouTubeRecovery.html(id,payload(ghost,ghostHtml("https://ghostvideo.b-cdn.net/video/aaaaaaaaaaa/aaaaaaaaaaa.mp4")),"Ghostarchive"))
        assertNull(YouTubeRecovery.html(id,payload(ghost,ghostHtml("https://www.youtube.com/embed/$id")),"Ghostarchive"))
    }
    @Test fun waybackMuxedFormatsStayInsideRawArchiveReplays() {
        val text="""{"formats":[{"url":"https://r1.googlevideo.com/videoplayback?video_id=$id&itag=18","timestamp":"20230102030405","mimetype":"video/mp4"}]}"""
        val result=YouTubeRecovery.wayback(id,text).single()
        assertEquals("https://web.archive.org/web/20230102030405id_/https://r1.googlevideo.com/videoplayback?video_id=$id&itag=18",result.media.url)
        assertEquals(1_672_628_645_000L,result.capturedAt);assertNull(result.media.audioUrl)
    }
    @Test fun splitWaybackRecordingRequiresBothVideoAndAudioAndProbesEach()=runBlocking {
        val text="""{"dmux":true,"formats":{"video":[{"url":"https://r1.googlevideo.com/videoplayback?video_id=$id&itag=248","timestamp":"20230102030405","mimetype":"video/webm"}],"audio":[{"url":"https://r1.googlevideo.com/videoplayback?video_id=$id&itag=251","timestamp":"20230102030406","mimetype":"audio/webm"}]}}"""
        val parsed=YouTubeRecovery.wayback(id,text).single()
        assertNotNull(parsed.media.audioUrl);assertEquals("audio/webm",parsed.media.audioMimeType)
        val transport=Transport().apply {documents[index]=payload(index,text);prefixes[parsed.media.url]=webm;prefixes[parsed.media.audioUrl!!]=webm}
        val recovered=YouTubeRecovery(transport).recover(id)!!
        assertEquals(parsed.media,recovered.media);assertEquals(3,transport.requests.size)
        transport.prefixes[parsed.media.audioUrl!!]="<html>Missing audio</html>".toByteArray()
        assertNull(YouTubeRecovery(transport).recover(id))
    }
    @Test fun knownSplitRecordingNeverBecomesSilentlyMuted() {
        assertTrue(YouTubeRecovery.wayback(id,"""{"formats":{"video":[{"url":"https://r1.googlevideo.com/videoplayback?video_id=$id","timestamp":"20230102030405","mimetype":"video/mp4"}]}}""").isEmpty())
        assertTrue(YouTubeRecovery.wayback(id,"""{"formats":[{"url":"https://r1.googlevideo.com/videoplayback?video_id=$id","timestamp":"20230102030405","mimetype":"video/mp4","codec":"h264, Unknwn"}]}""").isEmpty())
    }
    @Test fun invalidArchiveTimestampIdentityAndDestinationAreRejected() {
        fun candidate(time: String="20230102030405",url: String="https://r1.googlevideo.com/videoplayback?video_id=$id")=YouTubeRecovery.wayback(id,"""{"formats":[{"url":"$url","timestamp":"$time","mimetype":"video/mp4"}]}""")
        assertTrue(candidate("20230231030405").isEmpty())
        assertTrue(candidate(url="https://r1.googlevideo.com/videoplayback?video_id=aaaaaaaaaaa").isEmpty())
        assertTrue(candidate(url="https://unrelated.example/video.mp4").isEmpty())
        assertTrue(candidate(url="https://user:password@r1.googlevideo.com/videoplayback").isEmpty())
    }
    @Test fun legacyWaybackEndpointNeedsBinaryProofAndNeverInventsADate()=runBlocking {
        val transport=Transport().apply {prefixes[legacy]=mp4}
        val result=YouTubeRecovery(transport).recover(id)!!
        assertEquals(legacy,result.media.url);assertNull(result.capturedAt);assertNull(result.asPost().recoveredArchive)
        assertEquals(legacy,result.asPost().provenance!!.finalUrl);assertEquals(legacy,result.asPost().contentSourceUrl)
    }
    @Test fun failedPlaybackExcludesTheSameRecordingAndFindsAnother()=runBlocking {
        val transport=Transport().apply {prefixes[legacy]=mp4;documents[ghost]=payload(ghost,ghostHtml());prefixes[clip]=mp4}
        val recovery=YouTubeRecovery(transport)
        assertEquals(legacy,recovery.recover(id)!!.media.url)
        assertEquals(clip,recovery.recover(id,setOf(legacy))!!.media.url)
        assertNull(recovery.recover(id,setOf(legacy,clip)))
        assertEquals(1,transport.requests.count {it==legacy})
    }
    @Test fun archiveOrgMetadataNeedsAnActualMatchedVideoFile() {
        val identifier="youtube-$id";val url="https://archive.org/metadata/$identifier"
        fun record(files: String,extra: String="",source: String="")=payload(url,"""{"metadata":{"identifier":"$identifier","originalurl":"$source","title":"An archive"},"files":$files$extra}""")
        assertTrue(YouTubeRecovery.archiveItem(id,identifier,record("""[{"name":"$id.info.json"},{"name":"$id.jpg"}]""")).isEmpty())
        assertTrue(YouTubeRecovery.archiveItem(id,identifier,record("""[{"name":"another-video.mp4"}]""")).isEmpty())
        val matched=YouTubeRecovery.archiveItem(id,identifier,record("""[{"name":"video-$id.mp4"}]""")).single()
        assertEquals("https://archive.org/download/$identifier/video-$id.mp4",matched.media.url)
        assertTrue(YouTubeRecovery.archiveItem(id,identifier,record("""[{"name":"video-$id.mp4"}]""",",\"is_dark\":true")).isEmpty())
        assertTrue(YouTubeRecovery.archiveItem(id,identifier,record("""[{"name":"../video-$id.mp4"}]""")).isEmpty())
    }
    @Test fun declaredExactSourceAllowsOnlyOneOtherwiseUnnamedVideo() {
        val identifier="youtube-$id";val url="https://archive.org/metadata/$identifier"
        fun record(files: String)=payload(url,"""{"metadata":{"identifier":"$identifier","originalurl":"https://www.youtube.com/watch?v=$id"},"files":$files}""")
        assertEquals(1,YouTubeRecovery.archiveItem(id,identifier,record("""[{"name":"recording.mp4"}]""")).size)
        assertTrue(YouTubeRecovery.archiveItem(id,identifier,record("""[{"name":"recording.mp4"},{"name":"trailer.mp4"}]""")).isEmpty())
    }
    @Test fun preserveTubeMissingColdStorageAndDeletedRecordsAreNotPlayable() {
        val url="https://api.preservetube.com/video/$id"
        fun item(extra: String)=payload(url,"""{"id":"$id","title":"A video","source":"https://cdn.example/video.mp4"$extra}""")
        for(stage in listOf("deleted","cold_storage","soft_delete","pending_delete"))assertNull(YouTubeRecovery.preserve(id,item(",\"deletion_stage\":\"$stage\"")))
        assertNull(YouTubeRecovery.preserve(id,item(",\"disabled\":true")))
        assertNull(YouTubeRecovery.preserve(id,item(",\"error\":\"404\"")))
        assertNotNull(YouTubeRecovery.preserve(id,item(",\"deletion_stage\":null")))
        assertNull(YouTubeRecovery.preserve(id,payload(url,"""{"id":"aaaaaaaaaaa","source":"https://cdn.example/video.mp4"}""")))
    }
    @Test fun failedLookupBacksOffAndNetworkModeGetsItsOwnCache()=runBlocking {
        var now=1_000L;val transport=Transport();val recovery=YouTubeRecovery(transport){now}
        assertNull(recovery.recover(id));val initial=transport.requests.size
        assertTrue(initial<=8);assertNull(recovery.recover(id));assertEquals(initial,transport.requests.size)
        now+=5*60_000L;assertNull(recovery.recover(id));val retried=transport.requests.size;assertEquals(initial*2,retried)
        now+=5*60_000L;assertNull(recovery.recover(id));assertEquals(retried,transport.requests.size)
        transport.privacy="tor";assertNull(recovery.recover(id));assertTrue(transport.requests.size>retried)
    }
    @Test fun cachedSuccessAvoidsRepeatedArchiveRequestsUntilExpiry()=runBlocking {
        var now=1_000L;val transport=Transport().apply {prefixes[legacy]=mp4};val recovery=YouTubeRecovery(transport){now}
        assertNotNull(recovery.recover(id));val initial=transport.requests.size
        assertNotNull(recovery.recover(id));assertEquals(initial,transport.requests.size)
        now+=6*60*60_000L;assertNotNull(recovery.recover(id));assertEquals(initial*2,transport.requests.size)
    }
    @Test fun invalidVideoIdsAndUnavailableTorNeverProbe()=runBlocking {
        val transport=Transport();val recovery=YouTubeRecovery(transport)
        for(bad in listOf("","../../secret","dQw4w9WgXcQ&v=other","https://youtube.com/watch?v=$id"))assertNull(recovery.recover(bad))
        transport.available=false;assertNull(recovery.recover(id));assertTrue(transport.requests.isEmpty())
    }
    @Test fun cancellationPropagatesWithoutCachingAnUnavailableResult()=runBlocking {
        val transport=Transport().apply {cancel=true};val recovery=YouTubeRecovery(transport)
        try {recovery.recover(id);fail("Caller cancellation must propagate")}catch(_: CancellationException){}
        transport.cancel=false;transport.prefixes[legacy]=mp4;assertNotNull(recovery.recover(id))
    }
    @Test fun currentTextAndAttributionSurvivePlaybackRecovery() {
        val current=Post(id,Platform.YOUTUBE,"https://www.youtube.com/watch?v=$id","Current author","Current caption","Current title",1_000L)
        val result=RecoveredYouTubeVideo(id,Media(clip,"video"),ghost,"Ghostarchive",2_000L,"Archive title","Archive caption","Archive author",500L)
        val post=result.asPost(current,3_000L)
        assertEquals("Current author",post.author);assertEquals("Current caption",post.body);assertEquals("Current title",post.title);assertEquals(1_000L,post.publishedAt)
        assertEquals(listOf(result.media),post.media);assertEquals(2_000L,post.recoveredArchive!!.capturedAt)
    }
    @Test fun offlineRecoverySkipsSplitTracksAndUsesItsOwnCache()=runBlocking {
        val text="""{"dmux":true,"formats":{"video":[{"url":"https://r1.googlevideo.com/videoplayback?video_id=$id&itag=248","timestamp":"20230102030405","mimetype":"video/webm"}],"audio":[{"url":"https://r1.googlevideo.com/videoplayback?video_id=$id&itag=251","timestamp":"20230102030406","mimetype":"audio/webm"}]}}"""
        val split=YouTubeRecovery.wayback(id,text).single()
        val transport=Transport().apply {documents[index]=payload(index,text);prefixes[split.media.url]=webm;prefixes[split.media.audioUrl!!]=webm;documents[ghost]=payload(ghost,ghostHtml());prefixes[clip]=mp4}
        val recovery=YouTubeRecovery(transport)
        assertNotNull(recovery.recover(id)!!.media.audioUrl)
        val offline=recovery.recover(id,muxedOnly=true)!!
        assertEquals(clip,offline.media.url);assertNull(offline.media.audioUrl)
        assertEquals(1,transport.requests.count {it==split.media.url})
    }
    @Test fun explicitCacheClearAllowsAnImmediateFreshAttempt()=runBlocking {
        val transport=Transport();val recovery=YouTubeRecovery(transport)
        assertNull(recovery.recover(id));val count=transport.requests.size
        transport.prefixes[legacy]=mp4;assertNull(recovery.recover(id));assertEquals(count,transport.requests.size)
        recovery.clear();assertNotNull(recovery.recover(id));assertTrue(transport.requests.size>count)
    }
}
