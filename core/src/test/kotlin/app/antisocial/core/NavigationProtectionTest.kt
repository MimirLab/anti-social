/* AntiSocial, GPL-3.0-or-later. A browser handoff always rechecks current protection. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NavigationProtectionTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    @Test fun nativeDocumentRulesProtectExplicitBrowserHandoffs() {
        NativeFilter("||malware.test^\$document").use {filter->
            val gateway=NetworkGateway(cleaner,filter)
            assertThrows(IOException::class.java){gateway.screenNavigation("https://malware.test/payload","https://reader.example/")}
            assertFalse(filter.check("https://malware.test/payload","https://reader.example/","image").blocked)
        }
    }
    @Test fun cachedDestinationsAreRecheckedAfterProtectionChangesWithoutNetworkWork() {
        var type=""
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,kind:String):FilterDecision {type=kind;return FilterDecision(false)}})
        assertEquals("https://publisher.example/guide",gateway.screenNavigation("https://publisher.example/guide"));assertEquals("document",type)
        gateway.replaceFilter(object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(true,"New list entry")})
        assertThrows(IOException::class.java){gateway.screenNavigation("https://publisher.example/guide")}
    }
    @Test fun aRewrittenDestinationIsFilteredBeforeNavigationOrAnySocket():Unit=runBlocking {
        MockWebServer().use {server->server.start()
            val filter=object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=if(url.endsWith("/start"))FilterDecision(false,rewrittenUrl=server.url("/blocked").toString())else FilterDecision(true,"Fixture block")}
            val gateway=NetworkGateway(cleaner,filter,true);val url=server.url("/start").toString()
            assertThrows(IOException::class.java){gateway.screenNavigation(url)}
            assertThrows(IOException::class.java){runBlocking {gateway.text(url)}}
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun torStartupAndPrivateDestinationsCannotEscapeThroughTheBrowserButton() {
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)})
        gateway.configure(NetworkPolicy(tor=true,ready=false));assertThrows(IOException::class.java){gateway.screenNavigation("https://publisher.example/guide")}
        gateway.configure(NetworkPolicy());assertThrows(IOException::class.java){gateway.screenNavigation("https://127.0.0.1/private")}
        assertThrows(IOException::class.java){gateway.screenNavigation("https://reader.local/private")}
    }
    @Test fun filterRewriteLoopsAndInvalidAddressesStopLocally() {
        var calls=0
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String):FilterDecision {calls++;return FilterDecision(false,rewrittenUrl=if(url.endsWith("/a"))"https://publisher.example/b"else "https://publisher.example/a")}})
        assertThrows(IOException::class.java){gateway.screenNavigation("https://publisher.example/a")};assertEquals(9,calls)
        gateway.replaceFilter(object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false,rewrittenUrl="javascript:alert(1)")})
        assertThrows(IOException::class.java){gateway.screenNavigation("https://publisher.example/a")}
    }
}
