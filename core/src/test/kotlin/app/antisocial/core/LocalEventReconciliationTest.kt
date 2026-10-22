package app.antisocial.core

import org.junit.Test
import kotlin.test.*

class LocalEventReconciliationTest {
    private val at=1792614315235L
    private val url="https://www.visitspacecoast.com/event/downtown-melbourne-trick-or-treat/"
    private val calendar=LocalEvent("ical","Downtown Melbourne Trick or Treat",LocalEventTime("2026-10-24T11:00-03:00"),
        venue="Downtown Melbourne, E New Haven Avenue, Melbourne, FL, 32901, United States",address="E New Haven Avenue, Melbourne, FL",locality="Melbourne",
        urls=listOf(url,"https://www.visitspacecoast.com/events-calendar/?ical=1"),sourceIds=listOf("ical:one"),checkedAt=at-100,
        evidence=listOf(LocalDiscoveryIdentity.evidence("start","2026-10-24T11:00-03:00",url,at-100,LocalEvidenceKind.CALENDAR)))
    private val web=calendar.copy(id="web",start=LocalEventTime("2026-10-24T11:00:00-04:00","America/New_York"),venue="Downtown Melbourne",
        urls=listOf(url),sourceIds=listOf("web:one"),checkedAt=at,evidence=listOf(LocalDiscoveryIdentity.evidence("start","2026-10-24T11:00:00-04:00",url,at,LocalEvidenceKind.PUBLISHER)))
    @Test fun sameSpecificOccurrenceSurvivesThePublishersWrongCalendarTimezone() {
        assertTrue(LocalDiscoveryMerge.sameEvent(calendar,web))
        assertTrue(LocalEventIdentity.keys(calendar).intersect(LocalEventIdentity.keys(web)).isNotEmpty())
        val merged=LocalDiscoveryMerge.events(listOf(calendar,web)).single()
        assertEquals(web.start,merged.start);assertEquals(setOf("ical:one","web:one"),merged.sourceIds.toSet());assertEquals(2,merged.urls.size)
    }
    @Test fun intracoastalExpandedVenueAndCompanyAbbreviationRemainOneOccurrence() {
        val a=calendar.copy(title="Halloween Family Fun Night at Intracoastal Brewing Co.",venue="Intracoastal Brewing Company, 652 W Eau Gallie Blvd, Melbourne",address="652 W Eau Gallie Blvd, Melbourne")
        val b=web.copy(title=a.title,venue="Intracoastal Brewing Company",address="652 West Eau Gallie Boulevard, Melbourne")
        assertEquals(1,LocalDiscoveryMerge.events(listOf(a,b)).size)
    }
    @Test fun aSeparatePerformanceIsNotAnOffsetCorrection() {
        assertFalse(LocalDiscoveryMerge.sameEvent(calendar,web.copy(start=LocalEventTime("2026-10-24T12:00:00-04:00"))))
        assertFalse(LocalDiscoveryMerge.sameEvent(calendar,web.copy(start=LocalEventTime("2026-10-25T11:00:00-04:00"))))
        assertFalse(LocalDiscoveryMerge.sameEvent(calendar,web.copy(start=LocalEventTime("2026-10-24",allDay=true))))
    }
    @Test fun aSharedCalendarOrUnrelatedVenueCannotAuthorizeTimezoneMerging() {
        val generic="https://www.visitspacecoast.com/events-calendar/?ical=1"
        assertFalse(LocalDiscoveryMerge.sameEvent(calendar.copy(urls=listOf(generic)),web.copy(urls=listOf(generic))))
        assertFalse(LocalDiscoveryMerge.sameEvent(calendar,web.copy(venue="South Library",address="20 Different Ave")))
    }
    @Test fun equivalentInstantsOnDifferentCalendarDatesStillBecomeCandidates() {
        val a=calendar.copy(start=LocalEventTime("2026-10-24T23:00:00-04:00"))
        val b=web.copy(start=LocalEventTime("2026-10-25T03:00:00Z"))
        assertTrue(LocalEventIdentity.keys(a).intersect(LocalEventIdentity.keys(b)).isNotEmpty())
        assertEquals(1,LocalDiscoveryMerge.events(listOf(a,b)).size)
    }
    @Test fun harmlessEditionYearDoesNotPreventADuplicateButDifferentEventsStaySeparate() {
        assertTrue(LocalDiscoveryMerge.sameEvent(calendar,web.copy(title=web.title+" 2026")))
        assertFalse(LocalDiscoveryMerge.sameEvent(calendar,web.copy(title="Downtown Melbourne Costume Contest")))
        assertFalse(LocalDiscoveryMerge.sameEvent(calendar,web.copy(title=web.title+" 2025")))
    }
    @Test fun freshestStatedUpdateBeatsRetrievalTimeAndUnrelatedSequenceNumbers() {
        val a=calendar.copy(modifiedAt=at-5000,checkedAt=at+10000,sequence=30,price="$20")
        val b=web.copy(modifiedAt=at-1000,checkedAt=at,status="cancelled",price="$10")
        for(values in listOf(listOf(a,b),listOf(b,a))) {
            val result=LocalDiscoveryMerge.events(values).single();assertEquals("cancelled",result.status);assertEquals("$10",result.price);assertEquals(b.start,result.start)
            assertEquals("$10",LocalDiscoveryMerge.event(result,a.copy(checkedAt=at+20000)).price)
        }
        assertEquals("postponed",LocalDiscoveryMerge.event(web,web.copy(sequence=1,status="postponed",checkedAt=at-100)).status)
    }
    @Test fun newerSourceWithoutAnUpdateDateUsesRetrievalTime() {
        val result=LocalDiscoveryMerge.event(web,calendar.copy(checkedAt=at+100,price="Free"))
        assertEquals("Free",result.price);assertEquals(calendar.start,result.start)
    }
    @Test fun retrievingAnOlderCopyCannotArtificiallyFreshenTheWinningDetails() {
        val old=calendar.copy(checkedAt=at+10000,modifiedAt=at-5000,price="$20")
        val first=LocalDiscoveryMerge.event(web.copy(price="$10"),old)
        assertEquals(at,first.checkedAt)
        val update=web.copy(sourceIds=listOf("third-source"),checkedAt=at+2000,modifiedAt=at+1000,price="$5")
        assertEquals("$5",LocalDiscoveryMerge.event(first,update).price)
    }
    @Test fun calendarAdaptersRetainPublishedUpdateTimesRatherThanPublicationDates() {
        val area=LocalArea("fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08,-80.6),25,"America/New_York",countryCode="US")
        val tribe=LocalStructuredData.tribe("""{"events":[{"title":"Festival","start_date":"2026-10-24 11:00:00","timezone":"America/New_York","modified_utc":"2026-10-20 15:30:00","date_utc":"2025-01-01 10:00:00","url":"$url"}]}""",url,at,area).events.single()
        assertEquals(java.time.Instant.parse("2026-10-20T15:30:00Z").toEpochMilli(),tribe.modifiedAt)
        val localist=LocalCalendarPlatforms.localist("""{"events":[{"event":{"id":1,"title":"Festival","updated_at":"2026-10-20T11:30:00-04:00","created_at":"2025-01-01","event_instances":[{"event_instance":{"id":1,"start":"2026-10-24T11:00:00-04:00"}}]}}]}""","https://calendar.test/api/2/events",at,area).events.single()
        assertEquals(tribe.modifiedAt,localist.modifiedAt)
    }
    @Test fun doubleEncodedCalendarMarkupBecomesReadablePlainText() {
        assertEquals("Doors open at noon. Bring a costume.",LocalDocumentWashing.text("&lt;p&gt;Doors open at noon.&lt;/p&gt;&lt;p&gt;Bring a costume.&lt;/p&gt;"))
    }
    @Test fun completeDescriptionSurvivesANewerListingExcerptWithoutRepeatingIt() {
        val full="Families can collect treats at participating businesses throughout downtown. Look for the marked entrances."
        val result=LocalDiscoveryMerge.event(calendar.copy(description=full),web.copy(description="<p>"+full.substringBefore(" Look")+"...</p>"))
        assertEquals(full,result.description)
    }
    @Test fun descriptionFallbackRetainsNewlyExtractedAccountsAndContacts() {
        val area=LocalArea("fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08,-80.6),25,"America/New_York",countryCode="US")
        val owner=LocalBusiness("known","Example Coffee",address="10 Main St, Melbourne, FL 32901",locality="Melbourne",websites=listOf("https://examplecoffee.test/"))
        val body="""<html><head><title>Example Coffee</title><meta property="og:site_name" content="Example Coffee"></head><body><header><a href="https://www.facebook.com/ExampleCoffee">Facebook</a></header><main><h1>Example Coffee</h1><p>Our neighborhood coffee shop roasts whole beans and serves freshly prepared drinks. Customers can enjoy a quiet table, browse seasonal beans and find practical information about visiting the shop.</p><p>We offer coffee, tea and pastries prepared each morning. Visit us for our current selection.</p></main><footer><a href="mailto:hello@examplecoffee.test">Email</a></footer></body></html>"""
        val data=LocalDocumentWashing.extract(Payload(body,"https://examplecoffee.test/","text/html",200),at,area,owner)
        val result=data.businesses.first {it.id==owner.id}
        assertTrue(result.description.isNotBlank());assertTrue(result.socials.any {it.contains("ExampleCoffee",true)})
        assertEquals(listOf("hello@examplecoffee.test"),result.emails)
    }
    @Test fun shortenedSiteBrandRequiresAMatchingDomainAndCompatibleIdentity() {
        assertTrue(LocalBusinessOwnership.firstParty("Intracoastal Brewing Company","https://www.intracoastalbrewingcompany.com/",listOf("Intracoastal")))
        assertTrue(LocalBusinessOwnership.firstParty("Indian River Coffee","https://www.indianrivercoffee.com/",listOf("IndianRiverCoffee")))
        assertFalse(LocalBusinessOwnership.firstParty("Intracoastal Brewing Company","https://directory.test/",listOf("Intracoastal")))
        assertFalse(LocalBusinessOwnership.firstParty("Indian River Coffee","https://melbournedirections.com/",listOf("Melbourne Directions")))
    }
}
