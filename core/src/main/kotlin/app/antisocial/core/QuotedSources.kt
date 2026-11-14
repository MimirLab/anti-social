/* anti social, GPL-3.0-or-later. Direct curated quotation, not account ownership. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A complete, literally quoted announcement can be read once beside the
 * curator's own commentary. Only an exact captured source reference and a
 * source-bound repost role qualify. Independent essays keep the old gates. */
object QuotedSources {
    private val status=Regex("/([A-Za-z0-9_]{1,15})/status/([0-9]{1,40})/?")
    private data class Reference(val account:String,val id:String)
    private data class Plan(val docs:List<AnnouncementEditions.Caption>,val message:String)
    private fun reference(raw:String,post:Post):Reference? {
        val url=raw.toHttpUrlOrNull() ?: return null
        if(!url.isHttps||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty()||url.query!=null||url.fragment !in setOf(null,"m"))return null
        val path=if(url.host in setOf("x.com","www.x.com","twitter.com","www.twitter.com"))url.encodedPath else {
            val source=post.provenance?.source ?: return null
            val root=source.root.toHttpUrlOrNull() ?: return null
            if(source.platform!=Platform.X||source.frontend!="nitter"||source.role!=SourceRole.ALTERNATE||!root.isHttps||root.port!=443||
                root.username.isNotEmpty()||root.password.isNotEmpty()||root.query!=null||root.fragment!=null||url.host!=root.host)return null
            val base=root.encodedPath.trimEnd('/')
            if(!url.encodedPath.startsWith("$base/"))return null
            url.encodedPath.removePrefix(base)
        }
        val match=status.matchEntire(path) ?: return null
        return Reference(match.groupValues[1].lowercase(java.util.Locale.ROOT),match.groupValues[2])
    }
    private fun target(post:Post):Reference? {
        if(post.platform!=Platform.X||NitterReposts.handle(post)==null)return null
        // Every post-shaped destination must identify this one source. A
        // roundup or nested quotation cannot create transitive membership.
        val links=PostLinks.urls(post)
        if(links.size>32)return null
        val postLinks=links.filter {raw->
            val path=raw.toHttpUrlOrNull()?.encodedPath.orEmpty()
            path.contains("/status/")||SocialLinkHosts.platform(raw)?.let {p->ContentIdentity.key(p,raw).startsWith("post:")}==true
        }
        return postLinks.map {reference(it,post) ?: return null}.distinct().singleOrNull()
    }
    private fun own(post:Post):Reference?=if(post.platform==Platform.X&&SocialLinkHosts.platform(post.canonicalUrl)==Platform.X)reference(post.canonicalUrl,post)else null
    private fun eligible(post:Post)=!post.preview&&post.parentId==null&&post.recoveredArchive==null&&post.publishedAt?.let {it>0}==true
    private fun plan(posts:List<Post>):Plan? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size||posts.any {!eligible(it)})return null
        val times=posts.map {requireNotNull(it.publishedAt)}
        if(times.max()-times.min()>CrossPosts.MAX_REPEAT_WINDOW)return null
        val quoted=posts.mapNotNull {post->target(post)?.let {post to it}}
        if(quoted.isEmpty())return null
        val destination=quoted.map {it.second}.distinct().singleOrNull() ?: return null
        val root=posts.singleOrNull {own(it)==destination} ?: return null
        val leaves=quoted.map {it.first}.toSet()
        if(root in leaves||leaves.any {post->!CrossPosts.curatedQuotationSource(post,root)||requireNotNull(post.publishedAt)<requireNotNull(root.publishedAt)})return null
        // Ordinary collections leave through the cheap role/reference gates
        // above without preparing another caption bank on every card.
        val docs=posts.map {AnnouncementEditions.caption(it) ?: return null}
        val message=AnnouncementEditions.messageKey(docs.single {it.post==root})
        val words=message.split(' ')
        if(message.length<160||words.size<24||words.distinct().size<12)return null
        val roots=docs.filter {it.post !in leaves}
        if(roots.any {AnnouncementEditions.messageKey(it)!=message})return null
        for(doc in docs.filter {it.post in leaves}) {
            val text=AnnouncementEditions.messageKey(doc)
            val start=text.indexOf(message)
            if(start<0||text.indexOf(message,start+message.length)>=0||start>0&&!text[start-1].isWhitespace()||
                start+message.length<text.length&&!text[start+message.length].isWhitespace())return null
        }
        return Plan(docs,message)
    }
    /** Portable source/content relation for an explicitly saved collection.
     * This grants neither account ownership nor new retrieval authority. */
    fun accepts(posts:List<Post>):Boolean=plan(posts)!=null
    fun compose(posts:List<Post>):PostFusion? {
        val plan=plan(posts) ?: return null
        val docs=plan.docs;val message=plan.message
        val parts=docs.map {doc->val text=AnnouncementEditions.messageKey(doc);val start=text.indexOf(message)
            text.substring(0,start).trim() to text.substring(start+message.length).trim()}
        fun variant(values:List<String>)=FusionRun(false,docs.indices.groupBy {values[it]}.values.map {indexes->
            val value=values[indexes.first()]
            FusionVariant(value,indexes.mapTo(linkedSetOf()){docs[it].post.platform},indexes.mapTo(linkedSetOf()){CrossPosts.originalKey(docs[it].post)},
                if(value in setOf("—","–","-"))""else null)
        })
        val runs=listOf(variant(parts.map {it.first}),FusionRun(true,listOf(FusionVariant(message,posts.mapTo(linkedSetOf()){it.platform},posts.mapTo(linkedSetOf(),CrossPosts::originalKey)))),variant(parts.map {it.second}))
        return AnnouncementEditions.finishPassages(docs,runs,labelActors=true)
    }
    /** Frozen anchors and one exact destination: accepted leaves never become
     * new roots, even when a third post quotes the newly collected commentary. */
    internal fun collect(groups:List<PostGroup>,owners:Map<String,String?>,checkpoint:()->Unit):List<PostGroup> {
        if(groups.size<2)return groups
        val targets=groups.flatMapIndexed {index,group->checkpoint();group.posts.mapNotNull {post->own(post)?.let {it to index}}}.groupBy({it.first},{it.second})
        val accepted=linkedMapOf<Int,MutableList<Int>>()
        for((index,group) in groups.withIndex()) {
            checkpoint()
            val destination=group.posts.map {target(it) ?: return@map null}.distinct().singleOrNull() ?: continue
            val anchor=targets[destination]?.distinct()?.singleOrNull() ?: continue
            if(anchor==index||groups[anchor].posts.any {target(it)!=null})continue
            val owner=(group.posts+groups[anchor].posts).map {owners[CrossPosts.originalKey(it)]}.distinct().singleOrNull() ?: continue
            if(owner.isBlank())continue
            val previous=accepted[anchor].orEmpty()
            val all=groups[anchor].posts+(previous+index).flatMap {groups[it].posts}
            if(all.size<=CrossPosts.MAX_EDITIONS&&compose(all)!=null)accepted.getOrPut(anchor){mutableListOf()}.add(index)
        }
        val removed=accepted.values.flatten().toSet()
        return groups.indices.filter {it !in removed}.map {index->
            val leaves=accepted[index].orEmpty()
            if(leaves.isEmpty())groups[index]else PostGroup((groups[index].posts+leaves.flatMap {groups[it].posts})
                .sortedWith(compareBy<Post>{it.publishedAt ?: Long.MAX_VALUE}.thenBy(CrossPosts::originalKey)))
        }
    }
}
