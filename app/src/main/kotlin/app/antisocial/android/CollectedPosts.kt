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
internal fun collectedPostFusion(posts: List<Post>): PostFusion? = CrossPostFusion.composeCollectedEditions(posts)
    ?: CrossPostFusion.compose(posts)
    ?: CrossPostFusion.composeMedia(posts)
    ?: CaptionFieldBlocks.compose(posts)
    ?: CrossPostFusion.composeRelated(posts)
    ?: CrossPosts.clipQuotationCaption(posts)?.let {full->PostFusion(listOf(FusionSegment(PostPresentation.content(full),setOf(full.platform),false,setOf(CrossPosts.originalKey(full)))))}
    ?: sourceCaptionBlocks(posts)

/** Compression is optional after admission. A failed aligner must not choose a
 * platform's longest edition and quietly omit another source's unique prose. */
private fun sourceCaptionBlocks(posts:List<Post>):PostFusion? {
    if(posts.size !in 2..CrossPosts.MAX_EDITIONS)return null
    val groups=posts.filter {PostPresentation.content(it).isNotBlank()}.groupBy {CrossPosts.formattingCaptionKey(it) ?: PostPresentation.content(it).trim()}
        .values.flatMap {sources->if(sources.size>1&&CrossPosts.formattingCaptionCopies(sources))listOf(sources)else sources.groupBy {PostPresentation.content(it).trim()}.values}
    if(groups.isEmpty())return null
    return PostFusion(groups.map {sources->
        val preferred=sources.minBy {PostLinks.urls(it.copy(links=null)).size}
        FusionSegment(PostPresentation.content(preferred),sources.mapTo(linkedSetOf()){it.platform},
        sources.size==posts.size,sources.mapTo(linkedSetOf(),CrossPosts::originalKey))})
}

/** Read-only presentation of already admitted originals. This never establishes
 * a match or author identity. Feed, Context and the saved reader share it; the
 * Library's existing source/manifest admission remains a separate boundary. */
internal data class CollectionPlan(val originals:List<Post>,val fusion:PostFusion?,val attachments:List<CollectedAttachment>)
internal fun collectionPlan(posts:List<Post>,identities:MediaIdentityStore?=null):CollectionPlan {
    val observed=if(identities==null)posts else posts.map(identities::captured)
    return CollectionPlan(observed,collectedPostFusion(observed),collectedAttachments(observed))
}
@Composable internal fun rememberCollectionPlan(repo:Repository,posts:List<Post>):CollectionPlan {
    // New decoder evidence may collapse existing pictures. It must not trigger
    // expensive text alignment or rewrite the frozen caption on every revision.
    // Admission may already have used cached pixel evidence which isn't in the
    // original Room payload. Capture that same local evidence once when this
    // frozen body is adopted, so its composer can use the proof too. Later image
    // revisions still cannot rewrite text beneath an active reader.
    val prepared=remember(posts){posts.map(repo.images.identities::captured)}
    val fusion=remember(prepared){collectedPostFusion(prepared)}
    val attachments=rememberCollectedAttachments(repo,posts)
    return remember(prepared,fusion,attachments){CollectionPlan(prepared,fusion,attachments)}
}

/** Compare the union so a replacement image cannot hide behind an unchanged
 * attachment count. Proven CDN/pixel aliases and added attribution do not count
 * as another photograph. Runs with content changes on a worker, not scrolling. */
internal fun addsCollectedMedia(before:List<Post>,after:List<Post>,identities:MediaIdentityStore?=null):Boolean {
    val known=before.flatMap {it.media}.mapTo(hashSetOf()){it.type to it.url}
    if(after.all {post->post.media.all {(it.type to it.url) in known}})return false
    return collectedAttachments(before+after,identities).size>collectedAttachments(before,identities).size
}

internal fun collectedCaptionTexts(posts: List<Post>): List<String> = collectedPostFusion(posts)?.segments?.map {it.text}
    ?: listOf(collectedCaptionPost(posts)?.let(PostPresentation::content).orEmpty())

/** Reading freezes captions and editions, not local evidence that two already
 * included attachments are the same picture. Never fetches or changes a source. */
@Composable internal fun rememberCollectedMedia(repo: Repository,posts: List<Post>): List<Media> {
    return rememberCollectedAttachments(repo,posts).map {it.media}
}

