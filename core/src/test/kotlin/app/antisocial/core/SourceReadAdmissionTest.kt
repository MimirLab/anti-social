/* anti social, GPL-3.0-or-later. Request admission only; filter denies all network destinations. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SourceReadAdmissionTest(private val operation:String) {
    companion object {@JvmStatic @Parameterized.Parameters(name="{0}")fun operations()=listOf("initial","continuation","directory")}
    private class Fixture:AutoCloseable {
        private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(true,"Fixture denies all outbound traffic")},true)
        private val client=gateway.client()
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {add("sources",JsonArray());add("searchSources",JsonArray())},cleaner)
        val pool=SourcePool(gateway,routes)
        val target=routes.search(Platform.X,"nasa")
        val source=pool.originalSource(Platform.X,Capability.SEARCH)
        init {gateway.configureTor(NetworkPolicy(true,9,true))}
        fun pause(){pool.learning.observe(ProbeResult(source,Capability.SEARCH,HealthStatus.RATE_LIMITED,1,System.currentTimeMillis(),true,"Provider asked to pause",120_000),delivery=false)}
        suspend fun read(operation:String):Page=when(operation) {
            "initial"->pool.load(target)
            "directory"->pool.searchDirectory(target)
            else->pool.continuePage(target,Page(emptyList(),provenance=Provenance(source,0,source.root),next="search-form:"+Gson().toJson(mapOf("url" to source.root+"/html/","fields" to mapOf("q" to PublicSearchAdapter.query(target),"s" to "10")))))
        }
        override fun close(){client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
    }
    @Test fun aPauseLearnedWhileWaitingStopsBeforeRequestAdmission():Unit=runBlocking {
        Fixture().use {f->
            f.pool.awaitSearchConnection={f.pause()}
            val result=runCatching {withTimeout(1_500){f.read(operation)}}
            assertFalse(result.exceptionOrNull() is TimeoutCancellationException)
            assertEquals("$operation must recheck the pause before the Gateway",0L,f.gateway.protectionStats().checked)
            assertEquals(1,f.pool.learning.get(f.source,Capability.SEARCH,true)!!.samples)
            assertTrue(f.pool.results(Platform.X).isEmpty())
            if(operation!="directory")assertTrue((result.exceptionOrNull() as? SourceFailure)?.retryAfterMs?.let {it>110_000}==true)
        }
    }
    @Test fun aConnectionRoundTripWhileWaitingCannotStartQueuedSearchWork():Unit=runBlocking {
        Fixture().use {f->
            f.pool.awaitSearchConnection={f.gateway.configure(f.gateway.currentPolicy(NetworkRoute.TOR));f.gateway.configure(NetworkPolicy())}
            val result=runCatching {withTimeout(1_500){f.read(operation)}}
            assertFalse(result.exceptionOrNull() is TimeoutCancellationException)
            assertEquals("$operation must retain the user's connection selection",0L,f.gateway.protectionStats().checked)
            assertTrue(f.pool.results(Platform.X).isEmpty());assertTrue(f.pool.learning.snapshot().isEmpty())
            assertTrue(result.exceptionOrNull() is CancellationException||operation=="directory"&&result.getOrNull()?.accounts?.isEmpty()==true)
        }
    }
}
