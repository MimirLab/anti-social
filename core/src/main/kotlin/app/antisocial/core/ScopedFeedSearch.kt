/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A bounded text search of an already selected public container, not a platform index.
 * The unfiltered source page stays private so its continuation and routing metadata
 * reach [load] unchanged. Scope ownership must be established by that reader.
 */
class ScopedFeedSearch(
    query: String,
    private val load: suspend (Page?) -> Page,
    private val maxPages: Int = 12,
    private val maxPosts: Int = 400,
    private val maxCharacters: Int = 1024 * 1024
) {
    private val terms = Regex("\"([^\"]*)\"|(\\S+)").findAll(query).map {
        normalize(it.groups[1]?.value ?: it.groups[2]!!.value)
    }.filter(String::isNotBlank).distinct().toList()
    private var sourcePage: Page? = null
    private var inspectedPages = 0
    private var inspectedPosts = 0
    private var inspectedCharacters = 0
    private val seen = mutableSetOf<String>()
    private val cursors = mutableSetOf<String>()
    private var ended = false
    private var limited = false
    private var repeated = false

    init {
        require(terms.isNotEmpty()) { "Enter a search term" }
        require(maxPages > 0 && maxPosts > 0 && maxCharacters > 0)
    }

    /** [previous] is the filtered discovery page; source pagination uses its original page. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun next(previous: Page?): Page {
        currentCoroutineContext().ensureActive()
        if (ended) return result(sourcePage ?: Page(emptyList()), emptyList())
        repeat(3) {
            currentCoroutineContext().ensureActive()
            val page = load(sourcePage)
            currentCoroutineContext().ensureActive()
            val additions = mutableSetOf<String>()
            val matches = mutableListOf<Post>()
            var posts = inspectedPosts
            var characters = inspectedCharacters
            var truncated = false
            for (post in page.posts) {
                val identity = ContentIdentity.key(post.platform, post.canonicalUrl)
                if (identity in seen || !additions.add(identity)) continue
                val size = post.body.length.toLong() + post.title.length
                if (posts >= maxPosts || characters.toLong() + size > maxCharacters) {
                    additions.remove(identity)
                    truncated = true
                    break
                }
                posts++
                characters += size.toInt()
                val text = normalize(post.title + "\n" + post.body)
                if (terms.all { text.contains(it) }) matches += post
            }
            currentCoroutineContext().ensureActive()
            sourcePage = page
            inspectedPages++
            inspectedPosts = posts
            inspectedCharacters = characters
            seen += additions
            val cursor = page.next?.takeIf(String::isNotBlank)
            repeated = cursor != null && !cursors.add(cursor) ||
                page.posts.isNotEmpty() && additions.isEmpty() && !truncated
            limited = truncated || cursor != null &&
                (inspectedPages >= maxPages || inspectedPosts >= maxPosts || inspectedCharacters >= maxCharacters)
            ended = cursor == null || repeated || limited
            if (matches.isNotEmpty() || ended) return result(page, matches)
        }
        return result(sourcePage!!, emptyList())
    }

    private fun result(page: Page, matches: List<Post>): Page = page.copy(
        posts = matches,
        next = if (ended) null else page.next,
        profile = null,
        accounts = emptyList(),
        collections = emptyList(),
        notice = listOfNotNull(
            "Searches available public posts from this feed.",
            page.notice?.takeIf(String::isNotBlank),
            if (limited) "Search stopped at its public-feed reading limit; older posts may not have been searched." else null,
            if (repeated) "This feed supplied no new posts or repeated its continuation." else null
        ).distinct().joinToString(" ")
    )

    companion object {
        private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[\\s\\p{Z}]+"), " ").trim()
    }
}
