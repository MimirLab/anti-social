package app.antisocial.android

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.*
import app.antisocial.core.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Development fixtures are explicitly staged by the test runner. These tests
 * never download a model or touch a user's feed or settings. */
@RunWith(AndroidJUnit4::class)
class ContextMediaWorkerTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val gson=Gson()
    private fun optIn(){assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialContextMediaModels")=="true"&&InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")}
    private fun models()=File(context.filesDir,"context-media/packs/test")
    private fun ocrInput()=Bundle().apply {putString("kind","OCR");putString("path",models().path);putString("language","eng")}
    private fun speechInput()=Bundle().apply {putString("kind","SPEECH");putString("model",File(models(),"ggml-base-q5_1.bin").path);putString("vad",File(models(),"ggml-silero-v6.2.0.bin").path)}
    private suspend fun request(client:ContextMediaClient,command:Int,input:Bundle,file:File):Bundle=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->input.putParcelable("file",fd);client.request(command,input)}
    @Test fun imageTextRunsThroughReadOnlyDescriptorInPrivateProcess(): Unit=runBlocking {
        optIn();val client=ContextMediaClient(context);val file=File(context.cacheDir,"context-ocr-test.png")
        try {
            val image=Bitmap.createBitmap(1400,400,Bitmap.Config.ARGB_8888)
            Canvas(image).apply {drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=60f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)};drawText("Local private reader.",60f,140f,paint);drawText("Keep useful information.",60f,230f,paint)}
            file.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            client.request(ContextMediaIpc.PREPARE,ocrInput())
            val response=request(client,ContextMediaIpc.OCR,ocrInput(),file);assertNotEquals(android.os.Process.myPid(),response.getInt("workerPid"))
            val result=gson.fromJson(response.getByteArray("result")!!.toString(Charsets.UTF_8),OcrResult::class.java)
            assertTrue(result.text.contains("Local private reader."));assertTrue(result.text.contains("Keep useful information."));assertTrue(result.meanConfidence>=80);assertFalse(result.uncertain);assertTrue(result.lines.size>=2)
            File(models(),"android-ocr-result.json").writeText(gson.toJson(result))
        }finally {file.delete();client.release()}
    }
    @Test fun localAudioDecodesAndSlicesPreserveSourceTimestampsAndCancellationRecovers(): Unit=runBlocking {
        optIn();val client=ContextMediaClient(context);val audio=File(models(),"jfk.m4a");assumeTrue(audio.isFile)
        try {
            client.request(ContextMediaIpc.PREPARE,speechInput())
            val response=withTimeout(180_000){request(client,ContextMediaIpc.SPEECH,speechInput().apply {putLong("startMs",0);putLong("durationMs",120_000)},audio)}
            val result=gson.fromJson(response.getByteArray("result")!!.toString(Charsets.UTF_8),TranscriptResult::class.java)
            assertTrue(result.hasSpeech);assertEquals("en",result.language);assertTrue(result.text.contains("country",true));assertTrue(result.complete);assertTrue(result.nextOffsetMs in 10_000..12_000)
            assertTrue(result.segments.all {it.startMs>=0&&it.endMs>=it.startMs&&it.endMs<=result.nextOffsetMs})
            val slice=withTimeout(180_000){request(client,ContextMediaIpc.SPEECH,speechInput().apply {putLong("startMs",5500);putLong("durationMs",5500)},audio)}
            val second=gson.fromJson(slice.getByteArray("result")!!.toString(Charsets.UTF_8),TranscriptResult::class.java)
            assertEquals(5500L,second.startOffsetMs);assertTrue(second.segments.all {it.startMs>=5500});assertTrue(second.nextOffsetMs>second.startOffsetMs)
            val cancelled=launch {request(client,ContextMediaIpc.SPEECH,speechInput(),audio)};delay(500);cancelled.cancelAndJoin()
            var recovered=false
            repeat(30){if(!recovered){delay(100);try {client.request(ContextMediaIpc.PREPARE,ocrInput());recovered=true}catch(e:IllegalStateException){if(e.message?.contains("busy")!=true)throw e}}}
            assertTrue("Cancellation must release the worker for another tool",recovered)
            File(models(),"android-speech-result.json").writeText(gson.toJson(listOf(result,second)))
        }finally {client.release()}
    }
    @Test fun streamedAudioSlicesAdvanceUntilRealEndWithoutRepeatingSourceTimes(): Unit=runBlocking {
        optIn();val client=ContextMediaClient(context);val audio=File(models(),"jfk.ts");assumeTrue("MPEG-TS audio fixture must be staged",audio.isFile)
        try {
            val slices=ArrayList<TranscriptResult>();var offset=0L
            for(duration in listOf(2_000L,2_000L,120_000L)) {
                val response=withTimeout(180_000){request(client,ContextMediaIpc.SPEECH,speechInput().apply {putLong("startMs",offset);putLong("durationMs",duration)},audio)}
                val result=gson.fromJson(response.getByteArray("result")!!.toString(Charsets.UTF_8),TranscriptResult::class.java)
                assertEquals(offset,result.startOffsetMs);assertTrue(result.nextOffsetMs>offset)
                assertTrue(result.segments.all {it.startMs>=offset&&it.endMs<=result.nextOffsetMs&&it.endMs>=it.startMs})
                if(duration==2_000L)assertFalse("A bounded slice must not pretend it is the source end",result.complete)else assertTrue("Observed source EOF must finish resumable work",result.complete)
                slices.add(result);offset=result.nextOffsetMs
            }
            assertTrue(offset in 10_000..12_000);assertTrue(slices.zipWithNext().all {(a,b)->a.nextOffsetMs==b.startOffsetMs})
            File(models(),"android-streamed-slices-result.json").writeText(gson.toJson(slices))
        }finally {client.release()}
    }
    @Test fun immediateCancellationNeverLeavesMediaWorkerPermanentlyBusy(): Unit=runBlocking {
        optIn();val client=ContextMediaClient(context)
        try {
            client.request(ContextMediaIpc.PREPARE,ocrInput())
            repeat(20) {
                withContext(Dispatchers.Main.immediate) {
                    val request=launch(start=CoroutineStart.UNDISPATCHED){client.request(ContextMediaIpc.PREPARE,ocrInput())}
                    request.cancelAndJoin()
                }
                var ready=false
                repeat(30){if(!ready){delay(30);try {client.request(ContextMediaIpc.PREPARE,ocrInput());ready=true}catch(e:IllegalStateException){if(e.message?.contains("busy")!=true)throw e}}}
                assertTrue("Cancelled request must release its queue entry",ready)
            }
        }finally {client.release()}
    }
    @Test fun malformedPdfAndOversizedImageFailWithoutKillingReader(): Unit=runBlocking {
        val client=ContextMediaClient(context);val bad=File(context.cacheDir,"context-malformed.pdf");val image=File(context.cacheDir,"context-oversized.png")
        try {
            bad.writeText("This is not a PDF document.")
            try {request(client,ContextMediaIpc.PDF,Bundle().apply {putInt("pageIndex",0);putBoolean("ocrEnabled",false)},bad);fail("Malformed PDF must fail explicitly")}catch(expected:IllegalStateException){assertFalse(expected.message.isNullOrBlank())}
            // Valid PNG chunks advertise an unsafe 2.5-billion-pixel image.
            // The reader must reject bounds before allocating a bitmap.
            java.io.DataOutputStream(image.outputStream()).use {out->
                out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
                fun chunk(type:String,payload:ByteArray){val name=type.toByteArray();out.writeInt(payload.size);out.write(name);out.write(payload);val crc=java.util.zip.CRC32();crc.update(name);crc.update(payload);out.writeInt(crc.value.toInt())}
                val header=java.nio.ByteBuffer.allocate(13).putInt(50_000).putInt(50_000).put(8).put(2).put(0).put(0).put(0).array();chunk("IHDR",header);chunk("IEND",byteArrayOf())
            }
            if(InstrumentationRegistry.getArguments().getString("antisocialContextMediaModels")=="true") {
                try {request(client,ContextMediaIpc.OCR,ocrInput(),image);fail("Unsafe image must fail before allocating pixels")}catch(expected:IllegalStateException){assertFalse(expected.message.isNullOrBlank())}
            }
            assertTrue(android.os.Process.myPid()>0)
        }finally {bad.delete();image.delete();client.release()}
    }
    @Test fun pdfWithoutOcrHasEmbeddedTextOrExplicitUnavailable(): Unit=runBlocking {
        val file=File(context.cacheDir,"context-pdf-test.pdf");val client=ContextMediaClient(context)
        try {
            val pdf=PdfDocument()
            try {val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,800,1).create());page.canvas.drawText("A private local PDF page.",40f,80f,Paint().apply {textSize=22f});pdf.finishPage(page);file.outputStream().use {pdf.writeTo(it)}}finally {pdf.close()}
            val response=request(client,ContextMediaIpc.PDF,Bundle().apply {putInt("pageIndex",0);putBoolean("ocrEnabled",false)},file)
            val result=gson.fromJson(response.getByteArray("result")!!.toString(Charsets.UTF_8),ContextPdfPageResult::class.java)
            assertEquals(1,result.pageCount);assertFalse(result.ocr)
            if(Build.VERSION.SDK_INT>=35){assertTrue(result.available);assertTrue(result.text.contains("private local PDF"))}else {assertFalse(result.available);assertNotNull(result.reason)}
        }finally {file.delete();client.release()}
    }
}