internal data class CollectedAttachment(val media: Media,val platforms: Set<Platform>,val originalKeys: Set<String>,val previewPlatforms:Set<Platform> = emptySet(),val videoPreview:Boolean=false) {
    val sourceLabel get()=(if(videoPreview)"Video preview"else when(media.type){"image"->"Image";"video"->"Video";"audio"->"Audio";else->"Attachment"})+" from "+PlatformOrder.sort(platforms).joinToString(" · "){it.label}+
        (previewPlatforms-platforms).takeIf {it.isNotEmpty()}?.let {" · preview from "+PlatformOrder.sort(it).joinToString(" · "){p->p.label}}.orEmpty()
}

@Composable internal fun rememberCollectedAttachments(repo: Repository,posts: List<Post>): List<CollectedAttachment> {
    val revision by repo.images.identities.revision.collectAsStateWithLifecycle()
    return remember(posts,revision){collectedAttachments(posts,repo.images.identities)}
}

internal fun collectedMedia(posts: List<Post>,identities: MediaIdentityStore?=null): List<Media> {
    val observed=if(identities==null)posts else posts.map(identities::captured)
    return collectedMediaGroups(collectedAttachments(observed),CrossPosts.relatedVisualUpdates(observed)).map {it.primary.media}
}

internal fun collectedAttachments(posts: List<Post>,identities: MediaIdentityStore?=null): List<CollectedAttachment> {
    data class Edition(val media: Media,val post: Post)
    val observed=if(identities==null)posts else posts.map(identities::captured)
    val media=observed.flatMap {it.media}
    val assetKeys=media.filter {it.type=="image"}.mapNotNull {item->ImageAssetIdentity.key(item.url)?.let {item.url to it}}.toMap()
    val clusters=mutableListOf<MutableList<Edition>>()
    fun frame(m:Media)=m.attachmentFingerprint ?: m.visualFingerprint
    fun sameFrame(a:Media,b:Media)=VisualFrames.sameImage(frame(a),frame(b))
    fun sameClip(a:Media,b:Media)=VideoAssetIdentity.key(a.url)?.let {it==VideoAssetIdentity.key(b.url)}==true
    fun compatible(a:Media,b:Media):Boolean {
        val first=a.durationMs?.takeIf {it>0};val second=b.durationMs?.takeIf {it>0}
        if(a.type=="video"&&first!=null&&second!=null&&
            kotlin.math.abs(first-second)>maxOf(1000L,maxOf(first,second)/100))return false
        return a.type !in setOf("image","video")||a.type=="video"&&sameClip(a,b)||frame(a)==null||frame(b)==null||
            sameFrame(a,b)
    }
    fun equivalent(a:Media,b:Media)=a.type==b.type&&(a.url==b.url||when(a.type) {
        "image"->assetKeys[a.url]?.let {it==assetKeys[b.url]}==true&&ImageAssetIdentity.same(a,b)||
            sameFrame(a,b)
        "video"->sameClip(a,b)
        else->false
    })
    for(edition in observed.flatMap {post->post.media.map {Edition(it,post)}}) {
        val item=edition.media
        // A discarded copy can establish another CDN alias for the retained
        // picture. Keep that evidence within this call. Every known observation
        // must remain compatible: an intermediate JPEG cannot bridge two edits.
        val matches=clusters.filter {group->group.any {equivalent(it.media,item)}&&group.all {compatible(it.media,item)}}
        val existing=matches.singleOrNull()
        if(existing==null)clusters+=mutableListOf(edition)
        else if(existing.none {it.media==item&&CrossPosts.originalKey(it.post)==CrossPosts.originalKey(edition.post)})existing+=edition
    }
    // Resolve still previews after clustering, so arrival order cannot discard
    // their origin or choose arbitrarily between two distinct playable clips.
    // A preview establishes a matching picture, not video publication on that
    // service; retain that distinction in the existing source label.
    fun represents(image:Media,video:Media):Boolean {
        val preview=video.preview ?: return false
        val proof=image.url==preview||ImageAssetIdentity.same(image,Media(preview))||sameFrame(image,video)
        return proof&&(frame(image)==null||frame(video)==null||sameFrame(image,video))
    }
    val videoGroups=clusters.indices.filter {clusters[it].first().media.type=="video"}
    val previewOf=clusters.indices.filter {clusters[it].first().media.type=="image"}.mapNotNull {i->
        videoGroups.filter {v->clusters[i].all {image->clusters[v].any {represents(image.media,it.media)}}}.singleOrNull()?.let {i to it}
    }.toMap()
    return clusters.indices.filter {it !in previewOf}.map {i->
        val group=clusters[i];val previews=previewOf.filterValues {it==i}.keys.flatMap {clusters[it]}
        // RSS-Bridge explicitly prefixes Instagram videos with a play marker.
        // Its public fallback can supply only the poster. This is a preview
        // edition, not another photograph or a playable video declaration.
        val videoPreview=group.all {it.media.type=="image"&&it.post.platform==Platform.INSTAGRAM&&it.post.media.size==1&&
            it.post.provenance?.source?.frontend=="rss-bridge"&&it.post.title.trimStart().startsWith("▶")&&!it.post.body.trimStart().startsWith("▶")}
        CollectedAttachment(group.first().media,group.map {it.post.platform}.toSet(),(group+previews).map {CrossPosts.originalKey(it.post)}.toSet(),previews.map {it.post.platform}.toSet(),videoPreview)
    }
}

