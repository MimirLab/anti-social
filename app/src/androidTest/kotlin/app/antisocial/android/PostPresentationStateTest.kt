/* anti social, GPL-3.0-or-later. Synthetic native reference/title controls. */
package app.antisocial.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PostPresentationStateTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    @After fun finish(){ui.runOnIdle {ui.activity.setContent {Text("Presentation fixture complete")}}}
    private fun evidence(name:String) {
        ui.waitForIdle()
        ui.waitUntil(5_000){val focused=java.util.concurrent.atomic.AtomicBoolean();ui.runOnUiThread {focused.set(ui.activity.window.decorView.hasWindowFocus())};focused.get()}
        android.os.SystemClock.sleep(700);requireNativeFeedVisible(ui.activity)
        val dir=File(ui.activity.getExternalFilesDir("validation"),"post-presentation-state").apply {mkdirs()}
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {File(dir,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {image.recycle()}
    }
    @Test fun mediaOnlyShortLinkLivesInOneExpandableInventoryAndRetainsItsExactSource() {
        runBlocking {repo.awaitReady()}
        val post=Post("846001",Platform.X,"https://x.com/asworkshop/status/846001","Workshop","https://t.co/WorkshopLink",media=listOf(Media("https://images.fixture.invalid/workshop.png")))
        val before=repo.gson.toJson(post)
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Surface(Modifier.fillMaxSize()) {Column {
            PostText(post,repo,{},showDetails=false)
            PostDetailsInventory(postDetailSections(post,null,listOf(post),repo),PostLinks.urls(post),repo.key(post),{}, {},repo,networkAllowed=false)
        }}}}}
        ui.onNodeWithText(post.body).assertDoesNotExist()
        val toggle=ui.onNodeWithTag("Post extra links ${repo.key(post)}");toggle.assertIsDisplayed();evidence("media-url-caption-collapsed")
        toggle.performClick();ui.onAllNodesWithTag("Post link ${post.body}").assertCountEquals(1);ui.onNodeWithTag("Post body ${repo.key(post)} details text").assertDoesNotExist();evidence("media-url-caption-expanded")
        assertEquals(before,repo.gson.toJson(post));assertEquals(listOf(post.body),PostLinks.urls(post))
    }
    @Test fun aLongCommentThreadTitleIsCompactExpandableAndNeverHidesTheAnswer() {
        runBlocking {repo.awaitReady()}
        val title="A detailed question about shared workshop tools, replacement components, safe maintenance, practical training, public access and the equipment checkout procedures for a neighborhood repair project that welcomes beginners throughout the year"
        val post=Post("846002",Platform.REDDIT,"https://www.reddit.com/r/woodworking/comments/abc123/workshop_question/def456/","asworkshop","The replacement component is available from the workshop desk. Ask the coordinator to inspect the old part before fitting the new one.",title=title,parentId="abc123")
        val before=repo.gson.toJson(post)
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Surface(Modifier.fillMaxSize()) {Column {PostText(post,repo,{},showDetails=false)}}}}}
        ui.onNodeWithText(post.body).assertIsDisplayed()
        val heading=ui.onNodeWithTag("Post thread title ${post.identityKey}")
        val collapsed=heading.fetchSemanticsNode().boundsInRoot.height
        ui.onNodeWithText("Show thread title").assertIsDisplayed();evidence("comment-thread-title-collapsed")
        ui.onNodeWithText("Show thread title").performClick();assertTrue(heading.fetchSemanticsNode().boundsInRoot.height>collapsed)
        ui.onNodeWithText(post.body).assertIsDisplayed();evidence("comment-thread-title-expanded")
        ui.onNodeWithText("Hide thread title").performClick();assertEquals(collapsed,heading.fetchSemanticsNode().boundsInRoot.height)
        assertEquals(before,repo.gson.toJson(post))
    }
    @Test fun anOrdinaryPostTitleRemainsCompleteWithoutTheCommentControl() {
        runBlocking {repo.awaitReady()}
        val title="Workshop equipment access and maintenance information. ".repeat(4)
        val post=Post("846003",Platform.REDDIT,"https://www.reddit.com/r/woodworking/comments/abc123/workshop_question/","asworkshop","Original announcement body.",title=title)
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {Surface(Modifier.fillMaxSize()) {Column {PostText(post,repo,{},showDetails=false)}}}}}
        ui.onNodeWithText(title).assertIsDisplayed();ui.onNodeWithText("Show thread title").assertDoesNotExist()
    }
}
