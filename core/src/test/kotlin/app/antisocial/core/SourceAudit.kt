package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.zip.GZIPInputStream

/** Explicit public source diagnosis. Raw responses stay in external evidence storage. */
fun main(args: Array<String>): Unit=runBlocking {
    val loader=object {}.javaClass
    fun asset(name: String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
    val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    val wanted=System.getProperty("antisocial.auditPlatforms").split(',').toSet()
    val permits=Semaphore(4)
    NativeFilter(GZIPInputStream(loader.getResourceAsStream("/network-rules.seed")!!).reader().readText()).use { filter ->
        val gateway=NetworkGateway(cleaner,filter)
        val rootFilter=System.getProperty("antisocial.auditRoots").split(',').filter {it.isNotBlank()}
        val sources=if(System.getProperty("antisocial.auditOriginal")=="true")Platform.entries.filter {it.id in wanted}.map {Source(it,"original",it.origin,role=SourceRole.ORIGINAL)}else routes.sources.filter {it.platform.id in wanted&&(rootFilter.isEmpty()||rootFilter.any {root->it.root.contains(root)})}
        sources.map {source->async(Dispatchers.IO) {permits.withPermit {
            val override=System.getProperty("antisocial.auditTarget").takeIf {it.isNotBlank()}
            val target=override?.let {routes.recognize(it)} ?: FrontendAdapter(source,gateway,routes).sentinel(Capability.TIMELINE)
            val url=routes.convert(target,source)
            val record=linkedMapOf<String,Any?>("platform" to source.platform.id,"frontend" to source.frontend,"url" to url)
            try {
                val payload=withTimeout(15_000){gateway.text(url,target.canonicalUrl)}
                record["finalUrl"]=payload.finalUrl
                File(System.getProperty("antisocial.evidenceRoot"),"${source.platform.id}-${java.net.URI(source.root).host}.txt").apply {parentFile.mkdirs();writeText(payload.text)}
                val page=Extraction.parse(payload,target,source,routes)
                record["posts"]=page.posts.size;record["dated"]=page.posts.count {it.publishedAt!=null};record["profile"]=page.profile?.name;record["collections"]=page.collections.size
                if(System.getProperty("antisocial.auditAdapter")=="true") {
                    val full=withTimeout(25_000){FrontendAdapter(source,gateway,routes).fetch(target)}
                    record["readerPosts"]=full.posts.size;record["readerDates"]=full.posts.count {it.publishedAt!=null}
                    val feed=withTimeout(25_000){FrontendAdapter(source,gateway,routes).feed(target)}
                    record["feedPosts"]=feed.posts.size;record["feedDates"]=feed.posts.count {it.publishedAt!=null}
                }
            }catch(e: Exception){record["error"]=e.message ?: e.javaClass.simpleName}
            println(Gson().toJson(record))
        }}}.awaitAll()
    }
    Unit
}
