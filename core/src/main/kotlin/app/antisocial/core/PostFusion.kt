/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.util.Locale
import java.text.Normalizer

data class FusionSegment(val text: String,val platforms: Set<Platform>,val shared: Boolean,val originalKeys: Set<String> = emptySet())
data class FusionVariant(val text:String,val platforms:Set<Platform>,val originalKeys:Set<String>,val displayText:String?=null) {
    /** Source reconstruction keeps text; link inventories keep references. */
    val visibleText get()=displayText ?: text
}
data class FusionRun(val shared:Boolean,val variants:List<FusionVariant>)
data class PostFusion(val segments: List<FusionSegment>,val weave:List<FusionRun>?=null)

/** Presentation of already-verified source editions. Alignment alone never proves
 * account ownership or image identity; CrossPosts verifies content independently.
 */
object CrossPostFusion {
    internal data class PreparationStats(val computations:Int,val hits:Int,val entries:Int,val bytes:Long,
        val urlComputations:Int,val urlHits:Int,val urlEntries:Int,
        val frameComputations:Int,val frameHits:Int,val frameEntries:Int)
    private class Workspace {
        val documents=java.util.IdentityHashMap<Post,Prepared?>()
        val contexts=HashMap<String,ComparisonContext>()
        val presentations=java.util.IdentityHashMap<Post,PostTextPresentation>()
        val comparisons=java.util.IdentityHashMap<Post,String>()
        val normalizedUrls=HashMap<String,String?>()
        val imageAssets=HashMap<String,String?>()
        val frames=HashMap<Pair<String,Boolean>,VisualFrames.Frame?>()
        var bytes=0L;var computations=0;var hits=0
        var urlComputations=0;var urlHits=0
        var frameComputations=0;var frameHits=0
    }
    private val workspace=ThreadLocal<Workspace>()
    /** Reuse pure caption analysis inside one synchronous operation only.
     * Every match/contradiction guard still runs. No source, history or bitmap
     * survives the operation; nested grouping shares the same bounded workspace. */
    internal fun <T> withPrepared(block:()->T):T {
        if(workspace.get()!=null)return block()
        workspace.set(Workspace())
        return try {block()}finally {workspace.remove()}
    }
    internal fun preparationStats():PreparationStats?=workspace.get()?.let {PreparationStats(it.computations,it.hits,it.documents.size,it.bytes,it.urlComputations,it.urlHits,it.normalizedUrls.size,it.frameComputations,it.frameHits,it.frames.size)}
    /** Parsed comparison evidence shares the existing operation's byte bound.
     * Coarse and detailed reads remain separate: parsing a header cannot certify
     * its raster. Nulls are reusable, but no evidence survives this operation. */
    internal fun decodedFrame(raw:String,detail:Boolean,resolve:()->VisualFrames.Frame?):VisualFrames.Frame? {
        val current=workspace.get() ?: return resolve()
        val key=raw to detail
        if(current.frames.containsKey(key)){current.frameHits++;return current.frames[key]}
        current.frameComputations++
        val result=resolve()
        val bytes=2_048L+raw.length*2L+(result?.detail?.size ?: 0)
        if(current.frames.size<512&&current.bytes+bytes<=8L*1024*1024) {
            current.frames[key]=result;current.bytes+=bytes
        }
        return result
    }
    /** Exact input strings only. Signed parameters and case stay the resolver's
     * concern; this cache neither fetches URLs nor survives the operation. */
    internal fun normalizedUrl(value:String,resolve:()->String?):String? {
        val current=workspace.get() ?: return resolve()
        if(current.normalizedUrls.containsKey(value)){current.urlHits++;return current.normalizedUrls[value]}
        current.urlComputations++
        val result=resolve()
        val bytes=96L+(value.length.toLong()+(result?.length ?: 0))*2
        if(current.normalizedUrls.size<2_048&&current.bytes+bytes<=8L*1024*1024) {
            current.normalizedUrls[value]=result;current.bytes+=bytes
        }
        return result
    }
    internal fun imageAssetKey(value:String,resolve:()->String?):String? {
        val current=workspace.get() ?: return resolve()
        if(current.imageAssets.containsKey(value))return current.imageAssets[value]
        val result=resolve();val bytes=96L+(value.length.toLong()+(result?.length ?: 0))*2
        if(current.imageAssets.size<2_048&&current.bytes+bytes<=8L*1024*1024) {
            current.imageAssets[value]=result;current.bytes+=bytes
        }
        return result
    }
    internal fun presentation(post:Post)=workspace.get()?.presentations?.get(post)
    internal fun rememberPresentation(post:Post,value:PostTextPresentation) {
        val current=workspace.get() ?: return
        val bytes=96L+(value.title.length.toLong()+value.body.length+(value.attribution?.length ?: 0))*2
        if(current.presentations.size<2_048&&current.bytes+bytes<=8L*1024*1024){current.presentations[post]=value;current.bytes+=bytes}
    }
    internal fun comparison(post:Post)=workspace.get()?.comparisons?.get(post)
    internal fun rememberComparison(post:Post,value:String) {
        val current=workspace.get() ?: return
        val bytes=64L+value.length*2L
        if(current.comparisons.size<2_048&&current.bytes+bytes<=8L*1024*1024){current.comparisons[post]=value;current.bytes+=bytes}
    }
    private const val MAX_TEXT=16_384
    private const val MAX_WORDS=1_024
    private const val MAX_ANCHORS=256
    private const val MAX_PARTS=256
    private const val SHINGLE=8
    private const val MIN_WORDS=20
    private const val MIN_CHARS=120
    private val word=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val numericClaim=Regex("(?:[<>!]=?|[≤≥≠≈=])?\\s*(?:[+\\-−\\p{Sc}]\\s*){0,3}\\p{N}+(?:[.,]\\p{N}+)*(?:\\s*[%‰\\p{Sc}])?")
    private val numericWhitespace=Regex("[\\p{Z}\\s]+")
    private val truncatedCaption=Regex("(?:…|\\.\\.\\.)\\s*$")
    private val terminalExclamation=Regex("(?<=[\\p{L}\\p{N}])!(?=\\s|$)")
    private val explicitUrl=Regex("https?://[^\\s<>\"']+",RegexOption.IGNORE_CASE)
    private val mention=Regex("@[\\p{L}\\p{N}_][\\p{L}\\p{N}_.-]{0,255}")
    private val abbreviations=setOf("dr","mr","mrs","ms","prof","st","jr","sr","vs","etc","inc","fig","no","ep")
    private val quotedEnd=Regex("[,;][\"”’']")
    private val negatives=setOf("no","not","never","without","cannot","can't","don't","doesn't","didn't","won't","wouldn't","shouldn't","isn't","aren't","wasn't","weren't","hasn't","haven't","hadn't","mustn't","couldn't")
    private val editorial=Regex("\\b(?:fact[- ]?check(?:ing)?|debunk(?:ed|ing)?|misleading|disinformation|misinformation|disagree|disputed|refut(?:e|ed|ing)|false\\s+claim|(?:this|that|the)\\s+(?:claim|post|statement|quote)\\s+(?:is|was)\\s+(?:false|wrong|untrue)|(?:i|we)\\s+(?:support|oppose|reject|disagree)|this\\s+is\\s+(?:false|wrong|untrue))\\b",RegexOption.IGNORE_CASE)
    private data class Token(val text: String,val start: Int,val end: Int,val url: Boolean)
    private data class Anchor(val text: String,val index: Int)
    private data class Part(val start: Int,val end: Int,val words: Int,val key: String)
    private data class Prepared(val text: String,val words: List<Token>,val gaps: List<String>,val anchors: List<Anchor>,val parts: List<Part>) {
        val partIndex by lazy(LazyThreadSafetyMode.NONE){parts.withIndex().groupBy {it.value.key}}
    }
    private data class ComparisonContext(val words:List<String>,val numbers:List<String>,val urls:List<String>,val question:Boolean)
    private data class Match(val a: Int,val b: Int)
    private data class SourcePart(val source: Int,val part: Int)
    private class Node(val key: String) {
        val sources=mutableListOf<SourcePart>()
        val next=linkedSetOf<Node>()
        var incoming=0
    }

