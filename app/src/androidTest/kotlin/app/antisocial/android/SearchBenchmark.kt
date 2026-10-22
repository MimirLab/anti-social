package app.antisocial.android

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.flow.first

/** Opt-in measurements, not an assertion that an external engine is reliable. */
@RunWith(AndroidJUnit4::class)
class SearchBenchmark {
    @Test fun comparePreparedSearch(): Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialSearchBenchmark")=="true")
        val repo=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo;repo.awaitReady()
        withContext(Dispatchers.Main){repo.applyNetworkSettings()};withTimeout(120_000){repo.tor.state.first {it.ready}}
        repo.cancelSearchPreparation()
        val records=mutableListOf<Map<String,Any?>>()
        val dir=requireNotNull(repo.context.getExternalFilesDir("validation"))
        val connects=java.util.concurrent.atomic.AtomicInteger()
        repo.gateway.onNetworkEvent={if(it.host=="html.duckduckgo.com"&&it.stage=="connect")connects.incrementAndGet()}
        try {
            for((round,word) in listOf("elon","NASA").withIndex())for(prepared in if(round==0)listOf(false,true)else listOf(true,false)) {
                withContext(Dispatchers.IO){repo.gateway.client(NetworkRoute.TOR).connectionPool.evictAll()}
                val preparationStart=System.nanoTime()
                var preparationStatus: Int?=null
                if(prepared)withContext(Dispatchers.IO) {
                    preparationStatus=repo.gateway.preconnect("https://html.duckduckgo.com/favicon.ico")
                }
                val preparation=(System.nanoTime()-preparationStart)/1_000_000
                connects.set(0)
                val start=System.nanoTime();val target=repo.routes.search(Platform.X,word)
                val result=try {
                    val payload=repo.gateway.text("https://html.duckduckgo.com/html/?q="+DarwinRoutes.encode("site:x.com $word"),target.canonicalUrl)
                    val received=(System.nanoTime()-start)/1_000_000
                    val page=Extraction.parse(payload,target,Source(Platform.X,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL),repo.routes)
                    mapOf("receivedMs" to received,"usableMs" to (System.nanoTime()-start)/1_000_000,"links" to page.collections.size,"connectionsDuringQuery" to connects.get())
                }catch(e: Exception){mapOf("error" to e.toString(),"ms" to (System.nanoTime()-start)/1_000_000)}
                val record=mapOf("query" to word,"prepared" to prepared,"preparationMs" to preparation,"preparationStatus" to preparationStatus)+result;records+=record
                File(dir,"search-preparation.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(records));Log.i("AntiSocialSearchBenchmark",record.toString())
            }
        }finally {repo.gateway.onNetworkEvent=null}
    }
    @Test fun capturedSearchParsing(): Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("antisocialSearchParseBenchmark")=="true")
        val repo=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo;repo.awaitReady()
        val dir=requireNotNull(repo.context.getExternalFilesDir("validation"));val label=args.getString("antisocialParseLabel") ?: "current"
        val source=Source(Platform.X,"duckduckgo","https://html.duckduckgo.com",true,SourceRole.ORIGINAL)
        val records=mutableListOf<Map<String,Any?>>()
        for((round,word) in listOf("elon","NASA","space","linux").withIndex()) {
            val text=File(dir,"duckduckgo-forms/$round-html.html").readText()
            val target=repo.routes.search(Platform.X,word);val payload=Payload(text,source.root+"/html/","text/html",200)
            try {Extraction.parse(payload,target,source,repo.routes)}catch(e: SourceFailure){records+=mapOf("query" to word,"unusableFixture" to e.message);continue}
            repeat(5){Extraction.parse(payload,target,source,repo.routes)}
            var links=0
            val times=List(30){val start=System.nanoTime();links=Extraction.parse(payload,target,source,repo.routes).collections.size;(System.nanoTime()-start)/1000}.sorted()
            records+=mapOf("query" to word,"links" to links,"p50Us" to times[15],"p95Us" to times[28])
        }
        File(dir,"search-parsing-$label.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(records));Log.i("AntiSocialSearchBenchmark","Parsing $label: $records")
    }
    @Test fun compareDuckDuckGoPost(): Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialSearchBenchmark")=="true")
        val repo=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo;repo.awaitReady()
        withContext(Dispatchers.Main){repo.applyNetworkSettings()}
        withTimeout(120_000){repo.tor.state.first {it.ready}}
        val dir=File(requireNotNull(repo.context.getExternalFilesDir("validation")),"duckduckgo-post").apply {mkdirs()}
        val records=mutableListOf<Map<String,Any?>>()
        for(word in listOf("elon","NASA"))for(form in listOf("lite","html")) {
            val root="https://$form.duckduckgo.com/$form/";val start=System.nanoTime()
            val outcome=try {
                // Use the gateway's protected client, filters and redirect policy.
                // This diagnostic is the only POST caller until an endpoint works.
                val payload=withContext(Dispatchers.IO) {
                    val request=okhttp3.Request.Builder().url(root).header("X-AntiSocial-Source",repo.routes.search(Platform.X,word).canonicalUrl).post(okhttp3.FormBody.Builder().add("q","site:x.com $word").build()).build()
                    val call=repo.gateway.client(NetworkRoute.TOR).newCall(request);call.timeout().timeout(25,java.util.concurrent.TimeUnit.SECONDS)
                    call.execute().use {response->
                        val body=requireNotNull(response.body);val bytes=java.io.ByteArrayOutputStream()
                        body.byteStream().use {input->val chunk=ByteArray(8192);while(true){val count=input.read(chunk);if(count<0)break;check(bytes.size()+count<=128*1024);bytes.write(chunk,0,count)}}
                        Payload(bytes.toString("UTF-8"),response.request.url.toString(),body.contentType()?.toString().orEmpty(),response.code)
                    }
                }
                val received=(System.nanoTime()-start)/1_000_000
                val doc=Jsoup.parse(payload.text,payload.finalUrl)
                val links=doc.select("a.result-link[href],a.result__a[href]").size
                File(dir,"$word-$form.html").writeText(payload.text)
                mapOf("receivedMs" to received,"status" to payload.status,"bytes" to payload.text.toByteArray().size,"resultLinks" to links,"textEnd" to doc.body().text().takeLast(160))
            }catch(e: Exception){mapOf("error" to e.toString(),"ms" to (System.nanoTime()-start)/1_000_000)}
            val record=mapOf("query" to word,"form" to form)+outcome;records+=record;File(dir,"measurements.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(records));Log.i("AntiSocialSearchBenchmark",record.toString())
        }
    }
    @Test fun compareDuckDuckGoForms(): Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialSearchBenchmark")=="true")
        val repo=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo
        repo.awaitReady();assumeTrue(repo.prefs.originalFallback)
        val directory=File(requireNotNull(repo.context.getExternalFilesDir("validation")),"duckduckgo-forms").apply {mkdirs()}
        val records=mutableListOf<Map<String,Any?>>()
        val gson=GsonBuilder().setPrettyPrinting().create()
        fun publish(record: Map<String,Any?>){records+=record;File(directory,"measurements.json").writeText(gson.toJson(records));Log.i("AntiSocialSearchBenchmark",record.toString())}
        try {
            withContext(Dispatchers.Main){repo.applyNetworkSettings()}
            val connectionStarted=System.nanoTime()
            withTimeout(120_000){repo.tor.state.first {it.ready}}
            publish(mapOf("phase" to "tor-ready","waitMs" to (System.nanoTime()-connectionStarted)/1_000_000))
            val exit=repo.gateway.text("https://check.torproject.org/api/ip",route=NetworkRoute.TOR)
            check(com.google.gson.JsonParser.parseString(exit.text).asJsonObject["IsTor"].asBoolean)
            for((round,word) in listOf("elon","NASA","space","linux").withIndex()) {
                val target=repo.routes.search(Platform.X,word);val query=DarwinRoutes.encode("site:x.com $word")
                val endpoints=listOf("html" to "https://html.duckduckgo.com/html/?q=$query","lite" to "https://lite.duckduckgo.com/lite/?q=$query")
                for((form,url) in if(round%2==0)endpoints else endpoints.reversed()) {
                    val started=System.nanoTime()
                    var payload: Payload?=null
                    val outcome=try {
                        payload=withTimeout(25_000){repo.gateway.text(url,target.canonicalUrl)}
                        val received=(System.nanoTime()-started)/1_000_000
                        val document=Jsoup.parse(payload.text,payload.finalUrl)
                        val challenge=document.select("form#challenge-form,form#img-form,.anomaly-modal").isNotEmpty()||document.text().contains("bots use DuckDuckGo",true)
                        val links=document.select("a.result__a[href],a.result-link[href]").mapNotNull {link->
                            val raw=link.absUrl("href");val parsed=raw.toHttpUrlOrNull()
                            val destination=if(parsed?.host=="duckduckgo.com"||parsed?.host?.endsWith(".duckduckgo.com")==true)parsed.queryParameter("uddg")else raw
                            val found=destination?.let(repo.routes::recognize)
                            found?.takeIf {it.platform==Platform.X&&(it.kind==TargetKind.POST||it.kind==TargetKind.PROFILE&&it.identity.matches(Regex("[A-Za-z0-9_]{1,15}"))&&it.identity.lowercase() !in setOf("home","search","explore","settings","messages","login","signup","i"))}?.canonicalUrl
                        }.distinct()
                        mapOf("receivedMs" to received,"usableMs" to (System.nanoTime()-started)/1_000_000,"links" to links.size,"challenge" to challenge,"decodedBytes" to payload.text.toByteArray().size,"finalUrl" to payload.finalUrl,"title" to document.title())
                    }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;mapOf("error" to "25-second timeout","ms" to (System.nanoTime()-started)/1_000_000)}
                    catch(e: Exception){mapOf("error" to e.toString(),"ms" to (System.nanoTime()-started)/1_000_000)}
                    payload?.let {File(directory,"$round-$form.html").writeText(it.text)}
                    publish(mapOf("round" to round,"query" to word,"form" to form)+outcome)
                }
            }
        }finally {repo.gateway.onNetworkEvent=null}
    }
    @Test fun enginesThroughTor()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialSearchBenchmark")=="true")
        val repo=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo
        repo.awaitReady()
        val previous=repo.prefs.tor
        val records=mutableListOf<Map<String,Any?>>()
        val file=File(requireNotNull(repo.context.getExternalFilesDir("validation")),"search-tor-benchmark.json")
        fun publish(record: Map<String,Any?>) {
            records+=record
            file.parentFile?.mkdirs()
            file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(records))
            Log.i("AntiSocialSearchBenchmark",record.toString())
        }
        try {
            withContext(Dispatchers.Main){repo.tor.enable(false)}
            delay(500)
            val started=System.nanoTime()
            withContext(Dispatchers.Main){repo.tor.enable(true)}
            withTimeout(180_000){var last=-1;while(!repo.tor.state.value.ready){val state=repo.tor.state.value;if(state.progress!=last){last=state.progress;publish(mapOf("phase" to "tor-bootstrap","ms" to (System.nanoTime()-started)/1_000_000,"state" to state))};delay(250)}}
            publish(mapOf("phase" to "tor-ready","ms" to (System.nanoTime()-started)/1_000_000))
            val exitCheck=repo.gateway.text("https://check.torproject.org/api/ip")
            check(com.google.gson.JsonParser.parseString(exitCheck.text).asJsonObject["IsTor"].asBoolean)
            publish(mapOf("phase" to "tor-exit-verified","ms" to (System.nanoTime()-started)/1_000_000))
            for((round,word) in listOf("elon","NASA").withIndex()) {
                val target=repo.routes.search(Platform.X,word)
                val query=DarwinRoutes.encode("site:x.com $word")
                val providers=listOf(
                    "bing-rss" to "https://www.bing.com/search?format=rss&q=$query",
                    "duckduckgo" to "https://html.duckduckgo.com/html/?q=$query",
                    "google" to "https://www.google.com/search?gbv=1&q=$query"
                )
                for((provider,url) in if(round==0)providers else providers.reversed()) {
                    val requestStarted=System.nanoTime()
                    val result=try {
                        val payload=withTimeout(25_000){repo.gateway.text(url,target.canonicalUrl)}
                        val received=(System.nanoTime()-requestStarted)/1_000_000
                        File(file.parentFile,"search-benchmark-$provider-$word.html").writeText(payload.text)
                        val document=Jsoup.parse(payload.text,payload.finalUrl)
                        val challenge=Regex("captcha|unusual traffic|bots use DuckDuckGo|verify you are human|enable javascript",RegexOption.IGNORE_CASE).containsMatchIn(document.text())
                        val links=if(provider=="bing-rss")PublicSearchAdapter.rss(payload.text,target,repo.routes).collections.map {it.target.canonicalUrl} else document.select("a[href]").mapNotNull {node->
                            val absolute=node.absUrl("href")
                            val parsed=absolute.toHttpUrlOrNull()
                            val destination=parsed?.queryParameter("uddg") ?: if(parsed?.encodedPath=="/url")parsed.queryParameter("q") ?: parsed.queryParameter("url") else absolute
                            val found=destination?.let(repo.routes::recognize)
                            found?.takeIf {it.platform==Platform.X&&(it.kind==TargetKind.POST||it.kind==TargetKind.PROFILE&&it.identity.matches(Regex("[A-Za-z0-9_]{1,15}"))&&it.identity.lowercase() !in setOf("home","search","explore","settings","messages","login","signup","i"))}?.canonicalUrl
                        }.distinct()
                        mapOf("receivedMs" to received,"usableMs" to (System.nanoTime()-requestStarted)/1_000_000,"links" to links.size,"challenge" to challenge,"bytes" to payload.text.toByteArray().size,"title" to document.title().take(150))
                    }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;mapOf("error" to "25-second timeout","ms" to (System.nanoTime()-requestStarted)/1_000_000)}
                    catch(e: Exception){mapOf("error" to e.toString(),"ms" to (System.nanoTime()-requestStarted)/1_000_000)}
                    publish(mapOf("provider" to provider,"query" to word,"round" to round)+result)
                }
            }
        }catch(e: Exception){publish(mapOf("phase" to "failed","state" to repo.tor.state.value,"error" to e.toString()));throw e}
        finally {withContext(NonCancellable+Dispatchers.Main){repo.applyNetworkSettings()}}
    }
}
