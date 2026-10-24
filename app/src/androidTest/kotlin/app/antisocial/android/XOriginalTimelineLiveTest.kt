/* anti social, GPL-3.0-or-later. Explicit live source check on an isolated emulator. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Neither a parser fixture nor an alternate response can pass this check. */
@RunWith(AndroidJUnit4::class)
class XOriginalTimelineLiveTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    @Test fun loggedOutProfilesUseOneGatewayReadEachAndRemainNativeWithPrivacyFailClosed() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveXOriginal")=="true")
        runBlocking {withTimeout(30_000){repo.awaitReady()}}
        val prefs=repo.prefs.export().toString();val policy=repo.gateway.currentPolicy();val fallback=repo.gateway.originalFallback
        val listener=repo.gateway.onNetworkEvent;val observer=repo.gateway.onTextResponse
        val events=CopyOnWriteArrayList<NetworkEvent>();val bodies=CopyOnWriteArrayList<Payload>()
        val rows=mutableListOf<Map<String,Any?>>()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"x-original-timeline").apply {mkdirs()}
        val vm=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        val visiblePost=mutableStateOf<Post?>(null)
        ui.runOnUiThread {
            vm.viewModelScope.cancel()
            ui.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){
                LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).testTag("X original native reader")){item {
                    val post=visiblePost.value
                    if(post==null)Text("Reading public X profile data")else PostCard(post,vm,false){}
                }}
            }}}
        }
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.archiveRecovery=false;repo.prefs.mentionLookups=false
        scheduleRefresh(repo.context,repo.prefs);repo.cancelSearchPreparation()
        repo.gateway.originalFallback=true;repo.gateway.configure(NetworkPolicy())
        repo.gateway.onNetworkEvent={events+=it};repo.gateway.onTextResponse={bodies+=it}
        var nasa:Page?=null;var failure:String?=null
        try {
            for(account in listOf("NASA","SpaceX")) {
                val target=repo.routes.target(Platform.X,account)
                val source=repo.pool.originalSource(Platform.X,Capability.FEED)
                val adapter=OriginalAdapter(source,repo.gateway,repo.routes)
                val started=System.nanoTime();val before=events.count {it.stage=="start"};val responses=bodies.size
                val page=runBlocking(Dispatchers.IO){withTimeout(45_000){adapter.feed(target)}}
                val response=bodies.drop(responses).single {it.finalUrl.substringBefore('?').equals(target.canonicalUrl,true)}
                val requests=events.count {it.stage=="start"}-before
                rows+=mapOf("account" to account,"elapsedMs" to (System.nanoTime()-started)/1_000_000,"requests" to requests,
                    "responseBytes" to response.text.toByteArray().size,"responseSha256" to MessageDigest.getInstance("SHA-256").digest(response.text.toByteArray()).joinToString(""){"%02x".format(it)},
                    "profile" to page.profile,"limitedTimeline" to page.limitedTimeline,"postIds" to page.posts.map {it.id},
                    "dated" to page.posts.count {it.publishedAt!=null},"media" to page.posts.sumOf {it.media.size})
                assertEquals("Profile extraction itself must not make a second network read",1,requests)
                assertTrue(page.posts.isNotEmpty());assertTrue(page.limitedTimeline);assertNull(page.next)
                assertTrue(page.posts.all {it.author.equals(account,true)&&it.publishedAt!=null&&!it.preview&&it.provenance?.source?.role==SourceRole.ORIGINAL})
                assertEquals(target,page.profile?.target)
                assertTrue(FeedRefresh.posts(page,Platform.X).isNotEmpty())
                if(account=="NASA")nasa=page
            }
            val target=repo.routes.target(Platform.X,"NASA");val adapter=OriginalAdapter(repo.pool.originalSource(Platform.X,Capability.FEED),repo.gateway,repo.routes)
            val before=events.count {it.stage=="start"}
            repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
            assertTrue(runBlocking(Dispatchers.IO){runCatching {adapter.feed(target)}.isFailure})
            assertEquals(before,events.count {it.stage=="start"})
            repo.gateway.configure(NetworkPolicy());repo.gateway.originalFallback=false
            assertTrue(runBlocking(Dispatchers.IO){runCatching {adapter.feed(target)}.isFailure})
            assertEquals(before,events.count {it.stage=="start"})
            repo.gateway.originalFallback=true
            // Route request counts are recorded before ordinary card artwork.
            repo.gateway.onNetworkEvent=listener;repo.gateway.onTextResponse=observer
            val page=requireNotNull(nasa);runBlocking {repo.mentionNames.seed(page)}
            val post=page.posts.first();val visible=PostPresentation.content(post).substringBefore("https://").take(24)
            ui.runOnUiThread {visiblePost.value=post}
            ui.mainClock.advanceTimeBy(1000)
            ui.waitUntil(20_000){ui.onAllNodes(hasText(visible,substring=true)).fetchSemanticsNodes().isNotEmpty()}
            ui.onNode(hasText(visible,substring=true)).assertIsDisplayed();ui.waitForIdle()
            ui.onNodeWithTag("X original native reader").performTouchInput {click(Offset(2f,2f))}
            ui.runOnUiThread {ui.activity.window.decorView.requestLayout();ui.activity.window.decorView.invalidate()}
            ui.waitForIdle()
            ui.runOnIdle {assertTrue("Native evidence requires the displayed activity's window",ui.activity.window.decorView.hasWindowFocus())}
            android.os.SystemClock.sleep(300)
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(dir,"public-nasa-native.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }catch(e:Throwable){failure=e.message ?: e.javaClass.simpleName;throw e}
        finally {
            File(dir,"live-result.json").writeText(repo.gson.toJson(mapOf("records" to rows,"failure" to failure,"events" to events)))
            repo.gateway.onNetworkEvent=listener;repo.gateway.onTextResponse=observer
            ui.runOnIdle {ui.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);ui.activity.setContent {Text("X public profile check complete")}}
            repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(prefs))
            repo.applyNetworkSettings();repo.gateway.originalFallback=fallback;repo.gateway.configure(policy);scheduleRefresh(repo.context,repo.prefs)
        }
    }
}
