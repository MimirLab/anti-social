package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ContextMediaWireTest {
    @Test fun nativeOcrOmittedAppDefaultsRemainSafeWhenCopied() {
        val bytes="""{"text":"Local 🙂","lines":[{"text":"Local 🙂","confidence":96,"left":0,"top":0,"right":80,"bottom":20}],"language":"eng","width":100,"height":40,"meanConfidence":96,"uncertain":false}""".toByteArray()
        val result=ContextMediaWire.ocr(bytes,true)
        assertEquals("",result.sourceSha256);assertTrue(result.scaled);assertFalse(result.truncated)
        assertEquals("Local 🙂",result.text);assertTrue(result.engine.isNotBlank())
        assertEquals("",result.copy(scaled=false).sourceSha256)
        assertEquals("actual-file-hash",result.copy(sourceSha256="actual-file-hash").sourceSha256)
    }
    @Test fun nativeSilenceInitializesFullTypedSliceDefaults() {
        val result=ContextMediaWire.transcript("""{"text":"","segments":[],"durationMs":2000,"hasSpeech":false,"uncertain":false}""".toByteArray())
        assertEquals("",result.sourceSha256);assertEquals("",result.language);assertTrue(result.segments.isEmpty());assertFalse(result.hasSpeech)
        val slice=result.copy(startOffsetMs=6000,nextOffsetMs=8000,sourceDurationMs=12000,complete=false)
        assertEquals(6000L,slice.startOffsetMs);assertEquals("",slice.sourceSha256);assertTrue(slice.engine.isNotBlank())
    }
    @Test fun nativeSpeechCopiesSupplementaryUnicodeAndAbsoluteSliceMetadataSafely() {
        val bytes="""{"text":"Montréal 🙂","segments":[{"startMs":10,"endMs":500,"text":"Montréal 🙂","meanTokenProbability":0.9,"noSpeechProbability":0.01,"uncertain":false}],"language":"fr","durationMs":1000,"hasSpeech":true,"uncertain":false,"engine":"pinned local engine"}""".toByteArray()
        val result=ContextMediaWire.transcript(bytes).copy(complete=false,nextOffsetMs=1000)
        assertEquals("Montréal 🙂",result.segments.single().text);assertEquals("",result.sourceSha256);assertEquals("pinned local engine",result.engine)
    }
    @Test fun malformedNativeResultsCannotBecomeTypedSuccess() {
        val invalid="""{"text":"","lines":[],"language":"eng","width":0,"height":40,"meanConfidence":96}""".toByteArray()
        assertThrows(IllegalArgumentException::class.java){ContextMediaWire.ocr(invalid)}
        val probability="""{"text":"a","segments":[{"startMs":0,"endMs":100,"text":"a","meanTokenProbability":4,"noSpeechProbability":0}],"durationMs":1000,"hasSpeech":true}""".toByteArray()
        assertThrows(IllegalArgumentException::class.java){ContextMediaWire.transcript(probability)}
    }
}
