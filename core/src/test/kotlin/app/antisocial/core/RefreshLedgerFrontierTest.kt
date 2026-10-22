/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class RefreshLedgerFrontierTest {
    private val oldHead=listOf("twitter:post:100")
    private val partialHead=listOf("twitter:post:200")

    @Test fun failedPartialRefreshKeepsItsOriginalFrontierAcrossRetryAndRestartUntilSuccess() {
        val begun=RefreshLedger.begin(emptyMap(),listOf("source"),1,100)
        val captured=RefreshLedger.catchUpHead(begun,"source",1,oldHead,101)
        val failed=RefreshLedger.fail(captured,"source",1,"Continuation unavailable after newer posts were cached",110)
        assertFalse(RefreshLedger.caughtUp(listOf("source"),failed))
        assertEquals(oldHead,failed.getValue("source").catchUpHead)
        val restored=mapOf("source" to RefreshLedger.restore(Gson().fromJson(Gson().toJson(failed.getValue("source")),FollowRefresh::class.java),200))
        val retry=RefreshLedger.begin(restored,listOf("source"),2,210)
        val recaptured=RefreshLedger.catchUpHead(retry,"source",2,partialHead,211)
        assertEquals(oldHead,recaptured.getValue("source").catchUpHead)
        assertSame(retry,recaptured)
        val succeeded=RefreshLedger.success(recaptured,"source",2,220)
        assertNull(succeeded.getValue("source").catchUpHead)
        assertTrue(RefreshLedger.caughtUp(listOf("source"),succeeded))
        val later=RefreshLedger.catchUpHead(RefreshLedger.begin(succeeded,listOf("source"),3,300),"source",3,partialHead,301)
        assertEquals(partialHead,later.getValue("source").catchUpHead)
    }

    @Test fun onlyTheCurrentCheckingTokenCanCaptureOrClearAFrontier() {
        val begun=RefreshLedger.begin(emptyMap(),listOf("source"),2,100)
        assertSame(begun,RefreshLedger.catchUpHead(begun,"missing",2,oldHead,101))
        assertSame(begun,RefreshLedger.catchUpHead(begun,"source",1,oldHead,101))
        val captured=RefreshLedger.catchUpHead(begun,"source",2,oldHead,102)
        assertSame(captured,RefreshLedger.success(captured,"source",1,103))
        assertSame(captured,RefreshLedger.catchUpHead(captured,"source",2,partialHead,104))
        val failed=RefreshLedger.fail(captured,"source",2,"Paused",105)
        assertSame(failed,RefreshLedger.catchUpHead(failed,"source",2,partialHead,106))
        assertSame(failed,RefreshLedger.success(failed,"source",2,107))
        assertEquals(oldHead,failed.getValue("source").catchUpHead)
    }

    @Test fun anEmptyCapturedHeadIsAnInitialSubscriptionRatherThanAnUnsetFrontier() {
        val begun=RefreshLedger.begin(emptyMap(),listOf("source"),1,100)
        assertNull(begun.getValue("source").catchUpHead)
        val first=RefreshLedger.catchUpHead(begun,"source",1,emptyList(),101)
        assertEquals(emptyList<String>(),first.getValue("source").catchUpHead)
        val failed=RefreshLedger.fail(first,"source",1,"First read interrupted",102)
        val restored=mapOf("source" to RefreshLedger.restore(failed.getValue("source"),200))
        val retry=RefreshLedger.begin(restored,listOf("source"),2,201)
        assertSame(retry,RefreshLedger.catchUpHead(retry,"source",2,partialHead,202))
        assertEquals(emptyList<String>(),retry.getValue("source").catchUpHead)
        assertNull(RefreshLedger.success(retry,"source",2,203).getValue("source").catchUpHead)
    }

    @Test fun capturedAndRestoredFrontiersAreDistinctBoundedValidIdentities() {
        val valid=(1..12).map {"twitter:post:$it"}
        val invalid=listOf(""," ","bad\nkey","x".repeat(FeedReading.MAX_KEY+1))
        val keys=invalid+valid.first()+valid
        val begun=RefreshLedger.begin(emptyMap(),listOf("source"),1,100)
        val captured=RefreshLedger.catchUpHead(begun,"source",1,keys,101).getValue("source")
        assertEquals(valid.take(8),captured.catchUpHead)
        val restored=RefreshLedger.restore(captured.copy(catchUpHead=keys),200)
        assertEquals(valid.take(8),restored.catchUpHead)
        assertEquals(FollowRefreshStatus.PENDING,restored.status)
    }

    @Test fun olderGsonRecordsAndNullableCollectionEntriesRestoreSafely() {
        val gson=Gson()
        val old=gson.fromJson("{\"token\":1,\"status\":\"PENDING\",\"updatedAt\":100}",FollowRefresh::class.java)
        assertNull(RefreshLedger.restore(old,200).catchUpHead)
        val missingStatus=gson.fromJson("{\"token\":2,\"updatedAt\":100,\"catchUpHead\":[null,\"\",\"twitter:post:100\",\"twitter:post:100\"]}",FollowRefresh::class.java)
        val restored=RefreshLedger.restore(missingStatus,200)
        assertEquals(FollowRefreshStatus.PENDING,restored.status)
        assertEquals(oldHead,restored.catchUpHead)
    }

    @Test fun anInterruptedCheckingRecordKeepsTheUnfinishedFrontierAndLastSuccess() {
        val record=FollowRefresh(4,FollowRefreshStatus.CHECKING,lastSuccessAt=50,updatedAt=100,catchUpHead=oldHead)
        val restored=RefreshLedger.restore(record,200)
        assertEquals(FollowRefreshStatus.PENDING,restored.status)
        assertEquals(50L,restored.lastSuccessAt)
        assertEquals(oldHead,restored.catchUpHead)
        assertFalse(RefreshLedger.caughtUp(listOf("source"),mapOf("source" to restored)))
    }
}
