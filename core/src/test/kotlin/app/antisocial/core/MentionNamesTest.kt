/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MentionNamesTest {
    private val routes = DarwinRoutes(asset("darwin-routes.json"), UrlCleaner(asset("clean-links.json")))
    private fun asset(name: String) = JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private fun find(text: String, platform: Platform = Platform.X, context: String = "https://x.com/reader/status/123", links: List<String> = emptyList()) =
        MentionNames.find(text, platform, context, routes, links)
    private fun account(url: String, platform: Platform? = null) = routes.recognize(url, platform)?.let { MentionNames.account(it, routes) }

    @Test fun ordinaryMentionsKeepExactRangesAndPunctuation() {
        val text = "🛠 Café: thanks, (@alice), @Bob_2!"
        val found = find(text)
        assertEquals(listOf("@alice", "@Bob_2"), found.map { it.token })
        found.forEach { assertEquals(it.token, text.substring(it.start, it.endExclusive)) }
        assertEquals("https://x.com/Bob_2", found.last().target.canonicalUrl)
        assertEquals("twitter|x.com|bob_2", found.last().key.id)
    }

    @Test fun emailsUrlsCredentialsPathsAndCodeAreNotAccountsToLookUp() {
        val text = "mail alice@example.org or \"alice\"@example.org; https://host.org/@alice " +
            "ftp://alice@host.org/x www.host.org/@alice //host.org/@alice path/@alice " +
            "`@alice` ```kotlin\n@alice\n``` @Override @JvmStatic @param @bad() @alice"
        assertEquals(listOf("@alice"), find(text).map { it.token })
        assertTrue(find("\"alice\"@example.org", Platform.BLUESKY).isEmpty())
        assertTrue(find("val recipient = @alice").isEmpty())
    }

    @Test fun malformedTokensDoNotPartiallyBecomeDifferentAccounts() {
        listOf("@@alice", "@alice@example.org", "@alice/posts", "@alice:443", "@this_handle_is_far_too_long", "@alice..", "prefix@alice", "@alice=7").forEach {
            assertTrue("must stay raw: $it", find(it).isEmpty())
        }
        assertEquals("@alice", find("@alice.").single().token)
    }

    @Test fun everySupportedImplicitNamespaceBuildsItsAuthenticProfilePath() {
        val cases = listOf(
            Triple(Platform.X, "@Alice", "https://x.com/Alice"),
            Triple(Platform.BLUESKY, "@alice.bsky.social", "https://bsky.app/profile/alice.bsky.social"),
            Triple(Platform.TUMBLR, "@alice-blog", "https://www.tumblr.com/alice-blog"),
            Triple(Platform.TIKTOK, "@alice.posts", "https://www.tiktok.com/@alice.posts"),
            Triple(Platform.INSTAGRAM, "@alice.posts", "https://www.instagram.com/alice.posts"),
            Triple(Platform.PINTEREST, "@alice", "https://www.pinterest.com/alice"),
            Triple(Platform.IFUNNY, "@alice", "https://ifunny.co/user/alice"),
            Triple(Platform.THREADS, "@alice.posts", "https://www.threads.com/@alice.posts"),
            Triple(Platform.TRUTH_SOCIAL, "@alice", "https://truthsocial.com/@alice"),
            Triple(Platform.GETTR, "@alice", "https://gettr.com/user/alice"),
            Triple(Platform.GAB, "@alice", "https://gab.com/alice"),
            Triple(Platform.TELEGRAM, "@alice", "https://t.me/alice"),
            Triple(Platform.SNAPCHAT, "@alice", "https://www.snapchat.com/add/alice"),
            Triple(Platform.YOUTUBE, "@alice", "https://www.youtube.com/@alice")
        )
        cases.forEach { (platform, token, url) -> assertEquals(platform.label, url, find(token, platform).single().target.canonicalUrl) }
    }

    @Test fun accountKeysNeverCollideBetweenSitesOrFederatedAuthorities() {
        val x = find("@alice").single().key
        val ig = find("@alice", Platform.INSTAGRAM).single().key
        val one = find("@alice@mastodon.social", Platform.MASTODON).single().key
        val two = find("@alice@mstdn.social", Platform.MASTODON).single().key
        val three = find("@alice@www.mstdn.social", Platform.MASTODON).single().key
        assertEquals(5, setOf(x.id, ig.id, one.id, two.id, three.id).size)
        assertEquals(one, find("@ALICE@mastodon.social", Platform.MASTODON).single().key)
    }

    @Test fun unqualifiedFederationUsesThePostsServerAndNeverADefaultGuess() {
        val cases = listOf(
            Triple(Platform.MASTODON, "https://social.example.org/@author/123", "https://social.example.org/@alice"),
            Triple(Platform.LEMMY, "https://lemmy.example.org/post/123", "https://lemmy.example.org/u/alice"),
            Triple(Platform.PIXELFED, "https://photos.example.org/p/author/123", "https://photos.example.org/alice"),
            Triple(Platform.MISSKEY, "https://notes.example.org/notes/123", "https://notes.example.org/@alice")
        )
        cases.forEach { (platform, post, expected) ->
            assertEquals(platform.label, expected, find("@alice", platform, post).single().target.canonicalUrl)
            assertTrue(platform.label, find("@alice", platform, "").isEmpty())
        }
        assertTrue(find("@alice", Platform.MASTODON, "https://pixelfed.social/p/person/123").isEmpty())
    }

    @Test fun qualifiedFederationDoesNotDependOnSourceServer() {
        val actual = find("Reply to @alice@social.example.org.", Platform.MASTODON, "https://other.example.org/@bob/1").single()
        assertEquals("https://social.example.org/@alice", actual.target.canonicalUrl)
        assertEquals("social.example.org", actual.key.authority)
        assertEquals("@alice@social.example.org", actual.token)
    }

    @Test fun privateMalformedCredentialedAndPortedFederationStaysUnresolved() {
        listOf("@alice@localhost", "@alice@127.0.0.1", "@alice@192.168.1.4", "@alice@social.local", "@alice@social.localhost", "@alice@social.internal", "@alice@social.example.org:443", "@alice@social.example.org:8443", "@alice@-bad.example.org", "@alice@social..org").forEach {
            assertTrue("must not route $it", find(it, Platform.MASTODON).isEmpty())
        }
        listOf("https://user:pass@social.example.org/@alice/1", "https://127.0.0.1/@alice/1", "https://social.example.org:8443/@alice/1").forEach {
            assertTrue(find("@alice", Platform.MASTODON, it).isEmpty())
        }
    }

    @Test fun unsupportedOrAmbiguousNamespacesRemainRaw() {
        listOf(Platform.FACEBOOK, Platform.LINKEDIN, Platform.TIEBA, Platform.RSS, Platform.REDDIT).forEach {
            assertTrue(it.label, find("Hello @alice", it).isEmpty())
        }
        assertTrue(find("Hello @alice", Platform.BLUESKY).isEmpty())
        assertTrue(find("Hello @alice..posts", Platform.INSTAGRAM).isEmpty())
        assertTrue(find("Hello @alice.", Platform.TIKTOK).single().token == "@alice")
    }

    @Test fun explicitFacebookVanityAndLinkedInLinksCanResolveWithoutInventingIds() {
        val fb = find("Thanks @AliceSmith", Platform.FACEBOOK, links=listOf("https://www.facebook.com/AliceSmith")).single()
        assertEquals("alicesmith", fb.key.username)
        assertTrue(find("Thanks @123456789", Platform.FACEBOOK, links=listOf("https://www.facebook.com/123456789")).isEmpty())
        val person = find("@alice", Platform.LINKEDIN, links=listOf("https://www.linkedin.com/in/alice")).single()
        val company = find("@alice", Platform.LINKEDIN, links=listOf("https://www.linkedin.com/company/alice")).single()
        assertNotEquals(person.key.id, company.key.id)
        assertTrue(find("@alice", Platform.LINKEDIN, links=listOf(person.target.canonicalUrl, company.target.canonicalUrl)).isEmpty())
    }

    @Test fun rssCanUseAnExactKnownProfileLinkButNotAnUnrelatedOrAmbiguousOne() {
        assertEquals(Platform.X, find("Thanks @alice", Platform.RSS, links=listOf("https://x.com/alice")).single().key.platform)
        assertTrue(find("Thanks @alice", Platform.RSS, links=listOf("https://example.org/alice")).isEmpty())
        assertTrue(find("Thanks @alice", Platform.RSS, links=listOf("https://x.com/alice", "https://www.instagram.com/alice")).isEmpty())
        assertEquals(Platform.X, find("@alice", links=listOf("https://www.instagram.com/alice")).single().key.platform)
    }

    @Test fun redditUsesItsActualUserNotationInsteadOfInventedAtHandles() {
        val found = find("Thanks u/alice and /u/Bob_22, @charlie", Platform.REDDIT)
        assertEquals(listOf("u/alice", "/u/Bob_22"), found.map { it.token })
        assertEquals(listOf("https://www.reddit.com/user/alice", "https://www.reddit.com/user/Bob_22"), found.map { it.target.canonicalUrl })
    }

    @Test fun publicProfileSeedingRejectsPostsWrongPlatformsAndLookalikeHosts() {
        assertNull(account("https://x.com/alice/status/1"))
        assertNull(MentionNames.account(Target(Platform.INSTAGRAM, TargetKind.PROFILE, "alice", "https://x.com/alice"), routes))
        assertNull(MentionNames.account(Target(Platform.X, TargetKind.PROFILE, "alice", "https://x.com.attacker.org/alice"), routes))
        assertNull(account("https://www.youtube.com/watch?v=jNQXAC9IVRw"))
        assertNull(account("https://www.facebook.com/123456789"))
        assertEquals("alice", account("https://xcancel.com/Alice")?.key?.username)
    }

    @Test fun unicodeHandleAndEmojiNamePreserveUtf16TapRanges() {
        val raw = "🎉 @Тест has a new video."
        val mentions = find(raw, Platform.YOUTUBE)
        assertEquals("@Тест", mentions.single().token)
        val presented = MentionNames.present(raw, mentions, mapOf(mentions.single().key.id to "Тест 👩‍💻"))
        assertEquals("🎉 Тест 👩‍💻 has a new video.", presented.text)
        val span = presented.mentions.single()
        assertEquals("Тест 👩‍💻", presented.text.substring(span.start, span.endExclusive))
        assertEquals(raw, presented.rawText)
        assertEquals("@Тест", span.original.token)
    }

    @Test fun replacementsArePresentationOnlyAndUnresolvedTokensRemainTappable() {
        val raw = "@alice met @bob. @alice left."
        val found = find(raw)
        val presented = MentionNames.present(raw, found, mapOf(found.first().key.id to "Alice Example"))
        assertEquals("Alice Example met bob. Alice Example left.", presented.text)
        assertEquals(raw, presented.rawText)
        assertEquals(listOf(true, false, true), presented.mentions.map { it.resolved })
        assertEquals(listOf("@alice", "@bob", "@alice"), presented.mentions.map { it.original.token })
        presented.mentions.forEach { assertEquals(it.displayName, presented.text.substring(it.start, it.endExclusive)) }
    }

    @Test fun untrustedOrOverlappingPresentationRangesCannotRewriteOtherText() {
        val raw = "Keep @alice here."
        val mention = find(raw).single()
        val invalid = listOf(mention.copy(start=-1), mention.copy(token="@wrong"), mention.copy(endExclusive=9_999))
        assertEquals(raw, MentionNames.present(raw, invalid, mapOf(mention.key.id to "Alice Example")).text)
        val result = MentionNames.present(raw, listOf(mention, mention), mapOf(mention.key.id to "Alice Example"))
        assertEquals("Keep Alice Example here.", result.text)
        assertEquals(1, result.mentions.size)
    }

    @Test fun replyRepostAndColonPunctuationUseTheSameNamesAsOrdinaryMentions() {
        val text="R to @WhiteHouse: RT @NASA: Thanks, @alice; @Bob_2's update."
        val found=find(text)
        assertEquals(listOf("@WhiteHouse","@NASA","@alice","@Bob_2"),found.map {it.token})
        val names=found.associate {it.key.id to when(it.key.username){"whitehouse"->"The White House";"nasa"->"NASA";"alice"->"Alice Example";else->"Bob Example"}}
        assertEquals("R to The White House: RT NASA: Thanks, Alice Example; Bob Example's update.",MentionNames.present(text,found,names).text)
        assertTrue(find("@alice:443 @alice:8443").isEmpty())
        assertEquals("@alice",find("Reply:@alice: read this.").single().token)
    }

    @Test fun accountIdentifiersLoseTheirNotationWhileUnresolvedAndKeepTheirExactLinks() {
        val cases=listOf(Triple(Platform.X,"Hi @alice:","Hi alice:"),Triple(Platform.REDDIT,"Ask u/alice or /u/Bob_22.","Ask alice or Bob_22."),Triple(Platform.MASTODON,"Ask @alice@mastodon.social.","Ask alice (mastodon.social)."))
        cases.forEach {(platform,raw,expected)->
            val matches=find(raw,platform);val result=MentionNames.present(raw,matches,emptyMap())
            assertEquals(expected,result.text);assertEquals(raw,result.rawText)
            assertTrue(result.mentions.all {!it.resolved});assertEquals(matches.map {it.target},result.mentions.map {it.original.target})
        }
    }

    @Test fun accountLabelsUseProvenPermalinkOwnersRatherThanGuessingFromDisplayNames() {
        val found=MentionNames.label("NASA",Platform.X,"https://x.com/space_workshop/status/123",routes).single()
        assertEquals("space_workshop",found.key.username)
        assertEquals("https://x.com/space_workshop",found.target.canonicalUrl)
        assertEquals("NASA",MentionNames.present("NASA",listOf(found),emptyMap()).text)
        assertEquals("Space Workshop",MentionNames.present("NASA",listOf(found),mapOf(found.key.id to "Space Workshop")).text)
        assertTrue(MentionNames.label("Space Workshop",Platform.INSTAGRAM,"https://www.instagram.com/p/Example/",routes).isEmpty())
        assertTrue(MentionNames.label("Alice",Platform.X,"https://x.com.attacker.org/Alice/status/1",routes).isEmpty())
        val explicit=MentionNames.label("@OtherAuthor",Platform.X,"https://x.com/space_workshop/status/123",routes).single()
        assertEquals("otherauthor",explicit.key.username)
        assertEquals("https://x.com/OtherAuthor",explicit.target.canonicalUrl)
    }

    @Test fun profileAndRedditAuthorLabelsUseNamesWithoutAddingAccountSyntax() {
        val reddit=MentionNames.label("alice",Platform.REDDIT,"https://www.reddit.com/r/example/comments/abc/title/",routes).single()
        assertEquals("https://www.reddit.com/user/alice",reddit.target.canonicalUrl)
        assertEquals("Alice Example",MentionNames.present("alice",listOf(reddit),mapOf(reddit.key.id to "Alice Example")).text)
        val profile=MentionNames.label("@alice.posts",Platform.INSTAGRAM,"https://www.instagram.com/alice.posts/",routes).single()
        assertEquals("alice.posts",MentionNames.present(profile.token,listOf(profile),emptyMap()).text)
        val federated=MentionNames.label("Garden Team",Platform.MASTODON,"https://social.example.org/@garden/123",routes).single()
        assertEquals("https://social.example.org/@garden",federated.target.canonicalUrl)
        assertEquals("Garden Team",MentionNames.present(federated.token,listOf(federated),emptyMap()).text)
        val prefixed=MentionNames.label("profile/alice.bsky.social",Platform.BLUESKY,"https://bsky.app/profile/alice.bsky.social",routes).single()
        assertEquals("alice.bsky.social",prefixed.key.username)
        assertEquals("alice.bsky.social",MentionNames.present(prefixed.token,listOf(prefixed),emptyMap()).text)
    }

    @Test fun facebookMentionsUseExactSuppliedProfilesAndDoNotGuessFromAVisibleTagName() {
        assertEquals("https://www.facebook.com/AliceSmith",find("@AliceSmith:",Platform.FACEBOOK,links=listOf("https://www.facebook.com/AliceSmith")).single().target.canonicalUrl)
        assertTrue(find("@AliceSmith:",Platform.FACEBOOK).isEmpty())
        assertTrue(find("@123456789",Platform.FACEBOOK).isEmpty())
        assertTrue(find("@alice",Platform.LINKEDIN).isEmpty())
        assertTrue(find("@alice",Platform.LINKEDIN,links=listOf("https://www.linkedin.com/in/alice","https://www.linkedin.com/company/alice")).isEmpty())
    }

    @Test fun displayNameDecorationsDoNotReintroduceHandleNotation() {
        val key=find("@alice").single().key
        assertEquals("Alice Example",MentionNames.sanitizeDisplayName("Alice Example (@Alice)",key))
        assertEquals("Alice Example",MentionNames.sanitizeDisplayName("Alice Example [@alice]",key))
        assertNull(MentionNames.sanitizeDisplayName("Alice @unrelated",key))
        val text="Email alice@example.org; code `@alice`; https://x.com/@alice; ask @alice."
        assertEquals("Email alice@example.org; code `@alice`; https://x.com/@alice; ask alice.",MentionNames.present(text,find(text),emptyMap()).text)
    }

    @Test fun crossSiteNotationRequiresAnExplicitExactProfileRatherThanAGuessedNamespace() {
        val federated=find("Read @alice@mastodon.social:",links=listOf("https://mastodon.social/@alice")).single()
        assertEquals(Platform.MASTODON,federated.key.platform)
        val reddit=find("Ask u/alice:",Platform.RSS,links=listOf("https://www.reddit.com/user/alice")).single()
        assertEquals(Platform.REDDIT,reddit.key.platform)
        assertEquals("Ask Alice Example:",MentionNames.present("Ask u/alice:",listOf(reddit),mapOf(reddit.key.id to "Alice Example")).text)
        assertTrue(find("Ask u/bob:",Platform.RSS,links=listOf("https://www.reddit.com/user/alice")).isEmpty())
        assertTrue(find("Read @alice@mastodon.social:").isEmpty())
    }

    @Test fun leadingDottedReplyNotationDoesNotHideAMentionOrPolluteTheReadingText() {
        val raw=".@VP: “The public workshop is open.”"
        val matches=find(raw);assertEquals("@VP",matches.single().token)
        val result=MentionNames.present(raw,matches,mapOf(matches.single().key.id to "Vice President Example"))
        assertEquals("Vice President Example: “The public workshop is open.”",result.text)
        assertEquals(raw,result.rawText)
        assertEquals("Vice President Example",result.text.substring(result.mentions.single().start,result.mentions.single().endExclusive))
        assertEquals("VP: “The public workshop is open.”",MentionNames.present(raw,matches,emptyMap()).text)
        assertTrue(find("path.@VP path/@VP https://example.org/.@VP `.@VP`").isEmpty())
    }

    @Test fun displayNamesStripControlsBidiAndLineBreaksWithoutBreakingEmoji() {
        val key = find("@alice").single().key
        assertEquals("Alice Example 👩‍💻", MentionNames.sanitizeDisplayName("  Alice\n\u202EExample\u2069 \u0000👩‍💻  ", key))
        assertEquals("Alice Example", MentionNames.sanitizeDisplayName("Alice\u2028Example", key))
        assertEquals("Alice Example", MentionNames.sanitizeDisplayName("Alice\uD800 Example", key))
    }

    @Test fun handlesAddressesUrlsBlankAndOversizedNamesAreNotDisplayNames() {
        val key = find("@alice").single().key
        listOf("", " ", "@alice", "alice@x.com", "https://example.org", "ftp://example.org", "example.org", "www.example.org", "A".repeat(121), "\u202E\u2069", "--").forEach {
            assertNull("not a public name: $it", MentionNames.sanitizeDisplayName(it, key))
        }
        assertEquals("Alice Example", MentionNames.sanitizeDisplayName("Alice Example", key))
        assertEquals("Alice", MentionNames.sanitizeDisplayName("Alice", key))
        assertEquals("NASA", MentionNames.sanitizeDisplayName("NASA", key.copy(username="nasa")))
    }

    @Test fun recognitionAndReplacementWorkIsBounded() {
        assertEquals(MentionNames.MAX_MATCHES, find((1..100).joinToString(" ") { "@person$it" }).size)
        assertTrue(find("a".repeat(MentionNames.MAX_TEXT_LENGTH + 1) + " @alice").isEmpty())
    }

    @Test fun ordinaryNameBankExpiresUnusedPopularAccountsAndRefreshesViewedStaleNames() {
        val now = 100L * 24 * 60 * 60 * 1_000
        assertFalse(MentionNamePolicy.expired(now - MentionNamePolicy.UNUSED_TTL_MS + 1, now))
        assertTrue(MentionNamePolicy.expired(now - MentionNamePolicy.UNUSED_TTL_MS, now))
        assertFalse(MentionNamePolicy.stale(now - MentionNamePolicy.STALE_MS + 1, now))
        assertTrue(MentionNamePolicy.stale(now - MentionNamePolicy.STALE_MS, now))
        assertFalse(MentionNamePolicy.validTimestamp(now + 120_000, now))
        assertTrue(MentionNamePolicy.expired(0, now))
        assertEquals(Double.NEGATIVE_INFINITY, MentionNamePolicy.retainScore(Long.MAX_VALUE, now - MentionNamePolicy.UNUSED_TTL_MS, now), 0.0)
        assertTrue(MentionNamePolicy.retainScore(20, now, now) > MentionNamePolicy.retainScore(1, now, now))
        assertTrue(MentionNamePolicy.retainScore(20, now, now) > MentionNamePolicy.retainScore(20, now - MentionNamePolicy.STALE_MS, now))
        assertEquals(1_024, MentionNamePolicy.MAX_ENTRIES)
        assertEquals(1_048_576L, MentionNamePolicy.MAX_BYTES)
    }

    @Test fun missesBackOffWithoutOverflowOrPermanentLookupSuppression() {
        assertEquals(MentionNamePolicy.MISS_BACKOFF_MS, MentionNamePolicy.retryDelay(1))
        assertEquals(2 * MentionNamePolicy.MISS_BACKOFF_MS, MentionNamePolicy.retryDelay(2))
        assertEquals(MentionNamePolicy.MISS_BACKOFF_MS, MentionNamePolicy.retryDelay(Int.MIN_VALUE))
        assertEquals(MentionNamePolicy.MAX_MISS_BACKOFF_MS, MentionNamePolicy.retryDelay(Int.MAX_VALUE))
    }
}
