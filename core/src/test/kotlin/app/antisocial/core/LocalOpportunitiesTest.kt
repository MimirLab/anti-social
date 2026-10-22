package app.antisocial.core

import org.junit.Test
import kotlin.test.*
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl

class LocalOpportunitiesTest {
    private val at=Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
    private val area=LocalArea("melbourne-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
    private val query=LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS)
    private val helsinki=LocalArea("helsinki","Helsinki","Helsinki, Finland",LocalPoint(60.1699,24.9384),10,"Europe/Helsinki",countryCode="FI")
    private val source="https://calendar.example/"
    private fun event(address:String="Skate Park, 750 Jamaica Blvd, Satellite Beach, FL 32937")=LocalEvent("event","Workshop",LocalEventTime("2026-10-24T10:00:00",area.zoneId),address=address,venue="Skate Park")
    private fun feature(city:String="Satellite Beach",number:String="750",lon:Double= -80.602,lat:Double=28.175)=
        """{"properties":{"housenumber":"$number","street":"Jamaica Boulevard","city":"$city","countrycode":"US"},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""

    @Test fun regionalSeedsRespectCoordinatesCountryTopicAndMode() {
        assertEquals(2,LocalOpportunityRouting.seeds(query).size)
        assertEquals(1,LocalOpportunityRouting.seeds(query.copy(text="kids")).size)
        assertTrue(LocalOpportunityRouting.seeds(query.copy(text="software releases")).isEmpty())
        assertTrue(LocalOpportunityRouting.seeds(query.copy(mode=LocalSearchMode.BUSINESSES)).isEmpty())
        assertTrue(LocalOpportunityRouting.seeds(query.copy(savedOnly=true)).isEmpty())
        val au=area.copy(point=LocalPoint(-37.81,144.96),countryCode="AU",label="Melbourne, Victoria, Australia")
        assertTrue(LocalOpportunityRouting.seeds(query.copy(area=au)).isEmpty())
        assertEquals(listOf(LocalSourceKind.LINKED_EVENTS),LocalOpportunityRouting.seeds(query.copy(area=helsinki)).map {it.kind})
    }
    @Test fun sisterSitesAndForeignRegionalEndpointsAreRejectedBeforeReading() {
        assertFalse(LocalOpportunityRouting.allowed(LocalPublisherSource("https://fun4orlandokids.com/calendar/"),query))
        assertFalse(LocalOpportunityRouting.allowed(LocalPublisherSource("https://api.hel.fi/linkedevents/v1/event/",LocalSourceKind.LINKED_EVENTS),query))
        assertTrue(LocalOpportunityRouting.allowed(LocalPublisherSource("https://fun4spacecoastkids.com/calendar/"),query))
        assertFalse(LocalOpportunityRouting.allowed(LocalPublisherSource("https://sfyl.ifas.ufl.edu/brevard/"),query.copy(area=helsinki)))
        assertFalse(LocalOpportunityRouting.allowed(LocalPublisherSource("https://fun4spacecoastkids.com/calendar/icalevent_edit/0"),query))
        assertFalse(LocalOpportunityRouting.allowed(LocalPublisherSource("https://fun4spacecoastkids.com/calendar/eventsbyweek/2020/1/1/-"),query))
    }
    @Test fun coverageOverlapsTheRadiusInsteadOfRequiringTheSameCityName() {
        val box=LocalCoverageBox(28.1,-80.7,28.3,-80.5,"US")
        assertTrue(box.overlaps(area));assertFalse(box.overlaps(area.copy(point=LocalPoint(29.5,-80.6))))
        assertFalse(box.overlaps(area.copy(countryCode="AU")))
    }
    @Test fun focusedDiscoveryStillUsesOnlyTwoRegionalQueries() {
        val queries=LocalBusinessRouting.queries(query.copy(text="kids"))
        assertEquals(2,queries.size);assertTrue(queries.all {it.contains("Florida")});assertTrue(queries[1].contains("library parks"))
        assertTrue(LocalBusinessRouting.queries(query.copy(text="sky zone",mode=LocalSearchMode.BUSINESSES)).all {it.contains("Florida")})
        assertEquals("",LocalDiscoveryFilter.eventQuery("local events near me"))
        assertEquals(2,LocalOpportunityRouting.seeds(query.copy(text="local events near me")).size)
        assertTrue(LocalDiscoveryFilter.matches(LocalDiscoveryFilter.eventQuery("nearby workshops"),"A class on radio repairs"))
    }
    @Test fun offeredPublicFeedsAndSpaceEndpointsAreRecognizedWithoutRunningWidgets() {
        val body="""<link rel="space-api" href="/space.json"><a href="webcal://calendar.example/events.ics">Subscribe</a><a href="/?my-calendar-api=ical">iCal</a><script>fetch('https://never.example/run')</script>"""
        val sources=LocalCalendarPlatforms.sources(body,source,null)
        assertTrue(sources.any {it.kind==LocalSourceKind.SPACEAPI&&it.url.endsWith("space.json")})
        assertEquals(2,sources.count {it.kind==LocalSourceKind.CALENDAR});assertTrue(sources.none {it.url.contains("never.example")})
    }
    @Test fun livewhaleKeepsTheDepartmentScopeAndBoundsItsWindowAndFields() {
        val input=LocalPublisherSource("https://calendar.example/live/json/v2/events/group/Engineering/max/900/start_date/2020-01-01/?page=9",LocalSourceKind.LIVEWHALE)
        val request=LocalCalendarPlatforms.request(input,query).toHttpUrl()
        assertTrue(request.encodedPath.contains("/group/Engineering/"));assertTrue(request.encodedPath.contains("/max/50/"));assertFalse(request.encodedPath.contains("900"));assertNull(request.queryParameter("page"))
        assertTrue(request.encodedPath.contains("response_fields"));assertNotEquals(LocalCalendarPlatforms.window(input,query),LocalCalendarPlatforms.window(input,query.copy(window=LocalEventWindow.MONTH)))
    }
    @Test fun livewhaleReadsOccurrencesCorrectionsCostsAndExplicitCancellation() {
        val body="""{"data":[{"id":1,"title":"Workshop","date_iso":"2026-10-24T10:00:00-04:00","date2_iso":"2026-10-24T12:00:00-04:00","location_title":"Library","location_latitude":28.08,"location_longitude":-80.61,"description":"<p>Bring your broken radio.</p>","cost":"Free","last_modified":1792600000,"is_canceled":1},{"id":2,"title":"Hidden","status":2,"date_iso":"2026-10-24T11:00:00-04:00"},{"id":3,"title":"Undated notice","date_published":"2026-10-24"}]}"""
        val event=LocalOpportunityDocuments.livewhale(body,source,at,area).events.single()
        assertEquals("cancelled",event.status);assertEquals("Free",event.price);assertEquals(1792600000000L,event.modifiedAt);assertEquals("Bring your broken radio.",event.description)
        assertTrue(LocalDiscoveryFilter.event(query,event,at));assertNotNull(event.end)
        val v1=LocalOpportunityDocuments.livewhale("[]",source,at,area);assertTrue(v1.validEmpty)
        assertFails {LocalOpportunityDocuments.livewhale("{\"error\":\"login\"}",source,at,area)}
    }
    @Test fun spaceApiProvidesLocalAccessDetailsAndCalendarButNeverSensorEventsOrClosureClaims() {
        val body="""{"api_compatibility":["15"],"space":"Coastal Makers","url":"https://coastalmakers.example/","location":{"lat":28.08,"lon":-80.61,"address":"10 Main St, Melbourne, FL","country_code":"US","hint":"Use the side entrance."},"contact":{"phone":"+13215551234","email":"hello@coastalmakers.example","facebook":"https://www.facebook.com/coastalmakers"},"state":{"open":false},"events":[{"name":"Person arrived","timestamp":1792600000,"type":"check-in"}],"areas":[{"name":"Workshop","description":"Woodworking tools and a 3D printer"}],"feeds":{"calendar":{"type":"ical","url":"https://coastalmakers.example/public.ics"}}}"""
        val result=LocalOpportunityDocuments.space(body,source,at,area);val b=result.businesses.single()
        assertEquals("makerspace",b.category);assertEquals("",b.status);assertTrue(result.events.isEmpty());assertEquals(LocalSourceKind.CALENDAR,result.sources.single().kind)
        assertTrue(LocalBusinessDetails.facts(b).any {it.field=="visit notes"&&it.value.contains("side entrance")})
        assertEquals(listOf("hello@coastalmakers.example"),b.emails)
        val foreign=LocalOpportunityDocuments.space(body.replace("28.08","60.17").replace("-80.61","24.94"),source,at,area)
        assertTrue(foreign.validEmpty);assertTrue(foreign.businesses.isEmpty());assertTrue(foreign.sources.isEmpty())
    }
    @Test fun aSpaceWithoutAnActualLocalLocationCannotBecomeALocalPlace() {
        val result=LocalOpportunityDocuments.space("""{"api":"0.13","space":"Floating Club","url":"https://club.example/","contact":{}}""",source,at,area)
        assertTrue(result.businesses.isEmpty());assertFalse(LocalOpportunityDocuments.isSpaceApi("{\"space\":\"an advertisement\"}"))
    }
    @Test fun linkedEventsUsesABoundingBoxAndActualVenueRatherThanPublisherIdentity() {
        val q=query.copy(area=helsinki);val seed=LocalOpportunityRouting.seeds(q).single()
        val url=LocalOpportunityRouting.linkedRequest(seed,q).toHttpUrl()
        assertEquals("50",url.queryParameter("page_size"));assertEquals("location",url.queryParameter("include"));assertNotNull(url.queryParameter("bbox"));assertNotNull(url.queryParameter("end"))
        val body="""{"meta":{"next":"https://never-follow.example/"},"data":[{"id":"city:1","name":{"en":"Repair workshop","fi":"Korjaus"},"start_time":"2026-10-24T10:00:00Z","event_status":"EventScheduled","last_modified_time":"2026-10-22T08:00:00Z","offers":[{"is_free":true}],"location":{"name":{"en":"Library"},"address_locality":{"en":"Helsinki"},"street_address":{"en":"Main St 10"},"position":{"coordinates":[24.94,60.17]}}},{"id":"far:1","name":{"en":"Far show"},"start_time":"2026-10-24T10:00:00Z","location":{"position":{"coordinates":[25.7,62.2]}}}]}"""
        val result=LocalOpportunityDocuments.linkedEvents(body,seed.url,at,helsinki)
        assertEquals("Repair workshop",result.events.single().title);assertEquals("Free",result.events.single().price);assertTrue(result.sources.isEmpty());assertNotNull(result.notice)
    }
    @Test fun matchingCityCannotOverrideExplicitCountryOrStateConflicts() {
        val e=event("10 Main St, Melbourne, Victoria, Australia").copy(locality="Melbourne")
        assertFalse(LocalDiscoveryFilter.geographic(area,e))
        assertFalse(LocalDiscoveryFilter.geographic(area,e.copy(address="10 Main St, Melbourne, AR 72556")))
        assertTrue(LocalDiscoveryFilter.geographic(area,e.copy(address="10 Main St, Melbourne, FL 32901")))
        val json="""{"@type":"Event","name":"Foreign event","startDate":"2026-10-24","location":{"address":{"addressLocality":"Melbourne","addressCountry":"AU","addressRegion":"Victoria"}}}"""
        val fromSchema=LocalStructuredData.jsonld(json,source,at,area).events.single()
        assertFalse(LocalDiscoveryFilter.geographic(area,fromSchema));assertTrue(fromSchema.evidence.any {it.field=="country"&&it.value=="AU"})
    }
    @Test fun streetNamedAfterTheSelectedCityDoesNotInventVenueLocality() {
        assertEquals("",LocalDiscoveryFilter.addressLocality(area,"","123 Melbourne Street, Brisbane"))
        assertEquals("Melbourne",LocalDiscoveryFilter.addressLocality(area,"","10 Main St Melbourne, FL 32901"))
        val ics="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:foreign\r\nSUMMARY:Far show\r\nDTSTART:20261024T100000Z\r\nLOCATION:123 Melbourne Street\\, Brisbane\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertFalse(LocalDiscoveryFilter.geographic(area,LocalCalendars.parse(ics,source,at,area).events.single()))
    }
    @Test fun venueResolutionRequiresTheExactStreetAndTownAndRejectsAmbiguousOrFarMatches() {
        val e=event();assertTrue(LocalVenueResolution.eligible(query,e,at))
        val url=LocalVenueResolution.request(query,e).toHttpUrl();assertNotNull(url.queryParameter("bbox"));assertEquals("5",url.queryParameter("limit"))
        fun parse(vararg rows:String)=LocalVenueResolution.parse("{\"features\":[${rows.joinToString(",")}]}",source,at,area,e)
        val resolved=parse(feature())!!;assertNotNull(resolved.point);assertEquals("Satellite Beach",resolved.locality);assertTrue(LocalDiscoveryFilter.geographic(area,resolved))
        assertNull(parse(feature(number="751")));assertNull(parse(feature(city="Cocoa")));assertNull(parse(feature(lat=29.5)))
        assertNull(parse(feature(),feature(lon= -80.65)))
        assertFalse(LocalVenueResolution.eligible(query,e.copy(address="Somewhere in town"),at))
    }
    @Test fun knownPlacesResolveAnAddressWithoutNetworkButCannotBorrowAnotherTownsPin() {
        val e=event();val b=LocalBusiness("park","Skate Park",address="750 Jamaica Blvd, Satellite Beach, FL",locality="Satellite Beach",point=LocalPoint(28.175,-80.602))
        assertNotNull(LocalVenueResolution.known(area,e,listOf(b)))
        assertNull(LocalVenueResolution.known(area,e,listOf(b.copy(locality="Melbourne"))))
        assertNull(LocalVenueResolution.known(area,e,listOf(b,b.copy(id="other",point=LocalPoint(28.17,-80.65)))))
    }
    @Test fun familyCalendarUsesItsExplicitWhenAndLocationFieldsAndKeepsRegistrationFacts() {
        val html="""<div class="f4us_descr"><h2>Skate workshop</h2><ul><li class="f4us_time"><strong>When:</strong> Saturday, October 24, 2026, 9:00 am - 10:30 am</li><li class="f4usk-ev-location"><strong>Location:</strong> Skate Park, 750 Jamaica Blvd, Satellite Beach, FL 32937</li><li class="f4us_cost"><strong>Cost:</strong> $70 residents / $77 nonresidents</li></ul><p>Ages: 5-17. Registration required.</p></div>"""
        val result=LocalCommunityPages.events(html,source,at,area).single()
        assertEquals("2026-10-24T09:00",result.start.value);assertEquals("2026-10-24T10:30",result.end!!.value);assertEquals("5-17",result.age);assertTrue(result.price.contains("nonresidents"));assertNull(result.point)
        assertTrue(LocalCommunityPages.events(html.replace("Saturday, October 24, 2026, 9:00 am - 10:30 am","Coming soon"),source,at,area).isEmpty())
    }
    @Test fun calendarDiscoverySkipsOldDaysAndDoesNotFollowNationalSisterNavigation() {
        val stamp=Instant.parse("2026-10-24T14:00:00Z").epochSecond
        val html="""<script src="/components/com_jevents/assets.js"></script><div class="jevstart_1577836800"><a class="cal_titlelink" href="/calendar/eventdetail/1/old">Old</a></div><div class="jevstart_$stamp"><a class="cal_titlelink" href="/calendar/eventdetail/2/workshop">Workshop</a></div><footer><a href="https://fun4orlandokids.com/calendar/">Events</a></footer>"""
        val result=LocalOpportunityRouting.sources(html,"https://fun4spacecoastkids.com/calendar/",query,at)
        assertEquals(1,result.size);assertTrue(result.single().url.contains("/2/"))
    }
    @Test fun calendarBackoffIsBoundedAndChangedPriceOrVenueResetsStability() {
        val original=LocalExtraction(events=listOf(event().copy(price="$10")))
        var health=LocalBusinessEnrichment.observe(null,original,25,at,calendar=true)
        repeat(12){health=LocalBusinessEnrichment.observe(health,original,25,health.checkedAt+3600000,calendar=true)}
        assertEquals(6L*3600000,health.nextAt-health.checkedAt)
        val changed=LocalBusinessEnrichment.observe(health,original.copy(events=listOf(original.events.single().copy(price="$12"))),25,health.checkedAt+3600000,calendar=true)
        assertEquals(0,changed.stable);assertEquals(30L*60000,changed.nextAt-changed.checkedAt)
    }
    @Test fun anExplicitlyDistantPublisherCannotGrowAForeignCrawlButARegionalHubCanOfferItsCalendar() {
        val far=event().copy(point=LocalPoint(40.7,-74.0))
        val next=LocalPublisherSource("https://distant.example/calendar")
        val data=LocalExtraction(events=listOf(far),sources=listOf(next))
        assertTrue(LocalOpportunityRouting.scoped(LocalPublisherSource("https://distant.example/"),query,data).sources.isEmpty())
        val seed=LocalOpportunityRouting.seeds(query).first()
        assertEquals(listOf(next),LocalOpportunityRouting.scoped(seed,query,data).sources)
        assertEquals(listOf(next),LocalOpportunityRouting.scoped(LocalPublisherSource("https://publisher.example/"),query,data.copy(events=listOf(far.copy(point=area.point)))).sources)
    }
}
