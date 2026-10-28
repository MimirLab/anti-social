/* anti social, GPL-3.0-or-later. A bounded, session-bound recording view. */
package app.antisocial.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A short-lived byte view, not a playable/offline download. Only the Gateway
 * creates it. The decoder cannot choose a destination or acquire networking. */
class GatewayMediaRanges internal constructor(val length:Long,private val blockSize:Int,
    first:ByteArray,private val fetch:suspend (Long,Int)->ByteArray,private val recheck:Boolean=false) {
    private val mutex=Mutex()
    private val blocks=linkedMapOf(0L to first)
    var fetchedBytes:Long=first.size.toLong();private set
    var fetchedBlocks:Int=1;private set
    /** Validator-free sampling is an observation, not a complete-file identity.
     * Re-read the initial block before accepting its derived scenes. This does
     * not prove that unsampled portions of a recording were unchanged. */
    suspend fun verify()=mutex.withLock {
        if(recheck) {
            val first=blocks.getValue(0L);val latest=fetch(0,first.size)
            fetchedBytes+=latest.size;fetchedBlocks++
            if(!first.contentEquals(latest))throw java.io.IOException("Recording prefix changed during sampling")
        }
    }
    suspend fun read(offset:Long,size:Int):ByteArray=mutex.withLock {
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
        result
    }
}
