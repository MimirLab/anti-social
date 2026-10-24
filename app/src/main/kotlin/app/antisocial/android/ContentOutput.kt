/* anti social, GPL-3.0-or-later. Worker output is untrusted input too. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.*

/** Gson bypasses Kotlin constructors and can turn an unknown enum into null.
 * Validate JSON types before creating a model that the native UI will consume. */
internal object ContentOutput {
    private val gson=Gson()
    private val kinds=ContextArticleBlockKind.entries.map {it.name}.toSet()
    private fun obj(value:JsonElement):JsonObject {require(value.isJsonObject);return value.asJsonObject}
    private fun string(o:JsonObject,key:String,max:Int,optional:Boolean=false):String? {
        val v=o[key];if(optional&&(v==null||v.isJsonNull))return null
        require(v!=null&&v.isJsonPrimitive&&v.asJsonPrimitive.isString)
        return v.asString.also {require(it.length<=max)}
    }
    private fun number(o:JsonObject,key:String):Long {
        val v=o[key];require(v?.isJsonPrimitive==true&&v.asJsonPrimitive.isNumber)
        return v.asString.toLongOrNull() ?: throw IllegalArgumentException("Invalid worker number")
    }
    private fun bool(o:JsonObject,key:String) {val v=o[key];require(v?.isJsonPrimitive==true&&v.asJsonPrimitive.isBoolean)}
    fun article(value:String):ContextArticleEvidence {
        val o=obj(JsonParser.parseString(value))
        string(o,"originalUrl",65536);string(o,"finalUrl",65536);string(o,"title",1000)
        string(o,"author",300,true);string(o,"siteName",200,true);number(o,"fetchedAt");bool(o,"truncated")
        string(o,"publicationDoi",512,true)
        o["publishedAt"]?.takeUnless {it.isJsonNull}?.let {number(o,"publishedAt")}
        o["access"]?.takeUnless {it.isJsonNull}?.let {value->
            val access=obj(value);string(access,"route",128);string(access,"originalUrl",65536);string(access,"retrievedUrl",65536)
            string(access,"version",80,true);string(access,"license",4096,true);bool(access,"retracted")
            access["archivedAt"]?.takeUnless {it.isJsonNull}?.let {number(access,"archivedAt")}
        }
        val blocks=o["blocks"];require(blocks?.isJsonArray==true&&blocks.asJsonArray.size()<=30_000)
        var total=0L
        for(entry in blocks.asJsonArray) {
            val block=obj(entry);total+=string(block,"text",ContextArticles.MAX_TEXT_CHARS)!!.length
            require(string(block,"kind",32) in kinds&&number(block,"headingLevel") in 0..6&&number(block,"listDepth") in 0..512)
            string(block,"listMarker",64,true)
            val links=block["links"];require(links?.isJsonArray==true&&links.asJsonArray.size()<=32)
            require(links.asJsonArray.all {it.isJsonPrimitive&&it.asJsonPrimitive.isString&&it.asString.length<=65536})
        }
        require(total<=ContextArticles.MAX_TEXT_CHARS+1000)
        o["references"]?.takeUnless {it.isJsonNull}?.let {refs->
            require(refs.isJsonArray&&refs.asJsonArray.size()<=12)
            for(entry in refs.asJsonArray) {val ref=obj(entry);string(ref,"url",65536);string(ref,"label",240);string(ref,"reason",1000);string(ref,"passage",1000);require(number(ref,"priority") in -1000..1000)}
        }
        return gson.fromJson(o,ContextArticleEvidence::class.java)
    }
    fun pdf(value:String):ContextPdfPageResult {
        val o=obj(JsonParser.parseString(value));number(o,"pageIndex");number(o,"pageCount");string(o,"text",80_000)
        for(key in listOf("ocr","uncertain","available","truncated"))bool(o,key)
        string(o,"sourceSha256",64);string(o,"reason",1000,true)
        return gson.fromJson(o,ContextPdfPageResult::class.java)
    }
}
