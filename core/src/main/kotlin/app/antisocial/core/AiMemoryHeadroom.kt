/* AntiSocial, GPL-3.0-or-later. Shared CPU/GPU allocation headroom, not a device qualification. */
package app.antisocial.core

object AiMemoryHeadroom {
    private const val MIB = 1024L * 1024

    /** Incremental allocation budget from an Android MemoryInfo snapshot.
     * Existing allocations are already reflected in availableBytes. Never add
     * GPU heap sizes to system RAM: on phones they commonly share physical RAM.
     * The threshold and growth margin remain available for the system/reader.
     * This policy is a conservative estimate, not an OOM guarantee or benchmark.
     */
    fun allocationBudget(totalBytes: Long, availableBytes: Long, thresholdBytes: Long, lowMemory: Boolean): Long {
        if (lowMemory || totalBytes <= 0 || availableBytes <= 0 || thresholdBytes < 0) return 0
        val available = minOf(totalBytes, availableBytes)
        val growth = maxOf(512 * MIB, totalBytes / 16)
        if (thresholdBytes >= available || growth >= available - thresholdBytes) return 0
        return available - thresholdBytes - growth
    }

    /** Additional worker allocation estimate used before loading a plain target.
     * This preserves the old 512 MiB scratch allowance; it does not pretend the
     * file size is a complete working set. Drafter admission needs a separate
     * measured envelope including both models, features and rollback buffers.
     */
    fun plainTargetEstimate(weightFileBytes: Long): Long? =
        weightFileBytes.takeIf { it > 0 && it <= Long.MAX_VALUE - 512 * MIB }?.plus(512 * MIB)
}
