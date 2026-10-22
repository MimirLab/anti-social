package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Data-only captures exercise Android persistence without making archive or media requests. */
@RunWith(AndroidJUnit4::class)
class ArchiveHistoryStorageTest {
    private val repo get()=(ApplicationProvider.getApplicationContext<Context>() as AntiSocialApplication).repo
    private lateinit var preferences: String
    private val fixtures=mutableListOf<Post>()
    private val fixtureMedia=mutableSetOf<String>()
    private val sequence=AtomicInteger()
    private val prefix=System.nanoTime().toString().takeLast(12)

    @Before fun preserve()=runBlocking {
        repo.awaitReady();preferences=repo.prefs.export().toString()
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=true
    }
    @After fun restore()=runBlocking {
        // These tests must not clear a user's saved posts or unrelated media.
        for(post in fixtures) {
            val key=repo.key(post)
            val mediaKeys=listOf("archive-"+Repository.hash(key),"offline-"+Repository.hash(key))
            for(mediaKey in mediaKeys)repo.dao.media(mediaKey)?.let {File(it.path).delete()}
            repo.dao.removeMedia(mediaKeys);repo.dao.removePosts(listOf(key));repo.dao.removeBookmark(key)
            for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }
        for(key in fixtureMedia) {
            repo.dao.media(key)?.let {File(it.path).delete()}
            File(repo.context.cacheDir,"content/$key").delete();File(repo.context.filesDir,"saved/$key").delete()
            repo.dao.removeMedia(listOf(key))
        }
        repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
        repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(repo.context,repo.prefs)
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }

    private fun current(): Post {
        val id="90$prefix${sequence.incrementAndGet()}"
        return Post(id,Platform.X,"https://x.com/antisocial_archive_storage_fixture/status/$id","Fixture public author","Current post text $id",publishedAt=1_760_000_000_000L).also {fixtures+=it}
    }
    private fun version(post: Post)=ArchivedVersion("Internet Archive",1_770_000_000_000L,
        "https://web.archive.org/web/20260202024000id_/${post.canonicalUrl}",
        PostSnapshot.from(post.copy(body="Earlier archived text ${post.id}")))
    private fun store()=ArchiveHistoryStore(repo,ArchiveCheckPolicy(),budgetFile=null)
    private suspend fun putOrdinary(post: Post) {
        val json=repo.gson.toJson(post)
        repo.dao.putPost(PostRow(repo.key(post),post.platform.id,json,post.publishedAt,System.currentTimeMillis(),bytes=json.toByteArray().size.toLong()))
    }
    private suspend fun saved(post: Post)=repo.dao.bookmarksByKeys(listOf(repo.key(post))).firstOrNull()?.let {repo.gson.fromJson(it.payload,Post::class.java)}
    private fun assertPair(current: Post,record: Post) {
        assertEquals(current.canonicalUrl,record.canonicalUrl);assertEquals(current.body,record.body)
        val paired=requireNotNull(record.archivedVersion)
        assertEquals(version(current).snapshot.body,paired.snapshot.body)
        assertEquals(ArchiveContent.signature(current),paired.currentSignature)
        assertEquals(current.platform,record.platform)
    }

    @Test fun lookupUpdatesOrdinaryAndPreviouslySavedRecordsWithTheSamePair()=runBlocking {
        val post=current();putOrdinary(post);repo.bookmark(post,true)
        val before=requireNotNull(repo.dao.post(repo.key(post)))
        val calls=AtomicInteger();val history=store().apply {lookup={calls.incrementAndGet();this@ArchiveHistoryStorageTest.version(it)}}
        history.check(post)
        assertEquals(1,calls.get())
        val row=requireNotNull(repo.dao.post(repo.key(post)))
        assertEquals(before.createdAt,row.createdAt);assertEquals(before.lastViewedAt,row.lastViewedAt)
        assertEquals(row.payload.toByteArray().size.toLong(),row.bytes)
        assertPair(post,repo.gson.fromJson(row.payload,Post::class.java))
        assertPair(post,requireNotNull(saved(post)))
        assertEquals(requireNotNull(saved(post)).archivedVersion,history.history.value[repo.key(post)])
    }

