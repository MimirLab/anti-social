/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.antisocial.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A channel shares only the explicitly reviewed source selection, never its
 * keyword rules, reading progress, local collections or private preferences. */
@Composable internal fun ChannelSourcePack(repo: Repository,follows: List<Follow>,channel: PersonalChannel,onDismiss: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var pending by remember {mutableStateOf<ByteArray?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    fun write(uri: android.net.Uri?) {
        val bytes=pending;pending=null
        if(uri==null||bytes==null){busy=false;return}
        scope.launch {
            try {
                withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openOutputStream(uri,"wt")){"This file could not be opened."}.use {it.write(bytes)}}
                onDismiss()
            }catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "This source pack could not be saved."}finally {busy=false}
        }
    }
    val native=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"),::write)
    val opml=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml"),::write)
    val initial=remember(channel,follows){PersonalChannels.accounts(channel,follows).map {it.id}.toSet()}
    SourcePackSheet(follows,initialSourceIds=initial,initialTitle="${channel.name} sources",onDismiss=onDismiss,onExport={ids,title,asOpml->
        if(!busy)scope.launch {
            busy=true;error=null
            try {
                pending=if(asOpml)repo.portability.exportOpml(ids)else repo.portability.exportPack(ids,title)
                if(asOpml)opml.launch("antisocial-feeds.opml")else native.launch("antisocial-sources.json")
            }catch(e: CancellationException){throw e}catch(e: Exception){busy=false;pending=null;error=e.message ?: "These sources could not be exported."}
        }
    })
    error?.let {message->AlertDialog(onDismissRequest={error=null},title={Text("Source pack could not be saved")},text={Text(message)},confirmButton={TextButton(onClick={error=null}){Text("Done")}})}
}
