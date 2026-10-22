/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

data class FeedAnchor(val key: String,val offset: Int=0,val neighbors: List<String> = emptyList())
data class FeedPosition(val key: String,val index: Int,val offset: Int)
data class FeedMemory(
    val initialized: Boolean=false,
    val acceptedKeys: List<String> = emptyList(),
    val stagedKeys: List<String> = emptyList(),
    val readKeys: Set<String> = emptySet(),
    val freshKeys: Set<String> = emptySet(),
    val anchorKey: String?=null,
    val anchorOffset: Int=0,
    val anchorNeighbors: List<String> = emptyList(),
    val resumeReturn: FeedAnchor?=null,
    val updatedAt: Long=0
)

/** Identity-based reading state. Neither chronology nor a jump is evidence of reading. */
object FeedReading {
    const val MAX_ACCEPTED=1000
    const val MAX_READ=4000
    const val MAX_KEY=512
    private fun valid(key: String?)=key!=null&&key.isNotBlank()&&key.length<=MAX_KEY&&!key.any(Char::isISOControl)
    private fun keys(values: Iterable<String>,limit: Int)=values.filter {valid(it)}.distinct().take(limit)
    private fun change(old: FeedMemory,next: FeedMemory,at: Long): FeedMemory =
        if(next.copy(updatedAt=old.updatedAt)==old&&next.readKeys.toList()==old.readKeys.toList())old else next.copy(updatedAt=at.coerceAtLeast(1))

