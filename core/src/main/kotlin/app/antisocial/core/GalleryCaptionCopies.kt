/* anti social, GPL-3.0-or-later. Literal gallery excerpts, never generated prose. */
package app.antisocial.core

import org.jsoup.parser.Parser
import java.util.Locale

/** A proven gallery picture can carry a shorter heading on another service.
 * This only describes captions; CrossPosts separately proves every picture,
 * the time window, original identity and unambiguous gallery membership. */
internal object GalleryCaptionCopies {
    private data class Caption(val post:Post,val text:String,val key:String)
    private val urls=Regex("https://[^\\s<>\"']+",RegexOption.IGNORE_CASE)
    private val words=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val unsafe=Regex("\\b(?:no|not|never|without|false|fake|untrue|misleading|debunked|disputed|rejected|cancelled|canceled|correction|fact.check)\\b",RegexOption.IGNORE_CASE)
    private fun caption(post:Post):Caption? {
        if(post.preview||post.body.length.toLong()+post.title.length>4096)return null
        // Media enclosure links are not editorial destinations. Keep all URLs
        // in the untouched originals; only known attribution/campaign links
        // may be absent from the composed caption.
        if(PostLinks.urls(post).any {url->post.media.none {it.url==url||it.preview==url}&&
                !CrossPosts.opaqueLink(url)&&!CrossPosts.originalLink(url,post)})return null
        var text=PostPresentation.content(post);repeat(2){text=Parser.unescapeEntities(text,false)}
        text=urls.replace(text){match->if(CrossPosts.opaqueLink(match.value)||CrossPosts.originalLink(match.value,post))""else match.value}
        text=TextWhitespace.collapse(text).trim().replace(Regex("(?:[⬇↓↘👇][\\uFE0E\\uFE0F]?){1,4}$"),"").trimEnd().removeSuffix(":").trimEnd()
        if(text.length>4096||words.findAll(text).take(257).count()>256||text.any {it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'})return null
        val key=text.lowercase(Locale.ROOT)
        if(key.length!=text.length)return null // Source character offsets must remain exact.
        return Caption(post,text,key)
    }
    private fun publisherReply(full:Caption,part:Caption,scope:List<Post>):Boolean {
        val handle=PostPresentation.nitterReplyTo(part.post) ?: return false
        val account=CrossPosts.originalAccount(part.post) ?: return false
        val at=part.post.publishedAt?.takeIf {it>0} ?: return false
        if(account!="${Platform.X.id}:${handle.removePrefix("@").lowercase(Locale.ROOT)}")return false
        // A reply-to-account label does not name a parent post. Require a
        // separately captured full-caption edition from that same publisher;
        // the caller must still prove every gallery picture and its time bound.
        return scope.any {root->root!==part.post&&root.platform==Platform.X&&root.parentId==null&&!root.preview&&
            CrossPosts.originalKey(root)!=CrossPosts.originalKey(part.post)&&CrossPosts.originalAccount(root)==account&&
            root.publishedAt?.let {it>0&&kotlin.math.abs(it-at)<=600_000}==true&&
            PostPresentation.nitterReplyTo(root)==null&&caption(root)?.key==full.key}
    }
    private fun range(full:Caption,part:Caption,scope:List<Post>):IntRange? {
        if(part.key==full.key)return 0 until full.text.length
        if(part.key.length<35||words.findAll(part.key).count()<6)return null
        val start=full.key.indexOf(part.key)
        if(start<0||full.key.indexOf(part.key,start+1)>=0)return null
        val end=start+part.key.length
        // The omitted material must be outside a complete heading/sentence,
        // never a removed word or clause inside the same sentence.
        if(start>0&&(!full.text[start-1].isWhitespace()||full.text.take(start).trimEnd().lastOrNull() !in listOf('.','!')))return null
        if(end<full.text.length&&(full.text[end]!=' '||part.text.lastOrNull() !in listOf('.','!')))return null
        val extra=full.text.take(start)+" "+full.text.drop(end)
        // Every complete caption is bounded to 4096 characters/256 words. A
        // fuller literal introduction is retained and attributed, not removed;
        // its length alone need not defeat independently proven gallery media.
        if(extra.any {it.isDigit()||it=='?'||it=='@'}||
            Regex("[\\p{So}\\p{Sm}\\p{Sc}\\p{Sk}]").containsMatchIn(extra)||
            unsafe.containsMatchIn(extra)&&!(end==full.text.length&&publisherReply(full,part,scope)))return null
        return start until end
    }
    fun contains(anchor:Post,part:Post,scope:List<Post> = emptyList()):Boolean {
        val full=caption(anchor) ?: return false;val short=caption(part) ?: return false
        return short.text.isNotBlank()&&range(full,short,scope)!=null
    }
    fun compose(posts:List<Post>):PostFusion? {
        val captions=posts.map {caption(it) ?: return null}.filter {it.text.isNotBlank()}
        if(captions.size<2||captions.map {it.key}.distinct().size<2)return null
        val full=captions.maxBy {it.text.length}
        val ranges=captions.map {range(full,it,posts) ?: return null}
        val boundaries=(listOf(0,full.text.length)+ranges.flatMap {listOf(it.first,it.last+1)}).distinct().sorted()
        val segments=boundaries.zipWithNext().mapNotNull {(start,end)->
            val text=full.text.substring(start,end).trim();if(text.isEmpty())return@mapNotNull null
            val sources=captions.indices.filter {ranges[it].first<=start&&ranges[it].last+1>=end}.map {captions[it].post}
            if(sources.isEmpty())return null
            FusionSegment(text,sources.mapTo(linkedSetOf()){it.platform},sources.size==posts.size,sources.mapTo(linkedSetOf(),CrossPosts::originalKey))
        }
        return PostFusion(segments)
    }
}
