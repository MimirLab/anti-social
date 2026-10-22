package app.antisocial.core

import org.junit.Test
import kotlin.test.*

class LocalEventIntentTest {
    private fun event(title:String,age:String="",description:String="",price:String="")=LocalEvent("e",title,LocalEventTime("2026-10-24",allDay=true),age=age,description=description,price=price)
    @Test fun readerLanguageMatchesBroaderFamilyNatureWalkWithoutInventingAge() {
        val intent=LocalEventIntent.parse("something outdoors for toddlers this weekend")
        assertTrue(intent.family);assertEquals(LocalEventWindow.WEEKEND,intent.window)
        val result=intent.match(event("Children’s nature walk"))
        assertTrue(result.matches);assertEquals(listOf("Age range not stated"),result.unknowns)
        assertFalse(intent.match(event("Children’s nature walk","Ages 8–12")).matches)
        assertFalse(intent.match(event("Children’s nature walk","8-12")).matches)
        assertFalse(intent.match(event("Family garden walk","18+")).matches)
        assertFalse(intent.match(event("Family garden walk",description="Adults-only event")).matches)
        assertFalse(intent.match(event("Tax accounting seminar")).matches)
    }
    @Test fun explicitConstraintsAndExclusionsAreNotExpandedAway() {
        val intent=LocalEventIntent.parse("free music without concerts")
        assertFalse(intent.match(event("Free concert",price="Free")).matches)
        assertFalse(LocalEventIntent.parse("free workshop").match(event("Workshop",price="Unknown")).matches)
        assertTrue(LocalEventIntent.parse("free workshop").match(event("Workshop",price="Free")).matches)
        assertFalse(LocalEventIntent.parse("free workshop").match(event("Workshop",description="Free parking and refreshments; admission $20")).matches)
        assertTrue(LocalEventIntent.parse("free workshop").match(event("Workshop",description="Free admission")).matches)
        assertFalse(LocalEventIntent.parse("outdoors for kids").match(event("Children’s indoor play")).matches)
        assertTrue(LocalEventIntent.parse("outdoors for kids").match(event("Family craft afternoon")).unknowns.contains("Outdoor setting not confirmed"))
    }
    @Test fun ProperNamesAndUnrelatedTopicsStayPrecise() {
        assertFalse(LocalDiscoveryFilter.matches("Burger Inn","Burger King"))
        assertFalse(LocalEventIntent.parse("nature walk").match(event("Children’s software workshop")).matches)
        assertTrue(LocalEventIntent.parse("nature walk").match(event("Wildlife hike")).matches)
    }
    @Test fun ClockRangesAreNotAgesAndRoutingRecognizesToddlerQueries() {
        assertNull(LocalEventIntent.ageRange("Join us 5-8 PM"))
        assertEquals(5..8,LocalEventIntent.ageRange("Ages 5-8"))
        val area=LocalArea("fl","Melbourne","Melbourne, Florida, US",LocalPoint(28.08,-80.60),25,"America/New_York",countryCode="US")
        val query=LocalDiscoveryQuery(area,"something outdoors for toddlers",LocalSearchMode.EVENTS)
        assertTrue(LocalOpportunityRouting.seeds(query).any {it.url.contains("fun4spacecoastkids")})
    }
    @Test fun everydayWordingAndVendorCountsDoNotBecomeRestrictions() {
        val intent=LocalEventIntent.parse("fun things to do outside with my toddler")
        assertTrue(intent.topics.isEmpty());assertTrue(intent.outdoors)
        assertTrue(intent.match(event("Family garden festival",description="18+ vendors with crafts and food")).matches)
        assertFalse(intent.match(event("Family garden festival",description="18+ admission only")).matches)
    }

}
