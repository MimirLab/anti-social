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
    return rememberCollectedAttachments(repo,posts).map {it.media}
}

internal data class CollectedAttachment(val media: Media,val platforms: Set<Platform>,val originalKeys: Set<String>) {
    val sourceLabel get()=when(media.type){"image"->"Image";"video"->"Video";"audio"->"Audio";else->"Attachment"}+" from "+PlatformOrder.sort(platforms).joinToString(" · "){it.label}
}

@Composable internal fun rememberCollectedAttachments(repo: Repository,posts: List<Post>): List<CollectedAttachment> {
    val revision by repo.images.identities.revision.collectAsStateWithLifecycle()
    return remember(posts,revision){collectedAttachments(posts,repo.images.identities)}
}

internal fun collectedMedia(posts: List<Post>,identities: MediaIdentityStore?=null): List<Media> = collectedAttachments(posts,identities).map {it.media}

internal fun collectedAttachments(posts: List<Post>,identities: MediaIdentityStore?=null): List<CollectedAttachment> {
    data class Edition(val media: Media,val post: Post)
    val observed=if(identities==null)posts else posts.map(identities::captured)
    val near=CrossPosts.relatedVisualUpdates(observed)
    val media=observed.flatMap {it.media}
    val assetKeys=media.filter {it.type=="image"}.mapNotNull {item->ImageAssetIdentity.key(item.url)?.let {item.url to it}}.toMap()
    val videos=media.filter {it.type=="video"}
    val clusters=mutableListOf<MutableList<Edition>>()
    fun frame(m:Media)=m.attachmentFingerprint ?: m.visualFingerprint
    fun sameFrame(a:Media,b:Media)=VisualFrames.sameImage(frame(a),frame(b))
    fun sameClip(a:Media,b:Media)=VideoAssetIdentity.key(a.url)?.let {it==VideoAssetIdentity.key(b.url)}==true
    fun samePoster(a:Media,b:Media):Boolean {
        val first=a.preview ?: return false;val second=b.preview ?: return false
        return first==second||sameFrame(a,b)||ImageAssetIdentity.same(Media(first),Media(second))
    }
    fun compatible(a:Media,b:Media):Boolean {
        val first=a.durationMs?.takeIf {it>0};val second=b.durationMs?.takeIf {it>0}
        if(a.type=="video"&&first!=null&&second!=null&&
            kotlin.math.abs(first-second)>maxOf(1000L,maxOf(first,second)/100))return false
        return a.type !in setOf("image","video")||a.type=="video"&&sameClip(a,b)||frame(a)==null||frame(b)==null||
            sameFrame(a,b)||a.type=="image"&&near&&VisualFrames.same(frame(a),frame(b),nearbyFrame=true)
    }
    fun equivalent(a:Media,b:Media)=a.type==b.type&&(a.url==b.url||when(a.type) {
        "image"->assetKeys[a.url]?.let {it==assetKeys[b.url]}==true&&ImageAssetIdentity.same(a,b)||
            sameFrame(a,b)||near&&VisualFrames.same(frame(a),frame(b),nearbyFrame=true)
        "video"->sameClip(a,b)||samePoster(a,b)
        else->false
    })
    for(edition in observed.flatMap {post->post.media.map {Edition(it,post)}}) {
        val item=edition.media
        // An RSS thumbnail is already represented by the collected playable
        // video. Its source record remains available in Collected originals.
        if(item.type=="image"&&videos.any {video->video.preview==item.url||video.preview!=null&&sameFrame(item,video)})continue
        // A discarded copy can establish another CDN alias for the retained
        // picture. Keep that evidence within this call. Every known observation
        // must remain compatible: an intermediate JPEG cannot bridge two edits.
        val matches=clusters.filter {group->group.any {equivalent(it.media,item)}&&group.all {compatible(it.media,item)}}
        val existing=matches.singleOrNull()
        if(existing==null)clusters+=mutableListOf(edition)
        else if(existing.none {it.media==item&&CrossPosts.originalKey(it.post)==CrossPosts.originalKey(edition.post)})existing+=edition
    }
    return clusters.map {group->CollectedAttachment(group.first().media,group.map {it.post.platform}.toSet(),group.map {CrossPosts.originalKey(it.post)}.toSet())}
}
