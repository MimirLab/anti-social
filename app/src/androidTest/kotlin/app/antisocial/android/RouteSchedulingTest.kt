/* AntiSocial, GPL-3.0-or-later. Real native Gateway routing over loopback fixtures. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
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
import java.util.concurrent.atomic.AtomicInteger

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
    private inner class Fixture(frontends:List<String>,replies:List<Reply>,onProbe:suspend (ProbeResult)->Unit={}):Closeable {
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
        val pool=SourcePool(gateway,routes,onProbe=onProbe)
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
    @Test fun aBusyReaderDatabaseCannotHoldAUsableRouteBehindHealthPersistence():Unit=runBlocking {
        isolated()
        val repo=(context.applicationContext as AntiSocialApplication).repo
        repo.awaitReady()
        // Inspect our own constructor callback, not Android hidden APIs. The
        // released callback and the candidate's callback must use the same
        // actual Repository persistence path in this loopback route replay.
        @Suppress("UNCHECKED_CAST")
        val sink=SourcePool::class.java.getDeclaredField("onProbe").apply {isAccessible=true}.get(repo.pool) as (suspend (ProbeResult)->Unit)
        Fixture(listOf("nitter","nitter"),listOf(Reply(body=post("A complete public routing fixture.")),Reply(503)),sink).use {f->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val holding=async(Dispatchers.IO){repo.db.withTransaction {entered.complete(Unit);release.await()}}
            var read:Deferred<Page>?=null
            val started=System.nanoTime()
            try {
                withTimeout(5_000){entered.await()}
                read=async(Dispatchers.IO){f.pool.load(f.target,originalFallback=false)}
                val page=try {withTimeout(3_000){read.await()}}catch(e:TimeoutCancellationException) {
                    evidence("health-persistence-blocked",mapOf("deliveredBeforeTransactionRelease" to false,
                        "transactionStillHeld" to !release.isCompleted,"requestsBySource" to f.servers.map {it.requests.size},
                        "extractedHealthySources" to f.pool.results(Platform.X).count {it.status==HealthStatus.HEALTHY},
                        "scope" to "Exact Repository callback and held actual Room transaction; timeout is a failed regression, not a successful delivery."))
                    throw e
                }
                val elapsed=(System.nanoTime()-started)/1_000_000
                assertEquals("A complete public routing fixture.",page.posts.single().body)
                assertFalse("Content must arrive while the unrelated transaction is still held",release.isCompleted)
                assertEquals(listOf(1,0),f.servers.map {it.requests.size})
                val observed=f.pool.results(Platform.X).single()
                assertEquals(HealthStatus.HEALTHY,observed.status)
                assertEquals(1,f.pool.learning.get(f.routes.sources.first(),Capability.TIMELINE,false)!!.successes)
                release.complete(Unit);withTimeout(5_000){holding.await()}
                val key="${observed.source.key}|${observed.capability}|${observed.tor}"
                withTimeout(5_000){while(repo.dao.allHealth().none {it.key==key&&it.checkedAt>=observed.checkedAt})delay(10)}
                evidence("health-persistence",mapOf("deliveredBeforeTransactionRelease" to true,"elapsedMs" to elapsed,
                    "requestsBySource" to f.servers.map {it.requests.size},"healthEventuallyPersisted" to true,
                    "scope" to "Exact Repository callback, actual Room transaction and loopback Gateway source; not live-source, physical-phone or p95 latency."))
            }finally {
                release.complete(Unit);withTimeout(5_000){holding.await()};read?.cancelAndJoin()
                repo.db.withTransaction {f.routes.sources.forEach {source->
                    repo.db.openHelper.writableDatabase.execSQL("DELETE FROM health WHERE `key`=?",arrayOf("${source.key}|${Capability.TIMELINE}|false"))
                }}
            }
        }
    }
    @Test fun aQueuedOlderHealthRowCannotReplaceItsNewerStoredObservation():Unit=runBlocking {
        isolated()
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val key="health-order-fixture-${System.nanoTime()}"
        val at=System.currentTimeMillis();val newer=HealthRow(key,"newer fixture",at+20)
        try {
            repo.dao.putCurrentHealth(newer)
            repo.dao.putCurrentHealth(HealthRow(key,"late older fixture",at+10))
            assertEquals(newer,repo.dao.health(key))
            val next=newer.copy(payload="next fixture",checkedAt=at+30)
            repo.dao.putCurrentHealth(next);assertEquals(next,repo.dao.health(key))
        }finally {repo.db.withTransaction {repo.db.openHelper.writableDatabase.execSQL("DELETE FROM health WHERE `key`=?",arrayOf(key))}}
    }
    @Test fun aRefusedRouteStaysPausedBeforeItsAdvisoryHealthRowCommits():Unit=runBlocking {
        isolated()
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        @Suppress("UNCHECKED_CAST")
        val sink=SourcePool::class.java.getDeclaredField("onProbe").apply {isAccessible=true}.get(repo.pool) as (suspend (ProbeResult)->Unit)
        Fixture(listOf("nitter","nitter"),listOf(Reply(429),Reply(body=post("The available source remains readable."))),sink).use {f->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val holding=async(Dispatchers.IO){repo.db.withTransaction {entered.complete(Unit);release.await()}}
            try {
                withTimeout(5_000){entered.await()}
                repeat(2){
                    val page=withTimeout(3_000){f.pool.load(f.target,originalFallback=false)}
                    assertEquals("The available source remains readable.",page.posts.single().body)
                    assertFalse(release.isCompleted)
                    assertFalse("The real refusal must affect admission immediately",f.pool.learning.eligible(f.routes.sources.first(),Capability.TIMELINE,false,f.target))
                }
                assertEquals(listOf(1,2),f.servers.map {it.requests.size})
                val observations=f.pool.results(Platform.X)
                assertEquals(HealthStatus.RATE_LIMITED,observations.single {it.source==f.routes.sources.first()}.status)
                release.complete(Unit);withTimeout(5_000){holding.await()}
                withTimeout(5_000){while(observations.any {r->
                    repo.dao.health("${r.source.key}|${r.capability}|${r.tor}")?.checkedAt?.let {it>=r.checkedAt}!=true
                })delay(10)}
                evidence("health-pause",mapOf("pauseBeforePersistence" to true,"requestsBySource" to f.servers.map {it.requests.size},
                    "healthEventuallyPersisted" to true,"scope" to "Actual Repository callback and held Room transaction, loopback 429 response; not live provider or Tor availability."))
            }finally {
                release.complete(Unit);withTimeout(5_000){holding.await()}
                repo.db.withTransaction {f.routes.sources.forEach {source->
                    repo.db.openHelper.writableDatabase.execSQL("DELETE FROM health WHERE `key`=?",arrayOf("${source.key}|${Capability.TIMELINE}|false"))
                }}
            }
        }
    }
    @Test fun nativeSourceProbeAcceptsExpectedStartupReadiness():Unit=runBlocking {
        isolated()
        val body="<div class='result'><a class='result__a' href='https://x.com/NASA/status/12345'>Public destination</a></div>"
        Fixture(listOf("duckduckgo"),listOf(Reply(body=body))).use {f->
            f.pool.awaitSearchConnection={f.gateway.configureTor(NetworkPolicy(true,9,true))}
            val result=runCatching {withTimeout(2_000){f.pool.probe(f.routes.sources.single(),Capability.SEARCH,force=true)}}
            assertTrue("Startup readiness must not cancel a usable native check: ${result.exceptionOrNull()}",result.isSuccess)
            val probe=result.getOrThrow();assertEquals(HealthStatus.HEALTHY,probe.status)
            assertEquals(1,f.servers.single().requests.size);assertFalse(f.gateway.currentPolicy().tor)
            evidence("probe-startup",mapOf("probe" to probe,"requests" to f.servers.single().requests.size,"contentRemainsDirect" to true,"scope" to "Owned loopback source and simulated readiness transition; not a live Tor or public-provider check."))
        }
    }
    @Test fun waitingNativeSearchChecksLeaveReadPermitsForReadySources():Unit=runBlocking {
        isolated()
        Fixture(listOf("duckduckgo","nitter"),listOf(Reply(),Reply(body=post("Ready direct source check")))).use {f->
            val waiting=AtomicInteger();val allWaiting=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            f.pool.awaitSearchConnection={if(waiting.incrementAndGet()==4)allWaiting.complete(Unit);release.await()}
            val jobs=List(4){async {runCatching {f.pool.probe(f.routes.sources[0],Capability.SEARCH,force=true)}}}
            try {
                withTimeout(2_000){allWaiting.await()}
                val start=System.nanoTime()
                val direct=withTimeoutOrNull(1_000){f.pool.probe(f.routes.sources[1],Capability.TIMELINE,force=true)}
                val elapsed=(System.nanoTime()-start)/1_000_000
                assertNotNull("Ready sources must not wait for four unrelated Tor starts",direct)
                assertEquals(HealthStatus.HEALTHY,direct!!.status)
                val counts=f.servers.map {it.requests.size};assertEquals(listOf(0,1),counts)
                evidence("probe-ready-slot",mapOf("requestsBySource" to counts,"elapsedMs" to elapsed,"waitingChecks" to waiting.get(),"scope" to "Native owned fixture; measures source-check admission, not ordinary feed or live Tor latency."))
            }finally {jobs.forEach {it.cancel()};jobs.forEach {it.join()}}
        }
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
