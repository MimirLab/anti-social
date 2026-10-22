/* AntiSocial, GPL-3.0-or-later. Scope and request-contract fixtures, no live requests. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PublicFeedPathwayAdaptersTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"));private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private fun target(p: Platform,url: String,kind: TargetKind=TargetKind.PROFILE)=Target(p,kind,url.substringAfterLast('/'),url)
    private fun source(p: Platform,f: String,root: String="https://feed.example")=Source(p,f,root)
    private fun gateway()=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
    private fun reader(server: MockWebServer,p: Platform=Platform.X,f: String="rsshub")=publicFeedPathwayAdapter(source(p,f,server.url("/").toString()),gateway(),routes)!!
    private fun rss(link: String,items: String)="""<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><title>NASA</title><link>$link</link>$items</channel></rss>"""
    private fun item(url: String,body: String="Public caption",date: String="Fri, 16 Oct 2026 12:00:00 +0000",author: String="NASA")="""<item><link>$url</link><description>$body</description><pubDate>$date</pubDate><dc:creator>$author</dc:creator></item>"""
    @Test fun rsshubContractsUseOnlyExistingPublicRouteFamiliesAndChronologicalLemmy() {
        val rows=listOf(
            Triple(Platform.X,"https://x.com/NASA","/twitter/user/NASA"),Triple(Platform.BLUESKY,"https://bsky.app/profile/bsky.app","/bsky/profile/bsky.app"),
            Triple(Platform.TUMBLR,"https://staff.tumblr.com","/tumblr/posts/staff"),Triple(Platform.INSTAGRAM,"https://www.instagram.com/nasa","/instagram/user/nasa"),
            Triple(Platform.PINTEREST,"https://www.pinterest.com/nasa","/pinterest/user/nasa/_created"),Triple(Platform.THREADS,"https://www.threads.com/@nasa","/threads/nasa"),
            Triple(Platform.LINKEDIN,"https://www.linkedin.com/company/google","/linkedin/company/google/posts"),Triple(Platform.TIKTOK,"https://www.tiktok.com/@nasa","/tiktok/user/%40nasa/false"),
            Triple(Platform.GETTR,"https://gettr.com/user/joerogan","/gettr/user/joerogan"),Triple(Platform.GAB,"https://gab.com/AmericanFamAssc","/gab/user/AmericanFamAssc"),
            Triple(Platform.TELEGRAM,"https://t.me/telegram","/telegram/channel/telegram"),Triple(Platform.MASTODON,"https://mastodon.social/@Mastodon","/mastodon/acct/Mastodon%40mastodon.social/statuses"),
            Triple(Platform.MISSKEY,"https://misskey.io/@support","/misskey/users/notes/support%40misskey.io"))
        for((p,u,path) in rows)assertEquals(p.label,"https://feed.example$path",PublicFeedPathways.request(source(p,"rsshub"),target(p,u)))
        assertEquals("https://feed.example/lemmy/technology%40lemmy.world/New",PublicFeedPathways.request(source(Platform.LEMMY,"rsshub"),target(Platform.LEMMY,"https://lemmy.world/c/technology",TargetKind.COMMUNITY)))
        assertEquals("https://feed.example/baidu/tieba/forum/Android/created",PublicFeedPathways.request(source(Platform.TIEBA,"rsshub"),target(Platform.TIEBA,"https://tieba.baidu.com/f?kw=Android",TargetKind.COMMUNITY)))
    }
    @Test fun rssbridgeContractsUseExactContextAndParameterNames() {
        val rows=listOf(Triple(Platform.X,"https://x.com/NASA",mapOf("bridge" to "Twitter","context" to "By username","u" to "NASA")),Triple(Platform.BLUESKY,"https://bsky.app/profile/bsky.app",mapOf("bridge" to "Bluesky","user_id" to "bsky.app")),Triple(Platform.GETTR,"https://gettr.com/user/joerogan",mapOf("bridge" to "Gettr","user" to "joerogan","limit" to "20")),Triple(Platform.TELEGRAM,"https://t.me/telegram",mapOf("bridge" to "Telegram","username" to "@telegram")),Triple(Platform.MASTODON,"https://mastodon.social/@Mastodon",mapOf("bridge" to "Mastodon","canusername" to "@Mastodon@mastodon.social","signaturetype" to "nosig")),Triple(Platform.PINTEREST,"https://www.pinterest.com/nasa/space",mapOf("bridge" to "Pinterest","context" to "By username and board","u" to "nasa","b" to "space")),Triple(Platform.FACEBOOK,"https://www.facebook.com/NASA",mapOf("bridge" to "Facebook","context" to "User","u" to "NASA")),Triple(Platform.INSTAGRAM,"https://www.instagram.com/nasa",mapOf("bridge" to "Instagram","context" to "Username","u" to "nasa")),Triple(Platform.TIKTOK,"https://www.tiktok.com/@nasa",mapOf("bridge" to "TikTok","context" to "By user","username" to "@nasa")),Triple(Platform.GAB,"https://gab.com/alice",mapOf("bridge" to "Gab","username" to "alice")))
        for((p,u,expected) in rows){val request=PublicFeedPathways.request(source(p,"rss-bridge"),target(p,u))!!.toHttpUrlOrNull()!!;expected.forEach {(k,v)->assertEquals("$p $k",v,request.queryParameter(k))};assertEquals("Atom",request.queryParameter("format"));assertEquals("display",request.queryParameter("action"))}
        val reddit=PublicFeedPathways.request(source(Platform.REDDIT,"rss-bridge"),target(Platform.REDDIT,"https://www.reddit.com/r/Android",TargetKind.COMMUNITY))!!.toHttpUrlOrNull()!!;assertEquals("new",reddit.queryParameter("d"));assertEquals("single",reddit.queryParameter("context"))
    }
    @Test fun unsupportedShapesAndForeignTargetsNeverProduceNetworkDestinations() {
        assertNull(PublicFeedPathways.request(source(Platform.PINTEREST,"rss-bridge"),target(Platform.PINTEREST,"https://www.pinterest.com/nasa")))
        assertNull(PublicFeedPathways.request(source(Platform.X,"rsshub"),target(Platform.X,"https://x.com/NASA/status/12",TargetKind.POST)))
        assertNull(PublicFeedPathways.request(source(Platform.X,"rsshub"),target(Platform.X,"https://foreign.example/NASA")))
        assertNull(PublicFeedPathways.request(source(Platform.X,"rsshub"),target(Platform.X,"https://x.com:8443/NASA")))
        assertNull(PublicFeedPathways.request(source(Platform.X,"rsshub"),target(Platform.X,"https://user:secret@x.com/NASA")))
        assertNull(PublicFeedPathways.request(source(Platform.X,"rsshub"),target(Platform.X,"http://x.com/NASA")))
        assertNull(PublicFeedPathways.request(source(Platform.LINKEDIN,"rsshub"),target(Platform.LINKEDIN,"https://www.linkedin.com/in/someone")))
        assertNull(PublicFeedPathways.request(source(Platform.MASTODON,"rsshub"),target(Platform.MASTODON,"https://private.local/@alice")))
        assertNull(PublicFeedPathways.request(source(Platform.TRUTH_SOCIAL,"rsshub"),target(Platform.TRUTH_SOCIAL,"https://truthsocial.com/@realDonaldTrump")))
    }
    @Test fun publicXmlFeedProducesDatedNativeChronologicalCardsAndNoPagination(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=reader(server);server.enqueue(MockResponse().setBody(rss("https://twitter.com/NASA",item("https://x.com/NASA/status/12")+item("https://x.com/NASA/status/13",date="Sat, 17 Oct 2026 12:00:00 +0000")+item("https://x.com/other/status/99"))))
            val page=reader.fetch(target(Platform.X,"https://x.com/NASA"));assertEquals(2,page.posts.size);assertTrue(page.posts.first().canonicalUrl.endsWith("13"));assertEquals("post:13",page.posts.first().id);assertNull(page.next);assertNull(page.profile);assertEquals(setOf(Capability.TIMELINE,Capability.FEED),reader.capabilities)
            assertEquals("/twitter/user/NASA",server.takeRequest().path)
        }
    }
    @Test fun wrongChannelUndatedPostsShellAndExternalEntitiesNeverBecomeHealthyFeeds(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=reader(server);val t=target(Platform.X,"https://x.com/NASA")
            for(body in listOf(rss("https://x.com/other",item("https://x.com/NASA/status/12")),rss("https://x.com/NASA",item("https://x.com/NASA/status/12",date="")),"<html><title>Welcome to RSSHub</title></html>","<!DOCTYPE rss [<!ENTITY x SYSTEM 'https://foreign.example/secret'>]>"+rss("https://x.com/NASA",item("https://x.com/NASA/status/12")),rss("https://x.com/NASA",""))) {
                server.enqueue(MockResponse().setBody(body));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
            }
        }
    }
    @Test fun disabledBridgeIsUnsupportedAndDoesNotAttemptAnotherRequest(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=reader(server,Platform.TELEGRAM,"rss-bridge");server.enqueue(MockResponse().setBody("<html><h1>Bridge is not whitelisted</h1></html>"))
            val error=assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.TELEGRAM,"https://t.me/telegram"))}}
            assertEquals(HealthStatus.UNSUPPORTED,error.status);assertTrue(error.message!!.contains("Feed pathway disabled"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun bridgeRootOnlyChannelNeedsExactEntryActorAndCannotUseTitleAsIdentity(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=reader(server,Platform.GETTR,"rss-bridge");val t=target(Platform.GETTR,"https://gettr.com/user/alice")
            server.enqueue(MockResponse().setBody(rss("https://gettr.com",item("https://gettr.com/post/one",author="alice")+item("https://gettr.com/post/two",author="bob"))))
            assertEquals("https://gettr.com/post/one",reader.fetch(t).posts.single().canonicalUrl);val request=server.takeRequest().requestUrl!!;assertEquals("alice",request.queryParameter("user"));assertNull(request.queryParameter("username"))
            server.enqueue(MockResponse().setBody(rss("https://gettr.com",item("https://gettr.com/post/two",author="bob"))))
            assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(t)}}
        }
    }
    @Test fun atomNamespaceFeedAndSelectedFederatedServerArePreserved(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=reader(server,Platform.MISSKEY);server.enqueue(MockResponse().setBody("""<feed xmlns="http://www.w3.org/2005/Atom"><title>Alice</title><link rel="alternate" href="https://social.example/@alice"/><entry><id>https://social.example/notes/one</id><link rel="alternate" href="https://social.example/notes/one"/><title>Note</title><content>Public note</content><published>2026-10-16T12:00:00Z</published></entry><entry><link href="https://other.example/notes/two"/><title>Wrong server</title><published>2026-10-16T12:00:00Z</published></entry></feed>"""))
            val page=reader.fetch(target(Platform.MISSKEY,"https://social.example/@alice"));assertEquals(1,page.posts.size);assertEquals("https://social.example/notes/one",page.posts.single().canonicalUrl);assertEquals("/misskey/users/notes/alice%40social.example",server.takeRequest().path)
        }
    }
    @Test fun unsupportedTargetAndUnusableTorNeverSendARequest(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val g=gateway();val reader=publicFeedPathwayAdapter(source(Platform.X,"rsshub",server.url("/").toString()),g,routes)!!
            assertFalse(reader.accepts(target(Platform.X,"https://x.com/NASA/status/12",TargetKind.POST)));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.X,"https://x.com/NASA/status/12",TargetKind.POST))}}
            g.configure(NetworkPolicy(tor=true,ready=false));assertThrows(java.io.IOException::class.java){runBlocking {reader.fetch(target(Platform.X,"https://x.com/NASA"))}};assertEquals(0,server.requestCount)
        }
    }
    @Test fun threadsShortPostLinksNeedExactVerifiedFeedAndBridgeErrorFeedsStayEmpty(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val threads=reader(server,Platform.THREADS);val selected=target(Platform.THREADS,"https://www.threads.com/@nasa")
            server.enqueue(MockResponse().setBody(rss("https://www.threads.com/@nasa",item("https://www.threads.com/t/DekCdIIEZCG"))))
            assertEquals("post:DekCdIIEZCG",threads.fetch(selected).posts.single().id)
            server.enqueue(MockResponse().setBody(rss("https://www.threads.com",item("https://www.threads.com/t/DekCdIIEZCG"))))
            assertThrows(SourceFailure::class.java){runBlocking {threads.fetch(selected)}}
            val telegram=reader(server,Platform.TELEGRAM,"rss-bridge");server.enqueue(MockResponse().setBody(rss("https://t.me/s/telegram",item(server.url("/?action=display").toString(),body="Bridge returned error 0"))))
            assertThrows(SourceFailure::class.java){runBlocking {telegram.fetch(target(Platform.TELEGRAM,"https://t.me/telegram"))}}
        }
    }
    @Test fun tiktokBridgeKeepsUndatedProfileCardsWithoutEnteringChronologicalFeeds(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=reader(server,Platform.TIKTOK,"rss-bridge");val t=target(Platform.TIKTOK,"https://www.tiktok.com/@tiktok")
            val body="""<feed xmlns="http://www.w3.org/2005/Atom"><link rel="alternate" href="https://www.tiktok.com/@tiktok"/><entry><link rel="alternate" href="https://www.tiktok.com/@tiktok/video/7694797676306615583"/><title>Public video</title><updated>2026-10-17T13:12:54Z</updated><content type="html">&lt;p&gt;Caption&lt;/p&gt;&lt;img src="https://media.example/preview.jpg"/&gt;</content></entry></feed>"""
            server.enqueue(MockResponse().setBody(body));val page=reader.fetch(t)
            assertEquals(1,page.posts.size);assertNull(page.posts.single().publishedAt);assertEquals(1,page.posts.single().media.size);assertTrue(page.notice!!.contains("outside the chronological feed"));assertEquals(setOf(Capability.TIMELINE,Capability.MEDIA),reader.capabilities)
            val count=server.requestCount;val error=assertThrows(SourceFailure::class.java){runBlocking {reader.feed(t)}};assertEquals(HealthStatus.UNSUPPORTED,error.status);assertEquals(count,server.requestCount)
        }
    }
}

/** Explicit local replay of captured Gateway responses; never makes a live request. */
object PublicFeedCapturedAudit {
    @JvmStatic fun main(args: Array<String>)=runBlocking {
        val input=java.io.File(args[0]);val output=java.io.File(args[1]);val loader=PublicFeedCapturedAudit::class.java
        fun asset(name: String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
        val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
        val report=linkedMapOf<String,Any>()
        val cases=listOf(Triple(2,Platform.TIKTOK,"https://www.tiktok.com/@tiktok"),Triple(3,Platform.FACEBOOK,"https://www.facebook.com/NASA"),Triple(4,Platform.PINTEREST,"https://www.pinterest.com/nasa/images-of-earth"))
        for((index,platform,url) in cases) {
            val payload=JsonParser.parseReader(java.io.File(input,"response-$index.json").reader()).asJsonObject["text"].asString
            MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(payload))
                val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
                val reader=publicFeedPathwayAdapter(Source(platform,"rss-bridge",server.url("/").toString()),gateway,routes)!!;val target=routes.recognize(url,platform)!!
                if(platform==Platform.TIKTOK) {
                    val page=reader.fetch(target);check(page.posts.size==10&&page.posts.all {it.publishedAt==null});check(page.posts.sumOf {it.media.size}>0);check(Capability.FEED !in reader.capabilities)
                    val requests=server.requestCount;val failure=runCatching {reader.feed(target)}.exceptionOrNull();check(failure is SourceFailure&&failure.status==HealthStatus.UNSUPPORTED&&server.requestCount==requests)
                    report[platform.id]=mapOf("posts" to page.posts.size,"dated" to page.posts.count {it.publishedAt!=null},"media" to page.posts.sumOf {it.media.size},"feedRejectedBeforeRequest" to true)
                }else {
                    val failure=runCatching {reader.fetch(target)}.exceptionOrNull();check(failure is SourceFailure);report[platform.id]=mapOf("errorFeedRejected" to true,"status" to failure.status.name)
                }
                gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
            }
        }
        output.parentFile.mkdirs();output.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));println(output.readText())
    }
}
