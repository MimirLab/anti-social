/* AntiSocial, GPL-3.0-or-later. Offline RSS/Atom fixtures; no external requests. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RssSourcesTest {
    private val feed="https://blog.example/feed.xml"
    private val target=RssSources.target(feed)
    private fun parse(body: String,finalUrl: String=feed)=RssSources.parse(Payload(body,finalUrl,"application/xml",200),target,123)
    private fun rss(items: String,extra: String="")="""<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Maker's Blog</title><description>A public blog</description>$extra$items</channel></rss>"""
    private fun item(id: String="one",body: String="Body")="""<item><guid isPermaLink="false">$id</guid><title>Post $id</title><description>$body</description></item>"""
    private fun atom(items: String,attrs: String="",extra: String="")="""<feed xmlns="http://www.w3.org/2005/Atom" $attrs><title>Atom Blog</title><id>urn:blog:one</id>$extra$items</feed>"""
    private fun routes(): DarwinRoutes {
        fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
        return DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    }
    @Test fun feedUrlsAreExplicitCanonicalPublicHttpsWithoutCredentials() {
        assertEquals("https://blog.example/feed.xml?a=1",RssSources.feedUrl(" https://BLOG.EXAMPLE:443/feed.xml?a=1 "))
        assertEquals(target,RssSources.target(feed))
        assertEquals(RssSources.followId(feed),RssSources.followId("https://BLOG.EXAMPLE:443/feed.xml"))
        assertEquals(68,RssSources.followId(feed).length)
        for(url in listOf("http://blog.example/feed","ftp://blog.example/feed","https://user:secret@blog.example/feed","https://blog.example/feed#token","https://127.0.0.1/feed","https://[::1]/feed","https://192.168.1.1/feed","https://localhost/feed","https://host.local/feed","https://host.internal/feed","https://blog.example/\nfeed"))assertNull(url,RssSources.feedUrl(url))
        assertNotNull(RssSources.feedUrl("https://feeds.example:8443/rss"))
    }
    @Test fun ordinaryWebLinksNeverBecomeSocialTargetsOrGlobalFeedAliases() {
        val routes=routes()
        assertNull(routes.recognize("https://blog.example/an-article"))
        assertEquals(target,routes.target(Platform.RSS,feed))
        assertThrows(IllegalArgumentException::class.java){routes.search(Platform.RSS,"words")}
        assertTrue(SearchScopes.options(Platform.RSS).isEmpty())
        assertFalse(SocialLinkHosts.hosts.containsKey(Platform.RSS))
    }
    @Test fun rssUsesFeedAndItemFieldsRatherThanNestedAuthorTitlesAndDates() {
        val body=rss("""<item><title>Real title</title><link>/posts/one</link><dc:creator>Jane</dc:creator><pubDate>Fri, 16 Oct 2026 12:00:00 GMT</pubDate><content:encoded><![CDATA[<p>Full <b>body</b>.</p><script>bad()</script><a href="../guide">Guide</a>]]></content:encoded><description>Short summary</description><source><title>Wrong title</title><pubDate>1970-01-01</pubDate></source></item>""")
        val page=parse(body);val post=page.posts.single()
        assertEquals("Maker's Blog",page.profile!!.name);assertEquals("A public blog",page.profile!!.description)
        assertEquals("Real title",post.title);assertEquals("Jane",post.author);assertEquals("Full body. Guide",post.body)
        assertEquals("https://blog.example/posts/one",post.canonicalUrl)
        assertEquals(listOf("https://blog.example/guide"),post.links)
        assertEquals(Extraction.timestamp("2026-10-16T12:00:00Z"),post.publishedAt)
        assertEquals(Platform.RSS,post.platform);assertEquals(123L,post.provenance!!.fetchedAt)
    }
    @Test fun atomAlternateLinksAndXhtmlContentRespectInheritedXmlBase() {
        val page=parse(atom("""<entry xml:base="posts/"><id>urn:item:one</id><title>First</title><author><name>Alex</name><uri>https://wrong.example/name</uri></author><published>2026-10-16T10:00:00Z</published><updated>2026-10-17T10:00:00Z</updated><link rel="self" href="one.xml"/><link rel="alternate" type="text/html" href="one"/><content type="xhtml" xml:base="../assets/"><div xmlns="http://www.w3.org/1999/xhtml"><p>Read <a href="manual">manual</a>.</p><img src="photo.jpg" alt="A photo"/></div></content></entry>""",attrs="xml:base=\"https://cdn.example/journal/\""))
        val post=page.posts.single()
        assertEquals("https://cdn.example/journal/posts/one",post.canonicalUrl)
        assertEquals("Alex",post.author);assertEquals("Read manual.",post.body)
        assertEquals("https://cdn.example/journal/assets/photo.jpg",post.media.single().url)
        assertEquals(listOf("https://cdn.example/journal/assets/manual"),post.links)
        assertEquals(Extraction.timestamp("2026-10-16T10:00:00Z"),post.publishedAt)
    }
    @Test fun atomTextConstructsStayLiteralAndDoNotCreateMediaOrLinks() {
        val post=parse(atom("""<entry><id>urn:one</id><title type="text">Using &lt;html&gt;</title><content>&lt;img src="https://tracker.example/pixel.jpg"/&gt; &lt;a href="https://tracker.example/click"&gt;literal&lt;/a&gt;</content></entry>""")).posts.single()
        assertEquals("Using <html>",post.title)
        assertTrue(post.body.contains("<img"));assertTrue(post.media.isEmpty());assertTrue(post.links.isNullOrEmpty())
    }
    @Test fun atomExternalContentIsNotFollowedAndSummaryAndFeedAuthorAreUsed() {
        val post=parse(atom("""<entry><id>urn:entry:1</id><title>Entry</title><content src="https://tracker.example/full" type="text/html"/><summary type="html">&lt;p&gt;Available summary&lt;/p&gt;</summary><updated>2026-10-16T10:00:00Z</updated></entry>""",extra="<author><name>Feed author</name></author>")).posts.single()
        assertEquals("Available summary",post.body);assertEquals("Feed author",post.author)
        assertFalse(post.links.orEmpty().contains("https://tracker.example/full"));assertTrue(post.canonicalUrl.startsWith(feed+"#antisocial-entry="))
    }
    @Test fun linklessGuidEntriesKeepStableIndependentIdentitiesAcrossRefreshAndReordering() {
        val first=parse(rss(item("alpha")+item("beta"))).posts.associateBy {it.title}
        val second=parse(rss(item("beta","Edited body")+item("alpha"))).posts.associateBy {it.title}
        assertEquals(2,first.values.map {it.id}.distinct().size)
        assertEquals(first.getValue("Post beta").id,second.getValue("Post beta").id)
        assertEquals(first.getValue("Post alpha").id,second.getValue("Post alpha").id)
        assertNotEquals(first.getValue("Post alpha").id,first.getValue("Post beta").id)
        assertEquals(TargetKind.POST,RssSources.itemTarget(first.values.first()).kind)
    }
    @Test fun permalinkGuidAndFragmentsRetainRealEntryIdentity() {
        val posts=parse(rss("""<item><guid>https://blog.example/2026#one</guid><title>First</title></item><item><link>https://blog.example/2026#two</link><title>Second</title></item>""")).posts
        assertEquals(setOf("https://blog.example/2026#one","https://blog.example/2026#two"),posts.map {it.canonicalUrl}.toSet())
        assertEquals(2,posts.map {ContentIdentity.key(it.platform,it.canonicalUrl)}.distinct().size)
    }
    @Test fun feedLinksToSocialPagesRemainRssAttributionAndDoNotInventPlatformCopies() {
        val post=parse(rss("""<item><link>https://x.com/author/status/123</link><title>Blog round-up</title><description>Publisher supplied content</description></item>""")).posts.single()
        assertEquals(Platform.RSS,post.platform);assertEquals("https://x.com/author/status/123",post.canonicalUrl)
        assertEquals("RSS / Atom · x.com",SourcePresentation.label(post))
    }
    @Test fun mediaRssAndOrdinaryEnclosuresPreserveRecognizedImagesVideoAndAudio() {
        val media=parse(rss("""<item><title>Attachments</title><link>/post</link><media:content url="/clip.mp4" type="video/mp4" duration="610.25"/><enclosure url="/podcast.mp3" type="audio/mpeg" length="123"/><enclosure url="/photo.jpg" type="image/jpeg"/><enclosure url="/archive.zip" type="application/zip"/><media:thumbnail url="/poster.jpg"/></item>""")).posts.single().media
        assertEquals(listOf("video","audio","image","image"),media.map {it.type})
        assertEquals(610250L,media.first().durationMs);assertNull(media[1].durationMs)
    }
    @Test fun atomEnclosuresAndPrefixedAtomNamespaceAreRecognized() {
        val raw="""<atom:feed xmlns:atom="http://www.w3.org/2005/Atom"><atom:title>Blog</atom:title><atom:entry><atom:id>urn:one</atom:id><atom:title>Sound</atom:title><atom:link rel="enclosure" href="sound.ogg" type="audio/ogg"/></atom:entry></atom:feed>"""
        val post=parse(raw).posts.single();assertEquals("audio",post.media.single().type);assertEquals("https://blog.example/sound.ogg",post.media.single().url)
    }
    @Test fun rssOneRdfFeedsAreSupportedWithoutTreatingRdfAsAnArticle() {
        val page=parse("""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel rdf:about="$feed"><title>RDF Blog</title></channel><item rdf:about="https://blog.example/one"><title>RDF item</title><link>https://blog.example/one</link><description>Body</description><dc:date>2026-10-16T10:00:00Z</dc:date></item></rdf:RDF>""")
        assertEquals("RDF Blog",page.profile!!.name);assertEquals("RDF item",page.posts.single().title);assertNotNull(page.posts.single().publishedAt)
    }
    @Test fun feedsAreChronologicalWithoutInventingAnUnknownPublicationTime() {
        val page=parse(rss("""<item><title>Old</title><link>/old</link><pubDate>Fri, 16 Oct 2026 10:00:00 GMT</pubDate></item><item><title>Unknown</title><link>/unknown</link></item><item><title>New</title><link>/new</link><pubDate>Fri, 16 Oct 2026 12:00:00 GMT</pubDate></item>"""))
        assertEquals(listOf("New","Old","Unknown"),page.posts.map {it.title})
        assertEquals(listOf(Extraction.timestamp("2026-10-16T12:00:00Z"),Extraction.timestamp("2026-10-16T10:00:00Z")),page.posts.take(2).map {it.publishedAt})
        assertNull(page.posts.last().publishedAt)
    }
    @Test fun feedPagesAreBoundedAndNeverAutoFollowNextOrHubLinks() {
        val page=parse(atom((1..240).joinToString(""){"<entry><id>urn:item:$it</id><title>Post $it</title></entry>"},extra="""<link rel="next" href="https://other.example/page2"/><link rel="hub" href="https://push.example/subscribe"/>"""))
        assertEquals(200,page.posts.size);assertNull(page.next);assertTrue(page.posts.all {it.links.isNullOrEmpty()})
    }
    @Test fun htmlLoginPagesOtherXmlAndEmptyFeedsNeverMasqueradeAsWorkingContent() {
        listOf("<html><body><item><title>Log in</title></item></body></html>","<sitemap><entry><title>Other XML</title></entry></sitemap>","<feed xmlns=\"https://evil.example/namespace\"><entry><title>Wrong Atom</title></entry></feed>","<rss><item><title>No channel</title></item></rss>",rss(""),rss("<item><link>/empty</link></item>")).forEach {raw->assertThrows(raw.take(100),SourceFailure::class.java){parse(raw)}}
    }
    @Test fun declarationsAndUnsafeMediaAreRejectedWithoutResolvingExternalResources() {
        listOf("<!DOCTYPE rss SYSTEM 'https://tracker.example/entity'>"+rss(item()),"<!ENTITY x 'expansion'>"+rss(item())).forEach {raw->assertThrows(SourceFailure::class.java){parse(raw)}}
        val post=parse(rss("""<item><title>Safe</title><description><![CDATA[<img src="http://insecure.example/image.jpg"><img src="https://localhost/private.jpg"><a href="javascript:bad()">No</a>]]></description></item>""")).posts.single()
        assertTrue(post.media.isEmpty());assertTrue(post.links.isNullOrEmpty())
    }
    @Test fun selectedFeedTorModeFailsClosedBeforeAnyNetworkRequest(): Unit=runBlocking {
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision=error("Tor must fail before HTTP")})
        var requests=0;gateway.onNetworkEvent={requests++};gateway.configure(NetworkPolicy(tor=true,ready=false))
        val error=runCatching {SourcePool(gateway,routes()).externalFeed(Follow("rss",target,"Blog",feedUrl=feed))}.exceptionOrNull()
        assertNotNull(error);assertTrue(error!!.message.orEmpty().contains("Tor is connecting"));assertEquals(0,requests)
    }
    @Test fun selectedArticleDoesNotInitiateAnUnrequestedSiteOrDiscoveryFetch(): Unit=runBlocking {
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision=error("Cached article must not fetch")})
        var requests=0;gateway.onNetworkEvent={requests++}
        val pool=SourcePool(gateway,routes());val post=parse(rss(item())).posts.single()
        val error=runCatching {pool.load(RssSources.itemTarget(post))}.exceptionOrNull()
        assertTrue(error is SourceFailure);assertEquals(HealthStatus.UNSUPPORTED,(error as SourceFailure).status);assertEquals(0,requests)
        assertTrue(pool.sources(Platform.RSS,Capability.SEARCH,true).isEmpty())
    }
    @Test fun extractionDispatchUsesStrictFeedParserEvenWhenServerCallsItHtml() {
        val page=Extraction.parse(Payload(rss(item()),feed,"text/html",200),target,RssSources.source(feed),routes(),123)
        assertEquals("Maker's Blog",page.profile!!.name);assertEquals(Platform.RSS,page.posts.single().platform)
        assertThrows(SourceFailure::class.java){Extraction.parse(Payload("<h1>Please sign in</h1>",feed,"text/html",200),target,RssSources.source(feed),routes())}
    }
}
