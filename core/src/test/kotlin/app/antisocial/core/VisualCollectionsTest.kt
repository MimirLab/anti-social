/* AntiSocial, GPL-3.0-or-later. Public captions and local image observations, 2026-10-18. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class VisualCollectionsTest {
    private fun actual()=Gson().fromJson(javaClass.getResourceAsStream("/bessent-collection.json")!!.reader(),Array<Post>::class.java).toList()
    private fun groups(posts: List<Post>)=CrossPosts.group(posts,emptyList())
    private fun another(post: Post,id: String="2111840260455866726",body: String=post.body,time: Long=post.publishedAt!!+1_000)=post.copy(id="post:$id",canonicalUrl="https://x.com/USTreasury/status/$id",author="@USTreasury",body=body,title="RT by @WhiteHouse: $body",publishedAt=time)

    @Test fun actualBessentCoverageUsesOneCardAndRetainsBothCompleteDifferentStatements() {
        val posts=actual();val raw=posts.map {Gson().toJson(it)}
        val a=posts[0].media[0].visualFingerprint;val b=posts[1].media[0].visualFingerprint
        assertFalse(VisualFrames.same(a,b));assertTrue(VisualFrames.same(a,b,nearbyFrame=true))
        assertTrue(CrossPosts.relatedVisualUpdates(posts));assertTrue(CrossPosts.sameSourceRepeats(posts))
        val group=groups(posts.reversed()).single();assertEquals(posts.toSet(),group.posts.toSet())
        val fusion=CrossPostFusion.composeRelated(group.posts)!!;val text=fusion.segments.joinToString("\n"){it.text}
        for(phrase in listOf("Core inflation is down.","No one has been tougher on Russian sanctions than President Trump.","The Europeans have given Russia and Vladimir Putin"))assertEquals(phrase,1,Regex(Regex.escape(phrase)).findAll(text).count())
        assertEquals(2,group.posts.map {it.canonicalUrl}.distinct().size)
        assertEquals(raw,posts.map {Gson().toJson(it)})
    }

    @Test fun sharedPhrasingInsideDifferentlyAddressedSentencesAppearsOnce() {
        val base=actual().first();val common="energy costs affect households and small businesses across the entire country"
        val first=another(base,body=".@SecScottBessent: \"For consumers, $common so the full public briefing will be available tomorrow morning.\"")
        val second=another(base,"2111840260455866727",".@SecScottBessent: \"For employers, $common while the illustrated report will be distributed this evening.\"")
        assertTrue(CrossPosts.relatedVisualUpdates(listOf(first,second)))
        val fusion=CrossPostFusion.composeRelated(groups(listOf(first,second)).single().posts)!!
        val text=fusion.segments.joinToString("\n"){it.text}
        assertEquals(1,Regex(Regex.escape(common)).findAll(text).count())
        for(extra in listOf("For consumers,","For employers,","tomorrow morning","this evening"))assertTrue(extra,text.contains(extra))
        assertTrue(fusion.segments.any {it.shared&&it.text==common})
    }

    @Test fun aShortCommonCoreWithTheSamePictureWorksAcrossDifferentPlatforms() {
        val common="The repair workshop has published detailed instructions for replacing a laptop battery safely."
        val first=Post("100",Platform.X,"https://x.com/repair/status/100","repair","For the repair team: $common Additional volunteer tools will arrive on Friday.",publishedAt=1_800_000_000_000,media=listOf(Media("https://images.example/repair.jpg")))
        val second=Post("abc",Platform.BLUESKY,"https://bsky.app/profile/repair.example/post/abc","repair","For everyone working at home: $common The illustrated checklist is available tomorrow.",publishedAt=1_800_000_001_000,media=first.media)
        assertNull(CrossPostFusion.compose(listOf(first,second)))
        assertTrue(CrossPosts.sameContentCopies(listOf(first,second)))
        val fusion=CrossPostFusion.composeMedia(groups(listOf(first,second)).single().posts)!!
        val text=fusion.segments.joinToString("\n"){it.text}
        assertEquals(1,Regex(Regex.escape(common.removeSuffix("."))).findAll(text).count())
        assertTrue(text.contains("Friday"));assertTrue(text.contains("tomorrow"))
        assertEquals(2,groups(listOf(first,second.copy(media=emptyList()))).size)
    }

    @Test fun sameSpeakerAndPictureWithoutSharedSubjectWordingStaySeparate() {
        val first=actual().first();val other=another(first,body=".@SecScottBessent: \"Local school construction and volunteer gardening programs will receive illustrated instructions before the weekend starts in our neighborhood.\"")
        assertFalse(CrossPosts.relatedVisualUpdates(listOf(first,other)));assertEquals(2,groups(listOf(first,other)).size)
    }
    @Test fun aDifferentSpeakerMissingObservationOrUnrelatedFrameCannotUseTheCollectionRule() {
        val first=actual()[0];val next=actual()[1]
        for(other in listOf(next.copy(body=next.body.replace("SecScottBessent","OtherSpeaker"),title=""),next.copy(media=next.media.map {it.copy(visualFingerprint=null)}),next.copy(media=next.media.map {it.copy(visualFingerprint="invalid")}))) {
            assertFalse(CrossPosts.relatedVisualUpdates(listOf(first,other)));assertEquals(2,groups(listOf(first,other)).size)
        }
    }
    @Test fun correctionsNumbersAndOppositionCannotDisappearInsideACollection() {
        val base=actual().first();val caption=".@SecScottBessent: \"Energy costs affect households and small businesses across the entire country. The measured result is 20 and the public project is approved for use.\""
        val first=another(base,body=caption)
        for(body in listOf(caption.replace("20","21"),caption.replace("is approved","is not approved"),caption.replace("is approved","is rejected"),"This claim is wrong: $caption")) {
            val second=another(base,"2111840260455866727",body)
            assertFalse(CrossPosts.relatedVisualUpdates(listOf(first,second)));assertEquals(2,groups(listOf(first,second)).size)
        }
    }
    @Test fun visualCollectionsRequireEveryPairToFitTheSixHourWindow() {
        val base=actual().first();val first=another(base);val second=another(base,"2111840260455866727",time=first.publishedAt!!+5*60*60*1000)
        val third=another(base,"2111840260455866728",time=first.publishedAt!!+10*60*60*1000,body=actual()[1].body)
        assertFalse(CrossPosts.relatedVisualUpdates(listOf(first,second,third)))
        assertFalse(groups(listOf(first,second,third)).any {first in it.posts&&third in it.posts})
    }
    @Test fun malformedVisualObservationsFailClosedAndRoundTripAsOptionalCapturedData() {
        val first=actual().first();val fingerprint=first.media[0].visualFingerprint!!
        assertTrue(VisualFrames.valid(fingerprint));assertTrue(VisualFrames.valid(null))
        for(raw in listOf("","f9"+fingerprint.drop(2),fingerprint+"0","x".repeat(100_000)))assertFalse(VisualFrames.valid(raw))
        assertEquals(first,Gson().fromJson(Gson().toJson(first),Post::class.java))
    }
}
