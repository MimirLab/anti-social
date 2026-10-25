/* anti social, GPL-3.0-or-later. A stable reading viewport for streamed details. */
package app.antisocial.android

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Edge flings belong to the document, rather than also dragging and settling
 * its containing sheet. A fixed viewport also keeps late details from moving
 * sheet anchors. Back, outside-tap and the pinned close control still dismiss. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LocalDetailSheet(onDismiss:()->Unit,activity:(@Composable ()->Unit)?=null,tag:String="Local detail viewport",closeLabel:String="Close details",content:@Composable ColumnScope.()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,modifier=Modifier.fillMaxHeight(.92f).testTag(tag),
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),sheetGesturesEnabled=false,dragHandle=null) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(activity!=null)activity()else Spacer(Modifier.width(8.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,closeLabel)}
        }
        content()
    }
}
