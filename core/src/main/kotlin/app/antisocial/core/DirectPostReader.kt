/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import kotlinx.coroutines.CancellationException

/** Direct-post recovery order, independent of transport and source-health learning. */
class DirectPostReader {
    suspend fun read(
        target: Target,
        live: suspend ()->Page,
        cached: suspend ()->Page?,
        recover: suspend ()->Page?,
        onPartial: suspend (Page)->Unit={},
        progress: (String)->Unit={}
    ): Page {
        val wanted=ContentIdentity.key(target.platform,target.canonicalUrl)
        if(target.kind !in setOf(TargetKind.POST,TargetKind.THREAD)||!wanted.startsWith("post:"))return live()

        fun matching(post: Post)=post.platform==target.platform&&ContentIdentity.key(post.platform,post.canonicalUrl)==wanted
        fun readable(post: Post)=post.body.isNotBlank()||post.title.isNotBlank()||post.media.any {it.url.isNotBlank()}
        fun complete(page: Page?,archived: Boolean=false)=page?.posts?.firstOrNull {matching(it)&&readable(it)&&!it.preview&&(!archived||it.recoveredArchive!=null)}
        fun selected(page: Page,root: Post): Page=page.copy(posts=listOf(root)+Discussions.comments(root,page),profile=null,accounts=emptyList(),collections=emptyList())

        var current: Page?=null
        var liveFailure: Exception?=null
        try {current=live()}catch(e: CancellationException){throw e}catch(e: Exception){liveFailure=e}
        complete(current)?.let {return selected(requireNotNull(current),it)}
        val preview=current?.posts?.firstOrNull {matching(it)&&readable(it)&&it.preview}
        val currentDetail=liveFailure?.message?.takeIf(String::isNotBlank)
            ?: if(preview!=null)"Current sources supplied a preview only."else "Current sources did not supply the requested post."

        val saved=try {cached()}catch(e: CancellationException){throw e}catch(_: Exception){null}
        complete(saved)?.let {root->
            val page=selected(requireNotNull(saved),root)
            return page.copy(provenance=page.provenance?.copy(cached=true),posts=page.posts.map {it.copy(provenance=it.provenance?.copy(cached=true))},notice=notice(page.notice,"Showing the saved copy; current content could not be loaded."))
        }
        if(preview!=null)onPartial(selected(requireNotNull(current),preview))
        progress("Checking public archives…")

        var archiveFailure: Exception?=null
        val archived=try {recover()}catch(e: CancellationException){throw e}catch(e: Exception){archiveFailure=e;null}
        complete(archived,archived=true)?.let {return selected(requireNotNull(archived),it)}
        val archiveDetail=when {
            archiveFailure!=null->"Public archives are unavailable: ${archiveFailure.message?.takeIf(String::isNotBlank) ?: "the archive check failed"}"
            archived!=null->"Public archives did not supply a readable capture of this exact post."
            else->"Public archives have no readable capture of this post."
        }
        val detail=notice("Full content is unavailable.",currentDetail,archiveDetail)
        if(preview!=null)return selected(requireNotNull(current),preview).copy(notice=notice(current?.notice,"Only a public preview is available.",detail))
        val status=(archiveFailure as? SourceFailure)?.status ?: (liveFailure as? SourceFailure)?.status ?: if(liveFailure!=null)HealthStatus.UNAVAILABLE else HealthStatus.EMPTY
        throw SourceFailure(status,detail)
    }
    private fun notice(vararg values: String?): String=values.mapNotNull {it?.trim()?.takeIf(String::isNotEmpty)}.distinct().joinToString(" ")
}
