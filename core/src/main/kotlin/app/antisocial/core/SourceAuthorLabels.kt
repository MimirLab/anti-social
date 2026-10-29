/* anti social, GPL-3.0-or-later. Display labels never establish account identity. */
package app.antisocial.core

data class SourceAuthorLabel(val label:String,val originals:List<Post>,val accounts:List<Post>,val platforms:List<Platform>)

/** Compact only repeated literal display names. Keep different accounts and
 * every original in the group so the UI can offer the complete source chooser.
 * Never use this result for ownership, deduplication or saved-copy admission. */
object SourceAuthorLabels {
    fun collect(posts:List<Post>):List<SourceAuthorLabel> {
        val originals=posts.distinctBy(CrossPosts::originalKey).take(CrossPosts.MAX_EDITIONS)
        val position=originals.mapIndexed {index,post->CrossPosts.originalKey(post)to index}.toMap()
        // Preserve the established one-label-per-proven-profile behavior even
        // when that profile's earlier and later editions use different names.
        val accounts=originals.groupBy {CrossPosts.originalAccount(it) ?: CrossPosts.originalKey(it)}.values
        return accounts.groupBy {sources->TextWhitespace.collapse(sources.first().author).let {
            it to if(it.isBlank())CrossPosts.originalKey(sources.first())else ""
        }}.values.map {profiles->
            val sources=profiles.flatten().sortedBy {position[CrossPosts.originalKey(it)]}
            SourceAuthorLabel(TextWhitespace.collapse(profiles.first().first().author).ifBlank {"Author not supplied"},sources,
                profiles.map {it.first()},PlatformOrder.sort(sources.map {it.platform}))
        }
    }
}
