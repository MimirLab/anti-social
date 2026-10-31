/* anti social, GPL-3.0-or-later. Refresh a source record; never alias recordings. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/** Admission for a failed retained TikTok delivery. The new complete record
 * belongs in ordinary source cache; none of its bytes/observations belong to
 * the former delivery URL or a protected saved edition. */
object VideoPostRefresh {
    private val host=Regex("v[0-9]{1,3}[a-z]?\\.tiktokcdn(?:-eu|-us)?\\.com")
    private val path=Regex("/[0-9a-f]{32}/[0-9a-f]{8}/video/tos/[a-z0-9-]{2,32}/[a-z0-9-]{2,80}/[A-Za-z0-9_-]{24,128}/")
    private val postId=Regex("post:[0-9]{1,40}")
    fun eligible(post:Post):Boolean {
        if(post.platform!=Platform.TIKTOK||post.preview||post.parentId!=null||post.recoveredArchive!=null||post.archivedVersion!=null||
            post.provenance?.source?.role==SourceRole.CACHE||post.publishedAt?.let {it>0}!=true||post.media.size!=1||
            post.body.length.toLong()+post.title.length>16_384||post.links.orEmpty().size>16||post.canonicalUrl.length !in 1..8192||post.canonicalUrl.any(Char::isISOControl))return false
        if(CrossPosts.originalAccount(post)==null||!ContentIdentity.key(post.platform,post.canonicalUrl).matches(postId))return false
        val original=post.canonicalUrl.toHttpUrlOrNull() ?: return false
        if(!original.isHttps||original.port!=443||original.username.isNotEmpty()||original.password.isNotEmpty()||original.fragment!=null||
            original.query!=null||SocialLinkHosts.platform(post.canonicalUrl)!=Platform.TIKTOK||original.pathSegments.filter(String::isNotBlank).size!=3||original.pathSegments.getOrNull(1)!="video")return false
        val media=post.media.single()
        if(media.type!="video"||media.audioUrl!=null||media.samplingUrl!=null||VideoFormats.mime(media) !in setOf(null,"video/mp4","video/webm")||
            media.durationMs?.let {it !in 2_000..VideoFootage.MAX_DURATION_MS}==true||media.url.length>8192||media.url.any(Char::isISOControl))return false
        val url=media.url.toHttpUrlOrNull() ?: return false
        return url.isHttps&&url.port==443&&url.username.isEmpty()&&url.password.isEmpty()&&url.fragment==null&&host.matches(url.host)&&
            path.matches(url.encodedPath)&&PostPresentation.content(post).isNotBlank()
    }
    /** Local observation changes must not retire an otherwise identical queued
     * input. Source fields, acquisition hints and publication time still do. */
    fun sameInput(a:Post,b:Post):Boolean {
        fun source(post:Post)=post.copy(provenance=post.provenance?.copy(cached=false),media=post.media.map {
            it.copy(aiMetadata=null,visualFingerprint=null,attachmentFingerprint=null,videoFootage=null)
        })
        return source(a)==source(b)
    }
    fun accepts(original:Post,fresh:Post):Boolean {
        if(!eligible(original)||!eligible(fresh)||original.identityKey!=fresh.identityKey||CrossPosts.originalAccount(original)!=CrossPosts.originalAccount(fresh))return false
        val provenance=fresh.provenance ?: return false
        if(provenance.cached||provenance.source.platform!=fresh.platform||provenance.source.role!=SourceRole.ALTERNATE||provenance.fetchedAt<=0||
            provenance.fetchedAt<(original.provenance?.fetchedAt ?: 0)||!runCatching {DarwinRoutes.matches(URI(provenance.finalUrl),provenance.source.root)}.getOrDefault(false))return false
        val first=requireNotNull(original.publishedAt);val last=requireNotNull(fresh.publishedAt)
        if(maxOf(first,last)-minOf(first,last)>60_000||PostPresentation.content(original)!=PostPresentation.content(fresh)||
            original.links.orEmpty().toSet()!=fresh.links.orEmpty().toSet())return false
        // A narrower source must not erase a previously supplied disclosure or
        // relationship. Empty means unknown, not permission to drop evidence.
        if(!fresh.aiDisclosures.orEmpty().containsAll(original.aiDisclosures.orEmpty())||
            !fresh.contextNotes.orEmpty().containsAll(original.contextNotes.orEmpty())||
            !fresh.publicRelations.orEmpty().containsAll(original.publicRelations.orEmpty())||
            original.authorAccount?.let {it!=fresh.authorAccount}==true||
            listOf(original.conversationId to fresh.conversationId,original.relationId to fresh.relationId,original.sourceParentId to fresh.sourceParentId).any {(a,b)->a!=null&&a!=b})return false
        val old=original.media.single();val media=fresh.media.single()
        val duration=media.durationMs?.takeIf {it in 2_000..VideoFootage.MAX_DURATION_MS} ?: return false
        if(old.url==media.url||old.durationMs?.let {kotlin.math.abs(it-duration)>maxOf(750L,maxOf(it,duration)/100)}==true)return false
        return media.aiMetadata==null&&media.visualFingerprint==null&&media.attachmentFingerprint==null&&media.videoFootage==null
    }
    fun candidate(original:Post,page:Page):Post?=page.posts.takeIf {it.size in 1..64}?.singleOrNull {it.identityKey==original.identityKey}?.takeIf {accepts(original,it)}
}
