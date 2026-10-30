/* anti social, GPL-3.0-or-later. One reading intent, not a recording crawler. */
package app.antisocial.android

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.abs

internal data class FeedVideoPreparationInput(val recordings:VideoComparisonInput,val follows:List<Follow>,val memberships:Map<String,Set<String>>,
    val neighborhood:List<Pair<Post,Set<String>>>)
internal data class FeedVideoPreparationSource(val posts:List<Post>,val follows:List<Follow>,val memberships:Map<String,Set<String>>) {
    /** Scheduling only. Missing/ambiguous local ownership waits for the normal
     * visible-card engine; names never establish permission for this head start. */
    fun nearby(focus:Post):List<Post> {
        val at=focus.publishedAt ?: return emptyList()
        if(at<=0)return emptyList()
        val accounts=follows.filter {it.enabled}.associate {it.id to Subscriptions.id(it)}
        fun owner(post:Post)=memberships[post.identityKey].orEmpty().mapNotNull(accounts::get).distinct().singleOrNull()
        val ownerId=owner(focus) ?: return emptyList()
        fun video(post:Post)=!post.preview&&post.parentId==null&&post.recoveredArchive==null&&post.media.count {it.type=="video"}==1
        if(!video(focus))return emptyList()
        return listOf(focus)+posts.asSequence().filter {it.identityKey!=focus.identityKey&&video(it)&&owner(it)==ownerId&&it.publishedAt?.let {t->t>0&&abs(t-at)<=6*60*60_000L}==true}
            .sortedWith(compareBy<Post> {abs(requireNotNull(it.publishedAt)-at)}.thenBy {it.identityKey}).take(23).toList()
    }
    fun input(request:VideoFootageRequest,focusKey:String=request.first.identityKey):FeedVideoPreparationInput? {
        val a=posts.firstOrNull {it.identityKey==request.first.identityKey} ?: return null
        val b=posts.firstOrNull {it.identityKey==request.second.identityKey} ?: return null
        val focus=posts.firstOrNull {it.identityKey==focusKey} ?: return null
        val keys=request.originalKeys
        val ids=keys.flatMap {memberships[it].orEmpty()}.toSet()
        val accounts=if(keys.any {memberships[it].isNullOrEmpty()})follows else follows.filter {it.id in ids}
        fun stable(post:Post)=post.copy(avatar=null,replyCount=null,score=null,contextNotes=null,
            provenance=post.provenance?.copy(fetchedAt=0,cached=false),
            media=post.media.map {it.copy(preview=null,aiMetadata=null,visualFingerprint=null,attachmentFingerprint=null,videoFootage=null)})
        val nearby=nearby(focus).map {stable(it) to memberships[it.identityKey].orEmpty()}
        return FeedVideoPreparationInput(request.copy(first=a,second=b).acquisitionKey,
            accounts.map {it.copy(name="",personName=null)},keys.associateWith {memberships[it].orEmpty()},nearby)
    }
}
internal val LocalFeedVideoPreparation=compositionLocalOf<FeedVideoPreparationSource?> {null}
internal val LocalVideoPreparationPending=compositionLocalOf {false}

/** The root remains mounted while its predicted row becomes a real viewport
 * item. That gives preparation and ordinary dwell one continuous intent. */
