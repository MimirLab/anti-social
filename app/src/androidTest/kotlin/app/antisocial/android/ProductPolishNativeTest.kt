package app.antisocial.android

import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ProductPolishNativeTest {
    private fun evidence(name:String) {
        ui.waitForIdle();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=java.io.File(ui.activity.getExternalFilesDir("validation"),"product-polish").apply {mkdirs()}
        java.io.File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val handle="polish_fixture"
    private lateinit var vm:AntiSocialViewModel
    private lateinit var prefs:String
    @Before fun prepare() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()};prefs=repo.prefs.export().toString()
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
            repo.tor.enable(false);repo.prefs.tor=true;repo.prefs.originalFallback=false;repo.gateway.configure(NetworkPolicy(tor=true))
            vm=AntiSocialViewModel(repo)
        }
    }
    @After fun cleanup() {
        if(!::vm.isInitialized)return
        ui.runOnIdle {vm.viewModelScope.cancel()}
        runBlocking {repo.dao.allFollows().filter {it.payload.contains(handle)}.forEach {repo.dao.removeFollow(it.id)}}
        repo.prefs.restore(JSONObject(prefs));repo.applyNetworkSettings(false)
    }
    @Test fun metadataLossAfterUnsubscribeDoesNotRemoveTheLocalSubscriptionAction() {
        val target=repo.routes.target(Platform.X,handle)
        ui.runOnIdle {
            vm.screen.value=ScreenState(Platform.X,target=target,page=Page(emptyList(),profile=Profile(target,"Polish fixture")))
            ui.activity.setContent {MaterialTheme {PageIntro(vm)}}
        }
        ui.onNodeWithTag("Subscribe locally").performClick()
        ui.waitUntil(5000){ui.onAllNodesWithTag("Save subscription").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Save subscription").assertIsEnabled().performClick()
        ui.waitUntil(5000){runBlocking {repo.dao.allFollows().any {it.payload.contains(handle)}}}
        runBlocking {repo.dao.allFollows().filter {it.payload.contains(handle)}.forEach {repo.dao.removeFollow(it.id)}}
        // A source can return readable posts without optional account metadata.
        ui.runOnIdle {vm.screen.value=ScreenState(Platform.X,target=target,page=Page(emptyList()))}
        evidence("subscribe-again-without-profile-metadata")
        ui.onNodeWithTag("Subscribe locally").assertIsDisplayed().performClick()
        ui.waitUntil(5000){ui.onAllNodesWithTag("Save subscription").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Save subscription").assertIsEnabled().performClick()
        ui.waitUntil(5000){runBlocking {repo.dao.allFollows().count {it.payload.contains(handle)}==1}}
    }
    @Test fun subscribeAllAndUnsubscribeAllWorkRepeatedlyAndKeepAnotherBundle() {
        val x=repo.routes.target(Platform.X,handle);val b=repo.routes.target(Platform.BLUESKY,"$handle.test")
        val unrelated=repo.routes.target(Platform.INSTAGRAM,"${handle}_other")
        runBlocking {repo.saveSubscription("Other $handle",listOf(unrelated))}
        ui.runOnIdle {ui.activity.setContent {
            var dialog by remember {mutableStateOf(true)}
            val follows by repo.follows.collectAsState(emptyList())
            MaterialTheme {
                if(dialog)SubscriptionDialog(vm,Platform.X,autoDiscover=true,initialTarget=x,
                    discoverAccounts={AccountDiscovery("Main $handle",listOf(AccountCandidate(x,"Opened",AccountLinkKind.ENTERED),AccountCandidate(b,"Linked",AccountLinkKind.PROFILE)))},onDismiss={dialog=false})
                else {SubscriptionsScreen(vm,follows){dialog=true};TextButton(onClick={dialog=true}){Text("Add fixture again")}}
            }
        }}
        repeat(2) {
            ui.waitUntil(5000){ui.onAllNodesWithTag("Subscribe all").fetchSemanticsNodes().isNotEmpty()}
            if(it==0)evidence("subscribe-all")
            ui.onNodeWithTag("Subscribe all").performClick()
            ui.waitUntil(5000){runBlocking {repo.follows.first().count {it.target==x||it.target==b}==2}}
            ui.onNodeWithTag("Subscriptions list").performScrollToNode(hasContentDescription("Manage Main $handle"))
            ui.onNodeWithContentDescription("Manage Main $handle").performClick()
            if(it==0)evidence("unsubscribe-all")
            ui.onNodeWithTag("Unsubscribe all").assertIsDisplayed().performClick()
            ui.waitUntil(5000){runBlocking {repo.follows.first().none {it.target==x||it.target==b}}}
            assertTrue(runBlocking {repo.follows.first().any {it.target==unrelated}})
            ui.onNodeWithTag("Unsubscribe all").assertDoesNotExist()
            if(it==0)ui.onNodeWithText("Add fixture again").performClick()
        }
    }
}
