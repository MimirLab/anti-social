package app.antisocial.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CardWorkTest {
    @Test fun overlappingWorkBelongsOnlyToItsOwnersAndLastCompletionClearsIt()=runBlocking {
        val work=CardWork()
        val a=work.begin(listOf("a","b"),"Images")
        val b=work.begin(listOf("a"),"Names")
        assertEquals(listOf("Images","Names"),work.activity(setOf("a")).first())
        assertEquals(listOf("Images"),work.activity(setOf("b")).first())
        assertTrue(work.activity(setOf("other")).first().isEmpty())
        a.close();a.close();assertEquals(listOf("Names"),work.activity(setOf("a")).first())
        b.close();assertTrue(work.active.value.isEmpty())
    }
    @Test fun cancellationAndFailureCannotLeaveAPhantomUpdate()=runBlocking {
        val work=CardWork();val entered=CompletableDeferred<Unit>()
        val job=launch {work.track(listOf("a"),"Details"){entered.complete(Unit);awaitCancellation()}}
        entered.await();assertEquals(1,work.active.value.size);job.cancelAndJoin();assertTrue(work.active.value.isEmpty())
        try {work.track(listOf("a"),"Details"){error("Unavailable")};fail("Must propagate failure")}
        catch(_:IllegalStateException){}
        assertTrue(work.active.value.isEmpty())
    }
    @Test fun completingOneSearchCannotClearItsReplacement() {
        val work=CardWork();val first=work.begin(listOf("a"),"Details");val second=work.begin(listOf("a"),"Details")
        first.close();assertEquals(1,work.active.value.size);second.close();assertTrue(work.active.value.isEmpty())
        work.begin(emptyList(),"No owner").close();assertTrue(work.active.value.isEmpty())
    }
}
