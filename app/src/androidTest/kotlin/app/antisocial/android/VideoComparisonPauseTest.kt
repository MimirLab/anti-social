/* anti social, GPL-3.0-or-later. Resource deferral must not spend retrieval work. */
package app.antisocial.android

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class VideoComparisonPauseTest {
    @Test fun anObsoleteDeferredCandidateReturnsWithoutAcquisitionOrFailureLearning()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val directory=File(context.cacheDir,"comparison-pause-${UUID.randomUUID()}")
        val bank=VideoEvidenceStore(repo,directory=directory);bank.resourcePause={VideoComparisonPause.WIFI}
        val current=AtomicBoolean(true);val entered=CompletableDeferred<Unit>();val reasons=java.util.concurrent.CopyOnWriteArrayList<VideoComparisonPause?>()
        val job=async {bank.awaitAutomatic({current.get()}){reasons+=it;if(it!=null)entered.complete(Unit)}}
        withTimeout(5_000){entered.await()};assertFalse(job.isCompleted);assertFalse(directory.exists())
        current.set(false);assertFalse(withTimeout(5_000){job.await()});assertNull(reasons.last());assertFalse(directory.exists())
    }
    @Test fun cancellingADeferredCardClearsItsPauseAndLeavesNoPendingResourceJob()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val directory=File(context.cacheDir,"comparison-pause-${UUID.randomUUID()}")
        val bank=VideoEvidenceStore(repo,directory=directory);bank.resourcePause={VideoComparisonPause.THERMAL}
        val entered=CompletableDeferred<Unit>();val reasons=java.util.concurrent.CopyOnWriteArrayList<VideoComparisonPause?>()
        val job=launch {bank.awaitAutomatic({true}){reasons+=it;if(it!=null)entered.complete(Unit)}}
        withTimeout(5_000){entered.await()};job.cancelAndJoin();assertTrue(job.isCancelled);assertNull(reasons.last());assertFalse(directory.exists())
    }
}
