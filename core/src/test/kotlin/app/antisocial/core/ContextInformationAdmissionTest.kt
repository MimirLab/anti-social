/* AntiSocial, GPL-3.0-or-later. Admission precedes prose and lossy compaction. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextInformationAdmissionTest {
    @Test fun changedUncitedPostContextCannotReuseAnEarlierComposition():Unit=runBlocking {
        var subject="The laptop guide.";var writes=0
        val base=Model {"""{"test_keep":[1]}"""}
        val model=object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("items"))return base.generate(system,user,maxTokens)
                assertEquals(subject,input["post_context"].asJsonObject["text"].asString)
                writes++
                return gson.toJson(mapOf("text" to "The connector is loose. "+subject))
            }
        }
        val engine=ContextEngine(model,readerBrief=true,readerSubject={subject})
        val first=engine.summarizeResult(listOf(finding("connector","The connector is loose.")))
        subject="The bicycle guide."
        val next=engine.summarizeResult(first.findings,first.text,first.receipts)
        assertEquals(2,writes);assertNotEquals(first.text,next.text)
        engine.summarizeResult(next.findings,next.text,next.receipts)
        assertEquals(2,writes)
    }
    private fun supportedModel(check:(String)->Pair<String,String>):ContextModel {
        val base=Model {user->gson.toJson(mapOf("test_keep" to JsonParser.parseString(user).asJsonObject["items"].asJsonArray.map {it.asJsonObject["id"].asInt}))}
        return object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input["support_review"]?.asBoolean==true) {
                    val (basis,quote)=check(input["items"].asJsonArray.single().asJsonObject["text"].asString)
                    return gson.toJson(mapOf("checks" to listOf(mapOf("id" to 1,"basis" to basis,"quote" to quote))))
                }
                return base.generate(system,user,maxTokens)
            }
        }
    }
    private fun commentEngine(model:ContextModel)=ContextEngine(model,"basis",{"Reader"},{emptyList()},
        {ContextEvidenceScope(ContextTextObservation.COMMENT)},readerBrief=true,readerSubject={"The post and its relevant evidence."})
    @Test fun callingAnAssertionAReferenceDoesNotMakeItAnIdentifyingRecord():Unit=runBlocking {
        val text="Trump told all GOPs to vote no on this non partisan border bill. He said he wanted to use border issues in his campaign."
        var checks=0
        val model=supportedModel {checks++;"reference" to it}
        val engine=commentEngine(model)
        val result=engine.summarizeResult(listOf(finding("claim",text)))
        assertEquals("",result.text);assertTrue(result.sources.isEmpty())
        engine.summarizeResult(result.findings,result.text,result.receipts)
        assertEquals(2,checks)
    }
    @Test fun supportSplitFromACorrectionStaysBoundToTheSameOriginalComment():Unit=runBlocking {
        var checks=0
        val correction="The service manual says 56 Wh, not 65 Wh."
        val observation="I replaced this exact battery yesterday."
        val base=supportedModel {"none" to ""}
        val model=object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input["support_review"]?.asBoolean==true) {
                    checks++
                    val row=input["items"].asJsonArray.single().asJsonObject
                    if(!row.has("finding"))return """{"checks":[{"id":1,"basis":"none","quote":""}]}"""
                    assertTrue(row["text"].asString.contains(correction))
                    assertTrue(row["text"].asString.contains(observation))
                    assertFalse(row["text"].asString.contains("unrelated author"))
                    val retry=system.startsWith("Review the supplied finding using only text")
                    return gson.toJson(mapOf("checks" to listOf(mapOf("id" to 1,"basis" to if(retry)"firsthand" else "reference","quote" to if(retry)observation else correction))))
                }
                return base.generate(system,user,maxTokens)
            }
        }
        fun same(id:String,text:String)=finding(id,text).copy(sources=listOf("technician"),quotes=mapOf("technician" to text))
        val engine=commentEngine(model)
        val values=listOf(same("correction",correction),same("observation",observation))
        val first=engine.summarizeResult(values)
        assertTrue(first.text.contains("56 Wh"));assertTrue(first.text.contains(observation))
        val count=checks
        engine.summarizeResult(values+finding("stranger","An unrelated author supplies another observation."),first.text,first.receipts)
        // The stranger gets its own review; it cannot alter either completed
        // technician receipt or provide support for those findings.
        assertEquals(count+1,checks)
        val removed=engine.summarizeResult(listOf(values.first()),completed=first.receipts)
        assertEquals("",removed.text);assertTrue(checks>count+1)
    }
    @Test fun aReportedPoliticalMotiveCannotBeLaunderedAsAPracticalTip():Unit=runBlocking {
        val text="He said he wanted to use border issues for his campaign."
        assertEquals("",commentEngine(supportedModel {"practical" to it}).summarizeResult(listOf(finding("claim",text))).text)
        assertEquals("",commentEngine(supportedModel {"none" to it}).summarizeResult(listOf(finding("claim",text))).text)
    }
    @Test fun supportCannotInventItsQuotationOrCompleteADecision():Unit=runBlocking {
        var committed=0
        assertThrows(ContextProcessingException::class.java) {runBlocking {
            commentEngine(supportedModel {"reference" to "https://invented.invalid/a-record"}).summarizeResult(
                listOf(finding("claim","A source sentence without a supporting link.")),onCompact={committed++})
        }}
        assertEquals(0,committed)
    }
    @Test fun aSupportExcerptDoesNotDiscardTheSourcesOtherConditions():Unit=runBlocking {
        val observation="I reseated the connector and the battery works again. I have not tested other models."
        val record="Section 4 caps the inspection fee at 12 dollars for this building size."
        val engine=commentEngine(supportedModel {text->if(text.startsWith("I "))"firsthand" to "I reseated the connector and the battery works again" else "reference" to "Section 4"})
        val result=engine.summarizeResult(listOf(finding("repair",observation),finding("record",record)))
        assertTrue(result.text.contains("not tested other models"));assertTrue(result.text.contains("12 dollars"))
        assertEquals(setOf("repair","record"),result.sources.toSet())
    }
    @Test fun aLargeWriterRetriesInventedDiscussionNarrationAgainstTheSameEvidence():Unit=runBlocking {
        var writes=0
        val fact="The manual specifies 56 Wh, not 65 Wh."
        val base=Model {"""{"test_keep":[1]}"""}
        val model=object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                if(JsonParser.parseString(user).asJsonObject.has("items"))return base.generate(system,user,maxTokens)
                writes++
                return gson.toJson(mapOf("text" to if(writes==1)"A commenter corrects the capacity. Another commenter notes the manual. $fact" else fact))
            }
        }
        val engine=ContextEngine(model,readerBrief=true)
        val result=engine.summarizeResult(listOf(finding("manual",fact)))
        assertEquals(fact,result.text);assertEquals(2,writes)
        engine.summarizeResult(result.findings,result.text,result.receipts)
        assertEquals(2,writes)
    }
    @Test fun numericCorrectionAllowsExplicitContrastButKeepsItsValueDirection():Unit=runBlocking {
        val fact=finding("manual","The service manual says 56 Wh, not 65 Wh.","correction")
        for(text in listOf("The service manual specifies 56 Wh, correcting the 65 Wh figure.",
            "The service manual specifies 56 Wh rather than 65 Wh.","The service manual specifies 56 Wh instead of 65 Wh.")) {
            val base=Model {"""{"test_keep":[1]}"""}
            val model=object:ContextModel by base {
                override suspend fun generate(system:String,user:String,maxTokens:Int)=if(JsonParser.parseString(user).asJsonObject.has("items"))base.generate(system,user,maxTokens) else gson.toJson(mapOf("text" to text))
            }
            assertEquals(text,ContextEngine(model,"guard",{"Manual"},{emptyList()},{ContextEvidenceScope(ContextTextObservation.PAGE)},readerBrief=true).summarizeResult(listOf(fact)).text)
        }
        for(text in listOf("The service manual specifies 65 Wh, correcting the 56 Wh figure.",
            "The service manual specifies 56 Wh.","The service manual lists 56 Wh and 65 Wh; both work.")) {
            val base=Model {"""{"test_keep":[1]}"""}
            val model=object:ContextModel by base {
                override suspend fun generate(system:String,user:String,maxTokens:Int)=if(JsonParser.parseString(user).asJsonObject.has("items"))base.generate(system,user,maxTokens) else gson.toJson(mapOf("text" to text))
            }
            assertThrows(ContextProcessingException::class.java){runBlocking {
                ContextEngine(model,"guard",{"Manual"},{emptyList()},{ContextEvidenceScope(ContextTextObservation.PAGE)},readerBrief=true).summarizeResult(listOf(fact))
            }}
        }
    }
    @Test fun shortPreparedCommentGetsOneStrictSameSourceFormatRetry():Unit=runBlocking {
        val source="The service manual says 56 Wh, not 65 Wh."
        for(repaired in listOf(true,false)) {
            var calls=0;var committed=0
            val model=object:ContextModel {
                override val contextTokens=8192
                override suspend fun count(system:String,user:String)=128
                override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                    calls++
                    assertEquals(source,JsonParser.parseString(user).asJsonObject["text"].asString)
                    assertFalse(user.contains("unreviewed-extra"))
                    if(calls==2)assertTrue(system.contains("failed response is discarded"))
                    val extra=if(calls==1||!repaired)",\"unreviewed-extra\":true" else ""
                    return "{\"status\":\"signal\",\"findings\":[{\"text\":\"The battery is 56 Wh, not 65 Wh.\",\"kind\":\"correction\",\"quote\":\"$source\"$extra}]}"
                }
            }
            val engine=ContextEngine(model,readerBrief=true)
            val doc=ContextDocument("comment","revision",source,kind="comment",metadata=mapOf("readingLanguage" to "en"))
            if(repaired) {
                val result=engine.process(listOf(doc),onUnit={committed++})
                assertEquals(source,result.findings.single().quotes[doc.id]);assertEquals(1,committed)
                engine.process(listOf(doc),result.findings,result.outcomes)
            } else {
                assertThrows(ContextProcessingException::class.java){runBlocking {engine.process(listOf(doc),onUnit={committed++})}}
                assertEquals(0,committed)
            }
            assertEquals(2,calls)
        }
    }
    @Test fun correctionFallbackCannotMergeAuthorsOrUseUncertainText():Unit=runBlocking {
        val fact=finding("manual","The service manual says 56 Wh, not 65 Wh.","correction")
        val base=Model {user->gson.toJson(mapOf("test_keep" to JsonParser.parseString(user).asJsonObject["items"].asJsonArray.map {it.asJsonObject["id"].asInt}))}
        val broken=object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int)=if(JsonParser.parseString(user).asJsonObject.has("items"))base.generate(system,user,maxTokens) else """{"text":"Both batteries work."}"""
        }
        assertThrows(ContextProcessingException::class.java){runBlocking {
            ContextEngine(broken,readerBrief=true).summarizeResult(listOf(fact,finding("other","I tried a different battery.")))
        }}
        // Unconfirmed source text cannot acquire a plain exact-source fallback.
        val approved=supportedModel {"practical" to it}
        val uncertainModel=object:ContextModel by approved {
            override suspend fun generate(system:String,user:String,maxTokens:Int)=if(JsonParser.parseString(user).asJsonObject.has("items"))approved.generate(system,user,maxTokens) else """{"text":"Both batteries work."}"""
        }
        val uncertain=ContextEngine(uncertainModel,"uncertain",{"Technician"},{emptyList()},
            {ContextEvidenceScope(ContextTextObservation.COMMENT,uncertain=true)},readerBrief=true)
        assertThrows(ContextProcessingException::class.java){runBlocking {uncertain.summarizeResult(listOf(fact))}}
    }
    @Test fun generatedCompactionCannotBecomeAnExactSourceFallback():Unit=runBlocking {
        var writes=0
        val base=Model {user->gson.toJson(mapOf("test_keep" to JsonParser.parseString(user).asJsonObject["items"].asJsonArray.map {it.asJsonObject["id"].asInt}))}
        val model=object:ContextModel by base {
            override suspend fun count(system:String,user:String):Int {
                val input=JsonParser.parseString(user).asJsonObject
                val values=input["findings"]?.asJsonArray
                return if(values?.size()==2&&values.any {it.asJsonObject["text"].asString.length>500})9000 else 128
            }
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("items"))return base.generate(system,user,maxTokens)
                writes++
                return gson.toJson(mapOf("text" to if(writes<=2)input["findings"].asJsonArray.single().asJsonObject["text"].asString.substringBefore('.')+"." else "Both batteries work."))
            }
        }
        fun source(id:String,text:String,kind:String)=finding(id,text,kind).copy(sources=listOf("technician"),quotes=mapOf("technician" to text))
        val values=listOf(source("spec","The service manual says 56 Wh, not 65 Wh. "+"This applies only to revision A. ".repeat(25),"correction"),
            source("repair","The technician replaced the battery. "+"The pack was disconnected first. ".repeat(25),"context"))
        assertThrows(ContextProcessingException::class.java){runBlocking {ContextEngine(model,readerBrief=true).summarizeResult(values)}}
        assertEquals(4,writes)
    }
    @Test fun admittedShortCorrectionKeepsItsMeaningWithoutAnotherRewrite():Unit=runBlocking {
        val source="The service manual says 56 Wh, not 65 Wh."
        val base=supportedModel {"practical" to it}
        val model=object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                check(JsonParser.parseString(user).asJsonObject.has("items")){"The selected exact correction needs no prose generation"}
                return base.generate(system,user,maxTokens)
            }
        }
        val engine=commentEngine(model)
        val result=engine.summarizeResult(listOf(finding("manual",source,"correction")))
        assertEquals(source,result.text);assertEquals(listOf("manual"),result.sources)
        engine.summarizeResult(result.findings,result.text,result.receipts)
    }
    @Test fun actualSourceInformationAboutAMisconceptionIsNotAStyleError():Unit=runBlocking {
        val fact="The experiment documents a common misconception: energy capacity is measured in Wh."
        val model=Model {"""{"test_keep":[1]}"""}
        assertEquals(fact,ContextEngine(model,readerBrief=true).summarizeResult(listOf(finding("study",fact))).text)
        assertEquals(1,model.compositions)
    }
    @Test fun microObservationNominationCannotPromoteBoastsOrUnrelatedFirsthandReports():Unit=runBlocking {
        var ordinary=0;var supplements=0;var writes=0
        val model=object:ContextModel {
            override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("items")) {
                    val extra=system.startsWith("Review each item for two properties")
                    if(extra)supplements++ else ordinary++
                    // An over-eager supplemental fake exposes routing errors:
                    // nomination itself must exclude the other three sources.
                    return gson.toJson(mapOf("reviews" to listOf(mapOf("id" to 1,"on_topic" to true,"adds_information" to extra))))
                }
                writes++;return """{"text":"Reseating the battery connector restored operation; other models were not tested."}"""
            }
        }
        val values=listOf(finding("repair","I reseated the battery connector and it works again. I have not tested other models."),
            finding("boast","I like when he says my battery is the greatest ever."),
            finding("unrelated","I tested a pasta recipe and the noodles cooked in nine minutes."),
            finding("repeat","The guide recommends a replacement battery for this laptop."))
        val engine=ContextEngine(model,"nomination",{"Repair visitor"},{emptyList()},{ContextEvidenceScope(ContextTextObservation.COMMENT)},readerBrief=true,
            readerSubject={"The guide recommends a replacement battery for this laptop."})
        val result=engine.summarizeResult(values)
        assertEquals(listOf("repair"),result.sources);assertEquals(4,ordinary);assertEquals(1,supplements);assertEquals(1,writes)
        engine.summarizeResult(values,result.text,result.receipts)
        assertEquals(4,ordinary);assertEquals(1,supplements);assertEquals(1,writes)
    }
    @Test fun aSmallWindowReservesOnlyTheBoundedDecisionResponse():Unit=runBlocking {
        val base=Model {"""{"test_keep":[1]}"""};var budget=0
        val model=object:ContextModel by base {
            override val contextTokens=2048
            override suspend fun count(system:String,user:String)=system.toByteArray().size+user.toByteArray().size+128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                assertTrue(count(system,user)+maxTokens+32<=contextTokens)
                if(JsonParser.parseString(user).asJsonObject.has("items"))budget=maxTokens
                return base.generate(system,user,maxTokens)
            }
        }
        val text="The guide specifies a T5 screwdriver and says to disconnect the battery first."
        assertEquals(text,ContextEngine(model,readerBrief=true).summarizeResult(listOf(finding("guide",text))).text)
        assertEquals(128,budget)
    }
    @Test fun aHundredRejectedArrivalsAdvanceTheirReceiptsWithoutRewritingTheBrief():Unit=runBlocking {
        val model=Model {user->
            val items=JsonParser.parseString(user).asJsonObject["items"].asJsonArray
            gson.toJson(mapOf("test_keep" to items.filter {it.asJsonObject["text"].asString.contains("56 Wh")}.map {it.asJsonObject["id"].asInt}))
        }
        val engine=ContextEngine(model,readerBrief=true,readerSubject={"A laptop repair."})
        val baseline=listOf(finding("repair","The replacement battery is 56 Wh."))
        var result=engine.summarizeResult(baseline)
        val brief=result.text
        for(page in 1..100) {
            val arrivals=(1..page).map {finding("noise-$it","Another unrelated price reaction number $it.")}
            result=engine.summarizeResult(baseline+arrivals,result.text,result.receipts)
            assertEquals(brief,result.text);assertEquals(1,model.compositions)
            assertEquals(page+1,result.receipts.count {it.level==0})
        }
        assertEquals(101,model.selections)
        val calls=model.selections
        engine.summarizeResult(result.findings,result.text,result.receipts)
        assertEquals(calls,model.selections);assertEquals(1,model.compositions)
    }
    @Test fun shortPreparedMicroCommentsAreHarvestedBeforeAnyLossyDecision():Unit=runBlocking {
        val model=object:ContextModel {
            override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true,screenComments=true)
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String=error("Candidate harvest must not ask the model to paraphrase or prejudge this short source")
        }
        val text="I reseated the connector; the battery works again. I have not tested other models."
        val doc=ContextDocument("repair","1",text,metadata=mapOf("readingLanguage" to "en"))
        val result=ContextEngine(model,readerBrief=true).process(listOf(doc))
        assertEquals(text,result.findings.single().quotes[doc.id])
        assertEquals(ContextUnitStatus.SIGNAL,result.outcomes.single().status)
    }
    @Test fun selectedEvidenceKeepsOnlyTheAdmittedSourcesOfASharedFinding() {
        val finding=ContextFinding("answer","No.","answer",listOf("reply-a","reply-b"),mapOf("reply-a" to "No.","reply-b" to "No."),mapOf("reply-a" to "No.","reply-b" to "No."))
        val selected=ContextBriefEvidence.select(listOf(finding),listOf("answer"),listOf("reply-a","question-a")).single()
        assertEquals(listOf("reply-a"),selected.sources);assertEquals(setOf("reply-a"),selected.quotes.keys);assertEquals(setOf("reply-a"),selected.readingQuotes!!.keys)
        assertTrue(ContextBriefEvidence.select(listOf(finding),emptyList(),emptyList()).isEmpty())
        assertEquals(listOf(finding),ContextBriefEvidence.select(listOf(finding),null,null))
    }
    @Test fun unicodeNamesCannotBeSplicedFromDifferentPeopleInTheSource():Unit=runBlocking {
        val fact="Federal agents arrested 28 alleged noncitizens."
        val source="$fact Muñoz, 72, faces registration charges. Another defendant is José Probitsado Amaro."
        val base=Model {"""{"test_keep":[1]}"""};var writes=0
        val model=object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                if(JsonParser.parseString(user).asJsonObject.has("items"))return base.generate(system,user,maxTokens)
                writes++;return gson.toJson(mapOf("text" to "$fact José Muñoz faces registration charges."))
            }
        }
        val result=ContextEngine(model,readerBrief=true).summarizeResult(listOf(finding("report",source)))
        assertEquals(2,writes);assertEquals(fact,result.text)
    }
    @Test fun genuineUnicodeNamesAndCoherentShortenedNamesRemainUsable():Unit=runBlocking {
        val source="José Probitsado Amaro faces charges. Bill Essayli described the registration process. Belizean national Muñoz faces charges. R. Trent McCotter explained the record."
        for(text in listOf("José Probitsado Amaro faces charges.","José Amaro faces charges.","US Attorney Bill Essayli described the registration process.","Belizean Muñoz faces charges.","R. Trent McCotter explained the record.")) {
            val base=Model {"""{"test_keep":[1]}"""}
            val model=object:ContextModel by base {
                override suspend fun generate(system:String,user:String,maxTokens:Int):String=if(JsonParser.parseString(user).asJsonObject.has("items"))base.generate(system,user,maxTokens)else gson.toJson(mapOf("text" to text))
            }
            assertEquals(text,ContextEngine(model,readerBrief=true).summarizeResult(listOf(finding("report",source))).text)
        }
    }
    @Test fun microPastTenseRepairUsesTheSameOriginalActionObjectAndTime():Unit=runBlocking {
        val fact="This laptop needs 56 Wh, not 65 Wh. I fitted this exact battery yesterday."
        val base=Model {"""{"test_keep":[1]}"""}
        val model=object:ContextModel by base {
            override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true)
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                if(JsonParser.parseString(user).asJsonObject.has("items"))return base.generate(system,user,maxTokens)
                return gson.toJson(mapOf("text" to "This laptop needs 56 Wh, not 65 Wh. Fit an exact 56 Wh battery yesterday."))
            }
        }
        val result=ContextEngine(model,readerBrief=true).summarizeResult(listOf(finding("battery",fact)))
        assertEquals("This laptop needs 56 Wh, not 65 Wh. Fitted this exact battery yesterday.",result.text)
    }
    @Test fun aGeneratedPastActionCannotBorrowItsObjectOrTimeFromAnotherSource():Unit=runBlocking {
        val base=Model {"""{"test_keep":[1,2]}"""}
        val model=object:ContextModel by base {
            override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true)
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                if(JsonParser.parseString(user).asJsonObject.has("items"))return base.generate(system,user,maxTokens)
                return gson.toJson(mapOf("text" to "Fit the connector yesterday."))
            }
        }
        assertThrows(ContextProcessingException::class.java){runBlocking {
            ContextEngine(model,readerBrief=true).summarizeResult(listOf(finding("battery","I fitted the battery yesterday."),finding("connector","The connector is loose.")))
        }}
        for(source in listOf("I fitted the connector today. I checked the battery yesterday.","I fitted no connector yesterday.")) {
            assertThrows(ContextProcessingException::class.java){runBlocking {
                ContextEngine(model,readerBrief=true).summarizeResult(listOf(finding("single",source)))
            }}
        }
    }
    @Test fun aStubbornOfficialReactionCannotVetoTheUsefulRestOfABrief():Unit=runBlocking {
        val fact="Federal agents arrested 28 alleged noncitizens under Operation Protect the Vote. The review order gave the state 48 hours."
        val reaction="State officials, including Governor Newsom, denied the problem was widespread and called the move a political attempt to suppress voters."
        val base=Model {"""{"test_keep":[1]}"""};var writes=0
        val model=object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                if(JsonParser.parseString(user).asJsonObject.has("items"))return base.generate(system,user,maxTokens)
                writes++;return gson.toJson(mapOf("text" to "$fact $reaction"))
            }
        }
        val result=ContextEngine(model,"reactions",{"Publisher"},{emptyList()},{ContextEvidenceScope(ContextTextObservation.PAGE)},readerBrief=true)
            .summarizeResult(listOf(finding("article","$fact $reaction")))
        assertEquals(2,writes);assertEquals(fact,result.text)
        assertTrue(result.findings.single().quotes.values.single().contains(reaction))
    }
    @Test fun factualDecisionsAndNumericDenialsAreNotBarePoliticalReactions():Unit=runBlocking {
        val fact="State officials denied 24 permit applications. The court rejected the appeal. The service manual specifies 56 Wh, not 65 Wh."
        val model=Model {"""{"test_keep":[1]}"""}
        val result=ContextEngine(model,"decisions",{"Record"},{emptyList()},{ContextEvidenceScope(ContextTextObservation.PAGE)},readerBrief=true)
            .summarizeResult(listOf(finding("record",fact,"correction")))
        assertEquals(fact,result.text)
    }
    @Test fun articleSelectionRestoresWholeOriginalPassagesWithoutGeneratedQuotation():Unit=runBlocking {
        val article="The agency charged 28 people with alleged registration violations. Charges are not convictions.\n\nThe list contains more than 20,000 suspected entries; the review deadline is 48 hours.\n\nSubscribe to our newsletter and follow us."
        var calls=0
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=(system.length+user.length)/4
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                calls++
                val input=JsonParser.parseString(user).asJsonObject
                assertTrue(input.has("passages"));assertFalse(input.has("text"))
                val passages=input["passages"].asJsonArray
                assertTrue(passages[0].asJsonObject["text"].asString.contains("not convictions"))
                return """{"keep":[1,2]}"""
            }
        }
        val document=ContextDocument("article","r1",article,kind="article")
        val engine=ContextEngine(model,readerBrief=true)
        val result=engine.process(listOf(document))
        assertEquals(1,calls);assertEquals(2,result.findings.size)
        assertTrue(result.findings.first().quotes.getValue(document.id).contains("Charges are not convictions."))
        assertTrue(result.findings.all {article.contains(it.quotes.getValue(document.id))})
        engine.process(listOf(document),result.findings,result.outcomes)
        assertEquals(1,calls)
    }
    @Test fun finiteArticleSelectionUsesItsSixtyFourPassageBoundInsteadOfTheFreeformFindingQuota():Unit=runBlocking {
        val article=(1..40).joinToString("\n\n"){"Inspection batch $it measured 12 volts at the connector."}
        val model=object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val passages=JsonParser.parseString(user).asJsonObject["passages"].asJsonArray
                assertEquals(40,passages.size())
                return gson.toJson(mapOf("keep" to (1..40).toList()))
            }
        }
        val result=ContextEngine(model,readerBrief=true).process(listOf(ContextDocument("a","1",article,kind="article")))
        assertEquals(40,result.findings.size)
        assertTrue(result.findings.all {article.contains(it.quotes.getValue("a"))})
    }
    @Test fun invalidArticleSelectionCannotCompleteTheSource():Unit=runBlocking {
        for(reply in listOf("""{"keep":[999]}""","""{"keep":[1,1]}""","""{"keep":[1],"text":"an invented quote"}""")) {
            val model=object:ContextModel {
                override suspend fun count(system:String,user:String)=128
                override suspend fun generate(system:String,user:String,maxTokens:Int)=reply
            }
            var commits=0
            assertThrows(ContextProcessingException::class.java){runBlocking {
                ContextEngine(model,readerBrief=true).process(listOf(ContextDocument("a","1","A qualified finding.",kind="article")),onUnit={commits++})
            }}
            assertEquals(0,commits)
        }
    }
    @Test fun readerWriterGetsInformationWithoutAuthorOrPlatformRoleBait():Unit=runBlocking {
        val fact="The service manual specifies a T5 screwdriver."
        val base=Model {"""{"test_keep":[1]}"""};var writes=0
        val model=object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("items"))return base.generate(system,user,maxTokens)
                val row=input["findings"].asJsonArray.single().asJsonObject
                assertFalse(row.has("speakers"));assertFalse(row.has("source_role"))
                writes++
                return gson.toJson(mapOf("text" to if(writes==1)"Comments say $fact Officials dispute the conclusions."else fact))
            }
        }
        val engine=ContextEngine(model,"no-speakers",{"random_handle_47"},{emptyList()},{ContextEvidenceScope(ContextTextObservation.PAGE)},readerBrief=true)
        val result=engine.summarizeResult(listOf(finding("manual",fact)))
        assertEquals(fact,result.text);assertEquals(2,writes);assertEquals(listOf("manual"),result.sources)
    }
    private val gson=Gson()
    private fun finding(id:String,text:String,kind:String="context")=ContextFinding(id,text,kind,listOf(id),mapOf(id to text))
    private class Model(val decision:(String)->String):ContextModel {
        var selections=0;var compositions=0
        override val contextTokens=8192
        override suspend fun count(system:String,user:String)=(system.length+user.length+3)/4+32
        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
            assertTrue(count(system,user)+maxTokens+32<=contextTokens)
            val input=JsonParser.parseString(user).asJsonObject
            if(input.has("items")) {
                selections++
                val raw=decision(user)
                val chosen=JsonParser.parseString(raw).asJsonObject
                if(chosen.has("test_keep")) {
                    val ids=chosen["test_keep"].asJsonArray.map {it.asInt}
                    return Gson().toJson(mapOf("reviews" to input["items"].asJsonArray.map {item->
                        mapOf("id" to item.asJsonObject["id"].asInt,"on_topic" to (item.asJsonObject["id"].asInt in ids),"adds_information" to true)
                    }))
                }
                return raw
            }
            compositions++
            val evidence=JsonParser.parseString(user).asJsonObject["findings"].asJsonArray
            return Gson().toJson(mapOf("text" to evidence.joinToString(" "){it.asJsonObject["text"].asString}))
        }
    }
    @Test fun allNoiseClearsThePreviousBriefAndCachesItsSuccessfulRejections():Unit=runBlocking {
        val model=Model {"""{"test_keep":[]}"""}
        val engine=ContextEngine(model,readerBrief=true)
        val noisy=listOf(finding("pitch","We are winning like never before. Watch our best moments now!","brief"),
            finding("taunt","How about that unrelated stock going from 70 to 6.46. LOOSER!"))
        val result=engine.summarizeResult(noisy,"Old noisy brief")
        assertEquals("",result.text);assertTrue(result.changed);assertEquals(0,model.compositions)
        assertEquals(noisy.map {it.text},result.findings.map {it.text});assertTrue(result.sources.isEmpty())
        assertEquals(2,result.receipts.size);assertTrue(result.receipts.all {it.level==0});assertEquals(emptyList<String>(),result.selectedFindings)
        engine.summarizeResult(noisy,completed=result.receipts)
        assertEquals(1,model.selections)
        assertEquals("",engine.summarizeResult(emptyList(),"Earlier noise").text)
    }
    @Test fun onlySelectedEvidenceCanReachCompositionAndChangedEvidenceIsReconsidered():Unit=runBlocking {
        val model=Model {user->
            val items=JsonParser.parseString(user).asJsonObject["items"].asJsonArray
            gson.toJson(mapOf("test_keep" to items.filter {it.asJsonObject["text"].asString.contains("56 Wh")}.map {it.asJsonObject["id"].asInt}))
        }
        val engine=ContextEngine(model,readerBrief=true,readerSubject={"The laptop uses a 65 Wh replacement battery."})
        val useful=finding("technician","Read the damn manual: this laptop uses 56 Wh, not 65 Wh. I replaced this exact battery yesterday.","correction")
        val noisy=finding("taunt","The unrelated stock price is 6.46, you loser.")
        val candidate=useful.copy(text=useful.text+" This happened on 2026-10-20.")
        val first=engine.summarizeResult(listOf(candidate,noisy))
        assertTrue(first.text.contains("56 Wh"));assertFalse(first.text.contains("6.46"));assertFalse(first.text.contains("2026-10-20"))
        assertEquals(listOf("technician"),first.sources)
        val calls=model.selections+model.compositions
        engine.summarizeResult(listOf(candidate,noisy),first.text,first.receipts)
        assertEquals(calls,model.selections+model.compositions)
        engine.summarizeResult(listOf(useful,noisy.copy(quotes=mapOf("taunt" to "A changed source edition"))),first.text,first.receipts)
        assertTrue(model.selections+model.compositions>calls)
    }
    @Test fun bogusSelectionNeverAcquiresACompletedReceiptOrFallsBackToUnfilteredProse():Unit=runBlocking {
        for(raw in listOf("""{"keep":[2]}""","""{"keep":[1,1]}""","""{"keep":[1.5]}""","""{"keep":["1"]}""","""{"text":"trust me"}""","""{"keep":[],"note":"new data"}""", """{"reviews":[{"id":1,"on_topic":true,"adds_information":"true"}]}""", """{"reviews":[]}""")) {
            val model=Model {raw};var receipts=0
            assertThrows(ContextProcessingException::class.java){runBlocking {
                ContextEngine(model,readerBrief=true).summarizeResult(listOf(finding("one","A source sentence.")),onCompact={receipts++})
            }}
            assertEquals(0,receipts);assertEquals(0,model.compositions)
        }
    }
    @Test fun topicalNoiseAndSpecificOffTopicFactsFailIndependentTests():Unit=runBlocking {
        val model=Model {"""{"reviews":[{"id":1,"on_topic":true,"adds_information":false},{"id":2,"on_topic":false,"adds_information":true},{"id":3,"on_topic":true,"adds_information":true}]}"""}
        val values=listOf(finding("opinion","That project is the greatest ever."),finding("stock","The author's unrelated shares cost 12 dollars."),finding("pinout","The drawing identifies pin 3 as ground."))
        val result=ContextEngine(model,readerBrief=true).summarizeResult(values)
        assertEquals("The drawing identifies pin 3 as ground.",result.text)
        assertEquals(listOf("pinout"),result.sources);assertEquals(1,result.selectedFindings!!.size)
    }
    @Test fun laterArrivalsReviewOnlyTheirOwnNewEvidence():Unit=runBlocking {
        val seen=mutableListOf<String>()
        val model=Model {user->
            val items=JsonParser.parseString(user).asJsonObject["items"].asJsonArray
            seen+=items.map {it.asJsonObject["text"].asString}
            gson.toJson(mapOf("test_keep" to emptyList<Int>()))
        }
        val engine=ContextEngine(model,readerBrief=true)
        val first=listOf(finding("one","An unrelated price update."),finding("two","An opinion without an explanation."))
        val result=engine.summarizeResult(first)
        val later=finding("three","Another unrelated price update.")
        engine.summarizeResult(first+later,completed=result.receipts)
        assertEquals(first.map {it.text}+later.text,seen)
        assertEquals(2,model.selections)
    }
    @Test fun bareQuestionsStayInEvidenceWithoutBeingPromotedAsAnswers():Unit=runBlocking {
        val model=Model {error("A question alone supplies no answer")}
        val engine=ContextEngine(model,"questions",{"Public reply"},{emptyList()},
            {ContextEvidenceScope(ContextTextObservation.COMMENT)},readerBrief=true)
        val question=finding("q","Why do these idiots call this safe?","question")
        val result=engine.summarizeResult(listOf(question))
        assertEquals("",result.text);assertEquals(0,model.selections);assertEquals(question.text,result.findings.single().text)
    }
    @Test fun readerCanRejectANonemptyPitchWithoutTheLedgerRequiringAForcedBrief():Unit=runBlocking {
        val model=Model {error("No selection before source processing")}
        var reviews=0
        val rejecting=object:ContextModel by model {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {reviews++;return if(JsonParser.parseString(user).asJsonObject.has("passages"))"""{"keep":[]}"""else """{"status":"no_signal","findings":[]}"""}
        }
        for(kind in listOf("post","article")) {
            val engine=ContextEngine(rejecting,readerBrief=true)
            val documents=listOf(ContextDocument("pitch","1","We are the greatest. Watch now!",kind=kind))
            val result=engine.process(documents)
            val before=reviews;engine.process(documents,result.findings,result.outcomes);assertEquals(before,reviews)
            assertTrue(result.findings.isEmpty());assertEquals(ContextUnitStatus.NO_SIGNAL,result.outcomes.single().status)
        }
    }
    @Test fun microSelectionUsesCountedGroupsAndDoesNotGiveTheModelTools():Unit=runBlocking {
        val base=Model {user->gson.toJson(mapOf("test_keep" to JsonParser.parseString(user).asJsonObject["items"].asJsonArray.map {it.asJsonObject["id"].asInt}))}
        val model=object:ContextModel by base {
            override val contextTokens=2048
            override val processingPlan=ContextProcessingPlan.SINGLE.copy(extractiveFindings=true,summaryGroupLimit=4)
            override suspend fun count(system:String,user:String)=(system.length+user.length+3)/4+64
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                assertTrue(count(system,user)+maxTokens+32<=contextTokens)
                val input=JsonParser.parseString(user).asJsonObject
                if(input.has("findings")) {
                    val values=Regex("[0-9]+").findAll(input["findings"].asJsonArray.joinToString(" "){it.asJsonObject["text"].asString}).map {it.value}.distinct().toList()
                    return gson.toJson(mapOf("text" to "Recorded connector measurements: "+values.joinToString(", ")+"."))
                }
                return base.generate(system,user,maxTokens)
            }
        }
        val sources=(1..8).map {finding("s$it","The measured connector resistance in test $it is ${it+10} ohms. "+"Conditions were recorded. ".repeat(15))}
        val result=ContextEngine(model,readerBrief=true).summarizeResult(sources)
        assertTrue(base.selections>1);assertTrue(result.receipts.any {it.level==0})
        assertEquals(sources.map {it.text}.toSet(),result.findings.map {it.text}.toSet())
    }
}
