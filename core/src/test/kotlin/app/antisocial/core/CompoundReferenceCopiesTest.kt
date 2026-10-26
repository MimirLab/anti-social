package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class CompoundReferenceCopiesTest {
    private val tail=" telescope recently recorded stars forming inside this distant cloud. This region contains bright young stars surrounded by large amounts of dust and gas. The observing team published the original picture and the instrument settings so readers can inspect the result and compare earlier observations."
    private val x=Post("123456789",Platform.X,"https://x.com/aster/status/123456789","Aster","The @AsterDawn"+tail,publishedAt=1_790_000_000_000L,media=listOf(Media("https://images.example.org/cloud.jpg")))
    private val fb=x.copy(id="987654321",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/aster/posts/987654321",body="Aster's Maya Dawn Space"+tail,publishedAt=x.publishedAt!!+2_000)
    @Test fun compoundReferenceNeedsTheExistingIndependentMediaProof() {
        assertFalse(CaptionAliases.equivalent(x,fb))
        assertTrue(CaptionAliases.equivalent(x,fb,mediaConfirmed=true))
        assertEquals(1,CrossPosts.group(listOf(x,fb),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(x.copy(media=emptyList()),fb.copy(media=emptyList())),emptyList()).size)
    }
    @Test fun collectedCaptionUsesTheActualFullNameEditionAndKeepsBothOriginals() {
        val originals=listOf(x,fb);val fusion=CrossPostFusion.composeMedia(originals)!!
        assertEquals(1,fusion.segments.size);assertEquals(fb.body,fusion.segments.single().text)
        assertEquals(originals.map(CrossPosts::originalKey).toSet(),fusion.segments.single().originalKeys)
        assertEquals(1,Regex("recently recorded").findAll(fusion.segments.single().text).count())
    }
    @Test fun changedSubjectsClaimsAndConcreteDestinationsRemainSeparate() {
        for(value in listOf(fb.body.replace("Dawn","Dusk"),fb.body.replace("Maya","Different"),fb.body.replace("Maya","Not"),fb.body.replace("Maya","5"),fb.body.replace("Maya","II"),fb.body.replace("Maya","Two"),fb.body.replace("recorded","did not record"),fb.body+" https://different.example/report")) {
            val other=fb.copy(body=value)
            assertFalse(value,CaptionAliases.equivalent(x,other,mediaConfirmed=true))
            assertEquals(value,2,CrossPosts.group(listOf(x,other),emptyList()).size)
        }
    }
    @Test fun aSharedSurnameOrPartialSpellingDoesNotEstablishACompoundReference() {
        for(value in listOf("@Dawn","@AsterDaw","@AsterDus","@MayaDawn"))assertFalse(CaptionAliases.equivalent(x.copy(body=x.body.replace("@AsterDawn",value)),fb,mediaConfirmed=true))
        assertFalse(CaptionAliases.equivalent(x,fb.copy(body=fb.body.replace("Maya Dawn Space","Maya dawn space")),mediaConfirmed=true))
    }
}
