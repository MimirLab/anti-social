package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class FeedRefreshStageTest {
    private fun page(text:String="Useful source data")=Page(listOf(Post("123",Platform.X,"https://x.com/fixture/status/123","Workshop",text)))
    private fun root():File=Files.createTempDirectory("feed-stage-").toFile()
    @Test fun separateSourcesPreserveOriginalsAndAreRemovedAfterPublication() {
        val root=root()
        try {FeedRefreshStage(root).use {stage->
            val source=page();val first=stage.write("one",source,null);val second=stage.write("two",page("Other exact material"),"Continuation is unavailable")
            assertEquals(source,stage.read(first));assertEquals("Continuation is unavailable",second.pendingReason)
            assertEquals(listOf("one","two"),stage.entries().map {it.sourceId});assertTrue(stage.bytes>0)
            FeedRefreshStage.cleanup(root);assertEquals(source,stage.read(first))
        };assertTrue(root.listFiles().orEmpty().isEmpty())}finally {root.deleteRecursively()}
    }
    @Test fun aggregateQuotaAndPerSourceQuotaDoNotLeavePartialFiles() {
        val root=root()
        try {FeedRefreshStage(root,maxBytes=1200,maxEntryBytes=1200).use {stage->
            val first=stage.write("one",page("a".repeat(300)),null)
            assertThrows(IOException::class.java){stage.write("two",page("b".repeat(600)),null)}
            assertFalse(stage.acceptsMore);assertEquals(1,stage.entries().size);assertEquals(page("a".repeat(300)),stage.read(first))
            assertEquals(1,root.listFiles()!!.single().listFiles()!!.size)
        }}finally {root.deleteRecursively()}
    }
    @Test fun sourceOverflowAndCancellationReleaseTheirReservations() {
        val root=root()
        try {FeedRefreshStage(root,maxBytes=4096,maxEntryBytes=900).use {stage->
            assertThrows(IOException::class.java){stage.write("too-large",page("a".repeat(1800)),null)}
            assertEquals(0L,stage.bytes);assertTrue(stage.acceptsMore)
            assertThrows(CancellationException::class.java){stage.write("cancelled",page(),null){throw CancellationException("Stopped")}}
            assertEquals(0L,stage.bytes);assertTrue(stage.entries().isEmpty())
            assertEquals(page(),stage.read(stage.write("valid",page(),null)))
        }}finally {root.deleteRecursively()}
    }
    @Test fun aChangedScratchFileCannotBePublishedAndStartupCleanupKeepsUnrelatedFiles() {
        val root=root()
        try {
            File(root,"saved.txt").writeText("Explicitly saved material")
            FeedRefreshStage(root).use {stage->
                val entry=stage.write("one",page(),null);val file=root.listFiles()!!.single {it.isDirectory}.listFiles()!!.single()
                file.writeText(file.readText().replace("Useful","useful"));assertThrows(IOException::class.java){stage.read(entry)}
            }
            val orphan=File(root,"batch-00000000-0000-0000-0000-000000000000").apply {mkdirs()}
            File(orphan,"page-00000000-0000-0000-0000-000000000000.part").writeText("incomplete")
            FeedRefreshStage.cleanup(root);assertFalse(orphan.exists());assertEquals("Explicitly saved material",File(root,"saved.txt").readText())
        }finally {root.deleteRecursively()}
    }
    @Test fun simultaneousSourcesShareOneQuotaAndFailedWritesLeaveNoExtraFiles() {
        val root=root();val threads=Executors.newFixedThreadPool(3);val start=CountDownLatch(1)
        try {FeedRefreshStage(root,maxBytes=1600,maxEntryBytes=1600).use {stage->
            val results=(1..3).map {i->threads.submit<Boolean> {start.await();try {stage.write("source-$i",page("a".repeat(500)),null);true}catch(_:IOException){false}}}
            start.countDown()
            assertEquals(2,results.count {it.get()})
            assertTrue(stage.bytes<=1600)
            assertEquals(2,stage.entries().size)
            val files=root.listFiles()!!.single().listFiles()!!.toList()
            assertEquals(2,files.size);assertEquals(stage.bytes,files.sumOf {it.length()})
            stage.entries().forEach {assertEquals(page("a".repeat(500)),stage.read(it))}
        }}finally {threads.shutdownNow();root.deleteRecursively()}
    }
}
