/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Local fixture files and manifests exercise real storage and native playback.
 * No fixture depends on a public media host or weakens NetworkGateway's policy.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedMediaTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val prefix=System.nanoTime().toString().takeLast(12)
    private val host="saved-media-$prefix.invalid"
    private val posts=mutableListOf<Post>()
    private val mediaKeys=mutableSetOf<String>()
    private val events=CopyOnWriteArrayList<NetworkEvent>()
    private lateinit var preferences: String
    private lateinit var originalPolicy: NetworkPolicy
    private var originalEvents: ((NetworkEvent)->Unit)?=null
    private lateinit var fixtureDir: File

    @Before fun isolate()=runBlocking {
        repo.awaitReady()
        preferences=repo.prefs.export().toString();originalPolicy=repo.gateway.currentPolicy();originalEvents=repo.gateway.onNetworkEvent
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.videoDownloadWifiOnly=false
        fixtureDir=File(repo.context.cacheDir,"saved-media-fixtures-$prefix").apply {mkdirs()}
        ui.runOnIdle {ui.activity.setContent {Text("Saved media fixture")}}
        repo.gateway.onNetworkEvent={event->if(event.host==host)events+=event;originalEvents?.invoke(event)}
        // Cached files must be usable even when the selected network cannot connect.
        repo.gateway.configure(NetworkPolicy(tor=true))
    }
    @After fun restore()=runBlocking {
        ui.runOnIdle {ui.activity.setContent {Text("Saved media fixture complete")}}
        for(post in posts) {
            val key=repo.key(post);repo.library.cancel(key)
            repo.dao.deleteDocuments(key,"saved");repo.dao.deleteDocuments(key,"history")
            repo.dao.removeBookmark(key);repo.dao.removePosts(listOf(key))
            for(table in listOf("seen","history"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }
        for(key in mediaKeys) {
            repo.dao.media(key)?.let {File(it.path).delete()}
            repo.dao.removeMedia(listOf(key));File(repo.context.filesDir,"saved/$key").delete()
            File(repo.context.cacheDir,"content/$key").delete()
        }
        fixtureDir.deleteRecursively()
        repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
        repo.prefs.restore(JSONObject(preferences));repo.gateway.onNetworkEvent=originalEvents
        repo.applyNetworkSettings();repo.gateway.configure(originalPolicy);scheduleRefresh(repo.context,repo.prefs)
        repo.library.notifyChanged();repo.updateUsage()
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }
    private fun post(vararg media: Media): Post {
        val id="96$prefix${posts.size+1}"
        return Post(id,Platform.X,"https://x.com/antisocial_saved_media_fixture/status/$id","Public fixture author","Durable selected media $id",media=media.toList(),publishedAt=1_780_000_000_000L).also {posts+=it}
    }
    private suspend fun manifest(post: Post,status: String="QUEUED"): String {
        val owner=repo.key(post);val token=UUID.randomUUID().toString();val now=System.currentTimeMillis()
        val assets=post.media.map {SelectedAsset(Repository.hash(it.url),it.url,post.canonicalUrl,it.type,postKey=owner)}
        repo.dao.putBookmark(BookmarkRow(owner,post.platform.id,repo.gson.toJson(post),now))
        repo.dao.putSavedDetail(SavedDetailRow(owner,repo.gson.toJson(SavedCopyDetails(token,now,selectedAssets=assets))))
        assets.forEach {a->mediaKeys+=a.key;repo.dao.putSavedAsset(SavedAssetRow(owner,a.key,token,a.url,a.sourceUrl,a.type,status,updatedAt=now))}
        return token
    }
    private suspend fun local(url: String,bytes: ByteArray,saved: Boolean=true): File {
        val key=Repository.hash(url);mediaKeys+=key
        val file=if(saved)File(repo.context.filesDir,"saved/$key")else File(repo.context.cacheDir,"content/$key")
        file.apply {parentFile?.mkdirs();writeBytes(bytes)}
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis(),saved=saved));return file
    }
    private fun videoBytes()=InstrumentationRegistry.getInstrumentation().context.assets.open("video-fixture.mp4").use {it.readBytes()}
    private fun imageBytes(): ByteArray {
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.GREEN)}
        return java.io.ByteArrayOutputStream().use {out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();out.toByteArray()}
    }
    private suspend fun asset(post: Post,url: String)=requireNotNull(repo.dao.asset(repo.key(post),Repository.hash(url)))
    private fun evidence(name: String) {
        ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"saved-media").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun selectedLocalImageAndVideoCompleteWithoutPreparingANetworkConnection()=runBlocking {
        val image="https://$host/selected.png";val video="https://$host/selected.mp4"
        val post=post(Media(image),Media(video,"video"));val token=manifest(post)
        val imageFile=local(image,imageBytes());val videoFile=local(video,videoBytes())
        assertTrue(SavedMediaDownloads(repo).run(repo.key(post),token))
        for(url in listOf(image,video)) {
            val row=asset(post,url);assertEquals("COMPLETE",row.status)
            assertEquals(requireNotNull(repo.library.assetFile(url)).length(),row.bytes)
        }
        assertTrue(events.isEmpty());assertTrue(imageFile.isFile);assertTrue(videoFile.isFile)
        assertEquals(2,requireNotNull(repo.library.copy(repo.key(post))).details!!.selectedAssets.size)
    }
    @Test fun viewedImagesBecomeProtectedCopiesWithoutTorOrRefetching()=runBlocking {
        val url="https://$host/already-viewed.png";val post=post(Media(url))
        val bytes=imageBytes();val ordinary=local(url,bytes,saved=false)
        repo.lease(Repository.hash(url),true)
        try {
            repo.library.save(post,repo.library.availableAssets(post))
            ui.waitUntil(20_000){runBlocking {asset(post,url).status=="COMPLETE"}}
            val copy=requireNotNull(repo.library.assetFile(url))
            assertEquals("COMPLETE",asset(post,url).status);assertArrayEquals(bytes,copy.readBytes())
            assertNotEquals(ordinary.path,copy.path);assertTrue(ordinary.isFile)
            assertTrue(requireNotNull(repo.dao.media(Repository.hash(url))).saved)
            assertTrue(events.isEmpty())
        }finally {repo.lease(Repository.hash(url),false)}
        repo.cleanup(clearAll=true)
        assertFalse(ordinary.exists());assertArrayEquals(bytes,requireNotNull(repo.library.assetFile(url)).readBytes())
        assertEquals(2,repo.images.load(url,post.canonicalUrl,256,localOnly=true).width)
        assertTrue(events.isEmpty())
    }
    @Test fun unsupportedPlaylistsAreTerminalWithoutAnyNetworkAttempt()=runBlocking {
        val urls=listOf("https://$host/master.m3u8?token=fixture","https://$host/manifest.mpd")
        val post=post(*urls.map {Media(it,"video")}.toTypedArray());val token=manifest(post)
        assertTrue(SavedMediaDownloads(repo).run(repo.key(post),token))
        urls.forEach {url->assertEquals("UNSUPPORTED",asset(post,url).status);assertNull(repo.library.assetFile(url))}
        assertTrue(events.isEmpty())
    }
    @Test fun extensionlessAdaptiveStreamsAreRejectedByTheActualDownloadWorkerBeforeConnecting()=runBlocking {
        val hls="https://$host/opaque-hls?token=fixture";val dash="https://$host/opaque-dash?token=fixture"
        val post=post(Media(hls,"video",mimeType=VideoFormats.HLS),Media(dash,"video",mimeType=VideoFormats.DASH))
        repo.library.save(post,repo.library.availableAssets(post))
        ui.waitUntil(20_000){runBlocking {listOf(hls,dash).all {asset(post,it).status=="UNSUPPORTED"}}}
        listOf(hls,dash).forEach {assertNull(repo.library.assetFile(it))}
        assertTrue(events.isEmpty())
    }
    @Test fun aFullVideoAllowanceRejectsANewVideoBeforeConnecting()=runBlocking {
        repo.prefs.videoDownloadLimitMb=1
        val existing="https://$host/already-saved.mp4";val next="https://$host/next.mp4"
        val post=post(Media(existing,"video"),Media(next,"video"));val token=manifest(post)
        val retained=local(existing,videoBytes().copyOf(1024*1024))
        assertTrue(SavedMediaDownloads(repo).run(repo.key(post),token))
        assertEquals("COMPLETE",asset(post,existing).status);assertEquals("FAILED",asset(post,next).status)
        assertTrue(asset(post,next).error.orEmpty().contains("allowance"))
        assertTrue(retained.isFile);assertEquals(1024L*1024,retained.length());assertNull(repo.library.assetFile(next));assertTrue(events.isEmpty())
    }
    @Test fun selectedTrafficWaitsForTorWithoutLosingItsQueuedIntent()=runBlocking {
        val url="https://$host/tor-waiting.png";val post=post(Media(url));val token=manifest(post)
        assertFalse(SavedMediaDownloads(repo).run(repo.key(post),token))
        val row=asset(post,url);assertEquals("QUEUED",row.status);assertEquals("Waiting for Tor",row.error)
        assertTrue(repo.library.validToken(repo.key(post),token));assertNull(repo.library.assetFile(url));assertTrue(events.isEmpty())
    }
    @Test fun mediaValidationRejectsSuccessfulLoginPagesAndAcceptsActualImageAndVideoBytes()=runBlocking {
        val bad=listOf("<!DOCTYPE html><html><body>Sign in to see this media</body></html>","{\"error\":\"login_required\"}","Please log in to continue")
        bad.forEachIndexed {index,text->
            val file=File(fixtureDir,"not-media-$index").apply {writeText(text)}
            assertNotNull(SavedAssetValidation.problem(file,"image"));assertNotNull(SavedAssetValidation.problem(file,"video"))
        }
        val png=File(fixtureDir,"actual.png").apply {writeBytes(imageBytes())}
        val mp4=File(fixtureDir,"actual.mp4").apply {writeBytes(videoBytes())}
        assertNull(SavedAssetValidation.problem(png,"image"));assertNull(SavedAssetValidation.problem(mp4,"video"))
        val playlist=File(fixtureDir,"response-without-extension").apply {writeText("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nclip.m3u8")}
        assertTrue(SavedAssetValidation.problem(playlist,"video").orEmpty().contains("playlists"))
        // Previously cached 200 HTML must not be certified as a completed copy.
        val image="https://$host/login-image.png";val video="https://$host/login-video.mp4"
        val post=post(Media(image),Media(video,"video"));val token=manifest(post)
        local(image,bad.first().toByteArray());local(video,bad.first().toByteArray())
        assertTrue(SavedMediaDownloads(repo).run(repo.key(post),token))
        assertEquals("UNSUPPORTED",asset(post,image).status);assertEquals("UNSUPPORTED",asset(post,video).status);assertTrue(events.isEmpty())
    }
    @Test fun staleAndRemovedOwnersCannotPromoteAnAlreadyTransferredFile()=runBlocking {
        val url="https://$host/delayed.png";val post=post(Media(url));val oldToken=manifest(post)
        repo.library.save(post,repo.library.availableAssets(post));repo.library.cancel(repo.key(post))
        val newerToken=requireNotNull(repo.library.details(repo.key(post))).token
        assertNotEquals(oldToken,newerToken)
        val temp=File(fixtureDir,"delayed.part").apply {writeBytes(imageBytes())}
        assertNull(repo.library.commitAsset(repo.key(post),oldToken,url,temp))
        assertTrue(temp.isFile);assertNull(repo.library.assetFile(url));assertTrue(SavedMediaDownloads(repo).run(repo.key(post),oldToken))
        // Removal through the public action must invalidate an otherwise current manifest.
        repo.bookmark(post,false)
        assertNull(repo.library.commitAsset(repo.key(post),newerToken,url,temp))
        assertTrue(temp.isFile);assertNull(repo.dao.media(Repository.hash(url)));assertTrue(events.isEmpty())
    }
    @Test fun strictLocalMediaShowsMissingFilesAndPlaysTheDownloadedVideoWhileFailClosed()=runBlocking {
        val image="https://$host/local.png";val video="https://$host/local.mp4";val missing="https://$host/missing.mp4"
        val post=post(Media(image),Media(video,"video"),Media(missing,"video"));manifest(post)
        val imageFile=local(image,imageBytes());val videoFile=local(video,videoBytes())
        val bitmap=repo.images.load(image,post.canonicalUrl,256,localOnly=true)
        assertEquals(2,bitmap.width);assertEquals(android.graphics.Color.GREEN,bitmap.getPixel(0,0))
        try {repo.images.load("https://$host/missing.png",post.canonicalUrl,256,localOnly=true);fail("Missing local image must not fetch a replacement")}catch(e: IllegalStateException){assertTrue(e.message.orEmpty().contains("not downloaded"))}
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column {
            VideoCard(post.media[1],post,repo,localOnly=true,localFile=videoFile)
            VideoCard(post.media[2],post,repo,localOnly=true)
        }}}}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Saved video unavailable").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Saved video unavailable").assertIsDisplayed();ui.onNodeWithText("Play video").performClick()
        ui.waitUntil(30_000){ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Video ready")).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Video player").assertIsDisplayed();assertTrue(imageFile.isFile);assertTrue(events.isEmpty())
        fun nativePlayer(): androidx.media3.common.Player? {
            fun find(view: android.view.View): androidx.media3.ui.PlayerView? {
                if(view is androidx.media3.ui.PlayerView)return view
                if(view is android.view.ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))?.let {return it}
                return null
            }
            return find(ui.activity.window.decorView)?.player
        }
        var playing: androidx.media3.common.Player?=null
        ui.runOnIdle {playing=nativePlayer();assertNotNull(playing)}
        repeat(4) {repo.library.notifyChanged();ui.waitForIdle();ui.runOnIdle {assertSame("Unrelated library revisions must keep the same native player",playing,nativePlayer())}}
        evidence("strict-local-playback-and-missing-file")
    }
    @Test fun temporaryVideoBudgetAndRetentionChangeIndependentlyAtRuntime() {
        val context=object: ContextWrapper(repo.context) {
            override fun getApplicationContext(): Context=this
            override fun getCacheDir(): File {
                val directory=File(this@SavedMediaTest.fixtureDir,"isolated-cache")
                directory.mkdirs();return directory
            }
            override fun getDatabasePath(name: String): File {
                val database=File(this@SavedMediaTest.fixtureDir,"isolated-databases/$name")
                database.parentFile?.mkdirs();return database
            }
        }
        repo.prefs.cacheMb=50;repo.prefs.viewedDays=365;repo.prefs.videoCacheMb=2;repo.prefs.videoCacheDays=7
        val videos=VideoCache(context,repo.prefs)
        fun put(key: String,bytes: Int) {
            val span=videos.cache.startReadWrite(key,0,bytes.toLong())
            try {val file=videos.cache.startFile(key,0,bytes.toLong());file.writeBytes(ByteArray(bytes){1});videos.cache.commitFile(file,bytes.toLong())}finally {videos.cache.releaseHoleSpan(span)}
        }
        try {
            put("first",768*1024);put("second",768*1024);assertEquals(1536L*1024,videos.bytes())
            repo.prefs.videoCacheMb=1;put("third",128*1024)
            assertTrue(videos.bytes()<=1024L*1024);assertTrue(videos.cache.getCachedSpans("first").isEmpty())
            val later=System.currentTimeMillis()+2*CachePolicy.DAY
            videos.cleanup(later);assertTrue(videos.bytes()>0)
            repo.prefs.videoCacheDays=1;videos.cleanup(later);assertEquals(0L,videos.bytes())
            repo.prefs.videoCacheMb=0;put("disabled",32*1024);assertEquals(0L,videos.bytes())
            assertEquals(50,repo.prefs.cacheMb);assertEquals(365,repo.prefs.viewedDays)
            repo.prefs.validate(repo.prefs.export())
        }finally {videos.release()}
    }
}
