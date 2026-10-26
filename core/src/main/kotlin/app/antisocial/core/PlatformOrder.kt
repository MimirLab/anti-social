/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

/** Approximate mainstream adoption for navigation, reviewed October 2026.
 * Platforms publish incompatible activity/member measures; this is not a MAU table.
 * Storage identifiers and enum order remain independent of this display policy.
 */
object PlatformOrder {
    val popular: List<Platform> = listOf(
        Platform.FACEBOOK,Platform.INSTAGRAM,Platform.TIKTOK,Platform.TELEGRAM,
        Platform.SNAPCHAT,Platform.LINKEDIN,Platform.PINTEREST,Platform.X,
        Platform.REDDIT,Platform.THREADS,Platform.TUMBLR,Platform.TIEBA,
        Platform.BLUESKY,Platform.MASTODON,Platform.TRUTH_SOCIAL,Platform.IFUNNY,
        Platform.GAB,Platform.GETTR,Platform.MISSKEY,Platform.LEMMY,Platform.PIXELFED,Platform.RSS
    )
    private val rank=popular.withIndex().associate {it.value to it.index}
    val defaultSelected: List<Platform> = sort(listOf(Platform.FACEBOOK,Platform.X,Platform.REDDIT,Platform.INSTAGRAM,Platform.TIKTOK))
    fun sort(platforms: Iterable<Platform>): List<Platform> = platforms.distinct().sortedBy {rank[it] ?: Int.MAX_VALUE}
}
