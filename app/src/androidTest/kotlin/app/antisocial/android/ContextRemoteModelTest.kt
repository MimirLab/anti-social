/* AntiSocial, GPL-3.0-or-later. Explicit opt-in development qualification only. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Credentials are staged privately by the operator, never compiled into an
 * APK, passed as an instrumentation argument, or written into evidence. The
 * selected real model uses the production NetworkGateway and encrypted vault.
 * Only the public discussion is replaced by a deterministic local fixture. */
@RunWith(AndroidJUnit4::class)
class ContextRemoteModelTest {
    @Test fun selectedRealModelPreservesARudeCorrectionAndReusesCompletedWork(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialContextRemoteModel")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val configFile=File(context.noBackupFilesDir,"context-test-provider.json")
        assumeTrue("Stage the private development provider file",configFile.isFile)
        val config=JsonParser.parseString(configFile.readText()).asJsonObject
        val repo=(context as AntiSocialApplication).repo
        repo.awaitReady()
        val reader=repo.contextReader
        val prefs=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE)
        val before=prefs.all.toMap()
        val oldOptions=reader.preferences.state.value
        val oldCommunity=repo.prefs.communityComments
        val oldDiscussion=reader.discussionRead
        val oldDecorator=reader.modelDecorator
        val oldPolicy=repo.gateway.currentPolicy()
        val oldTor=repo.gateway.currentPolicy(NetworkRoute.TOR)
        val suffix=System.nanoTime().toString().takeLast(10)
        val providerId="context-real-test"
        val slot=aiCredentialSlot("text",providerId)
        val oldKey=repo.ai.vault.read(slot)
        var owner: String?=null
        var root: Post?=null
        val started=System.currentTimeMillis()
        try {
            repo.ai.enabled(false);reader.pauseAll()
            withTimeout(10_000){reader.active.first {it.isEmpty()}}
            reader.preferences.update(ContextOptions())
            repo.prefs.communityComments=false
            repo.gateway.configure(NetworkPolicy())
            val provider=AiProviderSettings(enabled=true,location=AiLocation.CLOUD,id=providerId,label="Development qualification",
                baseUrl=config["baseUrl"].asString,model=config["model"].asString)
            repo.ai.saveProvider(AiKind.TEXT,provider,config["key"].asString)
            repo.ai.selectProvider(AiKind.TEXT,providerId)
            repo.ai.routing(AiKind.TEXT,AiRouting.PROVIDER_ONLY)
            repo.ai.enabled(true)
            val post=Post("context-real-$suffix",Platform.X,"https://x.com/context_fixture/status/991$suffix","Repair workshop",
                "The replacement battery for the workshop laptop is 65 Wh. The repair session is on Friday.",publishedAt=started)
            root=post
            val payload=repo.gson.toJson(post)
            repo.dao.putPost(PostRow(repo.key(post),post.platform.id,payload,started,started,bytes=payload.toByteArray().size.toLong()))
            val correction=post.copy(id="correction-$suffix",canonicalUrl="https://x.com/context_reply/status/992$suffix",author="Repair technician",
                body="You have it backwards, genius. The service manual says 56 Wh, not 65 Wh. I replaced this exact battery yesterday.",parentId=post.id)
            val noise=post.copy(id="noise-$suffix",canonicalUrl="https://x.com/context_reply/status/993$suffix",author="Bystander",body="lol",parentId=post.id)
            reader.discussionRead={_,_->Page(listOf(post,correction,noise))}
            val record=reader.store.resolve(ContextSnapshot(listOf(post),post.body,"",suffix))
            owner=record.id
            val calls=mutableListOf<Map<String,Any>>()
            reader.modelDecorator={delegate->
                object:ContextModel by delegate {
                    override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                        val text=delegate.generate(system,user,maxTokens)
                        calls+=mapOf("system" to system,"input" to JsonParser.parseString(user),"text" to text,"maxTokens" to maxTokens)
                        File(context.filesDir,"context-real-$suffix-calls.json").writeText(repo.gson.toJson(calls))
                        return text
                    }
                }
            }
            withTimeout(240_000){reader.run(record.id)}
            val first=reader.store.record(record.id)!!
            fun capture(label:String,value:ContextRecord) {
                val data=JsonObject().apply {
                    add("record",repo.gson.toJsonTree(value));addProperty("units",reader.store.checkpointUnits(record.id))
                    add("documents",repo.gson.toJsonTree(reader.store.documents(record.id)))
                    add("findings",repo.gson.toJsonTree(reader.store.findings(record.id)))
                    add("streams",repo.gson.toJsonTree(reader.store.streams(record.id)))
                }
                File(context.filesDir,"context-real-$suffix-$label.json").writeText(data.toString())
            }
            capture("first",first)
            assertTrue("Real model read should complete: ${first.status}: ${first.message}",first.status in setOf("ready","partial"))
            assertTrue("The real model must produce usable Context",first.brief.isNotBlank())
            assertFalse("Internal source aliases must never become prose",Regex("\\b[SK][0-9]+\\b").containsMatchIn(first.brief))
            assertFalse("A capacity correction must not invent a change to the session date",first.brief.contains("not Friday",ignoreCase=true))
            val findings=reader.store.findings(record.id)
            assertTrue("Hostility must not erase a substantive correction",findings.any {finding->
                finding.text.contains("56")&&finding.quotes.values.any {it.contains("56 Wh")&&correction.body.contains(it)}
            })
            assertTrue(reader.capability.value!!.contextTokens>=2048)
            // A deferred malformed answer is unfinished work, not a cache hit.
            // Resume only those passages before qualifying unchanged reuse.
            repeat(2) {retry->
                if(reader.store.streams(record.id).any {it.kind=="review"}) {
                    withTimeout(120_000){reader.run(record.id)}
                    capture("retry${retry+1}",reader.store.record(record.id)!!)
                }
            }
            assertTrue("Selected model left a fixture passage pending after bounded retries",reader.store.streams(record.id).none {it.kind=="review"})
            val stable=reader.store.record(record.id)!!
            capture("stable",stable)
            val units=reader.store.checkpointUnits(record.id)
            withTimeout(120_000){reader.run(record.id)}
            val second=reader.store.record(record.id)!!
            capture("second",second)
            assertEquals(stable.findingStamp,second.findingStamp)
            assertEquals(stable.updatedAt,second.updatedAt)
            assertEquals(units,reader.store.checkpointUnits(record.id))
            val evidence=JsonObject().apply {
                addProperty("passed",true);addProperty("elapsedMs",System.currentTimeMillis()-started)
                addProperty("contextTokens",reader.capability.value!!.contextTokens)
                addProperty("capacitySource",reader.capability.value!!.source)
                addProperty("checkpointUnits",units);addProperty("findings",findings.size)
                addProperty("rudeCorrectionRetained",true);addProperty("unchangedRefreshReusedWork",true)
                addProperty("brief",first.brief)
                add("findingDetails",repo.gson.toJsonTree(findings))
            }
            File(context.filesDir,"context-real-model-evidence.json").writeText(evidence.toString())
        } finally {
            reader.pauseAll();repo.ai.enabled(false)
            withTimeout(10_000){reader.active.first {it.isEmpty()}}
            reader.discussionRead=oldDiscussion;reader.modelDecorator=oldDecorator
            owner?.let {reader.store.delete(it)}
            root?.let {repo.dao.removePosts(listOf(repo.key(it)))}
            repo.ai.vault.write(slot,oldKey)
            val edit=prefs.edit().clear()
            before.forEach {(key,value)->when(value){
                is Boolean->edit.putBoolean(key,value);is String->edit.putString(key,value);is Int->edit.putInt(key,value)
                is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)
                else->error("Unsupported fixture preference")
            }}
            edit.commit();reader.preferences.update(oldOptions);reader.invalidateCapabilities()
            repo.prefs.communityComments=oldCommunity
            repo.gateway.configure(oldPolicy);repo.gateway.configureTor(oldTor)
            repo.ai.refresh()
            configFile.delete()
        }
    }
}
