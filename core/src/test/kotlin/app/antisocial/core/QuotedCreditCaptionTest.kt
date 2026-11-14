/* anti social, GPL-3.0-or-later. Synthetic quotation and credit controls. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class QuotedCreditCaptionTest {
    private val at=1_800_000_000_000L
    private val quote="\"Our community workshop has published the complete repair guide for visitors. The illustrated instructions explain how to inspect every connector, compare the available materials, and prepare the equipment before the next practical demonstration. Please read the full document and bring your questions to the afternoon session.\""
    private val platforms=listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM)
    private fun profile(p:Platform)=when(p) {
        Platform.X->"https://x.com/credit_fixture"
        Platform.FACEBOOK->"https://www.facebook.com/credit_fixture"
        else->"https://www.instagram.com/credit_fixture"
    }
    private val follows=platforms.map {p->Follow(p.id,Target(p,TargetKind.PROFILE,"credit_fixture",profile(p)),"Workshop",personId="quoted-credit-fixture")}
    private fun post(p:Platform,credit:String)=Post("123456",p,when(p) {
        Platform.X->profile(p)+"/status/123456"
        Platform.FACEBOOK->profile(p)+"/posts/123456"
        else->"https://www.instagram.com/p/CreditFixture123/"
    },"Workshop","$quote - $credit",publishedAt=at,authorAccount=PostAuthorAccount(profile(p)))
    private fun originals()=platforms.map {post(it,if(it==Platform.X)"Director Vale"else"Director Mira L. Vale")}
    private fun group(posts:List<Post>,accounts:List<Follow> = follows)=CrossPosts.group(posts,accounts)
    private fun projection(posts:List<Post>,fusion:PostFusion) {
        for(post in posts) {
            val key=CrossPosts.originalKey(post)
            val text=requireNotNull(fusion.weave).joinToString(" "){run->run.variants.single {key in it.originalKeys}.text}
            assertEquals(TextWhitespace.collapse(PostPresentation.content(post)),TextWhitespace.collapse(text))
        }
    }
    @Test fun aLongLiteralQuotationAndExpandedCreditBecomeOneLinkedReadingCard() {
        val posts=originals();val before=Gson().toJson(posts)
        for(input in listOf(posts,posts.reversed(),posts.drop(1)+posts.first())) {
            val collection=group(input).single();assertEquals(posts.toSet(),collection.posts.toSet())
            val fusion=requireNotNull(CrossPostFusion.compose(collection.posts));projection(posts,fusion)
            assertEquals(1,Regex(Regex.escape(quote)).findAll(fusion.segments.joinToString(" "){it.text}).count())
            val variants=fusion.weave!!.single {!it.shared}.variants
            assertEquals(setOf("Director Vale","Director Mira L. Vale"),variants.map {it.text}.toSet())
            assertEquals(setOf(Platform.FACEBOOK,Platform.INSTAGRAM),variants.single {it.text=="Director Mira L. Vale"}.platforms)
        }
        assertEquals(before,Gson().toJson(posts))
    }
    @Test fun aCreditExpansionDoesNotEstablishCommonAccountControlOrRemoveOriginalLinks() {
        val posts=originals().take(2)
        assertEquals(2,group(posts,emptyList()).size)
        assertEquals(2,group(posts,follows.map {it.copy(personId=null)}).size)
        val selected=group(posts).single().posts
        assertEquals(posts.map {it.canonicalUrl}.toSet(),selected.map {it.canonicalUrl}.toSet())
        assertEquals(posts.map(CrossPosts::originalKey).toSet(),selected.map(CrossPosts::originalKey).toSet())
    }
    @Test fun changedSpeakersAndCreditStatusStaySeparateEvenAroundAnIdenticalQuotation() {
        val first=originals().first()
        for(credit in listOf("Director Avery Beck","Director Mira L. Beck","Former Director Vale","Director Former Vale","Director Vale Jr.","Director 2 Vale","Director @MiraVale","Director Mira & Vale")) {
            val second=post(Platform.FACEBOOK,credit)
            assertEquals(credit,2,group(listOf(first,second)).size)
        }
    }
    @Test fun changedQuotationClaimsAndDifferentDocumentsAreNotNameExpansions() {
        val first=originals().first();val second=originals()[1]
        for(body in listOf(second.body.replace("has published","has not published"),second.body.replace("afternoon session","morning session"),second.body.replace("every connector","20 connectors"),second.body.replace("complete repair guide","complete repair guide?")))
            assertEquals(body,2,group(listOf(first,second.copy(body=body))).size)
        val destinations=listOf(first.copy(body=first.body+" https://manual.example/one"),second.copy(body=second.body+" https://manual.example/two"))
        assertEquals(2,group(destinations).size)
    }
    @Test fun aShortSloganStaleCreditOrIncompleteSourceDoesNotQualify() {
        val posts=originals().take(2)
        assertEquals(2,group(posts.map {it.copy(body=it.body.replace(quote,"\"Everyone is welcome.\""))}).size)
        for(second in listOf(posts[1].copy(publishedAt=at+600_001),posts[1].copy(publishedAt=null),posts[1].copy(preview=true),posts[1].copy(parentId="other"),posts[1].copy(body=posts[1].body+"\u202e"),posts[1].copy(body="Text ".repeat(1000)+posts[1].body)))
            assertEquals(2,group(listOf(posts[0],second)).size)
    }
    @Test fun curlyQuotationsAndTheirExactDocumentReferencesKeepEveryLiteralPath() {
        val posts=originals().map {it.copy(body=it.body.replaceFirst('"','“').replace(".\" - ",".” - ")+" https://manual.example/Guide?Edition=New")}
        val fusion=requireNotNull(CrossPostFusion.compose(group(posts).single().posts));projection(posts,fusion)
        assertTrue(fusion.weave!!.flatMap {it.variants}.any {it.text=="https://manual.example/Guide?Edition=New"})
        assertEquals(2,group(listOf(posts[0],posts[1].copy(body=posts[1].body.replace("Edition=New","Edition=new")))).size)
    }
    @Test fun repeatedOriginalsFromOneAccountKeepBothSourceKeysAndTheirWholeCredits() {
        val x=originals().first();val repeated=x.copy(id="123457",canonicalUrl=profile(Platform.X)+"/status/123457",body=x.body.replace("Director Vale","Director Mira L. Vale"))
        val posts=listOf(x,repeated)
        assertTrue(CrossPosts.sameSourceRepeats(posts));assertEquals(1,group(posts).size)
        val fusion=requireNotNull(CrossPostFusion.compose(posts));projection(posts,fusion)
        assertEquals(posts.map(CrossPosts::originalKey).toSet(),fusion.weave!!.single {!it.shared}.variants.flatMap {it.originalKeys}.toSet())
    }
    @Test fun aShortCreditCannotBridgeTwoDifferentExpandedSpeakers() {
        val posts=listOf(post(Platform.X,"Director Vale"),post(Platform.FACEBOOK,"Director Mira Vale"),post(Platform.INSTAGRAM,"Director Rowan Vale"))
        assertNull(CrossPostFusion.compose(posts))
        val full=posts.drop(1).map(CrossPosts::originalKey).toSet()
        assertFalse(group(posts).any {group->group.posts.map(CrossPosts::originalKey).containsAll(full)})
    }
}
