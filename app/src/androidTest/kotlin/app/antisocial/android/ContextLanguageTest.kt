/* AntiSocial, GPL-3.0-or-later. Real offline language tools on an isolated emulator. */
package app.antisocial.android

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContextLanguageTest {
    private fun isolated() {assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")}
    @Test fun installedLatinCyrillicArabicAndJapanesePacksTranslateOfflineAndPreserveProtectedEvidence():Unit=runBlocking {
        isolated();assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveLanguageDownloads")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val aiPrefs=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).all.toMap()
        val options=repo.languages.options.value;val policy=repo.gateway.currentPolicy()
        val sources=linkedMapOf(
            "de" to "Die Ersatzbatterie hat 56 Wh, nicht 60 Wh. Die Anleitung steht unter https://example.org/manual .",
            "fr" to "La batterie de remplacement a une capacité de 56 Wh, et non de 60 Wh. Consultez le guide à https://example.org/manual .",
            "uk" to "Запасний акумулятор має ємність 56 Wh, а не 60 Wh. Інструкція доступна за адресою https://example.org/manual .",
            "ar" to "البطارية البديلة سعتها 56 Wh، وليست 60 Wh. الدليل متاح على https://example.org/manual .",
            "ja" to "交換用バッテリーは56 Whで、60 Whではありません。説明書はこちら: https://example.org/manual ."
        )
        repo.languages.refresh();val alreadyInstalled=repo.languages.state.value.installed.toSet()
        try {
            repo.ai.enabled(true);repo.languages.update(options.copy(enabled=true,automatic=false));repo.gateway.configure(NetworkPolicy())
            for(language in sources.keys)withTimeout(240_000){repo.languages.install(language,true)}
            repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
            val checked=repo.gateway.protectionStats().checked
            val session=repo.languages.session();val readings=linkedMapOf<String,ContextReadingText>()
            for((language,source) in sources) {
                session.beginPreparation()
                val reading=try {withTimeout(90_000){session.preparer.prepare(source,language)}}catch(failure:Exception) {
                    val rawClient=ContextMediaClient(context,LanguageService::class.java)
                    try {
                        val config=File(context.filesDir,"languages/runtime").listFiles()!!.single {it.name.startsWith("$language-en-")&&it.extension=="yml"}
                        val html=org.jsoup.nodes.Element("p").text(source).outerHtml()
                        val response=rawClient.request(LanguageIpc.TRANSLATE,Bundle().apply {putString("path",config.path);putByteArray("text",html.toByteArray(Charsets.UTF_8))})
                        val raw=response.getByteArray("result")!!.toString(Charsets.UTF_8)
                        val text=org.jsoup.Jsoup.parseBodyFragment(raw).wholeText()
                        val guesses=(listOf(text)+text.split(Regex("(?<=[.!?])\\s+"))).distinct().associateWith {repo.languages.detect(it)}
                        // Inspect the exact private formatter only in this
                        // development diagnostic; production exposes no logging
                        // hook and persists no extra source-text trace.
                        val splitter=EnglishReadingPreparer::class.java.getDeclaredMethod("passages",String::class.java).apply {isAccessible=true}
                        val formatter=EnglishReadingPreparer::class.java.getDeclaredMethod("markup",String::class.java).apply {isAccessible=true}
                        val sampler=EnglishReadingPreparer::class.java.getDeclaredMethod("sample",String::class.java).apply {isAccessible=true}
                        @Suppress("UNCHECKED_CAST") val ranges=splitter.invoke(session.preparer,source) as List<Pair<Int,Int>>
                        val protected=ranges.map {range->
                            val passage=source.substring(range.first,range.second).trim()
                            @Suppress("UNCHECKED_CAST") val formatted=formatter.invoke(session.preparer,passage) as Pair<String,List<String>>
                            val translated=rawClient.request(LanguageIpc.TRANSLATE,Bundle().apply {putString("path",config.path);putByteArray("text",formatted.first.toByteArray(Charsets.UTF_8))}).getByteArray("result")!!.toString(Charsets.UTF_8)
                            val probe=sampler.invoke(session.preparer,org.jsoup.Jsoup.parseBodyFragment(translated).wholeText()) as String
                            mapOf("html" to formatted.first,"translation" to translated,"probe" to probe,"predictions" to repo.languages.detect(probe))
                        }
                        File(context.filesDir,"language-multilingual-attempt.json").writeText(Gson().toJson(mapOf("language" to language,"source" to source,"translation" to raw,"predictions" to guesses,"protected" to protected,"failure" to failure.javaClass.simpleName)))
                    }finally {rawClient.release()}
                    throw failure
                }finally {session.finishPreparation()}
                readings[language]=reading
                // Save every response before assertions so a rejected language
                // retains its actual output instead of just a generic failure.
                File(context.filesDir,"language-multilingual-evidence.json").writeText(Gson().toJson(mapOf("sources" to sources,"readings" to readings,"offline" to true)))
                assertTrue("$language must actually use its translation model",reading.spans.any {it.translated})
                assertTrue("$language lost a protected value",reading.text.contains("56 Wh")&&reading.text.contains("60 Wh"))
                assertTrue("$language lost the source URL",reading.text.contains("https://example.org/manual"))
                assertEquals("$language must retain its complete original citation",source,reading.sourceQuote(source,reading.text,0,reading.text.length))
                assertEquals("Translation must make no network request",checked,repo.gateway.protectionStats().checked)
            }
        }finally {
            repo.languages.release()
            for(language in sources.keys-alreadyInstalled)repo.languages.remove(language)
            repo.languages.update(options);repo.ai.enabled(false)
            val editor=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).edit().clear()
            for((key,value) in aiPrefs)when(value){is String->editor.putString(key,value);is Boolean->editor.putBoolean(key,value);is Int->editor.putInt(key,value);is Long->editor.putLong(key,value)}
            editor.commit();repo.ai.refresh();repo.gateway.configure(policy)
        }
    }
    @Test fun bundledLanguageDetectorRecognizesSpanishEnglishArabicAndJapaneseWithoutANetworkRequest():Unit=runBlocking {
        isolated();val context=InstrumentationRegistry.getInstrumentation().targetContext;val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val policy=repo.gateway.currentPolicy();repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        try {
            val before=repo.gateway.protectionStats().checked
            val samples=mapOf("es" to "¿Sirve también para el modelo de 2021?","en" to "The workshop announced a replacement laptop battery.","ar" to "هل يوجد مدخل مناسب للكراسي المتحركة؟","ja" to "2021年モデルにも使えますか？")
            val results=samples.mapValues {repo.languages.detect(it.value)}
            for((language,result) in results){assertEquals(language,result.first().language);assertTrue(result.first().confidence>.8)}
            val short=mapOf("Thanks for the update 1." to "en","No funciona." to "es","Works." to "en")
            for((text,language) in short)assertEquals(language,repo.languages.detect(text).first().language)
            assertEquals(before,repo.gateway.protectionStats().checked)
            File(context.filesDir,"language-detector-evidence.json").writeText(Gson().toJson(results))
        } finally {repo.languages.release();repo.gateway.configure(policy)}
    }
    @Test fun offlineReadingResumesValidatedPassagesAndReusesItsCompleteCacheWithoutTranslation():Unit=runBlocking {
        isolated();assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLanguageModels")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val aiPrefs=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).all.toMap();val options=repo.languages.options.value;val policy=repo.gateway.currentPolicy()
        val database="context-reading-cache-${UUID.randomUUID()}.db";val store=ContextStore(context,database)
        try {
            repo.ai.enabled(true);repo.languages.update(options.copy(enabled=true,automatic=false));repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
            val source="La batería es de 56 Wh, no de 60 Wh.\n¿Sirve también para el modelo de 2021?"
            val post=Post("reading-cache",Platform.X,"https://x.com/language_fixture/status/991001","Workshop","The battery repair.")
            val owner=store.resolve(ContextSnapshot(listOf(post),post.body,"","reading-cache")).id
            val doc=ContextDocument("spanish","1",source,kind="comment");store.ingest(owner,listOf(doc))
            val before=repo.gateway.protectionStats().checked
            suspend fun interrupted() {
                val session=repo.languages.session();session.beginPreparation();var checks=0
                try {session.reading(store,owner,doc,"body",source,checkWork={if(++checks==2)throw CancellationException("bounded reading stopped")});fail("The reading must stop after its first validated passage")}
                catch(_:CancellationException){assertEquals(2,checks)}finally {session.finishPreparation()}
            }
            interrupted();assertNull(store.reading(owner,doc,"body",source))
            // The second attempt can reach its checkpoint with translation
            // disabled only by reusing the validated first passage.
            repo.languages.update(options.copy(enabled=false,automatic=false));interrupted()
            repo.languages.update(options.copy(enabled=true,automatic=false))
            val session=repo.languages.session();session.beginPreparation()
            val view=try {session.reading(store,owner,doc,"body",source)}finally {session.finishPreparation()}
            assertTrue(view.text.contains("56 Wh"));assertTrue(view.text.contains("60 Wh"));assertTrue(view.text.contains("2021"))
            assertEquals(source,view.sourceQuote(source,view.text,0,view.text.length));assertEquals(EnglishReadingPreparer.POLICY,view.recipe.substringBefore('|'))
            store.close();val reopened=ContextStore(context,database)
            try {
                assertEquals(view,reopened.reading(owner,doc,"body",source))
                repo.languages.update(options.copy(enabled=false,automatic=false))
                assertEquals(view,repo.languages.session().reading(reopened,owner,doc,"body",source))
                assertEquals(before,repo.gateway.protectionStats().checked)
                File(context.filesDir,"language-cache-evidence.json").writeText(Gson().toJson(mapOf("interruptedPassageReused" to true,"reopenedCompleteCacheReused" to true,"translationDisabledOnReuse" to true,"offline" to true,"reading" to view)))
            } finally {reopened.close()}
        } finally {
            repo.languages.release();runCatching {store.close()};context.deleteDatabase(database);repo.languages.update(options);repo.ai.enabled(false)
            val editor=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).edit().clear()
            for((key,value) in aiPrefs)when(value){is String->editor.putString(key,value);is Boolean->editor.putBoolean(key,value);is Int->editor.putInt(key,value);is Long->editor.putLong(key,value)}
            editor.commit();repo.ai.refresh();repo.gateway.configure(policy)
        }
    }
    @Test fun offlineSpanishIsPreparedBeforeBonsaiAndReceiptsAndTranslationsResume():Unit=runBlocking {
        isolated();val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("antisocialLanguageModels")=="true"&&args.getString("antisocialLocalAiModels")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val aiPrefs=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).all.toMap();val options=repo.languages.options.value;val policy=repo.gateway.currentPolicy()
        val database="context-language-${UUID.randomUUID()}.db";val store=ContextStore(context,database);val client=LocalAiClient(context)
        val modelName=args.getString("antisocialContextPhoneModel") ?: "Ternary-Bonsai-2-2B-PQ2_0.gguf"
        require(modelName in setOf("Ternary-Bonsai-2-2B-PQ2_0.gguf","Ternary-Bonsai-2-4B-PQ2_0.gguf"))
        val modelFile=File(context.filesDir,"local-ai/test/$modelName");assumeTrue(modelFile.isFile)
        val spanish="En realidad, la batería de repuesto es de 56 Wh, no de 60 Wh."
        val question="¿Sirve también para el modelo de 2021?"
        try {
            repo.ai.enabled(true);repo.languages.update(options.copy(enabled=true,automatic=false));repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
            val post=Post("language-fixture",Platform.X,"https://x.com/language_fixture/status/991000","Workshop","The workshop announced a battery repair.")
            val owner=store.resolve(ContextSnapshot(listOf(post),post.body,"","language-v1")).id
            val documents=listOf(ContextDocument("correction","1",spanish,author="Ana",kind="comment"),ContextDocument("question","1",question,author="Luis",kind="comment"))
            store.ingest(owner,documents)
            val before=repo.gateway.protectionStats().checked;val session=repo.languages.session();session.beginPreparation()
            val started=System.currentTimeMillis()
            val views=documents.associate {doc->doc.id to ContextReadingInput(session.reading(store,owner,doc,"body",doc.text))}
            session.finishPreparation()
            val translationMs=System.currentTimeMillis()-started
            assertTrue(views["correction"]!!.body.text.contains("56 Wh"));assertTrue(views["correction"]!!.body.text.contains("60 Wh"))
            assertFalse(views.values.any {it.body.text.contains("batería")||it.body.text.contains("¿")})
            assertEquals(before,repo.gateway.protectionStats().checked)
            assertEquals(views["correction"]!!.body,store.reading(owner,documents[0],"body",spanish))
            repo.languages.update(options.copy(enabled=false,automatic=false))
            try {assertEquals(views["correction"]!!.body,session.reading(store,owner,documents[0],"body",spanish))}
            finally {repo.languages.update(options.copy(enabled=true,automatic=false))}
            // HTML requests supply alignment even if a file disables it. A
            // broken model must fail without poisoning that same worker.
            val languageClient=ContextMediaClient(context,LanguageService::class.java)
            val runtime=File(context.filesDir,"languages/runtime")
            val config=runtime.listFiles()!!.single {it.name.startsWith("es-en-")&&it.extension=="yml"}
            val invalid=File(runtime,"test-no-alignment.yml")
            val missing=File(runtime,"test-missing-model.yml")
            try {
                fun languageInput(path:File,text:String)=Bundle().apply {putString("path",path.path);putByteArray("text",text.toByteArray(Charsets.UTF_8))}
                val pid=languageClient.request(LanguageIpc.DETECT,languageInput(File(runtime,"lid.176.ftz"),"A readable English sentence.")).getInt("workerPid")
                invalid.writeText(config.readText().replace("alignment: soft","alignment: none"))
                val aligned=withTimeout(60_000){languageClient.request(LanguageIpc.TRANSLATE,languageInput(invalid,"<p>La batería funciona.</p>"))}
                assertEquals(pid,aligned.getInt("workerPid"));assertTrue(aligned.getByteArray("result")!!.toString(Charsets.UTF_8).contains("battery",ignoreCase=true))
                missing.writeText(config.readText().replaceFirst(Regex("(?m)^ - [^\\r\\n]+")," - ${File(runtime,"missing-language-model.bin").path}"))
                try {withTimeout(60_000){languageClient.request(LanguageIpc.TRANSLATE,languageInput(missing,"<p>La batería funciona.</p>"))};fail("A missing model must be rejected")}
                catch(failure:IllegalStateException){assertTrue(failure.message.orEmpty().contains("model",ignoreCase=true))}
                val recovered=withTimeout(60_000){languageClient.request(LanguageIpc.TRANSLATE,languageInput(config,"<p>La batería funciona.</p>"))}
                assertEquals(pid,recovered.getInt("workerPid"));assertTrue(recovered.getByteArray("result")!!.toString(Charsets.UTF_8).contains("battery",ignoreCase=true))
            } finally {invalid.delete();missing.delete();languageClient.release()}
            fun input(system:String,user:String)=Bundle().apply {putString("kind","text");putString("path",modelFile.path);putString("backend","cpu");putString("system",system);putString("user",user)}
            val diagnostics=client.request(AiIpc.PREPARE,input("","")).getString("diagnostics")!!
            val capacity=ContextCapabilityParser.nativeRuntime(JsonParser.parseString(diagnostics).asJsonObject,modelName)
            var calls=0;val replies=mutableListOf<String>();val prompts=mutableListOf<Map<String,Any>>()
            fun attempt(outcomes:List<ContextUnitOutcome> = emptyList()) {
                File(context.filesDir,"language-phone-attempt.json").writeText(Gson().toJson(mapOf("model" to modelName,"views" to views,"outcomes" to outcomes,"responses" to replies,"calls" to prompts)))
            }
            attempt()
            val model=object:ContextModel {
                override val contextTokens=capacity.contextTokens
                override val maxOutputTokens=512
                override val shortDecisionTokens=32
                override val processingPlan=ContextProcessingRouter.select(capacity,true,512)
                override suspend fun count(system:String,user:String):Int {
                    assertFalse("Foreign prose reached the text worker",user.contains("batería")||user.contains("¿"))
                    return client.request(AiIpc.COUNT,input(system,user)).getInt("tokenCount")
                }
                override suspend fun generate(system:String,user:String,maxTokens:Int):String {
                fixtureBriefAdmission(user)?.let {return it}
                    count(system,user);calls++
                    val response=client.request(AiIpc.TEXT,input(system,user).apply {putInt("maxTokens",maxTokens);putBoolean("jsonMode",true);putBoolean("instructionCache",ContextEngine.isReusableInstruction(system))})
                    return response.getString("text")!!.also {replies+=it;prompts+=mapOf("system" to system,"user" to user,"maxTokens" to maxTokens,"output" to it);attempt()}
                }
            }
            val engine=ContextEngine(model,"translated-phone-fixture")
            val backgrounds=documents.associate {it.id to ContextBackground(ContextBackgroundSource(post.body))}
            val result=withTimeout(360_000){engine.process(documents,backgrounds=backgrounds,readings=views){store.commitUnit(owner,it)}}
            attempt(result.outcomes)
            assertTrue(result.findings.any {it.text.contains("56")&&it.quotes["correction"]?.let(spanish::contains)==true})
            assertTrue(result.findings.any {it.kind=="question"&&it.quotes["question"]==question&&it.text==views["question"]!!.body.text})
            val generated=calls
            val resumed=engine.process(documents,result.findings,result.outcomes,backgrounds=backgrounds,readings=views)
            assertEquals(generated,calls);assertEquals(result.outcomes,resumed.outcomes)
            assertEquals(views["correction"]!!.body,session.reading(store,owner,documents[0],"body",spanish))
            File(context.filesDir,"language-phone-evidence.json").writeText(Gson().toJson(mapOf("model" to modelName,"translationMs" to translationMs,"calls" to calls,"offline" to true,"findings" to result.findings,"originalQuestionsPreserved" to true,"receiptsReused" to true)))
        }finally {
            client.release();repo.languages.release();store.close();context.deleteDatabase(database);repo.languages.update(options);repo.ai.enabled(false)
            val editor=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE).edit().clear()
            for((key,value) in aiPrefs)when(value){is String->editor.putString(key,value);is Boolean->editor.putBoolean(key,value);is Int->editor.putInt(key,value);is Long->editor.putLong(key,value)}
            editor.commit();repo.ai.refresh();repo.gateway.configure(policy)
        }
    }
}
