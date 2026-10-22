/* AntiSocial, GPL-3.0-or-later. Explicit model qualification over public evidence. */
package app.antisocial.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContextSignalModelTest {
    @Test fun selectedServerRejectsTheReportedNoiseAndKeepsConcreteRudeInformation():Unit=runBlocking {
        qualify(false)
    }
    @Test fun phoneModelSelectsUsefulInformationBeforeComposing():Unit=runBlocking {
        qualify(true)
    }
    private suspend fun qualify(local:Boolean) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString(if(local)"antisocialLocalAiModels"else "antisocialContextSignalModel")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=(context as AntiSocialApplication).repo;repo.awaitReady()
        val fixture=File(context.noBackupFilesDir,"context-test-signal.json");assumeTrue(fixture.isFile)
        val data=JsonParser.parseString(fixture.readText()).asJsonObject
        val documents=data["documents"].asJsonArray.map {repo.gson.fromJson(it,ContextDocument::class.java)}.associateBy {it.id}
        val findings=data["findings"].asJsonArray.map {repo.gson.fromJson(it,ContextFinding::class.java)}
        val prefs=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE);val before=prefs.all.toMap()
        val policy=repo.gateway.currentPolicy();val tor=repo.gateway.currentPolicy(NetworkRoute.TOR)
        val slot=aiCredentialSlot("text","signal-qualification");val oldKey=repo.ai.vault.read(slot)
        val calls=mutableListOf<Map<String,Any>>()
        // Isolated R&D controls, never product settings or bundled prompts.
        val alternative=File(context.noBackupFilesDir,"context-test-admission-prompt.txt").takeIf {it.isFile}?.readText()?.also {require(it.length<=16000)}
        fun instruction(system:String,user:String)=if(JsonParser.parseString(user).asJsonObject.has("items"))alternative ?: system else system
        var client:LocalAiClient?=null
        val started=System.currentTimeMillis()
        try {
            repo.ai.enabled(false);repo.contextReader.pauseAll();repo.gateway.configure(NetworkPolicy())
            val caps:ContextModelCapabilities
            val model:ContextModel
            if(local) {
                val weights=File(context.filesDir,"local-ai/test/Ternary-Bonsai-2-2B-PQ2_0.gguf");assumeTrue(weights.isFile)
                val worker=LocalAiClient(context);client=worker
                fun input(system:String="",user:String="")=Bundle().apply {
                    putString("kind","text");putString("path",weights.path);putString("backend","cpu");putString("cacheProfile","pq4_protected_v2")
                    putString("system",system);putString("user",user)
                }
                caps=ContextCapabilityParser.nativeRuntime(JsonParser.parseString(worker.request(AiIpc.PREPARE,input()).getString("diagnostics")).asJsonObject,"2B")
                model=object:ContextModel {
                    override val contextTokens=caps.contextTokens
                    override val maxOutputTokens=512
                    override val processingPlan=ContextProcessingRouter.select(caps,true,maxOutputTokens)
                    override suspend fun count(system:String,user:String)=worker.request(AiIpc.COUNT,input(system,user)).getInt("tokenCount")
                    override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                        val begin=System.currentTimeMillis()
                        val text=withTimeout(300_000){worker.request(AiIpc.TEXT,input(instruction(system,user),user).apply {
                            putInt("maxTokens",maxTokens);putBoolean("jsonMode",true);putBoolean("instructionCache",ContextEngine.isReusableInstruction(instruction(system,user)))
                        }).getString("text")!!}
                        calls+=mapOf("system" to instruction(system,user),"maxTokens" to maxTokens,"input" to JsonParser.parseString(user),"text" to text,"elapsedMs" to System.currentTimeMillis()-begin)
                        return text
                    }
                }
            } else {
                val configFile=File(context.noBackupFilesDir,"context-test-provider.json");assumeTrue(configFile.isFile)
                val config=JsonParser.parseString(configFile.readText()).asJsonObject
                repo.ai.saveProvider(AiKind.TEXT,AiProviderSettings(enabled=true,location=AiLocation.CLOUD,id="signal-qualification",label="Signal qualification",baseUrl=config["baseUrl"].asString,model=config["model"].asString),config["key"].asString)
                repo.ai.selectProvider(AiKind.TEXT,"signal-qualification");repo.ai.routing(AiKind.TEXT,AiRouting.PROVIDER_ONLY);repo.ai.enabled(true)
                val identity=repo.ai.contextIdentity();caps=repo.ai.contextCapabilities(identity)
                model=object:ContextModel {
                    override val contextTokens=caps.contextTokens
                    override val maxOutputTokens=ContextProcessingRouter.outputBudget(caps,false)
                    override val processingPlan=ContextProcessingRouter.select(caps,false,maxOutputTokens)
                    override suspend fun count(system:String,user:String)=repo.ai.contextTokenCount(system,user,identity)
                    override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                        val begin=System.currentTimeMillis()
                        val text=repo.ai.contextText(instruction(system,user),user,maxTokens,identity,caps.vllmChatTemplate&&args.getString("antisocialContextThinking")!="true")
                        calls+=mapOf("system" to instruction(system,user),"maxTokens" to maxTokens,"input" to JsonParser.parseString(user),"text" to text,"elapsedMs" to System.currentTimeMillis()-begin)
                        return text
                    }
                }
            }
            val label=if(local)"phone"else "server"
            fun evidence(extra:Map<String,Any> = emptyMap()) {
                File(context.filesDir,"context-signal-$label.json").writeText(repo.gson.toJson(mapOf("positiveOnly" to (args.getString("antisocialContextPositiveOnly")=="true"),"capabilities" to caps,"plan" to model.processingPlan,"calls" to calls,"elapsedMs" to System.currentTimeMillis()-started,"scope" to "Retained public evidence, production engine and real selected model; emulator timing does not establish physical-phone speed.")+extra))
            }
            if(args.getString("antisocialContextHoldout")=="true") {
                val cases=JsonParser.parseString(File(context.noBackupFilesDir,"context-test-holdout.json").readText()).asJsonArray
                val results=mutableListOf<Map<String,Any>>()
                class SelectionDone:Exception()
                for(case in cases) {
                    val row=case.asJsonObject
                    val passages=row["passages"].asJsonArray.map {it.asJsonObject}
                    val values=passages.map {p->val id=p["id"].asInt.toString();val text=p["text"].asString;ContextFinding(id,text,"context",listOf(id),mapOf(id to text))}
                    val reviewed=mutableListOf<ContextSummaryReceipt>()
                    val onlyAdmission=object:ContextModel by model {
                        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                            if(JsonParser.parseString(user).asJsonObject.has("findings"))throw SelectionDone()
                            return model.generate(system,user,maxTokens)
                        }
                    }
                    val diagnostic=ContextEngine(onlyAdmission,"holdout-$label",{"Public reply"},{emptyList()},
                        {ContextEvidenceScope(ContextTextObservation.COMMENT)},readerBrief=true,readerSubject={row["subject"].asString})
                    try {diagnostic.summarizeResult(values,onCompact={r->reviewed+=r})}catch(_:SelectionDone){}
                    val kept=reviewed.flatMap {review->
                        check(review.level==0&&review.inputs.size==1&&review.sources.size==1)
                        if(JsonParser.parseString(review.text).asJsonObject["keep"].asJsonArray.size()>0)listOf(review.sources.single().toInt())else emptyList()
                    }.sorted()
                    val expected=row["expectedKeep"].asJsonArray.map {it.asInt}
                    results+=mapOf("name" to row["name"].asString,"kept" to kept,"expected" to expected,"exact" to (kept==expected),"usefulMatch" to (kept.isNotEmpty()==row["expectedUseful"].asBoolean))
                    File(context.filesDir,"context-holdout-$label.json").writeText(repo.gson.toJson(mapOf("results" to results,"calls" to calls,"alternative" to alternative,"thinking" to (args.getString("antisocialContextThinking")=="true"),"scope" to "Admission-only diagnostic over a previously observed 24-case set, not end-to-end extraction or population accuracy.")))
                }
                assertEquals(24,results.size)
                return
            }
            if(args.getString("antisocialContextArticle")=="true") {
                val article=JsonParser.parseString(File(context.noBackupFilesDir,"context-test-article.json").readText()).asJsonObject
                val doc=ContextDocument("public-article","r1",article["text"].asString,kind="article",url=article["url"].asString)
                val articleEngine=ContextEngine(model,"article-$label",{"Publisher"},{emptyList()},
                    {ContextEvidenceScope(ContextTextObservation.PAGE)},readerBrief=true,readerSubject={article["root"].asString})
                val extracted=articleEngine.process(listOf(doc))
                val result=articleEngine.summarizeResult(extracted.findings)
                File(context.filesDir,"context-article-model.json").writeText(repo.gson.toJson(mapOf("result" to result,"calls" to calls,"units" to extracted.outcomes)))
                assertTrue("The acquired article must yield information",result.text.isNotBlank())
                assertTrue(result.text.contains("28"));assertTrue(result.text.contains("alleg",true)||result.text.contains("suspect",true)||result.text.contains("charg",true))
                assertFalse(result.text.contains("comment",true));assertFalse(result.text.contains("newsletter",true))
                assertFalse("A political reaction is not another finding",result.text.contains("political move",true)||result.text.contains("suppress voters",true)||Regex("(?:officials|critics|supporters) (?:dispute|disagree|argue)",RegexOption.IGNORE_CASE).containsMatchIn(result.text))
                val count=calls.size
                articleEngine.process(listOf(doc),extracted.findings,extracted.outcomes)
                articleEngine.summarizeResult(extracted.findings,result.text,result.receipts)
                assertEquals("Unchanged article work must be reused",count,calls.size)
                return
            }
            val engine=ContextEngine(model,"signal-$label",{documents[it]?.author.orEmpty()}, {emptyList()},
                {documents[it]?.let(ContextEvidenceScope::from)},readerBrief=true,readerSubject={data["root"].asString})
            val negativeInputs=if(args.getString("antisocialContextPositiveOnly")=="true")emptyList()else findings
            val noisy=try {engine.summarizeResult(negativeInputs,"The old noisy brief")}finally {evidence()}
            evidence(mapOf("noise" to noisy))
            assertEquals("The reported pitches, taunts and vague assertions must not become a brief","",noisy.text)
            val n=calls.size;engine.summarizeResult(negativeInputs,completed=noisy.receipts);assertEquals(n,calls.size)
            val positive=listOf(
                "Read the damn service manual: this laptop needs 56 Wh, not 65 Wh. I fitted this exact battery yesterday.",
                "I reseated the loose connector on this model; the original battery works again. I have not tested other models.",
                "How about that unrelated stock price? LOOSER!",
                "I think this repair guide is the greatest guide ever. Our team is absolutely wonderful!",
                "I tested a pasta recipe yesterday; boiling the noodles for 9 minutes worked."
            ).mapIndexed {i,text->ContextFinding("p$i",text,if(i==0)"correction"else "context",listOf("p$i"),mapOf("p$i" to text))}
            val positiveEngine=ContextEngine(model,"positive-$label",{"Repair visitor"},{emptyList()},
                {ContextEvidenceScope(ContextTextObservation.COMMENT)},readerBrief=true,readerSubject={"A repair guide recommends a 65 Wh battery for this laptop."})
            val result=try {positiveEngine.summarizeResult(positive)}finally {evidence(mapOf("noise" to noisy))}
            evidence(mapOf("noise" to noisy,"positive" to result))
            assertTrue(result.text.contains("56"));assertTrue(result.text.contains("connector",true))
            assertFalse("Past observations must not become impossible instructions",Regex("(?:^|[.!?]\\s+)(?:Fit|Install|Replace|Reseat)\\b[^.!?]*yesterday",RegexOption.IGNORE_CASE).containsMatchIn(result.text))
            assertFalse(result.text.contains("LOOSER",true));assertFalse(result.text.contains("stock",true))
            assertFalse(result.text.contains("wonderful",true));assertFalse(result.text.contains("pasta",true))
            assertFalse(result.sources.any {it in setOf("p2","p3","p4")})
            if(!local) {
                val reader=repo.contextReader
                val post=Post("signal-screen",Platform.X,"https://x.com/context_fixture/status/990990","Repair workshop","A guide recommends a replacement battery.")
                val record=reader.store.resolve(ContextSnapshot(listOf(post),post.body,"","signal-screen"))
                try {
                    suspend fun screen(name:String,brief:String) {
                        if(brief.isNotBlank())result.findings.forEachIndexed {i,f->
                            val doc=ContextDocument("p$i","1",f.quotes.getValue("p$i"),kind="comment",author="Repair visitor")
                            reader.store.ingest(record.id,listOf(doc))
                            reader.store.commitUnit(record.id,ContextUnitOutcome("p$i",doc.id,doc.revision,0,doc.text.length,ContextUnitStatus.SIGNAL,listOf(f)))
                        }
                        reader.store.publish(record.copy(brief=brief,status="ready",message="Context ready",updatedAt=System.currentTimeMillis(),checkedAt=System.currentTimeMillis(),nextCheck=System.currentTimeMillis()+3600000,deliveryPolicy=reader.deliveryIdentity(),briefFindingIds=if(brief.isBlank())emptyList()else result.selectedFindings,briefSources=if(brief.isBlank())emptyList()else result.sources))
                        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("openContext",record.id))
                        withContext(Dispatchers.Main){reader.close();reader.open(record.id)}
                        fun contains(node:android.view.accessibility.AccessibilityNodeInfo?,value:String):Boolean {
                            if(node==null)return false
                            if(node.text?.toString()?.contains(value)==true)return true
                            return (0 until node.childCount).any {contains(node.getChild(it),value)}
                        }
                        val wanted=brief.ifBlank {"No useful additions found"}
                        withTimeout(15_000){while(!contains(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow,wanted))delay(200)}
                        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {bitmap->
                            File(context.filesDir,"context-signal-$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                        }
                    }
                    screen("empty",noisy.text);reader.close();delay(300);screen("useful",result.text)
                } finally {reader.close();reader.store.delete(record.id)}
            }
        } finally {
            client?.release();repo.ai.enabled(false);repo.ai.vault.write(slot,oldKey)
            val edit=prefs.edit().clear();before.forEach {(k,v)->when(v){is Boolean->edit.putBoolean(k,v);is String->edit.putString(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);else->error("Unexpected fixture preference")}};edit.commit()
            repo.gateway.configure(policy);repo.gateway.configureTor(tor);repo.ai.refresh()
        }
    }
}
