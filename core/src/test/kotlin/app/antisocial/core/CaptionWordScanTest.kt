/* anti social, GPL-3.0-or-later. Compare with the previous literal expressions. */
package app.antisocial.core

import java.util.Random
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class CaptionWordScanTest {
    private val words=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val simple=Regex("[\\p{L}\\p{N}]+")
    private fun same(value:String,limits:List<Int> = listOf(0,1,2,3,6,24,25,64,65,256,257,Int.MAX_VALUE)) {
        val old=words.findAll(value).map {it.value}.toList()
        val bare=simple.findAll(value).count()
        for(limit in limits) {
            assertEquals(old.take(limit),CaptionWordScan.read(value,limit))
            assertEquals(minOf(old.size,limit),CaptionWordScan.count(value,limit))
            assertEquals(minOf(bare,limit),CaptionWordScan.count(value,limit,simple=true))
        }
    }
    @Test fun literalWordsKeepApostrophesNumbersMarksAndSupplementaryLetters() {
        listOf("","one two three","Don't stop. It isn’t over!","a'b’c d''e 'last'",
            "e\u0301 ας ΑΣ\u0301Α İİ Ⅸ １２٣","𐐀𐐨 𝔸𝔹 👩\u200d🔬",
            "A\u0000B\uffffC\ud800 D\udc00","-6 9 6.5 3/4 1,000 6–9 #word @handle").forEach {same(it)}
    }
    @Test fun admissionThresholdsKeepTheFirstDisqualifyingWord() {
        for(count in listOf(1,2,3,5,6,7,8,23,24,25,26,63,64,65,66,255,256,257,258))
            same((1..count).joinToString(" "){"word$it"})
    }
    @Test fun fixedMixedUnicodeInputsAgreeWithTheOldIterator() {
        val random=Random(7301)
        repeat(1024) {same(buildString {repeat(24){appendCodePoint(random.nextInt(0x110000));append(" word'")}},listOf(0,2,6,25,65,257))}
    }
    @Test fun independentReadersOwnTheirMatchers() {
        val executor=Executors.newFixedThreadPool(4)
        try {
            val tasks=(1..16).map {index->executor.submit<Boolean> {
                val value=(1..index*4).joinToString(" "){"Reader${index}'s$it"}
                repeat(16){same(value,listOf(1,6,25,65))};true
            }}
            tasks.forEach {assertTrue(it.get())}
        }finally {executor.shutdownNow()}
    }
}
