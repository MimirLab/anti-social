/* anti social, GPL-3.0-or-later. Runtime-specific differential token checks. */
package app.antisocial.android

import app.antisocial.core.CrossPosts
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class CaptionTokenizerNativeTest {
    private val tokens=CrossPosts::class.java.getDeclaredMethod("tokens",String::class.java).apply {isAccessible=true}
    private val previous=Regex("(?:[<>!]=?|[≤≥≠≈=])?\\s*(?:[+\\-−\\p{Sc}]\\s*){0,3}\\p{N}+(?:[.,]\\p{N}+)*(?:\\s*[%‰\\p{Sc}])?|[\\p{L}\\p{N}]+(?:'[\\p{L}\\p{N}]+)?")
    private val spaces=Regex("[\\p{Z}\\s]+")
    // Keep the frozen expressions while bounding the reference oracle's
    // native Matcher allocation during millions of generated token checks.
    private val referenceWords=previous.toPattern().matcher("")
    private val referenceSpaces=spaces.toPattern().matcher("")
    private fun same(value:String) {
        val before=ArrayList<String>()
        referenceWords.reset(value.lowercase())
        while(referenceWords.find())before+=referenceSpaces.reset(referenceWords.group()).replaceAll("").replace('−','-')
        // Our private pure method is the same on control and candidate APKs.
        // This does not use an Android hidden API or require a public feature.
        assertEquals(before,tokens.invoke(CrossPosts,value))
    }
    @Test fun nativeTokensPreserveEveryUnicodeCodePointAcrossNumericAndWordRoles() {
        val trace=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("antisocialTokenWorkTrace")=="true"
        for(start in 0..Character.MAX_CODE_POINT step 512) {
            if(trace)android.util.Log.i("CaptionTokenProbe","Unicode block $start nativeBytes=${android.os.Debug.getNativeHeapAllocatedSize()}")
            same(buildString {for(point in start..minOf(start+511,Character.MAX_CODE_POINT)) {
                append('A');appendCodePoint(point);append("B 1");appendCodePoint(point);append("2 ")
                appendCodePoint(point);append(" 3 4 ");appendCodePoint(point);append(' ')
            }})
        }
    }
    @Test fun nativeMixedTokensPreserveSignsCurrencyContractionsAndControls() {
        val random=Random(882_741)
        val atoms=listOf(" ","\t","\n","\u0085","\u00a0","\u2007","\u202f","A","e","İ","é","\u0301","𐐀","𝟠","Ⅻ","四","9","４","٤","²","0","+","-","−","$","€","₿","𞋿","<",">","!","=","≤","≥","≠","≈","%","‰",".",",","'","’","🛠","\ud800","\udfff")
        repeat(5000){same(buildString {repeat(1+random.nextInt(80)){append(atoms[random.nextInt(atoms.size)])}})}
        for(value in listOf("Capacity is ≥ $ 40.50 %, not 400.","1e3 can't've x'40"," ".repeat(4096)+"x","\t".repeat(4096)+" > 40",
            " $ ".repeat(300)+"word 40","40"+" ".repeat(4096)+"word","a\u001cb\u0085c\ufeffd\u200be","a\ud800\t\udfffz"))same(value)
        val operators=listOf("","<",">","!","<=",">=","!=","≤","≥","≠","≈","=")
        val prefixes=listOf("","+","-","−","$","€","𞋿","+ -","− $ €","+ − € $")
        val numbers=listOf("40","0040","40.50","4,000.50","40..50","Ⅻ","𝟠","²","٤٥")
        val suffixes=listOf("","%","‰","€","𞋿"," word","'word")
        val gaps=listOf(""," ","\t","\u00a0","\u0085","\u2007")
        var index=0
        for(operator in operators)for(prefix in prefixes)for(number in numbers)for(suffix in suffixes) {
            val gap=gaps[index++%gaps.size]
            same("Before $operator$gap$prefix$gap$number$gap$suffix after.")
        }
    }
}
