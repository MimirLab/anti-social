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

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AdjacentSheet(repo:Repository,state:AdjacentUiState,onOpen:(Target)->Unit,onSubscribe:(Profile)->Unit,
    onMore:()->Unit,onRefresh:()->Unit,onVisible:(List<String>)->Unit,onDismiss:()->Unit) {
    val list=rememberLazyListState();var platform by remember(state.seed?.target){mutableStateOf<Platform?>(null)}
    var query by remember(state.seed?.target){mutableStateOf("")};var search by remember {mutableStateOf(false)};var sources by remember {mutableStateOf(false)}
    var kind by remember {mutableStateOf<AdjacentKind?>(null)};var kinds by remember {mutableStateOf(false)}
    val groups=remember(state.snapshot.cards,state.identities){adjacentGroups(state)}
    val platforms=remember(groups,repo.prefs.selected){val available=groups.flatMap {it.accounts}.map {it.platform}.toSet();(repo.prefs.selected+PlatformOrder.popular).distinct().filter {it in available}}
    val shown=remember(groups,platform,query,kind){groups.filter {g->(platform==null||g.accounts.any {it.platform==platform})&&
        (kind==null||g.cards.any {c->c.evidence.any {it.kind==kind}})&&(query.isBlank()||g.cards.any {c->(c.profile.name+" "+c.profile.description+" "+c.profile.target.identity).contains(query,true)})}}
    LaunchedEffect(list,shown){snapshotFlow {list.layoutInfo.visibleItemsInfo.mapNotNull {it.key as? String}.filter {it.startsWith("neighbor:")}.map {it.removePrefix("neighbor:")}}
        .distinctUntilChanged().collect {keys->onVisible(shown.filter {it.key in keys}.flatMap {it.cards.map(AdjacentCard::key)})}}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Related discovery")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal=20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text("Discover related",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                    state.seed?.let {Text(it.name,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}}
                IconButton(onClick={search=!search}){Icon(Icons.Rounded.Search,"Filter connections")}
                IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close discovery")}
            }
            if(search)OutlinedTextField(query,{query=it.take(120)},singleLine=true,label={Text("Find among connections")},modifier=Modifier.fillMaxWidth().testTag("Filter related accounts"))
            Row(verticalAlignment=Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    item {IconToggleButton(checked=platform==null,onCheckedChange={platform=null},modifier=Modifier.testTag("Related all platforms")){Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_antisocial),"All platforms",Modifier.size(30.dp))}}
                    items(platforms,key={it.id}) {p->IconToggleButton(checked=platform==p,onCheckedChange={platform=if(it)p else null},colors=IconButtonDefaults.iconToggleButtonColors(checkedContainerColor=MaterialTheme.colorScheme.secondaryContainer),modifier=Modifier.testTag("Related platform ${p.id}")){PlatformMark(p,28.dp)}}
                }
                Box {IconButton(onClick={kinds=true}){Icon(Icons.Rounded.Tune,"Connection types")}
                    DropdownMenu(expanded=kinds,onDismissRequest={kinds=false}) {
                        DropdownMenuItem(text={Text("All connections")},onClick={kind=null;kinds=false})
                        listOf(AdjacentKind.FEATURED to "Creator recommendations",AdjacentKind.SHARED_FOLLOWERS to "Shared followers",AdjacentKind.SHARED_PARTICIPANTS to "Shared commenters",AdjacentKind.INTERACTION to "Interactions",AdjacentKind.COMMUNITY to "Communities",AdjacentKind.READING_LIST to "Reading lists").forEach {(k,label)->DropdownMenuItem(text={Text(label)},onClick={kind=k;kinds=false})}
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(state.loading||state.processing.isNotEmpty())CircularProgressIndicator(Modifier.size(13.dp),strokeWidth=2.dp)
                Text(when {state.preparing->"Opening public connections…";state.snapshot.loading->"Finding connections…";state.findingBridges->"Checking linked accounts…";state.processing.isNotEmpty()->"Finding other accounts…";else->"${groups.size} connections · Ready"},Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={sources=!sources}){Text("Sources")}
            }
            LazyColumn(Modifier.weight(1f).testTag("Related accounts"),state=list,verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                if(state.bridges.isNotEmpty())item(key="bridges") {Text("Also looking through "+state.bridges.map {it.target.platform.label}.distinct().joinToString(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(sources)item(key="sources") {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Observed public connections",fontWeight=FontWeight.SemiBold)
                    Text("Samples can miss accounts. A follow, reply or shared audience is not an ownership claim or endorsement.",style=MaterialTheme.typography.bodySmall)
                    state.snapshot.coverage.forEach {c->Text("${sourceName(c.source)} · ${if(c.status in setOf(HealthStatus.HEALTHY,HealthStatus.EMPTY))"${c.sampled} sampled"else "Unavailable"}\n${c.detail}",style=MaterialTheme.typography.bodySmall)}
                }}
                items(shown,key={"neighbor:"+it.key}) {group->AdjacentAccountCard(repo,group,group.cards.any {it.key in state.processing},onOpen,onSubscribe)}
                if(shown.isEmpty()&&!state.loading)item(key="empty") {Text(if(groups.isNotEmpty())"No connections match these filters."else "No usable connections were found in the public material checked.",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                state.error?.let {error->item(key="error"){Text(error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                if(state.snapshot.hasMore)item(key="more") {OutlinedButton(onClick=onMore,enabled=!state.loading,modifier=Modifier.fillMaxWidth().testTag("Explore more connections")){Text("Explore further")}}
                if(!state.loading)item(key="refresh") {TextButton(onClick=onRefresh,modifier=Modifier.fillMaxWidth()){Text("Check again")}}
            }
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
