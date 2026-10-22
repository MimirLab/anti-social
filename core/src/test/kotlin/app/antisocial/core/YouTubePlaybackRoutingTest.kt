/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YouTubePlaybackRoutingTest {
    @Test fun aCoolingVideoMetadataApiIsNotImmediatelyRetriedAsANewMediaRoute(): Unit=runBlocking {
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
        var requests=0
        val gateway=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {requests++;throw java.io.IOException("No socket is allowed in this fixture")}})
        val now=System.currentTimeMillis();val pool=SourcePool(gateway,routes,clock={now})
        val target=YouTubeUrls.recognize(YouTubeUrls.canonical("jNQXAC9IVRw"))!!
        for(source in routes.sources.filter {it.platform==Platform.YOUTUBE})pool.learning.observe(ProbeResult(source,Capability.POST,HealthStatus.RATE_LIMITED,1,now,false,"Rate limited",retryAfterMs=600_000),target)
        assertThrows(Exception::class.java){runBlocking {pool.youtubePlayback("jNQXAC9IVRw",originalFallback=false)}}
        assertEquals("The media route needs the same cooling API document",0,requests)
    }
}
