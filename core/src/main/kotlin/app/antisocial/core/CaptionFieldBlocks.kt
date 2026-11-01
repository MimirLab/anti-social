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
    private fun projection(value:String,compact:Boolean=true):List<Part> = buildList {
        fun prose(text:String)=if(compact)text.filterNot {it.isWhitespace()||Character.isSpaceChar(it)}.lowercase(java.util.Locale.ROOT)
            else TextWhitespace.collapse(text).lowercase(java.util.Locale.ROOT)
        var at=0
        verbatim(value).forEach {range->
            add(Part(false,prose(value.substring(at,range.first))))
            add(Part(true,value.substring(range)));at=range.last+1
        }
        add(Part(false,prose(value.substring(at))))
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
    fun compose(posts:List<Post>,partialReferences:Boolean=false):PostFusion? {
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
        val bodies=if(partialReferences)referenceGroups(groups,fields)else groups.map {BodyGroup(it)}
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
            if(projection(projected)!=projection(PostPresentation.content(source.post)))return null
        }
        return PostFusion(segments)
    }
}
