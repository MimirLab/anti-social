package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Opt-in real-source qualification, separate from repeatable native fixtures. */
@RunWith(AndroidJUnit4::class)
class LocalOpportunitiesLiveTest {
    private fun repo():Repository {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLocalOpportunitiesLive")=="true")
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        return (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AntiSocialApplication).repo
    }
    private suspend fun check(name:String,action:suspend (Repository,PublicLocalDiscoveryBackend)->Any) {
        val repo=repo();repo.awaitReady();val old=repo.prefs.export().toString()
        try {
            repo.prefs.tor=false;repo.prefs.originalFallback=true;repo.prefs.intervalMinutes=0;repo.applyNetworkSettings()
            val start=System.currentTimeMillis();val data=action(repo,PublicLocalDiscoveryBackend(repo))
            val report=mapOf("elapsedMs" to System.currentTimeMillis()-start,"data" to data)
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"local-opportunities-$name.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        }catch(e:Exception) {
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"local-opportunities-$name-failure.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf("type" to e.javaClass.simpleName,"message" to e.message,"httpStatus" to (e as? SourceFailure)?.httpStatus,"health" to (e as? SourceFailure)?.status?.name)))
            throw e
        }finally {repo.prefs.restore(JSONObject(old));repo.localSearchConnection(false);repo.applyNetworkSettings()}
    }
    @Test fun livewhaleReturnsRealDatesAndLocalVenuesThroughTheProductionGateway():Unit=runBlocking {
        check("livewhale") {_,backend->
            val area=LocalArea("public-chicago","Chicago","Chicago, Illinois, United States",LocalPoint(41.7897,-87.5997),10,"America/Chicago",countryCode="US")
            val query=LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS,window=LocalEventWindow.MONTH)
            val data=withTimeout(45_000){backend.read(LocalPublisherSource("https://events.uchicago.edu/live/json/events",LocalSourceKind.LIVEWHALE),query,null)}
            Assert.assertTrue("A public calendar should supply dated records",data.events.isNotEmpty())
            Assert.assertTrue(data.events.all {it.start.valid()&&it.evidence.isNotEmpty()})
            val local=data.events.filter {LocalDiscoveryFilter.event(query,it)}
            Assert.assertTrue("At least one supplied venue must be inside the selected radius",local.isNotEmpty())
            mapOf("records" to data.events.size,"local" to local,"notice" to data.notice)
        }
    }
    @Test fun spaceApiSuppliesAnActualLocalSpaceAndAdvertisedCalendar():Unit=runBlocking {
        check("spaceapi") {_,backend->
            val area=LocalArea("public-regensburg","Regensburg","Regensburg, Germany",LocalPoint(49.00985,12.11898),5,"Europe/Berlin",countryCode="DE")
            val data=withTimeout(35_000){backend.read(LocalPublisherSource("https://www.binary-kitchen.de/spaceapi.php",LocalSourceKind.SPACEAPI),LocalDiscoveryQuery(area),null)}
            Assert.assertEquals("Binary Kitchen",data.businesses.single().name)
            Assert.assertTrue(data.sources.any {it.kind==LocalSourceKind.CALENDAR})
            Assert.assertTrue(data.events.isEmpty());Assert.assertTrue(data.businesses.single().status.isBlank())
            data
        }
    }
    @Test fun helsinkiProviderUsesTorAndReturnsOnlyActualNearbyOccurrences():Unit=runBlocking {
        check("helsinki") {repo,backend->
            val area=LocalArea("public-helsinki","Helsinki","Helsinki, Finland",LocalPoint(60.1699,24.9384),10,"Europe/Helsinki",countryCode="FI")
            val query=LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS,window=LocalEventWindow.MONTH)
            val source=LocalOpportunityRouting.seeds(query).single()
            val data=withTimeout(90_000){backend.read(source,query,null)}
            Assert.assertTrue(repo.tor.state.value.ready)
            Assert.assertTrue(data.events.isNotEmpty())
            Assert.assertTrue(data.events.all {LocalDiscoveryFilter.geographic(area,it)})
            Assert.assertTrue(data.sources.isEmpty())
            data
        }
    }
    @Test fun familyCalendarBecomesExplicitNativeEventFieldsWithoutScriptsOrAI():Unit=runBlocking {
        check("family") {_,backend->
            val area=LocalArea("public-melbourne-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
            val query=LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS,window=LocalEventWindow.MONTH)
            val listing=withTimeout(40_000){backend.read(LocalPublisherSource("https://fun4spacecoastkids.com/calendar/"),query,null)}
            val offered=listing.sources.filter {it.url.contains("/calendar/eventdetail/")}.take(3)
            Assert.assertTrue("The current public calendar should offer actual event pages",offered.isNotEmpty())
            val rows=mutableListOf<LocalEvent>()
            for(source in offered)rows+=withTimeout(30_000){backend.read(source,query,null)}.events
            Assert.assertTrue("Explicit public When and Location fields should become event records",rows.isNotEmpty())
            Assert.assertTrue(rows.all {it.start.valid()&&it.address.isNotBlank()&&it.evidence.any {e->e.field=="start"}})
            mapOf("offered" to offered,"events" to rows,"alreadyLocal" to rows.count {LocalDiscoveryFilter.geographic(area,it)},"note" to "Neighboring towns without coordinates remain candidates until an exact venue match is available.")
        }
    }
    @Test fun exactNeighboringTownAddressResolvesThroughTorAndReusesItsCoordinateCache():Unit=runBlocking {
        check("venue") {repo,backend->
            val area=LocalArea("public-melbourne-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
            val query=LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS)
            // An explicit public-library address tests geocoding mechanics;
            // this fixture is not represented as a real event announcement.
            val fixture=LocalEvent("fixture-library-venue","Library venue fixture",LocalEventTime(area.today().plusDays(1).toString(),allDay=true),venue="Satellite Beach Library",address="751 Jamaica Boulevard, Satellite Beach, FL 32937")
            val resolved=withTimeout(90_000){backend.venue(query,fixture)}
            Assert.assertNotNull("The exact public library street address should resolve",resolved)
            Assert.assertTrue(LocalDiscoveryFilter.geographic(area,resolved!!))
            Assert.assertEquals("Satellite Beach",resolved.locality)
            val requests=repo.gateway.protectionStats().checked
            val cached=backend.venue(query,fixture)
            Assert.assertEquals(resolved.point,cached?.point)
            Assert.assertEquals("The second lookup must reuse cached coordinates",requests,repo.gateway.protectionStats().checked)
            mapOf("resolved" to resolved,"cachedPoint" to cached?.point,"fixtureOnly" to true)
        }
    }
}
