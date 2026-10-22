/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PortableReaderTest {
    private val social = Follow("local-account", Target(Platform.X, TargetKind.PROFILE, "workshop", "https://x.com/workshop"), "Workshop", "Work / Releases", false,
        "https://reader.example/workshop/rss", "person-workshop", "My workshop")
    private val rss = Follow("local-blog", RssSources.target("https://blog.example/feed.xml"), "Workshop blog", "Work / Releases", true,
        "https://blog.example/feed.xml", "person-workshop", "My workshop")
    private val rule = PersonalChannel("releases", "Software releases", subscriptionIds = setOf("person-workshop"), keywords = listOf("release", "new build"),
        hashtags = listOf("software"), contentTypes = setOf(ChannelContentType.LINKS), excludeKeywords = listOf("giveaway"), excludeHashtags = listOf("ad"),
        excludeContentTypes = setOf(ChannelContentType.VIDEO), revision = 4)
    private fun state() = PortableDocument(PortableKind.STATE, 1_792_200_000_000, listOf(social, rss), listOf(rule),
        JsonObject().apply { add("settings", JsonObject().apply { addProperty("theme", "dark"); addProperty("name", "日本語 🛠") }); add("library", JsonObject()) },
        JsonObject().apply { addProperty("version", 1); add("contexts", com.google.gson.JsonArray()) }, title = "My reader")
    private fun pack() = PortableDocument(PortableKind.SOURCES, 1_792_200_000_000, listOf(social, rss), title = "Software sources")
    private fun json() = JsonParser.parseString(PortableReader.encode(state())).asJsonObject
    private fun rejects(value: String) { assertThrows(IllegalArgumentException::class.java) { PortableReader.parse(value) } }
    private fun opml(body: String, head: String = "") = "<?xml version=\"1.0\"?><opml version=\"2.0\"><head>$head</head><body>$body</body></opml>"

    @Test fun stateRoundTripPreservesActualIdentityFoldersRulesAndOpaqueState() {
        val initial = state()
        val decoded = PortableReader.decode(PortableReader.encode(initial))
        assertEquals(initial, decoded)
        assertEquals(listOf(social, rss), decoded.sources)
        assertEquals(rule, decoded.channels.single())
        assertEquals("日本語 🛠", decoded.statePayload!!.getAsJsonObject("settings")["name"].asString)
    }

    @Test fun progressIsOptionalAndAbsentFromExportWhenNotSelected() {
        val encoded = PortableReader.encode(state().copy(progress = null))
        assertFalse(JsonParser.parseString(encoded).asJsonObject.has("progress"))
        assertNull(PortableReader.decode(encoded).progress)
        rejects(json().apply { add("progress", com.google.gson.JsonNull.INSTANCE) }.toString())
    }

    @Test fun sourcePacksContainOnlyPublicSourceMetadata() {
        val encoded = PortableReader.encode(pack())
        val decoded = PortableReader.decode(encoded)
        assertTrue(decoded.channels.isEmpty()); assertNull(decoded.statePayload); assertNull(decoded.progress)
        assertFalse(encoded.contains("local-account")); assertFalse(encoded.contains("person-workshop")); assertFalse(encoded.contains("reader.example"))
        assertFalse(encoded.contains("folder")); assertFalse(encoded.contains("enabled")); assertFalse(encoded.contains("creatorName"))
        assertEquals("twitter:https://x.com/workshop", decoded.sources[0].id)
        assertTrue(decoded.sources.all { it.enabled && it.group.isEmpty() && it.personId == null && it.personName == null })
        assertNull(decoded.sources[0].feedUrl)
        assertEquals("https://blog.example/feed.xml", decoded.sources[1].feedUrl)
    }

    @Test fun sourcePacksCannotSmuggleRulesSettingsProgressOrRetrievalOverrides() {
        assertThrows(IllegalArgumentException::class.java) { PortableReader.encode(pack().copy(channels = listOf(rule))) }
        assertThrows(IllegalArgumentException::class.java) { PortableReader.encode(pack().copy(statePayload = JsonObject())) }
        assertThrows(IllegalArgumentException::class.java) { PortableReader.encode(pack().copy(progress = JsonObject())) }
        val value = JsonParser.parseString(PortableReader.encode(pack())).asJsonObject
        rejects(value.deepCopy().apply { add("statePayload", JsonObject()) }.toString())
        value["sources"].asJsonArray[0].asJsonObject.addProperty("feedUrl", "https://reader.example/tracking")
        rejects(value.toString())
    }

    @Test fun missingChannelSourcesArePreservedWithAnExplicitWarning() {
        val original = state().copy(channels = listOf(rule.copy(subscriptionIds = setOf("removed-creator"))))
        val parsed = PortableReader.decode(PortableReader.encode(original))
        assertEquals(setOf("removed-creator"), parsed.channels.single().subscriptionIds)
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.warnings.single().contains("missing subscription"))
        assertTrue(PersonalChannels.accounts(parsed.channels.single(), parsed.sources).isEmpty())
    }

    @Test fun duplicateFieldsCannotReplaceRulesOrNestedSavedData() {
        rejects(PortableReader.encode(state()).replace("\"version\": 1", "\"version\": 1, \"version\": 2"))
        rejects(PortableReader.encode(state()).replace("\"theme\": \"dark\"", "\"theme\": \"light\", \"theme\": \"dark\""))
        rejects(PortableReader.encode(state()).replace("\"keywords\": [", "\"keywords\": [], \"keywords\": ["))
    }

    @Test fun malformedJsonIsNeverLenientlyAccepted() {
        listOf("//comment\n", "/*comment*/").forEach { rejects(it + PortableReader.encode(state())) }
        rejects(PortableReader.encode(state()) + "{}")
        rejects(PortableReader.encode(state()).replace("\"version\": 1", "version: 1"))
        rejects(PortableReader.encode(state()).replace("\"version\": 1", "\"version\": NaN"))
        rejects(PortableReader.encode(state()).replace("\"version\": 1", "\"version\": 01"))
    }

    @Test fun unsupportedVersionsPlatformsTypesAndExtraFieldsFailClosed() {
        rejects(json().apply { addProperty("version", 2) }.toString())
        rejects(json().apply { addProperty("version", "1") }.toString())
        rejects(json().apply { addProperty("version", 1.0) }.toString())
        rejects(json().apply { addProperty("other", true) }.toString())
        rejects(json().apply { getAsJsonArray("sources")[0].asJsonObject.addProperty("platform", "unsupported-fixture-platform") }.toString())
        rejects(json().apply { getAsJsonArray("channels")[0].asJsonObject["contentTypes"].asJsonArray.add("UNKNOWN") }.toString())
    }

    @Test fun requiredFieldsNullsAndWrongScalarTypesAreRejected() {
        rejects(json().apply { remove("sources") }.toString())
        rejects(json().apply { getAsJsonArray("sources")[0].asJsonObject.remove("enabled") }.toString())
        rejects(json().apply { getAsJsonArray("sources")[0].asJsonObject.addProperty("enabled", "false") }.toString())
        rejects(json().apply { getAsJsonArray("channels")[0].asJsonObject.addProperty("minVideoSeconds", "300") }.toString())
        rejects(json().apply { getAsJsonArray("channels")[0].asJsonObject.addProperty("revision", 0) }.toString())
    }

    @Test fun duplicateIdsUrlsAndRulesAreRejectedRatherThanArbitrarilySelected() {
        assertThrows(IllegalArgumentException::class.java) { PortableReader.encode(state().copy(sources = listOf(social, social.copy(id = "different")))) }
        assertThrows(IllegalArgumentException::class.java) { PortableReader.encode(state().copy(sources = listOf(social, rss.copy(id = social.id)))) }
        assertThrows(IllegalArgumentException::class.java) { PortableReader.encode(state().copy(channels = listOf(rule, rule))) }
        rejects(json().apply { getAsJsonArray("channels")[0].asJsonObject.getAsJsonArray("keywords").add("release") }.toString())
    }

    @Test fun sourceUrlsMustBeHttpsAndCannotCarryCredentials() {
        listOf("http://x.com/workshop", "javascript:alert(1)", "https://secret:password@x.com/workshop").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { PortableReader.encode(state().copy(sources = listOf(social.copy(target = social.target.copy(canonicalUrl = url))))) }
        }
        assertThrows(IllegalArgumentException::class.java) { PortableReader.encode(state().copy(sources = listOf(rss.copy(target = rss.target.copy(canonicalUrl = "https://localhost/feed"))))) }
    }

    @Test fun malformedUtf8AndUnpairedUnicodeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PortableReader.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
        rejects(PortableReader.encode(state()).replace("My reader", "\\uD800"))
        rejects(PortableReader.encode(state()).replace("My reader", "\\uDC00"))
        rejects(PortableReader.encode(state()).replace("My reader", "\uD800"))
        val unicode = PortableReader.parse(PortableReader.encode(state()).toByteArray(Charsets.UTF_8))
        assertEquals(state(), unicode)
    }

    @Test fun oversizedDeepAndComplexInputsHaveExplicitBounds() {
        assertThrows(IllegalArgumentException::class.java) { PortableReader.parse(ByteArray(PortableReader.MAX_BYTES + 1)) }
        rejects("[".repeat(40) + "0" + "]".repeat(40))
        rejects("{\"n\":" + "1".repeat(129) + "}")
        assertThrows(IllegalArgumentException::class.java) { PortableReader.encode(pack().copy(sources = List(PortableReader.MAX_SOURCES + 1) { social })) }
    }

    @Test fun standardOpmlImportsAtomRssNestedFoldersAndUtf8Labels() {
        val parsed = PortableReader.parse(opml("""
            <outline text="Technology"><outline text="Blogs &amp; tools">
              <outline text="日本語 🛠" type="rss" xmlUrl="https://blog.example/feed.xml" htmlUrl="https://blog.example/"/>
              <outline title="Other" type="atom" xmlUrl="https://other.example/atom.xml"/>
            </outline></outline>
        """.trimIndent(), "<title>My sources</title>"))
        assertEquals(PortableOrigin.OPML, parsed.origin); assertEquals(PortableKind.SOURCES, parsed.kind)
        assertEquals("My sources", parsed.title)
        assertEquals(2, parsed.sources.size)
        assertEquals("Technology / Blogs & tools", parsed.sources[0].group)
        assertEquals("日本語 🛠", parsed.sources[0].name)
        assertTrue(parsed.sources.all { it.target.platform == Platform.RSS && it.target.kind == TargetKind.FEED })
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test fun opmlExportIsInteroperableAndEscapesExactNamesAndFolderLabels() {
        val initial = rss.copy(group = "Research & tools / Local", name = "Tools <new> \"release\"")
        val encoded = PortableReader.encodeOpml(listOf(social, initial), "Blogs & tools")
        assertFalse(encoded.contains("x.com")); assertFalse(encoded.contains("creatorId")); assertFalse(encoded.contains("enabled"))
        assertTrue(encoded.contains("xmlUrl=\"https://blog.example/feed.xml\"")); assertTrue(encoded.contains("&lt;new&gt;"))
        val parsed = PortableReader.decodeOpml(encoded)
        assertEquals(initial.name, parsed.sources.single().name)
        assertEquals(initial.group, parsed.sources.single().group)
        assertEquals("Blogs & tools", parsed.title)
    }

    @Test fun opmlSkipsUnsupportedScrapingCredentialsHttpAndDuplicateFeedsExplicitly() {
        val parsed = PortableReader.decodeOpml(opml("""
            <outline text="scraper" type="HTML+XPath" xmlUrl="https://scrape.example/"/>
            <outline text="http" type="rss" xmlUrl="http://old.example/feed"/>
            <outline text="secret" type="rss" xmlUrl="https://user:password@auth.example/feed"/>
            <outline text="private" type="rss" xmlUrl="https://127.0.0.1/feed"/>
            <outline text="Good" type="rss" xmlUrl="https://good.example/feed"/>
            <outline text="duplicate" type="rss" xmlUrl="https://good.example/feed"/>
        """.trimIndent()))
        assertEquals(listOf("Good"), parsed.sources.map { it.name })
        assertEquals(3, parsed.warnings.size)
        assertTrue(parsed.warnings.any { it.contains("unsupported OPML type") })
        assertTrue(parsed.warnings.any { it.contains("HTTPS") })
        assertTrue(parsed.warnings.any { it.contains("listed once") })
    }

    @Test fun freshrssExtensionsNeverBecomeCookiesProxyRulesOrRequests() {
        val parsed = PortableReader.decodeOpml(opml("""
            <outline xmlns:frss="https://freshrss.org/opml" text="Dynamic" frss:opmlUrl="https://uncontacted.example/list.opml">
              <outline text="Included" type="rss" xmlUrl="https://included.example/feed" frss:CURLOPT_COOKIE="session=secret" frss:CURLOPT_PROXY="https://proxy.example/" frss:filtersActionRead="all"/>
            </outline>
        """.trimIndent()))
        assertEquals(1, parsed.sources.size)
        assertEquals("https://included.example/feed", parsed.sources.single().feedUrl)
        assertTrue(parsed.warnings.any { it.contains("Dynamic OPML") })
        assertTrue(parsed.warnings.any { it.contains("extension settings") })
        val encoded = PortableReader.encode(parsed)
        assertFalse(encoded.contains("session=secret")); assertFalse(encoded.contains("proxy.example")); assertFalse(encoded.contains("uncontacted.example"))
    }

    @Test fun opmlRejectsDtdExternalEntitiesMalformedXmlAndDeepTrees() {
        rejects("<!DOCTYPE opml SYSTEM 'https://uncontacted.example/evil.dtd'>" + opml(""))
        rejects("<!DOCTYPE opml [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>" + opml("<outline text='&secret;'/>"))
        rejects(opml("<outline text='oops'>"))
        rejects(opml("<outline text='folder'>".repeat(40) + "</outline>".repeat(40)))
        rejects("<html><body>Not OPML</body></html>")
        rejects("<opml version='3.0'><body/></opml>")
    }

    @Test fun newpipeVideoJsonIsNotMisrepresentedAsSocialSources() {
        rejects("""{"app_version":"1.0","app_version_int":1,"subscriptions":[{"service_id":0,"url":"https://youtube.com/@example","name":"Example"}]}""")
    }
}
