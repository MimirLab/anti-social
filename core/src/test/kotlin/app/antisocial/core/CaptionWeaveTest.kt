/* anti social, GPL-3.0-or-later. Synthetic captions, no captured reader material. */
package app.antisocial.core

import org.junit.Test
import org.junit.Assert.*

class CaptionWeaveTest {
    private val intro="Delivering the equipment for the community workshop. The published schedule covers the Saturday morning session and the afternoon demonstration."
    private val end="These are the results of the training program in action for everyone attending the public workshop."
    private fun post(platform:Platform,body:String)=Post("991",platform,when(platform){Platform.X->"https://x.com/weave_fixture/status/991";Platform.FACEBOOK->"https://www.facebook.com/weave_fixture/posts/991";else->"https://www.instagram.com/p/WeaveFixture991/"},"",body,publishedAt=1_790_000_000_000L,
        media=listOf(Media("https://media.example/shared-workshop.png")))
    private fun originals()=listOf(post(Platform.X,"$intro $end"),post(Platform.FACEBOOK,"$intro ${end.replace("the results","the direct results")}"),post(Platform.INSTAGRAM,"$intro ${end.replace("the results","the direct results")}"))
    private fun assertProjection(posts:List<Post>,fusion:PostFusion) {
        for(post in posts) {
            val id=CrossPosts.originalKey(post)
            val text=fusion.weave!!.joinToString(" "){run->run.variants.single {id in it.originalKeys}.text}.trim().replace(Regex("\\s+")," ")
            assertEquals(PostPresentation.content(post).replace(Regex("\\s+")," "),text)
        }
    }
    @Test fun localInsertionBecomesOneCaptionWithAttributedAlternativesAndLosslessProjections() {
        val posts=originals();assertTrue(CrossPosts.sameContentCopies(posts))
        val fusion=CrossPostFusion.composeMedia(posts)!!;assertProjection(posts,fusion)
        assertEquals(1,fusion.weave!!.count {!it.shared})
        val change=fusion.weave.single {!it.shared}
        assertEquals(setOf("results","direct results"),change.variants.map {it.text}.toSet())
        assertEquals(setOf(Platform.FACEBOOK,Platform.INSTAGRAM),change.variants.single {it.text=="direct results"}.platforms)
        assertEquals(1,Regex(Regex.escape(intro)).findAll(fusion.segments.single().text).count())
        assertEquals(1,CrossPosts.group(posts,emptyList()).size)
        assertEquals(fusion,CaptionWeave.compose(posts.reversed()))
    }
    @Test fun ordinaryCollectedCaptionsPreferTheLiteralWeaveOverRepeatedParagraphs() {
        val versions=originals().take(2).map {it.copy(media=emptyList())}
        for(posts in listOf(versions,versions.reversed())) {
            val fusion=CrossPostFusion.compose(posts)!!
            assertNotNull("An existing paragraph composition must not hide the compact wording edition",fusion.weave)
            assertProjection(posts,fusion)
            assertEquals(1,Regex(Regex.escape(intro)).findAll(fusion.segments.single().text).count())
            assertEquals(setOf("results","direct results"),fusion.weave!!.single {!it.shared}.variants.map {it.text}.toSet())
        }
    }
    @Test fun repeatedEquivalentVersionsStillPermitOneAttributedWordingEdit() {
        val versions=originals().mapIndexed {i,p->p.copy(media=emptyList(),body=if(i==1)p.body.replace(". These", ".\n\nThese")else p.body)}
        for(posts in listOf(versions,versions.reversed(),versions.drop(1)+versions.first())) {
            assertTrue("Equivalent FB/Instagram editions must not veto their shared wording change",CrossPosts.literalWordingCollection(posts))
            val fusion=CrossPostFusion.compose(posts)!!
            assertNotNull(fusion.weave);assertProjection(posts,fusion)
            val change=fusion.weave!!.single {!it.shared}
            assertEquals(setOf(Platform.FACEBOOK,Platform.INSTAGRAM),change.variants.single {it.text=="direct results"}.platforms)
            assertEquals(1,Regex(Regex.escape(intro)).findAll(fusion.segments.single().text).count())
        }
        for(changed in listOf("not results","results 27","replacement results")) {
            val different=versions.toMutableList().apply {set(1,this[1].copy(body=this[1].body.replace("direct results",changed)))}
            assertFalse("Changed claims stay outside this wording route",CrossPosts.literalWordingCollection(different))
        }
        assertFalse(CrossPosts.literalWordingCollection(listOf(versions[0],versions[1].copy(publishedAt=versions[0].publishedAt!!+600_001),versions[2])))
    }
    @Test fun paragraphLayoutCannotPreferWholeRepeatedCaptionsOverOneLocalInsertion() {
        val start="Delivering on the promise to keep our workshop open."
        val middle="Visitor attendance increased from 2023 to 2024."
        val end="These are the results of our community outreach program in action. 🛠"
        val x=post(Platform.X,"$start $middle $end").copy(media=emptyList())
        val fb=post(Platform.FACEBOOK,"$start\n\n$middle ${end.replace("the results","the direct results")}").copy(media=emptyList())
        val ig=post(Platform.INSTAGRAM,fb.body.replace("\n\n"," ")).copy(title="$start...",media=emptyList())
        for(posts in listOf(listOf(x,fb),listOf(x,fb,ig),listOf(ig,fb,x))) {
            val fusion=CrossPostFusion.compose(posts)!!
            assertNotNull("Common sentences must not repeat around one attributed word",fusion.weave)
            assertProjection(posts,fusion)
            assertEquals(1,Regex(Regex.escape(start)).findAll(fusion.segments.single().text).count())
            assertEquals(1,Regex(Regex.escape(middle)).findAll(fusion.segments.single().text).count())
        }
    }
    @Test fun aReplacementAndACompleteAddedSentenceBothRemainSourceText() {
        val x=post(Platform.X,"$intro $end")
        val fb=post(Platform.FACEBOOK,"$intro ${end.replace("training program","outreach program")}")
        val replacement=CaptionWeave.compose(listOf(x,fb))!!;assertProjection(listOf(x,fb),replacement)
        assertEquals(setOf("training","outreach"),replacement.weave!!.single {!it.shared}.variants.map {it.text}.toSet())
        val extended=post(Platform.FACEBOOK,"$intro $end Bring identification.")
        val addition=CaptionWeave.compose(listOf(x,extended))!!;assertProjection(listOf(x,extended),addition)
        assertTrue(addition.segments.single().text.contains("Bring identification."))
    }
    @Test fun conflictingClaimsRemainOutsideThisAdditionalDetector() {
        val posts=originals();val x=posts.first().copy(body=posts.first().body+" Admission is 12 dollars.")
        val fb=posts[1].copy(body=posts[1].body+" Admission is 21 dollars.")
        assertFalse(CrossPosts.sameContentCopies(listOf(x,fb)))
        assertFalse(CrossPosts.sameContentCopies(listOf(posts[0],posts[1].copy(body=posts[1].body.replace("results","not results")))))
        assertFalse(CrossPosts.sameContentCopies(listOf(posts[0].copy(body=posts[0].body+" https://one.example/info"),posts[1].copy(body=posts[1].body+" https://two.example/info"))))
    }
    @Test fun mediaProofTimingAndOrderedSubstantialOverlapRemainRequired() {
        val posts=originals()
        assertFalse(CrossPosts.sameContentCopies(posts.mapIndexed {i,p->p.copy(media=listOf(Media("https://media.example/unrelated-$i.png")))}))
        assertFalse(CrossPosts.sameContentCopies(listOf(posts[0],posts[1].copy(publishedAt=posts[0].publishedAt!!+600_001))))
        assertNull(CaptionWeave.compose(listOf(posts[0],post(Platform.FACEBOOK,"The same logo does not make this unrelated announcement a copy."))))
        assertNull(CaptionWeave.compose(listOf(posts[0],post(Platform.FACEBOOK,"$end $intro"))))
        assertNull(CaptionWeave.compose(listOf(posts[0],posts[1].copy(platform=Platform.X))))
    }
    @Test fun threeDistinctLocalVariantsRetainAllSourcesWithoutTransitiveLoss() {
        val posts=originals().mapIndexed {i,p->if(i==2)p.copy(body="$intro ${end.replace("the results","the immediate results")}")else p}
        val fusion=CaptionWeave.compose(posts)!!;assertProjection(posts,fusion)
        assertEquals(setOf("results","direct results","immediate results"),fusion.weave!!.single {!it.shared}.variants.map {it.text}.toSet())
    }
}
