package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class CrossPostsTest {
    private val time=1_800_000_000_000L
    private val caption="Our community project has reached another important milestone. The team is sharing detailed public notes about the work completed this week, the remaining tasks, and the next steps everyone can follow together."
    private val medium="A detailed update about our project, including the latest work and the next steps for the community."
    private val article="https://example.org/updates/community"
    private fun post(platform: Platform,text: String=caption,id: String="123",at: Long?=time,title: String=""): Post {
        val url=when(platform) {
            Platform.X->"https://x.com/fixture/status/$id"
            Platform.BLUESKY->"https://bsky.app/profile/fixture.test/post/$id"
            Platform.THREADS->"https://www.threads.com/@fixture/post/$id"
            Platform.INSTAGRAM->"https://www.instagram.com/p/$id/"
            else->error("fixture")
        }
        return Post(id,platform,url,"A public display name",text,title,publishedAt=at)
    }
    private fun account(platform: Platform,person: String?="person"): Follow {
        val url=when(platform) {
            Platform.X->"https://x.com/fixture"
            Platform.BLUESKY->"https://bsky.app/profile/fixture.test"
            Platform.THREADS->"https://www.threads.com/@fixture"
            Platform.INSTAGRAM->"https://www.instagram.com/fixture/"
            else->error("fixture")
        }
        return Follow(platform.id,Target(platform,TargetKind.PROFILE,"fixture",url),"Fixture",personId=person)
    }
    private val linked=listOf(account(Platform.X),account(Platform.BLUESKY),account(Platform.THREADS))

    @Test fun aSpecificShortCaptionNeedsIndependentImageEvidenceWithoutClaimingLinkedAccounts() {
        val text="We really got flying cars before GTA 6"
        val x=post(Platform.X,text).copy(media=listOf(Media("https://x-images.example.org/flying.jpg")))
        val blue=post(Platform.BLUESKY,text,at=time+2_000).copy(media=listOf(Media("https://blue-images.example.org/flying.jpg")))
        assertEquals(2,CrossPosts.group(listOf(x,blue),emptyList()).size)
        assertEquals(1,CrossPosts.group(listOf(x,blue),linked,sameMedia={_,_->true}).size)
        assertEquals(1,CrossPosts.group(listOf(x,blue),emptyList(),sameMedia={_,_->true}).size)
        assertEquals(1,CrossPosts.group(listOf(x,blue.copy(publishedAt=time+7*3_600_000L)),linked,sameMedia={_,_->true}).size)
        assertEquals(2,CrossPosts.group(listOf(x,blue.copy(body=text.replace("6","7"))),linked,sameMedia={_,_->true}).size)
        assertEquals(2,CrossPosts.group(listOf(x.copy(body="Winning for YOU"),blue.copy(body="Winning for YOU")),emptyList(),sameMedia={_,_->true}).size)
    }
    @Test fun linkedPublishersCollectShortConcurrentEditionsWithDistinctMedia() {
        for(text in listOf("WE LOVE NORTH CAROLINA! 🇺🇸","Only in America.","NEXT STOP: KANSAS 🇺🇸","It's that time again... 🏀")) {
            val a=post(Platform.X,text).copy(media=listOf(Media("https://images.example.org/one.jpg")))
            val b=post(Platform.BLUESKY,text,at=time+240_000).copy(media=listOf(Media("https://images.example.org/two.jpg")))
            assertEquals(text,1,CrossPosts.group(listOf(a,b),linked).size)
            assertEquals(2,CrossPosts.group(listOf(a,b),emptyList()).size)
            assertEquals(2,CrossPosts.group(listOf(a,b.copy(publishedAt=time+601_000)),linked).size)
            assertEquals(2,CrossPosts.group(listOf(a,b.copy(media=emptyList())),linked).size)
            assertEquals(2,CrossPosts.group(listOf(a,b.copy(body="$text Updated.")),linked).size)
            assertEquals(2,CrossPosts.group(listOf(a,b.copy(publishedAt=null)),linked).size)
        }
        val text="SEE YOU SOON, NORTH CAROLINA 🦅"
        val a=post(Platform.X,text).copy(media=listOf(Media("https://images.example.org/one.jpg")))
        val b=post(Platform.BLUESKY,text,at=time+1_385_000).copy(media=listOf(Media("https://images.example.org/two.jpg")))
        assertEquals(1,CrossPosts.group(listOf(a,b),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a,b.copy(publishedAt=time+1_800_001)),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a.copy(body="Hello"),b.copy(body="Hello")),linked).size)
    }
    @Test fun concurrentCaptionCollectionsKeepQuestionsClaimsAndDestinationsDistinct() {
        val text="This is our next stop"
        val a=post(Platform.X,text).copy(media=listOf(Media("https://images.example.org/one.jpg")))
        val b=post(Platform.BLUESKY,text).copy(media=listOf(Media("https://images.example.org/two.jpg")))
        for((left,right) in listOf("$text?" to "$text.","$text 🇺🇸" to "$text 🇨🇦","$text 20" to "$text 21", "$text" to "This is not our next stop"))
            assertEquals(2,CrossPosts.group(listOf(a.copy(body=left),b.copy(body=right)),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a.copy(links=listOf("https://example.org/Case")),b.copy(links=listOf("https://example.org/case"))),linked).size)
    }
    @Test fun aMissingMediaCaptionCanStillMatchOnlyWithSpecificContentAndTightTiming() {
        val text="Honoring America's Brave First Responders: Postcard Edition 🇺🇸"
        val x=post(Platform.X,text).copy(media=listOf(Media("https://images.example.org/first-responders.jpg")))
        val blue=post(Platform.BLUESKY,text,at=time+276_000)
        assertEquals(1,CrossPosts.group(listOf(x,blue),linked).size)
        assertEquals(2,CrossPosts.group(listOf(x,blue.copy(publishedAt=time+601_000)),linked).size)
        assertEquals(2,CrossPosts.group(listOf(x,blue),emptyList()).size)
        assertEquals(1,CrossPosts.group(listOf(x,blue.copy(media=listOf(Media("https://images.example.org/different.jpg")))),linked).size)
    }
    @Test fun verifiedSameAccountShortCaptionRepeatsShareTheProvenCrossPlatformMatch() {
        val text="We really got flying cars before GTA 6"
        val x=post(Platform.X,text).copy(media=listOf(Media("https://images.example.org/one.jpg")))
        val b=post(Platform.BLUESKY,text).copy(media=x.media)
        val another=b.copy(id="another",canonicalUrl=b.canonicalUrl.replace("123","456"))
        val grouped=CrossPosts.group(listOf(x,b,another),linked,sameMedia={_,_->true})
        assertEquals(1,grouped.size);assertEquals(setOf(x,b,another),grouped.single().posts.toSet())
    }
    @Test fun anApiOriginalAndItsVerifiedCuratorRssQuoteKeepTheFullCaptionAndClip() {
        val claim="We replaced the damaged connector and successfully restored the original power supply."
        val full=".@Technician: \"$claim We also cleaned the contacts and published the complete repair instructions for everyone at the workshop.\""
        val preview="https://pbs.twimg.com/amplify_video_thumb/123456/img/frame.jpg"
        val video=Media("https://video.twimg.com/amplify_video/123456/vid/clip.mp4","video",preview=preview)
        val short=post(Platform.X,"“$claim” - @Technician 🎯",id="456").copy(media=listOf(video))
        val original=post(Platform.X,"$full Video",title="RT by @fixture: $full").copy(author="@repairteam",
            canonicalUrl="https://x.com/repairteam/status/123",media=listOf(Media(preview)),
            provenance=Provenance(Source(Platform.X,"nitter","https://reader.example.org"),time,"https://reader.example.org/fixture/rss"))
        assertEquals(setOf(original,short),CrossPosts.group(listOf(original,short),emptyList()).single().posts.toSet())
        assertEquals(original,CrossPosts.clipQuotationCaption(listOf(original,short)))
        assertNull(CrossPosts.clipQuotationCaption(listOf(original,short.copy(body="An unrelated workshop announcement follows."))))
        assertNull(CrossPosts.clipQuotationCaption(listOf(original,short.copy(publishedAt=time+49*3_600_000L))))
    }
    @Test fun newShortCaptionRulesDoNotTreatTruncationOrDifferentFlagsAsCompleteEqualContent() {
        val text="Honoring America's Brave First Responders: Postcard Edition 🇺🇸"
        val a=post(Platform.X,text);val b=post(Platform.BLUESKY,text)
        assertEquals(2,CrossPosts.group(listOf(a.copy(body="$text…"),b.copy(body="$text…")),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a,b.copy(body=text.replace("🇺🇸","🇨🇦"))),linked,sameMedia={_,_->true}).size)
    }
    @Test fun aConcurrentCaptionCollectionDoesNotTreatItsDifferentPicturesAsIdenticalCopies() {
        val text="We really got flying cars before GTA 6"
        val video=Media("https://images.example.org/common.mp4","video",preview="https://images.example.org/common-poster.jpg")
        val a=post(Platform.X,text).copy(media=listOf(Media("https://images.example.org/a.jpg"),video))
        val b=post(Platform.BLUESKY,text).copy(media=listOf(Media("https://images.example.org/b.jpg"),video))
        assertEquals(1,CrossPosts.group(listOf(a,b),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a,b),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(a,b),emptyList(),sameMedia={x,y->x.media.any {it.type=="video"}&&y.media.any {it.type=="video"}}).size)
    }
    @Test fun tokenEqualHeadlinesCannotHideCaseSensitiveDestinations() {
        val a=post(Platform.X,bodyText(),title=bodyText()+" Visit https://example.org/Approved")
            .copy(body=bodyText()+" Visit https://example.org/approved")
        val b=post(Platform.BLUESKY,a.body)
        assertEquals(2,CrossPosts.group(listOf(a,b),linked).size)
        assertTrue(PostPresentation.content(a).contains("Approved"))
    }
    private fun bodyText()="A detailed public update about the local project and its next steps for everyone in the community."

    @Test fun rssEchoAndEscapedFormattingDoNotHideTheSameCrossPlatformPost() {
        val a=post(Platform.X,"Our team’s detailed public update—everyone can inspect the work, learn what changed, and follow the next community steps.",title="Our team’s detailed public update—everyone can inspect the work, learn what changed, and follow the next community steps.")
        val b=post(Platform.BLUESKY,"Our team&#39;s detailed public update - everyone can inspect the work, learn what changed, and follow the next community steps.")
        val groups=CrossPosts.group(listOf(a,b),linked)
        assertEquals(1,groups.size);assertEquals(setOf(a,b),groups.single().posts.toSet())
    }
    private fun borderPosts(): List<Post> {
        val body="Securing the border. Standing with Angel Families. Signing the Laken Riley Act. And so much more. President Trump’s promises made, promises kept - watch it all now on TrumpTV.Gov 🇺🇸"
        val x=Post("post:2111224046046052660",Platform.X,"https://x.com/WhiteHouse/status/2111224046046052660","@WhiteHouse",body,
            title=body.replace("TrumpTV.Gov","https://TrumpTV.Gov"),publishedAt=1_792_190_011_000L,
            links=listOf("https://TrumpTV.Gov"),media=listOf(Media("https://pbs.twimg.com/media/HUyTE7AXgAEKkuZ.jpg")))
        val instagram=Post("post:DeknfR3xt3Y",Platform.INSTAGRAM,"https://www.instagram.com/p/DeknfR3xt3Y","whitehouse",body,
            title="Post by whitehouse: Securing the border. Standing with Angel Families. Signing the Laken Riley Act. And so much more. \n\n...",
            publishedAt=1_792_190_067_000L,links=emptyList(),media=listOf(Media("https://kg.meowing.de/mediaproxy?url=https%3A%2F%2Fimages.example.org%2Fborder.jpg")))
        return listOf(x,instagram)
    }
    @Test fun securingTheBorderCachedRssFieldsBecomeOneCardWithoutChangingRawRecords() {
        val gson=Gson();val originals=borderPosts();val before=originals.map(gson::toJson)
        val cached=before.map {gson.fromJson(it,Post::class.java)}
        val xFollow=Follow("whitehouse-x",Target(Platform.X,TargetKind.PROFILE,"WhiteHouse","https://x.com/WhiteHouse"),"White House",personId="whitehouse")
        val instagramFollow=Follow("whitehouse-instagram",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"whitehouse","https://www.instagram.com/whitehouse"),"White House",personId="whitehouse")
        val memberships=mapOf("instagram:post:DeknfR3xt3Y" to setOf(instagramFollow.id))
        for(accounts in listOf(emptyList(),listOf(xFollow,instagramFollow))) {
            val groups=CrossPosts.group(cached,accounts,memberships)
            assertEquals(1,groups.size);assertEquals(cached.toSet(),groups.single().posts.toSet())
        }
        assertEquals(before,cached.map(gson::toJson))
        assertTrue(cached.all {PostPresentation.text(it).title.isEmpty()})
    }
    @Test fun aBareDestinationAndAnExpandedApiDestinationMatchWithoutLosingLinkIdentity() {
        val (first,second)=borderPosts()
        val api=first.copy(title="",body=first.body.replace("TrumpTV.Gov","https://TrumpTV.Gov"))
        val follows=listOf(account(Platform.X),account(Platform.INSTAGRAM))
        assertEquals(1,CrossPosts.group(listOf(api,second),follows).size)
        assertNotNull(CrossPostFusion.compose(listOf(api,second)))
        val different=second.copy(body=second.body.replace("TrumpTV.Gov","OtherSite.Gov"))
        assertEquals(2,CrossPosts.group(listOf(api,different),follows).size)
    }
    @Test fun borderFormattingFixStillRetainsDifferentAttributionDestinationsAndClaims() {
        val (x,instagram)=borderPosts()
        val differentX=listOf(x.copy(title=x.title.replace("TrumpTV.Gov","Other.test")),
            x.copy(title=x.title.replace("TrumpTV.Gov","TrumpTV.Gov/Other")),
            x.copy(title=x.title.replace("TrumpTV.Gov","TrumpTV.Gov?count=20")),
            x.copy(title=x.title.replace("Standing with","Not standing with")),
            x.copy(title="A different policy announcement"))
        differentX.forEach {assertEquals(2,CrossPosts.group(listOf(it,instagram),emptyList()).size)}
        val wrongAuthor=instagram.copy(title=instagram.title.replace("Post by whitehouse:","Post by another:"))
        assertEquals(2,CrossPosts.group(listOf(x,wrongAuthor),emptyList()).size)
    }
    @Test fun repeatedEntityEscapesUnicodeSpacingAndInvisibleArtifactsNormalizeBoundedly() {
        val a=post(Platform.X,medium.replace("project","pro\u200bject").replace(" ","\u00a0"))
        val b=post(Platform.BLUESKY,medium.replace("project","project").replace("community.","community&amp;#46;"))
        assertEquals(1,CrossPosts.group(listOf(a,b),linked).size)
    }
    @Test fun genuinelyDifferentTitlesRemainPartOfTheContent() {
        val a=post(Platform.X,medium,title="Approved project")
        val b=post(Platform.BLUESKY,medium,title="Rejected project")
        assertEquals(2,CrossPosts.group(listOf(a,b),linked).size)
    }
    @Test fun substantiveIdenticalPublicContentCanMatchWithoutClaimingTheAuthorsAreLinked() {
        val a=post(Platform.X);val b=post(Platform.BLUESKY)
        assertEquals(1,CrossPosts.group(listOf(a,b),emptyList()).size)
        assertEquals(1,CrossPosts.group(listOf(a,b),listOf(account(Platform.X,"one"),account(Platform.BLUESKY,"two"))).size)
        assertEquals(1,CrossPosts.group(listOf(a,b.copy(publishedAt=time+7*60*60*1000)),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(a,b.copy(publishedAt=time+CrossPosts.MAX_COPY_WINDOW+1)),emptyList()).size)
    }
    @Test fun mediumUnlinkedCaptionsNeedAnIndependentMeaningfulDestinationOrMedia() {
        val a=post(Platform.X,medium);val b=post(Platform.BLUESKY,medium)
        assertEquals(2,CrossPosts.group(listOf(a,b),emptyList()).size)
        assertEquals(1,CrossPosts.group(listOf(a.copy(links=listOf(article)),b.copy(links=listOf("$article?utm_source=platform"))),emptyList()).size)
        assertEquals(1,CrossPosts.group(listOf(a.copy(media=listOf(Media("https://images.example.org/capture.png"))),b.copy(media=listOf(Media("https://images.example.org/capture.png")))),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(a.copy(links=listOf("https://example.org/")),b.copy(links=listOf("https://example.org/"))),emptyList()).size)
    }
    @Test fun shortSlogansAndUnknownDatesStaySeparateEvenWhenAnImageOrArticleIsShared() {
        for(at in listOf(time,null)) {
            val a=post(Platform.X,"Winning for YOU",at=at).copy(links=listOf(article))
            val b=post(Platform.BLUESKY,"Winning for YOU",at=at).copy(links=listOf(article))
            assertEquals(2,CrossPosts.group(listOf(a,b),linked).size)
        }
    }
    @Test fun trailingHashtagAdaptationsOnlyLowerTheThresholdForProvenLinkedPaths() {
        val a=post(Platform.X,medium);val b=post(Platform.BLUESKY,"$medium #Community #OpenProject")
        assertEquals(1,CrossPosts.group(listOf(a,b),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a,b),emptyList()).size)
    }
    @Test fun anExplicitSubstantialTruncationNeedsAnExactPrefixAndCorroboration() {
        val prefix=caption.split(' ').take(29).joinToString(" ")
        val a=post(Platform.X,"$prefix…").copy(links=listOf(article))
        val b=post(Platform.BLUESKY).copy(links=listOf(article))
        assertEquals(1,CrossPosts.group(listOf(a,b),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a.copy(links=emptyList()),b.copy(links=emptyList())),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a.copy(body=prefix),b),linked).size)
    }
    @Test fun changedNumbersNegationClaimsAndDestinationsAreNotFormattingAdaptations() {
        val a=post(Platform.X,"$caption We support 20 community projects.").copy(links=listOf(article))
        val changes=listOf(a.body.replace("20","21"),a.body.replace("We support","We do not support"),a.body.replace("We support","We oppose"))
        for(text in changes)assertEquals(2,CrossPosts.group(listOf(a,post(Platform.BLUESKY,text).copy(links=listOf(article))),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a,post(Platform.BLUESKY,a.body).copy(links=listOf("https://example.org/updates/different"))),linked).size)
    }
    @Test fun signedCurrencyComparisonDecimalAndPercentClaimsRetainTheirMeaning() {
        for((first,second) in listOf(
            "-20" to "20", "+20" to "-20", "−20" to "20", "$20" to "€20", "$ -20" to "€ -20",
            "<20" to ">20", "<=20" to ">=20", "≤20" to "≥20", "!=20" to "=20",
            "2.5" to "25", "2,500" to "2500", "20%" to "20", "20‰" to "20%", "20 €" to "20 £"
        )) {
            val a=post(Platform.X,"$caption The measured result is $first.")
            val b=post(Platform.BLUESKY,"$caption The measured result is $second.")
            assertEquals("Conflicting claims $first / $second must stay separate",2,CrossPosts.group(listOf(a,b),linked).size)
        }
        val a=post(Platform.X,"$caption The measured result is &lt; - $ 20.5 %.")
        val b=post(Platform.BLUESKY,"$caption The measured result is <-$20.5%.")
        assertEquals("Whitespace and entity formatting preserve the same numeric claim",1,CrossPosts.group(listOf(a,b),linked).size)
    }
    @Test fun canonicalAliasesChooseTheFullSnapshotAndVerifiedRepeatOriginalsAreRetained() {
        val full=post(Platform.X)
        val preview=full.copy(canonicalUrl="https://mobile.twitter.com/fixture/status/123?utm_source=share",body="Public preview",preview=true)
        val grouped=CrossPosts.group(listOf(preview,full),linked)
        assertEquals(listOf(full),grouped.single().posts)
        val repeated=full.copy(id="456",canonicalUrl="https://x.com/fixture/status/456")
        assertEquals(setOf(full,repeated),CrossPosts.group(listOf(full,repeated),linked).single().posts.toSet())
    }
    @Test fun repeatedPostsKeepTheirCrossPlatformMatchRegardlessOfChronologicalOrder() {
        val newest=post(Platform.X,at=time+1000)
        val other=post(Platform.BLUESKY,at=time)
        val repeated=post(Platform.X,id="456",at=time-1000)
        val grouped=CrossPosts.group(listOf(newest,other,repeated),linked)
        assertEquals(1,grouped.size);assertEquals(repeated,grouped.single().primary)
    }
    @Test fun compatibilityChainsCannotFoldDifferentFullPostsThroughASharedTruncatedPrefix() {
        val prefix=caption.split(' ').take(29).joinToString(" ")
        val a=post(Platform.X,"$caption More work follows.").copy(links=listOf(article))
        val b=post(Platform.BLUESKY,"$prefix…").copy(links=listOf(article))
        val c=post(Platform.THREADS,"$caption Further notes follow.").copy(links=listOf(article))
        val grouped=CrossPosts.group(listOf(a,b,c),linked)
        assertEquals(2,grouped.size);assertFalse(grouped.any {a in it.posts&&c in it.posts})
    }
    @Test fun opaquePostIdsAndSpoofedHostsDoNotInheritOwnershipFromDisplayNames() {
        val a=post(Platform.X,medium)
        val opaque=post(Platform.INSTAGRAM,medium).copy(author="fixture")
        assertEquals(2,CrossPosts.group(listOf(a,opaque),linked+account(Platform.INSTAGRAM)).size)
        val spoof=post(Platform.BLUESKY,medium).copy(author="fixture",canonicalUrl="https://attacker.example/profile/fixture.test/post/123")
        assertEquals(2,CrossPosts.group(listOf(a,spoof),linked).size)
    }
    @Test fun linkedOpaquePostsCanUseExactLocalFeedMembershipWithoutGuessingAnAuthor() {
        val a=post(Platform.X,medium);val b=post(Platform.INSTAGRAM,medium)
        val instagram=account(Platform.INSTAGRAM)
        val memberships=mapOf("${b.platform.id}:${ContentIdentity.key(b.platform,b.canonicalUrl)}" to setOf(instagram.id))
        assertEquals(1,CrossPosts.group(listOf(a,b),linked+instagram,memberships).size)
        assertEquals(2,CrossPosts.group(listOf(a,b),linked+instagram.copy(enabled=false),memberships).size)
        assertEquals(2,CrossPosts.group(listOf(a,b),linked+instagram,memberships.mapValues {setOf(account(Platform.X).id)}).size)
    }
    @Test fun conflictingOrNonProfileFeedMembershipCannotCertifyALinkedSourceBundle() {
        val a=post(Platform.X,medium);val b=post(Platform.INSTAGRAM,medium)
        val instagram=account(Platform.INSTAGRAM)
        val another=instagram.copy(id="other",personId="another-person")
        val key="${b.platform.id}:${ContentIdentity.key(b.platform,b.canonicalUrl)}"
        assertEquals(2,CrossPosts.group(listOf(a,b),linked+listOf(instagram,another),mapOf(key to setOf(instagram.id,another.id))).size)
        val tag=instagram.copy(target=instagram.target.copy(kind=TargetKind.TAG))
        assertEquals(2,CrossPosts.group(listOf(a,b),linked+tag,mapOf(key to setOf(tag.id))).size)
    }
    @Test fun repliesAndPreviewsNeverBecomeACrossPlatformRootCard() {
        val a=post(Platform.X)
        val b=post(Platform.BLUESKY)
        assertEquals(2,CrossPosts.group(listOf(a,b.copy(parentId="root")),linked).size)
        assertEquals(2,CrossPosts.group(listOf(a,b.copy(preview=true)),linked).size)
    }
    @Test fun oversizedTextsAndHugeAmbiguousBucketsKeepEveryDistinctPost() {
        val huge="${caption.repeat(100)} ending"
        assertEquals(2,CrossPosts.group(listOf(post(Platform.X,huge),post(Platform.BLUESKY,huge)),linked).size)
        val repeated=(0 until 150).map {index->post(if(index%2==0)Platform.X else Platform.BLUESKY,id=index.toString())}
        assertEquals(150,CrossPosts.group(repeated,linked).size)
    }
    @Test fun aThousandPostFeedIsIndexedAndRetainsEveryDistinctDiscussionIdentity() {
        val posts=(0 until 500).flatMap {i->listOf(post(Platform.X,"$caption Batch $i.",id=i.toString(),at=time-i),post(Platform.BLUESKY,"$caption Batch $i.",id=i.toString(),at=time-i))}
        val start=System.nanoTime();val grouped=CrossPosts.group(posts,linked);val elapsed=(System.nanoTime()-start)/1_000_000
        println("CrossPosts1000: ${elapsed}ms; ${grouped.size} cards")
        assertEquals(500,grouped.size);assertEquals(1000,grouped.sumOf {it.posts.size})
        assertTrue("Indexed1000-post grouping took ${elapsed}ms",elapsed<10_000)
    }
    @Test fun commonArticlesHomepagesProfilesAndStockImagesDoNotPolluteSpecificTextPairs() {
        val posts=(0 until 150).flatMap {i->listOf(Platform.X,Platform.BLUESKY).map {platform->
            post(platform,"$caption Batch $i.",id=i.toString()).copy(
                links=listOf(article,"https://example.org/","https://x.com/fixture"),
                media=listOf(Media("https://images.example.org/common-branding.png")))
        }}
        val grouped=CrossPosts.group(posts,linked)
        assertEquals(150,grouped.size);assertTrue(grouped.all {it.posts.size==2})
        assertEquals(300,grouped.sumOf {it.posts.size})
    }
}
