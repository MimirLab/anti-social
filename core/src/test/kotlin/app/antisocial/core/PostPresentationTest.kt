package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PostPresentationTest {
    private val caption="A public project update describes the completed work and the next steps for everyone following along."
    private val post=Post("post:2111206334771220528",Platform.X,"https://x.com/WhiteHouse/status/2111206334771220528","@WhiteHouse",caption,title=caption,publishedAt=1_792_185_789_000L)
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private val source=routes.sources.first {it.frontend=="nitter"}
    @Test fun aFacebookAttachedStoryLabelRetainsAttributionOutsideItsAuthoredCaption() {
        val p=Provenance(Source(Platform.FACEBOOK,"original","https://www.facebook.com",role=SourceRole.ORIGINAL),1,"https://www.facebook.com/api/graphql/")
        val shared=post.copy(platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/123456789/posts/987654321",title="",body="Shared from Space Workshop:\n$caption",provenance=p)
        val before=Gson().toJson(shared);val shown=PostPresentation.text(shared)
        assertEquals(caption,shown.body);assertEquals("Shared from Space Workshop",shown.attribution)
        assertEquals(before,Gson().toJson(shared))
        for(other in listOf(shared.copy(provenance=null),shared.copy(provenance=p.copy(finalUrl="https://reader.example/feed")),shared.copy(body="My own introduction.\n\n"+shared.body))) {
            assertEquals(other.body,PostPresentation.text(other).body);assertNull(PostPresentation.text(other).attribution)
        }
    }
    @Test fun aGeneratedRepostHeadingExpandsItsShortenedWwwLabelWithoutRepeatingTheProse() {
        val link="https://www.archive.example/updates/2026/complete-workshop-notes"
        val body="$caption Read archive.example/updates/2…"
        val value=post.copy(author="@WorkshopCrew",title="RT by @Fixture: $caption Read $link",body=body,provenance=Provenance(source,1,source.root+"/Fixture/rss"))
        val shown=PostPresentation.text(value)
        assertEquals("",shown.title);assertEquals("$caption Read $link",shown.body);assertEquals("Reposted by @Fixture",shown.attribution)
        assertEquals(body,value.body)
        val extra=value.copy(body=body+" Additional access notes: https://access.example/entrance")
        assertEquals("$caption Read $link Additional access notes: https://access.example/entrance",PostPresentation.text(extra).body)
    }
    @Test fun aSchemeLessFeedLabelCanRetainItsSuppliedHttpDestinationButCannotRewriteAnExplicitScheme() {
        val link="http://archive.example/Updates?mode=New#Latest"
        val value=post.copy(author="@WorkshopCrew",title="RT by @Fixture: $caption Read $link",body="$caption Read archive.example/Updates?mode=New#Latest",provenance=Provenance(source,1,source.root+"/Fixture/rss"))
        assertEquals("",PostPresentation.text(value).title);assertEquals("$caption Read $link",PostPresentation.text(value).body)
        for(body in listOf("$caption Read https://archive.example/Updates?mode=New#Latest","$caption Read archive.example/Updates?mode=Other#Latest","$caption Read archive.example/Updates?mode=New#Other"))
            assertEquals(value.title,PostPresentation.text(value.copy(body=body)).title)
    }

    @Test fun whiteHouseRssRepostKeepsAttributionWithoutRepeatingTheOriginalAuthorsCaption() {
        val body="ONE OF THE LARGEST INVESTMENTS IN UTAH HISTORY 🚨 Texas Instruments is building an $11 billion semiconductor wafer fabrication plant in Lehi, creating jobs and reshoring production. President Trump’s America First policies are fueling our industrial insurgence as companies are incentivized to invest and build on U.S. soil."
        val value=post.copy(author="@USTradeRep",canonicalUrl="https://x.com/USTradeRep/status/2111218803711541638",body=body,title="RT by @WhiteHouse: $body")
        val before=Gson().toJson(value);val shown=PostPresentation.text(value)
        assertEquals("",shown.title);assertEquals(body,shown.body);assertEquals("Reposted by @WhiteHouse",shown.attribution)
        assertEquals(body,PostPresentation.content(value));assertEquals(before,Gson().toJson(value))
    }
    @Test fun aRepostOfAQuotedPostShowsItsMainCaptionOnceAndKeepsTheAdditionalQuote() {
        val extra="A different original author added a complete public explanation and a link to the project."
        val value=post.copy(author="@Original",title="RT by @Reader: $caption",body="$caption $extra")
        val shown=PostPresentation.text(value)
        assertEquals("",shown.title);assertEquals("Reposted by @Reader",shown.attribution);assertEquals(value.body,shown.body)
        assertEquals(value.body,PostPresentation.content(value))
    }
    @Test fun mismatchedRepostHeadingsAndOtherPlatformsKeepTheirOriginalMeaning() {
        for(value in listOf(post.copy(title="RT by @WhiteHouse: We completed 21 projects.",body="We completed 20 projects."),
            post.copy(platform=Platform.REDDIT,title="RT by @WhiteHouse: $caption"))) {
            assertEquals(value.title,PostPresentation.text(value).title);assertNull(PostPresentation.text(value).attribution)
        }
    }
    @Test fun rssVideoLinkLabelsAreNotAnotherCaptionButIntentionalVideoWordsRemain() {
        val heading="Honoring America's Brave First Responders: Postcard Edition 🇺🇸"
        val value=post.copy(title=heading,body="$heading Video",media=listOf(Media("https://pbs.twimg.com/amplify_video_thumb/123/frame.jpg")),provenance=Provenance(source,1,source.root+"/WhiteHouse/rss"))
        assertEquals("",PostPresentation.text(value).title);assertEquals(heading,PostPresentation.text(value).body)
        assertEquals("$heading Video",value.body)
        assertEquals(value.body,PostPresentation.text(value.copy(provenance=null)).body)
        val intentional=value.copy(title="Film review",body="We disagree about the meaning of the word Video")
        assertEquals(intentional.body,PostPresentation.text(intentional).body)
    }
    @Test fun aProvenPlayGlyphAndCompleteSentenceExcerptDoNotRepeatTheBody() {
        val value=post.copy(title="▶ $caption",media=listOf(Media("https://example.org/frame.jpg")))
        assertEquals("",PostPresentation.text(value).title)
        val paragraph=post.copy(title=caption,body="$caption Additional information follows below with its own complete sentence.")
        assertEquals("",PostPresentation.text(paragraph).title);assertEquals(paragraph.body,PostPresentation.content(paragraph))
    }

    @Test fun exactFeedCaptionAppearsOnceWithoutChangingItsSourceFields() {
        val presented=PostPresentation.text(post)
        assertEquals("",presented.title);assertEquals(caption,presented.body)
        assertEquals(caption,PostPresentation.content(post))
        assertEquals(caption,post.title);assertEquals(caption,post.body)
    }

    @Test fun entityUnicodeAndWhitespaceDifferencesDoNotRepeatTheHeading() {
        val rawBody="Cafe\u0301 & public\u00a0records\nremain available 🇺🇸"
        val value=post.copy(title="<b>Café &amp; public</b> records\u202fremain available 🇺🇸",body=rawBody)
        assertEquals("",PostPresentation.text(value).title)
        assertEquals(rawBody,PostPresentation.text(value).body)
        assertEquals(rawBody,PostPresentation.content(value))
        assertTrue(value.title.contains("&amp;"))
    }

    @Test fun aClearlyTruncatedFeedHeadingDoesNotEchoTheFullCaption() {
        for(ending in listOf("…","...")) {
            val value=post.copy(title="A public project update describes$ending")
            assertEquals("",PostPresentation.text(value).title)
            assertEquals(caption,PostPresentation.content(value))
        }
    }
    @Test fun aKnownInstagramReaderShortExcerptDoesNotPrependTheCaptionAgain() {
        val body="New week. Same mission. An America worth fighting for. 🦅"
        val value=post.copy(platform=Platform.INSTAGRAM,title="New week. Same mission. ...",body=body,
            provenance=Provenance(Source(Platform.INSTAGRAM,"rss-bridge","https://reader.example.org"),1,"https://reader.example.org/feed"))
        assertEquals(body,PostPresentation.content(value))
        assertEquals(value.title,PostPresentation.text(value.copy(provenance=null)).title)
        assertEquals("A different public update...",PostPresentation.text(value.copy(title="A different public update...")).title)
    }

    @Test fun explicitHttpsAndTheSameVisibleLinkLabelDoNotRepeatACaption() {
        val label="Example.test/Updates/Status?day=20&mode=New#Latest"
        val value=post.copy(title="$caption Visit https://$label",body="$caption Visit $label")
        val before=Gson().toJson(value)
        assertEquals("",PostPresentation.text(value).title)
        assertEquals(value.body,PostPresentation.content(value))
        assertEquals(before,Gson().toJson(value))
        assertEquals("",PostPresentation.text(value.copy(title=value.body,body=value.title)).title)
    }

    @Test fun generatedAttributionNeedsTheKnownAuthorAndAProvenBodyEcho() {
        for(author in listOf("Fixture","@Fixture"))for(name in listOf("fixture","@fixture")) {
            for(heading in listOf(caption,"A public project update describes…")) {
                val value=post.copy(author=author,title="Post by $name: $heading")
                assertEquals("",PostPresentation.text(value).title)
                assertEquals(caption,PostPresentation.content(value))
                assertTrue(value.title.startsWith("Post by"))
            }
        }
    }

    @Test fun generatedAttributionDoesNotHideDifferentAuthorsClaimsOrShortExcerpts() {
        for(title in listOf("Post by another: $caption","A post by Fixture: $caption","Post by Fixture $caption",
            "Post by Fixture: A different public update…","Post by Fixture: A public project update…")) {
            val value=post.copy(author="Fixture",title=title)
            assertEquals(title,PostPresentation.text(value).title)
        }
        val unknown=post.copy(author="",title="Post by Fixture: $caption")
        assertEquals(unknown.title,PostPresentation.text(unknown).title)
    }

    @Test fun attributedFullCaptionWithGeneratedEllipsisDoesNotRepeatTheFlyingCarsPost() {
        val body="We really got flying cars before GTA 6"
        for(ellipsis in listOf("...","…")) {
            val value=post.copy(platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/DekujBuoIKa",author="whitehouse",title="Post by whitehouse: $body$ellipsis",body=body)
            val before=Gson().toJson(value)
            assertEquals("",PostPresentation.text(value).title)
            assertEquals(body,PostPresentation.content(value))
            assertEquals(before,Gson().toJson(value))
            for(title in listOf("Post by another: $body$ellipsis","$body$ellipsis",
                "Post by whitehouse: We really got flying cars before GTA 7$ellipsis",
                "Post by whitehouse: $body with additional details$ellipsis")) {
                assertEquals(title,PostPresentation.text(value.copy(title=title)).title)
            }
        }
    }

    @Test fun titleUrlDestinationsProtocolsPathsQueriesAndNumericClaimsRemainMeaningful() {
        val label="example.test/Approved?count=20&mode=New#Latest"
        val body="$caption Read $label"
        for(other in listOf("other.test/Approved?count=20&mode=New#Latest","example.test/Rejected?count=20&mode=New#Latest",
            "example.test/approved?count=20&mode=New#Latest","example.test/Approved?count=21&mode=New#Latest",
            "example.test/Approved?count=20&mode=new#Latest","example.test/Approved?count=20&mode=New#Earlier")) {
            val value=post.copy(title="$caption Read https://$other",body=body)
            assertEquals(value.title,PostPresentation.text(value).title)
        }
        val protocols=post.copy(title="$caption Read https://$label",body="$caption Read http://$label")
        assertEquals(protocols.title,PostPresentation.text(protocols).title)
        val numbers=post.copy(title="$caption We completed 21 projects.",body="$caption We completed 20 projects.")
        assertEquals(numbers.title,PostPresentation.text(numbers).title)
    }

    @Test fun anUppercaseFeedHeadingDoesNotRepeatTheSameMixedCaseBody() {
        val value=post.copy(title=caption.uppercase(java.util.Locale.ROOT))
        assertEquals("",PostPresentation.text(value).title)
        assertEquals(caption,PostPresentation.text(value).body)
        assertEquals(caption.uppercase(java.util.Locale.ROOT),value.title)
    }

    @Test fun shortEllipsizedTitlesRemainDistinctEvenWhenTheyStartTheBody() {
        for((title,body) in listOf("An…" to "Another public announcement follows.","A public..." to caption,"A public project update…" to caption)) {
            val value=post.copy(title=title,body=body)
            assertEquals(title,PostPresentation.text(value).title)
            assertEquals(body,PostPresentation.text(value).body)
        }
    }

    @Test fun genuinelyDistinctHeadingsAndNonTruncatedPrefixesArePreserved() {
        for(title in listOf("Project details","A public project update","A different public update…")) {
            val value=post.copy(title=title)
            assertEquals(title,PostPresentation.text(value).title)
            assertEquals("$title\n$caption",PostPresentation.content(value))
        }
    }

    @Test fun realParagraphsAndIntentionalRepetitionWithinTheBodyStayIntact() {
        val body="We completed the work.\n\nWe completed the work.\nNext: review the result."
        val value=post.copy(title="Status report",body=body)
        assertEquals("Status report",PostPresentation.text(value).title)
        assertEquals(body,PostPresentation.text(value).body)
        assertEquals("Status report\n$body",PostPresentation.content(value))
    }

    @Test fun titleOnlyMediaAndBodyOnlyPostsRetainTheirContent() {
        val media=post.copy(title="A public photograph",body="",media=listOf(Media("https://images.example.org/photo.jpg")))
        assertEquals("A public photograph",PostPresentation.text(media).title)
        assertEquals("A public photograph",PostPresentation.content(media))
        assertEquals(caption,PostPresentation.content(post.copy(title="")))
        assertEquals("",PostPresentation.content(post.copy(title="",body="")))
    }

    @Test fun cachedAndSavedStructuredRecordsUseTheSamePresentationWithoutMigration() {
        val provenance=Provenance(source,1_792_185_789_000L,source.root+"/WhiteHouse/rss",cached=true)
        val stored=post.copy(provenance=provenance,media=listOf(Media("https://images.example.org/photo.jpg")),replyCount=12)
        val json=Gson().toJson(stored)
        val cached=Gson().fromJson(json,Post::class.java)
        assertEquals("",PostPresentation.text(cached).title)
        assertEquals(caption,PostPresentation.text(cached).body)
        assertEquals(json,Gson().toJson(cached))
        assertEquals(provenance,cached.provenance);assertEquals(stored.media,cached.media)
        assertEquals(stored.canonicalUrl,cached.canonicalUrl);assertEquals(caption,cached.title)
    }

    @Test fun rssExtractionRetainsBothStructuredFieldsWhileNativePresentationShowsOneCaption() {
        val xml=feed(listOf(post.canonicalUrl))
        val parsed=Extraction.parse(Payload(xml,source.root+"/WhiteHouse/rss","application/rss+xml",200),routes.target(Platform.X,"WhiteHouse"),source,routes).posts.single()
        assertEquals(caption,parsed.title);assertEquals(caption,parsed.body)
        assertEquals("",PostPresentation.text(parsed).title)
        assertEquals(caption,PostPresentation.content(parsed))
        assertEquals(source,parsed.provenance?.source)
        assertEquals("post:2111206334771220528",parsed.id)
    }

    @Test fun originalMobileAndNitterUrlsForOneStatusDoNotCreateExtraFeedCards() {
        val id="2111206334771220528"
        val variants=listOf("https://twitter.com/WhiteHouse/status/$id?s=20","https://x.com/i/web/status/$id",source.root+"/WhiteHouse/status/$id#reply","https://mobile.twitter.com/WhiteHouse/statuses/$id/photo/1")
        assertEquals(setOf("post:$id"),variants.map {ContentIdentity.key(Platform.X,it)}.toSet())
        val page=Extraction.parse(Payload(feed(variants),source.root+"/WhiteHouse/rss","application/rss+xml",200),routes.target(Platform.X,"WhiteHouse"),source,routes)
        assertEquals(1,page.posts.size)
        assertEquals(caption,PostPresentation.content(page.posts.single()))
    }

    @Test fun distinctStatusIdentitiesAreNotCollapsedJustBecauseTheyReuseACaption() {
        val urls=listOf(post.canonicalUrl,post.canonicalUrl.replace("2111206334771220528","2111206334771220529"))
        val page=Extraction.parse(Payload(feed(urls),source.root+"/WhiteHouse/rss","application/rss+xml",200),routes.target(Platform.X,"WhiteHouse"),source,routes)
        assertEquals(2,page.posts.size)
        assertEquals(2,page.posts.map {it.id}.distinct().size)
        assertTrue(page.posts.all {PostPresentation.content(it)==caption})
    }

    private fun feed(urls: List<String>)="""<?xml version="1.0"?><rss version="2.0"><channel><title>Public account</title>${urls.joinToString("") {url->"""<item><title>$caption</title><link>${url.replace("&","&amp;")}</link><description><![CDATA[<p>$caption</p>]]></description><author>@WhiteHouse</author><pubDate>2026-10-16T12:00:00Z</pubDate></item>"""}}</channel></rss>"""
}
