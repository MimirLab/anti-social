/* anti social, GPL-3.0-or-later. Synthetic evidence and disposable native cache. */
package app.antisocial.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class VideoEvidenceStoreTest {
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo get()=(context.applicationContext as AntiSocialApplication).repo
    private lateinit var directory:File
    private val at=System.currentTimeMillis()
    private val first=Post("710001",Platform.X,"https://x.com/publicstudio/status/710001","Public studio","A workshop demonstration.",publishedAt=at,
        media=listOf(Media("https://video.twimg.com/amplify_video/710001/vid/1920x1080/main.mp4","video",durationMs=120_000)))
    private val second=Post("710002",Platform.FACEBOOK,"https://www.facebook.com/publicstudio/posts/710002","Public studio","Try the complete process in our public studio.",publishedAt=at+60_000,
        media=listOf(Media("https://media.fixture.invalid/studio.mp4","video",durationMs=120_000)))
    private val request get()=VideoFootageRequest(first,second,setOf(CrossPosts.originalKey(first),CrossPosts.originalKey(second)))
    @Before fun isolate()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        repo.awaitReady();directory=File(context.cacheDir,"video-evidence-fixture-${UUID.randomUUID()}")
    }
    @After fun remove(){if(::directory.isInitialized)directory.deleteRecursively()}
    private fun frames()=SampledVideoFrames(120_000,(0..3).map {p->
        val detail=ByteArray(4096){i->var n=(p+1)*7919+(i%64/4)*31+(i/64/4)*1009;n=(n xor(n ushr 13))*1274126177;((n ushr 16 and 191)+32).toByte()}
        SampledVideoScene(p,requireNotNull(VisualFrames.encode(1.5,40.0,0L,0L,List(48){128},detail)))
    })
    private fun result(media:Media)=GatewayVideoSampler.Result(frames(),1024,1,VideoAssetIdentity.sampleUrl(media))
    private fun store(clock:()->Long=System::currentTimeMillis)=VideoEvidenceStore(repo,clock,directory)
    @Test fun preemptedRetriesShareOnlyTheirRecordingAttemptAndEveryAttemptCloses():Unit=runBlocking {
        val bank=store();val attempts=linkedMapOf<String,MutableList<VideoSamplingAttempt>>()
        bank.sample={m,_->
            val attempt=requireNotNull(currentCoroutineContext()[VideoSamplingAttempt])
            val seen=attempts.getOrPut(m.url){mutableListOf()};seen+=attempt
            if(m.url==first.media.single().url&&seen.size==1)throw CancellationException("Foreground fixture preemption")
            result(m)
        }
        assertNull(currentCoroutineContext()[VideoSamplingAttempt]);bank.enrich(request,automatic=false)
        val a=attempts.getValue(first.media.single().url);val b=attempts.getValue(second.media.single().url)
        assertEquals(2,a.size);assertEquals(1,b.size);assertSame(a[0],a[1]);assertNotSame(a[0],b[0])
        for(attempt in listOf(a[0],b[0])) {
            assertEquals(0,attempt.retainedBlocks)
            assertThrows(IllegalStateException::class.java){runBlocking {attempt.ranges(first.media.single(),first.canonicalUrl){error("Closed attempt must not open a view")}}}
        }
        assertNull(currentCoroutineContext()[VideoSamplingAttempt])
        assertNotNull(bank.captured(first).media.single().videoFootage);assertNotNull(bank.captured(second).media.single().videoFootage)
    }
    @Test fun runningVideoComparisonOwnsTheKeyObservedByItsCard():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val bank=store()
        bank.sample={m,_->entered.complete(Unit);finish.await();result(m)}
        val task=launch {bank.enrich(request,automatic=false)}
        try {
            withTimeout(10_000){entered.await()}
            assertTrue(repo.cardWork.activity(setOf(postWorkKey(first))).first().contains("Comparing video editions"))
        }finally {task.cancelAndJoin();finish.complete(Unit)}
        assertTrue(repo.cardWork.activity(setOf(postWorkKey(first))).first().isEmpty())
    }
    @Test fun queuedComparisonIsVisibleAndPurgingRetiresItBeforeItCanSample():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val calls=AtomicInteger();val bank=store()
        bank.sample={m,_->calls.incrementAndGet();entered.complete(Unit);finish.await();result(m)}
        val changed=first.copy(id="710003",canonicalUrl="https://x.com/publicstudio/status/710003")
        val pending=VideoFootageRequest(changed,second,setOf(changed.identityKey,second.identityKey))
        val firstJob=launch {bank.enrich(request,automatic=false)}
        var queued:Job?=null
        try {
            withTimeout(10_000){entered.await()}
            queued=launch {bank.enrich(pending,automatic=false)}
            withTimeout(5_000){repo.cardWork.activity(setOf(postWorkKey(changed))).first {"Comparing video editions" in it}}
            bank.cleanup(true)
            withTimeout(5_000){firstJob.join();queued.join()}
            assertTrue(firstJob.isCancelled);assertTrue(queued.isCancelled);assertEquals(1,calls.get());assertEquals(0,bank.bytes())
        }finally {firstJob.cancelAndJoin();queued?.cancelAndJoin();finish.complete(Unit)}
        assertTrue(repo.cardWork.activity(setOf(postWorkKey(changed))).first().isEmpty())
    }

    @Test fun repeatedAndRestartedReadersReuseBoundedObservationsWithoutFetchingAgain():Unit=runBlocking {
        val calls=AtomicInteger();val bank=store();bank.sample={m,_->calls.incrementAndGet();result(m)}
        bank.enrich(request,automatic=false);assertEquals(2,calls.get());assertTrue(bank.bytes()>0)
        val captured=bank.captured(first);assertEquals(4,captured.media.single().videoFootage!!.scenes.size)
        assertEquals(first,captured.copy(media=captured.media.map {it.copy(videoFootage=null)}))
        bank.enrich(request,automatic=false);assertEquals(2,calls.get())
        val restarted=store();restarted.sample={_,_->error("An ordinary cache hit must not fetch")}
        restarted.restore();restarted.enrich(request,automatic=false)
        assertEquals(captured,restarted.captured(first));assertEquals(2,directory.listFiles()!!.count {it.extension=="json"})
        assertFalse(repo.cardWork.active.value.any {it.label=="Comparing video editions"})
    }
    @Test fun purgeCancelsAnUncooperativeOldCallbackAndANewAttemptStillWorks():Unit=runBlocking {
        val bank=store();val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val protected=repo.dao.allBookmarks()
        bank.sample={m,_->withContext(NonCancellable){entered.complete(Unit);finish.await();result(m)}}
        val old=launch {bank.enrich(request,automatic=false)}
        try {
            withTimeout(10_000){entered.await()};assertTrue(repo.cardWork.active.value.any {it.label=="Comparing video editions"})
            bank.cleanup(true);finish.complete(Unit);withTimeout(10_000){old.join()}
        }finally {finish.complete(Unit);old.cancel();withContext(NonCancellable){withTimeout(10_000){old.join()}}}
        assertTrue(old.isCancelled);assertEquals(0,bank.bytes());assertNull(bank.captured(first).media.single().videoFootage)
        assertEquals(protected,repo.dao.allBookmarks());assertFalse(repo.cardWork.active.value.any {it.label=="Comparing video editions"})
        bank.sample={m,_->result(m)};bank.enrich(request,automatic=false)
        assertNotNull(bank.captured(first).media.single().videoFootage)
    }
    @Test fun expiredAndCorruptOrdinaryFilesDoNotPoisonTheNextLookup():Unit=runBlocking {
        val now=AtomicLong(at);val bank=store(now::get);bank.sample={m,_->result(m)}
        bank.enrich(request,automatic=false);assertNotNull(bank.captured(first).media.single().videoFootage)
        now.addAndGet(7*CachePolicy.DAY+1)
        val restarted=store(now::get);restarted.restore();assertNull(restarted.captured(first).media.single().videoFootage);assertEquals(0,restarted.bytes())
        directory.mkdirs();File(directory,Repository.hash(first.media.single().url)+".json").writeText("{\"url\":null,\"evidence\":null}")
        val recovered=store(now::get);recovered.restore();assertEquals(0,recovered.bytes())
        recovered.sample={m,_->result(m)};recovered.enrich(request,automatic=false)
        assertNotNull(recovered.captured(first).media.single().videoFootage)
    }
    @Test fun aTransientFailureCanBeExplicitlyRetriedWithoutLosingTheSuccessfulOtherEdition():Unit=runBlocking {
        val bank=store();val calls=AtomicInteger()
        bank.sample={m,_->if(calls.incrementAndGet()==1)throw java.io.IOException("Transient fixture source failure");result(m)}
        bank.enrich(request,automatic=false);assertEquals(2,calls.get())
        assertNull(bank.captured(first).media.single().videoFootage);assertNotNull(bank.captured(second).media.single().videoFootage)
        bank.enrich(request,automatic=false);assertEquals(2,calls.get())
        bank.retryFailures();bank.enrich(request,automatic=false);assertEquals(3,calls.get())
        assertNotNull(bank.captured(first).media.single().videoFootage)
    }
    @Test fun cachedProviderRenditionsCannotTransferScenesToAnotherClipOrAnImage():Unit=runBlocking {
        val bank=store();bank.sample={m,_->result(m)};bank.enrich(request,automatic=false)
        val alias=first.copy(media=listOf(first.media.single().copy(url="https://video-s.twimg.com/amplify_video/710001/vid/480x270/alias.mp4")))
        assertNotNull(bank.captured(alias).media.single().videoFootage)
        assertNull(bank.captured(alias.copy(media=alias.media.map {it.copy(url=it.url.replace("710001","710009"))})).media.single().videoFootage)
        assertNull(bank.captured(alias.copy(media=alias.media.map {it.copy(type="image")})).media.single().videoFootage)
        val a=bank.captured(first);val b=bank.captured(second)
        assertNotNull(bank.related(a,b){});assertNotNull(bank.related(a,b){})
        val evidence=b.media.single().videoFootage!!
        val reverse=evidence.copy(scenes=evidence.scenes.mapIndexed {i,s->s.copy(fingerprint=evidence.scenes.reversed()[i].fingerprint)})
        assertNull(bank.related(a,b.copy(media=b.media.map {it.copy(videoFootage=reverse)})){})
        assertNotNull(bank.related(a,b){})
    }
    @Test fun playingVideoKeepsTheSamplingLaneIdleUntilPlaybackStops():Unit=runBlocking {
        val bank=store();val calls=AtomicInteger();bank.sample={m,_->calls.incrementAndGet();result(m)}
        bank.playback(true)
        val work=launch {bank.enrich(request,automatic=false)}
        try {delay(150);assertEquals(0,calls.get());bank.playback(false);withTimeout(10_000){work.join()};assertEquals(2,calls.get())}
        finally {bank.playback(false);work.cancelAndJoin()}
    }
    @Test fun legacyMetadataIsResolvedBeforeSamplingAndNotRepeatedAfterCacheOrRestart():Unit=runBlocking {
        val legacy=first.copy(media=first.media.map {it.copy(durationMs=null)})
        val pending=VideoFootageRequest(legacy,second,request.originalKeys,resolveMetadata=true)
        val small="https://video-s.twimg.com/amplify_video/710001/vid/320x180/small.mp4"
        val lookups=AtomicInteger();val samples=AtomicInteger();val bank=store()
        bank.metadata={post->assertEquals(legacy,post);lookups.incrementAndGet();first.media.single().copy(samplingUrl=small)}
        bank.sample={media,_->
            samples.incrementAndGet()
            if(media.url==first.media.single().url)assertEquals(small,media.samplingUrl)
            result(media)
        }
        bank.enrich(pending,automatic=false);assertEquals(1,lookups.get());assertEquals(2,samples.get())
        assertNotNull(bank.captured(legacy).media.single().videoFootage)
        assertNull(legacy.media.single().durationMs);assertNull(legacy.media.single().samplingUrl)
        bank.enrich(pending,automatic=false);assertEquals(1,lookups.get());assertEquals(2,samples.get())
        val restored=store();restored.metadata={error("Cached scenes must not look up metadata")};restored.sample={_,_->error("Cached scenes must not fetch")}
        restored.enrich(pending,automatic=false)
        assertNotNull(restored.captured(legacy).media.single().videoFootage)
    }
    @Test fun metadataForAnotherClipCannotStartSamplingTheRetainedVideo():Unit=runBlocking {
        val legacy=first.copy(media=first.media.map {it.copy(durationMs=null)})
        val bank=store();val samples=AtomicInteger()
        bank.metadata={first.media.single().copy(url=first.media.single().url.replace("710001","710009"))}
        bank.sample={media,_->assertNotEquals(first.media.single().url,media.url);samples.incrementAndGet();result(media)}
        bank.enrich(VideoFootageRequest(legacy,second,request.originalKeys,resolveMetadata=true),automatic=false)
        assertEquals(1,samples.get());assertNull(bank.captured(legacy).media.single().videoFootage)
        assertNotNull(bank.captured(second).media.single().videoFootage)
    }
    @Test fun purgeDuringMetadataCannotStartASamplerFromAnObsoleteCallback():Unit=runBlocking {
        val legacy=first.copy(media=first.media.map {it.copy(durationMs=null)})
        val bank=store();val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val samples=AtomicInteger()
        bank.metadata={withContext(NonCancellable){entered.complete(Unit);finish.await();first.media.single()}}
        bank.sample={m,_->samples.incrementAndGet();result(m)}
        val work=launch {bank.enrich(VideoFootageRequest(legacy,second,request.originalKeys,resolveMetadata=true),automatic=false)}
        try {
            withTimeout(10_000){entered.await()};bank.cleanup(true);finish.complete(Unit);withTimeout(10_000){work.join()}
            assertTrue(work.isCancelled);assertEquals(0,samples.get());assertEquals(0,bank.bytes())
        }finally {finish.complete(Unit);work.cancel();withContext(NonCancellable){withTimeout(10_000){work.join()}}}
    }
    @Test fun aNewAdvertisedRenditionIsNotBlockedByTheOldInputFailureCooldown():Unit=runBlocking {
        val bank=store();val samples=AtomicInteger()
        bank.metadata={error("An advertised rendition does not need metadata lookup")}
        bank.sample={m,_->samples.incrementAndGet();if(m.url==first.media.single().url&&m.samplingUrl==null)throw java.io.IOException("Old rendition failed");result(m)}
        bank.enrich(request,automatic=false);assertEquals(2,samples.get());assertNull(bank.captured(first).media.single().videoFootage)
        val improved=first.copy(media=first.media.map {it.copy(samplingUrl="https://video-s.twimg.com/amplify_video/710001/vid/320x180/small.mp4")})
        bank.enrich(VideoFootageRequest(improved,second,request.originalKeys),automatic=false)
        assertEquals(3,samples.get());assertNotNull(bank.captured(first).media.single().videoFootage)
    }
    @Test fun aCoolingPeerCannotHideAnotherAvailableComparisonAndExplicitRetryRestoresIt():Unit=runBlocking {
        val bank=store();val samples=AtomicInteger()
        bank.sample={m,_->samples.incrementAndGet();if(m.url==second.media.single().url)throw java.io.IOException("Unavailable peer");result(m)}
        assertTrue(bank.available(request));val before=bank.revision.value
        bank.enrich(request,automatic=false)
        assertTrue(bank.revision.value>before);assertFalse(bank.available(request))
        val other=second.copy(id="710003",canonicalUrl="https://www.facebook.com/publicstudio/posts/710003",media=second.media.map {it.copy(url="https://media.fixture.invalid/other.mp4")})
        val alternate=VideoFootageRequest(first,other,setOf(CrossPosts.originalKey(first),CrossPosts.originalKey(other)))
        assertTrue(bank.available(alternate));bank.enrich(alternate,automatic=false)
        assertEquals(3,samples.get());assertNotNull(bank.captured(other).media.single().videoFootage)
        assertFalse(bank.available(alternate));assertFalse(bank.available(request))
        val failedRevision=bank.revision.value;bank.retryFailures()
        assertTrue(bank.revision.value>failedRevision);assertTrue(bank.available(request))
    }
}
