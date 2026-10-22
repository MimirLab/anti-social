/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Cheap local eligibility checks; collecting these values never starts a transport. */
internal data class HeartbeatDeviceState(val connected: Boolean,val unmetered: Boolean,val batteryHealthy: Boolean)
internal data class HeartbeatTarget(val target: Target,val capability: Capability,val dueAt: Long)

internal object RouteHeartbeatTargets {
    fun eligible(follows: List<Follow>,selected: Set<Platform>): List<Target> = follows.asSequence()
        .filter {ReaderScope.active(it)&&it.feedUrl==null&&it.target.platform in selected&&it.target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY)}
        .map {it.target}.distinctBy {it.platform to it.canonicalUrl}.toList()
    fun permitted(device: HeartbeatDeviceState,selectedTor: Boolean,policy: NetworkPolicy): Boolean =
        device.connected&&device.unmetered&&device.batteryHealthy&&selectedTor==policy.tor&&(!selectedTor||policy.ready)
}

/** Bounded ordinary-cache state. No account names, URLs, post bodies or search terms are written. */
internal class RouteHeartbeatCache(private val file: File,private val gson: com.google.gson.Gson) {
    @Synchronized fun restore(policy: RouteHeartbeat) {
        if(!file.isFile||file.length()>MAX_BYTES)return
        runCatching {gson.fromJson(file.readText(),HeartbeatSnapshot::class.java)}.getOrNull()?.let {value->
            runCatching {policy.restore(value)}
        }
    }
    @Synchronized fun write(policy: RouteHeartbeat) {
        val bytes=gson.toJson(policy.snapshot()).toByteArray()
        if(bytes.size<=MAX_BYTES)Repository.atomic(file,bytes)
    }
    companion object {private const val MAX_BYTES=1024*1024}
}

/** One coalesced local plan and one constrained wakeup, shared by real reads and maintenance.
 * Real content deliveries do the learning; an automatic pulse only checks a due backup.
 * Background work neither starts Tor nor waits for it to become available. */
