package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PublicSourceRecordsTest {
    private val paper=PublicSourceRecords.paper("https://doi.org/10.1234/example")!!
    @Test fun aNoticeTargetsThePaperAndDuplicatePublisherAndWatchRecordsCoalesce() {
        val data="""{"status":"ok","message":{"DOI":"10.1234/example","updated-by":[{"DOI":"10.1234/notice","type":"retraction","source":"publisher","updated":{"date-parts":[[2025,2,1]]}},{"DOI":"10.1234/notice","type":"retraction","source":"retraction-watch","updated":{"date-parts":[[2025,2,1]]}}]}}"""
        val record=PublicSourceRecords.crossref(paper,data,100)
        assertEquals(1,record.facts.size);assertEquals("https://doi.org/10.1234/notice",record.facts.single().destination)
        assertTrue(record.facts.single().detail.contains("Retraction Watch"));assertTrue(record.facts.single().detail.contains("publisher"))
    }
    @Test fun anOutboundUpdateDoesNotRetractTheNoticeItselfAndAnEmptyRecordIsNotACleanBillOfHealth() {
        val record=PublicSourceRecords.crossref(paper,"""{"status":"ok","message":{"DOI":"10.1234/example","update-to":[{"DOI":"10.1234/other","type":"retraction"}]}}""",100)
        assertTrue(record.facts.isEmpty());assertTrue(record.message.contains("incomplete"))
        assertThrows(IllegalArgumentException::class.java){PublicSourceRecords.crossref(paper,"""{"status":"ok","message":{"DOI":"10.1234/unrelated"}}""",100)}
    }
    @Test fun correctionsConcernsAndReinstatementsRemainDistinctDatedNotices() {
        val records=PublicSourceRecords.crossref(paper,"""{"status":"ok","message":{"DOI":"10.1234/example","updated-by":[{"DOI":"10.1234/a","type":"correction"},{"DOI":"10.1234/b","type":"expression-of-concern"},{"DOI":"10.1234/c","type":"reinstatement"}]}}""",100)
        assertEquals(setOf("Correction","Expression of concern","Reinstatement"),records.facts.map {it.title}.toSet())
        assertTrue(records.facts.all {it.recordedAt==null})
    }
    @Test fun registrationsValidateTheExactDomainAndNeverUseLastChangedAsTheCreationDate() {
        val request=PublicSourceRecords.website("https://news.example.com/article?q=sensitive")!!
        assertEquals("example.com",request.identity)
        val facts=PublicSourceRecords.rdap(request,"""{"objectClassName":"domain","ldhName":"EXAMPLE.COM","events":[{"eventAction":"last changed","eventDate":"2026-01-01T00:00:00Z"}],"status":["ok"]}""","https://registry.example/domain/example.com")
        assertFalse(facts.any {it.kind=="registration"})
        assertThrows(IllegalArgumentException::class.java){PublicSourceRecords.rdap(request,"""{"objectClassName":"domain","ldhName":"other.com"}""","https://registry.example/domain/example.com")}
    }
    @Test fun authoritativeBootstrapChoosesTheLongestSuffixAndHttpsOnly() {
        val raw="""{"services":[[["com"],["http://unsafe.example/","https://registry.example/com/"]],[["example.com"],["https://special.example/"]]]}"""
        assertEquals("https://special.example/",PublicSourceRecords.bootstrap(raw,"example.com"))
        assertEquals("https://registry.example/com/",PublicSourceRecords.bootstrap(raw,"other.com"))
    }
    @Test fun sellerAccountsNeverTurnIntoOwnershipClaims() {
        val facts=PublicSourceRecords.advertising("""OWNERDOMAIN=owner.example
            MANAGERDOMAIN=manager.example, US
            adnetwork.example, 123, DIRECT
            OWNERDOMAIN=https://spoof.example/path
            OWNERDOMAIN=bad.example/path
        """.trimIndent(),"https://publisher.example/ads.txt")
        assertEquals(2,facts.size);assertEquals("Declared advertising manager",facts.last().title)
        assertTrue(facts.last().detail.endsWith("US"))
    }
    @Test fun plcHistoryIgnoresInvalidatedBranchesAndKeyRotationsWithoutVisibleChanges() {
        val r=PublicSourceRecords.account("did:plc:z72i7hdynmk6r22z27h6tvur")!!
        fun entry(handle:String,stamp:String,nullified:Boolean=false)="""{"did":"${r.identity}","nullified":$nullified,"createdAt":"$stamp","operation":{"type":"plc_operation","alsoKnownAs":["at://$handle"],"services":{"atproto_pds":{"endpoint":"https://pds.example"}}}}"""
        val record=PublicSourceRecords.plc(r,"["+listOf(entry("old.example","2023-01-01T00:00:00Z"),entry("bad.example","2023-02-01T00:00:00Z",true),entry("new.example","2023-03-01T00:00:00Z"),entry("new.example","2023-04-01T00:00:00Z")).joinToString(",")+"]",100)
        assertEquals(3,record.facts.size);assertFalse(record.facts.any {it.detail=="bad.example"})
        assertTrue(record.message.contains("Historical handles"));assertTrue(record.message.contains("not been independently audited"))
    }
    @Test fun onlyAnUnambiguousVerifiedProfileWebsiteIsPreserved() {
        val node=JsonParser.parseString("""{"fields":[{"name":"Site","value":"<a href='https://site.example/'>Site</a>","verified_at":"2025-01-01T00:00:00Z"},{"name":"Not verified","value":"<a href='https://other.example/'>Other</a>","verified_at":null},{"name":"Ambiguous","value":"<a href='https://a.example/'>A</a><a href='https://b.example/'>B</a>","verified_at":"2025-01-01T00:00:00Z"}]}""").asJsonObject
        assertEquals(listOf("https://site.example/"),PublicSourceRecords.verifiedWebsites(node,"https://mastodon.social/@person").map {it.url})
    }
    @Test fun selectedTorFailsClosedBeforeEvenAReadHookCanRun():Unit=runBlocking {
        val gateway=NetworkGateway(UrlCleaner(JsonParser.parseString("{}").asJsonObject),object:RequestFilter{override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)})
        gateway.configure(NetworkPolicy(tor=true,ready=false));val reader=PublicSourceRecordReader(gateway);var calls=0
        reader.read={_,_->calls++;"{}"}
        assertThrows(IllegalArgumentException::class.java){runBlocking {reader.lookup(paper)}};assertEquals(0,calls)
    }
}
