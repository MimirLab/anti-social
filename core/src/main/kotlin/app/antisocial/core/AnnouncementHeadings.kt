/* anti social, GPL-3.0-or-later. Literal announcement editions, no generated prose. */
package app.antisocial.core

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Additional bounded route for a distinctive complete headline followed by a
 * short platform-specific call to action. CrossPosts still requires explicitly
 * linked publishers, separate platforms and close publication times. */
internal object AnnouncementHeadings {
    private val words=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val sentenceEnd=Regex("[.!](?=\\s|$)")
    private val unsafeTail=Regex("\\b(?:no|not|never|without|cannot|can't|don't|doesn't|didn't|won't|isn't|aren't|cancelled|canceled|correction|instead|retracted|updated)\\b",RegexOption.IGNORE_CASE)
    private data class Edition(val head:String,val key:String,val tail:String,val resources:Set<String>?,val shortCall:Boolean,val longHead:Boolean,val articles:Set<String>)
    private fun prepare(post:Post):Edition? {
        if(post.preview||post.parentId!=null||post.title.length.toLong()+post.body.length>4096)return null
        val text=PostPresentation.content(post).trim()
        if(text.endsWith("...")||text.endsWith('…'))return null
        val boundary=sentenceEnd.find(text)?.range?.last?.plus(1) ?: return null
        val head=text.substring(0,boundary)
        val terms=words.findAll(head).map {it.value.lowercase(Locale.ROOT)}.toList()
        if(head.length<35||terms.size !in 7..64||terms.distinct().size<6||head.contains('?'))return null
        val tail=text.substring(boundary).trim()
        val prose=PostPresentation.proseWithoutLinks(tail)
        val shortCall=head.length>=90&&terms.size>=16&&terms.distinct().size>=12&&tail.length<=240&&words.findAll(prose).count()<=24&&!prose.any(Char::isDigit)&&!unsafeTail.containsMatchIn(prose)
        val resources=linkedSetOf<String>()
        val articles=linkedSetOf<String>()
        var rootsOnly=true
        for(link in (CrossPosts.editorialLinks(post)+PostPresentation.destinationLabels(text).toList()).distinct()) {
            if(CrossPosts.opaqueLink(link)||CrossPosts.originalLink(link,post))continue
            val url=link.toHttpUrlOrNull()
            if(url!=null&&url.isHttps&&url.encodedPath!="/"&&url.username.isEmpty()&&url.password.isEmpty())
                articles+=url.newBuilder().host(url.host.removePrefix("www.")).fragment(null).build().toString()
            if(url==null||!url.isHttps||url.encodedPath!="/"||url.query!=null||url.fragment!=null||url.username.isNotEmpty()||url.password.isNotEmpty())rootsOnly=false
            else resources+=url.host
        }
        return Edition(head,TextWhitespace.collapse(head).replace('’','\'').lowercase(Locale.ROOT),tail,resources.takeIf {rootsOnly},shortCall,
            head.length>=50&&terms.size>=10&&terms.distinct().size>=8,articles)
    }
    fun key(post:Post):String?=if(post.media.isEmpty())null else prepare(post)?.key
    fun matches(first:Post,second:Post,sameImage:()->Boolean={false}):Boolean {
        if(first.media.isEmpty()||second.media.isEmpty())return false
        val a=prepare(first) ?: return false;val b=prepare(second) ?: return false
        if(a.key!=b.key)return false
        if(a.shortCall&&b.shortCall&&a.resources!=null&&b.resources!=null&&(a.resources+b.resources).size==1)return true
        return (a.longHead&&b.longHead||a.articles.intersect(b.articles).isNotEmpty())&&sameImage()
    }
    fun compose(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS)return null
        // Most failed caption alignments have no extraction counterpart. Do
        // not parse their headings or build a witness plan for that case.
        if(posts.any {post->post.media.isEmpty()&&posts.none {it.media.isNotEmpty()&&it.platform==post.platform&&it.publishedAt==post.publishedAt}})return null
        val values=posts.map {prepare(it) ?: return null}
        if(values.map {it.key}.distinct().size!=1||!CrossPosts.headingComposition(posts))return null
        CaptionWeave.compose(posts)?.let {return it}
        CaptionWeave.composeQualified(posts)?.let {return it}
        val segments=mutableListOf(FusionSegment(values.first().head,posts.mapTo(linkedSetOf()){it.platform},true,posts.mapTo(linkedSetOf(),CrossPosts::originalKey)))
        // Whole tails remain coupled and attributed. Never recombine changed
        // fragments into a sentence no source published. Links remain visible.
        values.indices.groupBy {TextWhitespace.collapse(values[it].tail)}.forEach {(_,indices)->
            val tail=values[indices.first()].tail
            if(tail.isNotBlank())segments+=FusionSegment(tail,indices.mapTo(linkedSetOf()){posts[it].platform},indices.size==posts.size,indices.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])})
        }
        return PostFusion(segments)
    }
}
