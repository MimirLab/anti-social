/* anti social, GPL-3.0-or-later. Isolated scheduling checks, synthetic scenes. */
package app.antisocial.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VideoDwellTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var prefs:String
    private lateinit var originalSampler:suspend (Media,String)->GatewayVideoSampler.Result
    private val fixtureHost=AtomicReference<((MainActivity)->Unit)?>(null)
    private val mountedActivity=AtomicReference<MainActivity?>(null)
    private var lifecycleCallback:ActivityLifecycleCallback?=null
    private val at=System.currentTimeMillis()
    private val first=Post("710001",Platform.TIKTOK,"https://www.tiktok.com/@publicstudio/video/710001","Public studio","First film.",publishedAt=at,
        media=listOf(Media("https://media.fixture.invalid/first.mp4","video",durationMs=120_000)))
    private val second=Post("710002",Platform.X,"https://x.com/publicstudio/status/710002","Public studio","A different caption.",publishedAt=at+1_000,
        media=listOf(Media("https://media.fixture.invalid/second.mp4","video",durationMs=120_000)))
    private val request get()=VideoFootageRequest(first,second,setOf(CrossPosts.originalKey(first),CrossPosts.originalKey(second)))
    @Before fun isolate()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        repo.awaitReady();require(repo.dao.allFollows().isEmpty()&&repo.dao.allBookmarks().isEmpty())
        // Retire the activity's real startup refresh before mounting this
        // synthetic catalog. Its completion legitimately changes the video
        // revision; it must not race a fixture's deliberately frozen stamp.
        val startup=ui.runOnIdle {requireNotNull(ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.coroutineContext[Job])}
        withTimeout(20_000){startup.cancelAndJoin();repo.feedRefresh.first {!it.running}}
        ui.runOnIdle {ui.activity.setContent {Text("Preparing video scheduling fixture")}}
        prefs=repo.prefs.export().toString();originalSampler=repo.videos.sample
        repo.prefs.videoDownloadWifiOnly=false;repo.videos.playback(false);repo.videos.cleanup(true)
        // Battery saver can recreate the Activity for a night-mode change
        // between show() and Espresso's next idle check. Restore the fixture
        // on that new host before waiting for idle, not after the wait stalls.
        ui.runOnUiThread {
            lifecycleCallback=ActivityLifecycleCallback {activity,stage->
                if(stage==Stage.RESUMED&&activity is MainActivity&&mountedActivity.get()!==activity)fixtureHost.get()?.invoke(activity)
            }.also {ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(it)}
        }
    }
    @After fun restore() {
        if(::prefs.isInitialized) {
            val retained=repo
            ui.runOnUiThread {
                fixtureHost.set(null);mountedActivity.set(null)
                lifecycleCallback?.let {ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(it)}
                lifecycleCallback=null
                ui.activity.setContent {Text("Video scheduling fixture complete")}
            }
            retained.videos.cleanup(true);retained.videos.sample=originalSampler;retained.videos.playback(false);retained.prefs.restore(JSONObject(prefs))
        }
    }
    private fun sampled(media:Media):GatewayVideoSampler.Result {
        val scenes=(0..3).map {p->
            val detail=ByteArray(4096){i->var n=(p+1)*7919+(i%64/4)*31+(i/64/4)*1009;n=(n xor(n ushr 13))*1274126177;((n ushr 16 and 191)+32).toByte()}
            SampledVideoScene(p,requireNotNull(VisualFrames.encode(1.5,40.0,0L,0L,List(48){128},detail)))
        }
        return GatewayVideoSampler.Result(SampledVideoFrames(120_000,scenes),1024,1,media.url)
    }
    private fun show(catalog:State<Int>,offered:State<VideoFootageRequest?>,paused:State<Boolean>,lookup:VideoRequestLookup,counterparts:State<Map<String,List<Post>>> =mutableStateOf(emptyMap()),videoRevision:State<Long?> =mutableStateOf(null)) {
        val bank=repo
        val host:(MainActivity)->Unit={activity->mountedActivity.set(activity);activity.setContent {
            val resolver=remember(catalog.value) {
                val revision=catalog.value
                val current:VideoRequestLookup={key,excluded->check(revision>=0);lookup(key,excluded)}
                current
            }
            MaterialTheme {CompositionLocalProvider(LocalVideoRequestLookup provides resolver,LocalVideoOfferRevision provides videoRevision.value,LocalArchiveChecksPaused provides paused.value,LocalPostCounterparts provides counterparts.value) {
                val list=rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(),state=list) {
                    item(key=CrossPosts.originalKey(first)) {Column(Modifier.fillMaxWidth().height(320.dp)) {
                        FeedVideoDwell(list,CrossPosts.originalKey(first),first,true,offered.value,bank)
                        Text("Public workshop film")
                    }}
                }
            }}
        }}
        fixtureHost.set(host)
        ui.runOnUiThread {host(ui.activity)}
    }
    @Test fun freshGlobalOffersStayCheapWhileStaleSuccessorsWaitForCurrentEvidence():Unit=runBlocking {
        val catalog=mutableStateOf(0);val offered=mutableStateOf<VideoFootageRequest?>(request);val paused=mutableStateOf(false)
        val revision=mutableStateOf<Long?>(repo.videos.revision.value)
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val staleEntered=CompletableDeferred<Unit>()
        val calls=AtomicInteger();val lookups=AtomicInteger()
        val unrelated=second.copy(id="710003",canonicalUrl="https://x.com/publicstudio/status/710003",media=second.media.map {it.copy(url="https://media.fixture.invalid/unrelated.mp4")})
        repo.videos.sample={m,_->
            calls.incrementAndGet()
            if(m.url==first.media.single().url){entered.complete(Unit);finish.await()}
            if(m.url==unrelated.media.single().url){staleEntered.complete(Unit);awaitCancellation()}
            sampled(m)
        }
        val lookup:VideoRequestLookup={_,_->lookups.incrementAndGet();null}
        show(catalog,offered,paused,lookup,videoRevision=revision)
        try {
            ui.waitUntil(15_000){entered.isCompleted}
            assertEquals("A current global offer avoids an initial focused graph pass",0,lookups.get())
            ui.runOnIdle {catalog.value++;offered.value=VideoFootageRequest(first,unrelated,setOf(first.identityKey,unrelated.identityKey))}
            finish.complete(Unit)
            ui.waitUntil(10_000){staleEntered.isCompleted||lookups.get()>0}
            assertFalse("New scene evidence requires a current focused plan before another recording",staleEntered.isCompleted)
            assertEquals(2,calls.get());assertTrue(listOf(first,second).all {repo.videos.captured(it).media.single().videoFootage!=null})
            ui.waitForIdle();val idleLookups=lookups.get();delay(150)
            assertEquals("A rejected stale offer must suspend rather than poll the focused planner",idleLookups,lookups.get())
            // A later projection can still nominate this exact recording.
            // Freshness changes priority; it must not remove source coverage.
            ui.runOnIdle {revision.value=repo.videos.revision.value}
            ui.waitUntil(10_000){staleEntered.isCompleted}
            assertEquals(3,calls.get())
        }finally {finish.complete(Unit);ui.runOnIdle {paused.value=true}}
    }
    @Test fun aStaleGlobalOfferStillAllowsTheCurrentFocusedPlanToAcquireAMissingPeer():Unit=runBlocking {
        val catalog=mutableStateOf(0);val offered=mutableStateOf<VideoFootageRequest?>(request);val paused=mutableStateOf(false)
        val revision=mutableStateOf<Long?>(repo.videos.revision.value)
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val staleEntered=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val stale=second.copy(id="710003",canonicalUrl="https://x.com/publicstudio/status/710003",media=second.media.map {it.copy(url="https://media.fixture.invalid/stale.mp4")})
        val needed=second.copy(id="710004",canonicalUrl="https://x.com/publicstudio/status/710004",media=second.media.map {it.copy(url="https://media.fixture.invalid/needed.mp4")})
        val current=VideoFootageRequest(first,needed,setOf(first.identityKey,needed.identityKey))
        repo.videos.sample={m,_->
            calls.incrementAndGet()
            if(m.url==first.media.single().url){entered.complete(Unit);finish.await()}
            if(m.url==stale.media.single().url){staleEntered.complete(Unit);awaitCancellation()}
            sampled(m)
        }
        val lookup:VideoRequestLookup={_,excluded->current.takeIf {it.acquisitionKey !in excluded&&repo.videos.available(it)}}
        show(catalog,offered,paused,lookup,videoRevision=revision)
        try {
            ui.waitUntil(15_000){entered.isCompleted}
            ui.runOnIdle {catalog.value++;offered.value=VideoFootageRequest(first,stale,setOf(first.identityKey,stale.identityKey))}
            finish.complete(Unit)
            ui.waitUntil(10_000){staleEntered.isCompleted||repo.videos.captured(needed).media.single().videoFootage!=null}
            assertFalse("A stale offer must not beat a useful current peer",staleEntered.isCompleted)
            assertEquals(3,calls.get());assertNull(repo.videos.captured(stale).media.single().videoFootage)
        }finally {finish.complete(Unit);ui.runOnIdle {paused.value=true}}
    }
    @Test fun missingPlatformCoveragePrecedesACurrentSamePlatformOfferWithoutRemovingThatOffer():Unit=runBlocking {
        repo.videos.sample={m,_->sampled(m)};repo.videos.enrich(request,automatic=false)
        val unrelated=second.copy(id="710003",canonicalUrl="https://x.com/publicstudio/status/710003",media=second.media.map {it.copy(url="https://media.fixture.invalid/other-film.mp4")})
        val needed=second.copy(id="710004",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/publicstudio/posts/710004",media=second.media.map {it.copy(url="https://media.fixture.invalid/needed-film.mp4")})
        val offer=VideoFootageRequest(first,unrelated,setOf(first.identityKey,unrelated.identityKey))
        val focused=VideoFootageRequest(first,needed,setOf(first.identityKey,needed.identityKey))
        val revision=mutableStateOf<Long?>(repo.videos.revision.value)
        val known=mutableStateOf(mapOf(first.identityKey to listOf(first,second)))
        val paused=mutableStateOf(false);val neededEntered=CompletableDeferred<Unit>();val unrelatedEntered=CompletableDeferred<Unit>()
        val lookups=AtomicInteger()
        repo.videos.sample={m,_->
            if(m.url==needed.media.single().url)neededEntered.complete(Unit)
            if(m.url==unrelated.media.single().url){unrelatedEntered.complete(Unit);awaitCancellation()}
            sampled(m)
        }
        val lookup:VideoRequestLookup={_,excluded->lookups.incrementAndGet();focused.takeIf {it.acquisitionKey !in excluded&&repo.videos.available(it)}}
        show(mutableStateOf(0),mutableStateOf(offer),paused,lookup,known,revision)
        try {
            ui.waitUntil(15_000){neededEntered.isCompleted||unrelatedEntered.isCompleted}
            assertFalse("A current offer must not put represented-platform work ahead of missing coverage",unrelatedEntered.isCompleted)
            assertTrue(neededEntered.isCompleted);assertTrue(lookups.get()>0)
            ui.waitUntil(5_000){repo.videos.captured(needed).media.single().videoFootage!=null}
            ui.runOnIdle {revision.value=repo.videos.revision.value}
            ui.waitUntil(10_000){unrelatedEntered.isCompleted}
        }finally {ui.runOnIdle {paused.value=true}}
    }
    @Test fun aFocusedLookupWorksWithoutAGlobalOfferAndCatalogChangesCannotCancelItsSecondClip():Unit=runBlocking {
        val catalog=mutableStateOf(0);val offered=mutableStateOf<VideoFootageRequest?>(null);val paused=mutableStateOf(false)
        val calls=AtomicInteger();val lookups=AtomicInteger();val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        repo.videos.sample={m,_->
            calls.incrementAndGet()
            if(m.url==second.media.single().url)try {entered.complete(Unit);finish.await()}catch(e:CancellationException){cancelled.complete(Unit);throw e}
            sampled(m)
        }
        val lookup:VideoRequestLookup={_,excluded->lookups.incrementAndGet();request.takeIf {it.acquisitionKey !in excluded&&repo.videos.available(it)}}
        show(catalog,offered,paused,lookup)
        try {
            ui.waitUntil(15_000){entered.isCompleted};assertTrue(lookups.get()>0)
            val other=second.copy(id="710003",canonicalUrl="https://x.com/publicstudio/status/710003",media=second.media.map {it.copy(url="https://media.fixture.invalid/other.mp4")})
            ui.runOnIdle {catalog.value++;offered.value=VideoFootageRequest(first,other,setOf(CrossPosts.originalKey(first),CrossPosts.originalKey(other)))}
            delay(200);assertFalse("An evidence/catalog update must not cancel the active second recording",cancelled.isCompleted)
            ui.runOnIdle {offered.value=null};finish.complete(Unit)
            ui.waitUntil(10_000){listOf(first,second).all {repo.videos.captured(it).media.single().videoFootage!=null}}
            assertEquals(2,calls.get())
        }finally {finish.complete(Unit)}
    }
    @Test fun anOverlayCancelsPendingFocusedWorkAndRemovingItAllowsAFreshDwell():Unit=runBlocking {
        val catalog=mutableStateOf(0);val offered=mutableStateOf<VideoFootageRequest?>(null);val paused=mutableStateOf(false)
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();val calls=AtomicInteger()
        repo.videos.sample={m,_->
            if(calls.incrementAndGet()==1)try {entered.complete(Unit);awaitCancellation()}finally {cancelled.complete(Unit)}
            sampled(m)
        }
        val lookup:VideoRequestLookup={_,excluded->request.takeIf {it.acquisitionKey !in excluded&&repo.videos.available(it)}}
        show(catalog,offered,paused,lookup)
        ui.waitUntil(15_000){entered.isCompleted};ui.runOnIdle {paused.value=true}
        ui.waitUntil(10_000){cancelled.isCompleted}
        ui.waitUntil(5_000){repo.cardWork.active.value.none {it.label=="Comparing video editions"}}
        assertNull(repo.videos.captured(first).media.single().videoFootage)
        ui.runOnIdle {paused.value=false}
        ui.waitUntil(15_000){listOf(first,second).all {repo.videos.captured(it).media.single().videoFootage!=null}}
        assertEquals(3,calls.get())
    }
    @Test fun aNewPeerAcquisitionInputCancelsOnlyTheObsoleteChildAndReusesTheFirstSample():Unit=runBlocking {
        val catalog=mutableStateOf(0);val offered=mutableStateOf<VideoFootageRequest?>(request);val paused=mutableStateOf(false)
        val counterparts=mutableStateOf<Map<String,List<Post>>>(emptyMap())
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val renewed=second.copy(media=second.media.map {it.copy(url="https://media.fixture.invalid/renewed.mp4")})
        repo.videos.sample={m,_->
            calls.incrementAndGet()
            if(m.url==second.media.single().url)try {entered.complete(Unit);awaitCancellation()}finally {cancelled.complete(Unit)}
            sampled(m)
        }
        val lookup:VideoRequestLookup={_,_->null}
        show(catalog,offered,paused,lookup,counterparts)
        ui.waitUntil(15_000){entered.isCompleted}
        ui.runOnIdle {
            counterparts.value=mapOf(CrossPosts.originalKey(second)to listOf(renewed))
            offered.value=VideoFootageRequest(first,renewed,request.originalKeys)
        }
        ui.waitUntil(10_000){cancelled.isCompleted}
        ui.waitUntil(15_000){listOf(first,renewed).all {repo.videos.captured(it).media.single().videoFootage!=null}}
        assertNull(repo.videos.captured(second).media.single().videoFootage)
        assertEquals(3,calls.get())
    }

    @Test fun clearingOrdinaryEvidenceCancelsTheDwellWithoutStartingMoreReads():Unit=runBlocking {
        val catalog=mutableStateOf(0);val offered=mutableStateOf<VideoFootageRequest?>(request);val paused=mutableStateOf(false)
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();val calls=AtomicInteger()
        repo.videos.sample={m,_->
            if(calls.incrementAndGet()==1)try {entered.complete(Unit);awaitCancellation()}finally {cancelled.complete(Unit)}
            sampled(m)
        }
        val lookup:VideoRequestLookup={_,_->request}
        show(catalog,offered,paused,lookup)
        ui.waitUntil(15_000){entered.isCompleted};repo.videos.cleanup(true)
        ui.waitUntil(10_000){cancelled.isCompleted}
        ui.runOnIdle {catalog.value++};delay(300)
        assertEquals(1,calls.get());assertNull(repo.videos.captured(first).media.single().videoFootage)
        repo.videos.retryFailures()
        ui.waitUntil(15_000){listOf(first,second).all {repo.videos.captured(it).media.single().videoFootage!=null}}
        assertEquals(3,calls.get())
    }

    @Test fun stoppingTheActivityCancelsTheVisibleVideoWithoutARecomposition():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();val calls=AtomicInteger()
        repo.videos.sample={m,_->if(calls.incrementAndGet()==1)try {entered.complete(Unit);awaitCancellation()}finally {cancelled.complete(Unit)};sampled(m)}
        show(mutableStateOf(0),mutableStateOf(request),mutableStateOf(false),{_,_->null})
        ui.waitUntil(15_000){entered.isCompleted}
        try {ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED);withTimeout(5_000){cancelled.await()}}
        finally {ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)}
        ui.waitUntil(15_000){listOf(first,second).all {repo.videos.captured(it).media.single().videoFootage!=null}}
        assertEquals(3,calls.get())
    }

    @Test fun stoppingTheActivityCancelsTheVisibleGalleryWithoutARecomposition():Unit=runBlocking {
        val oldRead=repo.galleries.read;val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();val reads=AtomicInteger()
        val root=first.copy(platform=Platform.X,canonicalUrl="https://x.com/publicstudio/status/710001",media=(0..3).map {Media("https://pbs.twimg.com/media/fixture$it.jpg")})
        val peer=second.copy(platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/publicstudio/posts/710002",media=(0..5).map {Media("https://media.fixture.invalid/gallery$it.jpg")})
        val gallery=GalleryContinuationRequest(root,peer,listOf(root,peer),setOf("studio"))
        repo.prefs.wifiOnly=false;repo.galleries.clear()
        repo.galleries.read={if(reads.incrementAndGet()==1)try {entered.complete(Unit);awaitCancellation()}finally {cancelled.complete(Unit)};Page(listOf(root))}
        try {
            ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
                val list=rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(),state=list) {item(key=root.identityKey){Column(Modifier.fillMaxWidth().height(320.dp)) {
                    FeedGalleryDwell(list,root.identityKey,gallery,repo);Text("Public workshop gallery")
                }}}
            }}}
            ui.waitUntil(15_000){entered.isCompleted}
            try {ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED);withTimeout(5_000){cancelled.await()}}
            finally {ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)}
            ui.waitUntil(15_000){reads.get()==2&&!repo.galleries.available(gallery)}
            assertEquals(2,reads.get())
        }finally {
            ui.runOnIdle {ui.activity.setContent {Text("Visible gallery stop complete")}}
            repo.galleries.clear();repo.galleries.read=oldRead
        }
    }

    private fun shell(command:String)=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).use {stream->stream.readBytes().decodeToString()}
    }
    @Test fun powerSaverSkipsPlanningAndSamplingButAReleasedHoldCanRetry():Unit=runBlocking {
        val power=ui.activity.getSystemService(android.os.PowerManager::class.java)
        val previous=power.isPowerSaveMode
        val catalog=mutableStateOf(0);val offered=mutableStateOf<VideoFootageRequest?>(null);val paused=mutableStateOf(false)
        val lookups=AtomicInteger();val samples=AtomicInteger()
        repo.videos.sample={m,_->samples.incrementAndGet();sampled(m)}
        val lookup:VideoRequestLookup={_,excluded->lookups.incrementAndGet();request.takeIf {it.acquisitionKey !in excluded&&repo.videos.available(it)}}
        try {
            shell("dumpsys battery unplug")
            ui.waitUntil(5_000){shell("dumpsys power").contains("mIsPowered=false")}
            shell("cmd power set-mode 1");ui.waitUntil(5_000){power.isPowerSaveMode}
            show(catalog,offered,paused,lookup)
            var fixtureActivity=ui.activity;var remounts=0
            ui.mainClock.advanceTimeBy(3_000);ui.waitForIdle();delay(200)
            assertEquals("Power saver must defer the focused graph plan as well as decoder/network work",0,lookups.get())
            assertEquals(0,samples.get())
            // A deliberate read retains its existing override; only automatic
            // enrichment is held. The next automatic dwell still samples anew.
            repo.videos.enrich(request,automatic=false)
            assertEquals(2,samples.get());assertNotNull(repo.videos.captured(first).media.single().videoFootage)
            repo.videos.cleanup(true)
            shell("cmd power set-mode 0");ui.waitUntil(5_000){!power.isPowerSaveMode}
            assertTrue("Other automatic resource guards must also be available after releasing power saver",repo.videos.automaticAllowed())
            repo.videos.retryFailures()
            fun captured()=listOf(first,second).all {repo.videos.captured(it).media.single().videoFootage!=null}
            try {
                // Battery saver can change Android's night configuration and
                // recreate MainActivity. Its onCreate mounts the real app; the
                // synthetic host must be mounted again on that new activity.
                repeat(3) {
                    ui.waitUntil(15_000){captured()||ui.activity!==fixtureActivity}
                    if(!captured()) {
                        fixtureActivity=ui.activity;remounts++;show(catalog,offered,paused,lookup);repo.videos.retryFailures()
                    }
                }
                assertTrue("A released hold can start a new visible-card dwell",captured())
            }catch(error:Throwable) {
                val directory=java.io.File(ui.activity.getExternalFilesDir("validation"),"video-dwell").apply {mkdirs()}
                java.io.File(directory,"power-resume-state.json").writeText(JSONObject().put("sameFixtureActivity",fixtureActivity===ui.activity)
                    .put("powerSave",power.isPowerSaveMode).put("automaticAllowed",repo.videos.automaticAllowed())
                    .put("focused",ui.activity.window.decorView.hasWindowFocus()).put("lifecycle",ui.activity.lifecycle.currentState.name)
                    .put("lookups",lookups.get()).put("samples",samples.get()).put("playing",repo.videos.playbackActive.value).toString())
                java.io.File(directory,"power-resume-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
                throw error
            }
            assertTrue(lookups.get()>0);assertEquals(4,samples.get())
            val evidence=java.io.File(ui.activity.getExternalFilesDir("validation"),"video-dwell").apply {mkdirs()}
            java.io.File(evidence,"power-success.json").writeText(JSONObject().put("blockedPlanningCalls",0).put("manualSamples",2)
                .put("totalSamples",samples.get()).put("lookupCallsAfterRelease",lookups.get()).put("fixtureRemounts",remounts).toString())
        }finally {
            shell("cmd power set-mode ${if(previous)1 else 0}");shell("dumpsys battery reset")
        }
    }

}
