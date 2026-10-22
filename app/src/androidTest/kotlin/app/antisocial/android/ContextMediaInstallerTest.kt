/* AntiSocial, GPL-3.0-or-later. Explicit opt-in, isolated-device integration fixture. */
package app.antisocial.android

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Exercises the actual signed catalog, public NetworkGateway download,
 * activation, manager IPC decoding/provenance, disable and removal. No forged
 * install marker, credentials, or acquired worker-only model fixture is used. */
@RunWith(AndroidJUnit4::class)
class ContextMediaInstallerTest {
    @Test fun verifiedEnglishDownloadActivatesAndManagerReturnsSafeAttributedOcr(): Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialContextMediaInstall")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val repo=(context as AntiSocialApplication).repo;repo.awaitReady()
        val manager=repo.mediaTools
        val ai=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE)
        val media=context.getSharedPreferences("context-media",Context.MODE_PRIVATE)
        val oldAi=ai.all.toMap();val oldMedia=media.all.toMap()
        val oldPolicy=repo.gateway.currentPolicy();val oldTor=repo.gateway.currentPolicy(NetworkRoute.TOR)
        val file=File(context.cacheDir,"context-manager-install-test.png")
        val started=System.currentTimeMillis()
        try {
            repo.ai.enabled(false);repo.gateway.configure(NetworkPolicy());repo.ai.enabled(true)
            // This explicit development test is restricted to an isolated
            // fixture device. Clear only its English optional tool so the
            // successful path really downloads and verifies public bytes.
            manager.remove(ContextMediaKind.OCR,"eng")
            assertFalse(manager.hasDownload(ContextMediaKind.OCR,"eng"))
            val image=Bitmap.createBitmap(1400,400,Bitmap.Config.ARGB_8888)
            try {
                Canvas(image).apply {
                    drawColor(Color.WHITE)
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=60f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
                    drawText("Local private reader.",60f,140f,paint)
                    drawText("Keep useful information.",60f,230f,paint)
                }
                file.outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
            } finally {image.recycle()}
            val expectedHash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
            withTimeout(180_000){manager.install(ContextMediaKind.OCR,"eng")}
            assertTrue(manager.state.value.ocrEnabled);assertTrue("eng" in manager.state.value.languages)
            assertTrue(manager.hasDownload(ContextMediaKind.OCR,"eng"))
            val result=withTimeout(60_000){manager.ocr(file,"eng")}
            assertTrue(result.text.contains("Local private reader."));assertTrue(result.text.contains("Keep useful information."))
            assertTrue(result.meanConfidence>=80);assertFalse(result.uncertain);assertFalse(result.scaled)
            assertEquals(expectedHash,result.sourceSha256);assertTrue(result.engine.isNotBlank());assertTrue(result.lines.size>=2)
            assertEquals(expectedHash,result.copy(truncated=true).sourceSha256)
            manager.activate(ContextMediaKind.OCR,false,"eng")
            assertFalse(manager.state.value.ocrEnabled);assertTrue(manager.hasDownload(ContextMediaKind.OCR,"eng"))
            manager.remove(ContextMediaKind.OCR,"eng")
            assertFalse(manager.hasDownload(ContextMediaKind.OCR,"eng"))
            File(context.filesDir,"context-media-manager-install-evidence.json").writeText(Gson().toJson(mapOf(
                "passed" to true,"downloadBytes" to manager.downloadBytes(ContextMediaKind.OCR,"eng"),
                "activated" to true,"disabledWithDownloadKept" to true,"removed" to true,
                "sourceSha256" to result.sourceSha256,"text" to result.text,"meanConfidence" to result.meanConfidence,
                "safeConstructedDefaults" to true,"elapsedMs" to System.currentTimeMillis()-started
            )))
        } finally {
            withContext(NonCancellable) {
                try {manager.remove(ContextMediaKind.OCR,"eng")} finally {
                    file.delete();restore(media,oldMedia);restore(ai,oldAi)
                    repo.gateway.configure(oldPolicy);repo.gateway.configureTor(oldTor)
                    manager.refresh();repo.ai.refresh()
                }
            }
        }
    }
    private fun restore(preferences:SharedPreferences,values:Map<String,*>) {
        val edit=preferences.edit().clear()
        values.forEach {(key,value)->when(value) {
            is Boolean->edit.putBoolean(key,value);is String->edit.putString(key,value);is Int->edit.putInt(key,value)
            is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)
            is Set<*>->edit.putStringSet(key,value.map {it as String}.toSet())
            else->error("Unsupported fixture preference")
        }}
        check(edit.commit())
    }
}
