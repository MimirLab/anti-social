/* anti social, GPL-3.0-or-later. Synthetic intent and native cancellation controls. */
package app.antisocial.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

private val preparationAt=1_800_000_000_000L
private fun preparationPost(id:String,platform:Platform,body:String="A different workshop film.",offset:Long=0)=Post(id,platform,
    when(platform){Platform.TIKTOK->"https://www.tiktok.com/@publicstudio/video/$id";Platform.FACEBOOK->"https://www.facebook.com/publicstudio/posts/$id";else->"https://x.com/publicstudio/status/$id"},
    "publicstudio",body,publishedAt=preparationAt+offset,media=listOf(Media("https://media.fixture.invalid/$id.mp4","video",durationMs=120_000)))
private fun preparationFollows()=listOf(Platform.TIKTOK,Platform.X,Platform.FACEBOOK).map {p->
    Follow(p.id,Target(p,TargetKind.PROFILE,"publicstudio",when(p){Platform.TIKTOK->"https://www.tiktok.com/@publicstudio";Platform.FACEBOOK->"https://www.facebook.com/publicstudio";else->"https://x.com/publicstudio"}),"Public studio",personId="studio")
}
private fun preparationSource(posts:List<Post>)=FeedVideoPreparationSource(posts,preparationFollows(),posts.associate {it.identityKey to setOf(it.platform.id)})

class FeedVideoPreparationInputTest {
    private val first=preparationPost("71000001",Platform.TIKTOK,"First workshop film.")
    private val second=preparationPost("72000001",Platform.X,offset=1_000)
    @Test fun onlyBoundedExplicitlyOwnedNearbyRecordingsReceivePreparation() {
        val more=(2..40).map {preparationPost("${72000000+it}",Platform.X,offset=it*1000L)}
        val old=preparationPost("73000001",Platform.FACEBOOK,offset=6*60*60_000L+1)
        val namesake=preparationPost("73000002",Platform.FACEBOOK)
        val source=preparationSource(listOf(first,second,old,namesake)+more).let {s->s.copy(memberships=s.memberships+(namesake.identityKey to emptySet()))}
        val selected=source.nearby(first)
        assertEquals(24,selected.size);assertEquals(first,selected.first());assertFalse(old in selected);assertFalse(namesake in selected)
        assertEquals(selected,source.copy(posts=source.posts.reversed()).nearby(first))
        assertTrue(source.copy(memberships=source.memberships-first.identityKey).nearby(first).isEmpty())
        assertTrue(source.nearby(first.copy(parentId="70000000")).isEmpty())
        assertEquals(43,source.posts.size)
    }
    @Test fun recordingAndOwnershipChangesWithdrawAnInputButDisplayNamesDoNot() {
        val source=preparationSource(listOf(first,second));val request=VideoFootageRequest(first,second,setOf(first.identityKey,second.identityKey))
        val input=requireNotNull(source.input(request))
        assertEquals(input,source.copy(follows=source.follows.map {it.copy(name="A new label",personName="New label")}).input(request))
        assertNotEquals(input,source.copy(posts=listOf(first,second.copy(media=second.media.map {it.copy(url=it.url+"?revision=2")}))).input(request))
        assertNotEquals(input,source.copy(follows=source.follows.map {it.copy(enabled=false)}).input(request))
        assertNotEquals(input,source.copy(memberships=source.memberships-second.identityKey).input(request))
        assertNull(source.copy(posts=listOf(first)).input(request))
    }
    @Test fun semanticNeighborhoodChangesWithdrawPreparationButObservationNoiseDoesNot() {
        val source=preparationSource(listOf(first,second));val request=VideoFootageRequest(first,second,setOf(first.identityKey,second.identityKey))
        val input=source.input(request)
        assertNotEquals(input,source.copy(posts=listOf(first,second.copy(body="A newly withheld caption."))).input(request))
        assertNotEquals(input,preparationSource(source.posts+preparationPost("73000002",Platform.FACEBOOK,first.body)).input(request))
        assertEquals(input,preparationSource(source.posts+preparationPost("73000002",Platform.FACEBOOK,offset=7*60*60_000L)).input(request))
        assertEquals(input,source.copy(posts=source.posts.map {it.copy(replyCount=12,score=99)}).input(request))
    }
}

class FeedVideoPreparationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val first=preparationPost("71000001",Platform.TIKTOK,"First workshop film.")
    private val second=preparationPost("72000001",Platform.X,offset=1_000)
    private val source=mutableStateOf<FeedVideoPreparationSource?>(preparationSource(listOf(first,second)))
    private val initializing=mutableStateOf(true);private val showRows=mutableStateOf(false);private val blocked=mutableStateOf(false)
    private val active=mutableStateOf<Set<String>>(emptySet())
    private val filter=ReaderFilterSession(ContentFilterContext(FilterSurface.FEED),emptyList())
    private val calls=AtomicInteger();private val entered=CompletableDeferred<Unit>();private val finish=CompletableDeferred<Unit>();private val cancelled=CompletableDeferred<Unit>()
    private val recordings=java.util.concurrent.CopyOnWriteArrayList<String>()
    private val prefixes=AtomicInteger()
    private lateinit var prefs:String
    private lateinit var sampler:suspend (Media,String)->GatewayVideoSampler.Result
    private lateinit var prefix:suspend (Media,String)->Unit
    @Before fun isolate():Unit=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        repo.awaitReady();require(repo.dao.allFollows().isEmpty()&&repo.dao.allBookmarks().isEmpty())
        val startup=ui.runOnIdle {requireNotNull(ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.coroutineContext[Job])}
        startup.cancelAndJoin();withTimeout(20_000){repo.feedRefresh.first {!it.running}}
        ui.runOnIdle {ui.activity.setContent {Text("Preparing isolated reader intent")}}
        prefs=repo.prefs.export().toString();sampler=repo.videos.sample;prefix=repo.videos.preparePrefix
        repo.prefs.videoDownloadWifiOnly=false;repo.videos.cleanup(true);repo.videos.playback(false)
        repo.videos.preparePrefix={_,_->prefixes.incrementAndGet();Unit}
        repo.videos.sample={media,_->
            recordings+=media.url
            calls.incrementAndGet()
            if(media.url==first.media.single().url)try {entered.complete(Unit);finish.await()}catch(e:CancellationException){cancelled.complete(Unit);throw e}
            val scenes=(0..3).map {p->val pixels=ByteArray(4096){i->var n=(p+1)*7919+(i%64/4)*31+(i/64/4)*1009;n=(n xor(n ushr 13))*1274126177;((n ushr 16 and 191)+32).toByte()}
                SampledVideoScene(p,requireNotNull(VisualFrames.encode(1.5,40.0,0L,0L,List(48){128},pixels)))}
            GatewayVideoSampler.Result(SampledVideoFrames(120_000,scenes),1024,1,media.url)
        }
    }
    @After fun restore() {
        if(::prefs.isInitialized) {
            ui.runOnIdle {ui.activity.setContent {Text("Reader intent fixture complete")}}
            repo.videos.cleanup(true);repo.videos.sample=sampler;repo.videos.preparePrefix=prefix;repo.videos.playback(false);repo.prefs.restore(JSONObject(prefs));finish.complete(Unit)
        }
    }
    private fun show() {
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {CompositionLocalProvider(LocalFeedVideoPreparation provides source.value) {
            val list=rememberLazyListState()
            val rows=if(showRows.value)listOf(FrozenFeedRow(first.identityKey,listOf(first.identityKey),listOf(first)))else emptyList()
            Column(Modifier.fillMaxSize()) {
                FeedVideoPreparation(list,listOf(first),FeedMemory(initialized=true,anchorKey=first.identityKey),true,initializing.value,rows,filter,blocked.value,repo) {active.value=it}
                LazyColumn(Modifier.fillMaxSize().testTag("Intent feed"),state=list) {
                    if(showRows.value) {
                        item(key=first.identityKey) {Column(Modifier.fillMaxWidth().height(420.dp).testTag("Intent row")) {
                            val request=VideoFootageRequest(first,second,setOf(first.identityKey,second.identityKey))
                            CompositionLocalProvider(LocalVideoPreparationPending provides (first.identityKey in active.value)) {
                                FeedVideoDwell(list,first.identityKey,first,true,request,repo)
                            }
                            Text("Public workshop")
                        }}
                        items(8){Text("Another public announcement",Modifier.fillMaxWidth().height(420.dp))}
                    }
                }
            }
        }}}}
    }
    private fun waitForPreparation(){ui.waitUntil(15_000){prefixes.get()==1};assertEquals(0,calls.get());assertTrue(first.identityKey in active.value)}
    private fun waitForSample(){ui.waitUntil(15_000){entered.isCompleted};assertEquals(1,calls.get());assertTrue(first.identityKey in active.value)}
    private fun revealRows() {
        ui.runOnIdle {showRows.value=true}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Intent row").fetchSemanticsNodes().isNotEmpty()}
        ui.waitForIdle();ui.runOnIdle {initializing.value=false}
    }
    @Test fun onlyThePrefixBeginsBeforeLayoutAndFramesWaitForTheExpectedCard():Unit=runBlocking {
        show();waitForPreparation();assertFalse(showRows.value)
        revealRows();waitForSample();delay(1500);assertFalse(cancelled.isCompleted);assertEquals(1,calls.get())
        finish.complete(Unit);ui.waitUntil(10_000){calls.get()==2&&active.value.isEmpty()}
        delay(1500);assertEquals(2,calls.get());assertEquals(1,prefixes.get());assertNotNull(repo.videos.captured(first).media.single().videoFootage);assertNotNull(repo.videos.captured(second).media.single().videoFootage)
    }
    @Test fun overlayCancellationDoesNotRestartAnInvisiblePreparationOnDismissal():Unit=runBlocking {
        show();waitForPreparation();ui.runOnIdle {blocked.value=true};ui.waitUntil(10_000){active.value.isEmpty()}
        ui.runOnIdle {blocked.value=false};delay(1500);assertEquals(0,calls.get());assertEquals(1,prefixes.get());assertNull(repo.videos.captured(first).media.single().videoFootage)
    }
    @Test fun draggingTheActualListWithdrawsPreparation():Unit=runBlocking {
        show();waitForPreparation();revealRows();waitForSample()
        ui.onNodeWithTag("Intent feed").performTouchInput {swipeUp()}
        ui.waitUntil(10_000){cancelled.isCompleted&&active.value.isEmpty()};delay(500);assertEquals(1,calls.get())
    }
    @Test fun aChangedPeerRecordingCancelsOnlyThePreparedWork():Unit=runBlocking {
        show();waitForPreparation();revealRows();waitForSample();ui.runOnIdle {source.value=source.value!!.copy(posts=listOf(first,second.copy(media=second.media.map {it.copy(url=it.url+"?edition=2")})))}
        ui.waitUntil(10_000){cancelled.isCompleted&&active.value.isEmpty()};assertEquals(1,calls.get())
    }
    @Test fun withdrawnOwnershipCancelsThePreparedPair():Unit=runBlocking {
        show();waitForPreparation();ui.runOnIdle {source.value=source.value!!.copy(memberships=source.value!!.memberships-second.identityKey)}
        ui.waitUntil(10_000){active.value.isEmpty()};assertEquals(0,calls.get());assertEquals(1,prefixes.get())
    }
    @Test fun aRepeatFilterIsCheckedOnTheKnownCaptionGroupBeforeSampling():Unit=runBlocking {
        val repeat=preparationPost("73000001",Platform.FACEBOOK,first.body,offset=500)
        source.value=preparationSource(listOf(first,repeat,second))
        filter.apply(listOf(ContentRule("repeat","Keep repeats folded",repeatsOnly=true)))
        show()
        try {ui.waitUntil(3000){prefixes.get()>0};fail("A folded caption group must not start speculative prefix reads")}
        catch(_:ComposeTimeoutException) { /* Pump real frames while expecting no work. */ }
        assertEquals(0,calls.get());assertEquals(0,prefixes.get());assertTrue(active.value.isEmpty())
        // Prove this same neighborhood actually has useful eligible work;
        // otherwise a negative-only assertion can pass without running a plan.
        ui.runOnIdle {ui.activity.setContent {Text("Resetting filter control")};filter.apply(emptyList());finish.complete(Unit)}
        ui.waitForIdle();show();waitForPreparation();revealRows();ui.waitUntil(15_000){calls.get()>=2&&active.value.isEmpty()}
        assertEquals("Prepare the actual visible recording and its peer",setOf(first.media.single().url,second.media.single().url),recordings.toSet())
        assertEquals(2,calls.get());assertEquals(1,prefixes.get())
        assertNotNull(repo.videos.captured(first).media.single().videoFootage)
        assertNotNull(repo.videos.captured(second).media.single().videoFootage)
    }
    @Test fun stoppingTheActivityCancelsWithoutWaitingForRecomposition():Unit=runBlocking {
        show();waitForPreparation()
        try {ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED);withTimeout(5_000){while(active.value.isNotEmpty())delay(10)}}
        finally {ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)}
        ui.waitForIdle();delay(500);assertTrue(active.value.isEmpty());assertEquals(0,calls.get());assertEquals(1,prefixes.get())
    }
    @Test fun ordinaryCachePurgeRetiresPreparationAndItsClaim():Unit=runBlocking {
        show();waitForPreparation();repo.videos.cleanup(true)
        ui.waitUntil(10_000){active.value.isEmpty()};assertEquals(0,calls.get());assertEquals(1,prefixes.get());assertEquals(0,repo.videos.bytes())
    }
    @Test fun stoppingTheActivityAlsoCancelsAStalledPrefixRead():Unit=runBlocking {
        val reading=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>()
        repo.videos.preparePrefix={_,_->prefixes.incrementAndGet();try {reading.complete(Unit);awaitCancellation()}finally {stopped.complete(Unit)}}
        show();ui.waitUntil(15_000){reading.isCompleted};assertEquals(0,calls.get())
        try {ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED);withTimeout(5_000){stopped.await()}}
        finally {ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)}
        ui.waitUntil(5_000){active.value.isEmpty()};assertEquals(1,prefixes.get());assertEquals(0,calls.get())
    }
    @Test fun aChangedPeerCaptionWithdrawsThePrefixBeforeAnyFramesAreRead():Unit=runBlocking {
        show();waitForPreparation()
        ui.runOnIdle {source.value=source.value!!.copy(posts=listOf(first,second.copy(body="Newly withheld material.")))}
        ui.waitUntil(5_000){active.value.isEmpty()};revealRows();delay(500)
        // Ordinary dwell may now act on the newly rendered card. The cancelled
        // preparation itself must not turn its old caption into a source read.
        assertEquals(1,prefixes.get());assertNull(repo.videos.captured(second).media.single().videoFootage)
    }
    @Test fun initializationAndLayoutCanHandOffWithinTheSameFrame():Unit=runBlocking {
        show();waitForPreparation()
        ui.runOnIdle {showRows.value=true;initializing.value=false}
        waitForSample();finish.complete(Unit)
        ui.waitUntil(10_000){calls.get()==2&&active.value.isEmpty()};assertEquals(1,prefixes.get())
    }
}
