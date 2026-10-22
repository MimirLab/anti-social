/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class SponsorBlockUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var store: SponsorBlockStore
    private val video=("L"+System.nanoTime().toString().takeLast(10))
    private lateinit var originalRetrieve: suspend (String)->List<SponsorSegment>
    private lateinit var originalPolicy: NetworkPolicy
    private var fixture: File?=null
    @Before fun isolate()=runBlocking {
        repo.awaitReady();preferences=repo.prefs.export().toString()
        repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().remove("sponsorBlockEnabled").remove("sponsorBlockCategories").apply()
        store=repo.sponsorBlock;originalRetrieve=store.retrieve;originalPolicy=repo.gateway.currentPolicy();store.forget(video)
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface {SponsorBlockSettings(store)}}}}
    }
    @After fun restore()=runBlocking {
        ui.runOnIdle {ui.activity.setContent {Text("SponsorBlock fixture complete")}}
        store.forget(video);store.retrieve=originalRetrieve;fixture?.delete()
        repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
        repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(originalPolicy);scheduleRefresh(repo.context,repo.prefs)
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }
    @Test fun everySkipStartsEnabledAndNestedControlsPersistWithoutChangingConnection() {
        val before=repo.prefs.tor
        ui.onNodeWithTag("SponsorBlock enabled").assertIsOn()
        SponsorCategory.entries.forEach {category->
            ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("SponsorBlock category ${category.id}"))
            ui.onNodeWithTag("SponsorBlock category ${category.id}").assertIsOn()
        }
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("SponsorBlock category filler"))
        ui.onNodeWithTag("SponsorBlock category filler").performClick().assertIsOff()
        assertFalse("filler" in Preferences(repo.context).sponsorBlockCategories)
        ui.onNodeWithTag("Settings list").performScrollToIndex(0)
        ui.onNodeWithTag("SponsorBlock enabled").performClick().assertIsOff()
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("SponsorBlock category sponsor"))
        ui.onNodeWithTag("SponsorBlock category sponsor").assertIsNotEnabled()
        assertEquals(before,repo.prefs.tor)
        ui.onNodeWithTag("Settings list").performScrollToIndex(0)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"sponsorblock").apply {mkdirs()}
        File(directory,"native-settings.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val exported=repo.prefs.export()
        assertFalse(exported.getBoolean("sponsorBlockEnabled"))
        repo.prefs.validate(exported)
        val invalid=JSONObject(exported.toString()).put("sponsorBlockCategories",org.json.JSONArray().put("not-a-category"))
        try {repo.prefs.restore(invalid);fail("Invalid categories must not alter state")}catch(_: IllegalArgumentException){}
        assertEquals(exported.toString(),repo.prefs.export().toString())
    }
    @Test fun readOnlySegmentCacheAvoidsRepeatLookupsAndAnOutageNeverBlocksPlayback()=runBlocking {
        var requests=0
        store.retrieve={requests++;listOf(SponsorSegment("fixture","sponsor",1000,2000,10000))}
        assertEquals(1,store.read(video).size);assertEquals(1,store.read(video).size);assertEquals(1,requests)
        val second=SponsorBlockStore(repo)
        second.retrieve={error("Disk cache should avoid this request")}
        assertEquals(1,second.read(video).size)
        store.enabled(false)
        assertTrue(store.read(video).isEmpty());assertEquals(1,requests)
        store.enabled(true);store.forget(video)
        store.retrieve={requests++;throw java.io.IOException("Fixture outage")}
        assertTrue(store.read(video).isEmpty());assertTrue(store.read(video).isEmpty());assertEquals(2,requests)
        assertTrue(store.read("not-an-id").isEmpty());assertEquals(2,requests)
    }
    @Test fun nativeMedia3PlaybackActuallySkipsACachedSegmentWithTorUnavailable()=runBlocking {
        var requests=0
        store.retrieve={requests++;listOf(SponsorSegment("native-fixture","sponsor",0,5000))}
        assertEquals(1,store.read(video).size)
        val file=File(repo.context.cacheDir,"sponsorblock-player-$video.mp4").also {fixture=it}
        InstrumentationRegistry.getInstrumentation().context.assets.open("video-fixture.mp4").use {input->file.outputStream().use {input.copyTo(it)}}
        val media=Media("https://sponsorblock-fixture.invalid/$video.mp4","video")
        val post=Post("post:$video",Platform.YOUTUBE,YouTubeUrls.canonical(video),"Local fixture","Cached skip annotation",media=listOf(media))
        ui.runOnIdle {repo.cancelSearchPreparation();repo.tor.enable(false);repo.gateway.configure(NetworkPolicy(tor=true,ready=false))}
        ui.runOnIdle {ui.activity.setContent {
            MaterialTheme(colorScheme=darkColorScheme()) {Surface {Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("YouTube · saved video")
                VideoCard(media,post,repo,localOnly=true,localFile=file)
            }}}
        }}
        ui.waitForIdle();ui.onNodeWithText("Play video").performClick()
        var native: Player?=null
        fun find(view: View) {
            if(view is PlayerView&&view.isShown)native=view.player
            if(view is ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))
        }
        ui.waitUntil(3_500) {
            var skipped=false
            ui.runOnIdle {find(ui.activity.window.decorView);skipped=native?.currentPosition?.let {it>=5000}==true}
            skipped
        }
        ui.runOnIdle {assertTrue(requireNotNull(native).duration>=5000);assertTrue(requireNotNull(native).currentPosition>=5000);requireNotNull(native).pause()}
        assertEquals("Cached annotations do not contact a service during local-only playback",1,requests)
        assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"sponsorblock").apply {mkdirs()}
        File(directory,"native-skipped-video.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
