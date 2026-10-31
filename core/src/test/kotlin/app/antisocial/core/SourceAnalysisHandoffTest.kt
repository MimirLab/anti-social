/* anti social, GPL-3.0-or-later. Source facts survive only qualified context. */
package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class SourceAnalysisHandoffTest {
    private val at=1_800_000_000_000L
    private val posts=listOf(
        Post("81000001",Platform.X,"https://x.com/workshop/status/81000001","workshop","We really got flying cars before GTA 6",publishedAt=at,media=listOf(Media("https://one.example.org/first.jpg"))),
        Post("second",Platform.BLUESKY,"https://bsky.app/profile/workshop.example/post/second","workshop.example","We really got flying cars before GTA 6",publishedAt=at+2000,media=listOf(Media("https://two.example.org/second.jpg"))))
    private val follows=posts.map {p->Follow(p.platform.id,Target(p.platform,TargetKind.PROFILE,p.author,
        if(p.platform==Platform.X)"https://x.com/workshop"else "https://bsky.app/profile/workshop.example"),"Workshop",personId="workshop")}
    private val members=posts.associate {it.identityKey to setOf(it.platform.id)}
    private suspend fun snapshot():CrossPosts.SourceAnalysisSnapshot {
        val projection=CrossPosts.sourceProjection(follows,members)
        projection.group(posts){_,_->true}
        return requireNotNull(projection.snapshotSourceAnalysis())
    }
    private suspend fun parity(projection:CrossPosts.SourceProjection,values:List<Post>,accounts:List<Follow> = follows,bindings:Map<String,Set<String>> = members,same:Boolean=false) {
        val actual=projection.group(values){_,_->same}
        assertEquals(CrossPosts.groupCancellable(values,accounts,bindings){_,_->same},actual)
        assertEquals(values.map {it.identityKey}.toSet(),actual.flatMap {it.posts}.map {it.identityKey}.toSet())
    }
    @Test fun oneNewSourceReusesOnlyUnchangedAnalysisAndRunsFreshPixelAdmission():Unit=runBlocking {
        val snapshot=snapshot()
        val added=posts.first().copy(id="81000002",canonicalUrl="https://x.com/workshop/status/81000002",body="A separate volunteer workshop opens this afternoon.",publishedAt=at+4000)
        val values=(posts+added).map {p->p.copy(media=p.media.map {it.copy(attachmentFingerprint="new-observation")})}
        val bindings=members+(added.identityKey to setOf(Platform.X.id))
        val projection=CrossPosts.sourceProjection(follows,bindings)
        projection.restoreSourceAnalysis(snapshot,values)
        parity(projection,values,bindings=bindings,same=false)
        assertEquals(2,projection.stats().hits);assertEquals(1,projection.stats().misses)
        parity(projection,values,bindings=bindings,same=true)
        assertEquals(5,projection.stats().hits);assertEquals(1,projection.stats().misses)
    }
    @Test fun changedSourceFieldsCannotBorrowAnOlderVersionsAnalysis():Unit=runBlocking {
        val snapshot=snapshot()
        val changes=listOf<(Post)->Post>(
            {it.copy(body=it.body.replace("6","7"))},{it.copy(body="We did not get flying cars before GTA 6")},
            {it.copy(title="A different heading.")},{it.copy(publishedAt=at-1_000_000)},
            {it.copy(parentId="another-root")},{it.copy(author="another-publisher")},
            {it.copy(links=listOf("https://example.org/correction"))},
            {it.copy(media=it.media.map {m->m.copy(url="https://one.example.org/another.jpg")})},
            {it.copy(extractionCoverage=ExtractionCoverage(text=ExtractionStatus.PARTIAL))})
        for(change in changes) {
            val values=listOf(change(posts.first()),posts.last())
            val projection=CrossPosts.sourceProjection(follows,members)
            projection.restoreSourceAnalysis(snapshot,values);parity(projection,values,same=true)
            assertEquals(1,projection.stats().hits);assertEquals(1,projection.stats().misses)
        }
    }
    @Test fun publisherBindingsAndIndividualMembershipsArePartOfTheHandoff():Unit=runBlocking {
        val snapshot=snapshot()
        for(accounts in listOf(follows.map {it.copy(personId=it.id)},follows.map {it.copy(enabled=false)},follows.reversed())) {
            val projection=CrossPosts.sourceProjection(accounts,members)
            projection.restoreSourceAnalysis(snapshot,posts);parity(projection,posts,accounts=accounts,same=true)
            assertEquals(0,projection.stats().hits);assertEquals(2,projection.stats().misses)
        }
        val changed=members-posts.first().identityKey
        val projection=CrossPosts.sourceProjection(follows,changed)
        projection.restoreSourceAnalysis(snapshot,posts);parity(projection,posts,bindings=changed,same=true)
        assertEquals(1,projection.stats().hits);assertEquals(1,projection.stats().misses)
    }
    @Test fun theReceivingWindowAndMemoryAllowanceStillBoundTheImportedFacts():Unit=runBlocking {
        val snapshot=snapshot()
        val one=CrossPosts.SourceProjection(follows,members,maxEntries=1,maxBytes=16_384)
        one.restoreSourceAnalysis(snapshot,posts)
        assertEquals(1,one.stats().entries);assertTrue(one.stats().bytes<=16_384)
        parity(one,posts,same=false)
        val subset=CrossPosts.sourceProjection(follows,members)
        subset.restoreSourceAnalysis(snapshot,posts.take(1));assertEquals(1,subset.stats().entries)
        parity(subset,posts.take(1));subset.group(emptyList());assertEquals(0,subset.stats().entries)
        val disabled=CrossPosts.sourceProjection(follows,members);disabled.setPreparationBudget(0)
        disabled.restoreSourceAnalysis(snapshot,posts);parity(disabled,posts,same=true)
        assertEquals(0,disabled.stats().entries);assertEquals(0L,disabled.stats().bytes)
        assertNull(disabled.snapshotSourceAnalysis())
    }
    @Test fun aPendingRestoreCannotRefillFactsAfterAMemoryRelease():Unit=runBlocking {
        val snapshot=snapshot();val projection=CrossPosts.sourceProjection(follows,members)
        val field=projection.javaClass.getDeclaredField("preparation").also {it.isAccessible=true};val preparation=field.get(projection)
        val gate=preparation.javaClass.getDeclaredField("gate").also {it.isAccessible=true}.get(preparation)
        val failure=AtomicReference<Throwable?>()
        val worker=Thread {try {runBlocking {projection.restoreSourceAnalysis(snapshot,posts)}}catch(t:Throwable){failure.set(t)}}
        synchronized(gate) {
            worker.start()
            val deadline=System.nanoTime()+5_000_000_000L
            fun waiting()=worker.state==Thread.State.BLOCKED&&worker.stackTrace.any {it.className.endsWith("CrossPosts\$FeaturePreparation")&&it.methodName=="seed"}
            while(!waiting()&&System.nanoTime()<deadline)Thread.yield()
            assertTrue("The restore must be waiting for the actual seed monitor",waiting())
            projection.setPreparationBudget(0)
        }
        worker.join(5000);assertFalse(worker.isAlive);failure.get()?.let {throw it}
        assertEquals(0,projection.stats().entries);assertEquals(0L,projection.stats().bytes)
    }
    @Test fun cancellationCannotPublishAHandoffAndReleasingItsDonorDoesNotEraseCopiedFacts():Unit=runBlocking {
        val donor=CrossPosts.sourceProjection(follows,members);donor.group(posts)
        assertNull(donor.snapshotSourceAnalysis(0))
        assertThrows(IllegalArgumentException::class.java){donor.snapshotSourceAnalysis(SourcePreparationBudget.EXPANDED+1)}
        val snapshot=requireNotNull(donor.snapshotSourceAnalysis());donor.setPreparationBudget(0)
        val projection=CrossPosts.sourceProjection(follows,members);var published=false
        val job=launch {currentCoroutineContext().cancel();projection.restoreSourceAnalysis(snapshot,posts);published=true}
        job.join();assertTrue(job.isCancelled);assertFalse(published);assertEquals(0,projection.stats().entries)
        projection.restoreSourceAnalysis(snapshot,posts);parity(projection,posts,same=false)
        assertEquals(2,projection.stats().hits)
        assertEquals(0,donor.stats().entries)
    }
}
