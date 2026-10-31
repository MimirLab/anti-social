/* anti social, GPL-3.0-or-later. Complete, cancellable source observations. */
package app.antisocial.core

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ContentObservationPreparationTest {
    private fun post(id:String)=Post(id,Platform.X,"https://x.com/publicstudio/status/$id","Public studio",
        "A complete workshop announcement. Read https://example.org/report?id=$id&utm_source=fixture#details",
        provenance=Provenance(Source(Platform.X,"nitter","https://reader.fixture.invalid"),44,"https://reader.fixture.invalid/publicstudio/status/$id"))
    // Freeze the previous observation contract independently of the new worker.
    private fun previous(posts:List<Post>)=posts.filter {!it.preview&&(it.body.isNotBlank()||it.title.isNotBlank()||it.media.isNotEmpty())}
        .distinctBy(CrossPosts::originalKey).map {ContentObservation(CrossPosts.originalKey(it),ContentRevisions.signature(it),it.provenance?.fetchedAt ?: 0)}

    @Test fun finishedPreparationKeepsEligibilityFirstEditionOrderAndExactSignatures() {
        val first=post("720001");val second=post("720002").copy(body="",media=listOf(Media("https://media.example.org/photo.jpg?token=old")))
        val source=listOf(first.copy(preview=true),post("720003").copy(body=""),second,first,first.copy(body="A later duplicate edition.",provenance=null))
        val before=source.toList();var checkpoints=0
        val actual=ContentRevisions.observations(source){checkpoints++}
        assertEquals(previous(source),actual);assertEquals(actual,ContentRevisions.observations(source))
        assertEquals(listOf(second.identityKey,first.identityKey),actual.map {it.key})
        assertTrue(actual.all {it.fetchedAt==44L});assertTrue(checkpoints>=source.size)
        assertEquals(before,source)
    }
    @Test fun cancellationDoesNotReturnPartialFactsOrAffectANewPreparation() {
        val source=listOf(post("720001"),post("720002"));var returned=false
        assertThrows(CancellationException::class.java) {
            ContentRevisions.observations(source){throw CancellationException("Obsolete source batch")}.also {returned=true}
        }
        assertFalse(returned);assertEquals(previous(source),ContentRevisions.observations(source){})
    }
    @Test fun cancellationAlsoStopsAnIneligibleBatchBeforeItCanScanTheWholeInput() {
        val source=List(64){post((720000+it).toString()).copy(preview=true)};var seen=0
        assertThrows(CancellationException::class.java) {
            ContentRevisions.observations(source){if(++seen==5)throw CancellationException("Reader stopped")}
        }
        assertEquals(5,seen);assertTrue(ContentRevisions.observations(source).isEmpty())
    }
    @Test fun cancellationBetweenSignaturesNeverStartsTheNextOriginal() {
        val firstTouched=AtomicBoolean();val laterTouched=AtomicInteger()
        fun media(first:Boolean)=object:AbstractList<Media>() {
            override val size=1
            override fun get(index:Int):Media {
                check(index==0);if(first)firstTouched.set(true)else laterTouched.incrementAndGet()
                return Media("https://media.example.org/photo.jpg")
            }
        }
        val source=listOf(post("720001").copy(media=media(true)),post("720002").copy(media=media(false)))
        assertThrows(CancellationException::class.java) {
            ContentRevisions.observations(source){if(firstTouched.get())throw CancellationException("Reader stopped while signing")}
        }
        assertTrue(firstTouched.get());assertEquals(0,laterTouched.get())
    }
}
