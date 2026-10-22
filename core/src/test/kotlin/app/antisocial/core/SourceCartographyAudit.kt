package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Explicit research only. Uses app privacy/network code; no public reads in unit tests. */
fun main()=runBlocking {
    val input=File(System.getProperty("antisocial.cartographyInput"))
    val output=File(System.getProperty("antisocial.cartographyOutput")).apply {mkdirs()}
    File(output,"classpath.txt").writeText(System.getProperty("java.class.path"))
    val cases=JsonParser.parseString(input.readText()).asJsonArray.take(400)
    val gson=GsonBuilder().setPrettyPrinting().create();val loader=object {}.javaClass
    val cleaner=UrlCleaner(JsonParser.parseReader(loader.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    NativeFilter.restore(loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}).use {filter->
        val gateway=NetworkGateway(cleaner,filter);val permits=Semaphore(2);val hosts=ConcurrentHashMap<String,Semaphore>()
        val results=cases.mapIndexed {index,raw->async(Dispatchers.IO){permits.withPermit {
            val item=raw.asJsonObject;val url=item["url"].asString;val host=URI(url).host
            hosts.computeIfAbsent(host){Semaphore(1)}.withPermit {
                val record=JsonObject().apply {addProperty("id",item["id"]?.asString ?: "$index");addProperty("url",url);addProperty("observedAt",Instant.now().toString());addProperty("tor",false)}
                val started=System.nanoTime()
                try {
                    val headers=item.getAsJsonObject("headers")?.entrySet()?.associate {it.key to it.value.asString}.orEmpty()
                    require(headers.keys.none {it.equals("Authorization",true)||it.equals("Cookie",true)})
                    val payload=withTimeout(18_000){gateway.text(url,item["sourceUrl"]?.asString ?: url,headers=headers)}
                    val file=File(output,"response-$index.json");file.writeText(gson.toJson(payload))
                    record.addProperty("finalUrl",payload.finalUrl);record.addProperty("status",payload.status);record.addProperty("bytes",payload.text.toByteArray().size);record.addProperty("type",payload.contentType);record.addProperty("responseFile",file.path)
                    record.addProperty("title",org.jsoup.Jsoup.parse(payload.text).title())
                    runCatching {JsonParser.parseString(payload.text)}.getOrNull()?.takeIf {it.isJsonObject}?.let {record.add("jsonKeys",gson.toJsonTree(it.asJsonObject.keySet().take(24)))}
                }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();record.addProperty("error","Timed out")}
                catch(e: Exception){record.addProperty("error",e.message ?: e.javaClass.simpleName);(e as? SourceFailure)?.let {record.addProperty("healthStatus",it.status.name)}}
                record.addProperty("elapsedMs",(System.nanoTime()-started)/1_000_000)
                println(gson.toJson(record));record
            }
        }}}.awaitAll()
        File(output,"results.json").writeText(gson.toJson(results))
        gateway.client().dispatcher.executorService.shutdown();gateway.client().connectionPool.evictAll()
    }
    Unit
}