    private fun prepare(text: String,fieldBreak: Int?=null,links: List<String> = emptyList()): Prepared? {
        if(text.isBlank()||text.length>MAX_TEXT)return null
        val urls=PostPresentation.urlMatches(text).take(17).map {it.range}.toList()
        if(urls.size>16)return null
        val mentions=mention.findAll(text).take(65).map {match->
            match.range.first until match.range.last+1-match.value.takeLastWhile {it=='.'}.length
        }.toList()
        if(mentions.size>64)return null
        val words=word.findAll(text).take(MAX_WORDS+1).map {
            val spelling=Normalizer.normalize(it.value,Normalizer.Form.NFC).replace('’','\'')
            val inUrl=urls.any {url->it.range.first in url}
            Token(if(inUrl)spelling else spelling.lowercase(Locale.ROOT),it.range.first,it.range.last+1,inUrl)
        }.toList()
        if(words.size !in MIN_WORDS..MAX_WORDS)return null
        val gaps=(0 until words.size-1).map {i->normalizeGap(text.substring(words[i].end,words[i+1].start))}
        val all=(0..words.size-SHINGLE).map {i->Anchor(buildString {repeat(SHINGLE){j->if(j>0)append(gaps[i+j-1]);append(words[i+j].text)}},i)}
        if(all.any {it.text.length>1_024})return null
        // Content-defined minimizers retain an anchor inside a shared 20-word
        // span even when a source adds a different number of leading words.
        val anchors=linkedMapOf<Int,Anchor>()
        val windows=(all.size-SHINGLE+1).coerceAtLeast(1)
        repeat(windows){start->
            val end=(start+SHINGLE).coerceAtMost(all.size)
            val selected=all.subList(start,end).minWithOrNull(compareBy<Anchor> {it.text}.thenBy {it.index})!!
            anchors[selected.index]=selected
        }
        if(anchors.size>MAX_ANCHORS)return null
        // Whole clauses provide several independently aligned passages instead of
        // one longest substring. Never cut through a changed claim to manufacture
        // a sentence that no source actually wrote.
        val destinations=links.mapNotNull(::canonicalLink).distinct()
        val parts=mutableListOf<Part>()
        var start=0;var firstWord=0
        fun add(lastWord: Int,end: Int) {
            var from=start;var to=end
            while(from<to&&text[from].isWhitespace())from++
            while(to>from&&text[to-1].isWhitespace())to--
            if(to>from) {
                val key=buildString {
                    append(normalizeGap(text.substring(from,words[firstWord].start)))
                    for(i in firstWord..lastWord) {
                        if(i>firstWord)append(gaps[i-1])
                        append(words[i].text)
                    }
                    append(normalizeGap(text.substring(words[lastWord].end,to)))
                }
                val destination=linkKey(text.substring(from,to),destinations)
                parts+=Part(from,to,(firstWord..lastWord).count {!words[it].url},destination?.let {"link:"+it} ?: "words:"+key)
            }
            start=end;firstWord=lastWord+1
        }
        for(i in 0 until words.lastIndex) {
            val from=words[i].end;val to=words[i+1].start
            val gap=text.substring(from,to)
            val inReference=(urls+mentions).any {from in it||to-1 in it}
            val numericSeparator=words[i].text.lastOrNull()?.isDigit()==true&&words[i+1].text.firstOrNull()?.isDigit()==true&&gap in setOf(".",":")
            val abbreviation=gap.trimStart().startsWith('.')&&(words[i].text in abbreviations||words[i].text.length==1)
            val punctuation=!inReference&&!numericSeparator&&!abbreviation&&(gap.any {it in ".!?…:"}||quotedEnd.containsMatchIn(gap))
            val paragraph=fieldBreak?.let {it in from..to}==true||gap.count {it=='\n'}>=2&&!gap.trimEnd().endsWith(',')
            if(punctuation||paragraph) {
                var end=from
                // Retain a sentence's punctuation/closing quote, leaving its
                // following whitespace and the next sentence with that source.
                if(punctuation)while(end<to&&!text[end].isWhitespace()&&!text[end].isLetterOrDigit())end++
                add(i,end)
            }
        }
        add(words.lastIndex,text.length)
        if(parts.size>MAX_PARTS)return null
        return Prepared(text,words,gaps,anchors.values.toList(),parts)
    }
    private fun normalizeQuote(value: Char)=when(value){'’','‘'->'\'';'“','”'->'"';else->value}
    private fun normalizeGap(value: String)=numericWhitespace.replace(value," ").map(::normalizeQuote).joinToString("")
    private fun prepare(post:Post):Prepared? {
        val current=workspace.get() ?: return preparePost(post)
        if(current.documents.containsKey(post)){current.hits++;return current.documents[post]}
        current.computations++
        val result=preparePost(post)
        val bytes=result?.let {128L+it.text.length*2L+it.words.size*80L+it.gaps.sumOf {s->48L+s.length*2L}+it.anchors.sumOf {a->48L+a.text.length*2L}+it.parts.sumOf {p->160L+p.key.length*2L}} ?: 64L
        if(current.documents.size<2_048&&current.bytes+bytes<=8L*1024*1024) {
            current.documents[post]=result;current.bytes+=bytes
        }
        return result
    }
    private fun preparePost(post: Post): Prepared? {
        if(post.body.length.toLong()+post.title.length>MAX_TEXT)return null
        val fields=PostPresentation.text(post)
        fun decoded(value:String):String {var result=value;repeat(2){result=org.jsoup.parser.Parser.unescapeEntities(result,false)};return result}
        val title=decoded(fields.title);val body=decoded(fields.body)
        val boundary=title.takeIf {it.isNotBlank()&&body.isNotBlank()}?.length
        val text=listOf(title,body).filter(String::isNotBlank).joinToString("\n")
        return prepare(text,boundary,post.links.orEmpty().take(32))
    }

