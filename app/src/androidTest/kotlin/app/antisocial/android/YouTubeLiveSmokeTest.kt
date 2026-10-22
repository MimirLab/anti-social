/* AntiSocial, GPL-3.0-or-later. Explicit source-dependent archive/player smoke test. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
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

/** Run separately with -e antisocialLiveYouTube true. An unavailable archive or
 * decoder is a recorded failure when explicitly requested, never an offline pass. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YouTubeLiveSmokeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private var preferences: String?=null
    private var policy: NetworkPolicy?=null
    private var oldEvents: ((NetworkEvent)->Unit)?=null
    private val events=CopyOnWriteArrayList<NetworkEvent>()
    private val status=mutableStateOf("Looking for an archived recording…")
    private val directory get()=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"youtube-live").apply {mkdirs()}
    @After fun restore() {
        if(preferences==null)return
        ui.runOnIdle {ui.activity.setContent {Text("YouTube live archive check complete")}}
        repo.gateway.onNetworkEvent=oldEvents
        ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences!!))
        repo.applyNetworkSettings();policy?.let(repo.gateway::configure);scheduleRefresh(repo.context,repo.prefs)
    }
    private fun mainPlayerView(): PlayerView? {
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
    @Test fun exactArchivedVideoRendersNativeFramesAndSelectsAudioThroughTheGateway() {
        Assume.assumeTrue("Explicit live case: use -e antisocialLiveYouTube true",InstrumentationRegistry.getArguments().getString("antisocialLiveYouTube")=="true")
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();oldEvents=repo.gateway.onNetworkEvent
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.archiveRecovery=true;repo.prefs.originalFallback=false;repo.prefs.tor=false;repo.prefs.sponsorBlockEnabled=false;repo.prefs.videoCacheMb=0
        repo.gateway.originalFallback=false;repo.gateway.configure(NetworkPolicy());repo.gateway.onNetworkEvent={event->events+=event;oldEvents?.invoke(event)}
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)){Text("YouTube archive playback",style=MaterialTheme.typography.headlineSmall);Text(status.value)}}}}}
        val started=SystemClock.elapsedRealtime();var result: RecoveredYouTubeVideo?=null;var failure: String?=null;var frame=false;var audio=false;var video=false;var position=0L;var finalPlaybackUrl: String?=null;var trackMimeTypes: List<String> = emptyList()
        try {
            result=runBlocking(Dispatchers.IO){withTimeout(45_000){YouTubeRecovery(repo.gateway).recover("dQw4w9WgXcQ")}}
            val recovered=requireNotNull(result){"No verified playable recording was exposed by the archive sources within the bounded lookup"}
            assertEquals("dQw4w9WgXcQ",recovered.videoId)
            val post=recovered.asPost();assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ",post.canonicalUrl)
            ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("YouTube archive playback",style=MaterialTheme.typography.headlineSmall);Text(recovered.provider,style=MaterialTheme.typography.labelMedium)
                Text(recovered.title.ifBlank {"Archived YouTube recording"},style=MaterialTheme.typography.titleMedium)
                VideoCard(recovered.media,post,repo)
            }}}}}
            ui.waitUntil(10_000){ui.onAllNodes(hasText("Play video") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("Play video").assertIsEnabled().performClick()
            ui.waitUntil(90_000) {
                ui.runOnIdle {
                    val player=mainPlayerView()?.player
                    audio=player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_AUDIO)==true
                    video=player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO)==true
                    position=player?.currentPosition ?: 0L
                    finalPlaybackUrl=player?.currentMediaItem?.localConfiguration?.uri?.toString()
                    trackMimeTypes=player?.currentTracks?.groups.orEmpty().filter {it.isSelected}.flatMap {group->(0 until group.length).filter {group.isTrackSelected(it)}.mapNotNull {group.getTrackFormat(it).sampleMimeType}}.distinct()
                    failure=player?.playerError?.message
                }
                frame=ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Video ready")).fetchSemanticsNodes().isNotEmpty()
                frame&&audio&&video&&position>=1_000L
            }
            ui.runOnIdle {val player=requireNotNull(mainPlayerView()?.player);assertNull(player.playerError);player.pause()}
            screenshot("archive-first-frame-audio-video")
            assertTrue(frame);assertTrue(audio);assertTrue(video)
        }catch(error: Throwable) {
            failure=error.message ?: error.javaClass.simpleName
            if(result==null)ui.runOnIdle {status.value="Archive lookup unavailable: $failure"}
            runCatching {screenshot("archive-playback-unavailable")}
            throw error
        }finally {
            val report=JSONObject().put("videoId","dQw4w9WgXcQ").put("provider",result?.provider).put("sourceUrl",result?.sourceUrl)
                .put("splitTracks",result?.media?.audioUrl!=null).put("firstFrame",frame).put("audioTrack",audio).put("videoTrack",video).put("positionMs",position)
                .put("finalPlaybackUrl",finalPlaybackUrl).put("selectedTrackMimeTypes",org.json.JSONArray(trackMimeTypes)).put("initialRecordingStillSelected",finalPlaybackUrl==result?.media?.url)
                .put("elapsedMs",SystemClock.elapsedRealtime()-started).put("failure",failure).put("gatewayHosts",org.json.JSONArray(events.map {it.host}.distinct()))
            File(directory,"archive-playback-result.json").writeText(report.toString(2))
            File(directory,"gateway-events.json").writeText(repo.gson.toJson(events))
        }
    }
}
