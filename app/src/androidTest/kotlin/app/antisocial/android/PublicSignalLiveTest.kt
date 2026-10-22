package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PublicSignalLiveTest {
    private val repo get()=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AntiSocialApplication).repo
    private lateinit var policy:NetworkPolicy
    @Before fun prepare() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialPublicSignalLive")=="true")
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()};policy=repo.gateway.currentPolicy();repo.gateway.configure(NetworkPolicy())
    }
    @After fun restore(){if(::policy.isInitialized)repo.gateway.configure(policy)}
    private fun receipt(name:String,value:Any){File(repo.context.filesDir,"signal-live-$name.json").writeText(repo.gson.toJson(value))}
    @Test fun exactPaperRetrievesItsRecordedNoticeThroughTheGateway():Unit=runBlocking {
        val request=PublicSourceRecords.paper("https://doi.org/10.1177/1758835920922055")!!
        val data=withTimeout(45_000){PublicSourceRecordReader(repo.gateway).lookup(request)}
        receipt("paper",data)
        assertTrue(data.facts.any {it.kind=="paper:retraction"&&it.destination=="https://doi.org/10.1177/17588359231172420"})
        assertEquals(1,data.facts.count {it.destination=="https://doi.org/10.1177/17588359231172420"})
    }
    @Test fun authoritativeRegistrationAndPublisherDeclarationsAreReadAsSeparateRecords():Unit=runBlocking {
        val reader=PublicSourceRecordReader(repo.gateway)
        val registration=withTimeout(60_000){reader.lookup(PublicSourceRecords.website("https://example.com/article")!!)}
        val publisher=withTimeout(60_000){reader.lookup(PublicSourceRecords.website("https://www.theguardian.com/world")!!)}
        receipt("websites",listOf(registration,publisher))
        assertTrue(registration.facts.any {it.kind=="registration"&&it.sourceUrl.contains("verisign")})
        assertTrue(publisher.facts.any {it.kind=="registration"})
        // A filtered or unavailable declaration remains absent, never a claim
        // that this publisher has no owner or no advertising relationships.
        publisher.facts.filter {it.kind=="declared-owner"}.forEach {assertEquals("theguardian.com",it.detail)}
    }
    @Test fun publicDirectoryRetainsHandleAndHostingChangesForOnePersistentAccount():Unit=runBlocking {
        val data=withTimeout(45_000){PublicSourceRecordReader(repo.gateway).lookup(PublicSourceRecords.account("did:plc:z72i7hdynmk6r22z27h6tvur")!!)}
        receipt("account",data)
        assertTrue(data.facts.any {it.kind=="account-handle"&&it.detail=="bsky.app"})
        assertTrue(data.facts.any {it.kind=="account-host"&&it.title=="Hosting changed"})
    }
    @Test fun aScientificArticleProvidesRealUnderlyingPaperLinksThroughTheSharedReader():Unit=runBlocking {
        val data=withTimeout(120_000){repo.evidenceReader.article("https://www.nature.com/articles/s41586-024-07487-w","https://www.nature.com/articles/s41586-024-07487-w")}
        receipt("article",mapOf("original" to data.originalUrl,"retrieved" to data.finalUrl,"title" to data.title,"characters" to data.text.length,"references" to data.references,"doi" to data.publicationDoi))
        assertTrue(data.text.length>1000);assertEquals("10.1038/s41586-024-07487-w",data.publicationDoi)
        assertTrue("The extracted article should expose at least one underlying paper",data.references.orEmpty().any {PublicSourceRecords.paper(it.url)!=null})
    }
}
