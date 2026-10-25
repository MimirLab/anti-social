/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** These lists have already passed the grouping or saved-manifest checks. */
internal fun hasRepeatedOriginals(posts: List<Post>): Boolean = posts.size>posts.map {it.platform}.distinct().size

/** A short quotation of the same clip need not replace its complete caption.
 * Original records and the chronological primary remain unchanged. */
internal fun collectedCaptionPost(posts: List<Post>): Post? = if(hasRepeatedOriginals(posts)) {
    posts.sortedWith(compareByDescending<Post> {PostPresentation.content(it).length}.thenBy(CrossPosts::originalKey)).firstOrNull()
}else posts.firstOrNull()

internal fun orderedCollectedOriginals(posts: List<Post>): List<Post> {
    val unique=posts.distinctBy(CrossPosts::originalKey).take(CrossPosts.MAX_EDITIONS)
    return unique.sortedWith(compareBy<Post> {it.publishedAt?.takeIf {time->time>0} ?: Long.MAX_VALUE}.thenBy(CrossPosts::originalKey))
}

/** Already verified short clip quotations may not meet the full aligner's text
 * length. They remain accessible as originals; align their fullest editions. */
internal fun collectedPostFusion(posts: List<Post>): PostFusion? = CrossPostFusion.compose(posts)
    ?: CrossPostFusion.composeMedia(posts)
    ?: CrossPostFusion.composeRelated(posts)
    ?: (if(hasRepeatedOriginals(posts))CrossPostFusion.compose(posts.groupBy {it.platform}.values.mapNotNull(::collectedCaptionPost))else null)
    ?: CrossPosts.clipQuotationCaption(posts)?.let {full->PostFusion(listOf(FusionSegment(PostPresentation.content(full),setOf(full.platform),false,setOf(CrossPosts.originalKey(full)))))}

internal fun collectedCaptionTexts(posts: List<Post>): List<String> = collectedPostFusion(posts)?.segments?.map {it.text}
    ?: listOf(collectedCaptionPost(posts)?.let(PostPresentation::content).orEmpty())

/** Reading freezes captions and editions, not local evidence that two already
 * included attachments are the same picture. Never fetches or changes a source. */
@Composable internal fun rememberCollectedMedia(repo: Repository,posts: List<Post>): List<Media> {
    val revision by repo.images.identities.revision.collectAsStateWithLifecycle()
    return remember(posts,revision){collectedMedia(posts,repo.images.identities)}
}

internal fun collectedMedia(posts: List<Post>,identities: MediaIdentityStore?=null): List<Media> {
    val observed=if(identities==null)posts else posts.map(identities::captured)
    val near=CrossPosts.relatedVisualUpdates(observed)
    val media=observed.flatMap {it.media}
    val assetKeys=media.filter {it.type=="image"}.mapNotNull {item->ImageAssetIdentity.key(item.url)?.let {item.url to it}}.toMap()
    val posters=media.filter {it.type=="video"}.mapNotNull {it.preview}.toSet()
    val clusters=mutableListOf<MutableList<Media>>()
    fun compatible(a:Media,b:Media)=a.type!="image"||a.visualFingerprint==null||b.visualFingerprint==null||
        VisualFrames.sameImage(a.visualFingerprint,b.visualFingerprint)||near&&VisualFrames.same(a.visualFingerprint,b.visualFingerprint,nearbyFrame=true)
    fun equivalent(a:Media,b:Media)=a.type==b.type&&(a.url==b.url||a.type=="image"&&(
        assetKeys[a.url]?.let {it==assetKeys[b.url]}==true&&ImageAssetIdentity.same(a,b)||
        VisualFrames.sameImage(a.visualFingerprint,b.visualFingerprint)||near&&VisualFrames.same(a.visualFingerprint,b.visualFingerprint,nearbyFrame=true)))
    for(item in media) {
        // An RSS thumbnail is already represented by the collected playable
        // video. Its source record remains available in Collected originals.
        if(item.type=="image"&&item.url in posters)continue
        // A discarded copy can establish another CDN alias for the retained
        // picture. Keep that evidence within this call. Every known observation
        // must remain compatible: an intermediate JPEG cannot bridge two edits.
        val matches=clusters.filter {group->group.any {equivalent(it,item)}&&group.all {compatible(it,item)}}
        val existing=matches.singleOrNull()
        if(existing==null)clusters+=mutableListOf(item)
        else if(existing.none {it.url==item.url&&it.visualFingerprint==item.visualFingerprint})existing+=item
    }
    return clusters.map {it.first()}
}
