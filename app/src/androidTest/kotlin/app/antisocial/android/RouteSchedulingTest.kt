/* AntiSocial, GPL-3.0-or-later. Real native Gateway routing over loopback fixtures. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RouteSchedulingTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private data class Reply(val code:Int=200,val body:String="",val delayBodyMs:Long=0)
    private class Server(private val reply:Reply):Closeable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val client=AtomicReference<Socket?>()
        private val executor=Executors.newSingleThreadExecutor()
        val requests=CopyOnWriteArrayList<Map<String,String>>()
        val root get()="http://127.0.0.1:${socket.localPort}"
        init {executor.submit {
            while(!socket.isClosed) {
                val accepted=try {socket.accept()}catch(e:java.io.IOException){if(socket.isClosed)break else throw e}
                client.set(accepted)
                try {accepted.use {connection->
                    connection.soTimeout=5_000
                    val input=connection.getInputStream().bufferedReader()
                    requireNotNull(input.readLine());val headers=linkedMapOf<String,String>()
                    var ended=false
                    for(i in 0 until 64) {
                        val line=requireNotNull(input.readLine())
                        if(line.isEmpty()){ended=true;break}
                        require(line.length<=8192);headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()
                    }
                    check(ended);requests+=headers
                    val bytes=reply.body.toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 ${reply.code} Fixture\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());flush()
                        if(reply.delayBodyMs>0)Thread.sleep(reply.delayBodyMs)
                        write(bytes);flush()
                    }
                }}catch(_:java.io.IOException){/* A cancelled losing reader closes its connection. */}
                catch(_:InterruptedException){Thread.currentThread().interrupt();break}
                finally {client.compareAndSet(accepted,null)}
            }
        }}
        override fun close() {
            socket.close();client.getAndSet(null)?.close();executor.shutdownNow()
            check(executor.awaitTermination(3,TimeUnit.SECONDS))
        }
    }
    private inner class Fixture(frontends:List<String>,replies:List<Reply>):Closeable {
        val servers=replies.map(::Server)
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {
            override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)
        },true)
        val routes=DarwinRoutes(JsonParser.parseReader(context.assets.open("darwin-routes.json").reader()).asJsonObject.apply {
            add("sources",JsonParser.parseString(servers.mapIndexed {i,s->
                """{"platform":"twitter","frontend":"${frontends[i]}","root":"${s.root}","fallbackOnly":${frontends[i]=="rsshub"}}"""
            }.joinToString(",","[","]")))
            add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes)
        val target=routes.target(Platform.X,"NASA")
        override fun close() {
            gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()
            servers.forEach {it.close()}
        }
    }
    private fun isolated()=assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
    private fun post(text:String)="""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/123"></a><div class="tweet-content">$text</div><span class="tweet-date"><a title="Oct 23, 2026 · 12:00 PM UTC"></a></span></div>"""
    private fun evidence(name:String,values:Map<String,Any>) {
        val directory=File(requireNotNull(context.getExternalFilesDir("validation")),"route-scheduling").apply {mkdirs()}
        File(directory,"$name.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(values))
    }
    @Test fun aStalledNativeReadDoesNotBlockLaterAvailableRoutes():Unit=runBlocking {
        isolated()
        Fixture(List(4){"nitter"},listOf(Reply(503),Reply(body=post("stalled second route"),delayBodyMs=4_000),Reply(503),Reply(body=post("productive fourth route")))).use {f->
            val start=System.nanoTime()
            val page=withTimeout(3_000){f.pool.load(f.target,originalFallback=false)}
            val elapsed=(System.nanoTime()-start)/1_000_000
            assertEquals("productive fourth route",page.posts.single().body)
            assertEquals(1,f.servers[3].requests.size)
            assertTrue(f.pool.results(Platform.X).none {it.source==f.routes.sources[1]})
            evidence("free-slot",mapOf("elapsedMs" to elapsed,"requestsBySource" to f.servers.map {it.requests.size},"loserTrainedAsOutage" to false,"scope" to "Native emulator Gateway over local fixtures, not phone or public-provider latency."))
        }
    }
    @Test fun nativeFeedTriesAnotherMethodBeforeMoreUnprovenMirrors():Unit=runBlocking {
        isolated()
        val rss="""<rss><channel><title>NASA</title><link>https://x.com/NASA</link><item><title>Public flight notice</title><link>https://x.com/NASA/status/123</link><description>Bring the required equipment.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""
        Fixture(listOf("nitter","nitter","nitter","rsshub"),listOf(Reply(503),Reply(503),Reply(503),Reply(body=rss))).use {f->
            val page=f.pool.load(f.target,feed=true,originalFallback=false)
            assertNotNull(page.posts.single().publishedAt);assertFalse(page.posts.single().preview)
            assertEquals(SourceRole.ALTERNATE,page.provenance!!.source.role)
            val counts=f.servers.map {it.requests.size};assertEquals(listOf(2,0,0,1),counts)
            f.servers.flatMap {it.requests}.forEach {assertNull(it["cookie"]);assertNull(it["authorization"])}
            evidence("method-diversity",mapOf("requestsBySource" to counts,"datedPosts" to page.posts.size,"originalFallback" to false))
        }
    }
    @Test fun cancellingTheNativeReadLeavesQueuedSourcesUnused():Unit=runBlocking {
        isolated()
        Fixture(List(4){"nitter"},listOf(Reply(body=post("delayed body"),delayBodyMs=10_000))+List(3){Reply(503)}).use {f->
            val read=async(Dispatchers.IO){f.pool.load(f.target,originalFallback=false)}
            try {withTimeout(2_000){while(f.servers[0].requests.isEmpty())delay(10)}}
            finally {read.cancelAndJoin()}
            withTimeout(2_000){while(f.gateway.client().dispatcher.runningCallsCount()>0)delay(10)}
            val counts=f.servers.map {it.requests.size};assertEquals(listOf(1,0,0,0),counts)
            assertTrue(f.pool.results(Platform.X).isEmpty());assertTrue(f.pool.learning.snapshot().isEmpty())
            evidence("cancellation",mapOf("requestsBySource" to counts,"runningGatewayCallsAfterCancellation" to 0,"trainedOutages" to 0))
        }
    }
}
