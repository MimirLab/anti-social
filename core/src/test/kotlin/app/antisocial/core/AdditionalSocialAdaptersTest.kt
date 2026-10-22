package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AdditionalSocialAdaptersTest {
    private val linkedIn=Target(Platform.LINKEDIN,TargetKind.PROFILE,"company/example","https://www.linkedin.com/company/example")
    private val snap=Target(Platform.SNAPCHAT,TargetKind.PROFILE,"nasa","https://www.snapchat.com/@nasa")
    private fun source(platform: Platform)=Source(platform,"original",platform.origin,role=SourceRole.ORIGINAL)
    private fun payload(html: String,url: String)=Payload(html,url,"text/html",200)
    private fun posting(id: String,date: String,company: String="example",body: String="Update $id")="""{"@type":"SocialMediaPosting","author":{"@type":"Organization","name":"Example","url":"https://www.linkedin.com/company/$company"},"url":"https://www.linkedin.com/posts/example_topic-activity-$id-slug","datePublished":"$date","text":"$body","interactionStatistic":{"userInteractionCount":7}}"""
    private fun linkedHtml(vararg records: String)="""<script type="application/ld+json">{"@graph":[${records.joinToString(",")}]}</script>"""
    private fun snapStory(id: String="sample_123",time: Long=1700000000,media: String="https://sc-cdn.example/video.mp4")="""{"storyId":{"value":"$id"},"storyTitle":{"value":"A launch"},"snapList":[{"snapMediaType":1,"timestampInSec":{"value":"$time"},"snapUrls":{"mediaUrl":"$media","mediaPreviewUrl":{"value":"https://sc-cdn.example/preview.jpg"}}}]}"""
    private fun snapHtml(stories: String=snapStory(),username: String="nasa")="""<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"userProfile":{"publicProfileInfo":{"username":"$username","title":"NASA","bio":"Space agency","subscriberCount":"754000","profilePictureUrl":"https://sc-cdn.example/avatar.jpg","websiteUrl":"https://www.nasa.gov"}},"spotlightHighlights":[$stories]}}}</script>"""

    @Test fun companyJsonLdProducesOnlyMatchingChronologicalPosts() {
        val html=linkedHtml(posting("12345","2026-01-01T00:00:00Z"),posting("23456","2026-02-01T00:00:00Z"),posting("99999","2026-03-01T00:00:00Z","other"))
        val page=AdditionalSocialParsing.linkedIn(payload(html,linkedIn.canonicalUrl),linkedIn,source(Platform.LINKEDIN))
        assertEquals(listOf("Update 23456","Update 12345"),page.posts.map {it.body})
        assertEquals("Example",page.profile?.name);assertEquals(7L,page.posts.first().score)
        assertNull(page.next);assertTrue(page.notice!!.contains("comments"))
    }
    @Test fun companyMetadataCannotMasqueradeAsATimeline() {
        val html=linkedHtml("""{"@type":"Organization","name":"Example","url":"https://www.linkedin.com/company/example","description":"A profile only"}""")
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.linkedIn(payload(html,linkedIn.canonicalUrl),linkedIn,source(Platform.LINKEDIN))}
        assertEquals("Example",AdditionalSocialParsing.linkedIn(payload(html,linkedIn.canonicalUrl),linkedIn,source(Platform.LINKEDIN),true).profile?.name)
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.linkedIn(payload(html,linkedIn.canonicalUrl),linkedIn.copy(canonicalUrl="https://www.linkedin.com/company/other"),source(Platform.LINKEDIN))}
    }
    @Test fun individualLinkedInPostRequiresTheRequestedActivityId() {
        val target=linkedIn.copy(kind=TargetKind.POST,canonicalUrl="https://www.linkedin.com/posts/example_topic-activity-23456-slug")
        val html=linkedHtml(posting("12345","2026-01-01T00:00:00Z"),posting("23456","2026-02-01T00:00:00Z"))
        assertEquals("Update 23456",AdditionalSocialParsing.linkedIn(payload(html,target.canonicalUrl),target,source(Platform.LINKEDIN)).posts.single().body)
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.linkedIn(payload(html,target.canonicalUrl),target.copy(canonicalUrl=target.canonicalUrl.replace("23456","99999")),source(Platform.LINKEDIN))}
    }
    @Test fun individualLinkedInVideoUsesItsOwnSchemaAndIgnoresEmbeddedComments() {
        val target=linkedIn.copy(kind=TargetKind.POST,canonicalUrl="https://www.linkedin.com/posts/example_topic-activity-23456-slug")
        val html=linkedHtml("""{"@type":"VideoObject","@id":"${target.canonicalUrl}","creator":{"name":"Example","image":{"url":"https://media.example/avatar.jpg"}},"description":"A public video post","datePublished":"2026-01-01T00:00:00Z","contentUrl":"https://media.example/video.mp4","thumbnailUrl":"https://media.example/poster.jpg","commentCount":7,"comment":[{"@type":"Comment","text":"A reply"}]}""")
        val post=AdditionalSocialParsing.linkedIn(payload(html,target.canonicalUrl),target,source(Platform.LINKEDIN)).posts.single()
        assertEquals("A public video post",post.body);assertEquals("Example",post.author)
        assertEquals("video",post.media.single().type);assertEquals(7L,post.replyCount)
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.linkedIn(payload(html,target.canonicalUrl),target.copy(canonicalUrl=target.canonicalUrl.replace("23456","99999")),source(Platform.LINKEDIN))}
    }
    @Test fun snapchatUsesDatedPublicMediaAndNeverRelatedAccounts() {
        val html=snapHtml(snapStory("older",100)+","+snapStory("newer",200))
        val page=AdditionalSocialParsing.snapchat(payload(html,snap.canonicalUrl),snap,source(Platform.SNAPCHAT))
        assertEquals(listOf("https://www.snapchat.com/@nasa/spotlight/newer","https://www.snapchat.com/@nasa/spotlight/older"),page.posts.map {it.canonicalUrl})
        assertEquals("video",page.posts.first().media.single().type);assertEquals(754000L,page.profile?.followers)
        assertEquals(listOf("https://www.nasa.gov/"),page.profile?.links)
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.snapchat(payload(snapHtml(username="other"),snap.canonicalUrl),snap,source(Platform.SNAPCHAT))}
    }
    @Test fun snapchatMetadataAndLoginShellAreNotWorkingContent() {
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.snapchat(payload(snapHtml(stories=""),snap.canonicalUrl),snap,source(Platform.SNAPCHAT))}
        assertEquals("NASA",AdditionalSocialParsing.snapchat(payload(snapHtml(stories=""),snap.canonicalUrl),snap,source(Platform.SNAPCHAT),true).profile?.name)
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.snapchat(payload("<title>Snapchat</title><p>Sign in to view</p>",snap.canonicalUrl),snap,source(Platform.SNAPCHAT))}
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.snapchat(payload(snapHtml(snapStory(media="javascript:alert(1)")),snap.canonicalUrl),snap,source(Platform.SNAPCHAT))}
    }
    @Test fun snapchatSinglePostDoesNotReturnOtherHighlights() {
        val target=snap.copy(kind=TargetKind.POST,canonicalUrl=snap.canonicalUrl+"/spotlight/sample_123")
        assertEquals(1,AdditionalSocialParsing.snapchat(payload(snapHtml(),target.canonicalUrl),target,source(Platform.SNAPCHAT)).posts.size)
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.snapchat(payload(snapHtml(),target.canonicalUrl),target.copy(canonicalUrl=target.canonicalUrl.replace("sample_123","missing")),source(Platform.SNAPCHAT))}
    }
    @Test fun snapchatSinglePageSelectsTheActualStoryAndCreatorFromRecommendedFeed() {
        val target=snap.copy(kind=TargetKind.POST,canonicalUrl=snap.canonicalUrl+"/spotlight/sample_123")
        val html="""<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"spotlightFeed":{"spotlightStories":[{"story":${snapStory()},"metadata":{"videoMetadata":{"creator":{"personCreator":{"username":"nasa","name":"NASA"}}}}},{"story":${snapStory("recommended")},"metadata":{"videoMetadata":{"creator":{"personCreator":{"username":"other","name":"Someone else"}}}}}]}}}}</script>"""
        val page=AdditionalSocialParsing.snapchat(payload(html,target.canonicalUrl),target,source(Platform.SNAPCHAT))
        assertEquals(1,page.posts.size);assertEquals("NASA",page.posts.single().author)
        assertEquals(1,AdditionalSocialParsing.snapchat(payload(html,target.canonicalUrl),target.copy(canonicalUrl="https://www.snapchat.com/spotlight/sample_123"),source(Platform.SNAPCHAT)).posts.size)
        assertThrows(SourceFailure::class.java){AdditionalSocialParsing.snapchat(payload(html,target.canonicalUrl),target.copy(canonicalUrl=target.canonicalUrl.replace("@nasa","@other")),source(Platform.SNAPCHAT))}
    }
    @Test fun optionalDocumentAlternateActuallyUsesItsOwnOrigin(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(linkedHtml(posting("12345","2026-01-01T00:00:00Z"))))
            val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
            val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val selected=source(Platform.LINKEDIN).copy(root=server.url("/").toString().trimEnd('/'),role=SourceRole.ALTERNATE)
            val adapter=additionalSocialAdapter(selected,gateway,routes)!!
            assertEquals("Update 12345",adapter.fetch(linkedIn).posts.single().body)
            assertEquals("/company/example",server.takeRequest().path)
            assertFalse(Capability.COMMENTS in adapter.capabilities);assertFalse(Capability.SEARCH in adapter.capabilities)
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(linkedIn,"unverified-next-page")}}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun truthSharesOnlyThePublicProtocolAndReportsLoginRestrictions(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setResponseCode(401).setBody("Login required"))
            val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
            val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
            val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
            val adapter=additionalSocialAdapter(source(Platform.TRUTH_SOCIAL).copy(root=server.url("/").toString().trimEnd('/')),gateway,routes)!!
            val target=adapter.sentinel(Capability.TIMELINE)
            assertEquals(Platform.TRUTH_SOCIAL,target.platform)
            val error=assertThrows(SourceFailure::class.java){runBlocking {adapter.fetch(target)}}
            assertEquals(HealthStatus.LOGIN_REQUIRED,error.status)
            val request=server.takeRequest();assertTrue(request.path!!.startsWith("/api/v1/accounts/lookup?acct="))
            assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"));assertEquals(1,server.requestCount)
        }
    }
}
