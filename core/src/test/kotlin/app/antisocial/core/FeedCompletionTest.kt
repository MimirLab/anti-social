/* anti social, GPL-3.0-or-later. Explicit history evidence, not cursor guesses. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class FeedCompletionTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject))
    @Test fun genericHtmlWithoutPaginationCannotCertifyHistoryAndRemainsRetryable():Unit=runBlocking {
        val target=routes.target(Platform.X,"fixture")
        val source=Source(Platform.X,"unknown-reader","https://reader.example")
        val html="""<article class="post"><a href="https://x.com/fixture/status/105">Post</a><div class="post-content">A complete public announcement with instructions.</div><time datetime="2026-10-23T12:00:00Z"></time></article>"""
        var attempts=0
        repeat(2) {
            val result=FeedCatchUp().collect(Platform.X,setOf("twitter:post:100"),first={attempts++;Extraction.parse(Payload(html,source.root+"/fixture","text/html",200),target,source,routes)},next={error("No cursor may be invented")})
            assertEquals(1,result.page.posts.size);assertNotNull(result.pendingReason)
        }
        assertEquals(2,attempts)
    }
    @Test fun onlyTheQualifiedListingEndCanFinishAnAbsentPreviousHead():Unit=runBlocking {
        val target=routes.target(Platform.REDDIT,"r/fixture",TargetKind.COMMUNITY)
        val source=Source(Platform.REDDIT,"original","https://www.reddit.com",role=SourceRole.ORIGINAL)
        val post="""{"kind":"t3","data":{"id":"abc","name":"t3_abc","permalink":"/r/fixture/comments/abc/notice/","author":"fixture","title":"Workshop notice","created_utc":1792756800}}"""
        for(end in listOf(true,false)) {
            val body="""{"kind":"Listing","data":{"children":[$post]${if(end)",\"after\":null"else ""}}}"""
            val result=FeedCatchUp().collect(Platform.REDDIT,setOf("reddit:post:old"),first={Extraction.parse(Payload(body,"https://www.reddit.com/r/fixture/new.json","application/json",200),target,source,routes)},next={error("No supplied cursor")})
            assertEquals(1,result.page.posts.size)
            assertEquals(end,result.pendingReason==null)
        }
    }
    @Test fun aQualifiedEmptyNitterTerminalCanEndARealContinuation():Unit=runBlocking {
        val target=routes.target(Platform.X,"fixture");val source=Source(Platform.X,"nitter","https://reader.example")
        val ending="""<div class="timeline"><div class="timeline-item show-more"><a href="/fixture">Load newest</a></div><div class="timeline-footer"><h2 class="timeline-end">No more items</h2></div></div>"""
        val end=Extraction.parse(Payload(ending,source.root+"/fixture?cursor=OLDER","text/html",200),target,source,routes)
        assertNull(end.next);assertTrue(FeedRefresh.exhausted(end,Platform.X))
        val post=Post("105",Platform.X,"https://x.com/fixture/status/105","fixture","Public workshop notice",publishedAt=1792756800000,provenance=end.provenance)
        val result=FeedCatchUp().collect(Platform.X,setOf("twitter:post:100"),first={Page(listOf(post),next="owned-cursor",provenance=end.provenance)},next={end})
        assertNull(result.pendingReason);assertEquals(listOf(post),result.page.posts);assertEquals(2,result.pages)
        assertFalse(FeedRefresh.exhausted(end.copy(limitedTimeline=true),Platform.X))
        assertFalse(FeedRefresh.exhausted(end.copy(continuationIssue="Incomplete"),Platform.X))
        assertFalse(FeedRefresh.exhausted(end.copy(provenance=end.provenance!!.copy(cached=true)),Platform.X))
        assertFailsWith<SourceFailure>{Extraction.parse(Payload(ending.replace("timeline-end","timeline-none").replace("No more items","No items found"),source.root+"/fixture","text/html",200),target,source,routes)}
        assertFailsWith<SourceFailure>{Extraction.parse(Payload(ending,source.root+"/someoneelse","text/html",200),target,source,routes)}
        assertFailsWith<SourceFailure>{Extraction.parse(Payload(ending,source.root+"/fixture?q=unrelated","text/html",200),target,source,routes)}
        assertFailsWith<SourceFailure>{Extraction.parse(Payload(ending,source.root+"/fixture?cursor=one&cursor=two","text/html",200),target,source,routes)}
    }
    @Test fun aTerminalMarkerCannotHideAnUnparsedOrUndatedListingRow():Unit=runBlocking {
        val target=routes.target(Platform.REDDIT,"r/fixture",TargetKind.COMMUNITY)
        val source=Source(Platform.REDDIT,"original","https://www.reddit.com",role=SourceRole.ORIGINAL)
        val valid="""{"kind":"t3","data":{"id":"abc","permalink":"/r/fixture/comments/abc/notice/","author":"fixture","title":"Workshop notice","created_utc":1792756800}}"""
        for(other in listOf("""{"kind":"unknown","data":{}}""",valid.replace("abc","def").replace(",\"created_utc\":1792756800",""))) {
            val body="""{"kind":"Listing","data":{"children":[$valid,$other],"after":null}}"""
            val result=FeedCatchUp().collect(Platform.REDDIT,setOf("reddit:post:old"),first={Extraction.parse(Payload(body,source.root+"/r/fixture/new.json","application/json",200),target,source,routes)},next={error("No supplied cursor")})
            assertNotNull(result.pendingReason)
        }
    }
    @Test fun completeFeedDeclarationsRetainTheirQualifiedTerminalMeaning():Unit=runBlocking {
        val url="https://publisher.example/feed.xml";val target=RssSources.target(url)
        val item="""<item><guid>https://publisher.example/notice</guid><title>Public workshop</title><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item>"""
        for(marker in listOf("<fh:complete/>","","<wrong:complete/>")) {
            val body="""<rss version="2.0" xmlns:fh="http://purl.org/syndication/history/1.0" xmlns:wrong="urn:unrelated"><channel><title>Publisher</title>$marker$item</channel></rss>"""
            val result=FeedCatchUp().collect(Platform.RSS,setOf("retained-old-head"),first={RssSources.parse(Payload(body,url,"application/rss+xml",200),target)},next={error("No supplied cursor")})
            assertEquals(marker=="<fh:complete/>",result.pendingReason==null)
        }
        val partial="""<rss version="2.0" xmlns:fh="http://purl.org/syndication/history/1.0"><channel><title>Publisher</title><fh:complete/>$item<item/></channel></rss>"""
        val page=RssSources.parse(Payload(partial,url,"application/rss+xml",200),target)
        assertFalse(FeedRefresh.exhausted(page,Platform.RSS))
    }

}
