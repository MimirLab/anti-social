/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Actual Room publication and refresh generations with local, gated source pages. */
@RunWith(AndroidJUnit4::class)
class RepositoryFeedCatchUpTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val repo get()=(context as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(9)
    private val person="repository-catchup-$suffix"
    private val now=System.currentTimeMillis()
    private lateinit var preferences: String
    private lateinit var originalMemory: FeedMemoryStore
    private lateinit var originalLoader: suspend (Follow)->Page
    private lateinit var originalNext: suspend (Follow,Page)->Page
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private val posts=mutableListOf<Post>()
    private lateinit var follow: Follow
    @Before fun prepare()=runBlocking {
        repo.awaitReady();preferences=repo.prefs.export().toString();originalMemory=repo.feedMemory;originalLoader=repo.followLoader;originalNext=repo.followPageLoader
        repo.prefs.intervalMinutes=0;repo.prefs.wifiOnly=false;repo.prefs.archiveChecks=false;scheduleRefresh(context,repo.prefs)
        awaitStage("fixture begins after previous refresh is quiescent"){!repo.feedRefresh.value.running}
        file=File(context.cacheDir,"repository-catchup-$suffix.json");scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(context,scope,file);repo.feedMemory.awaitReady()
        val target=Target(Platform.X,TargetKind.PROFILE,"catch$suffix","https://x.com/catch$suffix")
        follow=Follow("$person:source",target,"Catch-up fixture",personId=person,personName="Catch-up fixture")
        repo.dao.putFollow(FollowRow(follow.id,follow.target.platform.id,repo.gson.toJson(follow)))
    }
    @After fun restore()=runBlocking {
        awaitStage("fixture restores only after its refresh is quiescent"){!repo.feedRefresh.value.running}
        repo.feedMemory.flush();scope.coroutineContext[Job]?.cancelAndJoin()
        repo.feedMemory=originalMemory;repo.followLoader=originalLoader;repo.followPageLoader=originalNext
        repo.dao.removeFollow(follow.id)
        val keys=posts.map(repo::key).distinct();repo.dao.removePosts(keys)
        for(table in listOf("history","seen"))for(key in keys)repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        listOf(file,File(file.path+".bak"),File(file.path+".new")).forEach {it.delete()}
        context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(context,repo.prefs)
    }
    private fun post(index: Int)=Post("92$suffix${1000+index}",Platform.X,"https://x.com/catch$suffix/status/92$suffix${1000+index}","Catch-up fixture","Public catch-up item $index includes its full source text.",publishedAt=now+index*60_000L).also {posts+=it}
    private fun page(values: List<Post>,next: String?=null): Page {
        val source=Source(Platform.X,"native-fixture","https://catch-up-fixture.invalid");val stamp=Provenance(source,System.currentTimeMillis(),source.root)
        return Page(values.map {it.copy(provenance=stamp)},next=next,provenance=stamp)
    }
    private suspend fun awaitStage(stage: String,observed: List<FollowedPostSnapshot> = emptyList(),condition: suspend ()->Boolean) {
        if(withTimeoutOrNull(5_000){while(!condition())delay(10);true}==true)return
        val rows=if(::follow.isInitialized)repo.dao.followedPosts(listOf(follow.id)).first()else emptyList()
        val record=mapOf("stage" to stage,"refresh" to repo.feedRefresh.value,"ledger" to repo.feedMemory.refresh.value,
            "followId" to if(::follow.isInitialized)follow.id else null,"databasePostKeys" to rows.map {it.key},
            "observed" to observed.map {mapOf("generation" to it.generation,"postKeys" to it.posts.map(repo::key),"memberships" to it.memberships)})
        val dir=File(requireNotNull(context.getExternalFilesDir("validation")),"repository-catch-up").apply {mkdirs()}
        File(dir,"failure-$suffix-${stage.hashCode()}.json").writeText(repo.gson.toJson(record))
        throw AssertionError("Timed out after 5000 ms at '$stage': refresh=${repo.feedRefresh.value}, databaseKeys=${rows.map {it.key}}, observedGenerations=${observed.map {it.generation}}")
    }
    @Test fun catchUpReachesTheKnownHeadBeforePublishingItsCompletedGeneration()=runBlocking {
        val known=post(0);val newer=(1..3).map(::post)
        repo.store(page(listOf(known)),follow.id,true)
        val observed=CopyOnWriteArrayList<FollowedPostSnapshot>()
        val collector=launch(Dispatchers.IO){repo.followedSnapshot(personId=person).collect {observed+=it}}
        val reachedNext=CompletableDeferred<Unit>();val release=CompletableDeferred<Page>()
        repo.followLoader={page(newer.takeLast(2),next="real-catch-up-page-2")}
        repo.followPageLoader={account,previous->assertEquals(follow.id,account.id);assertEquals("real-catch-up-page-2",previous.next);reachedNext.complete(Unit);release.await()}
        try {
            awaitStage("initial known head appears in the idle Room snapshot",observed){observed.any {it.posts.any {p->repo.key(p)==repo.key(known)}}}
            val before=repo.feedRefresh.value.generation
            val refresh=async(Dispatchers.IO){repo.refresh(personId=person)}
            awaitStage("real catch-up continuation is requested",observed){reachedNext.isCompleted}
            val active=repo.feedRefresh.value
            assertTrue(active.running);assertTrue(active.generation>before);assertEquals(setOf(follow.id),active.requested)
            assertEquals(FollowRefreshStatus.CHECKING,repo.feedMemory.refresh.value[follow.id]?.status)
            assertTrue("No new generation may escape before the source reaches the cached head",observed.none {it.generation==active.generation})
            assertNull("Partial first-window rows must not appear as a complete refresh",repo.dao.post(repo.key(newer.last())))
            release.complete(page(listOf(newer.first(),known)))
            awaitStage("refresh completes after reaching the original head",observed){refresh.isCompleted};refresh.await()
            awaitStage("completed generation publishes all four posts coherently",observed){observed.any {it.generation==active.generation&&it.posts.size==4}}
            val completed=repo.feedRefresh.value;val snapshot=observed.last {it.generation==active.generation}
            assertFalse(completed.running);assertEquals(active.generation,completed.generation);assertTrue(completed.failed.isEmpty())
            assertEquals((newer+known).map(repo::key).toSet(),snapshot.posts.map(repo::key).toSet())
            assertTrue(snapshot.memberships.values.all {it==setOf(follow.id)})
            assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[follow.id]?.status)
        }finally {release.complete(Page(emptyList()));collector.cancelAndJoin()}
    }
    @Test fun aRepeatedCursorPublishesAvailablePostsButKeepsThatGenerationPending()=runBlocking {
        val known=post(0);val newer=listOf(post(1),post(2));repo.store(page(listOf(known)),follow.id,true)
        val earlier=repo.feedMemory.begin(listOf(follow.id));repo.feedMemory.success(follow.id,earlier,now-60_000)
        val before=repo.feedRefresh.value.generation
        repo.followLoader={page(listOf(newer.last()),next="repeated-cursor")}
        repo.followPageLoader={_,previous->assertEquals("repeated-cursor",previous.next);page(listOf(newer.first()),next="repeated-cursor")}
        repo.refresh(personId=person)
        val completed=repo.feedRefresh.value
        assertTrue(completed.generation>before);assertFalse(completed.running)
        assertEquals(setOf(follow.id),completed.requested);assertEquals(setOf(follow.id),completed.failed)
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[follow.id]?.status)
        assertEquals(now-60_000,repo.feedMemory.refresh.value[follow.id]?.lastSuccessAt)
        val snapshot=withTimeout(5_000){repo.followedSnapshot(personId=person).first {it.generation==completed.generation&&it.posts.size==3}}
        assertEquals((newer+known).map(repo::key).toSet(),snapshot.posts.map(repo::key).toSet())
        assertFalse(RefreshLedger.caughtUp(listOf(follow.id),repo.feedMemory.refresh.value))
    }
    @Test fun aSlowLaterPageRetainsCompletedPostsAndRetriesTheOriginalGap()=runBlocking {
        // Exercise the actual production 45-second source deadline, rather than
        // introducing a test-only timeout override into the application.
        repo.prefs.tor=false
        val known=post(0);val newer=listOf(post(1),post(2))
        repo.store(page(listOf(known)),follow.id,true)
        val oldSuccess=now-60_000
        val earlier=repo.feedMemory.begin(listOf(follow.id));repo.feedMemory.success(follow.id,earlier,oldSuccess)
        var cancelled=false
        repo.followLoader={page(listOf(newer.last()),next="completed-second-page")}
        repo.followPageLoader={_,previous->when(previous.next) {
            "completed-second-page"->page(listOf(newer.first()),next="slow-third-page")
            "slow-third-page"->try {awaitCancellation()}finally {cancelled=true}
            else->error("Only follow the actual source cursor")
        }}
        withTimeout(60_000){repo.refresh(personId=person)}
        assertTrue("The unfinished read must be cancelled at the source deadline",cancelled)
        assertTrue("Keep every already completed page",newer.all {repo.dao.post(repo.key(it))!=null})
        assertEquals(setOf(follow.id),repo.feedRefresh.value.failed)
        assertFalse(repo.feedRefresh.value.running);assertFalse(repo.feedRefresh.value.publishing)
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[follow.id]?.status)
        assertEquals(oldSuccess,repo.feedMemory.refresh.value[follow.id]?.lastSuccessAt)
        assertFalse(RefreshLedger.caughtUp(listOf(follow.id),repo.feedMemory.refresh.value))
        assertTrue(File(context.cacheDir,"feed-refresh").listFiles().orEmpty().isEmpty())

        var retried=false
        repo.followLoader={page(listOf(newer.last()),next="retry-original-gap")}
        repo.followPageLoader={_,previous->assertEquals("retry-original-gap",previous.next);retried=true;page(listOf(newer.first(),known))}
        repo.refresh(personId=person)
        assertTrue("The newly retained head must not hide the unfinished gap",retried)
        assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[follow.id]?.status)
        assertTrue(repo.feedRefresh.value.failed.isEmpty())
        val snapshot=withTimeout(5_000){repo.followedSnapshot(personId=person).first {it.posts.size==3}}
        assertEquals((newer+known).map(repo::key).toSet(),snapshot.posts.map(repo::key).toSet())
    }
    @Test fun retryAfterAPartialCatchUpStillReachesTheOriginalHeadAfterMemoryReload()=runBlocking {
        val originalHead=post(0);val newer=listOf(post(1),post(2))
        repo.store(page(listOf(originalHead)),follow.id,true)
        val oldSuccess=now-60_000
        val earlier=repo.feedMemory.begin(listOf(follow.id));repo.feedMemory.success(follow.id,earlier,oldSuccess)
        repo.followLoader={page(listOf(newer.last()),next="first-attempt-repeated")}
        repo.followPageLoader={_,previous->assertEquals("first-attempt-repeated",previous.next);page(listOf(newer.first()),next="first-attempt-repeated")}
        repo.refresh(personId=person)
        val incompleteGeneration=repo.feedRefresh.value.generation
        assertEquals(setOf(follow.id),repo.feedRefresh.value.failed)
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[follow.id]?.status)
        assertEquals(oldSuccess,repo.feedMemory.refresh.value[follow.id]?.lastSuccessAt)
        assertNotNull("The partial newer head is already in Room before the retry",repo.dao.post(repo.key(newer.last())))
        repo.feedMemory.flush();scope.coroutineContext[Job]?.cancelAndJoin()
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(context,scope,file);repo.feedMemory.awaitReady()
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[follow.id]?.status)
        assertEquals(oldSuccess,repo.feedMemory.refresh.value[follow.id]?.lastSuccessAt)
        val requestedNext=CompletableDeferred<Unit>();val release=CompletableDeferred<Page>()
        repo.followLoader={page(listOf(newer.last()),next="retry-original-gap")}
        repo.followPageLoader={account,previous->
            assertEquals(follow.id,account.id);assertEquals("retry-original-gap",previous.next)
            requestedNext.complete(Unit);release.await()
        }
        val retry=async(Dispatchers.IO){repo.refresh(personId=person)}
        try {
            // Seeing the newer, partially stored head on the first page must not
            // make the unresolved original gap disappear after a process restart.
            withTimeout(5_000){requestedNext.await()}
            assertTrue(repo.feedRefresh.value.running)
            assertTrue(repo.feedRefresh.value.generation>incompleteGeneration)
            assertEquals(FollowRefreshStatus.CHECKING,repo.feedMemory.refresh.value[follow.id]?.status)
            assertEquals(oldSuccess,repo.feedMemory.refresh.value[follow.id]?.lastSuccessAt)
            release.complete(page(listOf(newer.first(),originalHead)));retry.await()
            val completed=repo.feedRefresh.value
            assertFalse(completed.running);assertTrue(completed.failed.isEmpty())
            assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[follow.id]?.status)
            assertTrue(RefreshLedger.caughtUp(listOf(follow.id),repo.feedMemory.refresh.value))
            val snapshot=withTimeout(5_000){repo.followedSnapshot(personId=person).first {it.generation==completed.generation&&it.posts.size==3}}
            assertEquals((newer+originalHead).map(repo::key).toSet(),snapshot.posts.map(repo::key).toSet())
        }finally {release.complete(Page(emptyList()));retry.cancelAndJoin()}
    }
    @Test fun cancellingASlowSourceStillPublishesTheOtherCompletedSourceAndRemovesScratch()=runBlocking {
        val known=post(0);val newer=post(1);repo.store(page(listOf(known)),follow.id,true)
        val other=Follow("$person:slow",follow.target.copy(identity="slow$suffix",canonicalUrl="https://x.com/slow$suffix"),"Slow source",personId=person)
        repo.dao.putFollow(FollowRow(other.id,other.target.platform.id,repo.gson.toJson(other)))
        repo.feedMemory.syncFollows(listOf(follow.id,other.id))
        val previous=repo.feedMemory.begin(listOf(other.id));repo.feedMemory.success(other.id,previous,now-60_000)
        val waiting=CompletableDeferred<Unit>()
        repo.followLoader={source->if(source.id==follow.id)page(listOf(newer,known))else {waiting.complete(Unit);awaitCancellation()}}
        val refresh=launch(Dispatchers.IO){repo.refresh(personId=person)}
        try {
            withTimeout(5_000){waiting.await()}
            awaitStage("completed source is staged before cancellation") {
                File(context.cacheDir,"feed-refresh").listFiles().orEmpty().any {dir->dir.listFiles().orEmpty().any {it.extension=="json"}}
            }
            assertNull(repo.dao.post(repo.key(newer)))
            refresh.cancelAndJoin()
            assertNotNull("An interrupted second source cannot discard the completed first one",repo.dao.post(repo.key(newer)))
            assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[follow.id]?.status)
            assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[other.id]?.status)
            assertEquals(now-60_000,repo.feedMemory.refresh.value[other.id]?.lastSuccessAt)
            assertFalse(repo.feedRefresh.value.running);assertFalse(repo.feedRefresh.value.publishing)
            assertTrue(File(context.cacheDir,"feed-refresh").listFiles().orEmpty().isEmpty())
        }finally {refresh.cancelAndJoin();repo.dao.removeFollow(other.id)}
    }
    @Test fun unavailableStagingKeepsThePreviousFeedAndMarksTheCheckPending()=runBlocking {
        val known=post(0);repo.store(page(listOf(known)),follow.id,true)
        val before=repo.feedRefresh.value.generation
        val root=File(context.cacheDir,"feed-refresh")
        assertTrue(root.listFiles().orEmpty().isEmpty());root.delete();root.writeText("A local filesystem obstruction")
        var requested=false;repo.followLoader={requested=true;page(listOf(post(1),known))}
        try {
            assertTrue(runCatching {repo.refresh(personId=person)}.isFailure)
            assertFalse("Do not fetch when the staging area cannot be prepared",requested)
            assertNotNull(repo.dao.post(repo.key(known)))
            val completed=repo.feedRefresh.value
            assertFalse(completed.running);assertFalse(completed.publishing)
            assertTrue("A failed attempt must complete so a manual refresh can be retried",completed.generation>before)
            assertEquals(setOf(follow.id),completed.requested);assertEquals(setOf(follow.id),completed.failed)
            assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[follow.id]?.status)
        }finally {root.delete()}
    }
    @Test fun refreshCalledOnMainDoesItsSourceAndStagingWorkOffMain()=runBlocking {
        val known=post(0);val newer=post(1);repo.store(page(listOf(known)),follow.id,true)
        var sourceWasOnMain=true
        repo.followLoader={
            sourceWasOnMain=Looper.myLooper()==Looper.getMainLooper()
            page(listOf(newer,known))
        }
        withContext(Dispatchers.Main.immediate){repo.refresh(personId=person)}
        assertFalse("Foreground refresh must not inherit the UI dispatcher for source or scratch work",sourceWasOnMain)
        assertNotNull(repo.dao.post(repo.key(newer)))
        assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[follow.id]?.status)
        assertTrue(repo.feedRefresh.value.failed.isEmpty());assertFalse(repo.feedRefresh.value.running)
    }
    @Test fun aFailedAtomicPublicationRollsBackAllNewRowsAndKeepsSavedMaterial()=runBlocking {
        val known=post(0);val newer=post(1);val rejected=post(2)
        repo.store(page(listOf(known)),follow.id,true)
        val bookmark=BookmarkRow(repo.key(known),known.platform.id,repo.gson.toJson(known),now)
        repo.dao.putBookmark(bookmark)
        repo.followLoader={page(listOf(newer,rejected,known))}
        val trigger="reject_refresh_$suffix"
        val rejectedKey=repo.key(rejected).replace("'","''")
        repo.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON posts WHEN NEW.`key`='$rejectedKey' BEGIN SELECT RAISE(ABORT,'fixture publication failure'); END")
        try {
            assertTrue(runCatching {repo.refresh(personId=person)}.isFailure)
            assertNull("Earlier rows in the same batch must also roll back",repo.dao.post(repo.key(newer)))
            assertNull(repo.dao.post(repo.key(rejected)));assertNotNull(repo.dao.post(repo.key(known)))
            assertEquals(bookmark,repo.dao.bookmarksByKeys(listOf(bookmark.key)).single())
            assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[follow.id]?.status)
            assertFalse(repo.feedRefresh.value.running);assertFalse(repo.feedRefresh.value.publishing)
            assertTrue(File(context.cacheDir,"feed-refresh").listFiles().orEmpty().isEmpty())
        }finally {
            repo.db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            repo.dao.removeBookmark(bookmark.key)
        }
    }
}
