/* AntiSocial, GPL-3.0-or-later. The same presentation primitives serve the card and Context. */
package app.antisocial.android

import app.antisocial.core.*

internal fun contextReadable(value: String,root: Post,repo: Repository): String {
    val raw=repo.publicText(value,root.platform)
    if(!repo.mentionNames.enabled.value)return raw
    val matches=MentionNames.find(raw,root.platform,repo.publicUrl(root.canonicalUrl,root.platform),repo.routes,root.links.orEmpty())
    return MentionNames.present(raw,matches,repo.mentionNames.names.value).text
}

internal fun contextSnapshot(repo: Repository,displayed: Post,roots: List<Post>,bodyRoots: List<Post> = roots,archived: Boolean=false): ContextSnapshot {
    val originals=roots.distinctBy(CrossPosts::originalKey).take(CrossPosts.MAX_EDITIONS)
    val fusion=if(archived||displayed.recoveredArchive!=null)null else collectedPostFusion(bodyRoots)
    val passages=if(fusion!=null)fusion.segments.map {segment->
        val candidates=bodyRoots.filter {CrossPosts.originalKey(it) in segment.originalKeys}
        val context=fusionMentionContext(candidates,segment.text,repo)
        val root=candidates.firstOrNull {it.platform==context?.platform&&it.canonicalUrl==context?.sourceUrl} ?: candidates.firstOrNull() ?: displayed
        val body=PostBodyDetails.split(segment.text).body
        // Attribution belongs to the typed originals/evidence manifest. The
        // model reads the same cleaned body as the reader, without injected
        // speaker labels that can turn its brief into a narration of the post.
        contextReadable(body,root,repo)
    }else listOf(contextReadable(PostBodyDetails.split(PostPresentation.content(displayed)).body,displayed,repo))
    val text=passages.filter(String::isNotBlank).joinToString("\n\n")
    val bodies=if(archived)listOf(displayed)else bodyRoots
    val links=bodies.flatMap(PostLinks::urls).map {repo.publicUrl(it)}.distinct().take(PostLinks.MAX_LINKS)
    val variant=if(archived)"archive:${ContentRevisions.signature(displayed)}"else "current"
    val revision=Repository.hash("reader-presentation-v3-sources|"+variant+"|"+bodies.joinToString("|"){CrossPosts.originalKey(it)+":"+ContentRevisions.signature(it)}+"|members:"+originals.map(CrossPosts::originalKey).sorted().joinToString("|")+"|reading:"+text+"|links:"+links.joinToString("\n")+"|annotations:"+repo.gson.toJson(originals.map {it.contextNotes}))
    return ContextSnapshot(originals,text,contextReadable(PostPresentation.text(displayed).title,displayed,repo),revision,variant,links,collectedMedia(if(fusion==null)listOf(displayed)else bodyRoots,repo.images.identities))
}
