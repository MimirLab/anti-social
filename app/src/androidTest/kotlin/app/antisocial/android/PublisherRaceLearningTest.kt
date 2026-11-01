/* anti social, GPL-3.0-or-later. Owned native timing and recovery controls. */
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
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class PublisherRaceLearningTest {
    private fun isolated()=assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
    private fun report(name:String,value:Any) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir("validation"),"publisher-race-learning").apply {mkdirs()}
        File(dir,"$name.json").writeText(com.google.gson.Gson().toJson(value))
    }
    @Test fun aRepeatUsesTheRecentUsefulWinnerWithoutAnotherHeldRead():Unit=runBlocking {
        isolated();PublisherQueryNativeFixture(holdFirstOnly=true).use {f->
            f.knownWinner();val source=f.routes.publisherSearchSources.first()
            val before=f.learning.get(source,Capability.SEARCH,true)
            val durations=mutableListOf<Long>()
            for(phase in listOf("initial","later")) {
                val began=android.os.SystemClock.elapsedRealtime()
                val hits=withTimeout(3000){f.engine.search("$phase workshop alpha",SearchProvider.DARWIN,false)}
                assertEquals("https://publisher.fixture.invalid/alpha",hits.single().url)
                durations+=android.os.SystemClock.elapsedRealtime()-began
            }
            val later=f.requests.filter {DarwinRoutes.queryValue(URI("http://${it.host}${it.path}"),"q")=="later workshop alpha"}
            report("repeat",mapOf("observedIndexTimesMs" to durations,"laterRequests" to later,"priorSourceHealthUnchanged" to (before==f.learning.get(source,Capability.SEARCH,true)),
                "scope" to "Owned native index fixture; no physical-phone, p95 or complete-search latency claim."))
            assertEquals(before,f.learning.get(source,Capability.SEARCH,true));assertEquals(1L,f.release.count)
            assertEquals(listOf(f.hosts[1]),later.map {it.host})
        }
    }
    @Test fun twoDifferentQueryTopicsKeepTheirResultsWhenTheRecentWinnerMovesFirst():Unit=runBlocking {
        isolated();PublisherQueryNativeFixture(holdFirstOnly=true).use {f->
            f.knownWinner()
            for(phase in listOf("initial","later")) {
                val hits=coroutineScope {listOf("alpha","beta").map {edition->async(Dispatchers.IO) {
                    withTimeout(3500){f.engine.search("$phase workshop $edition",SearchProvider.DARWIN,false)}
                }}.awaitAll()}
                assertEquals(listOf("https://publisher.fixture.invalid/alpha","https://publisher.fixture.invalid/beta"),hits.map {it.single().url})
            }
            val later=f.requests.filter {DarwinRoutes.queryValue(URI("http://${it.host}${it.path}"),"q")?.startsWith("later ")==true}
            report("two-topics",mapOf("laterRequests" to later,"bothOriginalTopicsPreserved" to true))
            assertEquals(2,later.size);assertTrue(later.all {it.host==f.hosts[1]})
        }
    }
    @Test fun wholeLookupCancellationDoesNotProduceAFalseSlowSourceReceipt():Unit=runBlocking {
        isolated();PublisherQueryNativeFixture().use {f->
            f.knownWinner();val source=f.routes.publisherSearchSources.first()
            val before=f.learning.get(source,Capability.SEARCH,true);val score=f.learning.score(source,Capability.SEARCH,true)
            val pending=launch(Dispatchers.IO){f.engine.search("initial workshop alpha",SearchProvider.DARWIN,false)}
            withTimeout(2500){while(f.requests.size<2)delay(10)}
            pending.cancelAndJoin()
            assertEquals(before,f.learning.get(source,Capability.SEARCH,true));assertEquals(score,f.learning.score(source,Capability.SEARCH,true),0.0)
            assertTrue(f.learning.eligible(source,Capability.SEARCH,true))
            report("cancelled",mapOf("healthAndScoreUnchanged" to true,"requests" to f.requests.toList()))
        }
    }
    @Test fun timingHintsExpireWithoutPersistingAnOutcome():Unit=runBlocking {
        isolated();val now=AtomicLong(System.currentTimeMillis())
        PublisherQueryNativeFixture(holdFirstOnly=true,clock=now::get).use {f->
            f.knownWinner();val source=f.routes.publisherSearchSources.first()
            val before=f.learning.get(source,Capability.SEARCH,true)!!;val normal=f.learning.score(source,Capability.SEARCH,true)
            withTimeout(3000){f.engine.search("initial workshop alpha",SearchProvider.DARWIN,false)}
            assertEquals(before,f.learning.get(source,Capability.SEARCH,true))
            assertTrue(f.learning.score(source,Capability.SEARCH,true)>normal)
            val restored=AdaptiveRouting(now::get);restored.restore(f.learning.snapshot())
            assertEquals(normal,restored.score(source,Capability.SEARCH,true),0.0)
            now.addAndGet(30_001)
            assertEquals(normal,f.learning.score(source,Capability.SEARCH,true),0.0)
            assertTrue(f.learning.eligible(source,Capability.SEARCH,true))
            report("expired",mapOf("restoredNormalScore" to true,"expiredAfterMs" to 30_001,"healthUnchanged" to true))
        }
    }
}