internal class RouteHeartbeatAndroid(
    private val repo: Repository,
    file: File=File(repo.context.cacheDir,"route-heartbeats.json"),
    private val clock: ()->Long=System::currentTimeMillis,
    private val followRows: suspend ()->List<FollowRow> = {repo.dao.allFollows()},
    private val scheduler: (Long?)->Unit={scheduleRouteHeartbeat(repo.context,repo.prefs,it)}
) {
    private val cache=RouteHeartbeatCache(file,repo.gson)
    private val updates=Channel<Unit>(Channel.CONFLATED)
    private val mutex=Mutex()
    private var quietRead: Job?=null
    private val updatesJob: Job
    @Volatile private var restored=false
    internal var deviceState: ()->HeartbeatDeviceState={readDeviceState(repo.context)}
    internal var execute: suspend (Target,Capability)->Unit={target,capability->repo.pool.maintain(target,capability,winner=null,originalFallback=repo.prefs.originalFallback)}

    init {
        updatesJob=repo.scope.launch {
            for(signal in updates) {
                delay(750)
                while(updates.tryReceive().isSuccess){}
                if(restored)runCatching {flush()}
            }
        }
    }
    internal suspend fun close() {quietRead?.cancelAndJoin();updatesJob.cancelAndJoin()}
    suspend fun restore() {
        withContext(Dispatchers.IO) {cache.restore(repo.pool.heartbeat)}
        restored=true
        changed()
    }
    fun changed() {if(restored)updates.trySend(Unit)}
    suspend fun persistReservation()=withContext(Dispatchers.IO) {cache.write(repo.pool.heartbeat)}
    /** Repeated reads coalesce until the foreground is quiet; they do not queue a sweep. */
    @Synchronized fun afterRead() {
        quietRead?.cancel()
        quietRead=repo.scope.launch {
            delay(2500)
            try {run(foreground=true)}catch(e: CancellationException){throw e}catch(_: Exception){}
        }
    }
    internal suspend fun plans(): List<HeartbeatTarget> {
        val follows=followRows().mapNotNull {row->runCatching {
            repo.gson.fromJson(row.payload,Follow::class.java).also {value->
                require(value.id.isNotBlank()&&value.target.canonicalUrl.length<=8192)
                value.target.platform.id;value.target.kind.name
            }
        }.getOrNull()}
        val targets=RouteHeartbeatTargets.eligible(follows,repo.prefs.selected.toSet())
        // Rotate an account representative without storing its identity. Shared routing scopes
        // receive one pulse, rather than one pulse for every followed author on that site.
        val offset=if(targets.isEmpty())0 else Math.floorMod(clock()/CachePolicy.DAY,targets.size.toLong()).toInt()
        val rotated=targets.drop(offset)+targets.take(offset)
        return rotated.flatMap {target->CAPABILITIES.map {target to it}}
            .distinctBy {(target,capability)->repo.pool.heartbeatScope(target,capability)}
            .mapNotNull {(target,capability)->repo.pool.maintenanceDueAt(target,capability,repo.prefs.originalFallback)?.let {HeartbeatTarget(target,capability,it)}}
            .sortedWith(compareBy<HeartbeatTarget> {it.dueAt}.thenBy {it.target.platform.id})
    }
    internal suspend fun flush()=mutex.withLock {
        withContext(Dispatchers.IO) {cache.write(repo.pool.heartbeat)}
        val next=if(repo.prefs.intervalMinutes==0)null else plans().minOfOrNull {it.dueAt}
        scheduler(next)
    }
    suspend fun run(foreground: Boolean=false) {
        repo.awaitReady()
        // WorkManager already enforces idle/unmetered/battery constraints for background
        // runs. Recheck locally for foreground piggybacking and changed transport settings.
        if(!foreground&&repo.prefs.intervalMinutes==0){scheduler(null);return}
        if(repo.pool.hasActiveReads||!permitted()) {
            if(!foreground)rescheduleDeferred()
            return
        }
        if(!mutex.tryLock())return
        try {
            val candidates=plans().filter {it.dueAt<=clock()}.take(2)
            withTimeoutOrNull(20_000) {
                for(candidate in candidates) {
                    currentCoroutineContext().ensureActive()
                    if(repo.pool.hasActiveReads||!permitted()||!foreground&&repo.prefs.intervalMinutes==0)break
                    execute(candidate.target,candidate.capability)
                }
            }
            withContext(Dispatchers.IO) {cache.write(repo.pool.heartbeat)}
            val next=if(repo.prefs.intervalMinutes==0)null else plans().minOfOrNull {it.dueAt}
            scheduler(next)
        }finally {mutex.unlock()}
    }
    private fun permitted()=RouteHeartbeatTargets.permitted(deviceState(),repo.prefs.tor,repo.gateway.currentPolicy())
    private suspend fun rescheduleDeferred() {
        val next=plans().minOfOrNull {it.dueAt}
        scheduler(next?.coerceAtLeast(clock()+DEFERRED_MS))
    }
    companion object {
        private val CAPABILITIES=listOf(Capability.FEED,Capability.TIMELINE,Capability.PROFILE)
        private const val DEFERRED_MS=30*60_000L
        fun readDeviceState(context: Context): HeartbeatDeviceState {
            val network=context.getSystemService(ConnectivityManager::class.java)
            val caps=network.getNetworkCapabilities(network.activeNetwork)
            val connected=caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
            val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
            val charging=battery?.getIntExtra(BatteryManager.EXTRA_STATUS,-1) in setOf(BatteryManager.BATTERY_STATUS_CHARGING,BatteryManager.BATTERY_STATUS_FULL)
            val low=battery?.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW,false)==true
            val saving=context.getSystemService(PowerManager::class.java).isPowerSaveMode
            val healthy=!saving&&!low&&(charging||level>=0&&scale>0&&level.toLong()*100/scale>15)
            return HeartbeatDeviceState(connected,connected&&!network.isActiveNetworkMetered,healthy)
        }
    }
}
