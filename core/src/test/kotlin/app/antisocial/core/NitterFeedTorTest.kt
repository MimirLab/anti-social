/* anti social, GPL-3.0-or-later. Owned SOCKS observation, not a live Tor exit. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class NitterFeedTorTest {
    @Test fun headerPaginationUsesTheSelectedSocksSessionWithoutValidatorsOrLocalEpoch():Unit=runBlocking {
        ServerSocket(0,2,InetAddress.getLoopbackAddress()).use {socks->
            socks.soTimeout=5_000
            val observed=CompletableFuture<List<Triple<String,String,Map<String,String>>>>()
            val token="DAA+/=KeepCase"
            val worker=Thread {
                try {
                    val requests=mutableListOf<Triple<String,String,Map<String,String>>>()
                    repeat(3){index->socks.accept().use {socket->
                        socket.soTimeout=5_000
                        val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                        check(input.readUnsignedByte()==5);repeat(input.readUnsignedByte()){input.readByte()};output.write(byteArrayOf(5,0));output.flush()
                        check(input.readUnsignedByte()==5&&input.readUnsignedByte()==1);input.readByte();check(input.readUnsignedByte()==3)
                        val host=ByteArray(input.readUnsignedByte()).also {input.readFully(it)}.toString(Charsets.UTF_8);input.readUnsignedShort()
                        output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80));output.flush()
                        val reader=input.bufferedReader();val request=requireNotNull(reader.readLine());val headers=linkedMapOf<String,String>()
                        for(i in 0 until 64){val line=requireNotNull(reader.readLine());if(line.isEmpty())break;check(line.length<=8192);headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                        requests+=Triple(host,request,headers)
                        val id=if(index<2)105 else 100
                        val bytes="<rss><channel><item><title>Flight notice $id</title><link>https://x.com/NASA/status/$id</link><description>Useful public data.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>".toByteArray()
                        val cursor=if(index==0)"Min-Id: $token\r\n"else if(index==1)"Min-Id: fresh-live-cursor\r\n"else ""
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/rss+xml\r\nETag: \"do-not-repeat\"\r\nLast-Modified: Fri, 23 Oct 2026 12:00:00 GMT\r\n$cursor"+"Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray());output.write(bytes);output.flush()
                    }}
                    observed.complete(requests)
                }catch(e:Throwable){observed.completeExceptionally(e)}
            }.apply {start()}
            val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
            val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
            gateway.configure(NetworkPolicy(true,socks.localPort,true));val client=gateway.client()
            val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
            val target=routes.target(Platform.X,"NASA");val source=Source(Platform.X,"nitter","http://feed.invalid")
            val adapter=FrontendAdapter(source,gateway,routes)
            val parses=AtomicInteger()
            suspend fun head()=withTimeout(3_000){gateway.feed("http://feed.invalid/NASA/rss",target,source){payload->parses.incrementAndGet();Extraction.parse(payload,target,source,routes)}}
            try {
                val first=head();val refreshed=head();assertNotNull(refreshed.next);assertNotEquals(first.next,refreshed.next)
                assertEquals(1,parses.get())
                val next=withTimeout(3_000){adapter.fetch(target,refreshed.next)};assertEquals("post:100",next.posts.single().id)
                val requests=observed.get(5,TimeUnit.SECONDS);assertEquals(3,requests.size)
                assertEquals("GET /NASA/rss HTTP/1.1",requests[0].second)
                assertEquals(requests[0].second,requests[1].second)
                val continued=(source.root+requests[2].second.split(' ')[1]).toHttpUrlOrNull()!!
                assertEquals("fresh-live-cursor",continued.queryParameter("cursor"))
                val epoch=gateway.currentSession().feedCursorEpoch
                requests.forEach {(host,line,headers)->
                    assertEquals("feed.invalid",host);assertFalse((line+headers).contains(epoch))
                    assertNull(headers["if-none-match"]);assertNull(headers["if-modified-since"])
                    assertNull(headers["cookie"]);assertNull(headers["authorization"])
                }
            }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();socks.close();worker.join(1_000)}
        }
    }
}
