package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Recorded source packets remain private external inputs, never source fixtures. */
class LocalBusinessPacketTest {
    @Test fun suppliedPrivatePacketGroupsTheRequestedChainWithoutChangingLocations() {
        val path=System.getenv("ANTISOCIAL_PRIVATE_BUSINESS_PACKET")
        val name=System.getenv("ANTISOCIAL_PRIVATE_CHAIN_NAME")
        assumeTrue("Supply a private recorded packet and chain name explicitly",!path.isNullOrBlank()&&!name.isNullOrBlank())
        val file=File(requireNotNull(path));require(file.length() in 1..6L*1024*1024)
        val rows=Gson().fromJson(file.readText(),Array<LocalBusiness>::class.java).toList();require(rows.size in 2..2000)
        val expected=System.getenv("ANTISOCIAL_PRIVATE_CHAIN_MEMBERS")?.takeIf(String::isNotBlank)?.let {Gson().fromJson(File(it).readText(),Array<String>::class.java).toSet()}
        if(expected!=null)require(expected.size>=2&&expected.all {id->rows.any {it.id==id}})
        fun matches(b:LocalBusiness)=expected?.contains(b.id) ?: LocalBusinessOwnership.sameBrand(requireNotNull(name),LocalBusinessDeck.label(b))
        val target=rows.filter(::matches);assertTrue("The packet must contain multiple matching locations",target.size>=2)
        val area=System.getenv("ANTISOCIAL_PRIVATE_BUSINESS_AREA")?.takeIf(String::isNotBlank)?.let {Gson().fromJson(File(it).readText(),LocalArea::class.java)}
        val groups=if(area==null)LocalBusinessDeck.groups(rows)else LocalBusinessDeck.groupsForArea(rows,area.point,area.countryCode)
        val cards=groups.filter {it.locations.any(::matches)}
        assertEquals("The recorded chain must occupy one reading card",1,cards.size)
        assertTrue("The chain retains exactly its original location facts",cards.single().locations.toSet()==target.toSet())
        assertTrue("Other businesses retain their original location facts",groups.flatMap {it.locations}.toSet()==rows.toSet())
    }
}
