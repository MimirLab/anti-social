/* anti social, GPL-3.0-or-later. Compact, inspectable public-account discovery. */
package app.antisocial.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class AdjacentGroup(val key:String,val cards:List<AdjacentCard>,val accounts:List<Target>)
internal fun adjacentGroups(value:AdjacentUiState):List<AdjacentGroup> {
    val rows=value.snapshot.cards;val parent=rows.associate {it.key to it.key}.toMutableMap();val index=rows.withIndex().associate {it.value.key to it.index}
    fun root(key:String):String {var at=key;while(parent[at]!=at)at=parent.getValue(at);return at}
    val seen=HashMap<String,String>();val targets=HashMap<String,List<Target>>()
    for(card in rows) {
        val aliases=(listOf(card.profile.target)+value.identities[card.key]?.let(AdjacentController::safeAccounts).orEmpty().map {it.target}).distinctBy(AdjacentDiscovery::key)
        targets[card.key]=aliases
        for(target in aliases){val previous=seen.putIfAbsent(AdjacentDiscovery.key(target),card.key) ?: continue
            val a=root(previous);val b=root(card.key);if(a!=b){val first=if(index.getValue(a)<index.getValue(b))a else b;parent[if(first==a)b else a]=first}
        }
    }
    return rows.groupBy {root(it.key)}.map {(key,cards)->AdjacentGroup(key,cards,cards.flatMap {targets[it.key].orEmpty()}.distinctBy(AdjacentDiscovery::key))}
}

@Composable internal fun AdjacentDialog(vm:AntiSocialViewModel,profile:Profile,page:Page?,onDismiss:()->Unit) {
    val controller=vm.adjacent;val state by controller.state.collectAsState()
    LaunchedEffect(profile.target){controller.start(profile,page)}
    DisposableEffect(controller,profile.target){onDispose {controller.stop()}}
    var subscribe by remember {mutableStateOf<Profile?>(null)}
    subscribe?.let {selected->
        if(selected.target.platform==Platform.RSS)AlertDialog(onDismissRequest={subscribe=null},title={Text("Subscribe locally")},text={Text(selected.name)},
            confirmButton={TextButton(onClick={vm.launch {vm.repo.addRssSource(selected.target.canonicalUrl,selected.name);subscribe=null}}){Text("Subscribe")}},dismissButton={TextButton(onClick={subscribe=null}){Text("Cancel")}})
        else SubscriptionDialog(vm,selected.target.platform,autoDiscover=AccountLinkage.valid(selected.target),initialTarget=selected.target,initialProfile=selected,onDismiss={subscribe=null})
    }
    AdjacentSheet(vm.repo,state,onOpen={target->controller.stop();onDismiss();vm.open(target)},onSubscribe={subscribe=it},onMore=controller::more,onRefresh=controller::refresh,onVisible=controller::visible,onDismiss=onDismiss)
}

