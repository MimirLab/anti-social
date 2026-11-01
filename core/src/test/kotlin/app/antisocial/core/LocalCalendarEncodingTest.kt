/* anti social, GPL-3.0-or-later. Calendar interoperability without changing source values. */
package app.antisocial.core

import java.time.Instant
import org.junit.Test
import kotlin.test.*

class LocalCalendarEncodingTest {
    private val area=LocalArea("fixture-encoding","Example City","Example City, Test Region",LocalPoint(10.0,20.0),5,"UTC",countryCode="ZZ")
    private val at=Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
    private val url="https://publisher.fixture.invalid/calendar"
    private val body="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:fixture-encoding\r\nSUMMARY:Radio \uFEFFworkshop\r\nDTSTART;VALUE=DATE:20261102\r\nRRULE:FREQ=DAILY;COUNT=3\r\nLOCATION:1 Example Street\\, Example City\r\nGEO:10;20\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parse(text:String)=LocalCalendars.parse(text,url,at,area)
    @Test fun anInitialEncodingMarkerRetainsAllOccurrencesAndTheirExactSourceValues() {
        val ordinary=parse(body);assertEquals(3,ordinary.events.size)
        assertEquals(ordinary,parse("\uFEFF"+body))
        assertTrue(ordinary.events.all {it.title=="Radio \uFEFFworkshop"&&it.urls.contains(url)&&it.evidence.any {e->e.field=="title"&&e.value=="Radio \uFEFFworkshop"}})
    }
    @Test fun aMarkerCannotBypassTheExistingDocumentAllowance() {
        assertFailsWith<IllegalArgumentException>{parse("\uFEFF"+body+" ".repeat(LocalStructuredData.MAX_DOCUMENT))}
    }
    @Test fun markerOnlyAndRepeatedMarkersCannotBecomeAWorkingCalendar() {
        for(text in listOf("\uFEFF","\uFEFF\uFEFF"+body," \uFEFF"+body))assertFailsWith<IllegalArgumentException>{parse(text)}
    }
}
