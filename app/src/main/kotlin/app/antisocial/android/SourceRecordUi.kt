/* AntiSocial, GPL-3.0-or-later. Evidence appears where it is relevant, on demand. */
package app.antisocial.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.antisocial.core.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private fun recordDate(at:Long)=DateTimeFormatter.ofPattern("MMM d, uuuu").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(at))
internal fun sourceRequests(urls:List<String>,profile:Profile?=null):List<SourceRecordRequest> =
    (listOfNotNull(PublicSourceRecords.account(profile?.persistentId))+urls.mapNotNull(PublicSourceRecords::paper)+urls.filter {url->
        SocialLinkHosts.platform(url)==null&&url.toHttpUrlOrNull()?.host?.removePrefix("www.") !in setOf("doi.org","dx.doi.org","pubmed.ncbi.nlm.nih.gov","pmc.ncbi.nlm.nih.gov","web.archive.org")&&url!=profile?.target?.canonicalUrl
    }.mapNotNull(PublicSourceRecords::website)).distinct().take(12)

/** Requests are owned by the visible disclosure. A foreground request may
 * cancel a supplemental read; retry only while this UI still needs it. */
@Composable private fun rememberSourceRecord(repo:Repository,request:SourceRecordRequest,settleMs:Long=0,automatic:Boolean=true):State<PublicSourceRecord?> =
    produceState<PublicSourceRecord?>(null,repo,request,repo.gateway.currentPolicy(),repo.prefs.originalFallback) {
        val activity=repo.cardWork.begin(listOf("source:"+request.key),"Source information")
        try {
        delay(settleMs)
        while(isActive) {
            try {value=repo.sourceRecords.lookup(request,deferred=automatic){value=it};break}
            catch(e:CancellationException){currentCoroutineContext().ensureActive();delay(700)}
            catch(_:Exception){value=PublicSourceRecord(request,emptyList(),System.currentTimeMillis(),false,"Reopen this panel after the network mode changes.");break}
        }
        }finally {activity.close()}
    }

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun SourceDetailsSheet(repo:Repository,urls:List<String>,profile:Profile?=null,onOpen:(String)->Unit,onDismiss:()->Unit) {
    val requests=remember(urls,profile){sourceRequests(urls,profile)}
    var selected by rememberSaveable(requests){mutableStateOf(0)}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Source details")) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.88f),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Row(verticalAlignment=Alignment.CenterVertically){Text(if(profile!=null)"Account details"else "Source details",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close source details")}}}
            if(profile?.verifiedWebsites.orEmpty().isNotEmpty())item {
                Text("Connected websites",style=MaterialTheme.typography.titleMedium)
                Text("This account's server reports a verified link back from each website.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                profile?.verifiedWebsites.orEmpty().forEach {website->
                    TextButton(onClick={onOpen(website.url)},modifier=Modifier.testTag("Verified website ${website.url}")) {Icon(Icons.Rounded.Link,null,Modifier.size(16.dp));Spacer(Modifier.width(8.dp));Text(website.label.ifBlank {website.url})}
                    Text(website.url+" · verified "+recordDate(website.verifiedAt),style=MaterialTheme.typography.bodySmall)
                }
            }
            if(requests.size>1)item {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    requests.forEachIndexed {index,request->FilterChip(selected=selected==index,onClick={selected=index},label={Text(when(request.kind){SourceRecordKind.PAPER->"Paper";SourceRecordKind.ACCOUNT->"History";SourceRecordKind.WEBSITE->request.identity})})}
                }
            }
            requests.getOrNull(selected)?.let {request->item(key=request.key){SourceRecordPanel(repo,request,onOpen)}}
            if(requests.isEmpty()&&profile?.verifiedWebsites.orEmpty().isEmpty())item {Text("No supported public records are attached to this item.")}
        }
    }
}

