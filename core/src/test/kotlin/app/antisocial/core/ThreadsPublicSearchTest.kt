package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Authored search connections; public captures remain outside the repository. */
class ThreadsPublicSearchTest {
    private val gson=Gson()
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val source=Source(Platform.THREADS,"original",Platform.THREADS.origin,role=SourceRole.ORIGINAL)
    private val query="battery {repair} \"manual\""
    private val target=routes.search(Platform.THREADS,query)
    private fun obj(vararg values:Pair<String,Any?>):JsonObject=gson.toJsonTree(values.toMap()).asJsonObject
    private fun post(code:String="RepairCodeOne",pk:String="12345",text:String="Replace the battery using the service manual.",at:Long=1792170638)=obj(
        "pk" to pk,"id" to "${pk}_100","code" to code,"user" to obj("pk" to "100","id" to "100","username" to "workshop","full_name" to "Repair workshop"),
        "caption" to obj("text" to text),"taken_at" to at,"media_type" to 19,"text_post_app_info" to obj("is_reply" to false,"direct_reply_count" to 4))
    private fun metadata(text:String=query,actor:String="0",recent:Int=0,subtab:String?=null)=obj("expectedPreloaders" to listOf(obj(
        "actorID" to actor,"queryID" to "1234567890","queryName" to "BarcelonaSearchResultsQuery",
        "variables" to obj("query" to text,"recent" to recent,"requested_subtab" to subtab,"search_surface" to null,
            "__relay_internal__pv__BarcelonaIsLoggedInrelayprovider" to false))))
    private fun connection(posts:List<JsonObject>)=obj("edges" to posts.map {obj("node" to obj("thread" to obj("thread_items" to listOf(obj("post" to it)))))},
        "page_info" to obj("has_next_page" to true,"end_cursor" to "unqualified source cursor"))
    private fun data(posts:List<JsonObject> = listOf(post()))=obj("searchResults" to connection(posts),
        "relatedPosts" to obj("searchResults" to connection(listOf(post("UnrelatedCode","99999","Not a selected search result.")))))
    private fun script(value:JsonObject)="<script type=\"application/json\">${gson.toJson(value)}</script>"
    private fun body(meta:JsonObject=metadata(),selected:JsonObject=data())=script(meta)+script(obj("__bbox" to obj("result" to obj("data" to selected))))
    private fun parse(html:String=body(),selected:Target=target,finalUrl:String=selected.canonicalUrl)=Extraction.parse(Payload(html,finalUrl,"text/html",200),selected,source,routes)

