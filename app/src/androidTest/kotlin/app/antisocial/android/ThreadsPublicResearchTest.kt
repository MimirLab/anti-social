/* anti social, GPL-3.0-or-later. Explicit bounded public-page research. */
package app.antisocial.android

import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.FormBody
import okio.Buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Research output never marks a route healthy. No scripts or account state. */
class ThreadsPublicResearchTest {
    @Test fun inspectSearchUrlProtectionWithoutNetwork() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cleaner=UrlCleaner(context.assets.open("clean-links.json").reader().use {JsonParser.parseReader(it).asJsonObject})
        val output=File(context.getExternalFilesDir("validation"),"threads-search-protection").apply {mkdirs()}
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val url="https://www.threads.com/search/?q=nasa&serp_type=default&filter=recent"
            val cleaned=cleaner.clean(url);val decision=filter.check(cleaned,url,"xmlhttprequest")
            File(output,"results.json").writeText(Gson().toJson(mapOf("input" to url,"cleaned" to cleaned,"decision" to decision,"networkRequests" to 0)))
        }
    }
    @Test fun compareExistingSearchStack():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveThreadsStackResearch")=="true")
        val label=args.getString("antisocialResearchLabel") ?: "measurement"
        require(label.matches(Regex("[a-z0-9-]{1,32}")))
        val query=args.getString("antisocialResearchThreadsQuery") ?: "nasa"
        require(query.length in 1..100&&query.none(Char::isISOControl))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo
        withTimeout(20_000){repo.awaitReady()}
        val preferences=repo.prefs.export().toString()
        val output=File(context.getExternalFilesDir("validation"),"threads-search-stack-$label").apply {mkdirs()}
        val records=mutableListOf<Map<String,Any?>>()
        try {
            withContext(Dispatchers.Main) {repo.prefs.intervalMinutes=0;repo.prefs.tor=false;repo.prefs.originalFallback=true;repo.prefs.searchProvider=SearchProvider.DUCKDUCKGO;repo.applyNetworkSettings()}
            val ready=System.nanoTime();withTimeout(60_000){repo.tor.state.first {it.ready}}
            records+=mapOf("step" to "protected-search-ready","ms" to (System.nanoTime()-ready)/1_000_000)
            val pathways=PublicPathways(context.assets.open("public-pathways.json").reader().use {JsonParser.parseReader(it).asJsonObject})
            val pool=SourcePool(repo.gateway,repo.routes,pathways).apply {searchProvider=SearchProvider.DUCKDUCKGO;awaitSearchConnection={withTimeout(60_000){repo.tor.state.first {it.ready};Unit}}}
            val target=repo.routes.search(Platform.THREADS,query)
            val session=SearchFeed(target,discover={previous->check(previous==null);pool.load(target,originalFallback=true,requestedCapability=Capability.SEARCH)},
                read={destination->pool.load(destination,originalFallback=true,requestedCapability=if(destination.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY))Capability.PROFILE else Capability.POST)},
                blockSize=24,maxPosts=24,maxPages=1,maxCharacters=512*1024,maxCandidates=24)
            val start=System.nanoTime()
            withTimeout(90_000){repo.gateway.boundedMediaRead(maxBytes=16L*1024*1024,maxRequests=8){session.loadNext {}}}
            val state=session.state
            records+=mapOf("step" to "bounded-search","ms" to (System.nanoTime()-start)/1_000_000,"posts" to state.page.posts.map {mapOf("key" to it.identityKey,"date" to it.publishedAt,"characters" to it.body.length,"preview" to it.preview,"source" to it.provenance?.source?.frontend)},
                "accounts" to state.page.accounts.size,"hasMore" to state.hasMore,"limited" to state.limited,"unavailable" to state.unavailable,"error" to state.error,
                "routes" to pool.results(Platform.THREADS))
            File(output,"page.json").writeText(Gson().toJson(state.page))
        }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;records+=mapOf("error" to "Bounded research deadline expired")}
        catch(e:Exception){records+=mapOf("error" to (e.javaClass.simpleName+": "+e.message).take(1000))}
        finally {
            File(output,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(records))
            withContext(NonCancellable+Dispatchers.Main){repo.context.getSharedPreferences("antisocial",android.content.Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(org.json.JSONObject(preferences));repo.mentionNames.changed();repo.applyNetworkSettings()}
        }
    }
    @Test fun inspectBoundedAnonymousSearchPages():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveThreadsSearchResearch")=="true")
        val query=args.getString("antisocialResearchThreadsQuery") ?: "nasa"
        require(query.length in 1..100&&query.none(Char::isISOControl))
        val variant=args.getString("antisocialResearchThreadsSearchVariant") ?: "reference"
        require(variant in setOf("reference","filter-recent","encoding"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=File(context.getExternalFilesDir("validation"),"threads-search-research-$variant").apply {mkdirs()}
        val cleaner=UrlCleaner(context.assets.open("clean-links.json").reader().use {JsonParser.parseReader(it).asJsonObject})
        val records=mutableListOf<Map<String,Any?>>()
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter);val client=gateway.client()
            try {withTimeout(45_000){gateway.boundedMediaRead(maxBytes=8L*1024*1024,maxRequests=2) {
                val variants=when(variant) {
                    "filter-recent"->listOf("filter-recent" to "serp_type=default&filter=recent")
                    "encoding"->listOf("form-space" to "","percent-space" to "")
                    else->listOf("recent" to "serp_type=recent","profiles" to "type=profiles")
                }
                for((label,mode)in variants) {
                    val encoded=DarwinRoutes.encode(query).let {if(label=="percent-space")it.replace("+","%20")else it}
                    val url="https://www.threads.com/search/?q=$encoded"+(if(mode.isEmpty())""else "&$mode")
                    val start=System.nanoTime()
                    records+=mapOf("step" to "$label-protection","input" to url,"cleaned" to cleaner.clean(url),"decision" to filter.check(cleaner.clean(url),url,"xmlhttprequest"))
                    val doc=withTimeout(20_000){gateway.extractorDocument(url,sourceUrl=url,sameOrigin=true,maxBytes=4L*1024*1024,
                        headers=mapOf("Accept" to "text/html","Accept-Language" to "en-US,en;q=0.5"))}
                    File(output,"$label.html").writeText(doc.text)
                    records+=mapOf("step" to label,"status" to doc.status,"bytes" to doc.text.length,"finalUrl" to doc.finalUrl,"ms" to (System.nanoTime()-start)/1_000_000)
                    check(doc.status in 200..299){"HTTP ${doc.status}; stop further public reads"}
                    HtmlAdmission.check(doc.text)
                    val scripts=org.jsoup.Jsoup.parse(doc.text).select("script[type=application/json]")
                    require(scripts.size<=128);var total=0L;var visited=0
                    val connections=mutableListOf<Map<String,Any?>>()
                    fun walk(value:JsonElement,path:String,depth:Int=0) {
                        require(depth<=64&&++visited<=50_000)
                        if(value.isJsonArray){value.asJsonArray.forEachIndexed {i,v->walk(v,"$path[$i]",depth+1)};return}
                        if(!value.isJsonObject)return
                        val obj=value.asJsonObject
                        obj.get("searchResults")?.takeIf {it.isJsonObject}?.asJsonObject?.let {search->
                            val edges=search.get("edges")?.takeIf {it.isJsonArray}?.asJsonArray
                            connections+=mapOf("path" to path,"fields" to search.keySet().toList(),"rows" to edges?.size(),"pageInfo" to search.get("page_info"),
                                "sample" to edges?.take(3)?.map {edge->edge.takeIf {it.isJsonObject}?.asJsonObject?.get("node")})
                        }
                        obj.entrySet().forEach {(key,v)->if(v.isJsonArray||v.isJsonObject)walk(v,"$path.$key",depth+1)}
                    }
                    scripts.forEachIndexed {index,script->
                        val raw=script.data();total+=raw.length.toLong();require(total<=4L*1024*1024)
                        val value=runCatching {com.google.gson.stream.JsonReader(raw.reader()).use {reader->
                            reader.strictness=Strictness.STRICT;reader.nestingLimit=64
                            JsonParser.parseReader(reader).also {require(reader.peek()==com.google.gson.stream.JsonToken.END_DOCUMENT)}
                        }}.getOrNull()
                        if(value!=null)walk(value,"script[$index]")
                    }
                    records+=mapOf("step" to "$label-literal-search-connections","scripts" to scripts.size,"connections" to connections)
                    if(variant=="encoding") {
                        val routes=DarwinRoutes(context.assets.open("darwin-routes.json").reader().use {JsonParser.parseReader(it).asJsonObject},cleaner)
                        val parsed=runCatching {Extraction.parse(Payload(doc.text,doc.finalUrl,"text/html",doc.status),routes.search(Platform.THREADS,query),Source(Platform.THREADS,"original",Platform.THREADS.origin,role=SourceRole.ORIGINAL),routes)}
                        records+=mapOf("step" to "$label-current-parser","posts" to parsed.getOrNull()?.posts?.size,"error" to parsed.exceptionOrNull()?.let {it.javaClass.simpleName+": "+it.message})
                    }
                }
            }}}
            catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;records+=mapOf("error" to "Bounded research deadline expired")}
            catch(e:Exception){records+=mapOf("error" to (e.javaClass.simpleName+": "+e.message).take(1000))}
            finally {File(output,"results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(records));client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
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
