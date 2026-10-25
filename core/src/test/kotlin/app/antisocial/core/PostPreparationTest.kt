package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import kotlinx.coroutines.*

class PostPreparationTest {
    private val body="The public workshop published detailed instructions for replacing a laptop battery, checking the connector, borrowing suitable tools and safely recycling the damaged pack. The illustrated guide includes repair photographs, compatible part numbers and the volunteer team's updated maintenance schedule."
    private fun post(index:Int,platform:Platform=Platform.X)=Post("${10000+index}",platform,
        if(platform==Platform.X)"https://x.com/workshop/status/${10000+index}"else "https://www.facebook.com/workshop/posts/${10000+index}",
        "Workshop","Bulletin ${index/2}. $body",publishedAt=1_780_000_000_000L-index/2*3_600_000L)
    @Test fun groupingReusesAnalysisButKeepsIndependentBulletinsAndEveryOriginal() {
        val posts=(0 until 24).map {post(it,if(it%2==0)Platform.X else Platform.FACEBOOK)}
        assertNull(CrossPostFusion.preparationStats())
        val groups=CrossPostFusion.withPrepared {
            val result=CrossPosts.group(posts,emptyList())
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.computations<=posts.size);assertTrue(stats.hits>posts.size)
            assertTrue(stats.urlHits>0);assertTrue(stats.urlComputations>0)
            result
        }
        assertNull(CrossPostFusion.preparationStats())
        assertEquals(12,groups.size);assertTrue(groups.all {it.posts.size==2})
        assertEquals(posts.map(CrossPosts::originalKey).toSet(),groups.flatMap {it.posts}.map(CrossPosts::originalKey).toSet())
    }
    @Test fun failedPreparationIsReusedOnlyWithinItsOperationAndExceptionsReleaseIt() {
        val short=post(0).copy(body="Brief",title="")
        CrossPostFusion.withPrepared {
            repeat(3){assertFalse(CrossPostFusion.matches(short,post(1)))}
            assertEquals(1,CrossPostFusion.preparationStats()!!.computations)
            assertEquals(2,CrossPostFusion.preparationStats()!!.hits)
        }
        assertThrows(IllegalStateException::class.java){CrossPostFusion.withPrepared {CrossPostFusion.matches(short,post(1));error("Interrupted comparison")}}
        assertNull(CrossPostFusion.preparationStats())
        val full=short.copy(body=post(0).body)
        assertTrue(CrossPostFusion.matches(full,post(1,Platform.FACEBOOK)))
    }
    @Test fun urlReuseKeepsExactInputsAndNullsWithinTheOwningOperation() {
        var calls=0
        fun read(value:String)=CrossPostFusion.normalizedUrl(value){calls++;value.takeUnless {it=="invalid"}}
        repeat(2){assertNull(read("invalid"))};assertEquals(2,calls)
        CrossPostFusion.withPrepared {
            repeat(3){assertNull(read("invalid"))};assertEquals(3,calls)
            assertEquals("https://example.org/Part?key=A",read("https://example.org/Part?key=A"))
            assertEquals("https://example.org/part?key=a",read("https://example.org/part?key=a"))
            CrossPostFusion.withPrepared {assertEquals("https://example.org/Part?key=A",read("https://example.org/Part?key=A"))}
            assertEquals(5,calls)
        }
        assertNull(CrossPostFusion.preparationStats());assertNull(read("invalid"));assertEquals(6,calls)
        assertThrows(IllegalStateException::class.java){CrossPostFusion.withPrepared {read("invalid");error("Cancelled preparation")}}
        assertNull(CrossPostFusion.preparationStats())
        assertNull(read("invalid"));assertEquals(8,calls)
    }
    @Test fun urlReuseSharesTheExistingByteBoundAndCannotGrowWithReadingHistory() {
        CrossPostFusion.withPrepared {
            repeat(3000){i->val value="https://example.org/$i";CrossPostFusion.normalizedUrl(value){value}}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.urlEntries<=2048);assertTrue(stats.bytes<=8L*1024*1024)
        }
        CrossPostFusion.withPrepared {
            repeat(300){i->val value="https://example.org/$i/"+"a".repeat(16_384);CrossPostFusion.normalizedUrl(value){value}}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.urlEntries<300);assertTrue(stats.bytes<=8L*1024*1024)
        }
    }
    @Test fun aSupersededGroupingStopsDuringPairChecksWithoutPublishingPartialGroups()=runBlocking {
        val posts=(0 until 24).map {post(it,if(it%2==0)Platform.X else Platform.FACEBOOK).copy(media=listOf(Media("https://images.example.org/$it.jpg")))}
        var published=false;var compared=false
        val job=launch(Dispatchers.Default) {
            val self=currentCoroutineContext()[Job]!!
            CrossPosts.groupCancellable(posts,emptyList(),sameMedia={_,_->compared=true;self.cancel();false})
            published=true
        }
        job.join();assertTrue(compared);assertTrue(job.isCancelled);assertFalse(published)
    }
    @Test fun theWorkspaceIsBoundedAndNeverSharedByParallelReaders() {
        CrossPostFusion.withPrepared {
            repeat(3000){CrossPostFusion.candidateAnchors(post(it).copy(body="Too short",title=""))}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.entries<=2048);assertTrue(stats.bytes<=8L*1024*1024)
        }
        CrossPostFusion.withPrepared {
            repeat(160){CrossPostFusion.candidateAnchors(post(it).copy(body=body.repeat(12)))}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue("Large prepared captions reach the byte budget first",stats.entries<160)
            assertTrue(stats.bytes>1024*1024);assertTrue(stats.bytes<=8L*1024*1024)
        }
        val workers=Executors.newFixedThreadPool(2)
        try {
            val barrier=java.util.concurrent.CyclicBarrier(2)
            val first=workers.submit<Boolean> {CrossPostFusion.withPrepared {
                assertEquals("first",CrossPostFusion.normalizedUrl("same input"){"first"});barrier.await(10,java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("first",CrossPostFusion.normalizedUrl("same input"){error("Already resolved")})
                CrossPostFusion.matches(post(0),post(1,Platform.FACEBOOK))
            }}
            val second=workers.submit<Boolean> {CrossPostFusion.withPrepared {
                assertEquals("second",CrossPostFusion.normalizedUrl("same input"){"second"});barrier.await(10,java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("second",CrossPostFusion.normalizedUrl("same input"){error("Already resolved")})
                CrossPostFusion.matches(post(0),post(1,Platform.FACEBOOK).copy(body=post(1).body.replace("Bulletin 0","Bulletin 7")))
            }}
            assertTrue(first.get());assertFalse(second.get());assertNull(CrossPostFusion.preparationStats())
        }finally {workers.shutdownNow()}
    }
}
