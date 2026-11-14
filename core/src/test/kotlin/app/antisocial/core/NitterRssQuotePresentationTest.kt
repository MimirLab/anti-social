/* anti social, GPL-3.0-or-later. Synthetic source-rendered quote/link echoes. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class NitterRssQuotePresentationTest {
    private val root="https://nitter.fixture.invalid"
    private val caption="A live look inside the community workshop."
    private val headline="Watch the public workshop demonstration and inspect every repair step."
    private val name="Local Workshop News"
    private val destination="$root/i/broadcasts/RepairShowComplete"
    private val shortened="nitter.fixture.invalid/i/broadcasts/RepairShow…"
    private val quote="$root/WorkshopNews/status/910002#m"
    private val body="$caption $name (@WorkshopNews) $headline $shortened Link $name $headline $destination — $quote"
    private val source=Source(Platform.X,"nitter",root)
    private fun post()=Post("910001",Platform.X,"https://x.com/RepairCrew/status/910001","RepairCrew",body,
        "RT by @Workshop: $caption",publishedAt=1_800_000_000_000,
        provenance=Provenance(source,1_800_000_001_000,"$root/Workshop/rss"),
        links=listOf(destination,quote),publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,"https://x.com/Workshop")))

    @Test fun aGeneratedPreviewEchoSharesItsLiteralHeadlineWithoutLosingQuoteOrDestination() {
        val raw=post();val before=Gson().toJson(raw);val text=PostPresentation.text(raw)
        assertEquals(1,Regex(Regex.escape(headline)).findAll(text.body).count())
        assertTrue(text.body.startsWith("$caption $name (@WorkshopNews) $headline"))
        assertTrue(text.body.contains(destination));assertTrue(text.body.endsWith("— $quote"))
        assertFalse(text.body.contains(" Link $name "));assertEquals("Reposted by @Workshop",text.attribution)
        assertEquals(before,Gson().toJson(raw));assertEquals(listOf(destination,quote),raw.links)
    }

    @Test fun anExactFullDestinationAndAnOwnProfileKeepTheSameCompactReading() {
        val raw=post().copy(title=caption,body=body.replace(shortened,destination),publicRelations=null,
            canonicalUrl="https://x.com/Workshop/status/910001",author="Workshop")
        val text=PostPresentation.text(raw)
        assertEquals(1,Regex(Regex.escape(headline)).findAll(text.body).count())
        assertEquals(1,Regex(Regex.escape(destination)).findAll(text.body).count())
        assertEquals("",text.title);assertNull(text.attribution)
    }

    @Test fun changedClaimsPublishersOrAbbreviatedDestinationsKeepTheirWholeSourceBody() {
        val altered=listOf(
            body.replace("Link $name $headline","Link $name ${headline.replace("every","no")}"),
            body.replace("Link $name $headline","Link $name ${headline.replace("every repair","12 repair")}"),
            body.replace("Link $name ","Link Another Publisher "),
            body.replace(shortened,"nitter.fixture.invalid/other/RepairShow…"),
            body.replace(shortened,"nitter.fixture.invalid/…"),
            body.replace(shortened,"nitter.fixture.invalid/i/broadcasts/RepairShow… Additional commentary."),
            body.replace(" $destination —"," https://elsewhere.invalid/RepairShowComplete —"))
        altered.forEach {value->assertEquals(value,PostPresentation.text(post().copy(body=value)).body)}
    }

    @Test fun missingLinkEvidenceWrongFooterOrOtherAcquisitionContextsCannotEraseText() {
        val raw=post()
        val other=listOf(raw.copy(links=emptyList()),raw.copy(links=listOf(destination)),raw.copy(publicRelations=null),
            raw.copy(publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,"https://x.com/OtherCrew"))),
            raw.copy(body=body.replace("— $quote","— ${quote.replace("WorkshopNews","OtherNews")}")),
            raw.copy(provenance=raw.provenance!!.copy(finalUrl="$root/Workshop")),
            raw.copy(provenance=raw.provenance!!.copy(source=source.copy(frontend="other"))),
            raw.copy(provenance=raw.provenance!!.copy(finalUrl="https://elsewhere.invalid/Workshop/rss")),
            raw.copy(platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/RepairCrew/posts/910001"),
            raw.copy(preview=true),raw.copy(parentId="parent"))
        other.forEach {value->assertEquals(value.body,PostPresentation.text(value).body)}
    }

    @Test fun authoredCopiesNestedQuotesAndIncompleteGeneratedHeadingsAreNotPreviewEvidence() {
        val raw=post()
        for(value in listOf(raw.copy(title="RT by @Workshop: $body"),raw.copy(title="RT by @Workshop: A live look…"),
            raw.copy(body=body.replace(" Link $name "," Nested Crew (@NestedCrew) Link $name ")),
            raw.copy(body=body.replace(" Link $name "," Link $name $headline $destination Link $name "))))
            assertEquals(value.body,PostPresentation.text(value).body)
    }
}
