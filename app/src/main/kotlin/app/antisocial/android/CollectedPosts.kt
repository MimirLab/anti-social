/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*

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

internal fun collectedMedia(posts: List<Post>): List<Media> {
    val near=CrossPosts.relatedVisualUpdates(posts)
    val media=posts.flatMap {it.media}
    val posters=media.filter {it.type=="video"}.mapNotNull {it.preview}.toSet()
    val out=mutableListOf<Media>()
    for(item in media) {
        // An RSS thumbnail is already represented by the collected playable
        // video. Its source record remains available in Collected originals.
        if(item.type=="image"&&item.url in posters)continue
        if(out.none {previous->previous.type==item.type&&
            (previous.url==item.url||item.type=="image"&&VisualFrames.same(previous.visualFingerprint,item.visualFingerprint,nearbyFrame=near))})out+=item
    }
    return out
}
