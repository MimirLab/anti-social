/* anti social, GPL-3.0-or-later. Owned SOCKS/HTTP fixture, never public traffic. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class LocalIndexNativeFixture(private val publisher:String,private val fourget:Boolean=false,private val duck:Boolean=false):Closeable {
    private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    private val acceptor=Executors.newSingleThreadExecutor();private val workers=Executors.newFixedThreadPool(4)
    private val connections=ConcurrentHashMap.newKeySet<Socket>()
    val requests=CopyOnWriteArrayList<String>();val release=CountDownLatch(1)
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
    val routes=DarwinRoutes(JsonParser.parseString("""{"sources":[],"services":{},"searchSources":[{"platform":"twitter","frontend":"librey","root":"http://first-index.fixture.invalid","jsonApi":true,"fallbackOnly":true}${if(fourget)""else ",{\"platform\":\"twitter\",\"frontend\":\"librey\",\"root\":\"http://quick-index.fixture.invalid\",\"jsonApi\":true,\"fallbackOnly\":true}"}],"publisherSearchSources":${if(fourget)"[{\"frontend\":\"4get\",\"root\":\"http://quick-index.fixture.invalid\"}]"else "[]"}}""").asJsonObject,cleaner)
    val engine=LocalWebDiscovery(gateway,routes)
    val duckSource=Source(Platform.X,"duckduckgo","http://first-index.fixture.invalid",true,SourceRole.ORIGINAL)
    private val client:okhttp3.OkHttpClient
    init {
        if(duck)gateway.configure(NetworkPolicy(true,socket.localPort,true))else gateway.configureTor(NetworkPolicy(true,socket.localPort,true))
        client=gateway.client(if(duck)NetworkRoute.DEFAULT else NetworkRoute.TOR)
        acceptor.submit {
            while(!socket.isClosed) {
                val connection=try {socket.accept()}catch(_:java.io.IOException){break}
                connections+=connection
                workers.submit {try {connection.use {c->
                    c.soTimeout=5000;val input=DataInputStream(c.getInputStream());val out=c.getOutputStream()
                    check(input.readUnsignedByte()==5);repeat(input.readUnsignedByte()){input.readByte()};out.write(byteArrayOf(5,0));out.flush()
                    check(input.readUnsignedByte()==5&&input.readUnsignedByte()==1);input.readByte();check(input.readUnsignedByte()==3)
                    val host=ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8);input.readUnsignedShort()
                    out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80));out.flush()
                    val reader=input.bufferedReader();val request=requireNotNull(reader.readLine());require(request.length<=8192)
                    var ended=false
                    for(i in 0 until 64) {val line=requireNotNull(reader.readLine());require(line.length<=8192)
                        if(line.isEmpty()){ended=true;break}
                        check(!line.startsWith("Cookie:",true)&&!line.startsWith("Authorization:",true))
                    };check(ended);requests+=host+request.substringAfter(' ').substringBefore(' ')
                    val body=when(host) {
                        "first-index.fixture.invalid"->if(duck&&request.substringAfter(' ').startsWith("/lite/"))"""<table><tr><td><a class="result-link" href="https://x.com/FixtureWorkshop/status/123">Workshop announcement</a></td></tr></table>"""
                            else {check(release.await(if(duck)12 else 8,TimeUnit.SECONDS));"[]"}
                        "quick-index.fixture.invalid"->if(fourget)"""{"status":"ok","web":[{"title":"Public workshop","url":"https://publisher.fixture.invalid/calendar"}],"answer":[{"url":"https://ignored.fixture.invalid/"}],"npt":"do-not-consume"}"""
                            else """{"0":{"title":"Public workshop","url":"https://publisher.fixture.invalid/calendar"},"results_source":"upstream.fixture.invalid"}"""
                        "publisher.fixture.invalid"->publisher
                        else->error("Unexpected fixture destination")
                    }.toByteArray()
                    out.write("HTTP/1.1 200 Fixture\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(body);out.flush()
                }}catch(_:java.io.IOException){/* Losing reads close their own sockets. */}
                catch(_:InterruptedException){Thread.currentThread().interrupt()}
                finally {connections.remove(connection)}}
            }
        }
    }
    override fun close() {
        release.countDown();client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow()
        socket.close();connections.forEach {runCatching {it.close()}};acceptor.shutdownNow();workers.shutdownNow()
        check(acceptor.awaitTermination(3,TimeUnit.SECONDS));check(workers.awaitTermination(3,TimeUnit.SECONDS))
    }
}
