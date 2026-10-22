/* anti social, GPL-3.0-or-later. Bounded HTTP Link receipt, not a generic URL crawler. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object MediaCredentialLinks {
    fun resolve(value:String,base:String):String? {
        if(value.length>4096||value.any(Char::isISOControl))return null
        val url=base.toHttpUrlOrNull()?.resolve(value.trim()) ?: return null
        return url.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.newBuilder()?.fragment(null)?.build()?.toString()
    }
    fun headers(values:List<String>,base:String):List<String> = values.take(8).flatMap {raw->
        if(raw.length>16384)emptyList()else Regex("<([^>]{1,4096})>((?:[^<,\"]|\"[^\"]*\")*)").findAll(raw).take(16).mapNotNull {m->
            val rel=Regex("(?:^|;)\\s*rel\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))",RegexOption.IGNORE_CASE).find(m.groupValues[2])
            val tokens=rel?.let {it.groupValues[1].ifEmpty {it.groupValues[2]}.split(Regex("\\s+"))}.orEmpty()
            if("c2pa-manifest" in tokens)resolve(m.groupValues[1],base)else null
        }.toList()
    }.distinct().take(4)
}
