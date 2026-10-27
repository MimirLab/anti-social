/* anti social, GPL-3.0-or-later. A narrowly proven spelling variation, not identity inference. */
package app.antisocial.core

import org.jsoup.parser.Parser
import java.text.Normalizer
import java.util.Locale

/** @ChandraXray versus "our Chandra X-ray Observatory" should not require an
 * image download to recognize a long otherwise identical caption. Only one
 * bounded name span may differ; surrounding prose, punctuation and destinations
 * must agree. Callers independently prove linked publisher and timing. */
internal object CaptionAliases {
    private val words=Regex("@[\\p{L}\\p{N}_](?:[\\p{L}\\p{N}_.-]{0,99}[\\p{L}\\p{N}_])?|[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val spaces=Regex("[\\s\\p{Z}]+")
    private val leading=setOf("our","the","my","official")
    private val labels=setOf("observatory","agency","department","foundation","institute","university","organization","corporation","company","office","team","project")
    private val mentionArticle=Regex("(?i)^(?:the|our|my|official)\\s+")
    private val qualifiers=labels+setOf("space","telescope","satellite","spacecraft","rover")
    private val competing=setOf("no","not","never","without","new","old","former","different","replacement","fake","counterfeit","one","two","three","four","five","six","seven","eight","nine","ten")
    private val ordinal=Regex("[IVXLCDM]{1,8}")
    private fun normalize(s:String)=spaces.replace(Normalizer.normalize(s,Normalizer.Form.NFC)," ").trim().lowercase(Locale.ROOT)
    private val tags=Regex("(?:\\s+#[\\p{L}\\p{N}_]+){1,8}\\s*$")
    private val urls=Regex("https://[^\\s<>\"']+")
    private fun content(p:Post,mediaConfirmed:Boolean=false):String {
        var s=PostPresentation.content(p);repeat(2){s=Parser.unescapeEntities(s,false)}
        s=tags.replace(s,"")
        if(mediaConfirmed)s=urls.replace(s){if(CrossPosts.opaqueLink(it.value))""else it.value}
        return s.trim()
    }
    private fun compact(s:String)=s.lowercase(Locale.ROOT).filter {it.isLetterOrDigit()}
    fun equivalent(a:Post,b:Post,mediaConfirmed:Boolean=false):Boolean {
        if('@' !in a.body&&'@' !in a.title&&'@' !in b.body&&'@' !in b.title)return false
        if(a.body.length.toLong()+a.title.length>16_384||b.body.length.toLong()+b.title.length>16_384)return false
        val left=content(a,mediaConfirmed);val right=content(b,mediaConfirmed)
        if(normalize(left)==normalize(right))return true
        if(PostPresentation.destinationLabels(left).toList()!=PostPresentation.destinationLabels(right).toList())return false
        val aa=words.findAll(left).take(1025).toList();val bb=words.findAll(right).take(1025).toList()
        if(aa.size !in 30..1024||bb.size !in 30..1024)return false
        var prefix=0;while(prefix<minOf(aa.size,bb.size)&&normalize(aa[prefix].value)==normalize(bb[prefix].value))prefix++
        var suffix=0;while(suffix<minOf(aa.size,bb.size)-prefix&&normalize(aa[aa.lastIndex-suffix].value)==normalize(bb[bb.lastIndex-suffix].value))suffix++
        if(prefix+suffix<24)return false
        val n=aa.size-prefix-suffix;val m=bb.size-prefix-suffix
        if(n !in 1..8||m !in 1..8)return false
        val fromA=aa[prefix].range.first;val fromB=bb[prefix].range.first
        val endA=aa[aa.lastIndex-suffix].range.last+1;val endB=bb[bb.lastIndex-suffix].range.last+1
        if(normalize(left.take(fromA))!=normalize(right.take(fromB))||normalize(left.substring(endA))!=normalize(right.substring(endB)))return false
        // A complete 24-word shared passage can fall just below 160 characters.
        // Strong independent picture proof permits that shorter prose; every
        // ordered proper-name, punctuation, destination and claim guard stays.
        if(left.take(fromA).length+left.substring(endA).length<(if(mediaConfirmed)120 else 160))return false
        fun name(mention:String,prose:String):Boolean {
            val named=if(mediaConfirmed)mentionArticle.replace(mention,"")else mention
            if(!named.startsWith('@')||!words.matches(named)||prose.contains('@'))return false
            val handle=compact(named);if(handle.length<6)return false
            val names=words.findAll(prose).map {it.value}.toList().dropWhile {normalize(it) in leading}.dropLastWhile {normalize(it) in labels}
            val tokens=names.map(::normalize)
            val spelled=tokens.joinToString(""){compact(it)}
            if(tokens.isNotEmpty()&&(spelled==handle||mediaConfirmed&&spelled.length>=8&&handle.endsWith(spelled)&&handle.length-spelled.length in 1..8))return true
            // A compound handle may omit a first name or a work qualifier:
            // @AsterDawn -> Aster's Maya Dawn Space Telescope. Require whole
            // ordered proper-name words, not fuzzy characters or a shared surname.
            // This route is available only after independent strong media proof.
            if(!mediaConfirmed||handle.length<8||names.size !in 2..6)return false
            val parts=names.map {normalize(it).replace('’','\'').removeSuffix("'s")}
            if(parts.any {it in competing||it.any(Char::isDigit)}||names.any {ordinal.matches(it)||it.firstOrNull()?.isUpperCase()!=true})return false
            for(mask in 1 until (1 shl parts.size)) {
                val selected=parts.indices.filter {mask and (1 shl it)!=0}
                if(selected.size<2||parts.size-selected.size>2||selected.first()!=0||selected.any {parts[it].length<3})continue
                if(parts.drop(selected.last()+1).any {it !in qualifiers})continue
                if(selected.joinToString(""){parts[it]}==handle)return true
            }
            return false
        }
        val x=left.substring(fromA,endA);val y=right.substring(fromB,endB)
        fun handles(a:String,b:String):Boolean {
            if(!mediaConfirmed||!words.matches(a)||!words.matches(b)||!a.startsWith('@')||!b.startsWith('@'))return false
            val aa=compact(a);val bb=compact(b);val short=if(aa.length<=bb.length)aa else bb;val long=if(aa.length<=bb.length)bb else aa
            return short.length>=8&&long.endsWith(short)&&long.length-short.length in 1..8
        }
        return name(x,y)||name(y,x)||handles(x,y)
    }
    fun compose(posts:List<Post>,mediaConfirmed:Boolean=false):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS)return null
        if(posts.none {'@' in it.body||'@' in it.title})return null
        val text=posts.map {content(it,mediaConfirmed)}
        val normalized=text.map(::normalize)
        if(normalized.distinct().size<2)return null
        for(i in posts.indices)for(j in i+1 until posts.size)if(normalized[i]!=normalized[j]&&!equivalent(posts[i],posts[j],mediaConfirmed))return null
        val preferred=text.indices.minWith(compareBy<Int> {words.findAll(text[it]).count {w->w.value.startsWith('@')}}.thenByDescending {text[it].length})
        return PostFusion(listOf(FusionSegment(text[preferred],posts.mapTo(linkedSetOf()){it.platform},true,posts.mapTo(linkedSetOf(),CrossPosts::originalKey))))
    }
}
