package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class LocalAnnouncementReadingTest {
    private val url="https://library.example/events/repair"
    private val event=LocalEvent("repair","Radio repair workshop",LocalEventTime("2026-10-30",allDay=true))
    private fun body(title:String=event.title,extra:String="")="""<html><head><title>$title</title><script type="application/ld+json">{"@type":"Event","name":"${event.title}","startDate":"2026-10-30","location":{"@type":"Place","name":"Library","address":"10 Main St"},"offers":{"price":"0","priceCurrency":"USD"}}</script></head><body><main><h1>$title</h1><p>Bring a broken radio. Friday at the library.</p>$extra</main></body></html>"""
    @Test fun aShortMatchingStructuredAnnouncementRetainsItsActualDetails() {
        val doc=LocalAnnouncementReading.extract(Payload(body(),url,"text/html",200),url,event,1234)
        assertEquals(event.title,doc.title);assertTrue(doc.text.contains("2026-10-30"));assertTrue(doc.text.contains("Library"))
        assertNull(doc.publishedAt);assertEquals(1234L,doc.fetchedAt)
    }
    @Test fun unrelatedMetadataDoesNotQualifyATersePage() {
        assertThrows(SourceFailure::class.java){LocalAnnouncementReading.extract(Payload(body().replace(event.title,"Another short event"),url,"text/html",200),url,event)}
    }
    @Test fun staleEventMetadataCannotRescueADeletedOrLoginPage() {
        for(title in listOf("Event not found","404 - Page not found","Page not found"))assertThrows(SourceFailure::class.java){LocalAnnouncementReading.extract(Payload(body(title),url,"text/html",200),url,event)}
        val login=body(extra="<div class='login-wall'>Log in to continue</div>")
        assertThrows(SourceFailure::class.java){LocalAnnouncementReading.extract(Payload(login,url,"text/html",200),url,event)}
    }
    @Test fun aRedirectToAGeneralCalendarDoesNotPretendTheOriginalAnnouncementSurvived() {
        val gone=body().replace(event.title,"Our community calendar")
        assertThrows(SourceFailure::class.java){LocalAnnouncementReading.extract(Payload(gone,"https://library.example/","text/html",200),url,event)}
    }
}
