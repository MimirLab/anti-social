package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Dwell and native presentation fixtures never ask an Internet archive to fetch test posts. */
@RunWith(AndroidJUnit4::class)
class ArchiveHistoryUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private val models=mutableListOf<AntiSocialViewModel>()
    private val current=Post("archive-ui-fixture",Platform.X,"https://x.com/antisocial_archive_fixture/status/80001","Public author","Current edited post text",publishedAt=1_760_000_000_000L)

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString()
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
    }
    @After fun restore() {
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.runOnIdle {
            models.forEach {it.discussions.close();it.viewModelScope.cancel()}
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        runBlocking {
            repo.dao.removeBookmark(repo.key(current))
            for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(repo.key(current)))
        }
    }
    private fun model()=AntiSocialViewModel(repo).also {it.following();models+=it}
    private fun pause(ms: Long) {android.os.SystemClock.sleep(ms);ui.waitForIdle()}
    private fun evidence(name: String) {
        ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"archive-history").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun composedOffscreenCardsDoNotCheckUntilActuallyVisible() {
        val calls=AtomicInteger();var reveal by mutableStateOf(false)
        ui.runOnUiThread {ui.activity.setContent {
            MaterialTheme {
                Box(Modifier.size(240.dp).clipToBounds()) {
                    val dwell=rememberArchiveDwellModifier("visibility-fixture",true,350) {calls.incrementAndGet()}
                    Box(Modifier.offset(y=if(reveal)0.dp else 320.dp).size(180.dp).then(dwell).testTag("Dwell fixture")) {Text("Composed archive dwell fixture")}
                }
            }
        }}
        ui.onNodeWithTag("Dwell fixture").assertExists().assertIsNotDisplayed()
        pause(550);assertEquals(0,calls.get())
        ui.runOnIdle {reveal=true}
        ui.onNodeWithTag("Dwell fixture").assertIsDisplayed()
        ui.waitUntil(2_000){calls.get()==1}
        pause(450);assertEquals(1,calls.get())
    }

    @Test fun movementRestartsAContinuousDwell() {
        val calls=AtomicInteger();var offset by mutableStateOf(0.dp)
        ui.runOnUiThread {ui.activity.setContent {
            MaterialTheme {
                val dwell=rememberArchiveDwellModifier("movement-fixture",true,1_000) {calls.incrementAndGet()}
                Box(Modifier.size(240.dp).clipToBounds()) {Box(Modifier.offset(y=offset).size(180.dp).then(dwell)) {Text("Read only while resting")}}
            }
        }}
        ui.waitForIdle();pause(450)
        ui.runOnIdle {offset=12.dp}
        pause(450);assertEquals(0,calls.get())
        ui.waitUntil(2_000){calls.get()==1}
    }

    @Test fun leavingTheResumedActivityCancelsTheDwell() {
        val calls=AtomicInteger()
        ui.runOnUiThread {ui.activity.setContent {
            MaterialTheme {
                val dwell=rememberArchiveDwellModifier("lifecycle-fixture",true,1_000) {calls.incrementAndGet()}
                Box(Modifier.size(180.dp).then(dwell)) {Text("Foreground reading only")}
            }
        }}
        ui.waitForIdle();pause(300)
        ui.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        android.os.SystemClock.sleep(800)
        assertEquals(0,calls.get())
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        pause(300);assertEquals(0,calls.get())
        ui.waitUntil(2_000){calls.get()==1}
    }

    @Test fun scrollingAwayAlsoCancelsAnArchiveReadAlreadyInProgress() {
        val started=AtomicInteger();val cancelled=AtomicInteger();var visible by mutableStateOf(true)
        ui.runOnUiThread {ui.activity.setContent {
            MaterialTheme {
                val dwell=rememberArchiveDwellModifier("request-cancellation-fixture",true,250) {
                    started.incrementAndGet()
                    try {awaitCancellation()}finally {cancelled.incrementAndGet()}
                }
                Box(Modifier.size(240.dp).clipToBounds()) {
                    Box(Modifier.offset(y=if(visible)0.dp else 320.dp).size(180.dp).then(dwell)) {Text("Cancellable archive read")}
                }
            }
        }}
        ui.waitUntil(2_000){started.get()==1}
        ui.runOnIdle {visible=false}
        ui.waitUntil(2_000){cancelled.get()==1}
        pause(400);assertEquals(1,started.get())
    }

    @Test fun abruptLazyListJumpsExcludeThePreviouslyVisiblePost() {
        val calls=AtomicInteger()
        ui.runOnUiThread {ui.activity.setContent {
            MaterialTheme {
                val list=rememberLazyListState()
                ArchiveListViewport(list) {
                    LazyColumn(state=list,modifier=Modifier.height(260.dp).testTag("Archive dwell list")) {
                        items((0..8).toList(),key={if(it==6)"history:lazy-fixture-0"else "post:lazy-fixture-$it"}) {index->
                            val key="lazy-fixture-$index"
                            val dwell=rememberArchiveDwellModifier(key,index==0&&archiveViewportAllows(key),1_000) {calls.incrementAndGet()}
                            Box(Modifier.fillMaxWidth().height(180.dp).then(dwell)) {Text("Lazy archive fixture $index")}
                        }
                    }
                }
            }
        }}
        ui.onNodeWithText("Lazy archive fixture 0").assertIsDisplayed()
        pause(400)
        // A visible reading-history row for the same identity is not its post card.
        ui.onNodeWithTag("Archive dwell list").performScrollToIndex(6)
        pause(1_100);assertEquals(0,calls.get())
        ui.onNodeWithText("Lazy archive fixture 6").assertIsDisplayed()
        ui.onNodeWithTag("Archive dwell list").performScrollToIndex(0)
        ui.waitUntil(2_500){calls.get()==1}
    }

    @Test fun archiveClockSwapsTheNativeCardAndUndoRestoresCurrentContent() {
        val archived=ArchivedVersion("Internet Archive",1_770_000_000_000L,"https://web.archive.org/web/20260202024000id_/https://x.com/antisocial_archive_fixture/status/80001",PostSnapshot.from(current.copy(body="Earlier archived post text")),ArchiveContent.signature(current))
        val paired=current.copy(archivedVersion=archived)
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {PostCard(paired,vm,false) {}}}}
        ui.onNodeWithText(current.body).assertIsDisplayed()
        ui.onNodeWithText("Earlier archived post text").assertDoesNotExist()
        ui.onNodeWithContentDescription("Show oldest available archived version").assertIsDisplayed().performClick()
        ui.onNodeWithText("Earlier archived post text").assertIsDisplayed()
        ui.onNodeWithText(current.body).assertDoesNotExist()
        ui.onNodeWithText("Oldest available snapshot").assertIsDisplayed()
        ui.onNodeWithContentDescription("Show current post").assertIsDisplayed()
        ui.onNodeWithContentDescription("Save post").assertExists()
        ui.onNodeWithTag("Read discussions ${repo.key(current)}").assertExists()
        ui.onNodeWithContentDescription("Save post").performClick()
        ui.waitUntil(3_000){runBlocking {repo.dao.bookmarksByKeys(listOf(repo.key(current))).isNotEmpty()}}
        val saved=runBlocking {repo.gson.fromJson(repo.dao.bookmarksByKeys(listOf(repo.key(current))).single().payload,Post::class.java)}
        assertEquals(current.body,saved.body)
        assertEquals(current.canonicalUrl,saved.canonicalUrl)
        assertEquals("Earlier archived post text",requireNotNull(saved.archivedVersion).snapshot.body)
        evidence("native-archived-version")
        ui.onNodeWithContentDescription("Show current post").performClick()
        ui.onNodeWithText(current.body).assertIsDisplayed()
        ui.onNodeWithText("Earlier archived post text").assertDoesNotExist()
        ui.onNodeWithText("Oldest available snapshot").assertDoesNotExist()
        ui.onNodeWithContentDescription("Show oldest available archived version").assertIsDisplayed()
        evidence("native-current-version")
    }

    @Test fun missingArchiveEvidenceNeverDisplaysAClock() {
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {PostCard(current,vm,false) {}}}}
        ui.onNodeWithText(current.body).assertIsDisplayed()
        ui.onNodeWithTag("Archive version toggle").assertDoesNotExist()
        ui.onNodeWithText("Oldest available snapshot").assertDoesNotExist()
    }
}
