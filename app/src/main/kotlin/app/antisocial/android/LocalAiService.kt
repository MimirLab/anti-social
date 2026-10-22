/* AntiSocial, GPL-3.0-or-later. Bundled runtimes have no network or action tools. */
package app.antisocial.android

import android.app.Service
import android.content.*
import android.os.*
import app.antisocial.core.SseEmbedding
import app.antisocial.core.EmbeddingResult
import app.antisocial.core.ContextEngine
import app.antisocial.core.ContextInstructionCache
import app.antisocial.core.AiMemoryHeadroom
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object LocalTextNative {
    @Volatile private var loaded=false
    @Synchronized fun ensureLoaded(context: Context) {
        if(!loaded) {
            val library=LocalTextRuntime.installer(context).verifiedLibrary()
            check(android.system.Os.stat(library.path).st_mode and 0x92==0) {"Local engine must be read-only"}
            System.load(library.path)
            loaded=true
        }
    }
    @JvmStatic external fun capabilities(): String
    @JvmStatic external fun open(path: String,backend: String,context: Int,threads: Int,cacheProfile: String="f16_reference"): Long
    @JvmStatic external fun generate(handle: Long,system: String,user: String,maxTokens: Int,seed: Int,jsonMode: Boolean=false,instructionCacheBytes: Int=0): String
    @JvmStatic external fun countTokens(handle: Long,system: String,user: String): Int
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun diagnostics(handle: Long): String
}

internal object AiIpc {
    const val CAPABILITIES=1;const val TEXT=2;const val EMBED=3;const val CANCEL=4;const val RELEASE=5;const val PREPARE=6;const val COUNT=7
    const val MAX_TEXT_BYTES=48*1024
}
internal class AiWorkerStoppedException(message: String): java.io.IOException(message)

/** Separate process: a native/driver crash cannot kill the reader. There is one
 * bounded inference lane, no listening socket, no downloader and no Repository. */
