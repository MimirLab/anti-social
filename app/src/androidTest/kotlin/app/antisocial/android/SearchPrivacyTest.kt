package app.antisocial.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchPrivacyTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    @Test fun liveMixedConnectionHasSeparateTorAndDirectExits(): Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveSearchTransport")=="true")
        repo.awaitReady()
        val previous=repo.prefs.tor
        try {
            repo.prefs.tor=false
            withContext(Dispatchers.Main){repo.tor.enable(false,searches=true)}
            withTimeout(90_000){repo.tor.state.first {it.ready}}
            assertFalse(repo.gateway.currentPolicy().tor)
            suspend fun isTor(route: NetworkRoute)=com.google.gson.JsonParser.parseString(repo.gateway.text("https://check.torproject.org/api/ip",route=route).text).asJsonObject["IsTor"].asBoolean
            assertTrue(isTor(NetworkRoute.TOR));assertFalse(isTor(NetworkRoute.DEFAULT))
            android.util.Log.i("AntiSocialSearchAudit","Mixed transport verified: protected search uses a Tor exit; default content uses the direct exit")
        }finally {repo.prefs.tor=previous;withContext(NonCancellable+Dispatchers.Main){repo.applyNetworkSettings()}}
    }
    @Test fun asynchronousTorStatusRepliesKeepTheirRequestIdentity()=runBlocking {
        repo.awaitReady()
        val connected=CompletableDeferred<android.os.IBinder>()
        val all=CompletableDeferred<Unit>()
        val received=java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val replies=android.os.Messenger(android.os.Handler(android.os.Looper.getMainLooper()){reply->
            if(reply.what==1){received+=reply.arg1;if(received.size==20)all.complete(Unit)}
            true
        })
        val connection=object: android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?,binder: android.os.IBinder?){if(binder!=null)connected.complete(binder)}
            override fun onServiceDisconnected(name: android.content.ComponentName?){}
        }
        val bound=repo.context.bindService(android.content.Intent(repo.context,AntiSocialTorService::class.java),connection,android.content.Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        try {
            val remote=android.os.Messenger(withTimeout(10_000){connected.await()})
            for(epoch in 1001..1020)remote.send(android.os.Message.obtain(null,1,epoch,0).apply {replyTo=replies})
            withTimeout(20_000){all.await()}
            assertEquals((1001..1020).toSet(),received.toSet())
        }finally {repo.context.unbindService(connection)}
    }
    @Test fun searchTorFailsClosedWithoutBlockingDirectContent() {
        runBlocking {repo.awaitReady()}
        val full=repo.prefs.tor;val provider=repo.prefs.searchProvider;val fallback=repo.prefs.originalFallback
        try {
            ui.runOnUiThread {repo.tor.enable(false);repo.gateway.configureTor(NetworkPolicy(tor=true))}
            assertFalse(repo.gateway.currentPolicy().tor)
            assertEquals(java.net.Proxy.Type.DIRECT,repo.gateway.client().proxy!!.type())
            val before=repo.gateway.protectionStats()
            for(url in listOf("https://html.duckduckgo.com/html/?q=fixture","https://www.bing.com/search?q=fixture"))assertThrows(java.io.IOException::class.java){runBlocking {repo.gateway.text(url)}}
            assertEquals(before,repo.gateway.protectionStats())
            repo.prefs.tor=false;repo.prefs.searchProvider=SearchProvider.DUCKDUCKGO;repo.prefs.originalFallback=true
            ui.runOnUiThread {repo.applyNetworkSettings()}
            assertTrue(repo.tor.state.value.enabled);assertFalse(repo.tor.state.value.allTraffic);assertFalse(repo.gateway.currentPolicy().tor)
        }finally {repo.prefs.tor=full;repo.prefs.searchProvider=provider;repo.prefs.originalFallback=fallback;ui.runOnUiThread {repo.applyNetworkSettings()}}
    }
    @Test fun nativeProviderChoiceSurvivesRecreationAndKeepsScopeVisible() {
        runBlocking {repo.awaitReady()}
        val full=repo.prefs.tor;val provider=repo.prefs.searchProvider;val fallback=repo.prefs.originalFallback
        try {
            repo.prefs.tor=false;repo.prefs.originalFallback=true
            ui.runOnUiThread {repo.applyNetworkSettings()}
            fun settings() {
                if(ui.onAllNodesWithTag("Search provider duckduckgo").fetchSemanticsNodes().isEmpty()) {
                    if(ui.onAllNodesWithTag("Settings destination search").fetchSemanticsNodes().isEmpty())ui.onNodeWithContentDescription("Settings").performClick()
                    ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Settings destination search"))
                    ui.onNodeWithTag("Settings destination search").performClick()
                }
            }
            settings()
            ui.onNodeWithTag("Search provider duckduckgo").performClick().assertIsSelected()
            assertEquals(SearchProvider.DUCKDUCKGO,Preferences(ui.activity).searchProvider)
            assertFalse(repo.gateway.currentPolicy().tor)
            ui.onNodeWithText("Search through Tor").assertIsDisplayed()
            ui.activityRule.scenario.recreate()
            settings()
            ui.onNodeWithTag("Search provider duckduckgo").assertIsSelected()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("DuckDuckGo",substring=false))
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val dir=requireNotNull(ui.activity.getExternalFilesDir("validation")).apply {mkdirs()}
            java.io.File(dir,"search-provider-settings.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            assertThrows(IllegalArgumentException::class.java){repo.prefs.validate(org.json.JSONObject("""{"searchProvider":"unknown"}"""))}
            ui.onNode(hasScrollAction()).performScrollToNode(hasTestTag("Search provider darwin"))
            ui.onNodeWithTag("Search provider darwin").performClick()
            assertFalse(repo.tor.state.value.enabled)
        }finally {repo.prefs.tor=full;repo.prefs.searchProvider=provider;repo.prefs.originalFallback=fallback;ui.runOnUiThread {repo.applyNetworkSettings()}}
    }
}
