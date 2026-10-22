/* AntiSocial, GPL-3.0-or-later. Inference is opt-in and never controls ordinary reading. */
package app.antisocial.android

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.cert.CertificateFactory
import java.util.concurrent.ConcurrentHashMap

data class AiModelState(val enabled: Boolean=false,val textInstalled: String?=null,val embeddingInstalled: String?=null,
    val textReady: Boolean=false,val embeddingReady: Boolean=false,val progress: AiInstallProgress?=null,
    val working: Boolean=false,val message: String="Optional · off",val textRoute: String="Not checked",val embeddingRoute: String="Not checked",
    val localBytes: Long=0,val engineBytes: Long=0,val engineInstalled: Boolean=false)

/** No feed subscriptions, boot-time downloads, remote fallback, or model work
 * originate here without an explicit enabled preference and a request. */
class AiModelManager(private val repo: Repository) {
    val preferences=AiPreferences(repo.context)
    internal val vault=AiCredentialVault(repo.context)
    val state=MutableStateFlow(AiModelState(enabled=preferences.enabled,message=if(preferences.enabled)"Choose or download your models"else "Optional · off"))
    private val client=LocalAiClient(repo.context)
    private val calls=ConcurrentHashMap.newKeySet<Job>()
    private val inferenceLane=Mutex()
    private val localMutation=Mutex()
    @Volatile private var changingLocal=false
    private val root=File(repo.context.filesDir,"local-ai")
    private val validation=repo.context.getSharedPreferences("ai-runtime-checks",Context.MODE_PRIVATE)
    val abi: String get()=Build.SUPPORTED_ABIS.first()
    val localSupported: Boolean get()=abi in setOf("arm64-v8a","x86_64")
    fun deviceRecommendation(): AiDeviceRecommendation {
        val info=ActivityManager.MemoryInfo().also(repo.context.getSystemService(ActivityManager::class.java)::getMemoryInfo)
        return AiDeviceRecommendations.recommend(AiDeviceFacts(abi,info.totalMem,info.availMem,repo.context.filesDir.usableSpace,
            repo.context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)))
    }
    val catalog: AiCatalog by lazy {
        val context=repo.context
        @Suppress("DEPRECATION") val signatures=if(Build.VERSION.SDK_INT>=28)context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners else context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNATURES).signatures!!
        val key=CertificateFactory.getInstance("X.509").generateCertificate(signatures.first().toByteArray().inputStream()).publicKey
        AiCatalog.verify(context.assets.open("ai/catalog.json").use {it.readBytes()},context.assets.open("ai/catalog.sig").use {it.readBytes()},key)
    }
    internal val installer: AiPackInstaller by lazy {AiPackInstaller(root,repo.gateway,catalog,BuildConfig.VERSION_CODE,abi)}
    internal val runtimeInstaller: NativeRuntimeInstaller by lazy {LocalTextRuntime.installer(repo.context,repo.gateway)}
    private fun kindName(kind: AiKind)=kind.name.lowercase()
    private fun checked(pack: AiInstalledPack?): Boolean=pack?.let {validation.getString(it.pack.id,null)==runtimeKey(it)} ?: false
    private fun recordReady(pack: AiInstalledPack) {validation.edit().putString(pack.pack.id,runtimeKey(pack)).commit()}
    private fun runtimeKey(pack: AiInstalledPack)="${pack.pack.id}|${pack.pack.revision}|${BuildConfig.VERSION_CODE}|${Build.FINGERPRINT}"
    private fun readableDiagnostics(value: String?): String {
        val json=runCatching {com.google.gson.JsonParser.parseString(value).asJsonObject}.getOrNull()
        val backend=json?.get("backend")?.asString ?: json?.get("selected_backend")?.asString ?: return "Local text model checked"
        return if(backend.contains("Vulkan",true))"Vulkan GPU · some operations may use the CPU"else "CPU · local text model checked"
    }
    fun selectedText(): AiModelPack=catalog.pack(when(preferences.textProfile){"compact"->"bonsai2-2b";"quality"->"bonsai2-9b";else->"bonsai2-4b"})!!
    suspend fun refresh()=withContext(Dispatchers.IO) {
        try {
            val text=installer.activePack(AiKind.TEXT);val embedding=installer.activePack(AiKind.EMBEDDING)
            state.value=state.value.copy(enabled=preferences.enabled,textInstalled=text?.pack?.label,embeddingInstalled=embedding?.pack?.label,
                textReady=checked(text)&&localSupported&&runtimeInstaller.installed(),embeddingReady=checked(embedding),
                engineInstalled=localSupported&&runtimeInstaller.installed(),engineBytes=if(localSupported)runtimeInstaller.localBytes()else 0,
                localBytes=AiKind.entries.sumOf {installer.localBytes(it)}+(if(localSupported)runtimeInstaller.localBytes()else 0))
        }catch(e: Exception){state.value=state.value.copy(message=e.message ?: "Could not read the model catalog.")}
    }
    fun enabled(value: Boolean) {
        if(value&&!preferences.hasTextProfile)deviceRecommendation().profile?.let {preferences.textProfile=it}
        preferences.enabled=value
        state.value=state.value.copy(enabled=value,message=if(value)"Local models first · remote only when you choose it"else if(state.value.localBytes>0)"AI paused · downloads kept"else "AI off",working=false)
        if(!value){calls.toList().forEach {it.cancel()};repo.contextReader.pauseAll();repo.mediaTools.cancel();repo.mediaTools.cancelDownload();repo.languages.cancel();WorkManager.getInstance(repo.context).cancelUniqueWork("ai-model-download");repo.scope.launch {client.release()}}
    }
    fun download(kind: AiKind?=null) {
        check(preferences.enabled){"Enable AI before downloading models."}
        check(localSupported){"Local models need a supported 64-bit device. You can connect a remote provider."}
        val kinds=kind?.let {listOf(it)} ?: listOf(AiKind.EMBEDDING,AiKind.TEXT)
        kinds.forEach {preferences.localChosen(kindName(it),true)}
        val request=OneTimeWorkRequestBuilder<AiModelDownloadWorker>().setInputData(workDataOf("kinds" to kinds.joinToString(","){it.name}))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        state.value=state.value.copy(working=true,message="Preparing download…")
        WorkManager.getInstance(repo.context).enqueueUniqueWork("ai-model-download",ExistingWorkPolicy.KEEP,request)
    }
    fun cancelDownload() {WorkManager.getInstance(repo.context).cancelUniqueWork("ai-model-download");state.value=state.value.copy(working=false,progress=null,message="Download paused · tap Download to resume")}
    internal suspend fun install(kinds: List<AiKind>)=tracked {
        try {
            state.value=state.value.copy(working=true,message="Downloading verified local models…")
            for(kind in kinds) {
                ensureEnabled()
                preferences.localChosen(kindName(kind),true)
                if(kind==AiKind.TEXT){validation.edit().remove("engine-removed").commit();ensureRuntime(verify=true)}
                val pack=if(kind==AiKind.TEXT)selectedText()else catalog.default(kind)
                val installed=installer.install(pack){progress->state.value=state.value.copy(progress=progress)}
                ensureEnabled()
                try {checkLocal(installed);recordReady(installed)}catch(e: Exception){if(e !is CancellationException)withContext(NonCancellable){client.release();installer.rejectActivation(kind,pack)};throw e}
                refresh()
            }
            state.value=state.value.copy(message="Local models ready · no account or API key needed")
        }catch(e: CancellationException){state.value=state.value.copy(message=if(preferences.enabled)"Download paused · tap Download to resume"else "AI paused · downloads kept");throw e}
        catch(e: Throwable){state.value=state.value.copy(message=publicError(e));throw e}
        finally {state.value=state.value.copy(progress=null,working=false);refresh()}
    }
    private suspend fun checkLocal(installed: AiInstalledPack) {
        ensureEnabled()
        val kind=installed.pack.kind
        val input=localInput(installed)
        state.value=state.value.copy(message="Checking ${if(kind==AiKind.TEXT)"text"else "embedding"} model on this device…")
        if(kind==AiKind.TEXT) {
            input.putString("system","Follow the user's instruction directly. Do not include reasoning.");input.putString("user","Reply with the single word ready.");input.putInt("maxTokens",12)
            val response=withTimeout(180_000){try {client.request(AiIpc.TEXT,input)}catch(e: AiWorkerStoppedException){recordWorkerFailure(installed);throw e}}
            check(response.getString("text").orEmpty().trim().trim('`','"','\'').trimEnd('.','!').equals("ready",true)){"The text model did not pass its setup check. Its verified download is kept for retry."}
            if(response.getBoolean("cpuFallback"))validation.edit().putBoolean("cpu:${runtimeKey(installed)}",true).commit()
            state.value=state.value.copy(textRoute=readableDiagnostics(response.getString("diagnostics")))
        }else {
            input.putString("user","A local private reader")
            val result=client.request(AiIpc.EMBED,input);val vector=result.getFloatArray("vector")
            check(vector?.size==512&&vector.all {it.isFinite()}){"The embedding model returned an invalid vector."}
            state.value=state.value.copy(embeddingRoute="CPU · English · 512 dimensions")
        }
    }
    suspend fun checkSetup(kind: AiKind?=null)=tracked {
        ensureEnabled();state.value=state.value.copy(working=true,message="Checking model setup…")
        try {
            for(item in kind?.let {listOf(it)} ?: AiKind.entries) {
                val local=installer.activePack(item)
                if(if(kind!=null)local!=null else useDevice(item,local)){checkLocal(requireNotNull(local));recordReady(local)}else {
                    val provider=remote(item);val diagnostic=provider.probe(item)
                    state.value=if(item==AiKind.TEXT)state.value.copy(textRoute=diagnostic)else state.value.copy(embeddingRoute=diagnostic)
                }
            }
            state.value=state.value.copy(message="Model setup checked")
        }catch(e: Throwable){state.value=state.value.copy(message=publicError(e));throw e}
        finally {state.value=state.value.copy(working=false);refresh()}
    }
    suspend fun remove(kind: AiKind?=null)=changeLocal {
        withContext(Dispatchers.IO) {for(item in kind?.let {listOf(it)} ?: AiKind.entries){installer.remove(item);preferences.localChosen(kindName(item),false);validation.edit().apply {catalog.packs.filter {it.kind==item}.forEach {remove(it.id)}}.commit()}}
        if(localSupported&&(kind==null||kind==AiKind.TEXT))runtimeInstaller.remove()
        state.value=state.value.copy(message="Local models removed · saved posts are protected")
    }
    suspend fun removeEngine()=changeLocal {
        validation.edit().putBoolean("engine-removed",true).commit()
        runtimeInstaller.remove()
        state.value=state.value.copy(message="Text engine removed · model downloads kept")
    }
    private suspend fun changeLocal(action: suspend ()->Unit)=localMutation.withLock {
        changingLocal=true
        try {
            cancelDownload();state.value=state.value.copy(working=true)
            calls.toList().forEach {it.cancel()};calls.toList().forEach {it.join()}
            inferenceLane.withLock {client.terminate();action()}
        }finally {withContext(NonCancellable){refresh();changingLocal=false;state.value=state.value.copy(working=false)}}
    }
    private suspend fun ensureRuntime(verify: Boolean=false) {
        ensureEnabled()
        check(!validation.getBoolean("engine-removed",false)) {"Install the local text engine in AI settings."}
        if(verify||!runtimeInstaller.installed()) {
            state.value=state.value.copy(message="Installing local text engine…")
            try {runtimeInstaller.install {state.value=state.value.copy(progress=it)}}finally {state.value=state.value.copy(progress=null);refresh()}
        }
    }
    suspend fun installEngine()=tracked {
        state.value=state.value.copy(working=true)
        try {ensureEnabled();validation.edit().remove("engine-removed").commit();ensureRuntime(verify=true);state.value=state.value.copy(message="Local text engine installed")}
        finally {state.value=state.value.copy(working=false);refresh()}
    }
    suspend fun saveProvider(kind: AiKind,value: AiProviderSettings,newKey: String?,removeKey: Boolean=false) = withContext(Dispatchers.IO) {
        require(value.location!=AiLocation.DEVICE)
        require(value.preset.supports(kind)){"Choose a provider with a documented embedding endpoint."}
        require(value.label.length<=80&&!value.label.any {it.isISOControl()}){"Use a shorter provider name."}
        if(value.preset!=AiProviderPreset.CUSTOM) {
            require(value.baseUrl.trimEnd('/')==value.preset.baseUrl&&value.protocol==value.preset.protocol&&value.location==AiLocation.CLOUD){"Use Other provider for a custom endpoint."}
        }
        val name=kindName(kind);val slot=aiCredentialSlot(name,value.id)
        AiRemoteConfig(value.baseUrl.trim(),value.model.trim(),"",value.location==AiLocation.SERVER,value.protocol,value.workspaceId.trim())
        val old=preferences.provider(name,value.id)
        // An explicit replacement/removal must also repair damaged ciphertext.
        val retainedCredential=if(newKey==null&&!removeKey)vault.read(slot)else null
        if(retainedCredential!=null&&(old==null||old.baseUrl.trimEnd('/')!=value.baseUrl.trim().trimEnd('/')||old.protocol!=value.protocol)) {
            require(newKey!=null||removeKey){"Re-enter or remove the API key when changing its endpoint or protocol."}
        }
        require(preferences.provider(name,value.id)!=null||preferences.providers(name).size<32){"You can save up to 32 providers per model type."}
        val credential=if(removeKey)null else newKey ?: retainedCredential
        require(!value.enabled||value.preset==AiProviderPreset.CUSTOM||!credential.isNullOrEmpty()){"Add your API key to enable this provider."}
        if(removeKey)vault.write(slot,null)else if(newKey!=null)vault.write(slot,newKey)
        preferences.provider(name,value.copy(baseUrl=value.baseUrl.trim(),model=value.model.trim(),label=value.label.trim(),workspaceId=value.workspaceId.trim(),hasKey=vault.read(slot)!=null))
        if(kind==AiKind.TEXT)repo.contextReader.invalidateCapabilities()
        state.value=state.value.copy(message="Provider saved · ${preferences.routing(name).label}")
    }
    suspend fun forgetProvider(kind: AiKind,id: String=preferences.provider(kindName(kind)).id)=withContext(Dispatchers.IO) {
        calls.toList().forEach {it.cancel()}
        vault.write(aiCredentialSlot(kindName(kind),id),null);preferences.removeProvider(kindName(kind),id)
        if(kind==AiKind.TEXT)repo.contextReader.invalidateCapabilities()
        state.value=state.value.copy(message="Provider and API key removed")
    }
    fun selectProvider(kind: AiKind,id: String) {
        calls.toList().forEach {it.cancel()};preferences.selectProvider(kindName(kind),id)
        if(kind==AiKind.TEXT)repo.contextReader.invalidateCapabilities()
        state.value=state.value.copy(message="Provider selected · ${preferences.routing(kindName(kind)).label}")
    }
    fun routing(kind: AiKind,value: AiRouting) {
        calls.toList().forEach {it.cancel()};preferences.routing(kindName(kind),value)
        if(kind==AiKind.TEXT)repo.contextReader.invalidateCapabilities()
        repo.scope.launch {client.release()}
        state.value=state.value.copy(message="${kindName(kind).replaceFirstChar {it.uppercase()}} · ${value.label}")
    }
    private fun useDevice(kind: AiKind,installed: AiInstalledPack?): Boolean=AiModelRouting.useDevice(preferences.routing(kindName(kind)),installed!=null,preferences.localChosen(kindName(kind)))
    private fun remote(kind: AiKind,id: String?=null): AiRemoteProvider {
        ensureEnabled()
        val name=kindName(kind)
        val value=if(id==null)preferences.provider(name)else preferences.provider(name,id) ?: error("Choose your provider in AI settings.")
        check(value.enabled){"Download a local $name model or select an enabled provider in AI settings."}
        check(value.preset.supports(kind)){"Choose a separate embedding provider."}
        return AiRemoteProvider(repo.gateway,AiRemoteConfig(value.baseUrl,value.model,vault.read(aiCredentialSlot(name,value.id)).orEmpty(),value.location==AiLocation.SERVER,value.protocol,value.workspaceId,value.preset))
    }
    suspend fun testProvider(kind: AiKind,id: String?=null): String=tracked {ensureEnabled();remote(kind,id).probe(kind)}
    private suspend fun localInput(pack: AiInstalledPack): Bundle {
        if(pack.pack.kind==AiKind.TEXT)ensureRuntime()
        return Bundle().apply {
        putString("kind",kindName(pack.pack.kind));putString("path",if(pack.pack.kind==AiKind.TEXT)File(pack.directory,pack.pack.assets.single().filename).path else pack.directory.path)
        if(pack.pack.kind==AiKind.TEXT) {
            check(!validation.getBoolean("stopped-cpu:${runtimeKey(pack)}",false)){"The local runtime stopped on this device using both routes. Use your provider until a compatible app update is available."}
            putString("backend",if(validation.getBoolean("cpu:${runtimeKey(pack)}",false))"cpu"else "auto")
        }
        }
    }
    private fun recordWorkerFailure(pack: AiInstalledPack) {
        val key=runtimeKey(pack)
        if(validation.getBoolean("cpu:$key",false))validation.edit().putBoolean("stopped-cpu:$key",true).remove(pack.pack.id).commit()
        else validation.edit().putBoolean("cpu:$key",true).remove(pack.pack.id).commit()
    }
    /** The only future feature entry points. Failure never silently switches a
     * running local request to a paid service or sends source material twice. */
    suspend fun text(system: String,user: String,maxTokens: Int=256): String=tracked {
        ensureEnabled();require(maxTokens in 1..512)
        val installed=installer.activePack(AiKind.TEXT)
        if(useDevice(AiKind.TEXT,installed)){val input=localInput(requireNotNull(installed)).apply {putString("system",system);putString("user",user);putInt("maxTokens",maxTokens)};try {client.request(AiIpc.TEXT,input).getString("text") ?: error("The model returned no text.")}catch(e: AiWorkerStoppedException){recordWorkerFailure(installed);throw e}}
        else remote(AiKind.TEXT).text(system,user,maxTokens)
    }
    /** Planning is exact on-device. Unknown remote tokenizers use a conservative
     * UTF-8 byte bound and a small context; no paid capability probes are sent. */
    suspend fun contextTokenCount(system: String,user: String,expectedIdentity: String?=null): Int=tracked {
        ensureEnabled();check(expectedIdentity==null||contextIdentity()==expectedIdentity){"The selected model changed. Resume Context with your new choice."};val installed=installer.activePack(AiKind.TEXT)
        if(useDevice(AiKind.TEXT,installed))try {client.request(AiIpc.COUNT,localInput(requireNotNull(installed)).apply {putString("system",system);putString("user",user)}).getInt("tokenCount")}catch(e: AiWorkerStoppedException){recordWorkerFailure(requireNotNull(installed));throw e}
        else (system.toByteArray().size+user.toByteArray().size+128)
    }
    fun contextUsesDevice(): Boolean=useDevice(AiKind.TEXT,installer.activePack(AiKind.TEXT))
    fun contextLocalEmbeddingAvailable(): Boolean=preferences.enabled&&state.value.embeddingReady&&state.value.embeddingInstalled!=null&&preferences.routing("embedding")!=AiRouting.PROVIDER_ONLY
    fun contextEmbeddingIdentity(): String=installer.activePack(AiKind.EMBEDDING)?.let(::runtimeKey).orEmpty()
    suspend fun contextCapabilities(expectedIdentity: String?=null): ContextModelCapabilities=tracked {
        ensureEnabled()
        check(expectedIdentity==null||contextIdentity()==expectedIdentity){"The selected model changed. Resume Context with your new choice."}
        val installed=installer.activePack(AiKind.TEXT)
        if(useDevice(AiKind.TEXT,installed)) {
            val pack=requireNotNull(installed)
            try {
                val diagnostics=client.request(AiIpc.PREPARE,localInput(pack)).getString("diagnostics")
                    ?: error("The local runtime did not report its reading capacity.")
                ContextCapabilityParser.nativeRuntime(com.google.gson.JsonParser.parseString(diagnostics).asJsonObject,pack.pack.label)
            }catch(e: AiWorkerStoppedException){recordWorkerFailure(pack);throw e}
        }else remote(AiKind.TEXT).contextCapabilities()
    }
    suspend fun contextText(system: String,user: String,maxTokens: Int,expectedIdentity: String?=null,vllmUtility: Boolean=false): String=tracked {
        ensureEnabled();check(expectedIdentity==null||contextIdentity()==expectedIdentity){"The selected model changed. Resume Context with your new choice."};val installed=installer.activePack(AiKind.TEXT)
        if(useDevice(AiKind.TEXT,installed)) {
            require(maxTokens in 1..512)
            try {client.request(AiIpc.TEXT,localInput(requireNotNull(installed)).apply {putString("system",system);putString("user",user);putInt("maxTokens",maxTokens);putBoolean("jsonMode",true);putBoolean("instructionCache",app.antisocial.core.ContextEngine.isReusableInstruction(system))}).getString("text") ?: error("The model returned no text")}catch(e: AiWorkerStoppedException){recordWorkerFailure(requireNotNull(installed));throw e}
        }else {require(maxTokens in 1..8192);remote(AiKind.TEXT).text(system,user,maxTokens,vllmUtility)}
    }
    fun contextAvailable(): Boolean {
        if(!preferences.enabled)return false
        return runCatching {
            val local=state.value.textInstalled!=null
            if(AiModelRouting.useDevice(preferences.routing("text"),local,preferences.localChosen("text")))state.value.textReady
            else preferences.provider("text").let {it.enabled&&it.baseUrl.isNotBlank()&&it.model.isNotBlank()}
        }.getOrDefault(false)
    }
    fun contextIdentity(): String {
        val installed=installer.activePack(AiKind.TEXT)
        return if(useDevice(AiKind.TEXT,installed))installed?.let(::runtimeKey) ?: "device-unavailable"
        else preferences.provider("text").let {Repository.hash("${it.id}|${it.baseUrl}|${it.model}|${it.protocol}")}
    }
    suspend fun releaseForMedia()=client.release()
    /** Context candidate hints are strictly local, even if provider settings
     * change between two lookups or while waiting for the inference lane. */
    suspend fun contextLocalEmbed(text: String,expectedIdentity: String): EmbeddingResult=tracked {
        ensureEnabled()
        check(contextLocalEmbeddingAvailable()&&contextEmbeddingIdentity()==expectedIdentity){"The local embedding model changed"}
        if(text.isBlank())return@tracked EmbeddingResult.Empty
        val installed=requireNotNull(installer.activePack(AiKind.EMBEDDING))
        val result=client.request(AiIpc.EMBED,localInput(installed).apply {putString("user",text)})
        if(result.getBoolean("empty"))EmbeddingResult.Empty else EmbeddingResult.Vector(requireNotNull(result.getFloatArray("vector")),requireNotNull(result.getString("spaceId")),result.getInt("tokenCount"))
    }
    suspend fun embed(text: String): EmbeddingResult=tracked {
        ensureEnabled();if(text.isBlank())return@tracked EmbeddingResult.Empty
        val installed=installer.activePack(AiKind.EMBEDDING)
        if(useDevice(AiKind.EMBEDDING,installed)){
            val result=client.request(AiIpc.EMBED,localInput(requireNotNull(installed)).apply {putString("user",text)})
            if(result.getBoolean("empty"))EmbeddingResult.Empty else EmbeddingResult.Vector(requireNotNull(result.getFloatArray("vector")),requireNotNull(result.getString("spaceId")),result.getInt("tokenCount"))
        }else remote(AiKind.EMBEDDING).embeddings(listOf(text)).let {EmbeddingResult.Vector(it.vectors.single(),it.spaceId,-1)}
    }
    private fun ensureEnabled(){check(preferences.enabled){"AI is off. Ordinary reading remains available."};check(!changingLocal){"AI is paused while local storage is updated."}}
    private suspend fun <T> tracked(action: suspend ()->T): T=coroutineScope {
        val job=currentCoroutineContext()[Job]!!;calls.add(job)
        try {inferenceLane.withLock {action()}}finally {calls.remove(job)}
    }
    private fun publicError(error: Throwable)=when(error){is CancellationException->"AI request paused";else->error.message?.take(240) ?: "Could not complete model setup. Try again."}
}

/** Android owns persistence/retry of the download job. It never owns a permanent
 * inference loop. Partial files survive worker/process interruption. */
class AiModelDownloadWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as AntiSocialApplication).repo
        if(!repo.ai.preferences.enabled)return Result.success()
        return try {
            setForeground(getForegroundInfo())
            repo.awaitReady()
            repo.ai.install(inputData.getString("kinds").orEmpty().split(',').map(AiKind::valueOf))
            Result.success()
        }catch(e: CancellationException){throw e}catch(_: Exception){Result.failure()}
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ai-models","Local model downloads",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(applicationContext,"ai-models").setSmallIcon(R.drawable.ic_antisocial)
            .setContentTitle("Downloading optional local models").setContentText("Open anti social to see progress or pause the download").setContentIntent(pending)
            .addAction(android.R.drawable.ic_media_pause,"Pause",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(84,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else ForegroundInfo(84,notification)
    }
}
