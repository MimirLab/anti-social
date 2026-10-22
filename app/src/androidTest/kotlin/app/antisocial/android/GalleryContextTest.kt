/* AntiSocial, GPL-3.0-or-later. Opt-in retained public example, real OCR/model/native UI. */
package app.antisocial.android

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GalleryContextTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun retainedGalleryCollectsAllOriginalsAndOneTapDeliversAnImageBrief() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialGalleryContext")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {repo.awaitReady()}
        val context=ui.activity;val reader=repo.contextReader
        val configFile=File(context.noBackupFilesDir,"context-test-provider.json")
        val fixtureFile=File(context.noBackupFilesDir,"gallery-context-fixture.json")
        assumeTrue(configFile.isFile&&fixtureFile.isFile)
        val config=JsonParser.parseString(configFile.readText()).asJsonObject
        val fixture=JsonParser.parseString(fixtureFile.readText()).asJsonObject
        val posts=fixture["posts"].asJsonArray.map {repo.gson.fromJson(it,Post::class.java)}
        val follows=fixture["follows"].asJsonArray.map {repo.gson.fromJson(it,Follow::class.java)}
        val prefs=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE)
        val oldPrefs=prefs.all.toMap();val oldOptions=reader.preferences.state.value
        val oldPolicy=repo.gateway.currentPolicy();val oldCommunity=repo.prefs.communityComments
        val oldDiscussion=reader.discussionRead;val oldDecorator=reader.modelDecorator
        val slot=aiCredentialSlot("text","gallery-qualification");val oldKey=repo.ai.vault.read(slot)
        var owner:String?=null
        val imageDirectory=File(context.cacheDir,"gallery-context-qualification").apply {mkdirs()}
        val mediaKeys=mutableListOf<String>();val calls=mutableListOf<Map<String,Any>>()
        fun capture() {owner?.let {id->File(context.filesDir,"gallery-context-result.json").writeText(repo.gson.toJson(mapOf(
            "record" to reader.store.record(id),"documents" to reader.store.documents(id),"streams" to reader.store.streams(id),"calls" to calls)))}}
        try {
            runBlocking {
                repo.ai.enabled(false);reader.pauseAll();withTimeout(10_000){reader.active.first {it.isEmpty()}}
                repo.gateway.configure(NetworkPolicy());repo.prefs.communityComments=false
                repo.ai.saveProvider(AiKind.TEXT,AiProviderSettings(enabled=true,location=AiLocation.CLOUD,id="gallery-qualification",label="Gallery qualification",baseUrl=config["baseUrl"].asString,model=config["model"].asString),config["key"].asString)
                repo.ai.selectProvider(AiKind.TEXT,"gallery-qualification");repo.ai.routing(AiKind.TEXT,AiRouting.PROVIDER_ONLY);repo.ai.enabled(true)
                reader.preferences.update(ContextOptions());reader.discussionRead={root,_->Page(listOf(root))}
                for(item in fixture["images"].asJsonArray) {
                    val row=item.asJsonObject;val url=row["url"].asString
                    val file=File(imageDirectory,row["file"].asString)
                    File(context.noBackupFilesDir,row["file"].asString).copyTo(file,overwrite=true)
                    val bitmap=BitmapFactory.decodeFile(file.path) ?: error("Missing retained image")
                    try {repo.images.identities.observe(url,bitmap)}finally {bitmap.recycle()}
                    val key=Repository.hash(url);mediaKeys+=key
                    repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis()))
                }
                val captured=posts.map(repo.images.identities::captured)
                val grouped=CrossPosts.group(captured,follows,sameMedia=repo.images.identities::same)
                assertEquals("The actual four retained originals belong to one gallery",1,grouped.size)
                val roots=grouped.single().posts
                assertEquals(4,roots.size);assertEquals(3,collectedMedia(roots).size)
                assertEquals(1,collectedCaptionTexts(roots).size)
                assertEquals(4,repo.library.normalizedFusionRoots(roots.first(),roots).size)
                File(context.filesDir,"gallery-group-result.json").writeText(repo.gson.toJson(mapOf("groups" to grouped,"media" to collectedMedia(roots),"caption" to collectedCaptionTexts(roots))))
                reader.modelDecorator={delegate->object:ContextModel by delegate {
                    override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                        val text=delegate.generate(system,user,maxTokens)
                        calls+=mapOf("system" to system,"input" to JsonParser.parseString(user),"text" to text);capture();return text
                    }
                }}
            }
            val roots=CrossPosts.group(posts.map(repo.images.identities::captured),follows,sameMedia=repo.images.identities::same).single().posts
            val display=collectedCaptionPost(roots)!!
            ui.runOnIdle {ui.activity.setContent {MaterialTheme {Column {ContextPostAction(repo,display,roots);ContextSheet(repo){}}}}}
            ui.onNodeWithTag("Context ${repo.key(display)}").performClick()
            ui.waitUntil(30_000){reader.store.forOriginals(roots.map(repo::key)).firstOrNull()?.also {owner=it.id}!=null}
            ui.waitUntil(300_000){reader.store.record(owner!!)?.let {it.checkedAt>0&&it.cycleStartedAt==0L&&it.status in setOf("ready","partial")||it.status=="paused"}==true}
            capture()
            val done=reader.store.record(owner!!)!!
            assertTrue("Actual image reading failed: ${done.status}: ${done.message}",done.status in setOf("ready","partial"))
            assertTrue("The image text must yield a brief",done.brief.isNotBlank())
            val names=listOf("Locklear","Speight","Brown")
            assertTrue("All three pictured cases must keep their identities",names.all {done.brief.contains(it,true)})
            fun case(name:String):String {
                val start=done.brief.indexOf(name,ignoreCase=true)
                val end=names.filter {it!=name}.map {done.brief.indexOf(it,start+name.length,ignoreCase=true)}.filter {it>=0}.minOrNull() ?: done.brief.length
                return done.brief.substring(start,end)
            }
            assertTrue("The 17-year-old case belongs to Locklear",case("Locklear").contains("17"))
            assertTrue("Eight convictions belong to Speight",case("Speight").contains("8")||case("Speight").contains("eight",true))
            assertFalse("Locklear's events cannot be moved to Speight",case("Speight").contains("17-year",true))
            assertTrue("The Zarutska case belongs to Brown",case("Brown").contains("Zarutska",true))
            assertEquals(3,reader.store.documents(owner!!).count {it.document.id.startsWith("ocr:")&&it.document.text.isNotBlank()})
            reader.store.documents(owner!!).map {it.document}.filter {it.id.startsWith("ocr:")}.forEach {doc->
                val original=roots.first {post->post.media.any {it.url==doc.metadata["mediaUrl"]}}
                assertEquals("Image evidence keeps its actual publication root",CrossPosts.originalKey(original),doc.metadata["root"])
            }
            ui.onNodeWithTag("Context brief").assertTextEquals(done.brief)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
                try {File(context.filesDir,"gallery-context-screen.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
            }
            assertFalse(done.brief.contains("commenter",true))
            assertFalse("The brief gives the information, not a narration of the post",done.brief.contains("post highlights",true))
            val count=calls.size
            reader.close();reader.open(owner!!)
            ui.waitForIdle();Thread.sleep(600)
            assertEquals("Reopening must use the cached brief",done,reader.store.record(owner!!));assertEquals(count,calls.size)
        } finally {
            capture();ui.runOnIdle {reader.close();ui.activity.setContent {Text("Gallery Context qualification complete")}}
            runBlocking {
                reader.pauseAll();repo.ai.enabled(false);withTimeout(10_000){reader.active.first {it.isEmpty()}}
                reader.discussionRead=oldDiscussion;reader.modelDecorator=oldDecorator
                owner?.let {reader.store.delete(it)};repo.dao.removeMedia(mediaKeys)
                imageDirectory.deleteRecursively()
                repo.ai.vault.write(slot,oldKey)
                val edit=prefs.edit().clear();oldPrefs.forEach {(key,value)->when(value){is Boolean->edit.putBoolean(key,value);is String->edit.putString(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)}};edit.commit()
                reader.preferences.update(oldOptions);reader.invalidateCapabilities();repo.prefs.communityComments=oldCommunity;repo.gateway.configure(oldPolicy);repo.ai.refresh()
                configFile.delete()
            }
        }
    }
}
