/* anti social, GPL-3.0-or-later. Dated feeds and source-qualified copies. */
package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class FeedRepresentationTest {
    @Test fun anEscapedApostropheCannotManufactureAnInstagramTagDestination() {
        val p=Provenance(Source(Platform.INSTAGRAM,"rss-bridge","https://reader.example"),1793000000000,"https://reader.example/feed")
        val post=Post("123",Platform.INSTAGRAM,"https://www.instagram.com/p/FixtureOne/","fixture","We&#039;re opening the public workshop this Saturday. Meet the volunteers and inspect the equipment.",
            provenance=p,links=listOf("https://www.instagram.com/explore/tags/039"))
        assertTrue(PostPresentation.content(post).startsWith("We're"))
        assertTrue(CrossPosts.onlyOpaqueOrOriginalLinks(post))
        assertFalse(CrossPosts.onlyOpaqueOrOriginalLinks(post.copy(body=post.body+" #039")))
        assertFalse(CrossPosts.onlyOpaqueOrOriginalLinks(post.copy(links=listOf("https://www.instagram.com/explore/tags/workshop"))))
        assertFalse(CrossPosts.onlyOpaqueOrOriginalLinks(post.copy(provenance=p.copy(source=p.source.copy(frontend="original")))))
        assertTrue(post.body.contains("&#039;"))
    }
    @Test fun knownApiEntityEscapesArePresentedOnceAndOriginalTransportTextSurvives() {
        val p=Provenance(Source(Platform.X,"sotwe-api","https://reader.example"),1793000000000,"https://reader.example/api")
        val post=Post("1",Platform.X,"https://x.com/fixture/status/1","fixture","Tools &amp; supplies; use &amp;amp; for a literal example.",provenance=p)
        assertEquals("Tools & supplies; use &amp; for a literal example.",PostPresentation.content(post))
        assertEquals("Tools &amp; supplies; use &amp;amp; for a literal example.",post.body)
        assertEquals(post.body,PostPresentation.content(post.copy(provenance=p.copy(source=p.source.copy(frontend="original")))))
    }
    @Test fun theKnownBlueskyRendererHeaderDoesNotBecomePartOfTheCaptionOrRepeatItsTitle() {
        val p=Provenance(Source(Platform.BLUESKY,"rss-bridge","https://reader.example"),1793000000000,"https://reader.example/?bridge=Bluesky")
        val body="The maker collective @fixture.bsky.social posted: The workshop is open to visitors. Bring your questions and meet the volunteers."
        val post=Post("post:fixture.bsky.social:abc",Platform.BLUESKY,"https://bsky.app/profile/fixture.bsky.social/post/abc","fixture.bsky.social",body,title="The workshop is open to visitors.",provenance=p)
        assertEquals("The workshop is open to visitors. Bring your questions and meet the volunteers.",PostPresentation.content(post))
        assertEquals(body,post.body)
        assertTrue(PostPresentation.content(post.copy(provenance=p.copy(source=p.source.copy(frontend="original")))).contains("posted:"))
        val authored=post.copy(body="The maker collective @fixture.bsky.social posted: @fixture.bsky.social posted: This literal quotation belongs to the author.",title="")
        assertEquals("@fixture.bsky.social posted: This literal quotation belongs to the author.",PostPresentation.content(authored))
        assertTrue(PostPresentation.content(post.copy(body=body.replace("posted:","replied:"))).contains("replied:"))
    }
    private fun asset(name:String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    @Test fun aKittygramGenerationTimestampCannotDateAllPostsOrCompleteAFeedCheck() {
        val source=Source(Platform.INSTAGRAM,"kittygram","https://reader.example")
        val target=Target(Platform.INSTAGRAM,TargetKind.PROFILE,"fixture","https://www.instagram.com/fixture/")
        val xml="""<feed xmlns="http://www.w3.org/2005/Atom"><title>Posts by fixture</title><updated>2026-10-26T12:00:00Z</updated>
          <entry><title>Post by fixture: The workshop is open...</title><link rel="alternate" href="https://www.instagram.com/p/FixtureOne/"/><updated>2026-10-26T12:00:00Z</updated><content type="html">The workshop is open to the public.</content></entry>
          <entry><title>Post by fixture: ...</title><link rel="alternate" href="https://www.instagram.com/p/FixtureTwo/"/><updated>2026-10-26T12:00:00Z</updated><content type="html"></content></entry></feed>"""
        val page=Extraction.parse(Payload(text=xml,finalUrl="https://reader.example/fixture/atom.xml",contentType="application/atom+xml",status=200),target,source,routes)
        assertEquals(1,page.posts.size);assertNull(page.posts.single().publishedAt)
        assertTrue(FeedRefresh.posts(page,Platform.INSTAGRAM).isEmpty())
        val dated=Extraction.parse(Payload(text=xml.replace("<updated>2026-10-26T12:00:00Z</updated><content","<published>2026-10-20T12:00:00Z</published><content"),finalUrl="https://reader.example/fixture/atom.xml",contentType="application/atom+xml",status=200),target,source,routes)
        assertEquals(Extraction.timestamp("2026-10-20T12:00:00Z"),dated.posts.single().publishedAt)
        assertEquals(1,FeedRefresh.posts(dated,Platform.INSTAGRAM).size)
    }

    private val caption="The community workshop is opening its new public reading room this Saturday. Visitors can inspect the equipment, meet the volunteers, borrow the printed guide, and join the afternoon demonstration. Full access instructions and the published schedule are available on our website."
    private val provenance=Provenance(Source(Platform.REDDIT,"original","https://www.reddit.com",role=SourceRole.ORIGINAL),1793000000000,"https://www.reddit.com/user/fixture/.rss")
    private val first=Post("comment:abc:one",Platform.REDDIT,"https://www.reddit.com/r/workshops/comments/abc/public_workshop/one","/u/fixture",caption,title="/u/fixture on Public workshop",publishedAt=1793000000000,provenance=provenance,links=listOf("https://workshop.example/open-day"))
    private val second=first.copy(id="comment:def:two",canonicalUrl="https://www.reddit.com/r/u_fixture/comments/def/public_workshop/two",publishedAt=1793000020000)

    @Test fun aPublishersCompleteCommentCopiesShareOneReadingCardAndRetainBothThreads() {
        for(input in listOf(listOf(first,second),listOf(second,first))) {
            val groups=CrossPosts.group(input,emptyList())
            assertEquals(1,groups.size)
            assertEquals(setOf(first.canonicalUrl,second.canonicalUrl),groups.single().posts.map {it.canonicalUrl}.toSet())
            assertTrue(CrossPosts.sameContentCopies(groups.single().posts))
        }
    }
    @Test fun commentResponsesNeedAnExactAuthorSourceBodyAndBoundedObservationWindow() {
        val changed=listOf(second.copy(author="/u/another"),second.copy(body=caption.replace("opening","closing")),
            second.copy(body="Yes."),second.copy(publishedAt=1793001000000),second.copy(parentId="parent"),
            second.copy(provenance=provenance.copy(finalUrl="https://www.reddit.com/r/workshops/.rss")),
            second.copy(links=listOf("https://workshop.example/different-day")))
        for(other in changed)assertEquals(other.toString(),2,CrossPosts.group(listOf(first,other),emptyList()).size)
    }
}
