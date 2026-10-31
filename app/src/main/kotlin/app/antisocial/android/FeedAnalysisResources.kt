/* anti social, GPL-3.0-or-later. Local, disposable analysis only. */
package app.antisocial.android

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.antisocial.core.SourcePreparationBudget
import app.antisocial.core.SourcePreparationMemory

internal class FeedAnalysisResources(context:Context,initialVisible:Boolean,
    private val memory:()->SourcePreparationMemory={readMemory(context)}) {
    private val gate=Any()
    private var visible=initialVisible
    private var generation=0L
    private var selected=SourcePreparationBudget.BASIC
    fun visible(value:Boolean)=synchronized(gate) {
        if(visible!=value){visible=value;generation++;selected=SourcePreparationBudget.BASIC}
    }
    fun allowance():Long {
        val captured=synchronized(gate){if(!visible)return 0L;generation}
        val facts=memory()
        return synchronized(gate) {
            if(!visible||captured!=generation)0L else SourcePreparationBudget.choose(selected,facts).also {selected=it}
        }
    }
    companion object {
        private fun readMemory(context:Context):SourcePreparationMemory {
            val runtime=Runtime.getRuntime();val used=runtime.totalMemory()-runtime.freeMemory()
            return try {
                val manager=requireNotNull(context.getSystemService(ActivityManager::class.java))
                val memory=ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
                SourcePreparationMemory(manager.isLowRamDevice,memory.lowMemory,runtime.maxMemory(),used,
                    if(memory.totalMem>0)minOf(memory.availMem,memory.totalMem)else -1,memory.threshold)
            }catch(_:Exception){SourcePreparationMemory(true,false,runtime.maxMemory(),used,-1,-1)}
        }
    }
}

@Composable internal fun rememberFeedAnalysisResources(context:Context):FeedAnalysisResources {
    val owner=LocalLifecycleOwner.current
    return remember(context,owner){FeedAnalysisResources(context.applicationContext,owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))}
}

/** Run directly from lifecycle callbacks even when the recomposer is paused.
 * Dispose an obsolete window's analysis without touching its displayed base. */
@Composable internal fun FeedAnalysisLifetime(resources:FeedAnalysisResources,matcher:FeedEditionMatcher?) {
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,resources,matcher) {
        fun visibility(value:Boolean) {resources.visible(value);if(!value)matcher?.releaseSourceAnalysis()}
        visibility(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer=LifecycleEventObserver {_,event->when(event) {
            Lifecycle.Event.ON_START->visibility(true)
            Lifecycle.Event.ON_STOP,Lifecycle.Event.ON_DESTROY->visibility(false)
            else->Unit
        }}
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer);matcher?.releaseSourceAnalysis()}
    }
}
