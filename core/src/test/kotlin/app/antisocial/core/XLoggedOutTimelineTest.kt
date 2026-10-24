/* anti social, GPL-3.0-or-later. Public NASA capture; no site code is executed. */
package app.antisocial.core

import com.google.gson.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class XLoggedOutTimelineTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject))
    private val target=Target(Platform.X,TargetKind.PROFILE,"NASA","https://x.com/NASA")
    private val source=Source(Platform.X,"original",Platform.X.origin,role=SourceRole.ORIGINAL)
    private val now=1_792_800_000_000L
    private val stamp=Provenance(source,now,target.canonicalUrl)
    private val gson=Gson()
    private fun captured()=javaClass.getResourceAsStream("/x-logged-out-nasa.html")!!.reader().readText()
    private fun tweet(number:String="2113776971603689874",author:String="NASA",body:String="A public launch update.")=JsonParser.parseString("""{
      "__typename":"Tweet","rest_id":"$number","core":{"user_results":{"result":{"__typename":"User","rest_id":"11348282","core":{"screen_name":"$author","name":"$author Laboratory"},"privacy":{"protected":false}}}},
      "details":{"created_at_ms":1792798676000,"full_text":${gson.toJson(body)}},"counts":{"reply_count":12}}
    """).asJsonObject
    private fun instructions(tweets:List<JsonObject>):JsonObject {
        val entries=JsonArray();tweets.forEach {t->entries.add(JsonObject().apply {add("content",JsonObject().apply {add("content",JsonObject().apply {add("tweet_results",JsonObject().apply {add("result",t)})})})})}
        return JsonObject().apply {add("timeline",JsonObject().apply {add("instructions",JsonArray().apply {add(JsonObject().apply {addProperty("__typename","TimelineAddEntries");add("entries",entries)})})})}
    }
    private fun message(root:JsonElement,stringEnvelope:Boolean=true):String {
        val data=JsonObject().apply {addProperty("kind","GraphQLRequestStream.Completed");add("result",JsonObject().apply {addProperty("kind","Result.Ok");add("value",if(stringEnvelope)JsonPrimitive(root.toString())else root)})}
        val r="${'$'}R";return "($r=>$r[19].next($r[21]=$data))($r[\"tsr\"]);document.currentScript.remove()"
    }
    private fun html(vararg tweets:JsonObject,stringEnvelope:Boolean=true)="<html><head><title>NASA on X</title></head><body><script>${message(instructions(tweets.toList()),stringEnvelope)}</script></body></html>"
    private fun parse(body:String,target:Target=this.target,stamp:Provenance=this.stamp)=XLoggedOutTimeline.parse(Jsoup.parse(body),target,stamp)
    private fun literal(value:String):JsonElement {
        val reader=XLoggedOutTimeline.LiteralData(value);val out=reader.read();require(value.substring(reader.position).isBlank());return out
    }
    @Test fun actualLoggedOutTimelineHasFiveOwnedDatedOriginalsWithoutExecutingItsScripts() {
        val page=Extraction.parse(Payload(captured(),target.canonicalUrl,"text/html",200),target,source,routes,1_792_800_000_000L)
        assertEquals(5,page.posts.size)
        assertTrue(page.posts.all {it.author=="NASA"&&it.publishedAt!=null&&it.canonicalUrl.startsWith("https://x.com/NASA/status/")&&!it.preview})
        assertTrue(page.posts.any {it.body.startsWith("We are not slowing down.")})
        assertTrue("Keep the full note beyond the short preview",page.posts.single {it.id=="post:2113776971603689874"}.body.contains("the first Artemis mission on the lunar surface"))
        assertTrue(page.limitedTimeline);assertNull(page.next)
        assertEquals("NASA",page.profile?.name)
        assertTrue(page.posts.any {it.media.any {m->m.type=="video"&&m.mimeType=="video/mp4"}})
        assertTrue(page.posts.any {it.links.orEmpty().any {u->u.startsWith("https://go.nasa.gov/")}})
    }
    @Test fun alternateCopiesWrongDestinationsAndNonProfileTargetsCannotClaimThisOriginalRoute() {
        val body=html(tweet())
        val badTargets=listOf(target.copy(identity="SpaceX"),target.copy(canonicalUrl="https://x.com/SpaceX"),target.copy(kind=TargetKind.SEARCH),target.copy(platform=Platform.FACEBOOK))
        badTargets.forEach {assertNull(parse(body,it))}
        listOf("https://x.com.evil.example/NASA","https://evil.example/NASA","http://x.com/NASA","https://x.com:444/NASA","https://x.com/i/flow/login","https://x.com/SpaceX","https://nitter.example/NASA").forEach {assertNull(parse(body,stamp=stamp.copy(finalUrl=it)))}
        assertNull(parse(body,stamp=stamp.copy(source=source.copy(role=SourceRole.ALTERNATE))))
        assertNotNull(parse(body,stamp=stamp.copy(finalUrl="https://twitter.com/nasa/")))
    }
    @Test fun onlyOwnedTimelineEntriesBecomeCardsAndQuotesKeepTheirAttribution() {
        val quote=tweet("2113776971603689873","SpaceX","The launch window opens at 09:30 UTC.")
        val own=tweet().apply {add("quoted_tweet_results",JsonObject().apply {add("result",quote)})}
        val root=instructions(listOf(own,tweet("2113776971603689872","unrelated")))
        root.add("recommended_tweet",tweet("2113776971603689871"))
        val body="<script>${message(root)}</script>"
        val page=requireNotNull(parse(body));val card=page.posts.single()
        assertEquals("NASA",card.author);assertEquals("post:2113776971603689874",card.id)
        assertTrue(card.body.contains("Quoted post by SpaceX Laboratory:\nThe launch window opens at 09:30 UTC."))
        assertTrue(card.links.orEmpty().contains("https://x.com/SpaceX/status/2113776971603689873"))
        assertEquals(ExtractionStatus.PRESENT,card.extractionCoverage?.quotedContext)
    }
    @Test fun visibilityWrappersAreReadButTombstonesAndPrivateAuthorsAreNotInvented() {
        val root=instructions(listOf(tweet()))
        val result=root["timeline"].asJsonObject["instructions"].asJsonArray[0].asJsonObject["entries"].asJsonArray[0].asJsonObject["content"].asJsonObject["content"].asJsonObject["tweet_results"].asJsonObject
        result.add("result",JsonObject().apply {addProperty("__typename","TweetWithVisibilityResults");add("tweet",tweet())})
        assertEquals(1,parse("<script>${message(root)}</script>")?.posts?.size)
        result.add("result",JsonObject().apply {addProperty("__typename","TweetTombstone");add("tweet",tweet())})
        assertNull(parse("<script>${message(root)}</script>"))
        val private=tweet().apply {getAsJsonObject("core").getAsJsonObject("user_results").getAsJsonObject("result").getAsJsonObject("privacy").addProperty("protected",true)}
        assertNull(parse(html(private)))
    }
    @Test fun missingMalformedFractionalOrFutureIdentitiesAndDatesDoNotProduceFreshCards() {
        val values=listOf(
            tweet("../status/123"),tweet(author="nasa/other"),tweet().apply {remove("rest_id")},
            tweet().apply {getAsJsonObject("details").remove("created_at_ms")},
            tweet().apply {getAsJsonObject("details").addProperty("created_at_ms","1792798676000")},
            tweet().apply {getAsJsonObject("details").addProperty("created_at_ms",BigDecimal("1792798676000.5"))},
            tweet().apply {getAsJsonObject("details").addProperty("created_at_ms",now+172_800_000)},
            tweet().apply {getAsJsonObject("details").addProperty("created_at_ms",-1)}
        )
        values.forEach {assertNull(parse(html(it)))}
    }
    @Test fun fullNotesUseTheirOwnLinksAndNeverApplyAnAmbiguousDisplayRange() {
        val own=tweet(body="A truncated excerpt").apply {
            getAsJsonObject("details").add("display_text_range",JsonParser.parseString("[0,2]"))
            add("note_tweet",JsonParser.parseString("""{"note_tweet_results":{"result":{"text":"🚀 Full instructions &amp; dates: https://t.co/Actual.","entity_set":{"urls":[{"url":"https://t.co/Actual","expanded_url":"https://nasa.gov/full-instructions"}]}}}}"""))
        }
        val p=requireNotNull(parse(html(own))).posts.single()
        assertEquals("🚀 Full instructions & dates: https://nasa.gov/full-instructions.",p.body)
        assertEquals(listOf("https://nasa.gov/full-instructions"),p.links)
        assertFalse(p.body.contains("truncated"))
    }
    @Test fun galleriesVideoVariantsAndCardDetailsSurviveWithoutRepeatingTheTitle() {
        val own=tweet(body="Flight manual").apply {
            add("media_entities2",JsonParser.parseString("""[
              {"type":"photo","media_url_https":"https://pbs.twimg.com/one.jpg","ext_alt_text":"Instrument panel"},
              {"type":"video","media_url_https":"https://pbs.twimg.com/poster.jpg","video_info":{"duration_millis":45000,"variants":[{"url":"https://video.twimg.com/low.mp4","content_type":"video/mp4","bitrate":100},{"url":"https://video.twimg.com/high.mp4","content_type":"video/mp4","bitrate":200}]}},
              {"type":"photo","media_url_https":"javascript:alert(1)"}]"""))
            add("card",JsonParser.parseString("""{"legacy":{"binding_values":[{"key":"title","value":{"string_value":"Flight manual"}},{"key":"description","value":{"string_value":"Checklist and abort procedures."}},{"key":"card_url","value":{"string_value":"https://nasa.gov/manual"}}]}}"""))
        }
        val p=requireNotNull(parse(html(own))).posts.single()
        assertEquals(2,p.media.size);assertEquals("Instrument panel",p.media[0].description)
        assertEquals("https://video.twimg.com/high.mp4",p.media[1].url);assertEquals(45_000L,p.media[1].durationMs)
        assertEquals("Flight manual\n\nChecklist and abort procedures.",p.body)
        assertEquals(listOf("https://nasa.gov/manual"),p.links)
        assertEquals(ExtractionStatus.PARTIAL,p.extractionCoverage?.attachments)
    }
    @Test fun missingQuoteContentIsUnavailableAndNestedQuotesCannotExpandWithoutBound() {
        val tombstone=tweet().apply {add("quoted_tweet_results",JsonParser.parseString("""{"result":{"__typename":"TweetTombstone"}}"""))}
        val absent=requireNotNull(parse(html(tombstone))).posts.single()
        assertEquals(ExtractionStatus.UNAVAILABLE,absent.extractionCoverage?.quotedContext)
        assertFalse(absent.body.contains("Quoted post"))
        val nested=tweet("2113776971603689872","Other","Inner quote").apply {add("quoted_tweet_results",JsonObject().apply {add("result",tweet("2113776971603689871","Deep","Deeper quote"))})}
        val own=tweet().apply {add("quoted_tweet_results",JsonObject().apply {add("result",nested)})}
        val result=requireNotNull(parse(html(own))).posts.single()
        assertTrue(result.body.contains("Inner quote"));assertFalse(result.body.contains("Deeper quote"))
        assertEquals(ExtractionStatus.PARTIAL,result.extractionCoverage?.quotedContext)
    }
    @Test fun arbitraryCaptionJsonIsNotAnotherTimelineAndMediaOnlyPostsRemainReadable() {
        val own=tweet(body=instructions(listOf(tweet("2113776971603689870"))).toString())
        assertEquals(1,parse(html(own))?.posts?.size)
        val photo=tweet(body="").apply {add("media_entities2",JsonParser.parseString("""[{"type":"photo","media_url_https":"https://pbs.twimg.com/photo.jpg"}]"""))}
        val post=requireNotNull(parse(html(photo))).posts.single()
        assertEquals(1,post.media.size);assertTrue(post.body.isEmpty())
    }
    @Test fun bothJsonAndEmbeddedObjectTransportEnvelopesAreSupported() {
        for(stringEnvelope in listOf(true,false))assertEquals(1,parse(html(tweet(),stringEnvelope=stringEnvelope))?.posts?.size)
    }
    @Test fun malformedCandidatesCannotPreventALaterValidMessageButShellsRemainEmpty() {
        val bad="<script>(${ '$' }R=>${ '$' }R[19].next({kind:\"GraphQLRequestStream.Completed\",result:fetch(\"https://evil.example\")}))(${ '$' }R[\"tsr\"])</script>"
        assertEquals(1,parse(bad+html(tweet()))?.posts?.size)
        val oversized="<script>(${ '$' }R=>${ '$' }R[19].next({kind:\"GraphQLRequestStream.Completed\",unused:\"${"x".repeat(1024*1024)}\"}))(${ '$' }R[\"tsr\"])</script>"
        assertEquals(1,parse(oversized+html(tweet()))?.posts?.size)
        assertNull(parse("<title>Log in to continue</title><script>window.tweets=${gson.toJson(tweet())}</script>"))
    }
    @Test fun anExplicitExcerptCannotOverwriteAFullFeedRecordButAFullNoteCan() {
        val excerpt=tweet().apply {add("legacy",JsonParser.parseString("""{"truncated":true}"""))}
        val page=requireNotNull(parse(html(excerpt)))
        assertTrue(page.posts.single().preview);assertEquals(ExtractionStatus.PARTIAL,page.posts.single().extractionCoverage?.text)
        assertTrue(FeedRefresh.posts(page,Platform.X).isEmpty());assertNotNull(FeedRefresh.pendingReason(page,Platform.X))
        excerpt.add("note_tweet",JsonParser.parseString("""{"note_tweet_results":{"result":{"text":"The full announcement and instructions."}}}"""))
        val full=requireNotNull(parse(html(excerpt)))
        assertFalse(full.posts.single().preview);assertEquals(ExtractionStatus.PRESENT,full.posts.single().extractionCoverage?.text)
        assertEquals(1,FeedRefresh.posts(full,Platform.X).size)
    }
    @Test fun literalDataPreservesEscapesHolesBooleansAndCompletedSharedReferences() {
        val r="${'$'}R"
        val value=literal("""{a:$r[1]={text:"line\n\x41\uD83D\uDE80",ok:!0,no:!1},b:$r[1],holes:[,null,void 0,undefined,],number:-1.25e2}""").asJsonObject
        assertEquals("line\nA🚀",value["a"].asJsonObject["text"].asString)
        assertSame(value["a"],value["b"]);assertEquals(4,value["holes"].asJsonArray.size())
        assertTrue(value["a"].asJsonObject["ok"].asBoolean);assertFalse(value["a"].asJsonObject["no"].asBoolean)
        assertEquals(-125,value["number"].asInt)
        assertTrue(literal("$r[1]={self:$r[1]}").asJsonObject["self"].isJsonNull)
    }
    @Test fun expressionsCallsGettersConstructorsDuplicateKeysAndAmbiguousDataAreRejected() {
        val r="${'$'}R"
        val bad=listOf("fetch(\"https://evil.example\")","new Date()","{x:()=>1}","{get x(){return 1}}","{x:r.value}","{x:`template`}","{x:/abc/}","{x:1,x:2}","{1key:1}","{x:$r[1]=1,y:$r[1]=2}","{x:NaN}","{x:Infinity}","{x:1+2}","{x:\"\\xZZ\"}","{x:\"\\u123Z\"}","{x:\"\\01\"}","{x:1", "{x:true};alert(1)")
        bad.forEach {assertTrue("Must remain non-executable: $it",runCatching {literal(it)}.isFailure)}
    }
    @Test fun nestingByteAndNodeBoundsRejectDisproportionateLiteralWork() {
        listOf("[".repeat(66)+"0"+"]".repeat(66),"\""+"a".repeat(1024*1024)+"\"","["+List(80_001){"0"}.joinToString(",")+"]").forEach {
            assertTrue(runCatching {literal(it)}.isFailure)
        }
    }
}
