package app.antisocial.core

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Link discovery, not a substitute for a site's posts or account search API. */
class PublicSearchAdapter(override val source: Source,private val gateway: NetworkGateway,private val routes: DarwinRoutes): DataAdapter {
    override val capabilities=setOf(Capability.SEARCH)
    override suspend fun fetch(target: Target,cursor: String?): Page {
        require(target.kind==TargetKind.SEARCH) {"Public index discovery requires a search"}
        return if(target.platform in setOf(Platform.FACEBOOK,Platform.INSTAGRAM,Platform.TIKTOK)&&SearchScopes.from(target)==null)indexedPosts(target,cursor)else fetchQuery(target,query(target),cursor)
    }
    /** Independent entity lookup through this already-selected index. The caller
     * must hydrate destinations before showing account/community metadata. */
    override suspend fun directory(target: Target,cursor: String?): Page {
        require(target.platform==source.platform&&target.kind==TargetKind.SEARCH&&SearchScopes.from(target)==null){"Entity discovery requires an unscoped search on the selected platform"}
        val query=directoryQuery(target)
        val binding=java.security.MessageDigest.getInstance("SHA-256").digest(("directory\n"+source.key+"\n"+target.canonicalUrl+"\n"+query).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        val token=cursor?.let {value->
            require(value.length<=MAX_INDEX_CURSOR&&value.startsWith(DIRECTORY_PREFIX)){"Invalid entity-directory continuation"}
            val saved=runCatching {JsonParser.parseString(value.removePrefix(DIRECTORY_PREFIX)).asJsonObject}.getOrNull()
            require(saved!=null&&saved.keySet()==setOf("version","binding","cursor")&&saved["version"]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asString=="1"&&saved["binding"]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString==binding&&saved["cursor"]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString?.isNotBlank()==true){"Entity continuation changed its selected source or query"}
            saved["cursor"].asString.also {request(query,it)}
        }
        val page=fetchQuery(target,query,token,entitiesOnly=true)
        val next=page.next?.let {value->DIRECTORY_PREFIX+JsonObject().apply {addProperty("version",1);addProperty("binding",binding);addProperty("cursor",value)}}?.takeIf {it.length<=MAX_INDEX_CURSOR}
        return page.copy(posts=emptyList(),profile=null,accounts=emptyList(),collections=page.collections.filter {entityAllowed(it.target)},next=next)
    }
    private data class SearchRequest(val url: String,val fields: Map<String,String>?=null)
    private fun request(query: String,cursor: String?): SearchRequest {
        val path=when(source.frontend){"bing-rss"->"/search?format=rss&q=";"duckduckgo"->"/html/?q=";else->"/search.php?q="}
        return if(cursor==null)SearchRequest(source.root.trimEnd('/')+path+DarwinRoutes.encode(query))
        else if(source.frontend=="duckduckgo") {
            require(cursor.length<=24*1024&&cursor.startsWith("search-form:")){"Invalid search continuation"}
            val form=Gson().fromJson(cursor.removePrefix("search-form:"),SearchForm::class.java)
            val url=form.url.toHttpUrlOrNull() ?: error("Invalid search continuation URL")
            require(DarwinRoutes.matches(URI(form.url),source.root)&&url.encodedPath=="/html/"&&url.query==null&&url.username.isEmpty()&&url.password.isEmpty()){ "Search continuation left the selected provider" }
            require(form.fields.keys.all {it in formFields}&&form.fields["q"]==query&&form.fields["s"]?.toIntOrNull() in 1..1000){"Search continuation changed the query"}
            SearchRequest(form.url,form.fields)
        }else {
            val url=cursor.toHttpUrlOrNull()
            val expected=(source.root.trimEnd('/')+path+DarwinRoutes.encode(query)).toHttpUrlOrNull()
            require(cursor.length<=8192&&url!=null&&expected!=null&&url.username.isEmpty()&&url.password.isEmpty()&&url.encodedPath==expected.encodedPath&&DarwinRoutes.matches(URI(cursor),source.root)&&DarwinRoutes.queryValue(URI(cursor),"q")==query){"Search continuation left the selected query or provider"}
            SearchRequest(cursor)
        }
    }
    private suspend fun fetchQuery(target: Target,query: String,cursor: String?,entitiesOnly: Boolean=false): Page {
        val request=request(query,cursor)
        val payload=if(request.fields==null)gateway.text(request.url,target.canonicalUrl)else gateway.form(request.url,request.fields,target.canonicalUrl)
        return try {Extraction.parse(payload,target,source,routes,publicSearchQuery=query,publicSearchEntitiesOnly=entitiesOnly)}catch(e: SourceFailure) {
            // The HTML endpoint sometimes returns its own HTTP-200 server-error
            // page. Retry once using its ordinary form, with the same provider,
            // query and protected route. Challenges and refusals are not retried.
            if(cursor!=null||source.frontend!="duckduckgo"||e.status!=HealthStatus.UNAVAILABLE||!e.message.orEmpty().startsWith("DuckDuckGo returned a server error"))throw e
            Extraction.parse(gateway.form(source.root.trimEnd('/')+"/html/",mapOf("q" to query),target.canonicalUrl),target,source,routes,publicSearchQuery=query,publicSearchEntitiesOnly=entitiesOnly)
        }
    }
    private data class IndexLane(val index: Int,val cursor: String?=null,val attempts: Int=0)
    private data class LaneResult(val lane: IndexLane,val result: Result<Page>)
    /** Posts and page profiles compete for the same small public-index result window.
     * Try a post-oriented query alongside the broad query, through the selected
     * provider only. A fast post result need not wait for its slower companion;
     * profiles alone must not keep cancelling a slower post-oriented lane. */
    private suspend fun indexedPosts(target: Target,cursor: String?): Page=coroutineScope {
        val queries=postQueries(target)
        val binding=java.security.MessageDigest.getInstance("SHA-256").digest((source.key+"\n"+target.canonicalUrl+"\n"+queries.joinToString("\n")).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        val lanes=if(cursor==null)queries.indices.map {IndexLane(it)}else readPlan(cursor,binding,queries)
        // Validate every lane before launching any request. A forged second lane
        // must not cause the first lane to disclose a query as a side effect.
        lanes.forEach {request(queries[it.index],it.cursor)}
        val replies=Channel<LaneResult>(lanes.size)
        val completed=linkedMapOf<Int,LaneResult>()
        val jobs=lanes.map {lane->launch {
            val result=try {Result.success(withTimeout(INDEX_LANE_TIMEOUT){fetchQuery(target,queries[lane.index],lane.cursor)})}
            catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();Result.failure(SourceFailure(HealthStatus.UNAVAILABLE,"The selected public search index timed out"))}
            catch(e: Exception){Result.failure(e)}
            replies.send(LaneResult(lane,result))
        }}
        var useful=false;var refused=false
        try {
            while(completed.size<lanes.size) {
                val reply=if(useful)withTimeoutOrNull(INDEX_COMPANION_GRACE){replies.receive()}else replies.receive()
                if(reply==null)break
                completed[reply.lane.index]=reply
                useful=useful||reply.result.getOrNull()?.collections?.any {it.target.kind==TargetKind.POST}==true
                if((reply.result.exceptionOrNull() as? SourceFailure)?.status in setOf(HealthStatus.CHALLENGE,HealthStatus.RATE_LIMITED,HealthStatus.LOGIN_REQUIRED)) {refused=true;break}
            }
        }finally {jobs.forEach {it.cancel()};jobs.joinAll();replies.cancel()}
        val pages=completed.values.mapNotNull {it.result.getOrNull()}
        if(pages.isEmpty())throw (completed.values.mapNotNull {it.result.exceptionOrNull()}.let {failures->
            failures.firstOrNull {(it as? SourceFailure)?.status in setOf(HealthStatus.CHALLENGE,HealthStatus.RATE_LIMITED,HealthStatus.LOGIN_REQUIRED)} ?: failures.firstOrNull()
        } ?: SourceFailure(HealthStatus.EMPTY,"The selected public search index returned no readable destinations"))
        val remaining=if(refused)emptyList()else lanes.mapNotNull {lane->
            val result=completed[lane.index]?.result
            val attempts=lane.attempts+1
            if(attempts>=MAX_INDEX_ATTEMPTS)null
            else if(result?.isSuccess==true)result.getOrThrow().next?.takeIf {it!=lane.cursor}?.let {IndexLane(lane.index,it,attempts)}
            else IndexLane(lane.index,lane.cursor,attempts)
        }
        var encodable=remaining
        var next=encodePlan(binding,encodable)
        while(next!=null&&next.length>MAX_INDEX_CURSOR) {
            encodable=encodable.sortedBy {it.cursor.orEmpty().length}.dropLast(1)
            next=encodePlan(binding,encodable)
        }
        val results=pages.flatMap {it.collections}.distinctBy {ContentIdentity.key(it.target.platform,it.target.canonicalUrl)}
            .sortedBy {if(it.target.kind==TargetKind.POST)0 else 1}.take(60)
        val partial=refused||completed.values.any {it.result.isFailure}||remaining.size!=encodable.size
        Page(emptyList(),next=next,collections=results,provenance=pages.firstOrNull {it.collections.isNotEmpty()}?.provenance ?: pages.first().provenance,
            notice=if(partial)"Some public-index results were unavailable; any retrieved destinations are shown."else null)
    }
    private fun encodePlan(binding: String,lanes: List<IndexLane>): String? {
        if(lanes.isEmpty())return null
        return INDEX_PREFIX+JsonObject().apply {
            addProperty("version",1);addProperty("binding",binding)
            add("lanes",com.google.gson.JsonArray().apply {lanes.forEach {lane->add(JsonObject().apply {
                addProperty("index",lane.index);addProperty("attempts",lane.attempts)
                if(lane.cursor==null)add("cursor",com.google.gson.JsonNull.INSTANCE)else addProperty("cursor",lane.cursor)
            })}})
        }
    }
    private fun readPlan(value: String,binding: String,queries: List<String>): List<IndexLane> {
        require(value.length<=MAX_INDEX_CURSOR&&value.startsWith(INDEX_PREFIX)){"Invalid public-index continuation; refresh this search"}
        val saved=runCatching {JsonParser.parseString(value.removePrefix(INDEX_PREFIX)).asJsonObject}.getOrNull()
        fun integer(o: JsonObject,name: String)=o[name]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asString?.toIntOrNull()
        require(saved!=null&&saved.keySet()==setOf("version","binding","lanes")&&integer(saved,"version")==1&&saved["binding"]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString==binding&&saved["lanes"]?.isJsonArray==true){"Search continuation changed the selected query or provider"}
        val entries=saved.getAsJsonArray("lanes")
        require(entries.size() in 1..queries.size){"Invalid public-index continuation"}
        val lanes=entries.map {value->
            val lane=value.takeIf {it.isJsonObject}?.asJsonObject ?: throw IllegalArgumentException("Invalid public-index continuation")
            val index=integer(lane,"index");val attempts=integer(lane,"attempts");val token=lane["cursor"]
            require(lane.keySet()==setOf("index","attempts","cursor")&&index!=null&&index in queries.indices&&attempts!=null&&attempts in 1 until MAX_INDEX_ATTEMPTS&&token!=null&&(token.isJsonNull||token.isJsonPrimitive&&token.asJsonPrimitive.isString)){"Invalid public-index continuation"}
            IndexLane(index,if(token.isJsonNull)null else token.asString,attempts)
        }
        require(lanes.map {it.index}.distinct().size==lanes.size){"Duplicate public-index continuation"}
        return lanes
    }
    override suspend fun feed(target: Target): Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"A search index does not provide a social feed")
    override fun sentinel(capability: Capability)=routes.search(source.platform,"space")
    companion object {
        val frontends=setOf("librey","bing-rss","duckduckgo")
        private const val INDEX_PREFIX="public-index:"
        private const val DIRECTORY_PREFIX="public-directory:"
        private const val MAX_INDEX_CURSOR=24*1024
        private const val MAX_INDEX_ATTEMPTS=4
        private const val INDEX_LANE_TIMEOUT=10_000L
        private const val INDEX_COMPANION_GRACE=200L
        internal fun facebookQueries(target: Target): List<String> {
            require(target.platform==Platform.FACEBOOK&&SearchScopes.from(target)==null)
            return listOf("site:facebook.com inurl:posts ${target.identity}",query(target)).distinct()
        }
        internal fun postQueries(target: Target): List<String> {
            if(SearchScopes.from(target)!=null)return listOf(query(target))
            return when(target.platform) {
                Platform.FACEBOOK->facebookQueries(target)
                Platform.INSTAGRAM->listOf("site:instagram.com/p/ ${target.identity}","site:instagram.com/reel/ ${target.identity}",query(target))
                Platform.TIKTOK->listOf(query(target),"site:tiktok.com inurl:photo ${target.identity}","site:tiktok.com ${target.identity}")
                else->listOf(query(target))
            }.distinct()
        }
        fun query(target: Target)=SearchScopes.publicIndexQuery(target) ?: "site:${target.platform.searchDomain} "+when(target.platform) {
            Platform.BLUESKY,Platform.THREADS->"inurl:post "
            Platform.TIKTOK->"inurl:video "
            Platform.TIEBA->"inurl:/p/ "
            else->""
        }+target.identity
        fun directoryQuery(target: Target): String {
            require(target.kind==TargetKind.SEARCH&&target.platform!=Platform.RSS&&SearchScopes.from(target)==null){"Entity discovery requires an unscoped social search"}
            val domain=if(target.platform in setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY))target.canonicalUrl.toHttpUrlOrNull()?.host ?: target.platform.searchDomain else target.platform.searchDomain
            val hint=when(target.platform) {
                Platform.REDDIT->"(inurl:/r/ OR inurl:/user/ OR inurl:/u/) -inurl:/comments/ "
                Platform.LEMMY->"(inurl:/c/ OR inurl:/u/) -inurl:/post/ -inurl:/comment/ "
                Platform.BLUESKY->"inurl:/profile/ -inurl:/post/ "
                Platform.X->"-inurl:/status/ -inurl:/statuses/ "
                Platform.INSTAGRAM->"-inurl:/p/ -inurl:/reel/ -inurl:/explore/ "
                Platform.THREADS->"-inurl:/post/ -inurl:/t/ "
                Platform.TIKTOK->"-inurl:/video/ -inurl:/photo/ "
                Platform.FACEBOOK->"-inurl:/posts/ -inurl:/videos/ -inurl:/reel/ -inurl:/photos/ "
                Platform.PINTEREST->"-inurl:/pin/ "
                Platform.TIEBA->"inurl:/f "
                else->""
            }
            return "site:$domain $hint${target.identity}"
        }
        private val xUsername=Regex("[A-Za-z0-9_]{1,15}")
        private val xReserved=setOf("home","search","explore","settings","messages","login","signup","i")
        private data class SearchForm(val url: String,val fields: Map<String,String>)
        private val formFields=setOf("q","s","nextParams","v","o","dc","api","vqd","kl","df")
        /** Jsoup absUrl deliberately removes URL user information. Check the
         * supplied navigation address before resolving it, so a credentialed
         * form cannot acquire a valid-looking continuation after sanitizing. */
        private fun navigationUrl(element: Element,attribute: String): String?=runCatching {
            val supplied=URI(element.attr(attribute))
            val base=URI(element.baseUri())
            if(supplied.rawUserInfo!=null||base.rawUserInfo!=null)null else element.absUrl(attribute).takeIf {it.isNotBlank()}
        }.getOrNull()
        fun profileAllowed(target: Target): Boolean {
            val parts=target.identity.trim('/').split('/')
            return when(target.platform) {
                Platform.X->parts.size==1&&target.identity.matches(xUsername)&&target.identity.lowercase() !in xReserved
                Platform.BLUESKY->parts.size==2&&parts[0]=="profile"&&parts[1].isNotBlank()
                Platform.INSTAGRAM,Platform.PINTEREST->parts.size==1&&parts[0].lowercase() !in setOf("accounts","login","signup","explore","ideas","search","about","business","developers","settings")
                Platform.TIKTOK,Platform.THREADS->parts.size==1&&parts[0].startsWith('@')&&parts[0].length>1
                Platform.REDDIT->parts.size==2&&parts[0] in setOf("user","u")
                Platform.TUMBLR->parts.size==1&&parts[0].isNotBlank()&&parts[0] !in setOf("explore","dashboard","login","register","about")
                Platform.IFUNNY->parts.size==2&&parts[0]=="user"
                Platform.YOUTUBE->YouTubeUrls.recognize(target.canonicalUrl)?.kind==TargetKind.PROFILE
                Platform.TIEBA,Platform.RSS->false
                Platform.FACEBOOK->parts.size==1&&parts[0].matches(Regex("[A-Za-z0-9.]{1,100}"))&&parts[0].lowercase() !in setOf("login","login.php","home.php","watch","reel","reels","marketplace","groups","pages","people","events","gaming","search","share","sharer.php","help","settings","privacy","policies","ads","business","photo.php","story.php","permalink.php")||parts.size==3&&parts[0]=="people"&&parts[1].isNotBlank()&&parts[2].matches(Regex("[0-9]{1,40}|pfbid[A-Za-z0-9]{1,195}"))
                Platform.TRUTH_SOCIAL->parts.size==1&&parts[0].startsWith('@')&&parts[0].length>1
                Platform.MASTODON,Platform.MISSKEY->runCatching {URI(target.canonicalUrl).path.trim('/').let {it.startsWith('@')&&!it.contains('/')}}.getOrDefault(false)
                Platform.PIXELFED->runCatching {URI(target.canonicalUrl).path.trim('/').let {it.isNotBlank()&&!it.contains('/')&&it.lowercase() !in setOf("login","register","explore","discover","search","about","settings","privacy","terms","auth","i","api")}}.getOrDefault(false)
                Platform.LEMMY->runCatching {URI(target.canonicalUrl).path.trim('/').split('/').let {it.size==2&&it[0]=="u"&&it[1].isNotBlank()}}.getOrDefault(false)
                Platform.GETTR->parts.size==2&&parts[0]=="user"&&parts[1].isNotBlank()
                Platform.GAB,Platform.TELEGRAM->parts.size==1&&parts[0].matches(Regex("[A-Za-z0-9_.-]{1,100}"))&&parts[0].lowercase() !in setOf("home","explore","search","login","settings","help","share","joinchat","addstickers","proxy","socks")
                Platform.LINKEDIN->parts.size==2&&parts[0] in setOf("company","in","school")&&parts[1].matches(Regex("[A-Za-z0-9_-]{1,160}"))
                Platform.SNAPCHAT->parts.size==2&&parts[0]=="add"&&parts[1].matches(Regex("[A-Za-z0-9_.-]{1,100}"))||parts.size==1&&parts[0].startsWith('@')&&parts[0].removePrefix("@").matches(Regex("[A-Za-z0-9_.-]{1,100}"))
            }
        }
        fun communityAllowed(target: Target): Boolean {
            if(target.kind!=TargetKind.COMMUNITY)return false
            val url=target.canonicalUrl.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()} ?: return false
            val parts=url.pathSegments.filter(String::isNotBlank)
            return when(target.platform) {
                Platform.REDDIT->url.host=="www.reddit.com"&&parts.size==2&&parts[0]=="r"&&parts[1].matches(Regex("[A-Za-z0-9_]{2,100}"))&&parts[1].lowercase() !in setOf("all","popular","random","friends","mod")
                Platform.LEMMY->parts.size==2&&parts[0]=="c"&&parts[1].matches(Regex("[A-Za-z0-9_.-]{1,100}(?:@[A-Za-z0-9.-]+)?"))
                Platform.PINTEREST->url.host=="www.pinterest.com"&&parts.size==2&&parts.all {it.matches(Regex("[A-Za-z0-9_-]{1,160}"))}&&parts[0] !in setOf("pin","search","ideas","settings","business")
                Platform.TIEBA->url.host=="tieba.baidu.com"&&parts==listOf("f")&&url.queryParameter("kw")?.let {it.isNotBlank()&&it.length<=100&&!it.any(Char::isISOControl)}==true
                else->false
            }
        }
        fun entityAllowed(target: Target)=target.kind==TargetKind.PROFILE&&profileAllowed(target)||communityAllowed(target)
        fun entityKey(target: Target): String {
            var identity=ContentIdentity.key(target.platform,target.canonicalUrl)
            if(target.platform==Platform.REDDIT&&target.kind==TargetKind.PROFILE)identity=identity.replace(Regex("(?<=reddit\\.com)/u/"),"/user/")
            if(target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY)&&target.platform in setOf(Platform.X,Platform.BLUESKY,Platform.INSTAGRAM,Platform.REDDIT,Platform.TIKTOK,Platform.THREADS))identity=identity.lowercase(java.util.Locale.ROOT)
            return "${target.kind}:$identity"
        }
        private fun result(url: String,name: String,description: String,target: Target,routes: DarwinRoutes,entitiesOnly: Boolean=false): Collection? {
            val found=routes.recognize(url) ?: return null
            if(found.platform!=target.platform||!(entityAllowed(found)||!entitiesOnly&&found.kind==TargetKind.POST)||name.isBlank())return null
            if(SearchScopes.from(target)!=null&&!SearchScopes.resultAccepts(target,found))return null
            if(found.kind==TargetKind.PROFILE&&!profileAllowed(found))return null
            return Collection(found,name.take(300),detail=description.take(1000))
        }
        fun rss(body: String,target: Target,routes: DarwinRoutes,entitiesOnly: Boolean=false): Page {
            if(body.contains('\u0000')||Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b",RegexOption.IGNORE_CASE).containsMatchIn(body))throw SourceFailure(HealthStatus.UNSUPPORTED,"Feed declarations and external entities are not supported")
            val results=Jsoup.parse(body,"",Parser.xmlParser()).select("rss channel item").mapNotNull {node->
                result(node.selectFirst("link")?.text().orEmpty(),node.selectFirst("title")?.text().orEmpty(),Extraction.plain(node.selectFirst("description")?.text().orEmpty()),target,routes,entitiesOnly)
            }.distinctBy {if(it.target.kind==TargetKind.POST)ContentIdentity.key(it.target.platform,it.target.canonicalUrl)else entityKey(it.target)}.take(30)
            return Page(emptyList(),collections=results)
        }
        fun extract(document: Document,target: Target,routes: DarwinRoutes,searchQuery: String=query(target),entitiesOnly: Boolean=false): Page {
            if(document.body().text().contains("Unable to fallback to other instances",true))throw SourceFailure(HealthStatus.UNAVAILABLE,"The public search index could not query its upstream sources")
            val results=document.select(".text-result-wrapper").mapNotNull {node->
                val link=node.selectFirst("a[href]:has(h2)") ?: return@mapNotNull null
                val name=link.selectFirst("h2")?.text().orEmpty()
                result(link.absUrl("href"),name,node.selectFirst("span")?.text().orEmpty(),target,routes,entitiesOnly)
            }.distinctBy {if(it.target.kind==TargetKind.POST)ContentIdentity.key(it.target.platform,it.target.canonicalUrl)else entityKey(it.target)}.take(30)
            val query=searchQuery
            val next=document.select("a[href]").firstOrNull {it.attr("rel")=="next"||it.text().trim().equals("Next",true)||it.text().trim()=="→"}?.let {navigationUrl(it,"href")}?.takeIf {url->
                val base=document.location().toHttpUrlOrNull();val parsed=url.toHttpUrlOrNull()
                parsed?.isHttps==true&&parsed.host==base?.host&&parsed.port==base.port&&parsed.username.isEmpty()&&parsed.password.isEmpty()&&parsed.encodedPath==base.encodedPath&&DarwinRoutes.queryValue(URI(url),"q")==query
            }
            return Page(emptyList(),next=next,collections=results)
        }
        fun duckDuckGo(document: Document,target: Target,routes: DarwinRoutes,searchQuery: String=query(target),entitiesOnly: Boolean=false): Page {
            if(document.selectFirst("form#challenge-form,form#img-form,.anomaly-modal")!=null)throw SourceFailure(HealthStatus.CHALLENGE,"DuckDuckGo requires a human challenge on this connection. Try another provider in Settings.")
            val results=document.getElementsByClass("result").asSequence().filterNot {it.hasClass("result--ad")}.mapNotNull {node->
                val link=node.getElementsByClass("result__a").firstOrNull {it.normalName()=="a"&&it.hasAttr("href")} ?: return@mapNotNull null
                val raw=link.absUrl("href")
                val parsed=raw.toHttpUrlOrNull()
                val destination=if(parsed?.host=="duckduckgo.com"||parsed?.host?.endsWith(".duckduckgo.com")==true)parsed.queryParameter("uddg") ?: return@mapNotNull null else raw
                result(destination,link.text(),node.getElementsByClass("result__snippet").firstOrNull()?.text().orEmpty(),target,routes,entitiesOnly)
            }.distinctBy {if(it.target.kind==TargetKind.POST)ContentIdentity.key(it.target.platform,it.target.canonicalUrl)else entityKey(it.target)}.take(30).toList()
            if(results.isEmpty()) {
                val text=document.text()
                if(text.contains("bots use DuckDuckGo",true))throw SourceFailure(HealthStatus.CHALLENGE,"DuckDuckGo requires a human challenge on this connection. Try another provider in Settings.")
                if(text.contains("The server encountered an error",true))throw SourceFailure(HealthStatus.UNAVAILABLE,"DuckDuckGo returned a server error. Your search was not sent to another provider.")
            }
            val form=document.select("form").firstOrNull {node->node.select("input[type=submit]").any {it.attr("value").equals("Next",true)}||node.select("button").any {it.text().trim().equals("Next",true)}}
            val next=form?.let {node->
                val url=navigationUrl(node,"action")?.toHttpUrlOrNull()
                val base=document.location().toHttpUrlOrNull()
                val fields=node.select("input[type=hidden][name]").filter {it.attr("name") in formFields}.associate {it.attr("name") to it.attr("value")}
                val query=searchQuery
                if(url?.isHttps==true&&url.host==base?.host&&url.port==base.port&&url.username.isEmpty()&&url.password.isEmpty()&&url.encodedPath=="/html/"&&url.query==null&&fields["q"]==query&&fields["s"]?.toIntOrNull() in 1..1000&&fields.entries.sumOf {it.key.length+it.value.length}<16*1024)
                    "search-form:"+Gson().toJson(SearchForm(url.toString(),fields)) else null
            }
            return Page(emptyList(),next=next,collections=results)
        }
    }
}
