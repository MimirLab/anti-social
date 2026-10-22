package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Opt-in diagnosis through the same gateway and bundled protection as the app. */
fun main()=runBlocking {
    val loader=object {}.javaClass
    val cleaner=UrlCleaner(JsonParser.parseReader(loader.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    val root=File(System.getProperty("antisocial.evidenceRoot")).apply {mkdirs()}
    val bytes=loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}
    NativeFilter.restore(bytes).use {filter->
        val gateway=NetworkGateway(cleaner,filter);val permits=Semaphore(4)
        System.getProperty("antisocial.auditUrls").split('|').filter {it.isNotBlank()}.mapIndexed {index,url->async(Dispatchers.IO){permits.withPermit {
            val result=linkedMapOf<String,Any?>("url" to url)
            try {
                val payload=withTimeout(16_000){gateway.text(url)}
                val file=File(root,"response-$index.txt");file.writeText(payload.text)
                result["finalUrl"]=payload.finalUrl;result["type"]=payload.contentType;result["bytes"]=file.length();result["file"]=file.path
                result["title"]=org.jsoup.Jsoup.parse(payload.text).title()
            }catch(e: Exception){result["error"]=e.message ?: e.javaClass.simpleName}
            println(Gson().toJson(result))
        }}}.awaitAll()
        gateway.client().dispatcher.executorService.shutdown()
        gateway.client().connectionPool.evictAll()
    }
}
