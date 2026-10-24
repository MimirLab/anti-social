package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SubscriptionsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val handle="antisocial_native_subscription_fixture"
    private val body="Our latest public project update includes substantial detail about what we built and how the next release will work."
    private lateinit var preferences: String
    private var custom: AntiSocialViewModel?=null
    private fun model()=custom ?: ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private fun target(p: Platform)=repo.routes.target(p,handle)
    private fun post(p: Platform,id: String="77001",time: Long?=System.currentTimeMillis()-60_000): Post {
        val url=when(p){Platform.X->"https://x.com/$handle/status/$id";Platform.BLUESKY->"https://bsky.app/profile/$handle/post/$id";Platform.REDDIT->"https://www.reddit.com/r/test/comments/$id/fixture";else->error("fixture")}
        return Post(ContentIdentity.key(p,url),p,url,handle,body,publishedAt=time)
    }
    @Before fun preserve() {
        runBlocking {repo.awaitReady()};preferences=repo.prefs.export().toString()
        repo.prefs.communityComments=false;repo.prefs.intervalMinutes=0
        ui.runOnIdle {model().following()}
    }
    @After fun restore() {
        ui.runOnIdle {
            custom?.let {it.discussions.close();it.viewModelScope.cancel()};custom=null
            model().discussions.close();model().following()
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        runBlocking {
            repo.dao.allFollows().filter {it.payload.contains(handle)}.forEach {repo.dao.removeFollow(it.id)}
            val keys=repo.dao.allPosts().filter {it.payload.contains(handle)}.map {it.key}
            if(keys.isNotEmpty())repo.dao.removePosts(keys)
            for(table in listOf("history","seen"))for(key in keys)repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
            assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
        }
    }
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(250)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"subscriptions").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun waitForCandidate(target:Target,ready:()->Boolean={true}) {
        try {
            ui.waitForIdle()
            ui.waitUntil(10_000){
                // The initial seed resumes after Default-dispatcher URL work;
                // advance the controlled UI clock while that work completes.
                ui.mainClock.advanceTimeByFrame()
                ready()&&ui.onAllNodesWithTag("Account candidate ${target.canonicalUrl}").fetchSemanticsNodes().isNotEmpty()
            }
        }catch(failure:Throwable) {
            val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"subscriptions").apply {mkdirs()}
            runCatching {File(dir,"candidate-setup-failure.txt").writeText(ui.onAllNodes(isRoot(),useUnmergedTree=true).printToString())}
            File(dir,"candidate-setup-threads.txt").writeText("UI clock=${ui.mainClock.currentTime}; autoAdvance=${ui.mainClock.autoAdvance}\n"+
                Thread.getAllStackTraces().entries.joinToString("\n\n"){(thread,frames)->thread.name+" "+thread.state+"\n"+frames.joinToString("\n")})
            runCatching {evidence("candidate-setup-failure")}
            throw failure
        }
    }
    private fun show(read: suspend (Post,Page?,ReadingOrder)->Page): AntiSocialViewModel {
        val vm=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope,read=read)})
        custom=vm;ui.runOnUiThread {ui.activity.setContent {AntiSocialApp(vm)};vm.combined()};return vm
    }
    @Test fun platformTabsUseAccessibleLargeLogosAndIncludeACombinedFeed() {
        val vm=model();ui.runOnIdle {vm.platform(Platform.X,false);vm.following()}
        ui.onNodeWithTag("Platform tab twitter").assertIsSelected()
        ui.onNodeWithTag("Platform tab twitter").assert(hasContentDescription("X"))
        // Attribution may say X inside a post; the tab itself stays logo-only.
        ui.onNode(hasText("X") and hasAnyAncestor(hasTestTag("Platform tab twitter")),useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithTag("Platform tab all").performScrollTo().performClick()
        ui.onNodeWithTag("Combined feed").assertIsDisplayed();ui.onNodeWithText("All platforms").assertIsDisplayed()
        ui.onNodeWithTag("Platform tab all").assertIsSelected();evidence("logo-tabs-combined")
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.runOnIdle {vm.settings(SettingsPage.PLATFORMS)}
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle twitter"))
        ui.onNodeWithTag("Platform toggle twitter").performClick()
        ui.onNodeWithContentDescription("Back").performClick();ui.onNodeWithContentDescription("Back").performClick()
        assertTrue(vm.screen.value.combined);assertFalse(Platform.X in repo.prefs.selected)
        ui.onNodeWithTag("Platform tab reddit").performScrollTo().performClick()
        assertEquals(Platform.REDDIT,vm.screen.value.platform);assertFalse(vm.screen.value.combined)
    }
    @Test fun linkedSubscriptionPreservesExistingSourceSettingsAndGroupsMatchingPosts() {
        val x=target(Platform.X);val b=target(Platform.BLUESKY);val posts=listOf(post(Platform.X),post(Platform.BLUESKY))
        val id=runBlocking {
            repo.follow(Platform.X,x.canonicalUrl,"Projects","https://example.org/custom-feed")
            repo.follow(Platform.BLUESKY,b.canonicalUrl)
            repo.saveSubscription("anti social native fixture",listOf(x,b))
            val follows=repo.follows.first().filter {it.target.identity.contains(handle)}
            assertEquals(1,Subscriptions.bundles(follows).size)
            assertEquals("Projects",follows.first {it.target.platform==Platform.X}.group)
            assertEquals("https://example.org/custom-feed",follows.first {it.target.platform==Platform.X}.feedUrl)
            follows.forEach {f->repo.store(Page(posts.filter {it.platform==f.target.platform}),f.id,true)}
            Subscriptions.id(follows.first())
        }
        ui.runOnIdle {model().section("follows")}
        ui.onNodeWithTag("Subscriptions list").performScrollToNode(hasText("anti social native fixture"))
        ui.onNodeWithText("anti social native fixture").performClick()
        ui.onNodeWithTag("Combined feed").assertIsDisplayed()
        ui.waitUntil(10_000){ui.onAllNodesWithText("2 platforms · matching post").fetchSemanticsNodes().size==1}
        assertEquals(id,model().screen.value.subscriptionId)
        ui.onAllNodesWithText(body).assertCountEquals(1)
        ui.onNodeWithText("Discussions").assertIsDisplayed();evidence("person-feed-grouped")
        ui.onNodeWithContentDescription("Back").performClick();assertEquals("follows",model().screen.value.section)
    }
    @Test fun accountCaseAliasesReuseTheExistingSourceAndItsCustomFeedSettings()=runBlocking {
        val upper=repo.routes.target(Platform.X,"https://x.com/ANTI_ALIAS")
        val lower=repo.routes.target(Platform.X,"anti_alias")
        assertTrue(AccountLinkage.valid(upper));assertTrue(AccountLinkage.valid(lower))
        val original=Follow("alias:$handle",upper,"$handle alias","Projects",feedUrl="https://example.org/$handle/feed")
        repo.dao.putFollow(FollowRow(original.id,upper.platform.id,repo.gson.toJson(original)))
        repo.saveSubscription("Alias fixture $handle",listOf(lower))
        val matching=repo.dao.allFollows().map {repo.gson.fromJson(it.payload,Follow::class.java)}.filter {AccountLinkage.same(it.target,lower,repo.routes)}
        assertEquals(1,matching.size);assertEquals(upper,matching.single().target)
        assertEquals(original.id,matching.single().id)
        assertEquals("Projects",matching.single().group);assertEquals("https://example.org/$handle/feed",matching.single().feedUrl)
        assertNotNull(matching.single().personId)
    }
    @Test fun subscribingDoesNotWaitForAStalledDiscoveryAndCancelsIt() {
        val seed=target(Platform.X);val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val vm=AntiSocialViewModel(repo);custom=vm
        ui.runOnUiThread {repo.tor.enable(false);repo.gateway.configure(NetworkPolicy(tor=true));ui.activity.setContent {
            var visible by remember {mutableStateOf(true)}
            MaterialTheme {if(visible)SubscriptionDialog(vm,Platform.X,autoDiscover=true,initialTarget=seed,
                discoverAccounts={started.complete(Unit);try {awaitCancellation()}finally {cancelled.complete(Unit)}},onDismiss={visible=false})}
        }}
        waitForCandidate(seed){started.isCompleted}
        ui.onNodeWithTag("Save subscription").assertIsEnabled()
        ui.onNodeWithTag("Account candidate ${seed.canonicalUrl}").assertIsOn()
        evidence("discovery-seed-ready-while-sources-load")
        ui.onNodeWithTag("Save subscription").performClick()
        ui.waitUntil(10_000){cancelled.isCompleted&&runBlocking {repo.dao.allFollows().any {it.payload.contains(handle)}}}
        ui.onNodeWithTag("Subscription dialog").assertDoesNotExist()
    }
    @Test fun delayedSuggestionsKeepTheReadersEditedNameAndCheckboxChoice() {
        val seed=target(Platform.X);val elsewhere=target(Platform.BLUESKY);val release=CompletableDeferred<Unit>()
        val vm=AntiSocialViewModel(repo);custom=vm
        ui.runOnUiThread {ui.activity.setContent {
            var visible by remember {mutableStateOf(true)}
            MaterialTheme {if(visible)SubscriptionDialog(vm,Platform.X,autoDiscover=true,initialTarget=seed,
                discoverAccounts={release.await();AccountDiscovery("Fetched name",listOf(AccountCandidate(seed,"Account you opened"),AccountCandidate(elsewhere,"Linked from profile")))},onDismiss={visible=false})}
        }}
        waitForCandidate(seed)
        ui.onNodeWithTag("Subscription name").performTextReplacement("My workshop")
        ui.onNodeWithTag("Account candidate ${seed.canonicalUrl}").performClick().assertIsOff()
        release.complete(Unit)
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Account candidate ${elsewhere.canonicalUrl}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Subscription name").assertTextContains("My workshop")
        ui.onNodeWithTag("Account candidate ${seed.canonicalUrl}").assertIsOff()
        ui.onNodeWithTag("Account candidate ${elsewhere.canonicalUrl}").assertIsOff()
        ui.onNodeWithTag("Account candidate ${elsewhere.canonicalUrl}").performClick().assertIsOn()
        evidence("discovery-late-suggestions-preserve-reader-choices")
    }
    @Test fun discoveryReviewRequiresChosenAccountsAndRetainsTheSeed() {
        val seed=target(Platform.X);val elsewhere=target(Platform.BLUESKY)
        val vm=AntiSocialViewModel(repo);custom=vm
        ui.runOnUiThread {repo.tor.enable(false);repo.gateway.configure(NetworkPolicy(tor=true));ui.activity.setContent {
            var visible by remember {mutableStateOf(true)}
            MaterialTheme {if(visible)SubscriptionDialog(vm,Platform.X,discoverAccounts={AccountDiscovery("anti social native fixture",listOf(AccountCandidate(seed,"Account you entered"),AccountCandidate(elsewhere,"Linked from profile")))},onDismiss={visible=false})}
        }}
        ui.onNodeWithTag("Subscription account").performTextInput(seed.canonicalUrl)
        ui.onNodeWithTag("Save subscription").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Account candidate ${seed.canonicalUrl}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Account candidate ${seed.canonicalUrl}").assertIsOn()
        ui.onNodeWithTag("Account candidate ${elsewhere.canonicalUrl}").assertIsOff().performClick().assertIsOn()
        evidence("discovery-review")
        ui.onNodeWithTag("Save subscription").performClick()
        ui.waitUntil(10_000){runBlocking {repo.dao.allFollows().count {it.payload.contains(handle)}==2}}
        val accounts=runBlocking {repo.follows.first()}.filter {it.target.identity.contains(handle)}
        assertEquals(1,Subscriptions.bundles(accounts).size);assertEquals("anti social native fixture",Subscriptions.bundles(accounts).single().name)
    }
    @Test fun discussionsLoadOnlySelectedPlatformsThenAllAndSortWithoutMixingOrRecommendations() {
        val roots=listOf(post(Platform.REDDIT),post(Platform.BLUESKY))
        val calls=CopyOnWriteArrayList<String>()
        val pending=CompletableDeferred<Unit>()
        fun reply(root: Post,id: String,text: String,time: Long)=root.copy(id="reply:$id",canonicalUrl=if(root.platform==Platform.REDDIT)root.canonicalUrl+"/$id"else root.canonicalUrl.replace("77001",id),body=text,publishedAt=time,parentId=root.id,score=if(id=="77002")100L else 5L)
        val now=System.currentTimeMillis()
        val vm=show {root,_,order->
            calls+="${root.platform.id}:${order.name}"
            if(root.platform==Platform.BLUESKY)pending.await()
            val old=reply(root,"77002","Older popular reply",now-20_000);val new=reply(root,"77003","Newest public reply",now-10_000)
            // A recommendation is a different post. Appending a Reddit path
            // segment would instead construct a genuine permalink to a comment
            // on this root, which may legitimately lack parent metadata.
            Page(listOf(root,old,new,root.copy(id="unrelated",canonicalUrl=root.canonicalUrl.replace("77001","88001"),body="Recommendation must stay out")))
        }
        ui.runOnIdle {vm.discussions.open(roots,Platform.REDDIT.id)}
        ui.waitUntil(10_000){vm.discussions.state.value?.threads?.get("reddit")?.page!=null}
        assertEquals(listOf("reddit:CHRONOLOGICAL"),calls.toList())
        ui.onNodeWithTag("Discussion order CHRONOLOGICAL").assertIsDisplayed().assertTextEquals("Newest")
        ui.onNodeWithTag("Discussion order SLOP").assertIsDisplayed().assertTextEquals("Slop")
        ui.onNodeWithText("Recommendation must stay out").assertDoesNotExist()
        val newest=ui.onNodeWithText("Newest public reply").fetchSemanticsNode().boundsInRoot.top
        val old=ui.onNodeWithText("Older popular reply").fetchSemanticsNode().boundsInRoot.top
        assertTrue(newest<old);evidence("comments-newest")
        ui.onNodeWithTag("Discussion order SLOP").performClick()
        ui.waitUntil(10_000){calls.contains("reddit:SLOP")&&vm.discussions.state.value?.threads?.get("reddit")?.page!=null}
        assertTrue(ui.onNodeWithText("Older popular reply").fetchSemanticsNode().boundsInRoot.top<ui.onNodeWithText("Newest public reply").fetchSemanticsNode().boundsInRoot.top)
        ui.onNodeWithTag("Discussion tab all").performScrollTo().performClick()
        ui.waitUntil(10_000){calls.contains("bluesky:SLOP")}
        assertTrue(vm.discussions.state.value!!.threads.getValue("bluesky").loading)
        pending.complete(Unit)
        ui.waitUntil(10_000){vm.discussions.state.value?.threads?.get("bluesky")?.page!=null}
        assertEquals(4,vm.discussions.state.value!!.threads.values.sumOf {Discussions.comments(it.post,it.page!!).size})
        evidence("comments-all-slop")
        ui.onNodeWithContentDescription("Close discussions").performClick();assertNull(vm.discussions.state.value)
    }
    @Test fun discussionCloseCancelsWorkAndAllUsesAtMostTwoReads() {
        val roots=listOf(post(Platform.REDDIT),post(Platform.BLUESKY),post(Platform.X));val active=AtomicInteger();val peak=AtomicInteger();val canceled=AtomicInteger()
        val vm=show {_,_,_->val count=active.incrementAndGet();peak.updateAndGet {maxOf(it,count)};try {awaitCancellation()}finally {active.decrementAndGet();canceled.incrementAndGet()}}
        ui.runOnIdle {vm.discussions.open(roots,"all")}
        ui.waitUntil(10_000){active.get()==2}
        ui.runOnIdle {vm.discussions.close()}
        ui.waitUntil(10_000){active.get()==0}
        assertEquals("At most two reads may run",2,peak.get());assertEquals("A queued read must not start after closing",2,canceled.get());assertNull(vm.discussions.state.value)
    }
    @Test fun unavailableDiscussionStaysHonestAndRetryCanRecover() {
        val attempts=AtomicInteger();val root=post(Platform.BLUESKY)
        val vm=show {_,_,_->if(attempts.incrementAndGet()==1)throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"Source requires a login")else Page(listOf(root))}
        ui.runOnIdle {vm.discussions.open(listOf(root),root.platform.id)}
        ui.waitUntil(10_000){vm.discussions.state.value?.threads?.get("bluesky")?.error!=null}
        ui.onNodeWithText("Source requires a login").assertIsDisplayed();evidence("comments-unavailable")
        ui.onNodeWithContentDescription("Refresh Bluesky comments").performClick()
        ui.waitUntil(10_000){vm.discussions.state.value?.threads?.get("bluesky")?.page!=null}
        ui.onNodeWithText("No public replies were supplied. This does not confirm that the post has no comments.").assertIsDisplayed()
    }
}
