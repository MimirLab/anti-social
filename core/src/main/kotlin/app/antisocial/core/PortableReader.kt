/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

enum class PortableKind { STATE, SOURCES }
enum class PortableOrigin { NATIVE, OPML }

/** Parsed data only. A recipient must preview and explicitly select sources before applying it. */
data class PortableDocument(
    val kind: PortableKind,
    val createdAt: Long,
    val sources: List<Follow>,
    val channels: List<PersonalChannel> = emptyList(),
    val statePayload: JsonObject? = null,
    val progress: JsonObject? = null,
    val warnings: List<String> = emptyList(),
    val title: String? = null,
    val origin: PortableOrigin = PortableOrigin.NATIVE
)

/** Versioned, bounded interchange. Parsing never subscribes, fetches, executes extensions or changes settings. */
object PortableReader {
    const val FORMAT = "app.antisocial.reader"
    const val VERSION = 1
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_SOURCES = 4096
    private const val MAX_DEPTH = 32
    private const val MAX_NODES = 500_000
    private const val MAX_ID = 8192
    private const val MAX_LABEL = 512
    private const val MAX_FOLDER = 1024
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create()
    private val stateSourceKeys = setOf("id", "platform", "kind", "identity", "url", "name", "folder", "enabled", "feedUrl", "creatorId", "creatorName")
    private val packSourceKeys = setOf("platform", "kind", "identity", "url", "name", "feedUrl")
    private val channelKeys = setOf("id", "name", "subscriptionIds", "keywords", "hashtags", "contentTypes", "excludeKeywords", "excludeHashtags", "excludeSubscriptionIds", "minVideoSeconds", "revision", "excludeContentTypes")

