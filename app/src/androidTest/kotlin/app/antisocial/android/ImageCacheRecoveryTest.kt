/* anti social, GPL-3.0-or-later. Owned response fixtures; no live social endpoints. */
package app.antisocial.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ImageCacheRecoveryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val url="https://media-cache-fixture.example/photo.png"
    private val key=Repository.hash(url)
    private lateinit var policy:NetworkPolicy
    private lateinit var clients:MutableMap<Pair<NetworkRoute,NetworkPolicy>,OkHttpClient>
    private lateinit var old:OkHttpClient
    private lateinit var fixture:OkHttpClient
    private val reply=AtomicReference("<html>A temporary connection challenge</html>".toByteArray())
    private val requests=AtomicInteger()
    @Before fun prepare()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        repo.awaitReady();policy=repo.gateway.currentPolicy();repo.gateway.configure(NetworkPolicy())
        repo.images.clear();repo.dao.media(key)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(key))
        // Keep the actual Gateway filtering, purposes, coroutine execution and
        // media byte limits. Only the final HTTP response is owned by this test.
        val field=NetworkGateway::class.java.getDeclaredField("clients").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        clients=field.get(repo.gateway) as MutableMap<Pair<NetworkRoute,NetworkPolicy>,OkHttpClient>
        old=repo.gateway.client()
        fixture=old.newBuilder().addInterceptor {chain->
            if(chain.request().url.host!="media-cache-fixture.example")chain.proceed(chain.request())else {
                requests.incrementAndGet()
                // A successful HTTP status is not proof of a decodable image.
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Owned response")
                    .body(reply.get().toResponseBody("application/octet-stream".toMediaType())).build()
            }
        }.build()
        synchronized(repo.gateway){clients[NetworkRoute.DEFAULT to NetworkPolicy()]=fixture}
    }
    @After fun restore()=runBlocking {
        if(!::fixture.isInitialized)return@runBlocking
        ui.runOnIdle {ui.activity.setContent {}}
        synchronized(repo.gateway){if(clients[NetworkRoute.DEFAULT to NetworkPolicy()]===fixture)clients[NetworkRoute.DEFAULT to NetworkPolicy()]=old}
        repo.images.clear();repo.dao.media(key)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(key));repo.gateway.configure(policy)
    }
    private fun png():ByteArray {
        val bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(30,100,170))
        return try {ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()}finally {bitmap.recycle()}
    }
    @Test fun failedImageRetriesFetchFreshBytesWithoutClearingSettingsOrRestarting() {
        val preferences=repo.prefs.export().toString()
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {RemoteImage(url,url,repo,Modifier.size(160.dp),"Recovered image")}}}
        ui.waitUntil(15_000){ui.onAllNodesWithContentDescription("Retry image").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(1,requests.get())
        assertNull("A connection challenge must not poison the persistent image cache",runBlocking {repo.dao.media(key)})
        reply.set(png());ui.onNodeWithContentDescription("Retry image").performClick()
        ui.waitUntil(15_000){ui.onAllNodesWithContentDescription("Recovered image").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(2,requests.get());assertEquals(preferences,repo.prefs.export().toString())
        val output=File(ui.activity.getExternalFilesDir("validation"),"cache-recovery").apply {mkdirs()}
        ui.onRoot().captureToImage().asAndroidBitmap().let {bitmap->File(output,"recovered-image.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
    }
    @Test fun aCorruptCopyLeftByAnEarlierRunIsRemovedAndNextLoadRecovers()=runBlocking {
        val file=File(ui.activity.cacheDir,"content/$key").apply {parentFile!!.mkdirs();writeBytes(reply.get())}
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis()))
        assertTrue(runCatching {ImageLoader(repo).load(url,url,512)}.isFailure)
        assertFalse(file.exists());assertNull(repo.dao.media(key));assertEquals(0,requests.get())
        reply.set(png())
        val bitmap=ImageLoader(repo).load(url,url,512)
        assertEquals(64,bitmap.width);assertEquals(48,bitmap.height);assertEquals(1,requests.get())
    }
    @Test fun refreshingRetriesAVisibleErrorOnceAfterTheConnectionRecovers() {
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {RemoteImage(url,url,repo,Modifier.size(160.dp),"Refreshed image")}}}
        ui.waitUntil(15_000){ui.onAllNodesWithContentDescription("Retry image").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(1,requests.get());reply.set(png())
        runBlocking {repo.refresh(personId="image-retry-fixture-with-no-subscriptions")}
        ui.waitUntil(15_000){ui.onAllNodesWithContentDescription("Refreshed image").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(2,requests.get())
        runBlocking {repo.refresh(personId="image-retry-fixture-with-no-subscriptions")}
        ui.waitForIdle();assertEquals("Successful visible images do not refetch on refresh",2,requests.get())
    }
    @Test fun protectedOfflineBytesAreNeverDeletedByADecoderFailure()=runBlocking {
        val file=File(ui.activity.filesDir,"saved/$key").apply {parentFile!!.mkdirs();writeBytes(reply.get())}
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis(),saved=true))
        assertTrue(runCatching {repo.images.load(url,url,512)}.isFailure)
        assertTrue(file.isFile);assertArrayEquals(reply.get(),file.readBytes());assertTrue(repo.dao.media(key)!!.saved)
        assertEquals(0,requests.get())
    }
    @Test fun removingDisposableCacheFilesCanRebuildThemWithoutReinstalling()=runBlocking {
        reply.set(png());repo.images.load(url,url,512);assertEquals(1,requests.get())
        val cached=repo.dao.media(key)!!;File(cached.path).delete();repo.images.clear()
        // Android can remove ordinary cache bytes while Room still has a row.
        val image=ImageLoader(repo).load(url,url,512)
        assertEquals(64,image.width);assertEquals(2,requests.get());assertTrue(File(repo.dao.media(key)!!.path).isFile)
    }
    @Test fun anOlderFailedDecodeCannotDeleteANewerAtomicReplacement()=runBlocking {
        val file=File(ui.activity.cacheDir,"content/$key").apply {parentFile!!.mkdirs();writeBytes(reply.get())}
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis()))
        val oldInput=android.system.Os.stat(file.path)
        Repository.atomic(file,png())
        repo.discardFailedImage(url,file,oldInput)
        assertTrue(file.isFile);assertNotNull(repo.dao.media(key))
        assertEquals(64,repo.images.load(url,url,512).width);assertEquals(0,requests.get())
    }
}
