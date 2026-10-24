/* anti social, GPL-3.0-or-later. Declared feed navigation and guarded consumption. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RssPaginationTest {
    private val feed="https://blog.example/feed.xml"
    private val target=RssSources.target(feed)
    private fun item(id:String)="<entry><id>urn:notice:$id</id><title>Notice $id</title><published>2026-10-23T12:00:00Z</published><content>Required equipment $id</content></entry>"
    private fun atom(id:String="2",head:String="",attrs:String="")="""<feed xmlns="http://www.w3.org/2005/Atom" $attrs><title>Notices</title>$head${item(id)}</feed>"""
    private fun parse(body:String=atom(),url:String=feed)=RssSources.parse(Payload(body,url,"application/atom+xml",200),target)
    private fun next(head:String="""<link rel="next" href="?page=2"/>""")=parse(atom(head=head))
    @Test fun aPublishedBloggerNextCanUseTheSameBlogsVerifiedHttpsFeedEndpoint() {
        val page=parse(atom(head="""<id>tag:blogger.com,1999:blog-123</id><link rel="next" href="http://www.blogger.com/feeds/123/posts/default?start-index=26&amp;max-results=25"/>"""))
        assertEquals("https://www.blogger.com/feeds/123/posts/default?start-index=26&max-results=25",page.next)
    }
    @Test fun onlyAnExplicitFeedScopedCompleteDeclarationRemovesTheWindowLimit() {
        val attrs="""xmlns:h="http://purl.org/syndication/history/1.0""""
        assertTrue(parse().limitedTimeline)
        assertFalse(parse(atom(head="<h:complete/>",attrs=attrs)).limitedTimeline)
        assertTrue(parse(atom(head="<complete/>")).limitedTimeline)
        assertTrue(parse(atom(head="<h:complete/>",attrs="""xmlns:h="https://wrong.example/history"""")).limitedTimeline)
        assertTrue(parse(atom(head="<H:complete/>",attrs="""xmlns:h="http://purl.org/syndication/history/1.0" xmlns:H="https://wrong.example/history"""")).limitedTimeline)
        assertTrue(parse(atom(head="<h:complete/>",attrs="""xmlns:H="http://purl.org/syndication/history/1.0" xmlns:h="https://wrong.example/history"""")).limitedTimeline)
        assertTrue(parse(atom(head="<h:Complete/>",attrs=attrs)).limitedTimeline)
        assertTrue(parse(atom(head="""<h:complete xmlns:h=""/>""",attrs=attrs)).limitedTimeline)
        assertTrue(parse(atom(head="<h:complete>Not an empty declaration</h:complete>",attrs=attrs)).limitedTimeline)
        assertFalse(parse(atom(head="<H:complete/>",attrs="""xmlns:H="http://purl.org/syndication/history/1.0"""")).limitedTimeline)
        assertTrue(parse(atom(head="<entry><h:complete/></entry>",attrs=attrs)).limitedTimeline)
        assertTrue(parse(atom(head="""<h:complete/><link rel="next" href="?page=2"/>""",attrs=attrs)).limitedTimeline)
    }
    @Test fun feedNavigationUsesNamespacedHeadLinksAndPrefersNextOverOlderArchive() {
        assertEquals("$feed?page=2",next().next)
        assertEquals("https://blog.example/history/one.xml",next("""<link rel="prev-archive" href="/history/one.xml"/>""").next)
        assertEquals("$feed?page=2",next("""<link rel="prev-archive" href="/archive"/><link rel="next" href="?page=2"/>""").next)
        assertNull(next("""<entry><link rel="next" href="/not-a-feed-page"/></entry>""").next)
        assertNull(next("""<x:link xmlns:x="https://wrong.example" rel="next" href="/not-a-feed-page"/>""").next)
        assertNull(next("""<link xmlns="" rel="next" href="?page=2"/>""").next)
        val rss="""<rss xmlns:a="http://www.w3.org/2005/Atom"><channel><a:link rel="prev-archive" href="/archive.xml"/><item><title>Readable notice</title></item></channel></rss>"""
        assertEquals("https://blog.example/archive.xml",parse(rss).next)
    }
    @Test fun xmlBasesAndQueryOnlyLinksKeepTheActualFeedPath() {
        val page=parse(atom(head="""<link xml:base="/history/part.xml" rel="next" href="?page=2"/>"""))
        assertEquals("https://blog.example/history/part.xml?page=2",page.next)
        val body=atom(head="""<link rel="next" href="?page=2"/>""",attrs="""xml:base="/history/part.xml"""")
        assertEquals("https://blog.example/history/part.xml?page=2",parse(body).next)
    }
    @Test fun unsafeOrUnscopedNavigationDoesNotBecomeAnAutomaticRequest() {
        for(url in listOf("https://elsewhere.example/page","https://blog.example:8443/page","http://blog.example/page","https://user:secret@blog.example/page","/page#fragment","/unsubscribe?token=abc","/account/delete?confirm=yes","javascript:alert(1)","https://127.0.0.1/page")) {
            val page=next("""<link rel="next" href="$url"/>""")
            assertNull(url,page.next);assertTrue(url,page.limitedTimeline)
        }
        assertTrue(AutomaticLinks.mayRead("https://blog.example/guides/account/delete"))
        assertTrue(AutomaticLinks.mayRead("https://blog.example/articles/how-to-delete-an-account"))
    }
    @Test fun parserTruncationCannotInheritACompleteClaim() {
        val body="""<feed xmlns="http://www.w3.org/2005/Atom" xmlns:h="http://purl.org/syndication/history/1.0"><h:complete/>${(1..201).joinToString(""){item(it.toString())}}</feed>"""
        val page=parse(body);assertEquals(200,page.posts.size);assertTrue(page.limitedTimeline)
    }
    @Test fun catchUpFollowsDeclaredPagesAndRetainsOneLogicalFeedIdentity():Unit=runBlocking {
        val first=next();val old=parse(atom("1")).posts.single();val requests=mutableListOf<String>()
        val result=FeedCatchUp().collect(Platform.RSS,setOf(CrossPosts.originalKey(old)),first={first},next={page->
            RssSources.continuePage(target,page){url,parent->
                requests+=url;assertEquals(feed,parent)
                Payload(atom("1"),url,"application/atom+xml",200)
            }
        })
        assertEquals(listOf("$feed?page=2"),requests);assertEquals(2,result.pages);assertNull(result.pendingReason)
        assertEquals(setOf("Notice 1","Notice 2"),result.page.posts.map {it.title}.toSet())
        assertEquals(old.canonicalUrl,result.page.posts.first {it.title=="Notice 1"}.canonicalUrl)
        assertEquals(target,result.page.profile!!.target)
    }
    @Test fun initialAndAlreadyOverlappingWindowsDoNotCrawlHistory():Unit=runBlocking {
        val first=next()
        for(head in listOf(emptySet(),setOf(CrossPosts.originalKey(first.posts.single())))) {
            val result=FeedCatchUp().collect(Platform.RSS,head,first={first},next={error("An already joined window must not request history")})
            assertEquals(1,result.pages);assertNull(result.pendingReason)
        }
    }
    @Test fun missingHistoryStillPreservesUsefulNewPagesWithoutCertifyingCatchUp():Unit=runBlocking {
        val first=next();val old=parse(atom("0")).posts.single()
        val result=FeedCatchUp().collect(Platform.RSS,setOf(CrossPosts.originalKey(old)),first={first},next={page->
            RssSources.continuePage(target,page){url,_->Payload(atom("1"),url,"application/atom+xml",200)}
        })
        assertEquals(2,result.page.posts.size);assertNotNull(result.pendingReason);assertNull(result.page.next)
    }
    @Test fun continuationsRejectAnotherSubscriptionCachedPagesAndActionTargetsBeforeReading():Unit=runBlocking {
        val page=next();var reads=0
        val bad=listOf(
            page.copy(profile=page.profile!!.copy(target=RssSources.target("https://blog.example/other.xml"))),
            page.copy(provenance=page.provenance!!.copy(cached=true)),
            page.copy(next="https://elsewhere.example/page"),
            page.copy(next="https://blog.example/unsubscribe?token=abc"))
        for(previous in bad) {
            assertTrue(runCatching {RssSources.continuePage(target,previous){_,_->reads++;error("Unsafe continuation was read")}}.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0,reads)
    }
    @Test fun redirectedOrPartialPagesCannotCloseTheGap():Unit=runBlocking {
        for((url,status)in listOf("https://elsewhere.example/page" to 200,"$feed?page=2" to 206)) {
            assertTrue(runCatching {RssSources.continuePage(target,next()){_,_->Payload(atom("1"),url,"application/atom+xml",status)}}.isFailure)
        }
    }
    @Test fun sourcePoolRoutesContinuationThroughTheProtectedSupplementalGateway():Unit=runBlocking {
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        var checked=0
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String):FilterDecision {checked++;assertEquals(feed,sourceUrl);return FilterDecision(true)}})
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
        val pool=SourcePool(gateway,routes);val follow=Follow("rss-test",target,"Notices",feedUrl=feed)
        val failed=runCatching {pool.continueExternalFeed(follow,next())}
        assertTrue(failed.isFailure);assertEquals(1,checked)
        val bad=runCatching {pool.continueExternalFeed(follow,next().copy(next="https://blog.example/unsubscribe?token=abc"))}
        assertTrue(bad.isFailure);assertEquals(1,checked)
    }
}
