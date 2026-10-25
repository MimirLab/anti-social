/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.net.IDN
import java.net.URI
import java.util.Locale

/** A public account identity, never a claim about a person's legal name. */
data class MentionKey(val platform: Platform, val authority: String, val username: String) {
    val id: String get() = "${platform.id}|$authority|$username"
}
data class MentionAccount(val key: MentionKey, val target: Target)
data class MentionMatch(val start: Int, val endExclusive: Int, val token: String, val key: MentionKey, val target: Target, val fallbackLabel: String? = null)
data class PresentedMention(val original: MentionMatch, val start: Int, val endExclusive: Int, val displayName: String, val resolved: Boolean = true)
data class MentionPresentation(val rawText: String, val text: String, val mentions: List<PresentedMention>)

/** Conservative, data-only mention recognition. No lookup, rewrite or DNS request happens here. */
object MentionNames {
    const val MAX_MATCHES = 48
    const val MAX_TEXT_LENGTH = 65_536
    private val federated = setOf(Platform.MASTODON, Platform.LEMMY, Platform.PIXELFED, Platform.MISSKEY)
    private val candidate = Regex("@[\\p{L}\\p{N}_.-]+(?:@[A-Za-z0-9.-]+(?::[0-9]+)?)?")
    private val reddit = Regex("/?u/[A-Za-z0-9_-]{3,20}")
    private val address = Regex("(?i)(?:[a-z][a-z0-9+.-]*://|(?<![@\\p{L}\\p{N}_])www\\.|//)[^\\s<>]+")
    private val email = Regex("(?:\"[^\"\\r\\n]+\"|[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+)@[A-Za-z0-9.-]+")
    private val fencedCode = Regex("(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)|`[^`\\n]*(?:`|(?=\\n)|$)")
    private val codeWords = setOf("Override", "Deprecated", "SuppressWarnings", "FunctionalInterface", "SafeVarargs", "JvmStatic", "JvmField", "JvmOverloads", "Composable", "Test", "Before", "After", "Inject", "Nullable", "NonNull", "param", "return", "throws", "see", "author", "since")
    private val reserved = setOf("home", "search", "explore", "discover", "login", "logout", "signup", "register", "settings", "privacy", "terms", "help", "about", "account", "accounts", "api", "i", "intent", "messages", "notifications")

    fun find(text: String, platform: Platform, contextUrl: String, routes: DarwinRoutes, explicitProfileLinks: List<String> = emptyList()): List<MentionMatch> {
        if (text.isBlank() || text.length > MAX_TEXT_LENGTH) return emptyList()
        // Do not mistake the social .@name prefix for an email address.
        val excluded = (address.findAll(text).map { it.range } + fencedCode.findAll(text).map { it.range } + email.findAll(text).filter { text.getOrNull(it.range.first - 1) != '@' && it.value.substringBefore('@')!="." }.map { it.range }).toList()
        val explicit = explicitProfileLinks.take(48).mapNotNull { link ->
            safeUri(link)?.let { routes.recognize(link, platform.takeIf { p -> p in federated }) }?.let { account(it, routes) }
        }.distinctBy { it.key.id }
        val found = mutableListOf<MentionMatch>()
        for (match in candidate.findAll(text)) {
            if (found.size >= MAX_MATCHES) break
            val start = match.range.first
            // A terminal full stop belongs to prose; repeated dots remain invalid syntax.
            val token = match.value.let { if (it.endsWith('.') && !it.endsWith("..")) it.dropLast(1) else it }
            val end = start + token.length
            if (token.length < 2 || !boundary(text, start, end) || excluded.any { start <= it.last && end > it.first }) continue
            val raw = token.drop(1)
            if (raw in codeWords || looksLikeCode(text, start, end)) continue
            val linkedMatches = explicit.filter { (platform == Platform.RSS || it.key.platform == platform || '@' in raw&&it.key.platform in federated) && matchesToken(it, raw) }
            if (linkedMatches.size > 1) continue
            val linked = linkedMatches.singleOrNull()
            val resolved = linked ?: implicit(platform, raw, contextUrl, routes) ?: continue
            found += MentionMatch(start, end, token, resolved.key, resolved.target)
        }
        if (platform == Platform.REDDIT||explicit.any {it.key.platform==Platform.REDDIT}) for (match in reddit.findAll(text)) {
            if (found.size >= MAX_MATCHES) break
            val start = match.range.first; val end = match.range.last + 1
            if (!boundary(text, start, end) || excluded.any { start <= it.last && end > it.first }) continue
            val username=match.value.substringAfter("u/")
            val resolved=if(platform==Platform.REDDIT)routes.recognize("https://www.reddit.com/user/$username")?.let {account(it,routes)}
                else explicit.singleOrNull {it.key.platform==Platform.REDDIT&&it.key.username.equals(username,true)}
            if(resolved==null)continue
            found += MentionMatch(start, end, match.value, resolved.key, resolved.target)
        }
        return found.sortedBy { it.start }
    }

