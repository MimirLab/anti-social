/* anti social, GPL-3.0-or-later. Owned public-search protocol through Gateway. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ThreadsSearchNativeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val suffix=System.nanoTime().toString().takeLast(9)
    private val oldText="Battery replacement instructions include connector dimensions, screw locations and the service manual."
    private val newText="The repair workshop now lends insulated tools and accepts used laptop battery packs for recycling."
    private fun post(code:String,id:String,text:String,time:Long)=mapOf("pk" to id,"id" to "${id}_100","code" to code,
        "user" to mapOf("pk" to "100","id" to "100","username" to "workshop","full_name" to "Public workshop"),
        "caption" to mapOf("text" to text),"taken_at" to time,"media_type" to 19,"text_post_app_info" to mapOf("is_reply" to false))
    private fun body(query:String):String {
        val posts=listOf(post("OldRepair$suffix","884900${suffix}1",oldText,1792170638),post("NewRepair$suffix","884900${suffix}2",newText,1792170640))
        val metadata=mapOf("expectedPreloaders" to listOf(mapOf("actorID" to "0","queryName" to "BarcelonaSearchResultsQuery","queryID" to "1234567890","variables" to mapOf("query" to query,"recent" to 0))))
        val data=mapOf("searchResults" to mapOf("edges" to posts.map {mapOf("node" to mapOf("thread" to mapOf("thread_items" to listOf(mapOf("post" to it)))))}))
        return listOf(metadata,mapOf("__bbox" to mapOf("result" to mapOf("data" to data)))).joinToString(""){"<script type=\"application/json\">${Gson().toJson(it)}</script>"}
    }
    private class Server(private val body:String):Closeable {
        private val socket=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));private val worker=Executors.newSingleThreadExecutor();private val active=AtomicReference<Socket?>()
        val root get()="http://127.0.0.1:${socket.localPort}"
        val requests=CopyOnWriteArrayList<Map<String,String>>()
        init {worker.submit {while(!socket.isClosed) {
            val accepted=try {socket.accept()}catch(e:java.io.IOException){if(socket.isClosed)break else throw e};active.set(accepted)
            try {accepted.use {connection->
                connection.soTimeout=5000;val input=connection.getInputStream().bufferedReader();val line=requireNotNull(input.readLine()).split(' ');val headers=linkedMapOf<String,String>();var ended=false
                repeat(64){if(!ended){val h=requireNotNull(input.readLine());require(h.length<=8192);if(h.isEmpty())ended=true else headers[h.substringBefore(':').lowercase()]=h.substringAfter(':').trim()}};check(ended)
                requests+=mapOf("method" to line[0],"path" to line[1],"cookie" to headers["cookie"].orEmpty(),"authorization" to headers["authorization"].orEmpty())
                val bytes=body.toByteArray();connection.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
            }}finally {active.compareAndSet(accepted,null)}
        }}}
        override fun close(){socket.close();active.getAndSet(null)?.close();worker.shutdownNow();check(worker.awaitTermination(3,TimeUnit.SECONDS))}
    }
    @Test fun oneOrdinarySearchShowsChronologicalNativeCardsWithoutInventingPeopleOrMorePages() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo;runBlocking {repo.awaitReady()}
        val prefs=repo.prefs.export().toString();val query="battery fixture $suffix";val target=repo.routes.search(Platform.THREADS,query)
        val output=File(ui.activity.getExternalFilesDir("validation"),"threads-search-native").apply {mkdirs()};var vm:AntiSocialViewModel?=null
        val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
        val keys=listOf("OldRepair$suffix","NewRepair$suffix").map {ContentIdentity.key(Platform.THREADS,"https://www.threads.com/@workshop/post/$it")}
        var gateway:NetworkGateway?=null
        try {Server(body(query)).use {server->
            gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false,
                rewrittenUrl=if(url.startsWith(Platform.THREADS.origin+"/search?"))server.root+url.removePrefix(Platform.THREADS.origin)else null)},true)
            val reader=SourcePool(gateway!!,repo.routes).sourceReader(Source(Platform.THREADS,"original",server.root,true,SourceRole.ORIGINAL))
            ui.runOnIdle {
                ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
                repo.cancelSearchPreparation();repo.tor.enable(false);repo.gateway.configureTor(NetworkPolicy(tor=true));repo.gateway.configure(NetworkPolicy(tor=true))
                repo.prefs.intervalMinutes=0;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false;Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
                vm=AntiSocialViewModel(repo,createSearch={opened->SearchFeed(opened,discover={reader.fetch(opened)},read={error("Native search posts need no hydration")})})
                ui.activity.setContent {AntiSocialApp(vm!!)};vm!!.open(target,true)
            }
            ui.waitUntil(15_000){vm!!.screen.value.searchFeed?.loading==false}
            val state=vm!!.screen.value.searchFeed!!;assertNull(state.error);assertEquals(2,state.page.posts.size)
            assertEquals(listOf(newText,oldText),state.page.posts.map {it.body});assertTrue(state.page.accounts.isEmpty());assertFalse(state.hasMore)
            ui.onNodeWithText(newText).assertIsDisplayed();ui.onNodeWithTag("Search feed").performScrollToNode(hasText(oldText));ui.onNodeWithText(oldText).assertIsDisplayed()
            assertEquals(1,server.requests.size);assertTrue(server.requests.all {it["method"]=="GET"&&it["cookie"].isNullOrEmpty()&&it["authorization"].isNullOrEmpty()})
            File(output,"results.json").writeText(Gson().toJson(mapOf("requests" to server.requests,"posts" to state.page.posts.map {it.identityKey},"accounts" to state.page.accounts.size,"hasMore" to state.hasMore)))
            val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(output,"native-search.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }}finally {
            ui.runOnIdle {vm?.discussions?.close();vm?.viewModelScope?.cancel();ui.activity.setContent {Text("Public search fixture complete")}}
            runBlocking {
                val pageKey=repo.pageKey(target);repo.dao.media(pageKey)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(pageKey));repo.dao.removePosts(keys)
                for(table in listOf("history","seen"))for(key in keys)repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
            }
            ui.runOnIdle {repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(prefs));repo.mentionNames.changed();repo.applyNetworkSettings()}
            gateway?.client()?.let {it.dispatcher.executorService.shutdownNow();it.connectionPool.evictAll()}
        }
    }
}