    @Test fun queryBoundSearchKeepsFullPostsAndNeverPromotesTheirAuthorsIntoAPeopleDirectory() {
        val text="Use {these steps} and the \"battery manual\"; no repeated explanation is needed."
        val page=parse(body(selected=data(listOf(post(text=text),post("RepairCodeTwo","12346",at=1792170640),post(text=text)))))
        assertEquals(listOf("post:RepairCodeOne","post:RepairCodeTwo"),page.posts.map {it.id})
        assertEquals(text,page.posts.first().body);assertEquals("Repair workshop",page.posts.first().author)
        assertEquals(1792170638000L,page.posts.first().publishedAt);assertFalse(page.posts.first().preview)
        assertTrue(page.accounts.isEmpty());assertTrue(page.collections.isEmpty());assertNull(page.profile);assertNull(page.next)
        assertFalse(page.posts.any {it.canonicalUrl.contains("UnrelatedCode")});assertNotNull(page.notice)
    }
    @Test fun selectedQueryAndActualOriginalOriginMustAgree() {
        for(final in listOf("https://www.threads.com/login",target.canonicalUrl.replace("battery","different"),target.canonicalUrl+"&q=other","https://other.example/search?q=${DarwinRoutes.encode(query)}")) {
            assertThrows(SourceFailure::class.java){parse(finalUrl=final)}
        }
        assertThrows(SourceFailure::class.java){parse(body(metadata("another query")))}
        assertThrows(SourceFailure::class.java){parse(body(metadata(actor="100")))}
        assertThrows(SourceFailure::class.java){parse(body(metadata(recent=1)))}
        assertThrows(SourceFailure::class.java){parse(body(metadata(subtab="users")))}
        assertThrows(SourceFailure::class.java){parse(script(obj("__bbox" to obj("result" to obj("data" to data())))))}
    }
    @Test fun nestedRecommendationsAndAmbiguousRootConnectionsCannotSubstituteForSearchResults() {
        assertThrows(SourceFailure::class.java){parse(body(selected=obj("relatedPosts" to data())))}
        val second=script(obj("__bbox" to obj("result" to obj("data" to data(listOf(post("OtherRootCode","23456")))))))
        assertThrows(SourceFailure::class.java){parse(body()+second)}
        assertThrows(SourceFailure::class.java){parse("<script>window.search="+gson.toJson(data())+";</script>")}
        assertThrows(SourceFailure::class.java){parse("<title>Log in to continue</title>"+body())}
    }
    @Test fun emptySearchHasNoInventedPeopleOrNavigationAndOversizedConnectionsAreRejected() {
        assertEquals(HealthStatus.EMPTY,assertThrows(SourceFailure::class.java){parse(body(selected=data(emptyList())))}.status)
        assertEquals(HealthStatus.UNSUPPORTED,assertThrows(SourceFailure::class.java){parse(body(selected=data(List(129){post()})))}.status)
    }
    @Test fun changedSourceSelectorsAndConflictingBindingsAreNotQuietlyTreatedAsOrdinarySearch() {
        val base=metadata()
        val vars=base.getAsJsonArray("expectedPreloaders")[0].asJsonObject.getAsJsonObject("variables")
        vars.addProperty("__relay_internal__pv__BarcelonaIsLoggedInrelayprovider",true)
        assertThrows(SourceFailure::class.java){parse(body(base))}
        for(field in listOf("tagID","meta_place_id","power_search_info","search_surface")) {
            val changed=metadata();changed.getAsJsonArray("expectedPreloaders")[0].asJsonObject.getAsJsonObject("variables").addProperty(field,"unselected")
            assertThrows(SourceFailure::class.java){parse(body(changed))}
        }
        assertThrows(SourceFailure::class.java){parse(script(metadata("other"))+body())}
    }
    @Test fun suppliedThreadRelationshipsAndGalleriesSurviveWithoutGuessedDatesOrParents() {
        val parent=post().apply {remove("taken_at");addProperty("media_type",8);add("carousel_media",gson.toJsonTree(listOf(
            obj("image_versions2" to obj("candidates" to listOf(obj("url" to "https://media.example/a.jpg")))),
            obj("image_versions2" to obj("candidates" to listOf(obj("url" to "https://media.example/b.jpg")))))))}
        val child=post("ChildCodeCase","12346").apply {getAsJsonObject("text_post_app_info").apply {addProperty("is_reply",true);addProperty("reply_level",1);add("reply_to_author",obj("username" to "workshop","id" to "100"))}}
        parent.getAsJsonObject("text_post_app_info").addProperty("reply_level",0)
        val selected=obj("searchResults" to obj("edges" to listOf(obj("node" to obj("thread" to obj("thread_items" to listOf(obj("post" to parent),obj("post" to child))))))))
        val page=parse(body(selected=selected));assertEquals(2,page.posts.size);assertNull(page.posts.first().publishedAt)
        assertEquals(2,page.posts.first().media.size);assertEquals("post:RepairCodeOne",page.posts.last().parentId)
        assertEquals("12345",page.posts.last().sourceParentId);assertTrue(page.accounts.isEmpty())
    }
    @Test fun supportedSearchUsesOneGatewayGetWithoutAccountState():Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody(body()))
            val root=server.url("/").toString().trimEnd('/')
            val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=
                FilterDecision(false,rewrittenUrl=if(url.startsWith(Platform.THREADS.origin))root+url.toHttpUrl().encodedPath+"?"+url.toHttpUrl().encodedQuery else null)},true)
            val client=gateway.client()
            try {
                val adapter=SourcePool(gateway,routes).sourceReader(source.copy(root=root))
                assertTrue(Capability.SEARCH in adapter.capabilities)
                val result=adapter.fetch(target);assertEquals(1,result.posts.size);assertNull(result.next)
                val request=server.takeRequest();assertEquals("GET",request.method);assertEquals("/search",request.requestUrl!!.encodedPath)
                assertEquals(query,request.requestUrl!!.queryParameter("q"));assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
                assertEquals(1,server.requestCount)
                assertThrows(SourceFailure::class.java){runBlocking {adapter.fetch(target,"unqualified source cursor")}}
                assertEquals(1,server.requestCount)
            }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    @Test fun unsupportedSelectorsAndScopesStopBeforeAnyRequest():Unit=runBlocking {
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String):FilterDecision=error("Unsupported query reached Gateway")})
        val adapter=SourcePool(gateway,routes).sourceReader(source)
        val scoped=routes.search(Platform.THREADS,query,SearchScopes.normalize(Platform.THREADS,SearchScopeKind.ACCOUNT,"workshop"))
        for(selected in listOf(scoped)+listOf("&type=profiles","&serp_type=recent","&filter=recent","&q=duplicate").map {target.copy(canonicalUrl=target.canonicalUrl+it)}) {
            assertFalse(adapter.accepts(selected));assertThrows(SourceFailure::class.java){runBlocking {adapter.fetch(selected)}}
        }
        assertEquals(0L,gateway.protectionStats().checked)
    }
    @Test fun theBundledCleanerDoesNotRemoveTheInvestigatedFunctionalFilter() {
        val real=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
        val url="https://www.threads.com/search/?q=nasa&serp_type=default&filter=recent"
        assertEquals(url,real.clean(url))
    }
}
