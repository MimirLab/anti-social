/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Native destination shelves and real streamed post cards; public requests fail closed. */
@RunWith(AndroidJUnit4::class)
class SearchDiscoveryUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(9)
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var searchPolicy: NetworkPolicy
    private val models=mutableListOf<AntiSocialViewModel>()
    private val targets=mutableSetOf<Target>()
    private val posts=mutableListOf<Post>()

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();searchPolicy=repo.gateway.currentPolicy(NetworkRoute.TOR)
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;scheduleRefresh(ui.activity,repo.prefs)
        ui.runOnIdle {ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].following()}
        repo.gateway.configure(NetworkPolicy(tor=true))
    }
    @After fun restore() {
        ui.runOnIdle {
            models.forEach {it.discussions.close();it.viewModelScope.cancel()}
            ui.activity.setContent {MaterialTheme {Text("Search discovery fixture complete")}}
        }
        runBlocking {
            models.forEach {it.viewModelScope.coroutineContext[Job]?.join()}
            for(target in targets) {
                val key=repo.pageKey(target)
                repo.dao.media(key)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(key))
            }
            val keys=posts.map(repo::key).distinct();repo.dao.removePosts(keys)
            for(table in listOf("history","seen"))for(key in keys)
                repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
            repo.gateway.configure(policy);repo.gateway.configureTor(searchPolicy)
        }
    }
    private fun profile(platform: Platform,url: String,name: String): Profile {
        val target=requireNotNull(repo.routes.recognize(url,platform)).also {targets+=it}
        return Profile(target,name,"Actual public description for $name.")
    }
    private fun post(platform: Platform,index: Int): Post {
        val number="97$suffix${1000+index}"
        val url=when(platform) {
            Platform.REDDIT->"https://www.reddit.com/r/nativefixture/comments/p$suffix$index/native_discovery_post/"
            Platform.FACEBOOK->"https://www.facebook.com/100000000000001/posts/$number"
            Platform.LEMMY->"https://lemmy.world/post/$number"
            Platform.TELEGRAM->"https://t.me/nativeshelf$suffix/$number"
            else->error("Unsupported local fixture")
        }
        return Post("discovery-$suffix-$index",platform,url,"Public fixture author","${platform.label} streamed post $index\nThis extracted post remains beside the public source shelf.",publishedAt=1_760_000_000_000L-index*60_000L).also {posts+=it}
    }
    private fun show(platform: Platform,accounts: List<Profile>,initialPosts: List<Post> = emptyList(),pendingPosts: List<Post> = emptyList(),read: suspend (Target)->Page={error("Direct fixture destinations need no hydration")}): AntiSocialViewModel {
        val query=repo.routes.search(platform,"public discovery fixture $suffix").also {targets+=it}
        val candidates=pendingPosts.map {post->Collection(requireNotNull(repo.routes.recognize(post.canonicalUrl,platform)),"Indexed title",detail="An index snippet must not appear as a post")}
        val vm=AntiSocialViewModel(repo,createSearch={target->SearchFeed(target,discover={Page(initialPosts,accounts=accounts,collections=candidates)},read=read)}).also {models+=it}
        host(vm,query)
        ui.waitUntil(10_000){vm.screen.value.page?.accounts?.size==accounts.size}
        ui.onNodeWithTag("Search feed").performScrollToNode(hasTestTag("Search people"))
        return vm
    }
    private fun host(vm: AntiSocialViewModel,query: Target) {
        targets+=query
        ui.runOnUiThread {vm.following();ui.activity.setContent {MaterialTheme {SearchFeedScreen(vm,emptySet())}};vm.open(query,true)}
    }
    private fun reveal(profile: Profile) {
        // Real horizontal gestures exercise the shelf rather than jumping the row's index.
        val shelf=ui.onNodeWithTag("Search people")
        val matcher=hasTestTag("Search account ${profile.target.identity}")
        val density=ui.activity.resources.displayMetrics.density
        fun fullyVisible(): Boolean {
            if(ui.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()==null)return false
            val viewport=shelf.fetchSemanticsNode().boundsInRoot
            // boundsInRoot clips cards to the viewport; a six-pixel sliver is
            // therefore inside it too. Compare the actual full card in dp.
            val bounds=ui.onNode(matcher).getUnclippedBoundsInRoot()
            return bounds.right.value-bounds.left.value>=271f&&
                bounds.left.value>=viewport.left/density-1&&bounds.right.value<=viewport.right/density+1&&
                bounds.top.value>=viewport.top/density-1&&bounds.bottom.value<=viewport.bottom/density+1&&
                bounds.bottom.value>bounds.top.value
        }
        repeat(6) {
            if(fullyVisible())return
            val bounds=ui.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()?.let {ui.onNode(matcher).getUnclippedBoundsInRoot()}
            val viewport=shelf.fetchSemanticsNode().boundsInRoot
            if(bounds!=null&&bounds.left.value<viewport.left/density-1)shelf.performTouchInput {swipeRight(durationMillis=420)}
            else shelf.performTouchInput {swipeLeft(durationMillis=420)}
            ui.waitForIdle()
        }
        assertTrue("The last public destination must be reachable by horizontal swiping",fullyVisible())
    }
    private fun open(vm: AntiSocialViewModel,profile: Profile) {
        ui.onNodeWithTag("Search account ${profile.target.identity}").performClick()
        ui.runOnIdle {
            assertEquals(profile.target,vm.screen.value.target)
            assertEquals(profile.target.kind,vm.screen.value.target!!.kind)
            assertEquals(profile.target.canonicalUrl,vm.screen.value.target!!.canonicalUrl)
        }
    }
    private fun evidence(name: String) {
        ui.waitForIdle()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"search-discovery").apply {mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        File(dir,"$name-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
    }
    private fun failureEvidence(name: String,vm: AntiSocialViewModel,reads: List<String>) {
        runCatching {
            val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"search-discovery").apply {mkdirs()}
            val state=vm.screen.value
            File(dir,"$name.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("target" to state.target,"loading" to state.loading,"error" to state.error,"feed" to state.searchFeed,"requestedUrls" to reads.toList())))
            evidence(name)
        }.onFailure {error->android.util.Log.w("AntiSocialSearchDiscovery","Search shelf failure capture was incomplete",error)}
    }

    @Test fun redditSubredditsAndUsersStayAlongsideStreamedPostsAndOpenTheChosenCommunity() {
        val accounts=listOf(
            profile(Platform.REDDIT,"https://www.reddit.com/user/nativeuser$suffix/","Native public writer"),
            profile(Platform.REDDIT,"https://www.reddit.com/r/nativefirst$suffix/","First public community"),
            profile(Platform.REDDIT,"https://www.reddit.com/r/nativesecond$suffix/","Second public community"),
            profile(Platform.REDDIT,"https://www.reddit.com/r/nativelast$suffix/","Last public community"),
        )
        val first=post(Platform.REDDIT,1);val second=post(Platform.REDDIT,2)
        val firstPage=CompletableDeferred<Page>();val secondPage=CompletableDeferred<Page>()
        val reads=CopyOnWriteArrayList<String>()
        val vm=show(Platform.REDDIT,accounts,pendingPosts=listOf(first,second)) {target->
            reads+=target.canonicalUrl
            if(ContentIdentity.key(target.platform,target.canonicalUrl)==ContentIdentity.key(first.platform,first.canonicalUrl))firstPage.await()else secondPage.await()
        }
        try {
            ui.onNodeWithText("Subreddits & users").assertIsDisplayed()
            ui.onNodeWithText("Native public writer").assertIsDisplayed()
            ui.onNodeWithText("u/nativeuser$suffix").assertDoesNotExist()
            ui.onNodeWithText("Reddit · User").assertIsDisplayed()
            ui.onNodeWithText("@user/nativeuser$suffix").assertDoesNotExist()
            assertTrue(vm.screen.value.loading)
            firstPage.complete(Page(listOf(first)))
            ui.waitUntil(10_000){vm.screen.value.page?.posts?.size==1}
            assertTrue("A second post remains pending while the first real card is usable",vm.screen.value.loading)
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(first.body))
            ui.onNodeWithText(first.body).assertIsDisplayed()
            ui.onNodeWithText("An index snippet must not appear as a post").assertDoesNotExist()
            ui.onNodeWithTag("Search feed").performScrollToNode(hasTestTag("Search people"))
            reveal(accounts.last())
            ui.onNodeWithText("r/nativelast$suffix").assertIsDisplayed()
            ui.onNodeWithText("Last public community").assertIsDisplayed()
            assertEquals(TargetKind.COMMUNITY,accounts.last().target.kind)
            evidence("reddit-mixed-shelf-with-streamed-post")
            open(vm,accounts.last())
        }catch(error: Throwable) {
            failureEvidence("reddit-streamed-shelf-failure",vm,reads)
            throw error
        }finally {firstPage.complete(Page(emptyList()));secondPage.complete(Page(emptyList()))}
    }
    @Test fun facebookKeepsPageNamesAndAuthenticAddressesWithoutInventingNumericHandles() {
        val accounts=listOf(
            profile(Platform.FACEBOOK,"https://www.facebook.com/10000$suffix","Sampleton Organic Growers"),
            profile(Platform.FACEBOOK,"https://www.facebook.com/nativefirst$suffix","First local page"),
            profile(Platform.FACEBOOK,"https://www.facebook.com/nativelast$suffix","Last local page"),
        )
        val item=post(Platform.FACEBOOK,1)
        val vm=show(Platform.FACEBOOK,accounts,initialPosts=listOf(item))
        ui.onNodeWithText("Pages & people").assertIsDisplayed()
        ui.onNodeWithText("Sampleton Organic Growers").assertIsDisplayed()
        ui.onNodeWithText("facebook.com/10000$suffix").assertIsDisplayed()
        ui.onNodeWithText("@10000$suffix").assertDoesNotExist()
        ui.onNodeWithTag("Search feed").performScrollToNode(hasText(item.body));ui.onNodeWithText(item.body).assertIsDisplayed()
        ui.onNodeWithTag("Search feed").performScrollToNode(hasTestTag("Search people"))
        reveal(accounts.last());ui.onNodeWithText("facebook.com/nativelast$suffix").assertIsDisplayed()
        evidence("facebook-pages-and-people")
        open(vm,accounts.last())
    }
    @Test fun lemmyCommunitiesKeepTheirInstanceAndCommunityTarget() {
        val accounts=listOf(
            profile(Platform.LEMMY,"https://lemmy.world/c/organic@lemmy.ml","Remote organic community"),
            profile(Platform.LEMMY,"https://lemmy.world/u/nativeuser$suffix","Public Lemmy writer"),
            profile(Platform.LEMMY,"https://lemmy.world/c/localorganic$suffix","Local organic community"),
        )
        val vm=show(Platform.LEMMY,accounts)
        ui.onNodeWithText("Communities & people").assertIsDisplayed()
        ui.onNodeWithText("organic · lemmy.ml").assertIsDisplayed()
        ui.onNodeWithText("c/organic@lemmy.ml@lemmy.world").assertDoesNotExist()
        reveal(accounts.last())
        ui.onNodeWithText("localorganic$suffix · lemmy.world").assertIsDisplayed()
        assertEquals(TargetKind.COMMUNITY,accounts.last().target.kind)
        evidence("lemmy-communities-and-people")
        open(vm,accounts.last())
    }
    @Test fun telegramCallsItsShelfChannelsAndOpensTheSelectedChannel() {
        val accounts=(1..3).map {index->profile(Platform.TELEGRAM,"https://t.me/nativeshelf${suffix}_$index","Public channel $index")}
        val vm=show(Platform.TELEGRAM,accounts)
        ui.onNodeWithText("Channels").assertIsDisplayed()
        ui.onAllNodesWithText("Telegram · Channel").onFirst().assertIsDisplayed()
        ui.onNodeWithText("Public channel 1").assertIsDisplayed()
        ui.onNodeWithText("@nativeshelf${suffix}_1").assertDoesNotExist()
        reveal(accounts.last());ui.onNodeWithText("Public channel 3").assertIsDisplayed()
        evidence("telegram-channels")
        open(vm,accounts.last())
    }
    @Test fun aSlowDirectoryDoesNotBlockPostPagingAndJoinsWithoutLosingLoadedPosts() {
        val directoryStarted=CompletableDeferred<Unit>();val directory=CompletableDeferred<Page>();val discoveries=AtomicInteger()
        val initial=(1..16).map {post(Platform.REDDIT,it)}
        val next=listOf(post(Platform.REDDIT,17),post(Platform.REDDIT,18))
        val community=profile(Platform.REDDIT,"https://www.reddit.com/r/laterorganic$suffix/","Later public organic community")
        val query=repo.routes.search(Platform.REDDIT,"independent directory fixture $suffix")
        val vm=AntiSocialViewModel(repo,createSearch={target->SearchFeed(target,discover={previous->
            discoveries.incrementAndGet()
            if(previous==null)Page(initial,next="posts-independent-of-directory")else {
                assertEquals("posts-independent-of-directory",previous.next)
                Page(next)
            }
        },read={error("Direct post fixtures need no hydration")},directory={directoryStarted.complete(Unit);directory.await()})}).also {models+=it}
        try {
            host(vm,query)
            ui.waitUntil(10_000){directoryStarted.isCompleted&&!vm.screen.value.loading&&vm.screen.value.page?.posts?.size==16}
            assertEquals(1,discoveries.get())
            assertTrue(vm.screen.value.page!!.accounts.isEmpty())
            assertFalse(directory.isCompleted)
            ui.onNodeWithTag("Search feed").performScrollToIndex(15)
            ui.waitUntil(10_000){!vm.screen.value.loading&&vm.screen.value.page?.posts?.size==18}
            assertEquals("The post continuation must complete while the directory is still blocked",2,discoveries.get())
            assertFalse(directory.isCompleted)
            assertTrue(vm.screen.value.page!!.accounts.isEmpty())
            directory.complete(Page(emptyList(),accounts=listOf(community)))
            ui.waitUntil(10_000){vm.screen.value.page?.accounts?.singleOrNull()?.target==community.target}
            assertEquals((initial+next).map(repo::key).toSet(),vm.screen.value.page!!.posts.map(repo::key).toSet())
            assertEquals(18,vm.screen.value.page!!.posts.size)
            assertFalse(vm.screen.value.loading)
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(community.name))
            ui.onNodeWithText(community.name).assertIsDisplayed()
            ui.onNodeWithText("r/laterorganic$suffix").assertIsDisplayed()
            evidence("independent-directory-after-post-continuation")
        }finally {directory.complete(Page(emptyList()))}
    }
    @Test fun replacingAQueryCancelsItsDirectoryAndRejectsLateOldShelfData() {
        val oldQuery=repo.routes.search(Platform.REDDIT,"old directory fixture $suffix")
        val newQuery=repo.routes.search(Platform.REDDIT,"replacement directory fixture $suffix").also {targets+=it}
        val oldPost=post(Platform.REDDIT,101);val newPost=post(Platform.REDDIT,102)
        val oldAccount=profile(Platform.REDDIT,"https://www.reddit.com/user/oldwriter$suffix/","Old directory writer")
        val newAccount=profile(Platform.REDDIT,"https://www.reddit.com/user/newwriter$suffix/","New directory writer")
        val oldStarted=CompletableDeferred<Unit>();val oldCanceled=CompletableDeferred<Unit>();val oldDirectory=CompletableDeferred<Page>()
        val oldDiscoveries=AtomicInteger();val newDiscoveries=AtomicInteger();val oldDirectoryAttempts=AtomicInteger()
        val vm=AntiSocialViewModel(repo,createSearch={target->SearchFeed(target,discover={
            if(target==oldQuery){oldDiscoveries.incrementAndGet();Page(listOf(oldPost))}else {newDiscoveries.incrementAndGet();Page(listOf(newPost))}
        },read={error("Direct fixtures need no hydration")},directory={
            if(target==oldQuery) {
                oldDirectoryAttempts.incrementAndGet();oldStarted.complete(Unit)
                try {oldDirectory.await()}finally {if(!currentCoroutineContext().isActive)oldCanceled.complete(Unit)}
            }else Page(emptyList(),accounts=listOf(newAccount))
        })}).also {models+=it}
        try {
            host(vm,oldQuery)
            ui.waitUntil(10_000){oldStarted.isCompleted&&!vm.screen.value.loading&&vm.screen.value.page?.posts?.singleOrNull()?.id==oldPost.id}
            ui.runOnIdle {vm.open(newQuery,true)}
            ui.waitUntil(10_000){oldCanceled.isCompleted&&vm.screen.value.target==newQuery&&!vm.screen.value.loading&&vm.screen.value.page?.accounts?.singleOrNull()?.target==newAccount.target}
            oldDirectory.complete(Page(listOf(oldPost),accounts=listOf(oldAccount)))
            ui.mainClock.advanceTimeBy(800);ui.waitForIdle()
            assertEquals(newQuery,vm.screen.value.target)
            assertEquals(listOf(newPost.id),vm.screen.value.page!!.posts.map {it.id})
            assertEquals(listOf(newAccount.target),vm.screen.value.page!!.accounts.map {it.target})
            assertTrue(oldCanceled.isCompleted)
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(newAccount.name))
            ui.onNodeWithText(newAccount.name).assertIsDisplayed()
            ui.onNodeWithText(oldAccount.name).assertDoesNotExist()
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(newPost.body))
            ui.onNodeWithText(newPost.body).assertIsDisplayed()
            ui.onNodeWithText(oldPost.body).assertDoesNotExist()
            evidence("replacement-query-rejects-old-directory")
            assertEquals(1,oldDiscoveries.get());assertEquals(1,newDiscoveries.get());assertEquals(1,oldDirectoryAttempts.get())
            ui.runOnIdle {assertTrue(vm.back())}
            ui.waitUntil(10_000){vm.screen.value.target==oldQuery&&!vm.screen.value.loading&&oldDirectoryAttempts.get()==2&&vm.screen.value.page?.accounts?.singleOrNull()?.target==oldAccount.target}
            assertEquals("Returning to the fresh cached query must not refetch its posts",1,oldDiscoveries.get())
            assertEquals(1,newDiscoveries.get())
            assertEquals(listOf(oldPost.id),vm.screen.value.page!!.posts.map {it.id})
            assertEquals(listOf(oldAccount.target),vm.screen.value.page!!.accounts.map {it.target})
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(oldAccount.name))
            ui.onNodeWithText(oldAccount.name).assertIsDisplayed();ui.onNodeWithText(newAccount.name).assertDoesNotExist()
            evidence("cached-back-retries-directory-without-post-refetch")
        }finally {oldDirectory.complete(Page(emptyList()))}
    }
}
