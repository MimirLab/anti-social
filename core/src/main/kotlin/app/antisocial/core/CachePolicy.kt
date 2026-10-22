package app.antisocial.core

object CachePolicy {
    const val DAY = 86_400_000L
    const val DEFAULT_BUDGET = 500L * 1024 * 1024
    fun expired(entry: CacheEntry, now: Long, unseenDays: Int = 7, viewedDays: Int = 30): Boolean =
        !entry.saved && !entry.active && now - (entry.lastViewedAt ?: entry.createdAt) >= (if (entry.lastViewedAt == null) unseenDays else viewedDays) * DAY
    fun eviction(entries: List<CacheEntry>, now: Long, budget: Long = DEFAULT_BUDGET, unseenDays: Int = 7, viewedDays: Int = 30): Set<String> {
        val eligible = entries.filter { !it.saved && !it.active }.sortedWith(compareBy<CacheEntry> { !expired(it,now,unseenDays,viewedDays) }.thenBy { it.lastViewedAt ?: it.createdAt })
        var remaining = entries.filter { !it.saved }.sumOf { it.bytes }
        return buildSet { eligible.forEach { entry -> if (expired(entry,now,unseenDays,viewedDays) || remaining > budget) { add(entry.key); remaining -= entry.bytes } } }
    }
    fun chronological(posts: List<Post>): List<Post> = posts.distinctBy { "${it.platform.id}:${it.id}" }.filter { it.publishedAt != null }.sortedWith(compareByDescending<Post> { it.publishedAt }.thenBy { it.id })
}
