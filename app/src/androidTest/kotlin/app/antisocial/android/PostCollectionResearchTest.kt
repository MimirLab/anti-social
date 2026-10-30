/* anti social, GPL-3.0-or-later. Explicit bounded public-source diagnostics. */
package app.antisocial.android

import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** External caller-supplied public records never enter Room or release assets. */
class PostCollectionResearchTest {
    @Test fun inspectSelectedEditionsAndTheirAvailableImageEvidence():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialCollectionResearch")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val input=File(context.getExternalFilesDir("validation"),"post-collection-research.json")
        require(input.isFile&&input.length() in 1..4L*1024*1024)
        val raw=JsonParser.parseString(input.readText()).asJsonObject;val gson=GsonBuilder().setPrettyPrinting().create()
        require(raw.getAsJsonArray("posts").size()<=64&&raw.getAsJsonArray("follows").size()<=2000)
        val posts=raw.getAsJsonArray("posts").map {gson.fromJson(it,Post::class.java)}.toMutableList()
        val follows=raw.getAsJsonArray("follows").map {gson.fromJson(it,Follow::class.java)}
        val memberships=raw.getAsJsonObject("memberships")?.entrySet()?.associate {(key,value)->
            require(key in posts.map {it.identityKey}&&value.isJsonArray&&value.asJsonArray.size()<=follows.size)
            key to value.asJsonArray.map {it.asString.also {id->require(follows.any {follow->follow.id==id})}}.toSet()
        }.orEmpty()
        val inspect=raw.getAsJsonArray("inspect").map {it.asString}.toSet();require(inspect.size<=16)
        val output=File(context.getExternalFilesDir("validation"),"post-collection-research").apply {mkdirs()}
        val records=mutableListOf<Map<String,Any?>>()
        val cleaner=UrlCleaner(context.assets.open("clean-links.json").reader().use {JsonParser.parseReader(it).asJsonObject})
        NativeFilter.restore(context.assets.open("network-rules.compiled").use {it.readBytes()}).use {filter->
            val gateway=NetworkGateway(cleaner,filter);val client=gateway.client();val bank=MediaIdentityStore()
            gateway.onTextResponse={payload->if(payload.text.length<=2*1024*1024)File(output,"response-${Repository.hash(payload.finalUrl)}.txt").writeText(payload.text)}
            try {withTimeout(120_000){gateway.boundedMediaRead(maxBytes=32L*1024*1024,maxRequests=8) {
                if(args.getString("antisocialCollectionResearchOffline")=="true") {
                    require(inspect.isEmpty()&&raw.getAsJsonArray("feeds").isEmpty())
                    return@boundedMediaRead
                }
                raw.getAsJsonArray("feeds")?.also {require(it.size()<=2)}?.forEach {value->
                    val config=value.asJsonObject;val root=config["root"].asString;val target=repo.routes.recognize(config["url"].asString)!!
                    require(target.platform==Platform.X&&target.kind==TargetKind.PROFILE&&root.startsWith("https://"))
                    try {
                        val page=withTimeout(25_000){FrontendAdapter(Source(Platform.X,"nitter",root),gateway,repo.routes).feed(target)}
                        File(output,"feed-${target.identity}.json").writeText(gson.toJson(page))
                        val selected=page.posts.filter {it.body.contains("Roll Tide",true)||it.body.contains("keep on our streets",true)}
                        posts+=selected;records+=mapOf("feed" to root,"posts" to page.posts.size,"selected" to selected.map {it.canonicalUrl})
                    }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;records+=mapOf("feed" to root,"error" to "Timed out")}
                    catch(e:Exception){records+=mapOf("feed" to root,"error" to e.message)}
                }
                val images=posts.filter {it.canonicalUrl in inspect||it.platform==Platform.X&&it.body.contains("Roll Tide",true)}.flatMap {post->post.media.filter {it.type=="image"}.map {post to it}}.distinctBy {it.second.url}
                require(images.size<=16)
                for((post,media)in images.take(6)) {
                    val file=File(output,Repository.hash(media.url)+".image")
                    try {
                        withTimeout(15_000){gateway.download(media.url,post.canonicalUrl,file,maxBytes=6L*1024*1024)}
                        val bitmap=repo.contentSandbox.image(file,512)
                        try {bank.observe(media.url,bitmap);records+=mapOf("image" to media.url,"width" to bitmap.width,"height" to bitmap.height,"fingerprint" to bank.knows(media.url))}finally {bitmap.recycle()}
                    }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;records+=mapOf("image" to media.url,"error" to "Timed out")}
                    catch(e:Exception){records+=mapOf("image" to media.url,"error" to e.message)}
                }
            }}}catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;records+=mapOf("error" to "Research deadline expired")}
            finally {
                val captured=posts.distinctBy {it.identityKey}.map(bank::captured)
                File(output,"captured.json").writeText(gson.toJson(mapOf("posts" to captured,"follows" to follows,"memberships" to memberships)))
                val grouped=CrossPosts.group(captured,follows,memberships,sameMedia=bank::same)
                records+=mapOf("before" to CrossPosts.group(posts,follows,memberships).map {it.posts.map(Post::canonicalUrl)},"after" to grouped.map {it.posts.map(Post::canonicalUrl)},
                    "renderedMedia" to grouped.map {collectedMedia(it.posts).map(Media::url)},"checkedRequests" to gateway.protectionStats().checked)
                File(output,"results.json").writeText(gson.toJson(records));client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
            }
        }
    }
}
