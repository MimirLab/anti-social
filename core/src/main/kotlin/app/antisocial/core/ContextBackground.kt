/* AntiSocial, GPL-3.0-or-later. Local evidence selection; no model or network authority. */
package app.antisocial.core

import java.security.MessageDigest

/** Shared by reference while processing a batch, never copied into every reply's
 * stored payload. Hashing a long subject happens once per source, not per reply. */
class ContextBackgroundSource(val text: String, val label: String = "", val incomplete: Boolean = false, val unavailable: Boolean = false, val uncertain: Boolean = false,
    val interpretationIdentity:String="",val sourceId:String="") {
    init { require(text.length <= 4 * 1024 * 1024 && label.length <= 256 && interpretationIdentity.length<=256) }
    val fingerprint: String by lazy { digest("$incomplete:$unavailable:$uncertain\n$interpretationIdentity\n$sourceId\n$label\n$text") }
}

data class ContextBackground(val post: ContextBackgroundSource? = null, val reply: ContextBackgroundSource? = null) {
    val fingerprint: String get() = digest("${post?.fingerprint}\n${reply?.fingerprint}")
    val unavailable: Boolean get() = post?.unavailable == true || reply?.unavailable == true
    fun excerpt(characters: Int): ContextBackgroundExcerpt {
        val limit = characters.coerceAtLeast(0)
        val a = post?.text.orEmpty(); val b = reply?.text.orEmpty()
        // Neither a long original nor a long reply parent may crowd the other
        // out. An unused share goes to the source that still needs it.
        var aCount = minOf(a.length, if (b.isEmpty()) limit else limit / 2)
        val bCount = minOf(b.length, limit - aCount)
        aCount = minOf(a.length, limit - bCount)
        val postText = a.prefix(aCount); val replyText = b.prefix(bCount)
        val text = buildList {
            if (postText.isNotEmpty()) add("Subject post${post?.label?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}:\n$postText")
            if (replyText.isNotEmpty()) add("Reply parent${reply?.label?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}:\n$replyText")
        }.joinToString("\n")
        return ContextBackgroundExcerpt(text, postText.length < a.length || replyText.length < b.length ||
            post?.incomplete == true || reply?.incomplete == true || unavailable, unavailable, post?.uncertain == true || reply?.uncertain == true)
    }
}

data class ContextBackgroundExcerpt(val text: String, val truncated: Boolean = false, val unavailable: Boolean = false, val uncertain: Boolean = false)

private fun String.prefix(length: Int): String {
    val end = length.coerceIn(0, this.length)
    return substring(0, if (end > 0 && end < this.length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end - 1 else end)
}
private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
