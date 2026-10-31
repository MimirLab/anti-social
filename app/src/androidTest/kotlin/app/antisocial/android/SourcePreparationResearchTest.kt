/* anti social, GPL-3.0-or-later. Opt-in, source-only memory diagnostics. */
package app.antisocial.android

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Supplied records stay outside Room/assets. No decoder, fetch or production
 * budget change is involved. These local timings do not measure card delivery. */
class SourcePreparationResearchTest {
    private data class Facts(val hits:Int,val misses:Int,val entries:Int,val chargedBytes:Long,val budget:Long,
        val candidateKeys:Int,val stringKeyCharacters:Long,val keyCharactersByKind:Map<String,Long>)
    private fun facts(source:CrossPosts.SourceProjection):Facts {
        val store=source.javaClass.getDeclaredField("preparation").apply {isAccessible=true}.get(source)
        fun field(name:String)=store.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(store)
        val entries=field("entries") as Map<*,*>
        var keys=0;var characters=0L;val categories=linkedMapOf<String,Long>()
        val kinds=setOf("text","base","url","media","clip","heading","resource-tail","speaker","fusion","repeat","copy")
        for(entry in entries.values) {
            requireNotNull(entry)
            val values=entry.javaClass.getDeclaredField("candidates").apply {isAccessible=true}.get(entry) as Set<*>
            keys+=values.size
            for(value in values.filterIsInstance<String>()) {
                characters+=value.length
                // Report fixed route names only, never a source-derived key.
                val kind=value.substringBefore(':').takeIf {it in kinds} ?: "other"
                categories[kind]=(categories[kind] ?: 0L)+value.length
            }
        }
        return Facts(field("hits") as Int,field("misses") as Int,entries.size,field("bytes") as Long,
            field("budget") as Long,keys,characters,categories)
    }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it)}

    @Test fun privateSourceWindowKeepsItsGroupsWhileFactBudgetsChange():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&
            args.getString("antisocialSourcePreparationResearch")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.getExternalFilesDir("validation"),"source-preparation-research.json")
        require(input.isFile&&input.length() in 1..8L*1024*1024)
        val raw=JsonParser.parseString(input.readText()).asJsonObject;val gson=Gson()
        require(raw.getAsJsonArray("posts").size() in 1..2048&&raw.getAsJsonArray("follows").size() in 1..2000)
        val posts=raw.getAsJsonArray("posts").map {gson.fromJson(it,Post::class.java)}.map {post->
            post.copy(media=post.media.map {it.copy(visualFingerprint=null,attachmentFingerprint=null,videoFootage=null)})
        }
        val follows=raw.getAsJsonArray("follows").map {gson.fromJson(it,Follow::class.java)}
        val ids=follows.mapTo(hashSetOf()){it.id};val keys=posts.mapTo(hashSetOf()){it.identityKey}
        val memberships=raw.getAsJsonObject("memberships").entrySet().associate {(key,value)->
            require(key in keys&&value.isJsonArray&&value.asJsonArray.size()<=ids.size)
            key to value.asJsonArray.map {it.asString.also {id->require(id in ids)}}.toSet()
        }
        val source=CrossPosts.sourceProjection(follows,memberships,SourcePreparationBudget.EXPANDED)
        source.setPreparationBudget(SourcePreparationBudget.BASIC)
        val unchanged=hash(gson.toJson(posts));var digest:String?=null
        val observations=mutableListOf<Map<String,Any>>()
        suspend fun measure(label:String)=withContext(Dispatchers.Default) {
            val before=facts(source);val thread=Thread.currentThread().id
            val wall=SystemClock.elapsedRealtimeNanos();val cpu=Debug.threadCpuTimeNanos()
            val groups=source.group(posts)
            val cpuMs=(Debug.threadCpuTimeNanos()-cpu)/1_000_000.0
            val wallMs=(SystemClock.elapsedRealtimeNanos()-wall)/1_000_000.0
            assertEquals("A CPU observation must stay on its measured worker",thread,Thread.currentThread().id)
            val current=hash(gson.toJson(groups.map {it.posts.map(CrossPosts::originalKey)}))
            if(digest==null)digest=current else assertEquals("Budget changes must not alter grouping",digest,current)
            assertEquals(posts.mapTo(hashSetOf(),CrossPosts::originalKey),groups.flatMap {it.posts}.mapTo(hashSetOf(),CrossPosts::originalKey))
            assertEquals("Source records must stay immutable",unchanged,hash(gson.toJson(posts)))
            val after=facts(source)
            assertTrue(after.chargedBytes in 0..after.budget)
            observations+=mapOf("phase" to label,"wallMs" to wallMs,"threadCpuMs" to cpuMs,
                "facts" to after,"hitsThisPass" to after.hits-before.hits,"missesThisPass" to after.misses-before.misses,
                "groups" to groups.size,"orderedGroupDigest" to current)
        }
        try {
            measure("first-basic");measure("repeat-basic")
            source.setPreparationBudget(SourcePreparationBudget.EXPANDED)
            measure("grow-expanded");measure("repeat-expanded")
            source.setPreparationBudget(0);measure("released")
            assertEquals(0,facts(source).entries)
            val report=mapOf("originals" to posts.size,"phases" to observations,"sourceUnchanged" to true,
                "scope" to "Source-only grouping with image/footage observations removed and no media callback. Charged bytes are the existing accounting estimate, not measured heap. Local-stage times are not first-card timings. No Room writes or requests.")
            val output=File(context.getExternalFilesDir("validation"),"source-preparation-research-report.json")
            FileOutputStream(output).use {it.write(gson.toJson(report).toByteArray(Charsets.UTF_8));it.fd.sync()}
        }finally {source.setPreparationBudget(0)}
    }
}
