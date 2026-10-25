/* anti social, GPL-3.0-or-later. Owned Relay page through Gateway and native UI. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
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

class ThreadsCommentsNativeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val targetUrl="https://www.threads.com/@workshop/post/RootCodeCase"
    private val firstText="Replacement batteries can be collected at the rear entrance."
    private val nestedText="Bring the laptop model number to select the correct battery."
    private fun post(code:String,id:String,user:String,uid:String,text:String,level:Int=0,parent:String="",parentId:String="")=mapOf(
        "code" to code,"pk" to id,"id" to "${id}_$uid","taken_at" to 1792800000,"media_type" to 19,
        "caption" to mapOf("text" to text),"user" to mapOf("username" to user,"pk" to uid,"full_name" to user.replaceFirstChar(Char::uppercase)),
        "text_post_app_info" to mapOf("is_reply" to (level>0),"reply_level" to level,"direct_reply_count" to 2,
            "reply_to_author" to if(parent.isEmpty())null else mapOf("username" to parent,"id" to parentId)))
    private fun page():String {
        val root=post("RootCodeCase","12345","workshop","100","Public repair workshop this Saturday.")
        val first=post("FirstReplyCase","12346","volunteer","101",firstText,1,"workshop","100")
        val nested=post("NestedReplyCase","12347","visitor","102",nestedText,2,"volunteer","101")
        val unrelated=post("SuggestedCode","99999","unrelated","999","Unrelated recommendation must not appear.")
        val replies=mapOf("edges" to listOf(mapOf("node" to mapOf("posts" to mapOf("edges" to listOf(mapOf("node" to first),mapOf("node" to nested)))))))
        val patch=mapOf("id" to "12345_100","text_post_app_info" to mapOf("direct_replies" to replies))
        return listOf(mapOf("media" to root),mapOf("relatedPosts" to mapOf("threads" to listOf(mapOf("thread_items" to listOf(mapOf("post" to unrelated)))))),mapOf("media" to patch))
            .joinToString(""){"<script type=\"application/json\">"+Gson().toJson(mapOf("__bbox" to mapOf("result" to mapOf("data" to it))))+"</script>"}
    }
    private class Server(private val body:String):Closeable {
        private val socket=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor()
        private val active=AtomicReference<Socket?>()
        val requests=CopyOnWriteArrayList<Map<String,String>>()
        val root get()="http://127.0.0.1:${socket.localPort}"
        init {worker.submit {
            while(!socket.isClosed) {
                val accepted=try {socket.accept()}catch(e:java.io.IOException){if(socket.isClosed)break else throw e};active.set(accepted)
                try {accepted.use {connection->
                    connection.soTimeout=5000;val input=connection.getInputStream().bufferedReader()
                    val line=requireNotNull(input.readLine()).split(' ');val headers=linkedMapOf<String,String>()
                    var ended=false
                    repeat(64){if(!ended){val h=requireNotNull(input.readLine());require(h.length<=8192);if(h.isEmpty())ended=true else headers[h.substringBefore(':').lowercase()]=h.substringAfter(':').trim()}}
                    check(ended);requests+=mapOf("method" to line[0],"path" to line[1],"cookie" to headers["cookie"].orEmpty(),"authorization" to headers["authorization"].orEmpty())
                    val bytes=body.toByteArray()
                    connection.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
                }}finally {active.compareAndSet(accepted,null)}
            }
        }}
        override fun close(){socket.close();active.getAndSet(null)?.close();worker.shutdownNow();check(worker.awaitTermination(3,TimeUnit.SECONDS))}
    }
    @Test fun oneCardTapShowsOnlySelectedRepliesInAllAndThePlatformTab() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {withTimeout(20_000){repo.awaitReady()}}
        val preferences=repo.prefs.export().toString();var model:AntiSocialViewModel?=null
        val output=File(ui.activity.getExternalFilesDir("validation"),"threads-comments").apply {mkdirs()}
        val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        val client=gateway.client()
        try {Server(page()).use {server->
            val source=Source(Platform.THREADS,"original",server.root,true,SourceRole.ORIGINAL)
            val production=SourcePool(gateway,repo.routes).sourceReader(source)
            assertTrue(Capability.COMMENTS in production.capabilities)
            val target=repo.routes.recognize(targetUrl)!!
            // Transport fixture only: production originals use their canonical HTTPS URL.
            // Keep the actual Gateway and production parser/identity checks in this path.
            val read:suspend ()->Page={Extraction.parse(gateway.text(server.root+"/@workshop/post/RootCodeCase",targetUrl),target,source,repo.routes)}
            val root=Post("post:RootCodeCase",Platform.THREADS,targetUrl,"Workshop","Public repair workshop this Saturday.")
            ui.runOnIdle {
                ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
                repo.prefs.intervalMinutes=0;repo.prefs.communityComments=false;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false;repo.prefs.originalFallback=true
                Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
                model=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope,read={_,_,_->read()})})
                ui.activity.setContent {MaterialTheme {CompositionLocalProvider(LocalArchiveChecksPaused provides true) {
                    Column {PostCard(root,model!!,false,onOpen={});DiscussionSheet(model!!)}
                }}}
            }
            ui.onNodeWithTag("Read discussions ${repo.key(root)}").performClick()
            ui.waitUntil(15_000){model!!.discussions.state.value?.thread(root)?.let {!it.loading&&(it.page!=null||it.error!=null)}==true}
            val state=model!!.discussions.state.value!!;val thread=state.thread(root)!!;assertNull(thread.error)
            assertEquals("all",state.selected);val comments=Discussions.comments(root,thread.page!!)
            assertEquals(2,comments.size);assertEquals("post:FirstReplyCase",comments.last().parentId)
            assertEquals(1,server.requests.size);assertEquals("GET",server.requests.single()["method"])
            assertTrue(server.requests.all {it["cookie"].isNullOrEmpty()&&it["authorization"].isNullOrEmpty()})
            ui.onNodeWithText(firstText).assertIsDisplayed()
            ui.onNodeWithTag("Discussion comments").performScrollToNode(hasText(nestedText))
            ui.onNodeWithText(nestedText).assertIsDisplayed()
            ui.onNodeWithText("Unrelated recommendation must not appear.").assertDoesNotExist()
            ui.onNodeWithTag("Discussion tab threads").performClick();ui.waitForIdle()
            ui.onNodeWithText(nestedText).assertIsDisplayed();assertEquals(1,server.requests.size)
            val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output,"native-comments.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            File(output,"results.json").writeText(Gson().toJson(mapOf("comments" to comments.size,"requests" to server.requests,"parent" to comments.last().parentId,"scope" to "Owned Gateway response and actual card/All/Threads controls; not a live-provider availability claim.")))
        }}finally {
            ui.runOnIdle {model?.discussions?.close();model?.viewModelScope?.cancel();repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences))}
            client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
        }
    }
    @Test fun onePublicGalleryKeepsItsFourImagesInTheNativeViewer() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveThreadsGallery")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {withTimeout(20_000){repo.awaitReady()}}
        val preferences=repo.prefs.export().toString();val policy=repo.gateway.currentPolicy();var model:AntiSocialViewModel?=null
        val output=File(ui.activity.getExternalFilesDir("validation"),"threads-gallery").apply {mkdirs()}
        try {
            ui.runOnIdle {
                ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
                repo.prefs.intervalMinutes=0;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false;repo.prefs.tor=false
                repo.applyNetworkSettings();repo.tor.enable(false);repo.cancelSearchPreparation()
                Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
            }
            val source=Source(Platform.THREADS,"original",Platform.THREADS.origin,true,SourceRole.ORIGINAL)
            val target=repo.routes.recognize("https://www.threads.com/@nasa/post/Decc755lYhC")!!
            val page=runBlocking {withTimeout(30_000){repo.pool.sourceReader(source).fetch(target)}}
            val post=page.posts.single {it.id=="post:Decc755lYhC"};assertEquals(4,post.media.size)
            assertTrue(post.media.all {it.type=="image"})
            val first=post.media.first()
            runBlocking {withTimeout(30_000){assertTrue(repo.media(first.url,post.canonicalUrl).isFile)}}
            ui.runOnIdle {
                model=AntiSocialViewModel(repo)
                ui.activity.setContent {MaterialTheme {CompositionLocalProvider(LocalArchiveChecksPaused provides true) {
                    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().testTag("Threads gallery fixture")) {item {PostCard(post,model!!,false,onOpen={})}}
                }}}
            }
            val imageTag="Post image ${repo.key(post)} ${first.url}"
            ui.onNodeWithTag("Threads gallery fixture").performScrollToNode(hasTestTag(imageTag))
            ui.onNodeWithTag(imageTag).performClick();ui.onNodeWithText("1 / 4").assertIsDisplayed()
            val decoded=hasContentDescription(repo.publicText(first.description)) and hasAnyAncestor(isDialog())
            ui.waitUntil(20_000){ui.onAllNodes(decoded).fetchSemanticsNodes().size==1}
            ui.onNode(decoded).assertIsDisplayed();ui.waitForIdle()
            val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output,"native-gallery.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            File(output,"results.json").writeText(Gson().toJson(mapOf("target" to target.canonicalUrl,"images" to post.media.size,"source" to page.provenance?.source,"scope" to "Dated single-post direct API35 public original and native gallery; not paging or live Tor qualification.")))
            ui.onNodeWithContentDescription("Close image").performClick()
        }finally {
            ui.runOnIdle {model?.viewModelScope?.cancel();repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy)}
        }
    }
}
