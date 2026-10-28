/* anti social, GPL-3.0-or-later. Explicit, private, point-in-time diagnostics. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** No account, query or owner history is built into this opt-in collector. It
 * neither subscribes nor imports discovered posts into the retained reader. */
@RunWith(AndroidJUnit4::class)
class ReaderRoutingLiveTest {
    @Test fun suppliedPublicTargetsUseTheProductionReaderAndSearchRoutes()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialReaderRoutingLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.filesDir,"reader-routing-live.json")
        require(input.isFile&&input.length() in 1..32768)
        val requests=JsonParser.parseReader(input.reader()).asJsonArray
        require(requests.size() in 1..8)
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val before=repo.prefs.export().toString()
        val output=File(context.getExternalFilesDir("validation"),"reader-routing-live").apply {
            require(!exists()||deleteRecursively());require(mkdirs())
        }
        val runs=JsonArray();val gson=GsonBuilder().setPrettyPrinting().create()
        try {
            repo.prefs.originalFallback=true;repo.prefs.intervalMinutes=0
            withContext(Dispatchers.Main){repo.applyNetworkSettings()}
            for((index,item) in requests.withIndex()) {
                val spec=item.asJsonObject;val platform=Platform.entries.single {it.id==spec["platform"].asString}
                val target=if(spec["kind"].asString=="search")repo.routes.search(platform,spec["value"].asString)else requireNotNull(repo.routes.recognize(spec["value"].asString,platform))
                val responses=CopyOnWriteArrayList<Map<String,Any?>>()
                repo.gateway.onTextResponse={p->
                    synchronized(responses) {
                    val n=responses.size
                    responses+=mapOf("url" to p.finalUrl,"status" to p.status,"characters" to p.text.length)
                    if(n<32&&p.text.length<=2*1024*1024)File(output,"$index-response-$n.txt").writeText(p.text)
                    }
                }
                val start=System.nanoTime();val row=JsonObject().apply {add("request",item)};runs.add(row)
                fun save(){row.addProperty("elapsedMs",(System.nanoTime()-start)/1_000_000);row.add("responses",gson.toJsonTree(responses));row.add("health",gson.toJsonTree(repo.pool.results(platform)));File(output,"runs.json").writeText(gson.toJson(runs))}
                try {withTimeout(120_000) {
                    val document=spec["publicDocumentUrl"]?.asString
                    if(document!=null) {
                        val payload=repo.gateway.boundedHealthRead(maxBytes=512L*1024,maxRequests=2){repo.gateway.extractorDocument(document,sourceUrl=target.canonicalUrl,maxBytes=512L*1024)}
                        row.addProperty("documentFetched",true);row.addProperty("documentCharacters",payload.text.length)
                        row.addProperty("documentStatus",payload.status)
                        File(output,"$index-document.txt").writeText(payload.text)
                    }else if(target.kind==TargetKind.SEARCH) {
                        val feed=repo.searchFeed(target)
                        repeat(2) {feed.loadNext {state->
                            if(state.page.posts.isNotEmpty()&&!row.has("firstPostMs"))row.addProperty("firstPostMs",(System.nanoTime()-start)/1_000_000)
                            row.add("state",gson.toJsonTree(state));save()
                        }}
                    }else {
                        val feed=spec["feed"]?.asBoolean ?: false
                        val frontend=spec["sourceFrontend"]?.asString
                        require(frontend==null||frontend.matches(Regex("[A-Za-z0-9._-]{1,80}")))
                        val source=spec["sourceRoot"]?.asString?.let {root->
                            val pathways=PublicPathways(JsonParser.parseReader(context.assets.open("public-pathways.json").reader()).asJsonObject)
                            (repo.routes.sources+pathways.sources(target)).distinctBy {it.key}.single {it.platform==platform&&it.root==root&&(frontend==null||it.frontend==frontend)}
                        }
                        val page=if(source==null)repo.pool.load(target,feed=feed,progress={row.addProperty("progress",it);save()})
                            else repo.pool.sourceReader(source).let {reader->
                                if(!reader.accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"The selected method does not serve this target")
                                if(feed)reader.feed(target)else reader.fetch(target)
                            }
                        row.add("page",gson.toJsonTree(page))
                    }
                    row.addProperty("completed",true)
                }}catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;row.addProperty("error","Target timed out")}
                catch(e:Exception){row.addProperty("error",e.toString())}
                finally {repo.gateway.onTextResponse=null;save()}
            }
        }finally {repo.gateway.onTextResponse=null;repo.prefs.restore(JSONObject(before));withContext(Dispatchers.Main){repo.applyNetworkSettings()}}
    }
}
