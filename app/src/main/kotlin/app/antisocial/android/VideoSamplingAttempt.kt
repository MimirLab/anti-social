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
    private var retained:Retained?=null
    private var closed=false
    internal val retainedBlocks:Int get()=retained?.view?.cachedBlocks ?: 0
    suspend fun ranges(media:Media,source:String,open:suspend (GatewayMediaRanges?)->GatewayMediaRanges):GatewayMediaRanges {
        currentCoroutineContext().ensureActive()
        check(!closed){"Recording attempt already ended"}
        val input=VideoAssetIdentity.acquisitionKey(media)
        val previous=retained?.takeIf {it.input==input&&it.source==source&&it.view.reusable}
        retained=previous
        val view=open(previous?.view)
        currentCoroutineContext().ensureActive()
        retained=Retained(input,source,view)
        return view
    }
    override fun close(){closed=true;retained=null}
}
