package app.antisocial.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.antisocial.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun DiscussionSheet(vm: AntiSocialViewModel,reader: DiscussionReader=vm.discussions) {
    val state by reader.state.collectAsState()
    val current=state ?: return
    val community by reader.community.state.collectAsState()
    var saveCopy by remember(current.posts){mutableStateOf(false)}
    var captured by remember {mutableStateOf<List<CapturedThread>>(emptyList())}
    var capturedEditions by remember {mutableStateOf<List<Post>>(emptyList())}
    var saveRoot by remember {mutableStateOf(current.posts.first())}
    val context=androidx.compose.ui.platform.LocalContext.current
    val outgoing=rememberOutgoingActions(vm.repo)
    val scrollPositions=remember(current.posts){mutableMapOf<String,LazyListState>()}
    val platforms=remember(current.posts){current.posts.map {it.platform}.distinct()}
    val tabPositions=remember(current.posts){(listOf(COMMENT_ANYWHERE,"all")+platforms.map {it.id}).associateWith {BringIntoViewRequester()}}
    val selected=if(current.selected=="all")current.posts else current.posts.filter {it.platform.id==current.selected}
    val threads=selected.mapNotNull(current::thread)
    val comments=remember(current,reader.communityEnabled){discussionEntries(current,reader.communityEnabled)}
    var sourceDetails by remember(current.posts,current.order){mutableStateOf(false)}
    ModalBottomSheet(onDismissRequest={reader.close()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.background,dragHandle=null,modifier=Modifier.fillMaxHeight(.94f).testTag("Public discussions")) {
        val composing=current.selected in setOf("all",COMMENT_ANYWHERE)&&WindowInsets.isImeVisible
        LaunchedEffect(current.selected,composing){if(!composing){withFrameNanos{};tabPositions[current.selected]?.bringIntoView()}}
        Column(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical=10.dp).size(32.dp,4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.5f),RoundedCornerShape(3.dp)))
            Row(Modifier.padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text(if(composing)"Join the discussion"else "Public discussions",fontSize=if(composing)18.sp else 22.sp,fontWeight=FontWeight.Bold);if(!composing)current.posts.first().let {root->MentionText(root.title.ifBlank {root.body}.ifBlank {"Public post"},vm.repo,root.platform,root.canonicalUrl,explicitProfileLinks=root.links.orEmpty(),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                IconButton(onClick={
                    saveRoot=selected.firstOrNull() ?: current.posts.first()
                    captured=captureLoadedThreads(vm,current.posts,reader)
                    capturedEditions=current.posts.takeIf {CrossPostFusion.compose(it)!=null}.orEmpty()
                    saveCopy=true
                },enabled=current.threads.values.any {it.page!=null}||current.communities.values.any {it.page!=null}||community?.page!=null,modifier=Modifier.testTag("Save loaded discussion")){Icon(Icons.Rounded.Download,"Save loaded discussion")}
                IconButton(onClick={reader.close()}){Icon(Icons.Rounded.Close,"Close discussions")}
            }
            if(!composing) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=current.selected=="all",onClick={reader.select("all")},label={Text("All")},leadingIcon={Icon(Icons.Rounded.Layers,null,Modifier.size(20.dp))},modifier=Modifier.bringIntoViewRequester(tabPositions.getValue("all")).testTag("Discussion tab all"))
                FilterChip(selected=current.selected==COMMENT_ANYWHERE,onClick={reader.select(COMMENT_ANYWHERE)},label={Icon(Icons.Rounded.Forum,null,Modifier.size(26.dp))},modifier=Modifier.semantics {contentDescription="Community comments"}.bringIntoViewRequester(tabPositions.getValue(COMMENT_ANYWHERE)).testTag("Discussion tab commentanywhere"))
                platforms.forEach {platform->FilterChip(selected=current.selected==platform.id,onClick={reader.select(platform.id)},label={PlatformMark(platform,26.dp)},modifier=Modifier.bringIntoViewRequester(tabPositions.getValue(platform.id)).testTag("Discussion tab ${platform.id}"))}
            }
            Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ReadingOrder.entries.forEach {order->FilterChip(selected=current.order==order,onClick={reader.order(order)},label={Text(order.label)},leadingIcon={Icon(if(order==ReadingOrder.CHRONOLOGICAL)Icons.Rounded.Schedule else Icons.Rounded.Whatshot,null,Modifier.size(16.dp))},modifier=Modifier.testTag("Discussion order ${order.name}"))}
            }
            }
            if(current.selected==COMMENT_ANYWHERE)AnywherePane(reader,Modifier.weight(1f).fillMaxWidth(),repo=vm.repo,onSettings={reader.close();vm.settings(SettingsPage.COMMENTS)})else {
            Text(when {
                current.selected=="all"&&current.order==ReadingOrder.CHRONOLOGICAL->"Newest conversations first · reply trees stay together"
                current.order==ReadingOrder.CHRONOLOGICAL->"Newest replies first · undated replies last"
                current.selected=="all"->"Source rankings, mixed across discussions"
                selected.firstOrNull()?.platform==Platform.REDDIT->"Reddit’s public top order."
                selected.firstOrNull()?.platform==Platform.X->"X’s relevance order, where the public source supports it."
                selected.firstOrNull()?.platform==Platform.FACEBOOK->"Facebook’s public ranked order, where the source supplies it."
                selected.firstOrNull()?.platform==Platform.LEMMY->"Lemmy’s public top order."
                else->"Source order. This reader does not expose a popularity ranking."
            },Modifier.padding(horizontal=20.dp,vertical=6.dp),fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(selected.size>platforms.size||current.selected!="all"&&selected.size>1)Text("${selected.size} originals · their replies stay linked to their own post",Modifier.padding(horizontal=20.dp,vertical=2.dp).testTag("Discussion original count"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
            key(current.selected,current.order) {
                val commentList=scrollPositions.getOrPut(current.selected+current.order.name){LazyListState()}
                ArchiveListViewport(commentList) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("Discussion comments"),state=commentList,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(current.selected=="all") {
                        item("source-summary") {
                            AllDiscussionSummary(current,comments.size,reader.communityEnabled,sourceDetails,{sourceDetails=!sourceDetails})
                        }
                        if(sourceDetails) {
                            threads.forEach {thread->item("status:${current.threadKey(thread.post)}") {
                                PlatformDiscussionStatus(thread,reader,vm.repo,current,showRanking=current.order==ReadingOrder.SLOP)
                            }}
                            if(reader.communityEnabled)current.posts.forEach {root->item("community-status:${current.threadKey(root)}") {
                                SharedDiscussionStatus(root,current.shared(root),reader,vm.repo,current)
                            }}
                        }
                        if(comments.isEmpty())item("discussion-empty") {
                            AllDiscussionEmpty(current,reader.communityEnabled)
                        }
                    }else threads.forEach {thread->item("status:${current.threadKey(thread.post)}") {
                        PlatformDiscussionStatus(thread,reader,vm.repo,current)
                    }}
                    items(comments,key={it.key}) {entry->
                        if(entry.community!=null) {
                            Column {
                                if(entry.depth==0)Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(start=4.dp,bottom=5.dp)) {Icon(Icons.Rounded.Forum,"Community comment",Modifier.size(13.dp));Spacer(Modifier.width(5.dp));PlatformMark(entry.root.platform,13.dp);Text("Shared discussion",Modifier.padding(start=5.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                AnywhereCommentCard(entry.community,entry.depth,repo=vm.repo,onReply={reader.reply(entry.root,entry.community)})
                            }
                        }else {
                        val comment=requireNotNull(entry.post)
                        DisposableEffect(vm.repo.key(comment)){vm.repo.lease(vm.repo.key(comment),true);onDispose {vm.repo.lease(vm.repo.key(comment),false)}}
                        Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {PlatformMark(comment.platform,16.dp);MentionText(comment.author.ifBlank {"Public reply"},vm.repo,comment.platform,comment.canonicalUrl,accountLabel=true,explicitProfileLinks=comment.links.orEmpty(),modifier=Modifier.weight(1f).padding(start=8.dp),fontWeight=FontWeight.SemiBold,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis);comment.score?.let {Text("↑ $it",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                                if(comment.title.isNotBlank())MentionText(comment.title,vm.repo,comment.platform,comment.canonicalUrl,explicitProfileLinks=comment.links.orEmpty(),fontWeight=FontWeight.Medium,fontSize=14.sp,modifier=Modifier.padding(top=8.dp))
                                MentionText(comment.body.ifBlank {if(comment.media.isNotEmpty())"Media reply"else "Reply text unavailable"},vm.repo,comment.platform,comment.canonicalUrl,explicitProfileLinks=comment.links.orEmpty(),fontSize=14.sp,lineHeight=21.sp,modifier=Modifier.padding(top=6.dp))
                                comment.media.firstOrNull()?.let {media->val image=if(media.type=="image")media.url else media.preview;if(image!=null)RemoteImage(image,comment.canonicalUrl,vm.repo,Modifier.fillMaxWidth().heightIn(max=200.dp).aspectRatio(1.5f).padding(top=8.dp),media.description,maxEdge=768)}
                                Row(Modifier.fillMaxWidth().padding(top=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                    val original=if(current.posts.count {it.platform==entry.root.platform}>1)current.originalLabel(entry.root)+" · "else ""
                                    Text(original+(comment.publishedAt?.let {DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))} ?: "Date not supplied"),Modifier.weight(1f),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    IconButton(onClick={outgoing.copy(comment)},modifier=Modifier.size(32.dp).testTag("Copy reply link ${vm.repo.key(comment)}")){Icon(Icons.Rounded.Link,"Copy original reply link",Modifier.size(17.dp))}
                                }
                            }
                        }
                        }
                    }
                    threads.filter {it.page?.next!=null}.forEach {thread->item("more:${current.threadKey(thread.post)}"){OutlinedButton(onClick={reader.more(current.threadKey(thread.post))},enabled=!thread.loading,modifier=Modifier.fillMaxWidth().testTag("More discussion ${current.threadKey(thread.post)}")){Text("More ${current.originalLabel(thread.post)} replies")}}}
                    if(comments.isNotEmpty())item {Text("Public replies can be incomplete, filtered or unavailable at the source.",fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))}
                }
            }
            }
            if(current.selected=="all"&&reader.communityEnabled)AnywhereComposer(reader,repo=vm.repo,onSettings={reader.close();vm.settings(SettingsPage.COMMENTS)},compact=true)
            }
        }
    }
    if(saveCopy)SaveCopySheet(vm.repo,saveRoot,captured,fusionRoots=capturedEditions,onSaved={saveCopy=false;android.widget.Toast.makeText(context,"Saved to your library",android.widget.Toast.LENGTH_SHORT).show()},onDismiss={saveCopy=false})
}


