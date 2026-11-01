/* anti social, GPL-3.0-or-later. Native refusal semantics over owned SOCKS. */
package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PublisherIndexRefusalTest {
    @Test fun aProofOfWorkPagePausesTheLibreYEndpointWithoutAnHtmlRetry():Unit=runBlocking {checkRefusal(false)}
    @Test fun aProofOfWorkPagePausesTheIndependentEndpointWithoutPoisoningItsPeer():Unit=runBlocking {checkRefusal(true)}

    private suspend fun checkRefusal(fourget:Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val gate="""<h1>Making sure you're not a bot!</h1><script id="anubis-main" type="module" src="/.within.website/x/cmd/anubis/static/js/main.mjs"></script>"""
        val publicApi="""{"0":{"title":"Public workshop","url":"https://publisher.fixture.invalid/calendar"}}"""
        LocalIndexNativeFixture("",fourget,firstBody=if(fourget)publicApi else gate,quickBody=if(fourget)gate else null).use {f->
            var now=System.currentTimeMillis()
            val learning=AdaptiveRouting {now}
            val challenged=f.routes.publisherSearchSources.single {it.root.contains(if(fourget)"quick-index"else "first-index")}
            val peer=f.routes.publisherSearchSources.single {it!=challenged}
            learning.observe(ProbeResult(challenged,Capability.SEARCH,HealthStatus.HEALTHY,1,now,true,"Controlled prior delivery"))
            val engine=LocalWebDiscovery(f.gateway,f.routes,learning,clock={now})
            val hits=withTimeout(3500){engine.search("community workshop",SearchProvider.DARWIN,false)}
            assertEquals("https://publisher.fixture.invalid/calendar",hits.single().url)
            assertEquals(2,f.requests.size)
            assertTrue(f.requests.first().startsWith(java.net.URI(challenged.root).host))
            assertFalse(f.requests.any {it.contains("/search.php")})
            val until=now+60_000
            assertEquals(until,learning.eligibleAt(challenged,Capability.SEARCH,true))
            assertFalse(learning.eligible(challenged.copy(platform=Platform.INSTAGRAM),Capability.SEARCH,true))
            assertTrue(learning.eligible(peer,Capability.SEARCH,true))
            assertEquals(1,learning.get(peer,Capability.SEARCH,true)!!.successes)
            now+=1000
            assertEquals(1,withTimeout(3500){engine.search("another workshop",SearchProvider.DARWIN,false)}.size)
            assertEquals(3,f.requests.size)
            assertEquals(1,f.requests.count {it.startsWith(java.net.URI(challenged.root).host)})
            assertEquals(until,learning.eligibleAt(challenged,Capability.SEARCH,true))
            assertEquals(2,learning.get(challenged,Capability.SEARCH,true)!!.samples)
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val dir=File(context.getExternalFilesDir("validation"),"publisher-index-refusals").apply {mkdirs()}
            File(dir,if(fourget)"fourget.json"else "librey.json").writeText(com.google.gson.Gson().toJson(mapOf(
                "protocol" to if(fourget)"4get"else "librey","requests" to f.requests.toList(),
                "unchangedCooldown" to true,"noHtmlRetry" to true,"peerUsable" to true,
                "scope" to "Native production parser/router over owned SOCKS; no live provider or phone latency claim.")))
        }
    }
}
