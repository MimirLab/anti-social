/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Source observations, not a guess that missing material never existed. */
enum class ExtractionStatus { PRESENT, PARTIAL, UNAVAILABLE, UNKNOWN }
data class ExtractionCoverage(val text: ExtractionStatus=ExtractionStatus.UNKNOWN,
    val quotedContext: ExtractionStatus=ExtractionStatus.UNKNOWN,
    val attachments: ExtractionStatus=ExtractionStatus.UNKNOWN,
    val note: String?=null,
    val replyParent: ExtractionStatus?=null)
data class ContentObservation(val key: String,val signature: String,val fetchedAt: Long)

object ContentRevisions {
    fun observations(posts: List<Post>): List<ContentObservation> = posts.filter { !it.preview&&(it.body.isNotBlank()||it.title.isNotBlank()||it.media.isNotEmpty()) }.distinctBy(CrossPosts::originalKey)
        .map {ContentObservation(CrossPosts.originalKey(it),signature(it),it.provenance?.fetchedAt ?: 0)}
    private val url=Regex("https?://[^\\s<>]+")
    private fun reference(raw: String): String? = runCatching {
        val clean=raw.trimEnd('.',',',';',')',']','"')
        val platform=SocialLinkHosts.platform(clean)
        if(platform!=null) {
            val key=ContentIdentity.key(platform,clean)
            return@runCatching if(key.startsWith("post:")||key.startsWith("comment:"))"${platform.id}:$key"else null
        }
        val uri=URI(clean)
        if(uri.scheme !in setOf("https","http")||uri.host==null)return@runCatching null
        val query=uri.rawQuery?.split('&')?.filterNot {part->val key=part.substringBefore('=').lowercase(Locale.ROOT);key.startsWith("utm_")||key in setOf("fbclid","gclid","igshid","mc_eid")}?.sorted()?.joinToString("&")?.ifEmpty {null}
        URI(uri.scheme,uri.rawAuthority.lowercase(Locale.ROOT),uri.rawPath,query,null).toASCIIString()
    }.getOrNull()
    private fun references(post: Post)=(post.links.orEmpty()+url.findAll(post.title+"\n"+post.body).map {it.value}.toList()).mapNotNull(::reference).toSet()
    private fun text(post: Post): String = TextWhitespace.collapse(Normalizer.normalize(PostBodyDetails.split(PostPresentation.content(post)).body,Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)).trim()
    private fun media(media: Media): String = media.type+":"+runCatching {
        val u=URI(media.url)
        // Keep content-bearing parameters. Only known expiring transport tokens
        // disappear; fetching a renewed signed URL is not a new photograph.
        val query=u.rawQuery?.split('&')?.filterNot {it.substringBefore('=').lowercase(Locale.ROOT) in setOf("token","signature","sig","expires","expiry","policy","key-pair-id")||it.substringBefore('=').startsWith("x-amz-",true)}?.sorted()?.joinToString("&")?.ifEmpty {null}
        URI(u.scheme,u.rawAuthority,u.rawPath,query,null).toASCIIString()
    }.getOrDefault(media.url)
    fun signature(post: Post): String {
        val value=text(post)+"\u0000"+post.media.map(::media).distinct().sorted().joinToString("\u0000")+"\u0000"+references(post).sorted().joinToString("\u0000")
        val digest=MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return buildString(digest.size*2) {
            digest.forEach {byte->val unsigned=byte.toInt() and 255;append("0123456789abcdef"[unsigned ushr 4]);append("0123456789abcdef"[unsigned and 15])}
        }
    }
    /** Grouping proves a relationship; it doesn't prove the reader saw every
     * contribution. A fresh edition must be covered by actually read editions. */
    fun addsContext(read: List<Post>,incoming: List<Post>): Boolean {
        if(read.isEmpty())return true
        val known=read.map(::text)
        val assets=read.flatMap {it.media}.map(::media).toSet()
        val originals=(read+incoming).mapNotNull {reference(it.canonicalUrl)}.toSet()
        val documents=read.flatMap {references(it)}.toSet()+originals
        return incoming.any {post->
            val body=text(post)
            (body.isBlank()&&post.media.isEmpty()) || body.isNotBlank()&&known.none {it.contains(body)} || post.media.any {media(it) !in assets} || references(post).any {it !in documents}
        }
    }
    fun coverage(post: Post): ExtractionCoverage = post.extractionCoverage ?: ExtractionCoverage(
        text=if(post.preview)ExtractionStatus.PARTIAL else if(post.body.isNotBlank()||post.title.isNotBlank())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,
        attachments=if(post.media.isNotEmpty())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,
        note=if(post.preview)"This source supplied an excerpt; other context may be missing."else null)
}
