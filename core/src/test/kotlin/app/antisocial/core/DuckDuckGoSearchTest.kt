package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith

class DuckDuckGoSearchTest {
    private class Fixture:AutoCloseable {
        val server=MockWebServer().apply {start()}
        val source=Source(Platform.X,"duckduckgo",server.url("/").toString().trimEnd('/'),true,SourceRole.ORIGINAL)
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        val held=CountDownLatch(1);val lite=CountDownLatch(1)
        @Volatile var respond:(RecordedRequest)->MockResponse={MockResponse().setBody(html())}
        init {server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest)=respond(request)}}
        suspend fun read(query:String="community workshop",check:()->Unit={}):List<LocalWebHit> =
            gateway.duckSearch.read(source,query,source.root,gateway.sessionFor(source.root),maxBytes=1024*1024,check=check,productive={it.isNotEmpty()}) {payload->
                LocalWebDiscovery.parse(payload.text,payload.finalUrl,"duckduckgo").also {if(it.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"No publisher links")}
            }
        override fun close() {held.countDown();lite.countDown();gateway.client().let {it.dispatcher.cancelAll();it.connectionPool.evictAll();it.dispatcher.executorService.shutdownNow()};server.close()}
    }
    companion object {
        private fun html()="""<div class="result"><a class="result__a" href="https://publisher.fixture.invalid/calendar">Community workshop</a></div>"""
        private fun lite()="""<table><tr><td><a class="result-link" href="https://publisher.fixture.invalid/calendar">Community workshop</a></td></tr><tr><td class="result-snippet">Published calendar</td></tr></table>"""
    }
    @Test fun ordinarySuccessNeedsOneRead():Unit=runBlocking {
        Fixture().use {f->assertEquals(1,f.read().size);assertEquals(1,f.server.requestCount);assertEquals("GET",f.server.takeRequest().method)}
    }
    @Test fun anUnreadyRequiredConnectionDoesNotAttemptAnyRepresentation():Unit=runBlocking {
        Fixture().use {f->
            f.gateway.configure(NetworkPolicy(tor=true))
            assertEquals(HealthStatus.UNAVAILABLE,assertFailsWith<SourceFailure>{f.read()}.status)
            assertEquals(0,f.server.requestCount);assertFalse(f.gateway.hasForegroundRequests)
            f.gateway.configure(NetworkPolicy())
        }
    }
    @Test fun deniedAdmissionIsNotRecordedAsANewProviderRefusal():Unit=runBlocking {
        Fixture().use {f->
            val feedback=PublicIndexFeedback(f.source,f.gateway.sessionFor(f.source.root))
            val pause=SourceFailure(HealthStatus.RATE_LIMITED,"Already paused",120_000)
            val error=assertFailsWith<IndexAdmissionPause>{withContext(feedback){f.gateway.duckSearch.read<List<LocalWebHit>>(f.source,"workshop",f.source.root,f.gateway.sessionFor(f.source.root),
                admit={throw IndexAdmissionPause(pause)},productive={it.isNotEmpty()}) {LocalWebDiscovery.parse(it.text,it.finalUrl,"duckduckgo")}}}
            assertSame(pause,error.failure);assertNull(feedback.refusal);assertEquals(0,f.server.requestCount)
        }
    }
    @Test fun aHeldHtmlReadDoesNotPreventLiteDeliveryAndAProductiveFormatIsReused():Unit=runBlocking {
        Fixture().use {f->
            f.respond={r->if(r.path!!.startsWith("/html/")){check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setBody(html())}else MockResponse().setBody(lite())}
            assertEquals(1,withTimeout(5500){f.read()}.size);assertEquals(1L,f.held.count);assertEquals(2,f.server.requestCount)
            assertEquals(1,withTimeout(1500){f.read("another workshop")}.size);assertEquals(3,f.server.requestCount)
            assertTrue(f.server.takeRequest().path!!.startsWith("/html/"));repeat(2){assertTrue(f.server.takeRequest().path!!.startsWith("/lite/"))}
        }
    }
    @Test fun aRecoverableServerPagePromotesTheOriginalFormAndPreservesQuotedQuery():Unit=runBlocking {
        Fixture().use {f->
            f.respond={r->MockResponse().setBody(if(r.method=="GET")"<h1>The server encountered an error</h1>"else html())}
            val query="\"community workshop\" Test Region -cancelled"
            assertEquals(1,f.read(query).size);assertEquals(2,f.server.requestCount)
            assertEquals("GET",f.server.takeRequest().method);val form=f.server.takeRequest()
            assertEquals("POST",form.method);assertEquals("/html/",form.path)
            assertEquals(query,java.net.URLDecoder.decode(form.body.readUtf8().removePrefix("q="),"UTF-8"))
            assertNull(form.getHeader("Cookie"));assertNull(form.getHeader("Authorization"))
        }
    }
    @Test fun everyRepresentationSharesOneThreeReadAllowance():Unit=runBlocking {
        Fixture().use {f->f.respond={MockResponse().setBody("<p>No results</p>")}
            assertEquals(HealthStatus.EMPTY,assertFailsWith<SourceFailure>{f.read()}.status);assertEquals(3,f.server.requestCount)
            val calls=List(3){f.server.takeRequest()};assertEquals(1,calls.count {it.method=="POST"});assertEquals(1,calls.count {it.path!!.startsWith("/lite/")})
        }
    }
    @Test fun refusalsNeverTryAnotherRepresentation():Unit=runBlocking {
        for((code,body,status)in listOf(Triple(401,"",HealthStatus.LOGIN_REQUIRED),Triple(429,"",HealthStatus.RATE_LIMITED),Triple(503,"",HealthStatus.UNAVAILABLE),Triple(200,"<form id='challenge-form'>Bots use DuckDuckGo</form>",HealthStatus.CHALLENGE)))
            Fixture().use {f->f.respond={MockResponse().setResponseCode(code).setHeader("Retry-After","120").setBody(body)}
                assertEquals(status,assertFailsWith<SourceFailure>{f.read()}.status);assertEquals(1,f.server.requestCount)
            }
    }
    @Test fun cancelledReadsReleaseTheirSlotsWithoutStartingAlternates():Unit=runBlocking {
        Fixture().use {f->
            f.respond={check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setBody(html())}
            val job=async {f.read()};withTimeout(2000){while(f.server.requestCount==0)delay(10)}
            job.cancelAndJoin();assertEquals(1,f.server.requestCount);f.respond={MockResponse().setBody(html())}
            assertEquals(1,withTimeout(1500){f.read("another workshop")}.size);assertEquals(2,f.server.requestCount)
        }
    }
    @Test fun anActivePolicyCancellationIsDeliveredInsteadOfHanging():Unit=runBlocking {
        Fixture().use {f->
            val allowed=AtomicBoolean(true);f.respond={check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setBody(html())}
            val job=async {f.read(check={if(!allowed.get())throw CancellationException("Policy changed")})}
            withTimeout(2000){while(f.server.requestCount==0)delay(10)};allowed.set(false);f.held.countDown()
            val error=assertFailsWith<CancellationException>{withTimeout(1500){job.await()}}
            assertFalse(error is TimeoutCancellationException);assertEquals(1,f.server.requestCount)
        }
    }
    @Test fun aConnectionRoundTripCannotContinueAnOldRequest():Unit=runBlocking {
        Fixture().use {f->
            f.respond={check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setBody(html())}
            val job=async {f.read()};withTimeout(2000){while(f.server.requestCount==0)delay(10)}
            f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy());f.held.countDown()
            val error=assertFailsWith<CancellationException>{withTimeout(1500){job.await()}}
            assertFalse(error is TimeoutCancellationException);assertEquals(1,f.server.requestCount)
        }
    }
    @Test fun heldOrdinaryQueriesCannotConsumeTheReservedLiteSlot():Unit=runBlocking {
        Fixture().use {f->
            f.respond={r->if(r.path!!.startsWith("/lite/")){check(f.lite.await(8,TimeUnit.SECONDS));MockResponse().setBody(lite())}
                else {check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setBody(html())}}
            val answers=Channel<List<LocalWebHit>>(4)
            val jobs=(1..4).map {launch {answers.send(f.read("workshop $it"))}}
            try {
                withTimeout(5500){while(f.server.requestCount<3)delay(10)}
                assertEquals(3,f.server.requestCount);assertEquals(3,f.gateway.client().dispatcher.runningCallsCount())
                f.lite.countDown();assertEquals(1,withTimeout(1500){answers.receive()}.size);assertEquals(1L,f.held.count)
            }finally {jobs.forEach {it.cancel()};jobs.joinAll();answers.cancel()}
        }
    }
    @Test fun preferenceDoesNotCrossAConnectionEpoch():Unit=runBlocking {
        Fixture().use {f->
            f.respond={r->MockResponse().setBody(if(r.path!!.startsWith("/html/"))"<p>No results</p>"else lite())}
            assertEquals(1,f.read().size);repeat(2){f.server.takeRequest()}
            f.gateway.configure(NetworkPolicy(tor=true));f.gateway.configure(NetworkPolicy())
            f.respond={MockResponse().setBody(html())};assertEquals(1,f.read().size)
            assertTrue(f.server.takeRequest().path!!.startsWith("/html/"));assertEquals(3,f.server.requestCount)
        }
    }
    @Test fun originsStayPinnedWhenARepresentationRedirects():Unit=runBlocking {
        MockWebServer().use {foreign->foreign.start();foreign.enqueue(MockResponse().setBody(html()))
          Fixture().use {f->
            f.respond={r->if(r.path!!.startsWith("/html/"))MockResponse().setResponseCode(302).setHeader("Location",foreign.url("/"))else MockResponse().setBody(lite())}
            assertEquals(1,f.read().size);assertEquals(2,f.server.requestCount)
            assertEquals(0,foreign.requestCount)
          }
        }
    }
    @Test fun liteFormsRemainBoundToTheirRepresentationAndQuery() {
        val source=Source(Platform.X,"duckduckgo",DuckDuckGoProtocol.HTML_ROOT,true,SourceRole.ORIGINAL)
        val query="community workshop"
        val body=lite()+"""<base href="https://unselected.fixture.invalid/"><form action="/lite/" method="post"><input type="submit" value="Next Page &gt;"><input type="hidden" name="q" value="$query"><input type="hidden" name="s" value="10"><input type="hidden" name="tracking" value="discard"></form>"""
        val page=DuckDuckGoProtocol.page(Jsoup.parse(body,DuckDuckGoProtocol.LITE_ROOT+"/lite/"),query)
        val cursor=requireNotNull(page.next);assertTrue(cursor.startsWith("search-lite-form:"));assertFalse(cursor.contains("tracking"))
        val form=DuckDuckGoProtocol.continuation(source,query,cursor);assertEquals(DuckDuckGoProtocol.LITE_ROOT+"/lite/",form.url)
        for(bad in listOf(cursor.replace("lite.duckduckgo.com","evil.fixture.invalid"),cursor.replace("/lite/","/html/"),cursor.replace("search-lite-form:","search-form:"),cursor.replace("community workshop","another query")))
            assertFailsWith<IllegalArgumentException>{DuckDuckGoProtocol.continuation(source,query,bad)}
        for(action in listOf("https://evil.fixture.invalid/lite/","https://user:secret@lite.duckduckgo.com/lite/","/logout","/lite/?q=x","/lite/#fragment"))
            assertNull(DuckDuckGoProtocol.page(Jsoup.parse(body.replace("action=\"/lite/\"","action=\"$action\""),DuckDuckGoProtocol.LITE_ROOT+"/lite/"),query).next)
    }
    @Test fun ordinaryResultTextCannotMasqueradeAsAProviderRefusal() {
        val body="""<div class="result"><a class="result__a" href="https://publisher.fixture.invalid/">Bots use DuckDuckGo</a><p>The server encountered an error</p></div>"""
        assertEquals(1,LocalWebDiscovery.parse(body,DuckDuckGoProtocol.HTML_ROOT+"/html/","duckduckgo").size)
        assertEquals(HealthStatus.UNAVAILABLE,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse("<h1>The server encountered an error</h1>",DuckDuckGoProtocol.HTML_ROOT+"/html/","duckduckgo")}.status)
    }
    @Test fun liteAdvertisementsAreExcludedWithoutDiscardingOrdinaryLinks() {
        val body=lite()+"""<div class="result-sponsored"><a class="result-link" href="https://ad.fixture.invalid/">Ad</a></div><a class="result-link" href="https://duckduckgo.com/y.js?ad_domain=ad.fixture.invalid&amp;uddg=https%3A%2F%2Fad.fixture.invalid%2F">Ad</a>"""
        assertEquals(listOf("https://publisher.fixture.invalid/calendar"),DuckDuckGoProtocol.page(Jsoup.parse(body,DuckDuckGoProtocol.LITE_ROOT+"/lite/"),"workshop").links.map {it.url})
    }
    @Test fun foregroundWorkPreemptsDeferredReadersBeforeWaitingForTheirSlots():Unit=runBlocking {
        Fixture().use {f->
            f.respond={r->if(r.path!!.contains("background")){check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setBody(html())}else MockResponse().setBody(html())}
            val background=(1..2).map {launch {f.gateway.deferredRead {f.read("background $it")}}}
            try {
                withTimeout(2000){while(f.server.requestCount<2)delay(10)}
                assertEquals(1,withTimeout(1500){f.read("foreground")}.size)
                withTimeout(1500){background.joinAll()};assertTrue(background.all {it.isCancelled});assertEquals(1L,f.held.count)
                assertFalse(f.gateway.hasForegroundRequests)
            }finally {background.forEach {it.cancel()};background.joinAll()}
        }
    }
    @Test fun anInitialDiscoveryReadStillCoexistsWithItsDeferredDetails():Unit=runBlocking {
        Fixture().use {f->
            f.respond={r->if(r.path!!.contains("background")){check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setBody(html())}else MockResponse().setBody(html())}
            val background=launch {f.gateway.deferredRead {f.read("background")}}
            try {
                withTimeout(1500){while(f.server.requestCount==0)delay(10)}
                assertEquals(1,withTimeout(1500){f.gateway.discoveryRead {f.read("initial")}}.size)
                assertTrue(background.isActive);assertEquals(1L,f.held.count);assertFalse(f.gateway.hasForegroundRequests)
            }finally {background.cancelAndJoin()}
        }
    }
    @Test fun theLocalAllowanceAlsoBoundsFormResponsesBeforeTheyAreCaptured():Unit=runBlocking {
        Fixture().use {f->
            val oversized=AtomicBoolean()
            f.gateway.onTextResponse={if(it.text.length>1024*1024)oversized.set(true)}
            f.respond={r->when {r.method=="POST"->MockResponse().setBody("x".repeat(2*1024*1024));r.path!!.startsWith("/html/")->MockResponse().setBody("<h1>The server encountered an error</h1>");else->MockResponse().setBody(lite())}}
            assertEquals(1,f.read().size);assertFalse(oversized.get());assertEquals(3,f.server.requestCount)
        }
    }
    @Test fun aLaterAdmissionPauseDoesNotEraseAnAlreadyRequestedAnswer():Unit=runBlocking {
        Fixture().use {f->
            val paused=AtomicBoolean();f.respond={check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setBody(html())}
            val job=async {f.gateway.duckSearch.read(f.source,"workshop",f.source.root,f.gateway.sessionFor(f.source.root),
                admit={if(paused.get())throw SourceFailure(HealthStatus.RATE_LIMITED,"Paused",120_000)},productive={it.isNotEmpty()}) {LocalWebDiscovery.parse(it.text,it.finalUrl,"duckduckgo")}}
            withTimeout(1500){while(f.server.requestCount==0)delay(10)};paused.set(true);f.held.countDown()
            assertEquals(1,withTimeout(1500){job.await()}.size);assertEquals(1,f.server.requestCount)
        }
    }
    @Test fun laterPlatformMatchesSurviveEarlierOffsiteResults() {
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
        val source=Source(Platform.X,"duckduckgo",DuckDuckGoProtocol.HTML_ROOT,true,SourceRole.ORIGINAL)
        val early=(1..60).joinToString(""){"""<div class="result"><a class="result__a" href="https://offsite.fixture.invalid/$it">Article</a></div>"""}
        val last="""<div class="result"><a class="result__a" href="https://x.com/FixtureWorkshop/status/123">Workshop announcement</a></div>"""
        val page=Extraction.parse(Payload(early+last,source.root+"/html/","text/html",200),routes.search(Platform.X,"workshop"),source,routes)
        assertEquals("https://x.com/FixtureWorkshop/status/123",page.collections.single().target.canonicalUrl)
        assertEquals(HealthStatus.EMPTY,assertFailsWith<SourceFailure>{Extraction.parse(Payload(early.replace("Article","Bots use DuckDuckGo"),source.root+"/html/","text/html",200),routes.search(Platform.X,"workshop"),source,routes)}.status)
    }
    @Test fun aStrongerRefusalSurvivesLaterParallelReplies():Unit=runBlocking {
        Fixture().use {f->
            val feedback=PublicIndexFeedback(f.source,f.gateway.sessionFor(f.source.root))
            feedback.observe(listOf(SourceFailure(HealthStatus.RATE_LIMITED,"Long wait",120_000)))
            coroutineScope {(1..24).map {i->async(Dispatchers.Default){feedback.observe(listOf(SourceFailure(HealthStatus.UNAVAILABLE,"Short wait",15_000L+i)))}}.awaitAll()}
            assertEquals(120_000L,feedback.refusal!!.retryAfterMs);assertEquals(HealthStatus.RATE_LIMITED,feedback.refusal!!.status)
            val error=assertFailsWith<SourceFailure>{withContext(feedback){f.read("future query")}}
            assertEquals(120_000L,error.retryAfterMs);assertEquals(0,f.server.requestCount)
        }
    }
    @Test fun aUsefulInFlightReplyCanCoexistWithAnotherQueriesRefusal():Unit=runBlocking {
        Fixture().use {f->
            val feedback=PublicIndexFeedback(f.source,f.gateway.sessionFor(f.source.root))
            f.respond={r->if(r.path!!.contains("earlier")){check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setBody(html())}
                else MockResponse().setResponseCode(429).setHeader("Retry-After","120")}
            val earlier=async {withContext(feedback){f.read("earlier query")}}
            withTimeout(1500){while(f.server.requestCount==0)delay(10)}
            assertEquals(HealthStatus.RATE_LIMITED,assertFailsWith<SourceFailure>{withContext(feedback){f.read("later query")}}.status)
            f.held.countDown();assertEquals(1,withTimeout(1500){earlier.await()}.size)
            assertEquals(120_000L,feedback.refusal!!.retryAfterMs);assertEquals(2,f.server.requestCount)
        }
    }
    @Test fun aRefusalCompletedDuringAnswerAcknowledgementIsRetained():Unit=runBlocking {
        Fixture().use {f->
            val feedback=PublicIndexFeedback(f.source,f.gateway.sessionFor(f.source.root))
            val acknowledging=CountDownLatch(1);val release=CountDownLatch(1)
            f.respond={r->if(r.path!!.startsWith("/html/")){check(f.held.await(8,TimeUnit.SECONDS));MockResponse().setResponseCode(429).setHeader("Retry-After","120")}
                else MockResponse().setBody(lite())}
            val answer=async(Dispatchers.Default){withContext(feedback){f.gateway.duckSearch.read(f.source,"workshop",f.source.root,f.gateway.sessionFor(f.source.root),
                productive={acknowledging.countDown();check(release.await(5,TimeUnit.SECONDS));it.isNotEmpty()}) {LocalWebDiscovery.parse(it.text,it.finalUrl,"duckduckgo")}}}
            try {
                withTimeout(5500){while(acknowledging.count>0)delay(10)}
                f.held.countDown();withTimeout(1500){while(feedback.refusal==null)delay(10)}
                release.countDown();assertEquals(1,withTimeout(1500){answer.await()}.size)
                assertEquals(120_000L,feedback.refusal!!.retryAfterMs);assertEquals(2,f.server.requestCount)
            }finally {release.countDown();answer.cancelAndJoin()}
        }
    }
    @Test fun expandedPlansKeepTheirNestedLiteFormsAndValidateThemBeforeTransport():Unit=runBlocking {
        Fixture().use {f->
            val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,f.cleaner)
            val target=routes.search(Platform.INSTAGRAM,"community workshop")
            val source=f.source.copy(platform=Platform.INSTAGRAM)
            f.respond={r->
                if(!r.path!!.startsWith("/lite/"))MockResponse().setBody("<h1>The server encountered an error</h1>")
                else {
                    val document=Jsoup.parse("""<a class="result-link" href="https://www.instagram.com/p/FixtureWorkshop/">Workshop announcement</a>""")
                    val form=document.body().appendElement("form").attr("action",f.server.url("/lite/").newBuilder().scheme("https").build().toString())
                    form.appendElement("input").attr("type","submit").attr("value","Next Page >")
                    form.appendElement("input").attr("type","hidden").attr("name","q").attr("value",r.requestUrl!!.queryParameter("q")!!)
                    form.appendElement("input").attr("type","hidden").attr("name","s").attr("value","10")
                    MockResponse().setBody(document.outerHtml())
                }
            }
            val adapter=PublicSearchAdapter(source,f.gateway,routes)
            val page=withTimeout(5000){adapter.fetch(target,null)}
            val cursor=requireNotNull(page.next);assertTrue(cursor.startsWith("public-index:"));assertTrue(cursor.contains("search-lite-form:"))
            val before=f.server.requestCount
            f.gateway.configure(NetworkPolicy(tor=true))
            val error=runCatching {withTimeout(1500){adapter.fetch(target,cursor)}}.exceptionOrNull()
            assertTrue("The nested forms should validate and reach the unavailable transport: $error",error is java.io.IOException)
            assertEquals(before,f.server.requestCount);f.gateway.configure(NetworkPolicy())
            assertFailsWith<IllegalArgumentException>{adapter.fetch(target,cursor.replace("search-lite-form:","search-form:"))}
            assertEquals(before,f.server.requestCount)
        }
    }
}
