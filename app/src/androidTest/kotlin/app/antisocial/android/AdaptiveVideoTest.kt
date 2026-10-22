/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic local streams prove that the packaged native adaptive modules decode
 * audio and video. These are not downloaded playlists or a live-source guarantee.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AdaptiveVideoTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(12)
    private val host="adaptive-video-$suffix.invalid"
    private lateinit var preferences: String
    private lateinit var originalPolicy: NetworkPolicy
    private var originalEvents: ((NetworkEvent)->Unit)?=null
    private lateinit var fixtureDir: File
    private val events=CopyOnWriteArrayList<NetworkEvent>()

    @Before fun isolate()=runBlocking {
        repo.awaitReady()
        preferences=repo.prefs.export().toString()
        originalPolicy=repo.gateway.currentPolicy();originalEvents=repo.gateway.onNetworkEvent
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
        ui.runOnIdle {ui.activity.setContent {Text("Adaptive video fixture")}}
        fixtureDir=File(repo.context.cacheDir,"adaptive-video-fixtures-$suffix").apply {mkdirs()}
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        fun copy(path: String,to: File) {
            val children=assets.list(path).orEmpty()
            if(children.isEmpty()) {
                to.parentFile?.mkdirs()
                assets.open(path).use {input->to.outputStream().use {output->input.copyTo(output)}}
            }else {
                to.mkdirs();children.forEach {copy("$path/$it",File(to,it))}
            }
        }
        copy("adaptive-video",fixtureDir)
        repo.gateway.onNetworkEvent={event->if(event.host==host)events+=event;originalEvents?.invoke(event)}
        // Every byte in these tests must come from a private local file, even
        // though the matching network media URL cannot be reached over Tor.
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
    }

    @After fun restore()=runBlocking {
        ui.runOnIdle {ui.activity.setContent {Text("Adaptive video fixture complete")}}
        fixtureDir.deleteRecursively()
        repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
        repo.prefs.restore(JSONObject(preferences));repo.gateway.onNetworkEvent=originalEvents
        repo.applyNetworkSettings();repo.gateway.configure(originalPolicy)
        scheduleRefresh(repo.context,repo.prefs)
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }

    private fun nativePlayer(): Player? {
        fun find(view: View): PlayerView? {
            if(view is PlayerView)return view
            if(view is ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))?.let {return it}
            return null
        }
        return find(ui.activity.window.decorView)?.player
    }

    private fun play(manifest: String,mime: String,name: String) {
        val media=Media("https://$host/opaque-$name?signature=fixture","video",mimeType=mime)
        val post=Post("adaptive-$name-$suffix",Platform.X,"https://x.com/antisocial_fixture/status/$suffix","Native playback fixture","Synthetic eight-second green video and silent audio.",media=listOf(media))
        val local=File(fixtureDir,manifest)
        assertTrue(local.isFile)
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Column {
            Text("Native ${name.uppercase()} playback")
            VideoCard(media,post,repo,localOnly=true,localFile=local)
        }}}}
        ui.onNodeWithText("Play video").performClick()
        ui.waitUntil(30_000){ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Video ready")).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Video player").assertIsDisplayed()
        ui.runOnIdle {
            val player=requireNotNull(nativePlayer())
            assertNull(player.playerError)
            assertTrue("Adaptive video track is selected",player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
            assertTrue("Adaptive audio track is selected",player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
            assertEquals(160,player.videoSize.width);assertEquals(90,player.videoSize.height)
            assertTrue("Static manifest should expose its eight-second duration",player.duration in 7_500L..8_500L)
            player.pause()
        }
        assertTrue("A local manifest must not probe its network URL",events.isEmpty())
        ui.waitForIdle()
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"video-player").apply {mkdirs()}
        File(folder,"$name-native.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
        screenshot.recycle()
    }

    @Test fun packagedHlsModuleRendersNativeAudioAndVideoWithoutANetworkConnection()=play("local.m3u8",VideoFormats.HLS,"hls")
    @Test fun packagedDashModuleRendersNativeAudioAndVideoWithoutANetworkConnection()=play("dash/local.mpd",VideoFormats.DASH,"dash")
}
