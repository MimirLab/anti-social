/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

internal data class FrozenFeedRow(val key: String,val aliases: List<String>,val posts: List<Post>,val bodyPosts: List<Post> = posts,val explanation: FeedRuleExplanation?=null,val orderAt:Long?=posts.first().publishedAt) {
    val primary get()=posts.first()
}
/** Initial rows normally have identical displayed and admitted editions. Do
 * their pure presentation projection once. A late revision remains separate;
 * original keys alone cannot establish that the two bodies are identical. */
internal fun projectFeedReadingRows(rows:List<FrozenFeedRow>,project:(Post)->Post):List<Triple<FrozenFeedRow,List<Post>,List<Post>>> = rows.map {row->
    val body=row.bodyPosts.map(project)
    Triple(row,body,if(row.bodyPosts==row.posts)body else row.posts.map(project))
}
/** A late, fully qualified component owns one reading card. Preserve the visible
 * card's body/key, or the first established position when it is off screen.
 * Partial overlaps never bridge two previously admitted announcements. */
internal fun coalesceFeedRows(rows:List<FrozenFeedRow>,counterparts:Map<String,List<Post>>,visible:Set<String>,key:(Post)->String):List<FrozenFeedRow> {
    val components=rows.map {row->row.aliases.asSequence().mapNotNull {alias->
        counterparts[alias]?.mapTo(linkedSetOf(alias),key)?.takeIf {it.containsAll(row.aliases)}
    }.firstOrNull()}
    val buckets=rows.indices.filter {components[it]!=null}.groupBy {components[it]!!}
    val replacements=mutableMapOf<Int,FrozenFeedRow>();val removed=mutableSetOf<Int>()
    for((component,indices) in buckets) {
        if(indices.size<2)continue
        val winner=indices.firstOrNull {rows[it].key in visible} ?: indices.first()
        val row=rows[winner]
        val editions=(row.posts+indices.filter {it!=winner}.flatMap {rows[it].posts}).distinctBy(key)
        if(editions.any {key(it) !in component})continue
        replacements[winner]=row.copy(aliases=editions.map(key),posts=editions)
        removed+=indices.filter {it!=winner}
    }
    return rows.indices.filter {it !in removed}.map {replacements[it] ?: rows[it]}
}
private data class FeedRestore(val row: Int,val offset: Int,val exact: Boolean)
private data class FeedContent(val aliases: Set<String>,val text: List<String>,val media: Set<String>)
private data class ManualFeedRefresh(val generation: Long,val before: List<FeedContent>)
private data class FeedBodyChanges(val rows:List<FrozenFeedRow>,val available:Map<String,Post>,val counterparts:Map<String,List<Post>>,
    val sources:Set<String>,val captions:Set<String>,val details:Set<String>,val regroup:Boolean)
internal data class FeedDetailsDraft(val row:FrozenFeedRow,val window:List<Post>,val groups:Map<String,List<Post>>,val replacement:FrozenFeedRow) {
    fun current(rows:List<FrozenFeedRow>?,window:List<Post>,groups:Map<String,List<Post>>,ready:Boolean):Boolean {
        val keys=replacement.aliases.toSet()
        return ready&&rows?.firstOrNull {it.key==row.key}==row&&window.filter {it.identityKey in keys}==this.window&&
            this.groups.all {(key,posts)->groups[key].orEmpty()==posts}
    }
}

/** LazyColumn can retain a preceding item while only its trailing spacing
 * overlaps the viewport. Anchor to actual card content, keeping partly visible
 * cards and the content-padding region in the same coordinate system. */
internal fun LazyListLayoutInfo.feedViewportItems(): List<LazyListItemInfo> = visibleItemsInfo.filter {item->
    item.size>0&&item.offset.toLong()+item.size>viewportStartOffset.toLong()&&item.offset<viewportEndOffset
}

