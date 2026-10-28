/* anti social, GPL-3.0-or-later. The parent owns networking; the decoder gets a descriptor. */
package app.antisocial.android

import android.os.*
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import app.antisocial.core.*
import kotlinx.coroutines.*
import java.io.IOException

/** Finite, read-only virtual recording. This is neither a public proxy server
 * nor an offline file. Gateway rejects unsupported ranges/changed validators
 * and caps sixteen exchanges/four MiB; a failure creates no observation. */
internal class GatewayVideoSampler(private val repo:Repository) {
    internal data class Result(val frames:SampledVideoFrames,val fetchedBytes:Long,val fetchedBlocks:Int)
    suspend fun sample(media:Media,sourceUrl:String):Result=withContext(Dispatchers.IO) {withTimeout(20_000) {
        require(media.type=="video"&&media.audioUrl==null&&!VideoFormats.adaptive(VideoFormats.mime(media)))
        // A qualified supplied clip URL may omit HTTP validators. Such a read
        // stays a short-lived observation with a repeated prefix check, never
        // equivalence of complete recordings or reusable playable bytes.
        val ranges=repo.gateway.mediaRanges(media.url,sourceUrl,observationOnly=VideoAssetIdentity.key(media.url)!=null)
        val scope=currentCoroutineContext()
        val thread=HandlerThread("anti social video bytes").apply {start()}
        try {
            val callback=object:ProxyFileDescriptorCallback() {
                override fun onGetSize()=ranges.length
                override fun onRead(offset:Long,size:Int,data:ByteArray):Int {
                    if(offset<0||size<0||size>data.size)throw ErrnoException("video read",OsConstants.EINVAL)
                    try {
                        val bytes=runBlocking(scope){ranges.read(offset,minOf(size,1024*1024))}
                        bytes.copyInto(data);return bytes.size
                    }catch(e:Exception){throw ErrnoException("video read",OsConstants.EIO,e)}
                }
                override fun onRelease()=Unit
            }
            val storage=repo.context.getSystemService(StorageManager::class.java) ?: throw IOException("Recording descriptor unavailable")
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,callback,Handler(thread.looper)).use {fd->
                val frames=repo.contentSandbox.sampledVideo(fd)
                ranges.verify()
                Result(frames,ranges.fetchedBytes,ranges.fetchedBlocks)
            }
        }finally {thread.quitSafely()}
    }}
}
