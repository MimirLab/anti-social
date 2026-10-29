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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

class VideoDwellTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var prefs:String
    private lateinit var originalSampler:suspend (Media,String)->GatewayVideoSampler.Result
    private val at=System.currentTimeMillis()
    private val first=Post("710001",Platform.TIKTOK,"https://www.tiktok.com/@publicstudio/video/710001","Public studio","First film.",publishedAt=at,
        media=listOf(Media("https://media.fixture.invalid/first.mp4","video",durationMs=120_000)))
    private val second=Post("710002",Platform.X,"https://x.com/publicstudio/status/710002","Public studio","A different caption.",publishedAt=at+1_000,
        media=listOf(Media("https://media.fixture.invalid/second.mp4","video",durationMs=120_000)))
    private val request get()=VideoFootageRequest(first,second,setOf(CrossPosts.originalKey(first),CrossPosts.originalKey(second)))
    @Before fun isolate()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        repo.awaitReady();require(repo.dao.allFollows().isEmpty()&&repo.dao.allBookmarks().isEmpty())
        prefs=repo.prefs.export().toString();originalSampler=repo.videos.sample
        repo.prefs.videoDownloadWifiOnly=false;repo.videos.playback(false);repo.videos.cleanup(true)
    }
    @After fun restore() {
        if(::prefs.isInitialized) {
            val retained=repo
            ui.runOnIdle {ui.activity.setContent {Text("Video scheduling fixture complete")}}
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
    private fun show(catalog:State<Int>,offered:State<VideoFootageRequest?>,paused:State<Boolean>,lookup:VideoRequestLookup,counterparts:State<Map<String,List<Post>>> =mutableStateOf(emptyMap())) {
        ui.runOnUiThread {ui.activity.setContent {
            val resolver=remember(catalog.value) {
                val revision=catalog.value
                val current:VideoRequestLookup={key,excluded->check(revision>=0);lookup(key,excluded)}
                current
            }
            MaterialTheme {CompositionLocalProvider(LocalVideoRequestLookup provides resolver,LocalArchiveChecksPaused provides paused.value,LocalPostCounterparts provides counterparts.value) {
                val list=rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(),state=list) {
                    item(key=CrossPosts.originalKey(first)) {Column(Modifier.fillMaxWidth().height(320.dp)) {
                        FeedVideoDwell(list,CrossPosts.originalKey(first),first,true,offered.value,repo)
                        Text("Public workshop film")
                    }}
                }
            }}
        }}
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

}
