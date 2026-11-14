/* anti social, GPL-3.0-or-later. Source-rendered echoes never rewrite originals. */
package app.antisocial.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Read-only recovery of one unambiguous RSS quote/card boundary after the
 * original HTML was flattened. A complete root caption, typed repost/profile,
 * quoted account/citation and supplied destination must all agree. */
internal object NitterRssQuoteText {
    private val account=Regex("[A-Za-z0-9_]{1,15}")
    private val status=Regex("[0-9]{1,40}")
    private val header=Regex("^([^@\\r\\n]{1,160}) \\(@([A-Za-z0-9_]{1,15})\\) ")
    private val accountLabel=Regex("\\(@[A-Za-z0-9_]{1,15}\\)")
    private fun https(raw:String):HttpUrl?=raw.takeIf {it.length in 1..8192&&it.none(Char::isISOControl)}?.toHttpUrlOrNull()
        ?.takeIf {it.isHttps&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()}
    private fun relative(url:HttpUrl,root:HttpUrl):List<String>? {
        if(url.host!=root.host||url.port!=root.port)return null
        val prefix=root.pathSegments.filter(String::isNotEmpty)
        return url.pathSegments.takeIf {it.take(prefix.size)==prefix}?.drop(prefix.size)?.filter(String::isNotEmpty)
    }
    private fun destinationLabel(label:String,destination:String,url:HttpUrl):Boolean {
        if(label.any(Char::isWhitespace))return false
        val full=destination.removePrefix("https://")
        val displayed=label.removePrefix("https://")
        if(displayed==full)return true
        val prefix=when {displayed.endsWith('…')->displayed.dropLast(1);displayed.endsWith("...")->displayed.dropLast(3);else->return false}
        // A bare abbreviated host cannot identify one of its many pages.
        return prefix.length>=url.host.length+9&&prefix.startsWith(url.host+"/")&&full.startsWith(prefix)
    }
    fun compact(post:Post,caption:String,repostedBy:String?,body:String):String? {
        if(post.platform!=Platform.X||post.preview||post.parentId!=null||post.recoveredArchive!=null||body.length !in 1..16_384||
            caption.length !in 1..4096||!body.startsWith(caption+" ")||
            body.any {it=='\u0000'||it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'})return null
        val remainder=body.substring(caption.length+1)
        val quoted=header.find(remainder) ?: return null
        if(" Link " !in remainder||" — https://" !in remainder)return null
        val provenance=post.provenance ?: return null;val source=provenance.source
        if(source.platform!=Platform.X||source.frontend!="nitter"||source.role!=SourceRole.ALTERNATE)return null
        val root=https(source.root)?.takeIf {it.query==null&&it.fragment==null} ?: return null
        val final=https(provenance.finalUrl)?.takeIf {it.fragment==null&&it.queryParameterNames.all {key->key=="cursor"}} ?: return null
        val feed=relative(final,root)?.takeIf {it.size==2&&it[1]=="rss"&&account.matches(it[0])}?.first() ?: return null
        val original=https(post.canonicalUrl)?.takeIf {SocialLinkHosts.platform(post.canonicalUrl)==Platform.X} ?: return null
        val originalParts=original.pathSegments.filter(String::isNotEmpty)
        if(originalParts.size!=3||!account.matches(originalParts[0])||originalParts[1] !in setOf("status","statuses")||!status.matches(originalParts[2]))return null
        if(repostedBy==null) {
            if(!originalParts[0].equals(feed,true))return null
        }else {
            if(!repostedBy.removePrefix("@").equals(feed,true)||post.publicRelations.orEmpty().none {relation->
                if(relation.kind!=PublicRelationKind.REPOSTED_BY)return@none false
                val target=https(relation.url) ?: return@none false
                SocialLinkHosts.platform(relation.url)==Platform.X&&target.query==null&&target.fragment==null&&
                    target.pathSegments.filter(String::isNotEmpty).singleOrNull()?.equals(feed,true)==true
            })return null
        }
        if(accountLabel.findAll(remainder).take(2).count()!=1)return null
        val name=quoted.groupValues[1];val handle=quoted.groupValues[2]
        val links=post.links.orEmpty().distinct().takeIf {it.size in 1..PostLinks.MAX_LINKS} ?: return null
        val footers=links.filter {value->
            if(!remainder.endsWith(" — "+value))return@filter false
            val url=https(value) ?: return@filter false
            val parts=relative(url,root) ?: return@filter false
            url.query==null&&url.fragment in setOf(null,"m")&&parts.size==3&&parts[0].equals(handle,true)&&
                parts[1]=="status"&&status.matches(parts[2])&&remainder.endsWith(" — "+value)
        }
        val footer=footers.singleOrNull() ?: return null
        val tail=" — "+footer
        if(remainder.indexOf(tail)!=remainder.lastIndexOf(tail))return null
        val content=remainder.substring(quoted.range.last+1,remainder.length-tail.length)
        val marker=" Link $name ";val boundary=content.indexOf(marker)
        if(boundary<=0||content.lastIndexOf(marker)!=boundary)return null
        val authored=content.substring(0,boundary);val card=content.substring(boundary+marker.length)
        val readings=links.filter {it!=footer}.mapNotNull {destination->
            if(!card.endsWith(" "+destination))return@mapNotNull null
            val url=https(destination) ?: return@mapNotNull null
            val excerpt=card.dropLast(destination.length+1)
            if(excerpt.length !in 40..1024||CaptionWordScan.count(excerpt,81,simple=true) !in 6..80||!authored.startsWith(excerpt+" "))return@mapNotNull null
            if(!destinationLabel(authored.substring(excerpt.length+1),destination,url))return@mapNotNull null
            caption+" "+quoted.value+excerpt+" "+destination+tail
        }.distinct()
        return readings.singleOrNull()
    }
}
