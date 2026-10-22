package app.antisocial.android

import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.antisocial.core.ArchivedVersion
import app.antisocial.core.Post
import app.antisocial.core.SourceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.min

/** Presentation is separate from the current record used by save, comments and downloads. */
internal data class ArchiveCardPresentation(
    val post: Post,
    val version: ArchivedVersion?,
    val showingArchive: Boolean,
    val dwell: Modifier,
    val toggle: () -> Unit
)

internal val LocalArchiveChecksPaused=compositionLocalOf {false}
private data class ArchiveViewport(val keys: Set<String>,val moving: Boolean) {
    fun allows(postKey: String)=!moving&&(postKey in keys||listOf("root","dated","undated","saved","post").any {"$it:$postKey" in keys})
}
private val LocalArchiveViewport=compositionLocalOf<ArchiveViewport?> {null}

/** Only placed, visible lazy items can start a check, even after an abrupt list jump. */
@Composable internal fun ArchiveListViewport(list: LazyListState,content: @Composable ()->Unit) {
    val viewport by remember(list) {derivedStateOf {ArchiveViewport(list.layoutInfo.visibleItemsInfo.map {it.key.toString()}.toSet(),list.isScrollInProgress)}}
    CompositionLocalProvider(LocalArchiveViewport provides viewport,LocalMentionUpdatesAllowed provides (LocalMentionUpdatesAllowed.current&&!viewport.moving),content=content)
}
@Composable internal fun ArchiveGridViewport(grid: LazyStaggeredGridState,content: @Composable ()->Unit) {
    val viewport by remember(grid) {derivedStateOf {ArchiveViewport(grid.layoutInfo.visibleItemsInfo.map {it.key.toString()}.toSet(),grid.isScrollInProgress)}}
    CompositionLocalProvider(LocalArchiveViewport provides viewport,LocalMentionUpdatesAllowed provides (LocalMentionUpdatesAllowed.current&&!viewport.moving),content=content)
}
@Composable internal fun archiveViewportAllows(postKey: String)=LocalArchiveViewport.current?.allows(postKey) ?: true

@Composable internal fun rememberArchivePresentation(post: Post, repo: Repository, active: Boolean=true): ArchiveCardPresentation {
    val key=repo.key(post)
    val history by repo.archiveHistory.collectAsStateWithLifecycle()
    val version=remember(post,history[key]) {if(post.provenance?.source?.role==SourceRole.CACHE)null else repo.archiveVersion(post)}
    var showingArchive by rememberSaveable(key,version?.currentSignature) {mutableStateOf(false)}
    val dwell=rememberArchiveDwellModifier(key,active&&archiveViewportAllows(key)&&!LocalArchiveChecksPaused.current&&version==null&&repo.prefs.archiveChecks&&post.recoveredArchive==null&&post.provenance?.source?.role!=SourceRole.CACHE&&!post.preview&&post.parentId==null) {repo.checkArchive(post)}
    val showing=showingArchive&&version!=null
    return ArchiveCardPresentation(if(showing)version!!.displayPost(post)else post,version,showing,dwell) {showingArchive=!showing}
}

@Composable internal fun ArchiveVersionButton(version: ArchivedVersion?, showingArchive: Boolean, onClick: () -> Unit) {
    if(version==null)return
    IconButton(onClick=onClick,modifier=Modifier.size(40.dp).testTag("Archive version toggle")) {
        Icon(if(showingArchive)Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.Schedule,
            if(showingArchive)"Show current post" else "Show oldest available archived version",
            modifier=Modifier.size(19.dp),tint=MaterialTheme.colorScheme.primary)
    }
}