    /** Account labels can be plain usernames or supplied names. A profile URL or
     * author-bearing permalink proves their destination; prose never supplies one. */
    fun label(text: String, platform: Platform, contextUrl: String, routes: DarwinRoutes, explicitProfileLinks: List<String> = emptyList(), declaredAuthor: Target? = null): List<MentionMatch> {
        if(text.isBlank()||text.length>1_024)return find(text,platform,contextUrl,routes,explicitProfileLinks)
        val canonical=safeUri(contextUrl)?.let {routes.recognize(contextUrl,platform.takeIf {it in federated})}?.takeIf {it.platform==platform}
        val direct=canonical?.let {account(it,routes)}
        val owner=canonical?.let {CrossPosts.originalAccount(Post("",platform,it.canonicalUrl,"",""))}?.substringAfterLast(':')
        val proven=direct ?: owner?.let {implicit(platform,it,contextUrl,routes,author=true)}
        val ordinary=find(text,platform,contextUrl,routes,explicitProfileLinks)
        val exact=ordinary.singleOrNull()?.takeIf {it.start==0&&it.endExclusive==text.length}
        // Reddit returns usernames in its dedicated author field, without u/.
        val redditAuthor=if(platform==Platform.REDDIT&&validUsername(platform,text))implicit(platform,text,contextUrl,routes,author=true)else null
        // A source's dedicated author field binds its label even when that label
        // looks like another handle. Never apply this binding to body mentions.
        val identity=declaredAuthor?.takeIf {it.platform==platform}?.let {account(it,routes)}
            ?: exact?.let {MentionAccount(it.key,it.target)} ?: proven ?: redditAuthor
            ?: explicitProfileLinks.take(MAX_MATCHES).mapNotNull {routes.recognize(it)?.let {target->account(target,routes)}}
                .filter {it.key.platform==platform&&matchesToken(it,text.removePrefix("@"))}.distinctBy {it.key.id}.singleOrNull()
            ?: return ordinary
        val identifier=text.removePrefix("@").removePrefix("profile/").removePrefix("user/").removePrefix("u/").removePrefix("/u/")
        val supplied=sanitizeDisplayName(text,identity.key)?.takeUnless {identifier.equals(identity.key.username.substringAfterLast('/'),true)||text.equals(identity.key.username,true)}
        return listOf(MentionMatch(0,text.length,text,identity.key,identity.target,supplied))
    }

