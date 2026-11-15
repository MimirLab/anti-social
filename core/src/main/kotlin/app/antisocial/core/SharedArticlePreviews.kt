/* anti social, GPL-3.0-or-later. A shared article remains a resource relationship. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Collect an acquired article preview with its publisher's own accompanying
 * announcement. Exact complete destinations supply the relationship; a shared
 * headline, domain or picture does not. Both different pictures are preserved. */
internal object SharedArticlePreviews {
    private val cardPath=Regex("/card_img/[0-9]{1,40}/[A-Za-z0-9_-]{1,100}")
    private fun eligible(post:Post)=!post.preview&&post.parentId==null&&post.recoveredArchive==null&&
        post.body.length.toLong()+post.title.length<=16_384&&post.publishedAt?.let {it>0}==true&&post.media.isNotEmpty()&&
        SocialLinkHosts.platform(post.canonicalUrl)==post.platform&&ContentIdentity.key(post.platform,post.canonicalUrl).startsWith("post:")
    private fun article(raw:String):String? {
        if(raw.length !in 1..4096||raw.any(Char::isISOControl)||CrossPosts.opaqueLink(raw)||SocialLinkHosts.platform(raw)!=null)return null
        val url=raw.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()&&it.query==null&&it.fragment==null&&AutomaticLinks.mayRead(it)} ?: return null
        val parts=url.pathSegments.filter(String::isNotEmpty)
        if(parts.size<2||parts.last().length<12||parts.last().count(Char::isLetter)<8||parts.last().substringAfterLast('.') in setOf("jpg","png","webp","mp4","pdf"))return null
        return raw
    }
    fun key(post:Post):String? {
        if(!eligible(post)||post.platform!=Platform.X||post.title.length !in 24..512)return null
        val source=post.provenance ?: return null
        if(source.source.platform!=Platform.X||source.source.frontend!="nitter"||source.source.role!=SourceRole.ALTERNATE)return null
        val root=source.source.root.toHttpUrlOrNull() ?: return null
        val final=source.finalUrl.toHttpUrlOrNull() ?: return null
        if(!root.isHttps||!final.isHttps||root.host!=final.host||root.port!=final.port)return null
        val image=post.media.singleOrNull()?.takeIf {it.type=="image"&&it.description.lineSequence().firstOrNull()=="Linked preview: ${post.title}"} ?: return null
        val asset=image.url.toHttpUrlOrNull() ?: return null
        if(!asset.isHttps||asset.host!="pbs.twimg.com"||asset.port!=443||asset.username.isNotEmpty()||asset.password.isNotEmpty()||
            !cardPath.matches(asset.encodedPath))return null
        val link=post.links.orEmpty().mapNotNull(::article).distinct().singleOrNull() ?: return null
        val host=link.toHttpUrlOrNull()!!.host.removePrefix("www.")
        val body=post.body.trim()
        // The acquired link card's abbreviated destination is not an authored
        // answer. Arbitrary commentary about the same URL stays independent.
        if(body.isNotEmpty()&&!(body.startsWith("$host/")&&body.length<=160&&('…' in body||body.endsWith("..."))))return null
        return link
    }
    private fun carries(post:Post,url:String)=eligible(post)&&PostLinks.urls(post).any {it==url}
    fun accepts(posts:List<Post>):Boolean=CrossPostFusion.withPrepared {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size||posts.any {!eligible(it)})return@withPrepared false
        val times=posts.map {requireNotNull(it.publishedAt)}
        if(times.max()-times.min()>10*60_000)return@withPrepared false
        val previews=posts.filter {key(it)!=null}
        val resource=previews.map(::key).distinct().singleOrNull() ?: return@withPrepared false
        val announcements=posts.filter {it !in previews}
        val witnesses=announcements.filter {carries(it,resource)}
        if(witnesses.isEmpty())return@withPrepared false
        announcements.all {post->
            if(PostLinks.urls(post).mapNotNull(::article).any {it!=resource})return@all false
            post in witnesses||witnesses.any {witness->
            val pair=listOf(witness,post)
            val a=PostBodyDetails.split(PostPresentation.content(witness));val b=PostBodyDetails.split(PostPresentation.content(post))
            val literal=a.body.length>=140&&a.body==b.body&&a.leadingDetails.isEmpty()&&b.leadingDetails.isEmpty()&&
                (b.details.isEmpty()||b.details==a.details)&&PostLinks.urls(post).filterNot {link->
                    CrossPosts.originalLink(link,post)||post.media.any {it.url==link||it.preview==link}
                }.all {it==resource}
            literal||CrossPosts.sameContentCopies(pair)||CrossPosts.sameSourceRepeats(pair)||CrossPostFusion.composeCollectedEditions(pair)!=null||CrossPostFusion.compose(pair)!=null
        }}
    }
    fun previewKeys(posts:List<Post>):Set<String> = if(accepts(posts))posts.filter {key(it)!=null}.mapTo(linkedSetOf(),CrossPosts::originalKey)else emptySet()
    fun collect(groups:List<PostGroup>,owners:Map<String,String?>,checkpoint:()->Unit):List<PostGroup> {
        if(groups.size<2)return groups
        val candidates=linkedMapOf<Pair<String,String>,MutableSet<Int>>()
        groups.forEachIndexed {index,group->
            checkpoint()
            val owner=group.posts.map {owners[CrossPosts.originalKey(it)]}.distinct().singleOrNull() ?: return@forEachIndexed
            group.posts.mapNotNull(::key).distinct().forEach {candidates.getOrPut(owner to it){linkedSetOf()}.add(index)}
        }
        val replacements=hashMapOf<Int,PostGroup>();val removed=hashSetOf<Int>()
        for((scope,previews)in candidates) {
            checkpoint();val (owner,url)=scope
            val other=groups.indices.filter {i->i !in removed&&i !in previews&&groups[i].posts.all {owners[CrossPosts.originalKey(it)]==owner}&&groups[i].posts.any {carries(it,url)}}
            val indexes=(previews+other).filter {it !in removed}
            if(indexes.size !in 2..32)continue
            val originals=indexes.flatMap {groups[it].posts}
            if(!accepts(originals))continue
            val ordered=originals.sortedWith(compareBy<Post>{it.publishedAt}.thenBy(CrossPosts::originalKey))
            replacements[indexes.first()]=PostGroup(ordered);removed+=indexes.drop(1)
        }
        return groups.indices.filter {it !in removed}.map {replacements[it] ?: groups[it]}
    }
}
