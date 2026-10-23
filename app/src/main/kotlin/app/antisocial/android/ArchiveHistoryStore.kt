package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.room.withTransaction
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private data class ArchiveLookupRecord(val signature: String,val checkedAt: Long,val tor: Boolean,val outcome: ArchiveCheckOutcome,val version: ArchivedVersion?=null)

/** Ordinary lookups expire with the ordinary cache. Saved pairs live in their saved post. */
internal class ArchiveHistoryStore(private val repo: Repository,private val policy: ArchiveCheckPolicy=ArchiveCheckPolicy(),private val budgetFile: File?=File(repo.context.cacheDir,"archive-budget.json")) {
    val mutations=Mutex()
    private val state=MutableStateFlow<Map<String,ArchivedVersion>>(emptyMap())
    val history: StateFlow<Map<String,ArchivedVersion>> = state.asStateFlow()
    private val reader by lazy {ArchiveReader(repo.gateway,repo.routes)}
    internal var lookup: suspend (Post)->ArchivedVersion? = {post->reader.oldest(post)}
    private val directory=File(repo.context.cacheDir,"content").apply {mkdirs()}
    init {budgetFile?.takeIf {it.isFile&&it.length()<=4096}?.let {file->runCatching {policy.restoreBudget(repo.gson.fromJson(file.readText(),ArchiveBudget::class.java))}}}
    @Synchronized private fun saveBudget() {budgetFile?.let {file->runCatching {Repository.atomic(file,repo.gson.toJson(policy.budget()).toByteArray())}}}
    private fun lookupKey(post: Post)="archive-"+Repository.hash(repo.key(post))
    fun offlineKey(post: Post)="offline-"+Repository.hash(repo.key(post))
    private fun signature(post: Post)=ArchiveContent.signature(post)
    private fun valid(post: Post,version: ArchivedVersion): Boolean=post.recoveredArchive==null&&post.provenance?.source?.role!=SourceRole.CACHE&&runCatching {
        val url=version.archiveUrl.toHttpUrlOrNull() ?: return@runCatching false
        val original=ArchiveUrls.original(version.archiveUrl) ?: return@runCatching false
        fun asset(value: String)=ArchiveUrls.safeAsset(value,version.archiveUrl)
        val snapshot=version.snapshot
        version.currentSignature==signature(post)&&url.isHttps&&url.username.isEmpty()&&url.password.isEmpty()&&url.host in setOf("web.archive.org","arquivo.pt")&&
            ArchiveUrls.samePost(post,original)&&
            version.provider.length in 1..80&&version.archiveUrl.length<=16*1024&&version.capturedAt in 1..System.currentTimeMillis()+86_400_000&&
            snapshot.body.length+snapshot.title.length<=128*1024&&snapshot.author.length<=500&&snapshot.media.size<=16&&snapshot.links.size<=24&&snapshot.links.sumOf {it.length}<=64*1024&&
            snapshot.media.all {asset(it.url)&&(it.preview?.let {preview->asset(preview)} ?: true)&&(it.audioUrl?.let {audio->asset(audio)} ?: true)&&it.description.length<=4096}&&(snapshot.avatar?.let {asset(it)} ?: true)&&
            ArchiveContent.compare(post,snapshot.asPost(post))==ArchiveComparison.DIFFERENT
    }.getOrDefault(false)
    fun version(post: Post): ArchivedVersion?=if(post.recoveredArchive!=null||post.provenance?.source?.role==SourceRole.CACHE)null else (post.archivedVersion?.takeIf {valid(post,it)} ?: state.value[repo.key(post)]?.takeIf {valid(post,it)})
    private fun publish(post: Post,version: ArchivedVersion) {
        state.update {old->(old-repo.key(post)+mapOf(repo.key(post) to version)).entries.toList().takeLast(48).associate {it.toPair()}}
    }
    /** Retain a pair across ordinary refreshes only while its current content still matches. */
    suspend fun marry(post: Post): Post {
        // A recovered copy is the only readable edition, not a live current half
        // of the older/current comparison feature.
        if(post.recoveredArchive!=null) {
            val recovered=post.copy(archivedVersion=null)
            if(!RecoveredArchives.valid(recovered))throw SourceFailure(HealthStatus.UNAVAILABLE,"The archived copy could not be verified.")
            return recovered
        }
        if(post.provenance?.source?.role==SourceRole.CACHE)return post.copy(archivedVersion=null)
        version(post)?.let {return post.copy(archivedVersion=it)}
        val existing=repo.dao.post(repo.key(post))?.let {runCatching {repo.gson.fromJson(it.payload,Post::class.java)}.getOrNull()}
        val saved=repo.dao.bookmarksByKeys(listOf(repo.key(post))).firstOrNull()?.let {runCatching {repo.gson.fromJson(it.payload,Post::class.java)}.getOrNull()}
        val pair=(listOfNotNull(existing?.archivedVersion,saved?.archivedVersion)).firstOrNull {valid(post,it)}
        return post.copy(archivedVersion=pair)
    }
    suspend fun check(post: Post) {
        if(post.recoveredArchive!=null||post.provenance?.source?.role==SourceRole.CACHE)return
        repo.awaitReady()
        if(!repo.prefs.archiveChecks||post.preview||post.parentId!=null)return
        val target=repo.routes.recognize(post.canonicalUrl,post.platform) ?: return
        if(target.platform!=post.platform||target.kind !in setOf(TargetKind.POST,TargetKind.THREAD))return
        val married=marry(post)
        version(married)?.let {publish(post,it);return}
        val sig=signature(post);val tor=repo.gateway.currentPolicy().tor
        val record=readRecord(post)
        if(record!=null&&record.signature==sig&&record.tor==tor) {
            record.version?.takeIf {valid(post,it)}?.let {attach(post,it);return}
            val ttl=if(record.outcome==ArchiveCheckOutcome.NOT_FOUND)3*CachePolicy.DAY else CachePolicy.DAY
            if(System.currentTimeMillis()-record.checkedAt in 0 until ttl)return
        }
        if(!policy.claim(repo.key(post),sig,tor))return
        saveBudget()
        val work=repo.cardWork.begin(listOf(postWorkKey(post)),"Archive history")
        var outcome=ArchiveCheckOutcome.FAILED
        try {
            val found=withTimeout(if(tor)40_000 else 24_000){lookup(post)}
            val result=found?.let {ArchiveContent.compare(post,it.snapshot.asPost(post))}
            outcome=when(result){ArchiveComparison.DIFFERENT->ArchiveCheckOutcome.DIFFERENT;ArchiveComparison.SAME->ArchiveCheckOutcome.SAME;else->ArchiveCheckOutcome.NOT_FOUND}
            val paired=found?.copy(currentSignature=sig)?.takeIf {outcome==ArchiveCheckOutcome.DIFFERENT&&valid(post,it)}
            writeRecord(post,ArchiveLookupRecord(sig,System.currentTimeMillis(),tor,outcome,paired))
            if(paired!=null)attach(post,paired)
        }catch(e: CancellationException) {
            if(e !is TimeoutCancellationException){outcome=ArchiveCheckOutcome.CANCELLED;throw e}
            currentCoroutineContext().ensureActive()
            writeRecord(post,ArchiveLookupRecord(sig,System.currentTimeMillis(),tor,ArchiveCheckOutcome.FAILED))
        }catch(e: Exception) {
            outcome=if((e as? SourceFailure)?.status==HealthStatus.RATE_LIMITED)ArchiveCheckOutcome.RATE_LIMITED else ArchiveCheckOutcome.FAILED
            writeRecord(post,ArchiveLookupRecord(sig,System.currentTimeMillis(),tor,outcome))
        }finally {work.close();policy.complete(repo.key(post),sig,tor,outcome);saveBudget()}
    }
    private suspend fun readRecord(post: Post): ArchiveLookupRecord? {
        val row=repo.dao.media(lookupKey(post)) ?: return null
        val file=File(row.path)
        if(!file.isFile||file.length()>256*1024)return null
        return runCatching {repo.gson.fromJson(file.readText(),ArchiveLookupRecord::class.java)}.getOrNull()
    }
    private suspend fun writeRecord(post: Post,record: ArchiveLookupRecord) {
        val bytes=repo.gson.toJson(record).toByteArray();if(bytes.size>256*1024)return
        val key=lookupKey(post);val file=File(directory,key)
        repo.mediaMutex.withLock {Repository.atomic(file,bytes);repo.dao.putMedia(MediaRow(key,file.path,bytes.size.toLong(),System.currentTimeMillis()))}
        repo.scheduleCleanup()
    }
    private suspend fun attach(post: Post,version: ArchivedVersion) {
        var offline=false
        mutations.withLock {repo.db.withTransaction {
            val key=repo.key(post)
            repo.dao.post(key)?.let {row->
                val current=repo.gson.fromJson(row.payload,Post::class.java)
                if(valid(current,version)) {
                    val json=repo.gson.toJson(current.copy(archivedVersion=version))
                    repo.dao.putPost(row.copy(payload=json,bytes=json.toByteArray().size.toLong()))
                }
            }
            repo.dao.bookmarksByKeys(listOf(key)).firstOrNull()?.let {row->
                val current=repo.gson.fromJson(row.payload,Post::class.java)
                if(valid(current,version)){
                    repo.dao.putBookmark(row.copy(payload=repo.gson.toJson(current.copy(archivedVersion=version))))
                    repo.library.indexBookmark(current)
                }
            }
            offline=repo.dao.media(offlineKey(post))?.saved==true
        };publish(post,version)}
        if(offline)repo.scope.launch {runCatching {repo.saveArchivedMedia(post,version)}}
    }
}