    /** Malformed UTF-8 must not silently change a source URL, literal rule or saved text. */
    fun parse(bytes: ByteArray): PortableDocument {
        require(bytes.size <= MAX_BYTES) { "This file is larger than 16 MiB." }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) { throw IllegalArgumentException("Use a UTF-8 reader export or OPML file.", e) }
        return parse(text)
    }

    fun parse(text: String): PortableDocument = if (text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("<")) decodeOpml(text) else decode(text)

    fun encode(document: PortableDocument): String {
        require(document.kind == PortableKind.STATE || (document.channels.isEmpty() && document.statePayload == null && document.progress == null)) {
            "A source pack can contain sources only."
        }
        val publicSources = if (document.kind == PortableKind.SOURCES) document.sources.map(::packSource) else document.sources
        validateDocument(document.copy(sources = publicSources))
        val root = JsonObject().apply {
            addProperty("format", FORMAT); addProperty("version", VERSION)
            addProperty("kind", if (document.kind == PortableKind.STATE) "state" else "sources")
            addProperty("createdAt", document.createdAt)
            add("title", document.title?.let(::comString) ?: JsonNull.INSTANCE)
            add("sources", JsonArray().apply { publicSources.forEach { add(sourceJson(it, document.kind)) } })
            if (document.kind == PortableKind.STATE) {
                add("channels", JsonArray().apply { document.channels.forEach { add(channelJson(it)) } })
                add("statePayload", document.statePayload?.deepCopy() ?: JsonNull.INSTANCE)
                document.progress?.let { add("progress", it.deepCopy()) }
            }
        }
        return gson.toJson(root).also { bounded(it); strictJson(it) }
    }

    fun decode(text: String): PortableDocument {
        bounded(text)
        val clean = text.removePrefix("\uFEFF")
        strictJson(clean)
        val root = requireObject(JsonParser.parseString(clean), "reader export")
        val base = setOf("format", "version", "kind", "createdAt", "title", "sources")
        require(root.get("format")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString == FORMAT) {
            "This is not an anti social reader export. NewPipe video subscriptions use a different format."
        }
        val kind = when (root.string("kind")) {
            "state" -> PortableKind.STATE
            "sources" -> PortableKind.SOURCES
            else -> throw IllegalArgumentException("Unsupported reader export kind.")
        }
        root.keys(base + if (kind == PortableKind.STATE) setOf("channels", "statePayload") else emptySet(), if (kind == PortableKind.STATE) setOf("progress") else emptySet())
        require(root.integer("version") == VERSION.toLong()) { "Unsupported reader export version." }
        val sources = root.array("sources", MAX_SOURCES).map { sourceFromJson(requireObject(it, "source"), kind) }
        val channels = if (kind == PortableKind.STATE) root.array("channels", PersonalChannels.MAX_CHANNELS).map { channelFromJson(requireObject(it, "channel")) } else emptyList()
        val payload = if (kind == PortableKind.STATE) root.nullableObject("statePayload") else null
        val progress = if (root.has("progress")) root.nullableObject("progress").also { require(it != null) { "Reading progress must be an object when included." } } else null
        val document = PortableDocument(kind, root.integer("createdAt"), sources, channels, payload, progress, title = root.nullableString("title", 160))
        validateDocument(document)
        return document.copy(warnings = missingChannelWarnings(document))
    }

    /** Ordinary RSS/Atom sources only. Social routing, rules, local state and extensions use native JSON. */
    fun encodeOpml(sources: List<Follow>, title: String = "anti social subscriptions"): String {
        require(sources.size <= MAX_SOURCES) { "Too many sources." }
        checkText(title, "OPML title", 160, false)
        val ordinary = sources.filter { it.target.platform == Platform.RSS }
        ordinary.forEach(::validateSource)
        require(ordinary.map { rssUrl(it) }.distinct().size == ordinary.size) { "Repeated feed URL." }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n  <head><title>")
            append(xml(title)); append("</title></head>\n  <body>\n")
            ordinary.groupBy { it.group }.forEach { (folder, follows) ->
                if (folder.isNotEmpty()) append("    <outline text=\"").append(xml(folder)).append("\">\n")
                follows.forEach { follow ->
                    append(if (folder.isEmpty()) "    " else "      ")
                    append("<outline type=\"rss\" text=\"").append(xml(follow.name)).append("\" title=\"")
                    append(xml(follow.name)).append("\" xmlUrl=\"").append(xml(rssUrl(follow))).append("\"/>\n")
                }
                if (folder.isNotEmpty()) append("    </outline>\n")
            }
            append("  </body>\n</opml>\n")
        }.also(::bounded)
    }

    /** SAX parsing has no external resolver, DTDs, entities, XInclude or executable FreshRSS extensions. */
    fun decodeOpml(text: String): PortableDocument {
        bounded(text)
        require(!Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "OPML declarations and external entities are not supported." }
        val handler = OpmlHandler()
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                runCatching { isXIncludeAware = false }
                // Android and desktop expose different parser feature sets. The
                // lexical declaration guard and refusing resolver remain mandatory.
                listOf(
                    "http://xml.org/sax/features/external-general-entities" to false,
                    "http://xml.org/sax/features/external-parameter-entities" to false,
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
                    "http://apache.org/xml/features/disallow-doctype-decl" to true
                ).forEach { (key, value) -> runCatching { setFeature(key, value) } }
            }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External OPML resources are not supported.") }
            runCatching { reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler) }
            reader.parse(InputSource(StringReader(text.removePrefix("\uFEFF"))))
        } catch (e: Exception) {
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException("This OPML file could not be read safely: ${e.message?.take(160).orEmpty()}", e)
        }
        return handler.document()
    }

    private fun packSource(source: Follow): Follow {
        validateSource(source)
        val target = if (source.target.platform == Platform.RSS) RssSources.target(rssUrl(source)) else source.target
        return Follow(sourceId(target), target, source.name, feedUrl = target.canonicalUrl.takeIf { target.platform == Platform.RSS })
    }

    private fun validateDocument(document: PortableDocument) {
        require(document.createdAt >= 0) { "Invalid export time." }
        document.title?.let { checkText(it, "export title", 160, false) }
        require(document.sources.size <= MAX_SOURCES) { "Too many sources." }
        document.sources.forEach(::validateSource)
        require(document.sources.map { it.id }.distinct().size == document.sources.size) { "Repeated source ID." }
        require(document.sources.map { it.target.platform to it.target.canonicalUrl }.distinct().size == document.sources.size) { "Repeated source URL." }
        require(document.channels.size <= PersonalChannels.MAX_CHANNELS) { "Too many channels." }
        require(document.channels.map { it.id }.distinct().size == document.channels.size) { "Repeated channel ID." }
        document.channels.forEach { require(PersonalChannels.validate(it).isEmpty()) { "Invalid channel rules." } }
        require(document.kind == PortableKind.STATE || (document.channels.isEmpty() && document.statePayload == null && document.progress == null)) { "A source pack can contain sources only." }
        // Opaque Android-owned payloads still receive duplicate/depth/type syntax
        // checks at serialization; the Android service validates their own schema.
    }

    private fun validateSource(source: Follow) {
        checkText(source.id, "source ID", MAX_ID, false)
        checkText(source.target.identity, "source identity", MAX_ID, false)
        checkText(source.name, "source name", MAX_LABEL, false)
        checkText(source.group, "folder", MAX_FOLDER, true)
        publicUrl(source.target.canonicalUrl)
        source.feedUrl?.let(::publicUrl)
        source.personId?.let { checkText(it, "creator ID", MAX_ID, false) }
        source.personName?.let { checkText(it, "creator name", MAX_LABEL, false) }
        require(source.personId != null || source.personName == null) { "A creator name needs a creator ID." }
        if (source.target.platform == Platform.RSS) {
            require(source.target.kind == TargetKind.FEED) { "Ordinary subscriptions need a feed target." }
            require(RssSources.feedUrl(source.target.canonicalUrl) != null) { "Use a public HTTPS RSS or Atom URL." }
            source.feedUrl?.let { require(RssSources.feedUrl(it) != null) { "Use a public HTTPS RSS or Atom URL." } }
        }
    }

    private fun missingChannelWarnings(document: PortableDocument): List<String> {
        val creators = document.sources.map(Subscriptions::id).toSet()
        return document.channels.filter { (it.subscriptionIds + it.excludeSubscriptionIds).any { id -> id !in creators } }
            .map { "“${it.name}” refers to a missing subscription. Its explicit rules are preserved; missing selections do not become All subscriptions." }
    }

    private fun sourceJson(source: Follow, kind: PortableKind) = JsonObject().apply {
        addProperty("platform", source.target.platform.id); addProperty("kind", source.target.kind.name.lowercase(Locale.ROOT))
        addProperty("identity", source.target.identity); addProperty("url", source.target.canonicalUrl); addProperty("name", source.name)
        add("feedUrl", source.feedUrl?.let(::comString) ?: JsonNull.INSTANCE)
        if (kind == PortableKind.STATE) {
            addProperty("id", source.id); addProperty("folder", source.group); addProperty("enabled", source.enabled)
            add("creatorId", source.personId?.let(::comString) ?: JsonNull.INSTANCE)
            add("creatorName", source.personName?.let(::comString) ?: JsonNull.INSTANCE)
        }
    }

    private fun sourceFromJson(value: JsonObject, kind: PortableKind): Follow {
        value.keys(if (kind == PortableKind.STATE) stateSourceKeys else packSourceKeys)
        val platform = Platform.entries.firstOrNull { it.id == value.string("platform") } ?: throw IllegalArgumentException("Unsupported source platform.")
        val targetKind = TargetKind.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == value.string("kind") } ?: throw IllegalArgumentException("Unsupported source kind.")
        val target = Target(platform, targetKind, value.string("identity"), value.string("url"))
        val feed = value.nullableString("feedUrl", MAX_ID)
        if (kind == PortableKind.SOURCES) {
            require(feed == null || (platform == Platform.RSS && feed == target.canonicalUrl)) { "Source packs cannot set alternate retrieval URLs." }
        }
        return Follow(
            if (kind == PortableKind.STATE) value.string("id") else sourceId(target), target, value.string("name"),
            if (kind == PortableKind.STATE) value.string("folder") else "", if (kind == PortableKind.STATE) value.boolean("enabled") else true, feed,
            if (kind == PortableKind.STATE) value.nullableString("creatorId", MAX_ID) else null,
            if (kind == PortableKind.STATE) value.nullableString("creatorName", MAX_LABEL) else null
        )
    }

    private fun channelJson(channel: PersonalChannel) = JsonObject().apply {
        addProperty("id", channel.id); addProperty("name", channel.name); addProperty("revision", channel.revision)
        add("subscriptionIds", strings(channel.subscriptionIds.sorted())); add("keywords", strings(channel.keywords)); add("hashtags", strings(channel.hashtags))
        add("contentTypes", strings(channel.contentTypes.map { it.name }.sorted())); add("excludeContentTypes", strings(channel.excludeContentTypes.map { it.name }.sorted()))
        add("excludeKeywords", strings(channel.excludeKeywords)); add("excludeHashtags", strings(channel.excludeHashtags)); add("excludeSubscriptionIds", strings(channel.excludeSubscriptionIds.sorted()))
        add("minVideoSeconds", channel.minVideoSeconds?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }

    private fun channelFromJson(value: JsonObject): PersonalChannel {
        value.keys(channelKeys)
        fun list(key: String, max: Int) = value.array(key, max).map { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "Invalid channel $key." }; element.asString
        }.also { require(it.distinct().size == it.size) { "Repeated channel $key." } }
        fun types(key: String) = list(key, ChannelContentType.entries.size).map { name -> ChannelContentType.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("Unsupported channel content type.") }.toSet()
        return PersonalChannel(
            id = value.string("id"), name = value.string("name"), subscriptionIds = list("subscriptionIds", PersonalChannels.MAX_SUBSCRIPTIONS).toSet(),
            keywords = list("keywords", PersonalChannels.MAX_TERMS), hashtags = list("hashtags", PersonalChannels.MAX_TERMS), contentTypes = types("contentTypes"),
            excludeKeywords = list("excludeKeywords", PersonalChannels.MAX_TERMS), excludeHashtags = list("excludeHashtags", PersonalChannels.MAX_TERMS),
            excludeSubscriptionIds = list("excludeSubscriptionIds", PersonalChannels.MAX_SUBSCRIPTIONS).toSet(),
            minVideoSeconds = if (value["minVideoSeconds"].isJsonNull) null else value.integer("minVideoSeconds"), revision = value.integer("revision"), excludeContentTypes = types("excludeContentTypes")
        )
    }

    private fun rssUrl(source: Follow) = requireNotNull(RssSources.feedUrl(source.feedUrl ?: source.target.canonicalUrl)) { "Use a public HTTPS RSS or Atom URL." }
    private fun sourceId(target: Target) = if (target.platform == Platform.RSS) RssSources.followId(target.canonicalUrl) else "${target.platform.id}:${target.canonicalUrl}"
    private fun publicUrl(value: String) {
        require(value.length in 1..MAX_ID && !value.any(Char::isISOControl)) { "Invalid public source URL." }
        val url = value.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "Sources must use HTTPS URLs without credentials." }
    }
    private fun checkText(value: String, label: String, max: Int, empty: Boolean) {
        require(value.length <= max && (empty || value.isNotBlank()) && !value.any(Char::isISOControl)) { "Invalid $label." }
    }
    private fun bounded(text: String) {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "This file is larger than 16 MiB." }
        require('\u0000' !in text && validUnicode(text)) { "This file contains invalid Unicode text." }
    }
    private fun validUnicode(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c.isHighSurrogate()) { if (i >= text.length || !text[i++].isLowSurrogate()) return false }
            else if (c.isLowSurrogate()) return false
        }
        return true
    }
    private fun strictJson(text: String) {
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                var nodes = 0
                fun read(depth: Int) {
                    require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "Reader export is too complex." }
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject(); val names = HashSet<String>()
                            while (reader.hasNext()) { val name=reader.nextName();require(validUnicode(name)&&name.length<=8192&&names.add(name)) { "Repeated or invalid JSON field." }; read(depth + 1) }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> { reader.beginArray(); while (reader.hasNext()) read(depth + 1); reader.endArray() }
                        JsonToken.STRING -> require(validUnicode(reader.nextString())) { "Invalid Unicode text." }
                        JsonToken.NUMBER -> { val n=reader.nextString();require(n.length<=128) { "JSON number is too large." } }
                        JsonToken.BOOLEAN -> { val ignored = reader.nextBoolean(); if (ignored) Unit }
                        JsonToken.NULL -> reader.nextNull()
                        else -> throw IllegalArgumentException("Invalid JSON data.")
                    }
                }
                read(0); require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after reader export." }
            }
        } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) { throw IllegalArgumentException("Invalid reader JSON.", e) }
    }
    private fun requireObject(value: JsonElement, label: String) = value.takeIf { it.isJsonObject }?.asJsonObject ?: throw IllegalArgumentException("Invalid $label.")
    private fun JsonObject.keys(required: Set<String>, optional: Set<String> = emptySet()) {
        require(keySet().containsAll(required) && keySet().all { it in required || it in optional }) { "Missing or unsupported reader export fields." }
    }
    private fun JsonObject.string(key: String): String {
        val value = get(key); require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid $key." }; return value.asString
    }
    private fun JsonObject.nullableString(key: String, max: Int): String? = if (get(key)?.isJsonNull == true) null else string(key).also { checkText(it, key, max, false) }
    private fun JsonObject.boolean(key: String): Boolean {
        val value = get(key); require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Invalid $key." }; return value.asBoolean
    }
    private fun JsonObject.integer(key: String): Long {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Invalid integer $key." }
        return value.asString.toLongOrNull() ?: throw IllegalArgumentException("Integer $key is out of range.")
    }
    private fun JsonObject.array(key: String, max: Int): JsonArray {
        val value = get(key); require(value != null && value.isJsonArray && value.asJsonArray.size() <= max) { "Invalid or oversized $key." }; return value.asJsonArray
    }
    private fun JsonObject.nullableObject(key: String): JsonObject? = if (get(key)?.isJsonNull == true) null else get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: throw IllegalArgumentException("Invalid $key.")
    private fun comString(value: String) = com.google.gson.JsonPrimitive(value)
    private fun strings(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private class OpmlHandler : DefaultHandler(), LexicalHandler {
        private data class Frame(val name: String, val folder: String?, val inBody: Boolean)
        private val stack = ArrayList<Frame>()
        private val sources = ArrayList<Follow>()
        private val warnings = LinkedHashSet<String>()
        private val urls = HashSet<String>()
        private val title = StringBuilder()
        private var titleDepth = -1
        private var rootSeen = false
        private var bodySeen = false
        private var nodes = 0
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            require(++nodes <= 20_000 && stack.size < MAX_DEPTH && attributes.length <= 64) { "OPML is too complex." }
            val name = localName?.takeIf { it.isNotBlank() } ?: qName.orEmpty()
            require(name.length <= 128 && attributes.let { a -> (0 until a.length).all { a.getValue(it).length <= MAX_ID } }) { "OPML attributes are too large." }
            if (stack.isEmpty()) {
                require(!rootSeen && name == "opml" && uri.isNullOrEmpty()) { "Expected an OPML subscription list." }
                require(attributes.getValue("version") in setOf("1.0", "1.1", "2.0")) { "Unsupported OPML version." }; rootSeen = true
            }
            val parent = stack.lastOrNull()
            if (name == "body" && stack.size == 1) { require(!bodySeen) { "Repeated OPML body." }; bodySeen = true }
            val inBody = parent?.inBody == true || (name == "body" && stack.size == 1)
            var folder: String? = null
            if (name == "outline" && inBody && uri.isNullOrEmpty()) {
                val raw = attributes.getValue("xmlUrl")
                val label = (attributes.getValue("text") ?: attributes.getValue("title")).orEmpty().trim()
                if (raw != null) {
                    require(sources.size < MAX_SOURCES) { "Too many OPML feeds." }
                    val type = attributes.getValue("type").orEmpty().lowercase(Locale.ROOT)
                    val extensions = (0 until attributes.length).any { !attributes.getURI(it).isNullOrBlank() }
                    val feed = RssSources.feedUrl(raw)
                    when {
                        type !in setOf("", "rss", "atom") -> warn("Skipped a source with unsupported OPML type “${type.take(80)}”. FreshRSS scraping and executable extensions are not imported.")
                        feed == null -> warn("Skipped a feed without a public HTTPS URL, or with credentials, a fragment or a private address.")
                        !urls.add(feed) -> warn("Repeated OPML feed URLs were listed once.")
                        else -> {
                            val path = stack.mapNotNull { it.folder }.joinToString(" / ")
                            require(path.length <= MAX_FOLDER) { "OPML folder path is too long." }
                            val target = RssSources.target(feed)
                            val nameText = label.ifBlank { target.canonicalUrl.toHttpUrlOrNull()!!.host }
                            checkText(nameText, "feed name", MAX_LABEL, false)
                            sources += Follow(RssSources.followId(target.canonicalUrl), target, nameText, path, feedUrl = target.canonicalUrl)
                            if (extensions) warn("OPML extension settings are not imported. Feeds use this device’s reader and privacy settings.")
                        }
                    }
                } else if (!attributes.getValue("type").isNullOrBlank()) {
                    warn("Skipped an OPML source without a supported xmlUrl. Remote inclusion and link outlines are not fetched.")
                } else if (label.isNotEmpty()) {
                    checkText(label, "folder", MAX_FOLDER, false); folder = label
                    if ((0 until attributes.length).any { attributes.getLocalName(it) == "opmlUrl" }) warn("Dynamic OPML subscriptions were not fetched. Only feeds included in this file are shown.")
                }
            }
            if (name == "title" && stack.size == 2 && parent?.name == "head") titleDepth = stack.size + 1
            stack += Frame(name, folder, inBody)
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (stack.size == titleDepth) { require(title.length + length <= 160) { "OPML title is too long." }; title.append(ch, start, length) }
        }
        override fun endElement(uri: String?, localName: String?, qName: String?) { if (stack.size == titleDepth) titleDepth = -1; stack.removeAt(stack.lastIndex) }
        override fun skippedEntity(name: String?) { throw SAXException("OPML entities are not supported.") }
        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("OPML DTDs are not supported.") }
        override fun endDTD() = Unit
        override fun startEntity(name: String?) = Unit
        override fun endEntity(name: String?) = Unit
        override fun startCDATA() = Unit
        override fun endCDATA() = Unit
        override fun comment(ch: CharArray?, start: Int, length: Int) = Unit
        private fun warn(message: String) { if (warnings.size < 32) warnings += message }
        fun document(): PortableDocument {
            require(rootSeen && bodySeen && stack.isEmpty()) { "Expected an OPML body." }
            return PortableDocument(PortableKind.SOURCES, 0, sources.toList(), warnings = warnings.toList(), title = title.toString().trim().takeIf { it.isNotEmpty() }, origin = PortableOrigin.OPML)
        }
    }
}
