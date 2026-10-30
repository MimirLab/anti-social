/* anti social, GPL-3.0-or-later. One recording's in-flight comparison only. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Retain validated blocks only between foreground-preempted retries of one
 * recording. No disk cache, new parent allowance or detached acquisition. Each
 * retry validates its prefix; the seed retains its session and original window. */
internal class VideoSamplingAttempt:AbstractCoroutineContextElement(Key),AutoCloseable {
    companion object Key:CoroutineContext.Key<VideoSamplingAttempt>
    private data class Retained(val input:VideoAcquisitionKey,val source:String,val view:GatewayMediaRanges)
    private data class Prepared(val input:VideoAcquisitionKey,val source:String,val initial:GatewayMediaPreparation)
    private var retained:Retained?=null
    private var prepared:Prepared?=null
    private var closed=false
    internal val retainedBlocks:Int get()=retained?.view?.cachedBlocks ?: if(prepared!=null)1 else 0
    suspend fun prepare(media:Media,source:String,open:suspend ()->GatewayMediaPreparation) {
        currentCoroutineContext().ensureActive();check(!closed&&retained==null){"Recording attempt already started or ended"}
        prepared?.initial?.close();prepared=null
        val initial=open()
        try {currentCoroutineContext().ensureActive();check(!closed);prepared=Prepared(VideoAssetIdentity.acquisitionKey(media),source,initial)}
        catch(e:Exception){initial.close();throw e}
    }
    suspend fun preparedRanges(media:Media,source:String,claim:suspend (GatewayMediaPreparation)->GatewayMediaRanges,
        open:suspend (GatewayMediaRanges?)->GatewayMediaRanges):GatewayMediaRanges {
        currentCoroutineContext().ensureActive();check(!closed){"Recording attempt already ended"}
        val initial=prepared;prepared=null
        if(initial!=null)try {
            if(initial.input==VideoAssetIdentity.acquisitionKey(media)&&initial.source==source) {
                val view=claim(initial.initial);currentCoroutineContext().ensureActive()
                retained=Retained(initial.input,source,view);return view
            }
        }finally {initial.initial.close()}
        return ranges(media,source,open)
    }
    suspend fun ranges(media:Media,source:String,open:suspend (GatewayMediaRanges?)->GatewayMediaRanges):GatewayMediaRanges {
        currentCoroutineContext().ensureActive()
        check(!closed){"Recording attempt already ended"}
        prepared?.initial?.close();prepared=null
        val input=VideoAssetIdentity.acquisitionKey(media)
        val previous=retained?.takeIf {it.input==input&&it.source==source&&it.view.reusable}
        retained=previous
        val view=open(previous?.view)
        currentCoroutineContext().ensureActive()
        retained=Retained(input,source,view)
        return view
    }
    override fun close(){closed=true;prepared?.initial?.close();prepared=null;retained=null}
}
