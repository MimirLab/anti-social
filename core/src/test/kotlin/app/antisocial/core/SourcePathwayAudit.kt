package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.time.Instant

/** Explicit opt-in live adapter audit. Never runs as part of ordinary unit tests. */
object SourcePathwayAudit {
    @JvmStatic fun main(args: Array<String>)=runBlocking {
        val input=File(args[0]);val output=File(args[1]).apply {mkdirs()}
        val gson=GsonBuilder().setPrettyPrinting().create();val loader=SourcePathwayAudit::class.java
        fun asset(name: String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
        val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
        NativeFilter.restore(loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter);val pool=SourcePool(gateway,routes,PublicPathways(asset("public-pathways.json")))
            val permits=Semaphore(2);val hosts=java.util.concurrent.ConcurrentHashMap<String,Semaphore>()
            val cases=JsonParser.parseString(input.readText()).asJsonArray
            val results=cases.mapIndexed {index,value->async(Dispatchers.IO){permits.withPermit {
                val item=value.asJsonObject;val platform=Platform.entries.first {it.id==item["platform"].asString}
                val source=Source(platform,item["frontend"].asString,item["root"].asString,role=SourceRole.valueOf(item["role"]?.asString ?: "ALTERNATE"))
                hosts.computeIfAbsent(java.net.URI(source.root).host){Semaphore(1)}.withPermit {
                val target=routes.recognize(item["url"].asString,platform) ?: error("Unknown target")
                val row=JsonObject().apply {addProperty("id",item["id"].asString);add("source",gson.toJsonTree(source));add("target",gson.toJsonTree(target));addProperty("observedAt",Instant.now().toString());addProperty("tor",false)}
                val start=System.nanoTime()
                try {
                    val reader=pool.sourceReader(source)
                    require(reader.accepts(target)){"Reader rejected this target form"}
                    val page=withTimeout(25_000){if(item["operation"]?.asString=="feed")reader.feed(target)else reader.fetch(target)}
                    require(page.posts.isNotEmpty()&&page.posts.none {it.preview}){"No complete native cards"}
                    File(output,"page-$index.json").writeText(gson.toJson(page))
                    row.addProperty("posts",page.posts.size);row.addProperty("dated",page.posts.count {it.publishedAt!=null});row.addProperty("media",page.posts.sumOf {it.media.size});row.addProperty("comments",page.posts.count {it.parentId!=null});row.addProperty("continuation",page.next!=null);row.addProperty("cached",page.provenance?.cached==true);row.addProperty("success",true)
                }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();row.addProperty("error","Timed out");row.addProperty("success",false)}
                catch(e: Exception){row.addProperty("error",e.message);row.addProperty("status",(e as? SourceFailure)?.status?.name);row.addProperty("success",false)}
                row.addProperty("elapsedMs",(System.nanoTime()-start)/1_000_000);println(gson.toJson(row));row
                }
            }}}.awaitAll()
            File(output,"results.json").writeText(gson.toJson(results))
            gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
        }
    }
}
