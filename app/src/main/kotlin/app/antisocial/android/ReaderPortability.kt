/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.room.withTransaction
import app.antisocial.core.*
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class PortableImportPreview(
    val document: PortableDocument,
    val sources: List<Follow> = document.sources.filter {ReaderScope.isFeedSource(it.target.platform)},
    val channels: Int = document.channels.size,
    val collections: Int = 0,
    val savedPosts: Int = 0,
    val hasFilters: Boolean = false,
    val hasSettings: Boolean = false,
    val hasProgress: Boolean = document.progress!=null,
    val warnings: List<String> = document.warnings,
    val isState: Boolean = document.kind==PortableKind.STATE
)
data class PortableImportOptions(val channels: Boolean=true,val filters: Boolean=true,val collections: Boolean=true,val settings: Boolean=false,val progress: Boolean=false)
data class PortableImportResult(val addedSources: Int=0,val existingSources: Int=0,val channels: Int=0,val collections: Int=0,val savedPosts: Int=0,val warnings: List<String> = emptyList())
internal data class PortableSaved(val post: Post,val savedAt: Long,val details: SavedCopyDetails?=null,val collectionIds: List<String> = emptyList())
internal data class PortableLibrary(val collections: List<LibraryCollectionRow> = emptyList(),val saved: List<PortableSaved> = emptyList())
internal data class PortableProgress(val version: Int=1,val feeds: Map<String,FeedMemory> = emptyMap(),val readKeys: Set<String> = emptySet(),val readAt: Long=0)
private data class ValidatedReaderState(val settings: JSONObject?,val filters: JSONObject?,val library: PortableLibrary,val progress: PortableProgress?,val localDiscovery:PortableLocalDiscovery=PortableLocalDiscovery())

/** Data files are parsed and validated without fetching any URL. Imports merge;
 * existing accounts, saved copies, collection contents and connection policy win. */
class ReaderPortability(private val repo: Repository) {
    private val mutex=Mutex()
    private val gson get()=repo.gson
    private val settingKeys=setOf("localAreas","localArea","localDistanceUnit","archiveChecks","archiveRecovery","interval","wifiOnly","cacheMb","videoCacheMb","videoCacheDays","videoDownloadLimitMb","videoDownloadWifiOnly","historyFullText","historyTextMb","unseenDays","viewedDays","historyDays","theme","platforms","sponsorBlockEnabled","sponsorBlockCategories","readableMentions","mentionLookups")

