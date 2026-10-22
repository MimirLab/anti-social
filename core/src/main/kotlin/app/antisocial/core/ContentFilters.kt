/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.Strictness
import java.io.StringReader
import java.net.URI
import java.text.Normalizer
import java.util.Locale

enum class ContentAction { KEEP, COLLECT, COLLAPSE, HIDE }
enum class FilterSurface { FEED, SEARCH }
enum class ContentScope { EVERYWHERE, ACCOUNT, CHANNEL, SEARCH }

/** Literal, reader-authored rules. OR within a section, AND between sections.
 * A single source edition must satisfy every positive section. No regex, scripts,
 * inferred politics, quality scores, or network requests participate in matching. */
data class ContentRule(
    val id: String, val name: String, val enabled: Boolean = true,
    val action: ContentAction = ContentAction.COLLAPSE,
    val scope: ContentScope = ContentScope.EVERYWHERE, val scopeKey: String = "",
    val surfaces: Set<FilterSurface> = FilterSurface.entries.toSet(),
    val phrases: List<String> = emptyList(), val hashtags: List<String> = emptyList(),
    val domains: List<String> = emptyList(), val types: Set<ChannelContentType> = emptySet(),
    val exceptPhrases: List<String> = emptyList(), val repeatsOnly: Boolean = false,
    val expiresAt: Long? = null, val revision: Long = 1,
    val platforms: Set<Platform> = emptySet(),
    val subreddits: List<String> = emptyList(), val servers: List<String> = emptyList(),
)
data class ContentFilterContext(val surface: FilterSurface, val channel: String? = null, val search: String? = null)
data class ContentMatch(val ruleId: String, val name: String, val action: ContentAction, val reasons: List<String>, val originalKeys: Set<String>)
data class ContentVerdict(val action: ContentAction = ContentAction.KEEP, val matches: List<ContentMatch> = emptyList()) {
    val withheld get() = action == ContentAction.COLLAPSE || action == ContentAction.HIDE
    val reason get() = matches.joinToString(" · ") { it.name }
}