/** All keeps available conversations first; source diagnostics are one deliberate disclosure. */
@Composable private fun AllDiscussionSummary(state: DiscussionState,comments: Int,shared: Boolean,expanded: Boolean,onExpand: ()->Unit) {
    val native=state.posts.map(state::thread)
    val community=if(shared)state.posts.map(state::shared)else emptyList()
    val nativeFailed=native.count {it?.error!=null}
    val sharedFailed=community.count {it?.error!=null}
    Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f),modifier=Modifier.fillMaxWidth().testTag("Discussion source summary")) {
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=4.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(comments>0)"$comments ${if(comments==1)"reply"else "replies"} loaded"else "Discussion sources",Modifier.weight(1f).padding(vertical=5.dp),fontSize=12.sp,fontWeight=FontWeight.Medium)
            if(nativeFailed+sharedFailed>0)Icon(Icons.Rounded.CloudOff,"Some discussion sources are unavailable",Modifier.size(15.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick=onExpand,contentPadding=PaddingValues(horizontal=9.dp),modifier=Modifier.testTag("Discussion source details")) {
                Text(if(expanded)"Hide details"else "Source details",fontSize=11.sp)
                Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null,Modifier.size(16.dp))
            }
        }
    }
}

@Composable private fun AllDiscussionEmpty(state: DiscussionState,shared: Boolean) {
    val native=state.posts.map(state::thread)
    val community=if(shared)state.posts.map(state::shared)else emptyList()
    val pending=native.any {it==null||it.loading||it.page==null&&it.error==null}||community.any {it==null||it.loading||it.page==null&&it.error==null}
    val failed=native.any {it?.error!=null}||community.any {it?.error!=null}
    Column(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=14.dp).testTag("Discussion empty state"),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(when {pending->"Reading public replies…";failed->"Public replies are unavailable";else->"No public replies were supplied"},fontSize=15.sp,fontWeight=FontWeight.SemiBold)
        Text(when {
            pending->"Available replies appear here as each discussion arrives."
            failed->"Some discussions could not be read. Open Source details for each result or try its platform tab. This does not confirm that the post has no comments."
            else->"This does not confirm that the post has no comments. Public sources can supply only part of a discussion."
        },fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun PlatformDiscussionStatus(thread: ThreadRead,reader: DiscussionReader,repo: Repository,state: DiscussionState,showRanking: Boolean=false) {
    val available=thread.page?.let {Discussions.comments(thread.post,it).size} ?: 0
    val key=state.threadKey(thread.post)
    Column(Modifier.fillMaxWidth().testTag("Discussion platform status $key")) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            PlatformMark(thread.post.platform,18.dp)
            Text("${state.originalLabel(thread.post)} · "+when {
                thread.loading->"Reading public replies…"
                thread.error!=null&&available>0->"$available previously loaded · refresh unavailable"
                thread.error!=null->"Public replies unavailable"
                thread.page==null->"Waiting to read"
                else->"$available replies supplied"
            },Modifier.weight(1f).padding(start=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!thread.loading)IconButton(onClick={reader.retry(key)},modifier=Modifier.size(40.dp)){Icon(Icons.Rounded.Refresh,"Refresh ${state.originalLabel(thread.post)} comments",Modifier.size(18.dp))}
        }
        if(thread.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        thread.error?.let {Text(repo.publicText(it,thread.post.platform),fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(!thread.loading&&thread.page!=null&&thread.error==null&&available==0)Text("No public replies were supplied. This does not confirm that the post has no comments.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        thread.page?.notice?.let {Text(repo.publicText(it,thread.post.platform),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(showRanking&&thread.post.platform !in setOf(Platform.X,Platform.REDDIT,Platform.FACEBOOK,Platform.LEMMY))Text("Source order · popularity ranking unavailable",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        thread.page?.provenance?.let {p->Text((if(p.cached)"Cached · "else "")+SourcePresentation.label(thread.post),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable private fun SharedDiscussionStatus(root: Post,entry: AnywhereThreadRead?,reader: DiscussionReader,repo: Repository,state: DiscussionState) {
    val key=state.threadKey(root)
    Row(Modifier.fillMaxWidth().testTag("Discussion shared status $key"),verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.Rounded.Forum,"Community comments",Modifier.size(18.dp));Spacer(Modifier.width(6.dp));PlatformMark(root.platform,16.dp)
        Column(Modifier.weight(1f).padding(start=8.dp)) {
            Text("Shared discussion · ${state.originalLabel(root)}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(entry?.loading==true)LinearProgressIndicator(Modifier.fillMaxWidth())
            entry?.error?.let {Text(repo.publicText(it,root.platform),fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(entry?.page?.items?.isEmpty()==true&&entry.loading!=true&&entry.error==null)Text("No shared comments were returned for this original page.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(entry?.page!=null&&entry.error!=null)Text("Previously loaded shared comments remain available.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick={reader.retryCommunity(key)},enabled=entry?.loading!=true){Icon(Icons.Rounded.Refresh,"Refresh community ${state.originalLabel(root)} comments",Modifier.size(18.dp))}
    }
}