class LocalAiService: Service() {
    private val lane=Executors.newSingleThreadExecutor {r->Thread(r,"anti social local AI")}.asCoroutineDispatcher()
    private val scope=CoroutineScope(SupervisorJob()+lane)
    private val jobs=ConcurrentHashMap<String,Job>()
    @Volatile private var text=0L
    private var textKey: Triple<String,String,String>?=null
    private var textCpuFallback=false
    private var instructionCacheBytes=0L
    @Volatile private var embedding: SseEmbedding?=null
    private var embeddingPath: String?=null
    private var idle: Job?=null
    @Volatile private var tooWarm=false
    private var thermal: android.os.PowerManager.OnThermalStatusChangedListener?=null
    override fun onCreate() {
        super.onCreate()
        if(Build.VERSION.SDK_INT>=29) {
            val power=getSystemService(android.os.PowerManager::class.java)
            tooWarm=power.currentThermalStatus>=android.os.PowerManager.THERMAL_STATUS_SEVERE
            thermal=android.os.PowerManager.OnThermalStatusChangedListener {status->
                tooWarm=status>=android.os.PowerManager.THERMAL_STATUS_SEVERE
                if(tooWarm){embedding?.cancel();val handle=text;if(handle!=0L)runCatching {LocalTextNative.cancel(handle)};jobs.values.forEach {it.cancel()}}
            }.also {power.addThermalStatusListener(mainExecutor,it)}
        }
    }
    private fun release() {
        if(text!=0L){LocalTextNative.close(text);text=0L;textKey=null;textCpuFallback=false}
        instructionCacheBytes=0
        embedding?.close();embedding=null;embeddingPath=null
    }
    private fun modelFile(path: String): File {
        val file=File(path).canonicalFile
        val root=File(filesDir,"local-ai").canonicalFile
        require(file.path.startsWith(root.path+File.separator)&&file.exists()){"The model is not an installed private pack."}
        return file
    }
    private fun prepare(data: Bundle) {
        check(!tooWarm){"This device is warm. Let it cool before running local AI."}
        val path=modelFile(requireNotNull(data.getString("path"))).path
        if(data.getString("kind")=="embedding") {
            if(embeddingPath!=path){embedding?.close();embedding=null;embeddingPath=null;embedding=SseEmbedding.open(File(path));embeddingPath=path}
        }else {
            LocalTextNative.ensureLoaded(this)
            val backend=data.getString("backend") ?: "auto"
            val cacheProfile=data.getString("cacheProfile") ?: "f16_reference"
            require(BuildConfig.DEBUG||cacheProfile=="f16_reference"){"This cache profile is still being qualified."}
            val key=Triple(path,backend,cacheProfile)
            if(textKey!=key) {
                if(text!=0L){LocalTextNative.close(text);text=0L;textKey=null}
                instructionCacheBytes=0
                val memory=getSystemService(android.app.ActivityManager::class.java)
                val info=android.app.ActivityManager.MemoryInfo().also(memory::getMemoryInfo)
                val allocationBudget=AiMemoryHeadroom.allocationBudget(info.totalMem,info.availMem,info.threshold,info.lowMemory)
                val estimatedPeak=AiMemoryHeadroom.plainTargetEstimate(File(path).length())
                require(estimatedPeak!=null&&estimatedPeak<allocationBudget){"Memory is low. Close another app or choose the smaller model."}
                textCpuFallback=false
                text=try {LocalTextNative.open(path,backend,2048,2,cacheProfile)}catch(e: IllegalStateException){
                    if(backend!="auto")throw e
                    // Native open frees its incomplete model before throwing.
                    // One CPU retry handles an unsupported GPU graph safely.
                    ensureNotStopping();textCpuFallback=true;LocalTextNative.open(path,"cpu",2048,2,cacheProfile)
                }
                textKey=key
            }
        }
    }
    private fun ensureNotStopping(){check(jobs.values.none {it.isCancelled}){"AI request cancelled."}}
    private val messenger=Messenger(Handler(Looper.getMainLooper()) {message->
        if(message.sendingUid!=android.os.Process.myUid())return@Handler true
        val id=message.arg1;val command=message.what;val input=Bundle(message.data);val recipient=message.replyTo
        val session=input.getString("session")?.takeIf {it.matches(Regex("[a-f0-9-]{36}"))} ?: return@Handler true
        val key="$session:$id"
        if(command==AiIpc.CANCEL){if(jobs[key]!=null){jobs[key]?.cancel();embedding?.cancel();val handle=text;if(handle!=0L)runCatching {LocalTextNative.cancel(handle)}};return@Handler true}
        if(command==AiIpc.RELEASE){
            val owned=jobs.filterKeys {it.startsWith("$session:")}.values
            if(jobs.isEmpty()||owned.isNotEmpty()){owned.forEach {it.cancel()};embedding?.cancel();val handle=text;if(handle!=0L)runCatching {LocalTextNative.cancel(handle)};scope.launch {release()}}
            return@Handler true
        }
        idle?.cancel()
        if(jobs.isNotEmpty()) {
            runCatching {recipient?.send(Message.obtain(null,command,id,0).apply {data=Bundle().apply {putString("error","Local AI is busy. Try again shortly.")}})}
            return@Handler true
        }
        val job=scope.launch(start=CoroutineStart.LAZY) {
            val result=Bundle()
            try {
                when(command) {
                    AiIpc.CAPABILITIES->{LocalTextNative.ensureLoaded(this@LocalAiService);result.putString("diagnostics",LocalTextNative.capabilities())}
                    AiIpc.PREPARE->{prepare(input);result.putString("diagnostics",if(text!=0L&&input.getString("kind")=="text")LocalTextNative.diagnostics(text)else "CPU · 512 dimensions · English")}
                    AiIpc.COUNT->{
                        val system=input.getString("system").orEmpty();val user=input.getString("user").orEmpty()
                        require((system+user).toByteArray().size<=AiIpc.MAX_TEXT_BYTES){"This planning request is too large."}
                        ensureActive();prepare(input);result.putInt("tokenCount",LocalTextNative.countTokens(text,system,user))
                    }
                    AiIpc.TEXT->{
                        val system=input.getString("system").orEmpty();val user=input.getString("user").orEmpty()
                        require((system+user).toByteArray().size<=AiIpc.MAX_TEXT_BYTES){"This request is too large."}
                        ensureActive();prepare(input);ensureActive()
                        val json=input.getBoolean("jsonMode",false)
                        val reusable=json&&input.getBoolean("instructionCache",false)&&ContextEngine.isReusableInstruction(system)
                        val memory=android.app.ActivityManager.MemoryInfo().also {getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)}
                        val available=AiMemoryHeadroom.allocationBudget(memory.totalMem,memory.availMem,memory.threshold,memory.lowMemory)
                        val budget=if(reusable&&available>0)ContextInstructionCache.budget(available,instructionCacheBytes,memory.lowMemory)else 0
                        LocalTextNative.reset(text)
                        try {
                            result.putString("text",LocalTextNative.generate(text,system,user,input.getInt("maxTokens",128).coerceIn(1,512),42,json,budget))
                        } finally {
                            val diagnostics=LocalTextNative.diagnostics(text)
                            instructionCacheBytes=org.json.JSONObject(diagnostics).optLong("instruction_cache_bytes").coerceIn(0L,ContextInstructionCache.MAX_BYTES.toLong())
                            result.putString("diagnostics",diagnostics)
                        }
                    }
                    AiIpc.EMBED->{
                        val value=input.getString("user").orEmpty();require(value.toByteArray().size<=AiIpc.MAX_TEXT_BYTES)
                        ensureActive();prepare(input);ensureActive()
                        when(val encoded=embedding!!.encode(value)) {
                            is EmbeddingResult.Empty->result.putBoolean("empty",true)
                            is EmbeddingResult.Vector->{result.putFloatArray("vector",encoded.values);result.putString("spaceId",encoded.spaceId);result.putInt("tokenCount",encoded.tokenCount)}
                        }
                    }
                    else->error("Unsupported local model request.")
                }
                result.putInt("workerPid",android.os.Process.myPid());result.putBoolean("cpuFallback",textCpuFallback);ensureActive()
            }catch(e: CancellationException){result.putString("error",if(tooWarm)"Local AI paused while the device cools."else "AI request cancelled.")}
            catch(e: Throwable){result.putString("error",e.message?.take(240) ?: "Local inference could not complete.")}
            finally {
                jobs.remove(key)
                runCatching {recipient?.send(Message.obtain(null,command,id,0).apply {data=result})}
                idle=scope.launch {delay(60_000);release()}
            }
        }
        jobs[key]=job
        // Cancellation can win before the lazy coroutine enters its finally.
        job.invokeOnCompletion {
            if(jobs.remove(key,job)) {
                runCatching {recipient?.send(Message.obtain(null,command,id,0).apply {data=Bundle().apply {putString("error","AI request cancelled.")}})}
                idle=scope.launch {delay(60_000);release()}
            }
        }
        job.start()
        true
    })
    override fun onBind(intent: Intent): IBinder=messenger.binder
    override fun onTrimMemory(level: Int) {super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW){embedding?.cancel();val handle=text;if(handle!=0L)runCatching {LocalTextNative.cancel(handle)};jobs.values.forEach {it.cancel()};scope.launch {release()}}}
    override fun onDestroy(){
        embedding?.cancel();val handle=text;if(handle!=0L)runCatching {LocalTextNative.cancel(handle)};jobs.values.forEach {it.cancel()}
        if(Build.VERSION.SDK_INT>=29)thermal?.let {getSystemService(android.os.PowerManager::class.java).removeThermalStatusListener(it)}
        // Release after blocking native work observes cancellation. Android may
        // bind a replacement service in this process immediately, so a routine
        // unbind must never kill the process underneath the replacement.
        scope.launch(NonCancellable) {release();scope.cancel();lane.close()}
        super.onDestroy()
    }
}

