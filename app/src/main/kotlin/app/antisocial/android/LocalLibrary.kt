/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Durable local material. Reading/searching a copy never invokes a remote reader. */
class LocalLibrary(private val repo: Repository) {
    private val dao get()=repo.dao
    private val gson get()=repo.gson
    private val transfers=Mutex()
    private val historyWrites=Mutex()
    val revision=MutableStateFlow(0)
    val collections=dao.collections()
    fun notifyChanged(){revision.update {it+1}}
    private fun sourceRecord(post: Post)=post.copy(archivedVersion=null,media=post.media.map {it.copy(visualFingerprint=null)})
    /** Retain the first captured edition verbatim; supplement only its missing
     * local image observation from an otherwise identical source snapshot. */
    private fun capturedEditions(editions: List<Post>): List<Post> = editions.groupBy(repo::key).values.map {copies->
        val first=copies.first();val compatible=copies.filter {sourceRecord(it)==sourceRecord(first)}
        first.copy(media=first.media.mapIndexed {index,media->media.copy(visualFingerprint=media.visualFingerprint ?: compatible.firstNotNullOfOrNull {it.media[index].visualFingerprint})})
    }
    private fun parse(payload: String): Post?=runCatching {gson.fromJson(payload,Post::class.java)}.getOrNull()?.takeIf {validMedia(it)&&(it.recoveredArchive==null||RecoveredArchives.valid(it))}?.let {it.copy(archivedVersion=repo.archiveVersion(it))}
    suspend fun details(key: String): SavedCopyDetails?=withContext(Dispatchers.IO) {dao.savedDetail(key)?.let {runCatching {gson.fromJson(it.payload,SavedCopyDetails::class.java)}.getOrNull()}}
    suspend fun assetFile(url: String): File?=repo.savedMedia(url)
    suspend fun validToken(owner: String,token: String)=dao.hasAssetToken(owner,token)

