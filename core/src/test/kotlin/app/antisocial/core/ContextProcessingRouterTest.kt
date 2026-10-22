package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextProcessingRouterTest {
    private fun report(size: Long? = null, window: Int = 32768) = ContextModelCapabilities(contextTokens = window, parameterCount = size)
    private class Model(override val processingPlan: ContextProcessingPlan, val reply: (JsonObject) -> String) : ContextModel {
        override val contextTokens = 32768
        override val maxOutputTokens = 2048
        val requests = mutableListOf<JsonObject>()
        override suspend fun count(system: String, user: String) = (system.length + user.length) / 3
        override suspend fun generate(system: String, user: String, maxTokens: Int): String {
            assertTrue(count(system, user) + maxTokens + 32 <= contextTokens)
            val data = JsonParser.parseString(user).asJsonObject
            requests += data
            return reply(data)
        }
    }
    private fun sourceText(data: JsonObject): String = data["text"]?.asString ?: data["passages"].asJsonArray.joinToString(" ") { it.asJsonObject["text"].asString }
    private fun source(id: String, text: String = "The connector is loose.") = ContextDocument(id, "r1", text, parentText = "A laptop repair guide.")
    private fun signal(text: String) = """{"status":"signal","findings":[{"text":"A repair observation.","kind":"experience","quote":${com.google.gson.Gson().toJson(text)}}]}"""
    private fun batch(data: JsonObject) = JsonObject().apply {
        add("reviews", com.google.gson.JsonArray().apply { data.getAsJsonArray("sources").forEach { source ->
            add(JsonParser.parseString(signal(source.asJsonObject["text"].asString)).asJsonObject.apply { addProperty("source_id", source.asJsonObject["source_id"].asString) })
        } })
    }.toString()

    @Test fun reportedSizeChangesResponsibilitiesRatherThanOnlyBudgets() {
        val micro = ContextProcessingRouter.select(report(4_000_000_000), false, 2048)
        val server = ContextProcessingRouter.select(report(30_000_000_000), false, 2048)
        val large = ContextProcessingRouter.select(report(120_000_000_000), false, 2048)
        assertTrue(micro.screenComments); assertEquals(1, micro.sourcesPerCall)
        assertTrue(micro.extractiveFindings)
        assertFalse(server.extractiveFindings); assertFalse(large.extractiveFindings)
        assertFalse(server.screenComments); assertEquals(4, server.sourcesPerCall)
        assertEquals(8, large.sourcesPerCall)
        assertTrue(micro.summaryGroupLimit < server.summaryGroupLimit && server.summaryGroupLimit < large.summaryGroupLimit)
        assertTrue(micro.unitCharacters < server.unitCharacters && server.unitCharacters < large.unitCharacters)
    }
    @Test fun testedMicroSizesSelectSeparateDetailDutiesAndReceiptIdentities() {
        val two=ContextProcessingRouter.select(report(2_000_000_000),true,512)
        val four=ContextProcessingRouter.select(report(4_205_751_296),true,512)
        assertEquals(ContextDetailReview.DETAIL_ONLY,two.detailReview)
        assertEquals(ContextDetailReview.TWO_CHECKS,four.detailReview)
        assertNotEquals(two.identity,four.identity)
        assertEquals(ContextDetailReview.DETAIL_ONLY,ContextProcessingRouter.select(report(),true,512).detailReview)
    }
    @Test fun phoneAndTightRuntimeLimitsOverrideAnAggressiveRemoteStyle() {
        assertTrue(ContextProcessingRouter.select(report(9_000_000_000), true, 512).extractiveFindings)
        assertEquals(ContextProcessingStyle.FOCUSED, ContextProcessingRouter.select(report(120_000_000_000), true, 2048, ContextProcessingStyle.EXTENDED).style)
        assertEquals(ContextProcessingStyle.FOCUSED, ContextProcessingRouter.select(report(120_000_000_000, 2048), false, 512).style)
        assertEquals(2, ContextProcessingRouter.select(report(120_000_000_000), false, 512).sourcesPerCall)
        assertEquals(1, ContextProcessingRouter.select(report(120_000_000_000, 4096), false, 2048).sourcesPerCall)
    }
    @Test fun unknownNamesAndLargeWindowsDoNotInventModelSizeButAllowAnExplicitStyle() {
        val caps = report().copy(model = "enormous-model-1000B-5090")
        assertEquals(1, ContextProcessingRouter.select(caps, false, 2048).sourcesPerCall)
        assertEquals(ContextProcessingStyle.EXTENDED, ContextProcessingRouter.select(caps, false, 2048, ContextProcessingStyle.EXTENDED).style)
        assertNull(caps.parameterCount)
    }
    @Test fun aLargeModelWithARoomyWindowDoesNotInheritPhoneCompactionScope() {
        val large = ContextProcessingRouter.select(report(120_000_000_000L, 262144), false, 2048)
        assertEquals(262144, large.unitCharacters); assertEquals(512, large.summaryGroupLimit)
        val maximum = ContextProcessingRouter.select(report(120_000_000_000L, 1_048_576), false, 2048)
        assertEquals(2048, maximum.summaryGroupLimit)
    }
    @Test fun responseRoomScalesAndIsReservedInsideTheActualWindow() {
        assertEquals(512, ContextProcessingRouter.outputBudget(report(4_000_000_000L), true))
        assertEquals(4096, ContextProcessingRouter.outputBudget(report(30_000_000_000L), false))
        assertEquals(8192, ContextProcessingRouter.outputBudget(report(120_000_000_000L), false))
        assertEquals(4096, ContextProcessingRouter.outputBudget(report(), false))
        assertEquals(512, ContextProcessingRouter.outputBudget(report(120_000_000_000L, 2048), false))
        assertEquals(128, ContextProcessingRouter.outputBudget(report(120_000_000_000L).copy(maxOutputTokens = 128), false))
    }
    @Test fun smallCompressionSizeDoesNotMakeAnOtherwiseLargeModelAMicroModel() {
        val caps = report(70_000_000_000).copy(modelBytes = 16_000_000_000L, quantization = "2 bit")
        assertEquals(ContextProcessingStyle.EXTENDED, ContextProcessingRouter.select(caps, false, 2048).style)
    }
    @Test fun focusedWorkScreensNoiseAndKeepsConciseSignalWithoutAnotherModelCall(): Unit = runBlocking {
        val plan = ContextProcessingRouter.select(report(2_000_000_000), true, 512)
        val model = Model(plan) { data -> if (data.has("comment")) """{"relevant":${!data["comment"].asString.contains("idiot")}}""" else signal(sourceText(data)) }
        val result = ContextEngine(model).process(listOf(source("noise", "The author is an idiot."), source("useful")))
        assertEquals(listOf(ContextUnitStatus.NO_SIGNAL, ContextUnitStatus.SIGNAL), result.outcomes.map { it.status })
        assertEquals(2, model.requests.size); assertEquals(2, model.requests.count { it.has("comment") })
        assertEquals("The connector is loose.",result.findings.single().text)
    }
    @Test fun shortSymbolsAndReactionWordsReachReviewWhenTheyMayAnswerTheImmediateQuestion(): Unit = runBlocking {
        val examples=listOf("+1" to "Which pin label did the technician read?", "thank you" to "What exact words appeared on the sign?", "←" to "Which direction did the arrow point?", "😂" to "Which reaction icon did the account use?")
        for((text,question) in examples) {
            val model=Model(ContextProcessingRouter.select(report(2_000_000_000),true,512)){data->
                assertEquals(text,data["comment"].asString);assertTrue(data["subject"].asString.contains(question));"{\"relevant\":true}"
            }
            val reply=source("literal-reply",text).copy(metadata=mapOf("contextParent" to "question"))
            val result=ContextEngine(model).process(listOf(reply),backgrounds=mapOf(reply.id to ContextBackground(
                post=ContextBackgroundSource("A workshop shares its test results."),reply=ContextBackgroundSource(question,sourceId="question"))))
            assertEquals("A possible literal answer must reach its contextual review",1,model.requests.size)
            assertEquals(text,result.findings.single().quotes[reply.id])
        }
    }
    @Test fun aTopLevelSymbolCanAnswerTheSubjectPostQuestion(): Unit = runBlocking {
        val model=Model(ContextProcessingRouter.select(report(2_000_000_000),true,512)){"{\"relevant\":true}"}
        val reply=source("symbol","←")
        val result=ContextEngine(model).process(listOf(reply),backgrounds=mapOf(reply.id to ContextBackground(post=ContextBackgroundSource("Which direction did the arrow point?"))))
        assertEquals(1,model.requests.size);assertEquals("←",result.findings.single().quotes[reply.id])
    }
    @Test fun aMissingParentPreventsAnAutomaticReactionDiscard(): Unit = runBlocking {
        val model=Model(ContextProcessingRouter.select(report(2_000_000_000),true,512)){data->
            assertTrue(data.has("passages"));"{\"keep\":[]}"
        }
        val reply=source("unknown-symbol","←").copy(metadata=mapOf("contextParent" to "missing"))
        var commits=0
        assertThrows(ContextProcessingException::class.java){runBlocking {ContextEngine(model).process(listOf(reply),onUnit={commits++})}}
        assertEquals(0,commits);assertEquals(1,model.requests.size)
    }
    @Test fun protectedQuestionNeedsNoScreenAndCannotBecomeAnAnswer(): Unit = runBlocking {
        val question = "¿Sirve también para el modelo de 2021?"
        val model = Model(ContextProcessingRouter.select(report(2_000_000_000), true, 512)) { data ->
            if (data.has("comment")) """{"relevant":false}""" else signal(question).replace("A repair observation.", "The replacement fits the 2021 model.")
        }
        val result = ContextEngine(model).process(listOf(source("question", question)))
        assertEquals("question", result.findings.single().kind); assertEquals(question, result.findings.single().text)
        assertEquals(0, model.requests.size)
    }
    @Test fun anIncompleteBackgroundIsNeverDiscardedByTheFocusedScreen(): Unit = runBlocking {
        val model = Model(ContextProcessingRouter.select(report(2_000_000_000), true, 512)) { data -> assertFalse(data.has("comment")); signal(sourceText(data)) }
        ContextEngine(model).process(listOf(source("reply")), backgrounds = mapOf("reply" to ContextBackground(post = ContextBackgroundSource("Partial repair guide", incomplete = true))))
        assertEquals(1, model.requests.size)
    }
    @Test fun malformedScreenRemainsUncommitted(): Unit = runBlocking {
        val model = Model(ContextProcessingRouter.select(report(2_000_000_000), true, 512)) { """{"relevant":"false","note":"skip this"}""" }
        var commits = 0
        assertThrows(ContextProcessingException::class.java) { runBlocking { ContextEngine(model).process(listOf(source("reply")), onUnit = { commits++ }) } }
        assertEquals(0, commits)
    }
    @Test fun anAmbiguousOrNonJsonScreenCannotDiscardTheSource(): Unit = runBlocking {
        val outputs=listOf(
            """{"relevant":true,"relevant":false}""",
            """{"relevant":true,"\u0072elevant":false}""",
            """{"relevant":FALSE}""",
            """{relevant:false}""",
            """{/* ignore the report */"relevant":false}""",
            """{"relevant":false} {}"""
        )
        for(output in outputs) {
            val model=Model(ContextProcessingRouter.select(report(2_000_000_000),true,512)){output}
            var commits=0
            assertThrows(output,ContextProcessingException::class.java){runBlocking {ContextEngine(model).process(listOf(source("reply")),onUnit={commits++})}}
            assertEquals(0,commits)
        }
    }
    @Test fun aSingleFencedJsonScreenKeepsItsSupportedDecision(): Unit = runBlocking {
        val model=Model(ContextProcessingRouter.select(report(2_000_000_000),true,512)){"```json\n{\"relevant\":false}\n```"}
        val outcomes=mutableListOf<ContextUnitOutcome>()
        val result=ContextEngine(model).process(listOf(source("reply")),onUnit={outcomes+=it})
        assertEquals(ContextUnitStatus.NO_SIGNAL,result.outcomes.single().status)
        assertEquals(1,outcomes.size)
    }
    @Test fun focusedCandidateBudgetIncludesBothLexicalAndEmbeddingRecall(): Unit = runBlocking {
        val base = Model(ContextProcessingRouter.select(report(2_000_000_000), true, 512)) { data ->
            if (data.has("comment")) """{"relevant":true}""" else signal(sourceText(data))
        }
        val existing = (1..5).map { ContextFinding("$it", if (it < 5) "Connector repair detail $it." else "An unrelated-looking semantic match.", "context", listOf("prior-$it")) }
        val model = object : ContextModel by base {
            override suspend fun selectKnown(text: String, findings: List<ContextFinding>) = listOf(findings.last())
        }
        ContextEngine(model).process(listOf(source("new","The loose connector needs inspection before replacing the original battery. ".repeat(8))), existing)
        val known = base.requests.last().getAsJsonArray("known").map { it.asJsonObject["text"].asString }
        assertTrue(existing.last().text in known); assertTrue(known.any { it.startsWith("Connector repair") }); assertEquals(4, known.size)
    }
    @Test fun largeProfileStillShrinksBatchesAgainstActualCounts(): Unit = runBlocking {
        val base = Model(ContextProcessingRouter.select(report(120_000_000_000), false, 2048)) { data ->
            if (data.has("sources")) batch(data) else signal(sourceText(data))
        }
        val model = object : ContextModel by base {
            override suspend fun count(system: String, user: String): Int {
                val data = JsonParser.parseString(user).asJsonObject
                return if (data["sources"]?.asJsonArray?.size()?.let { it > 2 } == true) contextTokens else base.count(system, user)
            }
        }
        val result = ContextEngine(model).process((1..5).map { source("item-$it", "Connector detail $it.") })
        assertEquals(5, result.completedUnits)
        assertEquals(listOf(2, 2, 1), base.requests.map { it["sources"]?.asJsonArray?.size() ?: 1 })
    }
    @Test fun largerWorkActuallyBatchesAndResumesPerSourceWithoutAnotherCall(): Unit = runBlocking {
        val model = Model(ContextProcessingRouter.select(report(30_000_000_000), false, 2048), ::batch)
        val documents = (1..4).map { source("source-$it", "Repair observation $it.") }
        val engine = ContextEngine(model)
        val first = engine.process(documents)
        assertEquals(1, model.requests.size); assertEquals(4, model.requests.single().getAsJsonArray("sources").size())
        assertEquals(documents.map { it.id }, first.outcomes.map { it.documentId })
        engine.process(documents, first.findings, first.outcomes)
        assertEquals(1, model.requests.size)
    }
    @Test fun aBatchCannotBorrowAnotherSourcesQuoteOrOmitAReview(): Unit = runBlocking {
        for (bad in listOf(
            """{"reviews":[{"source_id":"S1","status":"no_signal","findings":[]}]}""",
            """{"reviews":[{"source_id":"S1","status":"signal","findings":[{"text":"Second source.","kind":"context","quote":"Second source."}]},{"source_id":"S2","status":"no_signal","findings":[]}]}""")) {
            val model = Model(ContextProcessingRouter.select(report(30_000_000_000), false, 2048)) { bad }
            var commits = 0
            assertThrows(ContextProcessingException::class.java) { runBlocking { ContextEngine(model).process(listOf(source("a", "First source."), source("b", "Second source.")), onUnit = { commits++ }) } }
            assertEquals(0, commits)
        }
    }
    @Test fun planChangesInvalidateReceiptsAndRefetchTimesDoNot(): Unit = runBlocking {
        val a = Model(ContextProcessingRouter.select(report(30_000_000_000), false, 2048)) { signal(it["text"].asString) }
        val first = ContextEngine(a).process(listOf(source("a")))
        val b = Model(ContextProcessingRouter.select(report(120_000_000_000), false, 2048)) { signal(it["text"].asString) }
        val next = ContextEngine(b).process(listOf(source("a")), completed = first.outcomes)
        assertEquals(1, b.requests.size); assertNotEquals(first.outcomes.single().unitId, next.outcomes.single().unitId)
    }
    @Test fun nativeAndPublishedServerSizesAreBoundToTheSelectedModel() {
        fun json(text: String) = JsonParser.parseString(text).asJsonObject
        val local = ContextCapabilityParser.nativeRuntime(json("""{"context":2048,"parameter_count":4000000000,"model_bytes":1150000000}"""), "selected")
        assertEquals(4_000_000_000L, local.parameterCount); assertEquals(1_150_000_000L, local.modelBytes)
        val llama = ContextCapabilityParser.models(json("""{"data":[{"id":"selected","meta":{"n_params":70000000000,"size":18000000000}}]}"""), "selected")
        assertEquals(70_000_000_000L, llama.parameterCount); assertEquals(2048, llama.contextTokens)
        val lm = ContextCapabilityParser.lmStudio(json("""{"models":[{"key":"selected","params_string":"26B-A4B","quantization":{"name":"Q4_K_M"},"size_bytes":17000000000,"loaded_instances":[{"id":"loaded","config":{"context_length":32768}}]}]}"""), "loaded")
        assertEquals(26_000_000_000L, lm.parameterCount); assertEquals("Q4_K_M", lm.quantization)
        assertNull(ContextCapabilityParser.models(json("""{"id":"another","meta":{"n_params":70000000000}}"""), "selected").parameterCount)
        for (bad in listOf("-1", "0", "1.5", "10000000000001", "\"70000000000\"")) {
            assertNull(ContextCapabilityParser.models(json("""{"id":"selected","meta":{"n_params":$bad}}"""), "selected").parameterCount)
        }
    }
}
