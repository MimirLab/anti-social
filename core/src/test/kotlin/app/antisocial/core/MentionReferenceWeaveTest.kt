/* anti social, GPL-3.0-or-later. Synthetic literal-reference collation. */
package app.antisocial.core

import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MentionReferenceWeaveTest {
    private val connective="and meeting with students at"
    private val start="A full day of repairs at the community workshop with @CrewLead, from planning a new project between"
    private val end="to sharing the completed tools with our neighbors. The same care that guides each repair will help the next group learn practical skills and build something useful together."
    private fun body(partner:String,school:String)="$start @RepairGuild and $partner $connective $school $end"
    private fun post(platform:Platform,body:String,id:String="99001")=Post(id,platform,when(platform) {
        Platform.X->"https://x.com/publicstudio/status/$id"
        Platform.INSTAGRAM->"https://www.instagram.com/p/Fixture$id/"
        else->"https://bsky.app/profile/studio.example/post/$id"
    },"Public studio",body,publishedAt=1_800_000_000_000L,media=listOf(Media("https://images.example.org/repair.jpg")))
    private fun posts()=listOf(post(Platform.X,body("@ToolPartners","@WorkshopSchool")),
        post(Platform.INSTAGRAM,body("@toolpartnershq","@schoolworkshop")))
    private fun keys(text:String)=TextWhitespace.collapse(Normalizer.normalize(text,Normalizer.Form.NFC)).lowercase(Locale.ROOT).split(' ')
    private fun verify(posts:List<Post>,fusion:PostFusion) {
        val runs=requireNotNull(fusion.weave)
        for(post in posts) {
            val original=CrossPosts.originalKey(post)
            val path=runs.joinToString(" "){run->run.variants.single {original in it.originalKeys}.text}
            assertEquals("Every source keeps its complete ordered literal path",keys(PostPresentation.content(post)),keys(path))
        }
        assertEquals(posts.mapTo(hashSetOf(),CrossPosts::originalKey),runs.flatMap {it.variants}.flatMap {it.originalKeys}.toSet())
    }
    @Test fun repeatedConnectiveProseIsSharedWhileEveryHandleAlternativeKeepsItsSource() {
        val posts=posts();val before=posts.map {it.copy()}
        val fusion=requireNotNull(CrossPostFusion.composeMentionReferences(posts));verify(posts,fusion)
        assertEquals(1,Regex(Regex.escape(connective)).findAll(fusion.segments.single().text).count())
        assertEquals(2,fusion.weave!!.count {!it.shared})
        for(post in posts)for(handle in if(post.platform==Platform.X)listOf("@ToolPartners","@WorkshopSchool")else listOf("@toolpartnershq","@schoolworkshop")) {
            val variant=fusion.weave!!.flatMap {it.variants}.single {it.text==handle}
            assertEquals(setOf(post.platform),variant.platforms);assertEquals(setOf(CrossPosts.originalKey(post)),variant.originalKeys)
        }
        assertEquals(before,posts)
        assertEquals(fusion,CrossPostFusion.composeMentionReferences(posts.reversed()))
        val third=post(Platform.BLUESKY,body("@ToolPartners","@CommunitySchool"))
        val expanded=posts+third
        val multiple=requireNotNull(CrossPostFusion.composeMentionReferences(expanded));verify(expanded,multiple)
        val sharedReference=multiple.weave!!.flatMap {it.variants}.single {it.text=="@ToolPartners"}
        assertEquals(setOf(Platform.X,Platform.BLUESKY),sharedReference.platforms)
        assertEquals(setOf(CrossPosts.originalKey(posts[0]),CrossPosts.originalKey(third)),sharedReference.originalKeys)
        assertEquals(multiple,CrossPostFusion.composeMentionReferences(expanded.reversed()))
    }
    @Test fun onlyTheAlreadyCorroboratedMediaPresentationUsesTheNewRoute() {
        val posts=posts()
        assertTrue(CrossPosts.sameContentCopies(posts))
        val fusion=requireNotNull(CrossPostFusion.composeMedia(posts));verify(posts,fusion)
        assertEquals(1,Regex(Regex.escape(connective)).findAll(fusion.segments.single().text).count())
        assertNull(CrossPostFusion.composeMedia(posts.map {it.copy(media=emptyList())}))
        assertNull(CrossPostFusion.composeMedia(listOf(posts[0],posts[1].copy(media=listOf(Media("https://images.example.org/unrelated.jpg"))))))
    }
    @Test fun punctuationAndLiteralUrlsRemainOnTheirCompleteSourcePaths() {
        val posts=posts().map {it.copy(body=it.body.replace("@ToolPartners","(@ToolPartners),").replace("@toolpartnershq","(@toolpartnershq),")+" Read https://example.org/@Guide/Path?Token=AbC.")}
        val fusion=requireNotNull(CrossPostFusion.composeMentionReferences(posts));verify(posts,fusion)
        assertTrue(fusion.weave!!.flatMap {it.variants}.any {it.text=="(@ToolPartners),"})
        assertTrue(fusion.segments.single().text.contains("https://example.org/@Guide/Path?Token=AbC."))
        assertNull(CrossPostFusion.composeMentionReferences(listOf(posts[0],posts[1].copy(body=posts[1].body.replace("/Path?Token=AbC","/path?Token=abc")))))
        assertNull(CrossPostFusion.composeMentionReferences(posts.mapIndexed {i,p->p.copy(body=p.body+if(i==0)" Contact a@example.org."else " Contact a@other.example.org.")}))
    }
    @Test fun changedClaimsNamesOrderOrMissingReferencesDoNotBecomeReferenceChoices() {
        val posts=posts().map {it.copy(body=it.body+" The team completed 12 repairs.")}
        for(change in listOf<(String)->String>({it.replace("12 repairs","21 repairs")},{it.replace("will help","will not help")},
            {it.replace("our neighbors","Morgan's neighbors")},{it.replace("planning a new project","reviewing the old project")},
            {it.replace("@schoolworkshop","the workshop school")},{it.replace("@toolpartnershq","not:@toolpartnershq")}))
            assertNull(CrossPostFusion.composeMentionReferences(listOf(posts[0],posts[1].copy(body=change(posts[1].body)))))
        val swapped=posts[1].copy(body=posts[1].body.replace("@toolpartnershq","@first").replace("@schoolworkshop","@toolpartnershq").replace("@first","@schoolworkshop"))
        val fusion=requireNotNull(CrossPostFusion.composeMentionReferences(listOf(posts[0],swapped)))
        // References are alternatives, never an assertion that their accounts
        // are identical; both complete, coupled source paths remain available.
        verify(listOf(posts[0],swapped),fusion)
    }
    @Test fun sourceEditionLabelsRemainUnambiguousAndEveryRepeatedOriginalSurvives() {
        val posts=posts();val repeated=posts+posts.first().copy(id="99002",canonicalUrl="https://x.com/publicstudio/status/99002")
        verify(repeated,requireNotNull(CrossPostFusion.composeMentionReferences(repeated)))
        val changed=posts+posts.first().copy(id="99002",canonicalUrl="https://x.com/publicstudio/status/99002",body=posts.first().body.replace("@ToolPartners","@OtherPartner"))
        assertNull(CrossPostFusion.composeMentionReferences(changed))
        assertNull(CrossPostFusion.composeMentionReferences(posts+posts.first()))
    }
    @Test fun unusualSpacingAndUnsupportedOrOversizedInputsKeepTheExistingFallback() {
        val posts=posts().mapIndexed {i,p->p.copy(body=p.body.replace("care",if(i==0)"café"else "cafe\u0301").replace(" ",if(i==0)"\u00a0"else "\n"))}
        verify(posts,requireNotNull(CrossPostFusion.composeMentionReferences(posts)))
        val original=posts()
        for(change in listOf<(Post)->Post>({it.copy(body="x".repeat(4097))},{it.copy(preview=true)},{it.copy(parentId="parent")},
            {it.copy(body=it.body+"\u202ehidden")},{it.copy(body=it.body.replace("@toolpartnershq","@remote@instance.example"))},
            {it.copy(body=it.body.replace("@toolpartnershq","`@toolpartnershq`"))},
            {it.copy(body="~~~text "+it.body+" ~~~")}))
            assertNull(CrossPostFusion.composeMentionReferences(listOf(original[0],change(original[1]))))
        assertNull(CrossPostFusion.composeMentionReferences(original.take(1)))
        assertNull(CrossPostFusion.composeMentionReferences(original.map {it.copy(body="Thanks @one and @two.")}))
        assertNull(CrossPostFusion.composeMentionReferences(List(CrossPosts.MAX_EDITIONS+1){original[it%2]}))
    }
}
