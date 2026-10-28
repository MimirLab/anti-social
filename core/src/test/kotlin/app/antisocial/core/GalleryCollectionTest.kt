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
    @Test fun aPublisherReplyCanKeepTheAlreadyProvenGalleryHeading() {
        val child=parts[2].copy(parentId=parts[1].id,body=text)
        val originals=listOf(gallery,parts[0],parts[1],child)
        for(input in listOf(originals,originals.reversed())) {
            assertEquals(originals.toSet(),CrossPosts.group(input,emptyList()).single().posts.toSet())
            assertTrue(CrossPosts.galleryCollection(input))
            assertEquals(listOf(text),CrossPostFusion.composeMedia(input)!!.segments.map {it.text})
        }
        assertEquals(2,CrossPosts.group(listOf(gallery,parts[1],child.copy(body="The connector has NOT been repaired.")),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(gallery,child),emptyList()).size)
    }
    @Test fun anExplicitSamePublisherParentCanQualifyDetailedPicturesInABlankGalleryReply() {
        fun detail(index:Int,changed:Boolean=false):String {
            val pixels=ByteArray(4096){i->(40+(i%64*7+i/64*11+index*30)%170).toByte()}
            if(changed)for(y in 20..23)for(x in 40..43)pixels[y*64+x]=((pixels[y*64+x].toInt()and 255)+60).coerceAtMost(255).toByte()
            return VisualFrames.encode(1.25,50.0,0x1234+index.toLong(),0x4567,List(48){70+(it*3+index*7)%140},pixels)!!
        }
        val full=gallery.copy(media=gallery.media.mapIndexed {i,m->m.copy(attachmentFingerprint=detail(i))})
        val parent=parts[0].copy(media=listOf(full.media.first()))
        val child=parts[1].copy(parentId=parent.id,media=full.media.drop(1).map {it.copy(url=it.url.replace("images.example","resized.example"))})
        val originals=listOf(full,parent,child)
        for(input in listOf(originals,originals.reversed())) {
            assertEquals(originals.toSet(),CrossPosts.group(input,emptyList()).single().posts.toSet())
            assertTrue(CrossPosts.galleryCollection(input))
        }
        // Detailed raster evidence gains no stand-alone post identity. The
        // explicit parent must exist and name the same actual publisher.
        for(other in listOf(child.copy(parentId=null),child.copy(parentId="missing"),
            child.copy(canonicalUrl=child.canonicalUrl.replace("/workshop/","/stranger/"))))
            assertEquals(2,CrossPosts.group(listOf(full,parent,other),emptyList()).size)
        val edited=child.copy(media=child.media.mapIndexed {i,m->if(i==0)m.copy(attachmentFingerprint=detail(1,true))else m})
        assertEquals(2,CrossPosts.group(listOf(full,parent,edited),emptyList(),sameMedia={_,_->true}).size)
    }
}
