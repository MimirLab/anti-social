/* AntiSocial, GPL-3.0-or-later. Native fixtures; no external providers or real models. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContextUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repo get() = (ui.activity.application as AntiSocialApplication).repo
    private val reader get() = repo.contextReader
    private lateinit var aiPrefs: Map<String, *>
    private lateinit var readerPrefs: String
    private lateinit var originalOptions: ContextOptions
    private var originalCapability: ContextModelCapabilities? = null
    private lateinit var originalModel: () -> ContextModel
    private lateinit var originalImagePreparation: suspend ()->Unit
    private lateinit var originalDiscussion: suspend (Post, Page?) -> Page
    private lateinit var policy: NetworkPolicy
    private lateinit var searchPolicy: NetworkPolicy
    private val owners = mutableListOf<String>()
    private val posts = mutableListOf<Post>()
    private val suffix = System.nanoTime().toString().takeLast(10)
    private val originalBrief = "Jamie reports that the loose battery connector was repaired. A replacement battery was not needed."
    private val quote = "I reseated the loose connector. The original battery is still working."
    private val findingId = "context-ui-finding-$suffix"
    private val sourceId = "context-ui-source-$suffix"

    @Before fun preserve() {
        assumeTrue("Context fixtures require the isolated test device", InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice") == "true")
        runBlocking { repo.awaitReady() }
        aiPrefs = ui.activity.getSharedPreferences("ai-settings", Context.MODE_PRIVATE).all.toMap()
        readerPrefs = repo.prefs.export().toString(); originalOptions = reader.preferences.state.value; originalCapability = reader.capability.value
        originalModel = reader.modelFactory; originalDiscussion = reader.discussionRead
        originalImagePreparation=reader.prepareImageReader;reader.prepareImageReader={}
        policy = repo.gateway.currentPolicy(); searchPolicy = repo.gateway.currentPolicy(NetworkRoute.TOR)
        repo.ai.enabled(false); repo.prefs.intervalMinutes = 0; repo.prefs.archiveChecks = false; repo.prefs.communityComments = false
        reader.preferences.update(ContextOptions()); reader.changed()
        ui.runOnIdle { repo.cancelSearchPreparation(); repo.tor.enable(false); repo.gateway.configure(NetworkPolicy(tor = true, ready = false)); ui.activity.setContent { MaterialTheme { Text("Preparing local Context fixtures") } } }
    }

    @After fun restore() {
        if (!::aiPrefs.isInitialized) return
        ui.runOnIdle { reader.close(); reader.pauseAll(); repo.ai.enabled(false); ui.activity.setContent { Text("Context fixture complete") } }
        runBlocking { withTimeout(10_000) { reader.active.first { it.isEmpty() } } }
        reader.modelFactory = originalModel; reader.discussionRead = originalDiscussion
        reader.prepareImageReader=originalImagePreparation
        owners.forEach { reader.store.delete(it) }
        runBlocking { repo.dao.removePosts(posts.map(repo::key)) }
        val editor = ui.activity.getSharedPreferences("ai-settings", Context.MODE_PRIVATE).edit().clear()
        aiPrefs.forEach { (key, value) -> when (value) { is Boolean -> editor.putBoolean(key, value); is String -> editor.putString(key, value); is Int -> editor.putInt(key, value); else -> error("Unexpected AI fixture setting") } }; editor.commit()
        reader.preferences.update(originalOptions); reader.changed(); reader.capability.value = originalCapability
        ui.runOnIdle { ui.activity.getSharedPreferences("antisocial", Context.MODE_PRIVATE).edit().clear().commit(); repo.prefs.restore(JSONObject(readerPrefs)); repo.applyNetworkSettings(); repo.gateway.configure(policy); repo.gateway.configureTor(searchPolicy); scheduleRefresh(ui.activity, repo.prefs) }
        runBlocking { repo.ai.refresh() }
    }

    private fun fixture(brief: String = originalBrief, status: String = "partial", savedCapture: Boolean=false): ContextRecord {
        val now = System.currentTimeMillis()
        val post = Post("991$suffix", Platform.X, "https://x.com/context_fixture/status/991$suffix", "Workshop", "The battery repair workshop shared a practical update.", title = "A repair worth understanding", publishedAt = now)
        posts += post
        runBlocking { val payload = repo.gson.toJson(post); repo.dao.putPost(PostRow(repo.key(post), post.platform.id, payload, now, now, bytes = payload.toByteArray().size.toLong())) }
        val snapshot = ContextSnapshot(listOf(post), post.body, post.title, "post-$suffix")
        val record = reader.store.resolve(snapshot).copy(brief = brief, status = status, message = "HTTP 503 internal provider error that must not dominate the reader", updatedAt = now, checkedAt = now, findingStamp = "stamp-$suffix",deliveryPolicy=reader.deliveryIdentity())
        owners += record.id
        val document = ContextDocument(sourceId, "r1", quote, author = "Jamie", url = "https://x.com/repair_friend/status/881$suffix", kind = "comment", fetchedAt = now, metadata = mapOf("platform" to "X", "root" to CrossPosts.originalKey(post))+if(savedCapture)mapOf("savedCapture" to "true")else emptyMap())
        reader.store.ingest(record.id, listOf(document))
        val finding = ContextFinding(findingId, originalBrief, "experience", listOf(sourceId), mapOf(sourceId to quote))
        reader.store.commitUnit(record.id, ContextUnitOutcome("unit-$suffix", sourceId, "r1", 0, quote.length, ContextUnitStatus.SIGNAL, listOf(finding)))
        reader.store.processed(record.id, sourceId, "r1")
        reader.store.stream(record.id, ContextStream("platform-$suffix", post, status = "unavailable", message = "HTTP 503 internal provider error", count = 3))
        reader.store.stream(record.id, ContextStream("shared-$suffix", post, "shared", status = "pending", count = 2))
        if(savedCapture)reader.store.stream(record.id,ContextStream("saved-$suffix",post,"saved",status="limited",limited=true,count=1,fetchedAt=now,message="Saved discussion snapshot · live coverage is checked separately"))
        reader.store.publish(record)
        return record
    }

    private fun show(record: ContextRecord, dark: Boolean = true, links: MutableList<String> = mutableListOf()) {
        ui.runOnIdle {
            reader.open(record.id)
            ui.activity.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { Surface(Modifier.fillMaxSize()) { ContextSheet(repo) { links += it } } } }
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("Context brief section").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun evidence(name: String, settle: Boolean = true) {
        if (settle) ui.mainClock.advanceTimeBy(1_000)
        ui.waitForIdle()
        val folder = File(requireNotNull(ui.activity.getExternalFilesDir("validation")), "context-ui").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val roots = ui.onAllNodes(isRoot(), useUnmergedTree = true)
        File(folder, "$name-semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { index -> "Root $index\n" + roots[index].printToString() })
        File(folder, "$name-reader-state.txt").writeText("selected=${reader.selected.value}\nactive=${reader.active.value}\n")
    }

    private fun awaitLocalQuote(expected:String=quote) {
        // Android adds the dialog window asynchronously on some API levels.
        try {
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("Context local quote", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            runCatching { evidence("citation-timeout", settle = false) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        ui.onNodeWithTag("Context local quote", useUnmergedTree = true).assertTextEquals(expected)
    }

    private fun scrollToCitation(prefix:String="Context") {
        val finding = "$prefix finding $findingId"
        ui.waitUntil(5_000) { ui.onAllNodesWithTag(finding).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        // A composed LazyColumn descendant can still lie below its viewport.
        // Bring the complete card into view before targeting its nested row.
        ui.onNodeWithTag(finding).performScrollTo()
        ui.onNodeWithTag("$prefix citation $findingId 0").performScrollTo().assertIsDisplayed()
    }

    @Test fun availableFindingsAppearDirectlyWhenTheCombinedBriefIsNotReady() {
        show(fixture(brief = ""))
        ui.onNodeWithTag("Context awaiting brief").assertExists()
        ui.onNodeWithText("AVAILABLE FINDINGS").assertDoesNotExist()
        ui.onNodeWithText(originalBrief).assertDoesNotExist()
        ui.onNodeWithTag("Context findings toggle").performScrollTo().performClick()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context finding $findingId"))
        ui.onNodeWithText(originalBrief).assertIsDisplayed()
        evidence("available-findings-dark")
    }

    private fun currentFinding(record:ContextRecord,text:String) {
        val old=reader.store.document(record.id,sourceId)!!.document
        val source=old.copy(revision="current-$suffix",text=text)
        reader.store.ingest(record.id,listOf(source))
        reader.store.commitUnit(record.id,ContextUnitOutcome("current-unit-$suffix",sourceId,source.revision,0,text.length,ContextUnitStatus.SIGNAL,
            listOf(ContextFinding(findingId,text,"correction",listOf(sourceId),mapOf(sourceId to text)))))
        reader.store.save(reader.store.record(record.id)!!.copy(stale=true,status="partial"))
    }

    @Test fun currentFindingsAndEarlierBriefKeepTheirOwnCitationsEvenWhenSourceIdsMatch() {
        val record=fixture();reader.store.protect(record.id,true)
        val fresh="The service manual specifies a 56 Wh battery, correcting the earlier capacity."
        currentFinding(record,fresh);show(record)
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        ui.onNodeWithTag("Context finding $findingId").assertDoesNotExist()
        ui.onNodeWithTag("Context findings toggle").performScrollTo().performClick()
        scrollToCitation()
        ui.onNodeWithTag("Context citation $findingId 0").performClick()
        awaitLocalQuote(fresh)
        val current=reader.store.document(record.id,sourceId)!!.document
        reader.store.ingest(record.id,listOf(current.copy(revision="later-$suffix",text="A later edit must not replace the open citation.")))
        ui.onNodeWithTag("Context local quote",useUnmergedTree=true).assertTextEquals(fresh)
        ui.onNodeWithTag("Close Context source").performClick()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context earlier toggle"))
        ui.onNodeWithTag("Context earlier toggle").performClick()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context earlier brief"))
        ui.onNodeWithTag("Context earlier brief").assertTextEquals(originalBrief)
        scrollToCitation("Earlier Context")
        ui.onNodeWithTag("Earlier Context citation $findingId 0").performClick();awaitLocalQuote()
        assertEquals(originalBrief,reader.store.savedView(record.id)!!.record.brief)
        evidence("current-and-earlier-citations-dark")
    }

    @Test fun firstBriefAppearsAutomaticallyEvenWhenSourceNotesArrivedBeforeIt() {
        val record=fixture(brief="",status="reading")
        show(record)
        ui.onNodeWithTag("Context brief").assertDoesNotExist()
        val delivered="The battery uses a loose connector; reseating it restored operation."
        reader.store.publish(record.copy(brief=delivered,status="ready",checkedAt=System.currentTimeMillis(),cycleStartedAt=0,deliveryPolicy=reader.deliveryIdentity()))
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Context brief").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Context brief").assertTextEquals(delivered)
        ui.onNodeWithTag("Context updates ready").assertDoesNotExist()
        evidence("first-brief-automatic-dark")
    }

    @Test fun aBriefFromTheOldWriterIsReplacedAutomaticallyAfterItsFirstRegeneration() {
        val base=fixture();val old=base.copy(deliveryPolicy=null,status="reading")
        reader.store.publish(old);show(old)
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        val delivered="The manual specifies 56 Wh, not 65 Wh."
        reader.store.publish(old.copy(brief=delivered,status="ready",checkedAt=System.currentTimeMillis(),cycleStartedAt=0,deliveryPolicy=reader.deliveryIdentity()))
        ui.waitUntil(10_000){ui.onAllNodesWithText(delivered).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Context brief").assertTextEquals(delivered)
        ui.onNodeWithTag("Context updates ready").assertDoesNotExist()
        evidence("old-writer-automatic-replacement-dark")
    }

    @Test fun currentFindingsWaitBehindTheExistingUpdateActionBeforeReplacingVisibleContent() {
        val record=fixture();show(record)
        val bounds=ui.onNodeWithTag("Context brief").fetchSemanticsNode().boundsInRoot
        currentFinding(record,"The corrected service manual specifies a 56 Wh battery.")
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Context updates ready").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        assertEquals(bounds,ui.onNodeWithTag("Context brief").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithTag("Context current findings").assertDoesNotExist()
        ui.onNodeWithTag("Context updates ready").performClick()
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        ui.onNodeWithTag("Context updates ready").assertDoesNotExist()
        ui.onNodeWithTag("Context findings toggle").performScrollTo().performClick()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context finding $findingId"))
        ui.onNodeWithText("The corrected service manual specifies a 56 Wh battery.").assertIsDisplayed()
        evidence("current-findings-ready-dark")
    }

    @Test fun rejectedNotesDoNotAdvertiseAnUnchangedBriefAsAnUpdate() {
        val base=fixture()
        val record=base.copy(briefFindingIds=listOf(findingId),briefSignalStamp="same-useful-information",briefSources=listOf(sourceId))
        reader.store.publish(record);show(record)
        val text="An unrelated stock price taunt."
        val document=ContextDocument("noise-$suffix","1",text,kind="comment")
        reader.store.ingest(record.id,listOf(document))
        val finding=ContextFinding("noise-finding-$suffix",text,"context",listOf(document.id),mapOf(document.id to text))
        reader.store.commitUnit(record.id,ContextUnitOutcome("noise-unit-$suffix",document.id,"1",0,text.length,ContextUnitStatus.SIGNAL,listOf(finding)))
        reader.store.publish(record.copy(findingStamp="new-working-notes"))
        ui.mainClock.advanceTimeBy(1000);ui.waitForIdle()
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        ui.onNodeWithTag("Context updates ready").assertDoesNotExist()
        ui.onNodeWithTag("Context findings toggle").performScrollTo().performClick()
        ui.onNodeWithText(text).assertDoesNotExist()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context sources toggle"))
        ui.onNodeWithText("1 source cited",substring=true).assertExists()
    }

    @Test fun currentFindingsAreReadableAlongsideAnEmptyEarlierPublication() {
        val record=fixture(brief="")
        reader.store.processingPolicy(record.id,"current-policy-$suffix")
        reader.store.publish(reader.store.record(record.id)!!.copy(stale=false))
        assertTrue(reader.store.view(record.id)!!.findings.isEmpty())
        currentFinding(record,"The manual identifies the connector as the cause of the fault.")
        reader.store.save(reader.store.record(record.id)!!.copy(stale=false))
        val before=repo.gateway.protectionStats()
        show(record)
        ui.onNodeWithTag("Context earlier toggle").assertDoesNotExist()
        ui.onNodeWithTag("Context awaiting brief").assertExists()
        ui.onNodeWithTag("Context findings toggle").performScrollTo().performClick()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context finding $findingId"))
        ui.onNodeWithText("The manual identifies the connector as the cause of the fault.").assertIsDisplayed()
        assertEquals(before,repo.gateway.protectionStats());assertTrue(reader.active.value.isEmpty())
        evidence("empty-publication-current-findings-dark")
    }

    @Test fun briefLeadsTheNativeReaderAndUnavailableSourcesDoNotBecomeAnErrorWall() {
        val record = fixture(); show(record)
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        ui.onNodeWithTag("Context as of").assertExists()
        ui.onNodeWithText(record.message).assertDoesNotExist()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context sources toggle"))
        ui.onNodeWithTag("Context sources toggle").performClick()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context source coverage"))
        ui.onNodeWithText("Unavailable this check").assertExists(); ui.onNodeWithText("Waiting").assertExists()
        ui.onNodeWithTag("Context checkpoint count").assertTextContains("1 reading step saved", substring = true)
        ui.onNodeWithTag("Context partial coverage").assertExists(); evidence("partial-source-coverage-dark")
    }

    @Test fun capturedDiscussionsStayDatedAndSeparateFromUnavailableLiveCoverage() {
        val record=fixture(savedCapture=true);show(record)
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context sources toggle"))
        ui.onNodeWithTag("Context sources toggle").performClick()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context source coverage"))
        ui.onNodeWithText("Saved discussions").assertExists()
        ui.onNodeWithText("Dated local copies · live coverage remains separate").assertExists()
        ui.onNodeWithText("Unavailable this check").assertExists()
        evidence("saved-discussion-coverage-dark")
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context findings toggle"))
        ui.onNodeWithTag("Context findings toggle").performClick();scrollToCitation()
        ui.onNodeWithTag("Context citation $findingId 0").performClick();awaitLocalQuote()
        ui.onNode(hasText("Captured ",substring=true),useUnmergedTree=true).assertExists()
        ui.onNode(hasText("Retrieved ",substring=true),useUnmergedTree=true).assertDoesNotExist()
        evidence("saved-discussion-local-excerpt-dark")
    }

    @Test fun incomingContextWaitsBehindUpdatesReadyWithoutReplacingTheParagraphBeingRead() {
        val record = fixture(); show(record)
        val briefBounds=ui.onNodeWithTag("Context brief").fetchSemanticsNode().boundsInRoot
        try {
            ui.runOnIdle {reader.active.value=setOf(record.id)}
            ui.onNodeWithTag("Context reading status").assertTextEquals("Reading · progress saved as it goes")
            ui.waitForIdle()
            assertEquals(briefBounds,ui.onNodeWithTag("Context brief").fetchSemanticsNode().boundsInRoot)
        }finally {ui.runOnIdle {reader.active.value=emptySet()}}
        ui.waitForIdle()
        assertEquals(briefBounds,ui.onNodeWithTag("Context brief").fetchSemanticsNode().boundsInRoot)
        val newer = "The connector repair still works. Jamie has now shared the part number."
        reader.store.publish(record.copy(brief = newer, findingStamp = "new-stamp", updatedAt = record.updatedAt + 10))
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("Context updates ready").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief); ui.onNodeWithText(newer).assertDoesNotExist()
        evidence("updates-waiting-dark")
        ui.onNodeWithTag("Context updates ready").performClick()
        ui.onNodeWithTag("Context brief").assertTextEquals(newer); ui.onNodeWithTag("Context updates ready").assertDoesNotExist()
    }

    @Test fun citationsOpenTheExactLocalCopyBeforeOpeningTheAuthenticSource() {
        val record = fixture(); val links = mutableListOf<String>(); show(record, links = links)
        val before = repo.gateway.protectionStats()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context findings toggle"))
        ui.onNodeWithTag("Context findings toggle").performClick()
        scrollToCitation()
        evidence("citation-before-click", settle = false)
        ui.onNodeWithTag("Context citation $findingId 0").performClick()
        evidence("citation-after-click", settle = false)
        awaitLocalQuote(); assertTrue(links.isEmpty())
        assertEquals(before, repo.gateway.protectionStats()); evidence("local-source-excerpt-dark")
        ui.onNodeWithTag("Context open source").performClick()
        assertEquals(listOf("https://x.com/repair_friend/status/881$suffix"), links); ui.onNodeWithTag("Context sheet").assertDoesNotExist()
        assertEquals(before, repo.gateway.protectionStats())
    }

    @Test fun additionalCopyKeepsItsOwnNativeCitationAlongsideTheUnchangedBrief() {
        val record=fixture();val original=reader.store.documents(record.id).single().document
        val copy=original.copy(id="$sourceId-copy",author="Alex",url="https://bsky.app/profile/alex.test/post/881$suffix",
            metadata=original.metadata+mapOf("platform" to "Bluesky"))
        reader.store.ingest(record.id,listOf(copy))
        val finding=reader.store.findings(record.id).single().copy(sources=listOf(copy.id),quotes=mapOf(copy.id to quote))
        reader.store.commitUnit(record.id,ContextUnitOutcome("unit-copy-$suffix",copy.id,copy.revision,0,quote.length,ContextUnitStatus.REPEATED,listOf(finding)))
        reader.store.processed(record.id,copy.id,copy.revision)
        reader.store.publish(record.copy(findingStamp="copied-$suffix"))
        val links=mutableListOf<String>();show(record,links=links)
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context findings toggle"))
        ui.onNodeWithTag("Context findings toggle").performClick();scrollToCitation()
        ui.onNodeWithTag("Context citation $findingId 0").assertTextContains("Jamie",substring=true)
        ui.onNodeWithTag("Context citation $findingId 1").assertTextContains("Alex",substring=true).performClick()
        awaitLocalQuote();evidence("additional-copy-local-source-dark")
        ui.onNodeWithTag("Context local quote",useUnmergedTree=true).assertTextEquals(quote)
        ui.onNodeWithTag("Context open source").performClick()
        assertEquals(listOf(copy.url),links)
    }

    @Test fun earlierPublicationKeepsItsCitedTextWhileAChangedSourceIsWaiting() {
        val record = fixture(); show(record, dark = false)
        val previous = reader.store.document(record.id, sourceId)!!.document
        reader.store.ingest(record.id, listOf(previous.copy(revision = "r2", text = "The author has changed this public comment.")))
        reader.store.save(record.copy(stale = true, status = "queued"))
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Earlier Context · checking source changes").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context findings toggle")); ui.onNodeWithTag("Context findings toggle").performClick()
        scrollToCitation(); ui.onNodeWithTag("Context citation $findingId 0").performClick()
        awaitLocalQuote(); evidence("earlier-publication-local-quote-light")
    }

    @Test fun localCaptionScopeExplainsTheAvailableTextWithoutAProviderRequest() {
        val record=fixture();val old=reader.store.document(record.id,sourceId)!!.document;val finding=reader.store.findings(record.id).single()
        val source=old.copy(metadata=old.metadata+mapOf("medium" to "Published captions","truncated" to "true"))
        reader.store.ingest(record.id,listOf(source));reader.store.commitUnit(record.id,ContextUnitOutcome("caption-scope-$suffix",sourceId,source.revision,0,quote.length,ContextUnitStatus.SIGNAL,listOf(finding)))
        reader.store.processed(record.id,sourceId,source.revision);reader.store.publish(record)
        val before=repo.gateway.protectionStats();show(record)
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context findings toggle"));ui.onNodeWithTag("Context findings toggle").performClick();scrollToCitation()
        ui.onNodeWithTag("Context citation $findingId 0").performClick();awaitLocalQuote()
        ui.onNodeWithTag("Context observation scope").assertTextEquals("Caption text; visual steps need the original video. The text extract is incomplete.")
        assertEquals(before,repo.gateway.protectionStats());evidence("caption-observation-scope-dark")
    }

    @Test fun sourcePublicationTimeIsVisibleSeparatelyFromItsRetrievalTime() {
        val record=fixture();val old=reader.store.document(record.id,sourceId)!!.document
        val finding=reader.store.findings(record.id).single()
        val dated=old.copy(metadata=old.metadata+mapOf("published" to "2026-10-19T12:00:00Z"))
        reader.store.ingest(record.id,listOf(dated))
        reader.store.commitUnit(record.id,ContextUnitOutcome("dated-unit-$suffix",sourceId,dated.revision,0,quote.length,ContextUnitStatus.SIGNAL,listOf(finding)))
        reader.store.processed(record.id,sourceId,dated.revision);reader.store.publish(record)
        show(record)
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context findings toggle"))
        ui.onNodeWithTag("Context findings toggle").performClick();scrollToCitation()
        ui.onNodeWithTag("Context citation $findingId 0").performClick();awaitLocalQuote()
        ui.onNodeWithTag("Context publication time").assertTextEquals("Posted 19 Oct 2026, 12:00:00 UTC")
        ui.onNodeWithTag("Context local quote",useUnmergedTree=true).assertTextEquals(quote)
        evidence("known-publication-and-retrieval-dark")
    }

    @Test fun sourceEditionTimesCanExpandWithoutAssigningDatesToUnknownAuthors() {
        val record=fixture();val old=reader.store.document(record.id,sourceId)!!.document;val finding=reader.store.findings(record.id).single()
        val editions=listOf(ContextSourcePublication("Jamie",ContextPublicationTime.parse("2026-10-19T12:00:00Z")),ContextSourcePublication("Alex",ContextPublicationTime.parse("2026-10-20T12:00:00Z")),ContextSourcePublication("Casey",null),ContextSourcePublication("Morgan",ContextPublicationTime.parse("2026-10-21T12:00:00Z")))
        val source=old.copy(metadata=old.metadata+ContextSourcePublication.encode(editions))
        reader.store.ingest(record.id,listOf(source));reader.store.commitUnit(record.id,ContextUnitOutcome("timed-editions-$suffix",sourceId,source.revision,0,quote.length,ContextUnitStatus.SIGNAL,listOf(finding)))
        reader.store.processed(record.id,sourceId,source.revision);reader.store.publish(record);show(record)
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context findings toggle"));ui.onNodeWithTag("Context findings toggle").performClick();scrollToCitation()
        ui.onNodeWithTag("Context citation $findingId 0").performClick();awaitLocalQuote()
        ui.onNodeWithText("Casey (publication time unavailable)").assertExists()
        ui.onNodeWithText("Morgan (posted 21 Oct 2026, 12:00:00 UTC)").assertExists()
        ui.onNodeWithText("Alex (posted 20 Oct 2026, 12:00:00 UTC)").assertDoesNotExist()
        ui.onNodeWithTag("Context publication times toggle").performScrollTo().performClick()
        ui.onNodeWithText("Alex (posted 20 Oct 2026, 12:00:00 UTC)").performScrollTo().assertIsDisplayed()
        evidence("expanded-edition-times-dark")
    }

    @Test fun modelReplacementKeepsTheEarlierBriefAndItsLocalCitations() {
        val record=fixture()
        reader.store.publish(record.copy(model="previous-model",processingPolicy="previous-policy",compositionPolicy="previous-policy"))
        reader.store.protect(record.id,true)
        show(record)
        reader.store.processingPolicy(record.id,"replacement-model-policy")
        assertTrue(reader.store.findings(record.id).isEmpty())
        ui.waitUntil(10_000) {ui.onAllNodesWithText("Earlier brief kept · partial source coverage").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        evidence("model-replacement-earlier-context-dark")
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context findings toggle"))
        ui.onNodeWithTag("Context findings toggle").performClick()
        scrollToCitation();ui.onNodeWithTag("Context citation $findingId 0").performClick()
        awaitLocalQuote();ui.onNodeWithTag("Context local quote",useUnmergedTree=true).assertTextEquals(quote)
        evidence("model-replacement-earlier-citation-dark")
        assertEquals(originalBrief,reader.store.savedView(record.id)!!.record.brief)
    }

    @Test fun savedSnapshotCanBeReadAlongsideTheLatestBriefWithoutBeingOverwritten() {
        val record = fixture(); reader.store.protect(record.id, true)
        val newer = "Latest Context includes another useful workshop reply."
        reader.store.publish(reader.store.record(record.id)!!.copy(brief = newer, findingStamp = "new", updatedAt = record.updatedAt + 10))
        show(record)
        ui.onNodeWithTag("Context brief").assertTextEquals(newer)
        ui.onNodeWithTag("Context saved view").performClick(); ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        ui.onNodeWithTag("Context latest view").performClick(); ui.onNodeWithTag("Context brief").assertTextEquals(newer)
        assertEquals(originalBrief, reader.store.savedView(record.id)!!.record.brief)
    }

    @Test fun partialFindingsRemainReadableBeforeTheFirstBriefIsReadyAndBackDismisses() {
        val record = fixture(brief = "", status = "paused"); show(record)
        ui.onNodeWithTag("Context awaiting brief").assertExists()
        ui.onNodeWithTag("Context finding $findingId").assertDoesNotExist()
        ui.onNodeWithTag("Context findings toggle").performScrollTo().performClick()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context finding $findingId")); ui.onNodeWithText(originalBrief).assertExists()
        evidence("partial-findings-before-brief-dark")
        // Exercise Back in the focused Android window rather than requesting
        // a global accessibility action whose acceptance was not checked.
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5000) { reader.selected.value == null }; ui.onNodeWithTag("Context sheet").assertDoesNotExist()
    }

    @Test fun openingUnfinishedContextStartsReadingWithoutAnExtraRefreshTapAndStopStillWorks() {
        val started = CompletableDeferred<Unit>()
        reader.modelFactory = { object : ContextModel {
            override suspend fun count(system: String, user: String) = 256
            override suspend fun generate(system: String, user: String, maxTokens: Int): String {
                fixtureBriefAdmission(user)?.let {return it}; started.complete(Unit); awaitCancellation() }
        } }
        reader.discussionRead = { _, _ -> throw AssertionError("The stopped initial read must not reach a discussion source") }
        repo.ai.preferences.provider("text", AiProviderSettings(enabled = true, baseUrl = "https://context-fixture.example/v1", model = "fixture-only"))
        repo.ai.preferences.routing("text", AiRouting.PROVIDER_ONLY); repo.ai.enabled(true)
        val record = fixture(status = "paused"); show(record); val before = repo.gateway.protectionStats()
        ui.waitUntil(10_000) { started.isCompleted && record.id in reader.active.value }
        ui.onNodeWithTag("Stop Context").performClick()
        ui.waitUntil(10_000) { record.id !in reader.active.value }
        assertEquals("paused", reader.store.record(record.id)!!.status); ui.onNodeWithTag("Context brief").assertTextEquals(originalBrief)
        assertEquals(before, repo.gateway.protectionStats())
    }

    @Test fun uncertainLanguageStillProducesANativeBriefFromAvailableReplyEvidence() {
        val now=System.currentTimeMillis()
        val post=Post("best-effort-$suffix",Platform.X,"https://x.com/context_fixture/status/990$suffix","Workshop","Night lights 💡...")
        posts+=post
        runBlocking {val payload=repo.gson.toJson(post);repo.dao.putPost(PostRow(repo.key(post),post.platform.id,payload,now,now,bytes=payload.toByteArray().size.toLong()))}
        val reply=post.copy(id="best-effort-reply-$suffix",canonicalUrl="https://x.com/context_reply/status/989$suffix",author="Jamie",body=quote,parentId=post.id)
        val record=reader.store.resolve(ContextSnapshot(listOf(post),post.body,"","best-effort-$suffix"));owners+=record.id
        reader.modelFactory={object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                val data=com.google.gson.JsonParser.parseString(user).asJsonObject
                if(data.has("findings"))return repo.gson.toJson(mapOf("text" to quote))
                val text=data["text"].asString
                return com.google.gson.JsonObject().apply {addProperty("status","signal");add("findings",com.google.gson.JsonArray().apply {add(com.google.gson.JsonObject().apply {
                    addProperty("text",text);addProperty("quote",text);addProperty("kind",if(data["kind"].asString=="post")"brief"else "experience")
                })})}.toString()
            }
        }}
        reader.discussionRead={_,_->Page(listOf(post,reply))}
        repo.ai.preferences.provider("text",AiProviderSettings(enabled=true,baseUrl="https://context-fixture.example/v1",model="fixture-only"))
        repo.ai.preferences.routing("text",AiRouting.PROVIDER_ONLY);repo.ai.enabled(true)
        runBlocking(Dispatchers.Default){withTimeout(20_000){reader.run(record.id)}}
        val ready=reader.store.record(record.id)!!;assertTrue(ready.brief.contains(quote))
        show(ready,dark=false)
        ui.onNodeWithTag("Context brief").assertTextContains(quote,substring=true)
        ui.onNodeWithText("Some material needs another model review.",substring=true).assertDoesNotExist()
        evidence("best-effort-language-available-brief-light")
    }

    @Test fun contextActionAndSettingsStayHiddenUntilAiIsEnabledAndReady() {
        val record = fixture(); val post = record.snapshot.roots.first(); val before = repo.gateway.protectionStats()
        ui.runOnIdle { ui.activity.setContent { MaterialTheme { Column { ContextPostAction(repo, post, listOf(post)); ContextSettingsContent(repo) } } } }
        ui.onNodeWithTag("Context ${repo.key(post)}").assertDoesNotExist(); ui.onNodeWithTag("Context settings").assertDoesNotExist()
        repo.ai.preferences.provider("text", AiProviderSettings(enabled = true, baseUrl = "https://context-fixture.example/v1", model = "fixture-only")); repo.ai.preferences.routing("text", AiRouting.PROVIDER_ONLY)
        ui.runOnIdle { repo.ai.enabled(true) }
        ui.onNodeWithTag("Context ${repo.key(post)}").assertExists(); ui.onNodeWithTag("Context settings").assertExists()
        ui.runOnIdle { repo.ai.enabled(false) }
        ui.onNodeWithTag("Context ${repo.key(post)}").assertDoesNotExist(); ui.onNodeWithTag("Context settings").assertDoesNotExist(); assertEquals(before, repo.gateway.protectionStats())
    }

    @Test fun settingsHaveQuietDefaultsAndSaveReadingPoliciesLocallyWithoutNetworking() {
        ui.runOnIdle { repo.ai.enabled(true); ui.activity.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) { ContextSettingsContent(repo) } } } } }
        val before = repo.gateway.protectionStats()
        ui.onNodeWithTag("Context read ahead").assertDoesNotExist(); ui.onNodeWithTag("Context settings expand").performClick()
        ui.onNodeWithTag("Context read ahead").assertIsOff().performClick().assertIsOn()
        ui.onNodeWithTag("Context lookahead 10").performScrollTo().performClick().assertIsSelected()
        ui.onNodeWithTag("Context keep saved").performScrollTo().performClick().assertIsOn()
        ui.onNodeWithTag("Context refresh menu").performScrollTo().performClick(); ui.onNodeWithText("Every 6 hours").performClick()
        ui.onNodeWithTag("Context media policy menu").performScrollTo().performClick(); ui.onNodeWithText("Only when I request it").performClick()
        ui.onNodeWithTag("Context unmetered media").performScrollTo().assertIsOn()
        ui.onNodeWithTag("Context advanced settings").performScrollTo().performClick()
        ui.onNodeWithTag("Context battery menu").performScrollTo().performClick(); ui.onNodeWithText("30% battery").performClick()
        ui.onNodeWithTag("Context slice menu").performScrollTo().performClick(); ui.onNodeWithText("1 minute").performClick()
        ui.onNodeWithTag("Context comment links menu").performScrollTo().performClick(); ui.onNodeWithText("Up to 64 pages").performClick()
        ui.onNodeWithTag("Context processing menu").performScrollTo().performClick(); ui.onNodeWithText("Broader comparisons").performClick()
        assertEquals("extended", ContextPreferences(ui.activity).state.value.processingStyle)
        ui.onNodeWithTag("Context remote window input").performScrollTo().performTextReplacement("32768")
        ui.onNodeWithTag("Context apply remote window").performScrollTo().performClick()
        val reopened = ContextPreferences(ui.activity).state.value
        assertTrue(reopened.ahead); assertTrue(reopened.saved); assertEquals(10, reopened.lookahead); assertEquals(360, reopened.refreshMinutes)
        assertEquals("manual", reopened.mediaPolicy); assertEquals(30, reopened.minimumBattery); assertEquals(60, reopened.sliceSeconds)
        assertEquals(64, reopened.commentLinks); assertEquals(32768, reopened.remoteContextTokens)
        assertEquals(before, repo.gateway.protectionStats()); evidence("context-local-reading-settings-dark")
    }

    @Test fun offlineLanguagesHaveCompactControlsAndOpeningThemMakesNoNetworkRequest() {
        val original=repo.languages.options.value
        try {
            ui.runOnIdle {repo.ai.enabled(true);ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface{Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)){ContextSettingsContent(repo)}}}}}
            val before=repo.gateway.protectionStats()
            ui.onNodeWithTag("Context settings expand").performClick()
            ui.onNodeWithTag("Context languages expand").performScrollTo().performClick()
            ui.onNodeWithTag("Context automatic languages").performScrollTo().performClick()
            assertEquals(!original.automatic,repo.languages.options.value.automatic)
            ui.onNodeWithTag("Context language menu").performScrollTo().assertIsDisplayed()
            val action=if("es" in repo.languages.state.value.installed)"Context remove language"else "Context download language"
            ui.onNodeWithTag(action).performScrollTo().assertIsDisplayed()
            assertEquals(before,repo.gateway.protectionStats());evidence("context-languages-dark")
        }finally {repo.languages.update(original)}
    }

    @Test fun advancedWindowInformationUsesOnlyAlreadyObservedMetadataAndAcceptsAutomaticAgain() {
        ui.runOnIdle {
            repo.ai.enabled(true)
            reader.capability.value = ContextModelCapabilities(contextTokens = 262144, confidence = ContextCapabilityConfidence.ACTIVE_WINDOW, source = "Fixture server active window", model = "fixture", activeContextTokens = 262144)
            ui.activity.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) { ContextSettingsContent(repo) } } } }
        }
        val before = repo.gateway.protectionStats()
        ui.onNodeWithTag("Context observed window").assertDoesNotExist(); ui.onNodeWithTag("Context settings expand").performClick()
        ui.onNodeWithTag("Context observed window").assertDoesNotExist(); ui.onNodeWithTag("Context advanced settings").performScrollTo().performClick()
        ui.onNodeWithTag("Context observed window").performScrollTo().assertTextContains("262,144", substring = true)
        ui.onNodeWithTag("Context window source").performScrollTo().assertTextEquals("Fixture server active window")
        ui.onNodeWithTag("Context remote window input").performScrollTo().performTextReplacement("1023")
        ui.onNodeWithTag("Context apply remote window").assertIsNotEnabled(); assertEquals(0, reader.preferences.state.value.remoteContextTokens)
        ui.onNodeWithTag("Context remote window input").performTextReplacement("65536")
        ui.onNodeWithTag("Context apply remote window").performScrollTo().performClick(); assertEquals(65536, reader.preferences.state.value.remoteContextTokens)
        ui.onNodeWithTag("Context automatic window").performScrollTo().performClick(); assertEquals(0, reader.preferences.state.value.remoteContextTokens)
        ui.runOnIdle { ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ui.activity.window.decorView.windowToken,0) }
        val dismissStarted=android.os.SystemClock.elapsedRealtime()
        // API 26 reports the target inset before the separate IME window has
        // finished fading. Capture the settled native surface, not that frame.
        ui.waitUntil(5_000) { android.os.SystemClock.elapsedRealtime()-dismissStarted>=700 && androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true }
        ui.onNodeWithTag("Context observed window").performScrollTo().assertIsDisplayed()
        assertEquals(before, repo.gateway.protectionStats()); evidence("advanced-observed-window-local-metadata")
    }

    @Test fun mediaToolsAreOptionalAndOpeningLanguageChoicesStartsNoDownload() {
        ui.runOnIdle { repo.ai.enabled(true); ui.activity.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) { ContextSettingsContent(repo) } } } } }
        val events=java.util.concurrent.CopyOnWriteArrayList<NetworkEvent>();val prior=repo.gateway.onNetworkEvent
        repo.gateway.onNetworkEvent={event->events+=event;prior?.invoke(event)}
        try {
        val before = repo.gateway.protectionStats()
        ui.onNodeWithTag("Context settings expand").performClick()
        ui.onNodeWithTag("Context media expand").performScrollTo().performClick()
        ui.onNodeWithTag("Context OCR language menu").performScrollTo().performClick()
        ui.onAllNodesWithText("English").assertCountEquals(2) // Selected button and the open menu option.
        ui.onNodeWithText("Spanish").assertIsDisplayed().performClick()
        ui.onNodeWithTag("Context OCR language menu").assertTextEquals("Spanish")
        assertFalse(repo.mediaTools.state.value.working); assertNull(repo.mediaTools.state.value.progress)
        assertEquals("Unexpected gateway activity: $events",before, repo.gateway.protectionStats()); evidence("optional-local-media-tools-dark")
        }finally {repo.gateway.onNetworkEvent=prior}
    }
    @Test fun anEarlierReplyIsReadableFromTheSavedLocalCopyWithoutNetworking() {
        val record=fixture();val child=reader.store.document(record.id,sourceId)!!.document.copy(text="No.",revision="parent-ui")
        val parent=ContextDocument("earlier-$suffix","r1","Did the workshop replace the entire battery?",author="Alex",kind="comment")
        reader.store.ingest(record.id,listOf(parent,child.copy(metadata=child.metadata+mapOf("contextParent" to parent.id))))
        reader.store.saveReading(record.id,parent,"body",parent.text,app.antisocial.core.ContextReadingText.identity(parent.text,app.antisocial.core.EnglishReadingPreparer.POLICY))
        val finding=ContextFinding(findingId,"No.","answer",listOf(sourceId),mapOf(sourceId to child.text))
        reader.store.commitUnit(record.id,app.antisocial.core.ContextUnitOutcome("parent-ui-$suffix",sourceId,child.revision,0,child.text.length,app.antisocial.core.ContextUnitStatus.SIGNAL,listOf(finding)))
        reader.store.processed(record.id,sourceId,child.revision)
        reader.store.publish(record);reader.store.protect(record.id,true)
        reader.store.ingest(record.id,listOf(parent.copy(revision="r2",text="Did the workshop replace the connector?")))
        show(record)
        val before=repo.gateway.protectionStats()
        ui.onNodeWithTag("Context saved view").performClick()
        ui.onNodeWithTag("Context reading list").performScrollToNode(hasTestTag("Context findings toggle"))
        ui.onNodeWithTag("Context findings toggle").performClick()
        ui.onNodeWithTag("Context finding $findingId").performScrollTo()
        evidence("earlier-reply-finding-dark")
        ui.onNodeWithTag("Context parent $findingId 0 open").performScrollTo().performClick()
        ui.waitUntil(10_000) {ui.onAllNodesWithTag("Context local quote",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Context local quote",useUnmergedTree=true).assertTextEquals(parent.text)
        assertEquals(before,repo.gateway.protectionStats());evidence("earlier-reply-local-dark")
    }

}
