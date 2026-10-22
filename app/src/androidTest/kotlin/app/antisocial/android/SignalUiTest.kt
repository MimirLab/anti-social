package app.antisocial.android

import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SignalUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var fetch:suspend (SourceRecordRequest,suspend (PublicSourceRecord)->Unit)->PublicSourceRecord
    @Before fun prepare() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()};fetch=repo.sourceRecords.fetch
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Text("Preparing source fixtures")}}}
        repo.sourceRecords.cleanup(true)
    }
    @After fun restore() {if(::fetch.isInitialized){ui.runOnIdle {ui.activity.setContent {Text("Source fixtures complete")}};repo.sourceRecords.fetch=fetch;repo.sourceRecords.cleanup(true)}}
    @Test fun aPublicationNoticeOpensItsActualSourceWithoutAProfileOfMetadata() {
        val doi="10.1234/native-${System.nanoTime()}";val url="https://doi.org/$doi";val notice="https://doi.org/10.1234/correction";var opened=""
        repo.sourceRecords.fetch={request,_->PublicSourceRecord(request,listOf(SourceFact("paper:correction","Correction","The publisher corrected the reported measurement.","https://api.crossref.org/works/$doi",destination=notice)),System.currentTimeMillis())}
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {SourceDetailsSheet(repo,listOf(url),onOpen={opened=it},onDismiss={})}}}
        ui.waitUntil(10_000){ui.onAllNodesWithText("Correction").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Open publication notice").performClick();assertEquals(notice,opened)
        ui.onNodeWithText("The publisher corrected the reported measurement.").assertIsDisplayed()
        screenshot("publication-notice")
    }
    @Test fun communityAnnotationsStayCollapsedAndTheirEvidenceLinksAreUsableWithoutAi() {
        var opened="";val url="https://agency.gov/report"
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Surface {CommunityNoteDisclosure(listOf(PublicContextNote("The report contains a corrected measurement table.","https://x.com/author/status/1",listOf(url))),onOpen={opened=it})}}}}
        ui.onNodeWithText("The report contains a corrected measurement table.").assertDoesNotExist()
        ui.onNodeWithTag("Community note disclosure").performClick()
        ui.onNodeWithText("The report contains a corrected measurement table.").assertIsDisplayed()
        ui.onNodeWithText("Rating status was not supplied by this source.").assertIsDisplayed()
        ui.onNodeWithText(url).performClick();assertEquals(url,opened)
        screenshot("community-note")
    }
    private fun screenshot(name:String) {
        val file=File(repo.context.filesDir,"signal-$name.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
}
