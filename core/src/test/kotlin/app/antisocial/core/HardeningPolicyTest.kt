package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HardeningPolicyTest {
    @Test fun actionEndpointsWaitForDeliberateOpeningButTheirSourcesStayIntact() {
        for(path in listOf("/unsubscribe","/unsubscribe/abc","/password/reset?token=abc","/confirm?code=123","/accept-invite?key=abc","/?action=unsubscribe","/a/%75nsubscribe?token=abc","/verify/abc?key=123","/account/confirm?confirmation_token=abc","/invite/opaque/accept","/unsubscribe.php?list=abc","/?action=confirm&token=abc","/?action=view&action=unsubscribe","/mail/unsubscribe%2fopaque","/unsubscribe;session=abc"))
            assertFalse(path,AutomaticLinks.mayRead("https://example.org$path"))
    }
    @Test fun readingAboutActionsIsOrdinaryContent() {
        for(path in listOf("/articles/password-reset","/news/how-to-unsubscribe","/guides/unsubscribe?print=1","/reset-password-guide","/events/invitation-to-the-workshop","/password-resetting-guide","/news/confirm-or-deny","/recipes?code=coffee"))
            assertTrue(path,AutomaticLinks.mayRead("https://example.org$path"))
    }
    @Test fun supplementalRedirectIsStoppedBeforeItsDestinationIsRequested():Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/unsubscribe?token=private"))
            val gateway=gateway();assertThrows(IOException::class.java){runBlocking {gateway.extractorDocument(server.url("/read").toString(),purpose=FetchPurpose.SUPPLEMENTAL)}}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun explicitOpeningMediaAndDeclaredApiReadsRetainTheirRoutes():Unit=runBlocking {
        MockWebServer().use {server->server.start();repeat(3){server.enqueue(MockResponse().setBody("public data"))}
            val gateway=gateway();for(purpose in listOf(FetchPurpose.DELIBERATE,FetchPurpose.MEDIA,FetchPurpose.API))
                assertEquals("public data",gateway.extractorDocument(server.url("/confirm?token=signed").toString(),purpose=purpose).text)
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun supplementalActionIsStoppedWithoutDnsOrSocket():Unit=runBlocking {
        MockWebServer().use {server->server.start();assertThrows(IOException::class.java){runBlocking {gateway().extractorDocument(server.url("/unsubscribe").toString(),purpose=FetchPurpose.SUPPLEMENTAL)}};assertEquals(0,server.requestCount)}
    }
    private fun gateway()=NetworkGateway(UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject),object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)},true)
    @Test fun admissionIgnoresQuotedAnglesAndScriptContentsAndKeepsOrdinaryExtraction() {
        HtmlAdmission.check("<article title='a > b'><p>Guide</p><script>"+"</scriptx>"+"<div>".repeat(65_000)+"</script><p>Next</p></article>")
        HtmlAdmission.check("<ul>"+"<li>Ordinary implicit closing".repeat(10_000)+"</ul>")
    }
    @Test fun nestingAndNodeBombsStopBeforeDomAllocation() {
        assertThrows(SourceFailure::class.java){HtmlAdmission.check("<div>".repeat(2000))}
        assertThrows(SourceFailure::class.java){HtmlAdmission.check("<br>".repeat(60_001))}
    }
    @Test fun ordinaryFeedParserRejectsPathologicalStructureBeforeBuildingItsDom() {
        val url="https://workshop.example/feed.xml";val target=Target(Platform.RSS,TargetKind.FEED,"workshop",url)
        assertThrows(SourceFailure::class.java){RssSources.parse(Payload("<rss><channel>"+"<entry>".repeat(2000),url,"application/rss+xml",200),target)}
    }
    @Test fun overridesAreVisibleButRtlJoinersCombiningMarksAndEmojiStayReadable() {
        val normal="مكتبة העברית 日本語 e\u0301 پی\u200cنوشت 👩\u200d🔧"
        assertEquals(normal,IdentityDisplay.controls(normal));assertNull(IdentityDisplay.notice(normal))
        val deceptive="Alice\u202ecom.example";assertNotNull(IdentityDisplay.notice(deceptive));assertEquals(deceptive.length,IdentityDisplay.controls(deceptive).length)
        assertFalse(IdentityDisplay.controls(deceptive).contains('\u202e'))
    }
    @Test fun mixedScriptLookalikesAreObservationsAndDomainsExposeAscii() {
        assertNotNull(IdentityDisplay.notice("p\u0430ypal"));assertNull(IdentityDisplay.notice("Παράδειγμα"));assertNull(IdentityDisplay.notice("東京 Books"))
        assertEquals("xn--pple-43d.com",IdentityDisplay.domain("https://\u0430pple.com/path"))
        assertFalse(IdentityDisplay.destination("https://example.org/a\u202eb").contains('\u202e'))
    }
}
