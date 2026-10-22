/* AntiSocial, GPL-3.0-or-later. Stock Android decoder, local synthetic fixture only. */
package app.antisocial.android

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
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

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YouTubeReaderTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var fixture: File
    private var paired: ExoPlayer?=null
    @Before fun preserve()=runBlocking {
        repo.awaitReady();preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy()
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.sponsorBlockEnabled=false
        ui.runOnIdle {ui.activity.setContent {Text("YouTube local playback fixture")}}
        fixture=File(repo.context.cacheDir,"youtube-native-${System.nanoTime()}").apply {mkdirs()}
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        for(name in listOf("video-fixture.mp4","youtube-av-fixture.mp4","youtube-audio-fixture.m4a"))assets.open(name).use {input->File(fixture,name).outputStream().use {output->input.copyTo(output)}}
        ui.runOnIdle {repo.cancelSearchPreparation();repo.tor.enable(false);repo.gateway.configure(NetworkPolicy(tor=true,ready=false))}
    }
    @After fun restore() {
        ui.runOnIdle {ui.activity.setContent {Text("YouTube local playback fixture complete")};paired?.release();paired=null}
        fixture.deleteRecursively()
        ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
        repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy);scheduleRefresh(ui.activity,repo.prefs)
    }
    private fun player(): Player? {
        fun find(view: View): PlayerView? {
            if(view is PlayerView)return view
            if(view is ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))?.let {return it}
            return null
        }
        return find(ui.activity.window.decorView)?.player
    }
    @Test fun locallyPreservedYouTubeVideoPlaysNativelyWhileTorIsUnavailable() {
        val canonical="https://www.youtube.com/watch?v=BaW_jenozKc"
        val media=Media(canonical,"video",mimeType="video/mp4")
        val post=Post("post:BaW_jenozKc",Platform.YOUTUBE,canonical,"Public workshop","Preserved public video.",media=listOf(media))
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Column {Text("Saved YouTube video");VideoCard(media,post,repo,localOnly=true,localFile=File(fixture,"youtube-av-fixture.mp4"))}}}}
        ui.onNodeWithText("Play video").performClick()
        ui.waitUntil(30_000){ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Video ready")).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Video player").assertIsDisplayed()
        ui.waitUntil(10_000) {var selected=false;ui.runOnIdle {selected=player()?.let {it.playerError==null&&it.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)&&it.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)}==true};selected}
        ui.runOnIdle {val player=requireNotNull(player());assertNull(player.playerError);assertTrue("The preserved fixture has a selected video track",player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO));assertTrue("The preserved fixture has a selected audio track",player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))}
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"youtube").apply {mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"local-video-unavailable-tor.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
    }
    @Test fun actualMedia3MergingSelectsSeparateLocalVideoAndAudioTracks() {
        val rendered=mutableStateOf(false)
        ui.runOnIdle {
            val source=gatewayPairedMediaSource(FileDataSource.Factory(),MediaItem.fromUri(Uri.fromFile(File(fixture,"video-fixture.mp4"))),Uri.fromFile(File(fixture,"youtube-audio-fixture.m4a")).toString(),"audio/mp4")
            paired=ExoPlayer.Builder(ui.activity).build().apply {
                addListener(object: Player.Listener {override fun onRenderedFirstFrame(){rendered.value=true}})
                setMediaSource(source);prepare();playWhenReady=true
            }
            ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Column {Text("Native paired YouTube transport");MiniVideoPlayer(requireNotNull(paired),rendered.value,false,null,{}, {})}}}
        }
        ui.waitUntil(30_000){ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Video ready")).fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(10_000){var selected=false;ui.runOnIdle {selected=paired?.let {it.playerError==null&&it.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)&&it.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)}==true};selected}
        ui.onNodeWithTag("Video player").assertIsDisplayed()
        ui.runOnIdle {val player=requireNotNull(paired);assertNull(player.playerError);assertTrue(player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO));assertTrue(player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))}
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"youtube").apply {mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"paired-video-audio-native.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
