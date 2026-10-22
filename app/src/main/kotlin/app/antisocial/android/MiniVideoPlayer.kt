/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/** Small native controls; no web chrome, playlist, settings panel or autoplay. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun MiniVideoPlayer(player: Player,rendered: Boolean,expanded: Boolean,error: String?,onRetry: ()->Unit,onExpand: ()->Unit) {
    var playing by remember(player){mutableStateOf(player.isPlaying)}
    var buffering by remember(player){mutableStateOf(player.playbackState==Player.STATE_BUFFERING)}
    var ended by remember(player){mutableStateOf(player.playbackState==Player.STATE_ENDED)}
    var muted by remember(player){mutableStateOf(player.volume==0f)}
    var duration by remember(player){mutableLongStateOf(player.duration.coerceAtLeast(0))}
    var position by remember(player){mutableLongStateOf(player.currentPosition.coerceAtLeast(0))}
    var seekable by remember(player){mutableStateOf(player.isCurrentMediaItemSeekable)}
    var live by remember(player){mutableStateOf(player.isCurrentMediaItemLive)}
    var aspect by remember(player){mutableFloatStateOf(16f/9f)}
    var drag by remember(player){mutableStateOf<Float?>(null)}
    DisposableEffect(player) {
        val listener=object: Player.Listener {
            override fun onEvents(player: Player,events: Player.Events) {
                playing=player.isPlaying;buffering=player.playbackState==Player.STATE_BUFFERING;ended=player.playbackState==Player.STATE_ENDED
                muted=player.volume==0f;duration=player.duration.takeIf {it!=C.TIME_UNSET&&it>0} ?: 0L
                position=player.currentPosition.coerceAtLeast(0);seekable=player.isCurrentMediaItemSeekable;live=player.isCurrentMediaItemLive
                val size=player.videoSize
                if(size.width>0&&size.height>0)aspect=(size.width*size.pixelWidthHeightRatio/size.height).coerceIn(.56f,2.4f)
            }
        }
        player.addListener(listener)
        onDispose {player.removeListener(listener)}
    }
    LaunchedEffect(player) {
        while(isActive){position=player.currentPosition.coerceAtLeast(0);delay(if(player.isPlaying)250 else 1000)}
    }
    Column((if(expanded)Modifier.fillMaxSize()else Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))).background(Color.Black)) {
        val frame=if(expanded)Modifier.fillMaxWidth().weight(1f)else Modifier.fillMaxWidth().heightIn(max=440.dp).aspectRatio(aspect)
        Box(frame,contentAlignment=Alignment.Center) {
            AndroidView(factory={PlayerView(it).apply {setControllerAnimationEnabled(false);useController=false;setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);this.player=player}},update={it.player=player},onRelease={it.player=null},modifier=Modifier.fillMaxSize().testTag("Video player").semantics {stateDescription=if(rendered)"Video ready"else "Loading video"})
            if(buffering&&error==null)CircularProgressIndicator(Modifier.size(28.dp),color=Color.White,strokeWidth=2.dp)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
            if(duration>0&&seekable)Slider(value=drag ?: (position.toFloat()/duration).coerceIn(0f,1f),onValueChange={drag=it},onValueChangeFinished={drag?.let {player.seekTo((duration*it).toLong());position=player.currentPosition};drag=null},modifier=Modifier.fillMaxWidth().height(28.dp).testTag("Video seek"),colors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=Color.White,inactiveTrackColor=Color.White.copy(alpha=.25f)))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={if(playing)player.pause()else {if(ended)player.seekTo(0);player.play()}},modifier=Modifier.testTag("Video playback toggle")) {Icon(if(playing)Icons.Rounded.Pause else if(ended)Icons.Rounded.Replay else Icons.Rounded.PlayArrow,if(playing)"Pause video"else if(ended)"Replay video"else "Play video",tint=Color.White)}
                Text(if(live)"Live"else "${videoTime(position)} / ${if(duration>0)videoTime(duration)else "–:––"}",color=Color.White.copy(alpha=.85f),style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick={player.volume=if(muted)1f else 0f},modifier=Modifier.testTag("Video sound toggle")) {Icon(if(muted)Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,if(muted)"Unmute video"else "Mute video",tint=Color.White)}
                IconButton(onClick=onExpand) {Icon(if(expanded)Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,if(expanded)"Collapse video"else "Expand video",tint=Color.White)}
            }
            if(error!=null)Row(Modifier.fillMaxWidth().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(error,Modifier.weight(1f),color=Color.White.copy(alpha=.8f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=onRetry){Text("Retry video",color=Color.White)}
            }
        }
    }
}

private fun videoTime(position: Long): String {
    val seconds=(position/1000).coerceAtLeast(0)
    return if(seconds>=3600)String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60)else String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60)
}