    /** Only a supplied destination can expand a shortened display label. Host
     * spelling is cosmetic; path/query case, scheme and meaningful suffixes are
     * retained. Ambiguous prefix labels never establish equivalence.
     */
    private fun canonicalLink(raw: String): String? = runCatching {
        if(raw.length>4_096)return@runCatching null
        val uri=java.net.URI(raw)
        val scheme=uri.scheme?.lowercase(Locale.ROOT)
        if(scheme !in setOf("https","http")||uri.host==null||uri.userInfo!=null)return@runCatching null
        buildString {
            append(scheme);append("://");append(uri.host.lowercase(Locale.ROOT))
            if(uri.port!=-1)append(":"+uri.port)
            append(uri.rawPath.orEmpty());uri.rawQuery?.let {append("?"+it)};uri.rawFragment?.let {append("#"+it)}
        }
    }.getOrNull()
    private fun linkKey(label: String,supplied: List<String>): String? {
        if(supplied.isEmpty())return null
        val value=label.trim()
        if(PostPresentation.urlMatches(value).singleOrNull()?.range!=value.indices)return null
        val exact=canonicalLink(value)
        if(exact!=null&&exact in supplied)return exact
        val short=value.endsWith("...")||value.endsWith('…')
        val scheme=runCatching {java.net.URI(value).scheme?.lowercase(Locale.ROOT)}.getOrNull()?.takeIf {it in setOf("http","https")}
        // An explicit complete URL describes its own scheme. A provided HTTPS
        // href cannot turn an HTTP display URL into equivalent source text.
        if(scheme!=null&&!short)return null
        val withoutScheme=if(scheme!=null)value.substringAfter("://")else value
        val rawPrefix=withoutScheme.removeSuffix("...").removeSuffix("…")
        val prefix=rawPrefix.substringBefore('/').lowercase(Locale.ROOT)+rawPrefix.substringAfter('/',"").let {if('/' in rawPrefix)"/"+it else ""}
        if(prefix.length<12||'/' !in prefix)return null
        val candidates=supplied.filter {target->
            val destination=target.substringAfter("://")
            (scheme==null||target.substringBefore("://")==scheme)&&
                (if(short)destination.startsWith(prefix)else destination==prefix)
        }
        return candidates.singleOrNull()
    }

