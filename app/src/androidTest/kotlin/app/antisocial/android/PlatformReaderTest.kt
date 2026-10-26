package app.antisocial.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlatformReaderTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private lateinit var selectedBefore: List<Platform>
    private lateinit var activeBefore: Platform
    private lateinit var urlsBefore: Map<Platform,String>
    @org.junit.Before fun preserveNavigation(){selectedBefore=repo.prefs.selected;activeBefore=repo.prefs.activePlatform;urlsBefore=Platform.entries.associateWith {repo.prefs.lastUrl(it)}}
    @org.junit.After fun restoreNavigation(){repo.prefs.selected=selectedBefore;repo.prefs.activePlatform=activeBefore;urlsBefore.forEach {(platform,url)->repo.prefs.lastUrl(platform,url)}}
    private fun evidence(name: String) {
        ui.waitForIdle();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"native-platforms").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun allPlatformTabsOfferWordSearchWithTheNativeFeed() {
        runBlocking {repo.awaitReady()};val vm=model();val before=repo.gateway.currentPolicy()
        ui.runOnUiThread {repo.tor.enable(false)};repo.gateway.configure(NetworkPolicy(tor=true))
        try {for(platform in ReaderScope.platforms.filterNot {it==Platform.RSS}) {
            ui.runOnIdle {vm.feedPlatform(platform)}
            ui.onNodeWithContentDescription("Search or open a link").performClick()
            ui.onNodeWithTag("Content query").performTextInput("public fixture")
            ui.onNodeWithTag("Content query").performImeAction()
            ui.waitUntil(10_000){vm.screen.value.target?.kind==TargetKind.SEARCH&&!vm.screen.value.loading}
            assertEquals(platform,vm.screen.value.target!!.platform);assertEquals("public fixture",vm.screen.value.target!!.identity)
            ui.onNodeWithTag("Search feed").assertIsDisplayed()
        }}finally {repo.gateway.configure(before);ui.runOnUiThread {vm.following();repo.applyNetworkSettings()}}
    }
    @Test fun aSelectedUndatedPreviewAppearsBeforeDatedRelatedPins() {
        runBlocking {repo.awaitReady()};val vm=model();val target=repo.routes.recognize("https://www.pinterest.com/pin/990050001")!!
        val selected=Post("selected",Platform.PINTEREST,target.canonicalUrl,"Author","Selected public preview",preview=true)
        val related=selected.copy(id="related",canonicalUrl="https://www.pinterest.com/pin/990050002",body="Related pin",publishedAt=1,preview=false)
        try {
            ui.runOnIdle {vm.platform(Platform.PINTEREST,false);vm.following();vm.screen.value=ScreenState(Platform.PINTEREST,target=target,page=Page(listOf(related,selected)))}
            ui.onNodeWithText("Selected public preview").assertIsDisplayed();ui.onNodeWithText("Public preview · full content unavailable").assertIsDisplayed()
        }finally {
            ui.runOnIdle {vm.following()}
            runBlocking(kotlinx.coroutines.Dispatchers.IO) {listOf(selected,related).forEach {post->repo.db.openHelper.writableDatabase.execSQL("DELETE FROM history WHERE `key`=?",arrayOf(repo.key(post)))}}
        }
    }
    @Test fun failedImagesCanBeRetriedWithoutReopeningThePost() {
        runBlocking {repo.awaitReady()};val vm=model();val before=repo.gateway.currentPolicy()
        val url="https://example.org/retry-image-fixture.png";val key=Repository.hash(url)
        val target=repo.routes.recognize("https://x.com/fixture/status/990050001")!!
        val post=Post("retry-image-fixture",Platform.X,target.canonicalUrl,"Fixture","Image retry fixture",media=listOf(Media(url,description="Recovered image")))
        val file=File(ui.activity.cacheDir,"retry-image-fixture.png")
        try {
            ui.runOnUiThread {repo.tor.enable(false)}
            repo.gateway.configure(NetworkPolicy(tor=true))
            ui.runOnIdle {vm.platform(Platform.X,false);vm.following();vm.screen.value=ScreenState(Platform.X,target=target,page=Page(listOf(post)))}
            ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Retry image").fetchSemanticsNodes().isNotEmpty()}
            val bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}
            file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            runBlocking {repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis()))}
            ui.onNodeWithContentDescription("Retry image").performClick()
            ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Recovered image").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithContentDescription("Recovered image").assertIsDisplayed()
        }finally {
            repo.gateway.configure(before);ui.runOnIdle {vm.following();repo.applyNetworkSettings()}
            runBlocking(kotlinx.coroutines.Dispatchers.IO) {repo.dao.removeMedia(listOf(key));repo.db.openHelper.writableDatabase.execSQL("DELETE FROM history WHERE `key`=?",arrayOf(repo.key(post)))}
            file.delete()
        }
    }
    /** Records each upstream outcome; fixtures above assert deterministic UI behavior. */
    @Test fun liveSearchAndPostScreensAcrossPlatforms() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLivePlatforms")=="true")
        runBlocking {repo.awaitReady()};val vm=model();val records=mutableListOf<Map<String,Any?>>()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"native-platforms").apply {mkdirs()}
        fun record(platform: Platform,operation: String,start: Long) {
            val state=vm.screen.value;val page=state.page
            records+=mapOf("platform" to platform.id,"operation" to operation,"elapsedMs" to System.currentTimeMillis()-start,"error" to state.error,"posts" to page?.posts?.size,"accounts" to page?.accounts?.size,"dated" to page?.posts?.count {it.publishedAt!=null},"previews" to page?.posts?.count {it.preview},"cached" to page?.provenance?.cached,"source" to page?.provenance?.source?.root,"notice" to page?.notice)
            File(dir,"measurements.json").writeText(GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(records))
            evidence("${platform.id}-$operation")
        }
        try {for(platform in Platform.entries.filter {it!=Platform.X&&it!=Platform.RSS}) {
            val query=when(platform){Platform.REDDIT->"android";Platform.TIKTOK->"nasa";Platform.TIEBA->"安卓";else->"space"}
            var start=System.currentTimeMillis()
            ui.runOnIdle {vm.platform(platform,false);vm.following();repo.applyNetworkSettings();vm.open(repo.routes.search(platform,query),true)}
            ui.waitUntil(150_000){!vm.screen.value.loading}
            ui.onNodeWithTag("Search feed").assertIsDisplayed();record(platform,"search",start)
            val post=vm.screen.value.page?.posts?.firstOrNull()
            val account=vm.screen.value.page?.accounts?.firstOrNull()
            val target=post?.let {repo.routes.recognize(it.canonicalUrl)} ?: account?.target
            if(target!=null) {
                start=System.currentTimeMillis();ui.runOnIdle {vm.open(target,true)}
                ui.waitUntil(150_000){!vm.screen.value.loading};record(platform,if(post!=null)"post"else "profile",start)
            }
            if(platform in setOf(Platform.TIKTOK,Platform.THREADS)) {
                start=System.currentTimeMillis();ui.runOnIdle {vm.open(repo.routes.target(platform,"nasa"),true)}
                ui.waitUntil(150_000){!vm.screen.value.loading};record(platform,"public-profile",start)
            }
        }}finally {ui.runOnIdle {vm.following()}}
    }
}
