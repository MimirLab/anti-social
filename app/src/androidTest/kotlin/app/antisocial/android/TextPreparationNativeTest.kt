/* anti social, GPL-3.0-or-later. Generated text only; no imports or acquisition. */
package app.antisocial.android

import app.antisocial.core.ContentRevisions
import app.antisocial.core.Platform
import app.antisocial.core.Post
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

class TextPreparationNativeTest {
    // The pre-optimization expression is the oracle for this Android runtime.
    private fun previousSignature(raw:String):String {
        val prepared=Normalizer.normalize(raw,Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Z}]+")," ").trim()
        return MessageDigest.getInstance("SHA-256").digest((prepared+"\u0000\u0000").toByteArray()).joinToString(""){"%02x".format(it)}
    }
    private fun actual(raw:String)=ContentRevisions.signature(Post("generated",Platform.X,"https://x.com/workshop/status/123","Workshop",raw))
    @Test fun allUnicodeCodePointsKeepTheirExistingNativeSignature() {
        // Large input deliberately bypasses footer folding; there are no URLs.
        // Include unpaired surrogates as well as valid supplementary characters.
        val raw=buildString {for(point in 0..Character.MAX_CODE_POINT){append('a');appendCodePoint(point);append('b')}}
        val expected=previousSignature(raw)
        assertEquals(expected,actual(raw))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("validation"),"text-preparation").apply {mkdirs()}.resolve("unicode-parity.json")
            .writeText(Gson().toJson(mapOf("codePoints" to Character.MAX_CODE_POINT+1,"utf16Length" to raw.length,"signature" to expected,
                "nextLineMatchesRuntimeSpace" to Regex("\\s").matches("\u0085"),"scope" to "Generated exhaustive native signature parity; no reader records or acquisition.")))
    }
    @Test fun controlsNonbreakingSpacesAndWordBoundariesRemainDistinctWhereRequired() {
        for(raw in listOf("a\u001cb","a\u001db","a\u001eb","a\u001fb","a\u0085b","a\ufeffb","🛠\u2003\u2028\u2029\tREPAIR\u00a0🔋","\t one  two \r\n","a\ud800\t\udfffz","Repair bulletin 358")) {
            assertEquals(previousSignature(raw),actual(raw))
        }
        assertNotEquals(actual("a\u001cb"),actual("a b"))
    }
}