/** Lazy client: constructing settings never starts the worker or loads weights. */
internal class LocalAiClient(private val context: Context) {
    private var remote: Messenger?=null
    private var connection: ServiceConnection?=null
    private var connecting: CompletableDeferred<Unit>?=null
    private var nextId=0
    private val session=java.util.UUID.randomUUID().toString()
    private val mutex=Mutex()
    private val pending=ConcurrentHashMap<Int,CancellableContinuation<Bundle>>()
    private val replies=Messenger(Handler(Looper.getMainLooper()) {message->
        pending.remove(message.arg1)?.takeIf {it.isActive}?.let {continuation->
            val error=message.data.getString("error")
            if(error!=null)continuation.resumeWithException(IllegalStateException(error))else continuation.resume(Bundle(message.data))
        };true
    })
    private fun failed(message: String,workerStopped: Boolean=false){
        remote=null
        val error=if(workerStopped)AiWorkerStoppedException(message)else IllegalStateException(message)
        pending.values.forEach {if(it.isActive)it.resumeWithException(error)};pending.clear();connecting?.completeExceptionally(error);connecting=null
    }
    private suspend fun connect()=withContext(Dispatchers.Main.immediate) {
        if(remote!=null)return@withContext
        connecting?.let {it.await();return@withContext}
        val ready=CompletableDeferred<Unit>();connecting=ready
        val callback=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder){if(connection!==this)return;remote=Messenger(binder);ready.complete(Unit);connecting=null}
            override fun onServiceDisconnected(name: ComponentName){if(connection!==this)return;failed("The local model worker stopped. Retry to restart it.",workerStopped=true);connection?.let {runCatching {context.unbindService(it)}};connection=null}
            override fun onBindingDied(name: ComponentName){onServiceDisconnected(name)}
            override fun onNullBinding(name: ComponentName){onServiceDisconnected(name)}
        }
        connection=callback
        if(!context.bindService(Intent(context,LocalAiService::class.java),callback,Context.BIND_AUTO_CREATE)){connection=null;connecting=null;error("Local model worker is unavailable.")}
        try {withTimeout(15_000){ready.await()}}catch(e: Throwable){if(connection===callback){runCatching {context.unbindService(callback)};connection=null;remote=null;connecting=null};throw e}
    }
    suspend fun request(command: Int,input: Bundle=Bundle()): Bundle=mutex.withLock {
        connect()
        withContext(Dispatchers.Main.immediate) {
            val id=++nextId
            suspendCancellableCoroutine {continuation->
                pending[id]=continuation
                continuation.invokeOnCancellation {pending.remove(id);runCatching {remote?.send(Message.obtain(null,AiIpc.CANCEL,id,0).apply {data=Bundle().apply {putString("session",session)}})}}
                try {remote!!.send(Message.obtain(null,command,id,0).apply {data=Bundle(input).apply {putString("session",session)};replyTo=replies})}
                catch(e: Exception){pending.remove(id);continuation.resumeWithException(if(e is DeadObjectException)AiWorkerStoppedException("The local model worker stopped. Retry to restart it.")else e)}
            }
        }
    }
    suspend fun release()=withContext(Dispatchers.Main.immediate) {
        runCatching {remote?.send(Message.obtain(null,AiIpc.RELEASE).apply {data=Bundle().apply {putString("session",session)}})};failed("AI is paused.")
        connection?.let {runCatching {context.unbindService(it)}};connection=null
    }
    /** Removal is the one operation that must discard dlopen's mappings too.
     * Android permits stopping our own UID's dedicated worker without root. */
    suspend fun terminate() {
        release()
        val manager=context.getSystemService(android.app.ActivityManager::class.java)
        fun ownedWorkers()=manager.runningAppProcesses.orEmpty().filter {it.uid==android.os.Process.myUid()&&it.processName==context.packageName+":local_ai"}
        ownedWorkers().forEach {android.os.Process.killProcess(it.pid)}
        withTimeout(5_000){while(ownedWorkers().isNotEmpty())delay(25)}
    }
}
