/* anti social, GPL-3.0-or-later. Owned SOCKS endpoints; never public traffic. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class PublisherQueryNativeFixture(val fastFirst:Boolean=false,val htmlOnlyFirst:Boolean=false,
    private val publisher:(String)->String={""}):Closeable {
    data class Request(val host:String,val path:String)
    val hosts=listOf("first-index.fixture.invalid","second-index.fixture.invalid","third-index.fixture.invalid")
    private val socket=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
    private val acceptor=Executors.newSingleThreadExecutor()
    // At most six index attempts plus two publisher reads. Cancelled owned
    // handlers retain their latch until close; these are not live app readers.
    private val workers=Executors.newFixedThreadPool(8)
    private val connections=ConcurrentHashMap.newKeySet<Socket>()
    val requests=CopyOnWriteArrayList<Request>()
    val release=CountDownLatch(1)
    val firstTwo=CountDownLatch(2)
    val firstApi=CountDownLatch(1)
    val apiReady=CountDownLatch(1)
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    val gateway=NetworkGateway(cleaner,object:RequestFilter {
        override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)
    },true)
    val routes=DarwinRoutes(JsonParser.parseString("""{"sources":[],"services":{},"searchSources":[${hosts.joinToString(","){
        """{"platform":"twitter","frontend":"librey","root":"http://$it","jsonApi":true,"fallbackOnly":true}"""
    }}]}""").asJsonObject,cleaner)
    val learning=AdaptiveRouting()
    val engine=LocalWebDiscovery(gateway,routes,learning)
    init {
        gateway.configureTor(NetworkPolicy(true,socket.localPort,true))
        acceptor.submit {
            while(!socket.isClosed) {
                val connection=try {socket.accept()}catch(_:java.io.IOException){break}
                connections+=connection
                workers.submit {try {connection.use(::exchange)}
                    catch(_:java.io.IOException){/* Losing calls close their sockets. */}
                    catch(_:InterruptedException){Thread.currentThread().interrupt()}
                    finally {connections.remove(connection)}}
            }
        }
    }
    private fun exchange(connection:Socket) {
        connection.soTimeout=6000
        val input=DataInputStream(connection.getInputStream());val out=connection.getOutputStream()
        check(input.readUnsignedByte()==5);repeat(input.readUnsignedByte()){input.readByte()}
        out.write(byteArrayOf(5,0));out.flush()
        check(input.readUnsignedByte()==5&&input.readUnsignedByte()==1);input.readByte();check(input.readUnsignedByte()==3)
        val host=ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8);input.readUnsignedShort()
        out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80));out.flush()
        val reader=input.bufferedReader();val start=requireNotNull(reader.readLine());require(start.length<=8192)
        var ended=false
        for(i in 0 until 64) {
            val line=requireNotNull(reader.readLine());require(line.length<=8192)
            if(line.isEmpty()){ended=true;break}
            check(!line.startsWith("Cookie:",true)&&!line.startsWith("Authorization:",true))
        }
        check(ended)
        val path=start.substringAfter(' ').substringBefore(' ');requests+=Request(host,path)
        val body=when(host) {
            hosts[0],hosts[1]->{
                if(host==hosts[0])firstTwo.countDown()
                if(htmlOnlyFirst)when {
                    host!=hosts[0]->"[]"
                    path.startsWith("/api.php")-> {firstApi.countDown();check(apiReady.await(5,TimeUnit.SECONDS));"<p>This endpoint supplies its public HTML search.</p>"}
                    else->"<div class=\"text-result-wrapper\"><a href=\"${destination(host,path)}\"><h2>Public workshop</h2></a></div>"
                }
                else if(fastFirst&&host==hosts[0]) {Thread.sleep(30);links(host,path)}
                else {check(release.await(10,TimeUnit.SECONDS));"[]"}
            }
            hosts[2]->if(htmlOnlyFirst)"[]"else links(host,path)
            "publisher.fixture.invalid"->publisher(path)
            else->error("Unexpected owned-fixture destination")
        }.toByteArray()
        out.write("HTTP/1.1 200 Fixture\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body);out.flush()
    }
    private fun destination(host:String,path:String):String {
        val query=DarwinRoutes.queryValue(URI("http://$host$path"),"q").orEmpty()
        val edition=if(query.endsWith(" alpha"))"alpha"else "beta"
        return "https://publisher.fixture.invalid/$edition"
    }
    private fun links(host:String,path:String)="""{"0":{"title":"Public workshop","url":"${destination(host,path)}"}}"""
    fun knownWinner()=learning.observe(ProbeResult(routes.publisherSearchSources.first(),Capability.SEARCH,
        HealthStatus.HEALTHY,20,System.currentTimeMillis(),true,"Controlled earlier useful delivery"))
    override fun close() {
        release.countDown();apiReady.countDown();socket.close();connections.forEach {runCatching {it.close()}}
        acceptor.shutdownNow();workers.shutdownNow()
        check(acceptor.awaitTermination(3,TimeUnit.SECONDS));check(workers.awaitTermination(3,TimeUnit.SECONDS))
        gateway.client(NetworkRoute.TOR).let {it.dispatcher.cancelAll();it.connectionPool.evictAll();it.dispatcher.executorService.shutdownNow()}
    }
}
