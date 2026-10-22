package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in external smoke test; ordinary unit tests never contact websites. */
fun main()=runBlocking {
    val loader=object {}.javaClass
    fun asset(name: String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
    val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    val evidence=File(System.getProperty("antisocial.evidenceRoot")).apply {mkdirs()};val gson=GsonBuilder().setPrettyPrinting().create()
    val cases=listOf(
        Triple(Platform.MASTODON,"mastodon-api","https://mstdn.social/@stux"),
        Triple(Platform.MASTODON,"mastodon-rss","https://mstdn.social/@stux"),
        Triple(Platform.LEMMY,"lemmy-api","https://lemmy.world/c/technology"),
        Triple(Platform.PIXELFED,"pixelfed-atom","https://pixelfed.social/pixelfed"),
        Triple(Platform.GETTR,"gettr-api","https://gettr.com/user/joerogan"),
        Triple(Platform.GAB,"gab-api","https://gab.com/AmericanFamAssc"),
        Triple(Platform.TELEGRAM,"telegram-public","https://t.me/telegram"),
        Triple(Platform.MISSKEY,"misskey-api","https://misskey.io/@hmzdk3721")
    )
    NativeFilter.restore(loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}).use {filter->
        val gateway=NetworkGateway(cleaner,filter);val counter=AtomicInteger();val permits=Semaphore(3)
        gateway.onTextResponse={payload->File(evidence,"response-${counter.incrementAndGet()}.json").writeText(gson.toJson(payload))}
        val selected=System.getProperty("antisocial.auditPlatforms","").split(',').filter {it.isNotBlank()}.toSet()
        val results=cases.filter {selected.isEmpty()||it.first.id in selected}.map {(platform,frontend,url)->async(Dispatchers.IO) {permits.withPermit {
            val root=if(platform==Platform.GETTR)"https://api.gettr.com"else java.net.URI(url).let {"https://${it.host}"}
            val source=Source(platform,frontend,root,role=SourceRole.ORIGINAL)
            val target=Target(platform,if(platform==Platform.LEMMY)TargetKind.COMMUNITY else TargetKind.PROFILE,url.substringAfterLast('/'),url)
            val reader=publicSocialAdapter(source,gateway,routes)!!;val result=linkedMapOf<String,Any?>("platform" to platform.id,"source" to source,"target" to target,"tor" to false)
            val started=System.nanoTime()
            try {
                withTimeout(45_000) {
                    val page=reader.fetch(target);result["firstPage"]=page.posts.size;result["profile"]=page.profile?.name;result["notice"]=page.notice;result["elapsedMs"]=(System.nanoTime()-started)/1_000_000
                    result["examples"]=page.posts.take(3).map {mapOf("url" to it.canonicalUrl,"body" to it.body.take(180),"media" to it.media.size,"publishedAt" to it.publishedAt)}
                    page.next?.let {cursor->try {val older=reader.fetch(target,cursor);result["olderPage"]=older.posts.size;result["additionalOlderPosts"]=older.posts.count {post->page.posts.none {it.id==post.id}}}catch(e: CancellationException){throw e}catch(e: Exception){result["paginationError"]=e.message}}
                    if(Capability.POST in reader.capabilities) {
                        val post=page.posts.firstOrNull {it.replyCount?.let {n->n>0}==true} ?: page.posts.firstOrNull()
                        post?.let {p->try {val thread=Target(platform,TargetKind.POST,p.canonicalUrl.substringAfterLast('/'),p.canonicalUrl);val discussion=reader.fetch(thread);result["selectedPost"]=discussion.posts.any {ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(platform,p.canonicalUrl)};if(Capability.COMMENTS in reader.capabilities)result["comments"]=Discussions.comments(p,discussion).size;result["postNotice"]=discussion.notice}catch(e: CancellationException){throw e}catch(e: Exception){result["postError"]=e.message}}
                    }
                }
            }catch(e: Exception){result["error"]="${e.javaClass.simpleName}: ${e.message}"}
            println(gson.toJson(result));result
        }}}.awaitAll()
        File(evidence,"results.json").writeText(gson.toJson(results))
        gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
    }
}
