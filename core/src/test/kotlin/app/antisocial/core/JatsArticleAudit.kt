package app.antisocial.core

import com.google.gson.GsonBuilder
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.text.Normalizer

/** Explicit local retention audit of an externally retained public acquisition.
 * The complete paper stays outside the repository and Android packages. */
fun main() {
    val gson=GsonBuilder().setPrettyPrinting().create()
    val input=File(System.getProperty("antisocial.jatsInput"))
    val out=File(System.getProperty("antisocial.jatsOutput")).apply {mkdirs()}
    val original=System.getProperty("antisocial.jatsOriginal")
    val payload=gson.fromJson(input.readText(),Payload::class.java)
    val start=System.nanoTime();val document=ContextArticles.extract(payload,original,readerChecks=true)
    val elapsed=(System.nanoTime()-start)/1_000_000
    fun normalized(text:String)=Normalizer.normalize(text,Normalizer.Form.NFKC).filter {it.isLetterOrDigit()}.lowercase()
    val source=Jsoup.parse(payload.text,"",Parser.xmlParser())
    val paragraphs=source.select("article > body p").filter {it.text().length>=60}
    val extracted=normalized(document.text)
    val missing=paragraphs.filterNot {normalized(it.text()) in extracted}
    File(out,"document.json").writeText(gson.toJson(document))
    val report=mapOf("title" to document.title,"original" to original,"retrieved" to document.finalUrl,"doi" to document.publicationDoi,
        "truncated" to document.truncated,"blocks" to document.blocks.size,"characters" to document.text.length,"elapsedMs" to elapsed,
        "sourceBodyParagraphsOver60Characters" to paragraphs.size,"retainedAsContiguousNormalizedText" to paragraphs.size-missing.size,
        "missingParagraphs" to missing.map {it.text().take(240)},"references" to document.references?.size,"license" to document.access?.license,
        "scope" to "Local retained source; punctuation/whitespace-normalized paragraph recall, not semantic evaluation or universal JATS rendering.")
    File(out,"retention.json").writeText(gson.toJson(report));println(gson.toJson(report))
}
