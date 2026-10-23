package app.antisocial.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.antisocial.core.KeyedMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import app.antisocial.core.Post
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Bounded decoded-image reuse; original bytes still use the ordinary cache. */
class ImageLoader(private val repo: Repository) {
    val identities=MediaIdentityStore()
    private val memory=object: LruCache<String,Bitmap>(24*1024*1024) {
        override fun sizeOf(key: String,value: Bitmap)=value.allocationByteCount
    }
    private val keys=KeyedMutex<String>()
    private val decoders=Semaphore(2)
    /** Rebuild the bounded comparison bank from bytes already on this device.
     * Never calls media(), a route or a downloader. Essential reads take priority. */
    internal suspend fun restoreCachedIdentities(posts: List<Post>)=withContext(Dispatchers.IO) {
        val urls=posts.asSequence().flatMap {it.media.asSequence()}.mapNotNull {media->
            when(media.type){"image"->media.url;"video"->media.preview;else->null}
        }.distinct().take(2048).toList()
        for(url in urls) {
            coroutineContext.ensureActive()
            if(identities.knows(url))continue
            while(repo.pool.hasForegroundReads||repo.gateway.hasForegroundRequests||repo.feedRefresh.value.running)delay(250)
            repo.availableMedia(url)?.let {file->
                try {val bitmap=decode(file,512);try {identities.observe(url,bitmap)}finally {bitmap.recycle()}}
                catch(e: kotlinx.coroutines.CancellationException){throw e}
                catch(_: Exception){/* Missing or undecodable ordinary cache is not evidence. */}
            }
            yield()
        }
    }
    suspend fun load(url: String,source: String,maxEdge: Int,localOnly: Boolean=false,localFile: File?=null): Bitmap=withContext(Dispatchers.IO) {
        val work=repo.cardWork.begin(listOf(mediaWorkKey(url)),"Images")
        try {
        if(localFile!=null)return@withContext loadLocal(localFile,maxEdge,url)
        if(localOnly)return@withContext loadLocal(repo.savedMedia(url) ?: error("Image is not downloaded"),maxEdge,url)
        val edge=maxEdge.coerceIn(128,2048)
        val key="$edge:$url"
        memory.get(key)?.let {observeAvailable(url);identities.observe(url,it);return@withContext it}
        keys.withKey(key) {
            memory.get(key)?.let {observeAvailable(url);identities.observe(url,it);return@withKey it}
            val file=repo.media(url,source)
            repo.aiMetadata.observe(url,file)
            decode(file,edge).also {memory.put(key,it);identities.observe(url,it)}
        }
        }finally {work.close()}
    }
    /** A decoded bitmap can outlive a bounded metadata report, never the authority of its bytes. */
    private suspend fun observeAvailable(url: String) {
        repo.availableMedia(url)?.let {repo.aiMetadata.observe(url,it)}
    }
    /** Strict local reader path: a missing file never falls back to the network. */
    suspend fun loadLocal(file: File,maxEdge: Int,url: String?=null): Bitmap=withContext(Dispatchers.IO) {
        require(file.isFile){"Image is not downloaded"}
        url?.let {repo.aiMetadata.observe(it,file)}
        val edge=maxEdge.coerceIn(128,2048)
        val key="$edge:local:${file.canonicalPath}:${file.length()}:${file.lastModified()}"
        memory.get(key)?.let {return@withContext it}
        keys.withKey(key) {
            require(file.isFile){"Image is not downloaded"}
            memory.get(key) ?: decode(file,edge).also {memory.put(key,it)}
        }
    }
    private suspend fun decode(file: File,edge: Int): Bitmap=decoders.withPermit {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0){"Image format is unavailable"}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>edge)sample*=2
        BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample}) ?: error("Image could not be decoded")
    }
    fun clear(){memory.evictAll();identities.clear()}
}
