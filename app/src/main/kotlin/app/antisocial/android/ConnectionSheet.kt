/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.antisocial.core.SearchProvider

/** A quick route control over the reader; the selected search provider keeps its
 * existing policy. In particular, turning content Tor off never sends Bing/DDG direct. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConnectionSheet(
    state: TorState,
    contentTor: Boolean,
    provider: SearchProvider,
    originalFallback: Boolean,
    onContentTor: (Boolean)->Unit,
    onDismiss: ()->Unit
) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("Connection sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Connection",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"Close connection panel")}
            }
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(if(contentTor)Icons.Rounded.Shield else Icons.Rounded.Public,null,tint=MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Content and media",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if(contentTor)"Tor"else "Direct",style=MaterialTheme.typography.titleMedium)
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Search,null,tint=MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Search fallback",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if(!originalFallback&&provider.torOnly)"${provider.label} · disabled"else "${provider.label} · ${if(provider.torOnly||contentTor)"Tor"else "Direct"}",style=MaterialTheme.typography.titleMedium)
                            if(!originalFallback&&provider.torOnly)Text("Original-site fallback is off. Native social search still follows the content connection.",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().testTag("Content Tor switch").toggleable(value=contentTor,role=Role.Switch,onValueChange=onContentTor).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end=16.dp)) {
                    Text("Tor for all traffic",style=MaterialTheme.typography.titleMedium)
                    Text(if(contentTor)"Content, media and updates pause if Tor disconnects."else "Turn on to route content, media and updates through Tor.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked=contentTor,onCheckedChange=null)
            }
            if(state.enabled||contentTor) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(state.enabled)state.message else "Connecting to Tor",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("Connection Tor status"))
                    if(!state.ready)LinearProgressIndicator(progress={state.progress.coerceIn(0,100)/100f},modifier=Modifier.fillMaxWidth())
                }
            }
            if(originalFallback&&provider.torOnly)Text("${provider.label} searches always use Tor, even when content connects directly.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth()){Text("Done")}
        }
    }
}
