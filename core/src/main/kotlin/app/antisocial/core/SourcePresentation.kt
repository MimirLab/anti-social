/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.net.URI
import java.util.Locale

/** The social destination a post belongs to, independently of its retrieval route.
 * Keep the original identity post here when presenting an archived replay.
 */
object SourcePresentation {
    fun label(post: Post): String {
        if(post.platform !in ExpandedRoutes.federated&&post.platform!=Platform.RSS)return post.platform.label
        val origin=runCatching {URI(post.canonicalUrl)}.getOrNull()
            ?.takeIf {it.scheme?.lowercase(Locale.ROOT) in setOf("http","https")&&it.userInfo==null}
            ?: return post.platform.label
        val host=origin.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
            ?: return post.platform.label
        val port=origin.port.takeUnless {it in setOf(-1,80,443)}?.let {":$it"}.orEmpty()
        return "${post.platform.label} · $host$port"
    }
}
