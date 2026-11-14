/* anti social, GPL-3.0-or-later. Shared material is not shared account control. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Exact supplied photographs can connect an author's own announcement with
 * another author's explicitly curated commentary. Keep the captions separate
 * unless the ordinary literal composer can share them faithfully. */
internal object CuratedImageReposts {
    private val photograph=Regex("^/media/[A-Za-z0-9_-]{1,200}(?:\\.(jpg|jpeg|png|webp))?$")
    private val format=setOf("jpg","jpeg","png","webp")
    private val rendition=Regex("[A-Za-z0-9_-]{1,32}")
    data class Witnesses(val roots:List<Post>,val posts:List<Post>)

    /** Exact URL equality only, with no byte, signature or cache alias grant.
     * Avatars, recording covers and partial numbered galleries are excluded. */
    fun key(post:Post):String? {
        if(post.platform!=Platform.X||post.preview||post.parentId!=null||post.recoveredArchive!=null)return null
        val media=post.media.singleOrNull()?.takeIf {it.type=="image"&&it.url!=post.avatar} ?: return null
        if(media.sourceLayout?.let {it.count!=1||it.position!=1}==true)return null
        val raw=media.url.takeIf {it.length in 1..8192&&it.none(Char::isISOControl)} ?: return null
        val url=raw.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.host=="pbs.twimg.com"&&it.port==443&&
            it.username.isEmpty()&&it.password.isEmpty()&&it.fragment==null} ?: return null
        val path=photograph.matchEntire(url.encodedPath) ?: return null
        if(url.queryParameterNames.any {it !in setOf("format","name")})return null
        val formats=url.queryParameterValues("format");val names=url.queryParameterValues("name")
        if(formats.size>1||formats.any {it !in format}||names.size>1||names.any {it==null||!rendition.matches(it)})return null
        if(path.groupValues[1].isEmpty()&&formats.isEmpty())return null
        return raw
    }

    fun witnesses(posts:List<Post>):Witnesses? {
        val photographs=posts.filter {key(it)!=null}
        if(photographs.size<2||photographs.map(::key).distinct().size!=1)return null
        val roots=photographs.filter {root->photographs.any {post->post!=root&&
            CrossPosts.originalAccount(post)!=CrossPosts.originalAccount(root)&&CrossPosts.curatedQuotationSource(post,root)}}
        val account=roots.map(CrossPosts::originalAccount).distinct().singleOrNull() ?: return null
        val direct=photographs.filter {post->CrossPosts.originalAccount(post)==account||roots.any {CrossPosts.curatedQuotationSource(post,it)}}
        fun frame(post:Post)=post.media.single().let {it.attachmentFingerprint ?: it.visualFingerprint}
        if(direct.any {!VisualFrames.valid(frame(it))}||direct.indices.any {i->(i+1 until direct.size).any {j->
            val a=frame(direct[i]);val b=frame(direct[j]);a!=null&&b!=null&&!VisualFrames.sameImage(a,b)
        }})return null
        return Witnesses(roots,direct)
    }

    /** Presentation follows admission. Complete distinct captions remain
     * attributed blocks when there is no useful literal passage to weave. */
    fun compose(posts:List<Post>):PostFusion? {
        if(!CrossPosts.curatedImageCollection(posts))return null
        return CaptionFieldBlocks.compose(posts,partialReferences=true,shortClosings=true)
            ?: PostFusion(posts.groupBy {PostPresentation.content(it).trim()}.values.map {sources->
                FusionSegment(PostPresentation.content(sources.first()),sources.mapTo(linkedSetOf()){it.platform},
                    sources.size==posts.size,sources.mapTo(linkedSetOf(),CrossPosts::originalKey))
            })
    }

    /** Frozen source families keep their cross-platform editions. A whole
     * union needs portable direct proof; accepted leaves cannot bridge assets
     * or curators. Broad buckets remain unexamined, never negative evidence. */
    fun collect(groups:List<PostGroup>,proof:(List<Post>)->Boolean,checkpoint:()->Unit):List<PostGroup> {
        if(groups.size<2)return groups
        val buckets=linkedMapOf<String,MutableList<Int>>()
        groups.forEachIndexed {index,group->
            checkpoint();group.posts.mapNotNull(::key).distinct().singleOrNull()?.let {buckets.getOrPut(it){mutableListOf()}.add(index)}
        }
        val replacements=hashMapOf<Int,PostGroup>();val removed=hashSetOf<Int>()
        for(bucket in buckets.values.filter {it.size in 2..64}) {
            val families=mutableListOf<MutableList<Int>>()
            val ordered=bucket.sortedWith(compareBy<Int>{groups[it].posts.minOf {p->p.publishedAt ?: Long.MAX_VALUE}}.thenBy {CrossPosts.originalKey(groups[it].primary)})
            for(index in ordered) {
                checkpoint()
                val choices=families.filter {family->
                    val originals=(family+index).flatMap {groups[it].posts}
                    originals.size<=CrossPosts.MAX_EDITIONS&&proof(originals)
                }
                val destination=choices.singleOrNull()
                if(destination==null)families+=mutableListOf(index)else destination+=index
            }
            for(family in families.filter {it.size>1}) {
                val originals=family.flatMap {groups[it].posts}.sortedWith(compareBy<Post>{it.publishedAt ?: Long.MAX_VALUE}.thenBy(CrossPosts::originalKey))
                replacements[family.first()]=PostGroup(originals);removed+=family.drop(1)
            }
        }
        return groups.indices.filter {it !in removed}.map {replacements[it] ?: groups[it]}
    }
}
