/* anti social, GPL-3.0-or-later. Literal source paths on the Android runtime. */
package app.antisocial.android

import app.antisocial.core.*
import org.junit.Assert.*
import org.junit.Test

class CollectedCaptionNativeTest {
    @Test fun shortCaptionPrefixesPreserveEveryInlineCaseSensitiveDestination() {
        val heading="The public laboratory demonstrates a remarkable new telescope workshop"
        val posts=listOf(
            Post("123456789",Platform.X,"https://x.com/publiclab/status/123456789","Public lab","$heading [source](HTTPS://example.org/ExactCase) today."),
            Post("987654321",Platform.INSTAGRAM,"https://www.instagram.com/p/987654321/","Public lab","$heading [source](HTTPS://example.org/exactcase) today.")
        )
        val fusion=requireNotNull(collectedPostFusion(posts))
        for(post in posts) {
            val original=CrossPosts.originalKey(post)
            val projected=fusion.weave!!.joinToString(" "){run->run.variants.single {original in it.originalKeys}.text}.replace(Regex("\\s+")," ").trim()
            assertEquals(PostPresentation.content(post),projected)
        }
        assertEquals(1,Regex(Regex.escape(heading)).findAll(fusion.segments.single().text).count())
        assertTrue(fusion.segments.single().text.contains("/ExactCase"));assertTrue(fusion.segments.single().text.contains("/exactcase"))
        val caption="The public laboratory demonstrates a remarkable new telescope workshop for every visitor today."
        val formatting=listOf(posts.first().copy(body=caption),posts.last().copy(body=caption.replace("public laboratory","public\u00a0laboratory").replace("today.","today!")))
        val preserved=requireNotNull(collectedPostFusion(formatting))
        assertEquals(1,preserved.segments.size);assertEquals(caption,preserved.segments.single().text)
    }
}
