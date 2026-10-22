/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Short-lived stream addresses never become outgoing links or permanent
 * saved records. Failure recovery is demand-driven, never a background crawl. */
class YouTubeStore(private val repo: Repository,private val clock: ()->Long=System::currentTimeMillis) {
    val recovery=YouTubeRecovery(repo.gateway)
    private data class Entry(val at: Long,val result: YouTubePlayback)
    private val cache=linkedMapOf<String,Entry>()
    private val gate=Mutex()
    internal var live: suspend (String,Set<String>,Boolean)->YouTubePlayback = {id,rejected,muxed->repo.pool.youtubePlayback(id,repo.prefs.originalFallback,rejected,muxed)}
    internal var archived: suspend (String,Set<String>,Boolean)->RecoveredYouTubeVideo? = {id,rejected,muxed->recovery.recover(id,rejected,muxed)}
    suspend fun resolve(videoId: String,rejected: Set<String> = emptySet(),muxedOnly: Boolean=false,archiveOnly: Boolean=false): YouTubePlayback=gate.withLock {
        require(YouTubeUrls.validId(videoId)){"Invalid YouTube video"}
        repo.awaitReady()
        repo.gateway.originalFallback=repo.prefs.originalFallback
        val policy=repo.gateway.currentPolicy()
        repo.gateway.prepare()
        val key="$videoId|$policy|${repo.prefs.originalFallback}|$muxedOnly"
        val now=clock()
        cache.entries.removeIf {now<it.value.at||now-it.value.at>10*60_000L}
        cache[key]?.result?.takeIf {!archiveOnly&&it.media.url !in rejected&&it.media.audioUrl !in rejected}?.let {return@withLock it}
        var failure: Exception?=null
        if(!archiveOnly)try {
            val result=live(videoId,rejected,muxedOnly)
            require(result.videoId==videoId&&result.media.url !in rejected&&result.media.audioUrl !in rejected){"The source did not provide this YouTube video"}
            require(!muxedOnly||result.media.audioUrl==null&&!VideoFormats.adaptive(result.media.mimeType)){"This video has no downloadable combined stream"}
            require(repo.gateway.currentPolicy()==policy){"Network mode changed during video preparation"}
            cache.remove(key);cache[key]=Entry(clock(),result)
            while(cache.size>64)cache.remove(cache.keys.first())
            return@withLock result
        }catch(e: CancellationException){throw e}catch(e: Exception){failure=e}
        if(repo.prefs.archiveRecovery)archived(videoId,rejected,muxedOnly)?.let {copy->
            require(copy.videoId==videoId&&copy.media.url !in rejected&&copy.media.audioUrl !in rejected){"The archive did not provide this YouTube video"}
            require(!muxedOnly||copy.media.audioUrl==null&&!VideoFormats.adaptive(copy.media.mimeType)){"This archive has no downloadable combined stream"}
            require(repo.gateway.currentPolicy()==policy){"Network mode changed during video recovery"}
            return@withLock YouTubePlayback(videoId,copy.media,copy.sourceUrl,Source(Platform.YOUTUBE,"youtube-archive",copy.sourceUrl,role=SourceRole.CACHE),recovered=true,notice="Recovered archive copy")
        }
        throw failure ?: SourceFailure(HealthStatus.UNAVAILABLE,"No playable public or archived copy of this YouTube video was found")
    }
    suspend fun recoverPage(target: Target): Page {
        require(target.platform==Platform.YOUTUBE&&target.kind in setOf(TargetKind.POST,TargetKind.THREAD)){"This is not a YouTube video"}
        val id=YouTubeUrls.videoId(target.canonicalUrl) ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"This is not a YouTube video")
        require(repo.prefs.archiveRecovery){"Archive recovery is turned off in Privacy & connection."}
        repo.awaitReady();repo.gateway.originalFallback=repo.prefs.originalFallback;repo.gateway.prepare()
        val policy=repo.gateway.currentPolicy()
        val result=archived(id,emptySet(),false) ?: throw SourceFailure(HealthStatus.UNAVAILABLE,"No playable archived copy of this YouTube video was found")
        require(result.videoId==id){"The archive did not provide this YouTube video"}
        require(repo.gateway.currentPolicy()==policy){"Network mode changed during video recovery"}
        return result.asPage()
    }
    suspend fun clear()=gate.withLock {cache.clear();recovery.clear()}
}
