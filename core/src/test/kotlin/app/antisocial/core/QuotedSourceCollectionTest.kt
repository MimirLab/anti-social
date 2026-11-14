/* anti social, GPL-3.0-or-later. Synthetic curated quotations and original paths. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class QuotedSourceCollectionTest {
    private val at=1_800_000_000_000L
    private val common="The workshop team has published the complete schedule and equipment inventory for the community. Visitors can inspect the illustrated repair instructions, compare the available materials, and prepare their questions before attending the practical demonstrations. The document explains the remaining work, the shared equipment, and the next steps for everyone joining the public session."
    private val intro="On Day 1, our volunteers inspected the entrance and checked the shared tools. We have repaired the damaged fence, arranged the training materials, cleaned the storage room, prepared the tables, and completed our own inventory before the next neighborhood workshop."
    private val source=Source(Platform.X,"nitter","https://nitter.fixture.invalid",role=SourceRole.ALTERNATE)
    private fun profile(p:Platform)=when(p){Platform.X->"https://x.com/Workshop";Platform.FACEBOOK->"https://www.facebook.com/Workshop";else->"https://www.instagram.com/Workshop"}
    private val follows=listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM).map {p->Follow(p.id,Target(p,TargetKind.PROFILE,"Workshop",profile(p)),"Workshop",personId="quoted-workshop")}
    private fun root(p:Platform)=Post("910001",p,when(p){Platform.X->profile(p)+"/status/910001";Platform.FACEBOOK->profile(p)+"/posts/910001";else->"https://www.instagram.com/p/QuotedWorkshop1/"},"Workshop",common,publishedAt=at,authorAccount=PostAuthorAccount(profile(p)))
    private fun quote(id:String="910002",body:String="$intro Workshop (@Workshop) $common — https://x.com/Workshop/status/910001")=Post(id,Platform.X,"https://x.com/RepairCrew/status/$id","RepairCrew",body,publishedAt=at+70*60_000,
        provenance=Provenance(source,at+72*60_000,"${source.root}/Workshop/rss"),publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,"https://x.com/Workshop")))
    private fun originals()=listOf(root(Platform.X),root(Platform.FACEBOOK),root(Platform.INSTAGRAM),quote())
    private fun group(posts:List<Post>,accounts:List<Follow> = follows):List<PostGroup> {
        val membership=posts.associate {post->CrossPosts.originalKey(post) to setOf(post.platform.id)}
        return CrossPosts.group(posts,accounts,membership)
    }
    private fun projection(posts:List<Post>,fusion:PostFusion) {
        for(post in posts) {
            val key=CrossPosts.originalKey(post)
            val text=requireNotNull(fusion.weave).joinToString(" "){run->run.variants.single {key in it.originalKeys}.text}
            assertEquals(TextWhitespace.collapse(PostPresentation.content(post)).trim(),TextWhitespace.collapse(text).trim())
        }
    }
    @Test fun aCuratedDirectQuotationReadsItsCompleteSourceOnceAndKeepsItsOwnCommentary() {
        val posts=originals();val before=Gson().toJson(posts)
        for(input in listOf(posts,posts.reversed(),posts.drop(1)+posts.first())) {
            val collection=group(input).single();assertEquals(posts.toSet(),collection.posts.toSet())
            val fusion=requireNotNull(CrossPostFusion.compose(collection.posts));projection(posts,fusion)
            val shown=fusion.segments.joinToString(" "){it.text}
            assertEquals(1,Regex(Regex.escape(common)).findAll(shown).count())
            assertTrue(shown.contains(intro));assertTrue(PostLinks.urls(collection.posts.single {it.id=="910002"}).contains("https://x.com/Workshop/status/910001"))
            assertTrue(shown.contains("@repaircrew"))
            val own=fusion.weave!!.single {run->run.variants.any {intro in it.text}}
            assertFalse(own.shared);assertEquals(setOf(CrossPosts.originalKey(quote())),own.variants.single {intro in it.text}.originalKeys)
        }
        assertEquals(before,Gson().toJson(posts))
    }
    @Test fun sourceBoundNitterAndCanonicalHostAliasesKeepTheSameCitedOriginal() {
        for(url in listOf("https://twitter.com/Workshop/status/910001","${source.root}/Workshop/status/910001#m")) {
            val posts=originals().dropLast(1)+quote(body="$intro $common — $url")
            val fusion=requireNotNull(CrossPostFusion.compose(group(posts).single().posts));projection(posts,fusion)
        }
    }
    @Test fun aCommonCaptionOrWrongCitationCannotStandInForTheActualSourceReference() {
        for(url in listOf("https://x.com/Workshop/status/999999","https://elsewhere.example/Workshop/status/910001","https://x.com/Other/status/910001","http://x.com/Workshop/status/910001"))
            assertTrue(url,group(originals().dropLast(1)+quote(body="$intro $common — $url")).size>=2)
        assertTrue(group(originals().dropLast(1)+quote(body="$intro $common")).size>=2)
    }
    @Test fun aCuratorClaimNeedsItsBoundSourceAndTheExistingLocalPublisherBundle() {
        val posts=originals();assertTrue(group(posts,emptyList()).size>=2)
        val bad=listOf(quote().copy(publicRelations=null),quote().copy(provenance=Provenance(source.copy(frontend="other"),at,"${source.root}/Workshop/rss")),
            quote().copy(provenance=Provenance(source,at,"${source.root}/Other/rss")),quote().copy(publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,"https://x.com/Other"))))
        bad.forEach {assertTrue(group(posts.dropLast(1)+it).size>=2)}
    }
    @Test fun aChangedExcerptRepeatedQuotationOrRoundupRemainsOutsideThisCollection() {
        val bad=listOf(quote(body="$intro ${common.replace("has published","has not published")} — https://x.com/Workshop/status/910001"),
            quote(body="$intro $common $common — https://x.com/Workshop/status/910001"),quote(body="$intro $common — https://x.com/Workshop/status/910001 https://x.com/Other/status/910003"))
        bad.forEach {assertTrue(group(originals().dropLast(1)+it).size>=2)}
    }
    @Test fun uniqueNegativeCommentaryRemainsAttributedRatherThanChangingTheQuotedClaim() {
        val warning="These instructions are not correct. Read the correction before making your plans."
        val posts=originals().dropLast(1)+quote(body="$warning $intro $common — https://x.com/Workshop/status/910001")
        val fusion=requireNotNull(CrossPostFusion.compose(group(posts).single().posts));projection(posts,fusion)
        assertTrue(fusion.weave!!.filterNot {it.shared}.any {run->run.variants.any {warning in it.text}})
    }
    @Test fun staleIncompleteAndReplyRecordsCannotSupplyAnAnnouncementQuotation() {
        for(post in listOf(quote().copy(publishedAt=at+CrossPosts.MAX_REPEAT_WINDOW+1),quote().copy(publishedAt=at-600_000),quote().copy(publishedAt=null),quote().copy(preview=true),quote().copy(parentId="other"),quote().copy(body=quote().body+"\u202e")))
            assertTrue(group(originals().dropLast(1)+post).size>=2)
    }
    @Test fun severalDirectCommentsCannotBecomeANestedOrTransitiveCitationChain() {
        val direct=quote("910003",body="A separate update from the repair team. $intro $common — https://x.com/Workshop/status/910001")
        val posts=originals()+direct
        val fusion=requireNotNull(CrossPostFusion.compose(group(posts).single().posts));projection(posts,fusion)
        val nested=quote("910004",body="Another update. ${direct.body} — ${direct.canonicalUrl}")
        assertTrue(group(posts+nested).size>=2)
    }
}
