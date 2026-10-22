package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextScopeFrameTest {
    private class Model(override val processingPlan:ContextProcessingPlan=ContextProcessingPlan.SINGLE):ContextModel {
        override val contextTokens=8192
        override val maxOutputTokens=512
        val summaries=mutableListOf<JsonObject>()
        override suspend fun count(system:String,user:String)=128
        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
            val input=JsonParser.parseString(user).asJsonObject
            if(input.has("source_id")) {
                val text=input["text"].asString
                return JsonObject().apply {addProperty("status","signal");add("findings",JsonArray().apply {add(JsonObject().apply {addProperty("text",text);addProperty("quote",text);addProperty("kind","context")})})}.toString()
            }
            summaries+=input
            return """{"text":"Sources report distinct observations with their original limits."}"""
        }
    }
    private fun finding(id:String,text:String)=ContextFinding(id,text,"context",listOf(id),mapOf(id to text))

    @Test fun boundedPublicationFrameKeepsTheLatestTimeAndUndatedPresence():Unit=runBlocking {
        val model=Model()
        val editions=(1..63).map {day->ContextSourcePublication("Library",ContextPublicationTime(java.time.Instant.parse("2026-10-01T12:00:00Z").plusSeconds(day*86_400L)))}+
            ContextSourcePublication("Undated noticeboard",null)
        val latest=editions.mapNotNull {it.time?.instant}.maxOrNull()!!.toString()
        val engine=ContextEngine(model,"bounded",{"Library"},{id->if(id=="one")editions else emptyList()})
        engine.summarizeResult(listOf(finding("one","The entrance reopens tomorrow if inspection passes."),finding("two","A separate repair event remains planned.")))
        val wire=model.summaries.last().toString()
        assertTrue("Alphabetical truncation must not hide the newest known edition",wire.contains(latest))
        assertTrue("Undated attribution must remain explicit",wire.contains("publication_time_unavailable"))
        val frame=model.summaries.last()["findings"].asJsonArray.first().asJsonObject["publication_context"].asJsonObject
        assertEquals(63,frame["dated_contexts"].asInt);assertEquals(1,frame["undated_contexts"].asInt)
    }

    @Test fun captionAndRecognitionLimitsReachTheFinalCompactionLevel():Unit=runBlocking {
        val model=Model(ContextProcessingPlan.SINGLE.copy(summaryGroupLimit=2))
        val docs=listOf(
            ContextDocument("caption","1","The available captions describe disconnecting power but omit silent visual steps.",author="Video captions",kind="article",metadata=mapOf("medium" to "Published captions")),
            ContextDocument("ocr","1","The recognized label appears to name a revised connector.",author="Recognized label",kind="article",metadata=mapOf("medium" to "Local image text recognition","uncertain" to "true")),
            ContextDocument("article","1","A repair guide describes a particular connector inspection.",author="Repair guide",kind="article"),
            ContextDocument("comment","1","I tried this connector in my own laptop but not in other models.",author="Reader",kind="comment"))
        val engine=ContextEngine(model,"scope",{id->docs.single {it.id==id}.author})
        val processed=engine.process(docs)
        val result=engine.summarizeResult(processed.findings)
        assertEquals(2,result.levels)
        val wire=model.summaries.last().toString()
        assertTrue("Caption evidence is not a complete video inspection",wire.contains("caption_text"))
        assertTrue("Recognition uncertainty cannot vanish at higher levels",wire.contains("recognized_image_text")&&wire.contains("uncertain_text"))
    }

    @Test fun changedRoleAssociationsAndInstructionModesCannotReuseOldCompactions():Unit=runBlocking {
        val model=Model();val text="The source supplies a connector instruction."
        val data=listOf(ContextFinding("same",text,"context",listOf("a","b"),mapOf("a" to text,"b" to text)),finding("c","A separate event is described."))
        val caption=ContextEvidenceScope.from(ContextDocument("a","1",text,kind="article",metadata=mapOf("medium" to "Published captions")))
        val page=ContextEvidenceScope.from(ContextDocument("b","1",text,kind="article"))
        val scopes=mutableMapOf("a" to caption,"b" to page,"c" to page)
        fun engine(guidance:Boolean=false)=ContextEngine(model,"roles",{"Reader $it"},{emptyList()},{scopes[it]},guidance)
        val first=engine().summarizeResult(data);val count=model.summaries.size
        engine().summarizeResult(data,first.text,first.receipts);assertEquals(count,model.summaries.size)
        scopes["a"]=page;scopes["b"]=caption
        val changed=engine().summarizeResult(data,first.text,first.receipts)
        assertTrue(model.summaries.size>count);assertNotEquals(first.receipts.single().id,changed.receipts.single().id)
        assertNotEquals(engine().processingIdentity,engine(true).processingIdentity)
        val before=model.summaries.size
        engine(true).summarizeResult(data,changed.text,changed.receipts);assertTrue(model.summaries.size>before)
    }
}
