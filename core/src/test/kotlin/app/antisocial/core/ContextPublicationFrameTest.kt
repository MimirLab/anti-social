package app.antisocial.core

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ContextPublicationFrameTest {
    private val start=java.time.Instant.parse("2026-10-01T12:00:00Z")
    @Test fun boundedViewsAlwaysKeepBothTemporalEdgesAndAnUnknownContext() {
        val data=(0..62).map {i->ContextSourcePublication("Library $i",ContextPublicationTime(start.plusSeconds(i*86_400L)))}+ContextSourcePublication("Undated noticeboard",null)
        val selected=ContextPublicationFrame.representatives(data,3)
        assertEquals(3,selected.size);assertEquals(start,selected.first().time!!.instant)
        assertTrue(selected.any {it.time?.instant==start.plusSeconds(62*86_400L)});assertTrue(selected.any {it.time==null})
        assertEquals(selected,ContextPublicationFrame.representatives(data.reversed(),3))
        val json=JsonObject();ContextPublicationFrame.write(json,data)
        val bounds=json["publication_context"].asJsonObject
        assertEquals(63,bounds["dated_contexts"].asInt);assertEquals(1,bounds["undated_contexts"].asInt)
        assertEquals(start.toString(),bounds["earliest_known"].asString)
        assertEquals(start.plusSeconds(62*86_400L).toString(),bounds["latest_known"].asString)
        assertEquals(60,json["additional_source_contexts"].asInt)
    }
    @Test fun allUnknownContextsNeverProduceAFabricatedDateRange() {
        val data=(1..20).map {ContextSourcePublication("Source $it",null)}
        val json=JsonObject();ContextPublicationFrame.write(json,data)
        val context=json["publication_context"].asJsonObject
        assertEquals(0,context["dated_contexts"].asInt);assertEquals(20,context["undated_contexts"].asInt)
        assertFalse(context.has("earliest_known"));assertFalse(context.has("latest_known"))
        assertTrue(json["source_times"].asJsonArray.all {it.asJsonObject["publication_time_unavailable"].asBoolean})
    }
    @Test fun copiesAtOneInstantDoNotBecomeDifferentEventDatesAndPartialScopeIsRetained() {
        val data=(1..8).map {ContextSourcePublication("Source $it",ContextPublicationTime(start,it==1))}
        val json=JsonObject();ContextPublicationFrame.write(json,data)
        val context=json["publication_context"].asJsonObject
        assertEquals(8,context["dated_contexts"].asInt);assertEquals(1,context["distinct_instants"].asInt)
        assertEquals(1,context["earliest_edition_only_contexts"].asInt)
        assertEquals(context["earliest_known"],context["latest_known"])
    }
}
