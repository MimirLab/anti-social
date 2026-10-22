/* AntiSocial, GPL-3.0-or-later. Explicit live-source/server qualification, isolated device only. */
package app.antisocial.android

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContextRoundupModelTest {
    @Test fun actualRoundupReadsItsLinkedPostsAndPublishesOneBrief():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialContextRoundup")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val providerFile=File(context.noBackupFilesDir,"context-test-provider.json")
        val sourceFile=File(context.noBackupFilesDir,"context-test-roundup.json")
        assumeTrue(providerFile.isFile&&sourceFile.isFile)
        val config=JsonParser.parseString(providerFile.readText()).asJsonObject
        val repo=(context as AntiSocialApplication).repo;repo.awaitReady()
        val reader=repo.contextReader
        val prefs=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE);val before=prefs.all.toMap()
        val options=reader.preferences.state.value;val community=repo.prefs.communityComments
        val policy=repo.gateway.currentPolicy();val tor=repo.gateway.currentPolicy(NetworkRoute.TOR)
        val discussion=reader.discussionRead
        val slot=aiCredentialSlot("text","roundup-real-test");val oldKey=repo.ai.vault.read(slot)
        var owner:String?=null;var post:Post?=null
        val started=System.currentTimeMillis()
        try {
            repo.ai.enabled(false);reader.pauseAll();withTimeout(10_000){reader.active.first {it.isEmpty()}}
            repo.gateway.configure(NetworkPolicy());repo.prefs.communityComments=false
            reader.preferences.update(ContextOptions())
            repo.ai.saveProvider(AiKind.TEXT,AiProviderSettings(enabled=true,location=AiLocation.CLOUD,id="roundup-real-test",label="Roundup qualification",baseUrl=config["baseUrl"].asString,model=config["model"].asString),config["key"].asString)
            repo.ai.selectProvider(AiKind.TEXT,"roundup-real-test");repo.ai.routing(AiKind.TEXT,AiRouting.PROVIDER_ONLY);repo.ai.enabled(true)
            val root=repo.gson.fromJson(sourceFile.readText(),Post::class.java);post=root
            val payload=repo.gson.toJson(root)
            repo.dao.putPost(PostRow(repo.key(root),root.platform.id,payload,root.publishedAt,started,bytes=payload.toByteArray().size.toLong()))
            val snapshot=contextSnapshot(repo,root,listOf(root),listOf(root))
            assertTrue(snapshot.links.contains("https://x.com/WHOSTP47/status/2110521283779674185"))
            assertFalse(snapshot.text.contains("nitter."));assertTrue(snapshot.text.length<240)
            val record=reader.store.resolve(snapshot);owner=record.id
            // This run isolates the post and attached material. Platform discussion
            // acquisition has separate controller/native coverage; no invented replies.
            reader.discussionRead={_,_->Page(listOf(root))}
            withTimeout(900_000){reader.run(record.id)}
            val view=reader.store.view(record.id)!!
            val links=reader.store.streams(record.id).filter {it.kind=="link"&&it.commentContext.isBlank()}
            val evidence=mapOf("elapsedMs" to System.currentTimeMillis()-started,"capability" to reader.capability.value,"processingPlan" to reader.processingPlan.value,"view" to view,"documents" to reader.store.documents(record.id),"streams" to reader.store.streams(record.id),"links" to links.size,"scope" to "Actual public post and linked-post acquisition through production NetworkGateway/source pool, selected real remote model. Descriptive post text does not establish video transcript coverage.")
            File(context.filesDir,"context-roundup-evidence.json").writeText(repo.gson.toJson(evidence))
            assertEquals(20,links.size)
            assertTrue("Roundup must produce a useful brief: "+view.record.message,view.record.brief.length>120)
            assertTrue("At least the majority of direct post sources must actually be acquired",links.count {it.status=="done"}>=15)
            assertFalse(view.record.brief.contains("some commenters",true));assertFalse(view.record.brief.contains("people disagree",true))
            assertTrue(view.record.brief.length<reader.store.documents(record.id).filter {it.document.kind=="article"}.sumOf {it.document.text.length})
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("openContext",record.id))
            // Wait for the actual sheet instead of accepting a launcher/feed
            // screenshot while Activity startup is still being dispatched.
            withContext(Dispatchers.Main){reader.open(record.id)}
            fun hasBrief():Boolean {
                fun find(node:android.view.accessibility.AccessibilityNodeInfo?):Boolean {
                    if(node==null)return false
                    if(node.text?.toString()=="THE BRIEF")return true
                    return (0 until node.childCount).any {find(node.getChild(it))}
                }
                return find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
            }
            withTimeout(15_000){while(!hasBrief())delay(200)}
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {bitmap->File(context.filesDir,"context-roundup-native.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        } finally {
            reader.close();reader.pauseAll();repo.ai.enabled(false);withTimeout(10_000){reader.active.first {it.isEmpty()}}
            reader.discussionRead=discussion
            owner?.let {reader.store.delete(it)};post?.let {repo.dao.removePosts(listOf(repo.key(it)))}
            repo.ai.vault.write(slot,oldKey)
            val edit=prefs.edit().clear()
            before.forEach {(key,value)->when(value){is Boolean->edit.putBoolean(key,value);is String->edit.putString(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value);else->error("Unsupported preference")}}
            edit.commit();reader.preferences.update(options);reader.invalidateCapabilities();repo.prefs.communityComments=community
            repo.gateway.configure(policy);repo.gateway.configureTor(tor);repo.ai.refresh();providerFile.delete();sourceFile.delete()
        }
    }
}
