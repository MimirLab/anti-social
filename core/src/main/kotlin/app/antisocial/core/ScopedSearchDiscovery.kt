/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Keeps successful native searches fast; a failed route can select its public
 * container's bounded text scanner. Once selected, feed pagination never goes
 * back to the failed native endpoint or consumes a native cursor.
 */
class ScopedSearchDiscovery(
    private val native: suspend (Page?) -> Page,
    private val fallback: ScopedFeedSearch?
) {
    var usingFallback = false
        private set

    suspend fun next(previous: Page?): Page {
        if (usingFallback) return fallback!!.next(null)
        return try {
            native(previous)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceFailure) {
            if (e.status == HealthStatus.RATE_LIMITED || fallback == null) throw e
            usingFallback = true
            fallback.next(null)
        } catch (e: IOException) {
            if (fallback == null) throw e
            usingFallback = true
            fallback.next(null)
        }
    }
}
