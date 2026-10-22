/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.net.ConnectivityManager
import android.os.StatFs
import android.graphics.BitmapFactory
import app.antisocial.core.VideoFormats
import app.antisocial.core.YouTubeUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** The library serializes runs so video allowance checks include earlier commits.
 * A resumed job retries incomplete files from the beginning; partial responses are
 * never published as playable media. Ownership and promotion stay in LocalLibrary.
 */
class SavedMediaDownloads(private val repo: Repository) {
    suspend fun run(owner: String,token: String,mimeTypes: Map<String,String?> = emptyMap(),youtubeVideos: Map<String,String> = emptyMap(),splitVideos: Set<String> = emptySet()): Boolean=withContext(Dispatchers.IO) {
        if(!repo.library.validToken(owner,token))return@withContext true
        var retry=false
        val rows=repo.dao.allSavedAssets().filter {it.ownerKey==owner&&it.token==token}
        for(asset in rows) {
            currentCoroutineContext().ensureActive()
            if(!repo.library.validToken(owner,token))return@withContext true
            val latest=repo.dao.asset(owner,asset.assetKey) ?: continue
            if(latest.token!=token||latest.status in terminal)continue
            val available=repo.library.assetFile(asset.url)
            if(available!=null) {
                val invalid=SavedAssetValidation.problem(available,asset.type)
                if(invalid==null)status(asset,"COMPLETE",available.length())else status(asset,"UNSUPPORTED",available.length(),invalid)
                continue
            }
            if(asset.type=="image"&&promoteCachedImage(asset)!=null)continue
            // A COMPLETE manifest whose file was removed can be explicitly retried.
            if(latest.status=="COMPLETE") {
                status(asset,"FAILED",error="Downloaded file is missing. Retry to download it again.")
                continue
            }
            val video=asset.type!="image"
            if(asset.url in splitVideos&&asset.url !in youtubeVideos) {
                status(asset,"UNSUPPORTED",error="Offline downloads of separate audio/video tracks are not supported. The saved text remains available.")
                continue
            }
            if(video&&(playlistUrl(asset.url)||VideoFormats.adaptive(mimeTypes[asset.url]))) {
                status(asset,"UNSUPPORTED",error="Offline video playlists are not supported. The saved text is available.")
                continue
            }
            if(video&&repo.prefs.videoDownloadWifiOnly&&!unmetered()) {
                status(asset,"WAITING_WIFI",error="Waiting for an unmetered connection")
                retry=true;continue
            }
            val free=(StatFs(repo.context.filesDir.path).availableBytes-FREE_RESERVE).coerceAtLeast(0)
            val allowance=if(video)remainingVideoAllowance()else Long.MAX_VALUE
            val limit=minOf(if(video)MAX_VIDEO else MAX_IMAGE,free,allowance)
            if(limit<=0) {
                status(asset,"FAILED",error=if(free<=0)"Not enough free storage. Free space, then retry."else "Video download allowance is full. Increase it or remove downloaded videos, then retry.")
                continue
            }
            val network=repo.gateway.currentPolicy()
            if(network.tor&&(!network.ready||network.socksPort !in 1..65535)) {
                status(asset,"QUEUED",error="Waiting for Tor")
                retry=true;continue
            }
            if(repo.context.getSystemService(ConnectivityManager::class.java).activeNetwork==null) {
                status(asset,"QUEUED",error="Waiting for a connection")
                retry=true;continue
            }
            val directory=File(repo.context.cacheDir,"content").apply {mkdirs()}
            val temp=File(directory,"saved-${UUID.randomUUID()}.part")
            // Ordinary cache cleanup must not remove a live transfer's temp file.
            repo.lease(temp.name,true)
            try {
                if(!status(asset,"DOWNLOADING"))continue
                repo.awaitReady()
                val youtubeId=youtubeVideos[asset.url] ?: YouTubeUrls.videoId(asset.url)
                val stream=youtubeId?.let {repo.youtube.resolve(it,muxedOnly=true)}
                if(stream!=null&&(stream.media.audioUrl!=null||VideoFormats.adaptive(stream.media.mimeType))) {
                    status(asset,"UNSUPPORTED",error="This video is available as separate streaming tracks only. The saved text remains available.")
                    continue
                }
                repo.gateway.download(stream?.media?.url ?: asset.url,stream?.sourceUrl ?: asset.sourceUrl,temp,maxBytes=limit)
                val invalid=SavedAssetValidation.problem(temp,asset.type)
                if(invalid!=null) {
                    status(asset,"UNSUPPORTED",error=invalid)
                    continue
                }
                currentCoroutineContext().ensureActive()
                val committed=repo.library.commitAsset(owner,token,asset.url,temp)
                if(committed!=null)status(asset,"COMPLETE",committed.length())
                else if(!repo.library.validToken(owner,token))return@withContext true
            }catch(e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                status(asset,"QUEUED",error="The source timed out. Waiting to retry the download.")
                retry=true
            }catch(e: CancellationException) {
                withContext(NonCancellable) {status(asset,"QUEUED",error="Download interrupted. It can be resumed.")}
                throw e
            }catch(e: Exception) {
                val message=e.message.orEmpty()
                when {
                    message.contains("playlists are not",true)->status(asset,"UNSUPPORTED",error="Offline video playlists are not supported. The saved text is available.")
                    message.contains("size limit",true)->status(asset,"FAILED",error="This file exceeds the available download allowance or file size limit.")
                    permanentHttp(message)->status(asset,"FAILED",error=message.take(200))
                    e is IOException->{status(asset,"QUEUED",error=message.ifBlank {"Connection interrupted"}.take(200));retry=true}
                    else->status(asset,"FAILED",error=message.ifBlank {"This media could not be saved"}.take(200))
                }
            }finally {
                temp.delete();repo.lease(temp.name,false)
            }
        }
        repo.library.notifyChanged()
        !retry
    }

