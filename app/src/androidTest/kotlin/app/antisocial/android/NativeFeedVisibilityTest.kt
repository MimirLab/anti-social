/* anti social, GPL-3.0-or-later. Qualification must observe the foreground reader. */
package app.antisocial.android

import android.app.Activity
import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A semantic node can exist behind System UI. Keep this check outside the
 * reader and before its measured milestones; screenshots still need inspection. */
internal fun requireNativeFeedVisible(activity:Activity) {
    val snapshot=AtomicReference<Triple<Boolean,Boolean,Boolean>>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        snapshot.set(Triple(activity.getSystemService(PowerManager::class.java).isInteractive,
            activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            activity.window.decorView.isShown&&activity.window.decorView.hasWindowFocus()))
    }
    val (interactive,locked,focused)=requireNotNull(snapshot.get())
    // Assert on the instrumentation thread, so a failed visibility control is
    // a reported assertion rather than an uncaught main-thread process crash.
    assertTrue("Visible-feed measurement requires an interactive display",interactive)
    assertFalse("Visible-feed measurement cannot run behind the keyguard",locked)
    assertTrue("Visible-feed measurement requires the reader's shown, focused window",focused)
}

class NativeFeedVisibilityTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun aNotificationShadeCannotQualifyAsAVisibleNativeCard() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        fun focused():Boolean {
            val value=AtomicBoolean()
            ui.runOnUiThread {value.set(ui.activity.window.decorView.hasWindowFocus())}
            return value.get()
        }
        fun shell(command:String) {
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
                .bufferedReader().use {it.readText()}
        }
        ui.runOnUiThread {ui.activity.setContent {Text("Native visibility qualification")}}
        ui.waitUntil(5_000){focused()}
        requireNativeFeedVisible(ui.activity)
        try {
            shell("cmd statusbar expand-notifications")
            ui.waitUntil(5_000){!focused()}
            assertThrows(AssertionError::class.java){requireNativeFeedVisible(ui.activity)}
        }finally {shell("cmd statusbar collapse")}
        ui.waitUntil(5_000){focused()}
        requireNativeFeedVisible(ui.activity)
    }
}
