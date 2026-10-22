/* AntiSocial, GPL-3.0-or-later. Adapted from Darwin's news-archive-routes,
 * publisher-lite-routes and scientific-routes. Donor hashes ship in the catalog.
 * Pure routing/identity code: this class never fetches or executes page code. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.URLEncoder

data class ArticleAccess(val route:String,val originalUrl:String,val retrievedUrl:String,
    val archivedAt:Long?=null,val version:String?=null,val license:String?=null,val retracted:Boolean=false)
internal data class ArticlePath(val url:String,val route:String,val version:String?=null,val license:String?=null,val retracted:Boolean=false,val pdf:Boolean=false)
internal data class ScientificArticle(val key:String,val doi:String?=null,val pmid:String?=null)

class DarwinArticleRoutes internal constructor(catalog:JsonObject) {
    private fun com.google.gson.JsonArray?.orEmpty()=this?.toList().orEmpty()
    constructor():this(JsonParser.parseString(requireNotNull(DarwinArticleRoutes::class.java.getResourceAsStream("/darwin-article-routes.json"))
        .bufferedReader().use {it.readText()}).asJsonObject)
    private val mediumSources=catalog.getAsJsonArray("mediumSources").take(128).mapNotNull {row->runCatching {
        val x=row.asJsonObject;val frontend=x["frontend"].asString;val root=publicUrl(x["root"].asString)
        if(frontend !in setOf("scribe","libMedium","small","freedium")||root==null||root.query!=null||root.fragment!=null)null
        else frontend to root.toString().trimEnd('/')
    }.getOrNull()}.distinct()
    init {require(catalog["schema"]?.asInt==1&&catalog["donor"]?.asString=="Darwin")}

    internal fun publicUrl(raw:String):HttpUrl? {
        if(raw.length !in 1..4096||raw.any {it<=' '||it=='\u007f'||it=='\\'})return null
        val u=raw.toHttpUrlOrNull() ?: return null
        if(!u.isHttps||u.port!=443||u.username.isNotEmpty()||u.password.isNotEmpty()||!u.host.contains('.')||
            u.host.contains(':')||u.host.matches(Regex("[0-9.]+"))||Regex("(?:^|\\.)(?:local|localhost|internal|test|invalid)$").containsMatchIn(u.host)||
            u.queryParameterNames.any {SENSITIVE.containsMatchIn(it)})return null
        return u
    }
    /** Never send account, credential-bearing or asset URLs to another service. */
    internal fun recoverable(raw:String):Boolean=publicUrl(raw)?.let {u->
        u.encodedPath!="/"&&!PRIVATE_PATH.containsMatchIn(u.encodedPath)&&!ASSET.containsMatchIn(u.encodedPath)
    }==true
    fun canRead(raw:String):Boolean=recoverable(raw)||publicUrl(raw)?.let {it.encodedPath.endsWith(".pdf",true)&&!PRIVATE_PATH.containsMatchIn(it.encodedPath)}==true
    internal fun identity(raw:String):String?=publicUrl(raw)?.let {u->u.newBuilder().fragment(null).apply {
        u.queryParameterNames.filter {it.startsWith("utm_",true)||it in TRACKING}.forEach(::removeAllQueryParameters)
    }.build().toString()}
    internal fun sameArticle(a:String,b:String):Boolean {
        // Historical HTTP originals remain inside HTTPS archive replays. This
        // comparison never upgrades an arbitrary repository download/fetch.
        fun comparable(raw:String):HttpUrl? {
            val u=raw.toHttpUrlOrNull() ?: return null
            val value=if(u.scheme=="http"&&u.port==80)u.newBuilder().scheme("https").port(443).build().toString()else raw
            return identity(value)?.toHttpUrlOrNull()
        }
        val x=comparable(a) ?: return false;val y=comparable(b) ?: return false
        return x.host.removePrefix("www.")==y.host.removePrefix("www.")&&x.encodedPath.trimEnd('/')==y.encodedPath.trimEnd('/')&&
            x.queryParameterNames==y.queryParameterNames&&x.queryParameterNames.all {x.queryParameterValues(it)==y.queryParameterValues(it)}
    }
    internal fun publisherEdition(raw:String):ArticlePath? {
        val u=publicUrl(raw) ?: return null;val p=u.encodedPath;val target=when(u.host) {
            "npr.org","www.npr.org"->Regex("^/(?:[0-9]{4}/[0-9]{2}/[0-9]{2}/)?((?:nx-s1-|g-s1-)[0-9]+|[0-9]{6,})(?:/[^/]+)?/?$").matchEntire(p)?.groupValues?.get(1)?.let {"https://text.npr.org/$it"}
            "cbc.ca","www.cbc.ca"->Regex("^/(?:news/(?:[a-z0-9-]+/)*[^/]*-)?([19]\\.[0-9]{5,})/?$").matchEntire(p)?.groupValues?.get(1)?.let {"https://www.cbc.ca/lite/story/$it"}
            "cnn.com","www.cnn.com","edition.cnn.com"->p.takeIf {Regex("^/[0-9]{4}/[0-9]{2}/[0-9]{2}/[a-z-]+/[a-z0-9-]+(?:/index\\.html)?/?$").matches(it)&&!Regex("/(?:live-news|videos|interactive|gallery|galleries)/").containsMatchIn(it)}?.let {"https://lite.cnn.com"+it.removeSuffix("/index.html")}
            else->null
        }
        return target?.let {ArticlePath(it,"Publisher text edition")}
    }
    internal fun medium(raw:String):List<ArticlePath> {
        val u=publicUrl(raw)?.takeIf {recoverable(raw)} ?: return emptyList()
        if(u.host!="medium.com"&&!u.host.endsWith(".medium.com")&&u.host !in MEDIUM_HOSTS)return emptyList()
        // Account/profile and index pages are not recoverable article identifiers.
        if(!Regex("(?:-|/)[a-fA-F0-9]{12}(?:/|$)").containsMatchIn(u.encodedPath))return emptyList()
        val sub=u.host.removeSuffix(".medium.com").takeIf {u.host.endsWith(".medium.com")&&it !in setOf("www","link")&&!it.startsWith("cdn-images-")}
        val path=(sub?.let {"/@$it"}.orEmpty())+u.encodedPath+(u.encodedQuery?.let {"?$it"}.orEmpty())
        return mediumSources.map {(frontend,root)->ArticlePath(if(frontend=="freedium")"$root/"+URLEncoder.encode(identity(raw),"UTF-8").replace("+","%20")else root+path,frontend)}
    }
    internal fun preferArchives(raw:String):Boolean {
        val u=publicUrl(raw)?.takeIf {recoverable(raw)} ?: return false
        val h=u.host.removePrefix("www.")
        return h in NEWS_HOSTS||NEWS_PATHS[h]?.containsMatchIn(u.encodedPath)==true
    }
    internal fun scientific(raw:String):ScientificArticle? {
        val u=publicUrl(raw) ?: return null
        if(PRIVATE_PATH.containsMatchIn(u.encodedPath)||ASSET.containsMatchIn(u.encodedPath))return null
        val path=u.pathSegments.joinToString("/",prefix="/");val host=u.host
        if(host=="pubmed.ncbi.nlm.nih.gov")return Regex("^/([0-9]{1,9})/?$").matchEntire(path)?.groupValues?.get(1)?.let {ScientificArticle("pmid:$it",pmid=it)}
        val doi=when(host) {
            "doi.org","dx.doi.org"->path.removePrefix("/")
            "nature.com","www.nature.com"->Regex("^/articles/([a-zA-Z0-9][a-zA-Z0-9.-]*)/?$").matchEntire(path)?.groupValues?.get(1)?.let {"10.1038/$it"}
            "link.springer.com","link.springerlink.com"->Regex("^/article/(10\\..+?)/?$").matchEntire(path)?.groupValues?.get(1)
            "journals.plos.org"->u.queryParameter("id").takeIf {Regex("^/[^/]+/article/?$").matches(path)}
            in DOI_HOSTS->Regex("^/doi/(?:abs/|full/|epdf/)?(10\\..+?)/?$").matchEntire(path)?.groupValues?.get(1)
            else->null
        }
        return normalizeDoi(doi)?.let {ScientificArticle(it,doi=it)}
    }
    internal fun metadataPublisher(raw:String):Boolean {
        val u=publicUrl(raw) ?: return false
        return METADATA_PATHS[u.host]?.matches(u.encodedPath)==true
    }
    internal fun metadataDoi(html:String):ScientificArticle?=metadataDoi(Jsoup.parse(html))
    internal fun metadataDoi(document:org.jsoup.nodes.Document):ScientificArticle? {
        val values=document.select("meta[name]").filter {it.attr("name").lowercase() in setOf("citation_doi","dc.identifier","dc.identifier.doi","prism.doi")}
            .mapNotNull {normalizeDoi(it.attr("content"))}.take(21)
        return values.takeIf {it.size in 1..20}?.distinct()?.singleOrNull()?.let {ScientificArticle(it,doi=it)}
    }
    internal fun openAlex(root:JsonObject,doi:String):List<ArticlePath> {
        if(normalizeDoi(root["doi"]?.takeIf {it.isJsonPrimitive}?.asString)!=doi)return emptyList()
        val locations=listOfNotNull(root["best_oa_location"]?.takeIf {it.isJsonObject}?.asJsonObject)+root["locations"]?.takeIf {it.isJsonArray}?.asJsonArray.orEmpty().take(100).mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject}
        return locations.filter {it["is_oa"]?.toString()=="true"}.sortedBy {if(it["source"]?.takeIf {it.isJsonObject}?.asJsonObject?.get("type")?.asString=="repository")0 else 1}.flatMap {l->
            listOf("landing_page_url","pdf_url").mapNotNull {key->l[key]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString?.let(::destination)?.let {url->
                ArticlePath(url,"OpenAlex public copy",l["version"]?.takeIf {it.isJsonPrimitive}?.asString?.takeIf {it in setOf("publishedVersion","acceptedVersion","submittedVersion")},
                    l["license"]?.takeIf {it.isJsonPrimitive}?.asString?.take(80),root["is_retracted"]?.toString()=="true",key=="pdf_url")
            }}
        }.distinctBy {it.url}.take(12)
    }
    internal fun europePmc(root:JsonObject,article:ScientificArticle):List<ArticlePath> {
        val rows=root.getAsJsonObject("resultList")?.getAsJsonArray("result").orEmpty().take(10).mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject}
        val r=rows.firstOrNull {if(article.pmid!=null)it["id"]?.asString==article.pmid&&it["source"]?.asString=="MED"else normalizeDoi(it["doi"]?.asString)==article.doi} ?: return emptyList()
        val retracted=r.getAsJsonObject("pubTypeList")?.getAsJsonArray("pubType").orEmpty().any {it.isJsonPrimitive&&it.asString.contains("retracted publication",true)}
        val version=if(r["authMan"]?.asString=="Y")"acceptedVersion"else null
        val links=r.getAsJsonObject("fullTextUrlList")?.getAsJsonArray("fullTextUrl").orEmpty().take(40).mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject}.mapNotNull {l->
            if(l["availabilityCode"]?.asString !in setOf("F","OA")||l["documentStyle"]?.asString !in setOf("html","pdf"))null
            else destination(l["url"]?.asString)?.let {ArticlePath(it,"Europe PMC public copy",version,retracted=retracted,pdf=l["documentStyle"]?.asString=="pdf")}
        }
        val pmc=r["pmcid"]?.asString?.takeIf {Regex("PMC[0-9]+").matches(it)&&r["inPMC"]?.asString=="Y"&&r["isOpenAccess"]?.asString=="Y"}
        return (pmc?.let {listOf(ArticlePath("https://pmc.ncbi.nlm.nih.gov/articles/$it/","Europe PMC public copy",version,retracted=retracted))}.orEmpty()+links).distinctBy {it.url}.take(12)
    }
    internal fun pmidDoi(root:JsonObject,pmid:String):String?=runCatching {
        root.getAsJsonObject("resultList")?.getAsJsonArray("result").orEmpty().take(10).firstOrNull {
            it.isJsonObject&&it.asJsonObject["source"]?.asString=="MED"&&it.asJsonObject["id"]?.asString==pmid
        }?.asJsonObject?.get("doi")?.asString?.let(::normalizeDoi)
    }.getOrNull()
    private fun destination(raw:String?):String? {
        var s=raw ?: return null
        s=s.replace(Regex("^http://(arxiv\\.org|nrs\\.harvard\\.edu|www\\.ncbi\\.nlm\\.nih\\.gov)(?=/)",RegexOption.IGNORE_CASE),"https://$1")
        Regex("^https://europepmc.org/articles/PMC([0-9]+)(?:\\?.*)?$").matchEntire(s)?.let {s="https://pmc.ncbi.nlm.nih.gov/articles/PMC${it.groupValues[1]}/"}
        val u=publicUrl(s)?.takeIf {it.host !in setOf("doi.org","dx.doi.org")} ?: return null
        val old=Regex("^/pmc/articles/(?:PMC)?([0-9]+)/?$").matchEntire(u.encodedPath)
        return if(u.host=="www.ncbi.nlm.nih.gov"&&old!=null)"https://pmc.ncbi.nlm.nih.gov/articles/PMC${old.groupValues[1]}/"else u.toString()
    }
    companion object {
        internal fun normalizeDoi(raw:String?):String?=raw?.trim()?.replace(Regex("^https?://(?:dx\\.)?doi\\.org/",RegexOption.IGNORE_CASE),"")?.takeIf {it.length<=512&&Regex("10\\.[0-9]{4,9}/[^\\s<>?\"#]+").matches(it)&&it.none(Char::isISOControl)}?.lowercase()
        private val SENSITIVE=Regex("token|auth|key|session|signature|password|email|secret|access_code|article_code",RegexOption.IGNORE_CASE)
        private val PRIVATE_PATH=Regex("/(?:account|myaccount|accounts|login|logout|signin|sign-in|signup|sign-up|register|auth|oauth|subscribe|subscription|subscriptions|checkout|payment|billing|settings|preferences|profile|user|users|search|redirect|games|crosswords|interactive|live|newsletters|membership|abo|subscribe-now)(?:/|$)",RegexOption.IGNORE_CASE)
        private val ASSET=Regex("\\.(?:pdf|xml|json|txt|jpg|jpeg|png|gif|webp|svg|mp4|mp3|css|js|zip|epub)$",RegexOption.IGNORE_CASE)
        private val TRACKING=setOf("fbclid","gclid","dclid","msclkid","igshid","mc_cid","mc_eid")
        private val NEWS_HOSTS=setOf("nytimes.com","washingtonpost.com","wsj.com","ft.com","economist.com","bloomberg.com","theatlantic.com","newyorker.com","wired.com","businessinsider.com","telegraph.co.uk","latimes.com","bostonglobe.com","thetimes.com")
        private val NEWS_PATHS=mapOf("lemonde.fr" to "/article/[0-9]{4}/[0-9]{2}/[0-9]{2}/[^/]+\\.html$","spiegel.de" to "-a-(?:[a-f0-9-]{36}|[0-9]{5,})/?$","theverge.com" to "^/(?:[a-z-]+/)?(?:[0-9]{4}/[0-9]{1,2}/[0-9]{1,2}/)?[0-9]{5,}/[^/]+/?$","forbes.com" to "^/sites/[^/]+/[0-9]{4}/[0-9]{2}/[0-9]{2}/[^/]+/?$","404media.co" to "^/[a-z0-9]+(?:-[a-z0-9]+)+/?$","elpais.com" to "^/(?:[a-z-]+/){1,3}[0-9]{4}-[0-9]{2}-[0-9]{2}/[^/]+\\.html$","zeit.de" to "^/(?:[0-9]{4}/[0-9]{2}|[a-z-]+/[0-9]{4}-[0-9]{2})/[^/]+/?$","scmp.com" to "/article/[0-9]{6,}/[^/]+/?$","theglobeandmail.com" to "^/[^/]+(?:/[^/]+)*/article-[^/]+/?$","barrons.com" to "^/articles/[^/]+/?$","marketwatch.com" to "^/story/[^/]+/?$","statnews.com" to "^/[0-9]{4}/[0-9]{2}/[0-9]{2}/[^/]+/?$","newscientist.com" to "^/article/[0-9]{5,}-[^/]+/?$").mapValues {Regex(it.value)}
        private val MEDIUM_HOSTS=setOf("towardsdatascience.com","uxdesign.cc","uxplanet.org","betterprogramming.pub","aninjusticemag.com","betterhumans.pub","psiloveyou.xyz","entrepreneurshandbook.co","blog.coinbase.com","levelup.gitconnected.com","javascript.plainenglish.io","blog.bitsrc.io","itnext.io","codeburst.io","infosecwriteups.com","blog.devgenius.io","writingcooperative.com","proandroiddev.com","redfin.engineering")
        private val DOI_HOSTS=setOf("onlinelibrary.wiley.com","www.science.org","science.org","pubs.acs.org","www.tandfonline.com","journals.sagepub.com","www.pnas.org","pnas.org","dl.acm.org")
        private val METADATA_PATHS=mapOf("www.sciencedirect.com" to "^/science/article/(?:abs/)?pii/[A-Z0-9]{10,}/?$","ieeexplore.ieee.org" to "^/document/[0-9]+/?$","academic.oup.com" to "^/[^/]+/article(?:-abstract)?/(?:[^/]+/){2,4}[0-9]+/?$","www.cambridge.org" to "^/core/journals/[^/]+/article/(?:abs/)?[^/]+/[a-f0-9]{20,}/?$","www.jstor.org" to "^/stable/[0-9]+/?$").mapValues {Regex(it.value,RegexOption.IGNORE_CASE)}
    }
}
