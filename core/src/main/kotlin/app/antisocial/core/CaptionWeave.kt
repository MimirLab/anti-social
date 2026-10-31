/* anti social, GPL-3.0-or-later. Literal source composition; no generated prose. */
package app.antisocial.core

import org.jsoup.parser.Parser
import java.text.Normalizer
import java.util.Locale

/** Additional presentation route for independently corroborated copies with
 * small local edits. Every source projects back to its entire ordered caption;
 * alternatives are never concatenated into an unattributed factual sentence. */
internal object CaptionWeave {
    private val tokens=Regex("\\S+")
    private val urls=Regex("https://[^\\s<>\"']+")
    private val substantive=setOf("approved","rejected","denied","closed","cancelled","canceled","postponed","rescheduled","suspended",
        "false","fake","untrue","counterfeit","different","replacement","former","new","old","increased","decreased","rose","fell",
        "one","two","three","four","five","six","seven","eight","nine","ten")
    private data class Word(val raw:String,val key:String,val reference:Boolean=false)
    private data class Document(val post:Post,val words:List<Word>)
    private fun key(raw:String)=Normalizer.normalize(raw,Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun words(text:String,references:Boolean=false):List<Word> {
        val ranges=if(references)PostPresentation.urlMatches(text).map {it.range}.toList()else emptyList()
        return tokens.findAll(text).take(257).map {word->
            val reference=ranges.singleOrNull {it.first>=word.range.first&&it.last<=word.range.last}?.let {range->
                // A domain in "not:example.org" cannot hide the prose around
                // it. Only punctuation may surround a reference-only token.
                val outside=text.substring(word.range.first,range.first)+text.substring(range.last+1,word.range.last+1)
                outside.none {it.isLetterOrDigit()||it in "@#"}
            }==true
            // URL paths, query values and even their Unicode spelling can be
            // case/byte sensitive. Never share them through prose folding.
            Word(word.value,if(reference)word.value else key(word.value),reference)
        }.toList()
    }
    private fun document(post:Post,references:Boolean=false):Document? {
        if(post.body.length.toLong()+post.title.length>4096||post.preview||post.parentId!=null)return null
        var text=PostPresentation.content(post);repeat(2){text=Parser.unescapeEntities(text,false)}
        // The original link inventory retains opaque campaign links. They are
        // not sentence differences; meaningful destinations are checked by the
        // caller and retained here, including their case-sensitive components.
        if(!references)text=urls.replace(text){if(CrossPosts.opaqueLink(it.value))""else it.value}
        if(text.any {it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'})return null
        val words=words(text.replace('\u00a0',' '),references)
        if(words.size !in 24..256||words.map {it.key}.distinct().size<12)return null
        return Document(post,words)
    }
    private fun common(a:List<String>,b:List<String>):List<String> {
        val width=b.size+1;val lengths=IntArray((a.size+1)*width)
        for(i in a.indices.reversed())for(j in b.indices.reversed())lengths[i*width+j]=
            if(a[i]==b[j])1+lengths[(i+1)*width+j+1]else maxOf(lengths[(i+1)*width+j],lengths[i*width+j+1])
        val out=mutableListOf<String>();var i=0;var j=0
        while(i<a.size&&j<b.size)when {
            a[i]==b[j]->{out+=a[i];i++;j++}
            lengths[(i+1)*width+j]>=lengths[i*width+j+1]->i++
            else->j++
        }
        return out
    }
    private fun namedReplacements(runs:List<FusionRun>):Boolean=runs.filterNot {it.shared}.any {run->
        val values=run.variants.filter {it.text.isNotBlank()}
        if(values.size<2||values.any {'@' in it.text})false else {
            val terms=values.map {tokens.findAll(it.text).map {word->key(word.value)}.toList()}
            val smallest=terms.minBy {it.size}
            val insertion=terms.all {long->long.windowed(smallest.size).any {it==smallest}}
            !insertion&&values.any {Regex("[\\p{L}\\p{N}']+").findAll(it.text).any {word->word.value.firstOrNull()?.isUpperCase()==true}}
        }
    }
    private fun changedReferenceProse(run:FusionRun):List<String> {
        val parts=run.variants.map {words(it.text,true).filterNot {word->word.reference}}
        val shortest=parts.minOf {it.size};var first=0;var last=0
        while(first<shortest&&parts.map {it[first].key}.distinct().size==1)first++
        while(last<shortest-first&&parts.map {it[it.size-last-1].key}.distinct().size==1)last++
        // An insertion borrows a shared word for readability. That word is
        // still common prose, not a changed name, claim or sentence opening.
        return parts.map {it.subList(first,it.size-last).joinToString(" "){word->word.raw}}
    }
    /** Bounded candidate confirmation; caller supplies actual media and timing proof. */
    fun matches(a:Post,b:Post)=plan(listOf(a,b))!=null
    internal fun plan(posts:List<Post>,qualified:Boolean=false,sourceMentions:Boolean=false,sourceReferences:Boolean=false):List<FusionRun>? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS)return null
        val docs=posts.sortedBy(CrossPosts::originalKey).map {document(it,sourceReferences) ?: return null}
        val coverage=if(qualified).65 else .88
        if(qualified||sourceReferences) {
            val claims=Regex("\\p{N}+(?:[.,]\\p{N}+)*|\\b(?:no|not|never|without|cannot|can't|don't|doesn't|didn't|won't)\\b",RegexOption.IGNORE_CASE)
            if(docs.map {doc->claims.findAll(doc.words.filterNot {sourceReferences&&it.reference}.joinToString(" "){it.key}).map {m->m.value}.toList()}.distinct().size!=1)return null
        }
        if(posts.map {PostPresentation.proseWithoutLinks(PostPresentation.content(it)).count {c->c=='?'}}.distinct().size>1)return null
        val versions=docs.groupBy {it.words.map {w->w.key}}
        if(versions.size !in 2..8)return null
        // Different editions on one platform need date-specific labels. Do not
        // silently make their alternatives indistinguishable in a platform key.
        if(docs.groupBy {it.post.platform}.values.any {rows->rows.map {it.words.map {w->w.key}}.distinct().size>1})return null
        val longest=docs.maxOf {it.words.size}
        if(docs.minOf {it.words.size}<longest*coverage)return null
        // A cheap bag upper bound rejects unrelated captions before allocating
        // a small alignment table; exact ordered projection still decides.
        val counts=versions.keys.first().groupingBy {it}.eachCount()
        for(next in versions.keys.drop(1)) {
            val other=next.groupingBy {it}.eachCount()
            if(counts.entries.sumOf {(word,n)->minOf(n,other[word] ?: 0)}<longest*coverage)return null
        }
        var shared=versions.keys.first()
        for(next in versions.keys.drop(1))shared=common(shared,next)
        if(shared.size<24||shared.size<docs.maxOf {it.words.size}*coverage||shared.joinToString(" ").length<140)return null
        val positions=IntArray(docs.size);val runs=mutableListOf<FusionRun>();val buffer=mutableListOf<String>()
        val allPlatforms=docs.mapTo(linkedSetOf()){it.post.platform};val allKeys=docs.mapTo(linkedSetOf()){CrossPosts.originalKey(it.post)}
        fun flush() {if(buffer.isNotEmpty()){runs+=FusionRun(true,listOf(FusionVariant(buffer.joinToString(" "),allPlatforms,allKeys)));buffer.clear()}}
        fun variants(parts:List<List<Word>>) {
            flush()
            val groups=parts.indices.groupBy {parts[it].map {w->w.key}}
            runs+=FusionRun(false,groups.values.map {sources->
                FusionVariant(parts[sources.first()].joinToString(" "){it.raw},sources.mapTo(linkedSetOf()){docs[it].post.platform},sources.mapTo(linkedSetOf()){CrossPosts.originalKey(docs[it].post)})
            })
        }
        for(word in shared) {
            val next=docs.indices.map {i->(positions[i] until docs[i].words.size).firstOrNull {docs[i].words[it].key==word} ?: return null}
            val gaps=docs.indices.map {i->docs[i].words.subList(positions[i],next[i])}
            if(gaps.all {it.isEmpty()})buffer+=docs.first().words[next.first()].raw
            else {
                // An insertion in mid-sentence borrows the following shared
                // word so both alternatives are readable: "results [X] / direct
                // results [FB, IG]", never an unlabeled missing-word marker.
                val borrow=gaps.any {it.isEmpty()}
                variants(gaps.mapIndexed {i,gap->if(borrow)gap+docs[i].words[next[i]]else gap})
                if(!borrow)buffer+=docs.first().words[next.first()].raw
            }
            docs.indices.forEach {positions[it]=next[it]+1}
        }
        val tail=docs.indices.map {docs[it].words.drop(positions[it])}
        if(tail.any {it.isNotEmpty()})variants(tail)
        flush()
        if(runs.count {!it.shared}>(if(qualified)6 else 3))return null
        // Account/name substitutions have their own qualified alias route.
        // A high-overlap caption must not turn a different mentioned subject
        // into a local editorial wording variant.
        if(runs.filterNot {it.shared}.any {run->run.variants.any {'@' in it.text}&&
                (!sourceMentions||run.variants.size!=2||!CaptionAliases.correspondingNames(run.variants[0].text,run.variants[1].text))})return null
        if(sourceMentions&&namedReplacements(runs))return null
        if(runs.filterNot {it.shared}.any {run->
            val changed=if(sourceReferences)changedReferenceProse(run)else null
            run.variants.withIndex().any {(index,variant)->
            val prose=changed?.get(index) ?: variant.text
            val words=Regex("[\\p{L}\\p{N}']+").findAll(prose).map {it.value}.toList()
            val sentence=variant.text.firstOrNull()?.isUpperCase()==true&&variant.text.lastOrNull() in listOf('.','!','?')
            words.any {key(it) in substantive}||!qualified&&!sentence&&words.any {it.firstOrNull()?.isUpperCase()==true}
        }})return null
        // Proof of preservation, independent of the alignment tie choices. A
        // source's selected branches reproduce every normalized literal token.
        for(doc in docs) {
            val original=CrossPosts.originalKey(doc.post)
            val projected=runs.flatMap {run->
                val variant=run.variants.singleOrNull {original in it.originalKeys} ?: return null
                words(variant.text,sourceReferences).map {it.key}
            }
            if(projected!=doc.words.map {it.key})return null
        }
        return runs
    }
    fun compose(posts:List<Post>):PostFusion? {
        val runs=plan(posts) ?: return null
        return render(posts,runs)
    }
    /** Presentation of an already admitted collection. Preserve opaque inline
     * and trailing references literally; never infer their expanded destination
     * or use URL identifier digits as prose claims. Detector callers stay on
     * the original path and its unchanged overlap/identity requirements. */
    fun composeReferences(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.any {it.body.length.toLong()+it.title.length>4096||it.preview||it.parentId!=null})return null
        // This route handles visible literal references, not an unrelated or
        // oversized link inventory attached to a short source caption.
        if(posts.none {post->PostLinks.urls(post.copy(links=null)).any(CrossPosts::opaqueLink)})return null
        return plan(posts,sourceReferences=true)?.let {render(posts,it)}
    }
    /** Presentation only after an independently admitted, source-qualified
     * announcement. Never used as a lower-threshold copy detector. */
    fun composeQualified(posts:List<Post>):PostFusion? {
        val runs=plan(posts,qualified=true) ?: return null
        return render(posts,runs)
    }
    /** Actual artwork and publisher/time checks belong to the caller. Name
     * variants remain literal alternatives; this never rewrites an identity. */
    internal fun composeSourceMentions(posts:List<Post>):PostFusion? = plan(posts,qualified=true,sourceMentions=true)?.let {render(posts,it)}
    internal fun composeWordingEdits(posts:List<Post>):PostFusion? = plan(posts)?.takeUnless(::namedReplacements)?.let {render(posts,it)}
    private fun render(posts:List<Post>,runs:List<FusionRun>):PostFusion {
        val text=runs.joinToString(" "){run->if(run.shared)run.variants.single().text else
            run.variants.filter {it.text.isNotBlank()}.joinToString(" / ","[","]"){variant->variant.text+" — "+PlatformOrder.sort(variant.platforms).joinToString(" · "){it.label}}}
        return PostFusion(listOf(FusionSegment(text,posts.mapTo(linkedSetOf()){it.platform},false,posts.mapTo(linkedSetOf(),CrossPosts::originalKey))),runs)
    }
}
