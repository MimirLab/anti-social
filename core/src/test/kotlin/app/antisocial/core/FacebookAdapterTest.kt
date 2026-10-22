package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class FacebookAdapterTest {
    private val profile=Target(Platform.FACEBOOK,TargetKind.PROFILE,"NASA","https://www.facebook.com/NASA")
    private val target=Target(Platform.FACEBOOK,TargetKind.POST,"100/posts/500","https://www.facebook.com/100/posts/500")
    private val source=Source(Platform.FACEBOOK,"facebook-public","https://www.facebook.com",role=SourceRole.ORIGINAL)
    private val feedback="ZmVlZGJhY2s6NTAw"
    private fun json(value: String)=JsonParser.parseString(value)
    private fun payload(value: String)=Payload(value,"https://www.facebook.com/api/graphql/","application/json",200)
    private fun story(id: String="500",owner: String="100",time: Long=1700000000): JsonObject=json("""{
      "__typename":"Story","id":"story-$id","post_id":"$id","creation_time":$time,
      "actors":[{"id":"$owner","name":"Space Agency","url":"https://www.facebook.com/NASA"}],
      "permalink_url":"https://www.facebook.com/NASA/posts/pfbid$id",
      "comet_sections":{"content":{"story":{"actors":[{"id":"$owner","name":"Space Agency","profile_picture":{"uri":"https://images.example/avatar.jpg"}}],
       "comet_sections":{"message":{"story":{"message":{"text":"Launch $id"}}}},
       "attachments":[{"styles":{"attachment":{"media":{"__typename":"Photo","photo_image":{"uri":"https://images.example/$id.jpg"},"accessibility_caption":"Rocket"}}}}]}},
       "feedback":{"story":{"feedback":{"id":"$feedback","reaction_count":{"count":7},"comment_rendering_instance":{"comments":{"total_count":3}}}}}}}
    """).asJsonObject
    private fun timeline(vararg nodes: JsonObject,next: String?="next")=json("""{"data":{"node":{"id":"100","timeline_list_feed_units":{"edges":[],"page_info":{"has_next_page":${next!=null},"end_cursor":${next?.let {"\"$it\""} ?: "null"}}}}}}""").asJsonObject.apply {
        getAsJsonObject("data").getAsJsonObject("node").getAsJsonObject("timeline_list_feed_units").getAsJsonArray("edges").apply {nodes.forEach {add(JsonObject().apply {add("node",it)})}}
    }.toString()
    private fun comment(id: String,time: Long,parentFeedback: String=feedback,parent: String?=null)=json("""{
      "__typename":"Comment","legacy_fbid":"$id","created_time":$time,"author":{"name":"Reader $id","profile_picture_depth_0":{"uri":"https://images.example/a.jpg"}},
      "body":{"text":"Comment $id"},"parent_feedback":{"id":"$parentFeedback"},
      "comment_direct_parent":${parent?.let {"{\"legacy_fbid\":\"$it\"}"} ?: "null"},
      "feedback":{"reaction_count":{"count":2},"replies_fields":{"total_count":0}}}""")
    private fun comments(vararg nodes: com.google.gson.JsonElement,next: String?="more")=json("""{"data":{"node":{"id":"$feedback","comment_rendering_instance_for_feed_location":{"comments":{"edges":[],"page_info":{"has_next_page":${next!=null},"end_cursor":${next?.let {"\"$it\""} ?: "null"}}}}}}}""").asJsonObject.apply {
        getAsJsonObject("data").getAsJsonObject("node").getAsJsonObject("comment_rendering_instance_for_feed_location").getAsJsonObject("comments").getAsJsonArray("edges").apply {nodes.forEach {add(JsonObject().apply {add("node",it)})}}
    }.toString()

    @Test fun currentProfileIdentityDoesNotConfuseLegacyPageOrOtherActors() {
        val html="""<script type="application/json">{"delegate_page":{"id":"42"},"userID":"100","userVanity":"nasa","viewerID":"0"}</script>"""
        assertEquals("100",FacebookProtocol.profileId(payload(html),profile))
        assertThrows(SourceFailure::class.java){FacebookProtocol.profileId(payload(html),profile.copy(identity="Other",canonicalUrl="https://www.facebook.com/Other"))}
        assertEquals("100",FacebookProtocol.numericProfileId(profile.copy(canonicalUrl="https://www.facebook.com/people/Space-Agency/100")))
    }
    @Test fun timelineRequiresMatchingDatedContentAndKeepsChronology() {
        val page=FacebookProtocol.timeline(payload(timeline(story("501",time=20),story("500",time=10),story("900","999",30))),profile,source,"100")
        assertEquals(listOf("Launch 501","Launch 500"),page.posts.map {it.body})
        assertEquals("https://www.facebook.com/100/posts/501",page.posts.first().canonicalUrl)
        assertEquals("Rocket",page.posts.first().media.single().description)
        assertEquals("next",page.next)
        assertEquals(7L,page.posts.first().score)
        assertThrows(SourceFailure::class.java){FacebookProtocol.timeline(payload(timeline(story(owner="999"))),profile,source,"100")}
        assertThrows(SourceFailure::class.java){FacebookProtocol.timeline(payload(timeline(story().apply {remove("creation_time")})),profile,source,"100")}
    }
    @Test fun nextCursorBelongsToTimelineRatherThanNestedReplies() {
        val item=story().apply {add("related",json("""{"page_info":{"has_next_page":true,"end_cursor":"wrong-replies"}}"""))}
        assertEquals("next",FacebookProtocol.timeline(payload(timeline(item)),profile,source,"100").next)
    }
    @Test fun streamedFeedCursorDoesNotUseNestedReplyCursor() {
        val item=story().apply {add("related",json("""{"page_info":{"has_next_page":true,"end_cursor":"wrong-replies"}}"""))}
        val first=json(timeline(item)).asJsonObject.apply {getAsJsonObject("data").getAsJsonObject("node").getAsJsonObject("timeline_list_feed_units").remove("page_info")}
        val continuation=JsonObject().apply {addProperty("label","ProfileCometTimelineFeed_user\$defer\$ProfileCometTimelineFeed_user_timeline_list_feed_units\$page_info");add("path",json("""["node","timeline_list_feed_units"]"""));add("data",json("""{"page_info":{"has_next_page":true,"end_cursor":"stream-next"}}"""))}
        assertEquals("stream-next",FacebookProtocol.timeline(payload(first.toString()+"\n"+continuation),profile,source,"100").next)
    }
    @Test fun sharedPostsRetainTheirAttributionAndPublicContent() {
        val item=story()
        item.getAsJsonObject("comet_sections").getAsJsonObject("content").getAsJsonObject("story").apply {
            getAsJsonObject("comet_sections").remove("message");remove("attachments")
            add("attached_story",json("""{"actors":[{"name":"Space Mission"}],"comet_sections":{"message":{"story":{"message":{"text":"Mission update"}}}}}"""))
        }
        val page=FacebookProtocol.timeline(payload(timeline(item)),profile,source,"100")
        assertEquals("Shared from Space Mission:\nMission update",page.posts.single().body)
        assertEquals("Space Agency",page.posts.single().author)
    }
    @Test fun commentsKeepParentRelationshipsAndDoNotAcceptOtherThreads() {
        val page=FacebookProtocol.comments(payload(comments(comment("11",10),comment("12",20,parent="11"),comment("90",30,"other"))),target,source,feedback)
        assertEquals(listOf("Comment 12","Comment 11"),page.posts.map {it.body})
        assertEquals(page.posts[1].id,page.posts[0].parentId)
        assertEquals(ContentIdentity.key(Platform.FACEBOOK,target.canonicalUrl),page.posts[1].parentId)
        assertEquals(2,Discussions.comments(Post(ContentIdentity.key(Platform.FACEBOOK,target.canonicalUrl),Platform.FACEBOOK,target.canonicalUrl,"",""),page).size)
        assertEquals("more",page.next)
    }
    @Test fun rankedCommentsRetainServerOrderAndPaginationRetainsOrderToken() {
        val ranked=target.copy(canonicalUrl=target.canonicalUrl+"?sort=top")
        val page=FacebookProtocol.comments(payload(comments(comment("11",10),comment("12",20))),ranked,source,feedback)
        assertEquals(listOf("Comment 11","Comment 12"),page.posts.map {it.body})
        assertEquals("RANKED_FILTERED_INTENT_V1",FacebookProtocol.commentVariables(feedback,"more",true)["commentsIntentToken"].asString)
    }
    @Test fun postAcceptsPartialWarningsOnlyWhenSelectedContentIsPresent() {
        val response=json("""{"data":{"node":null},"errors":[{"message":"field_exception: optional media"}]}""").asJsonObject.apply {getAsJsonObject("data").add("node",story())}
        val page=FacebookProtocol.post(payload(response.toString()),target,source)
        assertEquals("Launch 500",page.posts.single().body);assertNotNull(page.notice)
        assertThrows(SourceFailure::class.java){FacebookProtocol.post(payload(response.toString()),target.copy(canonicalUrl="https://www.facebook.com/100/posts/999"),source)}
        assertThrows(SourceFailure::class.java){FacebookProtocol.post(payload("""{"errors":[{"message":"field_exception"}]}"""),target,source)}
    }
    @Test fun cursorRejectsDifferentPostSourceSortAndOversizedInput() {
        val encoded=FacebookProtocol.cursor(source,target,feedback,"more")
        assertEquals("more",FacebookProtocol.readCursor(encoded,source,target).token)
        assertThrows(IllegalArgumentException::class.java){FacebookProtocol.readCursor(encoded,source.copy(root="https://different.example"),target)}
        assertThrows(IllegalArgumentException::class.java){FacebookProtocol.readCursor(encoded,source,target.copy(canonicalUrl=target.canonicalUrl+"?sort=top"))}
        assertThrows(IllegalArgumentException::class.java){FacebookProtocol.readCursor(encoded,source,target.copy(canonicalUrl="https://www.facebook.com/100/posts/999"))}
        assertThrows(IllegalArgumentException::class.java){FacebookProtocol.readCursor("facebook:"+"x".repeat(17000),source,target)}
    }
    @Test fun loginAndEmptyDocumentsAreNotHealthyContent() {
        val error=assertThrows(SourceFailure::class.java){FacebookProtocol.timeline(payload("<title>Facebook</title><p>Log in to continue</p>"),profile,source,"100")}
        assertEquals(HealthStatus.LOGIN_REQUIRED,error.status)
        assertThrows(SourceFailure::class.java){FacebookProtocol.comments(payload(comments(next=null)),target,source,feedback)}
        assertTrue(FacebookProtocol.comments(payload(comments(next=null)),target,source,feedback,allowEmpty=true).posts.isEmpty())
    }
    @Test fun gatewaySendsOnlyReadQueriesAndBindsContinuationToOneSource(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(timeline(story())))
            server.enqueue(MockResponse().setBody(timeline(story("499",time=1600000000),next=null)))
            val cleaner=UrlCleaner(json("{\"providers\":{}}").asJsonObject)
            val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val selected=source.copy(root=server.url("/").toString().trimEnd('/'))
            val adapter=FacebookAdapter(selected,gateway,routes)
            val numeric=profile.copy(identity="100",canonicalUrl="https://www.facebook.com/100")
            val page=adapter.fetch(numeric);assertNotNull(page.next)
            assertEquals("Launch 499",adapter.fetch(numeric,page.next).posts.single().body)
            repeat(2){index->
                val request=server.takeRequest();assertEquals("POST",request.method);assertEquals("/api/graphql/",request.path)
                assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"));assertNotNull(request.getHeader("X-FB-LSD"))
                val fields=request.body.readUtf8().split('&').associate {part->part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='),"UTF-8")}
                assertEquals("ProfileCometTimelineFeedRefetchQuery",fields["fb_api_req_friendly_name"])
                val variables=json(fields.getValue("variables")).asJsonObject
                assertEquals("100",variables["id"].asString);assertEquals(3,variables["count"].asInt)
                if(index==1)assertEquals("next",variables["cursor"].asString)
            }
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(numeric.copy(canonicalUrl="https://www.facebook.com/200"),page.next)}}
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun alternateContentStaysOnTheSelectedFrontend() {
        val selected=Source(Platform.FACEBOOK,"phice","https://phice.example",role=SourceRole.ALTERNATE)
        val html="""<article class="post"><a class="post_link" href="/NASA/posts/500"></a><div class="post_name"><b>NASA</b></div><div class="post_text">Public post</div><div class="post_time"><span title="2026-10-16T10:00:00Z"></span></div></article><div id="navigation_buttons"><a href="/NASA?cursor=more">Next</a></div>"""
        val page=FacebookProtocol.phice(Payload(html,"https://phice.example/NASA","text/html",200),profile,selected)
        assertEquals("https://www.facebook.com/NASA/posts/500",page.posts.single().canonicalUrl)
        assertEquals("https://phice.example/NASA?cursor=more",page.next)
        assertEquals(selected,page.provenance?.source)
        assertThrows(SourceFailure::class.java){FacebookProtocol.phice(Payload(html.replace("/NASA/posts/500","https://another.example/posts/500"),"https://phice.example/NASA","text/html",200),profile,selected)}
    }
}
