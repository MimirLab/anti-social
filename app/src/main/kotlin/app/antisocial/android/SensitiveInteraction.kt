/* anti social, GPL-3.0-or-later. Scoped to consequential controls, never reading. */
package app.antisocial.android

import android.os.Build
import android.view.MotionEvent
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Do not consume ordinary gestures or affect accessibility actions. A tap
 * arriving through an obscuring window must not confirm credentials/exports. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.rejectObscuredTouches():Modifier=pointerInteropFilter {event->
    event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED!=0||Build.VERSION.SDK_INT>=29&&event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED!=0
}

/** Protect only this credential dialog, and restore its window on disposal.
 * Android 12+ can hide untrusted overlays; normal reader windows keep theirs. */
@Composable internal fun SensitiveInteraction() {
    val view=LocalView.current
    DisposableEffect(view) {
        val root=view.rootView;val previous=root.filterTouchesWhenObscured
        val window=(view.parent as? DialogWindowProvider)?.window
        root.filterTouchesWhenObscured=true
        if(Build.VERSION.SDK_INT>=31)window?.setHideOverlayWindows(true)
        onDispose {root.filterTouchesWhenObscured=previous;if(Build.VERSION.SDK_INT>=31)window?.setHideOverlayWindows(false)}
    }
}