internal data class AdjacentDeckMethod(val key:String,val label:String,val provider:Platform,val groups:List<AdjacentGroup>)
internal fun adjacentDeck(state:AdjacentUiState):List<AdjacentDeckMethod> {
    val methods=linkedMapOf<String,MutableList<AdjacentCard>>()
    for(card in state.snapshot.cards)for(e in card.evidence){val key=discoveryMethod(e);methods.getOrPut(key){mutableListOf()}.add(card.copy(evidence=listOf(e)))}
    val ordered=(state.methodOrder+methods.keys).distinct()
    return ordered.mapNotNull {key->val cards=methods[key]?.groupBy {it.key}?.map {(_,group)->group.first().copy(evidence=group.flatMap {it.evidence})} ?: return@mapNotNull null
        val evidence=cards.first().evidence.first();val p=evidence.owner.platform
        val label=when(evidence.kind){AdjacentKind.FEATURED->"Creator recommendations";AdjacentKind.FOLLOWS->"Following";AdjacentKind.SHARED_FOLLOWERS->"Shared followers";AdjacentKind.SHARED_PARTICIPANTS->"Shared commenters";AdjacentKind.SECOND_HOP->"Their circle";AdjacentKind.INTERACTION->"Public conversations";AdjacentKind.COMMUNITY->"Communities";AdjacentKind.FORWARDED->"Shared sources";AdjacentKind.MENTIONED->"Linked accounts";AdjacentKind.READING_LIST->"Reading lists";AdjacentKind.COLLABORATION->"Collaborators"}
        AdjacentDeckMethod(key,label,p,adjacentGroups(state.copy(snapshot=state.snapshot.copy(cards=cards))))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AdjacentSheet(repo:Repository,state:AdjacentUiState,onOpen:(Target)->Unit,onSubscribe:(Profile)->Unit,
    onMore:()->Unit,onRefresh:()->Unit,onVisible:(List<String>)->Unit,onDismiss:()->Unit) {
    val list=rememberLazyListState();var platform by remember(state.seed?.target){mutableStateOf<Platform?>(null)}
    var query by remember(state.seed?.target){mutableStateOf("")};var search by remember {mutableStateOf(false)}
    val methods=remember(state.snapshot.cards,state.identities,state.methodOrder){adjacentDeck(state)}
    val bridges=state.bridgeMethods.associateBy {it.key}
    val order=(state.methodOrder+methods.map {it.key}+bridges.keys).distinct()
    val platforms=remember(methods,state.bridgeMethods,repo.prefs.selected){val available=methods.flatMap {it.groups}.flatMap {it.accounts}.map {it.platform}.toSet()+state.bridgeMethods.flatMap {it.resolved.values.flatten()}.map {it.target.platform};(repo.prefs.selected+PlatformOrder.popular).distinct().filter {it in available}}
    val visibleByMethod=remember {mutableStateMapOf<String,List<String>>()}
    LaunchedEffect(list,order,visibleByMethod.toMap()){snapshotFlow {list.layoutInfo.visibleItemsInfo.mapNotNull {it.key as? String}}.distinctUntilChanged().collect {keys->onVisible(keys.flatMap {visibleByMethod[it].orEmpty()})}}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Related discovery")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal=16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text("Discover related",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                    state.seed?.let {Text(it.name,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}}
                IconButton(onClick={search=!search}){Icon(Icons.Rounded.Search,"Filter connections")}
                IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close discovery")}
            }
            if(search)OutlinedTextField(query,{query=it.take(120)},singleLine=true,label={Text("Find among connections")},modifier=Modifier.fillMaxWidth().testTag("Filter related accounts"))
            if(!search)LazyRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                item {IconToggleButton(checked=platform==null,onCheckedChange={platform=null},modifier=Modifier.testTag("Related all platforms")){Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_antisocial),"All platforms",Modifier.size(28.dp))}}
                items(platforms,key={it.id}) {p->IconToggleButton(checked=platform==p,onCheckedChange={platform=if(it)p else null},modifier=Modifier.testTag("Related platform ${p.id}")){PlatformMark(p,26.dp)}}
            }
            Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(state.loading)CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=2.dp)
                Text(if(state.loading)"Finding public connections…"else "Public connections · Ready",Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(!state.loading)IconButton(onClick=onRefresh,modifier=Modifier.size(30.dp)){Icon(Icons.Rounded.Refresh,"Check connections again",Modifier.size(18.dp))}
            }
            LazyColumn(Modifier.weight(1f).testTag("Related accounts"),state=list,verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                for(key in order) {
                    val native=methods.firstOrNull {it.key==key}
                    if(native!=null) {
                        val shown=native.groups.filter {g->(platform==null||g.accounts.any {it.platform==platform})&&(query.isBlank()||g.cards.any {c->(c.profile.name+" "+c.profile.description+" "+c.profile.target.identity).contains(query,true)})}
                        if(shown.isNotEmpty())item(key=key) {
                            DiscoveryMethodFrame(native.label,native.provider.label,shown.size,shown.any {g->g.cards.any {it.key in state.processing}},mark={PlatformMark(native.provider,22.dp)}) {
                                val horizontal=rememberLazyListState()
                                LaunchedEffect(horizontal,shown){snapshotFlow {horizontal.layoutInfo.visibleItemsInfo.mapNotNull {it.key as? String}}.distinctUntilChanged().collect {keys->visibleByMethod[key]=shown.filter {it.key in keys}.flatMap {it.cards.map(AdjacentCard::key)}}}
                                LazyRow(state=horizontal,horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=12.dp)) {
                                    items(shown,key={it.key}) {group->Box(Modifier.width(256.dp)){AdjacentAccountCard(repo,group,group.cards.any {it.key in state.processing},onOpen,onSubscribe)}}
                                }
                            }
                        }
                    }else bridges[key]?.let {method->
                        val shown=method.page.neighbors.filter {n->val profiles=method.resolved[PublicAccounts.key(n.account)].orEmpty();(platform==null||profiles.any {it.target.platform==platform})&&(query.isBlank()||n.account.name.contains(query,true)||profiles.any {it.name.contains(query,true)||it.target.identity.contains(query,true)})}
                        if(shown.isNotEmpty())item(key=key) {
                            DiscoveryMethodFrame(when(method.page.neighbors.firstOrNull()?.kind){AdjacentKind.SHARED_PARTICIPANTS->"Shared audience";AdjacentKind.FOLLOWS->"Following";else->"Creator recommendations"},PublicAccounts.label(method.page.account.provider),shown.size,method.processing.isNotEmpty(),mark={if(method.page.account.provider=="youtube")PlatformMark(Platform.YOUTUBE,22.dp)else Icon(Icons.Rounded.TravelExplore,null,Modifier.size(22.dp))}) {
                                val horizontal=rememberLazyListState()
                                LaunchedEffect(horizontal,shown){snapshotFlow {horizontal.layoutInfo.visibleItemsInfo.mapNotNull {it.key as? String}}.distinctUntilChanged().collect {visibleByMethod[key]=it}}
                                LazyRow(state=horizontal,horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=12.dp)) {
                                    items(shown,key={PublicAccounts.key(it.account)}) {neighbor->val id=PublicAccounts.key(neighbor.account)
                                        BridgeAccountTile(neighbor,method.resolved[id].orEmpty(),id in method.processing,id in method.checked,id in method.failed,onOpen,onSubscribe)
                                    }
                                }
                            }
                        }
                    }
                }
                if(order.isEmpty()&&!state.loading)item(key="empty") {Text("No usable connections were found in the public material checked.",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                state.error?.let {error->item(key="error"){Text(error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                // Progression is automatic; reaching this part of the deck also
                // resumes any bounded page work left from an earlier block.
                if(state.snapshot.hasMore)item(key="continuation") {LaunchedEffect(state.loading){if(!state.loading)onMore()};if(state.loading)LinearProgressIndicator(Modifier.fillMaxWidth())}
            }
        }
    }
}

@Composable private fun DiscoveryMethodFrame(title:String,source:String,count:Int,processing:Boolean,mark:@Composable ()->Unit,content:@Composable ()->Unit) {
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth().testTag("Discovery method $source $title")) {
        Column {
            Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                mark();Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold);Text("$source · $count "+if(count==1)"connection"else "connections",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(processing)CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)
            }
            content()
        }
    }
}
@Composable private fun BridgeAccountTile(neighbor:BridgeNeighbor,profiles:List<Profile>,processing:Boolean,checked:Boolean,failed:Boolean,onOpen:(Target)->Unit,onSubscribe:(Profile)->Unit) {
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface,modifier=Modifier.width(256.dp)) {
        Column(Modifier.padding(14.dp).heightIn(min=150.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(neighbor.account.name.ifBlank {neighbor.account.url.substringAfterLast('/')},fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(neighbor.detail,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(profiles.isNotEmpty())Row(verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(2.dp)){profiles.distinctBy {it.target.platform}.take(5).forEach {p->IconButton(onClick={onOpen(p.target)},modifier=Modifier.size(34.dp)){PlatformMark(p.target.platform,23.dp)}}}
                IconButton(onClick={onSubscribe(profiles.first())}){Icon(Icons.Rounded.Add,"Subscribe locally to ${neighbor.account.name}")}
            }
            Text(when {processing->"Finding social accounts…";profiles.isNotEmpty()->"Public accounts found";failed->"Lookup unavailable · try again later";checked->"No public social links found";else->"Social accounts not checked yet"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun AdjacentAccountCard(repo:Repository,group:AdjacentGroup,processing:Boolean,onOpen:(Target)->Unit,onSubscribe:(Profile)->Unit) {
    var expanded by remember(group.key){mutableStateOf(false)}
    val profile=group.cards.firstOrNull {it.profile.displayNameSupplied}?.profile ?: group.cards.first().profile
    val evidence=group.cards.flatMap {it.evidence}.distinctBy {it.kind.name+it.owner.canonicalUrl+it.sourceUrl}.sortedByDescending {when(it.kind){AdjacentKind.FEATURED,AdjacentKind.COLLABORATION->4;AdjacentKind.READING_LIST->3;AdjacentKind.SHARED_FOLLOWERS,AdjacentKind.SHARED_PARTICIPANTS->2;else->1}}
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.testTag("Related card ${profile.target.identity}")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.clickable {onOpen(profile.target)}){if(profile.avatar!=null)RemoteImage(profile.avatar!!,profile.target.canonicalUrl,repo,Modifier.size(42.dp).clip(CircleShape),maxEdge=256)else PlatformMark(profile.target.platform,34.dp)}
                Column(Modifier.weight(1f).padding(start=10.dp).clickable {onOpen(profile.target)}) {
                    MentionText(profile.name,repo,profile.target.platform,profile.target.canonicalUrl,accountLabel=true,networkAllowed=false,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,onAccountClick={onOpen(profile.target)})
                    Text(if(profile.target.kind==TargetKind.COMMUNITY)"Community"else profile.target.platform.label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick={onSubscribe(profile)}){Icon(Icons.Rounded.PersonAdd,"Subscribe locally to ${profile.name}")}
            }
            if(profile.description.isNotBlank())Text(profile.description,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={expanded=!expanded},modifier=Modifier.weight(1f)){Text(evidence.firstOrNull()?.let(AdjacentDiscovery::reason) ?: "Public connection",maxLines=2)}
                if(processing)CircularProgressIndicator(Modifier.size(13.dp),strokeWidth=2.dp)
            }
            if(group.accounts.size>1)Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {group.accounts.take(8).forEach {account->IconButton(onClick={onOpen(account)},modifier=Modifier.size(36.dp)){PlatformMark(account.platform,23.dp)}}}
            if(expanded)Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {evidence.forEach {e->
                Column {
                    Text(AdjacentDiscovery.reason(e),style=MaterialTheme.typography.bodyMedium)
                    Text(e.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${e.owner.platform.label} · Checked "+Instant.ofEpochMilli(e.checkedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d")),style=MaterialTheme.typography.labelSmall)
                    Row {TextButton(onClick={onOpen(e.owner)}){Text("Source account")}
                        IconButton(onClick={ReaderClipboard.copy(repo.context,"Discovery source",e.sourceUrl)}){Icon(Icons.Rounded.ContentCopy,"Copy public evidence address",Modifier.size(16.dp))}}
                }
            }}
        }
    }
}

private fun sourceName(value:String)=when {value.contains("x-follow")->"X public graph";value.contains("bsky")->"Bluesky public graph";value.contains("federated")->"Public federation";value.contains("reddit")->"Reddit archive";value.contains("publisher")->"Publisher recommendations";else->"Public source"}
