/* anti social, GPL-3.0-or-later. Explicit two-page, declared-link public audit. */
package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

fun main()=runBlocking {
    val build=File(requireNotNull(System.getProperty("antisocial.buildRoot"))).canonicalFile
    val output=File(requireNotNull(System.getProperty("antisocial.feedEvidence"))).canonicalFile
    require(output.path.startsWith(build.path+File.separator)&&!output.exists()){"Use a new evidence directory under LiberBuild"}
    val url=requireNotNull(RssSources.feedUrl(requireNotNull(System.getProperty("antisocial.feedUrl"))))
    output.mkdirs();val loader=object {}.javaClass
    fun asset(name:String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
    val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    NativeFilter.restore(loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}).use {filter->
        val gateway=NetworkGateway(cleaner,filter);val client=gateway.client();val pool=SourcePool(gateway,routes)
        val follow=Follow(RssSources.followId(url),RssSources.target(url),"Explicit feed audit",feedUrl=url)
        val results=mutableListOf<Map<String,Any?>>()
        val responses=CopyOnWriteArrayList<Map<String,Any>>()
        gateway.onTextResponse={p->
            HtmlAdmission.check(p.text)
            val document=org.jsoup.Jsoup.parse(p.text,p.finalUrl,org.jsoup.parser.Parser.xmlParser())
            val root=document.children().firstOrNull()
            val head=if(root?.normalName()=="rss")root.children().firstOrNull {it.normalName()=="channel"}else root
            val navigation=head?.children()?.filter {it.normalName().substringAfter(':')=="link"&&it.attr("rel") in setOf("next","prev-archive")}
                ?.take(8)?.map {mapOf("element" to it.tagName(),"rel" to it.attr("rel"),"href" to it.attr("href").take(2048))}.orEmpty()
            responses+=mapOf("status" to p.status,"decodedBodyBytes" to p.text.toByteArray(Charsets.UTF_8).size,"finalUrl" to p.finalUrl,
                "feedId" to head?.children()?.firstOrNull {it.normalName()=="id"}?.wholeText().orEmpty().take(256),"declaredNavigation" to navigation)
        }
        try {
            var previous:Page?=null
            for(read in 1..2) {
                val started=System.nanoTime();val responseStart=responses.size
                val record=linkedMapOf<String,Any?>("read" to read,"observedAt" to System.currentTimeMillis())
                try {
                    val prior=previous
                    val page=withTimeout(20_000){if(prior==null)pool.externalFeed(follow)else pool.continueExternalFeed(follow,prior)}
                    record["posts"]=page.posts.size;record["next"]=page.next;record["limitedTimeline"]=page.limitedTimeline
                    record["logicalFeed"]=page.profile?.target?.canonicalUrl;record["fetchedUrl"]=page.provenance?.finalUrl
                    record["syndicationId"]=page.syndicationId
                    record["previousPageOverlap"]=prior?.posts?.map {it.canonicalUrl}?.toSet()?.intersect(page.posts.map {it.canonicalUrl}.toSet())?.size
                    previous=page
                }catch(e:Exception){record["error"]=e.message ?: e.javaClass.simpleName;record["retryAfterMs"]=(e as? SourceFailure)?.retryAfterMs}
                record["elapsedMs"]=(System.nanoTime()-started)/1_000_000;record["responses"]=responses.drop(responseStart);results+=record
                if(record.containsKey("error")||previous?.next==null)break
            }
        }finally {gateway.onTextResponse=null;client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        File(output,"observations.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf("scope" to "Two explicit direct production-Gateway feed pages; no guessed continuation, reader state, article bodies, live Tor or phone benchmark.","observations" to results)))
        println(GsonBuilder().create().toJson(results))
    }
}
