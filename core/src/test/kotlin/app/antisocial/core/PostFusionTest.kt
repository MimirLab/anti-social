package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PostFusionTest {
    private val time=1_800_000_000_000L
    private val shared="Our community project has reached another important milestone. The team is sharing detailed public notes about the work completed this week, the remaining tasks, and the next steps everyone can follow together."
    private val image="https://images.example.org/workshop.jpg"
    private fun post(platform: Platform,body: String,id: String="123",media: String=image): Post {
        val url=when(platform) {
            Platform.X->"https://x.com/fixture/status/$id"
            Platform.BLUESKY->"https://bsky.app/profile/fixture.test/post/$id"
            Platform.THREADS->"https://www.threads.com/@fixture/post/$id"
            else->error("fixture")
        }
        return Post(id,platform,url,"fixture",body,publishedAt=time,media=listOf(Media(media)))
    }
    private fun account(platform: Platform): Follow {
        val url=when(platform) {
            Platform.X->"https://x.com/fixture"
            Platform.BLUESKY->"https://bsky.app/profile/fixture.test"
            Platform.THREADS->"https://www.threads.com/@fixture"
            else->error("fixture")
        }
        return Follow(platform.id,Target(platform,TargetKind.PROFILE,"fixture",url),"Fixture",personId="same-project")
    }
    private val linked=listOf(Platform.X,Platform.BLUESKY,Platform.THREADS).map(::account)
    @Test fun aCaptionOnlyFacebookReelAliasCannotMakeTheCompleteVideoCopyAmbiguous() {
        val caption="Progress will come through patient work. Keep moving forward."
        val x=post(Platform.X,caption).copy(media=listOf(Media("https://video.example/one.mp4","video")))
        val fb=x.copy(id="fb",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/123456/posts/456789",publishedAt=time+110_000)
        val reel=fb.copy(id="reel",canonicalUrl="https://www.facebook.com/reel/987654",title=caption,media=emptyList())
        val f=Follow("facebook-feed",Target(Platform.FACEBOOK,TargetKind.PROFILE,"fixture","https://www.facebook.com/fixture"),"Fixture",personId="same-project")
        val membership=mapOf(CrossPosts.originalKey(fb) to setOf(f.id),CrossPosts.originalKey(reel) to setOf(f.id))
        for(input in listOf(listOf(x,fb,reel),listOf(reel,fb,x))) {
            val group=CrossPosts.group(input,linked+f,membership).single()
            assertEquals(input.toSet(),group.posts.toSet());assertEquals(1,CrossPostFusion.compose(group.posts)!!.segments.size)
        }
        assertEquals(2,CrossPosts.group(listOf(fb,reel),listOf(f)).size)
        assertEquals(2,CrossPosts.group(listOf(fb,reel.copy(publishedAt=fb.publishedAt!!+1)),listOf(f),membership).size)
        assertEquals(2,CrossPosts.group(listOf(fb,reel.copy(body=caption.replace("Progress","Retreat"))),listOf(f),membership).size)
    }
    @Test fun aCompactSharedParagraphCanStillQualifyAnImageCorroboratedWorkNameAlias() {
        val rest="recently mapped new stars in this vast cloud. This small area of gas and dust, named Q17, is far from Earth, in the outer part of our galaxy."
        val x=post(Platform.X,"The @AsterDawn telescope $rest")
        val blue=post(Platform.BLUESKY,"Aster's Maya Dawn Space Telescope $rest")
        assertTrue(CaptionAliases.equivalent(x,blue,mediaConfirmed=true))
        assertFalse(CaptionAliases.equivalent(x,blue,mediaConfirmed=false))
        assertEquals(1,CrossPosts.group(listOf(x,blue),linked).size)
        assertEquals(1,CrossPostFusion.composeMedia(listOf(x,blue))!!.segments.size)
        assertFalse(CaptionAliases.equivalent(x,blue.copy(body=blue.body.replace("Q17","Q18")),mediaConfirmed=true))
        assertFalse(CaptionAliases.equivalent(x.copy(body=x.body.replace("AsterDawn","AsterDusk")),blue,mediaConfirmed=true))
    }
    @Test fun aNitterMentionAnchorIsNotACompetingArticleInACorroboratedAnnouncement() {
        val head="The community workshop is opening its public reading room."
        val article="https://workshop.example/schedule"
        val p=Provenance(Source(Platform.X,"nitter","https://reader.example"),time,"https://reader.example/fixture/rss")
        val x=post(Platform.X,"$head Meet @WorkshopCrew and inspect the new equipment. $article").copy(provenance=p,links=listOf("https://reader.example/WorkshopCrew",article))
        val blue=post(Platform.BLUESKY,"$head Meet our trained volunteers and inspect the new equipment. $article")
        assertEquals(1,CrossPosts.group(listOf(x,blue),linked).size)
        assertNotNull(CrossPostFusion.compose(listOf(x,blue)))
        assertEquals(2,CrossPosts.group(listOf(x.copy(links=listOf("https://reader.example/SomeoneElse",article)),blue),linked).size)
        assertEquals(2,CrossPosts.group(listOf(x.copy(links=listOf("https://reader.example.attacker.test/WorkshopCrew",article)),blue),linked).size)
    }
    @Test fun matchingVideoPostersCorroborateAnAnnouncementWithoutErasingEitherRecording() {
        val head="There is plenty of work ahead when you are building a community workshop."
        val cover="https://images.example.org/workshop-cover.jpg"
        val a=post(Platform.X,"$head Inspect the repair stations with our volunteers.").copy(media=listOf(Media("https://video.example.org/one.mp4","video",preview=cover)))
        val b=post(Platform.BLUESKY,"$head Browse the public reading room and join the afternoon tour.").copy(media=listOf(Media("https://video.example.org/two.mp4","video",preview=cover)))
        val originals=listOf(a,b)
        assertEquals(originals.toSet(),CrossPosts.group(originals,linked).single().posts.toSet())
        assertEquals(head,CrossPostFusion.compose(originals)!!.segments.first().text)
        assertEquals(listOf("https://video.example.org/one.mp4","https://video.example.org/two.mp4"),originals.flatMap {it.media}.map {it.url})
        val different=b.copy(media=listOf(b.media.single().copy(preview="https://images.example.org/different.jpg")))
        assertEquals(2,CrossPosts.group(listOf(a,different),linked).size)
        val detail=ByteArray(4096){80};val changed=detail.clone().apply {this[100]=0}
        val one=VisualFrames.encode(.6,40.0,0,0,List(48){100},detail)!!
        val two=VisualFrames.encode(.6,40.0,0,0,List(48){100},changed)!!
        assertFalse(VisualFrames.sameImage(one,two));assertTrue(VisualFrames.same(one,two))
        val editions=listOf(a.copy(media=listOf(a.media.single().copy(visualFingerprint=one))),different.copy(media=listOf(different.media.single().copy(visualFingerprint=two))))
        assertEquals(1,CrossPosts.group(editions,linked).size)
        assertEquals(2,CrossPosts.group(editions,linked).single().posts.flatMap {it.media}.map {it.url}.distinct().size)
    }
    @Test fun aCorroboratedHeadlineKeepsComplementaryLongEditionsOnOneCard() {
        val head="There is plenty of work ahead when you are building a community workshop."
        val first=post(Platform.X,"$head The repair stations will serve visitors throughout the morning. Bring your damaged equipment and meet the trained volunteers.")
        val second=post(Platform.BLUESKY,"$head We have published a complete plan for the new reading room. Join the afternoon demonstration and inspect the bookshelves, with free admission for all visitors.")
        assertEquals(1,CrossPosts.group(listOf(first,second),linked).size)
        val composed=CrossPostFusion.compose(listOf(first,second))!!
        assertEquals(head,composed.segments.first().text)
        assertEquals(first.body.substringAfter(head).trim(),composed.segments[1].text)
        assertEquals(second.body.substringAfter(head).trim(),composed.segments[2].text)
        assertEquals(2,CrossPosts.group(listOf(first,second.copy(media=listOf(Media("https://images.example/other.jpg")))),linked).size)
        assertEquals(2,CrossPosts.group(listOf(first,second),emptyList()).size)
    }
    @Test fun aRenditionEnclosureAndMissingMediaDoNotRepeatAnAlreadyCollectedCaption() {
        val text="The volunteer workshop has opened its public reading room. Meet the team, browse the equipment, and find the complete schedule inside."
        val cid="bafkreif4jreqaslj6zvmoatow4k5dfadalvovosiuhufhesf4q7ccap7s4"
        val asset="plain/did:plc:aaaaaaaaaaaaaaaaaaaaaaaa/$cid"
        val full="https://cdn.bsky.app/img/feed_fullsize/$asset"
        val blue=post(Platform.BLUESKY,text,media="https://cdn.bsky.app/img/feed_thumbnail/$asset").copy(links=listOf(full))
        val x=post(Platform.X,"$text https://t.co/FixtureOne")
        val noMedia=post(Platform.THREADS,text).copy(media=emptyList())
        val editions=listOf(x,blue,noMedia)
        assertTrue(CrossPosts.onlyOpaqueOrOriginalLinks(blue))
        assertEquals(1,CrossPostFusion.compose(editions)!!.segments.size)
        assertEquals(editions.map(CrossPosts::originalKey).toSet(),CrossPostFusion.compose(editions)!!.segments.single().originalKeys)
        assertFalse(CrossPosts.onlyOpaqueOrOriginalLinks(blue.copy(body="$text $full")))
        val anotherAsset=full.replace(cid,"b"+"a".repeat(58))
        assertFalse(CrossPosts.onlyOpaqueOrOriginalLinks(blue.copy(links=listOf(anotherAsset))))
        assertFalse(CrossPosts.onlyOpaqueOrOriginalLinks(blue.copy(links=listOf("https://publisher.example/different-story"))))
    }
    @Test fun linkedConcurrentHeadlinesRetainShortCallsToActionWithoutRepeatingTheAnnouncement() {
        val headline="OPEN WORKSHOP: Come and meet the dedicated volunteers who maintain our community's public equipment and shared reading room."
        val x=post(Platform.X,"$headline https://t.co/FixtureOne\nJoin the tour below ⬇️")
        val blue=post(Platform.BLUESKY,"$headline WorkshopFixture.test",media="https://images.example.org/another.png")
        assertEquals(1,CrossPosts.group(listOf(x,blue),linked).size)
        val fusion=CrossPostFusion.compose(listOf(x,blue))!!
        assertEquals(headline,fusion.segments.first().text)
        assertTrue(fusion.segments.first().shared)
        assertEquals(x.body.substringAfter(headline).trim(),fusion.segments[1].text)
        assertEquals(setOf(Platform.X),fusion.segments[1].platforms)
        assertEquals("WorkshopFixture.test",fusion.segments[2].text)
        assertEquals(2,CrossPosts.group(listOf(x,blue),emptyList()).size)
        for(other in listOf(blue.copy(body="$headline We are not open. WorkshopFixture.test"),
            blue.copy(body="$headline 20 places. WorkshopFixture.test"),blue.copy(publishedAt=time+601_000),
            blue.copy(body="$headline WorkshopFixture.test/another-event"),blue.copy(body=blue.body.replace("dedicated","retired")),blue.copy(media=emptyList())))
            assertEquals(other.body,2,CrossPosts.group(listOf(x,other),linked).size)
    }
    private fun editions(): List<Post> = listOf(
        post(Platform.X,"For the workshop team:\n\n$shared\n\nThere are 20 volunteer places available."),
        post(Platform.BLUESKY,"A note from our project office:\n\n$shared\n\nWe are not opening enrollment until Friday.")
    )

    @Test fun mediaCopiesWithOnlyPlatformSpecificMentionsRetainEveryReferenceAndShareTheProse() {
        val body="The workshop has published its complete equipment inventory. Our team is signing an agreement between @RepairCrew and @ToolLibrary and meeting with students at @LocalUniversity to show residents how to inspect battery connectors, replace damaged cables and safely recycle old cells. The same practical work will continue next weekend with another free community repair session."
        val first=post(Platform.X,body)
        val second=post(Platform.THREADS,body.replace("@RepairCrew","@repaircrewgroup").replace("@ToolLibrary","@locallibrary").replace("@LocalUniversity","@universitylocal"))
        val groups=CrossPosts.group(listOf(first,second),emptyList())
        assertEquals(1,groups.size)
        val fusion=CrossPostFusion.composeMedia(groups.single().posts)!!
        val text=fusion.segments.joinToString("\n"){it.text}
        for(name in listOf("@RepairCrew","@ToolLibrary","@LocalUniversity","@repaircrewgroup","@locallibrary","@universitylocal"))assertTrue(name,text.contains(name))
        assertEquals(1,Regex("complete equipment inventory").findAll(text).count())
        assertEquals(1,Regex("safely recycle old cells").findAll(text).count())
        assertEquals(2,CrossPosts.group(listOf(first,second.copy(media=listOf(Media("https://other.example.org/unproven.jpg")))),emptyList()).size)
        for(changed in listOf(second.body.replace("safely recycle","do not recycle"),second.body.replace("next weekend","in 8 weeks")))assertEquals(2,CrossPosts.group(listOf(first,second.copy(body=changed)),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(first.copy(body=first.body+" Read example.org/Release."),second.copy(body=second.body+" Read example.org/release.")),emptyList()).size)
    }

    @Test fun aSharedCaptionAndSameImageFuseWithEverySourcesLeadingAndTrailingContext() {
        val posts=editions();val before=posts.map {Gson().toJson(it)}
        assertTrue(CrossPostFusion.matches(posts[0],posts[1]))
        val result=CrossPostFusion.compose(posts)!!
        assertEquals(5,result.segments.size)
        assertEquals(listOf(shared),result.segments.filter {it.shared}.map {it.text})
        assertEquals(setOf(Platform.X,Platform.BLUESKY),result.segments.single {it.shared}.platforms)
        assertEquals(FusionSegment("For the workshop team:",setOf(Platform.X),false,setOf(CrossPosts.originalKey(posts[0]))),result.segments[0])
        assertEquals(FusionSegment("A note from our project office:",setOf(Platform.BLUESKY),false,setOf(CrossPosts.originalKey(posts[1]))),result.segments[1])
        assertEquals(FusionSegment("There are 20 volunteer places available.",setOf(Platform.X),false,setOf(CrossPosts.originalKey(posts[0]))),result.segments[3])
        assertEquals(FusionSegment("We are not opening enrollment until Friday.",setOf(Platform.BLUESKY),false,setOf(CrossPosts.originalKey(posts[1]))),result.segments[4])
        assertEquals(before,posts.map {Gson().toJson(it)})
        val grouped=CrossPosts.group(posts,linked)
        assertEquals(1,grouped.size);assertEquals(posts.toSet(),grouped.single().posts.toSet())
    }

    @Test fun differentImageUrlsNeedCachedPositiveImageEvidenceForThePartialCaptionPath() {
        val (first,other)=editions();val second=other.copy(media=listOf(Media("https://other-images.example.org/upload.png")))
        assertEquals(2,CrossPosts.group(listOf(first,second),linked).size)
        var checks=0
        val grouped=CrossPosts.group(listOf(first,second),linked,sameMedia={a,b->checks++;a.media.first().url!=b.media.first().url})
        assertTrue(checks>0);assertEquals(1,grouped.size)
        assertEquals(2,CrossPosts.group(listOf(first,second.copy(media=emptyList())),linked,sameMedia={_,_->true}).size)
    }

    @Test fun copiedCaptionsAndSameImageCanMergeWithoutEstablishingLinkedAccountOwnership() {
        val posts=editions()
        assertTrue(CrossPosts.sameContentCopies(posts))
        assertEquals(1,CrossPosts.group(posts,emptyList(),sameMedia={_,_->true}).size)
        assertEquals(1,CrossPosts.group(posts,linked.map {it.copy(personId=it.id)},sameMedia={_,_->true}).size)
        assertEquals(1,CrossPosts.group(posts,linked.map {it.copy(enabled=false)},sameMedia={_,_->true}).size)
    }

    @Test fun conflictingNumbersAndNegationInsideTheMainClaimAreNotContextAdditions() {
        val main="$shared The measured result is 20 and the project is approved for public use."
        val first=post(Platform.X,main)
        for(body in listOf(main.replace("20","21"),main.replace("is approved","is not approved"),main.replace("is approved","is rejected"))) {
            val second=post(Platform.BLUESKY,body)
            assertFalse(CrossPostFusion.matches(first,second))
            assertNull(CrossPostFusion.compose(listOf(first,second)))
            assertEquals(2,CrossPosts.group(listOf(first,second),linked).size)
        }
        val firstClaim=post(Platform.X,"The measured result is 20.\n\n$shared")
        val secondClaim=post(Platform.BLUESKY,"The measured result is 21.\n\n$shared")
        assertFalse(CrossPostFusion.matches(firstClaim,secondClaim))
        assertEquals(2,CrossPosts.group(listOf(firstClaim,secondClaim),linked).size)
        for((a,b) in listOf("-20" to "20","20%" to "20","$20" to "€20","2.5" to "25","<20" to ">20")) {
            assertFalse(CrossPostFusion.matches(post(Platform.X,"The measured result is $a.\n\n$shared"),post(Platform.BLUESKY,"The measured result is $b.\n\n$shared")))
        }
    }

    @Test fun changedWordsInsideASentenceDoNotBecomeAJoinedMainClaim() {
        val first=post(Platform.X,shared)
        val second=post(Platform.BLUESKY,shared.replace("remaining tasks","rejected tasks"))
        assertFalse(CrossPostFusion.matches(first,second))
        assertEquals(2,CrossPosts.group(listOf(first,second),linked).size)
    }

    @Test fun distinctExtraContextCanContainNumbersAndQualifiersBecauseItStaysAttributed() {
        val first=post(Platform.X,"$shared\n\n20 places remain for volunteers.")
        val second=post(Platform.BLUESKY,"$shared\n\nDo not visit the workshop before Friday.")
        val result=CrossPostFusion.compose(listOf(first,second))!!
        assertEquals(shared,result.segments.single {it.shared}.text)
        assertTrue(result.segments.any {it.text=="20 places remain for volunteers."&&it.platforms==setOf(Platform.X)&&!it.shared})
        assertTrue(result.segments.any {it.text=="Do not visit the workshop before Friday."&&it.platforms==setOf(Platform.BLUESKY)&&!it.shared})
        assertEquals(1,CrossPosts.group(listOf(first,second),linked).size)
    }

    @Test fun unicodeSpacingAndSourceLineBreaksStillAlignWithoutRewritingOriginals() {
        val spaced=shared.replace(" ","\u00a0").replace(".\u00a0",".\n\n").replace(",\u00a0",",\n\t")
        val first=post(Platform.X,"Workshop update:\n\n$shared\n\nSee the instructions tomorrow.")
        val second=post(Platform.BLUESKY,"Studio note:\n\n$spaced\n\nJoin us on Friday.")
        val raw=listOf(first,second).map {Gson().toJson(it)}
        assertTrue(CrossPostFusion.matches(first,second))
        val result=CrossPostFusion.compose(listOf(first,second))!!
        assertEquals(shared,result.segments.single {it.shared}.text)
        assertEquals(raw,listOf(first,second).map {Gson().toJson(it)})
        assertEquals(1,CrossPosts.group(listOf(first,second),linked).size)
    }

    @Test fun proseCaseAndCurlyQuoteTypographyAlignWithOriginalDisplaySpelling() {
        val quoted="“Our team’s public report follows. $shared”"
        val other=quoted.replace('’','\'').replace('“','"').replace('”','"').uppercase(java.util.Locale.ROOT)
        val first=post(Platform.X,"Workshop update:\n\n$quoted")
        val second=post(Platform.BLUESKY,"Studio note:\n\n$other\n\nJoin us on Friday.")
        assertTrue(CrossPostFusion.matches(first,second))
        assertEquals(quoted,CrossPostFusion.compose(listOf(first,second))!!.segments.single {it.shared}.text)
        assertEquals(1,CrossPosts.group(listOf(first,second),linked).size)
    }

    @Test fun verifiedSameAccountRepeatsDoNotMakeTheirCrossPlatformEditionAmbiguous() {
        val (first,second)=editions()
        val repeated=first.copy(id="456",canonicalUrl=first.canonicalUrl.replace("123","456"),publishedAt=time-1000)
        val grouped=CrossPosts.group(listOf(first,second,repeated),linked)
        assertEquals(1,grouped.size)
        assertEquals(repeated,grouped.single().primary)
        assertNotNull(CrossPostFusion.compose(listOf(first,repeated)))
    }

    @Test fun threeSourceEditionsKeepASingleCommonCaptionAndDistinctDiscussionRoots() {
        val posts=editions()+post(Platform.THREADS,"From the craft studio:\n\n$shared\n\nThe complete illustrated instructions follow tomorrow.")
        val result=CrossPostFusion.compose(posts)!!
        assertEquals(1,result.segments.count {it.shared})
        assertEquals(posts.map {it.platform}.toSet(),result.segments.single {it.shared}.platforms)
        assertEquals(7,result.segments.size)
        val grouped=CrossPosts.group(posts,linked)
        assertEquals(1,grouped.size);assertEquals(3,grouped.single().posts.map {it.canonicalUrl}.distinct().size)
    }

    @Test fun commonPunctuationIsPreservedAndRepeatedPassagesStayAmbiguous() {
        val quoted="“$shared”"
        val first=post(Platform.X,"Workshop note:\n\n$quoted")
        val second=post(Platform.BLUESKY,"Studio report:\n\n$quoted\n\nJoin us on Friday.")
        assertEquals(quoted,CrossPostFusion.compose(listOf(first,second))!!.segments.single {it.shared}.text)
        val repeated=second.copy(body="$shared\n\n$shared")
        assertNull(CrossPostFusion.compose(listOf(post(Platform.X,shared),repeated)))
    }

    @Test fun repliesUnknownDatesDistantDatesAndPreviewCardsRemainSeparate() {
        val (first,second)=editions()
        for(variation in listOf(second.copy(parentId="parent"),second.copy(preview=true),second.copy(publishedAt=null),second.copy(publishedAt=time+49*60*60*1000L))) {
            assertEquals(2,CrossPosts.group(listOf(first,variation),linked).size)
        }
    }

    @Test fun severalSharedPassagesAlignAroundAnInsertionWithoutRepeatingTheOpeningOrClosing() {
        val opening="The public workshop report describes the careful preparation completed by our volunteer construction team."
        val closing="Everyone can review the complete materials list and follow the illustrated instructions at home."
        val first=post(Platform.X,"$opening\n\nVisitors should use the west entrance.\n\n$shared\n\n$closing")
        val second=post(Platform.BLUESKY,"$opening\n\nAn accessibility guide is available from the project office.\n\n$shared\n\n$closing")
        val before=listOf(first,second).map {Gson().toJson(it)}
        assertTrue(CrossPostFusion.matches(first,second))
        val fusion=CrossPostFusion.compose(listOf(first,second))!!
        val rendered=fusion.segments.joinToString("\n\n"){it.text}
        assertEquals(1,Regex(Regex.escape(opening)).findAll(rendered).count())
        assertEquals(1,Regex(Regex.escape(shared)).findAll(rendered).count())
        assertEquals(1,Regex(Regex.escape(closing)).findAll(rendered).count())
        assertEquals(listOf(opening,"Visitors should use the west entrance.","An accessibility guide is available from the project office.","$shared\n\n$closing"),fusion.segments.map {it.text})
        assertEquals(before,listOf(first,second).map {Gson().toJson(it)})
        assertEquals(1,CrossPosts.group(listOf(first,second),linked).size)
    }

    @Test fun distinctUnpunctuatedTitlesDoNotHideTheFirstSharedBodySentence() {
        val first=post(Platform.X,shared).copy(title="Workshop office update")
        val second=post(Platform.BLUESKY,shared).copy(title="Studio accessibility information")
        val fusion=CrossPostFusion.compose(listOf(first,second))!!
        assertEquals(shared,fusion.segments.single {it.shared}.text)
        assertEquals(listOf("Workshop office update","Studio accessibility information",shared),fusion.segments.map {it.text})
    }

    @Test fun multipleShortSharedSentencesHaveCandidateHintsAndACollectiveSubstantialOverlapGate() {
        val common=listOf("Our public workshop welcomes every local volunteer this weekend.","The complete materials inventory remains available for everyone to inspect.","Please read the detailed safety instructions before arriving on site.")
        val first=post(Platform.X,common.joinToString("\n\n"))
        val second=post(Platform.BLUESKY,common.joinToString("\n\nA translator will be present.\n\n"))
        // No continuous 20-word span exists. Their ordered union is substantial.
        assertTrue(CrossPostFusion.matches(first,second))
        assertTrue(CrossPostFusion.candidateAnchors(first).intersect(CrossPostFusion.candidateAnchors(second)).isNotEmpty())
        val fusion=CrossPostFusion.compose(listOf(first,second))!!
        val rendered=fusion.segments.joinToString("\n"){it.text}
        common.forEach {assertEquals(1,Regex(Regex.escape(it)).findAll(rendered).count())}
        // Repeated same-source additions are intentionally retained, not mistaken
        // for extra editions of a shared sentence.
        assertEquals(2,Regex("A translator will be present\\.").findAll(rendered).count())
    }

    @Test fun aPassageSharedByOnlyTwoOfThreePlatformsAppearsOnceWithBothAttributions() {
        val extra="The project office will publish a separate illustrated accessibility guide for wheelchair users tomorrow morning."
        val posts=listOf(post(Platform.X,"$shared\n\n$extra"),post(Platform.BLUESKY,"$shared\n\n$extra"),post(Platform.THREADS,"$shared\n\nBring waterproof clothing."))
        val fusion=CrossPostFusion.compose(posts)!!
        assertEquals(shared,fusion.segments.single {it.shared}.text)
        assertEquals(FusionSegment(extra,setOf(Platform.X,Platform.BLUESKY),false,posts.take(2).map(CrossPosts::originalKey).toSet()),fusion.segments.single {it.text==extra})
        assertEquals(1,Regex(Regex.escape(extra)).findAll(fusion.segments.joinToString("\n"){it.text}).count())
        assertEquals(1,CrossPosts.group(posts,linked).size)
    }

    @Test fun factualEditsBetweenSharedPassagesRemainSeparateInsteadOfBecomingAnInventedHybrid() {
        val ending="The complete illustrated project report remains available for every resident to inspect and discuss."
        val first=post(Platform.X,"$shared\n\nPublic enrollment opens on Friday.\n\n$ending")
        for(claim in listOf("Public enrollment opens on Monday.","Public enrollment does not open on Friday.","Public enrollment opens for 20 residents on Friday.")) {
            val second=post(Platform.BLUESKY,"$shared\n\n$claim\n\n$ending")
            assertFalse(claim,CrossPostFusion.matches(first,second))
            assertNull(CrossPostFusion.compose(listOf(first,second)))
        }
    }

    @Test fun reorderingACommonPassageIsAmbiguousEvenWhenAllItsWordsArePresent() {
        val opening="The first phase of construction will begin after every safety inspection has been completed successfully."
        val ending="The second phase will require approval from the local planning committee before any work starts."
        val first=post(Platform.X,"$opening\n\n$shared\n\n$ending")
        val second=post(Platform.BLUESKY,"$ending\n\n$shared\n\n$opening")
        assertFalse(CrossPostFusion.matches(first,second))
        assertNull(CrossPostFusion.compose(listOf(first,second)))
    }

    @Test fun changedTimesAndShortNegatedClaimsNeverGetSplitIntoInventedClauses() {
        for((before,after) in listOf("At 8:00 the public workshop opens." to "At 9:00 the public workshop opens.","I met Dr. Adams." to "I met Dr. Baker.","Approved." to "Not approved.","20." to "21.")) {
            val first=post(Platform.X,"$shared\n\n$before")
            val second=post(Platform.BLUESKY,"$shared\n\n$after")
            assertFalse("$before / $after",CrossPostFusion.matches(first,second))
            assertNull(CrossPostFusion.compose(listOf(first,second)))
        }
    }

    @Test fun aLongSharedArticleUrlCannotSupplyTheSubstantialCaptionOverlap() {
        val destination="https://www.example.org/articles/"+(0..40).joinToString("-"){"differentTopic$it"}
        val first=post(Platform.X,"The workshop opens tomorrow.\n\n$destination").copy(links=listOf(destination))
        val second=post(Platform.BLUESKY,"The project has been cancelled.\n\n$destination").copy(links=listOf(destination))
        assertFalse(CrossPostFusion.matches(first,second))
        assertNull(CrossPostFusion.compose(listOf(first,second)))
    }

    @Test fun aShortenedLinkLabelNeedsOneExactSuppliedDestinationAndDisplaysTheFullOriginalUrl() {
        val destination="https://www.example.org/events/workshop-registration"
        val first=post(Platform.X,"$shared\n\nwww.example.org/events/workshop...").copy(links=listOf(destination))
        val second=post(Platform.BLUESKY,"$shared\n\n$destination").copy(links=listOf(destination))
        val fusion=CrossPostFusion.compose(listOf(first,second))!!
        assertEquals(listOf(shared,destination),fusion.segments.map {it.text})
        assertTrue(fusion.segments.all {it.shared})
        assertTrue(first.body.endsWith("workshop..."))
        val ambiguous=first.copy(links=listOf(destination,"https://www.example.org/events/workshop-instructions"))
        val ambiguousFusion=CrossPostFusion.compose(listOf(ambiguous,second))
        assertTrue(ambiguousFusion==null||ambiguousFusion.segments.any {it.text.contains("workshop...")&&!it.shared})
        val noEvidence=CrossPostFusion.compose(listOf(first.copy(links=emptyList()),second.copy(links=emptyList())))
        assertTrue(noEvidence==null||noEvidence.segments.any {it.text.contains("workshop...")&&!it.shared})
    }

    @Test fun explicitHttpAndHttpsLabelsNeverBecomeEquivalentThroughSuppliedHttpsMetadata() {
        val destination="https://www.example.org/events/workshop-registration"
        val second=post(Platform.BLUESKY,"$shared\n\n$destination").copy(links=listOf(destination))
        for(label in listOf(destination.replace("https://","http://"),"http://www.example.org/events/workshop...")) {
            val first=post(Platform.X,"$shared\n\n$label").copy(links=listOf(destination))
            assertFalse(label,CrossPostFusion.matches(first,second))
            assertNull(CrossPostFusion.compose(listOf(first,second)))
        }
        val sameScheme=post(Platform.X,"$shared\n\nHTTPS://www.example.org/events/workshop...").copy(links=listOf(destination))
        val fusion=CrossPostFusion.compose(listOf(sameScheme,second))!!
        assertEquals(listOf(shared,destination),fusion.segments.map {it.text})
    }

    @Test fun urlsAndDecimalClaimsAreNotBrokenIntoMisleadingSentenceFragments() {
        val first=post(Platform.X,"$shared\n\nThe measured result is 2.5 percent. See https://example.org/CaseSensitive/report.")
        val changedNumber=post(Platform.BLUESKY,first.body.replace("2.5","25"))
        val changedUrl=post(Platform.BLUESKY,first.body.replace("CaseSensitive","casesensitive"))
        assertFalse(CrossPostFusion.matches(first,changedNumber))
        assertFalse(CrossPostFusion.matches(first,changedUrl))
        val same=post(Platform.BLUESKY,first.body)
        assertEquals(first.body,CrossPostFusion.compose(listOf(first,same))!!.segments.single().text)
    }

    @Test fun completeEquivalentShortCaptionsComposeForPreservingEveryOriginalEdition() {
        for(caption in listOf("We really got flying cars before GTA 6","Honoring America's Brave First Responders: Postcard Edition 🇺🇸","Only in America.","NEXT STOP: KANSAS 🇺🇸")) {
            val posts=listOf(post(Platform.X,caption).copy(title=caption),post(Platform.BLUESKY,caption))
            val raw=posts.map {Gson().toJson(it)}
            val fusion=CrossPostFusion.compose(posts)!!
            assertEquals(listOf(FusionSegment(caption,setOf(Platform.X,Platform.BLUESKY),true,posts.map(CrossPosts::originalKey).toSet())),fusion.segments)
            assertEquals(raw,posts.map {Gson().toJson(it)})
            // Rendering an independently verified group does not lower the
            // substantial partial-caption matching/candidate thresholds.
            assertFalse(CrossPostFusion.matches(posts[0],posts[1]))
        }
    }

    @Test fun shortExactCompositionKeepsMeaningfulFlagsOperatorsDestinationsAndCompleteness() {
        val caption="Five careful volunteers approved this public workshop"
        val first=post(Platform.X,caption)
        for((a,b) in listOf(
            "$caption 🇺🇸" to "$caption 🇨🇦",
            "$caption >20" to "$caption <20",
            "$caption is approved" to "$caption is not approved",
            "$caption https://example.org/Path?item=Case" to "$caption https://example.org/path?item=Case",
            "$caption https://example.org/Path?item=Case" to "$caption https://example.org/Path?item=case",
            "$caption http://example.org/Path" to "$caption https://example.org/Path"
        ))assertNull("$a / $b",CrossPostFusion.compose(listOf(first.copy(body=a),post(Platform.BLUESKY,b))))
        for(body in listOf("$caption…","$caption...","read read read read read","one two https://example.org/path/that/has/many/distinct/words","@several.part.names @another.multiple.part.handle")) {
            assertNull(body,CrossPostFusion.compose(listOf(first.copy(body=body),post(Platform.BLUESKY,body))))
        }
        assertNull(CrossPostFusion.compose(listOf(first.copy(preview=true),post(Platform.BLUESKY,caption))))
    }

    @Test fun oversizedLowInformationAndSmallSharedFragmentsFailClosed() {
        val huge=post(Platform.X,shared.repeat(100));val other=post(Platform.BLUESKY,huge.body)
        assertFalse(CrossPostFusion.matches(huge,other));assertNull(CrossPostFusion.compose(listOf(huge,other)))
        assertEquals(2,CrossPosts.group(listOf(huge,other),linked).size)
        val repetitive="Community project notes. ".repeat(40)
        assertFalse(CrossPostFusion.matches(post(Platform.X,repetitive),post(Platform.BLUESKY,repetitive)))
        assertFalse(CrossPostFusion.matches(post(Platform.X,"A short shared slogan."),post(Platform.BLUESKY,"A short shared slogan. More details.")))
        val longContext=(0..100).joinToString(" ") {"detail$it"}
        assertFalse(CrossPostFusion.matches(post(Platform.X,"$longContext\n\n$shared"),post(Platform.BLUESKY,"$longContext different.\n\n$shared")))
    }
}
