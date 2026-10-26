/* AntiSocial, GPL-3.0-or-later. Native regional search and locally preserved discovery records. */
package app.antisocial.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LocalDiscoveryScreen(vm:AntiSocialViewModel) {
    val controller=vm.localSearch;val state by controller.state.collectAsState();val context=LocalContext.current
    val focus=LocalFocusManager.current;val scope=rememberCoroutineScope()
    LaunchedEffect(controller){if(controller.state.value.savedOnly)controller.saved(false)}
    DisposableEffect(controller){onDispose {controller.stop();vm.repo.localSearchConnection(false)}}
    var chooseArea by rememberSaveable {mutableStateOf(false)}
    var showCoverage by rememberSaveable {mutableStateOf(false)}
    var selectedPlatform by remember {mutableStateOf(vm.repo.prefs.activePlatform)}
    var business by remember {mutableStateOf<LocalBusiness?>(null)}
    var event by remember {mutableStateOf<LocalEvent?>(null)}
    var candidate by remember {mutableStateOf<LocalAnnouncementCandidate?>(null)}
    var showPossible by rememberSaveable {mutableStateOf(false)}
    var flyer by remember {mutableStateOf<Pair<String,String>?>(null)}
    var importError by remember {mutableStateOf<String?>(null)}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri->if(uri!=null)scope.launch {
        try {val body=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use {stream->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val count=stream.read(buffer);if(count<0)break;require(output.size()+count<=8*1024*1024){"Choose a regional place pack smaller than 8 MB"};output.write(buffer,0,count)};output.toString("UTF-8")} ?: error("Could not read this file")};controller.importPlaces(body)}catch(e:Exception){importError=e.message}
    }}
    fun submit() {
        focus.clearFocus()
        if(state.mode==LocalSearchMode.POSTS) {
            val text=state.text.trim();if(text.isNotBlank()){if(text.startsWith("https://",true)||text.startsWith("http://",true))vm.openLink(text)else vm.open(vm.repo.routes.search(selectedPlatform,text),true)}
        }else if(state.area==null&&!state.savedOnly)chooseArea=true else controller.search()
    }
    Column(Modifier.fillMaxSize().testTag("Discovery screen")) {
        Column(Modifier.padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LocalSearchMode.entries.forEachIndexed {index,mode->SegmentedButton(selected=state.mode==mode,onClick={controller.mode(mode)},shape=SegmentedButtonDefaults.itemShape(index,3),label={Text(mode.name.lowercase().replaceFirstChar(Char::titlecase))},modifier=Modifier.testTag("Search mode ${mode.name}"))}
            }
            if(state.mode==LocalSearchMode.POSTS)LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(vm.repo.prefs.selected.filter(ReaderScope::isFeedSource),key={it.id}) {p->Surface(onClick={selectedPlatform=p},shape=RoundedCornerShape(14.dp),color=if(selectedPlatform==p)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,modifier=Modifier.testTag("Search site ${p.id}")){Box(Modifier.padding(10.dp)){PlatformMark(p,26.dp)}}}
            }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                AssistChip(enabled=!state.savedOnly,onClick={chooseArea=true},label={if(!state.savedOnly&&state.area!=null)Column(Modifier.padding(vertical=3.dp)){Text(state.area!!.name,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(state.unit.radius(state.area!!.radiusKm),fontSize=11.sp)}else Text(if(state.savedOnly)"All saved areas"else "Choose area",maxLines=1)},leadingIcon={Icon(Icons.Rounded.LocationOn,null,Modifier.size(17.dp))},modifier=Modifier.weight(1f,false).testTag("Discovery area"))
                IconButton(onClick={showCoverage=true}){Icon(Icons.Rounded.Info,"Search coverage and sources")}
            }
            OutlinedTextField(state.text,controller::text,label={Text(when(state.mode){LocalSearchMode.POSTS->"Search posts or paste a link";LocalSearchMode.BUSINESSES->"Name, service or interest";LocalSearchMode.EVENTS->"What would you like to do?"})},singleLine=true,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={submit()}),
                trailingIcon={IconButton(onClick={submit()},modifier=Modifier.testTag("Run discovery search")){Icon(Icons.Rounded.Search,"Search")}},modifier=Modifier.fillMaxWidth().testTag("Discovery query"))
            if(state.mode==LocalSearchMode.EVENTS&&!state.savedOnly)LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(LocalEventWindow.entries){window->FilterChip(selected=state.window==window,onClick={controller.window(window)},label={Text(window.label)})}
                item {FilterChip(selected=state.freeOnly,onClick={controller.free(!state.freeOnly)},label={Text("Free")})}
            }
        }
        if(state.mode!=LocalSearchMode.POSTS&&state.area!=null)Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=20.dp).testTag("Local search progress"),verticalAlignment=Alignment.CenterVertically) {
            val busy=state.loading||state.enriching
            if(busy)CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp)else Icon(Icons.Rounded.Check,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when(state.phase){LocalSearchPhase.FINDING->"Finding public information…";LocalSearchPhase.ENRICHING->"Adding details…";LocalSearchPhase.COMPLETE->"Search complete";LocalSearchPhase.STOPPED->"Search stopped";LocalSearchPhase.IDLE->"Ready to search"},Modifier.weight(1f).padding(start=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(busy)TextButton(onClick=controller::stop){Text("Stop")}
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {if(state.enriching)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("Enriching discovery details"),color=MaterialTheme.colorScheme.primary.copy(alpha=.35f),trackColor=MaterialTheme.colorScheme.surface)}
        state.message?.let {Text(it,Modifier.padding(horizontal=20.dp,vertical=8.dp).testTag("Discovery status"),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        when(state.mode) {
            LocalSearchMode.POSTS->Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {Text("The useful parts, without an account",fontSize=20.sp,fontWeight=FontWeight.SemiBold);Text("Choose a site and search. Public posts and accounts open in your native reader.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            else->LazyColumn(Modifier.fillMaxSize().testTag("Local discovery results"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if((state.mode==LocalSearchMode.BUSINESSES&&state.businesses.isEmpty())||(state.mode==LocalSearchMode.EVENTS&&state.events.isEmpty()))item {Column(Modifier.padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(state.savedOnly)"Your saved discoveries"else if(state.area==null)"Your corner of the world"else if(state.loading)"Looking beyond account gates"else "Search the public web nearby",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                    Text(when {state.savedOnly->"Saved places and events remain here offline until you remove them.";state.area==null->"Choose a city or area. It stays on this device; background location tracking is never needed.";state.mode==LocalSearchMode.EVENTS->"Try a topic such as markets, music or children. Only announcements with an actual event date and a matching venue or locality appear here.";else->"Try a business name, organic food or coffee. Public directories help connect its website and social accounts."},fontSize=14.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!state.savedOnly)TextButton(onClick={submit()}){Text(if(state.area==null)"Choose an area"else "Find ${if(state.mode==LocalSearchMode.EVENTS)"events"else "businesses"}")}
                }}
                if(state.mode==LocalSearchMode.BUSINESSES)items(state.groups,key={it.id}) {group->val place=group.primary
                    val activityKeys=remember(group.locations){group.locations.flatMap(::businessWorkKeys).toSet()}
                    BusinessCard(place,state.area,state.unit,group.locations.all {it.id in state.saved},onOpen={business=place},onSave={controller.save(group)},onSocial=vm::openLink,locations=group.locations.size,label=group.title,
                        activity={CardActivity(vm.repo.cardWork,activityKeys,place.id,discoveryPending=state.loading||state.enriching)})}
                if(state.mode==LocalSearchMode.EVENTS)items(state.events,key={it.id}) {session->EventCard(session,state.area,session.id in state.saved,unknowns=LocalEventIntent.parse(state.text).match(session).unknowns,onOpen={event=session},onSave={controller.save(session)},activity={CardActivity(vm.repo.cardWork,eventWorkKeys(session),session.id,discoveryPending=state.loading||state.enriching)})}
                if(state.mode==LocalSearchMode.EVENTS&&state.announcements.isNotEmpty()) {
                    item {TextButton(onClick={showPossible=!showPossible},modifier=Modifier.testTag("Possible events toggle")){Icon(if(showPossible)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null);Text("Possible events · ${state.announcements.size}")}}
                    if(showPossible)items(state.announcements,key={"possible:"+it.id}) {item->OutlinedCard(onClick={candidate=item},modifier=Modifier.fillMaxWidth().testTag("Possible event ${item.id}")) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(item.title,fontWeight=FontWeight.SemiBold);Text(item.missing.joinToString(" · "),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                }
                if(state.more&&!state.loading&&!state.enriching&&!state.savedOnly)item {TextButton(onClick={controller.search(more=true)},modifier=Modifier.fillMaxWidth()){Text("Check more public sources")}}
                if(state.coverage.isNotEmpty()&&!state.loading)item {TextButton(onClick={showCoverage=true}){Text("Source details",fontSize=12.sp)}}
                if(state.businesses.isNotEmpty()||state.events.isNotEmpty())item {Text("Public records can be incomplete or out of date. Check the linked source before travelling.",fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
    }
    if(chooseArea)AreaPicker(controller,onClose={chooseArea=false},onImport={importer.launch("application/*")})
    if(showCoverage)ModalBottomSheet(onDismissRequest={showCoverage=false}) {Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Public sources",style=MaterialTheme.typography.headlineSmall)
        Text("Directories connect places to their websites and social accounts. We read public calendars and structured page information, then keep the discoveries locally. A reachable page does not guarantee readable content.")
        Text("Place records: Overture Maps via Fused; © OpenStreetMap contributors via Overpass and Photon (ODbL); published AllThePlaces brand data (CC0). Area names: GeoNames via Open-Meteo. Regional lookup and search requests travel through Tor. Private.coffee’s provider privacy policy is available at overpass.private.coffee. Selected websites follow your reading connection settings.",fontSize=13.sp)
        Text("Each search checks a small batch and reuses recent discoveries. Name and category searches cover your chosen radius. Precise results connect to nearby identity records; publisher pages fill the missing details. Incomplete announcements stay under Possible events until their date and venue can be established.",fontSize=13.sp)
        Text("${state.checked} public pages checked in this search",fontWeight=FontWeight.SemiBold)
        if(state.enriching)TextButton(onClick={controller.stop();showCoverage=false}){Text("Pause background details")}
        state.coverage.forEach {Text(it,fontSize=12.sp)}
        TextButton(onClick={showCoverage=false;controller.search(force=true)}){Text("Refresh public sources")}
        Spacer(Modifier.height(24.dp))
    }}
    LaunchedEffect(business?.id){business?.let {controller.menu(it)} ?: controller.stopMenu()}
    business?.let {selected->
        val value=state.businesses.firstOrNull {it.id==selected.id} ?: state.businesses.firstOrNull {LocalDiscoveryMerge.sameBusiness(selected,it)} ?: selected
        val locations=state.groups.firstOrNull {group->group.locations.any {it.id==value.id}}?.locations.orEmpty()
        BusinessDetails(value,state.area,state.unit,value.id in state.saved,menuLoading=state.menuLoading==value.id,onMenuRefresh=if(state.savedOnly)null else ({controller.menu(value,true)}),onDismiss={business=null},onSave={controller.save(value)},
            onSocial={business=null;vm.openLink(it)},onRead={business=null;vm.articles.open(it)},locations=locations,onLocation={business=it},clean=vm.repo.cleaner::clean,
            activity={CardActivity(vm.repo.cardWork,businessWorkKeys(value),value.id,discoveryPending=state.loading||state.enriching)})
    }
    event?.let {selected->
        val value=state.events.firstOrNull {it.id==selected.id} ?: state.events.firstOrNull {LocalDiscoveryMerge.sameEvent(selected,it)} ?: selected
        EventDetails(value,state.area,state.text,onDismiss={event=null},saved=value.id in state.saved,onSave={controller.save(value)},onRead={url->event=null;if(vm.repo.routes.recognize(url)!=null)vm.openLink(url)else vm.articles.open(url,value)},onImage={url,source->flyer=url to source},repo=vm.repo,
            activity={CardActivity(vm.repo.cardWork,eventWorkKeys(value),value.id,discoveryPending=state.loading||state.enriching)})
    }
    candidate?.let {value->LocalDetailSheet(onDismiss={candidate=null}) {Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(value.title,style=MaterialTheme.typography.headlineSmall)
        Text(value.missing.joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.text)
        value.image?.let {url->TextButton(onClick={flyer=url to value.sourceUrl}){Text("View flyer")}}
        EvidenceDetails(value.evidence,onOpen={candidate=null;vm.articles.open(it)},onImage={url,source->flyer=url to source},clean=vm.repo.cleaner::clean)
        TextButton(onClick={candidate=null;if(vm.repo.routes.recognize(value.sourceUrl)!=null)vm.openLink(value.sourceUrl)else vm.articles.open(value.sourceUrl)}){Text("Read announcement")}
        Spacer(Modifier.height(24.dp))
    }}}
    flyer?.let {(url,source)->MediaViewer(listOf(Media(url)),0,source,vm.repo,onClose={flyer=null})}
    importError?.let {error->AlertDialog(onDismissRequest={importError=null},text={Text(error)},confirmButton={TextButton(onClick={importError=null}){Text("Close")}})}
}

@Composable internal fun BusinessCard(place:LocalBusiness,area:LocalArea?,unit:LocalDistanceUnit,saved:Boolean,onOpen:()->Unit,onSave:()->Unit,onSocial:(String)->Unit,activity:(@Composable ()->Unit)?=null,locations:Int=1,label:String=place.name) {
    val locale=LocalConfiguration.current.locales[0]
    ElevatedCard(onClick=onOpen,modifier=Modifier.fillMaxWidth().testTag("Business ${place.id}"),shape=RoundedCornerShape(20.dp),colors=CardDefaults.elevatedCardColors(containerColor=MaterialTheme.colorScheme.surface)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)){Text(label,fontSize=19.sp,fontWeight=FontWeight.SemiBold);if(place.category.isNotBlank())Text(LocalBusinessContact.category(place.category),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(onClick=onSave){Icon(if(saved)Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,if(saved)"Remove saved $label"else "Save $label")}}
        if(locations>1)Text("$locations nearby locations",fontSize=13.sp,color=MaterialTheme.colorScheme.primary)
        else if(place.address.isNotBlank())Text(place.address,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        area?.let {a->place.point?.let {p->Text(unit.away(a.point.distanceKm(p),locale),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f)){SocialAccounts(place.socials,onSocial)};activity?.invoke()}
    }}
}
@Composable internal fun EventCard(event:LocalEvent,area:LocalArea?,saved:Boolean,unknowns:List<String> = emptyList(),onOpen:()->Unit,onSave:()->Unit,activity:(@Composable ()->Unit)?=null) {
    ElevatedCard(onClick=onOpen,modifier=Modifier.fillMaxWidth().testTag("Event ${event.id}"),shape=RoundedCornerShape(20.dp),colors=CardDefaults.elevatedCardColors(containerColor=MaterialTheme.colorScheme.surface)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {Text(eventTime(event,area),Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.primary);IconButton(onClick=onSave){Icon(if(saved)Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,if(saved)"Remove saved ${event.title}"else "Save ${event.title}")}}
        Text(event.title,fontSize=19.sp,fontWeight=FontWeight.SemiBold)
        if(unknowns.isNotEmpty())Text(unknowns.joinToString(" · "),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(event.status!="scheduled")Text(event.status.replaceFirstChar(Char::titlecase),color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.SemiBold)
        Text(listOf(event.venue,event.locality,event.price).filter(String::isNotBlank).distinct().joinToString(" · "),fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        if(event.description.isNotBlank())Text(event.description,fontSize=13.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
        activity?.let {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){it()}}
    }}
}
@Composable private fun SocialAccounts(urls:List<String>,onOpen:(String)->Unit) {
    val accounts=urls.mapNotNull {url->SocialLinkHosts.platform(url)?.takeIf(ReaderScope::isFeedSource)?.let {it to url}}.distinctBy {it.second.trimEnd('/')}.take(8)
    if(accounts.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {accounts.forEach {(platform,url)->IconButton(onClick={onOpen(url)}){PlatformMark(platform,25.dp,"Open ${platform.label} account")}}}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BusinessDetails(value:LocalBusiness,area:LocalArea?,unit:LocalDistanceUnit,saved:Boolean,menuLoading:Boolean,onMenuRefresh:(()->Unit)?,onDismiss:()->Unit,onSave:()->Unit,onSocial:(String)->Unit,onRead:(String)->Unit,
    locations:List<LocalBusiness> = emptyList(),onLocation:((LocalBusiness)->Unit)?=null,clean:(String)->String={it},activity:(@Composable ()->Unit)?=null) {
    val context=LocalContext.current
    var relatedSources by remember(value.id) {mutableStateOf(false)}
    var visitDetails by remember(value.id) {mutableStateOf(false)}
    var menuOpen by remember(value.id) {mutableStateOf(false)}
    var chooseLocation by remember {mutableStateOf(false)}
    var locationCount by remember {mutableIntStateOf(12)}
    if(menuOpen) {
        RestaurantMenuSheet(value,menuLoading,onRefresh=onMenuRefresh,onDismiss={menuOpen=false},onRead=onRead)
        return
    }
    fun contact(action:String,uri:String) {
        try {context.startActivity(android.content.Intent(action,android.net.Uri.parse(uri)))}
        catch(_:android.content.ActivityNotFoundException){android.widget.Toast.makeText(context,"No app is available for this contact action",android.widget.Toast.LENGTH_SHORT).show()}
    }
    LocalDetailSheet(onDismiss,activity) {Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).testTag("Business details"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.Top) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(value.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                val subtitle=listOf(LocalBusinessContact.category(value.category).takeUnless {it in setOf("Local Business","Organization","Place")}.orEmpty(),area?.let {a->value.point?.let {unit.away(a.point.distanceKm(it))}}.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
                if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick=onSave){Icon(if(saved)Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,if(saved)"Remove saved copy"else "Save local copy")}
        }
        if(locations.size>1&&onLocation!=null) {
            TextButton(onClick={chooseLocation=!chooseLocation;locationCount=12},modifier=Modifier.testTag("Choose business location")){Icon(Icons.Rounded.LocationOn,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("${locations.size} nearby locations");Icon(if(chooseLocation)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)}
            if(chooseLocation) {
                locations.take(locationCount).forEach {location->OutlinedCard(onClick={onLocation(location);chooseLocation=false},modifier=Modifier.fillMaxWidth().testTag("Business location ${location.id}")) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(location.address.ifBlank {location.locality.ifBlank {location.name}},fontWeight=FontWeight.SemiBold)
                if(location.name!=value.name)Text(location.name,fontSize=12.sp)
                area?.let {a->location.point?.let {point->Text(unit.away(a.point.distanceKm(point)),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                location.phones.map {LocalBusinessContact.displayPhone(it,area?.countryCode)}.distinct().take(2).forEach {Text(it,fontSize=12.sp)}
                }}}
                if(locationCount<locations.size)TextButton(onClick={locationCount=(locationCount+12).coerceAtMost(locations.size)}){Text("Show more locations")}
            }
            Text("Selected location",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(value.status.isNotBlank())Text(value.status,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {Column {
            if(value.address.isNotBlank())BusinessContactRow(Icons.Rounded.LocationOn,"Address",value.address)
            value.phones.distinctBy {LocalBusinessContact.phoneKey(it) ?: it}.take(3).forEach {phone->val dial=LocalBusinessContact.phone(phone)
                BusinessContactRow(Icons.Rounded.Call,"Phone",LocalBusinessContact.displayPhone(phone,area?.countryCode),if(dial==null)null else ({contact(android.content.Intent.ACTION_DIAL,"tel:$dial")}))}
            value.emails.orEmpty().mapNotNull(LocalBusinessContact::email).distinct().take(3).forEach {email->BusinessContactRow(Icons.Rounded.Email,"Email",email){contact(android.content.Intent.ACTION_SENDTO,"mailto:$email")}}
            value.websites.sortedBy {LocalBusinessDetails.websitePriority(value,it)}.distinctBy {runCatching {java.net.URI(it).host.removePrefix("www.")}.getOrDefault(it)}.take(3).forEachIndexed {i,url->BusinessContactRow(Icons.Rounded.Language,if(LocalBusinessDetails.directorySite(value,url))"Directory entry"else if(i==0)"Website"else "Related website",IdentityDisplay.domain(url).removePrefix("www."),onClick={onRead(url)})}
        }}
        val facts=LocalBusinessDetails.facts(value)
        val resources=facts.filter {it.field in LocalBusinessDetails.links&&LocalDiscoveryIdentity.url(it.value)!=null}.distinctBy {it.field+it.value}.take(8)
        val menus=value.menus.orEmpty()
        if(resources.isNotEmpty()||menus.isNotEmpty()||menuLoading)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(menus.isNotEmpty()||menuLoading)OutlinedButton(onClick={menuOpen=true},modifier=Modifier.testTag("Read restaurant menu")) {Icon(Icons.Rounded.RestaurantMenu,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Menu")}
            resources.filterNot {it.field in setOf("menu","branch menu")&&(menus.isNotEmpty()||menuLoading)}.distinctBy {it.value}.forEach {field->OutlinedButton(onClick={onRead(field.value)}){Text(LocalBusinessDetails.links.getValue(field.field))}}
        }
        val details=facts.filter {it.field in LocalBusinessDetails.text}
        if(details.isNotEmpty()) {
            TextButton(onClick={visitDetails=!visitDetails}){Text(if(visitDetails)"Hide visit details"else "Visit details")}
            if(visitDetails)details.forEach {field->Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(LocalBusinessDetails.text.getValue(field.field),style=MaterialTheme.typography.labelLarge);Text(field.value,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        }
        if(value.socials.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {Text("Public accounts",style=MaterialTheme.typography.titleSmall);SocialAccounts(value.socials,onSocial)}
        value.related.orEmpty().filter {it.role!="source publisher"}.forEach {entity->Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(if(entity.role=="linked account")"Linked account · identity not yet matched"else entity.role.replaceFirstChar(Char::titlecase),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entity.name,style=MaterialTheme.typography.bodyLarge)
            SocialAccounts(entity.accounts,onSocial)
            TextButton(onClick={onRead(entity.sourceUrl)}){Text("Source of this association")}
        }}
        if(value.hours.isNotBlank())Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {Text("Hours",style=MaterialTheme.typography.titleSmall);Text(value.hours.replace(" · ","\n").replace(Regex("(\\d{1,2}:\\d{2}):00"),"$1"),style=MaterialTheme.typography.bodyMedium,lineHeight=23.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(value.description.isNotBlank())Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {Text("About",style=MaterialTheme.typography.titleSmall);Text(value.description,style=MaterialTheme.typography.bodyMedium,lineHeight=23.sp)}
        HorizontalDivider()
        EvidenceDetails(value.evidence,onRead,clean=clean)
        if(value.related.orEmpty().any {it.role=="source publisher"}) {
            TextButton(onClick={relatedSources=!relatedSources}){Text(if(relatedSources)"Hide directory publishers"else "Directory publishers")}
            if(relatedSources)value.related.orEmpty().filter {it.role=="source publisher"}.forEach {entity->Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Directory publisher · ${entity.name}",style=MaterialTheme.typography.bodyMedium)
                Text("Publishes this listing. A relationship to the business has not been established.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                SocialAccounts(entity.accounts,onSocial)
                TextButton(onClick={onRead(entity.sourceUrl)}){Text("Read directory entry")}
            }}
        }
        TextButton(onClick=onSave){Text(if(saved)"Remove saved copy"else "Save local copy")}
        Spacer(Modifier.height(24.dp))
    }}
}
@Composable private fun BusinessContactRow(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,value:String,onClick:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClickLabel="$label: $value",onClick=onClick)else Modifier).padding(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,Modifier.size(21.dp),tint=MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.bodyLarge,color=if(onClick!=null)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)}
        if(onClick!=null)Icon(Icons.Rounded.ChevronRight,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun EvidenceDetails(evidence:List<LocalFieldEvidence>,onOpen:(String)->Unit,onImage:((String,String)->Unit)?=null,clean:(String)->String={it},available:((String)->LocalLinkCheck?)?=null,onExpand:(()->Unit)?=null) {
    var expanded by remember {mutableStateOf(false)}
    TextButton(onClick={expanded=!expanded;if(expanded)onExpand?.invoke()}){Icon(Icons.Rounded.Info,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(expanded)"Hide sources"else "Where this information came from")}
    if(expanded)LocalDiscoveryPresentation.sources(evidence,clean).take(12).forEach {source->Column(verticalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.testTag("Discovery source ${source.url}")) {
        Text(source.fields.joinToString(" · "){it.replaceFirstChar(Char::titlecase)},fontWeight=FontWeight.SemiBold,fontSize=12.sp)
        Text("Retrieved "+Instant.ofEpochMilli(source.checkedAt).atZone(ZoneId.systemDefault()).toLocalDate(),fontSize=11.sp)
        source.excerpts.forEach {Text(it,fontSize=12.sp)}
        source.images.forEach {image->if(onImage!=null)TextButton(onClick={onImage(image,source.url)}){Text("Inspect source flyer")}}
        source.licenses.forEach {Text(it,fontSize=11.sp)}
        val status=available?.invoke(source.url)
        if(available!=null&&status?.state!=LocalLinkState.AVAILABLE)Text(IdentityDisplay.domain(source.url)+when(status?.state){LocalLinkState.UNAVAILABLE->" · unavailable";LocalLinkState.CHECKING->" · checking";else->" · not checked"},fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(available==null||status?.state !in setOf(LocalLinkState.UNAVAILABLE,LocalLinkState.CHECKING))TextButton(onClick={onOpen(status?.destination ?: source.url)}){Text(IdentityDisplay.domain(source.url)+(if(available!=null&&status?.state!=LocalLinkState.AVAILABLE)" · open source"else ""),maxLines=1,overflow=TextOverflow.Ellipsis)}
    }}
}
internal fun eventTime(event:LocalEvent,area:LocalArea?):String {
    val zone=area?.zoneId ?: event.start.zoneId
    val currentYear=LocalDate.now(ZoneId.of(zone ?: "UTC")).year
    fun date(day:LocalDate)=day.format(DateTimeFormatter.ofPattern(if(day.year==currentYear)"EEE, d MMM"else "EEE, d MMM uuuu"))
    fun local(time:LocalEventTime)=time.instant()?.atZone(ZoneId.of(zone ?: "UTC"))?.toLocalDateTime() ?: LocalDateTime.parse(time.value)
    return runCatching {
        if(event.start.allDay) {
            val start=event.start.date()!!;val end=event.end?.date()
            date(start)+(if(end!=null&&end!=start)" – "+date(end)else "")+" · All day"
        }else {
            val start=local(event.start);val end=event.end?.takeUnless {it.allDay}?.let(::local)
            val clock=DateTimeFormatter.ofPattern("HH:mm")
            date(start.toLocalDate())+" · "+start.format(clock)+(if(end!=null&&end!=start) {
                if(end.toLocalDate()==start.toLocalDate())"–"+end.format(clock)else " – "+date(end.toLocalDate())+" · "+end.format(clock)
            }else "")+(if(zone==null)if(event.start.instant()==null)" · Local time"else " · UTC"else "")
        }
    }.getOrDefault(event.start.value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AreaPicker(controller:LocalDiscoveryController,onClose:()->Unit,onImport:()->Unit) {
    val state by controller.state.collectAsState();var text by rememberSaveable {mutableStateOf("")}
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.testTag("Area picker")) {
        item {Text("Choose your area",style=MaterialTheme.typography.headlineSmall);Text("Remembered here. No location tracking.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {OutlinedTextField(text,{text=it.take(160)},label={Text("City or area, country")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("Area lookup query"),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={controller.findArea(text)}),trailingIcon={IconButton(onClick={controller.findArea(text)}){Icon(Icons.Rounded.Search,"Find area")}})}
        if(state.lookingUpArea)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        state.message?.let {item {Text(it,fontSize=12.sp)}}
        items(state.choices,key={"choice:"+it.id}) {area->TextButton(onClick={controller.area(area);onClose()},modifier=Modifier.fillMaxWidth()){Text(area.label,Modifier.fillMaxWidth())}}
        if(state.areas.isNotEmpty())item {Text("Saved areas",fontWeight=FontWeight.SemiBold)}
        items(state.areas,key={"saved:"+it.id}) {area->Row(verticalAlignment=Alignment.CenterVertically) {TextButton(onClick={controller.area(area);onClose()},modifier=Modifier.weight(1f)){Text(area.label,Modifier.fillMaxWidth())};IconButton(onClick={controller.removeArea(area.id)}){Icon(Icons.Rounded.Close,"Forget ${area.name}")}}}
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Distance",fontWeight=FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow {LocalDistanceUnit.entries.forEachIndexed {i,unit->SegmentedButton(selected=state.unit==unit,onClick={controller.unit(unit)},shape=SegmentedButtonDefaults.itemShape(i,2),label={Text(unit.symbol)},modifier=Modifier.testTag("Distance unit ${unit.id}"))}}
        }}
        state.area?.let {area->item {Text("Search radius · ${state.unit.radius(area.radiusKm)}",fontWeight=FontWeight.SemiBold);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){state.unit.presets.forEach {value->FilterChip(selected=area.radiusKm==state.unit.toKm(value),onClick={controller.area(area.copy(radiusKm=state.unit.toKm(value)))},label={Text("$value ${state.unit.symbol}")})}}}}
        item {Text("Area lookup uses GeoNames through Open-Meteo over Tor.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(state.area!=null)item {TextButton(onClick={onClose();onImport()}){Text("Import a regional place pack")}}
        item {Spacer(Modifier.height(24.dp))}
    }}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EventDetails(value:LocalEvent,area:LocalArea?,query:String="",saved:Boolean,onDismiss:()->Unit,onSave:()->Unit,onRead:(String)->Unit,onImage:(String,String)->Unit,repo:Repository?=null,activity:(@Composable ()->Unit)?=null) {
        val checks=repo?.localLinks?.state?.collectAsState()?.value
        val scope=rememberCoroutineScope()
        LocalDetailSheet(onDismiss,activity) {Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("Event details"),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(value.title,style=MaterialTheme.typography.headlineSmall);Text(eventTime(value,area),fontWeight=FontWeight.SemiBold)
        if(repo!=null)EventAnnouncementLinks(value,repo,onRead)else LocalDiscoveryPresentation.eventLinks(value).take(1).forEach {link->TextButton(onClick={onRead(link.url)}){Text(if(link.registration)"Registration & details"else IdentityDisplay.domain(link.url))}}
        listOf(value.status.takeUnless {it=="scheduled"}.orEmpty(),value.venue,value.address,value.price,value.age.takeIf(String::isNotBlank)?.let {"Ages $it"}.orEmpty(),value.organizer).filter(String::isNotBlank).distinct().forEach {Text(it)}
        LocalEventIntent.parse(query).match(value).let {match->(match.reasons+match.unknowns).distinct().forEach {Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        if(value.description.isNotBlank())Text(value.description)
        EvidenceDetails(value.evidence,onOpen={onRead(it)},onImage={url,source->onImage(url,source)},clean=repo?.cleaner?.let {it::clean} ?: {it},available=repo?.localLinks?.let {links->{url->links.check(url,value,checks.orEmpty())}},onExpand=repo?.let {{scope.launch {it.localLinks.inspect(value,includeSources=true)}}})
        TextButton(onClick={onSave()}){Text(if(saved)"Remove saved copy"else "Save local copy")};Spacer(Modifier.height(24.dp))
    }}
}
