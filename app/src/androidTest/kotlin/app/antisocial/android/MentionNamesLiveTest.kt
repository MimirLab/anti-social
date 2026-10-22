/* AntiSocial, GPL-3.0-or-later. Explicit public-source Android name-bank smoke test. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Run separately with BOTH -e antisocialLiveMentionNames true and
 * -e antisocialIsolatedFixtureDevice true. An explicitly requested unavailable
 * public source is a failure, never a fabricated local-fixture success. */
@RunWith(AndroidJUnit4::class)
class MentionNamesLiveTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val token="@bsky.app"
    private val canonical="https://bsky.app/profile/bsky.app"
    private val raw="Public updates from @bsky.app should be readable as an ordinary sentence. The account link remains available whenever you need its original identity."
    private var preferences: String?=null
    private var policy: NetworkPolicy?=null
    private var searchPolicy: NetworkPolicy?=null
    private var refresh: FeedRefreshState?=null
    private var heartbeatDevice: (()->HeartbeatDeviceState)?=null
    private var oldEvents: ((NetworkEvent)->Unit)?=null
    private val events=CopyOnWriteArrayList<NetworkEvent>()
    private var captionView: android.view.View?=null
    private var captionCoordinates: LayoutCoordinates?=null
    private var fullAtLayout=""
    private var clippedAtLayout=""
    private val observedAccounts=CopyOnWriteArrayList<MentionAccount>()
    private val observingNames by lazy {object: MentionNameSource by repo.mentionNames {
        override fun observe(accounts: List<MentionAccount>,networkAllowed: Boolean): AutoCloseable {
            observedAccounts.addAll(accounts)
            return repo.mentionNames.observe(accounts,networkAllowed)
        }
    }}
    private val directory get()=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"mention-names-live").apply {mkdirs()}

    @After fun restore() {
        if(preferences==null)return
        ui.runOnIdle {ui.activity.setContent {Text("Public account-name check complete")}}
        runBlocking(Dispatchers.IO){repo.mentionNames.clear()}
        repo.gateway.onNetworkEvent=oldEvents
        heartbeatDevice?.let {repo.routeHeartbeat.deviceState=it}
        refresh?.let {repo.feedRefresh.value=it}
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.restore(JSONObject(preferences!!));repo.applyNetworkSettings();repo.mentionNames.changed()
            policy?.let(repo.gateway::configure);searchPolicy?.let(repo.gateway::configureTor)
            scheduleRefresh(repo.context,repo.prefs)
        }
    }
    private fun show() {
        ui.runOnIdle {ui.activity.setContent {
            MaterialTheme(colorScheme=darkColorScheme()) {Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Text("Readable account names",style=MaterialTheme.typography.headlineSmall)
                    Text("Public account · Bluesky",style=MaterialTheme.typography.labelLarge)
                    captionView=LocalView.current
                    MentionText(raw,repo,Platform.BLUESKY,canonical,modifier=Modifier.testTag("Live readable caption").onGloballyPositioned {
                        captionCoordinates=it;fullAtLayout=it.boundsInWindow(clipBounds=false).toString();clippedAtLayout=it.boundsInWindow().toString()
                    },source=observingNames)
                }
            }}
        }}
    }
    private fun screenshot(name: String) {
        ui.mainClock.advanceTimeBy(1_000);ui.waitForIdle();SystemClock.sleep(200)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val roots=ui.onAllNodes(isRoot(),useUnmergedTree=true)
        File(directory,"$name-semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n"){index->"Root $index\n"+roots[index].printToString()})
    }
    private fun tapName(name: String) {
        val layouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("Live readable caption").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        val layout=layouts.single();val offset=layout.layoutInput.text.text.indexOf(name)
        assertTrue("The resolved public name must be in the native paragraph",offset>=0)
        val center=layout.getBoundingBox(offset+name.length/2).center
        ui.onNodeWithTag("Live readable caption").performTouchInput {click(center)}
    }
    private fun requests()=events.count {it.stage=="start"}
    private fun awaitQuiet()=runBlocking(Dispatchers.IO) {
        withTimeout(30_000) {
            var quietSince=SystemClock.elapsedRealtime()
            while(true) {
                if(repo.pool.hasActiveReads||repo.gateway.hasForegroundRequests)quietSince=SystemClock.elapsedRealtime()
                else if(SystemClock.elapsedRealtime()-quietSince>=750)break
                delay(50)
            }
        }
    }

    @Test fun publicProfileResolvesAfterPriorityContentAndReusesTheLocalBank() {
        val arguments=InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Explicit live case: use -e antisocialLiveMentionNames true",arguments.getString("antisocialLiveMentionNames")=="true")
        assertEquals("This live check clears only the isolated fixture device's ordinary name cache; also pass -e antisocialIsolatedFixtureDevice true","true",arguments.getString("antisocialIsolatedFixtureDevice"))
        runBlocking {withTimeout(30_000){repo.awaitReady()}}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();searchPolicy=repo.gateway.currentPolicy(NetworkRoute.TOR)
        refresh=repo.feedRefresh.value;heartbeatDevice=repo.routeHeartbeat.deviceState;oldEvents=repo.gateway.onNetworkEvent
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.archiveRecovery=false;repo.prefs.historyFullText=false
        repo.prefs.originalFallback=true;repo.prefs.searchProvider=SearchProvider.DARWIN;repo.prefs.tor=false
        repo.prefs.readableMentions=true;repo.prefs.mentionLookups=true
        repo.routeHeartbeat.deviceState={HeartbeatDeviceState(false,false,false)}
        ui.runOnIdle {
            ui.activity.setContent {Text("Preparing the isolated public-name check")}
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].section("settings")
            repo.cancelSearchPreparation();repo.tor.enable(false)
            repo.gateway.originalFallback=true;repo.gateway.configure(NetworkPolicy())
        }
        scheduleRefresh(repo.context,repo.prefs)
        awaitQuiet()
        repo.feedRefresh.value=repo.feedRefresh.value.copy(running=true)
        runBlocking(Dispatchers.IO){repo.mentionNames.clear();repo.mentionNames.changed();repo.mentionNames.awaitReady()}
        repo.gateway.onNetworkEvent={event->events+=event;oldEvents?.invoke(event)}
        val target=requireNotNull(repo.routes.recognize(canonical))
        val account=requireNotNull(MentionNames.account(target,repo.routes))
        assertEquals(Platform.BLUESKY,account.key.platform);assertEquals("bsky.app",account.key.username)
        val before=repo.mentionNames.stats.value
        val started=SystemClock.elapsedRealtime()
        var resolved: String?=null;var priorityRequests=-1;var networkRequests=-1;var cacheRequests=-1
        var failure: String?=null;var cacheImmediate=false
        try {
            show();ui.onNodeWithTag("Live readable caption").assertTextEquals(raw.replace(token,token.removePrefix("@")))
            ui.waitUntil(5_000){repo.mentionNames.requestedCount==1}
            SystemClock.sleep(2_000);ui.onNodeWithTag("Live readable caption").assertTextEquals(raw.replace(token,token.removePrefix("@")))
            priorityRequests=requests();assertEquals("No account request may start while essential content is still refreshing",0,priorityRequests)
            screenshot("01-original-handle-priority-content")
            repo.feedRefresh.value=repo.feedRefresh.value.copy(running=false);repo.mentionNames.changed()
            ui.waitUntil(60_000) {
                resolved=repo.mentionNames.names.value[account.key.id]?.takeIf {it.isNotBlank()}
                resolved?.let {name->ui.onAllNodesWithText(raw.replace(token,name)).fetchSemanticsNodes().isNotEmpty()}==true
            }
            val name=requireNotNull(resolved){"The public source did not expose a validated display name within the bounded lookup"}
            assertEquals(name,MentionNames.sanitizeDisplayName(name,account.key))
            ui.onNodeWithTag("Live readable caption").assertTextEquals(raw.replace(token,name))
            awaitQuiet();networkRequests=requests();assertTrue("The actual production name bank must retrieve a public profile through NetworkGateway",networkRequests>0)
            screenshot("02-public-name-native-paragraph")
            tapName(name)
            ui.onNodeWithTag("Mention account sheet").assertExists()
            ui.onNodeWithTag("Mention public name").assertTextEquals(name)
            ui.onNodeWithTag("Mention original handle").assertTextEquals(token)
            ui.onNodeWithTag("Mention authentic account link").assertTextEquals(account.target.canonicalUrl)
            screenshot("03-original-account-details")
            ui.onNodeWithContentDescription("Close account details").performClick()
            ui.runOnIdle {ui.activity.setContent {Text("Reopening the same visible account")}}
            ui.waitUntil(5_000){repo.mentionNames.requestedCount==0}
            val cacheBaseline=requests();show()
            ui.onNodeWithTag("Live readable caption").assertTextEquals(raw.replace(token,name));cacheImmediate=true
            ui.waitUntil(5_000){repo.mentionNames.requestedCount==1}
            SystemClock.sleep(2_000);cacheRequests=requests()-cacheBaseline
            assertEquals("The repeated account must use its persisted local name rather than issue another profile request",0,cacheRequests)
            assertEquals(name,repo.mentionNames.names.value[account.key.id]);screenshot("04-cached-name-on-reentry")
        }catch(error: Throwable) {
            failure=error.message ?: error.javaClass.simpleName
            runCatching {screenshot("public-name-source-unavailable")};throw error
        }finally {
            val after=repo.mentionNames.stats.value
            val report=JSONObject().put("accountUrl",account.target.canonicalUrl).put("originalHandle",token).put("accountKey",account.key.id)
                .put("resolvedPublicName",resolved).put("priorityRequestCount",priorityRequests).put("retrievalRequestCount",networkRequests)
                .put("cachedReentryRequestCount",cacheRequests).put("cachedNameImmediate",cacheImmediate)
                .put("cacheBefore",JSONObject(repo.gson.toJson(before))).put("cacheAfter",JSONObject(repo.gson.toJson(after)))
                .put("elapsedMs",SystemClock.elapsedRealtime()-started).put("failure",failure ?: JSONObject.NULL)
                .put("gatewayHosts",JSONArray(events.map {it.host}.distinct())).put("torSelected",repo.gateway.currentPolicy().tor)
                .put("visibleDemandCount",repo.mentionNames.requestedCount).put("bankEnabled",repo.mentionNames.enabled.value)
                .put("observedAccounts",JSONArray(observedAccounts.map {it.key.id}.distinct()))
                .put("observedTargets",JSONArray(observedAccounts.map {repo.gson.toJson(it.target)}.distinct()))
                .put("viewWindowFocused",captionView?.hasWindowFocus()).put("viewAttached",captionView?.isAttachedToWindow)
                .put("activityLifecycle",ui.activity.lifecycle.currentState.name)
                .put("fullBoundsAtLayout",fullAtLayout).put("clippedBoundsAtLayout",clippedAtLayout)
            ui.runOnIdle {
                captionCoordinates?.takeIf {it.isAttached}?.let {coordinates->
                    report.put("fullBoundsNow",coordinates.boundsInWindow(clipBounds=false).toString())
                        .put("clippedBoundsNow",coordinates.boundsInWindow().toString())
                }
                val rect=android.graphics.Rect();val location=IntArray(2)
                report.put("viewVisible",captionView?.getLocalVisibleRect(rect)).put("viewLocalRect",rect.toString())
                captionView?.getLocationInWindow(location);report.put("viewWindowLocation",JSONArray(location.toList()))
            }
            val layouts=mutableListOf<TextLayoutResult>()
            ui.onNodeWithTag("Live readable caption").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
            layouts.singleOrNull()?.let {layout->
                val start=layout.layoutInput.text.text.indexOf(token)
                report.put("textLayout",layout.layoutInput.text.text).put("lineCount",layout.lineCount)
                if(start>=0)report.put("mentionGlyphBoxes",JSONArray((start until start+token.length).map {layout.getBoundingBox(it).toString()}))
            }
            File(directory,"public-name-result.json").writeText(report.toString(2))
            File(directory,"gateway-events.json").writeText(repo.gson.toJson(events))
        }
    }
}
