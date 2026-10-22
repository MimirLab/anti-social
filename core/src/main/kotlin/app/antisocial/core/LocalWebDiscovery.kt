/* AntiSocial, GPL-3.0-or-later. Public index links discover publishers; snippets are not facts. */
package app.antisocial.core

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64
import java.net.URI

class LocalWebDiscovery(private val gateway:NetworkGateway,private val routes:DarwinRoutes) {
    suspend fun search(query:String,provider:SearchProvider,originalFallback:Boolean):List<LocalWebHit> {
        require(query.length in 2..512)
        val sources=when(provider) {
            SearchProvider.BING->if(originalFallback)listOf("https://www.bing.com/search?format=rss&q="+DarwinRoutes.encode(query))else emptyList()
            SearchProvider.DUCKDUCKGO->if(originalFallback)listOf("https://html.duckduckgo.com/html/?q="+DarwinRoutes.encode(query))else emptyList()
            SearchProvider.DARWIN->routes.searchSources.filter {it.frontend=="librey"}.take(3).map {it.root.trimEnd('/')+"/search.php?q="+DarwinRoutes.encode(query)}
        }
        if(sources.isEmpty())throw SourceFailure(HealthStatus.UNSUPPORTED,"Enable a public search source in Settings to discover additional local publishers")
        var failure:Exception?=null
        for(url in sources)try {
            val payload=gateway.text(url,maxBytes=1024*1024,route=NetworkRoute.TOR)
            if(payload.status !in 200..299)throw SourceFailure(HealthStatus.UNAVAILABLE,"The selected search source is unavailable")
            val hits=parse(payload.text,payload.finalUrl)
            if(hits.isNotEmpty())return hits
            throw SourceFailure(HealthStatus.EMPTY,"The selected index supplied no publisher links")
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){failure=e}
        throw failure ?: SourceFailure(HealthStatus.EMPTY,"No publisher links were available")
    }
    companion object {
        fun parse(body:String,url:String):List<LocalWebHit> {
            require(body.length<=1024*1024)
            val page=Jsoup.parse(body,url,if(body.trimStart().startsWith("<?xml")||body.contains("<rss"))Parser.xmlParser()else Parser.htmlParser())
            if(page.select("form[action*=challenge],#challenge-form,.anomaly-modal,input[name=captcha]").isNotEmpty()||
                Regex("verify (?:that )?you are human|unusual traffic|automated queries|bots use duckduckgo|challenge-form",RegexOption.IGNORE_CASE).containsMatchIn(page.text().take(12000)))
                throw SourceFailure(HealthStatus.CHALLENGE,"The selected search source requested verification. Cached results remain available.")
            val raw=mutableListOf<LocalWebHit>()
            page.select("item").take(40).forEach {item->val link=item.selectFirst("link")?.text().orEmpty();if(link.isNotBlank())raw+=LocalWebHit(link,item.selectFirst("title")?.text().orEmpty())}
            page.select("a.result__a,.text-result-wrapper a:has(h2),li.b_algo h2 a,a.result-link").take(40).forEach {a->raw+=LocalWebHit(a.absUrl("href"),a.text())}
            return raw.mapNotNull {hit->val decoded=unwrap(hit.url) ?: return@mapNotNull null
                val link=LocalDiscoveryIdentity.url(decoded) ?: return@mapNotNull null
                val host=link.toHttpUrlOrNull()?.host.orEmpty()
                if(host=="bing.com"||host.endsWith(".bing.com")||host=="duckduckgo.com"||host.endsWith(".duckduckgo.com"))return@mapNotNull null
                LocalWebHit(link,hit.title.take(400))
            }.distinctBy {it.url}.take(24)
        }
        private fun unwrap(value:String):String? {
            val parsed=value.toHttpUrlOrNull() ?: return null
            parsed.queryParameter("uddg")?.takeIf {parsed.host.endsWith("duckduckgo.com")}?.let {return it}
            if(parsed.host.endsWith("bing.com")&&parsed.encodedPath.startsWith("/ck/")) {
                val token=parsed.queryParameter("u") ?: return null
                return if(token.startsWith("a1"))runCatching {String(Base64.getUrlDecoder().decode(token.drop(2)))}.getOrNull()else token
            }
            return value
        }
    }
}
