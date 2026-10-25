/* anti social, GPL-3.0-or-later. Explicit live public-reader qualification. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class InstagramCommentsNativeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun publicRepliesReachAllAndTheNativeMoreRepliesButton() {
        val args=androidx.test.platform.app.InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLivePublicComments")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {withTimeout(30_000){repo.awaitReady()}}
        val preferences=repo.prefs.export().toString();val policy=repo.gateway.currentPolicy()
        val vm=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        val output=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"instagram-comments").apply {mkdirs()}
        val rows=mutableListOf<Map<String,Any?>>()
        try {
            ui.runOnIdle {
                repo.prefs.intervalMinutes=0;repo.prefs.communityComments=false;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false
                repo.prefs.tor=false;repo.prefs.originalFallback=true;repo.applyNetworkSettings();repo.tor.enable(false);repo.cancelSearchPreparation();scheduleRefresh(repo.context,repo.prefs)
            }
            val target=repo.routes.recognize("https://www.instagram.com/p/DeTUk4sDSp-/")!!
            val post=runBlocking {withTimeout(45_000){repo.pool.load(target,originalFallback=true)}}.posts.single {ContentIdentity.key(it.platform,it.canonicalUrl)=="post:DeTUk4sDSp-"}
            ui.runOnIdle {ui.activity.setContent {MaterialTheme {DiscussionSheet(vm)}};vm.discussions.open(listOf(post),"all")}
            ui.waitUntil(60_000){vm.discussions.state.value?.thread(post)?.let {!it.loading&&(it.page!=null||it.error!=null)}==true}
            val first=vm.discussions.state.value!!.thread(post)!!;assertNull(first.error);val initial=Discussions.comments(post,requireNotNull(first.page))
            assertTrue(initial.isNotEmpty());assertEquals("all",vm.discussions.state.value!!.selected)
            assertEquals("instagram-public-graphql",first.page!!.provenance!!.source.frontend)
            assertEquals(initial.size,initial.map {it.identityKey}.distinct().size)
            rows+=mapOf("step" to "all","comments" to initial.size,"source" to first.page!!.provenance!!.source)
            ui.onNodeWithTag("Public discussions").assertIsDisplayed()
            val key=vm.discussions.state.value!!.threadKey(post)
            ui.onNodeWithTag("Discussion comments").performScrollToNode(hasTestTag("More discussion $key"))
            ui.onNodeWithTag("More discussion $key").performClick()
            ui.waitUntil(45_000){vm.discussions.state.value?.thread(post)?.let {!it.loading}==true}
            val later=vm.discussions.state.value!!.thread(post)!!;assertNull(later.error)
            val comments=Discussions.comments(post,requireNotNull(later.page));assertTrue(comments.size>initial.size)
            assertTrue(comments.all {ContentIdentity.key(it.platform,it.canonicalUrl).startsWith("comment:DeTUk4sDSp-:")})
            assertTrue(comments.all {repo.publicUrl(it.canonicalUrl,it.platform).contains("/c/")})
            rows+=mapOf("step" to "native-more-button","comments" to comments.size,"new" to comments.count {p->initial.none {it.identityKey==p.identityKey}})
            ui.onNodeWithTag("Discussion tab instagram").performClick();ui.waitForIdle()
            ui.onNodeWithTag("Discussion comments").performScrollToIndex(0);ui.waitForIdle()
            val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output,"native-comments.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }finally {
            File(output,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
            ui.runOnIdle {vm.discussions.close();vm.following();repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy);scheduleRefresh(repo.context,repo.prefs)}
        }
    }
}
