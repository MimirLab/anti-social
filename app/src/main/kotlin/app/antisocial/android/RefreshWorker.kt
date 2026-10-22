package app.antisocial.android

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

class RefreshWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as AntiSocialApplication).repo
        return try {repo.refreshWhenConnected();repo.cleanup();Result.success()}catch(e: TimeoutCancellationException){Result.retry()}catch(e: CancellationException){throw e}catch(_: Exception){Result.retry()}
    }
}
/** Idle/charging maintenance uses budgeted checks of actual subscriptions; the
 * full capability sweep remains an explicit source-details action. */
class MaintenanceWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as AntiSocialApplication).repo
        return try {repo.awaitReady();repo.probeSelected();repo.cleanup();Result.success()}catch(e: CancellationException){throw e}catch(_: Exception){Result.retry()}
    }
}
/** A constrained, best-effort pulse. Connection unavailability is deferred by the
 * adaptive scheduler, rather than retried in a background reconnect loop. */
class RouteHeartbeatWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        RouteHeartbeatScheduling.started()
        val repo=(applicationContext as AntiSocialApplication).repo
        return try {repo.routeHeartbeat.run();Result.success()}
        catch(e: CancellationException){throw e}
        catch(_: Exception){scheduleRouteHeartbeat(applicationContext,repo.prefs,System.currentTimeMillis()+30*60_000L);Result.success()}
        finally {RouteHeartbeatScheduling.finished(applicationContext,repo.prefs)}
    }
}

private object RouteHeartbeatScheduling {
    private var scheduledAt: Long?=null
    private var running=false
    private var pendingDueAt: Long?=null
    @Synchronized fun started(){scheduledAt=null;running=true;pendingDueAt=null}
    @Synchronized fun finished(context: Context,prefs: Preferences) {
        running=false
        val next=pendingDueAt;pendingDueAt=null
        if(next!=null&&prefs.intervalMinutes!=0)enqueue(context,next,ExistingWorkPolicy.APPEND_OR_REPLACE)
    }
    @Synchronized fun update(context: Context,prefs: Preferences,dueAt: Long?) {
        if(running){pendingDueAt=dueAt.takeIf {prefs.intervalMinutes!=0};return}
        val work=WorkManager.getInstance(context)
        if(prefs.intervalMinutes==0||dueAt==null){scheduledAt=null;work.cancelUniqueWork("route-heartbeat");return}
        enqueue(context,dueAt,ExistingWorkPolicy.REPLACE)
    }
    private fun enqueue(context: Context,dueAt: Long,policy: ExistingWorkPolicy) {
        val work=WorkManager.getInstance(context)
        val now=System.currentTimeMillis();val at=dueAt.coerceAtLeast(now+15*60_000L)
        if(scheduledAt?.let {it>now+60_000&&kotlin.math.abs(it-at)<60_000}==true)return
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true).setRequiresDeviceIdle(true).build()
        val request=OneTimeWorkRequestBuilder<RouteHeartbeatWorker>().setInitialDelay(at-now,TimeUnit.MILLISECONDS).setConstraints(constraints).build()
        scheduledAt=at
        work.enqueueUniqueWork("route-heartbeat",policy,request)
    }
}
internal fun scheduleRouteHeartbeat(context: Context,prefs: Preferences,dueAt: Long?)=RouteHeartbeatScheduling.update(context,prefs,dueAt)
fun scheduleRefresh(context: Context,prefs: Preferences) {
    val work=WorkManager.getInstance(context)
    scheduleFilterUpdates(context)
    if(prefs.intervalMinutes==0)work.cancelUniqueWork("feed-refresh") else {
        val constraints=Constraints.Builder().setRequiredNetworkType(if(prefs.wifiOnly)NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        val interval=prefs.intervalMinutes.coerceAtLeast(15).toLong()
        work.enqueueUniquePeriodicWork("feed-refresh",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<RefreshWorker>(interval,TimeUnit.MINUTES).setInitialDelay(interval,TimeUnit.MINUTES).setConstraints(constraints).build())
    }
    work.enqueueUniquePeriodicWork("maintenance",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<MaintenanceWorker>(24,TimeUnit.HOURS).setInitialDelay(24,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).build()).build())
    if(prefs.intervalMinutes==0)scheduleRouteHeartbeat(context,prefs,null)
    runCatching {(context.applicationContext as? AntiSocialApplication)?.repo?.routeHeartbeat?.changed()}
}
