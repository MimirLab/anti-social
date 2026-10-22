/* AntiSocial, GPL-3.0-or-later. Native public-pathway and historical-copy regression checks. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

/** Synthetic local material only: no external service, account, root, or browser is used. */
@RunWith(AndroidJUnit4::class)
class PublicPathwayUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val posts=mutableListOf<Post>()
    private val files=mutableListOf<File>()
    private val assets=mutableListOf<String>()
    private lateinit var preferences: String
    private lateinit var previousLookup: suspend (Post)->ArchivedVersion?
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]

    @Before fun preserve() {
        runBlocking {repo.awaitReady()};preferences=repo.prefs.export().toString();previousLookup=repo.archiveStore.lookup
        ui.runOnIdle {
            model().following();repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=true
            repo.prefs.tor=false;repo.prefs.searchProvider=SearchProvider.DARWIN;repo.prefs.originalFallback=false
            repo.applyNetworkSettings();repo.tor.enable(false)
            repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        }
    }
    @After fun restore() {
        ui.runOnIdle {ui.activity.setContent {Text("Public pathway fixture complete")};repo.archiveStore.lookup=previousLookup}
        runBlocking {
            for(post in posts) {
                val key=repo.key(post);repo.library.cancel(key);repo.dao.removeBookmark(key);repo.dao.removePosts(listOf(key))
                repo.dao.deleteDocuments(key,"saved");repo.dao.deleteDocuments(key,"history");repo.dao.removeRetainedHistory(listOf(key))
                for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
            }
            for(url in assets)repo.dao.media(Repository.hash(url))?.let {File(it.path).delete()}
            repo.dao.removeMedia(assets.map(Repository::hash))
        }
        files.forEach {it.delete()}
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs);repo.library.notifyChanged()
        }
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }
    private fun cachePost(platform: Platform=Platform.REDDIT,media: List<Media> = emptyList()): Post {
        val id="97${suffix}${posts.size}"
        val url=if(platform==Platform.REDDIT)"https://www.reddit.com/r/antisocial_fixture/comments/$id/public_copy/"else "https://www.instagram.com/p/Pathway$id/"
        return Post(id,platform,url,"Public workshop","A preserved public repair note about replacing a laptop battery.",publishedAt=1700000000000L,media=media,
            provenance=Provenance(Source(platform,"fixture-archive","https://archive-fixture.invalid",role=SourceRole.CACHE),System.currentTimeMillis(),"https://archive-fixture.invalid/record/$id",cached=true)).also {posts+=it}
    }
    private fun image(name: String,color: Int): String {
        val url="https://pathway-fixture.invalid/$suffix/$name.png";assets+=url
        val file=File(repo.context.cacheDir,"content/${Repository.hash(url)}").apply {parentFile?.mkdirs()}
        val bitmap=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color)
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();files+=file
        runBlocking {repo.dao.putMedia(MediaRow(Repository.hash(url),file.path,file.length(),System.currentTimeMillis()))};return url
    }
    private fun show(post: Post) {ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Column(Modifier.verticalScroll(rememberScrollState()).testTag("Pathway fixture content")){PostCard(post,model(),false){}}}}}}
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(250);ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(ui.activity.getExternalFilesDir("validation") ?: File(ui.activity.filesDir,"validation"),"public-pathways").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun bundledPoolUsesConcreteCapabilitiesAndKeepsArchiveDataOutOfSubscriptionRefreshes() {
        val data=repo.context.assets.open("public-pathways.json").bufferedReader().use {com.google.gson.JsonParser.parseReader(it).asJsonObject}
        val entries=PublicPathways(data).entries
        assertTrue("The APK must contain reviewed public data paths",entries.isNotEmpty())
        val widgets=entries.first {it.source.frontend=="pinterest-widgets"}.source
        val reader=repo.pool.sourceReader(widgets)
        assertTrue(Capability.POST in reader.capabilities);assertTrue(Capability.TIMELINE in reader.capabilities)
        assertFalse("Undated widget snapshots cannot confirm subscription refresh",Capability.FEED in reader.capabilities)
        assertTrue(reader.accepts(repo.routes.recognize("https://www.pinterest.com/pin/12345/")!!))
        assertFalse(reader.accepts(repo.routes.search(Platform.PINTEREST,"battery")))
        val cached=repo.pool.sourceReader(Source(Platform.REDDIT,"arctic-shift","https://arctic-shift.photon-reddit.com",role=SourceRole.CACHE))
        assertTrue(Capability.POST in cached.capabilities);assertFalse(Capability.FEED in cached.capabilities)
        assertFalse(entries.any {it.source.frontend=="meta-oembed"})
    }

    @Test fun independentArchiveCardHasNoInventedCaptureDateOrHistoryClock() {
        val post=cachePost();val calls=AtomicInteger()
        repo.archiveStore.lookup={calls.incrementAndGet();error("A cache copy is not a current live post")}
        val paired=ArchivedVersion("Internet Archive",1700000000000L,"https://web.archive.org/web/20231114221320id_/${post.canonicalUrl}",PostSnapshot(body="An older unrelated edition"),ArchiveContent.signature(post))
        val stray=post.copy(archivedVersion=paired)
        assertNull(repo.archiveVersion(stray))
        runBlocking {repo.archiveStore.check(stray);assertNull(repo.archiveStore.marry(stray).archivedVersion)}
        assertEquals(0,calls.get())
        show(stray)
        ui.onNodeWithTag("Recovered archive label").assertIsDisplayed().assertTextEquals("Archive copy")
        ui.onNodeWithTag("Recovered archive label").assert(hasContentDescription("Independent archive copy; capture date not supplied"))
        ui.onNodeWithTag("Archive version toggle").assertDoesNotExist()
        ui.onNodeWithTag("Post source ${repo.key(post)}").assertTextEquals("Reddit")
        ui.onNodeWithText("Oldest available snapshot").assertDoesNotExist()
        android.os.SystemClock.sleep(8400);ui.waitForIdle();assertEquals(0,calls.get())
        evidence("archive-copy-native-no-invented-date")
    }

    @Test fun nativeGalleryKeepsItsPlatformAndTheCacheCopyCanBePreservedLocally() {
        val first=image("first",android.graphics.Color.rgb(51,94,120));val second=image("second",android.graphics.Color.rgb(110,83,120))
        val post=cachePost(Platform.INSTAGRAM,listOf(Media(first,description="First public gallery image"),Media(second,description="Second public gallery image")))
        show(post)
        ui.waitUntil(5000){ui.onAllNodesWithContentDescription("First public gallery image").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("First public gallery image").performClick()
        ui.onNodeWithText("1 / 2").assertIsDisplayed()
        ui.onNode(hasContentDescription("First public gallery image") and hasAnyAncestor(isDialog())).performTouchInput {swipeLeft()}
        ui.waitUntil(5000){ui.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()}
        ui.onNode(hasContentDescription("Second public gallery image") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        evidence("native-public-gallery-second-image")
        ui.onNodeWithContentDescription("Close image").performClick()
        ui.onNodeWithTag("Pathway fixture content").performScrollToNode(hasTestTag("Post source ${repo.key(post)}"))
        ui.onNodeWithTag("Post source ${repo.key(post)}").assertTextEquals("Instagram")
        runBlocking {repo.library.save(post,repo.library.availableAssets(post))}
        ui.waitUntil(10000){runBlocking {repo.savedMedia(first)?.isFile==true&&repo.savedMedia(second)?.isFile==true}}
        val copy=runBlocking {repo.library.copy(repo.key(post))}!!
        assertEquals(SourceRole.CACHE,copy.post.provenance?.source?.role)
        assertEquals(post.body,copy.post.body);assertEquals(post.canonicalUrl,copy.post.canonicalUrl)
        assertEquals(setOf(first,second),copy.details!!.selectedAssets.map {it.url}.toSet())
        assertTrue(copy.details!!.selectedAssets.all {runBlocking {repo.savedMedia(it.url)?.isFile==true}})
        evidence("native-source-attribution-cache-copy-saved")
    }
}
