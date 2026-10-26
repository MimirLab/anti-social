/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.util.AtomicFile
import app.antisocial.core.FeedMemory
import app.antisocial.core.FeedReading
import app.antisocial.core.FollowRefresh
import app.antisocial.core.RefreshLedger
import app.antisocial.core.Post
import app.antisocial.core.CrossPosts
import app.antisocial.core.ContentRevisions
import app.antisocial.core.ContentObservation
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.ByteArrayOutputStream

private data class ContentStamp(val signature: String,val fetchedAt: Long,val updatedAt: Long)
private data class FeedMemoryFile(val version: Int=1,val feeds: Map<String,FeedMemory> = emptyMap(),
    val refresh: Map<String,FollowRefresh> = emptyMap(),val token: Long=0,val readKeys: Set<String> = emptySet(),val readAt: Long=0,
    val contentStamps: Map<String,ContentStamp>?=null)

/** Local metadata only. Mutations publish immediately; serialized file work stays on IO.
 * Thirty-two reading contexts, 2,000 follow checks and a conservative 4 MiB total
 * metadata budget are retained. Inactive records expire after ninety days.
 */
class FeedMemoryStore(
    context: Context,
    scope: CoroutineScope,
    private val file: File=File(context.filesDir,"feed-reading.json"),
    private val clock: ()->Long=System::currentTimeMillis
) {
    private val gson=GsonBuilder().disableHtmlEscaping().create()
    private val gate=Any()
    private val writeLock=Mutex()
    private val writes=Channel<Unit>(Channel.CONFLATED)
    private val loaded=CompletableDeferred<Unit>()
    private var initialized=false
    private var data=FeedMemoryFile()
    private var revision=0L
    private var writtenRevision=-1L
    private var issuedToken=clock().coerceAtLeast(1)
    private val queued=mutableListOf<(FeedMemoryFile)->FeedMemoryFile>()
    private val sizes=mutableMapOf<String,Long>()
    private var stampMemo: Map<String,ContentStamp>?=null
    private var stampBytesMemo=0L
    private val mutableStates=MutableStateFlow<Map<String,FeedMemory>>(emptyMap())
    private val mutableRefresh=MutableStateFlow<Map<String,FollowRefresh>>(emptyMap())
    private val mutableReady=MutableStateFlow(false)
    val states=mutableStates.asStateFlow()
    val refresh=mutableRefresh.asStateFlow()
    val ready=mutableReady.asStateFlow()

    /** The disk checkpoint retains every pixel; painting needs only changes to
     * the reading state or anchor identity. Do not recompose the whole deck for
     * each pixel travelled. Restore/checkpoint callers still use get(). */
    internal fun reading(id: String)=states.map {it[id] ?: get(id)}.distinctUntilChanged {old,next->
        old==next||old.copy(anchorOffset=next.anchorOffset,updatedAt=next.updatedAt)==next
    }

    init {
        scope.launch(Dispatchers.IO) {
            val restored=runCatching {readFile()}.getOrDefault(FeedMemoryFile())
            synchronized(gate) {
                sizes.clear()
                var next=restored
                queued.forEach {operation->runCatching {operation(next)}.onSuccess {next=it}};queued.clear()
                data=runCatching {bounded(next)}.getOrDefault(FeedMemoryFile());initialized=true;revision++
                publish();mutableReady.value=true;loaded.complete(Unit)
            }
            writes.trySend(Unit)
        }
        scope.launch(Dispatchers.IO) {
            loaded.await()
            for(signal in writes) {
                // Save after scrolling settles, with a five-second checkpoint
                // during continuous movement. Lifecycle flush does not wait here.
                val deadline=System.nanoTime()+5_000_000_000L
                while(System.nanoTime()<deadline&&withTimeoutOrNull(750){writes.receive()}!=null)Unit
                runCatching {persist()}
            }
        }
    }
    suspend fun awaitReady(){loaded.await()}
    /** Portable reading metadata deliberately excludes successful refresh checks,
     * transport state and request tokens: a new installation must check sources. */
    internal fun portableSnapshot(): PortableProgress=synchronized(gate) {
        PortableProgress(feeds=data.feeds.toMap(),readKeys=data.readKeys.toSet(),readAt=data.readAt)
    }
    internal fun mergePortable(incoming: PortableProgress) {
        val at=clock()
        mutate {old->
            val shared=(incoming.readKeys+old.readKeys).toList().takeLast(FeedReading.MAX_READ).toCollection(LinkedHashSet())
            val feeds=LinkedHashMap(old.feeds)
            incoming.feeds.forEach {(id,memory)->if(id !in feeds&&valid(id))feeds[id]=FeedReading.sanitize(memory,at).copy(updatedAt=at)}
            feeds.replaceAll {_,memory->
                val read=(memory.readKeys+shared.filter {it in memory.acceptedKeys}).toList().takeLast(FeedReading.MAX_READ).toCollection(LinkedHashSet())
                memory.copy(readKeys=read,freshKeys=memory.freshKeys-read)
            }
            old.copy(feeds=feeds,readKeys=shared,readAt=if(shared.isEmpty())old.readAt else at)
        }
    }
    fun get(id: String): FeedMemory=synchronized(gate){data.feeds[id] ?: FeedMemory(readKeys=data.readKeys)}
    private fun valid(id: String)=id.isNotBlank()&&id.length<=FeedReading.MAX_KEY&&!id.any(Char::isISOControl)
    private fun mutate(operation: (FeedMemoryFile)->FeedMemoryFile) {
        synchronized(gate) {
            if(!initialized)queued+=operation
            val next=bounded(operation(data))
            if(next!=data||next.readKeys.toList()!=data.readKeys.toList()||next.feeds.any {(id,memory)->memory.readKeys.toList()!=data.feeds[id]?.readKeys?.toList()}) {
                data=next;revision++;publish();writes.trySend(Unit)
            }
        }
    }
    private fun feed(id: String,operation: (FeedMemory)->FeedMemory) {
        if(!valid(id))return
        mutate {old->
            val previous=old.feeds[id] ?: FeedMemory()
            val next=operation(previous)
            if(next===previous)old else old.copy(feeds=LinkedHashMap(old.feeds).apply {remove(id);put(id,next)})
        }
    }
    fun reconcile(id: String,availableOrderedKeys: List<String>) {
        if(!valid(id))return
        val keys=availableOrderedKeys.toList();val at=clock()
        mutate {old->
            val previous=old.feeds[id] ?: FeedMemory()
            val shared=old.readKeys.filter {it in keys||it in previous.acceptedKeys}
            val known=(previous.readKeys+shared).toList().takeLast(FeedReading.MAX_READ).toCollection(LinkedHashSet())
            val seeded=if(known==previous.readKeys)previous else previous.copy(readKeys=known,freshKeys=previous.freshKeys-known)
            val next=FeedReading.reconcile(seeded,keys,at)
            if(next===previous)old else old.copy(feeds=LinkedHashMap(old.feeds).apply {remove(id);put(id,next)})
        }
    }
    /** Admit the fetched local snapshot once when entering a reading session.
     * Shared read marks seed the context exactly as ordinary reconciliation;
     * this admission neither views posts nor certifies a successful refresh. */
    fun enterSession(id: String,availableOrderedKeys: List<String>) {
        if(!valid(id))return
        val keys=availableOrderedKeys.toList();val at=clock()
        mutate {old->
            val previous=old.feeds[id] ?: FeedMemory()
            val shared=old.readKeys.filter {it in keys||it in previous.acceptedKeys}
            val known=(previous.readKeys+shared).toList().takeLast(FeedReading.MAX_READ).toCollection(LinkedHashSet())
            val seeded=if(known==previous.readKeys)previous else previous.copy(readKeys=known,freshKeys=previous.freshKeys-known)
            val next=FeedReading.enterSession(seeded,keys,at)
            if(next===previous)old else old.copy(feeds=LinkedHashMap(old.feeds).apply {remove(id);put(id,next)})
        }
    }
    fun accept(id: String,availableOrderedKeys: List<String>,arrivalKeys: kotlin.collections.Collection<String>) {val keys=availableOrderedKeys.toList();val arrivals=arrivalKeys.toList();val at=clock();feed(id){FeedReading.accept(it,keys,arrivals,at)}}
    /** Confident content matches extend the current message's identities, not its card/order. */
    fun mergeAliases(id: String,groups: List<List<String>>,inheritRead: Boolean=true) {
        if(!valid(id)||groups.isEmpty())return
        val snapshot=groups.map {it.toList()};val at=clock()
        mutate {old->
            val previous=old.feeds[id] ?: return@mutate old
            val accepted=previous.acceptedKeys.toSet()
            val eligible=snapshot.filter {group->group.any(accepted::contains)}
            if(eligible.isEmpty())return@mutate old
            val identities=eligible.flatten().toSet()
            val shared=old.readKeys.filter(identities::contains)
            val known=(previous.readKeys+shared).toList().takeLast(FeedReading.MAX_READ).toCollection(LinkedHashSet())
            val seeded=if(known==previous.readKeys)previous else previous.copy(readKeys=known,freshKeys=previous.freshKeys-known)
            val next=FeedReading.mergeAliases(seeded,eligible,at,inheritRead)
            val gained=next.readKeys-previous.readKeys
            val newlyShared=gained-old.readKeys
            if(next==previous&&newlyShared.isEmpty())return@mutate old
            val feeds=LinkedHashMap(old.feeds.mapValues {(_,memory)->if(gained.isEmpty())memory else FeedReading.markRead(memory,gained,at)})
            feeds.remove(id);feeds[id]=next
            val ledger=if(newlyShared.isEmpty())old.readKeys else LinkedHashSet(old.readKeys).apply {addAll(newlyShared)}.toList().takeLast(FeedReading.MAX_READ).toCollection(LinkedHashSet())
            old.copy(feeds=feeds,readKeys=ledger,readAt=if(newlyShared.isEmpty())old.readAt else at)
        }
    }
    /** Observe only an explicitly admitted source snapshot, never a frozen card's
     * background sidecar. Source timestamps prevent older cached copies undoing
     * newer revisions. Unknown legacy marks are adopted once on upgrade. */
    fun observeContent(posts: List<Post>) = observePrepared(ContentRevisions.observations(posts))
    internal fun observePrepared(snapshots: List<ContentObservation>) {
        val at=clock()
        mutate {old->
            val stamps=LinkedHashMap(old.contentStamps.orEmpty());val changed=mutableSetOf<String>()
            snapshots.forEach {(key,signature,fetched)->
                val previous=stamps[key]
                if(previous==null||fetched>=previous.fetchedAt) {
                    if(previous!=null&&previous.signature!=signature)changed+=key
                    stamps.remove(key);stamps[key]=ContentStamp(signature,fetched,at)
                }
            }
            val feeds=if(changed.isEmpty())old.feeds else old.feeds.mapValues {(_,m)->m.copy(readKeys=m.readKeys-changed,freshKeys=m.freshKeys+(changed.intersect(m.acceptedKeys.toSet())))}
            old.copy(feeds=feeds,readKeys=old.readKeys-changed,contentStamps=stamps)
        }
    }
    fun markRead(id: String,actuallyViewedKeys: kotlin.collections.Collection<String>) {
        val keys=actuallyViewedKeys.toList();val at=clock()
        mutate {old->
            val owned=old.feeds[id] ?: return@mutate old
            val viewed=keys.filter {it in owned.acceptedKeys}.distinct()
            if(viewed.isEmpty())return@mutate old
            val shared=LinkedHashSet(old.readKeys).apply {viewed.forEach {remove(it);add(it)}}.toList().takeLast(FeedReading.MAX_READ).toCollection(LinkedHashSet())
            val feeds=LinkedHashMap(old.feeds.mapValues {(_,memory)->FeedReading.markRead(memory,viewed,at)})
            // Shared read marks update other copies without making those contexts recent.
            feeds.remove(id)?.let {feeds[id]=it}
            old.copy(readKeys=shared,readAt=at,feeds=feeds)
        }
    }
    fun anchor(id: String,key: String,offset: Int) {val at=clock();feed(id){FeedReading.anchor(it,key,offset,at)}}
    fun keepReturn(id: String) {val at=clock();feed(id){FeedReading.keepReturn(it,at)}}
    fun clearReturn(id: String) {val at=clock();feed(id){FeedReading.clearReturn(it,at)}}
    fun reset(id: String) {mutate {it.copy(feeds=it.feeds-id)}}
    fun syncFollows(ids: kotlin.collections.Collection<String>) {val keys=ids.filter(::valid).distinct();val at=clock();mutate {it.copy(refresh=RefreshLedger.sync(it.refresh,keys,at))}}
    fun begin(ids: kotlin.collections.Collection<String>): Long {
        val keys=ids.filter(::valid).distinct();val at=clock()
        return synchronized(gate) {
            val token=maxOf(issuedToken,data.token,data.refresh.values.maxOfOrNull {it.token} ?: 0).coerceAtMost(Long.MAX_VALUE-1)+1
            issuedToken=token
            mutate {it.copy(refresh=RefreshLedger.begin(it.refresh,keys,token,at),token=token)}
            token
        }
    }
    fun success(id: String,token: Long,at: Long) {mutate {it.copy(refresh=RefreshLedger.success(it.refresh,id,token,at))}}
    fun catchUpHead(id: String,token: Long,keys: List<String>) {val snapshot=keys.toList();val at=clock();mutate {it.copy(refresh=RefreshLedger.catchUpHead(it.refresh,id,token,snapshot,at))}}
    fun fail(id: String,token: Long,reason: String) {val at=clock();mutate {it.copy(refresh=RefreshLedger.fail(it.refresh,id,token,reason,at))}}
    /** Call after the latest viewport capture on lifecycle stop. */
    suspend fun flush()=withContext(Dispatchers.IO){loaded.await();persist()}
    private fun publish(){mutableStates.value=data.feeds;mutableRefresh.value=data.refresh}

    private fun estimate(memory: FeedMemory): Long = 512L+
        (memory.acceptedKeys+memory.stagedKeys+memory.readKeys+memory.freshKeys+memory.anchorNeighbors+listOfNotNull(memory.anchorKey)+memory.resumeReturn?.let {listOf(it.key)+it.neighbors}.orEmpty()).sumOf {it.length*6L+4}
    private fun bounded(value: FeedMemoryFile): FeedMemoryFile {
        val now=clock();val cutoff=now-RETENTION
        val feeds=LinkedHashMap(value.feeds.filter {(id,row)->valid(id)&&row.updatedAt>=cutoff}.entries.toList().takeLast(MAX_FEEDS).associate {it.toPair()})
        sizes.keys.retainAll(feeds.keys)
        feeds.forEach {(id,row)->
            val old=data.feeds[id]
            // Anchor-only changes reuse the expensive reading-history estimate.
            if(id !in sizes||old==null||old.acceptedKeys!==row.acceptedKeys||old.stagedKeys!==row.stagedKeys||old.readKeys!==row.readKeys||old.freshKeys!==row.freshKeys)sizes[id]=estimate(row)
        }
        val checks=LinkedHashMap(value.refresh.entries.filter {(id,row)->valid(id)&&row.updatedAt>=cutoff}.takeLast(MAX_FOLLOWS).associate {it.toPair()})
        fun checkBytes()=checks.entries.sumOf {it.key.length*6L+it.value.reason.orEmpty().length*6L+it.value.catchUpHead.orEmpty().sumOf {key->key.length*6L+4}+256}
        while(checks.isNotEmpty()&&checkBytes()>MAX_BYTES/8)checks.remove(checks.keys.first())
        val shared=if(value.readAt<cutoff)mutableListOf()else value.readKeys.orEmpty().filter(::valid).distinct().takeLast(FeedReading.MAX_READ).toMutableList()
        var sharedBytes=shared.sumOf {it.length*6L+4}
        while(shared.isNotEmpty()&&sharedBytes>MAX_BYTES/8)sharedBytes-=shared.removeAt(0).let {it.length*6L+4}
        val stamps=if(value.contentStamps===stampMemo)stampMemo.orEmpty()else {
            val clean=LinkedHashMap(value.contentStamps.orEmpty().filter {(id,stamp)->valid(id)&&stamp.signature.length==64&&stamp.signature.all {it in 'a'..'f'||it in '0'..'9'}&&stamp.updatedAt>=cutoff}.entries.toList().takeLast(FeedReading.MAX_READ).associate {it.toPair()})
            var bytes=clean.entries.sumOf {it.key.length*6L+192}
            while(clean.isNotEmpty()&&bytes>MAX_BYTES/4){val key=clean.keys.first();bytes-=key.length*6L+192;clean.remove(key)}
            stampBytesMemo=bytes;stampMemo=clean;clean
        }
        val ledgerBytes=checkBytes()+sharedBytes+stampBytesMemo
        // Compact oldest contexts first, keeping their anchor and independent return
        // position. The active context is last in insertion order and is considered last.
        for(id in feeds.keys.toList()) {
            if(sizes.filterKeys(feeds::containsKey).values.sum()+ledgerBytes<=MAX_BYTES)break
            val row=feeds.getValue(id);val compact=FeedReading.compact(row)
            if(compact!=row){feeds[id]=compact;sizes[id]=estimate(compact)}
        }
        while(feeds.isNotEmpty()&&sizes.filterKeys(feeds::containsKey).values.sum()+ledgerBytes>MAX_BYTES) {val id=feeds.keys.first();feeds.remove(id);sizes.remove(id)}
        val sharedSet=if(shared.size==value.readKeys.size)value.readKeys else shared.toCollection(LinkedHashSet())
        return value.copy(feeds=feeds,refresh=checks,readKeys=sharedSet,contentStamps=stamps)
    }
    private fun readFile(): FeedMemoryFile {
        val now=clock()
        val restored=runCatching {
            val atomic=AtomicFile(file)
            atomic.openRead().use {input->
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(out.size()<=MAX_BYTES) {
                    val count=input.read(buffer,0,minOf(buffer.size,MAX_BYTES.toInt()+1-out.size()))
                    if(count<0)break
                    out.write(buffer,0,count)
                }
                val bytes=out.toByteArray()
                if(bytes.size>MAX_BYTES)return@use FeedMemoryFile()
                gson.fromJson(String(bytes,Charsets.UTF_8),FeedMemoryFile::class.java)?.takeIf {it.version==1} ?: FeedMemoryFile()
            }
        }.getOrDefault(FeedMemoryFile())
        val feeds=restored.feeds.orEmpty().entries.toList().takeLast(MAX_FEEDS).mapNotNull {(id,row)->
            if(!valid(id))null else runCatching {id to FeedReading.sanitize(row,now)}.getOrNull()
        }.toMap()
        val checks=restored.refresh.orEmpty().entries.toList().takeLast(MAX_FOLLOWS).mapNotNull {(id,row)->
            if(!valid(id))null else runCatching {id to RefreshLedger.restore(row,now)}.getOrNull()
        }.toMap()
        val shared=restored.readKeys.orEmpty().filter(::valid).distinct().takeLast(FeedReading.MAX_READ).toCollection(LinkedHashSet())
        return FeedMemoryFile(feeds=feeds,refresh=checks,token=restored.token.coerceIn(0,now.coerceAtLeast(0)),readKeys=shared,readAt=restored.readAt.coerceIn(0,now.coerceAtLeast(0)),contentStamps=restored.contentStamps)
    }
    private suspend fun persist()=writeLock.withLock {
        val snapshot=synchronized(gate){revision to data}
        if(snapshot.first==writtenRevision)return@withLock
        var retained=snapshot.second
        var bytes=gson.toJson(retained).toByteArray(Charsets.UTF_8)
        // A final byte check also bounds unusual escaped local identifiers.
        // Serialization and any defensive LRU pruning happen exclusively on IO.
        while(bytes.size>MAX_BYTES) {
            retained=when {
                retained.feeds.isNotEmpty()->retained.copy(feeds=retained.feeds-retained.feeds.keys.first())
                retained.readKeys.isNotEmpty()->retained.copy(readKeys=retained.readKeys.drop(retained.readKeys.size/2+1).toCollection(LinkedHashSet()))
                !retained.contentStamps.isNullOrEmpty()->retained.copy(contentStamps=retained.contentStamps.entries.drop(retained.contentStamps.size/2+1).associate {it.toPair()})
                retained.refresh.isNotEmpty()->retained.copy(refresh=retained.refresh-retained.refresh.keys.first())
                else->FeedMemoryFile()
            }
            bytes=gson.toJson(retained).toByteArray(Charsets.UTF_8)
        }
        file.parentFile?.mkdirs()
        val atomic=AtomicFile(file)
        val output=atomic.startWrite()
        try {output.write(bytes);atomic.finishWrite(output);writtenRevision=snapshot.first}
        catch(e: Exception){atomic.failWrite(output);throw e}
    }
    companion object {
        const val MAX_FEEDS=32
        const val MAX_FOLLOWS=2000
        const val MAX_BYTES=4L*1024*1024
        const val RETENTION=90L*24*60*60*1000
    }
}
