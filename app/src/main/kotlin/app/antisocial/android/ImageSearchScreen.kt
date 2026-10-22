package app.antisocial.android

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable internal fun ImageSearchScreen(vm: AntiSocialViewModel,savedKeys: Set<String>) {
    val ui by vm.imageSearch.state.collectAsState()
    val feed=ui.feed
    val follows by vm.repo.follows.collectAsState(emptyList())
    val imageRevision by vm.repo.images.identities.revision.collectAsState()
    val groups=remember(feed.page.posts,follows,imageRevision){CrossPosts.group(feed.page.posts,follows,sameMedia=vm.repo.images.identities::same)}
    val list=rememberLazyListState()
    var sources by rememberSaveable {mutableStateOf(false)}
    var unavailable by rememberSaveable {mutableStateOf(false)}
    val entry=remember {MutableTransitionState(false).apply {targetState=true}}
    LaunchedEffect(list,ui.loading,ui.stopped,ui.error,feed.hasMore,feed.page.posts.size,feed.page.accounts.size,feed.engines) {
        if(!ui.loading&&!ui.stopped&&ui.error==null&&feed.hasMore) {
            snapshotFlow {val info=list.layoutInfo;info.totalItemsCount>0&&(info.visibleItemsInfo.lastOrNull()?.index ?: -1)>=info.totalItemsCount-4}
                .distinctUntilChanged().collect {if(it)vm.imageSearch.next()}
        }
    }
    AnimatedVisibility(entry,enter=slideInVertically(tween(320)){it}+fadeIn(tween(220)),modifier=Modifier.fillMaxSize()) {
        ArchiveListViewport(list) {
        LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("Image search feed"),contentPadding=PaddingValues(16.dp,16.dp,16.dp,28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="heading") {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    ui.image?.let {RemoteImage(it.url,ui.source,vm.repo,Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)),"Selected search image",maxEdge=128)}
                    Column(Modifier.weight(1f).padding(start=12.dp)) {
                        Text("Image matches",fontSize=25.sp,fontWeight=FontWeight.Bold)
                        Text("Public sources · search through Tor",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                    }
                }
                Text("Only the selected image is sent; file metadata is removed.",fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=12.dp))
            }
            if(feed.engines.isNotEmpty())item(key="sources") {
                Column {
                    TextButton(onClick={sources=!sources},contentPadding=PaddingValues(0.dp),modifier=Modifier.testTag("Image search sources")) {
                        val done=feed.engines.count {it.stage in setOf(ImageSearchStage.READY,ImageSearchStage.EXHAUSTED)}
                        val failed=feed.engines.count {it.stage==ImageSearchStage.FAILED}
                        Text("${feed.engines.size} search sources · $done checked"+if(failed>0)" · $failed unavailable"else "")
                    }
                    AnimatedVisibility(sources) {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        feed.engines.forEach {source->
                            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().testTag("Image engine ${source.engine.id}")) {
                                Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(source.engine.label,fontSize=13.sp,fontWeight=FontWeight.Medium)
                                        Text(vm.repo.publicText(source.error ?: source.notice ?: when(source.stage) {
                                            ImageSearchStage.IDLE->"Waiting"
                                            ImageSearchStage.SEARCHING->"Searching…"
                                            ImageSearchStage.READY->"More matches available"
                                            ImageSearchStage.EXHAUSTED->"Search complete"
                                            ImageSearchStage.FAILED->"Unavailable"
                                        }),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
                                    }
                                    if(source.stage==ImageSearchStage.FAILED)IconButton(onClick={vm.imageSearch.retryEngine(source.engine)},enabled=!ui.loading){Icon(Icons.Rounded.Refresh,"Retry ${source.engine.label}")}
                                }
                            }
                        }
                    }}
                }
            }
            if(feed.page.accounts.isNotEmpty()) {
                item(key="accounts-heading"){Text("Accounts",fontWeight=FontWeight.SemiBold,fontSize=15.sp)}
                items(feed.page.accounts,key={"account:${it.target.platform.id}:${it.target.canonicalUrl}"}) {profile->
                    ImageMatchArrival(profile.target.canonicalUrl) {ImageAccountCard(profile,vm.repo){vm.open(profile.target)}}
                }
            }
            items(groups,key={"post:${vm.repo.key(it.primary)}"}) {group->
                ImageMatchArrival(vm.repo.key(group.primary)) {
                    Box(Modifier.testTag("Image result ${vm.repo.key(group.primary)}")) {
                        PostCard(group.primary,vm,vm.repo.key(group.primary) in savedKeys,group.posts){vm.repo.routes.recognize(group.primary.canonicalUrl)?.let {vm.open(it)}}
                    }
                }
            }
            if(ui.loading)item(key="loading") {
                Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                    Text(ui.message.ifBlank {"Finding public matches…"},Modifier.weight(1f).padding(start=12.dp),fontSize=13.sp)
                    TextButton(onClick={vm.imageSearch.stop()}){Text("Stop")}
                }
            }
            if(ui.error!=null)item(key="error") {
                Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.errorContainer) {Column(Modifier.padding(16.dp)) {
                    Text("Image search is unavailable",fontWeight=FontWeight.SemiBold)
                    Text(vm.repo.publicText(ui.error!!),fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
                    TextButton(onClick={vm.imageSearch.retry()}){Text("Retry search")}
                }}
            }
            if(feed.unresolved.isNotEmpty())item(key="unreadable") {
                Column {
                    TextButton(onClick={unavailable=!unavailable},modifier=Modifier.testTag("Unreadable image matches")){Text("${feed.unavailable} matched links couldn't be read")}
                    AnimatedVisibility(unavailable) {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        feed.unresolved.forEach {match->Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(match.target.platform.label+" · "+match.target.identity,fontWeight=FontWeight.Medium,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(match.error,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3)
                                Row {
                                    TextButton(onClick={vm.open(match.target)}){Text("Open in anti social")}
                                    TextButton(onClick={vm.imageSearch.retryMatch(match.target)},enabled=!ui.loading){Text("Retry")}
                                }
                            }
                        }}
                    }}
                }
            }
            if(!ui.loading&&ui.error==null)item(key="end") {
                Column(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    if(feed.error!=null)Text(vm.repo.publicText(feed.error!!),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=8.dp))
                    if(groups.isEmpty()&&feed.page.accounts.isEmpty()) {
                        Icon(Icons.Rounded.ImageSearch,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(ui.stopped)"Search paused"else "No readable social matches yet",fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=10.dp))
                        Text("Coverage depends on what public search sources have indexed and what anti social can read.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
                    }else if(!feed.hasMore)Text(if(feed.limited)"This search has reached its limit"else "End of available matches",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(feed.hasMore||ui.stopped)TextButton(onClick={vm.imageSearch.retry()},modifier=Modifier.testTag("More image matches")){Text(if(ui.stopped)"Continue searching"else "Find more matches")}
                }
            }
        }
        }
    }
}

@Composable private fun ImageMatchArrival(key: String,content: @Composable ()->Unit) {
    val entry=remember(key){MutableTransitionState(false).apply {targetState=true}}
    AnimatedVisibility(entry,enter=fadeIn(tween(260))+slideInVertically(tween(300)){it/5}){content()}
}

@Composable private fun ImageAccountCard(profile: Profile,repo: Repository,onOpen: ()->Unit) {
    Surface(onClick=onOpen,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f)),modifier=Modifier.fillMaxWidth().testTag("Image account ${profile.target.platform.id}:${profile.target.identity}")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                PlatformMark(profile.target.platform,32.dp)
                Column(Modifier.weight(1f).padding(start=12.dp)) {
                    MentionText(profile.name,repo,profile.target.platform,profile.target.canonicalUrl,accountLabel=true,explicitProfileLinks=profile.links.orEmpty(),fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(profile.target.platform.label,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            if(profile.description.isNotBlank())MentionText(profile.description,repo,profile.target.platform,profile.target.canonicalUrl,explicitProfileLinks=profile.links.orEmpty(),fontSize=13.sp,maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=10.dp))
        }
    }
}
