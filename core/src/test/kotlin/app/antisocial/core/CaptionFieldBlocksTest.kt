/* anti social, GPL-3.0-or-later. Synthetic, verbatim source-field paths. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class CaptionFieldBlocksTest {
    private val body="BUILDING. A. BETTER. NEIGHBORHOOD."
    private val first=Post("100",Platform.X,"https://x.com/publicstudio/status/100","Public studio",body,"Workshop film",publishedAt=1_800_000_000_000)
    private val second=first.copy(id="200",platform=Platform.BLUESKY,canonicalUrl="https://bsky.app/profile/publicstudio.test/post/fixture",body=body.replace(" ",""),title="",publishedAt=first.publishedAt!!+1_000)
    private val other=first.copy(id="300",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/publicstudio/posts/300",body="See how the public workshop came together.",title="",publishedAt=first.publishedAt!!+2_000)
    @Test fun aSourceTitleStaysAttributedWhileItsSharedBodyIsReadOnce() {
        val posts=listOf(first,second,other);val fusion=CaptionFieldBlocks.compose(posts)!!
        assertEquals(listOf("Workshop film",body,other.body),fusion.segments.map {it.text})
        assertEquals(setOf(CrossPosts.originalKey(first)),fusion.segments.first().originalKeys)
        assertEquals(setOf(Platform.X,Platform.BLUESKY),fusion.segments[1].platforms)
        for(post in posts) {
            val actual=fusion.segments.filter {CrossPosts.originalKey(post)in it.originalKeys}.joinToString("\n\n"){it.text}.filterNot(Char::isWhitespace)
            assertEquals(PostPresentation.content(post).filterNot(Char::isWhitespace),actual)
        }
        assertEquals(fusion,CaptionFieldBlocks.compose(posts.reversed()))
        assertEquals(2,CrossPosts.group(listOf(first,second),emptyList()).size)
    }
    @Test fun distinctTitlesRemainCoupledToTheirOriginalBodyAndTitleOnlyEditionsSurvive() {
        val changed=second.copy(title="Another supplied title")
        val titleOnly=other.copy(body="",title="A shorter original caption.")
        val fusion=CaptionFieldBlocks.compose(listOf(first,changed,titleOnly))!!
        assertEquals(listOf(first.title,changed.title,body,titleOnly.title),fusion.segments.map {it.text})
        assertEquals(setOf(CrossPosts.originalKey(changed)),fusion.segments[1].originalKeys)
    }
    @Test fun changedBodiesUrlsAndPreviewsCannotDisappearThroughFieldCompression() {
        assertNull(CaptionFieldBlocks.compose(listOf(first,second.copy(body=body.replace("BETTER","DIFFERENT")))))
        assertNull(CaptionFieldBlocks.compose(listOf(first,second.copy(preview=true))))
        assertNull(CaptionFieldBlocks.compose(listOf(first.copy(body=body+" https://t.co/First"),second.copy(body=body+" https://t.co/Second"))))
        assertNull(CaptionFieldBlocks.compose(listOf(first.copy(body="BUILDING. A. BETTER. COMMUNITY."),second.copy(body="BUILDING.A.BETTER.COMMUNITY."))))
    }
    private val accounts=listOf(
        Follow("x",Target(Platform.X,TargetKind.PROFILE,"publicstudio","https://x.com/publicstudio"),"Public studio",personId="studio"),
        Follow("bs",Target(Platform.BLUESKY,TargetKind.PROFILE,"publicstudio.test","https://bsky.app/profile/publicstudio.test"),"Public studio",personId="studio"))
    @Test fun aLiteralBodyUsesExistingLinkedCaptionRulesWhileASuppliedTitleSurvives() {
        val posts=listOf(first,second).map {it.copy(media=listOf(Media("https://media.example.org/${it.platform.id}.mp4","video",durationMs=120_000)))}
        val members=posts.associate {CrossPosts.originalKey(it)to setOf(if(it.platform==Platform.X)"x"else "bs")}
        assertEquals(2,CrossPosts.group(posts,emptyList()).size)
        for(order in listOf(posts,posts.reversed())) {
            val group=CrossPosts.group(order,accounts,members).single()
            assertEquals(posts.toSet(),group.posts.toSet())
            assertNotNull(CaptionFieldBlocks.compose(group.posts))
            assertTrue(CrossPosts.fieldCaptionComposition(posts[0],posts[1]))
        }
    }
    @Test fun aTitleCannotHideClaimsUrlsOrCompetingHeadingsAndGenericBodiesStaySeparate() {
        val a=first.copy(media=listOf(Media("https://media.example.org/first.mp4","video")))
        val b=second.copy(media=listOf(Media("https://media.example.org/second.mp4","video")))
        for(changed in listOf(a.copy(title="Not approved"),a.copy(title="This claim is false"),a.copy(title="Is this accurate?"),a.copy(title="Workshop 12"),a.copy(title="Details https://other.example.org/item"),
            a.copy(body=a.body.replace("BETTER","DIFFERENT")),a.copy(publishedAt=second.publishedAt!!-601_000),a.copy(media=emptyList()),a.copy(preview=true),a.copy(parentId="parent"))) {
            assertFalse(CrossPosts.fieldCaptionComposition(changed,b))
            val members=listOf(changed,b).associate {CrossPosts.originalKey(it)to setOf(if(it.platform==Platform.X)"x"else "bs")}
            assertEquals(2,CrossPosts.group(listOf(changed,b),accounts,members).size)
        }
        assertFalse(CrossPosts.fieldCaptionComposition(a,b.copy(title="A different heading")))
        assertFalse(CrossPosts.fieldCaptionComposition(a.copy(body="Hello there."),b.copy(body="Hello there.")))
    }

}
