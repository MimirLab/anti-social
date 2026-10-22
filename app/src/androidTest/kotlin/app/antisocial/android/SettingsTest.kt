package app.antisocial.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
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
class SettingsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private lateinit var preferences: String
    @Before fun preservePreferences() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString()
        ui.runOnIdle {model().section("settings")}
    }
    @After fun restorePreferences() {
        ui.runOnIdle {
            model().following()
            // Backup import merges keys; tests must also remove keys that were
            // absent before the test, including values using app defaults.
            ui.activity.getSharedPreferences("antisocial",android.content.Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
            assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
        }
    }
    private fun open(page: SettingsPage) {
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Settings destination ${page.name.lowercase()}"))
        ui.onNodeWithTag("Settings destination ${page.name.lowercase()}").performClick()
        ui.onNodeWithTag("Settings title").assertTextEquals(page.title)
    }
    private fun back(){ui.onNodeWithContentDescription("Back").performClick();ui.waitForIdle()}
    private fun row(title: String){ui.onNodeWithTag("Settings list").performScrollToNode(hasText(title));ui.onNodeWithText(title).performClick()}
    private fun evidence(name: String) {
        ui.waitForIdle()
        // Android dialog-window fades are outside Compose's test clock.
        if(name.contains("dialog"))android.os.SystemClock.sleep(300)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"settings-redesign").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun overviewKeepsAdvancedControlsInFocusedPagesAndBackReturnsToTheRightPlace() {
        ui.onNodeWithTag("Settings title").assertTextEquals("Settings")
        ui.onNodeWithText("Protection is on").assertIsDisplayed()
        ui.onNodeWithTag("Content query").assertDoesNotExist()
        ui.onNodeWithText("Custom cache limit in MB").assertDoesNotExist()
        evidence("overview")
        for(page in listOf(SettingsPage.PRIVACY,SettingsPage.SEARCH,SettingsPage.COMMENTS,SettingsPage.REFRESH,SettingsPage.STORAGE,SettingsPage.LINKS,SettingsPage.BACKUP,SettingsPage.APPEARANCE)) {
            open(page);evidence(page.name.lowercase());back();ui.onNodeWithTag("Settings title").assertTextEquals("Settings")
        }
        ui.onNodeWithTag("Settings list").performScrollToIndex(0)
        ui.onNodeWithText("Protection is on").performClick()
        ui.onNodeWithTag("Settings title").assertTextEquals("Network protection")
        evidence("protection")
        open(SettingsPage.FILTER_LISTS);evidence("filter-lists");back()
        ui.onNodeWithTag("Settings title").assertTextEquals("Network protection")
        back();ui.onNodeWithTag("Settings title").assertTextEquals("Settings")
        open(SettingsPage.PRIVACY);row("Inspect and test source pools")
        assertEquals("pools",model().screen.value.section)
        back();ui.onNodeWithTag("Settings title").assertTextEquals("Privacy & connection")
        back();ui.onNodeWithTag("Settings title").assertTextEquals("Settings")
        ui.runOnIdle {model().section("saved")}
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithTag("Settings title").assertTextEquals("Settings")
        back();assertEquals("saved",model().screen.value.section)
    }
    @Test fun communitySettingsControlParticipationLocallyAndResetNeedsConfirmation() {
        repo.prefs.communityComments=true
        val vault=AnywhereVault(ui.activity)
        runBlocking {vault.save(AnywhereIdentity("fixture-settings-owner","fixture-token","fixture-refresh",Long.MAX_VALUE))}
        open(SettingsPage.COMMENTS)
        ui.onNodeWithTag("Setting toggle Shared community comments").performClick()
        assertFalse(repo.prefs.communityComments)
        assertFalse(repo.prefs.export().getBoolean("communityComments"))
        ui.onNodeWithTag("Check community connection").assertIsNotEnabled()
        val checked=repo.gateway.protectionStats().checked
        runBlocking {try {repo.commentAnywhere.read("https://commentanywhere.app/",CommentOrder.NEWEST);fail("Disabled service must not read") }catch(e: IllegalArgumentException){assertTrue(e.message!!.contains("off"))}}
        assertEquals(checked,repo.gateway.protectionStats().checked)
        ui.onNodeWithTag("Setting toggle Shared community comments").performClick()
        assertTrue(repo.prefs.communityComments)
        evidence("community-settings")
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Reset community identity"))
        ui.onNodeWithTag("Reset community identity").performClick()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals("fixture-settings-owner",runBlocking {vault.load()!!.uid})
        ui.onNodeWithTag("Reset community identity").performClick()
        evidence("community-identity-reset-dialog")
        ui.onNodeWithTag("Confirm reset community identity").performClick()
        // Observe through the store that owns the reset operation, sharing its
        // vault mutex rather than racing file/key deletion through a second vault.
        ui.waitUntil(5_000){runBlocking {repo.commentAnywhere.owner()==null}}
        assertNull(runBlocking {repo.commentAnywhere.owner()})
    }
    @Test fun tabOrderAndActiveTabRemovalStayInSettingsAndSurviveRecreation() {
        val vm=model();val active=vm.screen.value.platform
        open(SettingsPage.PLATFORMS)
        val selected=repo.prefs.selected
        if(selected.size==1) {
            val extra=Platform.entries.first {it !in selected}
            ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle ${extra.id}"))
            ui.onNodeWithTag("Platform toggle ${extra.id}").performClick()
        }
        val first=repo.prefs.selected.first()
        ui.onNodeWithTag("Settings list").performScrollToNode(hasContentDescription("Move ${first.label} tab right"))
        ui.onNodeWithContentDescription("Move ${first.label} tab right").performClick()
        assertEquals(first,repo.prefs.selected[1])
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle ${active.id}"))
        ui.onNodeWithTag("Platform toggle ${active.id}").performClick()
        assertFalse(active in repo.prefs.selected);assertEquals("settings",vm.screen.value.section)
        assertEquals(SettingsPage.PLATFORMS,vm.screen.value.settingsPage)
        assertTrue(vm.screen.value.platform in repo.prefs.selected)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("Settings title").assertTextEquals("Platform tabs")
        assertFalse(active in Preferences(ui.activity).selected)
        for(platform in repo.prefs.selected.drop(1).toList()) {
            ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle ${platform.id}"))
            ui.onNodeWithTag("Platform toggle ${platform.id}").performClick()
        }
        val only=repo.prefs.selected.single()
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle ${only.id}"))
        ui.onNodeWithTag("Platform toggle ${only.id}").assertIsNotEnabled()
        evidence("platforms")
    }
    @Test fun popularitySortReordersOnlyChosenTabsAndSurvivesRecreation() {
        open(SettingsPage.PLATFORMS)
        if(repo.prefs.selected.size<2) {
            val extra=PlatformOrder.popular.first {it !in repo.prefs.selected}
            ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle ${extra.id}"))
            ui.onNodeWithTag("Platform toggle ${extra.id}").performClick()
        }
        if(repo.prefs.selected==PlatformOrder.sort(repo.prefs.selected)) {
            val first=repo.prefs.selected.first()
            ui.onNodeWithTag("Settings list").performScrollToNode(hasContentDescription("Move ${first.label} tab right"))
            ui.onNodeWithContentDescription("Move ${first.label} tab right").performClick()
        }
        val chosen=repo.prefs.selected.toSet();val active=repo.prefs.activePlatform
        val sorted=PlatformOrder.sort(repo.prefs.selected)
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Sort tabs by popularity"))
        ui.onNodeWithTag("Sort tabs by popularity").assertIsEnabled().performClick()
        assertEquals(sorted,repo.prefs.selected);assertEquals(chosen,repo.prefs.selected.toSet());assertEquals(active,repo.prefs.activePlatform)
        ui.activityRule.scenario.recreate()
        assertEquals(sorted,Preferences(ui.activity).selected)
        assertEquals(active,Preferences(ui.activity).activePlatform)
        ui.onNodeWithTag("Settings title").assertTextEquals("Platform tabs")
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Sort tabs by popularity"))
        ui.onNodeWithTag("Sort tabs by popularity").assertIsNotEnabled()
        evidence("platforms-popularity")
    }
    @Test fun storageValuesValidatePersistAndHistoryClearCanBeCancelled() {
        open(SettingsPage.STORAGE)
        row("Cache limit")
        ui.onNodeWithText("Custom value…").performClick()
        ui.onNodeWithTag("Setting number input").performTextReplacement("1")
        ui.onNodeWithText("Save").assertIsNotEnabled()
        ui.onNodeWithTag("Setting number input").performTextReplacement("5000")
        ui.onNodeWithText("Save").performClick();assertEquals(5000,repo.prefs.cacheMb)
        row("Unviewed items")
        ui.onNodeWithTag("Setting number input").performTextReplacement("12")
        ui.onNodeWithText("Save").performClick();assertEquals(12,repo.prefs.unseenDays)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("Settings title").assertTextEquals("Storage")
        ui.onNodeWithText("5000 MB").assertIsDisplayed()
        val history=runBlocking {repo.dao.allHistory().size}
        row("Clear reading history")
        ui.onNodeWithText("Clear reading history?").assertIsDisplayed();ui.onNodeWithText("Cancel").performClick()
        assertEquals(history,runBlocking {repo.dao.allHistory().size})
        row("Viewed items");evidence("number-dialog");ui.onNodeWithText("Cancel").performClick()
    }
    @Test fun refreshAndThemeUseSimpleChoicesAndKeepTheCurrentPage() {
        open(SettingsPage.REFRESH)
        val enabled=repo.prefs.intervalMinutes>0
        if(enabled)ui.onNodeWithTag("Setting toggle Automatic refresh").performClick()
        assertEquals(0,repo.prefs.intervalMinutes)
        ui.onNodeWithText("Refresh every").assertIsNotEnabled()
        ui.onNodeWithTag("Setting toggle Automatic refresh").performClick()
        row("Refresh every");ui.onNodeWithText("30 minutes").performClick();assertEquals(30,repo.prefs.intervalMinutes)
        row("Refresh every");ui.onNodeWithText("Custom value…").performClick()
        ui.onNodeWithTag("Setting number input").performTextReplacement("14");ui.onNodeWithText("Save").assertIsNotEnabled()
        ui.onNodeWithTag("Setting number input").performTextReplacement("45");ui.onNodeWithText("Save").performClick()
        assertEquals(45,repo.prefs.intervalMinutes)
        back();open(SettingsPage.APPEARANCE)
        ui.onNodeWithTag("Theme dark").performClick().assertIsSelected();assertEquals("dark",repo.prefs.theme)
        ui.onNodeWithTag("Settings title").assertTextEquals("Appearance");evidence("appearance-dark")
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("Theme dark").assertIsSelected()
        back();ui.onNodeWithTag("Settings title").assertTextEquals("Settings");ui.onNodeWithTag("Settings list").performScrollToIndex(0);evidence("overview-dark")
    }
    @Test fun filterListsHaveAnOwnScreenAndBackupRequiresAPassphrase() {
        ui.onNodeWithText("Protection is on").performClick();open(SettingsPage.FILTER_LISTS)
        val source=repo.filterSources.first();val before=source.id !in repo.prefs.disabledLists()
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Filter list ${source.id}"))
        ui.onNodeWithTag("Filter list ${source.id}").performClick()
        assertEquals(!before,source.id !in repo.prefs.disabledLists());assertTrue(repo.filters.value.ready)
        ui.onNodeWithTag("Settings list").performScrollToIndex(0);ui.onNodeWithText("Add a list").performClick()
        ui.onNodeWithTag("Custom filter URL").performTextInput("https://user:secret@example.org/list.txt")
        ui.onNodeWithText("Add list").performClick()
        ui.onNodeWithText("Enter a valid HTTPS URL without login credentials.").assertIsDisplayed()
        ui.onNodeWithTag("Custom filter URL").performTextReplacement("https://example.org/antisocial-settings-fixture.txt")
        ui.onNodeWithText("Add list").performClick();assertTrue("https://example.org/antisocial-settings-fixture.txt" in repo.prefs.customLists())
        back();back();open(SettingsPage.BACKUP)
        row("Export encrypted backup")
        ui.onNodeWithText("Continue").assertIsNotEnabled()
        ui.onNodeWithTag("Backup passphrase").performTextInput("testpassphrase")
        ui.onNodeWithText("Continue").assertIsEnabled();evidence("backup-dialog")
        ui.onNodeWithText("Cancel").performClick()
        assertTrue(repo.filters.value.ready)
    }
}
