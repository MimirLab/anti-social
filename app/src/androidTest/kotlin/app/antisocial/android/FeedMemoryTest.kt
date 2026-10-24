package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Native feed fixtures use local records and injected public pages, never live accounts. */
@RunWith(AndroidJUnit4::class)
class FeedMemoryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var originalMemory: FeedMemoryStore
    private lateinit var originalLoader: suspend (Follow)->Page
    private lateinit var originalPageLoader: suspend (Follow,Page)->Page
    private lateinit var memoryFile: File
    private var memoryScope: CoroutineScope?=null
    private val models=mutableListOf<AntiSocialViewModel>()
    private val fixturePosts=mutableListOf<Post>()
    private val fixtureFollows=mutableListOf<Follow>()
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val now=System.currentTimeMillis()
    private val person="feed-memory-$suffix"
    private val personFeed="person:$person"

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();originalMemory=repo.feedMemory;originalLoader=repo.followLoader;originalPageLoader=repo.followPageLoader
        repo.prefs.intervalMinutes=0;repo.prefs.wifiOnly=false;repo.prefs.archiveChecks=false
        Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].let {it.following();it.viewModelScope.cancel()}
            ui.activity.setContent {MaterialTheme {Text("Preparing local reading-memory fixtures")}}
        }
        ui.waitForIdle()
        runBlocking {withTimeout(5_000){repo.feedRefresh.first {!it.running}}}
        memoryFile=File(ui.activity.cacheDir,"feed-memory-test-${UUID.randomUUID()}.json")
        reopenMemory()
    }
    @After fun restore() {
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.runOnIdle {
            models.forEach {it.discussions.close();it.viewModelScope.cancel()}
            ui.activity.setContent {MaterialTheme {Text("Feed memory fixture complete")}}
        }
        runBlocking {
            models.forEach {it.viewModelScope.coroutineContext[Job]?.join()}
            withTimeout(5_000){repo.feedRefresh.first {!it.running}}
            repo.feedMemory.flush();memoryScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        }
        repo.feedMemory=originalMemory;repo.followLoader=originalLoader;repo.followPageLoader=originalPageLoader
        runBlocking {
            fixtureFollows.forEach {repo.dao.removeFollow(it.id)}
            val keys=fixturePosts.map(repo::key).distinct()
            repo.dao.removePosts(keys)
            for(table in listOf("history","seen"))for(key in keys)
                repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }
        listOf(memoryFile,File(memoryFile.path+".bak"),File(memoryFile.path+".new")).forEach {it.delete()}
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }
    private fun reopenMemory() {
        if(memoryScope!=null)runBlocking {repo.feedMemory.flush();memoryScope?.coroutineContext?.get(Job)?.cancelAndJoin()}
        memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope!!,memoryFile)
        runBlocking {repo.feedMemory.awaitReady()}
    }
    private fun model()=AntiSocialViewModel(repo).also {it.following();models+=it}
    private fun posts(count: Int,platform: Platform=Platform.X,start: Int=0): List<Post> = (start until start+count).map {index->
        val id="96$suffix${1000+index}"
        val handle="asmemory${suffix.takeLast(4)}"
        val url=if(platform==Platform.BLUESKY)"https://bsky.app/profile/$handle.test/post/$id"else "https://x.com/$handle/status/$id"
        Post(id,platform,url,handle,"${platform.label} memory post $index\nReading position stays steady while more public posts arrive.",publishedAt=now-index*60_000L).also {fixturePosts+=it}
    }
    private fun follow(platform: Platform=Platform.X,label: String=platform.label): Follow {
        val handle="asmemory${suffix.takeLast(4)}"
        val target=Target(platform,TargetKind.PROFILE,handle,if(platform==Platform.BLUESKY)"https://bsky.app/profile/$handle.test"else "https://x.com/$handle")
        return Follow("$person:${platform.id}:$label",target,"$label fixture",personId=person,personName="Feed memory fixture").also {fixtureFollows+=it}
    }
    private fun show(posts: State<List<Post>>,follows: List<Follow>,feedId: String=personFeed,grouped: Boolean=false,refreshPerson: Boolean=false): AntiSocialViewModel {
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {
            MaterialTheme {ChronologicalFeed(vm,posts.value,follows,emptySet(),feedId,"Feed memory fixture","Public posts, newest first",grouped=grouped,onAdd={},onRefresh=if(refreshPerson)({vm.launch {repo.refresh(personId=person)}})else null)}
        }}
        ui.onNodeWithTag(if(grouped)"Combined feed"else "Following feed").assertIsDisplayed()
        return vm
    }
    private fun row(post: Post)=ui.onNodeWithTag("Feed row ${repo.key(post)}")
    private fun top(post: Post)=row(post).fetchSemanticsNode().boundsInRoot.top
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(200)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"feed-memory").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun compactNativeReaderKeepsContentVisibleAndSecondaryToolsReachable() {
        val account=follow();val records=posts(6)
        ui.runOnIdle {
            repo.prefs.theme="light";repo.prefs.selected=listOf(Platform.X,Platform.REDDIT,Platform.BLUESKY)
            repo.prefs.activePlatform=Platform.X;repo.prefs.communityComments=false
            repo.gateway.configure(NetworkPolicy(tor=true))
        }
        runBlocking {
            repo.dao.putFollow(FollowRow(account.id,account.target.platform.id,repo.gson.toJson(account)))
            repo.store(Page(records),account.id,true)
        }
        seed("all",records)
        val vm=model();val appearance=mutableStateOf(0)
        ui.runOnIdle {vm.combined();ui.activity.setContent {key(appearance.value){AntiSocialApp(vm)}}}
        waitForRow(records.first())
        val viewport=ui.onNodeWithTag("Combined feed").fetchSemanticsNode().boundsInRoot
        val root=ui.onNode(isRoot()).fetchSemanticsNode().boundsInRoot
        val density=ui.activity.resources.displayMetrics.density
        assertTrue("Content viewport should occupy most of the physical screen, including its system bars",viewport.height>root.height*.55f)
        val destination=ui.onNodeWithTag("Primary destination saved").fetchSemanticsNode().boundsInRoot
        val icon=ui.onNodeWithContentDescription("Library",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val label=ui.onNodeWithText("Library",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue("Bottom navigation must fit above the system gesture area",icon.top>=destination.top&&label.bottom<=destination.bottom)
        assertTrue("Feed chrome should remain compact",ui.onNodeWithTag("Feed toolbar").fetchSemanticsNode().boundsInRoot.height<=56*density)
        ui.onNodeWithTag("Channel all subscriptions").assertDoesNotExist()
        ui.onNodeWithContentDescription("Subscribe").assertDoesNotExist()
        ui.onNodeWithTag("Copy post link ${repo.key(records.first())}").assertDoesNotExist()
        evidence("compact-full-reader-light")
        ui.onNodeWithTag("Choose feed").performClick()
        ui.onNodeWithTag("Feed chooser").assertIsDisplayed()
        ui.onNodeWithTag("Channel all subscriptions").performClick()
        ui.onNodeWithTag("Feed options").performClick()
        ui.onNodeWithText("Add a subscription").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithTag("Post options ${repo.key(records.first())}").performScrollTo().performClick()
        ui.onNodeWithTag("Copy post link ${repo.key(records.first())}").assertIsDisplayed()
        ui.onNodeWithTag("Save copy ${repo.key(records.first())}").assertIsDisplayed()
        ui.onNodeWithTag("Filter like ${repo.key(records.first())}").assertIsDisplayed()
        evidence("compact-post-options")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.runOnIdle {repo.prefs.theme="dark";appearance.value++}
        waitForRow(records.first());evidence("compact-full-reader-dark")
    }

    private fun seed(feedId: String,posts: List<Post>,anchorIndex: Int=0,offset: Int=0) {
        val keys=posts.map(repo::key)
        repo.feedMemory.reconcile(feedId,keys)
        repo.feedMemory.anchor(feedId,keys[anchorIndex],offset)
    }
    private fun waitForRow(post: Post) {
        try {
            ui.waitUntil(5_000){ui.onAllNodesWithTag("Feed row ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()}
            row(post).assertIsDisplayed()
        }catch(failure: Throwable) {failureEvidence("row-${post.id}",post,failure);throw failure}
    }
    private fun waitForRestoredAnchor(feedId: String,post: Post) {
        // A lazy row can exist while the saved-position coroutine or the
        // asynchronous filter assessment still has a pending layout. Leaving
        // that composition early would test disposal during initialization.
        try {
            ui.waitUntil(5_000){
                repo.feedMemory.get(feedId).anchorKey==repo.key(post)&&
                    ui.onAllNodesWithTag("Read discussions ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()&&
                    ui.onAllNodesWithTag("Jump to newest").fetchSemanticsNodes().any {!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}
            }
            ui.waitForIdle()
        }catch(failure: Throwable){failureEvidence("restored-${post.id}",post,failure);throw failure}
    }
    private fun waitForRead(post: Post) {
        try {ui.waitUntil(5_000){FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(post))}}
        catch(failure: Throwable) {failureEvidence("read-${post.id}",post,failure);throw failure}
    }
    private fun awaitAutomaticPrefix(arrivals: List<Post>) {
        val keys=arrivals.map(repo::key)
        ui.waitUntil(10_000){
            val memory=repo.feedMemory.get(personFeed)
            memory.stagedKeys.isEmpty()&&memory.acceptedKeys.containsAll(keys)&&
                ui.onAllNodesWithTag("Jump to newest").fetchSemanticsNodes().any {!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}
        }
        ui.waitForIdle()
    }
    /** Real downward finger gestures read toward newer rows. No batch/adoption
     * button or direct scroll-to-index shortcut is used to reveal arrivals. */
    private fun readUpTo(post: Post,maximumSwipes: Int=24) {
        val feed=ui.onNodeWithTag("Following feed")
        repeat(maximumSwipes) {
            if(ui.onAllNodesWithTag("Feed row ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()&&runCatching {row(post).assertIsDisplayed()}.isSuccess)return
            feed.performTouchInput {swipeDown(startY=height*.22f,endY=height*.78f,durationMillis=320)}
            ui.waitForIdle()
        }
        try {row(post).assertIsDisplayed()}
        catch(failure: Throwable){failureEvidence("upward-newer-${post.id}",post,failure);throw failure}
    }
    private fun failureEvidence(name: String,expected: Post,failure: Throwable) {
        // Capture the failing session before @After disposes its native composition.
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"feed-memory").apply {mkdirs()}
        runCatching {evidence("failure-$name")}
        runCatching {File(dir,"failure-$name-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())}
            .onFailure {File(dir,"failure-$name-semantics-error.txt").writeText(it.stackTraceToString())}
        runCatching {File(dir,"failure-$name-merged-semantics.txt").writeText(ui.onRoot().printToString())}
        File(dir,"failure-$name-memory.json").writeText(repo.gson.toJson(mapOf(
            "failure" to failure.stackTraceToString(),"expectedKey" to repo.key(expected),"expectedPost" to expected,
            "fixtureKeys" to fixturePosts.map(repo::key),"ready" to repo.feedMemory.ready.value,
            "states" to repo.feedMemory.states.value,"refresh" to repo.feedMemory.refresh.value,
            "screens" to models.map {it.screen.value},"lifecycle" to ui.activity.lifecycle.currentState.name,
            "windowFocused" to ui.activity.window.decorView.hasWindowFocus()
        )))
    }

    @Test fun viewportAnchorsExcludeSpacingOnlyCardsAndKeepPartlyVisibleContent() {
        fun item(fixtureKey: String,fixtureOffset: Int,fixtureSize: Int)=object: LazyListItemInfo {
            override val key: Any=fixtureKey
            override val index=0
            override val offset=fixtureOffset
            override val size=fixtureSize
        }
        val layout=object: LazyListLayoutInfo {
            override val visibleItemsInfo=listOf(
                item("preceding-spacing-only",-782,719),
                item("ends-at-padding-edge",-100,68),
                item("partly-in-top-padding",-40,20),
                item("reading-card",-31,719),
                item("partly-at-bottom",980,100),
                item("after-viewport",1000,100),
                item("zero-height",10,0))
            override val viewportStartOffset=-32
            override val viewportEndOffset=1000
            override val totalItemsCount=visibleItemsInfo.size
        }
        assertEquals(listOf("partly-in-top-padding","reading-card","partly-at-bottom"),layout.feedViewportItems().map {it.key})
    }

    @Test fun exactPostAndPixelOffsetSurviveActivityRecreationAndStoreReload() {
        val rows=posts(18);val follows=listOf(follow());val anchor=rows[8]
        seed(personFeed,rows,8,67)
        val data=mutableStateOf(rows)
        show(data,follows);waitForRow(anchor)
        ui.waitUntil(5_000){repo.feedMemory.get(personFeed).anchorKey==repo.key(anchor)}
        val originalTop=top(anchor)
        assertEquals(67,repo.feedMemory.get(personFeed).anchorOffset)
        runBlocking {repo.feedMemory.flush()}
        ui.activityRule.scenario.recreate()
        show(data,follows);waitForRow(anchor)
        ui.waitUntil(5_000){abs(top(anchor)-originalTop)<=2f}
        assertEquals(67,repo.feedMemory.get(personFeed).anchorOffset)
        runBlocking {repo.feedMemory.flush()}
        val persisted=repo.feedMemory.get(personFeed)
        ui.runOnIdle {ui.activity.setContent {Text("Reloading local reading memory")}}
        reopenMemory()
        assertEquals(persisted,repo.feedMemory.get(personFeed))
        show(data,follows);waitForRow(anchor)
        ui.waitUntil(5_000){abs(top(anchor)-originalTop)<=2f}
        evidence("exact-anchor-restored")
    }

    @Test fun idleArrivalsJoinTheScrollableFeedWithoutMovingTheCurrentPost() {
        val rows=posts(16);val follows=listOf(follow());val anchor=rows[7]
        seed(personFeed,rows,7,43)
        val data=mutableStateOf(rows)
        show(data,follows);waitForRow(anchor)
        val before=top(anchor)
        val arrivals=posts(2,start=-2)
        ui.runOnIdle {data.value=arrivals+rows}
        awaitAutomaticPrefix(arrivals)
        ui.onNodeWithTag("Show new posts").assertDoesNotExist()
        assertEquals(before,top(anchor),2f)
        assertEquals(repo.key(anchor),repo.feedMemory.get(personFeed).anchorKey)
        assertTrue(repo.feedMemory.get(personFeed).stagedKeys.isEmpty())
        assertTrue(repo.feedMemory.get(personFeed).acceptedKeys.containsAll(arrivals.map(repo::key)))
        assertTrue(arrivals.none {FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(it))})
        arrivals.forEach {row(it).assertDoesNotExist()}
        evidence("incoming-prefix-adopted-with-fixed-anchor")
    }

    @Test fun restoredAnchorKeepsFetchedNewerPostsReachableAfterAnInitialEmptyEmission() {
        val older=posts(18);val arrivals=posts(4,start=-4);val all=arrivals+older
        val follows=listOf(follow());val anchor=older[8]
        seed(personFeed,older,8,53)
        val data=mutableStateOf(older)
        show(data,follows);waitForRow(anchor)
        val originalTop=top(anchor)
        ui.runOnIdle {ui.activity.setContent {Text("Background fetch while the reader is closed")}}
        // Complete disposal before replacing the store. Otherwise two rapid
        // setContent calls can retain the old feed's remembered rows and scope.
        ui.waitForIdle()
        repo.feedMemory.reconcile(personFeed,all.map(repo::key))
        assertEquals(arrivals.map(repo::key).toSet(),repo.feedMemory.get(personFeed).stagedKeys.toSet())
        runBlocking {repo.feedMemory.flush()};reopenMemory()
        val initiallyEmpty=mutableStateOf<List<Post>>(emptyList())
        show(initiallyEmpty,follows)
        assertEquals(repo.key(anchor),repo.feedMemory.get(personFeed).anchorKey)
        assertEquals(53,repo.feedMemory.get(personFeed).anchorOffset)
        ui.runOnIdle {initiallyEmpty.value=all}
        waitForRow(anchor)
        ui.waitUntil(5_000){abs(top(anchor)-originalTop)<=2f}
        awaitAutomaticPrefix(all)
        assertEquals(originalTop,top(anchor),2f)
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(arrivals.first())))
        evidence("restored-anchor-with-newer-cached-posts")
        readUpTo(arrivals.first())
        row(arrivals.first()).assertIsDisplayed()
        val feed=ui.onNodeWithTag("Following feed")
        arrivals.forEach {post->feed.performScrollToNode(hasTestTag("Feed row ${repo.key(post)}"));row(post).assertIsDisplayed()}
        evidence("restored-newer-posts-reachable-without-show")
    }

    @Test fun consecutiveLivePrefixesAreReadyBeforeAnyUpwardReadingOrShowTap() {
        val older=posts(16);val follows=listOf(follow());val anchor=older[7]
        seed(personFeed,older,7,43)
        val data=mutableStateOf(older)
        show(data,follows);waitForRow(anchor)
        val before=top(anchor)
        val arrivals=posts(3,start=-3)
        ui.runOnIdle {data.value=arrivals+older}
        awaitAutomaticPrefix(arrivals)
        assertEquals(before,top(anchor),2f)
        assertEquals(repo.key(anchor),repo.feedMemory.get(personFeed).anchorKey)
        ui.onNodeWithTag("Show new posts").assertDoesNotExist()
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(arrivals.first())))
        val later=posts(3,start=-6)
        ui.runOnIdle {data.value=later+arrivals+older}
        awaitAutomaticPrefix(later+arrivals)
        assertEquals(before,top(anchor),2f)
        assertEquals(repo.key(anchor),repo.feedMemory.get(personFeed).anchorKey)
        assertTrue((later+arrivals).none {FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(it))})
        evidence("live-arrivals-stable-before-upward-reading")
        readUpTo(later.first())
        row(later.first()).assertIsDisplayed()
        assertEquals(emptyList<String>(),repo.feedMemory.get(personFeed).stagedKeys)
        evidence("live-arrivals-reached-by-upward-reading")
    }

    @Test fun theEntireNewPrefixIsInsertedAtIdleEvenWhenTheOldHeadCannotScrollUp() {
        val older=posts(12);val follows=listOf(follow());val oldHead=older.first()
        seed(personFeed,older)
        val data=mutableStateOf(older)
        show(data,follows);waitForRow(oldHead)
        val before=top(oldHead)
        val arrivals=posts(9,start=-9)
        ui.runOnIdle {data.value=arrivals+older}
        try {
            // No gesture, Show button or Newest action is used to admit any of
            // these nine rows. Their complete prefix must be ready while idle.
            awaitAutomaticPrefix(arrivals)
            row(oldHead).assertIsDisplayed()
            assertEquals(before,top(oldHead),2f)
            assertTrue(arrivals.none {FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(it))})
            ui.onNodeWithTag("Show new posts").assertDoesNotExist()
        }catch(failure: Throwable){failureEvidence("idle-entire-prefix",oldHead,failure);throw failure}
        evidence("idle-prefix-keeps-old-head-pixel")
        readUpTo(arrivals.first(),maximumSwipes=36);row(arrivals.first()).assertIsDisplayed()
        arrivals.forEach {post->ui.onNodeWithTag("Following feed").performScrollToNode(hasTestTag("Feed row ${repo.key(post)}"));row(post).assertIsDisplayed()}
    }

    @Test fun newestWithLateArrivalsKeepsAnExactContinuePointAcrossAStoreReload() {
        val older=posts(26);val follows=listOf(follow());val anchor=older[17]
        seed(personFeed,older,17,49)
        repo.feedMemory.markRead(personFeed,listOf(repo.key(anchor)))
        val data=mutableStateOf(older)
        show(data,follows);waitForRow(anchor)
        val before=top(anchor)
        val arrivals=posts(3,start=-3);val all=arrivals+older
        ui.runOnIdle {data.value=all}
        awaitAutomaticPrefix(arrivals)
        assertEquals(before,top(anchor),2f)
        ui.onNodeWithTag("Jump to newest").performClick();waitForRow(arrivals.first())
        assertEquals(repo.key(anchor),repo.feedMemory.get(personFeed).resumeReturn?.key)
        assertEquals(49,repo.feedMemory.get(personFeed).resumeReturn?.offset)
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(older[9])))
        ui.runOnIdle {ui.activity.setContent {Text("Reloading the continue-reading point")}}
        runBlocking {repo.feedMemory.flush()};reopenMemory()
        show(mutableStateOf(all),follows);waitForRow(arrivals.first())
        ui.onNodeWithTag("Continue reading").assertIsDisplayed().performClick();waitForRow(anchor)
        ui.waitUntil(5_000){abs(top(anchor)-before)<=2f}
        assertNull(repo.feedMemory.get(personFeed).resumeReturn)
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(older[9])))
        evidence("late-arrivals-newest-and-persisted-continue")
    }

    @Test fun newArrivalsAndEarlierReadPostsHaveAStableNativeBoundary() {
        val older=posts(8,start=2);val newer=posts(2);val all=newer+older
        val follows=listOf(follow())
        seed(personFeed,older)
        repo.feedMemory.markRead(personFeed,older.map(repo::key))
        repo.feedMemory.reconcile(personFeed,all.map(repo::key))
        repo.feedMemory.accept(personFeed,all.map(repo::key),newer.map(repo::key))
        repo.feedMemory.anchor(personFeed,repo.key(newer.first()),0)
        assertTrue(newer.all {repo.key(it) in repo.feedMemory.get(personFeed).freshKeys})
        assertTrue(older.all {FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(it))})
        show(mutableStateOf(all),follows)
        ui.onNodeWithTag("Following feed").performScrollToNode(hasTestTag("Earlier posts"))
        ui.onNodeWithTag("Earlier posts").assertIsDisplayed()
        val boundary=ui.onNodeWithTag("Earlier posts").fetchSemanticsNode().boundsInRoot.top
        repo.feedMemory.markRead(personFeed,newer.map(repo::key))
        ui.waitForIdle()
        assertEquals(boundary,ui.onNodeWithTag("Earlier posts").fetchSemanticsNode().boundsInRoot.top,2f)
        evidence("new-and-earlier-boundary")
    }

    @Test fun jumpToNewestKeepsAContinuePointAndDoesNotReadSkippedPosts() {
        val rows=posts(26);val follows=listOf(follow());val anchor=rows[17]
        seed(personFeed,rows,17,49)
        repo.feedMemory.markRead(personFeed,listOf(repo.key(anchor)))
        show(mutableStateOf(rows),follows);waitForRow(anchor)
        val before=top(anchor)
        ui.onNodeWithTag("Jump to newest").performClick()
        waitForRow(rows.first())
        ui.onNodeWithTag("Continue reading").assertIsDisplayed()
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(rows[9])))
        assertEquals(repo.key(anchor),repo.feedMemory.get(personFeed).resumeReturn?.key)
        evidence("jump-keeps-continue-point")
        ui.onNodeWithTag("Continue reading").performClick()
        waitForRow(anchor)
        ui.waitUntil(5_000){abs(top(anchor)-before)<=2f}
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(rows[9])))
        evidence("continue-reading-restored")
    }

    @Test fun onlyVisiblyRestedPostsBecomeReadAndJumpedOverRowsRemainUnread() {
        val rows=posts(22);val follows=listOf(follow())
        seed(personFeed,rows)
        show(mutableStateOf(rows),follows);waitForRow(rows.first())
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(rows.last())))
        waitForRead(rows.first())
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(rows[8])))
        ui.onNodeWithTag("Following feed").performScrollToNode(hasTestTag("Feed row ${repo.key(rows[12])}"))
        waitForRead(rows[12])
        assertFalse("Scrolling past a post must not imply reading it",FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(rows[8])))
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(rows.last())))
        evidence("actual-viewport-reading")
    }

    @Test fun aComposedVisiblePostDoesNotBecomeReadWhileTheActivityIsInBackground() {
        val rows=posts(10);seed(personFeed,rows)
        show(mutableStateOf(rows),listOf(follow()))
        ui.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        android.os.SystemClock.sleep(1_600)
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(rows.first())))
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForRead(rows.first())
        assertFalse(FeedReading.isRead(repo.feedMemory.get(personFeed),repo.key(rows.last())))
    }

    @Test fun platformAllAndPersonFeedsKeepIndependentAnchorsAndShareActualReadIdentities() {
        val x=posts(16);val blue=posts(16,Platform.BLUESKY);val follows=listOf(follow(),follow(Platform.BLUESKY))
        val xId="following:${Platform.X.id}:group:${Repository.hash("")}"
        val allId="all"
        val all=CachePolicy.chronological(x+blue)
        seed(xId,x,7,31);seed(allId,all,all.indexOf(blue[5]),47);seed(personFeed,blue,11,59)
        repo.feedMemory.markRead(xId,listOf(repo.key(x[3])))
        repo.feedMemory.markRead(allId,listOf(repo.key(x[4])))
        repo.feedMemory.markRead(personFeed,listOf(repo.key(blue[6])))
        show(mutableStateOf(x),follows.filter {it.target.platform==Platform.X},xId)
        waitForRow(x[7]);waitForRestoredAnchor(xId,x[7]);val xTop=top(x[7])
        show(mutableStateOf(all),follows,allId,grouped=true);waitForRow(blue[5]);waitForRestoredAnchor(allId,blue[5])
        assertEquals(repo.key(x[7]),repo.feedMemory.get(xId).anchorKey)
        show(mutableStateOf(blue),follows.filter {it.target.platform==Platform.BLUESKY},personFeed,grouped=true);waitForRow(blue[11]);waitForRestoredAnchor(personFeed,blue[11])
        assertEquals(repo.key(blue[5]),repo.feedMemory.get(allId).anchorKey)
        assertTrue(FeedReading.isRead(repo.feedMemory.get(xId),repo.key(x[3])))
        assertTrue("Reading the same identity in X must carry into All",FeedReading.isRead(repo.feedMemory.get(allId),repo.key(x[3])))
        assertTrue("Reading the same identity in All must carry back into X",FeedReading.isRead(repo.feedMemory.get(xId),repo.key(x[4])))
        assertFalse(FeedReading.isRead(repo.feedMemory.get(allId),repo.key(x[1])))
        assertFalse(FeedReading.isRead(repo.feedMemory.get(xId),repo.key(x[1])))
        show(mutableStateOf(x),follows.filter {it.target.platform==Platform.X},xId);waitForRow(x[7]);waitForRestoredAnchor(xId,x[7])
        ui.waitUntil(5_000){abs(top(x[7])-xTop)<=2f}
        evidence("independent-feed-scopes")
    }

    @Test fun aFailedSubscriptionStaysPendingUntilAFreshUsableRefreshSucceeds() {
        val first=follow();val second=follow(Platform.BLUESKY)
        val follows=listOf(first,second)
        val rows=posts(2)+posts(2,Platform.BLUESKY)
        runBlocking {follows.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}}
        var failBlue=true
        repo.followLoader={account->
            if(account.id==second.id&&failBlue)throw SourceFailure(HealthStatus.UNAVAILABLE,"Fixture public source is offline")
            val source=Source(account.target.platform,"native-fixture","https://feed-memory-fixture.invalid")
            val provenance=Provenance(source,System.currentTimeMillis(),source.root)
            Page(rows.filter {it.platform==account.target.platform}.map {it.copy(provenance=provenance)},provenance=provenance)
        }
        runBlocking {repo.refresh(personId=person)}
        assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[first.id]?.status)
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[second.id]?.status)
        seed(personFeed,rows)
        repo.feedMemory.markRead(personFeed,rows.map(repo::key))
        show(mutableStateOf(CachePolicy.chronological(rows)),follows,grouped=true)
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed pending subscriptions"))
        ui.onNodeWithTag("Feed pending subscriptions").assertIsDisplayed()
        ui.onNodeWithTag("Feed caught up").assertDoesNotExist()
        evidence("failed-source-stays-pending")
        failBlue=false
        runBlocking {repo.refresh(personId=person)}
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed caught up"))
        ui.onNodeWithTag("Feed caught up").assertIsDisplayed()
        ui.onNodeWithTag("Feed pending subscriptions").assertDoesNotExist()
        assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[second.id]?.status)
        evidence("fresh-sources-caught-up")
        failBlue=true
        runBlocking {repo.refresh(personId=person)}
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed pending subscriptions"))
        ui.onNodeWithTag("Feed pending subscriptions").assertIsDisplayed()
        assertNotNull("A previous success remains history, not a current success",repo.feedMemory.refresh.value[second.id]?.lastSuccessAt)
        ui.onNodeWithTag("Feed caught up").assertDoesNotExist()
    }

    @Test fun aSuccessfulNativePullAtTheNewestEdgeShowsTheExactCatchUpMessage() {
        val account=follow();val rows=posts(18);val calls=java.util.concurrent.atomic.AtomicInteger()
        val source=Source(Platform.X,"native-fixture","https://feed-memory-fixture.invalid")
        fun page()=Provenance(source,System.currentTimeMillis(),source.root).let {stamp->Page(rows.map {it.copy(provenance=stamp)},provenance=stamp)}
        runBlocking {repo.dao.putFollow(FollowRow(account.id,account.target.platform.id,repo.gson.toJson(account)));repo.store(page(),account.id,true)}
        repo.followLoader={calls.incrementAndGet();page()}
        seed(personFeed,rows)
        show(mutableStateOf(rows),listOf(account),refreshPerson=true);waitForRow(rows.first())
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Jump to newest").fetchSemanticsNodes().any {!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}}
        ui.onNodeWithText("Slop consumed, go be productive.").assertDoesNotExist()
        val before=repo.feedRefresh.value.generation
        ui.onNodeWithTag("Following feed").performScrollToIndex(12)
        ui.onNodeWithTag("Feed pull to refresh").performTouchInput {swipeDown(startY=height*.2f,endY=height*.7f,durationMillis=420)}
        ui.mainClock.advanceTimeBy(500);ui.waitForIdle()
        assertEquals("Reading upward away from the newest edge must not refresh",before,repo.feedRefresh.value.generation)
        assertEquals(0,calls.get())
        ui.onNodeWithTag("Following feed").performScrollToIndex(0);waitForRow(rows.first())
        ui.onNodeWithTag("Feed pull to refresh").performTouchInput {swipeDown(startY=height*.12f,endY=height*.85f,durationMillis=650)}
        ui.waitUntil(10_000){repo.feedRefresh.value.generation>before&&!repo.feedRefresh.value.running}
        assertEquals(1,calls.get())
        assertEquals(setOf(account.id),repo.feedRefresh.value.requested)
        assertTrue(repo.feedRefresh.value.failed.isEmpty())
        assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[account.id]?.status)
        ui.waitUntil(5_000){ui.onAllNodesWithText("Slop consumed, go be productive.").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("Slop consumed, go be productive.").assertIsDisplayed()
        evidence("native-edge-pull-no-new-catch-up-snackbar")
    }

    @Test fun aPendingSourceCannotAnnounceThatTheReadersSlopWasConsumed() {
        val x=follow();val blue=follow(Platform.BLUESKY);val accounts=listOf(x,blue)
        val rows=posts(2)+posts(2,Platform.BLUESKY)
        runBlocking {accounts.forEach {account->
            repo.dao.putFollow(FollowRow(account.id,account.target.platform.id,repo.gson.toJson(account)))
            repo.store(Page(rows.filter {it.platform==account.target.platform}),account.id,true)
        }}
        val previous=repo.feedMemory.begin(accounts.map {it.id});accounts.forEach {repo.feedMemory.success(it.id,previous,now-60_000)}
        repo.followLoader={account->
            if(account.id==blue.id)throw SourceFailure(HealthStatus.UNAVAILABLE,"Fixture source remains unavailable")
            val source=Source(account.target.platform,"native-fixture","https://feed-memory-fixture.invalid")
            val stamp=Provenance(source,System.currentTimeMillis(),source.root)
            Page(rows.filter {it.platform==account.target.platform}.map {it.copy(provenance=stamp)},provenance=stamp)
        }
        seed(personFeed,rows);repo.feedMemory.markRead(personFeed,rows.map(repo::key))
        show(mutableStateOf(CachePolicy.chronological(rows)),accounts,grouped=true,refreshPerson=true)
        val before=repo.feedRefresh.value.generation
        ui.onNodeWithTag("Feed options").performClick()
        ui.onNodeWithText("Refresh").performClick()
        ui.waitUntil(10_000){repo.feedRefresh.value.generation>before&&!repo.feedRefresh.value.running}
        assertEquals(setOf(blue.id),repo.feedRefresh.value.failed)
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[blue.id]?.status)
        assertNotNull(repo.feedMemory.refresh.value[blue.id]?.lastSuccessAt)
        ui.mainClock.advanceTimeBy(1_000);ui.waitForIdle()
        ui.onNodeWithText("Slop consumed, go be productive.").assertDoesNotExist()
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed pending subscriptions"))
        ui.onNodeWithTag("Feed pending subscriptions").assertIsDisplayed()
        evidence("pending-source-suppresses-catch-up-snackbar")
    }

    @Test fun anUnrelatedCompletedRefreshDoesNotAnnounceThisFeedsCatchUp() {
        val selected=follow();val outside=follow(label="Outside").copy(personId="$person-outside",personName="Outside fixture")
        val rows=posts(2);val other=posts(1,start=10)
        runBlocking {listOf(selected,outside).forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))};repo.store(Page(rows),selected.id,true);repo.store(Page(other),outside.id,true)}
        val token=repo.feedMemory.begin(listOf(selected.id));repo.feedMemory.success(selected.id,token,now-60_000)
        repo.followLoader={account->
            val source=Source(account.target.platform,"native-fixture","https://feed-memory-fixture.invalid");val stamp=Provenance(source,System.currentTimeMillis(),source.root)
            Page((if(account.id==outside.id)other else rows).map {it.copy(provenance=stamp)},provenance=stamp)
        }
        seed(personFeed,rows);repo.feedMemory.markRead(personFeed,rows.map(repo::key))
        show(mutableStateOf(rows),listOf(selected),refreshPerson=true);waitForRow(rows.first())
        val before=repo.feedRefresh.value.generation
        runBlocking {repo.refresh(personId=outside.personId)}
        assertTrue(repo.feedRefresh.value.generation>before);assertFalse(repo.feedRefresh.value.running)
        assertEquals(setOf(outside.id),repo.feedRefresh.value.requested)
        assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[selected.id]?.status)
        ui.mainClock.advanceTimeBy(1_000);ui.waitForIdle()
        ui.onNodeWithText("Slop consumed, go be productive.").assertDoesNotExist()
        evidence("unrelated-refresh-does-not-claim-current-feed-caught-up")
    }

    @Test fun aCancelledRefreshCannotLeaveAPreviousSuccessMarkedCaughtUp() {
        val account=follow();val rows=posts(5)
        runBlocking {repo.dao.putFollow(FollowRow(account.id,account.target.platform.id,repo.gson.toJson(account)))}
        val earlierSuccess=now-60_000
        val token=repo.feedMemory.begin(listOf(account.id))
        repo.feedMemory.success(account.id,token,earlierSuccess)
        val started=CompletableDeferred<Unit>()
        val ownedRefresh="feed-memory-cancel-$suffix"
        repo.followLoader={follow->
            if(follow.id==account.id&&currentCoroutineContext()[CoroutineName]?.name==ownedRefresh) {
                started.complete(Unit);awaitCancellation()
            }else {
                // A refresh queued by the Activity before this fixture may also
                // reach this shared seam. It neither releases our gate nor makes
                // a real source request or blocks the owned refresh's mutex.
                Page(emptyList())
            }
        }
        runBlocking {
            val pending=launch(Dispatchers.IO+CoroutineName(ownedRefresh),start=CoroutineStart.LAZY) {repo.refresh(personId=person)}
            pending.start()
            try {
                withTimeout(5_000){started.await()}
                assertTrue("The owned refresh must still be active inside its blocked source",pending.isActive)
                assertEquals(FollowRefreshStatus.CHECKING,repo.feedMemory.refresh.value[account.id]?.status)
            }finally {pending.cancelAndJoin()}
            assertTrue(pending.isCancelled)
        }
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[account.id]?.status)
        assertEquals(earlierSuccess,repo.feedMemory.refresh.value[account.id]?.lastSuccessAt)
        seed(personFeed,rows);repo.feedMemory.markRead(personFeed,rows.map(repo::key))
        show(mutableStateOf(rows),listOf(account))
        ui.onNodeWithTag("Following feed").performScrollToNode(hasTestTag("Feed pending subscriptions"))
        ui.onNodeWithTag("Feed pending subscriptions").assertIsDisplayed()
        ui.onNodeWithTag("Feed caught up").assertDoesNotExist()
        evidence("cancelled-refresh-remains-pending")
        ui.runOnIdle {ui.activity.setContent {Text("Reloading cancelled subscription state")}}
        reopenMemory()
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[account.id]?.status)
        assertEquals(earlierSuccess,repo.feedMemory.refresh.value[account.id]?.lastSuccessAt)
        assertFalse(RefreshLedger.caughtUp(listOf(account.id),repo.feedMemory.refresh.value))
    }

    @Test fun aPreviewRefreshCannotReplaceAFullSavedFeedRowButFreshPublicContentCan() {
        val account=follow();val base=posts(1).single()
        val source=Source(Platform.X,"native-fixture","https://feed-memory-fixture.invalid")
        val provenance=Provenance(source,now,source.root)
        val full=base.copy(body="Full public feed content ${base.id}",provenance=provenance)
        val preview=full.copy(body="Only an incomplete preview ${base.id}",preview=true)
        runBlocking {
            repo.dao.putFollow(FollowRow(account.id,account.target.platform.id,repo.gson.toJson(account)))
            repo.store(Page(listOf(full),provenance=provenance),account.id,true)
        }
        repo.followLoader={Page(listOf(preview),provenance=provenance)}
        runBlocking {repo.refresh(personId=person)}
        val retained=runBlocking {repo.gson.fromJson(requireNotNull(repo.dao.post(repo.key(full))).payload,Post::class.java)}
        assertEquals(full.body,retained.body);assertFalse(retained.preview)
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[account.id]?.status)
        seed(personFeed,listOf(retained))
        val data=mutableStateOf(listOf(retained))
        show(data,listOf(account));waitForRow(retained)
        ui.onNodeWithText(full.body).assertIsDisplayed()
        ui.onNodeWithText(preview.body).assertDoesNotExist()
        ui.onNodeWithTag("Following feed").performScrollToNode(hasTestTag("Feed pending subscriptions"))
        ui.onNodeWithTag("Feed pending subscriptions").assertIsDisplayed()
        evidence("preview-keeps-full-feed-content")
        val updated=full.copy(body="Fresh complete public feed content ${base.id}",provenance=provenance.copy(fetchedAt=System.currentTimeMillis()))
        repo.followLoader={Page(listOf(updated),provenance=updated.provenance)}
        runBlocking {repo.refresh(personId=person)}
        val fresh=runBlocking {repo.gson.fromJson(requireNotNull(repo.dao.post(repo.key(full))).payload,Post::class.java)}
        assertEquals(updated.body,fresh.body);assertFalse(fresh.preview)
        assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[account.id]?.status)
    }

    @Test fun aReturningReadPostInTheFirstRealBatchIsImmediatelyAvailableWithItsReadStatus() {
        val post=posts(1).single();val account=follow();val key=repo.key(post)
        seed(personFeed,listOf(post))
        repo.feedMemory.markRead(personFeed,listOf(key))
        runBlocking {repo.feedMemory.flush()}
        reopenMemory()
        val data=mutableStateOf<List<Post>>(emptyList())
        show(data,listOf(account))
        row(post).assertDoesNotExist()
        assertTrue(key in repo.feedMemory.get(personFeed).acceptedKeys)
        ui.runOnIdle {data.value=listOf(post)}
        waitForRow(post)
        ui.onNodeWithTag("Feed unread summary").assertTextEquals("0 new · 0 unread")
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        ui.onNodeWithTag("Show new posts").assertDoesNotExist()
        assertTrue(repo.feedMemory.get(personFeed).stagedKeys.isEmpty())
        assertFalse(key in repo.feedMemory.get(personFeed).freshKeys)
        ui.onNodeWithText(post.body).assertIsDisplayed()
        ui.onNodeWithTag("Feed row status $key").assertDoesNotExist()
        assertTrue(FeedReading.isRead(repo.feedMemory.get(personFeed),key))
        assertFalse(key in repo.feedMemory.get(personFeed).freshKeys)
        evidence("returning-read-post-available")
    }

    @Test fun removingOnePersonSourceDropsItsFrozenCardsWithoutLosingOtherMemberships() {
        val x=follow();val blue=follow(Platform.BLUESKY)
        val outside=follow(label="Unrelated").copy(personId="$person-outside",personName="Unrelated fixture")
        val xPost=posts(1).single();val bluePost=posts(1,Platform.BLUESKY,start=1).single()
        val shared=posts(1,start=2).single().copy(body="Shared public post retained through another subscription ${xPost.id}")
        val unrelated=posts(1,start=10).single().copy(body="Unrelated subscription content must stay outside this person ${xPost.id}")
        repo.prefs.theme="dark"
        runBlocking {
            listOf(x,blue,outside).forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}
            repo.store(Page(listOf(xPost,shared)),x.id,true)
            repo.store(Page(listOf(bluePost)),blue.id,true)
            // The same record has two real subscription memberships. Its last
            // stored follow must not erase its earlier membership in this person.
            repo.store(Page(listOf(shared,unrelated)),outside.id,true)
            val initial=withTimeout(5_000){repo.posts(null,personId=person).first {rows->rows.size==3}}
            assertEquals(setOf(repo.key(xPost),repo.key(bluePost),repo.key(shared)),initial.map(repo::key).toSet())
        }
        val vm=model()
        ui.runOnUiThread {vm.subscription(person);ui.activity.setContent {AntiSocialApp(vm)}}
        waitForRow(xPost)
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed row ${repo.key(bluePost)}"))
        row(bluePost).assertIsDisplayed();row(unrelated).assertDoesNotExist()
        ui.onNodeWithTag("Combined feed").performScrollToIndex(0)
        evidence("native-person-before-source-removal")
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasText("Source status"))
        ui.onNodeWithText("Source status").performClick()
        ui.onNodeWithTag("Feed source ${x.id}").assertExists()
        ui.onNodeWithTag("Feed source ${blue.id}").assertExists()
        ui.onNodeWithTag("Feed source ${outside.id}").assertDoesNotExist()
        evidence("native-person-source-memberships")
        ui.onNodeWithContentDescription("Close subscription checks").performClick()
        runBlocking {
            repo.dao.removeFollow(x.id)
            val remaining=withTimeout(5_000){repo.posts(null,personId=person).first {rows->rows.map(repo::key)==listOf(repo.key(bluePost))}}
            assertEquals(bluePost.body,remaining.single().body)
            val elsewhere=withTimeout(5_000){repo.posts(null,personId=outside.personId).first {rows->rows.any {repo.key(it)==repo.key(shared)}}}
            assertTrue(elsewhere.any {repo.key(it)==repo.key(shared)&&it.body==shared.body})
        }
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Combined feed").fetchSemanticsNodes().isNotEmpty()}
        // At index zero, a stale frozen copy of the removed source would be
        // visible. Waiting for its absence therefore checks native invalidation.
        ui.onNodeWithTag("Combined feed").performScrollToIndex(0)
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Feed row ${repo.key(xPost)}").fetchSemanticsNodes().isEmpty()}
        waitForRow(bluePost)
        row(xPost).assertDoesNotExist();row(shared).assertDoesNotExist();row(unrelated).assertDoesNotExist()
        ui.onNodeWithText(bluePost.body).assertIsDisplayed()
        evidence("native-person-after-source-removal")
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasText("Source status"))
        ui.onNodeWithText("Source status").performClick()
        ui.onNodeWithTag("Feed source ${blue.id}").assertExists()
        ui.onNodeWithTag("Feed source ${x.id}").assertDoesNotExist()
        ui.onNodeWithTag("Feed source ${outside.id}").assertDoesNotExist()
        ui.onNodeWithContentDescription("Close subscription checks").performClick()
    }

    @Test fun freshViewModelsRestoreAllPersonAndFollowingGroupInsteadOfAStaleTarget() {
        val legacyGroup="Legacy\n\tgroup "+"x".repeat(300)
        repo.prefs.readingGroup(Platform.X,legacyGroup)
        assertEquals(legacyGroup,repo.prefs.readingGroup(Platform.X))
        repo.prefs.validate(repo.prefs.export())
        repo.prefs.readingGroup(Platform.X,"")
        val group="Fixture group $suffix"
        val x=follow().copy(group=group);val blue=follow(Platform.BLUESKY)
        val xRows=posts(12);val blueRows=posts(12,Platform.BLUESKY)
        val ordered=CachePolicy.chronological(xRows+blueRows)
        val allAnchor=xRows[4];val personAnchor=blueRows[8];val groupAnchor=xRows[7]
        val following="following:${Platform.X.id}:group:${Repository.hash("")}"
        val groupFeed="following:${Platform.X.id}:group:${Repository.hash(group)}"
        // Fresh models honor selected platforms. Seed both fixture platforms
        // rather than inheriting this isolated device's previous selection.
        repo.prefs.selected=listOf(Platform.X,Platform.BLUESKY)
        repo.prefs.activePlatform=Platform.X;repo.prefs.theme="dark";repo.prefs.readingGroup(Platform.X,"")
        runBlocking {
            listOf(x,blue).forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}
            repo.store(Page(xRows),x.id,true);repo.store(Page(blueRows),blue.id,true)
        }
        // Only fixture cards are accepted. Other existing subscriptions remain
        // staged in All, so this native test cannot fetch their artwork.
        seed("all",ordered,ordered.indexOf(allAnchor),37)
        seed(personFeed,ordered,ordered.indexOf(personAnchor),61)
        seed(following,xRows,4,37);seed(groupFeed,xRows,7,29)
        val stale=xRows.last();val direct=repo.directLoader
        val targetOpens=java.util.concurrent.atomic.AtomicInteger()
        repo.directLoader={_,_->targetOpens.incrementAndGet();Page(listOf(stale))}
        fun hide(vm: AntiSocialViewModel) {
            ui.runOnIdle {ui.activity.setContent {Text("Starting a fresh reader")};vm.discussions.close();vm.viewModelScope.cancel()}
            runBlocking {repo.feedMemory.flush()}
        }
        fun fresh(view: String): AntiSocialViewModel {
            lateinit var vm: AntiSocialViewModel
            ui.runOnUiThread {
                vm=AntiSocialViewModel.Factory(repo).create(AntiSocialViewModel::class.java);models+=vm
                val initial=vm.screen.value
                assertNull(initial.target)
                when(view) {
                    "all"->{assertEquals("feed",initial.section);assertTrue(initial.combined);assertNull(initial.subscriptionId)}
                    "person"->{assertEquals("subscription",initial.section);assertTrue(initial.combined);assertEquals(person,initial.subscriptionId)}
                    else->{assertEquals("feed",initial.section);assertFalse(initial.combined);assertEquals(Platform.X,initial.platform)}
                }
                ui.activity.setContent {AntiSocialApp(vm)}
            }
            return vm
        }
        try {
            val first=model()
            ui.runOnUiThread {first.combined();repo.prefs.lastUrl(Platform.X,stale.canonicalUrl);ui.activity.setContent {AntiSocialApp(first)}}
            waitForRow(allAnchor)
            val allTop=top(allAnchor)
            assertEquals("all",repo.prefs.readingView)
            hide(first)
            val all=fresh("all")
            waitForRow(allAnchor);ui.waitUntil(5_000){abs(top(allAnchor)-allTop)<=2f}
            assertEquals(stale.canonicalUrl,repo.prefs.lastUrl(Platform.X))
            assertEquals(0,targetOpens.get())
            evidence("native-all-view-restored-after-new-model")
            ui.runOnIdle {all.subscription(person)}
            waitForRow(personAnchor)
            val personTop=top(personAnchor)
            assertEquals("person",repo.prefs.readingView);assertEquals(person,repo.prefs.readingPerson)
            hide(all)
            val personReader=fresh("person")
            waitForRow(personAnchor);ui.waitUntil(5_000){abs(top(personAnchor)-personTop)<=2f}
            assertEquals(0,targetOpens.get());assertEquals(person,personReader.screen.value.subscriptionId)
            evidence("native-person-view-restored-after-new-model")
            ui.runOnIdle {personReader.following()}
            // Open the menu after the requested reader has been composed.
            ui.waitUntil(5_000){ui.onAllNodesWithTag("Following feed").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("Feed options").performClick()
            ui.waitUntil(5_000){ui.onAllNodesWithText(group).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(group).performScrollTo().performClick()
            waitForRow(groupAnchor)
            val groupTop=top(groupAnchor)
            assertEquals(group,repo.prefs.readingGroup(Platform.X));assertEquals("following",repo.prefs.readingView)
            repo.prefs.lastUrl(Platform.X,stale.canonicalUrl)
            hide(personReader)
            val grouped=fresh("following")
            ui.waitUntil(5_000){ui.onAllNodesWithTag("Following feed").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("Feed options").performClick()
            ui.waitUntil(5_000){ui.onAllNodesWithText(group).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(group).assertIsSelected()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitForRow(groupAnchor);ui.waitUntil(5_000){abs(top(groupAnchor)-groupTop)<=2f}
            assertNull(grouped.screen.value.target);assertEquals(0,targetOpens.get())
            evidence("native-following-group-restored-after-new-model")
        }catch(e:Throwable) {
            runCatching {evidence("native-reading-scope-transition-failure")}
            throw e
        }finally {
            models.forEach {it.viewModelScope.cancel()}
            repo.directLoader=direct
            // This target can only have been cached if the stale-navigation
            // regression occurred; remove only that fixture's page record.
            repo.routes.recognize(stale.canonicalUrl,Platform.X)?.let {target->runBlocking {
                val key=repo.pageKey(target);repo.dao.media(key)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(key))
            }}
        }
    }
}
