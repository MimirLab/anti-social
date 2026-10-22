package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class GalleryCollectionTest {
    private val time=1_792_547_260_000L
    private val text="Three repair examples from the workshop show the original connector, the replacement and the completed assembly."
    private fun post(id:String,body:String="",images:List<String>,platform:Platform=Platform.X)=Post(id,platform,
        if(platform==Platform.INSTAGRAM)"https://www.instagram.com/p/$id"else "https://x.com/workshop/status/$id",
        "Workshop",body,publishedAt=time,media=images.map {Media("https://images.example/$it.jpg","image")})
    private val gallery=post("Gallery",text,listOf("one","two","three"),Platform.INSTAGRAM)
    private val parts=listOf(post("100",text+" ⬇️",listOf("one")),post("101",images=listOf("two")),post("102",images=listOf("three")))
    @Test fun galleryCollectsCaptionedAndCaptionlessSingleImagePublicationsWithoutRepeatingText() {
        val originals=listOf(gallery)+parts
        for(input in listOf(originals,originals.reversed(),listOf(parts[1],gallery,parts[2],parts[0]))) {
            val groups=CrossPosts.group(input,emptyList())
            assertEquals(1,groups.size);assertEquals(originals.toSet(),groups.single().posts.toSet())
            assertTrue(CrossPosts.sameContentCopies(groups.single().posts))
            assertEquals(listOf(text),CrossPostFusion.composeMedia(groups.single().posts)!!.segments.map {it.text})
        }
    }
    @Test fun similarTemplatesDifferentImagesCaptionsAndOldCopiesDoNotBecomeGalleryParts() {
        for(other in listOf(parts[1].copy(media=listOf(Media("https://images.example/different.jpg"))),
            parts[1].copy(body="The connector has NOT been repaired."),parts[1].copy(body="What happened?"),
            parts[1].copy(publishedAt=time-7*60*60_000),parts[1].copy(publishedAt=null),parts[1].copy(preview=true))) {
            assertEquals(2,CrossPosts.group(listOf(gallery,other),emptyList()).size)
        }
        assertEquals("Without a complete gallery, different pictures remain separate",3,CrossPosts.group(parts,emptyList()).size)
    }
    @Test fun ambiguousSharedPictureCannotBridgeTwoDifferentGalleries() {
        val other=post("Other","Two photos from a different repair session show the old connector and a broken screen.",listOf("two","four"),Platform.INSTAGRAM)
        val groups=CrossPosts.group(listOf(gallery,other,parts[1]),emptyList())
        assertEquals(3,groups.size)
    }
    @Test fun comparisonCallbackOnlyQualifiesTheExactPictureInTheGallery() {
        val altered=parts.map {p->p.copy(media=p.media.map {it.copy(url=it.url.replace("images.example","resized.example"))})}
        assertEquals(4,CrossPosts.group(listOf(gallery)+altered,emptyList()).size)
        val grouped=CrossPosts.group(listOf(gallery)+altered,emptyList(),sameMedia={a,b->a.media.any {x->b.media.any {y->x.url.substringAfterLast('/')==y.url.substringAfterLast('/')}}})
        assertEquals(1,grouped.size)
    }
    @Test fun discoveringAPublisherGalleryReplyDoesNotSplitItBackOutOfTheCollection() {
        val child=parts[2].copy(parentId=parts[1].id)
        val originals=listOf(gallery,parts[0],parts[1],child)
        assertEquals(originals.toSet(),CrossPosts.group(originals,emptyList()).single().posts.toSet())
        assertTrue(CrossPosts.galleryCollection(originals))
        assertEquals(2,CrossPosts.group(listOf(gallery,child),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(gallery,child.copy(body="A reply with its own information"),parts[1]),emptyList()).size)
        val stranger=child.copy(canonicalUrl=child.canonicalUrl.replace("/workshop/","/stranger/"))
        assertEquals(2,CrossPosts.group(listOf(gallery,parts[1],stranger),emptyList()).size)
    }
}
