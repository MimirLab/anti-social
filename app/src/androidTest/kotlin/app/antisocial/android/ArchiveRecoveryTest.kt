package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Local captures exercise persistence, courtesy and native archive-only presentation. */
@RunWith(AndroidJUnit4::class)
class ArchiveRecoveryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var previousHistoryLookup: suspend (Post)->ArchivedVersion?
    private val targets=mutableListOf<Target>()
    private val media=mutableSetOf<String>()
    private val files=mutableSetOf<File>()
    private val prefix=System.nanoTime().toString().takeLast(12)
    private var sequence=0
    private val capturedAt=1_770_000_000_000L

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();previousHistoryLookup=repo.archiveStore.lookup
        ui.runOnIdle {
            model().following();repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=true
            repo.prefs.tor=false;repo.prefs.searchProvider=SearchProvider.DARWIN;repo.prefs.originalFallback=false
            repo.applyNetworkSettings()
        }
    }
    @After fun restore()=runBlocking {
        ui.runOnIdle {
            model().following();repo.archiveStore.lookup=previousHistoryLookup
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        for(target in targets) {
            val post=Post(target.identity,target.platform,target.canonicalUrl,"","");val key=repo.key(post)
            media+=listOf("archive-"+Repository.hash(key),repo.archiveStore.offlineKey(post),repo.pageKey(target))
            val store=ArchiveRecoveryStore(repo,budgetFile=null)
            media+=listOf(store.cacheKey(target,false),store.cacheKey(target,true))
            repo.dao.removePosts(listOf(key));repo.dao.removeBookmark(key)
            for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }
        for(key in media) {
            repo.dao.media(key)?.let {File(it.path).delete()}
            File(repo.context.cacheDir,"content/$key").delete();File(repo.context.filesDir,"saved/$key").delete()
        }
        repo.dao.removeMedia(media.toList());files.forEach {it.delete()}
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private fun target(): Target {
        val id="91$prefix${++sequence}"
        return repo.routes.recognize("https://x.com/antisocial_recovery_fixture/status/$id")!!.also {targets+=it}
    }
    private fun version(target: Target,assets: List<Media> = emptyList())=ArchivedVersion("Internet Archive",capturedAt,
        "https://web.archive.org/web/20260202024000id_/${target.canonicalUrl}",
        PostSnapshot(body="Recovered public post ${target.identity.substringAfterLast('/')}",author="Archive fixture author",media=assets))
    private fun store(clock: ()->Long=System::currentTimeMillis,budget: File?=null)=ArchiveRecoveryStore(repo,clock,budget)
    private suspend fun recovered(target: Target,version: ArchivedVersion=this.version(target)): Post {
        return requireNotNull(store().apply {lookup={version}}.recover(target)).posts.single()
    }
    private suspend fun saved(post: Post)=repo.dao.bookmarksByKeys(listOf(repo.key(post))).firstOrNull()?.let {repo.gson.fromJson(it.payload,Post::class.java)}
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(350);ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"archive-recovery").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private suspend fun expectFailure(status: HealthStatus,action: suspend ()->Unit): SourceFailure {
        val error=try {action();null}catch(e: SourceFailure){e}
        assertNotNull("Expected a typed failure instead of a confirmed empty archive",error)
        assertEquals(status,requireNotNull(error).status);return error
    }

    @Test fun recoveredPostHasAnArchiveLabelWithoutAClockAndNeverStartsPassiveHistoryChecks() {
        val target=target();val post=runBlocking {recovered(target)}
        val calls=AtomicInteger();repo.archiveStore.lookup={calls.incrementAndGet();error("A recovered copy is not live current content")}
        val pair=version(target).copy(snapshot=PostSnapshot(body="Another old edition"),currentSignature=ArchiveContent.signature(post))
        val strayPair=post.copy(archivedVersion=pair)
        assertNull(repo.archiveVersion(strayPair))
        runBlocking {repo.archiveStore.check(strayPair);assertNull(repo.archiveStore.marry(strayPair).archivedVersion)}
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {PostCard(strayPair,model(),false){}}}}
        ui.onNodeWithTag("Recovered archive label").assertIsDisplayed()
        ui.onNodeWithTag("Recovered archive label").assertTextEquals("Archived 02/26")
        ui.onNodeWithTag("Recovered archive label").assert(hasContentDescription("Archived 02/26 from Internet Archive"))
        ui.onNodeWithTag("Post source ${repo.key(post)}").assertTextEquals("X")
        ui.onNodeWithTag("Archive version toggle").assertDoesNotExist()
        ui.onNodeWithText("Oldest available snapshot").assertDoesNotExist()
        // Longer than the production dwell: neither UI nor store may probe another edition.
        android.os.SystemClock.sleep(8_400);ui.waitForIdle();assertEquals(0,calls.get())
        evidence("recovered-native-post")
        ui.onNodeWithContentDescription("Save post").performClick()
        ui.waitUntil(5_000){runBlocking {saved(post)!=null}}
        val retained=runBlocking {requireNotNull(saved(post))}
        assertEquals(post.recoveredArchive,retained.recoveredArchive);assertNull(retained.archivedVersion)
    }

    @Test fun recoveredOfflineMediaAndBackupSurviveOrdinaryPostAndLookupEviction()=runBlocking {
        val target=target();val asset="https://web.archive.org/web/20260202024000im_/https://recovery-fixture.invalid/$prefix/image.png"
        val key=Repository.hash(asset);media+=key
        val file=File(repo.context.cacheDir,"content/$key").apply {parentFile?.mkdirs();writeBytes(byteArrayOf(1,2,3,4))}
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis()))
        val post=recovered(target,version(target,listOf(Media(asset))))
        assertTrue(RecoveredArchives.valid(post));assertEquals(target.canonicalUrl,post.canonicalUrl)
        repo.store(Page(listOf(post)));repo.saveOffline(post)
        assertEquals("Text and media saved offline",repo.status.value)
        val protected=requireNotNull(repo.savedMedia(asset));assertTrue(protected.isFile)
        assertTrue(requireNotNull(repo.dao.media(key)).saved)
        val lookup=store().cacheKey(target,false)
        repo.dao.media(lookup)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(lookup));repo.dao.removePosts(listOf(repo.key(post)))
        val retained=requireNotNull(saved(post))
        assertEquals(post.body,retained.body);assertEquals(post.recoveredArchive,retained.recoveredArchive)
        assertNull(retained.archivedVersion);assertTrue(RecoveredArchives.valid(retained));assertTrue(protected.isFile)
        val backup=repo.gson.fromJson(String(repo.exportBackup()),Backup::class.java)
        val restored=repo.gson.fromJson(backup.bookmarks.single {it.key==repo.key(post)}.payload,Post::class.java)
        assertEquals(post.recoveredArchive,restored.recoveredArchive);assertEquals(post.media,restored.media)
        assertTrue(RecoveredArchives.valid(restored));assertEquals(target.canonicalUrl,restored.canonicalUrl)
    }

    @Test fun simultaneousReadersShareOneLookupAndOneCancellationDoesNotCancelTheOther()=runBlocking {
        val target=target();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val recovery=store().apply {lookup={calls.incrementAndGet();started.complete(Unit);release.await();version(it)}}
        val first=async(Dispatchers.IO){recovery.recover(target)}
        withTimeout(3_000){started.await()}
        val second=async(Dispatchers.IO){recovery.recover(target)}
        delay(150);first.cancelAndJoin();release.complete(Unit)
        val result=withTimeout(3_000){requireNotNull(second.await())}
        assertEquals(1,calls.get());assertTrue(RecoveredArchives.valid(result.posts.single()))
        val cached=requireNotNull(store().apply {lookup={error("The positive cache must be reused")}}.recover(target))
        assertTrue(cached.provenance!!.cached);assertEquals(result.posts.single().body,cached.posts.single().body)
        val torRecovery=store().apply {lookup={calls.incrementAndGet();version(it)}}
        val before=repo.gateway.currentPolicy()
        try {
            // Injected data only: this models the cache key, never a live Tor connection.
            repo.gateway.configure(NetworkPolicy(tor=true,socksPort=9,ready=true))
            assertFalse(requireNotNull(torRecovery.recover(target)).provenance!!.cached)
            assertTrue(requireNotNull(torRecovery.recover(target)).provenance!!.cached)
            assertEquals(2,calls.get())
        }finally {repo.gateway.configure(before)}
        val abandoned=this@ArchiveRecoveryTest.target();val checking=CompletableDeferred<Unit>()
        recovery.lookup={calls.incrementAndGet();checking.complete(Unit);awaitCancellation()}
        val cancelled=async(Dispatchers.IO){recovery.recover(abandoned)}
        withTimeout(3_000){checking.await()};cancelled.cancelAndJoin()
        expectFailure(HealthStatus.UNAVAILABLE){recovery.recover(abandoned)}
        assertEquals(3,calls.get())
    }

    @Test fun confirmedEmptyAndUnavailableResultsHaveDifferentTypedCachesAndExpirations()=runBlocking {
        var now=System.currentTimeMillis();val calls=AtomicInteger();val empty=target()
        val recovery=store({now}).apply {lookup={calls.incrementAndGet();null}}
        assertNull(recovery.recover(empty));assertNull(recovery.recover(empty));assertEquals(1,calls.get())
        now+=6*60*60*1000L+1;assertNull(recovery.recover(empty));assertEquals(2,calls.get())
        val unavailable=target();recovery.lookup={calls.incrementAndGet();throw SourceFailure(HealthStatus.UNAVAILABLE,"Archive index is unavailable")}
        expectFailure(HealthStatus.UNAVAILABLE){recovery.recover(unavailable)}
        expectFailure(HealthStatus.UNAVAILABLE){recovery.recover(unavailable)};assertEquals(3,calls.get())
        now+=5*60*1000L+1;expectFailure(HealthStatus.UNAVAILABLE){recovery.recover(unavailable)};assertEquals(4,calls.get())
    }

    @Test fun explicitBudgetSurvivesStoreRestartAndUnreadyTorNeverQueriesOrConsumesIt()=runBlocking {
        var now=System.currentTimeMillis();val calls=AtomicInteger()
        val budget=File(repo.context.cacheDir,"recovery-budget-fixture-$prefix.json").also {files+=it}
        val recovery=store({now},budget).apply {lookup={calls.incrementAndGet();null}}
        repeat(30){assertNull(recovery.recover(target()));now+=1000}
        val extra=target();val restarted=store({now},budget).apply {lookup={calls.incrementAndGet();null}}
        expectFailure(HealthStatus.RATE_LIMITED){restarted.recover(extra)};assertEquals(30,calls.get())
        now+=60*60*1000L+1;assertNull(restarted.recover(extra));assertEquals(31,calls.get())
        val untouched=File(repo.context.cacheDir,"recovery-unready-fixture-$prefix.json").also {files+=it}
        val blocked=store(budget=untouched).apply {lookup={calls.incrementAndGet();error("Unready Tor must not make an archive request")}}
        val before=repo.gateway.currentPolicy()
        try {
            repo.gateway.configure(NetworkPolicy(tor=true))
            expectFailure(HealthStatus.UNAVAILABLE){blocked.recover(target())}
            assertEquals(31,calls.get());assertFalse(untouched.exists())
        }finally {repo.gateway.configure(before)}
    }

    @Test fun forgedRecoveryMetadataAndLiveAssetsAreRejectedBeforeSaving()=runBlocking {
        val target=target();val post=recovered(target);assertTrue(RecoveredArchives.valid(post))
        val recovery=requireNotNull(post.recoveredArchive)
        val independentAsset="https://web.archive.org/web/20260203024000im_/https://recovery-fixture.invalid/$prefix/independent.png"
        assertTrue("An asset's own capture may differ from its post's capture",RecoveredArchives.valid(post.copy(media=listOf(Media(independentAsset)))))
        val forged=listOf(
            post.copy(media=listOf(Media("https://pbs.twimg.com/media/live.png"))),
            post.copy(media=listOf(Media(independentAsset.replace("web.archive.org/web/","arquivo.pt/noFrame/replay/")))),
            post.copy(media=listOf(Media(independentAsset.replace("20260203024000","20990203024000")))),
            post.copy(recoveredArchive=recovery.copy(provider="Arquivo.pt")),
            post.copy(recoveredArchive=recovery.copy(capturedAt=capturedAt+1000)),
            post.copy(recoveredArchive=recovery.copy(archiveUrl=recovery.archiveUrl.replace(target.canonicalUrl,"https://x.com/another/status/123456"))),
        )
        for(value in forged) {
            assertFalse(RecoveredArchives.valid(value))
            expectFailure(HealthStatus.UNAVAILABLE){repo.bookmark(value,true)}
            assertNull(saved(value))
        }
        assertNull(repo.archiveVersion(post))
    }

    @Test fun archiveRateLimitPausesOtherLinksAndSurvivesStoreRestart()=runBlocking {
        var now=System.currentTimeMillis();val calls=AtomicInteger()
        val budget=File(repo.context.cacheDir,"recovery-rate-fixture-$prefix.json").also {files+=it}
        val recovery=store({now},budget).apply {lookup={calls.incrementAndGet();throw SourceFailure(HealthStatus.RATE_LIMITED,"Archive requested a pause")}}
        expectFailure(HealthStatus.RATE_LIMITED){recovery.recover(target())}
        val next=target();val restarted=store({now},budget).apply {lookup={calls.incrementAndGet();null}}
        expectFailure(HealthStatus.RATE_LIMITED){restarted.recover(next)};assertEquals(1,calls.get())
        now+=CachePolicy.DAY+1
        assertNull(restarted.recover(next));assertEquals(2,calls.get())
    }
}
