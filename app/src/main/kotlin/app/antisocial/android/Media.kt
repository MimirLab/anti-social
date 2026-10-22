package app.antisocial.android

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.*
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.antisocial.core.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

private data class VideoLocation(val url: String,val file: File?)
private data class PreparedVideo(val policy: NetworkPolicy,val client: okhttp3.OkHttpClient,val mime: String?,val media: Media,val sourceUrl: String,val attempt: Int,val notice: String?=null)

/** Read the current allowance on every write, including during long playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class VideoCacheEvictor(private val prefs: Preferences): CacheEvictor {
    private val spans=java.util.TreeSet<CacheSpan>(compareBy<CacheSpan> {it.lastTouchTimestamp}.thenBy {it.key}.thenBy {it.position})
    private var size=0L
    override fun requiresCacheSpanTouches()=true
    override fun onCacheInitialized()=Unit
    override fun onStartFile(cache: Cache,key: String,position: Long,length: Long) {if(length>0)evict(cache,length)}
    override fun onSpanAdded(cache: Cache,span: CacheSpan) {if(spans.add(span))size+=span.length;evict(cache,0)}
    override fun onSpanRemoved(cache: Cache,span: CacheSpan) {if(spans.remove(span))size-=span.length}
    override fun onSpanTouched(cache: Cache,oldSpan: CacheSpan,newSpan: CacheSpan) {onSpanRemoved(cache,oldSpan);onSpanAdded(cache,newSpan)}
    private fun evict(cache: Cache,incoming: Long) {
        val budget=prefs.videoCacheMb.toLong()*1024*1024
        while(spans.isNotEmpty()&&size+incoming>budget)cache.removeSpan(spans.first())
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoCache(context: Context,prefs: Preferences) {
    private val preferences=prefs
    private val database=StandaloneDatabaseProvider(context)
    val cache=SimpleCache(File(context.cacheDir,"video"),VideoCacheEvictor(prefs),database)
    private val active=java.util.concurrent.ConcurrentHashMap<String,Int>()
    fun bytes()=cache.cacheSpace
    fun release(){cache.release();database.close()}
    fun active(url: String,enabled: Boolean){if(enabled)active.merge(url,1){old,new->old+new}else active.computeIfPresent(url){_,count->(count-1).takeIf {it>0}}}
    fun cleanup(now: Long,all: Boolean=false) {
        cache.keys.toList().filter {!active.containsKey(it)}.forEach {key->cache.getCachedSpans(key).toList().forEach {span->if(all||now-span.lastTouchTimestamp>preferences.videoCacheDays*CachePolicy.DAY)cache.removeSpan(span)}}
        val budget=preferences.videoCacheMb.toLong()*1024*1024
        cache.keys.toList().filter {!active.containsKey(it)}.flatMap {cache.getCachedSpans(it).toList()}.sortedBy {it.lastTouchTimestamp}.forEach {span->if(cache.cacheSpace>budget)cache.removeSpan(span)}
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun VideoCard(media: Media,post: Post,repo: Repository,localOnly: Boolean=false,localFile: File?=null) {
    var requested by remember(media.url){mutableStateOf(false)}
    var error by remember(media.url){mutableStateOf<String?>(null)}
    var attempt by remember(media.url){mutableIntStateOf(0)}
    var recoveries by remember(media.url){mutableIntStateOf(0)}
    var rendered by remember(media.url){mutableStateOf(false)}
    var rejected by remember(media.url){mutableStateOf<Set<String>>(emptySet())}
    var resumePosition by remember(media.url){mutableLongStateOf(0)}
    val youtubeId=remember(media.url,post.canonicalUrl){YouTubeUrls.videoId(media.url) ?: post.takeIf {it.platform==Platform.YOUTUBE}?.canonicalUrl?.let(YouTubeUrls::videoId)}
    var expanded by remember(media.url){mutableStateOf(false)}
    val revision by repo.library.revision.collectAsState()
    val resolved by produceState<VideoLocation?>(null,media.url,localFile,revision,localOnly){
        value=VideoLocation(media.url,localFile?.takeIf {it.isFile} ?: repo.savedMedia(media.url))
    }
    val location=resolved?.takeIf {it.url==media.url}
    val local=location?.file?.takeIf {it.isFile}
    val lifecycle=LocalLifecycleOwner.current
    val tor by repo.tor.state.collectAsState()
    val contentTorReady=tor.allTraffic&&tor.ready
    val prepared by produceState<PreparedVideo?>(null,requested,contentTorReady,tor.allTraffic,attempt,location,localOnly) {
        value=null
        if(requested&&location!=null&&local==null&&!localOnly)try {
            repo.awaitReady()
            val policy=repo.gateway.currentPolicy()
            val playback=if(youtubeId!=null&&(YouTubeUrls.videoId(media.url)!=null||rejected.isNotEmpty()))repo.youtube.resolve(youtubeId,rejected,archiveOnly=recoveries>=3)else null
            val selected=playback?.media ?: media
            val source=playback?.sourceUrl ?: post.contentSourceUrl
            val mime=VideoFormats.resolve(selected,source,repo.gateway)
            val client=withContext(Dispatchers.IO){repo.gateway.client()}
            if(policy==repo.gateway.currentPolicy()){error=null;value=PreparedVideo(policy,client,mime,selected,source,attempt,playback?.notice)}
        }catch(e: CancellationException){throw e}catch(e: Exception){error=e.message}
    }
    val ready=prepared?.takeIf {it.policy==repo.gateway.currentPolicy()&&it.attempt==attempt}
    val client=ready?.client
    val cacheEnabled=repo.prefs.videoCacheMb>0
    val player=remember(media.url,post.contentSourceUrl,requested,contentTorReady,tor.allTraffic,attempt,local,client,ready?.mime,location!=null,localOnly,cacheEnabled) {
        if(!requested||location==null||(localOnly&&local==null)||(local==null&&client==null))null else runCatching {
            error=null;rendered=false
            val data=if(local!=null)FileDataSource.Factory()else videoNetworkDataSource(client!!,ready!!.sourceUrl,if(cacheEnabled)repo.videoCache.cache else null)
            ExoPlayer.Builder(repo.context).setMediaSourceFactory(DefaultMediaSourceFactory(data)).build().apply {
                addListener(object: Player.Listener {
                    override fun onPlayerError(e: PlaybackException){
                        if(youtubeId!=null&&local==null&&!localOnly&&recoveries<4) {
                            resumePosition=currentPosition.coerceAtLeast(0)
                            rejected=rejected+listOfNotNull(ready?.media?.url,ready?.media?.audioUrl)
                            error=null;recoveries++;attempt++;return
                        }
                        error=when(e.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND->"This source's video is unavailable."
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,PlaybackException.ERROR_CODE_DECODER_INIT_FAILED->"This device cannot play this video format."
                        else->"This video could not be played."
                    }}
                    override fun onRenderedFirstFrame(){rendered=true}
                })
                val item=MediaItem.Builder().setUri(local?.let {Uri.fromFile(it)} ?: Uri.parse(ready!!.media.url))
                val mime=if(local!=null)VideoFormats.mime(media)else ready?.mime
                // Unknown containers use the progressive extractors. DASH always
                // goes through our parser, including device-only clock timing.
                item.setMimeType(mime ?: "video/mp4")
                val selected=if(local!=null)media else ready!!.media
                val audioUrl=selected.audioUrl
                if(local==null&&audioUrl!=null)setMediaSource(gatewayPairedMediaSource(data,item.build(),audioUrl,selected.audioMimeType))
                else if(mime==VideoFormats.DASH)setMediaSource(gatewayDashMediaSource(data,item.build()))else setMediaItem(item.build())
                prepare();if(resumePosition>0)seekTo(resumePosition);playWhenReady=true
            }
        }.onFailure {error=it.message}.getOrNull()
    }
    val activeUrls=remember(ready,media.url){listOfNotNull(ready?.media?.url ?: media.url,ready?.media?.audioUrl)}
    DisposableEffect(player,activeUrls){if(player!=null)activeUrls.forEach {repo.videoCache.active(it,true)};onDispose {player?.release();if(player!=null)activeUrls.forEach {repo.videoCache.active(it,false)};repo.scheduleCleanup()}}
    DisposableEffect(player,lifecycle) {val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP)player?.pause()};lifecycle.lifecycle.addObserver(observer);onDispose {lifecycle.lifecycle.removeObserver(observer)}}
    val retry={error=null;rejected=emptySet();recoveries=0;attempt++;requested=true}
    if(player!=null) {
        SponsorBlockPlayback(player,youtubeId?.let(YouTubeUrls::canonical) ?: post.canonicalUrl,repo.sponsorBlock,networkAllowed=!localOnly)
        if(expanded)Dialog(onDismissRequest={expanded=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().testTag("Expanded video")) {MiniVideoPlayer(player,rendered,true,error,retry){expanded=false}}
        }else MiniVideoPlayer(player,rendered,false,error,retry){expanded=true}
    }
    else if(localOnly&&location!=null&&local==null)Text("Video is not downloaded",Modifier.fillMaxWidth().padding(16.dp).testTag("Saved video unavailable"),style=MaterialTheme.typography.bodyMedium)
    else Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black).aspectRatio(if(post.platform in setOf(Platform.TIKTOK,Platform.SNAPCHAT))1f else 16f/9f),contentAlignment=Alignment.Center) {
        media.preview?.takeIf {!localOnly}?.let {RemoteImage(it,post.contentSourceUrl,repo,Modifier.fillMaxSize(),media.description,maxEdge=512)}
        FilledTonalButton(onClick=retry,enabled=location!=null&&(!requested||error!=null),modifier=Modifier.padding(12.dp)){Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(8.dp));Text(if(error!=null)"Retry video"else if(requested)"Preparing video…"else "Play video")}
    }
    ready?.notice?.let {Text(it,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    if(error!=null&&player==null)Text(error.orEmpty(),Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
}
