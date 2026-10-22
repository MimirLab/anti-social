package app.antisocial.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Explicit real-reader availability records, not a claim that every upstream works. */
@RunWith(AndroidJUnit4::class)
class PlatformDiagnostics {
    @Test fun auditRecentImages(): Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveMedia")=="true")
        val repo=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo;repo.awaitReady()
        val gson=GsonBuilder().setPrettyPrinting().serializeNulls().create();val permits=Semaphore(3)
        val posts=repo.db.dao().allPosts().sortedByDescending {it.createdAt}.map {gson.fromJson(it.payload,Post::class.java)}
        val records=Platform.entries.mapNotNull {platform->posts.firstOrNull {it.platform==platform&&it.media.any {media->media.type=="image"||media.preview!=null}}}.map {post->async(Dispatchers.IO){permits.withPermit {
            val media=post.media.first {it.type=="image"||it.preview!=null};val url=if(media.type=="image")media.url else media.preview!!
            val start=System.currentTimeMillis()
            val result=try {val bitmap=repo.images.load(url,post.canonicalUrl,768);mapOf("width" to bitmap.width,"height" to bitmap.height)}catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;mapOf("error" to "Image request timed out")}catch(e: Exception){mapOf("error" to e.toString())}
            mapOf("platform" to post.platform.id,"post" to post.canonicalUrl,"url" to url,"elapsedMs" to System.currentTimeMillis()-start)+result
        }}}.awaitAll()
        File(requireNotNull(repo.context.getExternalFilesDir("validation")),"platform-media.json").writeText(gson.toJson(records))
    }
    @Test fun readExplicitResponses(): Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments();val urls=args.getString("antisocialExplicitUrls")
        assumeTrue(!urls.isNullOrBlank())
        val repo=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo;repo.awaitReady()
        val dir=File(requireNotNull(repo.context.getExternalFilesDir("validation")),"explicit-responses").apply {mkdirs()}
        val records=urls!!.split('|').mapIndexed {index,url->
            try {val payload=repo.gateway.text(url);File(dir,"response-$index.txt").writeText(payload.text);mapOf("url" to url,"finalUrl" to payload.finalUrl,"bytes" to payload.text.length)}catch(e: Exception){mapOf("url" to url,"error" to e.toString())}
        }
        File(dir,"measurements.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(records))
    }
    @Test fun auditPublicReadersAndSearch(): Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialPlatformAudit")=="true")
        val repo=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo;repo.awaitReady()
        withContext(Dispatchers.Main){repo.applyNetworkSettings()}
        val label=args.getString("antisocialAuditLabel") ?: "current"
        val wanted=args.getString("antisocialAuditPlatforms")?.split(',')?.toSet()
        val dir=File(requireNotNull(repo.context.getExternalFilesDir("validation")),"platforms-$label").apply {mkdirs()}
        val gson=GsonBuilder().setPrettyPrinting().serializeNulls().create()
        val records=mutableListOf<Map<String,Any?>>()
        val responseCount=AtomicInteger();val permits=Semaphore(2)
        repo.gateway.onTextResponse={payload->
            val n=responseCount.incrementAndGet()
            if(n<=80)File(dir,"response-$n-${java.net.URI(payload.finalUrl).host}.txt").writeText(payload.text)
        }
        suspend fun record(platform: Platform,kind: String,action: suspend ()->Page): Page? {
            val start=System.nanoTime();var page: Page?=null
            val result=try {
                page=withTimeout(60_000){action()}
                mapOf("posts" to page.posts.size,"dated" to page.posts.count {it.publishedAt!=null},"profile" to page.profile?.name,"collections" to page.collections.size,"accounts" to page.accounts.size,"next" to (page.next!=null),"source" to page.provenance?.source?.root,"frontend" to page.provenance?.source?.frontend,"sample" to page.posts.firstOrNull()?.canonicalUrl)
            }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;mapOf("error" to "Timed out")}catch(e: Exception){mapOf("error" to e.toString())}
            val entry=mapOf("platform" to platform.id,"operation" to kind,"elapsedMs" to (System.nanoTime()-start)/1_000_000)+result
            synchronized(records){records+=entry;File(dir,"measurements.json").writeText(gson.toJson(records))}
            android.util.Log.i("AntiSocialPlatformAudit",gson.toJson(entry));return page
        }
        try {
            Platform.entries.filter {it!=Platform.X&&(wanted==null||it.id in wanted)}.map {platform->async(Dispatchers.IO){permits.withPermit {
                val target=when(platform) {
                    Platform.REDDIT->repo.routes.target(platform,"r/Android")
                    Platform.BLUESKY->repo.routes.target(platform,"bsky.app")
                    Platform.TUMBLR->repo.routes.target(platform,"staff")
                    Platform.IFUNNY->repo.routes.target(platform,"user/ifunny")
                    Platform.TIEBA->repo.routes.target(platform,"Android")
                    else->repo.routes.target(platform,"nasa")
                }
                val reader=record(platform,"reader"){repo.pool.load(target,originalFallback=repo.prefs.originalFallback)}
                if(reader?.next!=null)record(platform,"reader-next"){repo.pool.continuePage(target,reader,repo.prefs.originalFallback)}
                if(args.getString("antisocialAuditDetails")=="true"&&reader?.posts?.isNotEmpty()==true) {
                    repo.routes.recognize(reader.posts.first().canonicalUrl)?.let {post->record(platform,"post"){repo.pool.load(post,originalFallback=repo.prefs.originalFallback)}}
                }
                val search=runCatching {repo.routes.search(platform,if(platform==Platform.REDDIT)"android"else "space")}.getOrNull()
                if(search!=null) {
                    val found=record(platform,"search"){repo.pool.load(search,originalFallback=repo.prefs.originalFallback)}
                    if(found?.next!=null)record(platform,"next"){repo.pool.continuePage(search,found,repo.prefs.originalFallback)}
                    if(found!=null&&args.getString("antisocialAuditFeeds")=="true")record(platform,"search-feed") {
                        val session=SearchFeed(search,discover={previous->if(previous==null)found else repo.pool.continuePage(search,previous,repo.prefs.originalFallback)},read={post->
                            val result=repo.pool.load(post,originalFallback=repo.prefs.originalFallback)
                            synchronized(records){File(dir,"resolved-${platform.id}.jsonl").appendText(gson.toJson(mapOf("requested" to post,"profile" to result.profile,"posts" to result.posts.size,"source" to result.provenance?.source))+"\n")}
                            result
                        })
                        session.loadNext {}
                        File(dir,"feed-${platform.id}.json").writeText(gson.toJson(session.state))
                        session.state.page
                    }
                }else synchronized(records){records+=mapOf("platform" to platform.id,"operation" to "search","error" to "Not implemented");File(dir,"measurements.json").writeText(gson.toJson(records))}
            }}}.awaitAll()
        }finally {repo.gateway.onTextResponse=null}
    }
}
