/* AntiSocial, GPL-3.0-or-later. Isolated synthetic-job audit, never app code. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private data class PipelineCheckpoint(
    val findings: List<ContextFinding>, val units: List<ContextUnitOutcome>,
    val brief: String, val compactions: List<ContextSummaryReceipt>,val documents:List<ContextDocument>?=null
)

/** Uses the real source planner, validator, compactor and durable receipts.
 * Inputs are frozen English fixtures. Collection/translation and Android driver
 * behavior require their own checks. Neither syntax nor successful completion
 * certifies the generated prose: the evidence includes criteria for review. */
fun main() = runBlocking {
    val root=File(System.getProperty("antisocialBuildRoot")).canonicalFile
    val out=File(System.getProperty("antisocial.pipeline.output")).canonicalFile
    require(out.path.startsWith(root.path+File.separator))
    require(!File(out,"jobs.jsonl").exists()) { "Refusing to overwrite evidence" }
    val gson=Gson()
    val mode=System.getProperty("antisocial.pipeline.model")
    require(mode in setOf("2B","4B","9B","server"))
    val calls=mutableListOf<Map<String,Any>>()
    var countRequests=0
    var generationAttempts=0
    var completedGenerations=0
    suspend fun generation(system:String,user:String,maxTokens:Int,run:suspend ()->String):String {
        val attempt=++generationAttempts;val begin=System.nanoTime()
        val journal=File(out,"generation-events.jsonl")
        journal.appendText(gson.toJson(mapOf("attempt" to attempt,"event" to "started","system" to system,"input" to JsonParser.parseString(user),"maxTokens" to maxTokens))+"\n")
        try {
            return run().also {completedGenerations++;journal.appendText(gson.toJson(mapOf("attempt" to attempt,"event" to "completed","elapsedMs" to (System.nanoTime()-begin)/1_000_000))+"\n")}
        }catch(failure:Exception) {
            journal.appendText(gson.toJson(mapOf("attempt" to attempt,"event" to "failed","errorType" to failure.javaClass.simpleName,"elapsedMs" to (System.nanoTime()-begin)/1_000_000))+"\n")
            throw failure
        }
    }
    val summaryLimit=System.getProperty("antisocial.pipeline.summaryGroupLimit","0").toInt().also {require(it in setOf(0,4,8,16))}
    fun scoped(plan:ContextProcessingPlan):ContextProcessingPlan {
        val screen=System.getProperty("antisocial.pipeline.commentScreen","production").also {require(it in setOf("production","disabled"))}
        val base=if(screen=="disabled")plan.copy(screenComments=false)else plan
        val selected=if(summaryLimit==0)base else base.copy(summaryGroupLimit=summaryLimit)
        return if(System.getProperty("antisocial.pipeline.composition","baseline") in setOf("source_selection","source_atomic"))selected.copy(extractiveFindings=true) else selected
    }
    val requestedOutput=System.getProperty("antisocial.pipeline.outputTokens","0").toInt().also {require(it in 0..8192)}
    val composition=System.getProperty("antisocial.pipeline.composition","baseline").also {require(it in setOf("baseline","publication","source_selection","source_atomic"))}
    val observationScope=System.getProperty("antisocial.pipeline.observationScope","data_only").also {require(it in setOf("enabled","disabled","data_only"))}
    val postBackground=System.getProperty("antisocial.pipeline.postBackground","legacy").also {require(it in setOf("legacy","typed"))}
    val readerCharacters=System.getProperty("antisocial.pipeline.readerCharacters","0").toInt().also {require(it==0||it in 256..32768)}.takeIf {it>0}
    require(readerCharacters==null||composition=="source_selection")
    fun outputBudget(caps:ContextModelCapabilities,local:Boolean,style:ContextProcessingStyle=ContextProcessingStyle.AUTO):Int {
        if(requestedOutput==0)return ContextProcessingRouter.outputBudget(caps,local,style)
        require(requestedOutput<=minOf(if(local)512 else 8192,caps.maxOutputTokens ?: 8192,caps.contextTokens/4)) {"Requested output exceeds an observed runtime or working-window limit"}
        return requestedOutput
    }
    var native: Process?=null
    var nativeTransportUsable=true
    val exchangeTimeout=System.getProperty("antisocial.pipeline.exchangeTimeoutSeconds","300").toLong().also {require(it in 1..900)}
    var remoteClient: okhttp3.OkHttpClient?=null
    val responses=Executors.newSingleThreadExecutor { task -> Thread(task,"Pipeline native responses").apply { isDaemon=true } }
    var model: ContextModel
    val profile=System.getProperty("antisocial.pipeline.identity")
    val started=System.nanoTime()
    try {
        if(mode!="server") {
            val command=listOf(System.getProperty("antisocial.pipeline.binary"),System.getProperty("antisocial.pipeline.weights"),
                System.getProperty("antisocial.pipeline.backend"),System.getProperty("antisocial.pipeline.cache"),
                System.getProperty("antisocial.pipeline.capacity"),System.getProperty("antisocial.pipeline.position"),System.getProperty("antisocial.pipeline.threads"))
            native=ProcessBuilder(command).redirectError(File(out,"native.log")).start()
            val writer=native.outputStream.bufferedWriter();val reader=native.inputStream.bufferedReader()
            fun exchange(op:String,system:String="",user:String="",maxTokens:Int=0):JsonObject {
                check(nativeTransportUsable) {"Native response transport is unavailable after an earlier failure"}
                val begin=System.nanoTime()
                val cache=if(ContextEngine.isReusableInstruction(system)) System.getProperty("antisocial.pipeline.instructionBytes").toInt() else 0
                // There are no response IDs on this test worker's protocol.
                // A timed-out read cannot stay queued while another request is
                // sent: its late response would be mistaken for the next one.
                val line=try {
                    writer.write(gson.toJson(mapOf("op" to op,"system" to system,"user" to user,"maxTokens" to maxTokens,"instructionCacheBytes" to cache,"samplingProfile" to System.getProperty("antisocial.pipeline.sampling"))));writer.newLine();writer.flush()
                    responses.submit<String> {reader.readLine() ?: error("Native worker ended")}.get(exchangeTimeout,TimeUnit.SECONDS)
                }catch(failure:Exception) {
                    nativeTransportUsable=false
                    native.destroyForcibly()
                    throw failure
                }
                val response=JsonParser.parseString(line).asJsonObject
                if(op=="generate") {
                    val call=mapOf("system" to system,"input" to JsonParser.parseString(user),"maxTokens" to maxTokens,"response" to response,"elapsedMs" to (System.nanoTime()-begin)/1_000_000)
                    calls+=call
                    File(out,"calls.jsonl").appendText(gson.toJson(call)+"\n")
                }
                check(!response.has("error")) { response["error"].asString }
                if(op=="generate") check(response["diagnostics"].asJsonObject["sampling_profile"].asString==System.getProperty("antisocial.pipeline.sampling"))
                return response
            }
            val metadata=exchange("metadata")
            File(out,"runtime.json").writeText(gson.toJson(metadata))
            val caps=ContextCapabilityParser.nativeRuntime(metadata,mode)
            check(caps.contextTokens==System.getProperty("antisocial.pipeline.capacity").toInt())
            check(metadata["threads"].asInt==System.getProperty("antisocial.pipeline.threads").toInt())
            model=object:ContextModel {
                override val contextTokens=caps.contextTokens
                override val maxOutputTokens=outputBudget(caps,true)
                override val shortDecisionTokens=32
                override val processingPlan=scoped(ContextProcessingRouter.select(caps,true,maxOutputTokens))
                override suspend fun count(system:String,user:String):Int {countRequests++;return exchange("count",system,user)["count"].asInt}
                override suspend fun generate(system:String,user:String,maxTokens:Int)=generation(system,user,maxTokens) {exchange("generate",system,user,maxTokens)["text"].asString}
            }
        } else {
            val config=JsonParser.parseString(File(root,"context-foundation/private/provider.json").readText()).asJsonObject
            val cleaner=UrlCleaner(JsonParser.parseReader(ContextEngine::class.java.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
            val gateway=NetworkGateway(cleaner,object:RequestFilter { override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false) })
            remoteClient=gateway.client()
            val provider=AiRemoteProvider(gateway,AiRemoteConfig(config["baseUrl"].asString,config["model"].asString,config["key"].asString))
            val caps=provider.contextCapabilities()
            File(out,"runtime.json").writeText(gson.toJson(caps))
            val requestedStyle=ContextProcessingStyle.valueOf(System.getProperty("antisocial.pipeline.style","AUTO"))
            model=object:ContextModel {
                override val contextTokens=caps.contextTokens
                override val maxOutputTokens=outputBudget(caps,false,requestedStyle)
                override val processingPlan=scoped(ContextProcessingRouter.select(caps,false,maxOutputTokens,requestedStyle))
                override suspend fun count(system:String,user:String):Int {countRequests++;return system.toByteArray().size+user.toByteArray().size+128}
                override suspend fun generate(system:String,user:String,maxTokens:Int):String=generation(system,user,maxTokens) {
                    val begin=System.nanoTime();val text=provider.text(system,user,maxTokens)
                    val call=mapOf("system" to system,"input" to JsonParser.parseString(user),"maxTokens" to maxTokens,"text" to text,"elapsedMs" to (System.nanoTime()-begin)/1_000_000)
                    calls+=call;File(out,"calls.jsonl").appendText(gson.toJson(call)+"\n");text
                }
            }
        }
        val passageMode=System.getProperty("antisocial.pipeline.passageReview","selector")
        val passageClosure=System.getProperty("antisocial.pipeline.passageClosure","false").toBooleanStrict()
        if(passageMode!="selector")model=ContextBinaryPassageAudit(model,passageMode,passageClosure,File(out,"binary-passages.jsonl"))
        val fixture=File(out,"pipeline-jobs-v1.json")
        val cases=JsonParser.parseString(fixture.readText()).asJsonArray
        val selected=System.getProperty("antisocial.pipeline.cases")?.split(',')?.toSet()
        require(selected==null || selected.all { name->cases.any { it.asJsonObject["name"].asString==name } })
        File(out,"jobs.jsonl").bufferedWriter().use { output ->
            for(entry in cases.filter { selected==null || it.asJsonObject["name"].asString in selected }) {
                val row=entry.asJsonObject;val name=row["name"].asString
                fun documents(field:String)=row[field].asJsonArray.map { element ->
                    val d=element.asJsonObject
                    ContextDocument(d["id"].asString,d["revision"].asString,d["text"].asString,
                        author=d["author"].asString,kind=d["kind"].asString,parentText=d["parentText"].asString,
                        metadata=d["metadata"]?.asJsonObject?.entrySet()?.associate {it.key to it.value.asString}.orEmpty())
                }
                val initial=documents("initial");val noise=documents("noise");val update=documents("update")
                val duplicates=if(row.has("duplicates"))documents("duplicates") else emptyList()
                val all=initial+noise+update;val authors=(all+duplicates).associate { it.id to it.author }
                val sourceDocuments=(all+duplicates).associateBy {it.id}
                var findings=emptyList<ContextFinding>();var units=emptyList<ContextUnitOutcome>();var brief="";var compacted=emptyList<ContextSummaryReceipt>()
                val checkpoint=File(out,"$name-checkpoint.json")
                val jobStart=System.nanoTime();val callStart=completedGenerations;val attemptStart=generationAttempts
                val stages=mutableListOf<Map<String,Any>>()
                var failure:Exception?=null
                try {
                    val stagesToRun=listOf("initial" to initial,"resume" to initial,"noise" to (initial+noise),"update" to all,"updated_resume" to all)+
                        if(duplicates.isEmpty())emptyList() else listOf("duplicate" to (all+duplicates))
                    for((stage,documents) in stagesToRun) {
                        // A new engine reads the serialized stage receipt. The
                        // native worker stays warm: this is not a cold-process
                        // or Android database-lifecycle qualification.
                        if(checkpoint.exists()) {
                            val restored=gson.fromJson(checkpoint.readText(),PipelineCheckpoint::class.java)
                            findings=restored.findings;units=restored.units;brief=restored.brief;compacted=restored.compactions
                        }
                        val engine=ContextEngine(model,profile,sourceLabel={authors[it].orEmpty()},
                            sourcePublications={id->sourceDocuments[id]?.let(ContextSourcePublication::from).orEmpty()},
                            sourceEvidenceScope={id->if(observationScope!="disabled")sourceDocuments[id]?.let(ContextEvidenceScope::from)else null},scopeGuidance=observationScope=="enabled")
                        val priorFindings=findings
                        val priorDocuments=if(checkpoint.exists())gson.fromJson(checkpoint.readText(),PipelineCheckpoint::class.java).documents.orEmpty() else emptyList()
                        val from=completedGenerations;val attemptsFrom=generationAttempts;val countsFrom=countRequests;val stageStart=System.nanoTime()
                        println("$mode $name $stage starting");System.out.flush()
                        val currentSources=documents.associateBy {it.id}
                        val backgrounds=documents.mapNotNull {doc->
                            val key=doc.metadata["contextParent"]
                            if(key==null&&(postBackground!="typed"||doc.kind!="comment"))null
                            else {
                                val post=currentSources[initial.first().id]
                                val parent=key?.let(currentSources::get)
                                doc.id to ContextBackground(post=post?.let {ContextBackgroundSource(it.text,it.author,sourceId=it.id)},
                                    reply=if(key==null)null else if(parent==null)ContextBackgroundSource("",unavailable=true)else ContextBackgroundSource(parent.text,parent.author,sourceId=parent.id))
                            }
                        }.toMap()
                        val batch=engine.process(documents,findings,units,backgrounds=backgrounds,onUnit={unit->
                            File(out,"$name-unit-journal.jsonl").appendText(gson.toJson(mapOf("stage" to stage,"unit" to unit))+"\n")
                        })
                        findings=batch.findings;units=batch.outcomes
                        // Preserve the evidence even if subsequent composition
                        // is rejected, just as the app commits sources first.
                        File(out,"$name-$stage-processed.json").writeText(gson.toJson(PipelineCheckpoint(findings,units,brief,compacted,priorDocuments)))
                        val reuse=composition=="publication"&&brief.isNotBlank()&&ContextBriefReuse.onlyExactCopiesAdded(priorFindings,findings,priorDocuments,documents)
                        val summary=if(reuse)ContextSummary(brief,findings.flatMap {it.sources}.distinct().sorted(),findings,0,false,compacted)
                        else if(composition=="source_atomic")ContextAtomicAuditComposer(model,profile,File(out,"$name-atomic"),sourceLabel={authors[it].orEmpty()}).summarize(findings,brief)
                        else if(composition=="source_selection")ContextSelectionAuditComposer(model,profile,File(out,"$name-selection"),sourceLabel={authors[it].orEmpty()},context=initial.first().text,primarySource=initial.first().id,readerCharacters=readerCharacters).summarize(findings,brief)
                        else engine.summarizeResult(findings,brief,compacted,onCompact={receipt->
                            File(out,"$name-compaction-journal.jsonl").appendText(gson.toJson(mapOf("stage" to stage,"receipt" to receipt))+"\n")
                        })
                        brief=summary.text;compacted=summary.receipts
                        checkpoint.writeText(gson.toJson(PipelineCheckpoint(findings,units,brief,compacted,documents)))
                        val count=generationAttempts-attemptsFrom
                        val evidence=mapOf("stage" to stage,"newModelCalls" to count,"completedModelCalls" to completedGenerations-from,"countRequests" to countRequests-countsFrom,"elapsedMs" to (System.nanoTime()-stageStart)/1_000_000,
                            "brief" to brief,"findings" to findings,"units" to units,"compactions" to compacted,"levels" to summary.levels,"publicationReused" to reuse)
                        stages+=evidence
                        File(out,"$name-$stage.json").writeText(gson.toJson(evidence))
                        if(stage in setOf("resume","noise","updated_resume","duplicate")) check(count==0) { "Unchanged/noise/duplicate-only stage woke the model" }
                        println("$mode $name $stage complete: $count model calls");System.out.flush()
                    }
                } catch(error:Exception) { failure=error }
                val evidence=mapOf("name" to name,"reviewCriteria" to row["review"],"stages" to stages,"plan" to model.processingPlan,
                    "elapsedMs" to (System.nanoTime()-jobStart)/1_000_000,"modelCalls" to generationAttempts-attemptStart,"completedModelCalls" to completedGenerations-callStart,
                    "composition" to composition,"completed" to (failure==null),"errorType" to failure?.javaClass?.simpleName.orEmpty(),"error" to failure?.message.orEmpty(),
                    "qualification" to "Unreviewed generated prose; completion is not semantic admission")
                output.write(gson.toJson(evidence));output.newLine();output.flush()
                println("$mode $name recorded; completed=${failure==null}")
                if(native!=null&&!nativeTransportUsable)break
            }
        }
        File(out,"completion.json").writeText(gson.toJson(mapOf("elapsedMsIncludingInitialization" to (System.nanoTime()-started)/1_000_000,"modelCalls" to generationAttempts,"completedModelCalls" to completedGenerations,"returnedGenerationRecords" to calls.size,
                    "countRequests" to countRequests,"scope" to "Real ContextEngine jobs with fresh engine and JSON receipt roundtrip between stages, warm native worker; no Android, cold-worker or general semantic admission")))
    } finally {
        native?.let { process ->
            runCatching { process.outputStream.close() }
            if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly() }
        }
        responses.shutdownNow()
        remoteClient?.let { client -> client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
    }
}
