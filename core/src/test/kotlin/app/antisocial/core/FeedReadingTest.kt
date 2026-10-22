package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class FeedReadingTest {
    private fun first(vararg keys: String)=FeedReading.reconcile(FeedMemory(),keys.toList(),100)

    @Test fun anEmptyInitialEmissionDoesNotConsumeTheFirstRealBatch() {
        val empty=FeedReading.reconcile(FeedMemory(),emptyList(),1)
        assertFalse(empty.initialized)
        val loaded=FeedReading.reconcile(empty,listOf("newer","older"),2)
        assertTrue(loaded.initialized);assertEquals(listOf("newer","older"),loaded.acceptedKeys)
        assertTrue(loaded.stagedKeys.isEmpty());assertTrue(loaded.readKeys.isEmpty());assertTrue(loaded.freshKeys.isEmpty())
    }
    @Test fun newerAndOlderBackfilledPostsStayStagedWithoutMovingTheCurrentBatch() {
        val current=first("middle","old")
        val staged=FeedReading.reconcile(current,listOf("new","middle","old","backfill"),200)
        assertEquals(listOf("middle","old"),staged.acceptedKeys)
        assertEquals(listOf("new","backfill"),staged.stagedKeys)
        assertTrue(staged.readKeys.isEmpty());assertTrue(staged.freshKeys.isEmpty())
        assertEquals(staged.acceptedKeys,FeedReading.reconcile(staged,emptyList(),300).acceptedKeys)
    }
    @Test fun aRestoredSessionAdmitsAllFetchedPostsWithoutReadingOrMovingItsAnchor() {
        val previous=FeedReading.keepReturn(FeedReading.anchor(FeedReading.markRead(first("read","old-unread","older"),listOf("read"),110),"old-unread",-37,120),130)
        val restored=FeedReading.enterSession(previous,listOf("newest","newer","read","old-unread","older","backfill"),200)
        assertEquals(listOf("newest","newer","read","old-unread","older","backfill"),restored.acceptedKeys)
        assertTrue(restored.stagedKeys.isEmpty())
        assertEquals(setOf("newest","newer","backfill"),restored.freshKeys)
        assertEquals(previous.readKeys,restored.readKeys)
        assertFalse("old-unread" in restored.freshKeys);assertFalse("old-unread" in restored.readKeys)
        assertEquals(FeedPosition("old-unread",3,-37),FeedReading.restore(restored,restored.acceptedKeys))
        assertEquals(previous.resumeReturn,restored.resumeReturn)
        assertEquals(FeedPosition("old-unread",3,-37),FeedReading.restoreReturn(restored,restored.acceptedKeys))
    }
    @Test fun sessionEntryAdmitsPreviouslyStagedPostsButLaterArrivalsStillWait() {
        val waiting=FeedReading.reconcile(first("middle","older"),listOf("already-fetched","middle","older"),150)
        val entered=FeedReading.enterSession(waiting,listOf("already-fetched","middle","older"),200)
        assertEquals(listOf("already-fetched","middle","older"),entered.acceptedKeys)
        assertEquals(setOf("already-fetched"),entered.freshKeys);assertTrue(entered.stagedKeys.isEmpty())
        val later=FeedReading.reconcile(entered,listOf("live-arrival","already-fetched","middle","older"),210)
        assertEquals(entered.acceptedKeys,later.acceptedKeys)
        assertEquals(listOf("live-arrival"),later.stagedKeys)
        assertEquals(entered.readKeys,later.readKeys)
    }
    @Test fun anAcceptedIdentityTemporarilyAbsentAtEntryReturnsWithoutBecomingANewArrival() {
        val previous=FeedReading.markRead(first("head","temporarily-missing","older"),listOf("temporarily-missing"),110)
        val entered=FeedReading.enterSession(previous,listOf("new","head","older"),200)
        assertEquals(listOf("new","head","older","temporarily-missing"),entered.acceptedKeys)
        assertEquals(setOf("new"),entered.freshKeys)
        assertTrue("temporarily-missing" in entered.readKeys)
        val returned=FeedReading.reconcile(entered,listOf("new","head","temporarily-missing","older"),210)
        assertTrue(returned.stagedKeys.isEmpty())
        assertFalse("temporarily-missing" in returned.freshKeys)
        assertTrue("temporarily-missing" in returned.readKeys)
        val unread=FeedReading.enterSession(first("head","missing-unread","older"),listOf("head","older"),220)
        val unreadReturn=FeedReading.reconcile(unread,listOf("head","missing-unread","older"),230)
        assertTrue(unreadReturn.stagedKeys.isEmpty())
        assertFalse("missing-unread" in unreadReturn.freshKeys)
        assertFalse("missing-unread" in unreadReturn.readKeys)
    }
    @Test fun anEmptySessionEmissionCannotDiscardPriorKeysOrConsumeTheRealBatch() {
        val previous=FeedReading.anchor(first("saved","older"),"saved",42,120)
        assertEquals(previous,FeedReading.enterSession(previous,emptyList(),200))
        val loaded=FeedReading.enterSession(previous,listOf("new","saved","older"),210)
        assertEquals(listOf("new","saved","older"),loaded.acceptedKeys)
        assertEquals(FeedPosition("saved",1,42),FeedReading.restore(loaded,loaded.acceptedKeys))
        assertFalse(FeedReading.enterSession(FeedMemory(),emptyList(),200).initialized)
    }
    @Test fun firstAndCompactedSessionBatchesDoNotInventFreshnessOrEraseSharedReadMarks() {
        val initial=FeedReading.enterSession(FeedMemory(readKeys=setOf("known-read")),listOf("newest","known-read","older"),200)
        assertTrue(initial.initialized);assertTrue(initial.freshKeys.isEmpty())
        assertEquals(setOf("known-read"),initial.readKeys)
        val previous=FeedReading.keepReturn(FeedReading.anchor(first("known-read","anchor"),"anchor",-18,110),120)
        val compact=FeedReading.compact(previous).copy(readKeys=setOf("known-read"))
        val restored=FeedReading.enterSession(compact,listOf("newest","known-read","anchor"),200)
        assertTrue(restored.freshKeys.isEmpty());assertEquals(setOf("known-read"),restored.readKeys)
        assertEquals(FeedPosition("anchor",2,-18),FeedReading.restore(restored,restored.acceptedKeys))
        assertEquals(previous.resumeReturn,restored.resumeReturn)
    }
    @Test fun sessionAdmissionIsBoundedIdempotentAndDoesNotMoveTheStoppingBaseline() {
        val previous=FeedReading.markRead(first("old-unread","read-tail"),listOf("read-tail"),110)
        val baseline=previous.readKeys.toSet()
        val entered=FeedReading.enterSession(previous,listOf("new","old-unread","read-tail"),200)
        assertEquals(2,FeedReading.boundary(entered.acceptedKeys,baseline))
        val read=FeedReading.markRead(entered,listOf("new","old-unread"),210)
        assertEquals(2,FeedReading.boundary(read.acceptedKeys,baseline))
        assertEquals(entered,FeedReading.enterSession(entered,entered.acceptedKeys,220))
        val bounded=FeedReading.enterSession(previous,(1..1500).map {"post-$it"}+listOf("bad\nkey","post-1"),230)
        assertEquals(FeedReading.MAX_ACCEPTED,bounded.acceptedKeys.size)
        assertTrue(bounded.stagedKeys.isEmpty());assertFalse("bad\nkey" in bounded.acceptedKeys)
        assertEquals(previous.readKeys,bounded.readKeys)
    }
    @Test fun acceptingCapturedArrivalsKeepsLaterArrivalsStagedAndOldUnreadDistinct() {
        val current=FeedReading.markRead(first("read","old-unread"),listOf("read"),110)
        val firstArrival=FeedReading.reconcile(current,listOf("arrival","read","old-unread","backfill"),200)
        val captured=firstArrival.stagedKeys
        val available=listOf("later","arrival","read","old-unread","backfill","later-old")
        val late=FeedReading.reconcile(firstArrival,available,210)
        val accepted=FeedReading.accept(late,available,captured,220)
        assertEquals(listOf("arrival","read","old-unread","backfill"),accepted.acceptedKeys)
        assertEquals(listOf("later","later-old"),accepted.stagedKeys)
        assertEquals(setOf("arrival","backfill"),accepted.freshKeys)
        assertFalse(FeedReading.isRead(accepted,"old-unread"));assertFalse("old-unread" in accepted.freshKeys)
    }
    @Test fun onlyAcceptedActuallyViewedIdentitiesBecomeRead() {
        val memory=FeedReading.reconcile(first("a","b"),listOf("staged","a","b"),200)
        val marked=FeedReading.markRead(memory,listOf("a","staged","not-in-feed"),210)
        assertEquals(setOf("a"),marked.readKeys)
        assertEquals(listOf("staged"),marked.stagedKeys)
        assertFalse(FeedReading.isRead(marked,"b"))
    }
    @Test fun acceptedArrivalFreshnessEndsOnlyWhenThatPostIsActuallyRead() {
        val pending=FeedReading.reconcile(first("old"),listOf("new","old"),200)
        val accepted=FeedReading.accept(pending,listOf("new","old"),listOf("new"),210)
        assertEquals(setOf("new"),accepted.freshKeys)
        val moved=FeedReading.anchor(accepted,"old",42,220)
        assertEquals(accepted.readKeys,moved.readKeys);assertEquals(accepted.freshKeys,moved.freshKeys)
        val read=FeedReading.markRead(moved,listOf("new"),230)
        assertTrue(read.freshKeys.isEmpty());assertEquals(setOf("new"),read.readKeys)
    }
    @Test fun exactAnchorIdentityAndSignedOffsetSurviveInsertionAndSanitization() {
        val anchored=FeedReading.anchor(first("a","b","c"),"b",-38,120)
        val clean=FeedReading.sanitize(anchored,200)
        assertEquals(FeedPosition("b",2,-38),FeedReading.restore(clean,listOf("new","a","b","c")))
        assertEquals(-38,clean.anchorOffset)
    }
    @Test fun evictedAnchorsUseNearbySurvivingKeysWithoutReusingWrongPixelOffset() {
        val anchored=FeedReading.anchor(first("a","b","c","d"),"b",81,120)
        assertEquals(FeedPosition("c",1,0),FeedReading.restore(anchored,listOf("a","c","d")))
        assertEquals(FeedPosition("a",0,0),FeedReading.restore(anchored,listOf("a","d")))
        assertNull(FeedReading.restore(anchored,emptyList()))
    }
    @Test fun jumpAndRepeatedNewestVisitsDoNotOverwriteTheContinueReadingAnchor() {
        val reading=FeedReading.anchor(first("newest","middle","older"),"older",-20,120)
        val retained=FeedReading.keepReturn(reading,130)
        val newest=FeedReading.anchor(retained,"newest",0,140)
        val repeated=FeedReading.keepReturn(newest,150)
        assertEquals(FeedPosition("older",2,-20),FeedReading.restoreReturn(repeated,repeated.acceptedKeys))
        assertEquals(FeedPosition("newest",0,0),FeedReading.restore(repeated,repeated.acceptedKeys))
        assertTrue(repeated.readKeys.isEmpty())
        assertNull(FeedReading.restoreReturn(FeedReading.clearReturn(repeated,160),repeated.acceptedKeys))
    }
    @Test fun aSessionBoundaryStaysAtThePriorReadTailAndDoesNotHideUnreadHoles() {
        val keys=listOf("new","unread","old-a","old-b")
        val baseline=setOf("old-a","old-b")
        assertEquals(2,FeedReading.boundary(keys,baseline))
        val memory=FeedReading.markRead(FeedMemory(true,acceptedKeys=keys,readKeys=baseline),listOf("new","unread"),300)
        assertEquals(2,FeedReading.boundary(keys,baseline))
        assertEquals(0,FeedReading.boundary(keys,memory.readKeys))
        assertNull(FeedReading.boundary(keys,setOf("new","old-a")))
    }
    @Test fun evictionDoesNotEraseReadHistoryButTheHistoryIsBoundedByRecency() {
        val history=(1..FeedReading.MAX_READ).map {"read-$it"}.toCollection(LinkedHashSet())
        val memory=FeedMemory(true,acceptedKeys=listOf("read-1","new"),readKeys=history)
        val revisited=FeedReading.markRead(memory,listOf("read-1"),200)
        assertEquals("read-1",revisited.readKeys.last())
        val newest=FeedReading.markRead(revisited,listOf("new"),210)
        assertEquals(FeedReading.MAX_READ,newest.readKeys.size);assertFalse("read-2" in newest.readKeys)
        val evicted=FeedReading.accept(newest,emptyList(),emptyList(),220)
        assertTrue(evicted.acceptedKeys.isEmpty());assertTrue("read-1" in evicted.readKeys);assertTrue("new" in evicted.readKeys)
    }
    @Test fun localRecordValidationBoundsKeysAndRepairsMissingCollections() {
        val missing=Gson().fromJson("""{"initialized":true,"acceptedKeys":null,"readKeys":null,"freshKeys":null}""",FeedMemory::class.java)
        val repaired=FeedReading.sanitize(missing,1000)
        assertTrue(repaired.acceptedKeys.isEmpty());assertTrue(repaired.readKeys.isEmpty())
        val oversized=FeedMemory(true,acceptedKeys=(1..1500).map {"post-$it"},stagedKeys=listOf("bad\nkey"),anchorOffset=Int.MIN_VALUE)
        val clean=FeedReading.sanitize(oversized,1000)
        assertEquals(1000,clean.acceptedKeys.size);assertTrue(clean.stagedKeys.isEmpty());assertEquals(-1_000_000,clean.anchorOffset)
    }
    @Test fun compactingInactiveHistoryRetainsExactAnchorAndContinuePosition() {
        val reading=FeedReading.keepReturn(FeedReading.anchor(first("a","b","c"),"b",-27,120),130)
        val compact=FeedReading.compact(FeedReading.markRead(reading,listOf("a"),140))
        assertFalse(compact.initialized);assertTrue(compact.acceptedKeys.isEmpty());assertTrue(compact.readKeys.isEmpty())
        val reloaded=FeedReading.reconcile(compact,listOf("new","a","b","c"),200)
        assertEquals(FeedPosition("b",2,-27),FeedReading.restore(reloaded,reloaded.acceptedKeys))
        assertEquals(FeedPosition("b",2,-27),FeedReading.restoreReturn(reloaded,reloaded.acceptedKeys))
        assertTrue(reloaded.stagedKeys.isEmpty())
    }

    @Test fun aNewOrUnattemptedFollowPreventsCaughtUpEvenWhenOtherSourcesSucceeded() {
        val started=RefreshLedger.begin(emptyMap(),listOf("a"),1,100)
        val success=RefreshLedger.success(started,"a",1,110)
        assertTrue(RefreshLedger.caughtUp(listOf("a"),success))
        assertEquals(1,RefreshLedger.pending(listOf("a","new"),success))
        assertFalse(RefreshLedger.caughtUp(listOf("a","new"),success));assertFalse(RefreshLedger.caughtUp(emptyList(),success))
    }
    @Test fun aMatchedCopyOfAReadPostDoesNotMoveItsAnchorOrBecomeANewArrival() {
        val before=FeedReading.keepReturn(FeedReading.anchor(FeedReading.markRead(first("x","older"),listOf("x"),110),"x",-37,120),130)
        val staged=FeedReading.reconcile(before,listOf("different","instagram","x","older"),200)
        val merged=FeedReading.mergeAliases(staged,listOf(listOf("x","instagram")),210)
        assertEquals(listOf("x","older","instagram"),merged.acceptedKeys)
        assertEquals(listOf("different"),merged.stagedKeys)
        assertEquals(setOf("x","instagram"),merged.readKeys)
        assertTrue(merged.freshKeys.isEmpty())
        assertEquals(before.anchorKey,merged.anchorKey);assertEquals(-37,merged.anchorOffset)
        assertEquals(before.anchorNeighbors,merged.anchorNeighbors);assertEquals(before.resumeReturn,merged.resumeReturn)
        assertEquals(FeedPosition("instagram",0,-37),FeedReading.restore(merged.copy(anchorKey="instagram"),listOf("instagram","older")))
    }
    @Test fun matchingAnUnreadMessageDoesNotMarkItReadOrAdoptAnUnrelatedMessage() {
        val staged=FeedReading.reconcile(first("x"),listOf("instagram","new-x","new-blue","x"),200)
        val merged=FeedReading.mergeAliases(staged,listOf(listOf("x","instagram"),listOf("new-x","new-blue")),210)
        assertEquals(listOf("x","instagram"),merged.acceptedKeys)
        assertEquals(listOf("new-x","new-blue"),merged.stagedKeys)
        assertTrue(merged.readKeys.isEmpty());assertTrue(merged.freshKeys.isEmpty())
        assertEquals(merged,FeedReading.mergeAliases(merged,listOf(listOf("x","instagram")),220))
    }
    @Test fun aliasesCannotInitializeABatchOrExceedTheExistingMemoryBudget() {
        assertFalse(FeedReading.mergeAliases(FeedMemory(),listOf(listOf("x","blue")),200).initialized)
        val full=first(*(1..FeedReading.MAX_ACCEPTED).map {"key-$it"}.toTypedArray())
        val merged=FeedReading.mergeAliases(full,listOf(listOf("key-1","extra","bad\nkey")),210)
        assertEquals(full,merged);assertEquals(FeedReading.MAX_ACCEPTED,merged.acceptedKeys.size)
    }
    @Test fun failedSkippedOrCancelledLatestChecksRemainPendingDespiteAnOlderSuccess() {
        val success=RefreshLedger.success(RefreshLedger.begin(emptyMap(),listOf("a"),1,100),"a",1,110)
        for(reason in listOf("Source unavailable","Waiting for Wi-Fi","Refresh cancelled")) {
            val failed=RefreshLedger.fail(RefreshLedger.begin(success,listOf("a"),2,200),"a",2,reason,210)
            assertEquals(110L,failed["a"]!!.lastSuccessAt);assertEquals(reason,failed["a"]!!.reason)
            assertFalse(RefreshLedger.caughtUp(listOf("a"),failed));assertEquals(FollowRefreshStatus.PENDING,failed["a"]!!.status)
        }
    }
    @Test fun staleRefreshCompletionsCannotCertifyOrInvalidateANewerAttempt() {
        val first=RefreshLedger.begin(emptyMap(),listOf("a"),1,100)
        val second=RefreshLedger.begin(first,listOf("a"),2,200)
        assertEquals(second,RefreshLedger.success(second,"a",1,210))
        assertEquals(second,RefreshLedger.fail(second,"a",1,"Old failure",210))
        val success=RefreshLedger.success(second,"a",2,220)
        assertEquals(success,RefreshLedger.fail(success,"a",2,"Duplicate late failure",230))
        assertEquals(220L,success["a"]!!.lastSuccessAt)
    }
    @Test fun restartPreservesCompletedCheckTimeButInterruptedChecksBecomePending() {
        val success=FollowRefresh(1,FollowRefreshStatus.SUCCESS,110,null,110)
        assertEquals(success,RefreshLedger.restore(success,1000))
        val interrupted=RefreshLedger.restore(success.copy(token=2,status=FollowRefreshStatus.CHECKING,updatedAt=200),1000)
        assertEquals(FollowRefreshStatus.PENDING,interrupted.status);assertEquals(110L,interrupted.lastSuccessAt)
        assertTrue(interrupted.reason!!.contains("did not finish"))
    }
}
