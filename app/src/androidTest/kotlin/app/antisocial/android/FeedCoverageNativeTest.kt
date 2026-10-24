/* anti social, GPL-3.0-or-later. Owned native feed acquisition and window checks. */
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FeedCoverageNativeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated()=assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
    private fun evidence(name:String,value:Any) {
        val dir=File(requireNotNull(context.getExternalFilesDir("validation")),"feed-coverage").apply {mkdirs()}
        File(dir,"$name.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(value))
    }
    private class Server:Closeable {
        private val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val active=AtomicReference<Socket?>();private val worker=Executors.newSingleThreadExecutor()
        val requests=AtomicInteger()
        val root get()="http://127.0.0.1:${listener.localPort}"
        init {worker.submit {
            while(!listener.isClosed) {
                val accepted=try {listener.accept()}catch(e:java.io.IOException){if(listener.isClosed)break else throw e};active.set(accepted)
                try {accepted.use {socket->
                    socket.soTimeout=5_000;val reader=socket.getInputStream().bufferedReader();requireNotNull(reader.readLine())
                    var ended=false
                    for(i in 0 until 64){val line=requireNotNull(reader.readLine());require(line.length<=8192);if(line.isEmpty()){ended=true;break}}
                    check(ended);requests.incrementAndGet()
                    val body="<rss><channel><title>NASA</title><link>https://x.com/NASA</link><item><title>Flight notice</title><link>https://x.com/NASA/status/105</link><description>Required equipment and arrival instructions.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>".toByteArray()
                    socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/rss+xml\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
                }}finally {active.compareAndSet(accepted,null)}
            }
        }}
        override fun close(){listener.close();active.getAndSet(null)?.close();worker.shutdownNow();check(worker.awaitTermination(3,TimeUnit.SECONDS))}
    }
    private fun gateway()=NetworkGateway(UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject),object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true).apply {originalFallback=false}
    private fun routes()=DarwinRoutes(JsonParser.parseReader(context.assets.open("darwin-routes.json").reader()).asJsonObject.apply {add("sources",JsonArray());add("searchSources",JsonArray())},UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject))

    @Test fun aNativeRelayWindowKeepsNewPostsButCannotCloseMissingHistory():Unit=runBlocking {
        isolated();Server().use {server->
            val gateway=gateway();val client=gateway.client();val routes=routes();val pool=SourcePool(gateway,routes)
            val target=routes.target(Platform.X,"NASA");val follow=Follow("native-window",target,"NASA",feedUrl=server.root+"/feed")
            var record:Map<String,Any?> = emptyMap()
            try {
                val previous=Post("100",Platform.X,"https://x.com/NASA/status/100","NASA","Previous notice")
                val result=FeedCatchUp().collect(Platform.X,setOf(CrossPosts.originalKey(previous)),first={pool.externalFeed(follow)},next={error("No verified continuation exists")})
                record=mapOf("posts" to result.page.posts.map {it.canonicalUrl},"pendingReason" to result.pendingReason,"requests" to server.requests.get(),"limitedTimeline" to result.page.limitedTimeline)
                assertEquals(1,result.page.posts.size);assertEquals(1,server.requests.get())
                assertNotNull("Missing prior history must remain pending",result.pendingReason)
                assertTrue(result.page.limitedTimeline)
            }finally {evidence("relay-window",record);client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    @Test fun nativeAtomPagingJoinsThePriorWindowWithoutChangingItsSubscription():Unit=runBlocking {
        isolated();val url="https://blog.example/feed.xml";val target=RssSources.target(url)
        fun xml(id:Int,next:Boolean=false)="""<feed xmlns="http://www.w3.org/2005/Atom"><title>Notices</title>${if(next)"<link rel=\"next\" href=\"?page=2\"/>"else ""}<entry><id>urn:notice:$id</id><title>Notice $id</title><content>Required equipment $id</content><published>2026-10-23T12:00:00Z</published></entry></feed>"""
        val first=RssSources.parse(Payload(xml(2,true),url,"application/atom+xml",200),target)
        val old=RssSources.parse(Payload(xml(1),url,"application/atom+xml",200),target).posts.single()
        val reads=mutableListOf<String>()
        val result=FeedCatchUp().collect(Platform.RSS,setOf(CrossPosts.originalKey(old)),first={first},next={page->RssSources.continuePage(target,page){next,_->reads+=next;Payload(xml(1),next,"application/atom+xml",200)}})
        assertEquals(listOf("$url?page=2"),reads);assertEquals(2,result.page.posts.size);assertNull(result.pendingReason)
        assertEquals(target,result.page.profile!!.target)
        evidence("atom-window",mapOf("declaredRequests" to reads,"posts" to result.page.posts.map {it.canonicalUrl},"logicalFeed" to result.page.profile!!.target.canonicalUrl,"scope" to "Native parser/controller with owned bodies; not a live provider request."))
    }
    @Test fun ordinaryFeedContinuationCannotGoDirectWhenSelectedTorIsUnavailable():Unit=runBlocking {
        isolated();val url="https://blog.example/feed.xml";val target=RssSources.target(url)
        val page=RssSources.parse(Payload("""<feed xmlns="http://www.w3.org/2005/Atom"><link rel="next" href="?page=2"/><entry><title>Notice</title></entry></feed>""",url,"application/atom+xml",200),target)
        val gateway=gateway();gateway.configure(NetworkPolicy(tor=true,ready=false));val pool=SourcePool(gateway,routes());val before=gateway.protectionStats()
        val failed=runCatching {pool.continueExternalFeed(Follow("rss-native",target,"Notices",feedUrl=url),page)}
        assertTrue(failed.isFailure);assertEquals(before,gateway.protectionStats())
        evidence("unavailable-tor",mapOf("failure" to failed.exceptionOrNull()?.message,"checksBefore" to before.checked,"checksAfter" to gateway.protectionStats().checked))
    }
    @Test fun nativeBloggerPagingRetainsItsPublicationAcrossAQualifiedProviderRead():Unit=runBlocking {
        isolated();val url="https://publisher.example/feed.xml";val target=RssSources.target(url)
        val marker="tag:blogger.com,1999:blog-123"
        val next="https://www.blogger.com/feeds/123/posts/default?start-index=26&max-results=25"
        fun xml(id:Int,link:String="")="""<feed xmlns="http://www.w3.org/2005/Atom"><id>$marker</id>${if(link.isBlank())""else "<link rel=\"next\" href=\"${link.replace("&","&amp;")}\"/>"}<entry><id>urn:post:$id</id><title>Notice $id</title></entry></feed>"""
        val first=RssSources.parse(Payload(xml(2,next.replace("https:","http:")),url,"application/atom+xml",200),target)
        assertEquals(next,first.next);assertEquals(marker,first.syndicationId)
        val second=RssSources.continuePage(target,first){destination,scope->assertEquals(next,destination);assertEquals(next,scope);Payload(xml(1),destination,"application/atom+xml",200)}
        assertEquals(target,second.profile!!.target);assertEquals(marker,second.syndicationId)
        evidence("blogger-history",mapOf("logicalFeed" to second.profile!!.target.canonicalUrl,"providerPage" to second.provenance!!.finalUrl,"publicationId" to second.syndicationId,"scope" to "Native controller/owned bodies; live source qualification is recorded separately."))
    }
}
