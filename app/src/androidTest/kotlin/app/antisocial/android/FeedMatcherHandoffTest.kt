/* anti social, GPL-3.0-or-later. New source windows keep qualified facts only. */
package app.antisocial.android

import app.antisocial.core.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FeedMatcherHandoffTest {
    private val at=1_800_000_000_000L
    private val follows=listOf(
        Follow("x",Target(Platform.X,TargetKind.PROFILE,"workshop","https://x.com/workshop"),"Workshop",personId="workshop"),
        Follow("sky",Target(Platform.BLUESKY,TargetKind.PROFILE,"workshop.example","https://bsky.app/profile/workshop.example"),"Workshop",personId="workshop"))
    private val posts=listOf(
        Post("81000001",Platform.X,"https://x.com/workshop/status/81000001","workshop","A public repair workshop opens the equipment room this afternoon.",publishedAt=at,media=listOf(Media("https://one.example.org/picture.jpg"))),
        Post("second",Platform.BLUESKY,"https://bsky.app/profile/workshop.example/post/second","workshop.example","A public repair workshop opens the equipment room this afternoon.",publishedAt=at+2000,media=listOf(Media("https://two.example.org/picture.jpg"))))
    private val members=posts.associate {p->p.identityKey to setOf(if(p.platform==Platform.X)"x"else "sky")}
    private val budget={SourcePreparationBudget.EXPANDED}
    private fun observation(values:List<Post>,revision:Int)=MediaIdentitySnapshot(values.map {p->p.copy(media=p.media.map {it.copy(attachmentFingerprint="observation-$revision")})}){_,_->false}
    private suspend fun project(matcher:FeedEditionMatcher,values:List<Post>,revision:Int=0,accounts:List<Follow> = follows,bindings:Map<String,Set<String>> = members) {
        val input=observation(values,revision)
        val actual=matcher.project(input,{it},{_,_->null})
        assertEquals(CrossPosts.groupCancellable(input.posts,accounts,bindings,input::same),actual)
        assertEquals(values.map {it.identityKey}.toSet(),actual.flatMap {it.posts}.map {it.identityKey}.toSet())
    }
    @Test fun aLateOriginalKeepsItsPredecessorsPureFactsWithinTheFirstPassAllowance():Unit=runBlocking {
        val handoff=FeedMatcherHandoff();val first=requireNotNull(handoff.create(follows,members,budget))
        project(first,posts);project(first,posts,1)
        assertEquals(SourcePreparationBudget.EXPANDED,sourceAnalysisAllocation(first).first)
        val added=posts.first().copy(id="81000002",canonicalUrl="https://x.com/workshop/status/81000002",body="A separate workshop gathering.",publishedAt=at+4000)
        val bindings=members+(added.identityKey to setOf("x"))
        val next=requireNotNull(handoff.create(follows,bindings,budget));first.releaseSourceAnalysis()
        project(next,posts+added,2,bindings=bindings)
        assertEquals(2 to 1,counts(next))
        assertEquals(SourcePreparationBudget.BASIC,sourceAnalysisAllocation(next).first)
        assertEquals(0L to 0,sourceAnalysisAllocation(first))
    }
    @Test fun changedMembershipAndPublisherContextRequireTheirCurrentAnalysis():Unit=runBlocking {
        val handoff=FeedMatcherHandoff();val first=requireNotNull(handoff.create(follows,members,budget));project(first,posts)
        val bindings=members-posts.first().identityKey
        val second=requireNotNull(handoff.create(follows,bindings,budget));first.releaseSourceAnalysis();project(second,posts,1,bindings=bindings)
        assertEquals(1 to 1,counts(second))
        val accounts=follows.map {it.copy(personId=it.id)}
        val third=requireNotNull(handoff.create(accounts,bindings,budget));second.releaseSourceAnalysis();project(third,posts,2,accounts,bindings)
        assertEquals(0 to 2,counts(third))
    }
    @Test fun stoppingBeforeTheFirstReceivingPassDropsThePendingHandoff():Unit=runBlocking {
        val healthy=SourcePreparationMemory(false,false,256L*1024*1024,16L*1024*1024,512L*1024*1024,128L*1024*1024)
        val resources=FeedAnalysisResources(InstrumentationRegistry.getInstrumentation().targetContext,true){healthy}
        val handoff=FeedMatcherHandoff();val first=requireNotNull(handoff.create(follows,members,resources::allowance));project(first,posts)
        val next=requireNotNull(handoff.create(follows,members,resources::allowance));first.releaseSourceAnalysis()
        resources.visible(false);next.releaseSourceAnalysis();project(next,posts,1)
        assertEquals(0L to 0,sourceAnalysisAllocation(next));assertEquals(0 to 2,counts(next))
        resources.visible(true);project(next,posts,2)
        assertTrue(sourceAnalysisAllocation(next).second>0);assertEquals(0 to 4,counts(next))
    }
    @Test fun aStaleResourceAnswerCannotRestoreThePendingFactsAfterStop():Unit=runBlocking {
        val handoff=FeedMatcherHandoff();val first=requireNotNull(handoff.create(follows,members,budget));project(first,posts)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val next=requireNotNull(handoff.create(follows,members){entered.countDown();check(release.await(5,TimeUnit.SECONDS));SourcePreparationBudget.EXPANDED})
        first.releaseSourceAnalysis()
        val work=async(Dispatchers.Default){project(next,posts,1)}
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));next.releaseSourceAnalysis();release.countDown()
            withTimeout(5000){work.await()}
            assertEquals(0L to 0,sourceAnalysisAllocation(next));assertEquals(0 to 2,counts(next))
        }finally {release.countDown();work.cancelAndJoin()}
    }
    @Test fun anAbsentSourceWindowCannotSeedAnotherReadersLaterWindow():Unit=runBlocking {
        val handoff=FeedMatcherHandoff();val first=requireNotNull(handoff.create(follows,members,budget));project(first,posts)
        assertNull(handoff.create(null,emptyMap(),budget));first.releaseSourceAnalysis()
        val next=requireNotNull(handoff.create(follows,members,budget));project(next,posts,1)
        assertEquals(0 to 2,counts(next))
    }
    private fun counts(matcher:FeedEditionMatcher):Pair<Int,Int> {
        val prepared=FeedEditionMatcher::class.java.getDeclaredField("sourcePreparation").apply {isAccessible=true}.get(matcher)as Lazy<*>
        val source=requireNotNull(prepared.value)
        val storage=source.javaClass.getDeclaredField("preparation").apply {isAccessible=true}.get(source)
        return storage.javaClass.getDeclaredField("hits").apply {isAccessible=true}.getInt(storage) to
            storage.javaClass.getDeclaredField("misses").apply {isAccessible=true}.getInt(storage)
    }
}
