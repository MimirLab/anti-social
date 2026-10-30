/* anti social, GPL-3.0-or-later. Synthetic, literal gallery reminders. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class GalleryReminderCaptionTest {
    private val heading="REPAIRS FROM THE COMMUNITY WORKSHOP IN WESTPORT"
    private val at=1_800_000_000_000L
    private val photos=(1..3).map {Media("https://images.example/workshop-$it.jpg")}
    private fun gallery(introduction:String)=Post("GalleryReminder",Platform.INSTAGRAM,"https://www.instagram.com/p/GalleryReminder/","Workshop","$introduction $heading:",publishedAt=at,media=photos)
    private fun part()=Post("51001",Platform.X,"https://x.com/workshop/status/51001","Workshop",heading,publishedAt=at+3000,media=listOf(photos[1]))

    @Test fun anOpeningReminderDoesNotRequireAnInventedReplyOrMissingFullEdition() {
        for(introduction in listOf("Never forget the volunteers who restored this equipment.","Do not forget the volunteers who restored this equipment.")) {
            val full=gallery(introduction);val short=part()
            for(input in listOf(listOf(full,short),listOf(short,full))) {
                val group=CrossPosts.group(input,emptyList()).single()
                assertEquals(input.toSet(),group.posts.toSet())
                assertTrue(CrossPosts.galleryCollection(group.posts))
                assertNull(short.parentId);assertNull(short.provenance)
            }
        }
    }
    @Test fun theReminderAndEveryLiteralSourcePathRemainAttributed() {
        val introduction="Never forget the volunteers who restored this equipment."
        val full=gallery(introduction);val short=part();val input=listOf(full,short)
        val fusion=requireNotNull(CrossPostFusion.composeMedia(input))
        assertEquals(listOf(introduction,heading),fusion.segments.map {it.text})
        assertEquals(setOf(full.identityKey),fusion.segments.first().originalKeys)
        assertEquals(input.map {it.identityKey}.toSet(),fusion.segments.last().originalKeys)
        assertTrue(CrossPosts.sameContentCopies(input))
        assertEquals("$introduction $heading:",full.body);assertEquals(heading,short.body)
    }
    @Test fun otherNegationClaimsAndContentInsideTheReminderStayProtected() {
        for(introduction in listOf("Never approved the repair report.","Do not accept the repair report.",
            "Never forget that this is false.","Never forget that the work is not complete.",
            "Never forget this isn't confirmed.","Never forget this cannot be confirmed.","Never forget the repairs aren’t complete.",
            "Do not forget the work hasn't finished.","Never forget this won’t happen.",
            "Do not forget the correction.","Never forget there are 20 repairs.",
            "Never forget this question?","Never forget this 🇺🇸.","Never forget https://example.org/report.",
            "Never forgetful volunteers completed the work.","Never forget the workshop. A later correction.")) {
            val full=gallery(introduction)
            assertEquals(introduction,2,CrossPosts.group(listOf(full,part()),emptyList()).size)
            assertNull(introduction,CrossPostFusion.composeMedia(listOf(full,part())))
        }
    }
    @Test fun aReminderCannotReplacePictureEvidenceOrWholeCaptionBoundaries() {
        val full=gallery("Never forget the volunteers who restored this equipment.")
        for(other in listOf(part().copy(media=listOf(Media("https://images.example/other.jpg"))),
            part().copy(publishedAt=at+7*3_600_000),part().copy(preview=true),part().copy(body=heading.replace("REPAIRS","FAILURES"))))
            assertEquals(2,CrossPosts.group(listOf(full,other),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(full.copy(body="Never forget the work with $heading:"),part()),emptyList()).size)
        val other=full.copy(id="OtherGallery",canonicalUrl="https://www.instagram.com/p/OtherGallery/",body="Another workshop opens. $heading:")
        assertEquals(3,CrossPosts.group(listOf(full,other,part()),emptyList()).size)
    }
}
