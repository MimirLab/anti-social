/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Real reader state and aggregation with deterministic local sources; no remote calls or writes. */
@RunWith(AndroidJUnit4::class)
class DiscussionSchedulingTest {
    private val repo get()=(ApplicationProvider.getApplicationContext<Context>() as AntiSocialApplication).repo
    private val roots=listOf(
        Post("schedule-x",Platform.X,"https://x.com/asscheduling/status/7710001","asscheduling","A public scheduling fixture."),
        Post("schedule-reddit",Platform.REDDIT,"https://www.reddit.com/r/asscheduling/comments/abc123/a_public_post/","asscheduling","The same public scheduling fixture."),
        Post("schedule-bluesky",Platform.BLUESKY,"https://bsky.app/profile/bsky.app/post/3kznscheduled","bsky.app","Another source edition."),
    )
    private fun page(root: Post,order: ReadingOrder): Page {
        val url=when(root.platform) {
            Platform.X->root.canonicalUrl+"9"
            Platform.REDDIT->root.canonicalUrl+"def456/"
            else->root.canonicalUrl+"9"
        }
        val reply=root.copy(id=root.id+"-reply",canonicalUrl=url,parentId=root.id,body="${root.platform.label} ${order.name} original reply",publishedAt=1_750_000_000_000)
        return Page(listOf(root,reply))
    }
    private fun room(url: String,order: CommentOrder)=AnywherePage(url,listOf(AnywhereComment("room-reply","$order shared reply",timestamp=1_750_000_000_001)),order,System.currentTimeMillis())
    private class Service(private val reading: suspend (String,CommentOrder)->AnywherePage): AnywhereAccess {
        val calls=CopyOnWriteArrayList<Pair<String,CommentOrder>>()
        val active=AtomicInteger();val maximum=AtomicInteger()
        override suspend fun read(url: String,order: CommentOrder,force: Boolean,limit: Int,parentId: String?): AnywherePage {
            calls+=url to order
            val count=active.incrementAndGet();maximum.updateAndGet {maxOf(it,count)}
            try{return reading(url,order)}finally {active.decrementAndGet()}
        }
        override suspend fun owner(): String?=null
        override suspend fun mutate(url: String,order: CommentOrder,action: AnywhereAction,text: String,id: String?,value: Int?)=error("Scheduling fixtures must never write")
        override suspend fun forget()=Unit
        override fun clear()=Unit
    }
    private inner class Harness(service: AnywhereAccess,read: suspend (Post,Page?,ReadingOrder)->Page) {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val reader=DiscussionReader(repo,scope,service,read)
        suspend fun act(action: (DiscussionReader)->Unit)=withContext(dispatcher){action(reader)}
        suspend fun await(predicate: (DiscussionState)->Boolean)=withTimeout(5_000){reader.state.filterNotNull().first {predicate(it)}}
        suspend fun close() {
            withContext(dispatcher){reader.close()}
            scope.coroutineContext[Job]!!.cancelAndJoin();dispatcher.close()
        }
    }

    @Test fun allOriginalSourcesProgressWhileTheCommunityAndOneOriginalSourceArePending()=runBlocking {
        repo.awaitReady()
        val communityStarted=CompletableDeferred<Unit>();val releaseCommunity=CompletableDeferred<Unit>()
        val originalStarted=CompletableDeferred<Unit>();val releaseOriginal=CompletableDeferred<Unit>()
        val service=Service {_,_->communityStarted.complete(Unit);releaseCommunity.await();error("Shared service unavailable in local fixture")}
        val originalActive=AtomicInteger();val originalMaximum=AtomicInteger()
        val h=Harness(service) {root,_,order->
            val count=originalActive.incrementAndGet();originalMaximum.updateAndGet {maxOf(it,count)}
            try {if(root==roots.first()){originalStarted.complete(Unit);releaseOriginal.await()};page(root,order)}finally {originalActive.decrementAndGet()}
        }
        try {
            h.act {it.open(roots)}
            withTimeout(5_000){communityStarted.await();originalStarted.await()}
            val partial=h.await {state->roots.drop(1).all {state.threads[it.platform.id]?.page!=null}}
            assertEquals("all",partial.selected)
            assertTrue(partial.threads.getValue(Platform.X.id).loading)
            assertTrue(partial.communities.getValue(Platform.X.id).loading)
            assertEquals(2,discussionEntries(partial,true).count {it.post!=null})
            val available=partial.threads.filterValues {it.page!=null}
            releaseCommunity.complete(Unit)
            val failed=h.await {state->state.communities.size==roots.size&&state.communities.values.all {it.error!=null&&!it.loading}}
            assertEquals(available,failed.threads.filterValues {it.page!=null})
            assertEquals(2,discussionEntries(failed,true).count {it.post!=null})
            releaseOriginal.complete(Unit)
            val ready=h.await {it.threads.values.all {thread->thread.page!=null}}
            assertEquals(3,discussionEntries(ready,true).count {it.post!=null})
            assertEquals(2,originalMaximum.get());assertEquals(1,service.maximum.get())
            assertEquals(roots.map {AnywhereUrls.root(it,repo.routes)}.toSet(),service.calls.map {it.first}.toSet())
        }finally {releaseCommunity.complete(Unit);releaseOriginal.complete(Unit);h.close()}
    }

