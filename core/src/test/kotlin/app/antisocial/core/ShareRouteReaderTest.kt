/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ShareRouteReaderTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private val routes: DarwinRoutes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val source=Source(Platform.X,"nitter","https://nitter.example")
    private val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})
    private val now=1_792_000_000_000L
    private val post=Post("123",Platform.X,"https://x.com/alice/status/123","Alice","A readable exact post.",publishedAt=now)
    private val candidate=ShareRouteCandidate(post,"https://nitter.example/alice/status/123",ShareRouteKind.FRONTEND,source)
    private val reader: ShareRouteReader=ShareRouteReader(gateway,routes,cleaner){now}
    init {routes.registerPathwayAliases(listOf(source))}
    private fun html(id: String="123"): String="""<html><title>Public reader</title><body><div class="main-tweet"><a class="tweet-link" href="/alice/status/$id"></a><div class="tweet-content">A readable exact post.</div></div></body></html>"""
    private fun payload(body: String,url: String=candidate.url,type: String="text/html",status: Int=200): Payload=Payload(body,url,type,status)

    @Test fun exactBodyReadRatherThanReachabilityEstablishesReadableFrontend() {
        val result=reader.inspect(payload(html()),candidate)
        assertEquals(ShareRouteStatus.READABLE,result.status);assertEquals(candidate.url,result.finalUrl)
        assertEquals(ShareRouteStatus.EMPTY,reader.inspect(payload("<h1>Reader homepage</h1>"),candidate).status)
        assertNotEquals(ShareRouteStatus.READABLE,reader.inspect(payload(html("999")),candidate).status)
        assertNotEquals(ShareRouteStatus.READABLE,reader.inspect(payload(html(),"https://nitter.example/alice/status/999"),candidate).status)
    }
    @Test fun loginAndDeletionAreDifferentFromAnEmptyOrTransportResponse() {
        assertEquals(ShareRouteStatus.LOGIN_REQUIRED,reader.inspect(payload("<title>Log in to continue</title>"),candidate).status)
        assertEquals(ShareRouteStatus.LOGIN_REQUIRED,reader.inspect(payload("<h1>Sign in</h1>","https://nitter.example/login"),candidate).status)
        assertEquals(ShareRouteStatus.NOT_FOUND,reader.inspect(payload("<div class=error-panel>This post was deleted</div>"),candidate).status)
        assertEquals(ShareRouteStatus.NOT_FOUND,reader.inspect(payload("",status=410),candidate).status)
        assertEquals(ShareRouteStatus.UNVERIFIED,reader.inspect(payload("",status=503),candidate).status)
    }
    @Test fun apiRssAndGenericSuccessShellsAreNotShareableHtmlProof() {
        assertNotEquals(ShareRouteStatus.READABLE,reader.inspect(payload("{\"post\":\"A readable exact post\"}",type="application/json"),candidate).status)
        assertNotEquals(ShareRouteStatus.READABLE,reader.inspect(payload("<rss><channel><item><description>Text</description></item></channel></rss>",type="application/rss+xml"),candidate).status)
        assertNotEquals(ShareRouteStatus.READABLE,reader.inspect(payload(html(),candidate.url+"/rss"),candidate).status)
        assertNotEquals(ShareRouteStatus.READABLE,reader.inspect(payload("<meta property='og:url' content='${post.canonicalUrl}'><h1>Sign in to view</h1>"),candidate).status)
    }
    @Test fun archiveReplayRequiresMatchingOriginalAndExtractedPostContent() {
        val url="https://web.archive.org/web/20250101000000id_/https://x.com/alice/status/123"
        val archived=ShareRouteCandidate(post,url,ShareRouteKind.ARCHIVE)
        val body="""<html><title>Archived public post</title><article data-tweet-id="123"><div class="tweet-content">An archived complete post caption.</div></article></html>"""
        assertEquals(ShareRouteStatus.READABLE,reader.inspect(payload(body,url),archived).status)
        assertNotEquals(ShareRouteStatus.READABLE,reader.inspect(payload("<h1>Wayback Machine</h1><p>Loading...</p>",url),archived).status)
        assertNotEquals(ShareRouteStatus.READABLE,reader.inspect(payload(body,url.replace("123","999")),archived).status)
    }
    @Test fun selectedTorFailsClosedBeforeAnyRequestFilterOrDnsWork(): Unit=runBlocking {
        gateway.configure(NetworkPolicy(tor=true,ready=false))
        assertEquals(ShareRouteStatus.UNVERIFIED,reader.check(candidate).status)
        assertEquals(0L,gateway.protectionStats().checked)
    }
}
