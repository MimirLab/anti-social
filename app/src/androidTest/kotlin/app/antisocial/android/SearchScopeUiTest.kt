package app.antisocial.android

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Native scope controls exercise local fixtures; no public source receives these searches. */
@RunWith(AndroidJUnit4::class)
class SearchScopeUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private var automationFlags=0
    private val models=mutableListOf<AntiSocialViewModel>()
    private val targets=mutableSetOf<Target>()
    private data class Submission(val platform: Platform,val query: String,val searching: Boolean,val scope: SearchScope?)

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString()
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val service=automation.serviceInfo
        automationFlags=service.flags
        service.flags=service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo=service
    }
    @After fun restore() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply {flags=automationFlags}
        ui.runOnIdle {
            models.forEach {it.following();it.viewModelScope.cancel()}
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        runBlocking {
            targets.forEach {target->
                val key=repo.pageKey(target)
                repo.dao.media(key)?.let {File(it.path).delete()}
                repo.dao.removeMedia(listOf(key))
            }
        }
    }
    private fun dialog(platform: Platform,context: Target?=null): AtomicReference<Submission?> {
        val submitted=AtomicReference<Submission?>(null)
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
            SourceDialog(platform,true,initialTarget=context,onDismiss={}) {p,query,_,_,searching,scope->submitted.set(Submission(p,query,searching,scope))}
        }}}
        return submitted
    }
    private fun choose(kind: SearchScopeKind,value: String) {
        ui.onNodeWithTag("Search scope").performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Search scope sheet").fetchSemanticsNodes().isNotEmpty()}
        waitForKeyboard(false)
        ui.onNodeWithTag("Search scope option ${kind.name}").performClick()
        ui.onNodeWithTag("Search scope value").performTextInput(value)
        ui.onNodeWithTag("Apply search scope").performScrollTo().performClick()
        ui.onNodeWithTag("Search scope sheet").assertDoesNotExist()
    }
    private fun evidence(name: String) {
        // Android IME/window animations are outside Compose's idling resources.
        ui.waitForIdle();android.os.SystemClock.sleep(450);ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"search-scopes").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun waitForKeyboard(shown: Boolean) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitUntil(5_000){automation.windows.any {it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}==shown}
        android.os.SystemClock.sleep(350);ui.waitForIdle()
    }

    @Test fun subredditScopeUsesOnePillAndPreservesTheQuery() {
        val submitted=dialog(Platform.REDDIT)
        ui.onNodeWithTag("Search scope").assertTextContains("Everywhere")
        ui.onNodeWithTag("Search scope option COMMUNITY").assertDoesNotExist()
        ui.onNodeWithTag("Content query").performTextInput("battery life")
        waitForKeyboard(true)
        ui.onNodeWithTag("Submit content query").assertIsDisplayed()
        evidence("reddit-search-keyboard")
        choose(SearchScopeKind.COMMUNITY,"r/Android")
        waitForKeyboard(false)
        ui.onNodeWithTag("Search scope").assertTextContains("r/android")
        ui.onNodeWithTag("Content query").assertTextContains("battery life")
        evidence("reddit-scoped-search")
        ui.onNodeWithTag("Submit content query").performClick()
        assertEquals(Submission(Platform.REDDIT,"battery life",true,SearchScope(SearchScopeKind.COMMUNITY,"android")),submitted.get())
    }

    @Test fun communityContextIsVisibleAndCanReturnToEverywhere() {
        val context=repo.routes.recognize("https://www.reddit.com/r/Android/")!!
        val submitted=dialog(Platform.REDDIT,context)
        ui.onNodeWithTag("Search scope").assertTextContains("r/android")
        ui.onNodeWithTag("Search scope").performClick()
        ui.onNodeWithTag("Search scope option COMMUNITY").assertIsSelected()
        evidence("reddit-scope-options")
        ui.onNodeWithTag("Search scope everywhere").performClick()
        ui.onNodeWithTag("Search scope").assertTextContains("Everywhere")
        ui.onNodeWithTag("Content query").performTextInput("battery life")
        ui.onNodeWithTag("Content query").performImeAction()
        assertNull(submitted.get()!!.scope)
    }

    @Test fun changingPlatformsClearsThePreviousScope() {
        val submitted=dialog(Platform.REDDIT,repo.routes.recognize("https://www.reddit.com/r/Android/")!!)
        ui.onNodeWithTag("Search platform").performClick()
        ui.onNodeWithTag("Search platform twitter").performScrollTo().performClick()
        ui.onNodeWithTag("Search scope").assertTextContains("Everywhere")
        choose(SearchScopeKind.ACCOUNT,"@nasa")
        ui.onNodeWithTag("Search scope").assertTextContains("@nasa")
        ui.onNodeWithTag("Content query").performTextInput("moon")
        ui.onNodeWithTag("Submit content query").performClick()
        assertEquals(Submission(Platform.X,"moon",true,SearchScope(SearchScopeKind.ACCOUNT,"nasa")),submitted.get())
    }

    @Test fun invalidScopeStaysInlineAndDismissingKeepsTheOriginal() {
        val submitted=dialog(Platform.REDDIT)
        ui.onNodeWithTag("Search scope").performClick()
        ui.onNodeWithTag("Search scope option COMMUNITY").performClick()
        ui.onNodeWithTag("Search scope value").performTextInput("r/android OR cats")
        ui.onNodeWithTag("Apply search scope").performScrollTo().performClick()
        ui.onNodeWithText("Enter a subreddit name such as r/Android").assertIsDisplayed()
        assertNull(submitted.get())
        ui.onNodeWithContentDescription("Close search scopes").performScrollTo().performClick()
        ui.onNodeWithTag("Search scope").assertTextContains("Everywhere")
    }

    @Test fun profileModeDoesNotCarrySearchScopeIntoOpeningALink() {
        val submitted=dialog(Platform.X,repo.routes.target(Platform.X,"nasa"))
        ui.onNodeWithTag("Search scope").assertTextContains("@nasa")
        ui.onNodeWithText("Profile or link").performClick()
        ui.onNodeWithTag("Search scope").assertDoesNotExist()
        ui.onNodeWithTag("Content query").performTextInput("esa")
        ui.onNodeWithTag("Submit content query").performClick()
        assertEquals(Submission(Platform.X,"esa",false,null),submitted.get())
    }

    @Test fun editingFeedScopeReusesTheQueryAndRefreshesItsOwnResults() {
        val everywhere=repo.routes.search(Platform.REDDIT,"scope heading fixture")
        val scoped=repo.routes.search(Platform.REDDIT,"scope heading fixture",SearchScope(SearchScopeKind.COMMUNITY,"android"))
        targets+=everywhere;targets+=scoped
        val queries=mutableListOf<Target>()
        val vm=AntiSocialViewModel(repo){target->SearchFeed(target,discover={
            synchronized(queries){queries+=target}
            Page(emptyList(),notice=if(SearchScopes.from(target)==null)"All Reddit fixture results"else "Android community fixture results")
        },read={error("No hydration in heading fixture")})}.also {models+=it}
        ui.runOnUiThread {vm.following();ui.activity.setContent {AntiSocialApp(vm)};vm.open(everywhere,true)}
        ui.waitUntil(10_000){vm.screen.value.target==everywhere&&!vm.screen.value.loading}
        choose(SearchScopeKind.COMMUNITY,"Android")
        ui.waitUntil(10_000){vm.screen.value.target==scoped&&!vm.screen.value.loading}
        waitForKeyboard(false)
        ui.onNodeWithText("scope heading fixture").assertIsDisplayed()
        ui.onNodeWithTag("Search scope").assertTextContains("r/android")
        ui.onNodeWithTag("Search feed").performScrollToNode(hasText("Android community fixture results"))
        ui.onNodeWithText("Android community fixture results").assertIsDisplayed()
        ui.onNodeWithText("All Reddit fixture results").assertDoesNotExist()
        evidence("native-scoped-feed")
        assertEquals(listOf(everywhere,scoped),synchronized(queries){queries.toList()})
        ui.onNodeWithTag("Search feed").performScrollToIndex(0)
        ui.onNodeWithContentDescription("Refresh search feed").performClick()
        ui.waitUntil(10_000){synchronized(queries){queries.size}==3&&!vm.screen.value.loading}
        assertEquals(scoped,synchronized(queries){queries.last()})
    }

    @Test fun everyPlatformOffersItsSupportedScopeKindsThroughTheSameControl() {
        var platform by mutableStateOf(Platform.X)
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {SearchScopeControl(platform,null,{})}}}
        for(candidate in Platform.entries) {
            ui.runOnIdle {platform=candidate}
            if(SearchScopes.options(candidate).isEmpty()) {
                ui.onNodeWithTag("Search scope").assertDoesNotExist()
                continue
            }
            ui.onNodeWithTag("Search scope").assertTextContains("Everywhere").performClick()
            for(option in SearchScopes.options(candidate))ui.onNodeWithTag("Search scope option ${option.kind.name}").assertExists()
            ui.onNodeWithTag("Search scope everywhere").performClick()
        }
    }
}
