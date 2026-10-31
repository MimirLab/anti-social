/* anti social, GPL-3.0-or-later. Synthetic public-picture window regressions. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ImageEchoWindowAdmissionTest {
    private val at=1_800_000_000_000L
    private val window=6*60*60_000L
    private val image=Media("https://images.example/workshop.jpg")
    private fun caption(id:String="81001",time:Long=at)=Post(id,Platform.FACEBOOK,"https://www.facebook.com/workshop/posts/$id","Workshop","Workshop repairs start this Saturday at the public community centre.",publishedAt=time,media=listOf(image))
    private fun echo(id:String="82001",time:Long=at+1000)=Post(id,Platform.X,"https://x.com/reposter/status/$id","Reposter","",publishedAt=time,media=listOf(image))
    private fun distractions(count:Int=128,time:Long=at-2*86_400_000L)=List(count){i->echo("83000$i",time+i).copy(media=listOf(Media("https://images.example/unrelated-$i.jpg")))}
    private fun group(posts:List<Post>)=CrossPosts.group(posts,emptyList())
    private fun family(groups:List<PostGroup>,posts:List<Post>)=groups.filter {g->g.posts.any {p->posts.any {it.identityKey==p.identityKey}}}
    private fun conserved(posts:List<Post>,groups:List<PostGroup>) {
        assertEquals(posts.size,groups.sumOf {it.posts.size});assertEquals(posts.toSet(),groups.flatMap {it.posts}.toSet())
    }
    @Test fun unrelatedOldPictureOnlyPostsCannotDisableACurrentProvenReshare() {
        val roots=listOf(caption(),echo());val posts=roots+distractions()
        for(input in listOf(posts,posts.reversed())) {
            val result=group(input);conserved(input,result);assertEquals(roots.toSet(),family(result,roots).single().posts.toSet())
        }
    }
    @Test fun manyCaptionedTargetsDoNotReduceThePreviousSmallEchoCapacity() {
        val roots=listOf(caption(),echo())
        val targets=List(150){i->caption("84000$i",at+i).copy(body="Inventory number $i lists replacement parts for the separate inspection session.",media=listOf(Media("https://images.example/inventory-$i.jpg")))}
        val result=group(roots+targets);conserved(roots+targets,result)
        assertEquals(roots.toSet(),family(result,roots).single().posts.toSet())
    }
    @Test fun competingCaptionsRemainAmbiguousAcrossALargeRetainedHistory() {
        val roots=listOf(caption(),echo(),caption("81002").copy(body="Another workshop publishes different admission instructions for this session."))
        val result=group(roots+distractions());conserved(roots+distractions(),result);assertEquals(3,family(result,roots).size)
    }
    @Test fun anOverfullTargetMustNotDisappearFromAQuieterAdjacentEchoWindow() {
        val roots=listOf(caption(),echo(time=at),caption("81002",at+window-1).copy(body="Another workshop publishes different admission instructions for this session."))
        val noise=distractions(129,at+2*window-1000)
        for(input in listOf(roots+noise,(roots+noise).reversed())) {
            val result=group(input);conserved(input,result);assertEquals(3,family(result,roots).size)
        }
    }
    @Test fun aRelevantEchoOverflowStaysUnexaminedAndDoesNotLoseAnyOriginal() {
        val roots=listOf(caption(),echo());val posts=roots+distractions(time=at)
        val result=group(posts);conserved(posts,result);assertEquals(2,family(result,roots).size)
    }
    @Test fun inclusiveAndExtremeDatesStillUseTheWholeSixHourBound() {
        for((start,end)in listOf(at to at+window,1L to window+1,Long.MAX_VALUE-window to Long.MAX_VALUE)) {
            val roots=listOf(caption(time=start),echo(time=end));val noise=distractions(time=if(start==1L)Long.MAX_VALUE-1000 else 1L)
            assertEquals(roots.toSet(),family(group(roots+noise),roots).single().posts.toSet())
        }
        val late=listOf(caption(),echo(time=at+window+1));assertEquals(2,family(group(late+distractions()),late).size)
    }
    @Test fun aTooLargeCombinedCollectionFallsBackWithoutDroppingItsChildren() {
        val posts=listOf(caption())+List(65){i->echo("85000$i",at+i)}
        val result=group(posts);conserved(posts,result);assertEquals(posts.size,result.size)
    }
}
