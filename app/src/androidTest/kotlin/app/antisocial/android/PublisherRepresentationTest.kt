/* anti social, GPL-3.0-or-later. Real Gateway reads from owned sources only. */
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PublisherRepresentationTest {
    private val row="""{"title":"Public workshop","url":"https://publisher.fixture.invalid/calendar"}"""
    private fun isolated()=assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
    private fun report(name:String,value:Any) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir("validation"),"publisher-representation").apply {mkdirs()}
        File(dir,"$name.json").writeText(com.google.gson.Gson().toJson(value))
    }
    @Test fun anInitialMarkerRetainsStructuredLinksAndTheRawGatewayResponse():Unit=runBlocking {marked(false)}
    @Test fun anInitialMarkerRetainsIndependentLinksAndTheRawGatewayResponse():Unit=runBlocking {marked(true)}
    private suspend fun marked(fourget:Boolean) {
        isolated()
        val body="\uFEFF \t\r\n"+if(fourget)"{\"status\":\"ok\",\"web\":[$row]}"else "{\"0\":$row}"
        LocalIndexNativeFixture("",fourget=fourget,firstBody="[]",quickBody=body).use {f->
            val received=CopyOnWriteArrayList<Payload>();f.gateway.onTextResponse={received+=it}
            val hits=withTimeout(4000){f.engine.search("first workshop",SearchProvider.DARWIN,false)}
            assertEquals("https://publisher.fixture.invalid/calendar",hits.single().url)
            val prefix=if(fourget)"api/v1/web?"else "api.php?"
            val requests=f.requests.filter {it.startsWith("quick-index.fixture.invalid/")}
            report(if(fourget)"marked-independent"else "marked-structured",mapOf("requests" to requests,"rawResponsePreserved" to received.any {it.text==body},"links" to hits))
            assertEquals(1,requests.size);assertTrue(requests.single().startsWith("quick-index.fixture.invalid/$prefix"))
            assertTrue(received.any {it.text==body})
            assertTrue(f.requests.size<=3)
        }
    }
    @Test fun aBlankReplyKeepsHtmlFallbackWithoutDisablingTheNextApiRead():Unit=runBlocking {
        isolated()
        for(noContent in listOf(true,false)) {
            val later=AtomicBoolean(false)
            LocalIndexNativeFixture("",quickReply={path->
                if(path.startsWith("/api.php")) {
                    if(later.get())200 to "{\"0\":$row}"else if(noContent)204 to ""else 200 to " \t\r\n"
                }else 200 to if(later.get())"<p>No results</p>"else """<div class="text-result-wrapper"><a href="https://publisher.fixture.invalid/calendar"><h2>Public workshop</h2></a></div>"""
            }).use {f->
                assertEquals(1,withTimeout(4000){f.engine.search("first workshop",SearchProvider.DARWIN,false)}.size)
                later.set(true)
                assertEquals(1,withTimeout(4000){f.engine.search("next workshop",SearchProvider.DARWIN,false)}.size)
                val quick=f.requests.filter {it.startsWith("quick-index.fixture.invalid/")}
                report(if(noContent)"no-content"else "whitespace",mapOf("requests" to quick,"nextApiRecovered" to true))
                assertEquals(listOf("api.php","search.php","api.php"),quick.map {it.substringAfter('/').substringBefore('?')})
            }
        }
    }
    @Test fun aMarkerCannotTurnMalformedJsonOrAChallengeIntoWorkingContent() {
        isolated()
        for(frontend in listOf("librey","4get")) {
            for(body in listOf("\uFEFF[{","\uFEFF[] trailing")) {
                try {LocalWebDiscovery.parse(body,"https://index.fixture.invalid/",frontend);fail("Malformed JSON must remain unsupported")}
                catch(e:SourceFailure){assertEquals(HealthStatus.UNSUPPORTED,e.status)}
            }
            try {LocalWebDiscovery.parse("\uFEFF<form id='challenge-form'>Verify you are human</form>","https://index.fixture.invalid/",frontend);fail("Challenge must remain a refusal")}
            catch(e:SourceFailure){assertEquals(HealthStatus.CHALLENGE,e.status)}
        }
    }
}
