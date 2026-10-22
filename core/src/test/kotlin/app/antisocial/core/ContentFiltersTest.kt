/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import kotlin.test.*
import org.junit.Test

class ContentFiltersTest {
    private val feed=ContentFilterContext(FilterSurface.FEED)
    private fun post(id: String="1",body: String="A release is available.",media: List<Media> = emptyList())=Post(id,Platform.X,"https://x.com/test/status/$id","Publisher",body,media=media)
    private fun rule(vararg phrases: String)=ContentRule("r","My rule",phrases=phrases.toList())
    private fun evaluate(r: ContentRule,posts: List<Post>,context: ContentFilterContext=feed,now: Long=10L)=ContentFilters.compile(listOf(r)).evaluate(posts,context,now)

    @Test fun emptyRulesLeaveEverythingVisible() {assertEquals(ContentVerdict(),ContentFilters.compile(emptyList()).evaluate(listOf(post()),feed,1))}
    @Test fun phraseUsesUnicodeWholeWordsAndAnyEntry() {
        val r=rule("sale","café")
        assertTrue(evaluate(r,listOf(post(body="A ＳＡＬＥ today"))).withheld)
        assertFalse(evaluate(r,listOf(post(body="Wholesale prices"))).withheld)
        assertTrue(evaluate(r,listOf(post(body="Meet at the CAFE\u0301."))).withheld)
    }
    @Test fun sectionsCannotBorrowFromDifferentEditions() {
        val r=rule("release").copy(types=setOf(ChannelContentType.VIDEO))
        assertFalse(evaluate(r,listOf(post(),post("2","Watch this",listOf(Media("https://cdn.test/clip","video"))))).withheld)
        assertTrue(evaluate(r,listOf(post(media=listOf(Media("https://cdn.test/clip","video"))))).withheld)
    }
    @Test fun exceptionsInAnyEditionSpareCollectedCorrection() {
        val r=rule("release").copy(exceptPhrases=listOf("regression","correction"))
        assertFalse(evaluate(r,listOf(post(),post("2","Correction: the release has a regression."))).withheld)
    }
    @Test fun explicitKeepWinsRegardlessOfOrder() {
        val keep=rule("release").copy(id="keep",action=ContentAction.KEEP)
        val hide=rule("release").copy(action=ContentAction.HIDE)
        for(rules in listOf(listOf(hide,keep),listOf(keep,hide))) {
            val verdict=ContentFilters.compile(rules).evaluate(listOf(post()),feed,1)
            assertFalse(verdict.withheld);assertEquals(listOf("keep"),verdict.matches.map {it.ruleId})
        }
    }
    @Test fun scopeIsVerifiedAccountRatherThanDisplayName() {
        val source=post();val account=CrossPosts.originalAccount(source)!!
        val r=rule("release").copy(scope=ContentScope.ACCOUNT,scopeKey=account)
        assertTrue(evaluate(r,listOf(source)).withheld)
        assertFalse(evaluate(r,listOf(source.copy(canonicalUrl="https://x.com/imposter/status/1"))).withheld)
    }
    @Test fun channelAndSearchScopesCannotLeak() {
        val r=rule("release").copy(scope=ContentScope.CHANNEL,scopeKey="c")
        assertFalse(evaluate(r,listOf(post())).withheld)
        assertTrue(evaluate(r,listOf(post()),feed.copy(channel="c")).withheld)
        val search=r.copy(scope=ContentScope.SEARCH,scopeKey="exact-query",surfaces=setOf(FilterSurface.SEARCH))
        assertFalse(evaluate(search,listOf(post()),ContentFilterContext(FilterSurface.SEARCH,search="other")).withheld)
        assertTrue(evaluate(search,listOf(post()),ContentFilterContext(FilterSurface.SEARCH,search="exact-query")).withheld)
    }
    @Test fun expiryIsExactAndDisabledRulesStayDisabled() {
        assertFalse(evaluate(rule("release").copy(expiresAt=10),listOf(post())).withheld)
        assertTrue(evaluate(rule("release").copy(expiresAt=11),listOf(post())).withheld)
        assertFalse(evaluate(rule("release").copy(enabled=false),listOf(post())).withheld)
    }
    @Test fun linkedDomainsIncludeSubdomainsButNeverLookalikes() {
        val r=rule().copy(domains=listOf("example.org"))
        assertTrue(evaluate(r,listOf(post().copy(links=listOf("https://a.example.org/study")))).withheld)
        assertFalse(evaluate(r,listOf(post().copy(links=listOf("https://example.org.evil.test/")))).withheld)
        assertFalse(evaluate(r,listOf(post(body="example.org is mentioned, not linked"))).withheld)
    }
    @Test fun socialSiteMatchingUsesOriginalPlatformRegardlessOfRetrievalRoute() {
        val r=rule().copy(platforms=setOf(Platform.X),surfaces=setOf(FilterSurface.SEARCH))
        val context=ContentFilterContext(FilterSurface.SEARCH,search="https://x.com/search?q=release")
        val p=post().copy(provenance=Provenance(Source(Platform.X,"public-search","https://search.example"),1,"https://frontend.example/post"))
        assertTrue(evaluate(r,listOf(p),context).withheld)
        assertTrue(evaluate(r,listOf(p.copy(provenance=p.provenance!!.copy(source=Source(Platform.X,"archive","https://archive.example")))),context).withheld)
        assertFalse(evaluate(r,listOf(p.copy(platform=Platform.REDDIT)),context).withheld)
        assertFalse(evaluate(r,listOf(p)).withheld)
    }
    @Test fun siteAndWordsMustMatchTheSameEditionAndAnySiteCanBeSelected() {
        val r=rule("release").copy(platforms=setOf(Platform.X,Platform.REDDIT))
        assertTrue(evaluate(r,listOf(post().copy(platform=Platform.REDDIT))).withheld)
        assertFalse(evaluate(r,listOf(post(body="Different words"),post("2").copy(platform=Platform.BLUESKY))).withheld)
        assertTrue(evaluate(r.copy(platforms=emptySet()),listOf(post().copy(platform=Platform.BLUESKY))).withheld)
    }
    @Test fun subredditRulesUseVerifiedOriginalPathsRatherThanMentionsOrLinks() {
        val r=rule().copy(platforms=setOf(Platform.REDDIT),subreddits=listOf("android"))
        val p=post().copy(platform=Platform.REDDIT,canonicalUrl="https://www.reddit.com/r/Android/comments/abc123/a_release/",provenance=Provenance(Source(Platform.REDDIT,"redlib","https://reader.example"),1,"https://reader.example/r/Android/comments/abc123"))
        assertTrue(evaluate(r,listOf(p)).withheld)
        assertFalse(evaluate(r,listOf(p.copy(canonicalUrl="https://www.reddit.com/r/AndroidDev/comments/abc123/a_release/",body="Shared from r/Android",links=listOf(p.canonicalUrl)))).withheld)
        assertFalse(evaluate(r,listOf(p.copy(canonicalUrl="https://www.reddit.com/comments/abc123"))).withheld)
        assertFalse(evaluate(r,listOf(p.copy(canonicalUrl="https://www.reddit.com.evil.test/r/Android/comments/abc123"))).withheld)
        assertEquals("android",ContentFilters.subredditName("https://www.reddit.com/r/Android/"))
    }
    @Test fun serverRulesUseExactOriginalFederatedHostsAndPreserveUnknownContext() {
        val r=rule().copy(platforms=setOf(Platform.MASTODON,Platform.PIXELFED),servers=listOf("social.example"))
        val p=post().copy(platform=Platform.MASTODON,canonicalUrl="https://social.example/@author/123",provenance=Provenance(Source(Platform.MASTODON,"alternate","https://proxy.example"),1,"https://proxy.example/123"))
        assertTrue(evaluate(r,listOf(p)).withheld)
        assertFalse(evaluate(r,listOf(p.copy(canonicalUrl="https://other.example/@author/123",links=listOf(p.canonicalUrl)))).withheld)
        assertFalse(evaluate(r,listOf(p.copy(canonicalUrl="https://social.example.evil.test/@author/123"))).withheld)
        assertFalse(evaluate(r,listOf(p.copy(platform=Platform.X))).withheld)
        assertEquals("www.social.example",ContentFilters.serverAddress("https://www.social.example/@author"))
    }
    @Test fun incompatibleSiteDetailsCannotBeSavedAsSilentlyIneffectiveRules() {
        assertTrue(ContentFilters.validate(rule().copy(platforms=setOf(Platform.FACEBOOK),subreddits=listOf("android"))).isNotEmpty())
        assertTrue(ContentFilters.validate(rule().copy(platforms=setOf(Platform.REDDIT),servers=listOf("social.example"))).isNotEmpty())
        assertTrue(ContentFilters.validate(rule().copy(subreddits=listOf("android"),servers=listOf("social.example"))).isNotEmpty())
    }
    @Test fun pastedWebsiteLinksBecomeAddressesWithoutPathsOrTracking() {
        assertEquals("example.org",ContentFilters.linkedWebsite("https://www.Example.org/report?id=2&utm_source=x"))
        assertEquals("docs.example.org",ContentFilters.linkedWebsite("docs.example.org/report"))
        assertTrue(ContentFilters.validate(rule().copy(domains=listOf(ContentFilters.linkedWebsite("not a website")))).isNotEmpty())
    }
    @Test fun hashtagsHaveBoundaries() {
        val r=rule().copy(hashtags=listOf("ad"))
        assertTrue(evaluate(r,listOf(post(body="#AD Great news"))).withheld)
        assertFalse(evaluate(r,listOf(post(body="#advice or me@example.test"))).withheld)
    }
    @Test fun collectNeverGroupsUnrelatedPostsByTopic() {
        val r=rule().copy(repeatsOnly=true,action=ContentAction.COLLECT)
        assertTrue(evaluate(r,listOf(post())).matches.isEmpty())
        val result=evaluate(r,listOf(post(),post("2")))
        assertEquals(ContentAction.COLLECT,result.action);assertFalse(result.withheld)
        assertFailsWith<IllegalArgumentException>{ContentFilters.compile(listOf(rule("release").copy(action=ContentAction.COLLECT)))}
    }
    @Test fun incompleteEmptyRecordDoesNotMeanUninformative() {
        val p=post(body="").copy(preview=true)
        assertFalse(evaluate(rule("noise"),listOf(p)).withheld)
        assertEquals(ExtractionStatus.PARTIAL,ContentRevisions.coverage(p).text)
        assertEquals(ExtractionStatus.UNKNOWN,ContentRevisions.coverage(p).quotedContext)
        assertEquals(ExtractionStatus.UNKNOWN,ContentRevisions.coverage(p).attachments)
    }
    @Test fun explanationsPreserveActualMatchedOriginals() {
        val p=post();val other=post("2","Completely different commentary")
        val result=evaluate(rule("release"),listOf(p,other))
        assertEquals(setOf(CrossPosts.originalKey(p)),result.matches.single().originalKeys)
        assertEquals(listOf("Contains “release”"),result.matches.single().reasons)
    }
    @Test fun recipeRoundTripPreservesAllControls() {
        val r=rule("release").copy(scope=ContentScope.ACCOUNT,scopeKey="twitter:test",exceptPhrases=listOf("fix"),expiresAt=25,types=setOf(ChannelContentType.TEXT),platforms=setOf(Platform.X,Platform.REDDIT),subreddits=listOf("android"))
        assertEquals(listOf(r),ContentRuleCodec.decode(ContentRuleCodec.encode(listOf(r))))
    }
    @Test fun malformedRecipesNeverBecomeBroaderRules() {
        val good=ContentRuleCodec.encode(listOf(rule("release")))
        for(bad in listOf(good+"{}",good.replace("\"version\":2","\"version\":99"),good.replace("\"enabled\":true","\"enabled\":\"true\""),good.replace("\"phrases\"","\"unknown\""),good.replace("\"name\":\"My rule\"","\"name\":\"My rule\",\"name\":\"broad\""),good.replace("\"COLLAPSE\"","\"EXECUTE\""),good.replace("\"platforms\":[]","\"platforms\":[\"UNKNOWN\"]"),good.replace(",\"platforms\":[]",""))) {
            assertFails {ContentRuleCodec.decode(bad)}
        }
    }
    @Test fun legacyRecipesRetainTheirMeaningAndCannotSmuggleNewSiteConditions() {
        val old=ContentRuleCodec.encode(listOf(rule("release"))).replace("\"version\":2","\"version\":1").replace(",\"platforms\":[]","").replace(",\"subreddits\":[]","").replace(",\"servers\":[]","")
        assertEquals(listOf(rule("release")),ContentRuleCodec.decode(old))
        val invalid=ContentRuleCodec.encode(listOf(rule("release").copy(platforms=setOf(Platform.X)))).replace("\"version\":2","\"version\":1")
        assertFails {ContentRuleCodec.decode(invalid)}
    }
    @Test fun broadGlobalRulesRequireAnExplicitCondition() {assertTrue(ContentFilters.validate(rule()).isNotEmpty());assertTrue(ContentFilters.validate(rule().copy(scope=ContentScope.ACCOUNT,scopeKey="twitter:test")).isEmpty())}
    @Test fun oversizedMultilingualRecipesAreRejectedBeforeSavingAsWellAsImporting() {
        val terms=(1..32).map {"中".repeat(150)+it}
        val rules=(1..80).map {ContentRule("r$it","Rule $it",phrases=terms)}
        val raw="{\"version\":1,\"rules\":"+com.google.gson.Gson().toJson(rules)+"}"
        assertTrue(raw.length<1_000_000);assertTrue(raw.toByteArray().size>1_000_000)
        assertFailsWith<IllegalArgumentException>{ContentRuleCodec.encode(rules)}
        assertFailsWith<IllegalArgumentException>{ContentRuleCodec.decode(raw)}
    }
    @Test fun regexLikeInputIsLiteral() {assertFalse(evaluate(rule(".*"),listOf(post())).withheld)}
    @Test fun nativeActionsDontReorderInput() {
        val input=(1..1000).map {post(it.toString(),if(it%3==0)"sponsored"else "release")}
        val engine=ContentFilters.compile(listOf(rule("sponsored")))
        val accepted=input.filter {!engine.evaluate(listOf(it),feed,1).withheld}
        assertEquals(input.filter {it.id.toInt()%3!=0}.map {it.id},accepted.map {it.id})
    }
    @Test fun exactCopiesInheritReadButNewContributionsDoNot() {
        val p=post();assertFalse(ContentRevisions.addsContext(listOf(p),listOf(p.copy(id="2"))))
        assertTrue(ContentRevisions.addsContext(listOf(p),listOf(p.copy(body=p.body+" A regression affects battery use."))))
        assertTrue(ContentRevisions.addsContext(listOf(p),listOf(p.copy(media=listOf(Media("https://cdn.test/new.jpg"))))))
        val m=FeedReading.markRead(FeedReading.reconcile(FeedMemory(),listOf("a"),1),listOf("a"),2)
        val attached=FeedReading.mergeAliases(m,listOf(listOf("a","b")),3,inheritRead=false)
        assertEquals(listOf("a","b"),attached.acceptedKeys);assertEquals(setOf("a"),attached.readKeys)
    }
    @Test fun fetchMetadataAndSignedUrlRefreshAreNotDevelopments() {
        val p=post(media=listOf(Media("https://cdn.test/image.jpg?sig=old&size=large")))
        assertEquals(ContentRevisions.signature(p),ContentRevisions.signature(p.copy(replyCount=500,score=50,media=listOf(Media("https://cdn.test/image.jpg?size=large&sig=new",visualFingerprint="later")))))
        assertNotEquals(ContentRevisions.signature(p),ContentRevisions.signature(p.copy(body="Correction: no release is available.")))
    }
    @Test fun aNewDocumentIsADevelopmentEvenWhenItsLinkWasFoldedIntoDetails() {
        val p=post(body="The release includes a practical fix for battery consumption on older devices.").copy(links=listOf("https://docs.example/report?id=1&utm_source=x"))
        val changed=p.copy(links=listOf("https://docs.example/report?id=2&utm_source=x"))
        assertNotEquals(ContentRevisions.signature(p),ContentRevisions.signature(changed))
        assertTrue(ContentRevisions.addsContext(listOf(p),listOf(changed)))
        val renewed=p.copy(links=listOf("https://docs.example/report?utm_source=bluesky&id=1"))
        assertEquals(ContentRevisions.signature(p),ContentRevisions.signature(renewed))
        assertFalse(ContentRevisions.addsContext(listOf(p),listOf(renewed)))
    }
    @Test fun linksToCollectedOriginalsAndAccountAttributionAreNotNewEvidence() {
        val p=post();val copy=post("2").copy(links=listOf(p.canonicalUrl,"https://x.com/test"))
        assertFalse(ContentRevisions.addsContext(listOf(p),listOf(copy)))
        assertTrue(ContentRevisions.addsContext(listOf(p),listOf(copy.copy(links=listOf("https://x.com/test/status/999")))))
    }
}