    private fun authentic(follow: Follow): Follow {
        val url=if(follow.target.platform==Platform.RSS)RssSources.target(follow.feedUrl ?: follow.target.canonicalUrl).canonicalUrl else repo.publicUrl(follow.target.canonicalUrl,follow.target.platform)
        return if(follow.target.platform==Platform.RSS)follow.copy(target=RssSources.target(url),feedUrl=url)else follow.copy(target=follow.target.copy(canonicalUrl=url))
    }
    private fun normalized(document: PortableDocument): PortableDocument=document.copy(sources=document.sources.map {incoming->
        val source=authentic(incoming)
        val target=if(source.target.platform==Platform.RSS)RssSources.target(source.feedUrl ?: source.target.canonicalUrl)else {
            val derived=repo.routes.recognize(source.target.canonicalUrl,source.target.platform)
            require(derived==null||derived.platform==source.target.platform){"A source URL does not belong to its declared platform."}
            derived ?: source.target.takeIf {document.kind==PortableKind.STATE&&it.kind==TargetKind.FEED&&source.feedUrl!=null}
                ?: throw IllegalArgumentException("This social source URL is not supported. Use RSS / Atom for an ordinary feed.")
        }
        // The displayed original URL is authoritative. Imported identity/kind
        // hints must not make one route request a different creator or search.
        source.copy(target=target)
    })
    suspend fun exportState(includeProgress: Boolean): ByteArray=withContext(Dispatchers.IO) {
        repo.awaitReady();repo.feedMemory.awaitReady()
        check(repo.channels.error.value==null){"Repair saved channels before exporting reader state."}
        check(repo.contentFilters.error.value==null){"Repair saved feed filters before exporting reader state."}
        val preferences=repo.prefs.export()
        val settings=JsonObject().apply {settingKeys.forEach {key->if(preferences.has(key))add(key,com.google.gson.JsonParser.parseString(preferences.get(key).toString().let {if(preferences.get(key) is String)JSONObject.quote(preferences.getString(key))else it}))};addProperty("platforms",repo.prefs.selected.joinToString(","){it.id})}
        val filters=JsonObject().apply {
            add("disabledLists",gson.toJsonTree(repo.prefs.disabledLists().sorted()))
            add("customLists",gson.toJsonTree(repo.prefs.customLists().sorted()))
            addProperty("contentRules",ContentRuleCodec.encode(repo.contentFilters.rules.value))
        }
        val library=repo.db.withTransaction {
            val links=repo.dao.allCollectionLinks().groupBy {it.ownerKey}
            val details=repo.dao.allSavedDetails().associate {it.key to gson.fromJson(it.payload,SavedCopyDetails::class.java)}
            PortableLibrary(repo.dao.allCollections(),repo.dao.allBookmarks().map {row->PortableSaved(gson.fromJson(row.payload,Post::class.java),row.savedAt,details[row.key],links[row.key].orEmpty().map {it.collectionId})})
        }
        val payload=JsonObject().apply {add("settings",settings);add("filters",filters);add("library",gson.toJsonTree(library));add("localDiscovery",LocalDiscoveryPortability.encode(repo.localDiscovery))}
        val document=normalized(PortableDocument(PortableKind.STATE,System.currentTimeMillis(),repo.dao.allFollows().map {authentic(gson.fromJson(it.payload,Follow::class.java))},repo.channels.channels.value,payload,if(includeProgress)gson.toJsonTree(repo.feedMemory.portableSnapshot()).asJsonObject else null))
        validate(document)
        PortableReader.encode(document).toByteArray(Charsets.UTF_8)
    }
    suspend fun exportPack(sourceIds: Set<String>,title: String="anti social sources"): ByteArray=withContext(Dispatchers.IO) {
        repo.awaitReady()
        val follows=repo.dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}
        require(sourceIds.isNotEmpty()&&sourceIds.all {id->follows.any {it.id==id}}){"Choose sources to share."}
        require(follows.filter {it.id in sourceIds}.all {ReaderScope.isFeedSource(it.target.platform)}){ReaderScope.VIDEO_ONLY}
        PortableReader.encode(normalized(PortableDocument(PortableKind.SOURCES,System.currentTimeMillis(),follows.filter {it.id in sourceIds}.map(::authentic),title=title))).toByteArray(Charsets.UTF_8)
    }
    suspend fun exportOpml(sourceIds: Set<String>): ByteArray=withContext(Dispatchers.IO) {
        repo.awaitReady()
        val follows=repo.dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}
        require(sourceIds.isNotEmpty()&&sourceIds.all {id->follows.any {it.id==id}}){"Choose feeds to export."}
        // A social frontend's RSS endpoint is transport configuration, not a
        // portable blog feed. Native packs carry the authentic social target.
        val feeds=follows.filter {it.id in sourceIds&&it.target.platform==Platform.RSS}.map(::authentic)
        require(feeds.isNotEmpty()){"OPML exports ordinary RSS/Atom feeds. Use a source pack for social subscriptions."}
        PortableReader.encodeOpml(feeds).toByteArray(Charsets.UTF_8)
    }
    suspend fun preview(bytes: ByteArray): PortableImportPreview=withContext(Dispatchers.IO) {
        val parsed=normalized(PortableReader.parse(bytes))
        val document=if(parsed.kind==PortableKind.SOURCES&&parsed.origin==PortableOrigin.NATIVE)PortableReader.decode(PortableReader.encode(parsed)).copy(warnings=parsed.warnings)else parsed
        val state=validate(document)
        PortableImportPreview(document,collections=state.library.collections.size,savedPosts=state.library.saved.size+state.localDiscovery.businesses.size+state.localDiscovery.events.size,hasFilters=state.filters!=null,hasSettings=state.settings!=null,
            warnings=document.warnings+
                (if(document.sources.any {!ReaderScope.isFeedSource(it.target.platform)})listOf("YouTube channel subscriptions are skipped. Saved videos and their reading material can still be imported.")else emptyList())+
                (if(state.library.saved.any {it.details?.selectedAssets?.isNotEmpty()==true})listOf("Saved text and available threads are included. Media files and video downloads are not included; no download starts during import.")else emptyList()))
    }

    suspend fun apply(preview: PortableImportPreview,selectedSourceIds: Set<String>,options: PortableImportOptions=PortableImportOptions()): PortableImportResult=withContext(Dispatchers.IO) {mutex.withLock {
        repo.awaitReady();repo.feedMemory.awaitReady()
        // Revalidate the complete structured preview before the first write.
        val reviewed=normalized(preview.document)
        val document=if(reviewed.origin==PortableOrigin.OPML) {
            require(reviewed.kind==PortableKind.SOURCES&&reviewed.channels.isEmpty()&&reviewed.statePayload==null&&reviewed.progress==null&&reviewed.sources.all {it.target.platform==Platform.RSS})
            // OPML folders are intentional interoperability data. A native
            // sources-only encoder intentionally removes private folder names.
            PortableReader.decodeOpml(PortableReader.encodeOpml(reviewed.sources,reviewed.title ?: "anti social subscriptions"))
        }else PortableReader.decode(PortableReader.encode(reviewed))
        val state=validate(document)
        require(selectedSourceIds.all {id->document.sources.any {it.id==id&&ReaderScope.isFeedSource(it.target.platform)}}){"Choose a supported social source from this file."}
        val imported=document.sources.filter {it.id in selectedSourceIds}
        val existing=repo.dao.allFollows().map {gson.fromJson(it.payload,Follow::class.java)}
        val byTarget=existing.associateBy(::sourceIdentity)
        val existingBundles=existing.map(Subscriptions::id).toSet()
        val sourceMap=mutableMapOf<String,Set<String>>()
        val excludedSourceMap=mutableMapOf<String,Set<String>>()
        val additions=mutableListOf<Follow>()
        var already=0
        document.sources.groupBy(Subscriptions::id).forEach {(oldBundle,group)->
            val chosen=group.filter {it.id in selectedSourceIds}
            val matches=chosen.mapNotNull {byTarget[sourceIdentity(it)]}
            already+=matches.size
            val known=matches.map(Subscriptions::id).toSet()
            val linked=chosen.any {it.personId!=null}
            val freshBundle=when {known.size==1->known.first();!linked->null;oldBundle !in existingBundles->oldBundle;else->"person:"+UUID.randomUUID()}
            val added=chosen.filter {sourceIdentity(it) !in byTarget}.map {source->
                source.copy(id=if(source.target.platform==Platform.RSS)RssSources.followId(source.target.canonicalUrl)else "${source.target.platform.id}:${source.target.canonicalUrl}",personId=if(linked)freshBundle else null,
                    personName=if(linked)matches.firstOrNull()?.let {it.personName ?: it.name} ?: source.personName else null)
            }
            additions+=added
            sourceMap[oldBundle]=known+added.map(Subscriptions::id)
            // An unchecked source need not be subscribed again, but a receiver
            // may already follow it. Preserve the exclusion for that known
            // public identity so its fused edition cannot bypass the rule.
            excludedSourceMap[oldBundle]=group.mapNotNull {byTarget[sourceIdentity(it)]}.map(Subscriptions::id).toSet()+added.map(Subscriptions::id)
        }
        // Missing/unselected creator rules stay explicit. An empty positive set
        // means All, so dropping an unselected ID would silently broaden a rule.
        val missing=mutableMapOf<String,String>()
        fun mapSources(ids: Set<String>,excluded: Boolean=false): Set<String> = ids.flatMap {old->(if(excluded)excludedSourceMap else sourceMap)[old].orEmpty().ifEmpty {setOf(missing.getOrPut(old){if(Regex("missing:import:[0-9a-f]{64}").matches(old))old else "missing:import:"+Repository.hash(old)})}}.toSet()
        val currentChannels=repo.channels.channels.value
        if(options.channels&&document.channels.isNotEmpty())check(repo.channels.error.value==null){"Repair your saved channels before importing channel rules."}
        val mergedChannels=currentChannels.toMutableList()
        val channelMap=mutableMapOf<String,PersonalChannel>()
        var addedChannels=0
        if(document.kind==PortableKind.STATE&&options.channels)document.channels.forEach {original->
            val mapped=original.copy(subscriptionIds=mapSources(original.subscriptionIds),excludeSubscriptionIds=mapSources(original.excludeSubscriptionIds,true))
            val same=mergedChannels.firstOrNull {it.name==mapped.name&&PersonalChannels.fingerprint(it)==PersonalChannels.fingerprint(mapped)}
            val next=same ?: mapped.copy(id=if(mergedChannels.any {it.id==mapped.id})"channel:"+UUID.randomUUID()else mapped.id,revision=1).also {mergedChannels+=it;addedChannels++}
            channelMap[original.id]=next
        }
        require(mergedChannels.size<=PersonalChannels.MAX_CHANNELS){"Import would exceed the ${PersonalChannels.MAX_CHANNELS}-channel limit. Import fewer channel rules."}
        if(options.collections)require((repo.localDiscovery.savedIds()+state.localDiscovery.businesses.map {it.id}+state.localDiscovery.events.map {it.id}).size<=2000){"Import would exceed the saved discovery allowance."}
        val changes=JSONObject()
        if(document.kind==PortableKind.STATE) {
            if(options.settings)state.settings?.keys()?.forEach {key->changes.put(key,state.settings.get(key))}
            if(options.filters)state.filters?.keys()?.forEach {key->if(key!="contentRules")changes.put(key,state.filters.get(key))}
            if(options.filters&&state.filters?.has("contentRules")==true) {
                check(repo.contentFilters.error.value==null){"Repair saved feed filters before importing rules."}
                val local=repo.contentFilters.rules.value
                val incoming=ContentRuleCodec.decode(state.filters.getString("contentRules")).map {rule->
                    val scoped=if(rule.scope==ContentScope.CHANNEL)rule.copy(scopeKey=channelMap[rule.scopeKey]?.id ?: "missing:import:"+Repository.hash(rule.scopeKey))else rule
                    if(local.any {it.id==scoped.id})scoped.copy(id="filter:"+UUID.randomUUID(),revision=1)else scoped
                }
                changes.put("contentRules",ContentRuleCodec.encode(local+incoming))
            }
            if(addedChannels>0)changes.put("personalChannels",PersonalChannelCodec.encode(mergedChannels))
        }
        repo.prefs.validate(changes)
        val warnings=(preview.warnings+document.warnings).distinct().toMutableList()
        if(options.filters&&state.filters!=null)warnings+="Filter choices were imported. Update filter lists to rebuild protection; current protection stays active until then."
        if(missing.isNotEmpty()&&addedChannels>0)warnings+="Some channel sources were not selected or are missing. Their rules remain explicit; edit those channels to choose sources."
        var addedCollections=0;var addedSaved=0
        repo.archiveStore.mutations.withLock {repo.db.withTransaction {
            additions.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,gson.toJson(it)))}
            if(document.kind==PortableKind.STATE&&options.collections) {
                val localCollections=repo.dao.allCollections().toMutableList()
                val collectionMap=state.library.collections.associate {row->
                    val same=localCollections.firstOrNull {asciiLower(it.name)==asciiLower(row.name)}
                    val mapped=same ?: row.copy(id=if(localCollections.any {it.id==row.id})"collection:"+UUID.randomUUID()else row.id).also {localCollections+=it;repo.dao.putCollection(it);addedCollections++}
                    row.id to mapped.id
                }
                val localPosts=repo.dao.allBookmarks().associateBy {it.key}
                val localLinks=repo.dao.allCollectionLinks().groupBy {it.ownerKey}
                state.library.saved.forEach {entry->
                    val key=repo.key(entry.post)
                    if(key !in localPosts) {
                        repo.dao.putBookmark(BookmarkRow(key,entry.post.platform.id,gson.toJson(entry.post),entry.savedAt));addedSaved++
                        entry.details?.let {incoming->
                            val detail=incoming.copy(token=UUID.randomUUID().toString())
                            repo.dao.putSavedDetail(SavedDetailRow(key,gson.toJson(detail)))
                            detail.selectedAssets.forEach {asset->
                                val file=repo.savedMedia(asset.url);val problem=file?.let {SavedAssetValidation.problem(it,asset.type,repo.contentSandbox)}
                                repo.dao.putSavedAsset(SavedAssetRow(key,asset.key,detail.token,asset.url,asset.sourceUrl,asset.type,
                                    if(file==null)"MISSING"else if(problem==null)"COMPLETE"else "UNSUPPORTED",file?.length() ?: 0,
                                    problem ?: if(file==null)"Media files are not included in reader-state exports"else null,System.currentTimeMillis()))
                            }
                        }
                    }
                    repo.dao.setCollectionLinks(key,(localLinks[key].orEmpty().map {it.collectionId}+entry.collectionIds.map {collectionMap.getValue(it)}).distinct())
                }
            }
        }}
        if(document.kind==PortableKind.STATE&&options.collections) {
            val saved=repo.localDiscovery.savedIds()
            state.localDiscovery.businesses.filter {it.id !in saved}.forEach {repo.localDiscovery.save(it);addedSaved++}
            state.localDiscovery.events.filter {it.id !in saved}.forEach {repo.localDiscovery.save(it);addedSaved++}
        }
        if(changes.length()>0)repo.prefs.restore(changes)
        if(additions.any {it.target.platform==Platform.RSS}&&Platform.RSS !in repo.prefs.selected)repo.prefs.selected=repo.prefs.selected+Platform.RSS
        if(document.kind==PortableKind.STATE&&options.progress&&state.progress!=null) {
            val contexts=state.progress.feeds.mapNotNull {(id,memory)->
                val mapped=when {
                    id.startsWith("person:")->sourceMap[id.removePrefix("person:")]?.singleOrNull()?.let {"person:$it"}
                    id.startsWith("channel:")->document.channels.firstOrNull {id.startsWith("channel:${it.id}:${PersonalChannels.fingerprint(it)}:")}?.let {old->channelMap[old.id]?.let {next->"channel:${next.id}:${PersonalChannels.fingerprint(next)}:"+id.removePrefix("channel:${old.id}:${PersonalChannels.fingerprint(old)}:")}}
                    else->id
                }
                mapped?.let {it to memory}
            }.toMap()
            repo.feedMemory.mergePortable(state.progress.copy(feeds=contexts));repo.feedMemory.flush()
        }
        repo.feedMemory.syncFollows((existing+additions).filter {it.enabled}.map {it.id})
        if(addedSaved>0)repo.library.initialize()
        scheduleRefresh(repo.context,repo.prefs);repo.updateUsage()
        PortableImportResult(additions.size,already,addedChannels,addedCollections,addedSaved,warnings.distinct())
    }}

    private fun sourceIdentity(follow: Follow): String = follow.target.platform.id+":"+(follow.target.canonicalUrl.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: follow.target.canonicalUrl)
    private fun asciiLower(value: String)=value.map {if(it in 'A'..'Z')it.lowercaseChar()else it}.joinToString("")
    private fun fields(value: JsonObject,allowed: Set<String>,required: Set<String> = emptySet()) {
        require(value.keySet().all {it in allowed}&&required.all(value::has)){"Unsupported or missing reader-state field."}
    }
    private fun obj(value: JsonElement?): JsonObject {require(value?.isJsonObject==true){"Expected a reader-state object."};return value.asJsonObject}
    private fun array(value: JsonElement?,max: Int): List<JsonElement> {require(value?.isJsonArray==true&&value.asJsonArray.size()<=max){"Invalid or oversized reader-state list."};return value.asJsonArray.toList()}
    private fun text(value: JsonElement?,max: Int,blank: Boolean=false): String {require(value?.isJsonPrimitive==true&&value.asJsonPrimitive.isString){"Expected reader-state text."};return value.asString.also {require(it.length<=max&&(blank||it.isNotBlank())&&!it.any(Char::isISOControl)){"Invalid reader-state identifier."}}}
    private fun long(value: JsonElement?): Long {require(value?.isJsonPrimitive==true&&value.asJsonPrimitive.isNumber){"Expected a whole reader-state number."};return runCatching {value.asBigDecimal.longValueExact()}.getOrElse {throw IllegalArgumentException("Invalid whole reader-state number.")}.also {require(it>=0){"Invalid reader-state timestamp."}}}
    private fun url(value: String): Boolean=value.length<=16*1024&&value.none {it<=' '||it=='\\'}&&value.toHttpUrlOrNull()?.let {it.username.isEmpty()&&it.password.isEmpty()}==true
    private fun validatePost(post: Post) {
        require(post.id.isNotBlank()&&post.id.length<=8192&&url(post.canonicalUrl)&&post.title.length+post.body.length<=1024*1024&&post.author.length<=8192&&post.media.size<=256&&post.links.orEmpty().size<=1024&&!post.preview){"Invalid saved post in reader state."}
        require(post.media.all {url(it.url)&&(it.preview?.let(::url) ?: true)&&(it.audioUrl?.let(::url) ?: true)&&
            (it.audioUrl==null||it.type=="video")&&(it.audioMimeType?.let {mime->it.audioUrl!=null&&mime.length<=256&&mime.startsWith("audio/")} ?: true)}){"Invalid saved media URL."}
        require(post.links.orEmpty().all(::url)){"Invalid saved link."}
        require(PublicRelations.valid(post)){"Invalid saved public relationship."}
        require(PostAuthorAccounts.valid(post)){"Invalid saved author account."}
        require(PublicContextNotes.valid(post)){"Invalid saved public annotation."}
        require(post.avatar?.let(::url) ?: true){"Invalid saved avatar URL."}
        post.provenance?.let {require(url(it.finalUrl)&&url(it.source.root)&&it.fetchedAt>=0){"Invalid saved source provenance."}}
        post.archivedVersion?.let {
            require(url(it.archiveUrl)&&repo.archiveVersion(post)==it){"Invalid saved original snapshot."}
            validatePost(it.snapshot.asPost(post))
        }
        post.recoveredArchive?.let {require(RecoveredArchives.valid(post)){"Invalid saved archive."}}
    }
    private fun validate(document: PortableDocument): ValidatedReaderState {
        if(document.kind==PortableKind.SOURCES)return ValidatedReaderState(null,null,PortableLibrary(),null)
        val payload=document.statePayload ?: JsonObject()
        fields(payload,setOf("settings","filters","library","localDiscovery"))
        val settings=payload["settings"]?.let {raw->val value=obj(raw);fields(value,settingKeys);JSONObject(value.toString()).also {repo.prefs.validate(it)}}
        val filters=payload["filters"]?.let {raw->
            val value=obj(raw);fields(value,setOf("disabledLists","customLists","contentRules"),setOf("disabledLists","customLists"))
            val disabled=array(value["disabledLists"],4096).map {text(it,8192)}
            val custom=array(value["customLists"],256).map {text(it,8192)}
            require(disabled.distinct().size==disabled.size&&custom.distinct().size==custom.size)
            require(custom.all {it.toHttpUrlOrNull()?.let {u->u.isHttps&&u.username.isEmpty()&&u.password.isEmpty()&&u.fragment==null}==true}){"Filter list URLs must use HTTPS without credentials."}
            JSONObject(value.toString()).also {repo.prefs.validate(it)}
        }
        val library=payload["library"]?.let {raw->
            val value=obj(raw);fields(value,setOf("collections","saved"),setOf("collections","saved"))
            val collections=array(value["collections"],1024).map {element->
                val c=obj(element);fields(c,setOf("id","name","createdAt"),setOf("id","name","createdAt"))
                LibraryCollectionRow(text(c["id"],128),text(c["name"],80),long(c["createdAt"]))
            }
            require(collections.map {it.id}.distinct().size==collections.size&&collections.map {asciiLower(it.name)}.distinct().size==collections.size){"Repeated collection in reader state."}
            val collectionIds=collections.map {it.id}.toSet()
            val saved=array(value["saved"],4096).map {element->
                val entry=obj(element);fields(entry,setOf("post","savedAt","details","collectionIds"),setOf("post","savedAt","collectionIds"))
                val postJson=obj(entry["post"]);strictSavedModel(postJson,Post::class.java)
                val post=capturedPortablePost(gson.fromJson(postJson,Post::class.java));validatePost(post)
                val ids=array(entry["collectionIds"],1024).map {text(it,128)}
                require(ids.distinct().size==ids.size&&ids.all {it in collectionIds}){"Saved item refers to a missing collection."}
                val details=entry["details"]?.takeUnless {it.isJsonNull}?.let {rawDetails->
                    val d=obj(rawDetails);fields(d,setOf("token","capturedAt","threads","selectedAssets","fusionRoots"),setOf("token","capturedAt","threads","selectedAssets"))
                    strictSavedModel(d,SavedCopyDetails::class.java)
                    array(d["threads"],4096);array(d["selectedAssets"],4096)
                    val detail=capturedPortableDetails(gson.fromJson(d,SavedCopyDetails::class.java))
                    require(detail.capturedAt>=0&&detail.threads.sumOf {it.count+1}<=4096)
                    val threads=detail.threads.map {thread->validatePost(thread.root);require(thread.capturedAt>=0&&thread.order in setOf("CHRONOLOGICAL","SLOP"));thread.replies.forEach(::validatePost);thread.copy(commentAnywhere=thread.commentAnywhere?.let {repo.library.normalizedCommunity(thread.root,it)})}
                    val fusion=repo.library.normalizedFusionRoots(post,detail.fusionRoots.orEmpty())
                    val assets=repo.library.availableAssets(post,threads,fusion).associateBy {it.key}
                    require(detail.selectedAssets.map {it.key}.distinct().size==detail.selectedAssets.size&&detail.selectedAssets.all {assets[it.key]==it}){"Invalid selected saved attachment."}
                    detail.copy(threads=threads,fusionRoots=fusion.takeIf {it.size>1})
                }
                PortableSaved(post,long(entry["savedAt"]),details,ids)
            }
            require(saved.map {repo.key(it.post)}.distinct().size==saved.size){"Repeated saved post in reader state."}
            PortableLibrary(collections,saved)
        } ?: PortableLibrary()
        val progress=document.progress?.let(::validateProgress)
        return ValidatedReaderState(settings,filters,library,progress,payload["localDiscovery"]?.let(LocalDiscoveryPortability::decode) ?: PortableLocalDiscovery())
    }
    internal fun validateProgress(value: JsonObject): PortableProgress {
        fields(value,setOf("version","feeds","readKeys","readAt"),setOf("version","feeds","readKeys","readAt"));require(long(value["version"])==1L)
        require(value.toString().toByteArray(Charsets.UTF_8).size<=FeedMemoryStore.MAX_BYTES){"Reading progress exceeds the local storage budget."}
        val feeds=obj(value["feeds"]);require(feeds.size()<=FeedMemoryStore.MAX_FEEDS)
        fun keys(element: JsonElement?,max: Int)=array(element,max).map {text(it,FeedReading.MAX_KEY)}.also {require(it.distinct().size==it.size)}
        fun offset(element: JsonElement?) {require(element?.isJsonPrimitive==true&&element.asJsonPrimitive.isNumber);val number=runCatching {element.asBigDecimal.intValueExact()}.getOrNull();require(number!=null&&number in -1_000_000..1_000_000)}
        feeds.entrySet().forEach {(id,element)->
            require(id.isNotBlank()&&id.length<=FeedReading.MAX_KEY&&!id.any(Char::isISOControl))
            val m=obj(element);fields(m,setOf("initialized","acceptedKeys","stagedKeys","readKeys","freshKeys","anchorKey","anchorOffset","anchorNeighbors","resumeReturn","updatedAt"),setOf("initialized","acceptedKeys","stagedKeys","readKeys","freshKeys","anchorOffset","anchorNeighbors","updatedAt"))
            require(m["initialized"]?.isJsonPrimitive==true&&m["initialized"].asJsonPrimitive.isBoolean)
            val accepted=keys(m["acceptedKeys"],FeedReading.MAX_ACCEPTED).toSet();val staged=keys(m["stagedKeys"],FeedReading.MAX_ACCEPTED);val read=keys(m["readKeys"],FeedReading.MAX_READ).toSet();val fresh=keys(m["freshKeys"],FeedReading.MAX_ACCEPTED)
            require(staged.none {it in accepted}&&fresh.all {it in accepted&&it !in read})
            m["anchorKey"]?.takeUnless {it.isJsonNull}?.let {text(it,FeedReading.MAX_KEY)};offset(m["anchorOffset"]);keys(m["anchorNeighbors"],8);long(m["updatedAt"])
            m["resumeReturn"]?.takeUnless {it.isJsonNull}?.let {r->val a=obj(r);fields(a,setOf("key","offset","neighbors"),setOf("key","offset","neighbors"));text(a["key"],FeedReading.MAX_KEY);offset(a["offset"]);keys(a["neighbors"],8)}
        }
        val read=keys(value["readKeys"],FeedReading.MAX_READ);val readAt=long(value["readAt"]);require(read.isEmpty()||readAt>0)
        return gson.fromJson(value,PortableProgress::class.java)
    }
}
