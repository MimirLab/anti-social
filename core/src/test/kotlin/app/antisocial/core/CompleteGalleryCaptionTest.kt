/* anti social, GPL-3.0-or-later. Synthetic full-gallery and literal-caption controls. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class CompleteGalleryCaptionTest {
    private val at=1_800_000_000_000L
    private val heading="Gallery highlights."
    private val introduction="Community workshop."
    private val pictures=(1..3).map {Media("https://images.example/picture-$it.jpg")}
    private fun profile(platform:Platform)=when(platform) {
        Platform.X->"https://x.com/workshop"
        Platform.FACEBOOK->"https://www.facebook.com/workshop"
        else->"https://www.instagram.com/workshop"
    }
    private fun edition(platform:Platform,body:String=heading):Post {
        val id=when(platform){Platform.X->"900001";Platform.FACEBOOK->"900002";else->"StudioGalleryA"}
        val url=when(platform){Platform.X->profile(platform)+"/status/$id";Platform.FACEBOOK->profile(platform)+"/posts/$id";else->"https://www.instagram.com/p/$id"}
        return Post(id,platform,url,"Workshop",body,publishedAt=at,media=pictures,authorAccount=PostAuthorAccount(profile(platform)))
    }
    private val follows=listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM).map {p->
        Follow(p.id,Target(p,TargetKind.PROFILE,"workshop",profile(p)),"Workshop",personId="workshop")
    }
    private fun group(posts:List<Post>,sameMedia:(Post,Post)->Boolean={_,_->false})=CrossPosts.group(posts,follows,sameMedia=sameMedia)
    private fun pair()=listOf(edition(Platform.X,"$introduction $heading"),edition(Platform.INSTAGRAM))
    @Test fun completeAlbumsCollectATwoWordHeadingAndAttributeTheExtraSentence() {
        val posts=pair()+edition(Platform.FACEBOOK,"$introduction $heading")
        val before=Gson().toJson(posts)
        for(input in listOf(posts,posts.reversed(),posts.drop(1)+posts.first())) {
            val groups=group(input);assertEquals(1,groups.size);assertEquals(posts.toSet(),groups.single().posts.toSet())
            assertTrue("The complete proof must also work without a subscription database",CrossPosts.sameContentCopies(groups.single().posts))
            val fusion=requireNotNull(CrossPostFusion.composeMedia(groups.single().posts))
            assertEquals(listOf(introduction,heading),fusion.segments.map {it.text})
            assertEquals(posts.filter {it.platform!=Platform.INSTAGRAM}.map {it.identityKey}.toSet(),fusion.segments.first().originalKeys)
            assertEquals(posts.map {it.identityKey}.toSet(),fusion.segments.last().originalKeys)
        }
        assertEquals(before,Gson().toJson(posts))
    }
    @Test fun identicalShortHeadingsStillRequireTheWholeIndependentPictureSet() {
        val posts=listOf(edition(Platform.X),edition(Platform.INSTAGRAM))
        assertEquals(1,group(posts).size)
        assertEquals(listOf(heading),requireNotNull(CrossPostFusion.composeMedia(posts)).segments.map {it.text})
        assertTrue(CrossPosts.sameContentCopies(posts))
    }
    @Test fun aParagraphBoundaryCanDelimitAShortHeadingWithoutInventingASentence() {
        val posts=pair().map {if(it.platform==Platform.X)it.copy(body="Community workshop\n\n$heading")else it}
        assertEquals(1,group(posts).size)
        assertEquals(listOf("Community workshop",heading),requireNotNull(CrossPostFusion.composeMedia(posts)).segments.map {it.text})
        assertEquals(2,group(posts.map {if(it.platform==Platform.X)it.copy(body="Community workshop $heading")else it}).size)
    }
    @Test fun sourcePictureOrderMayDifferWithoutRewritingEitherInventory() {
        val posts=pair().map {if(it.platform==Platform.INSTAGRAM)it.copy(media=it.media.reversed())else it}
        val before=Gson().toJson(posts);assertEquals(1,group(posts).size);assertEquals(before,Gson().toJson(posts))
    }
    @Test fun aPartialSetOrRepeatedSlotCannotClaimACompleteAlbum() {
        val (full,short)=pair()
        for(media in listOf(short.media.take(2),listOf(short.media[0],short.media[0],short.media[2]),
            short.media.dropLast(1)+Media("https://images.example/different.jpg"))) {
            assertEquals(2,group(listOf(full,short.copy(media=media))).size)
            assertFalse(CrossPosts.sameContentCopies(listOf(full,short.copy(media=media))))
        }
    }
    @Test fun sharedPicturesDoNotEraseNumbersNegationQuestionsOrDestinations() {
        val short=edition(Platform.INSTAGRAM)
        for(extra in listOf("This is not ready.","There are 20 repairs.","A correction.","Visit https://studio.example/another.","An announcement 🇺🇸.")) {
            val full=edition(Platform.X,"$extra $heading")
            assertEquals(extra,2,group(listOf(full,short)).size)
            assertFalse(extra,CrossPosts.sameContentCopies(listOf(full,short)))
        }
        assertEquals(2,group(listOf(pair().first(),short.copy(body="Gallery highlights?"))).size)
    }
    @Test fun aSeparateFramingQuestionRetainsItsOwnAttributionAndTheSharedHeading() {
        val shared="$heading 🎃";val question="Community workshop?"
        val posts=listOf(edition(Platform.X,"$question $shared"),edition(Platform.INSTAGRAM,shared),edition(Platform.FACEBOOK,"$question $shared"))
        assertEquals(1,group(posts).size);assertTrue(CrossPosts.sameContentCopies(posts))
        val fusion=requireNotNull(CrossPostFusion.composeMedia(posts))
        assertEquals(listOf(question,shared),fusion.segments.map {it.text})
        assertEquals(setOf(Platform.X,Platform.FACEBOOK),fusion.segments.first().platforms)
        assertEquals(posts.map {it.identityKey}.toSet(),fusion.segments.last().originalKeys)
        assertEquals(2,group(listOf(posts.first(),posts[1].copy(body="Gallery highlights? 🎃"))).size)
    }
    @Test fun aGalleryCannotChooseBetweenTwoIncomparableIntroductions() {
        val first=edition(Platform.X,"$introduction $heading")
        val second=edition(Platform.FACEBOOK,"Volunteer exhibition. $heading")
        val short=edition(Platform.INSTAGRAM)
        for(input in listOf(listOf(first,second,short),listOf(short,second,first)))assertEquals(3,group(input).size)
    }
    @Test fun missingDatesRepliesPreviewsAndAvatarsCannotSupplyThisProof() {
        val (full,short)=pair()
        for(other in listOf(short.copy(publishedAt=null),short.copy(preview=true),short.copy(parentId="900000"),short.copy(avatar=short.media.first().url)))
            assertEquals(2,group(listOf(full,other)).size)
        assertEquals(1,group(listOf(full,short.copy(publishedAt=at+6*60*60_000L))).size)
        assertEquals(2,group(listOf(full,short.copy(publishedAt=at+6*60*60_000L+1))).size)
    }
    private fun frame(slot:Int):String {
        val pixels=ByteArray(4096){i->var n=(slot+1)*7919+(i%64/4)*31+(i/64/4)*1009;n=(n xor(n ushr 13))*1274126177;((n ushr 16 and 191)+32).toByte()}
        return requireNotNull(VisualFrames.encode(1.5,40.0,slot.toLong(),0,List(48){128},pixels))
    }
    @Test fun distinctDecodedImagesCanMatchAcrossUnrelatedDeliveryUrls() {
        val posts=pair().map {p->p.copy(media=p.media.mapIndexed {i,m->m.copy(url=m.url.replace("images.example",p.platform.id+".example"),attachmentFingerprint=frame(i))})}
        assertEquals(1,group(posts).size);assertTrue(CrossPosts.sameContentCopies(posts))
        assertNotNull(CrossPostFusion.composeMedia(posts))
    }
    @Test fun contraryDecodedPixelsOverrideEvenAnEqualUrlAndAnOverbroadCallback() {
        val posts=pair().mapIndexed {p,post->post.copy(media=post.media.mapIndexed {i,m->m.copy(attachmentFingerprint=frame(if(p==1&&i==1)9 else i))})}
        assertEquals(2,group(posts){_,_->true}.size);assertFalse(CrossPosts.sameContentCopies(posts){_,_->true})
    }
    @Test fun ambiguousRasterSlotsCannotManufactureABijectiveGalleryProof() {
        val posts=pair().map {p->p.copy(media=p.media.map {it.copy(attachmentFingerprint=frame(0))})}
        assertEquals(2,group(posts){_,_->true}.size)
        assertFalse(CrossPosts.sameContentCopies(posts){_,_->true})
    }
    @Test fun anOverbroadPictureCallbackCannotReplaceIndividualSlotEvidence() {
        val (full,short)=pair();val other=short.copy(media=short.media.map {it.copy(url=it.url.replace("images.example","unobserved.example"))})
        assertEquals(2,group(listOf(full,other)){_,_->true}.size)
        assertFalse(CrossPosts.sameContentCopies(listOf(full,other)){_,_->true})
    }
    @Test fun aCommonPhotoCannotBridgeDifferentCompleteAlbums() {
        val (full,short)=pair()
        val other=edition(Platform.FACEBOOK,"Volunteer exhibition. $heading").copy(media=full.media.drop(1)+Media("https://images.example/fourth.jpg"))
        val groups=group(listOf(full,short,other));assertEquals(2,groups.size)
        assertEquals(setOf(full.identityKey,short.identityKey),groups.single {it.posts.size==2}.posts.map {it.identityKey}.toSet())
    }
    @Test fun unrelatedOlderGalleriesCannotDisableTheCurrentShortHeading() {
        val history=(0..129).map {i->edition(Platform.X,"Archive gallery number $i.").copy(id="${910000+i}",canonicalUrl=profile(Platform.X)+"/status/${910000+i}",publishedAt=at-14*24*60*60_000L,
            media=pictures.map {it.copy(url=it.url.replace("picture-","archive-$i-"))})}
        val posts=pair()+history;val groups=group(posts)
        assertEquals(131,groups.size);assertEquals(posts.toSet(),groups.flatMap {it.posts}.toSet());assertEquals(posts.size,groups.sumOf {it.posts.size})
    }
    @Test fun anOverfullRelevantWindowPreservesUnexaminedOriginalsWithoutChoosingARoot() {
        val surrounding=(0..128).map {i->edition(Platform.X,"Archive gallery number $i.").copy(id="${920000+i}",canonicalUrl=profile(Platform.X)+"/status/${920000+i}",
            media=pictures.map {it.copy(url=it.url.replace("picture-","nearby-$i-"))})}
        val posts=pair()+surrounding;val groups=group(posts)
        assertEquals(posts.size,groups.size);assertEquals(posts.toSet(),groups.flatMap {it.posts}.toSet());assertEquals(posts.size,groups.sumOf {it.posts.size})
    }
}
