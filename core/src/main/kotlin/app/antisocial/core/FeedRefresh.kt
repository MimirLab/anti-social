/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

/** A reachable source is not evidence that a subscription was checked successfully. */
object FeedRefresh {
    fun exhausted(page:Page,platform:Platform)=page.historyExhausted==true&&!page.limitedTimeline&&page.next.isNullOrBlank()&&page.continuationIssue.isNullOrBlank()&&page.provenance?.let {p->
        p.source.platform==platform&&!p.cached&&p.source.role!=SourceRole.CACHE&&p.source.frontend !in PublicSearchAdapter.frontends
    }==true
    private val generatedHeading=Regex("^Post by @?([^:]{1,100}):\\s*(.*)$",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
    /** Keep empty frontend wrappers out of the reader without deleting source
     * records or changing explicitly saved copies. Older Kittygram Atom entries
     * used feed-generation time as every post's date; they cannot establish a
     * chronological reading position or overwrite a complete dated edition. */
    fun readable(post:Post):Boolean {
        val heading=generatedHeading.matchEntire(post.title.trim())?.takeIf {it.groupValues[1].equals(post.author.removePrefix("@"),true)}
        if(post.body.isBlank()&&post.media.isEmpty()&&(post.title.isBlank()||heading?.groupValues?.get(2)?.trim()?.trim('.','…')?.isBlank()==true))return false
        val p=post.provenance
        val date=post.publishedAt
        if(heading!=null&&post.media.isEmpty()&&p?.source?.frontend=="kittygram"&&p.finalUrl.substringBefore('?').endsWith("/atom.xml")&&
            date!=null&&date>0&&p.fetchedAt>0&&kotlin.math.abs(date-p.fetchedAt)<=10_000)return false
        return true
    }
    /** Invalid partial responses must not overwrite the last complete cached post. */
    fun posts(page: Page,platform: Platform): List<Post> {
        if(page.provenance?.cached==true||page.provenance?.source?.role==SourceRole.CACHE)return emptyList()
        return page.posts.filter {post->
            val source=post.provenance ?: page.provenance
            val dateKnownOrOptional=post.publishedAt!=null||platform==Platform.RSS&&source?.source?.let {it.platform==Platform.RSS&&it.frontend=="rss"}==true
            post.platform==platform&&post.parentId==null&&dateKnownOrOptional&&
                !post.preview&&post.recoveredArchive==null&&
                readable(post)&&(post.body.isNotBlank()||post.title.isNotBlank()||post.media.any {it.url.isNotBlank()})&&
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
