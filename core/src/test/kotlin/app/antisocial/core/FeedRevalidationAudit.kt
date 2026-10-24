/* anti social, GPL-3.0-or-later. Explicit, bounded public-feed observations. */
package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

fun main()=runBlocking {
    val build=File(requireNotNull(System.getProperty("antisocial.buildRoot"))).canonicalFile
    val output=File(requireNotNull(System.getProperty("antisocial.feedEvidence"))).canonicalFile
    require(output.path.startsWith(build.path+File.separator)&&!output.exists()){ "Use a new evidence directory under LiberBuild" }
    val urls=System.getProperty("antisocial.feedUrls","https://www.nasa.gov/feed/").split('|').filter(String::isNotBlank)
    require(urls.size in 1..3);urls.forEach {requireNotNull(RssSources.feedUrl(it))}
    output.mkdirs()
    val loader=object {}.javaClass
    val cleaner=UrlCleaner(JsonParser.parseReader(loader.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    NativeFilter.restore(loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}).use {filter->
        val gateway=NetworkGateway(cleaner,filter);val client=gateway.client();val results=mutableListOf<Map<String,Any?>>()
        try {for(url in urls) {
            val responses=CopyOnWriteArrayList<Map<String,Any>>()
            gateway.onTextResponse={p->responses+=mapOf("status" to p.status,"decodedBodyBytes" to p.text.toByteArray(Charsets.UTF_8).size,"finalUrl" to p.finalUrl)}
            val target=RssSources.target(url);val source=RssSources.source(url);val parses=AtomicInteger()
            for(read in 1..2) {
                val began=System.nanoTime();val responseStart=responses.size
                val record=linkedMapOf<String,Any?>("url" to url,"read" to read,"observedAt" to System.currentTimeMillis())
                try {
                    val page=withTimeout(20_000){gateway.feed(url,target,source){p->parses.incrementAndGet();RssSources.parse(p,target)}}
                    record["posts"]=page.posts.size;record["pendingReason"]=FeedRefresh.pendingReason(page,Platform.RSS)
                    record["fetchedAt"]=page.provenance?.fetchedAt
                }catch(e:Exception){record["error"]=e.message ?: e.javaClass.simpleName;record["retryAfterMs"]=(e as? SourceFailure)?.retryAfterMs}
                record["elapsedMs"]=(System.nanoTime()-began)/1_000_000;record["parserCallsSoFar"]=parses.get();record["responses"]=responses.drop(responseStart)
                results+=record
                if(record.containsKey("error"))break // Never immediately repeat a refused/failed public request.
            }
        }}finally {gateway.onTextResponse=null;client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        File(output,"observations.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf("scope" to "Dated direct Gateway/feed-reader observations with compiled protection; not a phone, live-Tor or controlled provider benchmark.","observations" to results)))
        println(GsonBuilder().create().toJson(results))
    }
}
