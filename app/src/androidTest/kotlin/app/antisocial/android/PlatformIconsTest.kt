/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.Platform
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Full native gallery: catches missing/tint-invisible vector assets on Android itself. */
@RunWith(AndroidJUnit4::class)
class PlatformIconsTest {
    @get:Rule val ui=createComposeRule()

    private fun gallery(dark: Boolean) {
        ui.setContent {
            val palette=if(dark)darkColorScheme(surface=Color(0xFF111315),onSurface=Color(0xFFECEFEA))else lightColorScheme(surface=Color.White,onSurface=Color(0xFF111315))
            MaterialTheme(colorScheme=palette) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(dark)"Platform marks · Dark"else "Platform marks · Light",fontSize=20.sp)
                        Platform.entries.toList().chunked(3).forEach {row->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                                row.forEach {platform->
                                    Column(Modifier.width(98.dp).height(66.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                        PlatformMark(platform,36.dp)
                                        Text(platform.label,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Platform.entries.forEach {ui.onNodeWithContentDescription(it.label).assertIsDisplayed()}
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(requireNotNull(instrumentation.targetContext.getExternalFilesDir("validation")),"platform-icons").apply {mkdirs()}
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        File(folder,if(dark)"all-platforms-dark.png"else "all-platforms-light.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
        screenshot.recycle()
    }

    @Test fun allPlatformMarksRenderInDarkMode()=gallery(true)
    @Test fun allPlatformMarksRenderInLightMode()=gallery(false)
}
