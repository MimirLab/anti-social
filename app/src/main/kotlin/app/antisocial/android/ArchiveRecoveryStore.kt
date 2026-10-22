/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URI

private data class RecoveryRecord(val original: String,val tor: Boolean,val checkedAt: Long,val expiresAt: Long,
    val status: HealthStatus?=null,val detail: String="",val post: Post?=null)
private data class RecoveryBudget(val starts: List<Long> = emptyList(),val blockedUntil: Long=0)

/** Explicit failed-link recovery has its own courtesy budget, separate from passive history.
 * Records are ordinary expiring cache. Saved/offline records carry the recovery metadata themselves.
 */
internal class ArchiveRecoveryStore(
    private val repo: Repository,
    private val clock: ()->Long=System::currentTimeMillis,
    private val budgetFile: File?=File(repo.context.cacheDir,"archive-recovery-budget.json"),
) {
    private val reader by lazy {ArchiveReader(repo.gateway,repo.routes)}
    internal var lookup: suspend (Target)->ArchivedVersion? = {reader.recover(it)}
    private val directory=File(repo.context.cacheDir,"content").apply {mkdirs()}
    private val serial=Semaphore(1)
    private val flightMutex=Mutex()
    private data class Flight(val result: Deferred<Page?>,var readers: Int=1)
    private val flights=mutableMapOf<String,Flight>()
    private val budgetMutex=Mutex()
    private var budget=loadBudget()
    private fun key(target: Target,tor: Boolean)="archive-recovery-"+Repository.hash("${target.platform.id}|${ContentIdentity.key(target.platform,target.canonicalUrl)}|$tor")
    internal fun cacheKey(target: Target,tor: Boolean)=key(target,tor)

    suspend fun recover(target: Target): Page?=withContext(Dispatchers.IO) {
        requireTarget(target)
        val policy=repo.gateway.currentPolicy()
        val tor=policy.tor
        if(tor&&(!policy.ready||policy.socksPort !in 1..65535))throw SourceFailure(HealthStatus.UNAVAILABLE,"Tor is connecting. Archive recovery is paused; no direct fallback was used.")
        val identity=key(target,tor)
        val flight=flightMutex.withLock {
            flights[identity]?.also {it.readers++} ?: run {
                if(flights.size>=8)throw SourceFailure(HealthStatus.UNAVAILABLE,"Archive recovery is busy. Try this link again shortly.")
                Flight(repo.scope.async(start=CoroutineStart.LAZY){load(target,tor)}).also {flights[identity]=it}
            }
        }
        flight.result.start()
        try {flight.result.await()}
        finally {withContext(NonCancellable) {flightMutex.withLock {
            flight.readers--
            if(flight.readers==0) {
                if(flights[identity]===flight)flights.remove(identity)
                if(flight.result.isActive)flight.result.cancel()
            }
        }}}
    }

    private fun requireTarget(target: Target) {
        val recognized=runCatching {repo.routes.recognize(target.canonicalUrl,target.platform)}.getOrNull()
        if(target.kind !in setOf(TargetKind.POST,TargetKind.THREAD)||recognized==null||recognized.platform!=target.platform||
            recognized.kind !in setOf(TargetKind.POST,TargetKind.THREAD)||ContentIdentity.key(target.platform,recognized.canonicalUrl)!=ContentIdentity.key(target.platform,target.canonicalUrl))
            throw SourceFailure(HealthStatus.UNSUPPORTED,"Archive recovery needs an exact public social post link.")
    }
    private fun sameMode(tor: Boolean) {
        val policy=repo.gateway.currentPolicy()
        if(policy.tor!=tor)throw SourceFailure(HealthStatus.UNAVAILABLE,"The connection changed during archive recovery. Open the link again using the selected connection.")
        if(tor&&(!policy.ready||policy.socksPort !in 1..65535))throw SourceFailure(HealthStatus.UNAVAILABLE,"Tor is connecting. Archive recovery is paused; no direct fallback was used.")
    }
    private fun valid(target: Target,version: ArchivedVersion)=RecoveredArchives.validVersion(Post(target.identity,target.platform,target.canonicalUrl,"",""),version,clock())
    private fun post(target: Target,version: ArchivedVersion): Post {
        val snapshot=version.snapshot
        val origin=URI(version.archiveUrl).let {"${it.scheme}://${it.rawAuthority}"}
        return Post(target.identity,target.platform,target.canonicalUrl,snapshot.author,snapshot.body,title=snapshot.title,
            publishedAt=snapshot.publishedAt,avatar=snapshot.avatar,media=snapshot.media,links=snapshot.links,
            provenance=Provenance(Source(target.platform,"archive-recovery",origin,role=SourceRole.ALTERNATE),clock(),version.archiveUrl),
            recoveredArchive=ArchiveRecovery(version.provider,version.capturedAt,version.archiveUrl))
    }
    private fun page(post: Post,cached: Boolean=false): Page {
        val retained=post.copy(provenance=post.provenance?.copy(cached=cached))
        return Page(listOf(retained),provenance=retained.provenance)
    }
    private fun cachedPost(target: Target,value: Post): Boolean {
        val recovery=value.recoveredArchive ?: return false
        if(value.platform!=target.platform||value.canonicalUrl!=target.canonicalUrl||value.archivedVersion!=null||value.preview||value.parentId!=null||value.replyCount!=null||value.score!=null)return false
        val provenance=value.provenance ?: return false
        if(provenance.source.frontend!="archive-recovery"||provenance.source.role!=SourceRole.ALTERNATE||provenance.finalUrl!=recovery.archiveUrl)return false
        return RecoveredArchives.valid(value,clock())
    }
    private suspend fun read(target: Target,tor: Boolean): RecoveryRecord? {
        val row=repo.dao.media(key(target,tor)) ?: return null
        val file=File(row.path)
        if(!file.isFile||file.length()>256*1024)return null
        val record=runCatching {repo.gson.fromJson(file.readText(),RecoveryRecord::class.java)}.getOrNull() ?: return null
        val now=clock()
        if(record.original!=target.canonicalUrl||record.tor!=tor||record.checkedAt !in 1..now||record.expiresAt<=now||record.expiresAt-record.checkedAt !in 1..CachePolicy.DAY)return null
        if(record.post!=null&&!cachedPost(target,record.post))return null
        if(record.post==null&&record.status==null)return null
        return record
    }
    private fun result(record: RecoveryRecord): Page? {
        record.post?.let {return page(it,cached=true)}
        if(record.status==HealthStatus.EMPTY)return null
        throw SourceFailure(record.status ?: HealthStatus.UNAVAILABLE,record.detail.ifBlank {"Archive recovery is temporarily unavailable."})
    }
    private suspend fun write(target: Target,record: RecoveryRecord) {
        val bytes=repo.gson.toJson(record).toByteArray();if(bytes.size>256*1024)return
        val identity=key(target,record.tor);val file=File(directory,identity)
        repo.mediaMutex.withLock {Repository.atomic(file,bytes);repo.dao.putMedia(MediaRow(identity,file.path,bytes.size.toLong(),clock()))}
        repo.scheduleCleanup()
    }
    private suspend fun failure(target: Target,tor: Boolean,status: HealthStatus,detail: String,ttl: Long) {
        val now=clock();write(target,RecoveryRecord(target.canonicalUrl,tor,now,now+ttl,status,detail.take(1000)))
    }
    private suspend fun load(target: Target,tor: Boolean): Page? {
        sameMode(tor)
        read(target,tor)?.let {return result(it)}
        return serial.withPermit {
            sameMode(tor)
            read(target,tor)?.let {return@withPermit result(it)}
            reserve()
            try {
                val version=withTimeout(if(tor)40_000 else 24_000){lookup(target)}
                sameMode(tor)
                if(version==null) {
                    failure(target,tor,HealthStatus.EMPTY,"No public archive capture was found.",6*60*60*1000L)
                    null
                }else {
                    if(!valid(target,version))throw SourceFailure(HealthStatus.UNAVAILABLE,"The archive did not supply a usable capture of this exact post.")
                    val recovered=post(target,version);val now=clock()
                    write(target,RecoveryRecord(target.canonicalUrl,tor,now,now+CachePolicy.DAY,post=recovered))
                    page(recovered)
                }
            }catch(e: CancellationException) {
                if(e is TimeoutCancellationException&&currentCoroutineContext().isActive) {
                    failure(target,tor,HealthStatus.UNAVAILABLE,"The archive lookup timed out. Try again later.",5*60*1000L)
                    throw SourceFailure(HealthStatus.UNAVAILABLE,"The archive lookup timed out. Try again later.")
                }
                withContext(NonCancellable) {failure(target,tor,HealthStatus.UNAVAILABLE,"The previous archive lookup was cancelled. Try this link again shortly.",30_000)}
                throw e
            }catch(e: Exception) {
                val status=(e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE
                val detail=if(e is SourceFailure)e.message.orEmpty()else "Archives could not be reached. Try again later."
                val ttl=if(status in setOf(HealthStatus.RATE_LIMITED,HealthStatus.CHALLENGE,HealthStatus.LOGIN_REQUIRED))CachePolicy.DAY else 5*60*1000L
                if(status==HealthStatus.RATE_LIMITED)pause()
                failure(target,tor,status,detail,ttl)
                throw SourceFailure(status,detail)
            }
        }
    }
    private fun loadBudget(): RecoveryBudget {
        val now=clock()
        val stored=budgetFile?.takeIf {it.isFile&&it.length()<=4096}?.let {runCatching {repo.gson.fromJson(it.readText(),RecoveryBudget::class.java)}.getOrNull()} ?: return RecoveryBudget()
        val starts=runCatching {stored.starts}.getOrNull() ?: return RecoveryBudget()
        if(starts.size>30||starts.any {it !in 1..now})return RecoveryBudget()
        return RecoveryBudget(starts.filter {now-it<60*60*1000L}.sorted(),stored.blockedUntil.coerceIn(0,now+CachePolicy.DAY))
    }
    private fun saveBudget() {budgetFile?.let {Repository.atomic(it,repo.gson.toJson(budget).toByteArray())}}
    private suspend fun reserve()=budgetMutex.withLock {
        val now=clock();val recent=budget.starts.filter {now-it in 0 until 60*60*1000L}
        if(budget.blockedUntil>now)throw SourceFailure(HealthStatus.RATE_LIMITED,"Archive recovery is paused after an archive rate limit. Try again later.")
        if(recent.size>=30)throw SourceFailure(HealthStatus.RATE_LIMITED,"The hourly archive recovery limit was reached. Try again later.")
        budget=RecoveryBudget(recent+now,budget.blockedUntil);saveBudget()
    }
    private suspend fun pause()=budgetMutex.withLock {budget=budget.copy(blockedUntil=clock()+CachePolicy.DAY);saveBudget()}
}
