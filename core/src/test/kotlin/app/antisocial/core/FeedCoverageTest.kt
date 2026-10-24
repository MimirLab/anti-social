/* anti social, GPL-3.0-or-later. Feed-window coverage, not provider uptime. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FeedCoverageTest {
    private val url="https://blog.example/feed.xml"
    private val rssTarget=RssSources.target(url)
    private val xml="""<rss><channel><title>Public notices</title><link>https://x.com/NASA</link><item><title>Flight notice</title><link>https://x.com/NASA/status/105</link><description>Bring the required equipment.</description><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""
    private fun asset(name:String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private fun prior(platform:Platform,link:String)=CrossPosts.originalKey(Post(link,platform,link,"","Previous notice"))
    private suspend fun catchUp(platform:Platform,page:Page,old:String)=FeedCatchUp().collect(platform,setOf(prior(platform,old)),first={page},next={error("No advertised continuation was supplied")})

    @Test fun ordinarySlidingFeedsCannotCloseAnUnbridgedReadingWindow():Unit=runBlocking {
        val page=RssSources.parse(Payload(xml,url,"application/rss+xml",200),rssTarget)
        val result=catchUp(Platform.RSS,page,"https://blog.example/previous")
        assertNotNull("A finite RSS window must not certify missing history",result.pendingReason)
        assertEquals(page.posts,result.page.posts)
    }
    @Test fun ordinaryFeedsExposeDeclaredPaginationWithoutChangingTheirLogicalTarget() {
        val body=xml.replace("<channel>","""<channel xmlns:a="http://www.w3.org/2005/Atom"><a:link rel="next" href="?page=2"/>""")
        val page=RssSources.parse(Payload(body,url,"application/rss+xml",200),rssTarget)
        assertEquals("https://blog.example/feed.xml?page=2",page.next)
        assertEquals(rssTarget,page.profile!!.target)
    }
    @Test fun relaySnapshotsCannotCloseAnUnbridgedSocialReadingWindow():Unit=runBlocking {
        MockWebServer().use {server->
            server.start();repeat(2){server.enqueue(MockResponse().setHeader("Content-Type","application/rss+xml").setBody(xml))}
            val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
            val client=gateway.client()
            try {
                for(frontend in listOf("rsshub","rss-bridge")) {
                    val source=Source(Platform.X,frontend,server.url("/").toString())
                    val page=publicFeedPathwayAdapter(source,gateway,routes)!!.feed(routes.target(Platform.X,"NASA"))
                    assertNotNull(frontend,catchUp(Platform.X,page,"https://x.com/NASA/status/100").pendingReason)
                }
            }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    @Test fun genericSocialRssRetainsTheLimitedWindowContract():Unit=runBlocking {
        val source=Source(Platform.X,"nitter","https://reader.example")
        val page=Extraction.parse(Payload(xml,"https://reader.example/NASA/rss","application/rss+xml",200),routes.target(Platform.X,"NASA"),source,routes)
        assertNotNull(catchUp(Platform.X,page,"https://x.com/NASA/status/100").pendingReason)
    }
}
