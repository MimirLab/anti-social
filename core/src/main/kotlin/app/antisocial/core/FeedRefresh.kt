/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

/** A reachable source is not evidence that a subscription was checked successfully. */
object FeedRefresh {
    /** Invalid partial responses must not overwrite the last complete cached post. */
    fun posts(page: Page,platform: Platform): List<Post> {
        if(page.provenance?.cached==true||page.provenance?.source?.role==SourceRole.CACHE)return emptyList()
        return page.posts.filter {post->
            val source=post.provenance ?: page.provenance
            val dateKnownOrOptional=post.publishedAt!=null||platform==Platform.RSS&&source?.source?.let {it.platform==Platform.RSS&&it.frontend=="rss"}==true
            post.platform==platform&&post.parentId==null&&dateKnownOrOptional&&
                !post.preview&&post.recoveredArchive==null&&
                (post.body.isNotBlank()||post.title.isNotBlank()||post.media.any {it.url.isNotBlank()})&&
                source!=null&&!source.cached&&source.source.role!=SourceRole.CACHE&&source.source.frontend !in PublicSearchAdapter.frontends
        }
    }
    fun pendingReason(page: Page,platform: Platform): String? {
        page.continuationIssue?.takeIf {it.isNotBlank()}?.let {return it.take(300)}
        if(page.provenance?.cached==true||page.provenance?.source?.role==SourceRole.CACHE)
            return "Only saved content was available; the subscription is still pending."
        if(page.provenance?.source?.frontend in PublicSearchAdapter.frontends)
            return "Public indexed posts are available; the subscription's latest feed is still pending."
        if(posts(page,platform).isEmpty())return if(platform==Platform.RSS)"The source did not supply readable RSS or Atom entries; this subscription is still pending."else "The source did not supply a fresh readable dated feed; this subscription is still pending."
        return null
    }
}
