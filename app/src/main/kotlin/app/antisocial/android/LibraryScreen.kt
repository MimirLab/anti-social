/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect

/** Library searches and capture readers resolve only local records and selected local files. */
@Composable internal fun LibraryScreen(repo: Repository,onOpenOriginal: (Post)->Unit,onStorage: ()->Unit={},onOpenLink:(String)->Unit={},onReadLink:(String)->Unit={},onReadEvent:((String,LocalEvent)->Unit)?=null) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var query by rememberSaveable {mutableStateOf("")}
    var filter by rememberSaveable {mutableStateOf("all")}
    var collectionId by rememberSaveable {mutableStateOf<String?>(null)}
    var selectedKey by rememberSaveable {mutableStateOf<String?>(null)}
    var selectedKind by rememberSaveable {mutableStateOf("saved")}
    var matched by remember {mutableStateOf<Post?>(null)}
    var create by remember {mutableStateOf(false)}
    var menu by remember {mutableStateOf(false)}
    var manageCollections by remember {mutableStateOf(false)}
    var removeCollection by remember {mutableStateOf<LibraryCollectionRow?>(null)}
    val collections by repo.library.collections.collectAsState(emptyList())
    val entriesFlow=remember(repo,query,filter,collectionId){if(filter in setOf("businesses","events"))flowOf(emptyList())else repo.library.entries(query,filter,collectionId)}
    val entries by entriesFlow.collectAsState(emptyList())
    val areas=remember(repo.prefs.localAreas){runCatching {LocalAreaCodec.decode(repo.prefs.localAreas)}.getOrDefault(emptyList())}
    fun eventArea(event:LocalEvent):LocalArea?=if(event.start.zoneId!=null)null else event.point?.let {point->
        areas.minByOrNull {it.point.distanceKm(point)}?.takeIf {it.point.distanceKm(point)<=it.radiusKm}
    }
    var localSelected by remember {mutableStateOf<LocalSavedDiscovery?>(null)}
    var flyer by remember {mutableStateOf<Pair<String,String>?>(null)}
    val discoveries by produceState<List<LocalSavedDiscovery>>(emptyList(),repo,query,filter,collectionId) {
        if(collectionId!=null||filter !in setOf("all","businesses","events"))return@produceState
        if(query.isNotBlank())delay(160)
        repo.localDiscovery.revision.collect {
            value=withContext(Dispatchers.IO){repo.localDiscovery.savedDiscoveries(query).filter {item->filter=="all"||filter=="businesses"&&item.business!=null||filter=="events"&&item.event!=null}}
        }
    }
    val cards=remember(entries,discoveries,query){(entries.map {LibraryCard(it.savedAt,post=it)}+discoveries.map {LibraryCard(it.savedAt,discovery=it)}).sortedWith(compareBy<LibraryCard>{if(query.isNotBlank()&&it.post?.relatedMatch==true)1 else 0}.thenByDescending {it.savedAt})}
    val revision by repo.library.revision.collectAsState()
    var selected by remember {mutableStateOf<LibraryEntry?>(null)}
    var loading by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val resultsList=rememberLazyListState()
    LaunchedEffect(selectedKey,selectedKind,revision) {
        val key=selectedKey
        if(key==null){selected=null;loading=false;return@LaunchedEffect}
        loading=true
        if(selected?.key!=key||selected?.kind!=selectedKind)selected=null
        selected=repo.library.copy(key,selectedKind)?.let {it.copy(matchedPost=matched)}
        loading=false
    }
    BackHandler(selectedKey!=null){selectedKey=null}
    if(selectedKey!=null) {
        when {selected!=null->LocalCopyReader(repo,selected!!,collections,onBack={selectedKey=null},onOpenOriginal=onOpenOriginal,onRemoved={selectedKey=null},onPromoted={selectedKind="saved";selectedKey=it})
            loading->LinearProgressIndicator(Modifier.fillMaxWidth())
            else->Column(Modifier.padding(20.dp)) {Text("This local copy is no longer available.");TextButton(onClick={selectedKey=null}){Text("Back to library")}}}
    }else Column(Modifier.fillMaxSize().testTag("Library screen")) {
        Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Your library",Modifier.weight(1f),fontSize=27.sp,fontWeight=FontWeight.Bold)
                if(filter !in setOf("businesses","events"))Box {
                    TextButton(onClick={menu=true},modifier=Modifier.testTag("Library collection picker")){Text(collections.firstOrNull {it.id==collectionId}?.name ?: "All collections",maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=140.dp));Icon(Icons.Rounded.ExpandMore,null,Modifier.size(18.dp))}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        DropdownMenuItem(text={Text("All collections")},onClick={collectionId=null;menu=false})
                        collections.forEach {collection->DropdownMenuItem(text={Text(collection.name)},onClick={collectionId=collection.id;filter="saved";menu=false})}
                        HorizontalDivider();DropdownMenuItem(text={Text("New collection")},leadingIcon={Icon(Icons.Rounded.Add,null)},onClick={create=true;menu=false})
                        if(collections.isNotEmpty())DropdownMenuItem(text={Text("Manage collections")},onClick={manageCollections=true;menu=false})
                    }
                }
            }
            OutlinedTextField(query,{query=it},label={Text("Search your library")},singleLine=true,leadingIcon={Icon(Icons.Rounded.Search,null)},trailingIcon={if(query.isNotEmpty())IconButton(onClick={query=""}){Icon(Icons.Rounded.Close,"Clear library search")}},modifier=Modifier.fillMaxWidth().testTag("Library search"))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("all" to "All","saved" to "Posts","businesses" to "Businesses","events" to "Events","history" to "History").forEach {(value,label)->FilterChip(selected=filter==value,onClick={filter=value;if(value in setOf("history","businesses","events"))collectionId=null},label={Text(label)},modifier=Modifier.testTag("Library scope $value"))}}
        }
        CompositionLocalProvider(LocalMentionUpdatesAllowed provides !resultsList.isScrollInProgress) {
        LazyColumn(Modifier.fillMaxSize().testTag("Library results"),state=resultsList,contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(cards.isEmpty())item {Column(Modifier.padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(if(query.isNotBlank())"No local matches"else if(filter=="history")"Retained reading history"else if(filter=="businesses")"Saved businesses"else if(filter=="events")"Saved events"else "Keep the good parts",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                Text(when {query.isNotBlank()->"Search saved posts, businesses, events and retained history.";filter=="history"&&!repo.prefs.historyFullText->"Full-text history is off. You can choose to retain the text of posts you actually read in Storage settings.";filter=="history"->"Posts you spend time reading will appear here. History expires automatically; saved copies stay until you remove them.";else->"Save posts, businesses and events as local copies. They stay useful here when a public source disappears."},fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(filter=="history"&&!repo.prefs.historyFullText)TextButton(onClick=onStorage){Text("History settings")}
            }}
            items(cards,key={card->card.post?.let {"${it.kind}:${it.key}"} ?: "discovery:${card.discovery!!.id}"}) {card->
                card.post?.let {entry->LibraryResultCard(repo,entry,onClick={focus.clearFocus();keyboard?.hide();matched=entry.matchedPost;selectedKind=entry.kind;selectedKey=entry.key},onRemove={scope.launch {repo.library.remove(entry.key)}})}
                card.discovery?.let {item->
                    val open={focus.clearFocus();keyboard?.hide();localSelected=item}
                    val remove={scope.launch {withContext(Dispatchers.IO){repo.localDiscovery.removeSaved(item.id)}};Unit}
                    item.business?.let {b->BusinessCard(b,null,repo.prefs.localDistanceUnit,true,onOpen=open,onSave=remove,onSocial=onOpenLink,activity={Text("Saved copy",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)})}
                    item.event?.let {event->EventCard(event,eventArea(event),true,onOpen=open,onSave=remove,activity={Text("Saved copy",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)})}
                }
            }
        }
        }
    }
    localSelected?.let {item->
        val remove={scope.launch {withContext(Dispatchers.IO){repo.localDiscovery.removeSaved(item.id)};localSelected=null};Unit}
        item.business?.let {b->BusinessDetails(b,null,repo.prefs.localDistanceUnit,true,menuLoading=false,onMenuRefresh=null,onDismiss={localSelected=null},onSave=remove,onSocial={url->localSelected=null;onOpenLink(url)},onRead={url->localSelected=null;onReadLink(url)},clean=repo.cleaner::clean,activity={CardActivity(repo.cardWork,businessWorkKeys(b),b.id)})}
        item.event?.let {event->EventDetails(event,eventArea(event),saved=true,onDismiss={localSelected=null},onSave=remove,onRead={url->localSelected=null;if(repo.routes.recognize(url)!=null)onOpenLink(url)else onReadEvent?.invoke(url,event) ?: onReadLink(url)},onImage={url,source->flyer=url to source},repo=repo,activity={CardActivity(repo.cardWork,eventWorkKeys(event),event.id)})}
    }
    flyer?.let {(url,source)->MediaViewer(listOf(Media(url)),0,source,repo){flyer=null}}
    if(create)CreateLibraryCollection(repo,onCreated={collectionId=it;filter="saved";create=false},onDismiss={create=false})
    if(manageCollections)AlertDialog(onDismissRequest={manageCollections=false},title={Text("Collections")},text={LazyColumn(Modifier.heightIn(max=340.dp)) {items(collections,key={it.id}) {collection->Row(verticalAlignment=Alignment.CenterVertically) {Text(collection.name,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis);IconButton(onClick={removeCollection=collection}){Icon(Icons.Rounded.DeleteOutline,"Delete collection ${collection.name}")}}}}},confirmButton={TextButton(onClick={manageCollections=false}){Text("Done")}})
    removeCollection?.let {collection->AlertDialog(onDismissRequest={removeCollection=null},title={Text("Delete ${collection.name}?")},text={Text("Saved copies stay in your library. Only this collection is removed.")},confirmButton={TextButton(onClick={scope.launch {repo.library.deleteCollection(collection.id);if(collectionId==collection.id)collectionId=null;removeCollection=null}}){Text("Delete collection")}},dismissButton={TextButton(onClick={removeCollection=null}){Text("Cancel")}})}
}

