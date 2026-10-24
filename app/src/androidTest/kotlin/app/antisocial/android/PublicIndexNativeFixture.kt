/* anti social, GPL-3.0-or-later. Owned loopback source for native search qualification. */
package app.antisocial.android

import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.JsonParser
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class PublicIndexNativeFixture(routes:DarwinRoutes,val target:Target,val fast:String,val slower:String,private val third:String?=null):Closeable {
    private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    // A cancelled response can still occupy a server worker. Twelve bounds all
    // four-by-three fixture operations without making those leftovers stall an
    // otherwise fast request and accidentally conceal reader starvation.
    private val acceptor=Executors.newSingleThreadExecutor();private val workers=Executors.newFixedThreadPool(12)
    private val connections=ConcurrentHashMap.newKeySet<Socket>()
    val requests=CopyOnWriteArrayList<String>();val fastReads=AtomicInteger();val reelReads=AtomicInteger();val broadReads=AtomicInteger()
    val release=CountDownLatch(1)
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
    // Retain before fail-closed tests: acquiring a client while Tor is unavailable
    // must not be used as a cleanup shortcut.
    private val client=gateway.client()
    val source:Source=Source(target.platform,"librey","http://127.0.0.1:${socket.localPort}")
    private val localRoutes:DarwinRoutes=DarwinRoutes(JsonParser.parseReader(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.assets.open("darwin-routes.json").reader()).asJsonObject.apply {
        add("sources",JsonParser.parseString("""[{"platform":"${target.platform.id}","frontend":"librey","root":"${source.root}","fallbackOnly":false}]"""))
        add("searchSources",com.google.gson.JsonArray())
    },cleaner)
    val pool:SourcePool=SourcePool(gateway,localRoutes).apply {searchProvider=SearchProvider.DARWIN}
    val reader:DataAdapter=pool.sourceReader(source)
    private val postQuery=if(third==null)"site:instagram.com/p/ ${target.identity}"else PublicSearchAdapter.query(target)
    private val reelQuery=if(third==null)"site:instagram.com/reel/ ${target.identity}"else "site:tiktok.com inurl:photo ${target.identity}"
    private val broadQuery=if(third==null)PublicSearchAdapter.query(target)else "site:tiktok.com ${target.identity}"
    private fun hit(url:String)="""<div class="text-result-wrapper"><a href="$url"><h2>Index-only fixture hint</h2></a></div>"""
    init {acceptor.submit {
        while(!socket.isClosed) {
            val connection=try {socket.accept()}catch(e:java.io.IOException){if(socket.isClosed)break else throw e}
            connections+=connection
            workers.submit {
                try {connection.use {c->
                    c.soTimeout=4_000;val input=c.getInputStream().bufferedReader()
                    val path=requireNotNull(input.readLine()).split(' ')[1]
                    var ended=false
                    for(i in 0 until 64) {
                        val line=requireNotNull(input.readLine());require(line.length<=8192)
                        if(line.isEmpty()){ended=true;break}
                        check(!line.startsWith("Cookie:",true)&&!line.startsWith("Authorization:",true))
                    }
                    check(ended);requests+=path
                    val query=path.substringAfter('?',"").split('&').map {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=',""),"UTF-8")}.toMap()["q"]
                    val body=when(query) {
                        postQuery->hit(if(fastReads.incrementAndGet()==1||third!=null)fast else fast+"More")
                        reelQuery->{val call=reelReads.incrementAndGet();if(third!=null)Thread.sleep(450)else if(call==1)Thread.sleep(5_000)else {check(release.await(8,TimeUnit.SECONDS));Thread.sleep(700)};hit(slower)}
                        broadQuery->{broadReads.incrementAndGet();if(third!=null){Thread.sleep(900);hit(third)}else hit("https://www.instagram.com/nativefixture")}
                        else->error("Unknown fixture query")
                    }.toByteArray()
                    c.getOutputStream().apply {write("HTTP/1.1 200 Fixture\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
                }}catch(_:java.io.IOException){/* The reader cancels a losing request. */}
                catch(_:InterruptedException){Thread.currentThread().interrupt()}
                finally {connections.remove(connection)}
            }
        }
    }}
    /** HTML pagination intentionally admits HTTPS only. At this loopback fixture
     * boundary stage the already source/query-bound saved continuation; keep
     * production admission unchanged. Both phases still use the real Gateway. */
    fun firstPlan(page:Page):Page {
        val root=JsonParser.parseString(requireNotNull(page.next).removePrefix("public-index:")).asJsonObject
        val lanes=root.getAsJsonArray("lanes")
        if(lanes.none {it.asJsonObject["index"].asInt==0})lanes.add(com.google.gson.JsonObject().apply {
            addProperty("index",0);addProperty("attempts",fastReads.get())
            addProperty("cursor",source.root+"/search.php?q="+DarwinRoutes.encode(postQuery)+"&p="+(fastReads.get()+1))
            if(root["version"].asInt==2)addProperty("deferred",false)
        })
        return page.copy(next="public-index:$root")
    }
    override fun close() {
        release.countDown();client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow()
        socket.close();connections.forEach {runCatching {it.close()}};acceptor.shutdownNow();workers.shutdownNow()
        check(acceptor.awaitTermination(3,TimeUnit.SECONDS));check(workers.awaitTermination(3,TimeUnit.SECONDS))
    }
}
