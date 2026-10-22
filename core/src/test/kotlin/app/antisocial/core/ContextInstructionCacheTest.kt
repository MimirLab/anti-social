package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ContextInstructionCacheTest {
    @Test fun cacheUsesAtMostOneSixteenthOfReclaimableMemoryAndNeverExceedsItsCeiling() {
        val cap = ContextInstructionCache.MAX_BYTES
        for (available in listOf(1L, 1024L, 128L shl 20, 768L shl 20, 2L shl 30, Long.MAX_VALUE)) {
            for (cached in listOf(-1L, 0L, 20L shl 20, cap.toLong(), Long.MAX_VALUE)) {
                val budget = ContextInstructionCache.budget(available, cached)
                assertTrue(budget in 0..cap)
                if (available <= Long.MAX_VALUE - cap) assertTrue(budget.toLong() * 16 <= available + cached.coerceIn(0L, cap.toLong()))
            }
        }
        assertEquals(cap, ContextInstructionCache.budget(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0, ContextInstructionCache.budget(4L shl 30, cap.toLong(), true))
        assertEquals(0, ContextInstructionCache.budget(0, cap.toLong()))
        assertEquals(0, ContextInstructionCache.budget(-1, cap.toLong()))
    }

    @Test fun allocatingAnInstructionSnapshotDoesNotShrinkItsOwnAllowance() {
        val available = 768L shl 20
        val before = ContextInstructionCache.budget(available)
        val snapshot = 22L shl 20
        assertEquals(before, ContextInstructionCache.budget(available - snapshot, snapshot))
        assertTrue(ContextInstructionCache.budget(available / 2, snapshot) < before)
    }
}