/** A shared poster can share screen space, never erase playable recordings.
 * All distinct media remain in the plan, Context and saved inventory. */
internal data class CollectedMediaGroup(val editions:List<CollectedAttachment>) {
    val primary get()=editions.firstOrNull {it.media.type=="video"} ?: editions.first()
}
internal fun collectedMediaGroups(attachments:List<CollectedAttachment>,relatedScenes:Boolean=false):List<CollectedMediaGroup> {
    fun cover(media:Media)=when(media.type) {
        "image"->media
        "video"->media.preview?.let {Media(it,visualFingerprint=media.visualFingerprint,attachmentFingerprint=media.attachmentFingerprint)}
        else->null
    }
    fun same(a:Media,b:Media):Boolean {
        val x=cover(a) ?: return false;val y=cover(b) ?: return false
        fun frame(m:Media)=m.attachmentFingerprint ?: m.visualFingerprint
        val detail=frame(x)!=null&&frame(y)!=null
        if(relatedScenes&&a.type=="image"&&b.type=="image"&&VisualFrames.same(frame(x),frame(y),nearbyFrame=true))return true
        if(detail&&!VisualFrames.sameImage(frame(x),frame(y)))return false
        return x.url==y.url||ImageAssetIdentity.same(x,y)||detail
    }
    val groups=mutableListOf<MutableList<CollectedAttachment>>()
    for(item in attachments) {
        val matching=groups.filter {group->(relatedScenes||item.media.type=="video"||group.any {it.media.type=="video"})&&group.all {same(it.media,item.media)}}
        val group=matching.singleOrNull()
        if(group==null)groups+=mutableListOf(item)else group+=item
    }
    // Providers routinely choose different opening frames for the same
    // announcement. One playable edition per original can share a visual slot
    // across platforms without asserting that the recordings are identical.
    // A genuine multi-video original keeps its separate slots and every source
    // recording remains selectable, in Context and in the saved inventory.
    val videoCounts=attachments.filter {it.media.type=="video"}.flatMap {it.originalKeys}.groupingBy {it}.eachCount()
    val hasVideo=attachments.any {it.media.type=="video"}
    fun eligible(group:List<CollectedAttachment>)=hasVideo&&group.any {it.media.type=="video"||it.videoPreview}&&
        group.filter {it.media.type=="video"}.all {it.originalKeys.isNotEmpty()&&it.originalKeys.all {key->videoCounts[key]==1}}
    fun platforms(group:List<CollectedAttachment>)=group.filter {it.media.type=="video"}.flatMap {it.platforms}.toSet()
    val slots=mutableListOf<MutableList<CollectedAttachment>>()
    for(group in groups) {
        val target=if(eligible(group))slots.singleOrNull {eligible(it)&&platforms(it).intersect(platforms(group)).isEmpty()}else null
        if(target==null)slots+=group else target+=group
    }
    return slots.map {CollectedMediaGroup(it.toList())}
}
