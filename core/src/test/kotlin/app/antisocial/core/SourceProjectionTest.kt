/* anti social, GPL-3.0-or-later. Current evidence and retained source analysis. */
package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourceProjectionTest {
    private val at=1_800_000_000_000L
    private val text="We really got flying cars before GTA 6"
    private val posts=listOf(
        Post("81000001",Platform.X,"https://x.com/workshop/status/81000001","workshop",text,publishedAt=at,media=listOf(Media("https://one.example.org/first.jpg"))),
        Post("second",Platform.BLUESKY,"https://bsky.app/profile/workshop.example/post/second","workshop.example",text,publishedAt=at+2000,media=listOf(Media("https://two.example.org/second.jpg"))))
    private val follows=posts.map {p->Follow(p.platform.id,Target(p.platform,TargetKind.PROFILE,p.author,
        if(p.platform==Platform.X)"https://x.com/workshop"else "https://bsky.app/profile/workshop.example"),"Workshop",personId="workshop")}
    private val members=posts.associate {it.identityKey to setOf(it.platform.id)}
    private suspend fun parity(projection:CrossPosts.SourceProjection,values:List<Post>,accounts:List<Follow> = emptyList(),bindings:Map<String,Set<String>> = emptyMap(),same:Boolean=false):List<PostGroup> {
        val expected=CrossPosts.groupCancellable(values,accounts,bindings){_,_->same}
        val actual=projection.group(values){_,_->same}
        assertEquals(expected,actual)
        assertEquals(values.map {it.identityKey}.toSet(),actual.flatMap {it.posts}.map {it.identityKey}.toSet())
        return actual
    }
    @Test fun reusingCaptionsNeverReusesAnImageDecisionOrLosesTheCurrentMediaFields():Unit=runBlocking {
        val projection=CrossPosts.sourceProjection(emptyList())
        for((i,same)in listOf(false,true,false,true).withIndex()) {
            val current=posts.map {p->p.copy(media=p.media.map {it.copy(visualFingerprint="observation-$i",attachmentFingerprint="detail-$i")})}
            assertEquals(if(same)1 else 2,parity(projection,current,same=same).size)
        }
        assertEquals(2,projection.stats().misses);assertEquals(6,projection.stats().hits)
    }
    @Test fun everyOtherSourceChangeInvalidatesItsAnalysisAndStillUsesFreshAdmission():Unit=runBlocking {
        val mutations=listOf<(Post)->Post>(
            {it.copy(body=it.body.replace("6","7"))},{it.copy(body="We did not get flying cars before GTA 6")},
            {it.copy(title="A different original headline.")},{it.copy(canonicalUrl="https://other.example.org/81000001")},
            {it.copy(author="anotherpublisher")},{it.copy(authorAccount=PostAuthorAccount("https://x.com/anotherpublisher"))},
            {it.copy(publishedAt=at-7*24*60*60_000L)},{it.copy(publishedAt=null)},
            {it.copy(parentId="other-root")},{it.copy(preview=true)},
            {it.copy(provenance=Provenance(Source(it.platform,"original","https://x.com",role=SourceRole.ORIGINAL),at,"https://x.com/workshop"))},
            {it.copy(links=listOf("https://example.org/another-claim"))},
            {it.copy(media=it.media.map {m->m.copy(url="https://media.example.org/changed.jpg")})},
            {it.copy(media=it.media.map {m->m.copy(type="video",durationMs=20_000,preview="https://media.example.org/cover.jpg")})},
            {it.copy(media=it.media.map {m->m.copy(description="A source-supplied description.",samplingUrl="https://media.example.org/stream")})})
        for(change in mutations) {
            val projection=CrossPosts.sourceProjection(follows,members)
            parity(projection,posts,follows,members,true)
            val changed=listOf(change(posts[0]),posts[1])
            parity(projection,changed,follows,members,true)
            assertEquals(3,projection.stats().misses);assertEquals(1,projection.stats().hits)
        }
    }
    @Test fun theProjectionOwnsItsExactAccountAndMembershipContext():Unit=runBlocking {
        val accounts=follows.toMutableList();val bindings=members.mapValues {it.value.toMutableSet()}.toMutableMap()
        val projection=CrossPosts.sourceProjection(accounts,bindings)
        accounts.clear();bindings.values.forEach {it.clear()};bindings.clear()
        parity(projection,posts,follows,members,true)
        parity(projection,posts.map {it.copy()},follows,members,false)
        val independent=CrossPosts.sourceProjection(follows.map {it.copy(personId=it.id)},emptyMap())
        parity(independent,posts,follows.map {it.copy(personId=it.id)},emptyMap(),true)
        assertEquals(2,independent.stats().misses);assertEquals(0,independent.stats().hits)
    }
    @Test fun decorativePreparationCanBecomeRelevantLaterAndNeverUsesAnOlderPost():Unit=runBlocking {
        val prose="The repair workshop opens its equipment room for a public demonstration with experienced volunteers this afternoon. Visitors can examine the replacement parts and ask practical questions about the assembly."
        val values=posts.map {it.copy(body=prose)}
        val projection=CrossPosts.sourceProjection(follows,members)
        parity(projection,values,follows,members)
        val decorated=values.mapIndexed {i,p->if(i==0)p else p.copy(body="🛠️ "+p.body+" ✨")}
        parity(projection,decorated,follows,members)
        parity(projection,decorated.map {p->p.copy(media=p.media.map {it.copy(attachmentFingerprint="new-observation")})},follows,members)
        parity(projection,decorated.mapIndexed {i,p->if(i==0)p else p.copy(body=p.body.replace("opens","does not open"))},follows,members)
        assertTrue(projection.stats().hits>=3)
    }
    @Test fun cancellationCanRetainPureAnalysisButCannotPublishOrRetainAMatch():Unit=runBlocking {
        val projection=CrossPosts.sourceProjection(emptyList());var published=false;var compared=false
        val job=launch(Dispatchers.Default) {
            val current=currentCoroutineContext()[Job]!!
            projection.group(posts){_,_->compared=true;current.cancel();false}
            published=true
        }
        job.join();assertTrue(compared);assertTrue(job.isCancelled);assertFalse(published)
        assertEquals(1,parity(projection,posts,same=true).size)
        assertEquals(2,parity(projection,posts,same=false).size)
    }
    @Test fun detailedGalleryContradictionsAndParentWithdrawalStillSplitPreviouslyCollectedPictures():Unit=runBlocking {
        fun detail(index:Int,changed:Boolean=false):String {
            val pixels=ByteArray(4096){i->(40+(i%64*7+i/64*11+index*30)%170).toByte()}
            if(changed)for(y in 20..23)for(x in 40..43)pixels[y*64+x]=((pixels[y*64+x].toInt()and 255)+60).coerceAtMost(255).toByte()
            return VisualFrames.encode(1.25,50.0,0x1234+index.toLong(),0x4567,List(48){70+(it*3+index*7)%140},pixels)!!
        }
        val caption="Three repair examples from the workshop show the original connector, the replacement and the completed assembly."
        val gallery=Post("Gallery",Platform.INSTAGRAM,"https://www.instagram.com/p/Gallery","Workshop",caption,publishedAt=at,
            media=(0..2).map {Media("https://images.example/$it.jpg",attachmentFingerprint=detail(it))})
        val parent=Post("100",Platform.X,"https://x.com/workshop/status/100","Workshop",caption,publishedAt=at,media=gallery.media.take(1))
        val child=parent.copy(id="101",canonicalUrl="https://x.com/workshop/status/101",body="",parentId=parent.id,
            media=gallery.media.drop(1).map {it.copy(url=it.url.replace("images.example","resized.example"))})
        val projection=CrossPosts.sourceProjection(emptyList())
        assertEquals(1,parity(projection,listOf(gallery,parent,child),same=true).size)
        val edited=child.copy(media=child.media.mapIndexed {i,m->if(i==0)m.copy(attachmentFingerprint=detail(1,true))else m})
        assertEquals(2,parity(projection,listOf(gallery,parent,edited),same=true).size)
        assertEquals(1,parity(projection,listOf(gallery,parent,child),same=true).size)
        // Without a parent, the weak detail needs a separate strong image
        // proof. Do not supply that proof while testing its withdrawal.
        assertEquals(2,parity(projection,listOf(gallery,parent,child.copy(parentId=null)),same=false).size)
        assertTrue(projection.stats().hits>=8)
    }
    @Test fun concurrentObservationsShareNoAdmissionAndUseIndependentOperationWorkspaces():Unit=runBlocking {
        val projection=CrossPosts.sourceProjection(emptyList())
        coroutineScope {
            listOf(false,true).map {same->async(Dispatchers.Default){repeat(6){pass->
                val current=posts.map {p->p.copy(media=p.media.map {it.copy(attachmentFingerprint="${same}-$pass")})}
                assertEquals(if(same)1 else 2,parity(projection,current,same=same).size)
            }}}.awaitAll()
        }
        assertEquals(2,projection.stats().entries)
        assertNull(CrossPostFusion.preparationStats())
    }
    @Test fun cacheOverflowRemovedRowsAndOversizedRevisionsNeverReduceMatching():Unit=runBlocking {
        val projection=CrossPosts.SourceProjection(emptyList(),emptyMap(),maxEntries=1,maxBytes=32_768)
        repeat(3){parity(projection,posts,same=it%2==0)}
        assertEquals(1,projection.stats().entries);assertTrue(projection.stats().bytes<=32_768)
        val huge=posts.map {it.copy(body="Very long source prose. ".repeat(6000))}
        parity(projection,huge);assertEquals(0,projection.stats().entries);assertEquals(0,projection.stats().bytes)
        parity(projection,posts);assertEquals(1,projection.stats().entries)
        projection.group(emptyList());assertEquals(0,projection.stats().entries);assertEquals(0,projection.stats().bytes)
    }
    @Test fun mediaIsCapturedAfterSourcePreparationAndOneCoherentSetOwnsEveryComparison():Unit=runBlocking {
        val projection=CrossPosts.sourceProjection(emptyList())
        for(same in listOf(true,false,true)) {
            val current=posts.map {p->p.copy(media=p.media.map {it.copy(attachmentFingerprint="current-$same")})}
            var captured=false
            val actual=projection.groupWithCurrentMedia(posts) {
                assertEquals(2,projection.stats().entries);captured=true
                CrossPostMediaSnapshot(current){a,b->
                    assertTrue(captured)
                    assertEquals(current.toSet(),setOf(a,b))
                    same
                }
            }
            assertTrue(captured);assertEquals(if(same)1 else 2,actual.size)
            assertEquals(current.toSet(),actual.flatMap {it.posts}.toSet())
        }
    }
    @Test fun mediaRefreshCannotChangeSourcesOrPublishAfterCancellation():Unit=runBlocking {
        val projection=CrossPosts.sourceProjection(emptyList())
        val invalid=listOf(posts.drop(1),posts+posts.first().copy(id="other",canonicalUrl="https://x.com/workshop/status/81000002"),
            listOf(posts.first().copy(body="This was not approved."),posts.last()),
            listOf(posts.first().copy(media=listOf(Media("https://other.example.org/changed.jpg"))),posts.last()))
        for(values in invalid) {
            try {projection.groupWithCurrentMedia(posts){CrossPostMediaSnapshot(values){_,_->true}};fail("A media refresh must keep the exact source facts")}
            catch(_:IllegalArgumentException){}
        }
        var published=false
        val job=launch(Dispatchers.Default) {
            val context=currentCoroutineContext()
            projection.groupWithCurrentMedia(posts){context[Job]!!.cancel();CrossPostMediaSnapshot(posts){_,_->true}}
            published=true
        }
        job.join();assertTrue(job.isCancelled);assertFalse(published)
        assertEquals(1,parity(projection,posts,same=true).size)
        assertEquals(2,parity(projection,posts,same=false).size)
        assertNull(CrossPostFusion.preparationStats())
    }
}
