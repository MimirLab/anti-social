package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import kotlin.system.measureNanoTime

/** CPU-only replay of an externally retained public response; no requests. */
fun main() {
    val loader=object {}.javaClass
    fun asset(name: String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
    val cleaner=UrlCleaner(asset("clean-links.json"));cleaner.prepare()
    val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    val target=routes.recognize(System.getProperty("antisocial.extractionTarget","https://x.com/NASA"))!!
    val frontend=System.getProperty("antisocial.extractionFrontend","nitter")
    val source=Source(target.platform,frontend,System.getProperty("antisocial.extractionRoot","https://nitter.tiekoetter.com"),role=if(frontend=="original")SourceRole.ORIGINAL else SourceRole.ALTERNATE)
    val payload=Payload(File(System.getProperty("antisocial.extractionInput")).readText(),source.root+java.net.URI(target.canonicalUrl).rawPath,"text/html",200)
    var page: Page?=null
    val cold=measureNanoTime {page=Extraction.parse(payload,target,source,routes)}
    val warm=LongArray(15){measureNanoTime {Extraction.parse(payload,target,source,routes)}}.sorted()
    val result=Gson().toJson(mapOf("target" to target,"profile" to page!!.profile,"collections" to page!!.collections,"inputBytes" to payload.text.toByteArray().size,"posts" to page!!.posts.size,"dated" to page!!.posts.count {it.publishedAt!=null},"ids" to page!!.posts.map {it.id},"coldMs" to cold/1_000_000.0,"p50Ms" to warm[7]/1_000_000.0,"p95Ms" to warm[14]/1_000_000.0))
    File(System.getProperty("antisocial.extractionOutput")).apply {parentFile.mkdirs();writeText(result+"\n")}
    println(result)
}
