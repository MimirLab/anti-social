/* AntiSocial, GPL-3.0-or-later. Deterministic orchestration around purpose-built local tools. */
package app.antisocial.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LanguagePrediction(val language: String, val confidence: Double)
data class ReadingTranslation(val html: String, val recipe: String)
class SourceLanguageUnavailable(val language: String?, val reason: String): Exception(reason)

/** No text-model tool calls. Detection, segmentation, pack selection, value
 * protection, translation and evidence alignment have separate contracts. */
class EnglishReadingPreparer(
    private val detect: suspend (String)->List<LanguagePrediction>,
    private val translate: suspend (String,String)->ReadingTranslation,
    private val supported: (String)->Boolean,
    private val checkWork: ()->Unit = {},
    private val onTranslation: suspend (String,String,ReadingTranslation)->Unit = {_,_,_->}
) {
    suspend fun prepare(source: String, hint: String = ""): ContextReadingText = prepareSource(source,hint,false)
    /** Keep the best available source instead of making confidence a veto.
     * Successful translations survive an uncertain neighboring fragment;
     * unchanged fallbacks are explicitly marked, never called translations. */
    suspend fun prepareBestEffort(source:String,hint:String=""):ContextReadingText=prepareSource(source,hint,true)
    private suspend fun prepareSource(source: String, hint: String, bestEffort:Boolean): ContextReadingText {
        require(source.length <= ContextReadingText.MAX_CHARACTERS)
        val text = StringBuilder(); val spans = mutableListOf<ContextReadingSpan>(); val recipes = sortedSetOf(POLICY)
        var unconfirmed=false
        fun append(start: Int,end: Int,value: String,language: String,translated: Boolean) {
            if(start==end)return
            if(value.isEmpty())throw SourceLanguageUnavailable(language,"Translation omitted a source passage")
            // CJK sentences need no source space. Their separate English
            // translations do; attach that generated separator to the second
            // translated passage without inventing an original-source offset.
            val separator=translated && spans.lastOrNull()?.translated==true && start>0 &&
                source.codePointBefore(start) in CJK_SENTENCE_END && text.lastOrNull() in setOf('.','!','?') &&
                value.firstOrNull()?.let {it.isLetter()&&it.code<128}==true
            val reading=if(separator)" $value"else value
            spans += ContextReadingSpan(start,end,text.length,text.length+reading.length,language,translated)
            text.append(reading)
            if(text.length>ContextReadingText.MAX_CHARACTERS)throw SourceLanguageUnavailable(language,"The translated source exceeds the reading limit")
        }
        // A whole-source sample is only a hint for very short fragments. Each
        // sentence is still checked; comments never inherit their author's or
        // the parent post's language.
        val whole = try {sample(source).takeIf { it.any(Char::isLetter) && !neutral(it) }?.let { predictions(it) }.orEmpty()}
            catch(failure:SourceLanguageUnavailable) {
                if(!bestEffort)throw failure
                return ContextReadingText.identity(source,POLICY+"|source-language-unconfirmed",true)
            }
        val inherited = choose(whole,null)?.takeIf { whole.firstOrNull()?.confidence?.let { it>=.8 }==true }
        val declared = canonical(hint).takeIf { it.isNotBlank() }
        for ((start,end) in passages(source)) {
            currentCoroutineContext().ensureActive();checkWork()
            val raw = source.substring(start,end)
            val leading = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it>=0 } ?: raw.length
            val trailing = raw.indexOfLast { !it.isWhitespace() }+1
            if(leading>=trailing) { append(start,end,raw,"zxx",false); continue }
            append(start,start+leading,raw.substring(0,leading),"zxx",false)
            val original = raw.substring(leading,trailing)
            val probe = sample(original)
            if(probe.none(Char::isLetter) || neutral(probe)) {
                append(start+leading,start+trailing,original,"zxx",false)
            } else {
                try {
                val guesses = predictions(probe)
                val fallback = inherited ?: declared
                val language = choose(guesses,fallback) ?: unambiguousJapaneseScript(probe)
                    ?: throw SourceLanguageUnavailable(null,"This passage's language is uncertain")
                if(language=="en") append(start+leading,start+trailing,original,"en",false)
                else {
                    if(!supported(language))throw SourceLanguageUnavailable(language,"This language is not available offline")
                    val protected = markup(original)
                    val result = translate(language,protected.first)
                    val translated = unwrap(result.html,protected.second,language)
                    // The translator may not silently return a foreign passage
                    // unchanged. Identifiers and neutral replies are exempt.
                    val check = sample(translated)
                    if(check.any(Char::isLetter) && !neutral(check) && !confirmsEnglishTranslation(predictions(check)))
                        throw SourceLanguageUnavailable(language,"The translation could not be confirmed as English")
                    onTranslation(language,protected.first,result)
                    currentCoroutineContext().ensureActive();checkWork()
                    recipes += result.recipe
                    append(start+leading,start+trailing,translated,language,true)
                }
                }catch(failure:SourceLanguageUnavailable) {
                    if(!bestEffort)throw failure
                    unconfirmed=true
                    append(start+leading,start+trailing,original,"und",false)
                }
            }
            append(start+trailing,end,raw.substring(trailing),"zxx",false)
        }
        // Storage admits views by their leading processing policy. Keep that
        // marker first; alphabetically sorting engine names before it would
        // make every completed translation miss the durable reading cache.
        val recipe=(listOf(POLICY)+recipes.filter {it!=POLICY}).joinToString("|")
        if(spans.none {it.translated})return ContextReadingText.identity(source,recipe,unconfirmed)
        return ContextReadingText(ContextReadingText.digest(source),text.toString(),spans,recipe,unconfirmed).also { it.validate(source) }
    }

    private suspend fun predictions(text: String): List<LanguagePrediction> = detect(text.take(4000)).also { results ->
        require(results.size<=3 && results.all { it.language.matches(Regex("[A-Za-z_-]{2,16}")) && it.confidence.isFinite() && it.confidence in 0.0..1.0 })
    }.sortedByDescending { it.confidence }
    private fun choose(guesses: List<LanguagePrediction>, fallback: String?): String? {
        val first=guesses.firstOrNull() ?: return null
        val language=canonical(first.language);val second=guesses.getOrNull(1)?.confidence ?: 0.0
        if(first.confidence >= .5 && first.confidence-second>=.15)return language
        // A confident sentence in the same source can resolve a tiny fragment;
        // an opposing confident detection always takes precedence above.
        val hint=fallback?.let(::canonical)
        if(hint!=null && guesses.any { canonical(it.language)==hint && it.confidence>=.2 })return hint
        return null
    }
    private fun confirmsEnglishTranslation(guesses: List<LanguagePrediction>): Boolean {
        if(choose(guesses,null)=="en")return true
        // The output direction of this verified NMT pack is known. Short
        // English such as "Consult the guide to" can score below .5 despite
        // leading every alternative by over 3x. Admit that bounded case only
        // after translation; never weaken unknown-source language selection or
        // override a leading foreign-language prediction.
        val first=guesses.firstOrNull() ?: return false
        val second=guesses.getOrNull(1)?.confidence ?: 0.0
        return canonical(first.language)=="en" && first.confidence>=.3 &&
            first.confidence-second>=.15 && first.confidence>=3*second
    }
    private fun unambiguousJapaneseScript(text: String): String? {
        val letters=text.codePoints().filter {Character.isLetter(it)}.toArray()
        if(letters.isEmpty())return null
        var kana=0;var japanese=0
        for(cp in letters)when(Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HIRAGANA,Character.UnicodeScript.KATAKANA->{kana++;japanese++}
            Character.UnicodeScript.HAN->japanese++
            else->{}
        }
        // fastText's top three can omit Japanese for a short label such as
        // 説明書はこちら. Kana plus a dominant Japanese script is unambiguous;
        // shared Han alone, one quoted kana or mixed Latin prose is not.
        return if(kana>=2 && kana*5>=letters.size && japanese*5>=letters.size*4)"ja"else null
    }
    private fun sample(text: String) = PROTECTED.replace(text) { " " }.replace(Regex("\\s+")," ").trim()
    private fun markup(text: String): Pair<String,List<String>> {
        val expected=mutableListOf<String>();val body=Element("p");var at=0
        for(match in PROTECTED.findAll(text)) {
            body.appendText(text.substring(at,match.range.first))
            val id=expected.size.toString();expected+=match.value
            // NMT must see numeric arguments in their sentence. Ignored code
            // tags fragment grammar, while translated span boundaries are only
            // approximate word alignments. Check ordered values independently
            // below instead of using markup as numeric evidence.
            if(opaque(match.value))body.appendChild(Element("code").attr("data-antisocial",id).text(match.value))
            else body.appendText(match.value)
            at=match.range.last+1
        }
        body.appendText(text.substring(at));return body.outerHtml() to expected
    }
    private fun unwrap(html: String,expected: List<String>,language: String): String {
        if(html.length>65536)throw SourceLanguageUnavailable(language,"The translated passage exceeds its limit")
        val body=Jsoup.parseBodyFragment(html).body();val codes=body.select("code")
        val opaqueExpected=expected.withIndex().filter {opaque(it.value)}
        if(codes.size!=opaqueExpected.size || codes.withIndex().any { (index,code)->code.attr("data-antisocial")!=opaqueExpected[index].index.toString() || code.wholeText()!=opaqueExpected[index].value } ||
            body.select("*").any { it.tagName() !in setOf("body","p","code") })
            throw SourceLanguageUnavailable(language,"Translation changed a protected source value")
        val visible=body.clone().also {it.select("code").forEach {code->code.text(" ")}}.wholeText()
        val observed=PROTECTED.findAll(visible).map {it.value}.toList()
        if(observed.any(::opaque) || observed!=expected.filterNot(::opaque))
            throw SourceLanguageUnavailable(language,"Translation changed a protected source value")
        return body.wholeText().trim().takeIf { it.isNotBlank() }
            ?: throw SourceLanguageUnavailable(language,"Translation returned no readable text")
    }
    private fun opaque(value: String): Boolean = value.startsWith('`') || value.startsWith("http",true) || value.startsWith("www.",true)
    private fun passages(text: String): List<Pair<Int,Int>> {
        val result=mutableListOf<Pair<Int,Int>>();var start=0;var at=0
        val protected=PROTECTED.findAll(text).iterator();var protection=if(protected.hasNext())protected.next()else null
        while(at<text.length) {
            while(protection!=null&&at>protection.range.last)protection=if(protected.hasNext())protected.next()else null
            if(protection!=null&&at==protection.range.first){
                if(protection.value.length>1400) {
                    if(at>start)result+=start to at
                    result+=at to protection.range.last+1;at=protection.range.last+1;start=at
                }else at=protection.range.last+1
                continue
            }
            val cp=text.codePointAt(at);at+=Character.charCount(cp)
            val sentence=cp in SENTENCE_END && (at==text.length || text[at].isWhitespace() || cp>127)
            if(sentence || cp==10 || at-start>=1400) {
                if(at-start>=1400 && !sentence && cp!=10) {
                    val boundary=(at-1 downTo maxOf(start+1,at-200)).firstOrNull { text[it].isWhitespace() }
                    if(boundary!=null)at=boundary+1
                }
                result+=start to at;start=at
            }
        }
        if(start<text.length)result+=start to text.length
        require(result.size<=32_768){"This source contains too many reading passages"}
        return result
    }
    companion object {
        const val POLICY="english-reading-v5-best-effort-fasttext-8f3472cfe873"
        private val SENTENCE_END=setOf('.'.code,'!'.code,'?'.code,';'.code,0x3002,0xFF01,0xFF1F,0x061F)
        private val CJK_SENTENCE_END=setOf(0x3002,0xFF01,0xFF1F)
        private val NEUTRAL=setOf("yes","no","ok","okay","lol","lmao","wow","thanks","thank you","great","nice","first","true","false")
        // A literal neutral expression remains unchanged even when its context
        // later requires review (for example the exact words on a sign). This
        // is preparation, not a relevance or discard decision.
        private fun neutral(text:String)=text.trim().lowercase(Locale.ROOT).trimEnd('.','!','?') in NEUTRAL||ContextTextNoise.reaction(text)
        // CJK prose normally has no space before a number. A Japanese particle
        // must not prevent protecting "56 Wh" as though it were a Latin model
        // identifier. Keep the existing boundary for Latin alphanumeric words.
        private val PROTECTED=Regex("```[\\s\\S]*?```|`[^`\\n]+`|https?://[^\\s<>]+|www\\.[^\\s<>]+|(?:(?<![\\p{L}\\p{N}])|(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]))[-+−]?\\d+(?:[.,:/-]\\d+)*(?:%|\\s?(?:Wh|mAh|MHz|GHz|GB|MB|KB|mm|cm|km|kg|ms|°C|°F))?",RegexOption.IGNORE_CASE)
        fun canonical(language: String): String = when(val code=language.trim().replace('_','-').lowercase(Locale.ROOT)) {
            "zh","zh-cn","zh-hans"->"zh-Hans";"zh-tw","zh-hant"->"zh-Hant";"no"->"nb";else->code.substringBefore('-')
        }
    }
}