object ContentFilters {
    const val MAX_RULES = 128
    const val MAX_TERMS = 32
    val federatedSites=setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY)
    private val whitespace=Regex("[\\s\\p{Z}]+")
    private val hashtag=Regex("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_]+)")
    private val urlPattern=Regex("https?://[^\\s<>]+")
    private fun norm(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(whitespace, " ").trim()
    private fun safe(value: String, max: Int) = value.length <= max && value.none(Char::isISOControl)
    fun validate(rule: ContentRule): List<String> = buildList {
        if(rule.id.isBlank() || !safe(rule.id,160)) add("Invalid rule ID.")
        if(rule.name.isBlank() || !safe(rule.name,80)) add("Give this rule a name of up to 80 characters.")
        if(rule.surfaces.isEmpty()) add("Choose feeds or search.")
        if(rule.scope != ContentScope.EVERYWHERE && (rule.scopeKey.isBlank() || !safe(rule.scopeKey,2048))) add("Choose a scope.")
        if(rule.scope == ContentScope.EVERYWHERE && rule.scopeKey.isNotEmpty()) add("A global rule cannot have a scope key.")
        for(terms in listOf(rule.phrases,rule.hashtags,rule.domains,rule.exceptPhrases,rule.subreddits,rule.servers)) {
            if(terms.size>MAX_TERMS || terms.any { it.isBlank() || !safe(it,160) }) add("Use up to 32 terms of 160 characters each.")
            if(terms.map(::norm).distinct().size != terms.size) add("Remove repeated terms.")
        }
        if(rule.hashtags.any { !it.removePrefix("#").matches(Regex("[\\p{L}\\p{N}_]+")) }) add("Use a hashtag without spaces.")
        if(rule.domains.any { !validDomain(it) }) add("Enter a website address such as example.org.")
        if(rule.subreddits.any {!it.matches(Regex("[A-Za-z0-9_]{2,100}"))}) add("Enter subreddit names such as r/Android.")
        if(rule.subreddits.isNotEmpty()&&rule.platforms.isNotEmpty()&&Platform.REDDIT !in rule.platforms) add("Select Reddit or clear the subreddit names.")
        if(rule.servers.any {!validDomain(it)}) add("Enter a server address such as mastodon.social.")
        if(rule.servers.isNotEmpty()&&rule.platforms.isNotEmpty()&&rule.platforms.none {it in federatedSites}) add("Select a federated site or clear the server addresses.")
        if(rule.subreddits.isNotEmpty()&&rule.servers.isNotEmpty()) add("Choose subreddits or servers for a single rule, rather than both.")
        if(rule.expiresAt != null && rule.expiresAt <= 0) add("Invalid expiry.")
        if(rule.revision <= 0) add("Invalid rule revision.")
        if(rule.phrases.isEmpty() && rule.hashtags.isEmpty() && rule.domains.isEmpty() && rule.types.isEmpty() && rule.platforms.isEmpty() && rule.subreddits.isEmpty() && rule.servers.isEmpty() && !rule.repeatsOnly && rule.scope != ContentScope.ACCOUNT) add("Choose words, a social site, or another detail to match.")
        if(rule.action == ContentAction.COLLECT && !rule.repeatsOnly) add("Collect applies only to confirmed repeat groups.")
    }
    private fun validDomain(value: String): Boolean = runCatching {
        val host=URI("https://$value").host ?: return@runCatching false
        host.contains('.') && host.equals(value,true) && !value.endsWith('.') && value.length<=160
    }.getOrDefault(false)
    /** Reader input may be a pasted link; matching still uses its website only. */
    fun linkedWebsite(value: String): String=serverAddress(value).removePrefix("www.")
    fun serverAddress(value: String): String = runCatching {
        val trimmed=value.trim()
        val uri=URI(if("://" in trimmed)trimmed else "https://$trimmed")
        require(uri.scheme.lowercase(Locale.ROOT) in setOf("http","https") && uri.rawUserInfo==null)
        (uri.host ?: error("Missing website")).lowercase(Locale.ROOT)
    }.getOrDefault(value.trim())
    fun subredditName(value: String): String {
        val trimmed=value.trim()
        val name=if("://" in trimmed)runCatching {URI(trimmed).takeIf {SocialLinkHosts.platform(trimmed)==Platform.REDDIT&&it.userInfo==null}?.path?.trim('/')?.split('/')?.takeIf {it.size>=2&&it[0]=="r"}?.get(1)}.getOrNull() ?: trimmed else trimmed.trim('/').removePrefix("r/")
        return name.lowercase(Locale.ROOT)
    }
    private fun originalUri(post: Post)=runCatching {URI(post.canonicalUrl)}.getOrNull()?.takeIf {it.scheme=="https"&&it.host!=null&&it.userInfo==null&&(it.port==-1||it.port in 1..65535)}
    private fun postSubreddit(post: Post): String? {
        if(post.platform!=Platform.REDDIT||SocialLinkHosts.platform(post.canonicalUrl)!=Platform.REDDIT)return null
        val parts=originalUri(post)?.takeIf {it.port in setOf(-1,443)}?.path?.trim('/')?.split('/') ?: return null
        return parts.getOrNull(1)?.takeIf {parts.size>=4&&parts[0]=="r"&&parts[2]=="comments"&&parts[3].matches(Regex("[A-Za-z0-9]+"))}?.lowercase(Locale.ROOT)
    }
    private class Phrase(value: String) {
        private val value=norm(value)
        private fun word(code: Int): Boolean = code=='_'.code||Character.isLetter(code)||when(Character.getType(code)){Character.DECIMAL_DIGIT_NUMBER.toInt(),Character.LETTER_NUMBER.toInt(),Character.OTHER_NUMBER.toInt()->true;else->false}
        fun containsMatchIn(text: String): Boolean {
            if(value.isEmpty())return false
            var from=0
            while(from<=text.length-value.length) {
                val at=text.indexOf(value,from);if(at<0)return false
                val end=at+value.length
                if((at==0||!word(text.codePointBefore(at)))&&(end==text.length||!word(text.codePointAt(end))))return true
                from=at+1
            }
            return false
        }
    }
    private fun phrasePattern(value: String)=Phrase(value)

    class Compiled internal constructor(private val rules: List<ContentRule>) {
        private data class Ready(val rule: ContentRule, val phrases: List<Pair<String,Phrase>>, val except: List<Phrase>, val tags: Set<String>, val domains: Set<String>)
        private val ready=rules.map {rule->Ready(rule,rule.phrases.map {it to phrasePattern(it)},rule.exceptPhrases.map(::phrasePattern),rule.hashtags.map {norm(it.removePrefix("#"))}.toSet(),rule.domains.map(::norm).toSet())}
        private class Observed(val post: Post) {
            val text by lazy {norm(post.title+"\n"+post.body)}
            val tags by lazy {hashtag.findAll(text).map {it.groupValues[1]}.toSet()}
            val hosts by lazy {(post.links.orEmpty()+urlPattern.findAll(post.body).map {it.value}.toList()).mapNotNull {runCatching {URI(it).host?.lowercase(Locale.ROOT)}.getOrNull()}.toSet()}
            val types by lazy {contentTypes(post)}
            val subreddit by lazy {postSubreddit(post)}
            val server by lazy {if(post.platform in federatedSites)originalUri(post)?.host?.lowercase(Locale.ROOT)else null}
        }
        fun evaluate(editions: List<Post>, context: ContentFilterContext, now: Long): ContentVerdict {
            if(ready.isEmpty()||editions.isEmpty()) return ContentVerdict()
            val roots=editions.distinctBy(CrossPosts::originalKey)
            val observed=roots.map(::Observed)
            val matches=ready.mapNotNull {r->
                val rule=r.rule
                if(!rule.enabled || context.surface !in rule.surfaces || rule.expiresAt?.let {now>=it}==true) return@mapNotNull null
                if(rule.scope==ContentScope.CHANNEL && context.channel!=rule.scopeKey || rule.scope==ContentScope.SEARCH && context.search!=rule.scopeKey) return@mapNotNull null
                if(rule.repeatsOnly && roots.size<2) return@mapNotNull null
                // Exceptions in any edition protect a collected post, including
                // corrections that were absent from the representative edition.
                if(r.except.isNotEmpty()&&observed.any {p->r.except.any {it.containsMatchIn(p.text)}}) return@mapNotNull null
                val hits=observed.mapNotNull {view->
                    val p=view.post
                    // Original content identity, never the frontend or search provider.
                    if(rule.platforms.isNotEmpty() && p.platform !in rule.platforms) return@mapNotNull null
                    if(rule.subreddits.isNotEmpty()&&rule.subreddits.none {it.equals(view.subreddit,true)}) return@mapNotNull null
                    if(rule.servers.isNotEmpty()&&rule.servers.none {it.equals(view.server,true)}) return@mapNotNull null
                    if(rule.scope==ContentScope.ACCOUNT && CrossPosts.originalAccount(p)!=rule.scopeKey) return@mapNotNull null
                    val text=view.text
                    val phrase=r.phrases.firstOrNull {it.second.containsMatchIn(text)}?.first
                    if(r.phrases.isNotEmpty() && phrase==null) return@mapNotNull null
                    val tag=if(r.tags.isEmpty())null else view.tags.firstOrNull {it in r.tags}
                    if(r.tags.isNotEmpty() && tag==null) return@mapNotNull null
                    val domain=if(r.domains.isEmpty())null else view.hosts.firstOrNull {host->r.domains.any {host==it||host.endsWith(".$it")}}
                    if(r.domains.isNotEmpty() && domain==null) return@mapNotNull null
                    val type=if(rule.types.isEmpty())null else view.types.firstOrNull {it in rule.types}
                    if(rule.types.isNotEmpty() && type==null) return@mapNotNull null
                    CrossPosts.originalKey(p) to buildList<String> {
                        if(phrase!=null) add("Contains “$phrase”")
                        if(tag!=null) add("Hashtag #$tag")
                        if(domain!=null) add("Links to $domain")
                        if(type!=null) add(type.name.lowercase().replaceFirstChar { it.uppercase() }+" post")
                        if(rule.platforms.isNotEmpty()) add("From ${p.platform.label}")
                        if(rule.subreddits.isNotEmpty()) add("In r/${view.subreddit}")
                        if(rule.servers.isNotEmpty()) add("Posted on ${view.server}")
                        if(rule.scope==ContentScope.ACCOUNT) add("From this account")
                        if(rule.repeatsOnly) add("${roots.size} confirmed originals collected")
                    }
                }
                if(hits.isEmpty()) null else ContentMatch(rule.id,rule.name,rule.action,hits.flatMap {it.second}.distinct(),hits.map {it.first}.toSet())
            }
            // An explicit keep always wins. Otherwise use the strongest action;
            // rule order never silently changes what the reader sees.
            val keep=matches.filter {it.action==ContentAction.KEEP}
            if(keep.isNotEmpty()) return ContentVerdict(ContentAction.KEEP,keep)
            val action=matches.maxByOrNull {it.action.ordinal}?.action ?: ContentAction.KEEP
            return ContentVerdict(action,matches)
        }
    }
    fun compile(rules: List<ContentRule>): Compiled {
        require(rules.size<=MAX_RULES && rules.map {it.id}.distinct().size==rules.size) {"Too many or repeated rules."}
        rules.forEach {require(validate(it).isEmpty()) {validate(it).joinToString(" ")}}
        return Compiled(rules)
    }
    fun contentTypes(post: Post): Set<ChannelContentType> = buildSet {
        if(post.title.isNotBlank()||post.body.isNotBlank()) add(ChannelContentType.TEXT)
        if(post.media.any {it.type=="image"}) add(ChannelContentType.IMAGES)
        if(post.media.any {it.type=="video"}) add(ChannelContentType.VIDEO)
        if(post.media.any {it.type=="audio"}) add(ChannelContentType.AUDIO)
        if(post.links.orEmpty().isNotEmpty()||Regex("https?://").containsMatchIn(post.body)) add(ChannelContentType.LINKS)
    }
}

