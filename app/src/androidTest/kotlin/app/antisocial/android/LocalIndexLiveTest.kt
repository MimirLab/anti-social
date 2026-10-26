package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Opt-in route qualification. Parameters/evidence stay outside public source. */
@RunWith(AndroidJUnit4::class)
class LocalIndexLiveTest {
    @Test fun inspectRegionalDirectoryRequests()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialDirectoryDiagnostic")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val area=Gson().fromJson(requireNotNull(args.getString("antisocialDirectoryArea")),LocalArea::class.java)
        require(area.valid()&&area.radiusKm<=5)
        val topic=requireNotNull(args.getString("antisocialDirectoryTopic"));require(topic.length in 2..80)
        val method=requireNotNull(args.getString("antisocialDirectoryMethod"));require(method in setOf("GET","POST"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val dir=File(context.getExternalFilesDir("validation"),"local-index-live/regional-diagnostic-${method.lowercase()}").apply {mkdirs()}
        val reports=mutableListOf<Map<String,Any?>>()
        try {
            repo.localSearchConnection(true);withTimeout(90_000){repo.tor.state.first {it.enabled&&it.ready}}
            for((index,endpoint)in LocalPlaceDirectory.ENDPOINTS.withIndex()) {
                val url=LocalPlaceSources.overpassUrl(area,endpoint,topic=topic)
                val code=java.net.URI(url).rawQuery
                val begin=android.os.SystemClock.elapsedRealtime()
                val result=try {
                    val response=withTimeout(24_000){repo.gateway.extractorDocument(if(method=="GET")url else endpoint,method=method,data=if(method=="POST")code.toByteArray()else null,
                        headers=mapOf("Accept" to "application/json","Content-Type" to "application/x-www-form-urlencoded","User-Agent" to "anti-social (+https://github.com/MimirLab/anti-social; bounded regional discovery)"),sameOrigin=true,maxBytes=2L*1024*1024,route=NetworkRoute.TOR)}
                    File(dir,"response-$index.txt").writeText(response.text)
                    mapOf("endpoint" to endpoint,"method" to method,"status" to response.status,"message" to response.message,"headers" to response.headers,"bytes" to response.text.toByteArray().size,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-begin)
                }catch(e:CancellationException){currentCoroutineContext().ensureActive();if(e !is TimeoutCancellationException)throw e;mapOf("endpoint" to endpoint,"error" to "Timed out")}
                catch(e:Exception){mapOf("endpoint" to endpoint,"error" to e.javaClass.simpleName,"detail" to e.message)}
                reports+=result;File(dir,"results.json").writeText(Gson().toJson(reports))
                if((result["status"] as? Int)in setOf(401,403,429)||((result["headers"] as? Map<*,*>)?.keys?.any {it.toString().equals("Retry-After",true)}==true))break
            }
        }finally {repo.localSearchConnection(false)}
    }
    @Test fun protectedRegionalDirectoryReturnsPublishedPlaces()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialDirectoryLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val area=Gson().fromJson(requireNotNull(args.getString("antisocialDirectoryArea")),LocalArea::class.java)
        require(area.valid()&&area.radiusKm<=5)
        val topic=requireNotNull(args.getString("antisocialDirectoryTopic"));require(topic.length in 2..80)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val before=repo.prefs.export().toString();val observer=repo.gateway.onNetworkEvent
        val events=java.util.concurrent.CopyOnWriteArrayList<NetworkEvent>()
        try {
            repo.prefs.tor=false;repo.prefs.originalFallback=false;repo.prefs.searchProvider=SearchProvider.DARWIN
            repo.localSearchConnection(true);repo.applyNetworkSettings();withTimeout(90_000){repo.tor.state.first {it.enabled&&it.ready}}
            repo.gateway.onNetworkEvent={event->if(event.host in setOf("overpass.private.coffee","overpass-api.de"))events+=event}
            val began=android.os.SystemClock.elapsedRealtime()
            val data=try {withTimeout(50_000){PublicLocalDiscoveryBackend(repo).directory(LocalDiscoveryQuery(area,topic))}}
            catch(e:Exception) {
                val dir=File(context.getExternalFilesDir("validation"),"local-index-live/regional-directory").apply {mkdirs()}
                File(dir,"failure.json").writeText(Gson().toJson(mapOf("type" to e.javaClass.simpleName,"status" to (e as? SourceFailure)?.status,"httpStatus" to (e as? SourceFailure)?.httpStatus,"retryAfterMs" to (e as? SourceFailure)?.retryAfterMs,"networkEvents" to events)))
                throw e
            }
            org.junit.Assert.assertTrue(data.businesses.isNotEmpty())
            org.junit.Assert.assertTrue(data.businesses.all {it.sourceIds.any {id->id.startsWith("osm:")}})
            org.junit.Assert.assertTrue(repo.gateway.currentPolicy(NetworkRoute.TOR).ready)
            val dir=File(context.getExternalFilesDir("validation"),"local-index-live/regional-directory").apply {mkdirs()}
            File(dir,"results.json").writeText(Gson().toJson(mapOf("data" to data,"networkEvents" to events,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-began,"torReady" to repo.tor.state.value.ready)))
        }finally {repo.gateway.onNetworkEvent=observer;repo.prefs.restore(JSONObject(before));repo.localSearchConnection(false);repo.applyNetworkSettings()}
    }
    @Test fun publicLiteContinuationKeepsTheActualIssuer()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialDuckContinuationLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val before=repo.prefs.export().toString()
        try {
            repo.prefs.searchProvider=SearchProvider.DUCKDUCKGO;repo.prefs.originalFallback=true;repo.applyNetworkSettings();repo.localSearchConnection(true)
            withTimeout(90_000){repo.tor.state.first {it.enabled&&it.ready}}
            // This method qualifies a raw provider continuation. Expanded
            // query-plan composition is covered separately by the core fixture.
            val literal=args.getString("antisocialIndexQuery") ?: "\"astronomy\""
            require(literal.startsWith('"')&&literal.endsWith('"'))
            val target=repo.routes.search(Platform.X,literal)
            val query=PublicSearchAdapter.query(target);val root="https://lite.duckduckgo.com"
            val source=Source(Platform.X,"duckduckgo",SearchProvider.DUCKDUCKGO.root!!,true,SourceRole.ORIGINAL)
            val began=android.os.SystemClock.elapsedRealtime()
            val payload=repo.gateway.text(root+"/lite/?q="+DarwinRoutes.encode(query),maxBytes=1024*1024,route=NetworkRoute.TOR,allowedOrigins=setOf(root))
            val first=Extraction.parse(payload,target,source,repo.routes)
            val cursor=requireNotNull(first.next);org.junit.Assert.assertTrue(cursor.startsWith("search-lite-form:"))
            val next=withTimeout(30_000){PublicSearchAdapter(source,repo.gateway,repo.routes).fetch(target,cursor)}
            org.junit.Assert.assertTrue(next.provenance!!.finalUrl.startsWith(root+"/lite/"))
            org.junit.Assert.assertEquals(source,next.provenance!!.source)
            org.junit.Assert.assertTrue((first.collections+next.collections).isNotEmpty())
            val dir=File(context.getExternalFilesDir("validation"),"local-index-live/duck-lite-continuation").apply {mkdirs()}
            File(dir,"results.json").writeText(Gson().toJson(mapOf("firstLinks" to first.collections.size,"nextLinks" to next.collections.size,"firstUrl" to payload.finalUrl,"nextUrl" to next.provenance!!.finalUrl,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-began,"torReady" to repo.tor.state.value.ready)))
        }finally {repo.prefs.restore(JSONObject(before));repo.applyNetworkSettings();repo.localSearchConnection(false)}
    }
    @Test fun qualifiedPublisherProtocolFindsLinks()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialPublisherIndexLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val root=requireNotNull(args.getString("antisocialIndexRoot"))
        require(repo.routes.publisherSearchSources.any {it.frontend=="4get"&&it.root==root})
        val query=requireNotNull(args.getString("antisocialIndexQuery"));require(query.length in 2..256)
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val routes=DarwinRoutes(JsonParser.parseString("""{"sources":[],"services":{}}""").asJsonObject.apply {
            add("publisherSearchSources",com.google.gson.JsonArray().apply {add(com.google.gson.JsonObject().apply {addProperty("frontend","4get");addProperty("root",root)})})
        },cleaner)
        try {
            repo.localSearchConnection(true);withTimeout(90_000){repo.tor.state.first {it.enabled&&it.ready}}
            val began=android.os.SystemClock.elapsedRealtime()
            val hits=withTimeout(30_000){LocalWebDiscovery(repo.gateway,routes).search(query,SearchProvider.DARWIN,repo.prefs.originalFallback)}
            org.junit.Assert.assertTrue(hits.isNotEmpty())
            val dir=File(context.getExternalFilesDir("validation"),"local-index-live/qualified-publisher").apply {mkdirs()}
            File(dir,"results.json").writeText(Gson().toJson(mapOf("query" to query,"root" to root,"links" to hits,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-began,"torReady" to repo.tor.state.value.ready)))
        }finally {repo.localSearchConnection(false)}
    }
    /** One bounded read of published endpoints; no retries or continuation use.
     * URLs and captured responses are supplied/retained privately at execution. */
    @Test fun inspectPublishedIndexEndpoints()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIndexEndpointLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val urls=JsonParser.parseString(requireNotNull(args.getString("antisocialIndexUrls"))).asJsonArray.map {it.asString}
        require(urls.size in 1..8&&urls.distinct().size==urls.size)
        urls.forEach {val u=java.net.URI(it);require(u.scheme=="https"&&u.host!=null&&u.userInfo==null&&u.fragment==null&&it.length<=2048)}
        val label=requireNotNull(args.getString("antisocialIndexCaptureLabel"));require(label.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val dir=File(context.getExternalFilesDir("validation"),"local-index-live/$label").apply {mkdirs()}
        val reports=mutableListOf<Map<String,Any?>>()
        try {
            repo.localSearchConnection(true);withTimeout(90_000){repo.tor.state.first {it.enabled&&it.ready}}
            for((index,url)in urls.withIndex()) {
                val start=android.os.SystemClock.elapsedRealtime()
                val origin=java.net.URI(url).let {"https://"+it.rawAuthority}
                val result=try {
                    val payload=withTimeout(25_000){repo.gateway.text(url,maxBytes=1024*1024,route=NetworkRoute.TOR,allowedOrigins=setOf(origin))}
                    File(dir,"response-$index.txt").writeText(payload.text)
                    mapOf("url" to url,"status" to payload.status,"contentType" to payload.contentType,"finalUrl" to payload.finalUrl,"bytes" to payload.text.toByteArray().size,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-start)
                }catch(e:CancellationException){currentCoroutineContext().ensureActive();if(e !is TimeoutCancellationException)throw e;mapOf("url" to url,"error" to "Timed out","elapsedMs" to android.os.SystemClock.elapsedRealtime()-start)}
                catch(e:Exception){mapOf("url" to url,"error" to e.javaClass.simpleName,"detail" to e.message,"status" to (e as? SourceFailure)?.httpStatus,"retryAfterMs" to (e as? SourceFailure)?.retryAfterMs,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-start)}
                reports+=result;File(dir,"results.json").writeText(Gson().toJson(reports))
            }
        }finally {repo.localSearchConnection(false)}
    }
    @Test fun selectedPublicIndexRouterFindsPublisherLinks()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalIndexRouterLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val before=repo.prefs.export().toString()
        val provider=args.getString("antisocialIndexProvider")?.let {id->SearchProvider.entries.first {it.id==id}} ?: SearchProvider.DARWIN
        val query=args.getString("antisocialIndexQuery") ?: "public library"
        try {
            repo.prefs.searchProvider=provider;repo.applyNetworkSettings();repo.localSearchConnection(true)
            withTimeout(90_000){repo.tor.state.first {it.enabled&&it.ready}}
            val start=android.os.SystemClock.elapsedRealtime()
            val hits=withTimeout(30_000){repo.localWeb.search(query,repo.prefs.searchProvider,repo.prefs.originalFallback){repo.prefs.searchProvider==provider}}
            org.junit.Assert.assertTrue(hits.isNotEmpty())
            val dir=File(context.getExternalFilesDir("validation"),"local-index-live/selected-router").apply {mkdirs()}
            File(dir,"results.json").writeText(Gson().toJson(mapOf("links" to hits,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-start,"torReady" to repo.tor.state.value.ready)))
        }finally {repo.prefs.restore(JSONObject(before));repo.applyNetworkSettings();repo.localSearchConnection(false)}
    }
    @Test fun inspectPublicIndexRepresentations()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalIndexLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val roots=JsonParser.parseString(requireNotNull(args.getString("antisocialIndexRoots"))).asJsonArray.map {it.asString}
        require(roots.size in 1..8&&roots.distinct().size==roots.size)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val before=repo.prefs.export().toString();val reports=mutableListOf<Map<String,Any?>>()
        val label=args.getString("antisocialIndexCaptureLabel") ?: "measurement"
        require(label.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val html=args.getString("antisocialIndexRepresentation")=="html"
        val dir=File(context.getExternalFilesDir("validation"),"local-index-live/$label").apply {mkdirs()}
        try {
            repo.localSearchConnection(true);withTimeout(90_000){repo.tor.state.first {it.enabled&&it.ready}}
            for((index,root)in roots.withIndex()) {
                // One passive API read per selected candidate. No retries,
                // pagination, upstream fallback chain or location parameters.
                val url=root.trimEnd('/')+(if(html)"/search.php"else "/api.php")+"?q=public%20library&t=0&nf=1&ns=1&nfb=1"
                val start=android.os.SystemClock.elapsedRealtime()
                val result=try {
                    val payload=withTimeout(25_000){repo.gateway.text(url,maxBytes=1024*1024,route=NetworkRoute.TOR)}
                    File(dir,"response-$index.txt").writeText(payload.text)
                    mapOf("root" to root,"status" to payload.status,"contentType" to payload.contentType,"finalUrl" to payload.finalUrl,"bytes" to payload.text.toByteArray().size,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-start)
                }catch(e:CancellationException){currentCoroutineContext().ensureActive();if(e !is TimeoutCancellationException)throw e;mapOf("root" to root,"error" to "Timed out","elapsedMs" to android.os.SystemClock.elapsedRealtime()-start)}
                catch(e:Exception){mapOf("root" to root,"error" to e.javaClass.simpleName,"detail" to e.message,"status" to (e as? SourceFailure)?.httpStatus,"retryAfterMs" to (e as? SourceFailure)?.retryAfterMs,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-start)}
                reports+=result;File(dir,"results.json").writeText(Gson().toJson(reports))
            }
        }finally {repo.prefs.restore(JSONObject(before));repo.applyNetworkSettings();repo.localSearchConnection(false)}
    }
}
