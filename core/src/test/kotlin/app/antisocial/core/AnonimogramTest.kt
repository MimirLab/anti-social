package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AnonimogramTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val source=Source(Platform.INSTAGRAM,"anonimogram-api","https://api.anonimogram.com",true)
    private val target=routes.target(Platform.INSTAGRAM,"nasa")
    private val profile="""{"id":null,"username":"nasa","fullName":"NASA","biography":"Making the seemingly impossible, possible.","avatarUrl":"https://images.example/avatar.jpg","externalUrl":"https://www.nasa.gov/","isPrivate":false,"stats":{"followers":104000000,"following":94}}"""
    private val posts="""{"items":[{"id":"123","shortcode":"CaptionOne","caption":"The source's complete public caption.","timestamp":null,"mediaType":"video","isReel":true,"media":[{"type":"video","url":"https://images.example/poster.jpg","thumbnailUrl":"https://images.example/poster.jpg"}]},{"shortcode":"GalleryTwo","caption":"A public gallery caption.","timestamp":null,"mediaType":"carousel","media":[{"type":"image","url":"https://images.example/first.jpg"}]}],"nextCursor":null,"hasNextPage":false}"""
    private fun payload(text:String,path:String="posts")=Payload(text,"${source.root}/api/$path/nasa","application/json",200)
    @Test fun publicProfileFieldsRetainExplicitNamesAndUnknownStableIds() {
        val page=AnonimogramData.profile(payload(profile,"profile"),target,source,routes,1234)
        val p=requireNotNull(page.profile)
        assertEquals("NASA",p.name);assertTrue(p.displayNameSupplied);assertNull(p.persistentId)
        assertEquals(104000000L,p.followers);assertEquals(94L,p.following);assertEquals(listOf("https://www.nasa.gov/"),p.links)
        assertEquals(target,p.target);assertEquals(source,page.provenance!!.source)
    }
    @Test fun aDifferentOrEmptyProfileCannotTrainANameLookup() {
        for(body in listOf(profile.replace("\"username\":\"nasa\"","\"username\":\"other\""),"""{"username":"nasa"}"""))
            assertThrows(SourceFailure::class.java){AnonimogramData.profile(payload(body),target,source,routes)}
        val p=AnonimogramData.profile(payload(profile.replace("\"fullName\":\"NASA\"","\"fullName\":\"\"")),target,source,routes).profile!!
        assertEquals("nasa",p.name);assertFalse(p.displayNameSupplied)
    }
    @Test fun postersDoNotBecomePlayableVideosOrChronologicalFeedEvidence() {
        val page=AnonimogramData.posts(payload(posts),target,source,routes,now=1_792_800_000_000L)
        assertEquals(2,page.posts.size);assertTrue(page.posts.all {it.publishedAt==null&&it.preview})
        assertEquals("image",page.posts.first().media.single().type);assertEquals("https://images.example/poster.jpg",page.posts.first().media.single().url)
        assertTrue(page.posts.all {it.extractionCoverage!!.attachments==ExtractionStatus.PARTIAL})
        assertTrue(FeedRefresh.posts(page,Platform.INSTAGRAM).isEmpty());assertTrue(page.limitedTimeline)
        assertEquals("https://www.instagram.com/reel/CaptionOne/",page.posts.first().canonicalUrl)
    }
    @Test fun aSuppliedVideoAndThumbnailRemainSeparateWhileSourceDatesAreNotInvented() {
        val body=posts.replace("\"url\":\"https://images.example/poster.jpg\"","\"url\":\"https://video.example/clip.mp4\"").replace("\"timestamp\":null","\"timestamp\":\"2026-10-23T12:00:00Z\"")
        val post=AnonimogramData.posts(payload(body),target,source,routes,now=1_792_800_000_000L).posts.first()
        assertEquals("video",post.media.single().type);assertEquals("https://images.example/poster.jpg",post.media.single().preview)
        assertEquals(Extraction.timestamp("2026-10-23T12:00:00Z"),post.publishedAt);assertTrue(post.preview)
        for(time in listOf("0","-9223372036854775","999999999999999999999999","true")) {
            val p=AnonimogramData.posts(payload(posts.replace("\"timestamp\":null","\"timestamp\":$time")),target,source,routes).posts.first()
            assertNull(p.publishedAt)
        }
    }
    @Test fun unsafeMediaAndWrongOwnersAreDiscardedButAnImageOnlyPreviewRemainsUsable() {
        val body="""{"items":[{"shortcode":"Bad","username":"other","caption":"Wrong account"},{"shortcode":"Safe","caption":"","thumbnailUrl":"https://images.example/cover.jpg","media":[{"type":"image","url":"file:///private/data"},{"type":"video","url":"https://user:secret@video.example/clip.mp4"}]}]}"""
        val page=AnonimogramData.posts(payload(body),target,source,routes)
        assertEquals(1,page.posts.size);assertEquals("https://images.example/cover.jpg",page.posts.single().media.single().url)
    }
    @Test fun malformedDeepOrErrorJsonDoesNotBecomeAProfileOrCaptionSnapshot() {
        for(body in listOf("<html>Sign in</html>","[]","{} {}","""{"error":{"code":"quota"}}""","{"+"\"nested\":".repeat(1)+"[".repeat(40)+"0"+"]".repeat(40)+"}"))
            assertThrows(SourceFailure::class.java){AnonimogramData.posts(payload(body),target,source,routes)}
    }
    @Test fun onDemandPaginationIsBoundToItsSourceAndSelectedAccount():Unit=runBlocking {
        MockWebServer().use {server->server.start();repeat(2){server.enqueue(MockResponse().setBody(posts.replace("\"nextCursor\":null,\"hasNextPage\":false","\"nextCursor\":\"opaque==/one\",\"hasNextPage\":true")))}
            val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
            val local=source.copy(root=server.url("/").toString().trimEnd('/'));val reader=AnonimogramAdapter(local,gateway,routes)
            try {
                val first=reader.fetch(target);assertEquals("/api/posts/nasa",server.takeRequest().path);val next=requireNotNull(first.next)
                assertThrows(IllegalArgumentException::class.java){runBlocking {reader.fetch(routes.target(Platform.INSTAGRAM,"other"),next)}}
                assertThrows(IllegalArgumentException::class.java){runBlocking {AnonimogramAdapter(local.copy(root="https://other.example"),gateway,routes).fetch(target,next)}}
                assertEquals(1,server.requestCount)
                val second=reader.fetch(target,next);assertNull(second.next);assertNotNull(second.continuationIssue)
                assertEquals("opaque==/one",server.takeRequest().requestUrl!!.queryParameter("cursor"))
            }finally {gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()}
        }
    }
    @Test fun factorySeparatesProfileReadsFromUnsupportedFeedsPostsAndSearches():Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(profile))
            val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
            val local=source.copy(root=server.url("/").toString().trimEnd('/'));val reader=SourcePool(gateway,routes).sourceReader(local)
            try {
                assertEquals(setOf(Capability.PROFILE,Capability.TIMELINE),reader.capabilities)
                assertFalse(reader.accepts(routes.search(Platform.INSTAGRAM,"nasa")))
                assertFalse(reader.accepts(routes.recognize("https://www.instagram.com/p/CaptionOne/")!!))
                assertThrows(SourceFailure::class.java){runBlocking {reader.feed(target)}};assertEquals(0,server.requestCount)
                assertEquals("NASA",reader.profile(target).profile!!.name);assertEquals("/api/profile/nasa",server.takeRequest().path)
                assertEquals(1,server.requestCount)
            }finally {gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()}
        }
    }
    @Test fun sharedApiHostNeverBecomesAnInstagramOutwardAlias() {
        routes.registerPathwayAliases(listOf(source))
        assertNull(routes.recognize("https://api.anonimogram.com/api/profile/nasa"))
        assertNull(AnonimogramData.username(target.copy(canonicalUrl="https://attacker.example/nasa"),routes))
    }
    @Test fun malformedOrDeepContinuationIsRejectedBeforeAnyLookup() {
        for(body in listOf("{} {}","{\"nested\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}","""{"source":true,"target":[],"cursor":"opaque"}""")) {
            val cursor="anonimogram:"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())
            assertThrows(IllegalArgumentException::class.java){AnonimogramData.cursor(cursor,target,source)}
        }
    }
}
