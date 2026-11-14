/* anti social, GPL-3.0-or-later. Presentation of complete source-numbered image panels. */
package app.antisocial.core

data class ImagePanelLayout(val images:List<Media>,val aspectRatio:Float)

/** No pixel matching, guessed order, new media identity or discarded originals. */
object ImagePanels {
    fun layout(media:List<Media>):ImagePanelLayout? {
        if(media.size !in 4..20||media.any {it.type!="image"}||media.map {it.url}.distinct().size!=media.size)return null
        val sizes=media.map {it.sourceLayout ?: return null}
        val height=sizes.first().height
        if(height !in 64..32768)return null
        for((index,size)in sizes.withIndex()) {
            if(size.count!=media.size||size.position!=index+1||size.height!=height||size.width !in 1..8192)return null
            if(size.width.toDouble()/height !in .02..(1.0/6.0))return null
        }
        val aspect=sizes.sumOf {it.width}.toDouble()/height
        if(aspect !in .75..3.0)return null
        return ImagePanelLayout(media.toList(),aspect.toFloat())
    }
}