@Composable internal fun ArchiveVersionLabel(version: ArchivedVersion?, showingArchive: Boolean, modifier: Modifier=Modifier) {
    if(version==null||!showingArchive)return
    val date=remember(version.capturedAt) {DateTimeFormatter.ofPattern("MMM d, uuuu").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(version.capturedAt))}
    Column(modifier.testTag("Archived version label").semantics {contentDescription="Archived version from ${version.provider}, captured $date"}) {
        Text("Archived · $date",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
        Text("Oldest available snapshot",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A recovered snapshot has no known live edition to swap back to. */
@Composable internal fun RecoveredArchiveLabel(post: Post,modifier: Modifier=Modifier) {
    val recovery=post.recoveredArchive
    if(recovery==null) {
        if(post.provenance?.source?.role==SourceRole.CACHE)Text("Archive copy",modifier.testTag("Recovered archive label").semantics {contentDescription="Independent archive copy; capture date not supplied"},fontSize=12.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.primary)
        return
    }
    val label=remember(recovery.capturedAt) {runCatching {
        "Archived "+DateTimeFormatter.ofPattern("MM/yy").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(recovery.capturedAt))
    }.getOrDefault("Archived")}
    Text(label,modifier.testTag("Recovered archive label").semantics {contentDescription="$label from ${recovery.provider}"},fontSize=12.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
}

private data class ArchiveDwellGeometry(val full: Rect,val visible: Rect,val viewport: Rect) {
    val readable: Boolean get() {
        if(full.width<=0f||full.height<=0f||visible.width<full.width*.6f)return false
        if(full.height<=viewport.height)return visible.height>=full.height*.6f
        // A long text/media post cannot fit on screen. Require substantial space around
        // the viewport's centre rather than counting a thin, clipped edge as reading.
        return visible.height>=min(full.height*.6f,viewport.height*.6f)&&visible.top<=viewport.center.y&&visible.bottom>=viewport.center.y
    }
}

/** Lazy composition/prefetch is not visibility. Any movement restarts the continuous dwell. */
@Composable internal fun rememberArchiveDwellModifier(
    key: String,
    enabled: Boolean,
    dwellMs: Long=8_000,
    onDwell: suspend () -> Unit
): Modifier {
    val view=LocalView.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) {mutableStateOf(lifecycle.currentState==Lifecycle.State.RESUMED)}
    var focused by remember(view) {mutableStateOf(view.hasWindowFocus())}
    var geometry by remember(key) {mutableStateOf<ArchiveDwellGeometry?>(null)}
    var checked by remember(key) {mutableStateOf(false)}
    val check by rememberUpdatedState(onDwell)
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,_->resumed=lifecycle.currentState==Lifecycle.State.RESUMED}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    DisposableEffect(view) {
        val observer=android.view.ViewTreeObserver.OnWindowFocusChangeListener {focused=it}
        view.viewTreeObserver.addOnWindowFocusChangeListener(observer)
        onDispose {if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnWindowFocusChangeListener(observer)}
    }
    LaunchedEffect(key,enabled,resumed,focused,geometry) {
        if(!enabled||!resumed||!focused||checked||geometry?.readable!=true)return@LaunchedEffect
        delay(dwellMs)
        checked=true
        try {check()}catch(e: CancellationException){checked=false;throw e}catch(_: Exception) {
            // History is an optional enhancement; unavailable archives stay silent.
        }
    }
    return Modifier.onGloballyPositioned {coordinates->
        if(!coordinates.isAttached){geometry=null;return@onGloballyPositioned}
        val local=AndroidRect()
        val location=IntArray(2)
        val visible=view.getLocalVisibleRect(local)
        view.getLocationInWindow(location)
        val viewport=if(visible)Rect((local.left+location[0]).toFloat(),(local.top+location[1]).toFloat(),(local.right+location[0]).toFloat(),(local.bottom+location[1]).toFloat())else Rect.Zero
        val full=coordinates.boundsInWindow(clipBounds=false)
        val clipped=coordinates.boundsInWindow().intersect(viewport)
        geometry=ArchiveDwellGeometry(full,clipped,viewport)
    }
}
