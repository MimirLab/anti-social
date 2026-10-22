/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.antisocial.core.SponsorCategory

@Composable internal fun SponsorBlockSettings(store: SponsorBlockStore) {
    val values by store.settings.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("Settings list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Text("Save time in YouTube videos with community-marked segments.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {SponsorToggle("SponsorBlock","Automatically skip selected segments.",values.enabled,true,"SponsorBlock enabled",store::enabled)}
        item {Text("AUTOMATIC SKIPS",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
        items(SponsorCategory.entries,key={it.id}) {category->SponsorToggle(category.label,category.detail,category.id in values.categories,values.enabled,"SponsorBlock category ${category.id}"){store.category(category,it)}}
        item {Text("All skip categories and the highlight jump start enabled. Mute-only segments are skipped too. A deliberate seek back lets you watch a skipped part.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Text("Lookups send a short hash prefix instead of the exact video ID, use your selected connection and never report views or create an identity. An unavailable service does not interrupt playback.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Text("Uses SponsorBlock data from https://sponsor.ajay.app/ · CC BY-NC-SA 4.0. Community coverage varies. Full-video labels and chapters do not skip the entire video.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable private fun SponsorToggle(title: String,description: String,checked: Boolean,enabled: Boolean,tag: String,onChange: (Boolean)->Unit) {
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().toggleable(value=checked,enabled=enabled,role=Role.Switch,onValueChange=onChange).testTag(tag).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleSmall,color=if(enabled)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha=.38f))
                Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=checked,onCheckedChange=null,enabled=enabled)
        }
    }
}
