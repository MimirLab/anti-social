package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PublicSocialAdaptersTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private fun obj(value: String)=JsonParser.parseString(value).asJsonObject
    private fun target(platform: Platform,url: String,kind: TargetKind=TargetKind.PROFILE)=Target(platform,kind,url.substringAfterLast('/'),url)
    private fun provenance(platform: Platform,root: String="https://social.example")=Provenance(Source(platform,"original",root,role=SourceRole.ORIGINAL),1,root)
    private fun gateway(server: MockWebServer)=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {
        val parsed=url.toHttpUrlOrNull()!!
        return FilterDecision(false,rewrittenUrl=server.url(parsed.encodedPath+(parsed.encodedQuery?.let {"?$it"}.orEmpty())).toString())
    }},true)
    private fun adapter(platform: Platform,frontend: String,root: String,server: MockWebServer)=publicSocialAdapter(Source(platform,frontend,root),gateway(server),routes)!!
    private fun status(id: String="12",extra: String="")="""{"id":"$id","url":"https://social.example/@alice/$id","created_at":"2026-10-16T12:00:00Z","visibility":"public","content":"<p>A public post</p>","account":{"id":"7","username":"alice","display_name":"Alice"}$extra}"""

    @Test fun mastodonPostsRejectPrivateAndBrandingOnlyData() {
        val p=provenance(Platform.MASTODON)
        val valid=PublicSocialParsing.mastodonStatus(obj(status()),Platform.MASTODON,p,"https://social.example")!!
        assertEquals("A public post",valid.body);assertEquals(1792152000000L,valid.publishedAt);assertEquals("Alice",valid.author)
        assertNull(PublicSocialParsing.mastodonStatus(obj(status().replace("\"public\"","\"private\"")),Platform.MASTODON,p,"https://social.example"))
        assertNull(PublicSocialParsing.mastodonStatus(obj("""{"id":"7","username":"alice","display_name":"Alice"}"""),Platform.MASTODON,p,"https://social.example"))
        assertNull(PublicSocialParsing.mastodonStatus(obj(status().replace("<p>A public post</p>","")),Platform.MASTODON,p,"https://social.example"))
    }
    @Test fun mastodonContextKeepsOnlyActualReplyRelationships(): Unit=runBlocking {
        MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setBody(status()))
            server.enqueue(MockResponse().setBody("""{"descendants":[${status("13",",\"in_reply_to_id\":\"12\"")},${status("14",",\"in_reply_to_id\":\"13\"")},${status("15",",\"in_reply_to_id\":\"unrelated\"")}] }"""))
            val t=target(Platform.MASTODON,"https://social.example/@alice/12",TargetKind.POST)
            val page=adapter(Platform.MASTODON,"mastodon-api","https://social.example",server).fetch(t)
            val comments=Discussions.comments(page.posts.first(),page)
            assertEquals(2,comments.size);assertEquals(page.posts.first().id,comments.first {it.canonicalUrl.endsWith("13")}.parentId)
            assertTrue(page.notice!!.contains("no platform popularity ranking"));assertNull(page.next)
        }
    }
    @Test fun mastodonPaginationIsBoundToTargetAndSource(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"id":"7","username":"alice","display_name":"Alice"}"""))
            server.enqueue(MockResponse().setBody((1..20).joinToString(",","[","]"){status(it.toString())}))
            val reader=adapter(Platform.MASTODON,"mastodon-api","https://social.example",server);val selected=target(Platform.MASTODON,"https://social.example/@alice")
            val first=reader.fetch(selected);assertNotNull(first.next);assertEquals(20,first.posts.size)
            assertThrows(IllegalArgumentException::class.java){runBlocking {reader.fetch(target(Platform.MASTODON,"https://social.example/@bob"),first.next)}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter(Platform.MASTODON,"mastodon-api","https://other.example",server).fetch(selected,first.next)}}
            server.enqueue(MockResponse().setBody("[${status("0")} ]"));reader.fetch(selected,first.next)
            server.takeRequest();server.takeRequest();val next=server.takeRequest();assertEquals("20",next.requestUrl!!.queryParameter("max_id"));assertEquals(3,server.requestCount)
        }
    }
    @Test fun mismatchingMastodonAccountNeverBecomesWorkingTimeline(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"id":"88","username":"wrong"}"""))
            assertThrows(SourceFailure::class.java){runBlocking {adapter(Platform.MASTODON,"mastodon-api","https://social.example",server).fetch(target(Platform.MASTODON,"https://social.example/@alice"))}}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun lemmyUsesSelectedServerIdsInsteadOfForeignActivitypubIds() {
        val value=obj("""{"posts":[{"post":{"id":9,"name":"Title","body":"Body","ap_id":"https://remote.example/post/123","published":"2026-10-16T12:00:00Z"},"creator":{"name":"alice"},"counts":{"comments":2,"score":4}}]}""")
        val posts=PublicSocialParsing.lemmyPosts(value,provenance(Platform.LEMMY),"https://lemmy.example")
        assertEquals("https://lemmy.example/post/9",posts.single().canonicalUrl);assertEquals(2L,posts.single().replyCount)
        val selected=target(Platform.LEMMY,"https://lemmy.example/post/9",TargetKind.POST)
        val comments=PublicSocialParsing.lemmyComments(obj("""{"comments":[{"comment":{"id":21,"post_id":9,"content":"Reply","path":"0.21"},"creator":{"name":"alice"}},{"comment":{"id":22,"post_id":9,"content":"Nested","path":"0.21.22"},"creator":{}},{"comment":{"id":23,"post_id":777,"content":"Unrelated"}}]}"""),selected,provenance(Platform.LEMMY),"https://lemmy.example",9)
        assertEquals(2,comments.size);assertEquals(comments.first().id,comments.last().parentId)
        assertEquals(ContentIdentity.key(Platform.LEMMY,selected.canonicalUrl),comments.first().parentId)
    }
    @Test fun gettrReadsFeedReferencesRatherThanEveryAuxiliaryObject() {
        val data=obj("""{"rc":"OK","result":{"data":{"list":[{"activity":{"pstid":"post2"}}]},"aux":{"post":{"post1":{"_id":"post1","txt":"Unrelated cached post","uid":"bob","vis":"p"},"post2":{"_id":"post2","txt":"Selected post","uid":"alice","vis":"p","cdate":1792152000000,"imgs":["photos/one.jpg"]}},"cursor":"opaque"}}}""")
        val posts=PublicSocialParsing.gettr(data,provenance(Platform.GETTR))
        assertEquals(1,posts.size);assertEquals("Selected post",posts.single().body);assertEquals("https://media.gettr.com/photos/one.jpg",posts.single().media.single().url)
        assertTrue(PublicSocialParsing.gettr(obj("""{"rc":"LOGIN_REQUIRED","result":{"data":{}}}"""),provenance(Platform.GETTR)).isEmpty())
    }
    @Test fun gabCompactSchemaResolvesAuthorsAndVideoAttachments() {
        val data=obj("""{"a":[{"i":"7","dn":"Alice","un":"alice"}],"ma":[{"i":"m","t":"video","smp4":"https://media.example/a.mp4","pu":"https://media.example/p.jpg"}],"s":[{"i":"12","ai":"7","ul":"https://gab.com/alice/posts/12","c":"<p>Public body</p>","v":"public","mai":["m"],"fc":3}]}""")
        val p=PublicSocialParsing.gab(data,provenance(Platform.GAB)).single()
        assertEquals("Alice",p.author);assertEquals("Public body",p.body);assertEquals("https://media.example/a.mp4",p.media.single().url);assertEquals("video",p.media.single().type)
    }
    @Test fun telegramRejectsCrossChannelCardsAndFiltersSelectedPost() {
        val markup="""<a class="tme_messages_more" data-before="1" href="/s/alice?before=10"></a><div class="tgme_widget_message" data-post="alice/11"><div class="tgme_widget_message_text">Hello<br>world</div><time datetime="2026-10-16T12:00:00Z"></time></div><div class="tgme_widget_message" data-post="bob/99"><div class="tgme_widget_message_text">Wrong channel</div></div><div class="tgme_widget_message" data-post="alice/12"><a class="tgme_widget_message_photo_wrap" style="background-image:url('https://media.example/a.jpg')"></a><time datetime="2026-10-16T13:00:00Z"></time></div>"""
        val page=PublicSocialParsing.telegram(markup,target(Platform.TELEGRAM,"https://t.me/alice"),provenance(Platform.TELEGRAM,"https://t.me/s/alice"))
        assertEquals(2,page.posts.size);assertTrue(page.posts.first().canonicalUrl.endsWith("12"));assertEquals("10",page.next);assertEquals("Hello\nworld",page.posts.last().body)
        val post=PublicSocialParsing.telegram(markup,target(Platform.TELEGRAM,"https://t.me/alice/11",TargetKind.POST),provenance(Platform.TELEGRAM,"https://t.me/alice/11"))
        assertEquals(1,post.posts.size);assertNull(post.next)
    }
    @Test fun pixelfedRejectsHtmlShellAndDoesNotAdvertiseArbitraryPostSupport(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("<html><title>Pixelfed</title></html>"))
            val reader=adapter(Platform.PIXELFED,"pixelfed-atom","https://pixel.example",server)
            assertFalse(Capability.POST in reader.capabilities);assertFalse(Capability.COMMENTS in reader.capabilities)
            assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.PIXELFED,"https://pixel.example/alice"))}}
        }
    }
    @Test fun pixelfedAtomProducesActualMediaAndRejectsWrongProfile(): Unit=runBlocking {
        MockWebServer().use {server->server.start()
            val atom="""<feed xmlns="http://www.w3.org/2005/Atom"><author><name>alice</name></author><entry><title>Photo</title><id>https://pixel.example/p/alice/99</id><link rel="alternate" href="https://pixel.example/p/alice/99"/><content type="html">&lt;p&gt;Photo body&lt;/p&gt;&lt;img src="https://media.example/p.jpg"/&gt;</content><updated>2026-10-16T12:00:00Z</updated></entry></feed>"""
            server.enqueue(MockResponse().setBody(atom));val reader=adapter(Platform.PIXELFED,"pixelfed-atom","https://pixel.example",server)
            val page=reader.fetch(target(Platform.PIXELFED,"https://pixel.example/alice"));assertEquals("alice",page.profile!!.name);assertEquals(1,page.posts.single().media.size)
            server.enqueue(MockResponse().setBody(atom.replace("<name>alice</name>","<name>wrong</name>")))
            assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.PIXELFED,"https://pixel.example/alice"))}}
        }
    }
    @Test fun misskeySkipsPrivateAndEmptyNotesWhileKeepingPublicRenotes() {
        val value=JsonParser.parseString("""[{"id":"empty","text":null,"visibility":"public"},{"id":"secret","text":"Private","visibility":"followers"},{"id":"renote","text":null,"renote":{"id":"original","text":"Public text","visibility":"public","user":{"username":"alice"},"createdAt":"2026-10-16T12:00:00Z"}}]""")
        val notes=PublicSocialParsing.misskey(value,provenance(Platform.MISSKEY),"https://miss.example")
        assertEquals(1,notes.size);assertEquals("Public text",notes.single().body);assertEquals("https://miss.example/notes/original",notes.single().canonicalUrl)
    }
    @Test fun unsupportedSearchDoesNotIssueRequests(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(Platform.TELEGRAM,"telegram-public","https://t.me",server)
            val error=assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.TELEGRAM,"https://t.me/search?q=test",TargetKind.SEARCH))}}
            assertEquals(HealthStatus.UNSUPPORTED,error.status);assertEquals(0,server.requestCount)
        }
    }
    @Test fun mastodonRssBackupRequiresMatchingFeedAndPostIdentity(): Unit=runBlocking {
        MockWebServer().use {server->server.start()
            val rss="""<rss version="2.0"><channel><title>Alice</title><link>https://social.example/@alice</link><item><link>https://social.example/@alice/123</link><description>Public body</description><pubDate>Fri, 16 Oct 2026 12:00:00 +0000</pubDate></item><item><link>https://social.example/@bob/777</link><description>Unrelated</description></item></channel></rss>"""
            val reader=adapter(Platform.MASTODON,"mastodon-rss","https://social.example",server);val t=target(Platform.MASTODON,"https://social.example/@alice")
            server.enqueue(MockResponse().setBody(rss));val page=reader.fetch(t);assertEquals(1,page.posts.size);assertEquals("Alice",page.profile!!.name);assertEquals("Public body",page.posts.single().body);assertFalse(Capability.POST in reader.capabilities);assertFalse(Capability.COMMENTS in reader.capabilities);assertNull(page.next)
            server.enqueue(MockResponse().setBody(rss.replace("<link>https://social.example/@alice</link>","<link>https://social.example/@bob</link>")))
            assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
        }
    }
    @Test fun gabSelectedPostUsesVerifiedStatusEndpointRatherThanCurrentFeed(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(status("12").replace("https://social.example/@alice/12","https://gab.com/alice/posts/12")))
            val page=adapter(Platform.GAB,"gab-api","https://gab.com",server).fetch(target(Platform.GAB,"https://gab.com/alice/posts/12",TargetKind.POST))
            assertEquals("A public post",page.posts.single().body);assertEquals("/api/v1/statuses/12",server.takeRequest().path);assertEquals(1,server.requestCount)
        }
    }
    @Test fun lemmyDirectCommentIdIsNotConfusedWithPostId(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(Platform.LEMMY,"lemmy-api","https://lemmy.example",server)
            val error=assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.LEMMY,"https://lemmy.example/comment/12",TargetKind.POST))}}
            assertEquals(HealthStatus.UNSUPPORTED,error.status);assertEquals(0,server.requestCount)
        }
    }

}
