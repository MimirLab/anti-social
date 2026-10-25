package app.antisocial.core

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test

class InstagramPublicQueriesTest {
    private val source=Source(Platform.INSTAGRAM,"instagram-public-graphql","https://www.instagram.com",true,SourceRole.ORIGINAL)
    private val target=Target(Platform.INSTAGRAM,TargetKind.POST,"DeTUk4sDSp-","https://www.instagram.com/p/DeTUk4sDSp-/")
    private val base=InstagramPublicGraphQl.commentsQuery("DeTUk4sDSp-")
    private fun replacement(id:String="12345678901234567")=base.copy(id=id,variables=base.variables.deepCopy().apply {addProperty("__relay_internal__pv__ExampleFeatureRelayrelayprovider",true)})
    private fun gateway()=NetworkGateway(UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject),object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)})

    @Test fun learnedMetadataStaysWithItsSourceTargetOperationAndExactInputShape() {
        val gateway=gateway();val bank=InstagramPublicQueries({100},gateway::sessionFor);val session=gateway.sessionFor(source.root)
        bank.remember(source,target,session,base,replacement())
        assertEquals("12345678901234567",bank.resolve(source,target,session,base)?.id)
        assertNull(bank.resolve(source.copy(root="https://other.example"),target,session,base))
        assertNull(bank.resolve(source,target.copy(canonicalUrl="https://www.instagram.com/p/OtherCode/"),session,base))
        assertNull(bank.resolve(source,target,session,base.copy(name="AnotherReadonlyOperation")))
        assertNull(bank.resolve(source,target,session,base.copy(variables=base.variables.deepCopy().apply {addProperty("sort_order","popular")})))
        assertEquals(0L,gateway.protectionStats().checked)
    }
    @Test fun copiesCannotChangeTheRememberedQueryOrCarryPaginationIntoAnotherRead() {
        val gateway=gateway();val bank=InstagramPublicQueries({100},gateway::sessionFor);val session=gateway.sessionFor(source.root)
        val learned=replacement();bank.remember(source,target,session,base,learned)
        learned.variables.addProperty("after","caller mutation")
        val read=bank.resolve(source,target,session,base)!!;assertFalse(read.variables.has("after"))
        read.variables.addProperty("media_id","different post")
        assertEquals(base.variables,bank.resolve(source,target,session,base)!!.variables.deepCopy().apply {remove("__relay_internal__pv__ExampleFeatureRelayrelayprovider")})
    }
    @Test fun expiryIsAbsoluteAndClockRollbackCannotReviveAnOldTemplate() {
        val gateway=gateway();var now=100L;val bank=InstagramPublicQueries({now},gateway::sessionFor,ttl=100);val session=gateway.sessionFor(source.root)
        bank.remember(source,target,session,base,replacement());now=190
        bank.remember(source,target,session,base,replacement());assertNotNull(bank.resolve(source,target,session,base))
        now=200;assertNull(bank.resolve(source,target,session,base));assertEquals(0,bank.size())
        now=300;bank.remember(source,target,session,base,replacement());now=299
        assertNull(bank.resolve(source,target,session,base));now=301;assertNull(bank.resolve(source,target,session,base))
    }
    @Test fun actualGatewayModeRoundTripInvalidatesOldAdmissionAndLookup() {
        val gateway=gateway();val bank=InstagramPublicQueries({100},gateway::sessionFor);val old=gateway.sessionFor(source.root)
        bank.remember(source,target,old,base,replacement())
        gateway.configure(NetworkPolicy(tor=true));gateway.configure(NetworkPolicy())
        val current=gateway.sessionFor(source.root);assertNotSame(old,current)
        assertNull(bank.resolve(source,target,current,base))
        bank.remember(source,target,old,base,replacement());assertNull(bank.resolve(source,target,current,base))
        val newer=replacement("22345678901234567");bank.remember(source,target,current,base,newer)
        bank.forget(source,target,old,base,newer);assertEquals(newer,bank.resolve(source,target,current,base))
    }
    @Test fun staleFailureCannotForgetANewerQualifiedReplacement() {
        val gateway=gateway();val bank=InstagramPublicQueries({100},gateway::sessionFor);val session=gateway.sessionFor(source.root)
        val old=replacement();val newer=replacement("22345678901234567")
        bank.remember(source,target,session,base,old);bank.remember(source,target,session,base,newer)
        bank.forget(source,target,session,base,old);assertEquals(newer,bank.resolve(source,target,session,base))
        bank.forget(source,target,session,base,newer);assertNull(bank.resolve(source,target,session,base))
    }
    @Test fun fixedDefaultsAreNotStoredAndCapacityNeverGrowsWithBrowsingHistory() {
        val gateway=gateway();val bank=InstagramPublicQueries({100},gateway::sessionFor,maxEntries=2);val session=gateway.sessionFor(source.root)
        bank.remember(source,target,session,base,base);assertEquals(0,bank.size())
        val targets=(0..2).map {target.copy(canonicalUrl="https://www.instagram.com/p/OtherCode$it/")}
        targets.forEach {bank.remember(source,it,session,base,replacement())}
        assertEquals(2,bank.size());assertNull(bank.resolve(source,targets.first(),session,base));assertNotNull(bank.resolve(source,targets.last(),session,base))
    }
    @Test fun metadataCannotChangeIdentityOrderOrWindowOrInstallUnapprovedVariables() {
        val gateway=gateway();val bank=InstagramPublicQueries({100},gateway::sessionFor);val session=gateway.sessionFor(source.root)
        val invalid=listOf(
            replacement().copy(id="not-a-document"),replacement().copy(name="AnotherOperation"),
            replacement().copy(variables=base.variables.deepCopy().apply {addProperty("media_id","999")} ),
            replacement().copy(variables=base.variables.deepCopy().apply {addProperty("sort_order","popular")} ),
            replacement().copy(variables=base.variables.deepCopy().apply {addProperty("first",1000)} ),
            replacement().copy(variables=base.variables.deepCopy().apply {addProperty("after","opaque previous page")} ),
            replacement().copy(variables=base.variables.deepCopy().apply {addProperty("__relay_internal__pv__PolarisIsLoggedInrelayprovider",true)} ),
            replacement().copy(variables=base.variables.deepCopy().apply {addProperty("__relay_internal__pv__BadFlagrelayprovider","true")} )
        )
        invalid.forEach {bank.remember(source,target,session,base,it);assertNull(bank.resolve(source,target,session,base))}
    }
}
