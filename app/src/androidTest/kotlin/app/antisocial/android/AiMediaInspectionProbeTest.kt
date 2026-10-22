/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in inspection of externally supplied local files. No third-party image
 * is bundled and no platform, credential host or classifier is contacted. */
@RunWith(AndroidJUnit4::class)
class AiMediaInspectionProbeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun inspectExactSuppliedLocalMediaAndShowItsEvidence() {
        val arguments=InstrumentationRegistry.getArguments()
        val input=arguments.getString("aiAuditFiles").orEmpty()
        assumeTrue("External local media inspection is separately opt-in",input.isNotBlank())
        val names=input.split(',')
        require(names.size in 1..4&&names.all {it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}"))&&!it.contains("..")})
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {repo.awaitReady()}
        val policy=repo.gateway.currentPolicy()
        val folder=requireNotNull(ui.activity.getExternalFilesDir("validation-input"))
        val evidence=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"local-media-inspection").apply {mkdirs()}
        val reports=linkedMapOf<String,AiMetadata>()
        try {
            ui.runOnIdle {repo.gateway.configure(NetworkPolicy(tor=true,ready=false))}
            for(name in names) {
                val file=File(folder,name)
                require(file.isFile&&file.length() in 1..EmbeddedAiMetadata.MAX_BYTES.toLong())
                val url="https://local-inspection.invalid/$name"
                val digest=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
                val report=runBlocking {requireNotNull(repo.aiMetadata.inspect(url,file))}
                assertEquals(digest,report.sha256)
                assertTrue(report.inspectedAt>0)
                reports[name]=report
                if(arguments.getString("aiAuditExpectNoDeclaration")=="true") {
                    assertTrue("No AI declaration should be manufactured for supplied bytes",report.disclosures.isEmpty())
                    assertEquals(AiCredentialState.NOT_PRESENT,report.credentialState)
                    assertTrue("Missing declarations alone are not proof of removal",report.removals.isEmpty())
                    assertTrue(report.limitations.isEmpty())
                }
                val bitmap=runBlocking {repo.images.loadLocal(file,768,url)}
                val post=Post(name,Platform.X,"https://x.com/localinspection/status/123","Local inspection","Evidence from the supplied copy.",media=listOf(Media(url)))
                ui.runOnIdle {ui.activity.setContent {key(name){MaterialTheme(colorScheme=darkColorScheme()){Surface {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("Local media inspection",style=MaterialTheme.typography.titleLarge)
                    Image(bitmap.asImageBitmap(),"Inspected local media",Modifier.fillMaxWidth().heightIn(max=220.dp),contentScale=ContentScale.Fit)
                    AiProvenanceDisclosure(rememberAiProvenance(repo,listOf(post)))
                }}}}}}
                ui.waitUntil(10_000){ui.onAllNodesWithTag("AI provenance button").fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("AI provenance button").performClick()
                ui.onNodeWithTag("AI provenance manifest").assertExists()
                if(arguments.getString("aiAuditExpectNoDeclaration")=="true") {
                    ui.onNodeWithText("AI status unknown").assertExists()
                    ui.onNodeWithText("AI DETECTED").assertDoesNotExist()
                    ui.onNodeWithText("METADATA REMOVED").assertDoesNotExist()
                    ui.onNodeWithText("METADATA GAP").assertDoesNotExist()
                }
                ui.waitForIdle()
                val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(evidence,"$name.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
                screenshot.recycle()
            }
            File(evidence,"reports.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(reports))
        }finally {ui.runOnIdle {repo.gateway.configure(policy)}}
    }
}
