/* anti social, GPL-3.0-or-later. Frozen media selection and late source credit. */
package app.antisocial.android

import app.antisocial.core.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentAttributionTest {
    private val image=Media("https://media.example.org/workshop.jpg")
    private fun post(platform:Platform,id:String,media:List<Media>)=
        Post(id,platform,"https://example.org/$id","Public workshop","Workshop announcement.",media=media)

    @Test fun aLateCopyCreditsTheShownPictureWithoutAdoptingItsOtherContent() {
        val x=post(Platform.X,"first",listOf(image))
        val ig=post(Platform.INSTAGRAM,"later",listOf(image,Media("https://media.example.org/unseen.jpg"))).copy(body="A new caption with further instructions.")
        val result=collectedAttachmentAttributions(listOf(x),listOf(x,ig))
        assertEquals(1,result.size);assertEquals(image,result.single().media)
        assertEquals(setOf(Platform.X,Platform.INSTAGRAM),result.single().platforms)
        assertEquals(setOf(x.identityKey,ig.identityKey),result.single().originalKeys)
        assertEquals("Workshop announcement.",x.body);assertEquals(2,ig.media.size)
        assertEquals(listOf(image),collectionPlan(listOf(x)).attachments.map {it.media})
    }

    @Test fun aDifferentPictureOrRecordingDoesNotBorrowTheShownSources() {
        val x=post(Platform.X,"first",listOf(image))
        val ig=post(Platform.INSTAGRAM,"later",listOf(Media("https://media.example.org/different.jpg")))
        assertEquals(setOf(Platform.X),collectedAttachmentAttributions(listOf(x),listOf(x,ig)).single().platforms)
        val first=post(Platform.X,"video-one",listOf(Media("https://media.example.org/one.mp4","video",preview=image.url)))
        val other=post(Platform.INSTAGRAM,"video-two",listOf(Media("https://media.example.org/two.mp4","video",preview=image.url)))
        val result=collectedAttachmentAttributions(listOf(first),listOf(first,other)).single()
        assertEquals(first.media.single(),result.media);assertEquals(setOf(Platform.X),result.platforms)
        assertTrue(result.previewPlatforms.isEmpty())
    }

    @Test fun aLateStillPreviewCreditsOnlyThePreviewOfTheRecording() {
        val x=post(Platform.X,"recording",listOf(Media("https://media.example.org/one.mp4","video",preview=image.url)))
        val ig=post(Platform.INSTAGRAM,"preview",listOf(image))
        val result=collectedAttachmentAttributions(listOf(x),listOf(x,ig)).single()
        assertEquals(setOf(Platform.X),result.platforms)
        assertEquals(setOf(Platform.INSTAGRAM),result.previewPlatforms)
        assertEquals("Video from X · preview from Instagram",result.sourceLabel)
    }

    @Test fun conflictingPixelsDoNotGainCreditFromAnEqualUrl() {
        fun frame(shift:Int)=requireNotNull(VisualFrames.encode(1.0,60.0,if(shift==0)0 else -1,if(shift==0)0 else -1,List(48){(it*17+shift)%256}))
        val x=post(Platform.X,"first",listOf(image.copy(attachmentFingerprint=frame(0))))
        val ig=post(Platform.INSTAGRAM,"later",listOf(image.copy(attachmentFingerprint=frame(127))))
        assertEquals(setOf(Platform.X),collectedAttachmentAttributions(listOf(x),listOf(x,ig)).single().platforms)
    }
}
