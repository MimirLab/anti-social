/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Local rule and native editor fixtures never query a public source. */
@RunWith(AndroidJUnit4::class)
class PersonalChannelsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val raw get()=ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE)
    private lateinit var preferences: String
    private lateinit var store: PersonalChannelStore
    private var automationFlags=0
    private val follows=listOf(
        Follow("channel-editor-x",Target(Platform.X,TargetKind.PROFILE,"antisocial_channel_developer","https://x.com/antisocial_channel_developer"),"Developer",personId="channel-editor-person",personName="Developer"),
        Follow("channel-editor-blue",Target(Platform.BLUESKY,TargetKind.PROFILE,"channel.test","https://bsky.app/profile/channel.test"),"Developer",personId="channel-editor-person",personName="Developer"),
        Follow("channel-editor-local",Target(Platform.REDDIT,TargetKind.COMMUNITY,"antisocial_channel_local","https://www.reddit.com/r/antisocial_channel_local"),"Local community")
    )

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString()
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automationFlags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        ui.runOnIdle {
            ui.activity.setContent {Text("Preparing local channel fixtures")}
            raw.edit().clear().commit()
            repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
            repo.gateway.configure(NetworkPolicy(tor=true)) // No ready transport: fail closed.
            store=PersonalChannelStore(repo.prefs)
        }
    }
    @After fun restore() {
        ui.runOnIdle {
            ui.activity.setContent {Text("Local channel fixtures complete")}
            raw.edit().clear().commit()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        assertEquals(JsonParser.parseString(preferences),JsonParser.parseString(repo.prefs.export().toString()))
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply {flags=automationFlags}
    }
    private fun channel(id: String="software",name: String="Software releases")=PersonalChannel(id,name,subscriptionIds=setOf("channel-editor-person"),keywords=listOf("release"))
    private fun show(): AtomicInteger {
        val dismissed=AtomicInteger()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()) {
            key(dismissed) {
                var open by remember {mutableStateOf(true)}
                if(open)ChannelsSheet(store,follows,onDismiss={dismissed.incrementAndGet();open=false})
            }
        }}}
        ui.onNodeWithTag("Channels sheet").assertIsDisplayed()
        return dismissed
    }
    private fun editorField(tag: String): SemanticsNodeInteraction {
        val editor=ui.onNodeWithTag("Channel editor")
        editor.performScrollToNode(hasTestTag(tag))
        repeat(12) {attempt->
            val control=ui.onNodeWithTag(tag)
            // A nested horizontal row is the chip's nearest scroll parent.
            // performScrollTo reveals it on that axis but cannot move its tall
            // lazy item vertically. Finish with real gestures in the editor.
            control.performScrollTo()
            val node=control.fetchSemanticsNode()
            val viewport=editor.fetchSemanticsNode().boundsInRoot
            val position=node.positionInRoot
            val bottom=position.y+node.size.height
            val right=position.x+node.size.width
            val visible=position.y>=viewport.top&&bottom<=viewport.bottom&&position.x>=viewport.left&&right<=viewport.right&&
                runCatching {control.assertIsDisplayed()}.isSuccess
            val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"personal-channels").apply {mkdirs()}
            File(dir,"native-editor-scroll-bounds.jsonl").appendText(JSONObject().put("tag",tag).put("attempt",attempt).put("targetTop",position.y).put("targetBottom",bottom).put("viewportTop",viewport.top).put("viewportBottom",viewport.bottom).put("fullyVisible",visible).toString()+"\n")
            if(visible)return control
            val targetY=position.y+node.size.height/2f
            editor.performTouchInput {
                if(targetY>viewport.center.y)swipeUp(startY=height*.75f,endY=height*.4f)
                else swipeDown(startY=height*.4f,endY=height*.75f)
            }
            ui.waitForIdle()
        }
        throw AssertionError("Channel control $tag was not fully visible after bounded real editor gestures.")
    }
    private fun hideKeyboard() {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun shown()=instrumentation.uiAutomation.windows.any {it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}
        // ModalBottomSheet owns a separate window: the Activity's focus token
        // cannot dismiss its IME. Wait for pending native input/window work, then
        // use Android's normal keyboard dismissal only while that IME is visible.
        instrumentation.waitForIdleSync();android.os.SystemClock.sleep(150)
        if(shown())instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000){!shown()}
        // IME insets animate outside Compose's idling resources. Settle them
        // before querying chip positions or injecting the next touch.
        android.os.SystemClock.sleep(300);ui.waitForIdle()
    }
    private fun evidence(name: String) {
        hideKeyboard();android.os.SystemClock.sleep(350);ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"personal-channels").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun rejected(block: ()->Unit) {
        try {block();fail("Invalid rules must be rejected before a preferences write.")}
        catch(expected: RuntimeException) { /* org.json also rejects malformed documents. */ }
    }

    @Test fun aNewInstallHasNoSeededChannelsAndOneCreatorCanBelongToSeveral() {
        assertTrue(store.channels.value.isEmpty());assertNull(store.selected.value);assertNull(store.error.value)
        assertFalse(raw.contains("personalChannels"));assertFalse(raw.contains("personalChannel"))
        val software=store.save(channel().copy(name="  Software   releases ",keywords=listOf("release","RELEASE"),hashtags=listOf("#Software")))
        val local=store.save(channel("local","Local").copy(keywords=listOf("local")))
        assertEquals("Software releases",software.name);assertEquals(listOf("release"),software.keywords);assertEquals(listOf("software"),software.hashtags)
        assertEquals(software.subscriptionIds,local.subscriptionIds)
        assertEquals(listOf("software","local"),store.channels.value.map {it.id});assertNull(store.selected.value)
        store.select("local")
        val fresh=PersonalChannelStore(Preferences(ui.activity))
        assertEquals(store.channels.value,fresh.channels.value);assertEquals("local",fresh.selected.value)
        repo.prefs.validate(repo.prefs.export())
    }

    @Test fun malformedBackupsAreRejectedAtomicallyAndCannotDropRules() {
        store.save(channel());store.select("software");repo.prefs.theme="dark"
        val before=repo.prefs.export().toString()
        fun document(change: (JSONObject)->Unit)=JSONObject(raw.getString("personalChannels",null)!!).also(change).toString()
        val invalid=listOf(
            "not-json",
            document {it.put("version",2)},
            document {it.put("version","1")},
            document {it.put("extra",true)},
            document {it.getJSONArray("channels").getJSONObject(0).put("keywords",JSONArray().put(12))},
            document {it.getJSONArray("channels").getJSONObject(0).put("contentTypes",JSONArray().put("UNKNOWN"))},
            document {it.getJSONArray("channels").getJSONObject(0).put("excludeContentTypes",JSONArray().put("UNKNOWN"))},
            document {it.getJSONArray("channels").getJSONObject(0).put("excludeContentTypes","VIDEO")},
            document {it.getJSONArray("channels").getJSONObject(0).put("minVideoSeconds","600")},
            document {it.getJSONArray("channels").getJSONObject(0).put("minVideoSeconds",-1)},
            document {it.getJSONArray("channels").getJSONObject(0).put("revision",1.5)},
            document {it.getJSONArray("channels").getJSONObject(0).remove("excludeKeywords")},
            document {it.getJSONArray("channels").getJSONObject(0).remove("excludeContentTypes")},
            document {it.getJSONArray("channels").put(it.getJSONArray("channels").getJSONObject(0))},
            document {}+" trailing data",
            document {}.replace("\"keywords\":[\"release\"]","\"keywords\":[\"release\"],\"keywords\":[]"),
            document {}.replace("\"version\":1","version:1")
        )
        invalid.forEach {rules->
            rejected {repo.prefs.restore(JSONObject().put("theme","light").put("personalChannels",rules).put("personalChannel","software"))}
            assertEquals(JsonParser.parseString(before),JsonParser.parseString(repo.prefs.export().toString()))
            assertEquals("software",store.selected.value);assertEquals(listOf("release"),store.channels.value.single().keywords)
        }
        rejected {repo.prefs.restore(JSONObject().put("theme","light").put("personalChannels",true))}
        rejected {repo.prefs.restore(JSONObject().put("theme","light").put("personalChannel","missing"))}
        assertEquals(JsonParser.parseString(before),JsonParser.parseString(repo.prefs.export().toString()))
    }

    @Test fun restoreImmediatelyUpdatesExistingStoreAndPreservesEveryRule() {
        val complex=channel().copy(hashtags=listOf("software"),contentTypes=setOf(ChannelContentType.VIDEO,ChannelContentType.LINKS),excludeKeywords=listOf("giveaway"),excludeHashtags=listOf("ad"),excludeSubscriptionIds=setOf("channel-editor-local"),minVideoSeconds=61,excludeContentTypes=setOf(ChannelContentType.AUDIO))
        store.save(complex);store.save(channel("local","Local"));store.select("software")
        val saved=repo.prefs.export();val expected=store.channels.value
        store.delete("software");store.select("local")
        ui.runOnIdle {repo.prefs.restore(saved)}
        ui.waitUntil(5_000){store.selected.value=="software"&&store.channels.value==expected}
        assertNull(store.error.value);assertEquals(61L,store.channels.value.first().minVideoSeconds)
        assertEquals(setOf(ChannelContentType.AUDIO),store.channels.value.first().excludeContentTypes)
    }

    @Test fun corruptRulesKeepTheSelectedIdUntilExplicitRecovery() {
        store.save(channel());store.select("software")
        val backup=repo.prefs.export()
        ui.runOnIdle {raw.edit().putString("personalChannels","broken").commit()}
        assertEquals("software",store.selected.value);assertTrue(store.channels.value.isEmpty());assertNotNull(store.error.value)
        rejected {store.save(channel("broader","Broad"))}
        rejected {store.select("missing")}
        assertEquals("software",store.selected.value)
        ui.runOnIdle {repo.prefs.restore(backup)}
        ui.waitUntil(5_000){store.error.value==null&&store.channels.value.size==1}
        assertEquals("software",store.selected.value)
    }

    @Test fun aDamagedSelectionIsNeverInterpretedAsAllAndAValidChoiceRepairsIt() {
        store.save(channel());store.save(channel("local","Local"))
        ui.runOnIdle {raw.edit().putBoolean("personalChannel",true).commit()}
        assertNotNull(store.selected.value);assertNotNull(store.error.value)
        store.select(null)
        assertNull(store.selected.value);assertNull(store.error.value);assertEquals(2,store.channels.value.size)
        ui.runOnIdle {raw.edit().putString("personalChannel","missing").commit()}
        assertEquals("missing",store.selected.value);assertNotNull(store.error.value)
        store.select("local")
        assertEquals("local",store.selected.value);assertNull(store.error.value)
    }

    @Test fun deletionOnlyRemovesItsOwnRulesAndExplicitlyClearsAnActiveSelection() {
        store.save(channel());store.save(channel("local","Local"));store.select("software")
        store.delete("local")
        assertEquals("software",store.selected.value);assertEquals(listOf("software"),store.channels.value.map {it.id})
        store.save(channel("local","Local"));store.delete("software")
        assertNull(store.selected.value);assertEquals(listOf("local"),store.channels.value.map {it.id})
        assertNull(store.error.value);repo.prefs.validate(repo.prefs.export())
    }

    @Test fun storeEnforcesRawLimitsBeforeNormalizationAndDoesNotWriteInvalidInput() {
        store.save(channel());val before=repo.prefs.export().toString()
        listOf(
            channel("invalid").copy(keywords=List(PersonalChannels.MAX_TERMS+1){"duplicate"}),
            channel("invalid").copy(name="x".repeat(PersonalChannels.MAX_NAME_LENGTH+1)),
            channel("invalid").copy(hashtags=listOf("two words")),
            channel("invalid").copy(id="bad\nid"),
            channel("invalid").copy(minVideoSeconds=0),
            channel("invalid").copy(subscriptionIds=setOf(""))
        ).forEach {invalid->rejected {store.save(invalid)};assertEquals(JsonParser.parseString(before),JsonParser.parseString(repo.prefs.export().toString()))}
    }

    @Test fun nativeEditorCreatesAnExplicitChannelWithBundledSourcesAndExclusions() {
        val dismissed=show()
        ui.onNodeWithTag("New channel").performClick()
        editorField("Channel name").performTextInput("Software releases")
        editorField("Channel subscriptions").performClick()
        ui.onAllNodesWithTag("Channel subscription channel-editor-person").assertCountEquals(1)
        ui.onNodeWithTag("Channel subscription channel-editor-person").performClick()
        ui.onNodeWithTag("Channel subscriptions done").performScrollTo().performClick()
        editorField("Channel keywords").performTextInput("release, version")
        editorField("Channel hashtags").performTextInput("#Software")
        hideKeyboard()
        editorField("Channel type VIDEO").performClick()
        editorField("Channel minimum video").performTextInput("10")
        hideKeyboard();evidence("native-channel-content-rules")
        editorField("Channel exclusions").performClick()
        editorField("Channel excluded type AUDIO").performScrollTo().performClick()
        editorField("Channel excluded keywords").performTextInput("giveaway")
        hideKeyboard()
        editorField("Save channel").performClick()
        ui.waitUntil(5_000){dismissed.get()==1}
        val saved=store.channels.value.single()
        assertEquals("Software releases",saved.name);assertEquals(setOf("channel-editor-person"),saved.subscriptionIds)
        assertEquals(listOf("release","version"),saved.keywords);assertEquals(listOf("software"),saved.hashtags)
        assertEquals(setOf(ChannelContentType.VIDEO),saved.contentTypes);assertEquals(600L,saved.minVideoSeconds)
        assertEquals(listOf("giveaway"),saved.excludeKeywords);assertEquals(saved.id,store.selected.value)
        assertEquals(setOf(ChannelContentType.AUDIO),saved.excludeContentTypes)
    }

    @Test fun nativeEditorCanExcludeVideosFromReleaseLinks() {
        val dismissed=show();ui.onNodeWithTag("New channel").performClick()
        editorField("Channel name").performTextInput("Release links without video")
        editorField("Channel keywords").performTextInput("release")
        hideKeyboard();editorField("Channel type LINKS").performScrollTo().performClick()
        editorField("Channel exclusions").performClick()
        editorField("Channel excluded type VIDEO").performScrollTo().performClick().assertIsSelected()
        evidence("native-channel-content-exclusions")
        editorField("Save channel").performClick()
        ui.waitUntil(5_000){dismissed.get()==1}
        val saved=store.channels.value.single()
        assertEquals(setOf(ChannelContentType.LINKS),saved.contentTypes)
        assertEquals(setOf(ChannelContentType.VIDEO),saved.excludeContentTypes)
        val fresh=PersonalChannelStore(Preferences(ui.activity))
        assertEquals(saved,fresh.channels.value.single());assertEquals(saved.id,fresh.selected.value)
    }

    @Test fun invalidNativeRulesRemainInlineWithoutCreatingABroaderChannel() {
        show();ui.onNodeWithTag("New channel").performClick()
        editorField("Save channel").performClick()
        editorField("Channel validation").assertTextContains("Give the channel a name.")
        assertTrue(store.channels.value.isEmpty());assertNull(store.selected.value)
        editorField("Channel name").performTextInput("Long videos")
        editorField("Channel minimum video").performTextInput("not a number")
        hideKeyboard();editorField("Save channel").performClick()
        editorField("Channel validation").assertTextContains("Enter a valid minimum video length in minutes.")
        assertTrue(store.channels.value.isEmpty());assertFalse(raw.contains("personalChannels"))
        evidence("native-channel-validation")
    }

    @Test fun editingANamePreservesSecondAccurateVideoRulesAndTheSameId() {
        val saved=store.save(channel().copy(minVideoSeconds=61));store.select(saved.id)
        show();ui.onNodeWithTag("Channel edit ${saved.id}").performClick()
        editorField("Channel name").performTextReplacement("Release videos")
        hideKeyboard();editorField("Save channel").performClick()
        val edited=store.channels.value.single()
        assertEquals(saved.id,edited.id);assertEquals("Release videos",edited.name);assertEquals(61L,edited.minVideoSeconds)
        assertEquals(2L,edited.revision);assertEquals(saved.id,store.selected.value)
    }

    @Test fun overviewSelectsChannelsAndResetRequiresAnExplicitConfirmation() {
        store.save(channel());store.save(channel("local","Local"));show()
        evidence("native-channel-overview")
        ui.onNodeWithTag("Channel select local").performClick();assertEquals("local",store.selected.value)
        ui.runOnIdle {raw.edit().putString("personalChannels","broken").commit()}
        show();ui.onNodeWithTag("Channel storage error").assertIsDisplayed()
        ui.onNodeWithTag("Reset channels").performClick()
        assertEquals("local",store.selected.value);assertEquals("broken",raw.getString("personalChannels",null))
        ui.onNodeWithText("Cancel").performClick();assertEquals("local",store.selected.value)
        ui.onNodeWithTag("Reset channels").performClick();ui.onNodeWithTag("Confirm reset channels").performClick()
        assertNull(store.selected.value);assertTrue(store.channels.value.isEmpty());assertNull(store.error.value)
    }
}
