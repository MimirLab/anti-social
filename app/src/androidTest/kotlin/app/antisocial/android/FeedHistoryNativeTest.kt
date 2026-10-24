/* anti social, GPL-3.0-or-later. Owned native routing and catch-up qualification. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FeedHistoryNativeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated()=assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
    private class Server(val head:Boolean,val joins:Boolean):Closeable {
        private val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val active=ConcurrentHashMap.newKeySet<Socket>()
        private val workers=Executors.newCachedThreadPool()
        val paths=Collections.synchronizedList(mutableListOf<String>())
        val root get()="http://127.0.0.1:${listener.localPort}"
        init {workers.submit {
            while(!listener.isClosed) {
                val socket=try {listener.accept()}catch(e:java.io.IOException){if(listener.isClosed)break else throw e}
                active+=socket
                workers.submit {try {socket.use {connection->
                    connection.soTimeout=5_000;val reader=connection.getInputStream().bufferedReader()
                    val path=requireNotNull(reader.readLine()).split(' ')[1]
                    var ended=false
                    for(i in 0 until 64){val line=requireNotNull(reader.readLine());require(line.length<=8192);if(line.isEmpty()){ended=true;break}}
                    check(ended);paths+=path
                    val body=if(head&&path=="/NASA/rss")"""<rss><channel><title>NASA</title><link>https://x.com/NASA</link><item><title>Flight notice</title><link>https://x.com/NASA/status/105</link><description>The full arrival instructions and required equipment.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate><enclosure url="https://media.example/flight.jpg" type="image/jpeg"/></item></channel></rss>"""
                    else (if(joins)listOf(105,104,100)else listOf(104)).joinToString(""){id->"""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/$id"></a><div class="tweet-content">Shorter notice $id</div><span class="tweet-date"><a title="Oct 23, 2026 · 11:00 AM UTC"></a></span></div>"""}
                    val bytes=body.toByteArray();val type=if(path.endsWith("/rss"))"application/rss+xml"else "text/html"
                    connection.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
                }}finally {active-=socket}}
            }
        }}
        override fun close(){listener.close();active.forEach {runCatching {it.close()}};workers.shutdownNow();check(workers.awaitTermination(3,TimeUnit.SECONDS))}
    }
    private inner class Fixture(count:Int=1,joins:Boolean=true):Closeable {
        val servers=List(count){Server(it==0,joins)}
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true).apply {originalFallback=false}
        val routes=DarwinRoutes(JsonParser.parseReader(context.assets.open("darwin-routes.json").reader()).asJsonObject.apply {
            add("sources",JsonParser.parseString(servers.joinToString(",","[","]"){"""{"platform":"twitter","frontend":"nitter","root":"${it.root}","fallbackOnly":false}"""}));add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes);val target=routes.target(Platform.X,"NASA")
        suspend fun collect(known:Set<String> = setOf("twitter:post:100"))=FeedCatchUp(timeoutMillis=8_000).collect(Platform.X,known,
            first={pool.load(target,feed=true,originalFallback=false)},next={pool.continuePage(target,it,false,Capability.FEED)})
        fun evidence(name:String,result:FeedCatchUpResult) {
            val dir=File(requireNotNull(context.getExternalFilesDir("validation")),"feed-history").apply {mkdirs()}
            File(dir,"$name.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "posts" to result.page.posts.map {it.canonicalUrl},"pendingReason" to result.pendingReason,"pages" to result.pages,
                "requests" to servers.map {it.paths.toList()},"health" to pool.results(Platform.X),
                "scope" to "Native collector, source pool, protected loopback Gateway and actual parsers; no public-source or live-Tor claim.")))
        }
        override fun close(){gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll();servers.forEach {it.close()}}
    }
    @Test fun nativeTimelineBridgesAFiniteHeadAndPreservesItsRicherPost():Unit=runBlocking {
        isolated();Fixture().use {f->
            val result=f.collect();f.evidence("bridged",result)
            assertNull("Native timeline must bridge the retained previous head",result.pendingReason)
            assertEquals(setOf("post:105","post:104","post:100"),result.page.posts.map {it.id}.toSet())
            assertEquals(listOf("/NASA/rss","/NASA"),f.servers.single().paths)
            val first=result.page.posts.first();assertEquals("The full arrival instructions and required equipment.",first.body)
            assertEquals("https://media.example/flight.jpg",first.media.single().url)
        }
    }
    @Test fun nativeInitialAndOverlappingReadsDoNotFetchHistory():Unit=runBlocking {
        isolated();for(known in listOf(emptySet(),setOf("twitter:post:105")))Fixture().use {f->
            val result=f.collect(known);assertNull(result.pendingReason)
            assertEquals(listOf("/NASA/rss"),f.servers.single().paths)
            f.evidence(if(known.isEmpty())"initial"else "overlapping",result)
        }
    }
    @Test fun nativeRecoveryStopsAfterThreeAdditionalRouteStartsAndStaysPending():Unit=runBlocking {
        isolated();Fixture(6,joins=false).use {f->
            val result=f.collect();f.evidence("bounded-gap",result)
            assertNotNull(result.pendingReason);assertEquals(4,result.pages)
            assertEquals(4,f.servers.sumOf {it.paths.size});assertEquals(0,f.servers.drop(3).sumOf {it.paths.size})
            assertTrue(result.page.posts.any {it.id=="post:105"&&it.media.size==1})
        }
    }
}
