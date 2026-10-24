/* anti social, GPL-3.0-or-later. Known public feed operation and identity checks. */
package app.antisocial.core

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BloggerFeedHistoryTest {
    private val marker="tag:blogger.com,1999:blog-123"
    private val origin="https://publisher.example/feeds/posts/default"
    private val target=RssSources.target(origin)
    private val next="https://www.blogger.com/feeds/123/posts/default?start-index=26&max-results=25"
    private fun body(id:String=marker,href:String="",entry:Int=2)="""<feed xmlns="http://www.w3.org/2005/Atom"><id>$id</id><title>Publisher</title>${if(href.isNotEmpty())"<link rel=\"next\" href=\"${href.replace("&","&amp;")}\"/>"else ""}<entry><id>urn:post:$entry</id><title>Notice $entry</title><content>Useful published instructions.</content></entry></feed>"""
    private fun page(id:String=marker,href:String=next)=RssSources.parse(Payload(body(id,href),origin,"application/atom+xml",200),target)
    @Test fun onlyTheKnownReadOnlyHttpsFeedOperationCanBeQualified() {
        assertEquals(next,BloggerFeedHistory.next(next.replace("https:","http:"),marker))
        assertEquals(next,BloggerFeedHistory.next(next,marker))
        assertEquals(next,BloggerFeedHistory.next(next.replace("www.blogger.com","blogger.com"),marker))
        for(raw in listOf(next.replace("/123/","/456/"),next.replace("www.blogger.com","www.blogger.com.evil.example"),next.replace("www.blogger.com","user:secret@www.blogger.com"),next.replace("www.blogger.com","www.blogger.com:444"),next.replace("posts/default","comments/default"),next+"&action=delete",next+"&start-index=50",next+"#fragment",next.replace("start-index=26","start-index=0"),next.replace("max-results=25","max-results=999"),next+"&alt=json-in-script"))assertNull(raw,BloggerFeedHistory.next(raw,marker))
    }
    @Test fun aPublicationMustSupplyItsMatchingNamespacedAtomIdentity() {
        assertEquals(marker,page().syndicationId);assertEquals(next,page().next)
        assertNull(page("tag:blogger.com,1999:blog-456").next)
        assertNull(page("unknown").next)
        val wrong=body().replace("<id>$marker</id>","""<x:id xmlns:x="https://wrong.example">$marker</x:id>""")
        assertNull(RssSources.parse(Payload(wrong,origin,"application/atom+xml",200),target).next)
    }
    @Test fun customDomainHistoryUsesTheProviderOriginAndRetainsTheLogicalSubscription():Unit=runBlocking {
        val first=page(href=next.replace("https:","http:"));var calls=0
        val second=RssSources.continuePage(target,first){url,scope->
            calls++;assertEquals(next,url);assertEquals(next,scope)
            Payload(body(entry=1),url,"application/atom+xml",200)
        }
        assertEquals(1,calls);assertEquals(marker,second.syndicationId);assertEquals(target,second.profile!!.target)
        assertEquals("https://www.blogger.com",second.provenance!!.source.root)
    }
    @Test fun wrongOrMissingReturnedIdentityLeavesTheAlreadyFetchedWindowPending():Unit=runBlocking {
        for(id in listOf("tag:blogger.com,1999:blog-456","unknown","")) {
            val first=page();val result=FeedCatchUp().collect(Platform.RSS,setOf("rss:missing-head"),first={first},next={previous->
                RssSources.continuePage(target,previous){url,_->Payload(body(id,entry=1),url,"application/atom+xml",200)}
            })
            assertEquals(first.posts,result.page.posts);assertNotNull(result.pendingReason)
        }
    }
    @Test fun redirectingBackToThePublisherDoesNotBypassThePinnedProviderOrigin():Unit=runBlocking {
        val failed=runCatching {RssSources.continuePage(target,page()){_,_->Payload(body(entry=1),origin,"application/atom+xml",200)}}
        assertTrue(failed.isFailure)
    }
    @Test fun aForgedProviderCursorFailsBeforeAnyRead():Unit=runBlocking {
        var calls=0
        for(cursor in listOf(next.replace("/123/","/456/"),next+"&token=unexpected",next.replace("posts/default","settings"))) {
            assertTrue(runCatching {RssSources.continuePage(target,page().copy(next=cursor)){_,_->calls++;error("Unqualified operation")}}.isFailure)
        }
        assertEquals(0,calls)
    }
    @Test fun oldPagesWithoutAnAtomIdentifierCannotAuthorizeACrossOriginProviderRead():Unit=runBlocking {
        val json=Gson().toJsonTree(page()).asJsonObject.apply {remove("syndicationId")}
        val old=Gson().fromJson(json,Page::class.java);assertNull(old.syndicationId)
        assertTrue(runCatching {RssSources.continuePage(target,old){_,_->error("No legacy permission to invent")}}.isFailure)
    }
    @Test fun anAmbiguousAtomIdentityCannotQualifyTheProvider() {
        val duplicate=body().replace("<id>$marker</id>","<id>$marker</id><id>$marker</id>")
        val parsed=RssSources.parse(Payload(duplicate,origin,"application/atom+xml",200),target)
        assertNull(parsed.syndicationId);assertNull(parsed.next)
    }
}
