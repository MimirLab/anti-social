/* AntiSocial, GPL-3.0-or-later. A local CPU service with no network client. */
package app.antisocial.android

import android.app.*
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal object LanguageNative {
    init {System.loadLibrary("antisocial_translation")}
    @JvmStatic external fun detect(path:ByteArray,text:ByteArray):ByteArray
    @JvmStatic external fun translate(path:ByteArray,text:ByteArray):ByteArray
    @JvmStatic external fun release()
}
internal object LanguageIpc {const val DETECT=1;const val TRANSLATE=2}

/** Service input is local verified data only. A native failure cannot crash
 * the reader or commit a successful source receipt. No Google services/root. */
class LanguageService:Service() {
    private val lane=Executors.newSingleThreadExecutor {Thread(it,"anti social language tools")}.asCoroutineDispatcher()
    private val scope=CoroutineScope(SupervisorJob()+lane)
    private val jobs=ConcurrentHashMap<String,Job>()
    private var idle:Job?=null
    private fun path(value:String):ByteArray {
        val file=File(value).canonicalFile;val root=File(filesDir,"languages").canonicalFile
        require(file.path.startsWith(root.path+File.separator)&&file.isFile){"The language tool is not installed private data"}
        return file.path.toByteArray(Charsets.UTF_8)
    }
    private fun admit(translation:Boolean) {
        val memory=ActivityManager.MemoryInfo().also(getSystemService(ActivityManager::class.java)::getMemoryInfo)
        check(!memory.lowMemory&&memory.availMem>(if(translation)256L else 32L)*1024*1024){"Language tools paused for available memory"}
        if(translation&&Build.VERSION.SDK_INT>=29)check(getSystemService(PowerManager::class.java).currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE){"Translation paused while the device cools"}
    }
    private val messenger=Messenger(Handler(Looper.getMainLooper()){message->
        if(message.sendingUid!=android.os.Process.myUid())return@Handler true
        val input=Bundle(message.data);val session=input.getString("session")?.takeIf {it.matches(Regex("[a-f0-9-]{36}"))} ?: return@Handler true
        val command=message.what;val id=message.arg1;val key="$session:$id";val reply=message.replyTo
        if(command==ContextMediaIpc.CANCEL){jobs[key]?.cancel();return@Handler true}
        if(command==ContextMediaIpc.RELEASE){jobs.values.forEach {it.cancel()};scope.launch {LanguageNative.release()};return@Handler true}
        idle?.cancel()
        if(jobs.isNotEmpty()){runCatching {reply?.send(Message.obtain(null,command,id,0).apply {data=Bundle().apply {putString("error","Language tools are busy; this source is kept for retry")}})};return@Handler true}
        val job=scope.launch(start=CoroutineStart.LAZY) {
            val result=Bundle()
            try {
                admit(command==LanguageIpc.TRANSLATE)
                val text=requireNotNull(input.getByteArray("text"));require(text.size<=32768)
                val file=path(requireNotNull(input.getString("path")))
                val bytes=when(command){LanguageIpc.DETECT->LanguageNative.detect(file,text);LanguageIpc.TRANSLATE->LanguageNative.translate(file,text);else->error("Unsupported language request")}
                ensureActive();check(bytes.size<=256*1024);result.putByteArray("result",bytes)
            }catch(cancelled:CancellationException){result.putString("error","Language reading cancelled; progress is retained")}
            catch(failure:Throwable){result.putString("error",failure.message?.take(240) ?: "This language tool could not complete")}
            finally {jobs.remove(key);result.putInt("workerPid",android.os.Process.myPid());runCatching {reply?.send(Message.obtain(null,command,id,0).apply {data=result})};idle=scope.launch {delay(30_000);LanguageNative.release()}}
        };jobs[key]=job
        job.invokeOnCompletion {if(jobs.remove(key,job))runCatching {reply?.send(Message.obtain(null,command,id,0).apply {data=Bundle().apply {putString("error","Language reading cancelled")}})}}
        job.start();true
    })
    override fun onBind(intent:Intent):IBinder=messenger.binder
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW){jobs.values.forEach {it.cancel()};scope.launch {LanguageNative.release()}}}
    override fun onDestroy(){jobs.values.forEach {it.cancel()};scope.launch(NonCancellable){runCatching {LanguageNative.release()};scope.cancel();lane.close()};super.onDestroy()}
}
