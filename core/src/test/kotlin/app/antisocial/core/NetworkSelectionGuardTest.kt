/* anti social, GPL-3.0-or-later. Opaque local admission, never a network permit. */
package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NetworkSelectionGuardTest {
    private fun gateway()=NetworkGateway(UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject),object:RequestFilter {
        override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)
    },true)
    @Test fun readinessMayFinishWithoutChangingTheReadersSelection() {
        val gateway=gateway();gateway.configure(NetworkPolicy(tor=true,ready=false));val current=gateway.selectionGuard()
        gateway.configure(NetworkPolicy(tor=true,ready=true));assertTrue(current())
        gateway.configure(NetworkPolicy(tor=true,ready=false));assertTrue(current())
    }
    @Test fun returningToAnEqualPolicyDoesNotReviveAnObsoleteGuard() {
        val gateway=gateway();val original=gateway.selectionGuard()
        gateway.configure(NetworkPolicy(tor=true,ready=false));assertFalse(original())
        gateway.configure(NetworkPolicy());assertFalse(original());assertTrue(gateway.selectionGuard()())
    }
    @Test fun aDifferentGatewayCannotChangeTheCapturedChoice() {
        val first=gateway();val second=gateway();val current=first.selectionGuard()
        second.configure(NetworkPolicy(tor=true,ready=false));assertTrue(current())
        first.configure(NetworkPolicy(tor=true,ready=false));assertFalse(current())
    }
}
