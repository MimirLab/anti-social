package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ContextPublicationTimeTest {
    @Test fun knownInstantsNormalizeAndDisplayWithAnExplicitZone() {
        val utc=ContextPublicationTime.parse("2026-10-19T12:00:00Z")!!
        assertEquals(utc,ContextPublicationTime.parse("2026-10-19T08:00:00-04:00"))
        assertEquals("Library (posted 19 Oct 2026, 12:00:00 UTC)",utc.label("Library"))
        assertEquals("Library (earliest known edition 19 Oct 2026, 12:00:00 UTC)",utc.copy(earliestKnownEdition=true).label("Library"))
        assertNotEquals(utc.identity,utc.copy(earliestKnownEdition=true).identity)
    }
    @Test fun missingInvalidOrUnzonedTimesCannotBecomePublicationFacts() {
        for(value in listOf(null,"","tomorrow","2026-10-19","2026-10-19T12:00:00","x".repeat(81),java.time.Instant.MAX.toString()))
            assertNull(value,ContextPublicationTime.parse(value))
    }
    @Test fun packedEditionsRetainFullBoundedNamesAndUndatedAttributionAcrossMetadataChunks() {
        val editions=(0 until 64).map {i->ContextSourcePublication(("Author $i "+"<&\"\\".repeat(30)).take(100),
            if(i%3==0)null else ContextPublicationTime(java.time.Instant.ofEpochMilli(Long.MAX_VALUE-i)))}
        val encoded=ContextSourcePublication.encode(editions)
        assertTrue(encoded.values.all {it.length<=8192});assertEquals(2,encoded.size)
        val restored=ContextSourcePublication.from(ContextDocument("merged:owner","1","Same caption",metadata=encoded))
        assertEquals(editions.toSet(),restored.toSet());assertTrue(restored.any {it.time==null})
        assertTrue(restored.filter {it.time==null}.all {it.displayLabel().endsWith("(publication time unavailable)")})
    }
    @Test fun corruptEditionMetadataDoesNotTurnAPartialListIntoCompleteKnownTimes() {
        val doc=ContextDocument("merged:owner","1","Tomorrow",author="Library",kind="post",metadata=mapOf("editionTimes" to "[[\"Other\",\"2026-10-20T12:00:00Z\"]]","editionTimes2" to "broken","published" to "2026-10-19T12:00:00Z"))
        val fallback=ContextSourcePublication.from(doc).single()
        assertEquals("Library",fallback.speaker);assertTrue(fallback.time!!.earliestKnownEdition)
        assertEquals("2026-10-19T12:00:00Z",fallback.time!!.instant.toString())
    }
}
