package app.antisocial.android

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.IdentityDisplay
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HardeningUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Before fun isolated() {Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")}
    @Test fun clipboardPreservesValuesAndMarksOnlySensitiveCopies() {
        val signed="https://media.example/image.jpg?token=fixture-only"
        val clip=ReaderClipboard.clip("Signed image link",signed)
        assertEquals(signed,clip.getItemAt(0).text.toString());assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        val ordinary=ReaderClipboard.clip("Post link","https://x.com/workshop/status/123")
        assertFalse(ordinary.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") ?: false)
        assertTrue(ReaderClipboard.clip("Private export","fixture-only",sensitive=true).description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }
    @Test fun obscuredTouchesCannotConfirmButNormalTouchesAndAccessibilityStillWork() {
        var presses=0;var view:View?=null
        ui.activity.setContent {val current=LocalView.current;SideEffect {view=current};MaterialTheme {Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Button(onClick={presses++},modifier=Modifier.rejectObscuredTouches().testTag("Protected confirm")){Text("Save")}}}}
        ui.onNodeWithTag("Protected confirm").assertIsDisplayed()
        fun tap(flags:Int) {
            val center=ui.onNodeWithTag("Protected confirm").fetchSemanticsNode().boundsInRoot.center
            ui.runOnIdle {
                val at=android.os.SystemClock.uptimeMillis()
                val properties=arrayOf(MotionEvent.PointerProperties().apply {id=0;toolType=MotionEvent.TOOL_TYPE_FINGER})
                val coordinates=arrayOf(MotionEvent.PointerCoords().apply {x=center.x;y=center.y;pressure=1f;size=1f})
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP))MotionEvent.obtain(at,at+if(action==MotionEvent.ACTION_UP)20 else 0,action,1,properties,coordinates,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,flags).also {event->try {view!!.dispatchTouchEvent(event)}finally {event.recycle()}}
            }
        }
        tap(MotionEvent.FLAG_WINDOW_IS_OBSCURED);ui.runOnIdle {assertEquals(0,presses)}
        if(android.os.Build.VERSION.SDK_INT>=29){tap(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED);ui.runOnIdle {assertEquals(0,presses)}}
        tap(0);ui.runOnIdle {assertEquals(1,presses)}
        ui.onNodeWithTag("Protected confirm").performClick();ui.runOnIdle {assertEquals(2,presses)}
    }
    @Test fun nativeIdentityPresentationRetainsRtlNamesAndShowsAsciiDestination() {
        ui.activity.setContent {MaterialTheme {Surface {Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Public source",style=MaterialTheme.typography.headlineMedium)
            MentionText("مكتبة העברית",accountLabel=true,networkAllowed=false)
            MentionText("p\u0430ypal",accountLabel=true,networkAllowed=false)
            Text(IdentityDisplay.domain("https://\u0430pple.com/"))
            Text("The post and its original source remain unchanged.")
        }}}}
        ui.onNodeWithText("مكتبة העברית").assertIsDisplayed();ui.onNodeWithText("p\u0430ypal ⚠").assertIsDisplayed();ui.onNodeWithText("xn--pple-43d.com").assertIsDisplayed()
        ui.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val file=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"sandbox/identity-native.png").apply {parentFile!!.mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
