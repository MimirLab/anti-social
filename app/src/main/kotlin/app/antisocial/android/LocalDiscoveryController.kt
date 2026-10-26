/* AntiSocial, GPL-3.0-or-later. Deterministic, bounded local discovery. AI is not required. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant

internal interface LocalDiscoveryBackend {
    suspend fun areas(text:String):List<LocalArea>
    suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction
    suspend fun identities(point:LocalPoint):LocalExtraction
    suspend fun places(query:LocalDiscoveryQuery):LocalExtraction=LocalExtraction()
    suspend fun discover(query:LocalDiscoveryQuery):List<LocalWebHit>
    suspend fun discover(query:LocalDiscoveryQuery,onHits:suspend (List<LocalWebHit>)->Unit){onHits(discover(query))}
    suspend fun account(url:String):Boolean=false
    suspend fun refine(query:LocalDiscoveryQuery,business:LocalBusiness):List<LocalWebHit> = emptyList()
    suspend fun venue(query:LocalDiscoveryQuery,event:LocalEvent):LocalEvent?=null
    suspend fun enrich(query:LocalDiscoveryQuery,businesses:List<LocalBusiness>,onData:suspend (LocalExtraction)->Unit) {}
    suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction
    suspend fun poster(task:LocalPosterSource,query:LocalDiscoveryQuery):LocalExtraction=LocalExtraction()
}
internal class PublicLocalDiscoveryBackend(private val repo:Repository):LocalDiscoveryBackend {
    private val announcementReader=LocalAnnouncementReader(repo)
    override suspend fun poster(task:LocalPosterSource,query:LocalDiscoveryQuery)=announcementReader.read(task,query)
    private data class Venue(val point:LocalPoint,val locality:String,val sourceUrl:String,val checkedAt:Long)
    private suspend fun protected() {
        repo.awaitReady();repo.localSearchConnection(true)
        try {withTimeout(60_000){repo.tor.state.first {it.enabled&&it.ready}}}
        catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();throw SourceFailure(HealthStatus.UNAVAILABLE,"Waiting for a private search connection. No direct fallback was used.")}
    }
    override suspend fun areas(text:String):List<LocalArea> {protected();val p=repo.gateway.text(LocalPlaceSources.geocodeUrl(text),maxBytes=256*1024,route=NetworkRoute.TOR);return LocalPlaceSources.areas(p.text)}
    override suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction = repo.localPlaces.directory(query,::protected)
    override suspend fun identities(point:LocalPoint):LocalExtraction {protected();val p=repo.gateway.text(LocalPlaceSources.overtureUrl(point),maxBytes=2L*1024*1024,route=NetworkRoute.TOR);return LocalStructuredData.overture(p.text,p.finalUrl,System.currentTimeMillis())}
    override suspend fun places(query:LocalDiscoveryQuery):LocalExtraction {
        protected()
        val p=repo.gateway.text(LocalPlaceSources.photonUrl(query),maxBytes=512*1024,route=NetworkRoute.TOR)
        if(p.status !in 200..299)throw SourceFailure(if(p.status==429)HealthStatus.RATE_LIMITED else HealthStatus.UNAVAILABLE,"The public name directory is temporarily unavailable")
        return LocalPlaceSources.photon(p.text,System.currentTimeMillis())
    }
    override suspend fun discover(query:LocalDiscoveryQuery):List<LocalWebHit> {
        val hits=mutableListOf<LocalWebHit>();discover(query){synchronized(hits){hits+=it}};return hits.distinctBy {it.url}
    }
    override suspend fun discover(query:LocalDiscoveryQuery,onHits:suspend (List<LocalWebHit>)->Unit) {
        val provider=repo.prefs.searchProvider;val originals=repo.prefs.originalFallback
        fun current()=repo.prefs.searchProvider==provider&&repo.prefs.originalFallback==originals
        repo.awaitReady()
        if(!current())throw CancellationException("The selected search policy changed")
        repo.localWeb.pause(provider,originals)?.let {throw it}
        protected()
        if(!current())throw CancellationException("The selected search policy changed")
        val monitor=Any();var success=false;var failure:Exception?=null
        // Two bounded queries through the selected provider. Deliver and read
        // the first batch without waiting for its slower companion.
        coroutineScope {LocalBusinessRouting.queries(query).map {text->launch {
            try {val hits=withTimeout(22_000){repo.localWeb.search(text,provider,originals,::current)};synchronized(monitor){success=true};onHits(hits)}
            catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();synchronized(monitor){failure=e}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){synchronized(monitor){failure=e}}
        }}.joinAll()}
        if(!success)throw failure ?: SourceFailure(HealthStatus.EMPTY,"No public publisher links were found")
    }
    override suspend fun account(url:String):Boolean {
        repo.awaitConnection()
        val target=repo.routes.recognize(url)?.takeIf {it.kind==TargetKind.PROFILE&&ReaderScope.isFeedSource(it.platform)} ?: return false
        val page=repo.pool.load(target,originalFallback=repo.prefs.originalFallback,requestedCapability=Capability.PROFILE,background=true)
        return LocalAccountAvailability.usable(target,page)
    }
    override suspend fun refine(query:LocalDiscoveryQuery,business:LocalBusiness):List<LocalWebHit> {
        val text=LocalBusinessEnrichment.query(business,query.area) ?: return emptyList()
        val provider=repo.prefs.searchProvider;val originals=repo.prefs.originalFallback
        fun current()=repo.prefs.searchProvider==provider&&repo.prefs.originalFallback==originals
        repo.awaitReady()
        if(!current())throw CancellationException("The selected search policy changed")
        val key="local-refine:v1:"+LocalDiscoveryIdentity.hash(text+provider.id)
        repo.localDiscovery.cache(key)?.let {return gson.fromJson(it,Array<LocalWebHit>::class.java).toList()}
        repo.localWeb.pause(provider,originals)?.let {throw it}
        protected()
        if(!current())throw CancellationException("The selected search policy changed")
        return repo.localWeb.search(text,provider,originals,::current).take(6).also {repo.localDiscovery.cache(key,gson.toJson(it),6*3600000)}
    }
    private val gson=Gson()
    override suspend fun venue(query:LocalDiscoveryQuery,event:LocalEvent):LocalEvent? {
        if(!LocalVenueResolution.eligible(query,event))return null
        val key="local-venue:v1:"+LocalDiscoveryIdentity.hash(query.area.cacheKey+"|"+event.address)
        fun apply(v:Venue)=event.copy(point=v.point,locality=v.locality,evidence=event.evidence+LocalDiscoveryIdentity.evidence("location","${v.point.latitude},${v.point.longitude}",v.sourceUrl,v.checkedAt,LocalEvidenceKind.DIRECTORY,"© OpenStreetMap contributors · ODbL (via Photon)"))
        repo.localDiscovery.cache(key)?.let {raw->return if(raw=="unavailable")null else runCatching {gson.fromJson(raw,Venue::class.java).takeIf {it.point.valid()&&query.area.point.distanceKm(it.point)<=query.area.radiusKm}?.let(::apply)}.getOrNull()}
        protected()
        return try {
            val url=LocalVenueResolution.request(query,event)
            val data=repo.gateway.text(url,maxBytes=256*1024,route=NetworkRoute.TOR)
            if(data.status !in 200..299)throw SourceFailure(HealthStatus.UNAVAILABLE,"Venue directory unavailable")
            val at=System.currentTimeMillis();val result=LocalVenueResolution.parse(data.text,data.finalUrl,at,query.area,event)
            repo.localDiscovery.cache(key,if(result==null)"unavailable"else gson.toJson(Venue(result.point!!,result.locality,data.finalUrl,at)),if(result==null)30*60000 else 7L*86400000)
            result
        }catch(e:CancellationException){throw e}catch(_:Exception){repo.localDiscovery.cache(key,"unavailable",15*60000);null}
    }
    override suspend fun enrich(query:LocalDiscoveryQuery,businesses:List<LocalBusiness>,onData:suspend (LocalExtraction)->Unit) {
        if(businesses.isEmpty()||repo.localDiscovery.cache("atp-rest:v1")!=null)return
        protected()
        try {
            val root=repo.localDiscovery.cache("atp-root:v1") ?: repo.gateway.text(LocalAllThePlaces.INFO,maxBytes=64*1024,route=NetworkRoute.TOR).let {
                LocalAllThePlaces.root(it.text) ?: throw SourceFailure(HealthStatus.EMPTY,"No published place-data run was advertised")
            }.also {repo.localDiscovery.cache("atp-root:v1",it,86400000)}
            val key="atp-catalog:v1:"+LocalDiscoveryIdentity.hash(root)
            val catalog=repo.localDiscovery.cache(key)?.let {gson.fromJson(it,Array<LocalPlaceSlice>::class.java).toList()} ?: repo.gateway.text(root+"stats/_results.json",maxBytes=2*1024*1024,route=NetworkRoute.TOR).let {LocalAllThePlaces.catalog(it.text)}.also {val encoded=gson.toJson(it);if(encoded.length<=256*1024)repo.localDiscovery.cache(key,encoded,86400000)}
            for(slice in LocalAllThePlaces.select(catalog,businesses,query.area)) {
                val url=root+"output/"+slice.spider+".geojson"
                val cached="atp-slice:v1:"+LocalDiscoveryIdentity.hash(url+query.area.cacheKey)
                if(repo.localDiscovery.cache(cached)!=null)continue
                try {
                    val data=repo.gateway.text(url,maxBytes=LocalAllThePlaces.MAX_BYTES,route=NetworkRoute.TOR)
                    onData(LocalAllThePlaces.parse(data.text,data.finalUrl,System.currentTimeMillis(),query.area))
                    repo.localDiscovery.cache(cached,"read",7L*86400000)
                }catch(e:CancellationException){throw e}catch(e:Exception){repo.localDiscovery.cache(cached,"resting",3600000);onData(LocalExtraction(notice="Published ${slice.spider.replace('_',' ')} locations are resting: ${e.message ?: "unavailable"}"))}
            }
        }catch(e:CancellationException){throw e}
        catch(e:Exception){repo.localDiscovery.cache("atp-rest:v1","resting",30*60000);throw e}
    }
    override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
        if(!LocalOpportunityRouting.allowed(source,query))throw SourceFailure(HealthStatus.UNSUPPORTED,"This source covers a different region")
        repo.awaitConnection()
        if(source.kind==LocalSourceKind.LINKED_EVENTS)protected()
        if(source.kind==LocalSourceKind.MENU)return LocalMenuReader(repo).read(source,query,business ?: throw SourceFailure(HealthStatus.EMPTY,"This menu has no matching restaurant location"))
        val target=repo.routes.recognize(source.url)
        if(target!=null&&target.platform!=Platform.RSS) {
            if(!ReaderScope.isFeedSource(target.platform))return LocalExtraction()
            val page=repo.pool.load(target,originalFallback=repo.prefs.originalFallback,requestedCapability=if(target.kind==TargetKind.PROFILE){if(query.mode==LocalSearchMode.EVENTS)Capability.TIMELINE else Capability.PROFILE}else null,background=true)
            val profile=page.profile
            if(!LocalAccountAvailability.usable(target,page))throw SourceFailure(HealthStatus.EMPTY,"This public social page did not provide readable business material")
            val known=business ?: return LocalExtraction(sources=profile?.links.orEmpty().mapNotNull {LocalDiscoveryIdentity.url(it)}.take(4).map {LocalPublisherSource(it,label=profile?.name.orEmpty())})
            if((known.socials+known.related.orEmpty().filter {it.role=="linked account"}.flatMap {it.accounts}).none {url->repo.routes.recognize(url)?.let {it.platform==target.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}==true})throw SourceFailure(HealthStatus.UNSUPPORTED,"This account is not identified as the business account")
            val at=System.currentTimeMillis()
            val description=LocalDocumentWashing.text(profile?.description.orEmpty())
            val posts=if(query.mode==LocalSearchMode.EVENTS)page.posts.take(20).filter(LocalAnnouncements::likely)else emptyList()
            val location=LocalDiscoveryIdentity.name(known.locality)
            val sharedBranch=repo.localDiscovery.businesses(query.area).any {peer->!LocalDiscoveryMerge.sameBusiness(peer,known)&&peer.socials.any {url->
                repo.routes.recognize(url)?.let {it.platform==target.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}==true}}
            val publisherLocationVerified=!sharedBranch&&location.isNotBlank()&&LocalDiscoveryIdentity.name(profile?.name.orEmpty()+" "+description+" "+target.identity).contains(location)
            val announcements=posts.map {LocalAnnouncements.extract(it,known,query.area,at,publisherLocationVerified=publisherLocationVerified)}
            val social=profile?.let {LocalBusinessSocial.profile(known,it,repo.routes,at,sharedBranch)} ?: LocalExtraction(businesses=listOf(known),validEmpty=true)
            return social.copy(events=announcements.flatMap {it.events},announcements=announcements.flatMap {it.announcements},
                sources=(social.sources+posts.flatMap {it.links.orEmpty().take(2)}.mapNotNull {LocalDiscoveryIdentity.url(it)}.distinct().take(8)
                    .map {LocalPublisherSource(it,LocalCalendarPlatforms.kind(it) ?: LocalSourceKind.WEB,label=known.name+" announcement",discoveredFrom=source.url)}).distinctBy {it.url}.take(20),
                posters=posts.filter {post->post.media.any {it.type=="image"}&&announcements[posts.indexOf(post)].events.isEmpty()}.map {LocalPosterSource(it,known,publisherLocationVerified)})
        }
        // Ask for the source's actual representation. Some public HTML
        // publishers return server errors when JSON is preferred to HTML.
        val accept=when(source.kind) {
            LocalSourceKind.CALENDAR->"text/calendar,*/*;q=0.5"
            LocalSourceKind.TRIBE,LocalSourceKind.LOCALIST,LocalSourceKind.LIVEWHALE,LocalSourceKind.SPACEAPI,LocalSourceKind.LINKED_EVENTS->"application/json"
            else->"text/html,application/xhtml+xml,application/ld+json;q=0.9,application/rss+xml;q=0.8,*/*;q=0.5"
        }
        val raw=repo.gateway.extractorDocument(LocalCalendarPlatforms.request(source,query),purpose=if(source.kind in setOf(LocalSourceKind.TRIBE,LocalSourceKind.LOCALIST,LocalSourceKind.LIVEWHALE,LocalSourceKind.SPACEAPI,LocalSourceKind.LINKED_EVENTS))FetchPurpose.API else FetchPurpose.SUPPLEMENTAL,maxBytes=LocalStructuredData.MAX_DOCUMENT.toLong(),headers=mapOf("Accept" to accept),route=if(source.kind==LocalSourceKind.LINKED_EVENTS)NetworkRoute.TOR else NetworkRoute.DEFAULT)
        val payload=Payload(raw.text,raw.finalUrl,raw.headers.entries.firstOrNull {it.key.equals("Content-Type",true)}?.value?.firstOrNull().orEmpty(),raw.status)
        if(payload.status !in 200..299)throw SourceFailure(HealthStatus.UNAVAILABLE,"This publisher is temporarily unavailable",httpStatus=payload.status)
        val at=System.currentTimeMillis();val body=payload.text
        val result=when {
            source.kind==LocalSourceKind.CALENDAR||payload.contentType.contains("text/calendar")||body.trimStart().startsWith("BEGIN:VCALENDAR")->LocalCalendars.parse(body,payload.finalUrl,at,query.area,business).copy(validEmpty=true)
            source.kind==LocalSourceKind.TRIBE->LocalStructuredData.tribe(body,payload.finalUrl,at,query.area,business)
            source.kind==LocalSourceKind.LOCALIST->LocalCalendarPlatforms.localist(body,payload.finalUrl,at,query.area)
            source.kind==LocalSourceKind.LIVEWHALE->LocalOpportunityDocuments.livewhale(body,payload.finalUrl,at,query.area)
            source.kind==LocalSourceKind.SPACEAPI->LocalOpportunityDocuments.space(body,payload.finalUrl,at,query.area)
            source.kind==LocalSourceKind.LINKED_EVENTS->LocalOpportunityDocuments.linkedEvents(body,payload.finalUrl,at,query.area)
            body.trimStart().startsWith("{")&&LocalOpportunityDocuments.isSpaceApi(body)->LocalOpportunityDocuments.space(body,payload.finalUrl,at,query.area)
            payload.contentType.substringBefore(';').trim() in setOf("application/ld+json","application/json")->LocalStructuredData.jsonld(body,payload.finalUrl,at,query.area,business)
            payload.contentType.contains("html")||payload.contentType.contains("xml")||body.trimStart().startsWith("<")-> {
                val data=LocalDocumentWashing.extract(payload,at,query.area,business)
                if(query.mode!=LocalSearchMode.EVENTS)data else {
                    val community=LocalCommunityPages.events(body,payload.finalUrl,at,query.area)
                    val sources=if(community.isEmpty())data.sources else data.sources.filterNot {it.url.toHttpUrlOrNull()?.encodedPath?.contains("/icals.ical")==true}
                    data.copy(events=(data.events+community).distinctBy {it.id},sources=(LocalOpportunityRouting.sources(body,payload.finalUrl,query,at)+sources).distinctBy {it.url}.filter {LocalOpportunityRouting.allowed(it,query)}.take(48))
                }
            }
            else->throw SourceFailure(HealthStatus.UNSUPPORTED,"This publisher did not provide a supported public page or calendar")
        }
        return LocalOpportunityRouting.scoped(source,query,result)
    }
}
internal enum class LocalSearchPhase { IDLE, FINDING, ENRICHING, COMPLETE, STOPPED }
internal data class LocalDiscoveryState(val mode:LocalSearchMode=LocalSearchMode.POSTS,val text:String="",val area:LocalArea?=null,val areas:List<LocalArea> = emptyList(),val unit:LocalDistanceUnit=LocalDistanceUnit.KILOMETRES,
    val window:LocalEventWindow=LocalEventWindow.WEEK,val freeOnly:Boolean=false,val savedOnly:Boolean=false,val businesses:List<LocalBusiness> = emptyList(),val events:List<LocalEvent> = emptyList(),
    val saved:Set<String> = emptySet(),val loading:Boolean=false,val enriching:Boolean=false,val lookingUpArea:Boolean=false,val choices:List<LocalArea> = emptyList(),val message:String?=null,val coverage:List<String> = emptyList(),val checked:Int=0,val more:Boolean=false,val menuLoading:String?=null,
    val announcements:List<LocalAnnouncementCandidate> = emptyList(),val groups:List<LocalBusinessGroup> = emptyList(),val phase:LocalSearchPhase=LocalSearchPhase.IDLE)
