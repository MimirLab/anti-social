/* anti social, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element

/** Read a published repost label from the already admitted profile document.
 * The plain display name in a retweet header is not an account identifier:
 * bind it to the requested profile's own linked name and username first.
 * This records sharing, never ownership of the original author's account.
 */
internal object NitterReposts {
    private val username=Regex("[A-Za-z0-9_]{1,15}")
    data class Curator(val url:String,val name:String) {
        fun relation(card:Element):List<PublicRelation>? {
            val headers=card.select(".retweet-header").filter {header->
                header.parents().takeWhile {it!==card}.none {ancestor->
                    ancestor.hasClass("quote")||ancestor.hasClass("quote-big")||ancestor.hasClass("quoted-tweet")||
                        ancestor.hasClass("tweet-content")||ancestor.hasClass("timeline-item")||ancestor.hasClass("main-tweet")
                }
            }
            val header=headers.singleOrNull() ?: return null
            if(TextWhitespace.collapse(header.text()).trim()!="$name retweeted")return null
            return listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,url,name))
        }
    }

    fun curator(document:Element,target:Target,provenance:Provenance,routes:DarwinRoutes):Curator? {
        if(target.platform!=Platform.X||target.kind!=TargetKind.PROFILE||provenance.source.platform!=Platform.X||
            provenance.source.frontend!="nitter"||provenance.source.role!=SourceRole.ALTERNATE)return null
        val account=MentionNames.account(target,routes)?.takeIf {it.target.platform==Platform.X} ?: return null
        val expected=runCatching {routes.convert(target,provenance.source).toHttpUrlOrNull()}.getOrNull() ?: return null
        val actual=provenance.finalUrl.toHttpUrlOrNull() ?: return null
        if(expected.scheme!="https"||actual.scheme!="https"||actual.host!=expected.host||actual.port!=expected.port||
            actual.username.isNotEmpty()||actual.password.isNotEmpty()||actual.encodedPath.trimEnd('/')!=expected.encodedPath.trimEnd('/')||
            actual.fragment !in setOf(null,"m")||actual.queryParameterNames.any {it!="cursor"}||
            actual.queryParameterValues("cursor").let {it.size>1||it.any {v->v.isNullOrBlank()||v.length>4096}})return null
        fun matches(node:Element):Boolean {
            val link=actual.resolve(node.attr("href")) ?: return false
            if(link.username.isNotEmpty()||link.password.isNotEmpty()||link.query!=null||link.fragment!=null)return false
            return link.scheme=="https"&&link.host==expected.host&&link.port==expected.port&&
                link.encodedPath.trimEnd('/').equals(expected.encodedPath.trimEnd('/'),true)
        }
        val profile=document.select(".profile-card").filter {node->node.parents().none {
            it.hasClass("timeline-item")||it.hasClass("main-tweet")||it.hasClass("quote")
        }}.singleOrNull() ?: return null
        val username=profile.select("a.profile-card-username[href]").singleOrNull()?.takeIf(::matches) ?: return null
        if(!username.text().trim().equals("@${account.key.username}",true))return null
        val fullname=profile.select("a.profile-card-fullname[href]").singleOrNull()?.takeIf(::matches) ?: return null
        val name=TextWhitespace.collapse(fullname.text()).trim().takeIf {it.isNotBlank()&&it.length<=160} ?: return null
        return Curator(account.target.canonicalUrl,name)
    }

    /** Data-only presentation also survives a saved snapshot read through a
     * different current source. It grants no request or account-link authority. */
    fun handle(post:Post):String? {
        if(post.platform!=Platform.X)return null
        val relation=(post.publicRelations ?: return null).filter {it.kind==PublicRelationKind.REPOSTED_BY}.singleOrNull() ?: return null
        val url=relation.url.toHttpUrlOrNull() ?: return null
        if(url.scheme!="https"||url.host!="x.com"||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty()||url.query!=null||url.fragment!=null)return null
        return url.pathSegments.singleOrNull()?.takeIf {it.matches(username)}
    }
}
