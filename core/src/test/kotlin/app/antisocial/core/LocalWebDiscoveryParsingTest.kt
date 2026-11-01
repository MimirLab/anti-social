package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertFailsWith

class LocalWebDiscoveryParsingTest {
    private val source="https://index.fixture.invalid/api.php?q=workshop"
    @Test fun aSingleInitialUtf8MarkerKeepsBothSupportedJsonProtocolsAndLiteralValues() {
        val row="""{"title":"Public \ufeffworkshop","url":"https://publisher.fixture.invalid/ExactCase"}"""
        for((frontend,body)in listOf("librey" to "{\"0\":$row}","librey" to "[$row]","4get" to "{\"status\":\"ok\",\"web\":[$row]}")) {
            val expected=LocalWebDiscovery.parse(body,source,frontend)
            assertEquals(frontend,expected,LocalWebDiscovery.parse("\uFEFF \t\r\n$body",source,frontend))
            assertEquals("https://publisher.fixture.invalid/ExactCase",expected.single().url)
            assertEquals("Public \uFEFFworkshop",expected.single().title)
        }
    }
    @Test fun anInitialMarkerCannotRelaxStrictJsonOrHideARefusal() {
        for(frontend in listOf("librey","4get")) {
            val body=if(frontend=="4get")"""{"status":"ok","web":[]}"""else "[]"
            for(value in listOf("\uFEFF$body trailing","\uFEFF[{"))
                assertEquals(HealthStatus.UNSUPPORTED,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse(value,source,frontend)}.status)
            for(value in listOf("\uFEFF\uFEFF$body"," \uFEFF$body"))try {
                assertTrue(LocalWebDiscovery.parse(value,source,frontend).isEmpty())
            }catch(e:SourceFailure){assertEquals(HealthStatus.UNSUPPORTED,e.status)}
            for((page,status)in listOf("<form id='challenge-form'>Verify you are human</form>" to HealthStatus.CHALLENGE,
                "<form><input type='password'></form>" to HealthStatus.LOGIN_REQUIRED)) {
                // 4get accepts only its JSON protocol; its ordinary HTML
                // login response already has UNSUPPORTED classification.
                val expected=if(frontend=="4get"&&status==HealthStatus.LOGIN_REQUIRED)HealthStatus.UNSUPPORTED else status
                assertEquals(expected,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse(page,source,frontend)}.status)
                assertEquals(expected,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse("\uFEFF$page",source,frontend)}.status)
            }
        }
        assertEquals(HealthStatus.CHALLENGE,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse("\uFEFF{\"status\":\"Captcha required\",\"web\":[]}",source,"4get")}.status)
    }
    @Test fun anInitialMarkerKeepsStructuralAndByteAdmissionLimits() {
        val row="""{"title":"Workshop","url":"https://publisher.fixture.invalid/","url":"https://other.fixture.invalid/"}"""
        for(body in listOf("\uFEFF[$row]","\uFEFF"+"[".repeat(30)+"]".repeat(30),
            "\uFEFF"+(1..257).joinToString(",","[","]"){"{}"}))
            assertEquals(HealthStatus.UNSUPPORTED,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse(body,source)}.status)
        assertFailsWith<IllegalArgumentException>{LocalWebDiscovery.parse("\uFEFF"+" ".repeat(1024*1024)+"[]",source)}
    }
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
    @Test fun aProofOfWorkPageKeepsChallengeSemanticsWithoutExecutingItsScripts() {
        val body="""<html><head><script id="anubis_challenge" type="application/json">{"rules":{"algorithm":"fast"}}</script></head><body><h1>Making sure you're not a bot!</h1><script id="anubis-main" type="module" src="/.within.website/x/cmd/anubis/static/js/main.mjs"></script></body></html>"""
        for(frontend in listOf("librey","4get"))
            assertEquals(frontend,HealthStatus.CHALLENGE,assertFailsWith<SourceFailure>{LocalWebDiscovery.parse(body,source,frontend)}.status)
    }
    @Test fun aPublishedResultCanDiscussChallengeMarkupWithoutBecomingAChallenge() {
        val body="""<pre>&lt;script id="anubis-main"&gt;&lt;/script&gt;</pre><div class="text-result-wrapper"><a href="https://publisher.fixture.invalid/anubis"><h2>Making sure you're not a bot! Understanding Anubis</h2></a></div>"""
        assertEquals("https://publisher.fixture.invalid/anubis",LocalWebDiscovery.parse(body,source).single().url)
        val json="""{"status":"ok","web":[{"url":"https://publisher.fixture.invalid/anubis","title":"Anubis: Making sure you're not a bot!"}]}"""
        assertEquals("https://publisher.fixture.invalid/anubis",LocalWebDiscovery.parse(json,source,"4get").single().url)
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
