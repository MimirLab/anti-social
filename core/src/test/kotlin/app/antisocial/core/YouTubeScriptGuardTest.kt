/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.schabi.newpipe.extractor.utils.JavaScript
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

class YouTubeScriptGuardTest {
    @Test fun realNewPipeDecoderRunsInsideAWorkerGuardWithoutChangingGlobalFactory() {
        val global=ContextFactory.getGlobal()
        assertEquals("cba",YouTubeScriptGuard.run(EmptyCoroutineContext){JavaScript.run("function decode(s){return s.split('').reverse().join('')}","decode","abc")})
        assertSame(global,ContextFactory.getGlobal());assertNull(Context.getCurrentContext())
    }
    @Test fun safeScopeHasNoJavaBrowserFileOrNetworkCapabilities() {
        val result=YouTubeScriptGuard.run(EmptyCoroutineContext){JavaScript.run("function inspect(s){return [typeof Packages,typeof java,typeof JavaAdapter,typeof fetch,typeof document,typeof XMLHttpRequest,typeof readFile,typeof s.getClass].join('|')}","inspect","public")}
        assertEquals(List(8){"undefined"}.joinToString("|"),result)
    }
    @Test fun anInfiniteDecoderCannotCatchTheInstructionLimitAndTheWorkerRemainsUsable() {
        val before=System.nanoTime()
        try {YouTubeScriptGuard.run(EmptyCoroutineContext,scriptBudgetMs=50){JavaScript.run("function decode(s){try {while(true){}}catch(e){return 'caught'}}","decode","public")};fail("Decoder must stop")}
        catch(e: IOException){assertTrue(e.message!!.contains("execution limit"))}
        assertTrue("Instruction observer must stop an uncooperative decoder",(System.nanoTime()-before)/1_000_000<3000)
        assertNull(Context.getCurrentContext())
        assertEquals("public",YouTubeScriptGuard.run(EmptyCoroutineContext){JavaScript.run("function decode(s){return s}","decode","public")})
    }
    @Test fun coroutineCancellationStopsAnUncooperativeDecoderPromptly()=runBlocking {
        val entered=CompletableDeferred<Unit>()
        val task=async(Dispatchers.IO) {
            val parent=currentCoroutineContext()
            YouTubeScriptGuard.run(parent,scriptBudgetMs=10000) {
                entered.complete(Unit)
                JavaScript.run("function decode(s){while(true){}}","decode","public")
            }
        }
        entered.await();val before=System.nanoTime();task.cancel()
        withTimeout(3000){task.join()}
        assertTrue(task.isCancelled);assertTrue((System.nanoTime()-before)/1_000_000<3000)
    }
}
