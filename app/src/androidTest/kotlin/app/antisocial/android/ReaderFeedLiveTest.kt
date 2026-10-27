/* anti social, GPL-3.0-or-later. Opt-in private acquisition evidence. */
package app.antisocial.android

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the real refresh and source memberships without injecting posts,
 * fingerprints or expected grouping. Public subscriptions must first be made
 * through the app on an explicitly disposable emulator. Cache purge preserves
 * those subscriptions and settings. Captures stay in private device storage. */
@RunWith(AndroidJUnit4::class)
class ReaderFeedLiveTest {
    @Test fun aColdRefreshDeliversAndGroupsActualSubscribedSourceWindows():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialColdFeedLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        require(Build.MODEL.startsWith("sdk_gphone")){"Use a disposable emulator"}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        require(!repo.feedRefresh.value.running)
        val follows=repo.dao.allFollows()
        require(follows.size in 1..40&&repo.dao.allBookmarks().isEmpty())
        val preferences=repo.prefs.export().toString()
        val output=File(context.getExternalFilesDir("validation"),"cold-feed-live").apply {mkdirs()}
        repo.cleanup(clearAll=true)
        assertEquals(follows,repo.dao.allFollows());assertEquals(preferences,repo.prefs.export().toString())
        val start=SystemClock.elapsedRealtime()
        var firstMs:Long?=null;var firstCount=0
        val observer=launch {
            repo.followedSnapshot().filter {it.posts.isNotEmpty()}.first().let {
                firstMs=SystemClock.elapsedRealtime()-start;firstCount=it.posts.size
            }
        }
        try {withTimeout(300_000){repo.refresh()}}finally {
            observer.cancelAndJoin()
            val snapshot=withTimeout(10_000){repo.followedSnapshot().first()}
            val accounts=repo.dao.allFollows().map {repo.gson.fromJson(it.payload,Follow::class.java)}
            val groups=withContext(Dispatchers.Default){CrossPosts.groupCancellable(snapshot.posts,accounts,snapshot.memberships)}
            val result=mapOf("firstPostMs" to firstMs,"firstPostCount" to firstCount,"elapsedMs" to SystemClock.elapsedRealtime()-start,
                "posts" to snapshot.posts,"follows" to accounts,"memberships" to snapshot.memberships,
                "groups" to groups,"refresh" to repo.feedRefresh.value,"ledger" to repo.feedMemory.refresh.value)
            File(output,"result.json").writeText(repo.gson.toJson(result))
            assertEquals(follows,repo.dao.allFollows());assertEquals(preferences,repo.prefs.export().toString())
            assertTrue("Inspect the recorded provider failures when no source delivers",snapshot.posts.isNotEmpty())
            assertNotNull("The actual Room window must deliver before the refresh finishes",firstMs)
            assertEquals(snapshot.posts.map(repo::key).toSet(),groups.flatMap {it.posts}.map(repo::key).toSet())
        }
    }
}
