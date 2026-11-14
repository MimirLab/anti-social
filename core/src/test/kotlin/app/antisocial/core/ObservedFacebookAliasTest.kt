/* anti social, GPL-3.0-or-later. Synthetic source identities and image observations. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Test
import kotlin.test.*

class ObservedFacebookAliasTest {
    private val at=1_790_000_000_000L
    private val picture=requireNotNull(VisualFrames.encode(.8,45.0,0x12345678,0x76543210,List(48){(it*17)%256},ByteArray(4096){((it*37+it/64)%256).toByte()}))
    private val text="Our workshop tools are ready. 🛠"
    private val numeric=Post("post:987654321098765",Platform.FACEBOOK,"https://www.facebook.com/123456789012345/posts/987654321098765","Public workshop",text,publishedAt=at,
        media=listOf(Media("https://pictures.fixture.invalid/numeric.jpg",visualFingerprint=picture)))
    private val opaque=numeric.copy(id="post:pfbidFixtureAlias123456789",canonicalUrl="https://www.facebook.com/publicworkshop/posts/pfbidFixtureAlias123456789",
        media=listOf(Media("https://pictures.fixture.invalid/opaque.png",visualFingerprint=picture)))
    private fun follow(p:Platform)=Follow(p.id,Target(p,TargetKind.PROFILE,"publicworkshop",when(p) {
        Platform.FACEBOOK->"https://www.facebook.com/publicworkshop"
        Platform.X->"https://x.com/publicworkshop"
        else->"https://www.instagram.com/publicworkshop/"
    }),"Public workshop",personId="public-workshop-fixture")
    private val follows=listOf(Platform.FACEBOOK,Platform.X,Platform.INSTAGRAM).map(::follow)
    private fun memberships(posts:List<Post>)=posts.associate {it.identityKey to setOf(it.platform.id)}
    private fun group(posts:List<Post>,links:List<Follow> = follows,supplies:Map<String,Set<String>> = memberships(posts))=CrossPosts.group(posts,links,supplies)

    @Test fun observedPictureAliasesStopCompetingForTheirCrossPlatformFamily() {
        val x=numeric.copy(id="post:992",platform=Platform.X,canonicalUrl="https://x.com/publicworkshop/status/992",publishedAt=at+30_000)
        val instagram=numeric.copy(id="post:Workshop992",platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/Workshop992/",publishedAt=at+60_000)
        val posts=listOf(numeric,opaque,x,instagram);val raw=posts.map {Gson().toJson(it)}
        for(input in listOf(posts,posts.reversed(),posts.drop(1)+posts.first())) {
            val family=group(input).single().posts
            assertEquals(posts.toSet(),family.toSet())
            val fusion=assertNotNull(CrossPostFusion.composeCollectedEditions(family))
            assertEquals(text,fusion.segments.single().text)
            assertEquals(posts.map {it.identityKey}.toSet(),fusion.segments.single().originalKeys)
        }
        assertEquals(raw,posts.map {Gson().toJson(it)})
    }
    @Test fun aSharedPersonBundleWithoutTheSameSupplyingFeedDoesNotProveAliases() {
        assertEquals(2,group(listOf(numeric,opaque),supplies=emptyMap()).size)
        assertEquals(2,group(listOf(numeric,opaque),links=emptyList()).size)
        assertEquals(2,group(listOf(numeric,opaque),supplies=mapOf(numeric.identityKey to setOf("facebook"))).size)
        val other=follow(Platform.FACEBOOK).copy(id="other",target=follow(Platform.FACEBOOK).target.copy(identity="anotherworkshop",canonicalUrl="https://www.facebook.com/anotherworkshop"))
        assertEquals(2,group(listOf(numeric,opaque),links=follows+other,supplies=mapOf(numeric.identityKey to setOf("other"),opaque.identityKey to setOf("facebook"))).size)
    }
    @Test fun savedContentCopiesKeepTheirProofWithoutInventingAccountControl() {
        val posts=listOf(numeric,opaque)
        val raw=posts.map {Gson().toJson(it)}
        assertTrue(CrossPosts.sameContentCopies(posts))
        assertTrue(CrossPosts.sameContentCopies(posts.reversed()))
        assertFalse(CrossPosts.sameSourceRepeats(posts))
        assertEquals(2,group(posts,links=emptyList(),supplies=emptyMap()).size)
        assertEquals(text,assertNotNull(CrossPostFusion.composeCollectedEditions(posts)).segments.single().text)
        assertEquals(raw,posts.map {Gson().toJson(it)})
    }
    @Test fun missingCoarseAndAttachmentOnlyEvidenceDoNotFoldTheAliases() {
        val coarse=picture.substringBeforeLast('|').replaceFirst("f2|","f1|")
        for(observation in listOf(null,coarse)) {
            val other=opaque.copy(media=opaque.media.map {it.copy(visualFingerprint=observation)})
            assertEquals(2,group(listOf(numeric,other)).size)
            assertFalse(CrossPosts.sameContentCopies(listOf(numeric,other)))
        }
        val attachmentOnly=opaque.copy(media=opaque.media.map {it.copy(attachmentFingerprint=picture,visualFingerprint=null)})
        assertEquals(2,group(listOf(numeric,attachmentOnly)).size)
        assertFalse(CrossPosts.sameContentCopies(listOf(numeric,attachmentOnly)))
        assertEquals(2,group(listOf(numeric.copy(media=emptyList()),opaque.copy(media=emptyList()))).size)
    }
    @Test fun changedPixelsGalleriesCoversAndAvatarsStayDistinct() {
        val edited=requireNotNull(VisualFrames.encode(.8,45.0,0x12345678,0x76543210,List(48){(it*17)%256},ByteArray(4096){0}))
        for(other in listOf(
            opaque.copy(media=opaque.media.map {it.copy(visualFingerprint=edited)}),
            opaque.copy(media=opaque.media+opaque.media.single().copy(url="https://pictures.fixture.invalid/extra.jpg")),
            opaque.copy(media=opaque.media.map {it.copy(type="video",preview=it.url)}),
            opaque.copy(avatar=opaque.media.single().url)
        )) {
            assertEquals(2,group(listOf(numeric,other)).size)
            assertFalse(CrossPosts.sameContentCopies(listOf(numeric,other)))
        }
    }
    @Test fun exactTimeLiteralCaptionAuthorAndDestinationsRemainRequired() {
        for(other in listOf(opaque.copy(publishedAt=at+1),opaque.copy(publishedAt=null),opaque.copy(author="Another workshop"),
            opaque.copy(body="Our workshop tools are missing. 🛠"),opaque.copy(body="Our workshop tools are ready? 🛠"),
            opaque.copy(body="OUR WORKSHOP TOOLS ARE READY. 🛠"),opaque.copy(body="Our workshop tools are ready. 🇨🇦"),
            opaque.copy(links=listOf("https://other.fixture.invalid/notice")),opaque.copy(preview=true),opaque.copy(parentId="root"))) {
            assertEquals(2,group(listOf(numeric,other)).size)
            assertFalse(CrossPosts.sameContentCopies(listOf(numeric,other)))
        }
    }
    @Test fun aForeignOrDifferentFacebookAccountCannotBorrowThisAliasRoute() {
        val foreign=opaque.copy(canonicalUrl=opaque.canonicalUrl.replace("www.facebook.com","foreign.fixture.invalid"))
        assertEquals(2,group(listOf(numeric,foreign)).size)
        val secondNumeric=opaque.copy(canonicalUrl="https://www.facebook.com/777777777777777/posts/666666666666666",id="post:666666666666666")
        assertEquals(2,group(listOf(numeric,secondNumeric)).size)
    }
    @Test fun extraUrlAuthorityAndNavigationCannotSupplyPortableAliasProof() {
        for(url in listOf(opaque.canonicalUrl+"?other=1",opaque.canonicalUrl+"#other",opaque.canonicalUrl.replace("facebook.com/","facebook.com:444/"),
            opaque.canonicalUrl.replace("https://","https://user@"),opaque.canonicalUrl+"/other",opaque.canonicalUrl+"?padding="+"x".repeat(600))) {
            val other=opaque.copy(canonicalUrl=url)
            assertEquals(2,group(listOf(numeric,other)).size)
            assertFalse(CrossPosts.sameContentCopies(listOf(numeric,other)))
        }
    }
}
