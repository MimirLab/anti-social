package app.antisocial.core

import com.google.gson.Gson
import java.io.File
import kotlin.system.measureNanoTime

/** Local CPU measurements; no requests are sent to any of these example URLs. */
fun main() {
    val loader=object {}.javaClass
    lateinit var filter: NativeFilter
    val binary=loader.getResourceAsStream("/network-rules.compiled")!!.use {it.readBytes()}
    val restoreMs=measureNanoTime {filter=NativeFilter.restore(binary)}/1_000_000.0
    val cases=listOf(
        Triple("tracker","https://www.google-analytics.com/collect?v=1","https://reader.test/"),
        Triple("ad","https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js","https://reader.test/"),
        Triple("public API","https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=bsky.app","https://bsky.app/"),
        Triple("Darwin frontend","https://nitter.tiekoetter.com/NASA","https://x.com/NASA"),
        Triple("photo","https://pinterest.bunk.im/image/fixture.jpg","https://www.pinterest.com/nasa/")
    )
    val checks=cases.map {(name,url,source)->
        lateinit var decision: FilterDecision
        val coldMs=measureNanoTime {decision=filter.check(url,source,"xmlhttprequest")}/1_000_000.0
        val elapsed=LongArray(400) {i->measureNanoTime {filter.check("$url${if('?' in url)'&' else '?'}audit=$i",source,"xmlhttprequest")}}.sorted()
        mapOf("case" to name,"blocked" to decision.blocked,"reason" to decision.reason,"coldMs" to coldMs,"p50Ms" to elapsed[200]/1_000_000.0,"p95Ms" to elapsed[380]/1_000_000.0)
    }
    filter.close()
    val json=Gson().toJson(mapOf("compiledBytes" to binary.size,"restoreMs" to restoreMs,"checks" to checks))
    System.getProperty("antisocial.filterAuditOutput")?.let {File(it).apply {parentFile.mkdirs();writeText(json+"\n")}}
    println(json)
}
