/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** This sheet selects a durable capture. Opening it never fetches more replies or artwork. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SaveCopySheet(repo: Repository,post: Post,threads: List<CapturedThread> = emptyList(),fusionRoots: List<Post> = emptyList(),onSaved: (String)->Unit,onDismiss: ()->Unit) {
    val collections by repo.library.collections.collectAsState(emptyList())
    val scope=rememberCoroutineScope()
    var existing by remember {mutableStateOf<LibraryEntry?>(null)}
    var initialized by remember {mutableStateOf(false)}
    var includeReplies by remember {mutableStateOf(false)}
    val capturedPost=existing?.post ?: post
    val capturedRoots=remember(capturedPost,existing,fusionRoots){
        if(existing?.details?.fusionRoots.orEmpty().isEmpty()&&fusionRoots.isEmpty())emptyList()
        else (listOf(capturedPost)+existing?.details?.fusionRoots.orEmpty()+fusionRoots).distinctBy(repo::key)
    }
    val chosenThreads=remember(existing,threads,includeReplies){repo.library.mergeThreads(existing?.details?.threads.orEmpty(),if(includeReplies)threads else emptyList())}
    val assets=remember(capturedPost,chosenThreads,capturedRoots){repo.library.availableAssets(capturedPost,chosenThreads,capturedRoots)}
    var selected by remember {mutableStateOf(repo.library.availableAssets(post,fusionRoots=fusionRoots).filter {it.type=="image"}.map {it.key}.toSet())}
    var known by remember {mutableStateOf(repo.library.availableAssets(post,fusionRoots=fusionRoots).map {it.key}.toSet())}
    var collectionIds by remember {mutableStateOf<Set<String>>(emptySet())}
    var create by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(repo.key(post)) {
        val saved=repo.library.copy(repo.key(post))
        val priorRoots=(listOf(saved?.post ?: post)+saved?.details?.fusionRoots.orEmpty()+fusionRoots).distinctBy(repo::key)
        val priorAssets=repo.library.availableAssets(saved?.post ?: post,saved?.details?.threads.orEmpty(),priorRoots)
        selected=saved?.details?.selectedAssets?.map {it.key}?.toSet() ?: priorAssets.filter {it.type=="image"}.map {it.key}.toSet()
        known=priorAssets.map {it.key}.toSet();collectionIds=saved?.collections.orEmpty().map {it.id}.toSet()
        existing=saved;initialized=true
    }
    val initializedSnapshot=initialized
    LaunchedEffect(assets,initializedSnapshot) {
        if(!initializedSnapshot)return@LaunchedEffect
        val keys=assets.map {it.key}.toSet()
        selected=(selected.intersect(keys)+assets.filter {it.key !in known&&it.type=="image"}.map {it.key})
        known=known+keys
    }
    ModalBottomSheet(onDismissRequest={if(!busy)onDismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Save copy sheet")) {
        LazyColumn(Modifier.fillMaxWidth(),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {Row(verticalAlignment=Alignment.CenterVertically) {Text("Save a copy",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);IconButton(onClick=onDismiss,enabled=!busy){Icon(Icons.Rounded.Close,"Close save copy")}}}
            if(!initialized)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            item {Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                MentionText(capturedPost.author.ifBlank {capturedPost.platform.label},repo,capturedPost.platform,capturedPost.canonicalUrl,accountLabel=true,networkAllowed=false,explicitProfileLinks=capturedPost.links.orEmpty(),fontWeight=FontWeight.SemiBold)
                Text(repo.publicText(capturedPost.title.ifBlank {capturedPost.body},capturedPost.platform),maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Text, attribution and the original link stay on this device. Any verified paired archive stays with the post.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(capturedRoots.size>1)Text("All ${capturedRoots.size} source editions stay with this copy, including their extra text and original links.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Save fused source editions"))
            }}
            if(threads.isNotEmpty())item {
                Row(Modifier.fillMaxWidth().toggleable(includeReplies,enabled=initialized&&!busy,role=Role.Checkbox,onValueChange={includeReplies=it}).testTag("Include loaded replies"),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(includeReplies,null,enabled=initialized&&!busy)
                    Column(Modifier.weight(1f).padding(start=8.dp)) {Text("Include loaded replies",fontSize=14.sp);Text("${threads.sumOf {it.count}} replies captured so far · public threads may be incomplete",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
            }
            item {Row(verticalAlignment=Alignment.CenterVertically) {Text("Collections",Modifier.weight(1f),fontWeight=FontWeight.SemiBold);TextButton(onClick={create=true},enabled=initialized&&!busy){Text("New collection")}}}
            item {FlowCollectionChoices(collections,collectionIds,initialized&&!busy){collectionIds=it}}
            if(assets.isNotEmpty())item {Column {Text("Attachments",fontWeight=FontWeight.SemiBold);Text("Images are selected by default. Videos download only when you select them.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
            items(assets,key={it.key}) {asset->
                val index=assets.indexOf(asset)+1
                Row(Modifier.fillMaxWidth().toggleable(asset.key in selected,enabled=initialized&&!busy,role=Role.Checkbox,onValueChange={checked->selected=if(checked)selected+asset.key else selected-asset.key}).testTag("Save media ${asset.key}"),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(asset.key in selected,null,enabled=initialized&&!busy)
                    Icon(if(asset.type=="image")Icons.Rounded.Image else Icons.Rounded.Videocam,null,Modifier.padding(horizontal=10.dp).size(23.dp))
                    Column(Modifier.weight(1f)) {Text(repo.publicText(asset.description.ifBlank {if(asset.type=="image")"Photo $index"else "Video $index"}),maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=14.sp);Text(if(asset.edition=="original"||asset.edition.startsWith("original:"))"Paired archived version"else if(asset.type=="image")"Image"else "Video · uses your video download allowance",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
            }
            error?.let {message->item {Text(repo.publicText(message),color=MaterialTheme.colorScheme.error,fontSize=13.sp)}}
            if(capturedPost.preview)item {Text("A complete public post is needed to save a copy. This source supplied only a preview.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item {Button(onClick={busy=true;error=null;scope.launch {
                try {val key=repo.library.save(capturedPost,assets.filter {it.key in selected},chosenThreads,collectionIds,capturedRoots);withContext(Dispatchers.Main.immediate){onSaved(key)}}
                catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "This copy could not be saved.";busy=false}
            }},enabled=initialized&&!busy&&!capturedPost.preview,modifier=Modifier.fillMaxWidth().testTag("Save local copy")){Text(if(busy)"Saving…"else "Save to library")}}
        }
    }
    if(create)CreateLibraryCollection(repo,onCreated={collectionIds=collectionIds+it;create=false},onDismiss={create=false})
}

@Composable internal fun FlowCollectionChoices(collections: List<LibraryCollectionRow>,selected: Set<String>,enabled: Boolean=true,onChange: (Set<String>)->Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        item {FilterChip(selected=selected.isEmpty(),onClick={onChange(emptySet())},enabled=enabled,label={Text("Unsorted")},modifier=Modifier.testTag("Save collection unsorted"))}
        items(collections,key={it.id}) {collection->FilterChip(selected=collection.id in selected,onClick={onChange(if(collection.id in selected)selected-collection.id else selected+collection.id)},enabled=enabled,label={Text(collection.name,maxLines=1)},modifier=Modifier.testTag("Save collection ${collection.id}"))}
    }
}

@Composable internal fun CreateLibraryCollection(repo: Repository,onCreated: (String)->Unit,onDismiss: ()->Unit) {
    var name by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("New collection")},text={Column {
        OutlinedTextField(name,{name=it.take(80);error=null},label={Text("Name")},placeholder={Text("Projects, Recipes…")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("New collection name"))
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
    }},confirmButton={
        val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
        TextButton(onClick={busy=true;scope.launch {try {val id=repo.library.createCollection(name.trim());withContext(Dispatchers.Main.immediate){focus.clearFocus();keyboard?.hide();onCreated(id)}}catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "Collection could not be created.";busy=false}}},enabled=!busy&&name.isNotBlank(),modifier=Modifier.testTag("Create collection")){Text("Create")}
    },dismissButton={TextButton(onClick=onDismiss,enabled=!busy){Text("Cancel")}})
}
