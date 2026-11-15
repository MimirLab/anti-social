/* anti social, GPL-3.0-or-later. Synthetic detached references. */
package app.antisocial.core

import org.junit.Test
import kotlin.test.*

class MediaReferenceCaptionTest {
    private val post=Post("846001",Platform.X,"https://x.com/asworkshop/status/846001","Workshop","https://t.co/WorkshopLink",media=listOf(Media("https://images.fixture.invalid/workshop.png")))
    @Test fun aUrlOnlyMediaCaptionMovesIntactIntoTheExistingReferenceInventory() {
        assertTrue(PostBodyDetails.mediaReferencesOnly(post))
        val text=PostPresentation.content(post);val split=PostBodyDetails.split(text,allowStandalone=true)
        assertEquals("",split.body);assertEquals(text,split.details);assertEquals(text,split.originalText());assertEquals("https://t.co/WorkshopLink",post.body)
    }
    @Test fun authoredWordsMentionsAndHashtagsRemainTheCaption() {
        for(body in listOf("Workshop photos https://t.co/WorkshopLink","@asrepair https://t.co/WorkshopLink","#Workshop https://t.co/WorkshopLink","https://t.co/WorkshopLink — inspect this"))assertFalse(PostBodyDetails.mediaReferencesOnly(post.copy(body=body)))
    }
    @Test fun aMediaFreeLinkPostAndAnAvatarAreNotSilenced() {
        assertFalse(PostBodyDetails.mediaReferencesOnly(post.copy(media=emptyList())))
        assertFalse(PostBodyDetails.mediaReferencesOnly(post.copy(avatar=post.media.single().url)))
        assertFalse(PostBodyDetails.mediaReferencesOnly(post.copy(title="Workshop handbook")))
    }
}
