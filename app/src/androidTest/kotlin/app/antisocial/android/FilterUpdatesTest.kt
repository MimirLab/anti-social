/* AntiSocial, GPL-3.0-or-later. No live lists, network or model calls in these fixtures. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FilterUpdatesTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val repo get()=(context as AntiSocialApplication).repo
    private fun isolated(){assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")}
    @Test fun filterCleanupRetainsOnlyOwnedWorkingProtectionAndEnabledSources() {
        isolated();val root=File(context.cacheDir,"filter-cleanup-fixture").apply {deleteRecursively();mkdirs()}
        try {
            val dir=File(root,"filters").apply {mkdirs()};val active="enabled-source";val disabled="disabled-source"
            val retained=listOf("current.bin","current.json",Repository.hash(active)+".txt")
            val obsolete=listOf(Repository.hash(disabled)+".txt","current.bin.part","abandoned.txt.part","retired-source.txt")
            (retained+obsolete).forEach {File(dir,it).writeText(it)}
            val saved=File(root,"saved-post").apply {writeText("protected saved material")}
            val model=File(root,"downloaded-model").apply {writeText("explicit download")}
            pruneFilterFiles(dir,listOf(active))
            assertEquals(retained.toSet(),dir.listFiles().orEmpty().map {it.name}.toSet())
            retained.forEach {assertEquals(it,File(dir,it).readText())}
            assertEquals("protected saved material",saved.readText());assertEquals("explicit download",model.readText())
        }finally {root.deleteRecursively()}
    }
    @Test fun atomicWritesReplaceSuccessAndRemoveFailedTemporaryCopies() {
        isolated();val root=File(context.cacheDir,"atomic-cleanup-fixture").apply {deleteRecursively();mkdirs()}
        try {
            val file=File(root,"record").apply {writeText("previous")}
            Repository.atomic(file,"replacement".toByteArray())
            assertEquals("replacement",file.readText());assertFalse(File(root,"record.part").exists())
            val target=File(root,"directory-target").apply {mkdirs()}
            val retained=File(target,"saved").apply {writeText("keep")}
            try {Repository.atomic(target,"cannot replace a nonempty directory".toByteArray());fail("Commit should fail")}
            catch(_:IllegalStateException) { /* The original destination remains intact. */ }
            assertEquals("keep",retained.readText());assertFalse(File(root,"directory-target.part").exists())
        }finally {root.deleteRecursively()}
    }
    @Test fun aFreshAutomaticCheckDoesNoDownloadOrProtectionReplacement():Unit=runBlocking {
        isolated();repo.awaitReady();val prefs=context.getSharedPreferences("filter-update-state",Context.MODE_PRIVATE)
        val before=prefs.all.toMap();val download=repo.filterDownload;val rules=repo.filters.value.rules;var calls=0
        try {
            repo.filterUpdates.attempted();repo.filterDownload={calls++;error("Fresh automatic protection must not fetch")}
            assertTrue(repo.refreshFilters(FilterUpdateReason.AUTOMATIC));assertEquals(0,calls)
            assertEquals(rules,repo.filters.value.rules);assertFalse(repo.filters.value.updating)
        }finally {
            repo.filterDownload=download;val edit=prefs.edit().clear()
            before.forEach {(key,value)->if(value is Long)edit.putLong(key,value)else error("Unexpected clock preference")};edit.commit()
        }
    }
    @Test fun theDailyClockSurvivesReopeningAndRecoversFromClockRollback() {
        isolated();val name="filter-clock-fixture";val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);prefs.edit().clear().commit()
        var now=2*FilterUpdateState.DAY
        try {
            val state=FilterUpdateState(context,name){now};assertEquals(0L,state.remaining());state.attempted()
            now+=FilterUpdateState.DAY-1;assertEquals(1L,FilterUpdateState(context,name){now}.remaining())
            now++;assertEquals(0L,state.remaining());now=1;assertEquals(0L,state.remaining())
        }finally {prefs.edit().clear().commit();context.deleteSharedPreferences(name)}
    }
    @Test fun aCancelledRefreshKeepsWorkingProtectionAndClearsItsBusyState():Unit=runBlocking {
        isolated();repo.awaitReady();val download=repo.filterDownload;val before=repo.filters.value;val checked=repo.filterUpdates.lastAttempt
        val entered=CompletableDeferred<Unit>()
        repo.filterDownload={entered.complete(Unit);awaitCancellation()}
        val job=launch {repo.refreshFilters()}
        try {
            withTimeout(10_000){entered.await()};assertTrue(repo.filters.value.updating)
            job.cancelAndJoin()
            assertTrue(repo.filters.value.ready);assertFalse(repo.filters.value.updating);assertEquals(before.rules,repo.filters.value.rules)
            assertEquals(checked,repo.filterUpdates.lastAttempt)
        }finally {job.cancelAndJoin();repo.filterDownload=download}
    }
}
