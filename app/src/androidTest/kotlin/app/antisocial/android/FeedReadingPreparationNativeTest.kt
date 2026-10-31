/* anti social, GPL-3.0-or-later. Synthetic source-preparation cancellation. */
package app.antisocial.android

import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FeedReadingPreparationNativeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get()=(context.applicationContext as AntiSocialApplication).repo
    private val helpers=Class.forName("app.antisocial.android.ChronologicalFeedKt")
    private val modern=helpers.declaredMethods.singleOrNull {it.name.substringBefore('$')=="prepareFeedReadingRows"&&it.parameterCount==4}
    private val legacy=helpers.declaredMethods.single {it.name.substringBefore('$')=="projectFeedReadingRows"&&it.parameterCount==2}
    private data class Batch(val rows:List<Triple<FrozenFeedRow,List<Post>,List<Post>>>,val observations:List<ContentObservation>)
    @Before fun ready() {runBlocking {repo.awaitReady()}}

    /** Cross-version probe of actual app helpers. The legacy branch uses the
     * same Main -> Default boundary as the old reader; it intentionally fails
     * the stop-work requirement, rather than replacing the implementation. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun prepare(rows:List<FrozenFeedRow>,observe:Boolean,project:(Post)->Post):Batch {
        val method=modern
        if(method==null)return withContext(Dispatchers.Default) {
            val projected=try {legacy.invoke(null,rows,project) as List<Triple<FrozenFeedRow,List<Post>,List<Post>>>}
                catch(failure:InvocationTargetException){throw failure.targetException}
            Batch(projected,if(observe)ContentRevisions.observations(projected.flatMap {it.second})else emptyList())
        }
        val result=suspendCoroutineUninterceptedOrReturn<Any> {continuation->
            try {method.invoke(null,rows,observe,project,continuation)}catch(failure:InvocationTargetException){throw failure.targetException}
        }
        return Batch(result.javaClass.getMethod("getRows").invoke(result) as List<Triple<FrozenFeedRow,List<Post>,List<Post>>>,
            result.javaClass.getMethod("getObservations").invoke(result) as List<ContentObservation>)
    }
    private fun rows(count:Int)=List(count) {index->
        val id=(710_000+index).toString()
        val post=Post(id,Platform.X,"https://x.com/publicstudio/status/$id","Public studio",
            "A complete workshop announcement. More at https://example.org/report/$id?utm_source=fixture",publishedAt=1_800_000_000_000L)
        FrozenFeedRow(post.identityKey,listOf(post.identityKey),listOf(post))
    }
    private fun project(post:Post)=filterProjection(repo,post)

    @Test fun cancelledReadingPreparationStopsBeforeAnotherOriginal()=runBlocking {
        val source=rows(64);val before=source.toList();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val count=AtomicInteger();val committed=AtomicBoolean();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try {
            val job=scope.async {
                prepare(source,false) {post->
                    if(count.incrementAndGet()==1){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                    project(post)
                }
                committed.set(true)
            }
            assertTrue("The actual worker must enter its first source projection",entered.await(10,TimeUnit.SECONDS))
            job.cancel();release.countDown();withTimeout(10_000){job.join()}
            val report=mapOf("path" to if(modern==null)"legacy-source-helpers"else "cancellable-preparation",
                "inputOriginals" to source.size,"projectedAfterCancellationAtFirstOriginal" to count.get(),
                "commitRan" to committed.get(),"sourceUnchanged" to (source==before))
            val output=File(context.getExternalFilesDir("validation"),"reading-preparation-cancel").apply {mkdirs()}
            File(output,"report.json").writeText(repo.gson.toJson(report))
            assertFalse("Cancellation must not deliver an obsolete batch",committed.get())
            assertEquals(before,source)
            assertEquals("A stopped reader must not project the remaining originals",1,count.get())
        }finally {release.countDown();scope.cancel()}
    }

    @Test fun completePreparationPreservesFrozenAndNewEditionsAndTheirObservations()=runBlocking {
        val initial=rows(4)
        val first=initial.first();val revised=first.primary.copy(body="A corrected workshop announcement.")
        val source=listOf(first.copy(posts=listOf(revised)))+initial.drop(1);val before=source.toList()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try {
            val actual=withTimeout(10_000){scope.async {prepare(source,true,::project)}.await()}
            val expected=projectFeedReadingRows(source,::project)
            assertEquals(expected,actual.rows)
            assertEquals(ContentRevisions.observations(expected.flatMap {it.second}),actual.observations)
            assertNotEquals(actual.rows.first().second,actual.rows.first().third)
            actual.rows.drop(1).forEach {assertSame(it.second,it.third)}
            assertEquals(before,source)
        }finally {scope.cancel()}
    }

    @Test fun cancelledObservationPreparationDoesNotSignTheRemainingOriginals()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val blocked=AtomicBoolean()
        val signatures=ConcurrentHashMap.newKeySet<Int>();val projected=AtomicInteger();val committed=AtomicBoolean()
        val source=rows(64).mapIndexed {index,row->
            val media=object:AbstractList<Media>() {
                override val size=1
                override fun get(slot:Int):Media {
                    check(slot==0);signatures+=index
                    if(index==0&&blocked.compareAndSet(false,true)){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                    return Media("https://media.example.org/picture-$index.jpg")
                }
            }
            val post=row.primary.copy(media=media)
            FrozenFeedRow(row.key,row.aliases,listOf(post))
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try {
            val job=scope.async {
                prepare(source,true){projected.incrementAndGet();it}
                committed.set(true)
            }
            assertTrue("The actual signature path must reach the first source",entered.await(10,TimeUnit.SECONDS))
            job.cancel();release.countDown();withTimeout(10_000){job.join()}
            val report=mapOf("path" to if(modern==null)"legacy-source-helpers"else "cancellable-preparation",
                "inputOriginals" to source.size,"projectedOriginals" to projected.get(),
                "signatureSourcesTouched" to signatures.size,"commitRan" to committed.get())
            val output=File(context.getExternalFilesDir("validation"),"reading-preparation-cancel").apply {mkdirs()}
            File(output,"observation-report.json").writeText(repo.gson.toJson(report))
            assertFalse(committed.get());assertEquals(64,projected.get())
            assertEquals("A stopped reader must not sign the remaining originals",1,signatures.size)
        }finally {release.countDown();scope.cancel()}
    }

    @Test fun stoppingOneReaderDoesNotHoldOrCancelAnIndependentPreparation()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try {
            val stopped=scope.async {prepare(rows(8),true) {post->
                entered.countDown();check(release.await(10,TimeUnit.SECONDS));project(post)
            }}
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            val fresh=rows(3)
            val independent=withTimeout(10_000){scope.async {prepare(fresh,true,::project)}.await()}
            assertEquals(fresh.map {it.key},independent.rows.map {it.first.key})
            assertEquals(3,independent.observations.size)
            stopped.cancel();release.countDown();withTimeout(10_000){stopped.join()}
            val repeated=withTimeout(10_000){scope.async {prepare(fresh,true,::project)}.await()}
            assertEquals(independent,repeated)
        }finally {release.countDown();scope.cancel()}
    }

    @Test fun failedPreparationCannotInstallPartialFactsOrPolluteItsReplacement()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main);val source=rows(3);val count=AtomicInteger();val commit=AtomicBoolean()
        try {
            val broken=scope.async {prepare(source,true) {post->
                check(count.incrementAndGet()!=2){"Synthetic source projection failed"};project(post)
            }.also {commit.set(true)}}
            try {withTimeout(10_000){broken.await()};fail("The projection error must propagate")}catch(expected:IllegalStateException) {
                assertEquals("Synthetic source projection failed",expected.message)
            }
            assertFalse(commit.get())
            val fresh=withTimeout(10_000){scope.async {prepare(source,true,::project)}.await()}
            assertEquals(3,fresh.rows.size);assertEquals(3,fresh.observations.size)
        }finally {scope.cancel()}
    }
}