    fun reconcile(memory: FeedMemory,availableKeys: List<String>,at: Long): FeedMemory {
        val available=keys(availableKeys,MAX_ACCEPTED)
        if(!memory.initialized) {
            // Room's initial empty emission must not turn the first real page into arrivals.
            if(available.isEmpty())return memory
            return change(memory,memory.copy(initialized=true,acceptedKeys=available,stagedKeys=emptyList(),freshKeys=emptySet()),at)
        }
        val accepted=memory.acceptedKeys.toSet()
        return change(memory,memory.copy(stagedKeys=available.filterNot(accepted::contains)),at)
    }
    /** Start a reading session with every already-fetched local post available
     * above the saved position. This is a one-time admission, not a read action:
     * anchors, independent return positions and prior read marks remain intact.
     * Later live emissions still use reconcile and wait behind the arrival
     * banner. An empty initial storage emission cannot consume this admission. */
    fun enterSession(memory: FeedMemory,availableKeys: List<String>,at: Long): FeedMemory {
        val available=keys(availableKeys,MAX_ACCEPTED)
        if(available.isEmpty())return memory
        val reconciled=reconcile(memory,available,at)
        // A first-ever or compacted batch has no accepted-key baseline from
        // which to infer fresh arrivals; reconcile already initialized it.
        if(!memory.initialized)return reconciled
        val admitted=accept(reconciled,available,reconciled.stagedKeys,at)
        val shown=available.toSet()
        // An accepted cache row may be temporarily absent at entry and return
        // later in the same session. Retain its admission identity after the
        // current fetched window, without crowding out any available post or
        // mislabelling that return as newly fetched content.
        val retained=keys(memory.acceptedKeys,MAX_ACCEPTED).filterNot(shown::contains)
            .take((MAX_ACCEPTED-admitted.acceptedKeys.size).coerceAtLeast(0))
        val accepted=admitted.acceptedKeys+retained
        val fresh=(admitted.freshKeys+memory.freshKeys.filter {it in retained&&it !in admitted.readKeys}).toSet()
        return change(memory,admitted.copy(acceptedKeys=accepted,freshKeys=fresh),at)
    }
    /** Only the arrivals represented by the user's action are accepted. Later arrivals stay staged. */
    fun accept(memory: FeedMemory,availableKeys: List<String>,arrivalKeys: kotlin.collections.Collection<String>,at: Long): FeedMemory {
        if(!memory.initialized)return reconcile(memory,availableKeys,at)
        val available=keys(availableKeys,MAX_ACCEPTED)
        val arrivals=arrivalKeys.toSet().intersect(memory.stagedKeys.toSet())
        val acceptedSet=memory.acceptedKeys.toSet()+arrivals
        val accepted=available.filter(acceptedSet::contains)
        val fresh=(memory.freshKeys+arrivals).filter {it in accepted&&!memory.readKeys.contains(it)}.toSet()
        return change(memory,memory.copy(acceptedKeys=accepted,stagedKeys=available.filterNot(accepted::contains),freshKeys=fresh),at)
    }
    fun isRead(memory: FeedMemory,key: String)=key in memory.readKeys
    fun markRead(memory: FeedMemory,actuallyViewedKeys: kotlin.collections.Collection<String>,at: Long): FeedMemory {
        val viewed=actuallyViewedKeys.filter {it in memory.acceptedKeys}.distinct()
        if(viewed.isEmpty())return memory
        val read=LinkedHashSet(memory.readKeys)
        viewed.forEach {read.remove(it);read.add(it)}
        val bounded=read.toList().takeLast(MAX_READ).toCollection(LinkedHashSet())
        return change(memory,memory.copy(readKeys=bounded,freshKeys=memory.freshKeys-viewed.toSet()),at)
    }
    /** Attach confidently matched copies to an already accepted message, without accepting
     * unrelated arrivals or changing its position. A copy of a read message is still read;
     * discovering an unread copy alone is never evidence that the message was read. */
    fun mergeAliases(memory: FeedMemory,groups: List<List<String>>,at: Long,inheritRead: Boolean=true): FeedMemory {
        if(!memory.initialized||groups.isEmpty())return memory
        val original=memory.acceptedKeys.toSet()
        val accepted=LinkedHashSet(memory.acceptedKeys)
        val read=LinkedHashSet(memory.readKeys)
        val fresh=memory.freshKeys.toMutableSet()
        for(values in groups.take(MAX_ACCEPTED)) {
            val group=keys(values,MAX_ACCEPTED)
            if(group.none(original::contains))continue
            val attached=group.filter {key->key in accepted||accepted.size<MAX_ACCEPTED&&accepted.add(key)}
            if(inheritRead&&group.any(read::contains))attached.forEach {key->read.remove(key);read.add(key);fresh.remove(key)}
        }
        val bounded=read.toList().takeLast(MAX_READ).toCollection(LinkedHashSet())
        return change(memory,memory.copy(acceptedKeys=accepted.toList(),stagedKeys=memory.stagedKeys.filterNot(accepted::contains),readKeys=bounded,freshKeys=fresh),at)
    }
    fun anchor(memory: FeedMemory,key: String,offset: Int,at: Long): FeedMemory {
        val index=memory.acceptedKeys.indexOf(key)
        if(index<0)return memory
        val neighbors=buildList {
            for(distance in 1..4) {
                memory.acceptedKeys.getOrNull(index+distance)?.let(::add)
                memory.acceptedKeys.getOrNull(index-distance)?.let(::add)
            }
        }
        return change(memory,memory.copy(anchorKey=key,anchorOffset=offset.coerceIn(-1_000_000,1_000_000),anchorNeighbors=neighbors),at)
    }
    fun keepReturn(memory: FeedMemory,at: Long): FeedMemory {
        val key=memory.anchorKey ?: return memory
        if(memory.resumeReturn!=null)return memory
        return change(memory,memory.copy(resumeReturn=FeedAnchor(key,memory.anchorOffset,memory.anchorNeighbors)),at)
    }
    fun clearReturn(memory: FeedMemory,at: Long)=change(memory,memory.copy(resumeReturn=null),at)
    /** Retain an inactive feed's exact position when its reading history exceeds the local budget.
     * Its next real batch initializes normally; a saved anchor does not require cached content. */
    fun compact(memory: FeedMemory)=memory.copy(initialized=false,acceptedKeys=emptyList(),stagedKeys=emptyList(),readKeys=emptySet(),freshKeys=emptySet())
    fun restore(memory: FeedMemory,availableKeys: List<String>): FeedPosition? =
        position(memory.anchorKey?.let {FeedAnchor(it,memory.anchorOffset,memory.anchorNeighbors)},availableKeys)
    fun restoreReturn(memory: FeedMemory,availableKeys: List<String>): FeedPosition?=position(memory.resumeReturn,availableKeys)
    private fun position(anchor: FeedAnchor?,availableKeys: List<String>): FeedPosition? {
        if(anchor==null||availableKeys.isEmpty())return null
        val exact=availableKeys.indexOf(anchor.key)
        if(exact>=0)return FeedPosition(anchor.key,exact,anchor.offset)
        val neighbor=anchor.neighbors.firstOrNull {it in availableKeys} ?: availableKeys.first()
        return FeedPosition(neighbor,availableKeys.indexOf(neighbor),0)
    }
    /** Capture priorReadKeys at session entry. New read marks cannot move this boundary.
     * Older unread holes never disappear below a misleading previously-read separator. */
    fun boundary(orderedKeys: List<String>,priorReadKeys: Set<String>): Int? {
        if(orderedKeys.isEmpty())return null
        val lastUnread=orderedKeys.indexOfLast {it !in priorReadKeys}
        return (lastUnread+1).takeIf {it<orderedKeys.size}
    }
    /** Validate local JSON defensively; missing collection fields from older records are allowed. */
    fun sanitize(memory: FeedMemory,now: Long): FeedMemory {
        val accepted=keys(memory.acceptedKeys.orEmpty(),MAX_ACCEPTED)
        val read=keys(memory.readKeys.orEmpty(),Int.MAX_VALUE).takeLast(MAX_READ).toCollection(LinkedHashSet())
        fun cleanAnchor(anchor: FeedAnchor?): FeedAnchor? {
            if(anchor==null||!valid(anchor.key))return null
            return anchor.copy(offset=anchor.offset.coerceIn(-1_000_000,1_000_000),neighbors=keys(anchor.neighbors.orEmpty(),8))
        }
        return memory.copy(acceptedKeys=accepted,stagedKeys=keys(memory.stagedKeys.orEmpty(),MAX_ACCEPTED).filterNot(accepted::contains),readKeys=read,
            freshKeys=keys(memory.freshKeys.orEmpty(),MAX_ACCEPTED).filter {it in accepted&&it !in read}.toSet(),
            anchorKey=memory.anchorKey?.takeIf(::valid),anchorOffset=memory.anchorOffset.coerceIn(-1_000_000,1_000_000),anchorNeighbors=keys(memory.anchorNeighbors.orEmpty(),8),
            resumeReturn=cleanAnchor(memory.resumeReturn),updatedAt=memory.updatedAt.coerceIn(0,now.coerceAtLeast(0)))
    }
}

