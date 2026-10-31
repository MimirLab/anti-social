/* anti social, GPL-3.0-or-later. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class FeedEditionMatcherTest {
    @Test fun sourceRetentionStartsSmallAndLaterObservationsCanGrowIt():Unit=runBlocking {
        val matcher=FeedEditionMatcher(follows,emptyMap(),preparationBudget={SourcePreparationBudget.EXPANDED})
        val first=resourceImage(0)
        assertEquals(2,matcher.project(first,{it},{_,_->null}).size)
        assertEquals(SourcePreparationBudget.BASIC,sourceAnalysisAllocation(matcher).first)
        val second=resourceImage(1)
        matcher.project(second,{it},{_,_->null})
        assertEquals(SourcePreparationBudget.EXPANDED,sourceAnalysisAllocation(matcher).first)
        matcher.releaseSourceAnalysis()
        assertEquals(0L to 0,sourceAnalysisAllocation(matcher))
        assertEquals(2,matcher.project(second,{it},{_,_->null}).size)
        assertEquals(0L to 0,sourceAnalysisAllocation(matcher))
        matcher.project(resourceImage(2),{it},{_,_->null})
        assertEquals(SourcePreparationBudget.EXPANDED,sourceAnalysisAllocation(matcher).first)
        assertTrue(sourceAnalysisAllocation(matcher).second>0)
    }
    @Test fun aReleasedReaderCannotReinstallAnOlderResourceAnswer():Unit=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val matcher=FeedEditionMatcher(follows,emptyMap(),preparationBudget={
            if(calls.incrementAndGet()==1){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            SourcePreparationBudget.EXPANDED
        })
        val task=async(Dispatchers.Default){matcher.project(resourceImage(0),{it},{_,_->null})}
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            matcher.releaseSourceAnalysis();release.countDown()
            assertEquals(2,withTimeout(5000){task.await()}.size)
            assertEquals(0L to 0,sourceAnalysisAllocation(matcher))
            matcher.project(resourceImage(1),{it},{_,_->null})
            assertEquals(SourcePreparationBudget.EXPANDED,sourceAnalysisAllocation(matcher).first)
        }finally {release.countDown();task.cancelAndJoin()}
    }
    @Test fun aVisibilityRoundTripRejectsItsOldMemorySnapshot():Unit=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val healthy=SourcePreparationMemory(false,false,256L*1024*1024,16L*1024*1024,512L*1024*1024,128L*1024*1024)
        val resources=FeedAnalysisResources(InstrumentationRegistry.getInstrumentation().targetContext,true) {
            if(calls.incrementAndGet()==1){entered.countDown();check(release.await(5,TimeUnit.SECONDS))};healthy
        }
        val task=async(Dispatchers.Default){resources.allowance()}
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            resources.visible(false);resources.visible(true);release.countDown()
            assertEquals(0L,withTimeout(5000){task.await()})
            assertEquals(SourcePreparationBudget.EXPANDED,resources.allowance())
            resources.visible(false);val before=calls.get()
            assertEquals(0L,resources.allowance());assertEquals(before,calls.get())
        }finally {release.countDown();task.cancelAndJoin()}
    }
    private fun resourceImage(revision:Int)=MediaIdentitySnapshot(posts.mapIndexed {i,p->p.copy(media=listOf(
        Media("https://images.example.org/$i.jpg",attachmentFingerprint="resource-observation-$revision")))}){_,_->false}
    @Test fun aCancelledCollectorCannotRaceItsReplacementAndDoesNotBlockAnotherSource():Unit=runBlocking {
        val first=MediaIdentitySnapshot(posts){_,_->false};val second=MediaIdentitySnapshot(posts.map {it.copy(body=it.body+" Updated.")}){_,_->false}
        val entered=CompletableDeferred<Unit>();val nextEntered=CompletableDeferred<Unit>();val hold=CompletableDeferred<Unit>();var published=false
        val matcher=FeedEditionMatcher(follows,emptyMap()){image->
            if(image===first){entered.complete(Unit);hold.await()}else nextEntered.complete(Unit)
            image.posts.map {PostGroup(listOf(it))}
        }
        val old=launch {matcher.project(first,{it},{_,_->null});published=true}
        try {
            withTimeout(5000){entered.await()}
            val next=async {matcher.project(second,{it},{_,_->null})}
            repeat(6){yield()};assertFalse("Only this source projection waits for its retiring collector",nextEntered.isCompleted)
            val independent=FeedEditionMatcher(follows,emptyMap()){image->image.posts.map {PostGroup(listOf(it))}}
            assertEquals(2,withTimeout(5000){independent.project(second,{it},{_,_->null})}.size)
            old.cancelAndJoin();assertFalse(published)
            assertEquals(second.posts.toSet(),withTimeout(5000){next.await()}.flatMap {it.posts}.toSet())
            assertTrue(nextEntered.isCompleted)
        }finally {old.cancelAndJoin();hold.complete(Unit)}
    }
    @Test fun newImageEvidenceAndSourceEditsUseFreshAdmissionWithTheDefaultMatcher():Unit=runBlocking {
        val originals=listOf(
            Post("81000001",Platform.X,"https://x.com/workshop/status/81000001","workshop","We really got flying cars before GTA 6",publishedAt=1_800_000_000_000L,media=listOf(Media("https://one.example.org/first.jpg"))),
            Post("second",Platform.BLUESKY,"https://bsky.app/profile/workshop.example/post/second","workshop.example","We really got flying cars before GTA 6",publishedAt=1_800_000_002_000L,media=listOf(Media("https://two.example.org/second.jpg"))))
        val matcher=FeedEditionMatcher(emptyList(),emptyMap())
        for((i,same)in listOf(false,true,false,true).withIndex()) {
            val posts=originals.map {p->p.copy(media=p.media.map {it.copy(attachmentFingerprint="observation-$i")})}
            val actual=matcher.project(MediaIdentitySnapshot(posts){_,_->same},{it},{_,_->null})
            assertEquals(if(same)1 else 2,actual.size)
            assertEquals(posts.toSet(),actual.flatMap {it.posts}.toSet())
        }
        val edited=originals.mapIndexed {i,p->if(i==0)p else p.copy(body=p.body.replace("6","7"))}
        assertEquals(2,matcher.project(MediaIdentitySnapshot(edited){_,_->true},{it},{_,_->null}).size)
    }
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

/** Own application classes only; no Android hidden/system API reflection. */
internal fun sourceAnalysisAllocation(matcher:FeedEditionMatcher):Pair<Long,Int> {
    val prepared=FeedEditionMatcher::class.java.getDeclaredField("sourcePreparation").apply {isAccessible=true}.get(matcher)as Lazy<*>
    if(!prepared.isInitialized())return -1L to 0
    val source=requireNotNull(prepared.value)
    val storage=source.javaClass.getDeclaredField("preparation").apply {isAccessible=true}.get(source)
    val budget=storage.javaClass.getDeclaredField("budget").apply {isAccessible=true}.getLong(storage)
    val stats=source.javaClass.declaredMethods.single {it.name.substringBefore('$')=="stats"}.invoke(source)
    return budget to(stats.javaClass.getDeclaredMethod("getEntries").invoke(stats)as Int)
}
