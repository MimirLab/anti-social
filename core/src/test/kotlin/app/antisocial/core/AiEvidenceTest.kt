package app.antisocial.core

import org.junit.Test
import org.junit.Assert.*

class AiEvidenceTest {
    private val first=Post("1",Platform.X,"https://x.com/author/status/1","Author","Battery life is not 12 hours.")
    @Test fun identicalRepostsExtendAssociationsWithoutReprocessingTheSameContent() {
        val a=AiModelEvidence.snapshot("merged",listOf(first),first.body,1,10)
        val b=AiModelEvidence.snapshot("merged",listOf(first,first.copy(id="2",canonicalUrl="https://x.com/author/status/2")),first.body,2,20)
        assertEquals(a.contentRevision,b.contentRevision);assertEquals(2,b.sources.size);assertEquals(2L,b.ingestionSequence)
    }
    @Test fun editsAndNegationChangeEvidenceWhileArrivalAndConsumptionStayIndependent() {
        val old=AiModelEvidence.snapshot("merged",listOf(first),first.body,3,20)
        val edit=first.copy(body="Battery life is 12 hours.")
        val next=AiModelEvidence.snapshot("merged",listOf(edit),edit.body,4,30)
        assertNotEquals(old.contentRevision,next.contentRevision);assertNotEquals(old.sources[0].revision,next.sources[0].revision)
        assertEquals(old.sources[0].publishedAt,next.sources[0].publishedAt)
    }
    @Test fun anAttachedImageDoesNotClaimThatItsContentsWereRead() {
        val source=first.copy(media=listOf(Media("https://example.org/picture.jpg")))
        val snapshot=AiModelEvidence.snapshot("merged",listOf(source),source.body,0,0)
        assertTrue(snapshot.mediaObservations.isEmpty())
        val withOcr=AiModelEvidence.snapshot("merged",listOf(source),source.body,0,0,listOf(AiMediaObservation(source.media[0].url,"ocr","The actual OCR text","ocr-test-v1")))
        assertNotEquals(snapshot.contentRevision,withOcr.contentRevision)
    }
}
