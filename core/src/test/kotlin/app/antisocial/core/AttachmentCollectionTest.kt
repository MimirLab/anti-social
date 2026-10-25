/* anti social, GPL-3.0-or-later. Attachment editions retain their originals. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class AttachmentCollectionTest {
    private val time=1_800_000_000_000L
    private val caption="The community workshop continues to repair damaged equipment and make shared tools available to local residents. Completed repairs from last week in the main workshop"
    private fun url(n:Int,host:String="scontent-a.xx.fbcdn.net")="https://$host/v/t39.30808-6/123456789_${234567890+n}_345678901_n.jpg"
    private fun post(platform:Platform,id:String,text:String,images:List<String>)=Post(id,platform,
        if(platform==Platform.X)"https://x.com/workshop/status/$id"else "https://www.threads.com/@workshop/post/$id",
        "Workshop",text,publishedAt=time,media=images.map {Media(it)})
    private val follows=listOf(Platform.X,Platform.THREADS).map {platform->Follow(platform.id,
        Target(platform,TargetKind.PROFILE,"workshop",if(platform==Platform.X)"https://x.com/workshop"else "https://www.threads.com/@workshop"),"Workshop",personId="workshop")}

    @Test fun anAttachmentPointerAndAColonDoNotSplitTheSameCompleteCaption() {
        val first=post(Platform.X,"90001",caption+" ⬇️",listOf("https://images.example.org/one.jpg"))
        val second=post(Platform.THREADS,"GalleryOne",caption+":",listOf("https://other.example.org/two.jpg","https://other.example.org/three.jpg"))
        val posts=listOf(first,second)
        assertEquals(1,CrossPosts.group(posts,follows).size)
        val fusion=CrossPostFusion.compose(posts)!!
        assertEquals(1,fusion.segments.size);assertTrue(fusion.segments.single().text.startsWith(caption))
        assertEquals(posts.map(CrossPosts::originalKey).toSet(),fusion.segments.single().originalKeys)
    }

    @Test fun aCompleteGalleryCollectsItsIndividualPicturesAcrossMetaCdnAddresses() {
        val gallery=post(Platform.THREADS,"GalleryTwo",caption+":",(1..3).map {url(it)})
        val members=(1..3).map {n->post(Platform.X,"9001$n","",listOf(url(n,"scontent-b.cdninstagram.com")+"?stp=dst-jpg_e35_p1080x1080_tt6&oh=other"))}
        val posts=listOf(gallery)+members
        assertTrue(CrossPosts.galleryCollection(posts))
        assertEquals(1,CrossPosts.group(posts,follows).size)
        assertEquals(posts.toSet(),CrossPosts.group(posts,follows).single().posts.toSet())
    }

    @Test fun aNewPictureAndALookalikeCdnDoNotBecomeGalleryMembers() {
        val gallery=post(Platform.THREADS,"GalleryThree",caption+":",(1..3).map {url(it)})
        for(other in listOf(url(4),url(1,"scontent-a.xx.fbcdn.net.attacker.example"),url(1,"images.example.org"))) {
            val member=post(Platform.X,"90020","",listOf(other))
            assertFalse(CrossPosts.galleryCollection(listOf(gallery,member)))
            assertEquals(2,CrossPosts.group(listOf(gallery,member),follows).size)
        }
    }

    @Test fun aGalleryAndThreeCaptionedSinglePostsBecomeOneCollectionInEitherArrivalOrder() {
        val gallery=post(Platform.THREADS,"CaptionedGallery",caption+":",(1..3).map {url(it)})
        for(body in listOf(caption+":",caption+" ⬇️")) {
            val members=(1..3).map {n->post(Platform.X,"9002$n",body,listOf(url(n,"scontent-b.cdninstagram.com")))}
            for(posts in listOf(listOf(gallery)+members,(listOf(gallery)+members).reversed())) {
                val grouped=CrossPosts.group(posts,follows)
                assertEquals(1,grouped.size);assertEquals(posts.toSet(),grouped.single().posts.toSet())
                assertEquals(1,CrossPostFusion.compose(grouped.single().posts)!!.segments.size)
            }
        }
    }

    @Test fun aSimilarTemplateCannotOverrideDetailedEvidenceOfADifferentPicture() {
        val pixels=ByteArray(4096){if(it%2==0)20 else 200.toByte()}
        val changed=pixels.clone().apply {this[120]=110}
        val frame=VisualFrames.encode(1.25,40.0,0,0,List(48){100},pixels)!!
        val other=VisualFrames.encode(1.25,40.0,0,0,List(48){100},changed)!!
        assertTrue(VisualFrames.same(frame,other));assertFalse(VisualFrames.sameImage(frame,other))
        val gallery=post(Platform.THREADS,"DetailedGallery",caption+":",listOf("https://images.example.org/a.jpg","https://images.example.org/b.jpg")).let {p->p.copy(media=p.media.map {it.copy(visualFingerprint=frame)})}
        val member=post(Platform.X,"90040","",listOf("https://images.example.org/changed.jpg")).let {p->p.copy(media=p.media.map {it.copy(visualFingerprint=other)})}
        assertFalse(CrossPosts.galleryCollection(listOf(gallery,member),sameMedia={_,_->true}))
        assertEquals(2,CrossPosts.group(listOf(gallery,member),follows,sameMedia={_,_->true}).size)
    }

    @Test fun changedFlagsArrowsInsideTheClaimNumbersAndDestinationsStayDistinct() {
        val first=post(Platform.X,"90030",caption+" ⬇️",listOf(url(1)))
        for(body in listOf(caption+" 🇺🇸",caption.replace("repairs","9 repairs")+":",caption+" https://example.org/new",caption+" ⬆️")) {
            val other=post(Platform.THREADS,"OtherCard",body,listOf(url(1)))
            assertEquals(2,CrossPosts.group(listOf(first,other),follows).size)
        }
    }
}
