/* anti social, GPL-3.0-or-later. Owned SOCKS transport, not a live Tor exit. */
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

class SyndicationTorTest {
    @Test fun torDownloadsTheBodyEachTimeWithoutReplayingValidatorsButCanReuseParsing():Unit=runBlocking {
        ServerSocket(0,2,InetAddress.getLoopbackAddress()).use {socks->
            socks.soTimeout=5_000
            val seen=CompletableFuture<List<Pair<String,Map<String,String>>>>()
            val body="<rss><channel><item><title>Flight notice</title><link>https://x.com/NASA/status/123</link><description>Useful public data.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"
            val worker=Thread {
                try {
                    val requests=mutableListOf<Pair<String,Map<String,String>>>()
                    repeat(2){socks.accept().use {socket->
                        socket.soTimeout=5_000
                        val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                        check(input.readUnsignedByte()==5);repeat(input.readUnsignedByte()){input.readByte()};output.write(byteArrayOf(5,0));output.flush()
                        check(input.readUnsignedByte()==5&&input.readUnsignedByte()==1);input.readByte();check(input.readUnsignedByte()==3)
                        val host=ByteArray(input.readUnsignedByte()).also {input.readFully(it)}.toString(Charsets.UTF_8);input.readUnsignedShort()
                        output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80));output.flush()
                        val reader=input.bufferedReader();check(reader.readLine().startsWith("GET /feed "));val headers=linkedMapOf<String,String>()
                        for(i in 0 until 64){val line=requireNotNull(reader.readLine());if(line.isEmpty())break;check(line.length<=8192);headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                        requests+=host to headers
                        val bytes=body.toByteArray()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/rss+xml\r\nETag: \"do-not-repeat\"\r\nLast-Modified: Fri, 23 Oct 2026 12:00:00 GMT\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray());output.write(bytes);output.flush()
                    }}
                    seen.complete(requests)
                }catch(e:Throwable){seen.completeExceptionally(e)}
            }.apply {start()}
            val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
            val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
            gateway.configure(NetworkPolicy(true,socks.localPort,true));val client=gateway.client()
            val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
            val target=routes.target(Platform.X,"NASA");val source=Source(Platform.X,"rss","http://feed.invalid");val parses=AtomicInteger()
            try {
                repeat(2){val page=withTimeout(3_000){gateway.feed("http://feed.invalid/feed",target,source){payload->parses.incrementAndGet();Extraction.parse(payload,target,source,routes)}};assertNull(FeedRefresh.pendingReason(page,Platform.X))}
                val requests=seen.get(5,TimeUnit.SECONDS);assertEquals(2,requests.size);assertEquals(1,parses.get())
                requests.forEach {(host,headers)->assertEquals("feed.invalid",host);assertNull(headers["if-none-match"]);assertNull(headers["if-modified-since"]);assertNull(headers["cookie"]);assertNull(headers["authorization"])}
            }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();socks.close();worker.join(1_000)}
        }
    }
}
