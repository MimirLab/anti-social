/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose text, links and folded inventories; all account responses are local fixtures. */
@RunWith(AndroidJUnit4::class)
class MentionNamesUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val source=FixtureNames()
    private val text="The repair notes from @battery_lab explain how to replace the laptop battery safely and keep every screw organized."
    private val context="https://x.com/RepairWorkshop/status/1980000000000000001"
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var searchPolicy: NetworkPolicy

    private data class Observation(val accounts: List<MentionAccount>,val networkAllowed: Boolean)
    private class FixtureNames: MentionNameSource {
        override val names=MutableStateFlow<Map<String,String>>(emptyMap())
        override val enabled=MutableStateFlow(true)
        val observations=CopyOnWriteArrayList<Observation>()
        val active=AtomicInteger()
        override fun observe(accounts: List<MentionAccount>,networkAllowed: Boolean): AutoCloseable {
            observations+=Observation(accounts,networkAllowed);active.incrementAndGet()
            var closed=false
            return AutoCloseable {if(!closed){closed=true;active.decrementAndGet()}}
        }
    }
    private fun account(token: String="@battery_lab",platform: Platform=Platform.X,url: String=context)=MentionNames.find(token,platform,url,repo.routes).single()
    private fun show(content: @Composable ()->Unit) {
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {CompositionLocalProvider(LocalMentionNameSource provides source){content()}}}}
    }
    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();searchPolicy=repo.gateway.currentPolicy(NetworkRoute.TOR)
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.historyFullText=false
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].following()
            repo.cancelSearchPreparation();repo.tor.enable(false);repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        }
    }
    @After fun restore() {
        ui.runOnIdle {
            ui.activity.setContent {Text("Readable mentions fixture complete")}
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
            repo.gateway.configure(policy);repo.gateway.configureTor(searchPolicy)
        }
    }
    private fun evidence(name: String) {
        ui.mainClock.advanceTimeBy(1_000);ui.waitForIdle()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"mention-names").apply {mkdirs()}
        android.os.SystemClock.sleep(200) // Let Android present the settled Compose frame.
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val roots=ui.onAllNodes(isRoot(),useUnmergedTree=true)
        File(dir,"$name-semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n"){index->"Root $index\n"+roots[index].printToString()})
    }
    private fun tapSubstring(tag: String,substring: String) {
        val results=mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(results)}
        val layout=results.single();val index=layout.layoutInput.text.text.indexOf(substring)
        assertTrue(index>=0)
        val center=layout.getBoundingBox(index+substring.length/2).center
        ui.onNodeWithTag(tag).performTouchInput {click(center)}
    }

    @Test fun identicalInstagramNamesOpenTheirOwnAccountsInOfflineSavedLabels() {
        val opened=mutableListOf<String>()
        show {CompositionLocalProvider(LocalMentionOpenAccount provides {url->opened+=url}) {
            Column {
                listOf("reader_one","reader_two").forEach {handle->
                    MentionText("Shared display name",repo,Platform.INSTAGRAM,"https://www.instagram.com/p/ABC/c/17/",
                        accountLabel=true,authorAccount=PostAuthorAccounts.instagram(handle,"123"),networkAllowed=false,modifier=Modifier.testTag(handle))
                }
            }
        }}
        listOf("reader_one","reader_two").forEach {handle->
            ui.onNodeWithTag(handle).assertTextEquals("Shared display name")
            tapSubstring(handle,"Shared display name")
            ui.onNodeWithTag("Mention original handle").assertTextEquals("@$handle")
            ui.onNodeWithTag("Mention authentic account link").assertTextEquals("https://www.instagram.com/$handle")
            evidence("instagram-author-$handle")
            ui.onNodeWithTag("Open mentioned account").performClick()
        }
        assertEquals(listOf("https://www.instagram.com/reader_one","https://www.instagram.com/reader_two"),opened)
        assertTrue(source.observations.isNotEmpty());assertTrue(source.observations.none {it.networkAllowed})
    }

    @Test fun unresolvedTextIsImmediateAndNewNamesWaitUntilReadingIsIdle() {
        var idle by mutableStateOf(true)
        val original=text.replace("@battery_lab","battery_lab")
        show {CompositionLocalProvider(LocalMentionUpdatesAllowed provides idle) {MentionText(text,repo,Platform.X,context,modifier=Modifier.testTag("Readable caption"))}}
        ui.onNodeWithTag("Readable caption").assertTextEquals(original)
        ui.waitUntil(5_000){source.observations.isNotEmpty()}
        val key=account().key.id
        ui.runOnIdle {idle=false;source.names.value=mapOf(key to "Battery Repair Collective")}
        ui.waitForIdle();ui.mainClock.advanceTimeBy(1_000);ui.waitForIdle()
        ui.onNodeWithTag("Readable caption").assertTextEquals(original)
        ui.runOnIdle {idle=true}
        ui.waitUntil(5_000){ui.onAllNodesWithText(text.replace("@battery_lab","Battery Repair Collective")).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Readable caption").assertTextEquals(text.replace("@battery_lab","Battery Repair Collective"))
        evidence("readable-caption-after-idle")
    }

    @Test fun cachedNameIsImmediateAndItsLinkRevealsTheOriginalAccountWithoutOpeningTheCard() {
        val mention=account();val opened=mutableListOf<String>();var cardOpens=0
        source.names.value=mapOf(mention.key.id to "Battery Repair Collective")
        show {CompositionLocalProvider(LocalMentionOpenAccount provides {url->opened+=url}) {MentionText(text,repo,Platform.X,context,onOpen={cardOpens++},modifier=Modifier.testTag("Readable caption"))}}
        ui.onNodeWithTag("Readable caption").assertTextEquals(text.replace("@battery_lab","Battery Repair Collective"))
        tapSubstring("Readable caption","Battery Repair Collective")
        ui.onNodeWithTag("Mention account sheet").assertExists()
        ui.onNodeWithTag("Mention public name").assertTextEquals("Battery Repair Collective")
        ui.onNodeWithTag("Mention original handle").assertTextEquals("@battery_lab")
        ui.onNodeWithTag("Mention authentic account link").assertTextEquals("https://x.com/battery_lab")
        assertEquals(0,cardOpens)
        evidence("public-name-account-details")
        ui.onNodeWithTag("Open mentioned account").performClick()
        assertEquals(listOf("https://x.com/battery_lab"),opened)
        ui.onNodeWithTag("Mention account sheet").assertDoesNotExist()
    }

    @Test fun hiddenDetailsDoNotLeaseOrResolveAccountsUntilTheyAreExpanded() {
        val raw="$text\n\n@quiet_helper @another_helper\nhttps://example.org/repair-guide"
        show {Column {PostBodyText(raw,repo,Platform.X,tag="Folded caption",sourceUrl=context)}}
        ui.waitUntil(5_000){source.observations.isNotEmpty()}
        assertEquals(setOf("battery_lab"),source.observations.flatMap {it.accounts}.map {it.key.username}.toSet())
        ui.onNodeWithTag("Folded caption details text").assertDoesNotExist()
        ui.onNodeWithTag("Folded caption details toggle").performClick()
        ui.waitUntil(5_000){source.observations.flatMap {it.accounts}.any {it.key.username=="quiet_helper"}}
        assertEquals(setOf("battery_lab","quiet_helper","another_helper"),source.observations.flatMap {it.accounts}.map {it.key.username}.toSet())
        ui.onNodeWithTag("Folded caption details text").assertTextEquals("quiet_helper another_helper\nhttps://example.org/repair-guide")
        ui.onNodeWithTag("Folded caption details toggle").performClick()
        ui.waitUntil(5_000){source.active.get()==1}
    }

    @Test fun localOnlyPresentationUsesCachedNamesButNeverPermitsAccountTraffic() {
        val mention=account();source.names.value=mapOf(mention.key.id to "Battery Repair Collective")
        show {PostBodyText(text,repo,Platform.X,tag="Offline caption",sourceUrl=context,networkAllowed=false)}
        ui.onNodeWithTag("Offline caption").assertTextEquals(text.replace("@battery_lab","Battery Repair Collective"))
        ui.waitUntil(5_000){source.observations.isNotEmpty()}
        assertTrue(source.observations.all {!it.networkAllowed})
        assertTrue(repo.gateway.currentPolicy().tor)
        assertFalse(repo.gateway.currentPolicy().ready)
        evidence("cached-names-local-only")
    }

    @Test fun disablingReadableNamesRestoresRawTextAndReleasesVisibleDemand() {
        source.names.value=mapOf(account().key.id to "Battery Repair Collective")
        show {MentionText(text,repo,Platform.X,context,modifier=Modifier.testTag("Readable caption"))}
        ui.waitUntil(5_000){source.active.get()==1}
        ui.runOnIdle {source.enabled.value=false}
        ui.onNodeWithTag("Readable caption").assertTextEquals(text)
        ui.waitUntil(5_000){source.active.get()==0}
    }

    @Test fun uncomposingAnAccountReleasesItsDemandInsteadOfKeepingAHiddenLookupAlive() {
        var visible by mutableStateOf(true)
        show {if(visible)MentionText(text,repo,Platform.X,context,modifier=Modifier.testTag("Readable caption"))else Text("Nothing displayed")}
        ui.waitUntil(5_000){source.active.get()==1}
        ui.runOnIdle {visible=false}
        ui.waitUntil(5_000){source.active.get()==0}
        ui.onNodeWithTag("Readable caption").assertDoesNotExist()
    }

    @Test fun aSharedCaptionUsesOneActualSourceEditionAndKeepsTheOtherPlatformsAccountDistinct() {
        val first=Post("1980000000000000001",Platform.X,context,"Workshop",text)
        val second=first.copy(platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/RepairExample/",author="Workshop on Instagram",links=listOf("https://www.instagram.com/battery_lab/"))
        val xMention=account()
        val instagramMention=account(platform=Platform.INSTAGRAM,url=second.canonicalUrl)
        source.names.value=mapOf(xMention.key.id to "Battery Repair Collective",instagramMention.key.id to "Different Instagram Account")
        val fusion=PostFusion(listOf(FusionSegment(text,setOf(Platform.X,Platform.INSTAGRAM),true,setOf(CrossPosts.originalKey(first),CrossPosts.originalKey(second)))))
        show {FusedPostText(fusion,roots=listOf(first,second),repo=repo)}
        ui.onNodeWithTag("Fusion shared text").assertTextEquals(text.replace("@battery_lab","Battery Repair Collective"))
        ui.waitUntil(5_000){source.observations.isNotEmpty()}
        assertEquals(setOf(xMention.key.id),source.observations.flatMap {it.accounts}.map {it.key.id}.toSet())
        tapSubstring("Fusion shared text","Battery Repair Collective")
        ui.onNodeWithTag("Mention original handle").assertTextEquals("@battery_lab")
        ui.onNodeWithTag("Mention authentic account link").assertTextEquals("https://x.com/battery_lab")
        ui.onNodeWithText("Different Instagram Account").assertDoesNotExist()
        evidence("shared-caption-keeps-one-source-edition")
    }

    @Test fun aMentionHiddenBeyondAnEllipsisDoesNotStartALookup() {
        val raw="A long introduction that fills the single visible line with ordinary repair instructions before mentioning @battery_lab."
        show {MentionText(raw,repo,Platform.X,context,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().testTag("Clipped caption"))}
        ui.onNodeWithTag("Clipped caption").assertExists()
        ui.waitForIdle()
        assertTrue(source.observations.isEmpty())
    }
    @Test fun aComposedMentionBelowTheViewportIsOnlyLeasedWhenItBecomesVisible() {
        val scroll=ScrollState(0)
        val raw=("Ordinary repair instructions with no account tags.\n").repeat(30)+"More details from @battery_lab."
        show {Column(Modifier.height(160.dp).verticalScroll(scroll).testTag("Mention viewport")) {MentionText(raw,repo,Platform.X,context,modifier=Modifier.testTag("Long caption"))}}
        ui.waitForIdle()
        assertTrue("Composed text below the clipped viewport is not visible demand",source.observations.isEmpty())
        ui.runOnIdle {scroll.dispatchRawDelta(scroll.maxValue.toFloat())}
        ui.waitUntil(5_000){source.observations.isNotEmpty()}
        assertEquals(setOf("battery_lab"),source.observations.flatMap {it.accounts}.map {it.key.username}.toSet())
    }

    @Test fun aRealDialogPausesUnderlyingAccountDemandAndDefersNameChangesUntilDismissed() {
        var dialog by mutableStateOf(false)
        show {Column {
            MentionText(text,repo,Platform.X,context,modifier=Modifier.testTag("Covered caption"))
            if(dialog)AlertDialog(onDismissRequest={dialog=false},title={Text("Reader dialog")},text={Text("The underlying caption is covered.")},confirmButton={TextButton(onClick={dialog=false},modifier=Modifier.testTag("Dismiss reader dialog")){Text("Done")}})
        }}
        ui.waitUntil(5_000){source.active.get()==1}
        ui.runOnIdle {dialog=true}
        ui.onNodeWithTag("Dismiss reader dialog").assertIsDisplayed()
        ui.waitUntil(5_000){source.active.get()==0}
        ui.runOnIdle {source.names.value=mapOf(account().key.id to "Battery Repair Collective")}
        ui.mainClock.advanceTimeBy(1_000);ui.waitForIdle()
        ui.onNodeWithTag("Covered caption").assertTextEquals(text.replace("@battery_lab","battery_lab"))
        ui.onNodeWithTag("Dismiss reader dialog").performClick()
        ui.waitUntil(5_000){source.active.get()==1}
        ui.waitUntil(5_000){ui.onAllNodesWithText(text.replace("@battery_lab","Battery Repair Collective")).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Covered caption").assertTextEquals(text.replace("@battery_lab","Battery Repair Collective"))
    }

    @Test fun replacingContentInAFocusedWindowLeasesAVisibleSafelyPaddedBlueskyCaption() {
        val raw="Public updates from @bsky.app should be readable as an ordinary sentence. The account link remains available whenever you need its original identity."
        val canonical="https://bsky.app/profile/bsky.app"
        ui.waitUntil(5_000){ui.activity.hasWindowFocus()}
        ui.runOnIdle {ui.activity.setContent {Text("Preparing a replacement reader root")}}
        ui.waitForIdle()
        show {MaterialTheme(colorScheme=darkColorScheme()) {Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("Readable account names",style=MaterialTheme.typography.headlineSmall)
                Text("Public account · Bluesky",style=MaterialTheme.typography.labelLarge)
                MentionText(raw,repo,Platform.BLUESKY,canonical,modifier=Modifier.testTag("Safely padded caption"))
            }
        }}}
        ui.onNodeWithTag("Safely padded caption").assertTextEquals(raw.replace("@bsky.app","bsky.app")).assertIsDisplayed()
        ui.waitUntil(5_000){source.active.get()==1}
        val expected=account("@bsky.app",Platform.BLUESKY,canonical)
        assertEquals(setOf(expected.key.id),source.observations.flatMap {it.accounts}.map {it.key.id}.toSet())
        ui.runOnIdle {source.names.value=mapOf(expected.key.id to "Bluesky")}
        ui.waitUntil(5_000){ui.onAllNodesWithText(raw.replace("@bsky.app","Bluesky")).fetchSemanticsNodes().isNotEmpty()}
        evidence("focused-padded-reader-resolves-names")
    }

    @Test fun productionPostAuthorsReplyColonsAndRepostAttributionUseCachedPublicNames() {
        val canonical="https://x.com/WhiteHouse/status/1900000000000009911"
        val body="R to @WhiteHouse: Read the public workshop notes and repair the laptop safely."
        val post=Post("1900000000000009911",Platform.X,canonical,"@WhiteHouse",body,"RT by @POTUS: $body")
        val whitehouse=account("@WhiteHouse",url=canonical);val president=account("@POTUS",url=canonical)
        source.names.value=mapOf(whitehouse.key.id to "The White House",president.key.id to "President Example")
        val vm=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        show {CompositionLocalProvider(LocalArchiveChecksPaused provides true) {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {PostCard(post,vm,false,onOpen={})}}}
        ui.onNodeWithText("The White House").assertExists()
        ui.onNodeWithTag("Post attribution").assertTextEquals("Reposted by President Example")
        ui.onNodeWithTag("Post body ${repo.key(post)}").assertTextEquals(body.replace("@WhiteHouse","The White House"))
        ui.onNodeWithText("@WhiteHouse").assertDoesNotExist();ui.onNodeWithText("Reposted by @POTUS").assertDoesNotExist()
        evidence("post-author-reply-and-repost-names")
        tapSubstring("Post body ${repo.key(post)}","The White House")
        ui.onNodeWithTag("Mention original handle").assertTextEquals("@WhiteHouse")
        ui.onNodeWithTag("Mention authentic account link").assertTextEquals("https://x.com/WhiteHouse")
    }

    @Test fun unresolvedCrossPlatformMentionsUseClickableIdentifiersWithoutAtOrUserPrefixes() {
        val cases=listOf(
            Triple(Platform.X,"Thanks @battery_lab:","Thanks battery_lab:"),
            Triple(Platform.REDDIT,"Ask u/battery_lab:","Ask battery_lab:"),
            Triple(Platform.FACEBOOK,"Thanks @BatteryWorkshop:","Thanks BatteryWorkshop:"),
            Triple(Platform.MASTODON,"Ask @battery_lab@mastodon.social.","Ask battery_lab (mastodon.social).")
        )
        show {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            cases.forEachIndexed {index,(platform,raw,_)->MentionText(raw,repo,platform,context,explicitProfileLinks=if(platform==Platform.FACEBOOK)listOf("https://www.facebook.com/BatteryWorkshop")else emptyList(),modifier=Modifier.testTag("Notation-free $index"))}
        }}
        cases.forEachIndexed {index,(_,_,expected)->ui.onNodeWithTag("Notation-free $index").assertTextEquals(expected)}
        tapSubstring("Notation-free 2","BatteryWorkshop")
        ui.onNodeWithTag("Mention original handle").assertTextEquals("@BatteryWorkshop")
        ui.onNodeWithTag("Mention authentic account link").assertTextEquals("https://www.facebook.com/BatteryWorkshop")
        ui.onNodeWithText("Public name unavailable. Showing the account identifier.").assertExists()
        evidence("unresolved-account-details-keep-authentic-identity")
    }

    @Test fun offlineAuthorLabelsUseTheSameNameBankForRedditInstagramAndFederatedAccounts() {
        val cases=listOf(
            Triple(Platform.REDDIT,"battery_lab","https://www.reddit.com/r/repair/comments/abc/title/"),
            Triple(Platform.INSTAGRAM,"@battery.lab","https://www.instagram.com/battery.lab/"),
            Triple(Platform.MASTODON,"Garden workshop","https://mastodon.social/@battery_lab/123")
        )
        val matches=cases.map {(platform,raw,url)->MentionNames.label(raw,platform,url,repo.routes).single()}
        source.names.value=matches.mapIndexed {index,mention->mention.key.id to "Public Workshop ${index+1}"}.toMap()
        show {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            cases.forEachIndexed {index,(platform,raw,url)->MentionText(raw,repo,platform,url,accountLabel=true,networkAllowed=false,modifier=Modifier.testTag("Local account label $index"))}
        }}
        cases.indices.forEach {index->ui.onNodeWithTag("Local account label $index").assertTextEquals("Public Workshop ${index+1}")}
        ui.waitUntil(5_000){source.observations.map {it.accounts.single().key.id}.toSet().size==3}
        assertTrue(source.observations.all {!it.networkAllowed})
        evidence("offline-account-labels-across-sites")
    }

    @Test fun productionProfileHeadingAndBiographyUseNamesInsteadOfRawAccountSyntax() {
        val target=repo.routes.recognize("https://www.instagram.com/battery.lab/")!!
        val raw="Public repair notes from @battery.lab: keep every screw organized."
        val mention=account("@battery.lab",Platform.INSTAGRAM,target.canonicalUrl)
        source.names.value=mapOf(mention.key.id to "Battery Workshop")
        val vm=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        ui.runOnIdle {vm.screen.value=ScreenState(Platform.INSTAGRAM,target=target,page=Page(emptyList(),profile=Profile(target,"@battery.lab",raw)))}
        show {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {PageIntro(vm)}}
        ui.onNodeWithText("Battery Workshop").assertExists()
        ui.onNodeWithText(raw.replace("@battery.lab","Battery Workshop")).assertExists()
        ui.onNodeWithText("@battery.lab").assertDoesNotExist()
        evidence("profile-name-and-biography")
    }

    @Test fun anAlreadySuppliedAuthorNameDoesNotStartAnotherProfileRead() {
        show {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            MentionText("Public Repair Workshop",repo,Platform.X,context,accountLabel=true,modifier=Modifier.testTag("Supplied author name"))
            MentionText("More notes from @RepairWorkshop:",repo,Platform.X,context,modifier=Modifier.testTag("Missing inline name"))
        }}
        ui.onNodeWithTag("Supplied author name").assertTextEquals("Public Repair Workshop")
        ui.onNodeWithTag("Missing inline name").assertTextEquals("More notes from RepairWorkshop:")
        ui.waitUntil(5_000){source.observations.any {!it.networkAllowed}&&source.observations.any {it.networkAllowed}}
        assertTrue(source.observations.all {observation->observation.accounts.all {it.key.username=="repairworkshop"}})
    }

    @Test fun actualSavedReaderUsesLocalNamesForTheAuthorBodyAndCapturedReplyWithoutChangingTheCopy() {
        val id="1977"+System.nanoTime().toString().takeLast(10)
        val post=Post(id,Platform.X,"https://x.com/battery_lab/status/$id","@battery_lab",".@battery_lab: These public repair notes explain how to disconnect power and replace the battery safely.","Independent repair notes")
        val reply=post.copy(id=id+"1",canonicalUrl="https://x.com/battery_lab/status/${id}1",title="",body="Ask @battery_lab: the replacement parts list is available.",parentId=id)
        source.names.value=mapOf(account().key.id to "Battery Repair Collective")
        val key=runBlocking {repo.library.save(post,emptyList(),listOf(CapturedThread(post,listOf(reply),capturedAt=System.currentTimeMillis())))}
        try {
            show {LibraryScreen(repo,onOpenOriginal={})}
            ui.waitUntil(10_000){ui.onAllNodesWithTag("Library entry $key").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("Library entry $key").performScrollTo()
            ui.onNodeWithText(post.title,useUnmergedTree=true).performClick()
            ui.waitUntil(10_000){ui.onAllNodesWithTag("Local post body").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("Local post body").assertTextEquals(post.body.removePrefix(".").replace("@battery_lab","Battery Repair Collective"))
            ui.onAllNodesWithText("Battery Repair Collective").onFirst().assertExists()
            evidence("saved-copy-names-with-original-text-preserved")
            val expectedReply=reply.body.replace("@battery_lab","Battery Repair Collective")
            ui.onNodeWithTag("Local copy content").performScrollToNode(hasText(expectedReply))
            ui.onNodeWithText(expectedReply).assertExists()
            assertTrue(source.observations.isNotEmpty());assertTrue(source.observations.all {!it.networkAllowed})
            val copy=runBlocking {repo.library.copy(key)}!!
            assertEquals(post.body,copy.post.body);assertEquals(post.author,copy.post.author)
            assertEquals(reply,copy.details!!.threads.single().replies.single())
        }catch(error: Throwable) {
            evidence("saved-copy-name-failure");throw error
        }finally {
            ui.runOnIdle {ui.activity.setContent {Text("Saved account-name check complete")}}
            runBlocking {repo.library.remove(key);repo.dao.removePosts(listOf(key))}
        }
    }

}
