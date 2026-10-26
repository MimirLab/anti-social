/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import org.jsoup.Jsoup
import java.net.URI
import java.text.Normalizer
import java.util.Locale

data class PostTextPresentation(val title: String,val body: String,val attribution: String?=null)

/** Feed titles often repeat their captions. Keep source records intact and omit
 * only a redundant heading when presenting either fresh or already-cached posts.
 */
object PostPresentation {
    private val visibleUrl=Regex("(?<![\\p{L}\\p{N}_@.])(?:https?://)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}(?::[0-9]{1,5})?(?:[/?#][^\\s<>]*)?",RegexOption.IGNORE_CASE)
    private val repostHeading=Regex("^RT by (@[a-zA-Z0-9_]{1,15}):\\s*(.+)$",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
    private val alternateRepostHeading=Regex("^RT (@[a-zA-Z0-9_]{1,15}) \\(by (@[a-zA-Z0-9_]{1,15})\\):\\s*(.+)$",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
    private val videoLabel=Regex("\\s+Video\\s*$",RegexOption.IGNORE_CASE)
    private val captionWords=Regex("[\\p{L}\\p{N}]+")
    private val inlineHashtag=Regex("(?<![\\p{L}\\p{N}_])#(?=[\\p{L}][\\p{L}\\p{N}_]{2,})")

    fun text(post:Post):PostTextPresentation=CrossPostFusion.presentation(post) ?: textUncached(post).also {CrossPostFusion.rememberPresentation(post,it)}
    private fun textUncached(post: Post): PostTextPresentation {
        if(post.title.isBlank()||post.body.isBlank())return PostTextPresentation(post.title,post.body)
        val title=comparison(post.title)
        val author=comparison(post.author).removePrefix("@")
        val nitter=post.platform==Platform.X&&post.provenance?.source?.frontend=="nitter"
        val rawHeading=if(post.platform==Platform.X)Jsoup.parseBodyFragment(post.title).text()else ""
        val alternate=if(nitter)alternateRepostHeading.matchEntire(rawHeading)?.takeIf {it.groupValues[1].removePrefix("@").equals(author,true)}else null
        val repost=if(post.platform==Platform.X)repostHeading.matchEntire(alternate?.let {"RT by ${it.groupValues[2]}: ${it.groupValues[3]}"} ?: rawHeading)else null
        val repostCaption=repost?.groupValues?.get(2)?.let(::comparison)
        val pinnedCaption=title.takeIf {nitter&&it.startsWith("pinned: ")}?.removePrefix("pinned: ")
        // Some readers add attribution to a generated excerpt. It is redundant
        // only when the named source author and the actual body also agree.
        val unwrapped=if(author.isBlank())null else listOf("post by $author:","post by @$author:")
            .firstOrNull {title.startsWith(it)}?.let {title.removePrefix(it).trimStart()}
        val headings=listOfNotNull(title,unwrapped,repostCaption,pinnedCaption).flatMap {heading->
            // Some public readers prepend a play glyph to their generated
            // heading. Only a proven body echo can remove it.
            listOf(heading,heading.trimStart('▶','\uFE0E','\uFE0F',' '))
        }.distinct()
        var presentedBody=post.body
        val mediaLabel=videoLabel.find(post.body)
        if(nitter&&post.media.isNotEmpty()&&mediaLabel!=null) {
            val without=post.body.substring(0,mediaLabel.range.first).trimEnd()
            // This is an RSS media-link label only when the complete headline
            // independently supplies exactly the same caption without it.
            if(headings.any {it==comparison(without)})presentedBody=without
        }
        val body=comparison(presentedBody)
        fun echo(heading: String): Boolean {
            val suffix=when {heading.endsWith("…")->"…";heading.endsWith("...")->"...";else->null}
            val prefix=suffix?.let {heading.removeSuffix(it).trimEnd()}
            // A reader can append an ellipsis even when its attributed excerpt
            // already contains the entire caption. Require the known author's
            // wrapper for this equal-length case; a distinct editorial heading
            // ending in an ellipsis still participates in presentation.
            val completeAttributedEcho=heading==unwrapped&&prefix==body
            val generatedShort=post.platform in setOf(Platform.INSTAGRAM,Platform.FACEBOOK)&&post.provenance?.source?.frontend in setOf("rss-bridge","rsshub")&&
                prefix!=null&&prefix.length>=12&&captionWords.findAll(prefix).count()>=2
            val truncatedEcho=prefix!=null&&(prefix.length>=24||generatedShort)&&
                (body.length>prefix.length&&body.startsWith(prefix)||completeAttributedEcho)
            val fullPrefix=heading.length>=40&&captionWords.findAll(heading).count()>=6&&body.startsWith("$heading ")&&
                (heading.trimEnd('"','\'','’','”').lastOrNull() in listOf('.','!','?')||heading==repostCaption)
            return heading.isNotEmpty()&&(heading==body||truncatedEcho||fullPrefix)
        }
        val shortenedEcho=repost!=null&&post.provenance?.source?.frontend=="nitter"&&shortenedRssEcho(repost.groupValues[2],presentedBody)
        val redundant=headings.any(::echo)||shortenedEcho
        val attribution=if(repost!=null&&repostCaption!=null&&(echo(repostCaption)||shortenedEcho))"Reposted by ${repost.groupValues[1]}"else null
        return PostTextPresentation(if(redundant)""else post.title,presentedBody,attribution)
    }

    /** Matching may normalize further, but each meaningful field participates once. */
    fun content(post: Post): String=text(post).let {listOf(it.title,it.body).filter(String::isNotBlank).joinToString("\n")}
    internal fun proseWithoutLinks(value:String)=visibleUrl.replace(value," ")
    internal fun referenceFreeCaption(value:String)=comparison(inlineHashtag.replace(proseWithoutLinks(value),"")).trimEnd('.', ' ')
    internal fun destinationLabels(value:String)=visibleUrl.findAll(value).take(16).map {match->
        val literal=match.value.trimEnd('.',',',')',']','}',';')
        if(literal.startsWith("https://",true)||literal.startsWith("http://",true))literal else "https://$literal"
    }

    /** A Nitter RSS heading has full destinations where its caption has shortened
     * labels. Compare every destination in order before omitting that heading. */
    private fun shortenedRssEcho(heading:String,body:String):Boolean {
        val full=visibleUrl.findAll(heading).toList()
        val labels=visibleUrl.findAll(body).toList()
        if(full.isEmpty()||full.size>PostLinks.MAX_LINKS||labels.size<full.size)return false
        if(!full.zip(labels).all {(a,b)->
            val original=runCatching {URI(if(a.value.startsWith("https://"))a.value else "https://"+a.value)}.getOrNull()
            val label=runCatching {URI(if(b.value.startsWith("https://"))b.value else "https://"+b.value)}.getOrNull()
            if(original==null||label==null)false else {
                val path=label.rawPath.orEmpty()
                val shortened=path.endsWith("…")||path.endsWith("...")
                val sameHost=original.host.equals(label.host,true)||original.host in setOf("x.com","twitter.com")&&label.host.orEmpty().startsWith("nitter.")
                sameHost&&if(shortened)original.rawPath.orEmpty().startsWith(path.removeSuffix("…").removeSuffix("..."))else original.rawPath==path
            }
        })return false
        fun caption(text:String)=TextWhitespace.collapse(visibleUrl.replace(text," LINK ")).trim().lowercase(Locale.ROOT)
        val first=caption(heading);val second=caption(body)
        return second==first||second.startsWith(first+" ")
    }

    /** Whole-presentation identity for rendering already-grouped editions. Keep
     * meaningful symbols and URL path/query case instead of token-only equality.
     */
    internal fun comparableContent(post: Post): String=CrossPostFusion.comparison(post) ?: comparison(content(post)).also {CrossPostFusion.rememberComparison(post,it)}

    private fun comparison(value: String): String {
        val plain=Jsoup.parseBodyFragment(value).apply {select("script,style,noscript").remove()}.text()
        val normalized=TextWhitespace.collapse(Normalizer.normalize(plain,Normalizer.Form.NFC).replace("\u200b","").replace("\ufeff","")).trim()
        // RSS headings may spell out a link whose body shows its scheme-less
        // label. Retain the destination, including case-sensitive path/query;
        // an explicit HTTP URL still differs from an explicit HTTPS URL.
        return buildString {
            var end=0
            visibleUrl.findAll(normalized).forEach {match->
                append(normalized.substring(end,match.range.first).lowercase(Locale.ROOT))
                val raw=match.value
                val parsed=runCatching {URI(if(raw.startsWith("https://",true)||raw.startsWith("http://",true))raw else "https://$raw")}.getOrNull()
                if(parsed?.host==null||parsed.userInfo!=null)append(raw.lowercase(Locale.ROOT)) else {
                    if(parsed.scheme.equals("http",true))append("http://")
                    append(parsed.host.lowercase(Locale.ROOT))
                    if(parsed.port!=-1)append(":"+parsed.port)
                    append(parsed.rawPath.orEmpty())
                    parsed.rawQuery?.let {append("?"+it)}
                    parsed.rawFragment?.let {append("#"+it)}
                }
                end=match.range.last+1
            }
            append(normalized.substring(end).lowercase(Locale.ROOT))
        }
    }
}
