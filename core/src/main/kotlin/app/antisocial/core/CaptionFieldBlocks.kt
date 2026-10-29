/* anti social, GPL-3.0-or-later. Presentation of independently admitted editions. */
package app.antisocial.core

/** A distinct source title must not make a repeated body appear twice. This
 * composes literal source fields after independent pair admission. Successful
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
    fun compose(posts:List<Post>):PostFusion? {
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
        }
        if(groups.none {it.size>1&&it.any {source->source.title.isNotBlank()}})return null
        fun segment(text:String,sources:List<Fields>)=FusionSegment(text,sources.mapTo(linkedSetOf()){it.post.platform},
            sources.size==posts.size,sources.mapTo(linkedSetOf()){CrossPosts.originalKey(it.post)})
        val segments=groups.flatMap {sources->
            val titles=sources.filter {it.title.isNotBlank()}.groupBy {projection(it.title,compact=false)}.values
                .map {segment(it.first().title,it)}
            titles+segment(sources.first().body,sources)
        }
        // Keep each source's whole title/body relationship. A formatting
        // equivalence cannot silently discard a distinct URL, clause or title.
        for(source in fields) {
            val key=CrossPosts.originalKey(source.post)
            val projected=segments.filter {key in it.originalKeys}.joinToString("\n\n"){it.text}
            if(projection(projected)!=projection(PostPresentation.content(source.post)))return null
        }
        return PostFusion(segments)
    }
}
