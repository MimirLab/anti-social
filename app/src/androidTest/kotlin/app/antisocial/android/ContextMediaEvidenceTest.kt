package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Source-edition fixtures use no network, model download or native inference. */
@RunWith(AndroidJUnit4::class)
class ContextMediaEvidenceTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val repo get()=(context as AntiSocialApplication).repo
    private val store get()=repo.contextReader.store
    private val owners=ArrayList<String>()
    private lateinit var originalMedia:ContextMediaState
    @Before fun isolate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()};originalMedia=repo.mediaTools.state.value
        repo.mediaTools.state.value=ContextMediaState(speechEnabled=true,languages=listOf("eng"))
    }
    @After fun cleanup() {
        if(!::originalMedia.isInitialized)return
        owners.forEach {store.delete(it);File(context.cacheDir,"context-work/$it").deleteRecursively()}
        repo.mediaTools.state.value=originalMedia
    }
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
    private fun fixture(media:List<Media> = emptyList()):ContextRecord {
        val id=System.nanoTime().toString();val post=Post(id,Platform.X,"https://x.com/context_fixture/status/$id","Workshop","Public media details.")
        return store.resolve(ContextSnapshot(listOf(post),post.body,"","edition-$id",media=media)).copy(cycleStartedAt=100L).also {store.save(it);owners+=it.id}
    }
    private fun checkpoint(owner:String,stream:ContextStream,result:ContextMediaEvidenceResult)=stream.copy(work=result.work,limited=result.limited).also {store.ingest(owner,result.documents,it)}
    @Test fun unchangedAudioIsValidatedOncePerCycleAndNeverRetranscribed():Unit=runBlocking {
        val record=fixture(listOf(Media("https://audio.example.invalid/recording.m4a",type="audio")));val evidence=ContextMediaEvidence(repo)
        var edition="First audio edition";var downloads=0;var recognitions=0
        evidence.downloadFile={_,_,file,_->downloads++;file.writeText(edition)};evidence.sourceHash={hash(it)}
        evidence.readAudio={file,offset,_->recognitions++;val next=if(offset==0L)2000L else 4000L
            TranscriptResult("Useful media detail.",listOf(TranscriptSegment(offset,next,"Useful media detail.",.9f,.01f,false)),"en",next-offset,true,false,
                sourceSha256=hash(file),startOffsetMs=offset,nextOffsetMs=next,complete=next==4000L,sourceDurationMs=4000L)
        }
        var stream=ContextStream("media",record.snapshot.roots.first(),"media")
        var result=evidence.read(record,stream,false);assertTrue(result.more);stream=checkpoint(record.id,stream,result)
        result=evidence.read(record,stream,false);assertFalse(result.more);checkpoint(record.id,stream,result)
        assertEquals(1,downloads);assertEquals(2,recognitions)
        val refreshed=record.copy(cycleStartedAt=200L);store.save(refreshed);stream=ContextStream("media",record.snapshot.roots.first(),"media")
        result=evidence.read(refreshed,stream,false);assertTrue(result.more);stream=checkpoint(record.id,stream,result)
        result=evidence.read(refreshed,stream,false);assertFalse(result.more);checkpoint(record.id,stream,result)
        assertEquals("One source validation per cycle",2,downloads);assertEquals("Unchanged SHA must reuse both speech receipts",2,recognitions)
        edition="A different, shorter audio edition";store.save(refreshed.copy(cycleStartedAt=300L));stream=ContextStream("media",record.snapshot.roots.first(),"media")
        result=evidence.read(refreshed,stream,false);assertEquals("0",result.work["offset"]);assertTrue(result.more);assertTrue(store.documents(record.id).isEmpty());assertEquals(2,recognitions)
    }
    @Test fun imagesRefreshDescriptionsAndChangedBytesWhilePreservingSavedCopyAndBlankReceipts():Unit=runBlocking {
        repo.mediaTools.state.value=repo.mediaTools.state.value.copy(ocrEnabled=true)
        var item=Media("https://images.example.invalid/photo.jpg",description="Original source description.")
        var record=fixture(listOf(item));val evidence=ContextMediaEvidence(repo)
        val saved=File(context.cacheDir,"context-work/${record.id}/saved-image-fixture").apply {parentFile!!.mkdirs();writeText("original image bytes")}
        var remote="original image bytes";var reads=0;var downloads=0;var unavailable=false
        evidence.availableImage={saved};evidence.loadImage={_,_->error("First recognition should reuse already available bytes")};evidence.sourceHash={hash(it)}
        evidence.downloadFile={_,_,file,_->downloads++;if(unavailable)throw java.io.IOException("Fixture source unavailable");file.writeText(remote)}
        evidence.readImage={file,language->reads++;OcrResult(if(file.readText()=="blank image bytes")""else "Text from "+file.readText(),emptyList(),language,640,480,98f,false,sourceSha256=hash(file))}
        var stream=ContextStream("media",record.snapshot.roots.first(),"media")
        var result=evidence.read(record,stream,false);checkpoint(record.id,stream,result);assertEquals(1,reads);assertEquals(0,downloads)
        val old=result.documents.single {it.id.startsWith("ocr:")}
        item=item.copy(description="Updated source description.");record=record.copy(snapshot=record.snapshot.copy(media=listOf(item)),cycleStartedAt=200L);store.save(record)
        result=evidence.read(record,stream,false);checkpoint(record.id,stream,result);assertEquals(1,reads);assertEquals(1,downloads)
        assertEquals("Updated source description.",store.documents(record.id).single {it.document.id.startsWith("description:")}.document.text)
        unavailable=true;record=record.copy(cycleStartedAt=300L);store.save(record)
        result=evidence.read(record,stream,false)
        assertTrue("Unavailable refresh must preserve limited coverage",result.limited)
        assertTrue("The retained OCR must be identified as earlier text: ${result.message}",result.message.contains("earlier local text kept"))
        assertEquals("Old OCR retrieval time must not become fresh",old.fetchedAt,result.documents.single {it.id.startsWith("ocr:")}.fetchedAt)
        unavailable=false;remote="replacement image bytes";record=record.copy(cycleStartedAt=400L);store.save(record)
        result=evidence.read(record,stream,false);checkpoint(record.id,stream,result);assertEquals(2,reads)
        assertEquals("Text from replacement image bytes",store.documents(record.id).single {it.document.id.startsWith("ocr:")}.document.text)
        assertEquals("The explicit saved copy must stay untouched","original image bytes",saved.readText())
        remote="blank image bytes";item=item.copy(description="");record=record.copy(snapshot=record.snapshot.copy(media=listOf(item)),cycleStartedAt=500L);store.save(record)
        result=evidence.read(record,stream,false);checkpoint(record.id,stream,result);assertEquals(3,reads)
        assertTrue(store.documents(record.id).single {it.document.id.startsWith("description:")}.document.text.isEmpty())
        record=record.copy(cycleStartedAt=600L);store.save(record);result=evidence.read(record,stream,false)
        assertEquals("Blank image recognition is a reusable receipt",3,reads);assertTrue(result.documents.single {it.id.startsWith("ocr:")}.text.isBlank())
    }
    @Test fun shorterPdfResetsBeforeReadingOldPageAndUnchangedPagesReuseReceipts():Unit=runBlocking {
        val record=fixture();val url="https://documents.example.invalid/manual.pdf";val resource="pdf:$url";val evidence=ContextMediaEvidence(repo)
        val old=ContextDocument("pdf:${Repository.hash(url)}:2","old","An old third page.",metadata=mapOf("resource" to resource,"sourceSha256" to "old-sha"))
        store.ingest(record.id,listOf(old));var downloads=0;var renders=0
        evidence.downloadFile={_,_,file,_->downloads++;file.writeText("A replacement PDF with two pages")};evidence.sourceHash={hash(it)}
        evidence.readPdf={file,page,_,_->renders++;assertEquals("A removed old page must never reach the renderer",0,page);ContextPdfPageResult(0,2,"Updated first page.",false,false,sourceSha256=hash(file))}
        var stream=ContextStream("pdf",record.snapshot.roots.first(),"link",work=mapOf("page" to "2","pdfSha" to "old-sha","pdfCycle" to "90"))
        var result=evidence.pdf(record.id,url,stream,false);assertEquals(0,renders);assertEquals("0",result.work["page"]);assertTrue(result.more);assertTrue(store.documents(record.id).isEmpty())
        stream=checkpoint(record.id,stream,result);result=evidence.pdf(record.id,url,stream,false);checkpoint(record.id,stream,result)
        assertEquals(1,renders);assertEquals(1,downloads)
        store.save(record.copy(cycleStartedAt=200L));stream=ContextStream("pdf",record.snapshot.roots.first(),"link")
        result=evidence.pdf(record.id,url,stream,false);assertTrue(result.documents.single().text.contains("Updated"));assertEquals(1,renders);assertEquals(2,downloads)
    }
}
