/* anti social, GPL-3.0-or-later. Descriptor-only boundary for untrusted decoders. */
package app.antisocial.android

import android.app.Service
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.pdf.PdfRenderer
import android.os.*
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

internal object ContentWire {
    const val DESCRIPTOR="app.antisocial.content.v1"
    const val RUN=IBinder.FIRST_CALL_TRANSACTION
    const val STOP=RUN+1
    const val IMAGE=1
    const val PDF_COUNT=2
    const val PDF_PAGE=3
    const val ARTICLE=4
    const val PDF_TEXT=5
    const val OCR_IMAGE=6
    const val PDF_EMBEDDED=7
    const val PDF_OCR_PAGE=8
    const val IMAGE_BOUNDS=9
    const val PROBE=100
    const val STALL=101
    const val MAGIC=0x41535231
    const val REJECTED=0x41534531
    const val MAX_PIXELS=4_200_000L
    const val MAX_BYTES=MAX_PIXELS*8
    fun readOnly(fd:FileDescriptor):Boolean=if(Build.VERSION.SDK_INT>=30) {
        android.system.Os.fcntlInt(fd,android.system.OsConstants.F_GETFL,0) and android.system.OsConstants.O_ACCMODE==android.system.OsConstants.O_RDONLY
    }else readOnlyProbe(fd)
    /** fcntlInt became public in API30. The older public Os.write syscall
     * checks write permission even for zero bytes, without changing contents or
     * offset. fstat first rejects closed descriptors; no hidden API is used. */
    internal fun readOnlyProbe(fd:FileDescriptor):Boolean {
        android.system.Os.fstat(fd)
        return try {android.system.Os.write(fd,ByteArray(0),0,0);false}
        catch(e:android.system.ErrnoException){if(e.errno==android.system.OsConstants.EBADF)true else throw e}
    }
    fun config(code:Int)=when(code){1->Bitmap.Config.ARGB_8888;2->Bitmap.Config.RGB_565;3->Bitmap.Config.RGBA_F16;4->if(Build.VERSION.SDK_INT>=33)Bitmap.Config.RGBA_1010102 else throw IOException("Invalid pixel format");else->throw IOException("Invalid pixel format")}
    fun configCode(config:Bitmap.Config?):Int {if(Build.VERSION.SDK_INT>=33&&config==Bitmap.Config.RGBA_1010102)return 4;return when(config){Bitmap.Config.ARGB_8888->1;Bitmap.Config.RGB_565->2;Bitmap.Config.RGBA_F16->3;else->throw IOException("Unsupported pixel format")}}
}

/** Descriptor-only worker protocol: no Repository, URL, path or model weights.
 * ContentSandboxService and DocumentSandboxService are permissionless isolated
 * UIDs in the manifest; their writable output capability is a bounded pipe.
 * PlatformImageService reuses this protocol with the ordinary app UID instead
 * for HEIF/AVIF compatibility: this base class alone does not confer isolation.
 * The watchdog runs independently of native decoding. See docs/HARDENING.md
 * and docs/ARCHITECTURE.md for Gateway acquisition and parent validation. */
