package app.antisocial.android

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** Untrusted playlist references never gain access to local URI schemes. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoTransportTest {
    @Test fun remoteMediaCannotReadLocalFilesEvenThroughTheOrdinaryVideoCache() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo
        val gateway=NetworkGateway(UrlCleaner(JsonObject().apply {add("providers",JsonObject())}),object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})
        val events=java.util.concurrent.atomic.AtomicInteger();gateway.onNetworkEvent={events.incrementAndGet()}
        val file=File(context.cacheDir,"private-video-${System.nanoTime()}.mp4").apply {writeText("Private bytes")}
        try {
            for(cache in listOf(null,repo.videoCache.cache)) {
                val factory=videoNetworkDataSource(gateway.client(),"https://social.example/post/1",cache)
                for(uri in listOf(Uri.fromFile(file),Uri.parse("content://app.antisocial.android/private"),Uri.parse("asset:///secret"),Uri.parse("data:video/mp4;base64,AAAA"))) {
                    val source=factory.createDataSource()
                    try {source.open(DataSpec(uri));fail("Remote media must reject $uri")}catch(e: IOException){assertTrue(e.message.orEmpty().contains("unsupported media address"))}finally {source.close()}
                }
            }
            assertEquals(0,events.get());assertEquals(0L,gateway.protectionStats().checked)
        }finally {file.delete()}
    }
}
