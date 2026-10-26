/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.Post
import kotlinx.coroutines.*

@Composable internal fun ContextPostAction(repo: Repository,displayed: Post,roots: List<Post>,bodyRoots: List<Post> = roots,archived: Boolean=false) {
    val ai by repo.ai.state.collectAsState()
    val available=remember(ai){repo.ai.contextAvailable()}
    if(!ai.enabled||!available)return
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    FilledTonalButton(onClick={
        val notices=context.getSharedPreferences("context-notices",Context.MODE_PRIVATE)
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!notices.getBoolean("asked",false)) {
            notices.edit().putBoolean("asked",true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scope.launch {val snapshot=withContext(Dispatchers.Default){contextSnapshot(repo,displayed,roots,bodyRoots,archived)};repo.contextReader.request(snapshot)}
    },contentPadding=PaddingValues(horizontal=8.dp,vertical=6.dp),modifier=Modifier.padding(horizontal=4.dp).height(36.dp).testTag("Context ${repo.key(displayed)}")) {
        Icon(Icons.Rounded.AutoAwesome,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Context",fontSize=12.sp)
    }
}
