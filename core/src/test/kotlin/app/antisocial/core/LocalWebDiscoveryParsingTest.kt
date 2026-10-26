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
    @Test fun fourgetOnlyUsesOrganicWebLinksWithAnExplicitSuccessfulStatus() {
        val body="""{"status":"ok","answer":[{"title":"An answer","url":"https://ignored.fixture.invalid/"}],"web":[{"title":"Community <b>workshop</b>","url":"https://publisher.fixture.invalid/calendar","description":"Unverified hours"},{"title":"Next","url":"https://index.fixture.invalid/api/v1/web?npt=token"}],"npt":"single-use-token","related":["ignore"]}"""
        assertEquals(listOf(LocalWebHit("https://publisher.fixture.invalid/calendar","Community workshop")),LocalWebDiscovery.parse(body,source,"4get"))
        assertTrue(LocalWebDiscovery.parse("""{"status":"ok","web":[]}""",source,"4get").isEmpty())
        assertEquals(HealthStatus.UNAVAILABLE,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse(body.replace("\"ok\"","\"Upstream error\""),source,"4get")}.status)
        assertEquals(HealthStatus.CHALLENGE,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse("""{"status":"Captcha required"}""",source,"4get")}.status)
    }
    @Test fun foreignShapesDuplicateCriticalFieldsAndMissingArraysAreNotFourgetResults() {
        val row="""{"title":"Workshop","url":"https://publisher.fixture.invalid/"}"""
        for(body in listOf("[$row]","""{"0":$row}""","""{"status":"ok"}""","""{"web":[$row]}""","""{"status":"error","status":"ok","web":[$row]}""","""{"status":"ok","web":[],"web":[$row]}""","""{"status":"ok","web":[{"title":"A","url":"https://a.fixture.invalid/","url":"https://b.fixture.invalid/"}]}"""))
            assertEquals(HealthStatus.UNSUPPORTED,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse(body,source,"4get")}.status)
        assertEquals(HealthStatus.UNSUPPORTED,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse("<div class='text-result-wrapper'><a href='https://publisher.fixture.invalid/'><h2>Workshop</h2></a></div>",source,"4get")}.status)
    }
    @Test fun redirectWrappersRequireARealProviderHostnameBoundary() {
        val fake="https://notduckduckgo.com/?uddg=https%3A%2F%2Fother.fixture.invalid%2F"
        val bing="https://notbing.com/ck/a?u=https%3A%2F%2Fother.fixture.invalid%2F"
        for(url in listOf(fake,bing))assertEquals(LocalDiscoveryIdentity.url(url),LocalWebDiscovery.parse("""[{"title":"Publisher","url":"$url"}]""",source).single().url)
    }
}
