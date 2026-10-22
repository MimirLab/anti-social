package app.antisocial.core

import com.google.gson.JsonObject
import java.net.URLDecoder
import java.net.URI

/** Darwin ClearURLs rule semantics, evaluated locally before each network hop. */
class UrlCleaner(data: JsonObject) {
    private data class Provider(val url: Regex,val exceptions: List<Regex>,val redirects: List<Regex>,val parameters: List<Regex>,val raw: List<Regex>)
    private fun pattern(value: String,ignoreCase: Boolean=true)=runCatching {if(ignoreCase)Regex(value,RegexOption.IGNORE_CASE)else Regex(value)}.getOrNull()
    private fun matches(pattern: Regex,value: String)=runCatching {pattern.containsMatchIn(value)}.getOrDefault(false)
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
        if(input.isBlank())return input
        var current = input
        val seen = mutableSetOf<String>()
        repeat(12) {
            if (!seen.add(current)) return current
            val before = current
            for (provider in providers) {
                if(provider.redirects.isEmpty()&&provider.raw.isEmpty()&&!current.contains('?'))continue
                if (!matches(provider.url,current)) continue
                if (provider.exceptions.any {matches(it,current)}) continue
                for (pattern in provider.redirects) {
                    val target=runCatching {pattern.find(current)?.groupValues?.getOrNull(1)}.getOrNull()
                    if (target != null) { val decoded=runCatching { URLDecoder.decode(target,"UTF-8") }.getOrDefault(target); if (decoded.startsWith("https://") || decoded.startsWith("http://")) { current=decoded; break } }
                }
                val uri=runCatching { URI(current) }.getOrNull() ?: continue
                // Preserve signed media parameters: removing tracking can invalidate signatures.
                val parts=uri.rawQuery?.split('&') ?: emptyList()
                if (parts.any { it.substringBefore('=').lowercase() in setOf("signature","sig","x-amz-signature","x-goog-signature","token","expires") }) continue
                val query=parts.filterNot { part -> val key=runCatching { URLDecoder.decode(part.substringBefore('='),"UTF-8") }.getOrDefault(part.substringBefore('=')); provider.parameters.any { matches(it,key) } }.joinToString("&")
                current = current.substringBefore('?').substringBefore('#') + (if(query.isNotEmpty()) "?$query" else "") + (uri.rawFragment?.let { "#$it" } ?: "")
                provider.raw.forEach { pattern -> current=runCatching { current.replace(pattern,"") }.getOrDefault(current) }
            }
            if(current==before)return current
        }
        return current
    }
}
