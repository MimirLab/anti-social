/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

/** Native display/copy/navigation boundaries use originals; source records remain intact. */
@RunWith(AndroidJUnit4::class)
class PublicLinksTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val id="99${suffix}900"
    private val original="https://x.com/WhiteHouse/status/$id"
    private val raw="https://nitter.net/WhiteHouse/status/$id#m"
    private val profile="https://nitter.tiekoetter.com/realDonaldTrump"
    private val search="https://nitter.tiekoetter.com/search?f=tweets&q=%23Georgia"
    private val searchOriginal="https://x.com/search?f=tweets&q=%23Georgia"
    private val linkedPost="https://nitter.net/antisociallinks/status/99${suffix}901"
    private val linkedOriginal="https://x.com/antisociallinks/status/99${suffix}901"
    private val root get()=Post(id,Platform.X,raw,"@WhiteHouse","A public update. Read $profile and $search.",publishedAt=System.currentTimeMillis()-60_000,links=listOf(profile,search,linkedPost),provenance=Provenance(Source(Platform.X,"nitter","https://nitter.net"),System.currentTimeMillis(),raw))
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var loader: suspend (Target,(String)->Unit)->Page
    private lateinit var validator: suspend (ShareRouteCandidate)->ShareRouteCheck
    private val models=mutableListOf<AntiSocialViewModel>()
    private val records=mutableListOf<Post>()

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();loader=repo.directLoader
        validator=repo.outgoing.validate;repo.outgoing.clear();repo.outgoing.validate={ShareRouteCheck(ShareRouteStatus.READABLE,it.url)}
        repo.prefs.originalFallback=true
        repo.prefs.archiveChecks=false;repo.prefs.communityComments=false;repo.prefs.intervalMinutes=0;repo.prefs.theme="dark"
        repo.prefs.selected=Platform.entries.toList();repo.prefs.activePlatform=Platform.X
        Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
        ui.runOnIdle {ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].following()}
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
    }
    @After fun restore() {
        ui.runOnIdle {models.forEach {it.discussions.close();it.viewModelScope.cancel()};ui.activity.setContent {Text("Original-link fixture complete")}}
        runBlocking {records.forEach {post->
            val key=repo.key(post);repo.library.cancel(key);repo.dao.removeBookmark(key);repo.dao.deleteDocuments(key,"saved");repo.dao.deleteDocuments(key,"history");repo.dao.removeRetainedHistory(listOf(key));repo.dao.removePosts(listOf(key))
            for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }}
        repo.directLoader=loader;repo.outgoing.validate=validator
        ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
        repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy);repo.library.notifyChanged()
    }
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(250)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"public-links").apply {mkdirs()}
        File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        File(folder,"$name-tree.txt").writeText(ui.onRoot().printToString())
    }
    private fun copied(): String? {
        var value: String?=null
        ui.runOnIdle {value=ui.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()}
        return value
    }
    @Test fun feedShowsAuthenticLinksCopiesTheOriginalAndRoutesTheOriginalSearch() {
        val post=root.also {records+=it};val rawJson=repo.gson.toJson(post)
        val requested=CopyOnWriteArrayList<Target>()
        repo.directLoader={target,_->
            requested+=target
            Page(listOf(Post(target.identity,target.platform,target.canonicalUrl,"@antisociallinks","A locally supplied direct-post fixture.",publishedAt=System.currentTimeMillis()-60_000).also {records+=it}))
        }
        val vm=AntiSocialViewModel(repo,createSearch={target->
            requested+=target
            SearchFeed(target,discover={Page(emptyList(),notice="Fixture search supplied no posts")},read={error("An empty fixture must not hydrate results")})
        }).also {models+=it;it.following()}
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface {LazyColumn(Modifier.fillMaxSize().testTag("Public link fixture")) {item {PostCard(post,vm,false){}}}}}}}
        ui.onNodeWithText("A public update. Read https://x.com/realDonaldTrump and $searchOriginal.").assertExists()
        ui.onNodeWithTag("Post extra links ${repo.key(post)}").performScrollTo().performClick()
        // Account links use readable labels; their action and copied destination remain authentic.
        ui.onNodeWithTag("Post link https://x.com/realDonaldTrump").assertTextEquals("realDonaldTrump")
        ui.onNodeWithTag("Post link $searchOriginal").assertTextContains(searchOriginal.removePrefix("https://"))
        ui.onNodeWithTag("Post options ${repo.key(post)}").performScrollTo().performClick()
        ui.onNodeWithTag("Copy post link ${repo.key(post)}").performClick()
        ui.waitUntil(5_000){copied()==original}
        assertEquals(original,copied())
        evidence("feed-authentic-links-native")
        ui.onNodeWithTag("Post link https://x.com/realDonaldTrump").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.screen.value.target!=null||vm.screen.value.error!=null}
        assertEquals("Profile navigation should retain its authentic destination: ${vm.screen.value.error}","https://x.com/realDonaldTrump",vm.screen.value.target?.canonicalUrl)
        assertEquals(TargetKind.PROFILE,vm.screen.value.target?.kind)
        ui.onNodeWithTag("Post link $linkedOriginal").performScrollTo().performClick()
        ui.waitUntil(5_000){requested.any {it.canonicalUrl==linkedOriginal}||vm.screen.value.error!=null}
        assertTrue("The direct-post reader should receive its authentic destination: ${vm.screen.value.error}",requested.any {it.canonicalUrl==linkedOriginal})
        ui.onNodeWithTag("Post link $searchOriginal").performScrollTo().performTouchInput {longClick()}
        ui.waitUntil(5_000){copied()==searchOriginal}
        assertEquals(searchOriginal,copied())
        ui.onNodeWithTag("Post link $searchOriginal").performClick()
        ui.waitUntil(10_000){requested.any {it.canonicalUrl==searchOriginal}}
        assertEquals(Platform.X,requested.last().platform);assertEquals(TargetKind.SEARCH,requested.last().kind)
        assertEquals(rawJson,repo.gson.toJson(post));assertEquals(raw,post.provenance!!.finalUrl)
    }
    @Test fun savedCopyShowsCopiesAndOpensTheAuthenticLinkWithoutRewritingSavedSources() {
        val post=root.also {records+=it};val rawJson=repo.gson.toJson(post)
        runBlocking {repo.library.save(post,emptyList());repo.library.cancel(repo.key(post))}
        var opened: Post?=null
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface {LibraryScreen(repo,{opened=it})}}}}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Library entry ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry ${repo.key(post)}").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasTestTag("Local original URL"))
        ui.onNodeWithTag("Local original URL").assertTextEquals(original)
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasTestTag("Copy local original link"))
        ui.onNodeWithTag("Copy local original link").performClick();ui.waitUntil(5_000){copied()==original}
        assertEquals(original,copied())
        evidence("saved-authentic-original-native")
        ui.onNodeWithTag("Open original from local copy").performClick()
        assertEquals(original,opened?.canonicalUrl)
        val saved=runBlocking {repo.library.copy(repo.key(post))}!!
        assertEquals(rawJson,repo.gson.toJson(saved.post));assertEquals(raw, saved.post.provenance!!.finalUrl)
    }
}