    fun availableAssets(post: Post,threads: List<CapturedThread> = emptyList(),fusionRoots: List<Post> = emptyList()): List<SelectedAsset> {
        val result=mutableListOf<SelectedAsset>()
        fun add(p: Post,edition: String) {
            p.media.forEach {m->result+=SelectedAsset(Repository.hash(m.url),m.url,p.contentSourceUrl,m.type,m.description,m.preview,repo.key(p),edition)
                m.preview?.let {result+=SelectedAsset(Repository.hash(it),it,p.contentSourceUrl,"image","Video preview",postKey=repo.key(p),edition=edition)}}
        }
        add(post,"current")
        repo.archiveVersion(post)?.let {add(it.displayPost(post),"original")}
        fusionRoots.filter {repo.key(it)!=repo.key(post)}.forEach {p->
            add(p,"source:${p.platform.id}")
            repo.archiveVersion(p)?.let {add(it.displayPost(p),"original:${p.platform.id}")}
        }
        threads.forEach {thread->(listOf(thread.root)+thread.replies).forEach {add(it,"thread")}}
        return result.filter {safeUrl(it.url)&&safeUrl(it.sourceUrl)}.distinctBy {it.key}
    }
    private fun allPosts(post: Post,detail: SavedCopyDetails?): List<Post> = buildList {
        add(post)
        post.archivedVersion?.takeIf {repo.archiveVersion(post)==it}?.let {add(it.displayPost(post))}
        detail?.fusionRoots.orEmpty().forEach {add(it)}
        detail?.threads.orEmpty().forEach {add(it.root);addAll(it.replies)}
    }.distinctBy {repo.key(it)}
    /** A presentation projection never removes stored originals. A verified
     * short repeat can share a clip with a full caption without aligning as a
     * complete cross-platform passage. Keep the fullest caption per platform
     * only for this fallback composition; source selectors retain every root. */
    internal fun fusionCaptionRoots(roots: List<Post>): List<Post> = roots.groupBy {it.platform}.values.map {editions->
        editions.sortedWith(compareByDescending<Post> {PostPresentation.content(it).length}.thenBy(CrossPosts::originalKey)).first()
    }
    /** These are captured source editions, never inferred account identities.
     * Imported manifests must contain the exact primary source edition and only
     * valid, contemporaneous original destinations before they can be composed.
     * Repeated platforms additionally need a proven original account or explicit
     * curator source and strong pairwise content matching; display labels alone
     * are not evidence.
     * A separately validated archive may have been attached since the capture.
     */
    internal fun normalizedFusionRoots(root: Post,incoming: List<Post>): List<Post> {
        if(incoming.isEmpty())return emptyList()
        require(incoming.size<=CrossPosts.MAX_EDITIONS){"This fused copy contains too many source editions"}
        require(incoming.distinctBy(repo::key).size==incoming.size&&incoming.distinctBy(CrossPosts::originalKey).size==incoming.size){"A fused copy contains duplicate source editions"}
        val primary=incoming.firstOrNull {sourceRecord(it)==sourceRecord(root)}
        require(primary!=null){"A fused copy must include its original saved edition"}
        // Archive checks can update the bookmark after these source editions
        // were captured. Check both archive attachments before choosing the
        // bookmark's pair; no original text, author, media or date may change.
        (listOf(root)+incoming).forEach {p->
            require(validMedia(p)&&(p.recoveredArchive==null||RecoveredArchives.valid(p))&&(p.archivedVersion==null||repo.archiveVersion(p)==p.archivedVersion)){"Invalid paired source archive"}
        }
        val observedRoot=root.copy(media=root.media.mapIndexed {i,m->m.copy(visualFingerprint=primary.media[i].visualFingerprint ?: m.visualFingerprint)})
        val pairedRoot=if(root.archivedVersion==null&&primary.archivedVersion!=null)observedRoot.copy(archivedVersion=primary.archivedVersion)else observedRoot
        val roots=(listOf(pairedRoot)+incoming).distinctBy(repo::key)
        require(roots.size<=CrossPosts.MAX_EDITIONS){"This fused copy contains too many source editions"}
        if(roots.size<2)return emptyList()
        val contentCopies=CrossPosts.sameContentCopies(roots,repo.images.identities::same)
        val gallery=CrossPosts.galleryCollection(roots,repo.images.identities::same)
        roots.forEach {p->
            val target=repo.postTarget(p)
            require(!p.preview&&(p.parentId==null||gallery)&&safeUrl(p.canonicalUrl)&&target!=null&&target.platform==p.platform&&target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&
                (p.body.isNotBlank()||p.title.isNotBlank()||p.media.isNotEmpty())&&p.body.length.toLong()+p.title.length<=1024*1024&&
                (p.publishedAt?.let {it>0&&it<=System.currentTimeMillis()+CachePolicy.DAY} ?: contentCopies)){"Invalid captured source edition"}
        }
        val times=roots.mapNotNull {it.publishedAt}
        require(contentCopies||times.max()-times.min()<=48*60*60*1000L){"Captured source editions are too far apart"}
        roots.groupBy {it.platform}.values.filter {it.size>1}.forEach {repeated->
            require(contentCopies||CrossPosts.sameSourceRepeats(repeated,repo.images.identities::same)||CrossPosts.sameContentCopies(repeated,repo.images.identities::same)){"Repeated source editions need matching content and a proven shared account or reposting source"}
        }
        val repeatedOnly=contentCopies||roots.map {it.platform}.distinct().size==1&&CrossPosts.sameSourceRepeats(roots,repo.images.identities::same)
        require(repeatedOnly||CrossPostFusion.compose(roots)!=null||CrossPostFusion.compose(fusionCaptionRoots(roots))!=null){"Captured source editions do not share a substantial caption"}
        return roots
    }
    fun mergeThreads(previous: List<CapturedThread>,incoming: List<CapturedThread>): List<CapturedThread> {
        val captures=linkedMapOf<String,CapturedThread>()
        (previous+incoming).forEach {thread->
            val id=repo.key(thread.root)+(if(thread.commentAnywhere!=null)":commentanywhere"else "");val first=captures[id]
            thread.commentAnywhere?.let {normalizedCommunity(thread.root,it)}
            captures[id]=if(first==null)thread.copy(replies=thread.replies.distinctBy(repo::key))else {
                val before=first.commentAnywhere;val after=thread.commentAnywhere
                val community=if(before!=null&&after!=null) {
                    fun merge(old: List<AnywhereComment>,new: List<AnywhereComment>): List<AnywhereComment> {
                        val additions=new.associateBy {it.id}
                        return old.map {comment->additions[comment.id]?.let {comment.copy(children=merge(comment.children,it.children),hasMore=comment.hasMore||it.hasMore)} ?: comment}+new.filter {n->old.none {it.id==n.id}}
                    }
                    CommentAnywhereClient.parse(gson.toJson(mapOf("items" to merge(before.items,after.items))),before.pageUrl,before.order,before.limit,before.fetchedAt)
                        .let {it.copy(limited=it.limited||before.limited||after.limited)}
                }else before
                first.copy(replies=(first.replies+thread.replies).distinctBy(repo::key),hasMore=first.hasMore||thread.hasMore,commentAnywhere=community)
            }
        }
        return captures.values.toList()
    }
    internal fun normalizedCommunity(root: Post,page: AnywherePage): AnywherePage {
        require(page.pageUrl==AnywhereUrls.root(root,repo.routes)&&page.limit in 1..100&&page.fetchedAt>0){"Invalid captured CommentAnywhere address"}
        val normalized=CommentAnywhereClient.parse(gson.toJson(mapOf("items" to page.items)),page.pageUrl,page.order,page.limit,page.fetchedAt)
        require(normalized.flattened().size==page.flattened().size&&normalized.flattened().size<=200){"Captured CommentAnywhere thread exceeds its limit"}
        return normalized.copy(limited=page.limited||normalized.limited)
    }
    private suspend fun index(owner: String,kind: String,posts: List<Post>,at: Long,threads: List<CapturedThread> = emptyList()) {
        dao.deleteDocuments(owner,kind)
        posts.forEach {p->dao.putDocument(LocalDocumentRow(ownerKey=owner,entryKey=repo.key(p),kind=kind,platform=p.platform.id,title=p.title,body=p.body,author=p.author,url=p.canonicalUrl,sortAt=at))}
        threads.forEach {thread->thread.commentAnywhere?.let {page->page.flattened().forEach {(comment,_)->
            dao.putDocument(LocalDocumentRow(ownerKey=owner,entryKey="commentanywhere:${Repository.hash(page.pageUrl)}:${comment.id}",kind=kind,platform=thread.root.platform.id,title="CommentAnywhere",body=if(comment.deleted)"[deleted by user]"else comment.text,author=comment.username,url=page.pageUrl,sortAt=at))
        }}}
    }
    suspend fun initialize()=withContext(Dispatchers.IO) {
        // Existing bookmarks are already full text copies; add the searchable manifest without fetching anything.
        repo.archiveStore.mutations.withLock {
            val detailed=dao.savedDetailKeys().toSet()
            val indexed=dao.documentOwners("saved").toSet()
            val missing=dao.bookmarkKeys().first().filter {it !in detailed||it !in indexed}
            missing.chunked(64).forEach {keys->dao.bookmarksByKeys(keys).forEach {row->parse(row.payload)?.let {post->
                repo.db.withTransaction {
                    var detail=details(row.key)
                    if(detail==null) {
                        val local=availableAssets(post).filter {assetFile(it.url)!=null}
                        detail=SavedCopyDetails(UUID.randomUUID().toString(),row.savedAt,selectedAssets=local)
                        dao.putSavedDetail(SavedDetailRow(row.key,gson.toJson(detail)))
                        local.forEach {a->
                            val file=assetFile(a.url);val problem=file?.let {SavedAssetValidation.problem(it,a.type,repo.contentSandbox)}
                            dao.putSavedAsset(SavedAssetRow(row.key,a.key,detail.token,a.url,a.sourceUrl,a.type,if(problem==null)"COMPLETE"else "UNSUPPORTED",file?.length() ?: 0,problem,row.savedAt))
                        }
                    }
                    index(row.key,"saved",allPosts(post,detail),row.savedAt,detail?.threads.orEmpty())
                }
            }}}
            // Repair orphan search rows left by an older/direct DAO deletion.
            dao.pruneOrphanDocuments()
            dao.allSavedAssets().filter {it.status=="DOWNLOADING"}.forEach {dao.updateSavedAsset(it.ownerKey,it.assetKey,it.token,"QUEUED",0,"Interrupted; queued for retry",System.currentTimeMillis())}
        }
        pruneHistory()
        if(repo.prefs.historyFullText)historyWrites.withLock {
            val indexed=dao.documentOwners("history").toSet()
            dao.retainedHistoryUsage().map {it.key}.filter {it !in indexed}.chunked(64).forEach {keys->
                repo.db.withTransaction {dao.retainedHistoryByKeys(keys).forEach {row->parse(row.payload)?.let {index(row.key,"history",listOf(it),row.viewedAt)}}}
            }
        }
        dao.allSavedAssets().filter {it.status in setOf("QUEUED","WAITING_WIFI")}.distinctBy {it.ownerKey}.forEach {enqueue(it.ownerKey,it.token)}
        notifyChanged()
    }
    /** Metadata is committed before any network work is scheduled. */
    suspend fun save(post: Post,selected: List<SelectedAsset>,threads: List<CapturedThread> = emptyList(),collectionIds: Set<String> = emptySet(),fusionRoots: List<Post> = emptyList()): String=withContext(Dispatchers.IO) {
        require(!post.preview&&safeUrl(post.canonicalUrl)&&validMedia(post)){"A public post is required"}
        require(threads.sumOf {it.count+1}<=4096){"This captured thread is too large; save fewer replies"}
        require(fusionRoots.size<=CrossPosts.MAX_EDITIONS){"This fused copy contains too many source editions"}
        val owner=repo.key(post);val token=UUID.randomUUID().toString();val now=System.currentTimeMillis()
        repo.archiveStore.mutations.withLock {
            val old=dao.bookmarksByKeys(listOf(owner)).firstOrNull()
            val root=old?.let {parse(it.payload)} ?: repo.captureAiMetadata(repo.images.identities.captured(repo.archiveStore.marry(post)))
            val previous=details(owner)
            // A later save can add a newly discovered platform, but cannot edit
            // the first local capture of any already-preserved source edition.
            val editions=capturedEditions(listOf(root)+previous?.fusionRoots.orEmpty()+fusionRoots.map {repo.captureAiMetadata(repo.images.identities.captured(repo.archiveStore.marry(it)))})
            val fusion=if(previous?.fusionRoots.orEmpty().isNotEmpty()||fusionRoots.isNotEmpty())normalizedFusionRoots(root,editions)else emptyList()
            val captured=mergeThreads(previous?.threads.orEmpty(),threads)
            require(captured.sumOf {it.count+1}<=4096){"This captured thread is too large; save fewer replies"}
            require(captured.flatMap {listOf(it.root)+it.replies}.all {p->!p.preview&&safeUrl(p.canonicalUrl)&&validMedia(p)&&p.body.length+p.title.length<=1024*1024}){"Unavailable or oversized public replies cannot be saved"}
            val allowed=availableAssets(root,captured,fusion).associateBy {it.key}
            val assets=selected.distinctBy {it.key}.map {requireNotNull(allowed[it.key]){"Attachment is not part of the captured post"}}
            val detail=SavedCopyDetails(token,previous?.capturedAt ?: now,captured,assets,fusion.takeIf {it.size>1})
            val json=gson.toJson(detail)
            require(json.toByteArray().size+gson.toJson(root).toByteArray().size<=16*1024*1024){"This copy exceeds the 16 MiB text limit"}
            val validCollections=collectionIds.filter {dao.collection(it)!=null}
            repo.db.withTransaction {
                if(old==null)dao.putBookmark(BookmarkRow(owner,root.platform.id,gson.toJson(root),now))
                dao.putSavedDetail(SavedDetailRow(owner,json));dao.deleteSavedAssets(owner)
                for(a in assets) {
                    val file=assetFile(a.url)
                    val problem=file?.let {SavedAssetValidation.problem(it,a.type,repo.contentSandbox)}
                    dao.putSavedAsset(SavedAssetRow(owner,a.key,token,a.url,a.sourceUrl,a.type,if(file==null)"QUEUED"else if(problem==null)"COMPLETE"else "UNSUPPORTED",file?.length() ?: 0,problem,now))
                }
                dao.setCollectionLinks(owner,validCollections)
                index(owner,"saved",allPosts(root,detail),old?.savedAt ?: now,captured)
            }
            // Deselecting an earlier download releases only bytes no other copy owns.
            val removed=previous?.selectedAssets.orEmpty().map {it.key}.toSet()-assets.map {it.key}.toSet()
            if(removed.isNotEmpty()) {
                val retained=retainedMediaKeys()
                repo.mediaMutex.withLock {
                    val unused=dao.allMedia().filter {it.saved&&it.key in removed&&it.key !in retained}
                    unused.forEach {File(it.path).delete()};if(unused.isNotEmpty())dao.removeMedia(unused.map {it.key})
                }
            }
        }
        notifyChanged();enqueue(owner,token);repo.updateUsage();owner
    }
    suspend fun indexBookmark(post: Post) {
        val owner=repo.key(post);val row=dao.bookmarksByKeys(listOf(owner)).firstOrNull() ?: return
        val root=parse(row.payload) ?: return
        val detail=details(owner);index(owner,"saved",allPosts(root,detail),row.savedAt,detail?.threads.orEmpty());notifyChanged()
    }
    /** Caller holds archive mutations before deleting any shared saved files. */
    internal suspend fun retainedMediaKeys(): Set<String> {
        val detailed=dao.savedDetailKeys().toSet()
        val media=dao.allMedia().associateBy {it.key}
        val roots=dao.allBookmarks().mapNotNull {row->parse(row.payload)?.let {row.key to it}}
        val markers=roots.map {repo.archiveStore.offlineKey(it.second)}
        val legacy=roots.filter {(key,p)->key !in detailed||media[repo.archiveStore.offlineKey(p)]?.saved==true}
        return (dao.allSavedAssets().filter {it.status!="REMOVED"}.map {it.assetKey}+markers+legacy.flatMap {(_,p)->allPosts(p,null).flatMap {it.media.flatMap {m->listOfNotNull(m.url,m.preview,m.audioUrl)}+listOfNotNull(it.avatar)}.map(Repository::hash)}).toSet()
    }
    suspend fun createCollection(name: String): String=repo.archiveStore.mutations.withLock {
        val clean=name.trim().replace(Regex("\\s+")," ")
        require(clean.isNotEmpty()&&clean.length<=80){"Use a collection name of 1–80 characters"}
        dao.collectionNamed(clean)?.id ?: UUID.randomUUID().toString().also {dao.putCollection(LibraryCollectionRow(it,clean,System.currentTimeMillis()))}
    }
    suspend fun setCollections(key: String,ids: Set<String>) {repo.db.withTransaction {dao.setCollectionLinks(key,ids.filter {dao.collection(it)!=null})};notifyChanged()}
    suspend fun deleteCollection(id: String){dao.removeCollection(id);notifyChanged()}
    suspend fun remove(key: String){copy(key)?.let {repo.bookmark(it.post,false)}}
    suspend fun copy(key: String,kind: String="saved"): LibraryEntry?=withContext(Dispatchers.IO) {
        if(kind=="history") {
            val row=dao.retainedHistory(key) ?: return@withContext null
            return@withContext parse(row.payload)?.let {LibraryEntry(key,"history",it,row.viewedAt)}
        }
        val row=dao.bookmarksByKeys(listOf(key)).firstOrNull() ?: return@withContext null
        val post=parse(row.payload) ?: return@withContext null
        val links=dao.allCollectionLinks().filter {it.ownerKey==key}.map {it.collectionId}.toSet()
        LibraryEntry(key,"saved",post,row.savedAt,dao.allCollections().filter {it.id in links},details(key),dao.savedAssetsByOwners(listOf(key)))
    }
    fun entries(query: String,scope: String="all",collectionId: String?=null): Flow<List<LibraryEntry>> {
        val match=LocalSearch.query(query)
        val docs=if(match==null)dao.browseDocuments(scope!="history",scope!="saved",collectionId)else dao.searchDocuments(match,scope!="history",scope!="saved",collectionId)
        return combine(docs,revision,dao.collections(),dao.collectionLinks()) {documents,_,folders,links->
            val selected=documents.distinctBy {it.kind+":"+it.ownerKey}.take(200)
            val savedKeys=selected.filter {it.kind=="saved"}.map {it.ownerKey}
            val historyKeys=selected.filter {it.kind=="history"}.map {it.ownerKey}
            val saved=dao.bookmarksByKeys(savedKeys).associateBy {it.key}
            val history=dao.retainedHistoryByKeys(historyKeys).associateBy {it.key}
            val assets=dao.savedAssetsByOwners(savedKeys).groupBy {it.ownerKey}
            val foldersById=folders.associateBy {it.id}
            val ownerFolders=links.groupBy {it.ownerKey}.mapValues {(_,rows)->rows.mapNotNull {foldersById[it.collectionId]}}
            selected.mapNotNull {d->
                val root=if(d.kind=="saved")saved[d.ownerKey]?.let {parse(it.payload)}else history[d.ownerKey]?.let {parse(it.payload)}
                val matched=if(d.entryKey!=d.ownerKey)Platform.entries.firstOrNull {it.id==d.platform}?.let {Post(ContentIdentity.key(it,d.url),it,d.url,d.author,d.body,d.title)}else null
                root?.let {p->LibraryEntry(d.ownerKey,d.kind,p,d.sortAt,if(d.kind=="saved")ownerFolders[d.ownerKey].orEmpty()else emptyList(),null,if(d.kind=="saved")assets[d.ownerKey].orEmpty()else emptyList(),matched)}
            }
        }.flowOn(Dispatchers.IO)
    }
    suspend fun recordHistory(post: Post,at: Long) {
        if(!repo.prefs.historyFullText||post.preview)return
        val json=gson.toJson(post);val bytes=json.toByteArray().size.toLong();if(bytes>1024*1024)return
        historyWrites.withLock {
            if(!repo.prefs.historyFullText)return
            repo.db.withTransaction {dao.putRetainedHistory(RetainedHistoryRow(repo.key(post),post.platform.id,json,at,bytes));index(repo.key(post),"history",listOf(post),at)}
            pruneHistoryLocked()
        }
        notifyChanged()
    }
    suspend fun pruneHistory()=historyWrites.withLock {pruneHistoryLocked();notifyChanged()}
    private suspend fun pruneHistoryLocked() {
        repo.db.withTransaction {
            if(!repo.prefs.historyFullText){dao.clearRetainedHistory();dao.clearDocumentKind("history");return@withTransaction}
            val rows=dao.retainedHistoryUsage();val cutoff=System.currentTimeMillis()-repo.prefs.historyDays*CachePolicy.DAY
            var bytes=0L;var count=0
            val removed=rows.filter {r->if(r.viewedAt<cutoff||count>=2000||bytes+r.bytes>repo.prefs.historyTextMb*1024L*1024)true else {count++;bytes+=r.bytes;false}}
            if(removed.isNotEmpty()){dao.removeRetainedHistory(removed.map {it.key});removed.forEach {dao.deleteDocuments(it.key,"history")}}
        }
    }
    suspend fun clearHistory(){historyWrites.withLock {repo.db.withTransaction {dao.clearHistory();dao.clearRetainedHistory();dao.clearDocumentKind("history")}};notifyChanged();repo.updateUsage()}
    suspend fun commitAsset(owner: String,token: String,url: String,temp: File): File?=repo.archiveStore.mutations.withLock {
        val row=dao.asset(owner,Repository.hash(url))
        if(!validToken(owner,token)||row?.token!=token||row.status=="REMOVED")return@withLock null
        repo.mediaMutex.withLock {
            val target=File(repo.context.filesDir,"saved/${Repository.hash(url)}");target.parentFile?.mkdirs()
            // The temp and target may live on different Android volumes.
            if(!temp.renameTo(target)){
                val staging=File(target.parentFile,"${target.name}.${UUID.randomUUID()}.part")
                try {temp.copyTo(staging,true);check(staging.renameTo(target)){"Could not commit downloaded media"};temp.delete()}finally {staging.delete()}
            }
            val now=System.currentTimeMillis();dao.putMedia(MediaRow(Repository.hash(url),target.path,target.length(),now,now,true))
            dao.updateSavedAsset(owner,row.assetKey,token,"COMPLETE",target.length(),null,now);notifyChanged();target
        }
    }
    suspend fun retry(key: String) {
        val assets=dao.savedAssetsByOwners(listOf(key));val token=assets.firstOrNull()?.token ?: return
        assets.filter {it.token==token&&it.status in setOf("FAILED","WAITING_WIFI","REMOVED","MISSING","QUEUED")}.forEach {dao.updateSavedAsset(key,it.assetKey,token,"QUEUED",0,null,System.currentTimeMillis())}
        notifyChanged();enqueue(key,token)
    }
    private fun workName(key: String)="saved-copy-${Repository.hash(key)}"
    internal fun cancel(key: String){WorkManager.getInstance(repo.context).cancelUniqueWork(workName(key))}
    private fun enqueue(key: String,token: String) {
        // A viewed image can be preserved without connectivity. The worker
        // consumes local files first; unavailable network work uses backoff.
        val request=OneTimeWorkRequestBuilder<SavedCopyWorker>().setInputData(workDataOf("owner" to key,"token" to token)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(repo.context).enqueueUniqueWork(workName(key),ExistingWorkPolicy.REPLACE,request)
    }
    suspend fun download(owner: String,token: String): Boolean=transfers.withLock {
        if(!validToken(owner,token))return@withLock true
        repo.awaitReady()
        // Key-only icon flows no longer decode every bookmark. Classify this
        // explicit download's persisted original origins before gateway checks.
        val copy=copy(owner)
        val posts=copy?.let {allPosts(it.post,it.details)}.orEmpty()
        repo.registerPageOrigins(Page(posts))
        val mimeTypes=posts.flatMap {it.media}.filter {it.mimeType!=null}.associate {it.url to it.mimeType}
        val youtubeVideos=posts.filter {it.platform==Platform.YOUTUBE}.flatMap {p->YouTubeUrls.videoId(p.canonicalUrl)?.let {id->p.media.filter {it.type=="video"}.map {it.url to id}}.orEmpty()}.toMap()
        val splitVideos=posts.flatMap {it.media}.filter {it.audioUrl!=null}.map {it.url}.toSet()
        SavedMediaDownloads(repo).run(owner,token,mimeTypes,youtubeVideos,splitVideos).also {notifyChanged();repo.updateUsage()}
    }
    suspend fun removeVideos() {
        repo.archiveStore.mutations.withLock {
            val rows=dao.allSavedAssets();val affected=rows.filter {it.type!="image"}.map {it.ownerKey}.toSet()
            repo.db.withTransaction {affected.forEach {owner->cancel(owner);details(owner)?.let {d->
                val token=UUID.randomUUID().toString();dao.putSavedDetail(SavedDetailRow(owner,gson.toJson(d.copy(token=token))))
                rows.filter {it.ownerKey==owner}.forEach {a->dao.putSavedAsset(a.copy(token=token,status=if(a.type!="image")"REMOVED"else if(a.status=="DOWNLOADING")"QUEUED"else a.status,bytes=if(a.type!="image")0 else a.bytes,error=null))}
            }}}
            val posts=dao.allBookmarks().mapNotNull {parse(it.payload)}
            val videoUrls=(rows.filter {it.type!="image"}.map {it.url}+posts.flatMap {p->allPosts(p,null).flatMap {it.media.filter {m->m.type!="image"}.flatMap {listOfNotNull(it.url,it.audioUrl)}}}).toSet()
            val images=(rows.filter {it.type=="image"}.map {it.url}+posts.flatMap {p->allPosts(p,null).flatMap {it.media.filter {m->m.type=="image"}.map {it.url}+it.media.mapNotNull {it.preview}+listOfNotNull(it.avatar)}}).toSet()
            val markers=posts.filter {p->allPosts(p,null).any {it.media.any {m->m.type!="image"}}}.map {repo.archiveStore.offlineKey(it)}.toSet()
            repo.mediaMutex.withLock {val remove=dao.allMedia().filter {it.saved&&(it.key in (videoUrls-images).map(Repository::hash).toSet()||it.key in markers)};remove.forEach {File(it.path).delete()};if(remove.isNotEmpty())dao.removeMedia(remove.map {it.key})}
        }
        dao.allSavedAssets().filter {it.type=="image"&&it.status in setOf("QUEUED","WAITING_WIFI")}.distinctBy {it.ownerKey}.forEach {enqueue(it.ownerKey,it.token)}
        notifyChanged();repo.updateUsage()
    }
    companion object {
        private fun safeUrl(url: String)=url.length<=16*1024&&url.none {it<=' '||it=='\\'}&&runCatching {java.net.URI(url).let {it.scheme in setOf("http","https")&&!it.host.isNullOrBlank()&&it.userInfo==null}}.getOrDefault(false)
        private fun validMedia(post: Post): Boolean=PublicRelations.valid(post)&&PublicContextNotes.valid(post)&&PostAuthorAccounts.valid(post)&&post.media.size<=256&&post.media.all {m->safeUrl(m.url)&&(m.preview?.let(::safeUrl) ?: true)&&
            VisualFrames.valid(m.visualFingerprint)&&
            (m.audioUrl?.let(::safeUrl) ?: true)&&(m.audioUrl==null||m.type=="video")&&
            (m.audioMimeType?.let {mime->m.audioUrl!=null&&mime.length<=256&&mime.startsWith("audio/")} ?: true)}
    }
}

class SavedCopyWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as AntiSocialApplication).repo
        val owner=inputData.getString("owner") ?: return Result.failure();val token=inputData.getString("token") ?: return Result.failure()
        return try {if(repo.library.download(owner,token))Result.success()else Result.retry()}catch(e: CancellationException){throw e}catch(_: Exception){Result.retry()}
    }
}
