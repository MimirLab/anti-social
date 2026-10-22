/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
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

/** Real native decoder and controls, using only local files with Tor unavailable. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlayerTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(12)
    private val host="video-player-$suffix.invalid"
    private val media=Media("https://$host/clip.mp4","video",description="Local video fixture")
    private val post=Post("video-player-$suffix",Platform.X,"https://x.com/antisocial_video_fixture/status/96$suffix","Public fixture","A video preserved on this device.",media=listOf(media))
    private val events=CopyOnWriteArrayList<NetworkEvent>()
    private lateinit var preferences: String
    private lateinit var originalPolicy: NetworkPolicy
    private var originalEvents: ((NetworkEvent)->Unit)?=null
    private lateinit var fixtureDir: File

    @Before fun isolate()=runBlocking {
        repo.awaitReady()
        preferences=repo.prefs.export().toString();originalPolicy=repo.gateway.currentPolicy();originalEvents=repo.gateway.onNetworkEvent
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
        fixtureDir=File(repo.context.cacheDir,"video-player-fixtures-$suffix").apply {mkdirs()}
        ui.runOnIdle {ui.activity.setContent {Text("Video fixture")}}
        repo.gateway.onNetworkEvent={event->if(event.host==host)events+=event;originalEvents?.invoke(event)}
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
    }

    @After fun restore()=runBlocking {
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.runOnIdle {ui.activity.setContent {Text("Video fixture complete")}}
        fixtureDir.deleteRecursively()
        repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
        repo.prefs.restore(JSONObject(preferences));repo.gateway.onNetworkEvent=originalEvents
        repo.applyNetworkSettings();repo.gateway.configure(originalPolicy);scheduleRefresh(repo.context,repo.prefs)
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }

    private fun videoBytes()=InstrumentationRegistry.getInstrumentation().context.assets.open("video-fixture.mp4").use {it.readBytes()}
    private fun local(bytes: ByteArray=videoBytes())=File(fixtureDir,"clip.mp4").apply {writeBytes(bytes)}

    private fun mount(file: File) {
        ui.runOnIdle {ui.activity.setContent {
            MaterialTheme(colorScheme=darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Saved video",style=MaterialTheme.typography.headlineSmall)
                        Text("Playback stays inside anti social",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        VideoCard(media,post,repo,localOnly=true,localFile=file)
                    }
                }
            }
        }}
        ui.waitForIdle()
    }

    /** Dialog windows are separate roots; inspecting only the activity misses expanded playback. */
    private fun nativePlayers(): List<Player> {
        val found=mutableListOf<Player>()
        fun visit(view: View) {
            if(!view.isShown)return
            if(view is PlayerView) {
                assertFalse("Stock controls stay disabled",view.useController)
                assertFalse("Stock controls must not flash during expansion",view.findViewById<View>(androidx.media3.ui.R.id.exo_controller).isShown)
                view.player?.let {found+=it}
            }
            if(view is ViewGroup)for(index in 0 until view.childCount)visit(view.getChildAt(index))
        }
        val roots=if(Build.VERSION.SDK_INT>=29)WindowInspector.getGlobalWindowViews()else listOf(ui.activity.window.decorView)
        roots.forEach(::visit)
        return found.distinct()
    }
    private fun player(): Player {
        var current: Player?=null
        ui.runOnIdle {current=nativePlayers().singleOrNull()}
        return requireNotNull(current){"Expected one visible native video player"}
    }
    private fun waitForPlaying(expected: Boolean) {
        ui.waitUntil(10_000) {
            var matches=false
            ui.runOnIdle {matches=nativePlayers().singleOrNull()?.let {it.isPlaying==expected&&it.playWhenReady==expected}==true}
            matches
        }
    }
    private fun playAndWaitForFrame() {
        ui.onNodeWithText("Play video").performClick()
        ui.waitUntil(30_000) {ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Video ready")).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Video player").assertIsDisplayed()
        waitForPlaying(true)
    }
    private fun evidence(name: String) {
        ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"video-player").apply {mkdirs()}
        File(directory,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun nativeControlsSeekMutePauseAndKeepTheSamePlayerWhenExpanded() {
        mount(local());playAndWaitForFrame()
        val original=player()
        ui.onNodeWithTag("Video playback toggle").assertContentDescriptionEquals("Pause video").performClick()
        waitForPlaying(false)
        ui.onNodeWithTag("Video sound toggle").assertContentDescriptionEquals("Mute video").performClick()
        ui.runOnIdle {assertEquals(0f,original.volume,0f)}
        ui.onNodeWithTag("Video sound toggle").assertContentDescriptionEquals("Unmute video").performClick()
        ui.runOnIdle {assertTrue(original.volume>0f)}
        ui.onNodeWithTag("Video seek").performTouchInput {click(Offset(width*.62f,height/2f))}
        ui.waitUntil(10_000) {var sought=false;ui.runOnIdle {sought=original.currentPosition>3_000L};sought}
        var beforeExpand=0L
        ui.runOnIdle {beforeExpand=original.currentPosition;assertFalse(original.playWhenReady)}
        evidence("minimal-inline-controls")

        ui.onNodeWithContentDescription("Expand video").performClick()
        ui.onNodeWithTag("Expanded video").assertIsDisplayed()
        assertSame("Expanding must retain playback, buffering and position",original,player())
        ui.runOnIdle {assertFalse(original.playWhenReady);assertTrue(kotlin.math.abs(beforeExpand-original.currentPosition)<=250L)}
        evidence("expanded-local-video")

        ui.onNodeWithContentDescription("Collapse video").performClick()
        ui.onNodeWithTag("Expanded video").assertDoesNotExist()
        assertSame("Collapsing must return the same native player",original,player())
        ui.onNodeWithTag("Video playback toggle").assertContentDescriptionEquals("Play video").performClick()
        waitForPlaying(true)
        assertTrue("Local playback must never need a network route",events.isEmpty())
    }

    @Test fun leavingTheForegroundPausesTheActualPlayerUntilTheUserResumesIt() {
        mount(local());playAndWaitForFrame()
        val original=player()
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertFalse("ON_STOP must pause background video",original.playWhenReady)
            assertFalse(original.isPlaying)
        }
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertSame(original,player());waitForPlaying(false)
        ui.onNodeWithTag("Video playback toggle").assertContentDescriptionEquals("Play video").performClick()
        waitForPlaying(true)
        assertTrue(events.isEmpty())
    }

    @Test fun aFailedLocalVideoOffersRetryAndRecoversWhenValidBytesBecomeAvailable() {
        val file=local("<html><body>This is not a video.</body></html>".toByteArray())
        mount(file)
        ui.onNodeWithText("Play video").performClick()
        ui.waitUntil(20_000) {ui.onAllNodesWithText("Retry video").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("Retry video").assertIsDisplayed()
        file.writeBytes(videoBytes())
        ui.onNodeWithText("Retry video").performClick()
        ui.waitUntil(30_000) {ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Video ready")).fetchSemanticsNodes().isNotEmpty()}
        waitForPlaying(true)
        assertTrue("Retrying a local copy must not fetch replacement content",events.isEmpty())
    }
}
