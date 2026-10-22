package app.antisocial.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MediaInfoTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val keys=mutableListOf<String>();private lateinit var directory:File
    private fun fixture(name:String)=InstrumentationRegistry.getInstrumentation().context.assets.open("provenance/$name").use {it.readBytes()}
    @Before fun isolate() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()}
        directory=File(ui.activity.cacheDir,"info-fixture-${System.nanoTime()}").apply {mkdirs()}
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Text("Info fixtures")}}}
    }
    @After fun clean() {
        if(!::directory.isInitialized)return
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Text("Info checks complete")}}}
        runBlocking {repo.dao.removeMedia(keys)};directory.deleteRecursively()
    }
    private suspend fun image(name:String="plain.png",url:String="https://image.fixture.invalid/${System.nanoTime()}.png",saved:Boolean=false):Pair<String,File> {
        val file=File(directory,Repository.hash(url)).apply {writeBytes(fixture(name))};val key=Repository.hash(url);keys+=key
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis(),saved=saved));return url to file
    }
    private fun store()=MediaInfoStore(repo,file=File(directory,"info.json"))
    @Test fun recipesSurvivePortableRecordsWithoutImportedSignatureAuthority() {
        val original=AiMetadata(recipes=listOf(GenerationRecipe("NovelAI",prompt="A lighthouse",parameters=mapOf("steps" to "20"),signature=RecipeSignature.VALID)))
        val json=repo.gson.toJsonTree(original).asJsonObject
        strictSavedModel(json,AiMetadata::class.java)
        val value=AiMetadataValues.captured(repo.gson.fromJson(json,AiMetadata::class.java))!!
        assertEquals("20",value.recipes.single().parameters["steps"])
        assertEquals(RecipeSignature.UNCHECKED,value.recipes.single().signature)
        json.getAsJsonArray("recipes")[0].asJsonObject.getAsJsonObject("parameters").addProperty("steps",20)
        assertThrows(IllegalArgumentException::class.java){strictSavedModel(json,AiMetadata::class.java)}
    }
    @Test fun anUnavailableFirstAttachmentDoesNotHideTheRemainingFindings():Unit=runBlocking {
        val (url,_)=image();val store=store();store.inspectPixels={_,_->NovelAiInspection()}
        var latest=MediaInfoProgress()
        store.inspect(listOf(MediaInfoAsset("not-a-web-url","https://fixture.invalid/","Missing image"),MediaInfoAsset(url,url,"Image 2"))){latest=it}
        assertFalse(latest.working);assertEquals(1,latest.checked);assertNotNull(latest.results[url])
    }
    @Test fun yieldingToForegroundContentKeepsFindingsAndStopsTheSpinner():Unit=runBlocking {
        val (url,file)=image();val store=store();store.inspectPixels={_,_->NovelAiInspection()}
        store.acquired(url,GatewayMediaPayload(file.readBytes(),url,"image/png",listOf("https://credential.fixture.invalid/test.c2pa")))
        store.fetchManifest={_,_->throw CancellationException("Content requests take priority")}
        var latest=MediaInfoProgress();store.inspect(listOf(MediaInfoAsset(url,url,"Image 1"))){latest=it}
        assertFalse(latest.working);assertNotNull(latest.results[url]);assertTrue(latest.note!!.contains("paused"))
    }
    @Test fun suppliedPublicNovelAiFilesVerifyOnAndroidWithoutAnUpload():Unit=runBlocking(Dispatchers.IO) {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialNovelPublicFixtures")=="true")
        val folder=File(ui.activity.getExternalFilesDir("validation"),"media-info/input")
        val names=listOf("novelai-curated.png","novelai-full.png","novelai-full-3char.png","novelai-ref.png")
        val rows=names.map {name->
            val bytes=File(folder,name).readBytes();val start=android.os.SystemClock.elapsedRealtime()
            val metadata=EmbeddedAiMetadata.inspect(bytes);val result=NovelAiMetadata.inspect(bytes,metadata.textFields)
            assertEquals(name,2,result.recipes.size);assertTrue(result.recipes.all {it.signature==RecipeSignature.VALID})
            mapOf("file" to name,"milliseconds" to android.os.SystemClock.elapsedRealtime()-start,"signatures" to result.recipes.map {it.signature.name})
        }
        val changed=File(folder,"tampered-public-novelai.png").readBytes()
        val tampered=NovelAiMetadata.inspect(changed,EmbeddedAiMetadata.inspect(changed).textFields).recipes
        assertEquals(2,tampered.size);assertTrue(tampered.all {it.signature==RecipeSignature.INVALID})
        File(folder.parentFile,"public-novelai-android.json").writeText(repo.gson.toJson(rows))
    }
    @Test fun byteIdenticalReopeningAndRestartReuseWorkAndReplacementReinspects():Unit=runBlocking {
        val (url,file)=image();val store=store();var calls=0
        store.inspectPixels={_,_->calls++;NovelAiInspection()}
        var latest=MediaInfoProgress();val assets=listOf(MediaInfoAsset(url,url,"Image 1"))
        store.inspect(assets){latest=it};assertFalse(latest.working);assertEquals(1,calls)
        val digest=latest.results.getValue(url).metadata.sha256
        store.inspect(assets){latest=it};assertEquals(1,calls)
        val restarted=store();restarted.inspectPixels={_,_->calls++;NovelAiInspection()}
        restarted.inspect(assets){latest=it};assertEquals(1,calls)
        file.writeBytes(fixture("tampered.png"));restarted.inspect(assets){latest=it}
        assertEquals(2,calls);assertNotEquals(digest,latest.results.getValue(url).metadata.sha256)
    }
    @Test fun passiveReceiptDoesNoResearchAndOfflineInspectionNeverFollowsIt():Unit=runBlocking {
        val (url,file)=image(saved=true);val store=store();var requests=0
        store.inspectPixels={_,_->NovelAiInspection()};store.fetchManifest={_,_->requests++;error("Offline reads must not contact a credential host")}
        store.acquired(url,GatewayMediaPayload(file.readBytes(),url,"image/png",listOf("https://credential.fixture.invalid/test.c2pa")))
        assertEquals(0,requests)
        var latest=MediaInfoProgress();store.inspect(listOf(MediaInfoAsset(url,url,"Image 1")),localOnly=true){latest=it}
        assertEquals(0,requests);assertTrue(latest.results.getValue(url).detail!!.startsWith("Offline inspection"))
        assertTrue(file.isFile)
    }
    @Test fun anOfflineReceiptDoesNotSuppressRequestedOnlineCredentialRecovery():Unit=runBlocking {
        val (url,file)=image(saved=true);val store=store();var requests=0
        store.inspectPixels={_,_->NovelAiInspection()}
        store.acquired(url,GatewayMediaPayload(file.readBytes(),url,"image/png",listOf("https://credential.fixture.invalid/test.c2pa")))
        store.fetchManifest={_,_->requests++;byteArrayOf(1)}
        store.inspectDetached={_,_,_->AiMetadata(credentialState=AiCredentialState.VALID)}
        val assets=listOf(MediaInfoAsset(url,url,"Image 1"));var latest=MediaInfoProgress()
        store.inspect(assets,localOnly=true){latest=it};assertEquals(0,requests)
        store.inspect(assets){latest=it};assertEquals(1,requests);assertTrue(latest.results.getValue(url).externalCredential)
        assertEquals(AiCredentialState.VALID,repo.aiMetadata.reports.value.getValue(url).credentialState)
        store.inspect(assets){latest=it};assertEquals(1,requests)
    }
    @Test fun unavailableTorFailsClosedAndReopeningReusesTheFailureReceipt():Unit=runBlocking {
        val (url,file)=image();val store=store();store.inspectPixels={_,_->NovelAiInspection()}
        store.acquired(url,GatewayMediaPayload(file.readBytes(),url,"image/png",listOf("https://credential.fixture.invalid/test.c2pa")))
        val policy=repo.gateway.currentPolicy();val count=repo.gateway.protectionStats().checked
        try {
            repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
            var latest=MediaInfoProgress();val assets=listOf(MediaInfoAsset(url,url,"Image 1"))
            store.inspect(assets){latest=it}
            assertEquals("The separate credential is unavailable right now.",latest.results.getValue(url).detail)
            store.fetchManifest={_,_->error("A fresh miss must be cached")}
            store.inspect(assets){latest=it};assertFalse(latest.working)
            assertEquals(count,repo.gateway.protectionStats().checked)
        }finally {repo.gateway.configure(policy)}
    }
    @Test fun clearingWhileAWorkerRunsCannotResurrectTheResult():Unit=runBlocking {
        val (url,file)=image();val store=store();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        store.inspectPixels={_,_->entered.countDown();check(release.await(8,TimeUnit.SECONDS));NovelAiInspection()}
        val job=launch(Dispatchers.IO){store.inspect(listOf(MediaInfoAsset(url,url,"Image 1"))) {}}
        try {assertTrue(entered.await(5,TimeUnit.SECONDS));store.cleanup(clear=true)}finally {release.countDown()}
        job.join();assertNull(store.observed(java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}))
        assertTrue(file.isFile);assertEquals(0,store.bytes())
    }
    @Test fun cancellationStopsAnOnlineCheckAndDoesNotCacheALateVerdict():Unit=runBlocking {
        val (url,file)=image();val store=store();val entered=CompletableDeferred<Unit>()
        store.inspectPixels={_,_->NovelAiInspection()}
        store.acquired(url,GatewayMediaPayload(file.readBytes(),url,"image/png",listOf("https://credential.fixture.invalid/test.c2pa")))
        store.fetchManifest={_,_->entered.complete(Unit);awaitCancellation()}
        val job=launch {store.inspect(listOf(MediaInfoAsset(url,url,"Image 1"))) {}}
        withTimeout(5000){entered.await()};job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(store.observed(java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}))
    }
    @Test fun clickingInfoOpensAndRunsAutomaticallyWithoutASecondRefreshButton():Unit=runBlocking {
        val (url,_)=image("generated.png")
        val post=Post("info-fixture",Platform.BLUESKY,"https://bsky.app/profile/fixture.invalid/post/123","Workshop","A model of our workshop",media=listOf(Media(url)))
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Surface(Modifier.fillMaxSize()) {Column {Text("Workshop notebook");MediaInfoAction(repo,listOf(post))}}}}}
        ui.onNodeWithTag("AI provenance button").performClick()
        try {ui.waitUntil(5000){runCatching {ui.onNodeWithTag("Post information drawer").assertIsDisplayed();true}.getOrDefault(false)}}
        catch(e:Throwable) {
            val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {val folder=File(ui.activity.getExternalFilesDir("validation"),"media-info").apply {mkdirs()};File(folder,"info-open-failure.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {image.recycle()}
            throw e
        }
        ui.waitUntil(15000){ui.onAllNodesWithTag("Info checking").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithText("Behind this post").assertIsDisplayed()
        ui.onNodeWithText("Refresh").assertDoesNotExist()
        ui.onNodeWithText("Bluesky · Image 1").assertExists()
        // A warm cached result can finish before the sheet's first composited Android frame.
        // Advance Compose's animation clock, then allow the actual window surface to draw.
        ui.mainClock.advanceTimeBy(600);ui.waitForIdle();Thread.sleep(250)
        if(InstrumentationRegistry.getArguments().getString("antisocialInspectInfoPause")=="true")Thread.sleep(15_000)
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {val folder=File(ui.activity.getExternalFilesDir("validation"),"media-info").apply {mkdirs()};File(folder,"automatic-info-dark.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {screenshot.recycle()}
    }
}
