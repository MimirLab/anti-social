package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ContextBriefReuseTest {
    private val body="The fair is Saturday, not Sunday. The entrance reopening is a separate event."
    private val original=ContextDocument("original","r1",body,author="Library",url="https://example.test/post/1",kind="post",
        metadata=mapOf("platform" to "x","published" to "2026-10-19T12:00:00Z","medium" to "Public post"))
    private val copy=original.copy(id="copy",revision="copy-r1",author="Local bulletin",url="https://other.test/post/2",fetchedAt=200,
        metadata=original.metadata+mapOf("platform" to "bluesky","root" to "another-original"))
    private val finding=ContextFinding("fair","The library corrects the fair date to Saturday, not Sunday.","correction",listOf(original.id),mapOf(original.id to body))
    private val extended=finding.copy(sources=listOf(original.id,copy.id),quotes=finding.quotes+mapOf(copy.id to body))
    private fun reuse(next:ContextFinding=extended, extra:ContextDocument=copy,
        earlier:List<ContextDocument> = listOf(original), now:List<ContextDocument> = earlier+extra)=
        ContextBriefReuse.onlyExactCopiesAdded(listOf(finding),listOf(next),earlier,now)

    @Test fun fullCrossPlatformCopyKeepsInformationWhileRetainingBothAuthorsAndSources() {
        assertTrue(reuse())
        assertEquals(listOf("original","copy"),extended.sources)
        assertTrue(reuse(next=extended.copy(sources=extended.sources.reversed(),quotes=extended.quotes.toList().reversed().toMap()),
            now=listOf(copy,original.copy(fetchedAt=300))))
        assertFalse(ContextBriefReuse.onlyExactCopiesAdded(listOf(finding),listOf(finding),listOf(original),listOf(original)))
    }

    @Test fun sameGeneratedWordsDoNotHideChangedOriginalOrReadingQuotations() {
        assertFalse(reuse(next=extended.copy(quotes=extended.quotes+mapOf("original" to "The fair is Saturday."))))
        assertFalse(reuse(next=extended.copy(readingQuotes=mapOf("original" to "The fair date is uncertain."))))
        assertFalse(reuse(next=extended.copy(text="The library says Sunday.")))
        assertFalse(reuse(next=extended.copy(kind="question")))
    }

    @Test fun identicalQuotedSpanInADifferentFullSourceIsNotACompleteCopy() {
        assertFalse(reuse(extra=copy.copy(text=body+" Correction: the date has now changed again.")))
        assertFalse(reuse(extra=copy.copy(parentText="A reply about an entirely different event.")))
        assertFalse(reuse(extra=copy.copy(title="A different event")))
        assertFalse(reuse(extra=copy.copy(kind="comment")))
    }

    @Test fun qualityAttachmentsAndUnknownMetadataCannotDisappearIntoProvenance() {
        for(metadata in listOf(mapOf("uncertain" to "true"),mapOf("truncated" to "true"),mapOf("deleted" to "true"),
            mapOf("medium" to "Automatically generated captions"),mapOf("links" to "https://different.test/evidence"),
            mapOf("contextParent" to "different-parent"),mapOf("new-evidence-field" to "changed")))
            assertFalse(metadata.toString(),reuse(extra=copy.copy(metadata=copy.metadata+metadata)))
    }

    @Test fun anEarlierParentEditOrSpeakerReassignmentNeedsNormalComposition() {
        val parent=ContextDocument("parent","p1","The event is conditional on approval.",kind="post")
        assertFalse(reuse(earlier=listOf(original,parent),now=listOf(original,copy,parent.copy(text="Approval was denied.",revision="p2"))))
        assertFalse(reuse(now=listOf(original.copy(author="Someone else"),copy)))
        assertFalse(reuse(now=listOf(original.copy(revision="edited"),copy)))
        assertFalse(reuse(now=listOf(copy)))
    }

    @Test fun everyAddedSupportMustMatchAndMissingOrAmbiguousEvidenceIsRejected() {
        val third=copy.copy(id="third",text=body+" This extra qualification is new.")
        val more=extended.copy(sources=extended.sources+third.id,quotes=extended.quotes+mapOf(third.id to body))
        assertFalse(reuse(next=more,now=listOf(original,copy,third)))
        assertFalse(reuse(next=extended.copy(quotes=mapOf("copy" to body))))
        assertFalse(reuse(next=extended.copy(sources=listOf("copy"),quotes=mapOf("copy" to body))))
        assertFalse(reuse(now=listOf(original,copy,copy.copy(text="ID collision"))))
    }

    @Test fun translatedCopiesRequireTheSameOriginalAndWorkingEvidence() {
        val spanish="La feria es el sábado, no el domingo."
        val old=original.copy(text=spanish);val added=copy.copy(text=spanish)
        val translated=finding.copy(quotes=mapOf("original" to spanish),readingQuotes=mapOf("original" to body))
        val both=translated.copy(sources=listOf("original","copy"),quotes=mapOf("original" to spanish,"copy" to spanish),
            readingQuotes=mapOf("original" to body,"copy" to body))
        assertTrue(ContextBriefReuse.onlyExactCopiesAdded(listOf(translated),listOf(both),listOf(old),listOf(old,added)))
        assertFalse(ContextBriefReuse.onlyExactCopiesAdded(listOf(translated),listOf(both.copy(readingQuotes=mapOf("original" to body,"copy" to "Sunday is confirmed."))),listOf(old),listOf(old,added)))
    }

    @Test fun identicalRelativeWordingPublishedOnAnotherDayIsNotMerelyExtraProvenance() {
        val text="The entrance reopens tomorrow. The fair is next Saturday."
        val old=original.copy(text=text)
        val added=copy.copy(text=text,metadata=copy.metadata+mapOf("published" to "2026-10-20T12:00:00Z"))
        val prior=finding.copy(text="The entrance reopens on 20 October.",quotes=mapOf(old.id to text))
        val next=prior.copy(sources=listOf(old.id,added.id),quotes=mapOf(old.id to text,added.id to text))
        assertFalse("Publication time can change what identical wording means",ContextBriefReuse.onlyExactCopiesAdded(listOf(prior),listOf(next),listOf(old),listOf(old,added)))
    }

    @Test fun onlyTheSameKnownPublicationInstantCanBeExtraCopyProvenance() {
        assertTrue(reuse(extra=copy.copy(metadata=copy.metadata+mapOf("published" to "2026-10-19T08:00:00-04:00"))))
        assertFalse(reuse(extra=copy.copy(metadata=copy.metadata+mapOf("published" to "2026-10-19T12:00:01Z"))))
        assertFalse(reuse(extra=copy.copy(metadata=copy.metadata-mapOf("published" to "").keys)))
        assertFalse(reuse(extra=copy.copy(metadata=copy.metadata+mapOf("published" to "not a date"))))
    }
}
