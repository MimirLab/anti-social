/* AntiSocial — local search scope descriptors, GPL-3.0-or-later. */
package app.antisocial.core

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

enum class SearchScopeKind { ACCOUNT, COMMUNITY, TAG, BOARD, CHANNEL }
data class SearchScope(val kind: SearchScopeKind,val value: String)
data class SearchScopeOption(val kind: SearchScopeKind,val label: String,val hint: String)

/** A scope is part of a search's canonical identity, not another user preference.
 * The fragment stays local; readers translate it into supported query syntax.
 */
object SearchScopes {
    private const val marker="antisocial-scope="
    private val federated=setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY)
    private val tagPlatforms=setOf(Platform.X,Platform.BLUESKY,Platform.TUMBLR,Platform.TIKTOK,Platform.INSTAGRAM)
    fun options(platform: Platform): List<SearchScopeOption> = buildList {
        if(platform in setOf(Platform.RSS,Platform.YOUTUBE))return@buildList
        if(platform==Platform.TELEGRAM)add(SearchScopeOption(SearchScopeKind.CHANNEL,"Channel","Public channel username"))
        else if(platform!=Platform.TIEBA)add(SearchScopeOption(SearchScopeKind.ACCOUNT,when(platform){Platform.REDDIT->"Author";Platform.FACEBOOK->"Page";Platform.TUMBLR->"Blog";Platform.LINKEDIN->"Organization";else->"Account"},when(platform){Platform.LINKEDIN->"company/name or school/name";in federated->"username@server.example";else->"Public username"}))
        when(platform) {
            Platform.REDDIT->add(SearchScopeOption(SearchScopeKind.COMMUNITY,"Subreddit","r/Android"))
            Platform.LEMMY->add(SearchScopeOption(SearchScopeKind.COMMUNITY,"Community","community@server.example"))
            Platform.TIEBA->add(SearchScopeOption(SearchScopeKind.COMMUNITY,"Forum","Forum name"))
            Platform.PINTEREST->add(SearchScopeOption(SearchScopeKind.BOARD,"Board","username/board-name"))
            else->Unit
        }
        if(platform in tagPlatforms)add(SearchScopeOption(SearchScopeKind.TAG,"Hashtag","#topic"))
    }
    fun normalize(platform: Platform,kind: SearchScopeKind,input: String): SearchScope {
        require(options(platform).any {it.kind==kind}) {"This scope is not available for ${platform.label}"}
        val raw=input.trim()
        require(raw.isNotEmpty()&&raw.length<=300&&!raw.any {it.isISOControl()}) {"Enter a public ${options(platform).first {it.kind==kind}.label.lowercase(Locale.ROOT)}"}
        val value=when(kind) {
            SearchScopeKind.TAG->raw.removePrefix("#").also {require(it.length in 1..100&&it.matches(Regex("[\\p{L}\\p{N}_]+"))) {"Enter a hashtag without spaces"}}
            SearchScopeKind.BOARD->raw.trim('/').also {require(it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9_][A-Za-z0-9_-]{0,159}"))) {"Enter a board as username/board-name"}}
            SearchScopeKind.CHANNEL->raw.removePrefix("@").removePrefix("s/").trim('/').also {require(it.matches(Regex("[A-Za-z0-9_]{3,64}"))) {"Enter a public channel username"}}
            SearchScopeKind.COMMUNITY->when(platform) {
                Platform.TIEBA->raw.also {require(it.isNotBlank()&&it.length<=100&&!it.any {c->c in "<>#&?=/%\\"}) {"Enter a forum name"}}
                Platform.LEMMY->federatedName(platform,raw.removePrefix("!").removePrefix("c/"))
                Platform.REDDIT->raw.removePrefix("r/").trim('/').also {require(it.matches(Regex("[A-Za-z0-9_]{2,100}"))) {"Enter a subreddit name such as r/Android"}}
                else->error("Unsupported community")
            }
            SearchScopeKind.ACCOUNT->if(platform in federated)federatedName(platform,raw.removePrefix("@").removePrefix("u/"))else {
                val cleaned=raw.removePrefix("@").trim('/').let {when(platform){Platform.REDDIT->it.removePrefix("user/").removePrefix("u/");Platform.BLUESKY->it.removePrefix("profile/");Platform.GETTR,Platform.IFUNNY->it.removePrefix("user/");Platform.SNAPCHAT->it.removePrefix("add/");else->it}}
                val pattern=when(platform) {Platform.X->"[A-Za-z0-9_]{1,15}";Platform.LINKEDIN->"(?:company|school)/[A-Za-z0-9_-]{1,160}";Platform.BLUESKY->"(?:[A-Za-z0-9-]+\\.)+[A-Za-z0-9-]+|did:plc:[a-z0-9]{24}";else->"[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}"}
                require(cleaned.matches(Regex(pattern))) {if(platform==Platform.LINKEDIN)"Enter company/name or school/name"else "Enter a public username"}
                cleaned
            }
        }
        return SearchScope(kind,if(platform==Platform.TIEBA)value else value.lowercase(Locale.ROOT))
    }
    private fun federatedName(platform: Platform,input: String): String {
        val raw=input.removePrefix("@");val at=raw.lastIndexOf('@')
        val name=if(at<0)raw else raw.substring(0,at)
        val host=if(at<0)URI(platform.origin).host else raw.substring(at+1)
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}"))&&host.matches(Regex("(?:[A-Za-z0-9-]+\\.)+[A-Za-z0-9-]+(?::[0-9]{1,5})?"))) {"Enter username@server.example"}
        val parsed=runCatching {URI("https://$host")}.getOrNull()
        require(parsed?.host!=null&&parsed.userInfo==null&&parsed.port in -1..65535&&parsed.port!=0&&parsed.path.isNullOrEmpty()&&!host.substringBefore(':').matches(Regex("[0-9.]+"))&&!host.substringBefore(':').endsWith(".local",true)&&!host.substringBefore(':').endsWith(".localhost",true)) {"Enter a public server address"}
        return "$name@$host"
    }
    fun label(platform: Platform,scope: SearchScope?): String=when(scope?.kind) {
        null->"Everywhere"
        SearchScopeKind.TAG->"#${scope.value}"
        SearchScopeKind.BOARD->scope.value
        SearchScopeKind.CHANNEL->"@${scope.value}"
        SearchScopeKind.COMMUNITY->when(platform){Platform.REDDIT->"r/${scope.value}";Platform.LEMMY->"!${scope.value}";else->scope.value}
        SearchScopeKind.ACCOUNT->when(platform){Platform.LINKEDIN->scope.value;Platform.REDDIT->"u/${scope.value}";else->"@${scope.value}"}
    }
    fun from(target: Target): SearchScope? {
        val rawFragment=target.canonicalUrl.substringAfter('#',"")
        val fragment=try {URI(target.canonicalUrl).rawFragment}catch(_: Exception) {
            if(rawFragment.startsWith("antisocial-scope"))throw IllegalArgumentException("Invalid search scope")
            return null
        } ?: return null
        if(!fragment.startsWith("antisocial-scope"))return null
        require(fragment.startsWith(marker)&&fragment.length<=2048) {"Invalid search scope"}
        require(target.kind==TargetKind.SEARCH) {"A search scope requires a search"}
        val fields=fragment.split('&')
        require(fields.size==2&&fields[0].startsWith(marker)&&fields[1].startsWith("value=")) {"Invalid search scope"}
        val kind=runCatching {SearchScopeKind.valueOf(fields[0].removePrefix(marker))}.getOrNull() ?: throw IllegalArgumentException("Invalid search scope")
        val value=runCatching {URLDecoder.decode(fields[1].removePrefix("value="),"UTF-8")}.getOrNull() ?: throw IllegalArgumentException("Invalid search scope")
        return normalize(target.platform,kind,value)
    }
    fun apply(target: Target,scope: SearchScope?): Target {
        require(target.kind==TargetKind.SEARCH) {"A search scope requires a search"}
        val base=networkUrl(target.canonicalUrl)
        if(scope==null)return target.copy(canonicalUrl=base)
        val clean=normalize(target.platform,scope.kind,scope.value)
        return target.copy(canonicalUrl="$base#$marker${clean.kind.name}&value=${DarwinRoutes.encode(clean.value)}")
    }
    fun networkUrl(url: String): String=url.substringBefore('#')
    /** Native full-text routes with verifiable URL ownership or hydrated hashtag text. */
    fun nativeSearch(target: Target): Boolean=when(from(target)?.kind) {
        SearchScopeKind.ACCOUNT->target.platform in setOf(Platform.X,Platform.BLUESKY)
        SearchScopeKind.TAG->target.platform in setOf(Platform.X,Platform.BLUESKY)
        SearchScopeKind.COMMUNITY->target.platform==Platform.REDDIT
        else->false
    }
    fun networkUrl(target: Target): String {
        val scope=from(target) ?: return networkUrl(target.canonicalUrl)
        require(nativeSearch(target)) {"Search this scope through its selected public feed"}
        return when {
            target.platform==Platform.REDDIT&&scope.kind==SearchScopeKind.COMMUNITY->target.platform.origin+"/r/${scope.value}/search?q=${DarwinRoutes.encode(target.identity)}&restrict_sr=on&sort=new"
            target.platform==Platform.X&&scope.kind==SearchScopeKind.ACCOUNT->target.platform.origin+"/search?f=tweets&q=${DarwinRoutes.encode("from:${scope.value} (${target.identity})") }"
            target.platform==Platform.X&&scope.kind==SearchScopeKind.TAG->target.platform.origin+"/search?f=tweets&q=${DarwinRoutes.encode("#${scope.value} (${target.identity})") }"
            else->networkUrl(target.canonicalUrl)
        }
    }
    /** A selected public feed is a data boundary, rather than a guessed author name. */
    fun container(routes: DarwinRoutes,target: Target): Target {
        val scope=from(target) ?: throw IllegalArgumentException("Choose a search scope")
        val kind=when(scope.kind){SearchScopeKind.ACCOUNT,SearchScopeKind.CHANNEL->TargetKind.PROFILE;SearchScopeKind.COMMUNITY,SearchScopeKind.BOARD->TargetKind.COMMUNITY;SearchScopeKind.TAG->TargetKind.TAG}
        val input=when(scope.kind){SearchScopeKind.TAG->"#${scope.value}";SearchScopeKind.ACCOUNT->if(target.platform==Platform.IFUNNY)"user/${scope.value}"else "@${scope.value}";else->scope.value}
        val found=routes.target(target.platform,input,kind)
        require(found.platform==target.platform&&found.kind==kind) {"Enter a public ${target.platform.label} ${options(target.platform).first {it.kind==scope.kind}.label.lowercase(Locale.ROOT)}"}
        return found
    }
    /** Only supply [containerEvidence] after the reader verified that public feed.
     * Arbitrary post links, display names and index operators are not scope proof.
     */
    fun accepts(search: Target,result: Target,containerEvidence: Target?=null): Boolean {
        val scope=from(search) ?: return result.platform==search.platform
        if(result.platform!=search.platform)return false
        if(containerEvidence?.platform==search.platform&&fromContext(containerEvidence)==scope)return true
        if(result.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY,TargetKind.TAG))return fromContext(result)==scope
        if(result.kind !in setOf(TargetKind.POST,TargetKind.THREAD))return false
        val url=runCatching {URI(result.canonicalUrl)}.getOrNull() ?: return false
        if(url.scheme!="https"||url.userInfo!=null||url.port !in setOf(-1,443)||url.host!=URI(search.platform.origin).host)return false
        val parts=url.path.orEmpty().trim('/').split('/')
        return when {
            scope.kind==SearchScopeKind.COMMUNITY&&search.platform==Platform.REDDIT->parts.size>=4&&parts[0]=="r"&&parts[1].equals(scope.value,true)&&parts[2]=="comments"
            scope.kind==SearchScopeKind.ACCOUNT&&search.platform==Platform.X->parts.size==3&&parts[0].equals(scope.value,true)&&parts[1] in setOf("status","statuses")&&parts[2].matches(Regex("[0-9]{1,40}"))
            scope.kind==SearchScopeKind.ACCOUNT&&search.platform==Platform.BLUESKY->parts.size==4&&parts[0]=="profile"&&parts[1].equals(scope.value,true)&&parts[2]=="post"&&parts[3].matches(Regex("[A-Za-z0-9_-]{1,160}"))
            else->false
        }
    }
    fun accepts(search: Target,post: Post,containerEvidence: Target?=null): Boolean {
        val scope=from(search)
        if(scope?.kind==SearchScopeKind.TAG&&search.platform in setOf(Platform.X,Platform.BLUESKY)) {
            if(!resultAccepts(search,Target(post.platform,TargetKind.POST,"",post.canonicalUrl)))return false
            return Regex("(?<![\\p{L}\\p{N}_])#${Regex.escape(scope.value)}(?![\\p{L}\\p{N}_])",RegexOption.IGNORE_CASE).containsMatchIn(post.title+"\n"+post.body)
        }
        return accepts(search,Target(post.platform,TargetKind.POST,"",post.canonicalUrl),containerEvidence)
    }
    /** Discovery can admit a tag candidate; membership still needs hydrated text. */
    fun resultAccepts(search: Target,result: Target): Boolean {
        val scope=from(search) ?: return result.platform==search.platform
        if(result.kind !in setOf(TargetKind.POST,TargetKind.THREAD))return false
        if(scope.kind!=SearchScopeKind.TAG)return accepts(search,result)
        val url=runCatching {URI(result.canonicalUrl)}.getOrNull() ?: return false
        return result.platform==search.platform&&url.scheme=="https"&&url.host==URI(search.platform.origin).host&&url.userInfo==null&&url.port in setOf(-1,443)
    }
    fun scopePage(target: Target,page: Page): Page {
        if(from(target)==null)return page
        return page.copy(posts=page.posts.filter {accepts(target,it)},profile=null,accounts=emptyList(),collections=page.collections.filter {resultAccepts(target,it.target)})
    }
    /** Null means unscoped. A feed-only scope must never widen into a global index. */
    fun publicIndexQuery(target: Target): String? {
        val scope=from(target) ?: return null
        require(nativeSearch(target)) {"Search this scope through its selected public feed"}
        val path=when {
            target.platform==Platform.REDDIT&&scope.kind==SearchScopeKind.COMMUNITY->"site:reddit.com/r/${scope.value}/comments/"
            target.platform==Platform.X&&scope.kind==SearchScopeKind.ACCOUNT->"site:x.com/${scope.value}/status/"
            target.platform==Platform.BLUESKY&&scope.kind==SearchScopeKind.ACCOUNT->"site:bsky.app/profile/${scope.value}/post/"
            scope.kind==SearchScopeKind.TAG&&target.platform in setOf(Platform.X,Platform.BLUESKY)->"site:${target.platform.searchDomain} \"#${scope.value}\""
            else->throw IllegalArgumentException("This scope has no native public search")
        }
        return "$path ${target.identity}"
    }
    fun fromContext(target: Target): SearchScope? {
        if(target.kind==TargetKind.SEARCH)return from(target)
        val kind=when(target.kind){TargetKind.PROFILE->if(target.platform==Platform.TELEGRAM)SearchScopeKind.CHANNEL else SearchScopeKind.ACCOUNT;TargetKind.COMMUNITY->if(target.platform==Platform.PINTEREST)SearchScopeKind.BOARD else SearchScopeKind.COMMUNITY;TargetKind.TAG->SearchScopeKind.TAG;else->return null}
        val u=runCatching {URI(target.canonicalUrl)}.getOrNull() ?: return null
        if(u.scheme!="https"||u.userInfo!=null||u.host==null)return null
        val tumblrSubdomain=target.platform==Platform.TUMBLR&&u.host.endsWith(".tumblr.com")&&u.host!="www.tumblr.com"&&u.path.orEmpty().trim('/').isBlank()&&target.kind==TargetKind.PROFILE
        if(target.platform !in federated&&(!tumblrSubdomain&&u.host!=URI(target.platform.origin).host||u.port !in setOf(-1,443)))return null
        var value=u.path.orEmpty().trim('/')
        if(tumblrSubdomain)value=u.host.removeSuffix(".tumblr.com")
        else if(kind==SearchScopeKind.TAG&&target.platform in setOf(Platform.X,Platform.BLUESKY))value=DarwinRoutes.queryValue(u,"q").orEmpty().removePrefix("#")
        else if(target.platform in federated) {
            value=value.removePrefix("@").removePrefix("c/").removePrefix("u/")
            // A proxy's remote community name and its selected read server are
            // different identities; this two-field scope cannot represent both.
            if('@' in value&&!value.substringAfterLast('@').equals(u.rawAuthority,true))return null
            value=if('@' in value)value else value+"@"+u.rawAuthority
        }
        else value=when(target.platform){Platform.TIEBA->DarwinRoutes.queryValue(u,"kw").orEmpty();Platform.TUMBLR->value.removePrefix("tagged/");Platform.TIKTOK->value.removePrefix("tag/");Platform.INSTAGRAM->value.removePrefix("explore/tags/");else->value}
        return runCatching {normalize(target.platform,kind,value)}.getOrNull()
    }
}
