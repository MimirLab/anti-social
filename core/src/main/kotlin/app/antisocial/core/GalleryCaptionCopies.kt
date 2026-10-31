/* anti social, GPL-3.0-or-later. Literal gallery excerpts, never generated prose. */
package app.antisocial.core

import org.jsoup.parser.Parser
import java.util.Locale

/** A proven gallery picture can carry a shorter heading on another service.
 * This only describes captions; CrossPosts separately proves every picture,
 * the time window, original identity and unambiguous gallery membership. */
internal object GalleryCaptionCopies {
    private data class Caption(val post:Post,val text:String,val key:String,val paragraphs:Set<Int>)
    private val urls=Regex("https://[^\\s<>\"']+",RegexOption.IGNORE_CASE)
    private val words=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val paragraphBreaks=Regex("(?:\\r?\\n[\\t \\p{Zs}]*){2,}|\\u2029")
    private val unsafe=Regex("\\b(?:no|not|never|without|false|fake|untrue|misleading|debunked|disputed|rejected|cancelled|canceled|correction|fact.check)\\b",RegexOption.IGNORE_CASE)
    private val reminder=Regex("^(?:never|do not) forget\\s+",RegexOption.IGNORE_CASE)
    private val reminderNegation=Regex("\\b(?:cannot|\\p{L}+n['’]t)\\b",RegexOption.IGNORE_CASE)
    private fun reminderIntroduction(value:String):Boolean {
        val text=value.trim();val prefix=reminder.find(text) ?: return false
        // The imperative's negation does not contradict the gallery heading.
        // Claims, corrections or further negation inside it still need proof.
        val remainder=text.substring(prefix.range.last+1)
        return !unsafe.containsMatchIn(remainder)&&!reminderNegation.containsMatchIn(remainder)
    }
    private fun caption(post:Post):Caption? {
        if(post.preview||post.body.length.toLong()+post.title.length>4096)return null
        // Media enclosure links are not editorial destinations. Keep all URLs
        // in the untouched originals; only known attribution/campaign links
        // may be absent from the composed caption.
        if(PostLinks.urls(post).any {url->post.media.none {it.url==url||it.preview==url}&&
                !CrossPosts.opaqueLink(url)&&!CrossPosts.originalLink(url,post)})return null
        var text=PostPresentation.content(post);repeat(2){text=Parser.unescapeEntities(text,false)}
        text=urls.replace(text){match->if(CrossPosts.opaqueLink(match.value)||CrossPosts.originalLink(match.value,post))""else match.value}
        text=text.trim()
        val paragraphs=paragraphBreaks.findAll(text).map {
            TextWhitespace.collapse(text.take(it.range.last+1)).trimEnd().length+1
        }.toSet()
        text=TextWhitespace.collapse(text).trim().replace(Regex("(?:[⬇↓↘👇][\\uFE0E\\uFE0F]?){1,4}$"),"").trimEnd().removeSuffix(":").trimEnd()
        if(text.length>4096||words.findAll(text).take(257).count()>256||text.any {it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'})return null
        val key=text.lowercase(Locale.ROOT)
        if(key.length!=text.length)return null // Source character offsets must remain exact.
        return Caption(post,text,key,paragraphs)
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
    private fun range(full:Caption,part:Caption,scope:List<Post>,complete:Boolean=false):IntRange? {
        if(part.key==full.key)return 0 until full.text.length
        if(part.key.length<(if(complete)12 else 35)||words.findAll(part.key).count()<(if(complete)2 else 6))return null
        val start=full.key.indexOf(part.key)
        if(start<0||full.key.indexOf(part.key,start+1)>=0)return null
        val end=start+part.key.length
        // The omitted material must be outside a complete heading/sentence,
        // never a removed word or clause inside the same sentence.
        if(start>0&&(!full.text[start-1].isWhitespace()||full.text.take(start).trimEnd().lastOrNull() !in (if(complete)listOf('.','!','?')else listOf('.','!'))&&!(complete&&start in full.paragraphs)))return null
        if(end<full.text.length&&(full.text[end]!=' '||part.text.lastOrNull() !in listOf('.','!')&&!(complete&&end+1 in full.paragraphs)))return null
        val extra=full.text.take(start)+" "+full.text.drop(end)
        // Every complete caption is bounded to 4096 characters/256 words. A
        // fuller literal introduction is retained and attributed, not removed;
        // its length alone need not defeat independently proven gallery media.
        // The independent full-album proof may retain a separate framing
        // question as its attributed segment. It never turns the shared
        // heading into a question or removes an edition's qualification.
        if(extra.any {it.isDigit()||it=='?'&&!complete||it=='@'}||
            Regex("[\\p{So}\\p{Sm}\\p{Sc}\\p{Sk}]").containsMatchIn(extra)||
            unsafe.containsMatchIn(extra)&&!(end==full.text.length&&
                (publisherReply(full,part,scope)||start>0&&reminderIntroduction(extra))))return null
        return start until end
    }
    fun contains(anchor:Post,part:Post,scope:List<Post> = emptyList()):Boolean {
        val full=caption(anchor) ?: return false;val short=caption(part) ?: return false
        return short.text.isNotBlank()&&range(full,short,scope)!=null
    }
    /** Only the separate complete, distinct image-set proof can request this
     * shorter literal alignment. Ordinary gallery excerpts keep their limits. */
    fun composeComplete(posts:List<Post>):PostFusion?=compose(posts,true)
    fun compose(posts:List<Post>):PostFusion?=compose(posts,false)
    private fun compose(posts:List<Post>,complete:Boolean):PostFusion? {
        val captions=posts.map {caption(it) ?: return null}.filter {it.text.isNotBlank()}
        if(captions.size<2)return null
        if(complete) {
            val terms=captions.map {words.findAll(it.key).map {m->m.value}.toList()}
            if(captions.size!=posts.size||captions.indices.any {captions[it].key.length<12||terms[it].toSet().size<2}||
                captions.indices.none {captions[it].key.length<35||terms[it].size<6})return null
        }
        if(captions.map {it.key}.distinct().size<2)return if(complete)PostFusion(listOf(FusionSegment(captions.first().text,
            captions.mapTo(linkedSetOf()){it.post.platform},true,captions.mapTo(linkedSetOf()){CrossPosts.originalKey(it.post)})))else null
        val full=captions.maxBy {it.text.length}
        val ranges=captions.map {range(full,it,posts,complete) ?: return null}
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
