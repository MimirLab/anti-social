/* anti social, GPL-3.0-or-later. Synthetic native-gallery observation regression. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class AttachmentCaptionsTest {
    private val at=1_800_000_000_000L
    private val text="Repairs will continue. FINISHED PROJECTS FROM THE COMMUNITY WORKSHOP"
    private val gallery=Post("Gallery",Platform.INSTAGRAM,"https://www.instagram.com/p/Gallery","Workshop","$text:",publishedAt=at,
        media=listOf(Media("https://images.example/one.jpg"),Media("https://images.example/two.jpg")))
    private val child=Post("123",Platform.X,"https://x.com/workshop/status/123","Workshop","$text ⬇️",publishedAt=at-60_000,
        media=listOf(Media("https://images.example/another-rendition.jpg")))
    private val follows=listOf(Follow("ig",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"workshop","https://www.instagram.com/workshop"),"Workshop",personId="workshop"),
        Follow("x",Target(Platform.X,TargetKind.PROFILE,"workshop","https://x.com/workshop"),"Workshop",personId="workshop"))
    private fun groups(a:Post,b:Post,accounts:List<Follow> = follows)=CrossPosts.group(listOf(a,b),accounts,mapOf(a.identityKey to setOf("ig")))
    @Test fun aLinkedGalleryAndItsShortLayoutMarkedEditionUseOneCardBeforePixelLoading() {
        val grouped=groups(gallery,child).single()
        assertEquals(setOf(gallery,child),grouped.posts.toSet())
        val fusion=CrossPostFusion.compose(grouped.posts)!!
        assertEquals(listOf(gallery.body),fusion.segments.map {it.text})
        assertEquals(3,grouped.posts.sumOf {it.media.size}) // collection does not assert image equality
    }
    @Test fun shortLayoutMarksNeedAGalleryAndDoNotEraseClaimsOrUnlinkedAttribution() {
        assertEquals(2,groups(gallery,child,emptyList()).size)
        assertEquals(2,groups(gallery.copy(media=gallery.media.take(1)),child).size)
        for(body in listOf(child.body.replace("continue","not continue"),child.body.replace("FINISHED","CANCELLED"),"$text ⬆️","$text: ❌"))
            assertEquals(2,groups(gallery,child.copy(body=body)).size)
    }
}
