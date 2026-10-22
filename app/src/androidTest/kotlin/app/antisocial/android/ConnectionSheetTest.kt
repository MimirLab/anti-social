package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Quick connection controls use the real repository policy; status examples are local fixtures. */
@RunWith(AndroidJUnit4::class)
class ConnectionSheetTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private lateinit var preferences: String
    private lateinit var originalMemory: FeedMemoryStore
    private lateinit var memoryFile: File
    private lateinit var memoryScope: CoroutineScope
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val postId="99009$suffix"
    private val community="antisocial_conn_${suffix.takeLast(6)}"
    private val post=Post(postId,Platform.REDDIT,
        "https://www.reddit.com/r/$community/comments/$postId/connection_panel/",
        "Connection fixture","The reader stays in place while its connection changes.",publishedAt=System.currentTimeMillis())
    private val target=Target(Platform.REDDIT,TargetKind.THREAD,postId,post.canonicalUrl)
    private val follow=Follow("connection-panel-$suffix",Target(Platform.REDDIT,TargetKind.COMMUNITY,community,"https://www.reddit.com/r/$community"),"Connection fixture")
    private val feedId="following:${Platform.REDDIT.id}:group:${Repository.hash("")}"

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        // Policy assertions exercise the app sheet, not Android's first-use
        // notification permission window. That window has no Compose hierarchy.
        if(android.os.Build.VERSION.SDK_INT>=33)InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ui.activity.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        preferences=repo.prefs.export().toString()
        originalMemory=repo.feedMemory
        ui.runOnIdle {
            model().following()
            model().section("settings")
            repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
            repo.prefs.tor=false;repo.prefs.searchProvider=SearchProvider.DARWIN;repo.prefs.originalFallback=false
            repo.applyNetworkSettings()
        }
        // Connection changes keep the real Following database pipeline intact.
        // Isolate its accepted batch/position from the user's local reading state.
        ui.waitForIdle()
        memoryFile=File(ui.activity.cacheDir,"connection-memory-test-${UUID.randomUUID()}.json")
        memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope,memoryFile)
        runBlocking {repo.feedMemory.awaitReady()}
    }
    @After fun restore() {
        ui.runOnIdle {
            model().following()
            ui.activity.setContent {MaterialTheme {Text("Connection fixture complete")}}
        }
        runBlocking {repo.feedMemory.flush();memoryScope.coroutineContext[Job]?.cancelAndJoin()}
        repo.feedMemory=originalMemory
        runBlocking {
            repo.dao.removeFollow(follow.id)
            repo.dao.removePosts(listOf(repo.key(post)))
            for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(repo.key(post)))
        }
        listOf(memoryFile,File(memoryFile.path+".bak"),File(memoryFile.path+".new")).forEach {it.delete()}
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
            assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
        }
    }
    private fun reader(detail: Boolean=false) {
        runBlocking {
            repo.dao.putFollow(FollowRow(follow.id,follow.target.platform.id,repo.gson.toJson(follow)))
            repo.store(Page(listOf(post)),follow.id,true)
            repo.feedMemory.reconcile(feedId,listOf(repo.key(post)))
            repo.feedMemory.anchor(feedId,repo.key(post),0)
            repo.feedMemory.flush()
        }
        ui.runOnIdle {
            val vm=model();vm.platform(Platform.REDDIT,false);vm.following()
            // Following avoids an automatic live refresh when the native Tor process becomes ready.
            vm.screen.value=ScreenState(Platform.REDDIT,target=target.takeIf {detail},page=Page(listOf(post)).takeIf {detail})
        }
        ui.waitUntil(5_000){ui.onAllNodesWithText(post.body).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(post.body).assertIsDisplayed()
    }
    private fun panel() {ui.onNodeWithTag("Connection status").performClick();ui.onNodeWithTag("Connection sheet").assertIsDisplayed()}
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(350);ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"connection-panel").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun badgeOpensConnectionWithoutLeavingThePostAndSettingsRemainsSeparate() {
        reader(detail=true)
        val vm=model()
        panel()
        assertEquals(target,vm.screen.value.target);assertEquals("feed",vm.screen.value.section)
        ui.onNodeWithTag("Settings title").assertDoesNotExist()
        ui.onNodeWithTag("Content Tor switch").assertIsOff()
        ui.onNodeWithText("Darwin search routes · Direct").assertIsDisplayed()
        evidence("reader-connection-panel")
        ui.onNodeWithContentDescription("Close connection panel").performClick()
        ui.onNodeWithTag("Connection sheet").assertDoesNotExist()
        ui.onNodeWithText(post.body).assertIsDisplayed();assertEquals(target,vm.screen.value.target)
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithTag("Settings title").assertTextEquals("Settings")
    }

    @Test fun allTrafficToggleStaysOpenFailsClosedAndSurvivesRecreation() {
        reader();panel()
        ui.onNodeWithTag("Content Tor switch").performClick().assertIsOn()
        assertTrue(repo.prefs.tor);assertTrue(repo.gateway.currentPolicy().tor)
        ui.onNodeWithTag("Connection sheet").assertIsDisplayed()
        assertEquals("feed",model().screen.value.section)
        ui.runOnIdle {
            // Exercise the not-ready branch before the controller's next status callback.
            // No request is sent, and no actual Tor readiness is claimed by this fixture.
            val before=repo.gateway.currentPolicy()
            try {
                repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
                val failure=runCatching {repo.gateway.client()}.exceptionOrNull()
                assertTrue(failure is IOException);assertTrue(failure!!.message.orEmpty().contains("paused"))
                assertTrue(repo.gateway.currentPolicy().tor)
            }finally {repo.gateway.configure(before)}
        }
        evidence("all-traffic-mode")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("Connection sheet").assertIsDisplayed()
        ui.onNodeWithTag("Content Tor switch").assertIsOn()
        assertTrue(Preferences(ui.activity).tor);assertTrue(repo.gateway.currentPolicy().tor)
        assertEquals("feed",model().screen.value.section)
        ui.onNodeWithContentDescription("Close connection panel").performClick()
        ui.onNodeWithText(post.body).assertIsDisplayed()
    }

    @Test fun turningContentTorOffKeepsBingAndDuckDuckGoOnTheirSeparateTorRoute() {
        reader()
        for(provider in listOf(SearchProvider.DUCKDUCKGO,SearchProvider.BING)) {
            ui.runOnIdle {
                repo.prefs.searchProvider=provider;repo.prefs.originalFallback=true;repo.prefs.tor=false
                repo.applyNetworkSettings()
            }
            panel()
            ui.onNodeWithTag("Content Tor switch").performClick().assertIsOn()
            ui.onNodeWithTag("Content Tor switch").performClick().assertIsOff()
            assertFalse(repo.prefs.tor);assertFalse(repo.gateway.currentPolicy().tor)
            assertTrue(repo.gateway.currentPolicy(NetworkRoute.TOR).tor)
            assertTrue(repo.tor.state.value.enabled);assertFalse(repo.tor.state.value.allTraffic)
            ui.onNodeWithText("${provider.label} · Tor").assertIsDisplayed()
            ui.onNodeWithText("${provider.label} searches always use Tor, even when content connects directly.").performScrollTo().assertIsDisplayed()
            assertEquals("feed",model().screen.value.section)
            if(provider==SearchProvider.DUCKDUCKGO)evidence("search-tor-content-direct")
            ui.onNodeWithContentDescription("Close connection panel").performScrollTo().performClick()
            ui.onNodeWithText(post.body).assertIsDisplayed()
        }
    }

    @Test fun localStatusExamplesDistinguishTorConnectingFromTorReady() {
        var status by mutableStateOf(TorState(true,false,37,"Connecting to Tor · 37%",true))
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
            ConnectionSheet(status,true,SearchProvider.DUCKDUCKGO,true,onContentTor={},onDismiss={})
        }}}
        ui.onNodeWithTag("Content Tor switch").assertIsOn()
        ui.onNodeWithText("DuckDuckGo · Tor").assertIsDisplayed()
        ui.onNodeWithTag("Connection Tor status").performScrollTo().assertTextEquals("Connecting to Tor · 37%")
        evidence("fixture-tor-connecting")
        ui.runOnIdle {status=TorState(true,true,100,"Tor connected for all traffic",true)}
        ui.onNodeWithTag("Connection Tor status").performScrollTo().assertTextEquals("Tor connected for all traffic")
        evidence("fixture-tor-ready")
    }

    @Test fun localProviderExamplesShowDarwinDirectAndDisabledOriginalSearchFallbackHonestly() {
        var provider by mutableStateOf(SearchProvider.DARWIN)
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
            ConnectionSheet(TorState(),false,provider,false,onContentTor={},onDismiss={})
        }}}
        ui.onNodeWithTag("Content Tor switch").assertIsOff()
        ui.onNodeWithText("Darwin search routes · Direct").assertIsDisplayed()
        ui.onNodeWithTag("Connection Tor status").assertDoesNotExist()
        ui.runOnIdle {provider=SearchProvider.DUCKDUCKGO}
        ui.onNodeWithText("DuckDuckGo · disabled").assertIsDisplayed()
        ui.onNodeWithText("Original-site fallback is off. Native social search still follows the content connection.").assertIsDisplayed()
        ui.onNodeWithTag("Connection Tor status").assertDoesNotExist()
        evidence("fixture-fallback-disabled")
    }
}
