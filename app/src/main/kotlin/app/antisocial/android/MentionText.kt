/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.graphics.Rect as AndroidRect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.antisocial.core.MentionAccount
import app.antisocial.core.MentionMatch
import app.antisocial.core.MentionNames
import app.antisocial.core.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** The local name bank owns scheduling; presentation only leases the accounts it shows. */
interface MentionNameSource {
    val names: StateFlow<Map<String,String>>
    val enabled: StateFlow<Boolean>
    fun observe(accounts: List<MentionAccount>,networkAllowed: Boolean=true): AutoCloseable
}

/** Feed/readers pause replacement while scrolling, so a resolved name cannot move a pressed card. */
internal val LocalMentionUpdatesAllowed=staticCompositionLocalOf {true}
internal val LocalMentionOpenAccount=staticCompositionLocalOf<((String)->Unit)?> {null}
internal val LocalMentionNameSource=staticCompositionLocalOf<MentionNameSource?> {null}

/** Names are a reversible display treatment. The Post, selected source and stored text are untouched. */
@Composable internal fun MentionText(
    value: String,
    repo: Repository?=null,
    platform: Platform?=null,
    sourceUrl: String?=null,
    modifier: Modifier=Modifier,
    networkAllowed: Boolean=true,
    onOpen: (()->Unit)?=null,
    onOpenAccount: ((String)->Unit)?=null,
    fontSize: TextUnit=TextUnit.Unspecified,
    lineHeight: TextUnit=TextUnit.Unspecified,
    color: Color=Color.Unspecified,
    fontWeight: FontWeight?=null,
    maxLines: Int=Int.MAX_VALUE,
    overflow: TextOverflow=TextOverflow.Clip,
    style: TextStyle=LocalTextStyle.current,
    source: MentionNameSource?=null,
    explicitProfileLinks: List<String> = emptyList(),
    accountLabel: Boolean=false,
    onTextLayout: ((TextLayoutResult)->Unit)?=null
) {
    val bank=source ?: LocalMentionNameSource.current ?: repo?.mentionNames
    val raw=remember(value,repo,platform){repo?.publicText(value,platform) ?: value}
    val available by bank?.names?.collectAsState() ?: remember {mutableStateOf(emptyMap())}
    val enabled by bank?.enabled?.collectAsState() ?: remember {mutableStateOf(false)}
    val matches=remember(raw,platform,sourceUrl,repo,enabled,explicitProfileLinks,accountLabel) {
        if(!enabled||platform==null||repo==null||(!accountLabel&&'@' !in raw&&"u/" !in raw))emptyList()
        else if(accountLabel)MentionNames.label(raw,platform,sourceUrl.orEmpty(),repo.routes,explicitProfileLinks)
        else MentionNames.find(raw,platform,sourceUrl.orEmpty(),repo.routes,explicitProfileLinks)
    }
    val relevant=remember(matches,available){matches.mapNotNull {match->available[match.key.id]?.let {match.key.id to it}}.toMap()}
    var displayed by remember(raw,matches){mutableStateOf(relevant)}
    val updatesAllowed=LocalMentionUpdatesAllowed.current
    val view=LocalView.current
    var focused by remember(view){mutableStateOf(view.hasWindowFocus())}
    DisposableEffect(view) {
        val observer=android.view.ViewTreeObserver.OnWindowFocusChangeListener {focused=it}
        view.viewTreeObserver.addOnWindowFocusChangeListener(observer)
        // A new composition can attach after the window already gained focus;
        // that transition need not be replayed to a freshly registered listener.
        focused=view.hasWindowFocus()
        onDispose {if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnWindowFocusChangeListener(observer)}
    }
    var pointerDown by remember(raw){mutableStateOf(false)}
    // Cached names are immediate on entry. Later responses wait until this text and its scroll
    // container have both been quiet; no animation expands a paragraph beneath a finger.
    LaunchedEffect(relevant,updatesAllowed,pointerDown,focused) {
        if(updatesAllowed&&!pointerDown&&focused&&displayed!=relevant){delay(400);displayed=relevant}
    }
    var geometry by remember(raw){mutableStateOf<MentionGeometry?>(null)}
    val placement=remember(raw){MentionPlacement()}
    fun resampleGeometry() {
        val coordinates=placement.coordinates
        if(coordinates==null||!coordinates.isAttached){geometry=null;return}
        focused=view.hasWindowFocus()
        val local=AndroidRect();val location=IntArray(2)
        val visible=view.getLocalVisibleRect(local);view.getLocationInWindow(location)
        val viewport=if(visible)Rect((local.left+location[0]).toFloat(),(local.top+location[1]).toFloat(),(local.right+location[0]).toFloat(),(local.bottom+location[1]).toFloat())else Rect.Zero
        geometry=MentionGeometry(coordinates.boundsInWindow(clipBounds=false),coordinates.boundsInWindow().intersect(viewport))
    }
    DisposableEffect(view,placement) {
        // Compose can place a new full-size reader before its Android host has resized.
        // Re-sample after View.layout commits that frame, rather than retaining the
        // previous wrap-content host's clipped viewport until the next scroll.
        val observer=android.view.View.OnLayoutChangeListener {_,_,_,_,_,_,_,_,_->resampleGeometry()}
        view.addOnLayoutChangeListener(observer)
        onDispose {view.removeOnLayoutChangeListener(observer);placement.coordinates=null}
    }
    var layout by remember(raw){mutableStateOf<TextLayoutResult?>(null)}
    var selected by remember(raw){mutableStateOf<MentionMatch?>(null)}
    val presentation=remember(raw,matches,displayed){MentionNames.present(raw,matches,displayed)}
    val accounts=remember(presentation,geometry,layout,updatesAllowed,pointerDown,focused) {
        if(!updatesAllowed||pointerDown||!focused)emptyList()
        else presentation.mentions.filter {mention->mentionIsVisible(mention.start,mention.endExclusive,layout,geometry)}
            .distinctBy {it.original.key.id}.map {MentionAccount(it.original.key,it.original.target)}
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    // An already supplied author/profile name is sufficient for this label.
    // Touch its local cache entry without querying a profile just to re-fetch it.
    val lookupsAllowed=networkAllowed&&(!accountLabel||matches.none {it.fallbackLabel!=null})
    DisposableEffect(bank,accounts,lookupsAllowed,lifecycle) {
        var lease: AutoCloseable?=null
        fun sync() {
            val visible=accounts.isNotEmpty()&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(visible&&lease==null)lease=bank?.observe(accounts,lookupsAllowed)
            else if(!visible){lease?.close();lease=null}
        }
        val observer=LifecycleEventObserver {_,_->sync()}
        lifecycle.addObserver(observer);sync()
        onDispose {lifecycle.removeObserver(observer);lease?.close()}
    }
    val linkColor=MaterialTheme.colorScheme.primary
    val text=remember(presentation,linkColor) {
        buildAnnotatedString {
            var at=0
            presentation.mentions.forEach {mention->
                append(presentation.text.substring(at,mention.start))
                withLink(LinkAnnotation.Clickable(mention.original.key.id,TextLinkStyles(style=SpanStyle(color=linkColor,textDecoration=TextDecoration.Underline)),linkInteractionListener={selected=mention.original})) {
                    append(presentation.text.substring(mention.start,mention.endExclusive))
                }
                at=mention.endExclusive
            }
            append(presentation.text.substring(at))
        }
    }
    Text(text,fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,maxLines=maxLines,overflow=overflow,style=style,
        onTextLayout={layout=it;onTextLayout?.invoke(it)},
        modifier=modifier.onGloballyPositioned {coordinates->
            placement.coordinates=coordinates
            resampleGeometry()
        }.pointerInput(raw) {
            awaitPointerEventScope {
                while(true) {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    pointerDown=event.changes.any {it.pressed}
                }
            }
        }.then(if(onOpen==null)Modifier else Modifier.clickable(onClick=onOpen)))
    val openAccount=onOpenAccount ?: LocalMentionOpenAccount.current
    selected?.let {mention->
        MentionAccountSheet(mention,displayed[mention.key.id]?.let {MentionNames.sanitizeDisplayName(it,mention.key)},onOpen=openAccount,onDismiss={selected=null})
    }
}

private class MentionPlacement {var coordinates: LayoutCoordinates?=null}
private data class MentionGeometry(val full: Rect,val visible: Rect)

/** Layout coordinates are clipped by all scroll parents. Prefetched cards and paragraphs below
 * the fold therefore create no demand, even when their Text has already been composed. */
private fun mentionIsVisible(start: Int,end: Int,layout: TextLayoutResult?,geometry: MentionGeometry?): Boolean {
    if(layout==null||geometry==null||geometry.visible.isEmpty||geometry.full.isEmpty)return false
    if(start<0||end>layout.layoutInput.text.length||start>=end)return false
    var glyphs=0;var visible=0
    for(offset in start until end) {
        val line=layout.getLineForOffset(offset)
        if(line>=layout.lineCount||offset>=layout.getLineEnd(line,visibleEnd=true))continue
        val box=layout.getBoundingBox(offset)
        if(box.width<=0f||box.height<=0f)continue
        glyphs++
        val placed=Rect(box.left+geometry.full.left,box.top+geometry.full.top,box.right+geometry.full.left,box.bottom+geometry.full.top)
        val clipped=placed.intersect(geometry.visible)
        if(clipped.width>=box.width*.6f&&clipped.height>=box.height*.6f)visible++
    }
    return glyphs>0&&visible>=minOf(3,glyphs)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MentionAccountSheet(mention: MentionMatch,name: String?,onOpen: ((String)->Unit)?,onDismiss: ()->Unit) {
    val clipboard=LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Mention account sheet")) {
        Column(Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name ?: mention.fallbackLabel ?: MentionNames.identifierLabel(mention),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("Mention public name"))
                    val handle=if(mention.token.startsWith('@')||mention.token.startsWith("u/")||mention.token.startsWith("/u/"))mention.token
                        else if(mention.key.platform==Platform.REDDIT)"u/${mention.key.username}"else "@${mention.key.username.substringAfterLast('/')}"+(if(mention.key.platform in setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY))"@${mention.key.authority}"else "")
                    Text(handle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Mention original handle"))
                }
                IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close account details")}
            }
            if(name==null&&mention.fallbackLabel==null)Text("Public name unavailable. Showing the account identifier.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PlatformMark(mention.key.platform,21.dp)
                Text("${mention.key.platform.label} account",style=MaterialTheme.typography.labelLarge)
            }
            SelectionContainer {Text(mention.target.canonicalUrl,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Mention authentic account link"))}
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(onOpen!=null)FilledTonalButton(onClick={onDismiss();onOpen(mention.target.canonicalUrl)},modifier=Modifier.testTag("Open mentioned account")) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text("Open account")
                }
                TextButton(onClick={clipboard.setText(AnnotatedString(mention.target.canonicalUrl))},modifier=Modifier.testTag("Copy mentioned account link")) {
                    Icon(Icons.Rounded.ContentCopy,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text("Copy link")
                }
            }
        }
    }
}
