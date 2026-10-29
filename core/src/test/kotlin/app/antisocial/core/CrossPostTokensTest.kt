/* anti social, GPL-3.0-or-later. Differential caption-token qualification. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CrossPostTokensTest {
    private val previous=Regex("(?:[<>!]=?|[≤≥≠≈=])?\\s*(?:[+\\-−\\p{Sc}]\\s*){0,3}\\p{N}+(?:[.,]\\p{N}+)*(?:\\s*[%‰\\p{Sc}])?|[\\p{L}\\p{N}]+(?:'[\\p{L}\\p{N}]+)?")
    private val spaces=Regex("[\\p{Z}\\s]+")
    private fun before(value:String)=previous.findAll(value.lowercase()).map {spaces.replace(it.value,"").replace('−','-')}.toList()
    private fun same(value:String)=assertEquals(before(value),CrossPostTokens.read(value))
    @Test fun operatorsPrefixesSeparatorsSuffixesAndContractionsKeepTheirLiteralTokens() {
        assertEquals(listOf("capacity","is","≥$40.50%","not","400"),CrossPostTokens.read("Capacity is ≥ $ 40.50 %, not 400."))
        assertEquals(listOf("1","e3","can't","ve","x'40"),CrossPostTokens.read("1e3 can't've x'40"))
        for(value in listOf("", "< = 40", "==40", "!= − € 40,000.50 ‰", "---$ 40", "+ - − 40", "12..34", "12.,34", "40 \nword",
            "≥\u00a0−\u2007€\u202f40.50\t%", "a\u001cb\u0085c\ufeffd\u200be", "أدوات ٤٠٫٥ معدات", "🛠 ४० 🔋", "İI CAFE\u0301", "a\ud800\t\udfffz"))same(value)
    }
    @Test fun everyUnicodeCodePointKeepsWordNumericCurrencyAndWhitespaceRoles() {
        for(start in 0..Character.MAX_CODE_POINT step 512) {
            val value=buildString {for(point in start..minOf(start+511,Character.MAX_CODE_POINT)) {
                append('A');appendCodePoint(point);append("B 1");appendCodePoint(point);append("2 ")
                appendCodePoint(point);append(" 3 4 ");appendCodePoint(point);append(' ')
            }}
            same(value)
        }
    }
    @Test fun deterministicMixedTextAgreesWithTheFrozenPreviousExpression() {
        val random=Random(882_741)
        val atoms=listOf(" ","\t","\n","\u0085","\u00a0","\u2007","\u202f","A","e","İ","é","\u0301","𐐀","𝟠","Ⅻ","四","9","４","٤","²","0","+","-","−","$","€","₿","𞋿","<",">","!","=","≤","≥","≠","≈","%","‰",".",",","'","’","🛠","\ud800","\udfff")
        repeat(10_000) {
            val value=buildString {repeat(1+random.nextInt(80)){append(atoms[random.nextInt(atoms.size)])}}
            same(value)
        }
    }
    @Test fun failedWhitespaceAndPrefixRunsDoNotSkipTheNextPossibleToken() {
        for(value in listOf(" ".repeat(4096)+"x", "\t".repeat(4096)+" > 40", " $ ".repeat(300)+"word 40",
            "+  − € $ 40", "40"+" ".repeat(4096)+"word", "'".repeat(4096)+"word", ".".repeat(4096)+"4.5"))same(value)
    }
    @Test fun completeAndInvalidNumericGrammarRetainsThePreviousPriority() {
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
    @Test fun simultaneousReadersCannotResetEachOthersMatcherOrRetainAnotherCaption() {
        val workers=Executors.newFixedThreadPool(2);val barrier=CyclicBarrier(2)
        val inputs=listOf("Capacity is ≥ $ 40.50 %, not 400. Keep today's repair instructions.",
            "Capacity is ≤ € − 400.50 ‰, not 40. Keep tomorrow's repair instructions.")
        val expected=inputs.map(::before)
        try {
            val jobs=inputs.indices.map {index->workers.submit {
                barrier.await(10,TimeUnit.SECONDS)
                repeat(500){assertEquals(expected[index],CrossPostTokens.read(inputs[index]))}
            }}
            jobs.forEach {it.get(15,TimeUnit.SECONDS)}
            assertNotEquals(expected[0],expected[1])
        }finally {workers.shutdownNow()}
    }
}
