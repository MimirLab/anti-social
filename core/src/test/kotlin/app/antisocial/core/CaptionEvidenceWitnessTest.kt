/* anti social, GPL-3.0-or-later. Synthetic extraction-alias regressions. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class CaptionEvidenceWitnessTest {
    private val at=1_800_000_000_000L
    private val head="Community volunteers restored a collection of damaged household tools during the weekend repair workshop, documented each completed repair, and prepared replacement parts so everyone can keep useful equipment in service."
    private val prose="The work is supported by the Community Equipment Program and the Neighbors Repair Guild."
    private val mentions="The work is supported by @ToolPartners and @RepairGuildHQ."
    private val photos=(1..2).map {Media("https://pictures.example/workshop-$it.jpg")}
    private val facebook=Post("post:987654321098765",Platform.FACEBOOK,"https://www.facebook.com/123456789012345/posts/987654321098765","Workshop","$head $prose",publishedAt=at,media=photos,
        provenance=Provenance(Source(Platform.FACEBOOK,"original","https://www.facebook.com",role=SourceRole.ORIGINAL),at,"https://www.facebook.com/api/graphql/"))
    private val alias=facebook.copy(id="post:pfbid0"+"A".repeat(60),canonicalUrl="https://www.facebook.com/workshop/posts/pfbid0"+"A".repeat(60),media=emptyList(),
        provenance=Provenance(Source(Platform.FACEBOOK,"rss-bridge","https://reader.example"),at,"https://reader.example/?action=display&bridge=Facebook&u=workshop"))
    private val instagram=facebook.copy(id="post:Workshop7",platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/Workshop7",publishedAt=at+3000,provenance=null)
    private val x=facebook.copy(id="post:9001",platform=Platform.X,canonicalUrl="https://x.com/workshop/status/9001",body="$head $mentions",publishedAt=at-90_000,provenance=null)
    private val accounts=listOf(
        Follow("fb",Target(Platform.FACEBOOK,TargetKind.PROFILE,"workshop","https://www.facebook.com/workshop"),"Workshop",personId="workshop"),
        Follow("ig",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"workshop","https://www.instagram.com/workshop"),"Workshop",personId="workshop"),
        Follow("x",Target(Platform.X,TargetKind.PROFILE,"workshop","https://x.com/workshop"),"Workshop",personId="workshop"))
    private fun group(posts:List<Post>)=CrossPosts.group(posts,accounts,posts.associate {p->p.identityKey to setOf(accounts.single {it.target.platform==p.platform}.id)})
    private fun normalized(text:String)=TextWhitespace.collapse(text).trim().lowercase()
    private fun inputs()=listOf(facebook,alias,instagram,x)
    private fun <T> permutations(values:List<T>):List<List<T>> = if(values.isEmpty())listOf(emptyList())else values.indices.flatMap {i->permutations(values.filterIndexed {j,_->j!=i}).map {listOf(values[i])+it}}

    @Test fun anEmptyExtractionMustNotSplitAnOtherwiseProvenCaptionFamily() {
        assertNotNull(CrossPostFusion.compose(listOf(facebook,instagram,x)))
        assertEquals(1,group(listOf(facebook,instagram,x)).size)
        val before=Gson().toJson(inputs())
        for(posts in permutations(inputs()))assertEquals(posts.toSet(),group(posts).single().posts.toSet())
        assertEquals(before,Gson().toJson(inputs()));assertTrue(alias.media.isEmpty())
    }
    @Test fun portableCompositionIncludesTheEmptyCopyOnItsCompleteLiteralSourcePath() {
        val posts=inputs();val fusion=requireNotNull(CrossPostFusion.compose(posts))
        assertEquals(listOf(head,prose,mentions).toSet(),fusion.segments.map {it.text}.toSet())
        assertEquals(1,fusion.segments.count {it.text==head});assertEquals(posts.map {it.identityKey}.toSet(),fusion.segments.single {it.shared}.originalKeys)
        for(post in posts)assertEquals(normalized(PostPresentation.content(post)),normalized(fusion.segments.filter {post.identityKey in it.originalKeys}.joinToString(" "){it.text}))
        assertEquals(setOf(facebook.identityKey,alias.identityKey,instagram.identityKey),fusion.segments.single {it.text==prose}.originalKeys)
        assertEquals(setOf(x.identityKey),fusion.segments.single {it.text==mentions}.originalKeys)
    }
    @Test fun aMissingCopyCannotBorrowChangedClaimsLinksTimesOrAnUnrelatedAuthorsEvidence() {
        for(other in listOf(alias.copy(body=alias.body.replace("supported","not supported")),alias.copy(body=alias.body+" There are 12 grants."),
            alias.copy(body=alias.body+" https://other.example/report"),alias.copy(publishedAt=at+1),alias.copy(author="Other workshop"),
            alias.copy(preview=true),alias.copy(parentId="post:other"),alias.copy(provenance=null),alias.copy(recoveredArchive=ArchiveRecovery("fixture",at,"https://archive.example/capture"))))
            assertTrue(other.toString(),group(listOf(facebook,other,instagram,x)).size>1)
    }
    @Test fun anEditionWithItsOwnDifferentArtworkKeepsThatEvidenceInsteadOfBorrowingAnotherCopy() {
        val different=alias.copy(media=listOf(Media("https://pictures.example/unrelated.jpg")))
        assertTrue(group(listOf(facebook,different,instagram,x)).size>1)
        assertNull(CrossPostFusion.compose(listOf(different,x)))
        assertTrue(group(listOf(alias,instagram,x)).size>1)
        assertNull(CrossPostFusion.compose(listOf(alias,instagram,x)))
    }
    @Test fun everyRicherWitnessNeedsItsOwnDirectProofAndNoneBecomesATransitiveBridge() {
        val extra=facebook.copy(id="post:987654321098766",canonicalUrl=facebook.canonicalUrl.replace("987654321098765","987654321098766"))
        val same=inputs()+extra
        assertEquals(same.toSet(),group(same).single().posts.toSet())
        assertNotNull(CrossPostFusion.compose(same))
        val different=extra.copy(media=listOf(Media("https://pictures.example/other.jpg")))
        assertTrue(group(inputs()+different).size>1)
        assertNull(CrossPostFusion.compose(inputs()+different))
    }
    @Test fun aCanonicalSameAccountExtractionOnAnotherPlatformUsesTheSameRule() {
        val alternate=x.copy(id="post:9002",canonicalUrl="https://x.com/workshop/status/9002",media=emptyList())
        val posts=listOf(facebook,instagram,x,alternate)
        assertEquals(posts.toSet(),group(posts).single().posts.toSet())
        val fusion=requireNotNull(CrossPostFusion.compose(posts))
        assertEquals(setOf(x.identityKey,alternate.identityKey),fusion.segments.single {it.text==mentions}.originalKeys)
        assertTrue(alternate.media.isEmpty())
    }
}
