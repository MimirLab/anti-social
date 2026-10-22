/* AntiSocial, GPL-3.0-or-later. Processing plans never grant tools or network access. */
package app.antisocial.core

enum class ContextProcessingStyle { AUTO, FOCUSED, BALANCED, EXTENDED }

/** Size selects an initial work recipe, not a quality score. The actual working
 * window and output allowance remain hard limits. Unknown proprietary models
 * can be explicitly assigned a recipe without inventing a parameter count. */
data class ContextProcessingPlan(
    val style: ContextProcessingStyle,
    val screenComments: Boolean,
    val sourcesPerCall: Int,
    val knownLimit: Int,
    val summaryGroupLimit: Int,
    val unitCharacters: Int,
    val reason: String,
    val extractiveFindings: Boolean = false
) {
    init {
        require(style != ContextProcessingStyle.AUTO && sourcesPerCall in 1..8 && knownLimit in 1..32)
        require(summaryGroupLimit in 2..2048 && unitCharacters in 1024..262144 && reason.length <= 256)
    }
    // Version the recipe as well as the prompt. Switching plans must not reuse
    // a receipt produced with different scope or screening responsibilities.
    val identity: String get() = "router-v2:${style.name}:$screenComments:$sourcesPerCall:$knownLimit:$summaryGroupLimit:$unitCharacters:$extractiveFindings"

    companion object {
        /** Conservative interface default for adapters without capability data. */
        val SINGLE = ContextProcessingPlan(ContextProcessingStyle.BALANCED, false, 1, 12, 4, 262144, "Model size not reported; individual source review")
    }
}

object ContextProcessingRouter {
    /** Response allowance includes any server-side reasoning tokens. Reserving
     * only a phone-sized reply can leave a capable model no visible answer. */
    fun outputBudget(capabilities: ContextModelCapabilities, onDevice: Boolean,
        requested: ContextProcessingStyle = ContextProcessingStyle.AUTO): Int {
        val desired = when {
            onDevice -> 512
            requested == ContextProcessingStyle.FOCUSED -> 4096
            requested == ContextProcessingStyle.EXTENDED -> 8192
            requested == ContextProcessingStyle.BALANCED -> 4096
            capabilities.parameterCount?.let { it <= 12_000_000_000L } == true -> 4096
            capabilities.parameterCount?.let { it >= 60_000_000_000L } == true -> 8192
            else -> 4096
        }
        return minOf(desired, capabilities.maxOutputTokens ?: desired, capabilities.contextTokens / 4).coerceAtLeast(1)
    }
    fun select(capabilities: ContextModelCapabilities, onDevice: Boolean, outputTokens: Int,
        requested: ContextProcessingStyle = ContextProcessingStyle.AUTO): ContextProcessingPlan {
        require(outputTokens > 0)
        val size = capabilities.parameterCount
        val selected = when {
            onDevice -> ContextProcessingStyle.FOCUSED
            capabilities.contextTokens < 4096 || outputTokens < 512 -> ContextProcessingStyle.FOCUSED
            requested != ContextProcessingStyle.AUTO -> requested
            size == null -> ContextProcessingStyle.BALANCED
            size <= 12_000_000_000L -> ContextProcessingStyle.FOCUSED
            size >= 60_000_000_000L -> ContextProcessingStyle.EXTENDED
            else -> ContextProcessingStyle.BALANCED
        }
        val reason = when {
            onDevice -> "On-device work: focused semantic tasks and small compaction groups"
            capabilities.contextTokens < 4096 || outputTokens < 512 -> "Available reading window or output allowance requires focused work"
            requested != ContextProcessingStyle.AUTO -> "Reader-selected processing style; server limits still apply"
            size == null -> "Model size not reported; conservative individual source review"
            else -> "Reported model size selects the recipe; counted requests enforce server limits"
        }
        val batch = minOf(outputTokens / 256, if (selected == ContextProcessingStyle.EXTENDED) 8 else 4)
            .coerceAtLeast(1).let { if (capabilities.contextTokens < 8192 || size == null && requested == ContextProcessingStyle.AUTO) 1 else it }
        return when (selected) {
            ContextProcessingStyle.FOCUSED -> ContextProcessingPlan(selected, true, 1, 4, 4, 4096, reason)
            ContextProcessingStyle.BALANCED -> ContextProcessingPlan(selected, false, batch, 12,
                (capabilities.contextTokens / 2048).coerceIn(4, 64), (capabilities.contextTokens / 2).coerceIn(16384, 65536), reason)
            ContextProcessingStyle.EXTENDED -> ContextProcessingPlan(selected, false, batch, 24,
                (capabilities.contextTokens / 512).coerceIn(16, 2048), (capabilities.contextTokens.toLong() * 3).coerceIn(65536, 262144).toInt(), reason)
            ContextProcessingStyle.AUTO -> error("Automatic is not an executable plan")
        }.copy(extractiveFindings = onDevice || size?.let { it <= 4_000_000_000L } == true)
    }
}
