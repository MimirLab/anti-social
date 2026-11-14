/* anti social, GPL-3.0-or-later. Complete photo layout on the independent public route. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FxImagePanelsTest {
    private fun asset(name:String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    private val source=Source(Platform.X,"fxembed-api","https://frontend.fixture.invalid")
    private val provenance=Provenance(source,1792179740000,source.root)
    private fun payload():JsonObject {
        val photos=(1..20).joinToString(","){i->"""{"type":"photo","url":"https://images.fixture.invalid/panel-$i.jpg","width":150,"height":2000}"""}
        return JsonParser.parseString("""{"type":"status","provider":"twitter","id":"881","url":"https://x.com/panel_fixture/status/881","author":{"screen_name":"panel_fixture","name":"Public workshop"},"text":"Workshop panels","media":{"photos":[$photos],"all":[$photos]}}""").asJsonObject
    }
    @Test fun completeMatchingMediaArraysRetainEveryPhotoAndItsPublishedLayout() {
        val data=payload();val before=data.deepCopy();val post=ProtocolDiscoveryParsing.fxStatus(data,Platform.X,provenance,routes)!!
        assertEquals(20,post.media.size);assertEquals((1..20).toList(),post.media.map {it.sourceLayout!!.position})
        assertNotNull(ImagePanels.layout(post.media));assertEquals("Workshop panels",post.body);assertEquals(before,data)
    }
    @Test fun incompleteConflictingOrMixedArraysNeverBecomeAPanelSequence() {
        val changes=listOf<(JsonObject)->Unit>(
            {it.remove("all")},{it.getAsJsonArray("all").remove(19)},
            {it.getAsJsonArray("all")[0].asJsonObject.addProperty("url","https://images.fixture.invalid/other.jpg")},
            {it.getAsJsonArray("all")[0].asJsonObject.addProperty("type","video")},
            {it.getAsJsonArray("all")[0].asJsonObject.addProperty("height",2001)},
            {it.getAsJsonArray("all").add(it.getAsJsonArray("all")[0].deepCopy())})
        for(change in changes) {
            val data=payload();change(data.getAsJsonObject("media"));val before=data.deepCopy()
            val post=ProtocolDiscoveryParsing.fxStatus(data,Platform.X,provenance,routes)!!
            assertEquals(20,post.media.size);assertTrue(post.media.all {it.sourceLayout==null});assertEquals(before,data)
        }
    }
    @Test fun suppliedRecordedAlternateRouteMatchesTheSameCompletePhotoSequence() {
        val path=System.getenv("ANTISOCIAL_PRIVATE_FX_PANEL_SOURCE").orEmpty()
        assumeTrue("An explicit private alternate-source packet is required",path.isNotBlank())
        val file=File(path);require(file.length() in 1..4*1024*1024)
        val packet=JsonParser.parseReader(file.reader()).asJsonObject;val data=packet.getAsJsonObject("response");val before=data.deepCopy()
        val post=ProtocolDiscoveryParsing.fxStatus(data,Platform.X,provenance,routes)!!
        assertEquals(packet.getAsJsonArray("expectedPhotoUrls").map {it.asString},post.media.map {it.url})
        assertEquals(20,post.media.size);assertNotNull(ImagePanels.layout(post.media));assertEquals(before,data)
    }
}