    @Test fun changingTheActiveEditionRestartsItsCanceledOldRoomWithoutDuplicateReadsOrDraftLeakage()=runBlocking {
        repo.awaitReady()
        val first=roots[0];val second=roots[1]
        val firstStarted=CompletableDeferred<Unit>();val firstCanceled=CompletableDeferred<Unit>()
        val secondStarted=CompletableDeferred<Unit>();val releaseSecond=CompletableDeferred<Unit>()
        val firstCalls=AtomicInteger()
        val service=Service {url,order->
            if(url==AnywhereUrls.root(first,repo.routes)&&firstCalls.incrementAndGet()==1) {
                firstStarted.complete(Unit)
                try {awaitCancellation()}finally {firstCanceled.complete(Unit)}
            }
            if(url==AnywhereUrls.root(second,repo.routes)){secondStarted.complete(Unit);releaseSecond.await()}
            room(url,order)
        }
        val h=Harness(service) {root,_,order->page(root,order)}
        try {
            h.act {it.open(listOf(first,second))}
            withTimeout(5_000){firstStarted.await()}
            h.await {it.communities[first.platform.id]?.loading==true}
            h.act {it.community.draft("A draft belonging only to the first original page.");it.edition(second.platform.id)}
            withTimeout(5_000){firstCanceled.await();secondStarted.await()}
            assertEquals("",h.reader.community.state.value!!.draft.text)
            releaseSecond.complete(Unit)
            val ready=h.await {state->state.communities.size==2&&state.communities.values.all {it.page!=null&&!it.loading}}
            assertEquals(second.platform.id,ready.edition)
            assertEquals(2,firstCalls.get())
            assertEquals(1,service.calls.count {it.first==AnywhereUrls.root(second,repo.routes)})
            assertEquals(2,discussionEntries(ready,true).count {it.community!=null})
            h.act {it.edition(first.platform.id)}
            assertEquals("A draft belonging only to the first original page.",h.reader.community.state.value!!.draft.text)
            assertEquals(AnywhereUrls.root(first,repo.routes),h.reader.community.state.value!!.url)
        }finally {releaseSecond.complete(Unit);h.close()}
    }

    @Test fun selectingAnOriginalTabCancelsPendingSharedRoomsAndAllCanReadThemAgain()=runBlocking {
        repo.awaitReady()
        val entered=CompletableDeferred<Unit>();val canceled=CompletableDeferred<Unit>();val firstCalls=AtomicInteger()
        val service=Service {url,order->
            if(url==AnywhereUrls.root(roots.first(),repo.routes)&&firstCalls.incrementAndGet()==1) {
                entered.complete(Unit);try {awaitCancellation()}finally {canceled.complete(Unit)}
            }
            room(url,order)
        }
        val h=Harness(service) {root,_,order->page(root,order)}
        try {
            h.act {it.open(roots)}
            withTimeout(5_000){entered.await()}
            h.await {it.communities.size==roots.size&&it.communities.values.all {room->room.loading}}
            h.act {it.select(Platform.X.id)}
            withTimeout(5_000){canceled.await()}
            val platform=h.await {it.selected==Platform.X.id&&it.communities.isEmpty()}
            assertNull(h.reader.community.state.value);assertEquals(1,service.calls.size)
            assertNotNull(platform.threads.getValue(Platform.X.id).page)
            h.act {it.select("all")}
            val all=h.await {it.communities.size==roots.size&&it.communities.values.all {room->room.page!=null}}
            assertEquals(3,discussionEntries(all,true).count {it.post!=null})
            assertEquals(3,discussionEntries(all,true).count {it.community!=null})
            assertEquals(4,service.calls.size);assertEquals(1,service.maximum.get())
        }finally {h.close()}
    }

