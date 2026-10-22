/* AntiSocial, GPL-3.0-or-later. Data boundary for optional inference; no feed observer. */
package app.antisocial.core

import java.security.MessageDigest

/** AI processing is separate from ingestion and the reader's consumption. */
data class AiSourceRevision(val sourceId: String,val revision: String,val originalUrl: String,
    val originalText: String,val author: String,val publishedAt: Long?)
data class AiEvidenceSnapshot(val canonicalId: String,val contentRevision: String,val sources: List<AiSourceRevision>,
    val normalizedText: String,val ingestionSequence: Long,val ingestedAt: Long,
    val mediaObservations: List<AiMediaObservation> = emptyList())
data class AiMediaObservation(val sourceUrl: String,val kind: String,val text: String,val recipe: String)
data class AiProcessingReceipt(val canonicalId: String,val contentRevision: String,val modelIdentity: String,
    val recipe: String,val processedAt: Long,val coveredSources: List<String>)

/** Callers supply the existing merged identity and text. This adapter performs no
 * scraping, secondary merging, engagement ranking, or inference. Media contents
 * are absent unless an explicit transcript/OCR/description observation exists. */
object AiModelEvidence {
    fun snapshot(canonicalId: String,editions: List<Post>,normalizedText: String,ingestionSequence: Long,ingestedAt: Long,
        mediaObservations: List<AiMediaObservation> = emptyList()): AiEvidenceSnapshot {
        require(canonicalId.isNotBlank()&&canonicalId.length<=4096&&editions.size in 1..64)
        require(ingestionSequence>=0&&ingestedAt>=0&&normalizedText.length<=256*1024)
        val sources=editions.distinctBy {CrossPosts.originalKey(it)}.map {post->
            val id=CrossPosts.originalKey(post)
            AiSourceRevision(id,hash(listOf(post.author,post.title,post.body,post.parentId.orEmpty())+post.media.map {it.url}),post.canonicalUrl,
                listOf(post.title,post.body).filter {it.isNotBlank()}.joinToString("\n\n"),post.author,post.publishedAt)
        }
        require(mediaObservations.size<=128&&mediaObservations.all {it.kind in setOf("ocr","transcript","description","caption")&&it.recipe.isNotBlank()&&it.text.length<=256*1024})
        // Associations/counts can grow without changing a model input. The
        // independent source receipts retain every original and its revision.
        val revision=hash(listOf("normalized-evidence-v1",normalizedText)+mediaObservations.sortedWith(compareBy({it.sourceUrl},{it.kind},{it.recipe})).flatMap {listOf(it.sourceUrl,it.kind,it.recipe,it.text)})
        return AiEvidenceSnapshot(canonicalId,revision,sources,normalizedText,ingestionSequence,ingestedAt,mediaObservations)
    }
    private fun hash(fields: List<String>): String {
        val digest=MessageDigest.getInstance("SHA-256")
        fields.forEach {field->val bytes=field.toByteArray(Charsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