internal class LocalDiscoveryController(private val repo:Repository,private val scope:CoroutineScope,internal var backend:LocalDiscoveryBackend=PublicLocalDiscoveryBackend(repo)) {
    private val store=repo.localDiscovery
    private val gson=Gson()
    private val initialAreas=runCatching {LocalAreaCodec.decode(repo.prefs.localAreas)}.getOrDefault(emptyList())
    val state=MutableStateFlow(LocalDiscoveryState(areas=initialAreas,area=initialAreas.firstOrNull {it.id==repo.prefs.localArea} ?: initialAreas.firstOrNull(),unit=repo.prefs.localDistanceUnit))
    private var request:Job?=null;private var lookup:Job?=null;@Volatile private var generation=0
    private var menuRequest:Job?=null
    private var menuGeneration=0
    private val readAdmission=LocalDiscoveryReadAdmission()
    private val accountPermits=Semaphore(2)
    private val enrichmentPermits=Semaphore(1)
    private val detailSearchPermits=Semaphore(1)
    private val publication=Mutex()
    private var published:List<Any?>?=null
    private val availabilityRevision=java.util.concurrent.atomic.AtomicLong()
    private val stopAreaObserver=repo.prefs.observeLocalAreas {scope.launch {
        val areas=runCatching {LocalAreaCodec.decode(repo.prefs.localAreas)}.getOrDefault(emptyList())
        val area=areas.firstOrNull {it.id==repo.prefs.localArea} ?: areas.firstOrNull()
        if(area!=state.value.area)stop()
        state.update {it.copy(areas=areas,area=area,unit=repo.prefs.localDistanceUnit)};publish()
    }}
    init {scope.launch {store.revision.collect {publish()}}}
    private var localFilter:Job?=null
    fun text(value:String){if(request?.isActive==true)stop();state.value=state.value.copy(text=value.take(256),phase=LocalSearchPhase.IDLE);localFilter?.cancel();localFilter=scope.launch {delay(180);publish()}}
    fun mode(value:LocalSearchMode) {stop();state.value=state.value.copy(mode=value,message=null,coverage=emptyList(),phase=LocalSearchPhase.IDLE);scope.launch {publish()}}
    fun unit(value:LocalDistanceUnit){repo.prefs.localDistanceUnit=value;state.update {it.copy(unit=value)}}
    fun area(value:LocalArea) {
        require(value.valid());stop();published=null;val areas=(listOf(value)+state.value.areas.filter {it.id!=value.id}).take(12)
        repo.prefs.localAreas=LocalAreaCodec.encode(areas);repo.prefs.localArea=value.id
        state.value=state.value.copy(area=value,areas=areas,choices=emptyList(),message=null,businesses=emptyList(),groups=emptyList(),events=emptyList(),announcements=emptyList(),phase=LocalSearchPhase.IDLE);scope.launch {publish()}
    }
    fun removeArea(id:String){val areas=state.value.areas.filter {it.id!=id};repo.prefs.localAreas=LocalAreaCodec.encode(areas);if(state.value.area?.id==id){stop();repo.prefs.localArea=areas.firstOrNull()?.id.orEmpty();state.value=state.value.copy(areas=areas,area=areas.firstOrNull(),businesses=emptyList(),groups=emptyList(),events=emptyList(),announcements=emptyList(),phase=LocalSearchPhase.IDLE)}else state.value=state.value.copy(areas=areas);scope.launch {publish()}}
    fun findArea(text:String) {
        if(text.trim().length<2)return
        lookup?.cancel();state.value=state.value.copy(lookingUpArea=true,message=null,choices=emptyList())
        lookup=scope.launch {try {val found=withContext(Dispatchers.IO){backend.areas(text.trim())};state.value=state.value.copy(choices=found,lookingUpArea=false,message=if(found.isEmpty())"No matching area. Include a country or nearby city."else null)}
            catch(e:CancellationException){throw e}catch(e:Exception){state.value=state.value.copy(lookingUpArea=false,message=e.message ?: "Area lookup is unavailable")}}
    }
    fun window(value:LocalEventWindow){state.value=state.value.copy(window=value);scope.launch {publish()}}
    fun free(value:Boolean){state.value=state.value.copy(freeOnly=value);scope.launch {publish()}}
    fun saved(value:Boolean){stop();state.value=state.value.copy(savedOnly=value);scope.launch {publish()}}
    fun stopMenu(){menuGeneration++;menuRequest?.cancel();state.update {it.copy(menuLoading=null)}}
    fun stop(){val running=request?.isActive==true;request?.cancel();stopMenu();generation++;state.value=state.value.copy(loading=false,enriching=false,phase=if(running)LocalSearchPhase.STOPPED else state.value.phase)}
    /** Opening a restaurant can fill its menu without starting another index
     * search. Reuse known location routes and their successful/failure rests. */
    fun menu(value:LocalBusiness,force:Boolean=false) {
        if(!LocalMenuRouting.restaurant(value)||state.value.savedOnly)return
        val area=state.value.area ?: return
        if(menuRequest?.isActive==true&&state.value.menuLoading==value.id)return
        stopMenu();val bound=generation;val menuBound=menuGeneration
        menuRequest=scope.launch {
            val activity=repo.cardWork.begin(listOf(businessWorkKey(value.id)),"Menu")
            state.update {it.copy(menuLoading=value.id)}
            try {withTimeout(35_000) {withContext(Dispatchers.IO) {
                val visited=mutableSetOf<String>()
                repeat(4) {
                    val owner=store.business(value.id) ?: value
                    val known=LocalMenuRouting.known(owner)
                    val routes=(known+if(known.isEmpty())owner.websites.filterNot {LocalBusinessOwnership.directoryLink(owner,it)}.take(1).map {LocalPublisherSource(it,businessId=owner.id,label=owner.name)}else emptyList())
                    val source=routes.filter {it.url !in visited}.sortedBy {LocalMenuRouting.priority(it,owner)}.firstOrNull {candidate->
                        val history=store.cache(LocalBusinessEnrichment.key(candidate))?.let {runCatching {gson.fromJson(it,LocalRouteYield::class.java)}.getOrNull()}
                        force||history==null||history.nextAt<=System.currentTimeMillis()
                    } ?: return@withContext
                    visited+=source.url
                    val old=store.cache(LocalBusinessEnrichment.key(source))?.let {runCatching {gson.fromJson(it,LocalRouteYield::class.java)}.getOrNull()}
                    try {
                        val before=System.currentTimeMillis()
                        val data=withTimeout(14_000){backend.read(source,LocalDiscoveryQuery(area),owner)}
                        if(data.businesses.isEmpty()&&data.sources.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"No readable menu was supplied")
                        currentCoroutineContext().ensureActive();if(bound!=generation)return@withContext
                        store.ingest(data,area)
                        val observed=LocalBusinessEnrichment.observe(old,data,System.currentTimeMillis()-before,System.currentTimeMillis())
                        store.cache(LocalBusinessEnrichment.key(source),gson.toJson(observed),14L*86400000)
                        store.source(area,source.copy(checkedAt=observed.checkedAt,nextCheckAt=observed.nextAt))
                        publish()
                        if(data.businesses.flatMap {it.menus.orEmpty()}.any {m->m.title!="Menu highlights"&&(m.items.isNotEmpty()||m.text.isNotBlank())&&(m.sourceUrl==source.url||m.checkedAt>=before)})return@withContext
                    }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();store.cache(LocalBusinessEnrichment.key(source),gson.toJson(LocalBusinessEnrichment.failed(old,System.currentTimeMillis())),14L*86400000)}
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){store.cache(LocalBusinessEnrichment.key(source),gson.toJson(LocalBusinessEnrichment.failed(old,System.currentTimeMillis())),14L*86400000)}
                }
            }}}catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive()}
            catch(e:CancellationException){throw e}
            finally {activity.close();if(bound==generation&&menuBound==menuGeneration)state.update {it.copy(menuLoading=null)}}
        }
    }
    fun close(){stopAreaObserver();stop();lookup?.cancel();repo.localSearchConnection(false)}
    suspend fun publish()=publication.withLock {
        // Page/account completions and the store observer can arrive together.
        // Serialize projections so an older read cannot replace a newer deck.
        val snapshot=state.value;if(snapshot.mode==LocalSearchMode.POSTS)return@withLock;val area=snapshot.area;val saved=snapshot.savedOnly
        val fingerprint=listOf(store.revision.value,availabilityRevision.get(),snapshot.mode,area,snapshot.text,snapshot.window,snapshot.freeOnly,saved,System.currentTimeMillis()/60000)
        if(fingerprint==published)return@withLock
        val result=withContext(Dispatchers.IO) {
            if(!saved&&area!=null){if(snapshot.mode==LocalSearchMode.BUSINESSES)store.reconcile(area)else store.reconcileEvents()}
            val businesses=if(snapshot.mode!=LocalSearchMode.BUSINESSES)emptyList()else store.businesses(if(saved)null else area,saved).map {it to LocalBusinessRouting.relevance(snapshot.text,it)}.filter {it.second<9}
                .sortedWith(compareBy<Pair<LocalBusiness,Int>> {it.second}.thenBy {area?.point?.let {p->it.first.point?.let(p::distanceKm)} ?: Double.MAX_VALUE}.thenBy {it.first.name}).take(160)
                .map {(business,_)->business.copy(socials=business.socials.filter {store.cache(accountKey(it))=="usable"},related=business.related?.map {it.copy(accounts=it.accounts.filter {url->store.cache(accountKey(url))=="usable"})})}
            val query=area?.let {LocalDiscoveryQuery(it,snapshot.text,snapshot.mode,snapshot.window,saved,snapshot.freeOnly)}
            val eventIntent=LocalEventIntent.parse(snapshot.text)
            val announcementAdmission=LocalEventIntent(emptyList(),free=snapshot.freeOnly||eventIntent.free)
            val events=if(snapshot.mode!=LocalSearchMode.EVENTS)emptyList()else store.events(saved).filter {(saved||query!=null&&LocalDiscoveryFilter.event(query,it))&&eventIntent.match(it).matches}
                .sortedBy {it.start.instant()?.toEpochMilli() ?: it.start.date()?.atStartOfDay(java.time.ZoneId.of(area?.zoneId ?: "UTC"))?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE}.take(160)
            Triple(businesses,events,store.savedIds()) to (if(snapshot.mode!=LocalSearchMode.EVENTS||saved||area==null)emptyList()else store.announcements(area).filter {candidate->(candidate.date?.let {runCatching {java.time.LocalDate.parse(it)}.getOrNull()}?.let {date->val range=query!!.dates();date>=range.first&&date<=range.second} ?: true)&&eventIntent.matchText(candidate.title+" "+candidate.text,linkOnly=true).matches&&announcementAdmission.matchText(candidate.title+" "+candidate.text).matches})
        }
        var accepted=false
        state.update {current->if(current.mode==snapshot.mode&&current.area==snapshot.area&&current.text==snapshot.text&&current.savedOnly==saved&&current.window==snapshot.window&&current.freeOnly==snapshot.freeOnly) {
            accepted=true
            val records=result.first
            val positions=current.businesses.mapIndexed {i,b->b.id to i}.toMap()
            val aliases=current.businesses.flatMapIndexed {i,b->b.sourceIds.map {it to i}}.toMap()
            val stable=records.first.withIndex().sortedBy {(i,b)->positions[b.id] ?: b.sourceIds.mapNotNull {aliases[it]}.minOrNull() ?: (positions.size+i)}.map {it.value}
            val oldGroups=current.groups.flatMap {group->group.locations.map {it.id to group.id}}.toMap()
            val assigned=mutableSetOf<String>()
            val groups=LocalBusinessDeck.groups(stable).map {group->
                val key=group.locations.mapNotNull {oldGroups[it.id]}.firstOrNull {it !in assigned} ?: group.id.takeUnless {it in assigned} ?: group.id+":"+group.primary.id
                assigned+=key;group.copy(id=key)
            }
            current.copy(businesses=stable,groups=groups,events=records.second,saved=records.third,announcements=result.second)
        }else {accepted=false;current}}
        if(accepted)published=fingerprint
    }
    internal fun accountKey(url:String):String {
        val identity=repo.routes.recognize(url)?.let {ContentIdentity.key(it.platform,it.canonicalUrl)} ?: url.trimEnd('/')
        return "local-account:v2:${repo.prefs.tor}:${repo.prefs.originalFallback}:${repo.prefs.archiveRecovery}:"+LocalDiscoveryIdentity.hash(identity)
    }
    fun save(business:LocalBusiness){scope.launch {try {withContext(Dispatchers.IO){if(business.id in store.savedIds())store.removeSaved(business.id)else store.save(business)}}catch(e:Exception){state.value=state.value.copy(message=e.message)}}}
    fun save(group:LocalBusinessGroup){scope.launch {try {withContext(Dispatchers.IO){val ids=store.savedIds();val remove=group.locations.all {it.id in ids};group.locations.forEach {if(remove)store.removeSaved(it.id)else if(it.id !in ids)store.save(it)}}}catch(e:Exception){state.update {it.copy(message=e.message)}}}}
    fun save(event:LocalEvent){scope.launch {try {withContext(Dispatchers.IO){if(event.id in store.savedIds())store.removeSaved(event.id)else store.save(event)}}catch(e:Exception){state.value=state.value.copy(message=e.message)}}}
    fun importPlaces(body:String){val area=state.value.area ?: return;scope.launch {try {val count=withContext(Dispatchers.IO){val data=if(body.contains("\"@spider\""))LocalAllThePlaces.parse(body,"https://alltheplaces.xyz/",System.currentTimeMillis(),area)else LocalStructuredData.overture(body,"https://docs.overturemaps.org/guides/places/",System.currentTimeMillis());store.ingest(data,area).first};state.value=state.value.copy(message="Added $count place records in this area.");publish()}catch(e:Exception){state.value=state.value.copy(message=e.message ?: "Could not read this place pack")}}}
    fun search(force:Boolean=false,more:Boolean=false) {
        val snapshot=state.value
        if(snapshot.mode==LocalSearchMode.POSTS)return
        val area=snapshot.area ?: run {state.value=snapshot.copy(message="Choose an area to find businesses and events.");return}
        if(snapshot.savedOnly){scope.launch {publish()};return}
        stop();val bound=generation
        val intent=LocalEventIntent.parse(snapshot.text)
        val topic=snapshot.text.trim()
        val chosenWindow=if(snapshot.mode==LocalSearchMode.EVENTS)intent.window ?: snapshot.window else snapshot.window
        val query=LocalDiscoveryQuery(area,topic,snapshot.mode,chosenWindow,freeOnly=snapshot.freeOnly)
        state.value=state.value.copy(loading=true,enriching=false,message=null,coverage=emptyList(),checked=0,more=false,window=chosenWindow,phase=LocalSearchPhase.FINDING)
        // Begin the already-required private transport while cached records
        // are prepared, rather than waiting for that local work to finish.
        repo.localSearchConnection(true)
        request=scope.launch {repo.gateway.discoveryRead {
            fun current()=generation==bound
            val deckReady=CompletableDeferred<Unit>();val initialDone=CompletableDeferred<Unit>()
            val detailRevision=java.util.concurrent.atomic.AtomicLong()
            val posters=java.util.concurrent.ConcurrentLinkedQueue<LocalPosterSource>()
            fun ready() {if(current()&&(if(query.mode==LocalSearchMode.BUSINESSES)state.value.businesses.isNotEmpty()else state.value.events.isNotEmpty())) {
                deckReady.complete(Unit);state.update {it.copy(loading=false,enriching=true,phase=LocalSearchPhase.ENRICHING)}
            }}
            fun note(value:String){if(current())state.update {it.copy(coverage=(it.coverage+value).distinct().takeLast(8))}}
            suspend fun ingest(data:LocalExtraction) {currentCoroutineContext().ensureActive();if(!current())return;withContext(Dispatchers.IO){store.ingest(data,area,::current)};if(!current())return;posters.addAll(data.posters.take(20));detailRevision.incrementAndGet();data.notice?.let(::note);publish();ready()}
            suspend fun lane(label:String,action:suspend ()->Unit) {try {action()}catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();note("$label: timed out; cached material remains available.")}catch(e:CancellationException){throw e}catch(e:Exception){note("$label: ${e.message ?: "unavailable"}")}}
            try {
                publish()
                ready()
                withContext(Dispatchers.IO) {
                    val identityChecks=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val visited=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val accountChecks=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val currentSources=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val detailSources=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val reads=java.util.concurrent.atomic.AtomicInteger();val attempts=java.util.concurrent.atomic.AtomicInteger()
                    val initialAttempts=java.util.concurrent.atomic.AtomicInteger();val deepAttempts=java.util.concurrent.atomic.AtomicInteger()
                    val accountAttempts=java.util.concurrent.atomic.AtomicInteger()
                    val enriched=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val refined=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val sourceBudget=if(query.mode==LocalSearchMode.EVENTS&&repo.prefs.localFlyerReading)16 else 18
                    fun claim(counter:java.util.concurrent.atomic.AtomicInteger,limit:Int):Boolean {
                        while(true){val count=counter.get();if(count>=limit)return false;if(counter.compareAndSet(count,count+1))return true}
                    }
                    val venueAttempts=java.util.concurrent.atomic.AtomicInteger()
                    val venueChecks=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    fun relevant():List<LocalBusiness> {
                        val intent=if(query.mode==LocalSearchMode.EVENTS)LocalEventIntent.parse(query.text)else null
                        return store.businesses(area).map {it to LocalBusinessRouting.relevance(query.text,it)}.filter {query.mode==LocalSearchMode.EVENTS||it.second<9}
                            .sortedWith(compareBy<Pair<LocalBusiness,Int>> {intent?.publisherAffinity(it.first) ?: 0}.thenBy {it.second}.thenBy {it.first.point?.distanceKm(area.point) ?: 1e9}).map {it.first}
                    }
                    fun queueRelevant() {
                        if(!current())return
                        val sources=mutableListOf<LocalPublisherSource>()
                        relevant().take(8).forEach {owner->
                            sources+=owner.websites.sortedBy {LocalBusinessDetails.websitePriority(owner,it)}.take(2).map {LocalPublisherSource(it,businessId=owner.id,label=owner.name)}
                            if(query.mode==LocalSearchMode.BUSINESSES)sources+=LocalMenuRouting.known(owner)
                            sources+=LocalBusinessSocial.sources(owner).take(if(query.mode==LocalSearchMode.EVENTS)1 else 2)
                        }
                        store.offerSources(area,sources)
                    }
                    suspend fun checkAccounts()=coroutineScope {
                        val accountOwners=relevant().take(12)
                        val perBusiness=accountOwners.map {owner->(owner.socials+owner.related.orEmpty().filter {it.role!="source publisher"}.flatMap {it.accounts})
                            .distinctBy(::accountKey).sortedBy {url->if(url.toHttpUrlOrNull()?.pathSegments?.firstOrNull()?.all(Char::isDigit)==true)1 else 0}}
                        // Check one account per business before spending the
                        // entire budget on one business's platform/ID aliases.
                        val urls=(0 until (perBusiness.maxOfOrNull {it.size} ?: 0)).flatMap {i->perBusiness.mapNotNull {it.getOrNull(i)}}.distinctBy(::accountKey)
                        urls.map {url->launch {
                            val key=accountKey(url)
                            val owners=accountOwners.filter {owner->url in owner.socials||owner.related.orEmpty().any {url in it.accounts}}
                            val needsProjection=query.mode==LocalSearchMode.BUSINESSES&&owners.any {url in it.socials||it.related.orEmpty().any {e->e.role=="linked account"&&url in e.accounts}}
                            val available=store.cache(key)
                            val projectionKey="$key:business-facts:v1"
                            if(url in visited||available!=null&&(available!="usable"||!needsProjection||store.cache(projectionKey)!=null)||!accountChecks.add(key)||accountAttempts.incrementAndGet()>8)return@launch
                            repo.cardWork.track(owners.map {businessWorkKey(it.id)},"Social accounts") {accountPermits.withPermit {
                                val owner=owners.firstOrNull {url in it.socials||it.related.orEmpty().any {e->e.role=="linked account"&&url in e.accounts}}.takeIf {query.mode==LocalSearchMode.BUSINESSES}
                                val social=owner?.let {LocalPublisherSource(url,LocalSourceKind.SOCIAL,it.id,it.name)}
                                if(social!=null&&!visited.add(url))return@withPermit
                                fun unavailable(ttl:Long) {
                                    store.cache(key,"unavailable",ttl)
                                    social?.let {store.source(area,it.copy(nextCheckAt=System.currentTimeMillis()+ttl,failures=1))}
                                }
                                try {
                                    val usable=withTimeout(16_000) {
                                        if(owner==null)backend.account(url)else {
                                            val source=social!!
                                            val data=backend.read(source,query,owner)
                                            ingest(data);data.sources.forEach {currentSources.add(it.url)}
                                            val at=System.currentTimeMillis()
                                            store.source(area,source.copy(checkedAt=at,nextCheckAt=at+6*3600000))
                                            store.cache(projectionKey,"read",6*3600000)
                                            true
                                        }
                                    }
                                    store.cache(key,if(usable)"usable"else "unavailable",if(usable)6*3600000 else 30*60000)
                                }
                                catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();unavailable(2L*60000)}
                                catch(e:CancellationException){throw e}
                                catch(_:Exception){unavailable(10L*60000)}
                                availabilityRevision.incrementAndGet()
                                if(store.cache(key)=="unavailable")note("Some published social accounts are unavailable from the current public routes. Their source records are retained for a later retry.")
                                if(current())publish()
                            }}
                        }}.joinAll()
                    }
                    fun history(source:LocalPublisherSource)=store.cache(LocalBusinessEnrichment.key(source))?.let {runCatching {gson.fromJson(it,LocalRouteYield::class.java)}.getOrNull()}
                    suspend fun readSources(limit:Int,deep:Boolean=false) {
                        val owners=store.businesses(area).associateBy {it.id}
                        val candidates=store.sources(area).filter {source->
                            source.url !in visited&&(source.kind!=LocalSourceKind.SOCIAL||query.mode==LocalSearchMode.EVENTS||owners[source.businessId]==null)&&LocalOpportunityRouting.allowed(source,query)&&(source.kind!=LocalSourceKind.MENU||deep&&query.mode==LocalSearchMode.BUSINESSES&&owners[source.businessId]?.let {b->LocalMenuRouting.known(b).any {it.url==source.url}}==true)&&(force||source.nextCheckAt<=System.currentTimeMillis()||source.failures==0&&LocalCalendarPlatforms.window(source,query)?.let {it!=source.window}==true)&&
                                (source.url in currentSources||source.businessId?.let {owners[it]?.let {p->query.mode==LocalSearchMode.EVENTS||LocalBusinessRouting.relevance(query.text,p)<9}}==true||query.mode==LocalSearchMode.EVENTS&&LocalCalendarPlatforms.isCalendar(source))
                        }.sortedBy {LocalBusinessRouting.sourcePriority(it,query,owners[it.businessId],currentSources)+LocalBusinessEnrichment.priority(it,owners[it.businessId],history(it))-(if(deep&&it.url in detailSources)30 else 0)}.take(limit)
                        coroutineScope {candidates.map {source->launch {repo.cardWork.track(listOfNotNull(source.businessId?.let(::businessWorkKey))+localSourceWorkKeys(source.url),"Public details") {readAdmission.read({!deep&&!deckReady.isCompleted}) sourceRead@{
                            if(!visited.add(source.url))return@sourceRead
                            val phase=if(deep)deepAttempts else initialAttempts
                            val phaseLimit=if(deep)10 else if(!deckReady.isCompleted)sourceBudget else 8
                            if(!claim(phase,phaseLimit)){visited.remove(source.url);return@sourceRead}
                            if(!claim(attempts,sourceBudget)){phase.decrementAndGet();visited.remove(source.url);return@sourceRead}
                            lane(source.label.ifBlank {"Public page"}) {
                                val before=System.currentTimeMillis();val previous=history(source)
                                try {
                                    val data=withTimeout(18_000){backend.read(source,query,owners[source.businessId])}
                                    if(data.businesses.isEmpty()&&data.events.isEmpty()&&data.sources.isEmpty()&&!data.validEmpty)throw SourceFailure(HealthStatus.EMPTY,"No readable business or calendar data")
                                    if(source.kind==LocalSourceKind.SOCIAL&&source.businessId!=null)store.cache(accountKey(source.url),"usable",6*3600000)
                                    if(current())data.events.filter {LocalDiscoveryFilter.event(query,it)}.take(24).forEach {repo.localLinks.rememberReadable(source.url,source.label,it)}
                                    ingest(data)
                                    if(query.mode==LocalSearchMode.EVENTS) {
                                        val places=store.businesses(area)
                                        val resolved=mutableListOf<LocalEvent>()
                                        for(event in data.events) {
                                            if(!LocalVenueResolution.eligible(query,event))continue
                                            val known=LocalVenueResolution.known(area,event,places)
                                            if(known!=null){resolved+=known;continue}
                                            // An already admitted town-only event does not
                                            // need another lookup to appear. Resolve only
                                            // uncertain neighboring-town addresses, twice
                                            // per search, within the shared traffic cap.
                                            if(LocalDiscoveryFilter.geographic(area,event)||!venueChecks.add(event.address)||!claim(venueAttempts,2)||!claim(attempts,sourceBudget))continue
                                            try {withTimeout(8_000){backend.venue(query,event)}?.let {resolved+=it}}
                                            catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive()}
                                            catch(e:CancellationException){throw e}
                                            catch(_:Exception){}
                                        }
                                        if(resolved.isNotEmpty())ingest(LocalExtraction(events=resolved))
                                    }
                                    // Contact/calendars explicitly advertised by the page
                                    // can be read in this same bounded request.
                                    data.sources.forEach {currentSources.add(it.url)}
                                    val observed=LocalBusinessEnrichment.observe(previous,data,System.currentTimeMillis()-before,System.currentTimeMillis(),calendar=query.mode==LocalSearchMode.EVENTS)
                                    store.cache(LocalBusinessEnrichment.key(source),gson.toJson(observed),14L*86400000)
                                    store.source(area,source.copy(checkedAt=observed.checkedAt,nextCheckAt=observed.nextAt,failures=0,window=LocalCalendarPlatforms.window(source,query)))
                                }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();val failed=LocalBusinessEnrichment.failed(previous,System.currentTimeMillis());store.cache(LocalBusinessEnrichment.key(source),gson.toJson(failed),14L*86400000);store.source(area,source.copy(nextCheckAt=failed.nextAt,failures=failed.failures));throw SourceFailure(HealthStatus.UNAVAILABLE,"This source timed out")}
                                catch(e:CancellationException){throw e}
                                catch(e:Exception){val failed=LocalBusinessEnrichment.failed(previous,System.currentTimeMillis());store.cache(LocalBusinessEnrichment.key(source),gson.toJson(failed),14L*86400000);store.source(area,source.copy(nextCheckAt=failed.nextAt,failures=failed.failures));throw e}
                                finally {val read=reads.incrementAndGet();if(current())state.update {it.copy(checked=read)}}
                            }
                        }}}}.joinAll()}
                    }
                    suspend fun overtureAt(point:LocalPoint)=lane("Overture business links") {
                        val key="overture:${kotlin.math.round(point.latitude*1000)},${kotlin.math.round(point.longitude*1000)}"
                        if(identityChecks.size<3&&identityChecks.add(key)&&(force||store.cache(key)==null)) {
                            val data=withTimeout(35_000){backend.identities(point)};ingest(data);store.cache(key,"ok",if(data.businesses.isEmpty())6*3600000 else 14L*86400000)
                            queueRelevant();readSources(3)
                        }
                    }
                    queueRelevant()
                    LocalOpportunityRouting.seeds(query).forEach {source->store.offerSource(area,source);currentSources.add(source.url)}
                    coroutineScope {
                      launch {try {coroutineScope {
                        launch {readSources(3)}
                        if(!more)launch {overtureAt(area.point)}
                        if(!more&&query.text.length>=2)launch {lane("Public name search") {
                            val key="photon:v2:"+LocalDiscoveryIdentity.hash(area.cacheKey+LocalDiscoveryFilter.normalizedQuery(query.text))
                            if(force||store.cache(key)==null) {
                                val data=withTimeout(18_000){backend.places(query)};ingest(data);store.cache(key,"ok",6*3600000)
                                // A precise result supplies a geographic bridge to
                                // Overture's websites/socials outside the city centre.
                                coroutineScope {relevant().mapNotNull {it.point}.take(2).map {point->launch {overtureAt(point)}}.joinAll()}
                            }
                        }}
                        if(!more)launch {lane("Place directory") {
                            val version=if(snapshot.mode==LocalSearchMode.EVENTS)4 else 5
                            val key="directory:v$version:${area.cacheKey}:${snapshot.mode==LocalSearchMode.EVENTS}:${LocalDiscoveryIdentity.hash(PlaceSearchVocabulary.parse(query.text).fingerprint)}"
                            if(force||store.cache(key)==null){ingest(withTimeout(50_000){backend.directory(query)});store.cache(key,"ok",86400000)}
                            queueRelevant();readSources(3)
                        }}
                        if(!more)launch {lane("Publisher search") {
                            val key="search:v4:"+LocalDiscoveryIdentity.hash(PlaceSearchVocabulary.parse(query.text).fingerprint+query.toString()+repo.prefs.searchProvider.id+LocalBusinessRouting.queries(query).joinToString("\n"))
                            suspend fun accept(hits:List<LocalWebHit>) {
                                val hosts=mutableMapOf<String,Int>()
                                val offered=mutableListOf<LocalPublisherSource>()
                                hits.take(24).forEach {hit->
                                    val url=LocalDiscoveryIdentity.url(hit.url) ?: return@forEach
                                    val host=url.toHttpUrlOrNull()?.host ?: return@forEach
                                    if(hosts.getOrDefault(host,0)>=3)return@forEach
                                    hosts[host]=hosts.getOrDefault(host,0)+1;currentSources.add(url)
                                    val target=repo.routes.recognize(url)
                                    val source=LocalPublisherSource(url,if(target!=null)LocalSourceKind.SOCIAL else LocalCalendarPlatforms.kind(url) ?: LocalSourceKind.WEB,label=hit.title)
                                    if(LocalOpportunityRouting.allowed(source,query))offered+=source
                                }
                                store.offerSources(area,offered)
                                while(current()) {
                                    val before=attempts.get()
                                    readSources(6)
                                    // Unused enrichment capacity can find the
                                    // first card; the total request cap stays.
                                    if(deckReady.isCompleted||attempts.get()>=sourceBudget||attempts.get()<=before)break
                                }
                            }
                            val cached=if(force)null else store.cache(key)?.let {runCatching {gson.fromJson(it,Array<LocalWebHit>::class.java).toList()}.getOrNull()}
                            if(cached!=null)accept(cached)else {
                                val all=java.util.Collections.synchronizedList(mutableListOf<LocalWebHit>())
                                withTimeout(50_000){backend.discover(query){hits->all.addAll(hits);accept(hits)}}
                                store.cache(key,gson.toJson(all.distinctBy {it.url}.take(32)),30*60000)
                            }
                        }}
                      }}finally {initialDone.complete(Unit)}}
                      launch {
                        while(!deckReady.isCompleted&&!initialDone.isCompleted)delay(100)
                        if(!deckReady.isCompleted)return@launch
                        // A usable deck starts its own details immediately. Slow
                        // independent discovery must not hold every card hostage.
                        // Later discoveries trigger another bounded pass.
                        withTimeoutOrNull(70_000) {
                          while(repo.gateway.hasForegroundRequests)delay(200)
                          try {repo.gateway.deferredRead {
                           var lastRevision=-1L
                           while(current()) {
                            val revision=detailRevision.get()
                            if(revision==lastRevision) {
                                if(initialDone.isCompleted)break
                                delay(150);continue
                            }
                            lastRevision=revision
                            val owners=relevant().take(8)
                            queueRelevant()
                            coroutineScope {
                              // Account validation has its own two slots and
                              // starts with enrichment, rather than waiting
                              // behind every slow supplemental page read.
                              launch {checkAccounts()}
                              launch {readSources(2,deep=true)}
                              val fresh=owners.filter {enriched.size<8&&enriched.add(it.id)}
                              if(query.mode==LocalSearchMode.BUSINESSES&&fresh.isNotEmpty())launch {repo.cardWork.track(fresh.map {businessWorkKey(it.id)},"Business details"){enrichmentPermits.withPermit {lane("Published business locations") {
                                withTimeout(35_000){backend.enrich(query,fresh){data->ingest(data);queueRelevant()}}
                              }}}}
                              if(query.mode==LocalSearchMode.BUSINESSES&&query.text.isNotBlank())launch {
                                owners.filter {LocalBusinessEnrichment.query(it,area)!=null}.distinctBy {LocalDiscoveryMerge.businessName(it.name)}.filter {refined.size<2&&refined.add(LocalDiscoveryMerge.businessName(it.name))}.forEach {owner->
                                  lane("Additional business details") {
                                    val hits=repo.cardWork.track(listOf(businessWorkKey(owner.id)),"Business details"){detailSearchPermits.withPermit {withTimeout(18_000){backend.refine(query,owner)}}}
                                    hits.take(4).forEach {hit->
                                      val url=LocalDiscoveryIdentity.url(hit.url) ?: return@forEach
                                      if(url in visited)return@forEach
                                      val host=url.toHttpUrlOrNull()?.host
                                      val associated=owner.websites.any {it.toHttpUrlOrNull()?.host==host&&!LocalBusinessOwnership.directoryLink(owner,it)}
                                      currentSources.add(url)
                                      detailSources.add(url)
                                      store.offerSource(area,LocalPublisherSource(url,if(repo.routes.recognize(url)!=null)LocalSourceKind.SOCIAL else LocalSourceKind.WEB,owner.id.takeIf {associated},hit.title))
                                    }
                                    readSources(3,deep=true)
                                  }
                                }
                              }
                            }
                            queueRelevant();readSources(5,deep=true);checkAccounts()
                           }
                          }}catch(e:CancellationException){currentCoroutineContext().ensureActive()}
                        }
                      }
                    }
                    if(query.mode==LocalSearchMode.EVENTS&&repo.prefs.localFlyerReading) {
                        posters.toList().distinctBy {it.post.canonicalUrl}.sortedBy(LocalAnnouncements::posterPriority).take(2).forEach {task->
                            if(current()&&claim(attempts,18))lane("Reading public flyer") {
                                repo.gateway.deferredRead {ingest(backend.poster(task.copy(refresh=force),query))}
                            }
                        }
                    }
                    if(current())state.value=state.value.copy(more=store.sources(area).any {it.nextCheckAt<=System.currentTimeMillis()&&it.url !in visited&&it.url in currentSources})
                }
                publish()
            }finally {if(current())state.value=state.value.copy(loading=false,enriching=false,phase=LocalSearchPhase.COMPLETE)}
        }}
    }
}
