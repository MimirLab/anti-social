package app.antisocial.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PublicArticleResolverTest {
    private val original="https://www.nytimes.com/2026/10/19/world/report.html"
    private val stamp="20261019120000"
    private val replay="https://web.archive.org/web/${stamp}id_/$original"
    private val article="<html><head><title>A public report</title><meta name=author content='Reporter'></head><body><article><h1>A public report</h1><p>The workshop published its repair findings and included the measured battery capacity. The replacement was tested under a controlled workload for several hours, and the results remained consistent with the manual.</p><p>The author cautioned that a similar looking part is not necessarily compatible. Readers should consult the original documentation and retain the serial number when comparing different editions of the procedure.</p></article></body></html>"
    private data class Call(val url:String,val headers:Map<String,String> = emptyMap(),val head:Boolean=false,val strict:Boolean=true)
    private class Transport:ArticleTransport {
        override var originalAllowed=true
        override var session=NetworkSession(NetworkPolicy())
        override var mode:NetworkPolicy
            get()=session.policy
            set(value){if(value!=session.policy)session=NetworkSession(value)}
        val calls=mutableListOf<Call>()
        var sentConditionals=true
        var documents:(Call)->GatewayDocument={GatewayDocument(503,"unavailable",emptyMap(),"",it.url)}
        var indexes:(String)->String={if(it.startsWith("https://web.archive.org"))"[[\"timestamp\",\"original\"]]"else "<title>No results</title>"}
        override suspend fun document(url:String,source:String,headers:Map<String,String>,head:Boolean,strict:Boolean,purpose:FetchPurpose,expectedSession:NetworkSession):GatewayDocument {
            assertSame(expectedSession,session)
            val c=Call(url,headers,head,strict);calls+=c;return documents(c).copy(conditional=headers.isNotEmpty()&&sentConditionals)
        }
        override suspend fun index(url:String,source:String,origins:Set<String>,expectedSession:NetworkSession):Payload {assertSame(expectedSession,session);calls+=Call(url);return Payload(indexes(url),url,"application/json",200)}
    }
    private fun body(url:String,text:String=article,headers:Map<String,List<String>> = emptyMap())=GatewayDocument(200,"OK",mapOf("Content-Type" to listOf("text/html"))+headers,text,url)
    private fun resolver(t:Transport)=PublicArticleResolver(t,clock={java.time.Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()})
    @Test fun knownPublisherUsesVerifiedArchiveBeforeAnyOriginalAndPreservesCaptureDate():Unit=runBlocking {
        val t=Transport();t.indexes={if(it.startsWith("https://web.archive.org"))"""[["timestamp","original"],["$stamp","$original"]]"""else "<title>No results</title>"};t.documents={body(it.url)}
        val result=resolver(t).conditional(original,original) as ContextArticleFetch.Ready
        assertEquals(original,result.document.originalUrl);assertEquals(replay,result.document.finalUrl)
        assertEquals("Existing archive capture",result.document.access!!.route);assertNotNull(result.document.access!!.archivedAt)
        assertFalse(t.calls.any {it.url==original});assertTrue(t.calls.last().strict)
        assertEquals("Reporter",result.document.author)
    }
    @Test fun wrongArchiveIdentityCannotBecomeTheRequestedArticle():Unit=runBlocking {
        val t=Transport();t.indexes={if(it.startsWith("https://web.archive.org"))"""[["timestamp","original"],["$stamp","https://nytimes.com/different"]]"""else "<title>No results</title>"};t.documents={body(it.url)}
        val result=resolver(t).conditional(original,original) as ContextArticleFetch.Ready
        assertEquals(original,result.document.finalUrl);assertEquals("Original publisher",result.document.access!!.route)
        assertFalse(t.calls.any {it.url.contains("/web/")})
    }
    @Test fun archiveTodayRequiresMatchingCaptureMetadataAndRetainsItsDate():Unit=runBlocking {
        val t=Transport();t.indexes={"<a href='https://archive.ph/aB123'>Saved report title</a>"};t.documents={body(it.url,"<input id=SHARE_LONGLINK value='https://archive.ph/2026.10.19-120000/$original'>$article")}
        val result=resolver(t).conditional(original,original) as ContextArticleFetch.Ready
        assertEquals("https://archive.ph/aB123",result.document.finalUrl);assertNotNull(result.document.access!!.archivedAt)
        assertFalse(result.document.text.contains("SHARE_LONGLINK"))
    }
    @Test fun unrelatedArchiveTodayPageAndLoginTeasersAreNotWorkingContent():Unit=runBlocking {
        val t=Transport();t.indexes={if(it.startsWith("https://archive.ph"))"<a href='https://archive.ph/aB123'>Saved report title</a>"else "[[\"timestamp\",\"original\"]]"}
        t.documents={c->if(c.url.contains("archive.ph"))body(c.url,article)else body(c.url,"<title>Sign in</title><form><input type=password>Log in to continue</form>")}
        val error=assertThrows(SourceFailure::class.java){runBlocking {resolver(t).conditional(original,original)}}
        assertTrue(error.message!!.contains("No readable public copy"))
    }
    @Test fun originalOffStillAllowsThePublisherTextEditionAndNeverVisitsTheOriginal():Unit=runBlocking {
        val t=Transport();t.originalAllowed=false;t.documents={body(it.url)}
        val result=resolver(t).conditional("https://npr.org/1234567/story",original) as ContextArticleFetch.Ready
        assertEquals("https://text.npr.org/1234567",result.document.finalUrl);assertEquals(1,t.calls.size)
    }
    @Test fun aReachableReaderHomePageOrErrorShellNeverBecomesAnArticle():Unit=runBlocking {
        val t=Transport();val url="https://npr.org/1234567/story"
        t.documents={c->if(c.url.contains("text.npr.org"))body("https://text.npr.org/")else body(c.url)}
        val ready=resolver(t).conditional(url,url) as ContextArticleFetch.Ready
        assertEquals(url,ready.document.finalUrl)
        val bad=Transport();bad.documents={body(it.url,article.replace("A public report","Article not found"))}
        assertThrows(SourceFailure::class.java){runBlocking {resolver(bad).conditional(url,url)}}
    }
    @Test fun validatorsStayOnTheirExactRetrievalAndAChangedRouteReceivesNone():Unit=runBlocking {
        val t=Transport();t.documents={body(it.url,headers=mapOf("ETag" to listOf("\"v1\"")))}
        val r=resolver(t);val url="https://npr.org/1234567/story";val first=r.conditional(url,url) as ContextArticleFetch.Ready
        t.calls.clear();t.documents={c->assertEquals("\"v1\"",c.headers["If-None-Match"]);GatewayDocument(304,"Unchanged",emptyMap(),"",c.url)}
        assertTrue(r.conditional(url,url,ContextArticleValidators(first.document.etag),first.document.finalUrl) is ContextArticleFetch.NotModified)
        t.calls.clear();t.documents={c->assertTrue(c.headers.isEmpty());body(c.url)}
        resolver(t).conditional(url,url,ContextArticleValidators("\"private-edition\""),"https://another.example/old")
    }
    @Test fun retainedValidatorsFromAnEarlierResolverAreNotSentByANewSession():Unit=runBlocking {
        val t=Transport();val url="https://publisher.example/report"
        t.documents={body(it.url,headers=mapOf("ETag" to listOf("\"reader-identifier\"")))}
        val first=(resolver(t).conditional(url,url) as ContextArticleFetch.Ready).document
        t.calls.clear();t.documents={c->assertTrue("An on-disk ETag is not a current-session validator",c.headers.isEmpty());body(c.url)}
        assertTrue(resolver(t).conditional(url,url,ContextArticleValidators(first.etag),first.finalUrl) is ContextArticleFetch.Ready)
    }
    @Test fun changingToTorNeverReplaysTheEarlierDirectValidator():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val url="https://publisher.example/report"
        t.documents={body(it.url,headers=mapOf("ETag" to listOf("\"direct-identifier\"")))}
        val first=(r.conditional(url,url) as ContextArticleFetch.Ready).document
        t.mode=NetworkPolicy(tor=true,socksPort=9050,ready=true);t.calls.clear()
        t.documents={c->assertTrue("Tor must not carry a retained identifying validator",c.headers.isEmpty());body(c.url)}
        assertTrue(r.conditional(url,url,ContextArticleValidators(first.etag),first.finalUrl) is ContextArticleFetch.Ready)
    }
    @Test fun returningToTheSamePolicyDoesNotReviveAnEarlierValidator():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val url="https://publisher.example/report"
        t.documents={body(it.url,headers=mapOf("ETag" to listOf("\"old-session\"")))}
        val first=(r.conditional(url,url) as ContextArticleFetch.Ready).document
        t.mode=NetworkPolicy(tor=true);t.mode=NetworkPolicy()
        t.documents={c->assertTrue(c.headers.isEmpty());body(c.url)}
        assertTrue(r.conditional(url,url,ContextArticleValidators(first.etag),first.finalUrl) is ContextArticleFetch.Ready)
    }
    @Test fun torDoesNotRetainOrReplayValidatorsEvenWithinTheSamePolicy():Unit=runBlocking {
        val t=Transport();t.mode=NetworkPolicy(tor=true,socksPort=9050,ready=true)
        t.documents={c->assertTrue(c.headers.isEmpty());body(c.url,headers=mapOf("ETag" to listOf("\"tor-circuit-identifier\""),"Last-Modified" to listOf("Sun, 18 Oct 2026 12:30:00 GMT")))}
        val r=resolver(t);val url="https://publisher.example/report"
        repeat(2) {
            val document=(r.conditional(url,url,ContextArticleValidators("\"old\""),url) as ContextArticleFetch.Ready).document
            assertNull(document.etag);assertNull(document.lastModified)
        }
    }
    @Test fun unchangedResponsesCannotExtendAValidatorsAbsoluteLifetime():Unit=runBlocking {
        var now=1_792_454_400_000L
        val t=Transport();val r=PublicArticleResolver(t,clock={now});val url="https://publisher.example/report"
        t.documents={body(it.url,headers=mapOf("ETag" to listOf("W/\"v1\"")))}
        val first=(r.conditional(url,url) as ContextArticleFetch.Ready).document
        now+=20*60_000L;t.documents={c->assertEquals(first.etag,c.headers["If-None-Match"]);GatewayDocument(304,"Not modified",emptyMap(),"",c.url)}
        assertTrue(r.conditional(url,url,ContextArticleValidators(first.etag),url) is ContextArticleFetch.NotModified)
        now+=11*60_000L;t.documents={c->assertTrue("A304 must not renew an identifier indefinitely",c.headers.isEmpty());body(c.url)}
        assertTrue(r.conditional(url,url,ContextArticleValidators(first.etag),url) is ContextArticleFetch.Ready)
    }
    @Test fun cacheClearAndMismatchedValidatorPairsRequireAFullRead():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val url="https://publisher.example/report"
        t.documents={body(it.url,headers=mapOf("ETag" to listOf("\"v1\""),"Last-Modified" to listOf("Sun, 18 Oct 2026 12:30:00 GMT")))}
        val first=(r.conditional(url,url) as ContextArticleFetch.Ready).document
        t.documents={c->assertTrue(c.headers.isEmpty());body(c.url,headers=mapOf("ETag" to listOf("\"v1\""),"Last-Modified" to listOf("Sun, 18 Oct 2026 12:30:00 GMT")))}
        assertTrue(r.conditional(url,url,ContextArticleValidators(first.etag,"A different date"),url) is ContextArticleFetch.Ready)
        r.clearValidationMemory()
        assertTrue(r.conditional(url,url,ContextArticleValidators(first.etag,first.lastModified),url) is ContextArticleFetch.Ready)
    }
    @Test fun anUnconditional304CannotCertifyTheRetainedDocument():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val url="https://publisher.example/report"
        t.documents={body(it.url,headers=mapOf("ETag" to listOf("\"v1\"")))}
        val first=(r.conditional(url,url) as ContextArticleFetch.Ready).document
        t.sentConditionals=false;t.documents={GatewayDocument(304,"Not modified",emptyMap(),"",it.url)}
        assertThrows(SourceFailure::class.java){runBlocking {r.conditional(url,url,ContextArticleValidators(first.etag),url)}}
    }
    @Test fun aModeRoundTripDuringExtractionCancelsTheResult():Unit=runBlocking {
        val t=Transport();val url="https://publisher.example/report"
        t.documents={c->t.mode=NetworkPolicy(tor=true);t.mode=NetworkPolicy();body(c.url)}
        assertThrows(CancellationException::class.java){runBlocking {resolver(t).conditional(url,url)}}
        assertEquals(1,t.calls.size)
    }
    @Test fun validatorMemoryEvictsOldDocumentsBeforeItCanGrowWithoutBound():Unit=runBlocking {
        val t=Transport();val r=resolver(t)
        t.documents={body(it.url,headers=mapOf("ETag" to listOf("\"v1\"")))}
        repeat(129){i->val url="https://publisher.example/report-$i";r.conditional(url,url)}
        val old="https://publisher.example/report-0"
        t.documents={c->assertTrue(c.headers.isEmpty());body(c.url)}
        assertTrue(r.conditional(old,old,ContextArticleValidators("\"v1\""),old) is ContextArticleFetch.Ready)
    }
    @Test fun longAddressesAndTagsAlsoRespectTheByteBound():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val tag="\""+"a".repeat(2038)+"\""
        t.documents={body(it.url,headers=mapOf("ETag" to listOf(tag)))}
        val root="https://publisher.example/"+"b".repeat(3400)
        repeat(30){i->r.conditional("$root/$i","$root/$i")}
        t.documents={c->assertTrue(c.headers.isEmpty());body(c.url)}
        assertTrue(r.conditional("$root/0","$root/0",ContextArticleValidators(tag),"$root/0") is ContextArticleFetch.Ready)
    }
    @Test fun mediumAttemptsAreFiniteAndUseMoreThanOneFrontendFamily():Unit=runBlocking {
        val t=Transport();val url="https://medium.com/@author/report-123456abcdef"
        assertThrows(SourceFailure::class.java){runBlocking {resolver(t).conditional(url,url)}}
        val docs=t.calls.filterNot {it.url.contains("/search/")||it.url.contains("/cdx/")}
        assertTrue(docs.size<=10);assertTrue(docs.any {it.url.contains("scribe.rip")});assertTrue(docs.any {it.url.contains("freedium.cfd")});assertTrue(docs.any {it.url==url})
    }
    @Test fun unavailableOriginalBacksOffButAnArticle404DoesNotPoisonItsPublisher():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val a="https://publisher.example/a-report";val b="https://publisher.example/b-report"
        t.documents={GatewayDocument(404,"not found",emptyMap(),"",it.url)}
        assertThrows(SourceFailure::class.java){runBlocking {r.conditional(a,a)}}
        t.documents={body(it.url)};assertTrue(r.conditional(b,b) is ContextArticleFetch.Ready)
        val down=Transport();val d=resolver(down)
        repeat(2){assertThrows(SourceFailure::class.java){runBlocking {d.conditional(a,a)}}}
        assertEquals(1,down.calls.count {it.url==a})
    }
    @Test fun torUnavailableAndModeChangeFailClosed():Unit=runBlocking {
        val t=Transport();t.mode=NetworkPolicy(tor=true,ready=false)
        assertThrows(SourceFailure::class.java){runBlocking {resolver(t).conditional(original,original)}};assertTrue(t.calls.isEmpty())
        t.mode=NetworkPolicy();t.indexes={t.mode=NetworkPolicy(tor=true,ready=false);"<title>No results</title>"}
        assertThrows(CancellationException::class.java){runBlocking {resolver(t).conditional(original,original)}}
        assertEquals(1,t.calls.size)
    }
    @Test fun scientificPublicPdfIsOnlyACandidateUntilTheCallerValidatesActualPages():Unit=runBlocking {
        val t=Transport();val doi="10.1234/report"
        t.indexes={if(it.startsWith("https://api.openalex.org"))"""{"doi":"https://doi.org/$doi","locations":[{"is_oa":true,"pdf_url":"https://repository.example/report.pdf","version":"acceptedVersion"}]}"""else "{}"}
        t.documents={c->assertTrue(c.head);GatewayDocument(200,"OK",mapOf("Content-Type" to listOf("application/pdf")),"",c.url)}
        val result=resolver(t).conditional("https://doi.org/$doi",original) as ContextArticleFetch.PublicPdf
        assertEquals("https://repository.example/report.pdf",result.url);assertEquals("acceptedVersion",result.access.version)
    }
    @Test fun aWorkingOpenAlexHtmlRouteAvoidsAnotherMetadataLookup():Unit=runBlocking {
        val t=Transport();t.indexes={"""{"doi":"https://doi.org/10.1234/report","locations":[{"is_oa":true,"landing_page_url":"https://repository.example/report"}]}"""};t.documents={body(it.url)}
        assertTrue(resolver(t).conditional("https://doi.org/10.1234/report",original) is ContextArticleFetch.Ready)
        assertFalse(t.calls.any {it.url.contains("europepmc")})
    }
    @Test fun anAbsentArxivHtmlEditionDoesNotPreventItsExistingPublicPdf():Unit=runBlocking {
        val t=Transport();t.indexes={if(it.contains("openalex"))"""{"doi":"https://doi.org/10.1234/report","locations":[{"is_oa":true,"landing_page_url":"https://arxiv.org/abs/1234.56789","pdf_url":"https://arxiv.org/pdf/1234.56789","version":"submittedVersion"}]}"""else "{}"}
        t.documents={c->if(c.url.contains("/html/"))GatewayDocument(404,"not found",emptyMap(),"",c.url)else GatewayDocument(200,"OK",mapOf("Content-Type" to listOf("application/pdf")),"",c.url)}
        val result=resolver(t).conditional("https://doi.org/10.1234/report",original)
        assertTrue(result is ContextArticleFetch.PublicPdf);assertTrue(t.calls.any {it.url.contains("/html/")});assertTrue(t.calls.any {it.url.contains("/pdf/")&&it.head})
    }
    @Test fun pmidCanUseItsVerifiedDoiToFindAPublicCopyWhenEuropePmcHasNone():Unit=runBlocking {
        val t=Transport();t.indexes={if(it.contains("europepmc"))"""{"resultList":{"result":[{"id":"1234","source":"MED","doi":"10.1234/report"}]}}"""else """{"doi":"https://doi.org/10.1234/report","locations":[{"is_oa":true,"landing_page_url":"https://repository.example/report"}]}"""};t.documents={body(it.url)}
        val result=resolver(t).conditional("https://pubmed.ncbi.nlm.nih.gov/1234/",original) as ContextArticleFetch.Ready
        assertEquals("https://repository.example/report",result.document.finalUrl)
    }
    private fun jats()=javaClass.getResourceAsStream("/jats-repair.xml")!!.reader().use {it.readText()}
    private val xmlUrl="https://www.ebi.ac.uk/europepmc/webservices/rest/PMC12345/fullTextXML"
    private fun xml(url:String=xmlUrl,text:String=jats())=GatewayDocument(200,"OK",mapOf("Content-Type" to listOf("application/xml; charset=UTF-8")),text,url)
    @Test fun knownPmcIdentifierReadsStructuredFullTextWithoutMetadataOrOriginalAccess():Unit=runBlocking {
        val t=Transport();t.originalAllowed=false;t.documents={c->assertEquals(xmlUrl,c.url);xml()}
        val original="https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/"
        val document=(resolver(t).conditional(original,original) as ContextArticleFetch.Ready).document
        assertEquals(xmlUrl,document.finalUrl);assertEquals("Europe PMC full text",document.access!!.route)
        assertEquals("10.1234/repair",document.publicationDoi);assertEquals(1,t.calls.size)
        assertEquals("https://creativecommons.org/licenses/by/4.0/",document.access!!.license)
    }
    @Test fun discoveredPmcCopyTriesXmlBeforeHtmlAndAvoidsAnotherIndex():Unit=runBlocking {
        val t=Transport();t.originalAllowed=false
        t.indexes={"""{"doi":"https://doi.org/10.1234/repair","locations":[{"is_oa":true,"landing_page_url":"https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/","version":"acceptedVersion","license":"cc-by"}]}"""}
        t.documents={xml(it.url)}
        val original="https://doi.org/10.1234/repair"
        val document=(resolver(t).conditional(original,original) as ContextArticleFetch.Ready).document
        assertEquals(xmlUrl,document.finalUrl);assertNull(document.access!!.version);assertEquals("https://creativecommons.org/licenses/by/4.0/",document.access!!.license)
        assertEquals(2,t.calls.size);assertFalse(t.calls.any {it.url.contains("/rest/search")})
    }
    @Test fun absentStructuredCopyFallsThroughToExistingHtmlWithoutASecondSearch():Unit=runBlocking {
        val t=Transport();t.originalAllowed=false
        t.documents={c->if(c.url==xmlUrl)GatewayDocument(404,"Not found",emptyMap(),"",c.url)else body(c.url)}
        val original="https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/"
        val document=(resolver(t).conditional(original,original) as ContextArticleFetch.Ready).document
        assertEquals("https://europepmc.org/article/PMC/12345",document.finalUrl);assertEquals(2,t.calls.size)
        assertFalse(t.calls.any {it.url==original})
    }
    @Test fun xmlWithHtmlMimeOrWrongRootIdentityNeverBecomesTheStructuredArticle():Unit=runBlocking {
        for(bad in listOf(body(xmlUrl),xml(text=jats().replace("<article-id pub-id-type=\"pmcid\">PMC12345","<article-id pub-id-type=\"pmcid\">PMC54321")))) {
            val t=Transport();t.originalAllowed=false;t.documents={c->if(c.url==xmlUrl)bad else body(c.url)}
            val original="https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/"
            assertEquals("https://europepmc.org/article/PMC/12345",(resolver(t).conditional(original,original) as ContextArticleFetch.Ready).document.finalUrl)
        }
    }
    @Test fun recoveredPublisherDoiMustMatchTheXmlPrimaryIdentifier():Unit=runBlocking {
        val t=Transport();val original="https://www.sciencedirect.com/science/article/pii/S12345678901"
        t.indexes={if(it.contains("openalex"))"""{"doi":"https://doi.org/10.1234/repair","locations":[{"is_oa":true,"landing_page_url":"https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/"}]}"""else "{}"}
        t.documents={c->when(c.url) {
            original->body(c.url,"<meta name=citation_doi content='10.1234/repair'>")
            xmlUrl->xml(text=jats().replace("<article-id pub-id-type=\"doi\">10.1234/repair", "<article-id pub-id-type=\"doi\">10.1234/different"))
            else->body(c.url)
        }}
        val document=(resolver(t).conditional(original,original) as ContextArticleFetch.Ready).document
        assertEquals("https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/",document.finalUrl)
        assertTrue(t.calls.any {it.url==xmlUrl})
    }
    @Test fun fullTextFailureDoesNotCoolDownTheMetadataCapabilityOnTheSameHost():Unit=runBlocking {
        val t=Transport();val original="https://doi.org/10.1234/repair"
        t.indexes={if(it.contains("openalex"))"""{"doi":"https://doi.org/10.1234/repair","locations":[{"is_oa":true,"landing_page_url":"https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/"}]}"""else "{}"}
        t.documents={c->if(c.url==original)body(c.url)else GatewayDocument(503,"Unavailable",emptyMap(),"",c.url)}
        assertTrue(resolver(t).conditional(original,original) is ContextArticleFetch.Ready)
        assertTrue(t.calls.any {it.url.contains("europepmc/webservices/rest/search")})
    }
    @Test fun structuredRouteHintsAndExactValidatorsRetainTheCheapWarmPath():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val original="https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/"
        t.documents={xml().copy(headers=mapOf("Content-Type" to listOf("application/xml"),"ETag" to listOf("\"xml-v1\"")))}
        val first=(r.conditional(original,original) as ContextArticleFetch.Ready).document
        t.calls.clear();t.documents={c->assertEquals(xmlUrl,c.url);assertEquals("\"xml-v1\"",c.headers["If-None-Match"]);GatewayDocument(304,"Not modified",emptyMap(),"",c.url)}
        assertTrue(r.conditional(original,original,ContextArticleValidators(first.etag),first.finalUrl) is ContextArticleFetch.NotModified)
        assertEquals(1,t.calls.size)
    }

    @Test fun europePmcOriginalCanUseNcbiHtmlWhenXmlIsUnavailableAndOriginalsAreOff():Unit=runBlocking {
        val t=Transport();t.originalAllowed=false
        val original="https://europepmc.org/articles/PMC12345";val ncbi="https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/"
        t.documents={c->if(c.url==ncbi)body(c.url)else GatewayDocument(404,"Unavailable",emptyMap(),"",c.url)}
        val document=(resolver(t).conditional(original,original) as ContextArticleFetch.Ready).document
        assertEquals(ncbi,document.finalUrl);assertFalse(t.calls.any {it.url==original})
        assertEquals(listOf(xmlUrl,ncbi),t.calls.map {it.url})
    }
    @Test fun verifiedCanonicalUriAvoidsRepeatingTheRedirectAndFullBody():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val original="https://publisher.example/old-report";val canonical="https://publisher.example/reports/battery"
        t.documents={body(canonical,headers=mapOf("ETag" to listOf("\"canonical-v1\"")))}
        val first=(r.conditional(original,original) as ContextArticleFetch.Ready).document
        t.calls.clear();t.documents={c->if(c.url==canonical&&c.headers["If-None-Match"]==first.etag)GatewayDocument(304,"Unchanged",emptyMap(),"",c.url)else body(canonical,headers=mapOf("ETag" to listOf("\"canonical-v1\"")))}
        assertTrue(r.conditional(original,original,ContextArticleValidators(first.etag),first.finalUrl) is ContextArticleFetch.NotModified)
        assertEquals(listOf(canonical),t.calls.map {it.url});assertEquals(first.etag,t.calls.single().headers["If-None-Match"])
    }
    @Test fun aRememberedOriginalRedirectStillRequiresOriginalAccess():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val original="https://publisher.example/old-report";val canonical="https://canonical.example/reports/battery"
        t.documents={body(canonical)};r.conditional(original,original)
        t.originalAllowed=false;t.calls.clear()
        assertThrows(SourceFailure::class.java){runBlocking {r.conditional(original,original)}}
        assertFalse(t.calls.any {it.url==original||it.url==canonical})
    }
    @Test fun rememberedOriginalCannotJumpAheadOfRecoveredAlternates():Unit=runBlocking {
        val t=Transport();var now=1_792_454_400_000L;val r=PublicArticleResolver(t,clock={now})
        val original="https://medium.com/@author/report-123456abcdef"
        t.documents={c->if(c.url==original)body(c.url)else GatewayDocument(503,"Unavailable",emptyMap(),"",c.url)}
        assertEquals("Original publisher",(r.conditional(original,original) as ContextArticleFetch.Ready).document.access!!.route)
        now+=120_000;t.calls.clear();t.documents={body(it.url)}
        val recovered=(r.conditional(original,original) as ContextArticleFetch.Ready).document
        assertNotEquals("Original publisher",recovered.access!!.route)
        assertFalse(t.calls.any {it.url==original})
    }
    @Test fun anOriginalDeclaredAsAnOpenCopyCannotBeRememberedAsAnAlternate():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val original="https://www.nature.com/articles/s41586-1234";val alias="https://nature.com/articles/s41586-1234"
        t.indexes={if(it.contains("openalex"))"""{"doi":"https://doi.org/10.1038/s41586-1234","locations":[{"is_oa":true,"landing_page_url":"$alias"}]}"""else "{}"}
        t.documents={body(alias)};r.conditional(original,original)
        t.originalAllowed=false;t.calls.clear()
        assertThrows(SourceFailure::class.java){runBlocking {r.conditional(original,original)}}
        assertFalse(t.calls.any {it.url==original||it.url==alias})
    }
    @Test fun anUnavailableLearnedCanonicalTargetDoesNotBlockRediscoveryAtTheOriginal():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val original="https://publisher.example/old-report";val former="https://publisher.example/reports/battery";val current="https://publisher.example/reports/battery-updated"
        t.documents={body(former)};r.conditional(original,original);t.calls.clear()
        t.documents={c->if(c.url==former)GatewayDocument(503,"Target unavailable",emptyMap(),"",c.url)else body(current)}
        val found=(r.conditional(original,original) as ContextArticleFetch.Ready).document
        assertEquals(current,found.finalUrl);assertEquals(listOf(former,original),t.calls.map {it.url})
    }

    @Test fun aDiscoveredPmcLocationCanUseTheOtherHtmlProviderWithoutAnotherIndex():Unit=runBlocking {
        val t=Transport();t.originalAllowed=false;val original="https://doi.org/10.1234/repair"
        t.indexes={"""{"doi":"https://doi.org/10.1234/repair","locations":[{"is_oa":true,"landing_page_url":"https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/"}]}"""}
        t.documents={c->if(c.url=="https://europepmc.org/article/PMC/12345")body(c.url)else GatewayDocument(404,"Unavailable",emptyMap(),"",c.url)}
        val document=(resolver(t).conditional(original,original) as ContextArticleFetch.Ready).document
        assertEquals("https://europepmc.org/article/PMC/12345",document.finalUrl)
        assertEquals(4,t.calls.size);assertFalse(t.calls.any {it.url.contains("/rest/search")})
    }
    @Test fun structuredEndpointsDiscoveredDirectlyStillValidateRecoveredPublisherDoi():Unit=runBlocking {
        val t=Transport();val original="https://www.sciencedirect.com/science/article/pii/S12345678901"
        t.indexes={if(it.contains("openalex"))"""{"doi":"https://doi.org/10.1234/repair","locations":[{"is_oa":true,"landing_page_url":"$xmlUrl"}]}"""else "{}"}
        t.documents={c->when(c.url) {
            original->body(c.url,"<meta name=citation_doi content='10.1234/repair'>")
            xmlUrl->xml(text=jats().replace("<article-id pub-id-type=\"doi\">10.1234/repair", "<article-id pub-id-type=\"doi\">10.1234/different"))
            else->body(c.url)
        }}
        val document=(resolver(t).conditional(original,original) as ContextArticleFetch.Ready).document
        assertEquals("https://pmc.ncbi.nlm.nih.gov/articles/PMC12345/",document.finalUrl)
        assertTrue(t.calls.any {it.url==xmlUrl})
    }
    @Test fun sensitiveRedirectDestinationsDoNotBecomeReusableRouteHints():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val original="https://publisher.example/report";val signed="https://publisher.example/reader?access_token=fixture"
        t.documents={body(signed,headers=mapOf("ETag" to listOf("\"private-route-fixture\"")))}
        val first=(r.conditional(original,original,purpose=FetchPurpose.DELIBERATE) as ContextArticleFetch.Ready).document
        t.calls.clear()
        r.conditional(original,original,ContextArticleValidators(first.etag),first.finalUrl,purpose=FetchPurpose.DELIBERATE)
        assertEquals(listOf(original),t.calls.map {it.url});assertTrue(t.calls.single().headers.isEmpty())
    }

    @Test fun anObservedPdfDoesNotFreezeAnUndeclaredPublisherRepresentation():Unit=runBlocking {
        val t=Transport();val r=resolver(t);val original="https://publisher.example/report"
        t.documents={GatewayDocument(200,"OK",mapOf("Content-Type" to listOf("application/pdf")),"",it.url)}
        assertTrue(r.conditional(original,original) is ContextArticleFetch.PublicPdf)
        t.calls.clear();t.documents={body(it.url)}
        assertTrue("The publisher can later supply a readable HTML edition at this same URL",r.conditional(original,original) is ContextArticleFetch.Ready)
        assertFalse(t.calls.first().head)
    }

}
