/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.text.Normalizer
import java.util.Locale

data class PostTextPresentation(val title: String,val body: String,val attribution: String?=null)

/** Feed titles often repeat their captions. Keep source records intact and omit
 * only a redundant heading when presenting either fresh or already-cached posts.
 */
object PostPresentation {
    private val visibleUrl=Regex("(?<![\\p{L}\\p{N}_@.])(?:https?://)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+(?:xn--[a-z0-9-]{2,59}|[a-z]{2,63})(?![a-z0-9-])(?::[0-9]{1,5})?(?:[/?#][^\\s<>]*)?",RegexOption.IGNORE_CASE)
    private val topLevelDomains by lazy {
        requireNotNull(PostPresentation::class.java.getResourceAsStream("/iana-tlds.txt")).bufferedReader().use {reader->
            reader.lineSequence().filter {it.isNotBlank()&&!it.startsWith('#')}.toSet()+setOf("example","test","invalid")
        }
    }
    /** Bare dotted prose is not automatically a web destination. Explicit URL
     * schemes retain their original meaning, including newly introduced TLDs;
     * scheme-less labels use the bundled IANA root vocabulary. No DNS request. */
    internal fun urlMatches(value:String)=visibleUrl.findAll(value).filter {match->
        val raw=match.value
        raw.startsWith("http://",true)||raw.startsWith("https://",true)||
            raw.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').substringAfterLast('.').lowercase(Locale.ROOT) in topLevelDomains
    }
    private fun replaceUrls(value:String,replacement:String)=buildString {
        var end=0
        urlMatches(value).forEach {match->append(value.substring(end,match.range.first));append(replacement);end=match.range.last+1}
        append(value.substring(end))
    }
    private val repostHeading=Regex("^RT by (@[a-zA-Z0-9_]{1,15}):\\s*(.+)$",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
    private val alternateRepostHeading=Regex("^RT (@[a-zA-Z0-9_]{1,15}) \\(by (@[a-zA-Z0-9_]{1,15})\\):\\s*(.+)$",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
    private val videoLabel=Regex("\\s+Video\\s*$",RegexOption.IGNORE_CASE)
    private val shortenedHostLabel=Regex("(?<![\\p{L}\\p{N}_@.])(?:https?://)?(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z0-9-]+(?:…|\\.\\.\\.)(?!\\S)")
    private val captionWords=Regex("[\\p{L}\\p{N}]+")
    private val inlineHashtag=Regex("(?<![\\p{L}\\p{N}_])#(?=[\\p{L}][\\p{L}\\p{N}_]{2,})")

    fun text(post:Post):PostTextPresentation=CrossPostFusion.presentation(post) ?: textUncached(post).let {text->
        if(text.attribution!=null)text else NitterReposts.handle(post)?.let {
            text.copy(title=if(NitterReposts.emptyMediaHeading(post,it))""else text.title,attribution="Reposted by @$it")
        } ?: text
    }.also {CrossPostFusion.rememberPresentation(post,it)}
    /** RSS-Bridge's Bluesky renderer adds its author/posted header before the
     * authored text (getPostDescription in bridges/BlueskyBridge.php). Remove
     * exactly that root header, scoped to its source and canonical handle.
     * Quoted/reply context and a second, authored occurrence stay intact. */
    private fun sourceBody(post:Post):String {
        // This API retains Twitter's HTML entity transport escaping. Decode
        // once for native text; an authored "&amp;" was encoded as "&amp;amp;".
        if(post.platform==Platform.X&&post.provenance?.source?.frontend=="sotwe-api")return Parser.unescapeEntities(post.body,false)
        if(post.platform==Platform.INSTAGRAM&&post.provenance?.source?.frontend=="rss-bridge")return Parser.unescapeEntities(post.body,false)
        if(post.platform!=Platform.BLUESKY||post.provenance?.source?.frontend!="rss-bridge")return post.body
        val path=runCatching {URI(post.canonicalUrl).path.trim('/').split('/')}.getOrNull() ?: return post.body
        if(path.size!=4||path[0]!="profile"||path[2]!="post")return post.body
        val handle=path[1].takeIf {it.matches(Regex("[A-Za-z0-9.-]{3,253}"))} ?: return post.body
        val header=Regex("^(?:[^@\\r\\n]{1,150}\\s+)?@${Regex.escape(handle)}\\s+posted:\\s*",RegexOption.IGNORE_CASE).find(post.body) ?: return post.body
        return post.body.substring(header.range.last+1)
    }
    private fun textUncached(post: Post): PostTextPresentation {
        val sourceBody=sourceBody(post)
        // FacebookAdapter adds this label for an attached shared story. Keep
        // the attribution outside the authored caption so an uncaptioned share
        // can collect with that story's other editions. An author's own leading
        // commentary, other sources and ordinary inline text remain untouched.
        if(post.platform==Platform.FACEBOOK&&post.title.isBlank()&&post.provenance?.source?.frontend=="original"&&
            runCatching {URI(post.provenance.finalUrl).let {it.host in setOf("www.facebook.com","facebook.com")&&it.path=="/api/graphql/"}}.getOrDefault(false)) {
            Regex("^Shared from ([^\\r\\n:]{1,150}):\\r?\\n(?=\\S)").find(sourceBody)?.let {header->
                return PostTextPresentation("",sourceBody.substring(header.range.last+1),header.value.trimEnd().removeSuffix(":"))
            }
        }
        if(post.title.isBlank()||sourceBody.isBlank())return PostTextPresentation(post.title,sourceBody)
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
        var presentedBody=sourceBody
        val mediaLabel=videoLabel.find(sourceBody)
        if(nitter&&post.media.isNotEmpty()&&mediaLabel!=null) {
            val without=sourceBody.substring(0,mediaLabel.range.first).trimEnd()
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
        val expanded=if(repost!=null&&nitter)expandedRssBody(repost.groupValues[2],presentedBody,post.links.orEmpty())else null
        val shortenedEcho=expanded!=null
        if(expanded!=null) {
            presentedBody=expanded
            // A shortened host can prevent the earlier media-label echo check.
            // Recheck only after the source-supplied destination is restored.
            videoLabel.find(expanded)?.takeIf {post.media.isNotEmpty()}?.let {label->
                val without=expanded.substring(0,label.range.first).trimEnd()
                if(headings.any {it==comparison(without)})presentedBody=without
            }
        }
        val bridgeTitleEcho=sourceBody!=post.body&&captionWords.findAll(title).count()>=3&&body.startsWith("$title ")
        val redundant=headings.any(::echo)||shortenedEcho||bridgeTitleEcho
        val attribution=if(repost!=null&&repostCaption!=null&&(echo(repostCaption)||shortenedEcho))"Reposted by ${repost.groupValues[1]}"else null
        return PostTextPresentation(if(redundant)""else post.title,presentedBody,attribution)
    }

    /** Matching may normalize further, but each meaningful field participates once. */
    fun content(post: Post): String=text(post).let {listOf(it.title,it.body).filter(String::isNotBlank).joinToString("\n")}
    internal fun proseWithoutLinks(value:String)=replaceUrls(value," ")
    internal fun referenceFreeCaption(value:String)=comparison(inlineHashtag.replace(proseWithoutLinks(value),"")).trimEnd('.', ' ')
    internal fun destinationLabels(value:String)=urlMatches(value).take(16).map {match->
        val literal=match.value.trimEnd('.',',',')',']','}',';')
        if(literal.startsWith("https://",true)||literal.startsWith("http://",true))literal else "https://$literal"
    }

    /** A Nitter RSS heading has full destinations where its caption has shortened
     * labels. Compare every destination in order before omitting that heading. */
    private fun expandedRssBody(heading:String,body:String,sourceLinks:List<String>):String? {
        val full=urlMatches(heading).toList()
        val ordinary=urlMatches(body).toList()
        // Nitter can truncate inside the TLD itself. Such a label is not a
        // destination; qualify its prefix against the exact href retained from
        // this response before using it as a generated caption echo.
        val short=shortenedHostLabel.findAll(body).filter {label->ordinary.none {it.range.first<=label.range.last&&label.range.first<=it.range.last}}.toList()
        val labels=(ordinary+short).sortedBy {it.range.first}
        if(full.isEmpty()||full.size>PostLinks.MAX_LINKS||labels.size<full.size)return null
        if(!full.zip(labels).all {(a,b)->
            fun explicit(value:String)=value.startsWith("https://",true)||value.startsWith("http://",true)
            if(b in short) {
                val prefix=b.value.removeSuffix("…").removeSuffix("...")
                val original=if(explicit(b.value))a.value else a.value.removePrefix("https://").removePrefix("http://")
                return@all prefix.length>=12&&a.value in sourceLinks&&original.startsWith(prefix)
            }
            val original=runCatching {URI(if(explicit(a.value))a.value else "https://"+a.value)}.getOrNull()
            val label=runCatching {URI(if(explicit(b.value))b.value else "https://"+b.value)}.getOrNull()
            if(original?.host==null||label?.host==null||original.userInfo!=null||label.userInfo!=null||original.port!=label.port||
                explicit(b.value)&&original.scheme!=label.scheme)false else {
                val path=label.rawPath.orEmpty()
                val shortened=path.endsWith("…")||path.endsWith("...")
                val sameHost=original.host.equals(label.host,true)||!explicit(b.value)&&original.host.removePrefix("www.").equals(label.host.removePrefix("www."),true)||
                    original.host in setOf("x.com","twitter.com")&&label.host.orEmpty().startsWith("nitter.")
                sameHost&&if(shortened)label.rawQuery==null&&label.rawFragment==null&&original.rawPath.orEmpty().startsWith(path.removeSuffix("…").removeSuffix("..."))
                    else original.rawPath==path&&original.rawQuery==label.rawQuery&&original.rawFragment==label.rawFragment
            }
        })return null
        fun caption(text:String,matches:List<MatchResult>)=TextWhitespace.collapse(buildString {
            var end=0
            matches.forEach {append(text.substring(end,it.range.first));append(" LINK ");end=it.range.last+1}
            append(text.substring(end))
        }).trim().lowercase(Locale.ROOT)
        val first=caption(heading,full);val second=caption(body,labels)
        if(second!=first&&!second.startsWith(first+" "))return null
        // Use only URLs actually supplied in the generated heading. Preserve
        // the body's exact prose and any additional material after that echo.
        return buildString {
            var end=0
            full.zip(labels).forEach {(original,label)->append(body.substring(end,label.range.first));append(original.value);end=label.range.last+1}
            append(body.substring(end))
        }
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
            urlMatches(normalized).forEach {match->
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
