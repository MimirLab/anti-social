/* anti social, GPL-3.0-or-later. One useful destination, with other editions tucked away. */
package app.antisocial.android

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antisocial.core.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable internal fun EventAnnouncementLinks(event:LocalEvent,repo:Repository,onRead:(String)->Unit) {
    val checks by repo.localLinks.state.collectAsState()
    val scope=rememberCoroutineScope()
    var expanded by remember(event.id){mutableStateOf(false)}
    LaunchedEffect(LocalAnnouncementReading.key(event),event.urls,event.registrationUrl){repo.localLinks.inspect(event)}
    val links=LocalDiscoveryPresentation.eventLinks(event,repo.cleaner::clean)
    val readable=links.mapNotNull {link->repo.localLinks.check(link.url,event,checks)?.takeIf {it.state==LocalLinkState.AVAILABLE}?.let {link to it}}
        .distinctBy {it.second.destination}
    val checking=links.any {repo.localLinks.check(it.url,event,checks)?.state==LocalLinkState.CHECKING}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.testTag("Event announcements")) {
        readable.take(if(expanded)12 else 1).forEach {(link,result)->
            OutlinedButton(onClick={onRead(result.destination)},modifier=Modifier.fillMaxWidth().testTag("Announcement ${link.url}")) {
                Icon(if(link.registration)Icons.Rounded.EventAvailable else Icons.Rounded.Article,null,Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(result.title.ifBlank {IdentityDisplay.domain(result.destination)},maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(when {result.recovered->"Archived announcement"+(result.archivedAt?.let {" · "+Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()} ?: "");link.registration->"Registration & details";result.title.isNotBlank()->IdentityDisplay.domain(result.destination);else->"Event announcement"},style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ChevronRight,null,Modifier.size(18.dp))
            }
        }
        if(readable.size>1)TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Fewer announcements"else "${readable.size-1} more announcement${if(readable.size==2)""else "s"}");Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)}
        if(checking)Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp);Spacer(Modifier.width(8.dp));Text("Checking announcement links…",style=MaterialTheme.typography.bodySmall)}
        else if(links.isNotEmpty()&&readable.isEmpty())Text("No readable announcement link found yet.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!checking&&links.any {repo.localLinks.check(it.url,event,checks)?.state!=LocalLinkState.AVAILABLE})TextButton(onClick={scope.launch {repo.localLinks.inspect(event,force=true)}},modifier=Modifier.testTag("Retry announcement links")){Text("Check links again")}
    }
}