    @Test fun promotingAnAlreadyLoadedRoomKeepsItsRepliesOnRefreshFailureWithoutBorrowingAnotherSort()=runBlocking {
        repo.awaitReady()
        val second=roots[1];var failSecond=false
        val service=Service {url,order->
            if(url==AnywhereUrls.root(second,repo.routes)&&failSecond)error("Selected room refresh unavailable")
            room(url,order)
        }
        val h=Harness(service) {root,_,order->page(root,order)}
        try {
            h.act {it.open(roots.take(2))}
            val initial=h.await {it.communities.size==2&&it.communities.values.all {room->room.page!=null}}
            val captured=initial.communities.getValue(second.platform.id).page
            h.act {failSecond=true;it.edition(second.platform.id)}
            val failed=h.await {it.communities[second.platform.id]?.error!=null}
            assertEquals(captured,failed.communities.getValue(second.platform.id).page)
            assertEquals(2,discussionEntries(failed,true).count {it.community!=null})
            assertEquals(2,discussionEntries(failed,true).count {it.post!=null})
            assertNull("A retained read-only page must not authorize a write before the active refresh succeeds",h.reader.community.state.value!!.page)
            h.act {it.order(ReadingOrder.SLOP)}
            val sorted=h.await {it.communities[second.platform.id]?.error!=null&&it.communities[roots.first().platform.id]?.page?.order==CommentOrder.SLOP}
            assertNull("A failed different sort must not reuse an old-sort room",sorted.communities.getValue(second.platform.id).page)
            assertEquals(1,discussionEntries(sorted,true).count {it.community!=null})
        }finally {h.close()}
    }

    @Test fun aLateCanceledSortCannotOverwriteTheNewOriginalOrSharedResults()=runBlocking {
        repo.awaitReady()
        val originalEntered=CompletableDeferred<Unit>();val sharedEntered=CompletableDeferred<Unit>()
        val releaseOldOriginal=CompletableDeferred<Unit>();val releaseOldShared=CompletableDeferred<Unit>()
        val service=Service {url,order->
            if(order==CommentOrder.NEWEST){sharedEntered.complete(Unit);withContext(NonCancellable){releaseOldShared.await()}}
            room(url,order)
        }
        val h=Harness(service) {root,_,order->
            if(root==roots.first()&&order==ReadingOrder.CHRONOLOGICAL){originalEntered.complete(Unit);withContext(NonCancellable){releaseOldOriginal.await()}}
            page(root,order)
        }
        try {
            h.act {it.open(roots.take(2))}
            withTimeout(5_000){originalEntered.await();sharedEntered.await()}
            h.act {it.order(ReadingOrder.SLOP)}
            val platforms=h.await {it.order==ReadingOrder.SLOP&&it.threads.values.all {thread->thread.page!=null}}
            assertEquals(2,discussionEntries(platforms,true).count {it.post?.body?.contains("SLOP")==true})
            releaseOldOriginal.complete(Unit);releaseOldShared.complete(Unit)
            val ready=h.await {it.communities.size==2&&it.communities.values.all {room->room.page?.order==CommentOrder.SLOP}}
            assertEquals(ReadingOrder.SLOP,ready.order)
            assertTrue(ready.threads.values.all {thread->thread.page!!.posts.last().body.contains("SLOP")})
            assertEquals(CommentOrder.SLOP,h.reader.community.state.value!!.order)
            assertEquals(1,service.calls.count {it.second==CommentOrder.NEWEST})
            assertEquals(4,discussionEntries(ready,true).size)
        }finally {releaseOldOriginal.complete(Unit);releaseOldShared.complete(Unit);h.close()}
    }

