package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class LocalBusinessIdentityTest {
    private val base=LocalBusiness("a","Northstar Coffee","cafe",address="10 Example St",locality="Example City",point=LocalPoint(10.0,20.0),
        websites=listOf("https://northstar.fixture.invalid/branch"),phones=listOf("+1 202 555 0101"))
    private fun matches(expected:Boolean,a:LocalBusiness,b:LocalBusiness) {
        assertEquals(expected,LocalBusinessIdentity.same(a,b))
        assertEquals(expected,LocalBusinessIdentity.same(LocalBusinessIdentity.prepare(a),LocalBusinessIdentity.prepare(b)))
    }
    @Test fun addressBrandAndCanonicalAccountEvidenceRetainTheirRules() {
        matches(true,base,base.copy(id="b",name="The Northstar Coffee Company",address="10 Example Street"))
        matches(true,base,base.copy(id="b",name="Another trading name"))
        matches(false,base,base.copy(id="b",name="Another trading name",websites=emptyList()))
        matches(true,base.copy(websites=emptyList(),socials=listOf("https://twitter.com/example_shop")),base.copy(id="b",name="Another trading name",websites=emptyList(),socials=listOf("https://x.com/example_shop#profile")))
    }
    @Test fun branchesAndSuitesDoNotCollapse() {
        matches(false,base,base.copy(id="b",address="20 Example Street"))
        matches(false,base.copy(address="10 Example Street, Suite A"),base.copy(id="b",address="10 Example Street, Suite B"))
        matches(true,base.copy(address="10 Example Street, Suite A"),base.copy(id="b",address="10 Example Street, Ste a"))
        matches(false,base,base.copy(id="b",point=LocalPoint(10.03,20.0),phones=emptyList()))
        matches(true,base,base.copy(id="b",point=LocalPoint(10.03,20.0)))
    }
    @Test fun omittedHouseLettersNeedTheExistingCorroboration() {
        matches(true,base.copy(address="10A Example Street"),base.copy(id="b"))
        matches(false,base.copy(address="10A Example Street",point=null,phones=emptyList()),base.copy(id="b",point=null,phones=emptyList()))
        matches(false,base.copy(address="10A Example Street"),base.copy(id="b",address="10B Example Street"))
    }
    @Test fun sourceIdentifiersKeepTheirExistingPrecedence() {
        matches(true,base.copy(sourceIds=listOf("shared")),base.copy(id="b",name="Different name",address="999 Elsewhere Road",point=LocalPoint(-10.0,-20.0),sourceIds=listOf("shared")))
        matches(false,base.copy(point=null,address="",phones=emptyList(),websites=emptyList()),base.copy(id="b",point=null,address="",phones=emptyList(),websites=emptyList()))
    }
    @Test fun batchingPreservesDistinctBranchesAndMergesRepeatedEditions() {
        val branches=(0 until 80).map {i->base.copy(id="branch-$i",name="Workshop location $i",address="${i+1} Example Street",websites=emptyList(),phones=emptyList(),point=LocalPoint(10.0+(i/10)*.001,20.0+(i%10)*.001),sourceIds=listOf("branch-$i"))}
        val editions=branches.map {it.copy(id=it.id+"-edition",phones=listOf("+1 202 555 0101"),checkedAt=100)}
        val merged=LocalDiscoveryMerge.businesses(branches+editions)
        assertEquals(80,merged.size)
        assertEquals(branches.map {it.address}.toSet(),merged.map {it.address}.toSet())
        assertTrue(merged.all {it.phones==listOf("+1 202 555 0101")})
    }
}
