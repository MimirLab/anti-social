/* anti social, GPL-3.0-or-later. Attachment layout is not a changed claim. */
package app.antisocial.core

import org.jsoup.parser.Parser
import java.util.Locale

/** One edition introduces its attachments with a colon, another with a downward
 * pointer. All actual prose, punctuation and other symbols must still agree. */
internal object AttachmentCaptions {
    private data class Marked(val text:String,val key:String,val colon:Boolean)
    private val pointer=Regex("(?:[⬇↓↘👇][\\uFE0E\\uFE0F]?){1,4}\\s*$")
    private val words=Regex("[\\p{L}\\p{N}]+")
    private fun marked(post:Post):Marked? {
        if(post.preview||post.body.length.toLong()+post.title.length>16_384||post.media.isEmpty()||post.media.any {it.type!="image"})return null
        val text=PostPresentation.content(post).trimEnd()
        var decoded=text;repeat(2){decoded=Parser.unescapeEntities(decoded,false)}
        val colon=decoded.endsWith(':')
        val end=if(colon)decoded.length-1 else pointer.find(decoded)?.range?.first ?: return null
        val prefix=TextWhitespace.collapse(decoded.take(end)).trim().lowercase(Locale.ROOT)
        if(prefix.length<80)return null
        val tokens=words.findAll(prefix).take(1025).map {it.value}.toList()
        if(tokens.size !in 12..1024||tokens.distinct().size<8)return null
        return Marked(text,prefix,colon)
    }
    fun equivalent(first:Post,second:Post):Boolean {
        val a=marked(first) ?: return false;val b=marked(second) ?: return false
        return a.colon!=b.colon&&a.key==b.key
    }
    fun compose(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS)return null
        val values=posts.map {marked(it) ?: return null}
        if(values.map {it.key}.distinct().size!=1||values.map {it.colon}.distinct().size!=2)return null
        return PostFusion(listOf(FusionSegment(values.first {it.colon}.text,posts.mapTo(linkedSetOf()){it.platform},true,posts.mapTo(linkedSetOf(),CrossPosts::originalKey))))
    }
}
