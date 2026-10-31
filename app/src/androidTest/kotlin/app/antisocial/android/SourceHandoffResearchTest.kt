/* anti social, GPL-3.0-or-later. Opt-in source-window handoff diagnostics. */
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reads no Room records and fetches no media. An ad-hoc, false-only image
 * callback forces fresh passes without manufacturing an image observation.
 * Sequential local timings do not measure complete native card delivery. */
class SourceHandoffResearchTest {
    private data class Facts(val hits:Int=0,val misses:Int=0,val entries:Int=0,val chargedBytes:Long=0,val budget:Long=0,
        val pendingEntries:Int=0,val pendingChargedBytes:Long=0)
    private fun field(value:Any,name:String)=value.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(value)
    private fun facts(matcher:FeedEditionMatcher):Facts {
        val pending=(field(matcher,"pendingAnalysis") as AtomicReference<*>).get()
        val entries=if(pending==null)emptyMap<Any,Any>()else field(requireNotNull(field(pending,"value")),"entries") as Map<*,*>
        val pendingBytes=entries.values.sumOf {field(requireNotNull(it),"bytes") as Long}
        val source=field(matcher,"sourcePreparation") as Lazy<*>
        if(!source.isInitialized())return Facts(pendingEntries=entries.size,pendingChargedBytes=pendingBytes)
        val store=requireNotNull(field(requireNotNull(source.value),"preparation"))
        return Facts(field(store,"hits") as Int,field(store,"misses") as Int,(field(store,"entries") as Map<*,*>).size,
            field(store,"bytes") as Long,field(store,"budget") as Long,entries.size,pendingBytes)
    }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it)}

    @Test fun privateWarmHandoffUsesTheActualResourceChooserAndKeepsItsCurrentGroups():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&
            args.getString("antisocialSourceHandoffResearch")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.getExternalFilesDir("validation"),"source-preparation-research.json")
        require(input.isFile&&input.length() in 1..8L*1024*1024)
        val raw=JsonParser.parseString(input.readText()).asJsonObject;val gson=Gson()
        require(raw.getAsJsonArray("posts").size() in 2..2048&&raw.getAsJsonArray("follows").size() in 1..2000)
        val posts=raw.getAsJsonArray("posts").map {gson.fromJson(it,Post::class.java)}.map {post->
            post.copy(media=post.media.map {it.copy(visualFingerprint=null,attachmentFingerprint=null,videoFootage=null)})
        }
        require(posts.map {it.identityKey}.distinct().size==posts.size)
        val follows=raw.getAsJsonArray("follows").map {gson.fromJson(it,Follow::class.java)}
        val ids=follows.mapTo(hashSetOf()){it.id};val keys=posts.mapTo(hashSetOf()){it.identityKey}
        val memberships=raw.getAsJsonObject("memberships").entrySet().associate {(key,value)->
            require(key in keys&&value.isJsonArray&&value.asJsonArray.size()<=ids.size)
            key to value.asJsonArray.map {it.asString.also {id->require(id in ids)}}.toSet()
        }
        val initial=posts.dropLast(1);val initialBindings=memberships-posts.last().identityKey
        val unchanged=hash(gson.toJson(posts));val expected=mutableMapOf<Int,String>()
        fun identity(groups:List<PostGroup>)=hash(gson.toJson(groups.map {it.posts.map(CrossPosts::originalKey)}))
        withContext(Dispatchers.Default) {
            expected[initial.size]=identity(CrossPosts.groupCancellable(initial,follows,initialBindings))
            expected[posts.size]=identity(CrossPosts.groupCancellable(posts,follows,memberships))
        }
        val resources=FeedAnalysisResources(context,true);val allowances=mutableListOf<Long>()
        val budget={resources.allowance().also {allowances+=it}}
        val handoff=FeedMatcherHandoff();val first=requireNotNull(handoff.create(follows,initialBindings,budget))
        var next:FeedEditionMatcher?=null;val observations=mutableListOf<Map<String,Any>>()
        suspend fun measure(label:String,matcher:FeedEditionMatcher,values:List<Post>)=withContext(Dispatchers.Default) {
            val before=facts(matcher);val reads=allowances.size;val thread=Thread.currentThread()
            val wall=SystemClock.elapsedRealtimeNanos();val cpu=Debug.threadCpuTimeNanos()
            val groups=matcher.project(MediaIdentitySnapshot(values){_,_->false},{it},{_,_->null})
            val cpuMs=(Debug.threadCpuTimeNanos()-cpu)/1_000_000.0
            val wallMs=(SystemClock.elapsedRealtimeNanos()-wall)/1_000_000.0
            assertSame("A CPU observation must remain on its worker",thread,Thread.currentThread())
            assertEquals(expected.getValue(values.size),identity(groups))
            assertEquals(values.mapTo(hashSetOf(),CrossPosts::originalKey),groups.flatMap {it.posts}.mapTo(hashSetOf(),CrossPosts::originalKey))
            assertEquals(unchanged,hash(gson.toJson(posts)))
            val after=facts(matcher);assertTrue(after.chargedBytes in 0..after.budget)
            assertEquals("Each independent image pass queries actual memory",reads+1,allowances.size)
            observations+=mapOf("phase" to label,"originals" to values.size,"actualAllowance" to allowances.last(),
                "before" to before,"after" to after,"hitsThisPass" to after.hits-before.hits,
                "missesThisPass" to after.misses-before.misses,"wallMs" to wallMs,"threadCpuMs" to cpuMs,
                "groups" to groups.size,"orderedGroupDigest" to identity(groups))
        }
        try {
            measure("cold-first",first,initial);measure("warm-repeat",first,initial)
            val receiving=requireNotNull(handoff.create(follows,memberships,budget));next=receiving
            val offered=facts(receiving);first.releaseSourceAnalysis()
            assertEquals(0,facts(first).entries)
            measure("late-window-first",receiving,posts);measure("late-window-repeat",receiving,posts)
            resources.visible(false);receiving.releaseSourceAnalysis()
            measure("stopped",receiving,posts)
            assertEquals(0,facts(receiving).entries);assertEquals(0L,facts(receiving).budget)
            val report=mapOf("originals" to posts.size,"offered" to offered,"phases" to observations,"sourceUnchanged" to true,
                "scope" to "Actual FeedAnalysisResources chooser and source-only handoff; last existing input edition arrives after a repeated pass. No Room writes, media callback proofs or requests. Local sequential timings are not card, phone, p95 or energy benchmarks; charged bytes are estimates.")
            val output=File(context.getExternalFilesDir("validation"),"source-handoff-research-report.json")
            FileOutputStream(output).use {it.write(gson.toJson(report).toByteArray(Charsets.UTF_8));it.fd.sync()}
        }finally {first.releaseSourceAnalysis();next?.releaseSourceAnalysis()}
    }
}
