package app.antisocial.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class JatsArticlesTest {
    private val url="https://www.ebi.ac.uk/europepmc/webservices/rest/PMC12345/fullTextXML"
    private val original="https://doi.org/10.1234/repair"
    private fun fixture()=javaClass.getResourceAsStream("/jats-repair.xml")!!.reader().use {it.readText()}
    private fun read(raw:String=fixture(),retrieved:String=url,origin:String=original,type:String="application/xml")=
        ContextArticles.extract(Payload(raw,retrieved,type,200),origin,1234,readerChecks=true)
    private fun rejected(raw:String=fixture(),retrieved:String=url,origin:String=original,type:String="application/xml") {
        assertThrows(SourceFailure::class.java){read(raw,retrieved,origin,type)}
    }
    @Test fun completeStructuredCopyRetainsFindingsMethodsCaptionsDataAndCitations() {
        val result=read()
        assertEquals("Battery connector retention study",result.title);assertEquals("Alex Smith",result.author)
        assertEquals("Practical Research",result.siteName);assertEquals("10.1234/repair",result.publicationDoi)
        assertEquals(java.time.Instant.parse("2026-10-20T00:00:00Z").toEpochMilli(),result.publishedAt)
        assertEquals("https://creativecommons.org/licenses/by/4.0/",result.access!!.license)
        assertFalse(result.truncated);assertEquals(original,result.originalUrl);assertEquals(url,result.finalUrl)
        assertTrue(result.blocks.any {it.kind==ContextArticleBlockKind.HEADING&&it.text=="Abstract"})
        assertTrue(result.text.contains("retention force of 12 N after twenty cycles"))
        assertTrue(result.text.contains("Part: Connector A; Force: 12 N"))
        assertTrue(result.text.contains("Measurements used the same calibrated fixture."))
        assertTrue(result.text.contains("Connector orientation. The keyed edge"))
        assertTrue(result.text.contains("including the unusually weak connector retained in the sample"))
        val method=result.blocks.single {"retention force of 12 N" in it.text}
        assertTrue(method.links.containsAll(listOf("https://doi.org/10.1234/guide","https://maker.example.org/manual")))
        assertEquals(setOf("https://doi.org/10.1234/guide","https://maker.example.org/manual"),result.references!!.map {it.url}.toSet())
        assertTrue(result.text.contains("Sam Ng"));assertFalse(result.text.contains("NgSam"))
    }
    @Test fun listsCodeAndSupportedEquationsRemainReadableWithoutDoubleRendering() {
        val blocks=read().blocks
        assertEquals(listOf("1.","2."),blocks.filter {it.kind==ContextArticleBlockKind.LIST}.map {it.listMarker})
        assertEquals("if capacity < 0:\n    raise Error(\"invalid\")",blocks.single {it.kind==ContextArticleBlockKind.CODE}.text)
        assertTrue(blocks.any {"(C)/(C0)" in it.text});assertTrue(blocks.any {"x^2 + y^2" in it.text})
        assertFalse(blocks.any {"duplicate-equation" in it.text})
    }
    @Test fun imageOnlyTablesKeepTheirCaptionAndSourceWithoutClaimingToReadPixels() {
        val xml=fixture().replace(Regex("<table>.*?</table>",RegexOption.DOT_MATCHES_ALL),"<graphic xlink:href=\"table.png\"/>")
        val block=read(xml).blocks.single {it.kind==ContextArticleBlockKind.CAPTION&&"Table 1" in it.text}
        assertTrue(block.text.contains("Table image available at the source."))
        assertEquals(listOf("https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/#table1"),block.links)
    }
    @Test fun onlyRecognizedExactEndpointsAcceptXml() {
        for(bad in listOf(url.replace("www.ebi.ac.uk","ebi.ac.uk.evil.example"),url+"?redirect=1",url.replace("PMC12345","PMC12346"),url.replace("https:","http:")))rejected(retrieved=bad)
        rejected(type="text/plain");rejected(retrieved="https://repository.example/paper.xml")
    }
    @Test fun incompletePublicationDatesDoNotInventAMonthOrDay() {
        assertNull(read(fixture().replace("<pub-date publication-format=\"electronic\" iso-8601-date=\"2026-10-20\"/>","<pub-date><year>2026</year></pub-date>")).publishedAt)
    }
    @Test fun rootIdentifiersMustAgreeWithTheRequestNotWithACitation() {
        rejected(origin="https://doi.org/10.1234/guide")
        rejected(origin="https://pubmed.ncbi.nlm.nih.gov/654321/")
        rejected(origin="https://pmc.ncbi.nlm.nih.gov/articles/PMC54321/")
        rejected(fixture().replace("<article-id pub-id-type=\"doi\">10.1234/repair</article-id>",""))
        rejected(fixture().replace("<article-id pub-id-type=\"doi\">10.1234/repair</article-id>","<article-id pub-id-type=\"doi\">10.1234/repair</article-id><article-id pub-id-type=\"doi\">broken</article-id>"))
        assertEquals("10.1234/repair",read(origin="https://pubmed.ncbi.nlm.nih.gov/123456/").publicationDoi)
    }
    @Test fun wrongOrConflictingPrimaryMetadataIsNeverSalvagedFromNestedMaterial() {
        rejected(fixture().replace("<article-id pub-id-type=\"pmcid\">PMC12345", "<article-id pub-id-type=\"pmcid\">PMC54321"))
        rejected(fixture().replace("</article-meta>","<article-id pub-id-type=\"pmcid\">PMC54321</article-id></article-meta>"))
        rejected(fixture().replace("</front>","</front><front><article-meta/></front>"))
        rejected(fixture().replace("</body>","</body><body><p>Other body</p></body>"))
    }
    @Test fun abstractOnlyErrorAndTruncatedCopiesAreNotFullText() {
        rejected(fixture().replace(Regex("<body>.*?</body>",RegexOption.DOT_MATCHES_ALL),""))
        rejected("<response><error>Not available</error></response>")
        rejected(fixture().dropLast(15))
        rejected(fixture().replace("<body>","<body><unclosed>"))
    }
    @Test fun excludedNestedArticlesAreExplicitlyIncomplete() {
        val result=read(fixture().replace("</article>","<sub-article><body><p>Nested correction that has not been extracted.</p></body></sub-article></article>"))
        assertTrue(result.truncated);assertFalse(result.text.contains("Nested correction"))
    }
    @Test fun externalDtdNeverMakesANetworkRequest() {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("<!ENTITY injected 'unexpected text'>"))
            val xml=fixture().replace("<article ","<!DOCTYPE article SYSTEM '${server.url("/jats.dtd")}'>\n<article ")
            assertEquals("Battery connector retention study",read(xml).title)
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun entityDeclarationsAreRejectedBeforeExpansionOrExternalAccess() {
        for(declaration in listOf("<!DOCTYPE article [<!ENTITY payload SYSTEM 'file:///etc/passwd'>]>","<!DOCTYPE article [<!ENTITY a 'ha'><!ENTITY b '&a;&a;&a;'>]>","<!DOCTYPE article [<!ENTITY % remote SYSTEM 'https://example.org/entities'>%remote;]>"))
            rejected(fixture().replace("<article ",declaration+"<article "))
        rejected(fixture().replace("retention force", "&unknown; retention force"))
        assertTrue(read(fixture().replace("retention force", "&amp; retention force")).text.contains("& retention force"))
    }
    @Test fun literalEntityExamplesInsideCodeAndCommentsRemainInert() {
        val xml=fixture().replace("if capacity < 0:","<!ENTITY example 'literal'>\nif capacity < 0:")
            .replace("<body>","<body><!-- <!ENTITY harmless 'comment'> -->")
        assertTrue(read(xml).text.contains("<!ENTITY example 'literal'>"))
    }
    @Test fun citationsInsideBodySectionsRetainTypedBareIdentifiersAndSupplementDescriptions() {
        val xml=fixture().replace("<back>","<body-references>").replace("</back>","</body-references>")
            .replace("</body>","<supplementary-material id='supp1'><media xlink:href='data.zip'><label>Supplementary Data</label><caption><p>Complete connector measurements, calibration records and an explanation of the excluded sample.</p></caption></media></supplementary-material>")
            .replace("</body-references>","</body-references></body>")
            .replace("<pub-id pub-id-type=\"doi\">10.1234/guide</pub-id>","<ext-link ext-link-type='doi' xlink:href='10.1234/guide'/><ext-link ext-link-type='pmid' xlink:href='123457'/><ext-link ext-link-type='google-scholar' xlink:href='Connector guide text'/>")
        val document=read(xml)
        assertTrue(document.references!!.any {it.url=="https://doi.org/10.1234/guide"})
        assertTrue(document.blocks.flatMap {it.links}.contains("https://pubmed.ncbi.nlm.nih.gov/123457/"))
        assertFalse(document.blocks.flatMap {it.links}.any {"Connector%20guide" in it})
        assertTrue(document.text.contains("Complete connector measurements, calibration records and an explanation of the excluded sample."))
        assertTrue(document.blocks.any {it.kind==ContextArticleBlockKind.CAPTION&&it.links.contains("https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/#supp1")})
    }
    @Test fun structuralLimitsRejectDisproportionateWorkBeforeExtracting() {
        rejected(" ".repeat(4*1024*1024)+fixture())
        rejected(fixture().replace("<body>","<body>"+"<sec>".repeat(100)).replace("</body>","</sec>".repeat(100)+"</body>"))
        rejected(fixture().replace("<body>","<body>"+"<p/>".repeat(30_000)))
        rejected(fixture().replace("<body>","<body "+(1..65).joinToString(" "){"a$it='v'"}+">"))
        rejected(fixture().replace("<body>","<body a='"+"x".repeat(8193)+"'>"))
    }
    @Test fun longReadableArticlesAreBoundedAndMarkedIncomplete() {
        val xml=fixture().replace("</body>","<p>"+"A measured finding is preserved with its units and method. ".repeat(5000)+"</p></body>")
        val result=read(xml)
        assertTrue(result.truncated);assertTrue(result.blocks.sumOf {it.text.length}<=ContextArticles.MAX_TEXT_CHARS)
    }
}
