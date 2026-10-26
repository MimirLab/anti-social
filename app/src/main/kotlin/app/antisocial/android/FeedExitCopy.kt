/* anti social, GPL-3.0-or-later. */
package app.antisocial.android

/** A stable reading-session nudge. The target is compulsive refreshing, and
 * wording for a finished cached batch never certifies unavailable sources. */
internal object FeedExitCopy {
    private val checked=listOf(
        "Slop consumed, go be productive.",
        "You already read all the garbage we have, sorry.",
        "No fresh slop. Go have an unsponsored thought.",
        "You’re caught up. The algorithm is devastated.",
        "Nothing left to scroll. Terrible news for the attention economy.",
        "It’s all read. Checking again is not a personality.",
        "The feed is finished. Your life remains suspiciously unscrolled.",
        "No new garbage. Please manufacture your own dopamine.",
        "You’ve reached the end. We forgot to install the addiction loop.",
        "That’s enough internet. Go disappoint an engagement metric.",
        "Refresh all you like. Reality still has better graphics.",
        "Caught up. Somewhere, an engagement dashboard is crying."
    )
    private val fetched=listOf(
        "Fetched batch finished. Go have an unsponsored thought.",
        "You’ve read the loaded slop. The outside world is still open.",
        "This batch is done. Your attention is temporarily yours again.",
        "Loaded batch consumed. Go disappoint an engagement metric."
    )
    fun line(seed: Int,allChecked: Boolean=true):String=(if(allChecked)checked else fetched).let {it[Math.floorMod(seed,it.size)]}
}
