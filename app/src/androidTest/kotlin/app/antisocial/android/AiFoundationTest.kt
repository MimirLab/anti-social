/* AntiSocial, GPL-3.0-or-later. Run on the disposable fixture device only. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiFoundationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private lateinit var prefs: Map<String,*>
    private lateinit var policy: NetworkPolicy
    private lateinit var searchPolicy: NetworkPolicy
    private var readerPreferences=""
    private var credentials=emptyMap<String,ByteArray>()
    private val networkEvents=java.util.concurrent.CopyOnWriteArrayList<NetworkEvent>()
    private var originalNetworkObserver:((NetworkEvent)->Unit)?=null
    @Before fun preserve() {
        assumeTrue("AI setup fixtures only use the disposable emulator",InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()}
        readerPreferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();searchPolicy=repo.gateway.currentPolicy(NetworkRoute.TOR)
        prefs=ui.activity.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).all.toMap()
        originalNetworkObserver=repo.gateway.onNetworkEvent;networkEvents.clear()
        repo.gateway.onNetworkEvent={event->networkEvents+=event;originalNetworkObserver?.invoke(event)}
        credentials=File(ui.activity.noBackupFilesDir,"ai-credentials").listFiles().orEmpty().associate {it.name to it.readBytes()}
        repo.ai.enabled(false);ui.activity.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).edit().clear().commit()
        File(ui.activity.noBackupFilesDir,"ai-credentials").listFiles().orEmpty().forEach {it.delete()}
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false
        ui.runOnIdle {repo.cancelSearchPreparation();repo.tor.enable(false);repo.gateway.configure(NetworkPolicy(tor=true,ready=false));repo.gateway.configureTor(NetworkPolicy(tor=true,ready=false));model().settings(SettingsPage.AI)}
    }
    @After fun restore() {
        if(!::prefs.isInitialized)return
        repo.gateway.onNetworkEvent=originalNetworkObserver
        ui.runOnIdle {ui.activity.setContent {Text("AI foundation fixture complete")};repo.ai.enabled(false)}
        File(ui.activity.noBackupFilesDir,"ai-credentials").apply {mkdirs();listFiles().orEmpty().forEach {it.delete()};credentials.forEach {(name,bytes)->File(this,name).writeBytes(bytes)}}
        val editor=ui.activity.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).edit().clear()
        prefs.forEach {(key,value)->when(value){is Boolean->editor.putBoolean(key,value);is String->editor.putString(key,value);is Int->editor.putInt(key,value);else->error("Unexpected AI fixture preference")}};editor.commit()
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(readerPreferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
            repo.gateway.configure(policy);repo.gateway.configureTor(searchPolicy);model().following()
        }
    }
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(200)
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"ai-foundation").apply {mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        File(directory,"$name-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
    }
    @Test fun aiDefaultsOffAndTheWholeReaderRemainsAvailableWithoutAnyModelOrNetwork() {
        assertFalse(AiPreferences(ui.activity).enabled)
        ui.onNodeWithTag("AI master toggle").assertIsOff()
        ui.onNodeWithTag("Download local AI pack").assertDoesNotExist()
        ui.onNodeWithTag("AI model tabs").assertDoesNotExist()
        val before=repo.gateway.protectionStats()
        runBlocking {
            try {repo.ai.text("test","must not be sent");fail("AI is off")}catch(e: IllegalStateException){assertTrue(e.message!!.contains("off"))}
            try {repo.ai.embed("must not be embedded");fail("AI is off")}catch(e: IllegalStateException){assertTrue(e.message!!.contains("off"))}
        }
        assertEquals(before,repo.gateway.protectionStats());evidence("off")
        ui.runOnIdle {model().following()}
        ui.onNodeWithTag("AI model tabs").assertDoesNotExist()
        assertFalse(repo.ai.preferences.enabled)
    }
    @Test fun enablingOnlyRevealsSetupAndHardwareRecommendationWithoutStartingDownloads() {
        val before=repo.gateway.protectionStats()
        ui.onNodeWithTag("AI master toggle").performClick().assertIsOn()
        ui.onNodeWithTag("Download local AI pack").assertIsDisplayed()
        assertNull(repo.ai.state.value.progress)
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("AI model tabs"))
        ui.onNodeWithTag("AI model embedding").performClick()
        ui.onNodeWithText("SSE · English · 512 dimensions\n8.65 MB · CPU · independent of the text model").assertExists()
        evidence("embeddings")
        ui.onNodeWithTag("AI model text").performClick()
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("AI text profile compact"))
        ui.onNodeWithTag("AI text profile compact").performClick().assertIsSelected()
        assertEquals("compact",repo.ai.preferences.textProfile)
        evidence("text-choices")
        assertEquals(before,repo.gateway.protectionStats())
        ui.onNodeWithTag("AI settings list").performScrollToIndex(0)
        ui.onNodeWithTag("AI master toggle").performClick().assertIsOff()
        ui.onNodeWithTag("AI model tabs").assertDoesNotExist()
        assertEquals(before,repo.gateway.protectionStats())
    }
    @Test fun providerKeysAreEncryptedAndExcludedFromReaderExportsAndAiNeverAutoConnects() {
        val secret="fixture-ai-secret-"+System.nanoTime()
        val before=repo.gateway.protectionStats()
        ui.onNodeWithTag("AI master toggle").performClick()
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("Configure AI provider"))
        ui.onNodeWithTag("Configure AI provider").performClick()
        ui.onNodeWithTag("AI provider URL").performScrollTo().performTextInput("https://models.example.org/v1")
        ui.onNodeWithTag("AI provider model name").performScrollTo().performTextInput("reader-text")
        ui.onNodeWithTag("AI provider API key").performScrollTo().performTextInput(secret)
        ui.onNodeWithTag("AI provider enabled").performScrollTo().performClick()
        ui.onNodeWithTag("Save AI provider").performClick()
        ui.waitUntil(10_000){repo.ai.preferences.provider("text").enabled}
        assertEquals(secret,repo.ai.vault.read("text"));assertTrue(repo.ai.preferences.provider("text").hasKey)
        val files=File(ui.activity.noBackupFilesDir,"ai-credentials").listFiles().orEmpty()
        assertTrue(files.isNotEmpty());assertTrue(files.none {it.readText(Charsets.ISO_8859_1).contains(secret)})
        assertFalse(repo.prefs.export().toString().contains(secret))
        val backup=runBlocking {repo.exportBackup()}.toString(Charsets.UTF_8)
        assertFalse(backup.contains(secret));assertFalse(backup.contains("models.example.org"));assertFalse(backup.contains("ai-settings"))
        assertEquals("Unexpected network activity: $networkEvents",before,repo.gateway.protectionStats())
        evidence("provider-saved")
        ui.onNodeWithTag("Edit AI provider custom").performScrollTo().performClick()
        ui.onNodeWithTag("Forget AI provider").performScrollTo().performClick()
        ui.waitUntil(10_000){repo.ai.preferences.provider("text").baseUrl.isEmpty()}
        assertNull(repo.ai.vault.read("text"));assertEquals("Unexpected network activity: $networkEvents",before,repo.gateway.protectionStats())
    }
    @Test fun signedCatalogVerifiesWithTheInstalledAppCertificateAndSeparateModelIdentities() {
        assertEquals("0.32.0",repo.ai.catalog.release)
        assertEquals(3,repo.ai.catalog.packs.count {it.kind==AiKind.TEXT})
        assertEquals(1_155_118_989L,repo.ai.catalog.default(AiKind.TEXT).downloadBytes+repo.ai.catalog.default(AiKind.EMBEDDING).downloadBytes)
        assertEquals("en",repo.ai.catalog.default(AiKind.EMBEDDING).language)
        assertNotEquals(repo.ai.catalog.default(AiKind.TEXT).tokenizerIdentity,repo.ai.catalog.default(AiKind.EMBEDDING).tokenizerIdentity)
    }
    @Test fun anIncompleteLocalChoiceNeverSilentlySendsSourceTextToAnEnabledRemoteProvider() {
        repo.ai.enabled(true)
        runBlocking {repo.ai.saveProvider(AiKind.TEXT,AiProviderSettings(true,AiLocation.CLOUD,"https://models.example.org/v1","fixture-model"),null)}
        repo.ai.preferences.localChosen("text",true)
        val before=repo.gateway.protectionStats()
        runBlocking {try {repo.ai.text("test","Source material must remain private");fail("A local setup failure cannot turn into a cloud request")}catch(e: IllegalStateException){assertTrue(e.message!!.contains("local model is not ready"))}}
        assertEquals(before,repo.gateway.protectionStats())
    }
    @Test fun namedProvidersKeepIndependentKeysAndSelectionWithLocalPreferenceUnderUserControl() {
        val before=repo.gateway.protectionStats()
        ui.onNodeWithTag("AI master toggle").performClick()
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("AI provider slot anthropic"))
        ui.onNodeWithTag("Edit AI provider anthropic").performClick()
        ui.onNodeWithTag("AI provider URL").assertTextContains("https://api.anthropic.com/v1")
        ui.onNodeWithTag("AI provider model name").assertTextContains("claude-haiku-5-5")
        ui.onNodeWithTag("AI provider API key").performScrollTo().performTextInput("fixture-claude-key")
        ui.onNodeWithTag("AI provider enabled").performScrollTo().performClick()
        ui.onNodeWithTag("Save AI provider").performClick()
        ui.waitUntil(10_000){repo.ai.preferences.provider("text","anthropic")?.enabled==true}
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("Edit AI provider deepseek"))
        ui.onNodeWithTag("Edit AI provider deepseek").performClick()
        ui.onNodeWithTag("AI provider API key").performScrollTo().performTextInput("fixture-deepseek-key")
        ui.onNodeWithTag("AI provider enabled").performScrollTo().performClick()
        ui.onNodeWithTag("Save AI provider").performClick()
        ui.waitUntil(10_000){repo.ai.preferences.providers("text").size==2}
        assertEquals("fixture-claude-key",repo.ai.vault.read(aiCredentialSlot("text","anthropic")))
        assertEquals("fixture-deepseek-key",repo.ai.vault.read(aiCredentialSlot("text","deepseek")))
        assertNull(repo.ai.vault.read(aiCredentialSlot("embedding","anthropic")))
        assertEquals("anthropic",repo.ai.preferences.selectedId("text"))
        // Select the actual radio action after dialog/keyboard relayout. A
        // child index does not establish that its touch center is visible.
        val deepseekSelection=hasAnyAncestor(hasTestTag("AI provider slot deepseek")) and hasClickAction() and
            SemanticsMatcher("Provider radio selection") {it.config.contains(SemanticsProperties.Selected)}
        ui.onNode(deepseekSelection).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        ui.waitUntil(5_000){repo.ai.preferences.selectedId("text")=="deepseek"}
        assertEquals("deepseek",repo.ai.preferences.selectedId("text"))
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("AI routing PROVIDER_ONLY"))
        ui.onNodeWithTag("AI routing PROVIDER_ONLY").performClick().assertIsSelected()
        assertEquals(AiRouting.PROVIDER_ONLY,AiPreferences(ui.activity).routing("text"))
        assertEquals(AiRouting.LOCAL_FIRST,AiPreferences(ui.activity).routing("embedding"))
        assertEquals(before,repo.gateway.protectionStats())
        evidence("cloud-providers")
        runBlocking {repo.ai.forgetProvider(AiKind.TEXT,"deepseek")}
        assertNull(repo.ai.preferences.selectedId("text"))
        assertNull(repo.ai.vault.read(aiCredentialSlot("text","deepseek")))
        assertEquals("fixture-claude-key",repo.ai.vault.read(aiCredentialSlot("text","anthropic")))
        // Forgetting one service must not choose another service behind the user's back.
        assertFalse(repo.ai.preferences.provider("text").enabled)
    }
    @Test fun legacyProviderKeepsItsConfigurationAndKeyWhenNamedProvidersAreAdded() {
        val prefs=ui.activity.getSharedPreferences("ai-settings",Context.MODE_PRIVATE)
        prefs.edit().putString("text","""{"enabled":true,"location":"CLOUD","baseUrl":"https://legacy.example/v1","model":"legacy","hasKey":true}""").commit()
        repo.ai.vault.write("text","fixture-legacy-key")
        assertEquals("legacy",repo.ai.preferences.provider("text").model)
        assertEquals("custom",repo.ai.preferences.selectedId("text"))
        val preset=AiProviderPreset.OPENAI
        runBlocking {repo.ai.saveProvider(AiKind.TEXT,AiProviderSettings(true,baseUrl=preset.baseUrl,model=preset.textModel,id="openai",preset=preset,protocol=preset.protocol),"fixture-openai-key")}
        assertEquals("custom",repo.ai.preferences.selectedId("text"))
        assertEquals("https://legacy.example/v1",repo.ai.preferences.provider("text").baseUrl)
        assertEquals("fixture-legacy-key",repo.ai.vault.read("text"))
        assertEquals(2,repo.ai.preferences.providers("text").size)
        runBlocking {repo.ai.forgetProvider(AiKind.TEXT,"openai")}
        assertEquals("fixture-legacy-key",repo.ai.vault.read("text"))
    }
    @Test fun editingAnEndpointCannotForwardItsOldKeyToANewServer() {
        val original=AiProviderSettings(true,baseUrl="https://first.example/v1",model="model")
        runBlocking {repo.ai.saveProvider(AiKind.TEXT,original,"fixture-private-key")}
        runBlocking {try {repo.ai.saveProvider(AiKind.TEXT,original.copy(baseUrl="https://second.example/v1"),null);fail("A different endpoint needs a new credential decision")}catch(e: IllegalArgumentException){assertTrue(e.message!!.contains("Re-enter"))}}
        assertEquals(original.baseUrl,repo.ai.preferences.provider("text").baseUrl)
        assertEquals("fixture-private-key",repo.ai.vault.read("text"))
        runBlocking {repo.ai.saveProvider(AiKind.TEXT,original.copy(baseUrl="https://second.example/v1"),null,removeKey=true)}
        assertNull(repo.ai.vault.read("text"))
        File(ui.activity.noBackupFilesDir,"ai-credentials/text").writeBytes(ByteArray(31))
        runBlocking {repo.ai.saveProvider(AiKind.TEXT,original,"fixture-replacement-key")}
        assertEquals("fixture-replacement-key",repo.ai.vault.read("text"))
    }
    @Test fun embeddingChoicesAreIndependentAndDoNotAdvertiseUnsupportedProviders() {
        ui.onNodeWithTag("AI master toggle").performClick()
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("AI model tabs"))
        ui.onNodeWithTag("AI model embedding").performClick()
        ui.onNodeWithTag("AI settings list").performScrollToNode(hasTestTag("AI provider slot openai"))
        ui.onNodeWithTag("AI provider slot openai").assertExists()
        ui.onNodeWithTag("AI provider slot anthropic").assertDoesNotExist()
        ui.onNodeWithTag("AI provider slot deepseek").assertDoesNotExist()
        ui.onNodeWithTag("Edit AI provider openai").performClick()
        ui.onNodeWithTag("AI provider model name").assertTextContains("text-embedding-3-small")
        ui.onNodeWithTag("AI provider API key").performScrollTo().performTextInput("fixture-vector-key")
        ui.onNodeWithTag("AI provider enabled").performScrollTo().performClick()
        ui.onNodeWithTag("Save AI provider").performClick()
        ui.waitUntil(10_000){repo.ai.preferences.provider("embedding").enabled}
        assertFalse(repo.ai.preferences.provider("text").enabled)
        assertNull(repo.ai.vault.read(aiCredentialSlot("text","openai")))
        evidence("embedding-provider")
    }

    @Test fun explicitProviderChoiceCanOverrideAPendingLocalChoiceThroughTheRealGateway() {
        repo.ai.enabled(true);repo.gateway.configure(NetworkPolicy())
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use {server->
                server.soTimeout=15_000
                val received=executor.submit<String> {server.accept().use {socket->
                    socket.soTimeout=15_000
                    val input=socket.getInputStream().bufferedReader();val request=StringBuilder();var length=0
                    while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;request.append(line).append('\n');if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                    val body=CharArray(length);var count=0;while(count<length){val n=input.read(body,count,length-count);if(n<0)break;count+=n};request.append(body)
                    val response="""{"choices":[{"message":{"content":"User choice honored"}}]}""".toByteArray()
                    socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray());write(response);flush()}
                    request.toString()
                }}
                runBlocking {repo.ai.saveProvider(AiKind.TEXT,AiProviderSettings(true,AiLocation.SERVER,"http://127.0.0.1:${server.localPort}/v1","fixture-model"),"fixture-owned-server-key")}
                repo.ai.preferences.localChosen("text",true)
                runBlocking {try {repo.ai.text("Be brief.","Public source");fail("Local first must honor the pending download")}catch(_: IllegalStateException){}}
                assertFalse(received.isDone)
                repo.ai.routing(AiKind.TEXT,AiRouting.PROVIDER_ONLY)
                assertEquals("User choice honored",runBlocking {repo.ai.text("Be brief.","Public source")})
                val request=received.get(15,java.util.concurrent.TimeUnit.SECONDS)
                assertTrue(request.startsWith("POST /v1/chat/completions"));assertTrue(request.contains("Authorization: Bearer fixture-owned-server-key",true))
                assertTrue(repo.ai.preferences.localChosen("text")) // No deletion or reset was needed.
                repo.ai.routing(AiKind.TEXT,AiRouting.DEVICE_ONLY)
                val before=repo.gateway.protectionStats()
                runBlocking {try {repo.ai.text("Be brief.","Public source");fail("Device only must not call the saved server")}catch(_: IllegalStateException){}}
                assertEquals(before,repo.gateway.protectionStats())
            }
        }finally {executor.shutdownNow()}
    }

}