private data class LibraryCard(val savedAt:Long,val post:LibraryEntry?=null,val discovery:LocalSavedDiscovery?=null)

@Composable private fun LibraryResultCard(repo: Repository,entry: LibraryEntry,onClick: ()->Unit,onRemove: ()->Unit) {
    val text=remember(entry.post){PostPresentation.text(entry.post)}
    Surface(shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().clickable(onClick=onClick).testTag("Library entry ${entry.key}")) {
        Column(Modifier.padding(16.dp).testTag("Library entry ${entry.kind}:${entry.key}"),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {PlatformMark(entry.post.platform,22.dp);MentionText(entry.post.author.ifBlank {entry.post.platform.label},repo,entry.post.platform,entry.post.canonicalUrl,accountLabel=true,authorAccount=entry.post.authorAccount,networkAllowed=false,explicitProfileLinks=entry.post.links.orEmpty(),modifier=Modifier.weight(1f).padding(start=9.dp),fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(entry.kind=="saved")"Saved copy"else "History",fontSize=11.sp,color=MaterialTheme.colorScheme.primary);if(entry.kind=="saved")IconButton(onClick=onRemove){Icon(Icons.Rounded.Bookmark,"Remove saved post",tint=MaterialTheme.colorScheme.primary)}}
            if(text.title.isNotBlank())MentionText(text.title,repo,entry.post.platform,entry.post.canonicalUrl,networkAllowed=false,explicitProfileLinks=entry.post.links.orEmpty(),fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(text.body.isNotBlank())MentionText(text.body,repo,entry.post.platform,entry.post.canonicalUrl,networkAllowed=false,explicitProfileLinks=entry.post.links.orEmpty(),fontSize=14.sp,lineHeight=21.sp,maxLines=4,overflow=TextOverflow.Ellipsis)
            entry.matchedPost?.takeIf {it!=entry.post}?.let {match->MentionText("Matched saved text · "+match.body,repo,match.platform,match.canonicalUrl,networkAllowed=false,explicitProfileLinks=match.links.orEmpty(),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)}
            RecoveredArchiveLabel(entry.post)
            if(entry.collections.isNotEmpty())Text(entry.collections.joinToString(" · "){it.name},fontSize=12.sp,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text((if(entry.kind=="saved")assetSummary(entry.assets)else "Retained locally · "+libraryDate(entry.savedAt))+(if(entry.relatedMatch)" · Related wording"else ""),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun LocalCopyReader(repo: Repository,entry: LibraryEntry,collections: List<LibraryCollectionRow>,onBack: ()->Unit,onOpenOriginal: (Post)->Unit,onRemoved: ()->Unit,onPromoted: (String)->Unit) {
    val scope=rememberCoroutineScope()
    val outgoing=rememberOutgoingActions(repo)
    var removing by remember {mutableStateOf(false)}
    var editCollections by remember {mutableStateOf(false)}
    var create by remember {mutableStateOf(false)}
    var selectedCollections by remember(entry.collections){mutableStateOf(entry.collections.map {it.id}.toSet())}
    var showingArchive by remember(entry.key){mutableStateOf(false)}
    var selectedPlatform by remember(entry.key){mutableStateOf(entry.matchedPost?.platform?.id ?: "all")}
    var image by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var saveHistory by remember(entry.key){mutableStateOf(false)}
    val fusionRoots=remember(entry.post,entry.details){runCatching {repo.library.normalizedFusionRoots(entry.post,entry.details?.fusionRoots.orEmpty())}.getOrDefault(emptyList())}
    fun editionKey(post: Post)=if(fusionRoots.count {it.platform==post.platform}>1)repo.key(post)else post.platform.id
    var selectedEdition by remember(entry.key,entry.matchedPost){mutableStateOf(entry.matchedPost?.let {matched->fusionRoots.firstOrNull {repo.key(it)==repo.key(matched)}?.let(::editionKey)} ?: "all")}
    val sourceEdition=fusionRoots.firstOrNull {editionKey(it)==selectedEdition}
        ?: if(selectedEdition=="all"&&hasRepeatedOriginals(fusionRoots))CrossPosts.representative(fusionRoots,entry.post.platform) ?: entry.post else entry.post
    val version=sourceEdition.archivedVersion
    val displayed=if(showingArchive&&version!=null)version.displayPost(sourceEdition)else sourceEdition
    val presentationRoots=if(selectedEdition=="all"&&!showingArchive&&fusionRoots.isNotEmpty())fusionRoots else listOf(displayed)
    // Offline editions use their captured observations, never a newer live URL's bytes.
    val plan=remember(presentationRoots){collectionPlan(presentationRoots)}
    val fusion=plan.fusion
    val attachments=plan.attachments
    val mediaGroups=remember(attachments,plan.originals){collectedMediaGroups(attachments,CrossPosts.relatedVisualUpdates(plan.originals))}
    val shownMedia=remember(attachments){attachments.map {it.media}}
    val caption=if(selectedEdition=="all"&&!showingArchive&&fusion==null)collectedCaptionPost(fusionRoots) ?: displayed else displayed
    val body=remember(caption){PostPresentation.text(caption)}
    val selectedUrls=entry.details?.selectedAssets.orEmpty().map {it.url}.toSet()
    val threads=entry.details?.threads.orEmpty()
    val shownThreads=if(selectedPlatform=="all")threads else threads.filter {it.root.platform.id==selectedPlatform}
    val list=rememberLazyListState()
    Column(Modifier.fillMaxSize().testTag("Local copy reader")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back to library")}
            Column(Modifier.weight(1f)){Text(if(entry.kind=="saved")"Saved copy"else "Retained history",fontSize=20.sp,fontWeight=FontWeight.SemiBold);Text("On this device · "+libraryDate(entry.details?.capturedAt ?: entry.savedAt),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(entry.kind=="saved")IconButton(onClick={removing=true}){Icon(Icons.Rounded.DeleteOutline,"Remove local copy")}
        }
        CompositionLocalProvider(LocalMentionUpdatesAllowed provides !list.isScrollInProgress) {
        LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("Local copy content"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(fusionRoots.size>1)item {Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Source editions",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.testTag("Local source editions")) {
                    item {FilterChip(selectedEdition=="all",{selectedEdition="all";showingArchive=false},label={Text("Combined")},modifier=Modifier.testTag("Local source edition all"))}
                    items(fusionRoots,key={repo.key(it)}) {edition->FilterChip(selectedEdition==editionKey(edition),{selectedEdition=editionKey(edition);showingArchive=false},label={Row(verticalAlignment=Alignment.CenterVertically){PlatformMark(edition.platform,22.dp);if(fusionRoots.count {it.platform==edition.platform}>1)Text(" ${fusionRoots.filter {it.platform==edition.platform}.indexOf(edition)+1}",fontSize=12.sp)}},modifier=Modifier.testTag("Local source edition ${editionKey(edition)}"))}
                }
            }}
            item {Row(verticalAlignment=Alignment.CenterVertically) {PlatformMark(displayed.platform,25.dp);Column(Modifier.weight(1f).padding(start=10.dp)){MentionText(displayed.author.ifBlank {displayed.platform.label},repo,displayed.platform,displayed.canonicalUrl,accountLabel=true,authorAccount=displayed.authorAccount,networkAllowed=false,explicitProfileLinks=displayed.links.orEmpty(),fontWeight=FontWeight.SemiBold);Text(displayed.publishedAt?.let(::libraryDate) ?: "Post date not supplied",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};ArchiveVersionButton(version,showingArchive){showingArchive=!showingArchive}}}
            item {MediaInfoAction(repo,if(fusion!=null)fusionRoots else listOf(displayed),localOnly=true)}
            item {RecoveredArchiveLabel(displayed);ArchiveVersionLabel(version,showingArchive);if(fusion!=null)FusedPostText(fusion,roots=fusionRoots,repo=repo,networkAllowed=false)else {if(caption.author!=displayed.author)PostAttribution("Fuller original · ${caption.author}",Modifier.padding(bottom=8.dp),repo,caption.platform,caption.canonicalUrl,networkAllowed=false);body.attribution?.let {PostAttribution(it,Modifier.padding(bottom=8.dp),repo,caption.platform,caption.canonicalUrl,networkAllowed=false)};if(body.title.isNotBlank())MentionText(body.title,repo,caption.platform,caption.canonicalUrl,networkAllowed=false,explicitProfileLinks=caption.links.orEmpty(),fontSize=20.sp,fontWeight=FontWeight.SemiBold);if(body.body.isNotBlank())PostBodyText(body.body,repo,caption.platform,tag="Local post body",sourceUrl=caption.canonicalUrl,networkAllowed=false,explicitProfileLinks=caption.links.orEmpty())}}
            if(displayed.preview)item {Text("Partial public content · this is the text that was available when saved.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(mediaGroups,key={"local-media:${it.primary.media.url}"}) {group->
                val selected=CollectedMediaChoice(group)
                val media=selected.media
                val owner=if(fusion==null)displayed else fusionRoots.firstOrNull {root->root.media.any {it.url==media.url}} ?: displayed
                if(fusionRoots.map {it.platform}.distinct().size>1)Text(selected.sourceLabel,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(media.url in selectedUrls)LocalCopyMedia(repo,media,owner,onImage={image=it})else Text(if(media.type=="image")"Photo not included in this copy"else "Video not included in this copy",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Original link",fontWeight=FontWeight.Medium,fontSize=13.sp)
                Text(repo.publicUrl(sourceEdition.canonicalUrl,sourceEdition.platform),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Local original URL"))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={onOpenOriginal(sourceEdition.copy(canonicalUrl=repo.publicUrl(sourceEdition.canonicalUrl,sourceEdition.platform)))},modifier=Modifier.testTag("Open original from local copy")){Icon(Icons.AutoMirrored.Rounded.OpenInNew,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("Open original")}
                    TextButton(onClick={outgoing.copy(sourceEdition,fusionRoots)},modifier=Modifier.testTag("Copy local original link")){Text("Copy link")}
                }
                if(entry.kind=="history")FilledTonalButton(onClick={saveHistory=true},modifier=Modifier.testTag("Save retained history copy")){Icon(Icons.Rounded.BookmarkBorder,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Save to library")}
                if(entry.kind=="saved")TextButton(onClick={editCollections=true},modifier=Modifier.testTag("Edit copy collections")){Icon(Icons.Rounded.FolderOpen,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(entry.collections.joinToString(" · "){it.name}.ifBlank {"Add to a collection"},maxLines=1,overflow=TextOverflow.Ellipsis)}
            }}
            if(entry.assets.isNotEmpty())item {Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text("Selected attachments",fontWeight=FontWeight.SemiBold)
                Text(assetSummary(entry.assets),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("Local asset summary"))
                if(entry.assets.any {it.status in setOf("QUEUED","FAILED","WAITING_WIFI","REMOVED","MISSING")})TextButton(onClick={scope.launch {try {repo.library.retry(entry.key)}catch(e: CancellationException){throw e}catch(e: Exception){error=e.message}}},modifier=Modifier.testTag("Retry copy attachments")){Text("Retry available downloads")}
                entry.assets.filter {it.status !in setOf("COMPLETE","QUEUED","DOWNLOADING")}.forEach {asset->Text(assetStatus(asset.status)+(asset.error?.takeIf {it.isNotBlank()}?.let {" · $it"} ?: ""),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }}
            if(threads.isNotEmpty()) {
                item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {Text("Captured public replies",fontSize=20.sp,fontWeight=FontWeight.SemiBold);Text("${threads.sumOf {it.count}} replies captured so far. Public threads may be incomplete; this view never fetches more.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {if(threads.size>1)item {FilterChip(selectedPlatform=="all",{selectedPlatform="all"},label={Text("All")})};items(threads.distinctBy {it.root.platform},key={repo.key(it.root)}) {thread->FilterChip(selectedPlatform==thread.root.platform.id,{selectedPlatform=thread.root.platform.id},label={PlatformMark(thread.root.platform,22.dp)})}}}}
                shownThreads.filter {it.commentAnywhere!=null}.forEach {thread->
                    item("community-title:${repo.key(thread.root)}") {Text("CommentAnywhere · ${thread.root.platform.label} · saved locally",fontWeight=FontWeight.Medium,fontSize=13.sp)}
                    items(thread.commentAnywhere!!.flattened(),key={"community:${repo.key(thread.root)}:${it.first.id}"}) {(comment,depth)->AnywhereCommentCard(comment,depth,repo=repo)}
                }
                items(shownThreads.flatMap {it.replies}.distinctBy(repo::key).sortedByDescending {it.publishedAt ?: Long.MIN_VALUE},key={"local-reply:${repo.key(it)}"}) {reply->Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {Row(verticalAlignment=Alignment.CenterVertically){PlatformMark(reply.platform,17.dp);MentionText(reply.author,repo,reply.platform,reply.canonicalUrl,accountLabel=true,authorAccount=reply.authorAccount,networkAllowed=false,explicitProfileLinks=reply.links.orEmpty(),modifier=Modifier.padding(start=8.dp),fontWeight=FontWeight.Medium,fontSize=13.sp)};MentionText(reply.body.ifBlank {reply.title},repo,reply.platform,reply.canonicalUrl,networkAllowed=false,explicitProfileLinks=reply.links.orEmpty(),fontSize=14.sp,lineHeight=21.sp);reply.media.filter {it.url in selectedUrls}.forEach {LocalCopyMedia(repo,it,reply,onImage={url->image=url})};Text(reply.publishedAt?.let(::libraryDate) ?: "Date not supplied",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            }
            error?.let {message->item {Text(message,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}}
        }
        }
    }
    image?.let {url->LocalCopyImageDialog(repo,url){image=null}}
    if(saveHistory)SaveCopySheet(repo,entry.post,onSaved={saveHistory=false;onPromoted(it)},onDismiss={saveHistory=false})
    if(removing)AlertDialog(onDismissRequest={removing=false},title={Text("Remove this copy?")},text={Text("Its saved text, captured replies and collection links will be removed. Other saved copies stay.")},confirmButton={TextButton(onClick={scope.launch {try {repo.library.remove(entry.key);onRemoved()}catch(e: CancellationException){throw e}catch(e: Exception){error=e.message;removing=false}}},modifier=Modifier.testTag("Confirm remove local copy")){Text("Remove")}},dismissButton={TextButton(onClick={removing=false}){Text("Cancel")}})
    if(editCollections)AlertDialog(onDismissRequest={editCollections=false},title={Text("Collections")},text={Column {FlowCollectionChoices(collections,selectedCollections){selectedCollections=it};TextButton(onClick={create=true}){Text("New collection")}}},confirmButton={TextButton(onClick={scope.launch {try {repo.library.setCollections(entry.key,selectedCollections);editCollections=false}catch(e: CancellationException){throw e}catch(e: Exception){error=e.message}}},modifier=Modifier.testTag("Save copy collections")){Text("Done")}},dismissButton={TextButton(onClick={editCollections=false}){Text("Cancel")}})
    if(create)CreateLibraryCollection(repo,onCreated={selectedCollections=selectedCollections+it;create=false},onDismiss={create=false})
}

@Composable private fun LocalCopyMedia(repo: Repository,media: Media,post: Post,onImage: (String)->Unit) {
    val revision by repo.library.revision.collectAsState()
    var file by remember(media.url){mutableStateOf<File?>(null)}
    LaunchedEffect(media.url,revision){file=repo.library.assetFile(media.url)}
    if(media.type=="image")LocalCopyImage(repo,file,media.description,Modifier.fillMaxWidth().heightIn(min=100.dp,max=420.dp).clickable(enabled=file!=null){onImage(media.url)})
    else VideoCard(media,post,repo,localOnly=true,localFile=file)
}

@Composable private fun LocalCopyImage(repo: Repository,file: File?,description: String,modifier: Modifier) {
    var bitmap by remember(file?.path,file?.lastModified()){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(file?.path,file?.lastModified()) {bitmap=file?.let {try {repo.images.loadLocal(it,1600)}catch(e: CancellationException){throw e}catch(_: Exception){null}}}
    if(bitmap!=null)Image(bitmap!!.asImageBitmap(),description,modifier,contentScale=ContentScale.Fit)
    else Surface(modifier=modifier,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant) {Box(Modifier.padding(22.dp),contentAlignment=Alignment.Center){Text("Image not downloaded",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
}

@Composable private fun LocalCopyImageDialog(repo: Repository,url: String,onDismiss: ()->Unit) {
    var file by remember(url){mutableStateOf<File?>(null)}
    LaunchedEffect(url){file=repo.library.assetFile(url)}
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {Column {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close saved image")}};LocalCopyImage(repo,file,"Saved image",Modifier.fillMaxWidth().weight(1f))}}
    }
}

private fun libraryDate(at: Long)=DateTimeFormatter.ofPattern("MMM d, uuuu · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
private fun assetStatus(status: String)=when(status) {"COMPLETE"->"Downloaded";"QUEUED"->"Waiting to download";"DOWNLOADING"->"Downloading";"WAITING_WIFI"->"Waiting for Wi-Fi";"UNSUPPORTED"->"Download unavailable from this source";"REMOVED"->"Video removed from this device";"MISSING"->"Attachment is not on this device";else->"Download unavailable"}
private fun assetSummary(assets: List<SavedAssetRow>): String {
    if(assets.isEmpty())return "Text saved on this device"
    val complete=assets.count {it.status=="COMPLETE"}
    return "$complete of ${assets.size} selected attachments downloaded"+if(assets.any {it.status=="DOWNLOADING"})" · downloading"else if(assets.any {it.status=="WAITING_WIFI"})" · waiting for Wi-Fi"else if(assets.any {it.status=="QUEUED"})" · queued"else ""
}