@Composable internal fun SourceRecordPanel(repo:Repository,request:SourceRecordRequest,onOpen:(String)->Unit) {
    val record by rememberSourceRecord(repo,request,automatic=false)
    val clipboard=LocalClipboardManager.current
    Column(verticalArrangement=Arrangement.spacedBy(14.dp),modifier=Modifier.testTag("Source record ${request.key}")) {
        SelectionContainer {Text(request.identity,style=MaterialTheme.typography.titleSmall)}
        val data=record
        if(data==null)Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Text("Checking public records…",style=MaterialTheme.typography.bodySmall)}
        else {
            data.facts.forEach {fact->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                        Icon(when {fact.kind=="paper:retraction"||fact.kind=="paper:expression-of-concern"->Icons.Rounded.Info;fact.kind.startsWith("account")->Icons.Rounded.History;else->Icons.Rounded.Description},null,Modifier.size(17.dp),tint=MaterialTheme.colorScheme.primary)
                        Text(fact.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                    }
                    SelectionContainer {Text(fact.detail,style=MaterialTheme.typography.bodyMedium)}
                    fact.recordedAt?.let {Text(recordDate(it),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    val destination=fact.destination
                    if(destination!=null&&fact.kind.startsWith("paper:"))TextButton(onClick={onOpen(destination)},contentPadding=PaddingValues(0.dp),modifier=Modifier.testTag("Open publication notice")){Text("Read notice");Spacer(Modifier.width(6.dp));Icon(Icons.Rounded.ArrowForward,null,Modifier.size(16.dp))}
                }
            }
            if(data.message.isNotBlank())Text(data.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(data.facts.isNotEmpty()) {
                HorizontalDivider()
                Text("Checked "+recordDate(data.checkedAt),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                data.facts.map {it.sourceUrl}.distinct().forEach {url->TextButton(onClick={clipboard.setText(AnnotatedString(url))},contentPadding=PaddingValues(0.dp)){Text("Copy record link · "+java.net.URI(url).host,style=MaterialTheme.typography.labelMedium)}}
            }
        }
    }
}

/** No empty badge, spinner or score in the feed. Exact paper notices enrich
 * an already readable item after a quiet dwell and reuse the ordinary cache. */
@Composable internal fun PaperUpdateNotice(repo:Repository,urls:List<String>,onOpen:()->Unit) {
    val papers=remember(urls){urls.mapNotNull(PublicSourceRecords::paper).distinct().take(2)}
    papers.forEach {paper->
        val record by rememberSourceRecord(repo,paper,1500)
        val facts=record?.facts.orEmpty()
        if(facts.isNotEmpty())TextButton(onClick=onOpen,modifier=Modifier.padding(horizontal=8.dp).testTag("Publication update available")) {
            Icon(Icons.Rounded.Info,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp))
            Text(when(facts.firstOrNull {it.kind in setOf("paper:retraction","paper:reinstatement")}?.kind){"paper:retraction"->"Cited paper · retraction recorded";"paper:reinstatement"->"Cited paper · reinstatement recorded";else->"Cited paper · publication update"})
        }
    }
}

@Composable internal fun CommunityNoteDisclosure(notes:List<PublicContextNote>,onOpen:(String)->Unit) {
    if(notes.isEmpty())return
    var expanded by rememberSaveable(notes){mutableStateOf(false)}
    Column(Modifier.padding(horizontal=12.dp)) {
        TextButton(onClick={expanded=!expanded},modifier=Modifier.testTag("Community note disclosure")) {
            Icon(Icons.Rounded.Lightbulb,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("Community note")
            Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null,Modifier.size(18.dp))
        }
        if(expanded)notes.distinctBy {it.text}.take(4).forEach {note->
            SelectionContainer {Text(note.text,style=MaterialTheme.typography.bodyMedium)}
            Text(note.status?.let {"Recorded status: $it"} ?: "Rating status was not supplied by this source.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            note.links.forEach {url->TextButton(onClick={onOpen(url)}){Text(url,maxLines=2)}}
        }
    }
}
