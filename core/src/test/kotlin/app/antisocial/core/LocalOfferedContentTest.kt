/* anti social, GPL-3.0-or-later. Retain offered capabilities and announcement links. */
package app.antisocial.core

import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Test
import kotlin.test.*

class LocalOfferedContentTest {
    private val area=LocalArea("fixture-offered","Example City","Example City, Test Region",LocalPoint(10.0,20.0),5,"UTC",countryCode="ZZ")
    private val at=Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
    private val url="https://publisher.fixture.invalid/details"
    private val space="""{"api_compatibility":["15"],"space":"Community workshop","url":"https://publisher.fixture.invalid/","location":{"lat":10,"lon":20,"address":"1 Example Street, Example City","country_code":"ZZ"},"contact":{}}"""
    private val tools="""[{"name":"Workshop","description":"Laser cutter and woodworking tools","square_meters":42}]"""
    private fun space(locationAreas:String?=null,rootAreas:String?=null):LocalExtraction {
        val record=JsonParser.parseString(space).asJsonObject
        locationAreas?.let {record.getAsJsonObject("location").add("areas",JsonParser.parseString(it))}
        rootAreas?.let {record.add("areas",JsonParser.parseString(it))}
        return LocalOpportunityDocuments.space(record.toString(),url,at,area)
    }
    private fun services(b:LocalBusiness)=b.evidence.filter {it.field=="services"}.map {it.value}

    @Test fun documentedSpaceAreasMakePublishedEquipmentSearchable() {
        val data=space(locationAreas=tools);val business=data.businesses.single()
        assertEquals(listOf("Laser cutter and woodworking tools"),services(business))
        assertTrue(LocalBusinessRouting.matches("laser cutter",business))
        assertFalse(LocalBusinessRouting.matches("pottery kiln",business))
        assertTrue(data.events.isEmpty());assertTrue(business.hours.isBlank())
        assertTrue(business.evidence.filter {it.field=="services"}.all {it.sourceUrl==url&&it.checkedAt==at})
    }
    @Test fun previouslySupportedRootAreasRemainSearchable() {
        val business=space(rootAreas=tools).businesses.single()
        assertEquals(listOf("Laser cutter and woodworking tools"),services(business))
        assertTrue(LocalBusinessRouting.matches("laser cutter",business))
    }
    @Test fun distinctPublishedAreasAreRetainedWithoutRepeatingSharedDescriptions() {
        val root="""[{"description":"Laser cutter and woodworking tools"},{"name":"Electronics bench"}]"""
        val business=space(locationAreas=tools,rootAreas=root).businesses.single()
        assertEquals(listOf("Laser cutter and woodworking tools · Electronics bench"),services(business))
        assertTrue(LocalBusinessRouting.matches("electronics",business))
    }
    @Test fun malformedAreaValuesCannotInventCapabilitiesOrChangeLocationAdmission() {
        val data=space(locationAreas="[null,42,{}, {\"description\":null}]",rootAreas="null")
        assertTrue(services(data.businesses.single()).isEmpty())
        val far=space.replace("\"lat\":10","\"lat\":40")
        assertTrue(LocalOpportunityDocuments.space(far,url,at,area).businesses.isEmpty())
    }
    private val rss="""<rss><channel><item><title>Radio ﻿workshop</title><pubDate>Sun, 01 Nov 2026 12:00:00 GMT</pubDate><link>https://publisher.fixture.invalid/workshop</link></item></channel></rss>"""
    private fun read(body:String)=LocalStructuredData.html(body,url,at,area)
    @Test fun markedRssRetainsOfferedAnnouncementsAndInteriorCharacters() {
        val ordinary=read(rss)
        assertEquals(listOf("https://publisher.fixture.invalid/workshop"),ordinary.sources.map {it.url})
        assertEquals("Radio ﻿workshop",ordinary.sources.single().label)
        assertEquals(ordinary,read("\uFEFF"+rss))
        assertTrue(ordinary.events.isEmpty())
    }
    @Test fun markedRssWithAnXmlDeclarationKeepsItsOfferedLink() {
        val body="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"+rss
        assertEquals(read(body),read("\uFEFF"+body))
        assertEquals(1,read("\uFEFF"+body).sources.size)
    }
    @Test fun ordinaryAndMarkedAtomKeepWorkingWithoutInventingOccurrences() {
        val atom="""<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Workshop details</title><published>2026-11-01T12:00:00Z</published><link href="https://publisher.fixture.invalid/workshop"/></entry></feed>"""
        val ordinary=read(atom)
        assertEquals(listOf("https://publisher.fixture.invalid/workshop"),ordinary.sources.map {it.url})
        assertEquals(ordinary,read("\uFEFF"+atom));assertTrue(ordinary.events.isEmpty())
    }
}