    /** Bounded lookup hints only. A hash collision cannot establish a match.
     * Sentence hints cover shared short passages separated by source additions;
     * minimizers still cover a shared long passage despite different headings.
     */
    internal fun candidateAnchors(post: Post): Set<String> = prepare(post)?.let {source->buildSet {
        source.anchors.forEach {add(it.text.length.toString()+":"+it.text.hashCode())}
        source.parts.filter {it.words>=3}.forEach {add("part:"+it.key.length+":"+it.key.hashCode())}
    }}.orEmpty()

    private fun contextWords(value: String)=word.findAll(value).map {it.value.lowercase(Locale.ROOT).replace('’','\'')}.toList()
    private fun comparisonContext(value:String):ComparisonContext {
        val current=workspace.get()
        current?.contexts?.get(value)?.let {return it}
        val result=ComparisonContext(contextWords(value),numericClaim.findAll(value).map {numericWhitespace.replace(it.value,"").replace('−','-')}.toList(),
            explicitUrl.findAll(value).map {it.value}.toList(),'?' in explicitUrl.replace(value,""))
        val bytes=96L+value.length*2L+result.words.sumOf {48L+it.length*2L}+result.numbers.sumOf {48L+it.length*2L}+result.urls.sumOf {48L+it.length*2L}
        if(current!=null&&current.contexts.size<2_048&&current.bytes+bytes<=8L*1024*1024){current.contexts[value]=result;current.bytes+=bytes}
        return result
    }
    private fun competingContext(a: String,b: String): Boolean {
        val first=comparisonContext(a);val second=comparisonContext(b)
        val aa=first.words;val bb=second.words
        val numbersA=first.numbers;val numbersB=second.numbers
        // URL paths and queries can be case sensitive even though ordinary
        // prose comparison is not. A changed destination is not typography.
        val urlsA=first.urls;val urlsB=second.urls
        if(aa==bb&&numbersA==numbersB&&urlsA==urlsB)return first.question!=second.question
        if(minOf(aa.size,bb.size)<3) {
            // Short labels still carry facts: “20.” versus “21.” and
            // “Approved.” versus “Not approved.” cannot be silent additions.
            val ordinaryA=aa.filterNot {token->token.any(Char::isDigit)||token in negatives}
            val ordinaryB=bb.filterNot {token->token.any(Char::isDigit)||token in negatives}
            return ordinaryA==ordinaryB&&(
                numbersA.isNotEmpty()&&numbersB.isNotEmpty()&&numbersA!=numbersB||
                    aa.filter {it in negatives}!=bb.filter {it in negatives})
        }
        val ac=aa.groupingBy {it}.eachCount();val bc=bb.groupingBy {it}.eachCount()
        val common=ac.entries.sumOf {(key,count)->minOf(count,bc[key] ?: 0)}
        if(common.toDouble()/minOf(aa.size,bb.size)<.65)return false
        // Closely corresponding clauses are edits, not independent context.
        // In particular a changed number or negation must not disappear into
        // an otherwise identical shared paragraph.
        return numbersA!=numbersB||urlsA!=urlsB||aa.filter {it in negatives}!=bb.filter {it in negatives}||common>=minOf(aa.size,bb.size)-2
    }
    /** Match every complete shared passage in source order. A repeated or
     * reordered passage has no unambiguous position and therefore stays separate.
     * The substantial-overlap gate applies to their union, not each sentence.
     */
    private fun alignment(a: Prepared,b: Prepared,mediaConfirmed: Boolean=false): List<Match>? {
        val namingVariants=mediaConfirmed&&mentionOnlyVariant(a.text,b.text)
        val aa=a.partIndex
        val bb=b.partIndex
        val matches=mutableListOf<Match>()
        for((key,first) in aa) {
            val second=bb[key] ?: continue
            if(first.size!=1||second.size!=1)return null
            matches+=Match(first.single().index,second.single().index)
        }
        matches.sortBy {it.a}
        if(matches.isEmpty()||matches.zipWithNext().any {(first,next)->first.b>=next.b})return null
        val count=matches.sumOf {a.parts[it.a].words}
        val minimum=if(mediaConfirmed)12 else MIN_WORDS
        if(count<minimum||count<minOf(a.words.count {!it.url},b.words.count {!it.url})*(if(mediaConfirmed).45 else .6)||
            mediaConfirmed&&count<maxOf(a.words.count {!it.url},b.words.count {!it.url})*.30)return null
        var endA=0;var endB=0
        for(match in matches) {
            val partA=a.parts[match.a];val partB=b.parts[match.b]
            if(!namingVariants&&competingContext(a.text.substring(endA,partA.start),b.text.substring(endB,partB.start)))return null
            endA=partA.end;endB=partB.end
        }
        if(!namingVariants&&competingContext(a.text.substring(endA),b.text.substring(endB)))return null
        // Reject conflicting additions before scanning the shared passage again.
        // Both guards remain mandatory; this only orders their pure work.
        val common=matches.joinToString(" "){a.text.substring(a.parts[it.a].start,a.parts[it.a].end)}
        val prose=PostPresentation.proseWithoutLinks(common)
        if(prose.length<(if(mediaConfirmed)70 else MIN_CHARS)||contextWords(prose).distinct().size<(if(mediaConfirmed)8 else 12))return null
        return matches
    }

