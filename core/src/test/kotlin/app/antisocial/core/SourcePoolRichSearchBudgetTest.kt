package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** An injected delayed delivery isolates routing deadlines from volatile providers. */
class SourcePoolRichSearchBudgetTest {
    private fun check(platform:Platform,expected:Boolean)=runBlocking {
        val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
        val config=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject.apply {add("sources",JsonArray());add("searchSources",JsonArray())}
        val routes=DarwinRoutes(config,cleaner)
        val network=AtomicInteger();val calls=AtomicInteger()
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String):FilterDecision {network.incrementAndGet();error("The deterministic timing fixture must not use the network")}})
        val pool=SourcePool(gateway,routes);val source=pool.originalSource(platform,Capability.SEARCH);val target=routes.search(platform,"battery")
        val post=Post("delayed",platform,if(platform==Platform.THREADS)"https://www.threads.com/@workshop/post/DelayedCode"else "https://www.reddit.com/r/repair/comments/abc123/delayed/","Workshop","A complete delayed native search result.",publishedAt=1792170638000)
        val reader=object:DataAdapter {
            override val source=source
            override val capabilities=setOf(Capability.SEARCH)
            override suspend fun fetch(target:Target,cursor:String?):Page {calls.incrementAndGet();delay(4500);return Page(listOf(post),provenance=Provenance(source,System.currentTimeMillis(),target.canonicalUrl))}
            override suspend fun feed(target:Target)=error("Not a feed")
            override fun sentinel(capability:Capability)=target
        }
        @Suppress("UNCHECKED_CAST")
        val readers=SourcePool::class.java.getDeclaredField("readers").apply {isAccessible=true}.get(pool) as ConcurrentHashMap<Source,DataAdapter>
        readers[source]=reader
        val client=gateway.client()
        try {
            val result=runCatching {pool.load(target,originalFallback=true)}
            if(expected) {
                assertEquals(listOf(post),result.getOrThrow().posts)
                assertEquals(HealthStatus.HEALTHY,pool.results(platform).single {it.source==source}.status)
            }else {
                assertTrue(result.isFailure)
                assertEquals("Timed out",pool.results(platform).single {it.source==source}.detail)
            }
            assertEquals(1,calls.get());assertEquals(0,network.get())
        }finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
    }
    @Test fun aUsableBulkThreadsSearchIsNotDiscardedAtTheLegacyFourSecondShortcut()=check(Platform.THREADS,true)
    @Test fun otherOriginalShortcutsRetainTheirExistingDeadline()=check(Platform.REDDIT,false)
}
