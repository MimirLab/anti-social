/* anti social, GPL-3.0-or-later. Explicit private local CPU diagnostics. */
package app.antisocial.android

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** No fixture data is bundled, imported into Room or transmitted. The caller
 * supplies a private captured pool on an explicitly disposable installation. */
class CachedFeedProfileTest {
    /** Source reuse only: no injected image proof, downloads, Room mutation or
     * claim that a local operation timing measures first paint on a phone. */
    @Test fun compareCapturedSourceReuseWithIndependentFreshGrouping():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialPrivateSourceProjection")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val file=File(context.getExternalFilesDir("validation"),"private-captured-feed.json")
        require(file.isFile&&file.length()in 1..4L*1024*1024)
        val raw=JsonParser.parseString(file.readText()).asJsonObject
        val posts=raw.getAsJsonArray("posts").map {repo.gson.fromJson(it,Post::class.java)}
        val follows=raw.getAsJsonArray("follows").map {repo.gson.fromJson(it,Follow::class.java)}
        val members=raw.getAsJsonObject("memberships").entrySet().associate {(key,value)->key to value.asJsonArray.map {it.asString}.toSet()}
        require(posts.size in 1..1000&&follows.size in 1..2000)
        // A bounded opt-in experiment can compare retention allowances without
        // adding a public app setting or changing production's default. This
        // reflects our own class only, never an Android hidden/system API.
        val allowance=args.getString("antisocialSourceProjectionBytes")?.toLong()?.also {require(it in 262_144..8L*1024*1024)}
        var projection:CrossPosts.SourceProjection?=if(allowance==null)CrossPosts.sourceProjection(follows,members)else
            CrossPosts.SourceProjection::class.java.getDeclaredConstructor(List::class.java,Map::class.java,Int::class.javaPrimitiveType,Long::class.javaPrimitiveType)
                .newInstance(follows,members,2048,allowance)
        val rows=mutableListOf<Map<String,Any?>>()
        var expected:List<PostGroup>?=null
        withContext(Dispatchers.Default) {
            for(name in listOf("fresh-initial","retained-initial","retained-repeat-1","fresh-repeat-1","fresh-repeat-2","retained-repeat-2")) {
                // Independently captured immutable image snapshots have new
                // Post objects even though their source fields stay identical.
                val current=posts.map {p->p.copy(media=p.media.map {it.copy()})}
                val start=SystemClock.elapsedRealtimeNanos()
                val groups=if(name.startsWith("fresh"))CrossPosts.groupCancellable(current,follows,members)else projection!!.group(current)
                val elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                expected?.let {assertEquals(it,groups)} ?: run {expected=groups}
                val keys=groups.joinToString("\n"){group->group.posts.joinToString("\u0000"){it.identityKey}}
                val hash=java.security.MessageDigest.getInstance("SHA-256").digest(keys.toByteArray()).joinToString(""){"%02x".format(it)}
                rows+=mapOf("stage" to name,"elapsedMs" to elapsed,"groups" to groups.size,"groupingSha256" to hash)
            }
        }
        val output=File(context.getExternalFilesDir("validation"),"source-projection-profile.json")
        val stats=projection!!.javaClass.declaredMethods.single {it.name.substringBefore('$')=="stats"}.invoke(projection)
        val heap=if(args.getString("antisocialSourceProjectionHeap")=="true") {
            // Outside every timing interval. Report collection itself so a
            // retained local cannot manufacture a low allocation claim.
            repeat(3){System.gc();delay(100)}
            val runtime=Runtime.getRuntime();val before=runtime.totalMemory()-runtime.freeMemory()
            val owner=java.lang.ref.WeakReference(projection);projection=null
            repeat(3){System.gc();delay(100)}
            val after=runtime.totalMemory()-runtime.freeMemory()
            mapOf("ownerCollected" to (owner.get()==null),"managedBeforeBytes" to before,"managedAfterBytes" to after,"managedReleasedBytes" to before-after)
        }else null
        output.writeText(repo.gson.toJson(mapOf("scope" to "Pure local source preparation and grouping; no new image observations, network or display timing.","posts" to posts.size,"sources" to follows.size,"stages" to rows,"retainedPreparation" to stats,"experimentalAllowance" to allowance,"approximateManagedHeapExperiment" to heap)))
    }
    /** Inspect the real complete candidate window before any acquisition. A
     * diagnostic pass is not a claim that the visible feed has merged. */
    @Test fun inspectCapturedVideoCandidatePlans():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialPrivateVideoPlans")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val file=File(context.getExternalFilesDir("validation"),"private-captured-video-plan.json")
        require(file.isFile&&file.length()in 1..8L*1024*1024)
        val raw=JsonParser.parseString(file.readText()).asJsonObject
        val posts=raw.getAsJsonArray("posts").map {repo.gson.fromJson(it,Post::class.java)}
        val follows=raw.getAsJsonArray("follows").map {repo.gson.fromJson(it,Follow::class.java)}
        val members=raw.getAsJsonObject("memberships").entrySet().associate {(k,v)->k to v.asJsonArray.map {it.asString}.toSet()}
        val focus=raw.getAsJsonArray("focusKeys").map {it.asString}.toSet()
        require(posts.size in 1..2000&&follows.size in 1..2000&&focus.size in 1..CrossPosts.MAX_EDITIONS)
        val output=File(context.getExternalFilesDir("validation"),"captured-video-plan.json")
        val started=System.nanoTime()
        val groups=withContext(Dispatchers.Default){CrossPosts.groupCancellable(posts,follows,members)}
        val groupMs=(System.nanoTime()-started)/1_000_000.0
        assertEquals(posts.map(repo::key).toSet(),groups.flatMap {it.posts}.map(repo::key).toSet())
        val selected=groups.filter {g->g.posts.any {repo.key(it)in focus}}
        val global=withContext(Dispatchers.Default){CrossPosts.videoFootageRequests(groups,follows,members)}
        val focused=withContext(Dispatchers.Default){selected.associate {g->
            repo.key(g.primary)to CrossPosts.focusedVideoFootageRequests(groups,follows,members,setOf(repo.key(g.primary)))
        }}
        val stages=mutableListOf<Map<String,Any?>>()
        withContext(Dispatchers.Default) {
            val matcher=FeedEditionMatcher(follows,members)
            val image=MediaIdentitySnapshot(posts){_,_->false}
            repeat(4){pass->
                val start=System.nanoTime()
                val result=matcher.project(image,{it},{_,_->null})
                stages+=mapOf("pass" to pass,"elapsedMs" to (System.nanoTime()-start)/1e6,"groups" to result.size)
                assertEquals(groups,result)
            }
        }
        output.writeText(repo.gson.toJson(mapOf("posts" to posts.size,"groups" to groups.size,"groupingMs" to groupMs,
            "focusGroups" to selected,"globalRequests" to global,"focusedRequests" to focused,"unchangedImageVideoRevisions" to stages)))
    }
    @Test fun measureCapturedPoolGroupingAndReadingPreparation():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialPrivateFeedProfile")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo
        repo.awaitReady()
        val file=File(context.getExternalFilesDir("validation"),"private-captured-feed.json")
        require(file.isFile&&file.length() in 1..4L*1024*1024)
        val raw=JsonParser.parseString(file.readText()).asJsonObject
        require(raw.getAsJsonArray("posts").size()<=1000&&raw.getAsJsonArray("follows").size()<=2000)
        val original=repo.prefs.export().toString();val policy=repo.gateway.currentPolicy()
        val output=File(context.getExternalFilesDir("validation"),"cached-feed-profile").apply {mkdirs()}
        val trace=args.getString("antisocialTracePrivateFeed")=="true"
        val traceStage=args.getString("antisocialTracePrivateStage")
        require(traceStage==null||traceStage in setOf("decode","grouping","videoRequestPlanning","galleryRequestPlanning","projection","readingProjectionLegacy","readingProjection","observations","capturedReadComparisons","allReadComparisons","unreadComparisons"))
        val timings=linkedMapOf<String,Double>()
        suspend fun <T> measure(name:String,block:suspend ()->T):T {
            val tracing=trace&&(traceStage==null||name==traceStage)
            if(tracing)Debug.startMethodTracingSampling(File(output,"$name.trace").path,32*1024*1024,2_000)
            val start=SystemClock.elapsedRealtimeNanos()
            return try {block()}finally {timings[name]=(SystemClock.elapsedRealtimeNanos()-start)/1e6;if(tracing)Debug.stopMethodTracing()}
        }
        try {
            repo.prefs.intervalMinutes=0;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false
            repo.cancelSearchPreparation()
            // Complete the controller's main-thread stop before installing the
            // offline policy; a posted stop would otherwise overwrite it later.
            withContext(Dispatchers.Main.immediate) {
                repo.tor.enable(false)
                repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
            }
            assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
            val gson=Gson()
            val posts=measure("decode"){withContext(Dispatchers.Default){raw.getAsJsonArray("posts").map {gson.fromJson(it,Post::class.java)}}}
            val follows=raw.getAsJsonArray("follows").map {gson.fromJson(it,Follow::class.java)}
            val members=raw.getAsJsonObject("memberships").entrySet().associate {(key,value)->key to value.asJsonArray.map {it.asString}.toSet()}
            val read=raw.getAsJsonArray("readKeys").map {it.asString}.toSet()
            val groups=measure("grouping"){withContext(Dispatchers.Default){CrossPosts.groupCancellable(posts,follows,members)}}
            assertEquals(posts.map(repo::key).toSet(),groups.flatMap {it.posts}.map(repo::key).toSet())
            val groupingIdentity=groups.joinToString("\n") {group->group.posts.joinToString("\u0000",transform=repo::key)}
            val groupingHash=java.security.MessageDigest.getInstance("SHA-256").digest(groupingIdentity.toByteArray()).joinToString(""){"%02x".format(it)}
            val videoRequests=measure("videoRequestPlanning"){withContext(Dispatchers.Default){CrossPosts.videoFootageRequests(groups,follows,members)}}
            val galleryRequests=measure("galleryRequestPlanning"){withContext(Dispatchers.Default){CrossPosts.galleryContinuationRequests(groups,follows,members)}}
            val projected=measure("projection"){withContext(Dispatchers.Default){groups.map {group->group.posts.map {filterProjection(repo,it)} to group.posts.map {filterProjection(repo,it)}}}}
            val frozen=groups.map {group->FrozenFeedRow(repo.key(group.primary),group.posts.map(repo::key),group.posts)}
            val reading=measure("readingProjection"){withContext(Dispatchers.Default){projectFeedReadingRows(frozen){filterProjection(repo,it)}}}
            assertEquals(projected,reading.map {it.second to it.third})
            val observations=measure("observations"){withContext(Dispatchers.Default){ContentRevisions.observations(projected.flatMap {it.first})}}
            val observationIdentity=observations.joinToString("\n"){it.key+"\u0000"+it.signature+"\u0000"+it.fetchedAt}
            val observationHash=java.security.MessageDigest.getInstance("SHA-256").digest(observationIdentity.toByteArray()).joinToString(""){"%02x".format(it)}
            suspend fun inheritance(name:String,keys:Set<String>)=measure(name){withContext(Dispatchers.Default){projected.filter {it.first.size>1}.count {(body,all)->!ContentRevisions.addsContext(body.filter {repo.key(it) in keys},all)}}}
            val inherited=inheritance("capturedReadComparisons",read)
            inheritance("allReadComparisons",posts.map(repo::key).toSet())
            inheritance("unreadComparisons",emptySet())
            val result=mapOf("scope" to "Private captured pool; pure local stages only. No Room import, actual paint, cached-image rebuild, phone or live-provider timing claim.","posts" to posts.size,"groups" to groups.size,"groupingSha256" to groupingHash,"sources" to follows.size,"videoRequests" to videoRequests.size,"galleryRequests" to galleryRequests.size,"capturedReadKeys" to read.size,"capturedInheritedGroups" to inherited,"observations" to observations.size,"observationsSha256" to observationHash,"timingsMs" to timings)
            File(output,"results.json").writeText(gson.toJson(result))
            assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
        }finally {
            context.getSharedPreferences("antisocial",android.content.Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.restore(JSONObject(original));repo.applyNetworkSettings();repo.gateway.configure(policy);scheduleRefresh(context,repo.prefs)
        }
    }
}
