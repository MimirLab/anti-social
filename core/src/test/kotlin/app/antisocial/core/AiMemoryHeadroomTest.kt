package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class AiMemoryHeadroomTest {
    private val mib = 1024L * 1024
    private val gib = 1024 * mib

    @Test fun phoneBudgetLeavesSystemThresholdAndReaderGrowthUntouched() {
        assertEquals(6 * gib, AiMemoryHeadroom.allocationBudget(16 * gib, 8 * gib, gib, false))
        assertEquals(5 * gib / 2, AiMemoryHeadroom.allocationBudget(8 * gib, 4 * gib, gib, false))
    }

    @Test fun advertisedTotalCannotReplaceAvailableMemoryDuringPressure() {
        assertEquals(0L, AiMemoryHeadroom.allocationBudget(24 * gib, gib, gib / 2, false))
        assertEquals(0L, AiMemoryHeadroom.allocationBudget(24 * gib, 12 * gib, gib / 2, true))
        assertEquals(0L, AiMemoryHeadroom.allocationBudget(8 * gib, gib, gib, false))
    }

    @Test fun invalidOrOverflowingSnapshotsCannotCreateAnAllocationAllowance() {
        assertEquals(0L, AiMemoryHeadroom.allocationBudget(0, gib, 0, false))
        assertEquals(0L, AiMemoryHeadroom.allocationBudget(8 * gib, gib, -1, false))
        assertEquals(0L, AiMemoryHeadroom.allocationBudget(Long.MAX_VALUE, gib, Long.MAX_VALUE, false))
        assertEquals(7 * gib / 2, AiMemoryHeadroom.allocationBudget(4 * gib, Long.MAX_VALUE, 0, false))
        assertNull(AiMemoryHeadroom.plainTargetEstimate(Long.MAX_VALUE))
        assertNull(AiMemoryHeadroom.plainTargetEstimate(0))
    }

    @Test fun targetWeightsStillNeedRuntimeSpaceWithinTheRemainingBudget() {
        val budget = AiMemoryHeadroom.allocationBudget(8 * gib, 4 * gib, gib, false)
        assertTrue(AiMemoryHeadroom.plainTargetEstimate(gib)!! < budget)
        assertTrue(AiMemoryHeadroom.plainTargetEstimate(3 * gib)!! > budget)
    }
}
