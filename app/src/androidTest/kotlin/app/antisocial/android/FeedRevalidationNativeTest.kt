/* anti social, GPL-3.0-or-later. Native Gateway/SourcePool over owned HTTP feeds. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FeedRevalidationNativeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Server:Closeable {
        private val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val client=AtomicReference<Socket?>()
        private val worker=Executors.newSingleThreadExecutor()
        val fail=AtomicBoolean()
        val requests=CopyOnWriteArrayList<Map<String,String>>()
        val sentBodies=CopyOnWriteArrayList<Int>()
        val root get()="http://127.0.0.1:${listener.localPort}"
        private val body="<rss><channel><title>Fixture NASA</title><link>https://x.com/NASA</link><item><title>Flight notice</title><link>https://x.com/NASA/status/123</link><description>Bring the required equipment. ${"A useful published detail. ".repeat(200)}</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"
        init {worker.submit {
            while(!listener.isClosed) {
                val accepted=try {listener.accept()}catch(e:java.io.IOException){if(listener.isClosed)break else throw e};client.set(accepted)
                try {accepted.use {socket->
                    socket.soTimeout=5_000;val input=socket.getInputStream().bufferedReader();requireNotNull(input.readLine())
                    val headers=linkedMapOf<String,String>();var ended=false
                    for(i in 0 until 64){val line=requireNotNull(input.readLine());if(line.isEmpty()){ended=true;break};require(line.length<=8192);headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()};check(ended)
                    requests+=headers
                    val status=when {fail.get()->503;headers["if-none-match"]=="\"native-feed-one\""->304;else->200}
                    val bytes=if(status==200)body.toByteArray()else ByteArray(0);sentBodies+=bytes.size
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Fixture\r\nContent-Type: application/rss+xml\r\nETag: \"native-feed-one\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()
                    }
                }}catch(_:java.io.IOException){/* Owned cancellation can close a losing call. */}
                finally {client.compareAndSet(accepted,null)}
            }
        }}
        override fun close(){listener.close();client.getAndSet(null)?.close();worker.shutdownNow();check(worker.awaitTermination(3,TimeUnit.SECONDS))}
    }
    private inner class Fixture:Closeable {
        val server=Server()
        private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        private val client=gateway.client()
        val routes=DarwinRoutes(JsonParser.parseReader(context.assets.open("darwin-routes.json").reader()).asJsonObject.apply {add("sources",JsonArray());add("searchSources",JsonArray())},cleaner)
        val pool=SourcePool(gateway,routes)
        val target:Target=routes.target(Platform.X,"NASA")
        val follow=Follow("native-feed-fixture",target,"Fixture NASA",feedUrl=server.root+"/feed")
        override fun close(){client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();server.close()}
    }
    private fun isolated()=assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
    private fun evidence(name:String,record:Map<String,Any?>) {
        val dir=File(requireNotNull(context.getExternalFilesDir("validation")),"feed-revalidation").apply {mkdirs()}
        File(dir,"$name.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(record))
    }
    @Test fun unchangedNativeFeedsUse304AndKeepTheirReadableCurrentPosts():Unit=runBlocking {
        isolated()
        Fixture().use {f->
            var error:String?=null
            try {
                val first=f.pool.externalFeed(f.follow);val next=f.pool.externalFeed(f.follow)
                assertEquals(first.posts.map {it.body},next.posts.map {it.body});assertNull(FeedRefresh.pendingReason(next,Platform.X));assertFalse(next.provenance!!.cached)
                assertEquals("\"native-feed-one\"",f.server.requests[1]["if-none-match"])
                assertEquals(2,f.server.requests.size);assertTrue(f.server.sentBodies[0]>0);assertEquals(0,f.server.sentBodies[1])
                f.server.requests.forEach {assertNull(it["cookie"]);assertNull(it["authorization"])}
            }catch(e:Throwable){error=e.message;throw e}
            finally {evidence("conditional-native",mapOf("error" to error,"requests" to f.server.requests,"decodedBodyBytes" to f.server.sentBodies,"scope" to "Owned native HTTP/source-pool fixture; not a live provider or phone benchmark."))}
        }
    }
    @Test fun clearingNativeFeedMemoryRequiresAFullReadAndFailuresStayPending():Unit=runBlocking {
        isolated()
        Fixture().use {f->
            f.pool.externalFeed(f.follow);f.gateway.clearFeedMemory();f.pool.externalFeed(f.follow)
            assertNull(f.server.requests[1]["if-none-match"]);assertTrue(f.server.sentBodies[1]>0)
            f.server.fail.set(true)
            val failed=runCatching {f.pool.externalFeed(f.follow)}
            assertTrue(failed.exceptionOrNull() is SourceFailure);assertEquals(HealthStatus.UNAVAILABLE,(failed.exceptionOrNull() as SourceFailure).status)
            assertEquals(3,f.server.requests.size)
            evidence("clear-and-failure",mapOf("requests" to f.server.requests,"decodedBodyBytes" to f.server.sentBodies,"failure" to failed.exceptionOrNull()?.message,"scope" to "A failed network refresh is not substituted with the retained page."))
        }
    }
}
