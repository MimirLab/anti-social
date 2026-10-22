/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.RssSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Register a public feed explicitly; fetching is a separate user refresh. */
@Composable internal fun RssSourceSheet(onAdd: suspend (String,String,String)->Unit,onDismiss: ()->Unit) {
    var url by remember {mutableStateOf("")}
    var title by remember {mutableStateOf("")}
    var folder by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},icon={Icon(Icons.Rounded.RssFeed,null)},title={Text("Add an RSS / Atom feed")},text={Column(Modifier.verticalScroll(rememberScrollState()).testTag("RSS source form"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("A blog or public feed can sit beside your social subscriptions, in the same chronological reader.",fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url,{url=it;error=null},label={Text("HTTPS feed URL")},placeholder={Text("https://example.org/feed.xml")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth().testTag("RSS feed URL"))
        OutlinedTextField(title,{title=it;error=null},label={Text("Title (optional)")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("RSS feed title"))
        OutlinedTextField(folder,{folder=it;error=null},label={Text("Folder (optional)")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("RSS feed folder"))
        Text("Use the feed's URL, rather than a blog home page. Adding it stays local; Refresh reads the feed through your current connection settings.",fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let {Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("RSS source error"))}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }},confirmButton={TextButton(onClick={
        val validated=RssSources.feedUrl(url)
        if(validated==null)error="Enter a public HTTPS RSS or Atom URL without login credentials."
        else if(title.length>160||folder.length>120)error="Use 160 characters or fewer for the title, and 120 or fewer for the folder."
        else scope.launch {busy=true;error=null;try {onAdd(validated,title.trim(),folder.trim());onDismiss()}catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "This feed could not be added."}finally {busy=false}}
    },enabled=!busy&&url.isNotBlank(),modifier=Modifier.testTag("Add RSS source")){Text("Add feed")}},dismissButton={TextButton(onClick=onDismiss,enabled=!busy){Text("Cancel")}})
}