/** A recipe is data, never executable code. Reject incompatible fields rather
 * than quietly broadening a restored rule. No history or API keys are exported. */
object ContentRuleCodec {
    private val gson=GsonBuilder().disableHtmlEscaping().create()
    private val fields=setOf("id","name","enabled","action","scope","scopeKey","surfaces","phrases","hashtags","domains","types","exceptPhrases","repeatsOnly","expiresAt","revision","platforms","subreddits","servers")
    fun encode(rules: List<ContentRule>): String {
        ContentFilters.compile(rules)
        return JsonObject().apply {addProperty("version",2);add("rules",gson.toJsonTree(rules))}.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size<=1_000_000) {"Filter recipe is too large. Remove some rules or terms."}
        }
    }
    fun decode(value: String): List<ContentRule> {
        require(value.length<=1_000_000) {"Filter recipe is too large."}
        require(value.toByteArray(Charsets.UTF_8).size<=1_000_000) {"Filter recipe is too large."}
        JsonReader(StringReader(value)).use {reader->
            reader.strictness=Strictness.STRICT
            fun visit(depth: Int) {
                require(depth<=5) {"Filter recipe is nested too deeply."}
                when(reader.peek()) {
                    JsonToken.BEGIN_OBJECT->{reader.beginObject();val seen=mutableSetOf<String>();while(reader.hasNext()){require(seen.add(reader.nextName())) {"Repeated filter field."};visit(depth+1)};reader.endObject()}
                    JsonToken.BEGIN_ARRAY->{reader.beginArray();while(reader.hasNext())visit(depth+1);reader.endArray()}
                    JsonToken.STRING,JsonToken.NUMBER->reader.nextString()
                    JsonToken.BOOLEAN->reader.nextBoolean()
                    JsonToken.NULL->reader.nextNull()
                    else->throw IllegalArgumentException("Invalid filter recipe.")
                }
            }
            visit(0);require(reader.peek()==JsonToken.END_DOCUMENT) {"Unexpected data after filter recipe."}
        }
        val root=JsonParser.parseString(value).asJsonObject
        require(root.keySet()==setOf("version","rules") && root["version"].toString() in setOf("1","2")) {"Unsupported filter recipe version."}
        val version=root["version"].asInt
        val array=root["rules"].asJsonArray
        require(array.size()<=ContentFilters.MAX_RULES) {"Too many rules."}
        val rules=array.map {raw->
            val o=raw.asJsonObject
            val expected=if(version==1)fields-setOf("platforms","subreddits","servers") else fields
            require(o.keySet()==expected-setOf("expiresAt") || o.keySet()==expected) {"Unsupported filter fields."}
            fun string(key: String)=o[key].also {require(it.isJsonPrimitive&&it.asJsonPrimitive.isString) {"Invalid $key."}}.asString
            fun bool(key: String)=o[key].also {require(it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean) {"Invalid $key."}}.asBoolean
            fun number(key: String): Long {val text=o[key].toString();require(text.matches(Regex("[0-9]{1,19}")));return text.toLong()}
            fun strings(key: String): List<String> = o[key].asJsonArray.also {require(it.size()<=ContentFilters.MAX_TERMS)}.map {v->require(v.isJsonPrimitive&&v.asJsonPrimitive.isString);v.asString}.also {require(it.distinct().size==it.size)}
            ContentRule(string("id"),string("name"),bool("enabled"),ContentAction.valueOf(string("action")),ContentScope.valueOf(string("scope")),string("scopeKey"),strings("surfaces").map(FilterSurface::valueOf).toSet(),strings("phrases"),strings("hashtags"),strings("domains"),strings("types").map(ChannelContentType::valueOf).toSet(),strings("exceptPhrases"),bool("repeatsOnly"),o["expiresAt"]?.takeUnless {it.isJsonNull}?.let {number("expiresAt")},number("revision"),if(version==1)emptySet()else strings("platforms").map(Platform::valueOf).toSet(),if(version==1)emptyList()else strings("subreddits"),if(version==1)emptyList()else strings("servers"))
        }
        ContentFilters.compile(rules)
        return rules
    }
}
