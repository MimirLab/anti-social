package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class CoreTest {
    @Test fun compiledCleaningKeepsRedirectsExceptionsAndRawRuleCaseSemantics() {
        val data=JsonParser.parseString("""{"providers":{"parameters":{"urlPattern":"^https://example[.]test/","rules":["tracking"],"exceptions":["/keep"]},"redirect":{"urlPattern":"^https://redirect[.]test/","redirections":["^https://redirect[.]test/go/(.*)$"]},"raw":{"urlPattern":"^https://raw[.]test/","rawRules":["#ads$"]}}}""").asJsonObject
        val cleaner=UrlCleaner(data);cleaner.prepare()
        assertEquals("https://example.test/post",cleaner.clean("https://redirect.test/go/https%3A%2F%2Fexample.test%2Fpost%3Ftracking%3Dx"))
        assertEquals("https://example.test/keep?tracking=x",cleaner.clean("https://example.test/keep?tracking=x"))
        assertEquals("https://raw.test/post",cleaner.clean("https://raw.test/post#ads"))
        assertEquals("https://raw.test/post#ADS",cleaner.clean("https://raw.test/post#ADS"))
    }
    @Test fun nativeSnapshotsRestoreFromFilesAndBoundedFileRanges() {
        val file=java.io.File.createTempFile("antisocial-filter-",".bin",java.io.File(System.getProperty("antisocial.native.path")).parentFile)
        try {
            val binary=NativeFilter("||ads.test^").use {it.serialize()}
            file.writeBytes(binary)
            NativeFilter.restoreFile(file).use {assertTrue(it.check("https://ads.test/pixel","https://reader.test/","image").blocked)}
            file.writeBytes(ByteArray(17)+binary+ByteArray(11))
            NativeFilter.restoreFileRange(file.path,17,binary.size.toLong()).use {assertTrue(it.check("https://ads.test/pixel","https://reader.test/","image").blocked)}
            assertThrows(IllegalArgumentException::class.java){NativeFilter.restoreFileRange(file.path,-1,10)}
            assertThrows(IllegalArgumentException::class.java){NativeFilter.restoreFileRange(file.path,17,binary.size+12L)}
            file.writeBytes(binary.copyOf().apply {this[lastIndex]=(last().toInt() xor 1).toByte()})
            assertThrows(IllegalArgumentException::class.java){NativeFilter.restoreFile(file)}
        }finally {file.delete()}
    }
    @Test fun recentPagesKeepProfileAndPostSeparateAndBoundMemory() {
        val profile=Target(Platform.INSTAGRAM,TargetKind.PROFILE,"fixture","https://www.instagram.com/fixture")
        val post=Target(Platform.INSTAGRAM,TargetKind.POST,"p/1","https://www.instagram.com/p/1")
        val another=profile.copy(identity="other",canonicalUrl="https://www.instagram.com/other")
        val profilePage=Page(emptyList(),profile=Profile(profile,"Profile"))
        val postPage=Page(listOf(Post("1",Platform.INSTAGRAM,post.canonicalUrl,"fixture","Full post")))
        val memory=PageMemory(limit=2)
        memory.put(profile,profilePage);memory.put(post,postPage)
        assertEquals("Profile",memory.get(profile)?.profile?.name)
        memory.put(another,Page(emptyList()))
        assertNull(memory.get(post));assertSame(profilePage,memory.get(profile))
        memory.put(post,postPage,complete=false);assertFalse(memory.fresh(post))
        val tiny=PageMemory(maxCharacters=8);tiny.put(post,postPage);assertNull(tiny.get(post))
    }
    @Test fun resourceLocksAllowIndependentWorkAndReleaseCancelledWaiters(): Unit=runBlocking {
        val locks=KeyedMutex<String>();val started=kotlinx.coroutines.CompletableDeferred<Unit>();val release=kotlinx.coroutines.CompletableDeferred<Unit>()
        val first=launch {locks.withKey("slow"){started.complete(Unit);release.await()}}
        started.await()
        kotlinx.coroutines.withTimeout(1000){locks.withKey("another"){assertFalse(first.isCompleted)}}
        val waiting=launch {locks.withKey("slow"){fail("Cancelled waiter must not acquire the resource")}}
        kotlinx.coroutines.yield();waiting.cancel();waiting.join();release.complete(Unit);first.join()
        kotlinx.coroutines.withTimeout(1000){locks.withKey("slow"){}}
    }
    @Test fun protectionCountersCountAllowedAndBlockedRequestsBeforeConnections(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("Allowed"))
            val filter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(url.endsWith("/blocked"),"Synthetic test filter")}
            val gateway=NetworkGateway(cleaner,filter,true)
            assertEquals("Allowed",gateway.text(server.url("/allowed").toString()).text)
            assertThrows(java.io.IOException::class.java){runBlocking {gateway.text(server.url("/blocked").toString())}}
            assertEquals(ProtectionStats(2,1),gateway.protectionStats());assertEquals(1,server.requestCount)
        }
    }
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private val permit=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}
    @Test fun darwinCatalogCoversEveryPlatformAndRecognizesMirrors() {
        assertEquals(11,routes.sources.map {it.platform}.distinct().size)
        routes.sources.filterNot {it.frontend=="piped"}.forEach {source->val target=routes.target(source.platform,if(source.platform==Platform.BLUESKY)"bsky.app"else "nasa");val converted=routes.convert(target,source);assertTrue(converted.startsWith(source.root));assertEquals(source.platform,routes.recognize(converted)?.platform)}
    }
    @Test fun canonicalIdentitySurvivesOriginalAliasAndMirror() {
        val source=routes.sources.first {it.frontend=="nitter"}
        val one=routes.recognize("https://twitter.com/NASA/status/123/")!!
        assertEquals("https://x.com/NASA/status/123",one.canonicalUrl)
        assertEquals(one.canonicalUrl,routes.recognize(routes.convert(one,source))!!.canonicalUrl)
        assertEquals(TargetKind.TAG,routes.target(Platform.TUMBLR,"#space").kind)
        assertEquals("https://t.co/abc",routes.recognize("https://t.co/abc")!!.canonicalUrl)
        val short=routes.recognize("https://redd.it/abc")!!
        assertEquals(TargetKind.POST,short.kind);assertTrue(routes.convert(short,routes.sources.first {it.frontend=="redlib"}).contains("/comments/abc"))
        assertEquals("https://www.reddit.com/user/example",routes.target(Platform.REDDIT,"@example").canonicalUrl)
    }
    @Test fun trackingRemovalRetainsUsefulAndSignedParameters() {
        assertEquals("https://example.org/post?id=12#comment",cleaner.clean("https://example.org/post?utm_source=foo&id=12#comment"))
        val signed="https://example.org/image?token=abc&utm_source=foo"
        assertEquals(signed,cleaner.clean(signed))
    }
    @Test fun torUnavailableFailsBeforeAnySocket(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val gateway=NetworkGateway(cleaner,permit,true);gateway.configure(NetworkPolicy(tor=true));assertThrows(java.io.IOException::class.java){runBlocking {gateway.text(server.url("/").toString())}};assertEquals(0,server.requestCount)}
    }
    @Test fun timeoutClosesStalledResponseWithoutWaitingForBody(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("late body").setBodyDelay(2,TimeUnit.SECONDS))
            val gateway=NetworkGateway(cleaner,permit,true);val start=System.nanoTime()
            assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java){runBlocking {kotlinx.coroutines.withTimeout(150){gateway.text(server.url("/").toString())}}}
            assertTrue("Cancellation must not wait for the body",(System.nanoTime()-start)/1_000_000<1500)
            kotlinx.coroutines.withTimeout(1000){while(gateway.client().dispatcher.runningCallsCount()>0)kotlinx.coroutines.delay(10)}
            assertEquals(0,gateway.client().dispatcher.runningCallsCount())
        }
    }
    @Test fun filterBlocksRedirectBeforeDestinationConnection(): Unit=runBlocking {
        MockWebServer().use {source->MockWebServer().use {destination->source.start();destination.start();source.enqueue(MockResponse().setResponseCode(302).addHeader("Location",destination.url("/track")))
            val filter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(url.contains("/track"),"test rule")}
            val gateway=NetworkGateway(cleaner,filter,true)
            assertThrows(java.io.IOException::class.java){runBlocking {gateway.text(source.url("/post").toString())}}
            assertEquals(1,source.requestCount);assertEquals(0,destination.requestCount)
        }}
    }
    @Test fun networkRemovesCredentialsAndBoundsResponses(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("public"));val gateway=NetworkGateway(cleaner,permit,true)
            gateway.text(server.url("/").toString(),headers=mapOf("Cookie" to "secret", "Authorization" to "Bearer secret"))
            val received=server.takeRequest(1,TimeUnit.SECONDS)!!;assertNull(received.getHeader("Cookie"));assertNull(received.getHeader("Authorization"));assertNull(received.getHeader("X-AntiSocial-Source"))
            server.enqueue(MockResponse().setBody("too large"));assertThrows(java.io.IOException::class.java){runBlocking {gateway.text(server.url("/").toString(),maxBytes=3)}}
        }
    }
    @Test fun originalDisabledBlocksBeforeDns(): Unit=runBlocking {
        val gateway=NetworkGateway(cleaner,permit);gateway.originalFallback=false
        val failure=assertThrows(java.io.IOException::class.java){runBlocking {gateway.text("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile")}}
        assertTrue(failure.message!!.contains("disabled"))
    }
    @Test fun torSocksUsesRemoteHostnameRatherThanLocalDns(): Unit=runBlocking {
        java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()).use {server->
            server.soTimeout=5000
            val hostname=java.util.concurrent.CompletableFuture<String>()
            val worker=Thread {
                try {server.accept().use {socket->socket.soTimeout=5000;val input=java.io.DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                    assertEquals(5,input.readUnsignedByte());val methods=input.readUnsignedByte();repeat(methods){input.readByte()};output.write(byteArrayOf(5,0));output.flush()
                    assertEquals(5,input.readUnsignedByte());assertEquals(1,input.readUnsignedByte());input.readByte();assertEquals(3,input.readUnsignedByte())
                    val name=ByteArray(input.readUnsignedByte());input.readFully(name);hostname.complete(String(name));input.readUnsignedShort();output.write(byteArrayOf(5,5,0,1,0,0,0,0,0,0));output.flush()
                }}catch(e: Throwable){hostname.completeExceptionally(e)}
            }.apply {start()}
            val gateway=NetworkGateway(cleaner,permit);gateway.configure(NetworkPolicy(true,server.localPort,true))
            assertThrows(java.io.IOException::class.java){runBlocking {gateway.text("https://antisocial-remote-dns.invalid/post")}}
            assertEquals("antisocial-remote-dns.invalid",hostname.get(5,TimeUnit.SECONDS));worker.join(1000)
        }
    }
    @Test fun privateDestinationAndRewriteRejected(): Unit=runBlocking {
        val gateway=NetworkGateway(cleaner,permit)
        assertThrows(java.io.IOException::class.java){runBlocking {gateway.text("https://192.168.1.1/content")}}
        val rewrite=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false,rewrittenUrl="http://localhost/")})
        assertThrows(java.io.IOException::class.java){runBlocking {rewrite.text("https://example.org/")}}
    }
    @Test fun cacheAgeAndBudgetProtectSavedAndVisible() {
        val now=100*CachePolicy.DAY
        val entries=listOf(CacheEntry("unviewed",50,now-8*CachePolicy.DAY,null,false),CacheEntry("viewed",50,0,now-31*CachePolicy.DAY,false),CacheEntry("saved",1000,0,null,true),CacheEntry("visible",20,0,null,false,true),CacheEntry("fresh",60,now,null,false))
        assertEquals(setOf("unviewed","viewed","fresh"),CachePolicy.eviction(entries,now,20))
        assertEquals(setOf("unviewed","viewed"),CachePolicy.eviction(entries,now,100))
    }
    private val source=Source(Platform.REDDIT,"original",Platform.REDDIT.origin,role=SourceRole.ORIGINAL)
    private val target=routes.target(Platform.REDDIT,"r/Android")
    private fun frontend(platform: Platform,name: String,html: String,url: String=routes.target(platform,"nasa").canonicalUrl): Page {
        val source=routes.sources.first {it.platform==platform&&it.frontend==name}
        val target=routes.recognize(url)!!
        return Extraction.parse(Payload(html,routes.convert(target,source),"text/html",200),target,source,routes)
    }
    @Test fun kittygramGridTimelineAndCaptionHaveNativeData() {
        val header="""<div class="user-info-card"><div class="usernames"><h3>NASA</h3></div><p class="user-bio-text">Public science</p></div>"""
        val grid=frontend(Platform.INSTAGRAM,"kittygram",header+"""<span class="grid-item"><a href="/p/AbC"><img src="/proxy/image.jpg" alt="A planet"></a></span>""")
        assertEquals("NASA",grid.profile!!.name);assertEquals("post:AbC",grid.posts.single().id);assertNull(grid.posts.single().publishedAt)
        assertEquals("A planet",grid.posts.single().media.single().description)
        val timeline=frontend(Platform.INSTAGRAM,"kittygram",header+"""<div class="post"><div class="user-info"><a class="username">nasa</a><img src="/avatar.jpg"></div><div class="post-image"><video poster="/preview.jpg"><source src="/movie.mp4"></video></div><time datetime="2026-10-14T12:30:00"></time><div class="post-body"><div class="caption"><p class="post-caption-text">Public caption</p></div><div class="comments">Comment should not be the caption</div></div><a href="/p/AbC">Comments</a></div><a class="next-button" href="/nasa?after=next">Next</a>""")
        val post=timeline.posts.single();assertEquals("Public caption",post.body);assertEquals(Extraction.timestamp("2026-10-14T12:30:00Z"),post.publishedAt)
        assertEquals("video",post.media.single().type);assertTrue(post.media.single().preview!!.endsWith("/preview.jpg"));assertTrue(timeline.next!!.endsWith("?after=next"))
    }
    @Test fun pinlessBoardsPinsAndMainPinRemainDistinct() {
        val page=frontend(Platform.PINTEREST,"pinless","""<div class="profile-header"><div class="profile-name">NASA</div></div><div class="boards-grid"><a href="/nasa/space/"><div class="board-card-name">Space</div><div class="board-card-count">12 Pins</div><img src="/cover.jpg"></a></div><div class="pins-grid"><div><a href="/pin/123/"><img src="/image.jpg"></a></div></div>""")
        assertEquals("Space",page.collections.single().name);assertEquals(TargetKind.COMMUNITY,page.collections.single().target.kind)
        assertEquals("post:123",page.posts.single().id);assertEquals(1,page.posts.single().media.size);assertNull(page.posts.single().publishedAt)
        val pin=frontend(Platform.PINTEREST,"pinless","""<div class="pin-container"><img src="/original.jpg"><div class="pin-title">A planet</div><div class="pin-description">Real detail</div><div class="pin-pinner"><a href="/nasa/">NASA</a></div></div><div class="grid-container"><div><a href="/pin/456"><img src="/related.jpg"></a></div></div>""","https://www.pinterest.com/pin/123")
        assertEquals(2,pin.posts.size);assertEquals("A planet",pin.posts.first().title);assertEquals("Real detail",pin.posts.first().body)
    }
    @Test fun nestedTimelineCardsAreNotDuplicatePosts() {
        val page=frontend(Platform.X,"nitter","""<div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/123"></a><div class="tweet-content">Outer post</div><div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/456"></a><div class="tweet-content">Embedded card</div></div></div><div class="timeline-item"><a class="tweet-link" href="https://x.com/NASA/status/789"></a><div class="tweet-content">Second post</div></div>""")
        assertEquals(listOf("post:123","post:789"),page.posts.map {it.id})
        assertEquals(listOf("Outer post","Second post"),page.posts.map {it.body})
    }
    @Test fun proxitokDataAttributesAndTimelineWorkWithoutJavascript() {
        val tile=frontend(Platform.TIKTOK,"proxiTok","""<div data-video_share_url="https://www.tiktok.com/@nasa/video/123" data-video_url="/stream/movie.mp4" data-desc="A launch" data-createtime="1700000000"><img src="/cover.jpg"></div>""").posts.single()
        assertEquals("post:123",tile.id);assertEquals("A launch",tile.body);assertEquals(1700000000000L,tile.publishedAt);assertEquals("video",tile.media.single().type)
        val timeline=frontend(Platform.TIKTOK,"proxiTok","""<article class="media"><figure class="media-left"><img src="/avatar.jpg"></figure><div class="media-content"><small><a href="/@nasa">@nasa</a></small><small title="Nov 14, 2023 22:13:20 UTC"></small><p class="mb-2">A launch</p><video poster="/cover.jpg"><source src="/stream/movie.mp4"></video><a href="/@nasa/video/123">Instance</a></div></article>""").posts.single()
        assertEquals(tile.id,timeline.id);assertEquals(tile.publishedAt,timeline.publishedAt);assertEquals("nasa",timeline.author)
    }
    @Test fun blueskyVideoAndSyndicationVideoRetainPlayableUrls() {
        val bsky=routes.target(Platform.BLUESKY,"bsky.app")
        val bs=Source(Platform.BLUESKY,"original",Platform.BLUESKY.origin,role=SourceRole.ORIGINAL)
        val bp=Extraction.parse(Payload("""{"feed":[{"post":{"uri":"at://did:plc:abc/app.bsky.feed.post/123","author":{"handle":"bsky.app"},"record":{"text":"Video","createdAt":"2026-10-14T12:00:00Z"},"embed":{"playlist":"https://video.bsky.app/watch/playlist.m3u8","thumbnail":"https://video.bsky.app/watch/thumbnail.jpg"}}}]}""",bsky.canonicalUrl,"application/json",200),bsky,bs,routes).posts.single()
        assertEquals("video",bp.media.single().type);assertTrue(bp.media.single().url.endsWith(".m3u8"))
        val xs=Source(Platform.X,"original",Platform.X.origin,role=SourceRole.ORIGINAL);val xt=routes.recognize("https://x.com/NASA/status/123")!!
        val xp=Extraction.parse(Payload("""{"id_str":"123","text":"Video","user":{"screen_name":"NASA"},"video":{"poster":"https://pbs.twimg.com/preview.jpg","variants":[{"type":"video/mp4","src":"https://video.twimg.com/movie.mp4"}]}}""",xt.canonicalUrl,"application/json",200),xt,xs,routes).posts.single()
        assertEquals("https://video.twimg.com/movie.mp4",xp.media.single().url)
    }
    @Test fun nativeSearchAndKittygramTagsPreserveMeaning() {
        val query=routes.search(Platform.BLUESKY,"space & science")
        assertEquals("space & science",routes.recognize(query.canonicalUrl)!!.identity)
        assertEquals(TargetKind.SEARCH,routes.recognize(query.canonicalUrl)!!.kind)
        val kitty=routes.sources.first {it.frontend=="kittygram"}
        val tag=routes.target(Platform.INSTAGRAM,"#space")
        assertTrue(routes.convert(tag,kitty).endsWith("/popular/space"))
        assertEquals(TargetKind.TAG,routes.recognize("${kitty.root}/popular/space")!!.kind)
        for(platform in Platform.entries.filterNot {it==Platform.RSS})assertEquals("space",routes.recognize(routes.search(platform,"space").canonicalUrl)!!.identity)
    }
    @Test fun mediaRoutingKeepsDarwinsProxyPathsAndOriginalHosts() {
        val nitter=routes.sources.first {it.frontend=="nitter"}
        val image=routes.recognize("https://pbs.twimg.com/media/abc?format=jpg&name=small")!!
        assertEquals(TargetKind.MEDIA,image.kind);assertEquals("pbs.twimg.com",java.net.URI(image.canonicalUrl).host)
        assertEquals("${nitter.root}/pic/media/abc.jpg%3Fname%3Dsmall",routes.convert(image,nitter))
        assertEquals(TargetKind.MEDIA,routes.recognize(routes.convert(image,nitter))!!.kind)
        assertEquals("https://t.co/link",routes.recognize("${nitter.root}/t.co/link")!!.canonicalUrl)
        val tumblr=routes.recognize("https://64.media.tumblr.com/abc/image.jpg")!!;val priviblur=routes.sources.first {it.frontend=="priviblur"}
        assertEquals("${priviblur.root}/tblr/media/64/abc/image.jpg",routes.convert(tumblr,priviblur));assertEquals(tumblr,routes.recognize(routes.convert(tumblr,priviblur)))
        val pin=routes.recognize("https://i.pinimg.com/originals/abc.jpg")!!;val pinless=routes.sources.first {it.frontend=="pinless"}
        assertTrue(routes.convert(pin,pinless).startsWith("${pinless.root}/image?url="));assertEquals(pin,routes.recognize(routes.convert(pin,pinless)))
        val redlib=routes.sources.first {it.frontend=="redlib"};val reddit=routes.recognize("https://i.redd.it/abc.jpg")!!
        assertEquals(reddit,routes.recognize(routes.convert(reddit,redlib)))
        assertNull(routes.recognize("${routes.sources.first {it.frontend=="skyview"}.root}?url=http%3Abroken"))
    }
    @Test fun mediaSignaturesAndBoundedSampleRejectHtmlErrors(): Unit=runBlocking {
        assertNull(MediaFormats.type("<html>Login</html>".toByteArray()));assertEquals("image",MediaFormats.type(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0)))
        assertEquals("video",MediaFormats.type("#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("abcdefg"));val sample=NetworkGateway(cleaner,permit,true).sample(server.url("/media").toString(),limit=3);assertArrayEquals("abc".toByteArray(),sample);assertEquals("bytes=0-2",server.takeRequest().getHeader("Range"))}
    }
    @Test fun ifunnyInitialStateIsParsedAsDataWithoutExecutingItsScript() {
        val source=Source(Platform.IFUNNY,"original",Platform.IFUNNY.origin,role=SourceRole.ORIGINAL);val target=routes.target(Platform.IFUNNY,"user/iFunny")
        val body="""<script>window.__INITIAL_STATE__={"feed":{"items":[{"id":"AbC12XYZ9","link":"https://ifunny.co/picture/AbC12XYZ9","created":1700000000,"creator":{"nick":"iFunny"},"url":"https://example.org/meme.jpg","type":"picture","comments":3,"smiles":12}]}};(function(){throw new Error('Never execute');}());</script>"""
        val post=Extraction.parse(Payload(body,target.canonicalUrl,"text/html",200),target,source,routes).posts.single()
        assertEquals("post:AbC12XYZ9",post.id);assertEquals(1700000000000L,post.publishedAt);assertEquals(3L,post.replyCount)
        assertEquals(post.id,ContentIdentity.key(Platform.IFUNNY,"https://ifunny.co/picture/a-long-caption-AbC12XYZ9"))
    }
    @Test fun structuredPreviewNeverPretendsToBePlayableVideo() {
        val source=Source(Platform.TIKTOK,"original",Platform.TIKTOK.origin,role=SourceRole.ORIGINAL);val target=routes.recognize("https://www.tiktok.com/@fixture/video/123")!!
        val post=Extraction.parse(Payload("""{"provider_name":"TikTok","type":"video","author_url":"https://www.tiktok.com/@fixture","author_name":"Fixture","title":"Public caption","thumbnail_url":"https://example.org/preview.jpg","html":"<script>never execute</script>"}""",target.canonicalUrl,"application/json",200),target,source,routes).posts.single()
        assertEquals("image",post.media.single().type);assertTrue(post.media.single().description.contains("did not supply a playable video"));assertNull(post.publishedAt)
    }
    @Test fun shortLinksKeepTheirHostAndResolveWithoutReadingALoginWall(): Unit=runBlocking {
        val short=routes.recognize("https://vm.tiktok.com/Fixture/")!!
        assertEquals(Platform.TIKTOK,short.platform);assertEquals(TargetKind.POST,short.kind);assertEquals("vm.tiktok.com",java.net.URI(short.canonicalUrl).host)
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","/public-post"));server.enqueue(MockResponse().setResponseCode(403).setBody("Sign in to continue"))
            val gateway=NetworkGateway(cleaner,permit,true);assertEquals(server.url("/public-post").toString(),gateway.resolve(server.url("/short").toString()))
        }
    }
    @Test fun nativePostsKeepTheirOutboundLinksAsData() {
        val payload="""{"data":{"children":[{"data":{"permalink":"/r/Android/comments/links/title/","title":"Linked article","author":"fixture","created_utc":1700000000,"url_overridden_by_dest":"https://example.org/article?utm_source=reddit","secure_media":null}}]}}"""
        val post=Extraction.parse(Payload(payload,target.canonicalUrl,"application/json",200),target,source,routes).posts.single()
        assertEquals(listOf("https://example.org/article?utm_source=reddit"),post.links)
        assertEquals("https://example.org/article",cleaner.clean(post.links!!.single()))
        val html=frontend(Platform.INSTAGRAM,"kittygram","""<div class="post"><div class="caption">Read <a href="https://example.org/science">this article</a></div><a href="/p/LinkFixture">Post</a></div>""").posts.single()
        assertEquals(listOf("https://example.org/science"),html.links)
    }
    @Test fun failoverCanReachWorkingInstanceAfterThreeStaleChoices(): Unit=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object: okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest)=if(request.path!!.startsWith("/mirror3/"))MockResponse().setBody("""<div class="user-info-card"><h1>NASA</h1></div><span class="grid-item"><a href="https://www.instagram.com/p/AbC"><img src="https://example.org/image.jpg"></a></span>""")else MockResponse().setResponseCode(404)}
            server.start()
            val catalog=asset("darwin-routes.json");val list=com.google.gson.JsonArray()
            repeat(4){i->val s=catalog.getAsJsonArray("sources").first {it.asJsonObject["frontend"].asString=="kittygram"}.deepCopy().asJsonObject;s.addProperty("root",server.url("/mirror$i").toString().trimEnd('/'));list.add(s)}
            catalog.add("sources",list)
            val localRoutes=DarwinRoutes(catalog,cleaner)
            val pool=SourcePool(NetworkGateway(cleaner,permit,true),localRoutes)
            val page=kotlinx.coroutines.withTimeout(5000){pool.load(localRoutes.target(Platform.INSTAGRAM,"nasa"),originalFallback=false)}
            assertEquals(1,page.posts.size);assertTrue(page.provenance!!.source.root.endsWith("mirror3"))
            assertTrue(pool.results(Platform.INSTAGRAM).any {it.status==HealthStatus.HEALTHY})
        }
    }
    @Test fun exhaustedProviderTimeoutIsAReadFailureWhileCallerCancellationSurvives(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            repeat(2){server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))}
            val catalog=asset("darwin-routes.json")
            val entry=catalog.getAsJsonArray("sources").first {it.asJsonObject["frontend"].asString=="kittygram"}.deepCopy().asJsonObject
            entry.addProperty("root",server.url("/").toString().trimEnd('/'))
            catalog.add("sources",com.google.gson.JsonArray().apply {add(entry)})
            val local=DarwinRoutes(catalog,cleaner);val pool=SourcePool(NetworkGateway(cleaner,permit,true),local)
            val failure=assertThrows(SourceFailure::class.java){runBlocking {pool.load(local.target(Platform.INSTAGRAM,"fixture"),originalFallback=false)}}
            assertEquals(HealthStatus.UNAVAILABLE,failure.status)
            assertTrue(failure.message!!.contains("timed out"))
            assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java){runBlocking {kotlinx.coroutines.withTimeout(100){SourcePool(NetworkGateway(cleaner,permit,true),local).load(local.target(Platform.INSTAGRAM,"fixture"),originalFallback=false)}}}
        }
    }
    @Test fun redditNullMediaParsesAndMatchesRssIdentity() {
        val json="""{"data":{"children":[{"data":{"name":"t3_abc","permalink":"/r/Android/comments/abc/title/","title":"Title","selftext":"Body","author":"reader","created_utc":1700000000,"secure_media":null,"score":null}}]}}"""
        val post=Extraction.parse(Payload(json,target.canonicalUrl,"application/json",200),target,source,routes).posts.single()
        val rss="""<rss><channel><item><link>https://www.reddit.com/r/Android/comments/abc/title/</link><title>Title</title><description>Body</description><pubDate>Tue, 14 Nov 2023 22:13:20 GMT</pubDate></item></channel></rss>"""
        val feed=Extraction.parse(Payload(rss,target.canonicalUrl,"application/rss+xml",200),target,source,routes).posts.single()
        assertEquals(post.id,feed.id);assertEquals(post.publishedAt,feed.publishedAt);assertEquals(1,CachePolicy.chronological(listOf(post,feed)).size)
    }
    @Test fun rssRejectsExternalEntities() {
        val xml="""<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><link>https://example.org/1</link><description>&x;</description></item></channel></rss>"""
        assertThrows(Exception::class.java){Extraction.parse(Payload(xml,target.canonicalUrl,"application/xml",200),target,source,routes)}
    }
    @Test fun loginAndEmptyFrontendAreNotHealthy() {
        listOf("<html><title>Log in</title><form>Sign in to continue</form></html>","<html><title>Redlib</title><p>Welcome</p></html>").forEach {body->assertThrows(SourceFailure::class.java){Extraction.parse(Payload(body,target.canonicalUrl,"text/html",200),target,source,routes)}}
    }
    @Test fun redlibAndPriviblurRealMarkupCarryDatesAndPostIdentity() {
        val html="""<div class="post"><span class="created" title="Oct 14 2026, 05:15:23 UTC">9h ago</span><h2 class="post_title"><a href="/r/Android/comments/abc/example">Example</a></h2></div>"""
        val mirror=routes.sources.first {it.frontend=="redlib"}
        val post=Extraction.parse(Payload(html,mirror.root+"/r/Android","text/html",200),target,mirror,routes).posts.single()
        assertEquals(java.time.Instant.parse("2026-10-14T05:15:23Z").toEpochMilli(),post.publishedAt)
        val blog=routes.target(Platform.TUMBLR,"staff");val frontend=routes.sources.first {it.frontend=="priviblur"}
        val page="""<div class="post"><div class="post-timestamp"><a href="/staff/123456789/title"><time datetime="2026-09-17T13:16">Sep 17</time></a></div><div class="post-body">Example post</div></div>"""
        val item=Extraction.parse(Payload(page,frontend.root+"/staff","text/html",200),blog,frontend,routes).posts.single()
        assertEquals(TargetKind.POST,routes.recognize(item.canonicalUrl)?.kind);assertNotNull(item.publishedAt)
    }
    @Test fun stablePostIdentitySurvivesFeedAndFrontendUrlDifferences() {
        assertEquals(ContentIdentity.key(Platform.TUMBLR,"https://staff.tumblr.com/post/123456789"),ContentIdentity.key(Platform.TUMBLR,"https://www.tumblr.com/staff/123456789/new-slug"))
        assertEquals(ContentIdentity.key(Platform.X,"https://twitter.com/nasa/status/123"),ContentIdentity.key(Platform.X,"https://x.com/NASA/status/123"))
        assertEquals(ContentIdentity.key(Platform.REDDIT,"https://www.reddit.com/r/A/comments/abc/one"),ContentIdentity.key(Platform.REDDIT,"https://www.reddit.com/r/A/comments/abc/other"))
        assertNotEquals(ContentIdentity.key(Platform.REDDIT,"https://www.reddit.com/r/A/comments/abc/one"),ContentIdentity.key(Platform.REDDIT,"https://www.reddit.com/r/A/comments/abc/one/def"))
        assertNotEquals(ContentIdentity.key(Platform.REDDIT,"https://www.reddit.com/r/A/comments/abc/one/def"),ContentIdentity.key(Platform.REDDIT,"https://www.reddit.com/r/A/comments/abc/one/ghi"))
    }
    @Test fun siteBrandingImageIsNotAcceptedAsTheRequestedPost() {
        val requested=routes.recognize("https://www.instagram.com/p/example")!!
        val source=Source(Platform.INSTAGRAM,"original",Platform.INSTAGRAM.origin,role=SourceRole.ORIGINAL)
        val branding="""<html><head><meta property="og:title" content="Instagram"><meta property="og:type" content="website"><meta property="og:url" content="https://www.instagram.com/"><meta property="og:image" content="https://example.org/logo.png"></head></html>"""
        assertThrows(SourceFailure::class.java){Extraction.parse(Payload(branding,requested.canonicalUrl,"text/html",200),requested,source,routes)}
    }
    @Test fun quotedAuthorCannotReplaceTheRequestedProfileHeader() {
        val target=routes.target(Platform.BLUESKY,"reader.example")
        val source=Source(Platform.BLUESKY,"original",Platform.BLUESKY.origin,role=SourceRole.ORIGINAL)
        val json="""{"authors":[{"handle":"reader.example","displayName":"Requested reader"},{"handle":"other.example","displayName":"Quoted person"}]}"""
        val page=Extraction.parse(Payload(json,target.canonicalUrl,"application/json",200),target,source,routes)
        assertEquals("Requested reader",page.profile!!.name)
    }
    @Test fun nullVideoFieldDoesNotTurnAnInstagramPhotoIntoAVideo() {
        val target=routes.recognize("https://www.instagram.com/p/example")!!
        val source=Source(Platform.INSTAGRAM,"proxigram","https://example.org")
        val json="""{"shortcode":"example","video_url":null,"display_url":"https://example.org/photo.jpg","caption":"Photo caption"}"""
        val page=Extraction.parse(Payload(json,target.canonicalUrl,"application/json",200),target,source,routes)
        assertEquals("image",page.posts.single().media.single().type)
    }
    @Test fun originalRssCanSupplyCommunityContentWhenJsonIsDenied(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(403));server.enqueue(MockResponse().setBody("""<rss><channel><item><link>https://www.reddit.com/r/Android/comments/fallback/title</link><title>Synthetic fallback</title><description>Body</description><pubDate>Wed, 14 Oct 2026 10:00:00 GMT</pubDate></item></channel></rss>"""))
            val onlyOriginal=asset("darwin-routes.json").apply {add("sources",com.google.gson.JsonArray())}
            val filter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String):FilterDecision {
                val u=java.net.URI(url)
                return if(u.host==server.hostName&&u.port==server.port)FilterDecision(false)
                    else FilterDecision(false,rewrittenUrl=server.url(u.rawPath+"?"+u.rawQuery.orEmpty()).toString())
            }}
            val pool=SourcePool(NetworkGateway(cleaner,filter,true),DarwinRoutes(onlyOriginal,cleaner))
            val page=pool.load(target)
            assertEquals("Synthetic fallback",page.posts.single().title)
            assertTrue(server.takeRequest().path!!.contains("/new.json"));assertTrue(server.takeRequest().path!!.contains("/new/.rss"))
        }
    }
    @Test fun nativeFilterHandlesHostsExceptionsAndPersistence() {
        NativeFilter("0.0.0.0 tracker.test\n||ads.test^\n@@||ads.test/allowed").use {filter->
            assertTrue(filter.check("https://tracker.test/pixel","https://reader.test/","image").blocked)
            assertFalse(filter.check("https://ads.test/allowed","https://reader.test/","image").blocked)
            NativeFilter.restore(filter.serialize()).use {restored->assertTrue(restored.check("https://ads.test/banner","https://reader.test/","image").blocked)}
        }
    }
    @Test fun entireDarwinFilterSeedCompilesAndRecognizesCommonTracker() {
        val rules=GZIPInputStream(javaClass.getResourceAsStream("/network-rules.seed")!!).reader().readText()
        NativeFilter(rules).use {filter->assertTrue(filter.check("https://www.google-analytics.com/collect?v=1","https://reader.test/","xmlhttprequest").blocked);assertFalse(filter.check("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=bsky.app","https://bsky.app/","xmlhttprequest").blocked)}
    }
    @Test fun bundledCompiledProtectionMatchesSeedAndRestoresWithIntegrityChecks() {
        val seed=javaClass.getResourceAsStream("/network-rules.seed")!!.readBytes()
        val binary=javaClass.getResourceAsStream("/network-rules.compiled")!!.readBytes()
        val metadata=asset("network-rules.compiled.json")
        fun sha(bytes: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        assertEquals(sha(seed),metadata["seedSha256"].asString)
        assertEquals(sha(binary),metadata["sha256"].asString)
        NativeFilter.restore(binary).use {filter->
            assertTrue(filter.check("https://www.google-analytics.com/collect?v=1","https://reader.test/","xmlhttprequest").blocked)
            assertFalse(filter.check("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=bsky.app","https://bsky.app/","xmlhttprequest").blocked)
        }
        binary[binary.lastIndex]=(binary.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java){NativeFilter.restore(binary)}
    }
}
