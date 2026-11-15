/* anti social, GPL-3.0-or-later. Unrelated synthetic workshop captions. */
package app.antisocial.core

import org.junit.Test
import kotlin.test.*

class CaptionFormattingTest {
    private val x=Post("811001",Platform.X,"https://x.com/asworkshop/status/811001","Workshop office","Workshop repairs, ready for inspection.",publishedAt=1_790_000_000_000)
    private val instagram=x.copy(id="Workshop811002",platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/Workshop811002/",body="Workshop repairs ready for inspection.")

    @Test fun aSingleOptionalCommaRetainsEveryLiteralPathWithoutRepeatingTheSentence() {
        val posts=listOf(x,instagram);val original=posts.toList()
        val fusion=assertNotNull(CaptionFormatting.compose(posts));val runs=assertNotNull(fusion.weave)
        assertEquals(1,runs.count {!it.shared})
        val shown=fusion.segments.joinToString(" "){it.text}
        assertEquals(1,Regex("Workshop").findAll(shown).count())
        assertEquals(1,Regex(Regex.escape("ready for inspection.")).findAll(shown).count())
        for(post in posts)assertEquals(PostPresentation.content(post),runs.joinToString(" "){run->run.variants.single {CrossPosts.originalKey(post)in it.originalKeys}.text})
        assertEquals(original,posts)
    }

    @Test fun repeatedPlatformEditionsKeepTheirOwnPunctuationPaths() {
        val other=x.copy(id="811003",canonicalUrl="https://x.com/asworkshop/status/811003",body=instagram.body)
        val posts=listOf(other,instagram,x)
        val runs=assertNotNull(CaptionFormatting.compose(posts)).weave!!
        for(post in posts)assertEquals(PostPresentation.content(post),runs.joinToString(" "){it.variants.single {v->CrossPosts.originalKey(post)in v.originalKeys}.text})
    }

    @Test fun presentationDoesNotNominateAccountsOrChangeCollectionAdmission() {
        val posts=listOf(x,instagram)
        assertEquals(2,CrossPosts.group(posts, emptyList()).size)
        assertNotNull(CaptionFormatting.compose(posts))
        assertEquals(2,CrossPosts.group(posts, emptyList()).size)
    }

    @Test fun changedWordsNumbersQuestionsAndCoupledPunctuationKeepTheExistingFallback() {
        for(body in listOf("Workshop repairs approved for inspection.","Workshop repairs ready for inspection?",
            "Workshop, repairs ready for inspection.","Workshop repairs ready, for inspection.",
            "Workshop repairs 1000 ready for inspection.","Workshop repairs... ready for inspection.")) {
            assertNull(CaptionFormatting.compose(listOf(x,instagram.copy(body=body))),body)
        }
        assertNull(CaptionFormatting.compose(listOf(x.copy(body="Workshop balance 1,000 credits today."),instagram.copy(body="Workshop balance 1.000 credits today."))))
    }

    @Test fun referencesTitlesRepliesPreviewsAndOversizedSourcesAreNotReinterpreted() {
        for(post in listOf(instagram.copy(preview=true),instagram.copy(parentId="parent"),instagram.copy(title="Supplied heading"),
            instagram.copy(body="Workshop repairs ready for inspection. @ashelper"),instagram.copy(body="Workshop repairs ready for inspection. https://example.org/source"),
            instagram.copy(body="Workshop repairs ready for inspection.\u202e"),instagram.copy(body="long ".repeat(130))))assertNull(CaptionFormatting.compose(listOf(x,post)))
        assertNull(CaptionFormatting.compose(listOf(x,x)))
        assertNull(CaptionFormatting.compose(listOf(x,x.copy(id="811004",canonicalUrl="https://x.com/asworkshop/status/811004"))))
    }
    @Test fun detachedReferencesNeverShareChangedProseOrInventAnExpandedDestination() {
        val prose="Workshop volunteers repaired useful equipment, documented each replacement part, explained safe handling procedures and prepared the public room so that visitors can inspect the completed work and learn from the repair team."
        val first=x.copy(body=prose+"\n\nhttps://journal.fixture.invalid/news/workshop-handbook")
        val second=instagram.copy(body=prose)
        assertNotNull(CaptionFormatting.composeDetachedReferences(listOf(first,second)))
        assertNull(CaptionFormatting.composeDetachedReferences(listOf(first,second.copy(body=prose.replace("repaired","discarded")))))
        assertNull(CaptionFormatting.composeDetachedReferences(listOf(first.copy(body=prose),second)))
    }

}
