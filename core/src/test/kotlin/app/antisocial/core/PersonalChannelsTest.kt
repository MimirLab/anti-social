package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PersonalChannelsTest {
    private val alice = Follow("alice-x", Target(Platform.X, TargetKind.PROFILE, "alice", "https://x.com/alice"), "Alice", personId = "alice", personName = "Alice's workshop")
    private val aliceBsky = Follow("alice-bsky", Target(Platform.BLUESKY, TargetKind.PROFILE, "alice.example", "https://bsky.app/profile/alice.example"), "Alice elsewhere", personId = "alice", personName = "Alice's workshop")
    private val bob = Follow("bob-x", Target(Platform.X, TargetKind.PROFILE, "bob", "https://x.com/bob"), "Bob")
    private val follows = listOf(alice, aliceBsky, bob)
    private fun rule() = PersonalChannel("local-rule", "Software releases")
    private fun post(body: String = "Release 4.2 is ready #Software", id: String = "123", platform: Platform = Platform.X,
                     media: List<Media> = emptyList(), links: List<String>? = null, title: String = "") = Post(
        id, platform, if (platform == Platform.BLUESKY) "https://bsky.app/profile/alice.example/post/$id" else "https://x.com/alice/status/$id",
        "Not an identity proof", body, title = title, publishedAt = id.toLongOrNull(), media = media, links = links)
    private fun memberships(vararg entries: Pair<Post, List<Follow>>) = entries.associate { (post, accounts) ->
        PersonalChannels.postKey(post) to accounts.map { it.id }.toSet()
    }
    private fun match(rule: PersonalChannel = rule(), post: Post = post(), accounts: List<Follow> = follows): ChannelMatch? =
        PersonalChannels.match(rule, listOf(post), accounts, memberships(post to listOf(alice)))
    private fun image() = Media("https://media.example/photo.jpg")
    private fun video(durationMs: Long? = null) = Media("https://media.example/movie.mp4", "video", durationMs = durationMs)

    @Test fun noRulesMatchesOnlyExplicitEnabledSubscriptionMembership() {
        val p = post()
        assertNotNull(match())
        assertNull(PersonalChannels.match(rule(), listOf(p), follows, emptyMap()))
        assertNull(PersonalChannels.match(rule(), listOf(p), emptyList(), memberships(p to listOf(alice))))
        assertNull(PersonalChannels.match(rule(), listOf(p), listOf(alice.copy(enabled = false)), memberships(p to listOf(alice))))
    }

    @Test fun aDisplayNameOrProfileUrlNeverInventsSubscriptionMembership() {
        val p = post().copy(author = alice.name)
        assertNull(PersonalChannels.match(rule(), listOf(p), follows, mapOf(PersonalChannels.postKey(p) to setOf("unknown-follow"))))
    }

    @Test fun selectingCreatorBundleAcceptsItsSeveralAccounts() {
        val x = post()
        val bsky = post(platform = Platform.BLUESKY)
        val result = PersonalChannels.match(rule().copy(subscriptionIds = setOf("alice")), listOf(x, bsky), follows,
            memberships(x to listOf(alice), bsky to listOf(aliceBsky)))!!
        assertEquals(setOf(PersonalChannels.postKey(x), PersonalChannels.postKey(bsky)), result.matchedPostKeys)
        assertTrue(result.reasons.any { it.contains("Alice's workshop") })
    }

    @Test fun accountIdDoesNotImpersonateItsCreatorBundle() {
        assertNull(match(rule().copy(subscriptionIds = setOf(alice.id))))
    }

    @Test fun aPostCanAppearInSeveralIndependentChannels() {
        val p = post("Local software release #Town")
        assertNotNull(match(rule().copy(keywords = listOf("release")), p))
        assertNotNull(match(rule().copy(id = "local", name = "Local", hashtags = listOf("town")), p))
        assertNotNull(match(rule().copy(id = "all", name = "All Alice", subscriptionIds = setOf("alice")), p))
    }

    @Test fun alternativesWithinSectionsAndRequirementsAcrossSections() {
        val selected = rule().copy(subscriptionIds = setOf("alice", bob.id), keywords = listOf("unmatched", "release"),
            hashtags = listOf("other", "software"), contentTypes = setOf(ChannelContentType.IMAGES, ChannelContentType.VIDEO))
        assertNotNull(match(selected, post(media = listOf(image()))))
        assertNull(match(selected, post(body = "Release is ready #otherTag", media = listOf(image()))))
        assertNull(match(selected, post(body = "A picture #Software", media = listOf(image()))))
        assertNull(match(selected, post()))
    }

    @Test fun positiveSectionsCannotBorrowEvidenceAcrossEditions() {
        val x = post(body = "Release tomorrow", media = listOf(image()))
        val bsky = post(body = "The announcement #Software", platform = Platform.BLUESKY, media = listOf(image()))
        val selected = rule().copy(keywords = listOf("release"), hashtags = listOf("software"))
        assertNull(PersonalChannels.match(selected, listOf(x, bsky), follows, memberships(x to listOf(alice), bsky to listOf(aliceBsky))))
    }

    @Test fun sourceEligibilityAlsoMustBelongToTheMatchingEdition() {
        val x = post(body = "No news")
        val bsky = post(body = "Release today", platform = Platform.BLUESKY)
        assertNull(PersonalChannels.match(rule().copy(subscriptionIds = setOf(bob.id), keywords = listOf("release")), listOf(x, bsky), follows,
            memberships(x to listOf(bob), bsky to listOf(aliceBsky))))
    }

    @Test fun excludedKeywordOnAnyEditionVetoesTheWholeFusion() {
        val x = post()
        val bsky = post(body = "Release with a giveaway #Software", platform = Platform.BLUESKY)
        assertNull(PersonalChannels.match(rule().copy(keywords = listOf("release"), excludeKeywords = listOf("giveaway")), listOf(x, bsky), follows,
            memberships(x to listOf(alice), bsky to listOf(aliceBsky))))
    }

    @Test fun excludedHashtagOnAnUnsubscribedEditionStillVetoesFusion() {
        val x = post()
        val bsky = post(body = "Release #Sponsored", platform = Platform.BLUESKY)
        assertNull(PersonalChannels.match(rule().copy(excludeHashtags = listOf("sponsored")), listOf(x, bsky), follows,
            memberships(x to listOf(alice))))
    }

    @Test fun excludedCreatorCannotLeakThroughAnotherEditionOrAnotherMembership() {
        val x = post()
        val bsky = post(platform = Platform.BLUESKY)
        val selected = rule().copy(excludeSubscriptionIds = setOf("alice"))
        assertNull(PersonalChannels.match(selected, listOf(x, bsky), follows,
            memberships(x to listOf(bob), bsky to listOf(aliceBsky))))
        assertNull(PersonalChannels.match(selected, listOf(x), follows, memberships(x to listOf(bob, alice))))
    }

    @Test fun disabledExcludedAccountsStillPreventFusionContextLeaks() {
        val x = post()
        val bsky = post(platform = Platform.BLUESKY)
        assertNull(PersonalChannels.match(rule().copy(excludeSubscriptionIds = setOf("alice")), listOf(x, bsky),
            listOf(bob, aliceBsky.copy(enabled = false)), memberships(x to listOf(bob), bsky to listOf(aliceBsky))))
    }

    @Test fun forgedCrossPlatformMembershipCannotMatchOrExclude() {
        val x = post()
        assertNull(PersonalChannels.match(rule(), listOf(x), follows, memberships(x to listOf(aliceBsky))))
        assertNotNull(PersonalChannels.match(rule().copy(excludeSubscriptionIds = setOf("alice")), listOf(x), follows,
            memberships(x to listOf(bob, aliceBsky))))
    }

    @Test fun keywordsAreLiteralPhrasesWithUnicodeWordBoundaries() {
        val selected = rule().copy(keywords = listOf("C++", "software release"))
        assertNotNull(match(selected, post("A C++ update")))
        assertNotNull(match(selected, post("SOFTWARE\n  release today")))
        assertNull(match(selected, post("prerelease software releases")))
        assertNull(match(rule().copy(keywords = listOf("release")), post("unreleased release_notes")))
        assertNull(match(rule().copy(keywords = listOf("a.*b")), post("a whole thing b")))
        assertNotNull(match(rule().copy(keywords = listOf("a.*b")), post("Literal a.*b is fine")))
    }

    @Test fun unicodeCaseAndCompatibilityFormsMatchWithoutDroppingAccents() {
        assertNotNull(match(rule().copy(keywords = listOf("CAFÉ")), post("cafe\u0301 today")))
        assertNull(match(rule().copy(keywords = listOf("cafe")), post("café today")))
        assertNotNull(match(rule().copy(keywords = listOf("κόσμος")), post("ΚΌΣΜΟΣ")))
        assertNotNull(match(rule().copy(keywords = listOf("release")), post("ＲＥＬＥＡＳＥ today")))
        assertNull(match(rule().copy(keywords = listOf("大阪")), post("大阪市")))
    }

    @Test fun matchingDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertNotNull(match(rule().copy(keywords = listOf("INSTALL")), post("install update")))
            assertNotNull(match(rule().copy(hashtags = listOf("INSTALL")), post("#install")))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun hashtagsAreExactNormalizedTokensRatherThanSubstringsOrUrlFragments() {
        val selected = rule().copy(hashtags = listOf("#SOFTWARE"))
        assertNotNull(match(selected))
        assertNull(match(selected, post("#softwareNews")))
        assertNull(match(selected, post("software without a hashtag")))
        assertNull(match(selected, post("https://example.com/page#software")))
        assertNotNull(match(rule().copy(hashtags = listOf("大阪_2026")), post("#大阪_2026")))
        assertNotNull(match(rule().copy(hashtags = listOf("café")), post("#cafe\u0301")))
        assertNotNull(match(rule().copy(hashtags = listOf("Σ")), post("#ς")))
        assertNotNull(match(rule().copy(hashtags = listOf("i")), post("#İ")))
        assertNotNull(match(rule().copy(hashtags = listOf("İ")), post("#i")))
    }

    @Test fun titleContributesToKeywordsAndHashtagsButNotAuthorName() {
        assertNotNull(match(rule().copy(keywords = listOf("release"), hashtags = listOf("software")), post(body = "Details", title = "Release #Software")))
        assertNull(match(rule().copy(keywords = listOf("release")), post("Details").copy(author = "Release")))
    }

    @Test fun textOnlyExcludesAttachmentsAndExternalLinks() {
        val selected = rule().copy(contentTypes = setOf(ChannelContentType.TEXT))
        assertNotNull(match(selected, post()))
        assertNull(match(selected, post(media = listOf(image()))))
        assertNull(match(selected, post(media = listOf(video()))))
        assertNull(match(selected, post(media = listOf(Media("https://example.com/file", "unknown")))))
        assertNull(match(selected, post(links = listOf("https://example.com/notes"))))
        assertNull(match(selected, post(body = "See https://example.com/notes")))
        assertNull(match(selected, post(body = "")))
    }

    @Test fun ownPostUrlAndMediaAssetsDoNotTurnPostsIntoLinkPosts() {
        val p = post()
        val asset = image()
        assertNull(match(rule().copy(contentTypes = setOf(ChannelContentType.LINKS)), p.copy(links = listOf(p.canonicalUrl))))
        assertNull(match(rule().copy(contentTypes = setOf(ChannelContentType.LINKS)), p.copy(media = listOf(asset), links = listOf(asset.url))))
        assertNotNull(match(rule().copy(contentTypes = setOf(ChannelContentType.TEXT)), p.copy(links = listOf(p.canonicalUrl + "#fragment"))))
    }

    @Test fun authenticAliasesOfTheSamePostDoNotBecomeExternalLinkContent() {
        val p=post(links=listOf("https://twitter.com/i/status/123"))
        assertNull(match(rule().copy(contentTypes=setOf(ChannelContentType.LINKS)),p))
        assertNotNull(match(rule().copy(contentTypes=setOf(ChannelContentType.TEXT)),p))
        assertNotNull(match(rule().copy(contentTypes=setOf(ChannelContentType.LINKS)),p.copy(links=listOf("https://twitter.com/i/status/124"))))
    }

    @Test fun contentTypesCanOverlapAndUseSuppliedMimeType() {
        val p = post(media = listOf(image(), video()), links = listOf("https://example.com/release"))
        for (type in listOf(ChannelContentType.IMAGES, ChannelContentType.VIDEO, ChannelContentType.LINKS))
            assertNotNull(match(rule().copy(contentTypes = setOf(type)), p))
        assertNotNull(match(rule().copy(contentTypes = setOf(ChannelContentType.AUDIO)), post(media = listOf(Media("https://example.com/track", "unknown", mimeType = "audio/ogg")))))
        assertNull(match(rule().copy(contentTypes = setOf(ChannelContentType.TEXT)), p))
    }

    @Test fun excludingVideoRejectsLinkedVideosAndMixedGalleriesWithoutRejectingOrdinaryLinks() {
        val selected = rule().copy(contentTypes = setOf(ChannelContentType.LINKS), excludeContentTypes = setOf(ChannelContentType.VIDEO))
        val linked = post(links = listOf("https://example.com/release"))
        val accepted = match(selected, linked)!!
        assertTrue(accepted.reasons.contains("Includes a link"))
        assertTrue(accepted.reasons.contains("No fetched copy reports video."))
        assertNull(match(selected, linked.copy(media = listOf(video()))))
        assertNull(match(selected.copy(contentTypes = setOf(ChannelContentType.IMAGES)), linked.copy(media = listOf(image(), video()))))
        assertNotNull(match(selected.copy(contentTypes = setOf(ChannelContentType.IMAGES)), linked.copy(media = listOf(image()))))
        assertFalse(match(rule(), linked)!!.reasons.any { it.contains("contain no") })
    }

    @Test fun anExcludedContentTypeInAnyEditionVetoesTheWholeFusion() {
        val x = post(links = listOf("https://example.com/release"))
        val bsky = post(platform = Platform.BLUESKY, media = listOf(video()))
        val selected = rule().copy(contentTypes = setOf(ChannelContentType.LINKS), excludeContentTypes = setOf(ChannelContentType.VIDEO))
        // A known alternate edition's video still matters when it supplied no
        // eligible subscription membership for this channel's positive rule.
        val snapshot = memberships(x to listOf(alice))
        assertNull(PersonalChannels.match(selected, listOf(x, bsky), follows, snapshot))
        assertNull(PersonalChannels.matcher(selected, follows, snapshot)(listOf(x, bsky)))
        assertNotNull(PersonalChannels.match(selected.copy(excludeContentTypes = emptySet()), listOf(x, bsky), follows, snapshot))
        val textOnly = bsky.copy(media = emptyList())
        assertNull(PersonalChannels.match(selected.copy(excludeContentTypes = setOf(ChannelContentType.TEXT)), listOf(x, textOnly), follows, snapshot))
    }

    @Test fun minimumVideoLengthUsesKnownDurationAndNeverGuessesMissingMetadata() {
        val selected = rule().copy(minVideoSeconds = 600)
        assertNull(match(selected, post(media = listOf(video()))))
        assertNull(match(selected, post(media = listOf(video(0)))))
        assertNull(match(selected, post(media = listOf(video(-1)))))
        assertNull(match(selected, post(media = listOf(video(599_999)))))
        assertNotNull(match(selected, post(media = listOf(video(600_000)))))
        assertNotNull(match(selected, post(media = listOf(video(1), video(601_000)))))
        assertNull(match(selected, post(media = listOf(image().copy(durationMs = 900_000)))))
        assertNull(match(selected, post(body = "A 20 minute video", media = listOf(video()))))
    }

    @Test fun durationRequirementCombinesWithOtherSectionsWithinOneEdition() {
        val x = post(body = "Release", media = listOf(video(20_000)))
        val bsky = post(body = "A talk", platform = Platform.BLUESKY, media = listOf(video(900_000)))
        assertNull(PersonalChannels.match(rule().copy(keywords = listOf("release"), minVideoSeconds = 600), listOf(x, bsky), follows,
            memberships(x to listOf(alice), bsky to listOf(aliceBsky))))
    }

    @Test fun explanationsDescribeActualSourceAndOneCompleteMatch() {
        val result = match(rule().copy(keywords = listOf("missing", "release"), hashtags = listOf("software"),
            contentTypes = setOf(ChannelContentType.VIDEO), minVideoSeconds = 600), post(media = listOf(video(601_000))))!!
        assertEquals(listOf("From Alice's workshop on X", "Contains “release”", "Tagged #software", "Includes video",
            "Video is 10 minutes 1 second long; at least 10 minutes required"), result.reasons)
        assertFalse(result.reasons.joinToString().contains("missing"))
    }

    @Test fun channelsDoNotRankOrMutateInputChronology() {
        val older = post(id = "100")
        val newer = post(id = "200")
        val inputs = listOf(older, newer)
        val result = PersonalChannels.match(rule(), inputs, follows, memberships(older to listOf(alice), newer to listOf(alice)))!!
        assertEquals(listOf(older, newer), inputs)
        assertEquals(listOf(PersonalChannels.postKey(older), PersonalChannels.postKey(newer)), result.matchedPostKeys.toList())
    }

    @Test fun refreshAccountsRespectSelectedBundlesExclusionsAndEnabledState() {
        assertEquals(follows, PersonalChannels.accounts(rule(), follows))
        assertEquals(listOf(alice, aliceBsky), PersonalChannels.accounts(rule().copy(subscriptionIds = setOf("alice")), follows))
        assertEquals(listOf(bob), PersonalChannels.accounts(rule().copy(excludeSubscriptionIds = setOf("alice")), follows))
        assertEquals(listOf(alice), PersonalChannels.accounts(rule().copy(subscriptionIds = setOf("alice")), listOf(alice, aliceBsky.copy(enabled = false))))
        assertTrue(PersonalChannels.accounts(rule().copy(subscriptionIds = setOf("deleted")), follows).isEmpty())
    }

    @Test fun validationRejectsInvalidRulesRatherThanBroadeningThem() {
        assertTrue(PersonalChannels.validate(rule()).isEmpty())
        val invalid = listOf(rule().copy(id = ""), rule().copy(name = " "), rule().copy(name = "n".repeat(61)),
            rule().copy(keywords = List(33) { "$it" }), rule().copy(keywords = listOf(" ")),
            rule().copy(keywords = listOf("x".repeat(121))), rule().copy(hashtags = listOf("#")),
            rule().copy(hashtags = listOf("two words")), rule().copy(hashtags = listOf("dash-tag")), rule().copy(id = "bad\nID"),
            rule().copy(subscriptionIds = setOf("bad\u0085ID")), rule().copy(excludeSubscriptionIds = setOf("bad\tID")),
            rule().copy(subscriptionIds = setOf("")), rule().copy(minVideoSeconds = 0),
            rule().copy(minVideoSeconds = Long.MAX_VALUE), rule().copy(revision = 0))
        for (channel in invalid) {
            assertTrue("Expected validation error for $channel", PersonalChannels.validate(channel).isNotEmpty())
            assertNull(match(channel))
            assertTrue(PersonalChannels.accounts(channel, follows).isEmpty())
        }
    }

    @Test fun normalizationPreservesIntentAndDoesNotTruncateOverlongRules() {
        val normalized = PersonalChannels.normalize(rule().copy(name = " Software   releases ", keywords = listOf(" Release ", "release", "New\n version"),
            hashtags = listOf("#ＳＯＦＴＷＡＲＥ", "software"), subscriptionIds = setOf(" alice ")))
        assertEquals("Software releases", normalized.name)
        assertEquals(listOf("Release", "New version"), normalized.keywords)
        assertEquals(listOf("software"), normalized.hashtags)
        assertEquals(setOf("alice"), normalized.subscriptionIds)
        assertTrue(PersonalChannels.validate(PersonalChannels.normalize(rule().copy(keywords = listOf("x".repeat(121))))).isNotEmpty())
    }

    @Test fun ruleFingerprintSurvivesRenameReorderAndEquivalentFormattingButChangesWithMeaning() {
        val first = rule().copy(subscriptionIds = setOf("alice", bob.id), keywords = listOf("Release", "Version"), hashtags = listOf("#Software"), contentTypes = setOf(ChannelContentType.VIDEO, ChannelContentType.IMAGES))
        val equivalent = first.copy(id = "another", name = "Renamed", revision = 12, subscriptionIds = setOf(bob.id, "alice"), keywords = listOf(" version ", "release"), hashtags = listOf("software"), contentTypes = setOf(ChannelContentType.IMAGES, ChannelContentType.VIDEO))
        assertEquals(PersonalChannels.fingerprint(first), PersonalChannels.fingerprint(equivalent))
        for (different in listOf(first.copy(minVideoSeconds = 60), first.copy(excludeKeywords = listOf("ad")), first.copy(subscriptionIds = emptySet()), first.copy(hashtags = emptyList()), first.copy(excludeContentTypes = setOf(ChannelContentType.VIDEO))))
            assertNotEquals(PersonalChannels.fingerprint(first), PersonalChannels.fingerprint(different))
    }

    @Test fun preparedBatchMatcherPreservesRuleSemanticsAcrossIndependentGroups() {
        val selected = rule().copy(keywords = listOf("release"), excludeHashtags = listOf("ad"))
        val matching = post("Release today", id = "101")
        val rejected = post("An ordinary day", id = "102")
        val excludedEdition = post("Release #ad", id = "103", platform = Platform.BLUESKY)
        val snapshot = memberships(matching to listOf(alice), rejected to listOf(alice), excludedEdition to listOf(aliceBsky))
        val prepared = PersonalChannels.matcher(selected, follows, snapshot)
        val groups = listOf(listOf(matching), listOf(rejected), listOf(matching, excludedEdition), emptyList())
        assertEquals(listOf(true, false, false, false), groups.map { prepared(it) != null })
        assertEquals(groups.map { PersonalChannels.match(selected, it, follows, snapshot) }, groups.map(prepared))
        assertNotNull(prepared(listOf(matching))) // A rejected group never poisons subsequent cards.
        assertNull(PersonalChannels.matcher(selected.copy(name = ""), follows, snapshot)(listOf(matching)))
    }

    @Test fun serializationKeepsOnlyExplicitRulesAndRoundTrips() {
        val channel = rule().copy(subscriptionIds = setOf("alice"), keywords = listOf("release"), hashtags = listOf("software"),
            excludeKeywords = listOf("ad"), excludeHashtags = listOf("sponsored"), excludeSubscriptionIds = setOf(bob.id),
            contentTypes = setOf(ChannelContentType.VIDEO), minVideoSeconds = 600, revision = 3, excludeContentTypes = setOf(ChannelContentType.AUDIO))
        val gson = Gson()
        assertEquals(channel, gson.fromJson(gson.toJson(channel), PersonalChannel::class.java))
    }
}