    /** Only exact, recognized public PROFILE targets may seed the name bank. */
    fun account(target: Target, routes: DarwinRoutes): MentionAccount? {
        if (target.kind != TargetKind.PROFILE || target.platform == Platform.RSS) return null
        safeUri(target.canonicalUrl) ?: return null
        val canonical = routes.recognize(target.canonicalUrl, target.platform.takeIf { it in federated })
            ?.takeIf { it.kind == TargetKind.PROFILE && it.platform == target.platform } ?: return null
        val uri = safeUri(canonical.canonicalUrl) ?: return null
        val host = publicHost(uri.host) ?: return null
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        val username = when (target.platform) {
            Platform.X, Platform.INSTAGRAM, Platform.TUMBLR, Platform.PINTEREST, Platform.GAB -> parts.singleOrNull()
            Platform.TIKTOK, Platform.THREADS, Platform.TRUTH_SOCIAL, Platform.YOUTUBE -> parts.singleOrNull()?.takeIf { it.startsWith('@') }?.drop(1)
            Platform.BLUESKY -> parts.takeIf { it.size == 2 && it[0] == "profile" }?.get(1)
            Platform.IFUNNY -> parts.takeIf { it.size == 2 && it[0] == "user" }?.get(1)
            Platform.GETTR -> parts.takeIf { it.size == 2 && it[0] == "user" }?.get(1)
            Platform.SNAPCHAT -> parts.takeIf { it.size == 2 && it[0] == "add" }?.get(1)
            Platform.TELEGRAM -> parts.singleOrNull()
            Platform.MASTODON, Platform.MISSKEY -> parts.singleOrNull()?.takeIf { it.startsWith('@') }?.drop(1)
            Platform.PIXELFED -> parts.singleOrNull()?.removePrefix("@")
            Platform.LEMMY -> parts.takeIf { it.size == 2 && it[0] == "u" }?.get(1)?.takeUnless { '@' in it }
            Platform.REDDIT -> parts.takeIf { it.size == 2 && it[0] in setOf("user", "u") }?.get(1)
            // A numeric Facebook identifier is not an @handle. LinkedIn namespaces must not collide.
            Platform.FACEBOOK -> parts.singleOrNull()?.takeUnless { it.all(Char::isDigit) }
            Platform.LINKEDIN -> parts.takeIf { it.size == 2 && it[0] in setOf("in", "company", "school") }?.joinToString("/")
            // Tieba's visible names are not a stable URL username; do not guess an identifier.
            Platform.TIEBA, Platform.RSS -> null
        } ?: return null
        val name = username.substringAfterLast('/')
        if (!validUsername(target.platform, name) || name.lowercase(Locale.ROOT) in reserved) return null
        val key = MentionKey(target.platform, host, username.lowercase(Locale.ROOT))
        return MentionAccount(key, canonical.copy(canonicalUrl=URI("https", null, host, -1, uri.path, uri.query, null).toASCIIString()))
    }

    fun sanitizeDisplayName(value: String, key: MentionKey): String? {
        if (value.length > 1_024) return null
        var clean = buildString {
            value.codePoints().forEach { point ->
                when {
                    Character.isWhitespace(point) || Character.isSpaceChar(point) -> append(' ')
                    Character.isISOControl(point) || point in 0x202A..0x202E || point in 0x2066..0x206F || point in 0xD800..0xDFFF || point in setOf(0x061C,0x200E,0x200F,0xFEFF) -> Unit
                    else -> appendCodePoint(point)
                }
            }
        }.replace(Regex("\\s+"), " ").trim()
        val handle=Regex.escape(key.username.substringAfterLast('/'))
        val host=Regex.escape(key.authority)
        clean=clean.replace(Regex("\\s*(?:\\(|\\[)\\s*@$handle(?:@$host)?\\s*(?:\\)|\\])$",RegexOption.IGNORE_CASE),"").trim()
        if (clean.isBlank() || clean.codePointCount(0, clean.length) > 120 || clean.startsWith('@') || Regex("(?i)[a-z][a-z0-9+.-]*://|www\\.").containsMatchIn(clean)) return null
        if('@' in clean)return null
        if (Regex("\\S+@\\S+\\.\\S+").containsMatchIn(clean)) return null
        if (Regex("(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?:[/:?#].*)?").matches(clean)) return null
        if (clean.equals("${key.username.substringAfterLast('/')}@${key.authority}", true)) return null
        if (clean.none { Character.isLetterOrDigit(it) || Character.isSurrogate(it) || it.code in 0x2600..0x27BF }) return null
        return clean
    }

    /** Source text is retained intact; only presentation ranges move when a name becomes available. */
    fun present(raw: String, matches: List<MentionMatch>, names: Map<String, String>): MentionPresentation {
        if (raw.length > MAX_TEXT_LENGTH) return MentionPresentation(raw, raw, emptyList())
        val out = StringBuilder(); val spans = mutableListOf<PresentedMention>(); var offset = 0
        matches.sortedBy { it.start }.take(MAX_MATCHES).forEach { mention ->
            if (mention.start < offset || mention.start < 0 || mention.endExclusive > raw.length || mention.endExclusive <= mention.start || raw.substring(mention.start, mention.endExclusive) != mention.token) return@forEach
            val resolvedName = names[mention.key.id]?.let { sanitizeDisplayName(it, mention.key) }
            val name = resolvedName ?: mention.fallbackLabel?.let {sanitizeDisplayName(it,mention.key)} ?: identifierLabel(mention)
            val prefixEnd=if(mention.key.platform==Platform.X&&broadcastDot(raw,mention.start)&&mention.start-1>=offset)mention.start-1 else mention.start
            out.append(raw, offset, prefixEnd)
            val start = out.length; out.append(name)
            spans += PresentedMention(mention, start, out.length, name, resolvedName != null)
            offset = mention.endExclusive
        }
        out.append(raw, offset, raw.length)
        return MentionPresentation(raw, out.toString(), spans)
    }

