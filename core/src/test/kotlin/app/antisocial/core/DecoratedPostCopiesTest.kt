package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class DecoratedPostCopiesTest {
    private val resource="https://puzzle.example/games/new"
    private val prose="Need a pause? Try a new puzzle from our observing team today at "
    private val at=1_790_000_000_000L
    private fun posts():List<Post> = listOf(
        Post("123456789",Platform.X,"https://x.com/publiclab/status/123456789","Public lab","🛰️ $prose https://t.co/AbCdEf123 🔥",publishedAt=at,links=listOf(resource)),
        Post("AbCdEfGhijk",Platform.INSTAGRAM,"https://www.instagram.com/p/AbCdEfGhijk/","Public lab","🚀 $prose $resource 🎮 #Puzzles #Science",title="▶ Need a pause?...",publishedAt=at+60_000,
            provenance=Provenance(Source(Platform.INSTAGRAM,"rss-bridge","https://reader.example/"),at,"https://reader.example/feed")),
        Post("987654321",Platform.FACEBOOK,"https://www.facebook.com/publiclab/posts/987654321","Public lab",prose+"puzzle.example/games/new ✨🧩",publishedAt=at+120_000))
    private fun follows()=listOf(Platform.X,Platform.INSTAGRAM,Platform.FACEBOOK).map {p->
        val url=when(p){Platform.X->"https://x.com/publiclab";Platform.INSTAGRAM->"https://www.instagram.com/publiclab/";else->"https://www.facebook.com/publiclab"}
        Follow("fixture:${p.id}",Target(p,TargetKind.PROFILE,"publiclab",url),"Public lab",personId="fixture-public-lab")
    }
    private fun groups(values:List<Post>):List<PostGroup> {
        val accounts=follows();val memberships=values.associate {p->p.identityKey to accounts.filter {it.target.platform==p.platform}.map {it.id}.toSet()}
        return CrossPosts.group(values,accounts,memberships)
    }
    @Test fun decorativeVariantsUseOneCardAndOneActualCaptionWithoutAlteringOriginals() {
        val values=posts();val before=values.toList()
        for(input in listOf(values,values.reversed())) {
            val collection=groups(input).single();assertEquals(values.toSet(),collection.posts.toSet())
            val fusion=CrossPostFusion.compose(collection.posts)!!
            assertEquals(1,fusion.segments.size);assertTrue(fusion.segments.single().text.contains("puzzle.example/games/new"))
            assertEquals(1,Regex("Need a pause",RegexOption.IGNORE_CASE).findAll(fusion.segments.single().text).count())
        }
        assertEquals(before,values)
    }
    @Test fun conflictingDestinationsAndChangedClaimsStaySeparate() {
        val values=posts();val a=values.first();val b=values.last()
        for(changed in listOf(b.copy(body=b.body.replace("games/new","games/other")),b.copy(body="Not "+b.body),b.copy(body=b.body+" Costs $20.")))
            assertEquals(2,groups(listOf(a,changed)).size)
        assertEquals(2,groups(listOf(a,b.copy(publishedAt=at+2*60*60_000L))).size)
    }
    @Test fun approvalRejectionAndNumericOperatorsAreNotDecorativeNoise() {
        val base=posts().last()
        for((a,b)in listOf(" ✅" to " ❌"," 👍" to " 👎"," 2➕3" to " 2➖3"," $20" to " $30")) {
            val first=DecoratedPostCopies.prepare(base.copy(body=base.body+a))!!
            val second=DecoratedPostCopies.prepare(base.copy(body=base.body+b))!!
            assertNull(DecoratedPostCopies.compare(first,second))
        }
    }
    @Test fun chunkPercentageStillRequiresTheLiteralOrderedPassageAligner() {
        val paragraph="The observing team released a detailed account of the work completed at its public laboratory. The report explains the equipment used for the demonstration and the methods visitors can repeat with ordinary materials. Every participant can inspect the published instructions and compare their observations with the original experiment."
        val a=posts().first().copy(body=paragraph+" ✨",links=emptyList())
        val b=posts().last().copy(title="Camera settings",body=paragraph+" 🚀")
        val match=DecoratedPostCopies.compare(DecoratedPostCopies.prepare(a)!!,DecoratedPostCopies.prepare(b)!!)!!
        assertFalse(match.exact);assertTrue(match.coverage>=.90)
        assertNotNull(CrossPostFusion.compose(listOf(a,b)))
    }
    @Test fun concreteSocialDestinationsAndWordSubstitutionsRemainDistinct() {
        val paragraph="The observing team released a detailed account of the Artemis II mission at its public laboratory. The report explains the equipment used for the demonstration and the methods visitors can repeat with ordinary materials. Every participant can inspect the published instructions and compare their observations with the original experiment."
        val a=posts().first().copy(body=paragraph+" ✨",links=emptyList())
        val b=posts().last().copy(body=paragraph.replace("Artemis II","Artemis III")+" 🚀",links=emptyList())
        assertNull(DecoratedPostCopies.compare(DecoratedPostCopies.prepare(a)!!,DecoratedPostCopies.prepare(b)!!))
        val target="https://x.com/otherlab/status/555555555"
        val c=posts().last().copy(body=paragraph+" 🚀",links=listOf(target))
        assertNull(DecoratedPostCopies.compare(DecoratedPostCopies.prepare(a)!!,DecoratedPostCopies.prepare(c)!!))
    }
    @Test fun aPreviewAndAReplyCannotEnterThisFeedCopyRoute() {
        assertNull(DecoratedPostCopies.prepare(posts().first().copy(preview=true)))
        assertNull(DecoratedPostCopies.prepare(posts().first().copy(parentId="parent")))
    }
    @Test fun plainTextSkipsDecorationWorkButDecodedOrnamentStillEnablesTheRoute() {
        val post=posts().last().copy(title="",body=prose+"puzzle.example/games/new")
        assertFalse(DecoratedPostCopies.mayChange(post))
        assertFalse(DecoratedPostCopies.mayChange(post.copy(body=post.body+" Ελληνικά 中文")))
        for(extra in listOf(" 🎮"," &#x1f3ae;"," &amp;#x1f3ae;"," #Puzzles #Science"))assertTrue(DecoratedPostCopies.mayChange(post.copy(body=post.body+extra)))
    }
}
