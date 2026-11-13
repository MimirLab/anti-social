/* anti social, GPL-3.0-or-later. Synthetic sources and image observations. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Test
import kotlin.test.*

class ShortPictureCaptionTest {
    private val at=1_790_000_000_000L
    private val picture=requireNotNull(VisualFrames.encode(.8,45.0,0x12345678,0x76543210,List(48){(it*17)%256},ByteArray(4096){((it*37+it/64)%256).toByte()}))
    private val platforms=listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM)
    private fun post(platform:Platform,body:String="TEAM READY 🛠",offset:Long=0)=Post("991",platform,when(platform) {
        Platform.X->"https://x.com/picturefixture/status/991"
        Platform.FACEBOOK->"https://www.facebook.com/picturefixture/posts/991"
        else->"https://www.instagram.com/p/PictureFixture991/"
    },"Picture fixture",body,publishedAt=at+offset,media=listOf(Media("https://media.fixture.invalid/${platform.id}.jpg",visualFingerprint=picture)))
    private fun follow(platform:Platform)=Follow(platform.id,Target(platform,TargetKind.PROFILE,"picturefixture",when(platform) {
        Platform.X->"https://x.com/picturefixture"
        Platform.FACEBOOK->"https://www.facebook.com/picturefixture"
        else->"https://www.instagram.com/picturefixture/"
    }),"Picture fixture",personId="linked-picture-publisher")
    private val follows=platforms.map(::follow)
    private val posts=platforms.mapIndexed {i,p->post(p,offset=i*60_000L)}
    private fun membership(values:List<Post>)=values.associate {CrossPosts.originalKey(it) to setOf(it.platform.id)}
    private fun groups(values:List<Post>,linked:Boolean=true)=CrossPosts.group(values,if(linked)follows else emptyList(),if(linked)membership(values)else emptyMap())

    @Test fun linkedTwoWordEditionsNeedTheSameObservedWholePicture() {
        val before=posts.map {Gson().toJson(it)}
        for(values in listOf(posts,posts.reversed(),posts.drop(1)+posts.first())) {
            val collected=groups(values)
            assertEquals(1,collected.size);assertEquals(posts.toSet(),collected.single().posts.toSet())
        }
        assertEquals(before,posts.map {Gson().toJson(it)})
    }
    @Test fun portablePictureCopiesAreContentEvidenceAndNeverInferAccountControl() {
        assertTrue(CrossPosts.sameContentCopies(posts))
        assertEquals(3,groups(posts,linked=false).size,"A tiny caption cannot link unknown publishers")
        val fusion=assertNotNull(CrossPostFusion.composeMedia(posts))
        assertEquals(1,fusion.segments.size);assertEquals("TEAM READY 🛠",fusion.segments.single().text)
        assertEquals(posts.map(CrossPosts::originalKey).toSet(),fusion.segments.single().originalKeys)
    }
    @Test fun missingOrCoarseObservationsCannotSupplyThePictureProof() {
        val coarse=picture.substringBeforeLast('|').replaceFirst("f2|","f1|")
        for(observation in listOf(null,coarse)) {
            val unknown=posts.map {p->p.copy(media=p.media.map {it.copy(visualFingerprint=observation)})}
            assertEquals(3,groups(unknown).size);assertFalse(CrossPosts.sameContentCopies(unknown))
        }
        val attachmentOnly=posts.map {p->p.copy(media=p.media.map {it.copy(attachmentFingerprint=it.visualFingerprint,visualFingerprint=null)})}
        assertEquals(3,groups(attachmentOnly).size)
        assertFalse(CrossPosts.sameContentCopies(attachmentOnly))
    }
    @Test fun editedPicturesGalleriesVideoCoversAndAvatarsStayOutsideTheRule() {
        val edited=requireNotNull(VisualFrames.encode(.8,45.0,0x12345678,0x76543210,List(48){(it*17)%256},ByteArray(4096){0}))
        val different=posts.take(2).mapIndexed {i,p->if(i==0)p else p.copy(media=p.media.map {it.copy(visualFingerprint=edited)})}
        assertEquals(2,groups(different).size)
        for(values in listOf(
            posts.map {p->p.copy(media=p.media+p.media.single().copy(url=p.media.single().url+"?other"))},
            posts.map {p->p.copy(media=p.media.map {it.copy(type="video",preview=it.url)})},
            posts.map {p->p.copy(avatar=p.media.single().url)}
        ))assertEquals(3,groups(values).size)
    }
    @Test fun changedClaimsSymbolsQuestionsDestinationsAndDistantDatesStaySeparate() {
        val first=posts.first()
        for(text in listOf("TEAM NOTREADY 🛠","TEAM READY2 🛠","TEAM READY 🇨🇦","TEAM READY? 🛠","Team Ready 🛠"))
            assertEquals(2,groups(listOf(first,posts[1].copy(body=text))).size)
        assertEquals(2,groups(listOf(first,posts[1].copy(links=listOf("https://different.fixture.invalid/notice")))).size)
        assertEquals(2,groups(listOf(first,posts[1].copy(publishedAt=at+600_001))).size)
        assertEquals(2,groups(listOf(first,posts[1].copy(publishedAt=null))).size)
        assertEquals(3,groups(posts.map {it.copy(body="READY 🛠")}).size)
    }
}
