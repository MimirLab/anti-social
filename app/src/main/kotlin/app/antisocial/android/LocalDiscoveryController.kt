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
}
internal class PublicLocalDiscoveryBackend(private val repo:Repository):LocalDiscoveryBackend {
    private data class Venue(val point:LocalPoint,val locality:String,val sourceUrl:String,val checkedAt:Long)
    private suspend fun protected() {
        repo.awaitReady();repo.localSearchConnection(true)
        try {withTimeout(60_000){repo.tor.state.first {it.enabled&&it.ready}}}
        catch(e:TimeoutCancellationException){throw SourceFailure(HealthStatus.UNAVAILABLE,"Waiting for a private search connection. No direct fallback was used.")}
    }
    override suspend fun areas(text:String):List<LocalArea> {protected();val p=repo.gateway.text(LocalPlaceSources.geocodeUrl(text),maxBytes=256*1024,route=NetworkRoute.TOR);return LocalPlaceSources.areas(p.text)}
    override suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction {
        protected()
        var last:Exception?=null
        for(endpoint in listOf("https://overpass.private.coffee/api/interpreter","https://overpass-api.de/api/interpreter")) {
            val key="directory-rest:"+endpoint
            if(repo.localDiscovery.cache(key)!=null)continue
            try {
                val u=LocalPlaceSources.overpassUrl(query.area,endpoint,hubs=query.mode==LocalSearchMode.EVENTS,topic=query.text)
                val p=withTimeout(24_000){repo.gateway.extractorDocument(u,maxBytes=2L*1024*1024,route=NetworkRoute.TOR,headers=mapOf("Accept" to "application/json","User-Agent" to "AntiSocial/0.35.39 (accountless reader; cached regional discovery)"))}
                if(p.status==429) {repo.localDiscovery.cache(key,"rate limited",30*60000);throw SourceFailure(HealthStatus.RATE_LIMITED,"The place directory requested a pause")}
                if(p.status !in 200..299)throw SourceFailure(HealthStatus.UNAVAILABLE,"The place directory is unavailable")
                return LocalPlaceSources.overpass(p.text,"https://www.openstreetmap.org",System.currentTimeMillis())
            }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();last=e;repo.localDiscovery.cache(key,"timed out",15*60000)}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if((e as? SourceFailure)?.status==HealthStatus.RATE_LIMITED)throw e;last=e;repo.localDiscovery.cache(key,"unavailable",15*60000)}
        }
        throw SourceFailure(HealthStatus.UNAVAILABLE,last?.message ?: "Place directories are resting after an unavailable response; cached discoveries remain available")
    }
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
        protected()
        val index=LocalWebDiscovery(repo.gateway,repo.routes)
        val key="local-index-rest:${repo.prefs.searchProvider.id}"
        if(repo.localDiscovery.cache(key)!=null)throw SourceFailure(HealthStatus.UNAVAILABLE,"The selected search source is resting after an unavailable response")
        var success=false;var failure:Exception?=null
        // Two bounded queries through the selected provider. Deliver and read
        // the first batch without waiting for its slower companion.
        coroutineScope {LocalBusinessRouting.queries(query).map {text->launch {
            try {val hits=withTimeout(22_000){index.search(text,repo.prefs.searchProvider,repo.prefs.originalFallback)};synchronized(index){success=true};onHits(hits)}
            catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();synchronized(index){failure=e}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){synchronized(index){failure=e};if((e as? SourceFailure)?.status in setOf(HealthStatus.CHALLENGE,HealthStatus.RATE_LIMITED))repo.localDiscovery.cache(key,"paused",30*60000)}
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
        protected()
        if(repo.localDiscovery.cache("local-index-rest:${repo.prefs.searchProvider.id}")!=null)return emptyList()
        val key="local-refine:v1:"+LocalDiscoveryIdentity.hash(text+repo.prefs.searchProvider.id)
        repo.localDiscovery.cache(key)?.let {return gson.fromJson(it,Array<LocalWebHit>::class.java).toList()}
        return try {LocalWebDiscovery(repo.gateway,repo.routes).search(text,repo.prefs.searchProvider,repo.prefs.originalFallback).take(6).also {repo.localDiscovery.cache(key,gson.toJson(it),6*3600000)}}
        catch(e:CancellationException){throw e}
        catch(e:SourceFailure){if(e.status in setOf(HealthStatus.CHALLENGE,HealthStatus.RATE_LIMITED))repo.localDiscovery.cache("local-index-rest:${repo.prefs.searchProvider.id}","paused",30*60000);throw e}
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
            val page=repo.pool.load(target,originalFallback=repo.prefs.originalFallback,requestedCapability=if(target.kind==TargetKind.PROFILE)Capability.PROFILE else null,background=true)
            val profile=page.profile ?: throw SourceFailure(HealthStatus.EMPTY,"This public social page did not provide business details")
            val known=business ?: return LocalExtraction(sources=profile.links.orEmpty().mapNotNull {LocalDiscoveryIdentity.url(it)}.take(4).map {LocalPublisherSource(it,label=profile.name)})
            if(source.url !in known.socials)throw SourceFailure(HealthStatus.UNSUPPORTED,"This account is not identified as the business account")
            val links=profile.links.orEmpty().mapNotNull {LocalDiscoveryIdentity.url(it)}
            val at=System.currentTimeMillis()
            val description=LocalDocumentWashing.text(profile.description)
            return LocalExtraction(businesses=listOf(known.copy(description=description.ifBlank {known.description},
                websites=(known.websites+links.filter {SocialLinkHosts.platform(it)==null}).distinct().take(12),socials=(known.socials+profile.target.canonicalUrl).distinct().take(24),checkedAt=at,
                evidence=known.evidence+LocalDiscoveryIdentity.evidence("description",description,profile.target.canonicalUrl,at,LocalEvidenceKind.PUBLIC_POST))))
        }
        // Ask for the source's actual representation. Some public HTML
        // publishers return server errors when JSON is preferred to HTML.
        val accept=when(source.kind) {
            LocalSourceKind.CALENDAR->"text/calendar,*/*;q=0.5"
            LocalSourceKind.TRIBE,LocalSourceKind.LOCALIST,LocalSourceKind.LIVEWHALE,LocalSourceKind.SPACEAPI,LocalSourceKind.LINKED_EVENTS->"application/json"
            else->"text/html,application/xhtml+xml,application/ld+json;q=0.9,application/rss+xml;q=0.8,*/*;q=0.5"
        }
        val raw=repo.gateway.extractorDocument(LocalCalendarPlatforms.request(source,query),maxBytes=LocalStructuredData.MAX_DOCUMENT.toLong(),headers=mapOf("Accept" to accept),route=if(source.kind==LocalSourceKind.LINKED_EVENTS)NetworkRoute.TOR else NetworkRoute.DEFAULT)
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
internal data class LocalDiscoveryState(val mode:LocalSearchMode=LocalSearchMode.POSTS,val text:String="",val area:LocalArea?=null,val areas:List<LocalArea> = emptyList(),val unit:LocalDistanceUnit=LocalDistanceUnit.KILOMETRES,
    val window:LocalEventWindow=LocalEventWindow.WEEK,val freeOnly:Boolean=false,val savedOnly:Boolean=false,val businesses:List<LocalBusiness> = emptyList(),val events:List<LocalEvent> = emptyList(),
    val saved:Set<String> = emptySet(),val loading:Boolean=false,val enriching:Boolean=false,val lookingUpArea:Boolean=false,val choices:List<LocalArea> = emptyList(),val message:String?=null,val coverage:List<String> = emptyList(),val checked:Int=0,val more:Boolean=false,val menuLoading:String?=null)
internal class LocalDiscoveryController(private val repo:Repository,private val scope:CoroutineScope,internal var backend:LocalDiscoveryBackend=PublicLocalDiscoveryBackend(repo)) {
    private val store=repo.localDiscovery
    private val gson=Gson()
    private val initialAreas=runCatching {LocalAreaCodec.decode(repo.prefs.localAreas)}.getOrDefault(emptyList())
    val state=MutableStateFlow(LocalDiscoveryState(areas=initialAreas,area=initialAreas.firstOrNull {it.id==repo.prefs.localArea} ?: initialAreas.firstOrNull(),unit=repo.prefs.localDistanceUnit))
    private var request:Job?=null;private var lookup:Job?=null;@Volatile private var generation=0
    private var menuRequest:Job?=null
    private var menuGeneration=0
    private val permits=Semaphore(2)
    private val accountPermits=Semaphore(2)
    private val publication=Mutex()
    private val stopAreaObserver=repo.prefs.observeLocalAreas {scope.launch {
        val areas=runCatching {LocalAreaCodec.decode(repo.prefs.localAreas)}.getOrDefault(emptyList())
        val area=areas.firstOrNull {it.id==repo.prefs.localArea} ?: areas.firstOrNull()
        if(area!=state.value.area)stop()
        state.update {it.copy(areas=areas,area=area,unit=repo.prefs.localDistanceUnit)};publish()
    }}
    init {scope.launch {store.revision.collect {publish()}}}
    private var localFilter:Job?=null
    fun text(value:String){if(request?.isActive==true)stop();state.value=state.value.copy(text=value.take(256));localFilter?.cancel();localFilter=scope.launch {delay(180);publish()}}
    fun mode(value:LocalSearchMode) {stop();state.value=state.value.copy(mode=value,message=null,coverage=emptyList());scope.launch {publish()}}
    fun unit(value:LocalDistanceUnit){repo.prefs.localDistanceUnit=value;state.update {it.copy(unit=value)}}
    fun area(value:LocalArea) {
        require(value.valid());stop();val areas=(listOf(value)+state.value.areas.filter {it.id!=value.id}).take(12)
        repo.prefs.localAreas=LocalAreaCodec.encode(areas);repo.prefs.localArea=value.id
        state.value=state.value.copy(area=value,areas=areas,choices=emptyList(),message=null,businesses=emptyList(),events=emptyList());scope.launch {publish()}
    }
    fun removeArea(id:String){val areas=state.value.areas.filter {it.id!=id};repo.prefs.localAreas=LocalAreaCodec.encode(areas);if(state.value.area?.id==id){stop();repo.prefs.localArea=areas.firstOrNull()?.id.orEmpty();state.value=state.value.copy(areas=areas,area=areas.firstOrNull(),businesses=emptyList(),events=emptyList())}else state.value=state.value.copy(areas=areas);scope.launch {publish()}}
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
    fun stop(){request?.cancel();stopMenu();generation++;state.value=state.value.copy(loading=false,enriching=false)}
    /** Opening a restaurant can fill its menu without starting another index
     * search. Reuse known location routes and their successful/failure rests. */
    fun menu(value:LocalBusiness,force:Boolean=false) {
        if(!LocalMenuRouting.restaurant(value)||state.value.savedOnly)return
        val area=state.value.area ?: return
        if(menuRequest?.isActive==true&&state.value.menuLoading==value.id)return
        stopMenu();val bound=generation;val menuBound=menuGeneration
        menuRequest=scope.launch {
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
            finally {if(bound==generation&&menuBound==menuGeneration)state.update {it.copy(menuLoading=null)}}
        }
    }
    fun close(){stopAreaObserver();stop();lookup?.cancel();repo.localSearchConnection(false)}
    suspend fun publish()=publication.withLock {
        // Page/account completions and the store observer can arrive together.
        // Serialize projections so an older read cannot replace a newer deck.
        val snapshot=state.value;if(snapshot.mode==LocalSearchMode.POSTS)return@withLock;val area=snapshot.area;val saved=snapshot.savedOnly
        val result=withContext(Dispatchers.IO) {
            if(!saved&&area!=null){store.reconcile(area);store.reconcileEvents()}
            val businesses=store.businesses(if(saved)null else area,saved).filter {LocalDiscoveryFilter.matches(snapshot.text,LocalBusinessRouting.text(it))}
                .sortedWith(compareBy<LocalBusiness> {LocalBusinessRouting.relevance(snapshot.text,it)}.thenBy {area?.point?.let {p->it.point?.let(p::distanceKm)} ?: Double.MAX_VALUE}.thenBy {it.name}).take(160)
                .map {business->business.copy(socials=business.socials.filter {store.cache(accountKey(it))=="usable"},related=business.related?.map {it.copy(accounts=it.accounts.filter {url->store.cache(accountKey(url))=="usable"})})}
            val query=area?.let {LocalDiscoveryQuery(it,snapshot.text,snapshot.mode,snapshot.window,saved,snapshot.freeOnly)}
            val events=store.events(saved).filter {(saved||query!=null&&LocalDiscoveryFilter.event(query,it))&&LocalDiscoveryFilter.matches(LocalDiscoveryFilter.eventQuery(snapshot.text),it.title+" "+it.description+" "+it.venue+" "+it.organizer)}
                .sortedBy {it.start.instant()?.toEpochMilli() ?: it.start.date()?.atStartOfDay(java.time.ZoneId.of(area?.zoneId ?: "UTC"))?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE}.take(160)
            Triple(businesses,events,store.savedIds())
        }
        state.update {current->if(current.area==snapshot.area&&current.text==snapshot.text&&current.savedOnly==saved&&current.window==snapshot.window&&current.freeOnly==snapshot.freeOnly) {
            val positions=current.businesses.mapIndexed {i,b->b.id to i}.toMap()
            val aliases=current.businesses.flatMapIndexed {i,b->b.sourceIds.map {it to i}}.toMap()
            val stable=result.first.withIndex().sortedBy {(i,b)->positions[b.id] ?: b.sourceIds.mapNotNull {aliases[it]}.minOrNull() ?: (positions.size+i)}.map {it.value}
            current.copy(businesses=stable,events=result.second,saved=result.third)
        }else current}
    }
    private fun accountKey(url:String):String {
        val identity=repo.routes.recognize(url)?.let {ContentIdentity.key(it.platform,it.canonicalUrl)} ?: url.trimEnd('/')
        return "local-account:v2:${repo.prefs.tor}:${repo.prefs.originalFallback}:${repo.prefs.archiveRecovery}:"+LocalDiscoveryIdentity.hash(identity)
    }
    fun save(business:LocalBusiness){scope.launch {try {withContext(Dispatchers.IO){if(business.id in store.savedIds())store.removeSaved(business.id)else store.save(business)}}catch(e:Exception){state.value=state.value.copy(message=e.message)}}}
    fun save(event:LocalEvent){scope.launch {try {withContext(Dispatchers.IO){if(event.id in store.savedIds())store.removeSaved(event.id)else store.save(event)}}catch(e:Exception){state.value=state.value.copy(message=e.message)}}}
    fun importPlaces(body:String){val area=state.value.area ?: return;scope.launch {try {val count=withContext(Dispatchers.IO){val data=if(body.contains("\"@spider\""))LocalAllThePlaces.parse(body,"https://alltheplaces.xyz/",System.currentTimeMillis(),area)else LocalStructuredData.overture(body,"https://docs.overturemaps.org/guides/places/",System.currentTimeMillis());store.ingest(data,area).first};state.value=state.value.copy(message="Added $count place records in this area.");publish()}catch(e:Exception){state.value=state.value.copy(message=e.message ?: "Could not read this place pack")}}}
    fun search(force:Boolean=false,more:Boolean=false) {
        val snapshot=state.value
        if(snapshot.mode==LocalSearchMode.POSTS)return
        val area=snapshot.area ?: run {state.value=snapshot.copy(message="Choose an area to find businesses and events.");return}
        if(snapshot.savedOnly){scope.launch {publish()};return}
        stop();val bound=generation
        val topic=if(snapshot.mode==LocalSearchMode.EVENTS)LocalDiscoveryFilter.eventQuery(snapshot.text)else snapshot.text.trim()
        val query=LocalDiscoveryQuery(area,topic,snapshot.mode,snapshot.window,freeOnly=snapshot.freeOnly)
        state.value=state.value.copy(loading=true,enriching=false,message=null,coverage=emptyList(),checked=0,more=false)
        request=scope.launch {
            fun current()=generation==bound
            val deckReady=CompletableDeferred<Unit>();val initialDone=CompletableDeferred<Unit>()
            fun ready() {if(current()&&(if(query.mode==LocalSearchMode.BUSINESSES)state.value.businesses.isNotEmpty()else state.value.events.isNotEmpty())) {
                deckReady.complete(Unit);state.update {it.copy(loading=false,enriching=true)}
            }}
            fun note(value:String){if(current())state.update {it.copy(coverage=(it.coverage+value).distinct().takeLast(8))}}
            suspend fun ingest(data:LocalExtraction) {currentCoroutineContext().ensureActive();if(!current())return;withContext(Dispatchers.IO){store.ingest(data,area)};if(current()){data.notice?.let(::note);publish();ready()}}
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
                    val venueAttempts=java.util.concurrent.atomic.AtomicInteger()
                    val venueChecks=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    fun relevant()=store.businesses(area).filter {query.text.isBlank()||LocalBusinessRouting.relevance(query.text,it)<9||query.mode==LocalSearchMode.EVENTS&&it.category in setOf("library","theatre","community centre","arts centre","museum")}
                        .sortedWith(compareBy<LocalBusiness> {LocalBusinessRouting.relevance(query.text,it)}.thenBy {it.point?.distanceKm(area.point) ?: 1e9})
                    fun queueRelevant() {
                        relevant().take(8).forEach {owner->
                            owner.websites.sortedBy {LocalBusinessDetails.websitePriority(owner,it)}.take(2).forEach {store.offerSource(area,LocalPublisherSource(it,businessId=owner.id,label=owner.name))}
                            if(query.mode==LocalSearchMode.BUSINESSES)LocalMenuRouting.known(owner).forEach {store.offerSource(area,it)}
                            if(query.mode==LocalSearchMode.EVENTS)owner.socials.take(1).forEach {store.offerSource(area,LocalPublisherSource(it,LocalSourceKind.SOCIAL,owner.id,owner.name))}
                        }
                    }
                    suspend fun checkAccounts()=coroutineScope {
                        val perBusiness=relevant().take(12).map {owner->(owner.socials+owner.related.orEmpty().filter {it.role!="source publisher"}.flatMap {it.accounts})
                            .distinctBy(::accountKey).sortedBy {url->if(url.toHttpUrlOrNull()?.pathSegments?.firstOrNull()?.all(Char::isDigit)==true)1 else 0}}
                        // Check one account per business before spending the
                        // entire budget on one business's platform/ID aliases.
                        val urls=(0 until (perBusiness.maxOfOrNull {it.size} ?: 0)).flatMap {i->perBusiness.mapNotNull {it.getOrNull(i)}}.distinctBy(::accountKey)
                        urls.map {url->launch {
                            val key=accountKey(url)
                            if(store.cache(key)!=null||!accountChecks.add(key)||accountAttempts.incrementAndGet()>8)return@launch
                            accountPermits.withPermit {
                                try {val usable=withTimeout(16_000){backend.account(url)};store.cache(key,if(usable)"usable"else "unavailable",if(usable)6*3600000 else 30*60000)}
                                catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();store.cache(key,"unavailable",2*60000)}
                                catch(e:CancellationException){throw e}
                                catch(_:Exception){store.cache(key,"unavailable",10*60000)}
                                if(store.cache(key)=="unavailable")note("Some published social accounts are unavailable from the current public routes. Their source records are retained for a later retry.")
                                if(current())publish()
                            }
                        }}.joinAll()
                    }
                    fun history(source:LocalPublisherSource)=store.cache(LocalBusinessEnrichment.key(source))?.let {runCatching {gson.fromJson(it,LocalRouteYield::class.java)}.getOrNull()}
                    suspend fun readSources(limit:Int,deep:Boolean=false) {
                        val owners=store.businesses(area).associateBy {it.id}
                        val candidates=store.sources(area).filter {source->
                            source.url !in visited&&LocalOpportunityRouting.allowed(source,query)&&(source.kind!=LocalSourceKind.MENU||deep&&query.mode==LocalSearchMode.BUSINESSES&&owners[source.businessId]?.let {b->LocalMenuRouting.known(b).any {it.url==source.url}}==true)&&(force||source.nextCheckAt<=System.currentTimeMillis()||source.failures==0&&LocalCalendarPlatforms.window(source,query)?.let {it!=source.window}==true)&&
                                (source.url in currentSources||source.businessId?.let {owners[it]?.let {p->LocalBusinessRouting.relevance(query.text,p)<9}}==true||query.mode==LocalSearchMode.EVENTS&&LocalCalendarPlatforms.isCalendar(source))
                        }.sortedBy {LocalBusinessRouting.sourcePriority(it,query,owners[it.businessId],currentSources)+LocalBusinessEnrichment.priority(it,owners[it.businessId],history(it))-(if(deep&&it.url in detailSources)30 else 0)}.take(limit)
                        coroutineScope {candidates.map {source->launch {permits.withPermit {
                            if(!visited.add(source.url))return@withPermit
                            if((if(deep)deepAttempts else initialAttempts).incrementAndGet()>(if(deep)10 else 8)||attempts.incrementAndGet()>18){visited.remove(source.url);return@withPermit}
                            lane(source.label.ifBlank {"Public page"}) {
                                val before=System.currentTimeMillis();val previous=history(source)
                                try {
                                    val data=withTimeout(18_000){backend.read(source,query,owners[source.businessId])}
                                    if(data.businesses.isEmpty()&&data.events.isEmpty()&&data.sources.isEmpty()&&!data.validEmpty)throw SourceFailure(HealthStatus.EMPTY,"No readable business or calendar data")
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
                                            if(LocalDiscoveryFilter.geographic(area,event)||!venueChecks.add(event.address)||venueAttempts.incrementAndGet()>2||attempts.incrementAndGet()>18)continue
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
                        }}}.joinAll()}
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
                            val key="photon:v1:"+LocalDiscoveryIdentity.hash(area.cacheKey+LocalDiscoveryFilter.normalizedQuery(query.text))
                            if(force||store.cache(key)==null) {
                                val data=withTimeout(18_000){backend.places(query)};ingest(data);store.cache(key,"ok",6*3600000)
                                // A precise result supplies a geographic bridge to
                                // Overture's websites/socials outside the city centre.
                                coroutineScope {relevant().mapNotNull {it.point}.take(2).map {point->launch {overtureAt(point)}}.joinAll()}
                            }
                        }}
                        if(!more)launch {lane("Place directory") {
                            val key="directory:v2:${area.cacheKey}:${snapshot.mode==LocalSearchMode.EVENTS}:${LocalDiscoveryFilter.normalizedQuery(query.text)}"
                            if(force||store.cache(key)==null){ingest(withTimeout(50_000){backend.directory(query)});store.cache(key,"ok",86400000)}
                            queueRelevant();readSources(3)
                        }}
                        if(!more)launch {lane("Publisher search") {
                            val key="search:v3:"+LocalDiscoveryIdentity.hash(query.toString()+repo.prefs.searchProvider.id+LocalBusinessRouting.queries(query).joinToString("\n"))
                            suspend fun accept(hits:List<LocalWebHit>) {
                                val hosts=mutableMapOf<String,Int>()
                                hits.take(24).forEach {hit->
                                    val url=LocalDiscoveryIdentity.url(hit.url) ?: return@forEach
                                    val host=url.toHttpUrlOrNull()?.host ?: return@forEach
                                    if(hosts.getOrDefault(host,0)>=3)return@forEach
                                    hosts[host]=hosts.getOrDefault(host,0)+1;currentSources.add(url)
                                    val target=repo.routes.recognize(url)
                                    val source=LocalPublisherSource(url,if(target!=null)LocalSourceKind.SOCIAL else LocalCalendarPlatforms.kind(url) ?: LocalSourceKind.WEB,label=hit.title)
                                    if(LocalOpportunityRouting.allowed(source,query))store.offerSource(area,source)
                                }
                                readSources(6)
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
                        // Initial acquisition keeps priority. Supplemental reads
                        // yield to visible media/navigation and end with this search.
                        while(!initialDone.isCompleted)delay(100)
                        withTimeoutOrNull(70_000) {
                          while(repo.gateway.hasForegroundRequests)delay(200)
                          try {repo.gateway.deferredRead {
                            val owners=relevant().take(8)
                            queueRelevant()
                            coroutineScope {
                              // Account validation has its own two slots and
                              // starts with enrichment, rather than waiting
                              // behind every slow supplemental page read.
                              launch {checkAccounts()}
                              launch {readSources(2,deep=true)}
                              if(query.mode==LocalSearchMode.BUSINESSES)launch {permits.withPermit {lane("Published business locations") {
                                withTimeout(35_000){backend.enrich(query,owners){data->ingest(data);queueRelevant()}}
                              }}}
                              if(query.mode==LocalSearchMode.BUSINESSES&&query.text.isNotBlank())launch {
                                owners.filter {LocalBusinessEnrichment.query(it,area)!=null}.distinctBy {LocalDiscoveryMerge.businessName(it.name)}.take(2).forEach {owner->
                                  lane("Additional business details") {
                                    val hits=permits.withPermit {withTimeout(18_000){backend.refine(query,owner)}}
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
                          }}catch(e:CancellationException){currentCoroutineContext().ensureActive()}
                        }
                      }
                    }
                    if(current())state.value=state.value.copy(more=store.sources(area).any {it.nextCheckAt<=System.currentTimeMillis()&&it.url !in visited&&it.url in currentSources})
                }
                publish()
            }finally {if(current())state.value=state.value.copy(loading=false,enriching=false)}
        }
    }
}
