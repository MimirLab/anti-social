package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Explicit public 8.65MB install through the real signed catalog and Gateway. */
@RunWith(AndroidJUnit4::class)
class AiModelInstallTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private var oldAiEnabled=false
    private var policy: NetworkPolicy?=null
    private var previousChoice=false
    @Before fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true"&&InstrumentationRegistry.getArguments().getString("antisocialLocalAiDownload")=="true")
        runBlocking {repo.awaitReady()}
        assumeTrue("Never remove a user's existing embedding download",repo.ai.installer.activePack(AiKind.EMBEDDING)==null)
        assumeTrue("The all-model deletion fixture requires no existing text download",repo.ai.installer.activePack(AiKind.TEXT)==null)
        oldAiEnabled=repo.ai.preferences.enabled;previousChoice=repo.ai.preferences.localChosen("embedding");policy=repo.gateway.currentPolicy()
        ui.runOnIdle {repo.cancelSearchPreparation();repo.gateway.configure(NetworkPolicy());repo.ai.enabled(true);ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].settings(SettingsPage.AI)}
    }
    @After fun cleanup() {
        if(policy==null)return
        ui.runOnIdle {ui.activity.setContent {Text("Optional model download fixture complete")}}
        runBlocking {repo.ai.remove(AiKind.EMBEDDING)}
        repo.ai.preferences.localChosen("embedding",previousChoice);repo.ai.enabled(oldAiEnabled);repo.gateway.configure(policy!!)
    }
    @Test fun oneClickEmbeddingDownloadQualifiesTheModelThenItCanBeDisabledAndRemovedWhileOff() {
        val initialRequests=repo.gateway.protectionStats().checked
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("AI model tabs"))
        ui.onNodeWithTag("AI model embedding").performClick()
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("Download selected AI model"))
        ui.onNodeWithTag("Download selected AI model").performClick()
        ui.waitUntil(180_000){repo.ai.state.value.embeddingReady||(!repo.ai.state.value.working&&repo.ai.state.value.message.contains("failed",true))}
        assertTrue("Installation status: ${repo.ai.state.value.message}",repo.ai.state.value.embeddingReady)
        val installed=repo.ai.installer.activePack(AiKind.EMBEDDING)!!
        val bytes=installed.pack.assets.sumOf {java.io.File(installed.directory,it.filename).length()}
        assertEquals(8_653_869L,bytes)
        val vector=runBlocking {repo.ai.embed("A local battery replacement guide")} as EmbeddingResult.Vector
        assertEquals(512,vector.values.size);assertEquals(SseEmbedding.SPACE_ID,vector.spaceId)
        ui.onNodeWithTag("AI settings list").performScrollToIndex(0)
        ui.onNodeWithTag("AI master toggle").performClick().assertIsOff()
        assertTrue(installed.directory.isDirectory)
        val before=repo.gateway.protectionStats()
        runBlocking {try {repo.ai.embed("No inference while off");fail("Off")}catch(_: IllegalStateException){}}
        assertEquals(before,repo.gateway.protectionStats())
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("Remove all local AI models"))
        ui.onNodeWithTag("Remove all local AI models").performClick()
        ui.waitUntil(10_000){repo.ai.installer.activePack(AiKind.EMBEDDING)==null}
        assertFalse(installed.directory.exists());assertFalse(repo.ai.preferences.enabled)
        val evidence=org.json.JSONObject().put("download_gateway_requests",before.checked-initialRequests).put("requests_after_disable",repo.gateway.protectionStats().checked-before.checked).put("pack_bytes",bytes).put("embedding_dimensions",vector.values.size).put("space_id",vector.spaceId).put("removed_while_off",true)
        val output=java.io.File(ui.activity.filesDir,"local-ai/test").apply {mkdirs()}
        java.io.File(output,"install-flow.json").writeText(evidence.toString(2))
    }
}
