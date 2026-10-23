package app.antisocial.core

import com.google.gson.JsonObject
import java.net.URLDecoder
import java.net.URI

/** Darwin ClearURLs rule semantics, evaluated locally before each network hop. */
class UrlCleaner internal constructor(data: JsonObject,private val linear:Boolean) {
    constructor(data:JsonObject):this(data,true)
    private data class Provider(val url: UrlPattern,val exceptions: List<UrlPattern>,val redirects: List<UrlPattern>,val parameters: List<UrlPattern>,val raw: List<UrlPattern>)
    private fun pattern(value: String,ignoreCase: Boolean=true)=runCatching {UrlPattern(value,ignoreCase,linear)}.getOrNull()
    private class Input(val value:String) {val ascii=value.all {it.code in 32..126};val folded=if(ascii)value.lowercase(java.util.Locale.ROOT)else value}
    private inline fun <T> attempt(default:T,read:()->T):T=try {read()}catch(e:UrlPattern.WorkLimit){throw e}catch(_:StackOverflowError){throw UrlPattern.WorkLimit()}catch(_:Exception){default}
    private fun matches(pattern:UrlPattern,value:Input)=attempt(false){pattern.contains(value.value,value.ascii,value.folded)}
    private val providers by lazy {
        data.getAsJsonObject("providers").entrySet().mapNotNull {(_,element)->
            val p=element.asJsonObject
            val url=pattern(p["urlPattern"].asString) ?: return@mapNotNull null
            fun patterns(key: String,anchor: Boolean=false,ignoreCase: Boolean=true)=p.getAsJsonArray(key)?.mapNotNull {value->pattern(if(anchor)"^(?:${value.asString})$"else value.asString,ignoreCase)} ?: emptyList()
            Provider(url,patterns("exceptions"),patterns("redirections"),patterns("rules",true)+patterns("referralMarketing",true),patterns("rawRules",ignoreCase=false))
        }
    }
    fun prepare(){providers.size}
    fun clean(input: String): String {
        if(input.length>65_536)throw UrlPattern.WorkLimit()
        if(input.isBlank())return input
        var current = Input(input)
        val seen = mutableSetOf<String>()
        repeat(12) {
            if (!seen.add(current.value)) return current.value
            val before = current.value
            for (provider in providers) {
                if(provider.redirects.isEmpty()&&provider.raw.isEmpty()&&!current.value.contains('?'))continue
                if (!matches(provider.url,current)) continue
                if (provider.exceptions.any {matches(it,current)}) continue
                for (pattern in provider.redirects) {
                    val target=attempt<String?>(null){pattern.target(current.value,current.ascii)}
                    if (target != null) { val decoded=runCatching { URLDecoder.decode(target,"UTF-8") }.getOrDefault(target); if (decoded.startsWith("https://") || decoded.startsWith("http://")) { current=Input(decoded); break } }
                }
                val uri=runCatching { URI(current.value) }.getOrNull() ?: continue
                // Preserve signed media parameters: removing tracking can invalidate signatures.
                val parts=uri.rawQuery?.split('&') ?: emptyList()
                if (parts.any { it.substringBefore('=').lowercase() in setOf("signature","sig","x-amz-signature","x-goog-signature","token","expires") }) continue
                val query=parts.filterNot { part -> val key=Input(runCatching { URLDecoder.decode(part.substringBefore('='),"UTF-8") }.getOrDefault(part.substringBefore('='))); provider.parameters.any { matches(it,key) } }.joinToString("&")
                current = Input(current.value.substringBefore('?').substringBefore('#') + (if(query.isNotEmpty()) "?$query" else "") + (uri.rawFragment?.let { "#$it" } ?: ""))
                provider.raw.forEach { pattern -> current=Input(attempt(current.value){pattern.remove(current.value,current.ascii)}) }
            }
            if(current.value==before)return current.value
        }
        return current.value
    }
}
