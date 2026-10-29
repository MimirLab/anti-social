/* anti social, GPL-3.0-or-later. Presentation of independently admitted editions. */
package app.antisocial.core

/** A distinct source title must not make a repeated body appear twice. This
 * composes literal source fields after independent pair admission. Successful
 * presentation alone never supplies a pair match or saved-membership proof. */
object CaptionFieldBlocks {
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
    private fun compact(value:String)=value.filterNot {it.isWhitespace()||Character.isSpaceChar(it)}
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
        val groups=fields.groupBy {CrossPosts.formattingCaptionKey(it.bodyPost) ?: it.body}.values.flatMap {sources->
            if(sources.size>1&&CrossPosts.formattingCaptionCopies(sources.map {it.bodyPost}))listOf(sources)
            else sources.groupBy {TextWhitespace.collapse(it.body)}.values
        }
        if(groups.none {it.size>1&&it.any {source->source.title.isNotBlank()}})return null
        fun segment(text:String,sources:List<Fields>)=FusionSegment(text,sources.mapTo(linkedSetOf()){it.post.platform},
            sources.size==posts.size,sources.mapTo(linkedSetOf()){CrossPosts.originalKey(it.post)})
        val segments=groups.flatMap {sources->
            val titles=sources.filter {it.title.isNotBlank()}.groupBy {TextWhitespace.collapse(it.title)}.values
                .map {segment(it.first().title,it)}
            titles+segment(sources.first().body,sources)
        }
        // Keep each source's whole title/body relationship. A formatting
        // equivalence cannot silently discard a distinct URL, clause or title.
        for(source in fields) {
            val key=CrossPosts.originalKey(source.post)
            val projected=segments.filter {key in it.originalKeys}.joinToString("\n\n"){it.text}
            if(compact(projected)!=compact(PostPresentation.content(source.post)))return null
        }
        return PostFusion(segments)
    }
}