    @Test fun threeOriginalsOnOnePlatformKeepAllRequestsRepliesRoomsAndPaginationIndependent()=runBlocking {
        repo.awaitReady()
        val originals=(1..3).map {index->Post("repeated-root-$index",Platform.X,"https://x.com/asscheduling/status/772000$index","asscheduling","A release posted again.",replyCount=(index*10).toLong())}
        val calls=CopyOnWriteArrayList<Pair<String,String?>>()
        val service=Service {url,order->room(url,order)}
        val h=Harness(service) {root,previous,order->
            calls+=repo.key(root) to previous?.next
            val initial=page(root,order)
            if(previous==null)initial.copy(next="next-${root.id}")
            else Page(listOf(root,initial.posts.last().copy(id=root.id+"-page-two",canonicalUrl=root.canonicalUrl+"8",body="${root.id} second page")))
        }
        try {
            h.act {it.open(originals+originals.first())}
            val ready=h.await {it.posts.size==3&&it.threads.size==3&&it.threads.values.all {entry->entry.page!=null}&&it.communities.size==3&&it.communities.values.all {entry->entry.page!=null}}
            assertEquals("all",ready.selected);assertEquals(3,ready.posts.map(ready::threadKey).distinct().size)
            assertEquals(originals.map(repo::key).toSet(),calls.map {it.first}.toSet())
            assertEquals(3,discussionEntries(ready,true).count {it.post!=null});assertEquals(3,discussionEntries(ready,true).count {it.community!=null})
            assertEquals(originals.map {AnywhereUrls.root(it,repo.routes)}.toSet(),service.calls.map {it.first}.toSet())
            val second=originals[1];val third=originals[2];val beforeThird=ready.thread(third)
            h.act {it.more(ready.threadKey(second))}
            val paged=h.await {it.thread(second)?.page?.next==null&&it.thread(second)?.page?.posts?.any {reply->reply.body=="${second.id} second page"}==true}
            assertEquals(beforeThird,paged.thread(third));assertEquals(1,calls.count {it.first==repo.key(second)&&it.second=="next-${second.id}"})
            assertEquals(4,discussionEntries(paged,true).count {it.post!=null})
            h.act {it.select(Platform.X.id)}
            val platform=h.await {it.selected==Platform.X.id}
            assertEquals(4,discussionEntries(platform,true).size);assertTrue(discussionEntries(platform,true).all {it.post!=null})
            assertEquals(3,platform.threads.size)
        }finally {h.close()}
    }

    @Test fun samePlatformComposerChangesOnlyToTheSelectedOriginalAndDraftsStayInTheirRooms()=runBlocking {
        repo.awaitReady()
        val originals=(1..3).map {index->Post("room-original-$index",Platform.X,"https://x.com/asscheduling/status/773000$index","asscheduling","The same announcement again.")}
        val first=originals[0];val second=originals[1]
        val entered=CompletableDeferred<Unit>();val canceled=CompletableDeferred<Unit>();val firstCalls=AtomicInteger()
        val service=Service {url,order->
            if(url==AnywhereUrls.root(first,repo.routes)&&firstCalls.incrementAndGet()==1){entered.complete(Unit);try {awaitCancellation()}finally {canceled.complete(Unit)}}
            room(url,order)
        }
        val h=Harness(service) {root,_,order->page(root,order)}
        try {
            h.act {it.open(originals)};withTimeout(5_000){entered.await()}
            val opened=h.reader.state.value!!;val firstKey=opened.threadKey(first);val secondKey=opened.threadKey(second)
            h.act {it.community.draft("Only the first original's room.");it.edition(secondKey)}
            withTimeout(5_000){canceled.await()}
            val ready=h.await {it.edition==secondKey&&it.communities.size==3&&it.communities.values.all {room->room.page!=null&&!room.loading}}
            assertEquals(AnywhereUrls.root(second,repo.routes),h.reader.community.state.value!!.url)
            assertEquals("",h.reader.community.state.value!!.draft.text)
            h.act {it.community.draft("Only the second original's room.");it.edition(firstKey)}
            assertEquals(AnywhereUrls.root(first,repo.routes),h.reader.community.state.value!!.url)
            assertEquals("Only the first original's room.",h.reader.community.state.value!!.draft.text)
            h.act {it.edition(secondKey)}
            assertEquals("Only the second original's room.",h.reader.community.state.value!!.draft.text)
            assertEquals(3,discussionEntries(ready,true).count {it.community!=null})
            assertEquals(1,service.maximum.get())
        }finally {h.close()}
    }

    @Test fun aggregateDeduplicationUsesTheActualReplyIdentityRatherThanItsTextOrPlatform() {
        val originals=(1..3).map {index->Post("identity-root-$index",Platform.X,"https://x.com/asscheduling/status/774000$index","asscheduling","Repeated announcement")}
        val state=DiscussionState(originals,"all")
        val duplicate=Post("same-comment",Platform.X,"https://x.com/a_commenter/status/7759999","a_commenter","One exact reply identity")
        val pages=originals.associate {root->state.threadKey(root) to ThreadRead(root,Page(listOf(root,duplicate.copy(parentId=root.id),root.copy(id=root.id+"-own-reply",canonicalUrl=root.canonicalUrl+"9",parentId=root.id,body="The same wording is still three different replies."))))}
        val entries=discussionEntries(state.copy(threads=pages),false)
        assertEquals(4,entries.size)
        assertEquals(1,entries.count {it.post?.canonicalUrl==duplicate.canonicalUrl})
        assertEquals(3,entries.count {it.post?.body=="The same wording is still three different replies."})
        assertEquals(duplicate.canonicalUrl,entries.single {it.post?.id==duplicate.id}.post!!.canonicalUrl)
    }
}
