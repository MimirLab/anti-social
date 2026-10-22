/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Presentation-only copy. The metadata reader decides what was declared, never this view. */
internal enum class AiInspectionStatus { NO_DECLARATION, INCOMPLETE }
internal data class AiDisclosureEntry(
    val source: String,
    val asset: String,
    val paragraphs: List<String>,
    val evidence: String,
    val verification: String? = null,
    val tools: List<String> = emptyList(),
    val aiDeclared: Boolean = true,
    val metadataRemoved: Boolean = false,
    val metadataGap: Boolean = false,
    val inspectionStatus: AiInspectionStatus? = null,
)

/** A compact disclosure for places where the button and readable manifest can share a column. */
@Composable internal fun AiProvenanceDisclosure(entries: List<AiDisclosureEntry>,modifier: Modifier=Modifier) {
    val readable=remember(entries){entries.readable()}
    if(readable.isEmpty())return
    var expanded by remember(readable.map {it.source to it.asset}){mutableStateOf(false)}
    Column(modifier.animateContentSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        AiProvenanceButton(readable,expanded,{expanded=it})
        AnimatedVisibility(expanded) {AiProvenanceDetails(readable)}
    }
}

/** Can live in an existing, fixed-height action row so late metadata never shifts a feed. */
@Composable internal fun AiProvenanceButton(entries: List<AiDisclosureEntry>,expanded: Boolean,onExpandedChange: (Boolean)->Unit,modifier: Modifier=Modifier) {
    val readable=remember(entries){entries.readable()}
    if(readable.isEmpty())return
    val ai=readable.any {it.aiDeclared}
    val removed=readable.any {it.metadataRemoved};val gap=readable.any {it.metadataGap}
    val warning=ai||removed||gap
    val neutral= !warning&&readable.all {it.inspectionStatus!=null}
    if(neutral) {
        IconButton(onClick={onExpandedChange(!expanded)},modifier=modifier.size(34.dp).testTag("AI provenance button")
            .semantics {stateDescription=if(expanded)"Expanded"else "Collapsed"}) {
            Icon(Icons.Rounded.Info,if(readable.any {it.inspectionStatus==AiInspectionStatus.INCOMPLETE})"AI inspection incomplete"else "AI status unknown",
                Modifier.size(19.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.8f))
        }
        return
    }
    val accent=provenanceAccent(warning)
    val angle by animateFloatAsState(if(expanded)180f else 0f,label="Metadata disclosure arrow")
    Surface(onClick={onExpandedChange(!expanded)},shape=RoundedCornerShape(8.dp),
        color=accent.copy(alpha=.085f),contentColor=accent,border=BorderStroke(.75.dp,accent.copy(alpha=.38f)),
        modifier=modifier.testTag("AI provenance button").semantics {stateDescription=if(expanded)"Expanded"else "Collapsed"}) {
        Row(Modifier.heightIn(min=34.dp).padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Icon(if(warning)Icons.Rounded.WarningAmber else Icons.Rounded.History,null,Modifier.size(15.dp))
            Column(verticalArrangement=Arrangement.spacedBy(1.dp)) {
                Text(when {ai&&(removed||gap)->"AI + METADATA";ai->"AI DETECTED";removed->"METADATA REMOVED";gap->"METADATA GAP";else->"CONTENT HISTORY"},fontSize=10.sp,lineHeight=12.sp,fontWeight=FontWeight.Bold,letterSpacing=1.1.sp)
                Text(when {ai&&removed->"AI use & declared removal";ai&&gap->"AI use & metadata difference";ai->"Declared in metadata";removed->"Declared removal";gap->"Evidence of metadata difference";else->"Supplied record"},fontSize=8.sp,lineHeight=10.sp,color=accent.copy(alpha=.85f))
            }
            Icon(Icons.Rounded.ExpandMore,if(expanded)"Hide metadata details"else "Show metadata details",Modifier.size(17.dp).rotate(angle))
        }
    }
}

