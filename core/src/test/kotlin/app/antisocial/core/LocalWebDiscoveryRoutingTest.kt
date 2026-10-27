package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Gateway/SOCKS exchanges with invented destinations; never live traffic. */
class LocalWebDiscoveryRoutingTest {
    private data class Reply(val body:String="",val code:Int=200,val headers:Map<String,String> = emptyMap())
    private data class Request(val host:String,val path:String,val headers:List<String>)
    private class Fixture(val hosts:List<String> = listOf("first.fixture.invalid","second.fixture.invalid","third.fixture.invalid"),apis:Set<String> = emptySet(),fourget:Set<String> = emptySet()):AutoCloseable {
        private val server=ServerSocket(0,16,InetAddress.getLoopbackAddress())
        private val workers=Executors.newCachedThreadPool()
        private val sockets=ConcurrentLinkedQueue<Socket>()
        val requests=ConcurrentLinkedQueue<Request>()
        val release=CountDownLatch(1)
        @Volatile var respond:(Request)->Reply={Reply(html())}
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {
            override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)
        },true)
        var now=System.currentTimeMillis()
        val learning=AdaptiveRouting{now}
        val routes=DarwinRoutes(JsonParser.parseString("""{"sources":[],"services":{},"searchSources":[${hosts.filterNot {it in fourget}.joinToString(","){"""{"platform":"twitter","frontend":"librey","root":"http://$it","fallbackOnly":true,"jsonApi":${it in apis}}"""}}],"publisherSearchSources":[${hosts.filter {it in fourget}.joinToString(","){"""{"frontend":"4get","root":"http://$it"}"""}}]}""").asJsonObject,cleaner)
        val engine=LocalWebDiscovery(gateway,routes,learning,clock={now})
        init {
            gateway.configureTor(NetworkPolicy(true,server.localPort,true))
            workers.submit {
                while(!server.isClosed)try {
                    val socket=server.accept();sockets+=socket
                    workers.submit {runCatching {exchange(socket)}}
                }catch(_:java.io.IOException){break}
            }
        }
        fun renewSession() {gateway.configureTor(NetworkPolicy(true));gateway.configureTor(NetworkPolicy(true,server.localPort,true))}
        private fun exchange(socket:Socket)=socket.use {
            it.soTimeout=8000
            val input=DataInputStream(it.getInputStream());val out=it.getOutputStream()
            check(input.readUnsignedByte()==5);repeat(input.readUnsignedByte()){input.readByte()}
            out.write(byteArrayOf(5,0));out.flush()
            check(input.readUnsignedByte()==5&&input.readUnsignedByte()==1);input.readByte();check(input.readUnsignedByte()==3)
            val host=ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8);input.readUnsignedShort()
            out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80));out.flush()
            val reader=input.bufferedReader();val start=reader.readLine() ?: return@use
            val headers=buildList {while(true){val line=reader.readLine() ?: break;if(line.isEmpty())break;add(line)}}
            val request=Request(host,start.split(' ')[1],headers);requests+=request
            val reply=respond(request);val bytes=reply.body.toByteArray(Charsets.UTF_8)
            val head="HTTP/1.1 ${reply.code} Fixture\r\nContent-Length: ${bytes.size}\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n"+reply.headers.entries.joinToString(""){"${it.key}: ${it.value}\r\n"}+"\r\n"
            out.write(head.toByteArray(Charsets.UTF_8));out.write(bytes);out.flush()
        }
        override fun close() {
            release.countDown();server.close();sockets.forEach {runCatching {it.close()}};workers.shutdownNow();workers.awaitTermination(2,TimeUnit.SECONDS)
            runCatching {gateway.client(NetworkRoute.TOR).let {it.connectionPool.evictAll();it.dispatcher.executorService.shutdownNow()}}
        }
    }
    companion object {
        private fun html()="""<div class="text-result-wrapper"><a href="https://publisher.fixture.invalid/calendar"><h2>Community workshop</h2></a></div>"""
        private fun api()="""{"0":{"url":"https://publisher.fixture.invalid/calendar","title":"Community workshop"},"results_source":"index.fixture.invalid"}"""
    }
    private suspend fun failure(block:suspend ()->Unit):SourceFailure=try {block();error("Expected source refusal")}catch(e:SourceFailure){e}
    @Test fun aResponsiveMirrorCanDeliverWhileTheEarlierReadRemainsHeld():Unit=runBlocking {
        Fixture().use {f->
            f.respond={request->if(request.host==f.hosts.first()){check(f.release.await(8,TimeUnit.SECONDS));Reply()}else Reply(html())}
            val hits=withTimeout(3500){f.engine.search("community workshop",SearchProvider.DARWIN,false)}
            assertEquals("https://publisher.fixture.invalid/calendar",hits.single().url)
            assertEquals(1L,f.release.count)
            assertTrue(f.requests.any {it.host==f.hosts[1]})
        }
    }
    @Test fun aSessionRoundTripCannotMoveTheOldQueryToAnotherMirror():Unit=runBlocking {
        Fixture().use {f->
            f.gateway.onTextResponse={payload->if(payload.finalUrl.contains(f.hosts.first()))f.renewSession()}
            try {f.engine.search("community workshop",SearchProvider.DARWIN,false);fail("The old query must stop after the session changes")}
            catch(_:CancellationException){}
            assertEquals(listOf(f.hosts.first()),f.requests.map {it.host})
        }
    }
    @Test fun aFreedSlotCanReachTheThirdMirrorWithoutWaitingForTheFirst():Unit=runBlocking {
        Fixture().use {f->
            f.respond={r->when(r.host){f.hosts[0]->{check(f.release.await(8,TimeUnit.SECONDS));Reply()};f.hosts[1]->Reply(code=503);else->Reply(html())}}
            assertEquals(1,withTimeout(3500){f.engine.search("community workshop",SearchProvider.DARWIN,false)}.size)
            assertEquals(f.hosts,f.requests.map {it.host});assertEquals(1L,f.release.count)
            assertTrue(f.learning.snapshot().none {it.source.root.endsWith(f.hosts[0])})
        }
    }
    @Test fun allFormatsAndMirrorsShareThreeAttempts():Unit=runBlocking {
        val hosts=(1..6).map {"index-$it.fixture.invalid"}
        Fixture(hosts,hosts.toSet()).use {f->
            f.respond={Reply("[]")}
            assertEquals(HealthStatus.EMPTY,failure {f.engine.search("unusual workshop",SearchProvider.DARWIN,false)}.status)
            assertEquals(3,f.requests.size)
            assertEquals(listOf("/api.php","/search.php","/api.php"),f.requests.map {it.path.substringBefore('?')})
        }
    }
    @Test fun concurrentLookupsShareThreeActiveReadersAndCancellationLeavesNoOutage():Unit=runBlocking {
        Fixture().use {f->
            f.respond={check(f.release.await(8,TimeUnit.SECONDS));Reply(html())}
            val jobs=(1..5).map {async(Dispatchers.IO){f.engine.search("workshop $it",SearchProvider.DARWIN,false)}}
            try {
                withTimeout(4000){while(f.requests.size<3)delay(10)}
                delay(1700);assertEquals(3,f.requests.size)
            }finally {jobs.forEach {it.cancel()};jobs.joinAll()}
            withTimeout(2000){while(f.gateway.client(NetworkRoute.TOR).dispatcher.runningCallsCount()>0)delay(10)}
            assertTrue(f.learning.snapshot().isEmpty())
        }
    }
    @Test fun explicitWaitsCoverOtherQueriesAndPlatformCopiesButNotOtherEndpoints():Unit=runBlocking {
        val host="limited.fixture.invalid"
        Fixture(listOf(host),setOf(host)).use {f->
            f.respond={Reply(code=429,headers=mapOf("Retry-After" to "120"))}
            assertEquals(HealthStatus.RATE_LIMITED,failure {f.engine.search("workshop",SearchProvider.DARWIN,false)}.status)
            val source=f.routes.searchSources.single();val deadline=f.learning.eligibleAt(source,Capability.SEARCH,true)
            assertFalse(f.learning.eligible(source.copy(platform=Platform.INSTAGRAM),Capability.SEARCH,true))
            assertTrue(f.learning.eligible(source.copy(root="http://other.fixture.invalid"),Capability.SEARCH,true))
            f.now+=60_000;failure {f.engine.search("another subject",SearchProvider.DARWIN,false)}
            assertEquals(1,f.requests.size);assertEquals(deadline,f.learning.eligibleAt(source,Capability.SEARCH,true))
            f.now+=60_001;f.respond={Reply(api())}
            assertEquals(1,f.engine.search("another subject",SearchProvider.DARWIN,false).size)
            assertEquals(2,f.requests.size);assertTrue(f.learning.eligible(source,Capability.SEARCH,true))
        }
    }
    @Test fun aPauseLearnedElsewhereSkipsOnlyItsActualEndpoint():Unit=runBlocking {
        Fixture().use {f->
            val source=f.routes.searchSources.first().copy(platform=Platform.TIKTOK)
            f.learning.observe(ProbeResult(source,Capability.SEARCH,HealthStatus.RATE_LIMITED,0,f.now,true,"Rate limited",120_000))
            assertEquals(1,f.engine.search("workshop",SearchProvider.DARWIN,false).size)
            assertEquals(listOf(f.hosts[1]),f.requests.map {it.host})
        }
    }
    @Test fun anInFlightUsefulAnswerCannotClearTheCompanionServerWait():Unit=runBlocking {
        Fixture(listOf("index.fixture.invalid")).use {f->
            f.respond={r->if(r.path.contains("earlier")){check(f.release.await(8,TimeUnit.SECONDS));Reply(html())}else Reply(code=429,headers=mapOf("Retry-After" to "120"))}
            val earlier=async(Dispatchers.IO){f.engine.search("earlier workshop",SearchProvider.DARWIN,false)}
            withTimeout(2000){while(f.requests.isEmpty())delay(10)}
            assertEquals(HealthStatus.RATE_LIMITED,failure {f.engine.search("later workshop",SearchProvider.DARWIN,false)}.status)
            f.release.countDown();assertEquals(1,withTimeout(2000){earlier.await()}.size)
            val source=f.routes.searchSources.single()
            assertFalse(f.learning.eligible(source,Capability.SEARCH,true))
            val stats=f.learning.snapshot().single();assertEquals(2,stats.samples);assertEquals(1,stats.successes);assertEquals(1,stats.deliverySamples)
        }
    }
    @Test fun anEmptyQueryDoesNotParkAnOtherwiseUsableIndex():Unit=runBlocking {
        Fixture(listOf("index.fixture.invalid")).use {f->
            f.respond={r->if(r.path.contains("unusual"))Reply("<p>No results</p>")else Reply(html())}
            assertEquals(HealthStatus.EMPTY,failure {f.engine.search("unusual workshop",SearchProvider.DARWIN,false)}.status)
            assertEquals(1,f.engine.search("community workshop",SearchProvider.DARWIN,false).size)
            assertEquals(2,f.requests.size);assertEquals(1,f.learning.snapshot().single().successes)
        }
    }
    @Test fun anUnavailableApiKeepsHtmlAndDoesNotTrainAnEndpointOutage():Unit=runBlocking {
        val host="index.fixture.invalid"
        Fixture(listOf(host),setOf(host)).use {f->
            f.respond={r->if(r.path.startsWith("/api.php"))Reply(code=404)else Reply(html())}
            assertEquals(1,f.engine.search("workshop",SearchProvider.DARWIN,false).size)
            assertEquals(listOf("/api.php","/search.php"),f.requests.map {it.path.substringBefore('?')})
            assertEquals(1,f.learning.snapshot().single().samples)
            f.engine.search("another workshop",SearchProvider.DARWIN,false)
            assertEquals("/search.php",f.requests.last().path.substringBefore('?'))
            f.now+=6*3_600_000L+1;f.respond={Reply(api())}
            f.engine.search("new workshop",SearchProvider.DARWIN,false)
            assertEquals("/api.php",f.requests.last().path.substringBefore('?'))
        }
    }
    @Test fun emptyFastApiResultsRetainTheServerAssistedHtmlFallback():Unit=runBlocking {
        val host="index.fixture.invalid"
        Fixture(listOf(host),setOf(host)).use {f->
            f.respond={r->if(r.path.startsWith("/api.php"))Reply("[]")else Reply(html())}
            assertEquals(1,f.engine.search("workshop",SearchProvider.DARWIN,false).size)
            assertEquals(2,f.requests.size);assertTrue(f.requests.first().path.contains("&nfb=1"));assertFalse(f.requests.last().path.contains("&nfb="))
            assertTrue(f.requests.all {it.path.contains("&nf=1&ns=1")})
        }
    }
    @Test fun loginChallengeAndExplicitRefusalNeverRetryTheOtherRepresentation():Unit=runBlocking {
        val host="index.fixture.invalid"
        for((reply,status)in listOf(Reply(code=401)to HealthStatus.LOGIN_REQUIRED,Reply(code=503,headers=mapOf("Retry-After" to "120"))to HealthStatus.UNAVAILABLE,Reply("<form id='challenge-form'>Verify you are human</form>")to HealthStatus.CHALLENGE))
            Fixture(listOf(host),setOf(host)).use {f->f.respond={reply}
                assertEquals(status,failure {f.engine.search("workshop",SearchProvider.DARWIN,false)}.status)
                assertEquals(1,f.requests.size);assertFalse(f.learning.eligible(f.routes.searchSources.single(),Capability.SEARCH,true))
            }
    }
    @Test fun aPolicyRevocationIsReportedByItsChildWithoutHangingOrTrainingFailure():Unit=runBlocking {
        Fixture(listOf("index.fixture.invalid")).use {f->
            val allowed=AtomicBoolean(true)
            f.respond={check(f.release.await(8,TimeUnit.SECONDS));Reply(code=503)}
            val job=async(Dispatchers.IO){f.engine.search("workshop",SearchProvider.DARWIN,false,allowed::get)}
            withTimeout(2000){while(f.requests.isEmpty())delay(10)}
            allowed.set(false);f.release.countDown()
            try {withTimeout(2000){job.await()};fail("Revoked policy must cancel the lookup")}catch(e:CancellationException){assertFalse("An omitted child completion must not be hidden by the test timeout",e is TimeoutCancellationException)}
            assertEquals(1,f.requests.size);assertTrue(f.learning.snapshot().isEmpty())
        }
    }
    @Test fun selectedTorNotReadyAndDisabledOriginalSearchDoNotSendOrTrain():Unit=runBlocking {
        Fixture().use {f->
            f.gateway.configureTor(NetworkPolicy(true))
            assertEquals(HealthStatus.UNAVAILABLE,failure {f.engine.search("workshop",SearchProvider.DARWIN,false)}.status)
            assertEquals(HealthStatus.UNSUPPORTED,failure {f.engine.search("workshop",SearchProvider.BING,false)}.status)
            assertTrue(f.requests.isEmpty());assertTrue(f.learning.snapshot().isEmpty());assertFalse(f.gateway.currentPolicy().tor)
        }
    }
    @Test fun knownServerWaitIsAvailableBeforeTransportReadinessAndDoesNotRenew():Unit=runBlocking {
        Fixture(listOf("index.fixture.invalid")).use {f->
            val source=f.routes.searchSources.single()
            f.learning.observe(ProbeResult(source,Capability.SEARCH,HealthStatus.RATE_LIMITED,0,f.now,true,"Rate limited",120_000))
            val before=f.learning.snapshot();f.gateway.configureTor(NetworkPolicy(true))
            assertEquals(120_000L,f.engine.pause(SearchProvider.DARWIN,false)!!.retryAfterMs)
            val error=failure {f.engine.search("workshop",SearchProvider.DARWIN,false)}
            assertTrue(error.message.orEmpty().contains("paused"));assertEquals(120_000L,error.retryAfterMs)
            assertEquals(before,f.learning.snapshot());assertTrue(f.requests.isEmpty())
        }
    }
    @Test fun aMirrorRedirectCannotDiscloseTheQueryToAnUnselectedEndpoint():Unit=runBlocking {
        Fixture().use {f->
            f.respond={r->if(r.host==f.hosts.first())Reply(code=302,headers=mapOf("Location" to "http://unselected.fixture.invalid/search.php?q=workshop"))else Reply(html())}
            assertEquals(1,f.engine.search("workshop",SearchProvider.DARWIN,false).size)
            assertEquals(listOf(f.hosts[0],f.hosts[1]),f.requests.map {it.host})
        }
    }
    @Test fun anIndependentPublisherProtocolCanRescueAHeldMirror():Unit=runBlocking {
        val hosts=listOf("held.fixture.invalid","independent.fixture.invalid")
        Fixture(hosts,setOf(hosts[0]),setOf(hosts[1])).use {f->
            f.respond={r->if(r.host==hosts[0]){check(f.release.await(8,TimeUnit.SECONDS));Reply("[]")}
                else Reply("""{"status":"ok","web":[{"title":"Community workshop","url":"https://publisher.fixture.invalid/calendar"}],"npt":"single-use-token"}""")}
            val query="\"community workshop\" Test Region -cancelled"
            assertEquals(1,withTimeout(3500){f.engine.search(query,SearchProvider.DARWIN,false)}.size)
            assertEquals(1,f.routes.searchSources.size);assertEquals(2,f.routes.publisherSearchSources.size)
            val request=f.requests.last();assertTrue(request.path.startsWith("/api/v1/web?s="))
            assertEquals(query,DarwinRoutes.queryValue(java.net.URI("http://"+request.host+request.path),"s"))
            assertFalse(request.path.contains("npt"));assertEquals(1L,f.release.count)
        }
    }
    @Test fun anotherUntriedProtocolPrecedesMoreMirrorsWithinTheSameBudget():Unit=runBlocking {
        val mirrors=(1..4).map {"mirror-$it.fixture.invalid"};val others=(1..3).map {"publisher-$it.fixture.invalid"}
        Fixture(mirrors+others,fourget=others.toSet()).use {f->
            f.respond={r->if(r.host in mirrors){check(f.release.await(8,TimeUnit.SECONDS));Reply()}
                else Reply("""{"status":"ok","web":[{"title":"Workshop","url":"https://publisher.fixture.invalid/calendar"}]}""")}
            assertEquals(1,withTimeout(3500){f.engine.search("community workshop",SearchProvider.DARWIN,false)}.size)
            assertEquals(listOf(mirrors[0],others[0]),f.requests.map {it.host})
            assertEquals(1L,f.release.count);assertTrue(f.requests.size<=3)
            assertTrue(f.learning.snapshot().none {it.source.root.endsWith(mirrors[0])})
        }
    }
    @Test fun recentUsefulMirrorsKeepPriorityOverUntriedProtocols():Unit=runBlocking {
        val mirrors=(1..3).map {"mirror-$it.fixture.invalid"};val other="publisher.fixture.invalid"
        Fixture(mirrors+other,fourget=setOf(other)).use {f->
            f.routes.searchSources.take(2).forEach {f.learning.observe(ProbeResult(it,Capability.SEARCH,HealthStatus.HEALTHY,1,f.now,true,"Useful fixture"))}
            f.respond={r->if(r.host==mirrors[0])Reply(code=503)else Reply(html())}
            assertEquals(1,f.engine.search("community workshop",SearchProvider.DARWIN,false).size)
            assertEquals(mirrors.take(2),f.requests.map {it.host});assertTrue(f.requests.size<=3)
        }
    }
    @Test fun publisherApiRefusalsAreEndpointScopedWithoutHtmlRetry():Unit=runBlocking {
        val host="independent.fixture.invalid"
        Fixture(listOf(host),fourget=setOf(host)).use {f->
            f.respond={Reply("""{"status":"Captcha required","web":[]}""")}
            assertEquals(HealthStatus.CHALLENGE,failure {f.engine.search("community workshop",SearchProvider.DARWIN,false)}.status)
            val source=f.routes.publisherSearchSources.single()
            assertFalse(f.learning.eligible(source.copy(platform=Platform.INSTAGRAM),Capability.SEARCH,true))
            failure {f.engine.search("another workshop",SearchProvider.DARWIN,false)}
            assertEquals(1,f.requests.size)
        }
    }
    @Test fun aHeldIndependentEndpointCannotMonopolizeAUsableAlternate():Unit=runBlocking {
        val hosts=listOf("independent.fixture.invalid","alternate.fixture.invalid")
        Fixture(hosts,fourget=setOf(hosts[0])).use {f->
            val independent=f.routes.publisherSearchSources.first {it.frontend=="4get"}
            f.learning.observe(ProbeResult(independent,Capability.SEARCH,HealthStatus.HEALTHY,1,f.now,true,"Qualified fixture"))
            f.respond={r->if(r.host==hosts[0]){check(f.release.await(8,TimeUnit.SECONDS));Reply("""{"status":"ok","web":[]}""")}else Reply(html())}
            assertEquals(1,withTimeout(3500){f.engine.search("community workshop",SearchProvider.DARWIN,false)}.size)
            assertEquals(1L,f.release.count);assertEquals(hosts,f.requests.map {it.host});assertTrue(f.requests.size<=3)
            assertTrue(f.learning.get(independent,Capability.SEARCH,true)!!.lastSuccessAt>0)
            assertEquals("Cancelling the losing hedge must not train an outage",1,f.learning.get(independent,Capability.SEARCH,true)!!.samples)
        }
    }
    @Test fun independentEndpointFailureDoesNotPoisonItsUsablePeer():Unit=runBlocking {
        val hosts=listOf("independent.fixture.invalid","alternate.fixture.invalid")
        Fixture(hosts,fourget=setOf(hosts[0])).use {f->
            val preferred=f.routes.publisherSearchSources.first {it.frontend=="4get"}
            f.learning.observe(ProbeResult(preferred,Capability.SEARCH,HealthStatus.HEALTHY,1,f.now,true,"Qualified fixture"))
            f.respond={r->if(r.host==hosts[0])Reply(code=503)else Reply(html())}
            assertEquals(1,withTimeout(3500){f.engine.search("community workshop",SearchProvider.DARWIN,false)}.size)
            val independent=f.routes.publisherSearchSources.first {it.frontend=="4get"}
            val alternate=f.routes.publisherSearchSources.first {it.root.endsWith(hosts[1])}
            assertFalse(f.learning.eligible(independent,Capability.SEARCH,true))
            assertTrue(f.learning.eligible(alternate,Capability.SEARCH,true));assertTrue(f.requests.size<=3)
            assertTrue(f.requests.none {it.host==hosts[0]&&!it.path.startsWith("/api/v1/web?")})
        }
    }
    @Test fun anEmptyStructuredReadKeepsItsHtmlCoverageAheadOfNewCandidates():Unit=runBlocking {
        val hosts=listOf("index.fixture.invalid","independent.fixture.invalid")
        Fixture(hosts,setOf(hosts[0]),setOf(hosts[1])).use {f->
            f.respond={r->if(r.path.startsWith("/api.php"))Reply("[]")else Reply(html())}
            assertEquals(1,f.engine.search("community workshop",SearchProvider.DARWIN,false).size)
            assertEquals(listOf("/api.php","/search.php"),f.requests.map {it.path.substringBefore('?')})
            assertTrue(f.requests.all {it.host==hosts[0]})
        }
    }
    @Test fun theDefaultProvidersKnownEmptyQueryDoesNotStartNewRepresentations():Unit=runBlocking {
        Fixture().use {f->
            val source=Source(Platform.X,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
            val query="community workshop";val target=Target(Platform.X,TargetKind.SEARCH,query,source.root+"/html/?q="+DarwinRoutes.encode(query))
            f.learning.observe(ProbeResult(source,Capability.SEARCH,HealthStatus.EMPTY,0,f.now,true,"No matching publisher links"),target)
            assertEquals(HealthStatus.EMPTY,failure {withTimeout(1000){f.engine.search(query,SearchProvider.DUCKDUCKGO,true)}}.status)
            assertTrue(f.requests.isEmpty());assertTrue(f.learning.eligible(source,Capability.SEARCH,true))
        }
    }
    @Test fun aPeerPauseAtDefaultReadAdmissionDoesNotTrainOrRenewIt():Unit=runBlocking {
        Fixture().use {f->
            val source=Source(Platform.X,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
            var checks=0
            val error=failure {withTimeout(1000){f.engine.search("community workshop",SearchProvider.DUCKDUCKGO,true) {
                // The second policy checkpoint is inside the shared reader,
                // after initial local eligibility and before transmission.
                if(++checks==2)f.learning.observe(ProbeResult(source,Capability.SEARCH,HealthStatus.RATE_LIMITED,0,f.now,true,"Peer wait",120_000))
                true
            }}}
            assertEquals(120_000L,error.retryAfterMs);assertEquals(1,f.learning.get(source,Capability.SEARCH,true)!!.samples)
            assertEquals(f.now+120_000,f.learning.eligibleAt(source,Capability.SEARCH,true));assertTrue(f.requests.isEmpty())
        }
    }
}
