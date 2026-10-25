package app.antisocial.android

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContentSandboxTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var sandbox:ContentSandbox
    private lateinit var directory:File
    @Before fun prepare() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        directory=File(context.cacheDir,"sandbox-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        // A named Android service has one process. A private controller's STOP
        // also kills a warm app-owned binding. Use the production single owner
        // so a previous reader/image task cannot race this fixture's teardown.
        sandbox=(context.applicationContext as AntiSocialApplication).repo.contentSandbox
    }
    @After fun cleanup() {if(::directory.isInitialized)directory.deleteRecursively()}
    private fun image(format:Bitmap.CompressFormat=Bitmap.CompressFormat.PNG):File {
        val file=File(directory,"image-${UUID.randomUUID()}")
        val bitmap=Bitmap.createBitmap(1537,791,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {drawColor(Color.rgb(30,80,120));drawCircle(550f,350f,250f,Paint().apply {color=Color.argb(125,255,80,0)});drawText("Public workshop",30f,100f,Paint().apply {color=Color.WHITE;textSize=40f})}
        file.outputStream().use {bitmap.compress(format,93,it)};bitmap.recycle();return file
    }
    @Test fun workerHasDifferentUidNoNetworkPermissionAndCannotReadPrivateFiles():Unit=runBlocking {
        val secret=File(directory,"private-reader-record").apply {writeText("Fixture only, never a real credential")}
        val input=File(directory,"probe").apply {writeText(secret.absolutePath)}
        val result=sandbox.probe(input)
        assertNotEquals(android.os.Process.myUid(),result.first);assertFalse(result.second);assertFalse(result.third)
        assertEquals("Fixture only, never a real credential",secret.readText())
    }
    @Test fun descriptorAdmissionRejectsWritableInputsWithoutChangingBytesOrOffset() {
        val file=File(directory,"descriptor-admission").apply {writeText("Preserve this source")}
        for(mode in listOf(ParcelFileDescriptor.MODE_READ_ONLY,ParcelFileDescriptor.MODE_READ_WRITE,ParcelFileDescriptor.MODE_WRITE_ONLY)) {
            ParcelFileDescriptor.open(file,mode).use {fd->
                val before=android.system.Os.lseek(fd.fileDescriptor,3,android.system.OsConstants.SEEK_SET)
                val expected=mode==ParcelFileDescriptor.MODE_READ_ONLY
                assertEquals(expected,ContentWire.readOnly(fd.fileDescriptor));assertEquals(expected,ContentWire.readOnlyProbe(fd.fileDescriptor))
                assertEquals(before,android.system.Os.lseek(fd.fileDescriptor,0,android.system.OsConstants.SEEK_CUR))
            }
            assertEquals("Preserve this source",file.readText())
        }
    }
    @Test fun pngJpegAndWebpMatchExistingDecoderDimensionsPixelsAndColors():Unit=runBlocking {
        for(format in listOf(Bitmap.CompressFormat.PNG,Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.WEBP)) {
            val file=image(format)
            for(edge in listOf(128,512,2048)) {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>edge)sample*=2
                val expected=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample})!!
                val actual=sandbox.image(file,edge)
                try {assertEquals(expected.width,actual.width);assertEquals(expected.height,actual.height);assertEquals(expected.config,actual.config);assertEquals(expected.colorSpace,actual.colorSpace);assertTrue("$format edge $edge pixels",expected.sameAs(actual))}
                finally {expected.recycle();actual.recycle()}
            }
        }
    }
    @Test fun transparentAndWideGamutImagesKeepTheirColorAndAlphaState():Unit=runBlocking {
        for(config in listOf(Bitmap.Config.ARGB_8888,Bitmap.Config.RGBA_F16)) {
            val source=Bitmap.createBitmap(128,96,config,true,android.graphics.ColorSpace.get(if(config==Bitmap.Config.RGBA_F16)android.graphics.ColorSpace.Named.DISPLAY_P3 else android.graphics.ColorSpace.Named.SRGB))
            Canvas(source).drawCircle(60f,40f,30f,Paint().apply {color=Color.argb(85,100,200,40)})
            val file=File(directory,"alpha-$config.png");file.outputStream().use {source.compress(Bitmap.CompressFormat.PNG,100,it)};source.recycle()
            val expected=BitmapFactory.decodeFile(file.path)!!;val actual=sandbox.image(file,512)
            try {assertEquals(expected.colorSpace,actual.colorSpace);assertEquals(expected.hasAlpha(),actual.hasAlpha());assertEquals(expected.isPremultiplied,actual.isPremultiplied);assertEquals(expected.density,actual.density);val expectedBytes=java.nio.ByteBuffer.allocate(expected.byteCount).also {expected.copyPixelsToBuffer(it)}.array()
                val actualBytes=java.nio.ByteBuffer.allocate(actual.byteCount).also {actual.copyPixelsToBuffer(it)}.array()
                assertArrayEquals("$config raw pixel storage",expectedBytes,actualBytes)
                val expectedPixels=IntArray(expected.width*expected.height);val actualPixels=IntArray(actual.width*actual.height)
                expected.getPixels(expectedPixels,0,expected.width,0,0,expected.width,expected.height);actual.getPixels(actualPixels,0,actual.width,0,0,actual.width,actual.height)
                assertArrayEquals("$config interpreted pixels",expectedPixels,actualPixels)}finally {expected.recycle();actual.recycle()}
        }
    }
    @Test fun platformGifBmpHeifAndAvifSupportAndPixelsStayIntact():Unit=runBlocking {
        val evidence=mutableMapOf<String,String>()
        for(name in InstrumentationRegistry.getInstrumentation().context.assets.list("hardening-images").orEmpty()) {
            val file=File(directory,name);InstrumentationRegistry.getInstrumentation().context.assets.open("hardening-images/$name").use {input->file.outputStream().use {input.copyTo(it)}}
            val expected=BitmapFactory.decodeFile(file.path)
            if(expected==null){evidence[name]="Not supported by this Android decoder";continue}
            val marker=File(requireNotNull(context.getExternalFilesDir("validation")),"sandbox/last-format-api-${android.os.Build.VERSION.SDK_INT}.txt").apply {parentFile!!.mkdirs()}
            marker.writeText("$name config=${expected.config} colors=${expected.colorSpace} ${expected.width}x${expected.height}")
            val actual=sandbox.image(file,512)
            try {assertEquals(expected.width,actual.width);assertEquals(expected.height,actual.height);if(expected.config!=null)assertEquals(expected.config,actual.config);assertEquals(expected.colorSpace,actual.colorSpace)
                val left=IntArray(expected.width*expected.height);val right=IntArray(actual.width*actual.height);expected.getPixels(left,0,expected.width,0,0,expected.width,expected.height);actual.getPixels(right,0,actual.width,0,0,actual.width,actual.height);assertArrayEquals(name,left,right)
                evidence[name]="Supported; dimensions, color space and pixels match"
            }finally {expected.recycle();actual.recycle()}
        }
        val output=File(requireNotNull(context.getExternalFilesDir("validation")),"sandbox").apply {mkdirs()}
        File(output,"formats-api-${android.os.Build.VERSION.SDK_INT}.json").writeText(com.google.gson.Gson().toJson(evidence))
        assertTrue(evidence.keys.any {it.endsWith("gif")});assertTrue(evidence.values.any {it.startsWith("Supported")})
    }
    @Test fun customRgbProfilesSurviveWithoutBeingRelabelledAsSrgb():Unit=runBlocking {
        val space=android.graphics.ColorSpace.Rgb("Workshop RGB",floatArrayOf(.66f,.32f,.20f,.68f,.14f,.07f),floatArrayOf(.3127f,.3290f),2.1)
        val source=Bitmap.createBitmap(128,96,Bitmap.Config.RGBA_F16,true,space)
        Canvas(source).drawColor(Color.argb(180,70,170,230))
        val file=File(directory,"custom.png");file.outputStream().use {source.compress(Bitmap.CompressFormat.PNG,100,it)};source.recycle()
        val expected=BitmapFactory.decodeFile(file.path)!!;val actual=sandbox.image(file,512)
        try {val before=expected.colorSpace as android.graphics.ColorSpace.Rgb;val after=actual.colorSpace as android.graphics.ColorSpace.Rgb;assertArrayEquals(before.primaries,after.primaries,0.00001f);assertArrayEquals(before.whitePoint,after.whitePoint,0.00001f);assertEquals(before.transferParameters!!.g,after.transferParameters!!.g,0.00001)
            val left=IntArray(128*96);val right=IntArray(128*96);expected.getPixels(left,0,128,0,0,128,96);actual.getPixels(right,0,128,0,0,128,96);assertArrayEquals(left,right)
        }finally {expected.recycle();actual.recycle()}
    }
    @Test fun customRgbParametersAreValidatedAndRoundTripAcrossThePixelProtocol() {
        val space=android.graphics.ColorSpace.Rgb("Workshop RGB",floatArrayOf(.66f,.32f,.20f,.68f,.14f,.07f),floatArrayOf(.3127f,.3290f),2.1)
        val source=Bitmap.createBitmap(64,64,Bitmap.Config.RGBA_F16,true,space);Canvas(source).drawColor(Color.rgb(70,170,230))
        val service=ContentSandboxService()
        try {
            val encode=ContentSandboxService::class.java.getDeclaredMethod("pixels",Bitmap::class.java,java.io.DataOutputStream::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}
            val bytes=java.io.ByteArrayOutputStream();encode.invoke(service,source,java.io.DataOutputStream(bytes),false)
            val input=java.io.DataInputStream(bytes.toByteArray().inputStream());assertEquals(ContentWire.MAGIC,input.readInt())
            val decode=ContentSandbox::class.java.getDeclaredMethod("bitmap",java.io.DataInputStream::class.java).apply {isAccessible=true}
            val actual=(decode.invoke(sandbox,input) as ContentSandbox.Raster).bitmap
            try {val original=source.colorSpace as android.graphics.ColorSpace.Rgb;val restored=actual.colorSpace as android.graphics.ColorSpace.Rgb;assertArrayEquals(original.primaries,restored.primaries,0.00001f);assertArrayEquals(original.whitePoint,restored.whitePoint,0.00001f);assertEquals(original.transferParameters!!.g,restored.transferParameters!!.g,0.00001);val left=IntArray(4096);val right=IntArray(4096);source.getPixels(left,0,64,0,0,64,64);actual.getPixels(right,0,64,0,0,64,64);assertArrayEquals(left,right)}finally {actual.recycle()}
        }finally {source.recycle();service.onDestroy()}
    }
    @Test fun controlledArticleRejectionKeepsItsStatusAndDoesNotInvalidateTheWorker():Unit=runBlocking {
        val path=File(directory,"probe").apply {writeText(File(directory,"absent").path)}
        val before=sandbox.probe(path).first
        val url="https://workshop.example/report"
        val rejected=runCatching {sandbox.article(Payload("<title>Sign in</title><div id=login-wall>Log in to continue</div>",url,"text/html",200),url,1234)}.exceptionOrNull()
        assertTrue(rejected is SourceFailure);assertEquals(HealthStatus.LOGIN_REQUIRED,(rejected as SourceFailure).status)
        assertEquals(before,sandbox.probe(path).first)
    }
    @Test fun abandonedArticleInputsStayBoundedAndCacheClearLeavesOtherLocalFilesAlone() {
        val inputs=File(context.cacheDir,"content-input").apply {mkdirs()}
        val fixtures=(0..4).map {i->File(inputs,"fixture-orphan-$i").apply {java.io.RandomAccessFile(this,"rw").use {it.setLength(9L*1024*1024)};setLastModified(System.currentTimeMillis()-i*1000)}}
        val outside=File(directory,"saved-record").apply {writeText("Protected fixture")}
        try {ContentSandbox.cleanup(context.cacheDir);assertTrue(inputs.listFiles().orEmpty().sumOf {it.length()}<=32L*1024*1024)
            ContentSandbox.cleanup(context.cacheDir,true);assertTrue(fixtures.none {it.exists()});assertEquals("Protected fixture",outside.readText())
        }finally {fixtures.forEach {it.delete()}}
    }
    @Test fun malformedWorkerHeadersCannotAllocateOversizedImagesOrPassUnknownFields() {
        val method=ContentSandbox::class.java.getDeclaredMethod("bitmap",java.io.DataInputStream::class.java).apply {isAccessible=true}
        for(header in listOf(intArrayOf(Int.MAX_VALUE,Int.MAX_VALUE,1,4,0,4),intArrayOf(1,1,99,4,0,4),intArrayOf(1,1,1,Int.MAX_VALUE,0,4),intArrayOf(1,1,1,4,999,4),intArrayOf(1,1,1,4,0,-1))) {
            val bytes=java.io.ByteArrayOutputStream();java.io.DataOutputStream(bytes).use {out->header.forEach(out::writeInt);out.writeBoolean(false);out.writeBoolean(true);out.writeBoolean(true);out.writeInt(160)}
            val error=runCatching {method.invoke(sandbox,java.io.DataInputStream(bytes.toByteArray().inputStream()))}.exceptionOrNull()
            assertTrue(error is java.lang.reflect.InvocationTargetException);assertTrue(error!!.cause is IllegalArgumentException||error.cause is java.io.IOException)
        }
        val payload=Payload("<article><h1>Workshop</h1><p>"+"Useful published repair instructions with the compatible battery connector and measured voltage. ".repeat(12)+"</p></article>","https://workshop.example/repair","text/html",200)
        val json=com.google.gson.Gson().toJsonTree(ContextArticles.extract(payload,payload.finalUrl)).asJsonObject
        json.getAsJsonArray("blocks")[0].asJsonObject.addProperty("kind","UNKNOWN_ENUM")
        assertTrue(runCatching {ContentOutput.article(json.toString())}.isFailure)
        json.getAsJsonArray("blocks")[0].asJsonObject.add("text",com.google.gson.JsonNull.INSTANCE)
        assertTrue(runCatching {ContentOutput.article(json.toString())}.isFailure)
    }
    @Test fun pdfRenderingTextAndPageCountRemainIdentical():Unit=runBlocking {
        val file=File(directory,"menu.pdf")
        val document=PdfDocument();try {repeat(2){i->val page=document.startPage(PdfDocument.PageInfo.Builder(600,800,i+1).create());page.canvas.drawText("Workshop menu page ${i+1}",30f,80f,Paint().apply {textSize=24f});document.finishPage(page)};file.outputStream().use {document.writeTo(it)}}finally {document.close()}
        assertEquals(2,sandbox.pdfCount(file))
        for(index in 0..1) {
            val expected=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {pdf->pdf.openPage(index).use {page->
                val scale=minOf(2.0,1000.0/page.width,kotlin.math.sqrt(2_000_000.0/(page.width.toDouble()*page.height)))
                Bitmap.createBitmap(maxOf(1,(page.width*scale).toInt()),maxOf(1,(page.height*scale).toInt()),Bitmap.Config.ARGB_8888).also {it.eraseColor(Color.WHITE);page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
            }}}
            val actual=sandbox.pdfPage(file,index)
            try {assertTrue(expected.sameAs(actual))}finally {expected.recycle();actual.recycle()}
            val text=sandbox.pdfText(file,index);assertEquals(2,text.pageCount);assertTrue(text.text.contains("Workshop menu page ${index+1}"));assertFalse(text.ocr)
        }
    }
    @Test fun shortAnnouncementsKeepTheirFactsInsideThePermissionlessDocumentWorker():Unit=runBlocking {
        val worker=(context.applicationContext as AntiSocialApplication).repo.documentSandbox
        val secret=File(directory,"event-private-record").apply {writeText("Private fixture")}
        val input=File(directory,"event-probe").apply {writeText(secret.absolutePath)}
        val isolation=worker.probe(input)
        assertNotEquals(android.os.Process.myUid(),isolation.first);assertFalse(isolation.second);assertFalse(isolation.third)
        val event=LocalEvent("repair","Radio repair workshop",LocalEventTime("2026-10-30",allDay=true))
        val body="""<html><head><title>${event.title}</title><script type="application/ld+json">{"@type":"Event","name":"${event.title}","startDate":"2026-10-30","location":{"@type":"Place","name":"Library","address":"10 Main St"}}</script></head><body><h1>${event.title}</h1><p>Bring a radio.</p></body></html>"""
        val payload=Payload(body,"https://workshop.example/repair","text/html",200);val at=123456L
        val result=worker.announcement(payload,payload.finalUrl,at,event)
        assertEquals(LocalAnnouncementReading.extract(payload,payload.finalUrl,event,at),result)
        assertTrue(result.text.contains("2026-10-30"));assertTrue(result.text.contains("10 Main St"));assertNull(result.publishedAt)
        assertTrue(File(context.cacheDir,"content-input").listFiles().orEmpty().none {it.name.startsWith("article-")})
    }
    @Test fun articleBlocksLinksAttributionAndCleanupMatchExistingExtraction():Unit=runBlocking {
        val body="<html><head><title>Battery repair guide</title><meta name='author' content='Community workshop'></head><body><article><h1>Battery repair guide</h1>"+
            (1..12).joinToString(""){"<p>Step $it: inspect the battery connector and compare its voltage against the repair manual. Use the correct replacement and disconnect the power before opening the case. The published instructions include measured test results and compatible part numbers.</p>"}+
            "<ul><li>Connector B12</li><li>Voltage 12 V</li></ul><p><a href='/manual'>Read the complete manual</a></p><script>untrustedScript()</script></article></body></html>"
        val payload=Payload(body,"https://workshop.example/repair","text/html",200);val at=1234L
        val expected=ContextArticles.extract(payload,payload.finalUrl,at);val actual=sandbox.article(payload,payload.finalUrl,at)
        assertEquals(expected,actual)
        assertTrue(File(context.cacheDir,"content-input").listFiles().orEmpty().none {it.name.startsWith("article-")})
    }
    @Test fun stuckNativeWorkIsTerminatedAndNextRequestRestartsTheWorker():Unit=runBlocking {
        val file=image();val start=SystemClock.elapsedRealtime()
        assertTrue(runCatching {sandbox.stall(file)}.isFailure)
        assertTrue("Killable worker deadline",SystemClock.elapsedRealtime()-start<5000)
        val bitmap=withTimeout(10_000){sandbox.image(file,512)};assertTrue(bitmap.width>0);bitmap.recycle()
    }
    @Test fun cancellationAndMalformedInputDoNotPoisonTheNextRead():Unit=runBlocking {
        val file=image();val pending=launch {sandbox.stall(file)};delay(50);pending.cancelAndJoin()
        val malformed=File(directory,"bad").apply {writeText("<html>A login wall is not an image</html>")}
        assertTrue(runCatching {sandbox.image(malformed,512)}.isFailure)
        val bitmap=withTimeout(10_000){sandbox.image(file,512)};assertEquals(384,bitmap.width);bitmap.recycle()
    }
    @Test fun warmWorkerLatencyIsRecordedWithoutWeakeningOutputChecks():Unit=runBlocking {
        val file=image();val times=mutableListOf<Long>();val baseline=mutableListOf<Long>()
        fun original(){val start=SystemClock.elapsedRealtimeNanos();val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=4})!!;baseline+=(SystemClock.elapsedRealtimeNanos()-start)/1000;assertEquals(384,bitmap.width);bitmap.recycle()}
        suspend fun isolated(){val start=SystemClock.elapsedRealtimeNanos();val bitmap=sandbox.image(file,512);times+=(SystemClock.elapsedRealtimeNanos()-start)/1000;assertEquals(384,bitmap.width);bitmap.recycle()}
        repeat(13){i->if(i%2==0){original();isolated()}else {isolated();original()}}
        val output=File(requireNotNull(context.getExternalFilesDir("validation")),"sandbox").apply {mkdirs()}
        File(output,"decode-timings.json").writeText(com.google.gson.Gson().toJson(mapOf("unit" to "microseconds","firstRead" to times.first(),"workerMayAlreadyBeWarm" to true,"warm" to times.drop(1),"baseline" to baseline.drop(1))))
    }
}
