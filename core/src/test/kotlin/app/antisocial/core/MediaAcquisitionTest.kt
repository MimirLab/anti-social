package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MediaAcquisitionTest {
    private fun gateway(filter:RequestFilter=object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)})=
        NetworkGateway(UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject),filter,true)
    @Test fun acquisitionRetainsOnlyExplicitCredentialLinksWithoutRequestingThem():Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody("image bytes").addHeader("Content-Type","image/png")
            .addHeader("Set-Cookie","tracking=never-retained").addHeader("Link","<https://credentials.example/image.c2pa>; rel=\"c2pa-manifest\""))
            val result=gateway().mediaPayload(server.url("/image.png").toString())
            assertEquals(listOf("https://credentials.example/image.c2pa"),result.manifestUrls)
            assertEquals("image/png",result.contentType);assertEquals("image bytes",String(result.bytes));assertEquals(1,server.requestCount)
        }
    }
    @Test fun redirectedTargetsAreRefilteredBeforeTheirBytesCanBeInspected():Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",server.url("/blocked")))
            val gate=gateway(object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(url.endsWith("/blocked"),"Fixture")})
            assertThrows(IOException::class.java){runBlocking {gate.mediaPayload(server.url("/start").toString())}}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun inspectionBudgetsCountRedirectsAndStopBeforeTheNextRequest():Unit=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",server.url("/next")))
            val gate=gateway()
            assertThrows(IOException::class.java){runBlocking {gate.boundedMediaRead(maxRequests=1){gate.mediaPayload(server.url("/start").toString())}}}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun aggregateBytesAndSelectedTorCannotEscapeTheirBudgets():Unit=runBlocking {
        MockWebServer().use {server->server.start();val gate=gateway();server.enqueue(MockResponse().setBody("x".repeat(2048)))
            assertThrows(IOException::class.java){runBlocking {gate.boundedMediaRead(maxBytes=1024){gate.mediaPayload(server.url("/large").toString())}}}
            gate.configure(NetworkPolicy(tor=true,ready=false))
            assertThrows(IOException::class.java){runBlocking {gate.mediaPayload(server.url("/tor").toString())}}
            assertEquals(1,server.requestCount)
        }
    }
}
