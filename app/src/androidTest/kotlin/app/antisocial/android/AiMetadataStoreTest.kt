/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/** Observed cache bytes only. No host, platform account or inspection service is involved. */
@RunWith(AndroidJUnit4::class)
class AiMetadataStoreTest {
    private fun fixture(name: String)=InstrumentationRegistry.getInstrumentation().context.assets.open("provenance/$name").use {it.readBytes()}
    private fun directory()=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"ai-store-test-${System.nanoTime()}").apply {mkdirs()}
    private fun write(dir: File,name: String,bytes: ByteArray)=File(dir,name).apply {writeBytes(bytes)}
    private fun scope()=CoroutineScope(SupervisorJob()+Dispatchers.Default)

    @Test fun cachedC2paBytesHaveRealLocalValidationAndAnExactKnownCopyGap()=runBlocking {
        assumeTrue(Build.VERSION.SDK_INT>=28)
        val dir=directory();val scope=scope()
        try {
            val store=AiMetadataStore(scope)
            val signed=write(dir,"signed.png",fixture("generated.png"))
            val plain=write(dir,"plain.png",fixture("plain.png"))
            val before=requireNotNull(store.inspect("https://fixture.invalid/plain",plain))
            assertTrue(before.disclosures.isEmpty());assertTrue(before.removals.isEmpty())
            val report=requireNotNull(store.inspect("https://fixture.invalid/signed",signed))
            assertEquals(AiCredentialState.VALID,report.credentialState)
            assertEquals(AiSignerTrust.UNTRUSTED,report.trust)
            assertEquals(AiUse.GENERATED,report.disclosures.single().use)
            assertTrue(report.removals.isEmpty())
            val gap=requireNotNull(store.reports.value["https://fixture.invalid/plain"])
            assertTrue("A gap is not an AI declaration about the stripped copy",gap.disclosures.isEmpty())
            assertEquals(AiCredentialState.NOT_PRESENT,gap.credentialState)
            assertEquals(listOf(MetadataRemoval(MetadataRemovalEvidence.SAME_IMAGE_COPY,listOf("content_credentials"))),gap.removals)
            store.clear();assertTrue(store.reports.value.isEmpty())
            assertTrue("Clearing observations must preserve media files",signed.isFile&&plain.isFile)
        }finally {scope.cancel();dir.deleteRecursively()}
    }

    @Test fun exactEncodedMetadataDifferencesDoNotBecomePixelSimilarityGuessesOrAnAbsenceVerdict()=runBlocking {
        val dir=directory();val scope=scope()
        try {
            val store=AiMetadataStore(scope,inspectCredentials={_,_->null})
            val ordinary=fixture("plain.png")
            val plain=write(dir,"plain.png",ordinary)
            val comment=write(dir,"comment.png",insertPngText(ordinary,"Source attribution"))
            val changed=write(dir,"changed.png",fixture("tampered.png"))
            val first=requireNotNull(store.inspect("plain",plain))
            assertTrue(first.removals.isEmpty());assertTrue(first.disclosures.isEmpty())
            store.inspect("comment",comment)
            val missing=requireNotNull(store.reports.value["plain"])
            assertEquals(listOf("comments"),missing.removals.single().fields)
            assertTrue(missing.disclosures.isEmpty())
            // One changed pixel remains a different image, even with the same scene, dimensions,
            // and a C2PA-bearing source. The native validator is intentionally absent in this test.
            store.inspect("changed",changed)
            assertTrue(requireNotNull(store.reports.value["changed"]).removals.isEmpty())
            assertEquals(listOf("comments"),requireNotNull(store.reports.value["plain"]).removals.single().fields)
        }finally {scope.cancel();dir.deleteRecursively()}
    }

    @Test fun replacingCachedBytesRevalidatesCredentialsInsteadOfKeepingTheOldIntegrityResult()=runBlocking {
        assumeTrue(Build.VERSION.SDK_INT>=28)
        val dir=directory();val scope=scope()
        try {
            val store=AiMetadataStore(scope)
            val file=write(dir,"cache-entry.png",fixture("generated.png"))
            val valid=requireNotNull(store.inspect("same-url",file))
            assertEquals(AiCredentialState.VALID,valid.credentialState)
            file.writeBytes(fixture("tampered.png"))
            val changed=requireNotNull(store.inspect("same-url",file))
            assertEquals(AiCredentialState.INVALID,changed.credentialState)
            assertNotEquals(valid.sha256,changed.sha256)
            assertNotEquals(AiSignerTrust.TRUSTED,changed.trust)
            assertEquals(setOf("same-url"),store.reports.value.keys)
        }finally {scope.cancel();dir.deleteRecursively()}
    }

    @Test fun boundedObservationsExpireAndDerivedGapsDisappearWithTheirEvidence()=runBlocking {
        val dir=directory();val scope=scope();var now=1000L
        try {
            val store=AiMetadataStore(scope,inspectCredentials={_,_->null},maxEntries=2,ttlMs=100,clock={now})
            val plain=write(dir,"plain.png",fixture("plain.png"))
            val comment=write(dir,"comment.png",insertPngText(fixture("plain.png"),"Attribution"))
            val other=write(dir,"other.png",fixture("tampered.png"))
            store.inspect("comment",comment);now=1010;store.inspect("plain",plain)
            assertTrue(requireNotNull(store.reports.value["plain"]).removals.isNotEmpty())
            now=1020;store.inspect("other",other)
            assertEquals(setOf("plain","other"),store.reports.value.keys)
            assertTrue("Evicting the only metadata-bearing sibling removes the derived gap",requireNotNull(store.reports.value["plain"]).removals.isEmpty())
            now=1110;store.cleanup()
            assertEquals(setOf("other"),store.reports.value.keys)
            now=1120;store.cleanup();assertTrue(store.reports.value.isEmpty())
            assertTrue(plain.isFile&&comment.isFile&&other.isFile)
        }finally {scope.cancel();dir.deleteRecursively()}
    }

    @Test fun clearingObservationsAlsoInvalidatesAlreadyQueuedBackgroundWork()=runBlocking {
        val dir=directory();val scope=scope()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            var count=0
            val store=AiMetadataStore(scope,inspectCredentials={_,_->
                if(++count==1) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                null
            })
            val first=write(dir,"first.png",fixture("plain.png"));val second=write(dir,"second.png",fixture("generated.png"))
            store.observe("first",first)
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            store.observe("queued",second)
            // clear() queues for the held mutex before the worker can start the second item.
            val clearing=async(start=CoroutineStart.UNDISPATCHED){store.clear()}
            release.countDown();clearing.await()
            val resurrected=withTimeoutOrNull(500){store.reports.first {it.isNotEmpty()}}
            assertNull("Queued pre-clear observations must not reappear after clearing",resurrected)
            assertTrue(store.reports.value.isEmpty())
        }finally {release.countDown();scope.cancel();dir.deleteRecursively()}
    }

    @Test fun credentialReaderReceivesContainerMimeForGifHeifAndQuicktime()=runBlocking {
        val dir=directory();val scope=scope();val seen=mutableListOf<String>()
        try {
            val store=AiMetadataStore(scope,inspectCredentials={_,mime->seen+=mime;null})
            fun bmff(brand: String)=byteArrayOf(0,0,0,20)+"ftyp".toByteArray(Charsets.US_ASCII)+brand.toByteArray(Charsets.US_ASCII)+ByteArray(8)
            val cases=listOf(
                "gif87" to ("GIF87a".toByteArray() to "image/gif"),
                "gif89" to ("GIF89a".toByteArray() to "image/gif"),
                "heic" to (bmff("heic") to "image/heic"),
                "heif" to (bmff("mif1") to "image/heif"),
                "avif" to (bmff("avif") to "image/avif"),
                "quicktime" to (bmff("qt  ") to "video/quicktime"),
                "mp4" to (bmff("isom") to "video/mp4"),
            )
            cases.forEach {(name,case)->
                val before=seen.size
                val value=store.inspect(name,write(dir,name,case.first))!!
                assertEquals(case.second,seen.last());assertEquals(before+1,seen.size)
                assertTrue(value.disclosures.isEmpty());assertFalse(value.limitations.contains(AiMetadataLimit.UNSUPPORTED_FORMAT))
            }
            // An arbitrary RIFF container must not be passed to an audio reader as WAV.
            val unknown=store.inspect("unknown",write(dir,"unknown","RIFF1234NOTWAVE".toByteArray()))!!
            assertEquals(cases.size,seen.size)
            assertEquals(listOf(AiMetadataLimit.UNSUPPORTED_FORMAT),unknown.limitations)
            assertTrue(unknown.inspectedAt>0);assertEquals(64,unknown.sha256!!.length)
        }finally {scope.cancel();dir.deleteRecursively()}
    }

    @Test fun oversizedAvailableFileRecordsIncompleteInspectionWithoutInventingAnAiDeclaration()=runBlocking {
        val dir=directory();val scope=scope();var called=false
        try {
            val store=AiMetadataStore(scope,inspectCredentials={_,_->called=true;null})
            val file=File(dir,"oversized").apply {java.io.RandomAccessFile(this,"rw").use {it.setLength(EmbeddedAiMetadata.MAX_BYTES+1L)}}
            val report=store.inspect("oversized",file)!!
            assertFalse(called);assertTrue(report.inspectedAt>0)
            assertEquals(listOf(AiMetadataLimit.SIZE_LIMIT),report.limitations)
            assertTrue(report.disclosures.isEmpty());assertTrue(report.removals.isEmpty());assertNull(report.sha256)
        }finally {scope.cancel();dir.deleteRecursively()}
    }

    private fun insertPngText(source: ByteArray,text: String): ByteArray=ByteArrayOutputStream().use {buffer->
        DataOutputStream(buffer).use {out->
            out.write(source,0,33)
            val tag="tEXt".toByteArray(Charsets.US_ASCII);val data="Comment\u0000$text".toByteArray()
            out.writeInt(data.size);out.write(tag);out.write(data)
            out.writeInt(CRC32().apply {update(tag);update(data)}.value.toInt())
            out.write(source,33,source.size-33)
        };buffer.toByteArray()
    }
}
