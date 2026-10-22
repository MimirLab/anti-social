/* AntiSocial, GPL-3.0-or-later. Public protocol contract tests; no live requests. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.security.MessageDigest

class OtherDiscoveryAdaptersTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private fun target(platform: Platform,url: String,kind: TargetKind=TargetKind.PROFILE)=Target(platform,kind,url.substringAfterLast('/'),url)
    private fun gateway(server: MockWebServer)=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {
        val parsed=url.toHttpUrlOrNull()!!
        return FilterDecision(false,rewrittenUrl=server.url(parsed.encodedPath+(parsed.encodedQuery?.let {"?$it"}.orEmpty())).toString())
    }},true)
    private fun adapter(server: MockWebServer,platform: Platform,frontend: String,root: String=platform.origin,role: SourceRole=SourceRole.ORIGINAL)=otherDiscoveryAdapter(Source(platform,frontend,server.url("/").toString(),role=if(role==SourceRole.CACHE)SourceRole.CACHE else SourceRole.ALTERNATE),gateway(server),routes)!!
    private val account="""{"id":"7","username":"alice","acct":"alice","display_name":"Alice","url":"https://photos.example/alice"}"""
    private fun status(id: String="12",user: String="alice",extra: String="")="""{"id":"$id","url":"https://photos.example/p/$user/$id","visibility":"public","created_at":"2026-10-16T12:00:00Z","content":"<p>Public photo</p>","account":{"id":"7","username":"$user","display_name":"Alice"},"media_attachments":[{"type":"image","url":"https://media.example/a.jpg"}]$extra}"""
    private fun telegramFeed(channel: String="alice",items: String="""<item><link>https://t.me/alice/12</link><description>Public message</description><pubDate>Fri, 16 Oct 2026 12:00:00 +0000</pubDate></item>""")="""<rss version="2.0"><channel><title>Channel</title><link>https://t.me/s/$channel</link>$items</channel></rss>"""

    @Test fun pixelfedUsesAnonymousWebApiAndSelectedServer(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(account));server.enqueue(MockResponse().setBody("[${status()}]"))
            val reader=adapter(server,Platform.PIXELFED,"pixelfed-public-api","https://pixelfed.social")
            val page=reader.fetch(target(Platform.PIXELFED,"https://photos.example/alice"))
            assertEquals("Alice",page.profile!!.name);assertEquals(1,page.posts.size);assertEquals("Public photo",page.posts.single().body);assertEquals("https://photos.example/p/alice/12",page.posts.single().canonicalUrl)
            assertEquals("/api/v1/accounts/lookup?acct=alice",server.takeRequest().path)
            assertEquals("/api/pixelfed/v1/accounts/7/statuses?limit=20&exclude_replies=true",server.takeRequest().path)
            assertFalse(Capability.COMMENTS in reader.capabilities);assertNull(page.next)
        }
    }
    @Test fun pixelfedSelectedPostVerifiesAuthorAndExactId(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.PIXELFED,"pixelfed-public-api");val t=target(Platform.PIXELFED,"https://photos.example/p/alice/12",TargetKind.POST)
            server.enqueue(MockResponse().setBody("{\"status\":${status()}}"));assertEquals(1,reader.fetch(t).posts.size)
            assertEquals("/api/pixelfed/v2/profile/alice/status/12",server.takeRequest().path)
            server.enqueue(MockResponse().setBody("{\"status\":${status("99")}}"));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
            server.enqueue(MockResponse().setBody("{\"status\":${status(user="bob")}}"));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
        }
    }
    @Test fun pixelfedRejectsForeignAccountPrivatePostsAndShells(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.PIXELFED,"pixelfed-public-api");val t=target(Platform.PIXELFED,"https://photos.example/alice")
            server.enqueue(MockResponse().setBody(account.replace("\"username\":\"alice\"","\"username\":\"bob\"")));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
            assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody(account));server.enqueue(MockResponse().setBody("[${status().replace("\"public\"","\"private\"")}]"));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
            server.enqueue(MockResponse().setBody("<html><title>Pixelfed</title></html>"));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
        }
    }
    @Test fun pixelfedProfileLookupDoesNotNeedTimelineAndPaginationCannotChangeTarget(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.PIXELFED,"pixelfed-public-api");val t=target(Platform.PIXELFED,"https://photos.example/alice")
            server.enqueue(MockResponse().setBody(account));assertEquals("Alice",reader.profile(t).profile!!.name);assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody(account));server.enqueue(MockResponse().setBody((1..20).joinToString(",","[","]"){status(it.toString())}))
            val page=reader.fetch(t);assertNotNull(page.next);val before=server.requestCount
            assertThrows(IllegalArgumentException::class.java){runBlocking {reader.fetch(t.copy(canonicalUrl="https://photos.example/bob"),page.next)}};assertEquals(before,server.requestCount)
            server.enqueue(MockResponse().setBody("[${status("21")}]"));reader.fetch(t,page.next)
            while(server.requestCount>0){val request=server.takeRequest(10,java.util.concurrent.TimeUnit.MILLISECONDS) ?: break;if(request.path?.contains("max_id=20")==true){assertEquals("/api/pixelfed/v1/accounts/7/statuses?limit=20&exclude_replies=true&max_id=20",request.path);break}}
        }
    }
    @Test fun pixelfedAuthorlessPostIsUnsupportedWithoutRequest(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.PIXELFED,"pixelfed-public-api")
            val error=assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.PIXELFED,"https://photos.example/i/web/post/12",TargetKind.POST))}}
            assertEquals(HealthStatus.UNSUPPORTED,error.status);assertEquals(0,server.requestCount)
        }
    }
    @Test fun telegramRelayKeepsOnlySelectedChannelAndExactMessage(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.TELEGRAM,"telegram-rss","https://tg.i-c-a.su",SourceRole.ALTERNATE)
            val items="""<item><link>https://t.me/alice/12</link><description>Selected</description><pubDate>Fri, 16 Oct 2026 12:00:00 +0000</pubDate></item><item><link>https://t.me/bob/12</link><description>Other channel</description><pubDate>Fri, 16 Oct 2026 12:00:00 +0000</pubDate></item><item><link>https://t.me/alice/99</link><description>Other message</description><pubDate>Fri, 16 Oct 2026 12:00:00 +0000</pubDate></item>"""
            server.enqueue(MockResponse().setBody(telegramFeed(items=items)));val page=reader.fetch(target(Platform.TELEGRAM,"https://t.me/alice/12",TargetKind.POST))
            assertEquals("Selected",page.posts.single().body);assertEquals("post:alice:12",page.posts.single().id);assertNull(page.next)
            assertEquals("/rss/alice?id=12&limit=1",server.takeRequest().path)
        }
    }
    @Test fun telegramRelayRejectsWrongChannelAndUndatedEmptyFeeds(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.TELEGRAM,"telegram-rss","https://tg.i-c-a.su",SourceRole.ALTERNATE);val t=target(Platform.TELEGRAM,"https://t.me/alice")
            for(body in listOf(telegramFeed("bob"),telegramFeed(items=""),"<html>Sign in</html>",telegramFeed(items="<item><link>https://t.me/alice/12</link><description>Undated</description></item>"))) {
                server.enqueue(MockResponse().setBody(body));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
            }
        }
    }
    @Test fun telegramRelayPaginationIsBoundToSourceAndChannel(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.TELEGRAM,"telegram-rss","https://tg.i-c-a.su",SourceRole.ALTERNATE);val t=target(Platform.TELEGRAM,"https://t.me/alice")
            val items=(1..20).joinToString(""){"<item><link>https://t.me/alice/$it</link><description>Message $it</description><pubDate>Fri, 16 Oct 2026 12:00:00 +0000</pubDate></item>"}
            server.enqueue(MockResponse().setBody(telegramFeed(items=items)));val next=reader.fetch(t).next!!;assertEquals("/rss/alice?limit=20",server.takeRequest().path)
            assertThrows(IllegalArgumentException::class.java){runBlocking {reader.fetch(t.copy(canonicalUrl="https://t.me/bobby"),next)}};assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody(telegramFeed()));reader.fetch(t,next);assertEquals("/rss/alice/2?limit=20",server.takeRequest().path)
        }
    }
    @Test fun tiebaPublicClientIsCredentialFreeAndForumChecked(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.TIEBA,"tieba-client")
            val body="""{"error_code":"0","forum":{"name":"Android"},"user_list":[{"id":"5","name_show":"Alice"}],"thread_list":[{"id":"123","title":"Battery repair","author_id":"5","create_time":"1792152000","first_post_content":[{"type":"0","text":"Replace the battery"},{"type":"3","origin_src":"https://media.example/battery.jpg"}]}],"page":{"has_more":"0"}}"""
            server.enqueue(MockResponse().setBody(body));val page=reader.fetch(target(Platform.TIEBA,"https://tieba.baidu.com/f?kw=Android",TargetKind.COMMUNITY))
            assertEquals("Alice",page.posts.single().author);assertEquals("Replace the battery",page.posts.single().body);assertEquals(1,page.posts.single().media.size)
            val request=server.takeRequest();assertEquals("POST",request.method);assertEquals("/c/f/frs/page",request.path)
            val fields=request.body.readUtf8().split('&').associate {val p=it.split('=',limit=2);URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p[1],"UTF-8")}
            assertFalse(fields.containsKey("BDUSS"));assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"));assertEquals("000000000000000",fields["_phone_imei"])
            val raw=fields.filterKeys {it!="sign"}.toSortedMap().entries.joinToString(""){it.key+"="+it.value}+"tiebaclient!!!"
            assertEquals(MessageDigest.getInstance("MD5").digest(raw.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)},fields["sign"])
            server.enqueue(MockResponse().setBody(body.replace("\"name\":\"Android\"","\"name\":\"wrong\"")));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.TIEBA,"https://tieba.baidu.com/f?kw=Android",TargetKind.COMMUNITY))}}
        }
    }
    @Test fun tiebaThreadRequiresOriginalAndBindsReplyIdentity(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.TIEBA,"tieba-client");val t=target(Platform.TIEBA,"https://tieba.baidu.com/p/123",TargetKind.POST)
            val body="""{"error_code":"0","thread":{"id":"123","title":"Repair"},"user_list":[{"id":"5","name":"Alice"}],"post_list":[{"id":"700","floor":"1","author_id":"5","time":"1792152000","content":[{"type":"0","text":"Original"}]},{"id":"701","floor":"2","author_id":"5","time":"1792152060","content":[{"type":"0","text":"Reply"}]}]}"""
            server.enqueue(MockResponse().setBody(body));val page=reader.fetch(t)
            assertEquals("Original",page.posts.first().body);assertEquals("Repair",page.posts.first().title);assertEquals("Reply",Discussions.comments(page.posts.first(),page).single().body);assertEquals("post:123",page.posts.last().parentId)
            assertEquals("/c/f/pb/page",server.takeRequest().path)
            server.enqueue(MockResponse().setBody(body.replace("\"id\":\"123\"","\"id\":\"999\"")));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
            server.enqueue(MockResponse().setBody(body.replace("\"floor\":\"1\"","\"floor\":\"3\"")));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
        }
    }
    @Test fun unsupportedOperationsNeverReachAnyNewTransport(): Unit=runBlocking {
        MockWebServer().use {server->server.start()
            for((p,f) in listOf(Platform.TIEBA to "tieba-client",Platform.PIXELFED to "pixelfed-public-api",Platform.TELEGRAM to "telegram-rss")) {
                val error=assertThrows(SourceFailure::class.java){runBlocking {adapter(server,p,f).fetch(target(p,p.origin+"/search?q=hello",TargetKind.SEARCH))}}
                assertEquals(HealthStatus.UNSUPPORTED,error.status)
            }
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun allNewTransportsFailClosedWhenTorIsUnavailable(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val g=gateway(server);g.configure(NetworkPolicy(tor=true,ready=false))
            for((p,f,u) in listOf(Triple(Platform.PIXELFED,"pixelfed-public-api","https://photos.example/alice"),Triple(Platform.TELEGRAM,"telegram-rss","https://t.me/alice"),Triple(Platform.TIEBA,"tieba-client","https://tieba.baidu.com/f?kw=Android"))) {
                val reader=otherDiscoveryAdapter(Source(p,f,p.origin,role=SourceRole.ORIGINAL),g,routes)!!
                assertThrows(java.io.IOException::class.java){runBlocking {reader.fetch(target(p,u,if(p==Platform.TIEBA)TargetKind.COMMUNITY else TargetKind.PROFILE))}}
            }
            assertEquals(0,server.requestCount)
        }
    }
    private fun truthFeed(original: String="https://truthsocial.com/@realDonaldTrump/117454292438977074",originalId: String="117454292438977074",namespace: String="https://truthsocial.com/ns")="""<rss version="2.0" xmlns:truth="$namespace" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Trump’s Truth</title><link>https://www.trumpstruth.org</link><item><title>Image post</title><link>https://www.trumpstruth.org/statuses/42396</link><description>&lt;p&gt;Archived caption&lt;/p&gt;</description><dc:creator>Donald J. Trump</dc:creator><pubDate>Sat, 17 Oct 2026 04:11:12 +0000</pubDate><truth:originalUrl>$original</truth:originalUrl><truth:originalId>$originalId</truth:originalId><media:content url="https://archive.example/photo.jpg" medium="image"/></item></channel></rss>"""
    @Test fun truthArchiveIsActorScopedAndRetainsOriginalIdentityWithoutInventingCaptureDate(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.TRUTH_SOCIAL,"truth-archive-rss","https://www.trumpstruth.org",SourceRole.CACHE)
            server.enqueue(MockResponse().setBody(truthFeed()));val page=reader.fetch(target(Platform.TRUTH_SOCIAL,"https://truthsocial.com/@realDonaldTrump"))
            val post=page.posts.single();assertEquals("post:117454292438977074",post.id);assertEquals("https://truthsocial.com/@realDonaldTrump/117454292438977074",post.canonicalUrl);assertEquals("Archived caption",post.body);assertEquals(1,post.media.size)
            assertEquals(SourceRole.CACHE,post.provenance!!.source.role);assertTrue(post.provenance!!.cached);assertNull(post.recoveredArchive);assertTrue(page.notice!!.contains("no verified capture timestamps"));assertNull(page.next)
            assertEquals("/feed?limit=100",server.takeRequest().path)
            val wrong=target(Platform.TRUTH_SOCIAL,"https://truthsocial.com/@nasa");assertFalse(reader.accepts(wrong));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(wrong)}};assertEquals(1,server.requestCount)
        }
    }
    @Test fun truthArchiveRequiresNamespaceExactActorPostAndOriginalId(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.TRUTH_SOCIAL,"truth-archive-rss","https://www.trumpstruth.org",SourceRole.CACHE);val t=target(Platform.TRUTH_SOCIAL,"https://truthsocial.com/@realDonaldTrump/117454292438977074",TargetKind.POST)
            for(body in listOf(truthFeed(namespace="https://fake.example/ns"),truthFeed(original="https://truthsocial.com/@someoneElse/117454292438977074"),truthFeed(originalId="999"),truthFeed(original="https://truthsocial.com/@realDonaldTrump/999",originalId="999"))) {
                server.enqueue(MockResponse().setBody(body));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
            }
            server.enqueue(MockResponse().setBody(truthFeed()));assertEquals(1,reader.fetch(t).posts.size)
        }
    }
    private fun activityNote(extra: String="")="""{"@context":"https://www.w3.org/ns/activitystreams","id":"https://photos.example/p/alice/12","url":"https://photos.example/p/alice/12","type":"Note","attributedTo":"https://photos.example/users/alice","published":"2026-10-16T12:00:00Z","to":["https://www.w3.org/ns/activitystreams#Public"],"content":"<p>Photo caption</p>","attachment":[{"type":"Document","mediaType":"image/jpeg","url":"https://media.example/photo.jpg","name":"Alt text"}]$extra}"""
    @Test fun pixelfedActivityObjectUsesExactJsonRouteAndNoActorOrContextFetch(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.PIXELFED,"pixelfed-activitypub")
            server.enqueue(MockResponse().setBody(activityNote()));val t=target(Platform.PIXELFED,"https://photos.example/p/alice/12",TargetKind.POST);val page=reader.fetch(t)
            assertEquals("Photo caption",page.posts.single().body);assertEquals("alice",page.posts.single().author);assertEquals("Alt text",page.posts.single().media.single().description)
            assertEquals(setOf(Capability.POST,Capability.MEDIA),reader.capabilities);assertEquals(1,server.requestCount);assertEquals("/p/alice/12.json",server.takeRequest().path)
            assertFalse(reader.accepts(t.copy(kind=TargetKind.PROFILE,canonicalUrl="https://photos.example/alice")))
        }
    }
    @Test fun pixelfedActivityObjectRejectsWrongIdentityAudienceAndMediaOnlyBranding(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.PIXELFED,"pixelfed-activitypub");val t=target(Platform.PIXELFED,"https://photos.example/p/alice/12",TargetKind.POST)
            for(body in listOf(activityNote().replace("/users/alice","/users/bob"),activityNote().replace("/alice/12","/alice/99"),activityNote().replace("activitystreams#Public","activitystreams#Private"),activityNote().replace("\"Note\"","\"Person\""),activityNote().replace("https://photos.example/p/","https://foreign.example/p/"))) {
                server.enqueue(MockResponse().setBody(body));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
            }
        }
    }
    @Test fun selectedPixelfedOriginIsNeverReplacedByAnotherInstance() {
        val selected=target(Platform.PIXELFED,"https://photos.example/p/alice/12",TargetKind.POST)
        assertEquals("https://photos.example",otherDiscoveryRoot(Source(Platform.PIXELFED,"pixelfed-public-api","https://pixelfed.social",role=SourceRole.ORIGINAL),selected))
        assertEquals("https://proxy.example/pixel",otherDiscoveryRoot(Source(Platform.PIXELFED,"pixelfed-public-api","https://proxy.example/pixel"),selected))
    }
    @Test fun pixelfedCommentsUseNumericAccountAndExactDirectParent(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.PIXELFED,"pixelfed-public-comments");val t=target(Platform.PIXELFED,"https://photos.example/p/alice/12",TargetKind.POST)
            server.enqueue(MockResponse().setBody("{\"status\":${status()}}"));val reply=status("13","bob",",\"in_reply_to_id\":\"12\"")
            server.enqueue(MockResponse().setBody("{\"data\":[$reply,${status("14","bob",",\"in_reply_to_id\":\"999\"")},${status("15","bob",",\"in_reply_to_id\":\"12\"").replace("\"public\"","\"private\"")}]}"))
            val page=reader.fetch(t);assertEquals(2,page.posts.size);assertEquals("https://photos.example/p/bob/13",Discussions.comments(page.posts.first(),page).single().canonicalUrl)
            assertEquals(Capability.COMMENTS in reader.capabilities,true);assertFalse(reader.accepts(t.copy(kind=TargetKind.PROFILE,canonicalUrl="https://photos.example/alice")))
            assertEquals("/api/pixelfed/v2/profile/alice/status/12",server.takeRequest().path);assertEquals("/api/pixelfed/v2/comments/7/status/12?limit=20",server.takeRequest().path)
        }
    }
    @Test fun alternateRedirectCannotMasqueradeAsAWorkingRelay(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {second->first.start();second.start();first.enqueue(MockResponse().setResponseCode(302).setHeader("Location",second.url("/rss/alice")));second.enqueue(MockResponse().setBody(telegramFeed()))
            val g=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val reader=otherDiscoveryAdapter(Source(Platform.TELEGRAM,"telegram-rss",first.url("/").toString()),g,routes)!!;val e=assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.TELEGRAM,"https://t.me/alice"))}}
            assertEquals(HealthStatus.UNAVAILABLE,e.status)
        }}
    }
}
