/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class CopiedPostsTest {
    private val at=1_800_000_000_000L
    private val day=24*60*60*1000L
    private val caption="Our community workshop has completed another important software release. The public notes explain the careful preparation, current changes, remaining tasks, and practical next steps everyone can follow together."
    private val image=Media("https://images.example.org/workshop.jpg")
    private fun post(id: String,author: String,body: String=caption,platform: Platform=Platform.X,time: Long?=at,media: List<Media> = emptyList()): Post {
        val url=when(platform) {
            Platform.X->"https://x.com/$author/status/$id"
            Platform.BLUESKY->"https://bsky.app/profile/$author.example/post/$id"
            Platform.THREADS->"https://www.threads.com/@$author/post/$id"
            else->error("fixture")
        }
        return Post(id,platform,url,author,body,publishedAt=time,media=media)
    }
    private fun grouped(posts: List<Post>)=CrossPosts.group(posts,emptyList())

    @Test fun threeDifferentAccountsOnOnePlatformKeepAllOriginalsWithoutInventingAnOwner() {
        val originals=listOf(post("100","alice",time=at),post("101","bob",time=at+1_000),post("102","carol",time=at+2_000))
        val raw=originals.map {Gson().toJson(it)}
        assertTrue(CrossPosts.sameContentCopies(originals))
        assertFalse(CrossPosts.sameSourceRepeats(originals))
        val result=grouped(originals.reversed()).single()
        assertEquals(originals,result.posts)
        assertEquals(3,result.posts.mapNotNull(CrossPosts::originalAccount).distinct().size)
        val fusion=CrossPostFusion.compose(result.posts)!!
        assertEquals(listOf(caption),fusion.segments.map {it.text})
        assertEquals(originals.map(CrossPosts::originalKey).toSet(),fusion.segments.single().originalKeys)
        assertEquals(raw,originals.map {Gson().toJson(it)})
    }

    @Test fun aCopyCannotMoveReadNewsAheadOfANewerIndependentPost() {
        val original=post("100","alice")
        val independent=post("101","bob","A separate announcement about the garden opening tomorrow.",time=at+day)
        val copy=post("102","carol",time=at+20*day)
        val expected=listOf(listOf(independent),listOf(original,copy))
        listOf(listOf(original,independent,copy),listOf(copy,original,independent),listOf(independent,copy,original)).forEach {
            assertEquals(expected,grouped(it).map {group->group.posts})
        }
    }

    @Test fun originalEarliestChronologyAlsoAppliesToCrossPlatformOnlyCopies() {
        val original=post("100","alice",platform=Platform.BLUESKY)
        val copy=post("101","bob",platform=Platform.X,time=at+day)
        val other=post("102","carol","A different item between the original and the repost.",time=at+1_000)
        assertEquals(listOf(other,original),grouped(listOf(copy,other,original)).map {it.primary})
    }

    @Test fun periodAndExclamationFormattingDoNotTurnAnIdenticalStatementIntoTwoCards() {
        val first=post("100","alice")
        val second=post("101","bob",caption.replace("together.","together!"),Platform.BLUESKY,at+1_000)
        assertTrue(CrossPosts.sameContentCopies(listOf(first,second)))
        assertEquals(listOf(first,second),grouped(listOf(second,first)).single().posts)
    }

    @Test fun copiedContextFromTwoAccountsOnOnePlatformKeepsExactAttributionAboveAndBelow() {
        val first=post("100","alice","Workshop update:\n\n$caption\n\nDoors open on Friday.",media=listOf(image))
        val second=post("101","bob","Studio note:\n\n$caption\n\nBring waterproof clothing.",time=at+1_000,media=listOf(image))
        val third=post("102","carol",caption,Platform.BLUESKY,at+2_000,listOf(image))
        assertTrue(CrossPosts.sameContentCopies(listOf(first,second,third)))
        val group=grouped(listOf(third,second,first)).single()
        val fusion=CrossPostFusion.compose(group.posts)!!
        assertEquals(listOf(caption),fusion.segments.filter {it.shared}.map {it.text})
        assertEquals(setOf(CrossPosts.originalKey(first)),fusion.segments.single {it.text=="Doors open on Friday."}.originalKeys)
        assertEquals(setOf(CrossPosts.originalKey(second)),fusion.segments.single {it.text=="Bring waterproof clothing."}.originalKeys)
        assertEquals(setOf(Platform.X),fusion.segments.single {it.text=="Bring waterproof clothing."}.platforms)
        assertEquals(group.posts.map(CrossPosts::originalKey).toSet(),fusion.segments.single {it.shared}.originalKeys)
        assertEquals(3,group.posts.map {it.canonicalUrl}.distinct().size)
    }

    @Test fun aPassageSharedByTwoSamePlatformAccountsCarriesBothOriginalKeys() {
        val extra="The complete illustrated instructions will be available tomorrow."
        val originals=listOf(post("100","alice","$caption\n\n$extra",media=listOf(image)),post("101","bob","$caption\n\n$extra",media=listOf(image)),post("102","carol",caption,Platform.BLUESKY,media=listOf(image)))
        assertTrue(CrossPosts.sameContentCopies(originals))
        val fusion=CrossPostFusion.compose(grouped(originals).single().posts)!!
        val segment=fusion.segments.single {it.text==extra}
        assertFalse(segment.shared)
        assertEquals(setOf(Platform.X),segment.platforms)
        assertEquals(originals.take(2).map(CrossPosts::originalKey).toSet(),segment.originalKeys)
        assertEquals(1,Regex(Regex.escape(extra)).findAll(fusion.segments.joinToString("\n"){it.text}).count())
    }

    @Test fun captionOnlyCopiesUseACompleteLinkThirtyDayWindow() {
        val a=post("100","alice")
        val b=post("101","bob",time=at+20*day)
        val c=post("102","carol",time=at+40*day)
        assertTrue(CrossPosts.sameContentCopies(listOf(a,b)))
        assertTrue(CrossPosts.sameContentCopies(listOf(b,c)))
        assertFalse(CrossPosts.sameContentCopies(listOf(a,b,c)))
        val groups=grouped(listOf(c,b,a))
        assertEquals(2,groups.size)
        assertEquals(listOf(a,b),groups.single {a in it.posts}.posts)
        assertFalse(groups.any {a in it.posts&&c in it.posts})
    }

    @Test fun exactSubstantialCopiesWithRealMediaRemainCopiesAcrossLongOrUnknownDates() {
        val first=post("100","alice",media=listOf(image))
        for(date in listOf(at+400*day,null,0L)) {
            val second=post("101","bob",platform=Platform.BLUESKY,time=date,media=listOf(image))
            assertTrue(CrossPosts.sameContentCopies(listOf(first,second)))
            assertEquals(listOf(first,second),grouped(listOf(second,first)).single().posts)
        }
        assertFalse(CrossPosts.sameContentCopies(listOf(first.copy(media=emptyList()),post("101","bob",time=null))))
    }

    @Test fun stableVideoClipIdentityCanCorroborateAnOldExactCopyWithoutMatchingCdnTokens() {
        val first=post("100","alice",media=listOf(Media("https://pbs.twimg.com/amplify_video_thumb/123456789/img/first.jpg")))
        val second=post("101","bob",time=null,media=listOf(Media("https://video.twimg.com/amplify_video/123456789/vid/avc1/720x720/next.mp4?tag=1","video")))
        assertTrue(CrossPosts.sameContentCopies(listOf(first,second)))
        assertEquals(1,grouped(listOf(second,first)).size)
        assertFalse(CrossPosts.sameContentCopies(listOf(first,second.copy(body="A separate announcement about school construction, transport improvements, and the summer volunteer program in our community."))))
    }

    @Test fun aRealOriginalReferenceCanProveAnExactOldCopyButAFakeHostCannot() {
        val first=post("100","alice")
        val second=post("101","bob",time=null).copy(links=listOf(first.canonicalUrl))
        assertTrue(CrossPosts.sameContentCopies(listOf(first,second)))
        assertEquals(1,grouped(listOf(second,first)).size)
        val fake=second.copy(links=listOf("https://attacker.example/alice/status/100"))
        assertFalse(CrossPosts.sameContentCopies(listOf(first,fake)))
        assertEquals(2,grouped(listOf(first,fake)).size)
    }

    @Test fun aSpecificCompleteShortMemeNeedsRealImageEvidenceAcrossDifferentAccounts() {
        val text="We really got flying cars before GTA 6"
        val first=post("100","alice",text,media=listOf(image))
        val second=post("101","bob",text,media=listOf(Media("https://other.example.org/copy.jpg")))
        assertFalse(CrossPosts.sameContentCopies(listOf(first,second)))
        assertTrue(CrossPosts.sameContentCopies(listOf(first,second),sameMedia={_,_->true}))
        assertEquals(1,CrossPosts.group(listOf(first,second),emptyList(),sameMedia={_,_->true}).size)
        assertEquals(2,CrossPosts.group(listOf(first.copy(body="Winning for YOU"),second.copy(body="Winning for YOU")),emptyList(),sameMedia={_,_->true}).size)
    }

    @Test fun aSharedAvatarOrArticleWithoutSubstantialCopiedTextDoesNotCreateACopy() {
        val text="The next community meeting happens at our usual venue."
        val first=post("100","alice",text,media=listOf(image)).copy(avatar=image.url)
        val second=post("101","bob",text,media=listOf(image)).copy(avatar=image.url)
        assertFalse(CrossPosts.sameContentCopies(listOf(first,second)))
        assertEquals(2,grouped(listOf(first,second)).size)
        val article="https://example.org/public-report"
        assertFalse(CrossPosts.sameContentCopies(listOf(first.copy(media=emptyList(),links=listOf(article)),second.copy(media=emptyList(),links=listOf(article)))))
    }

    @Test fun sameImageAndTopicDoNotMergeDifferentClaimsOrIndependentCommentary() {
        val first=post("100","alice",media=listOf(image))
        val variations=listOf(
            caption.replace("remaining tasks","rejected tasks"),
            "This claim is wrong:\n\n$caption",
            "I disagree with this statement:\n\n$caption",
            "Fact-check:\n\n$caption",
            "$caption\n\nThis is false.",
            "$caption\n\n"+("Independent analysis of the workshop, funding, safety, education, transport and local planning. ".repeat(5)),
            "A different community workshop has postponed its next software release until the independent safety review is complete."
        )
        variations.forEach {body->
            val second=post("101","bob",body,media=listOf(image))
            assertFalse(body,CrossPosts.sameContentCopies(listOf(first,second)))
            assertEquals(body,2,grouped(listOf(first,second)).size)
        }
    }

    @Test fun aVerifiedAuthorsOwnCorrectionDoesNotCollapseIntoTheirEarlierPost() {
        val first=post("100","alice",media=listOf(image))
        val correction=post("101","alice","This claim is wrong:\n\n$caption",time=at+1_000,media=listOf(image))
        assertEquals(CrossPosts.originalAccount(first),CrossPosts.originalAccount(correction))
        assertTrue("The substantial quoted claim itself still aligns",CrossPostFusion.matches(first,correction))
        assertFalse(CrossPostFusion.matchesWithContext(first,correction))
        assertFalse(CrossPosts.sameSourceRepeats(listOf(first,correction)))
        assertFalse(CrossPosts.sameContentCopies(listOf(first,correction)))
        assertEquals(2,grouped(listOf(first,correction)).size)
    }

    @Test fun explicitlyLinkedAccountsCannotHideAnUnmatchedCorrectionPrefix() {
        val first=post("100","alice",media=listOf(image))
        val correction=post("101","bob","This statement is false:\n\n$caption",Platform.BLUESKY,at+1_000,listOf(image))
        val follows=listOf(
            Follow("x",Target(Platform.X,TargetKind.PROFILE,"alice","https://x.com/alice"),"Alice",personId="same-creator"),
            Follow("blue",Target(Platform.BLUESKY,TargetKind.PROFILE,"bob.example","https://bsky.app/profile/bob.example"),"Bob",personId="same-creator"),
        )
        assertTrue(CrossPostFusion.matches(first,correction))
        assertFalse(CrossPostFusion.matchesWithContext(first,correction))
        assertEquals(2,CrossPosts.group(listOf(first,correction),follows).size)
    }

    @Test fun genuinelyCopiedFactCheckLanguageRemainsSharedRatherThanBecomingAFalseRebuttal() {
        val shared="Fact-check: This claim is false. $caption"
        val first=post("100","alice","Workshop update:\n\n$shared\n\nDoors open on Friday.",media=listOf(image))
        val second=post("101","bob","Studio note:\n\n$shared\n\nBring waterproof clothing.",Platform.BLUESKY,at+1_000,listOf(image))
        assertTrue(CrossPostFusion.matchesWithContext(first,second))
        assertTrue(CrossPosts.sameContentCopies(listOf(first,second)))
        val group=grouped(listOf(first,second)).single()
        assertEquals(listOf(shared),CrossPostFusion.compose(group.posts)!!.segments.filter {it.shared}.map {it.text})
        assertEquals(1,grouped(listOf(first.copy(body=shared),second.copy(body=shared))).size)
    }

    @Test fun changedClaimsSymbolsQuestionsAndCaseSensitiveDestinationsNeverUseTheLegacyBackdoor() {
        for(platform in listOf(Platform.X,Platform.BLUESKY)) {
            val first=post("100","alice","$caption The measured result is 20 and the plan is approved.",media=listOf(image))
            for(text in listOf(first.body.replace("20","21"),first.body.replace("is approved","is not approved"),first.body.replace("is approved","is rejected"))) {
                val second=post("101","bob",text,platform,media=listOf(image))
                assertFalse(CrossPosts.sameContentCopies(listOf(first,second)))
                assertEquals(2,grouped(listOf(first,second)).size)
            }
            for((left,right) in listOf("$caption Is the plan approved?" to "$caption Is the plan approved!","$caption 🇺🇸" to "$caption 🇨🇦","$caption The result is -20." to "$caption The result is 20.")) {
                assertEquals(2,grouped(listOf(first.copy(body=left),post("101","bob",right,platform,media=listOf(image)))).size)
            }
            val linked=post("101","bob",caption,platform,media=listOf(image))
            for((a,b) in listOf("Approved" to "approved","item?x=A" to "item?x=a")) {
                assertEquals(2,grouped(listOf(first.copy(body=caption,links=listOf("https://example.org/$a")),linked.copy(links=listOf("https://example.org/$b")))).size)
            }
        }
    }

    @Test fun repliesPreviewsTruncationsAndSpoofedOriginalHostsAreNotCopies() {
        val first=post("100","alice",media=listOf(image))
        val second=post("101","bob",media=listOf(image))
        for(invalid in listOf(second.copy(parentId="root"),second.copy(preview=true),second.copy(body="$caption…"),second.copy(canonicalUrl="https://attacker.example/bob/status/101"))) {
            assertFalse(CrossPosts.sameContentCopies(listOf(first,invalid)))
            assertEquals(2,grouped(listOf(first,invalid)).size)
        }
    }

    @Test fun contextualCopiesStillRequireKnownCloseDatesAndGenuineMediaOrReference() {
        val first=post("100","alice","Workshop update:\n\n$caption",media=listOf(image))
        val second=post("101","bob","Studio note:\n\n$caption",media=listOf(image))
        assertTrue(CrossPosts.sameContentCopies(listOf(first,second)))
        for(invalid in listOf(second.copy(publishedAt=null),second.copy(publishedAt=at+CrossPosts.MAX_REPEAT_WINDOW+1),second.copy(media=emptyList()))) {
            assertFalse(CrossPosts.sameContentCopies(listOf(first,invalid)))
            assertEquals(2,grouped(listOf(first,invalid)).size)
        }
    }

    @Test fun copyLimitsRetainEveryOriginalAndRejectUnboundedVerifierInput() {
        val originals=(0..CrossPosts.MAX_EDITIONS).map {post((100+it).toString(),"author$it",time=at+it)}
        assertFalse(CrossPosts.sameContentCopies(originals))
        assertFalse(CrossPosts.sameContentCopies(listOf(originals.first(),originals.first())))
        val groups=grouped(originals.reversed())
        assertEquals(2,groups.size)
        assertTrue(groups.all {it.posts.size<=CrossPosts.MAX_EDITIONS})
        assertEquals(originals.map(CrossPosts::originalKey).toSet(),groups.flatMap {it.posts}.map(CrossPosts::originalKey).toSet())
        assertEquals(originals.size,groups.sumOf {it.posts.size})
    }
}
