/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor.
 * Meta label meanings: https://about.fb.com/news/2024/04/metas-approach-to-labeling-ai-generated-content-and-manipulated-media/
 * https://about.fb.com/news/2024/02/labeling-ai-generated-images-on-facebook-instagram-and-threads/
 */
package app.antisocial.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import java.math.BigInteger
import java.net.URI
import java.util.Locale

/** Read a supplied platform label, without requesting a page or judging its pictures.
 * Instagram's observed public Relay response uses xig_polaris_media and the
 * selected media's ai_label_info.attribution_label_text. This is an unsigned
 * platform declaration, distinct from an embedded or verified file credential.
 * Unknown/localized labels and absent labels make no claim about the image.
 */
object PlatformAiLabels {
    internal const val MAX_PAGE_CHARS=2*1024*1024
    internal const val MAX_JSON_CHARS=512*1024
    private const val MAX_JSON_TOTAL=1024*1024
    private const val MAX_SCRIPTS=96
    private const val MAX_NODES=8192
    private const val MAX_DEPTH=24
    private const val ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val transportKeys=setOf("require","__bbox","result","data")
    private val labels=mapOf(
        "ai info" to ("AI info" to AiUse.UNSPECIFIED),
        // Meta explicitly acknowledged that this legacy label included minor
        // AI edits. It does not establish that the entire image was generated.
        "made with ai" to ("Made with AI" to AiUse.UNSPECIFIED),
        "imagined with ai" to ("Imagined with AI" to AiUse.GENERATED),
    )

    fun attach(posts: List<Post>,payload: Payload,target: Target,provenance: Provenance): List<Post> {
        if(target.platform!=Platform.INSTAGRAM||target.kind!=TargetKind.POST||provenance.source.platform!=Platform.INSTAGRAM||provenance.source.role!=SourceRole.ORIGINAL)return posts
        val expected=code(target.canonicalUrl) ?: return posts
        if(code(payload.finalUrl)!=expected||payload.text.length>MAX_PAGE_CHARS||!payload.text.contains("ai_label_info"))return posts
        val text=payload.text.trimStart()
        val scripts=if(text.startsWith("{")||text.startsWith("["))listOf(text)else {
            val document=Jsoup.parse(text,payload.finalUrl)
            val declared=document.select("link[rel=canonical][href],meta[property=og:url][content]")
            if(declared.any {node->code(if(node.normalName()=="link")node.attr("href")else node.attr("content"))!=expected})return posts
            val selected=document.select("script[type=application/json]")
            if(selected.size>MAX_SCRIPTS)return posts
            selected.map {it.data()}
        }
        val relevant=scripts.filter {it.contains("xig_polaris_media")&&it.contains("ai_label_info")}
        if(relevant.isEmpty()||relevant.any {it.length>MAX_JSON_CHARS}||relevant.sumOf {it.length.toLong()}>MAX_JSON_TOTAL)return posts
        val found=mutableListOf<String?>()
        var visited=0
        var limited=false
        fun selected(record: JsonObject) {
            if(!identity(record,expected))return
            val media=if(record.has("if_not_gated_logged_out"))record.obj("if_not_gated_logged_out") ?: return else record
            if(!identity(media,expected)||media.string("__typename") !in setOf("XIGPolarisImageMedia","XIGPolarisVideoMedia","XIGPolarisCarouselMedia"))return
            val ai=media.obj("ai_label_info") ?: return
            val value=ai.get("attribution_label_text") ?: return
            found+=if(value.isJsonNull)null else value.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
                ?.takeIf {it.length<=80}?.replace(Regex("\\s+")," ")?.trim()?.lowercase(Locale.ROOT)
        }
        fun walk(value: JsonElement,depth: Int) {
            if(limited)return
            if(depth>MAX_DEPTH||++visited>MAX_NODES){limited=true;return}
            when {
                value.isJsonArray->{
                    if(value.asJsonArray.size()>256){limited=true;return}
                    value.asJsonArray.forEach {walk(it,depth+1)}
                }
                value.isJsonObject->{
                    val node=value.asJsonObject
                    node.obj("xig_polaris_media")?.let(::selected)
                    // Traverse the transport envelope only. In particular, never
                    // enter users, comments, recommendations or their media.
                    transportKeys.forEach {key->node.get(key)?.let {walk(it,depth+1)}}
                }
            }
        }
        relevant.forEach {raw->
            val value=runCatching {JsonParser.parseString(raw)}.getOrNull() ?: return posts
            walk(value,0)
        }
        if(limited||found.isEmpty()||found.distinct().size!=1)return posts
        val label=labels[found.first() ?: return posts] ?: return posts
        val disclosure=AiDisclosure(label.second,AiEvidence.PLATFORM_LABEL,label=label.first)
        return posts.map {post->
            if(post.platform==Platform.INSTAGRAM&&post.parentId==null&&code(post.canonicalUrl)==expected)
                post.copy(aiDisclosures=(post.aiDisclosures.orEmpty()+disclosure).distinct().take(64))else post
        }
    }

    private fun identity(record: JsonObject,expected: String): Boolean {
        if(record.string("code")!=expected)return false
        val pk=record.string("pk")?.takeIf {it.matches(Regex("[1-9][0-9]{0,19}"))} ?: return false
        val number=runCatching {BigInteger(pk)}.getOrNull()?.takeIf {it.bitLength()<=64} ?: return false
        var n=number
        val encoded=StringBuilder()
        while(n.signum()>0){encoded.append(ALPHABET[n.and(BigInteger.valueOf(63)).toInt()]);n=n.shiftRight(6)}
        if(encoded.reverse().toString()!=expected)return false
        val id=record.string("id")
        if(id!=null&&id!=pk&&id!="POLARIS_$pk")return false
        val canonical=record.string("seo_canonical_url")
        return canonical==null||code(canonical)==expected
    }
    private fun code(url: String): String?=runCatching {
        val uri=URI(url)
        if(!uri.scheme.equals("https",true)||uri.userInfo!=null||uri.port !in setOf(-1,443)||uri.host?.lowercase(Locale.ROOT) !in setOf("instagram.com","www.instagram.com","m.instagram.com"))null
        else Regex("^/(?:p|reel|reels)/([A-Za-z0-9_-]{1,15})/?(?:embed/(?:captioned/)?)?$").matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
    }.getOrNull()
    private fun JsonObject.obj(key: String)=get(key)?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonObject.string(key: String)=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
}