    fun matches(a: Post,b: Post): Boolean {
        val first=prepare(a) ?: return false;val second=prepare(b) ?: return false
        return alignment(first,second)!=null
    }

    private fun compatibleContext(first: Prepared,second: Prepared,matched: List<Match>): Boolean {
        fun extra(document: Prepared,indices: Set<Int>)=document.parts.withIndex().filter {it.index !in indices}.joinToString(" "){document.text.substring(it.value.start,it.value.end)}
        return !editorial.containsMatchIn(extra(first,matched.mapTo(mutableSetOf()){it.a}))&&
            !editorial.containsMatchIn(extra(second,matched.mapTo(mutableSetOf()){it.b}))
    }

    /** Shared editorial language may itself be copied. Unmatched opposition or
     * correction framing instead makes this an independently meaningful post,
     * even when the same verified account supplied both original captions. */
    internal fun matchesWithContext(a: Post,b: Post): Boolean {
        val first=prepare(a) ?: return false;val second=prepare(b) ?: return false
        val matched=alignment(first,second) ?: return false
        return compatibleContext(first,second,matched)
    }
    /** The no-download wording route cannot treat editorial response framing
     * as a cosmetic change. Reuse the same vocabulary as ordinary fusion. */
    internal fun neutralCopyFraming(post:Post):Boolean=prepare(post)?.let {!editorial.containsMatchIn(it.text)}==true
    internal fun compatibleIntroductions(first:String,second:String):Boolean =
        !editorial.containsMatchIn(first)&&!editorial.containsMatchIn(second)&&!competingContext(first,second)

    internal fun relatedCompatible(a: Post,b: Post): Boolean {
        val first=prepare(a) ?: return false;val second=prepare(b) ?: return false
        return !editorial.containsMatchIn(first.text)&&!editorial.containsMatchIn(second.text)&&!competingContext(first.text,second.text)
    }

    internal fun mediaMatched(a: Post,b: Post): Boolean {
        val first=prepare(a) ?: return false;val second=prepare(b) ?: return false
        // A local wording edit or an essay quoting a shorter post cannot become
        // two supposedly independent additions around a manufactured shared run.
        if(competingContext(first.text,second.text)&&!mentionOnlyVariant(first.text,second.text))return false
        fun symbols(text: String)=Regex("[\\p{So}\\p{Sm}\\p{Sc}\\p{Sk}]").findAll(text).joinToString(""){it.value}
        if(symbols(first.text)!=symbols(second.text))return false
        val pair=refineSharedPhrases(listOf(first,second))
        val matched=alignment(pair[0],pair[1],mediaConfirmed=true) ?: return false
        return compatibleContext(pair[0],pair[1],matched)
    }

    /** Only already corroborated media copies enter this exception. Equal
     * surrounding prose permits collection, not an assertion that the mentioned
     * accounts are identical. The ordinary aligner retains every changed span. */
    private fun mentionOnlyVariant(first:String,second:String):Boolean {
        if('@' !in first||'@' !in second)return false
        if(PostPresentation.urlMatches(first).map {it.value}.toList()!=PostPresentation.urlMatches(second).map {it.value}.toList())return false
        fun template(text:String):Pair<String,List<String>>? {
            val urls=PostPresentation.urlMatches(text).map {it.range}.toList()
            val matches=mention.findAll(text).filter {match->
                (match.range.first==0||text[match.range.first-1].let {!it.isLetterOrDigit()&&it !in "_./:@"})&&urls.none {match.range.first in it}
            }.take(9).toList()
            if(matches.size !in 1..8)return null
            val out=StringBuilder();var end=0
            matches.forEachIndexed {index,m->out.append(text.substring(end,m.range.first));out.append("[account-$index]");end=m.range.last+1}
            out.append(text.substring(end))
            val prose=mention.replace(text," ")
            if(prose.length<160||contextWords(prose).size<24)return null
            return TextWhitespace.collapse(Normalizer.normalize(out.toString(),Normalizer.Form.NFC)).trim().lowercase(Locale.ROOT) to matches.map {it.value.lowercase(Locale.ROOT)}
        }
        val a=template(first) ?: return false;val b=template(second) ?: return false
        return a.first==b.first&&a.second.size==b.second.size&&a.second.zip(b.second).count {it.first!=it.second} in 1..4
    }

