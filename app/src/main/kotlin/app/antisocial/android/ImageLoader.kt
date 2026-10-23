package app.antisocial.android

import android.graphics.Bitmap
import android.util.LruCache
import app.antisocial.core.KeyedMutex
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
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
            // Local comparison must not wait for an unrelated unavailable
            // subscription to finish its network timeout. Share the bounded
            // decoder pool with visible images and yield between cached files.
            repo.availableMedia(url)?.let {file->
                try {val bitmap=decode(file,512);try {identities.observe(url,bitmap)}finally {bitmap.recycle()}}
                catch(e: kotlinx.coroutines.CancellationException){throw e}
                catch(_: Exception){/* Missing or undecodable ordinary cache is not evidence. */}
            }
            delay(40);yield()
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
            val warming=async {try {repo.contentSandbox.warm()}catch(e:CancellationException){throw e}catch(_:Exception){/* The actual read owns recovery. */}}
            try {
                val file=repo.media(url,source)
                repo.aiMetadata.observe(url,file)
                // The chosen decoder owns its bind. Ordinary images join the
                // warm worker through its mutex; AVIF/HEIF need not wait for an
                // unrelated isolated-worker startup before their platform lane.
                decode(file,edge).also {memory.put(key,it);identities.observe(url,it)}
            }finally {warming.cancel()}
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
        repo.contentSandbox.image(file,edge)
    }
    fun clear(){memory.evictAll();identities.clear()}
}
