package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContextAtomicAuditComposerTest {
    private class Model(private val replies:Map<String,String>,override val maxOutputTokens:Int=512):ContextModel {
        val inputs=mutableListOf<String>()
        override suspend fun count(system:String,user:String)=(system.length+user.length+3)/4+12
        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
            assertTrue(count(system,user)+maxTokens+32<=contextTokens)
            val parsed=JsonParser.parseString(user).asJsonObject;assertEquals(setOf("source"),parsed.keySet())
            val source=parsed["source"].asString;inputs+=source
            return JsonObject().apply {addProperty("text",replies.getValue(source))}.toString()
        }
    }
    private val source="The kit costs 65 dollars. If QA passes Tuesday it ships Friday. Nothing has shipped yet. The price pays for a kit, not admission to a separate repair event."
    private val shortened="The 65-dollar kit ships Friday if QA passes Tuesday; nothing has shipped yet."
    private fun finding(id:String,text:String,kind:String="context")=ContextFinding(id,text,kind,listOf(id),mapOf(id to text))
    private suspend fun temporary(run:suspend (File)->Unit) {
        val root=File("/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/java-tmp")
        val dir=Files.createTempDirectory(root.toPath(),"atomic-audit-").toFile()
        try {run(dir)}finally {dir.deleteRecursively()}
    }
    private fun composer(model:ContextModel,dir:File,profile:String="model-v1")=ContextAtomicAuditComposer(model,profile,dir,{"Author $it"})

    @Test fun eachCallSeesOneSourceAndCodeReattachesSpeakersAndDuplicateProvenance():Unit=runBlocking {temporary {dir->
        val model=Model(mapOf(source to shortened));val data=listOf(finding("root",source))
        val first=composer(model,dir).summarize(data,"")
        assertEquals("Author root: $shortened",first.text);assertEquals(listOf(source),model.inputs)
        assertFalse(composer(model,dir).summarize(data,first.text).changed)
        val copy=data.single().copy(sources=listOf("root","copy"),quotes=mapOf("root" to source,"copy" to source))
        val next=composer(model,dir).summarize(listOf(copy),first.text)
        assertEquals(1,model.inputs.size);assertTrue(next.text.startsWith("Author copy · Author root: "))
        assertEquals(2,next.findings.single().sources.size)
    }}

    @Test fun updateCompressesOnlyItsOwnContributionAndSurvivesRestoration():Unit=runBlocking {temporary {dir->
        val update="QA found a label mismatch. Shipping is delayed and a replacement date has not been announced. The separate repair event is still on Saturday and is unaffected by the delay."
        val note="QA found a mismatch; shipping is delayed with no date announced. The Saturday event is unaffected."
        val model=Model(mapOf(source to shortened,update to note));val data=listOf(finding("root",source))
        val first=composer(model,dir).summarize(data,"")
        val next=composer(model,dir).summarize(data+finding("update",update),first.text)
        assertEquals(listOf(source,update),model.inputs)
        assertTrue(next.text.startsWith("New to this Context\n\nAuthor update: $note"))
        assertTrue(next.text.contains("From earlier reading\n\nAuthor root: $shortened"))
        assertFalse(composer(model,dir).summarize(data+finding("update",update),next.text).changed)
        assertEquals(2,model.inputs.size)
    }}

    @Test fun droppedValuesDatesConditionsAndExplicitUncertaintyKeepExactSourceInstead():Unit=runBlocking {
        val uncertain="I arrived this morning and the entrance was still taped off. I do not know whether it reopened later. My visit does not establish that the whole library remained inaccessible."
        for((input,bad) in listOf(source to "The 56-dollar kit ships Friday if QA passes Tuesday.",
            source to "The 65-dollar kit ships Friday if QA passes.",source to "The 65-dollar kit ships Friday after QA Tuesday.",
            uncertain to "The library remained closed all day."))temporary {dir->
            val model=Model(mapOf(input to bad));val result=composer(model,dir).summarize(listOf(finding("source",input)),"")
            assertEquals("Author source: $input",result.text)
            assertEquals(1,model.inputs.size)
            composer(model,dir).summarize(result.findings,result.text);assertEquals(1,model.inputs.size)
        }
    }

    @Test fun questionsStayUnansweredWithoutAWastedGeneration():Unit=runBlocking {temporary {dir->
        val question="Does the announced connector fit the older laptop when the new drawing shows another pin layout, and has anyone tested this particular combination?"
        val model=Model(emptyMap());val result=composer(model,dir).summarize(listOf(finding("reader",question,"question")),"")
        assertEquals("Author reader: $question",result.text);assertTrue(model.inputs.isEmpty())
    }}

    @Test fun explicitWithoutQualificationAndFullRemoteResponseAllowanceAreSupported():Unit=runBlocking {temporary {dir->
        val body="The library announced that its entrance reopened this morning. This does not establish the time of the separate book fair, and the announcement has no exact opening time."
        val note="The entrance reopened this morning without an exact opening time; the book fair is separate."
        val base=Model(mapOf(body to note),maxOutputTokens=8192)
        val model=object:ContextModel by base {
            override val contextTokens=262144
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                assertEquals(8192,maxTokens)
                return JsonObject().apply {addProperty("text",note)}.toString()
            }
        }
        assertEquals("Author library: $note",composer(model,dir).summarize(listOf(finding("library",body)),"").text)
    }}

    @Test fun brokenNoteCacheAndChangedModelCannotPublishUnvalidatedProse():Unit=runBlocking {temporary {dir->
        val model=Model(mapOf(source to shortened));val data=listOf(finding("root",source))
        composer(model,dir).summarize(data,"")
        val file=dir.listFiles()!!.single {it.name.startsWith("note-")}
        val corrupt=JsonParser.parseString(file.readText()).asJsonObject.apply {remove("text")};file.writeText(corrupt.toString())
        assertEquals("Author root: $shortened",composer(model,dir).summarize(data,"").text);assertEquals(2,model.inputs.size)
        composer(model,dir,profile="model-v2").summarize(data,"");assertEquals(3,model.inputs.size)
    }}

    @Test fun unfinishedModelWorkKeepsItsFailureTypeAndEarlierNoteReceipts():Unit=runBlocking {
        for(failure in listOf(java.io.IOException("Transport unavailable"),CancellationException("Stopped")))temporary {dir->
            val base=Model(emptyMap());val model=object:ContextModel by base {
                override suspend fun generate(system:String,user:String,maxTokens:Int):String=throw failure
            }
            val actual=runCatching {composer(model,dir).summarize(listOf(finding("short","The event is Saturday."),finding("long",source)),"")}.exceptionOrNull()
            assertSame(failure,actual);assertFalse(File(dir,"overview.json").exists())
            assertEquals(1,dir.listFiles()!!.count {it.name.startsWith("note-")})
        }
    }

    @Test fun allowanceOverflowPreservesEveryCompletedNoteAndPreviousPublicationReceipt():Unit=runBlocking {temporary {dir->
        val second="A volunteer reseated one connector successfully. The result only concerns that particular laptop. No other device or replacement battery was tested and broader safety is unknown."
        val note="One connector was reseated successfully in a particular laptop; other devices and replacement batteries remain untested and their safety unknown."
        val model=Model(mapOf(source to shortened,second to note),maxOutputTokens=96)
        val first=composer(model,dir).summarize(listOf(finding("root",source)),"");val prior=File(dir,"overview.json").readText()
        assertThrows(ContextProcessingException::class.java) {runBlocking {composer(model,dir).summarize(listOf(finding("root",source),finding("witness",second)),first.text)}}
        assertEquals(prior,File(dir,"overview.json").readText());assertEquals(2,dir.listFiles()!!.count {it.name.startsWith("note-")})
    }}

    @Test fun generatedFindingsOrDifferentSupportCannotMasqueradeAsExactAtomicInput():Unit=runBlocking {temporary {dir->
        val model=Model(emptyMap());val exact=finding("root",source)
        for(bad in listOf(exact.copy(text="The kit already shipped."),exact.copy(sources=listOf("root","other"),quotes=exact.quotes+mapOf("other" to "A materially different source."))))
            assertThrows(ContextProcessingException::class.java) {runBlocking {composer(model,dir).summarize(listOf(bad),"")}}
        assertTrue(model.inputs.isEmpty());assertFalse(File(dir,"overview.json").exists())
    }}
}
