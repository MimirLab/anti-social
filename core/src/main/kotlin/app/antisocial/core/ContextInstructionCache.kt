/* AntiSocial, GPL-3.0-or-later. RAM-only acceleration, never additional model capacity. */
package app.antisocial.core

object ContextInstructionCache {
    const val MAX_BYTES = 128 * 1024 * 1024

    /** Count the existing cache as reclaimable RAM, preventing repeated budget
     * checks from shrinking their own allowance. Low-memory signals win. */
    fun budget(availableBytes: Long, cachedBytes: Long = 0, lowMemory: Boolean = false): Int {
        if (lowMemory || availableBytes <= 0) return 0
        val reclaimed = cachedBytes.coerceIn(0L, MAX_BYTES.toLong())
        val available = if (availableBytes > Long.MAX_VALUE - reclaimed) Long.MAX_VALUE else availableBytes + reclaimed
        return (available / 16).coerceAtMost(MAX_BYTES.toLong()).toInt()
    }
}
