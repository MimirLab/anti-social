package app.antisocial.android

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import app.antisocial.core.NetworkGateway
import app.antisocial.core.NetworkPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.torproject.jni.TorService

private const val TOR_STATUS=1
private const val TOR_STOP=2

/** A fresh private process owns each native Tor run; native globals are never reused. */
class AntiSocialTorService: TorService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val queries=Mutex()
    private val messenger=Messenger(Handler(Looper.getMainLooper()) {request->
        when(request.what) {
            TOR_STATUS->{
                // Android recycles Message after this handler returns. Capture
                // its fields before dispatching the control query to IO.
                val epoch=request.arg1;val recipient=request.replyTo
                scope.launch {queries.withLock {
                val phase=runCatching {if(torControlConnection!=null)getInfo("status/bootstrap-phase")else null}.getOrNull().orEmpty()
                val progress=Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val reply=Message.obtain(null,TOR_STATUS,epoch,0).apply {data=Bundle().apply {putInt("progress",progress);putInt("port",if(torControlConnection!=null)socksPort else 0)}}
                runCatching {recipient?.send(reply)}
            }}}
            TOR_STOP->finishProcess()
        }
        true
    })
    override fun onCreate() {
        java.io.File(TorService.getTorrc(this).parentFile,"data/ControlSocket").delete()
        TorService.getTorrc(this).writeText("SocksPort auto IsolateSOCKSAuth\nClientOnly 1\nAvoidDiskWrites 1\nSafeSocks 1\nTestSocks 0\n")
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tor","Privacy connection",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {startForeground(81,NotificationCompat.Builder(this,"tor").setSmallIcon(app.antisocial.android.R.drawable.ic_antisocial).setContentTitle("anti social privacy connection").setContentText("Tor is running for protected requests").setContentIntent(pending).setOngoing(true).build())}
        catch(e: RuntimeException){if(e.javaClass.simpleName!="ForegroundServiceStartNotAllowedException")throw e}
    }
    override fun onBind(intent: Intent): IBinder=messenger.binder
    private fun finishProcess() {
        // Ending this app-owned process releases every Tor thread/socket together.
        // Calling libtor cleanup and reinitializing it in place can destroy a mutex
        // while a surviving native worker still uses it.
        scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onDestroy() {finishProcess()}
}
data class TorState(val enabled: Boolean=false,val ready: Boolean=false,val progress: Int=0,val message: String="Direct connection",val allTraffic: Boolean=false)
/** Owns embedded Tor for two distinct consumers: optional app-wide traffic and
 * Tor-required searches. Settings' "Tor for all traffic" drives enable(enabled);
 * searches can keep their separate Tor session warm with allTraffic=false.
 * That state is not evidence the all-traffic control is absent. Both modes keep
 * Gateway's filtering/session checks and fail closed for Tor-selected requests.
 * See docs/ARCHITECTURE.md for the setting -> Repository -> Gateway path. */
class TorController(private val context: Context,private val gateway: NetworkGateway,private val scope: CoroutineScope) {
    val state=MutableStateFlow(TorState())
    private var remote: Messenger?=null
    private var connection: ServiceConnection?=null
    private var bound=false
    private var poll: Job?=null
    private var pendingBind: Job?=null
    private var stoppedAt=0L
    private var generation=0
    private var lastReply=0L
    private val replies=Messenger(Handler(Looper.getMainLooper()) {reply->
        if(reply.what==TOR_STATUS&&reply.arg1==generation&&state.value.enabled) {
            lastReply=SystemClock.elapsedRealtime()
            val progress=reply.data.getInt("progress");val port=reply.data.getInt("port")
            val ready=progress==100&&port>0
            gateway.configureTor(NetworkPolicy(true,port,ready))
            state.value=state.value.copy(ready=ready,progress=progress,message=if(ready){if(state.value.allTraffic)"Tor connected for all traffic"else "Tor connected for search engines"}else "Connecting to Tor · $progress%")
        }
        true
    })
    private fun paused(message: String) {
        if(state.value.enabled){gateway.configureTor(NetworkPolicy(tor=true));state.value=state.value.copy(ready=false,message=message)}
    }
    fun enable(enabled: Boolean,searches: Boolean=false) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            if(enabled)gateway.configure(NetworkPolicy(tor=true))
            Handler(Looper.getMainLooper()).post {enable(enabled,searches)};return
        }
        gateway.configure(if(enabled)gateway.currentPolicy(app.antisocial.core.NetworkRoute.TOR)else NetworkPolicy())
        if(enabled||searches) {
            if(state.value.enabled&&(bound||pendingBind?.isActive==true)) {
                state.value=state.value.copy(allTraffic=enabled,message=if(state.value.ready){if(enabled)"Tor connected for all traffic"else "Tor connected for search engines"}else state.value.message)
                return
            }
            val epoch=++generation
            gateway.configureTor(NetworkPolicy(tor=true));state.value=TorState(true,false,0,"Connecting to Tor",enabled)
            val callback=object: ServiceConnection {
                override fun onServiceConnected(name: ComponentName?,binder: IBinder?) {
                    if(epoch!=generation||!state.value.enabled)return
                    remote=binder?.let {Messenger(it)};lastReply=SystemClock.elapsedRealtime()
                    poll?.cancel();poll=scope.launch(Dispatchers.Main.immediate) {
                        while(isActive&&state.value.enabled&&epoch==generation) {
                            if(SystemClock.elapsedRealtime()-lastReply>10_000)paused("Tor is not responding; requests paused")
                            val request=Message.obtain(null,TOR_STATUS,epoch,0).apply {replyTo=replies}
                            try {remote?.send(request)}catch(e: RemoteException){paused("Tor disconnected; requests paused")}
                            delay(1000)
                        }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    if(epoch==generation){remote=null;poll?.cancel();paused("Tor disconnected; requests paused")}
                }
            }
            pendingBind=scope.launch(Dispatchers.Main.immediate) {
                // A quick settings change must not rebind the process which is
                // still handling the preceding stop message.
                val cooldown=1000-(SystemClock.elapsedRealtime()-stoppedAt)
                if(cooldown>0)delay(cooldown)
                if(epoch!=generation||!state.value.enabled)return@launch
                connection=callback
                try {
                    bound=context.bindService(Intent(context,AntiSocialTorService::class.java),callback,Context.BIND_AUTO_CREATE)
                    if(!bound)paused("Tor could not start; requests paused")
                }catch(e: Exception){paused("Tor could not start: ${e.message}")}
            }
        }else {
            generation++;gateway.configureTor(NetworkPolicy(tor=true));state.value=TorState();poll?.cancel();poll=null
            pendingBind?.cancel();pendingBind=null;stoppedAt=SystemClock.elapsedRealtime()
            runCatching {remote?.send(Message.obtain(null,TOR_STOP))};remote=null
            if(bound)connection?.let {runCatching {context.unbindService(it)}}
            bound=false;connection=null;context.stopService(Intent(context,AntiSocialTorService::class.java))
        }
    }
}
