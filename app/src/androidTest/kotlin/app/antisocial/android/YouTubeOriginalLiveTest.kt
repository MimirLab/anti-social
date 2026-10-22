/* AntiSocial, GPL-3.0-or-later. Explicit source-dependent Android runtime check. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Run separately with -e antisocialLiveYouTubeOriginal true. This checks the actual
 * Android NewPipe/Rhino/desugaring path and remote Media3 transport. A source
 * outage is recorded as a failure, never replaced with a local-fixture pass. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YouTubeOriginalLiveTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private var preferences: String?=null
    private var policy: NetworkPolicy?=null
    private var oldEvents: ((NetworkEvent)->Unit)?=null
    private val events=CopyOnWriteArrayList<NetworkEvent>()
    private val status=mutableStateOf("Reading a public YouTube video…")
    private val directory get()=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"youtube-original-live").apply {mkdirs()}
    @After fun restore() {
        if(preferences==null)return
        ui.runOnIdle {ui.activity.setContent {Text("YouTube original runtime check complete")}}
        repo.gateway.onNetworkEvent=oldEvents
        repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
        repo.prefs.restore(JSONObject(preferences!!));repo.applyNetworkSettings()
        policy?.let(repo.gateway::configure);scheduleRefresh(repo.context,repo.prefs)
    }
    private fun playerView(): PlayerView? {
        fun find(view: View): PlayerView? {
            if(view is PlayerView&&view.isShown)return view
            if(view is ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))?.let {return it}
            return null
        }
        return find(ui.activity.window.decorView)
    }
    private fun screenshot(name: String) {
        ui.waitForIdle();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun publicOriginalMetadataAndCombinedStreamRenderNativeFramesWithAudio() {
        Assume.assumeTrue("Explicit live case: use -e antisocialLiveYouTubeOriginal true",InstrumentationRegistry.getArguments().getString("antisocialLiveYouTubeOriginal")=="true")
        runBlocking {withTimeout(30_000){repo.awaitReady()}}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();oldEvents=repo.gateway.onNetworkEvent
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.archiveRecovery=false
        repo.prefs.originalFallback=true;repo.prefs.tor=false;repo.prefs.sponsorBlockEnabled=false;repo.prefs.videoCacheMb=0
        repo.cancelSearchPreparation();repo.tor.enable(false)
        repo.gateway.originalFallback=true;repo.gateway.configure(NetworkPolicy())
        repo.gateway.onNetworkEvent={event->events+=event;oldEvents?.invoke(event)}
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)){Text("YouTube original playback",style=MaterialTheme.typography.headlineSmall);Text(status.value)}}}}}
        val videoId="jNQXAC9IVRw";val canonical=YouTubeUrls.canonical(videoId)
        val started=SystemClock.elapsedRealtime();val deadline=started+180_000L
        fun remaining()=(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        var metadata: Post?=null;var routed: YouTubePlayback?=null;var original: YouTubePlayback?=null
        var failure: String?=null;var frame=false;var audio=false;var video=false;var position=0L
        var finalPlaybackUrl: String?=null;var mimeTypes: List<String> = emptyList()
        try {
            val source=repo.pool.originalSource(Platform.YOUTUBE,Capability.POST)
            val adapter=YouTubeAdapter(source,repo.gateway,repo.routes)
            runBlocking(Dispatchers.IO) {withTimeout(minOf(45_000L,remaining())) {
                val target=requireNotNull(YouTubeUrls.recognize(canonical))
                val page=adapter.fetch(target)
                metadata=page.posts.singleOrNull {ContentIdentity.key(it.platform,it.canonicalUrl)=="post:$videoId"}
                    ?: error("Original extraction did not return the exact requested public video")
                assertEquals(canonical,metadata!!.canonicalUrl);assertTrue(metadata!!.title.isNotBlank())
                assertEquals(SourceRole.ORIGINAL,metadata!!.provenance?.source?.role)
            }}
            runBlocking(Dispatchers.IO) {withTimeout(minOf(45_000L,remaining())) {
                // Exercise Darwin's ordinary route pool first. If a healthy
                // alternate wins, also resolve an explicit permitted original
                // so this test can prove the Android original path precisely.
                routed=repo.pool.youtubePlayback(videoId,originalFallback=true,muxedOnly=true)
                original=if(routed!!.source.role==SourceRole.ORIGINAL)routed else adapter.playback(videoId,muxedOnly=true)
                val selected=requireNotNull(original)
                assertEquals(videoId,selected.videoId);assertEquals(SourceRole.ORIGINAL,selected.source.role)
                assertNull("This runtime check requires a combined audio/video stream",selected.media.audioUrl)
                assertEquals("video/mp4",VideoFormats.mime(selected.media))
                YouTubeStreamProof.check(selected.media,selected.sourceUrl,repo.gateway)
            }}
            val selected=requireNotNull(original);val post=requireNotNull(metadata).copy(media=listOf(selected.media))
            ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("YouTube original playback",style=MaterialTheme.typography.headlineSmall)
                Text(post.title,style=MaterialTheme.typography.titleMedium)
                Text("${post.author} · YouTube",style=MaterialTheme.typography.labelMedium)
                VideoCard(selected.media,post,repo)
            }}}}}
            ui.waitUntil(minOf(10_000L,remaining())) {ui.onAllNodes(hasText("Play video") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("Play video").assertIsEnabled().performClick()
            ui.waitUntil(minOf(90_000L,remaining())) {
                ui.runOnIdle {
                    val player=playerView()?.player
                    audio=player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_AUDIO)==true
                    video=player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO)==true
                    position=player?.currentPosition ?: 0L
                    finalPlaybackUrl=player?.currentMediaItem?.localConfiguration?.uri?.toString()
                    mimeTypes=player?.currentTracks?.groups.orEmpty().filter {it.isSelected}.flatMap {group->(0 until group.length).filter {group.isTrackSelected(it)}.mapNotNull {group.getTrackFormat(it).sampleMimeType}}.distinct()
                    failure=player?.playerError?.message
                }
                frame=ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Video ready")).fetchSemanticsNodes().isNotEmpty()
                frame&&audio&&video&&position>=1000L
            }
            ui.runOnIdle {val player=requireNotNull(playerView()?.player);assertNull(player.playerError);player.pause()}
            assertEquals("An archive or alternate substitution does not prove original transport",selected.media.url,finalPlaybackUrl)
            assertTrue(frame);assertTrue(audio);assertTrue(video)
            assertFalse("The original media transport in this case is direct",repo.gateway.currentPolicy().tor)
            screenshot("original-first-frame-audio-video")
        }catch(error: Throwable) {
            failure=error.message ?: error.javaClass.simpleName
            if(original==null)runCatching {ui.runOnIdle {status.value="Public original unavailable: $failure"}}
            runCatching {screenshot("original-playback-unavailable")}
            throw error
        }finally {
            val report=JSONObject().put("videoId",videoId).put("canonicalUrl",canonical).put("title",metadata?.title)
                .put("metadataSource",metadata?.provenance?.source?.frontend).put("routedSource",routed?.source?.frontend)
                .put("originalSource",original?.source?.frontend).put("originalStreamUrl",original?.media?.url)
                .put("firstFrame",frame).put("audioTrack",audio).put("videoTrack",video).put("positionMs",position)
                .put("finalPlaybackUrl",finalPlaybackUrl).put("originalStreamStillSelected",finalPlaybackUrl==original?.media?.url)
                .put("selectedTrackMimeTypes",org.json.JSONArray(mimeTypes)).put("elapsedMs",SystemClock.elapsedRealtime()-started)
                .put("failure",failure).put("gatewayHosts",org.json.JSONArray(events.map {it.host}.distinct()))
            File(directory,"original-playback-result.json").writeText(report.toString(2))
            File(directory,"gateway-events.json").writeText(repo.gson.toJson(events))
        }
    }
}
