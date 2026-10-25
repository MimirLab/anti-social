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

@RunWith(AndroidJUnit4::class)
class LocalEnrichmentLiveTest {
    private fun repo():Repository {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLocalEnrichmentLive")=="true")
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        return (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AntiSocialApplication).repo
    }
    private fun evidence(name:String,value:Any) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir,"local-enrichment-$name.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(value))
    }
    @Test fun memberContactBlockRetainsLocalAccountsHoursAndAddressThroughGatewayWashing()=runBlocking {
        val repo=repo();repo.awaitReady()
        val area=LocalArea("public-vero","Vero Beach","Vero Beach, Florida, United States",LocalPoint(27.6386,-80.3973),10,"America/New_York",countryCode="US")
        val source=LocalPublisherSource("https://business.indianriverchamber.com/list/member/coffee-house-1420-5222")
        val data=withTimeout(40_000){PublicLocalDiscoveryBackend(repo).read(source,LocalDiscoveryQuery(area),null)}
        evidence("member-accounts",data)
        val b=data.businesses.single {it.name=="Coffee House 1420"}
        Assert.assertTrue(b.address.contains("2001 14th Ave"));Assert.assertTrue(b.hours.contains("7:00am"))
        Assert.assertEquals(setOf("https://www.facebook.com/coffeehouse1420vero","https://www.instagram.com/coffeehouse1420"),b.socials.toSet())
        Assert.assertTrue(data.sources.any {it.kind==LocalSourceKind.SOCIAL&&it.businessId==b.id})
    }
    @Test fun publishedAllThePlacesBrandSliceEnrichesMelbourneWithoutAGlobalDownload()=runBlocking {
        val repo=repo();repo.awaitReady();val prefs=repo.prefs.export().toString()
        val area=LocalArea("public-melbourne-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
        val owner=LocalBusiness("public-sky-zone","Sky Zone",address="750 Bass Pro Dr NE",locality="Palm Bay",point=LocalPoint(28.0334911,-80.6527693))
        val rows=mutableListOf<LocalBusiness>()
        try {
            repo.prefs.intervalMinutes=0
            val backend=PublicLocalDiscoveryBackend(repo)
            withContext(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
            withTimeout(100_000){backend.enrich(LocalDiscoveryQuery(area,"sky zone"),listOf(owner)){data->rows+=data.businesses;repo.localDiscovery.ingest(data,area)}}
            evidence("places",mapOf("status" to if(rows.isNotEmpty())"verified"else "no data","businesses" to rows,"maxSliceBytes" to LocalAllThePlaces.MAX_BYTES))
            Assert.assertTrue("The published Sky Zone slice should include the Palm Bay branch",rows.any {LocalDiscoveryMerge.sameBusiness(owner,it)})
            Assert.assertTrue(rows.all {it.point?.let {p->area.point.distanceKm(p)<=25} ?: true})
            Assert.assertTrue(rows.any {it.emails.orEmpty().isNotEmpty()||it.phones.isNotEmpty()})
            var repeated=0
            backend.enrich(LocalDiscoveryQuery(area,"sky zone"),listOf(owner)){repeated++}
            Assert.assertEquals("A retained slice must not be redownloaded for a second callback",0,repeated)
        }finally {repo.prefs.restore(JSONObject(prefs));repo.localSearchConnection(false)}
    }
    @Test fun advertisedLocalistCalendarBecomesNativeDatedEventsThroughPassiveReads()=runBlocking {
        val repo=repo();repo.awaitReady()
        val area=LocalArea("public-tallahassee","Tallahassee","Tallahassee, Florida, United States",LocalPoint(30.438,-84.281),25,"America/New_York",countryCode="US")
        val query=LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS)
        val backend=PublicLocalDiscoveryBackend(repo)
        val (advertised,data)=withTimeout(90_000) {
            val first=backend.read(LocalPublisherSource("https://calendar.fsu.edu/"),query,null)
            val source=first.sources.first {it.kind==LocalSourceKind.LOCALIST}
            source to backend.read(source,query,null)
        }
        val local=data.events.filter {LocalDiscoveryFilter.event(query,it)}
        evidence("calendar",mapOf("status" to if(local.isNotEmpty())"verified"else "no data","advertisedSource" to advertised,"events" to local,"next" to data.sources))
        Assert.assertTrue("The public calendar must return actual local dated occurrences",local.isNotEmpty())
        Assert.assertTrue(local.all {it.start.valid()&&it.evidence.isNotEmpty()})
        Assert.assertTrue(local.all {it.sourceIds.any {id->id.startsWith("localist:")}})
    }
    @Test fun publicEventPagesAndThePublishedCalendarProduceOneCardPerOccurrence()=runBlocking {
        val repo=repo();repo.awaitReady()
        val area=LocalArea("public-melbourne-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
        val backend=PublicLocalDiscoveryBackend(repo);val query=LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS,window=LocalEventWindow.MONTH)
        val source="https://www.visitspacecoast.com/"
        val calendar=withTimeout(45_000){backend.read(LocalPublisherSource(source+"events-calendar/?ical=1",LocalSourceKind.CALENDAR),query,null)}
        val rows=calendar.events.toMutableList()
        val titles=listOf("Downtown Melbourne Trick or Treat","Halloween Family Fun Night at Intracoastal Brewing Co.")
        for(path in listOf("downtown-melbourne-trick-or-treat","halloween-family-fun-night-at-intracoastal-brewing-co"))
            rows+=withTimeout(30_000){backend.read(LocalPublisherSource(source+"event/$path/"),query,null)}.events
        val merged=LocalDiscoveryMerge.events(rows)
        evidence("event-reconciliation",mapOf("before" to rows.size,"after" to merged.size,"events" to merged.filter {it.title in titles}))
        titles.forEach {title->Assert.assertEquals(title,1,merged.count {it.title==title});Assert.assertTrue(merged.single {it.title==title}.sourceIds.size>=2)}
    }
    @Test fun publisherSocialConnectionsSurviveWashingAndPublicRoutesAreMeasured()=runBlocking {
        val repo=repo();repo.awaitReady()
        val area=LocalArea("public-melbourne-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
        val backend=PublicLocalDiscoveryBackend(repo);val query=LocalDiscoveryQuery(area)
        val owner=LocalBusiness("public-intracoastal","Intracoastal Brewing Company",address="652 W Eau Gallie Blvd, Melbourne, FL 32935",locality="Melbourne",websites=listOf("https://www.intracoastalbrewingcompany.com/"))
        val data=withTimeout(35_000){backend.read(LocalPublisherSource(owner.websites.single(),businessId=owner.id),query,owner)}
        val accounts=data.businesses.flatMap {it.socials}.distinct()
        Assert.assertTrue("Published Facebook connection was lost",accounts.any {it.contains("facebook.com/intracoastalbrewingcompany",true)})
        Assert.assertTrue("Published Instagram connection was lost",accounts.any {it.contains("instagram.com/intracoastalbrewingcompany",true)})
        val availability=coroutineScope {accounts.take(2).map {url->async {
            val result=try {withTimeout(25_000){mapOf("url" to url,"usable" to backend.account(url))}}
                catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();mapOf("url" to url,"usable" to false,"reason" to "timed out")}
                catch(e:CancellationException){throw e}
                catch(e:Exception){mapOf("url" to url,"usable" to false,"reason" to (e.message ?: "unavailable"))}
            result
        }}.awaitAll()}
        evidence("social-connections",mapOf("businesses" to data.businesses,"availability" to availability,"note" to "Identity extraction is asserted; public profile availability is measured, never invented."))
    }
}
