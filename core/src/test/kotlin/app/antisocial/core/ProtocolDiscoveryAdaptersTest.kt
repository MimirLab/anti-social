package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProtocolDiscoveryAdaptersTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private fun obj(raw: String)=JsonParser.parseString(raw).asJsonObject
    private fun target(platform: Platform,url: String,kind: TargetKind=TargetKind.PROFILE)=Target(platform,kind,url.substringAfterLast('/'),url)
    private fun source(platform: Platform,frontend: String,root: String,role: SourceRole=SourceRole.ALTERNATE)=Source(platform,frontend,root,role=role)
    private fun gateway(server: MockWebServer)=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {
        val parsed=url.toHttpUrlOrNull()!!;return FilterDecision(false,rewrittenUrl=server.url(parsed.encodedPath+(parsed.encodedQuery?.let {"?$it"}.orEmpty())).toString())
    }},true)
    private fun adapter(server: MockWebServer,platform: Platform=Platform.X,frontend: String="fxembed-api",root: String="https://api.fxtwitter.com",role: SourceRole=SourceRole.ALTERNATE)=protocolDiscoveryAdapter(source(platform,frontend,root,role),gateway(server),routes)!!
    private fun fx(id: String="20",handle: String="jack",platform: Platform=Platform.X,extra: String=""): String {
        val url=if(platform==Platform.X)"https://x.com/$handle/status/$id"else "https://bsky.app/profile/$handle/post/$id"
        return """{"type":"status","provider":"${platform.id}","id":"$id","url":"$url","text":"Public body $id","created_timestamp":1792152000,"author":{"screen_name":"$handle","name":"Jack"}$extra}"""
    }
    private fun fxProvenance(platform: Platform=Platform.X)=Provenance(source(platform,"fxembed-api","https://api.fx.example"),1,"https://api.fx.example/2/status/20")

    @Test fun fxReadsSelectedPostAndBoundedActualMedia(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"code":200,"status":${fx(extra=",\"media\":{\"photos\":[{\"url\":\"https://images.example/a.jpg\",\"altText\":\"A picture\"}],\"videos\":[{\"url\":\"https://videos.example/a.m3u8\",\"formats\":[{\"container\":\"mp4\",\"codec\":\"h264\",\"bitrate\":100,\"url\":\"https://videos.example/a.mp4\"}]}]}")}}"""))
            val page=adapter(server).fetch(routes.target(Platform.X,"https://x.com/jack/status/20"));val post=page.posts.single()
            assertEquals("Public body 20",post.body);assertEquals(1792152000000L,post.publishedAt);assertEquals(2,post.media.size);assertEquals("https://videos.example/a.mp4",post.media.last().url);assertEquals("A picture",post.media.first().description)
            assertEquals("/2/status/20",server.takeRequest().path);assertNull(page.next)
        }
    }
    @Test fun fxDoesNotWalkQuotesRecommendationsOrTombstones() {
        val p=ProtocolDiscoveryParsing.fxStatus(obj(fx(extra=",\"quote\":"+fx("99","someone")+",\"recommendations\":["+fx("100","other")+"]")),Platform.X,fxProvenance(),routes)!!
        assertEquals("Public body 20",p.body)
        assertNull(ProtocolDiscoveryParsing.fxStatus(obj(fx().replace("\"status\"","\"tombstone\"")),Platform.X,fxProvenance(),routes))
        assertNull(ProtocolDiscoveryParsing.fxStatus(obj(fx().replace("\"screen_name\":\"jack\"","\"screen_name\":\"wrong\"")),Platform.X,fxProvenance(),routes))
        assertNull(ProtocolDiscoveryParsing.fxStatus(obj(fx().replace("\"twitter\"","\"bluesky\"")),Platform.X,fxProvenance(),routes))
    }
    @Test fun fxRejectsProtectedAuthorsAndEmptyBranding() {
        assertNull(ProtocolDiscoveryParsing.fxStatus(obj(fx().replace("\"name\":\"Jack\"","\"name\":\"Jack\",\"protected\":true")),Platform.X,fxProvenance(),routes))
        assertNull(ProtocolDiscoveryParsing.fxStatus(obj(fx().replace("Public body 20","")),Platform.X,fxProvenance(),routes))
    }
    @Test fun fxRejectsWrongSelectedPostInsteadOfCallingItHealthy(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"code":200,"status":${fx("21")}}"""))
            val failure=assertThrows(SourceFailure::class.java){runBlocking {adapter(server).fetch(routes.target(Platform.X,"https://x.com/jack/status/20"))}}
            assertEquals(HealthStatus.EMPTY,failure.status)
        }
    }
    @Test fun fxTimelineFiltersForeignAccountsAndBindsCursor(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"code":200,"results":[${fx()},${fx("99","other")}],"cursor":{"bottom":"opaque+/="}}"""))
            val reader=adapter(server);val selected=routes.target(Platform.X,"jack");val page=reader.fetch(selected);assertEquals(1,page.posts.size);assertNotNull(page.next)
            assertThrows(IllegalArgumentException::class.java){runBlocking {reader.fetch(routes.target(Platform.X,"other"),page.next)}}
            server.enqueue(MockResponse().setBody("""{"code":200,"results":[${fx("19")}]}"""));reader.fetch(selected,page.next);server.takeRequest();val request=server.takeRequest();assertEquals("opaque+/=",request.requestUrl!!.queryParameter("cursor"));assertEquals(2,server.requestCount)
        }
    }
    @Test fun fxSearchUsesLatestAndNativeAccountScope(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"code":200,"results":[${fx()}]}"""))
            val reader=adapter(server);reader.fetch(routes.search(Platform.X,"space",SearchScopes.normalize(Platform.X,SearchScopeKind.ACCOUNT,"jack")))
            val request=server.takeRequest();assertEquals("latest",request.requestUrl!!.queryParameter("feed"));assertEquals("from:jack (space)",request.requestUrl!!.queryParameter("q"));assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun fxBlueskyUsesCorrectRouteAndPostIdentity(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"code":200,"status":${fx("3abc","bsky.app",Platform.BLUESKY)}}"""))
            val page=adapter(server,Platform.BLUESKY,root="https://api.fxbsky.app").fetch(routes.target(Platform.BLUESKY,"https://bsky.app/profile/bsky.app/post/3abc"))
            assertEquals("https://bsky.app/profile/bsky.app/post/3abc",page.posts.single().canonicalUrl);assertEquals("/2/status/bsky.app/3abc",server.takeRequest().path)
        }
    }
    @Test fun blueskyFractionalSecondsRemainChronological() {
        val post=ProtocolDiscoveryParsing.fxStatus(obj(fx("3abc","bsky.app",Platform.BLUESKY).replace("1792152000","1792181371.738")),Platform.BLUESKY,fxProvenance(Platform.BLUESKY),routes)!!
        assertEquals(1792181371738L,post.publishedAt)
    }
    @Test fun fxBlueskyDidPostRequiresExactAtUriProof(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val did="did:plc:z72i7hdynmk6r22z27h6tvur";val selected=routes.target(Platform.BLUESKY,"https://bsky.app/profile/$did/post/3abc");val reader=adapter(server,Platform.BLUESKY,root="https://api.fxbsky.app")
            server.enqueue(MockResponse().setBody("""{"code":200,"status":${fx("3abc","bsky.app",Platform.BLUESKY,",\"at_uri\":\"at://$did/app.bsky.feed.post/3abc\"")}}"""));assertEquals(selected.canonicalUrl,reader.fetch(selected).posts.single().canonicalUrl)
            server.enqueue(MockResponse().setBody("""{"code":200,"status":${fx("3abc","bsky.app",Platform.BLUESKY,",\"at_uri\":\"at://did:plc:wrong/app.bsky.feed.post/3abc\"")}}"""));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(selected)}}
        }
    }
    @Test fun fxBlueskyDidTimelineDoesNotConfuseOtherActorsRecords(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val did="did:plc:z72i7hdynmk6r22z27h6tvur";val selected=routes.target(Platform.BLUESKY,did);val reader=adapter(server,Platform.BLUESKY,root="https://api.fxbsky.app")
            server.enqueue(MockResponse().setBody("""{"code":200,"results":[${fx("3abc","bsky.app",Platform.BLUESKY,",\"at_uri\":\"at://$did/app.bsky.feed.post/3abc\"")},${fx("other","bsky.app",Platform.BLUESKY,",\"at_uri\":\"at://did:plc:wrong/app.bsky.feed.post/other\"")}]}"""));assertEquals(1,reader.fetch(selected).posts.size)
        }
    }
    @Test fun fxErrorsRemainFailuresEvenWithOldCachedStatusPayload(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"code":429,"status":${fx()}}"""))
            val failure=assertThrows(SourceFailure::class.java){runBlocking {adapter(server).fetch(routes.target(Platform.X,"https://x.com/jack/status/20"))}}
            assertEquals(HealthStatus.RATE_LIMITED,failure.status)
        }
    }
    @Test fun allProtocolTrafficFailsClosedBeforeSocketWhenTorIsUnavailable(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val gateway=gateway(server);gateway.configure(NetworkPolicy(tor=true,ready=false));val reader=protocolDiscoveryAdapter(source(Platform.X,"fxembed-api","https://api.fxtwitter.com"),gateway,routes)!!
            assertThrows(java.io.IOException::class.java){runBlocking {reader.fetch(routes.target(Platform.X,"https://x.com/jack/status/20"))}};assertEquals(0,server.requestCount)
        }
    }
    @Test fun vxEmbedReadsOnlySelectedPostAndIgnoresNestedRetweets(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"tweetID":"20","tweetURL":"https://twitter.com/jack/status/20","user_screen_name":"jack","user_name":"Jack","text":"Public body","date_epoch":1792152000,"media_extended":[{"url":"https://images.example/a.jpg","type":"image"}],"retweet":{"text":"Unrelated"}}"""))
            val reader=adapter(server,frontend="vxembed-api",root="https://api.vxtwitter.com");assertFalse(reader.accepts(routes.target(Platform.X,"jack")));assertFalse(Capability.FEED in reader.capabilities)
            val page=reader.fetch(routes.target(Platform.X,"https://x.com/jack/status/20"));assertEquals("Public body",page.posts.single().body);assertEquals("/jack/status/20",server.takeRequest().path)
        }
    }

    private fun tumblrPost(id: String="20",extra: String="")="""{"id":"$id","url":"https://staff.tumblr.com/post/$id","type":"regular","regular-title":"Title","regular-body":"<p>A Tumblr post</p>","unix-timestamp":1792152000$extra}"""
    private fun tumblr(vararg posts: String)="""var tumblr_api_read = {"tumblelog":{"name":"staff","title":"Tumblr Staff"},"posts-total":${posts.size},"posts":[${posts.joinToString(",")}]};"""
    @Test fun tumblrLegacyReadsKnownJsonWrapperAndExactPost(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(tumblr(tumblrPost(),tumblrPost("21"))))
            val page=adapter(server,Platform.TUMBLR,"tumblr-legacy","https://www.tumblr.com",SourceRole.ORIGINAL).fetch(routes.target(Platform.TUMBLR,"https://staff.tumblr.com/post/20"))
            assertEquals(1,page.posts.size);assertEquals("A Tumblr post",page.posts.single().body);assertEquals("/api/read/json?num=20&id=20",server.takeRequest().path)
        }
    }
    @Test fun tumblrRefusesArbitraryJavascriptOrJsonp() {
        assertThrows(SourceFailure::class.java){ProtocolDiscoveryParsing.tumblrDocument("run("+tumblr(tumblrPost())+")")}
        assertThrows(SourceFailure::class.java){ProtocolDiscoveryParsing.tumblrDocument(tumblr(tumblrPost())+"doSomething();")}
        assertThrows(SourceFailure::class.java){ProtocolDiscoveryParsing.tumblrDocument("callback({\"posts\":[]});")}
    }
    @Test fun tumblrWrongBlogIsNotAWorkingFeed(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(tumblr(tumblrPost()).replace("\"name\":\"staff\"","\"name\":\"wrong\"")))
            assertThrows(SourceFailure::class.java){runBlocking {adapter(server,Platform.TUMBLR,"tumblr-legacy","https://www.tumblr.com").fetch(routes.target(Platform.TUMBLR,"staff"))}}
        }
    }
    @Test fun tumblrGalleryUsesItsOwnPicturesAndExcludesWrongOwner() {
        val provenance=Provenance(source(Platform.TUMBLR,"tumblr-legacy","https://www.tumblr.com"),1,"https://staff.tumblr.com/api/read/json")
        val post=obj("""{"id":"20","url":"https://staff.tumblr.com/post/20","type":"photo","photo-caption":"<p>Gallery</p>","photos":[{"photo-url-1280":"https://64.media.tumblr.com/a.jpg"},{"photo-url-500":"https://64.media.tumblr.com/b.jpg"}]}""")
        val selected=routes.target(Platform.TUMBLR,"staff");assertEquals(2,ProtocolDiscoveryParsing.tumblrPost(post,"staff",selected,provenance,routes)!!.media.size)
        post.addProperty("url","https://wrong.tumblr.com/post/20");assertNull(ProtocolDiscoveryParsing.tumblrPost(post,"staff",selected,provenance,routes))
    }
    @Test fun tumblrSearchIsIneligibleAndDoesNotIssueNetworkRequests(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.TUMBLR,"tumblr-legacy","https://www.tumblr.com");val search=routes.search(Platform.TUMBLR,"hello");assertFalse(reader.accepts(search));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(search)}};assertEquals(0,server.requestCount)}
    }

    private fun rss(profile: String,post: String,title: String="Public body")="""<rss version="2.0"><channel><title>Alice</title><link>$profile</link><item><link>$post</link><description>$title</description><pubDate>Fri, 16 Oct 2026 12:00:00 +0000</pubDate></item></channel></rss>"""
    @Test fun lemmyFeedUsesNewSortAndKeepsServerLocalPostIdentity(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(rss("https://lemmy.example/c/technology","https://lemmy.example/post/12")))
            val reader=adapter(server,Platform.LEMMY,"lemmy-rss","https://lemmy.example");val page=reader.fetch(target(Platform.LEMMY,"https://lemmy.example/c/technology",TargetKind.COMMUNITY))
            assertEquals("https://lemmy.example/post/12",page.posts.single().canonicalUrl);assertEquals("/feeds/c/technology.xml?sort=New",server.takeRequest().path);assertFalse(Capability.COMMENTS in reader.capabilities);assertNull(page.next)
        }
    }
    @Test fun lemmyFeedRejectsWrongCommunityAndForeignServerPosts(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.LEMMY,"lemmy-rss","https://lemmy.example");val target=target(Platform.LEMMY,"https://lemmy.example/c/technology",TargetKind.COMMUNITY)
            server.enqueue(MockResponse().setBody(rss("https://lemmy.example/c/wrong","https://lemmy.example/post/12")));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target)}}
            server.enqueue(MockResponse().setBody(rss("https://lemmy.example/c/technology","https://foreign.example/post/12")));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target)}}
        }
    }
    @Test fun misskeyNativeFeedRequiresMatchingChannelBeforeUsingLocalIds(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(rss("https://miss.example/@alice","https://miss.example/notes/12")))
            val reader=adapter(server,Platform.MISSKEY,"misskey-rss","https://miss.example");val page=reader.fetch(target(Platform.MISSKEY,"https://miss.example/@alice"))
            assertEquals("Public body",page.posts.single().body);assertEquals("/@alice.rss",server.takeRequest().path);assertFalse(reader.accepts(target(Platform.MISSKEY,"https://miss.example/notes/12",TargetKind.POST)))
        }
    }
    @Test fun feedHtmlShellAndEntityDeclarationsAreNotWorkingContent(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.MISSKEY,"misskey-rss","https://miss.example");val selected=target(Platform.MISSKEY,"https://miss.example/@alice")
            server.enqueue(MockResponse().setBody("<html><title>Misskey</title></html>"));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(selected)}}
            server.enqueue(MockResponse().setBody("<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"https://evil.example\">]>"+rss("https://miss.example/@alice","https://miss.example/notes/12")));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(selected)}};assertEquals(2,server.requestCount)
        }
    }
    @Test fun federatedInstancesNeverInterchangeLocalPostIdsOrUserFeeds(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.MISSKEY,"misskey-discussion","https://other.example")
            val error=assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(target(Platform.MISSKEY,"https://miss.example/notes/12",TargetKind.POST))}};assertEquals(HealthStatus.UNSUPPORTED,error.status);assertEquals(0,server.requestCount)
        }
    }
    private fun note(id: String="12",extra: String="")="""{"id":"$id","text":"Public note $id","visibility":"public","createdAt":"2026-10-16T12:00:00Z","user":{"username":"alice"}$extra}"""
    @Test fun misskeyDirectRepliesRetainOnlyVerifiedParentRelationships(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(note()));server.enqueue(MockResponse().setBody("["+note("13",",\"replyId\":\"12\"")+","+note("14",",\"replyId\":\"other\"")+","+note("15",",\"replyId\":\"12\",\"visibility\":\"followers\"")+"]"))
            val page=adapter(server,Platform.MISSKEY,"misskey-discussion","https://miss.example").fetch(target(Platform.MISSKEY,"https://miss.example/notes/12",TargetKind.POST));assertEquals(2,page.posts.size);assertEquals(page.posts.first().id,page.posts.last().parentId);assertTrue(page.notice!!.contains("direct replies"));assertEquals("/api/notes/show",server.takeRequest().path);val request=server.takeRequest();assertEquals("/api/notes/replies",request.path);assertEquals("12",obj(request.body.readUtf8())["noteId"].asString)
        }
    }
    @Test fun misskeyEmptyRepliesDoNotBecomeSyntheticComments(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(note()));server.enqueue(MockResponse().setBody("[]"))
            val page=adapter(server,Platform.MISSKEY,"misskey-discussion","https://miss.example").fetch(target(Platform.MISSKEY,"https://miss.example/notes/12",TargetKind.POST));assertEquals(1,page.posts.size);assertTrue(Discussions.comments(page.posts.single(),page).isEmpty())
        }
    }
    @Test fun misskeyWrongSelectedNoteCannotReturnReplies(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(note("wrong")))
            assertThrows(SourceFailure::class.java){runBlocking {adapter(server,Platform.MISSKEY,"misskey-discussion","https://miss.example").fetch(target(Platform.MISSKEY,"https://miss.example/notes/12",TargetKind.POST))}};assertEquals(1,server.requestCount)
        }
    }
    private fun archivePost(id: String="abc",owner: String="alice",subreddit: String="Android",extra: String="")="""{"id":"$id","permalink":"/r/$subreddit/comments/$id/a_title/","author":"$owner","subreddit":"$subreddit","title":"Historical title","selftext":"Historical body","created_utc":1577799850$extra}"""
    @Test fun arcticShiftHasNoSubscriptionOrHeartbeatCapability(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.REDDIT,"arctic-shift","https://arctic-shift.photon-reddit.com",SourceRole.CACHE)
            assertFalse(Capability.FEED in reader.capabilities);assertFalse(Capability.COMMENTS in reader.capabilities);assertFalse(reader.accepts(target(Platform.REDDIT,"https://www.reddit.com/r/Android",TargetKind.FEED)))
            assertThrows(SourceFailure::class.java){runBlocking {reader.feed(routes.target(Platform.REDDIT,"r/Android",TargetKind.COMMUNITY))}}
            assertThrows(SourceFailure::class.java){reader.sentinel(Capability.POST)};assertEquals(0,server.requestCount)
        }
    }
    @Test fun archiveUnknownCaptureTimeIsNotInvented() {
        val provenance=Provenance(source(Platform.REDDIT,"arctic-shift","https://arctic-shift.photon-reddit.com",SourceRole.CACHE),1792241398000,"https://arctic-shift.photon-reddit.com/api/posts/ids?ids=abc")
        val post=ProtocolDiscoveryParsing.redditArchivePost(obj(archivePost()),provenance,routes)!!;assertNull(post.recoveredArchive);assertEquals(SourceRole.CACHE,post.provenance!!.source.role)
        val known=ProtocolDiscoveryParsing.redditArchivePost(obj(archivePost(extra=",\"retrieved_on\":1577800000")),provenance,routes)!!;assertNull(known.recoveredArchive)
        assertTrue(known.provenance!!.cached)
    }
    @Test fun archiveRejectsForeignPermalinksAndMismatchingPostIds() {
        val provenance=Provenance(source(Platform.REDDIT,"arctic-shift","https://arctic-shift.photon-reddit.com",SourceRole.CACHE),1,"https://arctic-shift.photon-reddit.com")
        val data=obj(archivePost());data.addProperty("permalink","https://evil.example/r/Android/comments/abc/a_title/");assertNull(ProtocolDiscoveryParsing.redditArchivePost(data,provenance,routes))
        data.addProperty("permalink","/r/Android/comments/wrong/a_title/");assertNull(ProtocolDiscoveryParsing.redditArchivePost(data,provenance,routes))
    }
    @Test fun archiveSelectedPostAndVerifiedReplyRelationshipsRemainHistorical(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"data":[${archivePost(extra=",\"retrieved_on\":1577800000")}]}"""));server.enqueue(MockResponse().setBody("""{"data":[{"id":"one","link_id":"t3_abc","parent_id":"t3_abc","author":"bob","body":"Root reply","created_utc":1577799900},{"id":"two","link_id":"t3_abc","parent_id":"t1_one","body":"Nested reply","created_utc":1577799999},{"id":"wrong","link_id":"t3_other","parent_id":"t3_other","body":"Another thread"}]}"""))
            val reader=adapter(server,Platform.REDDIT,"arctic-shift-comments","https://arctic-shift.photon-reddit.com",SourceRole.CACHE);val page=reader.fetch(routes.target(Platform.REDDIT,"https://www.reddit.com/r/Android/comments/abc/a_title/"));assertEquals(3,page.posts.size)
            val comments=Discussions.comments(page.posts.first(),page);assertEquals(2,comments.size);assertEquals("comment:abc:one",comments.first {it.body=="Nested reply"}.parentId);assertTrue(page.notice!!.contains("Historical Arctic Shift"));assertEquals("/api/posts/ids?ids=abc",server.takeRequest().path);assertEquals("abc",server.takeRequest().requestUrl!!.queryParameter("link_id"))
        }
    }
    @Test fun archiveTimelineValidatesOwnerAndCommunityBeforeReturningData(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"data":[${archivePost()},${archivePost("wrong","alice","Wrong")}]}"""))
            val reader=adapter(server,Platform.REDDIT,"arctic-shift","https://arctic-shift.photon-reddit.com",SourceRole.CACHE);val page=reader.fetch(routes.target(Platform.REDDIT,"r/Android",TargetKind.COMMUNITY));assertEquals(1,page.posts.size);assertTrue(page.notice!!.contains("not a live refresh"));assertEquals("Android",server.takeRequest().requestUrl!!.queryParameter("subreddit"))
            server.enqueue(MockResponse().setBody("""{"data":[${archivePost(owner="someone_else")}]}"""));assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(routes.target(Platform.REDDIT,"user/alice"))}}
        }
    }
    @Test fun archiveSelectedPostDoesNotWaitForASeparateCommentRequest(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("""{"data":[${archivePost()}]}"""))
            val reader=adapter(server,Platform.REDDIT,"arctic-shift","https://arctic-shift.photon-reddit.com",SourceRole.CACHE);val page=reader.fetch(routes.target(Platform.REDDIT,"https://www.reddit.com/r/Android/comments/abc/a_title/"));assertEquals(1,page.posts.size);assertEquals(1,server.requestCount);assertNull(page.next)
            val comments=adapter(server,Platform.REDDIT,"arctic-shift-comments","https://arctic-shift.photon-reddit.com",SourceRole.CACHE);assertTrue(Capability.COMMENTS in comments.capabilities);assertFalse(comments.accepts(routes.target(Platform.REDDIT,"r/Android",TargetKind.COMMUNITY)))
        }
    }
    @Test fun archiveWideSearchAndDirectCommentLinksAreIneligible(): Unit=runBlocking {
        MockWebServer().use {server->server.start();val reader=adapter(server,Platform.REDDIT,"arctic-shift","https://arctic-shift.photon-reddit.com",SourceRole.CACHE);val search=routes.search(Platform.REDDIT,"battery")
            assertThrows(SourceFailure::class.java){runBlocking {reader.fetch(search)}};assertFalse(reader.accepts(routes.target(Platform.REDDIT,"https://www.reddit.com/r/Android/comments/abc/title/def/")));assertEquals(0,server.requestCount)
        }
    }
    private fun sotwe(vararg posts: String)="""{"key":"jack","info":{"id":"12","screenName":"jack","name":"Jack"},"data":[${posts.joinToString(",")}],"after":"unverified-cursor"}"""
    private fun sotwePost(id: String="20",extra: String="")="""{"id":"$id","createdAt":1792179740000,"text":"Public Sotwe post","favoriteCount":4$extra}"""
    @Test fun sotweVerifiesAccountKeyAndParsesOnlyOwnDatedTimeline(): Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(sotwe(sotwePost(),sotwePost("21",",\"user\":{\"screenName\":\"someone_else\"}"),sotwePost("22",",\"retweetedStatus\":{\"text\":\"A retweet\"}"))))
            val reader=adapter(server,Platform.X,"sotwe-api","https://www.sotwe.com");val page=reader.fetch(routes.target(Platform.X,"jack"));assertEquals(1,page.posts.size);assertEquals(1792179740000L,page.posts.single().publishedAt);assertEquals("Jack",page.profile!!.name);assertNull(page.next);assertEquals("/api/v3/user/jack/",server.takeRequest().path)
            assertFalse(reader.accepts(routes.target(Platform.X,"https://x.com/jack/status/20")));assertFalse(Capability.COMMENTS in reader.capabilities)
        }
    }
    @Test fun sotweIgnoresRecommendationsAndNestedConversationObjects() {
        val data=obj(sotwe(sotwePost(extra=",\"conversation\":["+sotwePost("99")+"]")));data.add("whoToFollow",JsonParser.parseString("["+sotwePost("100")+"]"))
        val page=ProtocolDiscoveryParsing.sotwe(data,routes.target(Platform.X,"jack"),fxProvenance());assertEquals(1,page.posts.size);assertEquals("https://x.com/jack/status/20",page.posts.single().canonicalUrl)
    }
    @Test fun sotweRejectsWrongAccountProtectedAccountAndEmptyUndatedCards() {
        val selected=routes.target(Platform.X,"jack");val provenance=fxProvenance()
        assertThrows(SourceFailure::class.java){ProtocolDiscoveryParsing.sotwe(obj(sotwe(sotwePost()).replace("\"key\":\"jack\"","\"key\":\"wrong\"")),selected,provenance)}
        assertThrows(SourceFailure::class.java){ProtocolDiscoveryParsing.sotwe(obj(sotwe(sotwePost()).replace("\"name\":\"Jack\"","\"name\":\"Jack\",\"userProtected\":true")),selected,provenance)}
        assertThrows(SourceFailure::class.java){ProtocolDiscoveryParsing.sotwe(obj(sotwe(sotwePost().replace("1792179740000","null"))),selected,provenance)}
        assertThrows(SourceFailure::class.java){ProtocolDiscoveryParsing.sotwe(obj(sotwe(sotwePost(extra=",\"truncated\":true"))),selected,provenance)}
    }
    @Test fun sotweUsesProgressiveMp4VariantAndPublicExpandedLinks() {
        val data=obj(sotwe(sotwePost(extra=",\"mediaEntities\":[{\"type\":\"video\",\"mediaURL\":\"https://images.example/preview.jpg\",\"videoInfo\":{\"variants\":[{\"type\":\"application/x-mpegURL\",\"url\":\"https://video.example/a.m3u8\"},{\"type\":\"video/mp4\",\"bitrate\":100,\"url\":\"https://video.example/a.mp4\"}]}}],\"urlEntities\":[{\"expandedURL\":\"https://example.com/page\"}]")))
        val post=ProtocolDiscoveryParsing.sotwe(data,routes.target(Platform.X,"jack"),fxProvenance()).posts.single();assertEquals("https://video.example/a.mp4",post.media.single().url);assertEquals(listOf("https://example.com/page"),post.links)
    }
    @Test fun alternateRedirectCannotClaimHealthFromOriginalOrAnotherProvider() {
        val selected=source(Platform.X,"fxembed-rss","https://fxtwitter.com")
        assertThrows(SourceFailure::class.java){protocolDiscoveryProvenance(selected,Payload("<rss/>","https://x.com/jack/rss","text/xml",200))}
        assertThrows(SourceFailure::class.java){protocolDiscoveryProvenance(selected,Payload("<rss/>","https://other.example/jack/rss","text/xml",200))}
        val result=protocolDiscoveryProvenance(selected,Payload("<rss/>","https://fxtwitter.com/jack/feed.xml","text/xml",200));assertEquals(selected,result.source)
    }
}

/** Opt-in replay of the research gateway's actual captured payloads, never a unit-test network call. */
object ProtocolDiscoveryFixtureAudit {
    @JvmStatic fun main(args: Array<String>)=runBlocking {
        require(args.size==1){"Pass the source-cartography evidence directory"}
        val root=java.io.File(args.single());val loader=ProtocolDiscoveryFixtureAudit::class.java
        fun asset(name: String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
        val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner);val results=mutableListOf<Map<String,Any>>()
        val cases=listOf(
            Triple(Source(Platform.X,"fxembed-api","https://api.fxtwitter.com"),routes.target(Platform.X,"NASA"),0),
            Triple(Source(Platform.BLUESKY,"fxembed-api","https://api.fxbsky.app"),routes.target(Platform.BLUESKY,"bsky.app"),1),
            Triple(Source(Platform.TUMBLR,"tumblr-legacy","https://www.tumblr.com",role=SourceRole.ORIGINAL),routes.target(Platform.TUMBLR,"staff"),2),
            Triple(Source(Platform.LEMMY,"lemmy-rss","https://lemmy.world",role=SourceRole.ORIGINAL),routes.target(Platform.LEMMY,"technology",TargetKind.COMMUNITY),3)
        )
        for((source,target,index) in cases)MockWebServer().use {server->
            val payload=com.google.gson.Gson().fromJson(java.io.File(root,"initial-network/response-$index.json").readText(),Payload::class.java)
            server.start();server.enqueue(MockResponse().setHeader("Content-Type",payload.contentType).setBody(payload.text))
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {val parsed=url.toHttpUrlOrNull()!!;return FilterDecision(false,rewrittenUrl=server.url(parsed.encodedPath+(parsed.encodedQuery?.let {"?$it"}.orEmpty())).toString())}},true)
            val page=protocolDiscoveryAdapter(source,gateway,routes)!!.fetch(target)
            check(page.posts.isNotEmpty());check(page.posts.all {it.publishedAt!=null});check(page.posts.zipWithNext().all {it.first.publishedAt!!>=it.second.publishedAt!!})
            results+=mapOf("source" to source.key,"target" to target.canonicalUrl,"capturedFrom" to payload.finalUrl,"posts" to page.posts.size,"media" to page.posts.sumOf {it.media.size},"datedPosts" to page.posts.count {it.publishedAt!=null},"passed" to true)
            gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
        }
        for(index in listOf(2,3)) {
            val payload=com.google.gson.Gson().fromJson(java.io.File(root,"expanded-network/response-$index.json").readText(),Payload::class.java)
            val provenance=Provenance(Source(Platform.REDDIT,"arctic-shift","https://arctic-shift.photon-reddit.com",role=SourceRole.CACHE),System.currentTimeMillis(),payload.finalUrl)
            val nodes=JsonParser.parseString(payload.text).asJsonObject.getAsJsonArray("data").map {it.asJsonObject};val posts=nodes.mapNotNull {ProtocolDiscoveryParsing.redditArchivePost(it,provenance,routes)}
            check(posts.size==nodes.size&&posts.isNotEmpty());check(posts.all {it.publishedAt!=null&&it.provenance!!.source.role==SourceRole.CACHE})
            check(posts.all {it.provenance!!.cached&&it.recoveredArchive==null})
            results+=mapOf("source" to provenance.source.key,"capturedFrom" to payload.finalUrl,"posts" to posts.size,"media" to posts.sumOf {it.media.size},"datedPosts" to posts.count {it.publishedAt!=null},"capturedRecordDates" to nodes.count {it.has("retrieved_on")},"usesReplayOnlyRecoveryModel" to false,"passed" to true)
        }
        val sotwePayload=com.google.gson.Gson().fromJson(java.io.File(root,"third-network/response-4.json").readText(),Payload::class.java)
        val sotweProvenance=Provenance(Source(Platform.X,"sotwe-api","https://www.sotwe.com"),System.currentTimeMillis(),sotwePayload.finalUrl)
        val sotwePage=ProtocolDiscoveryParsing.sotwe(JsonParser.parseString(sotwePayload.text).asJsonObject,routes.target(Platform.X,"NASA"),sotweProvenance)
        check(sotwePage.posts.isNotEmpty()&&sotwePage.posts.all {it.publishedAt!=null});check(sotwePage.next==null)
        results+=mapOf("source" to sotweProvenance.source.key,"capturedFrom" to sotwePayload.finalUrl,"posts" to sotwePage.posts.size,"media" to sotwePage.posts.sumOf {it.media.size},"datedPosts" to sotwePage.posts.count {it.publishedAt!=null},"passed" to true)
        val out=java.io.File(root,"protocols/fixture-validation.json");out.parentFile.mkdirs();out.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(results));println(out.readText())
    }
}
