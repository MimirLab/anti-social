/* AntiSocial, GPL-3.0-or-later. Native service boundaries, no live source requests. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class YouTubeStoreTest {
    private val repo get()=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo
    private val id=UUID.randomUUID().toString().replace("-","").take(11)
    private val canonical=YouTubeUrls.canonical(id)
    private val url="https://cdn.example.org/$id/video.mp4"
    private val other="https://cdn.example.org/$id/other.mp4"
    private val ghost="https://ghostarchive.org/varchive/$id"
    private val archivedUrl="https://ghostvideo.b-cdn.net/video/$id/$id.mp4"
    private lateinit var settings: String
    private lateinit var policy: NetworkPolicy
    private var fallback=true
    private lateinit var store: YouTubeStore
    private val temporary=mutableListOf<File>()
    private val saved=mutableListOf<String>()
    private val mediaKeys=mutableListOf<String>()
    private data class Read(val id: String,val rejected: Set<String>,val muxedOnly: Boolean)
    private val liveReads=mutableListOf<Read>()
    private val archiveReads=mutableListOf<Read>()
    private fun live(media: Media=Media(url,"video",mimeType="video/mp4"),videoId: String=id)=
        YouTubePlayback(videoId,media,canonical,Source(Platform.YOUTUBE,"fixture","https://frontend.example.org"))
    private fun archive(media: Media=Media(archivedUrl,"video",mimeType="video/mp4"),videoId: String=id)=
        RecoveredYouTubeVideo(videoId,media,ghost,"Ghostarchive",System.currentTimeMillis()-1_000,"Preserved title","Preserved caption","A creator")
    @Before fun isolate(): Unit=runBlocking {
        repo.awaitReady();settings=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();fallback=repo.gateway.originalFallback
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.archiveRecovery=true;repo.prefs.originalFallback=false
        repo.gateway.configure(NetworkPolicy());store=YouTubeStore(repo)
        store.live={video,rejected,muxed->liveReads+=Read(video,rejected,muxed);throw SourceFailure(HealthStatus.UNAVAILABLE,"Public video unavailable")}
        store.archived={video,rejected,muxed->archiveReads+=Read(video,rejected,muxed);archive()}
    }
    @After fun restore(): Unit=runBlocking {
        saved.forEach {repo.dao.removeBookmark(it)};repo.dao.removeMedia(mediaKeys);temporary.forEach {it.delete()};store.clear()
        repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
        repo.prefs.restore(JSONObject(settings));repo.applyNetworkSettings();repo.gateway.configure(policy);repo.gateway.originalFallback=fallback;scheduleRefresh(repo.context,repo.prefs)
    }
    @Test fun publicPlaybackIsCachedWithoutChangingItsPublicIdentity(): Unit=runBlocking {
        store.live={video,rejected,muxed->liveReads+=Read(video,rejected,muxed);live()}
        val first=store.resolve(id);assertFalse(first.recovered);assertEquals(id,first.videoId);assertEquals(url,first.media.url)
        assertEquals(first,store.resolve(id));assertEquals(listOf(Read(id,emptySet(),false)),liveReads)
        assertTrue(archiveReads.isEmpty());assertEquals(canonical,repo.publicUrl(canonical,Platform.YOUTUBE))
    }
    @Test fun unavailablePublicVideoRecoversSameIdAndPassesFailedRoutesToArchives(): Unit=runBlocking {
        val rejected=setOf(url);val result=store.resolve(id,rejected,muxedOnly=true)
        assertTrue(result.recovered);assertEquals(archivedUrl,result.media.url);assertEquals(ghost,result.sourceUrl)
        assertEquals(listOf(Read(id,rejected,true)),liveReads);assertEquals(listOf(Read(id,rejected,true)),archiveReads)
        assertFalse(repo.gateway.originalFallback)
    }
    @Test fun disabledArchiveRecoveryPreservesTheOriginalFailure(): Unit=runBlocking {
        repo.prefs.archiveRecovery=false
        val error=assertThrows(SourceFailure::class.java){runBlocking {store.resolve(id)}}
        assertEquals("Public video unavailable",error.message);assertTrue(archiveReads.isEmpty())
    }
    @Test fun archiveOnlyBypassesAnEarlierCachedLiveStream(): Unit=runBlocking {
        store.live={_,_,_->live()};assertFalse(store.resolve(id).recovered)
        assertTrue(store.resolve(id,archiveOnly=true).recovered);assertEquals(1,archiveReads.size)
    }
    @Test fun aRejectedCachedUrlIsNotReused(): Unit=runBlocking {
        var calls=0;store.live={_,rejected,_->calls++;live(Media(if(url in rejected)other else url,"video",mimeType="video/mp4"))}
        assertEquals(url,store.resolve(id).media.url);assertEquals(other,store.resolve(id,setOf(url)).media.url);assertEquals(2,calls)
    }
    @Test fun wrongIdAndRejectedPublicOutputCannotBecomeCachedPlayback(): Unit=runBlocking {
        store.archived={_,_,_->null}
        store.live={_,_,_->live(videoId="aaaaaaaaaaa")}
        assertThrows(Exception::class.java){runBlocking {store.resolve(id)}}
        store.live={_,_,_->live()}
        assertThrows(Exception::class.java){runBlocking {store.resolve(id,setOf(url))}}
        assertEquals(url,store.resolve(id).media.url)
    }
    @Test fun archiveOutputMustAlsoMatchTheIdAndAvoidFailedTracks(): Unit=runBlocking {
        store.archived={_,_,_->archive(videoId="aaaaaaaaaaa")}
        assertThrows(Exception::class.java){runBlocking {store.resolve(id)}}
        store.archived={_,_,_->archive()}
        assertThrows(Exception::class.java){runBlocking {store.resolve(id,setOf(archivedUrl))}}
    }
    @Test fun offlinePreparationCannotAcceptSplitTracksFromEitherProvider(): Unit=runBlocking {
        val split=Media(url,"video",mimeType="video/webm",audioUrl="https://cdn.example.org/$id/audio.webm",audioMimeType="audio/webm")
        store.live={_,_,_->live(split)};store.archived={_,_,_->archive(split)}
        assertThrows(Exception::class.java){runBlocking {store.resolve(id,muxedOnly=true)}}
        assertNotNull(store.resolve(id).media.audioUrl)
    }
    @Test fun unavailableSelectedTorBlocksBothProvidersBeforeAnyDirectFallback(): Unit=runBlocking {
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        assertThrows(Exception::class.java){runBlocking {store.resolve(id)}}
        assertTrue(liveReads.isEmpty());assertTrue(archiveReads.isEmpty())
        val target=Target(Platform.YOUTUBE,TargetKind.POST,id,canonical)
        assertThrows(Exception::class.java){runBlocking {store.recoverPage(target)}}
        assertTrue(archiveReads.isEmpty())
    }
    @Test fun recoveryPageHonorsTheSettingAndRequiresAYouTubePost(): Unit=runBlocking {
        val target=Target(Platform.YOUTUBE,TargetKind.POST,id,canonical)
        repo.prefs.archiveRecovery=false
        assertThrows(Exception::class.java){runBlocking {store.recoverPage(target)}};assertTrue(archiveReads.isEmpty())
        repo.prefs.archiveRecovery=true
        assertThrows(Exception::class.java){runBlocking {store.recoverPage(target.copy(platform=Platform.FACEBOOK))}}
        assertThrows(Exception::class.java){runBlocking {store.recoverPage(target.copy(kind=TargetKind.PROFILE))}}
        val page=store.recoverPage(target);assertEquals(canonical,page.posts.single().canonicalUrl);assertEquals("Preserved caption",page.posts.single().body)
        assertTrue(RecoveredArchives.valid(page.posts.single()))
    }
    @Test fun networkModeChangesDuringProviderWorkRejectItsLateResult(): Unit=runBlocking {
        store.live={_,_,_->repo.gateway.configure(NetworkPolicy(tor=true,ready=false));live()}
        assertThrows(Exception::class.java){runBlocking {store.resolve(id)}}
        repo.gateway.configure(NetworkPolicy());store.live={_,_,_->live(Media(other,"video",mimeType="video/mp4"))}
        assertEquals(other,store.resolve(id).media.url)
    }
    @Test fun callerCancellationDoesNotTriggerAnArchiveLookup(): Unit=runBlocking {
        store.live={_,_,_->throw CancellationException("Reader left the video")}
        assertThrows(CancellationException::class.java){runBlocking {store.resolve(id)}};assertTrue(archiveReads.isEmpty())
    }
    @Test fun resolvingTransportNeverRewritesSavedCaptionOrProtectedLocalBytes(): Unit=runBlocking {
        val post=Post(id,Platform.YOUTUBE,canonical,"My retained attribution","My retained caption",media=listOf(Media(url,"video",mimeType="video/mp4")))
        val key=repo.key(post);val payload=repo.gson.toJson(post);saved+=key
        val file=File(repo.context.cacheDir,"youtube-store-$id.mp4").apply {writeBytes(byteArrayOf(9,8,7,6))};temporary+=file
        val mediaKey=Repository.hash(url);mediaKeys+=mediaKey
        val record=MediaRow(mediaKey,file.path,file.length(),System.currentTimeMillis(),saved=true)
        repo.dao.putBookmark(BookmarkRow(key,Platform.YOUTUBE.id,payload,System.currentTimeMillis()));repo.dao.putMedia(record)
        assertTrue(store.resolve(id).recovered)
        assertEquals(payload,repo.dao.bookmarksByKeys(listOf(key)).single().payload)
        assertEquals(record,repo.dao.media(mediaKey));assertArrayEquals(byteArrayOf(9,8,7,6),file.readBytes());assertEquals(file,repo.savedMedia(url))
    }
}
