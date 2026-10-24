/* anti social, GPL-3.0-or-later. Explicit bounded public RSS-header qualification. */
package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun main()=runBlocking {
    val build=File(requireNotNull(System.getProperty("antisocial.buildRoot"))).canonicalFile
    val output=File(requireNotNull(System.getProperty("antisocial.nitterEvidence"))).canonicalFile
    require(output.path.startsWith(build.path+File.separator)&&!output.exists()){"Use a new evidence directory under LiberBuild"}
    val loader=object {}.javaClass
    fun asset(name:String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
    val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    val selected=System.getProperty("antisocial.nitterRoots").split(',').filter(String::isNotBlank).also {require(it.size in 1..2)}
    val followHtml=System.getProperty("antisocial.nitterFollowHtml","false").toBooleanStrict()
    val sources=selected.map {root->
        val url=requireNotNull(root.toHttpUrlOrNull())
        require(url.isHttps&&url.username.isEmpty()&&url.password.isEmpty()&&url.query==null&&url.fragment==null){"Select explicit public HTTPS roots"}
        routes.sources.firstOrNull {it.platform==Platform.X&&it.frontend=="nitter"&&it.root==root} ?: Source(Platform.X,"nitter",root.trimEnd('/'))
    }
    routes.registerPathwayAliases(sources)
    val target=routes.target(Platform.X,"NASA");output.mkdirs()
    val results=mutableListOf<Map<String,Any?>>()
    NativeFilter.restore(loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}).use {filter->
        for(source in sources) {
            val gateway=NetworkGateway(cleaner,filter).apply {originalFallback=false};val client=gateway.client()
            val adapter=FrontendAdapter(source,gateway,routes);val responses=CopyOnWriteArrayList<Map<String,Any?>>()
            gateway.onTextResponse={p->responses+=mapOf("status" to p.status,"finalUrl" to p.finalUrl,"decodedBodyBytes" to p.text.toByteArray().size)}
            try {
                var previous:Page?=null
                for(read in 1..2) {
                    val start=System.nanoTime();val responseStart=responses.size
                    val record=linkedMapOf<String,Any?>("source" to source.root,"read" to read,"observedAt" to System.currentTimeMillis())
                    try {
                        val page=withTimeout(20_000){gateway.boundedHealthRead(maxBytes=1024L*1024,maxRequests=4){
                            if(previous==null) {
                                val url=routes.convert(target,source).substringBefore('?').trimEnd('/')+"/rss"
                                gateway.feed(url,target,source,maxBytes=1024L*1024){Extraction.parse(it,target,source,routes)}
                            }else adapter.fetch(target,requireNotNull(previous?.next))
                        }}
                        val prior=previous
                        record["posts"]=FeedRefresh.posts(page,Platform.X).size;record["nextAvailable"]=page.next!=null
                        record["datedPosts"]=FeedRefresh.posts(page,Platform.X).count {it.publishedAt!=null}
                        record["pendingReason"]=FeedRefresh.pendingReason(page,Platform.X)
                        record["headerBookmark"]=page.next?.let(NitterFeedHistory::bookmark)==true
                        record["fetchedUrl"]=page.provenance?.finalUrl?.substringBefore('?')
                        record["previousPageOverlap"]=prior?.posts?.map {it.id}?.toSet()?.intersect(page.posts.map {it.id}.toSet())?.size
                        record["navigationSha256"]=page.next?.let {value->MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}}
                        previous=page
                    }catch(e:CancellationException){currentCoroutineContext().ensureActive();record["error"]=e.javaClass.simpleName+": "+e.message}
                    catch(e:Exception){record["error"]=e.javaClass.simpleName+": "+e.message;record["retryAfterMs"]=(e as? SourceFailure)?.retryAfterMs}
                    record["elapsedMs"]=(System.nanoTime()-start)/1_000_000;record["responses"]=responses.drop(responseStart).map {it+mapOf("finalUrl" to (it["finalUrl"]as String).substringBefore('?'))};results+=record
                    if(record.containsKey("error")||record["pendingReason"]!=null||previous?.next==null||!followHtml&&previous?.next?.let(NitterFeedHistory::bookmark)!=true)break
                }
            }finally {gateway.onTextResponse=null;client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
    }
    val receipt=mapOf("scope" to "Explicit direct production-Gateway reads with compiled protection, at most two selected HTTPS sources/two pages each, at most 1 MiB per read. Candidate roots do not change the runtime pool. No reader state, raw cursor, article body, live Tor or phone benchmark.","followHtml" to followHtml,"observations" to results)
    File(output,"observations.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(receipt));println(GsonBuilder().create().toJson(receipt))
}
