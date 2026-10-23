/* AntiSocial, GPL-3.0-or-later. Local media decoder and extraction worker. */
package app.antisocial.android

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.*
import android.os.*
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

internal object ContextMediaNative {
    init {System.loadLibrary("antisocial_context_media")}
    @JvmStatic external fun start(id:String)
    @JvmStatic external fun cancel(id:String)
    @JvmStatic external fun finish(id:String)
    @JvmStatic external fun prepareOcr(path:String,language:String)
    @JvmStatic external fun prepareSpeech(model:String,vad:String,threads:Int)
    @JvmStatic external fun ocr(path:String,language:String,bitmap:Bitmap,id:String):ByteArray
    @JvmStatic external fun transcribe(pcmPath:String,id:String):ByteArray
    @JvmStatic external fun release()
}
internal object ContextMediaIpc {const val PREPARE=1;const val OCR=2;const val SPEECH=3;const val PDF=4;const val CANCEL=5;const val RELEASE=6;const val PDF_TEXT=7;const val OCR_PIXELS=8}

/** No Repository, networking client, listening socket, or remote fallback lives
 * in this process. The app passes read-only local descriptors and verified data. */
class ContextMediaService:Service() {
    private val lane=Executors.newSingleThreadExecutor {Thread(it,"anti social media tools")}.asCoroutineDispatcher()
    private val scope=CoroutineScope(SupervisorJob()+lane)
    private val jobs=ConcurrentHashMap<String,Job>()
    private val gson=Gson()
    private var idle:Job?=null
    @Volatile private var tooWarm=false
    private var thermal:PowerManager.OnThermalStatusChangedListener?=null
    override fun onCreate() {
        super.onCreate()
        // A previous worker may have died before its PDF finally block.
        // No request is active when this process/service is being created.
        File(cacheDir,"menu-pdf-scratch").listFiles()?.forEach {it.delete()}
        if(Build.VERSION.SDK_INT>=29) {
            val power=getSystemService(PowerManager::class.java)
            tooWarm=power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE
            thermal=PowerManager.OnThermalStatusChangedListener {status->tooWarm=status>=PowerManager.THERMAL_STATUS_SEVERE;if(tooWarm)cancelAll()}.also {power.addThermalStatusListener(mainExecutor,it)}
        }
    }
    private fun cancelAll(){jobs.forEach {(id,job)->runCatching {ContextMediaNative.cancel(id)};job.cancel()}}
    private fun admission(speech:Boolean) {
        check(!tooWarm){"Media tools paused while the device cools."}
        val info=ActivityManager.MemoryInfo().also(getSystemService(ActivityManager::class.java)::getMemoryInfo)
        check(!info.lowMemory&&info.availMem>(if(speech)512L else 192L)*1024*1024){"Memory is low. Close another app before using media tools."}
    }
    private fun modelPath(value:String):String {
        val file=File(value).canonicalFile;val root=File(filesDir,"context-media/packs").canonicalFile
        require(file.path.startsWith(root.path+File.separator)&&file.exists()){"The media tool is not an installed private pack."}
        return file.path
    }
    private fun prepare(input:Bundle) {
        val speech=input.getString("kind")==ContextMediaKind.SPEECH.name;admission(speech)
        if(speech)ContextMediaNative.prepareSpeech(modelPath(requireNotNull(input.getString("model"))),modelPath(requireNotNull(input.getString("vad"))),2)
        else ContextMediaNative.prepareOcr(modelPath(requireNotNull(input.getString("path"))),input.getString("language") ?: "eng")
    }
    private fun bitmap(fd:FileDescriptor):Pair<Bitmap,Boolean> {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFileDescriptor(fd,null,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<500_000_000){"This image cannot be decoded safely."}
        var sample=1;while(bounds.outWidth.toLong()*bounds.outHeight/(sample.toLong()*sample)>4_000_000)sample*=2
        val decoded=BitmapFactory.decodeFileDescriptor(fd,null,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}) ?: error("This image format is unavailable on this device.")
        val orientation=runCatching {ExifInterface(fd).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform=Matrix().apply {when(orientation){
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL->setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180->setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL->setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE->{setRotate(90f);postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_90->setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE->{setRotate(270f);postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_270->setRotate(270f)
        }}
        val rotated=if(transform.isIdentity)decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,transform,true).also {decoded.recycle()}
        val white=Bitmap.createBitmap(rotated.width,rotated.height,Bitmap.Config.ARGB_8888)
        Canvas(white).apply {drawColor(Color.WHITE);drawBitmap(rotated,0f,0f,null)};rotated.recycle()
        return white to (sample>1)
    }
    private fun readBitmap(input:Bundle,image:Bitmap,key:String,scaled:Boolean=false):OcrResult {
        val bytes=ContextMediaNative.ocr(modelPath(requireNotNull(input.getString("path"))),input.getString("language") ?: "eng",image,key)
        return ContextMediaWire.ocr(bytes,scaled)
    }
    private fun pdf(input:Bundle,fd:FileDescriptor,key:String):ContextPdfPageResult {
        PdfRenderer(ParcelFileDescriptor.dup(fd)).use {renderer->
            val pageIndex=input.getInt("pageIndex");require(pageIndex in 0 until renderer.pageCount){"This PDF page is unavailable."}
            renderer.openPage(pageIndex).use {page->
                if(Build.VERSION.SDK_INT>=35) {
                    val embedded=page.textContents.joinToString("\n"){it.text}.trim()
                    if(embedded.isNotBlank())return ContextPdfPageResult(pageIndex,renderer.pageCount,embedded.take(80_000),false,false,truncated=embedded.length>80_000)
                }
                if(!input.getBoolean("allowOcr",true))return ContextPdfPageResult(pageIndex,renderer.pageCount,"",false,true,available=false,reason="This scanned page is waiting for permission to use local image text extraction.")
                if(!input.getBoolean("ocrEnabled"))return ContextPdfPageResult(pageIndex,renderer.pageCount,"",false,true,available=false,reason="This page needs the optional image text tool. Enable it in Context settings.")
                admission(false)
                val scale=min(2.5,sqrt(4_000_000.0/(page.width.toDouble()*page.height)))
                val image=Bitmap.createBitmap(max(1,(page.width*scale).roundToInt()),max(1,(page.height*scale).roundToInt()),Bitmap.Config.ARGB_8888)
                try {Canvas(image).drawColor(Color.WHITE);page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val result=readBitmap(input,image,key,true)
                    return ContextPdfPageResult(pageIndex,renderer.pageCount,result.text,true,result.uncertain,truncated=result.truncated)
                }finally {image.recycle()}
            }
        }
    }
    private class TextLimit:IOException()
    private fun menuPdfText(input:Bundle,fd:FileDescriptor,key:String):ContextPdfPageResult {
        admission(false)
        val index=input.getInt("pageIndex");require(index in 0..5)
        val bytes=ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd)).use {stream->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){jobs[key]?.ensureActive();val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=4*1024*1024){"Menu document is too large"};out.write(buffer,0,n)};out.toByteArray()
        }
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        val scratch=File(cacheDir,"menu-pdf-scratch").apply {mkdirs()}
        val memory=com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(2L*1024*1024,8L*1024*1024).setTempDir(scratch)
        try {com.tom_roush.pdfbox.pdmodel.PDDocument.load(ByteArrayInputStream(bytes),memory).use {document->
            require(!document.isEncrypted){"Encrypted documents are not menu sources"}
            require(document.numberOfPages in 1..256&&index<document.numberOfPages){"Menu page is unavailable"}
            val text=StringBuilder();var truncated=false
            val writer=object:Writer() {
                override fun write(buffer:CharArray,offset:Int,length:Int) {jobs[key]?.ensureActive();val keep=minOf(length,(LocalMenus.MAX_TEXT-text.length).coerceAtLeast(0));text.append(buffer,offset,keep);if(keep<length)throw TextLimit()}
                override fun flush(){};override fun close(){}
            }
            try {com.tom_roush.pdfbox.text.PDFTextStripper().apply {startPage=index+1;endPage=index+1;sortByPosition=true}.writeText(document,writer)}catch(_:TextLimit){truncated=true}
            return ContextPdfPageResult(index,document.numberOfPages,text.toString().trim(),false,false,truncated=truncated)
        }}finally {scratch.listFiles()?.forEach {it.delete()}}
    }
    private data class Decoded(val file:File,val start:Long,val end:Long,val sourceDuration:Long,val complete:Boolean)
    /** Decode one seekable slice. Resample decoded PCM, never compressed bytes.
     * Output is bounded, 16k mono f32; timestamps remain tied to the source. */
    private fun decode(fd:FileDescriptor,input:Bundle,key:String):Decoded {
        val start=input.getLong("startMs");val duration=input.getLong("durationMs",120_000)
        require(start>=0&&duration in 1..1_200_000&&start<Long.MAX_VALUE-duration)
        val stop=start+duration;val extractor=MediaExtractor();var codec:MediaCodec?=null
        val file=File(cacheDir,"context-pcm-${key.replace(":","-")}.f32")
        try {
            extractor.setDataSource(fd)
            val track=(0 until extractor.trackCount).firstOrNull {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true} ?: error("No supported audio track was found.")
            extractor.selectTrack(track);val format=extractor.getTrackFormat(track)
            val sourceDuration=if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION)/1000 else -1L
            if(sourceDuration>=0)require(start<sourceDuration){"This audio slice is past the end."}
            extractor.seekTo(start*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder=MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)));codec=decoder
            decoder.configure(format,null,null,0);decoder.start()
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);var encoding=AudioFormat.ENCODING_PCM_16BIT
            var inputEnded=false;var sourceEnded=false;var ended=false;val info=MediaCodec.BufferInfo();var count=0L;var lastFrame=Float.NaN;var lastTime=0.0
            var nextSample=start*16L;var lastProgress=SystemClock.elapsedRealtime()
            BufferedOutputStream(FileOutputStream(file),64*1024).use {output->
                val out=ByteBuffer.allocate(64*1024).order(ByteOrder.LITTLE_ENDIAN)
                fun emit(value:Float){if(out.remaining()<4){output.write(out.array(),0,out.position());out.clear()};out.putFloat(value.coerceIn(-1f,1f));count++}
                while(!ended) {
                    check(jobs[key]?.isActive==true&&!tooWarm){"Media request cancelled."}
                    check(SystemClock.elapsedRealtime()-lastProgress<30_000){"The device audio decoder stopped responding."}
                    if(!inputEnded) {
                        val index=decoder.dequeueInputBuffer(10_000)
                        if(index>=0) {
                            val target=decoder.getInputBuffer(index)!!;val size=extractor.readSampleData(target,0);val pts=extractor.sampleTime
                            if(size<0||pts>=stop*1000){sourceEnded=size<0;decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                            else {decoder.queueInputBuffer(index,0,size,pts,0);extractor.advance()};lastProgress=SystemClock.elapsedRealtime()
                        }
                    }
                    val index=decoder.dequeueOutputBuffer(info,10_000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val changed=decoder.outputFormat;rate=changed.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding=if(changed.containsKey(MediaFormat.KEY_PCM_ENCODING))changed.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(rate in 8000..192000&&channels in 1..8&&encoding in setOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT)){"This decoded audio format is unavailable."}
                    }else if(index>=0) {
                        val data=decoder.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN);data.position(info.offset);data.limit(info.offset+info.size)
                        val frameBytes=channels*(if(encoding==AudioFormat.ENCODING_PCM_FLOAT)4 else 2);val frames=info.size/frameBytes
                        for(frame in 0 until frames) {
                            var value=0f;repeat(channels){value+=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)data.float else data.short/32768f};value/=channels
                            if(!value.isFinite())value=0f
                            val time=info.presentationTimeUs/1000.0+frame*1000.0/rate
                            while(nextSample/16.0<=time&&nextSample/16.0<stop) {
                                val target=nextSample/16.0
                                if(target>=start)emit(if(lastFrame.isFinite()&&time>lastTime)lastFrame+(value-lastFrame)*((target-lastTime)/(time-lastTime)).coerceIn(0.0,1.0).toFloat() else value)
                                nextSample++
                            };lastFrame=value;lastTime=time
                        }
                        ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0||nextSample/16L>=stop
                        decoder.releaseOutputBuffer(index,false);lastProgress=SystemClock.elapsedRealtime()
                    }
                }
                if(out.position()>0)output.write(out.array(),0,out.position())
            }
            check(count>0){"This audio slice contains no decodable samples."}
            val end=start+count*1000/16000
            return Decoded(file,start,end,sourceDuration,sourceEnded||(sourceDuration>=0&&end>=sourceDuration-100))
        }catch(e:Throwable){file.delete();throw e}finally {runCatching {codec?.stop()};runCatching {codec?.release()};extractor.release()}
    }
    @Suppress("DEPRECATION") private fun descriptor(input:Bundle)=input.getParcelable<ParcelFileDescriptor>("file") ?: error("The local media file is unavailable.")
    private val messenger=Messenger(Handler(Looper.getMainLooper()){message->
        if(message.sendingUid!=android.os.Process.myUid())return@Handler true
        val input=Bundle(message.data);input.classLoader=ParcelFileDescriptor::class.java.classLoader
        val session=input.getString("session")?.takeIf {it.matches(Regex("[a-f0-9-]{36}"))} ?: return@Handler true
        val command=message.what;val id=message.arg1;val key="$session:$id";val reply=message.replyTo
        if(command==ContextMediaIpc.CANCEL){jobs[key]?.cancel();runCatching {ContextMediaNative.cancel(key)};return@Handler true}
        if(command==ContextMediaIpc.RELEASE){cancelAll();scope.launch {ContextMediaNative.release()};return@Handler true}
        idle?.cancel()
        if(jobs.isNotEmpty()) {runCatching {descriptor(input).close()};runCatching {reply?.send(Message.obtain(null,command,id,0).apply {data=Bundle().apply {putString("error","Media tools are busy. Try again shortly.")}})};return@Handler true}
        val job=scope.launch(start=CoroutineStart.LAZY) {
            val result=Bundle();var started=false
            try {
                if(command!=ContextMediaIpc.PDF)admission(command==ContextMediaIpc.SPEECH)
                if(command!=ContextMediaIpc.PDF_TEXT){ContextMediaNative.start(key);started=true}
                when(command) {
                    ContextMediaIpc.PREPARE->{
                        prepare(input)
                        if(input.getString("kind")==ContextMediaKind.SPEECH.name) {
                            val silence=File(cacheDir,"context-probe-${key.replace(":","-")}.f32")
                            try {silence.writeBytes(ByteArray(16_000*4*2));val probe=ContextMediaWire.transcript(ContextMediaNative.transcribe(silence.path,key));check(!probe.hasSpeech&&probe.segments.isEmpty()){"The speech model did not pass its quiet-audio check."}}
                            finally {silence.delete()}
                        }else {
                            val image=Bitmap.createBitmap(640,240,Bitmap.Config.ARGB_8888)
                            try {Canvas(image).drawColor(Color.WHITE);check(readBitmap(input,image,key).text.isBlank()){"The image text model did not pass its blank-page check."}}
                            finally {image.recycle()}
                        }
                    }
                    ContextMediaIpc.OCR_PIXELS->{
                        @Suppress("DEPRECATION") val image=input.getParcelable<Bitmap>("bitmap") ?: error("Decoded pixels unavailable")
                        try {require(image.width>0&&image.height>0&&image.width.toLong()*image.height<=ContentWire.MAX_PIXELS);prepare(input);ensureActive();result.putByteArray("result",gson.toJson(readBitmap(input,image,key,input.getBoolean("scaled"))).toByteArray())}finally {image.recycle()}
                    }
                    ContextMediaIpc.OCR->descriptor(input).use {fd->check(BuildConfig.DEBUG){"Compressed input requires the isolated decoder"};prepare(input);val(image,scaled)=bitmap(fd.fileDescriptor);try {ensureActive();result.putByteArray("result",gson.toJson(readBitmap(input,image,key,scaled)).toByteArray())}finally {image.recycle()}}
                    ContextMediaIpc.PDF->descriptor(input).use {fd->check(BuildConfig.DEBUG){"PDF input requires the isolated decoder"};result.putByteArray("result",gson.toJson(pdf(input,fd.fileDescriptor,key)).toByteArray())}
                    ContextMediaIpc.PDF_TEXT->descriptor(input).use {fd->check(BuildConfig.DEBUG){"PDF input requires the isolated decoder"};result.putByteArray("result",gson.toJson(menuPdfText(input,fd.fileDescriptor,key)).toByteArray())}
                    ContextMediaIpc.SPEECH->descriptor(input).use {fd->
                        prepare(input);val decoded=decode(fd.fileDescriptor,input,key)
                        try {ensureActive();val raw=ContextMediaWire.transcript(ContextMediaNative.transcribe(decoded.file.path,key))
                            val segments=raw.segments.map {it.copy(startMs=(it.startMs+decoded.start).coerceAtMost(decoded.end),endMs=(it.endMs+decoded.start).coerceIn(decoded.start,decoded.end))}
                            val adjusted=raw.copy(segments=segments,startOffsetMs=decoded.start,nextOffsetMs=decoded.end,complete=decoded.complete,sourceDurationMs=decoded.sourceDuration)
                            result.putByteArray("result",gson.toJson(adjusted).toByteArray())
                        }finally {decoded.file.delete()}
                    }
                    else->error("Unsupported media request.")
                }
                check((result.getByteArray("result")?.size ?: 0)<=256*1024){"This media result exceeds the local processing plan."};ensureActive()
            }catch(e:CancellationException){result.putString("error",if(tooWarm)"Media tools paused while the device cools."else "Media request cancelled.")}
            catch(e:Throwable){result.putString("error",e.message?.take(240) ?: "The local media tool could not complete.")}
            finally {if(started)runCatching {ContextMediaNative.finish(key)};runCatching {descriptor(input).close()};jobs.remove(key);result.putInt("workerPid",android.os.Process.myPid());runCatching {reply?.send(Message.obtain(null,command,id,0).apply {data=result})};idle=scope.launch {delay(30_000);ContextMediaNative.release()}}
        };jobs[key]=job
        // A cancelled LAZY coroutine may never enter its body/finally. Own the
        // queued descriptor here too, and remove only this exact request.
        job.invokeOnCompletion {
            if(jobs.remove(key,job)) {
                runCatching {descriptor(input).close()}
                runCatching {reply?.send(Message.obtain(null,command,id,0).apply {data=Bundle().apply {putString("error","Media request cancelled.")}})}
                idle=scope.launch {delay(30_000);ContextMediaNative.release()}
            }
        }
        job.start();true
    })
    override fun onBind(intent:Intent):IBinder=messenger.binder
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW){cancelAll();scope.launch {ContextMediaNative.release()}}}
    override fun onDestroy(){cancelAll();if(Build.VERSION.SDK_INT>=29)thermal?.let {getSystemService(PowerManager::class.java).removeThermalStatusListener(it)};scope.launch(NonCancellable){runCatching {ContextMediaNative.release()};scope.cancel();lane.close()};super.onDestroy()}
}

