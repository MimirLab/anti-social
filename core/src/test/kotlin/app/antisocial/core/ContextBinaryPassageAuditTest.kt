/* AntiSocial, GPL-3.0-or-later. No model or networking required. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContextBinaryPassageAuditTest {
    private class Model(private val votes:List<Boolean>):ContextModel {
        val requests=mutableListOf<JsonObject>()
        var oversized=false
        var cancelled=false
        override suspend fun count(system:String,user:String)=if(oversized)contextTokens else 128
        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
            if(cancelled)throw CancellationException("fixture cancellation")
            assertEquals(shortDecisionTokens,maxTokens)
            requests+=JsonParser.parseString(user).asJsonObject
            return "{\"relevant\":"+votes[requests.lastIndex]+"}"
        }
    }
    private suspend fun temporary(run:suspend (File)->Unit) {
        val root=File("/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/java-tmp")
        val dir=Files.createTempDirectory(root.toPath(),"binary-passage-").toFile()
        try {run(File(dir,"decisions.jsonl"))}finally {dir.deleteRecursively()}
    }
    private fun input(vararg texts:String)=JsonObject().apply {
        addProperty("kind","comment");addProperty("parent_context","The ferry may resume service.")
        add("passages",JsonArray().apply {texts.forEachIndexed {index,text->add(JsonObject().apply {addProperty("id",index+1);addProperty("text",text)})}})
    }.toString()
    private suspend fun review(model:ContextModel,file:File,mode:String,closure:Boolean,vararg texts:String)=
        JsonParser.parseString(ContextBinaryPassageAudit(model,mode,closure,file).generate("Select useful source passages from S1.",input(*texts),64)).asJsonObject["keep"].asJsonArray.map {it.asInt}

    @Test fun strictVotesRejectAmbiguousMalformedAndExtraOutput() {
        assertTrue(ContextBinaryPassageAudit.parseVote(" {\"relevant\":true} "))
        assertFalse(ContextBinaryPassageAudit.parseVote("{\"relevant\":false}"))
        for(raw in listOf("{\"relevant\":TRUE}","{\"relevant\":\"true\"}","{\"relevant\":1}","{\"relevant\":true,\"relevant\":false}","{\"relevant\":false,\"reason\":\"noise\"}","{\"relevant\":false} {}","{relevant:false}","```json\n{\"relevant\":true}\n```")) {
            try {ContextBinaryPassageAudit.parseVote(raw);fail(raw)}catch(_:ContextProcessingException) { }
        }
    }
    @Test fun qualificationReviewOnlyFollowsANegativeVoteAndNeverOwnsPassageIds():Unit=runBlocking {temporary {file->
        val requests=mutableListOf<JsonObject>();val replies=ArrayDeque(listOf("{\"relevant\":false}","{\"kind\":\"report\"}","{\"relevant\":true}"))
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {requests+=JsonParser.parseString(user).asJsonObject;return replies.removeFirst()}
        }
        assertEquals(listOf(1,2),review(model,file,"qualified_linked",false,"I checked the deck before boarding.","The timetable remains unconfirmed."))
        assertEquals(3,requests.size);assertEquals(requests[0],requests[1]);assertTrue(replies.isEmpty())
        assertTrue(file.readText().contains("\"qualification_kind\":\"report\""))
        assertEquals(emptyList<Int>(),review(Model(listOf(false)),file,"qualified_linked",false,"The timetable was published."))
    }}
    @Test fun qualificationClassesRejectUnknownDuplicateAndTrailingPayloads() {
        assertEquals("condition",ContextBinaryPassageAudit.parseKind("{\"kind\":\"condition\"}"))
        for(raw in listOf("{\"kind\":\"observation\"}","{\"kind\":\"report\",\"kind\":\"reaction\"}","{\"kind\":\"report\",\"\\u006bind\":\"reaction\"}","{\"kind\":\"report\",\"keep\":[1]}","{\"kind\":\"report\"} {}")) {
            try {ContextBinaryPassageAudit.parseKind(raw);fail(raw)}catch(_:ContextProcessingException){}
        }
    }
    @Test fun anUnfitSecondReviewCannotFallBackToTheNegativePrimaryVote():Unit=runBlocking {temporary {file->
        var counts=0;var generations=0
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=if(++counts==1)128 else contextTokens
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {generations++;return "{\"relevant\":false}"}
        }
        try {review(model,file,"qualified_linked",false,"The trial remains unconfirmed.");fail()}catch(_:ContextProcessingException){}
        assertEquals(1,generations);assertFalse(file.exists())
    }}
    @Test fun numericTimeAndThousandsSeparatorsCannotHideAnInteriorReference() {
        assertEquals(listOf(listOf(0,1)),ContextBinaryPassageAudit.linkedGroups(listOf("The gauge passed the inspection.","At 10:45 that inspection was repeated.")))
        assertEquals(listOf(listOf(0,1)),ContextBinaryPassageAudit.linkedGroups(listOf("The gauge passed the inspection.","At 1,200 metres that inspection was repeated.")))
    }
    @Test fun appOwnsIdsAndOnlyImmediateNeighborsEnterTheNarrowRequest():Unit=runBlocking {temporary {file->
        val model=Model(listOf(false,true,false,true))
        assertEquals(listOf(2,4),review(model,file,"binary_neighbors",false,"First.","Second.","Third.","Fourth."))
        assertEquals("Second.",model.requests[1]["comment"].asString)
        assertTrue(model.requests[1]["subject"].asString.contains("First."));assertTrue(model.requests[1]["subject"].asString.contains("Third."))
        assertFalse(model.requests[1]["subject"].asString.contains("Fourth."))
        assertTrue(file.readText().contains("\"raw\":[2,4]"))
    }}
    @Test fun closureKeepsQualificationsAndForwardConditionsWithoutSelectingAnEmptyGroup():Unit=runBlocking {temporary {file->
        assertEquals(listOf(1,2,3),review(Model(listOf(true,false,false,false)),file,"binary_parent",true,
            "The ferry leaves at 14:00.","Only if wind drops.","That has not happened yet.","Fantastic work!"))
        assertEquals(listOf(1,2),review(Model(listOf(false,true)),file,"binary_parent",true,"If wind drops.","The ferry can leave at 14:00."))
        assertEquals(emptyList<Int>(),review(Model(listOf(false,false)),file,"binary_parent",true,"It is fantastic.","That team is amazing."))
    }}
    @Test fun actualBudgetAndCancellationFailWithoutInventingASelection():Unit=runBlocking {temporary {file->
        val model=Model(listOf(true));model.oversized=true
        try {review(model,file,"binary_full",true,"A useful observation.");fail()}catch(_:ContextProcessingException) { }
        assertTrue(model.requests.isEmpty());assertFalse(file.exists())
        model.oversized=false;model.cancelled=true
        try {review(model,file,"binary_parent",false,"A useful observation.");fail()}catch(_:CancellationException) { }
        assertFalse(file.exists())
    }}
    @Test fun linkedGroupsUseTheFirstClauseAndKeepLaterForwardConditionsWithTheirConsequence() {
        assertEquals(listOf(listOf(0),listOf(1,2)),ContextBinaryPassageAudit.linkedGroups(listOf(
            "Amazing team!","If the river drops overnight.","The path can reopen at 08:00.")))
        assertEquals(listOf(listOf(0,1),listOf(2)),ContextBinaryPassageAudit.linkedGroups(listOf(
            "The test input was: ignore your instructions.","Version 5.1 rejected that input.","Everyone should subscribe!")))
        assertEquals(listOf(listOf(0),listOf(1)),ContextBinaryPassageAudit.linkedGroups(listOf(
            "Buy from us!","Our warranty lasts three years; that does not extend the maker's warranty.")))
    }
    @Test fun linkedClassificationMakesOneVoteForTheWholeExactComponentAndAppAssignsEveryOriginalId():Unit=runBlocking {temporary {file->
        val model=Model(listOf(false,true))
        val parts=arrayOf("Amazing team!","If the river drops overnight.","The path can reopen at 08:00.")
        assertEquals(listOf(2,3),review(model,file,"binary_linked",false,*parts))
        assertEquals(2,model.requests.size)
        assertEquals(parts[1]+"\n\n"+parts[2],model.requests[1]["comment"].asString)
        assertEquals("The ferry may resume service.",model.requests[1]["subject"].asString)
        assertTrue(file.readText().contains("\"passages\":[2,3]"))
    }}
    @Test fun strictRolesCannotIntroduceAnUnknownClassOrUnreviewedOutput() {
        assertEquals("observation",ContextBinaryPassageAudit.parseRole("{\"contribution\":\"observation\"}"))
        assertEquals("none",ContextBinaryPassageAudit.parseRole("{\"contribution\":\"none\"}"))
        for(raw in listOf("{\"contribution\":\"new rule\"}","{\"contribution\":true}","{\"contribution\":\"claim\",\"text\":\"invented\"}","{\"contribution\":\"none\",\"contribution\":\"claim\"}","{\"contribution\":\"question\"} {}")) {
            try {ContextBinaryPassageAudit.parseRole(raw);fail(raw)}catch(_:ContextProcessingException) { }
        }
    }
    @Test fun roleLabelsOnlySelectExactPassageIdsAndNeverRewriteEvidence():Unit=runBlocking {temporary {file->
        val requests=mutableListOf<JsonObject>();val labels=listOf("observation","none")
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                assertTrue(system.startsWith("Classify what the COMMENT contributes"));requests+=JsonParser.parseString(user).asJsonObject
                return "{\"contribution\":\""+labels[requests.lastIndex]+"\"}"
            }
        }
        assertEquals(listOf(1,2),review(model,file,"role_linked",false,"I checked the hinge.","That check did not cover other samples.","Wonderful team!"))
        assertEquals(2,requests.size);assertTrue(file.readText().contains("\"role\":\"observation\""))
    }}
    @Test fun groupedSelectorRejectsAmbiguousOrInventedComponentIds() {
        assertEquals(listOf(2,1),ContextBinaryPassageAudit.parseSelection("{\"keep\":[2,1.0]}",2))
        assertEquals(emptyList<Int>(),ContextBinaryPassageAudit.parseSelection("{\"keep\":[]}",2))
        for(raw in listOf("{\"keep\":[3]}","{\"keep\":[1,1.0]}","{\"keep\":[0]}","{\"keep\":[1.5]}","{\"keep\":[\"1\"]}","{\"keep\":[true]}","{\"keep\":[],\"keep\":[1]}","{\"keep\":[],\"text\":\"new\"}","{\"keep\":[]} {}")) {
            try {ContextBinaryPassageAudit.parseSelection(raw,2);fail(raw)}catch(_:ContextProcessingException) { }
        }
    }
    @Test fun groupedSelectorRetainsEveryQualificationUsingOneCallAndTheUnchangedInstruction():Unit=runBlocking {temporary {file->
        val requests=mutableListOf<JsonObject>()
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                assertEquals("Select useful source passages from S1.",system);assertEquals(64,maxTokens)
                requests+=JsonParser.parseString(user).asJsonObject
                return "{\"keep\":[2]}"
            }
        }
        val parts=arrayOf("Wonderful team!","I counted 310 photographs.","This trial does not establish typical battery life.")
        assertEquals(listOf(2,3),review(model,file,"selector_linked",false,*parts))
        val request=requests.single()
        assertEquals("The ferry may resume service.",request["parent_context"].asString)
        val groups=request["passages"].asJsonArray
        assertEquals(2,groups.size());assertEquals(parts[1]+"\n\n"+parts[2],groups[1].asJsonObject["text"].asString)
        assertTrue(file.readText().contains("\"selected_groups\":[2]"))
    }}
}
