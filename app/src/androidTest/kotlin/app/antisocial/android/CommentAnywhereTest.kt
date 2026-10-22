package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CommentAnywhereTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private var vm: AntiSocialViewModel?=null
    private val marker="antisocial_commentanywhere_native_fixture"
    private val root=Post("ca-root",Platform.X,"https://x.com/$marker/status/123",marker,"A public post read through an alternate source.")
    private val second=Post("ca-second",Platform.INSTAGRAM,"https://www.instagram.com/p/ABC",marker,"The same public post on another platform.")
    private data class Read(val url: String,val order: CommentOrder,val force: Boolean,val limit: Int,val parent: String?)
    private data class Write(val url: String,val action: AnywhereAction,val text: String,val id: String?,val value: Int?)
    private class Service: AnywhereAccess {
        val reads=CopyOnWriteArrayList<Read>();val writes=CopyOnWriteArrayList<Write>();var uid: String?=null
        var failRead=false;var failWrite=false;var gate: CompletableDeferred<Unit>?=null
        var readGate: CompletableDeferred<Unit>?=null;var cancellations=AtomicInteger()
        var items=listOf(AnywhereComment("alice","A conversation shared across readers.","Alice",1757592000000,5,1,children=listOf(AnywhereComment("child","A nested public reply.","Bob",1757592010000))),AnywhereComment("mine","My earlier comment.","Anonymous",1757591990000,authorIds=listOf("owner")))
        override suspend fun read(url: String,order: CommentOrder,force: Boolean,limit: Int,parentId: String?): AnywherePage {
            reads+=Read(url,order,force,limit,parentId)
            try {readGate?.await()}catch(e: CancellationException){cancellations.incrementAndGet();throw e}
            if(failRead)error("CommentAnywhere is unavailable right now. Try a platform thread or return later.")
            return AnywherePage(url,items,order,System.currentTimeMillis(),limit)
        }
        override suspend fun mutate(url: String,order: CommentOrder,action: AnywhereAction,text: String,id: String?,value: Int?) {
            writes+=Write(url,action,text,id,value);gate?.await();if(failWrite)error("Not confirmed")
            uid="owner"
            when(action){AnywhereAction.POST,AnywhereAction.REPLY->items=items+AnywhereComment("sent${writes.size}",text,authorIds=listOf("owner"));AnywhereAction.EDIT->items=items.map {if(it.id==id)it.copy(text=text)else it};AnywhereAction.DELETE->items=items.map {if(it.id==id)it.copy(text="[deleted by user]",deleted=true)else it};else->Unit}
        }
        override suspend fun owner()=uid
        override suspend fun forget(){uid=null}
        override fun clear(){}
    }
    @Before fun prepare() {
        runBlocking {repo.awaitReady()};preferences=repo.prefs.export().toString();repo.prefs.intervalMinutes=0
        repo.gateway.configure(NetworkPolicy(tor=true)) // Fixtures cannot leak public writes.
    }
    @After fun restore() {
        ui.runOnIdle {vm?.discussions?.close();vm?.viewModelScope?.cancel();ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings()}
        runBlocking {repo.dao.allBookmarks().filter {it.payload.contains(marker)}.forEach {repo.library.remove(it.key)}}
    }
    private fun show(service: Service,roots: List<Post> = listOf(root),platformReads: AtomicInteger=AtomicInteger(),selected: String?=COMMENT_ANYWHERE): AntiSocialViewModel {
        val model=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope,service) {post,_,_->platformReads.incrementAndGet();Page(listOf(post,post.copy(id="native-reply",canonicalUrl=post.canonicalUrl+"9",body="Platform public reply",parentId=post.id)))}})
        vm=model
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){DiscussionSheet(model)}}};model.discussions.open(roots,selected)}
        return model
    }
    private fun ready(model: AntiSocialViewModel){ui.waitUntil(10_000){model.discussions.community.state.value?.let {!it.loading&&(it.page!=null||it.error!=null)}==true};ui.waitUntil(5_000){ui.onAllNodesWithTag("Refresh CommentAnywhere").fetchSemanticsNodes().isNotEmpty()}}
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(250) // Settle Android dialog/IME window animation too.
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(ui.activity.getExternalFilesDir("validation") ?: File(ui.activity.filesDir,"validation"),"comment-settings").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun primaryNativeInterfaceUsesOriginalPageAndDoesNotReadPlatformThreadsUntilSelected() {
        val service=Service();val platform=AtomicInteger();val model=show(service,listOf(root,second),platform);ready(model)
        ui.onNodeWithTag("Discussion tab commentanywhere").assertIsSelected();assertEquals(COMMENT_ANYWHERE,model.discussions.state.value!!.selected)
        assertEquals(listOf(root.canonicalUrl),service.reads.map {it.url});assertEquals(0,platform.get());assertNull(service.uid)
        ui.onNodeWithTag("CommentAnywhere original URL").assertTextEquals(root.canonicalUrl.removePrefix("https://"))
        ui.onNodeWithTag("CommentAnywhere comments").performScrollToNode(hasText("A conversation shared across readers."));ui.onNodeWithText("A conversation shared across readers.").assertIsDisplayed();evidence("primary-native-conversation")
        ui.onNodeWithTag("CommentAnywhere comments").performScrollToNode(hasText("A nested public reply."));ui.onNodeWithText("A nested public reply.").assertIsDisplayed()
        ui.onNodeWithTag("Discussion tab instagram").performScrollTo().performClick()
        ui.waitUntil(5_000){platform.get()==1};ui.onNodeWithText("Platform public reply").assertIsDisplayed()
        assertEquals(1,service.reads.size)
    }
    @Test fun feedCommentButtonOpensAllWithoutMislabelingThePlatformsReplyCount() {
        val service=Service();val model=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope,service) {post,_,_->Page(listOf(post))}});vm=model
        val post=root.copy(replyCount=7654321)
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){Column {PostCard(post,model,false,onOpen={});DiscussionSheet(model)}}}}}
        val button=ui.onNodeWithTag("Read discussions ${repo.key(post)}")
        button.assertIsDisplayed();ui.onNode(hasText("7654321",substring=true) and hasAnyAncestor(hasTestTag("Read discussions ${repo.key(post)}"))).assertDoesNotExist()
        button.performClick();ui.waitUntil(10_000){model.discussions.community.state.value?.page!=null};ui.onNodeWithTag("Discussion tab all").assertIsSelected();assertEquals(root.canonicalUrl,service.reads.single().url)
    }
    @Test fun mergedEditionsKeepDistinctRootsAndDraftsAndUseObservedNewestAndTopSorts() {
        val service=Service();val model=show(service,listOf(root,second));ready(model)
        ui.onNodeWithTag("CommentAnywhere draft").performTextInput("Keep this X draft")
        ui.onNodeWithTag("CommentAnywhere draft").performImeAction()
        ui.onNodeWithTag("CommentAnywhere edition instagram").performClick();ready(model)
        assertEquals(second.canonicalUrl,service.reads.last().url);assertEquals("",model.discussions.community.state.value!!.draft.text)
        ui.onNodeWithTag("Discussion order SLOP").performClick();ready(model);assertEquals(CommentOrder.SLOP,service.reads.last().order)
        ui.onNodeWithTag("CommentAnywhere edition twitter").performClick();ready(model)
        ui.onNodeWithTag("CommentAnywhere draft").assertTextEquals("Keep this X draft");assertEquals(root.canonicalUrl,service.reads.last().url)
        assertTrue(service.writes.isEmpty());evidence("merged-post-original-editions")
    }
    @Test fun explicitReplyIsSubmittedOnceAndCannotClearAnotherEditionsDraft() {
        val service=Service();service.gate=CompletableDeferred();val model=show(service,listOf(root,second));ready(model)
        ui.onNodeWithTag("CommentAnywhere comments").performScrollToNode(hasContentDescription("Reply to Alice"))
        ui.onNodeWithContentDescription("Reply to Alice").assertIsDisplayed().performClick()
        ui.runOnIdle {assertEquals("alice",model.discussions.community.state.value!!.draft.target?.id)}
        ui.onNodeWithTag("CommentAnywhere draft").performTextInput("A thoughtful reply")
        evidence("native-reply-composer");ui.onNodeWithTag("Publish CommentAnywhere").assertIsDisplayed().performClick();ui.waitUntil(5_000){service.writes.size==1}
        ui.onNodeWithTag("Publish CommentAnywhere").assertIsNotEnabled()
        ui.onNodeWithTag("CommentAnywhere edition instagram").performClick();ready(model)
        ui.onNodeWithTag("CommentAnywhere draft").performTextInput("A separate Instagram draft")
        service.gate!!.complete(Unit);ui.waitUntil(5_000){model.discussions.community.state.value?.draft?.text=="A separate Instagram draft"}
        assertEquals(Write(root.canonicalUrl,AnywhereAction.REPLY,"A thoughtful reply","alice",null),service.writes.single())
        ui.onNodeWithTag("CommentAnywhere edition twitter").performClick();ready(model)
        ui.waitUntil(5_000){!model.discussions.community.state.value!!.draft.working};assertEquals("",model.discussions.community.state.value!!.draft.text)
    }
    @Test fun anUnconfirmedWriteKeepsTheDraftAndRequiresCheckingTheThreadBeforeRetry() {
        val service=Service().apply {failWrite=true};val model=show(service);ready(model)
        ui.onNodeWithTag("CommentAnywhere draft").performTextInput("Do not lose this draft");evidence("native-keyboard-composer");ui.onNodeWithTag("Publish CommentAnywhere").assertIsDisplayed().performClick()
        ui.waitUntil(5_000){model.discussions.community.state.value?.draft?.checkRequired==true&&!model.discussions.community.state.value!!.draft.working}
        assertEquals(1,service.writes.size);ui.onNodeWithTag("CommentAnywhere draft").assertTextEquals("Do not lose this draft");ui.onNodeWithTag("Publish CommentAnywhere").assertIsNotEnabled();evidence("unconfirmed-write-draft-kept")
        ui.onNodeWithTag("Refresh CommentAnywhere").performClick();ready(model)
        ui.onNodeWithTag("Publish CommentAnywhere").assertIsEnabled();assertEquals(1,service.writes.size)
    }
    @Test fun serviceFailuresAreVisibleAndNeverLookLikeAnEmptyWorkingConversation() {
        val service=Service().apply {failRead=true};val model=show(service);ready(model)
        ui.onNodeWithText("Shared discussion unavailable").assertIsDisplayed();ui.onNodeWithText("Start the conversation").assertDoesNotExist();ui.onNodeWithTag("Publish CommentAnywhere").assertIsNotEnabled();evidence("service-unavailable-platform-tabs-retained")
        service.failRead=false;ui.onNodeWithTag("Refresh CommentAnywhere").performClick();ready(model)
        ui.onNodeWithText("A conversation shared across readers.").assertIsDisplayed()
    }
    @Test fun originalArchiveIdentityAndQueryCollisionGuardsApplyBeforeAnyServiceRead() {
        val service=Service();val mirror=repo.routes.sources.first {it.platform==Platform.X&&it.frontend=="nitter"}
        val alternate=root.copy(canonicalUrl=mirror.root+"/$marker/status/123?utm_source=share#x")
        val model=show(service,listOf(alternate));ready(model);assertEquals(root.canonicalUrl,service.reads.single().url)
        ui.runOnIdle {model.discussions.open(listOf(root.copy(recoveredArchive=ArchiveRecovery("wayback",1,"https://web.archive.org/web/20200101000000/${root.canonicalUrl}"))),COMMENT_ANYWHERE)};ready(model)
        assertEquals(root.canonicalUrl,service.reads.last().url)
        val unsafe=second.copy(platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/story.php?story_fbid=123&id=456")
        ui.runOnIdle {model.discussions.open(listOf(unsafe),COMMENT_ANYWHERE)};ready(model)
        assertEquals(2,service.reads.size);ui.onNodeWithText("Shared discussion unavailable").assertIsDisplayed();ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
    }
    @Test fun changingEditionsCancelsTheOldReadAndNeverPaintsItsResultIntoAnotherPost() {
        val service=Service().apply {readGate=CompletableDeferred()};val model=show(service,listOf(root,second))
        ui.waitUntil(5_000){service.reads.size==1};ui.onNodeWithTag("CommentAnywhere edition instagram").performClick()
        ui.waitUntil(5_000){service.reads.size==2};service.readGate!!.complete(Unit);ready(model)
        assertEquals(1,service.cancellations.get());assertEquals(second.canonicalUrl,model.discussions.community.state.value!!.page!!.pageUrl)
    }
    @Test fun savedCommunityCommentsAreSearchableAndSurviveOrdinaryCachePurges() {
        val service=Service();val model=show(service);ready(model)
        val key=runBlocking {
            val capture=model.discussions.community.captures(listOf(root));assertEquals(3,capture.single().count)
            repo.library.save(root,emptyList(),capture)
        }
        runBlocking {repo.cleanup(true)}
        val copy=runBlocking {repo.library.copy(key)}!!;assertEquals(3,copy.details!!.threads.single().commentAnywhere!!.flattened().size)
        runBlocking {
            val before=copy.details.threads.single();val page=before.commentAnywhere!!
            val newer=page.copy(items=page.items.map {if(it.id=="alice")it.copy(text="An edited remote caption",children=it.children+AnywhereComment("later","A later saved reply"))else it})
            repo.library.save(root,emptyList(),listOf(before.copy(commentAnywhere=newer)))
            val preserved=repo.library.copy(key)!!.details!!.threads.single().commentAnywhere!!
            assertEquals(4,preserved.flattened().size);assertEquals("A conversation shared across readers.",preserved.items.first().text)
            assertThrows(IllegalArgumentException::class.java){repo.library.normalizedCommunity(root,page.copy(pageUrl=second.canonicalUrl))}
        }
        val matches=runBlocking {repo.library.entries("shared across readers").first()}
        assertTrue(matches.any {it.key==key})
        ui.onNodeWithContentDescription("Close discussions").performClick()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){LibraryScreen(repo,onOpenOriginal={})}}}}
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Library entry $key").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry $key").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasText("CommentAnywhere · X · saved locally"))
        ui.onNodeWithText("CommentAnywhere · X · saved locally").assertIsDisplayed()
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasText("A conversation shared across readers."))
        ui.onNodeWithText("A conversation shared across readers.").assertIsDisplayed();evidence("community-discussion-saved-offline")
    }
    @Test fun anonymousCredentialsAreEncryptedLocalAndExcludedFromExportsAndCacheClearing() {
        val vault=AnywhereVault(ui.activity);val fake=AnywhereIdentity("fixture-owner","fixture-private-token","fixture-private-refresh",Long.MAX_VALUE)
        runBlocking {vault.save(fake);assertEquals(fake,AnywhereVault(ui.activity).load())}
        val file=File(ui.activity.noBackupFilesDir,"commentanywhere.identity");val text=String(file.readBytes(),Charsets.ISO_8859_1)
        assertFalse(text.contains(fake.token));assertFalse(text.contains(fake.refreshToken))
        val exported=runBlocking {repo.exportBackup()}.toString(Charsets.UTF_8);assertFalse(exported.contains(fake.token));assertFalse(exported.contains(fake.refreshToken))
        runBlocking {repo.cleanup(true);assertEquals(fake,vault.load());vault.forget();assertNull(vault.load())};assertFalse(file.exists())
    }
    @Test fun ownerActionsUseNativeControlsAndSettingsShortcutClosesDiscussion() {
        val service=Service().apply {uid="owner"};val model=show(service);ready(model)
        ui.onNodeWithTag("CommentAnywhere comments").performScrollToNode(hasContentDescription("Edit your comment"))
        ui.onNodeWithContentDescription("Edit your comment").performClick();ui.onNodeWithTag("CommentAnywhere draft").performTextReplacement("Edited native comment");ui.onNodeWithTag("Publish CommentAnywhere").performClick()
        ui.waitUntil(5_000){service.writes.any {it.action==AnywhereAction.EDIT}&&!model.discussions.community.state.value!!.loading};ready(model);assertEquals("mine",service.writes.single().id)
        ui.onNodeWithContentDescription("Comment settings").performClick()
        ui.waitUntil(5_000){model.discussions.state.value==null}
        assertEquals(SettingsPage.COMMENTS,model.screen.value.settingsPage);assertEquals(1,service.writes.size)

    }
    @Test fun allIsFirstAndDefaultAndOnlySharedTabsOfferParticipation() {
        val service=Service();val platform=AtomicInteger();val model=show(service,listOf(root),platform,selected=null)
        ui.waitUntil(10_000){model.discussions.community.state.value?.page!=null&&platform.get()==1}
        ui.onNodeWithTag("Discussion tab all").assertIsSelected()
        assertTrue(ui.onNodeWithTag("Discussion tab all").fetchSemanticsNode().boundsInRoot.left < ui.onNodeWithTag("Discussion tab commentanywhere").fetchSemanticsNode().boundsInRoot.left)
        ui.onNodeWithText("CommentAnywhere").assertDoesNotExist()
        ui.onNodeWithTag("Shared discussion composer").assertIsDisplayed()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithTag("Discussion comments").performScrollToNode(hasText("Platform public reply"))
        ui.onNodeWithText("Platform public reply").assertIsDisplayed()
        ui.onNodeWithTag("Discussion comments").performScrollToNode(hasText("A conversation shared across readers."))
        ui.onNodeWithText("A conversation shared across readers.").assertIsDisplayed();evidence("all-mixed-comments")
        ui.onNodeWithTag("Shared discussion composer").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertIsDisplayed()
        ui.onNodeWithContentDescription("Minimize shared composer").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithTag("Discussion tab twitter").performClick()
        ui.onNodeWithTag("Shared discussion composer").assertDoesNotExist()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithTag("Publish CommentAnywhere").assertDoesNotExist();evidence("platform-read-only")
        ui.onNodeWithTag("Discussion tab commentanywhere").performClick();ready(model)
        ui.onNodeWithTag("Shared discussion composer").assertIsDisplayed()
        ui.onNodeWithTag("Discussion tab all").performClick()
        ui.onNodeWithTag("Shared discussion composer").assertIsDisplayed()
        assertTrue(service.writes.isEmpty());assertNull(service.uid)
    }
    @Test fun allKeepsOriginalRoomsSeparateAndRepliesToTheSelectedCommunityRoot() {
        val service=Service();val model=show(service,listOf(root,second),selected=null)
        ui.waitUntil(10_000){model.discussions.state.value?.communities?.values?.count {it.page!=null}==2}
        assertEquals(setOf(root.canonicalUrl,second.canonicalUrl),service.reads.map {it.url}.toSet())
        val entries=discussionEntries(model.discussions.state.value!!,true)
        assertEquals(8,entries.size);assertEquals(8,entries.map {it.key}.toSet().size)
        val captures=model.discussions.communityCaptures(listOf(root,second))
        assertEquals(setOf(root.canonicalUrl,second.canonicalUrl),captures.map {it.commentAnywhere!!.pageUrl}.toSet())
        ui.onNodeWithTag("Shared discussion composer").performClick()
        ui.onNodeWithTag("CommentAnywhere edition instagram").performClick()
        ui.waitUntil(5_000){model.discussions.community.state.value?.post?.platform==Platform.INSTAGRAM&&model.discussions.community.state.value?.page!=null}
        ui.onNodeWithTag("CommentAnywhere draft").performTextInput("Participation belongs to the shared room")
        evidence("all-keyboard-participation")
        ui.onNodeWithTag("Publish CommentAnywhere").performClick()
        ui.waitUntil(5_000){service.writes.size==1}
        assertEquals(second.canonicalUrl,service.writes.single().url);assertEquals(AnywhereAction.POST,service.writes.single().action)
        ui.onNodeWithTag("Discussion tab instagram").performScrollTo().performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
    }
    @Test fun allRetainsPlatformRepliesWhenTheSharedServiceFails() {
        val service=Service().apply {failRead=true};val model=show(service,selected=null)
        ui.waitUntil(10_000){model.discussions.state.value?.threads?.get(Platform.X.id)?.page!=null&&model.discussions.community.state.value?.error!=null}
        ui.onNodeWithText("Platform public reply").assertIsDisplayed()
        ui.onNodeWithTag("Discussion source availability").assertDoesNotExist()
        ui.onNodeWithText("Shared discussion unavailable").assertDoesNotExist()
        ui.onNodeWithText("CommentAnywhere is unavailable right now. Try a platform thread or return later.").assertDoesNotExist()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithText("Join shared discussion").assertIsDisplayed()
        ui.onNodeWithTag("Shared discussion composer").performClick()
        ui.onNodeWithTag("Publish CommentAnywhere").assertIsNotEnabled();assertTrue(service.writes.isEmpty())
    }
    @Test fun allComposerKeepsItsDraftWhenMinimizedAndPlatformTabsStayReadOnly() {
        val service=Service();val model=show(service,selected=null)
        ui.waitUntil(10_000){model.discussions.community.state.value?.page!=null}
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithTag("Shared discussion composer").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").performTextInput("Keep this shared draft while I read")
        ui.onNodeWithTag("CommentAnywhere draft").performImeAction()
        ui.onNodeWithContentDescription("Minimize shared composer").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithText("Continue your shared comment").assertIsDisplayed()
        assertEquals("Keep this shared draft while I read",model.discussions.community.state.value!!.draft.text)
        ui.onNodeWithTag("Shared discussion composer").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertTextEquals("Keep this shared draft while I read")
        ui.onNodeWithTag("Discussion tab twitter").performClick()
        ui.onNodeWithTag("Shared discussion composer").assertDoesNotExist()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithTag("Discussion tab all").performClick()
        ui.waitUntil(5_000){model.discussions.community.state.value?.page!=null}
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithText("Continue your shared comment").assertIsDisplayed()
        ui.onNodeWithTag("Shared discussion composer").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertTextEquals("Keep this shared draft while I read")
        assertTrue(service.writes.isEmpty());assertNull(service.uid)
        evidence("all-minimized-draft-preserved")
    }
    @Test fun explicitReplyReopensAMinimizedComposerWithoutRestoredDraftOpeningItself() {
        val service=Service();val model=show(service,selected=null)
        ui.waitUntil(10_000){model.discussions.community.state.value?.page!=null}
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithTag("Discussion comments").performScrollToNode(hasContentDescription("Reply to Alice"))
        ui.onNodeWithContentDescription("Reply to Alice").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertIsDisplayed()
        ui.onNodeWithText("Replying to Alice").assertIsDisplayed()
        ui.onNodeWithTag("CommentAnywhere draft").performTextInput("A retained reply for this conversation")
        ui.onNodeWithTag("CommentAnywhere draft").performImeAction()
        ui.onNodeWithContentDescription("Minimize shared composer").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertDoesNotExist()
        ui.onNodeWithText("Continue your shared reply").assertIsDisplayed()
        assertEquals("alice",model.discussions.community.state.value!!.draft.target!!.id)
        evidence("all-explicit-reply-minimized-with-target-kept")
        // Selecting the same reply again must reopen it even when the target ID
        // and draft have not changed since the earlier explicit action.
        ui.onNodeWithTag("Discussion comments").performScrollToNode(hasContentDescription("Reply to Alice"))
        ui.onNodeWithContentDescription("Reply to Alice").performClick()
        ui.onNodeWithTag("CommentAnywhere draft").assertIsDisplayed().assertTextEquals("A retained reply for this conversation")
        ui.onNodeWithText("Replying to Alice").assertIsDisplayed()
        assertTrue(service.writes.isEmpty());assertNull(service.uid)
        evidence("all-same-explicit-reply-reopens-minimized-composer")
    }
    @Test fun combinedOrderingKeepsReplyTreesAndUsesDistinctServiceAndPlatformIds() {
        val platform=root.copy(id="alice",canonicalUrl=root.canonicalUrl+"9",body="Platform public reply",parentId=root.id,publishedAt=10)
        val tree=AnywhereComment("alice","Shared root",timestamp=20,children=listOf(AnywhereComment("child","Shared child",timestamp=1)))
        val other=AnywhereComment("other","Other root",timestamp=15)
        val state=DiscussionState(listOf(root),"all",threads=mapOf(root.platform.id to ThreadRead(root,Page(listOf(root,platform)))),communities=mapOf(root.platform.id to AnywhereThreadRead(root,root.canonicalUrl,AnywherePage(root.canonicalUrl,listOf(tree,other),CommentOrder.NEWEST,1))))
        val newest=discussionEntries(state,true)
        assertEquals(listOf("alice","child","other",null),newest.map {it.community?.id});assertEquals(4,newest.map {it.key}.toSet().size)
        assertEquals(listOf(null,"alice","child","other"),discussionEntries(state.copy(order=ReadingOrder.SLOP),true).map {it.community?.id})
        assertEquals(1,discussionEntries(state,false).size)
    }
    @Test fun liveServiceAvailabilityIsDisplayedHonestlyWithoutPublishing() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveCommentAnywhere")=="true")
        repo.gateway.configure(NetworkPolicy());val model=AntiSocialViewModel(repo);vm=model
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){DiscussionSheet(model)}}};model.discussions.open(listOf(root),COMMENT_ANYWHERE)}
        ready(model);val current=model.discussions.community.state.value!!
        assertTrue(current.page!=null||current.error!=null);assertEquals(root.canonicalUrl,current.url);evidence("live-public-service-result")
    }
    @Test fun liveSettingsConnectionCheckUsesPublicReadingWithoutAnIdentity() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveCommentAnywhere")=="true")
        repo.prefs.communityComments=true;repo.gateway.configure(NetworkPolicy())
        assertNull(runBlocking {repo.commentAnywhere.owner()})
        runBlocking {try {repo.commentAnywhere.check()}catch(e: Exception){assertTrue(e.message.orEmpty().isNotBlank())}}
        val connection=repo.commentAnywhere.connection.value
        assertNotNull(connection.checkedAt);assertFalse(connection.checking)
        assertNull(runBlocking {repo.commentAnywhere.owner()})
        val model=AntiSocialViewModel(repo);vm=model
        ui.runOnUiThread {model.settings(SettingsPage.COMMENTS);ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){SettingsScreen(model,repo.prefs.selected,{},{})}}}}
        ui.onNodeWithText(connection.title).assertIsDisplayed();evidence("live-service-settings-status")
    }
}
