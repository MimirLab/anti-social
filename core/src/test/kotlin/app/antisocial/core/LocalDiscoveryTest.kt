package app.antisocial.core

import org.junit.Test
import kotlin.test.*
import java.time.*

class LocalDiscoveryTest {
    private val now=Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()
    private val area=LocalArea("sampleton-au","Sampleton","Sampleton, Victoria, Australia",LocalPoint(-37.8136,144.9631),10,"Australia/Melbourne")
    private val source="https://library.example/events"
    private fun html(json:String)=LocalStructuredData.html("<script type='application/ld+json'>$json</script>",source,now,area)
    @Test fun overtureConnectsAnIdentityToCanonicalSocialsAndRetainsLicences() {
        val result=LocalStructuredData.overture("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"id":"gers-42","names":{"primary":"Community Library"},"basic_category":"library","socials":["https://www.facebook.com/library","https://instagram.com/library/"],"websites":["https://library.example/"],"phones":["+61 123"],"addresses":[{"freeform":"10 Main St","locality":"Sampleton"}],"sources":[{"dataset":"meta","license":"CDLA-Permissive-2.0"}]},"geometry":{"type":"Point","coordinates":[144.9631,-37.8136]}}]}""",source,now).businesses.single()
        assertEquals("overture:gers-42",result.id);assertEquals(2,result.socials.size);assertEquals("+61 123",result.phones.single());assertTrue(result.evidence.any {it.field=="socials"&&it.license!!.contains("CDLA")})
        val newer=result.copy(websites=listOf("https://new.example/"),checkedAt=now+100)
        assertTrue(LocalDiscoveryMerge.sameBusiness(result,newer));assertEquals(result.id,LocalDiscoveryMerge.business(result,newer).id)
    }
    @Test fun branchesSharingOneWebsiteDoNotMerge() {
        val a=LocalBusiness("a","Same Chain",point=area.point,websites=listOf("https://chain.example/"))
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,a.copy(id="b",point=LocalPoint(-37.82,144.98))))
        assertTrue(LocalDiscoveryMerge.sameBusiness(a,a.copy(id="b",point=LocalPoint(-37.81361,144.96311))))
    }
    @Test fun inheritedEvidenceCannotMakeASocialUpdateImpersonateAHigherPriorityPublisher() {
        val original=LocalBusiness("b","Library",description="Publisher description",checkedAt=now,evidence=listOf(LocalDiscoveryIdentity.evidence("description","Publisher description",source,now,LocalEvidenceKind.PUBLISHER)))
        val social=original.copy(description="Different social biography",checkedAt=now+1000,evidence=original.evidence+LocalDiscoveryIdentity.evidence("description","Different social biography","https://www.facebook.com/library",now+1000,LocalEvidenceKind.PUBLIC_POST))
        val merged=LocalDiscoveryMerge.business(original,social)
        assertEquals("Publisher description",merged.description);assertTrue(merged.evidence.any {it.kind==LocalEvidenceKind.PUBLIC_POST})
    }
    @Test fun adjacentBranchesWithDifferentStreetNumbersAndParallelVenueSessionsStayDistinct() {
        val a=LocalBusiness("a","Same Chain",address="10 Main St",point=area.point,websites=listOf("https://chain.example/"))
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,a.copy(id="b",address="20 Main St",point=LocalPoint(-37.8137,144.9632))))
        val event=LocalEvent("a","Storytime",LocalEventTime("2026-10-24T11:00:00"),venue="North Library",locality="Sampleton",urls=listOf(source))
        assertFalse(LocalDiscoveryMerge.sameEvent(event,event.copy(id="b",venue="South Library")))
    }
    @Test fun weakNameOnlyMatchCannotClaimSomebodyElsesSocialAccount() {
        val a=LocalBusiness("a","Common Name",socials=listOf("https://www.facebook.com/a"))
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,a.copy(id="b",socials=listOf("https://www.facebook.com/b"))))
    }
    @Test fun structuredGraphReferencesResolveVenueButNotOrganizerAsVenue() {
        val extracted=html("""{"@graph":[{"@id":"#venue","@type":"Place","name":"Community Hall","address":{"streetAddress":"1 Main St","addressLocality":"Sampleton"},"geo":{"latitude":-37.81,"longitude":144.96}},{"@id":"#organizer","@type":"Organization","name":"Traveling Club","address":{"addressLocality":"Sydney"}},{"@type":"Event","name":"Puppet show","startDate":"2026-10-24T11:00:00+11:00","location":{"@id":"#venue"},"organizer":{"@id":"#organizer"},"offers":{"price":0}}]}""")
        val event=extracted.events.single();assertEquals("Sampleton",event.locality);assertEquals("Traveling Club",event.organizer);assertEquals("Free",event.price)
        assertTrue(LocalDiscoveryFilter.event(LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS),event,now))
    }
    @Test fun publisherCityInTheWrongAddressFieldStillUsesExplicitVenueEvidence() {
        val event=html("""{"@type":"Event","name":"Festival","startDate":"2026-10-16","endDate":"2026-10-25","location":{"@type":"Place","name":"","address":{"streetAddress":"Sampleton","addressLocality":"Victoria","postalCode":"3000"}}}""").events.single()
        assertEquals("Sampleton",event.locality);assertNull(event.point)
        assertTrue(LocalDiscoveryFilter.event(LocalDiscoveryQuery(area),event,now))
        assertEquals("Brisbane",LocalDiscoveryFilter.addressLocality(area,"Brisbane","123 Sampleton Street, Brisbane"))
    }
    @Test fun unknownVenueCannotBorrowOrganizerCoordinates() {
        val event=html("""{"@type":"Event","name":"Festival","startDate":"2026-10-24","organizer":{"@type":"Organization","name":"Local organizer","address":{"addressLocality":"Sampleton"},"geo":{"latitude":-37.81,"longitude":144.96}}}""").events.single()
        assertNull(event.point);assertEquals("",event.locality);assertFalse(LocalDiscoveryFilter.event(LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS),event,now))
    }
    @Test fun twoEventsOnSamePageAtSameTimeRemainSeparate() {
        val events=html("""[{"@id":"#A","@type":"Event","name":"Storytime","startDate":"2026-10-24T11:00:00","location":{"name":"Library","address":{"addressLocality":"Sampleton"}}},{"@id":"#B","@type":"Event","name":"Repair workshop","startDate":"2026-10-24T11:00:00","location":{"name":"Library","address":{"addressLocality":"Sampleton"}}}]""").events
        assertEquals(2,events.size);assertFalse(LocalDiscoveryMerge.sameEvent(events[0],events[1]))
    }
    @Test fun eventFragmentsStayDistinctEvenWithSameTitle() {
        val events=html("""[{"@id":"#A","@type":"Event","name":"Storytime","startDate":"2026-10-24","location":{"name":"North Library","address":{"addressLocality":"Sampleton"}}},{"@id":"#B","@type":"Event","name":"Storytime","startDate":"2026-10-25","location":{"name":"South Library","address":{"addressLocality":"Sampleton"}}}]""").events
        assertEquals(2,events.size);assertNotEquals(events[0].sourceIds,events[1].sourceIds)
    }
    @Test fun eventFilterUsesOccurrenceDateRatherThanPublicationOrFetchDate() {
        val event=html("""{"@type":"Event","name":"Past concert","datePublished":"2026-10-21","startDate":"2025-10-24","location":{"address":{"addressLocality":"Sampleton"}}}""").events.single()
        assertFalse(LocalDiscoveryFilter.event(LocalDiscoveryQuery(area),event,now))
    }
    @Test fun datesWithOffsetsAreFilteredInTheChosenRegion() {
        val event=LocalEvent("e","Midnight meeting",LocalEventTime("2026-10-20T14:00:00Z"),locality="Sampleton")
        assertTrue(LocalDiscoveryFilter.event(LocalDiscoveryQuery(area,window=LocalEventWindow.TODAY),event,now))
    }
    @Test fun actualVenueDistanceWinsOverMatchingTownText() {
        val event=LocalEvent("e","Far away",LocalEventTime("2026-10-24",allDay=true),locality="Sampleton",point=LocalPoint(-33.87,151.21))
        assertFalse(LocalDiscoveryFilter.event(LocalDiscoveryQuery(area),event,now))
    }
    @Test fun correctionsWithPreviousDateKeepTheirOccurrenceIdentity() {
        val a=html("""{"@id":"#event","@type":"Event","name":"Workshop","startDate":"2026-10-24","location":{"address":{"addressLocality":"Sampleton"}}}""").events.single()
        val b=html("""{"@id":"#event","@type":"Event","name":"Workshop","startDate":"2026-10-25","previousStartDate":"2026-10-24","eventStatus":"https://schema.org/EventRescheduled","location":{"address":{"addressLocality":"Sampleton"}}}""").events.single().copy(modifiedAt=now+500)
        assertTrue(LocalDiscoveryMerge.sameEvent(a,b));assertEquals("2026-10-25",LocalDiscoveryMerge.event(a,b).start.value)
    }
    @Test fun searchDiscoversLinksAndNeverTurnsAnIndexSnippetIntoAnEvent() {
        val hits=LocalWebDiscovery.parse("""<rss><channel><item><title>Puppet show</title><link>https://library.example/events/puppets</link><description>Tomorrow at noon, free</description></item></channel></rss>""","https://www.bing.com/search?q=sampleton")
        assertEquals(listOf(LocalWebHit("https://library.example/events/puppets","Puppet show")),hits)
        assertFailsWith<SourceFailure> {LocalWebDiscovery.parse("<form id='challenge-form'>verify you are human</form>",source)}
    }
    @Test fun remoteJsonLdContextsAndWebsiteScriptsAreNeverExecuted() {
        val body="""<script src="https://evil.example/run.js"></script><script>throw new Error('must never execute')</script><script type="application/ld+json">{"@context":"https://evil.example/context","@type":"LocalBusiness","name":"Cafe","url":"https://cafe.example/","address":{"addressLocality":"Sampleton"}}</script>"""
        val data=LocalStructuredData.html(body,source,now,area);assertEquals("Cafe",data.businesses.single().name);assertFalse(data.sources.any {it.url.contains("evil")})
    }
    @Test fun deterministicInterestAliasesAreUsefulWithoutAI() {
        assertTrue(LocalDiscoveryFilter.matches("things for kids","Saturday puppet show for families"))
        assertTrue(LocalDiscoveryFilter.matches("organic food","Organic grocery market"))
        assertFalse(LocalDiscoveryFilter.matches("organic food","Used laptop repair"))
    }
    @Test fun ordinaryFeedDatesCannotBecomeEventDates() {
        val extraction=LocalStructuredData.html("<rss><channel><item><title>Meet us soon</title><pubDate>Wed, 21 Oct 2026 12:00:00 GMT</pubDate><link>https://library.example/update</link></item></channel></rss>",source,now,area)
        assertTrue(extraction.events.isEmpty());assertTrue(extraction.sources.any {it.url=="https://library.example/update"})
    }
    private fun calendar(events:String)=LocalCalendars.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//anti social fixture//EN\r\n"+events.trimIndent().replace("\n","\r\n")+"\r\nEND:VCALENDAR\r\n",source,now,area)
    @Test fun recurrenceSkipsExcludedDatesAndKeepsSeparateSessions() {
        val result=calendar("""
            BEGIN:VEVENT
            UID:weekly
            DTSTART;TZID=Australia/Melbourne:20261022T110000
            DTEND;TZID=Australia/Melbourne:20261022T120000
            RRULE:FREQ=WEEKLY;COUNT=3
            EXDATE;TZID=Australia/Melbourne:20261029T110000
            SUMMARY:Storytime
            LOCATION:Library\, Sampleton
            END:VEVENT
        """).events
        assertEquals(2,result.size);assertEquals(listOf("2026-10-22","2026-11-05"),result.map {it.start.date().toString()});assertNotEquals(result[0].id,result[1].id)
    }
    @Test fun recurrenceOverrideMovesOneSessionWithoutDuplicatingTheOldOne() {
        val result=calendar("""
            BEGIN:VEVENT
            UID:weekly
            DTSTART:20261022T000000Z
            RRULE:FREQ=WEEKLY;COUNT=2
            SUMMARY:Storytime
            LOCATION:Library\, Sampleton
            END:VEVENT
            BEGIN:VEVENT
            UID:weekly
            RECURRENCE-ID:20261029T000000Z
            DTSTART:20261030T000000Z
            SEQUENCE:2
            SUMMARY:Moved storytime
            LOCATION:Library\, Sampleton
            END:VEVENT
        """).events
        assertEquals(2,result.size);assertEquals(1,result.count {it.title=="Moved storytime"});assertEquals("2026-10-30",result.first {it.title=="Moved storytime"}.start.date().toString())
    }
    @Test fun embeddedNonIanaTimezoneUsesItsLocalDefinitionWithoutDownloadingTimezoneCode() {
        val result=calendar("""
            BEGIN:VTIMEZONE
            TZID:LibraryCustom
            TZURL:https://never-fetch.example/timezone
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+1100
            TZOFFSETTO:+1100
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:custom-zone
            DTSTART;TZID=LibraryCustom:20261024T110000
            SUMMARY:Workshop
            LOCATION:Sampleton
            END:VEVENT
        """).events.single()
        assertEquals(Instant.parse("2026-10-24T00:00:00Z"),result.start.instant())
    }
    @Test fun allDayCalendarEndIsExclusiveAndCancellationIsExplicit() {
        val result=calendar("""
            BEGIN:VEVENT
            UID:festival
            DTSTART;VALUE=DATE:20261024
            DTEND;VALUE=DATE:20261026
            SUMMARY:Festival
            STATUS:CANCELLED
            LOCATION:Sampleton
            END:VEVENT
        """).events.single()
        assertTrue(result.start.allDay);assertEquals("2026-10-25",result.end!!.value);assertEquals("cancelled",result.status)
    }
    @Test fun ownedWebsiteFooterConnectsAccountsWithoutClaimingShareButtonsOrArticleMentions() {
        val business=LocalBusiness("b","Community Library",point=area.point,websites=listOf(source))
        val body="""<meta property="og:site_name" content="Community Library"><article><a href="https://www.facebook.com/unrelated">Mention</a></article><footer><a href="https://www.facebook.com/library">Facebook</a><a href="https://www.instagram.com/library/">Instagram</a><a href="https://www.facebook.com/sharer.php?u=https://example.com">Share</a></footer>"""
        val result=LocalStructuredData.html(body,source,now,area,business).businesses.single()
        assertEquals(2,result.socials.size);assertTrue(result.socials.none {it.contains("unrelated")||it.contains("sharer")});assertEquals("b",result.id)
    }
    @Test fun tribeCalendarHonoursItsDeclaredTimezoneAndAllDayDates() {
        val body="""{"events":[{"title":"Evening show","url":"https://library.example/show","start_date":"2026-10-24 19:00:00","end_date":"2026-10-24 20:00:00","timezone":"Europe/London","venue":{"venue":"Library","city":"Sampleton"}},{"title":"Market","url":"https://library.example/market","start_date":"2026-10-25 00:00:00","end_date":"2026-10-25 23:59:59","timezone":"Australia/Melbourne","all_day":true,"venue":{"venue":"Library","city":"Sampleton"}}]}"""
        val result=LocalStructuredData.tribe(body,source,now,area).events
        assertEquals(Instant.parse("2026-10-24T18:00:00Z"),result.first {it.title=="Evening show"}.start.instant())
        assertTrue(result.first {it.title=="Market"}.start.allDay)
    }
    @Test fun areaPreferencesValidateBeforeTheyCanBeImported() {
        assertEquals(listOf(area),LocalAreaCodec.decode(LocalAreaCodec.encode(listOf(area))))
        assertFails {LocalAreaCodec.decode(LocalAreaCodec.encode(listOf(area)).replace("-37.8136","999"))}
        assertFails {LocalAreaCodec.decode("[{\"id\":null}]")}
        assertFails {LocalAreaCodec.decode(LocalAreaCodec.encode(listOf(area)).replace("\"radiusKm\":10","\"radiusKm\":10.5"))}
    }
    @Test fun washingPreservesStructuredFactsButRejectsLoginWallsAndNeverKeepsPageHusks() {
        val markup="""<script type="application/ld+json">{"@type":"LocalBusiness","name":"Library","address":{"addressLocality":"Sampleton"}}</script><nav>Tracking navigation</nav><script>fetch('https://evil.example')</script>"""
        val data=LocalDocumentWashing.extract(Payload(markup,source,"text/html",200),now,area)
        assertEquals("Library",data.businesses.single().name)
        assertFalse(data.businesses.single().description.contains("Tracking"))
        val error=assertFailsWith<SourceFailure> {LocalDocumentWashing.extract(Payload("<title>Log in</title><form><input type=password>Log in to continue</form>"+markup,source,"text/html",200),now,area)}
        assertEquals(HealthStatus.LOGIN_REQUIRED,error.status)
    }
    @Test fun advertisedDataFeedsAndStandaloneJsonLdRemainInertDataSources() {
        val data=LocalStructuredData.html("<link rel='alternate' type='application/rss+xml' href='/feed'><link rel='alternate' type='application/ld+json' href='/events.jsonld'>",source,now,area)
        assertEquals(setOf("https://library.example/feed","https://library.example/events.jsonld"),data.sources.map {it.url}.toSet())
        val json="""{"@context":"https://never-fetch.example","@type":"LocalBusiness","name":"Library","description":"Safe </script> text","address":{"addressLocality":"Sampleton"}}"""
        assertEquals("Library",LocalStructuredData.jsonld(json,source,now,area).businesses.single().name)
    }
    @Test fun structuredInputAndCalendarWorkAreBounded() {
        assertFails {LocalStructuredData.html("x".repeat(LocalStructuredData.MAX_DOCUMENT+1),source,now)}
        val result=calendar("""
            BEGIN:VEVENT
            UID:abusive
            DTSTART:20261022T000000Z
            RRULE:FREQ=SECONDLY
            SUMMARY:Every second
            LOCATION:Sampleton
            END:VEVENT
        """)
        assertTrue(result.events.size<=1);assertNotNull(result.notice)
    }
}
