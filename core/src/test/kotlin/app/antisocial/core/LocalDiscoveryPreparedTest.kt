/* anti social, GPL-3.0-or-later. Frozen reader-language matching controls. */
package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class LocalDiscoveryPreparedTest {
    @Test fun preparedQueriesKeepConceptsPhrasesExclusionsAndProtectedWords() {
        data class Case(val query:String,val text:String,val matches:Boolean)
        val cases=listOf(
            Case("rocket launch","Successful spaceflight",true),
            Case("rocket launch -cancelled","The spaceflight was cancelled",false),
            Case("rocket launch -cancelled","Successful spaceflight",true),
            Case("\"rocket launch\"","Successful spaceflight",false),
            Case("\"rocket launch\"","A rocket recovery, followed by a launch",false),
            Case("ice cream","A gelato tasting",true),
            Case("ice cream","Ice for a first aid cream container",false),
            Case("workshops -\"private session\"","A public class",true),
            Case("workshops -\"private session\"","A private session for the class",false),
            Case("coffee","A cafe",true),
            Case("coffee -cafe","A cafe",false),
            Case("coffee -cafe","A coffee store",true),
            Case("\"James’s workshop\"","James’s workshop",true),
            Case("\"James’s workshop\"","James workshop",false),
            Case("@alice_jones","Reply from @alice_jones",true),
            Case("@alice_jones","Reply from @alice_smith",false),
            Case("","Anything at all",true)
        )
        cases.forEach {case->assertEquals(case.matches,LocalDiscoveryFilter.prepare(case.query).matches(case.text),case.toString())}
    }
    @Test fun preparedQueriesKeepExistingDocumentAndLiteralIdentifierBounds() {
        val prefix="introductory material ".repeat(500)
        // Ordinary legacy words remain bounded to their first 256 tokens;
        // quoted phrases can inspect the existing larger, 32k-character view.
        assertFalse(LocalDiscoveryFilter.prepare("spaceflight").matches(prefix+"spaceflight"))
        assertTrue(LocalDiscoveryFilter.prepare("\"spaceflight\"").matches(prefix+"spaceflight"))
        assertFalse(LocalDiscoveryFilter.prepare("\"spaceflight\"").matches("x".repeat(32000)+" spaceflight"))
        val identifier="a".repeat(100)
        val query=LocalDiscoveryFilter.prepare("\"$identifier\"")
        assertTrue(query.matches(identifier));assertFalse(query.matches(identifier+"tail"))
    }
    @Test fun onePreparedQueryDoesNotRetainEarlierDocumentsAndCanBeShared():Unit=runBlocking {
        val query=LocalDiscoveryFilter.prepare("rocket launch -cancelled")
        val documents=listOf("Successful spaceflight" to true,"A cancelled spaceflight" to false,"Public library opening" to false)
        repeat(3){documents.forEach {(text,expected)->assertEquals(expected,query.matches(text))}}
        coroutineScope {(1..12).map {async(Dispatchers.Default){repeat(10){documents.forEach {(text,expected)->assertEquals(expected,query.matches(text))}}}}.awaitAll()}
    }
    @Test fun unfilteredEventIntentLeavesDateAndGeographyToTheirAdmissionLayer() {
        val event=LocalEvent("e","",LocalEventTime("2031-06-11",allDay=true),description="Unrelated prose")
        for(query in listOf("","this weekend"))assertEquals(LocalEventMatch(true),LocalEventIntent.parse(query).match(event))
        assertFalse(LocalEventIntent.parse("\"rocket launch\"").match(event).matches)
        assertFalse(LocalEventIntent.parse("free workshop").match(event).matches)
        assertFalse(LocalEventIntent.parse("outdoors for toddlers").match(event).matches)
        val area=LocalArea("fixture","Example City","Example City, Test Region",LocalPoint(10.0,20.0),5,"UTC")
        val now=java.time.Instant.parse("2031-06-10T12:00:00Z").toEpochMilli()
        assertFalse(LocalDiscoveryFilter.event(LocalDiscoveryQuery(area),event.copy(point=LocalPoint(-40.0,-100.0)),now))
        assertFalse(LocalDiscoveryFilter.event(LocalDiscoveryQuery(area,window=LocalEventWindow.TODAY),event.copy(point=area.point),now))
    }
}
