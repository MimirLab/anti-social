/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor.
 * References: https://schema.org/digitalSourceType and the IPTC Digital Source
 * Type vocabulary. These are page declarations, not verified media credentials.
 */
package app.antisocial.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Read only declarations belonging to a record whose post identity was already checked. */
object StructuredAiMetadata {
    private val mediaTypes=setOf("ImageObject","VideoObject","AudioObject","MediaObject")
    private val workTypes=mediaTypes+setOf("CreativeWork","SocialMediaPosting","DiscussionForumPosting","BlogPosting","Article","NewsArticle")
    private val sourceKeys=listOf("digitalSourceType","https://schema.org/digitalSourceType","http://schema.org/digitalSourceType")
    private val attachmentKeys=listOf("image","video","audio","associatedMedia")

    fun declarations(record: JsonObject): List<AiDisclosure> {
        val found=ArrayList<AiDisclosure>()
        var remaining=32
        fun types(node: JsonObject)=values(node.get("@type")).mapNotNull(::string).map {it.removePrefix("https://schema.org/").removePrefix("http://schema.org/")}.toSet()
        fun visit(node: JsonObject,depth: Int) {
            if(depth>2||--remaining<0)return
            val nodeTypes=types(node)
            if(nodeTypes.none {it in if(depth==0)workTypes else mediaTypes})return
            val attachment=depth>0||nodeTypes.any {it in mediaTypes}
            val asset=if(attachment)assetUrl(node)else null
            sourceKeys.forEach {key->values(node.get(key)).take(8).forEach {value->
                val uri=string(value) ?: value.takeIf {it.isJsonObject}?.asJsonObject?.get("@id")?.let(::string)
                val use=sourceType(uri)
                if(use!=null)found+=AiDisclosure(use,AiEvidence.STRUCTURED_DATA,sourceType=uri!!.trim(),label="Page metadata",attachment=attachment,assetUrl=asset)
            }}
            // A mentioned article, quoted post, recommendation or ingredient is
            // not the selected post's media. Do not recursively scan arbitrary fields.
            attachmentKeys.forEach {key->values(node.get(key)).take(16).forEach {value->
                if(value.isJsonObject)visit(value.asJsonObject,depth+1)
            }}
        }
        visit(record,0)
        return found.distinct().take(32)
    }

    /** Keep the existing content choice while retaining declarations in a second exact record. */
    fun mergeExactPosts(posts: List<Post>): List<Post> {
        val chosen=LinkedHashMap<Pair<Platform,String>,Post>()
        posts.forEach {post->
            val key=post.platform to ContentIdentity.key(post.platform,post.canonicalUrl)
            val first=chosen[key]
            if(first==null)chosen[key]=post
            else {
                val declarations=(first.aiDisclosures.orEmpty()+post.aiDisclosures.orEmpty()).distinct().take(64)
                if(declarations!=first.aiDisclosures.orEmpty())chosen[key]=first.copy(aiDisclosures=declarations.takeIf {it.isNotEmpty()})
            }
        }
        return chosen.values.toList()
    }

    private fun values(value: JsonElement?): List<JsonElement> = when {
        value==null||value.isJsonNull->emptyList()
        value.isJsonArray->value.asJsonArray.take(32)
        else->listOf(value)
    }
    private fun string(value: JsonElement): String?=value.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
    /** Schema.org names these three exact enumeration members in JSON-LD.
     * Keep this mapping here: embedded IPTC and credential fields still require
     * their own vocabulary URIs, and arbitrary compact names are not evidence.
     */
    private fun sourceType(value: String?): AiUse? {
        AiMetadataValues.sourceType(value)?.let {return it}
        val raw=value?.trim() ?: return null
        val term=when {
            raw.startsWith("https://schema.org/")->raw.substringAfter("https://schema.org/")
            raw.startsWith("http://schema.org/")->raw.substringAfter("http://schema.org/")
            else->return null
        }
        return when(term) {
            "TrainedAlgorithmicMediaDigitalSource"->AiUse.GENERATED
            "CompositeWithTrainedAlgorithmicMediaDigitalSource"->AiUse.EDITED
            "CompositeSyntheticDigitalSource"->AiUse.COMPOSITE
            else->null
        }
    }
    private fun assetUrl(node: JsonObject): String?=listOf("contentUrl","url","@id").firstNotNullOfOrNull {key->
        node.get(key)?.let(::string)?.trim()?.takeIf {it.length<=8192&&Subscriptions.publicHttps(it)}
    }
}
