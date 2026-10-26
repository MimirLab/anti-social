package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProductPolishRegressionTest {
    private data class Cases(val posts:List<Post>,val follows:List<Follow>,val memberships:Map<String,Set<String>>)
    private fun cases()=Gson().fromJson(javaClass.getResourceAsStream("/product-polish-posts.json")!!.reader(),Cases::class.java)
    @Test fun retainedWhiteHouseEditionsCollectDespiteShortGeneratedHeadingsAndFacebookPermalinkAliases() {
        val c=cases();val posts=c.posts.filter {it.body.startsWith("Saving American lives")}
        val groups=CrossPosts.group(posts,c.follows,c.memberships)

        assertEquals(4,posts.size);assertEquals("All equivalent editions must occupy one card",1,groups.size)
        val fusion=CrossPostFusion.compose(groups.single().posts) ?: CrossPostFusion.composeMedia(groups.single().posts)
        assertNotNull(fusion);assertEquals(1,fusion!!.segments.count {it.text.contains("Saving American lives")})
    }
    @Test fun retainedNasaCaptionAlignsAccountMentionWithItsProperNounWithoutDiscardingAnyEdition() {
        val c=cases();val posts=c.posts.filter {it.body.startsWith("That's our jam")}
        val groups=CrossPosts.group(posts,c.follows,c.memberships)

        assertEquals(3,posts.size);assertEquals(1,groups.size)
        assertEquals(posts.map {it.canonicalUrl}.toSet(),groups.single().posts.map {it.canonicalUrl}.toSet())
        assertNotNull(CrossPostFusion.compose(groups.single().posts))
    }
    @Test fun nameSpellingCannotEraseChangedNumbersNegationDestinationsOrIdentity() {
        val posts=cases().posts.filter {it.body.startsWith("That's our jam")}
        val x=posts.first {it.platform==Platform.X};val fb=posts.first {it.platform==Platform.FACEBOOK}
        assertTrue(CaptionAliases.equivalent(x,fb))
        for(changed in listOf(fb.copy(body=fb.body.replace("Chandra","Different")),fb.copy(body="Not "+fb.body),fb.copy(body=fb.body+" https://different.example/"),fb.copy(body=fb.body.replace("two galaxy","three galaxy"))))
            assertFalse(changed.body,CaptionAliases.equivalent(x,changed))
        assertEquals(2,CrossPosts.group(listOf(x,fb.copy(publishedAt=x.publishedAt!!+24*60*60*1000)),emptyList()).size)
    }
    @Test fun districtFestivalListingsMergeAcrossParticipatingVenuesAndKeepFreshDetails() {
        val events=Gson().fromJson(javaClass.getResourceAsStream("/product-polish-events.json")!!.reader(),Array<LocalEvent>::class.java).toList()
        val a=events[0];val b=events[1].copy(checkedAt=a.checkedAt+1000,price="Free",modifiedAt=a.checkedAt+1000)
        for(order in listOf(listOf(a,b),listOf(b,a))) {
            val result=LocalDiscoveryMerge.events(order).single()
            assertEquals(2,result.urls.size);assertEquals("Free",result.price)
        }
        assertTrue(LocalEventIdentity.same(a,b.copy(start=LocalEventTime("2026-10-24T16:30:00-04:00","America/New_York"))))
        for(other in listOf(b.copy(locality="Orlando"),b.copy(address="1423 Different Ave, Sampleton"),b.copy(start=LocalEventTime("2026-10-25T16:00:00-04:00")),b.copy(end=LocalEventTime("2026-10-24T21:00:00-04:00")),b.copy(description="Different entertainment"),b.copy(title=b.title+" - Spooky Hayride/Cemetery Tours")))
            assertFalse("Distinct occurrence: $other",LocalEventIdentity.same(a,other))
        assertFalse(LocalEventIdentity.same(a.copy(title="Storytime",venue="District library"),b.copy(title="Storytime")))
    }
}
