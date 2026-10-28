package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LargePlatformDiscoveryAdaptersTest {
    private val instagram=Target(Platform.INSTAGRAM,TargetKind.POST,"p/example123","https://www.instagram.com/p/example123/")
    private val threads=Target(Platform.THREADS,TargetKind.POST,"@nasa/post/example123","https://www.threads.com/@nasa/post/example123/")
    private val pin=Target(Platform.PINTEREST,TargetKind.POST,"pin/12345","https://www.pinterest.com/pin/12345/")
    private val user=Target(Platform.PINTEREST,TargetKind.PROFILE,"nasa","https://www.pinterest.com/nasa/")
    private fun payload(body: String,url: String="https://reader.example/api")=Payload(body,url,"application/json",200)
    private fun source(platform: Platform,frontend: String)=Source(platform,frontend,"https://reader.example")
    private fun fx(code: String="example123",username: String="nasa",body: String="Public launch update")="""{"id":"$code","author":{"username":"$username","full_name":"NASA","avatar_url":"https://media.example/avatar.jpg"},"content":"$body","timestamp":1700000000,"stats":{"likes":12,"comments":3},"medias":[{"is_video":false,"url":"https://media.example/image.jpg"}]}"""
    private fun activity(url: String="https://reader.example/p/example123",actor: String="https://reader.example/users/nasa",content: String="<p><b>12 likes</b></p><p>Launch update</p>")="""{"@context":"https://www.w3.org/ns/activitystreams","type":"Note","url":"$url","attributedTo":"$actor","content":"$content","to":["https://www.w3.org/ns/activitystreams#Public"],"published":"2026-01-01T00:00:00Z","attachment":[{"type":"Document","mediaType":"video/mp4","url":"https://reader.example/offload/example123/0.mp4"}]}"""
    private fun pinNode(id: String="12345",date: String="2026-01-01T00:00:00Z")="""{"id":"$id","description":"Repair a battery","title":"Repair","created_at":"$date","pinner":{"full_name":"NASA","username":"nasa"},"images":{"237x":{"url":"https://i.pinimg.com/small.jpg"},"orig":{"url":"https://i.pinimg.com/full.jpg"}},"link":"https://nasa.gov/"}"""
    private fun widget(pins: String=pinNode(),username: String="nasa",board: String="")="""{"status":"success","data":{"user":{"username":"$username","full_name":"NASA","about":"Space"},"pins":[$pins]$board}}"""
    private fun routes(): Pair<DarwinRoutes,NetworkGateway> {
        val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
        return routes to NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
    }

    @Test fun rawThreadsContainsOnlyTheRequestedPostAndItsOwnMedia() {
        val page=LargePlatformDiscoveryParsing.fxThreads(payload(fx()),threads,source(Platform.THREADS,"fxthreads-json"))
        val post=page.posts.single()
        assertEquals("Public launch update",post.body);assertEquals("NASA",post.author)
        assertEquals(1700000000000L,post.publishedAt);assertEquals(12L,post.score);assertEquals(3L,post.replyCount)
        assertEquals(threads.canonicalUrl,post.canonicalUrl);assertFalse(post.preview);assertNull(page.next)
    }
    @Test fun threadsRejectsDifferentIdOrAuthorAndEmptyData() {
        val source=source(Platform.THREADS,"fxthreads-json")
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.fxThreads(payload(fx(code="wrong")),threads,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.fxThreads(payload(fx(username="unrelated")),threads,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.fxThreads(payload("""{"id":"example123","author":{"username":"nasa"},"medias":[]}"""),threads,source)}
    }
    @Test fun invalidMediaAndScriptsNeverBecomeExecutableAssets() {
        val source=source(Platform.THREADS,"fxthreads-json")
        val body=fx(body="&lt;b&gt;Hello&lt;/b&gt;").replace("https://media.example/image.jpg","javascript:alert(1)")
        val post=LargePlatformDiscoveryParsing.fxThreads(payload(body),threads,source).posts.single()
        assertTrue(post.media.isEmpty());assertEquals("<b>Hello</b>",post.body)
    }
    @Test fun activityCarriesCaptionDateAndProxyMediaWithoutStatisticsParagraph() {
        val post=LargePlatformDiscoveryParsing.instagramActivity(payload(activity()),instagram,source(Platform.INSTAGRAM,"oginstagram-activity")).posts.single()
        assertEquals("Launch update",post.body);assertEquals("nasa",post.author)
        assertEquals("video",post.media.single().type);assertEquals("video/mp4",post.media.single().mimeType)
        assertEquals(instagram.canonicalUrl,post.canonicalUrl);assertNotNull(post.publishedAt)
    }
    @Test fun activityRejectsUnrelatedPostActorAndProfileDigest() {
        val source=source(Platform.INSTAGRAM,"oginstagram-activity")
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.instagramActivity(payload(activity(url="https://reader.example/p/other")),instagram,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.instagramActivity(payload(activity(actor="https://evil.example/users/nasa")),instagram,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.instagramActivity(payload(activity(url="https://reader.example/nasa")),instagram,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.instagramActivity(payload(activity(actor="https://reader.example/users/other")),instagram.copy(canonicalUrl="https://www.instagram.com/nasa/p/example123/"),source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.instagramActivity(payload(activity().replace("https://www.w3.org/ns/activitystreams#Public","https://reader.example/users/only-me")),instagram,source)}
    }
    @Test fun ordinaryUnsignedActivityIsNotAnAiDeclaration() {
        val post=LargePlatformDiscoveryParsing.instagramActivity(payload(activity()),instagram,source(Platform.INSTAGRAM,"oginstagram-activity")).posts.single()
        assertNull(post.aiDisclosures);assertNull(post.media.single().aiMetadata)
    }
    @Test fun officialEmbedRemainsAnHonestPreviewWithAnExactIdentity() {
        val html="<blockquote class='instagram-media' data-instgrm-permalink='${instagram.canonicalUrl}'><p>Public caption</p><script>fetch('https://tracker.example')</script></blockquote>"
        val node=JsonParser.parseString("""{"author_name":"NASA","thumbnail_url":"https://media.example/image.jpg"}""").asJsonObject.apply {addProperty("html",html)}
        val page=LargePlatformDiscoveryParsing.metaOembed(payload(node.toString()),instagram,source(Platform.INSTAGRAM,"meta-oembed"))
        assertTrue(page.posts.single().preview);assertEquals("Public caption",page.posts.single().body)
        assertTrue(page.notice!!.contains("incomplete"));assertNull(page.next)
    }
    @Test fun officialEmbedRejectsForeignPostAndIframeOnlyShell() {
        val source=source(Platform.INSTAGRAM,"meta-oembed")
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.metaOembed(payload("""{"html":"<blockquote cite='https://www.instagram.com/p/unrelated'><p>Wrong post</p></blockquote>"}"""),instagram,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.metaOembed(payload("""{"html":"<iframe src='https://www.instagram.com/p/example123/embed'></iframe>"}"""),instagram,source)}
        val error=assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.metaOembed(payload("""{"error":{"message":"An access token is required"}}"""),instagram,source)}
        assertEquals(HealthStatus.LOGIN_REQUIRED,error.status)
    }
    @Test fun pinWidgetSelectsExactIdAndLargestPublishedImage() {
        val post=LargePlatformDiscoveryParsing.pinterestWidget(payload(widget(pinNode()+","+pinNode("99999"))),pin,source(Platform.PINTEREST,"pinterest-widgets")).posts.single()
        assertEquals("https://www.pinterest.com/pin/12345/",post.canonicalUrl)
        assertEquals("https://i.pinimg.com/full.jpg",post.media.single().url);assertEquals("Repair a battery",post.body)
        assertEquals(listOf("https://nasa.gov/"),post.links);assertNotNull(post.publishedAt)
    }
    @Test fun widgetProfileUsesReturnedIdentityAndSortsOnlyRealSourceDates() {
        val page=LargePlatformDiscoveryParsing.pinterestWidget(payload(widget(pinNode("1","2026-01-01T00:00:00Z")+","+pinNode("2","2026-02-01T00:00:00Z")+","+pinNode("3",""))),user,source(Platform.PINTEREST,"pinterest-widgets"))
        assertEquals(listOf("2","1","3"),page.posts.map {it.canonicalUrl.trim('/').substringAfterLast('/')})
        assertNull(page.posts.last().publishedAt);assertEquals("NASA",page.profile?.name)
        assertNull(page.next)
    }
    @Test fun observedPinInfoArrayAndProfileUrlOnlyIdentityAreSupported() {
        val source=source(Platform.PINTEREST,"pinterest-widgets")
        val observedPin="""{"status":"success","endpoint_name":"v3_pin_info","data":[${pinNode()}]}"""
        assertEquals("Repair a battery",LargePlatformDiscoveryParsing.pinterestWidget(payload(observedPin),pin,source).posts.single().body)
        val observedProfile="""{"status":"success","data":{"pins":[${pinNode()}],"user":{"profile_url":"https://www.pinterest.com/nasa/","full_name":"NASA"}}}"""
        assertEquals("NASA",LargePlatformDiscoveryParsing.pinterestWidget(payload(observedProfile),user,source).profile?.name)
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.pinterestWidget(payload(observedProfile.replace("/nasa/","/other/")),user,source)}
    }
    @Test fun widgetMustMatchBoardUrlNotMerelyOwnerOrBoardName() {
        val board=user.copy(canonicalUrl="https://www.pinterest.com/nasa/space/")
        val source=source(Platform.PINTEREST,"pinterest-widgets")
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.pinterestWidget(payload(widget(board=",\"board\":{\"name\":\"Space\",\"url\":\"/nasa/other/\"}")),board,source)}
        assertEquals(1,LargePlatformDiscoveryParsing.pinterestWidget(payload(widget(board=",\"board\":{\"name\":\"Space\",\"url\":\"/nasa/space/\"}")),board,source).posts.size)
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.pinterestWidget(payload(widget(username="other")),user,source)}
    }
    @Test fun emptyWidgetCanBeProfileOnlyButNeverAHealthyTimeline() {
        val source=source(Platform.PINTEREST,"pinterest-widgets")
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.pinterestWidget(payload(widget(pins="")),user,source)}
        assertEquals("NASA",LargePlatformDiscoveryParsing.pinterestWidget(payload(widget(pins="")),user,source,true).profile?.name)
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.pinterestWidget(payload(widget(pinNode("99999"))),pin,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.pinterestWidget(payload("""{"status":"success","data":{"pins":[]}}"""),pin,source)}
    }
    @Test fun adapterRestrictsTargetsCapabilitiesAndUnverifiedPagination(): Unit=runBlocking {
        val (routes,gateway)=routes()
        val source=source(Platform.THREADS,"fxthreads-json")
        val adapter=largePlatformDiscoveryAdapter(source,gateway,routes)!!
        assertTrue(adapter.accepts(threads));assertFalse(adapter.accepts(threads.copy(kind=TargetKind.PROFILE)))
        assertEquals(setOf(Capability.POST,Capability.MEDIA),adapter.capabilities)
        assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(threads,"unknown")}}
        val widgets=largePlatformDiscoveryAdapter(source(Platform.PINTEREST,"pinterest-widgets"),gateway,routes)!!
        assertTrue(widgets.accepts(pin));assertTrue(widgets.accepts(user));assertFalse(widgets.accepts(user.copy(kind=TargetKind.SEARCH)))
        assertFalse(widgets.accepts(user.copy(canonicalUrl="https://www.pinterest.com/")))
        assertNull(largePlatformDiscoveryAdapter(source(Platform.SNAPCHAT,"meta-oembed"),gateway,routes))
    }
    @Test fun gatewayRequestsAreBoundedAccountlessAndCannotLeaveTheSelectedService(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(fx()))
            val (routes,gateway)=routes();val source=source(Platform.THREADS,"fxthreads-json").copy(root=server.url("/").toString().trimEnd('/'))
            val adapter=largePlatformDiscoveryAdapter(source,gateway,routes)!!
            assertEquals("Public launch update",adapter.fetch(threads).posts.single().body)
            val request=server.takeRequest();assertEquals("/api/post/example123",request.path)
            assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
            assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.requireSource(payload(fx(),"https://www.threads.com/@nasa/post/example123"),source)}
        }
    }
    @Test fun selectedTorFailsClosedBeforeContactingTheRoute(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val (routes,gateway)=routes();gateway.configure(NetworkPolicy(tor=true,ready=false))
            val adapter=largePlatformDiscoveryAdapter(source(Platform.THREADS,"fxthreads-json").copy(root=server.url("/").toString().trimEnd('/')),gateway,routes)!!
            assertThrows(Exception::class.java){runBlocking {adapter.fetch(threads)}};assertEquals(0,server.requestCount)
        }
    }
    private val tikTokProfile=Target(Platform.TIKTOK,TargetKind.PROFILE,"tiktok","https://www.tiktok.com/@tiktok")
    private fun creatorEmbed(owner: String="tiktok",itemOwner: String="tiktok",privateAccount: Boolean=false)= """<script id="__FRONTITY_CONNECT_STATE__" type="application/json">{"source":{"data":{"/embed/@tiktok":{"userInfo":{"uniqueId":"$owner","nickname":"TikTok","privateAccount":$privateAccount,"followerCount":12},"videoList":[{"id":"12345","desc":"A public post","authorUniqueId":"$itemOwner","playAddr":"https://cdn.example/video.mp4","coverUrl":"https://cdn.example/cover.jpg"}]}}}}</script>"""
    @Test fun creatorEmbedUsesOneScopedHydrationSnapshotWithoutInventingDates() {
        val page=LargePlatformDiscoveryParsing.tikTokCreatorEmbed(payload(creatorEmbed()),tikTokProfile,source(Platform.TIKTOK,"tiktok-creator-embed"))
        assertEquals("TikTok",page.profile?.name);assertEquals(12L,page.profile?.followers)
        assertEquals("A public post",page.posts.single().body);assertEquals("video",page.posts.single().media.single().type)
        assertNull(page.posts.single().publishedAt);assertNull(page.next)
        assertTrue(page.notice!!.contains("publication dates"))
    }
    @Test fun creatorEmbedCanonicalizesTheRequestPathWhileKeepingTheSelectedIdentity():Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(creatorEmbed(owner="Mixed.Creator",itemOwner="Mixed.Creator").replace("/embed/@tiktok","/embed/@mixed.creator")))
            val (routes,gateway)=routes()
            val source=source(Platform.TIKTOK,"tiktok-creator-embed").copy(root=server.url("/").toString().trimEnd('/'))
            val target=tikTokProfile.copy(identity="Mixed.Creator",canonicalUrl="https://www.tiktok.com/@Mixed.Creator")
            val page=largePlatformDiscoveryAdapter(source,gateway,routes)!!.fetch(target)
            assertEquals("/embed/@mixed.creator",server.takeRequest().path)
            assertEquals(target,page.profile!!.target);assertEquals("https://www.tiktok.com/@Mixed.Creator/video/12345",page.posts.single().canonicalUrl)
            assertNull(page.posts.single().publishedAt)
        }
    }
    @Test fun creatorEmbedRequiresOneCaseEquivalentScopeAndStillRejectsAnotherOwner() {
        val target=tikTokProfile.copy(identity="Mixed.Creator",canonicalUrl="https://www.tiktok.com/@Mixed.Creator")
        val source=source(Platform.TIKTOK,"tiktok-creator-embed")
        val body=creatorEmbed(owner="Mixed.Creator",itemOwner="Mixed.Creator").replace("/embed/@tiktok","/embed/@mixed.creator")
        assertEquals(1,LargePlatformDiscoveryParsing.tikTokCreatorEmbed(payload(body),target,source).posts.size)
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikTokCreatorEmbed(payload(body.replace("\"uniqueId\":\"Mixed.Creator\"","\"uniqueId\":\"Different\"")),target,source)}
        val json=JsonParser.parseString(body.substringAfter('>').substringBefore("</script>")).asJsonObject
        val data=json.getAsJsonObject("source").getAsJsonObject("data")
        data.add("/embed/@Mixed.Creator",data["/embed/@mixed.creator"].deepCopy())
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikTokCreatorEmbed(payload("<script id=\"__FRONTITY_CONNECT_STATE__\" type=\"application/json\">$json</script>"),target,source)}
    }
    @Test fun creatorEmbedRejectsWrongCreatorPrivateAndRecommendedPosts() {
        val source=source(Platform.TIKTOK,"tiktok-creator-embed")
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikTokCreatorEmbed(payload(creatorEmbed(owner="unrelated")),tikTokProfile,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikTokCreatorEmbed(payload(creatorEmbed(itemOwner="unrelated")),tikTokProfile,source)}
        val failure=assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikTokCreatorEmbed(payload(creatorEmbed(privateAccount=true)),tikTokProfile,source)}
        assertEquals(HealthStatus.LOGIN_REQUIRED,failure.status)
    }
    @Test fun creatorEmbedAdapterHasNoChronologicalFeedOrSelectedPostCapability(): Unit=runBlocking {
        val (routes,gateway)=routes();val adapter=largePlatformDiscoveryAdapter(source(Platform.TIKTOK,"tiktok-creator-embed"),gateway,routes)!!
        assertTrue(adapter.accepts(tikTokProfile));assertFalse(Capability.FEED in adapter.capabilities);assertFalse(Capability.POST in adapter.capabilities)
        assertFalse(adapter.accepts(tikTokProfile.copy(kind=TargetKind.POST)))
        assertThrows(SourceFailure::class.java){runBlocking {adapter.feed(tikTokProfile)}}
    }
    private val tikTokPost=tikTokProfile.copy(kind=TargetKind.POST,identity="@tiktok/video/12345",canonicalUrl="https://www.tiktok.com/@tiktok/video/12345")
    private fun tikWm(id: String="12345",username: String="tiktok",images: String="[]")="""{"code":0,"data":{"id":"$id","title":"A public caption","create_time":1700000000,"author":{"unique_id":"$username","nickname":"TikTok"},"images":$images,"play":"https://media.example/video.mp4","wmplay":"https://media.example/source-video.mp4","comment_count":5}}"""
    @Test fun tikWmKeepsSelectedIdentityOriginalMediaCaptionAndDate() {
        val post=LargePlatformDiscoveryParsing.tikWm(payload(tikWm()),tikTokPost,source(Platform.TIKTOK,"tikwm")).posts.single()
        assertEquals("A public caption",post.body);assertEquals(1700000000000L,post.publishedAt);assertEquals(5L,post.replyCount)
        assertEquals("https://media.example/source-video.mp4",post.media.single().url);assertEquals(tikTokPost.canonicalUrl,post.canonicalUrl)
        assertFalse(post.preview)
    }
    @Test fun tikWmPhotoPostUsesGalleryWithoutInventingAVideoOrAnAiLabel() {
        val target=tikTokPost.copy(canonicalUrl="https://www.tiktok.com/@tiktok/photo/12345")
        val post=LargePlatformDiscoveryParsing.tikWm(payload(tikWm(images="[\"https://media.example/a.jpg\",\"javascript:alert(1)\",\"https://media.example/b.jpg\"]")),target,source(Platform.TIKTOK,"tikwm")).posts.single()
        assertEquals(2,post.media.size);assertTrue(post.media.all {it.type=="image"});assertNull(post.aiDisclosures)
    }
    @Test fun tikWmRejectsMismatchesAndHonorsJsonQuotaErrors() {
        val source=source(Platform.TIKTOK,"tikwm")
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikWm(payload(tikWm(id="99999")),tikTokPost,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikWm(payload(tikWm(username="other")),tikTokPost,source)}
        val rate=assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikWm(payload("""{"code":-1,"msg":"Free Api Limit 1 request per second"}"""),tikTokPost,source)}
        assertEquals(HealthStatus.RATE_LIMITED,rate.status);assertEquals(60000L,rate.retryAfterMs)
    }
    @Test fun activityDiscoveryOnlyFollowsItsAdvertisedLocalRecordForTheSelectedPost() {
        val source=source(Platform.INSTAGRAM,"oginstagram-activity")
        val html="""<link rel="canonical" href="${instagram.canonicalUrl}"><link rel="alternate" type="application/activity+json" href="https://reader.example/users/nasa/statuses/123456789">"""
        assertEquals("https://reader.example/users/nasa/statuses/123456789",LargePlatformDiscoveryParsing.instagramActivityLink(payload(html),instagram,source))
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.instagramActivityLink(payload(html.replace("reader.example","foreign.example")),instagram,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.instagramActivityLink(payload(html.replace("/p/example123/","/p/unrelated/")),instagram,source)}
    }
    @Test fun newEmbedAndTikWmAdaptersUseSingleScopedGatewayRequests(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(tikWm()))
            val (routes,gateway)=routes();val source=source(Platform.TIKTOK,"tikwm").copy(root=server.url("/").toString().trimEnd('/'))
            val adapter=largePlatformDiscoveryAdapter(source,gateway,routes)!!
            assertTrue(adapter.accepts(tikTokPost));assertFalse(adapter.accepts(tikTokProfile))
            assertEquals(setOf(Capability.POST,Capability.MEDIA),adapter.capabilities)
            assertEquals(1,adapter.fetch(tikTokPost).posts.size)
            val request=server.takeRequest();assertTrue(request.path!!.startsWith("/api/?url="));assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
            val snap=largePlatformDiscoveryAdapter(source(Platform.SNAPCHAT,"snapchat-embed"),gateway,routes)!!
            val selected=routes.recognize("https://www.snapchat.com/spotlight/sample_123")!!
            assertTrue(snap.accepts(selected));assertFalse(snap.accepts(selected.copy(kind=TargetKind.PROFILE)))
            assertFalse(Capability.FEED in snap.capabilities)
        }
    }
    @Test fun snapEmbedReadsOnlyTheRequestedStoryThroughTheGateway(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start()
            fun entry(id: String,username: String)="""{"story":{"storyId":{"value":"$id"},"snapList":[{"snapMediaType":1,"timestampInSec":{"value":"1700000000"},"snapUrls":{"mediaUrl":"https://sc-cdn.example/$id.mp4"}}]},"metadata":{"videoMetadata":{"creator":{"personCreator":{"username":"$username","name":"$username"}}}}}"""
            server.enqueue(MockResponse().setBody("""<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"spotlightFeed":{"spotlightStories":[${entry("sample_123","nasa")},${entry("unrelated","other")}]}}}}</script>"""))
            val (routes,gateway)=routes();val source=source(Platform.SNAPCHAT,"snapchat-embed").copy(root=server.url("/").toString().trimEnd('/'))
            val target=routes.recognize("https://www.snapchat.com/spotlight/sample_123")!!
            val page=largePlatformDiscoveryAdapter(source,gateway,routes)!!.fetch(target)
            assertEquals(1,page.posts.size);assertEquals("nasa",page.posts.single().author)
            assertEquals("https://sc-cdn.example/sample_123.mp4",page.posts.single().media.single().url)
            assertEquals("/spotlight/sample_123/embed",server.takeRequest().path)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun importedTargetsCannotSendAnUnrelatedUrlToAPublicProvider() {
        val (routes,gateway)=routes()
        val tikWm=largePlatformDiscoveryAdapter(source(Platform.TIKTOK,"tikwm"),gateway,routes)!!
        assertFalse(tikWm.accepts(tikTokPost.copy(canonicalUrl="https://other.example/@tiktok/video/12345")))
        assertFalse(tikWm.accepts(tikTokPost.copy(canonicalUrl="https://user:secret@www.tiktok.com/@tiktok/video/12345")))
        assertFalse(tikWm.accepts(tikTokPost.copy(canonicalUrl="https://www.tiktok.com:8443/@tiktok/video/12345")))
        val creator=largePlatformDiscoveryAdapter(source(Platform.TIKTOK,"tiktok-creator-embed"),gateway,routes)!!
        assertFalse(creator.accepts(tikTokProfile.copy(canonicalUrl="https://www.tiktok.com/@different")))
        val widgets=largePlatformDiscoveryAdapter(source(Platform.PINTEREST,"pinterest-widgets"),gateway,routes)!!
        assertFalse(widgets.accepts(pin.copy(canonicalUrl="https://other.example/pin/12345/")))
    }
    private fun comment(id: String="90001",video: String="12345",time: Long=1700000000L,status: Int=1)="""{"id":"$id","video_id":"$video","text":"Public comment $id","create_time":$time,"digg_count":7,"reply_total":2,"status":$status,"user":{"unique_id":"reader","nickname":"Public reader","avatar":"https://media.example/reader.jpg"}}"""
    private fun comments(nodes: String=comment(),cursor: String="20",more: Boolean=true)="""{"code":0,"data":{"comments":[$nodes],"cursor":"$cursor","hasMore":$more}}"""
    @Test fun tikWmCommentsKeepExactParentsDatesAndSuppliedRanking() {
        val page=LargePlatformDiscoveryParsing.tikWmComments(payload(comments(comment("90001",time=100)+","+comment("90002",time=200))),tikTokPost,source(Platform.TIKTOK,"tikwm-comments"))
        assertEquals(listOf("Public comment 90001","Public comment 90002"),page.posts.map {it.body})
        assertEquals(listOf(100000L,200000L),page.posts.map {it.publishedAt})
        assertTrue(page.posts.all {it.parentId==ContentIdentity.key(Platform.TIKTOK,tikTokPost.canonicalUrl)&&it.score==7L&&it.replyCount==2L})
        assertEquals(2,page.posts.map {ContentIdentity.key(it.platform,it.canonicalUrl)}.distinct().size)
        val root=Post("root",Platform.TIKTOK,tikTokPost.canonicalUrl,"TikTok","")
        assertEquals(page.posts,Discussions.comments(root,page))
        assertTrue(page.notice!!.contains("supplied ranking"));assertTrue(page.notice!!.contains("nested replies"))
    }
    @Test fun tikWmCommentsRejectCrossPostDataPrivateEntriesEmptyShellsAndQuota() {
        val source=source(Platform.TIKTOK,"tikwm-comments")
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikWmComments(payload(comments(comment()+","+comment("90002",video="99999"))),tikTokPost,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikWmComments(payload(comments(comment(status=4))),tikTokPost,source)}
        assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikWmComments(payload(comments(nodes="")),tikTokPost,source)}
        val failure=assertThrows(SourceFailure::class.java){LargePlatformDiscoveryParsing.tikWmComments(payload("""{"code":-1,"msg":"Free Api Limit 1 request per second"}"""),tikTokPost,source)}
        assertEquals(HealthStatus.RATE_LIMITED,failure.status);assertEquals(60000L,failure.retryAfterMs)
    }
    @Test fun commentContinuationIsBoundToSourceAndPostAndNeverLoops() {
        val source=source(Platform.TIKTOK,"tikwm-comments")
        val page=LargePlatformDiscoveryParsing.tikWmComments(payload(comments()),tikTokPost,source)
        assertEquals("20",LargePlatformDiscoveryParsing.tikWmCommentToken(page.next,tikTokPost,source))
        assertThrows(IllegalArgumentException::class.java){LargePlatformDiscoveryParsing.tikWmCommentToken(page.next,tikTokPost.copy(canonicalUrl=tikTokPost.canonicalUrl.replace("12345","99999")),source)}
        assertThrows(IllegalArgumentException::class.java){LargePlatformDiscoveryParsing.tikWmCommentToken(page.next,tikTokPost,source.copy(root="https://other.example"))}
        assertNull(LargePlatformDiscoveryParsing.tikWmComments(payload(comments(cursor="20")),tikTokPost,source,"20").next)
        assertNull(LargePlatformDiscoveryParsing.tikWmComments(payload(comments(more=false)),tikTokPost,source).next)
        val selected=tikTokPost.copy(canonicalUrl=tikTokPost.canonicalUrl+"?comment_id=90001")
        assertEquals("post:12345",LargePlatformDiscoveryParsing.tikWmComments(payload(comments()),selected,source).posts.single().parentId)
    }
    @Test fun commentReaderMakesOneAccountlessGatewayRequestAndUsesOnlyItsOwnCursor(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(comments()));server.enqueue(MockResponse().setBody(comments(comment("90002"),cursor="40")))
            val (routes,gateway)=routes();val source=source(Platform.TIKTOK,"tikwm-comments").copy(root=server.url("/").toString().trimEnd('/'))
            val adapter=largePlatformDiscoveryAdapter(source,gateway,routes)!!
            assertEquals(setOf(Capability.COMMENTS),adapter.capabilities);assertTrue(adapter.accepts(tikTokPost));assertFalse(adapter.accepts(tikTokProfile))
            val first=adapter.fetch(tikTokPost);assertEquals(1,first.posts.size)
            val request=server.takeRequest();assertEquals("/api/comment/list?url=12345&count=20&cursor=0",request.path)
            assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"))
            adapter.fetch(tikTokPost,first.next);assertEquals("/api/comment/list?url=12345&count=20&cursor=20",server.takeRequest().path)
            assertEquals(2,server.requestCount)
            gateway.configure(NetworkPolicy(tor=true,ready=false))
            assertThrows(Exception::class.java){runBlocking {adapter.fetch(tikTokPost)}};assertEquals(2,server.requestCount)
        }
    }
}
