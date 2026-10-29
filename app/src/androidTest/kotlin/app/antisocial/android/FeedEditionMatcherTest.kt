/* anti social, GPL-3.0-or-later. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class FeedEditionMatcherTest {
    private val at=1_800_000_000_000L
    private val follows=listOf(Platform.X,Platform.FACEBOOK).map {p->
        Follow(p.id,Target(p,TargetKind.PROFILE,"publicstudio","https://${if(p==Platform.X)"x.com" else "www.facebook.com"}/publicstudio"),"Public studio",personId="studio")
    }
    private val posts=follows.mapIndexed {i,f->Post("71000001",f.target.platform,
        f.target.canonicalUrl+if(i==0)"/status/71000001"else "/posts/71000001","publicstudio",
        if(i==0)"The first workshop recording."else "Watch the building process unfold.",publishedAt=at+i*60_000,
        media=listOf(Media("https://media.example.org/$i.mp4","video",durationMs=120_000)))}
    private val proof by lazy {
        val scenes=(0..3).map {p->
            val detail=ByteArray(4096){i->var n=(p+1)*7919+(i%64/4)*31+(i/64/4)*1009;n=(n xor(n ushr 13))*1274126177;((n ushr 16 and 191)+32).toByte()}
            SampledVideoScene(p,requireNotNull(VisualFrames.encode(1.5,40.0,0L,0L,List(48){128},detail)))
        }
        val evidence=VideoFootageEvidence("https://media.example.org/0.mp4",120_000,at,scenes)
        requireNotNull(VideoFootage.shared(evidence,evidence))
    }
    @Test fun observationsReuseOnlyTheCurrentImageAndSourceProjection():Unit=runBlocking {
        var passes=0
        fun matcher(accounts:List<Follow> = follows)=FeedEditionMatcher(accounts,emptyMap()){evidence->
            passes++;CrossPosts.groupCancellable(evidence.posts,accounts,emptyMap(),evidence::same)
        }
        val matcher=matcher()
        val image=MediaIdentitySnapshot(posts){_,_->false}
        assertEquals(2,matcher.project(image,{it},{_,_->null}).size)
        assertEquals(1,matcher.project(image,{it},{_,_->proof}).size)
        assertEquals(2,matcher.project(image,{it},{_,_->null}).size) // Never reuse a prior joined family.
        assertEquals(1,passes)
        val changed=MediaIdentitySnapshot(posts.map {it.copy(body=it.body+" Updated source.")}){_,_->false}
        assertEquals(changed.posts.toSet(),matcher.project(changed,{it},{_,_->null}).flatMap {it.posts}.toSet())
        assertEquals(2,passes)
        assertEquals(2,matcher(follows.map {it.copy(personId=it.id)}).project(image,{it},{_,_->proof}).size)
        assertEquals(3,passes)
    }
    @Test fun cancelledBaseWorkCannotInstallAnIncompleteCache():Unit=runBlocking {
        var attempts=0
        val matcher=FeedEditionMatcher(follows,emptyMap()){evidence->
            if(++attempts==1)throw CancellationException("Obsolete image snapshot")
            CrossPosts.groupCancellable(evidence.posts,follows)
        }
        val image=MediaIdentitySnapshot(posts){_,_->false}
        try {matcher.project(image,{it},{_,_->null});fail("Expected cancellation")}catch(_:CancellationException){}
        assertEquals(2,matcher.project(image,{it},{_,_->null}).size)
        assertEquals(2,attempts)
    }
    @Test fun aBusyProjectionFinishesItsCoherentPassThenUsesOnlyTheNewestPendingSnapshot():Unit=runBlocking {
        val first=MediaIdentitySnapshot(posts){_,_->false}
        val updates=(1..3).map {i->MediaIdentitySnapshot(posts.map {it.copy(body=it.body+" Evidence $i.")}){_,_->false}}
        val commands=Channel<MediaIdentitySnapshot>();val emitted=Channel<Unit>(Channel.UNLIMITED)
        val images=flow {emit(first);for(next in commands){emit(next);emitted.send(Unit)}}
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val received=mutableListOf<MediaIdentitySnapshot>()
        val task=launch {feedMatchingInputs(images,MutableStateFlow(0L),MutableStateFlow(0L)).take(2).collect {image->
            if(received.isEmpty()){entered.complete(Unit);finish.await()}
            received+=image
        }}
        try {
            withTimeout(5_000){entered.await()}
            for(next in updates){commands.send(next);withTimeout(5_000){emitted.receive()};repeat(4){yield()}}
            assertTrue("Current coherent work must remain active",task.isActive);assertTrue(received.isEmpty())
            finish.complete(Unit);withTimeout(5_000){task.join()}
            assertEquals(2,received.size);assertSame(first,received[0]);assertSame("Intermediate evidence must not queue whole-window passes",updates.last(),received[1])
        }finally {finish.complete(Unit);commands.close();task.cancelAndJoin()}
    }
    @Test fun unchangedImagesStillDeliverNewFootageAndGalleryActivity():Unit=runBlocking {
        val image=MediaIdentitySnapshot(posts){_,_->false};val videos=MutableStateFlow(0L);val galleries=MutableStateFlow(0L)
        val received=Channel<MediaIdentitySnapshot>(Channel.UNLIMITED)
        val task=launch {feedMatchingInputs(flowOf(image),videos,galleries).collect {received.send(it)}}
        try {
            assertSame(image,withTimeout(5_000){received.receive()})
            videos.value=1;assertSame(image,withTimeout(5_000){received.receive()})
            galleries.value=1;assertSame(image,withTimeout(5_000){received.receive()})
        }finally {task.cancelAndJoin()}
    }
    @Test fun retiringASourceEpochCancelsItsRunningAndBufferedInputs():Unit=runBlocking {
        val image=MediaIdentitySnapshot(posts){_,_->false};val newest=MediaIdentitySnapshot(posts.reversed()){_,_->false}
        val entered=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        val commands=Channel<MediaIdentitySnapshot>(Channel.UNLIMITED);val received=mutableListOf<MediaIdentitySnapshot>()
        val images=flow {try {emit(image);for(next in commands)emit(next)}finally {stopped.complete(Unit)}}
        val task=launch {feedMatchingInputs(images,MutableStateFlow(0L),MutableStateFlow(0L)).collect {next->
            entered.complete(Unit);gate.await();received+=next
        }}
        try {
            withTimeout(5_000){entered.await()};commands.send(newest);repeat(4){yield()}
            task.cancelAndJoin();withTimeout(5_000){stopped.await()};assertTrue(received.isEmpty())
            assertSame(newest,feedMatchingInputs(flowOf(newest),MutableStateFlow(0L),MutableStateFlow(0L)).first())
        }finally {task.cancelAndJoin();commands.close();gate.complete(Unit)}
    }
}
