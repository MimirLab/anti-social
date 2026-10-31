/* anti social, GPL-3.0-or-later. Synthetic bounded collection regressions. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class GalleryWindowAdmissionTest {
    private val at=1_800_000_000_000L
    private val window=6*60*60_000L
    private val heading="REPAIRS FROM THE COMMUNITY WORKSHOP IN WESTPORT"
    private fun gallery(id:String="GalleryA",time:Long=at)=Post(id,Platform.INSTAGRAM,"https://www.instagram.com/p/$id/","Workshop","Work will continue. $heading:",publishedAt=time,
        media=(1..3).map {Media("https://images.example/workshop-$it.jpg")})
    private fun part(time:Long=at+3000)=Post("71001",Platform.X,"https://x.com/workshop/status/71001","Workshop",heading,publishedAt=time,media=listOf(gallery().media[1]))
    private fun distractions(count:Int=128,time:Long=at-2*86_400_000L)=List(count){i->
        Post("72000$i",Platform.FACEBOOK,"https://www.facebook.com/inventory/posts/72000$i","Inventory","Materials inventory record $i documents the separate training session and its replacement equipment.",publishedAt=time+i,
            media=(1..2).map {Media("https://images.example/inventory-$i-$it.jpg")})
    }
    private val follows=listOf(Follow("ig",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"workshop","https://www.instagram.com/workshop"),"Workshop",personId="workshop"),
        Follow("fb",Target(Platform.FACEBOOK,TargetKind.PROFILE,"workshop","https://www.facebook.com/workshop"),"Workshop",personId="workshop"),
        Follow("x",Target(Platform.X,TargetKind.PROFILE,"workshop","https://x.com/workshop"),"Workshop",personId="workshop"),
        Follow("inventory",Target(Platform.FACEBOOK,TargetKind.PROFILE,"inventory","https://www.facebook.com/inventory"),"Inventory"))
    private fun group(posts:List<Post>):List<PostGroup> = CrossPosts.group(posts,follows,posts.associate {p->p.identityKey to setOf(
        if(p.canonicalUrl.contains("/inventory/"))"inventory"else follows.single {it.target.platform==p.platform&&it.id!="inventory"}.id)})
    private fun family(groups:List<PostGroup>,posts:List<Post>)=groups.filter {g->g.posts.any {p->posts.any {it.identityKey==p.identityKey}}}
    private fun conserved(posts:List<Post>,groups:List<PostGroup>) {
        assertEquals(posts.size,groups.sumOf {it.posts.size});assertEquals(posts.toSet(),groups.flatMap {it.posts}.toSet())
    }
    @Test fun unrelatedHistoricalGalleriesCannotDisableAProvenCurrentFamily() {
        val pair=listOf(gallery(),part());val posts=pair+distractions();val raw=Gson().toJson(posts)
        for(input in listOf(posts,posts.reversed(),posts.shuffled(kotlin.random.Random(79)))) {
            val result=group(input);conserved(input,result)
            assertEquals(pair.toSet(),family(result,pair).single().posts.toSet())
            assertEquals(129,result.size)
        }
        assertEquals(raw,Gson().toJson(posts))
    }
    @Test fun inclusiveTimeWindowsWorkAcrossClockBucketsAndAtTheDateLimits() {
        val boundary=at/window*window+window-1000
        for((start,end) in listOf(at to at+window,boundary to boundary+2000,1L to window+1,Long.MAX_VALUE-window to Long.MAX_VALUE)) {
            val pair=listOf(gallery(time=start),part(end))
            val noise=distractions(time=if(start==1L)Long.MAX_VALUE-1000 else 1L)
            assertEquals("$start/$end",pair.toSet(),family(group(pair+noise),pair).single().posts.toSet())
        }
        val late=listOf(gallery(),part(at+window+1));assertEquals(2,family(group(late+distractions()),late).size)
    }
    @Test fun anOverfullRelevantWindowDoesNotBecomeAPartialUniqueMatch() {
        val pair=listOf(gallery(),part())
        for(input in listOf(pair+distractions(time=at),distractions(time=at).reversed()+pair.reversed())) {
            val result=group(input);conserved(input,result);assertEquals(2,family(result,pair).size)
        }
    }
    @Test fun ambiguousNearbyGalleriesRemainAmbiguousInALargeHistory() {
        val first=gallery();val child=part()
        val other=gallery("GalleryB").copy(body="Another session begins. $heading:",media=listOf(child.media.single(),Media("https://images.example/other-a.jpg"),Media("https://images.example/other-b.jpg")))
        val roots=listOf(first,child,other);val result=group(roots+distractions())
        conserved(roots+distractions(),result);assertEquals(3,family(result,roots).size)
    }
    @Test fun anUnexaminedRootCannotSilentlyDisappearFromAnOtherwiseSmallEdgeWindow() {
        val first=gallery();val child=part()
        val other=gallery("GalleryB",at+window-1).copy(body="Another session begins. $heading:",media=listOf(child.media.single(),Media("https://images.example/edge-a.jpg"),Media("https://images.example/edge-b.jpg")))
        val roots=listOf(first,child,other);val result=group(roots+distractions(129,at+2*window-1000))
        conserved(roots+distractions(129,at+2*window-1000),result);assertEquals(3,family(result,roots).size)
    }
    @Test fun anAlreadyCollectedCohortKeepsItsComplementaryPictureProof() {
        val first=gallery().copy(media=gallery().media.take(2))
        val second=first.copy(id="73001",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/workshop/posts/73001",media=listOf(Media("https://images.example/extra-a.jpg"),Media("https://images.example/extra-b.jpg")))
        val child=part().copy(media=listOf(first.media[1],second.media[0]));val roots=listOf(first,second,child)
        val result=group(roots+distractions());conserved(roots+distractions(),result)
        assertEquals(roots.toSet(),family(result,roots).single().posts.toSet())
        assertTrue("The admitted collection must retain a portable picture proof",CrossPosts.galleryCollection(roots))
        assertNotNull("Its common caption must remain composable",CrossPostFusion.composeMedia(roots))
    }
    @Test fun anOverfullRootCannotReceiveAChildFromAQuieterAdjacentWindow() {
        val full=gallery(time=at+window-1);val child=part(at)
        val posts=listOf(full,child)+distractions(129,at+2*window-1000)
        val result=group(posts);conserved(posts,result)
        assertEquals(2,family(result,listOf(full,child)).size)
    }
    @Test fun aLargeHistoryDoesNotExcuseChangedClaimsUnknownPicturesOrMissingDates() {
        val first=gallery();val child=part()
        for(other in listOf(child.copy(body=heading.replace("REPAIRS","CLOSURES")),child.copy(media=listOf(Media("https://images.example/unknown.jpg"))),
            child.copy(preview=true),child.copy(parentId="missing-parent"),child.copy(publishedAt=null),child.copy(publishedAt=0))) {
            val roots=listOf(first,other);val result=group(roots+distractions());conserved(roots+distractions(),result)
            assertEquals(other.toString(),2,family(result,roots).size)
        }
    }
}
