/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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

/** The cache-clear case is explicitly limited to the disposable development emulator. */
@RunWith(AndroidJUnit4::class)
class AccountNameSettingsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private val suffix=System.nanoTime().toString().takeLast(8)
    private val username="acct$suffix"
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var searchPolicy: NetworkPolicy
    private var disposableCache=false
    private var fixtureKey: String?=null
    private var fixtureFollow: String?=null

    @Before fun preserve() {
        runBlocking {repo.awaitReady();repo.mentionNames.awaitReady()}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();searchPolicy=repo.gateway.currentPolicy(NetworkRoute.TOR)
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.historyFullText=false
        ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().remove("readableMentions").remove("mentionLookups").apply()
        ui.runOnIdle {
            repo.cancelSearchPreparation();repo.tor.enable(false);repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
            repo.mentionNames.changed();model().section("settings")
        }
    }
    @After fun restore() {
        ui.runOnIdle {ui.activity.setContent {Text("Account names settings fixture complete")}}
        runBlocking {
            fixtureKey?.let {key->repo.dao.removeBookmark(key);repo.dao.removePosts(listOf(key))}
            fixtureFollow?.let {repo.dao.removeFollow(it)}
            if(disposableCache)repo.mentionNames.clear()
        }
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.mentionNames.changed();repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
            repo.gateway.configure(policy);repo.gateway.configureTor(searchPolicy)
            model().following()
        }
    }
    private fun openNames() {
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Settings destination account_names"))
        ui.onNodeWithTag("Settings destination account_names").performClick()
        ui.onNodeWithTag("Settings title").assertTextEquals("Account names")
    }
    private fun evidence(name: String) {
        ui.mainClock.advanceTimeBy(1_000);ui.waitForIdle()
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"mention-names").apply {mkdirs()}
        android.os.SystemClock.sleep(200) // Let Android present the settled Compose frame.
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val roots=ui.onAllNodes(isRoot(),useUnmergedTree=true)
        File(directory,"$name-semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n"){index->"Root $index\n"+roots[index].printToString()})
    }

    @Test fun nestedDefaultsAreEnabledAndTheMasterSwitchStopsPresentationAndAccountRequests() {
        assertTrue(Preferences(ui.activity).readableMentions)
        assertTrue(Preferences(ui.activity).mentionLookups)
        openNames()
        ui.onNodeWithTag("Setting toggle Readable mentions").assertIsOn().performClick().assertIsOff()
        assertFalse(repo.prefs.readableMentions);assertFalse(repo.mentionNames.enabled.value)
        assertTrue(repo.prefs.mentionLookups)
        val target=repo.routes.target(Platform.X,username)
        val account=MentionNames.account(target,repo.routes)!!
        val before=repo.gateway.protectionStats()
        val lease=repo.mentionNames.observe(listOf(account),networkAllowed=true)
        try {
            android.os.SystemClock.sleep(1_650)
            assertEquals("The disabled feature does not ask the gateway for an account",before,repo.gateway.protectionStats())
        }finally {lease.close()}
        ui.onNodeWithTag("Setting toggle Readable mentions").performClick().assertIsOn()
        assertTrue(repo.prefs.readableMentions);assertTrue(repo.mentionNames.enabled.value)
        ui.onNodeWithTag("Setting toggle Look up missing names").assertIsOn().performClick().assertIsOff()
        assertFalse(Preferences(ui.activity).mentionLookups)
        assertTrue(Preferences(ui.activity).readableMentions)
        evidence("account-name-settings")
    }

    @Test fun lookupOffKeepsLocalNamesAndForgettingOnlyTheNameBankPreservesReaderData() {
        Assume.assumeTrue("Cache clearing is only run on the explicitly isolated fixture emulator",InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        disposableCache=true
        runBlocking {repo.mentionNames.clear()}
        openNames()
        ui.onNodeWithTag("Setting toggle Look up missing names").performClick().assertIsOff()
        val target=repo.routes.target(Platform.X,username)
        val caption="The detailed workshop notes from @$username explain every safe laptop battery repair step and the tools required."
        val post=Post("198$suffix",Platform.X,"https://x.com/$username/status/198$suffix","Repair Workshop",caption,publishedAt=System.currentTimeMillis())
        val key=repo.key(post);fixtureKey=key
        val follow=Follow("name-settings-$suffix",target,"Repair Workshop")
        fixtureFollow=follow.id
        val payload=repo.gson.toJson(post)
        val postRow=PostRow(key,post.platform.id,payload,post.publishedAt,System.currentTimeMillis(),bytes=payload.toByteArray().size.toLong(),followId=follow.id)
        val followRow=FollowRow(follow.id,Platform.X.id,repo.gson.toJson(follow))
        val savedRow=BookmarkRow(key,Platform.X.id,payload,System.currentTimeMillis())
        runBlocking {
            repo.dao.putFollow(followRow);repo.dao.putPost(postRow);repo.dao.putBookmark(savedRow)
            assertTrue(repo.mentionNames.seed(Profile(target,"Battery Repair Workshop",displayNameSupplied=true)))
        }
        ui.waitUntil(5_000){repo.mentionNames.stats.value.names==1}
        ui.onNodeWithTag("Settings list").performScrollToNode(hasText("Remembered names"))
        ui.onNodeWithText("1").assertExists()
        val before=repo.gateway.protectionStats()
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column(Modifier.padding(20.dp)) {
            Text("Saved local context")
            MentionText(caption,repo,Platform.X,post.canonicalUrl,networkAllowed=false,modifier=Modifier.testTag("Cached account caption"))
        }}}}
        ui.onNodeWithTag("Cached account caption").assertTextEquals(caption.replace("@$username","Battery Repair Workshop"))
        ui.waitForIdle()
        assertEquals(before,repo.gateway.protectionStats())
        evidence("lookups-off-retains-cached-name")
        // SettingsScreen is the page content; AntiSocialApp owns the real toolbar and navigation.
        ui.runOnIdle {ui.activity.setContent {AntiSocialApp(model())}}
        ui.onNodeWithTag("Settings title").assertTextEquals("Account names")
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithTag("Settings title").assertTextEquals("Settings")
        openNames()
        ui.onNodeWithTag("Settings list").performScrollToNode(hasText("Forget remembered names"))
        ui.onNodeWithText("Forget remembered names").performClick()
        ui.waitUntil(5_000){repo.mentionNames.stats.value.entries==0&&repo.mentionNames.names.value.isEmpty()}
        runBlocking {
            assertEquals(postRow,repo.dao.post(key))
            assertEquals(followRow,repo.dao.allFollows().single {it.id==follow.id})
            assertEquals(savedRow,repo.dao.bookmarksByKeys(listOf(key)).single())
        }
        assertFalse(repo.prefs.mentionLookups);assertTrue(repo.prefs.readableMentions)
        ui.onNodeWithText("0").assertExists()
        evidence("forget-names-preserves-reader-data")
    }
}
