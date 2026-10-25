package app.antisocial.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

class LocalDiscoveryReadAdmissionTest {
    @Test fun aFastPublisherPassesTwoStalledReadsWithoutCancellingTheirWork()=runBlocking {
        val admission=LocalDiscoveryReadAdmission(hedgeDelayMs=20)
        val started=Channel<Unit>(2);val release=CompletableDeferred<Unit>()
        val slow=(1..2).map {launch {admission.read({false}){started.send(Unit);release.await()}}}
        repeat(2){started.receive()}
        assertEquals("useful",withTimeout(3000){admission.read({true}){"useful"}})
        assertFalse(release.isCompleted);assertTrue(slow.all {it.isActive})
        release.complete(Unit);slow.joinAll()
    }
    @Test fun theAdditionalLaneHasAFixedGlobalBound()=runBlocking {
        val admission=LocalDiscoveryReadAdmission(hedgeDelayMs=10)
        val entered=Channel<Unit>(Channel.UNLIMITED);val release=CompletableDeferred<Unit>()
        val active=AtomicInteger();val peak=AtomicInteger()
        val jobs=(1..9).map {launch {admission.read({true}){
            val count=active.incrementAndGet();peak.updateAndGet {maxOf(it,count)};entered.send(Unit)
            try {release.await()}finally {active.decrementAndGet()}
        }}}
        withTimeout(3000){repeat(3){entered.receive()}}
        assertNull(withTimeoutOrNull(100){entered.receive()});assertEquals(3,active.get())
        release.complete(Unit);jobs.joinAll();assertEquals(3,peak.get());assertEquals(0,active.get())
    }
    @Test fun aQueuedRescueStopsBeingEligibleAfterTheFirstDeckArrives()=runBlocking {
        val admission=LocalDiscoveryReadAdmission(hedgeDelayMs=30)
        val ready=AtomicBoolean();val entered=Channel<Unit>(2);val release=CompletableDeferred<Unit>()
        val slow=(1..2).map {launch {admission.read({false}){entered.send(Unit);release.await()}}}
        repeat(2){entered.receive()}
        val thirdStarted=CompletableDeferred<Unit>()
        val third=async(start=CoroutineStart.UNDISPATCHED){admission.read({!ready.get()}){thirdStarted.complete(Unit)}}
        ready.set(true)
        assertNull(withTimeoutOrNull(100){thirdStarted.await()})
        release.complete(Unit);withTimeout(3000){third.await();slow.joinAll()}
    }
    @Test fun cancellationOfQueuedAndActiveReadersDoesNotLeakEitherPool():Unit=runBlocking {
        val admission=LocalDiscoveryReadAdmission(hedgeDelayMs=10)
        val entered=Channel<Unit>(Channel.UNLIMITED)
        val held=(1..6).map {launch {admission.read({true}){entered.send(Unit);awaitCancellation()}}}
        withTimeout(3000){repeat(3){entered.receive()}}
        held.forEach {it.cancel()};held.joinAll()
        withTimeout(3000){coroutineScope {(1..8).map {async {admission.read({false}){yield();1}}}.awaitAll()}}
    }
    @Test fun competingAdmissionBranchesExecuteEachActionExactlyOnce()=runBlocking {
        val admission=LocalDiscoveryReadAdmission(hedgeDelayMs=0)
        val calls=ConcurrentHashMap<Int,AtomicInteger>()
        withTimeout(5000){coroutineScope {(1..150).map {id->async(Dispatchers.Default){admission.read({true}){
            calls.computeIfAbsent(id){AtomicInteger()}.incrementAndGet();yield();id
        }}}.awaitAll()}}
        assertEquals(150,calls.size);assertTrue(calls.values.all {it.get()==1})
    }
    @Test fun aFailurePropagatesAndReleasesItsPermit()=runBlocking {
        val admission=LocalDiscoveryReadAdmission(parallelism=1,hedgeDelayMs=0)
        try {admission.read({true}){throw IllegalStateException("fixture failure")};fail("Failure must propagate")}
        catch(e:IllegalStateException){assertEquals("fixture failure",e.message)}
        assertEquals(42,withTimeout(3000){admission.read({false}){42}})
    }
}
