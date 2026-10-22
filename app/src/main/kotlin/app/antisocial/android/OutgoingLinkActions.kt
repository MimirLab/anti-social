/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.antisocial.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Actions alone resolve routes. Labels and attribution remain authentic; the
 * selected result URL must never be sent through publicUrl a second time. */
internal class OutgoingLinkActions(private val repo: Repository,private val context: Context,private val scope: CoroutineScope) {
    private var request: Job?=null
    private fun run(resolve: suspend ()->ShareRouteResult,apply: (ShareRouteResult.Available)->Unit) {
        request?.cancel()
        request=scope.launch {
            try {
                val result=resolve()
                withContext(Dispatchers.Main.immediate) {when(result) {
                    is ShareRouteResult.Available->{apply(result);result.notice?.let {Toast.makeText(context,it,Toast.LENGTH_LONG).show()}}
                    is ShareRouteResult.Unavailable->Toast.makeText(context,result.message,Toast.LENGTH_LONG).show()
                }}
            }catch(e: CancellationException){throw e}catch(_: Exception){withContext(Dispatchers.Main.immediate){Toast.makeText(context,"This link could not be checked. Try again shortly.",Toast.LENGTH_LONG).show()}}
        }
    }
    private fun copy(result: ShareRouteResult.Available) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Post link · ${result.platform.label}",result.url))
        if(result.notice==null)Toast.makeText(context,"Link copied",Toast.LENGTH_SHORT).show()
    }
    fun copy(post: Post,editions: List<Post> = emptyList())=run({repo.resolveOutgoing(post,editions,ShareLinkAction.COPY)},::copy)
    fun copyUrl(url: String,platform: Platform?=null)=run({repo.resolveOutgoing(url,platform,ShareLinkAction.COPY)},::copy)
    fun open(post: Post,editions: List<Post> = emptyList(),onResolved: (String)->Unit)=run({repo.resolveOutgoing(post,editions,ShareLinkAction.OPEN)},{onResolved(it.url)})
    fun openUrl(url: String,platform: Platform?=null,onResolved: (String)->Unit)=run({repo.resolveOutgoing(url,platform,ShareLinkAction.OPEN)},{onResolved(it.url)})
    fun share(post: Post,editions: List<Post> = emptyList())=run({repo.resolveOutgoing(post,editions,ShareLinkAction.COPY)},{result->
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,result.url)},"Share link"))
    })
}

@Composable internal fun rememberOutgoingActions(repo: Repository): OutgoingLinkActions {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    return remember(repo,context,scope){OutgoingLinkActions(repo,context,scope)}
}
