/* AntiSocial, GPL-3.0-or-later. English preparation is local and follows NetworkGateway. */
package app.antisocial.android

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
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
import java.security.cert.CertificateFactory
import java.util.Locale

internal data class LanguageOptions(val enabled:Boolean=true,val automatic:Boolean=true,val automaticMb:Int=160)
internal data class LanguageState(val installed:List<String> = emptyList(),val kept:Set<String> = emptySet(),val downloads:Set<String> = emptySet(),val localBytes:Long=0,
    val downloading:String?=null,val downloaded:Long=0,val total:Long=0,val message:String?=null)

internal class LanguageTools(private val repo:Repository) {
    private val context=repo.context;private val prefs=context.getSharedPreferences("context-languages",Context.MODE_PRIVATE)
    private val root=File(context.filesDir,"languages")
    private val client=ContextMediaClient(context,LanguageService::class.java)
    private val workerLane=Mutex();private val setupLane=Mutex();private val transferLane=Mutex()
    private val gson=Gson();private var runtimeReady=false
    private val verified=mutableMapOf<String,String>()
    val options=MutableStateFlow(LanguageOptions(prefs.getBoolean("enabled",true),prefs.getBoolean("automatic",true),prefs.getInt("budget",160).coerceIn(64,512)))
    val state=MutableStateFlow(LanguageState())
    private val catalog:TranslationCatalog by lazy {
        @Suppress("DEPRECATION") val signatures=if(Build.VERSION.SDK_INT>=28)context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners else context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNATURES).signatures!!
        val key=CertificateFactory.getInstance("X.509").generateCertificate(signatures.first().toByteArray().inputStream()).publicKey
        TranslationCatalog.verify(context.assets.open("translation/catalog.json").use {it.readBytes()},context.assets.open("translation/catalog.sig").use {it.readBytes()},key)
    }
    private val packs by lazy {TranslationPackStore(root,repo.gateway,catalog)}
    val available:List<Pair<String,String>> get()=catalog.packs.map {it.language to label(it.language)}.sortedBy {it.second}
    fun label(language:String)=Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH).takeIf {it.isNotBlank()} ?: language
    fun bytes(language:String)=requireNotNull(catalog.pack(language)).bytes
    fun update(value:LanguageOptions) {
        require(value.automaticMb in 64..512)
        prefs.edit().putBoolean("enabled",value.enabled).putBoolean("automatic",value.automatic).putInt("budget",value.automaticMb).commit();options.value=value
        if(!value.enabled)cancel()
        repo.scope.launch {cleanup()}
    }
    suspend fun refresh()=withContext(Dispatchers.IO) {
        state.value=state.value.copy(installed=catalog.packs.filter {packs.installed(it)!=null}.map {it.language},
            downloads=catalog.packs.filter {packs.hasDownload(it)}.map {it.language}.toSet(),
            kept=catalog.packs.filter {packs.installed(it)!=null&&packs.protected(it)}.map {it.language}.toSet(),localBytes=packs.localBytes())
    }
    fun download(language:String) {
        check(repo.ai.preferences.enabled){"Enable optional AI to prepare Context languages"};requireNotNull(catalog.pack(language))
        state.value=state.value.copy(downloading=language,message=null)
        val work=OneTimeWorkRequestBuilder<LanguageDownloadWorker>().setInputData(workDataOf("language" to language))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("context-language-download",ExistingWorkPolicy.KEEP,work)
    }
    fun cancelDownload(){WorkManager.getInstance(context).cancelUniqueWork("context-language-download");if(state.value.downloading!=null)state.value=state.value.copy(downloading=null,message="Download paused; its partial files can be resumed")}
    internal suspend fun install(language:String,keep:Boolean)=transferLane.withLock {
        check(repo.ai.preferences.enabled){"Optional AI is off"};val pack=requireNotNull(catalog.pack(language))
        try {
            state.value=state.value.copy(downloading=language,downloaded=0,total=pack.bytes,message=null)
            val dir=packs.install(pack,keep,options.value.automaticMb.toLong()*1024*1024) {done,total->state.value=state.value.copy(downloaded=done,total=total)}
            verified.remove(pack.id);state.value=state.value.copy(message="${label(language)} downloaded for offline reading")
            dir
        }catch(cancelled:CancellationException){state.value=state.value.copy(message="Language download paused; progress is retained");throw cancelled}
        catch(failure:Exception){state.value=state.value.copy(message=failure.message?.take(160) ?: "This language could not be downloaded");throw failure}
        finally {state.value=state.value.copy(downloading=null);refresh()}
    }
    suspend fun remove(language:String) {
        cancelDownload();workerLane.withLock {client.release();packs.remove(requireNotNull(catalog.pack(language)));verified.clear();File(root,"runtime").listFiles().orEmpty().filter {it.name.startsWith("$language-en-")&&it.extension=="yml"}.forEach {it.delete()}};refresh()
    }
    fun cancel(){cancelDownload();repo.scope.launch {release()}}
    suspend fun release()=workerLane.withLock {client.release()}
    suspend fun cleanup(clear:Boolean=false){transferLane.withLock {workerLane.withLock {packs.prune(options.value.automaticMb.toLong()*1024*1024,clear)}};refresh()}
    fun cachedBytes():Long=packs.automaticBytes()
    private suspend fun runtime()=setupLane.withLock {withContext(Dispatchers.IO) {
        if(runtimeReady)return@withContext
        val destination=File(root,"runtime").apply {mkdirs()};val lid=File(destination,"lid.176.ftz")
        val expected="8f3472cfe8738a7b6099e8e999c3cbfae0dcd15696aac7d7738a8039db603e83"
        // Hash the actual binary, not a lossy text conversion.
        fun binaryHash(file:File)=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
        if(!lid.isFile||lid.length()!=938013L||binaryHash(lid)!=expected) {
            val temporary=File(destination,"lid.partial");context.assets.open("translation/lid.176.ftz").use {source->temporary.outputStream().use {source.copyTo(it)}}
            check(binaryHash(temporary)==expected){"The bundled language detector could not be verified"}
            check(temporary.renameTo(lid)){"The language detector could not be prepared"}
        }
        val prefixes=File(destination,"prefixes").apply {mkdirs()}
        for(name in context.assets.list("translation/prefixes").orEmpty())if(name.matches(Regex("nonbreaking_prefix\\.[A-Za-z_-]+"))) {
            val output=File(prefixes,name);if(!output.isFile)context.assets.open("translation/prefixes/$name").use {source->output.outputStream().use {source.copyTo(it)}}
        }
        runtimeReady=true
    } }
    internal suspend fun detect(text:String):List<LanguagePrediction> {try {
        runtime()
        val result=workerLane.withLock {withTimeout(30_000) {client.request(LanguageIpc.DETECT,Bundle().apply {putString("path",File(root,"runtime/lid.176.ftz").path);putByteArray("text",text.toByteArray(Charsets.UTF_8))})}}
        val bytes=result.getByteArray("result") ?: error("The language detector returned no result")
        return gson.fromJson(bytes.toString(Charsets.UTF_8),Array<LanguagePrediction>::class.java).toList()
    }catch(cancelled:CancellationException){throw cancelled}catch(failure:SourceLanguageUnavailable){throw failure}catch(failure:Exception){throw SourceLanguageUnavailable(null,failure.message?.take(160) ?: "The offline language detector is unavailable")}}
    private suspend fun config(pack:TranslationPack):File=withContext(Dispatchers.IO) {
        val dir=packs.installed(pack) ?: error("This language needs a download")
        val stamp=pack.assets.joinToString("|") {a->File(dir,a.filename).let {"${it.length()}:${it.lastModified()}"}}
        if(verified[pack.id]!=stamp){packs.verify(pack);verified[pack.id]=stamp}
        val model=pack.assets.single {it.role=="model"};val vocab=pack.assets.singleOrNull {it.role=="vocab"}
        fun file(role:String)=File(dir,pack.assets.single {it.role==role}.filename).path
        val source=vocab?.let {File(dir,it.filename).path} ?: file("srcvocab")
        val target=vocab?.let {File(dir,it.filename).path} ?: file("trgvocab")
        val prefix=File(root,"runtime/prefixes/nonbreaking_prefix.${pack.language.substringBefore('-')}")
        val yaml="models:\n - ${File(dir,model.filename).path}\nvocabs:\n - $source\n - $target\nshortlist:\n - ${file("lex")}\n - false\nbeam-size: 1\nalignment: soft\nnormalize: 1.0\nword-penalty: 0\nmax-length-break: 128\nmini-batch-words: 512\nworkspace: 64\ncpu-threads: 1\ngemm-precision: ${if(model.filename.contains("alphas"))"int8shiftAlphaAll"else "int8shiftAll"}\nssplit-mode: paragraph\nquiet: true\nquiet-translation: true\n"+(if(prefix.isFile)"ssplit-prefix-file: ${prefix.path}\n"else "")
        File(root,"runtime/${pack.id}-${pack.revision}.yml").also {if(!it.isFile||it.readText()!=yaml)it.writeText(yaml)}
    }
    inner class Session {
        private val acquired=mutableSetOf<String>()
        private var freedText=false
        fun beginPreparation(){freedText=false}
        suspend fun finishPreparation(){if(freedText)withContext(NonCancellable){release()}}
        val preparer=EnglishReadingPreparer(::detect,{language,html->translate(language,html)},{catalog.pack(it)!=null})
        private suspend fun translate(language:String,html:String):ReadingTranslation {try {
            if(!options.value.enabled)throw SourceLanguageUnavailable(language,"Translation is switched off")
            val pack=catalog.pack(language) ?: throw SourceLanguageUnavailable(language,"This language is not available offline")
            if(packs.installed(pack)==null) {
                val unmetered=!context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
                if(!options.value.automatic||!unmetered)throw SourceLanguageUnavailable(language,"Download ${label(language)} in Context settings, or allow automatic language downloads on Wi-Fi")
                if(language !in acquired&&acquired.size>=2)throw SourceLanguageUnavailable(language,"This reading has reached its automatic language-download allowance")
                acquired+=language
                install(language,false)
            }
            runtime()
            if(!freedText){repo.ai.releaseForMedia();freedText=true}
            val output=workerLane.withLock {val config=config(pack);withTimeout(45_000) {client.request(LanguageIpc.TRANSLATE,Bundle().apply {putString("path",config.path);putByteArray("text",html.toByteArray(Charsets.UTF_8))})}}
            packs.touch(pack)
            return ReadingTranslation(requireNotNull(output.getByteArray("result")).toString(Charsets.UTF_8),"bergamot-69455acaecbe:${pack.language}:${pack.revision}")
        }catch(cancelled:CancellationException){throw cancelled}catch(failure:SourceLanguageUnavailable){throw failure}catch(failure:Exception){throw SourceLanguageUnavailable(language,failure.message?.take(160) ?: "The offline translation is unavailable")}}
        suspend fun reading(store:ContextStore,owner:String,document:ContextDocument,field:String,source:String,hint:String="",reserve:(Long)->Unit={},checkWork:()->Unit={}):ContextReadingText {
            store.reading(owner,document,field,source)?.let {return it}
            val resumed=mutableSetOf<String>()
            fun key(language:String,html:String)=Repository.hash(EnglishReadingPreparer.POLICY+"|$language|${catalog.pack(language)?.revision}|$html")
            val preparing=EnglishReadingPreparer(::detect,{language,html->
                val id=key(language,html)
                store.readingChunk(owner,document,field,id)?.takeIf {it.recipe=="bergamot-69455acaecbe:$language:${catalog.pack(language)?.revision}"}?.also {resumed+=id} ?: translate(language,html)
            },{catalog.pack(it)!=null},checkWork,{language,html,result->
                val id=key(language,html)
                if(id !in resumed)withContext(NonCancellable){reserve(gson.toJson(result).toByteArray(Charsets.UTF_8).size.toLong());store.saveReadingChunk(owner,document,field,id,result)}
            })
            val reading=preparing.prepareBestEffort(source,hint)
            val bytes=if(reading.spans.none {it.translated})256L+reading.recipe.toByteArray().size else gson.toJson(reading).toByteArray().size.toLong()
            reserve(bytes);store.saveReading(owner,document,field,source,reading)
            return reading
        }
    }
    fun session()=Session()
}

class LanguageDownloadWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {return try {
        setForeground(getForegroundInfo());val repo=(applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        repo.languages.install(inputData.getString("language") ?: return Result.failure(),true);Result.success()
    }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){Result.failure()}}
    override suspend fun getForegroundInfo():ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("context-languages","Offline languages",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"context-languages").setSmallIcon(R.drawable.ic_antisocial).setContentTitle("Downloading an offline language")
            .setContentText("Open anti social for progress").addAction(android.R.drawable.ic_media_pause,"Pause",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(86,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else ForegroundInfo(86,notification)
    }
}
