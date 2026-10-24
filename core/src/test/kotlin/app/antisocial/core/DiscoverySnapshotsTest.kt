package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class DiscoverySnapshotsTest {
    private val seed=Target(Platform.X,TargetKind.PROFILE,"seed","https://x.com/seed")
    private fun card(id:String,at:Long,name:String=id):AdjacentCard {
        val target=seed.copy(identity=id,canonicalUrl="https://x.com/$id")
        return AdjacentCard(id,Profile(target,name),listOf(AdjacentEvidence(AdjacentKind.FEATURED,seed,seed.canonicalUrl,at)),100.0)
    }
    @Test fun partialRefreshAddsNewResultsWithoutLosingEarlierResultsOrTheirDates() {
        val a=card("first",1000);val b=card("second",1000)
        val value=DiscoverySnapshots.merge(AdjacentSnapshot(listOf(a,b)),AdjacentSnapshot(listOf(card("second",2000,"Updated name"),card("third",2000)),hasMore=true,complete=true),2000)
        assertEquals(listOf("first","second","third"),value.cards.map {it.key})
        assertEquals(1000L,value.cards.first().evidence.single().checkedAt)
        assertEquals("Updated name",value.cards[1].profile.name)
        assertEquals(1,value.cards[1].evidence.size)
        assertTrue(value.hasMore)
    }
    @Test fun emptyRefreshDoesNotRenewOldEvidenceIndefinitely() {
        val original=AdjacentSnapshot(listOf(card("first",1000)),complete=true)
        val retained=DiscoverySnapshots.merge(original,AdjacentSnapshot(complete=true),2000)
        assertEquals(1000L,retained.cards.single().evidence.single().checkedAt)
        val expired=DiscoverySnapshots.merge(retained,AdjacentSnapshot(complete=true),1001+DiscoverySnapshots.RETAIN_MS)
        assertTrue(expired.cards.isEmpty())
    }
    @Test fun distinctEvidenceSurvivesWhileAnOlderRefreshCannotReplaceANewerClaim() {
        val first=card("first",2000)
        val older=card("first",1000)
        val extra=older.evidence.single().copy(kind=AdjacentKind.FOLLOWS)
        val value=DiscoverySnapshots.merge(AdjacentSnapshot(listOf(first)),AdjacentSnapshot(listOf(older.copy(evidence=older.evidence+extra))),2000)
        assertEquals(2,value.cards.single().evidence.size)
        assertEquals(2000L,value.cards.single().evidence.single {it.kind==AdjacentKind.FEATURED}.checkedAt)
    }
}
