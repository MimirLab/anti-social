/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ContentFiltersUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var prefs: String
    private lateinit var oldMemory: FeedMemoryStore
    private lateinit var memoryScope: CoroutineScope
    private lateinit var memoryFile: File
    private val models=mutableListOf<AntiSocialViewModel>()
    private val post=Post("940001",Platform.X,"https://x.com/asfiltertest/status/940001","Example publisher","Sponsored: a new tool for your projects.",publishedAt=1_800_000_000_000L)
    private val context=ContentFilterContext(FilterSurface.FEED)
    private val rule=ContentRule("fixture-promotion","Promotion wording",phrases=listOf("sponsored"))
    @Before fun prepare() {
        runBlocking {repo.awaitReady()}
        prefs=repo.prefs.export().toString();oldMemory=repo.feedMemory
        memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        memoryFile=File(ui.activity.cacheDir,"filters-${UUID.randomUUID()}.json")
        ui.runOnIdle {
            ui.activity.setContent {Text("Preparing filter fixtures")}
            repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
            repo.prefs.writeContentRules(ContentRuleCodec.encode(emptyList()))
            Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
            repo.gateway.configure(NetworkPolicy(tor=true))
            repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope,memoryFile)
        }
        runBlocking {repo.feedMemory.awaitReady()}
    }
    @After fun restore() {
        ui.runOnIdle {ui.activity.setContent {Text("Filter fixtures complete")};models.forEach {it.discussions.close();it.viewModelScope.cancel()}}
        runBlocking {repo.feedMemory.flush();memoryScope.cancel()}
        repo.feedMemory=oldMemory
        ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(prefs));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)}
        memoryFile.delete();File(memoryFile.path+".bak").delete()
    }
    @Composable private fun Fixture(content: @Composable ()->Unit) {MaterialTheme(colorScheme=darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF8DE5BA),secondary=androidx.compose.ui.graphics.Color(0xFF8DE5BA),secondaryContainer=androidx.compose.ui.graphics.Color(0xFF253D31),onSecondaryContainer=androidx.compose.ui.graphics.Color(0xFF8DE5BA),background=androidx.compose.ui.graphics.Color(0xFF111315),surface=androidx.compose.ui.graphics.Color(0xFF111315),surfaceVariant=androidx.compose.ui.graphics.Color(0xFF1A1D20),onSurface=androidx.compose.ui.graphics.Color(0xFFECEFEA),onSurfaceVariant=androidx.compose.ui.graphics.Color(0xFF929D99))){Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){content()}}}
    private fun vm()=AntiSocialViewModel(repo).also {it.following();models+=it}
    private fun showFeed() {
        val vm=vm()
        ui.runOnIdle {ui.activity.setContent {Fixture {ChronologicalFeed(vm,listOf(post),emptyList(),emptySet(),"filter-fixture","Your feed","Newest first",onAdd={})}}}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Feed row ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun evidence(name: String) {
        ui.waitForIdle()
        val bmp=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder=File(ui.activity.getExternalFilesDir("validation"),"content-filters").apply {mkdirs()}
        File(folder,"$name.png").outputStream().use {bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
    }
    @Test fun quickEditorPreviewsBeforeSavingAndDeletionCanBeUndone() {
        ui.runOnIdle {ui.activity.setContent {Fixture {ContentFiltersScreen(repo,context,listOf(listOf(post)),listOf(post))}}}
        ui.onNodeWithTag("Filter rule name").performTextInput("Sales pitches")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter phrases"))
        ui.onNodeWithTag("Filter phrases").performTextInput("sponsored\npaid partnership")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Preview feed filter"))
        ui.onNodeWithTag("Preview feed filter").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter preview count"))
        ui.onNodeWithTag("Filter preview count").assertTextContains("1 of 1",substring=true)
        evidence("rule-preview")
        assertTrue(repo.contentFilters.rules.value.isEmpty())
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Save feed filter"))
        ui.onNodeWithTag("Save feed filter").performClick()
        ui.waitUntil {repo.contentFilters.rules.value.size==1}
        assertEquals(listOf("sponsored","paid partnership"),repo.contentFilters.rules.value.single().phrases)
        ui.onNodeWithText("Delete").performScrollTo().performClick()
        ui.onNodeWithText("Undo deletion").performScrollTo().performClick()
        assertEquals(1,repo.contentFilters.rules.value.size)
        evidence("rule-manager")
    }
    @Test fun recognizableSiteAndSearchControlsSaveAProviderIndependentRule() {
        ui.runOnIdle {repo.prefs.selected=listOf(Platform.X,Platform.REDDIT);ui.activity.setContent {Fixture {ContentFiltersScreen(repo,context,listOf(listOf(post)))}}}
        ui.onNodeWithTag("Create feed filter").performClick()
        ui.onNodeWithTag("Filter rule name").performTextInput("Quiet Reddit searches")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter site reddit"))
        fun left(site: Platform)=ui.onNodeWithTag("Filter site ${site.id}").fetchSemanticsNode().boundsInRoot.left
        assertTrue(left(Platform.X)<left(Platform.REDDIT))
        ui.onNodeWithText("Reddit").assertDoesNotExist()
        ui.runOnIdle {repo.prefs.selected=listOf(Platform.REDDIT,Platform.X)}
        ui.waitUntil {left(Platform.REDDIT)<left(Platform.X)}
        ui.runOnIdle {repo.prefs.selected=listOf(Platform.REDDIT)}
        ui.waitUntil {ui.onAllNodesWithTag("Filter site twitter").fetchSemanticsNodes().isEmpty()}
        // Every chosen tab is available without an arbitrary six-site cutoff.
        ui.runOnIdle {repo.prefs.selected=listOf(Platform.REDDIT,Platform.MASTODON,Platform.BLUESKY,Platform.TUMBLR,Platform.INSTAGRAM,Platform.TELEGRAM,Platform.PIXELFED)}
        ui.waitUntil {ui.onAllNodesWithTag("Filter site pixelfed").fetchSemanticsNodes().isNotEmpty()}
        ui.runOnIdle {repo.prefs.selected=listOf(Platform.REDDIT)}
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter surface FEED"))
        ui.onNodeWithTag("Filter surface FEED").performClick()
        ui.onNodeWithTag("Filter surface SEARCH").assertIsSelected()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter site reddit"))
        ui.onNodeWithTag("Filter site reddit").performClick().assertIsSelected()
        ui.onNodeWithTag("Filter all sites").assertIsNotSelected()
        evidence("site-and-search-picker")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("More filter sites"))
        ui.onNodeWithTag("More filter sites").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter site truthsocial"))
        ui.onNodeWithTag("Filter site truthsocial").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Preview feed filter"))
        ui.onNodeWithTag("Preview feed filter").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Save feed filter"))
        ui.onNodeWithTag("Save feed filter").performClick()
        val saved=repo.contentFilters.rules.value.single()
        assertEquals(setOf(Platform.REDDIT,Platform.TRUTH_SOCIAL),saved.platforms)
        assertEquals(setOf(FilterSurface.SEARCH),saved.surfaces)
        assertTrue(saved.domains.isEmpty());assertEquals(ContentScope.EVERYWHERE,saved.scope)
        evidence("visual-rule-manager")
        ui.onNodeWithTag("Edit filter ${saved.id}").performScrollTo().performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter site truthsocial"))
        ui.onNodeWithTag("Filter site truthsocial").assertIsSelected()
    }
    @Test fun searchShortcutAndPastedWebsiteLinksUseReaderTerms() {
        val search=ContentFilterContext(FilterSurface.SEARCH,search="https://x.com/search?q=projects")
        ui.runOnIdle {ui.activity.setContent {Fixture {ContentFiltersScreen(repo,search,listOf(listOf(post)))}}}
        ui.onNodeWithTag("Create feed filter").performClick()
        ui.onNodeWithTag("Filter rule name").performTextInput("Website links in this search")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter surface SEARCH"))
        ui.onNodeWithTag("Filter surface SEARCH").assertIsSelected()
        ui.onNodeWithTag("Filter surface FEED").assertIsNotSelected()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter scope SEARCH"))
        ui.onNodeWithTag("Filter scope SEARCH").assertIsSelected()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("More filter options"))
        ui.onNodeWithTag("More filter options").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter linked websites"))
        ui.onNodeWithTag("Filter linked websites").performTextInput("https://www.example.org/projects?tracking=123")
        evidence("website-link-option")
        // The Android IME can bring the focused multiline field back into view
        // after LazyColumn scroll returns. Wait for the real window to close.
        ui.runOnIdle {androidx.core.view.WindowCompat.getInsetsController(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}
        ui.waitUntil(5_000){androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true}
        ui.waitForIdle()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Preview feed filter"))
        ui.onNodeWithTag("Preview feed filter").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Save feed filter"))
        ui.onNodeWithTag("Save feed filter").performClick()
        assertEquals(listOf("example.org"),repo.contentFilters.rules.value.single().domains)
        assertEquals(search.search,repo.contentFilters.rules.value.single().scopeKey)
    }
    @Test fun selectingRedditRevealsSubredditRulesAndPreviewsTheirActualMembership() {
        val reddit=post.copy(platform=Platform.REDDIT,canonicalUrl="https://www.reddit.com/r/Android/comments/abc123/a_tool/",body="A useful Android tool.")
        ui.runOnIdle {repo.prefs.selected=listOf(Platform.REDDIT,Platform.INSTAGRAM);ui.activity.setContent {Fixture {ContentFiltersScreen(repo,context,listOf(listOf(post),listOf(reddit)))}}}
        ui.onNodeWithTag("Create feed filter").performClick()
        ui.onNodeWithTag("Filter rule name").performTextInput("Android communities")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter site reddit"))
        ui.onNodeWithTag("Filter site reddit").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter subreddits"))
        ui.onNodeWithTag("Filter subreddits").performTextInput("https://www.reddit.com/r/Android/")
        evidence("reddit-details")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Preview feed filter"))
        ui.onNodeWithTag("Preview feed filter").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter preview count"))
        ui.onNodeWithTag("Filter preview count").assertTextContains("1 of 2",substring=true)
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Save feed filter"))
        ui.onNodeWithTag("Save feed filter").performClick()
        val saved=repo.contentFilters.rules.value.single()
        assertEquals(listOf("android"),saved.subreddits)
        ui.onNodeWithTag("Edit filter ${saved.id}").performScrollTo().performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter site reddit"))
        ui.onNodeWithTag("Filter site reddit").performClick()
        ui.onNodeWithTag("Filter site instagram").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter subreddits"))
        ui.onNodeWithTag("Filter subreddits").assertTextContains("r/android",substring=true)
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Preview feed filter"))
        ui.onNodeWithTag("Preview feed filter").performClick()
        ui.waitUntil(5_000){ui.onAllNodes(hasText("Select Reddit or clear the subreddit names.",substring=true)).fetchSemanticsNodes().isNotEmpty()}
        File(ui.activity.getExternalFilesDir("validation"),"content-filters/site-switch-tree.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
        evidence("site-switch-validation")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasText("Select Reddit or clear the subreddit names.",substring=true))
        ui.onNodeWithText("Select Reddit or clear the subreddit names.",substring=true).assertIsDisplayed()
        assertEquals(saved,repo.contentFilters.rules.value.single())
    }
    @Test fun federatedSiteControlsMatchOriginalServersAndPhotoSitesUseCaptionLabels() {
        val mastodon=post.copy(platform=Platform.MASTODON,canonicalUrl="https://social.example/@author/940001")
        ui.runOnIdle {repo.prefs.selected=listOf(Platform.MASTODON,Platform.INSTAGRAM);ui.activity.setContent {Fixture {ContentFiltersScreen(repo,context,listOf(listOf(post),listOf(mastodon)))}}}
        ui.onNodeWithTag("Create feed filter").performClick()
        ui.onNodeWithTag("Filter rule name").performTextInput("Server rule")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter site mastodon"))
        ui.onNodeWithTag("Filter site mastodon").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter servers"))
        ui.onNodeWithTag("Filter servers").performTextInput("https://social.example/@someone")
        // Finish editing before scrolling to Preview; a focused field can
        // request that it be brought back onscreen during IME relayout.
        ui.runOnIdle {ui.activity.currentFocus?.clearFocus()}
        evidence("federated-details")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Preview feed filter"))
        ui.onNodeWithTag("Preview feed filter").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter preview count"))
        ui.onNodeWithTag("Filter preview count").assertTextContains("1 of 2",substring=true)
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Save feed filter"))
        ui.onNodeWithTag("Save feed filter").performClick()
        val saved=repo.contentFilters.rules.value.single()
        assertEquals(listOf("social.example"),saved.servers)
        ui.onNodeWithTag("Edit filter ${saved.id}").performScrollTo().performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter site mastodon"))
        ui.onNodeWithTag("Filter site mastodon").performClick()
        ui.onNodeWithTag("Filter site instagram").performClick()
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter phrases"))
        ui.onNodeWithTag("Filter phrases").assertTextContains("Caption words",substring=true)
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasText("Photos"))
        ui.onNodeWithText("Photos").assertIsDisplayed();evidence("photo-site-details")
        ui.onNodeWithTag("Feed filter editor").performScrollToNode(hasTestTag("Filter servers"))
        ui.onNodeWithTag("Filter servers").assertTextContains("social.example",substring=true)
    }
    @Test fun collapsedCardsRemainUnreadUntilTheReaderRevealsThem() {
        ui.runOnIdle {repo.contentFilters.save(rule)};showFeed()
        ui.waitUntil(8_000){ui.onAllNodesWithTag("Filtered post ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Filtered post ${repo.key(post)}").assertIsDisplayed()
        android.os.SystemClock.sleep(1000)
        assertFalse(repo.key(post) in repo.feedMemory.get("filter-fixture").readKeys)
        evidence("collapsed-feed")
        ui.onNodeWithTag("Reveal filtered ${repo.key(post)}").performClick()
        ui.waitUntil(8_000){repo.key(post) in repo.feedMemory.get("filter-fixture").readKeys}
    }
    @Test fun changedRulesWaitForApplyAndHiddenItemsCanBeRecovered() {
        showFeed()
        val before=ui.onNodeWithTag("Feed row ${repo.key(post)}").fetchSemanticsNode().boundsInRoot.top
        ui.runOnIdle {repo.contentFilters.save(rule.copy(action=ContentAction.HIDE))}
        ui.onNodeWithTag("Manage feed filters").performClick()
        ui.onNodeWithTag("Apply feed filters").assertIsDisplayed()
        assertEquals(before,ui.onNodeWithTag("Feed row ${repo.key(post)}").fetchSemanticsNode().boundsInRoot.top)
        ui.onNodeWithTag("Apply feed filters").performClick()
        ui.waitUntil(8_000){ui.onAllNodesWithTag("Feed row ${repo.key(post)}").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("Feed row ${repo.key(post)}").assertDoesNotExist()
        ui.onNodeWithTag("Manage feed filters").performClick()
        ui.onNodeWithTag("Review filtered posts").performClick()
        ui.onNodeWithTag("Filtered items").assertIsDisplayed();evidence("filtered-review")
        ui.onNodeWithTag("Review reveal ${repo.key(post)}").performScrollTo().performClick()
        ui.onNodeWithTag("Feed row ${repo.key(post)}").assertIsDisplayed()
    }
    @Test fun directPostCardsDontApplyFeedRulesOrOfferMisleadingFilterActions() {
        ui.runOnIdle {repo.contentFilters.save(rule.copy(action=ContentAction.HIDE))}
        val vm=vm()
        ui.runOnIdle {ui.activity.setContent {Fixture {PostCard(post,vm,false){}}}}
        ui.onNodeWithTag("Filtered post ${repo.key(post)}").assertDoesNotExist()
        ui.onNodeWithTag("Filter like ${repo.key(post)}").assertDoesNotExist()
    }
    @Test fun nativeBackupValidatesCompleteRulesBeforeMutation() {
        ui.runOnIdle {repo.contentFilters.save(rule)}
        val backup=repo.prefs.export()
        assertEquals(listOf(rule),ContentRuleCodec.decode(backup.getString("contentRules")))
        val broken=JSONObject(backup.toString()).put("contentRules",backup.getString("contentRules").replace("COLLAPSE","EXECUTE"))
        assertThrows(Exception::class.java){repo.prefs.restore(broken)}
        assertEquals(listOf(rule),repo.contentFilters.rules.value)
    }
    @Test fun freshRevisionsSurviveReloadAndOlderCacheCannotUndoThem() = runBlocking {
        val key=repo.key(post)
        fun fetched(p: Post,at: Long)=p.copy(provenance=Provenance(Source(Platform.X,"fixture","https://fixture.invalid"),at,p.canonicalUrl))
        val first=fetched(post,10)
        repo.feedMemory.reconcile("revision",listOf(key));repo.feedMemory.observeContent(listOf(first));repo.feedMemory.markRead("revision",listOf(key))
        repo.feedMemory.observeContent(listOf(first.copy(score=500)))
        assertTrue(key in repo.feedMemory.get("revision").readKeys)
        val correction=fetched(post.copy(body=post.body+" Correction: this tool was recalled."),20)
        repo.feedMemory.observeContent(listOf(correction))
        assertFalse(key in repo.feedMemory.get("revision").readKeys)
        repo.feedMemory.markRead("revision",listOf(key));repo.feedMemory.flush()
        memoryScope.cancel();memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope,memoryFile);repo.feedMemory.awaitReady()
        repo.feedMemory.observeContent(listOf(first))
        assertTrue(key in repo.feedMemory.get("revision").readKeys)
        repo.feedMemory.observeContent(listOf(fetched(correction.copy(body="A further correction."),30)))
        assertFalse(key in repo.feedMemory.get("revision").readKeys)
    }
    @Test fun unreadableRulesPauseVisiblyRatherThanSilentlyHidingPosts() {
        ui.runOnIdle {repo.prefs.writeContentRules("{broken}")};showFeed()
        ui.onNodeWithContentDescription("Filters need attention").assertIsDisplayed()
        ui.onNodeWithTag("Manage feed filters").performClick()
        ui.onNodeWithText("Repair filters").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithTag("Feed row ${repo.key(post)}").assertIsDisplayed()
    }
    @Test fun entirelyFilteredSearchKeepsDiscoveryAndRequiresAnExplicitNextBatch() {
        val calls=AtomicInteger()
        val target=Target(Platform.X,TargetKind.SEARCH,"filter fixture","https://x.com/search?q=antisocial_filter_fixture")
        val account=Profile(Target(Platform.X,TargetKind.PROFILE,"asfiltertest","https://x.com/asfiltertest"),"Example publisher")
        val second=post.copy(id="940002",canonicalUrl="https://x.com/asfiltertest/status/940002")
        val third=post.copy(id="940003",canonicalUrl="https://x.com/asfiltertest/status/940003",body="A practical release fixes battery use.")
        ui.runOnIdle {repo.contentFilters.save(rule.copy(action=ContentAction.HIDE))}
        val vm=AntiSocialViewModel(repo,createSearch={query->SearchFeed(query,discover={previous->calls.incrementAndGet();if(previous==null)Page(listOf(post),next="second",accounts=listOf(account))else if(previous.next=="second")Page(listOf(second),next="third")else Page(listOf(third))},read={Page(emptyList())},blockSize=1)}).also {models+=it}
        try {
            ui.runOnIdle {ui.activity.setContent {Fixture {SearchFeedScreen(vm,emptySet())}};vm.open(target,true)}
            ui.waitUntil(10_000){ui.onAllNodesWithTag("Continue filtered search").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("Search feed").performScrollToNode(hasTestTag("Search people"))
            ui.onNodeWithTag("Search people").assertIsDisplayed()
            android.os.SystemClock.sleep(700);assertEquals(2,calls.get())
            evidence("filtered-search")
            ui.onNodeWithTag("Continue filtered search").performScrollTo().performClick()
            ui.waitUntil(10_000){vm.screen.value.page?.posts?.size==3&&vm.screen.value.loading==false}
            assertEquals(3,calls.get())
        }finally {runBlocking {
            repo.dao.removePosts(listOf(repo.key(post),repo.key(second),repo.key(third)))
            val pageKey=repo.pageKey(target);repo.dao.media(pageKey)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(pageKey))
        }}
    }
    @Test fun readerStateExportsAndMergesExplicitRulesWithoutNeedingAnAiModel() = runBlocking {
        repo.contentFilters.save(rule)
        val bytes=repo.portability.exportState(false)
        val document=PortableReader.parse(bytes)
        val encoded=document.statePayload!!["filters"].asJsonObject["contentRules"].asString
        assertEquals(listOf(rule),ContentRuleCodec.decode(encoded))
        repo.contentFilters.reset()
        val preview=repo.portability.preview(bytes)
        assertTrue(preview.hasFilters)
        repo.portability.apply(preview,emptySet(),PortableImportOptions(channels=false,filters=true,collections=false,settings=false,progress=false))
        assertEquals(listOf(rule),repo.contentFilters.rules.value)
    }
}
