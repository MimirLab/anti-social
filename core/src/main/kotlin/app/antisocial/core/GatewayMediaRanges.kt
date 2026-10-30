/* anti social, GPL-3.0-or-later. A bounded, session-bound recording view. */
package app.antisocial.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GatewayRangeRepresentation(val final:String,val total:Long,val validator:String?)
internal class GatewayRangeScope(private val session:NetworkSession,private val url:String,private val source:String,
    private val observationOnly:Boolean,private val parent:Any?) {
    fun same(other:GatewayRangeScope)=parent!=null&&parent===other.parent&&session===other.session&&url==other.url&&source==other.source&&observationOnly==other.observationOnly
}
internal data class GatewayRangeSeed(val blocks:Map<Long,ByteArray>,val active:()->Boolean)

/** One unused initial prefix. Claiming it continues the original view and its
 * allowance; it cannot transfer a retry, source, session or parent budget. */
class GatewayMediaPreparation internal constructor(private var view:GatewayMediaRanges?):AutoCloseable {
    @Synchronized internal fun claim(owner:GatewayRangeScope):GatewayMediaRanges {
        val initial=checkNotNull(view){"Recording preparation already ended"};view=null
        initial.requireOwner(owner)
        return initial
    }
    @Synchronized override fun close(){view=null}
}

/** A short-lived byte view, not a playable/offline download. Only the Gateway
 * creates it. The decoder cannot choose a destination or acquire networking. */
class GatewayMediaRanges internal constructor(val length:Long,private val blockSize:Int,
    first:ByteArray,private val fetch:suspend (Long,Int)->ByteArray,private val recheck:Boolean=false,
    private val active:()->Boolean={true},seed:Map<Long,ByteArray> = emptyMap(),
    private val scope:GatewayRangeScope?=null,private val representation:GatewayRangeRepresentation?=null) {
    private val mutex=Mutex()
    private val blocks=linkedMapOf(0L to first)
    init {
        require(seed.size+1<=if(recheck)15 else 16)
        seed.forEach {(at,bytes)->
            require(at>0&&at<length&&at%blockSize==0L&&bytes.size==minOf(blockSize.toLong(),length-at).toInt())
            blocks[at]=bytes
        }
    }
    var fetchedBytes:Long=first.size.toLong();private set
    var fetchedBlocks:Int=1;private set
    /** A retry may retain this particular view, never revive an expired
     * observation window or an earlier connection-selection identity. */
    val reusable:Boolean get()=active()
    val cachedBlocks:Int get()=blocks.size
    private fun admit() {if(!reusable)throw kotlinx.coroutines.CancellationException("Recording view expired or connection mode changed")}
    internal fun requireOwner(owner:GatewayRangeScope) {
        admit()
        if(scope?.same(owner)!=true)throw kotlinx.coroutines.CancellationException("Recording retry changed its source or parent allowance")
    }
    internal suspend fun seed(first:ByteArray,owner:GatewayRangeScope,current:GatewayRangeRepresentation):GatewayRangeSeed?=mutex.withLock {
        requireOwner(owner)
        if(representation!=current||!blocks.getValue(0L).contentEquals(first))return@withLock null
        // Arrays are immutable inside a view; read() copies into its own output.
        GatewayRangeSeed(blocks.filterKeys {it!=0L},active)
    }
    /** Validator-free sampling is an observation, not a complete-file identity.
     * Re-read the initial block before accepting its derived scenes. This does
     * not prove that unsampled portions of a recording were unchanged. */
    suspend fun verify()=mutex.withLock {
        admit()
        if(recheck) {
            val first=blocks.getValue(0L);val latest=fetch(0,first.size)
            fetchedBytes+=latest.size;fetchedBlocks++
            if(!first.contentEquals(latest))throw java.io.IOException("Recording prefix changed during sampling")
        }
        admit()
    }
    suspend fun read(offset:Long,size:Int):ByteArray=mutex.withLock {
        admit()
        require(offset>=0&&size in 0..1024*1024)
        if(size==0||offset>=length)return@withLock ByteArray(0)
        val result=ByteArray(minOf(size.toLong(),length-offset).toInt())
        var copied=0
        while(copied<result.size) {
            val at=offset+copied;val start=at/blockSize*blockSize
            val bytes=blocks[start] ?: run {
                // Keep one exchange and one block for validator-free prefix
                // verification. Missing later scenes cannot consume its reserve.
                if(blocks.size>=if(recheck)15 else 16)throw java.io.IOException("Recording sampling read allowance exhausted")
                fetch(start,minOf(blockSize.toLong(),length-start).toInt())
            }.also {
                check(it.size==minOf(blockSize.toLong(),length-start).toInt())
                fetchedBytes+=it.size;fetchedBlocks++;blocks[start]=it
            }
            val inBlock=(at-start).toInt();val count=minOf(result.size-copied,bytes.size-inBlock)
            check(count>0);bytes.copyInto(result,copied,inBlock,inBlock+count);copied+=count
        }
        admit();result
    }
}
