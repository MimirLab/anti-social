/* AntiSocial, GPL-3.0-or-later. Optional local media extraction; no reader dependency. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

enum class ContextMediaKind { OCR, SPEECH }
data class ContextMediaCapabilities(val supported: Boolean,val ocrAllowed: Boolean,val speechAllowed: Boolean,
    val maxImagePixels: Int=12_000_000,val maxAudioDurationMs: Long=1_200_000,val reason: String?=null)
data class ContextMediaAsset(val filename: String,val url: String,val bytes: Long,val sha256: String)
data class ContextMediaPack(val id: String,val label: String,val kind: ContextMediaKind,val language: String,
    val revision: String,val assets: List<ContextMediaAsset>) {val downloadBytes: Long get()=assets.sumOf {it.bytes}}
data class ContextMediaProgress(val packId: String,val downloadedBytes: Long,val totalBytes: Long,val verifying: Boolean=false)
data class ContextMediaState(val ocrEnabled: Boolean=false,val speechEnabled: Boolean=false,
    val languages: List<String> = emptyList(),val speechInstalled: Boolean=false,val progress: ContextMediaProgress?=null,
    val working: Boolean=false,val jobId: String?=null,val localBytes: Long=0,val message: String="Optional media tools · off")
data class OcrLine(val text: String,val confidence: Float,val left: Int,val top: Int,val right: Int,val bottom: Int)
data class OcrResult(val text: String,val lines: List<OcrLine>,val language: String,val width: Int,val height: Int,
    val meanConfidence: Float,val uncertain: Boolean,val truncated: Boolean=false,val sourceSha256: String="",
    val engine: String="Tesseract 5 · fast language model",val scaled: Boolean=false)
data class TranscriptSegment(val startMs: Long,val endMs: Long,val text: String,val meanTokenProbability: Float,
    val noSpeechProbability: Float,val uncertain: Boolean)
data class TranscriptResult(val text: String,val segments: List<TranscriptSegment>,val language: String,
    val durationMs: Long,val hasSpeech: Boolean,val uncertain: Boolean,val truncated: Boolean=false,
    val sourceSha256: String="",val engine: String="Whisper base multilingual · Q5_1 · Silero VAD",
    val startOffsetMs: Long=0,val nextOffsetMs: Long=durationMs,val complete: Boolean=true,val sourceDurationMs: Long=durationMs)

data class ContextPdfPageResult(val pageIndex: Int,val pageCount: Int,val text: String,val ocr: Boolean,val uncertain: Boolean,
    val sourceSha256: String="",val available: Boolean=true,val reason: String?=null,val truncated: Boolean=false)

/** The release signer authenticates immutable model URLs and hashes. */
class ContextMediaCatalog private constructor(val packs: List<ContextMediaPack>,internal val identity: String) {
    fun pack(kind: ContextMediaKind,language: String="eng")=packs.singleOrNull {it.kind==kind&&(kind==ContextMediaKind.SPEECH||it.language==language)}
        ?: error("This language pack is not included in this app's verified catalog.")
    companion object {
        fun verify(bytes: ByteArray,signature: ByteArray,key: PublicKey): ContextMediaCatalog {
            require(bytes.size in 1..128*1024&&signature.size in 64..2048)
            require(Signature.getInstance("SHA256withRSA").run {initVerify(key);update(bytes);verify(signature)}){"Media model catalog signature is invalid."}
            val json=JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(json["schema"].asString=="antisocial-context-media-v1")
            val packs=json.getAsJsonArray("packs").map {entry->
                val item=entry.asJsonObject
                ContextMediaPack(item["id"].asString,item["label"].asString,ContextMediaKind.valueOf(item["kind"].asString),
                    item["language"].asString,item["revision"].asString,item.getAsJsonArray("assets").map {asset->asset.asJsonObject.let {
                        ContextMediaAsset(it["filename"].asString,it["url"].asString,it["bytes"].asLong,it["sha256"].asString)
                    }})
            }
            require(packs.size in 2..128&&packs.map {it.id}.distinct().size==packs.size)
            packs.forEach {pack->
                require(pack.id.matches(Regex("[a-z0-9-]{1,80}"))){"Invalid media pack id: ${pack.id}"}
                require(pack.language.matches(Regex("[a-z_]{2,16}"))){"Invalid media pack language: ${pack.id}"}
                require(pack.revision.matches(Regex("[a-f0-9]{40,64}"))){"Invalid media pack revision: ${pack.id}"}
                require(pack.assets.size in 1..3&&pack.assets.map {it.filename}.distinct().size==pack.assets.size)
                pack.assets.forEach {asset->
                    require(asset.filename.matches(Regex("[A-Za-z0-9_.-]{1,100}"))&&asset.filename !in setOf(".",".."))
                    require(asset.bytes in 1..512L*1024*1024&&asset.sha256.matches(Regex("[a-f0-9]{64}")))
                    val uri=java.net.URI(asset.url);require(uri.scheme=="https"&&uri.host!=null&&uri.userInfo==null&&uri.fragment==null)
                }
            }
            require(packs.count {it.kind==ContextMediaKind.SPEECH}==1&&packs.any {it.kind==ContextMediaKind.OCR&&it.language=="eng"})
            val identity=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            return ContextMediaCatalog(packs,identity)
        }
    }
}

