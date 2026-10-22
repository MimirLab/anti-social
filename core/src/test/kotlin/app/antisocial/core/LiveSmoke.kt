package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.zip.GZIPInputStream

/** Explicit opt-in external availability check; not a deterministic unit test. */
fun main()=runBlocking {
    val loader=object {}.javaClass
    fun asset(name: String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
    val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    NativeFilter(GZIPInputStream(loader.getResourceAsStream("/network-rules.seed")!!).reader().readText()).use {filter->
        val gateway=NetworkGateway(cleaner,filter);val pool=SourcePool(gateway,routes);val permits=Semaphore(3)
        val results=Platform.entries.map {p->async(Dispatchers.IO){permits.withPermit {
            val source=routes.sources.first {it.platform==p};val target=FrontendAdapter(source,gateway,routes).sentinel(Capability.TIMELINE)
            try {val page=pool.load(target);page.provenance?.let {provenance->runCatching {val raw=gateway.text(provenance.finalUrl,target.canonicalUrl);java.io.File(System.getProperty("antisocial.evidenceRoot"),"${p.id}.txt").apply {parentFile.mkdirs();writeText(raw.text)}}};mapOf("platform" to p.id,"status" to if(page.posts.isEmpty())"profile_only"else "content","posts" to page.posts.size,"dated" to page.posts.count {it.publishedAt!=null},"source" to page.provenance?.source?.root,"role" to page.provenance?.source?.role?.name)}
            catch(e: Exception){mapOf("platform" to p.id,"status" to "unavailable","reason" to e.message)}
        }}}.awaitAll()
        val reddit=java.io.File(System.getProperty("antisocial.evidenceRoot"),"reddit.txt")
        val linkedPost=if(reddit.exists())Regex("https://(?:x|twitter)\\.com/[A-Za-z0-9_]+/status/[0-9]+").find(reddit.readText())?.value else null
        val postResult=linkedPost?.let {url->
            val target=routes.recognize(url)!!;val original=Source(Platform.X,"original",Platform.X.origin,role=SourceRole.ORIGINAL)
            try {val page=OriginalAdapter(original,gateway,routes).fetch(target);mapOf("platform" to "twitter-post","status" to "content","posts" to page.posts.size,"dated" to page.posts.count {it.publishedAt!=null})}catch(e: Exception){mapOf("platform" to "twitter-post","status" to "unavailable","reason" to e.message)}
        }
        val feeds=listOf(Platform.REDDIT,Platform.TUMBLR,Platform.BLUESKY).map {p->async(Dispatchers.IO){
            val source=routes.sources.first {it.platform==p};val target=FrontendAdapter(source,gateway,routes).sentinel(Capability.FEED)
            try {val page=pool.load(target,feed=true);mapOf("platform" to "${p.id}-feed","status" to "content","posts" to page.posts.size,"dated" to page.posts.count {it.publishedAt!=null},"source" to page.provenance?.source?.root,"role" to page.provenance?.source?.role?.name)}catch(e: Exception){mapOf("platform" to "${p.id}-feed","status" to "unavailable","reason" to e.message)}
        }}.awaitAll()
        println(Gson().toJson(results+listOfNotNull(postResult)+feeds))
    }
}
