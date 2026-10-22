/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class MentionNameRoutingTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
    private fun routes(vararg roots: String): DarwinRoutes {
        val catalog=JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject
        catalog.add("sources",JsonParser.parseString(roots.joinToString(",","[","]"){"""{"platform":"twitter","frontend":"nitter","root":"$it","fallbackOnly":false}"""}))
        catalog.add("searchSources",JsonArray())
        return DarwinRoutes(catalog,cleaner)
    }
    private fun gateway()=NetworkGateway(cleaner,object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)},true)
    private fun metadata()="""<div class="profile-card"><h1>Nitter</h1><p class="profile-bio">A real profile panel without an account display name.</p></div>"""
    private fun named(name: String)="""<div class="profile-card"><a class="profile-card-fullname">$name</a><p class="profile-bio">Public account biography.</p></div>"""

    @Test fun metadataOnlyFirstProfileCannotWinNameLookupAndLaterPublicNameDoes(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {second->
            first.start();second.start()
            first.enqueue(MockResponse().setBody(metadata()));second.enqueue(MockResponse().setBody(named("National Aeronautics and Space Administration")))
            val routes=routes(first.url("/").toString().trimEnd('/'),second.url("/").toString().trimEnd('/'))
            val pool=SourcePool(gateway(),routes)
            val page=pool.load(routes.target(Platform.X,"NASA"),originalFallback=false,requestedCapability=Capability.PROFILE,background=true,profileNameOnly=true)
            assertEquals("National Aeronautics and Space Administration",page.profile!!.name)
            assertTrue(page.profile!!.displayNameSupplied)
            assertEquals(1,first.requestCount);assertEquals(1,second.requestCount)
            // The first source still exposed valid profile metadata. A missing
            // display-name field must not falsely mark that ordinary route down.
            assertEquals(HealthStatus.HEALTHY,pool.results(Platform.X).first {it.source==routes.sources.first()}.status)
            assertFalse(pool.hasActiveReads)
        }}
    }

    @Test fun ordinaryProfileReadsStillAcceptFirstValidMetadataWithoutConsultingBackup(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {second->
            first.start();second.start();first.enqueue(MockResponse().setBody(metadata()))
            val routes=routes(first.url("/").toString().trimEnd('/'),second.url("/").toString().trimEnd('/'))
            val pool=SourcePool(gateway(),routes)
            val page=pool.load(routes.target(Platform.X,"NASA"),originalFallback=false,requestedCapability=Capability.PROFILE)
            assertEquals("Nitter",page.profile!!.name);assertFalse(page.profile!!.displayNameSupplied)
            assertEquals(1,first.requestCount);assertEquals(0,second.requestCount)
            assertEquals(HealthStatus.HEALTHY,pool.results(Platform.X).single().status)
        }}
    }

    @Test fun missingNameDoesNotEscapeAsPartialSuccessfulLookup(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(metadata()))
            val routes=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes)
            assertThrows(SourceFailure::class.java){runBlocking {pool.load(routes.target(Platform.X,"NASA"),originalFallback=false,requestedCapability=Capability.PROFILE,background=true,profileNameOnly=true)}}
            assertEquals(1,server.requestCount);assertFalse(pool.hasActiveReads)
            assertEquals(HealthStatus.HEALTHY,pool.results(Platform.X).single().status)
        }
    }

    @Test fun unusableSuppliedNameIsSkippedForLaterSanitizedPublicName(): Unit=runBlocking {
        MockWebServer().use {first->MockWebServer().use {second->
            first.start();second.start();first.enqueue(MockResponse().setBody(named("https://example.org")));second.enqueue(MockResponse().setBody(named("NASA")))
            val routes=routes(first.url("/").toString().trimEnd('/'),second.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes)
            assertEquals("NASA",pool.load(routes.target(Platform.X,"NASA"),originalFallback=false,requestedCapability=Capability.PROFILE,profileNameOnly=true).profile!!.name)
            assertEquals(1,first.requestCount);assertEquals(1,second.requestCount)
        }}
    }

    @Suppress("UNCHECKED_CAST")
    private fun inject(pool: SourcePool,source: Source,returnedProfile: Profile) {
        val readers=SourcePool::class.java.getDeclaredField("readers").apply {isAccessible=true}.get(pool) as ConcurrentHashMap<Source,DataAdapter>
        val fixtureSource=source
        readers[source]=object: DataAdapter {
            override val source=fixtureSource
            override val capabilities=setOf(Capability.PROFILE)
            override suspend fun fetch(target: Target,cursor: String?)=Page(emptyList(),profile=returnedProfile,provenance=Provenance(source,1,source.root))
            override suspend fun feed(target: Target)=fetch(target)
            override fun sentinel(capability: Capability)=returnedProfile.target
        }
    }

    @Test fun explicitNameForADifferentAccountOrPlatformCannotSatisfyLookup(): Unit=runBlocking {
        val routes=routes("https://reader.example.org");val requested=routes.target(Platform.X,"NASA")
        for(wrong in listOf(routes.target(Platform.X,"POTUS"),routes.target(Platform.INSTAGRAM,"NASA"))) {
            val pool=SourcePool(gateway(),routes)
            inject(pool,routes.sources.single(),Profile(wrong,"A different public account",displayNameSupplied=true))
            assertThrows(SourceFailure::class.java){runBlocking {pool.load(requested,originalFallback=false,requestedCapability=Capability.PROFILE,profileNameOnly=true)}}
            assertFalse(pool.hasActiveReads)
        }
    }

    @Test fun nameOnlyRequiresProfileCapabilityBeforeAnyNetworkRead(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val routes=routes(server.url("/").toString().trimEnd('/'));val pool=SourcePool(gateway(),routes)
            assertThrows(IllegalArgumentException::class.java){runBlocking {pool.load(routes.target(Platform.X,"NASA"),originalFallback=false,requestedCapability=Capability.TIMELINE,profileNameOnly=true)}}
            assertEquals(0,server.requestCount);assertFalse(pool.hasActiveReads)
        }
    }
}
