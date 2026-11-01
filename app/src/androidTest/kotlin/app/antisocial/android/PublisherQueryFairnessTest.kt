/* anti social, GPL-3.0-or-later. Native bounded cooperative publisher hedges. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PublisherQueryFairnessTest {
    @Test fun twoUnprovenQueriesReachAWorkingThirdEndpoint():Unit=runBlocking {checkQueries(false,false)}
    @Test fun twoQueriesEscapeAPreviouslyFastButNowHeldEndpoint():Unit=runBlocking {checkQueries(false,true)}
    @Test fun fastUnprovenQueriesKeepBothTopicsWithoutAnExtraHedge():Unit=runBlocking {checkQueries(true,false)}
    @Test fun fastLearnedQueriesKeepBothTopicsWithoutAnExtraHedge():Unit=runBlocking {checkQueries(true,true)}
    @Test fun aQueuedCompanionRetainsTheHtmlOnlyEndpoint():Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        PublisherQueryNativeFixture(htmlOnlyFirst=true).use {f->
            suspend fun lookup(edition:String)=try {Result.success(f.engine.search("workshop $edition",SearchProvider.DARWIN,false))}
                catch(e:SourceFailure){Result.failure(e)}
            val first=async(Dispatchers.IO){lookup("alpha")}
            withTimeout(2000){while(f.firstApi.count>0)delay(10)}
            val second=async(start=CoroutineStart.UNDISPATCHED){lookup("beta")}
            yield();f.apiReady.countDown()
            try {
                val results=withTimeout(4000){listOf(first,second).awaitAll()}
                assertTrue("Both topics need their retained same-endpoint HTML path: $results",results.all {it.getOrNull()?.size==1})
                assertEquals(listOf("https://publisher.fixture.invalid/alpha","https://publisher.fixture.invalid/beta"),results.map {it.getOrThrow().single().url})
                assertEquals(2,f.requests.count {it.path.startsWith("/search.php")})
                assertTrue(f.requests.all {it.host==f.hosts[0]})
                val context=InstrumentationRegistry.getInstrumentation().targetContext
                val dir=File(context.getExternalFilesDir("validation"),"publisher-query-fairness").apply {mkdirs()}
                File(dir,"html-companion.json").writeText(com.google.gson.Gson().toJson(mapOf(
                    "requests" to f.requests.toList(),"bothTopicsPreserved" to true,"htmlReads" to 2,
                    "scope" to "Owned native queued representation race; no public provider or phone latency claim.")))
            }finally {f.apiReady.countDown();first.cancel();second.cancel();joinAll(first,second)}
        }
    }

    private suspend fun checkQueries(fast:Boolean,known:Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        PublisherQueryNativeFixture(fast).use {f->
            if(known)f.knownWinner()
            val maximum=AtomicInteger()
            val started=android.os.SystemClock.elapsedRealtime()
            coroutineScope {
                val observer=launch(Dispatchers.Default) {
                    while(isActive) {
                        val active=f.gateway.client(NetworkRoute.TOR).dispatcher.runningCalls().count {!it.isCanceled()}
                        maximum.updateAndGet {maxOf(it,active)};delay(3)
                    }
                }
                try {
                    val jobs=listOf("alpha","beta").map {edition->async(Dispatchers.IO) {
                        f.engine.search("workshop $edition",SearchProvider.DARWIN,false)
                    }}
                    val hits=withTimeoutOrNull(4500){jobs.awaitAll()}
                    assertNotNull("Both query topics need a useful endpoint while earlier reads remain held",hits)
                    assertEquals(listOf("https://publisher.fixture.invalid/alpha","https://publisher.fixture.invalid/beta"),hits!!.map {it.single().url})
                    assertTrue(maximum.get()<=3);assertEquals(1L,f.release.count)
                    val indexReads=f.requests.filter {it.host in f.hosts}
                    val perQuery=indexReads.groupingBy {DarwinRoutes.queryValue(URI("http://${it.host}${it.path}"),"q")}.eachCount()
                    assertEquals(setOf("workshop alpha","workshop beta"),perQuery.keys);assertTrue(perQuery.values.all {it<=3})
                    if(fast) {
                        assertEquals(2,indexReads.size);assertTrue(indexReads.all {it.host==f.hosts[0]})
                        assertEquals(0L,f.firstTwo.count)
                        assertTrue("Fast companions must not wait for another hedge interval",android.os.SystemClock.elapsedRealtime()-started<1200)
                    }else {
                        assertEquals(2,indexReads.count {it.host==f.hosts[2]})
                        assertNull(f.learning.get(f.routes.publisherSearchSources[1],Capability.SEARCH,true))
                        assertEquals(if(known)1 else null,f.learning.get(f.routes.publisherSearchSources[0],Capability.SEARCH,true)?.samples)
                    }
                    val context=InstrumentationRegistry.getInstrumentation().targetContext
                    val dir=File(context.getExternalFilesDir("validation"),"publisher-query-fairness").apply {mkdirs()}
                    File(dir,"${if(fast)"fast"else "held"}-${if(known)"learned"else "unproven"}.json").writeText(com.google.gson.Gson().toJson(mapOf(
                        "elapsedMs" to android.os.SystemClock.elapsedRealtime()-started,"maxActiveCalls" to maximum.get(),
                        "requests" to indexReads,"perQuery" to perQuery,"bothTopicsPreserved" to true,
                        "scope" to "Owned native Gateway/SOCKS index delivery; not a live-search or physical-phone benchmark.")))
                }finally {observer.cancelAndJoin()}
            }
        }
    }
}