    /** Honest fallback while a public name is unavailable; this is an account
     * identifier, not an invented personal name. The exact notation stays in details. */
    fun identifierLabel(mention: MentionMatch): String {
        val notation=mention.token.removePrefix("/").removePrefix("profile/").removePrefix("user/").removePrefix("u/").removePrefix("@")
        val raw=if(mention.key.platform==Platform.LINKEDIN&&notation==mention.key.username)notation.substringAfterLast('/')else notation
        val handle=if(mention.key.platform in federated&&'@' in raw)raw.substringBefore('@')+" ("+mention.key.authority+")"else raw
        return handle.takeUnless {it.isBlank()||'@' in it} ?: mention.key.username.substringAfterLast('/')
    }

    private fun implicit(platform: Platform, raw: String, contextUrl: String, routes: DarwinRoutes, author: Boolean=false): MentionAccount? {
        if (platform in setOf(Platform.RSS, Platform.LINKEDIN, Platform.TIEBA)||!author&&platform in setOf(Platform.FACEBOOK,Platform.REDDIT)) return null
        val qualified = raw.split('@')
        val name = qualified[0]
        if (!validUsername(platform, name)) return null
        if (platform !in federated && qualified.size != 1) return null
        val root = if (platform in federated) {
            val host = if (qualified.size == 2) publicHost(qualified[1]) else if (qualified.size == 1) {
                safeUri(contextUrl)?.let { routes.recognize(contextUrl, platform) }?.takeIf { it.platform == platform }
                    ?.canonicalUrl?.let(::safeUri)?.host?.let(::publicHost)
            } else null
            host?.let { "https://$it" } ?: return null
        } else platform.origin
        val path = when (platform) {
            Platform.BLUESKY -> "/profile/$name"
            Platform.TIKTOK, Platform.THREADS, Platform.TRUTH_SOCIAL, Platform.YOUTUBE, Platform.MASTODON, Platform.MISSKEY -> "/@$name"
            Platform.IFUNNY, Platform.GETTR -> "/user/$name"
            Platform.SNAPCHAT -> "/add/$name"
            Platform.LEMMY -> "/u/$name"
            Platform.REDDIT -> "/user/$name"
            else -> "/$name"
        }
        return runCatching { URI(root + path).toASCIIString() }.getOrNull()?.let { routes.recognize(it, platform.takeIf { p -> p in federated }) }?.let { account(it, routes) }
    }

