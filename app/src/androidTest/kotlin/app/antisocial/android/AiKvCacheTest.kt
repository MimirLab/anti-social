package app.antisocial.android

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.os.Bundle
import kotlinx.coroutines.*

/** Explicitly opted-in acquired-model tests on the isolated fixture emulator. */
@RunWith(AndroidJUnit4::class)
class AiKvCacheTest {
    @Test fun privateWorkerReportsItsCacheAndReopensForAChangedRecipe()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialLocalAiModels")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        LocalTextRuntime.installer(context).install()
        val size=args.getString("antisocialKvModel") ?: "2B";require(size in setOf("2B","4B"))
        val path=File(context.filesDir,"local-ai/test/Ternary-Bonsai-2-$size-PQ2_0.gguf").path
        val client=LocalAiClient(context);val evidence=JSONArray()
        fun input(profile: String,user: String)=Bundle().apply {
            putString("kind","text");putString("path",path);putString("backend","cpu");putString("cacheProfile",profile)
            putString("system","Return a JSON object with exactly one field named closures, an integer containing the corrected count from the supplied record.");putString("user",user);putInt("maxTokens",24);putBoolean("jsonMode",true)
        }
        try {
            for((profile,number) in listOf("pq4_protected_v2" to "23","f16_reference" to "47","pq4_protected_v2" to "61")) {
                val response=withTimeout(180_000){client.request(AiIpc.TEXT,input(profile,"The original count was 19. Correction: $number replaces 19. What is the corrected count?"))}
                val diagnostics=JSONObject(response.getString("diagnostics")!!)
                assertEquals("native",diagnostics.getString("positioning"))
                assertEquals("antisocial-hybrid-state-v3",diagnostics.getString("checkpoint_recipe"))
                assertNotEquals(android.os.Process.myPid(),response.getInt("workerPid"))
                assertEquals(profile,diagnostics.getString("cache"));val answer=JSONObject(response.getString("text")!!)
                assertEquals(setOf("closures"),answer.keys().asSequence().toSet());assertEquals(number.toInt(),answer.get("closures"))
                evidence.put(JSONObject().put("profile",profile).put("output",response.getString("text")).put("worker_pid",response.getInt("workerPid")).put("diagnostics",diagnostics))
            }
            File(context.filesDir,"local-ai/test/kv-$size-worker-android.json").writeText(JSONObject().put("cases",evidence).put("passed",true).toString(2))
        }finally {client.release()}
    }
    @Test fun protectedHistoryPreservesAnOlderCorrectionAndResetsBetweenJobs() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("antisocialLocalAiModels")=="true"&&arguments.getString("antisocialIsolatedFixtureDevice")=="true")
        val size=arguments.getString("antisocialKvModel") ?: "2B"
        require(size in setOf("2B","4B"))
        val context=instrumentation.targetContext
        runBlocking {LocalTextRuntime.installer(context).install()}
        LocalTextNative.ensureLoaded(context)
        val model=File(context.filesDir,"local-ai/test/Ternary-Bonsai-2-$size-PQ2_0.gguf")
        assertTrue("Stage the verified model before opting in",model.isFile)
        val capabilities=JSONObject(LocalTextNative.capabilities())
        assertTrue(capabilities.getString("revision").endsWith("+antisocial-kv-v3"))
        val system="Read only the supplied record. Return a JSON object with exactly one field named closures, an integer containing the corrected closure count."
        val filler=(1..30).joinToString("\n") {index->
            "Unrelated inventory $index: the museum catalogues maps, photographs, notebooks and wooden display cases. This entry says nothing about the closure report."
        }
        val source="Asha's closure report originally stated 19 closures. CORRECTION: 23 closures, replacing 19. Bruno's separate inventory count is irrelevant.\n$filler\nQuestion: What is the corrected number of closures in Asha's report?"
        val evidence=JSONArray()
        val file=File(context.filesDir,"local-ai/test/kv-$size-android.json")
        fun record(complete: Boolean=false) {file.writeText(JSONObject()
            .put("model",size).put("abi",android.os.Build.SUPPORTED_ABIS.first()).put("cases",evidence).put("complete",complete)
            .put("scope","Android CPU execution; emulator evidence does not qualify physical ARM/GPU devices").toString(2))}
        for(profile in listOf("f16_reference","pq4_protected_v2")) {
            val handle=LocalTextNative.open(model.path,"cpu",2048,2,profile)
            val executor=Executors.newSingleThreadExecutor()
            try {
                fun generate(user: String): String {
                    val task=executor.submit<String> {LocalTextNative.generate(handle,system,user,24,42,true)}
                    return try {task.get(if(size=="4B")600 else 300,TimeUnit.SECONDS)}catch(error: Exception){
                        LocalTextNative.cancel(handle)
                        runCatching {task.get(20,TimeUnit.SECONDS)}
                        throw error
                    }
                }
                val tokens=LocalTextNative.countTokens(handle,system,source)
                assertTrue("Fixture must exercise compressed history: $tokens",tokens>700)
                assertTrue("Fixture must fit without truncation: $tokens",tokens<1900)
                val started=android.os.SystemClock.elapsedRealtime()
                val output=generate(source).trim()
                val diagnostics=JSONObject(LocalTextNative.diagnostics(handle))
                val memory=Debug.MemoryInfo().also(Debug::getMemoryInfo)
                val result=JSONObject().put("profile",profile).put("output",output).put("tokens",tokens)
                    .put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started).put("sampled_process_pss_kib",memory.totalPss)
                    .put("diagnostics",diagnostics)
                evidence.put(result);record()
                assertEquals(profile,diagnostics.getString("cache"))
                assertEquals("native",diagnostics.getString("positioning"))
                assertEquals("antisocial-hybrid-state-v3",diagnostics.getString("checkpoint_recipe"))
                assertEquals(tokens,diagnostics.getInt("input_tokens"))
                val answer=JSONObject(output);assertEquals(setOf("closures"),answer.keys().asSequence().toSet());assertEquals(23,answer.get("closures"))
                LocalTextNative.reset(handle)
                val reset=generate("The original closure count was 31. Correction: 47 closures replaces 31. What is the corrected closure count?").trim()
                result.put("reset_output",reset);record();val resetAnswer=JSONObject(reset)
                assertEquals(setOf("closures"),resetAnswer.keys().asSequence().toSet());assertEquals(47,resetAnswer.get("closures"))
            }finally {LocalTextNative.cancel(handle);executor.shutdown();executor.awaitTermination(30,TimeUnit.SECONDS);LocalTextNative.close(handle)}
        }
        record(complete=true)
    }
}
