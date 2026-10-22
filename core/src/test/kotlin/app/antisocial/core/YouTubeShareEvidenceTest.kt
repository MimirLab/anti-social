/* AntiSocial, GPL-3.0-or-later. Shareable exact content, independent of live availability. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.*
import org.junit.Test

class YouTubeShareEvidenceTest {
    private val id="BaW_jenozKc"
    private val other="abcdefghijk"
    private val url=YouTubeUrls.canonical(id)
    private val now=1_792_000_000_000L
    private val post=Post("post:$id",Platform.YOUTUBE,url,"Workshop","Battery repair","A public repair video",publishedAt=now)
    private val source=Source(Platform.YOUTUBE,"invidious","https://yt-reader.example")
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})
    private val reader: ShareRouteReader=ShareRouteReader(gateway,routes,cleaner,clock={now})
    init {routes.registerPathwayAliases(listOf(source))}
    private fun inspect(body: String,at: String=url,kind: ShareRouteKind=ShareRouteKind.ORIGINAL,selected: Source?=null)=reader.inspect(Payload(body,at,"text/html",200),ShareRouteCandidate(post,at,kind,selected))
    private fun player(video: String=id,status: String="OK",reason: String="",streams: Boolean=true)="""<html><title>Public repair</title><script>var ytInitialPlayerResponse = {"videoDetails":{"videoId":"$video","title":"A public repair video"},"playabilityStatus":{"status":"$status","reason":"$reason"}${if(streams)",\"streamingData\":{\"formats\":[{\"itag\":18,\"mimeType\":\"video/mp4\",\"url\":\"https://media.googlevideo.com/videoplayback?video_id=$video\"}]}"else ""}};</script></html>"""
    @Test fun originalRequiresExactVideoPlayableDataRatherThanAnOgOrPlayerShell() {
        assertEquals(ShareRouteStatus.READABLE,inspect(player()).status)
        assertNotEquals(ShareRouteStatus.READABLE,inspect(player(other)).status)
        assertNotEquals(ShareRouteStatus.READABLE,inspect(player(streams=false)).status)
        assertNotEquals(ShareRouteStatus.READABLE,inspect("<meta property='og:url' content='$url'><meta property='og:title' content='Public repair'><div id=player></div>").status)
    }
    @Test fun loginRegionAndTransportFailureDoNotPretendTheVideoWasDeleted() {
        assertEquals(ShareRouteStatus.LOGIN_REQUIRED,inspect(player(status="LOGIN_REQUIRED",reason="Sign in to view")).status)
        assertNotEquals(ShareRouteStatus.NOT_FOUND,inspect(player(status="UNPLAYABLE",reason="The uploader has not made this video available in your country")).status)
        assertEquals(ShareRouteStatus.NOT_FOUND,inspect(player(status="ERROR",reason="This video has been removed by the uploader")).status)
    }
    @Test fun invidiousNeedsItsExactCanonicalVideoAndActualNativeVideoMarkup() {
        val at=source.root+"/watch?v=$id"
        val body="<link rel=canonical href='$url'><h1>Public repair</h1><video><source src='/videoplayback?id=$id' type='video/mp4'></video>"
        assertEquals(ShareRouteStatus.READABLE,inspect(body,at,ShareRouteKind.FRONTEND,source).status)
        assertNotEquals(ShareRouteStatus.READABLE,inspect(body.replace(url,YouTubeUrls.canonical(other)),at,ShareRouteKind.FRONTEND,source).status)
        assertNotEquals(ShareRouteStatus.READABLE,inspect("<link rel=canonical href='$url'><h1>Public repair</h1><video></video>",at,ShareRouteKind.FRONTEND,source).status)
    }
    @Test fun ghostAndHobuneRequireTheSameIdInTheirActualMediaPath() {
        for(at in listOf("https://ghostarchive.org/varchive/$id","https://hobune.stream/videos/$id")) {
            val host=at.toHttpUrlOrNull()!!.host
            val body="<h1>Public repair</h1><video src='https://$host/video/$id/$id.mp4'></video>"
            assertEquals(ShareRouteStatus.READABLE,inspect(body,at,ShareRouteKind.ARCHIVE).status)
            assertNotEquals(ShareRouteStatus.READABLE,inspect(body.replace("/$id/$id.mp4","/$other/$other.mp4"),at,ShareRouteKind.ARCHIVE).status)
            assertNotEquals(ShareRouteStatus.READABLE,inspect("<h1>Archive homepage</h1>",at,ShareRouteKind.ARCHIVE).status)
        }
    }
    @Test fun archiveOrgDetailsNeedAnExactRecordingRatherThanAnyDownloadedVideo() {
        val at="https://archive.org/details/youtube-$id"
        val body="<h1>Public repair</h1><video src='https://archive.org/download/youtube-$id/repair-$id.mp4'></video>"
        assertEquals(ShareRouteStatus.READABLE,inspect(body,at,ShareRouteKind.ARCHIVE).status)
        assertNotEquals(ShareRouteStatus.READABLE,inspect(body.replace("repair-$id.mp4","unrelated.mp4"),at,ShareRouteKind.ARCHIVE).status)
        val playlist="""<input id="js-play8-playlist" value='[{"sources":[{"file":"/download/youtube-$id/repair-$id.mp4","type":"mp4"}]}]'>"""
        assertEquals(ShareRouteStatus.READABLE,inspect(playlist,at,ShareRouteKind.ARCHIVE).status)
    }
    @Test fun waybackBinaryProofBindsTheFinalRedirectAndActualContainerBytes() {
        val fake="https://web.archive.org/web/20250101000000id_/https://wayback-fakeurl.archive.org/yt/$id"
        val google="https://web.archive.org/web/20250101000000id_/https://media.googlevideo.com/videoplayback?video_id=$id"
        val legacy="https://web.archive.org/web/0id_/http://wayback-fakeurl.archive.org/yt/$id"
        val prefix=byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()
        for(at in listOf(fake,google,legacy)) {
            val candidate=ShareRouteCandidate(post,at,ShareRouteKind.ARCHIVE)
            assertTrue(YouTubeShareEvidence.binaryReplay(post,at,now));assertEquals(ShareRouteStatus.READABLE,YouTubeShareEvidence.binary(prefix,at,candidate,now).status)
            assertNotEquals(ShareRouteStatus.READABLE,YouTubeShareEvidence.binary(prefix,at.replace(id,other),candidate,now).status)
            assertNotEquals(ShareRouteStatus.READABLE,YouTubeShareEvidence.binary("<html>unavailable</html>".toByteArray(),at,candidate,now).status)
        }
        assertEquals(ShareRouteStatus.READABLE,YouTubeShareEvidence.binary(prefix,fake,ShareRouteCandidate(post,legacy,ShareRouteKind.ARCHIVE),now).status)
        assertFalse(YouTubeShareEvidence.binaryReplay(post,fake.replace("20250101000000","20990101000000"),now))
    }
    @Test fun failedOriginalCanShareTheVerifiedExactReaderOrArchiveWithoutRewritingIt(): Unit=runBlocking {
        val archive="https://ghostarchive.org/varchive/$id"
        val fetched=post.copy(provenance=Provenance(source,now,source.root+"/api/v1/videos/$id"))
        val resolver=ShareRouteResolver(routes,cleaner,{candidate->ShareRouteCheck(if(candidate.kind==ShareRouteKind.ORIGINAL)ShareRouteStatus.NOT_FOUND else ShareRouteStatus.READABLE,candidate.url)},clock={now})
        assertEquals(source.root+"/watch?v=$id",(resolver.resolve(fetched) as ShareRouteResult.Available).url)
        val recovered=post.copy(recoveredArchive=ArchiveRecovery("Ghostarchive",now-1000,archive))
        assertEquals(archive,(resolver.resolve(recovered) as ShareRouteResult.Available).url)
        assertFalse(YouTubeShareEvidence.archive(post,archive.replace(id,other),now))
        assertFalse("piped" in ShareRouteResolver.htmlFrontends)
    }
}
