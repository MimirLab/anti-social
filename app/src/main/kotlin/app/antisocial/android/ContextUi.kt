/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.ContextDocument
import app.antisocial.core.ContextFinding
import app.antisocial.core.ContextBriefEvidence
import app.antisocial.core.ContextPublicationFrame
import app.antisocial.core.ContextEvidenceScope
import app.antisocial.core.ContextContributions
import app.antisocial.core.ContextReplyContext
import app.antisocial.core.ContextReplyContexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class ContextSheetData(val view: ContextView, val saved: ContextView?, val steps: Int, val reading:ContextView?=null,val earlier:ContextView?=null)
private data class ContextCitation(val source: ContextDocument?, val id: String, val quote: String, val label: String, val evidence:ContextView?=null)

/** Publications wait behind an explicit action. The brief, finding order and
 * cited local copies do not change beneath a reader while work continues. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ContextSheet(repo: Repository, onOpenLink: (String) -> Unit) {
    val reader = repo.contextReader
    val selected by reader.selected.collectAsState()
    val revision by reader.store.revision.collectAsState()
    val active by reader.active.collectAsState()
    val ai by repo.ai.state.collectAsState()
    val id = selected ?: return
    var latest by remember(id) { mutableStateOf<ContextSheetData?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var displayed by remember(id) { mutableStateOf<ContextSheetData?>(null) }
    var followingFirstRead by remember(id) { mutableStateOf(true) }
    var savedMode by rememberSaveable(id) { mutableStateOf(false) }
    var findingsExpanded by rememberSaveable(id) { mutableStateOf(false) }
    var sourcesExpanded by rememberSaveable(id) { mutableStateOf(false) }
    var earlierExpanded by rememberSaveable(id) { mutableStateOf(false) }
    var citation by remember(id) { mutableStateOf<ContextCitation?>(null) }
    LaunchedEffect(id, revision) {
        val data = withContext(Dispatchers.IO) {
            reader.store.view(id)?.let { view ->
                val reading=if(view.record.stale || view.record.brief.isBlank())reader.store.readingView(id)?.takeIf {
                    it.record.snapshot.revision==view.record.snapshot.revision && it.record.processingPolicy==view.record.processingPolicy && it.findings.isNotEmpty() &&
                        (view.record.stale || it.findings!=view.findings || it.documents!=view.documents || it.replyEvidence!=view.replyEvidence)
                }else null
                ContextSheetData(view, reader.store.savedView(id), reader.store.checkpointUnits(id),reading,reader.store.earlierView(id))
            }
        }
        latest = data
        loaded = true
        if(displayed==null)followingFirstRead=data?.view?.record?.let {it.brief.isBlank()||it.deliveryPolicy!=reader.deliveryIdentity()} ?: true
        // Working notes must not freeze the sheet before its first brief.
        // Follow the initial requested read through completion; subsequent
        // changes to a brief already being read retain the explicit update.
        if(displayed==null||followingFirstRead||displayed?.view?.record?.brief.isNullOrBlank())displayed=data
        if(data?.view?.record?.let {it.status in setOf("ready","partial")&&it.cycleStartedAt==0L&&it.deliveryPolicy==reader.deliveryIdentity()}==true)
            followingFirstRead=false
    }
    val shown = if (savedMode) latest?.saved ?: displayed?.view else displayed?.view
    val reading = if(savedMode)null else displayed?.reading
    val findingView=(reading ?: shown)?.let {view->
        val selectedIds=view.record.briefFindingIds
        if(reading==null&&selectedIds!=null)view.copy(findings=ContextBriefEvidence.select(view.findings,selectedIds,view.record.briefSources))else view
    }
    val earlier=if(savedMode)null else if(reading!=null&&shown?.record?.brief?.isNotBlank()==true)shown else displayed?.earlier
    val documentsById = remember(shown) { (shown?.documents.orEmpty().map {it.document}+shown?.replyEvidence?.documents.orEmpty()).associateBy {it.id} }
    val findingDocuments = remember(findingView) { (findingView?.documents.orEmpty().map {it.document}+findingView?.replyEvidence?.documents.orEmpty()).associateBy {it.id} }
    val current = latest?.view
    val changed = !savedMode && current != null && shown != null && (
        current.record.brief != shown.record.brief ||
            (if(current.record.briefSignalStamp!=null&&shown.record.briefSignalStamp!=null)current.record.briefSignalStamp!=shown.record.briefSignalStamp
            else current.record.findingStamp!=shown.record.findingStamp||current.findings.map {it.id to it.sources}!=shown.findings.map {it.id to it.sources}) ||
            latest?.reading?.findings != reading?.findings || latest?.reading?.documents != reading?.documents || latest?.reading?.replyEvidence != reading?.replyEvidence)
    val busy = id in active
    ModalBottomSheet(onDismissRequest = reader::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("Context sheet")) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.94f).testTag("Context reading list"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoStories, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
                        Text("Context", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = reader::close, modifier = Modifier.testTag("Close Context")) { Icon(Icons.Rounded.Close, "Close Context") }
                    }
                    shown?.record?.snapshot?.title?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (savedMode) "Saved snapshot" else contextStatus(current?.record, busy,latest?.reading?.findings?.size ?: 0), fontSize = 12.sp, lineHeight = 17.sp, minLines = 2, maxLines = 2, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context reading status"))
                            val time = shown?.record?.let { if (it.checkedAt > 0) it.checkedAt else it.updatedAt } ?: 0
                            if (time > 0) Text("As of ${contextDate(time)}", fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context as of"))
                        }
                        if (busy) TextButton(onClick = { reader.stop(id) }, modifier = Modifier.testTag("Stop Context")) { Icon(Icons.Rounded.Stop, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Stop") }
                        else if (!savedMode && current != null) TextButton(onClick = { reader.request(current.record.snapshot,force=true) }, enabled = ai.enabled && repo.ai.contextAvailable(), modifier = Modifier.testTag("Refresh Context")) { Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh") }
                    }
                    if (latest?.saved != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !savedMode, onClick = { savedMode = false; displayed = latest }, label = { Text("Latest") }, modifier = Modifier.testTag("Context latest view"))
                        FilterChip(selected = savedMode, onClick = { savedMode = true }, label = { Text("Saved snapshot") }, leadingIcon = { Icon(Icons.Rounded.BookmarkBorder, null, Modifier.size(16.dp)) }, modifier = Modifier.testTag("Context saved view"))
                    }
                    // Reserve this line so an arriving update cannot move the
                    // brief or the content underneath the reader's finger.
                    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
                        if (changed) Surface(onClick = { displayed = latest }, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("Context updates ready")) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text("Updates ready", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium); Text("Show", fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Icon(Icons.Rounded.ArrowForward, null, Modifier.padding(start = 6.dp).size(15.dp)) }
                        } else Text(if (savedMode) "A local copy, kept as captured." else "A brief from the public material read so far.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (shown == null) item {
                Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { if (!loaded) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(if (loaded) "This Context copy is no longer in the local cache." else "Opening your saved progress…", fontSize = 14.sp) }
            } else {
                val inlineFindings = false
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("Context brief section")) {
                        Text(if (inlineFindings) "AVAILABLE FINDINGS" else "THE BRIEF", fontSize = 10.sp, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        if (shown.record.brief.isNotBlank()) SelectionContainer { Text(shown.record.brief, fontSize = 16.sp, lineHeight = 25.sp, modifier = Modifier.testTag("Context brief")) }
                        else if (!inlineFindings) Text(if (busy) "Reading the post and its public sources. You can leave this open or return later; progress is kept." else if (shown.record.status in setOf("ready","partial")) "No useful additions found in the material read." else if (shown.streams.any {it.kind=="review"}) "The collected source copies and reading progress are saved below." else "Your collected source copies and reading progress are saved below.", fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context awaiting brief"))
                        if (!ai.enabled) Text("AI is off. Your existing Context stays readable.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!inlineFindings) item {
                    ContextDisclosure("Evidence", "${findingView?.findings?.size ?: 0} retained points · original quotations", findingsExpanded, "Context findings toggle") { findingsExpanded = !findingsExpanded }
                }
                if (inlineFindings || findingsExpanded) {
                    if (findingView?.findings.orEmpty().isEmpty()) item { Text("No additional findings have been retained yet. Reviewed comments can legitimately add no new information.", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(findingView?.findings.orEmpty(), key = { it.id }) { finding ->
                        ContextFindingCard(finding, findingDocuments,findingView?.replyEvidence?.contexts ?: ContextReplyContexts()) { source, sourceId, quote, label -> citation = ContextCitation(source, sourceId, quote, label,findingView) }
                    }
                }
                if(earlier!=null) {
                    item {ContextDisclosure("Earlier brief","Kept with its original sources",earlierExpanded,"Context earlier toggle"){earlierExpanded=!earlierExpanded}}
                    if(earlierExpanded) {
                        item {SelectionContainer {Text(earlier.record.brief,fontSize=16.sp,lineHeight=25.sp,modifier=Modifier.testTag("Context earlier brief"))}}
                        items(earlier.findings,key={"earlier:"+it.id}) {finding->
                            val earlierDocuments=(earlier.documents.map {it.document}+earlier.replyEvidence?.documents.orEmpty()).associateBy {it.id}
                            ContextFindingCard(finding,earlierDocuments,earlier.replyEvidence?.contexts ?: ContextReplyContexts(),"Earlier Context") {source,sourceId,quote,label->citation=ContextCitation(source,sourceId,quote,label,earlier)}
                        }
                    }
                }
                item {
                    val coverage = if (savedMode) shown else current ?: shown
                    ContextDisclosure("Sources", contextCoverageLabel(coverage), sourcesExpanded, "Context sources toggle") { sourcesExpanded = !sourcesExpanded }
                }
                if (sourcesExpanded) item {
                    ContextCoverage(if (savedMode) shown else current ?: shown, if (savedMode) null else latest?.steps, busy)
                }
            }
        }
    }
    citation?.let { selectedSource ->
        val citedView=selectedSource.evidence ?: shown
        val citedDocuments=(citedView?.documents.orEmpty().map {it.document}+citedView?.replyEvidence?.documents.orEmpty()).associateBy {it.id}
        val url = selectedSource.source?.url?.takeIf { it.isNotBlank() }?.let { runCatching { repo.publicUrl(it) }.getOrNull() }
        AlertDialog(onDismissRequest = { citation = null }, modifier = Modifier.testTag("Context source excerpt"), title = { Text(selectedSource.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("From the local copy", fontSize = 11.sp, letterSpacing = .5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                val excerpt = selectedSource.quote.ifBlank { selectedSource.source?.text.orEmpty() }.take(12000)
                SelectionContainer { Text(excerpt.ifBlank { "This source's local excerpt is no longer available." }, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("Context local quote")) }
                citedView?.replyEvidence?.contexts?.source(selectedSource.id)?.let {frame->
                    ContextReplyFrame(frame,citedDocuments,"Context local parent") {source,sourceId,quote,label->citation=ContextCitation(source,sourceId,quote,label,citedView)}
                }
                val source = selectedSource.source
                source?.let {ContextEvidenceScope.from(it).readerDescription()}?.let {description->
                    Text(description,fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Context observation scope"))
                }
                if (source != null && source.text != selectedSource.quote && source.text.isNotBlank()) {
                    var more by remember(selectedSource.id) { mutableStateOf(false) }
                    TextButton(onClick = { more = !more }, modifier = Modifier.testTag("Context source more")) { Text(if (more) "Hide surrounding text" else "Show surrounding text") }
                    if (more) SelectionContainer { Text(source.text.take(12000), fontSize = 13.sp, lineHeight = 21.sp) }
                    if (more && source.text.length > 12000) Text("A local excerpt is shown. Open the original to read further.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val publications=ContextPublicationFrame.ordered(source?.let(ContextSources::publications).orEmpty())
                if(publications.size==1) {
                    val time=publications.single().time
                    Text(if(time==null)"Publication time unavailable"else "${if(time.earliestKnownEdition)"Earliest known edition"else "Posted"} ${time.readable}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Context publication time"))
                }else if(publications.isNotEmpty()) {
                    var allDates by remember(selectedSource.id) {mutableStateOf(false)}
                    Column(verticalArrangement=Arrangement.spacedBy(5.dp),modifier=Modifier.testTag("Context publication times")) {
                        Text("Known editions",fontSize=11.sp,fontWeight=FontWeight.Medium)
                        (if(allDates)publications else ContextPublicationFrame.representatives(publications,3)).forEach {Text(it.displayLabel(),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        if(publications.size>3)TextButton(onClick={allDates=!allDates},modifier=Modifier.testTag("Context publication times toggle")) {Text(if(allDates)"Show fewer editions"else "Show all ${publications.size} editions")}
                    }
                }
                source?.fetchedAt?.takeIf { it > 0 }?.let { Text("${if(source.metadata["savedCapture"]=="true")"Captured"else "Retrieved"} ${contextDate(it)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }, confirmButton = {
            if (url != null) TextButton(onClick = { citation = null; reader.close(); onOpenLink(url) }, modifier = Modifier.testTag("Context open source")) { Text("Open original") }
        }, dismissButton = { TextButton(onClick = { citation = null }, modifier = Modifier.testTag("Close Context source")) { Text("Close") } })
    }
}

@Composable private fun ContextFindingCard(finding: ContextFinding, documents: Map<String, ContextDocument>, replies:ContextReplyContexts, tagPrefix:String="Context", onSource: (ContextDocument?, String, String, String) -> Unit) {
    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("$tagPrefix finding ${finding.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(contextFindingLabel(finding.kind), fontSize = 10.sp, letterSpacing = .6.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            ContextContributions.groups(listOf(finding),replies::source).forEachIndexed {groupIndex,group->
                group.reply?.let {ContextReplyFrame(it,documents,"Context parent ${finding.id} $groupIndex",onSource)}
                SelectionContainer { Text(group.finding.text, fontSize = 14.sp, lineHeight = 22.sp) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                group.finding.sources.forEach { id ->
                    val index=finding.sources.indexOf(id)
                    val source = documents[id]; val label = contextSourceLabel(source)
                    AssistChip(onClick = { onSource(source, id, finding.quotes[id].orEmpty(), label) }, label = { Text(label, fontSize = 11.sp, maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.FormatQuote, null, Modifier.size(14.dp)) }, modifier = Modifier.testTag("$tagPrefix citation ${finding.id} $index"))
                }
                }
            }
        }
    }
}

@Composable private fun ContextReplyFrame(frame:ContextReplyContext,documents:Map<String,ContextDocument>,tag:String,onSource:(ContextDocument?,String,String,String)->Unit) {
    Column(Modifier.fillMaxWidth().testTag(tag),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        val label=frame.sources.mapNotNull {documents[it]}.map(::contextSourceLabel).distinct().take(3).joinToString(" · ").ifBlank {frame.label}
        Text(when {frame.languagePending->"Earlier reply · English preparation pending";frame.unavailable->"Earlier reply context unavailable";else->"In reply to ${label.ifBlank {"an earlier reply"}}"},fontSize=11.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!frame.unavailable&&!frame.languagePending&&frame.text.isNotBlank())Text("“${frame.text}”",fontSize=12.sp,lineHeight=18.sp,maxLines=3,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(frame.partial&&!frame.unavailable)Text("Partial earlier reply excerpt",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(frame.uncertain)Text("Earlier reply text is marked uncertain",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        frame.sources.firstOrNull {it in documents}?.let {id->val source=documents.getValue(id)
            TextButton(onClick={onSource(source,id,source.text,contextSourceLabel(source))},modifier=Modifier.testTag("$tag open"),contentPadding=PaddingValues(0.dp)) {Text("Read earlier reply",fontSize=12.sp)}
        }
    }
}

@Composable private fun ContextDisclosure(title: String, subtitle: String, expanded: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse $title" else "Expand $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ContextCoverage(view: ContextView, steps: Int?, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp), modifier = Modifier.testTag("Context source coverage")) {
        val sourceCount=contextSourceCount(view)
        Text("$sourceCount local source ${if (sourceCount == 1) "copy" else "copies"} cited", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        val received = view.streams.filter { it.kind != "saved" }.sumOf { it.count }
        if (received > 0) Text("$received entries returned by public sources", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (steps != null) Text("$steps reading ${if (steps == 1) "step" else "steps"} saved · progress survives closing the app", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context checkpoint count"))
        val groups = view.streams.groupBy { stream -> when (stream.kind) { "saved" -> "Saved discussions"; "review" -> "Model review"; "link" -> "Linked pages"; "media" -> "Images & speech"; "shared" -> "Shared discussion"; else -> "${stream.root.platform.label} discussion" } }
        groups.forEach { (label, streams) ->
            val waiting = streams.count { it.status == "pending" }
            val unavailable = streams.count { it.status == "unavailable" }
            val limited = streams.count { it.status == "limited" || it.limited }
            val status = when { label == "Saved discussions" -> "Dated local copies · live coverage remains separate"; label == "Model review" -> "${streams.size} ${if(streams.size==1) "passage awaits" else "passages await"} another review"; waiting > 0 -> if (busy) "Reading" else "Waiting"; unavailable > 0 -> if (streams.size == unavailable) "Unavailable this check" else "Some unavailable"; limited > 0 -> "Source limited"; streams.all { it.status == "disabled" } -> "Off"; else -> "Available material read" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (waiting > 0) Icons.Rounded.Schedule else if (unavailable > 0 || limited > 0) Icons.Rounded.Info else Icons.Rounded.CheckCircleOutline, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) { Text(label, fontSize = 13.sp); Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)); if(label=="Saved discussions")streams.map {it.message}.distinct().take(3).forEach { Text(it,fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp)) } }
            }
        }
        if (groups.isEmpty()) Text("The post is available locally. Discussion and linked-source coverage will appear after the next check.", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (view.streams.any { it.status in setOf("unavailable", "limited", "disabled", "pending") }) Text("This brief has partial coverage. Unavailable or unread material stays visible here instead of being treated as completed.", fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("Context partial coverage"))
    }
}

private fun contextStatus(record: ContextRecord?, busy: Boolean,findings:Int=0) = when {
    record?.stale == true && record.brief.isNotBlank() -> if(busy||record.status=="queued")"Earlier Context · checking source changes"else if(findings>0)"$findings ${if(findings==1)"finding"else"findings"} · partial source coverage"else "Earlier brief kept · partial source coverage"
    busy -> "Reading · progress saved as it goes"
    record == null -> "Opening Context"
    record.status == "ready" -> "Available sources read"
    record.status == "partial" -> "Ready · partial source coverage"
    record.status == "paused" -> "Paused · your progress is saved"
    record.status == "queued" -> "Waiting to continue"
    else -> "Progress saved"
}
private fun contextCoverageLabel(view: ContextView): String {
    val pending = view.streams.count { it.status == "pending" }
    val limited = view.streams.any { it.status in setOf("unavailable", "limited", "disabled") || it.limited }
    val count=contextSourceCount(view)
    return "$count ${if (count == 1) "source" else "sources"} cited" + when { pending > 0 -> " · $pending waiting"; limited -> " · partial coverage"; else -> " · public sources" }
}
private fun contextSourceCount(view:ContextView)=(view.record.briefSources ?: (view.documents.map {it.document.id}+view.replyEvidence?.documents.orEmpty().map {it.id})).distinct().size
private fun contextSourceLabel(source: ContextDocument?): String {
    if (source == null) return "Stored source"
    val author = source.author.trim().removePrefix("@").take(80)
    val site = source.metadata["platform"] ?: runCatching { java.net.URI(source.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
    return listOf(author.ifBlank { if (source.kind == "post") "Original post" else source.title.take(60).ifBlank { "Source" } }, site).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}
private fun contextFindingLabel(kind: String) = when (kind) { "correction", "clarification" -> "CORRECTION & CLARIFICATION"; "disagreement" -> "A DIFFERENT VIEW"; "experience" -> "REPORTED EXPERIENCE"; "question" -> "QUESTION"; "answer" -> "ANSWER"; "source" -> "LINKED SOURCE"; "warning" -> "REPORTED CAUTION"; "brief" -> "THE POST"; else -> "ADDED CONTEXT" }
private fun contextDate(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
