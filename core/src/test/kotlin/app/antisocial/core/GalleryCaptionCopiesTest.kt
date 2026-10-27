/* anti social, GPL-3.0-or-later. Synthetic gallery and caption regressions. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class GalleryCaptionCopiesTest {
    private val heading="REPAIRS FROM THE COMMUNITY WORKSHOP IN WESTPORT"
    private val intro="Work will continue."
    private val at=1_800_000_000_000L
    private fun gallery(id:String="GalleryA")=Post(id,Platform.INSTAGRAM,"https://www.instagram.com/p/$id","Workshop","$intro $heading:",publishedAt=at,
        media=(1..3).map {Media("https://images.example/picture-$it.jpg")})
    private fun part()=Post("123",Platform.X,"https://x.com/workshop/status/123","Workshop",heading+" https://t.co/AbCd123",publishedAt=at+3000,
        media=listOf(gallery().media[1]))
    @Test fun provenGalleryCollectsItsShorterHeadingAndAttributesTheExtraSentence() {
        val full=gallery();val short=part();val before=listOf(full,short)
        for(input in listOf(before,before.reversed())) {
            val groups=CrossPosts.group(input,emptyList())
            assertEquals(1,groups.size);assertEquals(before.toSet(),groups.single().posts.toSet())
            assertTrue(CrossPosts.galleryCollection(groups.single().posts))
            val fusion=CrossPostFusion.composeMedia(groups.single().posts)!!
            assertEquals(listOf(intro,heading),fusion.segments.map {it.text})
            assertEquals(setOf(Platform.INSTAGRAM),fusion.segments.first().platforms)
            assertEquals(setOf(Platform.INSTAGRAM,Platform.X),fusion.segments.last().platforms)
            assertEquals(setOf(full.identityKey),fusion.segments.first().originalKeys)
            assertEquals(before.map {it.identityKey}.toSet(),fusion.segments.last().originalKeys)
        }
        assertEquals(heading+" https://t.co/AbCd123",short.body)
    }
    @Test fun multipleGalleryEditionsAndTheirShorterCaptionFormOneCompleteCollection() {
        val instagram=gallery();val fb=instagram.copy(id="456",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/workshop/posts/456")
        val posts=listOf(part(),fb,instagram)
        for(input in listOf(posts,posts.reversed())) {
            val group=CrossPosts.group(input,emptyList()).single()
            assertEquals(posts.toSet(),group.posts.toSet())
            val fusion=CrossPostFusion.composeMedia(group.posts)!!
            assertEquals(setOf(Platform.FACEBOOK,Platform.INSTAGRAM),fusion.segments.first().platforms)
            assertTrue(fusion.segments.last().shared)
        }
    }
    @Test fun anAlreadyCollectedAnnouncementSharesGallerySlotsAcrossItsEditions() {
        val instagram=gallery().copy(media=gallery().media.take(2))
        val facebook=instagram.copy(id="456",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/workshop/posts/456",
            media=listOf(Media("https://images.example/third.jpg"),Media("https://images.example/fourth.jpg")))
        val child=part().copy(media=listOf(instagram.media[1],facebook.media[0]))
        val follows=listOf(Follow("ig",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"workshop","https://www.instagram.com/workshop"),"Workshop",personId="workshop"),
            Follow("fb",Target(Platform.FACEBOOK,TargetKind.PROFILE,"workshop","https://www.facebook.com/workshop"),"Workshop",personId="workshop"),
            Follow("x",Target(Platform.X,TargetKind.PROFILE,"workshop","https://x.com/workshop"),"Workshop",personId="workshop"))
        val membership=mapOf(instagram.identityKey to setOf("ig"))
        for(input in listOf(listOf(instagram,facebook,child),listOf(child,facebook,instagram))) {
            val grouped=CrossPosts.group(input,follows,membership)
            assertEquals("Two admitted gallery editions are one announcement, not competing roots",1,grouped.size)
            assertEquals(input.toSet(),grouped.single().posts.toSet())
        }
        val unrelated=gallery("Other").copy(body="Another workshop opens. $heading:",media=child.media)
        assertEquals("An independent gallery still makes the child ambiguous",3,
            CrossPosts.group(listOf(instagram,facebook,child,unrelated),follows,membership).size)
    }
    @Test fun overlappingGalleryEditionsHaveASelfContainedUnionForPresentationAndSaving() {
        val first=gallery().copy(media=gallery().media.take(2))
        val second=first.copy(id="456",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/workshop/posts/456",
            media=listOf(first.media[1],Media("https://images.example/third.jpg")))
        val child=part().copy(media=listOf(first.media[0],second.media[1]))
        val posts=listOf(first,second,child)
        assertTrue(CrossPosts.galleryCollection(posts))
        assertTrue(CrossPosts.sameContentCopies(posts))
        val fusion=CrossPostFusion.composeMedia(posts)!!
        assertEquals(listOf(intro,heading),fusion.segments.map {it.text})
        assertFalse(CrossPosts.galleryCollection(listOf(first,second.copy(body="Another workshop opens. $heading:"),child)))
    }
    @Test fun pictureProofTimingAndWholeCaptionBoundariesRemainMandatory() {
        val full=gallery();val short=part()
        for(other in listOf(short.copy(media=listOf(Media("https://images.example/different.jpg"))),
            short.copy(body="COMMUNITY WORKSHOP"),short.copy(body="COMMUNITY WORKSHOP IN WESTPORT"),
            short.copy(body=heading.replace("REPAIRS","FAILURES")),short.copy(body=heading+"?"),
            short.copy(publishedAt=at+7*3600000),short.copy(preview=true)))
            assertEquals(other.body,2,CrossPosts.group(listOf(full,other),emptyList()).size)
    }
    @Test fun extraContradictionsNumbersReferencesAndMeaningfulSymbolsDoNotUseExcerptAdmission() {
        for(extra in listOf("This is false.","Work will not continue.","There are 20 repairs.","Is this true?","A correction.","Visit https://example.org/other.","A statement 🇺🇸.")) {
            val full=gallery().copy(body="$extra $heading:")
            assertEquals(extra,2,CrossPosts.group(listOf(full,part()),emptyList()).size)
        }
        assertEquals(2,CrossPosts.group(listOf(gallery().copy(body="Work will continue with $heading:"),part()),emptyList()).size)
    }
    @Test fun anAmbiguousPictureCannotSelectBetweenTwoIndependentGalleries() {
        val other=gallery("GalleryB").copy(body="Another workshop opens. $heading:",media=listOf(gallery().media[1],Media("https://images.example/extra.jpg")))
        assertEquals(3,CrossPosts.group(listOf(gallery(),other,part()),emptyList()).size)
    }
    @Test fun aLinkedShortCaptionWithAnOpaqueCampaignLinkCanBePresentedWithoutDroppingMediaEditions() {
        val first=part().copy(body="Workshop repairs are ready. 🛠 https://t.co/AbCd123",media=listOf(Media("https://video.example/one.mp4","video","https://images.example/poster.jpg")))
        val second=gallery().copy(body="Workshop repairs are ready. 🛠",media=listOf(Media("https://images.example/poster.jpg")),links=listOf("https://images.example/poster.jpg"),publishedAt=first.publishedAt)
        val accounts=listOf(Follow("x",Target(Platform.X,TargetKind.PROFILE,"workshop","https://x.com/workshop"),"Workshop",personId="workshop"),
            Follow("ig",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"workshop","https://www.instagram.com/workshop"),"Workshop",personId="workshop"))
        val group=CrossPosts.group(listOf(first,second),accounts,mapOf(second.identityKey to setOf("ig"))).single()
        val fusion=CrossPostFusion.compose(group.posts)!!
        assertEquals("Workshop repairs are ready. 🛠",fusion.segments.single().text)
        assertEquals(setOf(first.identityKey,second.identityKey),fusion.segments.single().originalKeys)
        assertEquals(listOf(first,second).toSet(),group.posts.toSet())
        assertNull(CrossPostFusion.compose(listOf(first,second.copy(body="Workshop repairs are NOT ready. 🛠"))))
        assertNull(CrossPostFusion.compose(listOf(first.copy(body="Workshop repairs are ready. 🛠 https://example.org/One"),second)))
    }
}
