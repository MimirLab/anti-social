package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Explicit public head-read audit. No reader database/preferences or site writes.
 * Cold/warm describe this pool's learning, not a cleared OS/network cache. */
fun main()=runBlocking {
    val loader=object {}.javaClass
    fun asset(name:String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
    val cleaner=UrlCleaner(asset("clean-links.json")).also {it.prepare()}
    val directory=File(System.getProperty("antisocial.routeLatencyEvidence")).apply {mkdirs()}
    val selected=System.getProperty("antisocial.routeLatencyTargets").split(',').filter(String::isNotBlank)
    val gson=GsonBuilder().setPrettyPrinting().create();val permits=Semaphore(2)
    NativeFilter.restore(loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}).use {filter->
        val results=selected.mapIndexed {index,url->async(Dispatchers.IO){permits.withPermit {
            val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
            val target=requireNotNull(routes.recognize(url)){"Unsupported public audit target"}
            val gateway=NetworkGateway(cleaner,filter)
            val observations=CopyOnWriteArrayList<Map<String,Any?>>()
            var phase="cold";var started=System.nanoTime()
            val events=CopyOnWriteArrayList<Map<String,Any?>>()
            val bytes=AtomicLong()
            gateway.onNetworkEvent={event->events+=mapOf("phase" to phase,"atMs" to (System.nanoTime()-started)/1_000_000,"host" to event.host,"stage" to event.stage)}
            gateway.onTextResponse={bytes.addAndGet(it.text.toByteArray().size.toLong())}
            val pool=SourcePool(gateway,routes,PublicPathways(asset("public-pathways.json")),onProbe={probe->observations+=mapOf("phase" to phase,"atMs" to (System.nanoTime()-started)/1_000_000,"probe" to probe)})
            val rows=mutableListOf<Map<String,Any?>>()
            try {
                for(mode in listOf("cold","warm")) {
                    phase=mode;started=System.nanoTime();val previousBytes=bytes.get();val priorEvents=events.size
                    val row=linkedMapOf<String,Any?>("phase" to mode,"goal" to "public feed head, no cached frontier")
                    try {
                        val page=withTimeout(50_000){pool.load(target,feed=true,originalFallback=true)}
                        row["source"]=page.provenance?.source;row["posts"]=page.posts.size
                        row["readableDatedRoots"]=FeedRefresh.posts(page,target.platform).size
                        row["limitedTimeline"]=page.limitedTimeline;row["hasCursor"]=page.next!=null
                        row["freshFeedIssue"]=FeedRefresh.pendingReason(page,target.platform)
                    }catch(e:CancellationException){currentCoroutineContext().ensureActive();row["error"]=e.javaClass.simpleName+": "+e.message}
                    catch(e:Exception){row["error"]=e.javaClass.simpleName+": "+e.message}
                    row["elapsedMs"]=(System.nanoTime()-started)/1_000_000
                    row["completedTextBytes"]=bytes.get()-previousBytes
                    row["requestStarts"]=events.drop(priorEvents).count {it["stage"]=="start"}
                    rows+=row
                    File(directory,"case-$index.json").writeText(gson.toJson(mapOf("target" to target,"reads" to rows,"observations" to observations,"network" to events)))
                    println("${target.platform.id} $mode ${row["elapsedMs"]}ms ${row["requestStarts"]} requests ${row["source"] ?: row["error"]}")
                }
            }finally {gateway.client().dispatcher.executorService.shutdownNow();gateway.client().connectionPool.evictAll()}
            mapOf("target" to target,"reads" to rows)
        }}}.awaitAll()
        File(directory,"summary.json").writeText(gson.toJson(results))
    }
}
