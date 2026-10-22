/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.math.abs

/** Production feed cards and local readers consume exact local bytes, with networking fail-closed. */
@RunWith(AndroidJUnit4::class)
class AiPostProvenanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val handle="asprovenance${suffix.takeLast(6)}"
    private val now=System.currentTimeMillis()
    private val posts=mutableListOf<Post>()
    private val models=mutableListOf<AntiSocialViewModel>()
    private val files=mutableListOf<File>()
    private val mediaKeys=mutableSetOf<String>()
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    private lateinit var originalMemory: FeedMemoryStore
    private lateinit var memoryFile: File
    private lateinit var memoryScope: CoroutineScope

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy();originalMemory=repo.feedMemory
        ui.runOnIdle {ui.activity.setContent {Text("Preparing local provenance fixtures")}}
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.historyFullText=false;repo.prefs.theme="dark"
        Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
        ui.runOnIdle {ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].following()}
        memoryFile=File(ui.activity.cacheDir,"ai-provenance-memory-$suffix.json")
        memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope,memoryFile)
        runBlocking {repo.feedMemory.awaitReady();repo.aiMetadata.clear()}
        isolateOfflineGateway()
    }

    @After fun restore() {
        ui.runOnIdle {models.forEach {it.discussions.close();it.viewModelScope.cancel()};ui.activity.setContent {Text("Local provenance fixture complete")}}
        runBlocking {
            repo.feedMemory.flush();memoryScope.coroutineContext[Job]?.cancelAndJoin();repo.aiMetadata.clear()
            posts.map(repo::key).distinct().forEach {key->
                repo.library.cancel(key);repo.dao.removeBookmark(key);repo.dao.deleteDocuments(key,"saved");repo.dao.deleteDocuments(key,"history")
                repo.dao.removeRetainedHistory(listOf(key));repo.dao.removePosts(listOf(key))
                for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
            }
            mediaKeys.forEach {key->repo.dao.media(key)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(key))}
        }
        repo.feedMemory=originalMemory
        files.forEach {it.delete()}
        listOf(memoryFile,File(memoryFile.path+".bak"),File(memoryFile.path+".new")).forEach {it.delete()}
        ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
        repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings()
        // A stopped native Tor process has no usable old SOCKS port. Let the
        // restored preferences restart it instead of restoring a stale ready verdict.
        if(!policy.tor)repo.gateway.configure(policy)
        scheduleRefresh(ui.activity,repo.prefs);repo.library.notifyChanged()
    }

    private fun model()=AntiSocialViewModel(repo).also {it.following();models+=it}
    private fun isolateOfflineGateway() {
        // Stop the real controller first. Otherwise its bootstrap callback can
        // legitimately overwrite a manually configured test policy while we read.
        ui.runOnIdle {repo.tor.enable(false,false)}
        ui.waitUntil(5_000){repo.tor.state.value.let {!it.enabled&&!it.ready}}
        ui.runOnIdle {repo.gateway.configure(NetworkPolicy(tor=true,ready=false))}
        assertFalse(repo.tor.state.value.enabled)
        assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
    }
    private fun post(index: Int,media: List<Media> = emptyList(),platform: Platform=Platform.X,body: String="Provenance workshop note $index."): Post {
        val id="97${suffix}${(1000+index).toString()}"
        val url=if(platform==Platform.INSTAGRAM)"https://www.instagram.com/p/Provenance$id/"else "https://x.com/$handle/status/$id"
        return Post(id,platform,url,handle,body,publishedAt=now-index*60_000L,media=media).also {posts+=it}
    }
    private fun asset(name: String)="https://provenance-$suffix.invalid/$name.png"
    private fun file(name: String,bytes: ByteArray)=File(ui.activity.cacheDir,"ai-post-$suffix/$name").apply {parentFile!!.mkdirs();writeBytes(bytes);files+=this}
    private fun evidence(name: String) {
        ui.waitForIdle()
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"ai-post-provenance").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun assertNoRawManifestOrPrompt() {
        val tree=ui.onRoot(useUnmergedTree=true).printToString()
        listOf("active_manifest","digitalSourceType","c2pa.created","FORBIDDEN_PROMPT_CANARY").forEach {assertFalse("Private/raw manifest value leaked into readable UI: $it",tree.contains(it))}
    }
    private fun card(vm: AntiSocialViewModel,post: Post,editions: List<Post> = listOf(post)) {
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)){PostCard(post,vm,false,discussions=editions,bodyVariants=editions,onOpen={})}}}}}
    }

    @Test fun lateFileMetadataAddsTheNativeBadgeWithoutMovingTheChronologicalAnchor() {
        val url=asset("late-generated");val first=post(0,media=listOf(Media(url)))
        val second=post(1)
        val feed="person:provenance-$suffix";val keys=listOf(first,second).map(repo::key)
        repo.feedMemory.reconcile(feed,keys);repo.feedMemory.anchor(feed,keys.first(),23)
        val vm=model();val data=mutableStateOf(listOf(first,second))
        val follow=Follow("provenance-$suffix",repo.routes.target(Platform.X,handle),"Local provenance fixture",personId="provenance-$suffix")
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface {ChronologicalFeed(vm,data.value,listOf(follow),emptySet(),feed,"Workshop feed","Public posts, newest first",onAdd={})}}}}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Feed row ${repo.key(first)}").fetchSemanticsNodes().isNotEmpty()}
        val row=ui.onNodeWithTag("Feed row ${repo.key(first)}")
        row.assertIsDisplayed();ui.onNodeWithTag("AI provenance button").assertDoesNotExist()
        val before=row.fetchSemanticsNode();val y=before.positionInRoot.y;val height=before.size.height
        val anchor=repo.feedMemory.get(feed).let {it.anchorKey to it.anchorOffset}
        val plain=InstrumentationRegistry.getInstrumentation().context.assets.open("provenance/plain.png").use {it.readBytes()}
        runBlocking {repo.aiMetadata.inspect(url,file("late-unlabeled.png",plain))}
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("AI status unknown").fetchSemanticsNodes().isNotEmpty()}
        val neutral=row.fetchSemanticsNode()
        assertTrue("The neutral information control must not move the reading position",abs(neutral.positionInRoot.y-y)<=2f)
        assertEquals(height,neutral.size.height)
        assertEquals(anchor,repo.feedMemory.get(feed).let {it.anchorKey to it.anchorOffset})
        val bytes=xmpPng("trainedAlgorithmicMedia")
        val report=runBlocking {repo.aiMetadata.inspect(url,file("late-generated.png",bytes))}
        assertTrue(report!!.disclosures.any {it.use==AiUse.GENERATED})
        ui.waitUntil(10_000){ui.onAllNodesWithText("AI DETECTED").fetchSemanticsNodes().isNotEmpty()}
        val after=row.fetchSemanticsNode()
        assertTrue("Late metadata must not move the card under the reader",abs(after.positionInRoot.y-y)<=2f)
        assertEquals("The footer must reserve badge space before metadata arrives",height,after.size.height)
        assertEquals(anchor,repo.feedMemory.get(feed).let {it.anchorKey to it.anchorOffset})
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        ui.onNodeWithTag("AI provenance button").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithText("AI use, as declared").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("X · Image 1").assertExists()
        ui.onNodeWithText(AiMetadataNarrative.paragraphs(report).single()).performScrollTo().assertIsDisplayed()
        assertNoRawManifestOrPrompt();evidence("native-feed-late-ai-readable-manifest")
    }

    @Test fun fusedCardKeepsSourceSpecificAiHistoryAndArchiveToggleUsesOnlyThatEdition() {
        val shared="At this community repair workshop, neighbors learned how to replace a laptop battery, organize all the screws, disconnect the old pack, and safely fit the replacement before closing the cover."
        val first=post(2,listOf(Media(asset("fusion-created"))),body="Workshop notebook.\n\n$shared")
        val second=post(3,listOf(Media(asset("fusion-edited"))),Platform.INSTAGRAM,"$shared\n\nBring the cobalt toolkit next time.")
        val old=first.copy(body="The workshop was announced before the illustrations were added.",media=emptyList(),aiDisclosures=emptyList())
        val paired=first.copy(archivedVersion=ArchivedVersion("Internet Archive",now-7*CachePolicy.DAY,"https://web.archive.org/web/20261010000000/${first.canonicalUrl}",PostSnapshot.from(old),ArchiveContent.signature(first)))
        posts+=paired
        runBlocking {
            repo.aiMetadata.inspect(first.media.single().url,file("fusion-created.png",xmpPng("trainedAlgorithmicMedia")))
            repo.aiMetadata.inspect(second.media.single().url,file("fusion-edited.png",xmpPng("compositeWithTrainedAlgorithmicMedia")))
        }
        assertNotNull("Fixture archive must pass the production source/signature checks",repo.archiveVersion(paired))
        card(model(),paired,listOf(paired,second))
        ui.onNodeWithTag("Fused post text").assertExists()
        ui.onNodeWithTag("AI provenance button").performScrollTo().performClick()
        ui.onNodeWithText("X · Image 1").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Instagram · Image 1").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("The supplied metadata describes this media as edited using generative AI. The declaration concerns an edit to existing content.").performScrollTo().assertIsDisplayed()
        assertNoRawManifestOrPrompt();evidence("native-fused-source-specific-ai-manifest")
        ui.onNodeWithTag("Archive version toggle").performScrollTo().performClick()
        ui.onNodeWithText(old.body).assertExists()
        ui.onNodeWithTag("AI provenance button").assertDoesNotExist()
        ui.onNodeWithTag("AI provenance manifest").assertDoesNotExist()
        ui.onNodeWithText("Instagram · Image 1").assertDoesNotExist()
        evidence("native-archive-no-inherited-current-ai-label")
        ui.onNodeWithTag("Archive version toggle").performScrollTo().performClick()
        ui.onNodeWithTag("AI provenance button").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("AI provenance manifest").assertDoesNotExist()
    }

    @Test fun savedSignedMediaKeepsItsDeclarationThroughTextBackupAndOfflineNativeReading() {
        Assume.assumeTrue("Native credential validation is supported on Android 9+",android.os.Build.VERSION.SDK_INT>=28)
        val url=asset("saved-signed");val root=post(4,listOf(Media(url)),body="Provenance battery workshop $suffix, preserved for offline reading.")
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("provenance/generated.png").use {it.readBytes()}
        val key=Repository.hash(url);mediaKeys+=key
        val saved=File(ui.activity.filesDir,"saved/$key").apply {parentFile!!.mkdirs();writeBytes(bytes);files+=this}
        runBlocking {repo.dao.putMedia(MediaRow(key,saved.path,saved.length(),now,saved=true))}
        val report=runBlocking {repo.aiMetadata.inspect(url,saved)}!!
        assertEquals("The bundled signed fixture must validate against its exact bytes",AiCredentialState.VALID,report.credentialState)
        assertEquals(AiSignerTrust.UNTRUSTED,report.trust)
        assertTrue(report.disclosures.any {it.use==AiUse.GENERATED})
        val owner=runBlocking {repo.library.save(root,repo.library.availableAssets(root)).also {repo.library.cancel(it)}}
        val copy=runBlocking {repo.library.copy(owner)}!!
        val retained=copy.post.media.single().aiMetadata!!
        assertTrue(retained.disclosures.any {it.use==AiUse.GENERATED})
        assertEquals("A durable copy must not retain a stale validation verdict",AiCredentialState.UNCHECKED,retained.credentialState)
        assertEquals(AiSignerTrust.NOT_EVALUATED,retained.trust)
        val backup=runBlocking {repo.gson.fromJson(String(repo.exportBackup()),Backup::class.java)}
        val minimal=Backup(settings=backup.settings,follows=emptyList(),bookmarks=listOf(backup.bookmarks.single {it.key==owner}),history=emptyList(),savedDetails=listOf(backup.savedDetails.orEmpty().single {it.key==owner}))
        runBlocking {repo.importBackup(repo.gson.toJson(minimal).toByteArray());repo.library.cancel(owner);repo.aiMetadata.clear();repo.dao.removePosts(listOf(owner))}
        isolateOfflineGateway()
        val restored=runBlocking {repo.library.copy(owner)}!!
        assertEquals(retained,restored.post.media.single().aiMetadata)
        assertTrue("Selected media must still be an actual protected local copy",saved.isFile)
        var originalOpened=false
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface {LibraryScreen(repo,{originalOpened=true})}}}}
        ui.onNodeWithTag("Library search").performTextInput("Provenance battery workshop $suffix")
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Library entry $owner").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry $owner").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("AI provenance button").performScrollTo().assertIsDisplayed().performClick()
        val paragraph=AiMetadataNarrative.paragraphs(report).single()
        ui.onNodeWithText(paragraph).performScrollTo().assertIsDisplayed()
        assertNoRawManifestOrPrompt();assertFalse(originalOpened)
        assertFalse("The offline fixture must not have a running Tor controller",repo.tor.state.value.enabled)
        assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
        evidence("native-offline-saved-ai-provenance-after-backup")
    }

    @Test fun decodedMemoryHitRestoresTheOriginalLocalObservationWithoutFetching() {
        val url=asset("memory-restored")
        val bytes=xmpPng("trainedAlgorithmicMedia");val local=file("memory-restored.png",bytes)
        val key=Repository.hash(url);mediaKeys+=key
        runBlocking {repo.dao.putMedia(MediaRow(key,local.path,local.length(),now))}
        val first=runBlocking {
            repo.images.load(url,"https://x.com/$handle/status/9700",256).also {
                withTimeout(10_000){repo.aiMetadata.reports.first {it[url]?.disclosures?.isNotEmpty()==true}}
            }
        }
        runBlocking {repo.aiMetadata.clear()}
        assertNull(repo.aiMetadata.reports.value[url])
        val second=runBlocking {repo.images.load(url,"https://x.com/$handle/status/9700",256)}
        assertSame("Metadata must be restored without discarding the decoded bitmap",first,second)
        val report=runBlocking {withTimeout(10_000){repo.aiMetadata.reports.first {it[url]?.disclosures?.isNotEmpty()==true}}}[url]!!
        assertEquals(AiUse.GENERATED,report.disclosures.single().use)
        assertArrayEquals("Inspection must use unchanged cached original bytes",bytes,local.readBytes())
        assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
    }

    @Test fun checkedUnlabeledMediaIsUnknownAndItsSavedInspectionSurvivesWithoutMediaBytes() {
        val url=asset("unlabeled-local")
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("provenance/plain.png").use {it.readBytes()}
        val local=file("unlabeled-local.png",bytes);val key=Repository.hash(url);mediaKeys+=key
        val root=post(6,listOf(Media(url)),body="Unlabeled provenance workshop $suffix, saved without downloading attachments.")
        runBlocking {repo.dao.putMedia(MediaRow(key,local.path,local.length(),now))}
        card(model(),root)
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("AI status unknown").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("AI provenance button").performScrollTo().performClick()
        ui.onNodeWithText("AI status unknown").assertExists()
        val observed=repo.aiMetadata.reports.value[url]!!
        assertTrue(observed.disclosures.isEmpty());assertTrue(observed.removals.isEmpty())
        ui.onNodeWithText(AiMetadataNarrative.inspectionParagraphs(observed).single()).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("AI DETECTED").assertDoesNotExist();ui.onNodeWithText("METADATA REMOVED").assertDoesNotExist()
        evidence("native-unlabeled-copy-unknown-readable-manifest")
        val owner=runBlocking {repo.library.save(root,emptyList()).also {repo.library.cancel(it)}}
        val copy=runBlocking {repo.library.copy(owner)}!!
        assertEquals(observed.sha256,copy.post.media.single().aiMetadata!!.sha256)
        assertTrue("Saving the inspection must not schedule attachment downloads",copy.assets.isEmpty())
        ui.runOnIdle {ui.activity.setContent {Text("Opening saved inspection")}}
        runBlocking {repo.aiMetadata.clear();repo.dao.removeMedia(listOf(key))};local.delete()
        var originalOpened=false
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface {LibraryScreen(repo,{originalOpened=true})}}}}
        ui.onNodeWithTag("Library search").performTextInput("Unlabeled provenance workshop $suffix")
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Library entry $owner").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry $owner").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("AI provenance button").performScrollTo().performClick()
        ui.onNodeWithText(AiMetadataNarrative.inspectionParagraphs(observed,retained=true).single()).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Photo not included in this copy").performScrollTo().assertIsDisplayed()
        assertFalse(originalOpened);assertTrue(repo.aiMetadata.reports.value.isEmpty())
        assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
        evidence("native-saved-inspection-retained-without-image-download")
    }

    @Test fun videoPreviewDeclarationDescribesOnlyThePreviewImage() {
        val video="https://provenance-$suffix.invalid/uninspected.mp4";val preview=asset("declared-video-poster")
        val local=file("declared-video-poster.png",xmpPng("trainedAlgorithmicMedia"))
        val key=Repository.hash(preview);mediaKeys+=key
        val root=post(7,listOf(Media(video,"video",preview)))
        runBlocking {repo.dao.putMedia(MediaRow(key,local.path,local.length(),now))}
        card(model(),root)
        ui.waitUntil(10_000){ui.onAllNodesWithText("AI DETECTED").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("AI provenance button").performScrollTo().performClick()
        ui.onNodeWithText("X · Video 1 preview image").assertExists()
        ui.onNodeWithText("X · Video 1").assertDoesNotExist()
        assertNull("An unplayed video must not acquire its preview's observation",repo.aiMetadata.reports.value[video])
        assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
        evidence("native-video-preview-ai-scoped-to-poster")
    }

    @Test fun unsupportedAvailableMediaShowsAnIncompleteNeutralInspection() {
        val url="https://provenance-$suffix.invalid/unknown.bin";val local=file("unknown.bin","Unrecognized media container".toByteArray())
        val key=Repository.hash(url);mediaKeys+=key
        val root=post(8,listOf(Media(url,"video")))
        runBlocking {repo.dao.putMedia(MediaRow(key,local.path,local.length(),now))}
        card(model(),root)
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("AI inspection incomplete").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("AI provenance button").performScrollTo().performClick()
        ui.onNodeWithText("AI status unknown").assertExists()
        val report=repo.aiMetadata.reports.value[url]!!
        ui.onNodeWithText(AiMetadataNarrative.inspectionParagraphs(report).single()).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("This media format could not be inspected by the available metadata readers.").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("AI DETECTED").assertDoesNotExist();ui.onNodeWithText("METADATA REMOVED").assertDoesNotExist()
        assertEquals(listOf(AiMetadataLimit.UNSUPPORTED_FORMAT),report.limitations)
        evidence("native-incomplete-unsupported-media-inspection")
    }

    /** Same deterministic encoded image with real, namespace-qualified, CRC-correct XMP. */
    private fun xmpPng(sourceType: String): ByteArray {
        val image=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(64*48){index->val x=index%64;val y=index/64;android.graphics.Color.rgb(x*4,y*5,(x*3+y*2)%256)}
        image.setPixels(pixels,0,64,0,0,64,48)
        val png=ByteArrayOutputStream().use {out->image.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray()};image.recycle()
        val xmp="""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/" xmlns:dc="http://purl.org/dc/elements/1.1/"><Iptc4xmpExt:DigitalSourceType rdf:resource="http://cv.iptc.org/newscodes/digitalsourcetype/$sourceType"/><Iptc4xmpExt:AISystemUsed>Fixture Painter</Iptc4xmpExt:AISystemUsed><Iptc4xmpExt:AISystemVersionUsed>2.0</Iptc4xmpExt:AISystemVersionUsed><dc:description>FORBIDDEN_PROMPT_CANARY</dc:description></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val payload="XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.UTF_8)+xmp.toByteArray(Charsets.UTF_8)
        val type="iTXt".toByteArray(Charsets.US_ASCII)
        val chunk=ByteArrayOutputStream().use {buffer->DataOutputStream(buffer).use {out->out.writeInt(payload.size);out.write(type);out.write(payload);out.writeInt(CRC32().apply {update(type);update(payload)}.value.toInt())};buffer.toByteArray()}
        return png.copyOfRange(0,png.size-12)+chunk+png.copyOfRange(png.size-12,png.size)
    }
}
