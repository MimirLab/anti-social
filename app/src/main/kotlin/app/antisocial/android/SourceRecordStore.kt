/* AntiSocial, GPL-3.0-or-later. Bounded ordinary evidence, separate from saved copies. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

internal class SourceRecordStore(private val repo:Repository,private val clock:()->Long=System::currentTimeMillis,private val file:File=File(repo.context.cacheDir,"public-source-records.json")) {
    private data class Entry(val key:String,val record:PublicSourceRecord)
    private val entries=linkedMapOf<String,PublicSourceRecord>()
    private val lock=Any()
    private val supplemental=Semaphore(1)
    private val requested=Semaphore(1)
    private var loaded=false
    private var generation=0L
    private val reader=PublicSourceRecordReader(repo.gateway,clock)
    internal var fetch:suspend (SourceRecordRequest,suspend (PublicSourceRecord)->Unit)->PublicSourceRecord=reader::lookup
    private fun valid(record:PublicSourceRecord)=runCatching {
        val age=clock()-record.checkedAt
        age in 0..(if(record.complete)CachePolicy.DAY else 15*60_000L)&&record.facts.size<=60&&record.facts.all {fact->
            fact.title.length<=120&&fact.detail.length<=2048&&PublicSourceRecords.safe(fact.sourceUrl)!=null&&(fact.destination==null||PublicSourceRecords.safe(fact.destination)!=null)
        }
    }.getOrDefault(false)
    private fun loadLocked() {
        if(loaded)return;loaded=true
        runCatching {if(file.isFile&&file.length()<=MAX_BYTES)repo.gson.fromJson(file.readText(),Array<Entry>::class.java)?.take(MAX_ENTRIES)?.forEach {
            if(valid(it.record))entries[it.key]=it.record
        }}
    }
    private fun cached(key:String):PublicSourceRecord?=synchronized(lock){loadLocked();entries[key]?.takeIf(::valid)}
    suspend fun lookup(request:SourceRecordRequest,deferred:Boolean=true,onPartial:suspend (PublicSourceRecord)->Unit={}):PublicSourceRecord=withContext(Dispatchers.IO) {
        repo.awaitReady()
        val policy=repo.gateway.currentPolicy();val originals=repo.prefs.originalFallback
        val key="$policy|$originals|${request.key}"
        cached(key)?.let {return@withContext it}
        val work=repo.cardWork.begin(listOf("source:"+request.key),"Source information")
        try {
        (if(deferred)supplemental else requested).withPermit {
            cached(key)?.let {return@withPermit it}
            val epoch=synchronized(lock){generation}
            val value=try {
                if(deferred)while(repo.gateway.hasForegroundRequests||repo.pool.hasForegroundReads||repo.feedRefresh.value.running)delay(250)
                cached(key)?.let {return@withPermit it}
                suspend fun acquire()=withTimeout(if(policy.tor)60_000L else 25_000L){fetch(request,onPartial)}
                if(deferred)repo.gateway.deferredRead {acquire()}else acquire()
            }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();PublicSourceRecord(request,emptyList(),clock(),false,"The public source did not respond in time. Try again later.")}
             catch(e:CancellationException){throw e}
             catch(_:Exception){PublicSourceRecord(request,emptyList(),clock(),false,"Public records are unavailable right now. This makes no claim about the source.")}
            ensureActive()
            check(repo.gateway.currentPolicy()==policy&&repo.prefs.originalFallback==originals){"Network policy changed"}
            synchronized(lock) {
                if(epoch==generation&&valid(value)) {
                    entries.entries.removeIf {!valid(it.value)}
                    entries.remove(key);entries[key]=value
                    while(entries.size>MAX_ENTRIES)entries.remove(entries.keys.first())
                    persistLocked()
                }
            }
            repo.scheduleCleanup()
            value
        }
        }finally {work.close()}
    }
    private fun persistLocked() {
        var json=repo.gson.toJson(entries.map {Entry(it.key,it.value)})
        while(json.toByteArray().size>MAX_BYTES&&entries.isNotEmpty()){entries.remove(entries.keys.first());json=repo.gson.toJson(entries.map {Entry(it.key,it.value)})}
        runCatching {val temporary=File(file.parentFile,file.name+".tmp");temporary.writeText(json);if(!temporary.renameTo(file))temporary.delete()}
    }
    fun cleanup(clear:Boolean=false)=synchronized(lock) {
        if(clear){generation++;entries.clear();loaded=true;file.delete();File(file.parentFile,file.name+".tmp").delete()}
        else if(loaded){entries.entries.removeIf {!valid(it.value)};persistLocked()}
        else if(file.isFile&&clock()-file.lastModified() !in 0..CachePolicy.DAY)file.delete()
    }
    fun bytes():Long=synchronized(lock){if(file.isFile)file.length()else 0}
    companion object {const val MAX_ENTRIES=256;const val MAX_BYTES=2L*1024*1024}
}
