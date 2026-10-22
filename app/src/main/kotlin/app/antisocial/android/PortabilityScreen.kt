/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Human-readable exports are separate from the private encrypted backup. */
@Composable internal fun PortabilityScreen(repo: Repository,onChanged: ()->Unit={}) {
    val follows by repo.follows.collectAsState(emptyList())
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val snackbar=remember {SnackbarHostState()}
    var progress by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var pending by remember {mutableStateOf<ByteArray?>(null)}
    var pendingPreview by remember {mutableStateOf<PortableImportPreview?>(null)}
    var pack by remember {mutableStateOf(false)}
    var importNotes by remember {mutableStateOf<List<String>>(emptyList())}
    val createJson=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val data=pending;pending=null
        if(uri!=null&&data!=null)scope.launch {
            busy=true;error=null
            try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use {it.write(data)} ?: error("This destination could not be opened.")};snackbar.showSnackbar("Export saved")}
            catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "The export could not be saved."}finally {busy=false}
        }
    }
    val createOpml=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")){uri->
        val data=pending;pending=null
        if(uri!=null&&data!=null)scope.launch {
            busy=true;error=null
            try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use {it.write(data)} ?: error("This destination could not be opened.")};snackbar.showSnackbar("OPML saved")}
            catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "The OPML file could not be saved."}finally {busy=false}
        }
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch {
        busy=true;error=null
        try {
            val bytes=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use {input->
                val out=ByteArrayOutputStream();val block=ByteArray(8192)
                while(true){val count=input.read(block);if(count<0)break;require(out.size()+count<=16*1024*1024){"Choose a reader or source file smaller than 16 MB."};out.write(block,0,count)}
                out.toByteArray()
            } ?: error("This file could not be opened.")}
            pendingPreview=repo.portability.preview(bytes)
        }catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "This is not a supported reader or subscription file."}finally {busy=false}
    }}
    fun prepare(name: String,opml: Boolean=false,action: suspend ()->ByteArray) {
        scope.launch {busy=true;error=null;try {pending=action();if(opml)createOpml.launch(name)else createJson.launch(name)}
        catch(e: CancellationException){throw e}catch(e: Exception){pending=null;error=e.message ?: "The export could not be prepared."}finally {busy=false}}
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("Portability screen"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item {Text("Take your reader with you, or share a few good sources. Files are read locally and reviewed before anything is added.",fontSize=14.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item {PortableCard("Your reader",Icons.Rounded.MoveDown) {
                Text("Subscriptions, folders, channel rules, collections and saved text in anti social's documented reader format.",fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().toggleable(progress,enabled=!busy,role=Role.Checkbox,onValueChange={progress=it}).testTag("Export reading progress"),verticalAlignment=Alignment.CenterVertically) {Checkbox(progress,null,enabled=!busy);Text("Include reading progress",fontSize=14.sp)}
                FilledTonalButton(onClick={prepare("antisocial-reader.json"){repo.portability.exportState(progress)}},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("Export reader file")){Icon(Icons.Rounded.Upload,null);Spacer(Modifier.width(8.dp));Text("Export reader file")}
                Text("This is a readable file. Offline media stays on this device. Use Backup & restore for a passphrase-protected copy.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            item {PortableCard("A source pack",Icons.Rounded.Share) {
                Text("Choose just the subscriptions you want to recommend. Your reading progress, saved posts, channel rules and settings stay here.",fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick={pack=true},enabled=!busy&&follows.isNotEmpty(),modifier=Modifier.fillMaxWidth().testTag("Choose pack sources")){Icon(Icons.Rounded.Checklist,null);Spacer(Modifier.width(8.dp));Text("Choose sources")}
                if(follows.isEmpty())Text("Add subscriptions first to build a source pack.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            item {PortableCard("Bring sources in",Icons.Rounded.Download) {
                Text("Open an anti social reader file or source pack, or an OPML subscription list from a feed reader such as FreshRSS. Choose what to add in the preview.",fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick={open.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("Import reader or source file")){Text("Choose a file")}
                Text("Imports merge with this reader. Existing subscriptions and local settings are kept. Importing does not contact any source.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            if(busy)item {LinearProgressIndicator(Modifier.fillMaxWidth().testTag("Portability working"))}
            if(importNotes.isNotEmpty())item {Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.secondaryContainer){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){importNotes.forEach {Text(it,fontSize=12.sp,lineHeight=18.sp)}}}}
            error?.let {message->item {Text(message,fontSize=13.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("Portability error"))}}
        }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    if(pack)SourcePackSheet(follows,onDismiss={pack=false},onExport={ids,title,opml->
        pack=false
        if(opml)prepare("antisocial-subscriptions.opml",true){repo.portability.exportOpml(ids)}
        else prepare("antisocial-sources.json"){repo.portability.exportPack(ids,title)}
    })
    pendingPreview?.let {preview->PortableImportSheet(preview,onDismiss={pendingPreview=null},onImport={ids,options->
        val result=repo.portability.apply(preview,ids,options)
        onChanged()
        pendingPreview=null
        importNotes=result.warnings
        val summary=buildList {
            add("${result.addedSources} ${if(result.addedSources==1)"source"else "sources"} added")
            if(result.existingSources>0)add("${result.existingSources} already here")
            if(result.channels>0)add("${result.channels} ${if(result.channels==1)"channel"else "channels"}")
            if(result.collections>0)add("${result.collections} ${if(result.collections==1)"collection"else "collections"}")
            if(result.savedPosts>0)add("${result.savedPosts} saved ${if(result.savedPosts==1)"post"else "posts"}")
        }.joinToString(" · ")
        scope.launch {snackbar.showSnackbar(summary)}
    })}
}

@Composable private fun PortableCard(title: String,icon: androidx.compose.ui.graphics.vector.ImageVector,content: @Composable ColumnScope.()->Unit) {
    Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Icon(icon,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(10.dp));Text(title,fontWeight=FontWeight.SemiBold,fontSize=18.sp)}
            content()
        }
    }
}

/** Individual source selection keeps a linked creator from leaking unchosen accounts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SourcePackSheet(follows: List<Follow>,initialSourceIds: Set<String> = emptySet(),initialTitle: String="Sources worth reading",onDismiss: ()->Unit,onExport: (Set<String>,String,Boolean)->Unit) {
    var selected by remember(follows,initialSourceIds){mutableStateOf(initialSourceIds.intersect(follows.map {it.id}.toSet()))}
    var title by remember {mutableStateOf(initialTitle)}
    val sources=remember(follows){follows.sortedWith(compareBy<Follow>{it.personName ?: it.name}.thenBy {it.target.platform.label}.thenBy {it.target.canonicalUrl})}
    val rssCount=selected.count {id->follows.any {it.id==id&&it.target.platform==Platform.RSS}}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Source pack sheet")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal=20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Text("Share chosen sources",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close source pack")}}
            Text("Only checked sources and their public labels go into this file.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title,{title=it.take(120)},singleLine=true,label={Text("Pack title")},modifier=Modifier.fillMaxWidth().padding(top=12.dp).testTag("Source pack title"))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("${selected.size} selected",Modifier.weight(1f),fontSize=13.sp);TextButton(onClick={selected=if(selected.size==sources.size)emptySet()else sources.map {it.id}.toSet()},modifier=Modifier.testTag("Pack select all")){Text(if(selected.size==sources.size)"Clear"else "Select all")}}
            LazyColumn(Modifier.weight(1f).testTag("Pack source list"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                items(sources,key={it.id}) {source->PortableSourceChoice(source,source.id in selected,"Pack source ${source.id}"){checked->selected=if(checked)selected+source.id else selected-source.id}}
            }
            Button(onClick={onExport(selected,title.trim().ifBlank {"anti social sources"},false)},enabled=selected.isNotEmpty(),modifier=Modifier.fillMaxWidth().testTag("Export source pack")){Text("Export source pack")}
            OutlinedButton(onClick={onExport(selected,title.trim().ifBlank {"anti social sources"},true)},enabled=rssCount>0,modifier=Modifier.fillMaxWidth().testTag("Export selected OPML")){Text("Export OPML${if(rssCount>0)" · $rssCount feeds"else ""}")}
            Text("OPML shares ordinary RSS/Atom subscriptions, titles and folders with other feed readers. Use an anti social source pack for social accounts.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp,bottom=24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PortableImportSheet(preview: PortableImportPreview,onDismiss: ()->Unit,onImport: suspend (Set<String>,PortableImportOptions)->Unit) {
    var selected by remember(preview){mutableStateOf(if(preview.isState)preview.sources.map {it.id}.toSet()else emptySet())}
    var channels by remember(preview){mutableStateOf(preview.channels>0)}
    var filters by remember(preview){mutableStateOf(preview.hasFilters)}
    var collections by remember(preview){mutableStateOf(preview.collections>0||preview.savedPosts>0)}
    var settings by remember(preview){mutableStateOf(false)}
    var progress by remember(preview){mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val otherSelected=preview.isState&&((channels&&preview.channels>0)||(filters&&preview.hasFilters)||(collections&&(preview.collections>0||preview.savedPosts>0))||(settings&&preview.hasSettings)||(progress&&preview.hasProgress))
    ModalBottomSheet(onDismissRequest={if(!busy)onDismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Portable import preview")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal=20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Text(if(preview.isState)"Review your reader"else "Choose sources to add",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);IconButton(onClick=onDismiss,enabled=!busy){Icon(Icons.Rounded.Close,"Close import preview")}}
            Text(preview.document.title?.takeIf {it.isNotBlank()} ?: if(preview.isState)"anti social reader file"else "Shared subscriptions",fontWeight=FontWeight.Medium,fontSize=15.sp,modifier=Modifier.padding(bottom=6.dp))
            Text("Nothing has been added yet. Existing subscriptions are kept, and sources are not contacted during import.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).padding(top=10.dp).testTag("Import preview list"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(preview.warnings.isNotEmpty())item {Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.secondaryContainer){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){preview.warnings.forEach {Text(it,fontSize=12.sp,lineHeight=18.sp)}}}}
                if(preview.isState) {
                    item {Text("READER DATA",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))}
                    if(preview.channels>0)item {ImportOption("${preview.channels} ${if(preview.channels==1)"channel"else "channels"}","Explicit rules and selected subscriptions",channels,!busy,"Import channels"){channels=it}}
                    if(preview.hasFilters)item {ImportOption("Filter rules & protection","Feed rules, enabled lists and custom list URLs",filters,!busy,"Import filters"){filters=it}}
                    if(preview.collections>0||preview.savedPosts>0)item {ImportOption("Collections & saved text","${preview.collections} ${if(preview.collections==1)"collection"else "collections"} · ${preview.savedPosts} ${if(preview.savedPosts==1)"saved post"else "saved posts"} · no offline media files",collections,!busy,"Import collections"){collections=it}}
                    if(preview.hasSettings)item {ImportOption("Reader preferences","Appearance and reading choices; connection and participation settings stay on this device",settings,!busy,"Import reader preferences"){settings=it}}
                    if(preview.hasProgress)item {ImportOption("Reading progress","Read markers and continue-reading positions",progress,!busy,"Import reading progress"){progress=it}}
                }
                item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("SOURCES · ${selected.size} / ${preview.sources.size}",Modifier.weight(1f),fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onClick={selected=if(selected.size==preview.sources.size)emptySet()else preview.sources.map {it.id}.toSet()},enabled=!busy&&preview.sources.isNotEmpty(),modifier=Modifier.testTag("Import select all")){Text(if(selected.size==preview.sources.size&&selected.isNotEmpty())"Clear"else "Select all")}}}
                items(preview.sources,key={it.id}) {source->PortableSourceChoice(source,source.id in selected,"Import source ${source.id}",!busy){checked->selected=if(checked)selected+source.id else selected-source.id}}
                if(preview.sources.isEmpty())item {Text("No subscriptions in this file.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                error?.let {message->item {Text(message,fontSize=13.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("Import preview error"))}}
                if(busy)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            }
            Button(onClick={scope.launch {busy=true;error=null;try {onImport(selected,PortableImportOptions(channels=channels,filters=filters,collections=collections,settings=settings,progress=progress))}catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "The selected data could not be imported."}finally {busy=false}}},enabled=!busy&&(selected.isNotEmpty()||otherSelected),modifier=Modifier.fillMaxWidth().padding(top=8.dp,bottom=24.dp).testTag("Confirm portable import")){Text(if(busy)"Adding…"else "Add selected")}
        }
    }
}

@Composable private fun ImportOption(title: String,description: String,checked: Boolean,enabled: Boolean,tag: String,onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked,enabled=enabled,role=Role.Checkbox,onValueChange=onChange).testTag(tag).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(checked,null,enabled=enabled);Column(Modifier.weight(1f).padding(start=8.dp)){Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium);Text(description,fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
}

@Composable private fun PortableSourceChoice(source: Follow,checked: Boolean,tag: String,enabled: Boolean=true,onChange: (Boolean)->Unit) {
    Surface(shape=MaterialTheme.shapes.large,color=if(checked)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().toggleable(checked,enabled=enabled,role=Role.Checkbox,onValueChange=onChange).testTag(tag).padding(horizontal=4.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked,null,enabled=enabled)
            Column(Modifier.weight(1f).padding(start=6.dp,end=10.dp)) {
                Text(source.personName?.takeIf {it.isNotBlank()} ?: source.name,fontWeight=FontWeight.Medium,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(source.target.platform.label+if(source.group.isNotBlank())" · ${source.group}"else "",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(source.target.canonicalUrl,fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}
