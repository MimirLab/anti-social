/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real native clipboard actions; every route validation is a local fixture. */
@RunWith(AndroidJUnit4::class)
class ShareRouteUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var validator: suspend (ShareRouteCandidate)->ShareRouteCheck
    private lateinit var sources: (Post)->List<Source>
    private var oldClipboard: ClipData?=null
    private var cacheBytes: ByteArray?=null
    private val models=mutableListOf<AntiSocialViewModel>()
    private val saved=mutableListOf<Post>()
    private val suffix=System.nanoTime().toString().takeLast(8)
    private val checked=CopyOnWriteArrayList<ShareRouteCandidate>()
    private val now=System.currentTimeMillis()
    private val source by lazy {repo.routes.sources.first {it.platform==Platform.X&&it.frontend=="nitter"&&it.root.startsWith("https://")}}
    private val post by lazy {Post("98${suffix}7",Platform.X,"https://x.com/antisocialshare/status/98${suffix}7","@antisocialshare","A retained complete post for outward link routing.",publishedAt=now,provenance=Provenance(source,now,source.root+"/antisocialshare/status/98${suffix}7"))}
    private val evidenceFile get()=File(ui.activity.cacheDir,"share-route-evidence.json")
    @Before fun preserve() {
        runBlocking {repo.awaitReady()};preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();validator=repo.outgoing.validate;sources=repo.outgoing.discoveredSources
        repo.outgoing.discoveredSources={emptyList()}
        cacheBytes=evidenceFile.takeIf(File::isFile)?.readBytes();repo.outgoing.clear()
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].following()
            ui.activity.setContent {Text("Preparing outbound link fixture")}
            val clipboard=ui.activity.getSystemService(ClipboardManager::class.java);oldClipboard=clipboard.primaryClip;clipboard.setPrimaryClip(ClipData.newPlainText("Fixture","unchanged"))
        }
        repo.prefs.archiveChecks=false;repo.prefs.communityComments=false;repo.prefs.intervalMinutes=0;repo.prefs.tor=true;repo.prefs.originalFallback=true
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
    }
    @After fun restore() {
        ui.runOnIdle {models.forEach {it.discussions.close();it.viewModelScope.cancel()};ui.activity.setContent {Text("Outbound link fixture complete")}}
        runBlocking {saved.forEach {repo.library.remove(repo.key(it))}}
        repo.outgoing.validate=validator;repo.outgoing.discoveredSources=sources;repo.outgoing.clear();cacheBytes?.let {evidenceFile.writeBytes(it)}
        ui.runOnIdle {
            oldClipboard?.let {ui.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(it)}
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy)
        }
    }
    private fun fixture(read: (ShareRouteCandidate)->ShareRouteCheck) {repo.outgoing.validate={candidate->checked+=candidate;read(candidate)}}
    private fun showOriginals(value: Post=post) {
        val model=AntiSocialViewModel(repo).also {models+=it}
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){OriginalPostsSheet(model,listOf(value),value,{})}}}
    }
    private fun copied(): String? {
        var text: String?=null;ui.runOnIdle {text=ui.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()};return text
    }
    private fun clickCopy(value: Post=post) {ui.onNodeWithTag("Collected original list").performScrollToNode(hasTestTag("Copy original ${repo.key(value)}"));ui.onNodeWithTag("Copy original ${repo.key(value)}").performClick()}
    private fun awaitCopied(expected: String) {ui.waitUntil(5000){copied()==expected};assertEquals(expected,copied())}

    @Test fun missingOriginalCopiesRawLiveFrontendAndKeepsOriginalAttribution() {
        fixture {ShareRouteCheck(if(it.kind==ShareRouteKind.ORIGINAL)ShareRouteStatus.NOT_FOUND else ShareRouteStatus.READABLE,it.url)}
        showOriginals();clickCopy()
        val expected=source.root+"/antisocialshare/status/98${suffix}7";awaitCopied(expected)
        ui.onNodeWithTag("Original URL ${repo.key(post)}").assertTextEquals(post.canonicalUrl)
        assertEquals(2,checked.size);assertEquals(post.canonicalUrl,checked.first().url)
    }
    @Test fun readableOriginalStillCopiesTheAuthenticCanonicalUrl() {
        fixture {ShareRouteCheck(ShareRouteStatus.READABLE,it.url)}
        showOriginals();clickCopy();awaitCopied(post.canonicalUrl);assertEquals(1,checked.size)
    }
    @Test fun disabledOriginalAccessDoesNotProbeItAndUsesTheReadableFrontend() {
        repo.prefs.originalFallback=false
        fixture {assertNotEquals(post.canonicalUrl,it.url);ShareRouteCheck(ShareRouteStatus.READABLE,it.url)}
        showOriginals();clickCopy();awaitCopied(source.root+"/antisocialshare/status/98${suffix}7");assertEquals(1,checked.size)
        val noAlternate=post.copy(provenance=null)
        assertTrue(runBlocking {repo.resolveOutgoing(noAlternate,action=ShareLinkAction.OPEN)} is ShareRouteResult.Unavailable)
        assertEquals(1,checked.size)
    }
    @Test fun loginWallPrefersVerifiedFrontendWithoutClaimingDeletion() {
        fixture {ShareRouteCheck(if(it.kind==ShareRouteKind.ORIGINAL)ShareRouteStatus.LOGIN_REQUIRED else ShareRouteStatus.READABLE,it.url)}
        showOriginals();clickCopy();awaitCopied(source.root+"/antisocialshare/status/98${suffix}7")
        assertEquals(2,checked.size)
    }
    @Test fun offlineCopyRetainsAuthenticUrlAsExplicitlyUnverified() {
        val original=post.copy(provenance=null)
        fixture {ShareRouteCheck(ShareRouteStatus.UNVERIFIED)}
        showOriginals(original);clickCopy(original);awaitCopied(post.canonicalUrl)
        val result=runBlocking {repo.resolveOutgoing(original)} as ShareRouteResult.Available
        assertFalse(result.verified);assertTrue(result.notice!!.contains("could not be verified"))
    }
    @Test fun confirmedMissingWithNoDiscoveredCopyDoesNotReplaceTheClipboard() {
        val original=post.copy(provenance=null)
        fixture {ShareRouteCheck(ShareRouteStatus.NOT_FOUND)}
        showOriginals(original);clickCopy(original)
        ui.waitUntil(5000){checked.isNotEmpty()};ui.waitForIdle();assertEquals("unchanged",copied())
        assertTrue(runBlocking {repo.resolveOutgoing(original)} is ShareRouteResult.Unavailable)
    }
    @Test fun savedCopyUsesTheSameLiveShareRouteWithoutChangingItsCapturedOriginal() {
        fixture {ShareRouteCheck(if(it.kind==ShareRouteKind.ORIGINAL)ShareRouteStatus.NOT_FOUND else ShareRouteStatus.READABLE,it.url)}
        runBlocking {repo.library.save(post,emptyList());repo.library.cancel(repo.key(post))};saved+=post
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){LibraryScreen(repo,{})}}}
        ui.waitUntil(5000){ui.onAllNodesWithTag("Library entry ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry ${repo.key(post)}").performClick()
        ui.waitUntil(5000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasTestTag("Copy local original link"))
        ui.onNodeWithTag("Copy local original link").performClick();awaitCopied(source.root+"/antisocialshare/status/98${suffix}7")
        ui.onNodeWithTag("Local original URL").assertTextEquals(post.canonicalUrl)
        val stored=runBlocking {repo.library.copy(repo.key(post))}!!
        assertEquals(post.canonicalUrl,stored.post.canonicalUrl);assertEquals(post.provenance,stored.post.provenance)
    }
}
