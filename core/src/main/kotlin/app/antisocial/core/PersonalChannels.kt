package app.antisocial.core

import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Local, explicit feed rules. No inferred interests, identity guesses or network lookups. */
enum class ChannelContentType { TEXT, IMAGES, VIDEO, AUDIO, LINKS }

data class PersonalChannel(
    val id: String,
    val name: String,
    val subscriptionIds: Set<String> = emptySet(),
    val keywords: List<String> = emptyList(),
    val hashtags: List<String> = emptyList(),
    val contentTypes: Set<ChannelContentType> = emptySet(),
    val excludeKeywords: List<String> = emptyList(),
    val excludeHashtags: List<String> = emptyList(),
    val excludeSubscriptionIds: Set<String> = emptySet(),
    val minVideoSeconds: Long? = null,
    val revision: Long = 1,
    val excludeContentTypes: Set<ChannelContentType> = emptySet()
)

/** Reasons describe one complete matching edition; keys include every matching edition. */
data class ChannelMatch(val reasons: List<String>, val matchedPostKeys: Set<String>)

object PersonalChannels {
    const val MAX_CHANNELS = 32
    const val MAX_TERMS = 32
    const val MAX_TERM_LENGTH = 120
    const val MAX_NAME_LENGTH = 60
    const val MAX_SUBSCRIPTIONS = 512
    const val MAX_VIDEO_SECONDS = 31_536_000L
    private val whitespace = Regex("[\\p{Z}\\s]+")
    private val hashtagToken = Regex("[\\p{L}\\p{M}\\p{N}_]+")
    // A URL fragment is not a hashtag. Hashtags remain independent from keyword rules.
    private val hashtagsInText = Regex("(?<![\\p{L}\\p{M}\\p{N}_/?:.&=%])#([\\p{L}\\p{M}\\p{N}_]+)")
    private val urlsInText = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val wordCharacter = "[\\p{L}\\p{M}\\p{N}_]"

