/* AntiSocial, GPL-3.0-or-later. Optional data-only media tools. */
package app.antisocial.android

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.concurrent.ConcurrentHashMap

class ContextMediaTools(private val repo:Repository) {
    private val context=repo.context
    private val preferences=context.getSharedPreferences("context-media",Context.MODE_PRIVATE)
    private val root=File(context.filesDir,"context-media")
    private val client=ContextMediaClient(context)
    private val lane=Mutex();private val mutations=Mutex()
    private val calls=ConcurrentHashMap.newKeySet<Job>()
    private val gson=Gson()
    val state=MutableStateFlow(ContextMediaState())
    val availableOcrLanguages=listOf("eng" to "English","spa" to "Spanish","fra" to "French","deu" to "German","por" to "Portuguese","ita" to "Italian","ara" to "Arabic","rus" to "Russian","hin" to "Hindi","jpn" to "Japanese","kor" to "Korean","chi_sim" to "Chinese (simplified)")
    private val catalog:ContextMediaCatalog by lazy {
        @Suppress("DEPRECATION") val signatures=if(Build.VERSION.SDK_INT>=28)context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners else context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNATURES).signatures!!
        val key=CertificateFactory.getInstance("X.509").generateCertificate(signatures.first().toByteArray().inputStream()).publicKey
        ContextMediaCatalog.verify(context.assets.open("context-media/catalog.json").use {it.readBytes()},context.assets.open("context-media/catalog.sig").use {it.readBytes()},key)
    }
    private val store by lazy {ContextMediaStore(root,repo.gateway,catalog)}
    fun hasDownload(kind:ContextMediaKind,language:String="eng"):Boolean=store.hasDownload(catalog.pack(kind,language))
    fun downloadBytes(kind:ContextMediaKind,language:String="eng"):Long=catalog.pack(kind,language).downloadBytes
    fun capabilities():ContextMediaCapabilities {
        val supported=Build.SUPPORTED_ABIS.first() in setOf("arm64-v8a","x86_64")
        val info=ActivityManager.MemoryInfo().also(context.getSystemService(ActivityManager::class.java)::getMemoryInfo)
        val warm=Build.VERSION.SDK_INT>=29&&context.getSystemService(PowerManager::class.java).currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE
        val ocr=supported&&!warm&&!info.lowMemory&&info.availMem>192L*1024*1024
        val speech=ocr&&info.totalMem>=2L*1024*1024*1024&&info.availMem>512L*1024*1024
        val reason=when { !supported->"Local media tools need a supported 64-bit device.";warm->"Media tools paused while the device cools.";!ocr->"Memory is low. Close another app to use local media tools.";!speech->"Speech needs more available memory; image text remains available.";else->null }
        return ContextMediaCapabilities(supported,ocr,speech,reason=reason)
    }
    suspend fun refresh()=withContext(Dispatchers.IO) {
        try {val languages=catalog.packs.filter {it.kind==ContextMediaKind.OCR&&store.installed(it)!=null}.map {it.language}.sortedBy {if(it==preferences.getString("ocr-language","eng"))0 else 1}
            state.value=state.value.copy(ocrEnabled=preferences.getBoolean("ocr",false)&&languages.isNotEmpty(),speechEnabled=preferences.getBoolean("speech",false)&&store.installed(catalog.pack(ContextMediaKind.SPEECH))!=null,
                languages=languages,speechInstalled=store.installed(catalog.pack(ContextMediaKind.SPEECH))!=null,localBytes=store.localBytes())
        }catch(e:Exception){state.value=state.value.copy(message=e.message ?: "Could not read optional media tools.")}
    }
    /** An explicit Context read includes the words inside its images. Prepare
     * the small English reader once, rather than silently treating pictures as
     * empty text. Scrolling/background work never starts a download, and an
     * explicit off choice is always respected. Speech remains separately opt-in. */
    internal suspend fun prepareRequestedImages() {
        if(preferences.contains("ocr")||!capabilities().ocrAllowed)return
        val now=System.currentTimeMillis()
        if(preferences.getLong("requested-ocr-retry",0)>now)return
        try {install(ContextMediaKind.OCR,"eng");preferences.edit().remove("requested-ocr-retry").apply()}
        catch(e:CancellationException){throw e}
        catch(_:Exception){preferences.edit().putLong("requested-ocr-retry",now+15*60_000L).apply()}
    }
    fun download(kind:ContextMediaKind,language:String="eng") {
        ensureEnabled();admit(kind)
        catalog.pack(kind,language)
        val request=OneTimeWorkRequestBuilder<ContextMediaDownloadWorker>().setInputData(workDataOf("kind" to kind.name,"language" to language))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if(kind==ContextMediaKind.OCR&&language=="eng")NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build()).build()
        state.value=state.value.copy(working=true,message="Preparing optional media tool…")
        WorkManager.getInstance(context).enqueueUniqueWork("context-media-download",ExistingWorkPolicy.KEEP,request)
    }
    fun cancelDownload(){WorkManager.getInstance(context).cancelUniqueWork("context-media-download");state.value=state.value.copy(working=false,progress=null,message="Download paused · tap Download to resume")}
    internal suspend fun install(kind:ContextMediaKind,language:String)=mutations.withLock {
        try {ensureEnabled();admit(kind);state.value=state.value.copy(working=true,message="Downloading verified media data…")
            val bundled:((String)->java.io.InputStream)?=if(kind==ContextMediaKind.OCR&&language=="eng")({name->context.assets.open("discovery-ocr/$name")})else null
            store.install(catalog.pack(kind,language),openBundled=bundled){state.value=state.value.copy(progress=it)}
            activate(kind,true,language);state.value=state.value.copy(message="Media tool ready · processed on this device")
        }catch(e:CancellationException){state.value=state.value.copy(message="Download paused · tap Download to resume");throw e}
        catch(e:Throwable){state.value=state.value.copy(message=e.message?.take(240) ?: "The media tool could not be installed.");throw e}
        finally {state.value=state.value.copy(working=false,progress=null);refresh()}
    }
    private fun input(pack:ContextMediaPack,directory:File)=Bundle().apply {
        putString("kind",pack.kind.name);putString("language",pack.language)
        if(pack.kind==ContextMediaKind.OCR)putString("path",directory.path)
        else {putString("model",File(directory,"ggml-base-q5_1.bin").path);putString("vad",File(directory,"ggml-silero-v6.2.0.bin").path)}
    }
    suspend fun activate(kind:ContextMediaKind,enabled:Boolean,language:String="eng") {
        if(!enabled){preferences.edit().putBoolean(kind.name.lowercase(),false).commit();calls.toList().forEach {it.cancel()};client.release();refresh();state.value=state.value.copy(message="Media tool paused · downloaded data kept");return}
        ensureEnabled();admit(kind)
        lane.withLock {
            val pack=catalog.pack(kind,language);val directory=store.verify(pack)
            repo.ai.releaseForMedia()
            withTimeout(90_000){client.request(ContextMediaIpc.PREPARE,input(pack,directory))}
            preferences.edit().putBoolean(kind.name.lowercase(),true).apply {if(kind==ContextMediaKind.OCR)putString("ocr-language",language)}.commit()
        };refresh()
    }
    suspend fun remove(kind:ContextMediaKind,language:String="eng") {
        cancelDownload();mutations.withLock {calls.toList().forEach {it.cancel()};client.release();lane.withLock {store.remove(catalog.pack(kind,language))}
        refresh()
        if(kind==ContextMediaKind.SPEECH||state.value.languages.isEmpty())preferences.edit().putBoolean(kind.name.lowercase(),false).commit()
        refresh();state.value=state.value.copy(message="Optional media data removed")
    }}
    private fun ensureEnabled(){check(repo.ai.preferences.enabled){"Enable optional AI before using Context media tools."}}
    private fun admit(kind:ContextMediaKind){val caps=capabilities();check(if(kind==ContextMediaKind.OCR)caps.ocrAllowed else caps.speechAllowed){caps.reason ?: "This media tool is unavailable on this device."}}
    fun cancel(){calls.toList().forEach {it.cancel()};repo.scope.launch {client.release()}}
    suspend fun release(){calls.toList().forEach {it.cancel()};client.release()}
    private suspend fun <T> tracked(block:suspend ()->T):T {
        val job=currentCoroutineContext()[Job];job?.let {calls.add(it)}
        return try {lane.withLock {state.value=state.value.copy(working=true,jobId=java.util.UUID.randomUUID().toString());block()}}
        finally {job?.let {calls.remove(it)};state.value=state.value.copy(working=false,jobId=null)}
    }
    private suspend fun selected(kind:ContextMediaKind,language:String):Bundle {
        ensureEnabled();admit(kind)
        check(preferences.getBoolean(kind.name.lowercase(),false)){"Enable this optional media tool in Context settings first."}
        val pack=catalog.pack(kind,language);val dir=store.installed(pack) ?: error("Download this optional media tool first.")
        repo.ai.releaseForMedia();return input(pack,dir)
    }
    private fun localFile(file:File):File {
        val canonical=file.canonicalFile
        require(listOf(context.filesDir,context.cacheDir).any {canonical.path.startsWith(it.canonicalPath+File.separator)}){"Copy this attachment into anti social's private local storage before processing it."}
        require(canonical.isFile&&canonical.length() in 1..512L*1024*1024){"This local attachment is unavailable or exceeds the media processing limit."}
        return canonical
    }
    private val hashes=object:LinkedHashMap<String,String>(64,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>)=size>64}
    suspend fun sourceSha256(localFile:File):String=sourceHash(this.localFile(localFile))
    private suspend fun sourceHash(file:File):String=withContext(Dispatchers.IO) {
        val key="${file.canonicalPath}|${file.length()}|${file.lastModified()}"
        synchronized(hashes){hashes[key]}?.let {return@withContext it}
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {source->val bytes=ByteArray(128*1024);while(true){ensureActive();val count=source.read(bytes);if(count<0)break;digest.update(bytes,0,count)}}
        val value=digest.digest().joinToString(""){"%02x".format(it)};synchronized(hashes){hashes[key]=value};value
    }
    private suspend fun request(command:Int,input:Bundle,file:File):ByteArray=withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->input.putParcelable("file",descriptor);client.request(command,input).getByteArray("result") ?: error("The media tool returned no result.")}
    }
    private suspend fun ocrPixels(input:Bundle,image:android.graphics.Bitmap,scaled:Boolean):OcrResult {
        try {
            input.putParcelable("bitmap",image);input.putBoolean("scaled",scaled)
            return gson.fromJson(client.request(ContextMediaIpc.OCR_PIXELS,input).getByteArray("result")?.toString(Charsets.UTF_8) ?: error("The image text tool returned no result"),OcrResult::class.java)
        }finally {image.recycle()}
    }
    private suspend fun decodedOcr(input:Bundle,file:File):OcrResult {
        val raster=repo.documentSandbox.ocrImage(file)
        return ocrPixels(input,raster.bitmap,raster.scaled)
    }
    suspend fun ocr(localFile:File,language:String="eng"):OcrResult=tracked {
        val file=this.localFile(localFile);val input=selected(ContextMediaKind.OCR,language);val hash=sourceHash(file)
        val result=decodedOcr(input,file)
        check(result.width>0&&result.height>0&&result.meanConfidence.isFinite()&&result.lines.size<=512)
        result.copy(sourceSha256=hash)
    }
    /** Discovery owns its own opt-out. It does not activate AI or Context tools. */
    internal suspend fun discoveryOcr(localFile:File):OcrResult=tracked {
        check(repo.prefs.localFlyerReading){"Flyer reading is off"};admit(ContextMediaKind.OCR)
        val file=this.localFile(localFile);val pack=catalog.pack(ContextMediaKind.OCR,"eng")
        val directory=store.install(pack,openBundled={name->context.assets.open("discovery-ocr/$name")})
        val hash=sourceHash(file)
        val result=decodedOcr(input(pack,directory),file)
        check(result.width>0&&result.height>0&&result.meanConfidence.isFinite()&&result.lines.size<=512)
        result.copy(sourceSha256=hash)
    }
    suspend fun transcribe(localFile:File,startMs:Long=0,durationMs:Long=120_000):TranscriptResult=tracked {
        require(startMs>=0&&durationMs in 1..capabilities().maxAudioDurationMs&&startMs<Long.MAX_VALUE-durationMs)
        val file=this.localFile(localFile);val input=selected(ContextMediaKind.SPEECH,"multilingual").apply {putLong("startMs",startMs);putLong("durationMs",durationMs)};val hash=sourceHash(file)
        val result=gson.fromJson(request(ContextMediaIpc.SPEECH,input,file).toString(Charsets.UTF_8),TranscriptResult::class.java)
        check(result.segments.size<=1024&&result.nextOffsetMs>startMs&&result.segments.all {it.startMs>=startMs&&it.endMs>=it.startMs&&it.meanTokenProbability.isFinite()&&it.noSpeechProbability.isFinite()})
        result.copy(sourceSha256=hash)
    }
    suspend fun pdfPage(localFile:File,pageIndex:Int,language:String="eng",allowOcr:Boolean=true):ContextPdfPageResult=tracked {
        ensureEnabled();require(pageIndex>=0);val file=this.localFile(localFile);val hash=sourceHash(file)
        val pack=catalog.pack(ContextMediaKind.OCR,language);val directory=store.installed(pack)
        val ocrEnabled=preferences.getBoolean("ocr",false)&&directory!=null&&capabilities().ocrAllowed
        val embedded=repo.documentSandbox.pdfEmbedded(file,pageIndex)
        if(embedded.text.isNotBlank())return@tracked embedded.copy(sourceSha256=hash)
        if(!allowOcr)return@tracked embedded.copy(sourceSha256=hash,available=false,reason="This scanned page is waiting for permission to use local image text extraction.")
        if(!ocrEnabled)return@tracked embedded.copy(sourceSha256=hash,available=false,reason="This page needs the optional image text tool. Enable it in Context settings.")
        repo.ai.releaseForMedia()
        val image=repo.documentSandbox.pdfOcrPage(file,pageIndex)
        val result=ocrPixels(input(pack,directory!!),image,true)
        embedded.copy(text=result.text,ocr=true,uncertain=result.uncertain,truncated=result.truncated,sourceSha256=hash)
    }
    /** Embedded PDF text is ordinary document parsing, not AI.
     * The isolated worker has no network; no OCR/model is downloaded or enabled. */
    internal suspend fun pdfTextPage(localFile:File,pageIndex:Int):ContextPdfPageResult=tracked {
        require(pageIndex in 0..5)
        val file=this.localFile(localFile)
        try {repo.documentSandbox.pdfText(file,pageIndex)}catch(e:CancellationException){throw e}catch(e:Exception){throw IllegalStateException("This document could not be read as a menu.",e)}
    }
}

class ContextMediaDownloadWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        return try {setForeground(getForegroundInfo());val repo=(applicationContext as AntiSocialApplication).repo;repo.awaitReady()
            repo.mediaTools.install(ContextMediaKind.valueOf(inputData.getString("kind") ?: return Result.failure()),inputData.getString("language") ?: "eng");Result.success()
        }catch(e:CancellationException){throw e}catch(_:Exception){Result.failure()}
    }
    override suspend fun getForegroundInfo():ForegroundInfo {
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("context-media","Optional media tools",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"context-media").setSmallIcon(R.drawable.ic_antisocial).setContentTitle("Downloading optional media tools").setContentText("Open anti social to see progress or pause")
            .addAction(android.R.drawable.ic_media_pause,"Pause",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(85,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else ForegroundInfo(85,notification)
    }
}
