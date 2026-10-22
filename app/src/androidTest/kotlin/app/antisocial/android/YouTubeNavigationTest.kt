/* AntiSocial, GPL-3.0-or-later. Native fixtures do not certify upstream availability. */
package app.antisocial.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class YouTubeNavigationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private val id="BaW_jenozKc"
    private val canonical="https://www.youtube.com/watch?v=$id"
    private val post=Post("post:$id",Platform.YOUTUBE,canonical,"Public workshop","A complete public guide explains the tools, safe disassembly and battery replacement.","Replace a laptop battery",media=listOf(Media(canonical,"video",durationMs=182000L)))
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var loader: suspend (Target,(String)->Unit)->Page
    private lateinit var validator: suspend (ShareRouteCandidate)->ShareRouteCheck
    private lateinit var initialIntent: Intent
    private val requested=CopyOnWriteArrayList<Target>()

    @Before fun preserve()=runBlocking {
        repo.awaitReady();preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();loader=repo.directLoader;validator=repo.outgoing.validate
        ui.runOnIdle {initialIntent=ui.activity.intent}
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.historyFullText=false
        repo.prefs.selected=listOf(Platform.X);repo.prefs.activePlatform=Platform.X
        repo.directLoader={target,_->requested+=target;Page(listOf(post))}
        repo.outgoing.validate={ShareRouteCheck(ShareRouteStatus.READABLE)}
        // The Activity's first model predates @Before's selected-site settings.
        // Recreate it with those settings while retaining MainActivity's real
        // incoming-intent handlers, rather than replacing its content wrapper.
        ui.runOnIdle {ui.activity.viewModelStore.clear()}
        ui.activityRule.scenario.recreate()
        ui.runOnIdle {model().following()}
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
    }
    @After fun restore() {
        val vm=model()
        ui.runOnIdle {ui.activity.intent=initialIntent;vm.discussions.close();vm.following();vm.viewModelScope.cancel();ui.activity.setContent {Text("YouTube navigation fixture complete")}}
        runBlocking {
            vm.viewModelScope.coroutineContext[Job]?.join()
            val key=repo.key(post);repo.dao.removePosts(listOf(key));repo.dao.deleteDocuments(key,"history");repo.dao.removeRetainedHistory(listOf(key))
            for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }
        repo.directLoader=loader;repo.outgoing.validate=validator
        ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
        repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy);scheduleRefresh(ui.activity,repo.prefs)
    }
    private fun evidence(name: String) {
        ui.mainClock.advanceTimeBy(1_000);ui.waitForIdle();android.os.SystemClock.sleep(200)
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"youtube").apply {mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val roots=ui.onAllNodes(isRoot(),useUnmergedTree=true)
        File(dir,"$name-semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n"){roots[it].printToString()})
    }
    private fun copied(): String? {
        var result: String?=null
        ui.runOnIdle {result=ui.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()}
        return result
    }
    @Test fun incomingShortLinkOpensANativeVideoCardAndCopiesItsAuthenticWatchAddress() {
        ui.runOnIdle {ui.activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://youtu.be/$id?si=discarded")).setClass(ui.activity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))}
        ui.waitUntil(10_000){model().screen.value.target?.canonicalUrl==canonical&&!model().screen.value.loading}
        assertEquals(canonical,requested.last().canonicalUrl)
        ui.onNodeWithTag("Platform tab youtube").assertDoesNotExist()
        assertFalse(Platform.YOUTUBE in repo.prefs.selected);assertEquals(Platform.X,repo.prefs.activePlatform)
        ui.onNodeWithText("Linked video").assertExists()
        ui.onNodeWithText(post.title).assertExists();ui.onNodeWithText(post.body).assertDoesNotExist()
        ui.onNodeWithText("Play video").assertIsDisplayed();ui.onNodeWithTag("Read discussions ${repo.key(post)}").assertExists()
        ui.onNodeWithTag("Video description ${repo.key(post)}").performScrollTo().performClick()
        ui.onNodeWithText(post.body).assertExists()
        ui.onNodeWithTag("Video description ${repo.key(post)}").performClick()
        ui.onNodeWithTag("Post options ${repo.key(post)}").performScrollTo().performClick()
        ui.onNodeWithTag("Copy post link ${repo.key(post)}").performClick()
        ui.waitUntil(5_000){copied()==canonical};assertEquals(canonical,copied())
        evidence("incoming-video-native-card")
    }
    @Test fun subscriptionAndSearchPickersOfferSocialSourcesWithoutAYouTubeTab() {
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {SubscriptionDialog(model(),Platform.YOUTUBE,onDismiss={})}}}
        ui.onNodeWithTag("Subscription platform youtube").assertDoesNotExist()
        ui.onNodeWithTag("Subscription platform twitter").assertIsSelected()
        ui.onNodeWithText("@channel, channel or video link").assertDoesNotExist()
        evidence("social-subscription-picker")
        ui.runOnIdle {ui.activity.setContent {AntiSocialApp(model())};model().section("settings");model().settings(SettingsPage.PLATFORMS)}
        ui.onNodeWithTag("Platform toggle youtube").assertDoesNotExist()
        ui.onNodeWithTag("Settings list").performScrollToNode(hasText("ADD A TAB"))
        ui.onNodeWithTag("Platform toggle youtube").assertDoesNotExist()
        evidence("social-platform-settings")
    }
    @Test fun videoLinkAliasesStayAvailableIndependentlyOfSocialTabsAndExcludeChannelSearchPaths() {
        fun handlers(url: String)=ui.activity.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(ui.activity.packageName),PackageManager.MATCH_DEFAULT_ONLY).map {it.activityInfo.name}
        val alias=LinkOpening.component(ui.activity,Platform.YOUTUBE).className
        fun verify() {
            for(host in SocialLinkHosts.hosts.getValue(Platform.YOUTUBE)) {
                val path=if(host.endsWith("youtu.be"))"/$id"else "/watch?v=$id"
                assertEquals(listOf(alias),handlers("https://$host$path"))
            }
            for(path in listOf("/@NASA","/channel/UC1234567890123456789012","/results?search_query=workshop","/playlist?list=example"))assertTrue(handlers("https://www.youtube.com$path").isEmpty())
        }
        verify()
        repo.prefs.selected=listOf(Platform.REDDIT);LinkOpening.sync(ui.activity,repo.prefs.selected)
        verify();assertFalse(Platform.YOUTUBE in repo.prefs.selected)
    }
    @Test fun channelAndSearchEntryPointsShowALocalExplanationWithoutReadingAnUploadFeed() {
        for(target in listOf(repo.routes.target(Platform.YOUTUBE,"@NASA"),repo.routes.search(Platform.YOUTUBE,"battery repair"))) {
            ui.runOnIdle {model().open(target)}
            assertTrue(requested.isEmpty());assertNull(model().screen.value.target)
            ui.onNodeWithTag("Reader scope notice").assertIsDisplayed()
            ui.onNodeWithText(ReaderScope.VIDEO_ONLY).assertExists()
            ui.onNodeWithContentDescription("Dismiss notice").performClick()
        }
        ui.runOnIdle {model().openInput(Platform.YOUTUBE,"battery repair",searching=true)}
        assertTrue(requested.isEmpty());ui.onNodeWithText(ReaderScope.VIDEO_ONLY).assertExists()
        evidence("video-only-scope-notice")
    }
    @Test fun aLinkedVideoReturnsToTheExactSocialReadingScopeWithoutChangingActiveTabs() {
        ui.runOnIdle {model().combined()}
        val previous=model().screen.value
        val reading=repo.prefs.readingView
        ui.runOnIdle {model().open(repo.routes.recognize(canonical)!!)}
        ui.waitUntil(10_000){model().screen.value.target?.canonicalUrl==canonical&&!model().screen.value.loading}
        assertEquals(reading,repo.prefs.readingView);assertEquals(Platform.X,repo.prefs.activePlatform)
        ui.onNodeWithTag("Platform tab youtube").assertDoesNotExist()
        ui.runOnIdle {assertTrue(model().back())}
        assertEquals(previous,model().screen.value)
        assertTrue(model().screen.value.combined);assertEquals(Platform.X,model().screen.value.platform)
        ui.onNodeWithTag("Platform tab all").assertIsSelected()
        evidence("social-feed-after-linked-video")
    }
    @Test fun aLongDescriptionStaysFoldedSoLinkedVideoControlsAreVisibleImmediately() {
        val long=post.copy(body=("Long source description with background context and notes. ").repeat(150))
        repo.directLoader={target,_->requested+=target;Page(listOf(long))}
        ui.runOnIdle {model().open(repo.routes.recognize(canonical)!!)}
        ui.waitUntil(10_000){!model().screen.value.loading&&model().screen.value.page!=null}
        ui.onNodeWithText("Play video").assertIsDisplayed()
        ui.onNodeWithText(long.body).assertDoesNotExist()
        evidence("linked-video-player-first")
        ui.onNodeWithTag("Video description ${repo.key(post)}").performScrollTo().performClick()
        ui.onNodeWithText(long.body).assertExists()
        assertEquals(long.body,model().screen.value.page!!.posts.single().body)
    }

}
