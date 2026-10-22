/* AntiSocial, GPL-3.0-or-later. Review lineage is evidence identity, not model prose. */
package app.antisocial.core

import java.security.MessageDigest

object ContextReviewIdentity {
    /** Unit IDs bind the source span, profile and supplied parent/background.
     * Only reviews supporting retained findings belong here. Empty reviews
     * must not turn a reaction or an unchanged read into a new interpretation. */
    fun of(unitIds:Iterable<String>):String? {
        val ids=unitIds.toSortedSet()
        if(ids.isEmpty())return null
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update("context-review-lineage-v1\n".toByteArray(Charsets.UTF_8))
        for(id in ids) {
            val bytes=id.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8));digest.update(bytes)
        }
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
}
