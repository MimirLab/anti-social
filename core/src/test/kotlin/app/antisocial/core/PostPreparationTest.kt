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
            val first=workers.submit<Boolean> {CrossPostFusion.withPrepared {CrossPostFusion.matches(post(0),post(1,Platform.FACEBOOK))}}
            val second=workers.submit<Boolean> {CrossPostFusion.withPrepared {CrossPostFusion.matches(post(0),post(1,Platform.FACEBOOK).copy(body=post(1).body.replace("Bulletin 0","Bulletin 7")))}}
            assertTrue(first.get());assertFalse(second.get());assertNull(CrossPostFusion.preparationStats())
        }finally {workers.shutdownNow()}
    }
}
