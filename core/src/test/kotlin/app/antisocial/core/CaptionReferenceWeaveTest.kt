/* anti social, GPL-3.0-or-later. Synthetic source captions, literal references. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class CaptionReferenceWeaveTest {
    private val intro="One month ago, the repair team launched"
    private val middle="It puts visitors first. Respects their time. And actually helps everyone."
    private val end="See the original workshop launch yourself in Hello, Neighbors. 🛠"
    private fun caption(reference:String)="$intro $reference, a directory built around you.\n\n$middle\n\n$end"
    private fun post(platform:Platform,body:String)=Post("Ref991",platform,when(platform) {
        Platform.X->"https://x.com/reference_fixture/status/991"
        Platform.FACEBOOK->"https://www.facebook.com/reference_fixture/posts/991"
        else->"https://www.instagram.com/p/ReferenceFixture991/"
    },"Reference fixture",body,publishedAt=1_800_000_000_000L,media=listOf(Media("https://example.org/shared-picture.png")))
    private fun editions()=listOf(post(Platform.X,caption("https://t.co/Fixture91")+" https://t.co/More72"),
        post(Platform.FACEBOOK,caption("Example.org")),post(Platform.INSTAGRAM,caption("Example.org").replace("\n\n"," ")))
    private fun compact(text:String)=text.replace(Regex("\\s+")," ").trim()
    private fun project(posts:List<Post>,fusion:PostFusion) {
        val runs=requireNotNull(fusion.weave)
        for(post in posts) {
            val key=CrossPosts.originalKey(post)
            val literal=runs.joinToString(" "){run->run.variants.single {key in it.originalKeys}.text}
            assertEquals("Every source path includes its complete literal references",compact(PostPresentation.content(post)),compact(literal))
        }
        assertEquals(posts.mapTo(hashSetOf(),CrossPosts::originalKey),runs.flatMap {it.variants}.flatMap {it.originalKeys}.toSet())
    }
    @Test fun anInlineShortLinkAndParagraphVariationShareOneCaptionWithLiteralAlternatives() {
        val posts=editions();val before=posts.map {it.copy()}
        val fusion=requireNotNull(CrossPostFusion.composeCollectedReferences(posts));project(posts,fusion)
        val text=fusion.segments.single().text
        for(shared in listOf(intro,middle,end))assertEquals(1,Regex(Regex.escape(shared)).findAll(text).count())
        val reference=fusion.weave!!.single {run->run.variants.any {it.text=="Example.org,"}}
        assertEquals(setOf(Platform.FACEBOOK,Platform.INSTAGRAM),reference.variants.single {it.text=="Example.org,"}.platforms)
        assertEquals(setOf(Platform.X),reference.variants.single {it.text=="https://t.co/Fixture91,"}.platforms)
        assertTrue(text.contains("https://t.co/More72"));assertEquals(before,posts)
        assertEquals(fusion,CrossPostFusion.composeCollectedReferences(posts.reversed()))
    }
    @Test fun referencePathsQueriesAndUnicodeRemainLiteralRatherThanProseFolded() {
        val urls=listOf("https://example.org/Guide?Token=AbC1","https://example.org/guide?Token=abc1","https://example.org/Guidé?Token=AbC1")
        val posts=listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM).zip(urls).map {(platform,url)->post(platform,caption(url)+" https://t.co/Shared91")}
        val fusion=requireNotNull(CrossPostFusion.composeCollectedReferences(posts));project(posts,fusion)
        for(url in urls)assertTrue(fusion.weave!!.filterNot {it.shared}.flatMap {it.variants}.any {it.text=="$url,"})
        val decomposed=posts.last().copy(body=posts.last().body.replace("é","e\u0301"))
        val pair=listOf(posts.last(),decomposed.copy(platform=Platform.X,canonicalUrl="https://x.com/reference_fixture/status/992"))
        val spelling=requireNotNull(CrossPostFusion.composeCollectedReferences(pair));project(pair,spelling)
        assertTrue(spelling.weave!!.any {!it.shared})
    }
    @Test fun referenceIdentifierDigitsAreNotProseClaimsButActualClaimsStayProtected() {
        val posts=editions().take(2).map {it.copy(body=it.body+" Admission is 12 dollars.")}
        project(posts,requireNotNull(CrossPostFusion.composeCollectedReferences(posts)))
        assertNull(CrossPostFusion.composeCollectedReferences(listOf(posts[0],posts[1].copy(body=posts[1].body.replace("12 dollars","21 dollars")))))
        assertNull(CrossPostFusion.composeCollectedReferences(listOf(posts[0],posts[1].copy(body=posts[1].body.replace("helps everyone","does not help everyone")))))
        assertNull(CrossPostFusion.composeCollectedReferences(listOf(posts[0],posts[1].copy(body=posts[1].body.replace("Example.org","not:Example.org")))))
    }
    @Test fun aReferenceCannotTurnDifferentNamedSubjectsIntoAnUnqualifiedWordingChoice() {
        val posts=editions().take(2).map {it.copy(body=it.body.replace("the repair team","Mira's repair team"))}
        val changed=listOf(posts[0],posts[1].copy(body=posts[1].body.replace("Mira","Nora")))
        assertNull(CrossPostFusion.composeCollectedReferences(changed))
        assertNull(CrossPostFusion.composeCollectedReferences(listOf(posts[0],posts[1].copy(body=posts[1].body.replace("visitors","@another_account")))))
    }
    @Test fun thePresentationRouteDoesNotRelaxTheExistingDetectorOrBecomeAGeneralFallback() {
        val posts=editions()
        assertFalse(CaptionWeave.matches(posts[0],posts[1]));assertNull(CaptionWeave.compose(posts))
        assertNull(CrossPostFusion.composeCollectedReferences(posts.drop(1)))
        assertNull(CrossPostFusion.composeCollectedReferences(listOf(posts[0],posts[1].copy(body="An unrelated announcement has the same photograph and nothing else in common."))))
        val samePlatform=listOf(posts[0],posts[1].copy(platform=Platform.X,canonicalUrl="https://x.com/reference_fixture/status/992"))
        assertNull(CrossPostFusion.composeCollectedReferences(samePlatform))
    }
    @Test fun oversizedOrUncertainSourceInputsKeepTheirExistingFaithfulFallback() {
        val posts=editions()
        assertNull(CrossPostFusion.composeCollectedReferences(posts.take(1)))
        assertNull(CrossPostFusion.composeCollectedReferences(List(CrossPosts.MAX_EDITIONS+1){posts[it%posts.size]}))
        for(change in listOf<(Post)->Post>({it.copy(body="x".repeat(4097))},{it.copy(preview=true)},
            {it.copy(parentId="parent")},{it.copy(body=it.body+"\u202ehidden")}))
            assertNull(CrossPostFusion.composeCollectedReferences(listOf(posts[0],change(posts[1]))))
        val inventory=List(1024){"https://t.co/Hidden91"}
        val metadataOnly=posts.map {it.copy(body=it.body.replace("https://t.co/","https://example.org/"),links=inventory)}
        assertNull(CrossPostFusion.composeCollectedReferences(metadataOnly))
        val withInventory=posts.map {it.copy(links=inventory)}
        project(withInventory,requireNotNull(CrossPostFusion.composeCollectedReferences(withInventory)))
    }
}