    fun composeMedia(posts: List<Post>): PostFusion? {
        if(CrossPosts.imageEchoCollection(posts)) {
            val sources=posts.filter {PostPresentation.content(it).any(Char::isLetterOrDigit)}
            return PostFusion(listOf(FusionSegment(PostPresentation.content(sources.first()),sources.mapTo(linkedSetOf()){it.platform},false,sources.mapTo(linkedSetOf(),CrossPosts::originalKey))))
        }
        if(CrossPosts.galleryCollection(posts)) {
            GalleryCaptionCopies.compose(posts)?.let {return it}
            val caption=posts.filter {PostPresentation.content(it).isNotBlank()}.maxBy {it.media.size}
            val sources=posts.filter {PostPresentation.content(it).isNotBlank()}
            return PostFusion(listOf(FusionSegment(PostPresentation.content(caption),sources.mapTo(linkedSetOf()){it.platform},
                sources.size==posts.size,sources.mapTo(linkedSetOf(),CrossPosts::originalKey))))
        }
        if(!CrossPosts.sameContentCopies(posts))return null
        CaptionAliases.compose(posts,mediaConfirmed=true)?.let {return it}
        CaptionWeave.compose(posts)?.let {return it}
        val prepared=refineSharedPhrases(posts.map {prepare(it) ?: return null})
        for(i in prepared.indices)for(j in i+1 until prepared.size)if(alignment(prepared[i],prepared[j],mediaConfirmed=true)==null)return null
        return composePrepared(posts,prepared)
    }

    /** The visual/subject/time proof is independent of phrase alignment. Keeping
     * every unique passage permits related variants without inventing a caption. */
    fun composeRelated(posts: List<Post>): PostFusion? {
        if(!CrossPosts.relatedVisualUpdates(posts))return null
        val prepared=refineSharedPhrases(posts.map {prepare(it) ?: return null})
        // A repeated shared phrase or contradictory ordering has no single safe
        // position. Keep those originals separate rather than silently deleting it.
        for(i in prepared.indices)for(j in i+1 until prepared.size) {
            val a=prepared[i].parts.groupingBy {it.key}.eachCount();val b=prepared[j].parts.groupingBy {it.key}.eachCount()
            if(a.keys.intersect(b.keys).any {a.getValue(it)>1||b.getValue(it)>1})return null
        }
        return composePrepared(posts,prepared)
    }

    /** Audience-specific introductions need not end in a paragraph or a period.
     * Refine already verified collections at unique, literal eight-word passages.
     * Matching uses internal punctuation too; numbers, signs and negations cannot
     * be replaced with a bag of similar words. Bounds remain 64 × 1,024 words. */
    private fun refineSharedPhrases(input: List<Prepared>): List<Prepared> {
        val ranges=Array(input.size){linkedSetOf<Pair<Int,Int>>()}
        fun key(p: Prepared,start: Int,count: Int)=buildString {repeat(count){i->if(i>0)append(p.gaps[start+i-1]);append(p.words[start+i].text)}}
        fun index(p: Prepared)=(0..p.words.size-SHINGLE).groupBy {key(p,it,SHINGLE)}
        val indices=input.map(::index)
        for(a in input.indices)for(b in a+1 until input.size) {
            val left=input[a];val right=input[b]
            for((hint,positions) in indices[a]) {
                val other=indices[b][hint] ?: continue
                if(positions.size!=1||other.size!=1)continue
                var x=positions.single();var y=other.single();var count=SHINGLE
                while(x>0&&y>0&&left.words[x-1].text==right.words[y-1].text&&left.gaps[x-1]==right.gaps[y-1]){x--;y--;count++}
                while(x+count<left.words.size&&y+count<right.words.size&&left.words[x+count].text==right.words[y+count].text&&left.gaps[x+count-1]==right.gaps[y+count-1])count++
                val text=key(left,x,count)
                if(text.length<48||left.words.subList(x,x+count).map {it.text}.distinct().size<6)continue
                // Repeated literal occurrences have no unambiguous position.
                if(indices[a][key(left,x,SHINGLE)]?.size!=1||indices[b][key(right,y,SHINGLE)]?.size!=1)continue
                ranges[a]+=x to x+count;ranges[b]+=y to y+count
            }
        }
        return input.mapIndexed {source,p->
            val cuts=ranges[source].flatMap {(first,last)->listOf(p.words[first].start,p.words[last-1].end)}.toSet()
            val parts=p.parts.flatMap {part->
                val positions=(listOf(part.start,part.end)+cuts.filter {it>part.start&&it<part.end}).distinct().sorted()
                val chunks=positions.zipWithNext().mapNotNull {(start,end)->
                    val words=p.words.filter {it.start>=start&&it.end<=end}
                    val raw=p.text.substring(start,end).trim()
                    if(raw.isEmpty())null else Triple(start,end,words)
                }.toMutableList()
                // Keep punctuation with its phrase, rather than producing a
                // source-context paragraph consisting only of a closing quote.
                var i=0
                while(i<chunks.size){if(chunks[i].third.isEmpty()&&chunks.size>1){val empty=chunks.removeAt(i);if(i==0){val next=chunks[0];chunks[0]=Triple(empty.first,next.second,next.third)}else {val previous=chunks[i-1];chunks[i-1]=Triple(previous.first,empty.second,previous.third)}}else i++}
                chunks.map {(start,end,words)->
                    val raw=p.text.substring(start,end).trim();val semantic=words.joinToString(" "){it.text}
                    // This key is only for literal refined phrases. Include all
                    // punctuation between words, plus question/claim endings.
                    val first=words.firstOrNull();val last=words.lastOrNull()
                    val internal=if(first==null||last==null)normalizeGap(raw)else key(p,p.words.indexOf(first),words.size)
                    val question=if(raw.trimEnd('"','”','\'','’').endsWith('?'))"?"else ""
                    val edge=if(first==null||last==null)""else Regex("[+\\-−<>=≤≥≠≈\\p{Sc}\\p{So}]").findAll(p.text.substring(start,first.start)+p.text.substring(last.end,end)).joinToString(""){it.value}
                    Part(start,end,words.count {!it.url},if(semantic.isEmpty())part.key else "phrase:$edge:$internal$question")
                }
            }
            p.copy(parts=parts)
        }
    }

