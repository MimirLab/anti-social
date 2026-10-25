/* anti social, GPL-3.0-or-later. Explicit bounded public-page research. */
package app.antisocial.android

import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.FormBody
import okio.Buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Research output never marks a route healthy. No scripts or account state. */
class ThreadsPublicResearchTest {
    @Test fun inspectOneSourceReportedProfileContinuation():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveThreadsPagingResearch")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=File(context.getExternalFilesDir("validation"),"threads-paging-research").apply {mkdirs()}
        val cleaner=UrlCleaner(context.assets.open("clean-links.json").reader().use {JsonParser.parseReader(it).asJsonObject})
        val records=mutableListOf<Map<String,Any?>>()
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter);val client=gateway.client()
            try {withTimeout(45_000){gateway.boundedMediaRead(maxBytes=8L*1024*1024,maxRequests=2) {
                val url="https://www.threads.com/@nasa"
                val head=withTimeout(20_000){gateway.extractorDocument(url,sourceUrl=url,sameOrigin=true,maxBytes=4L*1024*1024,
                    headers=mapOf("Accept" to "text/html","Accept-Language" to "en-US,en;q=0.5"))}
                File(output,"profile.html").writeText(head.text)
                records+=mapOf("step" to "profile","status" to head.status,"bytes" to head.text.length)
                check(head.status in 200..299){"Profile HTTP ${head.status}; stop"};HtmlAdmission.check(head.text)
                val objects=mutableListOf<JsonObject>()
                fun walk(value:JsonElement,depth:Int=0) {
                    if(depth>64||objects.size>=20000)return
                    if(value.isJsonObject){objects+=value.asJsonObject;value.asJsonObject.entrySet().forEach {walk(it.value,depth+1)}}
                    else if(value.isJsonArray)value.asJsonArray.forEach {walk(it,depth+1)}
                }
                org.jsoup.Jsoup.parse(head.text).select("script[type=application/json]").take(64).forEach {script->
                    runCatching {JsonParser.parseString(script.data())}.getOrNull()?.let {walk(it)}
                }
                val owner=objects.firstOrNull {it.get("username")?.takeIf {v->v.isJsonPrimitive}?.asString=="nasa"&&it.has("follower_count")} ?: error("No selected public owner")
                val id=owner.get("pk")?.asString ?: error("No selected source account ID")
                require(id.matches(Regex("[0-9]{1,40}")))
                val feed=objects.firstNotNullOfOrNull {it.get("mediaData")?.takeIf {v->v.isJsonObject}?.asJsonObject} ?: error("No profile connection")
                val info=feed.getAsJsonObject("page_info");check(info.get("has_next_page")?.asBoolean==true)
                val cursor=info.get("end_cursor")?.asString ?: error("No supplied cursor")
                require(cursor.length in 1..2048&&cursor.none(Char::isISOControl))
                val variables=JsonObject().apply {
                    addProperty("userID",id);addProperty("after",cursor);add("before",JsonNull.INSTANCE);addProperty("first",20);add("last",JsonNull.INSTANCE)
                    addProperty("allow_page_info_for_lox_user",true);addProperty("skipGhostPosts",false)
                    listOf("BarcelonaHasGhostPostNullStateString","BarcelonaHasDearAlgoConsumption","BarcelonaHasPodcastConsumption","BarcelonaHasCommunities","IsTagIndicatorEnabled","BarcelonaHasGhostPostConsumption","BarcelonaOptionalCookiesEnabled","BarcelonaQuotedPostUFIEnabled","BarcelonaHasTopicTags").forEach {addProperty("__relay_internal__pv__${it}relayprovider",true)}
                    listOf("BarcelonaIsLoggedIn","BarcelonaHasProfileSelfReplyContext","BarcelonaIsReplyApprovalEnabled","BarcelonaHasEventBadge","BarcelonaIsReplyApprovalsConsumptionEnabled","BarcelonaIsSearchDiscoveryEnabled","BarcelonaHasGameScoreShare","BarcelonaHasMusic","BarcelonaHasSelfThreadCount","BarcelonaHasDeepDive","BarcelonaHasSpoilerStylingInfo","BarcelonaHasGhostPostEmojiActivation","BarcelonaHasDearAlgoWebProduction","BarcelonaIsCrawler","BarcelonaHasDisplayNames","BarcelonaHasCommunityTopContributors","BarcelonaCanSeeSponsoredContent","BarcelonaShouldShowFediverseM075Features","BarcelonaImplicitTrendsGK","BarcelonaIsInternalUser").forEach {addProperty("__relay_internal__pv__${it}relayprovider",false)}
                }
                val form=FormBody.Builder().add("server_timestamps","true").add("doc_id","26400202666284393").add("variables",variables.toString()).build()
                val doc=withTimeout(20_000){gateway.extractorDocument("https://www.threads.com/graphql/query",method="POST",
                    data=Buffer().also {form.writeTo(it)}.readByteArray(),sourceUrl=url,sameOrigin=true,maxBytes=4L*1024*1024,
                    headers=mapOf("Content-Type" to "application/x-www-form-urlencoded","Origin" to "https://www.threads.com","Referer" to "https://www.threads.com/","X-IG-App-ID" to "238260118697367","X-Root-Field-Name" to "xdt_api__v1__text_feed__user_id__profile__connection","X-Logged-Out-Threads-Migrated-Request" to "true","X-CSRFToken" to ""))}
                File(output,"continuation.json").writeText(doc.text)
                records+=mapOf("step" to "continuation","status" to doc.status,"bytes" to doc.text.length,"ownerId" to id,"requestHasCookie" to false,"requestHasAuthorization" to false)
                check(doc.status in 200..299){"Continuation HTTP ${doc.status}; stop"}
                val root=com.google.gson.stream.JsonReader(doc.text.reader()).use {reader->reader.strictness=Strictness.STRICT;reader.nestingLimit=64;JsonParser.parseReader(reader).asJsonObject}
                check(root.getAsJsonArray("errors").isNullOrEmpty()){root.get("errors").toString().take(700)}
                val next=root.getAsJsonObject("data")?.getAsJsonObject("mediaData") ?: error("No continuation connection")
                records+=mapOf("step" to "continued-connection","rows" to next.getAsJsonArray("edges")?.size(),"pageInfo" to next.get("page_info"))
            }}}
            catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;records+=mapOf("error" to "Bounded research deadline expired")}
            catch(e:Exception){records+=mapOf("error" to (e.javaClass.simpleName+": "+e.message).take(1000))}
            finally {File(output,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(records));client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    private fun JsonArray?.isNullOrEmpty()=this==null||size()==0
    @Test fun inspectOnePublicProfileAndItsObservedPost():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveThreadsResearch")=="true")
        val handle=args.getString("antisocialResearchThreadsHandle") ?: "nasa"
        require(handle.matches(Regex("[A-Za-z0-9_.]{1,30}")))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=File(context.getExternalFilesDir("validation"),"threads-public-research").apply {mkdirs()}
        val cleaner=UrlCleaner(context.assets.open("clean-links.json").reader().use {JsonParser.parseReader(it).asJsonObject})
        val routes=DarwinRoutes(context.assets.open("darwin-routes.json").reader().use {JsonParser.parseReader(it).asJsonObject},cleaner)
        val source=Source(Platform.THREADS,"original",Platform.THREADS.origin,true,SourceRole.ORIGINAL)
        val records=mutableListOf<Map<String,Any?>>()
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter);val client=gateway.client()
            suspend fun read(label:String,target:Target):Page {
                val start=System.nanoTime()
                val doc=withTimeout(20_000){gateway.extractorDocument(target.canonicalUrl,sourceUrl=target.canonicalUrl,sameOrigin=true,
                    maxBytes=4L*1024*1024,headers=mapOf("Accept" to "text/html","Accept-Language" to "en-US,en;q=0.5"))}
                File(output,"$label.html").writeText(doc.text)
                records+=mapOf("step" to label,"status" to doc.status,"bytes" to doc.text.length,"finalUrl" to doc.finalUrl,"ms" to (System.nanoTime()-start)/1_000_000)
                check(doc.status in 200..299){"HTTP ${doc.status}; no further reads"}
                val page=Extraction.parse(Payload(doc.text,doc.finalUrl,"text/html",doc.status),target,source,routes)
                records+=mapOf("step" to "$label-current-parser","posts" to page.posts.map {mapOf("key" to it.identityKey,"url" to it.canonicalUrl,
                    "parent" to it.parentId,"sourceParent" to it.sourceParentId,"conversation" to it.conversationId,"date" to it.publishedAt,"media" to it.media.size)},"accounts" to page.accounts.size)
                return page
            }
            try {withTimeout(45_000){gateway.boundedMediaRead(maxBytes=8L*1024*1024,maxRequests=2) {
                val profile=routes.recognize("https://www.threads.com/@$handle")!!
                val first=read("profile",profile)
                val selected=first.posts.mapNotNull {routes.recognize(it.canonicalUrl)}.firstOrNull {
                    it.platform==Platform.THREADS&&it.kind==TargetKind.POST&&it.identity.substringBefore('/').equals("@$handle",true)
                }
                if(selected==null)records+=mapOf("step" to "post-skipped","reason" to "No actual selected-author post supplied by current parser")
                else read("post",selected)
            }}}
            catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;records+=mapOf("error" to "Bounded research deadline expired")}
            catch(e:Exception){records+=mapOf("error" to (e.javaClass.simpleName+": "+e.message).take(1000))}
            finally {
                File(output,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(records))
                client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
            }
        }
    }
}
