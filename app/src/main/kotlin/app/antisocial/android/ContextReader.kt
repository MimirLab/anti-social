/* AntiSocial, GPL-3.0-or-later. Optional, resumable evidence reading. Darwin remains the routing donor. */
package app.antisocial.android

import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class ContextReader(private val repo: Repository) {
    internal var prepareImageReader: suspend ()->Unit={repo.mediaTools.prepareRequestedImages()}
    val store=ContextStore(repo.context)
    val preferences=ContextPreferences(repo.context)
    val selected=MutableStateFlow<String?>(null)
    val active=MutableStateFlow<Set<String>>(emptySet())
    val capability=MutableStateFlow<ContextModelCapabilities?>(null)
    val processingPlan=MutableStateFlow<ContextProcessingPlan?>(null)
    @Volatile private var capabilityBinding: Pair<String,ContextModelCapabilities>?=null
    private val capabilityStore=ContextCapabilityStore(repo.context)
    private val candidateBank=ContextCandidateBank(repo)
    private val jobs=ConcurrentHashMap<String,Job>()
    private val automatic=ConcurrentHashMap.newKeySet<String>()
    private val aheadJobs=ConcurrentHashMap.newKeySet<String>()
    private val lane=Mutex()
    private val maintenanceLane=Mutex()
    private val requestLane=Mutex()
    private val starting=ConcurrentHashMap.newKeySet<String>()
    private val acquisition get()=repo.evidenceReader
    internal var articleRead:suspend (String,String,ContextArticleValidators,String?)->ContextArticleFetch={url,source,validators,retrieval->acquisition.articleConditional(url,source,validators,retrieval)}
    internal var publicationRead:suspend (SourceRecordRequest)->PublicSourceRecord={repo.sourceRecords.lookup(it,deferred=false)}
    private val eligibility=ConcurrentHashMap<String,List<ContextSnapshot>>()
    private val manualStopped=ConcurrentHashMap.newKeySet<String>()
    private val defaultModelFactory: ()->ContextModel = {
        val identity=repo.ai.contextIdentity()
        val binding=checkNotNull(capabilityBinding){"Resume Context to check the selected model’s reading capacity."}
        check(binding.first==identity){"The selected model changed. Resume Context to check its reading capacity."}
        val limits=binding.second
        val local=repo.ai.contextUsesDevice()
        val style=ContextProcessingStyle.valueOf(preferences.state.value.processingStyle.uppercase(java.util.Locale.ROOT))
        object: ContextModel {
            override val generationProfile=if(!local&&limits.vllmChatTemplate)"vllm-utility-direct-json-v2"else "default"
            override val contextTokens=limits.contextTokens
            override val maxOutputTokens=ContextProcessingRouter.outputBudget(limits,local,style)
            override val shortDecisionTokens=if(local)32 else maxOutputTokens
            override val processingPlan=ContextProcessingRouter.select(limits,local,maxOutputTokens,style)
            override suspend fun selectKnown(text: String,findings: List<ContextFinding>)=candidateBank.select(text,findings)
            override suspend fun count(system: String,user: String): Int =repo.ai.contextTokenCount(system,user,identity)
            override suspend fun generate(system: String,user: String,maxTokens: Int): String =repo.ai.contextText(system,user,maxTokens,identity,limits.vllmChatTemplate)
        }
    }
    internal var modelFactory: ()->ContextModel = defaultModelFactory
    /** Decorates the selected transport in native qualification without
     * replacing production capability discovery or provider routing. */
    internal var modelDecorator: (ContextModel)->ContextModel = {it}
    internal var discussionRead: suspend (Post,Page?)->Page = {post,page->repo.discussion(post,page)}
    internal var cleanupCacheKeys: suspend ()->Set<String> = {repo.dao.postCacheRows().map {it.key}.toSet()}
    /** Test injection scopes disposable saved fixtures without deleting a library. */
    internal var maintenanceKeys: suspend (String,Int)->List<String> = {cursor,limit->
        val after=repo.dao.bookmarkKeysAfter(cursor,limit,ContextPreferences.MAX_SAVED_CURSOR)
        if(after.size<limit&&cursor.isNotEmpty())after+repo.dao.bookmarkKeysThrough(cursor,limit-after.size,ContextPreferences.MAX_SAVED_CURSOR)else after
    }
    init {
        repo.scope.launch {repo.bookmarks.collect {bookmarks->
            val keys=bookmarks.map(CrossPosts::originalKey).toSet()
            store.all().forEach {record->val saved=record.snapshot.roots.any {CrossPosts.originalKey(it) in keys};if(record.saved!=saved)store.protect(record.id,saved)}
        }}
        repo.scope.launch {repo.ai.state.map {it.enabled}.distinctUntilChanged().collect {enabled->if(!enabled)pauseAll();ContextMaintenance.schedule(repo)}}
    }
    // A tap reads the cached publication. Explicit Refresh bypasses its TTL.
    internal fun deliveryIdentity()="context-delivery-v6-signal-trails|"+repo.ai.contextIdentity()+"|"+
        preferences.state.value.let {"${it.processingStyle}|${it.remoteContextTokens}"}
    internal suspend fun queueRequested(id:String,force:Boolean=false):Boolean=requestLane.withLock {
        if(!repo.ai.contextAvailable()||jobs.containsKey(id)||id in starting)return@withLock false
        val record=store.record(id) ?: return@withLock false
        val fresh=record.status in setOf("ready","partial")&&record.cycleStartedAt==0L&&!record.stale&&
            record.checkedAt>0&&record.nextCheck>System.currentTimeMillis()&&record.deliveryPolicy==deliveryIdentity()
        // Completed partial/empty results have a short retry floor too. An
        // interrupted job still resumes immediately; reopening a completed
        // failed-source cycle must not repeatedly spend network/inference.
        if(!force&&fresh)return@withLock false
        retryReviews(id)
        manualStopped.remove(id)
        starting.add(id)
        store.save(record.copy(status="queued",message=if(record.brief.isBlank())"Gathering the post and its sources"else "Checking for new information"))
        true
    }
    private suspend fun startRequested(id:String) {
        if(!queueRequested(id))return
        startService(id)
    }
    private suspend fun startService(id:String) {
        withContext(Dispatchers.Main) {
            try {val intent=Intent(repo.context,ContextJobService::class.java).putExtra("contextId",id)
                if(Build.VERSION.SDK_INT>=26)repo.context.startForegroundService(intent)else repo.context.startService(intent)
            }catch(e:Exception){starting.remove(id);store.record(id)?.let {store.save(it.copy(status="paused",message="Context is saved. Open it again to resume processing."))}}
        }
    }
    fun request(snapshot:ContextSnapshot,force:Boolean=false) {
        repo.scope.launch {
            if(!repo.ai.contextAvailable())return@launch
            val record=store.resolve(snapshot)
            selected.value=record.id
            if(queueRequested(record.id,force))startService(record.id)
        }
    }
    fun open(id:String){selected.value=id;repo.scope.launch {startRequested(id)}}
    fun close(){selected.value=null}
    fun stop(id: String) {manualStopped.add(id);jobs[id]?.cancel(CancellationException("Stopped by reader"));repo.scope.launch {store.record(id)?.let {store.save(it.copy(status="paused",message="Paused · tap Refresh to continue"))}}}
    fun pauseAll(){jobs.values.forEach {it.cancel(CancellationException("AI is paused"))};eligibility.clear();candidateBank.clear()}
    fun changed(){ContextMaintenance.schedule(repo);if(!preferences.state.value.ahead)clearAhead()}
    fun invalidateCapabilities(){capabilityStore.invalidate();capabilityBinding=null;capability.value=null;processingPlan.value=null}
    fun clearAhead(feed: String?=null){if(feed==null)eligibility.clear()else eligibility.remove(feed);repo.scope.launch {cancelOutdatedAhead()}}
    private fun cancelOutdatedAhead() {
        val eligible=eligibility.values.flatten().flatMap {it.roots}.map(CrossPosts::originalKey).toSet()
        aheadJobs.toList().forEach {id->if(store.record(id)?.snapshot?.roots?.none {CrossPosts.originalKey(it) in eligible}!=false)jobs[id]?.cancel(CancellationException("Reading position moved"))}
    }
    fun ahead(feed: String,snapshots: List<ContextSnapshot>) {
        if(!preferences.state.value.ahead||!repo.ai.contextAvailable()){clearAhead(feed);return}
        eligibility[feed]=snapshots.take(preferences.state.value.lookahead)
        repo.scope.launch {
            cancelOutdatedAhead()
            for(snapshot in snapshots.take(preferences.state.value.lookahead)) {
                if(!automaticAllowed(false))break
                val record=store.resolve(snapshot,false)
                if(record.nextCheck>System.currentTimeMillis()&&record.status !in setOf("paused","queued"))continue
                if(snapshot !in eligibility[feed].orEmpty())continue
                run(record.id,true,false)
            }
        }
    }
    suspend fun maintain()=maintenanceLane.withLock {
        fun allowed()=preferences.state.value.saved&&automaticAllowed(false)&&repo.ai.contextAvailable()
        if(!allowed())return@withLock
        val deadline=SystemClock.elapsedRealtime()+preferences.state.value.sliceSeconds*1000L
        val attemptedOwners=mutableSetOf<String>()
        // Only keys are scanned. Decode saved records on demand, and remember
        // the scan position even when an old copy is invalid or not yet due.
        val keys=maintenanceKeys(preferences.savedMaintenanceCursor,32).distinct().take(32)
        for(key in keys) {
            currentCoroutineContext().ensureActive()
            if(!allowed()||SystemClock.elapsedRealtime()>=deadline||attemptedOwners.size>=3)break
            preferences.savedMaintenanceCursor=key
            val current=store.forOriginals(listOf(key)).firstOrNull()
            if(current!=null) {
                if(current.id in manualStopped||jobs.containsKey(current.id))continue
                if(current.id in attemptedOwners||current.nextCheck>System.currentTimeMillis()) {
                    // Even a not-yet-due sibling can carry a richer saved
                    // edition manifest. Inspect that bounded local metadata
                    // before skipping an already reviewed owner.
                    val detail=try {repo.library.details(key)}catch(e: CancellationException){throw e}catch(_: Exception){null}
                    if(detail?.fusionRoots.orEmpty().all {saved->current.snapshot.roots.any {CrossPosts.originalKey(it)==CrossPosts.originalKey(saved)}})continue
                }
            }
            val entry=try {repo.library.copy(key)}catch(e: CancellationException){throw e}catch(_: Exception){null} ?: continue
            val captured=runCatching {repo.library.normalizedFusionRoots(entry.post,entry.details?.fusionRoots.orEmpty()).ifEmpty {listOf(entry.post)}}.getOrNull() ?: continue
            val known=store.forOriginals(captured.map(CrossPosts::originalKey))
            val existing=known.firstOrNull {it.id==current?.id} ?: known.firstOrNull()
            if(existing!=null&&(existing.id in manualStopped||jobs.containsKey(existing.id)))continue
            // First captures stay immutable. Prefer every current source edition
            // already known to Context; saved data may only add missing roots.
            val roots=(existing?.snapshot?.roots.orEmpty()+known.flatMap {it.snapshot.roots}+captured).distinctBy(CrossPosts::originalKey).take(CrossPosts.MAX_EDITIONS)
            val added=existing!=null&&captured.any {saved->existing.snapshot.roots.none {CrossPosts.originalKey(it)==CrossPosts.originalKey(saved)}}
            var record=if(existing!=null&&!added)existing
                else store.resolveMaintenance(contextSnapshot(repo,collectedCaptionPost(roots) ?: existing?.snapshot?.roots?.firstOrNull() ?: entry.post,roots),known.associate {it.id to it.snapshot.revision}) ?: continue
            if(added&&existing!=null&&(existing.id in attemptedOwners||existing.nextCheck>System.currentTimeMillis())) {
                record=store.continueMaintenance(record.id,record.snapshot.revision,System.currentTimeMillis()) ?: continue
                initializeStreams(record)
            }
            if(repo.dao.bookmarksByKeys(record.snapshot.roots.map(repo::key)).isEmpty()) {store.protect(record.id,false);continue}
            store.protect(record.id,true)
            if(!allowed())break
            if(record.id in attemptedOwners||record.id in manualStopped||jobs.containsKey(record.id)||record.nextCheck>System.currentTimeMillis())continue
            // Set an attempt floor before inference, including interruptions and
            // allowance pauses. A failed read never advances successful checkedAt.
            val now=System.currentTimeMillis()
            val retryAfter=now+preferences.state.value.refreshMinutes.coerceIn(15,60)*60_000L
            if(!store.reserveMaintenance(record.id,now,retryAfter))continue
            attemptedOwners.add(record.id)
            run(record.id,true,true,deadline)
        }
    }
    internal fun automaticAllowed(heavy: Boolean): Boolean {
        if(!repo.ai.preferences.enabled)return false
        val battery=repo.context.getSystemService(BatteryManager::class.java)
        val level=battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val power=repo.context.getSystemService(PowerManager::class.java)
        if(power.isPowerSaveMode||level in 0 until preferences.state.value.minimumBattery)return false
        if(Build.VERSION.SDK_INT>=29&&power.currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE)return false
        if(heavy) {
            val options=preferences.state.value
            if(options.mediaPolicy=="manual")return false
            if(options.mediaPolicy=="charging"&&!battery.isCharging)return false
            if(options.unmetered&&repo.context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered)return false
        }
        return true
    }
    suspend fun run(id: String,background: Boolean=false,savedMaintenance: Boolean=false,automaticDeadline: Long=Long.MAX_VALUE) {
        val job=currentCoroutineContext()[Job] ?: return
        if(!background) {
            automatic.toList().forEach {jobs[it]?.cancel(CancellationException("Requested Context takes priority"))}
        }
        while(true) {
            val existing=jobs.putIfAbsent(id,job) ?: break
            if(background)return
            // A foreground request may take over an automatic read. Waiting
            // for its durable checkpoint also prevents a queued service from
            // spinning while another owner still holds this card.
            existing.join()
            currentCoroutineContext().ensureActive()
            if(store.record(id)?.status!="queued")return
        }
        if(background){automatic.add(id);if(!savedMaintenance)aheadJobs.add(id)}
        starting.remove(id)
        active.value=jobs.keys.toSet()
        val deadline=if(background)minOf(SystemClock.elapsedRealtime()+preferences.state.value.sliceSeconds*1000L,automaticDeadline)else Long.MAX_VALUE
        var roots=emptyList<Post>()
        try {repo.gateway.contextRead {lane.withLock {
            repo.awaitReady();check(repo.ai.contextAvailable()){ "Choose a ready text model in AI settings." };repo.mediaTools.refresh()
            var record=store.record(id) ?: return@withLock
            if(record.snapshot.variant=="current") {
                // Older snapshots may already have discarded the RSS heading.
                // Consult the current local source record for this exact post;
                // never reinterpret an archived/saved edition as current data.
                val recoveredRoots=record.snapshot.roots.map {root->
                    val cached=repo.dao.post(repo.key(root))?.payload?.let {payload->
                        runCatching {repo.gson.fromJson(payload,Post::class.java)}.getOrNull()
                    }?.takeIf {candidate->!candidate.preview&&candidate.recoveredArchive==null&&
                        candidate.platform==root.platform&&CrossPosts.originalKey(candidate)==CrossPosts.originalKey(root)}
                    if(cached!=null&&PostLinks.urls(cached).map {repo.publicUrl(it)}.any {it !in record.snapshot.links})cached
                    else if(cached?.contextNotes!=null&&cached.contextNotes!=root.contextNotes)root.copy(contextNotes=cached.contextNotes)else root
                }
                val completeLinks=recoveredRoots.flatMap(PostLinks::urls).map {repo.publicUrl(it)}.distinct().take(PostLinks.MAX_LINKS)
                if(completeLinks.any {it !in record.snapshot.links}||recoveredRoots.map {it.contextNotes}!=record.snapshot.roots.map {it.contextNotes}) {
                    // Repair old 12-link snapshots on an authorized read too;
                    // Refresh must not require the reader to recreate the card.
                    record=store.resolve(contextSnapshot(repo,recoveredRoots.first(),recoveredRoots),record.requested)
                }
            }
            val readingRevision=record.snapshot.revision
            roots=record.snapshot.roots
            // The bookmark flow may have emitted before this owner existed.
            // Protect its very first publication before admission or inference.
            val saved=repo.dao.bookmarksByKeys(roots.map(repo::key)).isNotEmpty()
            if(record.saved!=saved){store.protect(id,saved);record=store.record(id) ?: return@withLock}
            roots.forEach {repo.lease(repo.key(it),true)}
            checkSpace(record,0)
            val modelIdentity=repo.ai.contextIdentity()
            fun checkWork() {
                currentCoroutineContextOrNull(job)
                if(store.record(id)?.snapshot?.revision!=readingRevision)throw ContextDeferred("Post changed · progress retained for the updated card",true)
                if(repo.ai.contextIdentity()!=modelIdentity)throw ContextDeferred("Model changed · tap Refresh to continue with your selected model")
                check(repo.ai.preferences.enabled){"AI is off"}
                if(background&&(!automaticAllowed(false)||SystemClock.elapsedRealtime()>=deadline))throw ContextDeferred("Automatic allowance reached · progress saved")
            }
            if(modelFactory===defaultModelFactory) {
                val identity=repo.ai.contextIdentity()
                // The private native worker is checked once per reading job;
                // remote metadata retains its bounded, privacy-scoped cache.
                val reported=if(repo.ai.contextUsesDevice())repo.ai.contextCapabilities(identity)
                    else capabilityStore.resolve(identity,if(repo.gateway.currentPolicy().tor)"tor"else "direct"){repo.ai.contextCapabilities(identity)}
                check(repo.ai.contextIdentity()==identity){"The selected model changed. Resume Context to check its reading capacity."}
                val override=preferences.state.value.remoteContextTokens.takeIf {it>0&&!repo.ai.contextUsesDevice()}
                val limits=if(override==null)reported else reported.copy(contextTokens=minOf(override,reported.activeContextTokens ?: override,reported.advertisedContextTokens ?: override,reported.maxInputTokens ?: override),source="Reader-selected window · "+reported.source.take(200))
                capabilityBinding=identity to limits
                capability.value=limits
            }
            val model=modelDecorator(modelFactory())
            checkWork()
            processingPlan.value=model.processingPlan
            val labels=mutableMapOf<String,String>()
            val engine=ContextEngine(model,modelIdentity,sourceLabel={source->labels.getOrPut(source){sourceLabel(id,source)}},
                sourcePublications={source->publicationSources(store.document(id,source)?.document)},
                sourceEvidenceScope={source->store.document(id,source)?.document?.let(ContextEvidenceScope::from)},readerBrief=true)
            val processingPolicy=EnglishReadingPreparer.POLICY+"|context-publication-v3-reader-brief|"+engine.processingIdentity
            store.processingPolicy(id,processingPolicy)
            // Policy invalidation changes the durable record. Never save the
            // pre-invalidation copy over its new marker later in this cycle.
            record=store.record(id) ?: return@withLock
            val languages=repo.languages.session()
            val reviewSession=ContextReviewSession(readingRevision,processingPolicy)
            update(id,"reading","Reading the post")
            val first=record.snapshot.roots.first()
            if(!background&&record.snapshot.media.any {it.type=="image"}) {
                update(id,"reading","Preparing image text")
                prepareImageReader()
                checkWork()
            }
            val postDocument=ContextDocument("merged:${record.id}",Repository.hash(record.snapshot.variant+record.snapshot.text+record.snapshot.title+record.snapshot.roots.map {it.author}.distinct().joinToString("|")),record.snapshot.text,record.snapshot.title,
                record.snapshot.roots.map {it.author}.distinct().joinToString(" · "),repo.publicUrl(first.canonicalUrl,first.platform),"post",fetchedAt=System.currentTimeMillis(),metadata=mapOf("root" to CrossPosts.originalKey(first))+publicationTime(record.snapshot.roots.mapNotNull {it.publishedAt}.minOrNull())+editionTimes(record.snapshot.roots))
            store.ingest(id,listOf(postDocument))
            initializeStreams(record)
            val refreshed=record.checkedAt>0&&record.cycleStartedAt==0L
            if(refreshed) {
                retryReviews(id)
                // A cycle revisits every known branch. Bodies already processed
                // are compared by revision and never sent back through inference.
                store.removeStream(id,"comment-links-limit")
                val commentLinks=store.streams(id).filter {it.kind=="link"&&it.commentContext.isNotBlank()}.map {it.id}.take(preferences.state.value.commentLinks).toSet()
                store.streams(id).filter {it.kind !in setOf("limit","saved")&&it.status in setOf("done","limited","unavailable","disabled")}.forEach {stream->
                    if(stream.kind=="link"&&stream.commentContext.isNotBlank()&&stream.id !in commentLinks) {
                        store.stream(id,stream.copy(status="disabled",message="Comment-linked page exceeds your reading allowance"));return@forEach
                    }
                    store.stream(id,stream.copy(previous=null,cursors=emptySet(),pendingParents=emptyList(),visitedParents=emptySet(),status="pending",rootLimit=30,work=if(stream.status=="done")stream.work.filterKeys {it in setOf("savedCycle","savedScanAt","savedThreadOffset","archivedAt","sourceVersion","license","retracted")||it.startsWith("article")||it.startsWith("signal")||it=="paperDoi"}else stream.work))
                }
            }
            if(record.cycleStartedAt==0L){record=record.copy(cycleStartedAt=System.currentTimeMillis());store.save(record)}
            reuseSavedThreads(record,::checkWork)
            process(id,engine,1,languages,reviewSession,::checkWork)
            if(readyToCompose(id)) {
                labels.clear();publish(id,model,readingRevision,modelIdentity,processingPolicy)
            }
            while(true) {
                checkWork()
                val stream=store.streams(id).filter {it.status=="pending"}.minWithOrNull(compareBy<ContextStream> {
                    when {it.kind=="link"&&it.commentContext.isBlank()&&it.work["signalDepth"]==null->0;it.kind=="media"->1;it.kind=="publication"->2;it.work["signalReason"]=="Publication update"->3;it.work["signalDepth"]!=null->4;else->5}
                }.thenBy {it.fetchedAt}.thenBy {it.id})
                if(stream!=null) {
                    update(id,"reading",when(stream.kind){"shared"->"Reading shared discussion";"publication"->"Checking cited research";"link"->if(stream.work["signalDepth"]!=null)"Reading underlying sources"else "Reading linked material";else->"Reading ${stream.root.platform.label} comments"})
                    try {
                        if(background)repo.gateway.deferredRead {readStream(id,stream,background)}else readStream(id,stream,background)
                    }catch(e: CancellationException){throw e}catch(e: Exception){store.stream(id,stream.copy(status="unavailable",message=publicError(e),fetchedAt=System.currentTimeMillis()))}
                }
                val processed=process(id,engine,8,languages,reviewSession,::checkWork)
                if(processed>0&&readyToCompose(id)){labels.clear();publish(id,model,readingRevision,modelIdentity,processingPolicy)}
                if(stream==null&&processed==0)break
            }
            record=store.record(id) ?: return@withLock
            checkWork()
            publish(id,model,readingRevision,modelIdentity,processingPolicy)
            record=store.record(id) ?: return@withLock
            val now=System.currentTimeMillis()
            val partial=store.streams(id).any {it.status in setOf("unavailable","limited","disabled","pending")}
            val unchanged=if(record.updatedAt<=record.checkedAt)record.unchanged+1 else 0
            val delay=if(partial&&record.brief.isBlank())5*60_000L else preferences.state.value.refreshMinutes*60_000L*(1L shl unchanged.coerceIn(0,4))
            store.save(record.copy(status=if(partial)"partial"else "ready",message=if(partial)"Context ready · some material could not be fully read"else "Context ready",
                checkedAt=now,nextCheck=now+delay.coerceAtMost(24*60*60_000L),unchanged=unchanged,cycleStartedAt=0,deliveryPolicy=deliveryIdentity()))
            if(record.saved)store.protect(id,true)
        }}}catch(e: ContextDeferred){update(id,if(e.requeue)"queued"else "paused",e.message.orEmpty())}
        catch(e: CancellationException){withContext(NonCancellable){update(id,if(manualStopped.contains(id))"paused"else "queued",if(manualStopped.contains(id))"Paused · tap Refresh to continue"else "Progress saved · waiting to continue")};throw e}
        catch(e: Exception){
            val rejected=(e as? SourceFailure)?.httpStatus in setOf(400,413)
            if(rejected)invalidateCapabilities()
            update(id,"paused",if(rejected)"The model server rejected this reading request · progress saved. Refresh checks its capacity again."else publicError(e))
        }
        finally {roots.forEach {repo.lease(repo.key(it),false)};jobs.remove(id,job);automatic.remove(id);aheadJobs.remove(id);active.value=jobs.keys.toSet();repo.scheduleCleanup()}
    }
    private fun currentCoroutineContextOrNull(job: Job){job.ensureActive()}
    private fun readyToCompose(owner:String):Boolean {
        val images=store.record(owner)?.snapshot?.media.orEmpty().let {it.isNotEmpty()&&it.all {media->media.type=="image"}}
        return store.streams(owner).none {it.status=="pending"&&(it.kind=="link"&&it.commentContext.isBlank()&&it.work["signalDepth"]==null||images&&it.kind=="media")}
    }
    private fun initializeStreams(record: ContextRecord) {
        val existing=store.streams(record.id).map {it.id}.toSet()
        record.snapshot.roots.forEach {root->
            val key=CrossPosts.originalKey(root)
            if("platform:$key" !in existing)store.stream(record.id,ContextStream("platform:$key",root))
            if("shared:$key" !in existing)store.stream(record.id,ContextStream("shared:$key",root,"shared"))
        }
        val mediaLinks=record.snapshot.roots.flatMap {it.media}.flatMap {listOfNotNull(it.url,it.preview,it.audioUrl)}.map(repo::publicUrl).toSet()
        record.snapshot.links.filter(::substantiveLink).filter {repo.publicUrl(it) !in mediaLinks}.forEach {link->enqueueLink(record.id,link,record.snapshot.roots.first())}
        record.snapshot.links.mapNotNull(PublicSourceRecords::paper).distinct().take(3).forEach {paper->enqueuePublication(record.id,paper,record.snapshot.roots.first(),ContextSources.post(record.id),0)}
        record.snapshot.roots.forEach {root->root.contextNotes.orEmpty().take(4).forEach {note->
            val signature=Repository.hash(note.text+note.links.joinToString("\n")+note.status)
            val id="annotation:"+Repository.hash(CrossPosts.originalKey(root)+note.sourceUrl)
            val document=ContextDocument(id,signature,note.text,"Community note","",note.sourceUrl,"comment",fetchedAt=System.currentTimeMillis(),metadata=mapOf("root" to CrossPosts.originalKey(root),"medium" to "Public community annotation","contextParent" to ContextSources.post(record.id),"links" to repo.gson.toJson(note.links)))
            checkSpace(record,note.text.toByteArray().size.toLong())
            store.ingest(record.id,listOf(document))
            note.links.filter(::substantiveLink).take(2).forEach {link->enqueueSignalLink(record.id,SignalReference(link,"Community note citation","Annotation citation",note.text.take(1000),100),root,document.id,1)}
        }}
        // Media is a separate stream so unavailable tools cannot block text.
        if(record.snapshot.media.isNotEmpty()&&"media" !in existing)store.stream(record.id,ContextStream("media",record.snapshot.roots.first(),"media"))
    }
    private fun substantiveLink(url: String): Boolean {
        val parsed=url.toHttpUrlOrNull() ?: return false
        if(!parsed.isHttps||parsed.encodedPath in setOf("/privacy","/terms","/login","/signup"))return false
        val target=runCatching {repo.routes.recognize(url)}.getOrNull()
        return target==null||target.kind in setOf(TargetKind.POST,TargetKind.THREAD,TargetKind.MEDIA)
    }
    private fun enqueueLink(owner: String,url: String,root: Post,commentContext: String="",originKey: String=CrossPosts.originalKey(root),commentDocument: String?=null) {
        val canonical=repo.publicUrl(url)
        val key="link:${Repository.hash(canonical)}"
        val streams=store.streams(owner)
        val identity=SignalDiscovery.identity(canonical)
        val existing=streams.firstOrNull {it.id==key||identity!=null&&it.kind=="link"&&SignalDiscovery.identity(it.root.canonicalUrl)==identity}
        if(existing!=null) {
            if(commentContext.isBlank()&&existing.work["signalDepth"]!=null){store.stream(owner,existing.copy(commentContext="",commentDocument=null,work=existing.work.filterKeys {!it.startsWith("signal")}));return}
            if(commentContext.isBlank()&&existing.commentContext.isNotBlank())store.stream(owner,existing.copy(commentContext="",commentDocument=null))
            else if(existing.commentContext.isNotBlank()&&existing.commentDocument==null&&commentDocument!=null)
                store.stream(owner,existing.copy(commentDocument=commentDocument))
            return
        }
        if(commentContext.isNotBlank()&&streams.count {it.kind=="link"&&it.commentContext.isNotBlank()}>=preferences.state.value.commentLinks) {
            store.stream(owner,ContextStream("comment-links-limit",root,"limit",status="limited",message="Comment-linked pages are limited to ${preferences.state.value.commentLinks} per post; other links remain in the original discussion",limited=true))
            return
        }
        store.stream(owner,ContextStream(key,root.copy(canonicalUrl=canonical),"link",originKey=originKey,commentContext=commentContext,commentDocument=commentDocument))
    }
    private fun enqueueCommentLinks(owner: String,document: ContextDocument,root: Post) {
        val supplied=runCatching {repo.gson.fromJson(document.metadata["links"],Array<String>::class.java).toList()}.getOrDefault(emptyList())
        val urls=supplied+Regex("https?://[^\\s<>\\\"\\[\\]]+").findAll(document.text).map {it.value.trimEnd('.',',',')',';')}.toList()
        // The model never supplies a URL or a fetch instruction. These are
        // literal source links with a fixed first-hop/user-budget policy.
        urls.distinct().filter(::substantiveLink).forEach {enqueueLink(owner,it,root,"Source-supplied comment link",document.metadata["root"] ?: CrossPosts.originalKey(root),document.id)}
    }
    private fun enqueuePublication(owner:String,paper:SourceRecordRequest,root:Post,parent:String,depth:Int,originKey:String=CrossPosts.originalKey(root)) {
        val streams=store.streams(owner);val id="publication:${paper.identity}"
        streams.firstOrNull {it.id==id}?.let {old->
            if(old.work["signalParent"]==ContextSources.post(owner)&&parent!=ContextSources.post(owner))store.stream(owner,old.copy(work=old.work+("signalParent" to parent)))
            return
        }
        if(streams.count {it.kind=="publication"}>=3)return
        store.stream(owner,ContextStream(id,root,"publication",originKey=originKey,work=mapOf("paperDoi" to paper.identity,"signalParent" to parent,"signalDepth" to depth.toString())))
    }
    private fun enqueueSignalLink(owner:String,reference:SignalReference,root:Post,parent:String,depth:Int,originKey:String=CrossPosts.originalKey(root)) {
        val url=SignalDiscovery.identity(repo.publicUrl(reference.url)) ?: return
        if(!substantiveLink(url)||depth !in 1..SignalDiscovery.MAX_DEPTH)return
        val streams=store.streams(owner);val id="link:${Repository.hash(url)}"
        if(streams.any {it.id==id||SignalDiscovery.identity(it.root.canonicalUrl)==url}||streams.count {it.kind=="link"&&it.work["signalDepth"]!=null}>=SignalDiscovery.MAX_FOLLOW_UPS)return
        store.stream(owner,ContextStream(id,root.copy(canonicalUrl=url),"link",originKey=originKey,work=mapOf("signalDepth" to depth.toString(),"signalParent" to parent,"signalReason" to reference.reason)))
    }
    private fun discoverArticleSources(owner:String,stream:ContextStream,document:ContextDocument,references:List<SignalReference>,doi:String?) {
        val depth=stream.work["signalDepth"]?.toIntOrNull() ?: 0
        val streams=store.streams(owner)
        val remaining=SignalDiscovery.MAX_FOLLOW_UPS-streams.count {it.kind=="link"&&it.work["signalDepth"]!=null}
        SignalDiscovery.follow(references,depth,streams.filter {it.kind=="link"}.map {it.root.canonicalUrl}.toSet()+document.url,remaining).forEach {
            enqueueSignalLink(owner,it,stream.root,document.id,depth+1,stream.originKey)
        }
        if(stream.work["signalReason"]!="Publication update")doi?.let {PublicSourceRecords.paper("https://doi.org/$it")}?.let {enqueuePublication(owner,it,stream.root,document.id,depth,stream.originKey)}
    }
    private suspend fun readPublication(owner:String,stream:ContextStream) {
        val request=PublicSourceRecords.paper("https://doi.org/"+stream.work["paperDoi"]) ?: error("Invalid cited paper")
        val result=publicationRead(request)
        val text=result.facts.filter {it.kind.startsWith("paper:")}.joinToString("\n") {fact->
            "${fact.title} recorded for https://doi.org/${request.identity}"+(fact.recordedAt?.let {" on "+java.time.Instant.ofEpochMilli(it).toString().take(10)} ?: "")+". ${fact.detail} Notice: ${fact.destination.orEmpty()}"
        }
        val id="publication-record:${request.identity}"
        if(text.isNotBlank()) {
            val doc=ContextDocument(id,Repository.hash(text),text,"Publication updates","Crossref","https://doi.org/${request.identity}","article",fetchedAt=result.checkedAt,metadata=mapOf("root" to stream.originKey,"contextParent" to stream.work["signalParent"].orEmpty(),"medium" to "Publication update record"))
            store.ingest(owner,listOf(doc))
            val depth=(stream.work["signalDepth"]?.toIntOrNull() ?: 0)+1
            result.facts.mapNotNull {it.destination}.distinct().take(2).forEach {url->enqueueSignalLink(owner,SignalReference(url,"Publication notice","Publication update",text.take(1000),110),stream.root,id,depth,stream.originKey)}
        }
        store.stream(owner,stream.copy(status=if(result.complete)"done"else "unavailable",message=if(text.isNotBlank())"Publication updates read"else "No publication update available",fetchedAt=result.checkedAt,count=if(text.isBlank())0 else 1))
    }
    private fun publicationTime(value: Long?): Map<String,String> =value?.takeIf {it>0}?.let {mapOf("published" to java.time.Instant.ofEpochMilli(it).toString())}.orEmpty()
    private fun editionTimes(roots:List<Post>):Map<String,String> {
        require(roots.size<=CrossPosts.MAX_EDITIONS){"Too many source editions · existing Context retained"}
        return ContextSourcePublication.encode(roots.map {post->ContextSourcePublication(post.author,
            post.publishedAt?.takeIf {it>0}?.let {ContextPublicationTime(java.time.Instant.ofEpochMilli(it))})})
    }
    /** Saved discussions are source snapshots, never a successful live check.
     * Reuse only this card's proven roots, once per durable reading cycle. A
     * current document always wins, including an edited/deleted public reply. */
    private suspend fun reuseSavedThreads(record: ContextRecord,checkWork: ()->Unit) {
        val owner=record.id;val cycle=record.cycleStartedAt.toString()
        val allowed=record.snapshot.roots.associateBy(CrossPosts::originalKey)
        val scans=store.streams(owner).associateBy {it.id}
        store.removeStream(owner,"saved-copy-limit")
        var copies=0;var received=0;var bytes=0L
        for((key,root) in allowed.entries.sortedBy {scans["platform:${it.key}"]?.work?.get("savedScanAt")?.toLongOrNull() ?: 0L}) {
            checkWork()
            val platform=store.streams(owner).firstOrNull {it.id=="platform:$key"} ?: continue
            if(platform.work["savedCycle"]==cycle)continue
            // Missing bookmarks are cheap indexed reads, not decoded copies.
            val saved=repo.dao.bookmarksByKeys(listOf(key)).firstOrNull()
            if(saved!=null&&copies>=8) {
                store.stream(owner,ContextStream("saved-copy-limit",root,"limit",status="limited",limited=true,
                    message="Saved discussion reuse is limited to eight source copies per reading pass"))
                break
            }
            val entry=if(saved==null)null else {copies++;try {repo.library.copy(key)}catch(e: CancellationException){throw e}catch(_: Exception){null}}
            val details=entry?.details
            var nextThreadOffset=platform.work["savedThreadOffset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val savedRoots=if(entry!=null&&entry.key==key&&CrossPosts.originalKey(entry.post)==key)runCatching {
                repo.library.normalizedFusionRoots(entry.post,details?.fusionRoots.orEmpty()).ifEmpty {listOf(entry.post)}.map(CrossPosts::originalKey).toSet()
            }.getOrNull()else null
            if(details!=null&&savedRoots!=null) {
                var truncated=details.threads.size>64;var at=0L
                val documents=mutableListOf<ContextDocument>()
                val available=mutableSetOf<String>()
                val offset=if(details.threads.isEmpty())0 else nextThreadOffset%details.threads.size
                val threads=(details.threads.drop(offset)+details.threads.take(offset)).take(64)
                nextThreadOffset=if(details.threads.isEmpty())0 else (offset+threads.size)%details.threads.size
                for(thread in threads) {
                    checkWork()
                    val threadKey=CrossPosts.originalKey(thread.root)
                    if(threadKey !in allowed||threadKey !in savedRoots||thread.capturedAt<=0||thread.capturedAt>System.currentTimeMillis()+CachePolicy.DAY||!validCapturedPost(thread.root))continue
                    val shared=thread.commentAnywhere
                    if(shared!=null&&!repo.commentAnywhere.enabled)continue
                    val candidates=if(shared!=null) {
                        val page=runCatching {repo.library.normalizedCommunity(thread.root,shared)}.getOrNull() ?: continue
                        sharedDocuments(owner,threadKey,page,thread.capturedAt).map {it.copy(metadata=it.metadata+mapOf("savedCapture" to "true","medium" to "Saved thread snapshot"))}
                    }else {
                        if(thread.replies.size>4096){truncated=true;continue}
                        val replies=Discussions.comments(thread.root,Page(listOf(thread.root)+thread.replies.filter(::validCapturedPost))) {parent->store.hasContextKey(owner,ContextSources.reply(threadKey,thread.root.platform,parent))}
                        platformDocuments(owner,threadKey,replies,thread.capturedAt).map {it.copy(metadata=it.metadata+mapOf("savedCapture" to "true","medium" to "Saved thread snapshot"))}
                    }
                    if(candidates.isNotEmpty())at=if(at==0L)thread.capturedAt else minOf(at,thread.capturedAt)
                    for(document in candidates) {
                        // No saved capture may replace an already cached source,
                        // reset its processing state or discard its exact receipt.
                        if(store.document(owner,document.id)!=null){available+=document.id;continue}
                        if(documents.any {it.id==document.id})continue
                        val size=repo.gson.toJson(document).toByteArray().size.toLong()
                        if(received>=512||bytes+size>2L*1024*1024){truncated=true;continue}
                        received++;bytes+=size;documents+=document;available+=document.id
                    }
                }
                if(documents.isNotEmpty()||at>0||truncated) {
                    checkSpace(store.record(owner) ?: return,documents.sumOf {repo.gson.toJson(it).toByteArray().size.toLong()})
                    val whenSaved=at.takeIf {it>0} ?: details.capturedAt
                    val date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(whenSaved))
                    val stream=ContextStream("saved:$key",root,"saved",status="limited",limited=true,fetchedAt=whenSaved,count=available.size,
                        message="Saved $date · a dated discussion snapshot; live coverage is checked separately"+if(truncated)" · some saved replies exceed this pass's allowance"else "",
                        work=mapOf("cycle" to cycle))
                    store.ingest(owner,documents,stream)
                    documents.forEach {document->enqueueCommentLinks(owner,document,allowed[document.metadata["root"]] ?: root)}
                }
            }
            // Keep the live stream's cursor/status: this marker records only
            // the local scan and survives a cancellation during a fresh fetch.
            val current=store.streams(owner).firstOrNull {it.id==platform.id} ?: platform
            store.stream(owner,current.copy(work=current.work+mapOf("savedCycle" to cycle,"savedScanAt" to System.currentTimeMillis().toString(),"savedThreadOffset" to nextThreadOffset.toString())))
        }
    }
    private fun validCapturedPost(post: Post): Boolean {
        if(post.preview||post.body.length.toLong()+post.title.length>1024*1024)return false
        val url=post.canonicalUrl.toHttpUrlOrNull() ?: return false
        if(!url.isHttps||url.username.isNotEmpty()||url.password.isNotEmpty())return false
        val target=runCatching {repo.routes.recognize(post.canonicalUrl,post.platform)}.getOrNull() ?: return false
        return target.platform==post.platform&&target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&ContentIdentity.key(post.platform,target.canonicalUrl)==ContentIdentity.key(post.platform,post.canonicalUrl)
    }
    private fun platformDocuments(owner: String,origin: String,comments: List<Post>,fetchedAt: Long): List<ContextDocument> {
        val root=store.record(owner)?.snapshot?.roots?.firstOrNull {CrossPosts.originalKey(it)==origin}
        return comments.map {comment->
            val text=contextReadable(PostPresentation.content(comment),comment,repo)
            val own=ContextSources.reply(origin,comment.platform,comment.id)
            val aliases=listOfNotNull(comment.relationId,ContentIdentity.key(comment.platform,comment.canonicalUrl)).map {ContextSources.reply(origin,comment.platform,it)}.distinct().filter {it!=own}
            val rootKeys=root?.let {listOfNotNull(it.id,it.canonicalUrl,it.relationId).map {id->ContextSources.reply(origin,it.platform,id)}.toSet()}.orEmpty()
            val parent=(comment.sourceParentId ?: comment.parentId)?.takeIf {it.isNotBlank()}?.let {ContextSources.reply(origin,comment.platform,it)}
                ?.takeIf {it!=own&&it !in aliases&&it !in rootKeys}
            ContextDocument("comment:${CrossPosts.originalKey(comment)}",ContentRevisions.signature(comment),text,author=comment.author,
                url=repo.publicUrl(comment.canonicalUrl,comment.platform),kind="comment",fetchedAt=fetchedAt,
                metadata=buildMap {
                    put("root",origin);put("links",repo.gson.toJson(comment.links.orEmpty()));put("platform",comment.platform.label)
                    put("contextKey",own);if(aliases.isNotEmpty())put("contextAliases",repo.gson.toJson(aliases))
                    parent?.let {put("contextParent",it)}
                    if(comment.extractionCoverage?.replyParent==ExtractionStatus.UNAVAILABLE ||
                        comment.conversationId!=null&&comment.sourceParentId==null&&comment.parentId==null)put("contextParentUnavailable","true")
                    putAll(publicationTime(comment.publishedAt))
                })
        }
    }
    private fun sharedDocuments(owner: String,origin: String,page: AnywherePage,at: Long,parent: String?=null): List<ContextDocument> =buildList {
        fun id(value: String)="shared:${Repository.hash(page.pageUrl)}:$value"
        fun walk(items: List<AnywhereComment>,parentId: String?,depth: Int) {
            if(depth>8)return
            for(comment in items) {
                val docId=id(comment.id)
                val parentKey=if(comment.id==parentId)store.document(owner,docId)?.document?.metadata?.get("contextParent")else parentId?.let(::id)
                add(ContextDocument(docId,Repository.hash("${comment.deleted}|${comment.text}"),if(comment.deleted)"[Comment deleted]"else comment.text,
                    author=comment.username,url=page.pageUrl,kind="comment",fetchedAt=at,metadata=buildMap {
                        put("root",origin);put("platform","Shared discussion");put("commentId",comment.id);if(comment.deleted)put("deleted","true")
                        parentKey?.let {put("contextParent",it)};putAll(publicationTime(comment.timestamp))
                    }))
                walk(comment.children,comment.id,depth+1)
            }
        }
        walk(page.items,parent,0)
    }
    private suspend fun readStream(owner: String,stream: ContextStream,background: Boolean) {
        when(stream.kind) {
            "platform"->readPlatform(owner,stream)
            "shared"->readShared(owner,stream)
            "link"->readLink(owner,stream,background)
            "publication"->readPublication(owner,stream)
            "media"->{
                val record=store.record(owner) ?: return
                val result=ContextMediaEvidence(repo).read(record,stream,background)
                store.ingest(owner,result.documents.map {parentLink(it,stream)},stream.copy(status=if(result.more)"pending"else if(result.limited)"limited"else "done",message=result.message,fetchedAt=System.currentTimeMillis(),count=stream.count+result.documents.size,limited=result.limited,work=result.work))
            }
            "limit"->store.stream(owner,stream.copy(status="limited"))
            "saved"->store.stream(owner,stream.copy(status="limited",limited=true))
        }
    }
    private suspend fun readPlatform(owner: String,stream: ContextStream) {
        val page=try {discussionRead(stream.root,stream.previous)}catch(e: CancellationException){throw e}catch(e: Exception){
            if(stream.previous!=null){store.stream(owner,stream.copy(previous=null,status="limited",message="Discussion continuation expired · refresh to rediscover it"));return};throw e
        }
        val comments=Discussions.comments(stream.root,page) {parent->store.hasContextKey(owner,ContextSources.reply(stream.originKey,stream.root.platform,parent))}
        val docs=platformDocuments(owner,stream.originKey,comments,page.provenance?.fetchedAt ?: System.currentTimeMillis())
        checkSpace(store.record(owner) ?: return,docs.sumOf {it.text.toByteArray().size.toLong()})
        val repeated=page.next!=null&&page.next in stream.cursors
        val limited=repeated||page.continuationIssue!=null||page.notice?.contains("first 200",true)==true
        val more=page.next!=null&&!limited
        store.ingest(owner,docs,stream.copy(previous=if(more)page.copy(posts=emptyList())else null,
            cursors=stream.cursors+listOfNotNull(page.next),status=if(more)"pending"else if(limited)"limited"else "done",
            message=if(limited)page.continuationIssue ?: "The source stopped providing further comments"else page.notice ?: if(more)"More comments remain"else "Available comments read",
            count=stream.count+docs.count {store.document(owner,it.id)==null},fetchedAt=System.currentTimeMillis(),limited=limited))
        docs.forEach {enqueueCommentLinks(owner,it,stream.root)}
        // Follow explicit reply targets when the source supplies readable child
        // discussions; an omitted parent is never fabricated.
        comments.filter {it.replyCount?.let {count->count>0}==true&&it.canonicalUrl!=stream.root.canonicalUrl}.forEach {comment->
            val target=runCatching {Discussions.target(comment,repo.routes,popular=false)}.getOrNull()
            val id="branch:${CrossPosts.originalKey(comment)}"
            if(target!=null&&store.streams(owner).none {it.id==id})store.stream(owner,ContextStream(id,comment,originKey=stream.originKey))
        }
    }
    private suspend fun readShared(owner: String,stream: ContextStream) {
        if(!repo.commentAnywhere.enabled){store.stream(owner,stream.copy(status="disabled",message="Shared discussion is disabled"));return}
        val url=AnywhereUrls.root(stream.root,repo.routes)
        val parent=stream.pendingParents.firstOrNull()
        val page=repo.commentAnywhere.read(url,CommentOrder.NEWEST,force=true,limit=stream.rootLimit,parentId=parent)
        val flat=page.flattened().map {it.first}
        val docs=sharedDocuments(owner,stream.originKey,page,page.fetchedAt,parent)
        val visited=stream.visitedParents+listOfNotNull(parent)
        val pending=(stream.pendingParents.drop(if(parent==null)0 else 1)+flat.filter {it.hasMore}.map {it.id}).distinct().filter {it !in visited}
        val expandRoot=parent==null&&page.limited&&stream.rootLimit<100
        val rootLimited=stream.limited||(parent==null&&page.limited&&stream.rootLimit>=100)
        val more=expandRoot||pending.isNotEmpty()
        store.ingest(owner,docs,stream.copy(rootLimit=if(expandRoot)(stream.rootLimit+30).coerceAtMost(100)else stream.rootLimit,
            pendingParents=if(expandRoot)emptyList()else pending,visitedParents=visited,status=if(more)"pending"else if(rootLimited)"limited"else "done",
            message=if(rootLimited)"The service limits accessible top-level comments"else if(more)"Reading remaining replies"else "Available shared replies read",
            fetchedAt=System.currentTimeMillis(),count=stream.count+docs.count {store.document(owner,it.id)==null},limited=rootLimited))
        docs.forEach {enqueueCommentLinks(owner,it,stream.root)}
    }
    private fun parentLink(document: ContextDocument,stream: ContextStream): ContextDocument =document.copy(
        metadata=(document.metadata-"contextParent")+(stream.commentDocument ?: stream.work["signalParent"])?.let {mapOf("contextParent" to it)}.orEmpty())
    private fun linkContext(document: ContextDocument,stream: ContextStream): ContextDocument =parentLink(document,stream).copy(
        kind=if(stream.commentContext.isBlank())"article"else "comment",
        parentText=if(stream.commentDocument==null)stream.commentContext else "")
    private suspend fun readLink(owner: String,stream: ContextStream,background: Boolean) {
        val url=repo.publicUrl(stream.root.canonicalUrl)
        if(YouTubeUrls.videoId(url)!=null) {
            val result=ContextMediaEvidence(repo).youtube(owner,url,stream,background)
            store.ingest(owner,result.documents.map {parentLink(it,stream)},stream.copy(status=if(result.more)"pending"else if(result.limited)"limited"else "done",message=result.message,fetchedAt=System.currentTimeMillis(),count=stream.count+result.documents.size,limited=result.limited,work=result.work));return
        }
        if(url.substringBefore('?').endsWith(".pdf",true)||stream.work["articlePdf"]=="true") {
            val result=try {ContextMediaEvidence(repo).pdf(owner,url,stream,background)}catch(e:CancellationException){throw e}catch(e:Exception) {
                val original=stream.work["articleOriginal"] ?: throw e
                val failed=stream.work["articlePdfFailures"]?.toIntOrNull() ?: 0
                if(failed>=2)throw e
                acquisition.articles.reject(original,url)
                store.stream(owner,stream.copy(root=stream.root.copy(canonicalUrl=original),status="pending",work=mapOf("articlePdfFailures" to (failed+1).toString())))
                return
            }
            val documents=result.documents.map {doc->stream.work["articleOriginal"]?.let {original->val base=doc.metadata["articleMediaRevision"] ?: doc.revision
                doc.copy(revision=Repository.hash(base+stream.work.filterKeys {it in setOf("archivedAt","sourceVersion","retracted")}.toSortedMap().toString()),url=original+"#page="+doc.metadata["page"],metadata=doc.metadata+
                stream.work.filterKeys {it in setOf("articleRoute","archivedAt","sourceVersion","license","retracted")}+
                mapOf("articleMediaRevision" to base,"retrievalUrl" to url,"medium" to (doc.metadata["medium"].orEmpty().substringBefore(" · ")+" · "+stream.work["articleRoute"].orEmpty())))} ?: doc}
            val keep=stream.work.filterKeys {it.startsWith("article")||it in setOf("archivedAt","sourceVersion","license","retracted")}
            store.ingest(owner,documents.map {parentLink(it,stream)},stream.copy(status=if(result.more)"pending"else if(result.limited)"limited"else "done",message=result.message,fetchedAt=System.currentTimeMillis(),count=stream.count+result.documents.size,limited=result.limited,work=result.work+keep));return
        }
        val target=runCatching {repo.routes.recognize(url)}.getOrNull()
        if(target!=null&&target.kind in setOf(TargetKind.POST,TargetKind.THREAD)) {
            val page=repo.pool.load(target,originalFallback=repo.prefs.originalFallback,background=background)
            val docs=listOfNotNull(PostLinks.linkedPost(page,target)).map {post->ContextDocument("link:${CrossPosts.originalKey(post)}",ContentRevisions.signature(post),contextReadable(PostPresentation.content(post),post,repo),post.title,post.author,repo.publicUrl(post.canonicalUrl,post.platform),"article",fetchedAt=System.currentTimeMillis(),metadata=mapOf("root" to stream.originKey)+publicationTime(post.publishedAt)).let {parentLink(it,stream)}}
            check(docs.isNotEmpty()){ "The linked post could not be read" }
            store.ingest(owner,docs,stream.copy(status="done",message="Linked post read",fetchedAt=System.currentTimeMillis(),count=docs.size));return
        }
        val docId="article:${Repository.hash(url)}"
        val cached=store.document(owner,docId)?.document
        // Old washed bodies did not retain their source relationships. Fetch
        // them once with the new extractor instead of accepting a perpetual
        // 304 that can never discover the newly supported paths.
        val prepared=cached?.takeIf {it.metadata["readingFormat"]==ContextArticles.READING_FORMAT&&it.metadata["signalDiscoveryPolicy"]==SignalDiscovery.POLICY}
        val validators=ContextArticleValidators(prepared?.metadata?.get("etag"),prepared?.metadata?.get("lastModified"))
        val fetched=articleRead(url,url,validators,cached?.metadata?.get("retrievalUrl"))
        if(fetched is ContextArticleFetch.NotModified){
            // A 304 validates bytes, not a link's current discussion role.
            // Promote a formerly comment-linked page or attach its now-known
            // parent without downloading/parsing the article again.
            cached?.let {
                store.ingest(owner,listOf(linkContext(it,stream)))
                val references=runCatching {repo.gson.fromJson(it.metadata["signalReferences"],Array<SignalReference>::class.java).toList()}.getOrDefault(emptyList())
                discoverArticleSources(owner,stream,it,references,it.metadata["publicationDoi"])
            }
            store.stream(owner,stream.copy(status="done",message="Article unchanged",fetchedAt=fetched.checkedAt,count=1));return
        }
        if(fetched is ContextArticleFetch.PublicPdf) {
            val next=stream.copy(root=stream.root.copy(canonicalUrl=fetched.url),status="pending",work=stream.work+
                articleAccessMetadata(fetched.access)+mapOf("articleOriginal" to fetched.originalUrl,"articlePdf" to "true"))
            store.stream(owner,next)
            return
        }
        val article=(fetched as ContextArticleFetch.Ready).document
        val text=article.readingText
        val doc=ContextDocument(docId,Repository.hash(text+listOf(article.access?.archivedAt,article.access?.version,article.access?.retracted)),text,article.title,article.author.orEmpty(),repo.publicUrl(article.originalUrl),if(stream.commentContext.isBlank())"article"else "comment",parentText=if(stream.commentDocument==null)stream.commentContext else "",fetchedAt=System.currentTimeMillis(),metadata=buildMap {
            put("root",stream.originKey);put("readingFormat",ContextArticles.READING_FORMAT);stream.commentDocument?.let {put("contextParent",it)};put("retrievalUrl",article.finalUrl);article.etag?.let {put("etag",it)};article.lastModified?.let {put("lastModified",it)}
            put("signalDiscoveryPolicy",SignalDiscovery.POLICY)
            putAll(publicationTime(article.publishedAt));if(article.truncated)put("truncated","true")
            article.access?.let {putAll(articleAccessMetadata(it))}
            if(!article.references.isNullOrEmpty())put("signalReferences",repo.gson.toJson(article.references))
            article.publicationDoi?.let {put("publicationDoi",it)}
        })
        checkSpace(store.record(owner) ?: return,text.toByteArray().size.toLong())
        store.ingest(owner,listOf(linkContext(doc,stream)),stream.copy(status=if(article.truncated)"limited"else "done",message=if(article.truncated)"Article exceeds the extraction window"else "Article body read",fetchedAt=System.currentTimeMillis(),count=1,limited=article.truncated))
        discoverArticleSources(owner,stream,doc,article.references.orEmpty(),article.publicationDoi)
    }
    private fun articleAccessMetadata(access:ArticleAccess)=buildMap {
        put("articleRoute",access.route);access.archivedAt?.let {put("archivedAt",java.time.Instant.ofEpochMilli(it).toString())}
        access.version?.let {put("sourceVersion",it)};access.license?.let {put("license",it)};if(access.retracted)put("retracted","true")
        val edition=access.version?.let {when(it){"acceptedVersion"->"accepted manuscript";"submittedVersion"->"author manuscript";"publishedVersion"->"published version";else->it}}
        val kind=when(access.route){"Existing archive capture"->"Archived article text";"Original publisher"->"Publisher article text";"Publisher text edition"->"Publisher text edition";else->"Public article text"}
        put("medium",(listOf(kind)+listOfNotNull(edition,access.archivedAt?.let {"archived "+java.time.Instant.ofEpochMilli(it)})).joinToString(" · "))
    }
    private suspend fun process(owner: String,engine: ContextEngine,limit: Int,languages:LanguageTools.Session,session:ContextReviewSession,checkWork: ()->Unit): Int {
        val pending=store.pendingDocuments(owner).groupBy {it.document.metadata["root"].orEmpty()}
        val prioritized=pending.mapValues {(_,items)->items.sortedBy {when(it.document.kind){"post"->0;"article"->1;else->2}}}
        val interleaved=buildList {val max=prioritized.values.maxOfOrNull {it.size} ?: 0;for(index in 0 until max)prioritized.values.forEach {it.getOrNull(index)?.let(::add)}}
        var count=0
        val background=ContextBackgroundResolver(store,owner)
        val ready=mutableListOf<ContextDocument>();val readings=mutableMapOf<String,ContextReadingInput>();val backgrounds=mutableMapOf<String,ContextBackground>()
        fun reserve(bytes:Long){checkSpace(store.record(owner) ?: error("Context was removed"),bytes)}
        suspend fun read(document:ContextDocument,field:String,text:String):ContextReadingText = languages.reading(store,owner,document,field,text,document.metadata["language"].orEmpty(),::reserve,checkWork)
        languages.beginPreparation()
        try {for(item in store.reviewOrder(owner,interleaved).take(limit)) {
            checkWork();val document=item.document
            try {
                update(owner,"processing","Preparing readable source text")
                val body=read(document,"body",document.text)
                val title=read(document,"title",document.title)
                val parent=background.readingBackground(document){d,field,text->read(d,field,text).text}
                val fallback=if(parent==null&&document.parentText.isNotBlank())read(document,"parent",document.parentText)else null
                readings[document.id]=ContextReadingInput(body,title.text,fallback?.text.orEmpty(),title.languageUnconfirmed,fallback?.languageUnconfirmed==true);parent?.let {backgrounds[document.id]=it};ready+=document
                store.removeStream(owner,"language:${Repository.hash(document.id)}")
            }catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception) {
                if(failure !is SourceLanguageUnavailable)throw failure
                val record=store.record(owner) ?: return count
                val root=record.snapshot.roots.firstOrNull {CrossPosts.originalKey(it)==document.metadata["root"]} ?: record.snapshot.roots.first()
                val language=(failure as? SourceLanguageUnavailable)?.language
                val label=language?.let {repo.languages.label(it)} ?: "Source language"
                store.stream(owner,ContextStream("language:${Repository.hash(document.id)}",root,"review",status="limited",limited=true,
                    message="$label · ${failure.message?.take(160) ?: "translation pending"}",work=mapOf("document" to document.id,"revision" to document.revision)))
                store.deferReview(owner,document.id,document.revision);count++
            }
        }}finally {languages.finishPreparation()}
        if(ready.isEmpty())return count
        checkWork()
        checkSpace(store.record(owner) ?: error("Context was removed"),ready.sumOf {it.id.toByteArray().size.toLong()+repo.gson.toJson(session).toByteArray().size+64L})
        val claims=store.beginReviews(owner,session,ready.map {ContextPreparedReview(it,readings.getValue(it.id),backgrounds[it.id])})
            ?: throw ContextDeferred("Sources changed · progress saved for another check",true)
        try {for(documents in ready.chunked(engine.processingPlan.sourcesPerCall)) {
            checkWork()
            if(!store.reviewsCurrent(owner,documents.associate {it.id to claims.getValue(it.id)}))throw ContextDeferred("Sources changed · progress saved for another check",true)
            update(owner,"processing",if(documents.any {it.kind=="post"})"Understanding the post"else "Reading for new information")
            try {
                val result=engine.process(documents,store.findings(owner),documents.flatMap {d->store.outcomes(owner,d.id,d.revision)},
                    backgrounds=backgrounds.filterKeys {key->documents.any {it.id==key}},readings=readings.filterKeys {key->documents.any {it.id==key}}) {unit->
                    withContext(NonCancellable){
                        checkSpace(store.record(owner) ?: error("Context was removed"),repo.gson.toJson(unit).toByteArray().size.toLong())
                        if(!store.commitReviewedUnit(owner,unit,claims.getValue(unit.documentId)))throw ContextDeferred("Sources changed · progress saved for another check",true)
                    }
                    checkWork()
                }
                documents.filter {it.id in result.documents}.forEach {d->
                    if(!store.finishReview(owner,d.id,d.revision,claims.getValue(d.id)))throw ContextDeferred("Sources changed · progress saved for another check",true)
                    store.removeStream(owner,"review:${Repository.hash(d.id)}")
                }
            }catch(e: Exception) {
                if(e !is ContextProcessingException&&e !is AiResponseLimitException)throw e
                // No malformed batch may silently erase one of its sources.
                // Already committed units retain their receipts for retry.
                val record=store.record(owner) ?: return count
                val failures=documents.map {d->
                    val root=record.snapshot.roots.firstOrNull {CrossPosts.originalKey(it)==d.metadata["root"]} ?: record.snapshot.roots.first()
                    ContextReviewFailure(d.id,d.revision,claims.getValue(d.id),ContextStream("review:${Repository.hash(d.id)}",root,"review",status="limited",limited=true,
                        message="A passage could not be reliably reviewed · kept for retry",work=mapOf("document" to d.id,"revision" to d.revision,"reviewFailure" to e.message.orEmpty().take(240))))
                }
                if(!store.deferReviewedSources(owner,failures))throw ContextDeferred("Sources changed · progress saved for another check",true)
            }
            count+=documents.size
        }}finally {withContext(NonCancellable){store.releaseReviews(owner,claims)}}
        return count
    }
    private fun retryReviews(owner: String) {
        store.retryDeferred(owner)
        store.streams(owner).filter {it.kind=="review"}.forEach {store.removeStream(owner,it.id)}
    }
    private suspend fun publish(owner: String,model: ContextModel,expectedRevision: String,modelIdentity: String,processingPolicy: String) {
        val input=store.publicationInput(owner) ?: return
        val record=input.record
        val documents=(input.documents+input.replyEvidence.documents.map {ContextStoredDocument(it,false)}).associateBy {it.document.id}
        // A root with no retained finding still defines what its comments
        // discuss. Publication citations deliberately exclude uncited sources;
        // they must not also decide whether the subject exists for admission.
        val subjectDocument=store.document(owner,"merged:$owner")?.document
        val subject=subjectDocument?.let {store.reading(owner,it,"body",it.text)?.text}.orEmpty()
        val engine=ContextEngine(model,modelIdentity,sourceLabel={sourceLabel(documents[it]?.document)},
            sourcePublications={publicationSources(documents[it]?.document)},
            sourceEvidenceScope={documents[it]?.document?.let(ContextEvidenceScope::from)},sourceReviewIdentity={input.reviewIdentities[it]},
            sourceReplyContext={input.replyEvidence.contexts.source(it)},readerBrief=true,readerSubject={subject})
        fun checkModel() {if(repo.ai.contextIdentity()!=modelIdentity)throw ContextDeferred("Model changed · tap Refresh to continue with your selected model")}
        checkModel()
        if(!processingPolicy.endsWith("|"+engine.processingIdentity))throw ContextDeferred("Model limits changed · tap Refresh to continue")
        if(record.snapshot.revision!=expectedRevision)throw ContextDeferred("Post changed · preparing its updated Context",true)
        val compatible=record.model==modelIdentity&&record.compositionPolicy==processingPolicy
        val prior=store.view(owner)
        // An unread comment cannot veto the post and linked material that
        // were successfully read. Earlier prose and its exact citations are
        // retained separately; an explicitly saved snapshot stays protected.
        if(compatible&&input.findings.isEmpty()&&record.brief.isNotBlank()&&store.hasUnreviewed(owner))return
        val findings=input.findings
        fun meaning(items: List<ContextFinding>,reviews:Map<String,String>?,replies:ContextReplyContexts?,document: (String)->ContextDocument?)=Repository.hash(repo.gson.toJson(items.sortedBy {it.id}.map {finding->
            listOf(finding.id,ContextEngine.summaryEvidenceFingerprint(finding),finding.sources.sorted().map {source->val doc=document(source)
                listOf(sourceLabel(doc),doc?.metadata?.get("published"),doc?.metadata?.get("editionTimes"),doc?.metadata?.get("editionTimes2"),doc?.metadata?.get("editionTimesOmitted"),doc?.let {ContextEvidenceScope.from(it).identity},reviews?.get(source),replies?.source(source))})
        }))
        val meaningStamp=meaning(findings,input.reviewIdentities,input.replyEvidence.contexts) {documents[it]?.document}
        val support=findings.sortedBy {it.id}.map {finding->listOf(finding.id,finding.sources.sorted(),finding.quotes.toSortedMap(),finding.readingQuotes.orEmpty().toSortedMap(),
            finding.sources.sorted().map {source->documents[source]?.document?.let {listOf(source,it.revision,it.author,it.metadata["published"],it.metadata["medium"],it.metadata["uncertain"])} ?: listOf(source)})}
        val stamp=Repository.hash(subject+"\n"+meaningStamp+repo.gson.toJson(support))
        if(compatible&&stamp==record.findingStamp) {
            if(record.stale)commitPublication(record,expectedRevision,input)
            return
        }
        if(findings.isEmpty()) {
            val signalStamp=Repository.hash(meaning(emptyList(),input.reviewIdentities,input.replyEvidence.contexts){documents[it]?.document})
            val updatedAt=if(compatible&&record.brief.isEmpty()&&record.briefSignalStamp==signalStamp)record.updatedAt else System.currentTimeMillis()
            commitPublication(record.copy(brief="",briefFindingIds=emptyList(),briefSources=emptyList(),briefSignalStamp=signalStamp,findingStamp=stamp,
                updatedAt=updatedAt,model=modelIdentity,compositionPolicy=processingPolicy),expectedRevision,input)
            store.pruneCompactions(owner,emptySet())
            return
        }
        val priorSources=prior?.documents.orEmpty().associateBy {it.document.id}
        if(compatible&&record.brief.isNotBlank()&&prior!=null&&prior.findings.isNotEmpty()&&meaning(prior.findings,prior.reviewIdentities,prior.replyEvidence?.contexts) {priorSources[it]?.document}==meaningStamp) {
            // Retrieval bookkeeping can update the manifest without rewriting
            // unchanged evidence. Changed quotations, reading text or speaker
            // associations must reach compaction even when finding words match.
            commitPublication(record.copy(findingStamp=stamp,updatedAt=System.currentTimeMillis()),expectedRevision,input)
            return
        }
        if(compatible&&record.brief.isNotBlank()&&prior!=null&&input.replyEvidence.contexts.references.isEmpty()&&prior.replyEvidence?.contexts?.references.orEmpty().isEmpty()&&ContextBriefReuse.onlyExactCopiesAdded(
                prior.findings,findings,prior.documents.map {it.document},input.documents.map {it.document})) {
            // A complete, identical extra source updates the local manifest,
            // citations and copy associations without asking a model to rewrite
            // the same information. Edits, missing support and new context fail
            // this check and continue through normal composition.
            commitPublication(record.copy(findingStamp=stamp,updatedAt=System.currentTimeMillis()),expectedRevision,input)
            return
        }
        update(owner,"processing","Composing Context")
        val summary=engine.summarizeResult(findings,record.brief.takeIf {compatible}.orEmpty(),store.compactions(owner)) {receipt->withContext(NonCancellable){checkModel();store.commitCompaction(owner,receipt)}}
        // A successful selection with no useful additions is a valid result.
        // It clears an older noisy brief without inventing filler prose.
        val selectedIds=summary.selectedFindings
        val selected=ContextBriefEvidence.select(findings,selectedIds,summary.sources)
        val signalStamp=Repository.hash(summary.text+meaning(selected,input.reviewIdentities,input.replyEvidence.contexts){documents[it]?.document})
        val updatedAt=if(compatible&&record.briefSignalStamp==signalStamp)record.updatedAt else System.currentTimeMillis()
        withContext(NonCancellable) {
            checkModel()
            commitPublication(record.copy(brief=summary.text,updatedAt=updatedAt,findingStamp=stamp,model=modelIdentity,compositionPolicy=processingPolicy,briefFindingIds=selectedIds,briefSignalStamp=signalStamp,briefSources=summary.sources),expectedRevision,input)
            store.pruneCompactions(owner,summary.receipts.map {it.id}.toSet())
        }
    }
    private fun commitPublication(record: ContextRecord,expectedRevision: String,input:ContextPublicationInput) {
        if(!store.publishIfCurrent(record,expectedRevision,input))throw ContextDeferred("Sources changed · preparing updated Context",true)
    }
    private fun sourceLabel(owner: String,source: String): String=sourceLabel(store.document(owner,source)?.document)
    private fun sourceLabel(document: ContextDocument?): String {
        document ?: return "Public source"
        val medium=document.metadata["medium"].orEmpty()
        if(document.metadata["articleRoute"]!=null) {
            val name=document.author.trim().removePrefix("@").take(100).ifBlank {document.title.take(100).ifBlank {document.url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: "Linked article"}}
            return name+sourceQualification(document)
        }
        val label=document.author.trim().removePrefix("@").take(100).ifBlank {document.metadata["platform"]?.take(60) ?: medium.take(100).ifBlank {when(document.kind){"article"->document.title.take(100).ifBlank {"Linked source"};"post"->"Original post";else->"Public reply"}}}
        return label+if(medium.startsWith("Automatically")&&label.startsWith("Automatically")&&document.metadata["uncertain"]!="true")""else sourceQualification(document)
    }
    private fun sourceQualification(document:ContextDocument)=when {document.metadata["uncertain"]=="true"->" (recognition uncertain)";document.metadata["medium"].orEmpty().startsWith("Automatically")->" (automatic captions)";else->""}
    private fun publicationSources(document:ContextDocument?):List<ContextSourcePublication> {
        document ?: return emptyList()
        return ContextSources.publications(document).map {it.copy(speaker=if(document.metadata["editionTimes"]==null)sourceLabel(document)else it.speaker+sourceQualification(document))}
    }
    /** A short ledger needs no second interpretation. Code can join already
     * extracted contributions with their real speakers and keep events apart. */
    private fun conciseContext(documents:Map<String,ContextStoredDocument>,findings: List<ContextFinding>,replies:ContextReplyContexts): String? {
        val groups=ContextContributions.groups(findings,replies::source)
        if(groups.any {it.reply!=null}) {
            if(findings.size>1&&findings.any {finding->finding.sources.none {(finding.readingQuotes?.get(it) ?: finding.quotes[it])==finding.text}})return null
            return ContextContributions.render(groups) {sources->
                val contexts=sources.map {source->val doc=documents[source]?.document;sourceLabel(doc) to publicationSources(doc)}
                if(contexts.any {it.second.isNotEmpty()})ContextPublicationFrame.display(contexts.flatMap {(name,times)->times.ifEmpty {listOf(ContextSourcePublication(name,null))}})
                else contexts.map {it.first}.distinct().sorted().take(3).joinToString(" · ")
            }.takeIf {it.length<=4096}
        }
        if(findings.size !in 1..3)return null
        val aliases=Regex("\\b[SK][0-9]+\\b")
        val paragraphs=findings.map {finding->
            val raw=finding.quotes.values.joinToString(" ")
            val unsupported=aliases.findAll(finding.text).any {it.value !in raw}
            val text=if(unsupported)(finding.readingQuotes?.values?.firstOrNull() ?: finding.quotes.values.firstOrNull()).orEmpty()else finding.text
            val contexts=finding.sources.map {source->val doc=documents[source]?.document;sourceLabel(doc) to publicationSources(doc)}
            val labels=if(contexts.any {it.second.isNotEmpty()})ContextPublicationFrame.display(contexts.flatMap {(name,times)->times.ifEmpty {listOf(ContextSourcePublication(name,null))}})
                else contexts.map {it.first}.distinct().sorted().let {names->names.take(3).joinToString(" · ")+if(names.size>3)" · ${names.size-3} more editions" else ""}
            val attributed=Regex("^${Regex.escape(labels)}\\s+(?:says|states|reports|notes|explains|corrects|clarifies|asks|claims|adds|writes)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)
            if(attributed)text else "$labels: $text"
        }
        return paragraphs.joinToString("\n\n").takeIf {it.length<=900}
    }
    private fun checkSpace(record: ContextRecord,additional: Long) {
        check(repo.context.filesDir.usableSpace>additional+256L*1024*1024){"Storage is low · progress saved"}
        if(!record.saved)check(store.logicalBytes(false)+temporaryBytes()+additional<=repo.prefs.cacheMb.toLong()*1024*1024){"Context reached the reader cache allowance · progress saved"}
    }
    private fun update(id: String,status: String,message: String) {store.record(id)?.let {store.save(it.copy(status=status,message=message))}}
    private fun publicError(error: Exception): String=error.message?.take(240)?.takeIf {it.isNotBlank()} ?: "Some material could not be read · progress saved"
    suspend fun cleanup(clear: Boolean=false) {
        repo.languages.cleanup(clear)
        // Capture eligible records before the suspending Room reads. A new
        // Context created while those reads are in flight belongs to a later
        // cleanup pass, not to an older cache snapshot.
        val candidates=store.all()
        val posts=cleanupCacheKeys()
        val saved=repo.dao.allBookmarks().map {it.key}.toSet()
        candidates.forEach {record->
            val keys=record.snapshot.roots.map(repo::key)
            val protected=keys.any {it in saved}
            // The bookmark collector handles removal. A stale cleanup read
            // must never undo protection added while this pass was waiting.
            if(protected&&!record.saved)store.protect(record.id,true)
            if(!protected&&!record.saved&&!jobs.containsKey(record.id)&&(clear||keys.none {it in posts}))store.deleteIfUnchanged(record)
        }
        val owners=store.all().map {it.id}.toSet()
        val finished=store.all().filter {it.status in setOf("ready","partial")}.map {it.id}.toSet()
        val expiredBefore=System.currentTimeMillis()-24L*60*60*1000
        File(repo.context.cacheDir,"context-work").listFiles()?.filter {!jobs.containsKey(it.name)&&(clear||it.name !in owners||it.name in finished||it.lastModified()<expiredBefore)}?.forEach {it.deleteRecursively()}
    }
    fun temporaryBytes(): Long=File(repo.context.cacheDir,"context-work").walkTopDown().filter {it.isFile}.sumOf {it.length()}
}
private class ContextDeferred(message: String,val requeue: Boolean=false): Exception(message)
