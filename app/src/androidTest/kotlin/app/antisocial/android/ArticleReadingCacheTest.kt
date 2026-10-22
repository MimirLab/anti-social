package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArticleReadingCacheTest {
    @Test fun ordinaryCopiesRespectQuotaAndExpiry() {
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"article-cache-fixture-${System.nanoTime()}").apply {mkdirs()}
        try {
            val now=System.currentTimeMillis();val cache=ArticleReadingCache(directory,{12L},{now})
            val oldest=cache.file("old").apply {writeText("older");setLastModified(now-25*60*60_000L)}
            val a=cache.file("a").apply {writeText("aaaaaa");setLastModified(now-2000)}
            val b=cache.file("b").apply {writeText("bbbbbb");setLastModified(now-1000)}
            val c=cache.file("c").apply {writeText("cccccc");setLastModified(now)}
            cache.cleanup();assertFalse(oldest.exists());assertFalse(a.exists());assertTrue(b.exists());assertTrue(c.exists());assertEquals(12L,cache.bytes())
        }finally {directory.deleteRecursively()}
    }
    @Test fun clearingCacheProtectsAnOpenDocumentUntilItsLastReaderCloses() {
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"article-lease-fixture-${System.nanoTime()}").apply {mkdirs()}
        try {
            val cache=ArticleReadingCache(directory,{1024L});val first=cache.lease("document");val second=cache.lease("document")
            val file=cache.file("document").apply {writeText("Open document bytes")}
            cache.cleanup(clear=true);assertTrue(file.exists());first.close();assertTrue(file.exists());second.close();assertFalse(file.exists())
            first.close();assertEquals(0L,cache.bytes())
        }finally {directory.deleteRecursively()}
    }
}
