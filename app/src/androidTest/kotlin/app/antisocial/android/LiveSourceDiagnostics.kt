package app.antisocial.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/** Explicit, point-in-time external evidence; skipped during ordinary tests. */
@RunWith(AndroidJUnit4::class)
class LiveSourceDiagnostics {
    @Test fun tracePublicRequests()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveDiagnostics")=="true")
        val app=ApplicationProvider.getApplicationContext<AntiSocialApplication>()
        val repo=app.repo;repo.awaitReady()
        val records=mutableListOf<Map<String,Any?>>()
        val events=Collections.synchronizedList(mutableListOf<NetworkEvent>())
        repo.gateway.onNetworkEvent={events+=it}
        try {
            val prep=System.nanoTime();repo.gateway.prepare()
            records+=mapOf("preparationMs" to (System.nanoTime()-prep)/1_000_000)
            repeat(2) {attempt->
                val source=Source(Platform.X,"nitter","https://nitter.tiekoetter.com")
                val target=repo.routes.target(Platform.X,"NASA");val started=System.nanoTime()
                val result=try {
                    val payload=withTimeout(60_000){repo.gateway.text("${source.root}/NASA",target.canonicalUrl)}
                    val received=(System.nanoTime()-started)/1_000_000
                    val page=Extraction.parse(payload,target,source,repo.routes)
                    mapOf("attempt" to attempt,"receivedMs" to received,"totalMs" to (System.nanoTime()-started)/1_000_000,"posts" to page.posts.size,"dated" to page.posts.count {it.publishedAt!=null})
                }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;mapOf("attempt" to attempt,"error" to "Timed out")}catch(e: Exception){mapOf("attempt" to attempt,"error" to e.toString())}
                records+=result
                File(app.cacheDir,"live-source-diagnostics.json").writeText(Gson().toJson(mapOf("records" to records,"events" to synchronized(events){events.toList()})))
            }
        }finally {repo.gateway.onNetworkEvent=null}
    }
}
