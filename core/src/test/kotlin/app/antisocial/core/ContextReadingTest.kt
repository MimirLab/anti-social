package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextReadingTest {
    private val spanish="La batería es de 56 Wh, no de 60 Wh."
    private val english="The battery is 56 Wh, not 60 Wh."
    private fun view(source:String=spanish,text:String=english,recipe:String="translation-test-v1")=ContextReadingText(ContextReadingText.digest(source),text,
        listOf(ContextReadingSpan(0,source.length,0,text.length,"es",true)),recipe)
    private fun document(text:String=spanish)=ContextDocument("source","revision",text,author="Ana",kind="comment")
    @Test fun failedShortPostReviewRetainsItsEntirePreparedSourceAndResumesWithoutAnotherCall()=runBlocking {
        val cases=listOf(
            document("The replacement is 65 Wh. The workshop opens Friday.").copy(kind="post") to ContextReadingText.identity("The replacement is 65 Wh. The workshop opens Friday.","fixture"),
            document().copy(kind="post") to view(),
            document("Which replacement is compatible?").copy(kind="post") to ContextReadingText.identity("Which replacement is compatible?","fixture")
        )
        for((doc,reading) in cases)for(answer in listOf("not valid JSON","""{"status":"no_signal","findings":[]}""","""{"status":"signal","status":"no_signal","findings":[]}""")) {
            var calls=0
            val model=object:ContextModel {
                override suspend fun count(system:String,user:String)=128
                override suspend fun generate(system:String,user:String,maxTokens:Int):String {calls++;return answer}
            }
            val engine=ContextEngine(model);val prepared=mapOf(doc.id to ContextReadingInput(reading))
            val result=engine.process(listOf(doc),readings=prepared);val finding=result.findings.single()
            assertEquals(reading.text,finding.text);assertEquals(doc.text,finding.quotes[doc.id]);assertEquals(1,calls)
            if(reading.spans.any {it.translated})assertEquals(reading.text,finding.readingQuotes!![doc.id])
            if(doc.text.endsWith('?'))assertEquals("question",finding.kind)
            val resumed=engine.process(listOf(doc),existing=result.findings,completed=result.outcomes,readings=prepared)
            assertEquals(result.outcomes,resumed.outcomes);assertEquals(1,calls)
        }
    }
    @Test fun supportedPostFallbackCannotCompleteCommentsLongOrUnconfirmedOrPartialSources():Unit=runBlocking {
        val invalid=object:ContextModel {
            override suspend fun count(system:String,user:String)=128
            override suspend fun generate(system:String,user:String,maxTokens:Int)="not valid JSON"
        }
        val text="The repair starts Friday."
        val source=document(text).copy(kind="post")
        val cases=listOf(source.copy(kind="comment") to ContextReadingText.identity(text,"fixture"),
            source to ContextReadingText.identity(text,"fixture",languageUnconfirmed=true),
            source.copy(metadata=mapOf("truncated" to "true")) to ContextReadingText.identity(text,"fixture"),
            source.copy(text=text.repeat(30)) to ContextReadingText.identity(text.repeat(30),"fixture"))
        for((doc,reading) in cases)assertThrows(ContextProcessingException::class.java){runBlocking {ContextEngine(invalid).process(listOf(doc),readings=mapOf(doc.id to ContextReadingInput(reading)))}}
        assertThrows(ContextProcessingException::class.java){runBlocking {ContextEngine(invalid).process(listOf(source))}}
        val offline=object:ContextModel by invalid {override suspend fun generate(system:String,user:String,maxTokens:Int):String=throw java.io.IOException("transport failed")}
        assertThrows(java.io.IOException::class.java){runBlocking {ContextEngine(offline).process(listOf(source),readings=mapOf(source.id to ContextReadingInput(ContextReadingText.identity(text,"fixture"))))}}
    }
    private class Model:ContextModel {
        var generated=0;val inputs=mutableListOf<String>();val retrieval=mutableListOf<String>()
        override suspend fun count(system:String,user:String):Int {inputs+=user;return (system.length+user.length)/4}
        override suspend fun selectKnown(text:String,findings:List<ContextFinding>):List<ContextFinding>? {retrieval+=text;return null}
        override suspend fun generate(system:String,user:String,maxTokens:Int):String {
            inputs+=user;generated++
            val json=JsonParser.parseString(user).asJsonObject
            if(json.has("findings"))return """{"text":"The battery is 56 Wh, not 60 Wh. The workshop opens Friday."}"""
            val quote=json["text"].asString
            return JsonObject().apply {addProperty("status","signal");add("findings",com.google.gson.JsonArray().apply {add(JsonObject().apply {addProperty("text",quote);addProperty("kind","warning");addProperty("quote",quote)})})}.toString()
        }
    }

    @Test fun translatedEvidenceReturnsOriginalQuotesAndOffsetsButOnlyEnglishReachesTheModel()=runBlocking {
        val model=Model();val doc=document();val result=ContextEngine(model).process(listOf(doc),readings=mapOf(doc.id to ContextReadingInput(view())))
        assertTrue(model.inputs.none {it.contains("batería")});assertEquals(english,result.findings.single().text)
        assertEquals(spanish,result.findings.single().quotes[doc.id]);assertEquals(english,result.findings.single().readingQuotes!![doc.id])
        assertEquals(0,result.outcomes.single().start);assertEquals(spanish.length,result.outcomes.single().end)
    }
    @Test fun translationReceiptsResumeWithoutGenerationAndBindTheTranslationRecipe()=runBlocking {
        val model=Model();val doc=document();val engine=ContextEngine(model)
        val first=engine.process(listOf(doc),readings=mapOf(doc.id to ContextReadingInput(view())))
        val calls=model.generated
        assertEquals(first.outcomes,engine.process(listOf(doc),first.findings,first.outcomes,readings=mapOf(doc.id to ContextReadingInput(view()))).outcomes)
        assertEquals(calls,model.generated)
        engine.process(listOf(doc),completed=first.outcomes,readings=mapOf(doc.id to ContextReadingInput(view(recipe="translation-test-v2"))))
        assertEquals(calls+1,model.generated)
    }
    @Test fun initialQuoteCapitalizationIsRecoveredFromOneExactSourcePassage()=runBlocking {
        val doc=document();val base=Model()
        val model=object:ContextModel by base {
            override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val result=JsonParser.parseString(base.generate(system,user,maxTokens)).asJsonObject
                val finding=result.getAsJsonArray("findings")[0].asJsonObject
                finding.addProperty("quote",finding["quote"].asString.replaceFirstChar {it.lowercaseChar()})
                return result.toString()
            }
        }
        val result=ContextEngine(model).process(listOf(doc),readings=mapOf(doc.id to ContextReadingInput(view())))
        assertEquals(spanish,result.findings.single().quotes[doc.id]);assertEquals(english,result.findings.single().readingQuotes!![doc.id])
    }
    @Test fun quoteCapitalizationRecoveryCannotChangeWordsNumbersPunctuationOrChooseAnAmbiguousPassage()=runBlocking {
        val initial=english.replaceFirstChar {it.lowercaseChar()}
        for(quote in listOf(english.replace("56","60"),english.replace("battery","batteries"),english.dropLast(1)+"!",english.lowercase(),initial)) {
            val base=Model();val model=object:ContextModel by base {override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                val json=JsonParser.parseString(base.generate(system,user,maxTokens)).asJsonObject
                json.getAsJsonArray("findings")[0].asJsonObject.addProperty("quote",quote)
                return json.toString()
            }}
            val source=if(quote==initial)"$english $english" else english
            try {ContextEngine(model).process(listOf(document(source)));fail("Altered or ambiguous evidence must remain pending")}catch(_:ContextProcessingException){}
        }
    }
    @Test fun compactionReadsEnglishEvidenceWhileOriginalQuotesRemainAvailable()=runBlocking {
        val model=Model();val first=ContextFinding("one",english,"warning",listOf("one"),mapOf("one" to spanish),mapOf("one" to english))
        val second=ContextFinding("two","The workshop opens Friday.","context",listOf("two"),mapOf("two" to "El taller abre el viernes."),mapOf("two" to "The workshop opens Friday."))
        val summary=ContextEngine(model).summarizeResult(listOf(first,second))
        assertTrue(model.inputs.none {it.contains("batería")||it.contains("El taller")})
        assertEquals(spanish,summary.findings.first().quotes["one"])
    }
    @Test fun identityAndTranslatedRangesCoverTheExactSourceWithoutSplittingUnicode() {
        val source="🙂\n$spanish\nEnd.";val reading="🙂\n$english\nEnd."
        val a="🙂\n".length;val b=a+spanish.length;val c=a+english.length
        val input=ContextReadingText(ContextReadingText.digest(source),reading,listOf(ContextReadingSpan(0,a,0,a,"zxx",false),ContextReadingSpan(a,b,a,c,"es",true),ContextReadingSpan(b,source.length,c,reading.length,"en",false)),"test")
        input.validate(source);assertEquals(spanish,input.sourceQuote(source,"56 Wh",a,c));assertEquals(a to b,input.sourceRange(a+1,c-1))
        assertThrows(IllegalArgumentException::class.java){input.sourceRange(1,2)}
        assertThrows(IllegalArgumentException::class.java){input.copy(spans=input.spans.drop(1)).validate(source)}
        assertThrows(IllegalArgumentException::class.java){input.validate(source+"changed")}
    }
    @Test fun ambiguousTranslatedWordsCiteEveryMatchingOriginalPassage() {
        val original="Primero. Después.";val reading="Same. Same."
        val input=ContextReadingText(ContextReadingText.digest(original),reading,listOf(ContextReadingSpan(0,9,0,6,"es",true),ContextReadingSpan(9,original.length,6,reading.length,"es",true)),"test")
        input.validate(original);assertEquals(original,input.sourceQuote(original,"Same",0,reading.length))
        assertThrows(ContextProcessingException::class.java){input.sourceQuote(original,"invented",0,reading.length)}
    }
    private fun preparer(detect:suspend (String)->List<LanguagePrediction> = {text->listOf(LanguagePrediction(if(text.contains("batería")||text.contains("Sirve"))"es"else "en",.99))},
        translate:suspend (String,String)->ReadingTranslation = {_,html->ReadingTranslation(html.replace("La batería es de","The battery is").replace(", no de",", not"),"offline-test-pack")},
        supported:(String)->Boolean={it=="es"})=EnglishReadingPreparer(detect,translate,supported)

    @Test fun bestEffortKeepsASuccessfulTranslationBesideAnUncertainFragment()=runBlocking {
        val raw="$spanish\n\nINDETERMINATE label.\n\nThe west gate is closed."
        val prepare=preparer(detect={text->when {
            text.contains("INDETERMINATE")->listOf(LanguagePrediction("fr",.28),LanguagePrediction("en",.21))
            text.contains("batería")->listOf(LanguagePrediction("es",.99))
            else->listOf(LanguagePrediction("en",.99))
        }})
        assertThrows(SourceLanguageUnavailable::class.java){runBlocking {prepare.prepare(raw)}}
        val reading=prepare.prepareBestEffort(raw)
        reading.validate(raw);assertTrue(reading.languageUnconfirmed)
        assertTrue(reading.text.contains(english));assertTrue(reading.text.contains("INDETERMINATE label."));assertTrue(reading.text.contains("The west gate is closed."))
        assertTrue(reading.spans.any {it.translated});assertTrue(reading.spans.any {it.language=="und"&&!it.translated})
        assertEquals(raw,reading.sourceQuote(raw,reading.text,0,reading.text.length))
    }

    @Test fun bestEffortUsesTheOriginalWhenALanguageWorkerOrTranslationFails()=runBlocking {
        val raw="The original connector is still working at 12 V."
        val failed=preparer(detect={throw SourceLanguageUnavailable(null,"Detector unavailable")}).prepareBestEffort(raw)
        assertEquals(raw,failed.text);assertTrue(failed.languageUnconfirmed);failed.validate(raw)
        val changed=preparer(translate={_,html->ReadingTranslation(html.replace("56 Wh","58 Wh"),"bad-pack")}).prepareBestEffort(spanish)
        assertEquals(spanish,changed.text);assertTrue(changed.languageUnconfirmed);assertTrue(changed.spans.none {it.translated})
        assertNotEquals(ContextReadingText.identity(raw,failed.recipe).fingerprint,failed.fingerprint)
        val doc=document(raw);val model=Model()
        val result=ContextEngine(model).process(listOf(doc),readings=mapOf(doc.id to ContextReadingInput(failed)))
        assertEquals(raw,result.findings.single().quotes[doc.id]);assertTrue(model.inputs.any {it.contains("source_language_unconfirmed")})
    }

    @Test fun knownDecoratedReactionsAndNeutralSymbolsNeedNoLanguageDetectorOrTranslator()=runBlocking {
        val prepare=preparer(detect={error("A known neutral expression needs no language guess")},translate={_,_->error("A known neutral expression needs no translation")})
        for(source in listOf("😂😂 Amazing! 👏👏","👍🏽 Thanks for sharing! 🙌🏿","😍 Love this! ❤️","lol","+1","⬅️","🇺🇸","1️⃣")) {
            val result=prepare.prepare(source)
            result.validate(source);assertEquals(source,result.text);assertTrue(result.spans.none {it.translated})
        }
    }

    @Test fun decorationsDoNotExemptTheSubstantiveOrForeignWordsBesideThem()=runBlocking {
        var detections=0
        val prepare=preparer(detect={text->detections++;listOf(LanguagePrediction(if(text.contains("batería"))"es"else "en",.99))})
        val fact="😂 The gate is closed at 12 V. 👏"
        assertEquals(fact,prepare.prepare(fact).text);assertTrue(detections>0)
        detections=0
        val foreign="😂 $spanish 👏"
        val reading=prepare.prepare(foreign)
        assertTrue(detections>0);assertTrue(reading.spans.any {it.translated})
        assertTrue(reading.text.contains("56 Wh, not 60 Wh"));assertEquals(foreign,reading.sourceQuote(foreign,reading.text,0,reading.text.length))
    }

    @Test fun preparationKeepsNumericValuesInlineAndCodeAndUrlsOpaqueWhileCheckingAllOfThem()=runBlocking {
        val source="${spanish.removeSuffix(".")}, según https://example.org/revised?model=2021 y `battery.reset()`."
        var markup=""
        val engine=preparer(translate={_,html->markup=html;ReadingTranslation(html.replace("La batería es de","The battery is").replace(", no de",", not").replace("según","according to").replace(" y "," and "),"offline-test-pack")})
        val result=engine.prepare(source)
        val body=org.jsoup.Jsoup.parseBodyFragment(markup)
        assertTrue(body.select("span").isEmpty());assertTrue(body.wholeText().contains("56 Wh, no de 60 Wh"))
        assertEquals(listOf("https://example.org/revised?model=2021","`battery.reset()`"),body.select("code").map {it.wholeText()})
        assertTrue(result.text.contains("56 Wh, not 60 Wh"));assertTrue(result.text.contains("https://example.org/revised?model=2021"));assertTrue(result.text.contains("`battery.reset()`"))
        assertEquals(EnglishReadingPreparer.POLICY,result.recipe.substringBefore('|'))
        assertEquals(source,result.sourceQuote(source,result.text,0,result.text.length))
    }
    @Test fun numbersAdjacentToJapaneseProseAreProtectedAndCannotBeSilentlyChanged()=runBlocking {
        val source="交換用バッテリーは56 Whで、60 Whではありません。"
        for(corrupt in listOf(false,true)) {
            val prepare=EnglishReadingPreparer(
                {text->listOf(LanguagePrediction(if(text.contains("交換"))"ja"else "en",.99))},
                {_,html->
                    val formatted=org.jsoup.Jsoup.parseBodyFragment(html)
                    assertTrue("Numeric arguments must remain part of the sentence",formatted.select("[data-antisocial]").isEmpty())
                    assertTrue(formatted.wholeText().contains("56 Wh")&&formatted.wholeText().contains("60 Wh"))
                    val translated=html.replace("交換用バッテリーは","The replacement battery is ").replace("で、",", not ").replace("ではありません。",".")
                    ReadingTranslation(if(corrupt)translated.replace("56 Wh","58 Wh")else translated,"japanese-test-pack")
                },{it=="ja"})
            if(corrupt)try {prepare.prepare(source);fail("CJK adjacency must not leave a value unprotected")}catch(_:SourceLanguageUnavailable){}
            else assertEquals("The replacement battery is 56 Wh, not 60 Wh.",prepare.prepare(source).text)
        }
    }
    @Test fun aShortJapaneseLabelCanUseItsDistinctiveScriptWithoutGuessingFromSharedHanOrOneKana()=runBlocking {
        val weak=listOf(LanguagePrediction("en",.124504),LanguagePrediction("ca",.0859483),LanguagePrediction("de",.0802881))
        val prepare=EnglishReadingPreparer({if(it.contains("instructions"))listOf(LanguagePrediction("en",.99))else weak},
            {language,_->assertEquals("ja",language);ReadingTranslation("<p>The instructions are here.</p>","japanese-test-pack")},{it=="ja"})
        assertEquals("The instructions are here.",prepare.prepare("説明書はこちら").text)
        for(ambiguous in listOf("説明書","Some Latin prose あ","中文文字あ")) {
            try {prepare.prepare(ambiguous);fail("A shared script or quoted character must not invent a language")}catch(_:SourceLanguageUnavailable){}
        }
    }
    @Test fun uncertainAndUnsupportedLanguagesNeverInvokeTheTextModelOrDisappearAsNoise()=runBlocking {
        var translated=0
        val ambiguous=preparer(detect={listOf(LanguagePrediction("es",.3),LanguagePrediction("en",.29))},translate={_,_->translated++;error("must not translate")})
        try {ambiguous.prepare(spanish);fail()}catch(_:SourceLanguageUnavailable){}
        val unsupported=preparer(detect={listOf(LanguagePrediction("sw",.99))})
        try {unsupported.prepare("Hiki ni chanzo kingine.");fail()}catch(failure:SourceLanguageUnavailable){assertEquals("sw",failure.language)}
        assertEquals(0,translated)
    }
    @Test fun anEnglishSourceHintCannotOverrideConfidentSpanishDetection()=runBlocking {
        assertEquals(english,preparer().prepare(spanish,"en").text)
    }
    @Test fun changedProtectedValuesAndUntranslatedForeignOutputStayPending()=runBlocking {
        val corrupted=preparer(translate={_,html->ReadingTranslation(html.replace("56 Wh","60 Wh"),"bad")})
        try {corrupted.prepare(spanish);fail()}catch(_:SourceLanguageUnavailable){}
        val unchanged=preparer(translate={_,html->ReadingTranslation(html,"unchanged")})
        try {unchanged.prepare(spanish);fail()}catch(_:SourceLanguageUnavailable){}
    }
    @Test fun numericOrderUnitsDuplicatesAndAdditionalValuesMustValidateBeforeAnyCheckpoint()=runBlocking {
        for(output in listOf("The battery is 60 Wh, not 56 Wh.","The battery is 56 mAh, not 60 Wh.",
            "The battery is 56 Wh, not 60 Wh, with 56 Wh measured.","The battery is 56 Wh, not 60 Wh, at 12 V.","The battery is 56 Wh.")) {
            var committed=0
            val prepare=EnglishReadingPreparer({listOf(LanguagePrediction(if(it.contains("batería"))"es"else "en",.99))},
                {_,_->ReadingTranslation("<p>$output</p>","test-pack")},{it=="es"},onTranslation={_,_,_->committed++})
            try {prepare.prepare(spanish);fail("Changed numeric evidence must stay pending: $output")}catch(_:SourceLanguageUnavailable){}
            assertEquals(0,committed)
        }
        val negative=EnglishReadingPreparer({listOf(LanguagePrediction(if(it.contains("temperatura"))"es"else "en",.99))},
            {_,_->ReadingTranslation("<p>The temperature is 5 °C.</p>","test-pack")},{it=="es"})
        try {negative.prepare("La temperatura es de −5 °C.");fail("A Unicode minus is part of the value")}catch(_:SourceLanguageUnavailable){}
    }
    @Test fun translatedCjkSentencesReceiveEnglishSpacingWhileTheirOriginalCitationRemainsExact()=runBlocking {
        val source="交換用バッテリーは56 Whです。説明書はこちら。"
        val prepare=EnglishReadingPreparer({text->listOf(LanguagePrediction(if(text.contains("交換")||text.contains("説明"))"ja"else "en",.99))},
            {_,html->ReadingTranslation(if(html.contains("交換"))"<p>The battery is 56 Wh.</p>"else "<p>The instructions are here.</p>","japanese-test-pack")},{it=="ja"})
        val reading=prepare.prepare(source)
        assertEquals("The battery is 56 Wh. The instructions are here.",reading.text)
        assertEquals(source,reading.sourceQuote(source,reading.text,0,reading.text.length))
        reading.validate(source)
    }
    @Test fun EnglishAndNeutralInputNeedNoTranslationAndUseCompactIdentityViews()=runBlocking {
        var translations=0;val prepare=preparer(translate={_,_->translations++;error("not required")})
        val source="The workshop is open.\nThanks!\nhttps://example.org/2021"
        val result=prepare.prepare(source)
        assertEquals(source,result.text);assertTrue(result.spans.none {it.translated});assertEquals(1,result.spans.size);assertEquals(0,translations)
        assertTrue(prepare.prepare("").spans.isEmpty())
    }
    @Test fun shortEnglishNeedsTheSameConfidenceAndMarginAsOtherLanguages()=runBlocking {
        val prepare=preparer(detect={listOf(LanguagePrediction("en",.515),LanguagePrediction("fr",.12))},translate={_,_->error("English must not invoke translation")})
        assertEquals("Thanks for the update 1.",prepare.prepare("Thanks for the update 1.").text)
        val ambiguous=preparer(detect={listOf(LanguagePrediction("en",.515),LanguagePrediction("es",.49))})
        try {ambiguous.prepare("A short ambiguous reply.");fail()}catch(_:SourceLanguageUnavailable){}
        val foreign=preparer(detect={text->listOf(LanguagePrediction(if(text=="No funciona.")"es"else "en",.535))},
            translate={_,_->ReadingTranslation("<p>It does not work.</p>","offline-test-pack")})
        assertEquals("It does not work.",foreign.prepare("No funciona.").text)
    }
    @Test fun knownEnglishTranslationCanUseAClearRelativeLeadWithoutWeakeningSourceDetection()=runBlocking {
        val source="Consultez le guide à https://example.org/manual ."
        val reading="Consult the guide to https://example.org/manual ."
        val english=listOf(LanguagePrediction("en",.3699),LanguagePrediction("ru",.117123),LanguagePrediction("ja",.0566456))
        val detector:suspend (String)->List<LanguagePrediction> = {if(it.contains("Consultez"))listOf(LanguagePrediction("fr",.99))else english}
        val prepare=EnglishReadingPreparer(detector,{_,html->ReadingTranslation(html.replace("Consultez le guide à","Consult the guide to"),"french-test-pack")},{it=="fr"})
        assertEquals(reading,prepare.prepare(source).text)
        try {prepare.prepare(reading);fail("The weaker output check must not classify an unknown source")}catch(_:SourceLanguageUnavailable){}
    }
    @Test fun expectedEnglishCannotOverrideForeignAmbiguousOrVeryWeakOutputAndCannotCheckpointIt()=runBlocking {
        for(english in listOf(
            listOf(LanguagePrediction("es",.42),LanguagePrediction("en",.36),LanguagePrediction("fr",.15)),
            listOf(LanguagePrediction("en",.3699),LanguagePrediction("es",.30)),
            listOf(LanguagePrediction("en",.26),LanguagePrediction("ru",.05))
        )) {
            var committed=0
            val prepare=EnglishReadingPreparer({if(it.contains("Consultez"))listOf(LanguagePrediction("fr",.99))else english},
                {_,_->ReadingTranslation("<p>Consult the guide to.</p>","french-test-pack")},{it=="fr"},onTranslation={_,_,_->committed++})
            try {prepare.prepare("Consultez le guide.");fail("Unconfirmed output must remain pending")}catch(_:SourceLanguageUnavailable){}
            assertEquals(0,committed)
        }
    }
    @Test fun interruptionCheckpointsAValidatedPassageBeforeStoppingAndResumeDoesNotTranslateItAgain()=runBlocking {
        val source="$spanish\n$spanish";val chunks=mutableMapOf<String,ReadingTranslation>();var translations=0;var stopped=false
        fun preparing(check:()->Unit)=EnglishReadingPreparer(
            detect={text->listOf(LanguagePrediction(if(text.contains("batería"))"es"else "en",.99))},
            translate={_,html->chunks[html] ?: ReadingTranslation(html.replace("La batería es de","The battery is").replace(", no de",", not"),"offline-test-pack").also {translations++}},
            supported={it=="es"},checkWork=check,onTranslation={_,html,result->chunks[html]=result;stopped=true})
        try {preparing {if(stopped)throw kotlinx.coroutines.CancellationException("allowance reached")}.prepare(source);fail()}catch(_:kotlinx.coroutines.CancellationException){}
        assertEquals(1,chunks.size);assertEquals(1,translations)
        val reading=preparing {}.prepare(source)
        assertEquals("$english\n$english",reading.text);assertEquals(1,translations);reading.validate(source)
    }
}
