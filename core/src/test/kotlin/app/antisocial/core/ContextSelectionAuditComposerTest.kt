package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContextSelectionAuditComposerTest {
    private class Model(val reply:String="""{"priority":[2,1]}""") :ContextModel {
        var calls=0
        override val maxOutputTokens=192
        override suspend fun count(system:String,user:String)=(system.length+user.length+3)/4+12
        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
            assertTrue(count(system,user)+maxTokens+32<=contextTokens);calls++;return reply
        }
    }
    private fun finding(id:String,text:String)=ContextFinding(id,text,"context",listOf(id),mapOf(id to text))
    private suspend fun temporary(run:suspend (File)->Unit) {
        val root=File("/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/java-tmp")
        val path=Files.createTempDirectory(root.toPath(),"selection-composer-").toFile()
        try {run(path)}finally {path.deleteRecursively()}
    }
    private fun composer(model:ContextModel,dir:File,profile:String="test",context:String="Battery repair")=
        ContextSelectionAuditComposer(model,profile,dir,{"Author $it"},context,"root")

    @Test fun completeConciseLedgerAndIdenticalRestoredReadNeedNoGeneration():Unit=runBlocking {temporary {dir->
        val model=Model();val data=listOf(finding("root","The capacity is 56 Wh, not 65 Wh."),finding("witness","I have not tested other laptops."))
        val first=composer(model,dir).summarize(data,"")
        assertTrue(first.text.contains("Author witness: I have not tested other laptops."));assertEquals(0,model.calls)
        val second=composer(model,dir).summarize(data,first.text)
        assertFalse(second.changed);assertEquals(first.text,second.text);assertEquals(0,model.calls)
    }}

    @Test fun suggestedIdsNeverWriteProseAndNewEvidenceCannotDisappearBehindAnOldOverview():Unit=runBlocking {temporary {dir->
        val model=Model("""{"priority":[2,3,1]}""")
        val root=finding("root","The kit may ship Friday if QA passes. "+"A public background observation. ".repeat(5))
        val correction=finding("correction","The manual specifies 56 Wh, not 65 Wh. "+"An explanation of the measured part. ".repeat(4))
        val other=finding("other","I have not tested other devices. "+"A further source observation. ".repeat(5))
        val data=listOf(root,correction,other)
        val first=composer(model,dir).summarize(data,"")
        assertTrue(model.calls>0);assertTrue(first.text.contains(correction.text));assertTrue(first.text.contains(root.text))
        val before=model.calls
        val update=finding("update","QA found a fault. Shipping is delayed; a new date is not confirmed.")
        val next=composer(model,dir).summarize(data+update,first.text)
        assertEquals(before,model.calls);assertTrue(next.text.startsWith("New to this Context\n\nAuthor update: ${update.text}"))
        assertEquals(4,next.findings.size);assertTrue(next.text.contains("From earlier reading"))
        assertTrue(model.count("",next.text)+32<=model.maxOutputTokens)
        assertFalse(composer(model,dir).summarize(data+update,next.text).changed)
        assertEquals(before,model.calls)
    }}

    @Test fun unknownDuplicateFractionalAndStringSuggestionsCannotCommitAnOverview():Unit=runBlocking {
        for(reply in listOf("""{"priority":[99]}""","""{"priority":[1,1]}""","""{"priority":[1.5]}""","""{"priority":["1"]}""","""{"priority":[true]}""","""{"text":"invented"}""")) temporary {dir->
            val model=Model(reply);val data=(1..3).map {finding(if(it==1)"root" else "$it","Source contribution $it. "+"Supporting observed detail. ".repeat(8))}
            assertThrows(ContextProcessingException::class.java) {runBlocking {composer(model,dir).summarize(data,"")}}
            assertFalse(File(dir,"overview.json").exists())
        }
    }

    @Test fun extraProseIsInertAndExplicitNonNumericCorrectionsSurviveAnUnhelpfulRanking():Unit=runBlocking {temporary {dir->
        val model=Model("""{"priority":[1,2],"text":"INVENTED OVERVIEW","candidates":[99]}""")
        val root=finding("root","An event has been announced. "+"Background to the original announcement. ".repeat(5))
        val other=finding("other","This is another observation. "+"Further background about the arrangements. ".repeat(5))
        val correction=finding("date","The meeting is on Wednesday, not Thursday. I am correcting the event date, not its venue. The meeting room and start time remain as originally announced. This changes the day only.")
        val result=composer(model,dir).summarize(listOf(root,other,correction),"")
        assertTrue(model.calls>0);assertFalse(result.text.contains("INVENTED OVERVIEW"))
        assertTrue(result.text.contains("Author date: ${correction.text}"));assertEquals(3,result.findings.size)
        assertTrue(model.count("",result.text)+32<=model.maxOutputTokens)
    }}

    @Test fun changedModelOrSubjectCannotReuseEarlierSelectionAndMalformedCachesAreNotTrusted():Unit=runBlocking {temporary {dir->
        val model=Model("""{"priority":[2,1,3]}""")
        val data=(1..3).map {finding(if(it==1)"root" else "$it","Source contribution $it. "+"Supporting observed detail. ".repeat(8))}
        composer(model,dir).summarize(data,"");val first=model.calls
        composer(model,dir,profile="different-model").summarize(data,"");assertTrue(model.calls>first)
        val second=model.calls
        composer(model,dir,profile="different-model",context="Different subject").summarize(data,"");assertTrue(model.calls>second)
        File(dir,"overview.json").writeText("{}")
        composer(model,dir).summarize(data,"")
        assertEquals(ContextSelectionAuditComposer.VERSION,JsonParser.parseString(File(dir,"overview.json").readText()).asJsonObject["version"].asString)
    }}

    @Test fun legacyParaphrasesCannotBePublishedAsExactSourceExcerpts():Unit=runBlocking {temporary {dir->
        val model=Model();val source=finding("root","I do not know whether staff reopened it later.")
        assertThrows(ContextProcessingException::class.java) {runBlocking {composer(model,dir).summarize(listOf(source.copy(text="Staff did not reopen it later.")),"")}}
        assertEquals(0,model.calls)
    }}

    @Test fun transportFailureAndCancellationKeepTheirTypesWithoutCommittingASelection():Unit=runBlocking {
        val data=(1..3).map {finding(if(it==1)"root" else "$it","Source contribution $it. "+"Supporting observed detail. ".repeat(8))}
        for(failure in listOf(java.io.IOException("Model unavailable"),CancellationException("Stopped")))temporary {dir->
            val base=Model()
            val model=object:ContextModel by base {
                override suspend fun generate(system:String,user:String,maxTokens:Int):String=throw failure
            }
            val actual=runCatching {composer(model,dir).summarize(data,"")}.exceptionOrNull()
            assertSame(failure,actual);assertFalse(File(dir,"overview.json").exists())
        }
    }

    @Test fun newMaterialThatCannotFitIsNotSilentlyHiddenBehindTheOldOverview():Unit=runBlocking {temporary {dir->
        val model=Model();val root=finding("root","The workshop has announced a battery kit.")
        val initial=composer(model,dir).summarize(listOf(root),"");val before=File(dir,"overview.json").readText()
        val update=finding("update","New consequential evidence and qualifications. ".repeat(20))
        assertThrows(ContextProcessingException::class.java) {runBlocking {composer(model,dir).summarize(listOf(root,update),initial.text)}}
        assertEquals(before,File(dir,"overview.json").readText())
    }}

    @Test fun aCodeRenderedReadingAllowanceIsIndependentOfTheModelsResponseLimit():Unit=runBlocking {temporary {dir->
        val base=Model()
        val model=object:ContextModel by base {
            // Unknown remote tokenizers can conservatively count bytes. Exact
            // local excerpts need no generation or token budget of their own.
            override suspend fun count(system:String,user:String)=system.toByteArray().size+user.toByteArray().size+12
        }
        val data=(1..4).map {finding(if(it==1)"root" else "$it","A qualified firsthand observation $it. "+"I have not tested other devices. ".repeat(4))}
        fun expanded()=ContextSelectionAuditComposer(model,"test",dir,{"Author $it"},"Battery repair","root",8192)
        val first=expanded().summarize(data,"")
        assertTrue(first.text.length>model.maxOutputTokens)
        data.forEach {assertTrue(first.text.contains("Author ${it.id}: ${it.text}"))}
        assertEquals(0,base.calls)
        val update=finding("update","QA found a fault. Shipping is delayed; a new date is not confirmed.")
        val next=expanded().summarize(data+update,first.text)
        assertTrue(next.text.startsWith("New to this Context\n\nAuthor update: ${update.text}"))
        data.forEach {assertTrue(next.text.contains(it.text))}
        assertFalse(expanded().summarize(data+update,next.text).changed)
        assertEquals(0,base.calls)
    }}

    @Test fun changingReadingDepthCannotReuseAnOldSubsetOrRerankTheSameContributions():Unit=runBlocking {temporary {dir->
        val model=Model("""{"priority":[2,3,1]}""")
        val data=(1..3).map {finding(if(it==1)"root" else "$it","Source contribution $it. "+"Supporting observed detail. ".repeat(7))}
        fun depth(characters:Int)=ContextSelectionAuditComposer(model,"test",dir,{"Author $it"},"Battery repair","root",characters)
        val compact=depth(512).summarize(data,"")
        assertTrue(model.calls>0);val calls=model.calls
        assertTrue(data.any {!compact.text.contains(it.text)})
        val expanded=depth(4096).summarize(data,compact.text)
        data.forEach {assertTrue(expanded.text.contains(it.text))}
        assertTrue(expanded.changed);assertEquals(calls,model.calls)
        val compactAgain=depth(512).summarize(data,expanded.text)
        assertTrue(compactAgain.text.length<=512);assertTrue(compactAgain.changed)
        assertTrue(data.any {!compactAgain.text.contains(it.text)})
        assertEquals(calls,model.calls)
    }}
}