enum class FollowRefreshStatus { PENDING, CHECKING, SUCCESS }
data class FollowRefresh(val token: Long=0,val status: FollowRefreshStatus=FollowRefreshStatus.PENDING,
    val lastSuccessAt: Long?=null,val reason: String?=null,val updatedAt: Long=0,val catchUpHead: List<String>?=null)

/** A previous success does not certify a later failed, skipped or cancelled check. */
object RefreshLedger {
    private fun cleanHead(keys: List<String?>): List<String> = keys.asSequence().filterNotNull().filter {it.isNotBlank()&&it.length<=FeedReading.MAX_KEY&&!it.any(Char::isISOControl)}.distinct().take(8).toList()
    fun sync(records: Map<String,FollowRefresh>,ids: kotlin.collections.Collection<String>,at: Long): Map<String,FollowRefresh> =
        ids.distinct().associateWith {records[it] ?: FollowRefresh(updatedAt=at)}
    fun begin(records: Map<String,FollowRefresh>,ids: kotlin.collections.Collection<String>,token: Long,at: Long): Map<String,FollowRefresh> {
        require(token>0)
        val next=records.toMutableMap()
        ids.distinct().forEach {id->val old=next[id] ?: FollowRefresh();if(token>old.token)next[id]=old.copy(token=token,status=FollowRefreshStatus.CHECKING,reason=null,updatedAt=at)}
        return next
    }
    fun success(records: Map<String,FollowRefresh>,id: String,token: Long,at: Long): Map<String,FollowRefresh> {
        val old=records[id] ?: return records
        if(old.token!=token||old.status!=FollowRefreshStatus.CHECKING||at<=0)return records
        return records+(id to old.copy(status=FollowRefreshStatus.SUCCESS,lastSuccessAt=at,reason=null,updatedAt=at,catchUpHead=null))
    }
    /** Capture the pre-refresh newest head once. Partial failed fetches may add
     * newer local rows, but cannot replace the frontier that still needs bridging.
     * An empty list records a first subscription; null means not captured yet. */
    fun catchUpHead(records: Map<String,FollowRefresh>,id: String,token: Long,keys: List<String>,at: Long): Map<String,FollowRefresh> {
        val old=records[id] ?: return records
        if(old.token!=token||old.status!=FollowRefreshStatus.CHECKING||old.catchUpHead!=null)return records
        return records+(id to old.copy(catchUpHead=cleanHead(keys),updatedAt=at))
    }
    fun fail(records: Map<String,FollowRefresh>,id: String,token: Long,reason: String,at: Long): Map<String,FollowRefresh> {
        val old=records[id] ?: return records
        if(old.token!=token||old.status!=FollowRefreshStatus.CHECKING)return records
        return records+(id to old.copy(status=FollowRefreshStatus.PENDING,reason=reason.take(240),updatedAt=at))
    }
    fun pending(ids: kotlin.collections.Collection<String>,records: Map<String,FollowRefresh>)=ids.distinct().count {records[it]?.let {row->row.status==FollowRefreshStatus.SUCCESS&&row.lastSuccessAt!=null}!=true}
    fun caughtUp(ids: kotlin.collections.Collection<String>,records: Map<String,FollowRefresh>)=ids.isNotEmpty()&&pending(ids,records)==0
    fun restore(record: FollowRefresh,now: Long): FollowRefresh {
        val success=record.lastSuccessAt?.takeIf {it in 1..now}
        val suppliedStatus: FollowRefreshStatus?=record.status
        val status=when {suppliedStatus==FollowRefreshStatus.CHECKING->FollowRefreshStatus.PENDING;suppliedStatus==FollowRefreshStatus.SUCCESS&&success==null->FollowRefreshStatus.PENDING;else->suppliedStatus ?: FollowRefreshStatus.PENDING}
        return record.copy(token=record.token.coerceIn(0,now.coerceAtLeast(0)),status=status,lastSuccessAt=success,
            reason=if(record.status==FollowRefreshStatus.CHECKING)"The previous check did not finish."else record.reason?.take(240),updatedAt=record.updatedAt.coerceIn(0,now.coerceAtLeast(0)),catchUpHead=record.catchUpHead?.let(::cleanHead))
    }
}
