/* anti social, GPL-3.0-or-later. Demand-scoped publisher gallery acquisition. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class GalleryContinuationRequest(val root:Post,val peer:Post,val editions:List<Post>,val followIds:Set<String>,val bindings:List<Follow> = emptyList()) {
    val originalKeys get()=editions.mapTo(linkedSetOf(),CrossPosts::originalKey)
}

/** Scheduling is independent from admission. A four-picture X edition must
 * already be observed inside a larger gallery from a linked publisher. */
object GalleryContinuations {
    /** Input identity only, never byte reuse or picture-equivalence evidence.
     * Public readers request different documented renditions of one X photo. */
    private fun inputAsset(value:String):String {
        ImageAssetIdentity.key(value)?.let {return it}
        if(value.length<=8192&&!value.any(Char::isISOControl))value.toHttpUrlOrNull()?.takeIf {url->
            url.isHttps&&url.port==443&&url.username.isEmpty()&&url.password.isEmpty()&&url.fragment==null&&url.host=="pbs.twimg.com"&&
                url.queryParameterNames.all {it in setOf("name","format")}&&url.queryParameterValues("name").let {it.size<=1&&it.all {v->v!=null&&v.matches(Regex("orig|small|medium|large|thumb|[1-9][0-9]{0,3}x[1-9][0-9]{0,3}"))}}&&
                url.queryParameterValues("format").let {it.size<=1&&it.all {v->v in setOf("jpg","jpeg","png","webp")}}
        }?.let {url->Regex("^/media/([A-Za-z0-9_-]{1,100})(?:\\.(?:jpg|jpeg|png|webp))?$").matchEntire(url.encodedPath)?.let {return "x-photo:"+it.groupValues[1]}}
        return value
    }
    private fun pictures(post:Post)=!post.preview&&post.recoveredArchive==null&&post.publishedAt?.let {it>0}==true&&
        post.body.length.toLong()+post.title.length<=4096&&post.media.isNotEmpty()&&post.media.size<=12&&post.media.all {it.type=="image"&&it.url!=post.avatar}
    fun potential(posts:List<Post>)=posts.size in 2..CrossPosts.MAX_EDITIONS&&
        posts.any {it.platform==Platform.X&&it.parentId==null&&it.media.size==4&&pictures(it)}&&
        posts.any {it.platform!=Platform.X&&it.parentId==null&&it.media.size in 5..12&&pictures(it)}
    internal fun request(posts:List<Post>,owners:Map<String,String?>,members:(Post)->Set<String>):GalleryContinuationRequest? {
        if(!potential(posts))return null
        val roots=posts.filter {it.platform==Platform.X&&it.parentId==null&&it.media.size==4&&pictures(it)}.sortedBy(CrossPosts::originalKey)
        for(root in roots) {
            val owner=owners[CrossPosts.originalKey(root)] ?: continue
            val ids=members(root);if(ids.isEmpty())continue
            val account=CrossPosts.originalAccount(root) ?: continue
            val own=posts.filter {it.platform==Platform.X&&CrossPosts.originalAccount(it)==account&&pictures(it)}
            val known=own.flatMap {it.media}.distinctBy {ImageAssetIdentity.key(it.url) ?: it.url}
            if(known.size>48)continue
            for(peer in posts.filter {it.platform!=Platform.X&&it.parentId==null&&it.media.size in 5..12&&pictures(it)&&owners[CrossPosts.originalKey(it)]==owner}) {
                if(!CrossPosts.galleryContains(peer,root,{_,_->false}))continue
                // Only an internal proof projection. Never rewrite the root's
                // source media or claim that a different branch supplied it.
                val covered=root.copy(title=peer.title,body=peer.body,media=known)
                if(CrossPosts.galleryContains(covered,peer,{_,_->false}))continue
                return GalleryContinuationRequest(root,peer,posts,ids)
            }
        }
        return null
    }
    /** Supplied relationships nominate same-author descendants only. Their
     * actual artwork still has to pass galleryCollection after acquisition. */
    fun candidates(request:GalleryContinuationRequest,page:Page):List<Post> {
        val root=page.posts.singleOrNull {CrossPosts.originalKey(it)==CrossPosts.originalKey(request.root)} ?: return emptyList()
        if(!sameInput(request.root,root))return emptyList()
        val known=request.editions.mapTo(hashSetOf(),CrossPosts::originalKey)
        return PublisherThreads.descendants(root,page.posts).filter {CrossPosts.originalKey(it) !in known&&pictures(it)&&it.media.size<=4&&
            kotlin.math.abs(requireNotNull(it.publishedAt)-requireNotNull(root.publishedAt))<=6*60*60_000L}.take(1)
    }
    fun sameInput(a:Post,b:Post)=CrossPosts.originalKey(a)==CrossPosts.originalKey(b)&&a.publishedAt==b.publishedAt&&
        PostPresentation.comparableContent(a)==PostPresentation.comparableContent(b)&&
        a.media.map {it.type to inputAsset(it.url)}==b.media.map {it.type to inputAsset(it.url)}
    fun proven(request:GalleryContinuationRequest,children:List<Post>):Boolean=children.isNotEmpty()&&children.size<=2&&
        CrossPosts.galleryCollection((request.editions.filter {pictures(it)}+children).distinctBy(CrossPosts::originalKey))
}
