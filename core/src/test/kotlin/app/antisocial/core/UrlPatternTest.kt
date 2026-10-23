package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class UrlPatternTest {
    private fun data()=JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject
    @Test fun everyBundledPatternQualifiesForTheLinearAsciiEngine() {
        var count=0
        data().getAsJsonObject("providers").entrySet().forEach {(_,entry)->val p=entry.asJsonObject
            for(key in listOf("urlPattern","exceptions","redirections","rules","referralMarketing","rawRules")) {
                val patterns=p[key]?.let {if(it.isJsonArray)it.asJsonArray.map {v->v.asString}else listOf(it.asString)}.orEmpty()
                patterns.forEach {rule->val value=if(key in setOf("rules","referralMarketing"))"^(?:$rule)$"else rule
                    assertTrue("$key $rule",UrlPattern(value,key!="rawRules",true).linearAvailable);count++}
            }
        };assertTrue(count>1000)
    }
    @Test fun completeCleaningMatchesJavaAcrossTrackingRedirectsSignaturesAndUnicode() {
        val linear=UrlCleaner(data());val original=UrlCleaner(data(),false);linear.prepare();original.prepare()
        val domains=listOf("www.amazon.com","www.google.com","www.facebook.com","www.instagram.com","www.reddit.com","x.com","t.co","youtube.com","example.org","www.bing.com","l.facebook.com")
        val queries=listOf("utm_source=message&keep=1","fbclid=abc&utm_campaign=test","sig=abc&utm_source=kept","token=abc&expires=123","url=https%3A%2F%2Fexample.org%2Farticle%3Futm_source%3Dtest","q=coffee&ved=123&source=hp","t%C4%B1d=123&utm_source=x","utm_source%0A=x&keep=1","foo=😀&ref=one")
        val random=Random(42)
        repeat(1200){i->val url="https://${domains[random.nextInt(domains.size)]}/${if(i%3==0)"url"else if(i%4==0)"l.php"else "article/$i"}?${queries[random.nextInt(queries.size)]}#section"
            assertEquals(url,original.clean(url),linear.clean(url))}
    }
    @Test fun unicodeCaseFoldingAndFinalNewlinesKeepJavaBehavior() {
        val input=JsonParser.parseString("""{"providers":{"fixture":{"urlPattern":".*","rules":["tid","utm_source"]}}}""").asJsonObject
        val cleaner=UrlCleaner(input)
        assertEquals("https://example.org/",cleaner.clean("https://example.org/?t%C4%B1d=123"))
        assertEquals("https://example.org/",cleaner.clean("https://example.org/?utm_source%0A=123"))
    }
    @Test fun necessaryLiteralPruningNeverRequiresOptionalRepeatedOrAlternativeText() {
        val cases=mapOf("ab+c" to listOf("abbbc","ABC"),"foo(?:bar)?baz" to listOf("foobaz","foobarBAZ"),"foo|bar" to listOf("foo","BAR"),"prefix(?:red|blue)suffix" to listOf("prefixredsuffix","prefixbluesuffix"),"https?://example\\.org" to listOf("http://example.org","HTTPS://EXAMPLE.ORG"),"abc{0,3}def" to listOf("abdef","abcccdef"),"a{1,3}bcdef" to listOf("abcdeF","aaabcdef"),"https?://[^/]+/foo" to listOf("https://host.example/foo"))
        assertTrue(UrlPattern("^EXAMPLE$",true,true).contains("EXAMPLE",true))
        for((pattern,inputs) in cases)for(input in inputs)assertEquals("$pattern $input",Regex(pattern,RegexOption.IGNORE_CASE).containsMatchIn(input),UrlPattern(pattern,true,true).contains(input,true,input.lowercase(java.util.Locale.ROOT)))
    }
    @Test fun unsupportedLookaroundsStillCleanNormally() {
        val input=JsonParser.parseString("""{"providers":{"fixture":{"urlPattern":"^https://(?=example)example.org/","rules":["tracking"]}}}""").asJsonObject
        assertEquals("https://example.org/?keep=1",UrlCleaner(input).clean("https://example.org/?tracking=x&keep=1"))
    }
    @Test(timeout=5000) fun repeatedAlternationHasLinearWorstCase() {
        val pattern=UrlPattern("^(a+)+$",false,true)
        assertTrue(pattern.linearAvailable);assertFalse(pattern.contains("a".repeat(100_000)+"!",true))
    }
    @Test(timeout=5000) fun unsupportedPathologicalPatternsFailClosedAtAWorkBound() {
        val input=JsonParser.parseString("""{"providers":{"fixture":{"urlPattern":"^(a+)+(?=b)$","rules":[]}}}""").asJsonObject
        assertThrows(UrlPattern.WorkLimit::class.java){UrlCleaner(input).clean("a".repeat(3000)+"!?query=1")}
    }
}
