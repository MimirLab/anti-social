package app.antisocial.core

import com.google.gson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder

class DarwinRoutes(data: JsonObject, private val cleaner: UrlCleaner) {
    val sources: List<Source> = data.getAsJsonArray("sources").map { n -> val o=n.asJsonObject; Source(Platform.entries.first { it.id==o["platform"].asString },o["frontend"].asString,o["root"].asString,o["fallbackOnly"].asBoolean) }
    // Separate from social mirrors: these discover links through Darwin's public
    // search routes, and must never be recognized as alternate social URLs.
    val searchSources: List<Source> = data.getAsJsonArray("searchSources")?.map {n->val o=n.asJsonObject;Source(Platform.entries.first {it.id==o["platform"].asString},o["frontend"].asString,o["root"].asString,fallbackOnly=true)} ?: emptyList()
    private val sourceRoots=java.util.concurrent.ConcurrentHashMap(sources.filterNot {it.frontend=="piped"}.map {it to URI(it.root)}.groupBy {it.second.host.lowercase(java.util.Locale.ROOT)})
    /** Only already implemented web frontends are reversible social links.
     * Shared API/feed renderers must never become aliases for their first platform. */
    fun registerPathwayAliases(extra: List<Source>) {
        val supported=sources.filterNot {it.frontend=="piped"}.map {it.frontend}.toSet()
        extra.take(1024).filter {it.role==SourceRole.ALTERNATE&&it.frontend in supported}.forEach {source->
            val uri=runCatching {URI(source.root)}.getOrNull() ?: return@forEach
            val host=uri.host?.lowercase(java.util.Locale.ROOT) ?: return@forEach
            sourceRoots.compute(host){_,old->(old.orEmpty()+listOf(source to uri)).distinctBy {it.first.key}.sortedByDescending {it.second.path.orEmpty().length}}
        }
    }
    /** Presentation may reverse a known reader without parsing a social target.
     * Keep raw paths, identity queries and comment anchors; do not register shared
     * API/feed hosts or infer an alias from a hostname that merely looks familiar. */
    internal fun outwardAlias(url: URI): Pair<Source,URI>? {
        val host=url.host?.lowercase(java.util.Locale.ROOT) ?: return null
        fun defaultPort(uri: URI)=uri.port==-1||uri.scheme.equals("https",true)&&uri.port==443||uri.scheme.equals("http",true)&&uri.port==80
        val matches=sourceRoots[host].orEmpty().filter {(_,base)->
            val prefix=base.rawPath.orEmpty().trimEnd('/')
            val path=url.rawPath.orEmpty()
            (url.port==base.port||defaultPort(url)&&defaultPort(base))&&
                (prefix.isEmpty()||path==prefix||path.startsWith("$prefix/"))
        }
        val longest=matches.maxOfOrNull {it.second.rawPath.orEmpty().trimEnd('/').length} ?: return null
        return matches.filter {it.second.rawPath.orEmpty().trimEnd('/').length==longest}
            .distinctBy {Triple(it.first.platform,it.first.frontend,it.second.rawPath)}.singleOrNull()
    }
    private val services=data.getAsJsonObject("services")
    private val expanded=ExpandedRoutes()
    private val targetPatterns by lazy {Platform.entries.associateWith {p->services.getAsJsonObject(p.id)?.getAsJsonArray("targets")?.mapNotNull {runCatching {Regex(it.asString)}.getOrNull()}.orEmpty()}}
    /** Only explicit selected federation links or restored known records add a server. */
    fun register(target: Target) {expanded.register(target)}
    fun recognize(input: String,selectedPlatform: Platform?=null): Target? {
        val found=recognizeBase(input,selectedPlatform) ?: return null
        val raw=runCatching {URI(cleaner.clean(input.trim()))}.getOrNull() ?: return null
        val explicit=SearchScopes.from(found.copy(canonicalUrl=raw.toString()))
        val community=if(found.platform==Platform.REDDIT&&found.kind==TargetKind.SEARCH)Regex("^/r/([A-Za-z0-9_]+)/search/?$").matchEntire(raw.path.orEmpty())?.groupValues?.get(1)?.let {SearchScopes.normalize(found.platform,SearchScopeKind.COMMUNITY,it)}else null
        return (explicit ?: community)?.let {search(found.platform,found.identity,it)} ?: found
    }
    private fun recognizeBase(input: String,selectedPlatform: Platform?=null): Target? {
        val url=runCatching { URI(cleaner.clean(input.trim())) }.getOrNull() ?: return null
        if(url.scheme !in setOf("http","https") || url.host==null||url.userInfo!=null)return null
        YouTubeUrls.recognize(url.toString())?.let {return it}
        expanded.recognize(url)?.let {return it}
        val mirror=sourceRoots[url.host.lowercase(java.util.Locale.ROOT)]?.firstOrNull {(_,base)->matches(url,base)}
        // Public indexed Bluesky links also use its own web CDN hostname.
        val canonical=runCatching {if(mirror!=null) reverse(url,mirror.first,mirror.second)else if(url.host=="web-cdn.bsky.app")URI(url.scheme,url.userInfo,"bsky.app",url.port,url.path,url.query,url.fragment).toString()else url.toString()}.getOrNull() ?: return null
        if(mirror?.first?.platform==Platform.YOUTUBE)return YouTubeUrls.recognize(canonical)
        val platform=mirror?.first?.platform ?: if(url.host in setOf("vm.tiktok.com","vt.tiktok.com","m.tiktok.com"))Platform.TIKTOK else Platform.entries.firstOrNull { p -> targetPatterns.getValue(p).any {it.containsMatchIn(canonical)} } ?: return expanded.recognize(url,selectedPlatform)
        val u=runCatching {URI(canonical)}.getOrNull()?.takeIf {it.scheme in setOf("http","https")&&it.host!=null} ?: return null
        val path=u.path.orEmpty().trim('/')
        val pieces=path.split('/').filter { it.isNotBlank() }
        val mediaHost=u.host in setOf("pbs.twimg.com","video.twimg.com","i.redd.it","preview.redd.it","external-preview.redd.it","i.pinimg.com","media.tumblr.com","assets.tumblr.com","static.tumblr.com")||u.host.endsWith(".media.tumblr.com")
        val shortTikTok=platform==Platform.TIKTOK&&(u.host in setOf("vm.tiktok.com","vt.tiktok.com")||path.startsWith("t/"))
        val kind=when {
            mediaHost -> TargetKind.MEDIA
            shortTikTok -> TargetKind.POST
            platform==Platform.TIEBA&&pieces.firstOrNull()=="f"&&queryValue(u,"kw")!=null -> TargetKind.COMMUNITY
            platform==Platform.REDDIT&&u.host=="redd.it" -> TargetKind.POST
            platform==Platform.REDDIT&&pieces.size==3&&pieces[0]=="r"&&pieces[2]=="search" -> TargetKind.SEARCH
            platform==Platform.REDDIT && pieces.firstOrNull()=="r" && !pieces.contains("comments") -> TargetKind.COMMUNITY
            platform==Platform.PINTEREST && pieces.size==2 && pieces[0] !in setOf("pin","search","ideas") && !pieces[1].startsWith("_") -> TargetKind.COMMUNITY
            platform==Platform.TIKTOK&&pieces.size==3&&pieces[0].startsWith('@')&&pieces[1]=="photo" -> TargetKind.POST
            pieces.any { it in setOf("status","statuses","p","reel","video","comments","post","t","picture","meme","pin") } || platform==Platform.TIEBA && pieces.firstOrNull()=="p" || platform==Platform.TUMBLR&&pieces.getOrNull(1)?.toLongOrNull()!=null -> TargetKind.POST
            pieces.firstOrNull() in setOf("explore","tag","tags","tagged") -> TargetKind.TAG
            path.startsWith("search") || u.rawQuery?.contains("q=")==true -> TargetKind.SEARCH
            else -> TargetKind.PROFILE
        }
        val identity=when {kind==TargetKind.SEARCH -> queryValue(u,"q") ?: path.removePrefix("search/");platform==Platform.TIEBA && u.rawQuery!=null -> queryValue(u,"kw") ?: path;else -> if(path.isBlank()) "" else path }
        val normalized=if(mediaHost||shortTikTok||u.host in setOf("t.co","redd.it"))"https://"+u.host+u.rawPath.orEmpty()else if(platform==Platform.TUMBLR && u.host.endsWith(".tumblr.com") && u.host!="www.tumblr.com") {
            platform.origin+"/"+u.host.substringBefore(".tumblr.com")+"/"+path
        } else platform.origin+(if(path.isEmpty())"" else "/"+u.rawPath.orEmpty().trim('/'))
        return Target(platform,kind,identity,cleaner.clean(normalized+u.rawQuery?.let {"?$it"}.orEmpty()))
    }
    fun target(platform: Platform, value: String, kind: TargetKind = TargetKind.PROFILE): Target {
        if(platform==Platform.YOUTUBE)return YouTubeUrls.target(value,kind)
        if(platform==Platform.RSS)return RssSources.target(value)
        if(value.startsWith("http",true))return recognize(value,platform) ?: Target(platform,TargetKind.FEED,value,value)
        expanded.input(platform,value,kind)?.let {return it}
        val identity=value.trim().removePrefix("@").trim('/')
        if(value.trim().startsWith("#")) {
            val tag=identity.removePrefix("#")
            val path=when(platform) {Platform.INSTAGRAM->"/explore/tags/$tag";Platform.TUMBLR->"/tagged/$tag";Platform.TIKTOK->"/tag/$tag";else->"/search?q=${encode("#$tag")}"}
            return Target(platform,TargetKind.TAG,tag,platform.origin+path)
        }
        val path=when(platform) { Platform.BLUESKY -> "/profile/$identity"; Platform.REDDIT -> if(identity.startsWith("r/") || identity.startsWith("user/")) "/$identity" else if(identity.startsWith("u/"))"/user/${identity.removePrefix("u/")}"else if(value.trim().startsWith('@'))"/user/$identity"else "/r/$identity"; Platform.TIKTOK -> "/@$identity"; Platform.THREADS -> "/@$identity"; Platform.TIEBA -> "/f?kw=${encode(identity)}"; else -> "/$identity" }
        return recognize(platform.origin+path) ?: Target(platform,kind,identity,platform.origin+path)
    }
    fun search(platform: Platform, query: String,scope: SearchScope?=null): Target {
        require(platform!=Platform.RSS){"Search your subscriptions or add an RSS / Atom feed URL"}
        require(query.isNotBlank()) {"Enter a search term"}
        val q=encode(query.trim())
        val path=when(platform) {
            Platform.YOUTUBE -> "/results?search_query=$q"
            Platform.X -> "/search?f=tweets&q=$q"
            Platform.BLUESKY,Platform.INSTAGRAM -> "/search?q=$q"
            Platform.REDDIT -> "/search?q=$q&sort=new"
            Platform.TUMBLR -> "/search/${q.replace("+","%20") }"
            Platform.PINTEREST -> "/search/pins/?q=$q"
            else -> "/search?q=$q"
        }
        return SearchScopes.apply(Target(platform,TargetKind.SEARCH,query.trim(),platform.origin+path),scope)
    }
    fun openInput(platform: Platform, value: String, searching: Boolean,scope: SearchScope?=null): Target {
        val input=value.trim()
        require(input.isNotBlank()) {"Enter a search term, username or link"}
        return if(searching&&!input.startsWith("https://",true)&&!input.startsWith("http://",true)&&(scope!=null||!input.startsWith('@')&&!input.startsWith('#')))search(platform,input,scope)else target(platform,input)
    }
    fun convert(target: Target, source: Source): String {
        val network=SearchScopes.networkUrl(target)
        if(source.role==SourceRole.ORIGINAL)return network
        val u=URI(network);var path=u.rawPath.orEmpty();var query=u.rawQuery?.let { "?$it" } ?: ""; val root=source.root.trimEnd('/')
        when(source.frontend) {
            "binternet" -> {if(u.host=="i.pinimg.com")return "$root/image_proxy.php?url=${encode(target.canonicalUrl)}";if(target.kind==TargetKind.SEARCH)return "$root/search.php?q=${encode(target.identity)}"}
            "pinless" -> if(u.host=="i.pinimg.com")return "$root/image?url=${encode(target.canonicalUrl)}"
            "painterest" -> {if(u.host=="i.pinimg.com")return "$root/_/proxy?url=${encode(target.canonicalUrl)}";Regex("^/pin/[^/]+").find(path)?.let {return "$root${it.value}"}}
            "kittygram" -> if(path.startsWith("/explore/tags/"))path="/popular/"+path.removePrefix("/explore/tags/").trimEnd('/')
            "skyview" -> return "$root?url=${encode(network)}"
            "nitter" -> { path=path.replace("/tweets",""); if(u.host=="t.co")path="/t.co$path"; query=query.split('&').filterNot { it.trimStart('?').substringBefore('=') in setOf("s","t","ref_src","ref_url") }.joinToString("&").let { if(it.isNotEmpty()&&!it.startsWith('?')) "?$it" else it };
                if(u.host in setOf("pbs.twimg.com","video.twimg.com")) {
                    val format=Regex("(.*)\\?format=(.*)&(.*)").find(query)
                    val tail=if(format!=null)encode("${format.groupValues[1]}.${format.groupValues[2]}?${format.groupValues[3]}").replace("+","%20")else query
                    return "$root/pic$path$tail"
                }
                return "$root$path$query#m" }
            "redlib","libreddit" -> { if(u.host=="redd.it")path="/comments$path"; if(u.host=="i.redd.it") {path="/img$path";query=""}; if(u.host=="preview.redd.it")path="/preview/pre$path";if(u.host=="external-preview.redd.it")path="/preview/external-pre$path" }
            "priviblur" -> {path=when {
                u.host.startsWith("assets.")->"/tblr/assets$path"
                u.host.startsWith("static.")->"/tblr/static$path"
                Regex("^([0-9]+)\\.media\\.tumblr\\.com$").matches(u.host)->"/tblr/media/${u.host.substringBefore('.')}"+path
                u.host.endsWith(".tumblr.com")&&u.host!="www.tumblr.com"->"/${u.host.substringBefore(".tumblr.com")}"+path.removePrefix("/post")
                else->path.replace("/post/","/")
            }}
            "shoelace" -> Regex("^/(?:@?[^/]+/post|t)/([^/]+)").find(path)?.let { path="/t/${it.groupValues[1]}" }
            "ratAintTieba" -> query=query.split('&').filterNot { it.trimStart('?').startsWith("ie=") }.joinToString("&")
        }
        return "$root$path$query"+(if(source.frontend=="nitter") "" else u.rawFragment?.let { "#$it" }.orEmpty())
    }
    private fun reverse(url: URI, source: Source, base: URI): String {
        var path=url.rawPath.removePrefix(base.rawPath.trimEnd('/')); val q=url.rawQuery?.let { "?$it" }.orEmpty()
        if(source.frontend=="skyview") return url.rawQuery?.substringAfter("url=","")?.substringBefore('&')?.let { URLDecoder.decode(it,"UTF-8") }?.takeIf { it.startsWith("http") } ?: source.platform.origin
        if(source.frontend in setOf("binternet","pinless","painterest")&&path in setOf("/image_proxy.php","/image","/_/proxy"))queryValue(url,"url")?.takeIf {it.startsWith("https://i.pinimg.com/")}?.let {return it}
        if(source.frontend=="nitter"&&path.startsWith("/pic/")) {
            val media=URLDecoder.decode(path.removePrefix("/pic"),"UTF-8")
            val host=if(Regex("\\.(mp4|m3u8)(?:[?]|$)",RegexOption.IGNORE_CASE).containsMatchIn(media))"video.twimg.com"else "pbs.twimg.com"
            return "https://$host$media$q"
        }
        if(source.frontend=="nitter"&&path.startsWith("/t.co/"))return "https://t.co/"+path.removePrefix("/t.co/")+q
        if(source.frontend in setOf("redlib","libreddit")) {
            if(path.startsWith("/img/"))return "https://i.redd.it/"+path.removePrefix("/img/")+q
            if(path.startsWith("/preview/pre/"))return "https://preview.redd.it/"+path.removePrefix("/preview/pre/")+q
            if(path.startsWith("/preview/external-pre/"))return "https://external-preview.redd.it/"+path.removePrefix("/preview/external-pre/")+q
        }
        if(source.frontend=="priviblur") {
            Regex("^/tblr/media/([0-9]+)(/.*)$").find(path)?.let {return "https://${it.groupValues[1]}.media.tumblr.com${it.groupValues[2]}$q"}
            if(path.startsWith("/tblr/assets/"))return "https://assets.tumblr.com/"+path.removePrefix("/tblr/assets/")+q
            if(path.startsWith("/tblr/static/"))return "https://static.tumblr.com/"+path.removePrefix("/tblr/static/")+q
        }
        if(source.frontend=="kittygram"&&path.startsWith("/popular/"))path="/explore/tags/"+path.removePrefix("/popular/")
        if(source.frontend=="shoelace")path=path.replace(Regex("^/t/"),"/t/")
        return source.platform.origin+path+q
    }
    companion object {
        fun queryValue(url: URI, name: String): String?=url.rawQuery?.split('&')?.firstOrNull {it.substringBefore('=')==name}?.substringAfter('=')?.let {URLDecoder.decode(it,"UTF-8")}
        fun encode(v: String): String = URLEncoder.encode(v,"UTF-8")
        fun matches(url: URI, root: String): Boolean=matches(url,URI(root))
        private fun matches(url: URI,b: URI): Boolean {val p=b.path.trimEnd('/');return url.scheme.equals(b.scheme,true)&&url.host.equals(b.host,true)&&url.port==b.port&&(p.isEmpty()||url.path==p||url.path.startsWith("$p/"))}
    }
}
