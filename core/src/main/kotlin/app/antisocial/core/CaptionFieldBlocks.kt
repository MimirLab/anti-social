/* anti social, GPL-3.0-or-later. Presentation of independently admitted editions. */
package app.antisocial.core

/** Shared bodies read once while distinct headings and prose stay attributed.
 * This composes literal source fields after independent pair admission. Successful
 * presentation alone never supplies a pair match or saved-membership proof. */
object CaptionFieldBlocks {
    private data class Part(val verbatim:Boolean,val text:String)
    private data class BodyKey(val formatted:String?,val literal:List<Part>?)
    /** Keep supported URLs, address/identifier tokens and code spans exact.
     * This is presentation, not URI normalization or a source identity proof. */
    private fun verbatim(value:String):List<IntRange> {
        val ranges=PostPresentation.urlMatches(value).map {it.range}.toMutableList()
        var at=0
        while(at<value.length) {
            if(value[at].isWhitespace()||Character.isSpaceChar(value[at])){at++;continue}
            val start=at
            while(at<value.length&&!value[at].isWhitespace()&&!Character.isSpaceChar(value[at]))at++
            val token=value.substring(start,at);val colon=token.indexOf(':')
            if(token.any {it.isDigit()||it in "@=<>#_/"}||colon in 1 until token.lastIndex)ranges+=start until at
        }
        at=0
        while(at<value.length) {
            val start=value.indexOf('`',at);if(start<0)break
            var end=start;while(end<value.length&&value[end]=='`')end++
            val marker=value.substring(start,end);val close=value.indexOf(marker,end)
            at=if(close<0)value.length else close+marker.length
            ranges+=start until at
        }
        val merged=mutableListOf<IntRange>()
        for(range in ranges.sortedBy {it.first}) {
            val previous=merged.lastOrNull()
            if(previous!=null&&range.first<=previous.last+1)merged[merged.lastIndex]=previous.first..maxOf(previous.last,range.last)
            else merged+=range
        }
        return merged
    }
    private fun projection(value:String,compact:Boolean=true,typography:Boolean=false):List<Part> = buildList {
        fun prose(text:String):String {
            val folded=if(typography)text.replace('’','\'').replace('‘','\'')else text
            return if(compact)folded.filterNot {it.isWhitespace()||Character.isSpaceChar(it)}.lowercase(java.util.Locale.ROOT)
                else TextWhitespace.collapse(folded).lowercase(java.util.Locale.ROOT)
        }
        var at=0
        verbatim(value).forEach {range->
            add(Part(false,prose(value.substring(at,range.first))))
            add(Part(true,value.substring(range)));at=range.last+1
        }
        add(Part(false,prose(value.substring(at))))
    }
    /** Source paths may share prose typography; URLs, code and identifiers
     * retain their literal spelling. The original Post records remain intact. */
    fun sourcePathMatches(first:String,second:String,typography:Boolean=false):Boolean {
        if(first.length>16_384||second.length>16_384||projection(first,typography=typography)!=projection(second,typography=typography))return false
        fun terms(text:String)=closingWords.findAll(text).map {
            val value=if(typography)it.value.replace('’','\'').replace('‘','\'')else it.value
            value.lowercase(java.util.Locale.ROOT)
        }.toList()
        return terms(first)==terms(second)
    }
    internal fun bodyEdition(post:Post):Post? {
        if(post.preview||post.parentId!=null||post.title.length.toLong()+post.body.length>4096)return null
        val text=PostPresentation.text(post)
        val body=text.body.ifBlank {text.title}
        return post.copy(title="",body=body).takeIf {body.isNotBlank()}
    }
    /** A supplied heading is retained, never an assertion that two bodies
     * match. CrossPosts applies its existing literal, owner and clock rules. */
    internal fun bodyEditions(first:Post,second:Post):Pair<Post,Post>? {
        if(listOf(first,second).any {it.title.length.toLong()+it.body.length>4096})return null
        val a=PostPresentation.text(first);val b=PostPresentation.text(second)
        val titleA=a.title.takeIf {a.body.isNotBlank()}.orEmpty()
        val titleB=b.title.takeIf {b.body.isNotBlank()}.orEmpty()
        if(titleA.isBlank()==titleB.isBlank()||maxOf(titleA.length,titleB.length)>160||'?' in titleA||'?' in titleB)return null
        return (bodyEdition(first) ?: return null)to(bodyEdition(second) ?: return null)
    }
    private data class Fields(val post:Post,val title:String,val body:String,val bodyPost:Post)
    private data class BodyGroup(val sources:List<Fields>,val fusion:PostFusion?=null,val opaque:Boolean=false)
    private val closingWords=Regex("[\\p{L}\\p{N}]+(?:['’‘][\\p{L}\\p{N}]+)*")
    private val closingOperators=Regex("[+\\-−<>=≤≥≠≈\\p{Sc}]")
    /** Only a whole short body may nominate a terminal clause in fuller bodies.
     * This is presentation after collection, never candidate or pair evidence. */
    private fun closingFusion(sources:List<Fields>):PostFusion? {
        if(sources.size !in 2..CrossPosts.MAX_EDITIONS||sources.minOf {it.body.length}>320)return null
        val words=sources.map {closingWords.findAll(it.body).take(257).toList()}
        if(words.any {it.size !in 4..256})return null
        val shortest=words.indices.minWith(compareBy<Int> {words[it].size}.thenBy {sources[it].body.length})
        val short=sources[shortest].body;val count=words[shortest].size
        if(count>32||words[shortest].first().range.first!=0)return null
        fun wordKey(value:String)=value.replace('’','\'').replace('‘','\'').lowercase(java.util.Locale.ROOT)
        val tokens=words.map {terms->terms.map {wordKey(it.value)}}
        val selected=tokens[shortest]
        if(tokens.any {it.takeLast(count)!=selected})return null
        val core=short.substring(0,words[shortest].last().range.last+1)
        if(core.length !in 20..280||words[shortest].map {it.value.lowercase(java.util.Locale.ROOT)}.distinct().size<3||
            PostPresentation.urlMatches(core).any()||core.any {it in "@#`"})return null
        val coreKey=projection(core,compact=false,typography=true)
        val starts=words.map {it[it.size-count].range.first}
        val ends=words.map {it.last().range.last+1}
        val prefixes=sources.indices.map {sources[it].body.substring(0,starts[it]).trim()}
        val tails=sources.indices.map {sources[it].body.substring(ends[it]).trim()}
        if(tails.any {it.length>32||closingOperators.containsMatchIn(it)||it.any {c->c in "@#`_/"}})return null
        for(i in sources.indices) {
            val text=sources[i].body;val terms=words[i];val start=starts[i]
            if(projection(text.substring(start,ends[i]),compact=false,typography=true)!=coreKey)return null
            if(start>0) {
                val gap=text.substring(terms[terms.size-count-1].range.last+1,start)
                // Do not cut a negated/quoted mid-sentence tail into an
                // apparently independent assertion. Symbols before a complete
                // sentence remain part of that source's introduction.
                if(gap.any {it in "\"“‘"}||gap.contains("...")||'…' in gap||
                    gap.none {it in ".!?"}&&gap.count {it=='\n'}<2)return null
            }
            var occurrences=0
            for(at in 0..terms.size-count) {
                // Most windows are unrelated. Check their small token keys
                // before allocating protected-span projections for a match.
                if((0 until count).any {tokens[i][at+it]!=selected[it]})continue
                val candidate=text.substring(terms[at].range.first,terms[at+count-1].range.last+1)
                if(projection(candidate,compact=false,typography=true)==coreKey&&++occurrences>1)return null
            }
        }
        if(prefixes.all {it.isBlank()}||sources.map {projection(it.body,compact=false,typography=true)}.distinct().size<2)return null
        val all=sources.indices.toList();val runs=mutableListOf<FusionRun>()
        fun emit(parts:List<String>) {
            if(parts.all(String::isBlank))return
            val variants=all.groupBy {projection(parts[it],compact=false,typography=true)}.values.map {rows->
                FusionVariant(parts[rows.first()],rows.mapTo(linkedSetOf()){sources[it].post.platform},rows.mapTo(linkedSetOf()){CrossPosts.originalKey(sources[it].post)})
            }
            runs+=FusionRun(variants.size==1,variants)
        }
        emit(prefixes)
        // A differing ending borrows its last word, so the UI never presents
        // an unexplained punctuation-only alternative or empty source label.
        val endingsDiffer=tails.map {projection(it,compact=false,typography=true)}.distinct().size>1
        emit(sources.indices.map {i->
            val end=if(endingsDiffer)words[i].last().range.first else ends[i]
            sources[i].body.substring(starts[i],end).trim()
        })
        if(endingsDiffer)emit(sources.indices.map {i->sources[i].body.substring(words[i].last().range.first).trim()})else emit(tails)
        if(runs.none {it.shared&&it.variants.single().text.any(Char::isLetter)})return null
        for(i in sources.indices) {
            val key=CrossPosts.originalKey(sources[i].post)
            val path=sourcePath(runs,key) ?: return null
            if(!sourcePathMatches(path,sources[i].body,typography=true))return null
        }
        val text=runs.joinToString(" "){run->if(run.shared)run.variants.single().text else run.variants.filter {it.text.isNotBlank()}
            .joinToString(" / ","[","]"){it.text+" — "+PlatformOrder.sort(it.platforms).joinToString(" · "){p->p.label}}}
        return PostFusion(listOf(FusionSegment(text,sources.mapTo(linkedSetOf()){it.post.platform},false,sources.mapTo(linkedSetOf()){CrossPosts.originalKey(it.post)})),runs)
    }
    private fun closingGroups(groups:List<BodyGroup>,fields:List<Fields>):List<BodyGroup> {
        val positions=fields.mapIndexed {index,field->CrossPosts.originalKey(field.post)to index}.toMap()
        val out=mutableListOf<BodyGroup>();var attempts=0
        // A complete short edition nominates the clause before longer bodies
        // arrive, allowing several distinct introductions to share its ending.
        for(group in groups.sortedWith(compareBy<BodyGroup> {it.sources.minOf {source->source.body.length}}
            .thenBy {it.sources.minOf {source->positions.getValue(CrossPosts.originalKey(source.post))}})) {
            var joined=false
            for(i in out.indices) {
                if(attempts++>=32)break
                val combined=(out[i].sources+group.sources).sortedBy {positions.getValue(CrossPosts.originalKey(it.post))}
                val fusion=closingFusion(combined) ?: continue
                out[i]=BodyGroup(combined,fusion,out[i].opaque||group.opaque);joined=true;break
            }
            if(!joined)out+=group
        }
        return out.sortedBy {it.sources.minOf {source->positions.getValue(CrossPosts.originalKey(source.post))}}
    }
    private fun sourcePath(runs:List<FusionRun>,key:String):String? {
        val parts=mutableListOf<String>()
        for(run in runs)parts+=run.variants.singleOrNull {key in it.originalKeys}?.text ?: return null
        return parts.joinToString(" ")
    }
    /** Only the already-admitted reader opts into partial reference weaving.
     * Nomination/representation checks keep the literal default. At most 32
     * whole-group attempts run; unexamined bodies retain their literal blocks. */
    private fun referenceGroups(groups:List<List<Fields>>,fields:List<Fields>):List<BodyGroup> {
        val positions=fields.mapIndexed {index,field->CrossPosts.originalKey(field.post) to index}.toMap()
        fun first(sources:List<Fields>)=sources.minOf {positions.getValue(CrossPosts.originalKey(it.post))}
        fun opaque(sources:List<Fields>)=sources.any {PostLinks.urls(it.bodyPost.copy(links=null)).any(CrossPosts::opaqueLink)}
        val out=mutableListOf<BodyGroup>();var attempts=0
        for(sources in groups.sortedWith(compareByDescending<List<Fields>> {it.size}.thenBy(::first))) {
            val incoming=opaque(sources);var joined=false
            for(index in out.indices) {
                if(!incoming&&!out[index].opaque)continue
                if(attempts>=32)break
                attempts++
                val combined=(out[index].sources+sources).sortedBy {positions.getValue(CrossPosts.originalKey(it.post))}
                // Fields have already applied source transport decoding. These
                // temporary body-only copies must not decode it a second time.
                val fusion=CaptionWeave.composeReferences(combined.map {it.bodyPost.copy(provenance=null)}) ?: continue
                val runs=fusion.weave ?: continue
                if(combined.any {source->
                    val path=sourcePath(runs,CrossPosts.originalKey(source.post))
                    path==null||projection(path)!=projection(source.body)
                })continue
                out[index]=BodyGroup(combined,fusion,incoming||out[index].opaque);joined=true;break
            }
            if(!joined)out+=BodyGroup(sources,opaque=incoming)
        }
        return out.sortedBy {first(it.sources)}
    }
    fun compose(posts:List<Post>,partialReferences:Boolean=false,shortClosings:Boolean=false):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size||
            posts.any {it.preview||it.title.length.toLong()+it.body.length>4096})return null
        val fields=posts.sortedWith(compareBy<Post> {it.publishedAt ?: Long.MAX_VALUE}.thenBy(CrossPosts::originalKey)).map {post->
            val text=PostPresentation.text(post)
            val title=text.title.takeIf {text.body.isNotBlank()}.orEmpty()
            val body=text.body.ifBlank {text.title}
            if(body.isBlank())return null
            Fields(post,title,body,post.copy(title="",body=body))
        }
        val groups=fields.groupBy {source->CrossPosts.formattingCaptionKey(source.bodyPost)?.let {
            BodyKey(it.lowercase(java.util.Locale.ROOT),null)
        } ?: BodyKey(null,projection(source.body,compact=false))}.values.flatMap {sources->
            if(sources.size>1&&CrossPosts.literalCaptionVersions(sources.map {it.bodyPost}))listOf(sources)
            else sources.groupBy {projection(it.body,compact=false)}.values
        // A resolved link inventory can disable the formatting route for one
        // edition while its peers still use it. Those are nomination buckets,
        // not a reason to repeat literally equal bodies in an admitted card.
        // Verbatim URL/identifier spans stay in the key; the complete per-source
        // title/body projection below must still pass for every merged group.
        }.groupBy {sources->projection(sources.first().body,compact=false)}.values.map {it.flatten()}
        val grouped=if(partialReferences)referenceGroups(groups,fields)else groups.map {BodyGroup(it)}
        val bodies=if(shortClosings)closingGroups(grouped,fields)else grouped
        if(bodies.none {it.sources.size>1})return null
        fun segment(text:String,sources:List<Fields>)=FusionSegment(text,sources.mapTo(linkedSetOf()){it.post.platform},
            sources.size==posts.size,sources.mapTo(linkedSetOf()){CrossPosts.originalKey(it.post)})
        val segments=bodies.flatMap {group->
            val sources=group.sources
            val titles=sources.filter {it.title.isNotBlank()}.groupBy {projection(it.title,compact=false)}.values
                .map {segment(it.first().title,it)}
            val body=group.fusion?.let {fusion->segment(fusion.segments.single().text,sources).copy(weave=fusion.weave)}
                ?: segment(sources.first().body,sources)
            titles+body
        }
        // Keep each source's whole title/body relationship. A formatting
        // equivalence cannot silently discard a distinct URL, clause or title.
        for(source in fields) {
            val key=CrossPosts.originalKey(source.post)
            val parts=segments.filter {key in it.originalKeys}.map {segment->
                segment.weave?.let {sourcePath(it,key) ?: return null} ?: segment.text
            }
            val projected=parts.joinToString("\n\n")
            val original=PostPresentation.content(source.post)
            if(shortClosings) {
                if(!sourcePathMatches(projected,original,typography=true))return null
            }else if(projection(projected)!=projection(original))return null
        }
        return PostFusion(segments)
    }
}
