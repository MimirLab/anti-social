/* anti social, GPL-3.0-or-later. Presentation after independent collection only. */
package app.antisocial.core

import org.jsoup.parser.Parser
import java.text.Normalizer

/** Share short literal captions differing at one word's optional comma.
 * Never a candidate, identity or admission signal. The differing complete word
 * stays attributed, so punctuation is neither erased nor silently selected. */
object CaptionFormatting {
    private val spaces=Regex("[\\s\\p{Z}]+")
    private val word=Regex("[\\p{L}]+(?:['’][\\p{L}]+)*")
    private fun text(post:Post):String? {
        if(post.preview||post.parentId!=null||post.title.length.toLong()+post.body.length>512)return null
        if(PostPresentation.text(post).title.isNotBlank())return null
        var value=PostPresentation.content(post);repeat(2){value=Parser.unescapeEntities(value,false)}
        if(value.any {it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'||it.isISOControl()&&it !in "\n\r\t"})return null
        if('@' in value||PostPresentation.urlMatches(value).any())return null
        return Normalizer.normalize(value,Normalizer.Form.NFC).replace(spaces," ").trim().takeIf {it.length in 12..512}
    }
    /** One literal body with detached reference inventories. This does not
     * align different claims or infer where a shortened link leads. */
    fun composeDetachedReferences(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size||
            posts.any {it.preview||it.parentId!=null||it.title.length.toLong()+it.body.length>4096})return null
        val texts=posts.map(PostPresentation::content)
        val parts=texts.map {PostBodyDetails.split(it)}
        if(parts.none {it.hasDetails}||parts.any {it.leadingDetails.isNotEmpty()}||parts.map {it.body}.distinct().size!=1)return null
        val common=parts.first().body
        if(common.length<140||parts.indices.any {parts[it].originalText()!=texts[it]})return null
        val keys=posts.mapTo(linkedSetOf(),CrossPosts::originalKey);val platforms=posts.mapTo(linkedSetOf()){it.platform}
        val segments=mutableListOf(FusionSegment(common,platforms,true,keys))
        parts.indices.filter {parts[it].details.isNotBlank()}.groupBy {parts[it].details}.forEach {(value,rows)->
            segments+=FusionSegment(value,rows.mapTo(linkedSetOf()){posts[it].platform},false,rows.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])})
        }
        for(i in posts.indices) {
            val path=segments.filter {CrossPosts.originalKey(posts[i])in it.originalKeys}.joinToString(" "){it.text}
            if(TextWhitespace.collapse(path).trim()!=TextWhitespace.collapse(texts[i]).trim())return null
        }
        return PostFusion(segments)
    }
    fun compose(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size)return null
        val sources=posts.map {text(it) ?: return null}
        val tokens=sources.map {it.split(' ')}
        if(tokens.first().size !in 3..23||tokens.any {it.size!=tokens.first().size})return null
        val differing=tokens.first().indices.filter {i->tokens.map {it[i]}.distinct().size>1}
        val index=differing.singleOrNull() ?: return null
        val variants=tokens.map {it[index]}.distinct()
        val bases=variants.map {it.removeSuffix(",")}
        if(bases.distinct().size!=1||!word.matches(bases.first())||variants.size!=2||variants.none {it.endsWith(',')})return null
        if(tokens.first().map {it.removeSuffix(",")}.distinct().size<3)return null
        val keys=posts.mapTo(linkedSetOf(),CrossPosts::originalKey);val platforms=posts.mapTo(linkedSetOf()){it.platform}
        val runs=mutableListOf<FusionRun>()
        fun shared(value:String) {if(value.isNotEmpty())runs+=FusionRun(true,listOf(FusionVariant(value,platforms,keys)))}
        shared(tokens.first().take(index).joinToString(" "))
        runs+=FusionRun(false,tokens.indices.groupBy {tokens[it][index]}.map {(value,rows)->
            FusionVariant(value,rows.mapTo(linkedSetOf()){posts[it].platform},rows.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])})
        })
        shared(tokens.first().drop(index+1).joinToString(" "))
        for(i in posts.indices) {
            val original=CrossPosts.originalKey(posts[i])
            val restored=runs.joinToString(" "){it.variants.single {v->original in v.originalKeys}.text}
            if(restored!=sources[i])return null
        }
        val display=runs.joinToString(" "){run->if(run.shared)run.variants.single().text else run.variants.joinToString(" / ","[","]") {
            it.text+" — "+PlatformOrder.sort(it.platforms).joinToString(" · "){p->p.label}
        }}
        return PostFusion(listOf(FusionSegment(display,platforms,false,keys)),runs)
    }
}
