package app.antisocial.core

import org.junit.Test
import java.time.Instant
import kotlin.test.*

class LocalAnnouncementsTest {
    private val at=Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
    private val area=LocalArea("fl","Sampleton","Sampleton, Florida, US",LocalPoint(28.608588,-80.799445),25,"America/New_York",countryCode="US")
    private val owner=LocalBusiness("library","Town Library",address="123 Main St, Sampleton, FL",locality="Sampleton",point=area.point)
    private fun post(text:String,published:Long?=at)=Post("p",Platform.FACEBOOK,"https://www.facebook.com/library/posts/123","Town Library",text,publishedAt=published)
    private fun extract(post:Post,business:LocalBusiness,area:LocalArea,at:Long,ocr:OcrResult?=null,image:String?=null)=LocalAnnouncements.extract(post,business,area,at,ocr,image,publisherLocationVerified=true)
    @Test fun ExplicitAnnouncementIsLocalDatedAndAttributable() {
        val data=extract(post("Storytime\nJoin us at Town Library October 24, 2026 at 10 AM. Free admission. Ages 1-3."),owner,area,at)
        val event=data.events.single()
        assertEquals("Storytime",event.title)
        assertEquals("2026-10-24T10:00",event.start.value);assertEquals("America/New_York",event.start.zoneId)
        assertEquals("Free",event.price);assertEquals(owner.point,event.point)
        assertTrue(event.evidence.any {it.field=="start"&&it.excerpt=="October 24, 2026"})
    }
    @Test fun MissingDatesAndVenuesStayCandidatesRatherThanInventedEvents() {
        val data=extract(post("Join us for our festival!"),owner,area,at)
        assertTrue(data.events.isEmpty());assertTrue(data.announcements.single().missing.contains("Date needs confirmation"))
        val remote=extract(post("Concert October 24, 2026 at 7 PM at a different venue"),owner,area,at)
        assertTrue(remote.events.isEmpty());assertTrue(remote.announcements.single().missing.contains("Venue needs confirmation"))
    }
    @Test fun RelativeDateUsesPublicationNotRetrievalAndUndatedMonthNeedsAnAnchor() {
        val yesterday=at-86400000
        val event=extract(post("Storytime tomorrow at 10 AM at Town Library",yesterday),owner,area,at).events.single()
        assertEquals("2026-10-22T10:00",event.start.value)
        assertTrue(extract(post("Storytime October 24 at 10 AM at Town Library",null),owner,area,at).events.isEmpty())
    }
    @Test fun MultiDateAnnouncementKeepsSessionsDistinctAndPreservesOcrRegion() {
        val text="Workshop\nOctober 24, 2026 at 10 AM at Town Library\nOctober 25, 2026 at 2 PM at Town Library"
        val ocr=OcrResult(text,listOf(OcrLine("October 24, 2026",90f,1,2,100,30)),"eng",200,300,90f,false,sourceSha256="abc")
        val result=extract(post(""),owner,area,at,ocr,"https://images.example/flyer.jpg")
        assertEquals(2,result.events.size);assertNotEquals(result.events[0].id,result.events[1].id)
        assertEquals("abc",result.events.first().evidence.first {it.field=="start"}.region?.sha256)
        assertTrue(extract(post(""),owner,area,at,ocr.copy(uncertain=true)).events.isEmpty())
    }
    @Test fun deadlinesUrlsAndConflictingWeekdaysAreNotOccurrenceDates() {
        val data=extract(post("Workshop\nRegister by October 23, 2026.\nOctober 24, 2026 at 10 AM at Town Library\nhttps://example.org/2026-10-25/"),owner,area,at)
        assertEquals(listOf("2026-10-24"),data.events.map {it.start.date().toString()})
        assertTrue(extract(post("Workshop Friday October 24, 2026 at 10 AM at Town Library"),owner,area,at).events.isEmpty())
        assertEquals("2026-10-24",extract(post("Workshop 24 October 2026 at 10 AM at Town Library"),owner,area,at).events.single().start.date().toString())
    }
    @Test fun ambiguousBrandLocationAndClockChangesNeedConfirmation() {
        val text=post("Workshop October 24, 2026 at 10 AM at Town Library")
        assertTrue(LocalAnnouncements.extract(text,owner,area,at).events.isEmpty())
        val changedClock=extract(post("Workshop November 1, 2026 at 1 AM at Town Library"),owner,area,at)
        assertTrue(changedClock.events.isEmpty());assertTrue(changedClock.announcements.single().missing.contains("Clock change needs confirmation"))
        assertEquals("2026-10-24",extract(post("Workshop 10/24/2026 at 10 AM at Town Library"),owner,area,at).events.single().start.date().toString())
    }
    @Test fun inertHtmlKeepsItsUsefulHeadingWithoutReadingScriptsAsAnnouncements() {
        val body="<p>Storytime</p><p>Join us at Town Library October 24, 2026 at 10 AM.</p><script>Festival October 25, 2026 at 11 AM at Town Library</script>"
        val event=extract(post(body),owner,area,at).events.single()
        assertEquals("Storytime",event.title);assertEquals("2026-10-24",event.start.date().toString())
        assertFalse(event.description.contains("Festival"))
    }
}
