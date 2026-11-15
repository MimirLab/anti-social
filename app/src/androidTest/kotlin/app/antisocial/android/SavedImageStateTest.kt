/* anti social, GPL-3.0-or-later. Local fixture files only. */
package app.antisocial.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** A delayed local decode must not claim that an existing file is missing. */
@RunWith(AndroidJUnit4::class)
class SavedImageStateTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val files=mutableListOf<File>()
    private val gates=mutableListOf<CompletableDeferred<Unit>>()

    @After fun cleanup() {
        gates.forEach {it.cancel()}
        ui.runOnIdle {ui.activity.setContent {Text("Saved image fixture complete")}}
        files.forEach {it.delete()}
    }

    private fun image(color:Int=Color.BLUE):File {
        val file=File.createTempFile("saved-image-state-",".png",ui.activity.cacheDir)
        val bitmap=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888)
        try {bitmap.eraseColor(color);file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
        finally {bitmap.recycle()}
        files+=file;return file
    }

    private fun evidence(name:String) {
        ui.waitForIdle();android.os.SystemClock.sleep(200)
        val dir=File(ui.activity.getExternalFilesDir("validation"),"saved-image-state").apply {mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
        File(dir,"$name-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
    }

    @Test fun anExistingSavedImageShowsLoadingDuringDecodeAndKeepsItsBounds() {
        runBlocking {repo.awaitReady()}
        val file=image();val bytes=file.readBytes()
        val release=CompletableDeferred<Unit>().also {gates+=it};val calls=AtomicInteger()
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {
            LocalCopyImage(repo,file,"Offline image fixture",Modifier.width(280.dp).heightIn(min=100.dp,max=420.dp).testTag("Saved image fixture"),decode={input->
                assertEquals(file.path,input.path);calls.incrementAndGet();release.await();BitmapFactory.decodeFile(input.path)
            })
        }}}
        ui.waitUntil(10_000){calls.get()==1}
        evidence("pending-existing-file")
        ui.onNodeWithText("Image not downloaded").assertDoesNotExist()
        ui.onNodeWithText("Loading saved image").assertIsDisplayed()
        val before=ui.onNodeWithTag("Saved image fixture").fetchSemanticsNode().boundsInRoot
        release.complete(Unit)
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Offline image fixture").fetchSemanticsNodes().size==1}
        ui.onNodeWithContentDescription("Offline image fixture").assertIsDisplayed()
        val after=ui.onNodeWithTag("Saved image fixture").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(before.height-after.height)<=2f)
        assertTrue(kotlin.math.abs(before.width-after.width)<=2f)
        assertArrayEquals(bytes,file.readBytes());assertEquals(1,calls.get())
        evidence("decoded-existing-file")
    }

    @Test fun fileLookupPendingAndMissingAreDistinctWithoutStartingADecode() {
        runBlocking {repo.awaitReady()}
        val file=image();val bytes=file.readBytes();val selected=mutableStateOf<File?>(null)
        val pending=mutableStateOf(true);val calls=AtomicInteger()
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {
            LocalCopyImage(repo,selected.value,"Located image fixture",Modifier.width(280.dp).heightIn(min=100.dp,max=420.dp).testTag("Saved image fixture"),
                lookupPending=pending.value,decode={calls.incrementAndGet();BitmapFactory.decodeFile(it.path)})
        }}}
        ui.onNodeWithText("Loading saved image").assertIsDisplayed()
        ui.onNodeWithText("Image not downloaded").assertDoesNotExist();assertEquals(0,calls.get())
        val before=ui.onNodeWithTag("Saved image fixture").fetchSemanticsNode().boundsInRoot
        ui.runOnIdle {pending.value=false}
        ui.onNodeWithText("Image not downloaded").assertIsDisplayed();assertEquals(0,calls.get())
        assertEquals(before,ui.onNodeWithTag("Saved image fixture").fetchSemanticsNode().boundsInRoot)
        evidence("confirmed-missing-file")
        ui.runOnIdle {selected.value=file}
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Located image fixture").fetchSemanticsNodes().size==1}
        assertEquals(before,ui.onNodeWithTag("Saved image fixture").fetchSemanticsNode().boundsInRoot)
        assertEquals(1,calls.get());assertArrayEquals(bytes,file.readBytes())
    }

    @Test fun aFailedLocalDecodeCanRetryWithoutCallingTheFileMissing() {
        runBlocking {repo.awaitReady()}
        val file=image();val bytes=file.readBytes();val calls=AtomicInteger()
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {
            LocalCopyImage(repo,file,"Retried image fixture",Modifier.width(280.dp).heightIn(min=100.dp,max=420.dp).testTag("Saved image fixture"),decode={
                if(calls.incrementAndGet()==1)throw IOException("Synthetic local decoder failure")
                BitmapFactory.decodeFile(it.path)
            })
        }}}
        ui.onNodeWithText("Saved image could not be read").assertIsDisplayed()
        ui.onNodeWithText("Image not downloaded").assertDoesNotExist()
        val before=ui.onNodeWithTag("Saved image fixture").fetchSemanticsNode().boundsInRoot
        evidence("failed-existing-file")
        ui.onNodeWithText("Retry saved image").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Retried image fixture").fetchSemanticsNodes().size==1}
        assertEquals(before,ui.onNodeWithTag("Saved image fixture").fetchSemanticsNode().boundsInRoot)
        assertEquals(2,calls.get());assertArrayEquals(bytes,file.readBytes())
        evidence("retry-existing-file")
    }

    @Test fun aLateCancelledDecodeCannotReplaceTheCurrentSourcesImage() {
        runBlocking {repo.awaitReady()}
        val old=image(Color.RED);val current=image(Color.BLUE)
        val selected=mutableStateOf(old);val release=CompletableDeferred<Unit>().also {gates+=it}
        val oldStarted=AtomicInteger();val oldReturned=AtomicInteger()
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {
            LocalCopyImage(repo,selected.value,"Current source image",Modifier.width(280.dp).heightIn(min=100.dp,max=420.dp).testTag("Saved image fixture"),decode={input->
                if(input==old) {
                    oldStarted.incrementAndGet()
                    withContext(NonCancellable){release.await()}
                    oldReturned.incrementAndGet()
                }
                BitmapFactory.decodeFile(input.path)
            })
        }}}
        ui.waitUntil(10_000){oldStarted.get()==1}
        ui.runOnIdle {selected.value=current}
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Current source image").fetchSemanticsNodes().size==1}
        fun assertCurrentPixels() {
            val image=ui.onNodeWithContentDescription("Current source image").captureToImage().asAndroidBitmap()
            assertEquals(Color.BLUE,image.getPixel(image.width/2,image.height/2))
        }
        assertCurrentPixels();release.complete(Unit)
        ui.waitUntil(10_000){oldReturned.get()==1}
        ui.waitForIdle();assertCurrentPixels()
        evidence("current-source-after-cancelled-decode")
    }
}
