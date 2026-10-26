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
        fun matches(b:LocalBusiness)=LocalBusinessOwnership.sameBrand(requireNotNull(name),LocalBusinessDeck.label(b))
        val target=rows.filter(::matches);assertTrue("The packet must contain multiple matching locations",target.size>=2)
        val groups=LocalBusinessDeck.groups(rows)
        val cards=groups.filter {it.locations.any(::matches)}
        assertEquals("The recorded chain must occupy one reading card",1,cards.size)
        assertTrue("The chain retains exactly its original location facts",cards.single().locations.toSet()==target.toSet())
        assertTrue("Other businesses retain their original location facts",groups.flatMap {it.locations}.toSet()==rows.toSet())
    }
}