    @Test fun savedPairSurvivesOrdinaryEvictionAndBackupSerialization()=runBlocking {
        val post=current();putOrdinary(post);repo.bookmark(post,true)
        store().apply {lookup={this@ArchiveHistoryStorageTest.version(it)}}.check(post)
        repo.dao.removePosts(listOf(repo.key(post)))
        val lookupKey="archive-"+Repository.hash(repo.key(post))
        repo.dao.media(lookupKey)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(lookupKey))
        val restored=store().apply {lookup={error("Saved pairs must not need an archive lookup")}}
        val married=restored.marry(post)
        assertPair(post,married)
        restored.check(married)
        val backup=repo.gson.fromJson(String(repo.exportBackup()),Backup::class.java)
        val roundTrip=repo.gson.fromJson(backup.bookmarks.single {it.key==repo.key(post)}.payload,Post::class.java)
        assertPair(post,roundTrip)
        assertEquals(married.archivedVersion,restored.history.value[repo.key(post)])
        assertNull(repo.dao.post(repo.key(post)))
    }

    @Test fun explicitOfflineSaveProtectsBothTextsAndKeepsCurrentAsTheSavedRecord()=runBlocking {
        val post=current();putOrdinary(post)
        val history=store().apply {lookup={this@ArchiveHistoryStorageTest.version(it)}}
        history.check(post)
        val paired=history.marry(post)
        val displayed=requireNotNull(paired.archivedVersion).displayPost(paired)
        assertEquals(version(post).snapshot.body,displayed.body)
        assertNotEquals(post.canonicalUrl,displayed.canonicalUrl)
        // The UI's download action supplies the canonical current record even when its
        // visible body is a replay. Repository resolves its already-married snapshot.
        repo.saveOffline(post)
        assertPair(post,requireNotNull(saved(post)))
        val marker=requireNotNull(repo.dao.media(history.offlineKey(post)))
        assertTrue(marker.saved);assertTrue(File(marker.path).isFile)
        assertEquals(36L,marker.bytes)
        assertEquals(File(marker.path).readText(),java.util.UUID.fromString(File(marker.path).readText()).toString())
        repo.dao.removePosts(listOf(repo.key(post)))
        assertPair(post,store().marry(post))
    }

    @Test fun removingTheSavedRecordWhileLookupRunsDoesNotResurrectIt()=runBlocking {
        val post=current();putOrdinary(post);repo.bookmark(post,true)
        val started=CompletableDeferred<Unit>();val answer=CompletableDeferred<ArchivedVersion?>()
        val history=store().apply {lookup={started.complete(Unit);answer.await()}}
        val pending=async(Dispatchers.IO) {history.check(post)}
        withTimeout(3_000){started.await()}
        // Delete only this fixture's saved row. The lookup's attach transaction must
        // update an existing saved record, never upsert one after an unbookmark.
        repo.dao.removeBookmark(repo.key(post))
        answer.complete(version(post));withTimeout(3_000){pending.await()}
        assertNull(saved(post))
        assertPair(post,repo.gson.fromJson(requireNotNull(repo.dao.post(repo.key(post))).payload,Post::class.java))
    }

    @Test fun disabledChecksAndMatchingSavedPairsNeverPerformALookup()=runBlocking {
        val post=current();val calls=AtomicInteger()
        val history=store().apply {lookup={calls.incrementAndGet();error("Unexpected archive request")}}
        repo.prefs.archiveChecks=false;history.check(post)
        assertEquals(0,calls.get());assertTrue(history.history.value.isEmpty())
        assertNull(repo.dao.media("archive-"+Repository.hash(repo.key(post))))
        repo.prefs.archiveChecks=true
        val paired=post.copy(archivedVersion=version(post).copy(currentSignature=ArchiveContent.signature(post)))
        history.check(paired)
        assertEquals(0,calls.get());assertPair(post,paired)
        assertEquals(paired.archivedVersion,history.history.value[repo.key(post)])
    }

    @Test fun removedOrReplacedOfflineIntentCannotPromoteDelayedCachedMedia()=runBlocking {
        // Delay one cached image at the same per-resource gate used by a pending
        // transfer. No fixture URL is requested and unrelated media is untouched.
        @Suppress("UNCHECKED_CAST")
        val gates=Repository::class.java.getDeclaredField("mediaKeys").apply {isAccessible=true}.get(repo) as KeyedMutex<String>
        for(replaceIntent in listOf(false,true)) {
            val base=current()
            val url="https://archive-storage.invalid/${base.id}/current.png"
            val post=base.copy(media=listOf(Media(url)))
            val key=Repository.hash(url);fixtureMedia+=key
            val ordinary=File(repo.context.cacheDir,"content/$key").apply {parentFile?.mkdirs();writeBytes(byteArrayOf(1,2,3,4))}
            repo.dao.putMedia(MediaRow(key,ordinary.path,ordinary.length(),System.currentTimeMillis()))
            val olderUrl="https://web.archive.org/web/20260202024000id_/https://archive-storage.invalid/${base.id}/older.png"
            val olderKey=Repository.hash(olderUrl);fixtureMedia+=olderKey
            val olderFile=File(repo.context.cacheDir,"content/$olderKey").apply {parentFile?.mkdirs();writeBytes(byteArrayOf(5,6,7,8))}
            repo.dao.putMedia(MediaRow(olderKey,olderFile.path,olderFile.length(),System.currentTimeMillis()))
            val archived=version(post).let {it.copy(snapshot=it.snapshot.copy(media=listOf(Media(olderUrl))),currentSignature=ArchiveContent.signature(post))}
            val paired=post.copy(archivedVersion=archived)
            val held=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val holder=launch(Dispatchers.IO) {gates.withKey(key) {held.complete(Unit);release.await()}}
            var pending: Deferred<Unit>?=null
            var replacementSave: Deferred<Unit>?=null
            try {
                withTimeout(3_000){held.await()}
                val originalSave=async(Dispatchers.IO) {repo.saveOffline(paired)}
                pending=originalSave
                val markerKey=repo.archiveStore.offlineKey(post)
                val marker=withTimeout(3_000) {
                    var row: MediaRow?=null
                    while(row==null) {row=repo.dao.media(markerKey);if(row==null)delay(10)}
                    requireNotNull(row)
                }
                assertTrue(originalSave.isActive)
                val oldToken=File(marker.path).readText()
                repo.bookmark(post,false)
                assertNull(saved(post));assertNull(repo.dao.media(markerKey))
                var replacement: String?=null
                if(replaceIntent) {
                    // Exercise a new public save while the older operation is still
                    // queued on the same media key. Only the new token may commit.
                    replacementSave=async(Dispatchers.IO) {repo.saveOffline(paired)}
                    replacement=withTimeout(3_000) {
                        var token: String?=null
                        while(token==null) {
                            repo.dao.media(markerKey)?.let {row->File(row.path).takeIf {it.isFile}?.readText()?.takeIf {it!=oldToken}?.let {token=it}}
                            if(token==null)delay(10)
                        }
                        requireNotNull(token)
                    }
                }
                release.complete(Unit);withTimeout(3_000){holder.join()}
                withTimeout(3_000){originalSave.join()}
                val failure=runCatching {originalSave.await()}.exceptionOrNull()
                assertTrue("The superseded offline save must cancel before promoting media",failure is CancellationException)
                if(replaceIntent) {
                    withTimeout(3_000){requireNotNull(replacementSave).await()}
                    assertNotEquals(oldToken,replacement)
                    assertEquals(replacement,File(requireNotNull(repo.dao.media(markerKey)).path).readText())
                    assertPair(post,requireNotNull(saved(post)))
                    assertNotNull(repo.savedMedia(url));assertNotNull(repo.savedMedia(olderUrl))
                    assertTrue(requireNotNull(repo.dao.media(key)).saved);assertTrue(requireNotNull(repo.dao.media(olderKey)).saved)
                }else {
                    val retained=requireNotNull(repo.dao.media(key))
                    assertFalse(retained.saved);assertEquals(ordinary.path,retained.path);assertTrue(ordinary.isFile)
                    assertNull(repo.savedMedia(url));assertNull(repo.savedMedia(olderUrl))
                    assertFalse(File(repo.context.filesDir,"saved/$key").exists());assertFalse(File(repo.context.filesDir,"saved/$olderKey").exists())
                    assertNull(saved(post));assertNull(repo.dao.media(markerKey));assertFalse(File(marker.path).exists())
                }
            }finally {
                release.complete(Unit)
                withContext(NonCancellable) {pending?.cancelAndJoin();replacementSave?.cancelAndJoin();holder.cancelAndJoin()}
            }
        }
    }

    @Test fun aLateArchiveAssetCannotAttachToANewerSavedEditionOfTheSamePost()=runBlocking {
        val olderCurrent=current()
        val archivedUrl="https://web.archive.org/web/20260202024000id_/https://archive-storage.invalid/${olderCurrent.id}/stale-edition.png"
        val assetKey=Repository.hash(archivedUrl);fixtureMedia+=assetKey
        val ordinary=File(repo.context.cacheDir,"content/$assetKey").apply {parentFile?.mkdirs();writeBytes(byteArrayOf(9,10,11,12))}
        repo.dao.putMedia(MediaRow(assetKey,ordinary.path,ordinary.length(),System.currentTimeMillis()))
        val olderVersion=version(olderCurrent).let {
            it.copy(snapshot=it.snapshot.copy(media=listOf(Media(archivedUrl))),currentSignature=ArchiveContent.signature(olderCurrent))
        }
        assertNotNull(repo.archiveVersion(olderCurrent.copy(archivedVersion=olderVersion)))
        val newer=olderCurrent.copy(body="A newer saved edition ${olderCurrent.id}",media=emptyList(),archivedVersion=null)
        repo.saveOffline(newer)
        val markerKey=repo.archiveStore.offlineKey(newer)
        val marker=requireNotNull(repo.dao.media(markerKey));val token=File(marker.path).readText()
        assertTrue(marker.saved)
        // This callback belongs to an earlier content signature, despite sharing
        // the newly saved post's canonical identity and a currently valid intent.
        repo.saveArchivedMedia(olderCurrent,olderVersion)
        val retained=requireNotNull(repo.dao.media(assetKey))
        assertFalse(retained.saved);assertEquals(ordinary.path,retained.path)
        assertArrayEquals(byteArrayOf(9,10,11,12),ordinary.readBytes())
        assertNull(repo.savedMedia(archivedUrl));assertFalse(File(repo.context.filesDir,"saved/$assetKey").exists())
        val savedNewer=requireNotNull(saved(newer))
        assertEquals(newer.body,savedNewer.body);assertNull(savedNewer.archivedVersion)
        assertEquals(token,File(requireNotNull(repo.dao.media(markerKey)).path).readText())
    }
}
