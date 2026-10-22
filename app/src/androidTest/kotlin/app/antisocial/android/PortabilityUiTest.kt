/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Native selection and confirmation tests; these fixtures never contact a source. */
@RunWith(AndroidJUnit4::class)
class PortabilityUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private var automationFlags=0
    private val x=Follow("portable-ui-x",Target(Platform.X,TargetKind.PROFILE,"portable_developer","https://x.com/portable_developer"),"Developer",personId="portable-ui-person",personName="Developer")
    private val blue=Follow("portable-ui-blue",Target(Platform.BLUESKY,TargetKind.PROFILE,"developer.test","https://bsky.app/profile/developer.test"),"Developer",personId="portable-ui-person",personName="Developer")
    private val rss=Follow("portable-ui-rss",Target(Platform.RSS,TargetKind.FEED,"https://blog.example.org/feed.xml","https://blog.example.org/feed.xml"),"Developer blog",group="Software",feedUrl="https://blog.example.org/feed.xml")

    @Before fun preserve() {
        runBlocking {repo.awaitReady()};preferences=repo.prefs.export().toString()
        ui.runOnIdle {
            ui.activity.setContent {Text("Preparing local portability fixtures")}
            repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
            repo.gateway.configure(NetworkPolicy(tor=true))
        }
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automationFlags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
    }
    @After fun restore() {
        ui.runOnIdle {
            ui.activity.setContent {Text("Local portability fixtures complete")}
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply {flags=automationFlags}
    }
    private fun show(content: @Composable ()->Unit) {
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme(),content=content)}}
        ui.waitForIdle()
    }
    private fun hideKeyboard() {
        ui.waitForIdle();val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun shown()=instrumentation.uiAutomation.windows.any {it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}
        instrumentation.waitForIdleSync();android.os.SystemClock.sleep(150)
        if(shown())instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000){!shown()};android.os.SystemClock.sleep(300);ui.waitForIdle()
    }
    private fun evidence(name: String) {
        hideKeyboard();android.os.SystemClock.sleep(300)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"portability").apply {mkdirs()}
        File(directory,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun sourcePreview(state: Boolean=false)=PortableImportPreview(document=PortableDocument(kind=if(state)PortableKind.STATE else PortableKind.SOURCES,createdAt=1,sources=listOf(x,blue,rss),title=if(state)"My reader"else "Software people"),channels=if(state)2 else 0,collections=if(state)1 else 0,savedPosts=if(state)3 else 0,hasFilters=state,hasSettings=state,hasProgress=state)

    @Test fun sourcePackStartsEmptyAndExportsOnlyTheChosenLinkedAccount() {
        var exported: Set<String>?=null;var opml: Boolean?=null
        show {SourcePackSheet(listOf(x,blue),onDismiss={},onExport={ids,_,isOpml->exported=ids;opml=isOpml})}
        ui.onNodeWithTag("Export source pack").assertIsNotEnabled()
        ui.onNodeWithTag("Pack source ${x.id}").assertIsOff().performClick().assertIsOn()
        ui.onNodeWithTag("Pack source ${blue.id}").assertIsOff()
        ui.onNodeWithTag("Export selected OPML").assertIsNotEnabled()
        evidence("source-pack-selected-account")
        ui.onNodeWithTag("Export source pack").performClick()
        ui.runOnIdle {assertEquals(setOf(x.id),exported);assertEquals(false,opml)}
    }

    @Test fun channelSourcePackPreselectionDoesNotAddOtherAccountsAndOpmlNeedsAFeed() {
        var exported: Set<String>?=null;var opml: Boolean?=null
        show {SourcePackSheet(listOf(x,rss),initialSourceIds=setOf(rss.id,"missing"),initialTitle="Software releases",onDismiss={},onExport={ids,_,isOpml->exported=ids;opml=isOpml})}
        ui.onNodeWithTag("Source pack title").assertTextContains("Software releases")
        ui.onNodeWithTag("Pack source ${rss.id}").assertIsOn()
        ui.onNodeWithTag("Pack source ${x.id}").assertIsOff()
        ui.onNodeWithTag("Export selected OPML").assertIsEnabled().performClick()
        ui.runOnIdle {assertEquals(setOf(rss.id),exported);assertEquals(true,opml)}
    }

    @Test fun sharedSourcePreviewAddsNothingUntilOneSourceIsSelectedAndConfirmed() {
        val preview=sourcePreview();val calls=AtomicInteger();var selected: Set<String>?=null
        show {PortableImportSheet(preview,onDismiss={},onImport={ids,options->calls.incrementAndGet();selected=ids;assertFalse(options.progress);assertFalse(options.settings)})}
        ui.onNodeWithTag("Confirm portable import").assertIsNotEnabled()
        ui.onNodeWithTag("Import source ${x.id}").assertIsOff().performClick().assertIsOn()
        ui.onNodeWithTag("Import source ${blue.id}").assertIsOff()
        assertEquals(0,calls.get());evidence("source-pack-import-preview")
        ui.onNodeWithTag("Confirm portable import").performClick()
        ui.waitUntil(5_000){calls.get()==1};ui.runOnIdle {assertEquals(setOf(x.id),selected)}
    }

    @Test fun nativeStatePreviewKeepsProgressAndReaderPreferencesOptIn() {
        val preview=sourcePreview(true);var captured: PortableImportOptions?=null;var selected: Set<String>?=null
        show {PortableImportSheet(preview,onDismiss={},onImport={ids,options->selected=ids;captured=options})}
        val list=ui.onNodeWithTag("Import preview list")
        list.performScrollToNode(hasTestTag("Import channels"));ui.onNodeWithTag("Import channels").assertIsOn()
        list.performScrollToNode(hasTestTag("Import filters"));ui.onNodeWithTag("Import filters").assertIsOn()
        list.performScrollToNode(hasTestTag("Import collections"));ui.onNodeWithTag("Import collections").assertIsOn()
        list.performScrollToNode(hasTestTag("Import reader preferences"));ui.onNodeWithTag("Import reader preferences").assertIsOff()
        list.performScrollToNode(hasTestTag("Import reading progress"));ui.onNodeWithTag("Import reading progress").assertIsOff()
        evidence("reader-import-options")
        list.performScrollToNode(hasTestTag("Import source ${x.id}"));ui.onNodeWithTag("Import source ${x.id}").assertIsOn().performClick().assertIsOff()
        ui.onNodeWithTag("Confirm portable import").performClick()
        ui.waitUntil(5_000){captured!=null}
        ui.runOnIdle {assertEquals(setOf(blue.id,rss.id),selected);assertTrue(captured!!.channels);assertTrue(captured!!.filters);assertTrue(captured!!.collections);assertFalse(captured!!.settings);assertFalse(captured!!.progress)}
    }

    @Test fun failedImportKeepsItsPreviewAndSelectionAvailableForRetry() {
        val preview=sourcePreview();val calls=AtomicInteger()
        show {PortableImportSheet(preview,onDismiss={},onImport={ids,_->assertEquals(setOf(x.id),ids);if(calls.incrementAndGet()==1)throw IllegalStateException("This reader is busy. Try again.")})}
        ui.onNodeWithTag("Import source ${x.id}").performClick()
        ui.onNodeWithTag("Confirm portable import").performClick()
        ui.onNodeWithTag("Import preview list").performScrollToNode(hasTestTag("Import preview error"))
        ui.onNodeWithTag("Import preview error").assertTextEquals("This reader is busy. Try again.")
        ui.onNodeWithTag("Import preview list").performScrollToNode(hasTestTag("Import source ${x.id}"));ui.onNodeWithTag("Import source ${x.id}").assertIsOn()
        ui.onNodeWithTag("Confirm portable import").performClick();ui.waitUntil(5_000){calls.get()==2}
    }

    @Test fun ordinaryRssFormRejectsInsecureUrlsAndOnlyAddsAfterExplicitConfirmation() {
        var added: List<String>?=null;val dismissed=AtomicInteger()
        show {RssSourceSheet(onAdd={url,title,folder->added=listOf(url,title,folder)},onDismiss={dismissed.incrementAndGet()})}
        ui.onNodeWithTag("RSS feed URL").performTextInput("http://blog.example.org/feed.xml");hideKeyboard()
        ui.onNodeWithTag("Add RSS source").performClick()
        ui.onNodeWithTag("RSS source error").assertTextContains("public HTTPS",substring=true)
        assertNull(added);assertEquals(0,dismissed.get())
        ui.onNodeWithTag("RSS feed URL").performTextReplacement("https://blog.example.org/feed.xml")
        ui.onNodeWithTag("RSS feed title").performTextInput("  Developer blog  ")
        ui.onNodeWithTag("RSS feed folder").performTextInput("  Software  ");hideKeyboard()
        evidence("ordinary-rss-form")
        ui.onNodeWithTag("Add RSS source").performClick();ui.waitUntil(5_000){added!=null}
        ui.runOnIdle {assertEquals(listOf("https://blog.example.org/feed.xml","Developer blog","Software"),added);assertEquals(1,dismissed.get())}
    }

    @Test fun importExportIsAFocusedSettingsPageAndReadingProgressStartsExcluded() {
        val model=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        ui.runOnIdle {model.section("settings")}
        show {SettingsScreen(model,repo.prefs.selected,{}, {})}
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Settings destination portability"))
        ui.onNodeWithTag("Settings destination portability").performClick()
        ui.onNodeWithTag("Portability screen").assertIsDisplayed()
        ui.onNodeWithTag("Export reading progress").assertIsOff()
        ui.onNodeWithTag("Export reader file").assertIsDisplayed()
        ui.onNodeWithTag("Backup passphrase").assertDoesNotExist()
        evidence("native-import-export")
        ui.runOnIdle {assertEquals(SettingsPage.PORTABILITY,model.screen.value.settingsPage)}
    }
}
