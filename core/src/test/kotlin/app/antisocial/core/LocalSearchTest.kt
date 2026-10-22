package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class LocalSearchTest {
    @Test fun blankAndPunctuationInputHasNoMatchExpression() {
        for(value in listOf("", " \n\t", "\"()***:—"))assertNull(LocalSearch.query(value))
    }
    @Test fun literalWordsUseImplicitAndWithBoundedPrefixesForTyping() {
        assertEquals("\"public*\" \"proje*\"",LocalSearch.query("Public proje"))
        assertEquals("\"a\"",LocalSearch.query("A"))
        assertEquals("\"running*\"",LocalSearch.query("running"))
    }
    @Test fun fulltextOperatorsAndQuotesCannotBecomeUserControlledSyntax() {
        assertEquals("\"alpha*\" \"beta*\" \"near*\" \"body*\" \"gamma*\"",LocalSearch.query("alpha\" OR beta NEAR body:gamma -"))
    }
    @Test fun unicodeNormalizationAndCasefoldKeepHumanSearchTerms() {
        assertEquals("\"café*\" \"δοκιμή*\" \"中文*\"",LocalSearch.query("Cafe\u0301 ΔΟΚΙΜΉ 中文"))
        assertEquals("\"public*\"",LocalSearch.query("ＰＵＢＬＩＣ"))
    }
    @Test fun duplicateWordsAndOversizedInputStayBounded() {
        assertEquals("\"public*\"",LocalSearch.query("PUBLIC public Public"))
        val query=LocalSearch.query((1..100).joinToString(" "){"word$it"})!!
        assertEquals(LocalSearch.MAX_TERMS,query.split(" ").size)
        val long=LocalSearch.query("a".repeat(10_000))!!
        assertEquals("\""+"a".repeat(LocalSearch.MAX_TERM)+"*\"",long)
        assertTrue(query.length<LocalSearch.MAX_INPUT)
    }
    @Test fun conversationalFillersAreOptionalButRemainSearchableAlone() {
        assertEquals("\"replacing*\" \"laptop*\" \"battery*\"",LocalSearch.query("Find that post about replacing a laptop battery"))
        assertEquals("\"the*\"",LocalSearch.query("the"))
        assertEquals("\"not*\" \"approved*\"",LocalSearch.query("not approved"))
    }
}
