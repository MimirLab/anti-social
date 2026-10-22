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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
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
import app.antisocial.core.Target
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable internal fun SearchFeedScreen(vm: AntiSocialViewModel,savedKeys: Set<String>) {
    val state by vm.screen.collectAsState()
    val target=state.target ?: return
    val feed=state.searchFeed
    // Empty searches still carry source coverage and availability notices.
    val page=state.page ?: feed?.page
    val posts=page?.posts.orEmpty()
    val filtering=rememberReaderFilters(vm.repo,ContentFilterContext(FilterSurface.SEARCH,search=target.canonicalUrl))
    val known=LocalPostCounterparts.current
    val postGroups=remember(posts,known){posts.map {post->(listOf(post)+known[vm.repo.key(post)].orEmpty()).distinctBy(CrossPosts::originalKey)}}
    val assessments=rememberFilterAssessments(vm.repo,filtering,postGroups)
    val decisions=remember(postGroups,assessments){postGroups.associate {vm.repo.key(it.first()) to assessments.decision(it)}}
    val filtersPending=decisions.values.any {it==null}
    val allFiltered=posts.isNotEmpty()&&posts.all {decisions[vm.repo.key(it)]?.withheld==true&&vm.repo.key(it) !in filtering.revealed}
    val accounts=page?.accounts.orEmpty()
    val list=rememberLazyListState()
    val entry=remember(target){MutableTransitionState(false).apply {targetState=true}}
    LaunchedEffect(list,target,state.loading,feed?.hasMore,feed?.paused,feed?.revision,state.error,posts.size,accounts.size,allFiltered,filtersPending) {
        if(!filtersPending&&!allFiltered&&!state.loading&&state.error==null&&feed?.hasMore==true&&feed.paused!=true) {
            snapshotFlow {val info=list.layoutInfo;info.totalItemsCount>0&&(info.visibleItemsInfo.lastOrNull()?.index ?: -1)>=info.totalItemsCount-4}
                .distinctUntilChanged().collect {nearEnd->if(nearEnd)vm.loadMore()}
        }
    }
    LaunchedEffect(filtering.revealTarget) {
        val key=filtering.revealTarget ?: return@LaunchedEffect
        val displayed=posts.filter {decisions[vm.repo.key(it)]?.action!=ContentAction.HIDE||vm.repo.key(it) in filtering.revealed}
        val index=displayed.indexOfFirst {vm.repo.key(it)==key}
        if(index>=0){withFrameNanos {};withFrameNanos {};list.scrollToItem(1+(if(page?.provenance?.cached==true)1 else 0)+(if(page?.notice!=null)1 else 0)+(if(accounts.isNotEmpty())2 else 0)+index)}
        filtering.revealTarget=null
    }
    ReaderFilterDialogs(vm.repo,filtering,postGroups,assessments)
    AnimatedVisibility(entry,enter=slideInVertically(tween(320)){it}+fadeIn(tween(220)),exit=fadeOut(),modifier=Modifier.fillMaxSize()) {
        ArchiveListViewport(list) {
        LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("Search feed"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="search-heading") {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(target.identity,fontSize=22.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Row(Modifier.padding(top=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SearchScopeControl(target.platform,SearchScopes.from(target),{scope->vm.open(vm.repo.routes.search(target.platform,target.identity,scope),true)})
                            Text("Latest first",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                        }
                    }
                    FilterControls(vm.repo,filtering,postGroups,assessments,compact=true)
                    IconButton(onClick={vm.refresh()}){Icon(Icons.Rounded.Refresh,"Refresh search feed")}
                }
            }
            if(page?.provenance?.cached==true)item(key="saved-search") {Text("Showing saved results",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            page?.notice?.let {notice->item(key="search-notice"){Text(vm.repo.publicText(notice),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
            if(accounts.isNotEmpty()) {
                item(key="people-heading"){Text(searchDiscoveryHeading(target.platform,accounts),fontWeight=FontWeight.SemiBold,fontSize=15.sp,modifier=Modifier.testTag("Search discovery heading"))}
                item(key="people") {
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.testTag("Search people")) {
                        items(accounts,key={"${it.target.kind}:"+it.target.canonicalUrl}) {profile->
                            SearchArrival("destination:${profile.target.kind}:"+profile.target.canonicalUrl) {SearchAccount(profile,vm)}
                        }
                    }
                }
            }
            items(posts.filter {decisions[vm.repo.key(it)]?.action!=ContentAction.HIDE||vm.repo.key(it) in filtering.revealed},key={"post:"+vm.repo.key(it)}) {post->
                SearchArrival(vm.repo.key(post)) {
                    Box(Modifier.testTag("Search post ${post.id}")) {
                        if(decisions[vm.repo.key(post)]==null)PendingFilterCard()else FilteredPost(decisions[vm.repo.key(post)]!!,vm.repo.key(post),filtering,onEdit={filtering.managing=true}) {
                            PostCard(post,vm,vm.repo.key(post) in savedKeys,onFilter={filtering.candidate=(listOf(post)+known[vm.repo.key(post)].orEmpty()).distinctBy(CrossPosts::originalKey)}){vm.repo.routes.recognize(post.canonicalUrl)?.let {vm.open(it)}}
                        }
                    }
                }
            }
            if(allFiltered&&!state.loading&&feed?.hasMore==true&&feed.paused!=true&&state.error==null)item(key="all-filtered") {
                Column(Modifier.padding(16.dp)) {
                    Text("This batch is filtered",fontWeight=FontWeight.SemiBold)
                    Text("Review the fetched posts or choose to search further.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick={vm.loadMore()},modifier=Modifier.testTag("Continue filtered search")){Text("Fetch another batch")}
                }
            }
            if(state.loading) {
                if(posts.isEmpty()&&accounts.isEmpty())items(3,key={"placeholder:$it"}) {SearchPlaceholder()}
                item(key="search-loading") {
                    Row(Modifier.fillMaxWidth().padding(vertical=12.dp).testTag("Search loading"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                        Text("Loading posts…",Modifier.padding(start=12.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick={vm.stopLoading()}){Text("Stop")}
                    }
                }
            }else if(feed?.paused==true) {
                item(key="search-paused") {
                    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.testTag("Search paused")) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Search paused",fontWeight=FontWeight.SemiBold)
                            Text("The results already loaded stay here. Continue when you're ready.",fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
                            TextButton(onClick={vm.loadMore()}){Text("Continue search")}
                        }
                    }
                }
            }else if(state.error!=null) {
                item(key="search-error") {
                    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.testTag("Search error")) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if(posts.isEmpty()&&accounts.isEmpty())"Search is unavailable"else "Couldn't load more results",fontWeight=FontWeight.SemiBold)
                            Text(vm.repo.publicText(state.error.orEmpty()),fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
                            Row {
                                TextButton(onClick={if(feed?.hasMore==true)vm.loadMore()else vm.refresh()}){Text("Retry")}
                                TextButton(onClick={vm.section("pools")}){Text("Inspect sources")}
                            }
                        }
                    }
                }
            }else if(feed?.hasMore==false) {
                item(key="search-end") {
                    Column(Modifier.fillMaxWidth().padding(vertical=24.dp).testTag("Search end"),horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Search,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        val heading=when {
                            posts.isNotEmpty()->if(feed.limited)"You've reached this search's limit"else "You're caught up"
                            accounts.isNotEmpty()->"No readable posts returned"
                            feed.unavailable>0->"Public results couldn't be read"
                            feed.limited->"Search coverage is limited"
                            else->"No readable public results"
                        }
                        val detail=when {
                            feed.unavailable>0&&posts.isEmpty()&&accounts.isEmpty()->"The public sources found for this search were unavailable or didn't expose readable content."
                            feed.unavailable>0->"Some results couldn't be read from public sources."
                            accounts.isNotEmpty()&&posts.isEmpty()->when {
                                accounts.all {it.target.kind==TargetKind.COMMUNITY}->"Public communities are shown above. Try another search for posts."
                                accounts.any {it.target.kind==TargetKind.COMMUNITY}->"Public profiles and communities are shown above. Try another search for posts."
                                target.platform==Platform.TELEGRAM->"Public channels are shown above. Try another search for posts."
                                else->"Public profiles are shown above. Try another search for posts."
                            }
                            feed.limited->"Try another search or inspect sources for more coverage."
                            else->"Try another search to discover more."
                        }
                        Text(heading,fontSize=14.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=10.dp))
                        Text(detail,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
                        if(feed.unavailable>0||feed.limited)TextButton(onClick={vm.section("pools")}){Text("Inspect sources")}
                    }
                }
            }
        }
        }
    }
}

@Composable private fun SearchArrival(identity: String,content: @Composable ()->Unit) {
    var revealed by rememberSaveable(identity){mutableStateOf(false)}
    val entry=remember(identity){MutableTransitionState(revealed).apply {targetState=true}}
    LaunchedEffect(identity){revealed=true}
    AnimatedVisibility(entry,enter=fadeIn(tween(260))+slideInVertically(tween(300)){it/5},exit=fadeOut()) {content()}
}

@Composable private fun SearchAccount(profile: Profile,vm: AntiSocialViewModel) {
    Surface(onClick={vm.open(profile.target)},shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f)),modifier=Modifier.width(272.dp).testTag("Search account ${profile.target.identity}")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                if(profile.avatar!=null)RemoteImage(profile.avatar!!,profile.target.canonicalUrl,vm.repo,Modifier.size(42.dp).clip(CircleShape),maxEdge=256)
                else Surface(shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Text(profile.name.take(1),Modifier.padding(12.dp),fontWeight=FontWeight.Bold)}
                Column(Modifier.weight(1f).padding(start=10.dp)) {
                    MentionText(profile.name,vm.repo,profile.target.platform,profile.target.canonicalUrl,accountLabel=true,explicitProfileLinks=profile.links.orEmpty(),fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(profile.target.kind!=TargetKind.PROFILE||profile.target.platform in setOf(Platform.FACEBOOK,Platform.LINKEDIN,Platform.PINTEREST,Platform.TIEBA))Text(searchDiscoveryAddress(profile.target),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            Text("${profile.target.platform.label} · ${searchDiscoveryKind(profile.target)}",Modifier.padding(top=8.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(profile.description.isNotBlank())MentionText(profile.description,vm.repo,profile.target.platform,profile.target.canonicalUrl,explicitProfileLinks=profile.links.orEmpty(),modifier=Modifier.padding(top=10.dp),fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}

private fun searchDiscoveryHeading(platform: Platform,accounts: List<Profile>)=when(platform) {
    Platform.FACEBOOK->if(accounts.any {it.target.kind==TargetKind.COMMUNITY})"Pages, people & groups"else "Pages & people"
    Platform.REDDIT->"Subreddits & users"
    Platform.LEMMY->"Communities & people"
    Platform.TELEGRAM->"Channels"
    Platform.PINTEREST->"People & boards"
    Platform.LINKEDIN->"People & organizations"
    Platform.TIEBA->"Forums & people"
    else->if(accounts.any {it.target.kind==TargetKind.COMMUNITY})"People & communities"else "People"
}

private fun searchDiscoveryKind(target: Target)=when(target.platform) {
    Platform.FACEBOOK->if(target.kind==TargetKind.COMMUNITY)"Group"else "Page or profile"
    Platform.REDDIT->if(target.kind==TargetKind.COMMUNITY)"Subreddit"else "User"
    Platform.LEMMY->if(target.kind==TargetKind.COMMUNITY)"Community"else "Person"
    Platform.TELEGRAM->"Channel"
    Platform.PINTEREST->if(target.kind==TargetKind.COMMUNITY)"Board"else "Profile"
    Platform.TIEBA->if(target.kind==TargetKind.COMMUNITY)"Forum"else "Profile"
    Platform.LINKEDIN->when(target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.firstOrNull()) {"company","school"->"Organization";"in"->"Person";else->"Public profile"}
    else->if(target.kind==TargetKind.COMMUNITY)"Community"else "Profile"
}

/** Destination labels follow each service's public address, never a guessed handle. */
private fun searchDiscoveryAddress(target: Target): String {
    val url=target.canonicalUrl.toHttpUrlOrNull()
    val parts=url?.pathSegments.orEmpty().filter {it.isNotBlank()}
    val identity=target.identity.removePrefix("profile/").removePrefix("@")
    return when(target.platform) {
        Platform.REDDIT->{
            val name=if(parts.firstOrNull() in setOf("r","u","user"))parts.getOrNull(1).orEmpty()else identity.removePrefix("r/").removePrefix("u/").removePrefix("user/")
            (if(target.kind==TargetKind.COMMUNITY)"r/"else "u/")+name
        }
        Platform.LEMMY->{
            val name=parts.getOrNull(1) ?: identity.removePrefix("c/").removePrefix("u/")
            if('@' in name)name.replace("@"," · ")else name+(url?.host?.let {" · $it"} ?: "")
        }
        Platform.FACEBOOK,Platform.LINKEDIN,Platform.PINTEREST,Platform.TIEBA->url?.let {it.host.removePrefix("www.")+it.encodedPath.trimEnd('/')+it.encodedQuery?.let {query->"?$query"}.orEmpty()} ?: target.canonicalUrl
        Platform.GETTR->"@"+identity.removePrefix("user/")
        Platform.SNAPCHAT->"@"+identity.removePrefix("add/")
        else->"@"+identity
    }
}

@Composable private fun SearchPlaceholder() {
    Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().height(160.dp).testTag("Search loading placeholder")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.size(36.dp)){}
            Surface(shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth(.9f).height(10.dp)){}
            Surface(shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth(.7f).height(10.dp)){}
        }
    }
}
