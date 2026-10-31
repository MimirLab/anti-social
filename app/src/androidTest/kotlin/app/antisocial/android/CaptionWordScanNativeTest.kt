/* anti social, GPL-3.0-or-later. Actual Android literal-word equivalence. */
package app.antisocial.android

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class CaptionWordScanNativeTest {
    @Test fun boundedWordReadsAndCountsMatchBothPreviousPlatformExpressions() {
        val type=Class.forName("app.antisocial.core.CaptionWordScan")
        val instance=type.getField("INSTANCE").get(null)
        val read=type.declaredMethods.single {it.name.substringBefore('$')=="read"&&it.parameterCount==2}.apply {isAccessible=true}
        val count=type.declaredMethods.single {it.name.substringBefore('$')=="count"&&it.parameterCount==3}.apply {isAccessible=true}
        val words=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
        val simple=Regex("[\\p{L}\\p{N}]+")
        fun same(value:String) {
            val expected=words.findAll(value).map {it.value}.toList();val bare=simple.findAll(value).count()
            for(limit in listOf(0,2,3,6,24,25,64,65,256,257,Int.MAX_VALUE)) {
                assertEquals(expected.take(limit),read.invoke(instance,value,limit))
                assertEquals(minOf(limit,expected.size),count.invoke(instance,value,limit,false))
                assertEquals(minOf(limit,bare),count.invoke(instance,value,limit,true))
            }
        }
        listOf("","Don't stop. It isn’t over!","a'b’c d''e 'last'","e\u0301 ας ΑΣ\u0301Α İİ Ⅸ １２٣",
            "𐐀𐐨 𝔸𝔹 👩\u200d🔬","A\u0000B\uffffC\ud800 D\udc00","-6 9 6.5 3/4 1,000 6–9").forEach(::same)
        for(size in listOf(2,3,6,7,24,25,64,65,256,257,258))same((1..size).joinToString(" "){"word$it"})
        val random=Random(7301)
        repeat(256){same(buildString {repeat(16){appendCodePoint(random.nextInt(0x110000));append(" word'")}})}
    }
}