    private fun matchesToken(account: MentionAccount, raw: String): Boolean {
        val name = account.key.username.substringAfterLast('/')
        return raw.equals(name, true) || account.key.platform in federated && raw.equals("$name@${account.key.authority}", true)
    }
    private fun boundary(text: String, start: Int, end: Int): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        if (before != null && (before.isLetterOrDigit() || before in "_@/+%=-\\"||before=='.'&&!broadcastDot(text,start))) return false
        if (after != null && (after.isLetterOrDigit() || after in "_@/\\=-")) return false
        if(after==':'&&text.getOrNull(end+1)?.isDigit()==true)return false
        return true
    }
    private fun broadcastDot(text: String,start: Int)=text.getOrNull(start-1)=='.'&&(start==1||text.getOrNull(start-2)?.let {it.isWhitespace()||it in "([\"'“‘"}==true)
    private fun looksLikeCode(text: String, start: Int, end: Int): Boolean {
        val line = text.substring(text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1, text.indexOf('\n', end).takeIf { it >= 0 } ?: text.length)
        return text.getOrNull(end) in setOf('(', '{', '=') || line.trimStart().startsWith("import ") || line.trimStart().startsWith("package ") || Regex("(?:^|\\s)(?:class|fun|void|const|val|var)\\s").containsMatchIn(line)
    }
    private fun validUsername(platform: Platform, name: String): Boolean = when (platform) {
        Platform.X -> name.matches(Regex("[A-Za-z0-9_]{1,15}"))
        Platform.INSTAGRAM, Platform.THREADS, Platform.PIXELFED -> name.matches(Regex("[A-Za-z0-9_](?:[A-Za-z0-9_.]{0,28}[A-Za-z0-9_])?")) && ".." !in name
        Platform.TIKTOK -> name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,22}[A-Za-z0-9_]")) && ".." !in name
        Platform.TUMBLR -> name.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,30}[A-Za-z0-9])?"))
        Platform.PINTEREST -> name.matches(Regex("[A-Za-z0-9_]{3,30}"))
        Platform.IFUNNY -> name.matches(Regex("[A-Za-z0-9_]{3,30}"))
        Platform.TRUTH_SOCIAL -> name.matches(Regex("[A-Za-z0-9_]{1,30}"))
        Platform.MASTODON, Platform.MISSKEY -> name.matches(Regex("[A-Za-z0-9_]{1,64}"))
        Platform.LEMMY -> name.matches(Regex("[A-Za-z0-9_]{3,20}"))
        Platform.GETTR -> name.matches(Regex("[A-Za-z0-9_]{1,20}"))
        Platform.GAB -> name.matches(Regex("[A-Za-z0-9_]{1,50}"))
        Platform.TELEGRAM -> name.matches(Regex("[A-Za-z][A-Za-z0-9_]{3,31}"))
        Platform.SNAPCHAT -> name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{1,13}[A-Za-z0-9]"))
        Platform.BLUESKY -> publicHost(name) != null && name.length <= 253 && name.substringAfterLast('.').any(Char::isLetter)
        Platform.YOUTUBE -> name.matches(Regex("[\\p{L}\\p{N}](?:[\\p{L}\\p{N}_.-]{0,28}[\\p{L}\\p{N}])?"))
        Platform.REDDIT -> name.matches(Regex("[A-Za-z0-9_-]{3,20}"))
        Platform.FACEBOOK -> name.matches(Regex("[A-Za-z0-9.]{5,50}")) && name.any(Char::isLetter)
        Platform.LINKEDIN -> name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))
        Platform.TIEBA, Platform.RSS -> false
    }
    private fun safeUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme.equals("https", true) && it.userInfo == null && it.port in setOf(-1,443) && it.host?.let(::publicHost) != null
    }
    /** Syntactic prefilter only. NetworkGateway still enforces DNS and every redirect. */
    private fun publicHost(value: String): String? {
        if (value.isBlank() || value.endsWith('.') || value.any { it == ':' || it == '/' || it == '@' }) return null
        val host = runCatching { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if (host.length > 253 || !host.contains('.') || host.split('.').any { !it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) return null
        if (host.substringAfterLast('.').all(Char::isDigit) || host.substringAfterLast('.') in setOf("local", "localhost", "localdomain", "internal", "invalid", "test") || host == "localhost") return null
        return host
    }
}

/** Tiny bounded ordinary cache: popularity affects eviction, never expiry of unused identities. */
object MentionNamePolicy {
    const val MAX_ENTRIES = 1_024
    const val MAX_BYTES = 1_048_576L
    const val UNUSED_TTL_MS = 30L * 24 * 60 * 60 * 1_000
    const val STALE_MS = 7L * 24 * 60 * 60 * 1_000
    const val MISS_BACKOFF_MS = 6L * 60 * 60 * 1_000
    const val MAX_MISS_BACKOFF_MS = 7L * 24 * 60 * 60 * 1_000
    fun validTimestamp(value: Long, now: Long): Boolean = value > 0 && value <= now + 60_000
    fun expired(lastViewedAt: Long, now: Long): Boolean = !validTimestamp(lastViewedAt, now) || now - lastViewedAt >= UNUSED_TTL_MS
    fun stale(checkedAt: Long, now: Long): Boolean = !validTimestamp(checkedAt, now) || now - checkedAt >= STALE_MS
    fun retryDelay(failures: Int): Long = (MISS_BACKOFF_MS * (1L shl (failures.coerceAtLeast(1) - 1).coerceIn(0, 5))).coerceAtMost(MAX_MISS_BACKOFF_MS)
    fun retainScore(viewCount: Long, lastViewedAt: Long, now: Long): Double {
        if (expired(lastViewedAt, now)) return Double.NEGATIVE_INFINITY
        val ageDays = (now - lastViewedAt).coerceAtLeast(0).toDouble() / (24 * 60 * 60 * 1_000)
        return kotlin.math.ln1p(viewCount.coerceIn(0, 1_000_000).toDouble()) - ageDays / 7.0
    }
}
