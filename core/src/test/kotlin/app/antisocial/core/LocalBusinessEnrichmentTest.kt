package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Test
import kotlin.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class LocalBusinessEnrichmentTest {
    private val at=1792600000000L
    private val area=LocalArea("fl","Sampleton","Sampleton, Florida, United States",LocalPoint(28.612218,-80.807555),25,"America/New_York",countryCode="US")
    private val owner=LocalBusiness("coffee","Example Coffee",address="2112A Example Rd, Sampleton",locality="Sampleton",point=area.point,websites=listOf("https://examplecoffee.test/"))
    @Test fun routesPreferTheMissingFieldAndLearnWhatAPathActuallySupplies() {
        val contact=LocalPublisherSource("https://examplecoffee.test/contact",businessId=owner.id)
        val menu=contact.copy(url="https://examplecoffee.test/menu")
        val completeVisit=owner.copy(evidence=listOf(LocalDiscoveryIdentity.evidence("menu",menu.url,menu.url,at,LocalEvidenceKind.PUBLISHER)))
        assertTrue(LocalBusinessEnrichment.priority(contact,completeVisit,null)<LocalBusinessEnrichment.priority(menu,completeVisit,null))
        val phoneOnly=LocalRouteYield(fields=listOf("phone"))
        val socialOnly=LocalRouteYield(fields=listOf("accounts"))
        assertTrue(LocalBusinessEnrichment.priority(contact,owner,phoneOnly)<LocalBusinessEnrichment.priority(contact,owner,socialOnly))
        assertTrue(LocalBusinessEnrichment.query(owner,area)!!.contains("contact phone"))
    }
    @Test fun stableObservationsLengthenRestAndChangedFactsRestoreResponsiveness() {
        val data=LocalExtraction(businesses=listOf(owner.copy(phones=listOf("2025550101"))))
        val first=LocalBusinessEnrichment.observe(null,data,150,at)
        val immediate=LocalBusinessEnrichment.observe(first,data,100,at+10)
        assertEquals(0,immediate.stable)
        val second=LocalBusinessEnrichment.observe(first,data.copy(businesses=data.businesses.map {it.copy(checkedAt=at+86400000)}),100,at+86400000)
        assertTrue(second.nextAt-second.checkedAt>first.nextAt-first.checkedAt)
        val changed=LocalBusinessEnrichment.observe(second,LocalExtraction(businesses=listOf(owner.copy(phones=listOf("2025550100")))),100,at+2*86400000)
        assertEquals(0,changed.stable)
        var failed=first
        repeat(30){failed=LocalBusinessEnrichment.failed(failed,at)}
        assertEquals(86400000L,failed.nextAt-at)
    }
    @Test fun timestampAndTrailingSlashDoNotManufactureNewAccountInformation() {
        val a=owner.copy(socials=listOf("https://www.facebook.com/example/"),checkedAt=at)
        assertEquals(LocalBusinessEnrichment.snapshot(a),LocalBusinessEnrichment.snapshot(a.copy(socials=listOf("https://www.facebook.com/example"),checkedAt=at+1)))
    }
    @Test fun schemaSuppliesVisitFactsAndKeepsExplicitNegativeAccessibility() {
        val o=JsonParser.parseString("""{"hasMenu":"/menu.pdf","acceptsReservations":true,"potentialAction":{"@type":"ReserveAction","target":{"urlTemplate":"https://booking.test/table"}},"servesCuisine":["Indian","Vegetarian"],"priceRange":"$$","paymentAccepted":"Cash","amenityFeature":{"name":"Wheelchair entrance","value":false},"availableService":{"name":"Coffee roasting"},"specialOpeningHoursSpecification":{"validFrom":"2026-12-25","opens":"00:00","closes":"00:00"}}""").asJsonObject
        val facts=LocalBusinessFacts.schema(o,"https://examplecoffee.test/",at,LocalEvidenceKind.PUBLISHER)
        assertEquals("https://examplecoffee.test/menu.pdf",facts.single {it.field=="menu"}.value)
        assertEquals("https://booking.test/table",facts.single {it.field=="booking"}.value)
        assertEquals("Wheelchair entrance: No",facts.single {it.field=="accessibility"}.value)
        assertTrue(facts.single {it.field=="special hours"}.value.contains("2026-12-25"))
        assertTrue(facts.all {it.sourceUrl=="https://examplecoffee.test/"})
        assertTrue(LocalBusinessFacts.schema(JsonParser.parseString("""{"acceptsReservations":"yes"}""").asJsonObject,"https://examplecoffee.test/",at,LocalEvidenceKind.PUBLISHER).isEmpty())
    }
    @Test fun contactExtractionCannotAttachSiblingBranchDataOrDirectoryFooterData() {
        val html="""<meta property='og:site_name' content='Example Coffee'><a href='tel:2025550101'>Call</a><a href='mailto:hello@examplecoffee.test'>Email</a><a href='/menu'>Menu</a>"""
        val updated=LocalBusinessFacts.html(html,"https://examplecoffee.test/contact",at,owner,emptyList())!!
        assertEquals(listOf("2025550101"),updated.phones);assertEquals(listOf("hello@examplecoffee.test"),updated.emails)
        assertTrue(LocalBusinessDetails.facts(updated).any {it.field=="menu"})
        assertNull(LocalBusinessFacts.html(html.replace("Example Coffee","Regional Directory"),"https://directory.test/coffee",at,owner,emptyList()))
        assertNull(LocalBusinessFacts.html(html,"https://examplecoffee.test/contact",at,owner,listOf(owner.copy(id="branch",address="4980 N Meadow Rd"))))
    }
    @Test fun preferredEvidenceRetainsUsefulFieldsWhenManyNewCopiesArrive() {
        val menu=LocalDiscoveryIdentity.evidence("menu","https://examplecoffee.test/menu","https://examplecoffee.test/",at,LocalEvidenceKind.PUBLISHER)
        val old=owner.copy(evidence=listOf(menu))
        val noisy=owner.copy(evidence=(1..120).map {LocalDiscoveryIdentity.evidence("name","Example Coffee $it","https://directory.test/$it",at+it,LocalEvidenceKind.DIRECTORY)})
        assertTrue(LocalDiscoveryMerge.business(old,noisy).evidence.any {it==menu})
    }
    @Test fun publishedRunAndBrandSelectionDoNotDownloadTheGlobalBundleOrForeignSlices() {
        val root=LocalAllThePlaces.root("<a href='https://alltheplaces-data.openaddresses.io/runs/2026-10-10-13-32-41/output.zip'>Download</a>")!!
        assertEquals("https://alltheplaces-data.openaddresses.io/runs/2026-10-10-13-32-41/",root)
        assertNull(LocalAllThePlaces.root("<a href='https://other.test/output.zip'>Download</a>"))
        val cat=LocalAllThePlaces.catalog("""{"results":[{"spider":"sky_zone","features":301},{"spider":"chipotle","features":4298},{"spider":"taco_bell_ca","features":204},{"spider":"taco_bell_us","features":8280},{"spider":"../../../bad","features":1}]}""")
        val selected=LocalAllThePlaces.select(cat,listOf(owner.copy(name="Sky Zone Trampoline Park · Mapleford - Sampleton"),owner.copy(name="Chipotle Mexican Grill"),owner.copy(name="Taco Bell")),area)
        assertEquals(listOf("sky_zone","chipotle"),selected.map {it.spider})
        assertTrue(LocalAllThePlaces.select(cat,listOf(owner.copy(name="Coffee")),area).isEmpty())
    }
    @Test fun allThePlacesRetainsBranchContactsOperatorAndUpstreamLineage() {
        val feature="""{"type":"FeatureCollection","features":[{"id":"abc","properties":{"@spider":"sky_zone","@source_uri":"https://skyzone.test/stores","ref":"33173","name":"Sky Zone","operator":"Example Operator","addr:street_address":"750 Sample Park Dr NE","addr:city":"Mapleford","addr:country":"US","phone":"+1 202-555-0109","email":"palm_bay@skyzone.test","website":"https://skyzone.test/mapleford/"},"geometry":{"type":"Point","coordinates":[-80.8522143,28.5620791]}},{"properties":{"@spider":"sky_zone","ref":"other","name":"Sky Zone","addr:city":"Sampleton","addr:country":"AU"},"geometry":{"type":"Point","coordinates":[144.96,-37.81]}}]}"""
        val result=LocalAllThePlaces.parse(feature,"https://alltheplaces-data.openaddresses.io/runs/example/output/sky_zone.geojson",at,area)
        val b=result.businesses.single();assertEquals("Mapleford",b.locality);assertEquals("palm_bay@skyzone.test",b.emails!!.single())
        assertEquals("operator",b.related!!.single().role);assertTrue(b.evidence.all {it.license!!.contains("CC0")&&it.license!!.contains("skyzone.test/stores")})
        assertTrue(b.sourceIds.single().startsWith("atp:"))
    }
    @Test fun calendarEmbedsAreRecognizedWithoutRunningScriptsOrGuessingTenantNames() {
        val body="""<script src='https://www.trumba.com/scripts/spuds.js'></script><script>Trumba.addSpud({webName:'community_library',spudType:'main'});fetch('https://evil.test')</script>"""
        val sources=LocalCalendarPlatforms.sources(body,"https://library.test/",owner.id)
        assertEquals("https://www.trumba.com/calendars/community_library.ics",sources.single().url)
        assertEquals(LocalSourceKind.CALENDAR,sources.single().kind)
        assertTrue(LocalCalendarPlatforms.sources("<script>webName:'random'</script>","https://library.test/",null).isEmpty())
        val u=LocalCalendarPlatforms.request(sources.single(),LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS)).toHttpUrl()
        assertEquals("0",u.queryParameter("previousweeks"));assertNotNull(u.queryParameter("enddate"))
        assertEquals(LocalSourceKind.LOCALIST,LocalCalendarPlatforms.sources("<meta name='generator' content='Localist'>","https://calendar.test/",null).single().kind)
        assertEquals(LocalSourceKind.LOCALIST,LocalCalendarPlatforms.sources("<meta name='application-name' content='Localist Event Calendar Software'><script src='https://localist-images.azureedge.net/application.js'></script>","https://calendar.test/",null).single().kind)
        val source=LocalPublisherSource("https://calendar.test/api/2/events",LocalSourceKind.LOCALIST)
        assertNotEquals(LocalCalendarPlatforms.window(source,LocalDiscoveryQuery(area,window=LocalEventWindow.WEEK)),LocalCalendarPlatforms.window(source,LocalDiscoveryQuery(area,window=LocalEventWindow.MONTH)))
        val page2=LocalCalendarPlatforms.localist("""{"events":[],"page":{"next_page":2}}""",source.url,at,area)
        assertTrue(page2.validEmpty);assertEquals("2",LocalCalendarPlatforms.request(page2.sources.single(),LocalDiscoveryQuery(area)).toHttpUrl().queryParameter("page"))
        assertFails {LocalCalendarPlatforms.localist("""{"error":"Login required"}""",source.url,at,area)}
    }
    @Test fun localistKeepsOccurrencesSeparateAndDoesNotUsePublicationTimeAsAnEvent() {
        val body="""{"events":[{"event":{"id":12,"title":"Puppet workshop","address":"10 Main St, Sampleton, FL","geo":{"latitude":"28.608588","longitude":"-80.809445","city":"Sampleton"},"created_at":"2026-10-01","event_instances":[{"event_instance":{"id":1,"start":"2026-10-24T10:00:00-04:00","all_day":false}},{"event_instance":{"id":2,"start":"2026-10-25T10:00:00-04:00","all_day":false}}],"localist_url":"https://calendar.test/event/puppets"}},{"event":{"id":13,"title":"Undated notice","created_at":"2026-10-21"}},{"event":{"id":14,"title":"Private meeting","private":true,"event_instances":[{"event_instance":{"start":"2026-10-24T10:00:00-04:00"}}]}}]}"""
        val events=LocalCalendarPlatforms.localist(body,"https://calendar.test/api/2/events",at,area).events
        assertEquals(2,events.size);assertNotEquals(events[0].id,events[1].id);assertTrue(events.all {it.price.isEmpty()&&it.locality=="Sampleton"})
        assertEquals("2026-10-24T14:00:00Z",events.first().start.instant().toString())
    }
}
