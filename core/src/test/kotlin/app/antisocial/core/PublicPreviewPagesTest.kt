package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PublicPreviewPagesTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val target=routes.recognize("https://www.instagram.com/nasa/")!!
    private val source=Source(Platform.INSTAGRAM,PublicPreviewAdapter.DESKTOP,"https://www.instagram.com",role=SourceRole.ORIGINAL)
    private fun media(handle:String="nasa",code:String="AbcDEF-123")="""{"__typename":"XIGPolarisVideoMedia","__isXIGPolarisMedia":"XIGPolarisVideoMedia","code":"$code","display_uri":"https://images.example/post.jpg","caption":{"text":"Mission data now available"},"user":{"username":"$handle","id":"100"},"accessibility_caption":"NASA on October 10, 2026"}"""
    private fun parse(body:String,target:Target=this.target,source:Source=this.source)=Extraction.parse(Payload("<script type='application/json'>$body</script>",target.canonicalUrl,"text/html",200),target,source,routes)
    @Test fun publicMediaKeepsTheCaptionAndPosterWithoutInventingADateOrVideo() {
        val page=parse(media())
        val post=page.posts.single();assertEquals("Mission data now available",post.body)
        assertTrue(post.preview);assertNull(post.publishedAt);assertEquals("image",post.media.single().type)
        assertEquals("https://www.instagram.com/p/AbcDEF-123",post.canonicalUrl)
        assertTrue(post.media.single().description.contains("preview"))
        assertTrue(FeedRefresh.posts(page,Platform.INSTAGRAM).isEmpty());assertNotNull(FeedRefresh.pendingReason(page,Platform.INSTAGRAM))
    }
    @Test fun recommendationsAndSimilarShortcodesAreNotSelectedPosts() {
        val page=parse("[${media()},${media("not_nasa","Another-123")},${media("nasa2","Third-123")}]")
        assertEquals(listOf("nasa"),page.posts.map {it.author})
        val selected=routes.recognize("https://www.instagram.com/reel/AbcDEF-123/")!!
        assertEquals(1,parse("[${media()},${media(code="AbcDEF-1234")}]",selected).posts.size)
    }
    @Test fun untypedOrMismatchedPayloadsCannotBecomeMedia() {
        val provenance=Provenance(source,1,target.canonicalUrl)
        val node=JsonParser.parseString(media()).asJsonObject
        node.remove("__isXIGPolarisMedia");assertNull(InstagramPublicMedia.parse(node,target,provenance))
        node.addProperty("__isXIGPolarisMedia","wrong");assertNull(InstagramPublicMedia.parse(node,target,provenance))
        node.addProperty("__isXIGPolarisMedia","XIGPolarisVideoMedia");node.addProperty("code","../bad")
        assertNull(InstagramPublicMedia.parse(node,target,provenance))
    }
    @Test fun publicWindowCannotClearTheCaughtUpBoundary() {
        val target=routes.recognize("https://www.facebook.com/NASA/")!!
        val source=source.copy(platform=Platform.FACEBOOK,root="https://www.facebook.com")
        val body="""<script type="application/json">{"userID":"100","userVanity":"NASA","feed":{"id":"s1","post_id":"200","creation_time":1700000000,"actors":[{"id":"100","name":"NASA"}],"comet_sections":{"content":{"story":{"actors":[{"id":"100","name":"NASA"}],"message":{"text":"Mission data published"}}}}}}</script>"""
        val page=PublicPreviewPages.parse(Payload(body,target.canonicalUrl,"text/html",200),target,source,routes)
        assertEquals("Mission data published",page.posts.single().body);assertNull(page.next)
        assertEquals(PublicPreviewPages.PARTIAL,FeedRefresh.pendingReason(page,Platform.FACEBOOK))
        assertThrows(SourceFailure::class.java){PublicPreviewPages.parse(Payload("<title>NASA</title>",target.canonicalUrl,"text/html",200),target,source,routes)}
    }
    @Test fun theRouteIsOptionalAndDoesNotAdvertiseSearchOrComments() {
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)})
        val pool=SourcePool(gateway,routes)
        assertFalse(pool.sourcesFor(target,Capability.PROFILE,true).any {it.frontend==PublicPreviewAdapter.DESKTOP})
        gateway.installPublicReadFactory(object:PublicReadEngineFactory {
            override fun supports(cohort:Int)=true
            override fun open(cohort:Int,host:String,port:Int,path:String,headers:List<Pair<String,String>>,limit:Int,verify:(PublicReadPeer)->Boolean,decoded:(Int)->Unit):PublicReadByteEngine=error("No networking in a route-plan test")
        })
        val source=pool.sourcesFor(target,Capability.PROFILE,true).single {it.frontend==PublicPreviewAdapter.DESKTOP}
        assertTrue(pool.sourceReader(source) is DataAdapter)
        assertFalse(Capability.SEARCH in pool.sourceReader(source).capabilities);assertFalse(Capability.COMMENTS in pool.sourceReader(source).capabilities)
        assertFalse(pool.sourcesFor(target,Capability.FEED,true).any {it.frontend==PublicPreviewAdapter.DESKTOP})
        assertFalse(pool.sourcesFor(target,Capability.PROFILE,false).any {it.frontend==PublicPreviewAdapter.DESKTOP})
        val facebook=routes.recognize("https://www.facebook.com/NASA/")!!
        assertTrue(pool.sourceReader(pool.sourcesFor(facebook,Capability.FEED,true).single {it.frontend==PublicPreviewAdapter.DESKTOP}).capabilities.contains(Capability.FEED))
    }
}
