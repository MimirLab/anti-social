/* anti social, GPL-3.0-or-later. Synthetic acquisition and nomination controls. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class CollectionAdmissionRegressionTest {
    private val at=1_800_000_000_000L
    private val shared="The community workshop has published its complete equipment inventory. Residents can inspect the original diagrams, compare every connector, read the repair instructions, and check the materials before bringing their damaged tools to the next public session."
    private fun post(id:String,platform:Platform=Platform.X,body:String=shared)=Post(id,platform,
        if(platform==Platform.X)"https://x.com/workshop/status/$id"else "https://www.instagram.com/p/Copy$id/","Workshop",body,publishedAt=at,
        media=listOf(Media("https://images.example/workshop.jpg")))
    @Test fun aRedundantGeneratedHeadingCannotBeatTheSameCaptionWithACompleteGallery() {
        val rich=post("123").copy(media=(1..5).map {Media("https://images.example/picture-$it.jpg")})
        val thin=rich.copy(title=shared.take(120)+"...",media=rich.media.take(1))
        assertEquals(PostPresentation.content(rich),PostPresentation.content(thin))
        for(input in listOf(listOf(rich,thin),listOf(thin,rich)))assertEquals(rich,CrossPosts.group(input,emptyList()).single().posts.single())
    }
    @Test fun unrelatedAuxiliaryBucketsCannotEraseADistinctiveSharedPassageCandidate() {
        val a=post("200",body="Office update.\n\n$shared\n\nBring gloves.").copy(links=listOf("https://docs.example/one","https://docs.example/two"))
        val b=post("201",Platform.INSTAGRAM,"Volunteer update.\n\n$shared\n\nBring batteries.")
        assertEquals(1,CrossPosts.group(listOf(a,b),emptyList()).size)
        val noise=(0..139).map {i->post((1000+i).toString(),body="Unrelated notice number $i with a separate subject about train timetables.").copy(
            media=emptyList(),links=listOf(if(i<70)"https://docs.example/one"else "https://docs.example/two"))}
        for(input in listOf(listOf(a,b)+noise,(listOf(a,b)+noise).reversed())) {
            val groups=CrossPosts.group(input,emptyList())
            assertEquals(setOf(a.identityKey,b.identityKey),groups.single {g->g.posts.any {it.identityKey==a.identityKey}}.posts.map {it.identityKey}.toSet())
            assertEquals(noise.size+1,groups.size)
        }
    }
    @Test fun punctuationVariantsOfAnAdmittedShortCaptionRemainCompleteSourceBlocks() {
        val a=post("300",body="Workshop repairs, ready for inspection.")
        val b=post("301",Platform.INSTAGRAM,"Workshop repairs ready for inspection.")
        val text=CrossPostFusion.compose(listOf(a,b))!!
        assertEquals(listOf(a.body,b.body),text.segments.map {it.text})
        assertEquals(setOf(a.identityKey),text.segments.first().originalKeys)
        assertEquals(setOf(b.identityKey),text.segments.last().originalKeys)
        assertNull(CrossPostFusion.compose(listOf(a,b.copy(body="Workshop repairs ready for inspection?"))))
        val tonal=listOf(a.copy(body="Workshop repairs are ready!"),b.copy(body="Workshop repairs are ready."))
        assertEquals(listOf(tonal.first().body),CrossPostFusion.compose(tonal)!!.segments.map {it.text})
    }
}
