/* anti social, GPL-3.0-or-later. Opt-in public-source qualification, never a default regression. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AdjacentDiscoveryLiveTest {
    @Test fun publicGraphPayloadsPassTheActualAndroidGateway()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveAdjacent")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=(context as AntiSocialApplication).repo
        repo.awaitReady();val old=repo.gateway.currentPolicy();val fallback=repo.gateway.originalFallback
        val rows=ArrayList<Map<String,Any>>();var healthy=0
        repo.gateway.configure(NetworkPolicy());repo.gateway.originalFallback=true
        try {
            val seeds=listOf(Profile(repo.routes.target(Platform.BLUESKY,"bsky.app"),"Bluesky"),Profile(repo.routes.target(Platform.X,"NASA"),"NASA"),
                Profile(repo.routes.target(Platform.MASTODON,"https://mastodon.social/@Gargron"),"Gargron"),Profile(repo.routes.target(Platform.REDDIT,"u/spez"),"Reddit public account"))
            for(seed in seeds) {
                val started=System.nanoTime()
                try {
                    if(seed.target.platform==Platform.REDDIT) {
                        val runtime=repo.adjacentSession(seed,force=true)
                        val result=repo.adjacentNext(runtime){}
                        require(result.coverage.any {it.status in setOf(HealthStatus.HEALTHY,HealthStatus.EMPTY)}){"Archive graph did not complete within the app's allowance"}
                        healthy++
                        rows+=mapOf("platform" to seed.target.platform.id,"status" to "app-session","cards" to result.cards.size,"requests" to result.requests,"elapsedMs" to (System.nanoTime()-started)/1_000_000)
                        continue
                    }
                    var q=AdjacentSources.plan(seed,repo.routes).first {it.operation!=AdjacentOperation.COMMUNITIES}
                    suspend fun read(request:AdjacentRequest):AdjacentBatch {
                        val ep=request.endpoints.last();if(ep.source.role==SourceRole.ORIGINAL)repo.gateway.registerOriginalUrl(ep.source.root)
                        val d=repo.gateway.extractorDocument(ep.url,maxBytes=512*1024,headers=mapOf("Accept" to "application/json"),purpose=FetchPurpose.API)
                        return AdjacentSources.parse(request,ep,AdjacentDocument(Payload(d.text,d.finalUrl,"application/json",d.status),d.headers),repo.routes,System.currentTimeMillis())
                    }
                    var batch=withTimeout(15_000){read(q)}
                    if(q.operation==AdjacentOperation.LOOKUP){q=batch.next.first {it.operation==AdjacentOperation.FEATURED};batch=withTimeout(15_000){read(q)}}
                    healthy++
                    rows+=mapOf("platform" to seed.target.platform.id,"status" to "parsed","cards" to batch.observations.size,"sampled" to batch.sampled,"more" to batch.more,"elapsedMs" to (System.nanoTime()-started)/1_000_000)
                }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;rows+=mapOf("platform" to seed.target.platform.id,"status" to "timeout")}
                catch(e:Exception){rows+=mapOf("platform" to seed.target.platform.id,"status" to (e.message ?: e.javaClass.simpleName).take(180))}
            }
            val out=File(requireNotNull(context.getExternalFilesDir("validation")),"adjacent-discovery/live-gateway.json");out.parentFile!!.mkdirs();out.writeText(Gson().toJson(rows))
            assertTrue("Fewer than two independent public sources worked: $rows",healthy>=2)
        }finally {repo.gateway.originalFallback=fallback;repo.gateway.configure(old)}
    }
    @Test fun nasaYoutubeAndSoundcloudBridgesWorkThroughTheActualGateway()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialLiveAdjacent")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=(context as AntiSocialApplication).repo
        repo.awaitReady();val policy=repo.gateway.currentPolicy();val original=repo.prefs.originalFallback
        repo.prefs.originalFallback=true;repo.gateway.configure(NetworkPolicy())
        val rows=ArrayList<Map<String,Any>>()
        try {
            val nasa=repo.discoveryBridge(PublicAccount("youtube","https://www.youtube.com/@NASA"),true,true)
            assertEquals("UCLA_DiR1FfKNvjuUpBHmylQ",nasa.account.id);assertTrue("NASA's creator-selected channels were missing",nasa.neighbors.size>=5)
            val peers=nasa.neighbors.take(2)
            var social=0
            for(peer in peers) {
                val page=repo.discoveryBridge(peer.account,false,true)
                val target=PublicAccounts.target(page.account,repo.routes)!!
                val identity=repo.discoverRoutingAccounts(target,Profile(target,page.account.name,page.description,links=page.links))
                val readable=(page.links.mapNotNull {repo.routes.recognize(it)}+identity.candidates.map {it.target}).filter(AdjacentDiscovery::valid).distinctBy(AdjacentDiscovery::key)
                social+=readable.size;rows+=mapOf("provider" to "youtube","name" to page.account.name,"socialAccounts" to readable.map {it.canonicalUrl})
            }
            assertTrue("No YouTube neighbor returned to social accounts",social>0)
            val audio=repo.discoveryBridge(PublicAccount("soundcloud","https://soundcloud.com/nasa"),true,true)
            assertTrue("NASA's public SoundCloud follows were missing",audio.neighbors.any {it.account.url.contains("/esa")})
            rows+=mapOf("provider" to "soundcloud","neighbors" to audio.neighbors.map {it.account.url},"links" to audio.links)
            val out=File(requireNotNull(context.getExternalFilesDir("validation")),"adjacent-discovery/live-bridges.json");out.parentFile!!.mkdirs();out.writeText(Gson().toJson(rows))
        }finally {repo.prefs.originalFallback=original;repo.gateway.configure(policy)}
    }

}
