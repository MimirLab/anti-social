package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextBackgroundTest {
    @Test fun hiddenInterpretationChangesInvalidateReceiptsWithoutPollutingVisibleExcerpts() {
        val before=ContextBackgroundSource("The entrance reopens tomorrow.","Two dated editions",interpretationIdentity="earlier-observed-times")
        val after=ContextBackgroundSource(before.text,before.label,interpretationIdentity="updated-observed-times")
        assertNotEquals(before.fingerprint,after.fingerprint)
        assertEquals(ContextBackground(before).excerpt(1000),ContextBackground(after).excerpt(1000))
        assertFalse(ContextBackground(after).excerpt(1000).text.contains("updated-observed-times"))
    }

    private class Model(override val contextTokens: Int=2048, private val noSignal: Boolean=false): ContextModel {
        override val maxOutputTokens=256
        val requests=mutableListOf<JsonObject>()
        var prose: (JsonObject)->String = {it["text"].asString}
        override suspend fun count(system: String,user: String)=(system.length+user.length+3)/4+16
        override suspend fun generate(system: String,user: String,maxTokens: Int): String {
            assertTrue(count(system,user)+maxTokens+32<=contextTokens)
            val input=JsonParser.parseString(user).asJsonObject;requests+=input
            if(noSignal)return """{"status":"no_signal","findings":[]}"""
            return JsonObject().apply {
                addProperty("status","signal")
                add("findings",JsonArray().apply {add(JsonObject().apply {
                    addProperty("text",prose(input));addProperty("kind","context");addProperty("quote",input["text"].asString)
                })})
            }.toString()
        }
    }
    private fun source(id: String="reply",text: String="The entrance is accessible from the rear courtyard.")=ContextDocument(id,"r1",text)

    @Test fun bothBackgroundSourcesKeepAShareAndUnusedSpaceFlowsToTheLongerOne() {
        val post=ContextBackgroundSource("P".repeat(5000),"Workshop")
        val parent=ContextBackgroundSource("R".repeat(5000),"Jamie")
        val short=ContextBackground(post,parent).excerpt(1000)
        assertTrue(short.truncated);assertFalse(short.unavailable)
        assertTrue(short.text.contains("P".repeat(500)));assertTrue(short.text.contains("R".repeat(500)))
        val room=ContextBackground(ContextBackgroundSource("short"),parent).excerpt(1000)
        assertTrue(room.text.contains("R".repeat(995)))
        assertFalse(ContextBackground(post,parent).excerpt(10000).truncated)
    }

    @Test fun unicodeExcerptsNeverLeaveAnUnpairedSurrogate() {
        val background=ContextBackground(ContextBackgroundSource("🌱".repeat(50)),ContextBackgroundSource("🙂".repeat(50)))
        for(limit in 0..203) {
            val text=background.excerpt(limit).text
            text.forEachIndexed {i,c->
                if(c.isHighSurrogate())assertTrue(i+1<text.length&&text[i+1].isLowSurrogate())
                if(c.isLowSurrogate())assertTrue(i>0&&text[i-1].isHighSurrogate())
            }
        }
    }

    @Test fun activeCapacityKeepsCompleteLongBackgroundOrExplicitBalancedExcerpts(): Unit=runBlocking {
        val post="Post introduction. "+"Background detail. ".repeat(240)+"The bridge reopens to buses only."
        val parent="Parent introduction. "+"Earlier observation. ".repeat(100)+"The school bus is affected."
        val background=ContextBackground(ContextBackgroundSource(post),ContextBackgroundSource(parent))
        val doc=source();val small=Model();val large=Model(262144)
        ContextEngine(small).process(listOf(doc),backgrounds=mapOf(doc.id to background))
        ContextEngine(large).process(listOf(doc),backgrounds=mapOf(doc.id to background))
        val clipped=small.requests.single();val complete=large.requests.single()
        assertTrue(clipped["parent_truncated"].asBoolean)
        assertTrue(clipped["parent_context"].asString.contains("Post introduction"))
        assertTrue(clipped["parent_context"].asString.contains("Parent introduction"))
        assertFalse(complete.has("parent_truncated"))
        assertTrue(complete["parent_context"].asString.contains(post));assertTrue(complete["parent_context"].asString.contains(parent))
        assertEquals(doc.text,complete["text"].asString)
    }

    @Test fun aSmallWindowKeepsTheWholeBackgroundWhenTheCountedRequestAlreadyFits(): Unit=runBlocking {
        val post="Workshop announcement. "+"Routine checklist detail. ".repeat(60)+"Only the revised connector is compatible."
        val parent="Does this fit the older laptop?"
        val model=Model();val doc=source(text="The service manual lists the revised connector.")
        ContextEngine(model).process(listOf(doc),backgrounds=mapOf(doc.id to ContextBackground(ContextBackgroundSource(post),ContextBackgroundSource(parent))))
        val actual=model.requests.single()
        assertFalse(actual.has("parent_truncated"))
        assertTrue(actual["parent_context"].asString.contains(post));assertTrue(actual["parent_context"].asString.contains(parent))
        assertTrue(actual["parent_context"].asString.contains("Only the revised connector is compatible."))
        assertEquals(doc.text,actual["text"].asString)
    }

    @Test fun anEditBeyondTheVisibleExcerptInvalidatesItsCompletedReview(): Unit=runBlocking {
        val doc=source();val post="Background. ".repeat(600)
        val first=ContextBackground(ContextBackgroundSource(post+"Old qualification"))
        val second=ContextBackground(ContextBackgroundSource(post+"Changed qualification"))
        assertEquals(first.excerpt(1000).text,second.excerpt(1000).text)
        val model=Model();val engine=ContextEngine(model)
        val result=engine.process(listOf(doc),backgrounds=mapOf(doc.id to first))
        engine.process(listOf(doc),completed=result.outcomes,backgrounds=mapOf(doc.id to first))
        assertEquals(1,model.requests.size)
        val changed=engine.process(listOf(doc),completed=result.outcomes,backgrounds=mapOf(doc.id to second))
        assertEquals(2,model.requests.size);assertNotEquals(result.outcomes.single().unitId,changed.outcomes.single().unitId)
    }

    @Test fun identicalRepliesToDifferentQuestionsMustBeComparedInTheirOwnContext(): Unit=runBlocking {
        val model=Model().apply {prose={if(it["parent_context"].asString.contains("closed"))"The reader says the entrance is closed."else "The reader says the entrance is accessible."}}
        val engine=ContextEngine(model);val one=source("one","Yes, it is.");val two=source("two",one.text)
        val first=engine.process(listOf(one),backgrounds=mapOf(one.id to ContextBackground(reply=ContextBackgroundSource("Is the entrance accessible?"))))
        val second=engine.process(listOf(two),first.findings,backgrounds=mapOf(two.id to ContextBackground(reply=ContextBackgroundSource("Is the entrance closed?"))))
        assertEquals(2,model.requests.size)
        assertTrue(model.requests.last()["parent_context"].asString.contains("closed"))
        assertEquals(ContextUnitStatus.SIGNAL,second.outcomes.single().status)
    }

    @Test fun missingBackgroundCannotBecomeASuccessfulNoSignalReview(): Unit=runBlocking {
        val model=Model(noSignal=true);val doc=source();var commits=0
        assertThrows(ContextProcessingException::class.java) {runBlocking {
            ContextEngine(model).process(listOf(doc),backgrounds=mapOf(doc.id to ContextBackground(reply=ContextBackgroundSource("",unavailable=true))),onUnit={commits++})
        }}
        assertEquals(0,commits);assertTrue(model.requests.single()["parent_unavailable"].asBoolean)
        assertTrue(model.requests.single()["parent_truncated"].asBoolean)
    }

    @Test fun knownFindingsKeepTheirLateQualificationsInTheActualComparison(): Unit=runBlocking {
        val text="The workshop is open. "+"A scheduling detail. ".repeat(24)+"It is not open to the public."
        val known=ContextFinding("earlier",text,"context",listOf("old"),mapOf("old" to text))
        val model=Model(noSignal=true)
        ContextEngine(model).process(listOf(source(text="The workshop is open to the public today.")),existing=listOf(known))
        val supplied=model.requests.single().getAsJsonArray("known").single().asJsonObject["text"].asString
        assertEquals(text,supplied);assertTrue(supplied.endsWith("not open to the public."))
    }

    @Test fun evidenceQualificationsSurviveEvenWhenBackgroundTextFitsCompletely(): Unit=runBlocking {
        val model=Model();val doc=source()
        val background=ContextBackground(ContextBackgroundSource("The published scan may be incomplete.","Archive",incomplete=true,uncertain=true))
        ContextEngine(model).process(listOf(doc),backgrounds=mapOf(doc.id to background))
        val request=model.requests.single()
        assertTrue(request["parent_truncated"].asBoolean);assertTrue(request["parent_uncertain"].asBoolean)
        assertFalse(request.has("parent_unavailable"))
        assertNotEquals(background.fingerprint,ContextBackground(ContextBackgroundSource(background.post!!.text,"Archive")).fingerprint)
    }

    @Test fun anExplicitCommentTombstoneNeedsNoModelAndKeepsItsCompletedReceipt(): Unit=runBlocking {
        val model=Model();val doc=source(text="[Comment deleted]").copy(metadata=mapOf("deleted" to "true"))
        val engine=ContextEngine(model);val first=engine.process(listOf(doc))
        assertEquals(ContextUnitStatus.NO_SIGNAL,first.outcomes.single().status)
        assertTrue(model.requests.isEmpty())
        assertEquals(first.outcomes,engine.process(listOf(doc),completed=first.outcomes).outcomes)
    }
}