    private fun text(value: String) = whitespace.replace(Normalizer.normalize(value, Normalizer.Form.NFKC), " ").trim()
    // Unicode simple case folding agrees with Java's case-insensitive literal
    // matcher (including Greek final sigma and dotted I), without accent removal
    // or multi-character substitutions such as changing ß into ss.
    private fun folded(value: String): String = buildString {
        val normalized = text(value)
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            appendCodePoint(Character.toLowerCase(Character.toUpperCase(codePoint)))
            offset += Character.charCount(codePoint)
        }
    }
    private fun tag(value: String) = text(value).removePrefix("#").replace('\u0130', 'i').lowercase(Locale.ROOT)
    private fun terms(values: List<String>) = values.map(::text).filter(String::isNotBlank).distinctBy(::folded)
    private fun tags(values: List<String>) = values.map(::tag).filter(String::isNotBlank).distinctBy(::folded)

    /** Does not truncate rules: invalid input must be rejected, never silently broadened. */
    fun normalize(channel: PersonalChannel): PersonalChannel = channel.copy(
        name = text(channel.name),
        subscriptionIds = channel.subscriptionIds.map(String::trim).toSet(),
        keywords = terms(channel.keywords),
        hashtags = tags(channel.hashtags),
        excludeKeywords = terms(channel.excludeKeywords),
        excludeHashtags = tags(channel.excludeHashtags),
        excludeSubscriptionIds = channel.excludeSubscriptionIds.map(String::trim).toSet()
    )

    /** Empty means valid. Limits apply before normalization so malformed imports fail closed. */
    fun validate(channel: PersonalChannel): List<String> = buildList {
        if (channel.id.isBlank() || channel.id.length > 160 || channel.id.any(Char::isISOControl)) add("A channel needs a valid local ID.")
        val name = text(channel.name)
        if (name.isBlank()) add("Give the channel a name.")
        if (name.length > MAX_NAME_LENGTH) add("Channel names can contain up to $MAX_NAME_LENGTH characters.")
        if (channel.revision < 1) add("The channel revision must be positive.")
        fun checkTerms(label: String, values: List<String>, hashtag: Boolean = false) {
            if (values.size > MAX_TERMS) add("$label can contain up to $MAX_TERMS entries.")
            if (values.any { text(it).isBlank() || text(it).length > MAX_TERM_LENGTH })
                add("Each $label entry needs 1–$MAX_TERM_LENGTH characters.")
            if (hashtag && values.any { !hashtagToken.matches(tag(it)) })
                add("Hashtags can contain letters, numbers and underscores, with an optional #.")
        }
        checkTerms("Keywords", channel.keywords)
        checkTerms("Excluded keywords", channel.excludeKeywords)
        checkTerms("Hashtags", channel.hashtags, true)
        checkTerms("Excluded hashtags", channel.excludeHashtags, true)
        listOf(channel.subscriptionIds, channel.excludeSubscriptionIds).forEach { ids ->
            if (ids.size > MAX_SUBSCRIPTIONS || ids.any { it.isBlank() || it.length > 512 || it.any(Char::isISOControl) })
                add("Choose up to $MAX_SUBSCRIPTIONS valid subscriptions in each selection.")
        }
        if (channel.minVideoSeconds != null && channel.minVideoSeconds !in 1..MAX_VIDEO_SECONDS)
            add("Minimum video length must be between 1 second and 365 days.")
    }

    /** Accounts that can contribute to this channel or keep its refresh visibly pending. */
    fun accounts(channel: PersonalChannel, follows: List<Follow>): List<Follow> {
        if (validate(channel).isNotEmpty()) return emptyList()
        val rule = normalize(channel)
        return follows.filter { ReaderScope.active(it) && Subscriptions.id(it) !in rule.excludeSubscriptionIds &&
            (rule.subscriptionIds.isEmpty() || Subscriptions.id(it) in rule.subscriptionIds) }
    }

    /** Rule identity excludes presentation name, local ID and edit revision. */
    fun fingerprint(channel: PersonalChannel): String {
        val value = normalize(channel)
        // Length prefixes avoid collisions between literal terms containing separators.
        val sections = listOf(
            value.subscriptionIds.sorted(), value.keywords.map(::folded).sorted(), value.hashtags.map(::folded).sorted(),
            value.contentTypes.map { it.name }.sorted(), value.excludeKeywords.map(::folded).sorted(),
            value.excludeHashtags.map(::folded).sorted(), value.excludeSubscriptionIds.sorted(),
            listOf(value.minVideoSeconds?.toString().orEmpty()), value.excludeContentTypes.map { it.name }.sorted()
        )
        val canonical = sections.joinToString("|") { section -> section.joinToString("") { "${it.length}:$it" } }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun postKey(post: Post) = "${post.platform.id}:${ContentIdentity.key(post.platform, post.canonicalUrl)}"

    /**
     * OR inside each section, AND across populated sections. All positive rules must
     * match one edition, rather than assembling a fictional match across platforms.
     * Exclusions in any edition veto the complete fusion so excluded context cannot
     * reappear through another platform. Memberships contain exact account Follow IDs
     * supplied by the local feed store; display names and matching handles prove nothing.
     * This function preserves input order and never ranks or sorts posts.
     */
    fun match(
        channel: PersonalChannel,
        posts: List<Post>,
        follows: List<Follow>,
        memberships: Map<String, Set<String>>
    ): ChannelMatch? = matcher(channel, follows, memberships)(posts)

    /** Compile once per feed snapshot, then evaluate each already-fused chronological card. */
    fun matcher(
        channel: PersonalChannel,
        follows: List<Follow>,
        memberships: Map<String, Set<String>>
    ): (List<Post>) -> ChannelMatch? {
        if (validate(channel).isNotEmpty()) return { null }
        val rule = normalize(channel)
        val byAccount = follows.groupBy { it.target.platform to it.id }
        val keywordPatterns = rule.keywords.map { it to phrasePattern(it) }
        val excludedPatterns = rule.excludeKeywords.map(::phrasePattern)
        return { posts -> matchPrepared(rule, posts, byAccount, memberships, keywordPatterns, excludedPatterns) }
    }

    private fun matchPrepared(
        rule: PersonalChannel,
        posts: List<Post>,
        byAccount: Map<Pair<Platform, String>, List<Follow>>,
        memberships: Map<String, Set<String>>,
        keywordPatterns: List<Pair<String, Regex>>,
        excludedPatterns: List<Regex>
    ): ChannelMatch? {
        if (posts.isEmpty()) return null
        fun sources(post: Post, enabledOnly: Boolean): List<Follow> = memberships[postKey(post)].orEmpty()
            .flatMap { byAccount[post.platform to it].orEmpty() }.filter { !enabledOnly || ReaderScope.active(it) }
        val editions = posts.map { post -> Edition(post, text(post.title + "\n" + post.body)) }
        for (edition in editions) {
            if (sources(edition.post, false).any { Subscriptions.id(it) in rule.excludeSubscriptionIds }) return null
            if (excludedPatterns.any { it.containsMatchIn(edition.text) }) return null
            if (rule.excludeHashtags.any { folded(it) in edition.tags }) return null
            if (rule.excludeContentTypes.any { it in edition.types }) return null
        }
        val matches = editions.mapNotNull { edition ->
            val selected = sources(edition.post, true).filter { rule.subscriptionIds.isEmpty() || Subscriptions.id(it) in rule.subscriptionIds }
            if (selected.isEmpty()) return@mapNotNull null
            val keyword = keywordPatterns.firstOrNull { it.second.containsMatchIn(edition.text) }?.first
            if (rule.keywords.isNotEmpty() && keyword == null) return@mapNotNull null
            val hashtag = rule.hashtags.firstOrNull { folded(it) in edition.tags }
            if (rule.hashtags.isNotEmpty() && hashtag == null) return@mapNotNull null
            val type = ChannelContentType.entries.firstOrNull { it in rule.contentTypes && it in edition.types }
            if (rule.contentTypes.isNotEmpty() && type == null) return@mapNotNull null
            val duration = if (rule.minVideoSeconds == null) null else edition.post.media
                .filter { mediaType(it) == ChannelContentType.VIDEO }
                .mapNotNull { it.durationMs?.takeIf { duration -> duration > 0 } }
                .firstOrNull { it >= rule.minVideoSeconds * 1000 }
            if (rule.minVideoSeconds != null && duration == null) return@mapNotNull null
            val source = selected.first()
            val sourceName = source.personName?.takeIf(String::isNotBlank) ?: source.name.ifBlank { source.target.identity }
            val reasons = buildList {
                add("From $sourceName on ${source.target.platform.label}")
                keyword?.let { add("Contains “$it”") }
                hashtag?.let { add("Tagged #$it") }
                type?.let { add(when (it) {
                    ChannelContentType.TEXT -> "Text only, without attachments or links"
                    ChannelContentType.IMAGES -> "Includes images"
                    ChannelContentType.VIDEO -> "Includes video"
                    ChannelContentType.AUDIO -> "Includes audio"
                    ChannelContentType.LINKS -> "Includes a link"
                }) }
                duration?.let { add("Video is ${durationLabel(it / 1000)} long; at least ${durationLabel(rule.minVideoSeconds!!)} required") }
                if (rule.excludeContentTypes.isNotEmpty()) {
                    val excluded = ChannelContentType.entries.filter { it in rule.excludeContentTypes }.map { when (it) {
                        ChannelContentType.TEXT -> "text-only posts"
                        ChannelContentType.IMAGES -> "images"
                        ChannelContentType.VIDEO -> "video"
                        ChannelContentType.AUDIO -> "audio"
                        ChannelContentType.LINKS -> "links"
                    } }
                    add("No fetched copy reports ${excluded.joinToString(" or ")}.")
                }
            }
            postKey(edition.post) to reasons
        }
        if (matches.isEmpty()) return null
        return ChannelMatch(matches.first().second, matches.map { it.first }.toSet())
    }

    private fun phrasePattern(term: String) = Regex("(?<!$wordCharacter)${Regex.escape(term)}(?!$wordCharacter)", RegexOption.IGNORE_CASE)
    private fun mediaType(media: Media): ChannelContentType? = when {
        media.type.equals("video", true) || media.mimeType?.startsWith("video/", true) == true -> ChannelContentType.VIDEO
        media.type.equals("audio", true) || media.mimeType?.startsWith("audio/", true) == true -> ChannelContentType.AUDIO
        media.type.equals("image", true) || media.mimeType?.startsWith("image/", true) == true -> ChannelContentType.IMAGES
        else -> null
    }
    private fun linkIdentity(value: String): String? = runCatching {
        val canonical = SocialLinkHosts.canonical(value) ?: value
        val uri = URI(canonical)
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") || uri.host == null) null
        else {
            val platform=SocialLinkHosts.platform(canonical)
            val identity=platform?.let {ContentIdentity.key(it,canonical)}
            if(platform!=null&&identity?.startsWith("post:")==true)"${platform.id}:$identity"
            else URI(uri.scheme.lowercase(Locale.ROOT), uri.authority.lowercase(Locale.ROOT), uri.path, uri.query, null).toString()
        }
    }.getOrNull()
    private class Edition(val post: Post, val text: String) {
        val tags = hashtagsInText.findAll(text).map { folded(it.groupValues[1]) }.toSet()
        val types: Set<ChannelContentType> by lazy {
            val media = post.media.mapNotNull(::mediaType).toSet()
            val own = linkIdentity(post.canonicalUrl)
            val assets = post.media.flatMap { listOfNotNull(it.url, it.preview) }.mapNotNull(::linkIdentity).toSet()
            val link = (post.links.orEmpty().asSequence() + urlsInText.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '}', ';') })
                .mapNotNull(::linkIdentity).any { it != own && it !in assets }
            buildSet {
                addAll(media)
                if (link) add(ChannelContentType.LINKS)
                if (post.media.isEmpty() && !link && text.isNotBlank()) add(ChannelContentType.TEXT)
            }
        }
    }
    private fun durationLabel(seconds: Long): String {
        if (seconds < 60) return "$seconds ${if (seconds == 1L) "second" else "seconds"}"
        val minutes = seconds / 60
        val remainder = seconds % 60
        val base = "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
        return if (remainder == 0L) base else "$base $remainder ${if (remainder == 1L) "second" else "seconds"}"
    }
}
