/* anti social, GPL-3.0-or-later. On-demand, deterministic inspection; all acquisition uses NetworkGateway. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class MediaAcquisition(val url:String,val finalUrl:String,val sha256:String,val mime:String,
    val manifestUrls:List<String>,val at:Long)
internal data class MediaInfoResult(val metadata:AiMetadata,val checkedAt:Long,val externalCredential:Boolean=false,
    val detail:String?=null,val version:Int=BuildConfig.VERSION_CODE,val hasExternalReference:Boolean=false,val networkKey:String?=null)
internal data class MediaInfoAsset(val url:String,val sourceUrl:String,val label:String,val acquire:Boolean=true)
internal data class MediaInfoProgress(val results:Map<String,MediaInfoResult> = emptyMap(),val working:Boolean=false,
    val checked:Int=0,val total:Int=0,val note:String?=null)

/** Persisted ordinary evidence is bounded independently of protected offline copies. Network misses
 * have a short lifetime; byte-local results require the same SHA and analysis implementation.
 */
internal class MediaInfoStore(private val repo:Repository,private val clock:()->Long=System::currentTimeMillis,
    private val file:File=File(repo.context.cacheDir,"media-info-v1.json")) {
    private data class State(val receipts:Map<String,MediaAcquisition> = emptyMap(),val results:Map<String,MediaInfoResult> = emptyMap())
    private val lock=Any();private val lane=Mutex();private var loaded=false;private var epoch=0L
    private val pendingReceipts=ConcurrentHashMap<String,MediaAcquisition>()
    private val writes=Channel<Unit>(Channel.CONFLATED)
    private val receipts=linkedMapOf<String,MediaAcquisition>();private val results=linkedMapOf<String,MediaInfoResult>()
    init {repo.scope.launch(Dispatchers.IO){for(ignored in writes){delay(400);synchronized(lock){load();persist()}}}}
    internal var inspectPixels:(ByteArray,Map<String,String>)->NovelAiInspection=NovelAiMetadata::inspect
    internal var inspectDetached:(ByteArray,String,ByteArray)->AiMetadata?=C2paInspector::inspectDetached
    internal var fetchManifest:suspend (String,String)->ByteArray={url,source->repo.gateway.mediaPayload(url,source,4L*1024*1024,"other").bytes}
    private fun load() {
        if(loaded)return;loaded=true
        runCatching {if(file.isFile&&file.length()<=MAX_BYTES)repo.gson.fromJson(file.readText(),State::class.java)?.let {s->
            s.receipts.orEmpty().entries.take(256).filter {it.key==Repository.hash(it.value.url)&&validReceipt(it.value)}.forEach {receipts[it.key]=it.value}
            s.results.orEmpty().entries.take(128).filter {it.key.matches(Regex("[a-f0-9]{64}"))&&validResult(it.value)&&it.value.metadata.sha256==it.key}.forEach {results[it.key]=it.value}
        }}
    }
    private fun validReceipt(r:MediaAcquisition)=runCatching {clock()-r.at in 0 until 7*CachePolicy.DAY&&r.url.length<=8192&&
        r.finalUrl.length<=8192&&r.mime.length<=160&&r.sha256.matches(Regex("[a-f0-9]{64}"))&&r.manifestUrls.size<=4&&r.manifestUrls.all {MediaCredentialLinks.resolve(it,r.finalUrl)==it}}.getOrDefault(false)
    private fun validResult(r:MediaInfoResult)=runCatching {r.version==BuildConfig.VERSION_CODE&&clock()-r.checkedAt in 0 until 7*CachePolicy.DAY&&
        r.metadata.disclosures.size<=64&&r.metadata.recipes.size<=8&&r.detail.orEmpty().length<=500}.getOrDefault(false)
    fun acquired(url:String,payload:GatewayMediaPayload) {
        val receipt=MediaAcquisition(url,payload.finalUrl,sha(payload.bytes),payload.contentType.take(160),payload.manifestUrls,clock())
        val key=Repository.hash(url);pendingReceipts[key]=receipt
        while(pendingReceipts.size>256)pendingReceipts.keys.firstOrNull {it!=key}?.let(pendingReceipts::remove) ?: break
        // Neither JSON serialization nor disk IO belongs on the media delivery path.
        writes.trySend(Unit)
    }
    fun observed(digest:String?):AiMetadata?=synchronized(lock){load();digest?.let {results[it]?.takeIf(::validResult)?.metadata}}
    suspend fun inspect(assets:List<MediaInfoAsset>,localOnly:Boolean=false,onProgress:suspend (MediaInfoProgress)->Unit) = withContext(Dispatchers.IO) {
        val selected=assets.distinctBy {it.url}.take(64);val observed=linkedMapOf<String,MediaInfoResult>()
        val generation=synchronized(lock){load();epoch};var checked=0;var requests=0
        suspend fun publish(working:Boolean,note:String?=null){ensureActive();check(synchronized(lock){epoch==generation}){"Inspection cache cleared"};onProgress(MediaInfoProgress(observed.toMap(),working,checked,assets.distinctBy {it.url}.size,note))}
        publish(true)
        try {withTimeout(if(repo.gateway.currentPolicy().tor)45_000L else 25_000L) {repo.gateway.boundedMediaRead {lane.withLock {
            for(asset in selected) {
                ensureActive()
                val local=if(localOnly)repo.savedMedia(asset.url)else repo.availableMedia(asset.url)
                val media=local ?: if(localOnly||!asset.acquire||requests>=4)null else try {
                    requests++
                    repo.awaitReady()
                    // Existing content takes precedence over requested supplementary inspection.
                    while(repo.feedRefresh.value.running||repo.pool.hasForegroundReads)delay(100)
                    repo.gateway.deferredRead {repo.media(asset.url,asset.sourceUrl)}
                }catch(e:CancellationException){throw e}catch(_:Exception){null}
                if(media==null||!media.isFile||media.length()>EmbeddedAiMetadata.MAX_BYTES)continue
                val bytes=try {read(media)}catch(_:java.io.IOException){continue}
                if(bytes.size>EmbeddedAiMetadata.MAX_BYTES)continue
                val digest=sha(bytes)
                val previous=synchronized(lock){results[digest]?.takeIf(::validResult)}
                if(previous!=null)observed[asset.url]=previous
                publish(true)
                // Reopening does not repeat decoding, validation, or failed online lookups.
                if(previous!=null&&(localOnly||clock()-previous.checkedAt<6*60*60_000L&&(!previous.hasExternalReference||previous.networkKey==networkKey()))){
                    repo.aiMetadata.inspect(asset.url,media);repo.aiMetadata.enrich(asset.url,previous.metadata)
                    checked++;publish(true);continue
                }
                val base=repo.aiMetadata.inspect(asset.url,media) ?: AiMetadata(inspectedAt=clock(),sha256=digest)
                val embedded=EmbeddedAiMetadata.inspect(bytes)
                while(repo.ai.processing||repo.ai.state.value.working||repo.mediaTools.state.value.working)delay(100)
                val pixels=runInterruptible {inspectPixels(bytes,embedded.textFields)}
                val recipes=(base.recipes+pixels.recipes).groupBy {it.generator to it.carrier}.map {(_,v)->v.last()}
                    .groupBy {it.copy(carrier="")}.map {(value,items)->value.copy(carrier=items.map {it.carrier}.distinct().joinToString(" · "))}.take(8)
                val verified=recipes.any {it.signature==RecipeSignature.VALID}
                var report=base.copy(recipes=recipes,disclosures=(base.disclosures+pixels.recipes.map {
                    AiDisclosure(if(it.signature==RecipeSignature.VALID)AiUse.GENERATED else it.use,
                        if(it.signature==RecipeSignature.VALID)AiEvidence.NOVELAI_SIGNATURE else AiEvidence.GENERATION_RECIPE,tool=it.generator)
                }).distinct().take(64))
                var external=false;var detail=if(pixels.limited)"Hidden-record inspection exceeded its image-size limit."else null
                val receipt=(pendingReceipts[Repository.hash(asset.url)] ?: synchronized(lock){receipts[Repository.hash(asset.url)]})?.takeIf {it.sha256==digest&&validReceipt(it)}
                val refs=(receipt?.manifestUrls.orEmpty()+embedded.credentialReferences.mapNotNull {MediaCredentialLinks.resolve(it,receipt?.finalUrl ?: asset.url)}).distinct().take(4)
                val networkStamp=networkKey();var attempted=false
                val remoteRelevant=base.credentialState in setOf(AiCredentialState.NOT_PRESENT,AiCredentialState.UNCHECKED)&&refs.isNotEmpty()
                observed[asset.url]=MediaInfoResult(report,clock(),hasExternalReference=remoteRelevant)
                repo.aiMetadata.enrich(asset.url,report)
                publish(true)
                if(!localOnly&&remoteRelevant) {
                    repo.awaitReady();val policy=repo.gateway.currentPolicy()
                    for(ref in refs) {
                        if(requests>=4)break
                        ensureActive();requests++;attempted=true
                        try {
                            val manifest=repo.gateway.deferredRead {withTimeout(if(policy.tor)15_000L else 8_000L){fetchManifest(ref,asset.sourceUrl)}}
                            check(manifest.size<=4*1024*1024)
                            ensureActive();check(policy==repo.gateway.currentPolicy()){"Network policy changed"}
                            val credentials=runInterruptible {inspectDetached(bytes,embedded.mime ?: receipt?.mime?.substringBefore(';') ?: "",manifest)}
                            if(credentials!=null) {
                                report=report.copy(credentialState=credentials.credentialState,trust=credentials.trust,signer=credentials.signer,
                                    disclosures=(report.disclosures+credentials.disclosures).distinct().take(64),removals=(report.removals+credentials.removals).distinct(),
                                    limitations=(report.limitations-AiMetadataLimit.REMOTE_NOT_FETCHED+credentials.limitations).distinct())
                                external=true;detail="Separate Content Credentials checked against this exact file.";break
                            }else detail="The separate credential could not be validated against this file."
                        }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();detail="The separate credential did not respond in time."}
                         catch(e:CancellationException){throw e}
                         catch(_:Exception){detail="The separate credential is unavailable right now."}
                    }
                }else if(localOnly&&refs.isNotEmpty())detail="Offline inspection; separate online credentials were not requested."
                if(verified)report=report.copy(disclosures=report.disclosures.filterNot {it.evidence==AiEvidence.GENERATION_RECIPE&&it.tool=="NovelAI"})
                // Do not bind a verdict to a URL whose cache file was replaced during investigation.
                ensureActive();if(!media.isFile||media.length()>EmbeddedAiMetadata.MAX_BYTES||sha(read(media))!=digest)continue
                val value=MediaInfoResult(report,clock(),external,detail,hasExternalReference=remoteRelevant,networkKey=if(attempted)networkStamp else null)
                synchronized(lock){check(epoch==generation){"Inspection cache cleared"};results.remove(digest);results[digest]=value;persist()}
                repo.aiMetadata.enrich(asset.url,report)
                observed[asset.url]=value;checked++;publish(true)
            }
        }}};publish(false,if(checked<assets.distinctBy {it.url}.size)"$checked of ${assets.distinctBy {it.url}.size} media inspected. Other files were unavailable or exceeded this inspection's limits."else null)
        }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();publish(false,"The inspection time budget ended. Available findings are shown.")}
         catch(e:CancellationException){currentCoroutineContext().ensureActive();publish(false,"Additional checks paused while content loaded.")}
         catch(_:Exception){if(synchronized(lock){epoch==generation})publish(false,"Some checks are unavailable. Available findings are shown.")else onProgress(MediaInfoProgress(total=assets.size,note="Inspection stopped after the cache was cleared."))}
        repo.scheduleCleanup()
    }
    private fun persist() {
        pendingReceipts.entries.forEach {(k,v)->if(pendingReceipts.remove(k,v)){receipts.remove(k);receipts[k]=v}}
        receipts.entries.removeIf {!validReceipt(it.value)};results.entries.removeIf {!validResult(it.value)}
        while(receipts.size>256)receipts.remove(receipts.keys.first());while(results.size>128)results.remove(results.keys.first())
        if(receipts.isEmpty()&&results.isEmpty()){file.delete();File(file.parentFile,file.name+".tmp").delete();return}
        var json=repo.gson.toJson(State(receipts,results))
        while(json.toByteArray().size>MAX_BYTES&&(receipts.isNotEmpty()||results.isNotEmpty())) {
            if(results.isNotEmpty())results.remove(results.keys.first())else receipts.remove(receipts.keys.first())
            json=repo.gson.toJson(State(receipts,results))
        }
        val temporary=File(file.parentFile,file.name+".tmp")
        try {temporary.writeText(json);temporary.renameTo(file)}catch(_:Exception){}finally {temporary.delete()}
    }
    fun cleanup(clear:Boolean=false)=synchronized(lock){load();if(clear){epoch++;receipts.clear();pendingReceipts.clear();results.clear();file.delete();File(file.parentFile,file.name+".tmp").delete()}else persist()}
    fun bytes()=synchronized(lock){if(file.isFile)file.length()else 0L}
    private fun networkKey()="${repo.gateway.currentPolicy()}|originals=${repo.prefs.originalFallback}"
    companion object {
        const val MAX_BYTES=3L*1024*1024
        private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
        private fun read(file:File):ByteArray=file.inputStream().use {input->
            val out=java.io.ByteArrayOutputStream();val b=ByteArray(16384)
            while(out.size()<=EmbeddedAiMetadata.MAX_BYTES){val n=input.read(b,0,minOf(b.size,EmbeddedAiMetadata.MAX_BYTES+1-out.size()));if(n<0)break;out.write(b,0,n)}
            out.toByteArray()
        }
    }
}
