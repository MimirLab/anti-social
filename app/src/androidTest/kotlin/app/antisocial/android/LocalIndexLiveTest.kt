package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Opt-in route qualification. Parameters/evidence stay outside public source. */
@RunWith(AndroidJUnit4::class)
class LocalIndexLiveTest {
    @Test fun selectedPublicIndexRouterFindsPublisherLinks()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalIndexRouterLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val before=repo.prefs.export().toString()
        try {
            repo.prefs.searchProvider=SearchProvider.DARWIN;repo.applyNetworkSettings();repo.localSearchConnection(true)
            withTimeout(90_000){repo.tor.state.first {it.enabled&&it.ready}}
            val start=android.os.SystemClock.elapsedRealtime()
            val hits=withTimeout(30_000){repo.localWeb.search("public library",repo.prefs.searchProvider,repo.prefs.originalFallback){repo.prefs.searchProvider==SearchProvider.DARWIN}}
            org.junit.Assert.assertTrue(hits.isNotEmpty())
            val dir=File(context.getExternalFilesDir("validation"),"local-index-live/selected-router").apply {mkdirs()}
            File(dir,"results.json").writeText(Gson().toJson(mapOf("links" to hits,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-start,"torReady" to repo.tor.state.value.ready)))
        }finally {repo.prefs.restore(JSONObject(before));repo.applyNetworkSettings();repo.localSearchConnection(false)}
    }
    @Test fun inspectPublicIndexRepresentations()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalIndexLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val roots=JsonParser.parseString(requireNotNull(args.getString("antisocialIndexRoots"))).asJsonArray.map {it.asString}
        require(roots.size in 1..8&&roots.distinct().size==roots.size)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val before=repo.prefs.export().toString();val reports=mutableListOf<Map<String,Any?>>()
        val label=args.getString("antisocialIndexCaptureLabel") ?: "measurement"
        require(label.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val html=args.getString("antisocialIndexRepresentation")=="html"
        val dir=File(context.getExternalFilesDir("validation"),"local-index-live/$label").apply {mkdirs()}
        try {
            repo.localSearchConnection(true);withTimeout(90_000){repo.tor.state.first {it.enabled&&it.ready}}
            for((index,root)in roots.withIndex()) {
                // One passive API read per selected candidate. No retries,
                // pagination, upstream fallback chain or location parameters.
                val url=root.trimEnd('/')+(if(html)"/search.php"else "/api.php")+"?q=public%20library&t=0&nf=1&ns=1&nfb=1"
                val start=android.os.SystemClock.elapsedRealtime()
                val result=try {
                    val payload=withTimeout(25_000){repo.gateway.text(url,maxBytes=1024*1024,route=NetworkRoute.TOR)}
                    File(dir,"response-$index.txt").writeText(payload.text)
                    mapOf("root" to root,"status" to payload.status,"contentType" to payload.contentType,"finalUrl" to payload.finalUrl,"bytes" to payload.text.toByteArray().size,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-start)
                }catch(e:CancellationException){currentCoroutineContext().ensureActive();if(e !is TimeoutCancellationException)throw e;mapOf("root" to root,"error" to "Timed out","elapsedMs" to android.os.SystemClock.elapsedRealtime()-start)}
                catch(e:Exception){mapOf("root" to root,"error" to e.javaClass.simpleName,"detail" to e.message,"status" to (e as? SourceFailure)?.httpStatus,"retryAfterMs" to (e as? SourceFailure)?.retryAfterMs,"elapsedMs" to android.os.SystemClock.elapsedRealtime()-start)}
                reports+=result;File(dir,"results.json").writeText(Gson().toJson(reports))
            }
        }finally {repo.prefs.restore(JSONObject(before));repo.applyNetworkSettings();repo.localSearchConnection(false)}
    }
}
