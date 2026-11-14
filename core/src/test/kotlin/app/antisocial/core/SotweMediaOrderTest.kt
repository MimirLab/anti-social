/* anti social, GPL-3.0-or-later. Synthetic public-source media order. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SotweMediaOrderTest {
    private val target=Target(Platform.X,TargetKind.PROFILE,"panel_fixture","https://x.com/panel_fixture")
    private val provenance=Provenance(Source(Platform.X,"sotwe-api","https://frontend.fixture.invalid"),1792179740000,"https://frontend.fixture.invalid/api/v3/user/panel_fixture/")
    private fun photo(index:Int,link:String="https://x.com/panel_fixture/status/881/photo/$index")="""{"id":"$index","type":"photo","mediaURL":"https://images.fixture.invalid/panel-$index.jpg","expandedURL":"$link","extAltText":"Panel $index","imageSize":{"width":150,"height":2000}}"""
    private fun payload(rows:List<String>)=JsonParser.parseString("""{"key":"panel_fixture","info":{"id":"12","screenName":"panel_fixture","name":"Public workshop"},"data":[{"id":"881","createdAt":1792179740000,"text":"Workshop panels","mediaEntities":[${rows.joinToString(",")}]}]}""").asJsonObject
    private fun parse(data:JsonObject)=ProtocolDiscoveryParsing.sotwe(data,target,provenance).posts.single()
    private fun urls(order:List<Int>)=order.map {"https://images.fixture.invalid/panel-$it.jpg"}

    @Test fun numberedPhotoLinksRestorePublishedOrderWithoutChangingSourceOrMedia() {
        val data=payload(listOf(3,1,2).map(::photo));val before=data.deepCopy()
        val post=parse(data)
        assertEquals(urls(listOf(1,2,3)),post.media.map {it.url})
        assertEquals(listOf("Panel 1","Panel 2","Panel 3"),post.media.map {it.description})
        assertEquals("Workshop panels",post.body);assertEquals(provenance,post.provenance)
        assertEquals("https://x.com/panel_fixture/status/881",post.canonicalUrl)
        assertEquals(before,data)
        assertEquals(listOf(1,2,3),post.media.map {it.sourceLayout?.position})
        assertTrue(post.media.all {it.sourceLayout?.let {size->size.width==150&&size.height==2000&&size.count==3}==true})
    }
    @Test fun ambiguousForeignOrPartialPositionClaimsKeepTheReturnedSequence() {
        val order=listOf(3,1,2)
        for(bad in listOf("https://x.com/panel_fixture/status/882/photo/3","https://x.com/other_fixture/status/881/photo/3", "https://foreign.fixture.invalid/panel_fixture/status/881/photo/3", "http://x.com/panel_fixture/status/881/photo/3", "https://x.com/panel_fixture/status/881/photo/3?photo=1", "https://x.com/panel_fixture/status/881/photo/3#other", "https://x.com/panel_fixture/status/881/photo/1", "https://x.com/panel_fixture/status/881/photo/8", "")) {
            val post=parse(payload(listOf(photo(3,bad),photo(1),photo(2))))
            assertEquals(bad,urls(order),post.media.map {it.url});assertTrue(post.media.all {it.sourceLayout==null})
        }
    }
    @Test fun canonicalLegacyHostAndAccountCaseRemainUsable() {
        val rows=listOf(photo(3),photo(1,"https://twitter.com/PANEL_FIXTURE/status/881/photo/1"),photo(2))
        assertEquals(urls(listOf(1,2,3)),parse(payload(rows)).media.map {it.url})
    }
    @Test fun completeTwentyPhotoSequenceIsBoundedAndOverflowIsNotGuessed() {
        val twenty=(1..20).toList();assertEquals(urls(twenty),parse(payload(twenty.reversed().map(::photo))).media.map {it.url})
        val oversized=(21 downTo 1).toList();val post=parse(payload(oversized.map(::photo)))
        assertEquals(urls(oversized.take(20)),post.media.map {it.url})
    }
    @Test fun suppliedRecordedPhotoSequenceMatchesItsExplicitSourcePositions() {
        val path=System.getenv("ANTISOCIAL_PRIVATE_PANEL_SOURCE").orEmpty()
        assumeTrue("An explicit private source-order packet is required",path.isNotBlank())
        val file=File(path);require(file.length() in 1..4*1024*1024)
        val packet=JsonParser.parseReader(file.reader()).asJsonObject
        val data=packet.getAsJsonObject("response");val account=packet["account"].asString
        val before=data.deepCopy();val selected=Target(Platform.X,TargetKind.PROFILE,account,"https://x.com/$account")
        val posts=ProtocolDiscoveryParsing.sotwe(data,selected,provenance).posts
        val post=posts.single {it.canonicalUrl=="https://x.com/$account/status/${packet["id"].asString}"}
        assertEquals(packet.getAsJsonArray("expectedPhotoUrls").map {it.asString},post.media.map {it.url})
        assertEquals(20,post.media.size);assertEquals(20,post.media.map {it.url}.distinct().size)
        assertEquals(before,data)
        assertNotNull(ImagePanels.layout(post.media))
    }
}
