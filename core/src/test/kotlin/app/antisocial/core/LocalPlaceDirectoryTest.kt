package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Actual Gateway/SOCKS requests to invented hosts, no public source traffic. */
class LocalPlaceDirectoryTest {
    private data class Request(val host:String,val start:String,val headers:Map<String,String>,val body:String)
    private data class Reply(val body:String=places(),val code:Int=200,val headers:Map<String,String> = emptyMap())
    private class Fixture(val hosts:List<String> = listOf("first.fixture.invalid","second.fixture.invalid"),timeout:Long=2000):AutoCloseable {
        val socket=ServerSocket(0,8,InetAddress.getLoopbackAddress())
        private val workers=Executors.newCachedThreadPool()
        private val sockets=ConcurrentLinkedQueue<Socket>()
        val requests=ConcurrentLinkedQueue<Request>()
        val release=CountDownLatch(1)
        @Volatile var reply:(Request)->Reply={Reply()}
        @Volatile var now=1_793_000_000_000L
        val cache=ConcurrentHashMap<String,Pair<String,Long>>()
        val ready=AtomicInteger()
        val gateway=NetworkGateway(UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject),object:RequestFilter {
            override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)
        },true)
        private val client:okhttp3.OkHttpClient
        val reader=LocalPlaceDirectory(gateway,{key->cache[key]?.takeIf {it.second>now}?.first},{key,value,ttl->cache[key]=value to (now+ttl)},{now},hosts.map {"http://$it/api/interpreter"},timeout)
        val query=LocalDiscoveryQuery(LocalArea("fixture","Example City","Example City, Test Region",LocalPoint(10.0,20.0),5,"UTC"),"workshop")
        suspend fun read(text:String=query.text)=reader.directory(query.copy(text=text)){ready.incrementAndGet();Unit}
        init {
            gateway.configureTor(NetworkPolicy(true,socket.localPort,true))
            client=gateway.client(NetworkRoute.TOR)
            workers.submit {while(!socket.isClosed)try {val c=socket.accept();sockets+=c;workers.submit {runCatching {exchange(c)}}}catch(_:java.io.IOException){break}}
        }
        @Suppress("DEPRECATION") private fun exchange(c:Socket)=c.use {
            c.soTimeout=5000;val input=DataInputStream(c.getInputStream());val out=c.getOutputStream()
            check(input.readUnsignedByte()==5);repeat(input.readUnsignedByte()){input.readByte()};out.write(byteArrayOf(5,0));out.flush()
            check(input.readUnsignedByte()==5&&input.readUnsignedByte()==1);input.readByte();check(input.readUnsignedByte()==3)
            val host=ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8);input.readUnsignedShort()
            out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80));out.flush()
            val start=input.readLine() ?: error("Missing request")
            val headers=linkedMapOf<String,String>()
            while(true){val line=input.readLine() ?: error("Missing headers");if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
            val body=ByteArray(headers["content-length"]?.toInt() ?: 0).also(input::readFully).toString(Charsets.UTF_8)
            val request=Request(host,start,headers,body);requests+=request
            val response=reply(request);val bytes=response.body.toByteArray(Charsets.UTF_8)
            out.write(("HTTP/1.1 ${response.code} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n"+response.headers.entries.joinToString(""){"${it.key}: ${it.value}\r\n"}+"\r\n").toByteArray())
            out.write(bytes);out.flush()
        }
        override fun close() {
            release.countDown();socket.close();sockets.forEach {runCatching {it.close()}};workers.shutdownNow();check(workers.awaitTermination(3,TimeUnit.SECONDS))
            client.let {it.dispatcher.cancelAll();it.connectionPool.evictAll();it.dispatcher.executorService.shutdownNow()}
        }
    }
    companion object {private fun places()="""{"elements":[{"type":"node","id":7,"lat":10.0,"lon":20.0,"tags":{"name":"Community Workshop","shop":"craft","contact:website":"https://publisher.fixture.invalid/"}}]}"""}
    private suspend fun failure(block:suspend ()->Unit):SourceFailure=try {block();error("Expected source failure")}catch(e:SourceFailure){e}
    @Test fun aProductiveDirectoryUsesOnePinnedFormWithTheExistingQueryAndProvenance():Unit=runBlocking {
        Fixture().use {f->
            val result=f.read();assertEquals("Community Workshop",result.businesses.single().name)
            assertTrue(result.businesses.single().evidence.all {it.sourceUrl=="https://www.openstreetmap.org/node/7"})
            val r=f.requests.single();assertEquals("POST /api/interpreter HTTP/1.1",r.start)
            assertEquals(LocalPlaceSources.overpassUrl(f.query.area,topic=f.query.text).toHttpUrl().queryParameter("data"),URLDecoder.decode(r.body.substringAfter("data="),"UTF-8"))
            assertEquals("application/x-www-form-urlencoded",r.headers["content-type"])
            assertTrue(r.headers["user-agent"].orEmpty().contains("github.com/MimirLab/anti-social"));assertFalse(r.headers.containsKey("cookie"));assertFalse(r.headers.containsKey("authorization"))
        }
    }
    @Test fun aProductiveBackupLeadsTheNextQueryEvenAfterTheOtherPauseExpires():Unit=runBlocking {
        Fixture().use {f->
            f.reply={if(it.host==f.hosts.first())Reply(code=503)else Reply()}
            assertEquals(1,f.read().businesses.size);assertEquals(f.hosts,f.requests.map {it.host})
            f.now+=20_000;assertEquals(1,f.read("coffee").businesses.size)
            assertEquals(listOf(f.hosts[0],f.hosts[1],f.hosts[1]),f.requests.map {it.host})
        }
    }
    @Test fun queryAllowanceDoesNotPauseOtherQueriesOrTrainAnEndpointOutage():Unit=runBlocking {
        Fixture().use {f->
            f.reply={Reply("""{"remark":"runtime error: Query timed out in query at line 1","elements":[]}""")}
            failure {f.read()};assertEquals(2,f.requests.size);assertTrue(f.cache.keys.all {it.startsWith("directory-query:")})
            val before=f.cache.toMap();val ready=f.ready.get();failure {f.read()};assertEquals(ready,f.ready.get());assertEquals(before,f.cache.toMap())
            f.reply={Reply()};assertEquals(1,f.read("coffee").businesses.size);assertEquals(3,f.requests.size)
        }
    }
    @Test fun rateRefusalStopsThisAttemptAndTheAbsoluteWaitSurvivesSkippedCallsAndUnreadyTor():Unit=runBlocking {
        Fixture(listOf("only.fixture.invalid")).use {f->
            f.reply={Reply(code=429,headers=mapOf("Retry-After" to "120"))}
            val first=failure {f.read()};assertEquals(HealthStatus.RATE_LIMITED,first.status);assertEquals(120_000L,first.retryAfterMs)
            val before=f.cache.toMap();f.gateway.configureTor(NetworkPolicy(true));f.now+=60_000
            assertEquals(60_000L,failure {f.read("coffee")}.retryAfterMs);assertEquals(1,f.ready.get());assertEquals(1,f.requests.size);assertEquals(before,f.cache.toMap())
            f.now+=60_001;f.gateway.configureTor(NetworkPolicy(true,f.socket.localPort,true));f.reply={Reply()}
            assertEquals(1,f.read("coffee").businesses.size)
        }
    }
    @Test fun anImmediateRateRefusalDoesNotTryTheSecondOperator():Unit=runBlocking {
        Fixture().use {f->f.reply={Reply(code=429)};failure {f.read()};assertEquals(listOf(f.hosts[0]),f.requests.map {it.host})}
    }
    @Test fun aQueryStatusCannotDiscardAnExplicitServerWait():Unit=runBlocking {
        Fixture(listOf("only.fixture.invalid")).use {f->
            f.reply={Reply(code=400,headers=mapOf("Retry-After" to "900"))};failure {f.read()}
            f.now+=600_000;assertEquals(300_000L,failure {f.read("coffee")}.retryAfterMs);assertEquals(1,f.requests.size)
        }
    }
    @Test fun repeatedWaitHeadersKeepTheLongestValidWait():Unit=runBlocking {
        Fixture(listOf("only.fixture.invalid")).use {f->
            f.reply={Reply(code=504,headers=mapOf("Retry-After" to "15","retry-after" to "120"))}
            assertEquals(120_000L,failure {f.read()}.retryAfterMs)
        }
    }
    @Test fun resourceAdmissionFailureHasAShortEndpointPauseRatherThanFifteenMinutes():Unit=runBlocking {
        Fixture(listOf("only.fixture.invalid")).use {f->
            f.reply={Reply(code=504)};assertEquals(504,failure {f.read()}.httpStatus)
            f.now+=10_000;assertEquals(5000L,failure {f.read("coffee")}.retryAfterMs);assertEquals(1,f.requests.size)
            f.now+=5001;f.reply={Reply()};assertEquals(1,f.read("coffee").businesses.size)
        }
    }
    @Test fun anExplicitHttpDateOnResourceRefusalIsRetained():Unit=runBlocking {
        Fixture(listOf("only.fixture.invalid")).use {f->
            val date=java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",java.util.Locale.US).apply {timeZone=java.util.TimeZone.getTimeZone("GMT")}.format(java.util.Date(f.now+180_000))
            f.reply={Reply(code=504,headers=mapOf("Retry-After" to date))};assertEquals(180_000L,failure {f.read()}.retryAfterMs)
            f.now+=60_000;assertEquals(120_000L,failure {f.read("coffee")}.retryAfterMs);assertEquals(1,f.requests.size)
        }
    }
    @Test fun legacyPausesAreHonoredBeforeStartingTransportWithoutBeingRenewed():Unit=runBlocking {
        Fixture().use {f->
            f.hosts.forEach {f.cache["directory-rest:http://$it/api/interpreter"]="rate limited" to (f.now+60_000)}
            val before=f.cache.toMap();f.gateway.configureTor(NetworkPolicy(true));failure {f.read()}
            assertEquals(0,f.ready.get());assertTrue(f.requests.isEmpty());assertEquals(before,f.cache.toMap())
        }
    }
    @Test fun aLocalDeadlineFallsBackSeriallyAndDoesNotLearnAFifteenMinuteOutage():Unit=runBlocking {
        Fixture(timeout=150).use {f->
            f.reply={if(it.host==f.hosts.first()){check(f.release.await(3,TimeUnit.SECONDS));Reply()}else Reply()}
            assertEquals(1,withTimeout(3000){f.read()}.businesses.size);assertEquals(f.hosts,f.requests.map {it.host});assertEquals(1L,f.release.count)
            f.now+=15_001;assertEquals(1,f.read("coffee").businesses.size);assertEquals(f.hosts[1],f.requests.last().host)
        }
    }
    @Test fun cancellationReleasesTheSingleReaderWithoutTrainingAPause():Unit=runBlocking {
        Fixture().use {f->
            f.reply={check(f.release.await(3,TimeUnit.SECONDS));Reply()}
            val job=launch(Dispatchers.IO){f.read()};withTimeout(2000){while(f.requests.isEmpty())delay(5)};job.cancelAndJoin()
            assertTrue(f.cache.isEmpty());f.release.countDown();f.reply={Reply()};assertEquals(1,withTimeout(2000){f.read("coffee")}.businesses.size)
        }
    }
    @Test fun concurrentQueriesHaveOnlyOneActualDirectoryRead():Unit=runBlocking {
        Fixture().use {f->
            f.reply={check(f.release.await(3,TimeUnit.SECONDS));Reply()}
            val first=async(Dispatchers.IO){f.read()};withTimeout(2000){while(f.requests.isEmpty())delay(5)}
            val second=async(Dispatchers.IO){f.read("coffee")};delay(100);assertEquals(1,f.requests.size)
            f.release.countDown();withTimeout(2000){awaitAll(first,second)};assertEquals(2,f.requests.size)
        }
    }
    @Test fun aConnectionRoundTripCannotContinueToAnotherOperatorOrTrainAnOutage():Unit=runBlocking {
        Fixture().use {f->
            f.reply={f.gateway.configure(NetworkPolicy(true));f.gateway.configure(NetworkPolicy(false));f.gateway.configureTor(NetworkPolicy(true,f.socket.localPort,true));Reply(code=503)}
            try {f.read();fail("Old request must stop")}catch(_:kotlinx.coroutines.CancellationException){}
            assertEquals(1,f.requests.size);assertTrue(f.cache.isEmpty())
        }
    }
    @Test fun anOriginChangingRedirectIsNotFollowed():Unit=runBlocking {
        Fixture().use {f->
            f.reply={if(it.host==f.hosts[0])Reply(code=302,headers=mapOf("Location" to "http://foreign.fixture.invalid/api/interpreter"))else Reply()}
            assertEquals(1,f.read().businesses.size);assertEquals(f.hosts,f.requests.map {it.host});assertFalse(f.requests.any {it.host=="foreign.fixture.invalid"})
        }
    }
    @Test fun anUnreadyRequiredTorNeverTransmits():Unit=runBlocking {
        Fixture().use {f->f.gateway.configureTor(NetworkPolicy(true));failure {f.read()};assertTrue(f.requests.isEmpty());assertTrue(f.cache.isEmpty())}
    }
    @Test fun cancellationDuringConnectionPreparationDoesNotTrainAnOutage():Unit=runBlocking {
        Fixture().use {f->
            try {withTimeout(100){f.reader.directory(f.query){awaitCancellation()}};fail("Preparation must be cancellable")}
            catch(_:TimeoutCancellationException){}
            assertTrue(f.requests.isEmpty());assertTrue(f.cache.isEmpty());assertEquals(1,f.read().businesses.size)
        }
    }
    @Test fun anOversizedBodyCannotBecomePlaces():Unit=runBlocking {
        Fixture().use {f->f.reply={if(it.host==f.hosts[0])Reply(" ".repeat(2*1024*1024+1))else Reply()};assertEquals(1,f.read().businesses.size);assertEquals(2,f.requests.size)}
    }
    @Test fun emptyValidElementsAreNotAnEndpointFailure():Unit=runBlocking {
        Fixture().use {f->f.reply={Reply("""{"elements":[]}""")};assertTrue(f.read().businesses.isEmpty());assertEquals(1,f.requests.size);assertTrue("An empty query must not teach a productive content route",f.cache.isEmpty());f.reply={Reply()};assertEquals(1,f.read("coffee").businesses.size)}
    }
}
