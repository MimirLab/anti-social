package app.antisocial.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Explicit public-source evidence, never silently treated as deterministic coverage. */
@RunWith(AndroidJUnit4::class)
class SubscriptionDiagnostics {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun livePublicIdentityEndpointsThroughTheGateway()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveAccountLinks")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo;repo.awaitReady()
        val old=repo.gateway.currentPolicy();val oldOriginal=repo.gateway.originalFallback
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"account-link-live").apply {mkdirs()}
        try {
            withContext(Dispatchers.Main){repo.tor.enable(false)}
            repo.gateway.configure(NetworkPolicy());repo.gateway.originalFallback=true
            val seed=repo.routes.target(Platform.X,"WhiteHouse");val started=System.nanoTime()
            val result=repo.gateway.boundedMediaRead(maxBytes=2L*1024*1024,maxRequests=4) {
                withTimeout(20_000) {
                    val search=repo.gateway.text(AccountLinkage.wikidataUrl(seed)!!,seed.canonicalUrl,maxBytes=512*1024,headers=mapOf("Accept" to "application/json"))
                    val payload=repo.gateway.text(AccountLinkage.wikidataEntitiesUrl(search.text)!!,seed.canonicalUrl,maxBytes=512*1024,headers=mapOf("Accept" to "application/json"))
                    AccountLinkage.wikidata(payload.text,seed,repo.routes,System.currentTimeMillis())
                }
            }
            File(dir,"wikidata.json").writeText(repo.gson.toJson(mapOf("elapsedMs" to (System.nanoTime()-started)/1_000_000,"result" to result)))
            assertTrue("Wikidata should supply supported public account links",result.accounts.size>=5)
            assertTrue("The matched entity should supply reusable proper nouns",result.nameHints.isNotEmpty())
            val kb=repo.routes.target(Platform.X,"maxtaco")
            val payload=withTimeout(10_000){repo.gateway.text(AccountLinkage.keybaseUrl(kb)!!,kb.canonicalUrl,maxBytes=512*1024,headers=mapOf("Accept" to "application/json"))}
            val associated=AccountLinkage.keybase(payload.text,kb,repo.routes,System.currentTimeMillis())
            File(dir,"keybase.json").writeText(repo.gson.toJson(associated))
            assertEquals(setOf(Platform.X,Platform.REDDIT),associated.map {it.target.platform}.toSet())
        }finally {repo.gateway.originalFallback=oldOriginal;repo.gateway.configure(old)}
    }
    @Test fun liveAccountDiscoveryAndPublicDiscussions()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveSubscriptions")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo;repo.awaitReady()
        val vm=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"subscription-live").apply {mkdirs()}
        val results=mutableListOf<Map<String,Any?>>()
        fun record(value: Map<String,Any?>){results+=value;File(dir,"results.json").writeText(repo.gson.toJson(results))}
        fun capture(name: String){ui.waitForIdle();android.os.SystemClock.sleep(350);val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        for((p,handle) in listOf(Platform.X to "NASA",Platform.BLUESKY to "bsky.app")) {
            val started=System.nanoTime()
            val found=repo.discoverAccounts(repo.routes.target(p,handle))
            record(mapOf("operation" to "discovery","platform" to p.id,"elapsedMs" to (System.nanoTime()-started)/1_000_000,"name" to found.name,"candidates" to found.candidates,"notice" to found.notice))
        }
        var successes=0
        for((p,handle) in listOf(Platform.BLUESKY to "bsky.app",Platform.X to "NASA",Platform.REDDIT to "r/android")) {
            val started=System.nanoTime()
            try {
                val feed=withTimeout(45_000){repo.open(repo.routes.target(p,handle))}
                val root=feed.posts.filter {it.parentId==null&&!it.preview}.maxByOrNull {it.replyCount ?: 0} ?: error("No public post supplied")
                for(order in ReadingOrder.entries) {
                    val before=System.nanoTime()
                    val page=withTimeout(45_000){repo.discussion(root,order=order)}
                    val comments=Discussions.comments(root,page)
                    record(mapOf("operation" to "discussion","platform" to p.id,"order" to order.label,"url" to root.canonicalUrl,"elapsedMs" to (System.nanoTime()-before)/1_000_000,"posts" to page.posts.size,"comments" to comments.size,"dated" to comments.count {it.publishedAt!=null},"source" to page.provenance?.source?.root,"next" to (page.next!=null)))
                    File(dir,"${p.id}-${order.name}.json").writeText(repo.gson.toJson(page))
                    if(comments.isNotEmpty())successes++
                }
                ui.runOnIdle {vm.discussions.open(listOf(root),root.platform.id)}
                ui.waitUntil(60_000){vm.discussions.state.value?.threads?.get(p.id)?.let {!it.loading&&(it.page!=null||it.error!=null)}==true}
                ui.onNodeWithTag("Public discussions").assertIsDisplayed();capture("${p.id}-native-comments")
                ui.runOnIdle {vm.discussions.close()}
            }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;record(mapOf("operation" to "discussion","platform" to p.id,"elapsedMs" to (System.nanoTime()-started)/1_000_000,"error" to "Timed out"))}
            catch(e: Exception){record(mapOf("operation" to "discussion","platform" to p.id,"elapsedMs" to (System.nanoTime()-started)/1_000_000,"error" to e.toString()))}
        }
        assertTrue("At least one actual public thread must supply replies; see results.json for each source's result",successes>0)
    }
}