    /** Copy a viewed image before considering connectivity. Keep its old path
     * intact for a decoder that already holds it; ordinary cleanup reclaims it.
     * Release the cache lock before commitAsset takes archive -> media locks.
     */
    private suspend fun promoteCachedImage(asset: SavedAssetRow): File? {
        val temp=File(repo.context.cacheDir,"content/saved-${UUID.randomUUID()}.part")
        repo.lease(temp.name,true)
        return try {
            val copied=repo.mediaMutex.withLock {
                val cached=repo.dao.media(asset.assetKey) ?: return@withLock false
                val source=File(cached.path)
                if(cached.saved||!source.isFile||source.length()>MAX_IMAGE||
                    StatFs(repo.context.filesDir.path).availableBytes-source.length()<FREE_RESERVE||
                    SavedAssetValidation.problem(source,"image")!=null)return@withLock false
                temp.parentFile?.mkdirs();source.copyTo(temp,true);true
            }
            if(!copied)null else {
                currentCoroutineContext().ensureActive()
                repo.library.commitAsset(asset.ownerKey,asset.token,asset.url,temp)?.also {repo.scheduleCleanup()}
            }
        }catch(_: IOException) {
            // The ordinary entry may be gone or unreadable; normal download
            // handling below still reports availability and storage errors.
            null
        }finally {temp.delete();repo.lease(temp.name,false)}
    }

