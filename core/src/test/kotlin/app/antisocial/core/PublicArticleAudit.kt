package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.File
import java.util.zip.GZIPInputStream

/** Explicit public availability check, separate from deterministic unit tests. */
fun main()=runBlocking {
    val type=object {}.javaClass
    val cleaner=UrlCleaner(JsonParser.parseReader(type.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    NativeFilter(GZIPInputStream(type.getResourceAsStream("/network-rules.seed")!!).reader().readText()).use {filter->
        val gateway=NetworkGateway(cleaner,filter);val resolver=PublicArticleResolver(gateway)
        val results=mutableListOf<Map<String,Any?>>()
        val urls=mutableListOf<String>()
        try {
            val index=gateway.text("https://text.npr.org/",maxBytes=512*1024)
            val id=Jsoup.parse(index.text,index.finalUrl).select("a[href]").map {it.absUrl("href")}.firstNotNullOfOrNull {
                Regex("https://text\\.npr\\.org/((?:nx-s1-|g-s1-)[0-9]+|[0-9]{6,})(?:[/?#]|$)").find(it)?.groupValues?.get(1)
            }
            if(id!=null)urls+="https://www.npr.org/$id"else results+=mapOf("source" to "NPR text index","status" to "unavailable","detail" to "No exact article ID found")
        }catch(e:Exception){results+=mapOf("source" to "NPR text index","status" to "unavailable","detail" to e.message)}
        urls+="https://doi.org/10.1038/s41586-021-03819-2"
        System.getProperty("antisocial.articleAuditUrls")?.split('|')?.filter {it.isNotBlank()}?.let {urls+=it}
        for(url in urls.distinct()) {
            val start=System.nanoTime()
            val result=try {
                when(val read=resolver.conditional(url,url)) {
                    is ContextArticleFetch.Ready->mapOf("url" to url,"status" to "readable","finalUrl" to read.document.finalUrl,"route" to read.document.access?.route,
                        "characters" to read.document.text.length,"title" to read.document.title,"truncated" to read.document.truncated,"version" to read.document.access?.version)
                    is ContextArticleFetch.PublicPdf->mapOf("url" to url,"status" to "public_pdf_candidate","finalUrl" to read.url,"route" to read.access.route,
                        "limit" to "HEAD reachability only; actual Android PDF download/page qualification is separate")
                    is ContextArticleFetch.NotModified->mapOf("url" to url,"status" to "unexpected_unchanged")
                }
            }catch(e:Exception){mapOf("url" to url,"status" to "unavailable","detail" to e.message)}
            results+=result+mapOf("elapsedMs" to (System.nanoTime()-start)/1_000_000)
        }
        val out=File(System.getProperty("antisocial.articleAuditOutput"));out.parentFile.mkdirs()
        out.writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf("results" to results,"scope" to "Current public sources through production gateway/filter. Availability can change; no universal publisher claim.")))
        println(out.readText())
    }
}
