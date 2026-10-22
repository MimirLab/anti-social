package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Exact-link recovery is separate from oldest-version edit comparison. */
class ArchiveRecoveryTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    private val now=1_792_146_400_000L
    private val target=Target(Platform.X,TargetKind.POST,"jack/status/20","https://x.com/jack/status/20")
    private val old="20081103142857"
    private val recent="20210323183740"
    private val header="[\"timestamp\",\"original\",\"statuscode\",\"mimetype\"]"
    private fun cdx(vararg timestamps: String,original: String="https://twitter.com/jack/status/20")=
        "["+header+timestamps.joinToString("") {",[\"$it\",\"$original\",\"200\",\"text/html\"]"}+"]"
    private fun html(body: String="Actual recovered post",id: String="20")="""<article data-tweet-id="$id"><p class=tweet-content>$body</p><div class=attachments><img src="https://pbs.twimg.com/media/recovered.jpg"></div></article>"""
    private fun provider(server: MockWebServer,id: String="one",ndjson: Boolean=false)=ArchiveProvider(id,id,server.url("/").toString().trimEnd('/'),"/$id-index","/$id/",ndjson)
    private suspend fun failure(read: suspend ()->ArchivedVersion?): SourceFailure {
        try {read();fail("Expected an honest archive failure") }catch(e: SourceFailure){return e}
        throw AssertionError("No source failure returned")
    }

    @Test fun latestIndexesUseDocumentedProviderSpecificBoundedExactQueries() {
        val wayback=ArchiveProvider.defaults[0].indexUrl(target.canonicalUrl,latest=true).toHttpUrlOrNull()!!
        assertEquals("-2",wayback.queryParameter("limit"));assertEquals("true",wayback.queryParameter("fastLatest"))
        assertEquals("exact",wayback.queryParameter("matchType"));assertNull(wayback.queryParameter("collapse"))
        val arquivo=ArchiveProvider.defaults[1].indexUrl(target.canonicalUrl,latest=true).toHttpUrlOrNull()!!
        assertEquals("2",arquivo.queryParameter("limit"));assertEquals("reverse",arquivo.queryParameter("sort"))
        assertEquals("timestamp,url,status,mime",arquivo.queryParameter("fields"))
        val history=ArchiveProvider.defaults[0].indexUrl(target.canonicalUrl).toHttpUrlOrNull()!!
        assertEquals("2",history.queryParameter("limit"));assertNull(history.queryParameter("fastLatest"))
    }

    @Test fun newestReadableFirstProviderReturnsWithoutWaitingForAnotherArchive(): Unit=runBlocking {
        MockWebServer().use {server->server.start()
            val first=provider(server);val second=provider(server,"two",true)
            server.enqueue(MockResponse().setBody(cdx(old,recent)))
            server.enqueue(MockResponse().setBody(html("The newest readable capture")))
            val result=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}.recover(target)!!
            assertEquals("The newest readable capture",result.snapshot.body);assertEquals("one",result.provider)
            assertEquals(2,server.requestCount)
            assertEquals("-2",server.takeRequest().requestUrl!!.queryParameter("limit"))
            assertTrue(server.takeRequest().path!!.startsWith("/one/${recent}id_/"))
            assertTrue(result.snapshot.media.single().url.startsWith(first.origin+"/one/${recent}id_/https://pbs.twimg.com/"))
            assertEquals("",result.currentSignature)
        }
    }

    @Test fun unreadableLatestShellCanUseOldestReadableCaptureWithoutScanningHistory(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server)
            server.enqueue(MockResponse().setBody(cdx(old,recent)))
            server.enqueue(MockResponse().setBody("<noscript>JavaScript is not available</noscript>"))
            server.enqueue(MockResponse().setBody(cdx(old)))
            server.enqueue(MockResponse().setBody(html("A complete older capture")))
            val result=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first)){now}.recover(target)!!
            assertEquals("A complete older capture",result.snapshot.body);assertEquals(4,server.requestCount)
            server.takeRequest();assertTrue(server.takeRequest().path!!.contains(recent))
            val oldestIndex=server.takeRequest().requestUrl!!;assertEquals("2",oldestIndex.queryParameter("limit"));assertNull(oldestIndex.queryParameter("fastLatest"))
            assertTrue(server.takeRequest().path!!.contains(old))
        }
    }

    @Test fun unavailableFirstArchiveCanRecoverFromSecondWithoutInventingLiveContent(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server);val second=provider(server,"two",true)
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""{"timestamp":"$recent","url":"https://twitter.com/jack/status/20","status":"200","mime":"text/html"}"""))
            server.enqueue(MockResponse().setBody(html()))
            val result=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}.recover(target)!!
            assertEquals("two",result.provider);assertEquals("Actual recovered post",result.snapshot.body);assertEquals(3,server.requestCount)
            assertTrue(server.takeRequest().path!!.startsWith("/one-index"));assertEquals("reverse",server.takeRequest().requestUrl!!.queryParameter("sort"))
            val replay=server.takeRequest();assertEquals("GET",replay.method);assertNull(replay.getHeader("Cookie"));assertNull(replay.getHeader("Authorization"))
        }
    }

    @Test fun completedEmptyIndexesAreTheOnlyConfirmedMiss(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server);val second=provider(server,"two",true)
            server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody(""))
            assertNull(ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}.recover(target))
            server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(HealthStatus.UNAVAILABLE,failure {ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}.recover(target)}.status)
            assertEquals(4,server.requestCount)
        }
    }

    @Test fun wrongIndexedIdentityAndWrongReplayRedirectCannotBecomeRecovery(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server)
            server.enqueue(MockResponse().setBody(cdx(recent,original="https://twitter.com/jack/status/21")))
            assertEquals(HealthStatus.UNAVAILABLE,failure {ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first)){now}.recover(target)}.status)
            server.enqueue(MockResponse().setBody(cdx(recent)))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",first.replay(recent,"https://twitter.com/jack/status/21")))
            server.enqueue(MockResponse().setBody(html("Wrong post",id="21")))
            assertEquals(HealthStatus.UNAVAILABLE,failure {ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first)){now}.recover(target)}.status)
            assertEquals(4,server.requestCount)
        }
    }

    @Test fun fourIndexAndReplayBudgetReservesEachArchivesLatestBeforeOldest(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server);val second=provider(server,"two",true)
            server.enqueue(MockResponse().setBody(cdx(old,recent)))
            server.enqueue(MockResponse().setBody("<meta property=og:description content='Metadata is not a post'>"))
            server.enqueue(MockResponse().setBody("""{"timestamp":"$recent","url":"https://twitter.com/jack/status/20","status":"200","mime":"text/html"}"""))
            server.enqueue(MockResponse().setBody("<meta property=og:description content='Metadata is not a post'>"))
            server.enqueue(MockResponse().setBody(cdx(old)))
            server.enqueue(MockResponse().setBody("<noscript>JavaScript is not available</noscript>"))
            server.enqueue(MockResponse().setBody("""{"timestamp":"$old","url":"https://twitter.com/jack/status/20","status":"200","mime":"text/html"}"""))
            server.enqueue(MockResponse().setBody("<noscript>JavaScript is not available</noscript>"))
            assertEquals(HealthStatus.UNAVAILABLE,failure {ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}.recover(target)}.status)
            assertEquals(8,server.requestCount)
            server.takeRequest();assertTrue(server.takeRequest().path!!.contains(recent))
            assertTrue(server.takeRequest().path!!.startsWith("/two-index"));assertTrue(server.takeRequest().path!!.startsWith("/two/"))
            assertEquals("2",server.takeRequest().requestUrl!!.queryParameter("limit"));assertTrue(server.takeRequest().path!!.contains(old))
            val oldestArquivo=server.takeRequest().requestUrl!!;assertEquals("/two-index",oldestArquivo.encodedPath);assertNull(oldestArquivo.queryParameter("sort"))
            assertTrue(server.takeRequest().path!!.contains(old))
        }
    }

    @Test fun readableLegacyCaptureIsReachableWhenBothReturnedRecentRowsAreShells(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server);val second=provider(server,"two",true)
            val newest="20250101000000"
            server.enqueue(MockResponse().setBody(cdx(recent,newest)))
            server.enqueue(MockResponse().setBody("<noscript>JavaScript is not available</noscript>"))
            server.enqueue(MockResponse().setBody(""))
            server.enqueue(MockResponse().setBody(cdx(old)))
            server.enqueue(MockResponse().setBody(html("The readable legacy post")))
            val result=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}.recover(target)!!
            assertEquals("The readable legacy post",result.snapshot.body);assertTrue(result.archiveUrl.contains(old));assertEquals(5,server.requestCount)
            server.takeRequest();assertTrue(server.takeRequest().path!!.contains(newest))
            assertEquals("/two-index",server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("2",server.takeRequest().requestUrl!!.queryParameter("limit"));assertTrue(server.takeRequest().path!!.contains(old))
        }
    }

    @Test fun replayRateLimitStopsHistoricalRetriesAndKeepsItsCooldown(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server);val second=provider(server,"two",true);var time=now
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){time}
            server.enqueue(MockResponse().setBody(cdx(recent)));server.enqueue(MockResponse().setResponseCode(429));server.enqueue(MockResponse().setBody(""))
            assertEquals(HealthStatus.RATE_LIMITED,failure {reader.recover(target)}.status);assertEquals(3,server.requestCount)
            time+=61_000;server.enqueue(MockResponse().setBody(""))
            assertEquals(HealthStatus.RATE_LIMITED,failure {reader.recover(target)}.status);assertEquals(4,server.requestCount)
            server.takeRequest();server.takeRequest();repeat(2){assertEquals("/two-index",server.takeRequest().requestUrl!!.encodedPath)}
        }
    }

    @Test fun callerCancellationDuringOldestIndexDoesNotReadAnotherBody(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server)
            server.enqueue(MockResponse().setBody(cdx(recent)))
            server.enqueue(MockResponse().setBody("<noscript>JavaScript is not available</noscript>"))
            server.enqueue(MockResponse().setBody(cdx(old)).setBodyDelay(1,TimeUnit.SECONDS))
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first)){now}
            try {withTimeout(200){reader.recover(target)};fail("Cancellation must propagate during historical fallback")}catch(expected: TimeoutCancellationException){}
            delay(100);assertEquals(3,server.requestCount)
        }
    }

    @Test fun rateLimitCooldownIsSharedWithHistoryAndDoesNotBecomeAnEmptySuccess(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server);val second=provider(server,"two",true);var time=now
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){time}
            server.enqueue(MockResponse().setResponseCode(429));server.enqueue(MockResponse().setBody(""))
            assertEquals(HealthStatus.RATE_LIMITED,failure {reader.recover(target)}.status)
            time+=61_000;server.enqueue(MockResponse().setBody(""))
            val post=Post("20",Platform.X,target.canonicalUrl,"jack","A complete current post")
            assertEquals(HealthStatus.RATE_LIMITED,failure {reader.oldest(post)}.status)
            assertEquals(3,server.requestCount)
            assertTrue(server.takeRequest().path!!.startsWith("/one-index"));repeat(2){assertTrue(server.takeRequest().path!!.startsWith("/two-index"))}
        }
    }

    @Test fun callerCancellationStopsBeforeAnyAlternateArchiveAndTorFailsClosed(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val first=provider(server);val second=provider(server,"two",true)
            val gateway=NetworkGateway(cleaner,permit,true)
            server.enqueue(MockResponse().setBody(cdx(recent)).setBodyDelay(1,TimeUnit.SECONDS))
            val reader=ArchiveReader(gateway,routes,listOf(first,second)){now}
            try {withTimeout(100){reader.recover(target)};fail("Caller timeout must propagate")}catch(expected: TimeoutCancellationException){}
            delay(100);assertEquals(1,server.requestCount)
            gateway.configure(NetworkPolicy(tor=true,ready=false))
            assertEquals(HealthStatus.UNAVAILABLE,failure {ArchiveReader(gateway,routes,listOf(first,second)){now}.recover(target)}.status)
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun genericPagesCommentsAndBinaryMediaAreRejectedBeforeNetwork(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(provider(server))){now}
            val unsupported=listOf(
                routes.target(Platform.X,"jack"),routes.search(Platform.X,"jack"),
                Target(Platform.REDDIT,TargetKind.POST,"comment","https://www.reddit.com/r/android/comments/abc/title/def"),
                Target(Platform.X,TargetKind.MEDIA,"image","https://pbs.twimg.com/media/actual.jpg"),
                Target(Platform.X,TargetKind.POST,"fake","https://foreign.example/jack/status/20")
            )
            for(input in unsupported)assertEquals(input.canonicalUrl,HealthStatus.UNSUPPORTED,failure {reader.recover(input)}.status)
            assertEquals(0,server.requestCount)
        }
    }
}
