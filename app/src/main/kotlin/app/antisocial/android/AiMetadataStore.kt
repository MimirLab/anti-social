/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Opportunistic, bounded local inspection. No asset or credential is fetched for this feature. */
class AiMetadataStore(
    scope: CoroutineScope,
    private val inspectCredentials: (ByteArray,String)->AiMetadata? = C2paInspector::inspect,
    private val maxEntries: Int=256,
    private val ttlMs: Long=7*CachePolicy.DAY,
    private val clock: ()->Long=System::currentTimeMillis,
    private val onWork:(String)->AutoCloseable={AutoCloseable {}},
) {
    private data class Request(val url: String,val file: File,val id: String,val generation: Long,val work:AutoCloseable)
    private data class Entry(val stamp: String,val at: Long,val base: AiMetadata,val picture: String?,val families: Set<String>)
    private val pending=ConcurrentHashMap.newKeySet<String>()
    private val requests=Channel<Request>(16,BufferOverflow.DROP_OLDEST,onUndeliveredElement={pending.remove(it.id);it.work.close()})
    private val gate=Mutex()
    private val generation=AtomicLong()
    private val entries=LinkedHashMap<String,Entry>()
    private val state=MutableStateFlow<Map<String,AiMetadata>>(emptyMap())
    val reports=state.asStateFlow()
    init {
        require(maxEntries>0&&ttlMs>0)
        scope.launch(Dispatchers.IO) {
            try {for(request in requests)try {inspectInternal(request.url,request.file,request.generation)}catch(e: CancellationException){throw e}catch(_: Exception){}finally {pending.remove(request.id);request.work.close()}}finally {requests.cancel()}
        }.invokeOnCompletion {requests.cancel()}
    }
    fun observe(url: String,file: File) {
        if(url.isBlank()||url.length>8192||!file.isFile)return
        val epoch=generation.get();val id="$epoch:$url:${stamp(file)}"
        if(pending.add(id)) {val work=onWork(url);if(requests.trySend(Request(url,file,id,epoch,work)).isFailure){pending.remove(id);work.close()}}
    }
    /** Used by explicit saving to retain the declarations present in already available bytes. */
    suspend fun inspect(url: String,file: File): AiMetadata?=inspectInternal(url,file,null)
    private suspend fun inspectInternal(url: String,file: File,epoch: Long?): AiMetadata?=withContext(Dispatchers.IO) {gate.withLock {
        if(epoch!=null&&epoch!=generation.get())return@withLock null
        if(prune())publish()
        if(!file.isFile)return@withLock state.value[url]
        val stamp=stamp(file)
        if(epoch!=null&&entries[url]?.stamp==stamp)return@withLock state.value[url]
        val now=clock()
        val base: AiMetadata;var picture: String?=null;var families: Set<String> = emptySet()
        if(file.length()>EmbeddedAiMetadata.MAX_BYTES)base=AiMetadata(limitations=listOf(AiMetadataLimit.SIZE_LIMIT),inspectedAt=now)
        else {
            val bytes=file.inputStream().use {input->
                val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
                while(out.size()<=EmbeddedAiMetadata.MAX_BYTES) {
                    val n=input.read(buffer,0,minOf(buffer.size,EmbeddedAiMetadata.MAX_BYTES+1-out.size()))
                    if(n<0)break;out.write(buffer,0,n)
                }
                out.toByteArray()
            }
            val digest=sha256(bytes)
            entries[url]?.takeIf {it.base.sha256==digest}?.let {previous->
                entries[url]=previous.copy(stamp=stamp,at=now)
                return@withLock state.value[url]
            }
            val embedded=EmbeddedAiMetadata.inspect(bytes)
            val mime=embedded.mime ?: sniff(bytes)
            val credentials=if(bytes.size<=EmbeddedAiMetadata.MAX_BYTES&&mime!=null)inspectCredentials(bytes,mime)else null
            picture=embedded.imageKey;families=embedded.families+if(credentials?.credentialState!=null&&credentials.credentialState!=AiCredentialState.NOT_PRESENT)setOf("content_credentials")else emptySet()
            base=AiMetadata(
                disclosures=(embedded.metadata?.disclosures.orEmpty()+credentials?.disclosures.orEmpty()).distinct().take(64),
                credentialState=credentials?.credentialState ?: AiCredentialState.NOT_PRESENT,
                signer=credentials?.signer,trust=credentials?.trust ?: AiSignerTrust.NOT_EVALUATED,
                recipes=embedded.metadata?.recipes.orEmpty(),
                limitations=(embedded.metadata?.limitations.orEmpty()+credentials?.limitations.orEmpty()+
                    if(mime==null)listOf(AiMetadataLimit.UNSUPPORTED_FORMAT)else emptyList()).distinct(),
                inspectedAt=now,sha256=digest,removals=credentials?.removals.orEmpty(),
            )
        }
        entries.remove(url);entries[url]=Entry(stamp,now,base,picture,families)
        while(entries.size>maxEntries)entries.entries.iterator().apply {next();remove()}
        publish();state.value[url]
    }}
    suspend fun cleanup()=gate.withLock {prune();publish()}
    /** Deep findings update the existing exact-byte observation, never create a new URL entry. */
    internal suspend fun enrich(url:String,value:AiMetadata)=gate.withLock {
        val previous=entries[url] ?: return@withLock
        if(value.sha256!=null&&previous.base.sha256==value.sha256) {entries[url]=previous.copy(base=value);publish()}
    }
    suspend fun clear() {generation.incrementAndGet();gate.withLock {entries.clear();state.value=emptyMap()}}
    private fun prune(): Boolean {val now=clock();return entries.entries.removeAll {now-it.value.at !in 0 until ttlMs}}
    private fun publish() {
        val families=entries.values.filter {it.picture!=null}.groupBy {it.picture}.mapValues {(_,items)->items.flatMap {it.families}.toSet()}
        state.value=entries.mapValues {(_,entry)->
            val missing=entry.picture?.let {families[it].orEmpty()-entry.families}.orEmpty().sorted()
            entry.base.copy(removals=entry.base.removals+if(missing.isNotEmpty())listOf(MetadataRemoval(MetadataRemovalEvidence.SAME_IMAGE_COPY,missing))else emptyList())
        }
    }
    private fun stamp(file: File): String {
        val inode=runCatching {android.system.Os.stat(file.path).let {"${it.st_dev}:${it.st_ino}"}}.getOrDefault("")
        return "${file.canonicalPath}:$inode:${file.length()}:${file.lastModified()}"
    }
    private fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun sniff(b: ByteArray): String? {
        fun text(at: Int,size: Int)=if(at>=0&&at+size<=b.size)String(b,at,size,Charsets.US_ASCII)else ""
        return when {
            text(0,6) in setOf("GIF87a","GIF89a")->"image/gif"
            b.size>=12&&text(4,4)=="ftyp"->when(text(8,4)) {
                "avif","avis"->"image/avif"
                "heic","heix","hevc","hevx"->"image/heic"
                "mif1","msf1","heif"->"image/heif"
                "qt  "->"video/quicktime"
                "M4A ","M4B ","M4P "->"audio/mp4"
                else->"video/mp4"
            }
            b.size>=12&&text(0,4)=="RIFF"&&text(8,4)=="WAVE"->"audio/wav"
            text(0,3)=="ID3"->"audio/mpeg"
            text(0,4)=="fLaC"->"audio/flac"
            text(0,5)=="%PDF-"->"application/pdf"
            else->null
        }
    }
}
