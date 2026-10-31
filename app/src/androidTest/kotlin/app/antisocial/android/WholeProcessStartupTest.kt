/* anti social, GPL-3.0-or-later. Observe retained-state process startup. */
package app.antisocial.android

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** This opt-in does not seed records, replace the content, change preferences,
 * inject a loader, or open a network route. Engineering staging and egress
 * controls belong to the isolated-device driver, outside the reader. */
class WholeProcessStartupTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun retainedStateReachesAnActualCardAfterApplicationCreation() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialPrivateStartupTrace")=="true")
        val runner=InstrumentationRegistry.getInstrumentation()as? StartupTraceRunner
            ?: error("Select the explicit startup runner")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        val output=File(ui.activity.getExternalFilesDir("validation"),"whole-process-startup")
        val cards=SemanticsMatcher("actual retained feed row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Feed row ")==true
        }
        fun visible():Int {
            val viewport=ui.onAllNodesWithTag("Combined feed").fetchSemanticsNodes().singleOrNull()?.boundsInRoot ?: return 0
            return ui.onAllNodes(cards,useUnmergedTree=true).fetchSemanticsNodes().count {node->
                val area=node.boundsInRoot.intersect(viewport);area.width>0&&area.height>=80
            }
        }
        runner.event("test-enter")
        ui.waitUntil(60_000){visible()>0};ui.waitForIdle()
        requireNativeFeedVisible(ui.activity)
        runner.event("first-card-observed",mapOf("visibleRows" to visible()))
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {File(output,"first-card.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
        val counts=runBlocking {repo.awaitReady();repo.dao.allFollows().size to repo.dao.allPosts().size}
        assertTrue("This opt-in requires staged retained follows and posts",counts.first>0&&counts.second>0)
        runner.event("retained-state-checked",mapOf("follows" to counts.first,"posts" to counts.second))
        runner.stopTrace()
        assertTrue("A successful launch alone does not establish a method trace",File(output,"application-start.trace").length()>1024)
    }
}