/** Keep the full fetched prefix available while preserving the visible reading card. */
@Composable internal fun ChronologicalFeed(
    vm: AntiSocialViewModel,posts: List<Post>,follows: List<Follow>,savedKeys: Set<String>,feedId: String,
    title: String,subtitle: String,grouped: Boolean=false,onAdd: ()->Unit,
    onRefresh: (()->Unit)?=null,selector: (@Composable ()->Unit)?=null,
    counterparts: Map<String,List<Post>> = emptyMap(),counterpartsReady: Boolean=true,
    explanations: Map<String,FeedRuleExplanation>?=null,emptyTitle: String?=null,emptyMessage: String?=null,onEditRules: (()->Unit)?=null,
) {
    key(feedId,vm.repo.feedMemory) {ChronologicalFeedSession(vm,posts,follows,savedKeys,feedId,title,subtitle,grouped,onAdd,onRefresh,selector,counterparts,counterpartsReady,explanations,emptyTitle,emptyMessage,onEditRules)}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ChronologicalFeedSession(
    vm: AntiSocialViewModel,posts: List<Post>,follows: List<Follow>,savedKeys: Set<String>,feedId: String,
    title: String,subtitle: String,grouped: Boolean,onAdd: ()->Unit,
    onRefresh: (()->Unit)?,selector: (@Composable ()->Unit)?,
    counterparts: Map<String,List<Post>>,counterpartsReady: Boolean,
    explanations: Map<String,FeedRuleExplanation>?,emptyTitle: String?,emptyMessage: String?,onEditRules: (()->Unit)?,
) {
    val repo=vm.repo;val store=repo.feedMemory
    val selectedChannel by repo.channels.selected.collectAsState()
    val filtering=rememberReaderFilters(repo,ContentFilterContext(FilterSurface.FEED,channel=selectedChannel.takeIf {feedId.startsWith("channel:")}))
    val memory by remember(store,feedId){store.reading(feedId)}.collectAsState(initial=store.get(feedId))
    val checks by store.refresh.collectAsState()
    val refresh by repo.feedRefresh.collectAsState()
    val ready by store.ready.collectAsState()
    val state by vm.screen.collectAsState()
    val readKeys=remember(memory.readKeys){memory.readKeys.toHashSet()}
    val freshKeys=remember(memory.freshKeys){memory.freshKeys.toHashSet()}
    val scope=rememberCoroutineScope()
    val list=rememberLazyListState()
    val density=LocalDensity.current
    var endFillPx by remember {mutableIntStateOf(0)}
    // A short feed used to fit inside the larger chrome. With more reading
    // space, native bottom clamping can move its anchor when a prefix arrives.
    // Turn only the existing unused viewport space into bounded end padding.
    // The visible cards stay put and newly inserted rows can scroll above them.
    LaunchedEffect(list) {
        snapshotFlow {
            val info=list.layoutInfo
            val end=info.visibleItemsInfo.lastOrNull {it.index==info.totalItemsCount-1}
            if(end==null||list.isScrollInProgress)0 else (info.viewportEndOffset-end.offset-end.size-info.afterContentPadding).coerceAtLeast(0)
        }.distinctUntilChanged().collect {missing->
            if(missing>0)endFillPx=(endFillPx+missing).coerceAtMost(list.layoutInfo.viewportSize.height)
        }
    }
    val ordered=remember(posts){(CachePolicy.chronological(posts)+posts.filter {it.publishedAt==null}).distinctBy(repo::key)}
    val latestWindow by rememberUpdatedState(ordered)
    val latestGroups by rememberUpdatedState(counterparts)
    val latestGroupsReady by rememberUpdatedState(counterpartsReady)
    val available=remember(ordered){ordered.associateBy(repo::key)}
    val availableKeys=remember(ordered){ordered.map(repo::key)}
    var rows by remember {mutableStateOf<List<FrozenFeedRow>?>(null)}
    var preparingVideoKeys by remember {mutableStateOf<Set<String>>(emptySet())}
    var baseline by remember {mutableStateOf<Set<String>>(emptySet())}
    var restoreMemory by remember {mutableStateOf<FeedMemory?>(null)}
    var restored by remember {mutableStateOf(false)}
    var navigating by remember {mutableStateOf(false)}
    var preparing by remember {mutableStateOf(false)}
    var blockedAnchor by remember {mutableStateOf<String?>(null)}
    val visibleKeys by remember(list) {derivedStateOf {list.layoutInfo.feedViewportItems().map {it.key.toString()}.toSet()}}
    val snackbar=remember {SnackbarHostState()}
    var manualRefresh by remember {mutableStateOf<ManualFeedRefresh?>(null)}
    val pullState=rememberPullToRefreshState()
    val exitSeed=remember {System.nanoTime().toInt()}
    val exitLine=remember(exitSeed){FeedExitCopy.line(exitSeed)}
    var earlier by remember {mutableStateOf(false)}
    var nearbyNotice by remember {mutableStateOf<String?>(null)}
    var showSources by remember {mutableStateOf(false)}
    var showOptions by remember {mutableStateOf(false)}
    var why by remember {mutableStateOf<FeedRuleExplanation?>(null)}
    val accounts=follows.filter {it.enabled}.distinctBy {it.id}
    val accountIds=accounts.map {it.id}
    val updating=refresh.running&&refresh.requested.any {it in accountIds}
    val frozen=rows.orEmpty()
    val filterGroups=remember(frozen){frozen.map {it.bodyPosts}}
    val assessments=rememberFilterAssessments(repo,filtering,filterGroups)
    val decisions=remember(frozen,assessments){frozen.associate {it.key to assessments.decision(it.bodyPosts)}}
    fun decision(row: FrozenFeedRow)=decisions[row.key]
    fun revealed(row: FrozenFeedRow)=row.aliases.any {it in filtering.revealed}
    fun readable(row: FrozenFeedRow)=decision(row)?.let {!it.withheld||revealed(row)}==true
    fun rendered(values: List<FrozenFeedRow>)=values.filter {decision(it)?.action!=ContentAction.HIDE||revealed(it)}
    val filteredCount=frozen.count {decision(it)?.withheld==true&&!revealed(it)}
    val filtersPending=frozen.any {decision(it)==null}
    val contextOptions by repo.contextReader.preferences.state.collectAsState()
    val contextModels by repo.ai.state.collectAsState()
    val contextMoving by remember(list){derivedStateOf {list.isScrollInProgress}}
    DisposableEffect(feedId){onDispose {repo.contextReader.clearAhead(feedId)}}
    LaunchedEffect(feedId,frozen,visibleKeys,contextMoving,contextOptions,contextModels.enabled,memory.readKeys,filtersPending) {
        if(!contextOptions.ahead||!contextModels.enabled||!restored||navigating||preparing||contextMoving||filtersPending) {
            repo.contextReader.clearAhead(feedId);return@LaunchedEffect
        }
        delay(900)
        if(list.isScrollInProgress)return@LaunchedEffect
        val anchor=frozen.indexOfFirst {it.key in visibleKeys}
        val nearby=if(anchor<=0)emptyList()else frozen.take(anchor).asReversed().filter {row->
            decision(row)?.withheld==false&&row.aliases.any {it !in readKeys}
        }.take(contextOptions.lookahead)
        val snapshots=withContext(Dispatchers.Default){nearby.map {row->contextSnapshot(repo,row.primary,row.posts,row.bodyPosts)}}
        repo.contextReader.ahead(feedId,snapshots)
    }
    val aliases=remember(frozen){frozen.flatMap {it.aliases}}
    val rowByAlias=remember(frozen){frozen.flatMapIndexed {index,row->row.aliases.map {it to index}}.toMap()}
    val baselineRows=remember(frozen,baseline){frozen.filter {row->row.aliases.all {it in baseline}}.map {it.key}.toSet()}
    val boundary=remember(frozen,baselineRows){FeedReading.boundary(frozen.map {it.key},baselineRows) ?: frozen.size}
    val pending=RefreshLedger.pending(accountIds,checks)
    val matchedAliases=remember(frozen,counterparts) {frozen.flatMap {row->row.aliases+row.aliases.flatMap {key->counterparts[key].orEmpty().map(repo::key)}}.toSet()}
    val staged=memory.stagedKeys.count {it !in matchedAliases}
    // A formerly accepted post can reappear after its cached row was evicted.
    // Restore it in the next coherent window without calling it newly arrived.
    val returned=remember(availableKeys,memory.acceptedKeys,matchedAliases) {
        val accepted=memory.acceptedKeys.toSet();val shown=matchedAliases
        availableKeys.count {it in accepted&&it !in shown}
    }
    // Both Following and combined feeds can acquire another source edition.
    // Compare it with the frozen body even when its root is outside this feed's
    // retrieval window. Only the visible card retains its current wording until it leaves the viewport.
    fun latestEditions(posts: List<Post>,known: List<Post>): List<Post> {
        val current=known.associateBy(repo::key)
        return orderedCollectedOriginals(posts.map {current[repo.key(it)] ?: it}+known)
    }
    // Content is compared when source material changes, never on each viewport
    // transition. Visibility only controls when an already identified change
    // may be adopted; the card under the finger retains its frozen body.
    val bodyChanges by produceState<FeedBodyChanges?>(null,frozen,available,counterparts) {
        value=withContext(Dispatchers.Default) {
            val owners=frozen.flatMap {row->row.aliases.map {it to row.key}}.toMap()
            fun sourceContent(post:Post)=post.copy(provenance=null,archivedVersion=null,media=post.media.map {it.copy(visualFingerprint=null,attachmentFingerprint=null,videoFootage=null,samplingUrl=null)})
            fun contexts(posts: List<Post>): List<Triple<String,Set<Platform>?,Set<String>?>> = collectedPostFusion(posts)?.segments
                ?.map {Triple(it.text,if(it.shared)null else it.platforms,if(it.shared)null else it.originalKeys)}
                ?: listOf(Triple(collectedCaptionPost(posts)?.let(PostPresentation::content).orEmpty(),null,null))
            val sources=mutableSetOf<String>();val captions=mutableSetOf<String>();val details=mutableSetOf<String>();var regroup=false
            frozen.forEach {row->
                ensureActive()
                if(row.posts.any {old->available[repo.key(old)]?.let {it!=old&&sourceContent(it)!=sourceContent(old)}==true})sources+=row.key
                val known=row.aliases.flatMap {counterparts[it].orEmpty()}
                if(known.any {post->owners[repo.key(post)]?.let {it!=row.key}==true})regroup=true
                val current=latestEditions(row.posts,known)
                if(current!=row.bodyPosts) {
                    val newer=contexts(current);val shown=contexts(row.bodyPosts)
                    val wording=newer.map {it.first}!=shown.map {it.first}
                    val media=!wording&&addsCollectedMedia(row.bodyPosts,current,repo.images.identities)
                    if(newer!=shown||media)captions+=row.key
                    // Source attribution can settle independently. Only text
                    // or media changes need an explicit in-card offer.
                    if(wording||media)details+=row.key
                }
            }
            FeedBodyChanges(frozen,available,counterparts,sources,captions,details,regroup)
        }
    }
    val changes=bodyChanges?.takeIf {it.rows==frozen&&it.available==available&&it.counterparts==counterparts}
    val changed=changes?.sources?.any {it !in visibleKeys}==true
    val fusionChanged=changes?.let {it.regroup||it.captions.any {key->key !in visibleKeys}}==true
    val removedByRules=explanations!=null&&frozen.any {row->row.aliases.none {it in available}}
    val incoming=counterpartsReady&&(staged>0||returned>0||changed||fusionChanged||removedByRules)
    val unread=frozen.count {row->readable(row)&&row.aliases.any {it !in readKeys}}
    val fresh=frozen.count {row->readable(row)&&row.aliases.any {it !in readKeys}&&row.aliases.any {it in freshKeys}}
    val unreadBefore=frozen.take(boundary).count {row->readable(row)&&row.aliases.any {it !in readKeys}}
    val caught=(frozen.isNotEmpty()||explanations!=null)&&counterpartsReady&&changes!=null&&RefreshLedger.caughtUp(accountIds,checks)&&unreadBefore==0&&!incoming&&!filtersPending
    val batchFinished=frozen.isNotEmpty()&&counterpartsReady&&changes!=null&&unreadBefore==0&&!incoming&&!filtersPending&&!updating&&!state.loading
    val canContinue=FeedReading.restoreReturn(memory,aliases)!=null

    fun extend(row: FrozenFeedRow,owners: Map<String,String> = emptyMap(),adopting: Boolean=false): FrozenFeedRow {
        val known=row.aliases.flatMap {counterparts[it].orEmpty()}.filter {post->owners[repo.key(post)]?.let {it==row.key} ?: true}
        val members=(if(adopting)latestEditions(row.posts,known)else (row.posts+known).distinctBy(CrossPosts::originalKey).take(CrossPosts.MAX_EDITIONS))
            .map {it.copy(media=it.media.toList(),links=it.links?.toList())}
        return row.copy(key=if(adopting)repo.key(members.first())else row.key,aliases=members.map(repo::key),posts=members,explanation=if(adopting)members.firstNotNullOfOrNull {explanations?.get(repo.key(it))} ?: row.explanation else row.explanation)
    }
    suspend fun freeze(keys: List<String>,source: Map<String,Post>): List<FrozenFeedRow> {
        val snapshots=keys.mapNotNull(source::get).map {it.copy(media=it.media.toList(),links=it.links?.toList())}
        val groups=when {
            counterparts.isEmpty()->withContext(Dispatchers.Default){CrossPosts.groupCancellable(snapshots,accounts,sameMedia=repo.images.identities::same)}
            else->{
                val accepted=snapshots.associateBy(repo::key);val used=mutableSetOf<String>()
                snapshots.mapNotNull {post->
                    val key=repo.key(post)
                    if(!used.add(key))null else {
                        val members=listOf(post)+counterparts[key].orEmpty().mapNotNull {accepted[repo.key(it)]}.filter {repo.key(it)!=key}
                        used+=members.map(repo::key);PostGroup(orderedCollectedOriginals(members))
                    }
                }
            }
        }
        return groups.map {group->FrozenFeedRow(repo.key(group.primary),group.posts.map(repo::key),group.posts.toList(),explanation=group.posts.firstNotNullOfOrNull {explanations?.get(repo.key(it))})}
            // Explicit adoption also freezes admitted source editions whose
            // cached roots fall outside this feed's current retrieval window.
            .map {extend(it,adopting=true).let {row->row.copy(bodyPosts=row.posts)}}
            .sortedWith(compareByDescending<FrozenFeedRow> {it.orderAt ?: Long.MIN_VALUE}.thenBy {it.key})
    }
    suspend fun prepareReadAliases(values: List<FrozenFeedRow>,observe: Boolean=false): ()->Unit = withContext(Dispatchers.Default) {
        val projected=projectFeedReadingRows(values){filterProjection(repo,it)}
        val observations=if(observe)ContentRevisions.observations(projected.flatMap {it.second})else emptyList()
        val commit: ()->Unit = {
            if(observe)store.observePrepared(observations)
            val read=store.get(feedId).readKeys
            val groups=projected.filter {it.first.aliases.size>1}
            val inheritable=groups.filter {(_,body,posts)->
                val seen=body.filter {repo.key(it) in read}
                !ContentRevisions.addsContext(seen,posts,includeMedia=false)&&!addsCollectedMedia(seen,posts,repo.images.identities)
            }.map {it.first.key}.toSet()
            store.mergeAliases(feedId,groups.filter {it.first.key in inheritable}.map {it.first.aliases})
            store.mergeAliases(feedId,groups.filter {it.first.key !in inheritable}.map {it.first.aliases},inheritRead=false)
        }
        commit
    }
    fun checkpoint(currentRows: List<FrozenFeedRow> = frozen) {
        val currentAliases=currentRows.flatMap {it.aliases}.toSet()
        val item=list.layoutInfo.feedViewportItems().firstOrNull {it.key.toString() in currentAliases} ?: return
        store.anchor(feedId,item.key.toString(),-item.offset)
    }
    fun restorePosition(record: FeedMemory,returning: Boolean=false,currentRows: List<FrozenFeedRow> = frozen): FeedRestore? {
        val displayed=rendered(currentRows)
        val all=displayed.flatMap {it.aliases}
        val position=if(returning)FeedReading.restoreReturn(record,all)else FeedReading.restore(record,all)
        position ?: return null
        val index=displayed.indexOfFirst {position.key in it.aliases}
        return index.takeIf {it>=0}?.let {FeedRestore(it,position.offset,position.key==(if(returning)record.resumeReturn?.key else record.anchorKey))}
    }
    fun stoppingPoint(currentRows: List<FrozenFeedRow>): Int {val displayed=rendered(currentRows);return FeedReading.boundary(displayed.map {it.key},displayed.filter {row->row.aliases.all {it in baseline}}.map {it.key}.toSet()) ?: displayed.size}
    suspend fun scroll(position: FeedRestore?,currentRows: List<FrozenFeedRow> = frozen) {
        if(position==null)return
        val stopping=stoppingPoint(currentRows)
        if(position.row>=stopping)earlier=true
        withFrameNanos {};withFrameNanos {}
        list.scrollToItem(position.row+if(position.row>=stopping)1 else 0,position.offset)
        withFrameNanos {}
    }
    fun content(values: List<FrozenFeedRow>): List<FeedContent> = values.map {row->
        FeedContent(row.aliases.toSet(),collectedCaptionTexts(row.posts),row.posts.flatMap {it.media}.map {it.url}.toSet())
    }
    fun requestRefresh() {
        if(accounts.isEmpty()) {scope.launch {snackbar.showSnackbar("Add a subscription to check for posts.")};return}
        if(refresh.running||state.loading||manualRefresh!=null) {
            scope.launch {snackbar.showSnackbar("Still checking sources. Your loaded posts are ready to read.")}
            return
        }
        manualRefresh=ManualFeedRefresh(refresh.generation,content(frozen))
        (onRefresh ?: {vm.refresh()})()
    }
    fun accept(jump: Boolean,startBatch: Boolean=true) {
        if(!restored||navigating||preparing||!counterpartsReady)return
        if(!jump&&availableKeys.isEmpty()&&explanations==null)return
        preparing=true
        scope.launch {
        try {
            checkpoint()
            if(jump){blockedAnchor=null;store.keepReturn(feedId)}
            var before=store.get(feedId)
            // Explicitly adopting a batch starts a new stopping baseline. Read
            // dwell alone never moves the separator under an active session.
            val nextSource=ordered.associateBy(repo::key);val nextKeys=ordered.map(repo::key)
            val preview=FeedReading.reconcile(store.get(feedId),nextKeys,System.currentTimeMillis())
            val admitted=FeedReading.accept(preview,nextKeys,preview.stagedKeys,System.currentTimeMillis())
            val collected=freeze(admitted.acceptedKeys,nextSource)
            if(!startBatch) {
                // Grouping can overlap the reader's next gesture. Keep normal
                // anchor saving active during preparation, then commit at idle.
                snapshotFlow {list.isScrollInProgress}.first {!it}
            }
            // A newer Room generation or image match can finish during grouping.
            // Discard this preparation rather than committing an obsolete window.
            if(latestWindow!=ordered||latestGroups!=counterparts||!latestGroupsReady)return@launch
            if(!jump) {checkpoint();before=store.get(feedId)}
            // Keep the visible identity even if regrouping chose another primary
            // original. Native keyed anchoring then preserves the viewport while
            // the complete newer prefix is inserted, without a synthetic scroll.
            val visibleBefore=frozen.filter {it.key in visibleKeys}
            val next=if(startBatch)collected else collected.map {row->
                val locked=visibleBefore.firstOrNull {old->old.aliases.any {it in row.aliases}}
                // Keep the earliest already displayed collection's position,
                // including after it leaves the viewport. A later-discovered
                // older counterpart must not move a read card below the reader
                // and make the same announcement appear a second time.
                val established=frozen.firstOrNull {it.key in row.aliases}
                val stable=before.anchorKey?.takeIf {it in row.aliases} ?: locked?.key?.takeIf {it in row.aliases} ?: established?.key ?: row.key
                val primary=(locked ?: established)?.primary?.let {old->row.posts.firstOrNull {repo.key(it)==repo.key(old)}}
                row.copy(key=stable,posts=primary?.let {listOf(it)+row.posts.filter {p->repo.key(p)!=repo.key(it)}} ?: row.posts,
                    bodyPosts=locked?.bodyPosts ?: row.bodyPosts,orderAt=established?.orderAt ?: row.orderAt)
            }.sortedWith(compareByDescending<FrozenFeedRow>{it.orderAt ?: Long.MIN_VALUE}.thenBy {it.key})
            if(!startBatch&&before.anchorKey!=null&&next.none {it.key==before.anchorKey}) {
                // Cache/rule removal of the actual reading point needs explicit
                // navigation; keep its frozen card under the reader's finger.
                blockedAnchor=before.anchorKey
                nearbyNotice="Reading post unavailable; use Newest to update."
                store.flush();return@launch
            }
            // All expensive preparation precedes the atomic main-thread
            // admission. No suspension can expose accepted memory with old cards.
            val commitRead=prepareReadAliases(next,observe=true)
            if(latestWindow!=ordered||!latestGroupsReady||latestGroups!=counterparts)return@launch
            store.reconcile(feedId,nextKeys)
            store.accept(feedId,nextKeys,store.get(feedId).stagedKeys.toList())
            commitRead()
            navigating=true
            if(startBatch)baseline=store.get(feedId).readKeys.toSet()
            val position=if(jump)null else restorePosition(before,currentRows=next)
            if(!jump&&before.anchorKey!=null&&position?.exact!=true)nearbyNotice="Saved post unavailable; resumed nearby."
            else nearbyNotice=null
            if(position!=null&&position.row>=stoppingPoint(next))earlier=true
            rows=next
            withFrameNanos {}
            if(jump) {
                val stopping=stoppingPoint(next)
                earlier=stopping==0&&next.isNotEmpty()
                withFrameNanos {};withFrameNanos {}
                list.scrollToItem(if(stopping==0&&next.isNotEmpty())1 else 0)
            }else if(startBatch)scroll(position,next)
            withFrameNanos {}
            restored=true
            checkpoint(next);store.flush()
        }finally {navigating=false;preparing=false}
    }}
    val latestDetailsDraft by rememberUpdatedState<(String)->FeedDetailsDraft?>({key->
        val current=frozen.firstOrNull {it.key==key}
        if(current==null||!counterpartsReady)null else {
            val groups=current.aliases.associateWith {counterparts[it].orEmpty()}
            val sources=latestEditions(current.posts,groups.values.flatten())
            val primary=sources.firstOrNull {repo.key(it)==repo.key(current.primary)}
            val editions=primary?.let {listOf(it)+sources.filter {post->repo.key(post)!=repo.key(it)}} ?: sources
            val replacement=current.copy(aliases=editions.map(repo::key),posts=editions,bodyPosts=editions)
            val keys=replacement.aliases.toSet()
            FeedDetailsDraft(current,ordered.filter {it.identityKey in keys},groups,replacement)
        }
    })
    fun showCapturedDetails(key:String) {
        if(!restored||navigating||preparing||!counterpartsReady||changes?.details?.contains(key)!=true)return
        val first=latestDetailsDraft(key) ?: return
        preparing=true
        scope.launch {
            try {repo.cardWork.track(first.replacement.posts.map(::postWorkKey),"Showing captured details") {
                repeat(4) {
                    val draft=latestDetailsDraft(key)
                    if(draft==null){withFrameNanos {};return@repeat}
                    val commitRead=prepareReadAliases(listOf(draft.replacement),observe=true)
                    snapshotFlow {list.isScrollInProgress}.first {!it}
                    // An unrelated card's source update cannot invalidate this
                    // action. A relevant update gets a fresh draft next frame,
                    // rather than silently discarding the reader's request.
                    if(!draft.current(rows,latestWindow,latestGroups,latestGroupsReady)){withFrameNanos {};return@repeat}
                    val sourceRows=rows ?: return@track
                    checkpoint(sourceRows);navigating=true
                    val next=sourceRows.map {if(it.key==key)draft.replacement else it}
                    commitRead();rows=next
                    withFrameNanos {};checkpoint(next);store.flush()
                    return@track
                }
                scope.launch {snackbar.showSnackbar("New details are still settling. Try again shortly.")}
            }}finally {navigating=false;preparing=false}
        }
    }
    fun continueReading() {scope.launch {
        navigating=true
        try {
            val position=restorePosition(store.get(feedId),returning=true)
            scroll(position)
            if(position?.exact==false)nearbyNotice="Saved post unavailable; resumed nearby."
            store.clearReturn(feedId);checkpoint();store.flush()
        }finally {navigating=false}
    }}

    LaunchedEffect(store){store.awaitReady()}
    LaunchedEffect(ready,availableKeys,counterpartsReady) {
        if(!ready)return@LaunchedEffect
        store.reconcile(feedId,availableKeys)
        val record=store.get(feedId)
        if(rows==null&&record.initialized&&availableKeys.isNotEmpty()&&counterpartsReady) {
            // A restored anchor is a place in the current fetched window, not a
            // reason to hide everything obtained since the previous session.
            // Leave empty Room startup emissions uncommitted until content arrives.
            store.enterSession(feedId,availableKeys)
            val initial=freeze(store.get(feedId).acceptedKeys,available)
            prepareReadAliases(initial,observe=true).invoke()
            val merged=store.get(feedId)
            baseline=merged.readKeys.toSet();restoreMemory=merged
            rows=initial
        }
    }
    // A late match must collapse existing cards too, not merely add each other's
    // source icons. The retained visible card's body/position remain frozen.
    val sidecarRows=rows
    LaunchedEffect(sidecarRows,counterparts,counterpartsReady) {
        if(sidecarRows==null||!counterpartsReady)return@LaunchedEffect
        val coalesced=coalesceFeedRows(sidecarRows,counterparts,visibleKeys,repo::key)
        val owners=coalesced.flatMap {row->row.aliases.map {it to row.key}}.toMap()
        val extended=coalesced.map {extend(it,owners)}
        if(extended==sidecarRows)return@LaunchedEffect
        val commitRead=prepareReadAliases(extended)
        // Preparing aliases suspends on the worker. It must not overwrite a
        // newer atomic regroup which committed while this sidecar was prepared.
        if(rows!=sidecarRows||latestGroups!=counterparts||!latestGroupsReady)return@LaunchedEffect
        commitRead()
        val inherited=extended.flatMap {next->sidecarRows.firstOrNull {it.key==next.key}?.takeIf {old->old.aliases.all {it in baseline}&&!ContentRevisions.addsContext(old.bodyPosts,next.posts)}?.let {next.aliases}.orEmpty()}
        baseline=baseline+inherited
        rows=extended
    }
    // The initializing effect above may publish rows before this composition's
    // effects start. Restore only the immutable rows captured by this composition.
    val initialRows=rows
    LaunchedEffect(initialRows!=null) {
        if(initialRows==null||restored)return@LaunchedEffect
        navigating=true
        try {
            val record=restoreMemory ?: store.get(feedId)
            val position=restorePosition(record,currentRows=initialRows)
                ?: initialRows.takeIf {it.isNotEmpty()}?.let {FeedRestore(0,0,record.anchorKey==null)}
            if(record.anchorKey!=null&&position?.exact!=true)nearbyNotice="Saved post unavailable; resumed nearby."
            scroll(position,initialRows)
            restored=true
        }finally {navigating=false}
    }
    LaunchedEffect(list,restored,frozen) {
        if(!restored)return@LaunchedEffect
        snapshotFlow {list.layoutInfo.feedViewportItems().firstOrNull {it.key.toString() in rowByAlias}?.let {it.key.toString() to -it.offset}}
            .filterNotNull().distinctUntilChanged().collect {(key,offset)->if(!navigating)store.anchor(feedId,key,offset)}
    }
    // Commit one complete source generation at idle. Native stable keys keep
    // the current card at the same pixel while the entire newer prefix appears.
    LaunchedEffect(incoming,restored,navigating,preparing,availableKeys,counterpartsReady,visibleKeys,memory.anchorKey) {
        if(!incoming||!restored||navigating||preparing||!counterpartsReady||availableKeys.isEmpty()&&explanations==null)return@LaunchedEffect
        if(blockedAnchor!=null&&blockedAnchor==memory.anchorKey)return@LaunchedEffect
        snapshotFlow {list.isScrollInProgress}.first {!it}
        accept(false,startBatch=false)
    }
    LaunchedEffect(manualRefresh,refresh,counterpartsReady,availableKeys,checks) {
        val request=manualRefresh ?: return@LaunchedEffect
        if(refresh.generation<=request.generation||refresh.running||!counterpartsReady)return@LaunchedEffect
        if(state.error!=null||accountIds.isEmpty()||refresh.requested.intersect(accountIds.toSet()).isEmpty()||
            refresh.failed.any {it in accountIds}||!RefreshLedger.caughtUp(accountIds,checks)){manualRefresh=null;return@LaunchedEffect}
        val after=withContext(Dispatchers.Default) {content(freeze(availableKeys,available))}
        val newContent=after.any {item->request.before.none {old->
            old.aliases.any {it in item.aliases}&&old.text==item.text&&old.media==item.media
        }}
        manualRefresh=null
        val boundaryVisible=list.layoutInfo.feedViewportItems().any {it.key=="boundary:$feedId"}
        if(!newContent&&!(unreadBefore==0&&!filtersPending&&boundaryVisible))scope.launch {snackbar.showSnackbar(if(unreadBefore==0&&filtersPending.not())exitLine else "No new posts. $unread still unread in this batch.")}
    }
    // Caption/filter projection can settle just after the refresh callback, or
    // the reader can scroll to the boundary while a snackbar is still visible.
    // Keep one copy of the exit line in either case.
    LaunchedEffect(batchFinished,visibleKeys,exitLine) {
        if(batchFinished&&"boundary:$feedId" in visibleKeys)snackbar.currentSnackbarData?.takeIf {it.visuals.message==exitLine}?.dismiss()
    }
    val saveCheckpoint by rememberUpdatedState({if(restored&&!navigating)checkpoint();repo.scope.launch {store.flush()};Unit})
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,store,feedId) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP)saveCheckpoint()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);saveCheckpoint()}
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().testTag("Chronological feed")) {
        // One fixed-height strip: status changes never insert space above the
        // reading card. Secondary actions live in the adjacent options menu.
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start=16.dp,end=4.dp).testTag("Feed toolbar"),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title,fontSize=17.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Row(Modifier.then(if(incoming)Modifier.testTag("Feed incoming banner")else Modifier),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(incoming){when {staged>0&&returned>0->"$staged new · $returned available";staged>0->"$staged new · $unread unread";returned>0->"$returned available · $unread unread";else->"Post updates ready"}}else "$fresh new · ${unread-fresh} unread",fontSize=10.sp,color=if(incoming)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false).testTag("Feed unread summary"))
                        if(updating||pending>0||state.loading||nearbyNotice!=null||state.error!=null)Text(if(updating)" · updating"else if(pending>0)" · $pending pending"else " · status",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,modifier=Modifier.testTag("Feed check summary").clickable {showSources=true})
                    }
                }
                if(canContinue)IconButton(onClick={continueReading()},enabled=!navigating&&!preparing,modifier=Modifier.testTag("Continue reading")){Icon(Icons.Rounded.History,"Continue reading",Modifier.size(20.dp))}
                TextButton(onClick={accept(true)},enabled=restored&&(frozen.isNotEmpty()||availableKeys.isNotEmpty())&&!navigating&&!preparing&&counterpartsReady,contentPadding=PaddingValues(horizontal=6.dp),modifier=Modifier.testTag("Jump to newest").semantics {contentDescription="Jump to newest"}){Text("Newest",fontSize=12.sp,maxLines=1)}
                FilterControls(repo,filtering,filterGroups,assessments,compact=true)
                Box {
                    IconButton(onClick={showOptions=true},modifier=Modifier.testTag("Feed options")){Icon(Icons.Rounded.MoreVert,"Feed options",Modifier.size(20.dp))}
                    DropdownMenu(expanded=showOptions,onDismissRequest={showOptions=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                        Text(subtitle,Modifier.padding(horizontal=16.dp,vertical=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        nearbyNotice?.let {Text(it,Modifier.widthIn(max=260.dp).padding(horizontal=16.dp,vertical=8.dp),fontSize=12.sp)}
                        state.error?.let {Text(repo.publicText(it),Modifier.widthIn(max=260.dp).padding(horizontal=16.dp,vertical=8.dp),fontSize=12.sp)}
                        if(state.loading)DropdownMenuItem(text={Text("Stop refresh")},leadingIcon={Icon(Icons.Rounded.Close,null)},onClick={showOptions=false;vm.stopLoading()})
                        else DropdownMenuItem(text={Text("Refresh")},leadingIcon={Icon(Icons.Rounded.Refresh,null)},enabled=!refresh.running,onClick={showOptions=false;requestRefresh()})
                        DropdownMenuItem(text={Text("Subscription checks")},leadingIcon={Icon(Icons.Rounded.Info,null)},onClick={showOptions=false;showSources=true})
                        DropdownMenuItem(text={Text("Add a subscription")},leadingIcon={Icon(Icons.Rounded.Add,null)},onClick={showOptions=false;onAdd()})
                        onEditRules?.let {edit->DropdownMenuItem(text={Text("Edit channel rules")},leadingIcon={Icon(Icons.Rounded.Edit,null)},onClick={showOptions=false;edit()})}
                        selector?.let {folders->HorizontalDivider();Box(Modifier.widthIn(max=320.dp)){folders()}}
                    }
                }
            }
        if(!ready||rows==null&&!counterpartsReady)LinearProgressIndicator(Modifier.fillMaxWidth())
        CompositionLocalProvider(LocalArchiveChecksPaused provides (LocalArchiveChecksPaused.current||why!=null)) {
        FeedVideoPreparation(list,ordered,store.get(feedId),ready,!restored||filtersPending,frozen.filter(::readable),filtering,
            showSources||showOptions||filtering.managing||filtering.reviewing||filtering.candidate!=null||preparing||restored&&navigating,repo) {preparingVideoKeys=it}
        // Restore the full native pull arrow/spinner. Matching and pixel
        // checkpoint work are independent of this visible request feedback.
        PullToRefreshBox(isRefreshing=updating||manualRefresh!=null,state=pullState,onRefresh={requestRefresh()},
            indicator={PullToRefreshDefaults.Indicator(state=pullState,isRefreshing=updating||manualRefresh!=null,
                modifier=Modifier.align(Alignment.TopCenter).testTag("Feed refresh indicator"))},
            modifier=Modifier.weight(1f).fillMaxWidth().testTag("Feed pull to refresh")) {
        ArchiveListViewport(list) {
            LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag(if(grouped)"Combined feed"else "Following feed"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=24.dp+with(density){endFillPx.toDp()}),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(frozen.isEmpty()&&availableKeys.isEmpty())item(key="empty:$feedId") {Column(Modifier.padding(vertical=20.dp)) {
                    Text(emptyTitle ?: "Your public posts will appear here",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    Text(emptyMessage ?: "Fetch public posts from your subscriptions. Your reading position stays on this device.",Modifier.padding(vertical=12.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick=onEditRules ?: if(accounts.isEmpty())onAdd else ({requestRefresh()})){Text(if(onEditRules!=null)"Edit channel rules"else if(accounts.isEmpty())"Add a subscription"else "Refresh subscriptions")}
                }}
                fun LazyListScope.feedRows(values: List<FrozenFeedRow>) {items(rendered(values),key={it.key}) {row->
                    val unreadRow=row.aliases.any {it !in readKeys}
                    val newRow=unreadRow&&row.aliases.any {it in freshKeys}
                    val combined=remember(row.bodyPosts){collectedPostFusion(row.bodyPosts)?.segments?.any {!it.shared}==true}
                    FeedReadDwell(list,row.key,restored&&!navigating&&unreadRow&&readable(row),row.bodyPosts) {store.markRead(feedId,row.bodyPosts.map(repo::key))}
                    Column(Modifier.testTag("Feed row ${row.key}"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth().height(18.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(row.posts.size>1)Text(if(hasRepeatedOriginals(row.posts))"${row.posts.size} originals · "+row.posts.map {it.platform.label}.distinct().joinToString(" · ")else "${row.posts.size} platforms · "+if(combined)"combined post"else "matching post",Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(unreadRow)Text(if(newRow)"New"else "Unread",fontSize=11.sp,color=if(newRow)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Feed row status ${row.key}"))
                        }
                        if(decision(row)==null)PendingFilterCard()else FilteredPost(decision(row)!!,repo.key(row.bodyPosts.first()),filtering,onEdit={filtering.managing=true}) {
                            CompositionLocalProvider(LocalVideoPreparationPending provides row.aliases.any {it in preparingVideoKeys}) {
                                FeedVideoDwell(list,row.key,row.primary,row.posts.any {p->p.media.any {it.type=="video"}},row.aliases.firstNotNullOfOrNull {LocalVideoRequests.current[it]},repo)
                            }
                            FeedGalleryDwell(list,row.key,row.aliases.firstNotNullOfOrNull {LocalGalleryRequests.current[it]},repo)
                            PostCard(row.primary,vm,row.aliases.any {it in savedKeys},row.posts,bodyVariants=row.bodyPosts,onExplain=row.explanation?.let {explanation->{why=explanation.copy(channel=title)}},onFilter={filtering.candidate=row.posts},
                                onShowUpdate=if(changes?.details?.contains(row.key)==true)({showCapturedDetails(row.key)})else null){repo.postTarget(row.primary)?.let {vm.open(it)}}
                        }
                    }
                }}
                feedRows(frozen.take(boundary))
                item(key="boundary:$feedId") {
                    FeedBoundary(caught,pending,unreadBefore,incoming,checks,accounts,tail=frozen.size-boundary,onSources={showSources=true},onEarlier={earlier=true},earlier=earlier,emptyChannel=explanations!=null&&frozen.isEmpty(),filtered=filteredCount,
                        batchFinished=batchFinished,nudge=if(caught)exitLine else FeedExitCopy.line(exitSeed,false))
                }
                if(earlier)feedRows(frozen.drop(boundary))
            }
        }
        }
        }
    }
    SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag("Feed refresh feedback"))
    }
    LaunchedEffect(filtering.revealTarget) {
        val target=filtering.revealTarget ?: return@LaunchedEffect
        val displayed=rendered(frozen)
        val index=displayed.indexOfFirst {target in it.aliases}
        if(index>=0){checkpoint();store.keepReturn(feedId);scroll(FeedRestore(index,0,true))}
        filtering.revealTarget=null
    }
    if(showSources)FeedSourceDetails(accounts,checks,onDismiss={showSources=false})
    ReaderFilterDialogs(repo,filtering,filterGroups,assessments)
    why?.let {explanation->AlertDialog(onDismissRequest={why=null},modifier=Modifier.testTag("Channel match explanation"),title={Text("Why this appeared")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("In ${explanation.channel}",fontWeight=FontWeight.SemiBold)
        explanation.reasons.forEach {Text(it,fontSize=14.sp)}
        Text("Matched locally when this batch was added. None of your exclusions matched the known versions of this post.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={TextButton(onClick={why=null}){Text("Done")}})}
}

@Composable private fun FeedBoundary(caught: Boolean,pending: Int,unread: Int,incoming: Boolean,checks: Map<String,FollowRefresh>,follows: List<Follow>,tail: Int,onSources: ()->Unit,onEarlier: ()->Unit,earlier: Boolean,emptyChannel: Boolean=false,filtered: Int=0,batchFinished: Boolean=false,nudge: String="") {
    val checked=follows.mapNotNull {checks[it.id]?.lastSuccessAt}.minOrNull()
    val stamp=checked?.let {DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))}
    Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f),modifier=Modifier.fillMaxWidth().height(160.dp).testTag(if(pending>0)"Feed pending subscriptions"else if(caught)"Feed caught up"else "Feed unread boundary")) {
        Column(Modifier.padding(16.dp)) {
            Text(when {batchFinished&&pending>0->"Fetched batch finished";pending>0->"Updates pending";incoming->"Updates are waiting";unread>0->"$unread still unread";caught&&filtered>0->"Caught up · $filtered filtered";caught->"Caught up";follows.isNotEmpty()->"Public posts not loaded";else->"End of fetched posts"},fontSize=16.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
            if(batchFinished&&filtered==0) {
                Text(nudge,Modifier.weight(1f).padding(top=7.dp).testTag("Feed batch nudge"),fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
            }else {
                Text(when {pending>0->"$pending subscribed ${if(pending==1)"account still needs"else "accounts still need"} a successful check.";incoming->"Newer posts are being collected above your reading position.";unread>0->"Posts skipped above stay unread.";caught&&emptyChannel->"The selected subscriptions were checked; no fetched posts matched this channel.";caught&&filtered>0->"Visible posts are read; $filtered filtered cards remain available in Review without being marked read.";caught->"You've read this batch of fetched public posts.";follows.isNotEmpty()->"Refresh subscriptions to fetch readable public posts.";else->"Add a subscription to build your reading feed."}+(if(pending==0&&stamp!=null)" Last checked $stamp."else ""),Modifier.weight(1f).padding(top=7.dp),fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onSources){Text(if(batchFinished&&pending>0)"Source status · $pending pending"else "Source status",fontSize=12.sp)}
                if(tail>0&&!earlier)TextButton(onClick=onEarlier,modifier=Modifier.testTag("Earlier posts")){Text("Earlier posts",fontSize=12.sp)}
                else if(tail>0)Text("Earlier posts below",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FeedSourceDetails(follows: List<Follow>,checks: Map<String,FollowRefresh>,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Feed source status")) {
        LazyColumn(contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {Row(verticalAlignment=Alignment.CenterVertically){Text("Subscription checks",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close subscription checks")}}}
            item {Text("These checks cover fetched public posts. Sources may limit or omit older posts.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(follows,key={it.id}) {follow->val record=checks[follow.id]
                Column(Modifier.fillMaxWidth().testTag("Feed source ${follow.id}")) {
                    Text(follow.name.ifBlank {follow.target.identity},fontWeight=FontWeight.SemiBold)
                    Text("${follow.target.platform.label} · "+when(record?.status){FollowRefreshStatus.SUCCESS->"Checked";FollowRefreshStatus.CHECKING->"Checking now";else->"Pending"},fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(record?.status!=FollowRefreshStatus.SUCCESS)Text(record?.reason ?: "This account has not completed its latest check.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    record?.lastSuccessAt?.let {at->Text("Last success "+DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at)),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
            }
        }
    }
}

internal data class FeedDwellGeometry(val offset: Int,val size: Int,val start: Int,val end: Int,val moving: Boolean) {
    val readable: Boolean get() {
        val viewport=end-start;if(size<=0||viewport<=0||moving)return false
        val visible=(min(offset+size,end)-max(offset,start)).coerceAtLeast(0)
        return if(size<=viewport)visible>=size*.6f else visible>=viewport*.6f&&offset<=start+viewport/2&&offset+size>=start+viewport/2
    }
}

/** Visible, settled cards only. Scrolling, overlays, lost focus and backgrounding
 * cancel the coroutine and its Gateway reads rather than queuing a global scan. */
@Composable internal fun FeedVideoDwell(list:LazyListState,key:String,post:Post,hasVideo:Boolean,request:VideoFootageRequest?,repo:Repository) {
    val running=remember {java.util.concurrent.atomic.AtomicReference<Job?>()}
    val attention=rememberReaderWorkAttention {running.get()?.cancel(CancellationException("Reader attention ended"))}
    val paused=LocalArchiveChecksPaused.current
    val retries by repo.videos.retries.collectAsStateWithLifecycle()
    val playing by repo.videos.playbackActive.collectAsStateWithLifecycle()
    val latestRequest by rememberUpdatedState(request)
    val latestOfferRevision by rememberUpdatedState(LocalVideoOfferRevision.current)
    val latestLookup by rememberUpdatedState(LocalVideoRequestLookup.current)
    val latestCounterparts by rememberUpdatedState(LocalPostCounterparts.current)
    val preparationPending by rememberUpdatedState(LocalVideoPreparationPending.current)
    val settled by remember(list,key){derivedStateOf {list.layoutInfo.let {info->
        info.visibleItemsInfo.firstOrNull {it.key.toString()==key}?.let {FeedDwellGeometry(it.offset,it.size,info.viewportStartOffset,info.viewportEndOffset,list.isScrollInProgress).readable}==true
    }}}
    val acquisition=remember(post){post.media.filter {it.type=="video"}.map(VideoAssetIdentity::acquisitionKey)}
    val potential=hasVideo||request!=null
    LaunchedEffect(potential,acquisition,settled,attention,paused,playing,retries) {
        if(!potential||!settled||!attention.active||paused||playing)return@LaunchedEffect
        val job=currentCoroutineContext()[Job]!!;running.set(job)
        try {
        delay(1200)
        // Count real viewport dwell while the initial pair owns the lane.
        // Handoff uses fresh evidence without resetting another 1.2s delay.
        snapshotFlow {preparationPending}.first {!it}
        val attempted=linkedSetOf<VideoComparisonInput>()
        // Keep the same finite comparison allowance, but spend it on the
        // settled card instead of letting an unrelated global prefix own it.
        // Evidence updates wake this loop without cancelling a pair halfway
        // through its second recording. Nothing polls while the catalog is idle.
        while(attempted.size<12) {
            if(!repo.videos.automaticAllowed())return@LaunchedEffect
            val lookup=latestLookup;val rawOffer=latestRequest;val offerRevision=latestOfferRevision
            // A precomputed offer can outlive the scene evidence which made
            // it useful. The focused planner already joins current footage;
            // use that path without waiting for the whole feed to regroup.
            val offered=rawOffer.takeIf {offerRevision?.let {it==repo.videos.revision.value}!=false}
            val availableOffer=offered?.takeIf {it.acquisitionKey !in attempted&&repo.videos.available(it)}
            val covered=latestCounterparts[key].orEmpty().mapTo(mutableSetOf(post.platform)){it.platform}
            // Prefer missing-platform coverage before another edition on a
            // represented platform. Keep that edition as the fallback: it can
            // still supply distinct content and must never be pruned.
            val focusFirst=availableOffer?.let {it.first.platform in covered&&it.second.platform in covered}==true
            val next=if(focusFirst)lookup(key,attempted) ?: availableOffer else availableOffer ?: lookup(key,attempted)
            if(next==null) {
                snapshotFlow {Triple(latestLookup,latestRequest,latestOfferRevision)}
                    .first {it.first!==lookup||it.second!=rawOffer||it.third!=offerRevision}
                continue
            }
            attempted+=next.acquisitionKey
            coroutineScope {
                val work=async {repo.videos.enrich(next)}
                // Catalog/evidence changes are harmless, but the same original
                // acquiring a new URL, duration or rendition must retire stale
                // work. Keep this child cancellation distinct from leaving the
                // viewport, changing privacy mode or purging the evidence bank.
                var obsolete=false
                val monitor=launch {
                    snapshotFlow {
                        fun current(original:Post)=latestCounterparts[CrossPosts.originalKey(original)].orEmpty()
                            .firstOrNull {CrossPosts.originalKey(it)==CrossPosts.originalKey(original)} ?: original
                        next.copy(first=current(next.first),second=current(next.second)).acquisitionKey
                    }.first {it!=next.acquisitionKey}
                    obsolete=true
                    work.cancel(CancellationException("Video source input changed"))
                }
                try {work.await()}catch(e:CancellationException){currentCoroutineContext().ensureActive();if(!obsolete)throw e}
                finally {monitor.cancel()}
            }
        }
        }finally {running.compareAndSet(job,null)}
    }
}

/** One proven gallery's continuation, only while its card is settled. */
@Composable internal fun FeedGalleryDwell(list:LazyListState,key:String,request:GalleryContinuationRequest?,repo:Repository) {
    if(request==null)return
    val running=remember {java.util.concurrent.atomic.AtomicReference<Job?>()}
    val attention=rememberReaderWorkAttention {running.get()?.cancel(CancellationException("Reader attention ended"))}
    val paused=LocalArchiveChecksPaused.current
    val settled by remember(list,key){derivedStateOf {list.layoutInfo.let {info->
        info.visibleItemsInfo.firstOrNull {it.key.toString()==key}?.let {FeedDwellGeometry(it.offset,it.size,info.viewportStartOffset,info.viewportEndOffset,list.isScrollInProgress).readable}==true
    }}}
    LaunchedEffect(request,settled,attention,paused) {
        if(!settled||!attention.active||paused)return@LaunchedEffect
        val job=currentCoroutineContext()[Job]!!;running.set(job)
        try {delay(1200);if(repo.galleries.allowed())repo.galleries.enrich(request)}
        finally {running.compareAndSet(job,null)}
    }
}

/** Placement, clipping, scrolling, focus and lifecycle all participate in a continuous read dwell. */
@Composable private fun FeedReadDwell(list: LazyListState,key: String,enabled: Boolean,body:List<Post>,onRead: ()->Unit) {
    val view=LocalView.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle){mutableStateOf(lifecycle.currentState==Lifecycle.State.RESUMED)}
    var focused by remember(view){mutableStateOf(view.hasWindowFocus())}
    val geometry by remember(list,key){derivedStateOf {list.layoutInfo.let {info->info.visibleItemsInfo.firstOrNull {it.key.toString()==key}?.let {FeedDwellGeometry(it.offset,it.size,info.viewportStartOffset,info.viewportEndOffset,list.isScrollInProgress)}}}}
    val read by rememberUpdatedState(onRead)
    val paused=LocalArchiveChecksPaused.current
    DisposableEffect(lifecycle) {val observer=LifecycleEventObserver {_,_->resumed=lifecycle.currentState==Lifecycle.State.RESUMED};lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}}
    DisposableEffect(view) {val observer=android.view.ViewTreeObserver.OnWindowFocusChangeListener {focused=it};view.viewTreeObserver.addOnWindowFocusChangeListener(observer);onDispose {if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnWindowFocusChangeListener(observer)}}
    LaunchedEffect(enabled,resumed,focused,paused,geometry,body) {
        if(!enabled||!resumed||!focused||paused||geometry?.readable!=true)return@LaunchedEffect
        delay(600);read()
    }
}
