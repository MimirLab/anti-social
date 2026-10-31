/* anti social, GPL-3.0-or-later. Synthetic co-occurrence and preservation controls. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PortableGalleryBridgeTest {
    private val at=1_800_000_000_000L
    private val heading="REPAIRS FROM THE COMMUNITY WORKSHOP IN WESTPORT"
    private val intro="Work will continue."
    private fun image(name:String)=Media("https://images.example/$name.jpg")
    private fun full(id:String,photos:List<Media>,platform:Platform=Platform.INSTAGRAM)=Post(id,platform,
        if(platform==Platform.INSTAGRAM)"https://www.instagram.com/p/$id/"else "https://www.facebook.com/workshop/posts/$id",
        "Workshop","$intro $heading:",publishedAt=at,media=photos)
    private fun bridge(id:String,photos:List<Media>)=Post(id,Platform.X,"https://x.com/workshop/status/$id","Workshop",heading,publishedAt=at+1000,media=photos)
    private fun editions():List<Post> {
        val a=full("PortableA",listOf(image("a1"),image("a2")))
        val b=full("91001",listOf(image("b1"),image("b2")),Platform.FACEBOOK)
        return listOf(a,b,bridge("92001",listOf(a.media[1],b.media[0])))
    }
    @Test fun oneObservedBridgeMakesDisjointEditionsPortableWithoutAReaderIdentityDatabase() {
        val posts=editions();val raw=Gson().toJson(posts)
        assertFalse(CrossPosts.galleryCollection(posts.take(2)))
        for(input in listOf(posts,posts.reversed())) {
            assertTrue(CrossPosts.galleryCollection(input));assertTrue(CrossPosts.sameContentCopies(input))
            val fusion=requireNotNull(CrossPostFusion.composeMedia(input))
            assertEquals(listOf(intro,heading),fusion.segments.map {it.text})
            assertEquals(posts.take(2).map {it.identityKey}.toSet(),fusion.segments.first().originalKeys)
            assertEquals(posts.map {it.identityKey}.toSet(),fusion.segments.last().originalKeys)
        }
        assertEquals(raw,Gson().toJson(posts))
    }
    @Test fun oneCooccurrenceMustCoverEveryMissingGalleryPair() {
        val (a,b)=editions();val c=full("PortableC",listOf(image("c1"),image("c2")))
        val whole=bridge("92002",listOf(a.media[1],b.media[0],c.media[0]))
        assertTrue(CrossPosts.galleryCollection(listOf(a,b,c,whole)))
        assertFalse(CrossPosts.galleryCollection(listOf(a,b,c,whole.copy(media=whole.media.take(2)))))
        val first=bridge("92003",listOf(a.media[1],c.media[0]));val second=bridge("92004",listOf(c.media[0],b.media[0]))
        assertFalse("Separate paths are not one observed announcement",CrossPosts.galleryCollection(listOf(a,b,c,first,second)))
    }
    @Test fun aFullCaptionEditionCanItselfSupplyTheObservedCooccurrence() {
        val (a,b)=editions();val observed=full("PortableC",listOf(a.media[1],b.media[0]))
        assertTrue(CrossPosts.galleryCollection(listOf(a,b,observed)))
        assertNotNull(CrossPostFusion.composeMedia(listOf(a,b,observed)))
    }
    @Test fun aSharedHeadingOrOneStockPictureDoesNotReplaceCompleteCaptionAgreement() {
        val (a,b,part)=editions();val stock=image("stock")
        val first=a.copy(media=a.media+stock)
        val second=b.copy(body="Another session begins. $heading:",media=b.media+stock)
        assertFalse(CrossPosts.galleryCollection(listOf(first,second,part.copy(media=listOf(stock)))))
        val both=part.copy(media=listOf(a.media[1],b.media[0]))
        assertFalse(CrossPosts.galleryCollection(listOf(first,second,both)))
    }
    @Test fun unknownPicturesChangedFactsDestinationsParentsAndDatesRemainProtected() {
        val (a,b,part)=editions()
        for(other in listOf(part.copy(media=part.media+image("unknown")),part.copy(body="$heading?"),
            part.copy(parentId="missing"),part.copy(publishedAt=at+7*3_600_000L),part.copy(media=part.media+Media("https://video.example/clip.mp4","video"))))
            assertFalse(other.toString(),CrossPosts.galleryCollection(listOf(a,b,other)))
        for(other in listOf(b.copy(body="Work will not continue. $heading:"),b.copy(body="Work continues at 7. $heading:"),
            b.copy(links=listOf("https://workshop.example/new-information"))))
            assertFalse(other.toString(),CrossPosts.galleryCollection(listOf(a,other,part)))
    }
    @Test fun contraryDetailedPixelsDefeatAnEqualBridgeAddress() {
        val (a,b,part)=editions();val pixels=ByteArray(4096){100};val changed=pixels.copyOf().apply {this[512]=0}
        fun frame(bytes:ByteArray)=requireNotNull(VisualFrames.encode(1.25,40.0,0,0,List(48){100},bytes))
        val observed=b.copy(media=b.media.map {it.copy(visualFingerprint=frame(pixels))})
        val conflict=part.copy(media=listOf(part.media[0],part.media[1].copy(visualFingerprint=frame(changed))))
        assertFalse(CrossPosts.galleryCollection(listOf(a,observed,conflict)))
    }
}
