/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SavedLibraryUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(9)
    private val now=System.currentTimeMillis()
    private lateinit var prefs: String
    private val posts=mutableListOf<Post>()
    private val files=mutableListOf<File>()
    private val collectionIds=mutableListOf<String>()
    private val assetUrls=mutableListOf<String>()
    private val models=mutableListOf<AntiSocialViewModel>()
    @Before fun preserve() {
        runBlocking {repo.awaitReady()};prefs=repo.prefs.export().toString()
        repo.prefs.archiveChecks=false;repo.prefs.communityComments=false;repo.prefs.intervalMinutes=0
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
    }
    @After fun restore() {
        ui.runOnIdle {models.forEach {it.discussions.close();it.viewModelScope.cancel()};ui.activity.setContent {Text("Local library fixture complete")}}
        runBlocking {
            posts.forEach {p->repo.library.cancel(repo.key(p));repo.dao.removeBookmark(repo.key(p));repo.dao.deleteDocuments(repo.key(p),"saved");repo.dao.deleteDocuments(repo.key(p),"history");repo.dao.removeRetainedHistory(listOf(repo.key(p)))}
            collectionIds.forEach {repo.dao.removeCollection(it)}
            repo.dao.removeMedia(assetUrls.map(Repository::hash))
            posts.forEach {repo.db.openHelper.writableDatabase.execSQL("DELETE FROM history WHERE `key`=?",arrayOf(repo.key(it)))}
        }
        files.forEach {it.delete()}
        ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
        repo.prefs.restore(JSONObject(prefs));repo.applyNetworkSettings();repo.library.notifyChanged()
    }
    private fun post(body: String,index: Int=0,media: List<Media> = emptyList()): Post {
        val id="98${suffix}${100+index}"
        return Post(id,Platform.X,"https://x.com/antisociallocal/status/$id","@repairworkshop",body,"",now-index*1000,media=media).also {posts+=it}
    }
    private fun image(name: String): String {
        val url="https://images.example.invalid/$suffix/$name.png";assetUrls+=url
        val file=File(ui.activity.filesDir,"saved/${Repository.hash(url)}");file.parentFile!!.mkdirs()
        val bitmap=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.rgb(70,110,125))
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();files+=file
        runBlocking {repo.dao.putMedia(MediaRow(Repository.hash(url),file.path,file.length(),now,saved=true))};return url
    }
    private fun assertCapturedSources(expected: List<Post>,actual: List<Post>) {
        assertEquals(expected.map(repo::key),actual.map(repo::key))
        expected.zip(actual).forEach {(original,saved)->
            assertEquals(original.media.size,saved.media.size)
            saved.media.forEachIndexed {index,media->
                assertNull(original.media[index].aiMetadata)
                val local=runBlocking {requireNotNull(repo.availableMedia(media.url))}
                val observation=requireNotNull(media.aiMetadata)
                assertTrue(observation.inspectedAt in 1..System.currentTimeMillis())
                val sha=java.security.MessageDigest.getInstance("SHA-256").digest(local.readBytes()).joinToString(""){"%02x".format(it)}
                assertEquals(AiMetadata(inspectedAt=observation.inspectedAt,sha256=sha),observation)
            }
            assertEquals("All original source fields survive alongside the local inspection",original,saved.copy(media=saved.media.mapIndexed {index,media->media.copy(aiMetadata=original.media[index].aiMetadata)}))
        }
    }
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(250)
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"local-library").apply {mkdirs()}
        File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun library(opened: (Post)->Unit={}) {ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface {LibraryScreen(repo,opened)}}}}}
    private fun sheetTo(matcher: SemanticsMatcher) {
        ui.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) and hasAnyAncestor(hasTestTag("Save copy sheet"))).performScrollToNode(matcher)
    }
    @Test fun rememberedConceptsFindSavedTextWhileQuotedWordingAndLiteralPriorityStayAvailable() {
        val literal=post("Rocket launch schedule and the original flight plan.",40)
        val related=post("A successful spaceflight with a new crew capsule.",41)
        runBlocking {repo.library.save(literal,emptyList());kotlinx.coroutines.delay(5);repo.library.save(related,emptyList())}
        library();ui.onNodeWithTag("Library search").performTextInput("rocket launch")
        // Both rows already exist in the initial chronological shelf. Wait for
        // the query's related-match result before inspecting its relevance order.
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Library entry ${repo.key(literal)}").fetchSemanticsNodes().isNotEmpty()&&ui.onAllNodesWithTag("Library entry ${repo.key(related)}").fetchSemanticsNodes().isNotEmpty()&&ui.onAllNodesWithText("Related wording",substring=true).fetchSemanticsNodes().isNotEmpty()}
        ui.waitForIdle()
        val a=ui.onNodeWithTag("Library entry ${repo.key(literal)}").fetchSemanticsNode().boundsInRoot.top
        val b=ui.onNodeWithTag("Library entry ${repo.key(related)}").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Direct wording stays above related wording even when the related save is newer",a<b)
        ui.onNodeWithText("Related wording",substring=true).assertExists();evidence("library-concept-search")
        ui.onNodeWithTag("Library search").performTextReplacement("\"rocket launch\"")
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Library entry ${repo.key(related)}").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("Library entry ${repo.key(literal)}").assertIsDisplayed()
        assertTrue(repo.gateway.currentPolicy().tor)
    }
    @Test fun selectedGalleryAndAvailableRepliesBecomeACollectionAndOpenWithoutTheFrontend() {
        val one=image("battery-guide");val two=image("second-photo")
        val video="https://videos.example.invalid/$suffix/repair.mp4"
        val root=post("Replacing a laptop battery: remove the cover and disconnect power first.",media=listOf(Media(one,description="Battery guide photo"),Media(two,description="Screw locations"),Media(video,"video",description="Repair demonstration")))
        val reply=post("The replacement battery uses connector size Q77; keep the screws in order.",1).copy(parentId=root.id)
        var saved: String?=null
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){var open by remember {mutableStateOf(true)};if(open)SaveCopySheet(repo,root,listOf(CapturedThread(root,listOf(reply),capturedAt=now,hasMore=true)),onSaved={saved=it;open=false},onDismiss={open=false})}}}
        ui.waitUntil(5000){ui.onAllNodesWithTag("Include loaded replies").fetchSemanticsNodes().any {!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}}
        sheetTo(hasTestTag("Save media ${Repository.hash(one)}"));ui.onNodeWithTag("Save media ${Repository.hash(one)}").assertIsOn()
        sheetTo(hasTestTag("Save media ${Repository.hash(two)}"));ui.onNodeWithTag("Save media ${Repository.hash(two)}").assertIsOn().performClick()
        sheetTo(hasTestTag("Save media ${Repository.hash(video)}"));ui.onNodeWithTag("Save media ${Repository.hash(video)}").assertIsOff()
        sheetTo(hasTestTag("Include loaded replies"));ui.onNodeWithTag("Include loaded replies").performClick()
        sheetTo(hasText("New collection"));ui.onNodeWithText("New collection").performClick()
        ui.onNodeWithTag("New collection name").performTextInput("Projects $suffix")
        ui.onNodeWithTag("Create collection").performClick()
        ui.waitUntil(5000){runBlocking {repo.dao.collectionNamed("Projects $suffix")}!=null}
        collectionIds+=runBlocking {repo.dao.collectionNamed("Projects $suffix")}!!.id
        ui.waitUntil(5000){ui.onAllNodesWithTag("Create collection").fetchSemanticsNodes().isEmpty()}
        sheetTo(hasTestTag("Save collection ${collectionIds.single()}"));ui.onNodeWithTag("Save collection ${collectionIds.single()}").assertIsSelected()
        evidence("save-selected-gallery-native")
        sheetTo(hasTestTag("Save local copy"));ui.onNodeWithTag("Save local copy").performClick()
        ui.waitUntil(10_000){saved!=null}
        val copy=runBlocking {repo.library.copy(saved!!)}!!
        assertEquals(listOf(one),copy.details!!.selectedAssets.map {it.url});assertEquals(1,copy.details!!.threads.single().replies.size)
        assertEquals(collectionIds,copy.collections.map {it.id})
        runBlocking {repo.library.cancel(saved!!);repo.cleanup(clearAll=true)}
        assertTrue("A captured reply must remain searchable after cache cleanup",runBlocking {withTimeout(5000){repo.library.entries("connector Q77","saved").first()}}.any {it.key==repo.key(root)})
        var opened=0;library {opened++}
        ui.onNodeWithTag("Library search").performTextInput("connector Q77")
        try {ui.waitUntil(5000){ui.onAllNodesWithTag("Library entry ${repo.key(root)}").fetchSemanticsNodes().isNotEmpty()}}
        catch(e: Exception){evidence("reply-search-failure");File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"local-library/reply-search-tree.txt").writeText(ui.onRoot().printToString());throw e}
        evidence("local-reply-search-native")
        ui.onNodeWithTag("Library entry ${repo.key(root)}").performClick()
        ui.waitUntil(5000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local copy reader").assertExists()
        ui.onNodeWithText(root.body).assertExists()
        evidence("local-gallery-reader-native")
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasText(reply.body))
        ui.onNodeWithText(reply.body).assertIsDisplayed();assertEquals(0,opened)
        evidence("captured-thread-native")
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasTestTag("Open original from local copy"))
        ui.onNodeWithTag("Open original from local copy").performClick();assertEquals(1,opened)
    }
    @Test fun retainedHistoryResultOpensItsLocalTextAndOffersAnExplicitOriginalAction() {
        val history=post("A retained workshop note about hinge lubrication and spare laptop parts.")
        repo.prefs.historyFullText=true
        runBlocking {repo.library.recordHistory(history,now)}
        var opened=0;library {opened++}
        ui.onNodeWithTag("Library scope history").performClick()
        ui.onNodeWithTag("Library search").performTextInput("hinge lubrication")
        ui.waitUntil(5000){ui.onAllNodesWithTag("Library entry ${repo.key(history)}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry ${repo.key(history)}").performClick()
        ui.waitUntil(5000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local copy reader").assertExists();ui.onNodeWithText(history.body).assertIsDisplayed()
        assertEquals(0,opened);evidence("retained-history-local-native")
    }
    @Test fun aPublicTimelineRepostKeepsItsOriginalAuthorAndSharingLabelInTheFusedAndSavedReader() {
        val selected=repo.routes.sources.first {it.frontend=="nitter"}
        val target=repo.routes.target(Platform.X,"Workshop")
        val shared="The community repair workshop published the complete equipment list and the next session's practical instructions."
        val id="99${suffix}301"
        val html="""<div class="profile-card"><a class="profile-card-fullname" href="/Workshop">Community Workshop</a><a class="profile-card-username" href="/Workshop">@Workshop</a></div>
            <div class="timeline-item"><a class="tweet-link" href="/RepairCrew/status/$id"></a><div class="tweet-body"><div><div class="retweet-header">Community Workshop retweeted</div><div class="tweet-header"><a class="username" href="/RepairCrew">@RepairCrew</a><span class="tweet-date"><a title="2026-01-09T12:00:00Z">Jan 9</a></span></div></div><div class="tweet-content">$shared</div></div></div>"""
        val root=Extraction.parse(Payload(html,repo.routes.convert(target,selected),"text/html",200),target,selected,repo.routes).posts.single().also {posts+=it}
        val other=Post("LocalRepost$suffix",Platform.INSTAGRAM,"https://www.instagram.com/p/LocalRepost$suffix/","repaircrew",shared,publishedAt=root.publishedAt).also {posts+=it}
        assertEquals("twitter:repaircrew",CrossPosts.originalAccount(root))
        assertEquals("Reposted by @Workshop",PostPresentation.text(root).attribution)
        val roots=listOf(root,other);val fusion=requireNotNull(CrossPostFusion.compose(roots))
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface {FusedPostText(fusion,roots=roots,repo=repo,networkAllowed=false)}}}}
        ui.onNodeWithTag("Post attribution").assertTextContains("Reposted by",substring=true).assertTextContains("Workshop",substring=true)
        ui.onAllNodesWithText(shared).assertCountEquals(1)
        evidence("repost-fused-native")
        val saved=runBlocking {repo.library.save(root,emptyList(),fusionRoots=roots).also {repo.library.cancel(it)}}
        val copy=runBlocking {repo.library.copy(saved)}!!
        assertEquals(roots,copy.details!!.fusionRoots)
        library()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Library entry ${repo.key(root)}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry ${repo.key(root)}").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Post attribution").assertTextContains("Reposted by",substring=true).assertTextContains("Workshop",substring=true)
        ui.onAllNodesWithText(shared).assertCountEquals(1)
        evidence("repost-saved-native")
        ui.onNodeWithTag("Local source edition twitter").performClick()
        ui.onNodeWithTag("Post attribution").assertTextContains("Reposted by",substring=true).assertTextContains("Workshop",substring=true)
        ui.onNodeWithText(shared).assertExists()
        assertTrue(repo.gateway.currentPolicy().tor)
    }
    @Test fun anRssPictureOnlyRepostKeepsItsSharingRoleAndBlankCaptionInTheSavedReader() {
        val selected=repo.routes.sources.first {it.frontend=="nitter"}
        val target=repo.routes.target(Platform.X,"Workshop")
        val id="99${suffix}302";val picture=image("rss-repost")
        val render=selected.root.trimEnd('/')
        val title="RT by @Workshop: Image"
        val rss="""<rss xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><link>$render/Workshop</link><item><title>$title</title><dc:creator>@RepairCrew</dc:creator><guid>$id</guid><link>$render/RepairCrew/status/$id#m</link><description><![CDATA[<img src="$picture"/>]]></description></item></channel></rss>"""
        val page=Extraction.parse(Payload(rss,"$render/Workshop/rss","application/rss+xml",200),target,selected,repo.routes)
        val root=page.posts.single().also {posts+=it}
        assertEquals("",root.body);assertEquals(title,root.title)
        assertEquals(1,AdjacentDiscovery.observed(Profile(target,"Community Workshop"),page,repo.routes,now).count {it.evidence.kind==AdjacentKind.FORWARDED})
        val saved=runBlocking {repo.library.save(root,emptyList()).also {repo.library.cancel(it)}}
        val copy=runBlocking {repo.library.copy(saved)}!!
        assertEquals(root.publicRelations,copy.post.publicRelations);assertEquals(root.body,copy.post.body);assertEquals(root.title,copy.post.title)
        library();ui.waitUntil(5_000){ui.onAllNodesWithTag("Library entry ${repo.key(root)}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry ${repo.key(root)}").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Post attribution").assertTextContains("Reposted by",substring=true).assertTextContains("Workshop",substring=true)
        ui.onNodeWithText(title).assertDoesNotExist()
        assertTrue(repo.gateway.currentPolicy().tor)
        evidence("rss-picture-repost-saved-native")
    }
    @Test fun fusedLocalCopyKeepsSharedTextOnceAndEveryOriginalEditionAvailableOffline() {
        val photo=image("fused-workshop")
        val shared="At this community repair workshop, neighbors learned how to replace a laptop battery, organize all the screws, disconnect the old pack, and safely fit the replacement before closing the cover."
        val primary=post(shared,media=listOf(Media(photo)))
        val source=Post("LocalFusion$suffix",Platform.INSTAGRAM,"https://www.instagram.com/p/LocalFusion$suffix/","repairworkshop",
            "Workshop notebook.\n\n$shared\n\nBring the cobalt toolkit for our next session.",publishedAt=now-1_000,media=listOf(Media(photo))).also {posts+=it}
        var saved: String?=null
        ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){var open by remember {mutableStateOf(true)};if(open)SaveCopySheet(repo,primary,fusionRoots=listOf(primary,source),onSaved={saved=it;open=false},onDismiss={open=false})}}}
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Save fused source editions").fetchSemanticsNodes().isNotEmpty()}
        sheetTo(hasTestTag("Save fused source editions"));ui.onNodeWithTag("Save fused source editions").assertIsDisplayed()
        sheetTo(hasTestTag("Save local copy"))
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Save local copy").fetchSemanticsNodes().any {!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}}
        ui.onNodeWithTag("Save local copy").performClick()
        ui.waitUntil(10_000){saved!=null}
        runBlocking {repo.library.cancel(saved!!);repo.dao.removePosts(posts.map(repo::key))}
        assertCapturedSources(listOf(primary,source),runBlocking {repo.library.copy(saved!!)}!!.details!!.fusionRoots!!)
        var opened=0;library {opened++}
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Library entry ${repo.key(primary)}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry ${repo.key(primary)}").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local source edition all").assertIsSelected()
        ui.onNodeWithTag("Fusion shared text").assertTextEquals(shared)
        ui.onAllNodesWithText(shared).assertCountEquals(1)
        evidence("fused-local-shared-text-native")
        ui.onNodeWithTag("Local source edition instagram").performClick()
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasText(source.body))
        ui.onNodeWithText(source.body).assertIsDisplayed()
        evidence("fused-local-original-edition-native")
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasTestTag("Local source editions"))
        ui.onNodeWithTag("Local source edition twitter").performClick()
        ui.onNodeWithText(primary.body).assertExists();ui.onNodeWithText(source.body).assertDoesNotExist()
        assertEquals(0,opened)
    }
    @Test fun aSavedRepeatGroupLetsEverySamePlatformOriginalBeSelectedByItsOwnIdentity() {
        val shared="repeatview$suffix At this community repair workshop, neighbors learned how to replace a laptop battery, organize all the screws, disconnect the old pack, and safely fit the replacement before closing the cover."
        val primary=post(shared)
        val second=post("Workshop notebook.\n\n$shared\n\nBring the cobalt toolkit for our next session.",1)
        val third=post("Repair checklist.\n\n$shared\n\nThe copper latch supplies are ready.",2)
        val originals=listOf(primary,second,third)
        assertTrue(CrossPosts.sameSourceRepeats(originals))
        val owner=runBlocking {repo.library.save(primary,emptyList(),fusionRoots=originals).also {repo.library.cancel(it)}}
        runBlocking {repo.dao.removePosts(originals.map(repo::key))}
        var opened=0;library {opened++}
        ui.onNodeWithTag("Library search").performTextInput("repeatview$suffix")
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Library entry $owner").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry $owner").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local source edition all").assertIsSelected()
        ui.onAllNodesWithText(shared).assertCountEquals(1)
        evidence("same-source-saved-combined-caption")
        for((index,original) in originals.withIndex()) {
            ui.onNodeWithTag("Local copy content").performScrollToNode(hasTestTag("Local source editions"))
            val tag="Local source edition ${repo.key(original)}"
            ui.onNodeWithTag("Local source editions").performScrollToNode(hasTestTag(tag))
            ui.onNodeWithTag(tag).performClick().assertIsSelected()
            ui.onNodeWithTag("Local copy content").performScrollToNode(hasText(original.body))
            ui.onNodeWithText(original.body).assertIsDisplayed()
            ui.onNodeWithTag("Local copy content").performScrollToNode(hasTestTag("Local original URL"))
            ui.onNodeWithTag("Local original URL").assertTextEquals(original.canonicalUrl)
            evidence("same-source-saved-original-$index")
        }
        assertEquals(0,opened)
        assertEquals(originals,runBlocking {repo.library.copy(owner)}!!.details!!.fusionRoots)
        // A source-specific search hit must open that original immediately,
        // rather than falling back to the primary merely because both use X.
        ui.onNodeWithContentDescription("Back to library").performClick()
        ui.onNodeWithTag("Library search").performTextReplacement("cobalt toolkit")
        val matched=runBlocking {withTimeout(5_000){repo.library.entries("cobalt toolkit","saved").first()}}.single {it.key==owner}
        assertEquals(repo.key(second),matched.matchedPost?.let(repo::key))
        val matchingText=hasText("cobalt toolkit",substring=true)
        // Clickable result cards merge their caption semantics. Require the
        // actual matching text on that owner card in either representation.
        ui.waitUntil(5_000){ui.onAllNodes(hasTestTag("Library entry $owner") and (matchingText or hasAnyDescendant(matchingText))).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry $owner").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local source edition ${repo.key(second)}").assertIsSelected()
        ui.onNodeWithTag("Local source edition all").assertIsNotSelected()
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasText(second.body));ui.onNodeWithText(second.body).assertIsDisplayed()
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasTestTag("Local original URL"));ui.onNodeWithTag("Local original URL").assertTextEquals(second.canonicalUrl)
        evidence("same-source-saved-search-opens-matched-original")
        assertEquals(0,opened)
    }

    @Test fun savingFromAPlatformDiscussionKeepsEveryFusedEditionAndLoadedReplyStream() {
        val shared="At this community repair workshop, neighbors learned how to replace a laptop battery, organize all the screws, disconnect the old pack, and safely fit the replacement before closing the cover."
        val primary=post(shared)
        val source=Post("DiscussionFusion$suffix",Platform.INSTAGRAM,"https://www.instagram.com/p/DiscussionFusion$suffix/","repairworkshop",
            "Workshop notebook.\n\n$shared\n\nBring the cobalt toolkit for our next session.",publishedAt=now-1_000).also {posts+=it}
        val vm=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope) {root,_,_->
            val reply=root.copy(id=root.id+"reply",canonicalUrl=root.canonicalUrl.trimEnd('/')+"reply/",body="${root.platform.label} local workshop reply",parentId=root.id)
            Page(listOf(root,reply))
        }}).also {models+=it}
        ui.runOnIdle {vm.discussions.open(listOf(primary,source),Platform.INSTAGRAM.id);ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){DiscussionSheet(vm)}}}
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.INSTAGRAM.id)?.page!=null}
        ui.onNodeWithTag("Discussion tab instagram").assertIsSelected()
        ui.onNodeWithTag("Discussion tab all").performClick()
        ui.waitUntil(5_000){vm.discussions.state.value!!.threads.values.all {it.page!=null}}
        // Save from a single selected platform while preserving the other
        // edition and its already-loaded comments, rather than only that tab.
        ui.onNodeWithTag("Discussion tab instagram").performClick()
        ui.onNodeWithTag("Save loaded discussion").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Save fused source editions").fetchSemanticsNodes().isNotEmpty()}
        sheetTo(hasTestTag("Save fused source editions"));ui.onNodeWithTag("Save fused source editions").assertIsDisplayed()
        sheetTo(hasTestTag("Include loaded replies"));ui.onNodeWithTag("Include loaded replies").performClick()
        evidence("discussion-save-fused-editions-native")
        sheetTo(hasTestTag("Save local copy"));ui.onNodeWithTag("Save local copy").performClick()
        ui.waitUntil(10_000){runBlocking {repo.library.details(repo.key(source))}?.fusionRoots?.size==2}
        val copy=runBlocking {repo.library.copy(repo.key(source))}!!
        assertEquals(setOf(primary,source),copy.details!!.fusionRoots!!.toSet())
        assertEquals(setOf(Platform.X,Platform.INSTAGRAM),copy.details!!.threads.map {it.root.platform}.toSet())
        assertEquals(2,copy.details!!.threads.sumOf {it.replies.size})
        runBlocking {repo.library.cancel(copy.key)}
    }
    @Test fun storageSeparatesDownloadedVideosTemporaryVideosAndOptInHistory() {
        val model=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
        ui.runOnIdle {model.settings(SettingsPage.STORAGE);ui.activity.setContent {AntiSocialApp(model)}}
        val list=ui.onNodeWithTag("Settings list")
        list.performScrollToNode(hasText("Video download allowance"))
        ui.onNodeWithText("Downloaded videos").assertExists();evidence("video-download-controls-native")
        list.performScrollToNode(hasText("Video cache limit"))
        ui.onNodeWithText("Video cache limit").assertIsDisplayed()
        ui.onNodeWithText("Keep temporary videos").assertExists();evidence("video-cache-controls-native")
        list.performScrollToNode(hasText("Retain history text"))
        ui.onNodeWithText("History text limit").assertExists();evidence("history-retention-controls-native")
        list.performScrollToNode(hasText("Remove downloaded videos"))
        ui.onNodeWithText("Remove downloaded videos").performClick()
        ui.onNodeWithText("Saved text, images, captured replies and collections stay. Selected videos can be downloaded again from Library.").assertIsDisplayed()
        evidence("remove-videos-keeps-text-dialog-native")
        ui.onNodeWithText("Cancel").performClick()
    }
}
