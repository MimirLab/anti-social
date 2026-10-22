/* AntiSocial, GPL-3.0-or-later. Independently written guard for NewPipe's decoder. */
package app.antisocial.core

import kotlinx.coroutines.ensureActive
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** NewPipe evaluates only extracted player-decoder functions in Rhino's safe
 * standard scope. Keep that worker's nested Context.enter() calls inside an
 * explicit factory, with no Java class access and a cumulative script budget.
 * This changes no global Rhino factory and exposes no application objects. */
internal object YouTubeScriptGuard {
    private class Stopped: Error("YouTube decoder execution stopped")
    fun <T> run(coroutine: CoroutineContext,scriptBudgetMs: Long=3000,action: ()->T): T {
        require(scriptBudgetMs in 1..10000)
        coroutine.ensureActive()
        val budget=scriptBudgetMs*1_000_000
        val factory=object: ContextFactory() {
            private var spent=0L
            private var started=0L
            override fun makeContext(): Context=super.makeContext().apply {
                setInterpretedMode(true)
                setClassShutter {false}
                setMaximumInterpreterStackDepth(128)
                setInstructionObserverThreshold(10_000)
            }
            override fun doTopCall(callable: Callable,context: Context,scope: Scriptable,thisObject: Scriptable,args: Array<out Any?>): Any? {
                started=System.nanoTime()
                try {return super.doTopCall(callable,context,scope,thisObject,args)}finally {spent+=System.nanoTime()-started;started=0L}
            }
            override fun observeInstructionCount(context: Context,instructionCount: Int) {
                // Error cannot be intercepted by a decoder's JavaScript catch
                // block. Convert it back to a normal failure outside Rhino.
                if(Thread.currentThread().isInterrupted||coroutine[kotlinx.coroutines.Job]?.isActive==false||spent+(if(started>0)System.nanoTime()-started else 0)>budget)throw Stopped()
            }
        }
        try {
            return factory.enterContext().use {context->
                check(context.factory===factory){"YouTube decoder entered an unrelated script context"}
                action()
            }
        }catch(_: Stopped) {
            coroutine.ensureActive()
            throw IOException("YouTube player decoding exceeded its execution limit")
        }
    }
}
