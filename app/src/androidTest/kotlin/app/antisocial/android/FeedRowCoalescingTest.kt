/* anti social, GPL-3.0-or-later. Synthetic late-arrival reading controls. */
package app.antisocial.android

import app.antisocial.core.*
import org.junit.Assert.*
import org.junit.Test

class FeedRowCoalescingTest {
    @Test fun identicalDisplayedAndAdmittedEditionsHaveOneCoherentProjectionPerPreparation() {
        val rows=listOf(row(x,fb),row(ig));var calls=0
        val actual=projectFeedReadingRows(rows){calls++;it.copy(body="Projected: "+it.body)}
        assertEquals(3,calls)
        actual.forEach {(original,body,sources)->assertEquals(original.posts.map {it.copy(body="Projected: "+it.body)},body);assertSame(body,sources)}
        assertEquals(rows.map {it.posts},listOf(listOf(x,fb),listOf(ig)))
        val newer=projectFeedReadingRows(rows){it.copy(body="Later projection: "+it.body)}
        assertTrue(newer.all {it.second.all {p->p.body.startsWith("Later projection:")}})
    }
    @Test fun aLateCaptionOrMediaRevisionNeverBorrowsTheFrozenBodysProjection() {
        val changed=listOf(x.copy(body="A corrected public announcement."),x.copy(media=listOf(Media("https://media.example.org/new.jpg"))),x.copy(links=listOf("https://example.org/correction")))
        for(post in changed) {
            val row=FrozenFeedRow(key(x),listOf(key(x)),listOf(post),bodyPosts=listOf(x));var calls=0
            val projected=projectFeedReadingRows(listOf(row)){calls++;it.copy(title="Presented: "+it.title)}.single()
            assertEquals(2,calls);assertEquals(listOf(x.copy(title="Presented: "+x.title)),projected.second)
            assertEquals(listOf(post.copy(title="Presented: "+post.title)),projected.third)
        }
    }
    @Test fun preparationFailureCannotRetainAValueForTheNextProjection() {
        assertThrows(IllegalStateException::class.java){projectFeedReadingRows(listOf(row(x))){error("Obsolete preparation")}}
        val projected=projectFeedReadingRows(listOf(row(x))){it.copy(body="Fresh interpretation.")}.single()
        assertEquals("Fresh interpretation.",projected.second.single().body);assertSame(projected.second,projected.third)
    }
    private fun post(platform:Platform,id:String)=Post(id,platform,"https://example.test/$id","fixture","A complete public announcement.",publishedAt=1793000000000)
    private val x=post(Platform.X,"x");private val fb=post(Platform.FACEBOOK,"fb");private val ig=post(Platform.INSTAGRAM,"ig")
    private fun key(p:Post)=CrossPosts.originalKey(p)
    private fun row(vararg p:Post)=FrozenFeedRow(key(p.first()),p.map(::key),p.toList())
    @Test fun aLateComponentReplacesDuplicateCardsAndKeepsAllOriginals() {
        val rows=listOf(row(x),row(fb))
        val components=mapOf(key(x) to listOf(x,fb,ig),key(fb) to listOf(x,fb,ig))
        val merged=coalesceFeedRows(rows,components,emptySet(),::key)
        assertEquals(1,merged.size);assertEquals(key(x),merged.single().key)
        assertEquals(listOf(x,fb),merged.single().posts)
        assertEquals(listOf(x),merged.single().bodyPosts)
        assertEquals(rows.first().orderAt,merged.single().orderAt)
    }
    @Test fun anActiveLaterCardKeepsItsKeyAndBodyWhileTheEarlierDuplicateDisappears() {
        val rows=listOf(row(x),row(fb))
        val components=rows.associate {it.key to listOf(x,fb)}
        val merged=coalesceFeedRows(rows,components,setOf(key(fb)),::key)
        assertEquals(1,merged.size);assertEquals(key(fb),merged.single().key)
        assertEquals(listOf(fb),merged.single().bodyPosts)
        assertEquals(setOf(x,fb),merged.single().posts.toSet())
    }
    @Test fun partialOverlapsAndUnavailableEvidenceCannotBridgeAnnouncements() {
        val rows=listOf(row(x,fb),row(ig))
        val partial=mapOf(key(x) to listOf(x,fb),key(fb) to listOf(fb,ig),key(ig) to listOf(fb,ig))
        assertEquals(rows,coalesceFeedRows(rows,partial,emptySet(),::key))
        assertEquals(rows,coalesceFeedRows(rows,emptyMap(),emptySet(),::key))
    }
}