    /** More conservative than linked-account presentation: an independently
     * copied core must dominate both records, while a rebuttal or an independent
     * essay quoting that core remains its own post. Media/reference corroboration
     * is checked by CrossPosts before this evidence is used. */
    internal fun copiedCore(a: Post,b: Post): Boolean {
        val first=prepare(a) ?: return false;val second=prepare(b) ?: return false
        val matched=alignment(first,second) ?: return false
        val shared=matched.sumOf {first.parts[it.a].words}
        val firstWords=first.words.count {!it.url};val secondWords=second.words.count {!it.url}
        if(shared<24||shared<maxOf(firstWords,secondWords)*.70)return false
        val common=matched.joinToString(" "){first.text.substring(first.parts[it.a].start,first.parts[it.a].end)}
        val prose=PostPresentation.proseWithoutLinks(common)
        if(prose.length<160||contextWords(prose).distinct().size<12)return false
        return compatibleContext(first,second,matched)
    }

    /** Merge the source passages as an ordered graph. Shared passages are one
     * node even if only two of three sources carry them; source-only passages
     * retain their position and platform attribution. All original Post records,
     * URLs and discussion identities remain untouched.
     */
    /** Literal compression of already collected editions. Deliberately separate
     * from pair admission and its corroboration thresholds. */
    fun composeCollectedEditions(posts:List<Post>):PostFusion? = AnnouncementEditions.compose(posts) ?: DecoratedPostCopies.composeShort(posts)
        ?: if(CrossPosts.resourceAnnouncementCollection(posts))ResourceAnnouncements.compose(posts)else null
        ?: if(posts.any {'@' in it.body||'@' in it.title}&&CrossPosts.corroboratedPassageCollection(posts))AnnouncementEditions.composePassages(posts)else null

