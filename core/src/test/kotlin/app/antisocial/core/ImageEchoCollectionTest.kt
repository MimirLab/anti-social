/* AntiSocial, GPL-3.0-or-later. A reshare never establishes account ownership. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ImageEchoCollectionTest {
    private val time=1_800_000_000_000L
    private val image=Media("https://images.example.org/photograph.jpg")
    private fun post(id:String,body:String)=Post(id,Platform.X,"https://x.com/publisher$id/status/$id","Publisher $id",body,publishedAt=time,media=listOf(image))
    private val original=post("100","NEXT STOP: KANSAS 🇺🇸")
    private val echo=post("200","🇺🇸🇺🇸🇺🇸").copy(publishedAt=time+6_000)

    @Test fun anImageReshareAddsItsPublisherAndDiscussionWithoutRepeatingTheCaption() {
        for(posts in listOf(listOf(original,echo),listOf(echo,original))) {
            val group=CrossPosts.group(posts,emptyList()).single()
            assertEquals(setOf(original,echo),group.posts.toSet())
            assertTrue(CrossPosts.sameContentCopies(group.posts))
            assertEquals(listOf(original.body),CrossPostFusion.composeMedia(group.posts)!!.segments.map {it.text})
        }
    }
    @Test fun anEmptyCaptionCanStillBeAProvenPictureReshare() {
        assertEquals(1,CrossPosts.group(listOf(original,echo.copy(body="")),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(original.copy(body=""),echo.copy(body="")),emptyList()).size)
    }
    @Test fun newWordsOppositeSymbolsQuestionsRepliesAndOtherPicturesStaySeparate() {
        for(other in listOf(echo.copy(body="A new firsthand observation."),echo.copy(body="🇨🇦"),echo.copy(body="???"),
            echo.copy(parentId=original.id),echo.copy(media=listOf(image.copy(url="https://images.example.org/other.jpg"))),
            echo.copy(publishedAt=time+6*3_600_000+1),echo.copy(publishedAt=null),echo.copy(links=listOf("https://example.org/new-evidence")),
            echo.copy(body="🇺🇸".repeat(5000)))) {
            assertEquals(other.body,2,CrossPosts.group(listOf(original,other),emptyList()).size)
        }
    }
    @Test fun ambiguousReusedPicturesDoNotChooseAnArbitraryCaption() {
        val different=post("300","Another event has different useful information. 🇺🇸")
        assertEquals(3,CrossPosts.group(listOf(original,different,echo),emptyList()).size)
    }
    @Test fun aReshareJoinsAnAlreadyCollectedCaptionWithoutClaimingItsOtherMediaIsIdentical() {
        val copy=original.copy(id="Other",platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/Other",media=listOf(image.copy(url="https://other.example.org/frame.jpg")))
        assertTrue(CrossPosts.imageEchoCollection(listOf(original,copy,echo)))
        assertEquals(listOf(original.body),CrossPostFusion.composeMedia(listOf(original,copy,echo))!!.segments.map {it.text})
    }
}
