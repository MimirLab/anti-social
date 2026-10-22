/* AntiSocial, GPL-3.0-or-later. Native local fixtures; no public reads or writes. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
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
import java.util.concurrent.atomic.AtomicInteger

/** Available replies must be visible before opening diagnostics or scrolling past failures. */
@RunWith(AndroidJUnit4::class)
class DiscussionAllStatusTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var outgoingValidator: suspend (ShareRouteCandidate)->ShareRouteCheck
    private var vm: AntiSocialViewModel?=null
    private val first=Post("all-status-root",Platform.X,"https://x.com/antisocial_fixture/status/123","fixture","A public cross-post with independent discussion sources.")
    private val other=Post("all-status-other",Platform.INSTAGRAM,"https://www.instagram.com/p/ABC","fixture",first.body)
    private val platformError="This platform does not supply public comments through the available sources."
    private val sharedError="The shared discussion service is unavailable in this fixture. Its failure must not fill the reading area or block the original platform replies."
    private val replyText="An original platform reply is visible immediately."
    private class Service(var failure: String?=null,val gate: CompletableDeferred<Unit>?=null): AnywhereAccess {
        val reads=AtomicInteger();val writes=AtomicInteger()
        override suspend fun read(url: String,order: CommentOrder,force: Boolean,limit: Int,parentId: String?): AnywherePage {
            reads.incrementAndGet();gate?.await();failure?.let {error(it)}
            return AnywherePage(url,emptyList(),order,System.currentTimeMillis(),limit)
        }
        override suspend fun mutate(url: String,order: CommentOrder,action: AnywhereAction,text: String,id: String?,value: Int?) {writes.incrementAndGet();error("This read-only fixture never publishes")}
        override suspend fun owner(): String?=null
        override suspend fun forget(){}
        override fun clear(){}
    }
    @Before fun preserve() {
        runBlocking {repo.awaitReady()};preferences=repo.prefs.export().toString()
        outgoingValidator=repo.outgoing.validate
        repo.outgoing.validate={ShareRouteCheck(ShareRouteStatus.READABLE,it.url)}
        repo.prefs.intervalMinutes=0;repo.prefs.communityComments=true
        repo.gateway.configure(NetworkPolicy(tor=true)) // Unexpected requests fail closed.
    }
    @After fun restore() {
        repo.outgoing.validate=outgoingValidator
        ui.runOnIdle {
            vm?.discussions?.close();vm?.viewModelScope?.cancel()
            ui.activity.setContent {Text("Discussion fixture complete")}
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings()
        }
    }
    private fun reply(post: Post,text: String=replyText)=post.copy(id=post.id+"-reply",canonicalUrl=post.canonicalUrl+"9",body=text,title="",publishedAt=System.currentTimeMillis(),parentId=post.id)
    private fun show(service: Service,roots: List<Post> = listOf(first,other),read: suspend (Post,Page?,ReadingOrder)->Page): AntiSocialViewModel {
        val model=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope,service,read)})
        vm=model
        ui.runOnUiThread {
            ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){DiscussionSheet(model)}}}
            model.discussions.open(roots)
        }
        return model
    }
    /** Use the actual feed-card entry point instead of opening the reader in test code. */
    private fun showPostCard(service: Service): AntiSocialViewModel {
        val model=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope,service) {post,_,_->Page(listOf(post,reply(post)))}})
        vm=model
        ui.runOnUiThread {
            ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){
                // Keep these temporary cards out of retained reading history,
                // as the real feed pauses its dwell work while a sheet is open.
                CompositionLocalProvider(LocalArchiveChecksPaused provides true) {
                    Column {PostCard(first,model,false,onOpen={});DiscussionSheet(model)}
                }
            }}}
        }
        ui.onNodeWithTag("Read discussions ${repo.key(first)}").assertIsDisplayed().performClick()
        return model
    }
    private fun assertQuietReadingArea() {
        ui.onNodeWithTag("Discussion tab all").assertIsSelected()
        ui.onNodeWithText(replyText).assertIsDisplayed() // No seek or scroll: replies have priority.
        ui.onNodeWithTag("Discussion source availability").assertDoesNotExist()
        ui.onNodeWithText(sharedError).assertDoesNotExist()
        ui.onNodeWithText("Shared discussion unavailable").assertDoesNotExist()
        ui.onNodeWithText("Shared discussion loading").assertDoesNotExist()
        ui.onNodeWithText("Checking shared comments…").assertDoesNotExist()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithTag("Shared discussion composer").assertIsDisplayed()
        ui.onNodeWithTag("Discussion source details").assertIsDisplayed()
    }
    private fun settled(model: AntiSocialViewModel,shared: Boolean=true) {
        ui.waitUntil(10_000) {model.discussions.state.value?.let {s->
            s.threads.values.all {!it.loading&&(it.page!=null||it.error!=null)}&&
                (!shared||s.communities.size==s.posts.size&&s.communities.values.all {!it.loading&&(it.page!=null||it.error!=null)})
        }==true}
        ui.waitForIdle()
    }
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(200)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"comments-all").apply {mkdirs()}
        File(directory,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun nativeReplyIsVisibleWithoutScrollingPastSharedFailureAndUnsupportedCounterpart() {
        val service=Service(sharedError)
        val model=show(service) {post,_,_->if(post.platform==Platform.INSTAGRAM)error(platformError)else Page(listOf(post,reply(post)))}
        settled(model)
        assertQuietReadingArea()
        ui.onNodeWithText(platformError).assertDoesNotExist()
        evidence("native-reply-visible-before-source-errors")
        ui.onNodeWithTag("Discussion source details").performClick()
        ui.onNodeWithTag("Discussion comments").performScrollToNode(hasText(platformError))
        ui.onNodeWithText(platformError).assertIsDisplayed()
        for(root in listOf(first,other)) {
            val status="Discussion shared status ${root.platform.id}"
            ui.onNodeWithTag("Discussion comments").performScrollToNode(hasTestTag(status))
            ui.onNode(hasText(sharedError) and hasAnyAncestor(hasTestTag(status))).assertIsDisplayed()
        }
        assertEquals(0,service.writes.get())
    }

    @Test fun slowSharedRoomCannotReplaceTheAvailableNativeConversationWithALoadingPanel() {
        val gate=CompletableDeferred<Unit>();val service=Service(gate=gate)
        val model=show(service,roots=listOf(first)) {post,_,_->Page(listOf(post,reply(post)))}
        settled(model,shared=false)
        ui.waitUntil(5_000){model.discussions.state.value?.communities?.get(first.platform.id)?.loading==true}
        assertQuietReadingArea()
        ui.onNodeWithTag("Discussion empty state").assertDoesNotExist()
        evidence("native-reply-while-shared-room-is-reading")
        ui.onNodeWithTag("Discussion source details").performClick()
        ui.onNodeWithTag("Discussion comments").performScrollToNode(hasTestTag("Discussion shared status ${first.platform.id}"))
        ui.onNodeWithTag("Discussion shared status ${first.platform.id}").assertIsDisplayed()
        assertTrue(model.discussions.state.value!!.communities.getValue(first.platform.id).loading)
        ui.onNodeWithTag("Discussion comments").performScrollToNode(hasTestTag("Discussion source details"))
        ui.onNodeWithTag("Discussion source details").performClick()
        gate.complete(Unit);settled(model)
        ui.onNodeWithText(replyText).assertIsDisplayed();assertEquals(0,service.writes.get())
    }

    @Test fun feedPostCommentButtonShowsOriginalReplyBeforeSharedServiceFailure() {
        val service=Service(sharedError);val model=showPostCard(service);settled(model)
        assertQuietReadingArea()
        ui.onNodeWithText("Join shared discussion").assertIsDisplayed()
        evidence("feed-card-comments-replies-first-after-shared-failure")
        ui.onNodeWithTag("Discussion source details").performClick()
        ui.onNodeWithTag("Discussion comments").performScrollToNode(hasText(sharedError))
        ui.onNodeWithText(sharedError).assertIsDisplayed()
        assertEquals(first.canonicalUrl,model.discussions.community.state.value!!.url)
        assertEquals(0,service.writes.get())
    }

    @Test fun reopeningFeedPostKeepsRetainedDraftCollapsedWhenSharedServiceFails() {
        val service=Service();val model=showPostCard(service);settled(model)
        ui.onNodeWithTag("Shared discussion composer").performClick()
        val draft="Keep this thought while reading the original replies."
        ui.onNodeWithTag("CommentAnywhere draft").performTextInput(draft)
        ui.onNodeWithTag("CommentAnywhere draft").performImeAction()
        ui.onNodeWithContentDescription("Minimize shared composer").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithContentDescription("Close discussions").performClick()
        ui.waitUntil(5_000){model.discussions.state.value==null}
        service.failure=sharedError
        ui.onNodeWithTag("Read discussions ${repo.key(first)}").assertIsDisplayed().performClick()
        settled(model)
        assertQuietReadingArea()
        ui.onNodeWithText("Continue your shared comment").assertIsDisplayed()
        assertEquals(draft,model.discussions.community.state.value!!.draft.text)
        evidence("feed-card-reopen-retained-draft-does-not-cover-replies")
        ui.onNodeWithTag("Shared discussion composer").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertTextEquals(draft)
        ui.onNodeWithTag("Publish CommentAnywhere").assertIsNotEnabled()
        ui.onNodeWithText("Posting is paused while the shared discussion is unavailable.").assertIsDisplayed()
        ui.onNodeWithContentDescription("Minimize shared composer").performClick()
        assertQuietReadingArea()
        assertEquals(draft,model.discussions.community.state.value!!.draft.text)
        assertEquals(0,service.writes.get())
        evidence("feed-card-failed-shared-composer-minimized-draft-kept")
    }

    @Test fun laterPlatformRepliesAppearInAllWhileItsEarlierRepliesRemainAvailable() {
        val gate=CompletableDeferred<Unit>();val service=Service()
        val model=show(service) {post,_,_->
            if(post.platform==Platform.INSTAGRAM)gate.await()
            Page(listOf(post,reply(post,if(post.platform==Platform.X)replyText else "The second platform arrived independently.")))
        }
        ui.waitUntil(5_000){model.discussions.state.value?.threads?.get(first.platform.id)?.page!=null}
        ui.onNodeWithText(replyText).assertIsDisplayed()
        assertTrue(model.discussions.state.value!!.threads.getValue(other.platform.id).loading)
        gate.complete(Unit);settled(model)
        ui.onNodeWithText("The second platform arrived independently.").assertIsDisplayed()
        assertEquals(2,discussionEntries(model.discussions.state.value!!,true).size)
        ui.onNodeWithTag("Discussion comments").performScrollToNode(hasText(replyText))
        ui.onNodeWithText(replyText).assertIsDisplayed()
        evidence("all-platform-replies-arrive-progressively")
        assertEquals(0,service.writes.get())
    }

    @Test fun everyUnavailableSourceHasAnHonestEmptyStateAndPlatformFailureRemainsReadable() {
        val service=Service(sharedError)
        val model=show(service) {_,_,_->error(platformError)};settled(model)
        ui.onNodeWithText("Public replies are unavailable").assertIsDisplayed()
        ui.onNodeWithText("No public replies were supplied").assertDoesNotExist()
        ui.onNode(hasText("does not confirm that the post has no comments",substring=true) and hasAnyAncestor(hasTestTag("Discussion empty state"))).assertIsDisplayed()
        ui.onNodeWithTag("Shared discussion composer").assertIsDisplayed()
        evidence("unavailable-is-not-zero-comments")
        ui.onNodeWithTag("Discussion tab instagram").performClick()
        ui.onNodeWithText(platformError).assertIsDisplayed()
        ui.onNodeWithTag("Shared discussion composer").assertDoesNotExist()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        assertEquals(0,service.writes.get())
    }

    @Test fun suppliedEmptyPagesDoNotClaimThatTheOriginalPostHasNoComments() {
        val service=Service();val model=show(service) {post,_,_->Page(listOf(post))};settled(model)
        ui.onNodeWithText("No public replies were supplied").assertIsDisplayed()
        ui.onNode(hasText("does not confirm that the post has no comments",substring=true) and hasAnyAncestor(hasTestTag("Discussion empty state"))).assertIsDisplayed()
        ui.onNodeWithText("Public replies are unavailable").assertDoesNotExist()
        ui.onNodeWithTag("Shared discussion composer").assertIsDisplayed()
        evidence("empty-sources-remain-an-incomplete-view")
        assertEquals(0,service.writes.get())
    }

    @Test fun samePlatformOriginalsShareOnePlatformTabAndKeepTheirReplyLinksAndWritingRooms() {
        val originals=(1..3).map {index->first.copy(id="same-platform-$index",canonicalUrl="https://x.com/antisocial_fixture/status/884000$index",body="The same public announcement repeated.",replyCount=(index*10).toLong())}
        val service=Service();val model=show(service,roots=originals) {post,_,_->Page(listOf(post,reply(post,"Reply to ${post.id}")))}
        settled(model)
        ui.onAllNodesWithTag("Discussion tab twitter").assertCountEquals(1)
        ui.onNodeWithTag("Discussion tab all").assertIsSelected()
        ui.onNodeWithTag("Discussion original count").assertTextContains("3 originals",substring=true)
        val comments=ui.onNodeWithTag("Discussion comments")
        originals.forEach {root->comments.performScrollToNode(hasText("Reply to ${root.id}"));ui.onNodeWithText("Reply to ${root.id}").assertIsDisplayed()}
        val firstReply=reply(originals[0],"Reply to ${originals[0].id}")
        evidence("same-platform-three-originals-all")
        ui.onNodeWithTag("Discussion tab twitter").performClick()
        assertEquals(3,discussionEntries(model.discussions.state.value!!,true).size)
        ui.onNodeWithTag("Shared discussion composer").assertDoesNotExist()
        ui.onNodeWithTag("Discussion tab all").performClick();settled(model)
        ui.onNodeWithTag("Shared discussion composer").performClick()
        val second=originals[1];val key=model.discussions.state.value!!.threadKey(second)
        ui.onNodeWithTag("CommentAnywhere edition $key").performClick().assertIsSelected()
        ui.waitUntil(5_000){model.discussions.community.state.value?.url==second.canonicalUrl}
        ui.onNodeWithTag("Shared composer original URL").assertTextEquals(second.canonicalUrl.removePrefix("https://"))
        val captures=captureLoadedThreads(model,originals)
        assertEquals(originals.map(repo::key).toSet(),captures.filter {it.commentAnywhere==null}.map {repo.key(it.root)}.toSet())
        assertEquals(originals.map(repo::key).toSet(),captures.filter {it.commentAnywhere!=null}.map {repo.key(it.root)}.toSet())
        evidence("same-platform-selected-writing-room-url")
        ui.onNodeWithContentDescription("Minimize shared composer").performClick()
        comments.performScrollToNode(hasTestTag("Copy reply link ${repo.key(firstReply)}"))
        ui.onNodeWithTag("Copy reply link ${repo.key(firstReply)}").performClick()
        ui.waitUntil(5_000) {
            var copied: String?=null
            ui.runOnIdle {copied=ui.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()}
            copied==firstReply.canonicalUrl
        }
        ui.runOnIdle {assertEquals(firstReply.canonicalUrl,ui.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())}
        assertEquals(0,service.writes.get())
    }

    @Test fun collectedOriginalsStayLocalPreserveCaptionsAndOpenOnlyTheChosenThread() {
        val longCaption=(1..12).joinToString("\n"){line->"Original caption line $line keeps its complete wording."}
        val originals=(1..3).map {index->first.copy(id="original-sheet-$index",canonicalUrl="https://x.com/antisocial_fixture/status/885000$index",body=if(index==1)longCaption else "Caption for original $index.",publishedAt=1_750_000_000_000+index,replyCount=if(index==3)null else (index*10).toLong())}
        val service=Service();val reads=java.util.concurrent.CopyOnWriteArrayList<String>()
        val model=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope,service) {post,_,_->reads+=repo.key(post);Page(listOf(post,reply(post)))}});vm=model
        val checked=repo.gateway.protectionStats().checked
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()) {
            var open by remember {mutableStateOf(true)}
            if(open)OriginalPostsSheet(model,originals,originals[1],onDismiss={open=false})
            DiscussionSheet(model)
        }}}}
        val list=ui.onNodeWithTag("Collected original list")
        val firstKey=repo.key(originals[0]);val secondKey=repo.key(originals[1]);val thirdKey=repo.key(originals[2])
        list.performScrollToNode(hasTestTag("Original caption $firstKey"))
        ui.onNodeWithTag("Original caption $firstKey").assertTextEquals(longCaption)
        val collapsed=ui.onNodeWithTag("Original caption $firstKey").fetchSemanticsNode().size.height
        list.performScrollToNode(hasTestTag("Expand original caption $firstKey"));ui.onNodeWithTag("Expand original caption $firstKey").performClick()
        assertTrue(ui.onNodeWithTag("Original caption $firstKey").fetchSemanticsNode().size.height>collapsed)
        ui.onNodeWithTag("Expand original caption $firstKey").performScrollTo().performClick()
        list.performScrollToNode(hasTestTag("Original edition $secondKey"))
        ui.onNodeWithTag("Default original link").assertIsDisplayed()
        ui.onNodeWithText("Highest supplied reply count").assertIsDisplayed()
        list.performScrollToNode(hasTestTag("Original URL $thirdKey"))
        ui.onNodeWithTag("Original URL $thirdKey").assertTextEquals(originals[2].canonicalUrl)
        ui.onNodeWithText("Reply count not supplied").assertIsDisplayed()
        ui.onNodeWithTag("Copy original $thirdKey").assertExists()
        assertTrue(reads.isEmpty());assertEquals(0,service.reads.get());assertEquals(checked,repo.gateway.protectionStats().checked)
        evidence("collected-originals-counts-captions-and-authentic-links")
        list.performScrollToNode(hasTestTag("Original comments $thirdKey"));ui.onNodeWithTag("Original comments $thirdKey").performClick();settled(model)
        assertEquals(listOf(thirdKey),reads.toList());assertEquals(listOf(thirdKey),model.discussions.state.value!!.posts.map(repo::key))
        assertEquals(originals[2].canonicalUrl,model.discussions.community.state.value!!.url)
        assertEquals(0,service.writes.get())
    }
}
