/* anti social, GPL-3.0-or-later. Presentation after independent admission. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AnnouncementPrefixTest {
    private val heading="The public laboratory demonstrates a remarkable new telescope workshop"
    private val tail="Visitors can build their own instruments this afternoon."
    private fun post(p:Platform,text:String,id:String="123456789")=Post(id,p,when(p){
        Platform.X->"https://x.com/publiclab/status/$id";Platform.FACEBOOK->"https://www.facebook.com/publiclab/posts/$id"
        Platform.INSTAGRAM->"https://www.instagram.com/p/$id/";else->"https://www.tiktok.com/@publiclab/video/$id"
    },"Public lab",text)
    private fun key(value:String)=value.replace(Regex("\\s+")," ").trim().lowercase(Locale.ROOT)
    private fun verify(posts:List<Post>):PostFusion {
        val fusion=requireNotNull(CrossPostFusion.composeCollectedPrefixes(posts))
        for(post in posts) {
            val original=CrossPosts.originalKey(post)
            val text=fusion.weave!!.joinToString(" "){run->run.variants.single {original in it.originalKeys}.text}
            assertEquals(key(PostPresentation.content(post)),key(text))
        }
        return fusion
    }
    @Test fun shortCaptionAndDecoratedFullerEditionsShareTheirHeadingAndRepeatedContinuationOnce() {
        val posts=listOf(post(Platform.X,"$heading 🔍💵 $tail 👻"),post(Platform.FACEBOOK,"$heading 🔍💵 $tail 👻"),
            post(Platform.INSTAGRAM,"$heading 🔍💵 $tail"),post(Platform.TIKTOK,"${heading.lowercase(Locale.ROOT)} 🔍"))
        val fusion=verify(posts)
        assertEquals(1,Regex(Regex.escape(heading),RegexOption.IGNORE_CASE).findAll(fusion.segments.single().text).count())
        assertEquals(1,Regex(Regex.escape(tail)).findAll(fusion.segments.single().text).count())
        assertTrue(fusion.weave!!.any {run->run.variants.any {it.text=="👻"&&it.platforms==setOf(Platform.X,Platform.FACEBOOK)}})
    }
    @Test fun sourceSpecificClausesStayCoupledAndSamePlatformVersionsKeepSeparatePaths() {
        val first=post(Platform.X,"$heading today. We approved application A.")
        val second=post(Platform.X,"$heading today. We denied application B.","987654321")
        val fusion=verify(listOf(first,second))
        val alternatives=fusion.weave!!.filterNot {it.shared}.flatMap {it.variants}.map {it.text}
        assertTrue(alternatives.any {it.contains("approved application A.")})
        assertTrue(alternatives.any {it.contains("denied application B.")})
        assertFalse(alternatives.any {it=="approved"||it=="denied"||it=="A."||it=="B."})
    }
    @Test fun anInsufficientSharedHeadingDoesNotBecomeAnExtraCompositionRoute() {
        val posts=listOf(post(Platform.X,"Public workshop opens today. Bring gloves."),post(Platform.INSTAGRAM,"Public workshop opens today. Bring scissors."))
        assertNull(CrossPostFusion.composeCollectedPrefixes(posts))
    }
    @Test fun inlineAndUppercaseSchemeUrlsKeepTheirCaseSensitiveSourcePaths() {
        val posts=listOf(post(Platform.X,"$heading [source](HTTPS://example.org/ExactCase) today."),
            post(Platform.INSTAGRAM,"$heading [source](HTTPS://example.org/exactcase) today."))
        val fusion=verify(posts)
        for(post in posts) {
            val original=CrossPosts.originalKey(post)
            val projected=fusion.weave!!.joinToString(" "){it.variants.single {v->original in v.originalKeys}.text}.replace(Regex("\\s+")," ").trim()
            assertEquals(PostPresentation.content(post),projected)
        }
        assertTrue(fusion.segments.single().text.contains("/ExactCase"));assertTrue(fusion.segments.single().text.contains("/exactcase"))
    }
}
