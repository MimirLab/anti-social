/* anti social, GPL-3.0-or-later. Owned protocol drift, fresh reads and native display. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.util.concurrent.atomic.AtomicInteger

class InstagramQueryRecoveryNativeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val code="DeTUk4sDSp-"
    private val media="4004634989362489982"
    private val replacement="12345678901234567"
    private val firstText="The rear entrance remains accessible while the main gate is repaired."
    private val secondText="Bring the registration receipt to collect the replacement equipment."
    private inner class Server:Closeable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val worker=Executors.newSingleThreadExecutor()
        private val active=AtomicReference<Socket?>()
        val requests=CopyOnWriteArrayList<Map<String,String>>()
        val root get()="http://127.0.0.1:${socket.localPort}"
        private val deliveries=AtomicInteger()
        val delivered get()=deliveries.get()
        init {worker.submit {
            while(!socket.isClosed) {
                val accepted=try {socket.accept()}catch(e:java.io.IOException){if(socket.isClosed)break else throw e};active.set(accepted)
                try {accepted.use {connection->
                    connection.soTimeout=5000;val input=connection.getInputStream().bufferedReader()
                    val line=requireNotNull(input.readLine()).split(' ');val headers=linkedMapOf<String,String>();var ended=false
                    repeat(64){if(!ended){val h=requireNotNull(input.readLine());require(h.length<=8192);if(h.isEmpty())ended=true else headers[h.substringBefore(':').lowercase()]=h.substringAfter(':').trim()}}
                    check(ended);val length=headers["content-length"]?.toIntOrNull() ?: 0;require(length in 0..32768)
                    val body=CharArray(length);var read=0;while(read<length){val n=input.read(body,read,length-read);check(n>0);read+=n}
                    val form=("https://fixture.invalid/?"+String(body)).toHttpUrl()
                    val doc=form.queryParameter("doc_id").orEmpty()
                    requests+=mapOf("method" to line[0],"path" to line[1],"document" to doc,"cookie" to headers["cookie"].orEmpty(),"authorization" to headers["authorization"].orEmpty())
                    val bootstrap=line[0]=="GET"
                    val content=if(bootstrap) {
                        check(line[1].trimEnd('/')=="/p/$code")
                        """<script type="application/json">{"p":{"actorID":"0","queryName":"PolarisPostCommentsPaginationQuery","queryID":"$replacement","variables":{"media_id":"$media"}}}</script>"""
                    }else {
                        check(line[0]=="POST"&&line[1]=="/api/graphql")
                        val vars=JsonParser.parseString(form.queryParameter("variables")).asJsonObject
                        check(vars["media_id"].asString==media&&!vars["__relay_internal__pv__PolarisIsLoggedInrelayprovider"].asBoolean)
                        if(doc!=replacement)"""{"errors":[{"message":"The GraphQL document with ID 25516980651312394 was not found."}]}"""
                        else {
                            val delivery=deliveries.incrementAndGet()
                            val node=mapOf("__typename" to "XDTCommentDict","pk" to delivery.toString(),"text" to if(delivery==1)firstText else secondText,
                                "parent_comment_id" to null,"created_at" to 1792800000,"user" to mapOf("username" to "fixture_maintainer","full_name" to "Workshop maintainer","pk" to "99"))
                            GsonBuilder().serializeNulls().create().toJson(mapOf("data" to mapOf("xdt_api__v1__media__media_id__comments__connection" to mapOf("edges" to listOf(mapOf("node" to node)),"page_info" to mapOf("has_next_page" to false,"end_cursor" to null)))))
                        }
                    }
                    val bytes=content.toByteArray();val type=if(bootstrap)"text/html"else "application/json"
                    connection.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
                }}finally {active.compareAndSet(accepted,null)}
            }
        }}
        override fun close(){socket.close();active.getAndSet(null)?.close();worker.shutdownNow();check(worker.awaitTermination(3,TimeUnit.SECONDS))}
    }
    @Test fun recoveredPublicQueryShowsRepliesAndAFreshNativeRetryNeedsOnlyOneRead() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {withTimeout(15_000){repo.awaitReady()}}
        val preferences=repo.prefs.export().toString();var model:AntiSocialViewModel?=null
        val output=File(ui.activity.getExternalFilesDir("validation"),"instagram-query-recovery").apply {mkdirs()}
        val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        val client=gateway.client()
        try {Server().use {server->
            val source=Source(Platform.INSTAGRAM,"instagram-public-graphql",server.root,true,SourceRole.ORIGINAL)
            val adapter=SourcePool(gateway,repo.routes).sourceReader(source)
            val target=repo.routes.recognize("https://www.instagram.com/p/$code/")!!
            val post=Post("post:$code",Platform.INSTAGRAM,target.canonicalUrl,"Public workshop","Public discussion recovery fixture")
            ui.runOnIdle {
                ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
                repo.prefs.intervalMinutes=0;repo.prefs.communityComments=false;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false
                Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
                model=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope,read={_,previous,_->adapter.comments(target,previous?.next)})})
                ui.activity.setContent {MaterialTheme {DiscussionSheet(requireNotNull(model))}}
                model!!.discussions.open(listOf(post),"all")
            }
            fun awaitRead(){ui.waitUntil(15_000){model!!.discussions.state.value?.thread(post)?.let {!it.loading&&(it.page!=null||it.error!=null)}==true};assertNull(model!!.discussions.state.value!!.thread(post)!!.error)}
            awaitRead();ui.onNodeWithText(firstText).assertIsDisplayed();assertEquals(3,server.requests.size)
            val first=model!!.discussions.state.value!!.thread(post)!!.page!!.posts.single().identityKey
            ui.onNodeWithTag("Discussion tab instagram").performClick()
            ui.onNodeWithContentDescription("Refresh Instagram comments").performClick()
            awaitRead();ui.onNodeWithText(secondText).assertIsDisplayed()
            assertNotEquals(first,model!!.discussions.state.value!!.thread(post)!!.page!!.posts.single().identityKey)
            assertEquals(4,server.requests.size);assertEquals(2,server.delivered)
            assertEquals(listOf("POST","GET","POST","POST"),server.requests.map {it["method"]})
            assertTrue(server.requests.all {it["cookie"].isNullOrEmpty()&&it["authorization"].isNullOrEmpty()})
            File(output,"results.json").writeText(Gson().toJson(mapOf("requests" to server.requests,"delivered" to server.delivered,"scope" to "Owned loopback Gateway, actual adapter and native discussion controls; not a public-provider availability claim.")))
            val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output,"native-recovered-comments.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }}finally {
            ui.runOnIdle {model?.discussions?.close();model?.viewModelScope?.cancel();repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences))}
            client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
        }
    }
}
