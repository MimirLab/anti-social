/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class SameSourceRepeatsTest {
    private val at=1_800_014_400_000L
    private val caption="Our community workshop has completed another important software release. The public notes explain the careful preparation, current changes, remaining tasks, and practical next steps everyone can follow together."
    private val border="We didn't just talk about repairing the workshop — we actually did it."
    private val longer=".@CrewLead: \"$border We didn't just talk about inspecting every toolbox — we actually did it. We didn't just talk about publishing safety instructions for every volunteer — we actually did it. I do think you have to show the repaired equipment and explain each remaining task, but I also think you have to make a clear promise to the people who use the shared workshop, with practical instructions that everyone can follow.\""
    private val shorter="“$border” - @CrewLead 🎯"
    private val thumb="https://pbs.twimg.com/amplify_video_thumb/710001/img/workshop.jpg"
    private val source=Source(Platform.X,"nitter","https://nitter.fixture.invalid")
    private fun post(id: String="100",body: String=caption,author: String="Workshop",time: Long?=at,media: List<Media> = emptyList(),title: String="",provenance: Provenance?=null)=
        Post(id,Platform.X,"https://x.com/$author/status/$id","@$author",body,title,publishedAt=time,media=media,provenance=provenance)
    private fun provenance(curator: String="Workshop")=Provenance(source,at+100_000,"${source.root}/$curator/rss")
    private fun curatedPair(): List<Post> = listOf(
        post("810001","$longer Video","RepairCrew",at,listOf(Media(thumb)),"RT by @Workshop: $longer",provenance()),
        post("810002","$shorter Video",time=at+2_851_000,media=listOf(Media(thumb)),title=shorter,provenance=provenance())
    )

    @Test fun curatedWorkshopVideoExcerptGroupsWithoutSwallowingOtherRepairTopics() {
        val pair=curatedPair()
        val workers=post("810004","The tool crew completed its separate inspection of the shared equipment and published a detailed maintenance checklist for next month.","ToolTeam",at+4_622_000,listOf(Media("https://pbs.twimg.com/amplify_video_thumb/710002/img/tools.jpg")),provenance=provenance())
        val accounts=post("810005","The garden crew published a different plan for the raised beds, seed storage, greenhouse repairs and next season's volunteer training sessions.","CrewLead",at+59_414_000,listOf(Media("https://pbs.twimg.com/amplify_video_thumb/710003/img/garden.jpg")),provenance=provenance())
        val before=(pair+workers+accounts).map {Gson().toJson(it)}
        assertTrue(CrossPosts.sameSourceRepeats(pair))
        val groups=CrossPosts.group(pair+workers+accounts,emptyList())
        assertEquals(3,groups.size)
        val repeated=groups.single {it.posts.size==2}
        assertEquals(pair.toSet(),repeated.posts.toSet());assertEquals(pair.first(),repeated.primary)
        assertEquals("Reposted by @Workshop",PostPresentation.text(repeated.primary).attribution)
        assertNull("The literal excerpt must not weaken general 20-word fusion.",CrossPostFusion.compose(pair))
        assertEquals(before,(pair+workers+accounts).map {Gson().toJson(it)})
    }

    @Test fun aThirdOriginalIsRetainedAndDoesNotRebumpTheOldestPost() {
        val pair=curatedPair();val third=pair[1].copy(id="810003",canonicalUrl="https://x.com/Workshop/status/810003",publishedAt=at+3_000_000,replyCount=91)
        val other=post("101","A completely separate update about maintenance.",time=at+1_000_000)
        val groups=CrossPosts.group(listOf(third,other)+pair.reversed(),emptyList())
        assertEquals(listOf(other,pair.first()),groups.map {it.primary})
        val repeated=groups.last();assertEquals(3,repeated.posts.size);assertEquals((pair+third).toSet(),repeated.posts.toSet())
        assertEquals(third,CrossPosts.representative(repeated.posts))
        assertEquals(third.canonicalUrl,CrossPosts.representative(repeated.posts)!!.canonicalUrl)
    }

    @Test fun aSharedCuratorRequiresItsExactFeedOriginPathAndValidatedRepostAttribution() {
        val (long,short)=curatedPair()
        val bad=listOf(
            long.copy(provenance=long.provenance!!.copy(finalUrl="https://unrelated.example/Workshop/rss")),
            long.copy(provenance=long.provenance!!.copy(finalUrl="${source.root}/someone/Workshop/rss")),
            long.copy(provenance=long.provenance!!.copy(finalUrl="${source.root}/Workshop/rss?other=1")),
            long.copy(provenance=long.provenance!!.copy(source=source.copy(role=SourceRole.CACHE))),
            long.copy(provenance=long.provenance!!.copy(source=source.copy(frontend="unknown"))),
            long.copy(title=longer),
            long.copy(title="RT by @OtherPerson: $longer"),
            long.copy(title="RT by @Workshop: A heading that does not echo this caption.")
        )
        bad.forEach {assertFalse(CrossPosts.sameSourceRepeats(listOf(it,short)));assertEquals(2,CrossPosts.group(listOf(it,short),emptyList()).size)}
    }

    @Test fun genericImagesAndQuotedOverlapAloneDoNotEstablishARepeat() {
        val pair=curatedPair()
        assertFalse(CrossPosts.sameSourceRepeats(pair.map {it.copy(media=listOf(Media("https://images.example/common-image.jpg")))}))
        assertFalse(CrossPosts.sameSourceRepeats(pair.map {it.copy(provenance=null,title=PostPresentation.content(it))}))
        // These are text-only controls. A qualified provider clip is a
        // separate collection route whose full alternative claims must survive.
        val generic=pair.map {it.copy(media=listOf(Media("https://images.example/common-cover.jpg")))}
        val changed=generic[1].copy(body="“We didn't just talk about repairing the workshop — we did not do it.” - @CrewLead 🎯",title="")
        assertFalse(CrossPosts.sameSourceRepeats(listOf(generic[0],changed)))
        val editorial=generic[1].copy(body="This claim is wrong: $shorter",title="")
        assertFalse(CrossPosts.sameSourceRepeats(listOf(generic[0],editorial)))
    }

    @Test fun anUnqualifiedSharedCoverWithACompletelyDifferentCaptionRemainsItsOwnPost() {
        val (long,short)=curatedPair().map {it.copy(media=listOf(Media("https://images.example/common-cover.jpg")))}
        val different=short.copy(body="A separate detailed announcement about local school construction, public transport improvements, and the summer volunteer program in our community.",title="")
        assertFalse(CrossPosts.sameSourceRepeats(listOf(long,different)))
        assertEquals(2,CrossPosts.group(listOf(long,different),emptyList()).size)
    }

    @Test fun strongSameAccountCaptionDoesNotDependOnCdnTokensOrCurrentSubscriptions() {
        val first=post(media=listOf(Media("https://video.example/one.mp4?token=first","video")))
        val next=post("101",time=at+10_000,media=listOf(Media("https://different.example/two.mp4?token=next","video")))
        assertTrue(CrossPosts.sameSourceRepeats(listOf(first,next)))
        assertEquals(listOf(first,next),CrossPosts.group(listOf(next,first),emptyList()).single().posts)
    }

    @Test fun changedNumbersNegationOrMeaningDoNotBecomeTheSameCaption() {
        val initial=post(body="$caption The measured result is 20 and the plan is approved.")
        listOf(initial.body.replace("20","21"),initial.body.replace("is approved","is not approved"),initial.body.replace("is approved","is rejected")).forEach {body->
            val next=post("101",body,time=at+1_000)
            assertFalse(CrossPosts.sameSourceRepeats(listOf(initial,next)))
            assertEquals(2,CrossPosts.group(listOf(initial,next),emptyList()).size)
        }
    }

    @Test fun sameAccountFullFusionKeepsCommonTextOnceAndAllDistinctContext() {
        val first=post(body="For the workshop team:\n\n$caption\n\nDoors open on Friday.")
        val next=post("101","A message from the project office:\n\n$caption\n\nThe illustrated instructions follow tomorrow.",time=at+1_000)
        assertTrue(CrossPosts.sameSourceRepeats(listOf(first,next)))
        val fusion=CrossPostFusion.compose(listOf(first,next))!!
        assertEquals(listOf(caption),fusion.segments.filter {it.shared}.map {it.text})
        assertTrue(fusion.segments.any {it.text=="Doors open on Friday."})
        assertTrue(fusion.segments.any {it.text=="The illustrated instructions follow tomorrow."})
        assertEquals(1,CrossPosts.group(listOf(first,next),emptyList()).size)
    }

    @Test fun contentCopiesNeverReplaceOriginalAccountProof() {
        val first=post();val next=post("101",author="OtherPerson",time=at+1_000).copy(author=first.author)
        val follows=listOf("Workshop","OtherPerson").map {name->Follow(name,Target(Platform.X,TargetKind.PROFILE,name,"https://x.com/$name"),"Same name",personId="same-person")}
        assertFalse(CrossPosts.sameSourceRepeats(listOf(first,next)))
        assertTrue(CrossPosts.sameContentCopies(listOf(first,next)))
        assertNotEquals(CrossPosts.originalAccount(first),CrossPosts.originalAccount(next))
        assertEquals(setOf(first,next),CrossPosts.group(listOf(first,next),follows).single().posts.toSet())
        val opaque=first.copy(canonicalUrl="https://x.com/i/status/100")
        assertNull(CrossPosts.originalAccount(opaque));assertFalse(CrossPosts.sameSourceRepeats(listOf(opaque,opaque.copy(id="101",canonicalUrl="https://x.com/i/status/101"))))
    }

    @Test fun datesRepliesPreviewsAndLongIntervalsStaySeparate() {
        val first=post();val next=post("101",time=at+1_000)
        listOf(next.copy(publishedAt=null),next.copy(publishedAt=0),next.copy(parentId="parent"),next.copy(preview=true),next.copy(publishedAt=at+CrossPosts.MAX_COPY_WINDOW+1),next.copy(body=caption+"…")).forEach {invalid->
            assertFalse(CrossPosts.sameSourceRepeats(listOf(first,invalid)))
            assertEquals(2,CrossPosts.group(listOf(first,invalid),emptyList()).size)
        }
    }

    @Test fun timeChainsDoNotTransitivelyCollapseUnrelatedOldAndNewUpdates() {
        val day=24*60*60*1000L
        val a=post();val b=post("101",time=at+20*day);val c=post("102",time=at+40*day)
        val groups=CrossPosts.group(listOf(c,b,a),emptyList())
        assertEquals(2,groups.size);assertEquals(c,groups.first().primary)
        assertEquals(listOf(a,b),groups.last().posts)
        assertFalse(CrossPosts.sameSourceRepeats(listOf(a,b,c)))
    }

    @Test fun repeatAndFusionLimitsKeepEveryOriginalRatherThanDroppingOverflow() {
        val posts=(0..CrossPosts.MAX_EDITIONS).map {post((100+it).toString(),time=at+it)}
        val groups=CrossPosts.group(posts.reversed(),emptyList())
        assertEquals(2,groups.size);assertTrue(groups.all {it.posts.size<=CrossPosts.MAX_EDITIONS})
        assertEquals(posts.toSet(),groups.flatMap {it.posts}.toSet())
        assertFalse(CrossPosts.sameSourceRepeats(posts));assertNull(CrossPostFusion.compose(posts))
    }

    @Test fun representativeUsesSuppliedReplyCountsAndDeterministicDatesNeverScore() {
        val first=post().copy(replyCount=null,score=Long.MAX_VALUE)
        val knownZero=post("101",time=at+1_000).copy(replyCount=0)
        val later=post("102",time=at+2_000).copy(replyCount=44,score=0)
        val earlier=post("103",time=at+1_000).copy(replyCount=44,score=-9)
        assertEquals(earlier,CrossPosts.representative(listOf(first,later,knownZero,earlier)))
        assertEquals(knownZero,CrossPosts.representative(listOf(first,knownZero)))
        assertEquals(first,CrossPosts.representative(listOf(first,knownZero.copy(replyCount=-1))))
        assertNull(CrossPosts.representative(listOf(first),Platform.BLUESKY))
    }

    @Test fun originalAccountNamesAreScopedToPlatformAndFederatedServer() {
        val a=Post("1",Platform.MASTODON,"https://a.example/@alice/1","Alice",caption,publishedAt=at)
        val b=a.copy(id="2",canonicalUrl="https://b.example/@alice/2",publishedAt=at+1_000)
        assertNotEquals(CrossPosts.originalAccount(a),CrossPosts.originalAccount(b))
        assertFalse(CrossPosts.sameSourceRepeats(listOf(a,b)))
        val same=b.copy(canonicalUrl="https://a.example/users/alice/statuses/2")
        assertEquals(CrossPosts.originalAccount(a),CrossPosts.originalAccount(same))
        assertTrue(CrossPosts.sameSourceRepeats(listOf(a,same)))
    }

    @Test fun incomingOrderDoesNotChangePrimaryGroupOrderOrOriginalKeys() {
        val first=post();val next=post("101",time=at+1_000);val other=post("102","Another topic with its own complete caption.",time=at+500)
        val expected=CrossPosts.group(listOf(first,next,other),emptyList()).map {it.posts.map(CrossPosts::originalKey)}
        listOf(listOf(other,next,first),listOf(next,first,other),listOf(next,other,first)).forEach {order->assertEquals(expected,CrossPosts.group(order,emptyList()).map {it.posts.map(CrossPosts::originalKey)})}
    }
}
