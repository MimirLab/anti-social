/* anti social, GPL-3.0-or-later. Disposable native admission/race fixtures. */
package app.antisocial.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class GalleryEvidenceStoreTest {
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo get()=(context.applicationContext as AntiSocialApplication).repo
    private val stamp=System.currentTimeMillis()
    private val suffix=UUID.randomUUID().toString()
    private val text="Six detailed workshop repairs show the connectors and their completed assemblies."
    private val images=(0..5).map {Media("https://gallery.fixture.invalid/$suffix-$it.jpg")}
    private val root=Post("post:850001",Platform.X,"https://x.com/gallery_fixture/status/850001","Workshop",text,publishedAt=stamp,media=images.take(4))
    private val peer=Post("post:GalleryFixture",Platform.INSTAGRAM,"https://www.instagram.com/p/GalleryFixture/","Workshop",text,publishedAt=stamp+1000,media=images)
    private val child=Post("post:850002",Platform.X,"https://x.com/gallery_fixture/status/850002","Workshop","",publishedAt=stamp+2000,media=images.drop(4),parentId=root.id,
        provenance=Provenance(Source(Platform.X,"fxembed-api","https://api.fxtwitter.com"),stamp,"https://api.fxtwitter.com/2/thread/850001"),relationId="850002",sourceParentId="850001",extractionCoverage=ExtractionCoverage(replyParent=ExtractionStatus.PRESENT))
    private val follows=listOf(Follow("gallery-$suffix-x",Target(Platform.X,TargetKind.PROFILE,"gallery_fixture","https://x.com/gallery_fixture"),"Workshop",personId="gallery-$suffix"),
        Follow("gallery-$suffix-ig",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"gallery_fixture","https://www.instagram.com/gallery_fixture/"),"Workshop",personId="gallery-$suffix"))
    private val members get()=mapOf(root.identityKey to setOf(follows[0].id),peer.identityKey to setOf(follows[1].id))
    private val request get()=CrossPosts.galleryContinuationRequests(listOf(PostGroup(listOf(root,peer))),follows,members).single()
    private var protected=emptyList<BookmarkRow>()
    @Before fun prepare():Unit=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        repo.awaitReady();protected=repo.dao.allBookmarks()
        follows.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}
        repo.store(Page(listOf(root)),follows[0].id,true);repo.store(Page(listOf(peer)),follows[1].id,true)
    }
    @After fun remove():Unit=runBlocking {
        if(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")!="true")return@runBlocking
        follows.forEach {repo.dao.removeFollow(it.id)}
        val keys=listOf(root,peer,child).map(repo::key);repo.dao.removePosts(keys)
        keys.forEach {repo.db.openHelper.writableDatabase.execSQL("DELETE FROM seen WHERE `key`=?",arrayOf(it))}
        assertEquals(protected,repo.dao.allBookmarks())
    }
    private fun bank()=GalleryEvidenceStore(repo).apply {image={_,_->}}
    @Test fun onlyTheProvenPublisherChildReceivesFollowMembershipAndTheCheckIsReused():Unit=runBlocking {
        var reads=0;var pictures=0
        val stranger=child.copy(id="post:850003",canonicalUrl="https://x.com/other_fixture/status/850003")
        val bank=bank().apply {read={reads++;Page(listOf(root,stranger,child))};image={_,_->pictures++}}
        bank.enrich(request,automatic=false)
        val actual=repo.gson.fromJson(repo.dao.post(repo.key(child))!!.payload,Post::class.java)
        assertEquals(child,actual);assertEquals(listOf(follows[0].id),repo.dao.postFollowIds(repo.key(child)))
        assertNull(repo.dao.post(repo.key(stranger)));assertEquals(2,pictures)
        bank.enrich(request,automatic=false);assertEquals(1,reads)
        val all=listOf(root,peer,actual);val bindings=members+(child.identityKey to setOf(follows[0].id))
        val groups=CrossPosts.group(all,follows,bindings)
        assertEquals(all.toSet(),groups.single().posts.toSet())
        assertTrue(CrossPosts.galleryContinuationRequests(groups,follows,bindings).isEmpty())
    }
    @Test fun sharedParentAndCaptionCannotAdmitDifferentPictures():Unit=runBlocking {
        val bad=child.copy(media=listOf(Media("https://gallery.fixture.invalid/different.jpg")))
        val bank=bank().apply {read={Page(listOf(root,bad))}}
        bank.enrich(request,automatic=false)
        assertNull(repo.dao.post(repo.key(child)))
    }
    @Test fun unsubscribeWhileTheResponseIsPendingCannotRestoreTheSubscriptionOrChild():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val bank=bank().apply {read={entered.complete(Unit);finish.await();Page(listOf(root,child))}}
        val task=launch {bank.enrich(request,automatic=false)}
        try {
            withTimeout(5_000){entered.await()};repo.dao.removeFollow(follows[0].id);finish.complete(Unit);task.join()
            assertNull(repo.dao.post(repo.key(child)));assertTrue(repo.dao.allFollows().none {it.id==follows[0].id})
            assertEquals(text,repo.gson.fromJson(repo.dao.post(repo.key(root))!!.payload,Post::class.java).body)
        }finally {finish.complete(Unit);task.cancelAndJoin()}
    }
    @Test fun purgeRetiresAnUncooperativeResponseBeforeItCanPublish():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val bank=bank().apply {read={entered.complete(Unit);withContext(NonCancellable){finish.await()};Page(listOf(root,child))}}
        val task=launch {bank.enrich(request,automatic=false)}
        try {
            withTimeout(5_000){entered.await()};bank.clear();finish.complete(Unit)
            withTimeout(5_000){task.join()};assertTrue(task.isCancelled);assertNull(repo.dao.post(repo.key(child)))
        }finally {finish.complete(Unit);task.cancelAndJoin()}
        assertFalse(repo.cardWork.active.value.any {it.label=="Finding gallery continuation"})
    }
}
