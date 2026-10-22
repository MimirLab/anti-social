/* AntiSocial, GPL-3.0-or-later. Quiet, local protection maintenance. */
package app.antisocial.android

import android.content.Context
import androidx.work.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

internal enum class FilterUpdateReason { USER, AUTOMATIC, RETRY }

/** Only the filter subsystem owns this directory. Call under its writer mutex.
 * Current compiled protection and enabled last-good source copies stay useful;
 * removed/disabled sources and interrupted writes have no remaining owner. */
internal fun pruneFilterFiles(directory:File,sourceIds:Collection<String>) {
    val keep=sourceIds.mapTo(mutableSetOf("current.bin","current.json")){Repository.hash(it)+".txt"}
    directory.listFiles().orEmpty().filter {it.isFile&&it.name !in keep}.forEach {it.delete()}
}

internal class FilterUpdateState(context:Context,name:String="filter-update-state",private val clock:()->Long=System::currentTimeMillis) {
    private val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    val lastAttempt:Long get()=prefs.getLong("attempt",0)
    fun remaining():Long {
        val at=lastAttempt;val now=clock()
        return if(at<=0||at>now)0 else (DAY-(now-at)).coerceAtLeast(0)
    }
    fun attempted(){prefs.edit().putLong("attempt",clock()).commit()}
    companion object {const val DAY=24*60*60_000L}
}

/** Independent of subscription refresh and route probes. Android may defer
 * background work; the existing compiled protection stays active throughout. */
class FilterUpdateWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val repo=(applicationContext as AntiSocialApplication).repo
        fun retry()=if(runAttemptCount>=2)Result.success()else Result.retry()
        return try {
            repo.awaitReady()
            if(repo.gateway.hasForegroundRequests)return retry()
            val complete=repo.gateway.deferredRead {
                repo.refreshFilters(if(runAttemptCount==0)FilterUpdateReason.AUTOMATIC else FilterUpdateReason.RETRY)
            }
            if(complete)Result.success()else retry()
        }catch(e:CancellationException){currentCoroutineContext().ensureActive();retry()}
         catch(_:Exception){retry()}
    }
}

internal fun scheduleFilterUpdates(context:Context) {
    val delay=FilterUpdateState(context).remaining().coerceAtLeast(15*60_000L)
    val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
    val request=PeriodicWorkRequestBuilder<FilterUpdateWorker>(24,TimeUnit.HOURS)
        .setInitialDelay(delay,TimeUnit.MILLISECONDS).setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("filter-updates-daily",ExistingPeriodicWorkPolicy.KEEP,request)
}
