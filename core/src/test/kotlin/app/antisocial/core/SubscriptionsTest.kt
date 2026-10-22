package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SubscriptionsTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    private val body="A detailed update about our project, including the latest work and the next steps for the community."
    private fun target(p: Platform,handle: String="asfixture")=routes.target(p,handle)
    private fun account(p: Platform,person: String?="person-one",handle: String="asfixture")=Follow(p.id+handle,target(p,handle),handle,personId=person,personName="anti social fixture")
    private fun post(p: Platform,id: String="123",text: String=body,time: Long?=1_800_000_000_000L): Post {
        val url=when(p){Platform.X->"https://x.com/asfixture/status/$id";Platform.BLUESKY->"https://bsky.app/profile/asfixture/post/$id";Platform.THREADS->"https://www.threads.com/@asfixture/post/$id";else->error("fixture")}
        return Post(ContentIdentity.key(p,url),p,url,"asfixture",text,publishedAt=time)
    }
    @Test fun linkedAccountsGroupExactSubstantialCrossPostsButNotOtherPeopleOrReplies() {
        val x=post(Platform.X);val b=post(Platform.BLUESKY);val follows=listOf(account(Platform.X),account(Platform.BLUESKY))
        assertEquals(2,CrossPosts.group(listOf(x,b),follows).single().posts.size)
        assertEquals(2,CrossPosts.group(listOf(x,b),follows.map {it.copy(personId=null)}).size)
        assertEquals(2,CrossPosts.group(listOf(x,b.copy(author="another",canonicalUrl=b.canonicalUrl.replace("asfixture","another"))),follows).size)
        assertEquals(2,CrossPosts.group(listOf(x,b.copy(parentId="parent")),follows).size)
        assertEquals(2,CrossPosts.group(listOf(x,b.copy(preview=true)),follows).size)
        assertEquals(2,CrossPosts.group(listOf(x,b),follows.map {if(it.target.platform==Platform.X)it.copy(enabled=false)else it}).size)
    }
    @Test fun shortCaptionsSharedUrlsUnknownDatesAndOldRepostsDoNotGuessMatches() {
        val x=post(Platform.X);val b=post(Platform.BLUESKY);val follows=listOf(account(Platform.X),account(Platform.BLUESKY))
        for(pair in listOf(listOf(x.copy(body="Hello"),b.copy(body="Hello")),listOf(x,b.copy(publishedAt=null)),listOf(x,b.copy(publishedAt=x.publishedAt!!+49*60*60*1000L)),listOf(x.copy(body="One",links=listOf("https://example.org/article")),b.copy(body="Two",links=listOf("https://example.org/article")))))assertEquals(2,CrossPosts.group(pair,follows).size)
        assertEquals(2,CrossPosts.group(listOf(x.copy(publishedAt=null),b.copy(publishedAt=null)),follows).size)
    }
    @Test fun explicitPostLinksNeedSubstantialMatchingContentAndRejectWrongHostsOrAmbiguousReposts() {
        val x=post(Platform.X,time=null);val b=post(Platform.BLUESKY,time=null);val follows=listOf(account(Platform.X),account(Platform.BLUESKY))
        assertEquals(1,CrossPosts.group(listOf(x.copy(links=listOf(b.canonicalUrl)),b),follows).size)
        assertEquals(2,CrossPosts.group(listOf(x.copy(body="Short",links=listOf(b.canonicalUrl)),b.copy(body="Different")),follows).size)
        assertEquals(2,CrossPosts.group(listOf(x.copy(links=listOf(b.canonicalUrl.replace("bsky.app","attacker.example"))),b),follows).size)
        assertEquals(1,CrossPosts.group(listOf(post(Platform.X,"456"),post(Platform.X),post(Platform.BLUESKY,time=1_799_999_999_000L)),follows).size)
    }
    @Test fun sameVerifiedAccountRepeatsCollapseButDifferentLinkedPeopleAndUnknownDatesSurvive() {
        val x=post(Platform.X);val next=post(Platform.X,"456")
        assertEquals(1,CrossPosts.group(listOf(x,next),listOf(account(Platform.X))).size)
        assertEquals(2,CrossPosts.group(listOf(x,post(Platform.BLUESKY)),listOf(account(Platform.X),account(Platform.BLUESKY,"person-two"))).size)
        val unknown=x.copy(publishedAt=null)
        assertEquals(listOf(next,unknown),CrossPosts.group(listOf(unknown,next),listOf(account(Platform.X))).map {it.primary})
    }
    @Test fun newNamedPostPathsProveOwnershipWithoutGuessingFromDisplayNames() {
        val x=post(Platform.X)
        val examples=listOf(
            Platform.MASTODON to Pair("https://social.example.org/@asfixture","https://social.example.org/@asfixture/123"),
            Platform.PIXELFED to Pair("https://photos.example.org/asfixture","https://photos.example.org/p/asfixture/123"),
            Platform.TRUTH_SOCIAL to Pair("https://truthsocial.com/@asfixture","https://truthsocial.com/@asfixture/123"),
            Platform.GAB to Pair("https://gab.com/asfixture","https://gab.com/asfixture/posts/123"),
            Platform.TELEGRAM to Pair("https://t.me/asfixture","https://t.me/asfixture/123"),
            Platform.FACEBOOK to Pair("https://www.facebook.com/100","https://www.facebook.com/100/posts/123")
        )
        for((platform,pair) in examples) {
            val target=routes.target(platform,pair.first)
            val follow=Follow(platform.id,target,"A different display name",personId="person-one")
            val item=Post(ContentIdentity.key(platform,pair.second),platform,pair.second,"A different display name",body,publishedAt=x.publishedAt)
            assertEquals(platform.label,1,CrossPosts.group(listOf(x,item),listOf(account(Platform.X),follow)).size)
            val stranger=item.copy(canonicalUrl=pair.second.replace("asfixture","someoneelse").let {if(platform==Platform.FACEBOOK)it.replace("/100/","/999/")else it})
            assertEquals(platform.label,2,CrossPosts.group(listOf(x,stranger),listOf(account(Platform.X),follow)).size)
        }
    }
    @Test fun federatedPostLinksAndOwnershipRequireTheExactServer() {
        val x=post(Platform.X,time=null)
        val target=routes.target(Platform.MASTODON,"@asfixture@social.example.org")
        val follows=listOf(account(Platform.X),Follow("mastodon",target,"Fixture",personId="person-one"))
        val url="https://social.example.org/@asfixture/123"
        val item=Post(ContentIdentity.key(Platform.MASTODON,url),Platform.MASTODON,url,"An unrelated display name",body)
        assertEquals(1,CrossPosts.group(listOf(x.copy(links=listOf(url)),item),follows).size)
        assertEquals(2,CrossPosts.group(listOf(x.copy(links=listOf(url.replace("social.example.org","foreign.example.org"))),item),follows).size)
        assertEquals(2,CrossPosts.group(listOf(x.copy(body=body,publishedAt=1),item.copy(canonicalUrl=url.replace("social.example.org","foreign.example.org"),author="asfixture@social.example.org",body=body,publishedAt=1)),follows).size)
    }
    @Test fun discoveryUsesOnlyValidPublicProfileLinksAndScopedProfileElements() {
        val html="""<div class="profile-card"><div class="profile-bio">Our work <a href="https://bsky.app/profile/asfixture">Bluesky</a></div><a href="http://example.org">Website</a></div><div class="timeline-item"><a href="https://www.instagram.com/otherperson">Someone mentioned</a></div>"""
        val links=Subscriptions.profileLinks(html,"https://nitter.example/asfixture")
        assertEquals(listOf("https://bsky.app/profile/asfixture","https://example.org"),links)
        val found=Subscriptions.candidates(links+listOf("https://x.com/login","https://www.instagram.com/accounts","https://x.com/asfixture/status/1","ftp://x.com/insecure","https://user:pass@x.com/credentials"),routes,"Profile link")
        assertEquals(listOf(Platform.BLUESKY),found.map {it.target.platform})
        assertEquals("Profile link",found.single().evidence)
        assertEquals("https://x.com/asfixture",Subscriptions.httpsLink("http://x.com/asfixture"))
        assertNull(Subscriptions.httpsLink("http://user:pass@x.com/credentials"))
    }
    @Test fun websiteIdentityLinksAreSuggestionsAndScriptsAreNeverExecuted() {
        val html="""<a href="https://x.com/asfixture">X</a><script type="application/ld+json">{"@graph":[{"@type":"Organization","sameAs":["https://bsky.app/profile/asfixture"]}]}</script><script>window.location='https://www.instagram.com/not-an-identity'</script>"""
        val found=Subscriptions.candidates(Subscriptions.websiteLinks(html,"https://example.org"),routes,"Website link")
        assertEquals(listOf(Platform.X,Platform.BLUESKY),found.map {it.target.platform})
    }
    @Test fun legacySubscriptionsAndProfilesRemainReadableWithNoDatabaseMigration() {
        val gson=Gson();val follow=account(Platform.X).copy(personId=null,personName=null)
        val json=JsonParser.parseString(gson.toJson(follow)).asJsonObject.apply {remove("personId");remove("personName")}
        val restored=gson.fromJson(json,Follow::class.java)
        assertEquals(follow,restored);assertEquals(follow.id,Subscriptions.bundles(listOf(restored)).single().id)
        val p=gson.fromJson("""{"target":${gson.toJson(target(Platform.X))},"name":"Legacy","description":"Bio"}""",Profile::class.java)
        assertTrue(p.links.orEmpty().isEmpty())
    }
    @Test fun discussionsIncludeOnlyDescendantsAndPreserveSuppliedRankOrder() {
        val root=post(Platform.X);val child=post(Platform.X,"456").copy(parentId=root.id);val nested=post(Platform.X,"789").copy(parentId=child.id)
        val related=post(Platform.X,"000");val other=post(Platform.BLUESKY).copy(parentId=root.id)
        val page=Page(listOf(root,nested,related,child,other))
        assertEquals(listOf(nested,child),Discussions.comments(root,page))
        assertTrue(Discussions.comments(root,Page(listOf(root,related))).isEmpty())
        assertTrue(Discussions.comments(root,Page(listOf(child.copy(parentId="unavailable-parent")))).isEmpty())
    }
    @Test fun anAttestedConversationOrKnownLocalParentCanRecoverAnOutOfOrderPage() {
        val root=post(Platform.X);val orphan=post(Platform.X,"456").copy(parentId="post:789",conversationId=root.id)
        assertEquals(listOf(orphan),Discussions.comments(root,Page(listOf(orphan))))
        val legacy=orphan.copy(conversationId=null)
        assertTrue(Discussions.comments(root,Page(listOf(legacy))).isEmpty())
        assertEquals(listOf(legacy),Discussions.comments(root,Page(listOf(legacy))) {it=="post:789"})
        val unrelated=orphan.copy(conversationId="post:unrelated")
        assertTrue(Discussions.comments(root,Page(listOf(unrelated))) {true}.isEmpty())
    }
    @Test fun aScopedRedditCommentRetainsItsMissingParentInsteadOfDisappearing() {
        val root=Post("post:abc",Platform.REDDIT,"https://www.reddit.com/r/test/comments/abc/title/","Author","Root")
        val child=Post("comment:abc:child",Platform.REDDIT,"https://www.reddit.com/r/test/comments/abc/title/child/","Reader","A reply",parentId="t1_missing")
        val foreign=child.copy(id="foreign",canonicalUrl="https://www.reddit.com/r/test/comments/other/title/child/")
        assertEquals(listOf(child),Discussions.comments(root,Page(listOf(child,foreign))))
        assertEquals("t1_missing",Discussions.comments(root,Page(listOf(child))).single().parentId)
    }
    @Test fun aReplyBranchDoesNotAdmitEverySiblingFromItsWholeConversation() {
        val root=post(Platform.X)
        val branch=post(Platform.X,"456").copy(parentId=root.id,conversationId=root.id)
        val child=post(Platform.X,"789").copy(parentId=branch.id,conversationId=root.id)
        val sibling=post(Platform.X,"890").copy(parentId=root.id,conversationId=root.id)
        assertEquals(listOf(child),Discussions.comments(branch,Page(listOf(root,branch,sibling,child))))
        // A current source can supply the missing conversation metadata of an
        // older cached branch without letting siblings become its children.
        assertEquals(listOf(child),Discussions.comments(branch.copy(conversationId=null),Page(listOf(root,branch,sibling,child))))
    }
    @Test fun nativeAndPublicReferencesShareAStableIdentityWithoutLosingThreadScope() {
        assertEquals(ContentIdentity.reference(Platform.X,"123"),ContentIdentity.reference(Platform.X,"https://x.com/person/status/123"))
        assertEquals(ContentIdentity.reference(Platform.REDDIT,"t1_reply"),ContentIdentity.reference(Platform.REDDIT,"https://www.reddit.com/r/test/comments/abc/title/reply/"))
        assertEquals("post:abc",ContentIdentity.reference(Platform.REDDIT,"t3_abc"))
        assertEquals("post:did:plc:actor:record",ContentIdentity.reference(Platform.BLUESKY,"at://did:plc:actor/app.bsky.feed.post/record"))
        assertNotEquals(ContentIdentity.reference(Platform.BLUESKY,"at://did:plc:one/app.bsky.feed.post/record"),ContentIdentity.reference(Platform.BLUESKY,"at://did:plc:two/app.bsky.feed.post/record"))
    }
    @Test fun publicJsonRetainsTheNativeReplyAndConversationBeforeUrlNormalization() {
        val root=post(Platform.X);val source=routes.sources.first {it.frontend=="nitter"};val target=routes.recognize(root.canonicalUrl)!!
        val json="""{"id_str":"456","full_text":"A public reply whose parent is on another page.","user":{"screen_name":"reader"},"in_reply_to_status_id_str":"789","conversation_id_str":"123"}"""
        val page=Extraction.parse(Payload(json,source.root+"/reader/status/123","application/json",200),target,source,routes)
        val reply=page.posts.single()
        assertEquals("456",reply.relationId);assertEquals("789",reply.sourceParentId);assertEquals("post:123",reply.conversationId)
        assertEquals("post:789",reply.parentId);assertEquals(listOf(reply),Discussions.comments(root,page))
    }
    @Test fun archivedCapturesKeepTheirOwnObservedReplyRelationships() {
        val root=post(Platform.X)
        val earlier=root.copy(body="Earlier words",parentId="post:old-parent",conversationId="post:old-root",relationId="native-old",sourceParentId="old-parent",
            extractionCoverage=ExtractionCoverage(replyParent=ExtractionStatus.UNAVAILABLE))
        val latest=root.copy(body="Latest words",parentId="post:new-parent",conversationId="post:new-root",relationId="native-new",sourceParentId="new-parent")
        val restored=PostSnapshot.from(earlier).asPost(latest)
        assertEquals(earlier.body,restored.body);assertEquals(earlier.parentId,restored.parentId)
        assertEquals(earlier.conversationId,restored.conversationId);assertEquals(earlier.relationId,restored.relationId);assertEquals(earlier.sourceParentId,restored.sourceParentId)
        assertEquals(ExtractionStatus.UNAVAILABLE,restored.extractionCoverage!!.replyParent)
        assertEquals(latest.parentId,PostSnapshot(body="Legacy capture").asPost(latest).parentId)
    }
    @Test fun redditDiscussionSortHasExactlyOneSourceParameterAndDoesNotChangeIdentity() {
        val url="https://www.reddit.com/r/test/comments/abc/title?sort=old&utm_source=tracking"
        val root=Post("root",Platform.REDDIT,url,"author","body")
        val top=Discussions.target(root,routes,true)!!;val newest=Discussions.target(root,routes,false)!!
        assertTrue(top.canonicalUrl.endsWith("?sort=top"));assertTrue(newest.canonicalUrl.endsWith("?sort=new"))
        assertEquals(ContentIdentity.key(Platform.REDDIT,url),ContentIdentity.key(top.platform,top.canonicalUrl))
        assertEquals(post(Platform.BLUESKY).canonicalUrl,Discussions.target(post(Platform.BLUESKY),routes,true)!!.canonicalUrl)
        assertTrue(Discussions.target(post(Platform.X),routes,true)!!.canonicalUrl.endsWith("?sort=Relevance"))
        assertTrue(Discussions.target(post(Platform.X),routes,false)!!.canonicalUrl.endsWith("?sort=Recency"))
    }
    @Test fun nitterReplyContainersExcludeAncestorsAndRelatedRecommendations() {
        val root=post(Platform.X);val source=routes.sources.first {it.frontend=="nitter"}
        fun node(id: String,body: String)="""<div class="timeline-item"><a class="tweet-link" href="/asfixture/status/$id"></a><a class="username">@asfixture</a><div class="tweet-content">$body</div></div>"""
        val html="""<div class="conversation"><div class="main-thread"><div class="before-tweet">${node("100","Ancestor")}</div><div class="main-tweet">${node("123","Root")}</div><div class="after-tweet">${node("124","Continuation")}</div></div><div class="replies"><div class="reply thread">${node("125","Actual reply")}</div></div><div class="related-tweets"><div class="reply thread">${node("126","Related recommendation")}</div></div></div>"""
        val target=routes.recognize(root.canonicalUrl)!!
        val page=Extraction.parse(Payload(html,source.root+"/asfixture/status/123","text/html",200),target,source,routes)
        assertEquals(listOf("Continuation","Actual reply"),Discussions.comments(root,page).map {it.body})
        assertTrue(Discussions.comments(root,page).all {it.conversationId==target.canonicalUrl&&it.extractionCoverage?.replyParent==ExtractionStatus.UNAVAILABLE})
        assertTrue(Capability.COMMENTS in FrontendAdapter(source,NetworkGateway(UrlCleaner(asset("clean-links.json")),object: RequestFilter{override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}),routes).capabilities)
    }
    @Test fun rootOnlyOrUnrelatedRepliesCannotWinTheCommentSourceRace()=kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use {server->
            val root="""<div class="main-tweet"><div class="timeline-item"><a class="tweet-link" href="https://x.com/asfixture/status/123"></a><div class="tweet-content">Root</div></div></div>"""
            val reply="""<div class="timeline-item"><a class="tweet-link" href="https://x.com/asfixture/status/456"></a><div class="tweet-content">Actual reply</div></div>"""
            server.dispatcher=object: okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest)=okhttp3.mockwebserver.MockResponse().setBody(root+if(request.path!!.startsWith("/good/"))"<div class='replies'>$reply</div>"else "<div class='related-tweets'>$reply</div>").apply {if(request.path!!.startsWith("/good/"))setBodyDelay(100,java.util.concurrent.TimeUnit.MILLISECONDS)}}
            server.start();val catalog=asset("darwin-routes.json");val sources=com.google.gson.JsonArray()
            for(path in listOf("empty","good")){val source=catalog["sources"].asJsonArray.first {it.asJsonObject["frontend"].asString=="nitter"}.deepCopy().asJsonObject;source.addProperty("root",server.url("/$path").toString().trimEnd('/'));sources.add(source)}
            catalog.add("sources",sources)
            val cleaner=UrlCleaner(asset("clean-links.json"));val local=DarwinRoutes(catalog,cleaner)
            val gateway=NetworkGateway(cleaner,object: RequestFilter{override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val pool=SourcePool(gateway,local);val post=post(Platform.X)
            val page=kotlinx.coroutines.withTimeout(5_000){pool.load(local.recognize(post.canonicalUrl)!!,originalFallback=false,requestedCapability=Capability.COMMENTS)}
            assertTrue(page.provenance!!.source.root.endsWith("/good"));assertEquals(listOf("Actual reply"),Discussions.comments(post,page).map {it.body})
            assertTrue(pool.results(Platform.X).any {it.source.root.endsWith("/empty")&&it.status==HealthStatus.EMPTY})
        }
    }
}