/** A human-readable manifest: each claim stays attached to its own edition and attachment. */
@Composable internal fun AiProvenanceDetails(entries: List<AiDisclosureEntry>,modifier: Modifier=Modifier) {
    val readable=remember(entries){entries.readable()}
    if(readable.isEmpty())return
    val ai=readable.any {it.aiDeclared}
    val removed=readable.any {it.metadataRemoved};val gap=readable.any {it.metadataGap}
    val neutral= !ai&&!removed&&!gap&&readable.all {it.inspectionStatus!=null}
    val accent=provenanceAccent(ai||removed||gap)
    Surface(shape=RoundedCornerShape(12.dp),color=accent.copy(alpha=.045f),border=BorderStroke(.7.dp,accent.copy(alpha=.22f)),modifier=modifier.fillMaxWidth().testTag("AI provenance manifest")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
            Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(ai||removed||gap)HazardMark(accent)
                    Text(when {ai&&(removed||gap)->"AI & metadata history";ai->"AI use, as declared";removed->"Metadata removed, as declared";gap->"A gap in the record";neutral->"AI status unknown";else->"Behind this media"},fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                }
                Text(if(neutral)"What inspection of the available copy can tell us."else if(removed||gap)"What the available evidence can tell us."else "Read from the information supplied with this content.",fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            readable.forEachIndexed {index,entry->
                if(index>0)HorizontalDivider(color=accent.copy(alpha=.16f))
                Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.testTag("AI provenance entry $index")) {
                    Text(listOf(entry.source,entry.asset).filter {it.isNotBlank()}.joinToString(" · "),fontSize=11.sp,fontWeight=FontWeight.SemiBold,letterSpacing=.25.sp,color=accent)
                    entry.paragraphs.forEach {Text(it,fontSize=14.sp,lineHeight=21.sp)}
                    if(entry.tools.isNotEmpty())Text("Recorded tools: ${entry.tools.joinToString(", ")}.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        if(entry.evidence.isNotBlank())Text(entry.evidence,fontSize=11.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        entry.verification?.let {Text(it,fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
            HorizontalDivider(color=accent.copy(alpha=.16f))
            Text(if(!ai&&(removed||gap))"A metadata difference or declared redaction does not establish AI use, authenticity, or intent."else "Metadata describes the declared history; it does not establish whether the content is true. Missing labels do not establish human authorship.",fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun provenanceAccent(ai: Boolean): Color = if(!ai)MaterialTheme.colorScheme.onSurfaceVariant else if(MaterialTheme.colorScheme.surface.luminanceForProvenance()<.5f)Color(0xFFE6BC68)else Color(0xFF785214)

private fun Color.luminanceForProvenance(): Float = .2126f*red+.7152f*green+.0722f*blue

/** Small warning-stripe accent, decorative rather than a second alert. */
@Composable private fun HazardMark(accent: Color) {
    Canvas(Modifier.size(width=19.dp,height=13.dp).clipToBounds()) {
        val spacing=6.dp.toPx();val stroke=2.dp.toPx()
        var x=-size.height
        while(x<size.width) {drawLine(accent.copy(alpha=.8f),Offset(x,size.height),Offset(x+size.height,0f),strokeWidth=stroke);x+=spacing}
    }
}

/** Avoid letting untrusted labels turn the disclosure back into a raw, unbounded data dump. */
private fun List<AiDisclosureEntry>.readable(): List<AiDisclosureEntry> = take(32).mapNotNull {entry->
    val paragraphs=entry.paragraphs.take(10).map {it.provenanceText(700)}.filter {it.isNotBlank()}.distinct()
    if(paragraphs.isEmpty())null else entry.copy(source=entry.source.provenanceText(80),asset=entry.asset.provenanceText(80),paragraphs=paragraphs,
        evidence=entry.evidence.provenanceText(180),verification=entry.verification?.provenanceText(350)?.takeIf {it.isNotBlank()},
        tools=entry.tools.take(8).map {it.provenanceText(96).trimEnd('.')}.filter {it.isNotBlank()}.distinct())
}

private fun String.provenanceText(limit: Int): String {
    val plain=replace(Regex("[\\p{Cc}\\p{Cf}]+")," ").replace(Regex("\\s+")," ").trim()
    return if(plain.length<=limit)plain else plain.take(limit-1).trimEnd()+"…"
}
