/* anti social, GPL-3.0-or-later. Detection projections never alter originals. */
package app.antisocial.core

import java.text.Normalizer
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.parser.Parser

/** A cheap additional detection route. Chunk overlap nominates a copy; claim,
 * destination, publisher/time and composition checks still decide admission. */
internal object DecoratedPostCopies {
    private val protected=setOf(0x2705,0x2714,0x2611,0x274c,0x274e,0x2715,0x2716,0x2717,0x2795,0x2796,0x2797,0x1f6ab,0x26d4,0x1f44d,0x1f44e,0x1f534,0x1f7e2,0x1f7e1)
    private val extras=setOf(0x25b6,0x25ba,0x25c0,0xfe0e,0xfe0f)
    private val tags=Regex("(?:\\s+#[\\p{L}_]+){1,8}\\s*$")
    private val words=Regex("[\\p{L}\\p{N}]+(?:'[\\p{L}\\p{N}]+)*")
    private val numbers=Regex("(?:[<>!]=?|[≤≥≠≈=])?\\s*(?:[+\\-−\\p{Sc}]\\s*){0,3}\\p{N}+(?:[.,]\\p{N}+)*(?:\\s*[%‰\\p{Sc}])?")
    private val negatives=setOf("no","not","never","without","cannot","can't","don't","doesn't","didn't","won't","wouldn't","shouldn't","isn't","aren't","wasn't","weren't","hasn't","haven't","hadn't","mustn't","couldn't")
    data class Prepared(val post:Post,val text:String,val tokens:List<String>,val chunks:IntArray,val resources:Set<String>,val claims:List<String>,val symbols:String,val questions:Int,val changed:Boolean)
    data class Match(val coverage:Double,val exact:Boolean,val sharedResource:Boolean)
    private fun decorative(c:Int)=(c in 0x1f000..0x1faff||c in 0x2600..0x27bf||c in extras)&&c !in protected&&
        Character.getType(c)!=Character.MATH_SYMBOL.toInt()&&Character.getType(c)!=Character.CURRENCY_SYMBOL.toInt()
    private fun clean(value:String):String {
        var decoded=value;repeat(2){decoded=Parser.unescapeEntities(decoded,false)}
        val out=StringBuilder()
        val points=Normalizer.normalize(decoded,Normalizer.Form.NFKC).codePoints().toArray()
        points.forEachIndexed {i,c->
            val emojiJoin=c==0x200d&&(points.getOrNull(i-1)?.let(::decorative)==true||points.getOrNull(i+1)?.let(::decorative)==true)
            if(decorative(c)||emojiJoin)out.append(' ')else out.appendCodePoint(c)
        }
        return tags.replace(out.toString(),"")
    }
    /** Nomination admission only. Plain ASCII prose needs no decoding, URL
     * inventory, token windows or second matching bank. Its publisher's plain
     * editions are prepared later only if a decorative counterpart exists. */
    fun mayChange(post:Post):Boolean {
        if(post.preview||post.parentId!=null||post.body.length.toLong()+post.title.length>4096)return false
        fun changed(value:String)=value.any {it=='#'||it=='&'||it.code>=0x2000}&&clean(value)!=value
        return changed(post.title)||changed(post.body)
    }
    private fun resource(raw:String,post:Post):String? {
        if(CrossPosts.opaqueLink(raw)||CrossPosts.originalLink(raw,post)||CrossPosts.nitterMentionLink(post,raw))return null
        val url=raw.toHttpUrlOrNull()?.takeIf {it.username.isEmpty()&&it.password.isEmpty()} ?: return null
        SocialLinkHosts.platform(raw)?.let {if(!ContentIdentity.key(it,raw).startsWith("post:"))return null}
        return url.newBuilder().fragment(null).apply {
            url.queryParameterNames.filter {it.startsWith("utm_",true)||it.lowercase(Locale.ROOT) in setOf("fbclid","gclid","igshid")}.forEach(::removeAllQueryParameters)
        }.build().toString()
    }
    fun prepare(post:Post):Prepared?=prepare(post,false)
    /** A separate projection for corroborated short announcement editions;
     * ordinary detector admission keeps its existing bounds. */
    fun short(post:Post):Prepared?=prepare(post,true)?.takeIf {it.tokens.size<12}
    private fun prepare(post:Post,short:Boolean):Prepared? {
        if(post.preview||post.parentId!=null||post.body.length.toLong()+post.title.length>4096)return null
        val copy=post.copy(title=clean(post.title),body=clean(post.body))
        val content=PostPresentation.content(copy)
        val raw=TextWhitespace.collapse(PostPresentation.proseWithoutLinks(content)).replace('’','\'').lowercase(Locale.ROOT).trim()
        if(short&&(raw.endsWith("…")||raw.endsWith("...")))return null
        val text=if(short)raw.trimEnd('.','!',' ')else raw
        val tokens=ArrayList<String>();val matcher=words.toPattern().matcher(text)
        while(tokens.size<257&&matcher.find())tokens+=matcher.group()
        if(text.length<(if(short)12 else 40)||tokens.size !in (if(short)3 else 8)..256||tokens.distinct().size<(if(short)3 else 6))return null
        val attached=post.media.flatMap {listOfNotNull(it.url,it.preview)}.toSet()
        val resources=(PostPresentation.destinationLabels(PostPresentation.content(post)).toList()+post.links.orEmpty().take(16))
            .filterNot {it in attached}.mapNotNull {resource(it,post)}.toSet()
        val symbols=StringBuilder().also {out->text.codePoints().forEach {c->val type=Character.getType(c);if(c in protected||type==Character.MATH_SYMBOL.toInt()||type==Character.CURRENCY_SYMBOL.toInt())out.appendCodePoint(c)}}.toString()
        return Prepared(post,text,tokens,tokens.windowed(3).map {it.joinToString("\u0001").hashCode()}.sorted().toIntArray(),resources,
            numbers.findAll(text).map {TextWhitespace.collapse(it.value).replace(" ","")}.toList()+tokens.filter {it in negatives},symbols,text.count {it=='?'},
            copy.title!=post.title||copy.body!=post.body||raw!=text)
    }
    fun anchors(value:Prepared)=value.chunks.distinct().sorted().take(8)
    fun compare(a:Prepared,b:Prepared):Match? {
        if(a.claims!=b.claims||a.symbols!=b.symbols||a.questions!=b.questions)return null
        if(a.resources!=b.resources)return null
        val shared=a.resources.intersect(b.resources).isNotEmpty()
        if(a.text==b.text)return Match(1.0,true,shared)
        if(minOf(a.tokens.size,b.tokens.size)<24||minOf(a.text.length,b.text.length)<160)return null
        // An inserted heading or retained addition can be composed. A word
        // substitution can change a person, mission, status or ordinal even
        // when it is not expressed as a digit or a conventional negation.
        val short=if(a.tokens.size<=b.tokens.size)a.tokens else b.tokens
        val long=if(a.tokens.size<=b.tokens.size)b.tokens else a.tokens
        var matched=0
        for(token in long)if(matched<short.size&&token==short[matched])matched++
        if(matched!=short.size)return null
        var i=0;var j=0;var common=0
        while(i<a.chunks.size&&j<b.chunks.size)when {
            a.chunks[i]<b.chunks[j]->i++
            a.chunks[i]>b.chunks[j]->j++
            else->{common++;i++;j++}
        }
        val coverage=common.toDouble()/maxOf(a.chunks.size,b.chunks.size)
        // Hashes are candidate/ranking hints only. The literal passage aligner
        // must confirm actual text, ordering and noncompeting source additions.
        if(coverage<.90||!CrossPostFusion.matchesWithContext(a.post,b.post))return null
        return Match(coverage,false,shared)
    }
    fun compose(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS)return null
        if(posts.none(::mayChange))return null
        val values=posts.map {prepare(it) ?: return null}
        if(values.none {it.changed})return null
        for(i in values.indices)for(j in i+1 until values.size)if(compare(values[i],values[j])?.exact!=true)return null
        // Display one actual source edition. Cleaning is for detection; full
        // originals, including every decorative variant, remain inspectable.
        val preferred=posts.indices.minWith(compareByDescending<Int> {PostPresentation.destinationLabels(PostPresentation.content(posts[it])).count {url->resource(url,posts[it])!=null}}
            .thenBy {PostPresentation.text(posts[it]).title.isNotBlank()}.thenBy {it})
        return PostFusion(listOf(FusionSegment(PostPresentation.content(posts[preferred]),posts.mapTo(linkedSetOf()){it.platform},true,posts.mapTo(linkedSetOf(),CrossPosts::originalKey))))
    }
    /** Presentation only after collection; never used by the pair matcher. */
    fun composeShort(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS)return null
        val values=posts.map {short(it) ?: return null}
        if(values.none {it.changed})return null
        for(i in values.indices)for(j in i+1 until values.size)if(compare(values[i],values[j])?.exact!=true)return null
        val preferred=posts.indices.minWith(compareBy<Int>{PostPresentation.text(posts[it]).title.isNotBlank()}.thenBy {CrossPosts.originalKey(posts[it])})
        return PostFusion(listOf(FusionSegment(PostPresentation.content(posts[preferred]),posts.mapTo(linkedSetOf()){it.platform},true,posts.mapTo(linkedSetOf(),CrossPosts::originalKey))))
    }
}
