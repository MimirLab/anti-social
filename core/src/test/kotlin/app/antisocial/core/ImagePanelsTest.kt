/* anti social, GPL-3.0-or-later. Complete source-panel presentation guards. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ImagePanelsTest {
    private fun panels()=(1..20).map {Media("https://images.fixture.invalid/panel-$it.jpg",description="Panel $it",sourceLayout=SourceImageLayout(150,2000,it,20))}
    @Test fun completePanelsKeepEveryOriginalInPublishedOrderWithoutChangingMediaIdentity() {
        val media=panels();val before=media.toList();val layout=requireNotNull(ImagePanels.layout(media))
        assertEquals(1.5f,layout.aspectRatio,0f);assertEquals(media,layout.images);assertEquals(before,media)
        assertTrue(media.zip(layout.images).all {(a,b)->a===b})
        assertEquals(20,layout.images.map {it.url}.distinct().size)
    }
    @Test fun unknownOrIncompleteSequencesNeverBecomePanelPictures() {
        val p=panels()
        val invalid=listOf(emptyList(),p.take(3),p.take(19),p+p.last(),p.reversed(),p.map {it.copy(sourceLayout=null)},
            p.dropLast(1)+p.first(),p.dropLast(1)+p.last().copy(type="video"),
            p.dropLast(1)+p.last().copy(sourceLayout=p.last().sourceLayout!!.copy(position=19)),
            p.dropLast(1)+p.last().copy(sourceLayout=p.last().sourceLayout!!.copy(height=2001)),
            p.map {it.copy(sourceLayout=it.sourceLayout!!.copy(width=0))},
            p.map {it.copy(sourceLayout=it.sourceLayout!!.copy(width=10000))},
            p.map {it.copy(sourceLayout=it.sourceLayout!!.copy(width=1,height=Int.MAX_VALUE))},
            p.map {it.copy(sourceLayout=it.sourceLayout!!.copy(width=1500))},
            p.map {it.copy(sourceLayout=it.sourceLayout!!.copy(width=40))})
        for(media in invalid)assertNull(ImagePanels.layout(media))
    }
    @Test fun optionalLayoutSurvivesSavedJsonAndLegacyMediaStillHasNoLayout() {
        val gson=Gson();val old="""{"url":"https://images.fixture.invalid/original.jpg","type":"image","description":"Original"}"""
        val legacy=gson.fromJson(old,Media::class.java);assertNull(legacy.sourceLayout)
        assertFalse(gson.toJson(legacy).contains("sourceLayout"))
        for(media in panels())assertEquals(media,gson.fromJson(gson.toJson(media),Media::class.java))
        val malformed=gson.fromJson("""{"url":"https://images.fixture.invalid/panel.jpg","type":"image","sourceLayout":{}}""",Media::class.java)
        assertNull(ImagePanels.layout(List(20){malformed}))
    }
}
