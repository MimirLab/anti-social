/* AntiSocial, GPL-3.0-or-later. Explicit, opt-in public-source checks only. */
package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.File

fun main()=runBlocking {
    val loader=object {}.javaClass
    fun asset(name: String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
    val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    val evidence=File(System.getProperty("antisocial.evidenceRoot")).apply {mkdirs()}
    val gson=GsonBuilder().setPrettyPrinting().create();val results=mutableListOf<Map<String,Any?>>()
    NativeFilter.restore(loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}).use {filter->
        val gateway=NetworkGateway(cleaner,filter);val pool=SourcePool(gateway,routes)
        val mode=System.getProperty("antisocial.youtubeAuditMode","all")
        suspend fun check(name: String,read: suspend (MutableMap<String,Any?>)->Unit) {
            val row=linkedMapOf<String,Any?>("case" to name);val started=System.nanoTime()
            try {withTimeout(65_000){read(row)}}catch(e: Exception){row["error"]="${e.javaClass.simpleName}: ${e.message}"}
            row["elapsedMs"]=(System.nanoTime()-started)/1_000_000;results+=row
            File(evidence,"gateway-audit.json").writeText(gson.toJson(results));println(gson.toJson(row))
        }
        if(mode in setOf("all","live")) {
            val original=YouTubeAdapter(Source(Platform.YOUTUBE,"original",Platform.YOUTUBE.origin,role=SourceRole.ORIGINAL),gateway,routes)
            check("original-video") {row->val page=original.fetch(YouTubeUrls.recognize(YouTubeUrls.canonical("jNQXAC9IVRw"))!!);row["title"]=page.posts.firstOrNull()?.title;row["posts"]=page.posts.size}
            check("routed-playback") {row->val p=pool.youtubePlayback("jNQXAC9IVRw");row["source"]=p.source;row["mime"]=p.media.mimeType;row["separateAudio"]=p.media.audioUrl!=null;row["streamProof"]=true}
            check("channel") {row->val target=YouTubeUrls.target("@NASA");val page=original.fetch(target);row["profile"]=page.profile?.name;row["posts"]=page.posts.size;row["dated"]=page.posts.count {it.publishedAt!=null};row["hasNext"]=page.next!=null;page.next?.let {val next=original.fetch(target,it);row["nextPosts"]=next.posts.size;row["additionalOlderPosts"]=next.posts.count {p->page.posts.none {it.id==p.id}};row["nextHasNext"]=next.next!=null}}
            check("search") {row->val page=original.fetch(routes.search(Platform.YOUTUBE,"laptop battery replacement"));row["posts"]=page.posts.size;row["hasNext"]=page.next!=null}
        }
        if(mode in setOf("all","recovery"))check("archive-recovery") {row->val found=YouTubeRecovery(gateway).recover(System.getProperty("antisocial.youtubeRecoveryId","jNQXAC9IVRw"));row["found"]=found!=null;found?.let {row["provider"]=it.provider;row["title"]=it.title;row["sourceUrl"]=it.sourceUrl;row["mime"]=it.media.mimeType;row["separateAudio"]=it.media.audioUrl!=null}}
        if(mode in setOf("all","sponsor"))check("sponsorblock") {row->val segments=SponsorBlockClient(gateway).read("kJQP7kiw5Fk");row["segments"]=segments.size;row["categories"]=segments.map {it.category}.distinct()}
        File(evidence,"gateway-protection.json").writeText(gson.toJson(gateway.protectionStats()))
        gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
    }
}