    private suspend fun status(asset: SavedAssetRow,status: String,bytes: Long=0,error: String?=null): Boolean=repo.archiveStore.mutations.withLock {
        if(!repo.library.validToken(asset.ownerKey,asset.token))return@withLock false
        val latest=repo.dao.asset(asset.ownerKey,asset.assetKey) ?: return@withLock false
        if(latest.token!=asset.token||latest.status=="REMOVED")return@withLock false
        val changed=repo.dao.updateSavedAsset(asset.ownerKey,asset.assetKey,asset.token,status,bytes,error,System.currentTimeMillis())>0
        if(changed)repo.library.notifyChanged()
        changed
    }
    private suspend fun remainingVideoAllowance(): Long {
        val limit=repo.prefs.videoDownloadLimitMb.toLong()*MB
        if(limit==0L)return Long.MAX_VALUE
        var used=0L
        for(url in repo.dao.allSavedAssets().filter {it.type!="image"&&it.status!="REMOVED"}.map {it.url}.distinct()) {
            used+=repo.library.assetFile(url)?.length() ?: 0L
        }
        return (limit-used).coerceAtLeast(0)
    }
    private fun unmetered(): Boolean {
        val network=repo.context.getSystemService(ConnectivityManager::class.java)
        return network.activeNetwork!=null&&!network.isActiveNetworkMetered
    }
    private fun playlistUrl(url: String): Boolean {
        val path=url.toHttpUrlOrNull()?.encodedPath?.lowercase(Locale.ROOT).orEmpty()
        return path.endsWith(".m3u8")||path.endsWith(".m3u")||path.endsWith(".mpd")
    }
    private fun permanentHttp(message: String): Boolean {
        val code=Regex("Download returned (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return code in 400..499&&code !in setOf(408,425,429)
    }
    private companion object {
        const val MB=1024L*1024
        const val FREE_RESERVE=256L*MB
        const val MAX_VIDEO=2048L*MB
        const val MAX_IMAGE=16L*MB
        val terminal=setOf("FAILED","UNSUPPORTED","REMOVED")
    }
}

/** A successful HTTP response is not proof of an image or playable media. */
internal object SavedAssetValidation {
    /** Null means a recognized media container; it does not promise a codec is supported. */
    fun problem(file: File,type: String): String? {
        if(!file.isFile||file.length()==0L)return "The source returned empty media."
        if(type=="image") {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(file.path,bounds)
            return if(bounds.outWidth>0&&bounds.outHeight>0)null else "The source did not return a supported image."
        }
        val bytes=file.inputStream().use {input->val buffer=ByteArray(2048);val count=input.read(buffer);if(count<0)ByteArray(0)else buffer.copyOf(count)}
        val text=String(bytes,Charsets.UTF_8).trimStart('\uFEFF',' ','\t','\r','\n')
        if(text.startsWith("#EXTM3U",true)||Regex("<(?:[A-Za-z_][\\w.-]*:)?MPD(?:\\s|>)",RegexOption.IGNORE_CASE).containsMatchIn(text))
            return "Offline video playlists are not supported. The saved text is available."
        fun starts(vararg values: Int)=bytes.size>=values.size&&values.indices.all {bytes[it].toInt() and 255==values[it]}
        fun ascii(offset: Int,value: String)=bytes.size>=offset+value.length&&value.indices.all {bytes[offset+it].toInt() and 255==value[it].code}
        val iso=bytes.size>=12&&listOf("ftyp","moov","mdat","wide","free").any {ascii(4,it)}
        val webm=starts(0x1a,0x45,0xdf,0xa3)
        val avi=ascii(0,"RIFF")&&ascii(8,"AVI ")
        val ogg=ascii(0,"OggS")
        val flash=ascii(0,"FLV")
        val mpeg=starts(0,0,1,0xba)||starts(0,0,1,0xb3)
        val transport=bytes.size>376&&bytes[0].toInt() and 255==0x47&&bytes[188].toInt() and 255==0x47&&bytes[376].toInt() and 255==0x47
        val asf=starts(0x30,0x26,0xb2,0x75,0x8e,0x66,0xcf,0x11,0xa6,0xd9,0,0xaa,0,0x62,0xce,0x6c)
        return if(iso||webm||avi||ogg||flash||mpeg||transport||asf)null else "The source did not return a supported video file."
    }
}
