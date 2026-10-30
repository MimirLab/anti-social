/* anti social, GPL-3.0-or-later. Supplemental work follows real reader attention. */
package app.antisocial.android

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class ReaderWorkAttention(val active:Boolean,val revision:Long)

/** Cancellation cannot wait for a recomposer which Android has already paused.
 * The revision also preserves a leave/return observed between two frames. */
@Composable internal fun rememberReaderWorkAttention(cancel:()->Unit):ReaderWorkAttention {
    val view=LocalView.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestCancel by rememberUpdatedState(cancel)
    var resumed by remember(lifecycle){mutableStateOf(lifecycle.currentState==Lifecycle.State.RESUMED)}
    var focused by remember(view){mutableStateOf(view.hasWindowFocus())}
    var revision by remember(lifecycle,view){mutableLongStateOf(0)}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,_->
            val next=lifecycle.currentState==Lifecycle.State.RESUMED
            if(next&&!resumed)revision++
            resumed=next
            if(!next)latestCancel()
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);latestCancel()}
    }
    DisposableEffect(view) {
        val observer=android.view.ViewTreeObserver.OnWindowFocusChangeListener {next->
            if(next&&!focused)revision++
            focused=next
            if(!next)latestCancel()
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(observer)
        onDispose {if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnWindowFocusChangeListener(observer);latestCancel()}
    }
    return ReaderWorkAttention(resumed&&focused,revision)
}
