package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SignalStoreTest {
    private val repo get()=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AntiSocialApplication).repo
    private lateinit var store:SourceRecordStore
    private var now=System.currentTimeMillis()
    private val request=PublicSourceRecords.paper("https://doi.org/10.1234/fixture")!!
    @Before fun prepare() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()}
        store=SourceRecordStore(repo,{now},File(repo.context.cacheDir,"signal-test-${System.nanoTime()}.json"))
    }
    @After fun cleanup(){if(::store.isInitialized)store.cleanup(true)}
    @Test fun simultaneousRequestsReuseOneReadAndExpiryDoesNotRenewOnAccess():Unit=runBlocking {
        var calls=0
        store.fetch={r,_->calls++;delay(30);PublicSourceRecord(r,emptyList(),now)}
        coroutineScope {(1..4).map {async {store.lookup(request)}}.awaitAll()}
        assertEquals(1,calls)
        now+=23*60*60_000L;store.lookup(request);assertEquals(1,calls)
        now+=2*60*60_000L;store.lookup(request);assertEquals(2,calls)
    }
    @Test fun paperResolversAndSocialPlatformInfrastructureDoNotBecomePublisherProfiles() {
        val requests=sourceRequests(listOf("https://doi.org/10.1234/paper","https://x.com/author/status/123","https://publisher.example/report"))
        assertEquals(2,requests.size)
        assertTrue(requests.any {it.kind==SourceRecordKind.PAPER})
        assertEquals(listOf("publisher.example"),requests.filter {it.kind==SourceRecordKind.WEBSITE}.map {it.identity})
    }
    @Test fun anUnavailableSourceHasABoundedRetryFloorAndDoesNotBecomeAnEmptySuccess():Unit=runBlocking {
        var calls=0
        store.fetch={r,_->calls++;PublicSourceRecord(r,emptyList(),now,false,"Unavailable")}
        assertFalse(store.lookup(request).complete)
        now+=14*60_000L;store.lookup(request);assertEquals(1,calls)
        now+=2*60_000L;store.lookup(request);assertEquals(2,calls)
    }
    @Test fun clearingDuringARunningReadPreventsThatReadFromRecreatingTheCache():Unit=runBlocking {
        val began=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();var calls=0
        store.fetch={r,_->calls++;began.complete(Unit);finish.await();PublicSourceRecord(r,emptyList(),now)}
        val job=async {store.lookup(request)};withTimeout(10_000){began.await()}
        store.cleanup(true);finish.complete(Unit);job.await()
        assertEquals(0L,store.bytes())
        store.lookup(request);assertEquals(2,calls)
    }
    @Test fun anExplicitReadDoesNotQueueBehindAutomaticWorkWaitingForTheFeed():Unit=runBlocking {
        val before=repo.feedRefresh.value;var calls=0
        repo.feedRefresh.value=before.copy(running=true)
        store.fetch={r,_->calls++;PublicSourceRecord(r,emptyList(),now)}
        val automatic=async {store.lookup(request)}
        try {
            delay(100)
            withTimeout(2000){store.lookup(request,deferred=false)}
            assertEquals(1,calls)
            repo.feedRefresh.value=before
            withTimeout(2000){automatic.await()};assertEquals(1,calls)
        }finally {repo.feedRefresh.value=before;automatic.cancelAndJoin()}
    }
}
