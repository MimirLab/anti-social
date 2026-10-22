/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Native presentation never changes the source or the saved/exported local copy. */
@RunWith(AndroidJUnit4::class)
class PostBodyDetailsUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(9)
    private val prose="The repair workshop and @incontext published a complete guide to replacing a laptop battery safely, including every tool and repair step."
    private val tail="@noise${suffix} @helpers$suffix #BatteryCare\nhttps://example.org/Guides/Battery$suffix"
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var searchPolicy: NetworkPolicy
    private val posts=mutableListOf<Post>()
    private val models=mutableListOf<AntiSocialViewModel>()

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();searchPolicy=repo.gateway.currentPolicy(NetworkRoute.TOR)
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.historyFullText=false;scheduleRefresh(ui.activity,repo.prefs)
        // These cases assert exact source wording and folding. Default-on name
        // presentation, including folded details, has dedicated native coverage.
        repo.prefs.readableMentions=false;repo.mentionNames.changed()
        ui.runOnIdle {ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].following()}
        repo.gateway.configure(NetworkPolicy(tor=true))
    }
    @After fun restore() {
        ui.runOnIdle {models.forEach {it.discussions.close();it.viewModelScope.cancel()};ui.activity.setContent {Text("Post detail fixture complete")}}
        runBlocking {
            models.forEach {it.viewModelScope.coroutineContext[Job]?.join()}
            posts.map(repo::key).distinct().forEach {key->
                repo.library.cancel(key);repo.library.remove(key);repo.dao.removePosts(listOf(key))
                repo.dao.deleteDocuments(key,"saved");repo.dao.deleteDocuments(key,"history");repo.dao.removeRetainedHistory(listOf(key))
                for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
            }
        }
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
            repo.gateway.configure(policy);repo.gateway.configureTor(searchPolicy);repo.library.notifyChanged()
        }
    }
    private fun post(index: Int=1,author: String="@RepairWorkshop",body: String="$prose\n\n$tail"): Post {
        val handle="noise${suffix.takeLast(6)}$index";val id="94$suffix${1000+index}"
        return Post(id,Platform.X,"https://x.com/$handle/status/$id",author,body,publishedAt=System.currentTimeMillis()-index*60_000L).also {posts+=it}
    }
    private fun model()=AntiSocialViewModel(repo).also {it.following();models+=it}
    private fun evidence(name: String) {
        ui.waitForIdle()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"post-details").apply {mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val roots=ui.onAllNodes(isRoot(),useUnmergedTree=true)
        File(dir,"$name-semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n"){index->"Root $index\n"+roots[index].printToString()})
    }
    @Test fun namedRoundupLinksAreAnExpandableInventoryRatherThanTheWholeCard() {
        val intro="In case you missed it, here is the complete lineup from the science summit!"
        val links="Alice: https://example.org/alice Bob Jones: https://example.org/bob Director Carol: https://example.org/carol"
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PostBodyText("$intro $links",tag="Roundup")
        }}}}
        ui.onNodeWithTag("Roundup").assertTextEquals(intro)
        ui.onNodeWithTag("Roundup details text").assertDoesNotExist()
        ui.onNodeWithTag("Roundup details toggle").performClick()
        ui.onNodeWithTag("Roundup details text").assertTextEquals(links)
        evidence("named-roundup-links")
    }
    @Test fun trailingReferencesExpandButSavingAndTheLocalReaderKeepTheEntireOriginal() {
        val post=post();val vm=model();val key=repo.key(post);val tag="Post body $key"
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("Post fixture")){PostCard(post,vm,false){}}}}}
        ui.onNodeWithTag(tag).assertTextEquals(prose)
        ui.onNodeWithText(tail).assertDoesNotExist()
        ui.onNodeWithTag("$tag details toggle").performScrollTo().assertTextContains("Mentions & links").performClick()
        ui.onNodeWithTag("$tag details text").assertTextEquals(tail).assertIsDisplayed()
        evidence("trailing-references-expanded")
        ui.onNodeWithTag("$tag details toggle").performClick()
        ui.onNodeWithTag("$tag details text").assertDoesNotExist()
        ui.onNodeWithContentDescription("Save post").performScrollTo().performClick()
        ui.waitUntil(10_000){runBlocking {repo.dao.bookmarksByKeys(listOf(key)).size==1}}
        val saved=runBlocking {repo.dao.bookmarksByKeys(listOf(key)).single()}.let {repo.gson.fromJson(it.payload,Post::class.java)}
        assertEquals(post.body,saved.body)
        val exported=runBlocking {repo.portability.exportState(includeProgress=false)}.toString(Charsets.UTF_8)
        assertTrue("Collapsed references remain part of portable saved content",exported.contains("@noise$suffix"))
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {LibraryScreen(repo,onOpenOriginal={})}}}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Library entry $key").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry $key").performScrollTo().performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Local post body").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local post body").assertTextEquals(prose)
        ui.onNodeWithTag("Local post body details text").assertDoesNotExist()
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasTestTag("Local post body details toggle"))
        ui.onNodeWithTag("Local post body details toggle").performClick()
        ui.onNodeWithTag("Local post body details text").assertTextEquals(tail)
        assertEquals(post.body,runBlocking {repo.library.copy(key)}!!.post.body)
        evidence("local-copy-retains-complete-references")
    }
    @Test fun fusedReferenceTailsStayWithTheirActualAccountAndExpandIndependently() {
        val first=post(1,"@RepairWorkshop");val second=post(2,"@CommunityArchive")
        val context="The original workshop also published a detailed safety checklist explaining how to disconnect power before opening the battery cover."
        val otherTail="@archive$suffix @library$suffix #PublicRepairs"
        val fusion=PostFusion(listOf(
            FusionSegment(prose,setOf(Platform.X),true,setOf(CrossPosts.originalKey(first),CrossPosts.originalKey(second))),
            FusionSegment("$context\n$tail",setOf(Platform.X),false,setOf(CrossPosts.originalKey(first))),
            FusionSegment(otherTail,setOf(Platform.X),false,setOf(CrossPosts.originalKey(second))),
        ))
        val sourceBytes=repo.gson.toJson(listOf(first,second))
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){FusedPostText(fusion,roots=listOf(first,second),repo=repo)}}}}
        ui.onNodeWithTag("Fusion shared text").assertTextEquals(prose)
        ui.onNodeWithTag("Fusion context 1").assertTextEquals(context).assert(hasAnySibling(hasText(first.author)))
        ui.onNodeWithTag("Fusion context 2 references").assert(hasAnySibling(hasText(second.author)))
        ui.onNodeWithText(tail).assertDoesNotExist();ui.onNodeWithText(otherTail).assertDoesNotExist()
        ui.onNodeWithTag("Fusion context 1 details toggle").performScrollTo().performClick()
        ui.onNodeWithTag("Fusion context 1 details text").assertTextEquals(tail)
        ui.onNodeWithTag("Fusion context 2 details text").assertDoesNotExist()
        ui.onNodeWithTag("Fusion context 2 details toggle").performScrollTo().performClick()
        ui.onNodeWithTag("Fusion context 2 details text").assertTextEquals(otherTail)
        assertEquals(sourceBytes,repo.gson.toJson(listOf(first,second)))
        evidence("fused-tails-keep-source-account-attribution")
    }
    @Test fun everyAttributedAccountIsReachableInTheHeaderAndOpensEveryIntactOriginal() {
        val first=post(1,"@RepairWorkshop");val second=post(2,"@CommunityArchive");val vm=model()
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){PostCard(first,vm,false,discussions=listOf(first,second)){}}}}}
        val authors=ui.onNodeWithTag("Post authors ${repo.key(first)}")
        ui.onNodeWithTag("Post author ${CrossPosts.originalAccount(first)}").assertExists()
        authors.performTouchInput {swipeLeft(durationMillis=420)}
        ui.onNodeWithTag("Post author ${CrossPosts.originalAccount(second)}").assertIsDisplayed().performClick()
        ui.onNodeWithTag("Collected originals").assertExists()
        for(original in listOf(first,second)) {
            ui.onNodeWithTag("Collected original list").performScrollToNode(hasTestTag("Original URL ${repo.key(original)}"))
            ui.onNodeWithTag("Original URL ${repo.key(original)}").assertTextEquals(original.canonicalUrl)
            ui.onNodeWithTag("Original caption ${repo.key(original)}").assertTextContains("@noise$suffix",substring=true)
            ui.onNodeWithTag("Original comments ${repo.key(original)}").assertExists()
        }
        evidence("all-attributed-accounts-open-intact-originals")
    }
    @Test fun loneMediaPermalinksAndTwoLinkInventoriesStayQuietUntilExpanded() {
        val vm=model()
        for(count in 1..2) {
            val initial=post(10+count,body="A public workshop report explains each battery repair step and shows the complete replacement in a short video.")
            val links=if(count==1)listOf(initial.canonicalUrl+"#m")else listOf("https://x.com/RepairWorkshop",initial.canonicalUrl+"#m")
            val record=initial.copy(links=links).also {posts+=it};val key=repo.key(record);val original=repo.gson.toJson(record)
            ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){PostCard(record,vm,false){}}}}}
            links.forEach {ui.onNodeWithTag("Post link $it").assertDoesNotExist()}
            ui.onNodeWithTag("Post extra links $key").performScrollTo().assertTextContains(if(count==1)"1 link"else "2 links").performClick()
            links.forEach {ui.onNodeWithTag("Post link $it").performScrollTo().assertExists()}
            assertEquals(original,repo.gson.toJson(record))
            if(count==2)evidence("small-extracted-link-inventory-expanded")
            ui.onNodeWithTag("Post extra links $key").performScrollTo().performClick()
            links.forEach {ui.onNodeWithTag("Post link $it").assertDoesNotExist()}
            if(count==2)evidence("small-extracted-link-inventory-collapsed")
        }
    }
    @Test fun aSinglePlainReadMoreFooterUsesTheSameExpandableDetails() {
        val footer="Read more ⬇️ https://example.org/releases/battery$suffix"
        val record=post(20,body="$prose\n\n$footer");val vm=model();val tag="Post body ${repo.key(record)}"
        val original=repo.gson.toJson(record)
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){PostCard(record,vm,false){}}}}}
        ui.onNodeWithTag(tag).assertTextEquals(prose)
        ui.onNodeWithText(footer).assertDoesNotExist()
        ui.onNodeWithTag("$tag details toggle").performScrollTo().performClick()
        ui.onNodeWithTag("$tag details text").assertTextEquals(footer)
        assertEquals(original,repo.gson.toJson(record))
        evidence("single-read-more-footer-expanded")
    }
    @Test fun leadingReferencesAndFooterLinksLiveInOneNestedInventory() {
        val header="Links: https://example.org/source$suffix\n@workshop$suffix"
        val footer="Read more: https://example.org/releases/battery$suffix"
        val link="https://example.org/attachment$suffix"
        val record=post(30,body="$header\n\n$prose\n\n$footer").copy(links=listOf(link)).also {posts+=it}
        val vm=model();val key=repo.key(record);val tag="Post body $key";val original=repo.gson.toJson(record)
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){PostCard(record,vm,false){}}}}}
        ui.onNodeWithTag(tag).assertTextEquals(prose)
        ui.onNodeWithText(header).assertDoesNotExist();ui.onNodeWithText(footer).assertDoesNotExist()
        ui.onNodeWithTag("Post link $link").assertDoesNotExist()
        ui.onNodeWithTag("$tag details toggle").assertDoesNotExist()
        ui.onAllNodesWithText("Mentions & links").assertCountEquals(1)
        evidence("one-inventory-leading-and-trailing-collapsed")
        ui.onNodeWithTag("Post extra links $key").performScrollTo().performClick()
        ui.onNodeWithTag("$tag details text").assertTextEquals("$header\n\n$footer")
        ui.onNodeWithTag("Post link $link").performScrollTo().assertExists()
        assertEquals(original,repo.gson.toJson(record))
        evidence("one-inventory-leading-and-trailing-expanded")
    }
}
