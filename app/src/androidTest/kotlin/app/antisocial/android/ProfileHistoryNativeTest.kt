/* anti social, GPL-3.0-or-later. Owned native history/cache qualification. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProfileHistoryNativeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val args get()=InstrumentationRegistry.getArguments()
    private fun isolated()=Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true")
    private val output get()=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"profile-history").apply {mkdirs()}
    @Test fun nativeLoadMoreButtonKeepsWorkingAfterTheLiveRouteHandoff() {
        isolated();Assume.assumeTrue(args.getString("antisocialLivePublicPaging")=="true")
        runBlocking {withTimeout(30_000){repo.awaitReady()}}
        val preferences=repo.prefs.export().toString();val policy=repo.gateway.currentPolicy()
        val vm=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        val target=repo.routes.target(Platform.INSTAGRAM,"nasa");val rows=mutableListOf<Map<String,Any?>>()
        try {
            ui.runOnIdle {
                repo.prefs.intervalMinutes=0;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false
                repo.prefs.tor=false;repo.prefs.originalFallback=true;repo.prefs.searchProvider=SearchProvider.DARWIN
                repo.applyNetworkSettings();repo.tor.enable(false);repo.cancelSearchPreparation();scheduleRefresh(repo.context,repo.prefs)
                vm.platform(Platform.INSTAGRAM,false);vm.open(target,true)
            }
            ui.waitUntil(90_000){vm.screen.value.target==target&&!vm.screen.value.loading}
            assertNull(vm.screen.value.error)
            fun record(step:String) {val page=requireNotNull(vm.screen.value.page);rows+=mapOf("step" to step,"posts" to page.posts.size,"source" to page.provenance?.source,"nextSource" to page.continuationSource,"next" to (page.next!=null))}
            record("head")
            repeat(2) {index->
                val before=requireNotNull(vm.screen.value.page).posts.size
                ui.onNodeWithTag("Instagram profile grid").performScrollToNode(hasText("Load more"))
                ui.onNodeWithText("Load more").performClick()
                ui.waitUntil(45_000){!vm.screen.value.loading}
                assertNull(vm.screen.value.error);assertTrue(requireNotNull(vm.screen.value.page).posts.size>before)
                record("button-$index")
            }
            ui.onNodeWithTag("Instagram profile grid").performScrollToIndex(0);ui.waitForIdle()
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output,"live-native-grid.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }finally {
            File(output,"live-native-buttons.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
            ui.runOnIdle {vm.following();repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy);scheduleRefresh(repo.context,repo.prefs)}
        }
    }
    private class Server(private val rss:String,private val pages:Map<String?,String>):Closeable {
        private val socket=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        private val executor=Executors.newSingleThreadExecutor()
        val requests=CopyOnWriteArrayList<Map<String,String?>>()
        val root get()="http://127.0.0.1:${socket.localPort}"
        init {executor.submit {
            while(!socket.isClosed) {
                val client=try {socket.accept()}catch(e:java.io.IOException){if(socket.isClosed)break else throw e}
                client.use {
                    it.soTimeout=5_000;val reader=it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val first=requireNotNull(reader.readLine()).split(' ');var length=0;var ended=false
                    repeat(64) {
                        if(!ended){val line=requireNotNull(reader.readLine());require(line.length<=8192)
                            if(line.isEmpty())ended=true
                            else {assertFalse(line.startsWith("Cookie:",true));assertFalse(line.startsWith("Authorization:",true))
                                if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                        }
                    }
                    require(ended&&length in 0..1024*1024)
                    val data=CharArray(length);var read=0
                    while(read<length){val n=reader.read(data,read,length-read);require(n>0);read+=n}
                    val fields=("https://fixture.example/?"+String(data)).toHttpUrl()
                    val after=fields.queryParameter("variables")?.let {JsonParser.parseString(it).asJsonObject.get("after")?.asString}
                    requests+=mapOf("method" to first[0],"path" to first[1],"after" to after)
                    val body=if(first[0]=="GET"&&first[1].startsWith("/feed/"))rss else if(first[0]=="POST"&&first[1]=="/api/graphql")pages[after]else null
                    val bytes=(body ?: "{}").toByteArray()
                    it.getOutputStream().apply {write("HTTP/1.1 ${if(body!=null)200 else 404} Fixture\r\nContent-Length: ${bytes.size}\r\nContent-Type: ${if(body?.startsWith('<')==true)"application/rss+xml"else "application/json"}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
                }
            }
        }}
        override fun close(){socket.close();executor.shutdownNow();check(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun finiteNativeSnapshotContinuesAcrossRoutesAndTheCacheKeepsItsNavigationOwner()=runBlocking {
        isolated();withTimeout(30_000){repo.awaitReady()}
        val preferences=repo.prefs.export().toString();val policy=repo.gateway.currentPolicy()
        val vm=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        ui.runOnUiThread {vm.viewModelScope.cancel();ui.activity.setContent {Text("Preparing profile history")}}
        repo.prefs.intervalMinutes=0;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false
        repo.cancelSearchPreparation();scheduleRefresh(repo.context,repo.prefs);repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        val suffix=System.nanoTime().toString(36).takeLast(8);val handle="history_fixture_$suffix"
        val codes=(1..4).map {"Hist$suffix$it"}
        val target=repo.routes.target(Platform.INSTAGRAM,handle)
        fun grid(items:List<String>,next:String?,more:Boolean)=Gson().toJson(mapOf("data" to mapOf("xig_user_by_username" to mapOf("pk" to "123","polaris_ordered_timeline_connection" to mapOf(
            "edges" to items.map {mapOf("node" to mapOf("__typename" to "XIGPolarisImageMedia","__isXIGPolarisMedia" to "XIGPolarisImageMedia","code" to it,"user" to mapOf("username" to handle),"caption" to mapOf("text" to "Earlier workshop notice $it")))},
            "page_info" to mapOf("has_next_page" to more,"end_cursor" to next))))))
        val rss="<rss><channel><title>Workshop</title><link>${target.canonicalUrl}</link>"+codes.take(2).joinToString("") {"<item><link>https://www.instagram.com/p/$it/</link><description>Complete workshop notice $it</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item>"}+"</channel></rss>"
        val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        val client=gateway.client();var stored=emptyList<String>()
        try {Server(rss,mapOf(null to grid(codes.take(2),"one",true),"one" to grid(listOf(codes[2]),"two",true),"two" to grid(listOf(codes[3]),null,false))).use {server->
            val routes=DarwinRoutes(repo.context.assets.open("darwin-routes.json").bufferedReader().use {JsonParser.parseReader(it).asJsonObject}.apply {
                add("sources",JsonParser.parseString("""[{"platform":"instagram","frontend":"rsshub","root":"${server.root}/feed","fallbackOnly":false},{"platform":"instagram","frontend":"instagram-public-graphql","root":"${server.root}","fallbackOnly":true}]"""));add("searchSources",JsonArray())
            },cleaner)
            val pool=SourcePool(gateway,routes)
            val headSource=routes.sources.first()
            pool.restore(listOf(ProbeResult(headSource,Capability.TIMELINE,HealthStatus.HEALTHY,1,System.currentTimeMillis(),false)))
            val first=pool.load(target,originalFallback=false);assertEquals(1,server.requests.size);assertNotNull(first.next)
            assertEquals(2,FeedRefresh.posts(first,Platform.INSTAGRAM).size)
            val extra=pool.continuePage(target,first,false);val merged=PageContinuation.merge(first,extra)
            assertEquals(3,merged.posts.size);assertEquals(headSource,merged.provenance!!.source)
            assertEquals("instagram-public-graphql",merged.continuationSource!!.frontend)
            stored=merged.posts.map {repo.key(it)};repo.store(merged);repo.rememberPage(target,merged)
            val cached=requireNotNull(repo.cachedPage(target));assertEquals(merged.next,cached.next);assertEquals(merged.continuationSource,cached.continuationSource);assertTrue(cached.limitedTimeline)
            val last=pool.continuePage(target,cached,false);val complete=PageContinuation.merge(cached,last)
            assertEquals(4,complete.posts.size);assertNull(complete.next)
            // Reusing saved cards must not certify a fresh subscription read.
            assertEquals(0,FeedRefresh.posts(complete,Platform.INSTAGRAM).size)
            assertEquals(listOf(null,null,"one","two"),server.requests.map {it["after"]})
            ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){
                LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)){items(complete.posts.size){index->PostCard(complete.posts[index],vm,false){}}}
            }}}}
            ui.waitUntil(10_000){ui.onAllNodes(hasText("Complete workshop notice",substring=true)).fetchSemanticsNodes().isNotEmpty()};ui.waitForIdle()
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output,"owned-native-history.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            File(output,"owned-result.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf("requests" to server.requests,"posts" to complete.posts.size,"initialRequests" to 1,"cachedNavigationOwner" to cached.continuationSource,"feedPosts" to FeedRefresh.posts(complete,Platform.INSTAGRAM).size,"scope" to "Owned loopback Gateway and actual adapters, native cache and cards; no live-provider claim.")))
        }}finally {
            client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
            repo.dao.removePosts(stored)
            val pageKey=repo.pageKey(target);repo.dao.media(pageKey)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(pageKey))
            ui.runOnIdle {ui.activity.setContent {Text("Profile history check complete")}}
            repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy);scheduleRefresh(repo.context,repo.prefs)
        }
    }
}
