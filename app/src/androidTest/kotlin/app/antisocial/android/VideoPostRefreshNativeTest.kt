/* anti social, GPL-3.0-or-later. Disposable native state; live inputs stay private. */
package app.antisocial.android

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VideoPostRefreshNativeTest {
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo get()=(context.applicationContext as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(9)
    private val at=System.currentTimeMillis()-120_000
    private val prefix="video-refresh-$suffix"
    private val accounts=mutableListOf<Follow>()
    private val posts=mutableListOf<Post>()
    private val banks=mutableListOf<VideoEvidenceStore>()
    private lateinit var original:Post
    private lateinit var fresh:Post
    private lateinit var binding:Follow
    private lateinit var policy:NetworkPolicy
    private var wifi=true
    private val source=Source(Platform.TIKTOK,"tikwm","https://reader.example")
    private fun url(objectName:String,shard:String="v45",region:String="eu")="https://$shard.tiktokcdn-$region.com/"+"a".repeat(32)+"/6ae10001/video/tos/alisg/tos-alisg-ve-001c001-sg/$objectName/?mime_type=video_mp4"
    @Before fun isolate():Unit=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        repo.awaitReady();policy=repo.gateway.currentPolicy();wifi=repo.prefs.videoDownloadWifiOnly
        repo.prefs.videoDownloadWifiOnly=false;repo.gateway.configure(NetworkPolicy(tor=true))
        val username="publicstudio$suffix";val id="92${suffix}001"
        original=Post(id,Platform.TIKTOK,"https://www.tiktok.com/@$username/video/$id","Public studio","",title="The workshop demonstration is ready.",publishedAt=at,
            media=listOf(Media(url("oWorkshopFilm0123456789abcdef$suffix"),"video")),provenance=Provenance(source.copy(frontend="rsshub",root="https://feed.example"),at+30_000,"https://feed.example/recent"))
        fresh=original.copy(title="",body=original.title,publishedAt=at+11_000,media=listOf(Media(url("oCurrentWorkshopFilm0123456789abcdef$suffix","v16m","us"),"video",durationMs=120_000)),
            provenance=Provenance(source,at+60_000,"https://reader.example/api/"))
        binding=Follow(prefix,repo.routes.target(Platform.TIKTOK,username),"Public studio",personId=prefix)
        seed(original,binding)
    }
    private suspend fun seed(post:Post,follow:Follow) {
        accounts+=follow;posts+=post
        repo.dao.putFollow(FollowRow(follow.id,follow.target.platform.id,repo.gson.toJson(follow)))
        repo.store(Page(listOf(post)),follow.id,true)
    }
    @After fun remove():Unit=runBlocking {
        if(!::original.isInitialized)return@runBlocking
        banks.forEach {it.cleanup(true)}
        accounts.distinctBy {it.id}.forEach {repo.dao.removeFollow(it.id)}
        val keys=posts.map(repo::key).distinct()
        for(key in keys) {
            repo.library.cancel(key);repo.dao.removeBookmark(key)
            repo.dao.deleteDocuments(key,"saved");repo.dao.deleteDocuments(key,"history")
            for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }
        repo.dao.removeRetainedHistory(keys);repo.dao.removePosts(keys)
        repo.prefs.videoDownloadWifiOnly=wifi;repo.gateway.configure(policy);repo.library.notifyChanged()
    }
    private fun bank(clock:()->Long=System::currentTimeMillis)=VideoEvidenceStore(repo,clock,directory=File(context.cacheDir,"$prefix-${UUID.randomUUID()}")).also {banks+=it}
    private fun peer()=Post("93${suffix}002",Platform.X,"https://x.com/publicstudio/status/93${suffix}002","Public studio","See the complete public demonstration.",publishedAt=at,
        media=listOf(Media("https://video.twimg.com/amplify_video/93${suffix}002/vid/320x180/film.mp4","video",durationMs=120_000)))
    private fun frames()=SampledVideoFrames(120_000,(0..3).map {p->
        val pixels=ByteArray(4096){i->var n=(p+1)*7919+(i%64/4)*31+(i/64/4)*1009;n=(n xor(n ushr 13))*1274126177;((n ushr 16 and 191)+32).toByte()}
        SampledVideoScene(p,requireNotNull(VisualFrames.encode(1.5,40.0,0,0,List(48){128},pixels)))
    })
    private fun result(media:Media)=GatewayVideoSampler.Result(frames(),1024,1,VideoAssetIdentity.sampleUrl(media))
    private suspend fun cached()=repo.gson.fromJson(requireNotNull(repo.dao.post(repo.key(original))).payload,Post::class.java)
    private suspend fun store(admit:()->Boolean={true})=repo.storeRefreshedVideoPost(original,fresh,listOf(binding),admit)

    @Test fun aCurrentSourceReplacesOnlyTheExistingOrdinaryRowAndPreservesProtectedCopies():Unit=runBlocking {
        repo.viewed(original);repo.library.save(original,emptyList())
        val key=repo.key(original);val before=repo.dao.post(key)!!;val bookmark=repo.dao.bookmarksByKeys(listOf(key)).single();val detail=repo.dao.savedDetail(key)
        val history=repo.dao.retainedHistory(key);val ids=repo.dao.postFollowIds(key)
        assertTrue(store());val after=repo.dao.post(key)!!
        assertEquals(fresh,cached());assertEquals(before.createdAt,after.createdAt);assertEquals(before.lastViewedAt,after.lastViewedAt);assertEquals(before.followId,after.followId)
        assertEquals(ids,repo.dao.postFollowIds(key));assertEquals(bookmark,repo.dao.bookmarksByKeys(listOf(key)).single());assertEquals(detail,repo.dao.savedDetail(key));assertEquals(history,repo.dao.retainedHistory(key))
        assertEquals(original.media.single().url,repo.gson.fromJson(bookmark.payload,Post::class.java).media.single().url)
        assertNull(cached().media.single().videoFootage)
    }
    @Test fun unsubscribedDisabledAndReboundAccountsCannotAdmitALateRefresh():Unit=runBlocking {
        for(changed in listOf(binding.copy(enabled=false),binding.copy(personId=prefix+"-changed"),binding.copy(target=repo.routes.target(Platform.TIKTOK,"anotherstudio")))) {
            repo.dao.putFollow(FollowRow(changed.id,changed.target.platform.id,repo.gson.toJson(changed)))
            assertFalse(store());assertEquals(original,cached())
        }
        repo.dao.removeFollow(binding.id);assertFalse(store());assertEquals(original,cached())
    }
    @Test fun missingOrRevisedOrdinaryRecordsAreNeverRecreatedOrOverwritten():Unit=runBlocking {
        val key=repo.key(original);val row=repo.dao.post(key)!!;val changed=original.copy(body="A newer source correction.")
        repo.dao.upsertPost(row.copy(payload=repo.gson.toJson(changed)))
        assertFalse(store());assertEquals(changed,cached())
        repo.dao.removePosts(listOf(key));assertFalse(store());assertNull(repo.dao.post(key))
    }
    @Test fun admissionIsCheckedAfterWaitingForTheDatabaseTransaction():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
        val admit=AtomicBoolean(true);val calls=AtomicInteger()
        val hold=launch(Dispatchers.IO){repo.db.withTransaction {entered.complete(Unit);release.await()}}
        try {
            withTimeout(5_000){entered.await()}
            val work=async(Dispatchers.IO){started.complete(Unit);store {calls.incrementAndGet();admit.get()}}
            withTimeout(5_000){started.await()};delay(100);assertFalse(work.isCompleted);assertEquals(0,calls.get())
            admit.set(false);release.complete(Unit);hold.join();assertFalse(withTimeout(5_000){work.await()});assertEquals(original,cached())
        }finally {release.complete(Unit);hold.cancelAndJoin()}
        assertTrue(store())
    }
    @Test fun retirementBeforeCommitRollsBackTheOrdinaryReplacement():Unit=runBlocking {
        val calls=AtomicInteger();assertThrows(CancellationException::class.java){runBlocking {store {calls.incrementAndGet()==1}}}
        assertEquals(2,calls.get());assertEquals(original,cached());assertTrue(store())
    }
    @Test fun aFailedDeliveryRefreshesItsSourceButCannotBorrowTheNewFilesScenes():Unit=runBlocking {
        val bank=bank();val refreshes=AtomicInteger();val samples=AtomicInteger();val peer=peer()
        bank.refreshPost={post,admit->assertEquals(original,post);refreshes.incrementAndGet();store(admit)}
        bank.sample={media,_->samples.incrementAndGet();if(media.url==original.media.single().url)throw RecordingRangeUnavailable(403);result(media)}
        val request=VideoFootageRequest(original,peer,setOf(original.identityKey,peer.identityKey))
        bank.enrich(request,automatic=false)
        assertEquals(1,refreshes.get());assertEquals(fresh,cached());assertNull(bank.captured(original).media.single().videoFootage)
        assertEquals("A changed source ends the obsolete pair before its peer consumes bytes",1,samples.get())
        bank.enrich(request,automatic=false);assertEquals(1,refreshes.get());assertEquals(2,samples.get())
        bank.enrich(request.copy(first=cached()),automatic=false)
        assertEquals(3,samples.get());assertNotNull(bank.captured(fresh).media.single().videoFootage);assertNull(bank.captured(original).media.single().videoFootage)
        assertTrue(VideoFootage.valid(bank.captured(fresh).media.single()))
    }
    @Test fun successfulReadsDecoderFailuresAndNonRenewableHttpFailuresDoNoParentLookup():Unit=runBlocking {
        for(failure in listOf<Exception?>(null,java.io.IOException("Decoder unavailable"),RecordingRangeUnavailable(429),RecordingRangeUnavailable(503))) {
            val bank=bank();val refreshes=AtomicInteger();bank.refreshPost={_,_->refreshes.incrementAndGet();true}
            bank.sample={media,_->if(failure!=null&&media.url==original.media.single().url)throw failure;result(media)}
            bank.enrich(VideoFootageRequest(original,peer(),setOf(original.identityKey,peer().identityKey)),automatic=false)
            assertEquals(0,refreshes.get());assertEquals(original,cached())
        }
    }
    @Test fun foregroundPreemptionDuringTheParentReadKeepsRecoveryRetryable():Unit=runBlocking {
        val bank=bank();val refreshes=AtomicInteger()
        bank.sample={media,_->if(media.url==original.media.single().url)throw RecordingRangeUnavailable(200);result(media)}
        bank.refreshPost={_,admit->if(refreshes.incrementAndGet()==1)throw CancellationException("Foreground reader preemption")else store(admit)}
        bank.enrich(VideoFootageRequest(original,peer(),setOf(original.identityKey,peer().identityKey)),automatic=false)
        assertEquals(2,refreshes.get());assertEquals(fresh,cached());assertNull(bank.captured(original).media.single().videoFootage)
    }
    @Test fun clearingTheEvidenceBankRetiresEvenAnUncooperativeParentCallback():Unit=runBlocking {
        val bank=bank();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        bank.sample={_,_->throw RecordingRangeUnavailable(403)}
        bank.refreshPost={_,admit->withContext(NonCancellable){entered.complete(Unit);release.await();store(admit)}}
        val work=launch {bank.enrich(VideoFootageRequest(original,peer(),setOf(original.identityKey,peer().identityKey)),automatic=false)}
        try {withTimeout(5_000){entered.await()};bank.cleanup(true)}finally {release.complete(Unit);work.join()}
        assertEquals(original,cached());assertNull(bank.captured(original).media.single().videoFootage)
    }
    @Test fun aResourceHoldLeavesTheUnattemptedParentRecoveryRetryable():Unit=runBlocking {
        val power=context.getSystemService(android.os.PowerManager::class.java)
        val previous=power.isPowerSaveMode
        fun shell(command:String)=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use {stream->stream.readBytes().decodeToString()}
        }
        suspend fun waitMode(enabled:Boolean)=withTimeout(5_000){while(power.isPowerSaveMode!=enabled)delay(25)}
        val bank=bank();val refreshes=AtomicInteger()
        bank.preparePrefix={_,_->throw RecordingRangeUnavailable(200)}
        bank.sample={media,_->if(media.url==original.media.single().url)throw RecordingRangeUnavailable(200);result(media)}
        bank.refreshPost={_,admit->refreshes.incrementAndGet();store(admit)}
        val request=VideoFootageRequest(original,peer(),setOf(original.identityKey,peer().identityKey))
        try {
            shell("dumpsys battery unplug")
            withTimeout(5_000){while(!shell("dumpsys power").contains("mIsPowered=false"))delay(25)}
            shell("cmd power set-mode 0");waitMode(false)
            bank.enrichPrepared(request){shell("cmd power set-mode 1");waitMode(true)}
            assertEquals(0,refreshes.get());assertEquals(original,cached())
            shell("cmd power set-mode 0");waitMode(false)
            bank.enrich(request,automatic=false)
            assertEquals("The deferred parent was never tested and must remain retryable",1,refreshes.get());assertEquals(fresh,cached())
        }finally {shell("cmd power set-mode ${if(previous)1 else 0}");shell("dumpsys battery reset")}
    }
    @Test fun rotatingFailedDeliveriesShareOneParentPauseButExplicitRetryAndExpiryStillWork():Unit=runBlocking {
        var now=System.currentTimeMillis()
        val bank=bank {now};val refreshes=AtomicInteger()
        bank.sample={media,_->if(media.url.contains("tiktokcdn"))throw RecordingRangeUnavailable(200);result(media)}
        bank.refreshPost={_,admit->refreshes.incrementAndGet();if(cached()==original)store(admit)else false}
        val request=VideoFootageRequest(original,peer(),setOf(original.identityKey,peer().identityKey))
        bank.enrich(request,automatic=false);assertEquals(1,refreshes.get());assertEquals(fresh,cached())
        val current=request.copy(first=cached())
        bank.enrich(current,automatic=false);assertEquals("A different unusable delivery is still the same source post",1,refreshes.get())
        now+=5*60_000L
        bank.enrich(current,automatic=false);assertEquals(2,refreshes.get())
        bank.retryFailures();bank.enrich(current,automatic=false);assertEquals(3,refreshes.get())
        repo.gateway.configure(NetworkPolicy());repo.gateway.configure(NetworkPolicy(tor=true))
        // The old acquisition cooldown remains; a third URI isolates the
        // source-level pause and proves that the selection round trip retires it.
        val third=fresh.copy(media=listOf(fresh.media.single().copy(url=url("oThirdWorkshopFilm0123456789abcdef$suffix"))))
        bank.enrich(current.copy(first=third),automatic=false);assertEquals(4,refreshes.get())
        assertNull(bank.captured(original).media.single().videoFootage);assertNull(bank.captured(fresh).media.single().videoFootage)
    }
    /** Separate from frozen-capture replay: the actual router must acquire the
     * current edition and the sampler must measure that edition's own URL. */
    @Test fun suppliedStaleRecordingRenewsThroughTheExistingRouterAndJoinsItsMeasuredPeer():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialVideoPostRefreshLive")=="true")
        val input=File(context.filesDir,"video-post-refresh/input.json")
        require(input.isFile&&input.length() in 1..4*1024*1024)
        val packet=input.reader().use {com.google.gson.JsonParser.parseReader(it).asJsonObject}
        val retained=repo.gson.fromJson(packet.get("original"),Post::class.java)
        val other=repo.gson.fromJson(packet.get("peer"),Post::class.java)
        require(VideoPostRefresh.eligible(retained)&&other.platform==Platform.X&&other.media.size==1&&other.media.single().type=="video")
        require(retained.media.single().videoFootage==null&&other.media.single().videoFootage==null)
        require(repo.dao.allFollows().map {it.id}.toSet()==setOf(binding.id)&&repo.dao.allBookmarks().isEmpty()) {"Use a separate empty fixture device"}
        require(repo.dao.post(retained.identityKey)==null&&repo.dao.post(other.identityKey)==null) {"The live originals must not replace an existing fixture record"}
        fun follow(post:Post):Follow {
            val owner=requireNotNull(CrossPosts.originalAccount(post)).substringAfter(':')
            return Follow("$prefix-live-${post.platform.id}",repo.routes.target(post.platform,owner),"Public source",personId=prefix)
        }
        original=retained
        val firstFollow=follow(retained);val otherFollow=follow(other)
        seed(retained,firstFollow);seed(other,otherFollow)
        repo.gateway.configure(NetworkPolicy())
        val output=File(requireNotNull(context.getExternalFilesDir("validation")),"video-post-refresh-live").apply {mkdirs()}
        val bank=bank();val sample=bank.sample;val calls=mutableListOf<Map<String,Any?>>()
        bank.sample={media,source->
            val started=android.os.SystemClock.elapsedRealtime()
            try {sample(media,source).also {result->
                calls+=mapOf("urlHash" to Repository.hash(media.url),"elapsedMs" to android.os.SystemClock.elapsedRealtime()-started,"bytes" to result.fetchedBytes,"blocks" to result.fetchedBlocks,"scenes" to result.frames.scenes.size,"success" to true)
            }}catch(e:Exception){
                calls+=mapOf("urlHash" to Repository.hash(media.url),"elapsedMs" to android.os.SystemClock.elapsedRealtime()-started,"status" to (e as? RecordingRangeUnavailable)?.statusCode,"success" to false,"error" to e.javaClass.name)
                throw e
            }finally {File(output,"samples.json").writeText(repo.gson.toJson(calls))}
        }
        val request=VideoFootageRequest(retained,other,setOf(retained.identityKey,other.identityKey))
        val started=android.os.SystemClock.elapsedRealtime()
        bank.enrich(request,automatic=false)
        val current=cached()
        File(output,"source-records.json").writeText(repo.gson.toJson(mapOf("before" to retained,"current" to current,"peer" to other)))
        assertTrue("The existing router must produce an admissible whole current edition",VideoPostRefresh.accepts(retained,current))
        assertNull("A current source read is not evidence for the old file",bank.captured(retained).media.single().videoFootage)
        bank.enrich(request.copy(first=current),automatic=false)
        val editions=listOf(bank.captured(current),bank.captured(other))
        File(output,"editions.json").writeText(repo.gson.toJson(editions))
        assertTrue(editions.all {it.media.single().videoFootage!=null&&VideoFootage.valid(it.media.single())})
        val follows=listOf(firstFollow,otherFollow)
        val memberships=mapOf(current.identityKey to setOf(firstFollow.id),other.identityKey to setOf(otherFollow.id))
        val groups=CrossPosts.withVideoFootage(CrossPosts.group(editions,follows,memberships),follows,memberships,{a,b->VideoFootage.related(a,b)})
        assertEquals(1,groups.size);assertEquals(editions.map {it.identityKey}.toSet(),groups.single().posts.map {it.identityKey}.toSet())
        repo.library.save(editions.first(),emptyList(),fusionRoots=editions)
        val saved=repo.library.details(current.identityKey)!!.fusionRoots!!
        assertEquals(editions.map {it.identityKey}.toSet(),saved.map {it.identityKey}.toSet())
        assertEquals(current.media.single().url,saved.single {it.identityKey==current.identityKey}.media.single().url)
        assertNull(bank.captured(retained).media.single().videoFootage)
        File(output,"report.json").writeText(repo.gson.toJson(mapOf("elapsedMs" to android.os.SystemClock.elapsedRealtime()-started,"sourceRefreshed" to true,"oldFileUnobserved" to true,"rows" to groups.size,"savedOriginals" to saved.size)))
    }
}