open class ContentSandboxService:Service() {
    protected open fun accepts(operation:Int)=true
    private val lane=Executors.newSingleThreadExecutor()
    private val watchdog=Executors.newSingleThreadScheduledExecutor()
    private val binder=object:Binder(){
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            if(code==INTERFACE_TRANSACTION){reply?.writeString(ContentWire.DESCRIPTOR);return true}
            if(code !in setOf(ContentWire.RUN,ContentWire.STOP))return super.onTransact(code,data,reply,flags)
            data.enforceInterface(ContentWire.DESCRIPTOR)
            if(code==ContentWire.STOP){Process.killProcess(Process.myPid());return true}
            val operation=data.readInt();val parameter=data.readInt();val timeout=data.readLong().coerceIn(100,30_000)
            val input=ParcelFileDescriptor.CREATOR.createFromParcel(data)
            val output=ParcelFileDescriptor.CREATOR.createFromParcel(data)
            lane.execute {
                val alarm=watchdog.schedule({Process.killProcess(Process.myPid())},timeout,TimeUnit.MILLISECONDS)
                try {input.use {fd->DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(output)).use {out->
                    // A writeable input descriptor is never an accepted capability.
                    require(ContentWire.readOnly(fd.fileDescriptor))
                    require(accepts(operation))
                    try {when(operation) {
                        ContentWire.IMAGE->image(fd,parameter).useBitmap {pixels(it,out)}
                        ContentWire.PDF_COUNT->PdfRenderer(ParcelFileDescriptor.dup(fd.fileDescriptor)).use {pdf->out.writeInt(ContentWire.MAGIC);out.writeInt(pdf.pageCount)}
                        ContentWire.PDF_PAGE,ContentWire.PDF_OCR_PAGE->PdfRenderer(ParcelFileDescriptor.dup(fd.fileDescriptor)).use {pdf->
                            require((operation==ContentWire.PDF_OCR_PAGE||pdf.pageCount in 1..2000)&&parameter in 0 until pdf.pageCount)
                            pdf.openPage(parameter).use {page->
                                require(page.width>0&&page.height>0)
                                val ocr=operation==ContentWire.PDF_OCR_PAGE
                                val scale=if(ocr)minOf(2.5,kotlin.math.sqrt(4_000_000.0/(page.width.toDouble()*page.height)))else minOf(2.0,1000.0/page.width,kotlin.math.sqrt(2_000_000.0/(page.width.toDouble()*page.height)))
                                val width=if(ocr)(page.width*scale).roundToInt()else (page.width*scale).toInt()
                                val height=if(ocr)(page.height*scale).roundToInt()else (page.height*scale).toInt()
                                Bitmap.createBitmap(maxOf(1,width),maxOf(1,height),Bitmap.Config.ARGB_8888).useBitmap {bitmap->
                                    bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);pixels(bitmap,out)
                                }
                            }
                        }
                        ContentWire.ARTICLE->{
                            val input=DataInputStream(FileInputStream(fd.fileDescriptor))
                            val url=input.readUTF();val original=input.readUTF();val type=input.readUTF();val status=input.readInt();val at=input.readLong()
                            val size=input.readInt();require(size in 0..8*1024*1024)
                            val bytes=ByteArray(size);input.readFully(bytes)
                            val article=ContextArticles.extract(Payload(bytes.toString(Charsets.UTF_8),url,type,status),original,at,readerChecks=true)
                            text(Gson().toJson(article),out)
                        }
                        ContentWire.PDF_TEXT->text(Gson().toJson(pdfText(fd,parameter)),out)
                        ContentWire.PDF_EMBEDDED->PdfRenderer(ParcelFileDescriptor.dup(fd.fileDescriptor)).use {pdf->
                            require(parameter in 0 until pdf.pageCount)
                            pdf.openPage(parameter).use {page->
                                val embedded=if(Build.VERSION.SDK_INT>=35)page.textContents.joinToString("\n"){it.text}.trim()else ""
                                text(Gson().toJson(ContextPdfPageResult(parameter,pdf.pageCount,embedded.take(80_000),false,embedded.isBlank(),truncated=embedded.length>80_000)),out)
                            }
                        }
                        ContentWire.OCR_IMAGE->{val (image,scaled)=ocrImage(fd);image.useBitmap {pixels(it,out,scaled)}}
                        ContentWire.IMAGE_BOUNDS->{val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};fileDecode(fd,bounds)
                            out.writeInt(ContentWire.MAGIC);out.writeInt(bounds.outWidth);out.writeInt(bounds.outHeight)}
                        ContentWire.PROBE->{check(BuildConfig.DEBUG);val path=FileInputStream(fd.fileDescriptor).bufferedReader().readText().take(2048)
                            out.writeInt(ContentWire.MAGIC);out.writeInt(Process.myUid());out.writeBoolean(checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)
                            out.writeBoolean(runCatching {FileInputStream(path).use {it.read()};true}.getOrDefault(false))
                        }
                        ContentWire.STALL->{check(BuildConfig.DEBUG);Thread.sleep(60_000)}
                        else->throw IOException("Unknown content operation")
                    }}catch(e:SourceFailure) {
                        check(out.size()==0);out.writeInt(ContentWire.REJECTED);out.writeInt(e.status.ordinal)
                        out.writeUTF(e.message.orEmpty().take(300))
                    }
                }}}catch(_:Exception){runCatching {input.close()};runCatching {output.close()}}
                finally {alarm.cancel(false)}
            }
            return true
        }
    }
    private inline fun <T> Bitmap.useBitmap(block:(Bitmap)->T):T=try {block(this)}finally {recycle()}
    private fun ocrImage(fd:ParcelFileDescriptor):Pair<Bitmap,Boolean> {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFileDescriptor(fd.fileDescriptor,null,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<500_000_000L)
        var sample=1;while(bounds.outWidth.toLong()*bounds.outHeight/(sample.toLong()*sample)>4_000_000)sample*=2
        android.system.Os.lseek(fd.fileDescriptor,0,android.system.OsConstants.SEEK_SET)
        val decoded=BitmapFactory.decodeFileDescriptor(fd.fileDescriptor,null,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}) ?: throw IOException("Image unavailable")
        val orientation=runCatching {android.media.ExifInterface(fd.fileDescriptor).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
        val transform=android.graphics.Matrix().apply {when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f)}}
        val rotated=if(transform.isIdentity)decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,transform,true).also {decoded.recycle()}
        try {return Bitmap.createBitmap(rotated.width,rotated.height,Bitmap.Config.ARGB_8888).also {white->android.graphics.Canvas(white).apply {drawColor(Color.WHITE);drawBitmap(rotated,0f,0f,null)}} to (sample>1)}finally {rotated.recycle()}
    }
    private fun text(value:String,out:DataOutputStream) {
        val bytes=value.toByteArray(Charsets.UTF_8);require(bytes.size<=4*1024*1024)
        out.writeInt(ContentWire.MAGIC);out.writeInt(bytes.size);out.write(bytes)
    }
    private class TextLimit:IOException()
    private fun pdfText(fd:ParcelFileDescriptor,index:Int):ContextPdfPageResult {
        require(index in 0..5)
        val stream=FileInputStream(fd.fileDescriptor);val bytes=stream.readBytesBounded(4*1024*1024)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        // Same total scratch budget as the previous mixed-memory parser, now
        // entirely in the disposable process; no writable file is granted.
        val memory=com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly(8L*1024*1024)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(ByteArrayInputStream(bytes),memory).use {document->
            require(!document.isEncrypted&&document.numberOfPages in 1..256&&index<document.numberOfPages)
            val result=StringBuilder();var truncated=false
            val writer=object:Writer(){
                override fun write(buffer:CharArray,offset:Int,length:Int){val keep=minOf(length,(LocalMenus.MAX_TEXT-result.length).coerceAtLeast(0));result.append(buffer,offset,keep);if(keep<length)throw TextLimit()}
                override fun flush(){};override fun close(){}
            }
            try {com.tom_roush.pdfbox.text.PDFTextStripper().apply {startPage=index+1;endPage=index+1;sortByPosition=true}.writeText(document,writer)}catch(_:TextLimit){truncated=true}
            return ContextPdfPageResult(index,document.numberOfPages,result.toString().trim(),false,false,truncated=truncated)
        }
    }
    private fun InputStream.readBytesBounded(limit:Int):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val count=read(buffer);if(count<0)break;require(out.size()+count<=limit);out.write(buffer,0,count)}
        return out.toByteArray()
    }
    private fun fileDecode(fd:ParcelFileDescriptor,options:BitmapFactory.Options):Bitmap? {
        android.system.Os.lseek(fd.fileDescriptor,0,android.system.OsConstants.SEEK_SET)
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use {BitmapFactory.decodeStream(it,null,options)}
    }
    private fun image(fd:ParcelFileDescriptor,edge:Int):Bitmap {
        require(edge in 128..2048)
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        fileDecode(fd,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=500_000_000L)
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>edge)sample*=2
        // decodeFile used the stream codec. Keep it, through a descriptor;
        // decodeFileDescriptor can differ in transparent/colour conversion.
        return fileDecode(fd,BitmapFactory.Options().apply {inSampleSize=sample}) ?: throw IOException("Image unavailable")
    }
    private fun pixels(bitmap:Bitmap,out:DataOutputStream,scaled:Boolean=false) {
        require(bitmap.width in 1..65535&&bitmap.height in 1..65535&&bitmap.width.toLong()*bitmap.height<=ContentWire.MAX_PIXELS)
        if(bitmap.config !in setOf(Bitmap.Config.ARGB_8888,Bitmap.Config.RGB_565,Bitmap.Config.RGBA_F16)&&!(Build.VERSION.SDK_INT>=33&&bitmap.config==Bitmap.Config.RGBA_1010102)) {
            // Android 8 can expose indexed GIF storage without a public Config.
            // Expand the palette in the isolated worker, preserving ARGB pixels.
            bitmap.copy(Bitmap.Config.ARGB_8888,false).useBitmap {pixels(it,out,scaled)};return
        }
        require(bitmap.byteCount.toLong() in 1..ContentWire.MAX_BYTES)
        val custom=bitmap.colorSpace?.takeIf {it.id<0} as? ColorSpace.Rgb
        if(custom!=null&&custom.transferParameters==null) {
            // Public Android APIs cannot serialize arbitrary transfer functions.
            // Convert colourimetrically to a named linear float space, retaining
            // wide-range colour rather than relabelling its raw samples as sRGB.
            val converted=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.RGBA_F16,bitmap.hasAlpha(),ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
            converted.density=bitmap.density
            converted.useBitmap {target->android.graphics.Canvas(target).drawBitmap(bitmap,android.graphics.Rect(0,0,bitmap.width,bitmap.height),android.graphics.Rect(0,0,target.width,target.height),null);pixels(target,out,scaled)};return
        }
        val bytes=ByteBuffer.allocate(bitmap.byteCount);bitmap.copyPixelsToBuffer(bytes)
        out.writeInt(ContentWire.MAGIC);out.writeInt(bitmap.width);out.writeInt(bitmap.height);out.writeInt(ContentWire.configCode(bitmap.config))
        out.writeInt(bitmap.rowBytes);out.writeInt(bitmap.colorSpace?.id ?: 0);out.writeInt(bitmap.byteCount);out.writeBoolean(scaled);out.writeBoolean(bitmap.hasAlpha());out.writeBoolean(bitmap.isPremultiplied);out.writeInt(bitmap.density)
        if(custom!=null) {
            out.writeUTF(custom.name.take(100));custom.primaries.forEach(out::writeFloat);custom.whitePoint.forEach(out::writeFloat)
            val p=requireNotNull(custom.transferParameters);listOf(p.a,p.b,p.c,p.d,p.e,p.f,p.g).forEach(out::writeDouble)
        }
        out.write(bytes.array())
    }
    override fun onBind(intent:Intent):IBinder=binder
    override fun onUnbind(intent:Intent):Boolean {Process.killProcess(Process.myPid());return false}
    override fun onDestroy(){lane.shutdownNow();watchdog.shutdownNow();super.onDestroy()}
}

