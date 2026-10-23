package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Run as its own instrumentation invocation: no Activity or discovery is opened. */
class DiscoveryCacheCleanupTest {
    @Test fun retainedDiscoveryBytesAreCountedAndClearWithoutOpeningDiscoveryWhileSavesSurvive()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=(context as AntiSocialApplication).repo
        repo.awaitReady();repo.cleanup(true);delay(1500)
        val graph=File(context.cacheDir,"adjacent-discovery/cleanup-fixture.json");val participants=File(context.cacheDir,"public-participants.json")
        graph.parentFile!!.mkdirs();graph.writeText(" ".repeat(4096));participants.writeText("[]")
        val post=Post("cleanup-fixture",Platform.X,"https://x.com/cache_fixture/status/7000000000000000001","Workshop","Keep this saved local repair note.")
        val key=repo.library.save(post,emptyList())
        try {
            repo.updateUsage();assertTrue("Discovery files were missing from cache accounting",repo.usage.value.cache>=graph.length()+participants.length())
            repo.cleanup(true)
            assertFalse("Neighborhood cache survived Clear cache",graph.exists())
            assertFalse("Participant cache survived Clear cache",participants.exists())
            assertTrue("Clear cache removed a saved record",repo.dao.bookmarksByKeys(listOf(key)).any {it.key==key})
            assertEquals(post.body,repo.library.copy(key)!!.post.body)
        }finally {repo.library.remove(key);graph.delete();participants.delete()}
    }
}