/** Resumable data-only installation. Execution remains bundled Android code. */
class ContextMediaStore(private val root: File,private val gateway: NetworkGateway,private val catalog: ContextMediaCatalog) {
    private val mutation=Mutex()
    private fun directory(pack: ContextMediaPack)=File(root,"packs/${pack.id}-${pack.revision}")
    private fun marker(pack: ContextMediaPack)=pack.id+":"+pack.revision+":"+pack.assets.joinToString(","){it.sha256}
    fun installed(pack: ContextMediaPack): File? {
        val directory=directory(pack)
        return directory.takeIf {File(it,".complete").takeIf {f->f.isFile&&f.length()<=512}?.readText()==marker(pack)&&pack.assets.all {asset->File(it,asset.filename).let {file->file.isFile&&file.length()==asset.bytes}}}
    }
    private fun versions(area: String,pack: ContextMediaPack)=File(root,area).listFiles().orEmpty().filter {it.name.matches(Regex(Regex.escape(pack.id)+"-[a-f0-9]{40,64}"))}
    fun hasDownload(pack: ContextMediaPack): Boolean=listOf("packs","staging").any {area->versions(area,pack).any {it.walkTopDown().any {file->file.isFile&&file.length()>0}}}
    suspend fun verify(pack: ContextMediaPack): File=withContext(Dispatchers.IO) {
        val dir=installed(pack) ?: error("Download this optional media tool first.")
        pack.assets.forEach {asset->if(hash(File(dir,asset.filename))!=asset.sha256){File(dir,".complete").delete();error("The media model changed. Remove it and download a verified copy.")}}
        dir
    }
    suspend fun install(pack: ContextMediaPack,openBundled:((String)->java.io.InputStream)?=null,progress: (ContextMediaProgress)->Unit={}): File=mutation.withLock {withContext(Dispatchers.IO) {
        require(catalog.packs.contains(pack));installed(pack)?.let {return@withContext verify(pack)}
        root.mkdirs();check(root.usableSpace>pack.downloadBytes+32L*1024*1024){"Free more storage before downloading this media tool."}
        val stage=File(root,"staging/${pack.id}-${pack.revision}").apply {mkdirs()}
        var completed=0L
        for(asset in pack.assets) {
            ensureActive();val file=File(stage,asset.filename)
            if(file.exists()&&file.length()>asset.bytes)file.delete()
            if(openBundled==null)gateway.downloadAiArtifact(asset.url,file,asset.bytes){progress(ContextMediaProgress(pack.id,completed+it,pack.downloadBytes))}
            else openBundled(asset.filename).use {input->file.outputStream().use {out->
                val bytes=ByteArray(65536);var count=0L
                while(true){ensureActive();val size=input.read(bytes);if(size<0)break;count+=size;require(count<=asset.bytes){"Bundled image reader exceeds its signed size"};out.write(bytes,0,size)}
                require(count==asset.bytes){"Bundled image reader is incomplete"}
            }}
            progress(ContextMediaProgress(pack.id,completed+asset.bytes,pack.downloadBytes,true))
            if(hash(file)!=asset.sha256){file.delete();error("A media model failed its integrity check. Tap Download to retry.")}
            completed+=asset.bytes
        }
        ensureActive();File(stage,".complete").writeText(marker(pack))
        val destination=directory(pack);destination.parentFile!!.mkdirs()
        if(destination.exists())destination.deleteRecursively()
        check(stage.renameTo(destination)){"Could not activate the complete media model."}
        destination
    }}
    suspend fun remove(pack: ContextMediaPack)=mutation.withLock {withContext(Dispatchers.IO) {
        versions("packs",pack).forEach {it.deleteRecursively()}
        versions("staging",pack).forEach {it.deleteRecursively()}
    }}
    fun localBytes(): Long=root.walkTopDown().filter {it.isFile}.sumOf {it.length()}
    private suspend fun hash(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val bytes=ByteArray(128*1024);while(true) {
            currentCoroutineContext().ensureActive();val count=input.read(bytes);if(count<0)break;digest.update(bytes,0,count)
        }};return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
