/* anti social, GPL-3.0-or-later. Owned native header/cursor qualification. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
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
class NitterFeedNativeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated()=assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
    private inner class Fixture(val redirect:Boolean=false,val repeatedRss:Boolean=false):Closeable {
        private val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val active=ConcurrentHashMap.newKeySet<Socket>()
        private val workers=Executors.newCachedThreadPool()
        val paths=Collections.synchronizedList(mutableListOf<String>())
        val root get()="http://127.0.0.1:${listener.localPort}"
        val token="DAA+/=KeepCase"
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true).apply {originalFallback=false}
        private val client=gateway.client()
        val routes=DarwinRoutes(JsonParser.parseReader(context.assets.open("darwin-routes.json").reader()).asJsonObject.apply {
            add("sources",JsonParser.parseString("""[{"platform":"twitter","frontend":"nitter","root":"$root","fallbackOnly":false}]"""));add("searchSources",JsonArray())
        },cleaner)
        val pool=SourcePool(gateway,routes);val target=routes.target(Platform.X,"NASA")
        init {workers.submit {
            while(!listener.isClosed) {
                val socket=try {listener.accept()}catch(e:java.io.IOException){if(listener.isClosed)break else throw e};active+=socket
                workers.submit {try {socket.use {connection->
                    connection.soTimeout=5_000;val reader=connection.getInputStream().bufferedReader()
                    val path=requireNotNull(reader.readLine()).split(' ')[1];var ended=false
                    for(i in 0 until 64){val line=requireNotNull(reader.readLine());require(line.length<=8192);if(line.isEmpty()){ended=true;break}}
                    check(ended);paths+=path
                    val head=path=="/NASA/rss";val cursor=path.startsWith("/NASA/rss?cursor=")
                    val html=repeatedRss&&path=="/NASA"
                    val status=if(cursor&&redirect)"302 Found"else if(head||cursor||html)"200 OK"else "503 Unavailable"
                    val id=if(head||cursor&&repeatedRss)105 else 100
                    val body=(if(html)"""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/100"></a><div class="tweet-content">Earlier public arrival instructions.</div><span class="tweet-date"><a title="Oct 23, 2026 · 12:00 PM UTC"></a></span></div>""" else if(head||cursor)"<rss><channel><title>NASA</title><link>https://x.com/NASA</link><item><title>Notice $id</title><link>https://x.com/NASA/status/$id</link><description>Public arrival instructions $id.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"else "No native HTML route").toByteArray()
                    val extra=if(head||cursor&&repeatedRss)"Min-Id: $token\r\n"else if(cursor&&redirect)"Location: $root/Other/rss?cursor=other\r\n"else ""
                    val type=if(html)"text/html"else "application/rss+xml"
                    connection.getOutputStream().apply {write(("HTTP/1.1 $status\r\nContent-Type: $type\r\n$extra"+"Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray());write(body);flush()}
                }}finally {active-=socket}}
            }
        }}
        suspend fun head()=pool.load(target,feed=true,originalFallback=false)
        suspend fun next(page:Page)=pool.continuePage(target,page,false,Capability.FEED)
        suspend fun collect(known:Set<String> = setOf("twitter:post:100"))=FeedCatchUp(timeoutMillis=8_000).collect(Platform.X,known,first={head()},next={next(it)})
        fun evidence(name:String,result:FeedCatchUpResult?=null) {
            val dir=File(requireNotNull(context.getExternalFilesDir("validation")),"nitter-rss").apply {mkdirs()}
            File(dir,"$name.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "posts" to result?.page?.posts?.map {it.canonicalUrl},"pendingReason" to result?.pendingReason,"pages" to result?.pages,
                "requests" to paths.toList(),"health" to pool.results(Platform.X),
                "scope" to "Native Gateway/parser/collector over owned HTTP fixtures; not public TLS, live Tor or phone performance.")))
        }
        override fun close(){client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();listener.close();active.forEach {runCatching {it.close()}};workers.shutdownNow();check(workers.awaitTermination(3,TimeUnit.SECONDS))}
    }
    @Test fun nativeRssHeaderJoinsThePreviousWindowWithoutAnHtmlRead():Unit=runBlocking {
        isolated();Fixture().use {f->
            val result=f.collect();f.evidence("header-window",result)
            assertNull("Native RSS cursor should bridge the prior reading window",result.pendingReason)
            assertEquals(setOf("post:105","post:100"),result.page.posts.map {it.id}.toSet())
            assertEquals(2,f.paths.size);assertEquals("/NASA/rss",f.paths.first())
            assertTrue(f.paths.last().startsWith("/NASA/rss?cursor="));assertFalse(f.paths.contains("/NASA"))
        }
    }
    @Test fun nativeInitialAndOverlappingWindowsKeepOneRead():Unit=runBlocking {
        isolated();for(known in listOf(emptySet(),setOf("twitter:post:105")))Fixture().use {f->
            val result=f.collect(known);assertNull(result.pendingReason);assertEquals(listOf("/NASA/rss"),f.paths)
            f.evidence(if(known.isEmpty())"initial"else "overlapping",result)
        }
    }
    @Test fun nativeRepeatedRssWindowRecoversThroughTheSameSourcesTimeline():Unit=runBlocking {
        isolated();Fixture(repeatedRss=true).use {f->
            val result=f.collect();f.evidence("repeated-rss-native-recovery",result)
            assertNull(result.pendingReason)
            assertEquals(setOf("post:105","post:100"),result.page.posts.map {it.id}.toSet())
            assertEquals(3,result.pages);assertEquals(3,f.paths.size)
            assertEquals("/NASA/rss",f.paths[0]);assertTrue(f.paths[1].startsWith("/NASA/rss?cursor="))
            assertEquals("/NASA",f.paths[2])
        }
    }
    @Test fun nativeModeRoundTripCannotReplayTheHeaderBookmark():Unit=runBlocking {
        isolated();Fixture().use {f->
            val first=f.head();assertNotNull(first.next)
            f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())
            val result=runCatching {f.next(first)}
            assertTrue(result.exceptionOrNull()is CancellationException);assertEquals(listOf("/NASA/rss"),f.paths)
            assertTrue(f.pool.results(Platform.X).none {it.capability==Capability.TIMELINE});f.evidence("round-trip")
        }
    }
    @Test fun nativeHeaderRedirectCannotFollowAnotherAccount():Unit=runBlocking {
        isolated();Fixture(redirect=true).use {f->
            val result=runCatching {f.next(f.head())}
            assertTrue(result.isFailure);assertEquals(2,f.paths.size)
            assertTrue(f.paths.none {it.startsWith("/Other/")});f.evidence("redirect-rejected")
        }
    }
}
