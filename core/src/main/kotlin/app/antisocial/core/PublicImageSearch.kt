package app.antisocial.core

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal data class YandexImageRoute(val host: String,val uploadPath: String)
/** A small public-route pool, not a retry loop. A refusal is never healthy and
 * a continuation always remains on the origin that produced its image token. */
internal class YandexImageRoutePool {
    private val preferredHost=java.util.concurrent.atomic.AtomicReference("yandex.com")
    internal fun routes(): List<YandexImageRoute> {
        val first=preferredHost.get();val other=if(first=="yandex.com")"yandex.ru"else "yandex.com"
        return listOf(YandexImageRoute(first,"/images/touch/search"),YandexImageRoute(other,"/images/touch/search"))
    }
    suspend fun search(read: suspend (YandexImageRoute)->ImageSearchLinks): ImageSearchLinks {
        var failure: SourceFailure?=null
        for(route in routes())try {
            val result=withTimeoutOrNull(18_000){read(route)} ?: throw SourceFailure(HealthStatus.UNAVAILABLE,"Yandex public route timed out")
            if(result.urls.isNotEmpty())preferredHost.set(route.host)
            return result
        }catch(e: CancellationException){throw e}
        catch(e: SourceFailure) {
            if(e.status !in setOf(HealthStatus.UNAVAILABLE,HealthStatus.CHALLENGE,HealthStatus.UNKNOWN))throw e
            failure=e
        }catch(e: IOException) {
            if(e.message.orEmpty().contains("Blocked by privacy filters")||e.message.orEmpty().contains("access is disabled")||e.message.orEmpty().contains("Tor is connecting"))throw e
            failure=SourceFailure(HealthStatus.UNAVAILABLE,"Yandex public route could not connect")
        }
        val last=failure ?: SourceFailure(HealthStatus.UNAVAILABLE,"Yandex image search is unavailable")
        throw SourceFailure(last.status,last.message.orEmpty()+"; its alternate public routes also failed")
    }
}

enum class ImageSearchEngine(val id: String,val label: String) {
    BING("bing","Bing"), GOOGLE_LENS("google-lens","Google Lens"), YANDEX("yandex","Yandex"), SAUCENAO("saucenao","SauceNAO")
}
data class ImageSearchInput(val bytes: ByteArray,val mimeType: String="image/jpeg")
data class ImageSearchLinks(val urls: List<String>,val next: String?=null,val notice: String?=null)

/** Ordinary, accountless website image-upload forms. No keys, paid service or
 * browser companion is used. Provider HTML is discovery evidence only: callers
 * must hydrate and verify each social destination before presenting a card.
 *
 * Upload field names were verified against the public sites and the GPL-3.0
 * Search by Image extension, https://github.com/dessant/search-by-image . This
 * implementation is native Kotlin; no extension runtime or JavaScript is run.
 */