    fun compose(posts: List<Post>): PostFusion? {
        composeOrdinary(posts)?.let {return it}
        if(CrossPosts.resourceAnnouncementCollection(posts))ResourceAnnouncements.compose(posts)?.let {return it}
        if(CrossPosts.literalWordingCollection(posts))CaptionWeave.composeWordingEdits(posts)?.let {return it}
        if(CrossPosts.corroboratedPassageCollection(posts))AnnouncementEditions.composePassages(posts)?.let {return it}
        return null
    }
    private fun composeOrdinary(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size)return null
        RedditSupplements.compose(posts)?.let {return it}
        DecoratedPostCopies.compose(posts)?.let {return it}
        AttachmentCaptions.compose(posts)?.let {return it}
        CaptionAliases.compose(posts)?.let {return it}
        // Prefer the complete proper-name caption to repeated source paragraphs
        // when the existing independent copy/media checks qualify its aliases.
        // Candidate recognition alone never supplies that corroboration.
        CaptionAliases.compose(posts,mediaConfirmed=true)?.let {aliases->
            if(CrossPosts.sameContentCopies(posts))return aliases
        }
        // Grouping proves timing/content/corroboration independently; a copy
        // never certifies that its distinct publishers are the same person.
        // A fully equivalent short caption needs no partial-span alignment, but
        // still needs enough actual prose to preserve useful source editions.
        val complete=posts.map {post->
            if(post.body.length.toLong()+post.title.length>MAX_TEXT)return null
            PostPresentation.content(post)
        }
        if(complete.any {word.findAll(it).take(MAX_WORDS+1).count()>MAX_WORDS})return null
        val prose=mention.replace(PostPresentation.proseWithoutLinks(complete.first())," ").trim()
        val proseWords=word.findAll(prose).take(MAX_WORDS+1).map {it.value.lowercase(Locale.ROOT)}.toList()
        if(posts.none {it.preview}&&complete.none {truncatedCaption.containsMatchIn(it)}&&prose.length>=12&&
            proseWords.size in 3..MAX_WORDS&&proseWords.distinct().size>=3) {
            val key=PostPresentation.comparableContent(posts.first())
            if(posts.drop(1).all {PostPresentation.comparableContent(it)==key}) {
                return PostFusion(listOf(FusionSegment(complete.first(),posts.mapTo(linkedSetOf()){it.platform},true,posts.mapTo(linkedSetOf()){CrossPosts.originalKey(it)})))
            }
            // Campaign links can differ while the complete announcement is the
            // same. Grouping has already checked accounts, timing and changed
            // facts. Keep one exact source caption and all links on its original
            // editions, instead of repeating the prose around opaque link codes.
            if(posts.all {CrossPosts.onlyOpaqueOrOriginalLinks(it)}&&
                posts.any {PostLinks.urls(it).any(CrossPosts::opaqueLink)}) {
                val captions=complete.map(PostPresentation::referenceFreeCaption)
                if(captions.distinct().size==1) {
                    val representative=complete.indices.minBy {PostPresentation.destinationLabels(complete[it]).count()}
                    return PostFusion(listOf(FusionSegment(complete[representative],posts.mapTo(linkedSetOf()){it.platform},true,posts.mapTo(linkedSetOf(),CrossPosts::originalKey))))
                }
            }
        }
        if((proseWords.size<MIN_WORDS||prose.length<MIN_CHARS)&&prose.length>=12&&proseWords.size in 3..MAX_WORDS&&proseWords.distinct().size>=3&&CrossPosts.literalCaptionVersions(posts)) {
            if(CrossPosts.formattingCaptionCopies(posts)) {
                val preferred=posts.minBy {PostPresentation.destinationLabels(PostPresentation.content(it)).count()}
                return PostFusion(listOf(FusionSegment(PostPresentation.content(preferred),posts.mapTo(linkedSetOf()){it.platform},true,posts.mapTo(linkedSetOf(),CrossPosts::originalKey))))
            }
            // Keep ordinary tonal punctuation out of the reading workload.
            // Question marks, internal commas, symbols, quantities and links
            // retain the preceding guards and all exact source editions.
            if(posts.map {terminalExclamation.replace(PostPresentation.comparableContent(it),".")}.distinct().size==1)
                return PostFusion(listOf(FusionSegment(PostPresentation.content(posts.first()),posts.mapTo(linkedSetOf()){it.platform},true,posts.mapTo(linkedSetOf(),CrossPosts::originalKey))))
            val versions=posts.groupBy(PostPresentation::comparableContent)
            return PostFusion(versions.values.map {sources->FusionSegment(PostPresentation.content(sources.first()),sources.mapTo(linkedSetOf()){it.platform},
                sources.size==posts.size,sources.mapTo(linkedSetOf(),CrossPosts::originalKey))})
        }
        val prepared=posts.map {prepare(it) ?: return AnnouncementHeadings.compose(posts)}
        for(i in prepared.indices)for(j in i+1 until prepared.size)if(alignment(prepared[i],prepared[j])==null)return AnnouncementHeadings.compose(posts)
        return composePrepared(posts,prepared) ?: AnnouncementHeadings.compose(posts)
    }

    private fun composePrepared(posts: List<Post>,prepared: List<Prepared>): PostFusion? {
        val nodes=linkedMapOf<String,Node>()
        prepared.forEachIndexed {source,document->
            val repeated=document.parts.groupingBy {it.key}.eachCount()
            var previous: Node?=null
            document.parts.forEachIndexed {index,part->
                // Repetition exclusive to one source is retained verbatim; the
                // pair validation above already rejected ambiguous shared text.
                val key=if(repeated.getValue(part.key)>1)"source:$source:$index:"+part.key else "text:"+part.key
                val node=nodes.getOrPut(key){Node(key)}
                node.sources+=SourcePart(source,index)
                previous?.let {if(it.next.add(node))node.incoming++}
                previous=node
            }
        }
        val rank=compareBy<Node> {it.sources.minOf {source->source.source}}.thenBy {node->node.sources.minOf {it.part}}
        val ready=java.util.PriorityQueue(rank)
        nodes.values.filterTo(ready){it.incoming==0}
        val ordered=mutableListOf<Node>()
        while(ready.isNotEmpty()) {
            val node=ready.remove();ordered+=node
            node.next.forEach {if(--it.incoming==0)ready+=it}
        }
        if(ordered.size!=nodes.size)return null
        val segments=mutableListOf<FusionSegment>()
        var position=0
        while(position<ordered.size) {
            val first=ordered[position]
            val sources=first.sources.mapTo(linkedSetOf()){it.source}
            val representative=if(first.key.startsWith("text:link:"))first.sources.maxBy {source->
                val part=prepared[source.source].parts[source.part]
                val text=prepared[source.source].text.substring(part.start,part.end)
                (if(text.startsWith("https://")||text.startsWith("http://"))MAX_TEXT else 0)+text.length
            }.source else sources.first()
            val initial=first.sources.single {it.source==representative}.part
            var last=initial;var next=position+1
            while(next<ordered.size) {
                val node=ordered[next]
                // Keep an equivalent URL label separate so a full original
                // destination can win over another platform's shortened label.
                if(first.key.startsWith("text:link:")||node.key.startsWith("text:link:"))break
                if(node.sources.mapTo(linkedSetOf()){it.source}!=sources)break
                val part=node.sources.single {it.source==representative}.part
                if(part!=last+1)break
                last=part;next++
            }
            val document=prepared[representative]
            val text=document.text.substring(document.parts[initial].start,document.parts[last].end)
            val platforms=sources.mapTo(linkedSetOf()){posts[it].platform}
            // A subset-shared addition keeps its attribution in the native UI.
            segments+=FusionSegment(text,platforms,sources.size==posts.size,sources.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])})
            position=next
        }
        return PostFusion(segments)
    }
}
