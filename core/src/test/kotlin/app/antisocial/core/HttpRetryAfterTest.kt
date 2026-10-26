package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class HttpRetryAfterTest {
    @Test fun deltaSecondsAndHostileValuesAreBounded() {
        assertEquals(120000L,HttpRetryAfter.parse("120",0));assertEquals(0L,HttpRetryAfter.parse("000",0))
        assertEquals(86400000L,HttpRetryAfter.parse("9".repeat(100),0));assertNull(HttpRetryAfter.parse("9".repeat(129),0))
        for(value in listOf("-1","1.5","1 seconds","","123, 456"))assertNull(HttpRetryAfter.parse(value,0))
    }
    @Test fun allThreeHttpDateFormsRemainAccepted() {
        val now=java.time.Instant.parse("1994-11-06T08:49:37Z").toEpochMilli()
        for(date in listOf("Sun, 06 Nov 1994 08:51:37 GMT","Sunday, 06-Nov-94 08:51:37 GMT","Sun Nov 6 08:51:37 1994"))assertEquals(120000L,HttpRetryAfter.parse(date,now))
        assertEquals(0L,HttpRetryAfter.parse("Sun, 06 Nov 1994 08:49:37 GMT",now+1));assertNull(HttpRetryAfter.parse("Sun, 06 Nov 1994 08:51:37 GMT junk",now))
    }
}
