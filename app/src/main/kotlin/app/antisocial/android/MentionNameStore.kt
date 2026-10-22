/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class MentionNameStats(val entries: Int=0,val names: Int=0,val bytes: Long=0)
internal data class MentionLookupPolicy(val network: NetworkPolicy,val originals: Boolean)

/** Demand-driven public display names. At most one low-priority profile read
 * is in flight, and neither a composition nor a failed lookup creates a crawl.
 * The bank contains bounded current account labels, never old post text. */
class MentionNameStore internal constructor(
    private val database: MentionNameDatabase,
    private val routes: DarwinRoutes,
    private val scope: CoroutineScope,
    private val readable: ()->Boolean,
    private val lookupEnabled: ()->Boolean,
    private val busy: ()->Boolean,
    private val policy: ()->MentionLookupPolicy,
    private val prepare: suspend ()->Unit,
    private val lookup: suspend (MentionAccount)->Profile?,
    private val clock: ()->Long=System::currentTimeMillis,
    private val quietMs: Long=1_200L,
    private val pollMs: Long=150L,
    private val maxEntries: Int=MAX_ENTRIES,
    private val maxBytes: Long=MAX_BYTES
): MentionNameSource {
    constructor(repo: Repository): this(
        MentionNameDatabase.open(repo.context),repo.routes,repo.scope,
        readable={repo.prefs.readableMentions},lookupEnabled={repo.prefs.mentionLookups},
        busy={repo.pool.hasForegroundReads||repo.gateway.hasForegroundRequests||repo.feedRefresh.value.running},
        policy={MentionLookupPolicy(repo.gateway.currentPolicy(),repo.prefs.originalFallback)},
        prepare={repo.awaitReady();repo.gateway.originalFallback=repo.prefs.originalFallback},
        lookup={account->repo.gateway.deferredRead {repo.gateway.boundedHealthRead(maxBytes=512*1024L,maxRequests=8) {
            repo.pool.load(account.target,originalFallback=repo.prefs.originalFallback,
                progress={},requestedCapability=Capability.PROFILE,background=true,profileNameOnly=true).profile
        }}}
    )

    private val mutableNames=MutableStateFlow<Map<String,String>>(emptyMap())
    override val names=mutableNames.asStateFlow()
    private val mutableEnabled=MutableStateFlow(readable())
    override val enabled=mutableEnabled.asStateFlow()
    private val mutableStats=MutableStateFlow(MentionNameStats())
    val stats=mutableStats.asStateFlow()
    private val dao=database.names()
    private val gate=Mutex()
    private val demandLock=Any()
    private val leases=linkedMapOf<Long,List<Demand>>()
    private val touches=linkedMapOf<String,Long>()
    private val ids=AtomicLong()
    private val generation=AtomicLong()
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val ready=CompletableDeferred<Unit>()
    private val rows=linkedMapOf<String,MentionNameRow>()
    @Volatile private var activeLookup: Job?=null
    private data class Demand(val account: MentionAccount,val networkAllowed: Boolean)
    private class YieldLookup: CancellationException("Essential content takes priority")

    init {
        scope.launch(Dispatchers.IO) {
            try {
                gate.withLock {
                    dao.all().forEach {row->if(valid(row))rows[row.key]=row}
                    pruneLocked(clock(),persistAll=true)
                }
                ready.complete(Unit)
                while(isActive) {
                    wake.receive()
                    while(isActive) {
                        delay(pollMs.coerceAtLeast(1))
                        applyTouches()
                        val candidate=nextDemand()
                        if(candidate==null)break
                        if(!canStart()) {
                            // Poll only while a real visible, due demand exists.
                            delay(pollMs.coerceAtLeast(500));continue
                        }
                        val snapshot=policy()
                        val quietStart=clock()
                        delay(quietMs.coerceAtLeast(0))
                        if(!canStart()||policy()!=snapshot||clock()<quietStart||!demanded(candidate.key.id))continue
                        resolve(candidate,snapshot)
                    }
                }
            }catch(e: CancellationException){ready.cancel(e);throw e}
            catch(_: Exception) {
                // A disposable database failure cannot break the feed. The
                // original handles remain readable and clickable.
                mutableNames.value=emptyMap();mutableStats.value=MentionNameStats()
                ready.complete(Unit)
            }
        }
    }

    /** A lease represents a currently visible body, not every cached post.
     * Repeated accounts across bodies coalesce into one pending identity. */
    override fun observe(accounts: List<MentionAccount>,networkAllowed: Boolean): AutoCloseable {
        val accepted=accounts.take(MAX_DEMANDS).mapNotNull {account->
            MentionNames.account(account.target,routes)?.takeIf {it.key==account.key}
        }.distinctBy {it.key.id}
        if(accepted.isEmpty())return AutoCloseable {}
        val id=ids.incrementAndGet()
        synchronized(demandLock) {
            val existing=leases.values.flatten().map {it.account.key.id}.toHashSet()
            val selected=accepted.filter {it.key.id in existing||existing.size<MAX_DEMANDS&&existing.add(it.key.id)}
            if(selected.isNotEmpty()) {
                leases[id]=selected.map {Demand(it,networkAllowed)}
                selected.forEach {account->touches[account.key.id]=(touches[account.key.id] ?: 0)+1}
            }
        }
        wake.trySend(Unit)
        return AutoCloseable {
            synchronized(demandLock){leases.remove(id)}
            wake.trySend(Unit)
        }
    }

    /** Settings/transport changes are supplied explicitly by Repository. */
    fun changed() {
        mutableEnabled.value=readable()
        if(!readable()||!lookupEnabled())activeLookup?.cancel(YieldLookup())
        wake.trySend(Unit)
    }

    internal suspend fun awaitReady()=ready.await()
    internal val requestedCount: Int get()=synchronized(demandLock){leases.values.flatten().map {it.account.key.id}.distinct().size}

    suspend fun seed(profile: Profile): Boolean {
        ready.await()
        if(!profile.displayNameSupplied)return false
        val account=MentionNames.account(profile.target,routes) ?: return false
        val name=MentionNames.sanitizeDisplayName(profile.name,account.key) ?: return false
        gate.withLock {
            val now=clock();val old=rows[account.key.id]
            val row=record(account,name,now,old?.lastViewedAt ?: now,old?.viewCount ?: 1,0,0,null)
            rows[row.key]=row;dao.put(listOf(row));pruneLocked(now)
        }
        wake.trySend(Unit)
        return true
    }

    suspend fun seed(page: Page) {
        // These are already retrieved public profile records. Names invented
        // by the reader, post author strings and guessed handles are excluded.
        (listOfNotNull(page.profile)+page.accounts).take(MAX_DEMANDS).distinctBy {it.target}.forEach {seed(it)}
    }

    private fun demands(): List<MentionAccount> = synchronized(demandLock) {
        leases.values.flatten().filter {it.networkAllowed}.map {it.account}.distinctBy {it.key.id}.take(MAX_DEMANDS)
    }
    private fun demanded(key: String)=synchronized(demandLock){leases.values.any {lease->lease.any {it.networkAllowed&&it.account.key.id==key}}}
    private fun canStart()=readable()&&lookupEnabled()&&!busy()&&policy().network.let {!it.tor||it.ready}

    private suspend fun applyTouches() {
        val batch=synchronized(demandLock){touches.toMap().also {touches.clear()}}
        if(batch.isEmpty())return
        gate.withLock {
            val now=clock()
            val updated=batch.keys.mapNotNull {key->rows[key]?.takeIf {now<it.lastViewedAt||now-it.lastViewedAt>=TOUCH_INTERVAL_MS}?.let {old->
                // A cap plus time decay in retention scoring prevents a long
                // forgotten once-popular account from becoming immortal. Ten
                // second coalescing avoids writes for pointer/scroll churn.
                old.copy(lastViewedAt=now,viewCount=(old.viewCount+1).coerceAtMost(1_000_000)).also {rows[key]=it}
            }}
            if(updated.isNotEmpty()){dao.put(updated);pruneLocked(now)}
        }
    }

    private suspend fun nextDemand(): MentionAccount? {
        val current=demands();if(current.isEmpty()||!readable()||!lookupEnabled())return null
        val failurePolicy=policyClass(policy())
        return gate.withLock {
            val now=clock()
            current.firstOrNull {account->
                val old=rows[account.key.id]
                old==null||now<old.checkedAt||(if(old.failures>0||old.displayName==null)old.failurePolicy!=failurePolicy||now>=old.retryAt else MentionNamePolicy.stale(old.checkedAt,now))
            }
        }
    }

    private suspend fun resolve(account: MentionAccount,expected: MentionLookupPolicy) {
        val epoch=generation.get()
        var deferred=false
        val profile=try {
            supervisorScope {
                val work=async(Dispatchers.IO) {
                    prepare()
                    if(!canStart()||policy()!=expected||!demanded(account.key.id))throw YieldLookup()
                    withTimeout(if(expected.network.tor)25_000L else 12_000L){lookup(account)}
                }
                activeLookup=work
                val watcher=launch {
                    while(isActive&&!work.isCompleted) {
                        delay(pollMs.coerceAtLeast(1))
                        if(!canStart()||policy()!=expected||!demanded(account.key.id)||generation.get()!=epoch) {
                            work.cancel(YieldLookup());break
                        }
                    }
                }
                try {work.await()}finally {watcher.cancel();activeLookup=null}
            }
        }catch(e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if(e is TimeoutCancellationException)null else {deferred=true;null}
        }catch(_: Exception){null}
        if(deferred||generation.get()!=epoch||policy()!=expected||!canStart()||!demanded(account.key.id))return
        val returned=profile?.let {MentionNames.account(it.target,routes)}
        val name=profile?.takeIf {it.displayNameSupplied&&returned?.key==account.key}?.let {MentionNames.sanitizeDisplayName(it.name,account.key)}
        gate.withLock {
            if(generation.get()!=epoch)return@withLock
            val now=clock();val old=rows[account.key.id]
            // A stale valid label can stay visible during a temporary outage;
            // failed names are never substituted for the original @handle.
            val failurePolicy=if(name==null)policyClass(expected)else null
            val previousFailures=old?.takeIf {it.failurePolicy==failurePolicy}?.failures ?: 0
            val failures=if(name==null)previousFailures+1 else 0
            val retry=if(name==null)now+MentionNamePolicy.retryDelay(failures)else 0
            val row=record(account,name ?: old?.displayName,now,old?.lastViewedAt ?: now,old?.viewCount ?: 1,failures,retry,failurePolicy)
            rows[row.key]=row;dao.put(listOf(row));pruneLocked(now)
        }
    }

    private fun record(account: MentionAccount,name: String?,checked: Long,viewed: Long,count: Long,failures: Int,retry: Long,failurePolicy: String?): MentionNameRow {
        val key=account.key
        val bytes=listOf(key.id,key.platform.id,key.authority,key.username,account.target.canonicalUrl,name.orEmpty(),failurePolicy.orEmpty()).sumOf {it.toByteArray(Charsets.UTF_8).size}.toLong()+96
        return MentionNameRow(key.id,key.platform.id,key.authority,key.username,account.target.canonicalUrl,name,checked,viewed,count,failures.coerceIn(0,32),retry,failurePolicy,bytes)
    }
    private fun valid(row: MentionNameRow): Boolean {
        if(row.bytes !in 1..maxBytes||row.viewCount !in 0..1_000_000||row.failures !in 0..32)return false
        if(row.canonicalUrl.length>2_048||row.retryAt<0||row.retryAt>row.checkedAt+MentionNamePolicy.MAX_MISS_BACKOFF_MS)return false
        if(row.failures==0&&row.retryAt!=0L||row.displayName==null&&row.failures==0)return false
        if(row.failures==0&&row.failurePolicy!=null||row.failures>0&&row.failurePolicy !in FAILURE_POLICIES)return false
        val now=clock()
        if(!MentionNamePolicy.validTimestamp(row.checkedAt,now)||!MentionNamePolicy.validTimestamp(row.lastViewedAt,now))return false
        val platform=Platform.entries.firstOrNull {it.id==row.platform} ?: return false
        val target=routes.recognize(row.canonicalUrl,platform) ?: return false
        val account=MentionNames.account(target,routes) ?: return false
        return account.key.id==row.key&&account.key.authority==row.authority&&account.key.username==row.username&&
            record(account,row.displayName,row.checkedAt,row.lastViewedAt,row.viewCount,row.failures,row.retryAt,row.failurePolicy).bytes==row.bytes&&
            (row.displayName==null||MentionNames.sanitizeDisplayName(row.displayName,account.key)==row.displayName)
    }

    private suspend fun pruneLocked(now: Long,persistAll: Boolean=false) {
        val expired=rows.values.filter {MentionNamePolicy.expired(it.lastViewedAt,now)}.map {it.key}.toMutableList()
        expired.forEach {rows.remove(it)}
        var bytes=rows.values.sumOf {it.bytes}
        val victims=rows.values.sortedBy {row->MentionNamePolicy.retainScore(row.viewCount,row.lastViewedAt,now)}.iterator()
        while((rows.size>maxEntries||bytes>maxBytes)&&victims.hasNext()) {
            val row=victims.next();rows.remove(row.key);expired+=row.key;bytes-=row.bytes
        }
        if(persistAll) {
            // Also discard malformed cache rows without trying to migrate them.
            dao.clear();if(rows.isNotEmpty())dao.put(rows.values.toList())
        }else expired.chunked(400).forEach {dao.remove(it)}
        if(expired.size>=32||diskBytes()>MAX_DISK_BYTES)compact()
        mutableNames.value=rows.values.mapNotNull {row->row.displayName?.let {row.key to it}}.toMap()
        mutableStats.value=MentionNameStats(rows.size,mutableNames.value.size,diskBytes())
    }
    private fun diskBytes(): Long {
        val path=runCatching {database.openHelper.writableDatabase.path}.getOrNull() ?: return 0
        if(path==":memory:")return rows.values.sumOf {it.bytes}
        return listOf("","-journal","-wal","-shm").sumOf {File(path+it).length()}
    }
    private fun compact(){runCatching {database.openHelper.writableDatabase.execSQL("VACUUM")}}

    suspend fun bytes(): Long {ready.await();return gate.withLock {diskBytes()}}
    suspend fun cleanup(now: Long=clock()) {ready.await();gate.withLock {pruneLocked(now)}}
    suspend fun clear() {
        generation.incrementAndGet();activeLookup?.cancel(YieldLookup())
        synchronized(demandLock){leases.clear();touches.clear()}
        ready.await();gate.withLock {rows.clear();dao.clear();compact();mutableNames.value=emptyMap();mutableStats.value=MentionNameStats(bytes=diskBytes())}
    }

    companion object {
        private fun policyClass(value: MentionLookupPolicy)="${if(value.network.tor)"tor"else "direct"}|${if(value.originals)"originals"else "alternates"}"
        private val FAILURE_POLICIES=setOf("tor|originals","tor|alternates","direct|originals","direct|alternates")
        private const val TOUCH_INTERVAL_MS=10_000L
        private const val MAX_ENTRIES=MentionNamePolicy.MAX_ENTRIES
        private const val MAX_BYTES=MentionNamePolicy.MAX_BYTES
        private const val MAX_DISK_BYTES=2L*1024*1024
        private const val MAX_DEMANDS=128
    }
}
