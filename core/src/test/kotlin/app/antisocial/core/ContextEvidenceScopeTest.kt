package app.antisocial.core

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ContextEvidenceScopeTest {
    private fun scope(medium:String,metadata:Map<String,String> = emptyMap())=ContextEvidenceScope.from(ContextDocument("source","1","Text",kind="article",metadata=mapOf("medium" to medium)+metadata))
    @Test fun knownProducerMetadataClassifiesTextWithoutGuessingFromTheCaptionBody() {
        assertEquals(ContextTextObservation.CAPTION,scope("Published captions").observation)
        assertTrue(scope("Automatically generated captions").automatic)
        assertEquals(ContextTextObservation.IMAGE_WORDS,scope("Local image text recognition").observation)
        assertEquals(ContextTextObservation.IMAGE_DESCRIPTION,scope("Source-supplied image description").observation)
        assertEquals(ContextTextObservation.AUDIO,scope("Local speech transcription").observation)
        assertEquals(ContextTextObservation.DOCUMENT_WORDS,scope("Recognized document page").observation)
        assertEquals(ContextTextObservation.DOCUMENT,scope("Document text").observation)
        assertEquals(ContextTextObservation.PAGE,scope("Some article mentioning captions").observation)
    }
    @Test fun qualityFlagsAndEveryObservationKindSurviveAggregationWithoutWitnessCounts() {
        val scopes=listOf(scope("Published captions"),scope("Local image text recognition",mapOf("uncertain" to "true")),scope("Document text",mapOf("truncated" to "true")))
        val json=JsonObject();ContextEvidenceScope.write(json,scopes+scopes)
        val frame=json["observation_scope"].asJsonObject
        assertEquals(3,frame["text_kinds"].asJsonArray.size())
        assertEquals("recognized_image_text",frame["uncertain_text"].asJsonArray.single().asString)
        assertEquals("document_text",frame["incomplete_text"].asJsonArray.single().asString)
        assertFalse(frame.toString().contains("count"))
    }
    @Test fun onlyRecognizedMediaWithDeclaredCueFormattingCanShedPlaybackLocators() {
        assertTrue(scope("Published captions",mapOf("timestamps" to "true")).timecodes)
        assertTrue(scope("Local speech transcription",mapOf("timestamps" to "true")).timecodes)
        assertFalse(scope("Ordinary article",mapOf("timestamps" to "true")).timecodes)
        assertFalse(scope("Published captions").timecodes)
    }
}
