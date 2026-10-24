/* anti social, GPL-3.0-or-later. Public snapshots remain previews, not feed freshness. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Captures are staged privately on the isolated device, never bundled in the APK. */
@RunWith(AndroidJUnit4::class)
class AnonimogramNativeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val args get()=InstrumentationRegistry.getArguments()
    private fun enabled(key:String)=args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString(key)=="true"
    private val source=Source(Platform.INSTAGRAM,"anonimogram-api","https://api.anonimogram.com",true)
    private class Server(private val bodies:Map<String,String>):Closeable {
        private val socket=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        private val executor=Executors.newSingleThreadExecutor()
        val requests=CopyOnWriteArrayList<String>()
        val root get()="http://127.0.0.1:${socket.localPort}"
        init {executor.submit {
            while(!socket.isClosed) {
                val client=try {socket.accept()}catch(e:java.io.IOException){if(socket.isClosed)break else throw e}
                client.use {
                    it.soTimeout=3_000
                    val input=it.getInputStream().bufferedReader()
                    val path=requireNotNull(input.readLine()).split(' ')[1]
                    var ended=false
                    for(i in 0 until 64) {
                        val line=requireNotNull(input.readLine());require(line.length<=8192)
                        if(line.isEmpty()){ended=true;break}
                        assertFalse(line.startsWith("Cookie:",true));assertFalse(line.startsWith("Authorization:",true))
                    }
                    check(ended);requests+=path
                    val body=bodies[path];val bytes=(body ?: "{}").toByteArray()
                    it.getOutputStream().apply {write("HTTP/1.1 ${if(body!=null)200 else 404} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
                }
            }
        }}
        override fun close(){socket.close();executor.shutdownNow();check(executor.awaitTermination(4,TimeUnit.SECONDS))}
    }
    @Test fun capturedProfileAndCaptionPreviewsUseTheNativeFactoryWithoutInventingFeedDates() {
        Assume.assumeTrue(enabled("antisocialCapturedAnonimogram"))
        runBlocking {withTimeout(30_000){repo.awaitReady()}}
        val preferences=repo.prefs.export().toString();val policy=repo.gateway.currentPolicy()
        val vm=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        ui.runOnUiThread {vm.viewModelScope.cancel();ui.activity.setContent {Text("Preparing public reader snapshot")}}
        repo.prefs.intervalMinutes=0;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false
        repo.cancelSearchPreparation();scheduleRefresh(repo.context,repo.prefs)
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        val directory=File(repo.context.noBackupFilesDir,"anonimogram-fixture")
        val bodies=listOf("profile","posts").associate {kind->
            val payload=repo.gson.fromJson(File(directory,"$kind.json").readText(),Payload::class.java)
            assertEquals(200,payload.status)
            "/api/$kind/nasa" to payload.text
        }
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        val output=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"anonimogram").apply {mkdirs()}
        try {
            Server(bodies).use {server->
                val reader=SourcePool(gateway,repo.routes).sourceReader(source.copy(root=server.root))
                val target=repo.routes.target(Platform.INSTAGRAM,"nasa")
                val profile=runBlocking(Dispatchers.IO){withTimeout(10_000){reader.profile(target)}}.profile!!
                assertEquals("NASA",profile.name);assertTrue(profile.displayNameSupplied);assertNull(profile.persistentId)
                val page=runBlocking(Dispatchers.IO){withTimeout(10_000){reader.fetch(target)}}
                assertEquals(12,page.posts.size);assertTrue(page.posts.all {it.preview&&it.publishedAt==null})
                assertEquals(0,page.posts.sumOf {p->p.media.count {it.type=="video"}})
                assertTrue(page.posts.all {it.canonicalUrl.startsWith("https://www.instagram.com/")})
                assertTrue(FeedRefresh.posts(page,Platform.INSTAGRAM).isEmpty());assertTrue(page.limitedTimeline)
                assertFalse(Capability.FEED in reader.capabilities)
                assertTrue(runBlocking {runCatching {reader.feed(target)}.isFailure})
                assertEquals(listOf("/api/profile/nasa","/api/posts/nasa"),server.requests.toList())
                // Explicit UI projection of two separately qualified reads. This
                // does not claim that normal navigation combines the requests.
                ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){
                    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).testTag("Public snapshot native reader")){
                        item {Text(profile.name,style=MaterialTheme.typography.titleLarge);Text(profile.description);PostCard(page.posts.first(),vm,false){}}
                    }
                }}}}
                ui.waitUntil(10_000){ui.onAllNodes(hasText("POV:",substring=true)).fetchSemanticsNodes().isNotEmpty()}
                ui.onNode(hasText("POV:",substring=true)).assertIsDisplayed();ui.waitForIdle()
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(output,"captured-native-preview.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                File(output,"captured-result.json").writeText(repo.gson.toJson(mapOf("requests" to server.requests,"name" to profile.name,"posts" to page.posts.size,"dates" to 0,"playableVideos" to 0,"feedPosts" to 0,"scope" to "Frozen public response through native Gateway/factory; explicit native card projection, not live provider availability.")))
            }
        }finally {
            gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()
            ui.runOnIdle {ui.activity.setContent {Text("Public snapshot check complete")}}
            repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy);scheduleRefresh(repo.context,repo.prefs)
        }
    }
    @Test fun oneLiveAnonymousProfileReadHonorsTorFailClosed():Unit=runBlocking {
        Assume.assumeTrue(enabled("antisocialLiveAnonimogram"))
        withTimeout(30_000){repo.awaitReady()}
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)})
        // Retain the client for disposal before deliberately making transport
        // unavailable. Acquiring it again must correctly fail closed.
        val client=gateway.client()
        val events=CopyOnWriteArrayList<NetworkEvent>();gateway.onNetworkEvent={events+=it}
        val output=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"anonimogram").apply {mkdirs()}
        try {
            val reader=SourcePool(gateway,repo.routes).sourceReader(source)
            val target=repo.routes.target(Platform.INSTAGRAM,"nasa");val start=System.nanoTime()
            val page=withContext(Dispatchers.IO){withTimeout(30_000){reader.profile(target)}}
            val elapsed=(System.nanoTime()-start)/1_000_000
            assertEquals("NASA",page.profile?.name);assertEquals(target,page.profile?.target)
            assertEquals(1,events.count {it.stage=="start"})
            gateway.configure(NetworkPolicy(tor=true,ready=false))
            assertTrue(runCatching {reader.profile(target)}.isFailure)
            assertEquals(1,events.count {it.stage=="start"})
            File(output,"live-result.json").writeText(repo.gson.toJson(mapOf("elapsedMs" to elapsed,"requests" to 1,"profile" to page.profile,"torUnavailableStartedRequests" to 0,"scope" to "One native emulator anonymous public metadata lookup; no provider-wide or phone-performance claim.")))
        }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
    }
}
