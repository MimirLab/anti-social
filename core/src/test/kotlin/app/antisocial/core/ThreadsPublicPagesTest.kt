package app.antisocial.core

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** Authored fixtures follow the observed public Relay containers, not live users. */
class ThreadsPublicPagesTest {
    private fun asset(name:String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    private val source=Source(Platform.THREADS,"original","https://www.threads.com",role=SourceRole.ORIGINAL)
    private fun objectOf(value:String)=JsonParser.parseString(value).asJsonObject
    private fun post(code:String="RootCodeCase",pk:String="12345",handle:String="workshop",owner:String="100",reply:Boolean=false,level:Int=0,to:String="",toId:String="")=objectOf("""{
        "pk":"$pk","id":"${pk}_$owner","code":"$code","user":{"pk":"$owner","id":"$owner","username":"$handle","full_name":"Repair workshop"},
        "caption":{"text":"Bring your laptop to the public repair workshop on Saturday."},"taken_at":1792170638,"media_type":19,"like_count":12,
        "text_post_app_info":{"is_reply":$reply,"reply_level":$level,"direct_reply_count":4,"reply_to_author":${if(to.isBlank())"null"else """{"username":"$to","id":"$toId"}"""}}
    }""")
    private fun data(vararg fields:Pair<String,JsonElement>)=JsonObject().apply {fields.forEach {(k,v)->add(k,v)}}
    private fun edges(vararg posts:JsonObject)=JsonArray().apply {posts.forEach {p->add(data("node" to p))}}
    private fun replyGroup(vararg posts:JsonObject)=data("posts" to data("edges" to edges(*posts)))
    private fun connection(vararg groups:JsonObject)=data("edges" to edges(*groups),"page_info" to objectOf("""{"has_next_page":true,"end_cursor":"source cursor"}"""))
    private fun html(vararg values:JsonObject)=values.joinToString(""){value->"<script type=\"application/json\">"+data("__bbox" to data("result" to data("data" to value)))+"</script>"}
    private fun parse(body:String,path:String="/@workshop/post/RootCodeCase",finalPath:String=path):Page {
        val target=routes.recognize(source.root+path)!!
        return Extraction.parse(Payload(body,source.root+finalPath,"text/html",200),target,source,routes)
    }
    private fun selectedWithReplies():String {
        val root=post();val first=post("FirstReplyCase","12346","volunteer","101",true,1,"workshop","100")
        val nested=post("NestedReplyCase","12347","visitor","102",true,2,"volunteer","101")
        val pinned=post("PinnedReplyCase","12348","host","103",true,1,"workshop","100")
        val patch=data("id" to JsonPrimitive("12345_100"),"text_post_app_info" to data("direct_replies" to connection(replyGroup(first,nested)),"pinned_replies" to connection(replyGroup(pinned))))
        return html(data("media" to root),data("relatedPosts" to data("threads" to JsonArray().apply {add(data("thread_items" to JsonArray().apply {add(data("post" to post("UnrelatedCode","12349","other","999")))}))})),data("media" to patch))
    }
    @Test fun selectedRepliesKeepTheirActualParentsAndExcludeRecommendations() {
        val page=parse(selectedWithReplies());val root=page.posts.single {it.id=="post:RootCodeCase"}
        assertEquals(4,page.posts.size);assertEquals(3,Discussions.comments(root,page).size)
        assertEquals("post:RootCodeCase",page.posts.single {it.id=="post:FirstReplyCase"}.parentId)
        assertEquals("post:FirstReplyCase",page.posts.single {it.id=="post:NestedReplyCase"}.parentId)
        assertEquals("post:RootCodeCase",page.posts.single {it.id=="post:PinnedReplyCase"}.parentId)
        assertFalse(page.posts.any {it.canonicalUrl.contains("UnrelatedCode")})
        assertEquals(12L,root.score);assertEquals(4L,root.replyCount)
        assertTrue(page.accounts.any {it.target.canonicalUrl=="https://www.threads.com/@volunteer"&&it.displayNameSupplied})
    }
    @Test fun anotherPostOrRedirectCannotSubstituteForTheSelectedPost() {
        assertThrows(SourceFailure::class.java){parse(html(data("media" to post("DifferentCode"))))}
        assertThrows(SourceFailure::class.java){parse(html(data("media" to post())),finalPath="/login")}
        assertThrows(SourceFailure::class.java){parse(html(data("media" to post("rootcodecase"))))}
        assertThrows(SourceFailure::class.java){parse(html(data("media" to post(handle="other"))))}
    }
    @Test fun foreignPartialRepliesCannotBindToAnUnrelatedRoot() {
        val child=post("ForeignReplyCode","12346","visitor","102",true,1,"workshop","100")
        val patch=data("id" to JsonPrimitive("88888_100"),"text_post_app_info" to data("direct_replies" to connection(replyGroup(child))))
        val page=parse(html(data("media" to post()),data("media" to patch)))
        assertEquals(listOf("post:RootCodeCase"),page.posts.map {it.id})
    }
    @Test fun aProfileRetainsItsWholeGalleryAndSelfReplyWithoutSuggestedAccounts() {
        val owner=objectOf("""{"pk":"100","id":"100","username":"workshop","full_name":"Repair workshop","follower_count":42,"biography":"Borrow tools and learn repairs","bio_links":[{"url":"https://workshop.example/"}]}""")
        val gallery=post().apply {addProperty("media_type",8);add("carousel_media",JsonArray().apply {repeat(4){i->add(objectOf("""{"media_type":1,"image_versions2":{"candidates":[{"url":"https://media.example/$i.jpg"}]}}"""))}})}
        val followup=post("FollowupCode","12346","workshop","100",true,1,"workshop","100")
        val feed=connection(data("thread_items" to JsonArray().apply {add(data("post" to gallery));add(data("post" to followup))}))
        val page=parse(html(data("user" to owner),data("mediaData" to feed),data("relatedPosts" to data("media" to post("SuggestedCode","99999","other","999")))) ,"/@workshop")
        assertEquals(2,page.posts.size);assertEquals(4,page.posts.single {it.id=="post:RootCodeCase"}.media.size)
        assertEquals("post:RootCodeCase",page.posts.single {it.id=="post:FollowupCode"}.parentId)
        assertEquals("Repair workshop",page.profile?.name);assertEquals(42L,page.profile?.followers)
        assertTrue(page.profile!!.displayNameSupplied);assertTrue(page.limitedTimeline);assertNull(page.next)
        assertEquals(listOf("https://workshop.example/"),page.profile!!.links)
    }
    @Test fun aReplyWithUnprovenImmediateParentKeepsOnlyItsProvenConversation() {
        val unknown=post("UnknownParentCode","12346","visitor","102",true,3,"absent","999")
        val patch=data("id" to JsonPrimitive("12345_100"),"text_post_app_info" to data("direct_replies" to connection(replyGroup(unknown))))
        val page=parse(html(data("media" to post()),data("media" to patch)));val root=page.posts.first();val reply=page.posts.single {it.id=="post:UnknownParentCode"}
        assertNull(reply.parentId);assertEquals("post:RootCodeCase",reply.conversationId)
        assertEquals(ExtractionStatus.UNKNOWN,reply.extractionCoverage?.replyParent);assertEquals(1,Discussions.comments(root,page).size)
    }
    @Test fun contradictoryOwnersAndPartialIdentifiersCannotDonateReplies() {
        val invalid=post().apply {getAsJsonObject("user").addProperty("id","999")}
        assertThrows(SourceFailure::class.java){parse(html(data("media" to invalid)))}
        val child=post("ForeignReplyCode","12346","visitor","102",true,1,"workshop","100")
        for(field in listOf("pk","code","user")) {
            val patch=data("id" to JsonPrimitive("12345_100"),"text_post_app_info" to data("direct_replies" to connection(replyGroup(child))))
            patch.add(field,when(field){"pk"->JsonPrimitive("99999");"code"->JsonPrimitive("DifferentCode");else->objectOf("""{"username":"workshop","pk":"999"}""")})
            assertEquals(listOf("post:RootCodeCase"),parse(html(data("media" to post()),data("media" to patch))).posts.map {it.id})
        }
        val conflicting=post("FirstReplyCase","12346","visitor","102",true,1,"workshop","100").apply {
            getAsJsonObject("text_post_app_info").getAsJsonObject("reply_to_author").addProperty("pk","999")
        }
        val patch=data("id" to JsonPrimitive("12345_100"),"text_post_app_info" to data("direct_replies" to connection(replyGroup(conflicting))))
        val childResult=parse(html(data("media" to post()),data("media" to patch))).posts.last()
        assertNull(childResult.parentId);assertEquals("post:RootCodeCase",childResult.conversationId)
    }
    @Test fun profileOwnershipAndTransportOriginAreRequired() {
        val target=routes.recognize(source.root+"/@workshop")!!
        val wrong=objectOf("""{"pk":"999","username":"other"}""")
        assertThrows(SourceFailure::class.java){parse(html(data("user" to wrong)),"/@workshop")}
        val first=objectOf("""{"pk":"100","username":"workshop"}""")
        val second=objectOf("""{"pk":"999","username":"workshop"}""")
        assertThrows(SourceFailure::class.java){parse(html(data("user" to first),data("user" to second)),"/@workshop")}
        for(final in listOf("https://other.example/@workshop","https://www.threads.com/@other","https://www.threads.com:444/@workshop")) {
            assertThrows(SourceFailure::class.java){Extraction.parse(Payload(html(data("user" to first)),final,"text/html",200),target,source,routes)}
        }
        val postTarget=routes.recognize(source.root+"/@workshop/post/RootCodeCase")!!
        for(kind in listOf(TargetKind.SEARCH,TargetKind.MEDIA,TargetKind.FEED)) {
            assertThrows(SourceFailure::class.java){Extraction.parse(Payload(html(data("media" to post())),postTarget.canonicalUrl,"text/html",200),postTarget.copy(kind=kind),source,routes)}
        }
    }
    @Test fun literalFragmentsVideosAndUnknownCountsRemainFaithful() {
        val video=post().apply {
            remove("caption");remove("like_count");addProperty("media_type",2)
            add("video_versions",JsonParser.parseString("""[{"url":"https://media.example/repair.mp4"}]"""))
            add("image_versions2",objectOf("""{"candidates":[{"url":"https://media.example/poster.jpg"}]}"""))
            addProperty("video_duration",3.25)
            getAsJsonObject("text_post_app_info").apply {
                remove("direct_reply_count")
                add("text_fragments",objectOf("""{"fragments":[{"plaintext":"Repair instructions: "},{"plaintext":"manual","link_fragment":{"uri":"https://workshop.example/manual"}}]}"""))
            }
        }
        val result=parse(html(data("media" to video))).posts.single()
        assertEquals("Repair instructions: manual",result.body);assertNull(result.score);assertNull(result.replyCount)
        assertEquals(listOf("https://workshop.example/manual"),result.links)
        val media=result.media.single();assertEquals("video",media.type);assertEquals(3250L,media.durationMs)
        assertEquals("https://media.example/poster.jpg",media.preview)
    }
    @Test fun unrelatedAndExecutableScriptsCannotSupplySelectedData() {
        val unrelated=data("relatedPosts" to data("media" to post()))
        assertThrows(SourceFailure::class.java){parse(html(unrelated))}
        val executable="<script>window.payload="+data("data" to data("media" to post()))+";</script>"
        assertThrows(SourceFailure::class.java){parse(executable)}
        assertThrows(SourceFailure::class.java){parse("<script type=\"application/json\">not JSON</script>")}
    }
    @Test fun nestedAndAggregateReplyBudgetsRejectBeforeMediaConstruction() {
        val child=post("FirstReplyCase","12346","visitor","102",true,1,"workshop","100")
        val huge=JsonArray().apply {repeat(129){add(data("node" to child))}}
        val self=data("id" to JsonPrimitive("12345_100"),"text_post_app_info" to data("self_thread" to data("posts" to data("edges" to huge))))
        assertEquals(HealthStatus.UNSUPPORTED,assertThrows(SourceFailure::class.java){parse(html(data("media" to post()),data("media" to self)))}.status)
        val groups=Array(3){replyGroup(*Array(100){child})}
        val aggregate=data("id" to JsonPrimitive("12345_100"),"text_post_app_info" to data("direct_replies" to connection(*groups)))
        assertEquals(HealthStatus.UNSUPPORTED,assertThrows(SourceFailure::class.java){parse(html(data("media" to post()),data("media" to aggregate)))}.status)
        assertEquals(HealthStatus.UNSUPPORTED,assertThrows(SourceFailure::class.java){parse((1..129).joinToString(""){"<script type=\"application/json\">{}</script>"})}.status)
    }
    @Test fun onlyPermittedOriginalFallbackOffersTheFiniteCommentReader():Unit=runBlocking {
        val localCleaner=UrlCleaner(objectOf("""{"providers":{}}"""))
        val config=asset("darwin-routes.json").apply {add("sources",JsonArray());add("searchSources",JsonArray())}
        val localRoutes=DarwinRoutes(config,localCleaner)
        val gateway=NetworkGateway(localCleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
        val pool=SourcePool(gateway,localRoutes);val adapter=pool.sourceReader(source)
        assertTrue(Capability.COMMENTS in adapter.capabilities);assertFalse(Capability.SEARCH in adapter.capabilities)
        assertFalse(pool.supportsComments(Platform.THREADS,false));assertTrue(pool.supportsComments(Platform.THREADS,true))
        val target=localRoutes.recognize(source.root+"/@workshop/post/RootCodeCase")!!
        assertThrows(SourceFailure::class.java){runBlocking {adapter.comments(target,"https://www.threads.com/@workshop/post/RootCodeCase?after=made-up")}}
        assertThrows(SourceFailure::class.java){runBlocking {adapter.fetch(localRoutes.search(Platform.THREADS,"workshop"))}}
    }
    @Test fun aLiteralSelectedJsonPostCanRemainReadableWithoutInventingNumericIds() {
        val raw=post().apply {remove("pk");remove("id");getAsJsonObject("user").apply {remove("pk");remove("id")}}
        val page=parse(raw.toString());val post=page.posts.single()
        assertEquals("post:RootCodeCase",post.id);assertNull(post.relationId)
        assertTrue(Discussions.comments(post,page).isEmpty())
        assertThrows(SourceFailure::class.java){parse(raw.toString().replace("RootCodeCase","OtherPostCode"))}
        assertThrows(SourceFailure::class.java){parse(raw.toString().replace("\"workshop\"","\"unrelated\""))}
        raw.getAsJsonObject("user").apply {addProperty("pk","100");addProperty("id","999")}
        assertThrows(SourceFailure::class.java){parse(raw.toString())}
    }
}