@Composable internal fun FeedVideoPreparation(
    list:LazyListState,scope:List<Post>,memory:FeedMemory,ready:Boolean,initializing:Boolean,
    readableRows:List<FrozenFeedRow>,filtering:ReaderFilterSession,blocked:Boolean,repo:Repository,
    onActive:(Set<String>)->Unit,
) {
    val source=LocalFeedVideoPreparation.current
    var pinned by remember {mutableStateOf(false)}
    var focusKey by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(ready,scope) {
        if(!pinned&&ready&&scope.isNotEmpty()) {
            pinned=true
            focusKey=FeedReading.restore(memory,scope.map {it.identityKey})?.key ?: scope.first().identityKey
        }
    }
    val focus=scope.firstOrNull {it.identityKey==focusKey} ?: return
    if(focus.media.count {it.type=="video"}!=1)return
    val filterGroups=remember(focus){listOf(listOf(focus))}
    val assessments=rememberFilterAssessments(repo,filtering,filterGroups)
    val permitted=assessments.decision(filterGroups.single())?.withheld==false||focus.identityKey in filtering.revealed
    val running=remember {java.util.concurrent.atomic.AtomicReference<Job?>()}
    val attention=rememberReaderWorkAttention {running.get()?.cancel(CancellationException("Reader attention ended"))}
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val playing by repo.videos.playbackActive.collectAsStateWithLifecycle()
    val paused=LocalArchiveChecksPaused.current||blocked
    val latestSource by rememberUpdatedState(source)
    val latestRows by rememberUpdatedState(readableRows)
    val visible by remember(list,focusKey){derivedStateOf {
        val row=latestRows.firstOrNull {focusKey in it.aliases}
        row!=null&&list.layoutInfo.let {info->info.visibleItemsInfo.firstOrNull {it.key.toString()==row.key}?.let {
            FeedDwellGeometry(it.offset,it.size,info.viewportStartOffset,info.viewportEndOffset,list.isScrollInProgress).readable
        }==true}
    }}
    var attempted by remember {mutableStateOf(false)}
    // A settled filter result can precede its LazyColumn layout by a frame.
    // Wait through that handoff, then require the actual expected card.
    var handedOff by remember {mutableStateOf(false)}
    LaunchedEffect(initializing){if(!initializing){withFrameNanos {};handedOff=true}}
    val allowed=source!=null&&permitted&&attention.active&&!paused&&!playing&&!dragged&&(!handedOff||visible)
    val acquisition=focus.media.filter {it.type=="video"}.map(VideoAssetIdentity::acquisitionKey)
    val rules=filtering.applied;val evaluatedAt=filtering.evaluatedAt
    LaunchedEffect(allowed,acquisition,rules,evaluatedAt,attention.revision) {
        if(!allowed||attempted)return@LaunchedEffect
        val job=currentCoroutineContext()[Job]!!;running.set(job)
        try {
        delay(250)
        if(!repo.videos.automaticAllowed())return@LaunchedEffect
        attempted=true
        val snapshot=latestSource ?: return@LaunchedEffect
        val request=try {withTimeout(750) {withContext(Dispatchers.Default) {
            val context=currentCoroutineContext()
            val nearby=snapshot.nearby(focus).map(repo.videos::captured)
            // Reuse actual caption grouping on this bounded neighborhood so
            // already collectible captions never cause speculative video reads.
            val groups=CrossPosts.groupCancellable(nearby,snapshot.follows,snapshot.memberships)
            val group=groups.firstOrNull {g->g.posts.any {it.identityKey==focus.identityKey}} ?: return@withContext null
            val verdict=ContentFilters.compile(rules).evaluate(group.posts.map {filterProjection(repo,it)},filtering.context,evaluatedAt)
            if(verdict.withheld&&focus.identityKey !in filtering.revealed)return@withContext null
            CrossPosts.focusedVideoFootageRequests(groups,snapshot.follows,snapshot.memberships,setOf(focus.identityKey),
                sharedFootage={a,b->repo.videos.related(a,b){context.ensureActive()}},checkpoint={context.ensureActive()})
                .firstOrNull(repo.videos::available)
        }}}catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();return@LaunchedEffect}
        request ?: return@LaunchedEffect
        val input=snapshot.input(request,focus.identityKey) ?: return@LaunchedEffect
        if(input.recordings!=request.acquisitionKey)return@LaunchedEffect
        if(latestSource?.input(request,focus.identityKey)!=input)return@LaunchedEffect
        onActive(request.originalKeys)
        try {coroutineScope {
            val work=async {repo.videos.enrichPrepared(request){snapshotFlow {visible}.first {it};Unit}}
            val monitor=launch {
                snapshotFlow {latestSource?.input(request,focus.identityKey)}.first {it!=input}
                work.cancel(CancellationException("Prepared recording input or ownership changed"))
            }
            try {work.await()}finally {monitor.cancel()}
        }}finally {onActive(emptySet())}
        }finally {running.compareAndSet(job,null)}
    }
}
