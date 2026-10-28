/* anti social, GPL-3.0-or-later. Ordinary public evidence, never a retained source rewrite. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** Only a settled visible card requests a pair. One decoder/network lane,
 * expiring derived observations and finite parent allowances keep it behind
 * foreground reading. Saved copies own their own observation fields. */
internal class VideoEvidenceStore(private val repo:Repository,private val clock:()->Long=System::currentTimeMillis,
    private val directory:File=File(repo.context.cacheDir,"video-evidence")) {
    private data class Entry(val url:String,val evidence:VideoFootageEvidence)
    private val lock=Any()
    private val lane=Mutex()
    private val entries=linkedMapOf<String,Entry>()
    private val aliases=mutableMapOf<String,MutableSet<String>>()
    private val failures=linkedMapOf<VideoAcquisitionKey,Long>()
    private val proofs=object:LinkedHashMap<Pair<VideoFootageEvidence,VideoFootageEvidence>,SharedVideoFootage?>(16,.75f,true){}
    private val running=linkedSetOf<Job>()
    private var generation=0L
    private var loaded=false
    private val changes=MutableStateFlow(0L)
    val revision=changes.asStateFlow()
    private val retryChanges=MutableStateFlow(0L)
    val retries=retryChanges.asStateFlow()
    private val playing=MutableStateFlow(false)
    val playbackActive=playing.asStateFlow()
    fun playback(active:Boolean){playing.value=active}
    internal var sample:suspend (Media,String)->GatewayVideoSampler.Result=GatewayVideoSampler(repo)::sample
    internal var metadata:suspend (Post)->Media?=repo::videoMetadata

    private fun fresh(e:VideoFootageEvidence)=clock()-e.sampledAt in 0 until 7*CachePolicy.DAY
    private fun remove(url:String) {
        entries.remove(url)
        VideoAssetIdentity.key(url)?.let {key->aliases[key]?.let {urls->urls.remove(url);if(urls.isEmpty())aliases.remove(key)}}
    }
    private fun put(entry:Entry) {
        remove(entry.url);entries[entry.url]=entry
        VideoAssetIdentity.key(entry.url)?.let {aliases.getOrPut(it){linkedSetOf()}.add(entry.url)}
    }
    private fun lookup(media:Media):VideoFootageEvidence? {
        if(media.type!="video")return null
        val exact=entries[media.url]?.evidence?.takeIf(::fresh)
        if(exact!=null)return exact
        val key=VideoAssetIdentity.key(media.url) ?: return null
        return aliases[key].orEmpty().mapNotNull {entries[it]?.evidence?.takeIf(::fresh)}
            .maxWithOrNull(compareBy<VideoFootageEvidence> {it.scenes.size}.thenBy {it.sampledAt})
    }
    /** In-memory references only. Call on the matching worker, never decode,
     * fetch, touch Room or read a cache file while projecting a feed. */
    fun captured(post:Post):Post=synchronized(lock) {
        val media=post.media.map {item->if(item.videoFootage?.scenes?.size?.let {it>=3}==true)item else lookup(item)?.let {item.copy(videoFootage=it)} ?: item}
        if(media==post.media)post else post.copy(media=media)
    }
    /** A cooling failed peer must not occupy a visible card's only comparison
     * slot ahead of another usable peer. This is an in-memory scheduling check;
     * no failed read becomes evidence that two recordings differ. */
    fun available(request:VideoFootageRequest):Boolean=synchronized(lock) {
        val media=listOf(request.first,request.second).map {it.media.singleOrNull {m->m.type=="video"} ?: return false}
        val needed=media.filter {it.videoFootage?.scenes?.size?.let {count->count>=3}!=true&&lookup(it)==null}
        needed.isNotEmpty()&&needed.none {m->failures[VideoAssetIdentity.acquisitionKey(m)]?.let {clock()-it in 0 until 5*60_000L}==true}
    }
    fun related(a:Post,b:Post,checkpoint:()->Unit):SharedVideoFootage? {
        for(left in a.media)for(right in b.media) {
            val first=left.videoFootage ?: continue;val second=right.videoFootage ?: continue
            if(!VideoFootage.valid(left)||!VideoFootage.valid(right))continue
            checkpoint()
            val key=first to second
            val cached=synchronized(lock){if(proofs.containsKey(key))true to proofs[key]else false to null}
            if(cached.first){if(cached.second!=null)return cached.second;continue}
            val epoch=synchronized(lock){generation}
            val proof=VideoFootage.shared(first,second,checkpoint)
            synchronized(lock){if(generation==epoch){proofs[key]=proof;while(proofs.size>128)proofs.remove(proofs.keys.first())}}
            if(proof!=null)return proof
        }
        return null
    }
    suspend fun restore()=withContext(Dispatchers.IO) {
        val context=currentCoroutineContext()
        synchronized(lock) {
            if(loaded)return@synchronized
            val epoch=generation
            val files=directory.listFiles().orEmpty().filter {it.name.matches(Regex("[a-f0-9]{64}\\.json"))}.sortedByDescending {it.lastModified()}.take(MAX_ENTRIES)
            for(file in files) {
                context.ensureActive()
                val row=runCatching {
                    if(file.length() !in 1..MAX_ENTRY_BYTES)null else repo.gson.fromJson(file.readText(),Entry::class.java)?.takeIf {row->
                        row.url.length<=16384&&file.name==Repository.hash(row.url)+".json"&&fresh(row.evidence)&&VideoFootage.valid(Media(row.url,"video",videoFootage=row.evidence))
                    }
                }.getOrNull()
                if(row!=null)put(row)
                else file.delete()
            }
            if(epoch==generation){loaded=true;if(entries.isNotEmpty())changes.value+=1}
        }
    }
    private suspend fun acquire(post:Post,epoch:Long,resolveMetadata:Boolean) {
        val media=post.media.singleOrNull {it.type=="video"} ?: return
        val input=VideoAssetIdentity.acquisitionKey(media)
        if(media.videoFootage?.scenes?.size?.let {it>=3}==true&&VideoFootage.valid(media))return
        synchronized(lock) {
            if(lookup(media)!=null)return
            failures[input]?.let {if(clock()-it in 0 until 5*60_000L)return}
        }
        try {
            val prepared=if(resolveMetadata&&media.samplingUrl==null&&VideoAssetIdentity.canResolveMetadata(post)) {
                val supplied=metadata(post) ?: throw IOException("No supported current video metadata")
                currentCoroutineContext().ensureActive()
                VideoAssetIdentity.withMetadata(media,supplied) ?: throw IOException("Video metadata belongs to another recording")
            }else media
            val result=sample(prepared,post.contentSourceUrl)
            currentCoroutineContext().ensureActive()
            val observation=VideoFootageEvidence(result.renditionUrl,result.frames.durationMs,clock(),result.frames.scenes.toList())
            if(observation.scenes.size<3||!VideoFootage.valid(media.copy(videoFootage=observation)))throw IOException("Too few supported video scenes")
            val row=Entry(media.url,observation);val bytes=repo.gson.toJson(row).toByteArray()
            require(bytes.size<=MAX_ENTRY_BYTES)
            synchronized(lock) {
                if(epoch!=generation)return
                directory.mkdirs();Repository.atomic(File(directory,Repository.hash(media.url)+".json"),bytes)
                put(row);failures.remove(input);trim(false);changes.value+=1
            }
        }catch(e:CancellationException){throw e}
        catch(_:Exception){synchronized(lock){if(epoch==generation){failures[input]=clock();while(failures.size>MAX_ENTRIES)failures.remove(failures.keys.first());changes.value+=1}}}
    }
    suspend fun enrich(request:VideoFootageRequest,automatic:Boolean=true)=withContext(Dispatchers.IO) {lane.withLock {
        repo.awaitReady();restore()
        if(automatic&&repo.prefs.videoDownloadWifiOnly&&repo.context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered!=false)return@withLock
        if(automatic) {
            val power=repo.context.getSystemService(android.os.PowerManager::class.java)
            if(power?.isPowerSaveMode==true||android.os.Build.VERSION.SDK_INT>=29&&power?.currentThermalStatus?.let {it>=android.os.PowerManager.THERMAL_STATUS_SEVERE}==true)return@withLock
            val info=android.app.ActivityManager.MemoryInfo()
            repo.context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
            if(info.lowMemory)return@withLock
        }
        val job=currentCoroutineContext()[Job]!!
        val epoch=synchronized(lock){running+=job;generation}
        try {withTimeout(45_000) {repo.cardWork.track(request.originalKeys,"Comparing video editions") {
            repo.gateway.boundedVideoComparison {
                for(post in listOf(request.first,request.second)) {
                    currentCoroutineContext().ensureActive()
                    var retries=0
                    while(true)try {
                        while(repo.gateway.hasForegroundRequests||playing.value)delay(300)
                        repo.gateway.deferredRead {acquire(post,epoch,request.resolveMetadata)}
                        break
                    }catch(e:CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if(++retries>=3)break
                        delay(700)
                    }
                }
            }
        }}}catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive()}
        finally {synchronized(lock){running-=job};repo.scheduleCleanup()}
    }}
    private fun trim(clear:Boolean) {
        if(clear){directory.deleteRecursively();return}
        val files=directory.listFiles().orEmpty().filter {it.isFile}.sortedBy {it.lastModified()}.toMutableList()
        files.removeAll {file->(clock()-file.lastModified() !in 0..7*CachePolicy.DAY)&&file.delete()}
        var bytes=files.sumOf {it.length()}
        for(file in files) {
            if(bytes<=8L*1024*1024&&files.count {it.exists()}<=MAX_ENTRIES)break
            val size=file.length();if(file.delete())bytes-=size
        }
        entries.keys.toList().filter {url->!fresh(entries.getValue(url).evidence)}.forEach(::remove)
        proofs.keys.removeAll {key->!fresh(key.first)||!fresh(key.second)}
        failures.entries.removeAll {clock()-it.value !in 0 until 5*60_000L}
        while(entries.size>MAX_ENTRIES)remove(entries.keys.first())
    }
    fun cleanup(clear:Boolean)=synchronized(lock) {
        if(clear){generation++;running.toList().forEach {it.cancel(CancellationException("Ordinary video evidence cleared"))};entries.clear();aliases.clear();failures.clear();proofs.clear();loaded=true;changes.value+=1}
        trim(clear)
    }
    fun retryFailures()=synchronized(lock){failures.clear();changes.value+=1;retryChanges.value+=1}
    fun bytes():Long=directory.listFiles().orEmpty().filter {it.isFile}.sumOf {it.length()}
    companion object {private const val MAX_ENTRIES=128;private const val MAX_ENTRY_BYTES=64*1024}
}
