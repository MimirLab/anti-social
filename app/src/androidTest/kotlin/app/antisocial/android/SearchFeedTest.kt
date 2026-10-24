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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SearchFeedTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val target=Target(Platform.X,TargetKind.SEARCH,"native feed fixture $suffix","https://x.com/search?q=native%20feed%20fixture%20$suffix")
    private val source=Source(Platform.X,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var searchPolicy: NetworkPolicy
    private val models=mutableListOf<AntiSocialViewModel>()
    private val targets=mutableSetOf(target)
    private val posts=mutableMapOf<Int,Post>()
    private val extraPosts=mutableListOf<Post>()
    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();searchPolicy=repo.gateway.currentPolicy(NetworkRoute.TOR)
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;scheduleRefresh(ui.activity,repo.prefs)
        ui.runOnIdle {ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].following()}
        if(InstrumentationRegistry.getArguments().getString("antisocialLiveSearchFeed")!="true")repo.gateway.configure(NetworkPolicy(tor=true))
    }
    @After fun restore() {
        ui.runOnIdle {
            models.forEach {it.discussions.close();it.viewModelScope.cancel()}
            ui.activity.setContent {MaterialTheme {Text("Search fixture complete")}}
        }
        runBlocking {
            for(destination in targets) {
                val key=repo.pageKey(destination)
                repo.dao.media(key)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(key))
            }
            val keys=(posts.values+extraPosts).map(repo::key).distinct()
            repo.dao.removePosts(keys)
            for(table in listOf("history","seen"))for(key in keys)
                repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
            repo.gateway.configure(policy);repo.gateway.configureTor(searchPolicy)
        }
    }
    private fun post(id: Int)=posts.getOrPut(id){Post("native-feed-$suffix-$id",Platform.X,"https://x.com/nativefixture/status/96$suffix${1000+id}","Native author","Native search post $id\nA real extracted post card, with the same interactions as Following.",publishedAt=1_760_000_000_000L-id*60_000L,provenance=Provenance(source,System.currentTimeMillis(),source.root))}
    private fun model(createSearch: (Target)->SearchFeed)=AntiSocialViewModel(repo,createSearch=createSearch).also {models+=it}
    private fun show(vm: AntiSocialViewModel,destination: Target=target) {targets+=destination;ui.runOnUiThread {vm.following();ui.activity.setContent {AntiSocialApp(vm)};vm.open(destination,true)}}
    private fun clean(vm: AntiSocialViewModel) {
        ui.runOnUiThread {vm.following();vm.discussions.close();vm.viewModelScope.cancel()}
    }
    private fun evidence(name: String) {
        ui.waitForIdle();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun failureEvidence(name: String,vm: AntiSocialViewModel,reads: List<String>) {
        val dir=requireNotNull(ui.activity.getExternalFilesDir("validation"))
        runCatching {
            val state=vm.screen.value
            val record=mapOf("target" to state.target,"loading" to state.loading,"error" to state.error,"feed" to state.searchFeed,"reads" to reads.toList(),"selectedPlatforms" to repo.prefs.selected.map {it.id})
            File(dir,"$name.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(record))
            File(dir,"$name-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
            evidence(name)
        }.onFailure {error->android.util.Log.w("AntiSocialSearchFeed","Search failure capture was incomplete",error)}
    }
    @Test fun deferredPublicIndexResultsStreamBehindTheFirstNativeCard() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        repo.prefs.selected=(repo.prefs.selected+Platform.INSTAGRAM).distinct();repo.prefs.activePlatform=Platform.INSTAGRAM
        val query=repo.routes.search(Platform.INSTAGRAM,"native-index-$suffix")
        val fastUrl="https://www.instagram.com/p/NativeFast$suffix"
        val reelUrl="https://www.instagram.com/reel/NativeReel$suffix"
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"search-lane-fairness").apply {mkdirs()}
        PublicIndexNativeFixture(repo.routes,query,fastUrl,reelUrl).use {f->
            val clock=System.nanoTime();val arrivals=CopyOnWriteArrayList<Map<String,Any>>()
            val fast=Post("fast-$suffix",Platform.INSTAGRAM,fastUrl,"Native fixture","First useful native search result.",publishedAt=System.currentTimeMillis())
            val slower=Post("slower-$suffix",Platform.INSTAGRAM,reelUrl,"Native fixture","Recovered slower Reel result.",publishedAt=fast.publishedAt!!-1_000)
            val additional=fast.copy(id="additional-$suffix",canonicalUrl=fastUrl+"More",body="Another page from the fast query.",publishedAt=fast.publishedAt!!-2_000)
            extraPosts+=listOf(fast,slower,additional)
            val discoveryReads=AtomicInteger()
            val vm=model {opened->
                assertEquals(query,opened)
                SearchFeed(opened,discover={previous->
                    val first=discoveryReads.incrementAndGet()==1
                    val page=f.reader.fetch(opened,previous?.next)
                    arrivals+=mapOf("atMs" to (System.nanoTime()-clock)/1_000_000,"phase" to "index","first" to first,"destinations" to page.collections.map {it.target.canonicalUrl})
                    if(first)f.firstPlan(page)else page
                },read={destination->
                    val post=listOf(fast,slower,additional).firstOrNull {it.canonicalUrl==destination.canonicalUrl}
                    if(post!=null) {
                        arrivals+=mapOf("atMs" to (System.nanoTime()-clock)/1_000_000,"phase" to "hydrate","url" to post.canonicalUrl)
                        Page(listOf(post))
                    }else Page(emptyList(),profile=Profile(destination,"Fixture account","Public fixture profile"))
                },maxPages=2)
            }
            var failure:String?=null
            try {
                show(vm,query)
                ui.waitUntil(10_000){ui.onAllNodesWithText(fast.body).fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("Search feed").performScrollToNode(hasText(fast.body))
                ui.onNodeWithText(fast.body).assertIsDisplayed()
                // Semantics can be ready before the compositor presents its
                // frame. Finish entry animations while the later read is held.
                ui.mainClock.advanceTimeBy(500)
                ui.runOnUiThread {ui.activity.window.decorView.requestLayout();ui.activity.window.decorView.invalidate()}
                ui.waitForIdle();android.os.SystemClock.sleep(250)
                evidence("search-lane-first-native-card")
                f.release.countDown()
                ui.waitUntil(10_000){vm.screen.value.searchFeed?.loading==false}
                val feed=requireNotNull(vm.screen.value.searchFeed)
                assertTrue("The first native result must stay visible",feed.page.posts.any {it.canonicalUrl==fastUrl})
                assertTrue("The next block must recover the deferred Reel instead of another fast page",feed.page.posts.any {it.canonicalUrl==reelUrl})
                assertEquals("Parked continuation must wait until deferred results arrive",1,f.fastReads.get())
                assertEquals(2,f.reelReads.get());assertEquals(2,discoveryReads.get())
                ui.onNodeWithTag("Search feed").performScrollToNode(hasText(slower.body))
                ui.onNodeWithText(slower.body).assertIsDisplayed()
                evidence("search-lane-deferred-reel-native")
            }catch(e:Throwable){failure=e.message;failureEvidence("search-lane-failure",vm,f.requests.toList());throw e}
            finally {
                File(directory,"streaming.json").writeText(repo.gson.toJson(mapOf("failure" to failure,"arrivals" to arrivals,"requests" to f.requests,"fastReads" to f.fastReads.get(),"reelReads" to f.reelReads.get(),"posts" to vm.screen.value.searchFeed?.page?.posts?.map {it.canonicalUrl},"scope" to "Authored two-block search on Android with source-bound saved cursor and loopback Gateway; real app search UI, no live-provider claim.")))
                clean(vm)
            }
        }
    }
    @Test fun threePublicIndexShapesRetainTheirTurnOnNativeGateway() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val query=repo.routes.search(Platform.TIKTOK,"native-fair-$suffix")
        val urls=listOf("https://www.tiktok.com/@nativefixture/video/97${suffix}1001","https://www.tiktok.com/@nativefixture/photo/97${suffix}1002","https://www.tiktok.com/@nativefixture/video/97${suffix}1003")
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"search-lane-fairness").apply {mkdirs()}
        PublicIndexNativeFixture(repo.routes,query,urls[0],urls[1],urls[2]).use {f->
            val found=linkedSetOf<String>();val blocks=mutableListOf<Map<String,Any?>>()
            var cursor:String?=null;var failure:String?=null
            try {
                runBlocking {
                    var round=0
                    do {
                        val started=System.nanoTime()
                        val page=withTimeout(2_500){f.reader.fetch(query,cursor)}
                        found+=page.collections.map {it.target.canonicalUrl.trimEnd('/')}
                        blocks+=mapOf("elapsedMs" to (System.nanoTime()-started)/1_000_000,"urls" to page.collections.map {it.target.canonicalUrl},"requestsByQuery" to listOf(f.fastReads.get(),f.reelReads.get(),f.broadReads.get()))
                        cursor=if(page.next!=null&&f.fastReads.get()<4)f.firstPlan(page).next else page.next
                        round++
                    }while(found.size<3&&cursor!=null&&round<4)
                }
                assertEquals("All shapes must arrive before repeated fast pages consume the four-attempt allowance",urls.toSet(),found)
                assertEquals(1,f.fastReads.get());assertTrue(f.requests.size<=6)
            }catch(e:Throwable){failure=e.message;throw e}
            finally {File(directory,"three-shapes.json").writeText(repo.gson.toJson(mapOf("failure" to failure,"blocks" to blocks,"requests" to f.requests,"counts" to listOf(f.fastReads.get(),f.reelReads.get(),f.broadReads.get()),"found" to found,"scope" to "Native Gateway with authored saved-cursor fixtures, not public-provider coverage or timing.")))}
        }
    }
    @Test fun resultsStreamIntoNormalCardsWithoutShowingIndexSnippets() {
        runBlocking {repo.awaitReady()};val one=CompletableDeferred<Page>();val two=CompletableDeferred<Page>()
        val profileTarget=repo.routes.target(Platform.X,"nativefixture")
        val nativeOne=post(1);val nativeTwo=post(2)
        val vm=model {query->SearchFeed(query,discover={Page(emptyList(),provenance=Provenance(source,System.currentTimeMillis(),source.root),collections=listOf(Collection(repo.routes.recognize(nativeOne.canonicalUrl)!!,"Search engine title",detail="Snippet must not be a post"),Collection(repo.routes.recognize(nativeTwo.canonicalUrl)!!,"Second index title"),Collection(profileTarget,"Indexed account name")))},read={destination->when(destination.canonicalUrl){nativeOne.canonicalUrl->one.await();nativeTwo.canonicalUrl->two.await();else->Page(emptyList(),profile=Profile(profileTarget,"Native account","Actual public biography"))}})}
        try {
            show(vm);ui.onNodeWithTag("Search feed").assertIsDisplayed()
            ui.waitUntil(10_000){vm.screen.value.loading}
            one.complete(Page(listOf(nativeOne)))
            ui.waitUntil(10_000){vm.screen.value.page?.posts?.size==1}
            assertTrue(vm.screen.value.loading)
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(nativeOne.body))
            ui.onNodeWithText(nativeOne.body).assertIsDisplayed()
            ui.onNodeWithText("Snippet must not be a post").assertDoesNotExist();ui.onNodeWithText("Search engine title").assertDoesNotExist()
            two.complete(Page(listOf(nativeTwo)))
            ui.waitUntil(10_000){!vm.screen.value.loading&&vm.screen.value.page?.accounts?.isNotEmpty()==true}
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText("Native account"))
            ui.onNodeWithText("Native account").assertIsDisplayed();evidence("native-search-streamed")
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(nativeTwo.body));ui.onNodeWithText(nativeTwo.body).assertIsDisplayed()
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText("You're caught up"));ui.onNodeWithText("You're caught up").assertIsDisplayed();evidence("native-search-feed-posts")
            val cached=runBlocking {repo.cachedPage(target)}!!
            assertEquals(2,cached.posts.size);assertEquals("Native account",cached.accounts.single().name)
        }finally {clean(vm)}
    }
    @Test fun scrollingNearTheEndFetchesAnotherBlockAndStopsAtTheEnd() {
        runBlocking {repo.awaitReady()};val calls=AtomicInteger();val next=CompletableDeferred<Page>()
        val vm=model {query->SearchFeed(query,discover={previous->calls.incrementAndGet();if(previous==null)Page((1..16).map {post(it)},next="next-block",provenance=Provenance(source,System.currentTimeMillis(),source.root))else next.await()},read={error("Native search posts need no hydration")})}
        try {
            show(vm);ui.waitUntil(10_000){vm.screen.value.page?.posts?.size==16&&!vm.screen.value.loading}
            assertEquals(1,calls.get());ui.onNodeWithText("Load more").assertDoesNotExist()
            ui.onNodeWithTag("Search feed").performScrollToIndex(15)
            ui.waitUntil(10_000){calls.get()==2}
            assertEquals(16,vm.screen.value.page!!.posts.size)
            next.complete(Page(listOf(post(16),post(17),post(18)),provenance=Provenance(source,System.currentTimeMillis(),source.root)))
            ui.waitUntil(10_000){vm.screen.value.page?.posts?.size==18&&!vm.screen.value.loading}
            assertFalse(vm.screen.value.searchFeed!!.hasMore)
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText("You're caught up"));ui.onNodeWithText("You're caught up").assertIsDisplayed()
            assertEquals(2,calls.get());evidence("native-search-auto-pagination")
        }finally {clean(vm)}
    }
    @Test fun aNewSearchCancelsTheOldFeedWithoutMixingResults() {
        runBlocking {repo.awaitReady()};val old=CompletableDeferred<Page>();val started=CompletableDeferred<Unit>()
        val replacement=repo.routes.search(Platform.X,"replacement feed fixture $suffix").also {targets+=it}
        val vm=model {query->SearchFeed(query,discover={if(query==target){started.complete(Unit);old.await()}else Page(listOf(post(2)),provenance=Provenance(source,System.currentTimeMillis(),source.root))},read={error("No hydration needed")})}
        try {
            show(vm);runBlocking {withTimeout(10_000){started.await()}}
            ui.runOnUiThread {vm.open(replacement,true)}
            ui.waitUntil(10_000){vm.screen.value.target==replacement&&!vm.screen.value.loading}
            old.complete(Page(listOf(post(1))))
            ui.waitForIdle();assertEquals(replacement,vm.screen.value.target)
            assertEquals(listOf(post(2).id),vm.screen.value.page!!.posts.map {it.id})
            ui.onNodeWithText(post(1).body).assertDoesNotExist();ui.onNodeWithText(post(2).body).assertIsDisplayed()
        }finally {
            clean(vm)
            runBlocking {repo.dao.media(repo.pageKey(replacement))?.let {java.io.File(it.path).delete()};repo.dao.removeMedia(listOf(repo.pageKey(replacement)))}
        }
    }
    @Test fun unreadableCandidatesDoNotHideALaterReadableFacebookPost() {
        repo.prefs.selected=repo.prefs.selected+Platform.FACEBOOK
        val facebook=repo.routes.search(Platform.FACEBOOK,"melbourne organic food fixture $suffix")
        val destinations=(1..3).map {id->Target(Platform.FACEBOOK,TargetKind.POST,"100000000000001/posts/96$suffix$id","https://www.facebook.com/100000000000001/posts/96$suffix$id")}
        val readable=Post("facebook-native-$suffix",Platform.FACEBOOK,destinations.last().canonicalUrl,"Local food fixture","Melbourne organic food\nA public post recovered after two unavailable indexed links.",publishedAt=1_760_000_000_000L).also {extraPosts+=it}
        val reachedReadable=CompletableDeferred<Unit>();val release=CompletableDeferred<Page>()
        val reads=CopyOnWriteArrayList<String>()
        val vm=model {query->SearchFeed(query,discover={Page(emptyList(),collections=destinations.map {Collection(it,"Indexed organic food",detail="An index snippet isn't the post")})},read={destination->
            reads+=destination.canonicalUrl
            if(destination==destinations.last()){reachedReadable.complete(Unit);release.await()}else Page(emptyList())
        },blockSize=2)}
        try {
            show(vm,facebook)
            // Advancing the native list's layout clock lets its near-end observer
            // request the next block after the first two unreadable candidates.
            ui.waitUntil(10_000){reachedReadable.isCompleted}
            assertTrue(vm.screen.value.loading)
            assertNull(vm.screen.value.error)
            ui.onNodeWithTag("Search end").assertDoesNotExist()
            ui.onNodeWithText("An index snippet isn't the post").assertDoesNotExist()
            release.complete(Page(listOf(readable)))
            ui.waitUntil(10_000){!vm.screen.value.loading&&vm.screen.value.page?.posts?.size==1}
            assertEquals(destinations.map {it.canonicalUrl}.toSet(),reads.toSet())
            assertEquals(2,vm.screen.value.searchFeed!!.unavailable)
            assertFalse(vm.screen.value.searchFeed!!.hasMore)
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(readable.body))
            ui.onNodeWithText(readable.body).assertIsDisplayed()
            ui.onNodeWithText("No readable public results").assertDoesNotExist()
            evidence("native-search-facebook-after-unavailable")
        }catch(error: Throwable) {
            failureEvidence("native-search-facebook-unavailable-failure",vm,reads)
            throw error
        }finally {release.complete(Page(emptyList()));clean(vm)}
    }
    @Test fun stoppingCancelsActiveAndQueuedReadsUntilAnExplicitContinue() {
        val paused=AtomicBoolean(true);val reads=AtomicInteger();val active=AtomicInteger();val canceled=AtomicInteger()
        val destinations=(2..9).map {repo.routes.recognize(post(it).canonicalUrl)!!}
        val vm=model {query->SearchFeed(query,discover={Page(listOf(post(1)),collections=destinations.map {Collection(it,"Indexed post")})},read={destination->
            reads.incrementAndGet();active.incrementAndGet()
            try {
                if(paused.get())try {awaitCancellation()}finally {canceled.incrementAndGet()}
                Page(listOf(posts.values.single {it.canonicalUrl==destination.canonicalUrl}))
            }finally {active.decrementAndGet()}
        },blockSize=8)}
        try {
            show(vm)
            ui.waitUntil(10_000){active.get()>0&&vm.screen.value.page?.posts?.size==1}
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText("Stop"))
            ui.onNodeWithText("Stop").performClick()
            ui.waitUntil(10_000){!vm.screen.value.loading&&active.get()==0}
            val startedBeforePause=reads.get()
            assertTrue("The fixture must leave unread queued candidates",startedBeforePause in 1 until destinations.size)
            assertEquals(startedBeforePause,canceled.get())
            assertTrue(vm.screen.value.searchFeed!!.paused)
            assertNull(vm.screen.value.error)
            assertEquals(listOf(post(1).id),vm.screen.value.page!!.posts.map {it.id})
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText("Continue search"))
            ui.onNodeWithText("Search paused").assertIsDisplayed()
            ui.onNodeWithTag("Search error").assertDoesNotExist()
            ui.onNodeWithText("Search is unavailable").assertDoesNotExist()
            ui.mainClock.advanceTimeBy(1_200);ui.waitForIdle()
            assertEquals("Being near the end must not resume a paused search",startedBeforePause,reads.get())
            assertEquals(0,active.get())
            evidence("native-search-paused-with-partial-card")
            paused.set(false)
            ui.onNodeWithText("Continue search").performClick()
            ui.waitUntil(10_000){!vm.screen.value.loading&&vm.screen.value.page?.posts?.size==9}
            assertFalse(vm.screen.value.searchFeed!!.paused)
            assertFalse(vm.screen.value.searchFeed!!.hasMore)
            assertNull(vm.screen.value.error)
            assertEquals(9,vm.screen.value.page!!.posts.map {repo.key(it)}.distinct().size)
            assertEquals(startedBeforePause+destinations.size,reads.get())
            assertEquals(0,active.get())
            ui.onNodeWithTag("Search paused").assertDoesNotExist()
        }finally {clean(vm)}
    }
    @Test fun aFailedContinuationKeepsCardsAndRetriesThatPageOnlyOnce() {
        val discoveries=AtomicInteger();val attempts=AtomicInteger();val retry=CompletableDeferred<Page>()
        val vm=model {query->SearchFeed(query,discover={previous->
            discoveries.incrementAndGet()
            if(previous==null)Page((1..16).map {post(it)},next="retry-this-continuation")
            else {
                assertEquals("retry-this-continuation",previous.next)
                if(attempts.incrementAndGet()==1)error("Fixture continuation temporarily unavailable")
                retry.await()
            }
        },read={error("Direct post fixtures need no hydration")})}
        try {
            show(vm);ui.waitUntil(10_000){!vm.screen.value.loading&&vm.screen.value.page?.posts?.size==16}
            ui.onNodeWithTag("Search feed").performScrollToIndex(15)
            ui.waitUntil(10_000){vm.screen.value.error!=null&&!vm.screen.value.loading}
            assertEquals(16,vm.screen.value.page!!.posts.size)
            assertTrue(vm.screen.value.searchFeed!!.hasMore)
            assertEquals(2,discoveries.get())
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText("Couldn't load more results"))
            ui.onNodeWithText("Couldn't load more results").assertIsDisplayed()
            ui.onNodeWithText("No readable public results").assertDoesNotExist()
            ui.mainClock.advanceTimeBy(1_200);ui.waitForIdle();assertEquals(2,discoveries.get())
            evidence("native-search-continuation-error-keeps-cards")
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText("Retry"))
            ui.onNodeWithText("Retry").performClick()
            ui.waitUntil(10_000){attempts.get()==2&&vm.screen.value.loading}
            ui.mainClock.advanceTimeBy(600);ui.waitForIdle();assertEquals(3,discoveries.get())
            retry.complete(Page(listOf(post(16),post(17))))
            ui.waitUntil(10_000){!vm.screen.value.loading&&vm.screen.value.page?.posts?.size==17}
            assertNull(vm.screen.value.error);assertFalse(vm.screen.value.searchFeed!!.hasMore)
            assertEquals(3,discoveries.get())
            assertEquals(17,vm.screen.value.page!!.posts.map {repo.key(it)}.distinct().size)
            ui.onNodeWithTag("Search error").assertDoesNotExist()
        }finally {retry.complete(Page(emptyList()));clean(vm)}
    }
    @Test fun emptyUnavailableAndProfilesOnlyResultsHaveDifferentHonestEndStates() {
        val empty=repo.routes.search(Platform.X,"no indexed results fixture $suffix")
        val unavailable=repo.routes.search(Platform.X,"unreadable indexed results fixture $suffix")
        val people=repo.routes.search(Platform.X,"people only fixture $suffix")
        val profileTarget=repo.routes.target(Platform.X,"nfx$suffix")
        val vm=model {query->SearchFeed(query,discover={when(query) {
            empty->Page(emptyList())
            unavailable->Page(emptyList(),collections=listOf(Collection(repo.routes.recognize(post(1).canonicalUrl)!!,"Indexed result")))
            else->Page(emptyList(),accounts=listOf(Profile(profileTarget,"Public fixture profile","Actual public account metadata")))
        }},read={Page(emptyList())})}
        try {
            show(vm,empty)
            ui.waitUntil(10_000){!vm.screen.value.loading&&vm.screen.value.searchFeed?.hasMore==false}
            ui.onNodeWithText("No readable public results").assertIsDisplayed()
            ui.onNodeWithText("You're caught up").assertDoesNotExist()
            targets+=unavailable;ui.runOnIdle {vm.open(unavailable,true)}
            ui.waitUntil(10_000){vm.screen.value.target==unavailable&&!vm.screen.value.loading}
            ui.onNodeWithText("Public results couldn't be read").assertIsDisplayed()
            ui.onNodeWithText("No readable public results").assertDoesNotExist()
            ui.onNodeWithText("Inspect sources").assertIsDisplayed()
            assertEquals(1,vm.screen.value.searchFeed!!.unavailable)
            evidence("native-search-unavailable-is-not-zero-matches")
            targets+=people;ui.runOnIdle {vm.open(people,true)}
            ui.waitUntil(10_000){vm.screen.value.target==people&&!vm.screen.value.loading}
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText("No readable posts returned"))
            ui.onNodeWithText("No readable posts returned").assertIsDisplayed()
            ui.onNodeWithText("You're caught up").assertDoesNotExist()
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText("Public fixture profile"))
            ui.onNodeWithText("Public fixture profile").assertIsDisplayed()
        }finally {clean(vm)}
    }
    /** Explicit public-source check; fixtures above remain deterministic. */
    @Test fun liveSearchResolvesPostsAndRequestsARealContinuation() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveSearchFeed")=="true")
        runBlocking {repo.awaitReady()}
        org.junit.Assume.assumeTrue(repo.prefs.searchProvider==SearchProvider.DUCKDUCKGO&&repo.prefs.originalFallback)
        val vm=androidx.lifecycle.ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        val responses=AtomicInteger();val continuations=AtomicInteger();val dir=requireNotNull(ui.activity.getExternalFilesDir("validation"))
        repo.gateway.onTextResponse={payload->if(java.net.URI(payload.finalUrl).host=="html.duckduckgo.com") {
            if((payload.pageOffset ?: 0)>0)continuations.incrementAndGet()
            val n=responses.incrementAndGet();if(n<=3)java.io.File(dir,"native-search-live-index-$n.html").writeText(payload.text)
        }}
        val started=android.os.SystemClock.elapsedRealtime();val freshAfter=System.currentTimeMillis()
        try {
            ui.runOnUiThread {vm.platform(Platform.X,false);vm.following();repo.applyNetworkSettings();vm.open(repo.routes.search(Platform.X,"elon"),true)}
            ui.waitUntil(120_000){vm.screen.value.error!=null||vm.screen.value.page?.posts?.isNotEmpty()==true}
            assertNull(vm.screen.value.error)
            val first=vm.screen.value.page!!.posts.first()
            assertTrue(first.body.isNotBlank()||first.media.isNotEmpty());assertTrue(first.provenance!!.fetchedAt>=freshAfter&&!first.provenance!!.cached)
            val firstPostMs=android.os.SystemClock.elapsedRealtime()-started
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(first.body));evidence("native-search-live-posts")
            ui.waitUntil(120_000){!vm.screen.value.loading}
            for(round in 0..8) {
                val current=vm.screen.value
                if(continuations.get()>0||current.searchFeed?.hasMore!=true||current.error!=null)break
                val count=current.page!!.posts.size+if(current.page.accounts.isNotEmpty())2 else 0
                ui.onNodeWithTag("Search feed").performScrollToIndex(count)
                ui.waitUntil(120_000){!vm.screen.value.loading}
                ui.waitForIdle()
            }
            val current=vm.screen.value
            val record=mapOf("firstPostMs" to firstPostMs,"indexResponses" to responses.get(),"continuationResponses" to continuations.get(),"posts" to current.page!!.posts.size,"accounts" to current.page.accounts.size,"hasMore" to current.searchFeed?.hasMore,"error" to current.error,"unavailable" to current.searchFeed?.unavailable,"defaultTor" to repo.gateway.currentPolicy().tor)
            java.io.File(dir,"native-search-live.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(record))
            android.util.Log.i("AntiSocialSearchFeed",record.toString());evidence("native-search-live-pagination")
            assertNull(current.error);assertTrue("The provider must issue and receive a real next-page request",continuations.get()>0)
            assertTrue(current.page.posts.isNotEmpty());assertTrue(current.page.collections.isEmpty())
        }finally {repo.gateway.onTextResponse=null;ui.runOnUiThread {vm.following();repo.applyNetworkSettings()}}
    }
}
