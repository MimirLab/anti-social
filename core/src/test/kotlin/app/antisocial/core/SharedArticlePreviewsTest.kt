/* anti social, GPL-3.0-or-later. Unrelated synthetic article fixtures. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Test
import kotlin.test.*

class SharedArticlePreviewsTest {
    private val at=1_790_000_000_000L
    private val article="https://journal.fixture.invalid/news/2027/workshop-equipment-handbook"
    private val caption="The community workshop published its complete equipment handbook. Visitors can inspect the checkout procedure, compare the maintenance records, ask coordinators about accessible tools and read the room schedule before reserving a workbench."
    private val title="Community workshop publishes equipment handbook for visiting repair volunteers"
    private val facebook=Post("845001",Platform.FACEBOOK,"https://www.facebook.com/asworkshop/posts/845001","Workshop",caption+"\n\n"+article,publishedAt=at,
        media=listOf(Media("https://images.fixture.invalid/handbook-poster.jpg")),links=listOf(article))
    private val instagram=facebook.copy(id="Workshop845002",platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/Workshop845002/",body=caption,publishedAt=at+1000,links=emptyList())
    private val preview=Post("845003",Platform.X,"https://x.com/asworkshop/status/845003","Workshop","journal.fixture.invalid/news/2027/…",title,publishedAt=at-1000,
        media=listOf(Media("https://pbs.twimg.com/card_img/845003/HandbookPreview",description="Linked preview: $title\nSource excerpt: The handbook describes equipment checkout and maintenance.")),links=listOf(article),
        provenance=Provenance(Source(Platform.X,"nitter","https://nitter.fixture.invalid"),at+5000,"https://nitter.fixture.invalid/asworkshop"))
    private val follows=listOf(Platform.FACEBOOK,Platform.INSTAGRAM,Platform.X).map {Follow(it.id,Target(it,TargetKind.PROFILE,"asworkshop",when(it){Platform.FACEBOOK->"https://www.facebook.com/asworkshop";Platform.INSTAGRAM->"https://www.instagram.com/asworkshop/";else->"https://x.com/asworkshop"}),"Workshop",personId="workshop")}
    private fun groups(posts:List<Post>,accounts:List<Follow> = follows)=CrossPosts.group(posts,accounts,posts.associate {CrossPosts.originalKey(it) to setOf(it.platform.id)})

    @Test fun acquiredArticlePreviewJoinsTheExplicitSameResourceAnnouncementAndItsCaptionEditions() {
        val posts=listOf(facebook,instagram,preview);val before=posts.map(Gson()::toJson)
        for(order in listOf(posts,posts.reversed()))assertEquals(posts.toSet(),groups(order).single().posts.toSet())
        assertTrue(CrossPosts.articlePreviewCollection(posts));assertEquals(setOf(CrossPosts.originalKey(preview)),CrossPosts.articlePreviewKeys(posts))
        assertEquals(before,posts.map(Gson()::toJson));assertEquals(2,posts.flatMap {it.media}.map {it.url}.distinct().size)
    }
    @Test fun captionEditionsWithADetachedArticleReferenceKeepOneLiteralProsePath() {
        val other=instagram.copy(id="845004",platform=Platform.X,canonicalUrl="https://x.com/asworkshop/status/845004")
        val posts=listOf(facebook,instagram,other)
        val fusion=assertNotNull(CaptionFormatting.composeDetachedReferences(posts))
        val text=fusion.segments.joinToString("\n"){it.text}
        assertEquals(1,Regex(Regex.escape(caption)).findAll(text).count())
        for(post in posts) {
            val restored=fusion.segments.filter {CrossPosts.originalKey(post)in it.originalKeys}.joinToString(" "){it.text}
            assertEquals(TextWhitespace.collapse(PostPresentation.content(post)).trim(),TextWhitespace.collapse(restored).trim())
        }
    }
    @Test fun domainHeadlineAndPictureSimilarityCannotReplaceTheExactCompleteArticle() {
        for(other in listOf(preview.copy(links=listOf(article+"-different")),preview.copy(links=listOf("https://journal.fixture.invalid/")),preview.copy(links=emptyList()),preview.copy(links=listOf(article+"?token=private")))) {
            assertFalse(CrossPosts.articlePreviewCollection(listOf(facebook,other)));assertEquals(2,groups(listOf(facebook,other)).size)
        }
    }
    @Test fun ordinaryImageObservationCannotSplitAnOtherwiseLiteralArticleCaptionFamily() {
        val frame=requireNotNull(VisualFrames.encode(1.0,40.0,0,0,List(48){128},ByteArray(4096){(it%251).toByte()}))
        val posts=listOf(facebook,instagram,preview).map {p->p.copy(media=p.media.map {it.copy(attachmentFingerprint=frame)})}
        assertTrue(CrossPosts.articlePreviewCollection(posts));assertEquals(posts.toSet(),groups(posts).single().posts.toSet())
        val different=instagram.copy(links=listOf("https://journal.fixture.invalid/news/a-different-handbook"))
        assertFalse(CrossPosts.articlePreviewCollection(listOf(facebook,preview,different)))
    }
    @Test fun actualCommentaryIsNotMistakenForAnExtractedLinkCard() {
        for(other in listOf(preview.copy(body="This report contains an important correction to yesterday's announcement."),
            preview.copy(media=preview.media.map {it.copy(description="An authored photograph")}),preview.copy(provenance=null),preview.copy(title="Different supplied heading"))) {
            assertFalse(CrossPosts.articlePreviewCollection(listOf(facebook,other)))
        }
    }
    @Test fun publisherIdentityAndShortPublicationWindowAreRequiredForFeedAdmission() {
        assertEquals(2,groups(listOf(facebook,preview),emptyList()).size)
        assertEquals(2,groups(listOf(facebook,preview),follows.map {it.copy(personId=it.id)}).size)
        val late=preview.copy(publishedAt=at+11*60_000);assertFalse(CrossPosts.articlePreviewCollection(listOf(facebook,late)));assertEquals(2,groups(listOf(facebook,late)).size)
    }
    @Test fun unrelatedCaptionLeavesAndRepliesCannotBorrowTheArticleWitness() {
        for(other in listOf(instagram.copy(body="A different concert has been cancelled."),instagram.copy(parentId="parent"),instagram.copy(preview=true))) {
            assertFalse(CrossPosts.articlePreviewCollection(listOf(facebook,preview,other)))
        }
        assertEquals(2,groups(listOf(facebook,preview,instagram.copy(body="A different concert has been cancelled."))).size)
    }
    @Test fun articleWitnessMustBePresentInTheSourceNotGuessedFromThePreview() {
        val without=facebook.copy(body=caption,links=emptyList())
        assertFalse(CrossPosts.articlePreviewCollection(listOf(without,preview)));assertEquals(2,groups(listOf(without,preview)).size)
        assertFalse(CrossPosts.articlePreviewCollection(listOf(preview,preview.copy(id="845004",canonicalUrl="https://x.com/asworkshop/status/845004"))))
    }
}
