/* AntiSocial, GPL-3.0-or-later. User-requested work and bounded optional maintenance. */
package app.antisocial.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object ContextNotifications {
    const val PROGRESS=1741
    private const val WORK="context-work"
    private const val READY="context-ready"
    fun channels(context: Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(WORK,"Context processing",NotificationManager.IMPORTANCE_LOW).apply {setShowBadge(false)})
        manager.createNotificationChannel(NotificationChannel(READY,"Context ready",NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun open(context: Context,id: String)=PendingIntent.getActivity(context,id.hashCode(),Intent(context,MainActivity::class.java).putExtra("openContext",id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun progress(context: Context,id: String,message: String): Notification {
        channels(context)
        val stop=PendingIntent.getService(context,id.hashCode(),Intent(context,ContextJobService::class.java).setAction("stop").putExtra("contextId",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context,WORK).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Preparing Context")
            .setContentText(message).setContentIntent(open(context,id)).setOngoing(true).setOnlyAlertOnce(true).setProgress(0,0,true)
            .addAction(android.R.drawable.ic_media_pause,"Stop",stop).build()
    }
    fun ready(context: Context,record: ContextRecord) {
        channels(context)
        val text=if(record.status=="ready")record.snapshot.title.ifBlank {"Your brief is ready to read"}else if(record.status=="partial")"Ready to read · some sources were unavailable"else record.message
        val notification=NotificationCompat.Builder(context,READY).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(if(record.status in setOf("ready","partial"))"Context ready"else "Context paused")
            .setContentText(text).setContentIntent(open(context,record.id)).setAutoCancel(true).build()
        runCatching {context.getSystemService(NotificationManager::class.java).notify(record.id.hashCode(),notification)}
    }
}

class ContextJobService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs=ConcurrentHashMap<String,Job>()
    private val repo get()=(application as AntiSocialApplication).repo
    override fun onBind(intent: Intent?)=null
    override fun onCreate() {super.onCreate();ContextNotifications.channels(this)}
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val id=intent?.getStringExtra("contextId") ?: run {stopSelf(startId);return START_NOT_STICKY}
        if(intent.action=="stop"){repo.contextReader.stop(id);if(jobs.isEmpty())stopSelf();return START_NOT_STICKY}
        val notification=ContextNotifications.progress(this,id,"Gathering sources")
        if(Build.VERSION.SDK_INT>=29)startForeground(ContextNotifications.PROGRESS,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else startForeground(ContextNotifications.PROGRESS,notification)
        if(jobs.containsKey(id))return START_NOT_STICKY
        val job=scope.launch(start=CoroutineStart.LAZY) {
            val observer=launch {repo.contextReader.store.revision.collectLatest {repo.contextReader.store.record(id)?.let {record->
                getSystemService(NotificationManager::class.java).notify(ContextNotifications.PROGRESS,ContextNotifications.progress(this@ContextJobService,id,record.message))
            }}}
            try {do {repo.contextReader.run(id)}while(repo.ai.preferences.enabled&&repo.contextReader.store.record(id)?.status=="queued")}
            catch(_: CancellationException){}
            finally {
                observer.cancel();jobs.remove(id)
                repo.contextReader.store.record(id)?.takeIf {it.status in setOf("ready","partial","paused") }?.let {ContextNotifications.ready(this@ContextJobService,it)}
                if(jobs.isEmpty()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            }
        }
        jobs[id]=job;job.start();return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int,fgsType: Int) {jobs.values.forEach {it.cancel()};stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){jobs.values.forEach {it.cancel()};scope.cancel();super.onDestroy()}
}

internal object ContextMaintenance {
    fun schedule(repo: Repository) {
        val options=repo.contextReader.preferences.state.value
        val manager=WorkManager.getInstance(repo.context)
        if(!repo.ai.preferences.enabled||!options.saved){manager.cancelUniqueWork("context-maintenance");return}
        val request=PeriodicWorkRequestBuilder<ContextMaintenanceWorker>(options.refreshMinutes.toLong(),TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build()
        manager.enqueueUniquePeriodicWork("context-maintenance",ExistingPeriodicWorkPolicy.UPDATE,request)
    }
    fun recover(repo: Repository) {
        if(!repo.ai.preferences.enabled)return
        val request=OneTimeWorkRequestBuilder<ContextRecoveryWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()).build()
        WorkManager.getInstance(repo.context).enqueueUniqueWork("context-recovery",ExistingWorkPolicy.KEEP,request)
    }
}
class ContextMaintenanceWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as AntiSocialApplication).repo
        repo.contextReader.maintain();return Result.success()
    }
}
class ContextRecoveryWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as AntiSocialApplication).repo
        if(!repo.ai.preferences.enabled)return Result.success()
        val pending=repo.contextReader.store.all().filter {it.requested&&it.status in setOf("queued","reading","processing")}
        for(record in pending) {
            val notification=ContextNotifications.progress(applicationContext,record.id,"Resuming saved progress")
            setForeground(if(Build.VERSION.SDK_INT>=29)ForegroundInfo(ContextNotifications.PROGRESS,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else ForegroundInfo(ContextNotifications.PROGRESS,notification))
            do {repo.contextReader.run(record.id)}while(repo.ai.preferences.enabled&&repo.contextReader.store.record(record.id)?.status=="queued")
            repo.contextReader.store.record(record.id)?.takeIf {it.status in setOf("ready","partial","paused")}?.let {ContextNotifications.ready(applicationContext,it)}
        }
        return Result.success()
    }
}
