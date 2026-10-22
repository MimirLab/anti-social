/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Local images and injected public-page results exercise real native screens.
 * No fixture calls a public search engine, frontend or social-media host.
 */
@RunWith(AndroidJUnit4::class)
class ImageSearchUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val models=mutableListOf<AntiSocialViewModel>()
    private val mediaKeys=mutableSetOf<String>()
    private val posts=mutableListOf<Post>()
    private val targets=mutableListOf<Target>()
    private val prefix=System.nanoTime().toString().takeLast(11)
    private lateinit var preferences: String
    private lateinit var oldDirectLoader: suspend (Target,(String)->Unit)->Page

    @Before fun isolate()=runBlocking {
        repo.awaitReady()
        preferences=repo.prefs.export().toString();oldDirectLoader=repo.directLoader
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].following()
            repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
            repo.prefs.readingView="following";repo.prefs.theme="dark"
            repo.prefs.selected=listOf(Platform.X,Platform.TRUTH_SOCIAL)
            Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
            ui.activity.setContent {Text("Image search fixture")}
        }
    }

    @After fun restore()=runBlocking {
        ui.runOnIdle {
            ui.activity.setContent {Text("Image search fixture complete")}
            models.forEach {it.imageSearch.close();it.viewModelScope.cancel()}
            repo.directLoader=oldDirectLoader
        }
        for(post in posts) {
            val key=repo.key(post)
            repo.dao.removePosts(listOf(key))
            for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
            repo.dao.deleteDocuments(key,"history")
            repo.dao.removeRetainedHistory(listOf(key))
        }
        mediaKeys+=targets.map(repo::pageKey)
        for(key in mediaKeys) {
            repo.dao.media(key)?.let {File(it.path).delete()}
            repo.dao.removeMedia(listOf(key))
        }
        ui.runOnIdle {
            repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(repo.context,repo.prefs)
        }
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }

    private fun target(url: String)=requireNotNull(repo.routes.recognize(url)).also {targets+=it}
    private fun post(platform: Platform,number: Int,body: String): Post {
        val id="95$prefix$number"
        val url=if(platform==Platform.X)"https://x.com/antisocial_image_fixture/status/$id"else "https://truthsocial.com/@antisocial_image_fixture/$id"
        val destination=target(url)
        assertEquals(TargetKind.POST,destination.kind)
        return Post(id,platform,destination.canonicalUrl,"${platform.label} fixture author",body,publishedAt=1_780_000_000_000L-number*60_000L).also {posts+=it}
    }
    private fun image(number: Int): Media=runBlocking {
        val url="https://image-search-$prefix.invalid/selected-$number.png";val key=Repository.hash(url)
        val bitmap=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888).apply {eraseColor(if(number==1)Color.CYAN else Color.MAGENTA)}
        val bytes=try {ByteArrayOutputStream().use {out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray()}}finally {bitmap.recycle()}
        val file=File(repo.context.cacheDir,"content/$key").apply {parentFile?.mkdirs();writeBytes(bytes)}
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis()));mediaKeys+=key
        Media(url,description=if(number==1)"First selected fixture"else "Second selected fixture")
    }
    private fun model(
        session: ()->ImageSearchSession,
        selected: (Media,String)->Unit={_,_->},
        releases: AtomicInteger=AtomicInteger()
    ): AntiSocialViewModel {
        lateinit var result: AntiSocialViewModel
        ui.runOnUiThread {
            result=AntiSocialViewModel(repo,imageSearchFactory={scope->ImageSearchController(repo,scope,
                prepare={image,source->selected(image,source);ImageSearchInput(byteArrayOf(1,2,3))},
                create={session()},connect={},release={releases.incrementAndGet()})}).also {
                    it.following();models+=it
                }
        }
        return result
    }
    private fun show(vm: AntiSocialViewModel,image: Media=image(1)) {
        ui.runOnIdle {ui.activity.setContent {AntiSocialApp(vm)};vm.searchImage(image,"https://x.com/antisocial_image_fixture/status/1")}
        ui.onNodeWithTag("Image search feed").assertExists()
    }
    private fun evidence(name: String) {
        ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"image-search").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun selectedViewerPageStartsAProgressiveNativeFeedAndDeduplicatesEngineHits() {
        val selected=mutableListOf<Media>();val one=image(1);val two=image(2)
        val x=post(Platform.X,1,"Native X match from the first public source.")
        // The numeric ID is deliberately equal: post identity remains platform-scoped.
        val truth=post(Platform.TRUTH_SOCIAL,1,"A distinct Truth Social post using the same numeric ID.")
        val account=target("https://x.com/img_$prefix")
        assertTrue(PublicSearchAdapter.profileAllowed(account))
        val slow=CompletableDeferred<Unit>();val calls=AtomicInteger();val reads=AtomicInteger()
        val vm=model(session={ImageSearchSession(listOf(ImageSearchEngine.BING,ImageSearchEngine.YANDEX),
            discover={engine,_->calls.incrementAndGet();if(engine==ImageSearchEngine.BING)ImageSearchLinks(listOf(x.canonicalUrl,x.canonicalUrl,account.canonicalUrl))else {slow.await();ImageSearchLinks(listOf(x.canonicalUrl,truth.canonicalUrl,account.canonicalUrl))}},
            recognize={repo.routes.recognize(it)},read={destination->reads.incrementAndGet();when(destination.canonicalUrl) {
                x.canonicalUrl->Page(listOf(x))
                truth.canonicalUrl->Page(listOf(truth))
                else->Page(emptyList(),profile=Profile(account,"Public image fixture account","Actual public profile biography."))
            }})},selected={media,_->selected+=media})
        val viewer=mutableStateOf(true)
        ui.runOnIdle {ui.activity.setContent {
            AntiSocialApp(vm)
            if(viewer.value)CompositionLocalProvider(LocalImageSearch provides vm::searchImage) {
                MediaViewer(listOf(one,two),0,"https://x.com/antisocial_image_fixture/status/1",repo){viewer.value=false}
            }
        }}
        ui.onNodeWithText("1 / 2").assertIsDisplayed()
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription(one.description).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription(one.description).performTouchInput {swipeLeft()}
        ui.onNodeWithText("2 / 2").assertIsDisplayed()
        ui.onNodeWithTag("Search selected image").performClick()
        ui.waitUntil(10_000){vm.imageSearch.state.value.feed.page.posts.size==1&&vm.imageSearch.state.value.feed.page.accounts.size==1}
        assertEquals(listOf(two),selected);assertTrue(vm.imageSearch.state.value.loading)
        assertFalse(viewer.value)
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasText(x.body));ui.onNodeWithText(x.body).assertIsDisplayed()
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasText("Public image fixture account"));ui.onNodeWithTag("Image account ${account.platform.id}:${account.identity}").assertIsDisplayed()
        evidence("progressive-native-account")
        slow.complete(Unit)
        ui.waitUntil(10_000){!vm.imageSearch.state.value.loading&&vm.imageSearch.state.value.feed.page.posts.size==2}
        val state=vm.imageSearch.state.value.feed
        assertEquals(2,calls.get());assertEquals(3,reads.get());assertEquals(1,state.page.accounts.size)
        assertEquals(setOf(repo.key(x),repo.key(truth)),state.page.posts.map(repo::key).toSet())
        assertEquals(x.id,truth.id)
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasTestTag("Image result ${repo.key(x)}"));ui.onNodeWithTag("Image result ${repo.key(x)}").assertIsDisplayed()
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasTestTag("Image result ${repo.key(truth)}"));ui.onNodeWithTag("Image result ${repo.key(truth)}").assertIsDisplayed()
        evidence("cross-platform-native-cards")
    }

    @Test fun failedSourcesAreVisibleAndRetryNeverTurnsAnUnreadableSnippetIntoAPost() {
        val readable=post(Platform.X,2,"Readable post after explicitly retrying a failed image source.")
        val unavailable=post(Platform.X,3,"Engine preview text must never become a native post.")
        val attempts=AtomicInteger()
        val vm=model(session={ImageSearchSession(listOf(ImageSearchEngine.BING,ImageSearchEngine.YANDEX),
            discover={engine,_->if(engine==ImageSearchEngine.BING) {
                if(attempts.incrementAndGet()==1)error("Synthetic public-source challenge")
                ImageSearchLinks(listOf(readable.canonicalUrl))
            }else ImageSearchLinks(listOf(unavailable.canonicalUrl))},recognize={repo.routes.recognize(it)},
            read={target->if(target.canonicalUrl==readable.canonicalUrl)Page(listOf(readable))else Page(listOf(unavailable.copy(preview=true)))})})
        show(vm)
        ui.waitUntil(10_000){!vm.imageSearch.state.value.loading&&vm.imageSearch.state.value.feed.unavailable==1}
        assertTrue(vm.imageSearch.state.value.feed.page.posts.isEmpty())
        ui.onNodeWithText(unavailable.body).assertDoesNotExist()
        ui.onNodeWithTag("Image search sources").performClick()
        ui.onNodeWithTag("Image engine bing").assertExists()
        ui.onNodeWithText("Synthetic public-source challenge").assertIsDisplayed()
        evidence("source-failure-without-fabricated-card")
        ui.onNodeWithContentDescription("Retry Bing").performClick()
        ui.waitUntil(10_000){!vm.imageSearch.state.value.loading&&vm.imageSearch.state.value.feed.page.posts.size==1}
        assertEquals(2,attempts.get())
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasText(readable.body));ui.onNodeWithText(readable.body).assertIsDisplayed()
        ui.onNodeWithText(unavailable.body).assertDoesNotExist()
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasTestTag("Unreadable image matches"))
        ui.onNodeWithTag("Unreadable image matches").performClick()
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasText("This public match did not supply its exact readable post."))
        ui.onNodeWithText("This public match did not supply its exact readable post.").assertIsDisplayed()
        evidence("retried-source-and-unreadable-match")
    }

    @Test fun stoppingIgnoresALateSourceAndCanResumeTheSameSearch() {
        val late=post(Platform.X,4,"Late canceled result must not appear.")
        val resumed=post(Platform.X,5,"Resumed image search returns a real native post.")
        val release=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>();val attempts=AtomicInteger()
        val vm=model(session={ImageSearchSession(listOf(ImageSearchEngine.BING),discover={_,_->
            if(attempts.incrementAndGet()==1) {started.complete(Unit);withContext(NonCancellable){release.await()};ImageSearchLinks(listOf(late.canonicalUrl))}
            else ImageSearchLinks(listOf(resumed.canonicalUrl))
        },recognize={repo.routes.recognize(it)},read={Page(listOf(if(it.canonicalUrl==late.canonicalUrl)late else resumed))})})
        show(vm);runBlocking {withTimeout(10_000){started.await()}}
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasText("Stop"));ui.onNodeWithText("Stop").performClick()
        ui.waitUntil(5_000){vm.imageSearch.state.value.stopped&&!vm.imageSearch.state.value.loading}
        release.complete(Unit)
        ui.onNodeWithText("Search paused").assertIsDisplayed()
        assertTrue(vm.imageSearch.state.value.feed.page.posts.isEmpty())
        evidence("paused-search")
        ui.onNodeWithTag("More image matches").performClick()
        ui.waitUntil(10_000){!vm.imageSearch.state.value.loading&&vm.imageSearch.state.value.feed.page.posts.size==1}
        assertEquals(2,attempts.get())
        assertEquals(listOf(resumed),vm.imageSearch.state.value.feed.page.posts)
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasText(resumed.body));ui.onNodeWithText(resumed.body).assertIsDisplayed()
        ui.onNodeWithText(late.body).assertDoesNotExist()
        evidence("resumed-native-search")
    }

    @Test fun openingAResultAndReturningPreservesTheSearchWithoutRediscovery() {
        val result=post(Platform.X,6,"Open this native image-search result and return to the same feed.")
        val destination=requireNotNull(repo.routes.recognize(result.canonicalUrl))
        val discoveries=AtomicInteger();val prepares=AtomicInteger();val releases=AtomicInteger()
        repo.directLoader={target,_->check(target==destination);Page(listOf(result))}
        val vm=model(session={ImageSearchSession(listOf(ImageSearchEngine.BING),discover={_,_->discoveries.incrementAndGet();ImageSearchLinks(listOf(result.canonicalUrl))},recognize={repo.routes.recognize(it)},read={Page(listOf(result))})},selected={_,_->prepares.incrementAndGet()},releases=releases)
        show(vm)
        ui.waitUntil(10_000){!vm.imageSearch.state.value.loading&&vm.imageSearch.state.value.feed.page.posts.size==1}
        val before=vm.imageSearch.state.value.feed
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasText(result.body));ui.onNodeWithText(result.body).performClick()
        ui.waitUntil(10_000){vm.screen.value.target==destination&&!vm.screen.value.loading}
        ui.onNodeWithText(result.body).assertIsDisplayed()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.waitUntil(5_000){vm.screen.value.section=="image-search"}
        assertEquals(before,vm.imageSearch.state.value.feed)
        assertEquals(1,discoveries.get());assertEquals(1,prepares.get());assertEquals(0,releases.get())
        ui.onNodeWithTag("Image search feed").performScrollToNode(hasText(result.body));ui.onNodeWithText(result.body).assertIsDisplayed()
        evidence("returned-to-existing-search")
        ui.onNodeWithContentDescription("Back").performClick()
        ui.waitUntil(5_000){vm.screen.value.section=="feed"&&vm.imageSearch.state.value.image==null}
        assertEquals(1,releases.get());assertEquals(1,discoveries.get())
    }
}
