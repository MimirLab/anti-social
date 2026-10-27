package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class AttachmentCopiesRegressionTest {
    private fun frame(hash:Long,changed:Boolean=false)=VisualFrames.encode(1.5,40.0,hash,hash,List(48){100},ByteArray(4096){100}.apply {if(changed)this[512]=0})!!
    private val prose="The laboratory released a detailed view of a distant cloud where young stars illuminate the surrounding dust. The observation combines several instruments and records the same structures at different wavelengths, including the bright regions measured by "
    private fun editions():List<Post> {
        val at=1_790_000_000_000L
        val a=Post("one",Platform.INSTAGRAM,"https://www.instagram.com/p/AbCdEfGhijk/","Lab",prose+"@labaurorascope. #Science #Observing",publishedAt=at,media=listOf(Media("https://a.example/picture.jpg",visualFingerprint=frame(0))))
        val b=Post("two",Platform.X,"https://x.com/lab/status/123456789","Lab",prose+"@aurorascope. https://t.co/AbCdEf123",publishedAt=at+49*3600000L,media=listOf(Media("https://b.example/picture.jpg",visualFingerprint=frame(-1))))
        val c=Post("three",Platform.FACEBOOK,"https://www.facebook.com/lab/posts/987654321","Lab",prose+"our Aurora Scope Observatory.",publishedAt=b.publishedAt,media=listOf(Media("https://c.example/picture.jpg",visualFingerprint=frame(0))))
        return listOf(a,b,c,c.copy(id="four",canonicalUrl="https://www.facebook.com/100000000000001/posts/987654322"))
    }
    @Test fun detailedRasterProofSurvivesHashFlipsFromIndependentResizing() {
        assertTrue(VisualFrames.sameImage(frame(0),frame(-1)))
        assertFalse(VisualFrames.same(frame(0),frame(-1)))
        assertFalse(VisualFrames.same(frame(0),frame(-1,true)))
    }
    @Test fun longCreditVariantsWithMatchingPicturesRemainOneCardAcrossPublicationDays() {
        val posts=editions();val before=posts.toList()
        val groups=CrossPosts.group(posts,emptyList())
        assertEquals(1,groups.size);assertEquals(posts.toSet(),groups.single().posts.toSet())
        val fusion=CrossPostFusion.composeMedia(groups.single().posts)
        assertNotNull(fusion);assertEquals(1,fusion!!.segments.size)
        assertTrue(fusion.segments.single().text.endsWith("our Aurora Scope Observatory."))
        assertEquals("The primary presentation must not return repeated source paragraphs before its qualified alias route",fusion,CrossPostFusion.compose(groups.single().posts))
        assertEquals(before,posts)
    }
    @Test fun namingVariantsCannotSwallowDifferentClaimsDestinationsOrPictures() {
        val posts=editions();val first=posts[0];val other=posts[2]
        for(changed in listOf(other.copy(body="Not "+other.body),other.copy(body=other.body.replace("same structures","different structures")),
            other.copy(body=other.body+" https://different.example/notice"),other.copy(media=other.media.map {it.copy(visualFingerprint=frame(-1,true))})))
            assertEquals(2,CrossPosts.group(listOf(first,changed),emptyList()).size)
    }
    @Test fun knownClipRenditionsShareAnAssetKeyWhileDifferentClipsAndGenericQueriesDoNot() {
        val a="https://video.twimg.com/ext_tw_video/123456789/vid/720x400/a.mp4?tag=12"
        val b="https://video.twimg.com/ext_tw_video/123456789/pl/abc.m3u8"
        assertEquals(VideoAssetIdentity.key(a),VideoAssetIdentity.key(b))
        assertNotEquals(VideoAssetIdentity.key(a),VideoAssetIdentity.key(b.replace("123456789","123456788")))
        assertNull(VideoAssetIdentity.key("https://video.example/watch?id=one"))
        assertNull(VideoAssetIdentity.key("https://video.twimg.com.evil.example/ext_tw_video/123456789/vid/a.mp4"))
    }
}