class PublicImageSearch(private val gateway: NetworkGateway) {
    private val yandexRoutes=YandexImageRoutePool()
    suspend fun search(engine: ImageSearchEngine,image: ImageSearchInput,cursor: String?=null): ImageSearchLinks {
        require(image.bytes.isNotEmpty()&&image.bytes.size<=8*1024*1024){"Search image is too large"}
        require(image.mimeType in setOf("image/jpeg","image/png","image/webp")){"Unsupported search image format"}
        if(cursor!=null) {
            require(cursor.length<=16*1024&&resultUrl(engine,cursor)){"Image continuation left its selected provider"}
            return extract(engine,read(engine,cursor))
        }
        val result=when(engine) {
            ImageSearchEngine.BING->gateway.imageUpload("https://www.bing.com/images/search?view=detailv2&iss=sbiupload&FORM=SBIHMP","imageBin",image.bytes,image.mimeType,base64=true)
            ImageSearchEngine.SAUCENAO->gateway.imageUpload("https://saucenao.com/search.php?hide=3","file",image.bytes,image.mimeType)
            ImageSearchEngine.GOOGLE_LENS-> {
                val upload=gateway.imageUpload("https://lens.google.com/v3/upload?hl=en","encoded_image",image.bytes,image.mimeType)
                if(upload.status==303) {
                    require(resultUrl(engine,upload.finalUrl)){"Google Lens returned an unexpected result destination"}
                    read(engine,upload.finalUrl)
                }else upload
            }
            ImageSearchEngine.YANDEX->return yandexRoutes.search {route->
                val url="https://${route.host}${route.uploadPath}?rpt=imageview&format=json&request="+DarwinRoutes.encode("""{"blocks":[{"block":"cbir-uploader__get-cbir-id"}]}""")
                val upload=gateway.imageUpload(url,"upfile",image.bytes,image.mimeType,headers=mapOf("X-Requested-With" to "XMLHttpRequest","Accept" to "application/json"))
                val params=runCatching {JsonParser.parseString(upload.text).asJsonObject.getAsJsonArray("blocks").first().asJsonObject.getAsJsonObject("params")}.getOrNull()
                val token=runCatching {params?.get("cbirId")?.asString}.getOrNull()
                if(token==null) {
                    // The uploader may return an HTML challenge with HTTP 200.
                    extract(engine,upload)
                    throw SourceFailure(HealthStatus.UNAVAILABLE,"Yandex did not return an image search reference")
                }
                require(token.length in 1..2048&&token.none(Char::isISOControl)){"Invalid Yandex image reference"}
                extract(engine,read(engine,"https://${route.host}/images/search?cbir_id="+DarwinRoutes.encode(token)+"&rpt=imageview&tabInt=1"))
            }
        }
        return extract(engine,result)
    }
    private suspend fun read(engine: ImageSearchEngine,url: String): Payload {
        require(resultUrl(engine,url)){"Image search left its selected provider"}
        val origin=url.toHttpUrlOrNull()!!.let {"${it.scheme}://${it.host}"}
        return gateway.text(url,route=NetworkRoute.TOR,allowedOrigins=setOf(origin),maxBytes=4L*1024*1024)
    }
    companion object {
        internal fun resultUrl(engine: ImageSearchEngine,value: String): Boolean {
            val url=value.toHttpUrlOrNull() ?: return false
            if(!url.isHttps||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty())return false
            return when(engine) {
                ImageSearchEngine.BING->url.host=="www.bing.com"&&url.encodedPath=="/images/search"&&url.queryParameter("insightsToken")?.isNotBlank()==true
                ImageSearchEngine.GOOGLE_LENS->url.host in setOf("www.google.com","lens.google.com")&&url.encodedPath=="/search"&&(url.queryParameter("vsrid")?.isNotBlank()==true||url.queryParameter("p")?.isNotBlank()==true)
                ImageSearchEngine.YANDEX->url.host in setOf("yandex.com","yandex.ru")&&url.encodedPath=="/images/search"&&url.queryParameter("cbir_id")?.isNotBlank()==true
                ImageSearchEngine.SAUCENAO->url.host=="saucenao.com"&&url.encodedPath=="/search.php"
            }
        }
        private fun providerHost(value: String)=listOf("bing.com","google.com","googleusercontent.com","gstatic.com","yandex.com","yandex.ru","yandex.net","yastatic.net","tineye.com","saucenao.com").any {value==it||value.endsWith(".$it")}
        private fun continuation(engine: ImageSearchEngine,base: String,value: String?): String? {
            val original=base.toHttpUrlOrNull() ?: return null
            val next=value?.let(original::resolve) ?: return null
            if(!resultUrl(engine,next.toString())||next.host!=original.host||next==original)return null
            val key=when(engine){ImageSearchEngine.BING->"insightsToken";ImageSearchEngine.YANDEX->"cbir_id";ImageSearchEngine.GOOGLE_LENS->if(original.queryParameter("vsrid")!=null)"vsrid"else "p";ImageSearchEngine.SAUCENAO->return null}
            if(original.queryParameter(key)!=next.queryParameter(key))return null
            return next.toString().takeIf {it.length<=16*1024}
        }
        private fun candidate(raw: String,base: String): String? {
            val value=Parser.unescapeEntities(raw,false).replace("\\u0026","&").replace("\\u003d","=").replace("\\u003a",":").replace("\\/","/")
            val resolved=base.toHttpUrlOrNull()?.resolve(value) ?: value.toHttpUrlOrNull() ?: return null
            val unwrapped=if(providerHost(resolved.host))listOf("imgrefurl","mediaurl","purl","url","q").firstNotNullOfOrNull {resolved.queryParameter(it)?.takeIf {v->v.startsWith("https://")||v.startsWith("http://")}}?.toHttpUrlOrNull() ?: return null else resolved
            if(unwrapped.scheme !in setOf("http","https")||unwrapped.username.isNotEmpty()||unwrapped.password.isNotEmpty()||providerHost(unwrapped.host))return null
            if(unwrapped.host=="localhost"||unwrapped.host.endsWith(".local")||unwrapped.host.contains(':')||unwrapped.host.matches(Regex("[0-9.]+")))return null
            if(unwrapped.encodedPath.lowercase().matches(Regex(".*\\.(?:png|jpe?g|gif|webp|svg|ico|css|js|woff2?)(?:/)?$")))return null
            return unwrapped.newBuilder().fragment(null).build().toString().takeIf {it.length<=8192}
        }
        internal fun extract(engine: ImageSearchEngine,payload: Payload): ImageSearchLinks {
            val doc=Jsoup.parse(payload.text,payload.finalUrl)
            val text=doc.body().text()
            val path=payload.finalUrl.toHttpUrlOrNull()?.encodedPath.orEmpty()
            if(path.startsWith("/sorry")||path.startsWith("/showcaptcha")||doc.selectFirst("form[action*=/sorry],#captcha,.CheckboxCaptcha,.SmartCaptcha,#challenge-running,#cf-error-details")!=null||
                Regex("unusual traffic|verify (?:that )?you are human|checking your browser|are you a robot|confirm you.re not a robot",RegexOption.IGNORE_CASE).containsMatchIn(text))
                throw SourceFailure(HealthStatus.CHALLENGE,"${engine.label} requires a human challenge on this Tor connection")
            if(doc.selectFirst("form[action*=accounts.google.com]")!=null&&text.contains("Sign in",true))throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"${engine.label} requires sign in on this connection")
            if(engine==ImageSearchEngine.SAUCENAO&&Regex("(?:daily|short.?term|search) (?:search )?(?:limit|quota)|too many searches|search rate limit",RegexOption.IGNORE_CASE).containsMatchIn(text))throw SourceFailure(HealthStatus.RATE_LIMITED,"SauceNAO has reached its public search limit on this Tor connection")
            val links=linkedSetOf<String>()
            // A provider's footer or share buttons are not image matches. Read
            // only the site's actual image-result structures, never all links
            // or arbitrary URL strings embedded in scripts.
            if(resultUrl(engine,payload.finalUrl))when(engine) {
                ImageSearchEngine.BING->doc.select(".iusc[m]").filterNot {it.closest(".b_ad,.ads")!=null}.forEach {node->
                    val page=runCatching {JsonParser.parseString(node.attr("m")).asJsonObject.get("purl")?.asString}.getOrNull()
                    page?.let {candidate(it,payload.finalUrl)}?.let(links::add)
                }
                ImageSearchEngine.YANDEX->doc.select(".CbirSites-ItemTitle a[href],a.CbirSites-ItemDomain[href],.CbirSites-ItemTitle[href]").forEach {node->candidate(node.attr("href"),payload.finalUrl)?.let(links::add)}
                ImageSearchEngine.GOOGLE_LENS->doc.select(".Vd9M6 a[href],a.LBcIee[href],.G19kAf a[href]").filter {node->node.selectFirst("img")!=null||node.closest(".Vd9M6,.G19kAf")?.selectFirst("img")!=null}.forEach {node->candidate(node.attr("href"),payload.finalUrl)?.let(links::add)}
                ImageSearchEngine.SAUCENAO->doc.select(".result:has(table.resulttable):has(.resultimage img) .resultcontent a[href]").forEach {node->candidate(node.attr("href"),payload.finalUrl)?.let(links::add)}
            }
            val noResults=resultUrl(engine,payload.finalUrl)&&Regex("no (?:matching |similar |image |visual )?(?:images|results|matches)(?: found)?|couldn.t find (?:any )?(?:matches|results)|we couldn.t find",RegexOption.IGNORE_CASE).containsMatchIn(text)
            if(links.isEmpty()&&!noResults)throw SourceFailure(HealthStatus.UNKNOWN,"${engine.label} did not expose readable image matches; its public page may have changed or require JavaScript")
            val yandexNext=if(engine==ImageSearchEngine.YANDEX)doc.selectFirst("a.CbirSitesList-MoreButton[href]")?.attr("href")
                ?: doc.selectFirst(".page-layout_page_cbir-sites.serp-controller[data-bem]")?.let {node->runCatching {JsonParser.parseString(node.attr("data-bem")).asJsonObject.getAsJsonObject("serp-controller").get("nextPageUrl")?.asString}.getOrNull()}else null
            val next=continuation(engine,payload.finalUrl,yandexNext)
                ?: continuation(engine,payload.finalUrl,doc.select("a[rel=next],a[aria-label]").firstOrNull {it.attr("rel")=="next"||it.attr("aria-label").lowercase() in setOf("next","next page")}?.attr("href"))
            val notice=when {
                links.isEmpty()->"${engine.label} found no public image matches"
                engine==ImageSearchEngine.SAUCENAO->"Includes partial and historic indexes; matches can be visually similar."
                else->null
            }
            return ImageSearchLinks(links.take(100),next,notice)
        }
    }
}