/** A warm single worker amortizes process startup. Cached images never cross
 * IPC again. Parent checks every size before allocating, never decodes a
 * worker-supplied compressed image, and discards incomplete output. */
class DocumentSandboxService:ContentSandboxService()

/** HEIF/AVIF needs Android's remote media service, denied to isolated UIDs.
 * This dedicated compatibility process shares the app UID; it is not a
 * permission sandbox. It receives only a descriptor, never a URL or path. */
class PlatformImageService:ContentSandboxService() {
    override fun accepts(operation:Int)=operation in setOf(ContentWire.IMAGE,ContentWire.IMAGE_BOUNDS,ContentWire.OCR_IMAGE)
}

internal class ContentSandbox(private val context:Context,private val service:Class<out Service> = ContentSandboxService::class.java) {
    private val mutex=Mutex()
    private var connection:ServiceConnection?=null
    private var binder:IBinder?=null
    private val main=Handler(Looper.getMainLooper())
    private val reader=Executors.newSingleThreadExecutor {r->Thread(r,"anti social content pipe").apply {isDaemon=true}}
    private val idle=Runnable {disconnect()}
    private fun disconnect() {
        main.removeCallbacks(idle)
        binder?.let {remote->runCatching {Parcel.obtain().useParcel {p->p.writeInterfaceToken(ContentWire.DESCRIPTOR);remote.transact(ContentWire.STOP,p,null,IBinder.FLAG_ONEWAY)}}}
        binder=null;connection?.let {runCatching {context.unbindService(it)}};connection=null
    }
    private suspend fun connect():IBinder=withContext(Dispatchers.Main.immediate) {
        main.removeCallbacks(idle);binder?.takeIf {it.isBinderAlive}?.let {return@withContext it}
        disconnect()
        val ready=CompletableDeferred<IBinder>()
        val callback=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,service:IBinder){if(connection===this){binder=service;ready.complete(service)}}
            override fun onServiceDisconnected(name:ComponentName){if(connection===this){binder=null;ready.completeExceptionally(IOException("Content worker stopped"))}}
            override fun onBindingDied(name:ComponentName)=onServiceDisconnected(name)
            override fun onNullBinding(name:ComponentName)=onServiceDisconnected(name)
        }
        connection=callback
        check(context.bindService(Intent(context,service),callback,Context.BIND_AUTO_CREATE)){"Content worker unavailable"}
        try {withTimeout(15_000){ready.await()}}catch(e:Throwable){disconnect();throw e}
    }
    private inline fun <T> Parcel.useParcel(block:(Parcel)->T):T=try {block(this)}finally {recycle()}
    private suspend fun <T> read(file:File,operation:Int,parameter:Int=0,deadlineMs:Long=15_000,decode:(DataInputStream)->T):T=mutex.withLock {
        withContext(Dispatchers.IO) {
            val remote=connect();val pipes=ParcelFileDescriptor.createPipe()
            // Independent timer: native decoder work need not cooperate with
            // coroutine cancellation. STOP is handled on a Binder thread.
            val stop=Runnable {disconnect();runCatching {pipes[0].close()}}
            main.postDelayed(stop,deadlineMs+1000)

            try {
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {input->
                    pipes[1].use {output->Parcel.obtain().useParcel {p->
                        p.writeInterfaceToken(ContentWire.DESCRIPTOR);p.writeInt(operation);p.writeInt(parameter);p.writeLong(deadlineMs)
                        input.writeToParcel(p,0);output.writeToParcel(p,0)
                        check(remote.transact(ContentWire.RUN,p,null,IBinder.FLAG_ONEWAY)){"Content worker unavailable"}
                    }}
                }
                suspendCancellableCoroutine<T> {continuation->
                    continuation.invokeOnCancellation {main.post(stop)}
                    reader.execute {
                        try {
                            val result=DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipes[0])).use {stream->
                                val marker=stream.readInt()
                                if(marker==ContentWire.REJECTED) {
                                    val status=HealthStatus.entries.getOrNull(stream.readInt()) ?: throw IOException("Invalid worker status")
                                    val message=stream.readUTF();require(message.length<=300&&stream.read()==-1)
                                    throw SourceFailure(status,message)
                                }
                                check(marker==ContentWire.MAGIC){"Content could not be read"}
                                decode(stream).also {check(stream.read()==-1){"Invalid content output"}}
                            }
                            continuation.resume(result)
                        }catch(e:Exception){continuation.resumeWithException(e)}
                    }
                }
            }catch(e:SourceFailure){throw e}catch(e:Exception){withContext(NonCancellable+Dispatchers.Main.immediate){disconnect()};throw e}
            finally {main.removeCallbacks(stop);runCatching {pipes[0].close()};runCatching {pipes[1].close()};withContext(NonCancellable+Dispatchers.Main.immediate){main.postDelayed(idle,60_000)}}
        }
    }
    internal data class Raster(val bitmap:Bitmap,val scaled:Boolean)
    private fun bitmap(stream:DataInputStream):Raster {
        val width=stream.readInt();val height=stream.readInt();val config=ContentWire.config(stream.readInt())
        val stride=stream.readInt();val color=stream.readInt();val count=stream.readInt();val scaled=stream.readBoolean();val alpha=stream.readBoolean();val premultiplied=stream.readBoolean();val density=stream.readInt()
        require(density in 0..65535)
        require(width in 1..65535&&height in 1..65535&&width.toLong()*height<=ContentWire.MAX_PIXELS)
        val bpp=when(config){Bitmap.Config.RGBA_F16->8;Bitmap.Config.RGB_565->2;else->4}
        require(stride.toLong() in width.toLong()*bpp..width.toLong()*bpp+8&&count.toLong()==stride.toLong()*height&&count.toLong() in 1..ContentWire.MAX_BYTES)
        require(color in -1 until ColorSpace.Named.entries.size)
        val space=if(color<0) {
            val name=stream.readUTF();require(name.length<=100)
            val primaries=FloatArray(6){stream.readFloat().also {require(it.isFinite()&&it in -16f..16f)}}
            val white=FloatArray(2){stream.readFloat().also {require(it.isFinite()&&it in -16f..16f)}}
            val p=DoubleArray(7){stream.readDouble().also {require(it.isFinite()&&it in -1000.0..1000.0)}}
            ColorSpace.Rgb(name,primaries,white,ColorSpace.Rgb.TransferParameters(p[0],p[1],p[2],p[3],p[4],p[5],p[6]))
        }else ColorSpace.get(ColorSpace.Named.entries[color])
        val result=Bitmap.createBitmap(width,height,config,alpha,space)
        try {require(result.rowBytes==stride&&result.byteCount==count);val bytes=ByteArray(count);stream.readFully(bytes);result.copyPixelsFromBuffer(ByteBuffer.wrap(bytes));result.setPremultiplied(premultiplied);result.density=density;return Raster(result,scaled)}
        catch(e:Exception){result.recycle();throw e}
    }
    /** A visible uncached image can bind while its gateway download is in flight.
     * This opens no input and never starts an AI model or a network request. */
    suspend fun warm()=mutex.withLock {
        connect();withContext(Dispatchers.Main.immediate){main.postDelayed(idle,60_000)}
    }
    suspend fun image(file:File,edge:Int):Bitmap=if(platformImage(file))platform().image(file,edge)else read(file,ContentWire.IMAGE,edge,decode=::bitmap).bitmap
    suspend fun pdfCount(file:File)=read(file,ContentWire.PDF_COUNT){it.readInt().also {count->require(count in 1..2000)}}
    suspend fun pdfPage(file:File,index:Int)=read(file,ContentWire.PDF_PAGE,index,decode=::bitmap).bitmap
    suspend fun ocrImage(file:File):Raster=if(platformImage(file))platform().ocrImage(file)else read(file,ContentWire.OCR_IMAGE,decode=::bitmap)
    suspend fun pdfOcrPage(file:File,index:Int)=read(file,ContentWire.PDF_OCR_PAGE,index,decode=::bitmap).bitmap
    suspend fun imageBounds(file:File):Pair<Int,Int> = if(platformImage(file))platform().imageBounds(file)else read(file,ContentWire.IMAGE_BOUNDS){it.readInt() to it.readInt()}
    suspend fun pdfEmbedded(file:File,index:Int)=read(file,ContentWire.PDF_EMBEDDED,index){stream->ContentOutput.pdf(json(stream)).also {result->require(result.pageIndex==index&&result.pageCount>index&&result.text.length<=80_000&&!result.ocr)}}
    private fun json(stream:DataInputStream):String {
        val size=stream.readInt();require(size in 0..4*1024*1024)
        val bytes=ByteArray(size);stream.readFully(bytes);val text=bytes.toString(Charsets.UTF_8)
        // Validate nesting before the app deserializes a worker response.
        var depth=0;var quoted=false;var escaped=false
        for(c in text) {if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
            else if(c=='"')quoted=true else if(c=='{'||c=='['){depth++;require(depth<=32)}else if(c=='}'||c==']'){depth--;require(depth>=0)}}
        require(depth==0&&!quoted);return text
    }
    suspend fun article(payload:Payload,original:String,at:Long):ContextArticleEvidence=articleInputs.withPermit {
        val directory=File(context.cacheDir,"content-input").apply {mkdirs()}
        val file=synchronized(activeInputs){cleanup(context.cacheDir,reserve=MAX_INPUT);File.createTempFile("article-",".tmp",directory).also {activeInputs+=it.path}}
        try {
            withContext(Dispatchers.IO){DataOutputStream(file.outputStream()).use {out->
                out.writeUTF(payload.finalUrl);out.writeUTF(original);out.writeUTF(payload.contentType);out.writeInt(payload.status);out.writeLong(at)
                val text=payload.text.toByteArray(Charsets.UTF_8);if(text.size>8*1024*1024)throw SourceFailure(HealthStatus.UNSUPPORTED,"This article exceeds the extraction transfer allowance");out.writeInt(text.size);out.write(text)
            }}
            read(file,ContentWire.ARTICLE){stream->ContentOutput.article(json(stream)).also {doc->
                require(doc.originalUrl==original&&doc.finalUrl==payload.finalUrl&&doc.fetchedAt==at&&doc.title.length<=1000)
                require(doc.blocks.size<=30_000&&doc.blocks.sumOf {it.text.length.toLong()}<=ContextArticles.MAX_TEXT_CHARS+1000)
            }}
        }finally {synchronized(activeInputs){file.delete();activeInputs-=file.path}}
    }
    suspend fun pdfText(file:File,index:Int)=read(file,ContentWire.PDF_TEXT,index){stream->ContentOutput.pdf(json(stream)).also {result->
        require(result.pageIndex==index&&result.pageCount in 1..256&&result.text.length<=LocalMenus.MAX_TEXT&&!result.ocr)
    }}
    internal suspend fun probe(file:File)=read(file,ContentWire.PROBE){Triple(it.readInt(),it.readBoolean(),it.readBoolean())}
    internal suspend fun stall(file:File)=read(file,ContentWire.STALL,deadlineMs=250){it.readInt()}
    companion object {
        private const val MAX_INPUT=8L*1024*1024+256*1024
        private val articleInputs=Semaphore(2)
        private val activeInputs=HashSet<String>()
        private var platformWorker:ContentSandbox?=null
        private val imageBrands=setOf("avif","avis","heic","heix","hevc","hevx","heim","heis","hevm","hevs","mif1","msf1")
        /** Container identity, never filename, extension, URL or parse failure. */
        internal fun needsPlatform(file:File):Boolean=file.inputStream().use {input->
            val bytes=ByteArray(4096);var used=0
            while(used<bytes.size){val n=input.read(bytes,used,bytes.size-used);if(n<0)break;used+=n}
            var at=0;var boxes=0
            fun int(offset:Int)=java.nio.ByteBuffer.wrap(bytes,offset,4).int
            while(at+16<=used&&boxes++<16) {
                val size=int(at);if(size<8||size>used-at)return@use false
                val kind=String(bytes,at+4,4,Charsets.US_ASCII)
                if(kind=="ftyp") {
                    val brands=listOf(String(bytes,at+8,4,Charsets.US_ASCII))+(at+16 until at+size step 4).filter {it+4<=at+size}.map {String(bytes,it,4,Charsets.US_ASCII)}
                    return@use brands.any {it in imageBrands}
                }
                if(kind !in setOf("free","skip","wide"))return@use false
                at+=size
            };false
        }
        fun cleanup(cacheDir:File,clearAll:Boolean=false,reserve:Long=0)=synchronized(activeInputs) {
            val files=File(cacheDir,"content-input").listFiles().orEmpty().filter {it.isFile&&it.path !in activeInputs}.sortedBy {it.lastModified()}.toMutableList()
            val now=System.currentTimeMillis()
            files.removeAll {file->(clearAll||now-file.lastModified()>15*60_000L)&&file.delete()}
            var bytes=activeInputs.size*MAX_INPUT+files.sumOf {it.length()}+reserve
            for(file in files){if(bytes<=32L*1024*1024)break;val size=file.length();if(file.delete())bytes-=size}
        }
    }
    private fun platformImage(file:File)=service!=PlatformImageService::class.java&&needsPlatform(file)
    private fun platform():ContentSandbox=synchronized(Companion){platformWorker ?: ContentSandbox(context.applicationContext,PlatformImageService::class.java).also {platformWorker=it}}
    suspend fun close()=withContext(Dispatchers.Main.immediate){mutex.withLock {disconnect();reader.shutdownNow()}}
}
