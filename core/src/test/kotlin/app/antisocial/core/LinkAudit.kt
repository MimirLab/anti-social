package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import kotlin.system.measureNanoTime

/** CPU-only link-cleaning benchmark: never opens a connection. */
fun main() {
    val loader=object {}.javaClass
    val cleaner=UrlCleaner(JsonParser.parseReader(loader.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    var cleaned=""
    val cold=measureNanoTime {cleaned=cleaner.clean("https://nitter.tiekoetter.com/NASA?utm_source=benchmark")}
    val warm=LongArray(200){i->measureNanoTime {cleaner.clean("https://nitter.tiekoetter.com/NASA?utm_source=benchmark&item=$i")}}.sorted()
    val result=Gson().toJson(mapOf("coldMs" to cold/1_000_000.0,"p50Ms" to warm[100]/1_000_000.0,"p95Ms" to warm[190]/1_000_000.0,"cleaned" to cleaned))
    File(System.getProperty("antisocial.linkAuditOutput")).apply {parentFile.mkdirs();writeText(result+"\n")};println(result)
}
