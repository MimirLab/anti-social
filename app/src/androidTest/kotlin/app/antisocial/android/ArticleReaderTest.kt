package app.antisocial.android

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ArticleReaderTest {
    @Test fun canonicalPublisherCopyKeepsTheAuthenticOriginalInTheNativeReader() {
        val canonical="https://publisher.example/reports/battery"
        reader.fetch={ContextArticleFetch.Ready(ContextArticleEvidence(original,canonical,"A practical repair",null,null,null,listOf(ContextArticleBlock(body)),1234,access=ArticleAccess("Original publisher",original,canonical)))}
        ui.runOnIdle {reader.open(original)};ui.waitUntil(10_000){reader.state.value?.document!=null}
        ui.onNodeWithText(body).assertIsDisplayed();ui.onNodeWithTag("Copy article link").performClick()
        val clipboard=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ui.runOnIdle {assertEquals(original,clipboard.primaryClip!!.getItemAt(0).text.toString())}
        ui.onNodeWithTag("Article source").performClick()
        ui.onNodeWithText("Read from: $canonical",substring=true).assertExists()
        SystemClock.sleep(6500);evidence("canonical-publisher-source")
    }
    @Test fun structuredScientificCopyReadsCleanlyWithoutAi() {
        val xml=InstrumentationRegistry.getInstrumentation().context.assets.open("jats-repair.xml").reader().use {it.readText()}
        val url="https://www.ebi.ac.uk/europepmc/webservices/rest/PMC12345/fullTextXML"
        val document=runBlocking {repo.documentSandbox.article(Payload(xml,url,"application/xml",200),"https://doi.org/10.1234/repair",1234)}
        reader.fetch={ContextArticleFetch.Ready(document)}
        ui.runOnIdle {reader.open("https://doi.org/10.1234/repair")};ui.waitUntil(10_000){reader.state.value?.document!=null}
        ui.onNodeWithText("Abstract").assertExists()
        evidence("jats-article-summary")
        ui.onNodeWithTag("Article reading list").performScrollToNode(hasText("Part: Connector A; Force: 12 N"))
        ui.onNodeWithText("Part: Connector A; Force: 12 N").assertIsDisplayed()
        ui.onNodeWithTag("Article reading list").performScrollToNode(hasText("Measurements used the same calibrated fixture."))
        ui.onNodeWithText("Measurements used the same calibrated fixture.").assertIsDisplayed()
        evidence("jats-article-table")
        assertFalse(repo.ai.preferences.enabled)
    }
    @Test fun structuredStaticExtractionRendersReadableStepsAndLabelledTablesWithoutAi() {
        val html="""<html><head><title>Repair procedure</title></head><body><article><h1>Repair procedure</h1><p>The replacement battery must match the exact laptop model and electrical specification. Disconnect power before opening the case, photograph the connector, and keep the original screws organized.</p><h2>Safe sequence</h2><ol start=2><li>Disconnect power</li><li>Inspect the connector</li></ol><table><tr><th>Part</th><th>Capacity</th></tr><tr><td>Battery A</td><td>56 Wh</td></tr></table><p>The service manual documents the approved replacement sequence. Never puncture a swollen cell or force a damaged connector back into place.</p></article><script>fetch('/never-run')</script></body></html>"""
        val document=ContextArticles.extract(Payload(html,original,"text/html",200),original)
        reader.fetch={ContextArticleFetch.Ready(document)}
        ui.runOnIdle {reader.open(original)};ui.waitUntil(10_000){reader.state.value?.document!=null}
        ui.onNodeWithText("2. Disconnect power").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("3. Inspect the connector").assertExists()
        ui.onNodeWithText("Part: Battery A; Capacity: 56 Wh").performScrollTo().assertIsDisplayed()
        assertFalse(repo.ai.preferences.enabled)
        // Let Android's clipboard overlay from an earlier copy fixture expire.
        SystemClock.sleep(6500);evidence("structured-article-light")
    }
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var reader:ArticleReaderController
    private lateinit var aiBefore:Map<String,*>
    private lateinit var policy:NetworkPolicy
    private var interval=0
    private val files=mutableListOf<File>()
    private val original="https://publisher.example/repair-report"
    private val replay="https://web.archive.org/web/20261019120000id_/$original"
    private val body="The repair technician replaced the battery connector and confirmed normal charging. The report does not establish the battery's long-term life."
    private fun evidence(name:String) {
        ui.mainClock.advanceTimeBy(1_000);ui.waitForIdle();SystemClock.sleep(500)
        val folder=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"article-reader").apply {mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(folder,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Before fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()}
        aiBefore=ui.activity.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).all.toMap()
        policy=repo.gateway.currentPolicy();interval=repo.prefs.intervalMinutes
        repo.ai.enabled(false);repo.prefs.intervalMinutes=0;repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        reader=ArticleReaderController(repo,scope)
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {Surface(Modifier.fillMaxSize()){ArticleReaderSheet(reader,{}, {})}}}}
    }
    @After fun restore() {
        if(!::aiBefore.isInitialized)return
        ui.runOnIdle {reader.close()};scope.cancel();files.forEach {it.delete()}
        val edit=ui.activity.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).edit().clear()
        aiBefore.forEach {(k,v)->when(v){is Boolean->edit.putBoolean(k,v);is String->edit.putString(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v)}}
        edit.commit();repo.prefs.intervalMinutes=interval;repo.gateway.configure(policy);runBlocking {repo.ai.refresh()}
    }
    @Test fun underlyingMaterialAndTheNestedSourcePanelOpenWithoutAi() {
        val destination="https://maker.example/manual.pdf";var opened=""
        val previous=repo.sourceRecords.fetch
        repo.sourceRecords.fetch={request,_->PublicSourceRecord(request,emptyList(),System.currentTimeMillis(),message="Public source record fixture")}
        try {
            reader.fetch={ContextArticleFetch.Ready(ContextArticleEvidence(original,original,"A repair worth understanding","Jamie","Workshop",null,
                listOf(ContextArticleBlock(body)),System.currentTimeMillis(),access=ArticleAccess("Original publisher",original,original),
                references=listOf(SignalReference(destination,"DOI","Linked primary material","The manual documents the repair.",90))))}
            ui.runOnIdle {ui.activity.setContent {MaterialTheme {Surface(Modifier.fillMaxSize()){ArticleReaderSheet(reader,{opened=it}, {})}}};reader.open(original)}
            ui.waitUntil(10_000){reader.state.value?.document!=null}
            ui.onNodeWithTag("Article source").performClick()
            ui.onNodeWithText("Source details").performClick()
            ui.onNodeWithTag("Source details").assertIsDisplayed()
            ui.onNodeWithContentDescription("Close source details").performClick()
            ui.onNodeWithTag("Article reader").assertExists()
            ui.onNodeWithTag("Underlying source $destination").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("The manual documents the repair.").assertIsDisplayed()
            evidence("underlying-sources")
            ui.onNodeWithTag("Underlying source $destination").performClick()
            assertEquals(destination,opened);assertNull(reader.state.value);assertFalse(repo.ai.preferences.enabled)
        }finally {repo.sourceRecords.fetch=previous;repo.sourceRecords.cleanup(true)}
    }
    @Test fun recoveredArticleIsReadableWithoutAiAndCopiesTheAuthenticUrl() {
        reader.fetch={ContextArticleFetch.Ready(ContextArticleEvidence(original,replay,"A repair worth understanding","Jamie","Public Workshop",null,
            listOf(ContextArticleBlock(body)),System.currentTimeMillis(),access=ArticleAccess("Existing archive capture",original,replay,Instant.parse("2026-10-19T12:00:00Z").toEpochMilli())))}
        ui.runOnIdle {reader.open(original)}
        ui.waitUntil(10_000){reader.state.value?.document!=null}
        ui.onNodeWithText(body).assertIsDisplayed();ui.onNodeWithText("Archived 10/26").assertIsDisplayed()
        assertFalse(repo.ai.preferences.enabled)
        ui.onNodeWithTag("Copy article link").performClick()
        val clipboard=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ui.runOnIdle {assertEquals(original,clipboard.primaryClip!!.getItemAt(0).text.toString())}
        evidence("archived-article-light")
        ui.onNodeWithTag("Article source").performClick()
        ui.onNodeWithText("Read from: $replay",substring=true).assertExists()
    }
    @Test fun unavailableCopyStaysInTheReaderAndCanRetryWithoutAnAutomaticBrowserJump() {
        var tries=0
        reader.fetch={tries++;if(tries==1)throw IllegalStateException("No readable public copy was available")else ContextArticleFetch.Ready(ContextArticleEvidence(original,original,"Recovered report",null,null,null,listOf(ContextArticleBlock(body)),0))}
        ui.runOnIdle {reader.open(original)}
        ui.waitUntil(10_000){reader.state.value?.error!=null}
        ui.onNodeWithText("Try again").performClick();ui.waitUntil(10_000){reader.state.value?.document!=null}
        ui.onNodeWithText(body).assertIsDisplayed();assertEquals(2,tries)
    }
    @Test fun linkNavigationClosesTheReaderAndPassesTheAuthenticDestination() {
        val destination="https://x.com/workshop/status/123456789"
        val opened=mutableListOf<String>()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {Surface(Modifier.fillMaxSize()){ArticleReaderSheet(reader,{opened+=it},{})}}}}
        reader.fetch={ContextArticleFetch.Ready(ContextArticleEvidence(original,original,"Repair report",null,null,null,
            listOf(ContextArticleBlock(body,links=listOf("https://web.archive.org/web/20261019120000id_/$destination"))),0))}
        ui.runOnIdle {reader.open(original)};ui.waitUntil(10_000){reader.state.value?.document!=null}
        ui.onNodeWithText("Links · 1").performScrollTo().performClick()
        ui.onNodeWithText(destination).performScrollTo().performClick()
        ui.runOnIdle {assertEquals(listOf(destination),opened);assertNull(reader.state.value)}
        ui.onNodeWithTag("Article reader").assertDoesNotExist()
    }
    @Test fun publicPdfUsesTheNativeRendererWithoutAiOrWebContent() {
        val file=File(ui.activity.cacheDir,"native-article-fixture.pdf");files+=file
        val pdf=PdfDocument()
        try {repeat(2){index->val p=pdf.startPage(PdfDocument.PageInfo.Builder(480,640,index+1).create())
            p.canvas.drawColor(android.graphics.Color.WHITE);p.canvas.drawText("Workshop repair report",30f,70f,Paint().apply {textSize=25f})
            p.canvas.drawText("Page ${index+1}: keep the original manual.",30f,125f,Paint().apply {textSize=16f});pdf.finishPage(p)}
            file.outputStream().use {pdf.writeTo(it)}}finally {pdf.close()}
        val access=ArticleAccess("OpenAlex public copy",original,"https://repository.example/report.pdf",version="acceptedVersion")
        reader.fetch={ContextArticleFetch.PublicPdf(original,access.retrievedUrl,access)}
        reader.pdfLoad={file to 2}
        ui.runOnIdle {reader.open(original)};ui.waitUntil(10_000){reader.state.value?.pdf!=null}
        ui.onNodeWithText("Accepted manuscript").assertIsDisplayed()
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Document page 1").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("Document page 1").assertIsDisplayed();evidence("public-pdf-light")
    }
}
