/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor.
 * SponsorBlock segment data: contributors, CC BY-NC-SA-4.0. */
package app.antisocial.android

import androidx.compose.runtime.*
import androidx.media3.common.Player
import app.antisocial.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Small, expiring ordinary cache. Cached public annotations may be reused
 * offline; any new request follows NetworkGateway's selected Tor policy. */
class SponsorBlockStore(private val repo: Repository,private val clock: ()->Long=System::currentTimeMillis) {
    private val mutableSettings=MutableStateFlow(readSettings())
    val settings=mutableSettings.asStateFlow()
    @Suppress("unused") private val stopObserving=repo.prefs.observeSponsorBlock {mutableSettings.value=readSettings()}
    private val directory=File(repo.context.cacheDir,"sponsorblock")
    private val gate=Mutex()
    private val memory=linkedMapOf<String,Entry>()
    internal var retrieve: suspend (String)->List<SponsorSegment> = SponsorBlockClient(repo.gateway)::read
    private data class Entry(val checked: Long,val expires: Long,val segments: List<SponsorSegment>)
    private fun readSettings()=SponsorSettings(repo.prefs.sponsorBlockEnabled,repo.prefs.sponsorBlockCategories)
    fun enabled(value: Boolean){repo.prefs.sponsorBlockEnabled=value;mutableSettings.value=readSettings()}
    fun category(category: SponsorCategory,enabled: Boolean) {
        repo.prefs.sponsorBlockCategories=if(enabled)repo.prefs.sponsorBlockCategories+category.id else repo.prefs.sponsorBlockCategories-category.id
        mutableSettings.value=readSettings()
    }
    suspend fun read(videoId: String,networkAllowed: Boolean=true): List<SponsorSegment> = withContext(Dispatchers.IO) {
        if(!settings.value.enabled||settings.value.categories.isEmpty()||!SponsorBlock.validVideoId(videoId))return@withContext emptyList()
        gate.withLock {
            val now=clock();prune(now)
            val key=SponsorBlock.hash(videoId)
            memory[key]?.takeIf {now in it.checked until it.expires}?.let {return@withLock it.segments}
            val file=File(directory,"$key.json")
            val cached=runCatching {
                if(!file.isFile||file.length()>SponsorBlock.MAX_BYTES||file.lastModified()>now||now-file.lastModified()>DAY)return@runCatching null
                SponsorBlock.parse(file.readText(),videoId)
            }.getOrNull()
            if(cached!=null){memory[key]=Entry(file.lastModified(),file.lastModified()+DAY,cached);return@withLock cached}
            if(!networkAllowed)return@withLock emptyList()
            repo.awaitReady();repo.applyNetworkSettings(startTransport=false)
            if(!settings.value.enabled)return@withLock emptyList()
            try {
                val segments=retrieve(videoId)
                memory[key]=Entry(now,now+DAY,segments)
                // Keep the same strictly validated wire format on disk, with
                // attribution carried by the app's notice and settings page.
                val encoded=repo.gson.toJson(listOf(mapOf("videoID" to videoId,"segments" to segments.map {mapOf("UUID" to it.id,"category" to it.category,"actionType" to it.action,"segment" to listOf(it.startMs/1000.0,it.endMs/1000.0),"videoDuration" to it.videoDurationMs/1000.0)})))
                if(encoded.toByteArray().size<=SponsorBlock.MAX_BYTES)runCatching {
                    directory.mkdirs();val temporary=File(directory,"$key.tmp");temporary.writeText(encoded)
                    if(!temporary.renameTo(file)){temporary.copyTo(file,overwrite=true);temporary.delete()}
                    file.setLastModified(now);prune(now)
                }
                segments
            }catch(e: CancellationException){throw e}catch(_: Exception) {
                // An outage never prevents video playback or causes a request
                // per progress tick. Failed lookups are held only in memory.
                memory[key]=Entry(now,now+5*60_000L,emptyList());emptyList()
            }
        }
    }
    private fun prune(now: Long) {
        memory.entries.removeIf {now !in it.value.checked until it.value.expires}
        while(memory.size>MAX_ENTRIES)memory.remove(memory.keys.first())
        val files=directory.listFiles().orEmpty().sortedByDescending {it.lastModified()}
        var bytes=0L
        files.forEachIndexed {index,file->
            if(file.extension!="json"||index>=MAX_ENTRIES||file.lastModified()>now||now-file.lastModified()>DAY||bytes+file.length()>MAX_CACHE_BYTES)file.delete()
            else bytes+=file.length()
        }
    }
    suspend fun cleanup(now: Long=clock())=withContext(Dispatchers.IO){gate.withLock {prune(now)}}
    suspend fun clear()=withContext(Dispatchers.IO){gate.withLock {memory.clear();directory.deleteRecursively();Unit}}
    internal suspend fun forget(videoId: String)=withContext(Dispatchers.IO){gate.withLock {
        val key=SponsorBlock.hash(videoId);memory.remove(key);File(directory,"$key.json").delete();File(directory,"$key.tmp").delete();Unit
    }}
    companion object {private const val DAY=24*60*60_000L;private const val MAX_ENTRIES=96;private const val MAX_CACHE_BYTES=2L*1024*1024}
}

/** One observer per actual player, outside inline/fullscreen presentation.
 * Waiting for a real, seekable timeline prevents stale/trimmed archive copies
 * from being skipped according to another video's timestamps. */
@Composable internal fun SponsorBlockPlayback(player: Player,sourceUrl: String,store: SponsorBlockStore,enabled: Boolean=true,networkAllowed: Boolean=true) {
    val settings by store.settings.collectAsState()
    val currentSettings by rememberUpdatedState(settings)
    val videoId=remember(sourceUrl){SponsorBlock.videoId(sourceUrl)}
    LaunchedEffect(player,videoId,enabled,settings.enabled,networkAllowed) {
        if(!enabled||!settings.enabled||videoId==null)return@LaunchedEffect
        // A suspending cache/network read can resume through Compose's apply
        // interceptor. Enforce Media3's application thread at every access,
        // including the initial wait and each progress tick after a delay.
        while(isActive&&!withContext(Dispatchers.Main.immediate){player.playWhenReady})delay(200)
        val segments=store.read(videoId,networkAllowed)
        if(segments.isEmpty())return@LaunchedEffect
        val session=SponsorSkipSession()
        while(isActive) {
            withContext(Dispatchers.Main.immediate) {
                if(player.playWhenReady&&player.playbackState==Player.STATE_READY&&player.isCurrentMediaItemSeekable&&!player.isCurrentMediaItemLive) {
                    session.next(player.currentPosition,player.duration,segments,currentSettings)?.let {skip->
                        session.applied(skip);player.seekTo(skip.toMs)
                    }
                }
            }
            delay(150)
        }
    }
}
