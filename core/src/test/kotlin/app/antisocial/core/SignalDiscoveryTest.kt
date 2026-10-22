package app.antisocial.core

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SignalDiscoveryTest {
    private fun refs(html:String)=SignalDiscovery.references(Jsoup.parse(html,"https://publisher.example/news/report").body(),"https://publisher.example/news/report")
    @Test fun primaryMaterialIsSelectedWithoutCollectingNavigationOrRelatedStories() {
        val result=refs("""<nav><a href="https://agency.gov/full-report">Report</a></nav>
            <p>The study is available <a href="https://doi.org/10.1234/results">here</a>.</p>
            <p>The agency published a <a href="https://agency.gov/reports/final.pdf">full report</a>.</p>
            <aside><a href="https://elsewhere.example/research">Research</a></aside>
            <div class="related-posts"><a href="https://noise.example/study">Study</a></div>
            <p><a href="https://publisher.example/subscribe">Subscribe for research</a></p>""")
        assertEquals(listOf("https://doi.org/10.1234/results","https://agency.gov/reports/final.pdf"),result.map {it.url})
        assertEquals("Cited research",result.first().reason)
    }
    @Test fun aCitationAndAFullRecordingBecomeRoutesRatherThanFacts() {
        val result=refs("""<p>Watch the <a href="https://youtu.be/abcdefghijk?t=90">full interview</a>.</p>
            <p>The official <a href="https://data.gov/files/table.csv">dataset</a> contains the measurements.</p>""")
        assertEquals(2,result.size);assertEquals("https://www.youtube.com/watch?v=abcdefghijk",result.first().url)
        assertTrue(result.last().passage.contains("measurements"))
    }
    @Test fun genericLinksAndOrdinaryCrossPromotionDoNotBecomeAWebCrawl() {
        assertTrue(refs("""<p><a href="https://shop.example/products/new">Buy this</a><a href="https://publisher.example/about">About</a>
            <a href="https://elsewhere.example/post">Another story</a><a href="javascript:alert(1)">Full report</a></p>""").isEmpty())
    }
    @Test fun declaredCitationsWorkAcrossLanguagesAndPracticalDocumentationIsUsefulMaterial() {
        val found=refs("""<p><cite><a href="https://source.example/reports/123">調査結果</a></cite></p><p>Read the <a href="https://maker.example/manual">service manual</a>.</p>""")
        assertEquals(2,found.size);assertEquals("Cited source",found.first().reason)
        assertTrue(found.any {it.url=="https://maker.example/manual"})
    }
    @Test fun identifiersUnifyMirrorsFragmentsAndPaperAliasesWithoutInventingContent() {
        assertEquals("https://doi.org/10.1038/s41586-024-00001-1",SignalDiscovery.identity("https://www.nature.com/articles/s41586-024-00001-1#references"))
        assertNull(SignalDiscovery.identity("https://user:password@evil.example/report"));assertNull(SignalDiscovery.identity("http://unsafe.example/report"))
        assertNull(SignalDiscovery.identity("https://source.example/login"));assertNull(SignalDiscovery.identity("https://source.example:8443/report"))
        assertEquals("https://publisher.example/report",SignalDiscovery.identity("https://publisher.example/report#page2"))
    }
    @Test fun cyclesDepthAndPerCardBudgetsBoundRecursiveDiscovery() {
        val candidates=(1..20).map {SignalReference("https://source.example/report/$it","Report","Evidence","",100-it)}
        assertEquals(listOf(candidates[1],candidates[2]),SignalDiscovery.follow(candidates,0,setOf(candidates[0].url),6))
        assertEquals(1,SignalDiscovery.follow(candidates,1,emptySet(),1).size)
        assertTrue(SignalDiscovery.follow(candidates,2,emptySet(),6).isEmpty())
        assertTrue(SignalDiscovery.follow(candidates,0,emptySet(),0).isEmpty())
    }
    @Test fun longScientificPagesDoNotSpendTheFollowupBudgetOnRepeatedStructureAccessions() {
        val early=(1..600).joinToString(""){"<a href='#r$it'>$it</a>"}
        val structures=(1..20).joinToString(""){"<a href='https://doi.org/10.2210/pdb7aa$it/pdb'>7AA$it</a>"}
        val found=refs("<p>$early</p><figcaption>Structure predictions $structures</figcaption><p>The underlying study <a href='https://doi.org/10.1234/method'>describes the method</a>.</p>")
        assertEquals("https://doi.org/10.1234/method",found.first().url)
        assertEquals(1,found.count {it.reason=="Structural data"})
        assertEquals(listOf(found.first()),SignalDiscovery.follow(found,0,emptySet(),6))
        assertNull(PublicSourceRecords.paper("https://doi.org/10.2210/pdb7pzb/pdb"))
    }
    @Test fun articleExtractionRetainsEvidenceRoutesButExecutesNoPageInstructions() {
        val prose="Measurements from this investigation describe a useful change in the local water supply. ".repeat(8)
        val article=ContextArticles.extract(Payload("""<html><head><title>Water report</title><meta name="citation_doi" content="10.1234/water"></head><body><article><h1>Water report</h1><p>$prose</p><p>Read the <a href="https://agency.gov/report.pdf">full report</a>.</p><script>fetch('https://evil.example')</script></article></body></html>""","https://publisher.example/water","text/html",200),"https://publisher.example/water")
        assertEquals("10.1234/water",article.publicationDoi)
        assertEquals("https://agency.gov/report.pdf",article.references!!.single().url)
        assertFalse(article.text.contains("evil.example"))
    }
}
