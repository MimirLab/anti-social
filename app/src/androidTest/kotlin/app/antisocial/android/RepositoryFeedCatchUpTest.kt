/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
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
}
