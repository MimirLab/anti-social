/* anti social, GPL-3.0-or-later. Advisory cache concurrency controls. */
package app.antisocial.core

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProbeCacheWriterTest {
    private fun result(name:String,at:Long=1L)=ProbeResult(Source(Platform.X,"nitter","https://$name.example"),
        Capability.TIMELINE,HealthStatus.HEALTHY,7,at,false,"Public fixture extracted")
    private suspend fun until(block:()->Boolean)=withTimeout(5_000){while(!block())delay(1)}

    @Test fun aBusyWriteCoalescesEachKeyWithoutWaitingOrMixingItsOperationAndTransport():Unit=runBlocking {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val written=CopyOnWriteArrayList<ProbeResult>()
        val first=result("source")
        val writer=ProbeCacheWriter(scope){value->
            if(value==first){entered.complete(Unit);release.await()}
            written+=value
        }
        try {
            assertTrue(writer.offer(first));withTimeout(5_000){entered.await()}
            assertTrue(writer.offer(first.copy(checkedAt=2)));assertTrue(writer.offer(first.copy(checkedAt=3)))
            assertFalse(writer.offer(first.copy(checkedAt=2)))
            val post=first.copy(capability=Capability.POST);val tor=first.copy(tor=true)
            assertTrue(writer.offer(post));assertTrue(writer.offer(tor))
            assertTrue(written.isEmpty());release.complete(Unit)
            until {written.size==4}
            assertEquals(listOf(first,first.copy(checkedAt=3),post,tor),written.toList())
        }finally {release.complete(Unit);job.cancelAndJoin()}
    }
    @Test fun overflowDropsOnlyTheOldestPendingKeyAndKeepsOneActiveWrite():Unit=runBlocking {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val written=CopyOnWriteArrayList<ProbeResult>()
        val first=result("active");val writer=ProbeCacheWriter(scope,2){value->
            if(value==first){entered.complete(Unit);release.await()};written+=value
        }
        try {
            writer.offer(first);withTimeout(5_000){entered.await()}
            writer.offer(result("oldest"));writer.offer(result("second"));writer.offer(result("newest"))
            release.complete(Unit);until {written.size==3}
            assertEquals(listOf(first,result("second"),result("newest")),written.toList())
        }finally {release.complete(Unit);job.cancelAndJoin()}
    }
    @Test fun aFailedCacheWriteDoesNotKillTheWriterOrRetryWithoutANewObservation():Unit=runBlocking {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val failed=CompletableDeferred<Unit>();val written=CopyOnWriteArrayList<ProbeResult>();var attempts=0
        val writer=ProbeCacheWriter(scope){value->
            attempts++
            if(value.source==result("broken").source&&value.checkedAt==1L){failed.complete(Unit);throw IOException("Fixture disk error")}
            written+=value
        }
        try {
            writer.offer(result("broken"));withTimeout(5_000){failed.await()}
            writer.offer(result("ready"));until {written.size==1}
            assertEquals(2,attempts);assertEquals(listOf(result("ready")),written.toList())
            writer.offer(result("broken",2));until {written.size==2}
            assertEquals(listOf(result("ready"),result("broken",2)),written.toList())
        }finally {job.cancelAndJoin()}
    }
    @Test fun stoppingTheOwnerDropsPendingRowsAndRejectsLaterOffers():Unit=runBlocking {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val finished=CompletableDeferred<Unit>()
        val writer=ProbeCacheWriter(scope){try {entered.complete(Unit);awaitCancellation()}finally {finished.complete(Unit)}}
        writer.offer(result("active"));withTimeout(5_000){entered.await()};writer.offer(result("pending"))
        job.cancelAndJoin();assertTrue(finished.isCompleted);assertFalse(writer.offer(result("late")))
    }
    @Test fun anAlreadyStoppedOwnerCannotRetainOrWriteAnOffer():Unit=runBlocking {
        val job=SupervisorJob().also {it.cancel()};var writes=0
        val writer=ProbeCacheWriter(CoroutineScope(job+Dispatchers.IO)){writes++}
        job.join();assertFalse(writer.offer(result("late")));assertEquals(0,writes)
    }
    @Test fun oversizedMetadataNeverEntersTheOrdinaryCacheQueue():Unit=runBlocking {
        val job=SupervisorJob();val written=CopyOnWriteArrayList<ProbeResult>()
        val writer=ProbeCacheWriter(CoroutineScope(job+Dispatchers.IO)){written+=it}
        try {
            assertFalse(writer.offer(result("source").copy(detail="x".repeat(1025))))
            assertFalse(writer.offer(result("source").copy(source=result("source").source.copy(root="https://example.org/"+"x".repeat(8192)))))
            assertFalse(writer.offer(result("source").copy(source=result("source").source.copy(frontend="x".repeat(129)))))
            writer.offer(result("ready"));until {written.isNotEmpty()};assertEquals(listOf(result("ready")),written.toList())
        }finally {job.cancelAndJoin()}
    }
}
