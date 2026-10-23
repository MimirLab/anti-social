package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.Instant

/** Explicit isolated public-record audits. No reader's live database is modified. */
class ProductPolishAuditTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val repo get()=(context as AntiSocialApplication).repo
    @Test fun inspectRetainedPublicPixelsAndCaptions()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialPolishMediaAudit")=="true")
        repo.awaitReady();val dir=File(context.noBackupFilesDir,"product-polish-input");val gson=Gson()
        val posts=gson.fromJson(File(dir,"posts.json").readText(),Array<Post>::class.java).toList()
        val bank=MediaIdentityStore();val decoded=mutableListOf<Map<String,Any>>()
        for(value in JsonParser.parseString(File(dir,"images.json").readText()).asJsonArray) {
            val row=value.asJsonObject;val url=row["url"].asString;val file=File(dir,row["file"].asString)
            val image=repo.contentSandbox.image(file,512)
            try {withContext(Dispatchers.Default){bank.observe(url,image)};decoded+=mapOf("url" to url,"width" to image.width,"height" to image.height,"known" to bank.knows(url))}finally {image.recycle()}
        }
        val captured=posts.map(bank::captured);val wh=captured.filter {it.body.startsWith("Saving American lives")}
        val pairs=buildList {for(i in wh.indices)for(j in i+1 until wh.size) {
            val a=wh[i].media.first();val b=wh[j].media.first()
            add(mapOf("a" to i,"b" to j,"known" to (a.visualFingerprint!=null&&b.visualFingerprint!=null),"support" to VisualFrames.same(a.visualFingerprint,b.visualFingerprint),"gallery" to VisualFrames.sameImage(a.visualFingerprint,b.visualFingerprint)))
        }}
        val out=File(context.getExternalFilesDir("validation"),"product-polish/media.json").apply {parentFile!!.mkdirs()}
        out.writeText(gson.toJson(mapOf("decoded" to decoded,"pairs" to pairs,"captured" to captured,"galleryCount" to collectedMedia(wh,bank).size)))
        assertTrue(decoded.isNotEmpty())
    }
    @Test fun auditSiteWideInstagramAndTikTokThroughProductionSearch()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialPolishSearchAudit")=="true")
        repo.awaitReady();val before=repo.prefs.export().toString();val report=mutableListOf<Map<String,Any?>>()
        val out=File(context.getExternalFilesDir("validation"),"product-polish/search.json").apply {parentFile!!.mkdirs()}
        try {
            repo.prefs.originalFallback=true;repo.prefs.tor=false;repo.prefs.searchProvider=SearchProvider.entries.firstOrNull {it.id==args.getString("antisocialPolishProvider")} ?: SearchProvider.DUCKDUCKGO;repo.applyNetworkSettings()
            for(platform in listOf(Platform.INSTAGRAM,Platform.TIKTOK,Platform.BLUESKY,Platform.REDDIT)) {
                val target=repo.routes.search(platform,if(platform==Platform.REDDIT)"space"else "nasa")
                val started=System.nanoTime();var first:Long?=null
                val session=repo.searchFeed(target);var failure:String?=null
                try {withTimeout(90_000){session.loadNext {if(first==null&&it.page.posts.isNotEmpty())first=(System.nanoTime()-started)/1_000_000}}}
                catch(e:TimeoutCancellationException){failure="Timed out"}catch(e:CancellationException){throw e}catch(e:Exception){failure=e.message}
                val result=session.state
                report+=mapOf("platform" to platform.id,"query" to target.identity,"firstPostMs" to first,"elapsedMs" to (System.nanoTime()-started)/1_000_000,"posts" to result.page.posts.size,"accounts" to result.page.accounts.size,"source" to result.page.provenance?.source,"error" to (failure ?: result.error),"notice" to result.page.notice,"hasMore" to result.hasMore,"destinations" to result.page.posts.map {it.canonicalUrl},"routes" to repo.pool.results(platform),"provider" to repo.pool.searchProvider,"torReady" to repo.tor.state.value.ready)
                out.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            }
        }finally {repo.prefs.restore(org.json.JSONObject(before));repo.applyNetworkSettings()}
    }
}
