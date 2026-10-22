package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextImageRecordsTest {
    @Test fun separateImageNameLinesStayWithTheirFactsThroughSelectionAndComposition():Unit=runBlocking {
        val texts=listOf("REPAIR RECORD\n\nJamie North\n\nInstalled a 56 Wh battery.\nThe original 65 Wh pack was incompatible.",
            "REPAIR RECORD\n\nAlex West\n\nReseated the connector.\nThe original 48 Wh battery works again.")
        val documents=texts.mapIndexed {i,text->ContextDocument("image-$i","r1",text,kind="article",metadata=mapOf("medium" to "Local image text recognition"))}
        val gson=Gson();var writes=0
        val model=object:ContextModel {
            override val contextTokens=8192
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                assertFalse("Short image records are harvested in code before admission",input.has("passages"))
                input.getAsJsonArray("items")?.let {items->return gson.toJson(mapOf("reviews" to items.map {mapOf("id" to it.asJsonObject["id"].asInt,"on_topic" to true,"adds_information" to true)}))}
                val findings=input.getAsJsonArray("findings")
                assertEquals(texts.toSet(),findings.map {it.asJsonObject["text"].asString}.toSet())
                assertTrue(system.contains("Never transfer details between images"));writes++
                return gson.toJson(mapOf("text" to "Jamie North installed a 56 Wh battery; the original 65 Wh pack was incompatible. Alex West reseated the connector; the original 48 Wh battery works again."))
            }
        }
        val engine=ContextEngine(model,"images",readerBrief=true)
        val extracted=engine.process(documents)
        assertEquals(2,extracted.findings.size)
        assertEquals(texts.toSet(),extracted.findings.map {it.text}.toSet())
        assertTrue(engine.summarizeResult(extracted.findings).text.contains("Alex West"));assertEquals(1,writes)
    }
}