internal class ContextMediaClient(private val context:Context,private val service:Class<out Service> = ContextMediaService::class.java) {
    private var remote:Messenger?=null;private var connection:ServiceConnection?=null;private var connecting:CompletableDeferred<Unit>?=null
    private var nextId=0;private val session=java.util.UUID.randomUUID().toString();private val mutex=Mutex()
    private val pending=ConcurrentHashMap<Int,CancellableContinuation<Bundle>>()
    private val replies=Messenger(Handler(Looper.getMainLooper()){message->pending.remove(message.arg1)?.takeIf {it.isActive}?.let {c->val error=message.data.getString("error");if(error!=null)c.resumeWithException(IllegalStateException(error))else c.resume(Bundle(message.data))};true})
    private fun failed(){remote=null;val name=if(service==LanguageService::class.java)"language"else "media";val error=IOException("The $name worker stopped. Retry to restart it.");pending.values.forEach {if(it.isActive)it.resumeWithException(error)};pending.clear();connecting?.completeExceptionally(error);connecting=null}
    private suspend fun connect()=withContext(Dispatchers.Main.immediate) {
        if(remote!=null)return@withContext;connecting?.let {it.await();return@withContext}
        val ready=CompletableDeferred<Unit>();connecting=ready
        val callback=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder){if(connection!==this)return;remote=Messenger(binder);ready.complete(Unit);connecting=null}
            override fun onServiceDisconnected(name:ComponentName){if(connection!==this)return;failed();runCatching {context.unbindService(this)};connection=null}
            override fun onBindingDied(name:ComponentName)=onServiceDisconnected(name)
            override fun onNullBinding(name:ComponentName)=onServiceDisconnected(name)
        };connection=callback
        if(!context.bindService(Intent(context,service),callback,Context.BIND_AUTO_CREATE)){connection=null;connecting=null;error("The local reading worker is unavailable.")}
        try {withTimeout(15_000){ready.await()}}catch(e:Throwable){if(connection===callback){runCatching {context.unbindService(callback)};connection=null;remote=null;connecting=null};throw e}
    }
    suspend fun request(command:Int,input:Bundle=Bundle()):Bundle=mutex.withLock {connect();withContext(Dispatchers.Main.immediate) {
        val id=++nextId;suspendCancellableCoroutine {c->pending[id]=c;c.invokeOnCancellation {pending.remove(id);runCatching {remote?.send(Message.obtain(null,ContextMediaIpc.CANCEL,id,0).apply {data=Bundle().apply {putString("session",session)}})}}
            try {remote!!.send(Message.obtain(null,command,id,0).apply {data=Bundle(input).apply {putString("session",session)};replyTo=replies})}catch(e:Throwable){pending.remove(id);c.resumeWithException(e)}
        }
    }}
    suspend fun release()=withContext(Dispatchers.Main.immediate) {runCatching {remote?.send(Message.obtain(null,ContextMediaIpc.RELEASE).apply {data=Bundle().apply {putString("session",session)}})};failed();connection?.let {runCatching {context.unbindService(it)}};connection=null}
}
