package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class LocalDiscoveryPresentationTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    @Test fun sourcesCombineRepeatedRetrievalsTrackingVariantsAndRepeatedExcerpts() {
        val url="https://example.org/events/repair"
        val rows=listOf(LocalFieldEvidence("description","Bring a broken radio",url+"?utm_source=feed",100,LocalEvidenceKind.PUBLIC_POST,"Bring a broken\nradio"),
            LocalFieldEvidence("Description","Bring a broken radio",url,200,LocalEvidenceKind.PUBLIC_POST,"Bring a broken radio"),
            LocalFieldEvidence("start","2026-10-30",url,200,LocalEvidenceKind.PUBLISHER),
            LocalFieldEvidence("description","Bring a broken radio","https://other.example/announcement",200,LocalEvidenceKind.PUBLIC_POST,"Bring a broken radio"))
        val sources=LocalDiscoveryPresentation.sources(rows,cleaner::clean)
        assertEquals(2,sources.size);assertEquals(200L,sources.first().checkedAt)
        assertEquals(2,sources.first().fields.size);assertEquals(listOf("Bring a broken radio"),sources.flatMap {it.excerpts})
        assertEquals(4,rows.size)
    }
    @Test fun oneEventDestinationCanServeRegistrationAndAnnouncementWithoutRepeatingItsAction() {
        val event=LocalEvent("e","Repair",LocalEventTime("2026-10-30",allDay=true),urls=listOf("https://example.org/events/repair?utm_source=social","https://example.org/events/repair"),registrationUrl="https://example.org/events/repair")
        val links=LocalDiscoveryPresentation.eventLinks(event,cleaner::clean)
        assertEquals(1,links.size);assertTrue(links.single().registration)
    }
    @Test fun aChainSharesOneDeckEntryWhileEveryLocationKeepsItsOwnFacts() {
        val first=LocalBusiness("one","Northstar Coffee - Downtown",address="1 Main St",websites=listOf("https://northstarcoffee.com/locations/downtown"),phones=listOf("+13215550101"))
        val second=first.copy(id="two",name="Northstar Coffee - Riverside",address="20 River Rd",phones=listOf("+13215550102"),websites=listOf("https://northstarcoffee.com/locations/riverside"),hours="Tuesday 8am - 4pm")
        val one=LocalBusinessDeck.groups(listOf(first)).single()
        val together=LocalBusinessDeck.groups(listOf(first,second)).single()
        assertEquals(one.id,together.id);assertEquals(listOf(first,second),together.locations)
        assertEquals(listOf("+13215550101","+13215550102"),together.locations.flatMap {it.phones})
        assertEquals("Tuesday 8am - 4pm",together.locations[1].hours)
    }
    @Test fun matchingNamesOnDirectoriesOrDifferentOfficialSitesStaySeparate() {
        val first=LocalBusiness("one","Burger Inn",address="1 Main St",websites=listOf("https://directory.example/burger-inn"),evidence=listOf(LocalFieldEvidence("name","Burger Inn","https://directory.example/burger-inn",1,LocalEvidenceKind.DIRECTORY)))
        assertEquals(2,LocalBusinessDeck.groups(listOf(first,first.copy(id="two",address="99 River Rd"))).size)
        assertEquals(2,LocalBusinessDeck.groups(listOf(first.copy(websites=listOf("https://burgerinn.com")),first.copy(id="two",websites=listOf("https://burgerinnfl.com")))).size)
    }
}
