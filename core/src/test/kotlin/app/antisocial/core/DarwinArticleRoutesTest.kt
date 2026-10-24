package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DarwinArticleRoutesTest {
    private val routes=DarwinArticleRoutes()
    @Test fun publisherEditionsRetainExactIdentifiersAndExcludeIndexesAndVideo() {
        assertEquals("https://text.npr.org/nx-s1-1234567",routes.publisherEdition("https://www.npr.org/2026/10/20/nx-s1-1234567/story")?.url)
        assertEquals("https://www.cbc.ca/lite/story/1.1234567",routes.publisherEdition("https://www.cbc.ca/news/canada/story-1.1234567")?.url)
        assertEquals("https://lite.cnn.com/2026/10/20/politics/story",routes.publisherEdition("https://edition.cnn.com/2026/10/20/politics/story/index.html")?.url)
        for(u in listOf("https://npr.org/","https://cnn.com/2026/10/20/videos/story","https://npr.org/1234567?token=private"))assertNull(routes.publisherEdition(u))
    }
    @Test fun everyImportedMediumFamilyCanRouteAnExactArticleIncludingCustomDomains() {
        val paths=routes.medium("https://medium.com/@author/laptop-repair-123456abcdef")
        assertEquals(setOf("scribe","libMedium","small","freedium"),paths.map {it.route}.toSet())
        assertEquals(14,paths.size)
        assertTrue(paths.first {it.route=="freedium"}.url.contains("https%3A%2F%2Fmedium.com%2F%40author%2F"))
        assertTrue(routes.medium("https://author.medium.com/repair-123456abcdef").first().url.contains("/@author/repair-"))
        assertTrue(routes.medium("https://towardsdatascience.com/repair-123456abcdef").isNotEmpty())
        for(u in listOf("https://medium.com/@author","https://medium.com.evil.example/repair-123456abcdef","https://medium.com/login/repair-123456abcdef"))assertTrue(routes.medium(u).isEmpty())
    }
    @Test fun archivesCoverEveryDarwinPublisherClassWithoutAccountOrAssetRequests() {
        for(u in listOf("https://nytimes.com/2026/story","https://lemonde.fr/article/2026/10/20/story.html","https://spiegel.de/story-a-12345","https://404media.co/a-useful-report/","https://statnews.com/2026/10/20/report/"))assertTrue(u,routes.preferArchives(u))
        for(u in listOf("https://nytimes.com/subscribe/","https://nytimes.com/story.pdf","https://nytimes.com/story?article_code=private","https://spiegel.de/","https://lemonde.fr/search/"))assertFalse(u,routes.preferArchives(u))
    }
    @Test fun exactArticleIdentityPreservesMeaningfulQueriesAndRejectsCredentials() {
        assertTrue(routes.sameArticle("https://www.publisher.example/report?id=42&utm_source=chat","https://publisher.example/report/?id=42"))
        assertTrue(routes.sameArticle("https://publisher.example/report?id=42","http://www.publisher.example/report?id=42"))
        assertFalse(routes.sameArticle("https://publisher.example/report","http://publisher.example:8080/report"))
        assertFalse(routes.sameArticle("https://publisher.example/report?id=42","https://publisher.example/report?id=43"))
        for(u in listOf("https://user:pass@publisher.example/report","https://publisher.example/report?email=reader","https://127.0.0.1/report","http://publisher.example/report","https://publisher.example:8443/report"))assertFalse(routes.recoverable(u))
    }
    @Test fun scientificIdentifiersAreExactAcrossAllDonorPublisherForms() {
        val expected="10.1234/test";val hosts=listOf("onlinelibrary.wiley.com","science.org","pubs.acs.org","www.tandfonline.com","journals.sagepub.com","pnas.org","dl.acm.org")
        for(h in hosts)assertEquals(h,expected,routes.scientific("https://$h/doi/full/10.1234/TEST")?.doi)
        assertEquals(expected,routes.scientific("https://link.springer.com/article/10.1234/test")?.doi)
        assertEquals(expected,routes.scientific("https://journals.plos.org/plosone/article?id=10.1234/test")?.doi)
        assertEquals("10.1038/s41586-1234",routes.scientific("https://www.nature.com/articles/s41586-1234")?.doi)
        assertEquals("pmid:123456",routes.scientific("https://pubmed.ncbi.nlm.nih.gov/123456/")?.key)
        assertNull(routes.scientific("https://doi.org/10.1234/test.pdf"))
        assertNull(routes.scientific("https://notnature.com/articles/s41586-1234"))
    }
    @Test fun reviewedMetadataPublishersRequireOneUnambiguousDoi() {
        assertTrue(routes.metadataPublisher("https://ieeexplore.ieee.org/document/1234"))
        assertTrue(routes.metadataPublisher("https://www.jstor.org/stable/1234"))
        assertFalse(routes.metadataPublisher("https://www.jstor.org/login"))
        assertEquals("10.1234/report",routes.metadataDoi("<meta name=citation_doi content='10.1234/report'><meta name='dc.identifier' content='https://doi.org/10.1234/REPORT'>")?.doi)
        assertNull(routes.metadataDoi("<meta name=citation_doi content='10.1234/report'><meta name='prism.doi' content='10.1234/different'>"))
    }
    @Test fun openAlexRequiresMatchingDoiAndExplicitPublicAccessAndRetainsEdition() {
        val d=JsonParser.parseString("""{"doi":"https://doi.org/10.1234/report","is_retracted":true,"locations":[{"is_oa":false,"landing_page_url":"https://publisher.example/paywall"},{"is_oa":true,"source":{"type":"repository"},"landing_page_url":"https://arxiv.org/abs/1234.56789","pdf_url":"https://arxiv.org/pdf/1234.56789","version":"submittedVersion","license":"cc-by"},{"is_oa":true,"landing_page_url":"http://arbitrary.example/report"}]}""").asJsonObject
        val paths=routes.openAlex(d,"10.1234/report")
        assertEquals(2,paths.size);assertEquals("submittedVersion",paths.first().version);assertEquals("cc-by",paths.first().license);assertTrue(paths.all {it.retracted});assertTrue(paths.last().pdf)
        assertTrue(routes.openAlex(d,"10.1234/different").isEmpty())
    }
    @Test fun europePmcMatchesDoiOrExactMedPmidAndOnlyPublicFullText() {
        val d=JsonParser.parseString("""{"resultList":{"result":[{"id":"123","source":"MED","doi":"10.1234/report","pmcid":"PMC123","inPMC":"Y","isOpenAccess":"Y","authMan":"Y","fullTextUrlList":{"fullTextUrl":[{"availabilityCode":"S","documentStyle":"html","url":"https://publisher.example/subscribe"},{"availabilityCode":"OA","documentStyle":"html","url":"https://europepmc.org/articles/PMC123"}]}}]}}""").asJsonObject
        val paths=routes.europePmc(d,ScientificArticle("pmid:123",pmid="123"))
        assertEquals(1,paths.size);assertEquals("https://pmc.ncbi.nlm.nih.gov/articles/PMC123/",paths.single().url);assertEquals("acceptedVersion",paths.single().version)
        assertTrue(routes.europePmc(d,ScientificArticle("pmid:124",pmid="124")).isEmpty())
        assertEquals(paths,routes.europePmc(d,ScientificArticle("pmc:PMC123",pmcid="PMC123")))
        assertTrue(routes.europePmc(d,ScientificArticle("pmc:PMC124",pmcid="PMC124")).isEmpty())
        assertTrue(routes.europePmc(d,ScientificArticle("unknown")).isEmpty())
    }
    @Test fun knownPmcIdentifiersNeedNoSearchAndNeverComeFromLookalikeHosts() {
        for(url in listOf("https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/","https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12345/","https://europepmc.org/articles/PMC12345","https://www.ebi.ac.uk/europepmc/webservices/rest/PMC12345/fullTextXML"))
            assertEquals(url,"PMC12345",routes.scientific(url)?.pmcid)
        for(url in listOf("https://pmc.ncbi.nlm.nih.gov.evil.example/articles/PMC12345/","https://europepmc.org/articles/PMC12345?token=secret","https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/supplement","https://pmc.ncbi.nlm.nih.gov/articles/PMC0/"))assertNull(url,routes.scientific(url))
    }

}
