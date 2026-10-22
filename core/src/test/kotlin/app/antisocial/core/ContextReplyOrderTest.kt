package app.antisocial.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextReplyOrderTest {
    private class RejectingScreen(batch:Int=1):ContextModel {
        override val processingPlan=ContextProcessingPlan(ContextProcessingStyle.FOCUSED,true,batch,4,4,4096,"Order fixture",true)
        var calls=0
        override suspend fun count(system:String,user:String)=128
        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
            calls++
            return if(system.contains("\"relevant\""))"""{"relevant":false}"""else """{"keep":[1]}"""
        }
    }
    @Test fun anAnswerBeforeItsQuestionGetsTheSameDeterministicReviewAsOrderedSources():Unit=runBlocking {
        val model=RejectingScreen();val engine=ContextEngine(model,"reply-order",{it})
        val root=ContextDocument("root","1","A workshop answers battery repair questions.",kind="post")
        val parent=ContextDocument("parent","1","Did you replace the entire battery?",kind="comment")
        val child=ContextDocument("child","1","No.",metadata=mapOf("contextParent" to "parent-alias"))
        val background=ContextBackground(ContextBackgroundSource(root.text,sourceId=root.id),ContextBackgroundSource(parent.text,sourceId=parent.id))
        val result=engine.process(listOf(root,child,parent),backgrounds=mapOf(child.id to background))
        assertTrue("Ordering must not discard an explicit answer",result.findings.any {child.id in it.sources&&it.text=="No."})
        assertEquals("Questions and closed answers need no relevance judgment",0,model.calls)
        assertEquals(listOf(root.id,parent.id,child.id),result.outcomes.map {it.documentId})
    }
    @Test fun aQuestionProtectedRegardlessOfTheVoteDoesNotNeedThatVote():Unit=runBlocking {
        val model=RejectingScreen();val engine=ContextEngine(model,"question-vote")
        val doc=ContextDocument("q","1","Has the funding already been approved?",kind="comment")
        val result=engine.process(listOf(doc),backgrounds=mapOf(doc.id to ContextBackground(post=ContextBackgroundSource("A roof replacement appears in a proposed budget."))))
        assertEquals("question",result.findings.single().kind)
        assertEquals("The ignored classifier vote must not consume model work",0,model.calls)
    }
    @Test fun deterministicQuestionsAndAnswersCannotBeAbsorbedIntoAMicroBatch():Unit=runBlocking {
        val model=RejectingScreen(4);val engine=ContextEngine(model,"micro-batch-order")
        val parent=ContextDocument("parent","1","Did you replace the entire battery?",kind="comment")
        val child=ContextDocument("child","1","No.",metadata=mapOf("contextParent" to parent.id))
        val result=engine.process(listOf(child,parent),backgrounds=mapOf(child.id to ContextBackground(reply=ContextBackgroundSource(parent.text,sourceId=parent.id))))
        assertEquals(0,model.calls);assertTrue(result.findings.any {child.id in it.sources});assertEquals(listOf(parent.id,child.id),result.outcomes.map {it.documentId})
    }
    @Test fun dependentSourcesAreNeverReviewedAsIndependentPeersInALargerBatch():Unit=runBlocking {
        val requests=mutableListOf<com.google.gson.JsonObject>()
        val model=object:ContextModel {
            override val processingPlan=ContextProcessingPlan.SINGLE.copy(sourcesPerCall=4)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=com.google.gson.JsonParser.parseString(user).asJsonObject;requests+=input
                fun review(source:com.google.gson.JsonObject)=com.google.gson.JsonObject().apply {
                    addProperty("status","signal");add("findings",com.google.gson.JsonArray().apply {add(com.google.gson.JsonObject().apply {
                        addProperty("text",source["text"].asString);addProperty("quote",source["text"].asString);addProperty("kind","context")
                    })})
                }
                return if(input.has("sources"))com.google.gson.JsonObject().apply {add("reviews",com.google.gson.JsonArray().apply {input["sources"].asJsonArray.forEach {source->add(review(source.asJsonObject).apply {addProperty("source_id",source.asJsonObject["source_id"].asString)})}})}.toString()
                    else review(input).toString()
            }
        }
        val parent=ContextDocument("parent","1","Did you replace the entire battery?",kind="comment")
        val child=ContextDocument("child","1","No.",metadata=mapOf("contextParent" to parent.id))
        val engine=ContextEngine(model,"large-batch-order")
        val result=engine.process(listOf(child,parent),backgrounds=mapOf(child.id to ContextBackground(reply=ContextBackgroundSource(parent.text,sourceId=parent.id))))
        assertEquals(2,requests.size);assertTrue(requests.none {it.has("sources")})
        assertEquals(listOf(parent.id,child.id),result.outcomes.map {it.documentId})
    }

}
