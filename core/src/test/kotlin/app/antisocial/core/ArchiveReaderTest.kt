package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ArchiveReaderTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    private val now=1_792_146_400_000L
    private val post=Post("post:20",Platform.X,"https://x.com/jack/status/20","jack","A changed current post")
    private val provider=ArchiveProvider.defaults.first()
    private val capture=ArchiveCapture(provider,"20081103142857","http://twitter.com:80/jack/status/20",1_225_722_537_000L)
    // Minimal factual HTML excerpt from a public 2008 Wayback capture. Full response
    // and live gateway evidence live outside the checkout under archive-history-2026-10-16.
    private val legacy="""<html><head><title>Twitter / Jack Dorsey: just setting up my twttr</title></head><body class="status"><div id="permalink" class="hentry"><div class="desc"><div class="desc-inner"><p>just setting up my twttr</p><div class="meta"><span class="entry-meta">12:50 PM March 21, 2006 from web</span></div></div><div class="user-info"><div class="thumb"><a href="http://twitter.com/jack"><img src="http://s3.amazonaws.com/twitter_production/profile_images/example.png"></a></div><div class="screen-name"><a href="http://twitter.com/jack">jack</a></div></div></div></div></body></html>"""
    private fun replay(body: String=legacy,url: String=capture.replayUrl,p: Post=post)=ArchiveParsing.replay(Payload(body,url,"text/html",200),capture,p,routes,now)
    private fun cdx(original: String="http://twitter.com:80/jack/status/20",timestamp: String="20081103142857",status: String="200",mime: String="text/html")="""[["timestamp","original","statuscode","mimetype"],["$timestamp","$original","$status","$mime"]]"""

    private suspend fun failed(read: suspend ()->ArchivedVersion?): SourceFailure {
        try {read()}catch(e: SourceFailure){return e}
        throw AssertionError("Incomplete archive lookup must not become a confirmed negative")
    }
    @Test fun historicalTwitterAliasUsesOldIndexNameAndStableExactIdentity() {
        assertEquals("https://twitter.com/jack/status/20",ArchiveParsing.indexOriginal(post))
        assertEquals(1,ArchiveParsing.index(cdx(),provider,post,now).size)
        assertTrue(ArchiveParsing.index(cdx(original="https://evil.example/jack/status/20"),provider,post,now).isEmpty())
        assertTrue(ArchiveParsing.index(cdx(original="https://twitter.com/jack/status/21"),provider,post,now).isEmpty())
    }
    @Test fun oldestIndexRequestBoundsRawRowsWithoutRemoteDigestScanning() {
        val url=provider.indexUrl(ArchiveParsing.indexOriginal(post)).toHttpUrlOrNull()!!
        assertEquals("2",url.queryParameter("limit"));assertEquals("exact",url.queryParameter("matchType"))
        assertEquals("timestamp,original,statuscode,mimetype",url.queryParameter("fl"));assertNull(url.queryParameter("collapse"))
        val duplicated="""[["timestamp","original","statuscode","mimetype"],["20081103142857","http://twitter.com:80/jack/status/20","200","text/html"],["20081103142857","http://twitter.com:80/jack/status/20","200","text/html"]]"""
        assertEquals(1,ArchiveParsing.checkedIndex(Payload(duplicated,provider.indexUrl(post.canonicalUrl),"application/json",200),provider,post,now).size)
    }
    @Test fun invalidCaptureDatesStatusesMimeAndCredentialedOriginalsNeverBecomeCandidates() {
        for(body in listOf(cdx(timestamp="20260230120000"),cdx(timestamp="20990101000000"),cdx(status="302"),cdx(mime="image/png"),cdx(original="https://user:password@twitter.com/jack/status/20")))assertTrue(body,ArchiveParsing.index(body,provider,post,now).isEmpty())
    }
    @Test fun arquivoNdjsonFieldsAreDistinctFromWaybackArrayAndOldestIsSorted() {
        val p=ArchiveProvider.defaults[1]
        val lines="""{"timestamp":"20211104014340","url":"https://twitter.com/jack/status/20","status":"200","mime":"text/html"}
            |{"timestamp":"20210323183740","url":"https://twitter.com/jack/status/20","status":"200","mime":"text/html"}""".trimMargin()
        val parsed=ArchiveParsing.index(lines,p,post,now);assertEquals(2,parsed.size);assertEquals("20210323183740",parsed.first().timestamp)
    }
    @Test fun realLegacyPermalinkYieldsBodyAndArchiveOnlyAvatar() {
        val old=replay()!!;assertEquals("Internet Archive",old.provider);assertEquals("just setting up my twttr",old.snapshot.body);assertEquals("jack",old.snapshot.author)
        assertTrue(old.snapshot.avatar!!.startsWith("https://web.archive.org/web/20081103142857id_/https://s3.amazonaws.com/"))
        assertNull(old.snapshot.publishedAt) // The old page has no reliable timezone.
    }
    @Test fun liveRedirectWrongPostForeignArchiveAndNewerThanCurrentCaptureAreRejected() {
        assertNull(replay(url="https://twitter.com/jack/status/20"));assertNull(replay(url="https://web.archive.org/web/20081103142857id_/https://twitter.com/jack/status/21"))
        assertNull(replay(url="https://evil.example/web/20081103142857id_/https://twitter.com/jack/status/20"))
        assertNull(replay(p=post.copy(provenance=Provenance(Source(Platform.X,"nitter","https://nitter.example"),1L,post.canonicalUrl))))
    }
    @Test fun knownJavascriptShellLoginChallengeAndOpenGraphOnlyMetadataAreNotBodies() {
        for(body in listOf("<noscript>JavaScript is not available. Please enable JavaScript</noscript>","<html><title>Just a moment</title><article><div class=tweet-text>fake</div></article></html>","<p>Log in to continue</p>","<link rel=canonical href='https://twitter.com/jack/status/20'><meta property=og:description content='A truncated description'><meta property=og:image content='https://pbs.twimg.com/media/abc.jpg'>"))assertNull(replay(body))
    }
    @Test fun structuredBodyMustNameExactPostAndMustNotBeDescriptionOrEllipsized() {
        fun json(url: String,field: String,body: String)="""<script type="application/ld+json">{"@type":"SocialMediaPosting","url":"$url","$field":"$body","author":{"name":"Jack"}}</script>"""
        assertEquals("Original structured text",replay(json(post.canonicalUrl,"articleBody","Original structured text"))!!.snapshot.body)
        assertNull(replay(json("https://twitter.com/jack/status/21","articleBody","Unrelated body")))
        assertNull(replay(json(post.canonicalUrl,"description","Metadata only")))
        assertNull(replay(json(post.canonicalUrl,"articleBody","Truncated…")))
    }
    @Test fun unrelatedRecommendationsAndNestedRepliesCannotStandInForRequestedPost() {
        val wrong="""<article><a href="https://twitter.com/other/status/21">other post</a><p class=tweet-content>Unrelated content</p></article>"""
        assertNull(replay(wrong))
        val right="""<article><a href="https://twitter.com/jack/status/20">exact post</a><p class=tweet-content>The actual body</p><div class=replies><p class=tweet-content>Some reply</p></div></article>"""
        assertEquals("The actual body",replay(right)!!.snapshot.body)
    }
    @Test fun requestedPermalinkInsideQuotedOrBodyTextIsNotTheOuterPostsIdentity() {
        val outer="""<article><a href="https://twitter.com/other/status/21">other post</a><p class=tweet-content>Unrelated <a href="https://twitter.com/jack/status/20">message link</a></p><div class=quote-tweet><a href="https://twitter.com/jack/status/20">quoted requested post</a></div></article>"""
        assertNull(replay(outer))
    }
    @Test fun exactMediaOnlyPostIsUsableWithoutInventingTextFromMetadata() {
        val body="""<article data-tweet-id="20"><div class=attachments><img src="https://pbs.twimg.com/media/actual.jpg"></div></article>"""
        val version=replay(body)!!;assertEquals("",version.snapshot.body);assertEquals(1,version.snapshot.media.size)
    }
    @Test fun archivedArtworkAndPreviewsNeverExposeOriginalNetworkUrls() {
        val body="""<article data-tweet-id="20"><p class=tweet-content>The body</p><div class=attachments><img src="http://pbs.twimg.com/media/original.jpg"></div><video src="https://video.twimg.com/original.mp4" poster="https://pbs.twimg.com/media/preview.jpg"></video></article>"""
        val old=replay(body)!!;assertEquals(2,old.snapshot.media.size)
        old.snapshot.media.forEach {assertTrue(it.url.startsWith("https://web.archive.org/web/20081103142857id_/https://"));it.preview?.let {preview->assertTrue(preview.startsWith("https://web.archive.org/"))}}
    }
    @Test fun oldestLookupUsesTwoBoundedReadOnlyIndexesAndReusesProviderCooldown(): Unit=runBlocking {
        MockWebServer().use {server->server.start()
            val origin=server.url("/").toString().trimEnd('/')
            val first=ArchiveProvider("one","One",origin,"/one-index","/one/")
            val second=ArchiveProvider("two","Two",origin,"/two-index","/two/",true)
            server.enqueue(MockResponse().setBody(cdx()))
            server.enqueue(MockResponse().setBody("""{"timestamp":"20210323183740","url":"https://twitter.com/jack/status/20","status":"200","mime":"text/html"}"""))
            server.enqueue(MockResponse().setBody(legacy))
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}
            assertEquals("One",reader.oldest(post)!!.provider);assertEquals(HealthStatus.UNAVAILABLE,failed {reader.oldest(post)}.status);assertEquals(3,server.requestCount)
            repeat(3){val request=server.takeRequest();assertEquals("GET",request.method);assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"));assertFalse(request.path!!.contains("save"))}
        }
    }
    @Test fun archive429BackoffSurvivesOrdinarySpacingAndDoesNotBlockOtherProvider(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val origin=server.url("/").toString().trimEnd('/');var time=now
            val first=ArchiveProvider("one","One",origin,"/one-index","/one/")
            val second=ArchiveProvider("two","Two",origin,"/two-index","/two/",true)
            server.enqueue(MockResponse().setResponseCode(429));server.enqueue(MockResponse().setBody(""))
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){time}
            assertEquals(HealthStatus.RATE_LIMITED,failed {reader.oldest(post)}.status);time+=61_000;server.enqueue(MockResponse().setBody(""));assertEquals(HealthStatus.RATE_LIMITED,failed {reader.oldest(post)}.status);assertEquals(3,server.requestCount)
            assertTrue(server.takeRequest().path!!.startsWith("/one-index"));assertTrue(server.takeRequest().path!!.startsWith("/two-index"));assertTrue(server.takeRequest().path!!.startsWith("/two-index"))
        }
    }
    @Test fun confirmedNotFoundRequiresEveryConfiguredIndexToReturnValidEmptyData(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val origin=server.url("/").toString().trimEnd('/')
            val first=ArchiveProvider("one","One",origin,"/one-index","/one/")
            val second=ArchiveProvider("two","Two",origin,"/two-index","/two/",true)
            server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody(""))
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}
            assertNull(reader.oldest(post));assertEquals(2,server.requestCount)
        }
    }
    @Test fun validEmptyIndexAndUnavailableOtherProviderIsIncompleteCoverageNotNotFound(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val origin=server.url("/").toString().trimEnd('/')
            val first=ArchiveProvider("one","One",origin,"/one-index","/one/")
            val second=ArchiveProvider("two","Two",origin,"/two-index","/two/",true)
            server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setResponseCode(503))
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}
            assertEquals(HealthStatus.UNAVAILABLE,failed {reader.oldest(post)}.status)
            assertEquals(HealthStatus.UNAVAILABLE,failed {reader.oldest(post)}.status);assertEquals(2,server.requestCount)
        }
    }
    @Test fun malformedIndexAndUnusableReplayAreFailuresRatherThanNegativeResults(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val origin=server.url("/").toString().trimEnd('/')
            val first=ArchiveProvider("one","One",origin,"/one-index","/one/")
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first)){now}
            server.enqueue(MockResponse().setBody("<html>A reachable archive shell</html>"))
            assertEquals(HealthStatus.UNAVAILABLE,failed {reader.oldest(post)}.status)
            val other=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first)){now}
            server.enqueue(MockResponse().setBody(cdx()));server.enqueue(MockResponse().setBody("<noscript>JavaScript is not available</noscript>"))
            assertEquals(HealthStatus.UNAVAILABLE,failed {other.oldest(post)}.status);assertEquals(3,server.requestCount)
        }
    }
    @Test fun usableReplayStillWinsWhenOtherProviderIsTemporarilyUnavailable(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val origin=server.url("/").toString().trimEnd('/')
            val first=ArchiveProvider("one","One",origin,"/one-index","/one/")
            val second=ArchiveProvider("two","Two",origin,"/two-index","/two/",true)
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""{"timestamp":"20210323183740","url":"https://twitter.com/jack/status/20","status":"200","mime":"text/html"}"""))
            server.enqueue(MockResponse().setBody(legacy))
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}
            assertEquals("Two",reader.oldest(post)!!.provider);assertEquals(3,server.requestCount)
        }
    }
    @Test fun onlyDocumentedEmptyIndexRepresentationsAreConfirmedNegatives() {
        fun parsed(body: String,p: ArchiveProvider=provider,type: String="application/json")=ArchiveParsing.checkedIndex(Payload(body,p.origin+p.indexPath,type,200),p,post,now)
        assertTrue(parsed("[]").isEmpty());assertTrue(parsed("[[\"timestamp\",\"original\",\"statuscode\",\"mimetype\"]]").isEmpty())
        assertTrue(parsed("",ArchiveProvider.defaults[1],"text/x-ndjson").isEmpty())
        for(body in listOf("", "{}", "<html></html>", "[[]]", cdx(original="https://twitter.com/jack/status/21")))assertThrows(SourceFailure::class.java){parsed(body)}
        assertThrows(SourceFailure::class.java){parsed("",ArchiveProvider.defaults[1],"text/html")}
    }
    @Test fun parentTimeoutStopsImmediatelyBeforeAnyAlternateProviderRequest(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val origin=server.url("/").toString().trimEnd('/')
            val first=ArchiveProvider("one","One",origin,"/one-index","/one/")
            val second=ArchiveProvider("two","Two",origin,"/two-index","/two/",true)
            server.enqueue(MockResponse().setBody(cdx()).setBodyDelay(1,TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody(""))
            val reader=ArchiveReader(NetworkGateway(cleaner,permit,true),routes,listOf(first,second)){now}
            try {withTimeout(100) {reader.oldest(post)};fail("Caller timeout must propagate")}catch(expected: TimeoutCancellationException) {}
            delay(100);assertEquals(1,server.requestCount)
        }
    }
    @Test fun selectedTorAndConcurrentLookupsDoNotIssueDirectOrDuplicateRequests(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val origin=server.url("/").toString().trimEnd('/');val p=ArchiveProvider("one","One",origin,"/index","/web/")
            val gateway=NetworkGateway(cleaner,permit,true);gateway.configure(NetworkPolicy(tor=true,ready=false));val reader=ArchiveReader(gateway,routes,listOf(p)){now}
            assertEquals(HealthStatus.UNAVAILABLE,failed {reader.oldest(post)}.status);assertEquals(0,server.requestCount)
            gateway.configure(NetworkPolicy());val other=ArchiveReader(gateway,routes,listOf(p)){now}
            server.enqueue(MockResponse().setBody(cdx()).setBodyDelay(150,TimeUnit.MILLISECONDS));server.enqueue(MockResponse().setBody(legacy))
            val pending=async {other.oldest(post)};delay(50);assertEquals(HealthStatus.UNAVAILABLE,failed {other.oldest(post)}.status);assertNotNull(pending.await());assertEquals(2,server.requestCount)
        }
    }
}
