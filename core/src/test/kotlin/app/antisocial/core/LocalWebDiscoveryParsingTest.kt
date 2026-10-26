package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertFailsWith

class LocalWebDiscoveryParsingTest {
    private val source="https://index.fixture.invalid/api.php?q=workshop"
    @Test fun phpMixedResultObjectsKeepPublisherLinksAndIgnoreSpecialAnswers() {
        val body="""{"0":{"special_response":{"response":"A generated answer","source":"https://answer.fixture.invalid/"}},"1":{"title":"\n Public &amp; community <b>workshop</b> ","url":"https://publisher.fixture.invalid/calendar","description":"Tomorrow, free, ages 3+"},"2":{"title":"Repeated","url":"https://publisher.fixture.invalid/calendar#section"},"results_source":"upstream.fixture.invalid"}"""
        assertEquals(listOf(LocalWebHit("https://publisher.fixture.invalid/calendar","Public & community workshop")),LocalWebDiscovery.parse(body,source))
    }
    @Test fun arraysRejectUnsafeAndNavigationDestinationsWithoutPromotingSnippets() {
        val body="""[{"title":"Bad","url":"javascript:alert(1)"},{"title":"Credentials","url":"https://user:pass@publisher.fixture.invalid/"},{"title":"Next","url":"https://index.fixture.invalid/search.php?q=x"},{"title":"Workshop","url":"https://publisher.fixture.invalid/"}]"""
        assertEquals(listOf(LocalWebHit("https://publisher.fixture.invalid/","Workshop")),LocalWebDiscovery.parse(body,source))
    }
    @Test fun explicitErrorsAndMalformedOrExcessiveStructuresAreNotWorkingContent() {
        assertEquals(HealthStatus.UNAVAILABLE,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse("""{"error":{"message":"Unable to query upstream"}}""",source)}.status)
        for(body in listOf("[{", "[] trailing", "[".repeat(30)+"]".repeat(30), (1..257).joinToString(",","[","]"){"{}"}))
            assertEquals(HealthStatus.UNSUPPORTED,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse(body,source)}.status)
        assertTrue(LocalWebDiscovery.parse("[]",source).isEmpty())
    }
    @Test fun challengesAndLoginPagesCannotBecomeAnApiCapabilityFallback() {
        assertEquals(HealthStatus.CHALLENGE,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse("<form id='challenge-form'>Verify you are human</form>",source)}.status)
        assertEquals(HealthStatus.LOGIN_REQUIRED,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse("<form><input type='password'></form>",source)}.status)
    }
    @Test fun anOptionalLoginFormDoesNotHideAlreadyPublicPublisherLinks() {
        val body="""<form><input type="password"></form><div class="text-result-wrapper"><a href="https://publisher.fixture.invalid/calendar"><h2>Public workshop</h2></a></div>"""
        assertEquals(listOf(LocalWebHit("https://publisher.fixture.invalid/calendar","Public workshop")),LocalWebDiscovery.parse(body,source))
    }
}
