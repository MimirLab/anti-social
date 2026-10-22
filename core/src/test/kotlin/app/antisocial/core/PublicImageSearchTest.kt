package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PublicImageSearchTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    private fun result(engine: ImageSearchEngine,body: String)=PublicImageSearch.extract(engine,Payload(body,when(engine) {
        ImageSearchEngine.BING->"https://www.bing.com/images/search?insightsToken=bcid_image"
        ImageSearchEngine.GOOGLE_LENS->"https://www.google.com/search?vsrid=image&udm=26"
        ImageSearchEngine.YANDEX->"https://yandex.com/images/search?cbir_id=image&rpt=imageview"
        ImageSearchEngine.SAUCENAO->"https://saucenao.com/search.php?hide=3"
    },"text/html",200))

    @Test fun yandexUsesOnlyImageSiteCardsAndPreservesIndependentUploaders() {
        val found=result(ImageSearchEngine.YANDEX,"""<nav><a href="https://x.com/yandex">Our account</a></nav><script>const cbir={url:"https://x.com/scriptnoise"};</script><li class="CbirSites-Item"><div class="CbirSites-ItemTitle"><a href="https://x.com/alice/status/42">Image occurrence</a></div><a class="CbirSites-ItemDomain" href="https://x.com/alice/status/42">x.com</a></li><li class="CbirSites-Item"><div class="CbirSites-ItemTitle"><a href="https://www.reddit.com/r/test/comments/42/title/">Different uploader</a></div></li><footer><a href="https://www.facebook.com/yandex">Facebook</a></footer>""")
        assertEquals(listOf("https://x.com/alice/status/42","https://www.reddit.com/r/test/comments/42/title/"),found.urls)
    }
    @Test fun bingUsesPageDestinationsInImageMetadataInsteadOfThumbnailsOrAds() {
        val found=result(ImageSearchEngine.BING,"""<a href="https://x.com/bing">Footer</a><a class="iusc" m='{"purl":"https://x.com/alice/status/1","murl":"https://cdn.example/image.jpg"}'></a><div class="b_ad"><a class="iusc" m='{"purl":"https://x.com/advert"}'></a></div>""")
        assertEquals(listOf("https://x.com/alice/status/1"),found.urls)
    }
    @Test fun lensUnwrapsOnlyThumbnailBearingResultCards() {
        val found=result(ImageSearchEngine.GOOGLE_LENS,"""<header><a href="https://x.com/google">Social</a></header><div class="Vd9M6"><a href="https://www.google.com/url?q=https%3A%2F%2Fwww.instagram.com%2Fp%2Fabc%2F"><img src="https://encrypted.google.com/thumb"></a></div><script>AF_initDataCallback({url:'https://x.com/not_an_image_match'})</script>""")
        assertEquals(listOf("https://www.instagram.com/p/abc/"),found.urls)
    }
    @Test fun sauceNaoReadsOnlySourceAttributionInsideRealImageResultTables() {
        val page="""<nav><a href="https://x.com/saucenao">Our account</a></nav><div class="result"><table class="resulttable"><tr><td class="resultimage"><img src="https://img.saucenao.com/image.jpg"></td><td><div class="resultsimilarityinfo">57.88%</div><div class="resultcontent"><div class="resultcontentcolumn"><a href="https://twitter.com/i/web/status/691265311232819200">Tweet ID</a><a href="https://twitter.com/i/user/217190768">Twitter user</a></div></div></td></tr></table></div><div class="resultcontent"><a href="https://x.com/chrome">Unrelated chrome</a></div><footer><a href="https://facebook.com/site">Our account</a></footer>"""
        assertEquals(listOf("https://twitter.com/i/web/status/691265311232819200","https://twitter.com/i/user/217190768"),result(ImageSearchEngine.SAUCENAO,page).urls)
    }
    @Test fun sauceNaoQuotaIsNotAnEmptySuccessfulImageSearch() {
        val failure=assertThrows(SourceFailure::class.java){result(ImageSearchEngine.SAUCENAO,"<main>Daily search limit exceeded</main>")}
        assertEquals(HealthStatus.RATE_LIMITED,failure.status)
    }
    @Test fun everyProviderRejectsChromeAndScriptOnlyPagesAsUnknown() {
        ImageSearchEngine.entries.forEach {engine->
            val failure=assertThrows(SourceFailure::class.java){result(engine,"""<nav><a href="https://x.com/site">Account</a></nav><footer><a href="https://facebook.com/site">Account</a></footer><script>AF_initDataCallback({sites:['https://reddit.com/user/site']});</script>""")}
            assertEquals(HealthStatus.UNKNOWN,failure.status)
        }
    }
    @Test fun publicResultsFromAnUnsupportedSiteAreStillReadableMatches() {
        assertEquals(listOf("https://example.org/gallery/earth"),result(ImageSearchEngine.YANDEX,"""<div class="CbirSites-ItemTitle"><a href="https://example.org/gallery/earth">Earth image</a></div>""").urls)
    }
    @Test fun challengeAndExplicitEmptyAreDistinct() {
        val challenge=assertThrows(SourceFailure::class.java){result(ImageSearchEngine.YANDEX,"<div id='captcha'>Please confirm you're not a robot</div>")}
        assertEquals(HealthStatus.CHALLENGE,challenge.status)
        val empty=result(ImageSearchEngine.GOOGLE_LENS,"<main>No matching images found</main>")
        assertTrue(empty.urls.isEmpty());assertNotNull(empty.notice)
    }
    @Test fun aProviderTextClassificationIsNotReverseImageMatches() {
        val failure=assertThrows(SourceFailure::class.java){PublicImageSearch.extract(ImageSearchEngine.BING,Payload("<a href='https://x.com/person'>A web search hit</a>","https://www.bing.com/search?q=logo&bcid=image","text/html",200))}
        assertEquals(HealthStatus.UNKNOWN,failure.status)
    }
    @Test fun yandexContinuationUsesItsActualSitesPagerAndKeepsTheSameImage() {
        val page="""<div class="CbirSites-ItemTitle"><a href="https://x.com/alice/status/42">Match</a></div>"""
        val pager="""<div class="page-layout_page_cbir-sites serp-controller" data-bem='{"serp-controller":{"nextPageUrl":"/images/search?cbir_id=image&amp;cbir_page=sites&amp;p=1"}}'></div>"""
        assertEquals("https://yandex.com/images/search?cbir_id=image&cbir_page=sites&p=1",result(ImageSearchEngine.YANDEX,page+pager).next)
        assertNull(result(ImageSearchEngine.YANDEX,page+pager.replace("cbir_id=image","cbir_id=different")).next)
        assertNull(result(ImageSearchEngine.YANDEX,page+"""<a rel="next" href="https://yandex.ru/images/search?cbir_id=image&amp;p=1">Next</a>""").next)
    }
    @Test fun yandexRefusalTriesItsOtherPublicOriginAndPrefersOnlyRealMatches(): Unit=runBlocking {
        val pool=YandexImageRoutePool();val visited=mutableListOf<YandexImageRoute>()
        val result=pool.search {route->visited+=route;if(route.host=="yandex.com")throw SourceFailure(HealthStatus.UNAVAILABLE,"Source returned 403");ImageSearchLinks(listOf("https://x.com/alice/status/42"))}
        assertEquals(2,visited.size);assertEquals("yandex.com",visited[0].host);assertEquals("yandex.ru",visited[1].host);assertEquals("/images/touch/search",visited[1].uploadPath)
        assertEquals(1,result.urls.size);assertEquals("yandex.ru",pool.routes().first().host)
    }
    @Test fun yandexChallengeCanTryItsOtherKnownPublicOriginOnce(): Unit=runBlocking {
        val pool=YandexImageRoutePool();val visited=mutableListOf<YandexImageRoute>()
        assertEquals(1,pool.search {route->visited+=route;if(route.host=="yandex.com")throw SourceFailure(HealthStatus.CHALLENGE,"Human challenge");ImageSearchLinks(listOf("https://x.com/alice/status/42"))}.urls.size)
        assertEquals(2,visited.size);assertEquals(YandexImageRoute("yandex.ru","/images/touch/search"),visited.last())
    }
    @Test fun exhaustedYandexPoolIsAFailureAndDoesNotRecordHealth(): Unit=runBlocking {
        val pool=YandexImageRoutePool();var attempts=0
        val failure=assertThrows(SourceFailure::class.java){runBlocking {pool.search {attempts++;throw SourceFailure(HealthStatus.CHALLENGE,"Human challenge")}}}
        assertEquals(2,attempts);assertEquals(HealthStatus.CHALLENGE,failure.status);assertEquals("yandex.com",pool.routes().first().host)
    }
    @Test fun providerRateLimitStopsWithoutUploadingToItsOtherHostname(): Unit=runBlocking {
        val pool=YandexImageRoutePool();var attempts=0
        val failure=assertThrows(SourceFailure::class.java){runBlocking {pool.search {attempts++;throw SourceFailure(HealthStatus.RATE_LIMITED,"Source is rate limited")}}}
        assertEquals(1,attempts);assertEquals(HealthStatus.RATE_LIMITED,failure.status)
    }
    @Test fun anExplicitEmptySearchDoesNotMakeTheAlternateRouteHealthy(): Unit=runBlocking {
        val pool=YandexImageRoutePool()
        val result=pool.search {route->if(route.host=="yandex.com")throw SourceFailure(HealthStatus.UNAVAILABLE,"Source returned 403");ImageSearchLinks(emptyList(),notice="No matching images")}
        assertTrue(result.urls.isEmpty());assertEquals("yandex.com",pool.routes().first().host)
    }
    @Test fun cancelledImageSearchDoesNotWalkTheAlternatePool(): Unit=runBlocking {
        val pool=YandexImageRoutePool();var attempts=0
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java){runBlocking {withTimeout(30){pool.search {attempts++;delay(1000);ImageSearchLinks(emptyList())}}}}
        assertEquals(1,attempts)
    }
    @Test fun yandexPoolDoesNotRetryPrivacyPolicyOrUnavailableTor(): Unit=runBlocking {
        for(message in listOf("Blocked by privacy filters: yandex.com","Original-site access is disabled","Tor is connecting. Requests are paused.")) {
            val pool=YandexImageRoutePool();var attempts=0
            assertThrows(IOException::class.java){runBlocking {pool.search {attempts++;throw IOException(message)}}}
            assertEquals(1,attempts)
        }
    }
    @Test fun alternateYandexResultKeepsItsOwnOriginAndImageForPagination() {
        val page="""<div class="CbirSites-ItemTitle"><a href="https://x.com/alice/status/42">Match</a></div><a class="CbirSitesList-MoreButton" href="/images/search?cbir_id=ru-image&amp;cbir_page=sites">View all</a>"""
        val result=PublicImageSearch.extract(ImageSearchEngine.YANDEX,Payload(page,"https://yandex.ru/images/search?cbir_id=ru-image","text/html",200))
        assertEquals("https://yandex.ru/images/search?cbir_id=ru-image&cbir_page=sites",result.next)
    }
    @Test fun continuationCannotLeaveItsEngineOrChangeToAnOrdinaryTextQuery(): Unit=runBlocking {
        val search=PublicImageSearch(NetworkGateway(cleaner,permit))
        for(engine in ImageSearchEngine.entries)for(cursor in listOf("https://attacker.example/search?cbir_id=image","http://yandex.com/images/search?cbir_id=image","https://user:secret@yandex.com/images/search?cbir_id=image","https://www.google.com/search?q=foo"))
            assertThrows(IllegalArgumentException::class.java){runBlocking {search.search(engine,ImageSearchInput(byteArrayOf(1)),cursor)}}
    }
    @Test fun imageSearchUploadsAndNewProviderDomainsFailClosedWithoutTor(): Unit=runBlocking {
        val gateway=NetworkGateway(cleaner,permit)
        for(url in listOf("https://yandex.com/images/search","https://yandex.ru/images/search","https://tineye.com/search","https://lens.google.com/search","https://saucenao.com/search.php"))
            assertThrows(IOException::class.java){runBlocking {gateway.text(url)}}
        assertThrows(IOException::class.java){runBlocking {gateway.imageUpload("https://upload.example/search","upfile",byteArrayOf(1),"image/jpeg")}}
        assertEquals(0,gateway.protectionStats().checked)
    }

    private data class UploadRequest(val remoteHost: String,val headers: String,val body: String)
    /** A minimal SOCKS5 fixture proves remote DNS and explicit Tor routing for
     * a non-search-domain upload, without contacting a real search engine. */
    private fun withUpload(response: String,check: (NetworkGateway,CompletableFuture<UploadRequest>)->Unit) {
        ServerSocket(0,1,InetAddress.getLoopbackAddress()).use {socks->
            socks.soTimeout=6000
            val received=CompletableFuture<UploadRequest>()
            val worker=Thread {
                try {socks.accept().use {socket->
                    socket.soTimeout=6000
                    val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                    assertEquals(5,input.readUnsignedByte());repeat(input.readUnsignedByte()){input.readByte()};output.write(byteArrayOf(5,0));output.flush()
                    assertEquals(5,input.readUnsignedByte());assertEquals(1,input.readUnsignedByte());input.readByte();assertEquals(3,input.readUnsignedByte())
                    val host=ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8);input.readUnsignedShort();output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80));output.flush()
                    val bytes=java.io.ByteArrayOutputStream();var tail=0
                    while(tail!=0x0d0a0d0a){val value=input.readUnsignedByte();bytes.write(value);tail=(tail shl 8) or value}
                    val headers=bytes.toString("UTF-8");val length=Regex("Content-Length: (\\d+)",RegexOption.IGNORE_CASE).find(headers)!!.groupValues[1].toInt()
                    val body=ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
                    received.complete(UploadRequest(host,headers,body));output.write(response.toByteArray());output.flush()
                }}catch(e: Throwable){received.completeExceptionally(e)}
            }.apply {start()}
            val gateway=NetworkGateway(cleaner,permit,true);gateway.configureTor(NetworkPolicy(true,socks.localPort,true))
            try {check(gateway,received)}finally {worker.join(6500)}
        }
    }
    @Test fun multipartUsesRemoteDnsAndStripsCredentialsAndSourceMetadata() {
        withUpload("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\nSet-Cookie: account=secret\r\n\r\nok") {gateway,received->
            assertEquals("ok",runBlocking {gateway.imageUpload("http://upload.invalid/submit","upfile","selectedpixels".toByteArray(),"image/png",headers=mapOf("Cookie" to "private","Authorization" to "private","X-AntiSocial-Source" to "private"))}.text)
            val request=received.get(6,TimeUnit.SECONDS)
            assertEquals("upload.invalid",request.remoteHost);assertFalse(request.headers.contains("private"));assertFalse(request.headers.contains("Cookie:"));assertTrue(request.body.contains("name=\"upfile\"; filename=\"image.png\""));assertTrue(request.body.contains("selectedpixels"));assertTrue(request.headers.contains("multipart/form-data"))
            assertFalse(gateway.currentPolicy().tor)
        }
    }
    @Test fun crossOriginUploadBodyRedirectIsRejectedWithoutContactingDestination() {
        withUpload("HTTP/1.1 307 Temporary Redirect\r\nContent-Length: 0\r\nConnection: close\r\nLocation: http://other.invalid/upload\r\n\r\n") {gateway,received->
            assertThrows(IOException::class.java){runBlocking {gateway.imageUpload("http://upload.invalid/submit","upfile",byteArrayOf(1),"image/jpeg")}}
            assertEquals("upload.invalid",received.get(6,TimeUnit.SECONDS).remoteHost)
        }
    }
    @Test fun completedUpload303IsReturnedForAdapterValidationWithoutForwardingImage() {
        withUpload("HTTP/1.1 303 See Other\r\nContent-Length: 0\r\nConnection: close\r\nLocation: http://other.invalid/results?token=image\r\n\r\n") {gateway,received->
            val upload=runBlocking {gateway.imageUpload("http://upload.invalid/submit","upfile",byteArrayOf(1),"image/jpeg")}
            assertEquals(303,upload.status);assertEquals("http://other.invalid/results?token=image",upload.finalUrl);assertEquals("",upload.text);assertEquals("upload.invalid",received.get(6,TimeUnit.SECONDS).remoteHost)
        }
    }
}
